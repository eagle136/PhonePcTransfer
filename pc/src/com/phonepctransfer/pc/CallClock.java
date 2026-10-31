package com.phonepctransfer.pc;

// ==================================================================
// 通话相对单调时钟（任务单 1.1）。
//
// 媒体帧头的 ts 只有 4B：塞绝对 currentTimeMillis 既会 49.7 天回绕，又会把
// 两端墙钟差、NTP 校正、用户手动改表全部混进时间轴。改为「本端起呼=0 的相对毫秒」：
//   · 两端各自零点（都在进入 TALKING 时置零），接收端**从不**跨端比较绝对值；
//   · 帧龄是两段式（发送侧排队 + 接收侧排队，见方案 §3.8），本类只提供发送侧那一段的基准；
//   · nanoTime 的绝对值无意义、差值有意义 → 只取差值，负值夹 0。
// 上限核对：单通最长 6h = 21_600_000ms << 4_294_967_295（4B 无符号），不会回绕。
//
// 本机同一时刻只有一路通话（CallManager 单实例），故用全局单例。
// ==================================================================
public final class CallClock {

    private static volatile long zeroNanos = 0L;

    private CallClock() {
    }

    /** 进入 TALKING 时置零（两端同义：起呼=0）。 */
    public static void reset() {
        zeroNanos = System.nanoTime();
    }

    /** 距本次起呼的毫秒数；未置零（无通话）时为 0。 */
    public static long elapsedMs() {
        long z = zeroNanos;
        if (z == 0L) {
            return 0L;
        }
        long d = (System.nanoTime() - z) / 1_000_000L;
        return d < 0L ? 0L : d;
    }
}
