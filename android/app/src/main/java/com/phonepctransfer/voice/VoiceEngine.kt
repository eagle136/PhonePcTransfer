package com.phonepctransfer.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.phonepctransfer.util.CallCounters
import com.phonepctransfer.util.SegMeter
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 端语音引擎：实时麦克风采集 → Opus(96k) 编码 → 0.2s 一帧发出；
 * 收端：Opus 解码 → 抖动缓冲对时 → AudioTrack 播放。
 *
 * 关键设计（与 PC 端 VoiceEngine.java 严格对齐）：
 *  - 采样 48kHz、单声道、16bit。
 *  - **语音通话**：帧长 200ms → 9600 采样 = 19200 字节 PCM；码率 64kbps 起步，
 *    弱网由 [VoiceBitrateController] 自适应降到 32k（Opus 码率对解码端透明）。
 *  - **视频通话**：帧长 40ms（[VIDEO_FRAME_MS]）→ 唇音同步；码率随档位 L0 32k / L1~L3 64k。
 *    由 [configure] 在 startRecord/startPlay 之前一次性设定，**通话中不切换**。
 *  - Opus 帧长固定取 20ms（480 采样），一帧语音 = 10 个子帧、一帧视频通话 = 2 个子帧。
 *  - 抖动缓冲：接收端按 seq 排序、**严格按序播放**；seq 断档按静音补齐（同步对时），
 *    从而「丢包→重传一次→再失败→静音加速跳过」不会让两端时间越漂越大。
 *  - 发送侧的重传/静音策略由 CallManager（上层）依据 TCP 可靠性兜底；TCP 下几乎不丢包。
 */
class VoiceEngine {

    companion object {
        private const val TAG = "PP-Voice"

        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1

        /**
         * 纯语音通话帧长：**低延迟模式 = 20ms**（1 个 Opus 子帧，2026-09-15 起默认）。
         *
         * 帧长是**收发两端硬对称**的常量（播放端按 seq 严格对齐时间轴），所以只有
         * **对端也声明支持 20ms** 时才启用（协商字段 = `call_invite`/`call_accept` 的 `af`）。
         * 老版本对端不带这个字段 → 回落 [VOICE_FRAME_MS]（200ms），行为与改造前完全一致。
         *
         * 收益：采集侧「填满一帧才发送」的固有延迟从 200ms 直接降到 20ms（-180ms），
         * 同时把播放侧缓冲粒度也缩小 10 倍（见 [startRecord] / [startPlay] 的缓冲算法）。
         */
        const val VOICE_FRAME_MS_LOWLAT = 20

        /** 纯语音通话帧长的**兼容模式**（对端是老版本时用）：200ms */
        const val VOICE_FRAME_MS = 200

        /**
         * 视频通话帧长 40ms（= 2 个 20ms Opus 子帧）。
         *
         * 现状音频链路时延较大（录 200ms/帧 + AudioTrack 缓冲≈800ms + 输出硬件），
         * 视频通话下唇音差可达 250ms。缩到 40ms 把音频时延压下来（计划 §3.7），
         * 顺带把「音频被卡住的粒度」从 200ms 缩到 40ms（视频帧与音频共用 writeLock）。
         *
         * 低风险依据：视频通话只可能发生在**双方都是新版本**时（老端收不到 vid，
         * 根本进不了视频模式），所以这个对称常量改动没有跨版本兼容问题。
         */
        const val VIDEO_FRAME_MS = 40

        const val OPUS_FRAME_MS = 20
        const val OPUS_FRAME_SAMPLES = SAMPLE_RATE * OPUS_FRAME_MS / 1000   // 960

        /** 语音通话初始码率（2026-09-15 用户要求：语音通话从 96k 降到 64k 起步） */
        const val BITRATE_VOICE = 64000
        /**
         * 语音通话最低码率（弱网自适应下限，见 [VoiceBitrateController]）。
         *
         * 通话开始用 [BITRATE_VOICE]（64k），网络拥塞时降到 32k、恢复后升回 64k。
         * Opus 码率对解码端**完全透明**，切换不需要重建解码器。
         */
        const val BITRATE_VOICE_LO = 32000

        /** 视频通话档位码率：L0 480p → 32k；L1~L3 720p/1080p → 64k */
        const val BITRATE_VIDEO_L0 = 32000
        const val BITRATE_VIDEO_L1 = 64000

        /**
         * 缺帧等待轮询间隔（ms）。**不引入额外延迟**：播放端不预缓冲，
         * 只要下一帧到了就立刻解码写出；这个值只决定「队列空时多久回来看一眼」。
         * 20ms 帧长下必须调到毫秒级，否则等待本身就成了延迟。
         */
        const val POLL_MS = 4L

        /**
         * 缺帧容忍窗口（ms）—— **轻量抖动缓冲**。
         *
         * 等的是「下一帧迟到」这一种情况；超时仍不到就补静音跳过（时间轴不漂）。
         * 20ms 帧下取 30ms（≈1.5 帧）：足够吸收局域网 + Android 调度的抖动，
         * 又不会让通话延迟明显抬头。200ms 兼容模式同样受用。
         */
        const val FRAME_WAIT_MS = 30L

        /**
         * 播放积压上限（ms，2026-09-15 用户报「弱网后语音延迟好几十秒」的根因修复）。
         *
         * 旧 playLoop 严格按 seq 追播：网络卡顿期间队列积压 N 帧，恢复后**播放速率 =
         * 到达速率**，积压永远追不完 —— 每卡一次延迟净增一截，反复卡就叠到几十秒。
         * 积压超过 [BACKLOG_MAX_MS] 时直接跳到最新帧（保留 [BACKLOG_KEEP_MS] 平滑），
         * 跳过的帧按缺帧计数。正常通话积压远小于阈值，永不触发。
         */
        const val BACKLOG_MAX_MS = 600
        const val BACKLOG_KEEP_MS = 120

        // ==================== 10-06 播放水位（双向收敛，替代"只靠 600ms 悬崖"） ====================
        //
        // 与 PC VoiceEngine.java 同名同值同语义（改动必须双端成对）。两端的坏方向**相反**：
        //   · PC：JavaSound 的 write 基本恒阻塞 ⇒ 消费被声卡钉死在 1:1，一次 312ms 的 write
        //     造成的 16 帧垫层整场不回落（10-05 实测 aBuf p50 290–304ms）；
        //   · 安卓：AudioTrack.write 缓冲够就不阻塞 ⇒ 播放循环跑在设备前面，playedSeq 超过
        //     真实播出点，后面的帧全被"迟到闸门"判死 ⇒ 持续补静音的自锁（10-03 实测 25 次/s）。
        // 所以两条口径分工：
        //   · 上界（队列深过 targetFrames ⇒ 每格裁最旧一帧）治 PC 的固化垫层；
        //     上界只判队列：线内那段是 write 恒阻塞的固定偏移，裁不掉，只能等声卡消化。
        //   · 下界（总水位 = 队列 + 线内 > totalMaxMs ⇒ 本格不取帧）治安卓的跑赢自锁。
        //   · 缺帧的 30ms 等待窗口只在队列 ≤ 目标时生效（等满 30ms 再写 20ms 静音 = 每个缺帧
        //     净给队列加 1.5 帧，正是垫层的来源）。
        // 600ms 悬崖原样保留兜底，正常通话已经够不着它。

        /** 队列水位目标（ms）：20ms 帧 ⇒ 4 帧；线内另有 0–42ms 由下界管 ⇒ 总水位落在 ~80–122ms */
        const val PLAY_TARGET_MS = 80

        /** 总水位上限（ms）：超过就本格不取帧。必须 > PLAY_TARGET_MS，否则两条规则互相矛盾 */
        const val PLAY_TOTAL_MAX_MS = 120

        /**
         * 连续"不取帧"的格数上限（× [POLL_MS] ≈ 100ms）—— **保命放行**。
         * 安卓上这条尤其重要：AudioFlinger/HAL 那段不含在 [backlogWireMs] 里（Android 16 机型 实测
         * 21–100ms），设备 standby 时 head 不推进 ⇒ 纯水位判据会把播放线程永久钉住。
         */
        const val PLAY_HOLD_MAX_TICKS = 25

        /**
         * 事件日志节流窗口（ms，任务单 1.6）：缺帧/补静音/积压快进**不再逐次打印**，
         * 攒成 1 行/秒的汇总（行内带次数）。20ms 帧下这三类最高 ~33 行/s。
         * **PC 同名同值**（VoiceEngine.java 的 EVENT_SUMMARY_MS）。
         */
        const val EVENT_SUMMARY_MS = 1000L

        /**
         * 线内 PCM 折算系数：48kHz 单声道 ⇒ **48 样本 = 1ms**
         * （等价于 PC 的 96 字节/ms：16bit × 单声道 = 2 B/样本）。
         */
        const val WIRE_SAMPLES_PER_MS = 48L
    }

    /**
     * 当前帧长（ms）。由 [configure] 在 startRecord/startPlay **之前**一次性设定，
     * **通话中不切换** —— 收发两端的帧长是硬对称常量，动态改会让播放端时间轴错乱。
     */
    @Volatile
    private var frameMs = VOICE_FRAME_MS

    /** 当前帧长（ms）只读出口：码率自适应按帧长换算积压阈值（PC VoiceEngine.frameMs() 同义） */
    val audioFrameMs: Int get() = frameMs

    /** 队列水位目标的帧数（上界判据）；200ms 兼容帧下限 2 帧，不把老端节奏改坏 */
    private fun targetFrames(): Int = (PLAY_TARGET_MS / frameMs).coerceAtLeast(2)

    /** 总水位上限（下界判据）：20/40ms→120，200ms→600（= 悬崖值 ⇒ 兼容模式下 hold 恒不生效） */
    private fun totalMaxMs(): Long = maxOf(PLAY_TOTAL_MAX_MS.toLong(), 3L * frameMs)

    /** 当前 Opus 码率（对解码端完全透明，可随时改） */
    @Volatile
    private var bitrate = BITRATE_VOICE

    /**
     * 静音开关（视频界面「静音」按钮）。
     *
     * 语义 = **不发送**，而不是发送静音帧：编码直接跳过以省 CPU，但 **seq 仍然递增**，
     * 对端看到的是"缺帧"→ 走它既有的补静音分支，时间轴不会因为少发帧而漂移。
     */
    @Volatile
    private var muted = false

    fun setMuted(m: Boolean) {
        muted = m
        Log.i(TAG, if (m) "麦克风已静音" else "麦克风已取消静音")
    }

    val isMuted: Boolean get() = muted

    private val frameSamples: Int get() = SAMPLE_RATE * frameMs / 1000
    private val frameBytes: Int get() = frameSamples * 2
    private val opusSubframes: Int get() = frameMs / OPUS_FRAME_MS

    /**
     * 设定本次通话的音频参数。**必须在 startRecord()/startPlay() 之前调用**。
     *
     * @param videoMode     true = 视频通话（40ms 帧长，唇音同步用，不受 af 协商影响）
     * @param videoBitrate  视频通话码率（L0 32k / L1~L3 64k）；语音模式忽略，
     *                      语音模式起步恒 [BITRATE_VOICE]（64k），通话中由
     *                      [VoiceBitrateController] 自适应升降。
     * @param voiceFrameMs  纯语音通话的帧长：**对端也支持时传 [VOICE_FRAME_MS_LOWLAT]（20ms）**，
     *                      否则必须传 [VOICE_FRAME_MS]（200ms，与老端对齐）。
     */
    @Synchronized
    fun configure(
        videoMode: Boolean,
        videoBitrate: Int = BITRATE_VIDEO_L1,
        voiceFrameMs: Int = VOICE_FRAME_MS_LOWLAT
    ) {
        frameMs = if (videoMode) {
            VIDEO_FRAME_MS
        } else if (voiceFrameMs <= VOICE_FRAME_MS_LOWLAT) {
            VOICE_FRAME_MS_LOWLAT
        } else {
            VOICE_FRAME_MS
        }
        bitrate = if (videoMode) videoBitrate else BITRATE_VOICE
        Log.i(
            TAG,
            "音频配置：帧长 ${frameMs}ms，码率 ${bitrate / 1000}k，" +
                if (videoMode) "模式=视频" else "模式=语音（${if (frameMs <= VOICE_FRAME_MS_LOWLAT) "低延迟" else "兼容 200ms"}）"
        )
    }

    /** 档位联动：只改 Opus 码率（解码端透明，无需重建解码器） */
    fun setBitrate(b: Int) {
        bitrate = b
        runCatching { encoder?.bitrate = b }
    }

    /** 编码好的一帧 Opus 数据（上层通过它发给对端） */
    class EncodedFrame(val seq: Int, val data: ByteArray, val encDoneNano: Long)

    interface Listener {
        /** 采集+编码出一帧，交给上层发送（在采集线程回调） */
        fun onEncoded(frame: EncodedFrame)
    }

    /** 采集线程运行标志（与播放**独立**）：旧版两者共用一个标志，stopRecord 会把播放线程一起停掉 */
    private val recordRunning = AtomicBoolean(false)

    /** 播放线程运行标志 */
    private val playRunning = AtomicBoolean(false)

    private val recording = AtomicBoolean(false)

    @Volatile
    private var listener: Listener? = null

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    // 解码播放队列：按 seq 排序的缓冲（用于抖动对时）。
    // 元素带发送端 ts（任务单 1.1：ts 要一路带到消费点，不能在读线程丢掉）；
    // rxNano = 本端入队完成的 nanoTime，aBuf 段「入队→出队」的起点（任务单 1.2）
    private class Inbound(val opus: ByteArray, val ts: Long, val rxNano: Long)

    private val playQueue = java.util.concurrent.ConcurrentSkipListMap<Int, Inbound>()

    /** 最近一帧**实际写入播放设备**的发送端 ts（对端起呼=0 的 ms，-1 = 尚未播出） */
    @Volatile
    var lastPlayedTsMs = -1L
        private set

    // 播放未就绪/播放线程已死时的丢帧日志节流（最多 1 行/秒，防刷屏）
    private val lastDropLogAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 播放未就绪期间被丢的帧数（接收线程累加，汇总行读并清零） */
    private val notReadyDropped = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 迟到帧（seq ≤ playedSeq）被丢的本秒计数与最大落后帧数（任务单 T6 取证）。
     * 与 [notReadyDropped] 各自独立：两条闸门同时触发时互不掩盖，谁丢的分数看得清。
     * 只在「确实丢了」这条支路上读写（正常通话恒 0，不进热路径）。
     */
    private val lateSeqDropped = java.util.concurrent.atomic.AtomicInteger(0)
    private val lateSeqMaxLag = java.util.concurrent.atomic.AtomicInteger(0)

    /** 迟到帧日志节流时刻（最多 1 行/秒，与 [lastDropLogAt] 同款，防 25 帧/s 刷屏） */
    private val lastLateSeqLogAt = java.util.concurrent.atomic.AtomicLong(0)

    // 播放线程事件计数（任务单 1.6）：只有播放线程自己读写 ⇒ 不用原子类，也无竞争
    private var evSilenceFill = 0      // 缺帧补静音跳过次数
    private var evBacklogJump = 0      // 积压快进次数
    private var evBacklogDropped = 0   // 快进跳过的帧数
    private var evTrim = 0             // 10-06 水位上界裁掉的最旧帧数
    private var evHoldCap = 0          // 10-06 水位下界 hold 到保命上限、被迫放行的次数
    private var holdTicks = 0          // 水位下界连续生效格数（取到帧即归零）
    private var evSummaryAt = 0L       // 上次打播放事件汇总的时刻（ms）

    // 已播放的最大 seq（静音补齐 + 判定迟到帧用）
    @Volatile
    private var playedSeq = -1

    // ==================== 接收质量统计（语音码率自适应判据） ====================

    /** 收到的音频帧数（缺帧率的分母） */
    private val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 缺帧补静音跳过的次数（对端没发 / 迟到超过一帧 → 播放端被迫跳过） */
    private val missedCount = java.util.concurrent.atomic.AtomicInteger(0)

    val receivedFrames: Int get() = receivedCount.get()

    val missedFrames: Int get() = missedCount.get()

    /** 当前播放队列积压帧数（>1 = 送达慢于播放 → 链路拥塞） */
    val pendingFrames: Int get() = playQueue.size

    // ==================== 积压口径：队列 + 线内（任务单 1.5，与 PC VoiceEngine 同式） ====================

    /** 旧口径（只算队列）：队列帧数 × frameMs。不含线内 ⇒ 比真实积压虚好 40–43ms */
    val backlogQueueMs: Long get() = playQueue.size.toLong() * frameMs

    /**
     * 已写入 [AudioTrack] 的累计样本数（单声道 ⇒ 样本 = 帧）。
     * 只由播放线程在 `write()` 返回后累加 ⇒ 与 `getPlaybackHeadPosition()` 同轴可减。
     */
    @Volatile
    private var writtenSamplesTotal = 0L

    /**
     * 线内未播部分（ms）= (写入选样总数 − playbackHeadPosition) / 48。
     *
     * **降级口径：返回 -1 = 本端此刻拿不到线内字节**（track 未建 / 已 release / 系统抛异常）。
     * 调用方必须把 -1 原样打成"不可测"，**不许折成 0 少算**（任务单 1.5 自查项）。
     */
    val backlogWireMs: Long get() {
        val t = track ?: return -1L
        return try {
            // headPosition 是 32 位无符号计数（48kHz 下约 24.8h 才回绕）；与本场累计一起
            // 在 startPlay 归零，所以通话期内不会跨回绕点
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val unplayed = writtenSamplesTotal - head
            if (unplayed <= 0L) 0L else unplayed / WIRE_SAMPLES_PER_MS
        } catch (e: Throwable) {
            -1L
        }
    }

    /*
     * 音频积压总口径（ms）= 队列 ms + max(0, 线内 ms)（方案 A3）。
     * 相加式写在消费点（一次读队列、一次读线内，保证分量与总和对得上），双端各一处：
     * 安卓 voice/CallManager 的 pp-voice-aq 采样、PC CallManager 同名线程的同一位置。
     * 本单只把它接到观测/日志上；码率自适应判据仍是**帧**口径（换毫秒属任务单 4.2/4.10）。
     */

    fun resetStats() {
        receivedCount.set(0)
        missedCount.set(0)
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    // ==================== 发送（采集 + 编码） ====================

    /** 开始录音编码。必须在持有 RECORD_AUDIO 权限后调用。 */
    @Synchronized
    fun startRecord(): Boolean {
        if (recording.get()) return true
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // 低延迟关键：缓冲只要 **2 帧**（20ms 帧 → 40ms），不再要 4 帧（旧的 800ms）。
            // 下限仍是系统 minBuf（低延迟模式下它会显著变小，走 fast capture 路径）。
            val buf = maxOf(minBuf, frameBytes * 2)
            val r = buildRecord(buf)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                Log.e(TAG, "AudioRecord 初始化失败 state=$r.state（STATE_UNINITIALIZED=0：多为无 RECORD_AUDIO 权限或参数不支持）")
                return false
            }
            // 提前绑定 recorder：后续步骤（编码器构造/建线程）抛异常时，catch 可经它释放 AudioRecord
            recorder = r
            encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
            encoder!!.bitrate = bitrate
            recording.set(true)
            recordRunning.set(true)
            recordThread = Thread({ recordLoop(r) }, "pp-voice-rec").apply { isDaemon = true }.also { it.start() }
            Log.i(TAG, "开始录音（48kHz 单声道，Opus ${bitrate / 1000}k，${frameMs}ms/帧）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动录音失败", t)
            // 启动失败：释放已创建的 AudioRecord（音频 HAL 句柄），复位运行标志，保证可重试
            recording.set(false)
            recordRunning.set(false)
            runCatching { recorder?.release() }
            recorder = null
            encoder = null
            false
        }
    }

    /**
     * 建 AudioRecord：用 `Builder` + **小缓冲**（旧写法 `AudioRecord(...)` 只能给一个
     * 大缓冲，且不能表达其他配置）。
     *
     * ⚠ **AudioRecord 没有公开的「低延迟性能模式」API**（`PERFORMANCE_MODE_*` 只在
     * `AudioTrack` 上有；`AudioRecord.Builder` 无 `setPerformanceMode`，2026-09-15 在
     * compileSdk 37 的 android.jar 里核对过）。采集侧的低延迟只能靠
     * ① `VOICE_COMMUNICATION` 音频源（走通信路径）② **缓冲只要 2 帧**。
     */
    private fun buildRecord(bufBytes: Int): AudioRecord {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "AudioRecord.Builder 创建失败，回落普通构造器：${e.message}")
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
        }
    }

    private fun recordLoop(r: AudioRecord) {
        try {
            r.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording 失败", t)
            return
        }
        val pcm = ByteArray(frameBytes)
        val short = ShortArray(OPUS_FRAME_SAMPLES)
        var seq = 0
        // ---- 10-06 T3.1/T3.2 发端取证（只加仪表，不改任何发送行为；PC VoiceEngine.recordLoop 同式）----
        // T3.1 短读：补零后**照常 seq++ 发出去** ⇒ 对端收到一个"合法但全静音"的帧，
        //      双端所有接收计数都不会动 ⇒ 这是"听感断续而计数全 0"的唯一静默通道，必须单独计。
        // T3.2 aPace：按 seq 反推每帧**应到时刻**，量偏差（正 = 本端产出比实时慢）。
        //      为什么不看 aEnc：20ms 帧下 PC 实测 aEnc p50=30ms 而每秒样本 n=50 ⇒ 分布是双峰，
        //      判不出"驱动突发给数据"还是"采集线程真的落后"。稳态应有界（±几 ms）；
        //      单调增长 = 断续的根在发端，与收端水位无关 ⇒ 另开单。
        val paceWin = IntArray(64)   // 1s 窗口最多 50 帧（20ms 帧）⇒ 64 是纯防御
        var paceN = 0
        var baseNano = 0L
        var winFrames = 0
        var winShortRead = 0
        var winShortLastFilled = 0
        var winNano = 0L
        try {
            while (recordRunning.get()) {
                val t0 = System.nanoTime()
                // 读满一帧 PCM
                var filled = 0
                while (filled < frameBytes) {
                    val n = r.read(pcm, filled, frameBytes - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < frameBytes) {
                    // 不足一帧，补静音再发（保证帧对齐）
                    java.util.Arrays.fill(pcm, filled, frameBytes, 0)
                    winShortRead++
                    winShortLastFilled = filled
                    CallCounters.audioShortReadFrames.inc()
                }
                val out = encodeFrame(pcm, short)
                if (out != null) {
                    val t1 = System.nanoTime()
                    SegMeter.record(SegMeter.A_ENC, t0, t1)
                    if (baseNano == 0L) {
                        baseNano = t1
                        winNano = t1
                    } else if (paceN < paceWin.size) {
                        val d = (t1 - baseNano) / 1_000_000L - seq.toLong() * frameMs
                        paceWin[paceN] = d.coerceIn(-9999L, 9999L).toInt()
                        paceN++
                    }
                    winFrames++
                    if (!muted) listener?.onEncoded(EncodedFrame(seq, out, t1))
                    seq++
                }
                // 1 行/秒（与 EVENT_SUMMARY_MS 同窗）；没产出帧的窗口也要说话（那正是最糟的情况）
                val nowNano = System.nanoTime()
                if (baseNano != 0L && nowNano - winNano >= EVENT_SUMMARY_MS * 1_000_000L) {
                    val ideal = (EVENT_SUMMARY_MS / frameMs).toInt()
                    val pace = if (paceN == 0) {
                        "-"
                    } else {
                        java.util.Arrays.sort(paceWin, 0, paceN)
                        val p50 = paceWin[paceN shr 1]
                        val p95 = paceWin[minOf(paceN - 1, paceN * 95 / 100)]
                        "$p50/${p95}/${paceWin[paceN - 1]}"
                    }
                    Log.w(
                        TAG, "采集节拍 aPace p50/p95/max=${pace}ms（1s 窗口）：帧数 $winFrames/理想 $ideal" +
                            "，短读 $winShortRead 次" +
                            if (winShortRead > 0) "（最后 filled=${winShortLastFilled}B）" else ""
                    )
                    winNano = nowNano
                    winFrames = 0
                    paceN = 0
                    winShortRead = 0
                    winShortLastFilled = 0
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "录音循环异常", t)
        }
    }

    private fun encodeFrame(pcm: ByteArray, shortBuf: ShortArray): ByteArray? {
        val enc = encoder ?: return null
        return try {
            // 每个 20ms 子帧编码后用 2B 长度前缀分隔，接收端据此精确切分逐帧解码
            val out = java.io.ByteArrayOutputStream(2048)
            for (i in 0 until opusSubframes) {
                val base = i * OPUS_FRAME_SAMPLES
                for (j in 0 until OPUS_FRAME_SAMPLES) {
                    val lo = pcm[base * 2 + j * 2].toInt() and 0xFF
                    val hi = pcm[base * 2 + j * 2 + 1].toInt() and 0xFF
                    var s = (lo or (hi shl 8)).toShort().toInt()
                    if (s >= 0x8000) s -= 0x10000
                    shortBuf[j] = s.toShort()
                }
                val outBuf = ByteArray(1275)
                val n = enc.encode(shortBuf, 0, OPUS_FRAME_SAMPLES, outBuf, 0, outBuf.size)
                if (n <= 0) throw IllegalStateException("Opus 编码返回 0 字节")
                if (n > 0xFFFF) throw IllegalStateException("Opus 子帧过长")
                out.write((n ushr 8) and 0xFF)
                out.write(n and 0xFF)
                out.write(outBuf, 0, n)
            }
            out.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Opus 编码失败", t)
            null
        }
    }

    @Synchronized
    fun stopRecord() {
        if (!recording.get()) return
        recording.set(false)
        recordRunning.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        encoder = null
        Log.i(TAG, "停止录音")
    }

    // ==================== 接收（解码 + 播放） ====================

    /**
     * 收到一帧对端音频，送入播放队列（抖动缓冲按 seq 排序对时）。
     *
     * @param ts 发送端**通话相对**时间戳（对端起呼=0 的 ms）；与本端墙钟不同零点
     */
    fun onAudioReceived(seq: Int, ts: Long, data: ByteArray, len: Int) {
        val t0 = System.nanoTime()
        if (len <= 0) return
        // 闸门：播放未启动或播放线程已死 → 直接丢帧，防止 playQueue 只进不出
        if (track == null || !playRunning.get()) {
            // 任务单 1.6：最多 1 行/s，但每行**必须带上被节流掉的条数**（不许只留最后一条）。
            // 计数放在接收侧而不是播放汇总里：播放线程已死时没人打汇总，这条得自己会说话。
            notReadyDropped.incrementAndGet()
            val now = System.currentTimeMillis()
            val last = lastDropLogAt.get()
            if (now - last >= EVENT_SUMMARY_MS && lastDropLogAt.compareAndSet(last, now)) {
                Log.w(TAG, "播放未就绪/播放线程已死，丢弃音频帧 seq=$seq" +
                        "（本秒累计丢弃 ${notReadyDropped.getAndSet(0)} 帧）")
            }
            return
        }
        receivedCount.incrementAndGet()
        // 已播放过的迟到帧直接丢（积压追赶后补发的旧帧不该再进队列）
        // played 一次读快照：playedSeq 由播放线程推进，连读两次会让行里的「已播到」与判据不同步
        val played = playedSeq
        if (played >= 0 && seq <= played) {
            // 任务单 T6（10-03 取证）：这条闸门原先**静默**——无计数、无日志，而下面的 A_IN
            // 记录点又在它之后，被丢的帧连 aIn 都不计 ⇒ 音频塌成 1–3 帧/s 时，日志里只剩
            // 「缺帧补静音」一个症状，判不出是链路没送到还是本端已把时间轴推过头。
            // 现在补计数 + 1 行/秒的日志（行内带被节流掉的条数与本窗口最大落后帧数）。
            CallCounters.audioDroppedByLateSeq.inc()
            lateSeqDropped.incrementAndGet()
            val lag = played - seq
            if (lag > lateSeqMaxLag.get()) lateSeqMaxLag.set(lag)
            val now = System.currentTimeMillis()
            val last = lastLateSeqLogAt.get()
            if (now - last >= EVENT_SUMMARY_MS && lastLateSeqLogAt.compareAndSet(last, now)) {
                Log.w(TAG, "迟到帧丢弃：seq=$seq 已播到=$played（落后 $lag 帧）" +
                        "（本秒累计丢弃 ${lateSeqDropped.getAndSet(0)} 帧，" +
                        "本窗口最大落后 ${lateSeqMaxLag.getAndSet(0)} 帧）")
            }
            return
        }
        val copy = data.copyOf(len)
        val rxNano = System.nanoTime()
        playQueue[seq] = Inbound(copy, ts, rxNano)
        SegMeter.record(SegMeter.A_IN, t0, rxNano)
    }

    @Synchronized
    fun startPlay(): Boolean {
        if (track != null) return true
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = buildTrack(minBuf)
            // 提前绑定 track：后续步骤（解码器构造/建线程）抛异常时，catch 可经它释放 AudioTrack
            track = t
            decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
            playRunning.set(true)
            playedSeq = -1
            lastPlayedTsMs = -1L
            // 新轨道的 headPosition 从 0 起算 ⇒ 累计量必须同时归零（否则 backlogWireMs 偏高）
            writtenSamplesTotal = 0L
            resetPlayEvents()
            // 媒体域线程 pp-av-audio（任务单 2.2 / 方案 D2）：音频解码 + 写播放设备。
            // write 在设备缓冲满时必然阻塞 ⇒ 不能并进媒体主循环 pp-av-loop，这是 D2 单列它的原因。
            // daemon；退出条件 = playRunning 置 false，且 stopPlay 里的 stop/release 让阻塞中的
            // write 立即返回。无 join：线程退出后引用留在字段里，下一次 startPlay 覆盖它。
            playThread = Thread({ playLoop(t) }, "pp-av-audio").apply { isDaemon = true }.also { it.start() }
            Log.i(TAG, "开始播放（帧长 ${frameMs}ms，缓冲 ${maxOf(minBuf, frameBytes * 2)}B，严格按 seq 顺序播放）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动播放失败", t)
            // 启动失败：释放已建的 AudioTrack / OpusDecoder，复位标志，保证可重试
            playRunning.set(false)
            runCatching { track?.release() }
            track = null
            decoder = null
            playedSeq = -1
            lastPlayedTsMs = -1L
            false
        }
    }

    /**
     * 播放输出通道：true = **媒体**（USAGE_MEDIA，媒体音量、音量大、系统自动跟随
     * 有线/蓝牙耳机）；false = 通话（USAGE_VOICE_COMMUNICATION，通话音量）。
     * 默认媒体（2026-09-16 用户定调），每次通话开始由 CallManager 重置。
     */
    @Volatile
    var mediaOutput: Boolean = true
        private set

    /**
     * 切换播放通道（媒体 ⇄ 通话）。AudioTrack 的 usage 建轨时定死，切换需短暂重启
     * 播放管线（stopPlay→startPlay，队列清空会丢掉积压帧——切换瞬间本来就静音，无感）。
     * 只影响本端播放，不影响对端；未在播放时只记状态，下次建轨生效。
     */
    @Synchronized
    fun setMediaOutput(on: Boolean) {
        if (mediaOutput == on) return
        mediaOutput = on
        if (track != null) {
            stopPlay()
            startPlay()
            Log.i(TAG, "播放通道已切换 → ${if (on) "媒体" else "通话"}")
        }
    }

    /**
     * 建 AudioTrack：同 AudioRecord —— 优先**低延迟性能模式**（API 26+ 走 fast track），
     * 回放缓冲只要 2 帧（20ms 帧 → 40ms，旧实现是 4 帧 = 800ms，那是通话延迟的最大来源）。
     */
    private fun buildTrack(minBuf: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (mediaOutput) AudioAttributes.USAGE_MEDIA
                else AudioAttributes.USAGE_VOICE_COMMUNICATION
            )
            .setContentType(
                if (mediaOutput) AudioAttributes.CONTENT_TYPE_MUSIC
                else AudioAttributes.CONTENT_TYPE_SPEECH
            )
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val buf = maxOf(minBuf, frameBytes * 2)
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(buf)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "低延迟 AudioTrack 创建失败，回落普通模式：${e.message}")
            @Suppress("DEPRECATION")
            AudioTrack(
                if (mediaOutput) android.media.AudioManager.STREAM_MUSIC
                else android.media.AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun playLoop(t: AudioTrack) {
        val short = ShortArray(frameSamples)
        var missSince = 0L
        try {
            t.play()
            while (playRunning.get()) {
                // 事件日志 1 行/秒汇总（任务单 1.6）：本轮只加计数，这里攒够了才打一行
                flushPlayEventSummary(false)
                // 积压超限快速追赶（见 BACKLOG_MAX_MS 注释）：跳到最新帧，旧帧按缺帧丢弃
                val maxBacklog = (BACKLOG_MAX_MS / frameMs).coerceAtLeast(2)
                if (playQueue.size > maxBacklog) {
                    val newest = playQueue.lastKey()
                    val jumpTo = newest - (BACKLOG_KEEP_MS / frameMs).coerceAtLeast(1)
                    if (jumpTo > playedSeq) {
                        val dropped = playQueue.headMap(jumpTo, true).size
                        playQueue.headMap(jumpTo, true).clear()
                        missedCount.addAndGet(dropped)
                        playedSeq = jumpTo
                        missSince = 0L
                        // 原来这里逐次 Log.w（带 seq 与丢帧数）⇒ 改成计数，由上面的汇总行带出
                        // "快进 N 次（跳过 M 帧）"（与 PC 同口径）
                        evBacklogJump++
                        evBacklogDropped += dropped
                    }
                }
                // ---- 10-06 水位下界：总水位（队列 + 线内）太高 ⇒ 本格不取帧，等设备自己消化 ----
                // 治的就是本机方向的那个自锁：AudioTrack.write 不阻塞时 playedSeq 跑在播出点前面，
                // 后续帧一到就被"迟到闸门"判死 ⇒ 永远补静音。线内不可测（-1）时只按队列算：
                // 宁可少 hold 一拍，也不把不可测当 0 少算。
                val depth = playQueue.size
                val wireMsNow = backlogWireMs
                val totalMs = depth.toLong() * frameMs + if (wireMsNow < 0L) 0L else wireMsNow
                if (depth > 0 && totalMs > totalMaxMs()) {
                    if (holdTicks < PLAY_HOLD_MAX_TICKS) {
                        holdTicks++
                        Thread.sleep(POLL_MS)
                        continue
                    }
                    evHoldCap++   // 连续 hold 到上限 ⇒ 设备没在消费或口径失真，放行取帧（保命）
                }
                // ---- 10-06 水位上界：队列深过目标水位 ⇒ 每格丢最旧一帧，渐进收敛 ----
                // （上面 600ms 悬崖的兜底保留；正常情况下它已经够不着了。）
                if (depth > targetFrames()) {
                    val oldest = playQueue.firstKey()
                    if (oldest <= playedSeq + 1) {
                        playQueue.remove(oldest)
                        playedSeq = oldest
                        missedCount.incrementAndGet()
                        CallCounters.audioTrimmedByBacklog.inc()
                        evTrim++
                        missSince = 0L
                        continue
                    }
                    // oldest > playedSeq+1 ⇒ 下一格本来就缺，交给下面的缺帧分支（不等就直接补静音，
                    // 推进 playedSeq 同样把水位降下来，不用碰真实帧）
                }
                // 取 seq == playedSeq+1 的下一帧（严格按序播放）
                val next = playedSeq + 1
                val dqNano = System.nanoTime()
                val slot = playQueue.remove(next)
                if (slot != null) {
                    SegMeter.record(SegMeter.A_BUF, slot.rxNano, dqNano)
                    decodeAndWrite(t, slot.opus, short)
                    SegMeter.record(SegMeter.A_OUT, dqNano, System.nanoTime())
                    // 播出点读取发送端 ts（通话相对）；A/V 对时与分段延迟计量用（任务单 1.2）
                    lastPlayedTsMs = slot.ts
                    playedSeq = next
                    missSince = 0L
                    holdTicks = 0
                    // 清理积压过久的过期帧
                    while (playQueue.isNotEmpty() && playQueue.firstKey() <= playedSeq) {
                        playQueue.pollFirstEntry()
                    }
                } else if (playedSeq < 0) {
                    // 还没拿到任何帧：等一帧（毫秒级轮询，不预缓冲 → 不引入额外延迟）
                    Thread.sleep(POLL_MS)
                } else {
                    // 缺帧：轻量抖动容忍 —— 一帧最多等 [FRAME_WAIT_MS]，超时才补静音跳过。
                    // 20ms 帧下必须这么写：不等就会「晚到几毫秒即丢弃」，通话断续。
                    // 10-06：**只有队列还在水位之下才等**。等满 30ms 再写静音 = 50ms 墙钟只推进
                    // 一帧时间轴 ⇒ 每个缺帧净给队列加 1.5 帧；队列已经深过水位时这 30ms 是纯亏，
                    // 后面的帧就在手里，直接补静音反而把水位收回来。
                    val now = System.currentTimeMillis()
                    if (missSince == 0L) missSince = now
                    if (playQueue.isEmpty() || (depth <= targetFrames() && now - missSince < FRAME_WAIT_MS)) {
                        // 缓冲全空（对端暂停/断流）→ 等；水位之下且在容忍窗口内 → 等等迟到的这一帧
                        Thread.sleep(POLL_MS)
                    } else {
                        // 跳过缺失帧，补静音保持时间轴对齐
                        writeSilence(t, short)
                        playedSeq = next
                        missSince = 0L
                        missedCount.incrementAndGet()
                        // 原来这里逐次 Log.w（20ms 帧下最高 ~33 行/s）⇒ 只加计数，
                        // 由 flushPlayEventSummary 每秒带出次数（任务单 1.6）
                        evSilenceFill++
                    }
                }
            }
            // 正常收线（挂断）：把不足 1s 的尾巴打成最后一行，别静默吞掉
            flushPlayEventSummary(true)
        } catch (t: Throwable) {
            Log.e(TAG, "播放循环异常", t)
            flushPlayEventSummary(true)
            // 播放线程死亡：复位运行标志与轨道引用（假活自愈），onAudioReceived 闸门随即开始丢帧；
            // 清队列+复位 playedSeq：防旧高 seq 残帧在下次通话触发积压快进、把新帧全跳掉。
            // 注意：catch 形参 t 遮蔽了 playLoop 形参 t: AudioTrack，这里经 track 字段释放（同对象）
            playRunning.set(false)
            runCatching { track?.release() }
            track = null
            decoder = null
            playQueue.clear()
            playedSeq = -1
            lastPlayedTsMs = -1L
            writtenSamplesTotal = 0L
        }
    }

    /** 新一场播放：事件计数与节流窗口归零（防上一场的尾巴混进这一场的汇总行） */
    private fun resetPlayEvents() {
        evSilenceFill = 0
        evBacklogJump = 0
        evBacklogDropped = 0
        evTrim = 0
        evHoldCap = 0
        holdTicks = 0
        evSummaryAt = 0L
        notReadyDropped.set(0)
        lastDropLogAt.set(0L)
        lateSeqDropped.set(0)
        lateSeqMaxLag.set(0)
        lastLateSeqLogAt.set(0L)
    }

    /**
     * 播放事件汇总（任务单 1.6）：窗口 [EVENT_SUMMARY_MS] 内最多一行，行里**带上被节流掉的
     * 次数**（不许只留最后一条）；这一窗口没事件就不打。
     *
     * @param force 播放线程退出前调 true —— 不足一个窗口的尾巴也要打出来，不静默吞
     */
    private fun flushPlayEventSummary(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - evSummaryAt < EVENT_SUMMARY_MS) return
        evSummaryAt = now
        if (evSilenceFill == 0 && evBacklogJump == 0 && evTrim == 0 && evHoldCap == 0) return
        Log.w(
            TAG, "音频播放事件汇总（${EVENT_SUMMARY_MS}ms 窗口）：缺帧补静音 $evSilenceFill 次，" +
                "积压快进 $evBacklogJump 次（跳过 $evBacklogDropped 帧）" +
                "，水位裁帧 $evTrim 帧，保命放行 $evHoldCap 次" +
                "，当前队列 ${playQueue.size} 帧/${backlogQueueMs}ms 线内 ${backlogWireMs}ms"
        )
        evSilenceFill = 0
        evBacklogJump = 0
        evBacklogDropped = 0
        evTrim = 0
        evHoldCap = 0
    }

    private fun decodeAndWrite(t: AudioTrack, opus: ByteArray, short: ShortArray) {
        val dec = decoder ?: return
        try {
            var written = 0
            var off = 0
            // 按 2B 长度前缀逐个子帧解码（编码端同格式）
            while (off + 2 <= opus.size && written < frameSamples) {
                val flen = ((opus[off].toInt() and 0xFF) shl 8) or (opus[off + 1].toInt() and 0xFF)
                off += 2
                if (flen <= 0 || off + flen > opus.size) break
                val outBuf = ShortArray(OPUS_FRAME_SAMPLES)
                val n = dec.decode(opus, off, flen, outBuf, 0, OPUS_FRAME_SAMPLES, false)
                val copy = minOf(n, frameSamples - written)
                System.arraycopy(outBuf, 0, short, written, copy)
                written += copy
                off += flen
            }
            if (written < frameSamples) {
                java.util.Arrays.fill(short, written, frameSamples, 0)
            }
            val pcm = shortArrayToBytes(short, frameSamples)
            writeTrack(t, pcm)
        } catch (e: Throwable) {
            Log.e(TAG, "解码播放失败", e)
            writeSilence(t, short)
        }
    }

    private fun writeSilence(t: AudioTrack, short: ShortArray) {
        java.util.Arrays.fill(short, 0)
        // 与改造前一致：补静音路径不抛异常（它本来就常在轨道已失效时被调用）
        runCatching { writeTrack(t, shortArrayToBytes(short, frameSamples)) }
    }

    /**
     * 写播放轨道并记"已 committed 的样本数"（[backlogWireMs] 的被减数）。
     *
     * ⚠ **先记账再 write**：write() 会阻塞到线里有空间，这期间这一帧既已出队（不在了
     * [playQueue]）、又没写进计数 ⇒ 若按 write 返回值累加，积压会**悄悄少算最多 1 帧**
     * （20ms 帧 = 20ms）。先加整帧是"宁可多算不漏算"。
     * 异常照旧往上抛：由 decodeAndWrite 的 catch 记日志并退回补静音（与改造前同行为）。
     */
    private fun writeTrack(t: AudioTrack, pcm: ByteArray) {
        writtenSamplesTotal += pcm.size / 2
        t.write(pcm, 0, pcm.size)
    }

    private fun shortArrayToBytes(s: ShortArray, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = s[i].toInt()
            b[i * 2] = (v and 0xFF).toByte()
            b[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return b
    }

    @Synchronized
    fun stopPlay() {
        if (track == null) return
        playRunning.set(false)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        decoder = null
        playQueue.clear()
        playedSeq = -1
        lastPlayedTsMs = -1L
        writtenSamplesTotal = 0L
        Log.i(TAG, "停止播放")
    }

    fun stopAll() {
        stopRecord()
        stopPlay()
    }
}
