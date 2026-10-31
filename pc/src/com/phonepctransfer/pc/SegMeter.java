package com.phonepctransfer.pc;

import java.util.Arrays;

// ==================================================================
// 分段延迟计量（任务单 1.2）——M1 / M9 的唯一证据来源（I12：不接受"听起来还行"）。
//
// 段名双端一致（改一处必须同时改 pc/SegMeter.java 与 android util/SegMeter.kt）：
//   aEnc  本轮采集开始（含打包等待）→ Opus 编码完
//   aTx   编码完 → sendAudioTo 返回（已交给传输）
//   aIn   读线程回调入口 → 播放队列入队完成
//   aBuf  入队 → 出队（= 抖动缓冲实际驻留，M1 预算里那一行 40ms）
//   aOut  出队 → 播放线 write 返回
//   vTx   编码输出回调 → sendVideoTo 返回
//   vIn   读线程回调入口 → 解码队列入队完成
//   vOut  解码队列出队 → 一帧交付 UI（PC：ffmpeg 输出一帧并回调返回）
//
// 首尾相接：发送侧 aEnc→aTx、接收侧 aIn→aBuf→aOut 各自无隙无叠。
// **有意不计**的三段：采集线缓冲里已在飞的 2 帧（§1 归硬件/驱动段，单独报）、
// write 返回后的播放缓冲（同上）、网络段（两端零点不同，测不了单程）。
// PC 端 vOut 少算了 EDT 异步重绘（VideoWindow 用 repaintPending 合帧），
// 上屏真实间隔由任务单 5 的 renderGapMs 交叉核对。
//
// 热路径约束：record() 只做「一次无竞争锁 + 一次 int 写」，不拼字符串、不装箱；
// 排序与拼行只在本类的 pp-seg-meter 线程里做（每秒一次）。
// ==================================================================
public final class SegMeter {

    // ==================== 段号（双端同序） ====================

    public static final int A_ENC = 0;
    public static final int A_TX = 1;
    public static final int A_IN = 2;
    public static final int A_BUF = 3;
    public static final int A_OUT = 4;
    public static final int V_TX = 5;
    public static final int V_IN = 6;
    public static final int V_OUT = 7;

    private static final String[] NAMES = {
            "aEnc", "aTx", "aIn", "aBuf", "aOut", "vTx", "vIn", "vOut"
    };

    private static final int SEGS = NAMES.length;

    /** 单段单窗上限：1s 窗内最高 ~60 样本，1024 是纯防御（超上限丢弃、不覆盖已有样本） */
    private static final int CAP = 1024;

    private static final int WINDOW_MS = 1000;

    public interface Printer {
        void println(String line);
    }

    private static final SegMeter INSTANCE = new SegMeter();

    public static SegMeter get() {
        return INSTANCE;
    }

    /** 每段双缓冲（ping-pong）：record 只碰当前面，flush 换面后独占另一面排序 */
    private final Object lock = new Object();
    private final int[][][] buf = new int[SEGS][2][CAP];
    private final int[] idx = new int[SEGS];
    private final int[] cnt = new int[SEGS];

    private volatile boolean alive = false;
    private volatile boolean loopStarted = false;
    private volatile Printer printer;

    private SegMeter() {
    }

    /** 进入 TALKING 调用：清窗 + 开始每秒打印（打印线程只建一次，之后靠 alive 开关） */
    public void start(Printer p) {
        printer = p;
        synchronized (lock) {
            Arrays.fill(idx, 0);
            Arrays.fill(cnt, 0);
        }
        alive = true;
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        Thread t = new Thread(this::loop, "pp-seg-meter");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 通话结束：**先把残留窗口（不足 1s 的尾巴）打最后一行汇总**，再停止每秒打印。
     * 没有这一次 flush，短于一秒的通话、以及挂断前最后不足一秒的样本在验收里就是空的
     * （验收门写的是"每秒分段延迟行 + 挂断汇总行"）。
     * 残留样本在下一次 {@link #start} 清空。
     */
    public void stop() {
        alive = false;
        flushOnce();
    }

    /**
     * 记一段。t0/t1 取同一条时间轴的 {@code System.nanoTime()}（本端单调、无装箱）。
     * 负值/零按 0 记；超 9999ms 夹到 9999（与 lat 上报口径一致，便于对照）。
     */
    public void record(int seg, long t0Nano, long t1Nano) {
        long d = t1Nano - t0Nano;
        int ms = d <= 0L ? 0 : (d >= 9_999_000_000L ? 9999 : (int) (d / 1_000_000L));
        synchronized (lock) {
            int c = cnt[seg];
            if (c < CAP) {
                buf[seg][idx[seg]][c] = ms;
                cnt[seg] = c + 1;
            }
        }
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(WINDOW_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (alive) {
                flushOnce();
            }
        }
    }

    /** 唯一的汇总打印点：一行含全部段，空段打 '-'，全空则本轮不打 */
    private void flushOnce() {
        int[] counts = new int[SEGS];
        int[][] taken = new int[SEGS][];
        synchronized (lock) {
            for (int s = 0; s < SEGS; s++) {
                taken[s] = buf[s][idx[s]];
                counts[s] = cnt[s];
                idx[s] ^= 1;
                cnt[s] = 0;
            }
        }
        StringBuilder sb = new StringBuilder(192);
        sb.append("分段ms(p50/p95/max)");
        boolean any = false;
        for (int s = 0; s < SEGS; s++) {
            sb.append(' ').append(NAMES[s]).append('=');
            int c = counts[s];
            if (c <= 0) {
                sb.append('-');
                continue;
            }
            any = true;
            int[] a = taken[s];
            Arrays.sort(a, 0, c);
            int p50 = a[c >> 1];
            int p95 = a[Math.min(c - 1, (int) ((long) c * 95 / 100))];
            sb.append(p50).append('/').append(p95).append('/').append(a[c - 1])
                    .append('(').append(c).append(')');
        }
        Printer p = printer;
        if (p != null && any) {
            p.println(sb.toString());
        }
    }
}
