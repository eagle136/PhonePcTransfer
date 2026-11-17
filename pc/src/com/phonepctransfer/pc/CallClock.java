package com.phonepctransfer.pc;

// 通话相对单调时钟：两端各自「本端起呼=0」的相对毫秒，接收端从不跨端比较绝对值；
// 单通最长 6h 远小于 4B 无符号上限，不回绕；本机单路通话，用全局单例。
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
