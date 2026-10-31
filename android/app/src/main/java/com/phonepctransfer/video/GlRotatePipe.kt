package com.phonepctransfer.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GL 中间层（二期，2026-09-22）：`相机 →【GL 转正】→ 编码器输入 Surface`。
 *
 * ### 为什么要有这一层
 * 一期方案 B 里 Camera2 把**传感器原生方向**的画面直喂编码器，发出的数据是侧倒的，
 * 只能靠 `video_cfg.rot` 让**接收端**转——但两台设备的后置表现完全不同（手调记忆角
 * 互相打架）。微信同款做法：**发送端先把数据转正再编码**，发出的数据方向就是正的，
 * 接收端默认零旋转、无需任何按机型的补偿。
 *
 * ### 结构
 *  - 私有 `pp-video-gl` 线程 + EGL 上下文（编码器 Surface 需 `EGL_RECORDABLE_ANDROID`）；
 *  - 相机 target = 我们自己的 `SurfaceTexture`（OES 纹理）；其**缓冲尺寸 = 相机输出
 *    尺寸（横向）**，与「编码输出尺寸」是**两回事**，两者分开钉
 *    （[start] 的 camW/camH 与 outW/outH 参数）；
 *  - 每帧：`updateTexImage` → 采样 OES 纹理（套 ST 矩阵）→ **按内容真实宽高比等比
 *    contain 到编码输出尺寸**（`uFit`）→ `eglSwapBuffers`；
 *  - 本地预览**不走这层**（相机多 target 直喂预览 ST，预览路径保持一期原样）。
 *
 * ### ⚠ 为什么必须有 `uFit`（2026-09-22 实测定案）
 * 相机 `SurfaceTexture.getTransformMatrix()` 给的是**转置矩阵**
 * （`u'=1-v, v'=1-u`，实测值 `[0,-1,0,0, -1,0,0,0, 0,0,1,0, 1,1,0,1]`）——
 * 即**系统已把 sensor 横向缓冲转成竖立方向**。此时若仍「全屏 quad 铺满编码器
 * 1920x1080」（= 把 1080x1920 的竖立内容塞进 16:9 横框），**发出的流本身就是
 * 压扁 3.16 倍的**；接收端「看着正常」只是因为它的 View 恰好是竖长条、各向异性
 * 铺满把这份压扁反向抵消掉了（巧合，换 PC/宽屏立刻露出）。
 * 所以这里必须：① 按内容方向选编码尺寸（上层 [start] 的 outW/outH 会在竖持时与
 * 档位宽高交换）；② 再套一层等比 contain 兜底 —— 即便 out 尺寸选错，也只是留黑边，
 * **绝不再变形**。
 *
 * ⚠ 所有 EGL/GL 调用都必须在 gl 线程；对外接口用 CountDownLatch 同步（启动 ≤2s）。
 */
class GlRotatePipe(
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "PP-VideoGl"

        /**
         * UV 旋转符号（2026-09-22 定，实测可翻转）：对 UV 做 `±effRot` 旋转（= 内容转 effRot）。
         * **若实测画面转过头（差 180° 附近不对），把 [UV_ROT_SIGN] 改 -1 即可。**
         */
        private const val UV_ROT_SIGN = 1f

        /**
         * 摆正角公式里的"ST 已转角度"量级（度）：
         *
         * ```
         * effRot = A − base，A = { +90  反置 ST（m[1] < 0，后置式）
         *                        { −90  正置 ST（m[1] > 0，前置式，HAL 已带镜像）
         * ```
         *
         * 定案依据（2026-09-22，两轮真机数据）：
         *  - **竖持**：base=90（后）/270（前）→ effRot 均为 **0**（两端实测：后置本来就正；
         *    前置原公式给 180 → 对端看到**倒立**，就是这条公式的错）；
         *  - **横持**（base=0/180）：effRot = ±90，与横持时期的历史观测一致
         *    （后置需顺时针 90、前置需逆时针 90）。
         *
         * 👉 前后置 `rotationDegrees()` 相差 180°，所以**任何只看 base 的公式必然给两者相差 180**，
         *    而实测要求两者同为 0 —— 这就是必须有 ST 符号项（区分前后置）的原因。
         */
        private const val ROT_BASE_QUARTER = 90

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uST;
            uniform float uRot;
            uniform vec2 uFit;
            varying vec2 vUV;
            void main() {
                vec2 c = aPos * 0.5;
                float s = sin(uRot);
                float k = cos(uRot);
                vec2 r = vec2(c.x * k + c.y * s, c.y * k - c.x * s);
                vUV = (uST * vec4(r + 0.5, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos * uFit, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
        """
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglCfg: EGLConfig? = null

    private var texId = 0
    private var prog = 0
    private var locPos = 0
    private var locST = 0
    private var locRot = 0
    private var locFit = 0
    private var locTexU = 0
    private var quad: FloatBuffer? = null

    private var st: SurfaceTexture? = null

    /** 相机采集目标（GL 输入 Surface），start 成功后非空 */
    var cameraSurface: Surface? = null
        private set

    private var outEgl = EGL14.EGL_NO_SURFACE
    private var outSurface: Surface? = null

    /** 编码输出尺寸（GL viewport / quad 的目标尺寸）。**竖持时 = 档位宽高交换**，由上层算好传入。 */
    private var outW = 0
    private var outH = 0

    /** 相机输出（= GL 输入 ST）缓冲尺寸。**恒为相机支持的横向尺寸**，与输出尺寸分开钉。 */
    private var camW = 0
    private var camH = 0

    /**
     * 原始「转正角」（上层传 `VideoCapture.rotationDegrees()`，随握姿变化）。
     *
     * ⚠ 它不是直接拿去转 UV 的角 —— 见 [ROT_BASE_QUARTER] 里的 `effRot = A − base`。
     */
    @Volatile
    var baseRotationDeg = 0

    /**
     * 当前用的是不是**前置**摄像头（上层 `VideoCapture.isFront`，切摄时同步）。
     *
     * 用途仅一个：ST 符号与摄像头位置**不一致**时兜底（万一某机型前置的 ST 矩阵不带镜像，
     * 即 `m[1] < 0` 与后置同形）。此时按前置口径取 `A = −90` —— 竖持仍得 0，不会又倒过来。
     */
    @Volatile
    var frontCam = false

    /** 实际 UV 旋转angle（gl 线程算出的 effRot，仅用于诊断日志） */
    private var effRotDeg = 0

    /** 等比 contain 缩放（作用于 quad 位置；1,1 = 恰好填满，无黑边） */
    private var fitX = 1f
    private var fitY = 1f

    /** 几何诊断：算出的几何变了（或每一轮启动首次）才打一条 */
    private var geomKey = ""

    private val started = AtomicBoolean(false)

    /**
     * 「允许绘制」闸门（方案 A）：[stop] 在**入队释放任务之前**置 false，
     * [drawFrame] 在入口与各原生阻塞点（updateTexImage / eglSwapBuffers）
     * 返回后检查并早退 —— 给正卡在原生层里的 drawFrame 尽早退出、
     * 让排在其后的释放任务得以执行的机会。
     *
     * 只由实例默认值（true）与 [stop]（false）翻转，帧回调不写它。
     */
    @Volatile
    private var drawing = true

    // ==================== 生命周期 ====================

    /**
     * 启动（同步，≤2s）。
     *
     * @param encoderSurface MediaCodec.createInputSurface() 的产物
     * @param camW/camH 相机输出尺寸（= GL 输入 ST 的缓冲尺寸，**横向**）
     * @param outW/outH 编码输出尺寸（GL viewport；竖持时与档位宽高交换，见上层 outDim）
     * @param baseRotDeg 原始转正角（`VideoCapture.rotationDegrees()`）
     * @return false = EGL/初始化失败（上层应禁用视频）
     */
    fun start(
        encoderSurface: Surface, camW: Int, camH: Int, outW: Int, outH: Int, baseRotDeg: Int
    ): Boolean {
        if (!started.compareAndSet(false, true)) return false
        baseRotationDeg = baseRotDeg
        val t = HandlerThread("pp-video-gl").apply { start() }
        thread = t
        handler = Handler(t.looper)
        val latch = CountDownLatch(1)
        var ok = false
        handler?.post {
            ok = runCatching { initOnGl(encoderSurface, camW, camH, outW, outH) }
                .onFailure { Log.e(TAG, "GL 初始化失败", it) }
                .getOrDefault(false)
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            onError("GL 旋转层初始化超时")
            stop()
            return false
        }
        if (ok) {
            onLog("GL 旋转层已启动（相机 ${camW}x$camH → 输出 ${outW}x$outH，转正角 $baseRotDeg°）")
        }
        return ok
    }

    /**
     * 分辨率切档：GL 输出换到新编码器 Surface，输入缓冲同步钉新尺寸。
     * 相机侧 target（[cameraSurface]）**不变**——输入 ST 与编码器解耦，
     * 上层只需重建 captureSession（updateTargets）。
     */
    fun setOutput(encoderSurface: Surface, camW: Int, camH: Int, outW: Int, outH: Int): Boolean {
        val h0 = handler ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        h0.post {
            ok = runCatching { setOutputOnGl(encoderSurface, camW, camH, outW, outH) }
                .onFailure { Log.e(TAG, "GL 切换输出失败", it) }
                .getOrDefault(false)
            latch.countDown()
        }
        return latch.await(2, TimeUnit.SECONDS) && ok
    }

    fun stop() {
        started.set(false)
        val h = handler ?: return
        // 方案 A：先关绘制闸门 + 中断 GL 线程，让卡在原生阻塞里的 drawFrame
        // 一返回就早退；**必须**在入队释放任务之前做（同线程排队，先到的先跑）
        drawing = false
        runCatching { thread?.interrupt() }
        val latch = CountDownLatch(1)
        h.post {
            runCatching { releaseOnGl() }
            latch.countDown()
        }
        // 方案 B：2s → 10s —— EGL 清理幂等，晚执行无害；
        // 只有到点仍未跑完，quitSafely() 才会丢弃任务（即原生泄漏场景）
        val released = latch.await(10, TimeUnit.SECONDS)
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        if (released) {
            onLog("GL 旋转层已释放")
        } else {
            // 超时兜底：原生泄漏发生时可观测（dumpsys meminfo native 段应同步增长）
            Log.e(TAG, "EGL 释放超时（>10s）：释放任务未执行，display/context/surface 原生资源已泄漏")
            onError("GL 旋转层释放超时（>10s），原生资源可能泄漏")
        }
    }

    // ==================== GL 线程内部 ====================

    private fun initOnGl(
        encoderSurface: Surface, camW: Int, camH: Int, outW: Int, outH: Int
    ): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, num, 0) || num[0] < 1) {
            return false
        }
        eglCfg = cfgs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglCfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        if (!attachOutput(encoderSurface, outW, outH)) return false
        this.camW = camW
        this.camH = camH

        // ---- OES 纹理 + 输入 SurfaceTexture ----
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        val newSt = SurfaceTexture(texId)
        // 相机 ST 钉的是**相机输出尺寸**（横向），不是编码输出尺寸
        newSt.setDefaultBufferSize(camW, camH)
        newSt.setOnFrameAvailableListener(this, handler)
        st = newSt
        cameraSurface = Surface(newSt)

        // ---- 着色器 + 全屏四边形 ----
        prog = buildProgram(VERTEX, FRAGMENT)
        if (prog == 0) return false
        locPos = GLES20.glGetAttribLocation(prog, "aPos")
        locST = GLES20.glGetUniformLocation(prog, "uST")
        locRot = GLES20.glGetUniformLocation(prog, "uRot")
        locFit = GLES20.glGetUniformLocation(prog, "uFit")
        locTexU = GLES20.glGetUniformLocation(prog, "uTex")
        quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }
        onLog("GL 管线就绪：OES 纹理 $texId，program $prog")
        return true
    }

    /** 创建/替换编码器输出 EGL Surface（须持有 context 已初始化的 display） */
    private fun attachOutput(encoderSurface: Surface, w: Int, h: Int): Boolean {
        if (outEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, outEgl)
            outEgl = EGL14.EGL_NO_SURFACE
        }
        outEgl = EGL14.eglCreateWindowSurface(
            eglDisplay, eglCfg, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (outEgl == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(eglDisplay, outEgl, outEgl, eglContext)) return false
        outSurface = encoderSurface
        outW = w
        outH = h
        return true
    }

    private fun setOutputOnGl(
        encoderSurface: Surface, camW: Int, camH: Int, outW: Int, outH: Int
    ): Boolean {
        if (!attachOutput(encoderSurface, outW, outH)) return false
        this.camW = camW
        this.camH = camH
        runCatching { st?.setDefaultBufferSize(camW, camH) }
        geomKey = ""
        return true
    }

    private fun buildProgram(vSrc: String, fSrc: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vSrc)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "program link 失败: ${GLES20.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader 编译失败: ${GLES20.glGetShaderInfoLog(s)}")
            return 0
        }
        return s
    }

    /** 相机帧到达（已在 gl 线程）→ 立即转正绘制进编码器 */
    override fun onFrameAvailable(st: SurfaceTexture?) {
        drawFrame()
    }

    private fun drawFrame() {
        val curSt = st ?: return
        val egl = outEgl
        if (egl == EGL14.EGL_NO_SURFACE) return
        // 方案 A：stop() 已关闸门 → 直接丢帧，不碰 GL
        if (!drawing) return
        runCatching {
            curSt.updateTexImage()
            // 方案 A：updateTexImage 阻塞期间 stop() 可能已置 false → 返回后早退，
            // 不画这一帧（排在其后的释放任务随即执行）
            if (!drawing) return
            val m = FloatArray(16)
            curSt.getTransformMatrix(m)
            applyGeometry(m)
            GLES20.glUseProgram(prog)
            GLES20.glUniformMatrix4fv(locST, 1, false, m, 0)
            GLES20.glUniform1f(locRot, UV_ROT_SIGN * effRotDeg * (Math.PI.toFloat() / 180f))
            GLES20.glUniform2f(locFit, fitX, fitY)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(locTexU, 0)
            quad?.let {
                GLES20.glEnableVertexAttribArray(locPos)
                GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, it)
                GLES20.glViewport(0, 0, outW, outH)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(locPos)
            }
            EGL14.eglSwapBuffers(eglDisplay, egl)
        }.onFailure { Log.e(TAG, "drawFrame 失败: ${it.message}") }
    }

    /**
     * 按 ST 矩阵与 [baseRotationDeg] 算出本帧的 UV 旋转角与等比 fit 缩放（gl 线程）。
     *
     *  - 摆正角 `effRot = A − base`（A 见 [ROT_BASE_QUARTER]）：竖持恒 0（帧与内容同向）、
     *    横持 ±90（帧横内容竖）；
     *  - 内容逻辑尺寸 = 相机尺寸经「ST 转置」与「额外旋转 90/270」两次 swap 的叠加；
     *  - `fit` = 内容等比 contain 到 outW/outH：out 选对时恒为 (1,1)（无黑边），
     *    选错也只是留黑边，**永不变形** —— 这是 2026-09-22 那轮「流被压扁」的兜底。
     */
    private fun applyGeometry(m: FloatArray) {
        val swapSt = Math.abs(m[1]) > Math.abs(m[0])
        effRotDeg = computeEffRot(m, swapSt)
        var cw = if (swapSt) camH else camW
        var ch = if (swapSt) camW else camH
        if (effRotDeg == 90 || effRotDeg == 270) {
            val t = cw
            cw = ch
            ch = t
        }
        if (cw <= 0 || ch <= 0 || outW <= 0 || outH <= 0) {
            fitX = 1f
            fitY = 1f
            return
        }
        val k = minOf(outW.toFloat() / cw, outH.toFloat() / ch)
        fitX = cw * k / outW
        fitY = ch * k / outH
        val key = "$swapSt|${if (m[1] < 0f) "A" else "B"}|$baseRotationDeg|$frontCam|" +
            "${camW}x$camH|${outW}x$outH"
        if (key != geomKey) {
            geomKey = key
            Log.i(
                "PP-VideoTf",
                "GL 几何：ST转置=$swapSt ST符号=${if (m[1] < 0f) "反置" else "正置"} " +
                    "base=$baseRotationDeg 前置=$frontCam effRot=$effRotDeg 内容=${cw}x$ch " +
                    "cam=${camW}x$camH out=${outW}x$outH fit=$fitX,$fitY"
            )
            // 原始矩阵一并留下：判定前后置 ST 符号（几何规则的关键输入）
            Log.i(
                "PP-VideoTf",
                "GL 相机 ST矩阵=[" + m.joinToString(",") { v ->
                    String.format(java.util.Locale.US, "%.3f", v)
                } + "] 前置=$frontCam base=$baseRotationDeg"
            )
        }
    }

    /**
     * 摆正角（UV 旋转，顺时针度）：`A − base`。
     *
     * A 由 ST 矩阵的**符号**决定（区分前后置的传感器安装方向）：
     *  - 反置（`m[1] < 0`，`u'=1-v`）= 后置式 → A = +90
     *  - 正置（`m[1] > 0`，`u'=v`）  = 前置式（HAL 在 ST 里带了镜像）→ A = −90
     *  - 未转置（`|m[0]| > |m[1]|`，实测从未见）→ A = 0
     *
     * 符号与摄像头位置不一致时（见 [frontCam]）按前置口径兜底。
     */
    private fun computeEffRot(m: FloatArray, swapSt: Boolean): Int {
        val a = when {
            !swapSt -> 0
            m[1] < 0f -> if (frontCam) -ROT_BASE_QUARTER else ROT_BASE_QUARTER
            else -> -ROT_BASE_QUARTER
        }
        return ((a - baseRotationDeg) % 360 + 360) % 360
    }

    private fun releaseOnGl() {
        runCatching { st?.setOnFrameAvailableListener(null) }
        runCatching { st?.release() }
        st = null
        runCatching { cameraSurface?.release() }
        cameraSurface = null
        if (texId != 0) {
            runCatching {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                if (prog != 0) GLES20.glDeleteProgram(prog)
            }
        }
        texId = 0
        prog = 0
        if (outEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, outEgl)
            outEgl = EGL14.EGL_NO_SURFACE
        }
        outSurface = null
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
