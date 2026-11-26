package com.phonepctransfer.net

import android.util.Log
import java.util.Locale

/**
 * 接收管线分阶段计时（诊断用）；字段与口径和 PC 端 `TxStats.java` 完全一致。
 * 每秒汇总一行：`[xfer] rx=… blk=… read=… copy=… digest=… wait=… write=… part=… ms/s`，
 * 各列 = 这一秒内花在该阶段的墙钟毫秒数（read=readFrame 含等 socket，copy=分片拷进轮转缓冲
 * （[Protocol.RX_BUF_SIZE]=8MB），
 * digest=SHA-256(一次性传完时为 0)，wait=读线程等落盘线程归还缓冲(背压)，write=落盘 sink.write()，part=重写 partials.json）。
 * read 与 write/wait 分属两个线程，同一行加起来可能超过 1000ms，正常；
 * 判定：read 贴近 1000 = 网络封顶，wait+write 大 = 磁盘封顶，copy+digest 大 = CPU 封顶
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
    /** 零拷贝直接进内核的字节数（sendfile/transferFrom 命中；回落路径不计） */
    private var zcBytes = 0L
    /** 本秒发生的分片缓冲分配次数（P1-3 快路径生效后应接近 0） */
    private var allocs = 0L
    /** 本秒零拷贝回落次数（正常应为 0；SAF 虚拟文档等可 seek 失败的源会走这里） */
    private var zcFalls = 0L
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

    /** 零拷贝命中：本秒有多少字节绕过用户态拷贝直接进了 socket/磁盘 */
    @Synchronized
    fun zeroCopy(len: Long) { zcBytes += len }

    /** 分片缓冲分配次数（分配即损耗，快路径命中时应为 0） */
    @Synchronized
    fun alloc(n: Int) { allocs += n }

    /** 零拷贝不可用、本块回落普通拷贝的次数 */
    @Synchronized
    fun zcFallback() { zcFalls += 1 }

    /** 一块落盘完成（块 = [Protocol.RX_BUF_SIZE] = 8MB；提前冲半块时按实际字节计）：吞吐量按这里累计的字节算 */
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
            "[xfer] rx=%.1fMB/s blk=%d read=%d copy=%d digest=%d wait=%d write=%d part=%d zc=%dMB zcf=%d alloc=%d ms/s",
            mbps, blocks, readNs / 1_000_000L, copyNs / 1_000_000L, digestNs / 1_000_000L,
            waitNs / 1_000_000L, writeNs / 1_000_000L, partNs / 1_000_000L,
            zcBytes / (1024 * 1024), zcFalls, allocs
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
        zcBytes = 0L
        zcFalls = 0L
        allocs = 0L
        windowStart = now
    }
}
