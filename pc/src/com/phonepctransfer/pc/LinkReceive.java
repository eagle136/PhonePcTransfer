package com.phonepctransfer.pc;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.nio.file.*;
import java.net.*;

import com.phonepctransfer.pc.Link.Session;
// ==================================================================
// 文件/文件夹接收管线（v2.7 拆分自 Link.java）：Rx/DirRx、落盘、断点续传、
// dir_end 完整性核对。Rx/DirRx 转为顶层包私有类；方法持 Link 引用访问成员。
// ==================================================================

final class LinkReceive {

    final Link L;

    /**
     * 接收进度帧的 UI 限流窗口（毫秒）。局域网传大文件时 1% 一帧可一秒上百帧，
     * 每帧整泡重建 + revalidate 会淹没 EDT，界面长时间停在旧百分比。
     * 5Hz 刷新对进度条完全够用；完成/失败/暂停等终态帧不受限流影响。
     */
    static final long UI_THROTTLE_MS = 200L;

    /** 2 期多通道：接收侧重排槽数。3 槽 = 允许领先 2 个单元（发送端 2 条通道各在飞 1 个单元） */
    static final int MULTI_SLOTS = 3;

    /** 多通道：等一个槽被写线程腾空的超时。超过即判接收异常（断链重传），避免读线程无限期挂住 */
    static final long MULTI_SLOT_WAIT_MS = 30_000L;

    /** 单通道接收缓冲池块数：3 块 RX_BUF_SIZE 轮转（读线程在填 1 块 + 排队/写盘中最多 2 块）。
     *  缓冲池调大 = 写落后队列更深；磁盘慢于网络时读线程排队等空闲块（TCP 窗口随之收窄，形成背压） */
    static final int RX_BUF_COUNT = 3;

    /** 攒够多少字节才交给落盘线程：满 1MB 即写；不足 1MB 只在单文件收尾/续传查询/直读错位时冲 */
    static final int RX_WRITE_MIN_BYTES = 1 << 20;

    /** 交给落盘线程的一块缓冲：缓冲 + 有效字节数（FIFO 排队，写线程按序落盘） */
    static final class PendingBuf {
        final byte[] buf;
        final int len;
        PendingBuf(byte[] buf, int len) {
            this.buf = buf;
            this.len = len;
        }
    }

    LinkReceive(Link L) {
        this.L = L;
    }

    static final class Rx {
        final String mid;
        final String name;
        final long size;
        /** 所属设备 IP：收到的文件气泡要挂到这台设备的会话里 */
        final String peerIp;
        /** 所属目录气泡 mid；空 = 普通单文件 */
        final String dirId;
        /**
         * 文件夹成员在目录内的相对路径（普通单文件为空）。
         * 接收端用它回答「哪个成员没到」—— dir_end 核对时与对端清单求差集。
         */
        final String relPath;
        /** 续传起始偏移（0 = 全新） */
        final long startAt;
        /** 落盘位置（绝对路径，人看的文案兼删除句柄） */
        String desc;
        OutputStream sink;
        /** 2 期多通道：true 时用「3 槽重排环」按单元号乱序收、顺序写；false 时是单通道缓冲池顺序路径 */
        final boolean multi;
        // 单通道缓冲池：RX_BUF_COUNT 块轮转，正在写盘的块绝不会再被读线程复用
        //（根治旧双缓冲「换块只等 pending 不等 writeBusy」的读写同块竞态）；多通道时不给（由 3 个槽承担）
        byte[][] bufPool;
        byte[] cur;
        int pos = 0;
        /** 空闲块（flushLock 守护）：写线程写完一块归还到这里，读线程换块时从这里取 */
        final ArrayDeque<byte[]> freeBufs = new ArrayDeque<byte[]>();
        /** 已收满待写块队列（flushLock 守护），FIFO；没有空闲块时读线程排队等 */
        final ArrayDeque<PendingBuf> pendingQ = new ArrayDeque<PendingBuf>();
        long received;
        int expectSeq;
        // ===== 2 期多通道：单元重排环 =====
        /** 文件总单元数（1 单元 = CHUNK_SIZE） */
        final int totalUnits;
        /** 期望写入落盘的单元号（读线程按它挑槽，写线程只认它） */
        int expectUnit;
        final byte[][] slotBufs;
        /** 槽状态：0 空闲 1 接收中 2 已收满待写 3 写盘中 */
        final int[] slotState;
        final int[] slotUnit;
        final int[] slotLen;
        final Object slotLock = new Object();
        int lastPct = -1;
        /** 上一次向界面发进度帧的时刻（UI 限流，见 UI_THROTTLE_MS） */
        long lastUiAt = 0L;
        final Object flushLock = new Object();
        volatile boolean writerAlive = true;
        /** 写盘线程代际：resetRxFromZero 换代后，旧线程即使还卡在 wait/写盘里也不得再往新流写 */
        volatile int writerGen = 0;
        /**
         * 落盘线程**正在**写盘：缓冲已从队列取走但 sink.write() 还没返回。
         * ⚠️ 收尾判据必须是「队列空 **且** writeBusy 为假」—— 只看队列会在最后一块
         * 还握在写盘线程手里时就关流，落出 0 字节/截断文件（与 Android 端同构）。
         */
        volatile boolean writeBusy = false;
        /** 已成功写进 sink 的字节数（诊断用） */
        volatile long writtenBytes = 0L;
        /**
         * 上一次登记半成品台账时的「已落盘字节」水位：落盘每累计满 {@link Protocol#BLOCK_SIZE}(32MB)
         * 才 putPartial 一次（旧实现每交一块 1MB 就写一次台账）。
         * 断链最多丢「最后 32MB 内的登记精度」——续传起点按半成品实测磁盘大小对齐，不依赖它。
         */
        long partialRegBytes = 0L;
        /**
         * 最近一次收到真实进度（分片追加成功 / 写盘成功）的时刻 —— 接收停滞看门狗的依据。
         * 更新点两处：onChunk 成功 appendToRx 后、写盘线程 sink.write 成功后。
         */
        volatile long lastProgressAt = System.currentTimeMillis();
        Thread writer;

        Rx(String mid, String name, long size, String peerIp, String dirId, String relPath,
           String desc, OutputStream sink, long startAt, boolean multi) throws Exception {
            this.mid = mid;
            this.name = name;
            this.size = size;
            this.peerIp = peerIp == null ? "" : peerIp;
            this.dirId = dirId;
            this.relPath = relPath == null ? "" : relPath;
            this.desc = desc;
            this.sink = sink;
            this.startAt = startAt;
            this.received = startAt;
            // 自适应帧下 seq 只是「本次尝试内的帧序号」，与文件偏移无关，一律从 0 期望起
            this.expectSeq = (size > 0 && startAt >= size) ? Integer.MAX_VALUE : 0;
            this.multi = multi;
            // 多通道时不给缓冲池（改由 3 个槽承担），避免同一文件两份缓冲同时驻留
            this.bufPool = multi ? new byte[0][] : new byte[RX_BUF_COUNT][Protocol.RX_BUF_SIZE];
            this.cur = multi ? new byte[0] : bufPool[0];
            if (!multi) {
                for (int i = 1; i < bufPool.length; i++) {
                    freeBufs.addLast(bufPool[i]);
                }
            }
            this.totalUnits = (size <= 0) ? 0 : (int) ((size + Protocol.CHUNK_SIZE - 1) / Protocol.CHUNK_SIZE);
            this.expectUnit = (startAt > 0) ? (int) (startAt / Protocol.CHUNK_SIZE) : 0;
            // 槽 = 一个多通道单元（恒为 CHUNK_SIZE），不随单通道轮转缓冲（RX_BUF_SIZE）一起变大
            this.slotBufs = multi ? new byte[MULTI_SLOTS][Protocol.CHUNK_SIZE] : null;
            this.slotState = new int[MULTI_SLOTS];
            this.slotUnit = new int[MULTI_SLOTS];
            this.slotLen = new int[MULTI_SLOTS];
            for (int i = 0; i < MULTI_SLOTS; i++) {
                this.slotUnit[i] = -1;
            }
        }

        /** 截断重来专用：换一套全新缓冲。旧写线程可能还卡在 sink.write 里握着旧块，
         *  复用旧池会重演「读写同块」竞态；旧块由 GC 在旧线程退出后回收 */
        void rebuildBufPool() {
            if (multi) {
                return;
            }
            bufPool = new byte[RX_BUF_COUNT][Protocol.RX_BUF_SIZE];
            cur = bufPool[0];
            pos = 0;
            freeBufs.clear();
            for (int i = 1; i < bufPool.length; i++) {
                freeBufs.addLast(bufPool[i]);
            }
        }
    }

    /** 文件夹接收状态（dir_meta 创建，成员 file_done 汇总） */
    static final class DirRx {
        final String mid;
        final String name;
        final String peerIp;
        /** 对端声明的成员数（同一 mid 重发时会刷新，故非 final） */
        int count;
        long total;
        /** 实际落盘目录（重名自动加序号） */
        final File folder;
        final String desc;
        int doneFiles = 0;
        long doneBytes = 0L;
        boolean failed = false;
        int lastPct = -1;
        /** 上一次向界面发进度帧的时刻（UI 限流，见 UI_THROTTLE_MS） */
        long lastUiAt = 0L;

        // ===== 完整性核对（v2.6，对端 dl=1 时启用）=====

        /** 对端在 dir_meta 里声明的成员清单。空 = 对端是老端 / 未带清单 → 只能按数量核对 */
        java.util.Set<String> expect = new java.util.HashSet<String>();
        /** 清单大小（dir_meta.sizes，与 list 逐项对应）：成员收完按它核对实收字节数 */
        final java.util.Map<String, Long> expectSizes = new java.util.HashMap<String, Long>();
        /** 已成功落盘且校验通过的成员 relPath */
        final java.util.Set<String> got = new java.util.HashSet<String>();
        /** 落盘失败或校验失败的成员 relPath —— 也算「到过」，但要报给对端补发 */
        final java.util.Set<String> bad = new java.util.HashSet<String>();
        /** 对端声明被跳过（深度/数量上限）的分支数，&gt;0 时对用户明示 */
        int skipped = 0;
        /** 本轮已占用的成员落盘相对路径（小写去重）：防两个不同成员清洗后撞名、互相覆盖 */
        final java.util.Set<String> usedRel = new java.util.HashSet<String>();
        /** 已按 dir_end 收尾。成品目录**保留**，同一 mid 重发时复用它，不再生成「文件夹 (1)」副本 */
        boolean finished = false;
        /** 收尾时刻（回收最旧的成品目录用） */
        long finishedAt = 0L;
        /**
         * 最近一次活动（dir_meta / 成员 chunk / 成员 file_done / 成员被中止）。
         * 未完成目录闲置 10 分钟即回收（防「没走到 dir_end 的目录永留」，见 pruneDirs）。
         */
        long lastActivity = System.currentTimeMillis();

        DirRx(String mid, String name, String peerIp, int count, long total, File folder, String desc) {
            this.mid = mid;
            this.name = name;
            this.peerIp = peerIp == null ? "" : peerIp;
            this.count = count;
            this.total = total;
            this.folder = folder;
            this.desc = desc;
        }

        /** 还没到的成员（有清单时精确到项；无清单时返回空表，调用方改用「差几个」） */
        java.util.List<String> missingNow() {
            java.util.List<String> out = new java.util.ArrayList<String>();
            for (String p : expect) {
                // expect-got：bad（对账发现缺块/大小不符）也必须补发，不能当"已交代"跳过
                if (!got.contains(p)) {
                    out.add(p);
                }
            }
            return out;
        }

        /** 清单成员对应的落盘文件（与接收时同一个清洗规则） */
        File fileFor(String rel) {
            return new File(folder, LinkNames.sanitizeRelPath(rel));
        }

        /**
         * 按清单**逐个核对磁盘**：文件不在，或声明了大小但对不上 → 一律算缺。
         * 这是完整性唯一可信的判据（内存里的 got 只代表这一轮收到过，磁盘被挪走/清空就失效）。
         */
        java.util.List<String> missingOnDisk() {
            java.util.List<String> out = new java.util.ArrayList<String>();
            for (String p : expect) {
                File f = fileFor(p);
                Long want = expectSizes.get(p);
                boolean ok = f.isFile() && (want == null || want.longValue() < 0L || f.length() == want.longValue());
                if (!ok) {
                    out.add(p);
                }
            }
            return out;
        }

        /** 已在盘上且大小对的成员（给 dir_have 用）：断链重发时只报「真实已在盘上」的，避免让对端漏传 */
        java.util.List<String> haveOnDisk(java.util.Collection<String> candidates) {
            java.util.List<String> out = new java.util.ArrayList<String>();
            for (String p : candidates) {
                if (!expect.contains(p)) {
                    continue;
                }
                File f = fileFor(p);
                Long want = expectSizes.get(p);
                boolean ok = f.isFile() && (want == null || want.longValue() < 0L || f.length() == want.longValue());
                if (ok) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /** 一次续传核对的会话状态：本端大小即清单声明值（已知），等对端回发落盘字节数 */
    static final class VerifyState {
        final String mid;
        final Msg item;
        volatile long peerSize = -1L;
        volatile boolean finished;

        VerifyState(String mid, Msg item) {
            this.mid = mid;
            this.item = item;
        }
    }

    Thread startWriterThread(final Rx rx) {
        final int gen = rx.writerGen;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (rx.multi) {
                    multiWriteLoop(rx);
                    return;
                }
                while (true) {
                    if (gen != rx.writerGen) {
                        return;   // 已换代：旧线程退出，新线程接手
                    }
                    final PendingBuf pb;
                    synchronized (rx.flushLock) {
                        while (rx.pendingQ.isEmpty() && rx.writerAlive && gen == rx.writerGen) {
                            try {
                                rx.flushLock.wait();
                            } catch (InterruptedException ignore) {
                                return;
                            }
                        }
                        if (gen != rx.writerGen || rx.pendingQ.isEmpty()) {
                            return;
                        }
                        pb = rx.pendingQ.removeFirst();
                        rx.writeBusy = true;      // 从「取走缓冲」到「写完」，收尾必须等这一段
                        rx.flushLock.notifyAll();
                    }
                    try {
                        long w0 = System.nanoTime();
                        rx.sink.write(pb.buf, 0, pb.len);
                        TxStats.write(System.nanoTime() - w0);
                        rx.writtenBytes += pb.len;
                        rx.lastProgressAt = System.currentTimeMillis();
                        TxStats.block(pb.len);
                        // 半成品登记按 32MB 一拍（用户要求：落盘优先 32MB 一次写入）
                        if (L.store != null && rx.writtenBytes - rx.partialRegBytes >= Protocol.BLOCK_SIZE) {
                            long p0 = System.nanoTime();
                            L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, rx.received);
                            TxStats.part(System.nanoTime() - p0);
                            rx.partialRegBytes = rx.writtenBytes;
                        }
                    } catch (Throwable t2) {
                        L.log("落盘失败 " + rx.name + "（已写 " + rx.writtenBytes + "/" + rx.received + "）: " + t2);
                    } finally {
                        synchronized (rx.flushLock) {
                            rx.writeBusy = false;
                            // 写完归还块供读线程复用；代际已换则不归还（reset 已换新池，旧块交给 GC）
                            if (gen == rx.writerGen) {
                                rx.freeBufs.addLast(pb.buf);
                            }
                            rx.flushLock.notifyAll();
                        }
                    }
                }
            }
        }, "pp-disk-" + rx.mid);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 多通道落盘线程：只认 {@link Rx#expectUnit} 那个槽，收到就按顺序写 —— 写入顺序与 1 期完全一致，
     * 因此顺序流落盘 / 续传 / 校验 / 目录记账全都不用改。
     */
    private void multiWriteLoop(Rx rx) {
        final byte[][] bufs = rx.slotBufs;
        if (bufs == null) {
            return;
        }
        while (true) {
            int slot = -1;
            int len = 0;
            synchronized (rx.slotLock) {
                while (slot < 0) {
                    if (!rx.writerAlive) {
                        return;
                    }
                    int s = ((rx.expectUnit % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS;
                    if (rx.slotState[s] == 2 && rx.slotUnit[s] == rx.expectUnit) {
                        slot = s;
                        len = rx.slotLen[s];
                        rx.slotState[s] = 3;
                    } else {
                        try {
                            rx.slotLock.wait(50);
                        } catch (InterruptedException ignore) {
                            return;
                        }
                    }
                }
            }
            byte[] buf = bufs[slot];
            rx.writeBusy = true;
            long w0 = System.nanoTime();
            try {
                rx.sink.write(buf, 0, len);
                TxStats.write(System.nanoTime() - w0);
                rx.writtenBytes += len;
                // 多通道下「收到」的记账点在落盘这一刻（槽里只是暂存）：
                // 少这一行 received 一直是 startAt，进度/半成品台账/收尾校验全错
                rx.received += len;
                rx.lastProgressAt = System.currentTimeMillis();
                TxStats.block(len);
                // 半成品登记按 32MB 一拍（与单通道路径一致）
                if (L.store != null && rx.writtenBytes - rx.partialRegBytes >= Protocol.BLOCK_SIZE) {
                    long p0 = System.nanoTime();
                    L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, rx.received);
                    TxStats.part(System.nanoTime() - p0);
                    rx.partialRegBytes = rx.writtenBytes;
                }
            } catch (Throwable t2) {
                L.log("落盘失败 " + rx.name + "（已写 " + rx.writtenBytes + "/" + rx.received + "）: " + t2);
            } finally {
                synchronized (rx.slotLock) {
                    rx.slotState[slot] = 0;
                    rx.slotUnit[slot] = -1;
                    rx.slotLen[slot] = 0;
                    rx.expectUnit++;
                    rx.writeBusy = false;
                    rx.slotLock.notifyAll();
                }
            }
        }
    }

    /**
     * 关流后测「磁盘上真实有多少字节」——判断有没有被写空/截断；量不到返回 -1（不因此判失败）。
     * PC 端落盘位置就是绝对路径（Rx.desc），直接 File.length()。
     */
    long diskSizeOf(Rx rx) {
        try {
            if (rx.desc == null || rx.desc.isEmpty()) {
                return -1L;
            }
            File f = new File(rx.desc);
            return f.exists() ? f.length() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 缓冲池交接：当前块（≥1MB/写满/直读错位）入队交落盘线程，读线程取下一块空闲块继续填；
     *  没有空闲块 = 磁盘写不过来 → 排队等（TCP 窗口随之收窄，形成背压）。
     *  正在写盘的块绝不会再交到读线程手里——根治旧双缓冲「只等 pending 不等 writeBusy」的换块竞态 */
    void handOffFullBuffer(Rx rx) throws InterruptedException {
        synchronized (rx.flushLock) {
            if (rx.pos == 0) {
                return;
            }
            rx.pendingQ.addLast(new PendingBuf(rx.cur, rx.pos));
            rx.pos = 0;
            while (rx.freeBufs.isEmpty()) {
                if (!rx.writerAlive) {
                    throw new IllegalStateException("接收已停止，无空闲缓冲");
                }
                rx.flushLock.wait();
            }
            rx.cur = rx.freeBufs.removeFirst();
            rx.flushLock.notifyAll();
        }
    }

    /**
     * 分片直读落点判定（P1-3 快路径）：顺序命中且当前缓冲放得下 → 返回落点，
     * 让 Protocol.readFrameInto 把载荷直接读进 8MB 轮转缓冲，省掉每帧一次 byte[] 分配 + 一次 memcpy。
     * <p>放不下时**不再直接回落**（8M 帧一旦错位就会每帧都现分配 8MB + 拷贝一遍）：先把当前半块
     * 交给落盘线程，让本帧从缓冲起点开始读。落盘是顺序 append，提前冲半块不改变正确性，
     * 只是把一次 8MB 写拆成一次半块写 + 一次 8MB 写。
     * <p>只有「单帧比整块缓冲还大」/ 乱序 / 未知 mid 才返回 null（回落到原逻辑，行为不变）。
     */
    Protocol.ChunkSink reserveDirectChunk(String mid, int seq, int len) {
        Rx rx;
        synchronized (L.rxMap) {
            rx = L.rxMap.get(mid);
        }
        if (rx == null) {
            return null;
        }
        if (rx.multi) {
            return reserveUnitSlot(rx, seq, len);
        }
        if (rx.expectSeq != seq) {
            return null;
        }
        if (len > Protocol.RX_BUF_SIZE) {
            return null;
        }
        if (len > Protocol.RX_BUF_SIZE - rx.pos) {
            try {
                handOffFullBuffer(rx);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return new Protocol.ChunkSink(rx.cur, rx.pos);
    }

    /** 多通道落点：按单元号选槽；槽被占用就等（TCP 窗口随之收窄，形成背压），同一单元重传则整槽重写 */
    Protocol.ChunkSink reserveUnitSlot(Rx rx, int seq, int len) {
        final byte[][] bufs = rx.slotBufs;
        if (bufs == null || len > Protocol.CHUNK_SIZE) {
            return null;
        }
        if (seq < rx.expectUnit) {
            return null;
        }
        final int slot = ((seq % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS;
        synchronized (rx.slotLock) {
            long deadline = System.currentTimeMillis() + MULTI_SLOT_WAIT_MS;
            while (true) {
                if (!rx.writerAlive) {
                    throw new IllegalStateException("接收已停止");
                }
                // 窗口闸门：只允许 [expectUnit, expectUnit+MULTI_SLOTS) 的单元占槽。
                // 少这一道，远处的单元（例如 unit 4 与 unit 1 同槽）会抢走本窗口单元的槽，
                // 两条通道互相等对方把那个槽腾空 —— 2 期首测就是这样 30s 超时卡死的。
                if (seq >= rx.expectUnit + MULTI_SLOTS) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new IllegalStateException("接收超时：单元 " + seq + " 超出窗口（已落盘到 "
                                + rx.expectUnit + "）");
                    }
                    try {
                        rx.slotLock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                if (rx.slotState[slot] == 0) {
                    rx.slotState[slot] = 1;
                    rx.slotUnit[slot] = seq;
                    rx.slotLen[slot] = 0;
                    return new Protocol.ChunkSink(bufs[slot], 0);
                }
                if (rx.slotUnit[slot] == seq && rx.slotState[slot] == 1) {
                    // 同一单元的重传（上一条通道中途断了，槽里是半截数据）：从 0 整槽重写
                    rx.slotLen[slot] = 0;
                    return new Protocol.ChunkSink(bufs[slot], 0);
                }
                if (rx.slotUnit[slot] == seq && rx.slotState[slot] == 2) {
                    return null;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("接收超时：单元槽被占用（unit=" + seq + "）");
                }
                try {
                    rx.slotLock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** 多通道：载荷已整单元直读进槽缓冲 0 处 —— 只标记「收满」，由写线程按单元顺序落盘 */
    void commitUnitSlot(Rx rx, int seq, int len) {
        int slot = ((seq % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS;
        synchronized (rx.slotLock) {
            if (rx.slotUnit[slot] != seq || rx.slotState[slot] != 1) {
                L.log("单元落点状态不符（unit=" + seq + " slot=" + slot + " st=" + rx.slotState[slot] + "），忽略");
                return;
            }
            rx.slotLen[slot] = len;
            rx.slotState[slot] = 2;
            rx.slotLock.notifyAll();
        }
    }

    /** 回落路径（没抢到直读落点，载荷已在堆上）：先按窗口认领槽，整单元拷进去，再标记收满 */
    void copyIntoSlot(Rx rx, int seq, byte[] data, int off, int len) {
        final byte[][] bufs = rx.slotBufs;
        if (bufs == null) {
            return;
        }
        // 认领槽（窗口闸门 / 同单元重写都在里面）：直读失效时这条路径同样要占槽，
        // 否则 commit 会因「槽不归这个单元」被拒，数据直接丢
        Protocol.ChunkSink sink = reserveUnitSlot(rx, seq, len);
        if (sink == null) {
            L.log("单元回落无槽（unit=" + seq + "），忽略");
            return;
        }
        if (sink.off + len > sink.buf.length) {
            L.log("单元回落超缓冲（unit=" + seq + " len=" + len + "），忽略");
            return;
        }
        System.arraycopy(data, off, sink.buf, sink.off, len);
        commitUnitSlot(rx, seq, len);
    }

    /** 载荷已直读进 rx.cur 的 off 处：只推进游标与记账，不做拷贝 */
    void commitDirectChunk(Rx rx, int off, int len) throws InterruptedException {
        rx.pos = off + len;
        rx.received += len;
        rx.expectSeq++;
        // 满 1MB 即交落盘线程（含写满整块的情形），不再等 8MB 攒满
        if (rx.pos >= RX_WRITE_MIN_BYTES) {
            long w0 = System.nanoTime();
            handOffFullBuffer(rx);
            TxStats.waitBuf(System.nanoTime() - w0);
        }
    }

    /** 追加一个分片到缓冲（自动切块换缓冲）；off 为分片在 data 中的起始偏移 */
    void appendToRx(Rx rx, byte[] data, int len, int off) throws InterruptedException {
        int src = off;
        int remaining = len;
        while (remaining > 0) {
            if (rx.pos == Protocol.RX_BUF_SIZE) {
                long w0 = System.nanoTime();
                handOffFullBuffer(rx);
                TxStats.waitBuf(System.nanoTime() - w0);
            }
            int n = Math.min(remaining, Protocol.RX_BUF_SIZE - rx.pos);
            long c0 = System.nanoTime();
            System.arraycopy(data, src, rx.cur, rx.pos, n);
            TxStats.copy(System.nanoTime() - c0);
            rx.pos += n;
            rx.received += n;
            src += n;
            remaining -= n;
        }
        // 满 1MB 即写：尾段攒够阈值就交落盘线程，不足 1MB 留到单文件收尾/续传查询再冲
        if (rx.pos >= RX_WRITE_MIN_BYTES) {
            long w0 = System.nanoTime();
            handOffFullBuffer(rx);
            TxStats.waitBuf(System.nanoTime() - w0);
        }
    }

    /** 把接收端缓冲里未落盘的部分全部冲到磁盘（续传查询时调用，让 offset 反映真实落盘量） */
    void flushRxNow(Rx rx) throws InterruptedException {
        synchronized (rx.flushLock) {
            if (rx.pos == 0) {
                return;
            }
            rx.pendingQ.addLast(new PendingBuf(rx.cur, rx.pos));
            rx.pos = 0;
            while (rx.freeBufs.isEmpty()) {
                if (!rx.writerAlive) {
                    return;
                }
                rx.flushLock.wait();
            }
            rx.cur = rx.freeBufs.removeFirst();
            rx.flushLock.notifyAll();
        }
    }

    /** 传输收尾：剩余不满一块直接落盘，等全部写完，关流 */
    void finishRx(Rx rx) throws InterruptedException {
        if (rx.multi) {
            // 多通道：等写线程把重排环里剩下的单元按顺序落完，再关流
            long deadline = System.currentTimeMillis() + 30_000L;
            synchronized (rx.slotLock) {
                while (rx.expectUnit < rx.totalUnits && rx.writerAlive
                        && System.currentTimeMillis() < deadline) {
                    rx.slotLock.wait(100);
                }
            }
            if (rx.expectUnit < rx.totalUnits) {
                L.log("多通道收尾超时：已落盘 " + rx.expectUnit + "/" + rx.totalUnits + " 个单元");
            }
            rx.writerAlive = false;
            synchronized (rx.slotLock) {
                rx.slotLock.notifyAll();
            }
            try {
                rx.sink.close();
            } catch (Exception ignore) {
            }
            return;
        }
        synchronized (rx.flushLock) {
            long deadline = System.currentTimeMillis() + 60_000L;   // P4-8：60s 上限，防磁盘卡死钉住读线程
            // 单文件传输完成：剩余不足 1MB 也全部冲出去
            if (rx.pos > 0) {
                rx.pendingQ.addLast(new PendingBuf(rx.cur, rx.pos));
                rx.pos = 0;
                rx.flushLock.notifyAll();
            }
            // ⚠️ 关键：队列空只说明缓冲被取走，最后一块可能正握在写盘线程手里。
            // 少等 writeBusy 这一下，就会在 sink.write() 中途关流 → 0 字节/截断文件。
            while (!rx.pendingQ.isEmpty() || rx.writeBusy) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) {
                    L.log("收尾等待落盘超时 60s（" + rx.name + "），按未收全处理并强制关流");
                    break;
                }
                rx.flushLock.wait(Math.min(left, 200L));
            }
        }
        rx.writerAlive = false;
        synchronized (rx.flushLock) {
            rx.flushLock.notifyAll();
        }
        try {
            rx.sink.close();
        } catch (Exception ignore) {
        }
    }

    /** 取消接收：丢弃缓冲、关流、删半成品、清注册表 */
    void cancelRx(Rx rx) {
        rx.writerAlive = false;
        synchronized (rx.flushLock) {
            rx.pendingQ.clear();
            rx.flushLock.notifyAll();
        }
        synchronized (rx.slotLock) {
            rx.slotLock.notifyAll();
        }
        try {
            rx.sink.close();
        } catch (Exception ignore) {
        }
        try {
            if (rx.desc != null && !rx.desc.isEmpty()) {
                new File(rx.desc).delete();
            }
        } catch (Throwable ignore) {
        }
        if (L.store != null) {
            L.store.removePartial(rx.mid);
        }
        // 单任务槽：单文件取消即放槽（目录成员等整目录收尾/断链时统一释放）
        if (rx.dirId.isEmpty()) {
            L.releaseTask(rx.mid, false);
        }
    }

    /** 中止接收（区别于 cancelRx）：只停落盘线程、关流，**不删文件、不清注册表** —— 断链≠取消，半成品保留供续传 */
    void abortRx(Rx rx) {
        if (rx.dirId.isEmpty()) {
            L.releaseTask(rx.mid, false);
        }
        rx.writerAlive = false;
        synchronized (rx.flushLock) {
            rx.pendingQ.clear();
            rx.flushLock.notifyAll();
        }
        synchronized (rx.slotLock) {
            rx.slotLock.notifyAll();
        }
        try {
            rx.sink.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * 会话断开：中止该对端名下所有活动接收。文件与 partials 注册表原样保留 ——
     * 对端重连后 file_query 仍可命中续传；若对端永久放弃，过期清理会兜底回收。
     */
    void abortRxsFor(String peerIp) {
        List<Rx> aborted = new ArrayList<Rx>();
        synchronized (L.rxMap) {
            java.util.Iterator<Map.Entry<String, Rx>> it = L.rxMap.entrySet().iterator();
            while (it.hasNext()) {
                Rx rx = it.next().getValue();
                if (rx.peerIp.equals(peerIp)) {
                    aborted.add(rx);
                    it.remove();
                }
            }
        }
        for (Rx rx : aborted) {
            abortRx(rx);
        }
        // 单任务槽：断链兜底释放（文件夹成员不逐条放，只能按「对端+方向」一次性收口）
        L.releaseTaskFor(peerIp, "recv");
        // 所属目录记一次活动（未完成目录 10 分钟闲置回收的依据；重连续传会继续刷新它）
        for (Rx rx : aborted) {
            if (rx.dirId.isEmpty()) {
                continue;
            }
            DirRx dir;
            synchronized (L.dirMap) {
                dir = L.dirMap.get(rx.dirId);
            }
            if (dir != null) {
                synchronized (dir) {
                    dir.lastActivity = System.currentTimeMillis();
                }
            }
        }
        if (!aborted.isEmpty()) {
            L.log("会话断开，中止 " + aborted.size() + " 路接收（半成品保留，重传自动续传）");
        }
        // 顺带回收目录记录（未完成目录闲置 10 分钟兜底 / 成品超限量）——
        // 平时心跳线程每 5s 也会跑，断链此刻立即跑一次，不等下一个心跳
        pruneDirs();
        // 顺带回收超龄半成品：「取消帧丢失/中途废弃」的孤儿条目不必等到下次启动；
        // purge 只回收 ts 超 3 天的条目，不碰为续传/停滞保留的新鲜半成品，语义不冲突
        if (L.store != null) {
            L.store.purgeStalePartials(Link.PARTIAL_MAX_AGE_MS);
        }
    }

    /**
     * 接收停滞看门狗：某路接收 30s 无任何进度（对端半死但 TCP 心跳仍正常）时中止该路。
     * 由心跳线程每 {@link Link#STALL_SCAN_MS} 顺带调用（不新开线程）。
     *
     * 触发条件（全部满足）：
     *  1. now - lastProgressAt > 30s；
     *  2. 队列空 && !writeBusy（写盘线程手里没有缓冲，磁盘是活的）；
     *  3. 在 rxMap 锁内二次确认并**先摘通道** —— 之后的 file_done/file_cancel/onChunk
     *     都查不到这条 rx，不会与收尾竞态。
     */
    void scanStalledRx() {
        long now = System.currentTimeMillis();
        List<Rx> stalled = new ArrayList<Rx>();
        synchronized (L.rxMap) {
            java.util.Iterator<Map.Entry<String, Rx>> it = L.rxMap.entrySet().iterator();
            while (it.hasNext()) {
                Rx rx = it.next().getValue();
                if (now - rx.lastProgressAt > Link.STALL_TIMEOUT_MS
                        && rx.pendingQ.isEmpty() && !rx.writeBusy) {
                    stalled.add(rx);
                    it.remove();
                }
            }
        }
        for (Rx rx : stalled) {
            stallAbort(rx);
        }
    }

    /**
     * 停滞中止：断链 abortRx 的超时触发版，**不删文件、不清 partials** ——
     * 半成品保留，对端重连后 file_query 命中注册表自动续传。
     * 调用方（scanStalledRx）已在锁内把 mid 从 rxMap 摘掉。
     */
    void stallAbort(Rx rx) {
        // 1. 把双缓冲中已完整收到的字节全部落盘，进度最大化。
        //    正确性：发送按 CHUNK_SIZE 切片、seq 严格顺序，停滞时缓冲内容必为 CHUNK 对齐
        //    （只有文件最后一块才可能非对齐，而那会立刻 file_done）→ 落盘量满足 onFileQuery
        //    的 aligned 判定，续传 offset 有效。
        try {
            flushRxNow(rx);
        } catch (InterruptedException ignore) {
        }
        // 等最后一块写完再关流（writeBusy 时关流会在 sink.write() 中途截断）。
        // 加 10s 上限：万一磁盘恰好卡死，不让心跳线程被永久拖住（拖住会停掉断链检测）。
        synchronized (rx.flushLock) {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (!rx.pendingQ.isEmpty() || rx.writeBusy) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    rx.flushLock.wait(left);
                } catch (InterruptedException ignore) {
                    break;
                }
            }
        }
        // 2. 停写线程、关流（不删文件、不清注册表）
        abortRx(rx);
        // 文件夹成员不做字节续传：停滞就当场丢弃这半份，等 dir_end 清单对账后按文件粒度重发。
        // 不再置 PAUSED、不登记半成品 —— 「等待对端续传」那条提示对文件夹成员没有意义
        if (!rx.dirId.isEmpty()) {
            L.log("文件夹成员接收停滞，丢弃半成品，等清单对账后整文件重发：" + rx.name
                    + "（已收 " + rx.received + "/" + rx.size + " B）");
            return;
        }
        // 单文件：记「暂停过」标记 —— 下一个进度帧（file_meta 重到 / 续传首 chunk）
        // 到达时气泡回「发送中」。目录成员不需要：首个续传 chunk 本就把目录气泡发成 SENDING。
        if (rx.dirId.isEmpty()) {
            L.pausedMids.add(rx.mid);
        }
        // 3. 注册表最后补一次，确保 received 与磁盘一致
        if (L.store != null) {
            L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, rx.received);
        }
        // 4. 气泡暂停态 + 日志（边判定边落盘，实时可见）
        DirRx dir;
        synchronized (L.dirMap) {
            dir = rx.dirId.isEmpty() ? null : L.dirMap.get(rx.dirId);
        }
        if (dir != null) {
            synchronized (dir) {
                dir.lastActivity = System.currentTimeMillis();
            }
            int pct = dir.total > 0 ? (int) Math.min(99, (dir.doneBytes * 100) / dir.total) : -1;
            Msg m = new Msg();
            m.mid = dir.mid;
            m.peerIp = dir.peerIp;
            m.dir = Msg.DIR_PEER;
            m.kind = Msg.KIND_FILE;
            m.fileName = dir.name;
            m.fileSize = dir.total;
            m.filePath = dir.desc;
            m.progress = pct;
            m.isDir = true;
            m.dirCount = dir.count;
            m.status = Msg.ST_PAUSED;
            if (L.listener != null) {
                L.listener.onMessageUpdated(m);
            }
        } else {
            int pct = rx.size > 0 ? (int) Math.min(99, (rx.received * 100) / rx.size) : -1;
            Msg m = new Msg();
            m.mid = rx.mid;
            m.peerIp = rx.peerIp;
            m.dir = Msg.DIR_PEER;
            m.kind = Msg.KIND_FILE;
            m.fileName = rx.name;
            m.fileSize = rx.size;
            m.filePath = rx.desc;
            m.progress = pct;
            m.status = Msg.ST_PAUSED;
            if (L.listener != null) {
                L.listener.onMessageUpdated(m);
            }
        }
        int logPct = rx.size > 0 ? (int) (rx.received * 100 / rx.size) : -1;
        L.log("接收停滞 30s 已暂停 mid=" + rx.mid + " name=" + rx.name
                + " 已收 " + rx.received + "/" + rx.size + " B"
                + (logPct >= 0 ? " (" + logPct + "%)" : "")
                + " 半成品=" + rx.desc + " 等待对端重连续传");
    }

    /** 新建接收通道。dirId 非空时落到目录成员路径；startAt>0 时以追加方式打开半成品 */
    private Rx createRx(String mid, String name, long size, String peerIp, String relPath,
                        String dirId, long startAt, String pathHint) throws Exception {
        return createRx(mid, name, size, peerIp, relPath, dirId, startAt, pathHint, false);
    }

    /**
     * 2 期：多通道开传声明（在 file_meta 之前）。只登记该 mid 的通道数，
     * 真正建重排环是在 file_meta 建 Rx 时按 {@link Link#multiMids} 决定。
     */
    void onXeBegin(Session s, Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        if (mid.isEmpty()) {
            return;
        }
        int unit = Json.integer(j, "unit", Protocol.CHUNK_SIZE);
        int lanes = Json.integer(j, "lanes", 1);
        if (unit != Protocol.CHUNK_SIZE) {
            L.log("多通道声明单元 " + unit + "B 与本端 " + Protocol.CHUNK_SIZE + "B 不一致，该文件按单通道收");
            return;
        }
        if (lanes < 2) {
            return;
        }
        L.multiMids.put(mid, Integer.valueOf(lanes));
        L.log("多通道接收声明 mid=" + mid + " lanes=" + lanes + " unit=" + unit);
    }

    /** 同目录成员撞名时按序号去重："a.txt" → "a (2).txt"；"a_.txt" → "a_ (2).txt" */
    static String withOrdinalRel(String rel, int n) {
        int slash = rel.lastIndexOf('/');
        String dirPart = slash >= 0 ? rel.substring(0, slash + 1) : "";
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return dirPart + base + " (" + n + ")" + ext;
    }

    private Rx createRx(String mid, String name, long size, String peerIp, String relPath,
                        String dirId, long startAt, String pathHint, boolean multi) throws Exception {
        // L.dirMap 会被多条会话的读线程并发访问，必须 synchronized（与 Android 端一致）
        DirRx dir;
        synchronized (L.dirMap) {
            dir = dirId.isEmpty() ? null : L.dirMap.get(dirId);
        }
        OutputStream sink;
        String path;
        if (startAt > 0) {
            path = pathHint != null ? pathHint
                    : (L.store != null && L.store.getPartial(mid) != null
                    ? Json.str(L.store.getPartial(mid), "path", "") : "");
            sink = new FileOutputStream(new File(path), true);
        } else if (dir != null) {
            // 相对路径逐段清洗（防路径穿越 + NTFS 非法字符）。对端（尤其 Android）的文件名
            // 可能带 `:` `*` `?` 等 Windows 建不出来的字符 —— 直接 new File 会在打开输出流时
            // 抛异常，该成员就此静默失败 = 用户看到「文件夹里少了文件」。
            String rel = LinkNames.sanitizeRelPath(relPath);
            if (rel.isEmpty()) {
                rel = LinkNames.sanitize(name);
            }
            // 两个不同成员清洗后可能撞成同一路径（"a?.txt" 与 "a*.txt" → "a_.txt"）：
            // 各自加序号落盘，否则后到的会覆盖前一个 → 对账把前者判成缺失
            synchronized (dir) {
                String uniq = rel;
                int n = 1;
                while (!dir.usedRel.add(uniq.toLowerCase(java.util.Locale.ROOT))) {
                    n++;
                    if (n > 5000) {
                        uniq = rel + "." + System.nanoTime();
                        dir.usedRel.add(uniq.toLowerCase(java.util.Locale.ROOT));
                        break;
                    }
                    uniq = withOrdinalRel(rel, n);
                }
                File target = new File(dir.folder, uniq);
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                path = target.getAbsolutePath();
                sink = new FileOutputStream(target, false);
            }
        } else {
            // 保存目录可由用户在 PC 端设置（默认 pc/received）；原子创建不重名文件，不覆盖
            File target = LinkNames.createUniqueFile(L.store.receivedDir().toFile(), LinkNames.sanitize(name));
            path = target.getAbsolutePath();
            sink = new BufferedOutputStream(new FileOutputStream(target));
        }
        Rx rx = new Rx(mid, name, size, peerIp, dirId, relPath, path, sink, startAt, multi);
        if (!dirId.isEmpty()) {
            // 成员 mid 登记：file_query 帧不带 dirId，靠这张表才不会把缺失成员的重发
            // 误判成「重复投递」（见 dupSavedPath）
            L.memberOfMid.put(mid, dirId);
        }
        // P4-1：续传不再同步读取整段前缀算 SHA-256（1GB 半成品会卡住网络读线程 → 触发 35s 判死），
        // 前缀正确性由 32MB 分块校验在 file_query 阶段逐块核对
        if (L.store != null) {
            L.store.putPartial(mid, path, name, size, rx.received);
        }
        rx.writer = startWriterThread(rx);
        return rx;
    }

    /**
     * 本次运行内这条 mid 是否已**完整收妥**（普通单文件）。是则返回落盘路径，否则空串。
     *
     * file_query 只查这张内存表：那一帧不带 dirId，光看它分不清「这条 mid 是单文件还是
     * 某个文件夹的成员」，拿历史当判据会把 dir_missing 的缺失成员补发误判成重复
     *（打成永远收不齐的文件夹）。历史判据留在 {@link #dupSavedPath}，只在
     * dirId 为空的 file_meta 上生效。
     */
    private String savedThisRun(String mid, long declaredSize) {
        if (mid == null || mid.isEmpty()) {
            return "";
        }
        String p = L.savedFiles.get(mid);
        return sameSizeOnDisk(p, declaredSize) ? p : "";
    }

    /**
     * 重复投递判据（内存表 + 历史兜底），供 file_meta 用。
     *
     * 历史兜底只兜「PC 重启后对端仍按原 mid 补发」这一类；都要求文件仍在磁盘上 ——
     * 用户把收到的文件删了就该允许他重收一次。文件夹成员（{@link Link#memberOfMid}
     * 登记过）一律判非重复，它们的去重由 DirRx 的 got/bad 记账负责。
     *
     * @param declaredSize 这一帧声明的文件大小；与磁盘上的不等就说明内容变了
     *                     （同一 mid 换了文件），按新文件重收而不是判重复
     */
    private String dupSavedPath(String peerIp, String mid, long declaredSize) {
        String p = savedThisRun(mid, declaredSize);
        if (!p.isEmpty()) {
            return p;
        }
        if (mid == null || mid.isEmpty() || L.memberOfMid.containsKey(mid) || L.store == null) {
            return "";
        }
        String hp = L.store.savedFileOf(peerIp, mid);
        return sameSizeOnDisk(hp, declaredSize) ? hp : "";
    }

    /** 落盘文件还在、且大小与对端声明一致（声明 ≤0 = 对端未知大小，只要求文件还在） */
    private boolean sameSizeOnDisk(String path, long declaredSize) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        long onDisk = partialSize(path);
        return onDisk > 0L && (declaredSize <= 0L || onDisk == declaredSize);
    }

    /**
     * 对端开传前问本端有没有半成品。三种情况：
     *  1. 活动通道还在（上次会话刚断）→ 冲掉未落盘缓冲，原地续传；
     *  2. 注册表里有半成品（本端重启过）→ 追加打开、重算前缀摘要，准备好续传通道；
     *  3. 都没有 → 从头传。
     *
     * 在 1 之前还有一道**重复投递闸门**：这条 mid 已经收妥过就直接报
     * 「已有整个文件」，对端随即只发 file_meta + file_done、一个分片都不发，本端在
     * file_meta 处丢弃 —— 不再产生「xxx (1)」副本，也不再叠一条托盘通知。
     */
    void onFileQuery(Session s, Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        String name = Json.str(j, "name", "file");
        long size = Json.lng(j, "size", -1L);
        Rx live;
        synchronized (L.rxMap) {
            live = L.rxMap.get(mid);
        }
        if (live != null) {
            try {
                flushRxNow(live);
            } catch (InterruptedException ignore) {
            }
            L.log("断点续传查询 " + name + "：本端已有 " + live.received + " B（活动通道）");
            L.trySend(s, Protocol.fileResume(mid, true, live.received, ""));
            return;
        }
        String dupAt = savedThisRun(mid, size);
        if (!dupAt.isEmpty()) {
            // 报「整份已在」而不是「从头传」：对端据此一个分片都不发，省掉一次全量重传
            L.log("重复投递 " + name + "（mid=" + mid + "）本端已存为 " + dupAt
                    + "，按已收完整答复");
            L.trySend(s, Protocol.fileResume(mid, true, size > 0 ? size : partialSize(dupAt), ""));
            return;
        }
        if (L.store != null) {
            Map<String, Object> p = L.store.getPartial(mid);
            if (p != null) {
                String path = Json.str(p, "path", "");
                long avail = partialSize(path);
                // 续传先按 32MB 块边界对齐（末尾不足一块的「撕裂块」整块重发）；
                // 再等发送端发起大小校验（blk_verify），核验通过后截断落盘并建续传通道
                long aligned = (avail > 0 && avail <= size)
                        ? (avail / Protocol.BLOCK_SIZE) * Protocol.BLOCK_SIZE : 0L;
                if (!path.isEmpty() && aligned > 0) {
                    L.resumePrep.put(mid, new Link.ResumePrep(mid, name, size, s.remoteIp(), path));
                    L.log("断点续传查询 " + name + "：半成品 " + avail + " B，块边界对齐到 "
                            + aligned + "，等发送端大小校验");
                    L.trySend(s, Protocol.fileResume(mid, true, aligned, "", true));
                    return;
                } else {
                    // 半成品不可续（不足 32MB 或非本端留存）：删掉再清台账，别留垃圾文件
                    new File(path).delete();
                    L.store.removePartial(mid);
                }
            }
        }
        L.trySend(s, Protocol.fileResume(mid, false, 0L, ""));
    }

    /**
     * 接收端收到发送端的大小校验请求：按 32MB 块核对 [0, offset) 是否都在盘上（只量大小，不做 hash），
     * 截断半成品到最后一个完整块边界，建 Rx 并回 blk_ack（带实际核验偏移）。
     * 截断失败 → 删半成品、回 false，让发送端从 0 重传（绝不 append 到脏尾部）。
     */
    void onBlkVerify(Session s, Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        long offset = Json.lng(j, "offset", 0L);
        Link.ResumePrep prep = L.resumePrep.remove(mid);
        if (prep == null) {
            L.trySend(s, Protocol.blkAck(mid, -1, offset > 0L, offset));
            return;
        }
        long target = Math.max(0L, Math.min(offset, prep.size));
        if (target <= 0L) {
            if (L.store != null) {
                L.store.removePartial(mid);
            }
            // 注册表+磁盘文件都要清，否则留孤儿（与下方截断失败分支同口径）
            new File(prep.path).delete();
            L.log("续传改为从头接收 " + prep.name + "（旧半成品已删除）");
            L.trySend(s, Protocol.blkAck(mid, -1, true, 0L));
            return;
        }
        // 大小校验：盘上实际字节不够覆盖请求前缀时，退到最后一个完整 32MB 块边界
        //（只 stat 一次文件大小，逐块覆盖判定 = 大小比较，不读内容、不算 hash）
        long disk = partialSize(prep.path);
        long verified = (Math.min(target, disk) / Protocol.BLOCK_SIZE) * Protocol.BLOCK_SIZE;
        if (verified < target) {
            L.log("大小校验 " + prep.name + "：盘上 " + disk + " B 不足请求前缀 " + target
                    + " B，退到块边界 " + verified);
        }
        if (verified <= 0L) {
            if (L.store != null) {
                L.store.removePartial(mid);
            }
            new File(prep.path).delete();
            L.log("大小校验：半成品不足一个完整块（" + prep.name + "），改从头接收");
            L.trySend(s, Protocol.blkAck(mid, -1, true, 0L));
            return;
        }
        boolean truncated;
        try {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(new File(prep.path), "rw")) {
                raf.setLength(verified);
            }
            truncated = true;
        } catch (Throwable t) {
            L.log("半成品截断失败（" + prep.name + "）：" + t);
            truncated = false;
        }
        if (!truncated) {
            if (L.store != null) {
                L.store.removePartial(mid);
            }
            new File(prep.path).delete();
            L.trySend(s, Protocol.blkAck(mid, -1, false, 0L));
            return;
        }
        try {
            Rx rx = createRx(mid, prep.name, prep.size, prep.peerIp, "", "", verified, prep.path);
            synchronized (L.rxMap) {
                L.rxMap.put(mid, rx);
            }
            L.log("续传就绪 " + prep.name + "：大小核验通过，已截断到 " + verified + " B，等对端从该块起发");
            L.trySend(s, Protocol.blkAck(mid, -1, true, verified));
        } catch (Throwable t) {
            L.log("续传通道创建失败（" + t + "），改从头接收 " + prep.name);
            if (L.store != null) {
                L.store.removePartial(mid);
            }
            new File(prep.path).delete();
            L.trySend(s, Protocol.blkAck(mid, -1, false, 0L));
        }
    }

    /** 发送端：收到大小核验结果（ok + 实际核验偏移） */
    void onBlkAck(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        ArrayBlockingQueue<Link.BlkAckResult> q = L.blkAckWaiters.get(mid);
        if (q != null) {
            q.offer(new Link.BlkAckResult(Json.bool(j, "ok", false), Json.lng(j, "offset", 0L)));
        }
    }

    void onFileResume(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        boolean ok = Json.bool(j, "ok", false);
        long offset = ok ? Json.lng(j, "offset", 0L) : 0L;
        L.resumeNeedBlk.put(mid, Boolean.valueOf(ok && Json.bool(j, "needBlk", false) && offset > 0L));
        ArrayBlockingQueue<Long> q = L.resumeWaiters.get(mid);
        if (q != null) {
            q.offer(Long.valueOf(offset));
        }
    }

    /**
     * 发送端：续传文件发完后启动核对 —— 等对端回发**落盘文件字节数**，与清单声明大小比对。
     * 整文件按一个验证单元计，10s 超时（对端没回发）只记日志跳过，绝不阻塞、不改传输状态。
     */
    void startVerifyWatch(Msg item, long resumedFrom) {
        final VerifyState st = new VerifyState(item.mid, item);
        if (L.verifyMap.putIfAbsent(item.mid, st) != null) {
            return;
        }
        Link.daemon(() -> {
            try {
                Thread.sleep(L.VERIFY_TIMEOUT_MS);
            } catch (InterruptedException ignore) {
            }
            if (!st.finished) {
                st.finished = true;
                L.verifyMap.remove(item.mid);
                L.log("大小核对超时 " + item.fileName
                        + "（对端未回发落盘字节数，自 " + resumedFrom + " B 续传），跳过");
            }
        }, "pp-verify-timeout");
        L.log("续传文件发送完成，等待对端大小核对 " + item.fileName + "（自 " + resumedFrom + " B 起）");
    }

    /** 接收端回发的落盘字节数到了 → 与清单声明大小比对 */
    void onFileVerify(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        VerifyState st = L.verifyMap.get(mid);
        if (st == null) {
            L.log("收到大小核对帧但无在途核对（mid=" + mid + " 已超时/非续传/旧版本对端），忽略");
            return;
        }
        st.peerSize = Json.lng(j, "size", -1L);
        compareVerify(st);
    }

    /** 核对一次性收尾（finished 原子防重入）；大小不符 → 气泡标失败提醒用户重传 */
    void compareVerify(final VerifyState st) {
        synchronized (st) {
            long peer = st.peerSize;
            if (st.finished || peer < 0L) {
                return;
            }
            st.finished = true;
            L.verifyMap.remove(st.mid);
            long mine = st.item.fileSize;
            if (mine <= 0L) {
                L.log("大小核对 " + st.item.fileName + "：对端落盘 " + peer + " B（清单大小未知，跳过比对）");
            } else if (peer == mine) {
                L.log("大小核对一致 " + st.item.fileName + "（" + peer + " B）");
            } else {
                L.log("大小核对不一致 " + st.item.fileName + "：清单 " + mine + " B / 对端落盘 " + peer + " B");
                // 气泡标失败提醒用户重传；接收端文件已在，是否删除由用户决定
                Msg u = new Msg();
                u.mid = st.mid;
                // 归属带上：不带的话界面归属不明会把这条失败标记画进当前会话
                u.peerIp = st.item == null ? "" : st.item.peerIp;
                u.dir = Msg.DIR_SELF;
                u.kind = Msg.KIND_FILE;
                u.fileName = st.item.fileName;
                u.fileSize = st.item.fileSize;
                u.status = Msg.ST_FAILED;
                Link.Listener l = L.listener;
                if (l != null) {
                    l.onMessageUpdated(u);
                }
            }
        }
    }

    static long partialSize(String path) {
        if (path == null || path.isEmpty()) {
            return 0L;
        }
        File f = new File(path);
        return f.isFile() ? f.length() : 0L;
    }

    /** 对端取消了某个文件/文件夹的发送：中止接收、删除半成品、把该条从界面移除。 */
    void onRemoteFileCancel(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        // 对端明确「这条不要了」：本端若是发送方立刻让发送循环停下（cancelled 是一次性信号）；
        // 另外记取消墓碑 + 出待发队列 —— 否则排队的补发任务会把同一条又发回去
        L.cancelled.add(mid);
        L.markCanceled(mid);
        if (L.store != null) {
            L.store.removePending(mid);
        }
        DirRx dir;
        synchronized (L.dirMap) {
            dir = L.dirMap.remove(mid);
        }
        if (dir != null) {
            // 取消整个文件夹：停掉所有成员的接收、删除目录（含已收成员与半成品）、移除气泡
            List<Rx> members = new ArrayList<Rx>();
            synchronized (L.rxMap) {
                for (Rx rx : L.rxMap.values()) {
                    if (mid.equals(rx.dirId)) {
                        members.add(rx);
                    }
                }
                for (Rx rx : members) {
                    L.rxMap.remove(rx.mid);
                }
            }
            for (Rx rx : members) {
                cancelRx(rx);
            }
            LinkNames.deleteRecursively(dir.folder);
            L.log("对端取消文件夹 " + dir.name + "，已移除");
            L.releaseTask(mid, false);
            Link.Listener l = L.listener;
            if (l != null) {
                l.onMessageRemoved(mid);
            }
            return;
        }
        Rx rx;
        synchronized (L.rxMap) {
            rx = L.rxMap.remove(mid);
        }
        if (rx != null) {
            cancelRx(rx);
            L.log("对端取消文件 " + rx.name + "，已删除半成品并移除");
        } else {
            L.log("对端取消文件传输 " + mid + "（本端未在接收，仅移除展示）");
        }
        Link.Listener l = L.listener;
        if (l != null) {
            l.onMessageRemoved(mid);
        }
    }

    void onFileFrame(Session s, Map<String, Object> j) {
        String type = Json.str(j, "type", "");
        if ("file_meta".equals(type)) {
            String mid = Json.str(j, "mid", "");
            String name = Json.str(j, "name", "file");
            long size = Json.lng(j, "size", -1L);
            long offset = Json.lng(j, "offset", 0L);
            String relPath = Json.str(j, "relPath", "");
            String dirId = Json.str(j, "dirId", "");
            try {
                if (L.store == null) {
                    return;
                }
                Rx live;
                synchronized (L.rxMap) {
                    live = L.rxMap.get(mid);
                }
                Rx rx;
                if (live == null) {
                    // 重复投递闸门：这条 mid 已经完整收妥过，就绝不再
                    // 建第二条接收通道 —— 否则 createRx 会挑「xxx (1)」这个新名字落一份副本，
                    // 用户看到的正是「发 2 个文件、文件夹里躺 5 个」+ 一串托盘通知。
                    // 只对普通单文件生效：文件夹成员的去重交给 DirRx（目录本身按 mid 复用），
                    // 拦成员会连带堵死 dir_missing 的缺失成员补发。
                    if (dirId.isEmpty()) {
                        String dupAt = dupSavedPath(s.remoteIp(), mid, size);
                        if (!dupAt.isEmpty()) {
                            L.log("重复投递 " + name + "（mid=" + mid + "）已存为 " + dupAt
                                    + "，跳过落盘");
                            L.trySend(s, Protocol.ack(mid, true));
                            return;
                        }
                    }
                    // 单任务槽（与安卓端同口径）：本机同一时刻只传一个任务，一个文件夹整体算一个。
                    // ① 成员撞「已被回绝过的目录」→ 直接回 busy，必须先拦，否则下面的兜底会替它建目录
                    if (!dirId.isEmpty() && L.isBusyId(dirId)) {
                        L.log("目录 " + dirId + " 本机已回绝（忙），成员 " + name + " 直接回 busy");
                        L.trySend(s, Protocol.busy(mid, name, L.busyHolder(), "member"));
                        return;
                    }
                    String gateId = dirId.isEmpty() ? mid : dirId;
                    if (!L.claimTask(gateId, s.remoteIp(), "recv",
                            dirId.isEmpty() ? name : "文件夹 " + dirId)) {
                        L.log("本机正忙（" + L.busyHolder() + "），回 busy 拒绝接收 " + name
                                + "（mid=" + mid + "）");
                        if (!dirId.isEmpty()) {
                            L.markBusyId(dirId);
                        }
                        L.trySend(s, Protocol.busy(mid, name, L.busyHolder(),
                                dirId.isEmpty() ? "file" : "member"));
                        return;
                    }
                    // dir_meta 丢失的兜底：隐式建目录记录（正常流程 dir_meta 先到）。
                    // containsKey→put 必须整体在锁内，否则两台设备同时发文件夹会竞态
                    if (!dirId.isEmpty()) {
                        synchronized (L.dirMap) {
                            if (!L.dirMap.containsKey(dirId)) {
                                File folder = LinkNames.createUniqueDir(L.store.receivedDir().toFile(), LinkNames.segSanitize(dirId));
                                L.dirMap.put(dirId, new DirRx(dirId, dirId, s.remoteIp(), 0, 0L, folder,
                                        folder.getAbsolutePath()));
                            }
                        }
                    }
                    Integer mt = L.multiMids.remove(mid);
                    rx = createRx(mid, name, size, s.remoteIp(), relPath, dirId, 0L, null,
                            mt != null && mt.intValue() >= 2);
                    synchronized (L.rxMap) {
                        L.rxMap.put(mid, rx);
                    }
                } else if (offset <= 0L || offset != live.received) {
                    // 对端要求从头（或偏移对不上，如前缀校验失败）：截断重传
                    L.log("file_meta 偏移 " + offset + " 与本端 " + live.received
                            + " 不符，" + live.name + " 从头重收");
                    resetRxFromZero(live);
                    rx = live;
                } else {
                    // 同一会话内「暂停→续发」：偏移接得上，接着收；但发送端每次尝试的帧序号都从 0 重来
                    live.expectSeq = 0;
                    rx = live;
                }
                // Rx 已建好并登记进 rxMap → 通知发送端「可以起数据通道了」。
                // 必须在这一步之后才允许对端发分片：早到的分片会因 rxMap 里查不到 mid 被丢弃，
                // 缺口补齐不了就会把 3 槽重排环卡到 30s 超时（2 期首测 1GB 卡死）。
                if (rx.multi) {
                    L.trySend(s, Protocol.xeReady(mid));
                }
                DirRx dir;
                synchronized (L.dirMap) {
                    dir = rx.dirId.isEmpty() ? null : L.dirMap.get(rx.dirId);
                }
                if (dir == null) {
                    // 从「已暂停」恢复：file_meta 重到 → 气泡回「发送中」（清标记）
                    L.pausedMids.remove(mid);
                    Msg m = new Msg();
                    m.mid = mid;
                    // ⚠️ 归属必须打上：这一条是**普通文件接收的第一条气泡**，
                    //    原来漏了 peerIp → 界面的归属判据（空 = 放行）会把别的设备发来的文件
                    //    画进当前打开的会话里 —— 正是用户报的「不同设备聊天窗口混在一起」。
                    m.peerIp = s.remoteIp();
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_FILE;
                    m.fileName = name;
                    m.fileSize = size;
                    m.progress = 0;
                    // 在途就是 SENDING —— 之前新接收标成 ST_OK，PcMain 立即按终态落历史并弹
                    // 「文件已保存」托盘：传输还在跑，界面却已是完成态，进度条永远停在 0%
                    m.status = Msg.ST_SENDING;
                    m.ts = Json.lng(j, "ts", System.currentTimeMillis());
                    L.emit(m);
                }
                String resumeNote = rx.startAt > 0 ? "，续传自 " + rx.startAt + " B" : "";
                L.log("开始接收文件 " + name + " (" + (size < 0 ? "未知大小" : size + " B") + resumeNote + ")");
            } catch (Throwable t) {
                L.log("创建接收文件失败: " + t);
                L.releaseTask(mid, false);   // 建通道失败：把刚占的单任务槽放掉，别把本机钉在"忙"
                L.trySend(s, Protocol.ack(mid, false));
            }
            return;
        }
        // file_done
        String mid = Json.str(j, "mid", "");
        Rx rx;
        synchronized (L.rxMap) {
            rx = L.rxMap.remove(mid);
        }
        if (rx == null) {
            // 零分片直发 file_done（对端判定本端已有整份文件）时本端没有 rx；只要历史里确实存着
            // 成品就必须回 ack，否则发送端会把已送达的文件判失败并重发整份
            long have = (L.store != null) ? L.store.savedCompleteSize(s.remoteIp(), mid) : -1L;
            long declared = Json.lng(j, "size", -1L);
            boolean ok = have >= 0L && (declared <= 0L || have == declared);
            L.log("收到无通道 file_done（" + mid + "）：成品 " + (have < 0 ? "不存在" : have + "B")
                    + "，声明 " + declared + " → " + (ok ? "回 ack 成功" : "回 ack 失败"));
            L.trySend(s, Protocol.ack(mid, ok));
            return;
        }
        boolean ioOk = true;
        try {
            finishRx(rx);
        } catch (Throwable t) {
            ioOk = false;
            L.log("落盘收尾失败 " + rx.name + ": " + t);
        }
        // 落盘实测：内存记账校的是「网络收到的字节」，磁盘被写空/截断它照样判通过，
        // 所以关流后必须再量一次磁盘真实字节数（修完 writeBusy 后这里应当恒等）
        long diskSize = diskSizeOf(rx);
        boolean sizeOk = diskSize < 0 || diskSize == rx.received;
        if (!sizeOk) {
            ioOk = false;
            L.log("落盘字节数不符 " + rx.name + "：磁盘 " + diskSize + "B ≠ 应收 " + rx.received + "B");
        }
        // 再和「对端声明的文件大小」比一次（与 Android 端同口径）：发送端源被截断时
        // 实收字节照样自洽，只判「收少了」；声明值偏小不判死，免得把能用的传输打成失败。
        if (rx.size > 0 && rx.received < rx.size) {
            ioOk = false;
            L.log("实收字节少于声明 " + rx.name + "：实收 " + rx.received + "B < 声明 " + rx.size + "B");
        }
        // 校验只核对大小（不做 hash）：落盘成功且字节数相符即通过；
        // 失败时半成品保留（续传来的仍可继续续传，超龄由过期清理兜底）
        if (ioOk && L.store != null) {
            L.store.removePartial(mid);
        }
        DirRx dir;
        synchronized (L.dirMap) {
            dir = rx.dirId.isEmpty() ? null : L.dirMap.get(rx.dirId);
        }
        if (dir != null) {
            String status;
            int pct;
            synchronized (dir) {
                // 按清单大小核对（dir_meta.sizes）：成员实收字节与清单不符 → 判未收妥
                Long expectSize = dir.expectSizes.get(rx.relPath);
                if (expectSize != null && expectSize.longValue() > 0
                        && rx.received != expectSize.longValue()) {
                    ioOk = false;
                    L.log("成员大小与清单不符 " + rx.name + "：实收 " + rx.received
                            + "B ≠ 清单 " + expectSize + "B");
                }
                dir.doneFiles++;
                dir.lastActivity = System.currentTimeMillis();
                // 逐项记账：成功的进 got、落盘/大小核对失败的进 bad —— dir_end 核对的依据
                if (!rx.relPath.isEmpty()) {
                    if (ioOk) {
                        dir.got.add(rx.relPath);
                    } else {
                        dir.bad.add(rx.relPath);
                    }
                }
                if (!ioOk) {
                    dir.failed = true;
                }
                boolean allDone = dir.doneFiles >= dir.count;
                if (allDone && dir.failed) {
                    status = Msg.ST_FAILED;
                } else if (allDone) {
                    status = Msg.ST_OK;
                } else {
                    status = Msg.ST_SENDING;
                }
                pct = allDone ? 100
                        : (dir.total > 0 ? (int) ((dir.doneBytes * 100) / dir.total) : -1);
                Msg m = new Msg();
                m.mid = dir.mid;
                m.peerIp = dir.peerIp;
                m.dir = Msg.DIR_PEER;
                m.kind = Msg.KIND_FILE;
                m.fileName = dir.name;
                m.fileSize = dir.total;
                m.filePath = dir.desc;
                m.progress = pct;
                m.isDir = true;
                m.dirCount = dir.count;
                m.status = status;
                if (L.listener != null) {
                    L.listener.onMessageUpdated(m);
                }
                // 数量收齐只是**兜底**判据（老发送端不发 dir_end）；新发送端一律以 dir_end 核对为准。
                // 目录**不从 L.dirMap 移除**：成品目录保留给「重发 / 补发」复用 —— 否则同一
                // 文件夹重传会另建「文件夹 (1)」，用户看到的正是「原文件夹里少了文件」。
                // 保留态由 pruneDirs() 限量回收。
                if (allDone) {
                    dir.finished = true;
                }
            }
            L.log("文件夹成员完成 " + rx.name + "（" + dir.doneFiles + "/" + dir.count
                    + "）大小核对=" + (ioOk ? "通过" : "失败"));
        } else {
            // 单个文件收完：丢掉速率采样器（气泡按「样本过期」自动隐藏数字）
            Speeds.end(mid);
            if (rx.dirId.isEmpty() && ioOk) {
                // 登记「这条 mid 已完整收妥 + 落在哪」—— 同一 mid 之后被待发补发/hsync
                // 再投一次时，就在这里留下判据（Link.savedFiles），不再落第二份副本
                L.savedFiles.put(mid, rx.desc);
            }
            Msg m = new Msg();
            m.mid = mid;
            m.peerIp = rx.peerIp;
            m.dir = Msg.DIR_PEER;
            m.kind = Msg.KIND_FILE;
            m.fileName = rx.name;
            m.fileSize = rx.received;
            m.filePath = rx.desc;
            m.progress = 100;
            m.status = ioOk ? Msg.ST_OK : Msg.ST_FAILED;
            m.ts = System.currentTimeMillis();
            // 接收完成必须走 updated（与文件夹一致）：走 added 会被 onMessageAdded 的
            // mids.contains(mid) 拦截，气泡停留在 file_meta 阶段（filePath 为空、无缩略图），
            // 导致图片缩略图、正确大小只有重启重载历史后才出现。
            if (L.listener != null) {
                L.listener.onMessageUpdated(m);
            }
            L.log("文件接收完成 " + rx.name + " " + rx.received + " B 大小核对="
                    + (ioOk ? "通过" : "失败（落盘/字节数未过）"));
        }
        L.trySend(s, Protocol.ack(mid, ioOk));
        // 触发过续传的普通单文件：把落盘实际字节数回发对端，与清单大小核对（大小校验，不做 hash）
        if (ioOk && rx.startAt > 0 && rx.dirId.isEmpty()) {
            long sz = diskSize >= 0 ? diskSize : rx.received;
            L.trySend(s, Protocol.fileVerify(mid, sz));
            L.log("已回发续传文件大小（mid=" + mid + "，" + sz + " B）");
        }
        // 单任务槽：普通单文件收完（ack 已回）就放槽；文件夹成员的槽由 finishDirReceiving 统一释放
        if (rx.dirId.isEmpty()) {
            L.releaseTask(mid, true);
        }
    }

    /** 截断重来：停写线程 → 等缓冲清空 → 以截断方式重开 → 状态归零 → 重启写线程 */
    void resetRxFromZero(Rx rx) {
        rx.writerGen++;
        rx.writerAlive = false;
        synchronized (rx.flushLock) {
            rx.flushLock.notifyAll();
        }
        if (rx.writer != null) {
            try {
                rx.writer.join(2000);
            } catch (InterruptedException ignore) {
            }
        }
        synchronized (rx.flushLock) {
            rx.pendingQ.clear();
            // 换一套全新缓冲：旧写线程可能还卡在 sink.write 里握着旧块，复用旧池会重演「读写同块」竞态
            rx.rebuildBufPool();
            rx.received = 0;
            rx.expectSeq = 0;
            rx.lastPct = -1;
            // P2-3（同步安卓）：多通道状态必须一起归零。只清 expectSeq 的话，旧的单元窗/槽位仍站在原地：
            // 截断重来后第一批单元会被「seq < expectUnit」直接丢掉，槽里还留着上一轮的半截数据。
            rx.expectUnit = 0;
            java.util.Arrays.fill(rx.slotState, 0);
            java.util.Arrays.fill(rx.slotUnit, -1);
            java.util.Arrays.fill(rx.slotLen, 0);
            try {
                rx.sink.close();
            } catch (Exception ignore) {
            }
            try {
                rx.sink = new FileOutputStream(new File(rx.desc), false);
            } catch (Exception e) {
                L.log("重开半成品失败 " + rx.name + ": " + e);
            }
            rx.writerAlive = true;
        }
        rx.writer = startWriterThread(rx);
        rx.partialRegBytes = 0L;
        if (L.store != null) {
            L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, 0);
        }
    }

    /** 二进制分片到达：按 seq 顺序追加进缓冲池，进度按需更新气泡 */
    void onChunk(Session s, String mid, int seq, byte[] data, int len) {
        onChunk(s, mid, seq, data, len, 0, false);
    }

    /**
     * 二进制分片到达：按 seq 顺序追加进缓冲池，进度按需更新气泡。
     * direct = true 表示载荷已由读循环直读进 data 的 off 处（P1-3 快路径，无分配无拷贝）。
     */
    void onChunk(Session s, String mid, int seq, byte[] data, int len, int off, boolean direct) {
        Rx rx;
        synchronized (L.rxMap) {
            rx = L.rxMap.get(mid);
        }
        if (rx == null) {
            // 不能静默丢：多通道下这就是「单元缺口」的来源（发送端以为发了，接收端当没到）
            L.log("丢弃分片：mid=" + mid + " 无接收上下文（seq=" + seq + " len=" + len + "）");
            return;
        }
        if (rx.multi) {
            // 多通道：按单元窗口收，乱序是常态（不同 lane 各自推进），不能按 expectSeq 判
            if (seq < rx.expectUnit) {
                return;
            }
        } else {
            if (rx.expectSeq != seq) {
                if (!direct) {
                    L.log("分片乱序（mid=" + mid + " seq=" + seq + " 期望 " + rx.expectSeq + "），忽略");
                }
                return;
            }
            if (!direct) {
                rx.expectSeq++;
            }
        }
        try {
            if (rx.multi) {
                if (direct) {
                    commitUnitSlot(rx, seq, len);
                } else {
                    copyIntoSlot(rx, seq, data, off, len);
                }
            } else if (direct) {
                commitDirectChunk(rx, off, len);
            } else {
                appendToRx(rx, data, len, off);
            }
            rx.lastProgressAt = System.currentTimeMillis();
            DirRx dir;
            synchronized (L.dirMap) {
                dir = rx.dirId.isEmpty() ? null : L.dirMap.get(rx.dirId);
            }
            if (dir != null) {
                synchronized (dir) {
                    dir.doneBytes += len;
                    dir.lastActivity = System.currentTimeMillis();
                    // 速率采样（接收侧，与发送侧同口径）：文件夹用聚合字节数
                    Speeds.sample(dir.mid, dir.doneBytes);
                    if (dir.total > 0) {
                        int pct = (int) ((dir.doneBytes * 100) / dir.total);
                        if (pct > 99) {
                            pct = 99;
                        }
                        // UI 帧限流：同单文件进度帧
                        long nowUi = System.currentTimeMillis();
                        if (pct != dir.lastPct && nowUi - dir.lastUiAt >= UI_THROTTLE_MS) {
                            dir.lastPct = pct;
                            dir.lastUiAt = nowUi;
                            Msg m = new Msg();
                            m.mid = dir.mid;
                            m.peerIp = dir.peerIp;   // 归属
                            m.dir = Msg.DIR_PEER;
                            m.kind = Msg.KIND_FILE;
                            m.fileName = dir.name;
                            m.fileSize = dir.total;
                            m.filePath = dir.desc;
                            m.progress = pct;
                            m.isDir = true;
                            m.dirCount = dir.count;
                            m.status = Msg.ST_SENDING;
                            if (L.listener != null) {
                                L.listener.onMessageUpdated(m);
                            }
                        }
                    }
                }
            } else if (rx.size > 0) {
                // 速率采样（接收侧）：写盘字节数已由 appendToRx 累加
                Speeds.sample(mid, rx.received);
                int pct = (int) ((rx.received * 100) / rx.size);
                // UI 帧限流：局域网大文件 1% 一帧、一秒可出上百帧，
                // 每帧都整泡重建会淹死 EDT —— 界面长时间停在旧百分比。
                // pct 没发出去的下一块会带着更新的 pct 再试；收尾/完成帧不走这条限流路径。
                long nowUi = System.currentTimeMillis();
                if (pct != rx.lastPct && nowUi - rx.lastUiAt >= UI_THROTTLE_MS) {
                    rx.lastPct = pct;
                    rx.lastUiAt = nowUi;
                    // 从「已暂停」恢复：续传首个 chunk → 气泡回「发送中」（清标记）
                    L.pausedMids.remove(mid);
                    Msg m = new Msg();
                    m.mid = mid;
                    m.peerIp = rx.peerIp;   // 归属
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_FILE;
                    m.fileName = rx.name;
                    m.fileSize = rx.size;
                    m.progress = pct;
                    // 传输中的进度帧一律 SENDING：非续传首帧标 ST_OK 的话，
                    // 会把每个 1% 都当终态补写进历史（磁盘洪泛）且界面状态错乱
                    m.status = Msg.ST_SENDING;
                    if (L.listener != null) {
                        L.listener.onMessageUpdated(m);
                    }
                }
            }
        } catch (Throwable t) {
            L.log("写入分片失败: " + t);
        }
    }

    /** 等本目录的在途成员写完（dir_end 核对前用；局域网通常几十毫秒） */
    void waitDirRxQuiet(String dirId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean busy = false;
            synchronized (L.rxMap) {
                for (Rx r : L.rxMap.values()) {
                    if (r.dirId != null && r.dirId.equals(dirId)) {
                        busy = true;
                        break;
                    }
                }
            }
            if (!busy) {
                return;
            }
            try {
                Thread.sleep(30L);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    /** 文件夹传输开始（dir_meta）：挑定最终目录并建气泡 */
    void onDirMeta(Session s, Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        String name = Json.str(j, "name", "folder");
        int count = Json.integer(j, "fileCount", 0);
        long total = Json.lng(j, "totalSize", 0L);
        try {
            if (L.store == null) {
                return;
            }
            // 空文件夹一律忽略（双端统一规则）：不占任务槽、不建目录、不出气泡
            if (count == 0) {
                L.log("空文件夹已忽略 " + name + "（对端未纳入传输，本端不建目录/不出气泡）");
                return;
            }
            // 单任务槽：整目录算一个任务，被别的任务占着就明确回 busy（对端会自动排队重试）
            if (!L.claimTask(mid, s.remoteIp(), "recv", "文件夹 " + name)) {
                L.log("本机正忙（" + L.busyHolder() + "），回 busy 拒绝接收文件夹 " + name + "（mid=" + mid + "）");
                L.markBusyId(mid);
                L.trySend(s, Protocol.busy(mid, name, L.busyHolder(), "dir"));
                return;
            }
            // get→put 必须整体在锁内：两台设备同时发文件夹时这是两条读线程在并发跑
            DirRx dir;
            synchronized (L.dirMap) {
                dir = L.dirMap.get(mid);
                if (dir == null) {
                    File folder = LinkNames.createUniqueDir(L.store.receivedDir().toFile(), LinkNames.sanitizeDir(name));
                    dir = new DirRx(mid, name, s.remoteIp(), count, total, folder, folder.getAbsolutePath());
                    L.dirMap.put(mid, dir);
                }
            }
            // 上一轮已收妥的成员（断链重连会重发同一个 dirMid）：重置前先抄下来，等下回给对端
            java.util.List<String> prevGot;
            synchronized (dir) {
                prevGot = new java.util.ArrayList<String>(dir.got);
            }
            // 成员清单（v2.6）：对端 dl=1 时带来，收尾核对缺失用。没有 = 老端 → 退化为按数量核对
            boolean hasList = j.containsKey("list");
            java.util.List<String> ordered = new java.util.ArrayList<String>();
            java.util.Set<String> list = new java.util.HashSet<String>();
            Object lobj = j.get("list");
            if (lobj instanceof java.util.List) {
                for (Object o : (java.util.List<?>) lobj) {
                    String p = String.valueOf(o);
                    if (!p.isEmpty()) {
                        ordered.add(p);
                        list.add(p);
                    }
                }
            }
            // 清单大小（与 list 逐项对应）：先给清单再存文件，成员收完按它核对字节数
            Object sizesObj = j.get("sizes");
            int skipped = Json.integer(j, "skipped", 0);
            synchronized (dir) {
                dir.count = count;
                dir.total = total;
                dir.skipped = skipped;
                dir.lastActivity = System.currentTimeMillis();
                // 同一 mid 又来一轮 dir_meta = 对端重发整个文件夹（断链后从待发队列补发）：
                // 每轮都必须清零重算（含成品目录已收尾的那轮），否则上一轮的计数/清单会污染本轮核对
                dir.doneFiles = 0;
                dir.doneBytes = 0L;
                dir.failed = false;
                dir.lastPct = -1;
                dir.got.clear();
                dir.bad.clear();
                dir.usedRel.clear();
                // 有清单用新清单；没有（对端老端 / 清单超限被丢弃）必须**清空旧清单** ——
                // 否则同一 mid 重发时残留的上一轮清单会用来核对，误报一堆「缺失」
                dir.expect = hasList ? list : new java.util.HashSet<String>();
                dir.expectSizes.clear();
                if (hasList && sizesObj instanceof java.util.List
                        && ((java.util.List<?>) sizesObj).size() == ordered.size()) {
                    java.util.List<?> szs = (java.util.List<?>) sizesObj;
                    for (int i = 0; i < ordered.size(); i++) {
                        Object so = szs.get(i);
                        long v = (so instanceof Number) ? ((Number) so).longValue() : -1L;
                        dir.expectSizes.put(ordered.get(i), Long.valueOf(v));
                    }
                }
                dir.finished = false;
            }
            if (skipped > 0) {
                L.log("注意：对端有 " + skipped + " 个分支因目录过深/过大未纳入本次传输（非本端丢件）");
            }
            Msg m = new Msg();
            m.mid = mid;
            m.peerIp = s.remoteIp();
            m.dir = Msg.DIR_PEER;
            m.kind = Msg.KIND_FILE;
            m.fileName = name;
            m.fileSize = total;
            m.filePath = dir.desc;
            m.progress = 0;
            m.isDir = true;
            m.dirCount = count;
            m.ts = Json.lng(j, "ts", System.currentTimeMillis());
            L.emit(m);
            // 已经在盘上的成员先回给对端（逐个核对磁盘，不认内存记账），避免断链重发时整目录重传
            if (!dir.expect.isEmpty()) {
                java.util.List<String> have;
                synchronized (dir) {
                    have = dir.haveOnDisk(prevGot);
                }
                L.trySend(s, Protocol.dirHave(mid, have));
                if (!have.isEmpty()) {
                    L.log("对端重发文件夹 " + name + "：本机盘上已有 " + have.size() + " 个成员，请它只补缺的");
                }
            }
            // 空子目录：本端是 PC，直接重建目录结构
            Object dirsObj = j.get("dirs");
            if (dirsObj instanceof List) {
                int n = 0;
                for (Object o : (List<?>) dirsObj) {
                    if (!(o instanceof String)) {
                        continue;
                    }
                    String rel = LinkNames.sanitizeRelPath((String) o);
                    if (rel.isEmpty()) {
                        continue;
                    }
                    new File(dir.folder, rel).mkdirs();
                    n++;
                }
                if (n > 0) {
                    L.log("已重建 " + n + " 个空子目录");
                }
            }
            // 空文件夹已在入口忽略（双端统一规则），这里只可能是 count > 0
            L.log("开始接收文件夹 " + name + "（" + count + " 个文件）→ " + dir.desc);
        } catch (Throwable t) {
            L.log("创建接收目录失败: " + t);
        }
    }

    /**
     * 文件夹收尾（v2.6，对端 dl=1 时才有）。
     *
     * 接收端没有「对端发完了」这个信号，只能靠 doneFiles&gt;=count 猜；成员在发送端
     * 被跳过或发送失败时永远等不到 → **静默缺文件**。新流程由 dir_end 明确收尾：
     *  ① 有清单：与实收清单求差集，缺失项回发 dir_missing 让对端补发；
     *  ② last=1（对端不再补发）：无论是否缺件都定稿，缺件则明确标失败并逐项列出。
     */
    void onDirEnd(Session s, Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        if (mid.isEmpty()) {
            return;
        }
        boolean last = Json.bool(j, "last", true);
        DirRx dir;
        synchronized (L.dirMap) {
            dir = L.dirMap.get(mid);
        }
        if (dir == null) {
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<String>();
        int lack = 0;
        boolean hasList;
        synchronized (dir) {
            hasList = !dir.expect.isEmpty();
        }
        if (hasList) {
            // dir_end 紧跟在最后一批成员后面，可能有成员还在写盘：先等本目录的在途接收收口，
            // 再按清单逐个核对磁盘 —— 否则会把「正在写」误判成缺件，白白重传一轮
            waitDirRxQuiet(dir.mid, 10_000L);
            synchronized (dir) {
                missing = dir.missingOnDisk();
            }
        } else {
            synchronized (dir) {
                // 对端没带清单（老端 / 清单超限被丢弃）：只能算出「差几个」，无法精确到项
                lack = Math.max(0, dir.count - dir.doneFiles);
            }
        }
        long stamp = System.currentTimeMillis();
        if (!hasList) {
            // 无清单 → 无法用回执表达「缺哪些」（空回执只能表示收齐，会误导对端）。
            // 本端仍如实定稿：缺件就明确标失败；发送端那边等一拍超时后自行定稿。
            synchronized (dir) {
                dir.finished = true;
                dir.finishedAt = stamp;
            }
            finishDirReceiving(dir, new java.util.ArrayList<String>(), lack);
            return;
        }
        if (missing.isEmpty()) {
            synchronized (dir) {
                dir.finished = true;
                dir.finishedAt = stamp;
            }
            finishDirReceiving(dir, new java.util.ArrayList<String>(), 0);
            // 回执（空清单 = 收齐）：发送端据此立即定稿，不必干等到超时
            L.trySend(s, Protocol.dirMissing(mid, new java.util.ArrayList<String>()));
            return;
        }
        if (last) {
            // 对端已明说不再补发：定稿并回最后一版缺失清单（发送端据此如实标失败）
            synchronized (dir) {
                dir.finished = true;
                dir.finishedAt = stamp;
            }
            finishDirReceiving(dir, missing, 0);
            L.trySend(s, Protocol.dirMissing(mid, missing));
            return;
        }
        L.log("文件夹 " + dir.name + " 还差 " + missing.size() + " 个成员，已请求对端补发");
        L.trySend(s, Protocol.dirMissing(mid, missing));
    }

    /** 接收端回报「已经在盘上的成员」：发送端据此跳过这些成员，只补缺的文件 */
    void onDirHave(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        if (mid.isEmpty()) {
            return;
        }
        java.util.List<String> items = new java.util.ArrayList<String>();
        Object o = j.get("items");
        if (o instanceof java.util.List) {
            for (Object x : (java.util.List<?>) o) {
                String p = String.valueOf(x);
                if (!p.isEmpty()) {
                    items.add(p);
                }
            }
        }
        ArrayBlockingQueue<java.util.List<String>> q = L.dirHaveBox.get(mid);
        if (q != null) {
            q.offer(items);
        }
    }

    /** 接收端回执：这些成员没到（空清单 = 收齐）。发送端在 doSendFolderInner 里等它 */
    void onDirMissing(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        if (mid.isEmpty()) {
            return;
        }
        java.util.List<String> items = new java.util.ArrayList<String>();
        Object o = j.get("items");
        if (o instanceof java.util.List) {
            for (Object x : (java.util.List<?>) o) {
                String p = String.valueOf(x);
                if (!p.isEmpty()) {
                    items.add(p);
                }
            }
        }
        ArrayBlockingQueue<java.util.List<String>> q = L.dirMissingBox.get(mid);
        if (q != null) {
            q.offer(items);
        }
    }

    /**
     * 文件夹定稿：更新气泡并如实汇报。**缺件一律标失败并列入日志** ——
     * 绝不把「差了几个文件」当成「发送成功」。
     */
    void finishDirReceiving(DirRx dir, java.util.List<String> missing, int lack) {
        boolean hasList = !dir.expect.isEmpty();
        // 有清单时按「盘上真实有多少」报数（逐个核对磁盘后的结果），不按本轮收到多少 ——
        // 断链补发只传缺的那几个，按本轮计数会把 147 个说成「实收 2/147」
        int got = hasList ? Math.max(0, dir.expect.size() - missing.size()) : dir.doneFiles;
        int total = hasList ? dir.expect.size() : dir.count;
        boolean incomplete = !missing.isEmpty() || lack > 0;
        int pct = total > 0 ? Math.min(100, (got * 100) / total) : 100;
        // 整目录定稿 → 丢掉速率采样器（气泡按「样本过期」自动隐藏）
        Speeds.end(dir.mid);
        Msg m = new Msg();
        m.mid = dir.mid;
        m.peerIp = dir.peerIp;
        m.dir = Msg.DIR_PEER;
        m.kind = Msg.KIND_FILE;
        m.fileName = dir.name;
        m.fileSize = dir.total;
        m.filePath = dir.desc;
        m.progress = incomplete ? pct : 100;
        m.isDir = true;
        m.dirCount = total;
        m.status = (incomplete || dir.failed) ? Msg.ST_FAILED : Msg.ST_OK;
        Link.Listener l = L.listener;
        if (l != null) {
            l.onMessageUpdated(m);
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(20, missing.size()); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(missing.get(i));
            }
            L.log("文件夹接收不完整 " + dir.name + "：盘上 " + got + "/" + total + "，缺 " + missing.size()
                    + " 项 → " + sb + (missing.size() > 20 ? " …（略）" : ""));
        } else if (lack > 0) {
            L.log("文件夹接收不完整 " + dir.name + "：盘上 " + got + "/" + total + "，缺 " + lack
                    + " 项（对端未带成员清单，无法精确定位到具体文件）");
        } else if (dir.failed) {
            L.log("文件夹接收完成（有成员校验失败）" + dir.name + "：盘上 " + got + "/" + total);
        } else {
            L.log("文件夹接收完成 " + dir.name + "：盘上 " + got + "/" + total + "，全部校验通过");
        }
        // 单任务槽：整目录定稿（含不完整定稿）→ 放槽并通知对端补发被 busy 拦下的条目
        L.releaseTask(dir.mid, true);
        pruneDirs();
    }

    /**
     * 回收目录记录：
     *  ① **未收尾且闲置 10 分钟**（且无活动成员）→ 回收。防「没走到 dir_end 的 DirRx 永留」
     *     （成员被停滞中止 / 会话断开后对端永不重连的场景）。
     *  ② 成品目录超出 MAX_KEEP_DIRS → 回收最旧的。**刻意保留**最近若干条：同一 mid 重发
     *     （断链后从待发队列补发整目录）时复用它，才不会另建「文件夹 (1)」。
     * 由心跳线程每 {@link Link#STALL_SCAN_MS} 顺带调用 + 每次目录定稿时调用。
     */
    void pruneDirs() {
        long now = System.currentTimeMillis();
        java.util.List<String> stale = new java.util.ArrayList<String>();
        synchronized (L.dirMap) {
            for (DirRx d : L.dirMap.values()) {
                if (!d.finished && now - d.lastActivity > Link.DIR_STALE_MS) {
                    stale.add(d.mid);
                }
            }
        }
        for (int i = 0; i < stale.size(); i++) {
            final String mid = stale.get(i);
            // 还有成员在 rxMap 里活动就不收（chunk/file_done 会持续刷新 lastActivity，这里是双保险）
            boolean active = false;
            synchronized (L.rxMap) {
                for (Rx r : L.rxMap.values()) {
                    if (r.dirId.equals(mid)) {
                        active = true;
                        break;
                    }
                }
            }
            if (!active) {
                synchronized (L.dirMap) {
                    L.dirMap.remove(mid);
                }
                L.log("回收闲置未完成目录记录 " + mid);
                L.releaseTask(mid, false);
            }
        }
        synchronized (L.dirMap) {
            if (L.dirMap.size() <= L.MAX_KEEP_DIRS) {
                return;
            }
            int over = L.dirMap.size() - L.MAX_KEEP_DIRS;
            java.util.List<DirRx> finished = new java.util.ArrayList<DirRx>();
            for (DirRx d : L.dirMap.values()) {
                if (d.finished) {
                    finished.add(d);
                }
            }
            java.util.Collections.sort(finished, new java.util.Comparator<DirRx>() {
                @Override
                public int compare(DirRx a, DirRx b) {
                    return Long.compare(a.finishedAt, b.finishedAt);
                }
            });
            for (int i = 0; i < finished.size() && over > 0; i++, over--) {
                L.dirMap.remove(finished.get(i).mid);
            }
        }
    }

}
