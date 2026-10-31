package com.phonepctransfer.util

import java.util.Arrays

// ==================================================================
// 分段延迟计量（任务单 1.2）——M1 / M9 的唯一证据来源（I12：不接受"听起来还行"）。
//
// 与 PC 端 `pc/SegMeter.java` **同名同序同口径**（改一处必须同时改两处）：
//   aEnc  本轮采集开始（含打包等待）→ Opus 编码完
//   aTx   编码完 → sendAudioTo 返回（已交给传输）
//   aIn   读线程回调入口 → 播放队列入队完成
//   aBuf  入队 → 出队（= 抖动缓冲实际驻留，M1 预算里那一行 40ms）
//   aOut  出队 → 播放轨道 write 返回
//   vTx   编码输出回调 → sendVideoTo 返回
//   vIn   读线程回调入口 → 解码队列入队完成
//   vOut  解码队列出队 → releaseOutputBuffer(render=true) 返回
//
// 首尾相接：发送侧 aEnc→aTx、接收侧 aIn→aBuf→aOut 各自无隙无叠。
// **有意不计**的三段：采集缓冲里已在飞的 2 帧（§1 归硬件/驱动段，单独报）、
// write 返回后的播放缓冲（同上）、网络段（两端零点不同，测不了单程）。
//
// 热路径约束：record() 只做「一次无竞争锁 + 一次 IntArray 写」，不拼字符串、不装箱；
// 排序与拼行只在 pp-seg-meter 线程里做（每秒一次）。
// ==================================================================
object SegMeter {

    // ==================== 段号（双端同序） ====================
    const val A_ENC = 0
    const val A_TX = 1
    const val A_IN = 2
    const val A_BUF = 3
    const val A_OUT = 4
    const val V_TX = 5
    const val V_IN = 6
    const val V_OUT = 7

    private val NAMES = arrayOf("aEnc", "aTx", "aIn", "aBuf", "aOut", "vTx", "vIn", "vOut")

    private val SEGS = NAMES.size

    /** 单段单窗上限：1s 窗内最高 ~60 样本，1024 是纯防御（超上限丢弃、不覆盖已有样本） */
    private const val CAP = 1024

    private const val WINDOW_MS = 1000L

    private val lock = Object()

    /** 每段双缓冲（ping-pong）：record 只碰当前面，flush 换面后独占另一面排序 */
    private val buf = Array(SEGS) { Array(2) { IntArray(CAP) } }
    private val idx = IntArray(SEGS)
    private val cnt = IntArray(SEGS)

    @Volatile
    private var alive = false

    @Volatile
    private var loopStarted = false

    @Volatile
    private var printer: ((String) -> Unit)? = null

    /** 进入 TALKING 调用：清窗 + 开始每秒打印（打印线程只建一次，之后靠 alive 开关） */
    fun start(p: (String) -> Unit) {
        printer = p
        synchronized(lock) {
            idx.fill(0)
            cnt.fill(0)
        }
        alive = true
        if (loopStarted) return
        loopStarted = true
        Thread({ loop() }, "pp-seg-meter").apply { isDaemon = true }.start()
    }

    /**
     * 通话结束：停表 + **把残留窗口（不足 1s 的尾巴）打最后一行汇总**。
     * 验收门要求"每秒分段行 + 挂断汇总行"，少了这一次，短于一秒的通话与挂断前最后
     * 不足一秒的样本在数字上就是空的。残留样本在下一次 [start] 清空。
     */
    fun stop() {
        alive = false
        flushOnce()
    }

    /**
     * 记一段。t0/t1 取同一条时间轴的 `System.nanoTime()`（本端单调、无装箱）。
     * 负值/零按 0 记；超 9999ms 夹到 9999（与 lat 上报口径一致，便于对照）。
     */
    fun record(seg: Int, t0Nano: Long, t1Nano: Long) {
        val d = t1Nano - t0Nano
        val ms = when {
            d <= 0L -> 0
            d >= 9_999_000_000L -> 9999
            else -> (d / 1_000_000L).toInt()
        }
        synchronized(lock) {
            val c = cnt[seg]
            if (c < CAP) {
                buf[seg][idx[seg]][c] = ms
                cnt[seg] = c + 1
            }
        }
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(WINDOW_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (alive) flushOnce()
        }
    }

    /** 唯一的汇总打印点：一行含全部段，空段打 '-'，全空则本轮不打 */
    private fun flushOnce() {
        val counts = IntArray(SEGS)
        val taken = arrayOfNulls<IntArray>(SEGS)
        synchronized(lock) {
            for (s in 0 until SEGS) {
                taken[s] = buf[s][idx[s]]
                counts[s] = cnt[s]
                idx[s] = idx[s] xor 1
                cnt[s] = 0
            }
        }
        val sb = StringBuilder(192)
        sb.append("分段ms(p50/p95/max)")
        var any = false
        for (s in 0 until SEGS) {
            sb.append(' ').append(NAMES[s]).append('=')
            val c = counts[s]
            val a = taken[s]
            if (c <= 0 || a == null) {
                sb.append('-')
                continue
            }
            any = true
            val arr = a
            Arrays.sort(arr, 0, c)
            val p50 = arr[c shr 1]
            val p95 = arr[minOf(c - 1, c * 95 / 100)]
            sb.append(p50).append('/').append(p95).append('/').append(arr[c - 1])
                .append('(').append(c).append(')')
        }
        val pr = printer
        if (pr != null && any) pr(sb.toString())
    }
}
