package com.phonepctransfer.pc;

import java.util.Locale;

/**
 * 接收管线分阶段计时（诊断用，2026-10-30 加）。
 *
 * <p>每秒汇总一行 ASCII，写进 pc.log：
 * <pre>
 * [xfer] rx=131.2MB/s blk=31 read=412 copy=8 digest=0 wait=96 write=530 part=12 ms/s
 * </pre>
 * 各列是「这一秒内花在该阶段上的墙钟毫秒数」：
 * <ul>
 *   <li>read   读线程在 Protocol.readFrame 里（含等在 socket 上）</li>
 *   <li>copy   把分片拷进 4MB 轮转缓冲</li>
 *   <li>digest SHA-256（只在续传通道非 0 —— 一次性传完不算摘要）</li>
 *   <li>wait   读线程等落盘线程归还缓冲（背压）</li>
 *   <li>write  落盘线程 sink.write()</li>
 *   <li>part   落盘线程重写 partials.json</li>
 * </ul>
 * <p>read 与 write/wait 分属两个线程，所以同一行加起来可能超过 1000ms —— 这是正常的。
 * 判定口径：read 接近 1000 = 网络侧封顶；wait+write 大 = 磁盘侧封顶；
 * digest+copy 大 = CPU 侧封顶。
 */
final class TxStats {

    private static final long WINDOW_NS = 1_000_000_000L;

    private static long bytes;
    private static long blocks;
    private static long readNs;
    private static long copyNs;
    private static long digestNs;
    private static long waitNs;
    private static long writeNs;
    private static long partNs;
    private static long windowStart = System.nanoTime();

    private TxStats() {
    }

    static synchronized void read(long ns) {
        readNs += ns;
        maybeFlush();
    }

    static synchronized void copy(long ns) {
        copyNs += ns;
    }

    static synchronized void digest(long ns) {
        digestNs += ns;
    }

    static synchronized void waitBuf(long ns) {
        waitNs += ns;
    }

    static synchronized void write(long ns) {
        writeNs += ns;
    }

    static synchronized void part(long ns) {
        partNs += ns;
    }

    /** 一块 4MB 落盘完成：累计字节与块数（吞吐量按这个算） */
    static synchronized void block(long len) {
        bytes += len;
        blocks++;
        maybeFlush();
    }

    private static void maybeFlush() {
        long now = System.nanoTime();
        long dt = now - windowStart;
        if (dt < WINDOW_NS || bytes <= 0) {
            return;
        }
        double mbps = bytes * 1_000_000_000.0 / dt / (1024.0 * 1024.0);
        Log.i(String.format(Locale.US,
                "[xfer] rx=%.1fMB/s blk=%d read=%d copy=%d digest=%d wait=%d write=%d part=%d ms/s",
                mbps, blocks, readNs / 1_000_000L, copyNs / 1_000_000L, digestNs / 1_000_000L,
                waitNs / 1_000_000L, writeNs / 1_000_000L, partNs / 1_000_000L));
        bytes = 0;
        blocks = 0;
        readNs = 0;
        copyNs = 0;
        digestNs = 0;
        waitNs = 0;
        writeNs = 0;
        partNs = 0;
        windowStart = now;
    }
}
