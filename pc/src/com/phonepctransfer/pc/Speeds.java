package com.phonepctransfer.pc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传输速率采样（2026-09-22 新增）：气泡里显示 `87%  0.95M/s`。
 *
 * **与安卓端 `net/SpeedMeter.kt` + `data/MsgItem.kt TextUtil.speed` 同口径**，两端读到的数字
 * 必须能对上（同一个文件两端各算一次，采样点都是「本机已处理的字节数」）：
 *  - 字节级采样（发送循环 `sent += len` / 接收 `appendToRx` 后）—— 不用百分比反推：
 *    progress 是整数百分比，10GB 文件 1% = 105MB，采样间隔会被拉成好几秒；
 *  - 节流 500ms + EMA（0.4 权重）平滑，避免 1MB 分片抖动让数字乱跳；
 *  - 字节数倒退（断点续传）→ 重新取基线，绝不给负值。
 *
 * 线程：采样发生在发送/接收线程，读发生在 EDT（气泡重绘）。全部走 ConcurrentHashMap +
 * 每个 Meter 自带锁，不做跨线程通知 —— 界面用 [bpsOf] 主动拉，拉到的值为 0 就不显示。
 */
public final class Speeds {

    private Speeds() {
    }

    /** 速率有效期：超过这么久没有新样本 → 当作「没在传」（暂停 / 卡住 / 传完），气泡不显示 */
    public static final long FRESH_MS = 1500L;

    /** 采样最短间隔（ms）：与安卓端一致 */
    private static final long MIN_INTERVAL_MS = 500L;

    private static final class Meter {
        long lastBytes = -1L;
        long lastAt = 0L;
        double ema = 0.0;
    }

    private static final Map<String, Meter> METERS = new ConcurrentHashMap<>();
    /** mid → {bps, 采样时刻} */
    private static final Map<String, long[]> VALUES = new ConcurrentHashMap<>();

    /** 记一笔字节总数（发送/接收循环里调）；不够一个采样间隔就直接返回 */
    public static void sample(String mid, long bytes) {
        if (mid == null || mid.isEmpty() || bytes <= 0L) {
            return;
        }
        Meter m = METERS.computeIfAbsent(mid, k -> new Meter());
        synchronized (m) {
            long t = System.currentTimeMillis();
            if (m.lastBytes < 0L) {           // 第一个样本只取基线
                m.lastBytes = bytes;
                m.lastAt = t;
                return;
            }
            if (bytes < m.lastBytes) {        // 续传 / 重置 → 重新取基线
                m.lastBytes = bytes;
                m.lastAt = t;
                m.ema = 0.0;
                return;
            }
            long dt = t - m.lastAt;
            if (dt < MIN_INTERVAL_MS) {
                return;
            }
            double inst = (bytes - m.lastBytes) * 1000.0 / dt;
            m.ema = m.ema <= 0.0 ? inst : m.ema * 0.6 + inst * 0.4;
            m.lastBytes = bytes;
            m.lastAt = t;
            VALUES.put(mid, new long[]{Math.max(0L, (long) m.ema), t});
        }
    }

    /** 当前速率（字节/秒）；**0 = 未知 / 已过期 → 界面不显示** */
    public static long bpsOf(String mid) {
        long[] v = mid == null || mid.isEmpty() ? null : VALUES.get(mid);
        if (v == null) {
            return 0L;
        }
        return System.currentTimeMillis() - v[1] <= FRESH_MS ? v[0] : 0L;
    }

    /** 这条传完/失败/取消 → 丢掉采样器与数值 */
    public static void end(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        METERS.remove(mid);
        VALUES.remove(mid);
    }

    /**
     * 速率文案（与安卓 `TextUtil.speed` **完全一致**）：
     *  - **小于 801 KB/s → 整数 K，无小数**：`650K/s`
     *  - **801 KB/s 及以上 → M，两位小数**：`0.95M/s`
     *
     * 用 `Locale.ROOT`：小数分隔符恒为点，别跟着系统区域变成逗号（安卓端同此口径）。
     */
    public static String text(long bps) {
        if (bps <= 0L) {
            return "";
        }
        if (bps < 801L * 1024L) {
            return (bps / 1024L) + "K/s";
        }
        return String.format(Locale.ROOT, "%.2fM/s", bps / 1048576.0);
    }
}
