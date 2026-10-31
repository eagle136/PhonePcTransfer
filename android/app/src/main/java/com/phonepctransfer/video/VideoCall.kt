package com.phonepctransfer.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.util.Log
import com.phonepctransfer.net.AvTransport
import com.phonepctransfer.net.Link
import com.phonepctransfer.net.Protocol
import com.phonepctransfer.util.CallClock
import com.phonepctransfer.util.SegMeter

/**
 * 视频通话会话编排（计划 §5）。
 *
 * 发送半：`Camera2 → GL 转正（GlRotatePipe）→ encoderSurface → MediaCodec → io{ AvTransport.sendVideo }`
 * （2026-09-22 二期起**发出的数据方向就是正的**，`video_cfg.rot` 恒发 0）
 * 接收半：`onVideoFrame(只入队) → MediaCodec → Surface(TextureView)`
 * 自适应：`QualityController`（降档激进 / 升档四重闸门），接收侧每 2s 回 `video_fb`。
 *
 * ⚠ 线程红线（本项目已踩过两次，务必对照 §8）：
 *  - **网络读线程**只做入队（[onRemoteFrame]），解码器创建/相机打开全在各自线程；
 *  - 发视频帧一律走媒体模块 [AvTransport.sendVideo]（任务单 2.1：底下是五档里的哪一档由模块内部
 *    决定，本类不知道也不据此分支；2.1 落 INBAND ⇒ 底下仍是 `Link.sendVideoTo`，帧格式一字未变，
 *    内部不碰 UI 线程）；
 *  - 相机/编码器初始化在 `startSending` 的调用线程（CallManager 已放到 pp-call-audio 之外的
 *    独立线程路径）—— 不在读线程、不在主线程做重活。
 */
class VideoCall(
    private val ctx: Context,
    private val link: Link,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** 状态文案（档位 / 帧率 / 丢帧）供 UI 显示 */
    private val onStats: (String) -> Unit = {},
    /** 档位变化（上层据此联动音频码率） */
    private val onLevelChanged: (VideoLevel) -> Unit = {}
) {
    companion object {
        private const val TAG = "PP-VideoCall"
    }

    // ==================== 发送半 ====================

    /** 编码器句柄。切档线程写、网络读线程（[onRemoteKeyRequest]）读 ⇒ 必须 @Volatile */
    @Volatile
    private var encoder: VideoEncoder? = null
    private var quality: QualityController? = null

    @Volatile
    private var sendPeerIp = ""

    /**
     * 媒体传输模块（任务单 2.1：发送只走它，五档里的哪一档由模块内部决定）。
     * 由 voice/CallManager 在建媒体会话时注入；未注入时**不发**（返回 -1 计入质量统计，
     * 不会绕过模块自己找 socket）。
     */
    @Volatile
    var transport: AvTransport? = null

    @Volatile
    private var running = false

    @Volatile
    private var sendEnabled = true

    private var seq = 0
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null

    /** GL 转正中间层（相机 target 与编码器解耦；预览不走这层） */
    private var glPipe: GlRotatePipe? = null

    /**
     * 本地预览的 `SurfaceTexture`。
     *
     * 分辨率变化的切档后必须重设 `setDefaultBufferSize`（缓冲区尺寸 = 渲染侧算等比的依据，
     * 见 [attachLocalPreview]），所以要留着引用。只换码率的切档（L2↔L3 同为 1080P）不涉及。
     */
    private var previewSt: SurfaceTexture? = null

    /**
     * 切档执行线程（**串行**）。
     *
     * ⚠ 切档必须在**独立线程**做，不能就地做：`QualityController.onChange` 是在
     * `VideoEncoder` 的输出线程（`pp-video-enc`）回调里触发的，在那里 `stop()` 编码器
     * 等于「自己 join 自己」→ `join(300)` 超时后 release 掉还在跑的 codec（崩/花）。
     */
    private var switchExec: java.util.concurrent.ExecutorService? = null

    private fun ensureSwitchExec(): java.util.concurrent.ExecutorService {
        var e = switchExec
        if (e == null) {
            e = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "pp-video-switch").apply { isDaemon = true }
            }
            switchExec = e
        }
        return e
    }

    @Volatile
    var activeLevel: VideoLevel = VideoLevel.DEFAULT

    /** 当前**编码输出尺寸**（= GL viewport 尺寸）。竖持时是档位宽高交换后的值。 */
    @Volatile
    private var encW = 0

    @Volatile
    private var encH = 0
        private set

    @Volatile
    var encoderMime: String = VideoCodecs.MIME_HEVC
        private set

    /** 本端是否真的在发视频（相机+编码器都起来了） */
    val sending: Boolean get() = running

    /**
     * 本端编码尺寸（本地小窗做等比留边用）。
     *
     * ⚠ 预览 SurfaceTexture 的缓冲区尺寸是 [pinPreviewSize] 用 `setDefaultBufferSize`
     * 钉的值，**且会吸附到本机相机真正支持的流尺寸**（2026-09-16：854x480 两台机都
     * 不支持 → 480P 黑屏的教训）—— 所以渲染侧等比计算的依据是下面这两个 getter
     * （吸附后的实际值），不是档位的编码尺寸。
     */
    val localVideoW: Int get() = if (previewW > 0) previewW else VideoLevel.encodeWidth(activeLevel)

    val localVideoH: Int get() = if (previewH > 0) previewH else VideoLevel.encodeHeight(activeLevel)

    /** 预览缓冲实际尺寸（相机吸附后）。0 = 预览还没钉过 → 回落编码尺寸 */
    @Volatile
    private var previewW = 0

    @Volatile
    private var previewH = 0

    /**
     * 钉预览缓冲尺寸：先经 [VideoCapture.pickPreviewSize] 吸附到本机相机支持的
     * 尺寸，再 `setDefaultBufferSize`。渲染侧（ChatScreen.applyLocalTransform）
     * 通过 [localVideoW]/[localVideoH] 拿到的就是吸附后的实际值。
     */
    private fun pinPreviewSize(w: Int, h: Int) {
        val s = capture.pickPreviewSize(w, h)
        previewW = s[0]
        previewH = s[1]
        // 诊断（只读）：渲染侧等比全靠这个值，两台机的诉求可能不同 → 必须留痕
        onLog(
            "预览缓冲：请求 ${w}x$h → 实际 ${previewW}x$previewH" +
                "（档位=${activeLevel.label} 前置=${capture.isFront} 握姿角=${capture.rotationDegrees()}°）"
        )
        previewSt?.let { st ->
            runCatching { st.setDefaultBufferSize(previewW, previewH) }
                .onFailure { onLog("预览缓冲区尺寸设置失败：${it.message}（本地小窗可能被拉伸）") }
        }
    }

    // ==================== 接收半 ====================

    val decoder = VideoDecoder { msg -> onError(msg) }

    @Volatile
    private var recvPeerIp = ""

    @Volatile
    private var remoteCodec: String = ""

    @Volatile
    private var remoteW = 0

    @Volatile
    private var remoteH = 0

    /** 远端上次告知的码率（仅用于「变了才打日志」——解码器不吃这个参数） */
    @Volatile
    private var remoteBr = 0

    /** 远端上次告知的旋转角（接收端据此转正画面） */
    @Volatile
    var remoteRotation: Int = 0
        private set

    /**
     * 对端当前用的是**前置**摄像头吗（来自 `video_cfg.cam`，2026-09-19 加）。
     *
     * 接收端据此决定「对方画面」的默认显示角：对端前置 → 默认多转 180°
     * （用户口径「相当于点了两次旋转按钮」）。老对端不带 `cam` → 恒为 false（后置），
     * 行为与改造前完全一致。切摄像头时对端会重发 cfg，这里随手更新。
     */
    @Volatile
    var remoteCamFront: Boolean = false
        private set

    /**
     * 远端画面缓冲区尺寸（0 = 尚未收到 `video_cfg`）。
     *
     * UI 渲染侧做**等比留边**要用它（§16.6）：`TextureView` 默认把 `SurfaceTexture`
     * 缓冲区**各向异性拉伸铺满**整个 View，不知道真实宽高比就一定会被压扁。
     * 解码器的输出缓冲区尺寸就来自这份编码参数，所以这里是权威值。
     */
    val remoteVideoW: Int get() = remoteW

    val remoteVideoH: Int get() = remoteH

    private var lastVideoSeq = -1
    private var fbThread: Thread? = null

    /**
     * 发送端 1s 心跳：周期重发 `video_cfg`（含 rot + cam）。
     *
     * 为什么要有：切摄像头时那份「即时 cfg」是单发的 —— 万一对端没收到/晚到，
     * 它就会一直按旧朝向选旋转配置（用户症状：调正后又倒立）。1s 重发保证
     * 对端的 rot/cam **最终一致**；收到的值没变时对端什么都不做，零副作用。
     */
    private var cfgThread: Thread? = null

    @Volatile
    private var fbFrames = 0

    /**
     * 已收到的视频字节数（只由网络读线程累加，与 [fbFrames] 同一条路）。
     * 供反馈环算**实收码率**（video_fb.kbps，任务单 1.4）：与 PC 端同口径
     * = 2s 窗口内收到的视频字节 × 8 / 2000（kbps），不含音频/文件字节。
     */
    @Volatile
    private var fbBytes = 0L

    @Volatile
    private var fbLastArrival = 0L

    /** 对端是否在发视频（收到过 video_cfg 且仍在收帧） */
    @Volatile
    var receiving: Boolean = false
        private set

    // ==================== 发送：启动 / 停止 ====================

    /**
     * 启动视频发送（Android 端专用；PC 端 cam=false 不会有这个路径）。
     *
     * @return false = 无法发视频（无硬编 / 对端不支持 / 相机打开失败）→ 调用方应降级为纯语音
     */
    fun startSending(peerIp: String, level: VideoLevel): Boolean {
        stopSending()
        recvPeerIp = peerIp
        sendPeerIp = peerIp
        val peerVcodec = link.peerCapsOf(peerIp)?.vcodec ?: emptyList()
        val mime = VideoCodecs.pickEncoderMime(peerVcodec)
        if (mime == null) {
            onError("无可用硬件编码器（或对端不支持 HEVC/H.264），已禁用视频")
            return false
        }
        encoderMime = mime

        // ---- 编码输出尺寸（竖持时与档位宽高交换，见 outDim 注释） ----
        val (ow, oh) = outDim(level)
        encW = ow
        encH = oh

        val enc = VideoEncoder(
            onFrame = { data, len, isConfig, isKey -> handleEncoded(data, len, isConfig, isKey) },
            onError = { onError(it) }
        )
        val surface = enc.start(mime, ow, oh, level.fps, level.videoBitrate)
        if (surface == null) {
            onError("编码器启动失败，已禁用视频")
            return false
        }
        encoder = enc
        encoderSurface = surface
        activeLevel = level

        val qc = QualityController(maxLevel = VideoLevel.of(maxLevelIdx)) { newLevel ->
            applyLevel(newLevel)
        }
        qc.reset(level)
        quality = qc

        seq = 0
        running = true
        sendEnabled = true

        // GL 转正中间层（二期）：相机帧经 ST 矩阵处理 + 等比 fit 后喂编码器。
        //
        // ⚠ 2026-09-22 实测定案：相机 ST 的 UV 矩阵是**转置矩阵**（u'=1-v, v'=1-u）
        //   —— 系统已把 sensor 横向缓冲转成**竖立**方向。所以：
        //   ① 编码输出尺寸必须与内容方向一致（竖持 = 交换，见 outDim）；
        //   ② 摆正角在 GL 内按 `A − base` 算（A 由 ST 矩阵的**符号**区分前后置）——
        //      竖持恒得 0（实测：后置本来就正；原公式给前置 180 → 对端看到**倒立**）；
        //   ③ GL 内再套一层等比 fit 兜底：就算分辨/朝向估错，也只是留黑边，永不变形。
        val gl = GlRotatePipe(onLog, onError)
        gl.frontCam = capture.isFront
        if (!gl.start(surface, level.width, level.height, ow, oh, capture.rotationDegrees())) {
            onError("GL 旋转层启动失败，已禁用视频")
            stopSending()
            return false
        }
        glPipe = gl

        // 先把编码参数告知对端（接收端据此建解码器），再开采集；数据已转正 → rot 恒 0
        link.sendVideoCfg(
            peerIp, VideoCodecs.name(mime), ow, oh,
            level.fps, level.videoBitrate / 1000, 0, capture.isFront
        )

        val targets = mutableListOf(gl.cameraSurface!!)
        previewSurface?.let { targets.add(it) }
        // 会话就绪 / 握姿变化 → 采集角可能变
        //  · 只是角度变（不影响尺寸）→ 同步 GL 转正角 + 重发 cfg（零中断）
        //  · 竖↔横翻转 → 编码输出尺寸也随之换（内容方向变了）→ 必须重建编码器
        capture.onSessionReady = { onCaptureAngleChanged() }
        capture.onRotationChanged = { onCaptureAngleChanged() }
        if (!capture.start(targets, level.fps)) {
            onError("摄像头打开失败，已禁用视频")
            stopSending()
            return false
        }
        startCfgHeartbeat()
        onLog("视频发送已启动：${VideoCodecs.name(mime)} ${ow}x$oh@${level.fps} ${level.videoKbps}k（${capture.cameraLabel}）")
        return true
    }

    /**
     * 档位上限（两端协商与用户设置取 min），由 CallManager 在启动前设定。
     * ⚠ 不回退值写 [VideoLevel.MAX].idx（= "注入前不设限"），不写 DEFAULT：这里钉 2 的话，
     *   任何"忘了注入就直接 startSending"的新调用方都会把超清静默夹成高清（同族坑，见 Prefs.videoMaxLevel）。
     */
    var maxLevelIdx: Int = VideoLevel.MAX.idx

    /**
     * 当前持握下的**编码输出尺寸**（GL viewport 尺寸）。
     *
     * ⚠ 2026-09-22 实测定案（这是“预览窗/远端画面被压扁”的真因）：
     * 相机 `SurfaceTexture.getTransformMatrix()` 给的是**转置矩阵**
     * （实测值 `[0,-1,0,0, -1,0,0,0, 0,0,1,0, 1,1,0,1]` 即 `u'=1-v, v'=1-u`），
     * 也就是**系统已把 sensor 横向缓冲转成竖立方向**。若仍按档位的 1920x1080 横框编码，
     * 竖立内容被塞进 16:9 横框 → **发出的流本身就压扁 3.16 倍**（接收端“看着正常”
     * 只是它的 View 恰好是竖长条、各向异性铺满又把这份压扁反向抵消了——纯属巧合）。
     *
     * 判据：`rotationDegrees()` 的 90/270（后置竖持 90 / 前置竖持 270）= 竖持 → 交换。
     */
    private fun outDim(level: VideoLevel): Pair<Int, Int> =
        if (capture.rotationDegrees() % 180 != 0) level.height to level.width
        else level.width to level.height

    /**
     * 采集角变化（会话就绪 / 握姿翻转 / 切摄），跑在 `pp-video-cam` 线程。
     *
     * 绝大多数情况只是角度变 → 推给 GL + 重发 cfg，**零中断**；
     * 只有竖↔横翻转会改变编码输出尺寸（内容方向变了）→ 走重建。
     */
    private fun onCaptureAngleChanged() {
        if (!running) return
        glPipe?.frontCam = capture.isFront
        glPipe?.baseRotationDeg = capture.rotationDegrees()
        val (w, h) = outDim(activeLevel)
        if (w != encW || h != encH) onOrientationFlipped() else sendCfgNow()
    }

    /**
     * 握姿竖↔横翻转 → 编码输出尺寸必须跟着换（内容方向变了）。
     *
     * 复用切档那条重建路径（重建编码器 + 换 GL 输出 + 重建相机会话，中断 ~200-400ms），
     * 在 `pp-video-switch` 线程串行执行；进线程后**重新判一次**尺寸，避免两个回调
     * （会话就绪 + 旋转）并发触发时重复重建。
     */
    private fun onOrientationFlipped() {
        runCatching {
            ensureSwitchExec().execute {
                runCatching {
                    glPipe?.frontCam = capture.isFront
                    glPipe?.baseRotationDeg = capture.rotationDegrees()
                    if (!running) return@runCatching
                    val (w, h) = outDim(activeLevel)
                    if (w == encW && h == encH) {
                        sendCfgNow()
                        return@runCatching
                    }
                    sendCfgNow()
                    pinPreviewSize(activeLevel.width, activeLevel.height)
                    restartEncoder(activeLevel)
                    if (!running) return@runCatching
                    sendCfgNow()
                    onLog("握姿翻转 → 输出尺寸 ${encW}x$encH（编码器已重建）")
                }.onFailure { onError("握姿切换重建失败：${it.message}") }
            }
        }.onFailure { onError("握姿切换任务提交失败：${it.message}") }
    }
    /**
     * 相机会话就绪后补发一次 `video_cfg`。
     *
     * 原因：`startSending` 里发第一份 cfg 时相机还没开，`capture.rotationDegrees()`
     * 可能拿不到真实值（取默认 0）。会话就绪后旋转角才确定，需要**补发一次**让对端
     * 把画面转正；此时分辨率/码率都没变，接收端只会更新 rot，**不重建解码器**。
     */
    private fun sendCfgNow() {
        if (!running) return
        // 发送的宽高是**编码输出尺寸**（[encW]/[encH]，竖持时已交换），不是档位尺寸。
        //
        // ⚠ 2026-09-22 实测定案：GL 额外旋转**不再恒 0**。相机 ST 的 UV 矩阵是**转置矩阵**
        //   （`u'=1-v, v'=1-u`），系统已替我们把 sensor 内容转了 90° → 所以 GL 里实际 UV 旋转
        //   = `rotationDegrees() - 90`（竖持恰好 0，横持 270，前置竖持 180）。
        //   `cfg.rot` 仍恒发 0：发送端已把数据摆正，接收端零旋转。
        val w = if (encW > 0) encW else outDim(activeLevel).first
        val h = if (encH > 0) encH else outDim(activeLevel).second
        link.sendVideoCfg(
            sendPeerIp, VideoCodecs.name(encoderMime),
            w, h,
            activeLevel.fps, activeLevel.videoBitrate / 1000,
            0, capture.isFront
        )
    }

    /**
     * 1s cfg 心跳线程（[startSending] 尾部启动，[stopSending] 摘除）。
     *
     * `sendCfgNow()` 自带 `running` 闸门且走 `io{}`，从这里调线程安全；
     * 值没变时接收端不重建解码器、不重选槽位，只是确认。
     */
    private fun startCfgHeartbeat() {
        if (cfgThread != null) return
        val t = Thread({
            val self = Thread.currentThread()
            while (cfgThread === self && running) {
                try {
                    Thread.sleep(1_000)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
                sendCfgNow()
            }
        }, "pp-video-cfg").apply { isDaemon = true }
        cfgThread = t
        t.start()
    }

    fun stopSending() {
        running = false
        cfgThread = null
        quality = null
        // 等切档线程把手里那一刀切完（否则可能出现「它刚建好编码器、这边又漏停」）
        val e = switchExec
        switchExec = null
        if (e != null) {
            e.shutdown()
            runCatching { e.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        capture.stop()
        // GL 层先停（它还持有编码器 Surface 的 EGL 包装），再停编码器
        runCatching { glPipe?.stop() }
        glPipe = null
        encoder?.stop()
        encoder = null
        encoderSurface = null
        onLog("视频发送已停止")
    }

    private fun handleEncoded(data: ByteArray, len: Int, isConfig: Boolean, isKeyframe: Boolean) {
        val t0 = System.nanoTime()
        if (!running || !sendEnabled) return
        var flag = 0
        if (isConfig) flag = flag or Protocol.VIDEO_FLAG_CONFIG
        if (isKeyframe) flag = flag or Protocol.VIDEO_FLAG_KEYFRAME
        // ts 与音频帧共用 CallClock 零点（进入 TALKING 时置零），接收端才能同轴对 A/V
        val ts = CallClock.elapsedMs()
        // 发送目标由媒体模块决定（2.1 落 INBAND ⇒ 仍是本通话对端；2.7 learn-by-receiving 收进模块）
        val writeMs = transport?.sendVideo(seq++, ts, flag, data, len) ?: -1L
        // vTx：编码输出回调 → 已交给传输（含 socket 写；失败也计入）
        SegMeter.record(SegMeter.V_TX, t0, System.nanoTime())
        // 100ms 发送截止期统计（超阈值只记 drop，**不重发** —— TCP 上重传是自伤式拥塞）
        quality?.onFrameSent(writeMs)
        // 问题2 的自愈入口：这一帧**因为太大而整帧没上线**（面内单帧闸门丢）⇒ 交给状态机当拥塞证据，
        //   由它把每帧数据量降下来。判据用帧长而不是耗时，所以两种场景都覆盖到了：
        //   · 带内档：超 128KB 的帧在 Link.sendVideoTo 被丢、返回 -1 ⇒ 这里进得来；
        //   · UDP 档：同一帧已切成 ≤1188B/片上线、返回 0 ⇒ 进不来（切片本来就解决了它，不该再降档）。
        //   挂断 / 无会话那条 -1 出口长度通常不过闸门，不会把"通话已结束"误报成拥塞。
        if (writeMs < 0L && len > Protocol.MAX_VIDEO_FRAME) {
            quality?.onLocalDrop()
        }
    }

    /**
     * 切档入口（由 `QualityController` 在**编码器输出线程**调用）。
     *
     * 只做「提交任务」，实际动作在 `pp-video-switch` 串行线程执行（原因见 [switchExec]）。
     */
    private fun applyLevel(level: VideoLevel) {
        if (!running) return
        runCatching {
            ensureSwitchExec().execute {
                runCatching { doApplyLevel(level) }
                    .onFailure { onError("切档失败（${level.titleLabel}）：${it.message}") }
            }
        }.onFailure { onError("切档任务提交失败：${it.message}") }
    }

    /**
     * 真正的切档执行（`pp-video-switch` 线程）。
     *
     *  - **只换码率** → `setParameters()`，零中断；
     *  - **分辨率也变了** → 重建编码器 + 换相机 target（画面中断 ~200–400ms）。
     *
     * 顺序：先 `activeLevel` + 发 `video_cfg`（让对端尽早按新尺寸重建解码器），
     * 再重建本端编码器 —— 对端重建与本端重建并行，中断窗口最短。
     */
    private fun doApplyLevel(level: VideoLevel) {
        if (!running) return
        val prev = activeLevel
        if (level.idx == prev.idx) return
        // 尺寸比较**必须走 outDim**（竖持时编码尺寸是交换过的），否则切档判断会错
        val sizeChanged = outDim(level) != outDim(prev)

        activeLevel = level
        // 先告知对端（w/h 变 → 对端 onRemoteCfg 会重建解码器；只变码率时对端什么都不做）
        sendCfgNow()
        if (sizeChanged) {
            // 先钉新预览缓冲（吸附相机支持尺寸），再重建编码器/换 target ——
            // updateTargets 重建会话时用的就是新缓冲尺寸，一次建对
            pinPreviewSize(level.width, level.height)
            restartEncoder(level)
            if (!running) return
        }
        encoder?.setBitrate(level.videoBitrate)
        onLevelChanged(level)
        sendCfgNow()
        onStats("${level.label}/${level.videoKbps}k")
        onLog(
            "切档 → ${level.titleLabel}/${level.videoKbps}k" +
                if (sizeChanged) "（分辨率 ${prev.label}→${level.label}，编码器已重建）" else "（仅码率）"
        )
    }

    /**
     * 重建编码器（分辨率切换用）。
     *
     * 复用同一个 `VideoCapture`：只把编码器输入 Surface 换成新的，再 `updateTargets`
     * 让相机指过去（`captureSession` 会被重建，**相机不关闭、不重新授权**）。
     */
    private fun restartEncoder(level: VideoLevel) {
        val old = encoder
        encoder = null
        runCatching { old?.stop() }
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        if (!running) return

        val (ow, oh) = outDim(level)
        val enc = VideoEncoder(
            onFrame = { data, len, isConfig, isKey -> handleEncoded(data, len, isConfig, isKey) },
            onError = { onError(it) }
        )
        val surface = enc.start(encoderMime, ow, oh, level.fps, level.videoBitrate)
        if (surface == null) {
            onError("切档重建编码器失败（${level.titleLabel}），继续使用上一档")
            return
        }
        encoder = enc
        encoderSurface = surface
        encW = ow
        encH = oh
        // GL 输出换到新编码器 Surface（输出尺寸 = 新的编码尺寸，相机输入尺寸不变）；
        // 相机 target（GL 输入 ST）不变，但分辨率变了 → 重建会话
        // （预览 ST 新尺寸已在 pinPreviewSize 钉过）
        val gl = glPipe ?: throw IllegalStateException("GL 层不存在")
        if (!gl.setOutput(surface, level.width, level.height, ow, oh)) {
            throw IllegalStateException("GL 输出切换失败")
        }
        val targets = mutableListOf(gl.cameraSurface!!)
        previewSurface?.let { targets.add(it) }
        capture.updateTargets(targets)
    }

    /**
     * 接收侧健康反馈到达（发送侧用于升降档）。
     *
     * @param lat      对端排队时延 ms（真实值，[QualityController] 的 `lat>400ms` 判据从此才有数）
     * @param dropping 对端此刻正处于丢帧期（只展示；判据改动属任务单 3，本单不动行为）
     * @param kbps     对端 2s 窗口内的**实收码率**（任务单 1.4：不再是恒 0 的死字段）
     */
    fun onRemoteFb(fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int) {
        quality?.onFeedback(drop, lat, kbps)
        if (running) {
            onStats("${activeLevel.label}/${activeLevel.videoKbps}k · 对端 ${fps}fps 丢 ${drop}% 延迟 ${lat}ms" +
                    " 实收 ${kbps}k" + if (dropping) " 丢帧中" else "")
        }
    }

    /** 对端请求临时停/开视频（如它切了后台） */
    fun onRemoteReq(on: Boolean) {
        sendEnabled = on
        onLog(if (on) "对端请求恢复视频" else "对端请求暂停视频（已停发）")
    }

    /**
     * 对端请求**立刻一个 IDR**（PC 的解码进程刚换代：换尺寸或首次起进程）。
     *
     * 为什么必须有：换代后的收端只能从一个 IDR 起解，而编码器 GOP=1s ⇒ 画面要白等到下一个关键帧，
     * 用户看到的就是「接通后卡几秒」。GOP 的 1s 是档位表里的既有口径，不改它，改的是「什么时候插一个 IDR」。
     *
     * ⚠ 网络读线程调用。resendConfig 只置一个 volatile 标志、requestKeyFrame 只有一次 setParameters，
     *   两者都不阻塞、不碰 writeLock，真正的发帧由编码器线程做（见 VideoEncoder.resendConfig）；
     *   编码器已停（encoder=null）或本端正停发（sendEnabled=false）时直接 no-op。
     *   API<31 或不支持 request-sync 的编码器会静默不吃这个参数，最坏退回等下一个 GOP IDR（与改前一致）。
     */
    fun onRemoteKeyRequest() {
        val e = encoder ?: return
        if (!running || !sendEnabled) return
        e.resendConfig()
        e.requestKeyFrame()
        onLog("对端请求关键帧 ⇒ 已重发参数集 + 强制 IDR")
    }

    // ==================== 接收 ====================

    /** 编码参数通知：codec/分辨率有变才重建解码器（一期只改码率/rot → 不会重建） */
    fun onRemoteCfg(
        peerIp: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int, front: Boolean
    ) {
        if (recvPeerIp.isNotEmpty() && peerIp != recvPeerIp) return
        recvPeerIp = peerIp
        val rotChanged = rot != remoteRotation
        remoteRotation = rot
        val camChanged = front != remoteCamFront
        remoteCamFront = front
        val changed = codec != remoteCodec || w != remoteW || h != remoteH
        if (changed) {
            onLog("远端编码参数：$codec ${w}x$h@$fps ${br}k rot=${rot}° 摄像头=${if (front) "前置" else "后置"}")
            // ⚠ 缓存只在**重建成功**后写入：失败（Surface 未就绪 / 并发冲突）时缓存保持旧值，
            //   下一条 1s 心跳 cfg 会再试一次 → **自愈，不必重拨**。
            //   （2026-09-22 实测：a16 曾因解码器创建失败黑屏整场，重拨才恢复。）
            if (decoder.prepare(VideoCodecs.mime(codec), w, h)) {
                remoteCodec = codec
                remoteW = w
                remoteH = h
            }
        } else if (rotChanged || camChanged) {
            // 对端横竖持切换 / 手工纠偏 / 换前后摄 → 只改旋转，**不重建解码器、不中断画面**
            onLog("对端画面变化 → 转正 ${rot}°（${if (front) "前置" else "后置"}摄像头）")
        } else if (br != remoteBr) {
            // 码率变了：解码器侧无需任何动作（HEVC 解码不吃码率参数），只记一行 ——
            // 1s 心跳会让这条判断每秒命中一次，**值没变就不打日志**（避免刷屏）
            remoteBr = br
            Log.i(TAG, "远端码率 → ${br}k（解码器无需重建）")
        }
        onStats("$codec ${w}x$h rot=${rot}° ${if (front) "前置" else "后置"}")
        if (fbThread == null) startFeedbackLoop()
    }

    /**
     * 网络读线程回调：**只入队**。
     *
     * `seq` 单调性去重是廉价保险（防御未来传输方式变更导致的重复帧）。
     */
    fun onRemoteFrame(peerIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {
        val t0 = System.nanoTime()
        if (recvPeerIp.isNotEmpty() && peerIp != recvPeerIp) return
        if (!decoder.ready) return
        val isConfig = (flag and Protocol.VIDEO_FLAG_CONFIG) != 0
        if (!isConfig) {
            if (seq <= lastVideoSeq) return
            lastVideoSeq = seq
        }
        receiving = true
        fbFrames++
        fbBytes += len
        fbLastArrival = System.currentTimeMillis()
        decoder.enqueue(data, len, isConfig, ts)
        // vIn：读线程回调入口 → 解码队列入队完成
        SegMeter.record(SegMeter.V_IN, t0, System.nanoTime())
    }

    /** UI 挂上远端 Surface 时调用 */
    fun attachRemoteSurface(surface: Surface) {
        decoder.attachSurface(surface)
        // 只在**本场已收到过 cfg**（remoteW/H 非 0）时才建；否则交给随后的 onRemoteCfg ——
        // 用上一场残留的尺寸建解码器会与实际流不符 → 黑屏（2026-09-22 a16 实测）。
        if (remoteCodec.isNotEmpty() && remoteW > 0 && remoteH > 0) {
            decoder.prepareIfNeeded(VideoCodecs.mime(remoteCodec), remoteW, remoteH)
        }
        if (fbThread == null) startFeedbackLoop()
    }

    /**
     * UI 挂上本地预览 TextureView 时调用（迟到的预览会让相机重建一次会话，编码不中断）。
     *
     * **缓冲区尺寸在这里钉死**（2026-09-15 加，配合等比留边）：
     *  - Camera2 对 `SurfaceTexture` 目标取的是它的 `getDefaultBufferSize()`，而 `TextureView`
     *    默认填的是**控件尺寸**（本地小窗只有 104x150dp ≈ 286x412px，是任何相机都不可能输出的
     *    尺寸，能否建会话全看 HAL 心情）；
     *  - 渲染侧 `videoFitMatrix` 又必须按**真实缓冲区宽高比**算等比缩放。
     *
     * 2026-09-16 起经 [pinPreviewSize] **吸附到本机相机支持的流尺寸**再钉 —— 档位编码
     * 尺寸不一定在相机的 StreamConfigurationMap 里（854x480 两台实测机都没有 → 黑屏）。
     * 必须在把 Surface 交给 Camera2 **之前**调用（`updateTargets` 会重建 captureSession）。
     */
    fun attachLocalPreview(st: SurfaceTexture) {
        // 防重复 attach（TextureView 重建会二次回调）：先释放旧包装再建新 Surface
        runCatching { previewSurface?.release() }
        previewSt = st
        pinPreviewSize(VideoLevel.encodeWidth(activeLevel), VideoLevel.encodeHeight(activeLevel))
        val s = Surface(st)
        previewSurface = s
        if (glPipe != null) {
            val targets = mutableListOf(glPipe!!.cameraSurface!!)
            targets.add(s)
            capture.updateTargets(targets)
        }
    }

    fun detachLocalPreview() {
        previewSt = null
        previewSurface?.release()
        previewSurface = null
        if (glPipe != null) {
            capture.updateTargets(listOf(glPipe!!.cameraSurface!!))
        }
    }

    // ==================== 摄像头切换（R8） ====================

    /**
     * 切换前后摄：复用同一 GL 输入 ST，码流不中断；切完重发 cfg（rot=0 + 新朝向）。
     *
     * `capture.switchCamera()` 内部同步读新摄像头 characteristics → `rotationDegrees()`
     * 已是新真值 → `sendCfgNow()` 会把 GL 旋转角同步过去，数据保持正立。
     */
    fun switchCamera(): Boolean {
        if (!running) return false
        val ok = capture.switchCamera()
        if (ok) {
            // 新摄像头的采集角不同（前置/后置 sensor 常差 180）→ 推给 GL；
            // 若竖/横判定也跟着翻，onCaptureAngleChanged 会自动走重建
            onCaptureAngleChanged()
            onLog("已切换到${capture.cameraLabel}摄像头（采集角=${capture.rotationDegrees()}°，数据已转正）")
        } else {
            onLog("没有另一个摄像头可切换")
        }
        return ok
    }

    // ==================== 接收侧反馈（每 2s） ====================

    private fun startFeedbackLoop() {
        if (fbThread != null) return
        val t = Thread({
            var prevFrames = 0
            var prevDropped = 0
            // 基线取"本反馈环启动时"的累计字节（decoder 的帧计数每场会归零，字节计数是进程级累加）
            var prevBytes = fbBytes
            while (fbThread === Thread.currentThread()) {
                try {
                    Thread.sleep(2_000)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
                val peer = recvPeerIp
                if (peer.isEmpty()) continue
                val frames = decoder.decodedFrames
                val dropped = decoder.droppedFrames
                val bytes = fbBytes
                val dFrames = frames - prevFrames
                val dDropped = dropped - prevDropped
                val dBytes = bytes - prevBytes
                prevFrames = frames
                prevDropped = dropped
                prevBytes = bytes
                val total = dFrames + dDropped
                val dropPct = if (total <= 0) 0 else (dDropped * 100 / total)
                val fps = dFrames / 2
                val lat = decoder.lastQueueDelayMs
                // lat 是真实排队时延；「正在丢帧」由独立的 dropping 字段说（任务单 1.3，与 PC 同名）
                val dropping = decoder.dropping
                // 实收码率（任务单 1.4）：窗口 2s、只算视频字节、×8/2000 = kbps —— 与 PC 端同公式
                val kbps = (dBytes * 8 / 2000).toInt()
                link.sendVideoFb(peer, fps, dropPct, lat, dropping, kbps)
                if (dFrames > 0 || dDropped > 0) {
                    Log.i(
                        TAG, "video_fb → $peer: ${fps}fps drop=${dropPct}% lat=${lat}ms" +
                            " kbps=${kbps}" + if (dropping) " dropping" else ""
                    )
                }
            }
        }, "pp-video-fb").apply { isDaemon = true }
        fbThread = t
        t.start()
    }

    // ==================== 相机封装（UI 直接用） ====================

    val capture = VideoCapture(
        ctx,
        onLog = { onLog(it) },
        onError = { onError(it) }
    )

    fun stop() {
        stopSending()
        decoder.stop()
        recvPeerIp = ""
        sendPeerIp = ""
        receiving = false
        lastVideoSeq = -1
        // 挂断可能先于 TextureView 销毁：此时 detachLocalPreview 已 no-op，
        // 包装必须在这里释放（重复 release 安全，UI 侧再释放属 no-op）
        runCatching { previewSurface?.release() }
        previewSurface = null
        fbThread = null
        cfgThread = null
        // ⚠ 换场清零：远端编码参数**绝不跨通话沿用**。
        //   不清零的后果（2026-09-22 a16 实测）：新通话 UI 挂远端 Surface 时，
        //   attachRemoteSurface 会拿**上一场**的分辨率建解码器，与本场真实流不符 →
        //   对端画面黑屏（且 480P↔720P 换档时才必现，重拨偶尔蒙对）。
        remoteCodec = ""
        remoteW = 0
        remoteH = 0
        remoteBr = 0
        remoteRotation = 0
        remoteCamFront = false
    }

    // 2026-09-22 用户要求：顶部不再显示「转 90°」这类角度文案（发送端已把数据摆正，
    // 这个数字对用户没意义）。角度仍照常算、照常发 cfg，只是不往外露。
    fun summary(): String =
        if (sending) {
            "视频 ${activeLevel.label}/${activeLevel.videoKbps}k ${capture.cameraLabel}"
        } else if (receiving) {
            "接收 $remoteCodec ${remoteW}x${remoteH}"
        } else {
            "视频未激活"
        }
}
