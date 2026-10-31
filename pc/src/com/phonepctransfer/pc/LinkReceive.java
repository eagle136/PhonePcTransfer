package com.phonepctransfer.pc;
import java.io.*;
import java.security.*;
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
     * 每帧整泡重建 + revalidate 会淹没 EDT，界面长时间停在旧百分比（2026-09-30 修「卡在 0%」）。
     * 5Hz 刷新对进度条完全够用；完成/失败/暂停等终态帧不受限流影响。
     */
    static final long UI_THROTTLE_MS = 200L;

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
        /** 整文件 SHA-256：只在续传（startAt > 0）时才建 —— 与发送端门控同口径 */
        MessageDigest digest;
        final byte[] bufA = new byte[Protocol.RX_BUF_SIZE];
        final byte[] bufB = new byte[Protocol.RX_BUF_SIZE];
        byte[] cur = bufA;
        int pos = 0;
        long received;
        int expectSeq;
        int lastPct = -1;
        /** 上一次向界面发进度帧的时刻（UI 限流，见 UI_THROTTLE_MS） */
        long lastUiAt = 0L;
        final Object flushLock = new Object();
        byte[] pending = null;
        int pendingLen = 0;
        volatile boolean writerAlive = true;
        /**
         * 落盘线程**正在**写盘：缓冲已被取走（pending=null）但 sink.write() 还没返回。
         * ⚠️ 收尾判据必须是「pending 为空 **且** writeBusy 为假」—— 只看 pending 会在最后一块
         * 还握在写盘线程手里时就关流，落出 0 字节/截断文件，而 digest 只校验收到的字节，
         * 照样报「校验通过」（与 Android 端同构，2026-09-17 一并修）。
         */
        volatile boolean writeBusy = false;
        /** 已成功写进 sink 的字节数（诊断用） */
        volatile long writtenBytes = 0L;
        /**
         * 最近一次收到真实进度（分片追加成功 / 写盘成功）的时刻 —— 接收停滞看门狗的依据。
         * 更新点两处：onChunk 成功 appendToRx 后、写盘线程 sink.write 成功后。
         */
        volatile long lastProgressAt = System.currentTimeMillis();
        Thread writer;

        Rx(String mid, String name, long size, String peerIp, String dirId, String relPath,
           String desc, OutputStream sink, long startAt) throws Exception {
            this.mid = mid;
            this.name = name;
            this.size = size;
            this.peerIp = peerIp == null ? "" : peerIp;
            this.dirId = dirId;
            this.relPath = relPath == null ? "" : relPath;
            this.desc = desc;
            this.sink = sink;
            this.digest = (startAt > 0) ? MessageDigest.getInstance("SHA-256") : null;
            this.startAt = startAt;
            this.received = startAt;
            this.expectSeq = (size > 0 && startAt >= size)
                    ? Integer.MAX_VALUE
                    : (int) (startAt / Protocol.CHUNK_SIZE);
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
        /** 已成功落盘且校验通过的成员 relPath */
        final java.util.Set<String> got = new java.util.HashSet<String>();
        /** 落盘失败或校验失败的成员 relPath —— 也算「到过」，但要报给对端补发 */
        final java.util.Set<String> bad = new java.util.HashSet<String>();
        /** 对端声明被跳过（深度/数量上限）的分支数，&gt;0 时对用户明示 */
        int skipped = 0;
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
                if (!got.contains(p) && !bad.contains(p)) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    static final class VerifyState {
        final String mid;
        final Msg item;
        volatile String myMd5;
        volatile String peerMd5;
        volatile boolean finished;

        VerifyState(String mid, Msg item) {
            this.mid = mid;
            this.item = item;
        }
    }

    Thread startWriterThread(final Rx rx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    final byte[] buf;
                    final int len;
                    synchronized (rx.flushLock) {
                        while (rx.pending == null && rx.writerAlive) {
                            try {
                                rx.flushLock.wait();
                            } catch (InterruptedException ignore) {
                                return;
                            }
                        }
                        if (rx.pending == null) {
                            return;
                        }
                        buf = rx.pending;
                        len = rx.pendingLen;
                        rx.pending = null;
                        rx.writeBusy = true;      // 从「取走缓冲」到「写完」，收尾必须等这一段
                        rx.flushLock.notifyAll();
                    }
                    try {
                        long w0 = System.nanoTime();
                        rx.sink.write(buf, 0, len);
                        TxStats.write(System.nanoTime() - w0);
                        rx.writtenBytes += len;
                        rx.lastProgressAt = System.currentTimeMillis();
                        TxStats.block(len);
                        if (L.store != null) {
                            long p0 = System.nanoTime();
                            L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, rx.received);
                            TxStats.part(System.nanoTime() - p0);
                        }
                    } catch (Throwable t2) {
                        L.log("落盘失败 " + rx.name + "（已写 " + rx.writtenBytes + "/" + rx.received + "）: " + t2);
                    } finally {
                        synchronized (rx.flushLock) {
                            rx.writeBusy = false;
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

    /** 双缓冲交替：满块交给落盘线程，读端切到另一块继续填；两块都未落完才等 */
    void handOffFullBuffer(Rx rx) throws InterruptedException {
        synchronized (rx.flushLock) {
            while (rx.pending != null) {
                rx.flushLock.wait();
            }
            rx.pending = rx.cur;
            rx.pendingLen = rx.pos;
            rx.cur = (rx.cur == rx.bufA) ? rx.bufB : rx.bufA;
            rx.pos = 0;
            rx.flushLock.notifyAll();
        }
    }

    /** 追加一个分片到缓冲（自动切块换缓冲） */
    void appendToRx(Rx rx, byte[] data, int len) throws InterruptedException {
        int src = 0;
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
            if (rx.digest != null) {
                long d0 = System.nanoTime();
                rx.digest.update(data, src, n);
                TxStats.digest(System.nanoTime() - d0);
            }
            rx.pos += n;
            rx.received += n;
            src += n;
            remaining -= n;
        }
    }

    /** 把接收端缓冲里未落盘的部分全部冲到磁盘（续传查询时调用，让 offset 反映真实落盘量） */
    void flushRxNow(Rx rx) throws InterruptedException {
        synchronized (rx.flushLock) {
            while (true) {
                if (rx.pending != null) {
                    rx.flushLock.wait();
                    continue;
                }
                if (rx.pos == 0) {
                    break;
                }
                rx.pending = rx.cur;
                rx.pendingLen = rx.pos;
                rx.pos = 0;
                rx.cur = (rx.cur == rx.bufA) ? rx.bufB : rx.bufA;
                rx.flushLock.notifyAll();
            }
        }
    }

    /** 传输收尾：剩余不满一块直接落盘，等全部写完，关流 */
    void finishRx(Rx rx) throws InterruptedException {
        synchronized (rx.flushLock) {
            if (rx.pos > 0) {
                while (rx.pending != null) {
                    rx.flushLock.wait();
                }
                rx.pending = rx.cur;
                rx.pendingLen = rx.pos;
                rx.pos = 0;
                rx.cur = (rx.cur == rx.bufA) ? rx.bufB : rx.bufA;
                rx.flushLock.notifyAll();
            }
            // ⚠️ 关键：pending 为空只说明缓冲被取走，最后一块可能正握在写盘线程手里。
            // 少等 writeBusy 这一下，就会在 sink.write() 中途关流 → 0 字节/截断文件。
            while (rx.pending != null || rx.writeBusy) {
                rx.flushLock.wait();
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
            rx.pending = null;
            rx.flushLock.notifyAll();
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
    }

    /** 中止接收（区别于 cancelRx）：只停落盘线程、关流，**不删文件、不清注册表** —— 断链≠取消，半成品保留供续传 */
    void abortRx(Rx rx) {
        rx.writerAlive = false;
        synchronized (rx.flushLock) {
            rx.pending = null;
            rx.flushLock.notifyAll();
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
        // 平时心跳线程每 5s 也会跑，断链此刻立即跑一次，不等下一个心跳（plan 03）
        pruneDirs();
        // 顺带回收超龄半成品（plan 07）：「取消帧丢失/中途废弃」的孤儿条目不必等到下次启动；
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
     *  2. pending == null && !writeBusy（写盘线程手里没有缓冲，磁盘是活的）；
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
                        && rx.pending == null && !rx.writeBusy) {
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
            while (rx.pending != null || rx.writeBusy) {
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
            File target = new File(dir.folder, rel);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            path = target.getAbsolutePath();
            sink = new FileOutputStream(target, false);
        } else {
            // 保存目录可由用户在 PC 端设置（默认 pc/received）；原子创建不重名文件，不覆盖
            File target = LinkNames.createUniqueFile(L.store.receivedDir().toFile(), LinkNames.sanitize(name));
            path = target.getAbsolutePath();
            sink = new BufferedOutputStream(new FileOutputStream(target));
        }
        Rx rx = new Rx(mid, name, size, peerIp, dirId, relPath, path, sink, startAt);
        if (!dirId.isEmpty()) {
            // 成员 mid 登记：file_query 帧不带 dirId，靠这张表才不会把缺失成员的重发
            // 误判成「重复投递」（见 dupSavedPath）
            L.memberOfMid.put(mid, dirId);
        }
        if (startAt > 0 && !readPrefixInto(path, startAt, rx.digest)) {
            throw new IOException("续传前缀读取失败: " + path);
        }
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
     * 在 1 之前还有一道**重复投递闸门**（2026-10-01）：这条 mid 已经收妥过就直接报
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
                // 偏移必须对齐分片边界（或正好是整个文件），否则从 0 重来
                long aligned = (avail > 0 && (avail % Protocol.CHUNK_SIZE == 0L || avail == size))
                        ? avail : 0L;
                if (!path.isEmpty() && aligned > 0) {
                    try {
                        Rx rx = createRx(mid, name, size, s.remoteIp(), "", "", aligned, path);
                        synchronized (L.rxMap) {
                            L.rxMap.put(mid, rx);
                        }
                        L.log("断点续传查询 " + name + "：命中半成品 " + aligned + " B，已就绪");
                        L.trySend(s, Protocol.fileResume(mid, true, aligned, ""));
                        return;
                    } catch (Throwable t) {
                        L.log("续传半成品不可用（" + t + "），改从头接收 " + name);
                        L.store.removePartial(mid);
                    }
                } else {
                    L.store.removePartial(mid);
                }
            }
        }
        L.trySend(s, Protocol.fileResume(mid, false, 0L, ""));
    }

    void onFileResume(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        boolean ok = Json.bool(j, "ok", false);
        long offset = ok ? Json.lng(j, "offset", 0L) : 0L;
        ArrayBlockingQueue<Long> q = L.resumeWaiters.get(mid);
        if (q != null) {
            q.offer(Long.valueOf(offset));
        }
    }

    /** 流式计算文件 MD5（十六进制小写）；打不开/读失败返回空串 */
    static String md5OfFile(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 发送端：续传文件发完后启动核对 —— 后台算**源文件** MD5，等对端回发**落盘文件** MD5 比对。
     * 超时（对端没回发 / 任一方没算完）只记日志跳过，绝不阻塞、不改传输状态。
     */
    void startVerifyWatch(Msg item, long resumedFrom) {
        final VerifyState st = new VerifyState(item.mid, item);
        if (L.verifyMap.putIfAbsent(item.mid, st) != null) {
            return;
        }
        Link.daemon(() -> {
            st.myMd5 = md5OfFile(new File(item.filePath));
            compareVerify(st);
        }, "pp-tx-md5");
        Link.daemon(() -> {
            try {
                Thread.sleep(L.VERIFY_TIMEOUT_MS);
            } catch (InterruptedException ignore) {
            }
            if (!st.finished) {
                st.finished = true;
                L.verifyMap.remove(item.mid);
                L.log("MD5 核对超时 " + item.fileName
                        + "（对端未回发或双方未算完，自 " + resumedFrom + " B 续传），跳过");
            }
        }, "pp-verify-timeout");
        L.log("续传文件发送完成，等待对端 MD5 核对 " + item.fileName + "（自 " + resumedFrom + " B 起）");
    }

    /** 对端回发的落盘 MD5 到了 → 尝试比对 */
    void onFileVerify(Map<String, Object> j) {
        String mid = Json.str(j, "mid", "");
        VerifyState st = L.verifyMap.get(mid);
        if (st == null) {
            L.log("收到 MD5 核对帧但无在途核对（mid=" + mid + " 已超时/非续传/旧版本对端），忽略");
            return;
        }
        st.peerMd5 = Json.str(j, "md5", "");
        compareVerify(st);
    }

    /** 双方 MD5 都就绪才比对；比对一次性收尾（finished 原子防重入） */
    void compareVerify(final VerifyState st) {
        synchronized (st) {
            String mine = st.myMd5;
            String peer = st.peerMd5;
            if (st.finished || mine == null || mine.isEmpty()
                    || peer == null || peer.isEmpty()) {
                return;
            }
            st.finished = true;
            L.verifyMap.remove(st.mid);
            if (mine.equalsIgnoreCase(peer)) {
                L.log("MD5 核对一致 " + st.item.fileName + "（" + mine + "）");
            } else {
                L.log("MD5 核对不一致 " + st.item.fileName + "：本地 " + mine + " / 对端落盘 " + peer);
                // 气泡标失败提醒用户重传；接收端文件已在，是否删除由用户决定
                Msg u = new Msg();
                u.mid = st.mid;
                // 归属带上（2026-09-21）：不带的话界面归属不明会把这条失败标记画进当前会话
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

    /** 把文件前 len 字节读进 digest（续传时补齐整文件 sha256 的前缀段） */
    static boolean readPrefixInto(String path, long len, MessageDigest digest) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) {
                    return false;
                }
                digest.update(buf, 0, n);
                left -= n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** 对端取消了某个文件/文件夹的发送：中止接收、删除半成品、把该条从界面移除。 */
    void onRemoteFileCancel(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
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
                    // 重复投递闸门（2026-10-01 根源修）：这条 mid 已经完整收妥过，就绝不再
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
                    rx = createRx(mid, name, size, s.remoteIp(), relPath, dirId, 0L, null);
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
                    rx = live;
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
                    // ⚠️ 归属必须打上（2026-09-21 修）：这一条是**普通文件接收的第一条气泡**，
                    //    原来漏了 peerIp → 界面的归属判据（空 = 放行）会把别的设备发来的文件
                    //    画进当前打开的会话里 —— 正是用户报的「不同设备聊天窗口混在一起」。
                    m.peerIp = s.remoteIp();
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_FILE;
                    m.fileName = name;
                    m.fileSize = size;
                    m.progress = 0;
                    // 在途就是 SENDING —— 之前新接收标成 ST_OK，PcMain 立即按终态落历史并弹
                    // 「文件已保存」托盘：传输还在跑，界面却已是完成态，进度条永远停在 0%（2026-09-30 修）
                    m.status = Msg.ST_SENDING;
                    m.ts = Json.lng(j, "ts", System.currentTimeMillis());
                    L.emit(m);
                }
                String resumeNote = rx.startAt > 0 ? "，续传自 " + rx.startAt + " B" : "";
                L.log("开始接收文件 " + name + " (" + (size < 0 ? "未知大小" : size + " B") + resumeNote + ")");
            } catch (Throwable t) {
                L.log("创建接收文件失败: " + t);
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
            return;
        }
        boolean ioOk = true;
        try {
            finishRx(rx);
        } catch (Throwable t) {
            ioOk = false;
            L.log("落盘收尾失败 " + rx.name + ": " + t);
        }
        // 落盘实测：digest 校的是「网络收到的字节」，磁盘被写空/截断它照样判通过，
        // 所以关流后必须再量一次磁盘真实字节数（修完 writeBusy 后这里应当恒等）
        long diskSize = diskSizeOf(rx);
        boolean sizeOk = diskSize < 0 || diskSize == rx.received;
        if (!sizeOk) {
            ioOk = false;
            L.log("落盘字节数不符 " + rx.name + "：磁盘 " + diskSize + "B ≠ 应收 " + rx.received + "B");
        }
        // 再和「对端声明的文件大小」比一次（与 Android 端同口径，2026-09-30）：发送端源被截断时
        // sha256 算的是实际发出的那些字节，照样判通过 → 半截文件会被标成已保存。
        // 只判「收少了」；声明值偏小不判死，免得把能用的传输打成失败。
        if (rx.size > 0 && rx.received < rx.size) {
            ioOk = false;
            L.log("实收字节少于声明 " + rx.name + "：实收 " + rx.received + "B < 声明 " + rx.size + "B");
        }
        String actual = (rx.digest == null) ? "" : L.hex(rx.digest.digest());
        String expect = Json.str(j, "sha256", "");
        if (actual.isEmpty() && !expect.isEmpty()) {
            // 对端要求校验、本端却没留摘要（非续传通道）：无从比对，如实记一行后放行
            L.log(rx.name + "：对端要求 SHA-256 校验，本端未留摘要（非续传通道），跳过比对");
        }
        boolean okSha = expect.isEmpty() || actual.isEmpty() || expect.equalsIgnoreCase(actual);
        if (okSha) {
            if (L.store != null) {
                L.store.removePartial(mid);
            }
        } else if (rx.startAt > 0) {
            // 续传出来的前缀已损坏：删半成品，下次从头传
            L.log("校验失败且来自续传，删除半成品 " + rx.name + " 以便下次从头传输");
            try {
                new File(rx.desc).delete();
            } catch (Throwable ignore) {
            }
            if (L.store != null) {
                L.store.removePartial(mid);
            }
        }
        DirRx dir;
        synchronized (L.dirMap) {
            dir = rx.dirId.isEmpty() ? null : L.dirMap.get(rx.dirId);
        }
        if (dir != null) {
            String status;
            int pct;
            synchronized (dir) {
                dir.doneFiles++;
                dir.lastActivity = System.currentTimeMillis();
                // 逐项记账：成功的进 got、落盘/校验失败的进 bad —— dir_end 核对的依据
                if (!rx.relPath.isEmpty()) {
                    if (ioOk && okSha) {
                        dir.got.add(rx.relPath);
                    } else {
                        dir.bad.add(rx.relPath);
                    }
                }
                if (!ioOk || !okSha) {
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
                    + "）校验=" + (okSha ? "通过" : "失败"));
        } else {
            // 单个文件收完：丢掉速率采样器（气泡按「样本过期」自动隐藏数字）
            Speeds.end(mid);
            if (rx.dirId.isEmpty() && ioOk && okSha) {
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
            m.status = (ioOk && okSha) ? Msg.ST_OK : Msg.ST_FAILED;
            m.ts = System.currentTimeMillis();
            // 接收完成必须走 updated（与文件夹一致）：走 added 会被 onMessageAdded 的
            // mids.contains(mid) 拦截，气泡停留在 file_meta 阶段（filePath 为空、无缩略图），
            // 导致图片缩略图、正确大小只有重启重载历史后才出现。
            if (L.listener != null) {
                L.listener.onMessageUpdated(m);
            }
            L.log("文件接收完成 " + rx.name + " " + rx.received + " B 校验=" + (okSha ? "通过" : "失败"));
        }
        L.trySend(s, Protocol.ack(mid, ioOk && okSha));
        // 触发过续传的普通单文件：后台算落盘文件的 MD5 回发对端核对
        //（异步不阻塞接收线程；发送端有超时兜底，这边算不完对端只记超时日志）
        if (ioOk && okSha && rx.startAt > 0 && rx.dirId.isEmpty()) {
            final Session fs = s;
            final String fmid = mid;
            final String fpath = rx.desc;
            Link.daemon(() -> {
                String md5 = md5OfFile(new File(fpath));
                if (!md5.isEmpty()) {
                    L.trySend(fs, Protocol.fileVerify(fmid, md5));
                    L.log("已回发续传文件 MD5（mid=" + fmid + "）");
                } else {
                    L.log("落盘文件 MD5 计算失败（mid=" + fmid + "），对端将按超时跳过核对");
                }
            }, "pp-rx-md5");
        }
    }

    /** 截断重来：停写线程 → 等缓冲清空 → 以截断方式重开 → 状态归零 → 重启写线程 */
    void resetRxFromZero(Rx rx) {
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
            rx.pending = null;
            rx.pendingLen = 0;
            rx.pos = 0;
            rx.received = 0;
            if (rx.digest != null) {
                rx.digest.reset();
            }
            rx.expectSeq = 0;
            rx.lastPct = -1;
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
        if (L.store != null) {
            L.store.putPartial(rx.mid, rx.desc, rx.name, rx.size, 0);
        }
    }

    /** 二进制分片到达：按 seq 顺序追加进双缓冲，进度按需更新气泡 */
    void onChunk(Session s, String mid, int seq, byte[] data, int len) {
        Rx rx;
        synchronized (L.rxMap) {
            rx = L.rxMap.get(mid);
        }
        if (rx == null) {
            return;
        }
        if (rx.expectSeq != seq) {
            L.log("分片乱序（mid=" + mid + " seq=" + seq + " 期望 " + rx.expectSeq + "），忽略");
            return;
        }
        rx.expectSeq++;
        try {
            appendToRx(rx, data, len);
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
                        // UI 帧限流：同单文件进度帧（2026-09-30）
                        long nowUi = System.currentTimeMillis();
                        if (pct != dir.lastPct && nowUi - dir.lastUiAt >= UI_THROTTLE_MS) {
                            dir.lastPct = pct;
                            dir.lastUiAt = nowUi;
                            Msg m = new Msg();
                            m.mid = dir.mid;
                            m.peerIp = dir.peerIp;   // 归属（2026-09-21）
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
                // UI 帧限流（2026-09-30）：局域网大文件 1% 一帧、一秒可出上百帧，
                // 每帧都整泡重建会淹死 EDT —— 界面长时间停在旧百分比（用户报「卡在 0%」）。
                // pct 没发出去的下一块会带着更新的 pct 再试；收尾/完成帧不走这条限流路径。
                long nowUi = System.currentTimeMillis();
                if (pct != rx.lastPct && nowUi - rx.lastUiAt >= UI_THROTTLE_MS) {
                    rx.lastPct = pct;
                    rx.lastUiAt = nowUi;
                    // 从「已暂停」恢复：续传首个 chunk → 气泡回「发送中」（清标记）
                    L.pausedMids.remove(mid);
                    Msg m = new Msg();
                    m.mid = mid;
                    m.peerIp = rx.peerIp;   // 归属（2026-09-21）
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_FILE;
                    m.fileName = rx.name;
                    m.fileSize = rx.size;
                    m.progress = pct;
                    // 传输中的进度帧一律 SENDING：旧代码非续传首帧标 ST_OK，
                    // 会把每个 1% 都当终态补写进历史（磁盘洪泛）且界面状态错乱（2026-09-30 修）
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
            // 成员清单（v2.6）：对端 dl=1 时带来，收尾核对缺失用。没有 = 老端 → 退化为按数量核对
            boolean hasList = j.containsKey("list");
            java.util.Set<String> list = new java.util.HashSet<String>();
            Object lobj = j.get("list");
            if (lobj instanceof java.util.List) {
                for (Object o : (java.util.List<?>) lobj) {
                    String p = String.valueOf(o);
                    if (!p.isEmpty()) {
                        list.add(p);
                    }
                }
            }
            int skipped = Json.integer(j, "skipped", 0);
            synchronized (dir) {
                dir.count = count;
                dir.total = total;
                dir.skipped = skipped;
                dir.lastActivity = System.currentTimeMillis();
                // 同一 mid 又来一轮 dir_meta = 对端重发整个文件夹（断链后从待发队列补发）。
                // 上一轮已收尾 → 计数与清单清零重算，目录**就地复用**（不新建「文件夹 (1)」）。
                if (dir.finished) {
                    dir.doneFiles = 0;
                    dir.doneBytes = 0L;
                    dir.failed = false;
                    dir.lastPct = -1;
                    dir.got.clear();
                    dir.bad.clear();
                }
                // 有清单用新清单；没有（对端老端 / 清单超限被丢弃）必须**清空旧清单** ——
                // 否则同一 mid 重发时残留的上一轮清单会用来核对，误报一堆「缺失」
                dir.expect = hasList ? list : new java.util.HashSet<String>();
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
            if (count == 0) {
                // 整个文件夹为空：没有成员 file_done，直接落完成态
                m.progress = 100;
                m.status = Msg.ST_OK;
                Link.Listener l = L.listener;
                if (l != null) {
                    l.onMessageUpdated(m);
                }
                L.log("空文件夹接收完成 " + name);
            } else {
                L.log("开始接收文件夹 " + name + "（" + count + " 个文件）→ " + dir.desc);
            }
        } catch (Throwable t) {
            L.log("创建接收目录失败: " + t);
        }
    }

    /**
     * 文件夹收尾（v2.6，对端 dl=1 时才有）。
     *
     * 老流程接收端没有「对端发完了」这个信号，只能靠 doneFiles&gt;=count 猜；成员在发送端
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
            if (hasList) {
                missing = dir.missingNow();
            } else {
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
        int got = hasList ? dir.got.size() : dir.doneFiles;
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
            L.log("文件夹接收不完整 " + dir.name + "：实收 " + got + "/" + total + "，缺 " + missing.size()
                    + " 项 → " + sb + (missing.size() > 20 ? " …（略）" : ""));
        } else if (lack > 0) {
            L.log("文件夹接收不完整 " + dir.name + "：实收 " + got + "/" + total + "，缺 " + lack
                    + " 项（对端未带成员清单，无法精确定位到具体文件）");
        } else if (dir.failed) {
            L.log("文件夹接收完成（有成员校验失败）" + dir.name + "：" + got + "/" + total);
        } else {
            L.log("文件夹接收完成 " + dir.name + "：" + got + "/" + total + "，全部校验通过");
        }
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
