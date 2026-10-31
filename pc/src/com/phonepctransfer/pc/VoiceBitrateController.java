package com.phonepctransfer.pc;

/**
 * **纯语音通话**的 Opus 码率自适应（64k ⇄ 32k，2026-09-15）。
 *
 * 与 Android 端 voice/VoiceBitrate.kt 严格同构（降档激进、升档保守），只有两档：
 *
 * <pre>
 * 64k（默认，VoiceEngine.BITRATE_VOICE）
 *   ↓ 拥塞连续 2 次采样（≈4s），且距上次降档 ≥3s
 * 32k（VoiceEngine.BITRATE_VOICE_LO）
 *   ↓ 健康窗口连续 15s，且距上次升/降档分别 ≥30s / ≥60s
 * 64k
 * </pre>
 *
 * 判据（三者任一成立即判拥塞；全部宽松达标才算健康）：
 * <ol>
 *   <li>发送阻塞：写 socket 的耗时 2s 均值 &gt;120ms（TCP 发送缓冲被占满）；</li>
 *   <li>播放侧积压（毫秒口径，任务单 4）：队列 + 线内未播 &gt;max(160, 2×帧长)ms。
 *       <b>原来这里是「≥2 帧」</b>——200ms 帧时代 2 帧 = 400ms 的门槛，20ms 帧下变成 40ms，
 *       比接收端的水位目标（VoiceEngine.PLAY_TARGET_MS=80ms）还低，稳态就会持续误判拥塞、
 *       接通 4s 即钉在 32k（10-05 两次 PC↔PC 通话实测）。阈值改成毫秒并按帧长放大，
 *       旧版 200ms 帧对端仍是 400ms 口径，不会因为换帧长而反向变松；</li>
 *   <li>缺帧率：播放端被迫补静音跳过的比例 &gt;15%。</li>
 * </ol>
 *
 * ⚠ 只在纯语音通话启用：视频通话的音频码率跟随视频档位（L0 32k / L1~L3 64k）。
 */
public final class VoiceBitrateController {

    /** 高/低档（bps） */
    public static final int HI = VoiceEngine.BITRATE_VOICE;
    public static final int LO = VoiceEngine.BITRATE_VOICE_LO;

    /** 采样周期（与视频侧一致：2s 一次） */
    public static final long EVAL_INTERVAL_MS = 2000L;

    /** 拥塞 / 健康阈值 */
    public static final long CONGESTED_WRITE_MS = 120L;
    public static final long HEALTHY_WRITE_MS = 30L;
    /** 积压毫秒下限的基准值；20ms 帧取基准，长帧对端按 2×/1×帧长放大（见类注释判据 2） */
    public static final long CONGESTED_BACKLOG_MS = 160L;
    public static final long HEALTHY_BACKLOG_MS = 100L;
    public static final int MISS_DOWN_PCT = 15;
    public static final int MISS_OK_PCT = 3;

    /** 降档：连续几次拥塞采样；最小间隔 */
    public static final int DOWN_STREAK = 2;
    public static final long DOWN_MIN_GAP_MS = 3000L;

    /** 升档闸门 */
    public static final long UP_MIN_GAP_MS = 30_000L;
    public static final long UP_AFTER_DOWN_MS = 60_000L;
    public static final long UP_HEALTHY_MS = 15_000L;

    public interface OnChange {
        void onChange(int bitrate);
    }

    private final OnChange onChange;

    private volatile int current = HI;
    private volatile long lastAvgWriteMs = 0L;

    private long lastDownAt = 0L;
    private long lastUpAt = 0L;
    private long healthySince = 0L;
    private int downStreak = 0;

    private long winSumMs = 0L;
    private int winCount = 0;

    public VoiceBitrateController(OnChange onChange) {
        this.onChange = onChange;
    }

    public int current() {
        return current;
    }

    public long lastAvgWriteMs() {
        return lastAvgWriteMs;
    }

    public synchronized void reset() {
        current = HI;
        lastDownAt = 0L;
        lastUpAt = 0L;
        healthySince = 0L;
        downStreak = 0;
        winSumMs = 0L;
        winCount = 0;
        lastAvgWriteMs = 0L;
    }

    /** 每发出一帧音频后调用（writeMs = 写 socket 耗时；&lt;0 表示没发出去） */
    public synchronized void onFrameSent(long writeMs) {
        if (writeMs < 0) {
            return;
        }
        winSumMs += writeMs;
        winCount++;
    }

    /**
     * 每 {@link #EVAL_INTERVAL_MS} 采样一次。
     *
     * @param backlogMs   播放侧积压总口径（队列 + 线内未播，毫秒；线内不可测时只含队列）
     * @param frameMs     本通音频帧长，用于把积压阈值按帧长放大（兼容 200ms 帧旧对端）
     * @param missedDelta 本窗口内缺帧（补静音跳过/水位裁帧）增量
     * @param framesDelta 本窗口内正常收到的帧数增量
     */
    public synchronized void evaluate(long backlogMs, int frameMs,
                                      int missedDelta, int framesDelta) {
        long nowMs = System.currentTimeMillis();
        long avg = winCount > 0 ? winSumMs / winCount : -1L;
        lastAvgWriteMs = avg;
        winSumMs = 0L;
        winCount = 0;

        int total = missedDelta + framesDelta;
        int missPct = total <= 0 ? 0 : (missedDelta * 100 / total);

        long congestedBacklog = Math.max(CONGESTED_BACKLOG_MS, 2L * frameMs);
        long healthyBacklog = Math.max(HEALTHY_BACKLOG_MS, (long) frameMs);

        boolean congested = (avg >= 0 && avg > CONGESTED_WRITE_MS)
                || backlogMs > congestedBacklog
                || missPct > MISS_DOWN_PCT;
        boolean healthy = (avg >= 0 && avg <= HEALTHY_WRITE_MS)
                && backlogMs <= healthyBacklog
                && missPct <= MISS_OK_PCT;

        if (congested) {
            downStreak++;
            healthySince = 0L;
            if (downStreak >= DOWN_STREAK && current == HI
                    && nowMs - lastDownAt >= DOWN_MIN_GAP_MS) {
                downStreak = 0;
                lastDownAt = nowMs;
                current = LO;
                Log.i("语音降码率 → " + (LO / 1000) + "k（avgWrite=" + avg + "ms 积压="
                        + backlogMs + "ms/阈值" + congestedBacklog + "ms 缺帧=" + missPct + "%）");
                if (onChange != null) {
                    onChange.onChange(LO);
                }
            }
            return;
        }

        downStreak = 0;
        if (healthy) {
            if (healthySince == 0L) {
                healthySince = nowMs;
            }
        } else {
            healthySince = 0L;
        }

        if (current != LO) {
            return;
        }
        if (nowMs - lastUpAt < UP_MIN_GAP_MS) {
            return;
        }
        if (lastDownAt > 0 && nowMs - lastDownAt < UP_AFTER_DOWN_MS) {
            return;
        }
        if (healthySince == 0L || nowMs - healthySince < UP_HEALTHY_MS) {
            return;
        }

        lastUpAt = nowMs;
        healthySince = 0L;
        current = HI;
        Log.i("语音升码率 → " + (HI / 1000) + "k（健康窗口 " + (UP_HEALTHY_MS / 1000)
                + "s，avgWrite=" + avg + "ms 积压=" + backlogMs + "ms）");
        if (onChange != null) {
            onChange.onChange(HI);
        }
    }
}
