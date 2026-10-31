package com.phonepctransfer.net

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Peer
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 手机 ↔ 电脑 的 TCP 链路（**多设备独立长连接**）。
 *
 * 设计要点（与 docs/PROTOCOL.md 一致）：
 *  - 两端都监听 28765，也都能主动 connect，覆盖「同一 WiFi」与「手机开热点」两种拓扑
 *    （v2.1 起从 8765 迁移：MIUI 的 scene-daemon 会长期占用 127.0.0.1:8765，通配绑定必冲突）
 *  - **每台对端各一条独立会话**：同时可以和 PC、另一台手机都保持长连接，互不干扰
 *    （一台抖动/断开不影响其他）。协议零改动 —— TCP 连接本身就标识了对端，
 *    所以消息归属 = 读到它的那条会话，帧里不需要「发给谁」字段。
 *  - **只有用户点击设备才（重）连接**：connectTo() 投递一次请求、试一次、失败不重试
 *  - 去重收敛规则：同设备双连接时 preferOutgoing = (myId < peerId)，两端独立算得同一结论
 *  - 15s ping 保活，35s 无收包判定断链；断链后对本端发起成功过的会话自动重连（保持长连接）
 *  - **写失败即判死**：任何一次发送异常都立刻废弃该会话
 */
class Link(internal val ctx: Context, internal val prefs: Prefs) {

    enum class State { STOPPED, IDLE, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State, peer: String)
        /**
         * 会话集合变化（某台连上 / 断开）。
         * UI 用它只刷设备列表 —— 聊天区不受影响，别人的连接抖动不会打断当前对话。
         */
        fun onPeersChanged() {}
        /** 新增一条消息（自己发 / 收到对端） */
        fun onMessageAdded(m: MsgItem)
        /** 已有消息的状态或进度变化 */
        fun onMessageUpdated(m: MsgItem)
        /**
         * 一条消息被移除（文件被任一方取消）。
         * 实现方应把该条从界面移除，并从本地历史/待发队列里删掉 —— 取消后两端都不再显示。
         */
        fun onMessageRemoved(mid: String) {}
        /**
         * 历史同步（hsync）一批补齐完成：从对端补入了 count 条消息。
         * 实现方借此按 ts 重排聊天区（补入的旧消息是按到达顺序追加的）。
         */
        fun onHsyncApplied(ip: String, count: Int) {}
        /** 需要把文本写入本机剪贴板（来自对端）。剪贴板同步不生成气泡，只写剪贴板。 */
        fun onClipToClipboard(text: String)
        /**
         * 旧握手协商遗留：对端对 clip_pull 回「缓冲为空」时触发。纯被动模型下本机
         * 不再发起拉取，恒不会发生；保留接口仅为兼容，实现方无需处理。
         */
        fun onPeerClipEmpty() {}
        /**
         * 这份剪贴板内容本机是否"已经知道"（自己刚推出去过，或刚从对端拉回来过）。
         * 为 true 时不再重复处理——避免把同一份内容反复写进剪贴板。
         * 哈希由实现方持久化，跨进程重启依然有效。
         */
        fun shouldSkipPeerClip(sha1: String): Boolean = false
        fun onLog(s: String)

        // ==================== 语音通话回调 ====================
        /** 收到呼叫请求（来自 peerIp 的对端，name 为对方名）。实现方据此弹接听窗。 */
        fun onCallInvite(peerIp: String, name: String) {}
        /** 收到同意接听 */
        fun onCallAccept(peerIp: String) {}
        /** 收到拒绝接听 */
        fun onCallReject(peerIp: String) {}
        /** 收到「忙」：对方已有通话，本端静默收线 */
        fun onCallBusy(peerIp: String) {}
        /** 收到挂断（reason 为空=正常挂断，非空=超时等） */
        fun onCallEnd(peerIp: String, reason: String) {}
        /** 收到呼出方取消（接听前） */
        fun onCallCancel(peerIp: String) {}
        /**
         * 收到一帧对端音频（在读线程回调，实现方应尽快转交播放队列）。
         *
         * @param ts 发送端的**通话相对**时间戳（ms，对端起呼=0）；只可与本端视频帧同轴比较，
         *           不可与本端墙钟比大小（两端各自零点，见 `util/CallClock.kt`）
         */
        fun onCallAudio(peerIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {}

        // ==================== 视频通话回调 ====================
        /**
         * 视频呼叫请求（带视频协商）。默认实现回落到纯语音版 [onCallInvite]，
         * 这样没升级的实现方不会漏掉来电。
         *
         * @param af 对端期望的语音帧长（ms）；0 = 对端是老版本，未声明 → 用 200ms 兼容模式
         * @param mport 对端媒体端口（任务单 2.4 / 方案 §4.2）；0 = 未声明 → 只能走带内档
         * @param mudp  对端媒体端口是否 UDP；false = 未声明
         * @param msid  对端本次通话的随机 token；空 = 未声明
         */
        fun onCallInviteVideo(
            peerIp: String, name: String, vid: Boolean, lv: Int, af: Int = 0,
            mport: Int = 0, mudp: Boolean = false, msid: String = ""
        ) {
            onCallInvite(peerIp, name)
        }
        /** 视频接听应答（带档位、语音帧长与媒体通道协商），字段语义同 [onCallInviteVideo] */
        fun onCallAcceptVideo(
            peerIp: String, vid: Boolean, lv: Int, af: Int = 0,
            mport: Int = 0, mudp: Boolean = false, msid: String = ""
        ) {
            onCallAccept(peerIp)
        }
        /**
         * 振铃期的**逐档媒体协商**（任务单 2.5 / 方案 §4.3① 的「交换」）：对端把它当前档的
         * 本端三元组带过来。默认实现什么都不做（老实现方不会因此漏掉任何通话事件）。
         *
         * @param msid 空 = 对端未声明（老端口径）⇒ 本端只能落带内档
         * @param mport 0 且 msid 非空 = 对端已落带内档（第五档），本端不必再试后面几档
         * @param mtier 对端当前档序号；⚠ 两端档位序列可能不同（安卓跳过两个 443 档），
         *              只用于日志，**不是**连通性判据
         */
        fun onCallMedia(
            peerIp: String, mport: Int, mudp: Boolean, msid: String, mtier: Int
        ) {}
        /**
         * 收到一帧对端视频（**网络读线程**回调，实现方只能入队，禁止在这里初始化解码器 ——
         * 那会阻塞读线程 → 对端 35s 判死掉线）。
         *
         * @param ts 与同通话音频帧同零点的**通话相对**毫秒（发送端侧）
         */
        fun onVideoFrame(peerIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {}
        /**
         * 编码参数变更（收到后必要时重建解码器）。
         *
         * @param front 对端摄像头是否**前置**（`video_cfg.cam`；老对端不带此键 = 后置）。
         *   接收端据此决定「对方画面」的默认显示角（前置默认多 180°，见 ChatScreen）。
         */
        fun onVideoCfg(
            peerIp: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int,
            front: Boolean
        ) {}
        /**
         * 接收侧健康反馈（发送侧用于升降档）。
         *
         * @param lat      对端「入队→喂解码器」的排队时延 ms（真实值）
         * @param dropping 对端此刻是否正处于丢帧期（任务单 1.3，与 PC 端同名同义）
         */
        fun onVideoFb(peerIp: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int) {}
        /** 接收端请求临时停/开视频；[key] = 请求立刻给一个 IDR（对端解码器刚重建，等不起 1s GOP） */
        fun onVideoReq(peerIp: String, on: Boolean, key: Boolean) {}

        // ==================== 数据流量确认（v2.7）====================

        /**
         * 对端请求「用数据流量发文件/语音/视频」。
         *
         * 实现方（AppCore）决定：本机不在数据网络 → 直接 [Link.replyDataAsk] 同意（不打扰用户）；
         * 本机在数据网络 → 弹框问用户，用户点完再应答。
         * **绝不能在读线程里阻塞等用户**（会把会话读循环卡死）。
         */
        fun onDataAsk(peerIp: String, mid: String, kind: String, name: String, size: Long) {}

        /**
         * 一条消息在时间线上被移到「最新一条」（当前只有「数据流量拒绝后重试成功」这一条路径：
         * 用户要求同意后把那条消息挪到最下面）。
         */
        fun onMessageMoved(mid: String) {}
    }

    /** 对端能力（hello 协商所得）。发给某台设备前必须先看这个。 */
    class PeerCaps(
        @Volatile var vid: Boolean = false,
        @Volatile var cam: Boolean = false,
        @Volatile var vcodec: List<String> = emptyList(),
        /** 对端支持历史同步（hsync，v2.3 起）。老端不带该字段 → false → 不做历史补齐 */
        @Volatile var hs: Boolean = false,
        /** 对端支持「消息删除同步」（msg_del，v2.4 起）。老端不带该字段 → false → 删除不跨端 */
        @Volatile var hd: Boolean = false,
        /**
         * 对端支持「文件夹完整性校验」（dir_meta.list / dir_end / dir_missing，v2.6 起）。
         * 老端不带该字段 → false → 不发这些帧、不做清单核对（保持老行为）
         */
        @Volatile var dl: Boolean = false,

        /**
         * 对端支持「数据流量确认」（data_ask / data_ans，v2.7 起）。
         * 老端不带该字段 → false → 不发确认帧，沿用「对端不在局域网段就提醒本机用户」的老口径。
         */
        @Volatile var dm: Boolean = false
    )

    /**
     * 本端能力（由 AppCore 注入，握手时广播）。
     *
     * **必须在 link.start() 之前设置**（AppCore.init 里就设），否则第一轮握手会广播错误能力。
     */
    @Volatile
    var localVidCap: Boolean = false

    @Volatile
    var localCamCap: Boolean = false

    @Volatile
    var localVCodecs: List<String> = emptyList()

    @Volatile
    var listener: Listener? = null

    @Volatile
    var state: State = State.STOPPED
        internal set

    /** 状态栏附注文案（当前对话设备名 / 「连接失败，点设备重试」这类） */
    @Volatile
    var peerLabel: String = ""
        internal set

    @Volatile
    internal var running = false

    internal var serverSocket: ServerSocket? = null
    internal val sessions = CopyOnWriteArrayList<Session>()

    /**
     * 按对端 IP 索引的会话表 —— **多设备并发的核心**。
     *
     * 旧模型是一个 `active` 单活跃会话（同时只服务一个对端）；现在每台连上的设备各有一条
     * 独立长连接：连着的多台同时在 `sessions` 里，互不干扰。
     */
    internal val byPeer = ConcurrentHashMap<String, Session>()

    /** 当前对话（也是主动发送的唯一目标）设备 IP；空 = 未选中 */
    @Volatile
    var selectedIp: String = ""
        internal set

    /** 当前对话设备在设备列表里的显示名 */
    @Volatile
    var selectedLabel: String = ""
        internal set

    /**
     * 一次性连接请求**队列**（同一台设备的候选地址，按优先级排好）。
     * 只在「用户点击设备」/「启动自动连接」时投递（见 connectTo）。
     *
     * 连接工人按顺序试：某个地址连上并握手成功 → 清空队列、不再试后面的；
     * 全部失败 → 清空队列、**不重试**（要重试只能等对端被动连过来，或用户再点一次设备）。
     */
    @Volatile
    internal var pendingConnectIps: MutableList<String>? = null

    /**
     * 「点的那个地址连不上、同设备的另一个地址连上了」的回调（请求地址 → 实际连上的地址）。
     *
     * 上层据此把「当前对话地址」跟到真正连上的那个地址上（顶栏 / 地址菜单回显才不会错）。
     * ⚠ 由 pp-connect 线程回调：**不得在这里做 UI 操作**，上层自行切线程。
     */
    @Volatile
    var onGroupFallback: ((requestedIp: String, actualIp: String) -> Unit)? = null

    // ==================== 传输速率（2026-09-22，气泡显示用）====================

    /**
     * 传输速率回调：`(mid, 字节/秒)`，**节流到最快 500ms 一次**（见 [SpeedMeter]）。
     *
     * ⚠ 回调在发送线程 / 接收读线程上执行，上层（AppCore）自己切主线程再写界面状态。
     * 只上报「平滑后的正值」；停止/卡住不上报 —— 界面靠「样本新鲜度」自动隐藏（见 AppCore.speedBpsOf）。
     */
    @Volatile
    var onSpeed: ((mid: String, bps: Long) -> Unit)? = null

    private val speedMeters = java.util.concurrent.ConcurrentHashMap<String, SpeedMeter>()

    /** 记一笔字节总数（发送/接收循环里调）：够一个采样间隔就回调一次 [onSpeed] */
    internal fun markSpeed(mid: String, bytes: Long) {
        if (mid.isEmpty() || bytes <= 0L) return
        val m = speedMeters.computeIfAbsent(mid) { SpeedMeter() }
        m.sample(bytes) { bps -> onSpeed?.invoke(mid, bps) }
    }

    /** 这一条传完/失败/取消 → 丢掉采样器（避免下次同 mid 拿着旧基线算出假速率） */
    internal fun endSpeed(mid: String) {
        if (mid.isNotEmpty()) speedMeters.remove(mid)
    }

    /**
     * 生成一台设备的**候选地址顺序**：
     *  ① 传入的那个地址（用户点的 / 上次选中的）排第一；
     *  ② 同设备（同组键 = 同一 deviceId）的其它地址，按最近在线时间倒序。
     *
     * 为什么要有 ②（2026-09-19）：一台设备可能有多个地址（WiFi / 热点 / 换网段），
     * 用户点的（或记住的）可能正是它当前不在线的那个 —— 只试一个地址就直接判「连不上」，
     * 而同设备的另一个地址此刻是通的。试通一个就停，绝不并发、绝不循环重试。
     */
    internal fun groupCandidates(ip: String, tryGroup: Boolean = true): List<String> {
        val v = ip.trim()
        if (v.isEmpty()) return emptyList()
        if (!tryGroup) return listOf(v)
        val out = LinkedHashSet<String>()
        out.add(v)
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        if (k.isEmpty() || k == v) return out.toList()
        val others: List<Peer> = runCatching { prefs.peers }.getOrDefault(emptyList())
        others.filter { p ->
            p.ip != v && p.ip.isNotBlank() &&
                runCatching { prefs.convKeyOf(p.ip) }.getOrNull() == k
        }
            .sortedByDescending { it.lastSeenAt }
            .forEach { out.add(it.ip) }
        return out.toList()
    }

    /** 被动连上 / 已连上：撤掉同设备还没轮到的候选地址（不再白试） */
    internal fun clearPendingFor(ip: String) {
        val q = pendingConnectIps ?: return
        val v = ip.trim()
        if (v.isEmpty()) return
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        val drop = q.firstOrNull { c ->
            c == v || (k.isNotEmpty() && runCatching { prefs.convKeyOf(c) }.getOrNull() == k)
        }
        if (drop != null) {
            pendingConnectIps = null
            log("设备 $v 已可达，取消剩余候选地址 ${q.joinToString(", ")}")
        }
    }

    /**
     * 出向连接闸门（由 AppCore 注入，返回 true=拒绝）：
     * 「**后台只允许连已知设备**」的落点（策略在 AppCore，见 `link.outgoingGate = ...`）。
     */
    @Volatile
    var outgoingGate: ((ip: String) -> Boolean)? = null

    /**
     * 入向连接闸门（由 AppCore 注入，返回 true=拒绝）：
     * 「**后台不接受陌生设备主动连入**」（2026-10-01 定稿，与出向闸门配对）。
     * 命中即在 [adopt] 里直接 close，不发 hello、不进 sessions。
     */
    @Volatile
    var incomingGate: ((ip: String) -> Boolean)? = null

    /**
     * 「本机当前是否走运营商数据网络」（由 AppCore 注入 = `LanGate.onCellular(prefs.forceLanEnabled)`）。
     * 与对端 IP 的网段无关 —— 只有本机真的在花流量时才需要提醒本机用户。
     */
    @Volatile
    var localOnCellular: (() -> Boolean)? = null

    /**
     * 本机「数据流量提醒」确认框（由 AppCore 注入）。
     *
     * **会阻塞**直到用户点「同意/取消」（实现内部把弹框投到主线程 + 闩锁等待），
     * 因此只允许在**专用后台线程**里调用 —— 详见 [ensureDataConsent]。
     * 返回 true = 同意本次使用数据流量。
     */
    @Volatile
    var localDataConsent: ((peerIp: String, label: String) -> Boolean)? = null

    /**
     * 「已知设备」名单（由 AppCore 注入，同 [store] 的注入方式）。
     *
     * 打点时机（**只增不减**，与聊天记录解耦 —— 清空消息不影响判定）：
     *  - 握手成功 [onHello] → 双方**连接过**
     *  - 收到 / 发出 文本 [onFrame]/[sendText] → 有**消息**往来
     *  - 收到 / 发出 文件（含文件夹成员）[onFileFrame]/[sendFile]/[sendFolder] → 有**文件**往来
     */
    @Volatile
    var trusted: com.phonepctransfer.data.TrustedPeers? = null

    /** 打点：与该对端有过消息/文件往来。任何异常都不能影响收发主流程 */
    internal fun trustContent(ip: String, isFile: Boolean) {
        runCatching { trusted?.markContent(ip, isFile) }
    }

    /** 正在连接中的目标 IP（仅用于状态展示） */
    @Volatile
    internal var connectingIp: String? = null

    /** 状态附注（「连接失败，点设备重试」这类） */
    @Volatile
    internal var stateNote = ""

    /** 兼容旧调用：当前对话设备的 IP（未选中为 null） */
    val peerIp: String?
        get() = selectedIp.ifEmpty { null }

    internal val reconnectLock = Object()

    internal var acceptThread: Thread? = null
    internal var connectThread: Thread? = null
    internal var heartbeatThread: Thread? = null

    internal val myId: String get() = prefs.deviceId
    internal val myName: String get() = prefs.deviceName

    // ==================== 会话 ====================

    internal inner class Session(val socket: Socket, val outgoing: Boolean) {
        val input: InputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        /**
         * 写锁：保证帧不交错。用 ReentrantLock 而不是 synchronized ——
         * 心跳线程需要 tryLock：写锁被文件传输占用时跳过本轮 ping，
         * 否则一条阻塞的会话会把整个心跳循环（其他所有会话的判死）拖住。
         */
        internal val writeLock = ReentrantLock()

        /** 对端 IP，构造时定下（socket 关了也能读，用来做归属与状态判定） */
        val remoteIp: String =
            runCatching { socket.inetAddress?.hostAddress }.getOrNull().orEmpty()

        @Volatile var peerId = ""
        @Volatile var peerName = ""
        @Volatile var handshaked = false

        /** 对端能力（hello 协商所得）。握手前全 false —— 因此默认绝不会发视频帧。 */
        val caps = PeerCaps()

        @Volatile var lastRx = System.currentTimeMillis()
        @Volatile var lastTx = System.currentTimeMillis()
        /** 已发出 ping 但之后未收到任何帧（含 pong）时为 true —— 判死依据，防自身 ping 刷 lastTx 骗过看门狗 */
        @Volatile var pingOutstanding = false
        @Volatile var closed = false

        /**
         * 收到任何帧（pong / 数据分片 / 音视频帧 / 控制帧）时调用：刷新存活证据并**清掉 ping 未回标志**。
         * 判死只看收包 —— PC 端 readLoop 一直这么做，Android 端此前漏了 `pingOutstanding = false`
         * （2026-09-21 补对称：不清的话它一旦置位就永远是 true，判死条件被放宽一拍）。
         */
        internal fun touchRx() {
            lastRx = System.currentTimeMillis()
            pingOutstanding = false
        }

        init {
            // 大一点的收发缓冲：分片 1MB 起，默认 8KB 太小会白白多走系统调用
            runCatching {
                socket.sendBufferSize = 1 shl 20
                socket.receiveBufferSize = 1 shl 20
                // 诊断（2026-10-30）：读回内核实际值。TCP 窗口缩放在握手时已按当时的缓冲定死，
                // 这里再设只改缓冲、改不了缩放因子 —— 读回值仅供对照。
                log("[sock] $remoteIp snd=${socket.sendBufferSize}" +
                        " rcv=${socket.receiveBufferSize}" + (if (outgoing) " (主动)" else " (被动)"))
            }
        }

        @Throws(Exception::class)
        fun send(j: JSONObject) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                Protocol.write(output, j)
                lastTx = System.currentTimeMillis()
            } finally {
                writeLock.unlock()
            }
        }

        /** 二进制文件分片：与 JSON 控制帧共用写锁，保证帧不交错 */
        @Throws(Exception::class)
        fun sendChunk(mid: String, seq: Int, buf: ByteArray, off: Int, len: Int) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                Protocol.writeChunk(output, mid, seq, buf, off, len)
                lastTx = System.currentTimeMillis()
            } finally {
                writeLock.unlock()
            }
        }

        /**
         * 语音音频帧：与 JSON 控制帧/文件分片共用写锁，保证帧不交错。
         *
         * @return 本次写入耗时（ms）—— 上层（语音码率自适应）据此判断链路是否拥塞。
         */
        @Throws(Exception::class)
        fun sendAudio(seq: Int, ts: Long, buf: ByteArray, off: Int, len: Int): Long {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                val t0 = System.nanoTime()
                Protocol.writeAudio(output, seq, ts, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeLock.unlock()
            }
        }

        /**
         * 视频帧：与音频/文件分片共用写锁。
         *
         * @return 本次写入耗时（ms）—— 上层据此做「100ms 发送截止期」统计（§3.4.3），
         *         超阈值只记 drop，**不重发**（TCP 已保证可靠有序，重发只会翻倍占用带宽）。
         */
        @Throws(Exception::class)
        fun sendVideo(seq: Int, ts: Long, flag: Int, buf: ByteArray, off: Int, len: Int): Long {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                val t0 = System.nanoTime()
                Protocol.writeVideo(output, seq, ts, flag, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeLock.unlock()
            }
        }

        /**
         * 心跳专用：**tryLock** 发 ping。返回 false = 写锁被文件传输占用
         * （本端正在向对端发数据，链路显然活着，本轮跳过）；
         * 返回 true = ping 已发出（或会话已关/发送异常转判死流程）。
         */
        fun tryPing(): Boolean {
            if (!writeLock.tryLock()) return false
            return try {
                if (closed) {
                    true
                } else {
                    Protocol.write(output, Protocol.ping())
                    lastTx = System.currentTimeMillis()
                    true
                }
            } catch (t: Throwable) {
                close()  // 与 sendOrDrop 同语义：发送失败即判死
                true
            } finally {
                writeLock.unlock()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }

    /**
     * 按 IP 找已握手且活着的会话。
     *
     * 同一个 IP 上可能同时挂着**两台**设备的会话（2026-10-05 同 IP 多设备：两台设备各自
     * 连进来，或者一端连入一端连出）。此时「按 IP 找」天然有歧义 —— 必须优先返回**身份与
     * 这个 IP 的组键一致**的那条（即 Prefs 认定的活动设备），否则会出现
     * 「列表显示 A 在线、切过去却是 B 的会话」。
     */
    internal fun sessionOf(ip: String?): Session? {
        if (ip.isNullOrEmpty()) return null
        val want = runCatching { prefs.convKeyOf(ip) }.getOrNull().orEmpty()
        var fallback: Session? = null
        for (s in sessions) {
            if (!s.handshaked || s.closed || ip != s.remoteIp) continue
            if (want.isNotEmpty() && want == s.peerId) return s
            if (fallback == null) fallback = s
        }
        return fallback
    }

    /** 指定 deviceId 的设备是否有健康会话（同 IP 多设备的别名行打点用） */
    fun isPeerConnected(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val s = byPeer[deviceId] ?: return false
        return s.handshaked && !s.closed
    }

    /**
     * 该地址上正连着的、但**不是** keepDevId 这台设备的对端名（同 IP 多设备冲突判定用）。
     * 没有冲突（该地址没会话、或仅 keepDevId 自己的会话）返回 null。
     */
    fun foreignPeerAt(ip: String, keepDevId: String): String? {
        val v = ip.trim()
        if (v.isEmpty()) return null
        for (s in sessions) {
            if (!s.handshaked || s.closed || v != s.remoteIp) continue
            if (keepDevId.isNotEmpty() && keepDevId == s.peerId) continue
            return s.peerName.ifEmpty { s.peerId }
        }
        return null
    }

    /**
     * 断开某个地址上的会话（同 IP 多设备冲突时「断开之前的连接」的落点）。
     *
     * 只断这个地址上的**旧设备**会话，别的设备（别的 IP）会话一条都不动 ——
     * 多设备独立长连接的语义不能破。
     *
     * @param keepDevId 非空时保留这条设备身份的会话（通常是即将切换过去的那台）
     * @return 实际断开的会话条数
     */
    fun disconnectAt(ip: String, keepDevId: String = ""): Int {
        val v = ip.trim()
        if (v.isEmpty()) return 0
        var n = 0
        for (s in sessions) {
            if (v != s.remoteIp) continue
            if (keepDevId.isNotEmpty() && keepDevId == s.peerId) continue
            log("断开 $v 上的旧连接（${s.peerName.ifEmpty { s.peerId }}，切换设备）")
            s.close()
            sessions.remove(s)
            if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
            n++
        }
        if (n > 0) {
            notifyPeers()
            refreshState()
        }
        return n
    }

    /** 当前会话（主动发送的目标）；未选中或未连接返回 null。
     *  选中地址没会话时回落到**同设备的另一个地址**（见 [sessionByGroup]）——
     *  设备分组的语义就是「这几个地址是同一台机器」。 */
    internal fun target(): Session? = sessionByGroup(selectedIp)

    /** 指定 IP 的设备当前是否已连接（设备列表打点用，与选中与否无关） */
    fun isConnectedIp(ip: String): Boolean = sessionOf(ip) != null

    /**
     * 指定**设备**是否可达：组内任意地址有健康会话即算（见 [sessionByGroup]）。
     * 设备分组后「这台机器在不在线」不该取决于用户当前选了哪个地址。
     */
    fun isDeviceConnected(ip: String): Boolean = sessionByGroup(ip) != null

    /** 已经连上的设备 IP 集合 */
    val connectedIps: Set<String>
        get() {
            val out = LinkedHashSet<String>()
            for (s in sessions) {
                if (s.handshaked && !s.closed) out.add(s.remoteIp)
            }
            return out
        }

    // ==================== 生命周期 ====================

    fun start() {
        if (running) return
        running = true
        // 线程池是一次性的：stop() 会 shutdownNow，重启链路必须重建（否则 execute 抛
        // RejectedExecutionException —— sendExec 在主线程 io{} 里被调，会直接崩进程）。
        if (keepAliveExec.isShutdown) keepAliveExec = newKeepAliveExec()
        if (fileSendExec.isShutdown) fileSendExec = newFileSendExec()
        if (sendExec.isShutdown) sendExec = newSendExec()
        store?.purgeStalePartials(PARTIAL_MAX_AGE_MS)
        log(
            "链路启动：本机 id=$myId name=$myName 监听 ${prefs.tcpPort}" +
                "（协议 v${Protocol.PROTO_VER}，多设备独立长连接）"
        )

        acceptThread = Thread({ acceptLoop() }, "pp-accept").apply { isDaemon = true }.also { it.start() }
        connectThread = Thread({ connectLoop() }, "pp-connect").apply { isDaemon = true }.also { it.start() }
        heartbeatThread = Thread({ heartbeatLoop() }, "pp-heartbeat").apply { isDaemon = true }.also { it.start() }
        refreshState()
    }

    fun stop() {
        if (!running) return
        running = false
        keepAliveJobs.values.forEach { it.cancel(false) }
        keepAliveJobs.clear()
        // 发送线程池一并停：停止后排队任务不得再对着已关的 socket 写
        keepAliveExec.shutdownNow()
        fileSendExec.shutdownNow()
        sendExec.shutdownNow()
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (s in sessions) s.close()
        sessions.clear()
        byPeer.clear()
        // 停止链路前取消的 mid 不会被发送循环处理到，清掉避免残留到进程结束
        //（重启后不应再有「幽灵取消」命中新传输）
        cancelled.clear()
        pendingConnectIps = null
        connectingIp = null
        wakeUp()
        setState(State.STOPPED, "")
        log("链路已停止")
    }

    /** 当前对话设备是否已连接 */
    fun isConnected(): Boolean = target() != null

    /**
     * 点选一台设备 → 设为当前对话目标。
     * 只改目标、不碰别的会话：连着的其他设备**不受任何影响**（多设备互不干扰）。
     */
    fun selectTarget(ip: String, label: String? = null) {
        val v = ip.trim()
        if (v.isEmpty()) return
        selectedIp = v
        if (!label.isNullOrEmpty()) selectedLabel = label
        refreshState()
    }

    /**
     * 点选设备并按需连接：已连着就只是切过去（不动连接）；没连才发起**一次**连接尝试。
     * 失败不重试 —— 要重试就再点一次设备（这是「只有点击才触发重连」的落点）。
     */
    fun selectAndConnect(ip: String, label: String? = null) {
        selectTarget(ip, label)
        connectTo(ip)
    }

    /**
     * 发起一次连接尝试（**不重试**）。
     * 若该设备已有健康会话则什么都不做。
     * `quiet=true` 时不打「请求连接」类日志（保活重连用，避免阶梯重试刷屏）。
     *
     * @param tryGroup true = 该设备有多个地址时**依次**试（见 [groupCandidates]）：
     *                 点的那个先试，连上并握手成功即停，后面的地址不再试；
     *                 全试完仍失败 → 队列清空、不再重试，直到对端被动连过来或用户再点一次。
     */
    fun connectTo(ip: String, quiet: Boolean = false, tryGroup: Boolean = true) {
        val v = ip.trim()
        if (v.isEmpty()) return
        val ex = sessionOf(v)
        if (ex != null) {
            // 已有会话 ≠ 健康：TCP 半开（对端进程已死 / 网络切换）时 socket 仍“连着”，
            // 但再也收不到任何帧 —— 表现为“点了没反应 / 连不上”。超过一轮心跳周期仍无
            // 任何收包且我们发过 ping 未得 pong，就判失效、废弃旧会话并重连。
            val rxIdle = System.currentTimeMillis() - ex.lastRx
            if (!(ex.pingOutstanding && rxIdle > STALE_RX_MS)) {
                if (!quiet) log("已连接 $v，无需重连")
                refreshState()
                return
            }
            log("会话 $v 已 ${rxIdle}ms 无收包（疑似半开），废弃后重连")
            ex.close()
            sessions.remove(ex)
            if (ex.peerId.isNotEmpty()) byPeer.remove(ex.peerId, ex)
            notifyPeers()
        }
        // 同设备的另一个地址已经连着 → 这台设备已可达，不用再拨（见 sessionByGroup）
        if (sessionByGroup(v) != null) {
            if (!quiet) log("设备 $v 已通过同设备其它地址连接，无需重连")
            refreshState()
            return
        }
        // 硬规则：**后台只允许连「已知设备」**（连接过 + 有消息/文件往来，名单见 [trusted]）。
        // 其余对端只能在前台由用户点击建立连接；未知设备一律拒绝。
        // 入向同规则：后台陌生设备主动连入由 incomingGate 拒接（见 adopt）。
        if (outgoingGate?.invoke(v) == true) {
            stateNote = "后台不允许连接未知设备"
            log("后台建连被拒：$v 不在已知设备名单（需先前台连接并收发过消息/文件）")
            refreshState()
            return
        }
        val cands = groupCandidates(v, tryGroup)
        pendingConnectIps = java.util.Collections.synchronizedList(ArrayList(cands))
        stateNote = ""
        if (!quiet) {
            log(
                if (cands.size > 1) "请求连接设备 $v（依次尝试 ${cands.joinToString(" → ")}，成功即停、失败不重试）"
                else "请求连接设备 $v（手动触发，失败不自动重试）"
            )
        }
        wakeUp()
    }

    // ==================== 保活重连（2026-09-16） ====================

    /**
     * 连接成功过就要**保持长连接**（用户定调）：通话 / 视频 / 文件传输结束后会话不能掉。
     * 只对「本端主动发起成功」的会话自动重连 —— 能连上说明这个方向可达（对端可能
     * 有防火墙只放行单向来连）；对端发起的入向会话不重试（本方向可能被挡，SYN
     * 长时间悬挂），由对端的保活负责。
     * 节奏（2026-10-01 定稿）：断开后 5s/10s/30s 各重试一次，仍不通**转纯被动**；
     * 本端正在传文件/通话（[keepAliveBusy]）时除外，按 30s 继续重试。
     */
    @Volatile
    internal var keepAliveExec: java.util.concurrent.ScheduledExecutorService =
        newKeepAliveExec()

    private fun newKeepAliveExec(): java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pp-keepalive").apply { isDaemon = true }
        }
    internal val keepAliveJobs =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /**
     * 「本端正在传文件/通话」判据（由 AppCore 注入，true=正在忙）。
     * 保活阶梯走完时用它决定：转纯被动，还是不降级继续重试（见 `runKeepAliveResolve`）。
     */
    @Volatile
    var keepAliveBusy: (() -> Boolean)? = null

    // ==================== 2.12 / D16：文件发送的排他投票表 ====================

    /**
     * 文件与目录发送的**排他投票**，裁决 = 三票取最大值（任一为 1 即 1），与 PC 端
     * `Link.fileGateBlocked()` 同口径。票号见 [VOTE_IN_CALL] / [VOTE_NO_NETWORK] / [VOTE_CONSENT_DENIED]。
     *
     * 1 的时候：新条目**不入网**、改走既有待发台账排队（[queuePending]）；
     * 在传的那一条在下一个分片边界（单文件）或成员边界（文件夹）就地暂停（[pauseInFlight]）。
     *
     * ⚠ 与 2.11 的 `AvTransport.aggregationAllowed()` 是**两个不同的判据**（那条读媒体承载，
     *   本条读通话与发送状态），不许共用读口 —— 用户点的 ② 里"通话中"这一票与五档无关，四档也投 1。
     */
    fun fileGateBlocked(): Boolean = voteInCall || voteNoNetwork || voteConsentDenied

    @Volatile
    private var voteInCall = false

    @Volatile
    private var voteNoNetwork = false

    @Volatile
    private var voteConsentDenied = false

    /**
     * 写一票。只在**裁决真的翻转**（有 1 ⇄ 全 0）时打一行日志（1.6 不周期打印），
     * 并且在全 0 的那一刻自动触发一次续发（[flushPending]）—— 这就是"结束拦截状态后，
     * 会自动触发一次续传请求"：`flushPendingNow → resendPendingFile → doSendFile` 顶部的
     * [negotiateResume] 就是那**一次** `file_query`，对方应答就按回传的偏移续，
     * 不应答就退回暂停等气泡上的手动「继续」。
     *
     * ⚠ [VOTE_NO_NETWORK] 的解除点就是握手完成，而那里**本来就有** `flushPending(ip)`（既有行为）
     *   ⇒ 这一票解除时不再重复触发，免得同一条目排两次。
     */
    @Synchronized
    fun setFileVote(which: Int, on: Boolean) {
        val before = fileGateBlocked()
        when (which) {
            VOTE_IN_CALL -> voteInCall = on
            VOTE_NO_NETWORK -> voteNoNetwork = on
            VOTE_CONSENT_DENIED -> voteConsentDenied = on
            else -> return
        }
        val after = fileGateBlocked()
        if (before == after) return
        log(
            if (after) "[文件排他] 投票翻转 0→1 ⇒ 文件与目录不入网、在传的暂停（D16）"
            else "[文件排他] 投票翻转 1→0 ⇒ 自动续发一次（D16）"
        )
        if (!after && which != VOTE_NO_NETWORK) flushPending()
    }

    // ==================== 文件发送队列（2026-09-21） ====================

    /**
     * 文件 / 文件夹发送队列：**单线程串行** —— 一次只传一个文件，前一个结束立刻上下一个。
     *
     * 为什么不共用 `sendExec`（pp-send 单线程）：那条线程要处理消息 / 剪贴板 / ack / 握手补发，
     * 让一个几百 MB 的文件占住它，聊天消息就会排队卡住。这里独立一条线程，只干文件传输。
     *
     * 为什么不影响通话与消息：音频 / 视频帧由采集与编码线程直接调 `sendAudio` / `sendVideo`，
     * 消息与 ack 走 `sendOrDrop` —— **都不进这条队列**，只按【帧粒度】抢同一个 socket 的写锁。
     * 所以文件排队再长，也不会让语音 / 视频 / 消息"排在文件后面"。
     */
    @Volatile
    internal var fileSendExec: java.util.concurrent.ExecutorService =
        newFileSendExec()

    private fun newFileSendExec(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pp-file-send").apply { isDaemon = true }
        }

    /** 当前排队等待的文件 / 文件夹条数（不含正在传的那一个） */
    internal fun fileSendQueueDepth(): Int =
        (fileSendExec as? java.util.concurrent.ThreadPoolExecutor)?.queue?.size ?: 0



    // ==================== 帧处理 ====================

    /**
     * 这条链路允许同步剪贴板吗 —— 只有局域网链路。
     *
     * 判据用 [LanGate.isLanPeerIp]（私网 v4 / 回环 / 169.254 / fe80::/10 / ULA），
     * 但要先解掉 `::ffff:` 映射：那个 v6 分支不认映射写法，而 adb forward、本机代理
     * 与部分 v4-over-v6 链路给出的正是 `::ffff:127.0.0.1` 这种形式。
     */
    internal fun clipLinkAllowed(ip: String?): Boolean {
        val v = ip?.trim()?.substringBefore('%') ?: return false
        if (v.isEmpty()) return false
        val bare = if (v.lowercase().startsWith("::ffff:")) v.substring(7) else v
        return LanGate.isLanPeerIp(bare)
    }

    internal fun handleFrame(s: Session, j: JSONObject) {
        val type = j.optString("type")
        // 剪贴板只走局域网链路：其余链路（公网 v4 / 全球单播 v6）收到就当没收到，
        // 也不应答 clip_pull、不回 clip_state。文字/文件/通话一律不受影响。
        if (type.startsWith("clip") && !clipLinkAllowed(s.remoteIp)) return
        when (type) {
            "hello" -> onHello(s, j)
            "ping" -> sendOrDrop(s, Protocol.pong(), "pong", quiet = true)
            "pong" -> Unit
            "text" -> {
                val mid = j.optString("mid", newMid())
                // 统一按 mid 查重（2026-09-16）：待发补发/hsync 补齐可能重放同一条，
                // 历史里已有的 mid 不再生成第二个气泡，但 ack 照回（对端要靠它出队）
                if (store?.hasMid(s.remoteIp, mid) == true) {
                    log("文本 mid=$mid 本机已有（重复投递），跳过")
                } else {
                    val m = MsgItem(
                        mid = mid,
                        dir = Dir.PEER, kind = Kind.TEXT,
                        text = j.optString("text", ""),
                        ts = j.optLong("ts", System.currentTimeMillis()),
                        peerIp = s.remoteIp
                    )
                    trustContent(s.remoteIp, isFile = false)
                    emit(m)
                }
                sendOrDrop(s, Protocol.ack(mid, true), "ack", quiet = true)
            }
            "clip" -> {
                // 剪贴板同步不是「发消息」：直接写本机剪贴板，不生成气泡、不进聊天记录。
                // isDuplicateClip 只用于避免重复写入同一份内容（不再是"要不要画气泡"的判断）。
                val text = j.optString("text", "")
                if (text.isNotEmpty() && !isDuplicateClip(text)) {
                    listener?.onClipToClipboard(text)
                }
            }
            "clip_state" -> {
                val text = j.optString("text", "")
                if (text.isNotEmpty()) {
                    if (!isDuplicateClip(text)) {
                        listener?.onClipToClipboard(text)
                        log("已接收对端剪贴板 ${text.length} 字")
                    }
                } else {
                    // 握手协商第二步：对端明说它缓冲为空 → 反过来把本机的推过去。
                    // 本机也读不到内容时，AppCore 那边会自然什么都不做。
                    log("对端剪贴板为空，改为推送本机剪贴板")
                    listener?.onPeerClipEmpty()
                }
            }
            "clip_notify" -> {
                // 兼容旧 PC 的通知帧（新 PC 已改为直接推正文）。纯被动：不回拉。
                log("电脑剪贴板更新（${j.optInt("len", 0)} 字）")
            }
            "clip_pull" -> {
                // 对端请求本机剪贴板缓冲内容。手机端原本只做「拉取方」、不应答任何 clip_pull，
                // 于是手机↔手机互连时两边都收不到应答、日志一直刷「未知帧类型」。
                // 补上应答：读得到就回正文，读不到（后台无焦点时 Android 10+ 会静默失败）回空，
                // 由对端提示"缓冲为空"——如实反映，不假装成功。
                val text = Clip(ctx).read() ?: ""
                sendOrDrop(
                    s,
                    Protocol.clipState(text, if (text.isEmpty()) "" else Clip.sha1(text)),
                    "clip_state"
                )
                if (text.isNotEmpty()) log("已应答对端剪贴板请求 ${text.length} 字")
            }
            "file_meta", "file_done" -> onFileFrame(s, j)
            "file_query" -> onFileQuery(s, j)
            "file_resume" -> onFileResume(j)
            "file_verify" -> onFileVerify(j)
            "dir_meta" -> onDirMeta(s, j)
            "dir_end" -> onDirEnd(s, j)
            "dir_missing" -> onDirMissing(j)
            "file_cancel" -> onRemoteFileCancel(j.optString("mid"))
            "sync_req" -> flushPending(s.remoteIp)
            "hsync_mids" -> Thread({ onHsyncMids(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "hsync_pull" -> Thread({ onHsyncPull(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "hsync_msg" -> Thread({ onHsyncMsg(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "msg_del" -> Thread({ onRemoteMsgDel(s, j) }, "pp-msgdel").apply { isDaemon = true }.start()
            "ack" -> {
                val ackMid = j.optString("mid", "")
                // 我方正在等这条文件的落盘回报 → 送达成立与否**由它决定**（出待发队列 + 记台账）
                if (onSendAck(ackMid, j.optBoolean("ok", true))) return
                // 其余 ack（文本 / 剪贴板 / 文件夹）：消息在本地成功路径（text 写出）已置 OK，
                // 这里再走 markStatus 只会发一个 kind 默认 TEXT、fileSize=0 的裸帧 ——
                // 把文件气泡污染成空白文本、还把 0B 写进历史（2026-09-13 实测）。故只记日志，不 emit。
                log("对端已确认收到 mid=$ackMid")
            }
            "call_invite" -> {
                // 带视频协商（vid/lv）、语音帧长（af）与媒体通道三元组（mport/mudp/msid，任务单 2.4）。
                // 三项**缺省即回退**：老端不带 ⇒ 0 / false / 空 ⇒ 本端只能落带内档（§4.2 / I11）。
                listener?.onCallInviteVideo(
                    s.remoteIp, j.optString("name", ""),
                    j.optBoolean("vid", false), j.optInt("lv", 1), j.optInt("af", 0),
                    j.optInt("mport", 0), j.optBoolean("mudp", false), j.optString("msid", "")
                )
            }
            "call_accept" -> listener?.onCallAcceptVideo(
                s.remoteIp, j.optBoolean("vid", false), j.optInt("lv", 1), j.optInt("af", 0),
                j.optInt("mport", 0), j.optBoolean("mudp", false), j.optString("msid", "")
            )
            "call_media" ->
                // 振铃期逐档协商（任务单 2.5）。老对端不会发这条；本端读到缺省 = -1「未声明」。
                listener?.onCallMedia(
                    s.remoteIp, j.optInt("mport", 0), j.optBoolean("mudp", false),
                    j.optString("msid", ""), j.optInt("mtier", -1)
                )
            "call_reject" -> listener?.onCallReject(s.remoteIp)
            "call_busy" -> listener?.onCallBusy(s.remoteIp)
            "call_end" -> listener?.onCallEnd(s.remoteIp, j.optString("reason", ""))
            "call_cancel" -> listener?.onCallCancel(s.remoteIp)
            "video_cfg" -> listener?.onVideoCfg(
                s.remoteIp, j.optString("codec", "hevc"),
                j.optInt("w", 1280), j.optInt("h", 720), j.optInt("fps", 30),
                j.optInt("br", 1500), j.optInt("rot", 0),
                // cam：1 = 前置，0/缺省 = 后置（老对端不带此键 → 行为与改造前一致）
                j.optInt("cam", 0) != 0
            )
            "video_fb" -> listener?.onVideoFb(
                s.remoteIp, j.optInt("fps", 0), j.optInt("drop", 0),
                j.optInt("lat", 0),
                // dropping：老对端不带此键 = false（与改造前行为一致）
                j.optBoolean("dropping", false), j.optInt("kbps", 0)
            )
            // key：老对端不带此键 = false（与改造前行为一致）
            "video_req" -> listener?.onVideoReq(
                s.remoteIp, j.optBoolean("on", true), j.optBoolean("key", false)
            )
            // ==================== 数据流量确认（v2.7）====================
            // data_ask 交上层（可能要弹框问本机用户）；data_ans 直接投进等待信箱 ——
            // 它是「发起方阻塞等结果」的唤醒信号，绝不能过 ui{} 或任何可能被拖延的队列。
            "data_ask" -> listener?.onDataAsk(
                s.remoteIp, j.optString("mid", ""), j.optString("kind", DataConsentText.KIND_FILE),
                j.optString("name", ""), j.optLong("size", 0L)
            )
            "data_ans" -> {
                val mid = j.optString("mid", "")
                dataAnsBoxes[mid]?.offer(j.optBoolean("ok", false))
            }
            "busy" -> {
                // 只可能来自**旧版本**对端：它还是"同一时刻只服务一个对端"。
                // 新版本不再发这个帧，也不再做退避轮询 —— 本端本来就不自动重试。
                val holder = j.optString("holder", "")
                log(
                    "对端（旧版本）返回忙" + (if (holder.isEmpty()) "" else "：正与 $holder 会话") +
                        "，本端不自动重试，稍后再点一次设备"
                )
                stateNote = "对端忙（旧版本同时只服务一个对端），稍后再点一次"
                s.close()
                sessions.remove(s)
                if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
                notifyPeers()
                refreshState()
            }
            else -> log("未知帧类型: ${j.optString("type")}")
        }
    }

    /**
     * 握手完成：把这条会话并入会话集合。
     *
     * **不同设备一律照收**（多设备独立长连接）—— 不再回 busy 顶掉别人。
     * 只对「同一台设备的双连接」去重：两端同时启动会各连一次，两端各自独立算出
     * `preferOutgoing = (myId < peerId)` 得到同一结论，不会各留一条。
     */
    internal fun onHello(s: Session, j: JSONObject) {
        val pid = j.optString("id", "")
        if (pid.isEmpty()) {
            s.close()
            sessions.remove(s)
            return
        }
        s.peerId = pid
        s.peerName = j.optString("name", pid)
        s.handshaked = true

        // ===== 能力协商（§4.3 兼容性闸门）=====
        // 老版本对端不带这几个字段 → vid=false → 本端**永不**发视频帧
        // （老端 readFrame 遇到未知二进制子类型 0x03 会 IOException 直接断链）
        s.caps.vid = j.optBoolean("vid", false)
        s.caps.cam = j.optBoolean("cam", false)
        val vc = mutableListOf<String>()
        val arr = j.optJSONArray("vcodec")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) vc.add(v)
            }
        }
        s.caps.vcodec = vc
        s.caps.hs = j.optBoolean("hs", false)
        s.caps.hd = j.optBoolean("hd", false)
        s.caps.dl = j.optBoolean("dl", false)
        // 数据流量确认（v2.7）：老端不带该位 → 不发 data_ask，退回「对端不在局域网段就提醒本机」老口径
        s.caps.dm = j.optBoolean("dm", false)

        log(
            "握手成功 ← ${s.peerName}($pid) ${s.remoteIp} ${if (s.outgoing) "出向" else "入向"}" +
                " caps[vid=${s.caps.vid}, cam=${s.caps.cam}, vcodec=$vc, hs=${s.caps.hs}, hd=${s.caps.hd}, dl=${s.caps.dl}]"
        )
        // 「双方连接过」到这里才成立 → 记进已知设备名单（消息/文件往来的那半边上限另计）。
        // 放在去重逻辑之前：同设备双连接被丢弃的那条也是一次真实的连接。
        runCatching { trusted?.markConnected(s.remoteIp, s.peerName, pid) }

        // ===== 设备身份绑定（2026-09-16）=====
        // 对方每次握手都会带一次自己的 id → 本机把它绑到**当前这个 IP** 上。
        // 同一 id 绑了多个 IP = 同一台设备有多个地址 → Prefs 那边自动成组
        //（备注、聊天记录、旋转记忆都按设备共用一份）。
        // 必须早于 hsync / flushPending：那两步都按「设备组键」读写历史与待发。
        val bound = runCatching { prefs.bindDeviceIfFree(s.remoteIp, pid) }.getOrDefault(false)
        if (bound) {
            log("设备身份绑定：${prefs.describeDevice(s.remoteIp)}")
            // 绑定变了 = 这台设备的组键可能从「IP」变成「deviceId」（新地址并进已有设备）：
            // 通知界面重建设备列表，并由 AppCore 在组键真的变化时**重新打开该设备的聊天界面**
            //（否则会停在旧组键读出来的空列表上，看着像记录丢了；2026-09-16 用户反馈的 bug）。
            notifyPeers()
        }
        // 入向连接且本机列表里还没有这个地址 → 登记一条（不覆盖用户备注）。
        // 与 PC 端对称（PC 在 onHello 里 upsertPeer）：对端若能单向上连过来、
        // 而广播收不到（防火墙/网段），握手就是本机拿到它身份的唯一来源，
        // 不登记的话这台设备在抽屉里根本不出现。
        runCatching {
            if (prefs.peers.none { it.ip == s.remoteIp }) {
                prefs.upsertPeer(
                    s.remoteIp, remark = null, lastId = pid, lastName = s.peerName,
                    seenAt = System.currentTimeMillis()
                )
                log("新设备已登记：${s.peerName} @ ${s.remoteIp}")
            }
        }

        var reject = false
        val exist = byPeer[pid]
        if (exist != null && exist !== s && !exist.closed && exist.handshaked) {
            // 同设备双连接（两端同时启动各连了一次）：保留符合 preferOutgoing 的那条
            val preferOutgoing = myId < pid
            val take = (s.outgoing == preferOutgoing)
            log("重复会话去重：${if (take) "保留新连接" else "保留旧连接"}（同设备双连接）")
            if (!take) {
                reject = true
            } else {
                exist.close()
                sessions.remove(exist)
                byPeer.remove(pid, exist)
            }
        }
        if (reject) {
            s.close()
            sessions.remove(s)
            return
        }
        byPeer[pid] = s
        // 连上了 → 保活重连任务完成使命
        cancelKeepAlive(s.remoteIp)
        // 这台设备已可达 → 撤掉还没轮到的候选地址（可能是被动连上，也可能是对端抢先拨通）
        clearPendingFor(s.remoteIp)

        // 第一台连上的设备默认成为当前对话目标；**当前选中目标没有健康会话时也跟过去**——
        // 否则 selectedIp 停在旧 IP/旧设备上：状态恒显「未连接」、发消息进待发队列收不到
        //（2026-09-16 修复：通话/传文件按会话工作、顶栏却显示未连接的根源）。
        // 判据必须**按设备**（组内任意地址有健康会话），不能用精确 IP：同一台设备常有两个地址
        //（换热点/多网卡，界面选 .13、会话建在 .210），sessionOf(.13)==null 会让**另一台**设备
        // 的握手把当前对话抢走（与 PC 端 FileDrop「绿点亮着却说未连接」同一类 bug，2026-09-30）
        if (selectedIp.isEmpty() || sessionByGroup(selectedIp) == null) {
            selectedIp = s.remoteIp
            selectedLabel = s.peerName
        }
        notifyPeers()
        refreshState()
        // 这台设备可达 → 撤掉"没网络"那一票；紧跟着下面那句既有的 flushPending 负责续发
        //（setFileVote 对这一票**不**再自动 flush，避免同一条目排两次）
        setFileVote(VOTE_NO_NETWORK, false)
        // 只补发**发给这台设备**的待发内容；剪贴板不做任何握手协商（读取只由焦点切换触发）
        flushPending(s.remoteIp)
        // 历史同步：双方都支持（hs=1）时互报 mid 清单、各取所需（见 onHsyncMids）
        if (s.caps.hs) {
            Thread({ startHsync(s) }, "pp-hsync").apply { isDaemon = true }.start()
        }
    }

    /** 这条会话是不是当前对话目标 */
    internal fun isSelected(s: Session): Boolean = s.remoteIp == selectedIp

    internal fun setState(st: State, peer: String) {
        if (state == st && peerLabel == peer) return
        state = st
        peerLabel = peer
        listener?.onState(st, peer)
    }

    /**
     * 依据「会话集合 + 当前选中目标」重新算一次对外状态。
     *
     * 状态一律**推导**而不是命令式赋值：去重会主动关掉多余的那条连接，
     * 若靠"关连接时顺手置位"，两条同 IP 的会话会互相踩。推导天然不受顺序影响。
     */
    internal fun refreshState() {
        val st: State
        val label: String
        if (!running) {
            st = State.STOPPED
            label = ""
        } else if (target() != null) {
            st = State.CONNECTED
            label = selectedLabel.ifEmpty { selectedIp }
        } else if (connectingIp != null) {
            st = State.CONNECTING
            label = ""
        } else {
            st = State.IDLE
            label = stateNote
        }
        setState(st, label)
    }

    /** 会话集合变化（连上 / 断开）时通知 UI 刷设备列表 */
    internal fun notifyPeers() {
        listener?.onPeersChanged()
    }

    // ==================== 文件：接收（双 4MB 缓冲 + 断点续传） ====================

    /**
     * 一个正在接收的文件。
     *
     * 双缓冲：数据先进 [bufA]/[bufB]（各 4MB），一块填满就交给落盘线程、自己切到另一块继续填；
     * 两块都未落完时才阻塞（天然背压）。[digest] 覆盖整个文件（含续传前缀），
     * file_done 时与发送端整文件 sha256 比对。
     */
    internal val rxMap = HashMap<String, Rx>()
    internal val dirMap = HashMap<String, DirRx>()

    /**
     * 被停滞暂停的单文件 mid（v2.8）：下一个进度帧（file_meta 重到 / 续传首个 chunk）
     * 到达时气泡回「发送中」并清掉标记。目录成员不用标记 —— 其首个续传 chunk 本来
     * 就把目录气泡发成 SENDING。
     */
    internal val pausedMids = ConcurrentHashMap.newKeySet<String>()

    /** 保留多少条「已收尾」的目录记录（供同一 mid 重发时复用目录名，见 pruneDirs） */
    internal val MAX_KEEP_DIRS = 32

    /** 发送端等 file_resume 应答用：mid → 容量为 1 的信箱 */
    internal val resumeWaiters =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Long>>()

    /**
     * 发起端等 `data_ans`（数据流量确认）应答用：mid → 容量为 1 的信箱。
     * 与 [resumeWaiters] 同一套路：读完即从表里摘掉，不会随传输次数增长。
     */
    internal val dataAnsBoxes =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Boolean>>()

    /** 启动落盘线程：从 pending 取满块写盘，注册表同步刷新（供断点续传跨重启找回半成品） */

    // ==================== 发送 ====================

    /**
     * 发送线程池（单线程，保序）。
     *
     * 关键：剪贴板推送的触发点是 onWindowFocusChanged / onPause / 前台 1s 轮询 / UI 按钮，
     * 这些都在主线程上。socket 写必须离开主线程，否则抛 NetworkOnMainThreadException
     * （Android 12 实测踩过）。所有对外发送入口统一经过这里。
     */
    @Volatile
    internal var sendExec: java.util.concurrent.ExecutorService =
        newSendExec()

    private fun newSendExec(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pp-send").apply { isDaemon = true }
        }

    internal inline fun io(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            sendExec.execute { block() }
        } else {
            block()
        }
    }

    /**
     * 主动发文本。目标是**当前对话设备**；未连接或发送失败则入待发队列（队列里带着 peer，
     * 只会补发回这台设备）。
     */
    fun sendText(text: String) {
        io {
            val s = target()
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.TEXT, text = text,
                peerIp = s?.remoteIp ?: selectedIp
            )
            // 主动发文本 → 该设备有过消息往来（即刻送达或进待发队列都算一次记录）
            trustContent(item.peerIp, isFile = false)
            if (s != null && sendOrDrop(s, Protocol.text(item.mid, text), "text")) {
                emit(item)
                log("已发送文本 ${text.length} 字 → ${item.peerIp}")
                return@io
            }
            log("文本暂存待发（等待连接）")
            emit(item.copy(status = Status.PENDING))
            queuePending(item)
            wakeUp()
        }
    }

    /**
     * 主动推送本机剪贴板内容。
     *
     * 剪贴板同步**不是「发消息」**：这里不发气泡、不进聊天记录，
     * 对端收到后只是直接写进它自己的剪贴板（冲突时后写覆盖先写）。
     */
    fun sendClip(text: String, sha1: String) {
        io {
            // 记下这份内容：之后从对端回读到的同一份不必再处理（避免重复写剪贴板）
            lastClipEmitSha1 = sha1
            // 剪贴板是同步通道不是消息：广播给**所有已连接设备**（多设备下每台都该拿到）
            val ips = connectedIps
            if (ips.isEmpty()) {
                log("剪贴板暂存待发（当前无已连接设备，连上后自动补发）")
                queuePending(
                    MsgItem(
                        mid = newMid(), dir = Dir.SELF, kind = Kind.CLIP, text = text,
                        peerIp = selectedIp ?: ""
                    )
                )
                wakeUp()
                return@io
            }
            var sent = 0
            for (ip in ips) {
                val s = sessionOf(ip) ?: continue
                if (!clipLinkAllowed(s.remoteIp)) continue
                sendOrDrop(s, Protocol.clip(newMid(), text, sha1), "clip")
                sent++
            }
            if (sent > 0) log("已推送剪贴板到 $sent 台设备（${text.length} 字）")
        }
    }

    /** 已被本端取消的文件 mid 集合：发送循环每轮检查，命中即中断 */
    internal val cancelled: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 文件夹发送状态：dirMid → 已完成的成员 relPath（重发时跳过） */
    internal val dirSendState = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()

    internal fun isCancelled(mid: String, dirId: String): Boolean =
        cancelled.contains(mid) || (dirId.isNotEmpty() && cancelled.contains(dirId))

    // ==================== 数据流量确认（v2.7）====================

    /** 供上层为一次确认生成 mid（确认帧与真正的传输共用同一个号，便于对账） */
    fun newConsentMid(): String = newMid()

    /**
     * 发起文件/语音/视频前统一过这道闸门。**会阻塞**：
     *  - 本机在数据网络 → 弹框等本机用户点（每次使用都提醒，不记忆已同意的对端）
     *  - 对端支持 `dm` → 发 `data_ask` 等对端回 `data_ans`（对端不在数据网络时立即自动同意）
     *
     * ⚠ **绝不能在主线程 / 网络读线程 / `io{}`（pp-send 单线程）里调用** ——
     * 前者会 ANR，后两者会把整个发送队列或会话读循环卡住。调用点一律用专用线程。
     */
    fun ensureDataConsent(
        peerIp: String, mid: String, kind: String, label: String,
        name: String = "", size: Long = 0L
    ): DataConsent {
        val s = sessionByGroup(peerIp) ?: return DataConsent.NO_SESSION
        val peerDm = s.caps.dm
        // ① 本机提醒：本机在运营商数据网络上；对端是老版本时退回改造前口径
        //    （对端 IP 不在局域网段 = 这条链路没走局域网 → 老版本下仍提醒本机用户）
        val onCellular = runCatching { localOnCellular?.invoke() == true }.getOrDefault(false)
        if (onCellular || (!peerDm && !LanGate.isLanPeerIp(s.remoteIp))) {
            val ok = runCatching { localDataConsent?.invoke(s.remoteIp, label) == true }
                .getOrDefault(false)
            if (!ok) {
                log("本机未同意为「$label」消耗数据流量，已取消")
                return DataConsent.LOCAL_REFUSED
            }
        }
        // ② 对端确认：对端在它自己的数据网络下会提醒它自己的用户；不在数据网络则立即回同意
        if (peerDm && !askPeerDataConsent(s, mid, kind, name, size)) {
            return DataConsent.REMOTE_REFUSED
        }
        return DataConsent.OK
    }

    /** 发 data_ask 并等 data_ans；超时/发送失败一律按「未同意」处理（宁可少发，不偷偷花流量） */
    internal fun askPeerDataConsent(
        s: Session, mid: String, kind: String, name: String, size: Long
    ): Boolean {
        val box = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        dataAnsBoxes[mid] = box
        return try {
            if (!sendOrDrop(s, Protocol.dataAsk(mid, kind, name, size), "data_ask", quiet = true)) {
                log("数据流量确认：对端会话不可用，按未同意处理")
                return false
            }
            val r = runCatching { box.poll(DATA_ANS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull()
            if (r == null) {
                log("数据流量确认：对端 ${DATA_ANS_WAIT_MS / 1000}s 内未应答，按未同意处理")
                false
            } else {
                r
            }
        } finally {
            dataAnsBoxes.remove(mid)
        }
    }

    /**
     * 应答对端的数据流量确认请求（AppCore 在用户点完按钮 / 判定本机非数据网络后调用）。
     * 内部走 `io{}`，可从读线程与主线程安全调用。
     */
    fun replyDataAsk(peerIp: String, mid: String, ok: Boolean) {
        if (mid.isEmpty()) return
        io {
            val s = sessionByGroup(peerIp)
            if (s == null) {
                log("数据流量确认应答丢弃：与 $peerIp 已无会话")
                return@io
            }
            sendOrDrop(s, Protocol.dataAns(mid, ok), "data_ans", quiet = true)
        }
    }

    // ==================== 主动发送 ====================

    /**
     * 主动发文件（发给当前对话设备）。
     *
     * v2.7 起把「数据流量确认」插在出气泡与真正开传之间：
     *   出气泡(PENDING) → 本机提醒/对端确认 → 同意才 SENDING 开传；
     *   任一侧拒绝 → 气泡留在聊天里置 [Status.DENIED]（红色叹号可点重试）。
     * 确认框要等用户点按钮、还要等对端回帧，所以整段必须跑在**专用线程**上。
     */
    fun sendFile(uri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val name = FileIO.queryName(ctx, uri)
            val size = FileIO.querySize(ctx, uri)
            val s = sessionByGroup(ip)
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                fileSize = size, filePath = uri.toString(), localUri = uri.toString(), progress = 0,
                peerIp = s?.remoteIp ?: ip
            )
            // 主动发文件 → 该设备有过文件往来（已知设备判定的一半）
            trustContent(item.peerIp, isFile = true)
            // 2.12 / D16：投票为 1（通话中 / 没网络 / 被拒）⇒ 一律**不入网**，改走既有的待发台账排队，
            //   拦截解除（全 0）那一刻由 setFileVote 自动触发一次续发。拦在**入口**而不是写者里
            //   （写者里拦 = 字节已入队，等于没拦）。
            if (s == null || fileGateBlocked()) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true)
                    log("离线，文件 $name 进入待发队列")
                } else {
                    log("[文件排他] 通话期不入网：文件 $name 排队，结束后自动续发（D16）")
                }
                wakeUp()
                return@Thread
            }
            emit(item.copy(status = Status.PENDING))
            // ⚠ 确认 + 传输整段进【串行队列】（2026-09-21 排队发送）：一次只传一个文件，
            //   前一个完成立刻上下一个。排队期间气泡是 PENDING（等待中），真正开传才刷 SENDING。
            enqueueFileSend("发送文件 $name") {
                val cs = sessionByGroup(item.peerIp)
                if (cs == null) {
                    okUpdate(item.copy(status = Status.PENDING))
                    queuePending(item)
                    setFileVote(VOTE_NO_NETWORK, true)   // 2.12：排队期间掉线 = "原本没网络"那一票
                    log("排队期间会话断开，文件 $name 进入待发队列")
                    wakeUp()
                    return@enqueueFileSend
                }
                val fileKind = DataConsentText.KIND_FILE
                val consent = if (preGranted) DataConsent.OK else
                    ensureDataConsent(cs.remoteIp, item.mid, fileKind, "发送文件", name, size)
                when (consent) {
                    DataConsent.OK -> {
                        okUpdate(item.copy(status = Status.SENDING))
                        doSendFile(uri, item)
                    }
                    DataConsent.LOCAL_REFUSED -> denyFileByData(item, LOCAL_DENY_TEXT)
                    DataConsent.REMOTE_REFUSED ->
                        denyFileByData(item, DataConsentText.peerRefusedText(fileKind))
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true)   // 2.12：确认期间掉线 = "原本没网络"票
                        okUpdate(item.copy(status = Status.PENDING))
                        queuePending(item)
                        log("确认期间会话断开，文件 $name 进入待发队列")
                        wakeUp()
                    }
                }
            }
        }, "pp-send-file").apply { isDaemon = true }.start()
    }

    /**
     * 主动发文件夹（发给当前对话设备），空文件夹也照发（fileCount=0）。
     *
     * @param treeUri 两种来源都支持（见 [FileIO.scanTree]）：系统 SAF 选的目录树，
     *   或**内置文件浏览器**长按选中的本机文件夹（`file://`，root 模式下为 /data/media/0 路径）。
     */
    fun sendFolder(treeUri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val scan = FileIO.scanTree(ctx, treeUri)
            val files = scan.files
            if (scan.skipped > 0) {
                log("注意：目录过深/过大或部分子树读取失败，${scan.skipped} 个分支未包含在传输中")
            }
            // 一个文件都没读到、却又有整块读失败：这是「读不到」而不是「空的」。
            // 老逻辑会照发一个 count=0 的文件夹，用户以为传成功了，实际什么都没传。
            if (files.isEmpty() && scan.skipped > 0) {
                log("文件夹内容读取失败（${scan.skipped} 个分支未读到），已中止发送 —— 请确认该文件夹仍可访问")
                return@Thread
            }
            val name = FileIO.queryName(ctx, treeUri)
            val total = files.fold(0L) { acc, f -> acc + f.size.coerceAtLeast(0) }
            val s = sessionByGroup(ip)
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                fileSize = total, filePath = treeUri.toString(), localUri = treeUri.toString(),
                progress = 0, isDir = true, dirCount = files.size,
                peerIp = s?.remoteIp ?: ip
            )
            // 主动发文件夹 → 同上（空文件夹也打点，与接收侧 dir_meta 对称）
            trustContent(item.peerIp, isFile = true)
            // 2.12 / D16：同 sendFile —— 投票 = 1 时文件夹也不入网，整目录排队
            if (s == null || fileGateBlocked()) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true)
                    log("离线，文件夹 $name（${files.size} 个文件）进入待发队列")
                } else {
                    log("[文件排他] 通话期不入网：文件夹 $name（${files.size} 个文件）排队，结束后自动续发（D16）")
                }
                wakeUp()
                return@Thread
            }
            emit(item.copy(status = Status.PENDING))
            // 同上：整个文件夹算**一个**排队任务，其成员在队列线程内顺序发（一次仍只有一个文件在传）
            enqueueFileSend("发送文件夹 $name") {
                val cs = sessionByGroup(item.peerIp)
                if (cs == null) {
                    okUpdate(item.copy(status = Status.PENDING))
                    queuePending(item)
                    setFileVote(VOTE_NO_NETWORK, true)   // 2.12：排队期间掉线 = "原本没网络"那一票
                    log("排队期间会话断开，文件夹 $name 进入待发队列")
                    wakeUp()
                    return@enqueueFileSend
                }
                val kind = DataConsentText.KIND_FILE
                val consent = if (preGranted) DataConsent.OK else
                    ensureDataConsent(cs.remoteIp, item.mid, kind, "发送文件夹", name, total)
                when (consent) {
                    DataConsent.OK -> {
                        okUpdate(item.copy(status = Status.SENDING))
                        doSendFolder(treeUri, item, files, scan.emptyDirs, scan.skipped)
                    }
                    DataConsent.LOCAL_REFUSED -> denyFileByData(item, LOCAL_DENY_TEXT)
                    DataConsent.REMOTE_REFUSED ->
                        denyFileByData(item, DataConsentText.peerRefusedText(kind))
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true)   // 2.12：确认期间掉线 = "原本没网络"票
                        okUpdate(item.copy(status = Status.PENDING))
                        queuePending(item)
                        wakeUp()
                    }
                }
            }
        }, "pp-send-dir").apply { isDaemon = true }.start()
    }

    /** 本机拒绝用数据流量时的备注（气泡上那一行，只在 [Status.DENIED] 时显示） */
    internal val LOCAL_DENY_TEXT get() = "未同意使用数据流量"

    /**
     * 把一条文件消息置为「数据流量未同意」：气泡**留在聊天里**，末尾显示红色叹号（可点重试）。
     * 备注写进 `text` —— 文件消息的 text 从不参与协议与 hsync（只有 TEXT/图片会同步），
     * 界面也只在 DENIED 状态下才渲染它，因此不会污染其它场景。
     *
     * @param voteDenied 2.12 / D16：**只有真的"被拒"才投第三票**（全局闸门）。
     *   本方法同时是所有失败出口的共用出口（"设备未连接"、"文件夹已不可读"、
     *   "重试要重新选目录"都从这儿走），那些**不是**用户拒绝，绝不能投 1 ——
     *   否则一次偶发的会话断开就会把整条文件管线锁到用户下一次手动重试。
     *   这类出口请显式传 false，并把原因归到 [VOTE_NO_NETWORK]（握手完成自动清）。
     */
    internal fun denyFileByData(item: MsgItem, note: String, voteDenied: Boolean = true) {
        store?.removePending(item.mid)
        okUpdate(item.copy(status = Status.DENIED, progress = -1, text = note))
        log("文件「${item.fileName}」未发送：$note")
        if (voteDenied) {
            // 2.12 / D16：被拒 = 全局票，清零点只有一个 = 用户在气泡上手动重试（retryFileSend 入口）
            setFileVote(VOTE_CONSENT_DENIED, true)
        }
    }

    /**
     * 重试一条「数据流量未同意」的文件（气泡末尾的红色叹号触发）。
     * 重新完整走一遍提醒流程；同意 → 由 [Listener.onMessageMoved] 通知界面把这条挪到最新位置，
     * 然后开始发送；再次拒绝 → 保持 DENIED（与第一次拒绝后的表现完全一致）。
     */
    fun retryFileSend(
        mid: String, uri: Uri, name: String, size: Long, isDir: Boolean, peerIp: String
    ) {
        enqueueFileSend("重试发送 $name") {
            // 2.12 / D16（用户点的 ⑬-2）：手动重试就是"被拒"这一票的清零点。
            // 放在入口而不是成功后 ⇒ 重试若再次被拒，denyFileByData 会重新投 1
            //（与今天"每次拒绝都回 DENIED"一致）。
            setFileVote(VOTE_CONSENT_DENIED, false)
            val s = sessionByGroup(peerIp)
            if (s == null) {
                setFileVote(VOTE_NO_NETWORK, true)   // 归票 ②：握手完成自动清，不靠下一次手动重试
                denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    "设备未连接，点设备重连后再试",
                    voteDenied = false
                )
                return@enqueueFileSend
            }
            val label = if (isDir) "发送文件夹" else "发送文件"
            val kind = DataConsentText.KIND_FILE
            when (ensureDataConsent(s.remoteIp, mid, kind, label, name, size)) {
                DataConsent.OK -> {
                    listener?.onMessageMoved(mid)
                    val item = MsgItem(
                        mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                        fileSize = size, filePath = uri.toString(), localUri = uri.toString(),
                        progress = 0, isDir = isDir, status = Status.SENDING, peerIp = s.remoteIp
                    )
                    okUpdate(item)
                    store?.removePending(mid)
                    if (isDir) {
                        val scan = FileIO.scanTree(ctx, uri)
                        if (scan.files.isEmpty() && scan.emptyDirs.isEmpty() && scan.skipped == 0) {
                            log("重试失败：文件夹 $name 已读不到内容")
                            // 本地读不到 ≠ 用户拒绝，不许投全局票
                            denyFileByData(item, "文件夹已不可读", voteDenied = false)
                            return@enqueueFileSend
                        }
                        doSendFolder(uri, item, scan.files, scan.emptyDirs, scan.skipped)
                    } else {
                        doSendFile(uri, item)
                    }
                }
                DataConsent.LOCAL_REFUSED -> denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    LOCAL_DENY_TEXT
                )
                DataConsent.REMOTE_REFUSED -> denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    DataConsentText.peerRefusedText(kind)
                )
                DataConsent.NO_SESSION -> {
                    // 会话在这一步断了：归票 ②（握手完成自动清），不是用户拒绝 ⇒ 不投全局票
                    setFileVote(VOTE_NO_NETWORK, true)
                    denyFileByData(
                        MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                        "设备未连接，点设备重连后再试",
                        voteDenied = false
                    )
                }
            }
        }
    }

    /**
     * 待发队列的自动补发（重连后 [flushPendingNow] 甩到这条专用线程跑）。
     * 与手动重试（[retryFileSend]）走**同一道数据流量闸门** —— 对端在线且在数据网络时同样会
     * 收到 data_ask 弹窗，本机在数据网络同样会弹本机确认；「每次使用都提醒一次」对补发同样成立。
     * 拒绝 → 条目移出待发队列、气泡置 DENIED（红色叹号可再手动重试），不会反复骚扰。
     */

    /**
     * 待发条目的目的会话：优先按条目自带的 [MsgItem.peerIp]（断线前记下的目标设备），
     * 为空时（旧版本写下的条目没有 peer 字段）才回落到当前对话设备。
     * 绝不"随便找一台连着就发" —— 那正是内容串台。
     */
    internal fun sessionForPending(peerIp: String): Session? =
        sessionByGroup(peerIp) ?: if (peerIp.isEmpty()) target() else null

    /**
     * 取「某台设备」的会话：先按 IP 精确匹配，找不到时回落到**同设备（同组键）**的
     * 任意健康会话。
     *
     * 为什么需要回落（2026-09-16 设备分组）：一台设备可能有多个地址，用户在顶栏
     * 切到其中一个（比如切到热点地址）而会话实际建在另一个地址（WiFi）上时，
     * 按 IP 精确找会「找不到会话」→ 消息进待发、通话发不出去，
     * 明明同一台设备就在线。组键相等即视为同一台设备。
     */
    internal fun sessionByGroup(ip: String): Session? {
        sessionOf(ip)?.let { return it }
        val v = ip.trim()
        if (v.isEmpty()) return null
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        if (k.isEmpty()) return null
        for (s in sessions) {
            if (s.handshaked && !s.closed && runCatching { prefs.convKeyOf(s.remoteIp) }.getOrNull() == k) {
                return s
            }
        }
        return null
    }

    /** 发送端等「对端文件夹核对回执」用：dirMid → 容量 1 的信箱（[onDirMissing] 投递） */
    internal val dirMissingBox =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<List<String>>>()

    /** 对端报缺失后最多补发几轮；再缺说明是真发不过去，如实报失败而不是死循环 */
    internal val DIR_RESEND_ROUNDS = 2

    /** 等 dir_missing 回执的时长：对端要先把本轮全部成员落盘、算校验和才核对 */
    internal val DIR_MISSING_WAIT_MS = 60_000L

    /** dir_meta 成员清单在线上最多多少字符（超了就不带，退化为「只按数量核对」，避免撑爆 8MB 帧上限） */
    internal val DIR_LIST_MAX_CHARS = 2 * 1024 * 1024




    /** 发送端在途核对表（普通单文件续传才登记） */
    internal val verifyMap = ConcurrentHashMap<String, VerifyState>()

    /** 核对超时：大文件 MD5 双方都要读一遍全盘，给足 2 分钟；超时只记日志不改传输结果 */
    internal val verifyTimeoutMs = 120_000L


    /**
     * 取消一个正在发送 / 待发送的文件或文件夹。两端同步：
     * ① 本端把该条从界面移除，并从待发队列与历史里删掉；
     * ② 发一帧 `file_cancel`，对端收到后中止接收、删除半成品、同样移除展示。
     *
     * 发送循环会在下一轮读到 `cancelled` 并自行中断（不再发 `file_done`）。
     */
    fun cancelFile(mid: String) {
        if (mid.isEmpty()) return
        io {
            cancelled.add(mid)
            dirSendState.remove(mid)
            // 先查目的设备：取消通知要发给那台，别发给当前对话的另一台
            val dest = store?.loadPending()?.firstOrNull { it.mid == mid }?.peerIp.orEmpty()
            store?.removePending(mid)
            listener?.onMessageRemoved(mid)
            val s = sessionOf(dest) ?: if (dest.isEmpty()) target() else null
            if (s != null) {
                sendOrDrop(s, Protocol.fileCancel(mid), "file_cancel")
            }
            log("已取消文件传输 $mid")
        }
    }

    // ==================== 语音通话发送 ====================

    /**
     * 向指定 IP 的设备发一帧音频。会话不存在/发送失败只记日志（通话层自行兜底静音跳过）。
     *
     * @return 写入耗时 ms；-1 = 未发送（无会话 / 异常）—— 语音码率自适应用
     */
    fun sendAudioTo(ip: String, seq: Int, ts: Long, data: ByteArray, len: Int): Long {
        val s = sessionOf(ip) ?: return -1L
        return try {
            s.sendAudio(seq, ts, data, 0, len)
        } catch (t: Throwable) {
            log("音频帧发送失败(${t.javaClass.simpleName}): ${t.message}")
            -1L
        }
    }

    /**
     * 语音通话控制帧（invite / accept / reject / busy / end / cancel）。
     *
     * ⚠ 这六个入口**全部由 UI 回调（主线程）调用**，必须走 io{}：
     * 直接在主线程写 socket 会抛 NetworkOnMainThreadException，而 sendOrDrop 的语义是
     * 「任何异常都判该会话失效」→ 点一下通话按钮就把连接关掉，用户感知就是
     * **「点击立即断线 / 设备未连接」**（2026-09-15 a16 logcat 实测定位：
     * `call_invite 发送失败(NetworkOnMainThreadException) → 会话结束 → 无法发起通话`）。
     *
     * 返回「有没有可用会话」而不是「是否已写出」：写操作已投递到 pp-send 线程，尽力而为。
     */
    fun sendCallInvite(
        name: String, ip: String, vid: Boolean = false, lv: Int = 1, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ): Boolean {
        if (target() == null) return false
        io {
            target()?.let {
                sendOrDrop(it, Protocol.callInvite(name, ip, vid, lv, af, mport, mudp, msid), "call_invite")
            }
        }
        return true
    }

    /** 同意接听（发给指定 IP 的设备）。vid=是否接受视频，lv=可接受档位（两端取 min），af=期望语音帧长；末三项=本端媒体通道三元组，语义同 [sendCallInvite]。 */
    fun sendCallAccept(
        ip: String, vid: Boolean = false, lv: Int = 1, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(
                    it, Protocol.callAccept(vid, lv, af, mport, mudp, msid), "call_accept", quiet = true
                )
            }
        }

    /**
     * 振铃期逐档交换本端媒体三元组（任务单 2.5 / 方案 §4.3①）。
     * 字段语义见 [Protocol.callMedia]。
     *
     * ⚠ 由 **pp-av-negotiate 线程**调用（振铃期的定档时序），不是主线程；仍走 [io] 的 pp-send
     *   队列 —— 与上面 [sendCallInvite] 那条实测教训同一条：任何通话控制帧都不能在调用线程上写 socket。
     *
     * @return 有没有可用会话（写出已入队，尽力而为）；false = 主 TCP 已无会话 ⇒ 调用方按 ⑦ 处置
     */
    fun sendCallMedia(ip: String, mport: Int, mudp: Boolean, msid: String, tierOrdinal: Int): Boolean {
        val s = sessionOf(ip) ?: return false
        io { sendOrDrop(s, Protocol.callMedia(mport, mudp, msid, tierOrdinal), "call_media", quiet = true) }
        return true
    }

    fun sendCallReject(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callReject(), "call_reject", quiet = true) } }

    fun sendCallBusy(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callBusy(), "call_busy", quiet = true) } }

    fun sendCallEnd(ip: String, reason: String = "") =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callEnd(reason), "call_end", quiet = true) } }

    fun sendCallCancel(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callCancel(), "call_cancel", quiet = true) } }

    // ==================== 视频通话发送 ====================

    /** 该设备的对端能力（未握手/未连接返回 null）。按**设备**找会话（见 [sessionByGroup]） */
    fun peerCapsOf(ip: String?): PeerCaps? = sessionByGroup(ip.orEmpty())?.caps

    /**
     * 本端能力晚到（如解码器枚举在后台线程完成）时，把所有已握手会话的
     * `hello` 重发一次，让对端及时看到最新能力。
     */
    fun resendHello() {
        io {
            val j = Protocol.hello(
                myId, myName, Build.MODEL ?: "android",
                localVidCap, localCamCap, localVCodecs
            )
            for (s in sessions) {
                if (s.handshaked && !s.closed) sendOrDrop(s, j, "hello", quiet = true)
            }
            log("已重发 hello（vid=${localVidCap} cam=${localCamCap} vcodec=${localVCodecs}）")
        }
    }

    /** 对端是否声明可接收视频（**发任何视频帧前的硬闸门**） */
    fun peerVidCap(ip: String?): Boolean = peerCapsOf(ip)?.vid == true

    /**
     * 向指定 IP 的设备发一帧视频。
     *
     * **闸门**：对端未广播 `vid=1` 时直接丢弃 —— 老端 readFrame 遇到 0x03 会
     * 抛 IOException 断链，这是向后兼容的关键（见 §4.3）。
     *
     * @param flag VIDEO_FLAG_KEYFRAME / VIDEO_FLAG_CONFIG / 0
     * @return 写入耗时 ms；-1 = 未发送（无会话 / 对端无能力 / 超 128KB 闸门）
     */
    fun sendVideoTo(ip: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int): Long {
        if (len > Protocol.MAX_VIDEO_FRAME) {
            log("视频帧超 128KB 闸门（${len}B），丢弃以免长时间独占写锁")
            // 计数器口径（CallCounters 文件头）：被单帧闸门拦下**必须**计数并上报，
            // 否则这条静默丢就是下一个"lat 恒 0"——建了名字没人加。
            com.phonepctransfer.util.CallCounters.videoDroppedByGate.inc()
            return -1L
        }
        val s = sessionOf(ip) ?: return -1L
        if (!s.caps.vid) return -1L
        return try {
            s.sendVideo(seq, ts, flag, data, 0, len)
        } catch (t: Throwable) {
            log("视频帧发送失败(${t.javaClass.simpleName}): ${t.message}")
            -1L
        }
    }

    /**
     * 编码参数变更通知（控制帧，主线程安全）。
     *
     * @param front 本端摄像头是否前置（`video_cfg.cam`），接收端据此选默认显示角
     */
    fun sendVideoCfg(
        ip: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int, front: Boolean
    ) = io {
        sessionOf(ip)?.let {
            sendOrDrop(
                it, Protocol.videoCfg(codec, w, h, fps, br, rot, front), "video_cfg", quiet = true
            )
        }
    }

    /** 接收侧健康反馈（每 2s 一次，供对端升降档）。dropping = 本机此刻正在丢帧（任务单 1.3） */
    fun sendVideoFb(ip: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(it, Protocol.videoFb(fps, drop, lat, dropping, kbps), "video_fb", quiet = true)
            }
        }

    /** 接收端请求临时停/开视频（如切后台） */
    fun sendVideoReq(ip: String, on: Boolean) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(it, Protocol.videoReq(on), "video_req", quiet = true)
            }
        }

    /**
     * 当前对话**实际可达**的对端地址（通话呼叫目标）；未选中返回空串。
     *
     * 为什么优先返回会话的 remoteIp 而不是 selectedIp：设备分组之后，用户在顶栏
     * 把当前地址切到组内另一个（比如切到热点地址）而会话仍建在原地址上时，
     * 消息仍会走原会话；此时若把 selectedIp 报给 CallManager，通话应答帧的源地址
     * 与它记的 peerIp 对不上 → 呼叫会卡在「正在呼叫」永不接通。
     */
    fun currentPeerIp(): String = target()?.remoteIp ?: selectedIp

    /** 手动同步：只对当前对话设备冲刷待发内容（点一次试一次，不重连其他设备；剪贴板纯被动不拉） */
    fun syncNow() {
        io {
            log("手动同步")
            val s = target()
            if (s != null) {
                sendOrDrop(s, Protocol.syncReq(), "sync_req")
                flushPendingNow(s.remoteIp)
            } else {
                wakeUp()
            }
        }
    }

    // ==================== 待发队列 ====================

    /** 由 AppCore 注入，避免 Link 直接依赖 Store */
    var store: com.phonepctransfer.data.Store? = null


    /** 对外入口：可能被主线程调用，统一转到发送线程。补发所有连着的设备各自的条目 */
    fun flushPending() {
        io { flushPendingNow(null) }
    }

    /** 只补发**发给某台设备**的待发条目（某台刚握手成功时调用） */
    fun flushPending(onlyIp: String) {
        io { flushPendingNow(onlyIp) }
    }


    // ==================== 消息删除同步（v2.4，仅双方 hd=1 时使用）====================

    /**
     * 本机删除一条文本/图片后通知对端同步移除。
     * 只发帧，不碰本地数据（界面/历史/待发由 AppCore 统一清理）。
     * 对端不在线或老版本（hd=0）时静默跳过 —— 此时删除只影响本机，
     * 这条内容下次连接会被 hsync 自动补回来（对端历史里还在，属预期行为）。
     */
    fun deleteMessage(peerIp: String, mid: String) {
        if (mid.isEmpty()) return
        // 按设备找会话：删除通知要发给「那台设备」，它可能挂在同组的另一个地址上
        val s = sessionByGroup(peerIp) ?: return
        if (!s.handshaked || s.closed || !s.caps.hd) return
        sendOrDrop(s, Protocol.msgDel(listOf(mid)), "msg_del")
        log("已通知对端删除消息 mid=$mid")
    }

    /** 这台设备现在收得到 `msg_del` 吗（在线 + 握手完成 + 对端支持删除同步 hd）。
     *  界面要在**确认之前**给出「对方在线/不在线」的口径，不能等发完了再猜。 */
    fun canSendMsgDel(peerIp: String): Boolean {
        val s = if (peerIp.isEmpty()) null else sessionByGroup(peerIp)
        return s != null && s.handshaked && !s.closed && s.caps.hd
    }

    /**
     * 批量删除通知（「清空会话」逐条删除用）：把**参与同步**的 mid（文本 + 图片）
     * 分片发 `msg_del`。只发帧 —— 本地历史/待发/界面由 AppCore 一次清完
     * （见 [AppCore.clearHistory]），别在这里逐条调 [deleteMessage] 又读又写一遍磁盘。
     *
     * 分片：单帧塞几千个 mid 会撞上帧长上限，故每 [MSG_DEL_CHUNK] 条一帧。
     *
     * @return 实际请求对端删除的条数（**0 = 对端不在线/不支持 hd**，本次只清了本机）。
     *         帧走 `io{}` 投递（UI 主线程禁写 socket），能返回非 0 就代表已入列。
     */
    fun deleteMessages(peerIp: String, mids: List<String>): Int {
        if (mids.isEmpty()) return 0
        val s = sessionByGroup(peerIp) ?: return 0
        if (!s.handshaked || s.closed || !s.caps.hd) return 0
        io {
            var i = 0
            while (i < mids.size) {
                val chunk = mids.subList(i, minOf(i + MSG_DEL_CHUNK, mids.size))
                sendOrDrop(s, Protocol.msgDel(chunk), "msg_del")
                i += MSG_DEL_CHUNK
            }
            log("清空：已逐条通知对端删除 ${mids.size} 条（文本/图片）")
        }
        return mids.size
    }

    /**
     * 这台设备名下**还有传输在途**吗（正在收 / 正在发）。
     *
     * 「清空」要在这种情况下拒绝执行 —— 枚举出来的 mid 里就有正在传的那条，
     * 清掉会让落地流程找不到自己的注册项。判据按**设备组键**比对（同一台设备换地址也算它）。
     */
    fun hasActiveTransfer(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val k = runCatching { prefs.convKeyOf(ip) }.getOrNull().orEmpty()
        fun same(v: String): Boolean =
            v.isNotEmpty() && runCatching { prefs.convKeyOf(v) }.getOrNull() == k
        synchronized(rxMap) { if (rxMap.values.any { same(it.peerIp) }) return true }
        val act = inFlight.toList()
        if (act.isEmpty()) return false
        // 发送侧只有 mid 集合（不带 IP）→ 用待发队列里的归属反查这台设备
        return store?.loadPending()?.any { it.mid in act && same(it.peerIp) } == true
    }

    /** 本机是否**有任何**文件传输在途（任台设备在收 / 在发）——保活「传输中不降级」判定用 */
    fun hasAnyActiveTransfer(): Boolean =
        synchronized(rxMap) { rxMap.isNotEmpty() } || inFlight.isNotEmpty()


    // ==================== 二进制工具 ====================


    // ==================== 工具 ====================

    /** 最近一次已入列的对端剪贴板哈希——同一份内容反复拉取时不重复生成气泡 */
    @Volatile
    internal var lastClipEmitSha1 = ""

    internal fun isDuplicateClip(text: String): Boolean {
        val sha = Clip.sha1(text)
        if (sha == lastClipEmitSha1) return true
        // 跨重启有效的判断：这条内容本机已经推出去过 / 已经拉回来过，界面上本来就有气泡
        if (listener?.shouldSkipPeerClip(sha) == true) {
            lastClipEmitSha1 = sha
            return true
        }
        lastClipEmitSha1 = sha
        return false
    }

    internal fun emit(m: MsgItem) {
        listener?.onMessageAdded(m)
    }

    internal fun okUpdate(m: MsgItem) {
        listener?.onMessageUpdated(m)
    }

    internal fun markStatus(mid: String, st: Status) {
        if (mid.isEmpty()) return
        listener?.onMessageUpdated(MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.TEXT, status = st))
    }

    internal fun waitFor(ms: Long) {
        synchronized(reconnectLock) {
            runCatching { reconnectLock.wait(ms) }
        }
    }

    internal fun wakeUp() {
        synchronized(reconnectLock) { runCatching { reconnectLock.notifyAll() } }
    }

    /**
     * 统一消息 id（2026-09-16 定版）：两端同构的 32 位十六进制全局唯一串。
     * 接收查重、待发队列、历史合并、hsync 补齐**全部以它为唯一判据**。
     */
    internal fun newMid(): String = UUID.randomUUID().toString().replace("-", "")

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    internal fun log(s: String) {
        Log.i(TAG, s)
        listener?.onLog(s)
    }

    companion object {
        internal const val TAG = "PP-Link"

        /** 半成品过期时限：3 天没人续传的孤儿半成品（取消帧丢失/对端重传换 mid）启动时回收 */
        const val PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000

        /** 判「假连接（半开）」的收包空闲阈值：超过一轮心跳周期（5s）仍无收包才算 */
        internal const val STALE_RX_MS = 20_000L

        /**
         * 接收/发送停滞判定阈值：30s 无任何真实进度（对端半死但 TCP 心跳仍正常）。
         * 心跳仍正常是因为对端进程活着、只是发送线程卡死/被杀 —— 心跳帧保不了业务帧。
         */
        internal const val STALL_TIMEOUT_MS = 30_000L

        /** 停滞看门狗扫描周期（= 心跳周期）：心跳线程每 5s 顺带扫一次，不新开线程 */
        internal const val STALL_SCAN_MS = 5_000L

        /** 未完成目录闲置回收时限：10 分钟无活动即回收（防「没走到 dir_end 的目录永留」） */
        internal const val DIR_STALE_MS = 10L * 60 * 1000

        /** hsync：双方各扫描最近多少条历史参与对账 */
        internal const val HSYNC_SCAN = 300

        /** hsync：单次 pull 最多请求多少条（超出的等下次连接再补） */
        internal const val HSYNC_PULL_MAX = 200

        /** hsync：文件自动补齐的大小上限；更大的文件不补（避免误触发 GB 级重传） */
        internal const val HSYNC_FILE_MAX = 64L * 1024 * 1024

        /** 等对端数据流量确认（data_ans）的最长时间：对端要弹框等用户点，给足时间 */
        internal const val DATA_ANS_WAIT_MS = 90_000L

        /** 「清空会话」逐条删除时，单帧 `msg_del` 最多带多少个 mid（多了会撞帧长上限） */
        internal const val MSG_DEL_CHUNK = 200

        // ---- 2.12 / D16：文件排他投票表的三个票号（与 PC 端 Link.VOTE_* 同号同义） ----
        /** 票 ①：通话中（**仅 TALKING**，接通后；由 CallManager 的状态迁移写） */
        const val VOTE_IN_CALL = 0
        /** 票 ②：原本"没网络"（发送入口发现无会话时写，握手完成清） */
        const val VOTE_NO_NETWORK = 1
        /** 票 ③：原本"被拒绝"（数据流量确认被拒时写，清零点 = 用户在气泡上手动重试） */
        const val VOTE_CONSENT_DENIED = 2
    }
}
