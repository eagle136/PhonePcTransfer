package com.phonepctransfer.net

import android.util.Log
import java.util.Locale

/**
 * 接收管线分阶段计时（诊断用，2026-10-30 加）。字段与口径和 PC 端 `TxStats.java` 完全一致。
 *
 * 每秒汇总一行 ASCII：
 * ```
 * [xfer] rx=131.2MB/s blk=31 read=412 copy=8 digest=0 wait=96 write=530 part=12 ms/s
 * ```
 * 各列 = 这一秒内花在该阶段的墙钟毫秒数：
 *  - read   读线程在 Protocol.readFrame 里（含等 socket）
 *  - copy   把分片拷进 4MB 轮转缓冲
 *  - digest SHA-256（一次性传完时为 0 —— 只在续传通道才算）
 *  - wait   读线程等落盘线程归还缓冲（背压）
 *  - write  落盘线程 sink.write()
 *  - part   落盘线程重写 partials.json
 *
 * read 与 write/wait 分属两个线程，同一行加起来可能超过 1000ms，正常。
 * 判定：read 贴近 1000 = 网络封顶；wait+write 大 = 磁盘封顶；copy+digest 大 = CPU 封顶。
 */
internal object TxStats {

    private const val WINDOW_NS = 1_000_000_000L

    /** 输出口：绑定到 Link.log（= logcat + 应用内日志）；未绑定时只打 logcat */
    var printer: ((String) -> Unit)? = null

    private var bytes = 0L
    private var blocks = 0L
    private var readNs = 0L
    private var copyNs = 0L
    private var digestNs = 0L
    private var waitNs = 0L
    private var writeNs = 0L
    private var partNs = 0L
    private var windowStart = System.nanoTime()

    @Synchronized
    fun read(ns: Long) {
        readNs += ns
        maybeFlush()
    }

    @Synchronized
    fun copy(ns: Long) { copyNs += ns }

    @Synchronized
    fun digest(ns: Long) { digestNs += ns }

    @Synchronized
    fun waitBuf(ns: Long) { waitNs += ns }

    @Synchronized
    fun write(ns: Long) { writeNs += ns }

    @Synchronized
    fun part(ns: Long) { partNs += ns }

    /** 一块 4MB 落盘完成：吞吐量按这里累计的字节算 */
    @Synchronized
    fun block(len: Long) {
        bytes += len
        blocks++
        maybeFlush()
    }

    private fun maybeFlush() {
        val now = System.nanoTime()
        val dt = now - windowStart
        if (dt < WINDOW_NS || bytes <= 0L) return
        val mbps = bytes * 1_000_000_000.0 / dt / (1024.0 * 1024.0)
        val line = String.format(
            Locale.US,
            "[xfer] rx=%.1fMB/s blk=%d read=%d copy=%d digest=%d wait=%d write=%d part=%d ms/s",
            mbps, blocks, readNs / 1_000_000L, copyNs / 1_000_000L, digestNs / 1_000_000L,
            waitNs / 1_000_000L, writeNs / 1_000_000L, partNs / 1_000_000L
        )
        val p = printer
        if (p != null) p(line) else Log.i("PP-Xfer", line)
        bytes = 0L
        blocks = 0L
        readNs = 0L
        copyNs = 0L
        digestNs = 0L
        waitNs = 0L
        writeNs = 0L
        partNs = 0L
        windowStart = now
    }
}
