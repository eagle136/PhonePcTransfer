package com.phonepctransfer.util

// ==================================================================
// 通话相对单调时钟（任务单 1.1）。
//
// 与 PC 端 `CallClock.java` **同名同义同实现**，两端必须对称：
//   · ts 只描述本端发出的流，塞绝对 currentTimeMillis 会 49.7 天回绕，
//     还会把两端墙钟差 / NTP 校正 / 用户改表混进时间轴；
//   · 两端各自零点（进入 TALKING 时置零），接收端从不跨端比较绝对值；
//   · 帧龄是两段式（发送侧排队 + 接收侧排队，见方案 §3.8），本对象只提供发送侧基准；
//   · nanoTime 绝对值无意义、差值有意义 → 只取差值，负值夹 0。
// 上限核对：单通最长 6h = 21_600_000ms << 4_294_967_295（4B 无符号），不会回绕。
//
// 同一时刻只有一路通话（CallManager 单实例），故用全局单例。
// ==================================================================
object CallClock {

    @Volatile
    private var zeroNanos = 0L

    /** 进入 TALKING 时置零（两端同义：起呼=0） */
    fun reset() {
        zeroNanos = System.nanoTime()
    }

    /** 距本次起呼的毫秒数；未置零（无通话）时为 0 */
    fun elapsedMs(): Long {
        val z = zeroNanos
        if (z == 0L) return 0L
        val d = (System.nanoTime() - z) / 1_000_000L
        return if (d < 0L) 0L else d
    }
}
