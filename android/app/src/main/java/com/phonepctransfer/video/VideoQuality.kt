package com.phonepctransfer.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * 视频档位表 + 升/降档状态机 + 编解码器选择（计划 §3.5 / §4.3 / §9）。
 *
 * **2026-09-15 起（用户要求）**：三档 = 流畅 480P / 标清 720P / 高清 1080P，
 * 默认 1080P（2 Mbps）。档位**同时决定编码分辨率**（真分辨率切换，方案 A）：
 * 降档时 1080p → 720p → 480p 逐级降，升档同理逐级升。
 *
 * **2026-10-03 加第四档（用户点）**：超清 = **1080P / 5 Mbps** —— 分辨率与高清档**完全相同**，
 * 只有码率差 ⇒ 选它不引入任何新的采集/编码尺寸（`pickPreviewSize` 那条 HAL 尺寸坑与本档无关），
 * 也不改变换档代价（只换码率 = `setParameters()` 零中断）。
 * 用户同时定的两条语义（本文件的 `DEFAULT`/`MAX` 与 `CallManager` 的起始档都按这个走）：
 *  ① **选定的档位就是上限**：自动升降档不许越过本次用户选的那一档（`QualityController.maxLevel`
 *     = 选定档，`current.idx >= maxLevel.idx` 即不再升 —— 这条本来就成立，本档只是把它延伸到 L3）；
 *  ② **起始码率仍从推荐档（高清 2M）起**：选「超清」也不一上来就 5M，要等 `QualityController`
 *     的五重升档闸门（健康窗口 [QualityController.UP_HEALTHY_MS]、距上次升档
 *     [QualityController.UP_MIN_GAP_MS]、降档冷静期 [QualityController.UP_AFTER_DOWN_MS]、
 *     本窗无本地丢帧、对端实收 >0k）全满足，才逐级升到选定档。
 *
 * 切档代价与做法（[com.phonepctransfer.video.VideoCall.applyLevel]）：
 *  - 只换码率 → `setParameters()`，零中断；
 *  - 分辨率变了 → 重建编码器 + `capture.updateTargets()`（画面中断 ~200–400ms），
 *    并重发 `video_cfg` 让对端按新尺寸重建解码器。
 */
enum class VideoLevel(
    val idx: Int,
    /** 档名（用户定调：「流畅 / 标清 / 高清」） */
    val title: String,
    /** 分辨率短名（日志、状态条用） */
    val label: String,
    /** 编码分辨率（真分辨率切换，见类注释） */
    val width: Int,
    val height: Int,
    val fps: Int,
    /** 视频码率（kbps） */
    val videoKbps: Int,
    /** 随档位的音频 Opus 码率（bps） */
    val audioBitrate: Int
) {
    // ⚠ 480P 曾用 854x480（FWVGA）：a12/a16 两台机的相机 StreamConfigurationMap 都没有
    // 这个尺寸（最接近 720x480/864x480），且 854 非 16 对齐常被硬编码器拒 → 480P 档
    // 无视频输出、预览空白（2026-09-16 a12 实锤）。640x480 = VGA，是 Camera2 强制的
    // 预览尺寸、所有硬编都支持，作为「流畅」档的编码分辨率。渲染侧对任意宽高比
    // 都会等比留边/裁剪（videoFitMatrix），4:3 没有问题。
    L0(0, "流畅", "480P", 640, 480, 30, 500, 32_000),
    L1(1, "标清", "720P", 1280, 720, 30, 1500, 64_000),
    L2(2, "高清", "1080P", 1920, 1080, 30, 2000, 64_000),
    // 1080P / 5 Mbps（2026-10-03 用户点）：分辨率与 L2 逐字节相同 ⇒ 升/降这一档只动码率，
    // 走 `setParameters()` 零中断那条路（不重建编码器、不重发分辨率、对端解码器不动）。
    // ⚠ 两个由 5M 带出来的事实，写在这里免得下一轮当新 bug 查：
    //   ① CBR/GOP=1s 下 IDR ≈ 2M 档实测最大值 143,590B 的 ~2.5 倍 ⇒ **带内档（第 5 档）的
    //      128KB 单帧闸门（`Protocol.MAX_VIDEO_FRAME`）会更常整帧丢关键帧**，UDP/媒体 TCP 档
    //      走切片重组（1188B/片）不受此限；
    //   ② 250KB 级 IDR ≈ 210 片，仍在发端 `VIDEO_SLICES_PER_TICK=256` 的一个 tick 预算内，
    //      但余量从"一倍"缩到"两成" ⇒ 若真机又见"缺片超时"，先看这一档，别再动 40ms 窗。
    L3(3, "超清", "1080P", 1920, 1080, 30, 5000, 64_000);

    val videoBitrate: Int get() = videoKbps * 1000

    /** UI 选项文案：「高清 1080P」 */
    val titleLabel: String get() = "$title $label"

    /** 选档框文案：推荐档（= 起始档基准）多挂一个「（推荐）」，不新增任何数字 */
    val pickLabel: String get() = if (this == DEFAULT) "$titleLabel（推荐）" else titleLabel

    companion object {
        /**
         * 起始档基准 = 1080P / 2 Mbps（用户定调，选档框里带「（推荐）」）。
         * ⚠ 它同时是"即便用户选了超清，也先按这一档起步"的那个上限（见 [MAX] 与本文件头部②）。
         */
        val DEFAULT = L2

        /** 最高档（UI/校验用；= 用户可选的上限，自动升降档不得越过本次选定档） */
        val MAX = L3

        /** 档位索引 → 档位（越界收敛到最近档，永不抛异常：索引来自对端 JSON） */
        fun of(idx: Int): VideoLevel =
            values().firstOrNull { it.idx == idx } ?: if (idx <= 0) L0 else MAX

        fun encodeWidth(active: VideoLevel): Int = active.width
        fun encodeHeight(active: VideoLevel): Int = active.height
    }
}

/**
 * 编解码器选择（两端能力协商的落点）。
 *
 * 前提（已实测，见计划 §15.1）：两台测试机都有 HEVC 硬件编码器；
 * a16 还有 `*.hevc.decoder.low_latency`。
 */
object VideoCodecs {
    private const val TAG = "PP-VideoCodec"

    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"

    fun name(mime: String): String = if (mime == MIME_HEVC) "hevc" else "h264"

    fun mime(name: String): String = if (name.equals("h264", true) || name.equals("avc", true)) MIME_AVC else MIME_HEVC

    /** 本端**可解码**的编码列表（握手时广播，对端据此选编码器）。 */
    val decodable: List<String> by lazy {
        val out = LinkedHashSet<String>()
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (t in info.supportedTypes) {
                    when (t) {
                        MIME_HEVC -> out.add("hevc")
                        MIME_AVC -> out.add("h264")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "枚举解码器失败: ${it.message}") }
        if (out.isEmpty()) {
            // 枚举失败时假定都能解（最坏情况：对端发 HEVC 而本端解不了，会走 onError 提示）
            Log.w(TAG, "未能枚举到解码器，按 hevc/h264 都能解处理")
            out.add("hevc"); out.add("h264")
        }
        Log.i(TAG, "本端可解码: $out")
        out.toList()
    }

    /** 找一个硬件加速的编码器名（返回 null = 无硬编，可用的软件编码器不实时，不用） */
    fun findHardwareEncoder(mime: String): String? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var fallback: String? = null
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(mime, true) }) continue
                // isHardwareAccelerated() 需 API 29+；minSdk 28 → 低版本按「非软编」近似
                val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                if (hw) {
                    // 优先带 low_latency 后缀的（实时通话首选）
                    if (info.name.contains("low_latency", true)) return info.name
                    if (fallback == null) fallback = info.name
                }
            }
            fallback
        } catch (t: Throwable) {
            Log.w(TAG, "查找硬编码器失败($mime): ${t.message}")
            null
        }
    }

    /** 找一个解码器名：优先硬件 + low_latency */
    fun findDecoder(mime: String): String? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var hw: String? = null
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(mime, true) }) continue
                val accelerated = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                if (accelerated && info.name.contains("low_latency", true)) return info.name
                if (accelerated && hw == null) hw = info.name
            }
            hw
        } catch (t: Throwable) {
            Log.w(TAG, "查找硬解码器失败($mime): ${t.message}")
            null
        }
    }

    /** 该编码是否被系统支持为编码器（用于「本端能否发送视频」） */
    fun hasHardwareEncoder(mime: String): Boolean = findHardwareEncoder(mime) != null

    /**
     * 按对端能力选编码 MIME（R4 的回落链）。
     *
     * ```
     * 1) 对端可解 hevc 且本端有 HEVC 硬编 → HEVC 硬编（优先）
     * 2) 对端可解 h264 且本端有 H.264 硬编 → H.264 硬编（回落）
     * 3) 都不满足 → null（禁用视频，记 warning）
     * ```
     */
    fun pickEncoderMime(peerVcodec: List<String>): String? {
        val peerHevc = peerVcodec.isEmpty() || peerVcodec.any { it.equals("hevc", true) }
        val peerAvc = peerVcodec.isEmpty() || peerVcodec.any { it.equals("h264", true) || it.equals("avc", true) }
        if (peerHevc && hasHardwareEncoder(MIME_HEVC)) return MIME_HEVC
        if (peerAvc && hasHardwareEncoder(MIME_AVC)) return MIME_AVC
        Log.w(TAG, "无可用硬编码器或对端不支持（peerVcodec=$peerVcodec）→ 禁用视频")
        return null
    }

    /** 该编码器是否支持低延迟（KEY_LOW_LATENCY，API 30+） */
    fun supportsLowLatency(mime: String, codecName: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return runCatching {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == codecName } ?: return false
            val caps = runCatching {
                if (Build.VERSION.SDK_INT >= 29) info.getCapabilitiesForType(mime)
                else null
            }.getOrNull() ?: return false
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
    }
}

/**
 * 升/降档状态机（R7 核心）。**降档激进、升档保守**。
 *
 * 判据（发送侧采集，接收侧反馈）：
 *  1. 本端发送阻塞：每帧写入耗时 `writeMs`，2s 均值；
 *  2. 对端反馈 `video_fb{drop, lat, kbps}`（每 2s 一次）；
 *  3. 本端**本地丢帧**（一帧大得过不了单帧闸门，整帧没上线）—— 这条是问题2 的自愈入口，
 *     前两条都在量"发出去之后"，只有这条量"根本没发出去"。
 *
 * 降档（快）：2s 均值 >120ms 或 drop>8% 或 lat>400ms，**连续 2 次采样（≈4s）** → 降一档；
 *            两次降档至少间隔 3s；60s 内最多降 3 档。
 * 升档（慢）：五重闸门 —— ①距上次升档 ≥30s（「半分钟只能触发一次」）
 *            ②距上次降档 ≥60s（冷静期）③健康窗口连续 ≥15s ④未到上限
 *            ⑤对端**实收码率 > 0**（任务单 1.4：报 0k = 这一档的流根本没到，
 *              此时 drop/lat 再干净也不是健康证据，不该继续往上加档）。
 *
 * 「不激进」落到代码里是可验证的常量，而不是形容词。
 */
class QualityController(
    /** 上限（两端协商档位与用户设置取 min） */
    private val maxLevel: VideoLevel,
    /** 每次切档回调（在调用线程执行 —— 请确保调用线程不是网络读线程/主线程） */
    private val onChange: (VideoLevel) -> Unit
) {
    companion object {
        private const val TAG = "PP-VideoQ"

        /** 健康 / 拥塞 阈值 */
        const val HEALTHY_WRITE_MS = 30L
        const val CONGESTED_WRITE_MS = 120L
        const val DROP_DOWN_PCT = 8
        const val DROP_OK_PCT = 3
        const val LAT_DOWN_MS = 400
        const val LAT_OK_MS = 200

        /** 采样周期（每帧上报 writeMs，累计到该周期才评估一次） */
        const val EVAL_INTERVAL_MS = 2_000L

        /** 降档：连续几次「拥塞」采样才降 */
        const val DOWN_STREAK = 2
        /** 降档最小间隔 */
        const val DOWN_MIN_GAP_MS = 3_000L
        /** 60s 内最多降档次数 */
        const val DOWN_MAX_PER_MIN = 3

        /** 升档闸门（R7） */
        const val UP_MIN_GAP_MS = 30_000L      // 距上次升档 ≥30s —— 「半分钟只能触发一次」
        const val UP_AFTER_DOWN_MS = 60_000L   // 降档后冷静期
        const val UP_HEALTHY_MS = 15_000L      // 健康窗口
    }

    @Volatile
    var current: VideoLevel = VideoLevel.DEFAULT
        private set

    /** 最近一次对端反馈 */
    @Volatile private var fbDrop = 0
    @Volatile private var fbLat = 0

    /** 对端 2s 窗口内的实收码率 kbps（任务单 1.4：升档闸门⑤用它） */
    @Volatile private var fbKbps = 0
    @Volatile private var fbAt = 0L

    private var lastUpAt = 0L
    private var lastDownAt = 0L
    private var healthySince = 0L
    private var downStreak = 0
    private val downTimes = ArrayDeque<Long>()

    private var winStart = 0L
    private var winCount = 0
    private var winSumMs = 0L
    private var lastEvalAt = 0L

    /**
     * 本窗内**发端本地丢帧**的次数（问题2 的根：一帧大得过不了单帧闸门 ⇒ 整帧没发出去）。
     * ⚠ 与 avgWrite 的区别是这条不是"假拥塞"：avgWrite 量的是主 TCP 写锁排队（文件/音频抢锁会虚高），
     *   而这个数是**这一帧确实一个字节都没上线**。收端因此等不到关键帧 → 卡在两个 IDR 之间 → 花屏，
     *   唯一的自愈就是把每帧的数据量降下来，所以它必须进拥塞判据。
     */
    private var winLocalDrops = 0

    /** 上次评估时的 2s 平均写入耗时（供 UI/日志） */
    @Volatile
    var lastAvgWriteMs = 0L
        private set

    /** 上次评估窗内的本地丢帧数（降档日志用它说明"为什么降"） */
    @Volatile
    var lastLocalDrops = 0
        private set

    fun reset(level: VideoLevel) {
        current = level
        lastUpAt = 0L
        lastDownAt = 0L
        healthySince = 0L
        downStreak = 0
        downTimes.clear()
        winStart = 0L
        winCount = 0
        winSumMs = 0L
        winLocalDrops = 0
        lastLocalDrops = 0
        lastEvalAt = 0L
        fbDrop = 0
        fbLat = 0
        fbKbps = 0
    }

    /** 每发送一帧视频后调用（writeMs = 写入 socket 耗时；-1 表示未发送） */
    @Synchronized
    fun onFrameSent(writeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (writeMs >= 0) {
            if (winStart == 0L) winStart = nowMs
            winSumMs += writeMs
            winCount++
        }
        if (lastEvalAt == 0L) lastEvalAt = nowMs
        if (nowMs - lastEvalAt >= EVAL_INTERVAL_MS) {
            lastEvalAt = nowMs
            evaluate(nowMs)
        }
    }

    /**
     * 发端本地丢一帧（超单帧闸门 / 媒体队列硬顶）⇒ 记进当前窗，[evaluate] 里作为拥塞证据。
     * 与 [onFrameSent] 同一个评估节拍（同一 2s 窗、同样连续 [DOWN_STREAK] 次才降、同样受
     * [DOWN_MIN_GAP_MS] / [DOWN_MAX_PER_MIN] 约束）—— 这里没有新增任何数字，只多了一条判据。
     */
    @Synchronized
    fun onLocalDrop(nowMs: Long = System.currentTimeMillis()) {
        winLocalDrops++
        if (lastEvalAt == 0L) lastEvalAt = nowMs
        if (nowMs - lastEvalAt >= EVAL_INTERVAL_MS) {
            lastEvalAt = nowMs
            evaluate(nowMs)
        }
    }

    /** 收到对端 video_fb（每 2s）。[rxKbps] = 对端窗口内实收码率（任务单 1.4） */
    @Synchronized
    fun onFeedback(drop: Int, lat: Int, rxKbps: Int, nowMs: Long = System.currentTimeMillis()) {
        fbDrop = drop
        fbLat = lat
        fbKbps = rxKbps
        fbAt = nowMs
    }

    /** 对端反馈是否新鲜（>6s 没反馈视为不可信 → 不升档） */
    private fun feedbackFresh(nowMs: Long): Boolean = fbAt > 0 && nowMs - fbAt < 6_000L

    @Synchronized
    private fun evaluate(nowMs: Long) {
        val avg = if (winCount > 0) winSumMs / winCount else -1L
        lastAvgWriteMs = avg
        val localDrops = winLocalDrops
        lastLocalDrops = localDrops
        winStart = 0L; winCount = 0; winSumMs = 0L; winLocalDrops = 0

        val fresh = feedbackFresh(nowMs)
        val drop = if (fresh) fbDrop else 0
        val lat = if (fresh) fbLat else 0
        val rxKbps = if (fresh) fbKbps else 0

        val congested = (avg >= 0 && avg > CONGESTED_WRITE_MS) ||
            (fresh && drop > DROP_DOWN_PCT) || (fresh && lat > LAT_DOWN_MS) ||
            localDrops > 0
        // 健康窗口要求「对端真的收到了流」：实收 0k 时 drop/lat 再干净也不是升档证据
        // （旧口径下这种场景照样攒满 15s 健康窗口并升档 —— 任务单 1.4 的假信号）
        // ⚠ 还要加上"本窗没有本地丢帧"：超闸门丢帧说明这一档的每帧数据量已经超了通道容量，
        //   此刻写锁再空闲也不是健康，绝不能当成升档理由（否则会在超帧与升档之间来回打摆）。
        val healthy = (avg in 0..HEALTHY_WRITE_MS) && localDrops == 0 &&
            (fresh && drop <= DROP_OK_PCT && lat <= LAT_OK_MS && rxKbps > 0)

        if (congested) downStreak++ else downStreak = 0

        if (congested && downStreak >= DOWN_STREAK) {
            if (down(nowMs)) downStreak = 0
            return
        }

        // ===== 健康窗口维护 =====
        if (healthy) {
            if (healthySince == 0L) healthySince = nowMs
        } else {
            healthySince = 0L
        }

        // ===== 升档：五重闸门（R7 + 任务单 1.4 的实收码率）=====
        if (current.idx >= maxLevel.idx) return
        if (nowMs - lastUpAt < UP_MIN_GAP_MS) return
        if (lastDownAt > 0 && nowMs - lastDownAt < UP_AFTER_DOWN_MS) return
        if (healthySince == 0L || nowMs - healthySince < UP_HEALTHY_MS) return

        val sinceUp = if (lastUpAt == 0L) Long.MAX_VALUE else nowMs - lastUpAt
        lastUpAt = nowMs
        healthySince = 0L
        // 逐级升（三档后不能再用 L0→L1 的写法：1080P 档永远上不去）
        current = VideoLevel.of((current.idx + 1).coerceAtMost(maxLevel.idx))
        Log.i(
            TAG, "升档 → ${current.titleLabel}/${current.videoKbps}k" +
                "（距上次升档 ${formatGap(sinceUp)}，健康窗口 ${UP_HEALTHY_MS / 1000}s；" +
                "avgWrite=${lastAvgWriteMs}ms drop=${fbDrop}% lat=${fbLat}ms 实收=${fbKbps}k）"
        )
        onChange(current)
    }

    private fun formatGap(ms: Long): String =
        if (ms == Long.MAX_VALUE) "首次" else "${ms / 1000}s 前"

    private fun down(nowMs: Long): Boolean {
        if (current.idx <= 0) return false
        if (nowMs - lastDownAt < DOWN_MIN_GAP_MS) return false
        while (downTimes.isNotEmpty() && nowMs - downTimes.first() > 60_000L) downTimes.removeFirst()
        if (downTimes.size >= DOWN_MAX_PER_MIN) return false

        lastDownAt = nowMs
        downTimes.addLast(nowMs)
        healthySince = 0L
        // 逐级降（1080P → 720P → 480P），避免一步跌到底带来的画质断崖
        current = VideoLevel.of(current.idx - 1)
        Log.i(
            TAG, "降档 → ${current.titleLabel}/${current.videoKbps}k" +
                "（拥塞连续 $DOWN_STREAK 次采样，avgWrite=${lastAvgWriteMs}ms drop=${fbDrop}% lat=${fbLat}ms 实收=${fbKbps}k" +
                " 本地丢帧=$lastLocalDrops）"
        )
        onChange(current)
        return true
    }
}
