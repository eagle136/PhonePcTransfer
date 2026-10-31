package com.phonepctransfer.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 封装：枚举 / 打开 / 关闭 / 切换摄像头 / 方向 / 镜像（计划 §5.2 / §5.4）。
 *
 * 一期（方案 B）链路：`captureSession ──► encoderInputSurface`（+ 可选的本地预览 Surface）。
 * 二期（2026-09-22）：encoderInputSurface 换成 [GlRotatePipe] 的 GL 输入 ST —— 相机帧先转正
 * 再进编码器，发出的数据方向就是正的；本地预览仍直喂预览 Surface（路径保持一期原样）。
 *
 * ⚠ 线程红线：openCamera / createCaptureSession 都是异步回调，全部跑在私有
 * `pp-video-cam` HandlerThread 上；**绝不在网络读线程里调用本类任何方法**。
 */
class VideoCapture(
    private val ctx: Context,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "PP-VideoCap"
    }

    private val camMgr: CameraManager? =
        runCatching { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }.getOrNull()

    /** 默认显示的旋转来源（应用上下文不能用 ctx.display，只能走 DisplayManager） */
    private val displayMgr: DisplayManager? =
        runCatching { ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }.getOrNull()

    /** 握姿监听：注册在 pp-video-cam 线程上，随采集生命周期开关 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val d = rotationDegrees()
            if (d == reportedRot) return
            reportedRot = d
            onLog("握姿变化：display=${displayRotationDegrees()}° → 对端需旋转 ${d}°（重发 cfg）")
            runCatching { onRotationChanged?.invoke(d) }
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var cameraId: String? = null

    /**
     * stop() 进行中：迟到的 openCamera 回调必须立即关掉刚打开的 CameraDevice，
     * 且不得再建 captureSession（否则 stop 后相机灯不灭 / 设备无人关闭锁死相机）。
     */
    @Volatile
    private var stopping = false

    /**
     * openCamera 在飞闸门：每次 [open] 新建一个，回调负责解决（关自己的设备 + countDown）。
     * 回调闭包只解决**自己那次 open 的** gate —— 切摄像头/快速重呼时，
     * 旧 open 的迟到回调不会污染新 open 的 gate。
     */
    private class OpenGate {
        val latch = CountDownLatch(1)

        /** 已被更新的 [open] 取消：本 open 的回调只准关设备，不准碰共享状态 */
        @Volatile
        var cancelled = false
    }

    /** 所有未解决的在飞 open（回调解决时自移除；[open] 开新相机前等这个列表清空） */
    private val inFlightOpens = CopyOnWriteArrayList<OpenGate>()

    @Volatile
    private var facing = CameraCharacteristics.LENS_FACING_BACK

    @Volatile
    private var sensorOrientation = 90

    /** 当前会话的 target 集合（编码器输入 Surface + 可选预览 Surface） */
    private var targets: List<Surface> = emptyList()

    private var fpsRange: Range<Int> = Range(30, 30)

    /**
     * 采集会话真正跑起来时的回调（**此刻 `rotationDegrees()` 才是真值**）：
     * 上层在这里把 `video_cfg`（含 rot）发给对端 —— openCamera 是异步的，
     * 提前发只能拿到默认 rot。
     */
    @Volatile
    var onSessionReady: (() -> Unit)? = null

    // ==================== 能力枚举 ====================

    fun hasCamera(): Boolean = cameraIds().isNotEmpty()

    private fun cameraIds(): List<String> = runCatching {
        camMgr?.cameraIdList?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun idOf(facingValue: Int): String? = cameraIds().firstOrNull { id ->
        runCatching {
            camMgr?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.LENS_FACING) == facingValue
        }.getOrDefault(false)
    }

    fun hasFront(): Boolean = idOf(CameraCharacteristics.LENS_FACING_FRONT) != null

    fun hasBack(): Boolean = idOf(CameraCharacteristics.LENS_FACING_BACK) != null

    /** 当前摄像头是否是前置（UI 据此决定本地预览是否水平镜像） */
    val isFront: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    val cameraLabel: String get() = if (isFront) "前置" else "后置"

    /**
     * 把本机传感器原生方向的画面**转正**所需的顺时针角度（0/90/180/270）。
     *
     * 二期（2026-09-22）：这个角喂给 [GlRotatePipe] 在发送端 GL 里旋转——
     * **发出的数据方向就是正的**，接收端默认零旋转。
     *
     * 自动值 = `SENSOR_ORIENTATION ∓ display.rotation`，即「设备**当前**握姿」下的补偿：
     * 竖持返回 sensor 值、横持会差 90°。**必须读到真实的 display.rotation**——
     * 2026-09-15 实测缺陷：原实现用 `ctx.display`（应用上下文恒抛异常 → 回落 0），
     * 于是**永远按竖持算**，横持时对端画面恒定歪 90°（用户报「需向左转 90 才正」）。
     */
    fun rotationDegrees(): Int {
        val display = displayRotationDegrees()
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + display) % 360
        } else {
            (sensorOrientation - display + 360) % 360
        }
    }

    /**
     * 读真实 display 旋转。
     *
     * ⚠ **不能用 `Context.getDisplay()`**：本类是应用上下文（`CallManager(link, app)`），
     * 非可视上下文在 API 30+ 上恒抛 UnsupportedOperationException → 只能靠 DisplayManager
     * 取默认显示（任意上下文可用，且返回的是窗口管理器当前的实际旋转）。
     */
    private fun displayRotationDegrees(): Int {
        val dm = displayMgr
        val rot = runCatching {
            if (dm != null) {
                dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            } else {
                // 极老设备/异常回落：WindowManager 默认显示
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }
        }.getOrDefault(Surface.ROTATION_0)
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 握姿变化回调（旋转角变了才会触发，已去重）。
     *
     * 上层（[com.phonepctransfer.video.VideoCall]）收到后要**重发一次 `video_cfg`**：
     * 采集画面本身没变（一期不做 GL），变的是「对端该怎么转」，只有重发 cfg 才能生效。
     * 回调跑在 `pp-video-cam` 线程上。
     */
    @Volatile
    var onRotationChanged: ((Int) -> Unit)? = null

    /** 最近一次上报的旋转角，用于去重（display 旋转动画会连发多次回调） */
    @Volatile
    private var reportedRot = -1

    // ==================== 开 / 关 ====================

    /**
     * 把想要的预览流尺寸**吸附到本机相机真正支持的 SurfaceTexture 输出尺寸**。
     *
     * 背景（2026-09-16 a12 实锤）：`setDefaultBufferSize` 钉的尺寸 = Camera2 给该
     * target 配流的尺寸，HAL **不支持的尺寸会建会话失败/不出帧**（854x480 在
     * a12/a16 的 StreamConfigurationMap 里都不存在 → 480P 档黑屏）。
     *
     * 吸附策略：完全一致 → 原样；否则优先**同宽高比（±2%）**里面积最接近的，
     * 没有同比例的就全表面积最接近的。查询失败一律原样返回（老路径兜底）。
     *
     * @return [width, height]（可能是吸附后的值）
     */
    fun pickPreviewSize(w: Int, h: Int): IntArray {
        val id = cameraId ?: return intArrayOf(w, h)
        val sizes = runCatching {
            val ch = camMgr?.getCameraCharacteristics(id)
            ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        }.getOrNull() ?: return intArrayOf(w, h)
        if (sizes.isEmpty()) return intArrayOf(w, h)
        if (sizes.any { it.width == w && it.height == h }) return intArrayOf(w, h)
        val ratio = w.toDouble() / h
        val sameAspect = sizes.filter {
            kotlin.math.abs(it.width.toDouble() / it.height - ratio) / ratio < 0.02
        }
        val pool = if (sameAspect.isNotEmpty()) sameAspect else sizes.toList()
        val best = pool.minByOrNull {
            kotlin.math.abs(it.width.toLong() * it.height - w.toLong() * h)
        } ?: return intArrayOf(w, h)
        if (best.width == w && best.height == h) return intArrayOf(w, h)
        onLog("预览尺寸 ${w}x${h} 本机相机不支持，吸附到 ${best.width}x${best.height}")
        return intArrayOf(best.width, best.height)
    }


    private fun ensureThread(): Handler {
        var h = handler
        if (h == null) {
            val t = HandlerThread("pp-video-cam").apply { start() }
            thread = t
            h = Handler(t.looper)
            handler = h
        }
        return h!!
    }

    /**
     * 打开摄像头并建立会话。
     *
     * @param surfaces 采集目标（**第一个必须是编码器输入 Surface**，其后可跟预览 Surface）
     * @return false = 无摄像头 / 参数不合法（真正打开是异步的，失败走 [onError]）
     */
    fun start(surfaces: List<Surface>, fps: Int = 30): Boolean {
        // 新一轮采集开始：清掉上一轮 stop() 留下的 stopping 标志
        // （不重置的话，本轮 onOpened 会误判为停止中而自杀关相机）
        stopping = false
        if (camMgr == null) {
            onError("本机无 CameraManager")
            return false
        }
        if (surfaces.isEmpty()) {
            onError("相机采集目标为空")
            return false
        }
        targets = surfaces
        fpsRange = Range(fps, fps)

        // 握姿监听：横竖持切换 → display.rotation 变 → 重算旋转角并让上层重发 video_cfg。
        // 一期画面不做 GL 旋转，**只有重发 cfg 才能纠正对端**，所以这一步是必需的。
        reportedRot = -1
        runCatching { displayMgr?.registerDisplayListener(displayListener, ensureThread()) }

        val wantFront = hasFront() && !hasBack()
        val id = idOf(if (wantFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK)
            ?: cameraIds().firstOrNull()
        if (id == null) {
            onError("没有可用摄像头")
            return false
        }
        open(id)
        return true
    }

    private fun open(id: String) {
        val mgr = camMgr ?: return
        val h = ensureThread()

        // 先取消所有旧的在飞 open：它们的回调只关自己的设备、不碰共享状态
        // （快速重呼/快速切摄：旧 open 还在 HAL 里，不能让它建会话或占着相机）
        for (g in inFlightOpens) g.cancelled = true
        // 等旧在飞 open 全部解决（线程没 quit，回调必执行）。
        // 不等的话旧设备可能还开着，同相机的新 openCamera 会被拒（busy）。
        val pending = inFlightOpens.toList()
        if (pending.isNotEmpty()) {
            val deadlineNs = System.nanoTime() + 3_000_000_000L
            for (g in pending) {
                val remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainMs <= 0) {
                    onLog("在飞 open 等待超时（3s），强制继续")
                    break
                }
                runCatching { g.latch.await(remainMs, TimeUnit.MILLISECONDS) }
            }
            // 超时未解决的也移出列表：已 cancelled=true，其迟到回调只会关自己的设备，
            // 留着会让之后每次 open 都白等 3s
            for (g in pending) inFlightOpens.remove(g)
        }

        // 先关旧的（切换摄像头时复用同一路径）。
        // 必须在等待之后：旧 onOpened 若在取消生效前已跑完，device/session 已被它设上，
        // 这里才能可靠关掉
        closeDeviceOnly()

        cameraId = id
        runCatching {
            val ch = mgr.getCameraCharacteristics(id)
            facing = ch.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }

        // 在飞闸门：openCamera 是异步的，回调负责解决（归属 device 或关闭）
        val gate = OpenGate()
        inFlightOpens.add(gate)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gate.cancelled || stopping) {
                        // 过期 open / 挂断抢跑：立即关掉刚打开的设备，不建会话
                        runCatching { d.close() }
                        onLog("摄像头 $id 已打开但已过期/停止中，立即关闭")
                    } else {
                        device = d
                        onLog(
                            "摄像头已打开 $id（${cameraLabel}，sensor=$sensorOrientation°，" +
                                "display=${displayRotationDegrees()}°，对端需旋转 ${rotationDegrees()}°）"
                        )
                        createSession(d)
                    }
                    inFlightOpens.remove(gate)
                    gate.latch.countDown()
                }

                override fun onDisconnected(d: CameraDevice) {
                    onLog("摄像头被断开 $id")
                    runCatching { d.close() }
                    // stopping 时状态由 stop() 自己拆，这里不再动 device
                    if (!stopping && device === d) device = null
                    inFlightOpens.remove(gate)
                    gate.latch.countDown()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    onError("摄像头错误 $id: code=$error")
                    runCatching { d.close() }
                    if (!stopping && device === d) device = null
                    inFlightOpens.remove(gate)
                    gate.latch.countDown()
                }
            }, h)
        } catch (t: Throwable) {
            // openCamera 同步抛异常（如 SecurityException）时不会再有任何回调，
            // 必须自己解决 gate，否则下一次 open 会白等满 3s
            onError("openCamera 失败: ${t.javaClass.simpleName}: ${t.message}")
            inFlightOpens.remove(gate)
            gate.latch.countDown()
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice) {
        val h = ensureThread()
        val outs = targets
        if (outs.isEmpty()) return
        try {
            d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    // stop 可能落在 onOpened 与这里之间：设备已关，不再起流
                    if (stopping) {
                        runCatching { s.close() }
                        return
                    }
                    session = s
                    startRepeating(d, s)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("createCaptureSession 配置失败")
                }
            }, h)
        } catch (t: Throwable) {
            onError("createCaptureSession 异常: ${t.message}")
        }
    }

    private fun startRepeating(d: CameraDevice, s: CameraCaptureSession) {
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (o in targets) req.addTarget(o)
            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            // 固定帧率（低延迟实时编码场景，别让 AE 动态调帧率）
            req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            s.setRepeatingRequest(req.build(), null, ensureThread())
            onLog("采集已启动（${targets.size} 个 target，${fpsRange.lower}~${fpsRange.upper}fps）")
        } catch (t: Throwable) {
            onError("setRepeatingRequest 失败: ${t.message}")
        }
    }

    /**
     * 换一批采集目标（如预览 TextureView 迟到了）。
     * 复用同一个 CameraDevice，只重建 captureSession —— **编码器不重建、码流不中断**。
     */
    fun updateTargets(surfaces: List<Surface>) {
        if (surfaces.isEmpty()) return
        targets = surfaces
        val d = device ?: return
        val h = ensureThread()
        runCatching { session?.close() }
        session = null
        createSessionOn(h, d)
    }

    private fun createSessionOn(h: Handler, d: CameraDevice) {
        val outs = targets
        if (outs.isEmpty()) return
        try {
            d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    // stop 可能落在 onOpened 与这里之间：设备已关，不再起流
                    if (stopping) {
                        runCatching { s.close() }
                        return
                    }
                    session = s
                    startRepeating(d, s)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("重建 captureSession 失败")
                }
            }, h)
        } catch (t: Throwable) {
            onError("重建 captureSession 异常: ${t.message}")
        }
    }

    /**
     * 切换前后摄（R8）。
     *
     * **复用同一个编码输入 Surface**：只换相机 + 重建 captureSession，
     * `MediaCodec` 不重建、码流不中断（切完由上层发一条 video_cfg 通知新的 rot）。
     */
    fun switchCamera(): Boolean {
        val wantFront = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val id = idOf(wantFront) ?: return false
        if (id == cameraId) return false
        open(id)
        return true
    }

    private fun closeDeviceOnly() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    fun stop() {
        // 先置 stopping：让任何迟到的回调（cancelled/stopping）关自己的设备、不建会话
        stopping = true
        runCatching { displayMgr?.unregisterDisplayListener(displayListener) }
        reportedRot = -1
        closeDeviceOnly()
        // ⚠ 这里**不 quit** pp-video-cam 线程：VideoCapture 是 app 级单例，线程随对象存活
        // （全 app 最多 1 条空闲线程）。quit 了的话，之后新 open（快速重呼）的回调会被
        // 已死的 looper 丢弃 → HAL 的 CameraDevice 孤儿 → 相机锁死。
        // 在飞 open 也无需在此等待：其回调会在活线程上关掉自己的设备。
        cameraId = null
        targets = emptyList()
        onLog("摄像头已释放")
    }

    /** 供上层校验：编码器 Surface 是否仍是我们正在用的那个 */
    fun currentTargets(): List<Surface> = targets

    /**
     * 给本地预览用的最小辅助：把 TextureView 的 SurfaceTexture 包成 Surface。
     * 由上层调用（UI 层不做 Surface 生命周期管理）。
     */
    fun surfaceOf(st: SurfaceTexture): Surface = Surface(st).also {
        Log.i(TAG, "本地预览 Surface 已创建 ${it.hashCode()}")
    }
}
