package com.phonepctransfer.voice

import android.util.Log

/**
 * **纯语音通话**的 Opus 码率自适应（64k ⇄ 32k，2026-09-15 用户要求）。
 *
 * 与视频侧 `QualityController` 同构（降档激进、升档保守），但只有两档：
 *
 * ```
 * 64k（默认，[VoiceEngine.BITRATE_VOICE]）
 *   ↓ 拥塞连续 2 次采样（≈4s），且距上次降档 ≥3s
 * 32k（[VoiceEngine.BITRATE_VOICE_LO]）
 *   ↓ 健康窗口连续 15s，且距上次升/降档分别 ≥30s / ≥60s
 * 64k
 * ```
 *
 * ### 判据（语音帧只有几百字节，链路很差时才可能拥塞）
 *  1. **发送阻塞**：写 socket 的耗时 2s 均值 >120ms（TCP 发送缓冲被占满 = 链路拥塞）；
 *  2. **播放侧积压（毫秒口径，任务单 4）**：队列 + 线内未播 > max(160, 2×帧长) ms。
 *     **原来这里是「≥2 帧」**——200ms 帧时代 2 帧 = 400ms 的门槛，20ms 帧下变成 40ms，
 *     比接收端的水位目标（[VoiceEngine.PLAY_TARGET_MS]=80ms）还低，稳态就会持续误判拥塞、
 *     接通 4s 即钉在 32k（10-05 PC↔PC 实测）。阈值改成毫秒并按帧长放大，
 *     旧版 200ms 帧对端仍是 400ms 口径，不会因为换帧长而反向变松；
 *  3. **缺帧率**：播放端被迫补静音跳过/水位裁帧的比例 >15%。
 *
 * 三者任一成立即判拥塞；只有三者全部「宽松达标」才算健康。
 *
 * ⚠ 只在**纯语音通话**启用：视频通话的音频码率跟随视频档位（L0 32k / L1~L3 64k），
 * 两套自适应同时改同一路 Opus 编码器会互相打架。
 */
class VoiceBitrateController(
    /** 码率变化回调（在采样线程执行；Opus 改码率即刻生效，对解码端完全透明） */
    private val onChange: (Int) -> Unit
) {
    companion object {
        private const val TAG = "PP-VoiceQ"

        /** 高/低档（bps） */
        const val HI: Int = VoiceEngine.BITRATE_VOICE
        const val LO: Int = VoiceEngine.BITRATE_VOICE_LO

        /** 采样周期（与视频侧一致：2s 一次） */
        const val EVAL_INTERVAL_MS = 2_000L

        /** 拥塞 / 健康阈值 */
        const val CONGESTED_WRITE_MS = 120L
        const val HEALTHY_WRITE_MS = 30L

        /**
         * 积压毫秒阈值基准（PC VoiceBitrateController 同名同值）：
         * 20ms 帧取基准值，长帧对端按 2×/1×帧长放大 ⇒ `max(基准, 系数×帧长)`。
         */
        const val CONGESTED_BACKLOG_MS = 160L
        const val HEALTHY_BACKLOG_MS = 100L
        const val MISS_DOWN_PCT = 15
        const val MISS_OK_PCT = 3

        /** 降档：连续几次拥塞采样；最小间隔 */
        const val DOWN_STREAK = 2
        const val DOWN_MIN_GAP_MS = 3_000L

        /** 升档闸门 */
        const val UP_MIN_GAP_MS = 30_000L
        const val UP_AFTER_DOWN_MS = 60_000L
        const val UP_HEALTHY_MS = 15_000L
    }

    /** 当前码率（初始恒 HI） */
    @Volatile
    var current: Int = HI
        private set

    /** 最近一次采样的平均写入耗时（日志/UI 用；-1 = 窗口内没有发送） */
    @Volatile
    var lastAvgWriteMs: Long = 0L
        private set

    private var lastDownAt = 0L
    private var lastUpAt = 0L
    private var healthySince = 0L
    private var downStreak = 0

    private var winSumMs = 0L
    private var winCount = 0

    @Synchronized
    fun reset() {
        current = HI
        lastDownAt = 0L
        lastUpAt = 0L
        healthySince = 0L
        downStreak = 0
        winSumMs = 0L
        winCount = 0
        lastAvgWriteMs = 0L
    }

    /** 每发出一帧音频后调用（writeMs = 写 socket 耗时；<0 表示没发出去） */
    @Synchronized
    fun onFrameSent(writeMs: Long) {
        if (writeMs < 0) return
        winSumMs += writeMs
        winCount++
    }

    /**
     * 每 [EVAL_INTERVAL_MS] 采样一次（由通话层的采样线程调用）。
     *
     * @param backlogMs   播放侧积压总口径（队列 + 线内未播，毫秒；线内不可测时只含队列）
     * @param frameMs     本通音频帧长，用于把积压阈值按帧长放大（兼容 200ms 帧旧对端）
     * @param missedDelta 本窗口内缺帧（补静音跳过/水位裁帧）增量
     * @param framesDelta 本窗口内正常收到的帧数增量
     */
    @Synchronized
    fun evaluate(
        backlogMs: Long,
        frameMs: Int,
        missedDelta: Int,
        framesDelta: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val avg = if (winCount > 0) winSumMs / winCount else -1L
        lastAvgWriteMs = avg
        winSumMs = 0L
        winCount = 0

        val total = missedDelta + framesDelta
        val missPct = if (total <= 0) 0 else missedDelta * 100 / total

        val congestedBacklog = maxOf(CONGESTED_BACKLOG_MS, 2L * frameMs)
        val healthyBacklog = maxOf(HEALTHY_BACKLOG_MS, frameMs.toLong())

        val congested = (avg >= 0 && avg > CONGESTED_WRITE_MS) ||
            backlogMs > congestedBacklog ||
            missPct > MISS_DOWN_PCT
        val healthy = (avg in 0..HEALTHY_WRITE_MS) &&
            backlogMs <= healthyBacklog &&
            missPct <= MISS_OK_PCT

        if (congested) {
            downStreak++
            healthySince = 0L
            if (downStreak >= DOWN_STREAK && current == HI &&
                nowMs - lastDownAt >= DOWN_MIN_GAP_MS
            ) {
                downStreak = 0
                lastDownAt = nowMs
                current = LO
                Log.i(
                    TAG,
                    "语音降码率 → ${LO / 1000}k（avgWrite=${avg}ms 积压=${backlogMs}ms/阈值${congestedBacklog}ms 缺帧=${missPct}%）"
                )
                onChange(LO)
            }
            return
        }

        downStreak = 0
        if (healthy) {
            if (healthySince == 0L) healthySince = nowMs
        } else {
            healthySince = 0L
        }

        if (current != LO) return
        if (nowMs - lastUpAt < UP_MIN_GAP_MS) return
        if (lastDownAt > 0 && nowMs - lastDownAt < UP_AFTER_DOWN_MS) return
        if (healthySince == 0L || nowMs - healthySince < UP_HEALTHY_MS) return

        lastUpAt = nowMs
        healthySince = 0L
        current = HI
        Log.i(
            TAG,
            "语音升码率 → ${HI / 1000}k（健康窗口 ${UP_HEALTHY_MS / 1000}s，avgWrite=${avg}ms 积压=${backlogMs}ms）"
        )
        onChange(HI)
    }
}
