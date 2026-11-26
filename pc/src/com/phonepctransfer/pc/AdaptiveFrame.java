package com.phonepctransfer.pc;

import java.util.Locale;

/**
 * 自适应分片大小控制器（单连接下尽量把带宽跑满）。与安卓端 AdaptiveFrame 同规则，双端各自独立评估。
 *
 * 档位 64K→8M，**默认 1M 起步**；每 500ms 评估一次：吞吐不降、单帧写入无明显背压 → 升一档，
 * 一旦抖动 → 立即降两档。判据见 {@link #onFrame(int, long)}。
 *
 * 顶档 8M 的依据（2026-11-22 抬档）：接收端轮转缓冲 {@link Protocol#RX_BUF_SIZE} 已双端同步提到 8MB，
 * 8M 级帧能整帧直读进轮转缓冲走零拷贝；再高不行 —— 协议单帧硬上限 {@link Protocol#MAX_FRAME} 就是 8MB。
 * ⚠ 8M 是**载荷**档位：帧头(6B+mid)也占 MAX_FRAME 额度，发送侧必须用 {@code LinkSend#framePayloadCap} 再夹一次载荷。
 */
final class AdaptiveFrame {

    /**
     * 档位表 64K→8M。顶档 8M 是**载荷**档位，而 {@link Protocol#MAX_FRAME}(8MB) 是整帧上限：
     * 帧头(6B + mid)还要占额度，所以发送侧一律再夹 {@code LinkSend#framePayloadCap} → 一帧正好卡在 8MB，
     * 接收端轮转缓冲 {@link Protocol#RX_BUF_SIZE}(8MB) 仍能整块直读，不额外分配。
     */
    static final int[] GEARS = {
            64 * 1024,
            128 * 1024,
            256 * 1024,
            512 * 1024,
            1024 * 1024,
            2 * 1024 * 1024,
            4 * 1024 * 1024,
            8 * 1024 * 1024
    };

    /** 默认档位下标 = 1M */
    static final int DEFAULT_INDEX = 4;
    static final long WINDOW_NS = 500L * 1_000_000L;
    /** 吞吐环比低于该比例视为抖动 */
    static final double DROP_RATIO = 0.75;
    /** 单帧写超过该耗时视为背压（绝对下限，防小帧被噪声误判） */
    static final long SLOW_FRAME_NS = 60L * 1_000_000L;

    /** 日志用的文件名/标识，可为空（为空时日志只带档位信息） */
    private final String label;

    private int idx = DEFAULT_INDEX;
    private long windowStart = System.nanoTime();
    private long windowBytes = 0L;
    private int windowFrames = 0;
    private int slowFrames = 0;
    private double lastTp = 0.0;

    int ups = 0;
    int drops = 0;

    AdaptiveFrame() {
        this("");
    }

    AdaptiveFrame(String label) {
        this.label = (label == null) ? "" : label;
    }

    int size() {
        return GEARS[idx];
    }

    int gearIndex() {
        return idx;
    }

    /** 每帧发完后调用：len=本帧载荷字节，writeNanos=本次写入耗时 */
    void onFrame(int len, long writeNanos) {
        windowBytes += len;
        windowFrames++;
        if (isSlowFrame(len, writeNanos)) {
            slowFrames++;
        }
        long now = System.nanoTime();
        long elapsed = now - windowStart;
        if (elapsed < WINDOW_NS) {
            return;
        }
        double tp = windowBytes * 1e9 / (double) elapsed;
        boolean slowHeavy = windowFrames > 0 && slowFrames * 4 >= windowFrames;
        boolean shrunk = lastTp > 0.0 && tp < lastTp * DROP_RATIO;
        int before = idx;
        if (slowHeavy || shrunk) {
            if (idx > 0) {
                idx = Math.max(0, idx - 2);
                drops++;
            }
        } else if (lastTp <= 0.0 || tp >= lastTp) {
            if (idx < GEARS.length - 1) {
                idx++;
                ups++;
            }
        }
        if (idx != before) {
            // 档位变化日志（每 0.5s 窗口最多一行，只记变化点，不污染热路径）
            logGear(before, tp, shrunk, slowHeavy);
        }
        lastTp = tp;
        windowStart = now;
        windowBytes = 0;
        windowFrames = 0;
        slowFrames = 0;
    }

    /**
     * 慢帧判据（背压信号）：单帧写耗时超过「按上一窗口吞吐这一帧本该花多久」的 2 倍才算卡，
     * 且不低于 60ms 的绝对下限。
     * <p>为什么不能只用 60ms：顶档 8M 帧在 130MB/s 下天然就要 ~64ms，固定 60ms 会把顶档帧全判成
     * 慢帧 → 每个窗口 slowHeavy 成立 → 8M 与 2M 之间来回抖（抬档等于白抬）。
     */
    private boolean isSlowFrame(int len, long writeNanos) {
        long floor = SLOW_FRAME_NS;
        if (lastTp > 0.0) {
            long expect = (long) (len * 1e9 / lastTp);
            floor = Math.max(floor, expect * 2);
        }
        return writeNanos > floor;
    }

    /** 档位变化一行：`[frame] 文件 升档 4M→8M 吞吐=128.3MB/s 慢帧=0/16 已到顶档` */
    private void logGear(int from, double tp, boolean shrunk, boolean slowHeavy) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("[frame] ");
        if (!label.isEmpty()) {
            sb.append(label).append(' ');
        }
        sb.append(idx > from ? "升档 " : "降档 ");
        sb.append(gearName(GEARS[from])).append('→').append(gearName(GEARS[idx]));
        sb.append(" 吞吐=").append(String.format(Locale.US, "%.1f", tp / (1024.0 * 1024.0))).append("MB/s");
        sb.append(" 慢帧=").append(slowFrames).append('/').append(windowFrames);
        if (shrunk && lastTp > 0.0) {
            sb.append(" 环比=").append(String.format(Locale.US, "%.0f%%", (tp / lastTp - 1.0) * 100.0));
        }
        if (slowHeavy) {
            sb.append(" 慢帧超标");
        }
        if (idx == GEARS.length - 1) {
            sb.append(" 已到顶档");
        }
        Log.i(sb.toString());
    }

    private static String gearName(int bytes) {
        return (bytes >= 1024 * 1024) ? (bytes / (1024 * 1024)) + "M" : (bytes / 1024) + "K";
    }

    void reset() {
        idx = DEFAULT_INDEX;
        windowStart = System.nanoTime();
        windowBytes = 0;
        windowFrames = 0;
        slowFrames = 0;
        lastTp = 0.0;
        ups = 0;
        drops = 0;
    }
}
