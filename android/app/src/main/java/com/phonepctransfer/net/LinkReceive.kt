package com.phonepctransfer.net

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock


import com.phonepctransfer.net.Link.Session

// ======================================================================
// 接收管线（v2.7 拆分自 Link.kt）：Rx/DirRx 状态、文件/文件夹接收、落盘与校验。全部为 Link 的扩展函数。
// ======================================================================

/**
 * 接收进度帧的界面限流窗口（毫秒），与 PC 端 `LinkReceive.UI_THROTTLE_MS` 同值同口径。
 * 局域网传大文件时「整百分之一」一帧可一秒上百帧，每帧都要过一遍 ui{} + Compose 重组，
 * 界面会长时间停在旧百分比上；5Hz 刷新对进度条完全够用。终态帧（完成/失败/暂停）不受限流影响。
 */
internal const val UI_THROTTLE_MS = 200L

internal class Rx(
        val mid: String,
        val name: String,
        val size: Long,
        /** 所属设备 IP：收到的文件气泡要挂到这台设备的会话里 */
        val peerIp: String,
        /** 所属目录气泡 mid；空 = 普通单文件 */
        val dirId: String,
        /**
         * 文件夹成员在目录内的相对路径（普通单文件为空）。
         * 接收端用它回答「哪个成员没到」—— dir_end 核对时与对端清单求差集。
         */
        val relPath: String,
        /** 落盘位置描述（人看的文案） */
        var desc: String,
        /** 可直接读取/删除的地址：MediaStore 为 content:// URI，回退时为绝对路径 */
        val uri: String,
        var sink: OutputStream,
        /** 续传起始偏移（0 = 全新） */
        val startAt: Long
    ) {
        /** 整文件 SHA-256：只在续传（startAt > 0）时才建 —— 与发送端门控同口径 */
        val digest: MessageDigest? =
            if (startAt > 0) MessageDigest.getInstance("SHA-256") else null
        val bufA = ByteArray(Protocol.RX_BUF_SIZE)
        val bufB = ByteArray(Protocol.RX_BUF_SIZE)
        var cur = bufA
        var pos = 0
        /** 已接收字节（含缓冲中未落盘部分；续传时含前缀） */
        var received = startAt
        /** 期望的下一个分片 seq；文件已收满时为 Int.MAX_VALUE（不再收分片） */
        var expectSeq: Int =
            if (startAt >= size && size > 0) Int.MAX_VALUE else (startAt / Protocol.CHUNK_SIZE).toInt()
        var lastPct = -1
        /** 上一次向界面发进度帧的时刻（UI 限流，见 [UI_THROTTLE_MS]） */
        var lastUiAt = 0L
        val flushLock = Object()
        var pending: ByteArray? = null
        var pendingLen = 0
        @Volatile var writerAlive = true
        /**
         * 落盘线程**正在**写盘：缓冲已被取走（pending=null）但 sink.write() 还没返回。
         * ⚠️ 收尾判据必须是「pending 为空 **且** writeBusy 为假」—— 只看 pending 会在最后一块
         * 还握在写盘线程手里时就关流，落出 0 字节/截断文件，而 digest 只校验收到的字节，
         * 照样报「校验通过」（2026-09-17 实测 75 个文件里有 3 个被这样写坏）。
         */
        var writeBusy = false
        /** 已成功写进 sink 的字节数（诊断用） */
        @Volatile var writtenBytes = 0L
        var writer: Thread? = null
        /**
         * 最近一次收到真实进度（分片追加成功 / 写盘成功）的时刻 —— 接收停滞看门狗的依据。
         * 更新点两处：onChunk 成功 appendToRx 后、写盘线程 sink.write 成功后。
         */
        @Volatile var lastProgressAt = System.currentTimeMillis()
    }

    /** 文件夹接收状态（dir_meta 创建，成员 file_done 汇总） */
internal class DirRx(
        val mid: String,
        val name: String,
        /** 所属设备 IP：目录气泡要挂到这台设备的会话里 */
        val peerIp: String,
        /** 对端声明的成员数（同一 mid 重发时会刷新，故非 val） */
        var count: Int,
        var total: Long,
        /** 实际落盘用的文件夹名（重名自动加序号） */
        val folder: String,
        val desc: String
    ) {
        var doneFiles = 0
        var doneBytes = 0L
        var failed = false
        var lastPct = -1
        /** 上一次向界面发进度帧的时刻（UI 限流，见 [UI_THROTTLE_MS]） */
        var lastUiAt = 0L
        /** 已落盘成员的 uri/路径：整目录取消时删除 */
        val receivedUris = ArrayList<String>()

        // ===== 完整性核对（v2.6，对端 dl=1 时启用）=====

        /** 对端在 dir_meta 里声明的成员清单。空 = 对端是老端/未带清单 → 只能按数量核对 */
        var expect: MutableSet<String> = HashSet()
        /** 已成功落盘且校验通过的成员 relPath */
        val got = HashSet<String>()
        /** 落盘失败或校验失败的成员 relPath —— 也算「到过」，但要报给对端补发 */
        val bad = HashSet<String>()
        /** 对端声明被跳过（深度/数量上限）的分支数，>0 时对用户明示 */
        var skipped = 0
        /** 已按 dir_end 收尾。成品目录**保留**，同一 mid 重发时复用它，不再生成「文件夹 (1)」副本 */
        var finished = false
        /** 收尾时刻（回收最旧的成品目录用） */
        var finishedAt = 0L
        /**
         * 最近一次活动（dir_meta / 成员 chunk / 成员 file_done / 成员被中止）。
         * 未完成目录闲置 10 分钟即回收（防「没走到 dir_end 的目录永留」，见 pruneDirs）。
         */
        var lastActivity: Long = System.currentTimeMillis()

        /** 本轮还没到的成员（expect 有清单时精确到项；无清单时用空串占位表示「差 N 项」） */
        fun missingNow(): List<String> =
            if (expect.isEmpty()) emptyList() else (expect - got - bad).toList()
    }


internal fun Link.startWriterThread(rx: Rx): Thread =
        Thread({
            while (true) {
                val buf: ByteArray
                val len: Int
                synchronized(rx.flushLock) {
                    while (rx.pending == null && rx.writerAlive) rx.flushLock.wait()
                    if (rx.pending == null) return@Thread
                    buf = rx.pending!!
                    len = rx.pendingLen
                    rx.pending = null
                    rx.writeBusy = true          // 从「取走缓冲」到「写完」，收尾必须等这一段
                    rx.flushLock.notifyAll()
                }
                try {
                    val w0 = System.nanoTime()
                    rx.sink.write(buf, 0, len)
                    TxStats.write(System.nanoTime() - w0)
                    rx.writtenBytes += len
                    rx.lastProgressAt = System.currentTimeMillis()
                    TxStats.block(len.toLong())
                    val p0 = System.nanoTime()
                    store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, rx.received)
                    TxStats.part(System.nanoTime() - p0)
                } catch (t: Throwable) {
                    log("落盘失败 ${rx.name}（已写 ${rx.writtenBytes}/${rx.received}）: ${t.message}")
                } finally {
                    synchronized(rx.flushLock) {
                        rx.writeBusy = false
                        rx.flushLock.notifyAll()
                    }
                }
            }
        }, "pp-disk-${rx.mid}").apply { isDaemon = true }.also { it.start() }

    /** 双缓冲交替：满块交给落盘线程，读端切到另一块继续填；两块都未落完才等 */
internal fun Link.handOffFullBuffer(rx: Rx) {
        synchronized(rx.flushLock) {
            while (rx.pending != null) rx.flushLock.wait()
            rx.pending = rx.cur
            rx.pendingLen = rx.pos
            rx.cur = if (rx.cur === rx.bufA) rx.bufB else rx.bufA
            rx.pos = 0
            rx.flushLock.notifyAll()
        }
    }

    /** 追加一个分片到缓冲（自动切块换缓冲） */
internal fun Link.appendToRx(rx: Rx, data: ByteArray, len: Int) {
        var src = 0
        var remaining = len
        while (remaining > 0) {
            if (rx.pos == Protocol.RX_BUF_SIZE) {
                val w0 = System.nanoTime()
                handOffFullBuffer(rx)
                TxStats.waitBuf(System.nanoTime() - w0)
            }
            val n = minOf(remaining, Protocol.RX_BUF_SIZE - rx.pos)
            val c0 = System.nanoTime()
            System.arraycopy(data, src, rx.cur, rx.pos, n)
            TxStats.copy(System.nanoTime() - c0)
            val dg = rx.digest
            if (dg != null) {
                val d0 = System.nanoTime()
                dg.update(data, src, n)
                TxStats.digest(System.nanoTime() - d0)
            }
            rx.pos += n
            rx.received += n
            src += n
            remaining -= n
        }
    }

    /** 把接收端缓冲里未落盘的部分全部冲到磁盘（续传查询时调用，让 offset 反映真实落盘量） */
internal fun Link.flushRxNow(rx: Rx) {
        synchronized(rx.flushLock) {
            while (true) {
                if (rx.pending != null) {
                    rx.flushLock.wait()
                    continue
                }
                if (rx.pos == 0) break
                rx.pending = rx.cur
                rx.pendingLen = rx.pos
                rx.pos = 0
                rx.cur = if (rx.cur === rx.bufA) rx.bufB else rx.bufA
                rx.flushLock.notifyAll()
            }
        }
    }

    /** 传输收尾：剩余不满一块直接落盘，等全部写完，关流。digest 此后即为整文件 sha256 源 */
internal fun Link.finishRx(rx: Rx) {
        synchronized(rx.flushLock) {
            if (rx.pos > 0) {
                while (rx.pending != null) rx.flushLock.wait()
                rx.pending = rx.cur
                rx.pendingLen = rx.pos
                rx.pos = 0
                rx.cur = if (rx.cur === rx.bufA) rx.bufB else rx.bufA
                rx.flushLock.notifyAll()
            }
            // ⚠️ 关键：pending 为空只说明缓冲被取走，最后一块可能正握在写盘线程手里。
            // 少等 writeBusy 这一下，就会在 sink.write() 中途关流 → 0 字节/截断文件。
            while (rx.pending != null || rx.writeBusy) rx.flushLock.wait()
        }
        rx.writerAlive = false
        synchronized(rx.flushLock) { rx.flushLock.notifyAll() }
        runCatching { rx.sink.close() }
    }

    /**
     * 关流后测「磁盘上真实有多少字节」——判断有没有被写空/截断。
     * content:// 走 fd.statSize（读 MediaStore 的 SIZE 列可能滞后）；量不到返回 -1（不因此判失败）。
     */
internal fun Link.diskSizeOf(rx: Rx): Long = try {
        if (rx.uri.startsWith("content://")) {
            ctx.contentResolver.openFileDescriptor(Uri.parse(rx.uri), "r")
                ?.use { it.statSize } ?: -1L
        } else {
            java.io.File(rx.uri).length()
        }
    } catch (t: Throwable) {
        runCatching { FileIO.querySize(ctx, Uri.parse(rx.uri)) }.getOrNull()?.takeIf { it >= 0 } ?: -1L
    }

    /** 取消接收：丢弃缓冲、关流、删半成品、清注册表 */
internal fun Link.cancelRx(rx: Rx) {
        rx.writerAlive = false
        synchronized(rx.flushLock) {
            rx.pending = null
            rx.flushLock.notifyAll()
        }
        runCatching { rx.sink.close() }
        FileIO.deletePartial(ctx, rx.uri)
        store?.removePartial(rx.mid)
    }

    /** 中止接收（区别于 cancelRx）：只停落盘线程、关流，**不删文件、不清注册表** —— 断链≠取消，半成品保留供续传 */
internal fun Link.abortRx(rx: Rx) {
        rx.writerAlive = false
        synchronized(rx.flushLock) {
            rx.pending = null
            rx.flushLock.notifyAll()
        }
        runCatching { rx.sink.close() }
    }

    /**
     * 会话断开：中止该对端名下所有活动接收。文件与 partials 注册表原样保留 ——
     * 对端重连后 file_query 仍可命中续传；若对端永久放弃，过期清理会兜底回收。
     */
internal fun Link.abortRxsFor(peerIp: String) {
        val aborted = mutableListOf<Rx>()
        synchronized(rxMap) {
            val it = rxMap.entries.iterator()
            while (it.hasNext()) {
                val rx = it.next().value
                if (rx.peerIp == peerIp) {
                    aborted.add(rx)
                    it.remove()
                }
            }
        }
        aborted.forEach { abortRx(it) }
        // 所属目录记一次活动（未完成目录 10 分钟闲置回收的依据；重连续传会继续刷新它）
        aborted.forEach { rx ->
            if (rx.dirId.isEmpty()) return@forEach
            val dir = synchronized(dirMap) { dirMap[rx.dirId] } ?: return@forEach
            synchronized(dir) { dir.lastActivity = System.currentTimeMillis() }
        }
        if (aborted.isNotEmpty()) {
            log("会话断开，中止 ${aborted.size} 路接收（半成品保留，重传自动续传）")
        }
        // 顺带回收目录记录（未完成目录闲置 10 分钟兜底 / 成品超限量）——
        // 平时心跳线程每 5s 也会跑，断链此刻立即跑一次，不等下一个心跳（plan 03）
        pruneDirs()
        // 顺带回收超龄半成品（plan 07）：「取消帧丢失/中途废弃」的孤儿条目不必等到下次启动；
        // purge 只回收 ts 超 3 天的条目，不碰为续传/停滞保留的新鲜半成品，语义不冲突
        store?.purgeStalePartials(Link.PARTIAL_MAX_AGE_MS)
    }

    /**
     * 接收停滞看门狗：某路接收 30s 无任何进度（对端半死但 TCP 心跳仍正常）时中止该路。
     * 由心跳线程每 [Link.STALL_SCAN_MS] 顺带调用（不新开线程）。
     *
     * 触发条件（全部满足）：
     *  1. now - lastProgressAt > 30s；
     *  2. pending == null && !writeBusy（写盘线程手里没有缓冲，磁盘是活的）；
     *  3. 在 rxMap 锁内二次确认并**先摘通道** —— 之后的 file_done/file_cancel/onChunk
     *     都查不到这条 rx，不会与收尾竞态。
     */
    internal fun Link.scanStalledRx() {
        val now = System.currentTimeMillis()
        val stalled = mutableListOf<Rx>()
        synchronized(rxMap) {
            val it = rxMap.entries.iterator()
            while (it.hasNext()) {
                val rx = it.next().value
                if (now - rx.lastProgressAt > Link.STALL_TIMEOUT_MS &&
                    rx.pending == null && !rx.writeBusy
                ) {
                    stalled.add(rx)
                    it.remove()
                }
            }
        }
        stalled.forEach { stallAbort(it) }
    }

    /**
     * 停滞中止：断链 abortRx 的超时触发版，**不删文件、不清 partials** ——
     * 半成品保留，对端重连后 file_query 命中注册表自动续传。
     * 调用方（scanStalledRx）已在锁内把 mid 从 rxMap 摘掉。
     */
    internal fun Link.stallAbort(rx: Rx) {
        // 1. 把双缓冲中已完整收到的字节全部落盘，进度最大化。
        //    正确性：发送按 CHUNK_SIZE 切片、seq 严格顺序，停滞时缓冲内容必为 CHUNK 对齐
        //    （只有文件最后一块才可能非对齐，而那会立刻 file_done）→ 落盘量满足 onFileQuery
        //    的 aligned 判定，续传 offset 有效。
        try {
            flushRxNow(rx)
        } catch (_: InterruptedException) {
        }
        // 等最后一块写完再关流（writeBusy 时关流会在 sink.write() 中途截断）。
        // 加 10s 上限：万一磁盘恰好卡死，不让心跳线程被永久拖住（拖住会停掉断链检测）。
        synchronized(rx.flushLock) {
            val deadline = System.currentTimeMillis() + 10_000L
            while (rx.pending != null || rx.writeBusy) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try { rx.flushLock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
        // 2. 停写线程、关流（不删文件、不清注册表）
        abortRx(rx)
        // 单文件：记「暂停过」标记 —— 下一个进度帧（file_meta 重到 / 续传首 chunk）
        // 到达时气泡回「发送中」。目录成员不需要：首个续传 chunk 本就把目录气泡发成 SENDING。
        if (rx.dirId.isEmpty()) pausedMids.add(rx.mid)
        // 3. 注册表最后补一次，确保 received 与磁盘一致
        store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, rx.received)
        // 4. 气泡暂停态 + 日志（边判定边落盘，实时可见）
        val dir = if (rx.dirId.isEmpty()) null else synchronized(dirMap) { dirMap[rx.dirId] }
        if (dir != null) {
            synchronized(dir) { dir.lastActivity = System.currentTimeMillis() }
            val pct = if (dir.total > 0) ((dir.doneBytes * 100) / dir.total).toInt().coerceIn(0, 99) else -1
            okUpdate(
                MsgItem(
                    mid = dir.mid, dir = Dir.PEER, kind = Kind.FILE,
                    fileName = dir.name, fileSize = dir.total, filePath = dir.desc,
                    progress = pct, isDir = true, dirCount = dir.count,
                    status = Status.PAUSED, peerIp = dir.peerIp
                )
            )
        } else {
            val pct = if (rx.size > 0) ((rx.received * 100) / rx.size).toInt().coerceIn(0, 99) else -1
            okUpdate(
                MsgItem(
                    mid = rx.mid, dir = Dir.PEER, kind = Kind.FILE, fileName = rx.name,
                    fileSize = rx.size, filePath = rx.desc, localUri = rx.uri,
                    progress = pct, status = Status.PAUSED, peerIp = rx.peerIp
                )
            )
        }
        val logPct = if (rx.size > 0) ((rx.received * 100) / rx.size).toInt() else -1
        log("接收停滞 30s 已暂停 mid=${rx.mid} name=${rx.name} 已收 ${rx.received}/${rx.size} B" +
            (if (logPct >= 0) " ($logPct%)" else "") +
            " 半成品=${rx.uri} 等待对端重连续传")
    }

    /**
     * 新建接收通道。dirId 非空时按「文件夹名/相对路径」落盘。
     *
     * @param peerIp 发起这次传输的对端 IP —— 收到的气泡挂到它名下（归属由会话决定）
     */
internal fun Link.createRx(
        mid: String, name: String, size: Long, peerIp: String, mime: String,
        relPath: String, dirId: String, startAt: Long, uriHint: String? = null
    ): Rx {
        val dir = if (dirId.isEmpty()) null else synchronized(dirMap) { dirMap[dirId] }
        val sink: OutputStream
        val uri: String
        val desc: String
        if (startAt > 0) {
            // 续传：从半成品末尾追加（uri 来自注册表或活动通道）
            uri = uriHint ?: store?.getPartial(mid)?.optString("uri").orEmpty()
            sink = FileIO.appendSink(ctx, uri)
                ?: throw java.io.IOException("续传打开半成品失败: $uri")
            desc = if (uri.startsWith("content://")) "下载/${FileIO.DOWNLOAD_SUBDIR}/$name" else uri
        } else {
            val effectiveRel = if (dir != null) "${dir.folder}/$relPath" else relPath
            val s = FileIO.createSink(ctx, name, mime, effectiveRel)
            sink = s.out; uri = s.uri; desc = s.desc
            dir?.let { synchronized(it) { it.receivedUris.add(uri) } }
        }
        val rx = Rx(mid, name, size, peerIp, dirId, relPath, desc, uri, sink, startAt)
        if (startAt > 0) {
            // 前缀摘要：读一遍半成品把整文件 sha256 的前段补齐（只在续传时发生一次）
            if (!readPrefixInto(uri, startAt, rx.digest!!)) {
                throw java.io.IOException("续传前缀读取失败: $uri")
            }
        }
        store?.putPartial(mid, uri, name, size, rx.received)
        rx.writer = startWriterThread(rx)
        return rx
    }

    /**
     * 对端开传前问本端有没有半成品。三种情况：
     *  1. 活动通道还在（上次会话刚断）→ 冲掉未落盘缓冲，原地续传（sink/digest 都还热着）；
     *  2. 这条 mid 本端**已经存好一份完整的** → 直接答「收全了」，让对方一个字节都不发；
     *  3. 注册表里有半成品（对端重启过）→ 追加打开、重算前缀摘要，准备好续传通道；
     *  4. 都没有 → 从头传。
     */
internal fun Link.onFileQuery(s: Session, j: JSONObject) {
        val mid = j.optString("mid")
        val name = j.optString("name", "file")
        val size = j.optLong("size", -1L)
        val live = synchronized(rxMap) { rxMap[mid] }
        if (live != null) {
            flushRxNow(live)
            log("断点续传查询 $name：本端已有 ${TextUtil.humanSize(live.received)}（活动通道）")
            sendOrDrop(s, Protocol.fileResume(mid, true, live.received, ""), "file_resume", quiet = true)
            return
        }
        val saved = savedCompleteSize(s.remoteIp, mid, size)
        if (saved > 0L) {
            log("重复投递 $name（mid=$mid）本端已存过 $saved B，按已收完整答复")
            sendOrDrop(s, Protocol.fileResume(mid, true, saved, ""), "file_resume", quiet = true)
            return
        }
        val p = store?.getPartial(mid)
        if (p != null) {
            val uri = p.optString("uri")
            val avail = partialSize(uri)
            // 偏移必须对齐分片边界（或正好是整个文件），否则从 0 重来
            val aligned =
                if (avail > 0 && (avail % Protocol.CHUNK_SIZE == 0L || avail == size)) avail else 0L
            if (uri.isNotEmpty() && aligned > 0) {
                runCatching {
                    val mime = FileIO.mimeOf(name)
                    val rx = createRx(mid, name, size, s.remoteIp, mime, "", "", aligned, uriHint = uri)
                    synchronized(rxMap) { rxMap[mid] = rx }
                    log("断点续传查询 $name：命中半成品 ${TextUtil.humanSize(aligned)}，已就绪")
                    sendOrDrop(s, Protocol.fileResume(mid, true, aligned, ""), "file_resume", quiet = true)
                    return
                }.onFailure { t ->
                    log("续传半成品不可用（${t.message}），改从头接收 $name")
                    store?.removePartial(mid)
                }
            } else {
                store?.removePartial(mid)
            }
        }
        sendOrDrop(s, Protocol.fileResume(mid, false, 0L, ""), "file_resume", quiet = true)
    }

internal fun Link.onFileResume(j: JSONObject) {
        val mid = j.optString("mid")
        val ok = j.optBoolean("ok", false)
        val offset = if (ok) j.optLong("offset", 0L) else 0L
        resumeWaiters[mid]?.offer(offset)
    }

    /** 半成品当前实际大小（content:// 查 MediaStore，路径看文件长度） */
internal fun Link.partialSize(uri: String): Long = if (uri.isEmpty()) 0L
    else if (uri.startsWith("content://")) FileIO.querySize(ctx, Uri.parse(uri))
    else runCatching { File(uri).length() }.getOrDefault(0L)

    /**
     * 这条 mid 本端是否已经存成过一份**完整**的收讫文件；是 → 返回磁盘上的实际字节数，否 → 0。
     *
     * 历史里标 OK 还不够，必须文件真的还在、字节数与对端声明值对得上（与 PC 端
     * `dupSavedPath` 同一套判据）。用户删掉收到的文件后想重收，这里就判不出来 → 照常收。
     */
internal fun Link.savedCompleteSize(peerIp: String, mid: String, declaredSize: Long): Long {
        if (mid.isEmpty()) return 0L
        val m = store?.savedFileOf(peerIp, mid) ?: return 0L
        val onDisk = partialSize(m.localUri.ifEmpty { m.filePath })
        return if (onDisk > 0L && (declaredSize <= 0L || onDisk == declaredSize)) onDisk else 0L
    }

    /** 把 uri 内容前 len 字节读进 digest（续传时补齐整文件 sha256 的前缀段） */
internal fun Link.readPrefixInto(uri: String, len: Long, digest: MessageDigest): Boolean {
        return runCatching {
            val ins: InputStream? = if (uri.startsWith("content://")) {
                ctx.contentResolver.openInputStream(Uri.parse(uri))
            } else {
                java.io.FileInputStream(File(uri))
            }
            var ok = false
            ins?.use { stream ->
                val buf = ByteArray(1 shl 16)
                var left = len
                ok = true
                while (left > 0) {
                    val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) {
                        ok = false
                        break
                    }
                    digest.update(buf, 0, n)
                    left -= n
                }
            }
            ok
        }.getOrDefault(false)
    }

    /** 对端取消了某个文件/文件夹的发送：中止接收、删除半成品、把该条从界面移除。 */
internal fun Link.onRemoteFileCancel(mid: String) {
        if (mid.isEmpty()) return
        val dir = synchronized(dirMap) { dirMap.remove(mid) }
        if (dir != null) {
            // 取消整个文件夹：停掉所有成员的接收、删除半成品与已收成员、移除气泡
            val members = synchronized(rxMap) {
                rxMap.values.filter { it.dirId == mid }.toList()
                    .also { list -> list.forEach { rxMap.remove(it.mid) } }
            }
            members.forEach { cancelRx(it) }
            synchronized(dir) { dir.receivedUris.toList() }.forEach { FileIO.deletePartial(ctx, it) }
            store?.removePending(mid)
            log("对端取消文件夹 ${dir.name}，已移除")
            listener?.onMessageRemoved(mid)
            return
        }
        val rx = synchronized(rxMap) { rxMap.remove(mid) }
        if (rx != null) {
            cancelRx(rx)
            log("对端取消文件 ${rx.name}，已删除半成品并移除")
        } else {
            log("对端取消文件传输 $mid（本端未在接收，仅移除展示）")
        }
        listener?.onMessageRemoved(mid)
    }

internal fun Link.onFileFrame(s: Session, j: JSONObject) {
        when (j.optString("type")) {
            "file_meta" -> {
                val mid = j.optString("mid")
                val name = j.optString("name", "file")
                val size = j.optLong("size", -1L)
                // 对端在推文件 → 该设备有过文件往来（已知设备判定的一半，另一半是握手）
                trustContent(s.remoteIp, isFile = true)
                val mime = j.optString("mime", FileIO.mimeOf(name))
                val offset = j.optLong("offset", 0L)
                val relPath = j.optString("relPath", "")
                val dirId = j.optString("dirId", "")
                runCatching {
                    var rx = synchronized(rxMap) { rxMap[mid] }
                    if (rx == null) {
                        // 重复投递闸门：同一条 mid 本端已经存好一份完整的了（对端重启丢了
                        // 送达台账、或它自己那条补发路径又跑了一遍）→ 回 ack、绝不建第二个
                        // 接收通道，否则 createRx 会另起一个唯一名 → 落盘多出一份 (1)。
                        // 文件夹成员不判：成员 mid 不进本端 FILE 历史，判了会误拦 dir_missing 补发。
                        if (dirId.isEmpty()) {
                            val saved = savedCompleteSize(s.remoteIp, mid, size)
                            if (saved > 0L) {
                                log("重复投递 $name（mid=$mid）本端已存过 $saved B，跳过落盘")
                                sendOrDrop(s, Protocol.ack(mid, true), "ack", quiet = true)
                                return@runCatching
                            }
                        }
                        // dir_meta 丢失的兜底：隐式建目录记录（正常流程 dir_meta 先到）
                        if (dirId.isNotEmpty() && synchronized(dirMap) { dirMap[dirId] } == null) {
                            val folder = FileIO.uniqueFolderName(ctx, segSanitize(dirId))
                            synchronized(dirMap) {
                                dirMap[dirId] = DirRx(
                                    dirId, dirId, s.remoteIp, 0, 0L, folder,
                                    "下载/${FileIO.DOWNLOAD_SUBDIR}/$folder"
                                )
                            }
                        }
                        rx = createRx(mid, name, size, s.remoteIp, mime, relPath, dirId, 0L)
                        synchronized(rxMap) { rxMap[mid] = rx }
                    } else if (offset <= 0 || offset != rx.received) {
                        // 对端要求从头（或偏移对不上，如前缀校验失败）：截断重传
                        log("file_meta 偏移 $offset 与本端 ${rx.received} 不符，${rx.name} 从头重收")
                        resetRxFromZero(rx)
                    }
                    val r = rx
                    val dir = if (r.dirId.isEmpty()) null else synchronized(dirMap) { dirMap[r.dirId] }
                    if (dir == null) {
                        // 从「已暂停」恢复：file_meta 重到 → 气泡回「发送中」（清标记）
                        pausedMids.remove(mid)
                        emit(
                            MsgItem(
                                mid = mid, dir = Dir.PEER, kind = Kind.FILE, fileName = name,
                                fileSize = size, filePath = "", localUri = r.uri, progress = 0,
                                ts = j.optLong("ts", System.currentTimeMillis()), peerIp = r.peerIp,
                                // 一个字节都还没收 → 在途就是 SENDING。旧代码标 OK 的后果（与 PC 端
                                // 2026-09-30 修的同源）：AppCore.updateMessage 把 OK 当终态补写历史，
                                // 每个 1% 进度帧都 append 一行（历史文件被灌爆、每次切会话全量重读），
                                // addMessage 的 clearSpeed 又把速率采样器提前回收 → 传输中看不到速率。
                                status = Status.SENDING
                            )
                        )
                    }
                    log("开始接收文件 $name (${if (size < 0) "未知大小" else "$size B"}" +
                        if (r.startAt > 0) "，续传自 ${TextUtil.humanSize(r.startAt)}" else "" + ")")
                }.onFailure {
                    log("创建接收文件失败: ${it.message}")
                    sendOrDrop(s, Protocol.ack(mid, false), "ack")
                }
            }
            "file_done" -> {
                val mid = j.optString("mid")
                val rx = synchronized(rxMap) { rxMap.remove(mid) } ?: return
                var ioOk = true
                runCatching { finishRx(rx) }.onFailure {
                    ioOk = false
                    log("落盘收尾失败 ${rx.name}: ${it.message}")
                }
                // 落盘实测：rx.digest 校的是「网络收到的字节」，磁盘被写空/截断它照样判通过，
                // 所以关流后必须再量一次磁盘真实字节数（修完 writeBusy 后这里应当恒等）
                val diskSize = diskSizeOf(rx)
                val sizeOk = diskSize < 0 || diskSize == rx.received
                if (!sizeOk) {
                    ioOk = false
                    log("落盘字节数不符 ${rx.name}：磁盘 ${diskSize}B ≠ 应收 ${rx.received}B")
                }
                // 再和「对端声明的文件大小」比一次：发送端源被截断（SAF 读到底、传中被改小）时，
                // sha256 算的是**实际发出的那些字节**，照样判通过 → 半截文件会被标成已保存。
                // 只判「收少了」；声明值偏小（个别提供器估报大小）不判失败，免得把能用的传输判死。
                if (rx.size > 0 && rx.received < rx.size) {
                    ioOk = false
                    log("实收字节少于声明 ${rx.name}：实收 ${rx.received}B < 声明 ${rx.size}B")
                }
                val actual = rx.digest?.let { hex(it.digest()) } ?: ""
                val expect = j.optString("sha256", "")
                if (actual.isEmpty() && expect.isNotEmpty()) {
                    // 对端要求校验、本端却没留摘要（非续传通道）：无从比对，如实记一行后放行
                    log("${rx.name}：对端要求 SHA-256 校验，本端未留摘要（非续传通道），跳过比对")
                }
                val okSha = expect.isEmpty() || actual.isEmpty() ||
                        expect.equals(actual, ignoreCase = true)
                if (okSha) {
                    store?.removePartial(mid)
                } else if (rx.startAt > 0) {
                    // 续传出来的前缀已损坏：删半成品，下次从头传
                    log("校验失败且来自续传，删除半成品 ${rx.name} 以便下次从头传输")
                    FileIO.deletePartial(ctx, rx.uri)
                    store?.removePartial(mid)
                }
                val dir = if (rx.dirId.isEmpty()) null else synchronized(dirMap) { dirMap[rx.dirId] }
                if (dir != null) {
                    val status: Status
                    val pct: Int
                    synchronized(dir) {
                        dir.doneFiles++
                        dir.lastActivity = System.currentTimeMillis()
                        // 逐项记账：成功的进 got、落盘/校验失败的进 bad —— dir_end 核对的依据
                        if (rx.relPath.isNotEmpty()) {
                            if (ioOk && okSha) dir.got.add(rx.relPath) else dir.bad.add(rx.relPath)
                        }
                        if (!ioOk || !okSha) dir.failed = true
                        val allDone = dir.doneFiles >= dir.count
                        // 整目录收齐 → 丢掉速率采样器（气泡按样本过期自动隐藏）
                        if (allDone) endSpeed(dir.mid)
                        status = when {
                            allDone && dir.failed -> Status.FAILED
                            allDone -> Status.OK
                            else -> Status.SENDING
                        }
                        pct = if (allDone) 100
                        else if (dir.total > 0) ((dir.doneBytes * 100) / dir.total).toInt().coerceIn(0, 99)
                        else -1
                        listener?.onMessageUpdated(
                            MsgItem(
                                mid = dir.mid, dir = Dir.PEER, kind = Kind.FILE,
                                fileName = dir.name, fileSize = dir.total, filePath = dir.desc,
                                progress = pct, isDir = true, dirCount = dir.count, status = status,
                                peerIp = dir.peerIp
                            )
                        )
                        // 数量收齐只是**兜底**判据（老发送端不发 dir_end）；新发送端一律以 dir_end 核对为准。
                        // 目录**不从 dirMap 移除**：成品目录保留给「重发 / 补发」复用 —— 否则同一
                        // 文件夹重传会另建「文件夹 (1)」，用户看到的正是「原文件夹里少了文件」。
                        // 保留态由 pruneDirs() 限量回收（会话断开时清空）。
                        if (allDone) dir.finished = true
                    }
                    log("文件夹成员完成 ${rx.name}（${dir.doneFiles}/${dir.count}）校验=${if (okSha) "通过" else "失败"}")
                } else {
                    // 单个文件收完：丢掉速率采样器（气泡靠样本过期自动隐藏数字）
                    endSpeed(mid)
                    emit(
                        MsgItem(
                            mid = mid, dir = Dir.PEER, kind = Kind.FILE, fileName = rx.name,
                            fileSize = rx.received, filePath = rx.desc, localUri = rx.uri,
                            progress = 100,
                            status = if (ioOk && okSha) Status.OK else Status.FAILED,
                            ts = System.currentTimeMillis(), peerIp = rx.peerIp
                        )
                    )
                    log("文件接收完成 ${rx.name} ${rx.received} B 校验=${if (okSha) "通过" else "失败"}")
                }
                sendOrDrop(s, Protocol.ack(mid, ioOk && okSha), "ack")
                // 触发过续传的普通单文件：后台算落盘文件的 MD5 回发对端核对
                //（异步不阻塞接收线程；发送端有超时兜底，这边算不完对端只记超时日志）
                if (ioOk && okSha && rx.startAt > 0 && rx.dirId.isEmpty()) {
                    Thread({
                        val md5 = md5Of(openSource(Uri.parse(rx.uri)))
                        if (md5.isNotEmpty()) {
                            sendOrDrop(s, Protocol.fileVerify(mid, md5), "file_verify")
                            log("已回发续传文件 MD5（mid=$mid）")
                        } else {
                            log("落盘文件 MD5 计算失败（mid=$mid），对端将按超时跳过核对")
                        }
                    }, "pp-rx-md5").apply { isDaemon = true }.start()
                }
            }
        }
    }

    /** 截断重来：停写线程 → 等缓冲清空 → 以截断方式重开 → 状态归零 → 重启写线程 */
internal fun Link.resetRxFromZero(rx: Rx) {
        rx.writerAlive = false
        synchronized(rx.flushLock) { rx.flushLock.notifyAll() }
        rx.writer?.join(2000)
        synchronized(rx.flushLock) {
            rx.pending = null
            rx.pendingLen = 0
            rx.pos = 0
            rx.received = 0
            rx.digest?.reset()
            rx.expectSeq = 0
            rx.lastPct = -1
            runCatching { rx.sink.close() }
            rx.sink = if (rx.uri.startsWith("content://")) {
                ctx.contentResolver.openOutputStream(Uri.parse(rx.uri), "w")
                    ?: FileOutputStream(File(rx.uri), false)
            } else {
                FileOutputStream(File(rx.uri), false)
            }
            rx.writerAlive = true
        }
        rx.writer = startWriterThread(rx)
        store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, 0)
    }

    /** 二进制分片到达：按 seq 顺序追加进双缓冲，进度按需更新气泡 */
internal fun Link.onChunk(s: Session, mid: String, seq: Int, data: ByteArray, len: Int) {
        val rx = synchronized(rxMap) { rxMap[mid] } ?: return
        if (rx.expectSeq != seq) {
            log("分片乱序（mid=$mid seq=$seq 期望 ${rx.expectSeq}），忽略")
            return
        }
        rx.expectSeq++
        runCatching {
            appendToRx(rx, data, len)
            rx.lastProgressAt = System.currentTimeMillis()
            val dir = if (rx.dirId.isEmpty()) null else synchronized(dirMap) { dirMap[rx.dirId] }
            if (dir != null) {
                synchronized(dir) {
                    dir.doneBytes += len
                    dir.lastActivity = System.currentTimeMillis()
                    // 速率采样（接收侧，与发送侧同口径）：文件夹用聚合字节数
                    markSpeed(dir.mid, dir.doneBytes)
                    if (dir.total > 0) {
                        val pct = ((dir.doneBytes * 100) / dir.total).toInt().coerceIn(0, 99)
                        // UI 帧限流：整百分之一一帧，局域网速度下可一秒上百帧（见 UI_THROTTLE_MS）
                        val nowUi = System.currentTimeMillis()
                        if (pct != dir.lastPct && nowUi - dir.lastUiAt >= UI_THROTTLE_MS) {
                            dir.lastPct = pct
                            dir.lastUiAt = nowUi
                            listener?.onMessageUpdated(
                                MsgItem(
                                    mid = dir.mid, dir = Dir.PEER, kind = Kind.FILE,
                                    fileName = dir.name, fileSize = dir.total, filePath = dir.desc,
                                    progress = pct, isDir = true, dirCount = dir.count,
                                    status = Status.SENDING, peerIp = dir.peerIp
                                )
                            )
                        }
                    }
                }
            } else if (rx.size > 0) {
                // 速率采样（接收侧）：写盘字节数已由 appendToRx 累加
                markSpeed(mid, rx.received)
                val pct = ((rx.received * 100) / rx.size).toInt()
                val nowUi = System.currentTimeMillis()
                if (pct != rx.lastPct && nowUi - rx.lastUiAt >= UI_THROTTLE_MS) {
                    rx.lastPct = pct
                    rx.lastUiAt = nowUi
                    // 从「已暂停」恢复：续传首个 chunk → 气泡回「发送中」（清标记）
                    pausedMids.remove(mid)
                    // 在途进度帧一律 SENDING（与 PC 端同口径）：以前非续传帧标 OK，
                    // AppCore.updateMessage 会把每个 1% 都当终态补写进历史（磁盘灌爆），
                    // 传输中气泡还显示成完成态、速率数字被 clearSpeed 提前回收。
                    listener?.onMessageUpdated(
                        MsgItem(
                            mid = mid, dir = Dir.PEER, kind = Kind.FILE, fileName = rx.name,
                            fileSize = rx.size, progress = pct, peerIp = rx.peerIp,
                            status = Status.SENDING
                        )
                    )
                }
            }
        }.onFailure { log("写入分片失败: ${it.message}") }
    }

    /** 文件夹传输开始（dir_meta）：挑定最终文件夹名并建气泡 */
internal fun Link.onDirMeta(s: Session, j: JSONObject) {
        val mid = j.optString("mid")
        val name = j.optString("name", "folder")
        val count = j.optInt("fileCount", 0)
        val total = j.optLong("totalSize", 0L)
        // 空文件夹只会走 dir_meta（没有成员 file_meta）→ 这里也打一次点
        trustContent(s.remoteIp, isFile = true)
        runCatching {
            val existing = synchronized(dirMap) { dirMap[mid] }
            val dir = existing ?: run {
                val folder = FileIO.uniqueFolderName(ctx, segSanitize(name))
                val d = DirRx(
                    mid, name, s.remoteIp, count, total, folder,
                    "下载/${FileIO.DOWNLOAD_SUBDIR}/$folder"
                )
                synchronized(dirMap) { dirMap[mid] = d }
                d
            }
            // 成员清单（v2.6）：对端 dl=1 时带来，收尾核对缺失用。没有 = 老端 → 退化为按数量核对
            val hasList = j.has("list")
            val list = j.optJSONArray("list")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val skipped = j.optInt("skipped", 0)
            synchronized(dir) {
                dir.count = count
                dir.total = total
                dir.skipped = skipped
                dir.lastActivity = System.currentTimeMillis()
                // 同一 mid 又来一轮 dir_meta = 对端重发整个文件夹（断链后从待发队列补发）。
                // 上一轮已收尾 → 计数与清单清零重算，目录**就地复用**（不新建「文件夹 (1)」）。
                if (dir.finished) {
                    dir.doneFiles = 0
                    dir.doneBytes = 0L
                    dir.failed = false
                    dir.lastPct = -1
                    dir.got.clear()
                    dir.bad.clear()
                }
                // 有清单用新清单；没有（对端老端 / 清单超限被丢弃）必须**清空旧清单** ——
                // 否则同一 mid 重发时残留的上一轮清单会用来核对，误报一堆「缺失」
                dir.expect = if (hasList) HashSet(list) else HashSet()
                dir.finished = false
            }
            if (skipped > 0) {
                log("注意：对端有 $skipped 个分支因目录过深/过大未纳入本次传输（非本端丢件）")
            }
            // 空子目录列表：MediaStore 没有「空目录」概念，无法落盘，明确告知而不是无声丢失
            val dirs = j.optJSONArray("dirs")
            if (dirs != null && dirs.length() > 0) {
                log("对端含 ${dirs.length()} 个空子目录（手机端不保留空目录结构）")
            }
            val m = MsgItem(
                mid = mid, dir = Dir.PEER, kind = Kind.FILE, fileName = name,
                fileSize = total, filePath = dir.desc, progress = 0,
                isDir = true, dirCount = count,
                ts = j.optLong("ts", System.currentTimeMillis()), peerIp = dir.peerIp
            )
            emit(m)
            if (count == 0) {
                // 整个文件夹为空：没有成员 file_done，直接落完成态
                okUpdate(m.copy(progress = 100, status = Status.OK))
                log("空文件夹接收完成 $name")
            } else {
                log("开始接收文件夹 $name（$count 个文件，${TextUtil.humanSize(total)}）→ ${dir.desc}")
            }
        }.onFailure { log("创建接收目录失败: ${it.message}") }
    }

    /**
     * 文件夹收尾（v2.6，对端 dl=1 时才有）。
     *
     * 老流程接收端没有「对端发完了」这个信号，只能靠 `doneFiles >= count` 猜；成员在发送端
     * 被跳过或发送失败时永远等不到 → **静默缺文件**。新流程由 dir_end 明确收尾：
     *  ① 有清单：与实收清单求差集，缺失项回发 dir_missing 让对端补发；
     *  ② last=1（对端不再补发）：无论是否缺件都定稿，缺件则明确标失败并逐项列出。
     */
internal fun Link.onDirEnd(s: Session, j: JSONObject) {
        val mid = j.optString("mid")
        if (mid.isEmpty()) return
        val last = j.optBoolean("last", true)
        val dir = synchronized(dirMap) { dirMap[mid] } ?: return
        var missing: List<String> = emptyList()
        var lack = 0
        var hasList = false
        synchronized(dir) {
            hasList = dir.expect.isNotEmpty()
            if (hasList) {
                missing = dir.missingNow()
            } else {
                // 对端没带清单（老端 / 清单超限被丢弃）：只能算出「差几个」，无法精确到项
                lack = (dir.count - dir.doneFiles).coerceAtLeast(0)
            }
        }
        val stamp = System.currentTimeMillis()
        if (!hasList) {
            // 无清单 → 无法用回执表达「缺哪些」（空回执只能表示收齐，会误导对端）。
            // 本端仍如实定稿：缺件就明确标失败；发送端那边等一拍超时后自行定稿。
            synchronized(dir) { dir.finished = true; dir.finishedAt = stamp }
            finishDirReceiving(dir, emptyList(), lack)
            return
        }
        if (missing.isEmpty()) {
            synchronized(dir) { dir.finished = true; dir.finishedAt = stamp }
            finishDirReceiving(dir, emptyList(), 0)
            // 回执（空清单 = 收齐）：发送端据此立即定稿，不必干等到超时
            sendOrDrop(s, Protocol.dirMissing(mid, emptyList()), "dir_missing", quiet = true)
            return
        }
        if (last) {
            // 对端已明说不再补发：定稿并回最后一版缺失清单（发送端据此如实标失败）
            synchronized(dir) { dir.finished = true; dir.finishedAt = stamp }
            finishDirReceiving(dir, missing, 0)
            sendOrDrop(s, Protocol.dirMissing(mid, missing), "dir_missing", quiet = true)
            return
        }
        log("文件夹 ${dir.name} 还差 ${missing.size} 个成员，已请求对端补发")
        sendOrDrop(s, Protocol.dirMissing(mid, missing), "dir_missing")
    }

    /** 接收端回执：这些成员没到（空清单 = 收齐）。发送端在 doSendFolderInner 里等它 */
internal fun Link.onDirMissing(j: JSONObject) {
        val mid = j.optString("mid")
        if (mid.isEmpty()) return
        val items = j.optJSONArray("items")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        } ?: emptyList()
        dirMissingBox[mid]?.offer(items)
    }

    /**
     * 文件夹定稿：更新气泡并如实汇报。**缺件一律标失败并列入日志** ——
     * 绝不把「差了几个文件」当成「发送成功」。
     */
internal fun Link.finishDirReceiving(dir: DirRx, missing: List<String>, lack: Int) {
        val hasList = dir.expect.isNotEmpty()
        val got = if (hasList) dir.got.size else dir.doneFiles
        val total = if (hasList) dir.expect.size else dir.count
        val bad = if (missing.isEmpty() && lack == 0 && dir.failed) "有成员校验失败" else ""
        val incomplete = missing.isNotEmpty() || lack > 0
        val pct = if (total > 0) ((got * 100) / total).coerceIn(0, 100) else 100
        listener?.onMessageUpdated(
            MsgItem(
                mid = dir.mid, dir = Dir.PEER, kind = Kind.FILE, fileName = dir.name,
                fileSize = dir.total, filePath = dir.desc,
                progress = if (incomplete) pct else 100, isDir = true, dirCount = total,
                status = if (incomplete || dir.failed) Status.FAILED else Status.OK,
                peerIp = dir.peerIp
            )
        )
        when {
            missing.isNotEmpty() -> log(
                "文件夹接收不完整 ${dir.name}：实收 $got/$total，缺 ${missing.size} 项 → " +
                    missing.take(20).joinToString(", ") + (if (missing.size > 20) " …（略）" else "")
            )
            lack > 0 -> log(
                "文件夹接收不完整 ${dir.name}：实收 $got/$total，缺 $lack 项" +
                    "（对端未带成员清单，无法精确定位到具体文件）"
            )
            dir.failed -> log("文件夹接收完成（$bad）${dir.name}：$got/$total")
            else -> log("文件夹接收完成 ${dir.name}：$got/$total，全部校验通过")
        }
        pruneDirs()
    }

    /**
     * 回收成品目录记录。**刻意保留**最近若干条成品目录：同一 mid 重发（断链后从待发
     * 队列补发整目录）时复用它，才不会另建「文件夹 (1)」——那正是用户看到的「原文件夹
     * 里少了文件」。
     */
    /**
     * 回收目录记录：
     *  ① **未收尾且闲置 10 分钟**（且无活动成员）→ 回收。防「没走到 dir_end 的 DirRx 永留」
     *     （成员被停滞中止 / 会话断开后对端永不重连的场景）。
     *  ② 成品目录超出 [MAX_KEEP_DIRS] → 回收最旧的。**刻意保留**最近若干条：同一 mid 重发
     *     （断链后从待发队列补发整目录）时复用它，才不会另建「文件夹 (1)」。
     * 由心跳线程每 [Link.STALL_SCAN_MS] 顺带调用 + 每次目录定稿时调用。
     */
internal fun Link.pruneDirs() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<String>()
        synchronized(dirMap) {
            for (e in dirMap.entries) {
                if (!e.value.finished && now - e.value.lastActivity > Link.DIR_STALE_MS) {
                    stale.add(e.key)
                }
            }
        }
        for (mid in stale) {
            // 还有成员在 rxMap 里活动就不收（chunk/file_done 会持续刷新 lastActivity，这里是双保险）
            val active = synchronized(rxMap) { rxMap.values.any { it.dirId == mid } }
            if (!active) {
                synchronized(dirMap) { dirMap.remove(mid) }
                log("回收闲置未完成目录记录 $mid")
            }
        }
        synchronized(dirMap) {
            if (dirMap.size <= MAX_KEEP_DIRS) return
            val over = dirMap.size - MAX_KEEP_DIRS
            val finished = dirMap.values.filter { it.finished }.sortedBy { it.finishedAt }.take(over)
            for (d in finished) dirMap.remove(d.mid)
        }
    }

internal fun Link.segSanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "folder_${System.currentTimeMillis()}" }
