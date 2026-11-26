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
import com.phonepctransfer.util.BusyLock
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock


import com.phonepctransfer.net.Link.Session

/**
 * 接收收尾专用单线程：`file_done` / `dir_end`（要等落盘收尾，最长 60s）从网络读线程挪出来。
 * 单线程还保证顺序：同一文件夹的成员 file_done 一定先于 dir_end 处理，
 * dir_end 的清单核对不会把「还在写盘」的成员误判成缺件。
 */
private val rxFinalizeExec: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pp-rx-finalize").apply { isDaemon = true }
    }

internal fun Link.runRxFinalize(tag: String, task: () -> Unit) {
    rxFinalizeExec.execute {
        runCatching { task() }.onFailure {
            log("$tag 收尾异常(${it.javaClass.simpleName}: ${it.message})")
        }
    }
}

// 接收管线(拆分自 Link.kt):Rx/DirRx 状态、文件/文件夹接收、落盘与校验;全部为 Link 扩展函数

/**
 * 接收进度帧的界面限流窗口(毫秒,与 PC 端同值):局域网传大文件时进度帧可一秒上百帧,5Hz 刷新对进度条足够;终态帧(完成/失败/暂停)不受限流
 */
internal const val UI_THROTTLE_MS = 200L

/** 2 期多通道：接收侧重排槽数。3 槽 = 允许领先 2 个单元（发送端 2 条通道各在飞 1 个单元） */
internal const val MULTI_SLOTS = 3

/** 多通道模式下不用的占位缓冲（不占内存） */
private val EMPTY_BUF = ByteArray(0)

/** 多通道：等一个槽被写线程腾空的超时。超过即判接收异常（断链重传），避免读线程无限期挂住 */
internal const val MULTI_SLOT_WAIT_MS = 30_000L

/** 单通道接收缓冲池块数：3 块 RX_BUF_SIZE 轮转（读线程在填 1 块 + 排队/写盘中最多 2 块）。
 *  缓冲池调大 = 写落后队列更深；磁盘慢于网络时读线程排队等空闲块（TCP 窗口随之收窄，形成背压） */
internal const val RX_BUF_COUNT = 3

/** 攒够多少字节才交给落盘线程：满 1MB 即写；不足 1MB 只在单文件收尾/续传查询/直读错位时冲 */
internal const val RX_WRITE_MIN_BYTES = 1 shl 20

/** 交给落盘线程的一块缓冲：缓冲 + 有效字节数（FIFO 排队，写线程按序落盘） */
internal class PendingBuf(val buf: ByteArray, val len: Int)

internal class Rx(
        val mid: String,
        val name: String,
        val size: Long,
        /** 所属设备 IP：收到的文件气泡要挂到这台设备的会话里 */
        val peerIp: String,
        /** 所属目录气泡 mid；空 = 普通单文件 */
        val dirId: String,
        /** 文件夹成员在目录内的相对路径(普通单文件为空);dir_end 核对时与对端清单求差集判「哪个成员没到」 */
        val relPath: String,
        /** 落盘位置描述（人看的文案） */
        var desc: String,
        /** 可直接读取/删除的地址：MediaStore 为 content:// URI，回退时为绝对路径 */
        val uri: String,
        var sink: OutputStream,
        /** 续传起始偏移（0 = 全新） */
        val startAt: Long,
        /**
         * 2 期多通道：true 时用「3 槽重排环」按单元号乱序收、顺序写；
         * false 时是单通道缓冲池顺序路径（bufPool 轮转 + pos）。
         */
        val multi: Boolean = false
    ) {
        // 单通道缓冲池：RX_BUF_COUNT 块轮转，正在写盘的块绝不会再被读线程复用
        //（根治旧双缓冲「换块只等 pending 不等 writeBusy」的读写同块竞态）；多通道时不给（由 3 个槽承担）
        var bufPool: Array<ByteArray> = if (multi) emptyArray() else Array(RX_BUF_COUNT) { ByteArray(Protocol.RX_BUF_SIZE) }
        var cur = if (multi) EMPTY_BUF else bufPool[0]
        var pos = 0
        /** 空闲块（flushLock 守护）：写线程写完一块归还到这里，读线程换块时从这里取 */
        val freeBufs = java.util.ArrayDeque<ByteArray>()
        /** 已收满待写块队列（flushLock 守护），FIFO；没有空闲块时读线程排队等 */
        val pendingQ = java.util.ArrayDeque<PendingBuf>()

        init {
            if (!multi) for (i in 1 until bufPool.size) freeBufs.addLast(bufPool[i])
        }
        /** 已接收字节（含缓冲中未落盘部分；续传时含前缀） */
        var received = startAt
        /** 期望的下一个分片 seq；文件已收满时为 Int.MAX_VALUE（不再收分片） */
        // 自适应帧下 seq 只是「本次尝试内的帧序号」，与文件偏移无关，一律从 0 期望起
        var expectSeq: Int =
            if (startAt >= size && size > 0) Int.MAX_VALUE else 0

        // ===== 2 期多通道：单元重排环（3 槽）=====

        /** 文件总单元数（1 单元 = CHUNK_SIZE） */
        val totalUnits: Int =
            if (size <= 0) 0 else ((size + Protocol.CHUNK_SIZE - 1) / Protocol.CHUNK_SIZE).toInt()
        /** 期望写入落盘的单元号（读线程按它挑槽，写线程只认它） */
        var expectUnit: Int = if (startAt > 0) (startAt / Protocol.CHUNK_SIZE).toInt() else 0
        // 槽 = 一个多通道单元（恒为 CHUNK_SIZE），不随单通道轮转缓冲（RX_BUF_SIZE）一起变大
        val slotBufs: Array<ByteArray>? =
            if (multi) Array(MULTI_SLOTS) { ByteArray(Protocol.CHUNK_SIZE) } else null
        /** 槽状态：0 空闲 1 接收中 2 已收满待写 3 写盘中 */
        val slotState = IntArray(MULTI_SLOTS)
        val slotUnit = IntArray(MULTI_SLOTS) { -1 }
        val slotLen = IntArray(MULTI_SLOTS)
        val slotLock = Object()

        var lastPct = -1
        /** 上一次向界面发进度帧的时刻（UI 限流，见 [UI_THROTTLE_MS]） */
        var lastUiAt = 0L
        val flushLock = Object()
        @Volatile var writerAlive = true
        /** 写线程代际号：resetRxFromZero 自增后旧线程自行退出，避免新旧两个写线程交错落盘 */
        @Volatile var writerGen = 0
        /**
         * 落盘线程正在写盘:缓冲已从队列取走但 sink.write() 还没返回。
         * 收尾判据必须是「队列空且 writeBusy 为假」,否则会在 sink.write() 中途关流落出 0 字节/截断文件
         */
        var writeBusy = false
        /** 已成功写进 sink 的字节数（诊断用） */
        @Volatile var writtenBytes = 0L
        /**
         * 上一次登记半成品台账时「已落盘字节」的水位：落盘每累计满 [Protocol.BLOCK_SIZE]（32MB）
         * 才 putPartial 一次（旧实现每交一块 1MB 就写一次台账，磁盘与注册表都吃亏）。
         * 断链最多丢「最后 32MB 内的登记精度」——续传起点本来就按 partialSize 实测磁盘并向下对齐，
         * 不依赖这里的值，所以节流不影响续传正确性。
         */
        var partialRegBytes = 0L
        var writer: Thread? = null
        /** 最近一次收到真实进度(分片追加成功/写盘成功)的时刻——接收停滞看门狗的依据;成功 append 后、写盘成功后各更新 */
        @Volatile var lastProgressAt = System.currentTimeMillis()

        /** 截断重来专用：换一套全新缓冲。旧写线程可能还卡在 sink.write 里握着旧块，
         *  复用旧池会重演「读写同块」竞态；旧块由 GC 在旧线程退出后回收 */
        fun rebuildBufPool() {
            if (multi) return
            bufPool = Array(RX_BUF_COUNT) { ByteArray(Protocol.RX_BUF_SIZE) }
            cur = bufPool[0]
            pos = 0
            freeBufs.clear()
            for (i in 1 until bufPool.size) freeBufs.addLast(bufPool[i])
        }
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
        /** 清单大小（dir_meta.sizes，与 list 逐项对应）：成员 file_done 时按它核对实收字节数 */
        val expectSizes = HashMap<String, Long>()
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
        /** 最近一次活动(dir_meta/成员 chunk/成员 file_done/成员被中止);未完成目录闲置 10 分钟即回收(见 pruneDirs) */
        var lastActivity: Long = System.currentTimeMillis()

        /** 本轮还没到的成员（expect 有清单时精确到项；无清单时用空串占位表示「差 N 项」）
         *  bad（落盘/校验失败）也要算「没到」并报给对端补发——不能从差集里减掉，否则永远补不回来 */
        fun missingNow(): List<String> =
            if (expect.isEmpty()) emptyList() else (expect - got).toList()

        /** 成员原始 relPath → 实际落盘 uri：dir_end 时按清单逐个核对磁盘用 */
        val memberUris = HashMap<String, String>()

        /** 已占用的清洗后相对路径：成员撞名时追加序号（对账仍用原始 relPath） */
        val usedSanitizedRel = HashSet<String>()
    }

    /** 量某个 uri 的真实字节数（量不到返回 -1） */
internal fun Link.uriSize(uri: String): Long = try {
        if (uri.startsWith("content://")) {
            ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize } ?: -1L
        } else java.io.File(uri).length()
    } catch (t: Throwable) {
        runCatching { FileIO.querySize(ctx, Uri.parse(uri)) }.getOrNull()?.takeIf { it >= 0 } ?: -1L
    }

    /**
     * 按清单**逐个核对磁盘**：文件不在，或清单声明了大小但对不上 → 一律算缺。
     * 内存里的 got 只代表这一轮收到过，磁盘被挪走/清空就失效，所以完整性只认这个判据。
     */
internal fun Link.dirMissingOnDisk(dir: DirRx): List<String> {
        val out = ArrayList<String>()
        for (rel in dir.expect) {
            val uri = dir.memberUris[rel]
            val want = dir.expectSizes[rel]
            val sz = if (uri == null) -1L else uriSize(uri)
            val ok = sz >= 0 && (want == null || want <= 0L || sz == want)
            if (!ok) out.add(rel)
        }
        return out
    }

    /** 已在盘上且大小对的成员（dir_have 用）：只报真实在盘上的，避免让对端漏传 */
internal fun Link.dirHaveOnDisk(dir: DirRx, candidates: Collection<String>): List<String> {
        val out = ArrayList<String>()
        for (rel in candidates) {
            if (dir.expect.isNotEmpty() && rel !in dir.expect) continue
            val uri = dir.memberUris[rel] ?: continue
            val want = dir.expectSizes[rel]
            val sz = uriSize(uri)
            if (sz >= 0 && (want == null || want <= 0L || sz == want)) out.add(rel)
        }
        return out
    }

    /** 等本目录的在途成员写完（dir_end 核对前用；局域网通常几十毫秒） */
internal fun Link.waitDirRxQuiet(dirId: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var busy = false
            synchronized(rxMap) {
                busy = rxMap.values.any { it.dirId == dirId }
            }
            if (!busy) return
            runCatching { Thread.sleep(30L) }.getOrElse { return }
        }
    }


internal fun Link.startWriterThread(rx: Rx): Thread =
        Thread({
            if (rx.multi) {
                multiWriteLoop(rx)
                return@Thread
            }
            val gen = rx.writerGen
            while (true) {
                val pb: PendingBuf
                synchronized(rx.flushLock) {
                    while (rx.pendingQ.isEmpty() && rx.writerAlive && rx.writerGen == gen) rx.flushLock.wait()
                    // 代际已换（resetRxFromZero）或已被停：旧线程必须退出，不能再和新线程抢队列
                    if (rx.writerGen != gen || rx.pendingQ.isEmpty()) return@Thread
                    pb = rx.pendingQ.removeFirst()
                    rx.writeBusy = true          // 从「取走缓冲」到「写完」，收尾必须等这一段
                    rx.flushLock.notifyAll()
                }
                try {
                    val w0 = System.nanoTime()
                    rx.sink.write(pb.buf, 0, pb.len)
                    TxStats.write(System.nanoTime() - w0)
                    rx.writtenBytes += pb.len
                    rx.lastProgressAt = System.currentTimeMillis()
                    TxStats.block(pb.len.toLong())
                    // 半成品登记按 32MB 一拍（用户要求：落盘优先 32MB 一次写入）
                    if (rx.writtenBytes - rx.partialRegBytes >= Protocol.BLOCK_SIZE) {
                        val p0 = System.nanoTime()
                        store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, rx.received)
                        TxStats.part(System.nanoTime() - p0)
                        rx.partialRegBytes = rx.writtenBytes
                    }
                } catch (t: Throwable) {
                    log("落盘失败 ${rx.name}（已写 ${rx.writtenBytes}/${rx.received}）: ${t.message}")
                } finally {
                    synchronized(rx.flushLock) {
                        rx.writeBusy = false
                        // 写完归还块供读线程复用；代际已换则不归还（reset 已换新池，旧块交给 GC）
                        if (rx.writerGen == gen) rx.freeBufs.addLast(pb.buf)
                        rx.flushLock.notifyAll()
                    }
                }
            }
        }, "pp-disk-${rx.mid}").apply { isDaemon = true }.also { it.start() }

    /**
     * 分片直读落点判定（P1-3 快路径）：顺序命中且当前缓冲放得下 → 返回落点，
     * 让 [Protocol.readFrameInto] 把载荷直接读进 8MB 轮转缓冲，省掉每帧一次 ByteArray 分配 + 一次 memcpy。
     * <p>放不下时**不再直接回落**（8M 帧一旦错位就会每帧现分配 8MB + 拷一遍）：先把当前半块交给
     * 落盘线程，让本帧从缓冲起点读。落盘是顺序 append，提前冲半块不改变正确性。
     * <p>只有「单帧比整块缓冲还大」/ 乱序 / 未知 mid 才返回 null（回落到原逻辑，行为不变）。
     */
internal fun Link.reserveDirectChunk(mid: String, seq: Int, len: Int): Protocol.ChunkSink? {
        val rx = synchronized(rxMap) { rxMap[mid] } ?: return null
        if (rx.multi) return reserveUnitSlot(rx, seq, len)
        if (rx.expectSeq != seq) return null
        if (len > Protocol.RX_BUF_SIZE) return null
        if (len > Protocol.RX_BUF_SIZE - rx.pos) {
            try {
                handOffFullBuffer(rx)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return Protocol.ChunkSink(rx.cur, rx.pos)
    }

    /** 多通道落点：按单元号选槽；槽被占用就等（TCP 窗口随之收窄，形成背压），同一单元重传则整槽重写 */
internal fun Link.reserveUnitSlot(rx: Rx, seq: Int, len: Int): Protocol.ChunkSink? {
        val bufs = rx.slotBufs ?: return null
        if (len > Protocol.CHUNK_SIZE) return null
        if (seq < rx.expectUnit) {
            // 已落盘单元的迟到/重复帧：丢弃即可，不影响正确性
            return null
        }
        val slot = ((seq % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS
        synchronized(rx.slotLock) {
            val deadline = System.currentTimeMillis() + MULTI_SLOT_WAIT_MS
            while (true) {
                if (!rx.writerAlive) throw java.io.IOException("接收已停止")
                // 窗口闸门：只允许 [expectUnit, expectUnit+MULTI_SLOTS) 的单元占槽。
                // 少这一道，远处的单元（例如 unit 4 与 unit 1 同槽）会抢走本窗口单元的槽，
                // 两条通道互相等对方腾槽 —— 2 期首测就是这样 30s 超时卡死的
                if (seq >= rx.expectUnit + MULTI_SLOTS) {
                    if (System.currentTimeMillis() > deadline) {
                        throw java.io.IOException(
                            "接收超时：单元 $seq 超出窗口（已落盘到 ${rx.expectUnit}）"
                        )
                    }
                    rx.slotLock.wait(50)
                    continue
                }
                when {
                    rx.slotState[slot] == 0 -> {
                        rx.slotState[slot] = 1
                        rx.slotUnit[slot] = seq
                        rx.slotLen[slot] = 0
                        return Protocol.ChunkSink(bufs[slot], 0)
                    }
                    rx.slotUnit[slot] == seq && rx.slotState[slot] == 1 -> {
                        // 同一单元的重传（上一条通道中途断了，槽里是半截数据）：从 0 整槽重写
                        rx.slotLen[slot] = 0
                        return Protocol.ChunkSink(bufs[slot], 0)
                    }
                    rx.slotUnit[slot] == seq && rx.slotState[slot] == 2 -> return null
                    else -> {
                        if (System.currentTimeMillis() > deadline) {
                            throw java.io.IOException("接收超时：单元槽被占用（unit=$seq）")
                        }
                        rx.slotLock.wait(50)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    /** 多通道：载荷已整单元直读进槽缓冲 0 处 —— 只标记「收满」，由写线程按单元顺序落盘 */
internal fun Link.commitUnitSlot(rx: Rx, seq: Int, len: Int) {
        val slot = ((seq % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS
        synchronized(rx.slotLock) {
            if (rx.slotUnit[slot] != seq || rx.slotState[slot] != 1) {
                log("单元落点状态不符（unit=$seq slot=$slot st=${rx.slotState[slot]}），忽略")
                return
            }
            rx.slotLen[slot] = len
            rx.slotState[slot] = 2
            rx.slotLock.notifyAll()
        }
    }

    /** 回落路径（没抢到直读落点，载荷已在堆上）：先按窗口认领槽，整单元拷进去，再标记收满 */
internal fun Link.copyIntoSlot(rx: Rx, seq: Int, data: ByteArray, off: Int, len: Int) {
        if (rx.slotBufs == null) return
        // 认领槽（窗口闸门 / 同单元重写都在里面）：直读失效时这条路径同样要占槽，
        // 否则 commit 会因「槽不归这个单元」被拒，数据直接丢
        val sink = reserveUnitSlot(rx, seq, len) ?: run {
            log("单元回落无槽（unit=$seq），忽略")
            return
        }
        if (sink.off + len > sink.buf.size) {
            log("单元回落超缓冲（unit=$seq len=$len），忽略")
            return
        }
        System.arraycopy(data, off, sink.buf, sink.off, len)
        commitUnitSlot(rx, seq, len)
    }

    /**
     * 多通道落盘线程：只认 [Rx.expectUnit] 那个槽，收到就按顺序写 —— 写入顺序与 1 期完全一致，
     * 因此 MediaStore 顺序流 / 续传 / 校验 / 目录记账全都不用改。
     */
internal fun Link.multiWriteLoop(rx: Rx) {
        val bufs = rx.slotBufs ?: return
        while (true) {
            var slot = -1
            var len = 0
            synchronized(rx.slotLock) {
                while (slot < 0) {
                    if (!rx.writerAlive) return
                    val s = ((rx.expectUnit % MULTI_SLOTS) + MULTI_SLOTS) % MULTI_SLOTS
                    if (rx.slotState[s] == 2 && rx.slotUnit[s] == rx.expectUnit) {
                        slot = s
                        len = rx.slotLen[s]
                        rx.slotState[s] = 3
                    } else {
                        rx.slotLock.wait(50)
                    }
                }
            }
            val buf = bufs[slot]
            rx.writeBusy = true
            val w0 = System.nanoTime()
            try {
                rx.sink.write(buf, 0, len)
                TxStats.write(System.nanoTime() - w0)
                rx.writtenBytes += len
                // 多通道下「收到」的记账点在落盘这一刻（槽里只是暂存）：
                // 少这一行 received 一直是 startAt，进度 / 半成品台账 / 收尾校验全错
                rx.received += len
                rx.lastProgressAt = System.currentTimeMillis()
                TxStats.block(len.toLong())
                // 半成品登记按 32MB 一拍（与单通道路径一致）
                if (rx.writtenBytes - rx.partialRegBytes >= Protocol.BLOCK_SIZE) {
                    val p0 = System.nanoTime()
                    store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, rx.received)
                    TxStats.part(System.nanoTime() - p0)
                    rx.partialRegBytes = rx.writtenBytes
                }
            } catch (t: Throwable) {
                log("落盘失败 ${rx.name}（已写 ${rx.writtenBytes}/${rx.received}）: ${t.message}")
            } finally {
                synchronized(rx.slotLock) {
                    rx.slotState[slot] = 0
                    rx.slotUnit[slot] = -1
                    rx.slotLen[slot] = 0
                    rx.expectUnit++
                    rx.writeBusy = false
                    rx.slotLock.notifyAll()
                }
            }
        }
    }

    /** 载荷已直读进 rx.cur 的 off 处：只推进游标与记账，不做拷贝 */
internal fun Link.commitDirectChunk(rx: Rx, off: Int, len: Int) {
        rx.pos = off + len
        rx.received += len
        rx.expectSeq++
        // 满 1MB 即交落盘线程（含写满整块的情形），不再等 8MB 攒满
        if (rx.pos >= RX_WRITE_MIN_BYTES) {
            val w0 = System.nanoTime()
            handOffFullBuffer(rx)
            TxStats.waitBuf(System.nanoTime() - w0)
        }
    }

    /** 缓冲池交接：当前块（≥1MB/写满/直读错位）入队交落盘线程，读线程取下一块空闲块继续填；
     *  没有空闲块 = 磁盘写不过来 → 排队等（TCP 窗口随之收窄，形成背压）。
     *  正在写盘的块绝不会再交到读线程手里——根治旧双缓冲「只等 pending 不等 writeBusy」的换块竞态 */
internal fun Link.handOffFullBuffer(rx: Rx) {
        synchronized(rx.flushLock) {
            if (rx.pos == 0) return
            rx.pendingQ.addLast(PendingBuf(rx.cur, rx.pos))
            rx.pos = 0
            while (rx.freeBufs.isEmpty()) {
                if (!rx.writerAlive) throw java.io.IOException("接收已停止，无空闲缓冲")
                rx.flushLock.wait()
            }
            rx.cur = rx.freeBufs.removeFirst()
            rx.flushLock.notifyAll()
        }
    }

    /** 追加一个分片到缓冲（自动切块换缓冲）；[off] 为分片在 [data] 中的起始偏移 */
internal fun Link.appendToRx(rx: Rx, data: ByteArray, len: Int, off: Int = 0) {
        var src = off
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
            rx.pos += n
            rx.received += n
            src += n
            remaining -= n
        }
        // 满 1MB 即写：尾段攒够阈值就交落盘线程，不足 1MB 留到单文件收尾/续传查询再冲
        if (rx.pos >= RX_WRITE_MIN_BYTES) {
            val w0 = System.nanoTime()
            handOffFullBuffer(rx)
            TxStats.waitBuf(System.nanoTime() - w0)
        }
    }

    /** 把接收端缓冲里未落盘的部分全部冲到磁盘（续传查询时调用，让 offset 反映真实落盘量） */
internal fun Link.flushRxNow(rx: Rx) {
        synchronized(rx.flushLock) {
            if (rx.pos == 0) return
            rx.pendingQ.addLast(PendingBuf(rx.cur, rx.pos))
            rx.pos = 0
            while (rx.freeBufs.isEmpty()) {
                if (!rx.writerAlive) return
                rx.flushLock.wait()
            }
            rx.cur = rx.freeBufs.removeFirst()
            rx.flushLock.notifyAll()
        }
    }

    /** 传输收尾：剩余不满一块直接落盘，等全部写完，关流。此后按磁盘实际字节数做大小核对 */
internal fun Link.finishRx(rx: Rx) {
        if (rx.multi) {
            // 多通道：等写线程把重排环里剩下的单元按顺序落完，再关流
            val deadline = System.currentTimeMillis() + 30_000L
            synchronized(rx.slotLock) {
                while (rx.expectUnit < rx.totalUnits && rx.writerAlive &&
                    System.currentTimeMillis() < deadline
                ) {
                    rx.slotLock.wait(100)
                }
            }
            if (rx.expectUnit < rx.totalUnits) {
                log("多通道收尾超时：已落盘 ${rx.expectUnit}/${rx.totalUnits} 个单元")
            }
            rx.writerAlive = false
            synchronized(rx.slotLock) { rx.slotLock.notifyAll() }
            runCatching { rx.sink.close() }
            return
        }
        synchronized(rx.flushLock) {
            // 单文件传输完成：剩余不足 1MB 也全部冲出去
            if (rx.pos > 0) {
                rx.pendingQ.addLast(PendingBuf(rx.cur, rx.pos))
                rx.pos = 0
                rx.flushLock.notifyAll()
            }
            // ⚠️ 关键：队列空只说明缓冲被取走，最后一块可能正握在写盘线程手里。
            // 少等 writeBusy 这一下，就会在 sink.write() 中途关流 → 0 字节/截断文件。
            // P4-8：60s 超时，磁盘/MediaStore 卡死时不能把网络读线程永久钉在这里
            val deadline = System.currentTimeMillis() + 60_000L
            while (rx.pendingQ.isNotEmpty() || rx.writeBusy) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) {
                    log("收尾等待落盘超时 60s（${rx.name}），按未收全处理并强制关流")
                    break
                }
                rx.flushLock.wait(minOf(left, 200L))
            }
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
        // 与 abortRx 各管一半出口：取消/中止共用同一把锁的释放点，任何调用点都不会再漏
        BusyLock.end("rx:${rx.mid}")
        rx.writerAlive = false
        synchronized(rx.flushLock) {
            rx.pendingQ.clear()
            rx.flushLock.notifyAll()
        }
        synchronized(rx.slotLock) { rx.slotLock.notifyAll() }
        runCatching { rx.sink.close() }
        FileIO.deletePartial(ctx, rx.uri)
        store?.removePartial(rx.mid)
        // 普通单文件取消 = 任务结束，释放单任务槽；文件夹成员随目录任务走（不在这里释放）
        if (rx.dirId.isEmpty()) releaseTask(rx.mid, notifyPeer = false)
    }

    /** 中止接收（区别于 cancelRx）：只停落盘线程、关流，**不删文件、不清注册表** —— 断链≠取消，半成品保留供续传 */
internal fun Link.abortRx(rx: Rx) {
        // 断链/30s 停滞/取消共用此出口；tags.remove 幂等，重复 end 无副作用（P1-2）
        BusyLock.end("rx:${rx.mid}")
        rx.writerAlive = false
        synchronized(rx.flushLock) {
            rx.pendingQ.clear()
            rx.flushLock.notifyAll()
        }
        synchronized(rx.slotLock) { rx.slotLock.notifyAll() }
        runCatching { rx.sink.close() }
        // 单文件中止（断链/30s 停滞）= 本机任务结束，释放槽位；文件夹成员等 dir_end 定稿时再释放
        if (rx.dirId.isEmpty()) releaseTask(rx.mid, notifyPeer = false)
    }

    /**
     * 会话断开:中止该对端名下所有活动接收;文件与 partials 注册表原样保留(对端重连后 file_query 仍可命中续传,永久放弃则由过期清理兜底回收)
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
            // 断链后任务槽必须还给本机（文件夹成员不会在 abortRx 里逐条释放），否则重连后永远「设备忙」
            releaseTaskFor(peerIp, "recv")
        }
        // 顺带回收目录记录(未完成目录闲置 10 分钟兜底/成品超限量),此刻立即跑一次不等下个心跳;
        // 顺带回收超龄半成品(只回收 ts 超 3 天的条目,不碰为续传/停滞保留的新鲜半成品)
        pruneDirs()
        store?.purgeStalePartials(Link.PARTIAL_MAX_AGE_MS)
    }

    /**
     * 接收停滞看门狗:某路接收 30s 无任何进度(对端半死但 TCP 心跳仍正常)时中止该路(心跳线程每 5s 顺带调用)。
     * 触发条件:now-lastProgressAt>30s 且 pending==null 且 !writeBusy;在 rxMap 锁内二次确认并先摘通道,避免与收尾竞态
     */
    internal fun Link.scanStalledRx() {
        val now = System.currentTimeMillis()
        val stalled = mutableListOf<Rx>()
        synchronized(rxMap) {
            val it = rxMap.entries.iterator()
            while (it.hasNext()) {
                val rx = it.next().value
                if (now - rx.lastProgressAt > Link.STALL_TIMEOUT_MS &&
                    rx.pendingQ.isEmpty() && !rx.writeBusy
                ) {
                    stalled.add(rx)
                    it.remove()
                }
            }
        }
        stalled.forEach { stallAbort(it) }
    }

    /**
     * 停滞中止:断链 abortRx 的超时触发版(不删文件、不清 partials,半成品保留供对端重连续传);
     * 调用方已在锁内把 mid 从 rxMap 摘掉
     */
    internal fun Link.stallAbort(rx: Rx) {
        // 1. 把缓冲池中已完整收到的字节全部落盘,进度最大化;缓冲内容必为 CHUNK 对齐
        //    (发送按 CHUNK_SIZE 切片、seq 严格顺序)→ 落盘量满足 aligned 判定,续传 offset 有效
        try {
            flushRxNow(rx)
        } catch (_: InterruptedException) {
        }
        // 等最后一块写完再关流(writeBusy 时关流会在 sink.write() 中途截断);
        // 加 10s 上限:万一磁盘恰好卡死,不让心跳线程被永久拖住(拖住会停掉断链检测)
        synchronized(rx.flushLock) {
            val deadline = System.currentTimeMillis() + 10_000L
            while (rx.pendingQ.isNotEmpty() || rx.writeBusy) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try { rx.flushLock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
        // 2. 停写线程、关流（不删文件、不清注册表）
        abortRx(rx)
        // 文件夹成员不做字节续传：停滞就当场丢弃这半份，等 dir_end 清单对账后按文件粒度重发。
        // 不再置 PAUSED、不登记半成品 —— 那条「等待对端续传」的提示对文件夹成员没有意义
        if (rx.dirId.isNotEmpty()) {
            log("文件夹成员接收停滞，丢弃半成品，等清单对账后整文件重发：${rx.name}（已收 ${rx.received}/${rx.size} B）")
            return
        }
        // 单文件:记「暂停过」标记,下一个进度帧(file_meta 重到/续传首 chunk)到达时气泡回「发送中」;
        // 目录成员不需要(首个续传 chunk 本就把目录气泡发成 SENDING)
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
     * 2 期：多通道开传声明（在 file_meta 之前）。只登记该 mid 的通道数，
     * 真正建重排环是在 file_meta 建 Rx 时按 [multiMids] 决定。
     */
internal fun Link.onXeBegin(s: Session, j: JSONObject) {
        val mid = j.optString("mid", "")
        if (mid.isEmpty()) return
        val unit = j.optInt("unit", Protocol.CHUNK_SIZE)
        val lanes = j.optInt("lanes", 1)
        if (unit != Protocol.CHUNK_SIZE) {
            log("多通道声明单元 ${unit}B 与本端 ${Protocol.CHUNK_SIZE}B 不一致，该文件按单通道收")
            return
        }
        if (lanes < 2) return
        multiMids[mid] = lanes
        log("多通道接收声明 mid=$mid lanes=$lanes unit=$unit")
    }

    /**
     * 新建接收通道。dirId 非空时按「文件夹名/相对路径」落盘。
     * peerIp = 发起这次传输的对端 IP(收到的气泡挂到它名下,归属由会话决定)
     */
internal fun Link.createRx(
        mid: String, name: String, size: Long, peerIp: String, mime: String,
        relPath: String, dirId: String, startAt: Long, uriHint: String? = null,
        multi: Boolean = false
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
            val effectiveRel = if (dir != null) {
                // 成员清洗撞名（"a?.txt"/"a*.txt" 都变 "a_.txt"）时追加序号，避免后到覆盖先到
                val cleaned = FileIO.sanitizeRelPath(relPath)
                synchronized(dir) {
                    var n = 1
                    var rel = cleaned
                    while (!dir.usedSanitizedRel.add(rel)) {
                        n++
                        rel = FileIO.withOrdinalRel(cleaned, n)
                    }
                    "${dir.folder}/$rel"
                }
            } else relPath
            // P2-4：文件夹成员按相对路径覆盖旧文件（与 PC 端 directory 落盘口径一致）；单文件仍走唯一名
            val s = FileIO.createSink(ctx, name, mime, effectiveRel, overwrite = dir != null)
            sink = s.out; uri = s.uri; desc = s.desc
            dir?.let { synchronized(it) { it.receivedUris.add(uri) } }
            if (dir != null && relPath.isNotEmpty()) {
                synchronized(dir) { dir.memberUris[relPath] = uri }
            }
        }
        val rx = Rx(mid, name, size, peerIp, dirId, relPath, desc, uri, sink, startAt, multi)
        // P4-1：不再同步读取整段前缀算 SHA-256（1GB 半成品会把读线程卡 30s+，触发 35s 判死）；
        // 前缀正确性由 32MB 分块校验在 file_query 阶段逐块核对
        store?.putPartial(mid, uri, name, size, rx.received)
        rx.writer = startWriterThread(rx)
        return rx
    }

    /**
     * 对端开传前问本端有没有半成品:
     *  1. 活动通道还在(上次会话刚断)→ 冲掉未落盘缓冲,原地续传; 2. 本端已存好完整一份 → 直接答「收全了」;
     *  3. 注册表里有半成品(对端重启过)→ 追加打开、重算前缀摘要,准备好续传通道; 4. 都没有 → 从头传
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
            // 续传偏移先按 32MB 块边界对齐（末尾不足一块的「撕裂块」整块重发），
            // 再等发送端发起大小校验（blk_verify），核验通过后截断落盘并建续传通道
            val aligned = if (avail in 1L..size) (avail / Protocol.BLOCK_SIZE) * Protocol.BLOCK_SIZE else 0L
            if (uri.isNotEmpty() && avail > 0L && aligned > 0L) {
                resumePrep[mid] = Link.ResumePrep(mid, name, size, s.remoteIp, uri)
                log("断点续传查询 $name：半成品 ${TextUtil.humanSize(avail)}，" +
                    "块边界对齐到 ${TextUtil.humanSize(aligned)}，等发送端大小校验")
                sendOrDrop(
                    s, Protocol.fileResume(mid, true, aligned, "", needBlk = true),
                    "file_resume", quiet = true
                )
                return
            } else {
                // 弃用半成品：注册表条目和磁盘文件一起清，否则孤儿文件永远没人回收
                if (uri.isNotEmpty()) FileIO.deletePartial(ctx, uri)
                store?.removePartial(mid)
            }
        }
        sendOrDrop(s, Protocol.fileResume(mid, false, 0L, ""), "file_resume", quiet = true)
    }

    /**
     * 接收端收到发送端的大小校验请求：按 32MB 块核对 [0, offset) 是否都在盘上（只量大小，不做 hash），
     * 截断半成品到最后一个完整块边界，建 Rx 并回 blk_ack（带实际核验偏移）。
     * 截断失败（provider 不支持随机写）→ 删半成品、回 false，让发送端从 0 重传（绝不 append 到脏尾部）。
     */
internal fun Link.onBlkVerify(s: Session, j: JSONObject) {
        val mid = j.optString("mid")
        val offset = j.optLong("offset", 0L)
        val prep = resumePrep.remove(mid)
        if (prep == null) {
            sendOrDrop(s, Protocol.blkAck(mid, -1, offset > 0, offset), "blk_ack", quiet = true)
            return
        }
        val target = offset.coerceIn(0L, prep.size)
        if (target <= 0L) {
            // 发送端决定从头重发：删掉旧半成品（注册表+磁盘文件都要清，否则留孤儿），Rx 由后面的 file_meta 新建
            store?.removePartial(mid)
            FileIO.deletePartial(ctx, prep.uri)
            log("续传改为从头接收 ${prep.name}（旧半成品已删除）")
            sendOrDrop(s, Protocol.blkAck(mid, -1, true, 0L), "blk_ack", quiet = true)
            return
        }
        // 大小校验：盘上实际字节不够覆盖请求前缀时，退到最后一个完整 32MB 块边界
        //（只 stat 一次文件大小，逐块覆盖判定 = 大小比较，不读内容、不算 hash）
        val disk = partialSize(prep.uri)
        val verified = (minOf(target, disk) / Protocol.BLOCK_SIZE) * Protocol.BLOCK_SIZE
        if (verified < target) {
            log("大小校验 ${prep.name}：盘上 ${TextUtil.humanSize(disk)} 不足请求前缀 " +
                "${TextUtil.humanSize(target)}，退到块边界 ${TextUtil.humanSize(verified)}")
        }
        if (verified <= 0L) {
            store?.removePartial(mid)
            FileIO.deletePartial(ctx, prep.uri)
            log("大小校验：半成品不足一个完整块（${prep.name}），改从头接收")
            sendOrDrop(s, Protocol.blkAck(mid, -1, true, 0L), "blk_ack", quiet = true)
            return
        }
        if (!FileIO.truncateLen(ctx, prep.uri, verified)) {
            store?.removePartial(mid)
            FileIO.deletePartial(ctx, prep.uri)
            log("半成品截断到 ${TextUtil.humanSize(verified)} 失败（${prep.name}），改从头接收")
            sendOrDrop(s, Protocol.blkAck(mid, -1, false, 0L), "blk_ack", quiet = true)
            return
        }
        runCatching {
            val mime = FileIO.mimeOf(prep.name)
            val rx = createRx(
                mid, prep.name, prep.size, prep.peerIp, mime, "", "", verified,
                uriHint = prep.uri
            )
            synchronized(rxMap) { rxMap[mid] = rx }
            BusyLock.begin("rx:$mid")
            log("续传就绪 ${prep.name}：大小核验通过，已截断到 ${TextUtil.humanSize(verified)}，等对端从该块起发")
            sendOrDrop(s, Protocol.blkAck(mid, -1, true, verified), "blk_ack", quiet = true)
        }.onFailure { t ->
            log("续传通道创建失败（${t.message}），改从头接收 ${prep.name}")
            store?.removePartial(mid)
            FileIO.deletePartial(ctx, prep.uri)
            sendOrDrop(s, Protocol.blkAck(mid, -1, false, 0L), "blk_ack", quiet = true)
        }
    }

    /** 发送端：收到大小核验结果（ok + 实际核验偏移） */
internal fun Link.onBlkAck(j: JSONObject) {
        val mid = j.optString("mid")
        val ok = j.optBoolean("ok", false)
        blkAckWaiters[mid]?.offer(Link.BlkAckResult(ok, j.optLong("offset", 0L)))
    }

internal fun Link.onFileResume(j: JSONObject) {
        val mid = j.optString("mid")
        val ok = j.optBoolean("ok", false)
        val offset = if (ok) j.optLong("offset", 0L) else 0L
        resumeNeedBlk[mid] = ok && j.optBoolean("needBlk", false) && offset > 0L
        resumeWaiters[mid]?.offer(offset)
    }

    /** 半成品当前实际大小（content:// 查 MediaStore，路径看文件长度） */
internal fun Link.partialSize(uri: String): Long = if (uri.isEmpty()) 0L
    else if (uri.startsWith("content://")) FileIO.querySize(ctx, Uri.parse(uri))
    else runCatching { File(uri).length() }.getOrDefault(0L)

    /**
     * 这条 mid 本端是否已存成过一份完整的收讫文件(是→返回磁盘实际字节数,否→0);
     * 文件必须真的还在、字节数与对端声明值对得上(与 PC 端 dupSavedPath 同判据);用户删掉文件后重收则照常收
     */
internal fun Link.savedCompleteSize(peerIp: String, mid: String, declaredSize: Long): Long {
        if (mid.isEmpty()) return 0L
        val m = store?.savedFileOf(peerIp, mid) ?: return 0L
        val onDisk = partialSize(m.localUri.ifEmpty { m.filePath })
        return if (onDisk > 0L && (declaredSize <= 0L || onDisk == declaredSize)) onDisk else 0L
    }

    /** 对端取消了某个文件/文件夹的发送：中止接收、删除半成品、把该条从界面移除。 */
internal fun Link.onRemoteFileCancel(mid: String) {
        if (mid.isEmpty()) return
        // 对端明确「这条不要了」：本端若是发送方，立刻让发送循环停下（cancelled 是一次性信号）；
        // 同时记取消墓碑 + 出待发队列 —— 否则排在队列里的补发任务会把同一条又发回去
        cancelled.add(mid)
        markCanceled(mid)
        store?.removePending(mid)
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
            // 整个目录任务被取消：任务槽一起释放（成员 rx 的 cancelRx 不会替目录释放）
            releaseTask(mid, notifyPeer = false)
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
                        // 重复投递闸门:同一条 mid 本端已存好一份完整的 → 回 ack、绝不建第二个接收通道
                        // (否则 createRx 会另起唯一名,落盘多出一份 (1));文件夹成员不判(成员 mid 不进 FILE 历史,判了会误拦 dir_missing 补发)
                        if (dirId.isEmpty()) {
                            val saved = savedCompleteSize(s.remoteIp, mid, size)
                            if (saved > 0L) {
                                log("重复投递 $name（mid=$mid）本端已存过 $saved B，跳过落盘")
                                sendOrDrop(s, Protocol.ack(mid, true), "ack", quiet = true)
                                return@runCatching
                            }
                        }
                        // 单任务槽：本机正在传别的任务 → 回 busy（发送端把条目留队列，等本机空闲的 sync_req 重试）。
                        // 文件夹成员按「目录 mid」占槽；已被回绝的目录，其成员也一律回 busy（不再隐式建目录）。
                        if (dirId.isNotEmpty() && isDirBusy(dirId)) {
                            sendOrDrop(s, Protocol.busy(mid, name, taskHolder(), "member"), "busy", quiet = true)
                            log("设备忙：丢弃文件夹成员 $name（目录 $dirId 已被回绝，mid=$mid）")
                            return@runCatching
                        }
                        val slotMid = if (dirId.isEmpty()) mid else dirId
                        if (!claimTask(slotMid, s.remoteIp, "recv", if (dirId.isEmpty()) name else dirId)) {
                            val holder = taskHolder()
                            if (dirId.isNotEmpty()) markDirBusy(dirId)
                            sendOrDrop(
                                s,
                                Protocol.busy(mid, name, holder, if (dirId.isEmpty()) "file" else "dir"),
                                "busy", quiet = true
                            )
                            log("设备忙（正在 $holder），拒绝接收 $name（mid=$mid），已回 busy")
                            return@runCatching
                        }
                        if (dirId.isNotEmpty()) busyDirIds.remove(dirId)
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
                        rx = createRx(
                            mid, name, size, s.remoteIp, mime, relPath, dirId, 0L,
                            multi = (multiMids.remove(mid) ?: 0) >= 2
                        )
                        synchronized(rxMap) { rxMap[mid] = rx }
                        BusyLock.begin("rx:$mid")   // 接收期间持 CPU/WiFi 锁，落盘不受息屏影响
                    } else if (offset <= 0 || offset != rx.received) {
                        // 对端要求从头（或偏移对不上，如前缀校验失败）：截断重传
                        log("file_meta 偏移 $offset 与本端 ${rx.received} 不符，${rx.name} 从头重收")
                        resetRxFromZero(rx)
                    } else {
                        // 同一会话内「暂停→续发」：偏移接得上，接着收；但发送端每次尝试的帧序号都从 0 重来
                        rx.expectSeq = 0
                    }
                    // Rx 已建好并登记进 rxMap → 通知发送端「可以起数据通道了」。
                    // 必须等这一步之后才允许对端发分片：早到的分片会因 rxMap 查不到 mid 被丢，
                    // 缺口补不齐就把 3 槽重排环卡到 30s 超时（2 期首测 1GB 卡死）。
                    val ackRx = rx
                    if (ackRx.multi) {
                        runCatching { sendOrDrop(s, Protocol.xeReady(mid), "xe_ready", quiet = true) }
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
                                // 在途进度帧一律 SENDING(旧代码标 OK 的后果:AppCore.updateMessage
                                // 把每个 1% 都当终态补写历史,历史文件被灌爆、速率采样器又被提前回收)
                                status = Status.SENDING
                            )
                        )
                    }
                    log("开始接收文件 $name (${if (size < 0) "未知大小" else "$size B"}" +
                        if (r.startAt > 0) "，续传自 ${TextUtil.humanSize(r.startAt)}" else "" + ")")
                }.onFailure {
                    log("创建接收文件失败: ${it.message}")
                    // 任务槽已经占到才走到这里；单文件建 Rx 失败要立刻释放，否则本机一直「忙」
                    if (dirId.isEmpty()) releaseTask(mid, notifyPeer = false)
                    sendOrDrop(s, Protocol.ack(mid, false), "ack")
                }
            }
            "file_done" -> {
                val mid = j.optString("mid")
                val rx = synchronized(rxMap) { rxMap.remove(mid) }
                if (rx == null) {
                    // 通道已被中止（停滞/去重收尾/会话断开）：已落盘就如实确认，没落盘就明确拒绝，别让发送端干等 60s
                    val declared = j.optLong("size", -1L)
                    val saved = savedCompleteSize(s.remoteIp, mid, declared)
                    val done = saved > 0L
                    sendOrDrop(s, Protocol.ack(mid, done), "ack", quiet = true)
                    log("file_done 到达时接收通道已不在（mid=$mid），按已落盘=$done 回执")
                    return
                }
                BusyLock.end("rx:$mid")
                var ioOk = true
                runCatching { finishRx(rx) }.onFailure {
                    ioOk = false
                    log("落盘收尾失败 ${rx.name}: ${it.message}")
                }
                // 落盘实测:内存记账校的是「网络收到的字节」,磁盘被写空/截断它照样判通过,
                // 所以关流后再量一次磁盘真实字节数
                val diskSize = diskSizeOf(rx)
                val sizeOk = diskSize < 0 || diskSize == rx.received
                if (!sizeOk) {
                    ioOk = false
                    log("落盘字节数不符 ${rx.name}：磁盘 ${diskSize}B ≠ 应收 ${rx.received}B")
                }
                // 再和「对端声明的文件大小」比一次:源被截断时 sha256 算的是实际发出的字节,照样判通过;
                // 只判「收少了」,声明值偏小(提供器估报大小)不判失败,免得把能用的传输判死
                if (rx.size > 0 && rx.received < rx.size) {
                    ioOk = false
                    log("实收字节少于声明 ${rx.name}：实收 ${rx.received}B < 声明 ${rx.size}B")
                }
                // 校验只核对大小（不做 hash）：落盘成功且字节数相符即通过；
                // 失败时半成品保留（续传来的仍可继续续传，超龄由过期清理兜底）
                if (ioOk) {
                    store?.removePartial(mid)
                }
                val dir = if (rx.dirId.isEmpty()) null else synchronized(dirMap) { dirMap[rx.dirId] }
                if (dir != null) {
                    val status: Status
                    val pct: Int
                    synchronized(dir) {
                        // 按清单大小核对（dir_meta.sizes）：成员实收字节与清单不符 → 判未收妥
                        val expectSize = dir.expectSizes[rx.relPath]
                        if (expectSize != null && expectSize > 0 && rx.received != expectSize) {
                            ioOk = false
                            log("成员大小与清单不符 ${rx.name}：实收 ${rx.received}B ≠ 清单 ${expectSize}B")
                        }
                        dir.doneFiles++
                        dir.lastActivity = System.currentTimeMillis()
                        // 逐项记账：成功的进 got、落盘/大小核对失败的进 bad —— dir_end 核对的依据
                        if (rx.relPath.isNotEmpty()) {
                            if (ioOk) dir.got.add(rx.relPath) else dir.bad.add(rx.relPath)
                        }
                        if (!ioOk) dir.failed = true
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
                        // 数量收齐只是兜底判据(老发送端不发 dir_end),新发送端一律以 dir_end 核对为准;
                        // 目录不从 dirMap 移除:成品目录保留给重发/补发复用,保留态由 pruneDirs() 限量回收
                        if (allDone) dir.finished = true
                    }
                    log("文件夹成员完成 ${rx.name}（${dir.doneFiles}/${dir.count}）大小核对=${if (ioOk) "通过" else "失败"}")
                } else {
                    // 单个文件收完：丢掉速率采样器（气泡靠样本过期自动隐藏数字）
                    endSpeed(mid)
                    emit(
                        MsgItem(
                            mid = mid, dir = Dir.PEER, kind = Kind.FILE, fileName = rx.name,
                            fileSize = rx.received, filePath = rx.desc, localUri = rx.uri,
                            progress = 100,
                            status = if (ioOk) Status.OK else Status.FAILED,
                            ts = System.currentTimeMillis(), peerIp = rx.peerIp
                        )
                    )
                    log("文件接收完成 ${rx.name} ${rx.received} B 大小核对=${if (ioOk) "通过" else "失败（落盘/字节数未过）"}")
                }
                sendOrDrop(s, Protocol.ack(mid, ioOk), "ack")
                // 触发过续传的普通单文件:把落盘实际字节数回发对端,与清单大小核对(大小校验,不做 hash)
                if (ioOk && rx.startAt > 0 && rx.dirId.isEmpty()) {
                    val sz = if (diskSize >= 0) diskSize else rx.received
                    sendOrDrop(s, Protocol.fileVerify(mid, sz), "file_verify", quiet = true)
                    log("已回发续传文件大小（mid=$mid，$sz B）")
                }
                // 普通单文件收尾：释放单任务槽（文件夹成员不释放，槽归整个目录任务）
                if (rx.dirId.isEmpty()) releaseTask(mid)
            }
        }
    }

    /** 截断重来：停写线程 → 等缓冲清空 → 以截断方式重开 → 状态归零 → 重启写线程 */
internal fun Link.resetRxFromZero(rx: Rx) {
        // 先换代际：旧写线程即便卡在 sink.write，返回后也会发现代际不符而退出
        rx.writerGen++
        rx.writerAlive = false
        synchronized(rx.flushLock) { rx.flushLock.notifyAll() }
        rx.writer?.join(2000)
        synchronized(rx.flushLock) {
            rx.pendingQ.clear()
            // 换一套全新缓冲：旧写线程可能还卡在 sink.write 里握着旧块，复用旧池会重演「读写同块」竞态
            rx.rebuildBufPool()
            rx.received = 0
            rx.expectSeq = 0
            rx.lastPct = -1
            // P2-3：多通道状态必须一起归零。只清 expectSeq 的话，旧的单元窗/槽位仍站在原地：
            // 截断重来后第一批单元会被「seq < expectUnit」直接丢掉，槽里还留着上一轮的半截数据。
            // totalUnits 是构造时按 size 算的 val（同一条 mid 重来，size 不变），不需要重置。
            rx.expectUnit = 0
            rx.slotState.fill(0)
            rx.slotUnit.fill(-1)
            rx.slotLen.fill(0)
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
        rx.partialRegBytes = 0
        store?.putPartial(rx.mid, rx.uri, rx.name, rx.size, 0)
    }

    /** 二进制分片到达：按 seq 顺序追加进缓冲池，进度按需更新气泡。
     *  [direct] = true 表示载荷已由读循环直读进 [data] 的 [off] 处（P1-3 快路径，无分配无拷贝） */
internal fun Link.onChunk(
        s: Session, mid: String, seq: Int, data: ByteArray, len: Int,
        off: Int = 0, direct: Boolean = false
    ) {
        val rx = synchronized(rxMap) { rxMap[mid] }
        if (rx == null) {
            // 不能静默丢：多通道下这就是「单元缺口」的来源（发送端以为发了，接收端当没到）
            log("丢弃分片：mid=$mid 无接收上下文（seq=$seq len=$len）")
            return
        }
        if (rx.multi) {
            // 多通道：按单元窗口收，乱序是常态（不同 lane 各自推进），不能按 expectSeq 判
            if (seq < rx.expectUnit) return
        } else {
            if (rx.expectSeq != seq) {
                if (!direct) log("分片乱序（mid=$mid seq=$seq 期望 ${rx.expectSeq}），忽略")
                return
            }
            if (!direct) rx.expectSeq++
        }
        runCatching {
            if (rx.multi) {
                if (direct) commitUnitSlot(rx, seq, len) else copyIntoSlot(rx, seq, data, off, len)
            } else if (direct) commitDirectChunk(rx, off, len) else appendToRx(rx, data, len, off)
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
                    // 在途进度帧一律 SENDING(以前非续传帧标 OK,AppCore.updateMessage 会把每个 1%
                    // 都当终态补写进历史(磁盘灌爆)、传输中气泡显示成完成态、速率数字被提前回收)
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
        // 空文件夹一律忽略（双端统一规则）：不占任务槽、不建气泡、不建目录
        if (count == 0) {
            log("空文件夹已忽略 $name（对端未纳入传输，本端不建目录/不出气泡）")
            busyDirIds.remove(mid)
            return
        }
        // 空文件夹只会走 dir_meta（没有成员 file_meta）→ 这里也打一次点
        trustContent(s.remoteIp, isFile = true)
        runCatching {
            // 单任务槽：本机正在传别的任务 → 回 busy（目录 mid 记账，成员 file_meta 到达时同样回绝）
            if (!claimTask(mid, s.remoteIp, "recv", name)) {
                val holder = taskHolder()
                markDirBusy(mid)
                sendOrDrop(s, Protocol.busy(mid, name, holder, "dir"), "busy", quiet = true)
                log("设备忙（正在 $holder），拒绝接收文件夹 $name（mid=$mid），已回 busy")
                return@runCatching
            }
            busyDirIds.remove(mid)
            val existing = synchronized(dirMap) { dirMap[mid] }
            // 上一轮已收妥的成员（断链重连会重发同一个 dirMid）：在重置前先抄下来，等下回给对端
            val prevGot = existing?.let { d -> synchronized(d) { d.got.toList() } } ?: emptyList()
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
            // 清单大小（与 list 逐项对应）：先给清单再存文件，成员收完按它核对字节数
            val sizesArr = j.optJSONArray("sizes")
            val skipped = j.optInt("skipped", 0)
            synchronized(dir) {
                dir.count = count
                dir.total = total
                dir.skipped = skipped
                dir.lastActivity = System.currentTimeMillis()
                // 同一 mid 又来一轮 dir_meta = 对端重发整个文件夹(断链后从待发队列补发):
                // 每一轮都按新一轮重置计数/清单（未收尾的上一轮不清零会让计数重复累加出「假完成」）
                dir.doneFiles = 0
                dir.doneBytes = 0L
                dir.failed = false
                dir.lastPct = -1
                dir.got.clear()
                dir.bad.clear()
                dir.usedSanitizedRel.clear()
                // 有清单用新清单;没有(对端老端/清单超限被丢弃)必须清空旧清单
                // —— 否则同一 mid 重发时残留的上一轮清单会用来核对,误报一堆「缺失」
                dir.expect = if (hasList) HashSet(list) else HashSet()
                dir.expectSizes.clear()
                if (hasList && sizesArr != null && sizesArr.length() == list.size) {
                    for (i in list.indices) dir.expectSizes[list[i]] = sizesArr.optLong(i, -1L)
                }
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
            // 已经在盘上的成员先回给对端（逐个核对磁盘，不认内存记账），避免断链重发时整目录重传
            if (dir.expect.isNotEmpty()) {
                val have = synchronized(dir) { dirHaveOnDisk(dir, prevGot) }
                sendOrDrop(s, Protocol.dirHave(mid, have), "dir_have", quiet = true)
                if (have.isNotEmpty()) log("对端重发文件夹 $name：本机盘上已有 ${have.size} 个成员，请它只补缺的")
            }
            if (count == 0) {
                // 整个文件夹为空：已在入口忽略（双端统一规则），这里不会再走到
                log("空文件夹已忽略 $name")
            } else {
                log("开始接收文件夹 $name（$count 个文件，${TextUtil.humanSize(total)}）→ ${dir.desc}")
            }
        }.onFailure { log("创建接收目录失败: ${it.message}") }
    }

    /**
     * 文件夹收尾(对端 dl=1 时才有):由 dir_end 明确收尾(老流程只能靠数量猜,会静默缺文件)。
     * ① 有清单:与实收清单求差集,缺失项回发 dir_missing 让对端补发;② last=1(对端不再补发):无论是否缺件都定稿,缺件则明确标失败并逐项列出
     */
internal fun Link.onDirEnd(s: Session, j: JSONObject) {
        val mid = j.optString("mid")
        if (mid.isEmpty()) return
        val last = j.optBoolean("last", true)
        val dir = synchronized(dirMap) { dirMap[mid] } ?: return
        var missing: List<String> = emptyList()
        var lack = 0
        var hasList = false
        synchronized(dir) { hasList = dir.expect.isNotEmpty() }
        if (hasList) {
            // dir_end 紧跟在最后一批成员后面，可能有成员还在写盘：先等本目录在途接收收口，
            // 再按清单逐个核对磁盘 —— 否则会把「正在写」误判成缺件，白白重传一轮
            waitDirRxQuiet(dir.mid, 10_000L)
            synchronized(dir) { missing = dirMissingOnDisk(dir) }
        } else {
            synchronized(dir) {
                // 对端没带清单（老端 / 清单超限被丢弃）：只能算出「差几个」，无法精确到项
                lack = (dir.count - dir.doneFiles).coerceAtLeast(0)
            }
        }
        val stamp = System.currentTimeMillis()
        if (!hasList) {
            // 无清单→无法用回执表达「缺哪些」(空回执只能表示收齐,会误导对端);
            // 本端仍如实定稿(缺件标失败),发送端那边等一拍超时后自行定稿
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

    /** 接收端回报「已经在盘上的成员」：发送端据此跳过这些成员，只补缺的文件 */
internal fun Link.onDirHave(j: JSONObject) {
        val mid = j.optString("mid")
        if (mid.isEmpty()) return
        val items = j.optJSONArray("items")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        } ?: emptyList()
        dirHaveBox[mid]?.offer(items)
    }

    /**
     * 文件夹定稿：更新气泡并如实汇报。**缺件一律标失败并列入日志** ——
     * 绝不把「差了几个文件」当成「发送成功」。
     */
internal fun Link.finishDirReceiving(dir: DirRx, missing: List<String>, lack: Int) {
        val hasList = dir.expect.isNotEmpty()
        // 有清单时按「盘上真实有多少」报数（逐个核对磁盘后的结果），不按本轮收到多少 ——
        // 断链补发只传缺的那几个，按本轮计数会把 147 个说成「实收 2/147」
        val got = if (hasList) (dir.expect.size - missing.size).coerceAtLeast(0) else dir.doneFiles
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
                "文件夹接收不完整 ${dir.name}：盘上 $got/$total，缺 ${missing.size} 项 → " +
                    missing.take(20).joinToString(", ") + (if (missing.size > 20) " …（略）" else "")
            )
            lack > 0 -> log(
                "文件夹接收不完整 ${dir.name}：盘上 $got/$total，缺 $lack 项" +
                    "（对端未带成员清单，无法精确定位到具体文件）"
            )
            dir.failed -> log("文件夹接收完成（$bad）${dir.name}：盘上 $got/$total")
            else -> log("文件夹接收完成 ${dir.name}：盘上 $got/$total，全部校验通过")
        }
        // 目录定稿（收齐/明确失败/不再补发）= 整个目录任务结束，释放单任务槽
        releaseTask(dir.mid)
        pruneDirs()
    }

    /**
     * 回收目录记录:① 未收尾且闲置 10 分钟(且无活动成员)→ 回收,防没走到 dir_end 的 DirRx 永留;
     * ② 成品目录超出 [MAX_KEEP_DIRS] → 回收最旧的(刻意保留最近若干条:同一 mid 重发时复用,不另建「文件夹 (1)」)。
     * 由心跳线程每 [Link.STALL_SCAN_MS] 顺带调用 + 每次目录定稿时调用
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
                // 未收尾就被回收的目录也占着任务槽：一并释放，否则本机永远「设备忙」
                releaseTask(mid, notifyPeer = false)
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
