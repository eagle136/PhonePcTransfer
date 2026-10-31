package com.phonepctransfer.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phonepctransfer.AppCore
import com.phonepctransfer.net.AvTransport
import com.phonepctransfer.net.Link
import com.phonepctransfer.util.CallClock
import com.phonepctransfer.util.CallCounters
import com.phonepctransfer.util.SegMeter
import com.phonepctransfer.video.VideoCall
import com.phonepctransfer.video.VideoLevel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通话状态机（Android 端，与 PC 端 CallManager.java 同构）。
 *
 * 全局**同一时刻最多一个通话**：任何新的呼出请求在已有通话时直接拒绝（不入队），
 * 任何新的呼入在已有通话时回 `call_busy` 静默拒绝（不打扰用户）。
 *
 * 状态：
 *  IDLE → OUTGOING（呼出，等对方接） → TALKING
 *  IDLE → INCOMING（呼入，弹窗等用户选） → TALKING
 *  TALKING → 任一方挂断 / 6 小时超时 → IDLE
 *
 * 音频：TALKING 状态下，VoiceEngine 采集编码经 Link 发出；收到对端音频交 VoiceEngine 播放。
 * 视频（2026-09-15 新增）：`videoMode` 为真时，Android 端在 TALKING 后启动
 * [VideoCall] 的发送/接收半；档位协商在 invite/accept 里完成（两端取 min）。
 */
class CallManager(private val link: Link, private val ctx: Context) {

    companion object {
        private const val TAG = "PP-Call"

        /** 单次通话最长 6 小时，到时强制挂断 */
        const val MAX_CALL_MS = 6L * 3600 * 1000

        /** 呼出等待接听超时（60s 无人接自动取消） */
        const val RING_TIMEOUT_MS = 60_000L
    }

    enum class State { IDLE, OUTGOING, INCOMING, TALKING }

    interface Listener {
        /** 通话状态变化（UI 据此显示/隐藏「正在通话」条、弹窗） */
        fun onStateChanged(state: State, peerIp: String, peerName: String)

        /**
         * 收到呼入请求，需要弹窗显示对方 IP + 备注，让用户同意/拒绝。
         * `vid=true` 时 UI 应把标题写成「视频来电」（默认值保证老调用方仍可编译）。
         */
        fun onIncomingCall(peerIp: String, peerName: String, vid: Boolean = false)

        /** 通话已结束（正常/超时/拒绝/取消），UI 收尾 */
        fun onCallEnded(peerIp: String, reason: String) {}

        /**
         * 通话已结束（v2.7 起带通话形态与时长，供上层**以消息方式记录**）。
         * 默认实现回落到上面的两参版本，老实现方不会漏收结束事件。
         *
         * @param video   本次是否视频通话
         * @param durationMs 实际通话时长（未接通为 0）
         * @param outgoing true = 本机发起
         */
        fun onCallEnded(
            peerIp: String, reason: String, video: Boolean, durationMs: Long, outgoing: Boolean
        ) {
            onCallEnded(peerIp, reason)
        }

        /**
         * 2.9 / S23：通话中的**媒体侧提示**（"疑似被拦"这一类：只提示，不改变任何传输方式）。
         * 默认空实现 ⇒ 老实现方不漏收；UI 侧要显示就覆盖它（本单由 AppCore 接到短提示 Toast）。
         * ⚠ 可能在非主线程被调（pp-av-alive）⇒ 实现方自己 marshal，与 onStateChanged 同一约定。
         */
        fun onMediaNotice(text: String) {}
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    var state: State = State.IDLE
        private set

    /** 当前通话对端 IP（空 = 无通话） */
    @Volatile
    var peerIp: String = ""
        private set

    /** 当前通话对端显示名（备注 > 广播名 > IP） */
    @Volatile
    var peerName: String = ""
        private set

    /** 本次通话是否视频通话（协商结果） */
    @Volatile
    var videoMode: Boolean = false
        private set

    /** 协商后的档位（两端取 min） */
    @Volatile
    var level: VideoLevel = VideoLevel.DEFAULT
        private set

    /**
     * 本机允许的最高档位（由 AppCore 从 Prefs 注入；这里的不回退值 = [VideoLevel.MAX].idx，
     * 即"注入前不设限"。写 DEFAULT 会让没来得及注入的那一通把超清夹成高清）。
     */
    @Volatile
    var maxVideoLevelIdx: Int = VideoLevel.MAX.idx

    /** 通话开始时刻（TALKING 起点，用于计时与 6 小时超时） */
    @Volatile
    private var talkStartMs = 0L

    /** 呼出/呼入协商中的视频意愿（TALKING 前暂存） */
    @Volatile
    private var pendingVideo = false

    @Volatile
    private var pendingLevelIdx = VideoLevel.DEFAULT.idx

    /**
     * 对端声明的**语音帧长**（ms，来自 `call_invite`/`call_accept` 的 `af` 字段）。
     *
     * **0 = 对端是老版本**（没有这个字段）→ 本次通话用 200ms 兼容模式。
     * 帧长是收发硬对称常量，所以绝不能「本端单方面用 20ms」。
     */
    @Volatile
    private var pendingAfMs = 0

    /**
     * 对端在 `call_invite`/`call_accept` 里声明的**媒体通道三元组**（任务单 2.4 / 方案 §4.2）。
     * 与 [pendingAfMs] 同一生命周期：每次振铃/应答覆盖，不单独复位。
     * 缺省值 0 / false / 空 = 对端未声明（老端或老口径）→ 2.5 的定档时序只能落带内档。
     * ⚠ msid 只做**串话隔离**，不是安全边界（S12）；谁生成、谁回显由 2.5 定（本单只落 wire 字段）。
     */
    @Volatile
    private var peerMport = 0

    @Volatile
    private var peerMudp = false

    @Volatile
    private var peerMsid = ""

    private val main = Handler(Looper.getMainLooper())

    private val voice = VoiceEngine()

    /** 视频会话编排（发送半 + 接收半）。UI 通过它挂 Surface。 */
    val video: VideoCall = VideoCall(
        ctx,
        link,
        onLog = { AppCore.appendLogPublic(it) },
        onError = { msg ->
            AppCore.appendLogPublic("视频：$msg")
            Log.w(TAG, "视频错误: $msg")
        },
        onStats = { }
    )

    private val busy = AtomicBoolean(false)

    /**
     * 媒体传输模块（任务单 2.1；D2「一通电话一个媒体会话」）。
     * 与 PC 端 CallManager.java 同一时机：呼叫一开（振铃期）就建，endCall 里关。
     * 上层只调 start / sendAudio / sendVideo / sendCtrl / stop 这五个方法；
     * 底下是五档里的哪一档由模块内部决定，通话层既不读取也不据此分支（D3 / I17）。
     */
    @Volatile
    private var av: AvTransport? = null

    /**
     * 定档时序**是否已跑完**（任务单 2.5）。与 [pendingStartTalking] 成对：
     * §4.3 要求七步全在振铃期完成，而"进 TALKING"的两个触发点（用户点接听 / 对端应答）
     * 和时序结论谁先到都不一定 ⇒ 两边在这里会面。
     */
    @Volatile
    private var negotiated = false

    /** 用户已点接听（或已收到 call_accept），但定档结论还没回来 ⇒ 等 onNegotiated 补进 TALKING。 */
    @Volatile
    private var pendingStartTalking = false

    /** 模块的收帧出口（过滤口径与改前逐字一致：音频按 state＋源地址，视频半交给 VideoCall 自己判源） */
    private val avSink = object : AvTransport.Sink {
        override fun onAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {
            handleInboundAudio(fromIp, seq, ts, data, len)
        }

        override fun onVideo(fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {
            handleInboundVideo(fromIp, seq, ts, flag, data, len)
        }

        /**
         * 2.9 / S23：模块判定"本档 1s 一条反馈都没收到"（**上升沿**，一秒最多一次）。
         * 处置口径是写死的：**只提示、不换档、不换端口、不重开探测**（I17），
         * 所以这里除把话交给 UI 以外不做任何动作 —— 通话层不许读 tier 做分支（D3）。
         */
        override fun onMediaSuspectedBlocked(text: String) {
            val l = listener ?: return
            // 与 PC 同构：这里**不**切线程 —— 提示不是状态迁移，交给 UI 实现方按既有约定 marshal
            if (state == State.TALKING) l.onMediaNotice(text)
        }

        /**
         * 2.9 / P10（2026-10-02 用户认可的默认处置）：媒体通道彻底无流达 10s ⇒ **自动挂断 + 提示**。
         * ⚠ "媒体存活由通话状态机决定"（I14）⇒ 挂断动作在这里，不在 AvTransport 里。
         * ⚠ 线程：回调来自 pp-av-alive，而安卓这套状态机的约定是"一切迁移走 main"（同 onNegotiated）
         *   ⇒ 整段投到 main 上做，state/peerIp 的重查也放在 main 里，迟到的回调不会复活上一通。
         */
        override fun onMediaNoStream() {
            main.post {
                val ip = peerIp
                if (state != State.TALKING || ip.isEmpty()) return@post
                Log.i(TAG, "媒体通道无流达 ${AvTransport.NO_STREAM_HANGUP_MS}ms ⇒ 按 P10 自动挂断")
                link.sendCallEnd(ip, "")
                endCall(ip, "呼叫中断：媒体通道无数据")
            }
        }
    }

    /**
     * 建本次通话的媒体会话。2.5 起这一步就把**第 0 档 bind 好**（§4.3①「先 bind 出本档端口，
     * 再交换 mport/mudp/msid」——端口要随 invite/accept 出去），并由调用方紧接着发 invite/accept、
     * 再 [startNegotiation] 把七步时序跑完。
     *
     * @param caller true = 本端是主叫（媒体 TCP 档由主叫发起连接，见 AvTransport 2.5 块「我的推论③」）
     */
    private fun openTransport(ip: String, caller: Boolean) {
        val t = AvTransport(link, avSink)
        // 三元组的本端一份（localMsid，每端各自生成 —— 2026-10-02 用户点）+ 对端一份
        // （主叫此刻还没有 ⇒ 0/false/空；被叫就是 invite 里带进来的那一份，2.4 的字段从本条起有读者）
        t.start(
            AvTransport.Spec(
                ip, randomMsid(),
                if (caller) "" else peerMsid,
                if (caller) 0 else peerMport,
                if (caller) false else peerMudp,
                caller
            )
        )
        // 2.10 / P6 + S14：媒体 TCP **被叫侧 accept** 的准入判据（三条件里的前三样在本机的表上，
        // msid 那一半在模块里 —— AvTransport.admitMediaInbound，两边**与**起来才放行）。
        // ⚠ 每条都当场读 volatile 状态，不是在 openTransport 时刻快照：时序跑在 pp-av-negotiate 上，
        //   跑到 ④ 时本通可能已经挂了，那时必须判为"不在通话中"。
        // ⚠ **没有**动 link.incomingGate（AppCore.kt:437 那条 2026-10-01 真机验证过的后台闸门）：
        //   它管的是消息端口 28765 的 accept，媒体监听器是模块自己 bind 的、根本不经过它
        //   ⇒ S14 那句"不能整条关掉 incomingGate"因此天然成立。
        t.mediaInboundAdmit = { rip ->
            val pf = link.prefs
            val tr = link.trusted
            val pip = peerIp
            state != State.IDLE && rip.isNotEmpty() && tr != null
                // ②b 既有网段黑名单：高风险段 + 本机设备级标记（与 adopt 那道同源）
                && !com.phonepctransfer.net.BlocklistIpv6.isHighRisk(rip) && !pf.isBlockedAddr(rip)
                // ② 已知设备。⚠ 安卓这张表按 **IP** 存（PC 按设备组键存，两端这里不同构），
                //   所以再补 D10 那一半：与通话对端**同组**的其它地址（另一块网卡，2.7 的教训）
                //   且该组已知，才算已知设备。不能只写 tr.isTrusted(rip) —— 那会把多地址设备判死；
                //   也不能写 rip == pip —— 会话地址≠界面地址。
                && (tr.isTrusted(rip) || (pip.isNotEmpty() && tr.isTrusted(pip)
                        && pf.convKeyOf(rip) == pf.convKeyOf(pip)))
        }
        av = t
        video.transport = t
        negotiated = false
        pendingStartTalking = false
    }

    /**
     * 本次通话的随机 token（串话隔离，S12；不是安全边界）。
     * 用 UUID ⇒ 不新增"token 长度/字符集"这类方案里没给的数字；36 字符塞进 probe 正文
     * 后整报 13+36=49B，仍在 §4.4 的 ctrl ≤64B 以内。
     */
    private fun randomMsid(): String = java.util.UUID.randomUUID().toString()

    /**
     * 在振铃期把 §4.3 的七步时序跑完（独立线程：每一步都可能按窗口阻塞，绝不能在 main 上跑）。
     * 结果只决定「定在哪一档 + 能不能进 TALKING」，不决定媒体帧今天怎么走（3.3 之前恒带内，
     * 见 [AvTransport.mediaUsesMainTcp]）。
     *
     * ⚠ 与 PC 端唯一差一处：结论**回投 main**（[main.post]）再做状态迁移 —— 安卓这套状态机的
     *   约定是「一切迁移走 ui{} / main 线程」（见 AppCore 的 listener 转发），而 PC 端
     *   CallManager 的方法本身没有线程域约束。时序线程自己绝不碰 voice / video。
     */
    private fun startNegotiation() {
        val t = av ?: return
        val ip = peerIp
        if (ip.isEmpty()) return
        Thread({
            val ok = try {
                t.negotiate()
            } catch (e: Throwable) {
                false
            }
            main.post { onNegotiated(ip, ok) }
        }, "pp-av-negotiate").apply { isDaemon = true }.start()
    }

    /** 时序跑完（或 ⑦ 判失败）。与 [requestStartTalking] 同一把锁，两边谁先到都不会漏。 */
    @Synchronized
    private fun onNegotiated(ip: String, ok: Boolean) {
        if (state == State.IDLE || ip != peerIp) {
            return   // 这一通已经结束了（挂断 / 超时 / 对端取消都会叫停时序），不要复活它
        }
        if (ok && mediaDowngraded) {
            // 2.11 / §4.3⑥：UI **明示**降级（不是只写日志）。走 2.9 那条 onMediaNotice ——
            // 它本来就是"媒体侧提示"的通道，不再为降级另立第三个回调（D3：上层只读标志位，不读 tier）。
            // ⚠ 一次通话只跑一次时序（startNegotiation 的调用点是振铃期那两处）⇒ 天然不重复提示，
            //   所以没有另设"提示过没有"的闩锁（1.6 的口径）。本方法已经在 main 上（onNegotiated 的约定）。
            listener?.onMediaNotice(
                "本次通话媒体已降级：走消息端口的带内通道（第五档）。" +
                        "通话期间文件不发送、聚合恒关（D16 / I13）"
            )
        }
        negotiated = true
        if (!ok) {
            // ⑦（U11②，2026-10-02 用户点）：带内也不成立 ⇒ 提示呼叫失败，**不进 TALKING**
            when (state) {
                State.OUTGOING -> {
                    link.sendCallCancel(ip)
                    endCall(ip, "无法与对方建立媒体通道，呼叫失败")
                }
                State.INCOMING -> {
                    link.sendCallReject(ip)
                    endCall(ip, "本机无法与对方建立媒体通道")
                }
                else -> Log.i(TAG, "媒体定档失败但已在通话中（state=$state），只记录不切换")
            }
            return
        }
        if (pendingStartTalking) {
            pendingStartTalking = false
            startTalking()
        }
    }

    /**
     * 接听 / 对端应答 ⇒ 进 TALKING。⚠ §4.3 要求定档**全程在振铃期完成**，而 call_accept
     * 的语义就是「我接了」（收到即 startTalking）⇒ 本方法只把"要接听"这个意图记下来，
     * call_accept 由 [acceptIncoming] / [onAccept] 照发，进入 TALKING 这一步等定档结论：
     * 时序先跑完 ⇒ 就地进；用户先点接听 ⇒ 由 onNegotiated 补进（最坏等一个窗口）。
     */
    @Synchronized
    private fun requestStartTalking() {
        if (negotiated) {
            startTalking()
        } else {
            pendingStartTalking = true
        }
    }

    /**
     * 关本次通话的媒体会话（endCall 与"invite 都没发出去"这一条失败分支都走这里）。
     * 可重入：重复调用不出异常（任务单 2.9 的五条退出路径最终都汇聚到这一个 close）。
     */
    private fun closeTransport() {
        val t = av
        av = null
        video.transport = null
        t?.stop()
    }

    /**
     * 2.11：本次通话是否**落到带内档**（第五档 = 媒体跑在主 TCP 的消息端口上）。
     * 上层只读这一个标志，**不读 tier**（D3：五档互换时通话层零改动）；
     * UI 的降级文案（ChatScreen 的通话条）与 2.12 的文件闸门读的都是它。
     * ⚠ av 为 null（还没 openTransport / 已 closeTransport）时为 false：
     *   那时本通没有媒体结论可展示，不能把"未定档"显示成"已降级"。
     */
    val mediaDowngraded: Boolean
        get() = av?.mediaDowngradedToInband() == true

    /**
     * 纯语音通话的 Opus 码率自适应（64k ⇄ 32k，见 [VoiceBitrateController]）。
     * **只在 `!videoMode` 时启用** —— 视频通话的音频码率跟随视频档位。
     */
    @Volatile
    private var voiceQuality: VoiceBitrateController? = null

    /** 语音码率自适应采样线程（每 2s 一次） */
    @Volatile
    private var aqThread: Thread? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    fun isBusy(): Boolean = state != State.IDLE

    fun isTalking(): Boolean = state == State.TALKING

    fun isMuted(): Boolean = voice.isMuted

    /** 当前通话已持续毫秒数（TALKING 才有效） */
    // nowMs 由 UI 每 500ms 传入（LaunchedEffect 驱动）：elapsedMs 本身读的不是快照状态，
    // 若 UI 直接调用 System.currentTimeMillis() 版本，重组只发生一次、计时会冻结在 00:00。
    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long =
        if (state == State.TALKING && talkStartMs > 0) nowMs - talkStartMs else 0L

    // ==================== 呼出 ====================

    /**
     * 发起呼叫（发给当前对话设备）。
     * 已有通话时静默拒绝（返回 false，不弹任何提示）。
     *
     * @param video    true = 视频呼叫（只有本机有摄像头且对端声明 vid=1 才有意义）
     * @param levelIdx 呼叫前用户选的**画质上限档位**（0 流畅 480P / 1 标清 720P /
     *                 2 高清 1080P/2M / 3 超清 1080P/5M）。本机上限与对端上限都会再取一次
     *                 min；通话中自动降档，回升不越过这里选的档（起始仍最高 2M，见 [startVideo]）。
     */
    fun startOutgoing(video: Boolean = false, levelIdx: Int = VideoLevel.DEFAULT.idx): Boolean {
        if (isBusy()) {
            Log.i(TAG, "已有通话进行中，拒绝新的呼出请求")
            return false
        }
        val ip = link.currentPeerIp()
        if (ip.isEmpty()) {
            AppCore.appendLogPublic("尚未选择目标设备，无法发起通话")
            return false
        }
        // 本机界面显示名（可能含**用户私有备注**）只留在本机；
        // 发到对端显示的必须是**本机设备名**（见 AppCore.myDeviceName）——
        // 备注是「仅自己可见」的，不能顺着 call_invite 泄给对方。
        val localName = AppCore.peerLabelOf(ip)
        val name = AppCore.myDeviceName
        if (name.isBlank()) return false

        // 视频呼叫前置校验：对端必须声明可收视频（否则老端收到 0x03 会断链）
        val wantVideo = video && link.peerVidCap(ip)
        if (video && !wantVideo) {
            AppCore.appendLogPublic("对端不支持视频接收，本次按语音呼叫发起")
        }
        val lv = minOf(levelIdx, maxVideoLevelIdx).coerceIn(0, VideoLevel.MAX.idx)
        pendingVideo = wantVideo
        pendingLevelIdx = lv

        // 媒体会话先建再发 invite：任务单 2.4/2.5 要在 invite 里带上本端 bind 出来的媒体端口，
        // 所以定档必须早于协商报文（振铃期完成，§4.3）。
        // invite 发不出去 ⇒ 就地关掉，不留半个已 bind 的会话（2.9 的"呼叫失败也必须释放"从这条分支开始成立）。
        openTransport(ip, true)
        // 末尾三项 = 本端第 0 档的媒体通道三元组（§4.3①：start() 已把这一档 bind 好，
        // 端口在这里带出去，2.4 的 wire 字段从本条起有真实值）。bind 不成立 ⇒ 0/false，对端落带内档。
        val t0 = av
        if (!link.sendCallInvite(
                name, AppCore.localIp, wantVideo, lv, VoiceEngine.VOICE_FRAME_MS_LOWLAT,
                t0?.mediaPort() ?: 0, t0?.mediaIsUdp() == true, t0?.localMsid() ?: ""
            )
        ) {
            AppCore.appendLogPublic("设备 $name 未连接，无法发起通话")
            closeTransport()
            return false
        }
        peerIp = ip
        peerName = localName
        setState(State.OUTGOING)
        // 七步时序在振铃期跑（独立线程，§4.3）；结果由 onNegotiated 回 main 处理
        startNegotiation()
        main.postDelayed({
            if (state == State.OUTGOING && peerIp == ip) {
                // 等待接听超时，自动取消
                link.sendCallCancel(ip)
                endCall(ip, "无人接听")
            }
        }, RING_TIMEOUT_MS)
        return true
    }

    /** 呼出方在对方接听前主动取消 */
    fun cancelOutgoing() {
        if (state != State.OUTGOING) return
        val ip = peerIp
        link.sendCallCancel(ip)
        endCall(ip, "已取消")
    }

    // ==================== 呼入 ====================

    /**
     * 收到呼入请求（由 Link.listener 转发；带视频协商 + 语音帧长协商 + 媒体通道三元组）。
     *
     * @param mport 呼叫方的媒体端口；0 = 未声明 ⇒ 本端只能落带内档
     * @param mudp  呼叫方媒体端口是否 UDP
     * @param msid  呼叫方本次通话的随机 token；空 = 未声明
     */
    fun onIncoming(
        peerIp: String, name: String, vid: Boolean = false,
        lv: Int = VideoLevel.DEFAULT.idx, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ) {
        if (isBusy()) {
            // 已有通话：静默回 busy，不打扰
            Log.i(TAG, "忙：静默拒绝来自 $peerIp 的呼入")
            link.sendCallBusy(peerIp)
            return
        }
        this.peerIp = peerIp
        // 来电人名字：**优先本机对该设备的备注**（备注仅自己可见、也最贴近用户认知），
        // 本机没备注时才用对端在 call_invite 里自报的设备名（不会再出现"我的备注被对方看到"）。
        val local = AppCore.peerLabelOf(peerIp)
        this.peerName = if (local.isNotBlank() && local != peerIp) local else name.ifBlank { peerIp }
        // 是否接受视频：对端要发视频，且本机确实能收（Android 恒能收；PC 端同理）
        pendingVideo = vid && link.localVidCap
        pendingLevelIdx = minOf(lv, maxVideoLevelIdx)
        pendingAfMs = af
        peerMport = mport
        peerMudp = mudp
        peerMsid = msid
        // 被叫侧也在**振铃期**建媒体会话并起时序（任务单 2.5；原先这一步在 acceptIncoming 里，
        // 那时无条件回 call_accept ⇒ 端口只能广告"未声明"，七步时序没地方跑）。
        // 未接听的来电在 endCall（拒绝 / 对方取消 / 超时）里由 closeTransport 释放端口。
        openTransport(peerIp, false)
        setState(State.INCOMING)
        startNegotiation()
        startRinger()
        listener?.onIncomingCall(peerIp, this.peerName, pendingVideo)
    }

    /** 用户点「同意」 */
    fun acceptIncoming() {
        if (state != State.INCOMING) return
        val ip = peerIp
        // 媒体会话与七步时序在**振铃期**就起好了（onIncoming 里 openTransport + startNegotiation，
        // 原先这一步在本方法里 ⇒ 那时还没有任何端口可广告）。这里只把当前正在广告的那一档
        // 带进 call_accept；对端随后仍会由 call_media 收到更新后的三元组（时序可能还在往后档推进）。
        val t = av
        // 回带本端期望的语音帧长：双方都声明 20ms 才会真正启用低延迟模式
        link.sendCallAccept(
            ip, pendingVideo, pendingLevelIdx, VoiceEngine.VOICE_FRAME_MS_LOWLAT,
            t?.mediaPort() ?: 0, t?.mediaIsUdp() == true, t?.localMsid() ?: ""
        )
        requestStartTalking()
    }

    /** 用户点「拒绝」 */
    fun rejectIncoming() {
        if (state != State.INCOMING) return
        val ip = peerIp
        link.sendCallReject(ip)
        endCall(ip, "对方拒绝")
    }

    // ==================== 对端应答 ====================

    /**
     * 对端接听（带视频应答与语音帧长；档位两端取 min → 最终档位）。
     * 末尾三项 = 对端声明的媒体通道三元组，语义同 [onIncoming] 的同名参数。
     */
    fun onAccept(
        peerIp: String, vid: Boolean = false,
        lv: Int = VideoLevel.DEFAULT.idx, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ) {
        if (state == State.OUTGOING && this.peerIp == peerIp) {
            // 本端提议 pendingVideo；对端回 vid=false 则本次退回纯语音
            pendingVideo = pendingVideo && vid
            pendingLevelIdx = minOf(pendingLevelIdx, lv)
            pendingAfMs = af
            peerMport = mport
            peerMudp = mudp
            peerMsid = msid
            // 主叫侧此刻才拿到被叫的三元组（invite 时还没有）⇒ 喂给媒体模块，
            // 振铃期时序后面每一档的 probe/媒体 TCP 连接都打这个端口。
            // accept 帧里**没有** mtier（§4.3① 之后由 call_media 逐档交换）⇒ 传 -1「未声明」。
            av?.offerPeerTriple(mport, mudp, msid, -1)
            // §4.3：定档全程在振铃期完成，"进入通话"要等时序结论（见 requestStartTalking 的说明）
            requestStartTalking()
        }
    }

    /**
     * 对端在振铃期逐档交换过来的媒体三元组（任务单 2.5 / 方案 §4.3①，2026-10-02 用户点的 (a) 口径）。
     *
     * 这是**唯一**能在时序进行中更新「对端此刻听哪个端口」的通道：accept 只有一帧，
     * 而五档一轮一轮地推进，每一轮本端端口都会变（§4.1：逐档换端口、不复用）。
     * mtier 只进日志，不参与定档判断 —— 双端的档位清单长度不同（安卓滤掉两条 443 档），
     * 序号无法对齐，能对齐的是**协议类**（UDP 对 UDP、媒体 TCP 对媒体 TCP）。
     */
    fun onCallMedia(peerIp: String, mport: Int, mudp: Boolean, msid: String, mtier: Int) {
        if (state == State.IDLE || this.peerIp != peerIp) {
            return   // 不属于本通的协商帧（已挂断 / 串到了别的对端）直接丢
        }
        peerMport = mport
        peerMudp = mudp
        peerMsid = msid
        av?.offerPeerTriple(mport, mudp, msid, mtier)
    }

    fun onReject(peerIp: String) {
        if ((state == State.OUTGOING || state == State.INCOMING) && this.peerIp == peerIp) {
            endCall(peerIp, "对方拒绝")
        }
    }

    fun onBusy(peerIp: String) {
        if (state == State.OUTGOING && this.peerIp == peerIp) {
            endCall(peerIp, "对方忙")
        }
    }

    fun onCancel(peerIp: String) {
        if (state == State.INCOMING && this.peerIp == peerIp) {
            endCall(peerIp, "对方取消")
        }
    }

    // ==================== 挂断 ====================

    /** 本端主动挂断（通话中） */
    fun hangup() {
        if (state != State.TALKING) return
        val ip = peerIp
        link.sendCallEnd(ip, "")
        endCall(ip, "已挂断")
    }

    /** 收到对端挂断 */
    fun onRemoteEnd(peerIp: String, reason: String) {
        if (this.peerIp == peerIp && (state == State.TALKING || state == State.OUTGOING)) {
            val text = when (reason) {
                "" -> "对方已挂断"
                "record_fail" -> "对方无法访问麦克风，通话被取消"
                "timeout" -> "对方通话已达时长上限"
                else -> reason
            }
            endCall(peerIp, text)
        }
    }

    /**
     * 会话层发现「通话对端的健康会话已经不在了」时调用（Link 掉线 → AppCore 转发）。
     *
     * 必须显式处理：`link` 掉线只走 `onState/onPeersChanged`，CallManager 不挂在那条链路上，
     * 没人通知就会永远停在 TALKING（最坏 6 小时）—— 表现为计时条一直转、「挂断」之外
     * 什么都不响应，且「视频」按钮因 `callState != IDLE` **恒久置灰**
     * （2026-09-15 实测：手机互打时按钮点不动，与 PC 通话却正常）。
     *
     * 不做静默变 IDLE：必须给文案，否则用户感知是「用了就自己断了」。
     * 两端对称 —— 对端会话掉线时，本端也会各自走到这里。
     */
    fun onPeerDisconnected(ip: String) {
        if (state == State.IDLE) return
        if (peerIp != ip) return
        val text = if (state == State.OUTGOING) "设备已断开，呼叫已取消" else "连接已断开，通话结束"
        Log.i(TAG, "通话对端 $ip 会话已消失（state=$state）→ 结束通话")
        endCall(ip, text)
    }

    // ==================== 音频 / 视频 ====================

    /** 收到对端音频（Link 读线程回调）⇒ 交给媒体模块分发（任务单 2.1）。ts = 对端通话相对毫秒 */
    fun onAudio(peerIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {
        av?.deliverAudio(peerIp, seq, ts, data, len)
    }

    /** 模块收帧回调（守卫与改前 onAudio 逐字一致） */
    private fun handleInboundAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {
        if (state != State.TALKING || this.peerIp != fromIp) return
        voice.onAudioReceived(seq, ts, data, len)
    }

    /** 收到对端视频（Link 读线程）⇒ 交给媒体模块分发 */
    fun onVideoFrame(peerIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {
        av?.deliverVideo(peerIp, seq, ts, flag, data, len)
    }

    /** 模块收帧回调（守卫与改前 onVideoFrame 逐字一致：源地址仍由 VideoCall 首源锁定处理，2.7 统一） */
    private fun handleInboundVideo(
        fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int
    ) {
        if (state != State.TALKING || !videoMode) return
        video.onRemoteFrame(fromIp, seq, ts, flag, data, len)
    }

    /** @param front 对端摄像头是否前置（决定「对方画面」默认显示角，前置默认多 180°） */
    fun onVideoCfg(
        peerIp: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int, front: Boolean
    ) {
        if (state != State.TALKING || !videoMode) return
        video.onRemoteCfg(peerIp, codec, w, h, fps, br, rot, front)
    }

    fun onVideoFb(
        peerIp: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int
    ) {
        if (state != State.TALKING || !videoMode) return
        video.onRemoteFb(fps, drop, lat, dropping, kbps)
    }

    fun onVideoReq(peerIp: String, on: Boolean, key: Boolean) {
        if (state != State.TALKING || !videoMode) return
        video.onRemoteReq(on)
        // 对端解码器刚重建（换尺寸 / 换进程）：立刻给一个 IDR，别让它等满 1s GOP
        if (key) video.onRemoteKeyRequest()
    }

    /** 切换前后摄（R8） */
    fun switchCamera(): Boolean = if (videoMode && state == State.TALKING) video.switchCamera() else false

    /** 静音开关（只停发，不发静音包；对端按 seq 跳变补静音保持时间轴） */
    fun toggleMute(): Boolean {
        val m = !voice.isMuted
        voice.setMuted(m)
        return m
    }

    // ==================== 音频输出路由（媒体 ⇄ 通话通道 + 免提/听筒） ====================

    /**
     * 输出通道（2026-09-16 用户定调）：**每次通话默认「媒体通道」**——
     * 播放轨用 USAGE_MEDIA（媒体音量、音量大），AudioManager 保持 MODE_NORMAL，
     * 输出设备由系统按媒体路由决定：**插着有线/蓝牙耳机就走耳机，手机不外放**。
     * 可切「电话通道」：MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION（通话音量），
     * 无耳机时默认免提（扬声器），可切听筒。
     */
    enum class AudioChannel { MEDIA, CALL }

    @Volatile
    var audioChannel: AudioChannel = AudioChannel.MEDIA
        private set

    /** 免提开关（仅电话通道下有意义；媒体通道的输出设备跟随系统路由） */
    @Volatile
    var speakerOn: Boolean = true
        private set

    private val audio: AudioManager
        get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前是否插着外接音频输出（有线 / USB 耳机 / 蓝牙音频） */
    private fun headsetActive(): Boolean = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    /** 应用当前的路由（mode + 通信设备选择）。可在任意线程调用（AudioManager 线程安全）。 */
    private fun applyAudioRoute() {
        runCatching {
            if (audioChannel == AudioChannel.MEDIA) {
                // 媒体通道：mode 回 NORMAL、不碰通信设备 —— 输出由 USAGE_MEDIA 流
                // 按系统媒体路由走（耳机优先，手机不外放），音量键调媒体音量。
                audio.mode = AudioManager.MODE_NORMAL
                return@runCatching
            }
            // 电话通道：MODE_IN_COMMUNICATION
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (headsetActive()) {
                // 耳机在播：**绝不强制扬声器**（否则耳机插着手机还外放），路由跟随耳机
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.clearCommunicationDevice()
                }
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
                return@runCatching
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val spk = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (spk != null) {
                    audio.setCommunicationDevice(spk)
                } else {
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = speakerOn
                }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speakerOn
            }
        }.onFailure { Log.w(TAG, "音频路由设置失败: ${it.message}") }
    }

    /** 恢复系统默认（通话结束调用）：清通信设备选择 + mode 回 NORMAL */
    private fun restoreAudioRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    /** 通话 UI 展示用：当前是否媒体通道 */
    val isMediaChannel: Boolean get() = audioChannel == AudioChannel.MEDIA

    /**
     * 切换媒体 ⇄ 电话通道（通话 UI 按钮）。切换时 VoiceEngine 重建播放轨
     * （usage 换向），只影响本端播放，不影响对端。
     */
    fun setAudioChannel(media: Boolean) {
        val ch = if (media) AudioChannel.MEDIA else AudioChannel.CALL
        if (ch == audioChannel) return
        audioChannel = ch
        voice.setMediaOutput(media)
        applyAudioRoute()
        Log.i(TAG, "音频通道切换 → ${if (media) "媒体" else "电话"}")
    }

    /** 免提 ⇄ 听筒切换（**仅电话通道**下通话 UI 提供按钮）。返回切换后的免提状态。 */
    fun toggleSpeaker(): Boolean {
        speakerOn = !speakerOn
        applyAudioRoute()
        Log.i(TAG, "音频输出切换 → ${if (speakerOn) "免提" else "听筒"}")
        return speakerOn
    }

    /** 耳机插拔 / 蓝牙连接变化：通话中重算路由（耳机在播时手机不外放） */
    fun onAudioDeviceChanged() {
        if (state == State.TALKING) {
            Log.i(TAG, "音频设备变化，重算路由（headset=${headsetActive()}，channel=$audioChannel）")
            applyAudioRoute()
        }
    }

    /** 音频设备插拔监听（startTalking 时注册一次）：通话期间自动跟随耳机插拔重算路由 */
    private val audioDeviceCb = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
            onAudioDeviceChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            onAudioDeviceChanged()
    }

    @Volatile
    private var audioCbRegistered = false

    // ==================== 内部 ====================

    /** 来电铃声（系统电话铃声，循环播放）。进入 TALKING 或通话结束即停。 */
    private fun startRinger() {
        if (ringer != null) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val mp = MediaPlayer()
            mp.setDataSource(ctx, uri)
            // USAGE_NOTIFICATION_RINGTONE → 走系统「铃声」音量（独立于媒体/通话音量），
            // 与系统来电行为一致：静音/勿扰模式下自然无声，音量键调的是铃声滑条。
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = true
            // prepareAsync：onIncoming 在主线程（ui{}）调用，同步 prepare 会卡主线程
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            ringer = mp
            Log.i(TAG, "来电铃声已启动（系统电话铃声）")
        }.onFailure { Log.w(TAG, "来电铃声播放失败: ${it.message}") }
    }

    private fun stopRinger() {
        ringer?.let { mp ->
            runCatching {
                mp.stop()
                mp.release()
            }
            Log.i(TAG, "来电铃声已停止")
        }
        ringer = null
    }

    private fun startTalking() {
        if (state != State.OUTGOING && state != State.INCOMING) return
        stopRinger()
        // 媒体帧 ts 的零点（任务单 1.1）：与 PC 端 CallManager.startTalking 同位置置零。
        // 音频与视频**共用这一个零点**，所以两端能各自做本端 A/V 对轴。
        CallClock.reset()
        // 分段延迟计量起表（任务单 1.2）：每秒一行 p50/p95/max，挂断即停表
        SegMeter.start { AppCore.appendLogPublic(it) }
        // 通话计数器清零（任务单 1.7）：计数口径 = 本次 TALKING 段，与 CallClock/SegMeter 同位置，
        // 双端同序。挂断时在 endCall 打唯一一行汇总。
        CallCounters.reset()
        talkStartMs = System.currentTimeMillis()
        videoMode = pendingVideo
        level = VideoLevel.of(minOf(pendingLevelIdx, maxVideoLevelIdx))
        setState(State.TALKING)
        // 2.12 / D16：进入 TALKING ⇒ "通话中"这一票投 1（用户点的边界：**仅接通后**，振铃期不投，
        // 所以不是 isBusy()）。此后文件与目录一律不入网、在传的在分片/成员边界暂停。
        link.setFileVote(Link.VOTE_IN_CALL, true)
        //  ⚠ 音频设备初始化（AudioRecord/AudioTrack 打开）与相机/编码器启动都可能阻塞数百毫秒~数秒，
        // 必须放到独立线程：startTalking 在 Android 由主线程调用（AppCore 的 ui{}）。
        // 直接在主线程做 = ANR；在网络读线程做 = 卡死会话（收不到对端帧 → 对端 35s 判死掉线）。
        Thread({
            if (state != State.TALKING || peerIp.isEmpty()) return@Thread

            // ① 音频先起（视频起不来也要能通话）
            //    语音帧长协商：**双方都声明 20ms 才用低延迟模式**，否则回落 200ms
            //    （帧长是收发硬对称的，单方面改会让对端时间轴错乱）。
            //    每次通话重置为默认「媒体通道」（媒体音量、自动跟随耳机），用户可切电话通道。
            audioChannel = AudioChannel.MEDIA
            speakerOn = true
            voice.setMediaOutput(true)
            if (!audioCbRegistered) {
                runCatching {
                    audio.registerAudioDeviceCallback(audioDeviceCb, Handler(Looper.getMainLooper()))
                }
                audioCbRegistered = true
            }
            applyAudioRoute()
            val afMs = if (pendingAfMs in 1..VoiceEngine.VOICE_FRAME_MS_LOWLAT) {
                VoiceEngine.VOICE_FRAME_MS_LOWLAT
            } else {
                VoiceEngine.VOICE_FRAME_MS
            }
            voice.configure(videoMode, level.audioBitrate, afMs)
            if (!videoMode) {
                AppCore.appendLogPublic(
                    if (afMs <= VoiceEngine.VOICE_FRAME_MS_LOWLAT) {
                        "语音低延迟模式：${afMs}ms/帧"
                    } else {
                        "语音兼容模式：${afMs}ms/帧（对端版本较旧，未声明低延迟）"
                    }
                )
            }
            if (!voice.startRecord()) {
                // 录音启动失败：无法通话，挂断（回到主线程改状态）。
                // 原因要写进 endCall 文案 + call_end 帧 —— 否则对端「接听后什么反应都没有」。
                Log.e(TAG, "录音启动失败，强制挂断（多为 RECORD_AUDIO 未真正授权，MIUI 常见：系统框点了允许但 appops 仍 ignore）")
                main.post {
                    if (state == State.TALKING) {
                        link.sendCallEnd(peerIp, "record_fail")
                        endCall(peerIp, "无法访问麦克风，请检查录音权限")
                    }
                }
                return@Thread
            }
            if (state != State.TALKING) {
                // 启动期间已被挂断/对方结束：直接释放设备
                voice.stopAll()
                return@Thread
            }
            voice.startPlay()
            voice.setListener(object : VoiceEngine.Listener {
                override fun onEncoded(frame: VoiceEngine.EncodedFrame) {
                    if (state == State.TALKING) {
                        // 发送耗时回传给码率自适应（TCP 写阻塞 = 链路拥塞的直接证据）
                        // ts = 通话相对毫秒（CallClock），与视频帧同零点；不再是绝对墙钟
                        val t = av
                        val w = t?.sendAudio(
                            frame.seq, CallClock.elapsedMs(), frame.data, frame.data.size
                        ) ?: -1L
                        // aTx：编码完 → 已交给传输（含抢写锁与 socket 写；失败返回 -1 也计入）
                        SegMeter.record(SegMeter.A_TX, frame.encDoneNano, System.nanoTime())
                        voiceQuality?.onFrameSent(w)
                    }
                }
            })

            // ② 视频（mode 已协商为真才起；失败只降级为语音，不挂断）
            if (videoMode) {
                startVideo()
            } else {
                // 纯语音通话：码率 64k ⇄ 32k 自适应（拥塞降、恢复升）
                startVoiceAutoQuality()
            }
        }, "pp-call-audio").apply { isDaemon = true }.start()
        // 6 小时超时定时
        main.postDelayed({
            if (state == State.TALKING) {
                Log.i(TAG, "通话超过 6 小时，强制挂断")
                link.sendCallEnd(peerIp, "timeout")
                endCall(peerIp, "通话超时")
            }
        }, MAX_CALL_MS)
    }

    /**
     * 启动视频半（在 pp-call-audio 线程里调用）。
     *
     * 谁能发：
     *  - 本机有摄像头（cam cap）**且**对端声明可收视频 → 启动采集+编码；
     *  - 否则只做接收半（对端是 PC 或本机无摄像头）。
     */
    private fun startVideo() {
        val ip = peerIp
        if (state != State.TALKING) return
        // 2026-09-16：旋转全部收敛到「本机渲染侧」（AppCore.videoRemoteRot/videoLocalRot，
        // 见 ChatScreen.applyRemote/LocalTransform），不注入采集侧、不污染发出的 video_cfg.rot。
        val peerCanRecv = link.peerVidCap(ip)
        val localCanSend = link.localCamCap
        if (peerCanRecv && localCanSend) {
            // 用户本次选的档 = **上限**（QualityController 的 maxLevel，自动回升不许越过它，
            // 10-03 用户点）；而**起始**码率最高只到推荐档 [VideoLevel.DEFAULT]（= 高清 2M）：
            // 选「超清 5M」也先按 2M 出帧，等 QualityController 的五重升档闸门全满足才升到 5M。
            // 这样"超清"不会一上就把发端单 tick 的 256 片预算顶满（2M 档实测最大 IDR=143KB≈121 片）。
            video.maxLevelIdx = level.idx
            val sendStart = if (level.idx > VideoLevel.DEFAULT.idx) VideoLevel.DEFAULT else level
            if (!video.startSending(ip, sendStart)) {
                AppCore.appendLogPublic("视频发送启动失败，本次通话降级为语音")
            }
        } else {
            AppCore.appendLogPublic(
                if (!localCanSend) "本机无摄像头，只接收视频" else "对端不能收视频，只接收其画面"
            )
        }
    }

    /**
     * 纯语音通话的 Opus 码率自适应采样（每 2s 一次）。
     *
     * 只做两件事：读统计（发送耗时 / 播放队列积压 / 缺帧增量）+ 调 Opus 码率。
     * **不碰 socket 写、不碰 UI、不碰解码器**，所以可安全跑在自己的 `pp-voice-aq` 线程上。
     *
     * 退出条件：通话结束（state != TALKING）或本次通话变成视频通话（码率改由档位管）。
     */
    private fun startVoiceAutoQuality() {
        var ctrl = voiceQuality
        if (ctrl == null) {
            ctrl = VoiceBitrateController { b ->
                AppCore.appendLogPublic("语音码率自适应 → ${b / 1000}k")
            }
            ctrl.reset()
            voiceQuality = ctrl
        }
        if (aqThread?.isAlive == true) return
        val t = Thread({
            // 统计从零开始（在本线程内先重置再读基准，避免与接收线程抢跑）
            voice.resetStats()
            var lastReceived = voice.receivedFrames
            var lastMissed = voice.missedFrames
            while (state == State.TALKING && !videoMode) {
                try {
                    Thread.sleep(VoiceBitrateController.EVAL_INTERVAL_MS)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
                val c = voiceQuality ?: return@Thread
                val rcv = voice.receivedFrames
                val mis = voice.missedFrames
                val dRcv = rcv - lastReceived
                val dMis = mis - lastMissed
                lastReceived = rcv
                lastMissed = mis
                // 任务单 1.5：积压口径 = 队列 ms + 线内未播 ms（只算队列虚好 40–43ms）。
                // 任务单 4：这个毫秒口径现在**同时是码率自适应的判据**（原来判据是「≥2 帧」，
                // 20ms 帧下 = 40ms，比接收端水位目标 80ms 还低，稳态必误判）。
                // 线内不可测（-1）时如实打"不可测"，不折成 0 少算。
                // 任务单 3.3：末尾带出向队列帧数 —— 发端只入队不入网的那一段，收端计数器看不见。
                val qMs = voice.backlogQueueMs
                val wMs = voice.backlogWireMs
                val backlogMs = qMs + if (wMs < 0L) 0L else wMs
                val outFrames = av?.pendingAudioDatagrams ?: 0
                AppCore.appendLogPublic(
                    "音频积压 ${backlogMs}ms（队列 ${qMs}ms/${voice.pendingFrames} 帧" +
                        " + 线内 ${if (wMs < 0L) "不可测" else "${wMs}ms"}）出向 ${outFrames} 帧"
                )
                c.evaluate(backlogMs, voice.audioFrameMs, dMis, dRcv)
            }
        }, "pp-voice-aq").apply { isDaemon = true }
        aqThread = t
        t.start()
        AppCore.appendLogPublic("语音码率自适应已启用（64k 起步，弱网降到 32k）")
    }

    /** 来电铃声播放器（仅 INCOMING 态存在；接听/挂断/取消/超时都会经 endCall 停掉） */
    @Volatile
    private var ringer: MediaPlayer? = null

    private fun endCall(ip: String, reason: String) {
        SegMeter.stop()
        val wasTalking = state == State.TALKING
        val wasVideo = videoMode
        val wasOutgoing = state == State.OUTGOING
        stopRinger()
        // 时长必须在清 talkStartMs 之前算好（v2.7：上层要把它记成一条通话消息）
        val durationMs = if (wasTalking && talkStartMs > 0) System.currentTimeMillis() - talkStartMs else 0L
        // 先摘监听（立刻停止后续音频发送），音频设备与相机释放放后台：
        // 若音频初始化线程仍持有 VoiceEngine 的锁（startRecord 阻塞中），同步 stopAll
        // 会把调用线程（主线程/读线程）一起卡住 —— 通话已结束但界面/会话卡死。
        voice.setListener(null)
        voiceQuality = null
        // 媒体会话关闭（任务单 2.1 建、此处收；2.9 会把"挂断/异常/对端消失/切后台/呼叫失败"
        // 五条路径全部汇聚到这一个 closeTransport）
        closeTransport()
        Thread({
            runCatching { voice.stopAll() }
            runCatching { video.stop() }
            restoreAudioRoute()
        }, "pp-voice-stop").apply { isDaemon = true }.start()
        peerIp = ""
        peerName = ""
        talkStartMs = 0L
        videoMode = false
        pendingVideo = false
        // 定档意图/结论随本通一起作废：closeTransport 已把协商线程叫停（stop() ⇒ negotiationStopped），
        // 残留的 onNegotiated 会被上面那句 IDLE 判断挡住，但标志本身不能留给下一通。
        negotiated = false
        pendingStartTalking = false
        setState(State.IDLE)
        // 2.12 / D16：挂断 ⇒ "通话中"这一票收回为 0。若三票全为 0，setFileVote 内部会自动
        // 续发一次待发队列（对端没应答就停在 PAUSED 等手动「继续」）。
        link.setFileVote(Link.VOTE_IN_CALL, false)
        listener?.onCallEnded(ip, reason, wasVideo, durationMs, wasOutgoing)
        if (wasTalking) {
            AppCore.appendLogPublic("${if (wasVideo) "视频" else ""}通话结束（$reason）")
            // 计数器唯一打印点（任务单 1.7）：一行 17 项，全 0 也要出现（缺行 = 没接上）
            CallCounters.logSummary { AppCore.appendLogPublic(it) }
        }
    }

    private fun setState(s: State) {
        state = s
        listener?.onStateChanged(s, peerIp, peerName)
    }

    /**
     * 2.9 的"切后台 / 实例销毁"这一条退出路径的落点。
     * ⚠ 这里必须先 [closeTransport]：媒体会话是振铃期就建的（[openTransport]），
     *   本方法过去只收尾音频、视频与音频设备回调 ⇒ 走这条路径退出会留下已 bind 的 UDP/TCP 端口、
     *   ④ 预建那条连接，以及 pp-av-alive 线程（"结束后记得关闭传输线程"正是防这个）。
     *   与 endCall 走的是**同一个** closeTransport ⇒ 可重入，重复调用不出异常。
     */
    fun dispose() {
        closeTransport()
        stopRinger()
        voice.stopAll()
        video.stop()
        // startTalking 里注册的 AudioDeviceCallback 此前永不注销（单例 + 主线程 Handler 常驻）：
        // dispose 时主动注销并复位标志，避免回调链在管理器销毁后仍被系统持有。
        if (audioCbRegistered) {
            runCatching { audio.unregisterAudioDeviceCallback(audioDeviceCb) }
            audioCbRegistered = false
            Log.i(TAG, "AudioDeviceCallback 已注销")
        }
    }
}
