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
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock


import com.phonepctransfer.net.Link.Session

// 发送管线(拆分自 Link.kt):文件夹/文件发送、断点续传协商、md5 核对、BlockReader;全部为 Link 扩展函数

/**
 * 一个分片帧里**最多能装多少载荷字节**。
 *
 * [Protocol.MAX_FRAME] 是整帧（4B 帧长字段的值 = 子类型 1B + midLen 1B + mid + seq 4B + 载荷）的上限，
 * 不是载荷上限：帧头固定占 `6 + midLen` 字节（见 [Protocol.chunkHeader] 的 `(buf.size - 4) + len`）。
 * 自适应顶档 8M 若原样当载荷发，8*1024*1024 + 6 + midLen 必然越界 → 抛「分片帧过大」→
 * 发送线程 catch 后关会话 → 重连再爬档再炸（大文件永远传不完）。所以在发送侧按 mid 的真实
 * UTF-8 长度让位，一帧正好卡在 MAX_FRAME 上，既不改协议常量也不降档。
 */
internal fun framePayloadCap(mid: String): Int =
    Protocol.MAX_FRAME - 6 - mid.toByteArray(Charsets.UTF_8).size

/** 单文件等对端 ack 的上限：超时只撤登记，不判失败、不出队（见 [scanAckTimeout]） */
private const val ACK_WAIT_TIMEOUT_MS = 60_000L

/**
 * 文件/文件夹发送的唯一入队口:把整段发送流程(数据流量确认+传输)丢进单线程队列 [Link.fileSendExec],
 * 保证「一次只有一个文件在传送,前一个完成立刻进入下一个」。
 * 只给顶层发送入口用(用户发送/重试/待发补发/hsync 补图);文件夹成员在队列线程内同步顺序调用,不能再各自入队;语音/视频/消息不走这里
 */
internal fun Link.enqueueFileSend(tag: String, task: () -> Unit) {
    val depth = fileSendQueueDepth()
    if (depth > 0) log("$tag 已排队（前面还有 $depth 个在等）")
    try {
        fileSendExec.execute {
            // 传输期持 CPU/WiFi 锁：息屏后 WiFi 进省电档会把发送窗口挂起，长传必断
            BusyLock.begin("send")
            try {
                task()
            } catch (t: Throwable) {
                log("$tag 队列任务异常(${t.javaClass.simpleName}: ${t.message})")
            } finally {
                BusyLock.end("send")
                // 队列空了(最后一个任务跑完)就补跑一次超龄半成品回收(「取消帧丢失+会话一直不断」的孤儿条目不必等到下次启动)
                if (fileSendQueueDepth() == 0) store?.purgeStalePartials(Link.PARTIAL_MAX_AGE_MS)
            }
        }
    } catch (t: Throwable) {
        log("$tag 入队失败(${t.javaClass.simpleName}: ${t.message})")
    }
}

internal fun Link.wireListFits(relPaths: List<String>): Boolean {
        var n = 0
        for (p in relPaths) {
            n += p.length + 4
            if (n > DIR_LIST_MAX_CHARS) return false
        }
        return true
    }

internal fun Link.doSendFolder(
        treeUri: Uri, item: MsgItem, files: List<FileIO.TreeFile>, emptyDirs: List<String>,
        skipped: Int = 0
    ) {
        // 与 doSendFile 同理：排在队列里的补发任务要认取消墓碑，取消后不许再入账、再开传
        if (isCanceled(item.mid)) {
            log("文件夹 ${item.fileName} 已被取消，跳过后到的发送任务")
            store?.removePending(item.mid)
            return
        }
        // 开传前先入待发队列(防强杀):整目录发送成功后才移除,重启重连后自动补发。
        // 补发时走「按清单逐个核对」:对端先回报已在盘上的成员(dir_have),本端只补缺的文件 ——
        // 不再有半成品偏移/完成记号表,重启丢状态也不影响正确性(最多把没到的重发一遍)
        // 已完整送达过(同一个 mid)就直接跳过:断链会同时触发「握手补发」和「原先在队列里的补发任务」,
        // 前一趟跑成功时 inFlight 已摘下,后一趟会把整个目录再全量传一遍(实测每次断链多一整轮)
        if (isFileDelivered(item.mid, item.peerIp)) {
            log("文件夹 ${item.fileName} 本机已完整送达该设备，跳过重复发送")
            store?.removePending(item.mid)
            okUpdate(item.copy(progress = 100, status = Status.OK))
            return
        }
        queuePending(item)
        // 单任务槽：本机正在收别的任务就排队等槽位（串行队列线程里等，安全）；等不到=被取消/已停止
        if (!awaitTaskSlot(item.mid, item.peerIp, "send", item.fileName) { isCanceled(item.mid) }) return
        // 对端在传别的任务（busy 早于本任务入队）：直接留待发队列，等对端释放槽位的 sync_req
        if (consumePeerBusy(item.mid)) {
            okUpdate(item.copy(status = Status.PENDING))
            releaseTask(item.mid)
            log("对端设备忙，文件夹 ${item.fileName} 已排队，等对端空闲后自动补发")
            return
        }
        if (!inFlight.add(item.mid)) {
            return
        }
        try {
            inFlightPeer[item.mid] = item.peerIp
            doSendFolderInner(treeUri, item, files, emptyDirs, skipped)
        } finally {
            inFlight.remove(item.mid)
            inFlightPeer.remove(item.mid)
            dirMissingBox.remove(item.mid)
            // 文件夹任务结束（成功/失败/取消/忙）/释放单任务槽，并让对端把被拦下的待发条目重试一次
            releaseTask(item.mid)
        }
    }

    /**
     * 发送一个文件夹(带完整性闭环):`dir_meta`(带成员清单)→逐成员发送→`dir_end` 收尾→等对端 `dir_missing` 回执;
     * 有缺失就只补发缺失项(最多 [DIR_RESEND_ROUNDS] 轮),仍有缺件则如实标失败并把缺的文件名列进日志。
     * 本地不可读只跳过该成员继续,最后统一汇报(老流程一个成员读不了就让整目录停摆,且完全不等对端确认)
     */
internal fun Link.doSendFolderInner(
        treeUri: Uri, item: MsgItem, files: List<FileIO.TreeFile>, emptyDirs: List<String>,
        skipped: Int
    ) {
        val s = sessionForPending(item.peerIp)
        if (s == null) {
            setFileVote(Link.VOTE_NO_NETWORK, true, item.peerIp)   // 2.12 / D16：文件夹同一条口径（"原本没网络"票）
            queuePending(item)
            return
        }
        if (cancelled.contains(item.mid)) {
            cancelled.remove(item.mid)
            return
        }
        // 对端在传别的任务：dir_meta 刚发出去就会被回 busy；先看一眼记号，别白推一轮
        if (consumePeerBusy(item.mid)) {
            okUpdate(item.copy(status = Status.PENDING))
            log("对端设备忙，文件夹 ${item.fileName} 已排队（等对端空闲后自动整目录补发）")
            return
        }
        // 只有对端也支持（hello dl=1）才启用清单/收尾/核对；老端保持原行为，不引入未知帧
        val verifyPeer = s.caps.dl
        val relPaths = files.map { it.relPath }
        val wireList =
            if (verifyPeer && relPaths.isNotEmpty() && wireListFits(relPaths)) relPaths else emptyList()
        // 清单带大小：与 list 逐项对应，接收端按清单存放并核对每个成员的字节数
        val wireSizes = if (wireList.isNotEmpty()) files.map { it.size } else emptyList()
        if (verifyPeer && wireList.isEmpty() && relPaths.isNotEmpty()) {
            log("成员清单过长（${relPaths.size} 项），本次改用「按数量核对」完整性")
        }
        // 空文件夹一律忽略、不记清单：dir_meta 里的 dirs 恒为空（双端统一规则）
        val emptyDirsWire = emptyList<String>()
        // ⚠ 信箱**必须先登记再发送**：对端收到 dir_meta 后会立刻回 dir_have，
        //   先 send 后登记会与这条回执赛跑 —— 回执投进还不存在的信箱被丢掉，本端白等 1s、整目录重传
        val haveBox: java.util.concurrent.ArrayBlockingQueue<List<String>>? =
            if (verifyPeer) java.util.concurrent.ArrayBlockingQueue<List<String>>(1) else null
        if (haveBox != null) dirHaveBox[item.mid] = haveBox
        try {
            s.send(
                Protocol.dirMeta(
                    item.mid, item.fileName, files.size, item.fileSize, emptyDirsWire, wireList, skipped, wireSizes
                )
            )
        } catch (e: Throwable) {
            // 发送失败：信箱立刻撤掉，别留一个永不投递的空信箱
            if (haveBox != null) dirHaveBox.remove(item.mid)
            throw e
        }
        try {
            val agg = DirProgress(this, item, item.fileSize)
            // 对端「已经在盘上」的成员：断链重连后对端会在 dir_meta 之后立刻回报，跳过它们只补缺的
            val already: Set<String> = if (haveBox != null) {
                try {
                    haveBox.poll(DIR_HAVE_WAIT_MS, TimeUnit.MILLISECONDS)?.toSet() ?: emptySet()
                } catch (e: InterruptedException) {
                    emptySet()
                } finally {
                    dirHaveBox.remove(item.mid)
                }
            } else emptySet()
            if (already.isNotEmpty()) {
                log("对端已有 ${already.size} 个成员，本次跳过，只补缺的（${item.fileName}）")
            }
            // 本地不可读而跳过的成员（本地问题，补发也无用 —— 最后如实汇报）
            val unreadable = ArrayList<String>()
            var pendingSet = files
            var round = 0
            var peerMissing: List<String> = emptyList()
            var peerAllOk = true
            var unconfirmed = false
            while (true) {
                for (f in pendingSet) {
                    if (cancelled.contains(item.mid)) {
                        cancelled.remove(item.mid)
                        clearMemberAcks(item.mid)
                        log("文件夹发送已取消 ${item.fileName}（已发 ${files.size - pendingSet.size}/${files.size}）")
                        return
                    }
                    // 2.12 / D16：投票 = 1 ⇒ 在成员边界结束整目录这次尝试
                    if (fileGateBlocked(item.peerIp)) {
                        pauseInFlight(item, "", null)
                        return
                    }
                    // 对端被别的任务占着（dir_meta 回的 busy 在本轮途中才到）：整轮中止，条目留待发队列
                    if (consumePeerBusy(item.mid)) {
                        okUpdate(item.copy(status = Status.PENDING))
                        log("对端设备忙，文件夹 ${item.fileName} 中止本轮（等对端空闲后自动整目录补发）")
                        return
                    }
                    if (f.relPath in already) {
                        // 对端已在盘上：不传，进度按已收补上
                        agg.add(f.size.coerceAtLeast(0))
                        continue
                    }
                    val member = MsgItem(
                        mid = newMid(), dir = Dir.SELF, kind = Kind.FILE,
                        fileName = f.relPath.substringAfterLast('/'), fileSize = f.size,
                        filePath = f.uri.toString(), localUri = f.uri.toString(), progress = 0,
                        peerIp = item.peerIp
                    )
                    try {
                        doSendFile(f.uri, member, f.relPath, item.mid, agg, throwOnFail = true)
                    } catch (e: SourceError) {
                        // 本地读不了(文件已删/无权限/SAF 授权失效):只跳过这一个成员继续发其余(老逻辑会让整目录就此停摆)
                        unreadable.add(f.relPath)
                        log("成员本地不可读，跳过继续：${f.relPath}（${e.message}）")
                    } catch (b: BusyError) {
                        // 对端设备忙（成员 file_meta 被回 busy）：整轮中止，等对端释放槽位的 sync_req 再整目录补发
                        okUpdate(item.copy(status = Status.PENDING))
                        log("对端设备忙，文件夹 ${item.fileName} 中止本轮（等对端空闲后自动整目录补发）")
                        return
                    }
                }
                if (!verifyPeer) break
                // 收尾：明确告诉对端「本轮发完了」。最后一轮 last=1 —— 对端无论缺不缺都定稿
                val last = round >= DIR_RESEND_ROUNDS
                val box = java.util.concurrent.ArrayBlockingQueue<List<String>>(1)
                dirMissingBox[item.mid] = box
                s.send(Protocol.dirEnd(item.mid, pendingSet.size, last))
                val resp = try {
                    box.poll(DIR_MISSING_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (resp == null) {
                    // 无回执 ≠ 收齐：绝不默认成功。条目留在待发台账，下次握手可再核对/补发
                    log("对端未在 ${DIR_MISSING_WAIT_MS / 1000}s 内回报文件夹核对结果，无法确认收齐，按失败处理（条目留在待发队列）")
                    peerAllOk = false
                    unconfirmed = true
                    break
                }
                if (resp.isEmpty()) break          // 空清单 = 对端确认收齐
                peerAllOk = false
                peerMissing = resp
                val next = files.filter { it.relPath in resp }
                if (last) break
                if (next.isEmpty()) break
                round++
                log("对端报告 ${resp.size} 个成员未到，第 $round 轮补发：${resp.take(10).joinToString(", ")}")
                pendingSet = next
            }
            if (cancelled.contains(item.mid)) {
                cancelled.remove(item.mid)
                log("文件夹发送已取消 ${item.fileName}")
                return
            }
            // 定稿：**任何一个成员没送达都要如实标失败并列出**，绝不无条件报「发送完成」
            val bad = (unreadable + peerMissing).distinct()
            // 整目录收尾：速率采样器一起丢掉（气泡按「样本过期」自动不显示速率）
            endSpeed(item.mid)
            if (bad.isEmpty() && peerAllOk) {
                okUpdate(item.copy(progress = 100, status = Status.OK))
                store?.removePending(item.mid)
                // 落送达台账:断链后残留的补发任务凭这条记录直接出队,不再整目录重传(见 doSendFolder 入口)
                markFileDelivered(item.mid, item.peerIp)
                log("文件夹发送完成 ${item.fileName}（${files.size} 个文件，${TextUtil.humanSize(item.fileSize)}）")
            } else {
                okUpdate(item.copy(status = Status.FAILED))
                // 对端未回报（无法确认）时保留待发台账，让下次握手有机会再核对；明确失败才出队
                if (!unconfirmed) store?.removePending(item.mid)
                val head = bad.take(20).joinToString(", ")
                val tail = if (bad.size > 20) " …（略）" else ""
                log(
                    "文件夹发送不完整 ${item.fileName}：应有 ${files.size} 个，未送达 ${bad.size} 个 → " +
                        "$head$tail" +
                        (if (unreadable.isNotEmpty()) "（其中 ${unreadable.size} 个本地不可读，请检查文件是否已被删除/授权是否失效）" else "")
                )
            }
            // 整目录收尾
            dirHaveBox.remove(item.mid)
            clearMemberAcks(item.mid)
        } catch (t: Throwable) {
            if (cancelled.contains(item.mid)) {
                cancelled.remove(item.mid)
                log("文件夹发送已取消 ${item.fileName}")
                return
            }
            // 失败收尾：重发走待发队列整目录补发，缺哪些由对端清单对账决定
            dirHaveBox.remove(item.mid)
            clearMemberAcks(item.mid)
            log("文件夹发送失败(${t.javaClass.simpleName}: ${t.message})")
            okUpdate(item.copy(status = Status.FAILED))
        } finally {
            // 整目录速率采样器统一收尾:覆盖 while 内取消 / 循环后取消 / catch 失败;
            // 定稿处那次 endSpeed 保留,重入幂等
            endSpeed(item.mid)
        }
    }

    /** 文件夹发送进度聚合：字节累加、按 % 变化刷新气泡 */
internal class DirProgress(private val link: Link, private val item: MsgItem, private val total: Long) {
        internal var done = 0L
        internal var lastPct = -1

        @Synchronized
        fun add(n: Long) {
            if (total <= 0) return
            done += n
            // 文件夹气泡的速率用**聚合字节数**采样（成员文件各自算会互相打断基线）
            link.markSpeed(item.mid, done)
            val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastPct) {
                lastPct = pct
                link.okUpdate(item.copy(progress = pct, status = Status.SENDING))
            }
        }
    }

    /**
     * 断点续传协商:问对端有没有这个文件的半成品(与 PC 端口径一致)。
     * >0 = 对端已有这么多字节(按分片边界对齐后从这里续);0 = 没有半成品(从头发);
     * -1 = 没发出问题或 3s 没收到应答 ⇒ 调用方保持暂停,不许退化成从头重发
     */
internal fun Link.negotiateResume(
        s: Session, mid: String, name: String, size: Long, uri: Uri? = null
    ): Long {
        val q = java.util.concurrent.ArrayBlockingQueue<Long>(1)
        resumeWaiters[mid] = q
        try {
            if (!sendOrDrop(s, Protocol.fileQuery(mid, name, size), "file_query", quiet = true)) {
                return -1L
            }
            val off = q.poll(3000, TimeUnit.MILLISECONDS)
            if (off == null) return -1L
            if (off <= 0L) return 0L
            // 自适应帧之后落盘是「按帧顺序追加」，偏移可以是任意值，不必再向下对齐到 4MB
            var o = if (off > size) size else off
            // 大小校验：接收端说半成品需要按块核对 → 请它按 32MB 块核对前缀大小，
            // 回来的核验偏移就是重发起点（比「从 0 重传」省，比「信任裸偏移」稳；不做 hash）
            if (resumeNeedBlk[mid] == true && o > 0L) {
                o = verifyPrefixBlocks(s, mid, size, o)
            }
            return o
        } catch (t: Throwable) {
            return -1L
        } finally {
            resumeWaiters.remove(mid)
            resumeNeedBlk.remove(mid)
        }
    }

    /**
     * 发送端前缀大小校验（不做 hash）：请接收端按 32MB 块核对 [0, offset) 是否都在盘上，
     * 接收端截断到最后一个完整块边界、建好续传通道后回 blk_ack（带实际核验偏移），本端从该点续发。
     * 单块超时 10s（总预算 = 块数 × 10s）；超时/失败退回从头重发
     * （补发 blk_verify{0} 让接收端清掉半成品，避免与续传通道错位）。
     */
internal fun Link.verifyPrefixBlocks(
        s: Session, mid: String, size: Long, offset: Long
    ): Long {
        val aq = java.util.concurrent.ArrayBlockingQueue<Link.BlkAckResult>(1)
        blkAckWaiters[mid] = aq
        try {
            if (!sendOrDrop(s, Protocol.blkVerify(mid, offset), "blk_verify", quiet = true)) {
                return 0L
            }
            val blocks = ((offset + Protocol.BLOCK_SIZE - 1) / Protocol.BLOCK_SIZE).coerceAtLeast(1L)
            val budgetMs = blocks * 10_000L   // 单块超时 10s
            val r = aq.poll(budgetMs, TimeUnit.MILLISECONDS)
            if (r == null) {
                log("大小校验：${budgetMs / 1000}s 没等到对端核验结果（mid=$mid），改从头重发")
                runCatching { sendOrDrop(s, Protocol.blkVerify(mid, 0L), "blk_verify", quiet = true) }
                return 0L
            }
            if (!r.ok) {
                log("大小校验：对端无法截断半成品，改从头重发")
                return 0L
            }
            val target = r.offset.coerceIn(0L, offset)
            if (target != offset) {
                log("大小校验：对端盘上字节不足，退到最后一个完整块边界 ${TextUtil.humanSize(target)} 续传")
            }
            log("大小校验完成（$blocks 块 × 32MB），从 ${TextUtil.humanSize(target)} 续传 ${TextUtil.humanSize(size)}")
            return target
        } catch (t: Throwable) {
            // 异常同样不能沿用偏移：接收端没建好续传通道，按 offset 推流会错位
            log("大小校验异常（${t.message}），改从头重发")
            runCatching { sendOrDrop(s, Protocol.blkVerify(mid, 0L), "blk_verify", quiet = true) }
            return 0L
        } finally {
            blkAckWaiters.remove(mid)
        }
    }

internal fun Link.pauseInFlight(
        item: MsgItem,
        dirId: String = "",
        agg: DirProgress? = null
    ) {
        if (agg == null && dirId.isEmpty()) {
            queuePending(item)                       // store.addPending 按 mid 幂等
            okUpdate(item.copy(status = Status.PAUSED))
        }
        log(
            "[文件排他] 已暂停：${item.fileName}（投票=1，D16）" +
                "⇒ 解除拦截后自动续发一次，没应答就停在这儿等手动「继续」"
        )
    }

    /** 对端设备忙（单任务槽被占）：单文件回待发队列；文件夹成员抛 [BusyError] 让目录整轮中止 */
internal fun Link.pauseForPeerBusy(item: MsgItem, dirId: String, agg: DirProgress?) {
        if (dirId.isEmpty() && agg == null) {
            queuePending(item)
            okUpdate(item.copy(status = Status.PENDING))
            log("对端设备忙：文件 ${item.fileName} 停止推送，留在待发队列等对端空闲")
        } else {
            throw BusyError("对端设备忙，本轮文件夹发送中止")
        }
    }

    // ==================== 续传完成大小核对（端到端，不做 hash） ====================

    /** 一次续传核对的会话状态：本端大小即清单声明值（已知），等对端回发落盘字节数 */
internal class VerifyState(val mid: String, val item: MsgItem) {
        @Volatile var peerSize: Long = -1L
        @Volatile var finished = false
    }

    /**
     * 发送端:续传文件发完后启动核对——等对端回发落盘文件字节数,与清单声明大小比对;
     * 整文件按一个验证单元计,10s 超时(对端没回发)只记日志跳过,绝不阻塞、不改传输状态
     */
internal fun Link.startVerifyWatch(item: MsgItem, resumedFrom: Long) {
        val st = VerifyState(item.mid, item)
        if (verifyMap.putIfAbsent(item.mid, st) != null) return
        Thread({
            try {
                Thread.sleep(verifyTimeoutMs)
            } catch (_: InterruptedException) {
            }
            if (!st.finished) {
                st.finished = true
                verifyMap.remove(item.mid)
                log("大小核对超时 ${item.fileName}（对端未回发落盘字节数，自 ${resumedFrom} B 续传），跳过")
            }
        }, "pp-verify-timeout").apply { isDaemon = true }.start()
        log("续传文件发送完成，等待对端大小核对 ${item.fileName}（自 ${TextUtil.humanSize(resumedFrom)} 起）")
    }

    /** 接收端回发的落盘字节数到了 → 与清单声明大小比对 */
internal fun Link.onFileVerify(j: JSONObject) {
        val mid = j.optString("mid")
        val st = verifyMap[mid]
        if (st == null) {
            log("收到大小核对帧但无在途核对（mid=$mid 已超时/非续传/旧版本对端），忽略")
            return
        }
        st.peerSize = j.optLong("size", -1L)
        compareVerify(st)
    }

    /** 核对一次性收尾（finished 原子防重入）；大小不符 → 气泡标失败提醒用户重传 */
internal fun Link.compareVerify(st: VerifyState) {
        synchronized(st) {
            val peer = st.peerSize
            if (st.finished || peer < 0L) return
            st.finished = true
            verifyMap.remove(st.mid)
            val mine = st.item.fileSize
            if (mine <= 0L) {
                log("大小核对 ${st.item.fileName}：对端落盘 $peer B（清单大小未知，跳过比对）")
            } else if (peer == mine) {
                log("大小核对一致 ${st.item.fileName}（$peer B）")
            } else {
                log("大小核对不一致 ${st.item.fileName}：清单 $mine B / 对端落盘 $peer B")
                // 气泡标失败提醒用户重传；接收端文件已在，是否删除由用户决定
                okUpdate(st.item.copy(status = Status.FAILED))
            }
        }
    }

    /** 续传时把源流推进到 offset（只跳过、不算摘要）；读失败返回 false,调用方改从头传 */
internal fun Link.skipPrefix(ins: InputStream, len: Long): Boolean {
        return try {
            val buf = ByteArray(1 shl 16)
            var left = len
            while (left > 0) {
                val n = ins.skip(left)
                if (n > 0) {
                    left -= n
                } else {
                    // 部分提供器不支持 skip：退化为读弃
                    val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r <= 0) return false
                    left -= r
                }
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 发送一个文件(1MB 二进制分片 + 双 4MB 缓冲读源 + 断点续传)。
     * relPath = 文件夹成员时为目录内相对路径;dirId = 所属目录气泡 mid(空 = 普通单文件);
     * agg = 文件夹进度聚合器(普通单文件为 null);throwOnFail = true 成员失败向上抛(整目录标失败),false 记待发队列自动重试
     */
    /** 正在发送中的 mid（含文件夹整体）：防止握手补发与在途传输重复发同一条 */
    internal val inFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 在途发送的 mid → 对端地址：发送停滞看门狗用（30s 无新字节 → 关该会话解阻塞） */
    internal val inFlightPeer = ConcurrentHashMap<String, String>()

    /** 在途发送的 mid → 最近一次写出新字节的时刻（发送停滞看门狗用） */
    internal val sendLastAt = ConcurrentHashMap<String, Long>()

    /** 一次「字节已写出、等对端回报落盘」的登记：气泡快照 + 当时用的对端地址 + 登记时刻 */
    internal class AckWait(
        internal val item: MsgItem,
        internal val peerIp: String,
        /** 登记时刻：60s 没回执由 [scanAckTimeout] 撤登记（只清表，不动气泡/待发队列） */
        internal val at: Long = System.currentTimeMillis()
    )

    /**
     * 已把全部分片和 `file_done` 写出、但**对端还没回报落盘结果**的普通单文件:mid → 登记。
     * 为什么写完 file_done 不能就算送达:那一刻对端完全可能还在落盘、校验没过、或回 ack 前链路就断了,
     * 老写法当场出队并记了台账 → 对端永远少这一份、本机气泡却报成功(漏传/假成功)。现在出队 + 记台账统一由对端的 `ack` 帧驱动(见 [onSendAck]);
     * 在等的这段时间条目一直留在待发队列里,任何意外下次握手都会自动补发(补发时先凭同一个 mid 问一遍对端「这条到没到」)
     */
    internal val awaitingAck = ConcurrentHashMap<String, AckWait>()

    /*
     * 对端**提前回绝**(`ack(ok=false)` 在字节还没推完时就到)的 mid 记号:
     * 接收端在建接收通道失败时(存盘目录不可写/磁盘建不了文件)会在 `file_meta` 这一步立刻回 `ack false`,
     * 而 `awaitingAck` 是写完 `file_done` 才挂上的——那一刻我方还在闷头推分片,回执没人认领,等推完再挂就永远等不到第二份 ack;
     * 有了这个记号,收尾时就能把这条按失败处理(气泡标失败、条目留在待发队列下次重试)
     */
    internal val rejectedAck: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /*
     * 送达台账(**已完整送达**的普通单文件):键 = `mid@对端组键`,判据见 [Store.isDelivered]。
     * 为什么要这张表:一次握手同时触发两条投递路径——① 待发队列补发([flushPendingNow]);② 对端 `hsync_pull` 点名重发([resendHistoryFile],按**原 mid**)。
     * 两条都只判 [inFlight],而发送队列是**串行**的:各自入队时对方还没跑,[inFlight] 为空
     * → 同一条文件被完整发两遍。[inFlight] 收尾就摘 mid,兜不住「先后各跑一次」,所以「送达过没有」要单独记(落盘端 PC 已有同名闸门)。
     * 键带对端**设备组键**:同一条消息发给两台设备不算重复;台账由 Store 持久化(`delivered.json`),只放内存的话重启即归零。
     */

    /** 送达台账的键：mid + 对端设备组键（同一台设备换了 WiFi/热点地址仍算同一台） */
    internal fun Link.deliveryKey(mid: String, peerIp: String): String {
        if (mid.isEmpty()) return ""
        val k = runCatching { prefs.convKeyOf(peerIp) }.getOrNull().orEmpty()
        return mid + "@" + if (k.isEmpty()) peerIp else k
    }

    /** 单文件送达登记 —— 只在「对端回报这条已存妥」的两条路径调用（[onSendAck] / [doSendFileInner] 的协商短路） */
    internal fun Link.markFileDelivered(mid: String, peerIp: String) {
        val k = deliveryKey(mid, peerIp)
        if (k.isEmpty()) return
        awaitingAck.remove(mid)
        store?.markDelivered(k)
    }

    /** 是否已把这条文件**完整送达**过这台设备（跨进程重启仍成立） */
    internal fun Link.isFileDelivered(mid: String, peerIp: String): Boolean =
        store?.isDelivered(deliveryKey(mid, peerIp)) == true

    /**
     * 对端对这条文件回了落盘回报(`ack`)——送达与否以它为准。
     * ok = 对端是否已把这条完整落盘;true = 这是我方正在等的那条文件回执,调用方无需再按通用 ack 处理
     */
internal fun Link.onSendAck(mid: String, ok: Boolean): Boolean {
        if (mid.isEmpty()) return false
        val w = awaitingAck.remove(mid)
        if (w == null) {
            // 还在推字节 = 对端提前回绝（建接收通道就失败了）；记一笔，收尾时按失败处理。
            // 不在这一步改气泡 —— 字节还没推完，界面就该显示「发送中」。
            if (!ok && inFlight.contains(mid)) {
                rejectedAck.add(mid)
                log("对端提前回绝接收 $mid（多半是它那边存不了），本端收尾按失败处理")
                return true
            }
            return false
        }
        if (!ok) {
            // 对端明说没存好：**条目继续留在待发队列**（下次握手自动重发/续传），气泡改回失败
            okUpdate(w.item.copy(progress = 100, status = Status.FAILED))
            log("对端回报文件未收妥 ${w.item.fileName}（mid=$mid），留在待发队列等补发")
            return true
        }
        store?.removePending(mid)
        markFileDelivered(mid, w.peerIp)
        // 成功气泡由这里落：收尾时若回执已经抢跑就不再写 OK（防把失败态盖回去），
        // 所以这条路径必须补上，否则气泡会永远停在「发送中」
        okUpdate(w.item.copy(progress = 100, status = Status.OK))
        log("对端已确认落盘 ${w.item.fileName}，出待发队列")
        return true
    }

    /**
     * 普通单文件收尾：**先登记回执 → 再发 `file_done` → 按「是否还在等」写气泡**。
     *
     * 顺序颠倒就会漏回执：局域网 RTT(0.2~1ms) 远小于一次「写历史」的磁盘耗时，
     * 对端 ok 回执常早于登记到达 → [onSendAck] 查不到条目（ok=true 直接 return false）→
     * `removePending`/`markFileDelivered` 全都不执行：文件永久留守待发队列，每次重连多一轮 file_query。
     * 另外 ack(false) 抢跑时 [onSendAck] 已把气泡改成失败并摘掉登记，这里再无条件写 OK 会把它盖回去。
     */
    internal fun Link.finishSingleSend(s: Session, item: MsgItem, sha: String, offset: Long) {
        if (rejectedAck.remove(item.mid)) {
            // 对端在推字节期间就回绝了（建接收通道失败：目录不可写/磁盘满）：不再发 file_done、也不登记
            endSpeed(item.mid)
            okUpdate(item.copy(progress = 100, status = Status.FAILED))
            log("文件 ${item.fileName} 对端提前拒收，留在待发队列等补发")
            return
        }
        awaitingAck[item.mid] = AckWait(item, s.remoteIp)   // ① 先登记，防 ack 抢跑
        s.send(Protocol.fileDone(item.mid, sha))            // ② 再发
        endSpeed(item.mid)
        // ③ 还没收到回执 → 停在「已推完、待确认」，绝不提前写成功（P2-2）：
        //    之前这里直接写 OK，ack(false) 到达才变红，用户先看到一次假的"已发送"
        if (awaitingAck.containsKey(item.mid)) {
            okUpdate(item.copy(progress = 100, status = Status.SENDING))
        }
        log("文件发送完成 ${item.fileName} ${TextUtil.humanSize(item.fileSize)}，等对端回报落盘")
        // 触发过续传 → 等对端回发 MD5 做端到端核对（异步，不阻塞发送线程）
        if (offset > 0) {
            startVerifyWatch(item, offset)
        }
    }

    /**
     * 回执看门狗（心跳每 5s 调度）：登记超过 [ACK_WAIT_TIMEOUT_MS] 还没等到 ack 就撤掉登记。
     * **只清表**：不改气泡（对端若是不回 ack 的老构建，保持原来的「已发送」表现），
     * 也不出待发队列 —— 下次握手 `file_query` 一问（offset==size）就会补 ack 自愈。
     */
    internal fun Link.scanAckTimeout() {
        if (awaitingAck.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((mid, w) in awaitingAck) {
            if (now - w.at < ACK_WAIT_TIMEOUT_MS) continue
            if (awaitingAck.remove(mid, w)) {
                // P2-2：超时**不再改气泡**（对端可能确实没存好，写成功就是假成功）；
                // 气泡保持「发送中」，条目留待发台账，下次握手 file_query 一问（offset==size）补 ack 自愈
                log("等对端落盘回执超时 ${w.item.fileName}（mid=$mid），气泡保持发送中，条目留待发台账等下次握手核对")
            }
        }
    }

    /**
     * 发送入口(防强杀版):开传前先把条目落进持久化待发队列,成功后才移除;
     * 任何时刻被强杀(包括传输中途),重启后连接上对端都会由握手自动补发,并凭同一个 mid 命中对端半成品注册表从断点续传
     */
internal fun Link.doSendFile(
        uri: Uri,
        item: MsgItem,
        relPath: String = "",
        dirId: String = "",
        agg: DirProgress? = null,
        throwOnFail: Boolean = false
    ) {
        // 队列里排着的任务（多为 syncNow→flushPendingNow 产生的「待发补发」）可能在用户取消之后才轮到：
        // 取消墓碑命中就直接跳过，**不再入待发台账、不再开传**。曾经这里漏判 ⇒ 取消完过几秒
        // 同一个 mid 又被重发一遍：对端冒出气泡继续收，本端气泡已删除、看不到进度。
        if (isCanceled(item.mid, dirId)) {
            log("文件 ${item.fileName} 已被取消，跳过后到的发送任务")
            store?.removePending(item.mid)
            return
        }
        // 只有「普通单文件」由这里管待发队列；文件夹整体由 doSendFolder 管，成员不管
        if (dirId.isEmpty() && agg == null) {
            // 重复投递闸门(判据见 [isFileDelivered]):这条已经完整送达过,
            // 就连队都不排、流量确认都不弹。必须在这里判而不是只判 [inFlight]——待发补发与 hsync 补齐都往同一条串行队列塞任务,
            // 前一个跑完 [inFlight] 已清空,后一个照样放行 = 同一份文件发两遍。文件夹成员不受这里管(每次重发用新 mid,去重由 dirSendState/对端 DirRx 负责)
            if (isFileDelivered(item.mid, item.peerIp)) {
                log("文件 ${item.fileName} 本机已完整送达该设备，跳过重复发送")
                store?.removePending(item.mid)
                okUpdate(item.copy(progress = 100, status = Status.OK))
                return
            }
            queuePending(item)
        }
        // 单文件任务自己占槽（文件夹成员不算，槽归整目录任务）
        val ownSlot = dirId.isEmpty() && agg == null
        if (ownSlot) {
            // 本机正在收别的任务就排队等槽位（串行队列线程里等，安全）；等不到=被取消/已停止
            if (!awaitTaskSlot(item.mid, item.peerIp, "send", item.fileName) { isCanceled(item.mid) }) return
            if (consumePeerBusy(item.mid)) {
                okUpdate(item.copy(status = Status.PENDING))
                releaseTask(item.mid)
                log("对端设备忙，文件 ${item.fileName} 已排队，等对端空闲后自动补发")
                return
            }
        }
        if (!inFlight.add(item.mid)) {
            return  // 已有同 mid 在途（握手补发撞上在途传输），让在途的那个继续
        }
        try {
            inFlightPeer[item.mid] = item.peerIp
            doSendFileInner(uri, item, relPath, dirId, agg, throwOnFail)
        } finally {
            inFlight.remove(item.mid)
            inFlightPeer.remove(item.mid)
            sendLastAt.remove(item.mid)
            // 速率采样器统一收尾:一处覆盖 取消/失败/成功(含文件夹成员),
            // 成功路径里那次 endSpeed 只是提前收尾,这里重入幂等
            endSpeed(item.mid)
            if (ownSlot) releaseTask(item.mid)
        }
    }

    /**
     * 发送停滞看门狗:在途 mid 30s 没写出新字节(对端半死、socket 写阻塞)时,关掉对应会话强制解阻塞→进待发队列→重连后自动续传。
     * 由心跳线程每 [Link.STALL_SCAN_MS] 顺带调用;本地磁盘读停滞 30s+ 会被误判关会话(概率极低,后果仅是重传,正确性无损)
     */
internal fun Link.scanStalledSends() {
        val now = System.currentTimeMillis()
        val staleIps = mutableSetOf<String>()
        for (mid in inFlight) {
            val t = sendLastAt[mid] ?: continue
            if (now - t > Link.STALL_TIMEOUT_MS) {
                val ip = inFlightPeer[mid].orEmpty()
                if (ip.isNotEmpty()) staleIps.add(ip)
            }
        }
        for (ip in staleIps) {
            val s = sessionByGroup(ip) ?: continue
            if (s.closed) continue
            // 反向仍有收包 = 对端活着、只是读得慢（它在落盘/在忙别的任务）：这是单向背压，不是死链。
            // 只让自适应帧降速，绝不关会话 —— 关掉会把同一条链路上正在跑的反向传输一起带走。
            if (now - s.lastRx < Link.STALL_TIMEOUT_MS) {
                log("发送停滞 ${Link.STALL_TIMEOUT_MS / 1000}s 但反向仍有收包（$ip），按对端背压处理，不关会话")
                continue
            }
            log("发送停滞 30s（$ip），关会话解阻塞；重连后自动续传")
            dropSession(s)
        }
    }

internal fun Link.doSendFileInner(
        uri: Uri,
        item: MsgItem,
        relPath: String = "",
        dirId: String = "",
        agg: DirProgress? = null,
        throwOnFail: Boolean = false
    ) {
        // 首块计时起点：用来定位「链路已就绪」到「第一块数据真正出网」之间的空转
        val tTask = System.currentTimeMillis()
        // 目的会话按条目自带的 peerIp 解析 —— 会话中途切换对话设备也不会发错人
        val s = sessionForPending(item.peerIp)
        if (s == null) {
            if (throwOnFail) throw java.io.IOException("未连接")
            setFileVote(Link.VOTE_NO_NETWORK, true, item.peerIp)   // 2.12 / D16：这就是"原本没网络"那一票
            queuePending(item)
            return
        }
        if (isCancelled(item.mid, dirId)) {
            cancelled.remove(item.mid)
            return
        }
        try {
            val mime = FileIO.mimeOf(item.fileName)
            // 上一趟留下的「拒收」记号与本趟无关，先清掉
            rejectedAck.remove(item.mid)
            // 对端在传别的任务（busy 早于本任务开传）：单文件留待发队列；文件夹成员抛 BusyError 让目录整轮中止
            if (consumePeerBusy(item.mid)) {
                pauseForPeerBusy(item, dirId, agg)
                return
            }
            // ① 断点续传协商：只给「普通单文件」用。文件夹成员一律从 0 整文件发 ——
            //   局域网下按文件粒度补发足够快，不做半成品偏移，省掉每成员一次往返和一堆对账代码
            var offset = 0L
            if (item.fileSize > 0 && dirId.isEmpty() && agg == null) {
                offset = negotiateResume(s, item.mid, item.fileName, item.fileSize, uri)
                log(
                    "[首块计时] ${item.fileName} 续传协商 ${System.currentTimeMillis() - tTask}ms" +
                        " → offset=$offset（负数=无应答，将暂停）"
                )
                if (offset < 0L) {
                    // 2.12 / D16：这一趟的续传请求**没发出去或 3s 没应答** ⇒ 保持暂停，
                    // 绝不退化成从头重发（用户："对方收到了就继续传输，没收到就暂停，手动点继续才重试"）
                    pauseInFlight(item, dirId, agg)
                    return
                }
                // 对端报的偏移 = 文件全长 → 它已经有完整一份,那就是送达确认:一个字节都不必再推,
                // 出队 + 记台账直接收尾(专门兜「ack 丢帧/对端回 ack 前掉线、但它其实已经存好」这种假失败);
                // 文件夹成员不判(成员每次重发都用新 mid,且提前 return 会打乱目录侧 dirSendState 记账)
                if (offset == item.fileSize && dirId.isEmpty() && agg == null) {
                    log("文件 ${item.fileName} 对端已存妥完整一份，无需重发，直接标记送达")
                    markFileDelivered(item.mid, s.remoteIp)   // 内部顺手摘掉 awaitingAck
                    store?.removePending(item.mid)
                    okUpdate(item.copy(progress = 100, status = Status.OK))
                    return
                }
                if (offset > 0) {
                    log("断点续传 ${item.fileName}：对端已有 ${TextUtil.humanSize(offset)}，校验后续传")
                }
            }
            // 校验口径：只核对大小，不做 hash。续传前缀由 32MB 分块大小校验负责，
            // 正常传输收尾核对「磁盘字节数 == 实收 == 声明」，续传完成后再由 file_verify(size) 端到端核对。
            // ①-a 多通道分支已停用（回到单连接：第二条 TCP 连接在千兆局域网无实测收益，
            //   却多一条独立断链路径，且要求 4MB 单元的窗口重排）。保留 doSendUnitized 代码但不再调用。
            // ①-b 零拷贝分支（P1-4）：非续传 + 源可 seek → 每块 transferTo 直送 socket，数据不进用户态内存。
            // 续传需先把源流推进到 offset，走下面的流式路径；不可 seek 的源（云盘虚拟文档等）同样回落。
            if (Link.ZC_SEND && offset == 0L && item.fileSize > 0) {
                val zc = FileIO.openSourceChannel(ctx, uri)
                if (zc != null) {
                    try {
                        log("零拷贝发送 ${item.fileName}（${TextUtil.humanSize(item.fileSize)}）" +
                            "｜入队到首块 ${System.currentTimeMillis() - tTask}ms")
                        doSendFileZeroCopy(s, item, zc.channel, mime, relPath, dirId, agg)
                        return
                    } finally {
                        runCatching { zc.close() }
                    }
                }
            }
            var ins: InputStream? = openSource(uri)
            // ⚠ 本地源打不开(SAF / 第三方 FileProvider 授权失效、文件已被删)**不是**网络故障:
            //   必须抛 SourceError。抛 IOException 会被下面的通用 catch 当成链路故障 → 关掉整条会话 → 双方保活重连 →
            //   握手补发同一条 → 又打不开 → 再关,形成「连上就断」死循环(真机实测:批量发文件时只要有一个 URI 失效,整批陷入 5s 一断的重连风暴)
            if (ins == null) throw SourceError("打不开源文件: $uri")
            if (offset > 0) {
                // ② 把源流推进到 offset（只跳过、不算摘要），之后直接续着读，不用 reopen
                if (!skipPrefix(ins, offset)) {
                    offset = 0
                    log("前缀跳过失败，改从头发送 ${item.fileName}")
                    runCatching { ins.close() }
                    ins = openSource(uri)
                    if (ins == null) throw SourceError("打不开源文件: $uri")
                }
            }
            ins.use { stream ->
                // ③ 双 8MB 缓冲交替读源（后台线程读盘，与网络发送重叠），分片长度由自适应帧决定
                s.send(
                    Protocol.fileMeta(item.mid, item.fileName, item.fileSize, mime, "", offset, relPath, dirId)
                )
                val reader = BlockReader(stream, Protocol.RX_BUF_SIZE)
                // 自适应帧：1M 起步，0.5s 一评估，抖了降两档，顶档 8M（见 AdaptiveFrame）
                val frame = AdaptiveFrame(item.fileName)
                try {
                    // 帧序号只用于接收端按序校验，与文件偏移无关，续传也从 0 起
                    var seq = 0
                    var sent = offset
                    var lastPct = -1
                    while (true) {
                        if (isCancelled(item.mid, dirId)) {
                            cancelled.remove(item.mid)
                            log("文件发送已取消 ${item.fileName}（已发 $sent B）")
                            return
                        }
                        val (buf, n) = reader.next()
                        if (n == -1) break
                        // 读源中途失败(磁盘错误/文件被截断/授权中途失效)同样是本地问题:抛 SourceError,绝不能被当成网络故障去关会话
                        if (n == -2 || buf == null) throw SourceError("读取源文件失败")
                        var off = 0
                        while (off < n) {
                            if (isCancelled(item.mid, dirId)) {
                                cancelled.remove(item.mid)
                                log("文件发送已取消 ${item.fileName}（已发 $sent B）")
                                return
                            }
                            // 2.12 / D16：在传的遇到投票 = 1 ⇒ 在下一个分片边界就地结束本次尝试
                            //（不劈开已入队的字节、不进 cancelled，条目回待发台账等拦截解除自动续发一次）
                            if (fileGateBlocked(item.peerIp)) {
                                pauseInFlight(item, dirId, agg)
                                return
                            }
                            if (rejectedAck.remove(item.mid)) {
                                // 对端建接收通道失败已提前回绝：立即停止推送，别把 GB 级字节倒进虚空
                                log("文件 ${item.fileName} 对端提前拒收，停止推送（条目留在待发队列）")
                                endSpeed(item.mid)
                                okUpdate(item.copy(status = Status.FAILED))
                                return
                            }
                            // 对端回 busy（单任务槽被占）：停推，条目留待发队列等 sync_req
                            if (consumePeerBusy(item.mid)) {
                                pauseForPeerBusy(item, dirId, agg)
                                return
                            }
                            // 帧长自适应（读块 8MB 是上限，档位再高也按块切）
                            // 单帧整帧上限是 MAX_FRAME，帧头(6B+mid)也要占额度 -> 载荷再多夹一道上限，
                            // 否则顶档 8M 必然抛「分片帧过大」(见 [framePayloadCap])
                            val len = minOf(frame.size(), n - off, framePayloadCap(item.mid))
                            val w0 = System.nanoTime()
                            s.sendChunk(item.mid, seq, buf, off, len)
                            frame.onFrame(len, System.nanoTime() - w0)
                            seq++
                            off += len
                            sent += len
                            // 发送停滞看门狗：每写出一块刷新「最近进度时刻」
                            sendLastAt[item.mid] = System.currentTimeMillis()
                            // 速率采样（字节级）：进度是整数百分比，大文件太粗 → 气泡速率靠这里
                            markSpeed(item.mid, sent)
                            if (agg != null) {
                                agg.add(len.toLong())
                            } else if (item.fileSize > 0) {
                                val pct = ((sent * 100) / item.fileSize).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    okUpdate(item.copy(progress = pct, status = Status.SENDING))
                                }
                            }
                        }
                        // 这块发完了：**必须归还**给 free 队列，读线程才能继续填下一块；
                        // 只有 2 块缓冲，不还的话发完 2×4MB 后读线程永远阻塞在 free.take()，消费端卡死在 ready.take() —— 大文件必然卡住
                        reader.recycle(buf)
                    }
                    // 源文件实际可读字节少于声明（提供器虚报 / 云占位 / 中途被截断）：
                    // 按本地源错误收尾（不重试、出队、通知对端清半成品），否则每轮「发完→对端判少→留队」永远出不去（S16）
                    if (item.fileSize > 0 && sent < item.fileSize) {
                        throw SourceError(
                            "源文件实际可读 $sent B 少于声明 ${item.fileSize} B（可能被截断或提供器虚报）"
                        )
                    }
                } finally {
                    reader.cancel()
                }
            }
            if (isCancelled(item.mid, dirId)) {
                cancelled.remove(item.mid)
                log("文件发送已取消 ${item.fileName}")
                return
            }
            // 校验只核对大小：file_done 的 sha256 字段恒空串（保留字段仅为线上格式兼容）
            if (dirId.isEmpty() && agg == null) {
                // 普通单文件：登记回执 → 发 file_done → 气泡收尾（顺序不能颠倒，见 [finishSingleSend]）
                finishSingleSend(s, item, "", offset)
            } else {
                // 先登记成员回执映射，再发 file_done：对端 ack(false) 到达时凭 mid 找回 (dirId, relPath) 记账
                if (dirId.isNotEmpty()) {
                    dirMemberAck[item.mid] = dirId + "\u0001" + relPath
                }
                s.send(Protocol.fileDone(item.mid, ""))
                if (agg == null) {
                    // 文件夹成员：进度已由聚合器反映，气泡状态在整目录收尾时更新
                    endSpeed(item.mid)
                    okUpdate(item.copy(progress = 100, status = Status.OK))
                }
                log("文件发送完成 ${item.fileName} ${TextUtil.humanSize(item.fileSize)}，等对端回报落盘")
            }
        } catch (b: BusyError) {
            // 对端设备忙：链路是好的，绝不关会话 —— 条目留待发队列，等对端释放槽位的 sync_req 再补发
            awaitingAck.remove(item.mid)
            rejectedAck.remove(item.mid)
            cancelled.remove(item.mid)
            if (dirId.isEmpty() && agg == null) {
                log("对端设备忙，文件 ${item.fileName} 已留在待发队列（等对端空闲后 sync_req 补发）")
                return
            }
            throw b
        } catch (t: Throwable) {
            cancelled.remove(item.mid)
            // 没走到「等 ack」就别留在待确认表里（条目按各自分支回待发/出队）
            awaitingAck.remove(item.mid)
            rejectedAck.remove(item.mid)
            if (t is SourceError) {
                // **本地**读不了源文件(被删/无权限/SAF 授权失效):链路本身是好的,
                // 因此绝不关连接(老逻辑误杀连接,把后续所有传输一起带走),也不重试(重试同样是失败,只会无限循环)
                log("文件发送失败（本地源不可读）${item.fileName}：${t.message}")
                // 对端可能已建好半成品：通知它删掉，免得留下残缺文件在「已接收」里（与 PC 端同口径）
                sendOrDrop(s, Protocol.fileCancel(item.mid), "file_cancel", quiet = true)
                if (throwOnFail) throw t
                // ⚠ 必须**出队**：doSendFile 入口已经 queuePending，这里不清掉的话
                //   每次握手补发都会把这条读不了的文件重放一遍 —— 几十条积压就是反复雪崩。
                if (dirId.isEmpty() && agg == null) store?.removePending(item.mid)
                okUpdate(item.copy(status = Status.FAILED))
                return
            }
            log("文件发送失败(${t.javaClass.simpleName}: ${t.message})")
            // 半死连接：只废弃这一条（别的设备不受影响），不重连
            s.close()
            sessions.remove(s)
            if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
            notifyPeers()
            refreshState()
            if (throwOnFail) throw t
            // 文件转入待发；重连后 file_query 会从对端半成品处续传（只补发回这台设备）
            okUpdate(item.copy(status = Status.PENDING))
            queuePending(item)
        }
    }

    /**
     * 本地源文件不可读（被删 / 无权限 / SAF 授权失效 / 读取中断）。
     * 与网络故障**严格区分**：本地问题不该关连接、不该让整个文件夹停摆、也不该无限重试
     */
    class SourceError(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

    /**
     * 对端设备忙（单任务槽被别的任务占着）。**不是链路故障**：不关会话、不标失败，
     * 条目回待发队列，等对端释放槽位的 `sync_req` 再补发。文件夹成员用它中止整轮。
     */
    class BusyError(message: String) : RuntimeException(message)

internal fun Link.openSource(uri: Uri): InputStream? = FileIO.openSource(ctx, uri)

    /**
     * 零拷贝发送（P1-4）：按 4MB 块把源文件直接 transferTo 进 socket，数据不经过用户态内存。
     * 取消 / 数据流量闸门 / 进度 / 速率 / 收尾语义与流式路径逐条对齐；只用于非续传（offset=0），
     * 续传要算整文件 SHA-256，必须逐块喂 digest，仍走流式路径。
     */
internal fun Link.doSendFileZeroCopy(
        s: Session, item: MsgItem, src: FileChannel, mime: String,
        relPath: String, dirId: String, agg: DirProgress?
    ) {
        val total = item.fileSize
        // 源文件比声明小（提供器虚报 / 云占位 / 已被截断）：直接按本地源错误收尾，
        // 别把半截文件当完整文件推完再让对端判少、留队无限重发（S16）
        if (total > 0 && src.size() < total) {
            throw SourceError("源文件实际 ${src.size()} B 少于声明 $total B（文件可能已被截断）")
        }
        s.send(Protocol.fileMeta(item.mid, item.fileName, total, mime, "", 0L, relPath, dirId))
        var seq = 0
        var sent = 0L
        var lastPct = -1
        // 自适应帧（与流式路径同规则）：1M 起步，0.5s 一评估，抖了降两档，顶档 8M
        val frame = AdaptiveFrame(item.fileName)
        while (sent < total) {
            if (isCancelled(item.mid, dirId)) {
                cancelled.remove(item.mid)
                log("文件发送已取消 ${item.fileName}（已发 $sent B）")
                return
            }
            // 2.12 / D16：在传的遇到投票 = 1 ⇒ 在下一个分片边界就地结束本次尝试
            if (fileGateBlocked(item.peerIp)) {
                pauseInFlight(item, dirId, agg)
                return
            }
            // 与流式路径同口径：对端建接收通道失败已提前回绝 → 立即停推，别把 GB 级字节倒进虚空（S14/S21）
            if (rejectedAck.remove(item.mid)) {
                log("文件 ${item.fileName} 对端提前拒收，停止零拷贝推送（条目留在待发队列）")
                endSpeed(item.mid)
                okUpdate(item.copy(status = Status.FAILED))
                return
            }
            // 对端回 busy（单任务槽被占）：停推，条目留待发队列等 sync_req
            if (consumePeerBusy(item.mid)) {
                pauseForPeerBusy(item, dirId, agg)
                return
            }
            // 与块读路径同规则：8M 档的载荷要扣掉帧头(6B+mid)，见 [framePayloadCap]
            val len = minOf(total - sent, minOf(frame.size().toLong(), framePayloadCap(item.mid).toLong())).toInt()
            val w0 = System.nanoTime()
            s.sendChunkFile(item.mid, seq, src, sent, len)
            frame.onFrame(len, System.nanoTime() - w0)
            seq++
            sent += len
            // 发送停滞看门狗：每写出一块刷新「最近进度时刻」
            sendLastAt[item.mid] = System.currentTimeMillis()
            markSpeed(item.mid, sent)
            if (agg != null) {
                agg.add(len.toLong())
            } else if (total > 0) {
                val pct = ((sent * 100) / total).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    okUpdate(item.copy(progress = pct, status = Status.SENDING))
                }
            }
        }
        if (isCancelled(item.mid, dirId)) {
            cancelled.remove(item.mid)
            log("文件发送已取消 ${item.fileName}")
            return
        }
        // 中途被截断的兜底（与流式路径同口径）：实际推出字节少于声明 → 按本地源错误收尾（S16）
        if (item.fileSize > 0 && sent < item.fileSize) {
            throw SourceError("零拷贝实际推出 $sent B 少于声明 ${item.fileSize} B（源文件被截断）")
        }
        // 非续传：sha 发空串，接收端见空串即「对端未要求校验」
        if (dirId.isEmpty() && agg == null) {
            finishSingleSend(s, item, "", 0L)
        } else {
            if (dirId.isNotEmpty()) {
                dirMemberAck[item.mid] = dirId + "\u0001" + relPath
            }
            s.send(Protocol.fileDone(item.mid, ""))
            if (agg == null) {
                endSpeed(item.mid)
                okUpdate(item.copy(progress = 100, status = Status.OK))
            }
            log("文件发送完成 ${item.fileName} ${TextUtil.humanSize(item.fileSize)}，等对端回报落盘")
        }
    }

    /**
     * 2 期多通道发送：4MB 单元逐个交给「谁空谁领」的 N 条通道，每条通道一个线程 + 一个独立 FileChannel，
     * 整单元走 transferTo 零拷贝直发 —— 发送端一分用户态缓冲都不用。
     * 接收端按 3 槽重排环乱序收、顺序落盘，因此单元到达顺序可以乱，但每个单元必须整块到达。
     */
internal fun Link.doSendUnitized(
        s: Session, item: MsgItem, uri: Uri, mime: String,
        relPath: String, dirId: String, agg: DirProgress?, lanes: List<Session>
    ) {
        val unit = Protocol.CHUNK_SIZE
        val total = item.fileSize
        val units = ((total + unit - 1) / unit).toInt()
        val declared = lanes.size
        val chans = ArrayList<FileIO.SeekableSource>(declared)
        try {
            for (i in 0 until declared) {
                chans.add(FileIO.openSourceChannel(ctx, uri) ?: throw SourceError("打不开源文件: $uri"))
            }
            // 先登记「等就绪」的 latch，再发声明 + 元数据：接收端建好 Rx 会回 xe_ready，
            // 收到之后才允许起数据通道。否则比 file_meta 更早到的分片在接收端查不到 mid 会被丢，
            // 单元缺口补不齐 → 3 槽重排环卡到 30s 超时（1GB 首测卡死就是这么来的）。
            val ready = java.util.concurrent.CountDownLatch(1)
            expectXeReady(item.mid, ready)
            val readyOk = try {
                // 先声明后发元数据：接收端建 Rx 时要靠 xe_begin 决定用重排环还是 1 期双缓冲
                s.send(Protocol.xeBegin(item.mid, unit, declared, 0L))
                s.send(Protocol.fileMeta(item.mid, item.fileName, total, mime, "", 0L, relPath, dirId))
                ready.await(Link.XE_READY_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                // 发送失败/中断也要摘掉登记，避免 map 里留一颗永不消费的 latch
                clearXeReady(item.mid)
            }
            // 超时（对端不支持/异常）→ 只用主连接按单元顺序发；接收端重排环照样按序收
            var use = lanes
            if (!readyOk) {
                log("多通道：等对端接收上下文就绪超时（${Link.XE_READY_WAIT_MS}ms 未见 xe_ready），" +
                    "${item.fileName} 降级为主连接单通道")
                use = listOf(s)
            }
            val n = use.size
            log("多通道发送 ${item.fileName}（${TextUtil.humanSize(total)}，$n 条通道 × $units 个单元）")

            val nextUnit = AtomicInteger(0)
            /** 真正写完的单元数（错误信息用真实完成数，别拿“已分配”当“已发完”） */
            val doneUnits = AtomicInteger(0)
            val sentBytes = AtomicLong(0)
            val lastPct = AtomicInteger(-1)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val errLock = Object()
            var failed: Throwable? = null
            var cancelledHere = false
            var pausedHere = false

            val threads = (0 until n).map { li ->
                Thread({
                    try {
                        while (!stop.get()) {
                            val u = nextUnit.getAndIncrement()
                            if (u >= units) break
                            if (isCancelled(item.mid, dirId)) {
                                cancelled.remove(item.mid)
                                cancelledHere = true
                                stop.set(true)
                                return@Thread
                            }
                            // 2.12 / D16：数据流量闸门在**单元边界**收手，已入队的字节不劈开
                            if (fileGateBlocked(item.peerIp)) {
                                pausedHere = true
                                stop.set(true)
                                return@Thread
                            }
                            val at = u.toLong() * unit
                            val len = minOf(unit.toLong(), total - at).toInt()
                            use[li].sendChunkFile(item.mid, u, chans[li].channel, at, len)
                            doneUnits.incrementAndGet()
                            sendLastAt[item.mid] = System.currentTimeMillis()
                            val done = sentBytes.addAndGet(len.toLong())
                            markSpeed(item.mid, done)
                            if (agg != null) {
                                agg.add(len.toLong())
                            } else if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (lastPct.get() != pct && lastPct.getAndSet(pct) != pct) {
                                    okUpdate(item.copy(progress = pct, status = Status.SENDING))
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        synchronized(errLock) { if (failed == null) failed = t }
                        log("通道 lane=$li 发送中断：${t.message}（剩余单元交给其它通道）")
                        stop.set(true)
                    }
                }, "pp-send-l$li").apply { isDaemon = true }.also { it.start() }
            }
            threads.forEach { runCatching { it.join() } }

            if (cancelledHere || isCancelled(item.mid, dirId)) {
                cancelled.remove(item.mid)
                log("多通道发送已取消 ${item.fileName}（已发 ${TextUtil.humanSize(sentBytes.get())}）")
                return
            }
            if (pausedHere) {
                pauseInFlight(item, dirId, agg)
                return
            }
            val miss = units - minOf(doneUnits.get(), units)
            if (miss > 0 || failed != null) {
                val t = failed
                if (t is SourceError) throw t
                throw java.io.IOException(
                    "多通道发送中断（${units - miss}/$units 个单元）：${t?.message ?: "通道全部退出"}"
                )
            }
            s.send(Protocol.xeEnd(item.mid))
            // 非续传：sha 发空串，接收端见空串即「对端未要求校验」
            if (dirId.isEmpty() && agg == null) {
                finishSingleSend(s, item, "", 0L)
            } else {
                if (dirId.isNotEmpty()) {
                    dirMemberAck[item.mid] = dirId + "\u0001" + relPath
                }
                s.send(Protocol.fileDone(item.mid, ""))
                if (agg == null) {
                    endSpeed(item.mid)
                    okUpdate(item.copy(progress = 100, status = Status.OK))
                }
                log("文件发送完成（多通道）${item.fileName} ${TextUtil.humanSize(item.fileSize)}，等对端回报落盘")
            }
        } finally {
            for (c in chans) runCatching { c.close() }
        }
    }

    /**
     * 发送端读源：双 4MB 缓冲交替，后台线程读盘，与网络发送重叠。
     * 空闲缓冲从 [free] 领取、填满放回 [ready]；消费端发完归还；len=-1 表示 EOF，len=-2 表示读源失败
     */
internal class BlockReader(ins: InputStream, internal val blockSize: Int) {
        internal val ready = LinkedBlockingQueue<Pair<ByteArray?, Int>>()
        internal val free = LinkedBlockingQueue<ByteArray>()
        internal val thread: Thread

        init {
            val a = ByteArray(blockSize)
            val b = ByteArray(blockSize)
            free.add(a)
            free.add(b)
            thread = Thread({
                try {
                    while (true) {
                        val buf = free.take()
                        var off = 0
                        while (off < blockSize) {
                            val n = ins.read(buf, off, blockSize - off)
                            if (n < 0) break
                            off += n
                        }
                        if (off == 0) {
                            ready.put(Pair(null, -1))
                            break
                        }
                        ready.put(Pair(buf, off))
                        if (off < blockSize) {
                            ready.put(Pair(null, -1))
                            break
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { ready.offer(Pair(null, -2)) }
                } finally {
                    runCatching { ins.close() }
                }
            }, "pp-src-read").apply { isDaemon = true; start() }
        }

        /** 阻塞取下一块；len=-1 EOF，len=-2 读源失败 */
        @Throws(InterruptedException::class)
        fun next(): Pair<ByteArray?, Int> = ready.take()

        /** 消费完一块后归还给 free 队列；双缓冲防覆写的关键一步（见消费端注释） */
        fun recycle(buf: ByteArray?) {
            if (buf != null) free.offer(buf)
        }

        /** 取消：中断读线程（消费端提前退出时调用） */
        fun cancel() {
            thread.interrupt()
        }
    }
