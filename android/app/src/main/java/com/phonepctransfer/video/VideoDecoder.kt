package com.phonepctransfer.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.phonepctransfer.util.SegMeter
import java.util.ArrayDeque

/**
 * HEVC / H.264 硬件解码器，输出到 Surface（计划 §5.3）。
 *
 * 设计要点：
 *  - **CONFIG 帧确定性 `configure()`**：参数集（VPS/SPS/PPS）作为 `BUFFER_FLAG_CODEC_CONFIG`
 *    入队，不依赖任何 Annex-B 解析。
 *  - **入队即返回**：网络读线程只调 [enqueue]，解码在私有 `pp-video-dec` 线程 ——
 *    在读线程里初始化解码器会阻塞 → 对端 35s 判死掉线（项目已踩过的红线）。
 *  - **有界队列 + 丢最旧**：UI/解码跟不上时宁可丢帧也不无限积压（视频通话的正确取舍）。
 */
class VideoDecoder(
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "PP-VideoDec"
        private const val MAX_QUEUE = 12
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** vOut 配对环容量（2 的幂）：与 PC 端 FfmpegDecoder 的 DEQ_RING 同宽 */
        private const val DEQ_RING = 64
        private const val DEQ_MASK = DEQ_RING - 1
    }

    /**
     * 队列元素（与 PC 端 `FfmpegDecoder.PendingAu` 同构）：
     *  · [recvMs] —— 本端收到并入队的时刻（墙钟）：只用于算排队时延 `lat`（任务单 1.3）。
     *  · [tsMs]   —— 发送端的**通话相对**时间戳（对端起呼=0 的 ms）：只作 presentationTimeUs；
     *    它与 [recvMs] 不同零点，**禁止相减**。
     */
    private class Item(
        val data: ByteArray,
        val len: Int,
        val config: Boolean,
        val tsMs: Long,
        val recvMs: Long
    )

    private val queue = ArrayDeque<Item>()
    private val lock = Object()

    // ==================== vOut 配对环（任务单 1.2，与 PC 端 FfmpegDecoder 同设计） ====================
    // 压入 = 「出队并已喂进 MediaCodec 的**图像** AU」的时刻；弹出 = 输出一帧渲染返回后。
    // 配对依据：发端编码器不产 B 帧 → 喂入序 = 输出序，1 AU 出 1 帧。
    // 参数集（config）喂得进去但不产帧，所以**不压环**，否则输出与时刻从这一刻起永久错开一帧。
    // 环满（输出落后 > 64 帧）丢最旧：宁可少样本，也不要错样本。头尾指针都在 lock 下动。
    private val deqRing = LongArray(DEQ_RING)
    private var deqHead = 0
    private var deqTail = 0

    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var mime: String? = null

    @Volatile
    private var w = 0

    @Volatile
    private var h = 0

    /** 已解码输出的帧数 / 因队列满被丢弃的帧数（供 video_fb 上报） */
    private val decodedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val droppedCount = java.util.concurrent.atomic.AtomicInteger(0)

    val decodedFrames: Int get() = decodedCount.get()
    val droppedFrames: Int get() = droppedCount.get()

    @Volatile
    private var lastDecodeAt = 0L

    /**
     * 最近一帧「入队 → 真正喂给解码器」的排队时延（ms）—— video_fb 的 lat 指标。
     * **唯一写入点**在 [drainLoop] 取帧处（任务单 1.3：此前全工程只有声明与读取，恒 0）。
     */
    @Volatile
    var lastQueueDelayMs: Int = 0
        private set

    /**
     * 接收端此刻是否正处于「丢帧期」（任务单 1.3，与 PC 端 `FfmpegDecoder.dropping()` 同名）。
     * 安卓这条链路本来就是**丢最旧、不等 IDR**，所以口径是「最近一次入队有没有挤掉旧帧」：
     * 溢出 → true，下一次入队不溢出 → false。语义与 PC 一致：**这一时刻的 lat 不可信，
     * 画面正在丢帧**（旧口径把它折成 lat=0，最坏状态反而表现为"零延迟"）。
     */
    @Volatile
    var dropping: Boolean = false
        private set

    val lastDecodeMs: Long get() = lastDecodeAt

    val ready: Boolean get() = codec != null

    /** 设置输出 Surface（UI 的 TextureView 就绪时调用；会按已知参数重建解码器） */
    @Synchronized
    fun attachSurface(s: Surface) {
        // 覆盖前释放旧 Surface（防二次 attach 泄漏；UI 随后再 release 属安全重复）
        runCatching { surface?.release() }
        surface = s
        val m = mime
        if (m != null && w > 0 && h > 0) {
            prepare(m, w, h)
        }
    }

    /**
     * 参数一致时**不重建**（UI 每次挂 Surface 都会调，避免无谓地重建解码器）。
     * 参数不同或尚无解码器 → 走 [prepare]。
     */
    @Synchronized
    fun prepareIfNeeded(mimeWanted: String, width: Int, height: Int): Boolean {
        if (codec != null && mime == mimeWanted && w == width && h == height) return true
        return prepare(mimeWanted, width, height)
    }

    /**
     * 按编码参数创建解码器。**必须已有 Surface**，否则返回 false。
     * 重复调用会重建（参数变化时正是我们要的行为）。
     *
     * ⚠ `@Synchronized`：调用方可能来自两个线程（UI 挂 Surface / 网络分发线程收 cfg），
     * 无锁并发会出现「一边 configure、另一边 stop+release 同一个 MediaCodec」→
     * `IllegalArgumentException` → 对端画面整场黑屏（2026-09-22 a16 实测）。
     */
    @Synchronized
    fun prepare(mimeWanted: String, width: Int, height: Int): Boolean {
        val s = surface
        if (s == null) {
            Log.i(TAG, "Surface 未就绪，暂不创建解码器（等 UI 挂上）")
            return false
        }
        stopInternal()
        mime = mimeWanted
        w = width
        h = height
        return try {
            val name = VideoCodecs.findDecoder(mimeWanted)
            val c = if (name != null) MediaCodec.createByCodecName(name)
            else MediaCodec.createDecoderByType(mimeWanted)

            val fmt = MediaFormat.createVideoFormat(mimeWanted, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                if (Build.VERSION.SDK_INT >= 30) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            c.configure(fmt, s, null, 0)
            c.start()
            codec = c
            decodedCount.set(0)
            droppedCount.set(0)
            running = true
            dropping = false
            synchronized(lock) { queue.clear() }
            clearDeqRing()
            thread = Thread({ drainLoop() }, "pp-video-dec").apply { isDaemon = true }.also { it.start() }
            Log.i(TAG, "解码器就绪：${name ?: "default"}（${VideoCodecs.name(mimeWanted)} ${width}x$height）")
            true
        } catch (t: Throwable) {
            onError("解码器创建失败: ${t.javaClass.simpleName}: ${t.message}")
            codec = null
            running = false
            false
        }
    }

    /**
     * 网络读线程调用：只入队（有界，满则丢最旧并计数）。
     *
     * @param tsMs 该帧的**发送端通话相对**毫秒（对端起呼=0，与对端音频帧同零点）。
     *             它直接喂给 `queueInputBuffer` 当 presentationTimeUs，因此只需单调，
     *             不可与本端墙钟比较大小。
     */
    fun enqueue(data: ByteArray, len: Int, config: Boolean, tsMs: Long) {
        if (!running) return
        val recvMs = System.currentTimeMillis()
        synchronized(lock) {
            // 队列已满 = 本端解码跟不上，这一帧会把最旧的挤出去 → 丢帧期（lat 此刻不可信）
            val overflow = queue.size >= MAX_QUEUE
            while (queue.size >= MAX_QUEUE) {
                queue.pollFirst()
                droppedCount.incrementAndGet()
            }
            queue.addLast(Item(data, len, config, tsMs, recvMs))
            dropping = overflow
            lock.notifyAll()
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val c = codec ?: break
                // 取一帧：拿不到就 continue 回到循环头重新判 running/codec
                var item: Item? = null
                var deqNano = 0L
                synchronized(lock) {
                    if (queue.isEmpty()) {
                        runCatching { lock.wait(20) }
                    }
                    val polled = queue.pollFirst()
                    item = polled
                    if (polled != null) {
                        deqNano = System.nanoTime()
                        // lat 的**唯一写入点**：入队（本端墙钟 recvMs）→ 真正喂解码器前。
                        // 只用同一个墙钟轴做差，绝不相减对端 ts（任务单 1.3）
                        lastQueueDelayMs = (System.currentTimeMillis() - polled.recvMs)
                            .coerceIn(0L, 9999L).toInt()
                    }
                }
                val frame = item ?: continue
                feedOne(c, frame, info, deqNano)
            }
        } catch (t: Throwable) {
            if (running) onError("解码循环异常: ${t.message}")
        }
    }

    private fun feedOne(c: MediaCodec, item: Item, info: MediaCodec.BufferInfo, deqNano: Long) {
        // 取输入缓冲；最多等 200ms，超时视为本机解码跟不上 → 丢这一帧
        var idx = -1
        val deadline = System.currentTimeMillis() + 200
        while (System.currentTimeMillis() < deadline) {
            idx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx >= 0) break
            // 同时排空输出，避免输出缓冲占满导致不再给输入
            drainOutput(c, info)
        }
        if (idx < 0) {
            droppedCount.incrementAndGet()
            Log.w(TAG, "解码器输入缓冲枯竭，丢 1 帧（累计 $droppedFrames）")
            return
        }
        try {
            val buf = c.getInputBuffer(idx)
            if (buf == null) {
                droppedCount.incrementAndGet()
                return
            }
            buf.clear()
            buf.put(item.data, 0, item.len)
            val flags = if (item.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            c.queueInputBuffer(idx, 0, item.len, item.tsMs * 1000L, flags)
            // 只有会产帧的 AU 进配对环（参数集不产帧，压环 = 后续每帧都错配一帧）
            if (!item.config) {
                pushDeqNano(deqNano)
            }
        } catch (t: Throwable) {
            onError("喂帧失败: ${t.message}")
        }
        drainOutput(c, info)
    }

    private fun drainOutput(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val oi = try {
                c.dequeueOutputBuffer(info, 0L)
            } catch (t: Throwable) {
                return
            }
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                oi >= 0 -> {
                    val isFrame = info.size > 0
                    if (isFrame) {
                        decodedCount.incrementAndGet()
                        lastDecodeAt = System.currentTimeMillis()
                    }
                    runCatching { c.releaseOutputBuffer(oi, true) }
                    // 交付完成 → 弹出该帧的出队时刻记 vOut（安卓的「上屏」= releaseOutputBuffer(render=true) 返回）
                    if (isFrame) {
                        popDeqAndRecord()
                    }
                }
                else -> return
            }
        }
    }

    /** 压入一个「已喂进解码器的图像 AU」的出队时刻（pp-video-dec 线程，lock 下）。 */
    private fun pushDeqNano(nano: Long) {
        synchronized(lock) {
            deqRing[deqTail] = nano
            deqTail = (deqTail + 1) and DEQ_MASK
            if (deqTail == deqHead) {
                deqHead = (deqHead + 1) and DEQ_MASK  // 满：丢最旧（宁少不错）
            }
        }
    }

    /**
     * 弹出一个配对时刻并记 vOut 段（解码线程）。
     * 环空 = 这一帧输出没有配对（例如解码器内部缓冲了一帧），直接跳过。
     */
    private fun popDeqAndRecord() {
        val t0 = synchronized(lock) {
            if (deqHead == deqTail) {
                return
            }
            val v = deqRing[deqHead]
            deqHead = (deqHead + 1) and DEQ_MASK
            v
        }
        SegMeter.record(SegMeter.V_OUT, t0, System.nanoTime())
    }

    /** 清空配对环（解码器换代 / 停止：旧时刻与新输出不再对应） */
    private fun clearDeqRing() {
        synchronized(lock) {
            deqHead = 0
            deqTail = 0
        }
    }

    private fun stopInternal() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        val c = codec
        codec = null
        runCatching { thread?.join(300) }
        thread = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        synchronized(lock) { queue.clear() }
        clearDeqRing()
    }

    @Synchronized
    fun stop() {
        stopInternal()
        surface = null
        mime = null
        w = 0
        h = 0
        Log.i(TAG, "解码器已停止")
    }
}
