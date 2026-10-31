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
// 发送管线（v2.7 拆分自 Link.kt）：文件夹/文件发送、断点续传协商、md5 核对、BlockReader。全部为 Link 的扩展函数。
// ======================================================================

/**
 * 文件 / 文件夹发送的**唯一入队口**（2026-09-21 用户定调「排队发送」）：
 * 把整段发送流程（数据流量确认 + 传输）丢进 [Link.fileSendExec] 单线程队列，
 * 保证「一次只有一个文件在传送，前一个完成立刻进入下一个」。
 *
 * ⚠ 只给**顶层发送入口**用（用户发送 / 重试 / 待发补发 / hsync 补图）。
 *   文件夹成员由 [Link.doSendFolderInner] 在队列线程内同步顺序调用，不能再各自入队
 *   —— 否则一个文件夹会被拆成 N 个排队任务。
 * ⚠ 语音 / 视频 / 消息**不走这里**（见 [Link.fileSendExec] 注释），通话不受排队影响。
 */
internal fun Link.enqueueFileSend(tag: String, task: () -> Unit) {
    val depth = fileSendQueueDepth()
    if (depth > 0) log("$tag 已排队（前面还有 $depth 个在等）")
    try {
        fileSendExec.execute {
            try {
                task()
            } catch (t: Throwable) {
                log("$tag 队列任务异常(${t.javaClass.simpleName}: ${t.message})")
            } finally {
                // 队列空了（最后一个任务跑完）就补跑一次超龄半成品回收（plan 07）：
                // 「取消帧丢失 + 会话一直不断」的孤儿条目不必等到下次启动
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
        // 开传前先入待发队列（防强杀）：整目录发送成功后才移除；
        // 重启重连后自动补发，已完成的成员靠 dirSendState……
        // 注意 dirSendState 是内存态，重启后为空 —— 成员凭各自半成品（partials）续传，
        // 已完整送达的成员会整文件重传一遍（对端生成重复副本，可手动删，不影响正确性）。
        queuePending(item)
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
        }
    }

    /**
     * 发送一个文件夹（v2.6 起带完整性闭环）。
     *
     * 流程：`dir_meta`（带成员清单）→ 逐成员发送 → `dir_end` 收尾 → 等对端核对回执
     * （`dir_missing`）→ 有缺失就**只补发缺失项**（最多 [DIR_RESEND_ROUNDS] 轮）→
     * 仍有缺件则如实标失败并把缺的文件名列进日志。
     *
     * 为什么必须这么改（2026-09-16 用户报「传文件夹会漏掉部分文件」）：
     *  - 老流程发送端只要 `file_done` 写进 socket 就当成功，**完全不等对端确认**。
     *    对端建文件失败 / 落盘失败 / 校验失败时只是本地记一行日志，发送端照样报
     *    「文件夹发送完成」→ 用户永远不知道少了哪些。
     *  - 成员失败即中止整目录：一个读不了的文件就能让**后面所有成员**一个都发不出去。
     *    现在本地源不可读只跳过该成员并继续，最后统一汇报。
     */
internal fun Link.doSendFolderInner(
        treeUri: Uri, item: MsgItem, files: List<FileIO.TreeFile>, emptyDirs: List<String>,
        skipped: Int
    ) {
        val s = sessionForPending(item.peerIp)
        if (s == null) {
            setFileVote(Link.VOTE_NO_NETWORK, true)   // 2.12 / D16：文件夹同一条口径（"原本没网络"票）
            queuePending(item)
            return
        }
        if (cancelled.contains(item.mid)) {
            cancelled.remove(item.mid)
            return
        }
        // 只有对端也支持（hello dl=1）才启用清单/收尾/核对；老端保持原行为，不引入未知帧
        val verifyPeer = s.caps.dl
        val relPaths = files.map { it.relPath }
        val wireList =
            if (verifyPeer && relPaths.isNotEmpty() && wireListFits(relPaths)) relPaths else emptyList()
        if (verifyPeer && wireList.isEmpty() && relPaths.isNotEmpty()) {
            log("成员清单过长（${relPaths.size} 项），本次改用「按数量核对」完整性")
        }
        try {
            s.send(
                Protocol.dirMeta(
                    item.mid, item.fileName, files.size, item.fileSize, emptyDirs, wireList, skipped
                )
            )
            val agg = DirProgress(this, item, item.fileSize)
            val state = dirSendState.getOrPut(item.mid) { ConcurrentHashMap() }
            // 本地不可读而跳过的成员（本地问题，补发也无用 —— 最后如实汇报）
            val unreadable = ArrayList<String>()
            var pendingSet = files
            var round = 0
            var peerMissing: List<String> = emptyList()
            var peerAllOk = true
            while (true) {
                for (f in pendingSet) {
                    if (cancelled.contains(item.mid)) {
                        cancelled.remove(item.mid)
                        // 防竞态残留：cancelFile 已 remove 后，成员完成钩子 getOrPut 可能重建 map
                        dirSendState.remove(item.mid)
                        log("文件夹发送已取消 ${item.fileName}（已发 ${files.size - pendingSet.size}/${files.size}）")
                        return
                    }
                    // 2.12 / D16：投票 = 1 ⇒ 在**成员边界**结束整目录这次尝试。
                    // ⚠ 不清 dirSendState：已完成成员的记号要留着，拦截解除后整目录续发时跳过它们。
                    if (fileGateBlocked()) {
                        pauseInFlight(item, "", null)
                        return
                    }
                    if (state[f.relPath] == true) {
                        // 重发时跳过已完成的成员（进度也要补上）
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
                        // 本地读不了（文件已删 / 无权限 / SAF 授权失效）：
                        // **只跳过这一个成员**继续发其余 —— 老逻辑会让整目录就此停摆
                        unreadable.add(f.relPath)
                        log("成员本地不可读，跳过继续：${f.relPath}（${e.message}）")
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
                    log("对端未在 ${DIR_MISSING_WAIT_MS / 1000}s 内回报文件夹核对结果，按已发送处理")
                    peerAllOk = unreadable.isEmpty()
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
                log("文件夹发送完成 ${item.fileName}（${files.size} 个文件，${TextUtil.humanSize(item.fileSize)}）")
            } else {
                okUpdate(item.copy(status = Status.FAILED))
                store?.removePending(item.mid)
                val head = bad.take(20).joinToString(", ")
                val tail = if (bad.size > 20) " …（略）" else ""
                log(
                    "文件夹发送不完整 ${item.fileName}：应有 ${files.size} 个，未送达 ${bad.size} 个 → " +
                        "$head$tail" +
                        (if (unreadable.isNotEmpty()) "（其中 ${unreadable.size} 个本地不可读，请检查文件是否已被删除/授权是否失效）" else "")
                )
            }
            // 整目录收尾：同步丢掉本 mid 的发送状态表（防 dirSendState 无界增长，2026-09-28）
            dirSendState.remove(item.mid)
        } catch (t: Throwable) {
            if (cancelled.contains(item.mid)) {
                cancelled.remove(item.mid)
                log("文件夹发送已取消 ${item.fileName}")
                return
            }
            // 失败收尾：同样丢掉状态表（重发走待发队列整目录补发，不依赖 entry 残留）
            dirSendState.remove(item.mid)
            log("文件夹发送失败(${t.javaClass.simpleName}: ${t.message})")
            okUpdate(item.copy(status = Status.FAILED))
        } finally {
            // 整目录速率采样器统一收尾（2026-09-29）：覆盖 while 内取消 / 循环后取消 / catch 失败；
            // 定稿处那次 endSpeed 保留，重入幂等。
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
     * 断点续传协商：问对端有没有这个文件的半成品。
     *
     * 返回值口径（2.12 起，与 PC 端 `LinkSend.negotiateResume` 一致）：
     *  - **>0** = 对端已有这么多字节（按分片边界对齐后从这儿续）；
     *  - **0** = 对端答了"没有半成品"（从头发）；
     *  - **-1** = **没发出问题或 3s 没收到应答** ⇒ 调用方保持暂停，不许退化成从头重发
     *    （用户点的 ⑬-1："对方收到了就继续传输，如果没收到就暂停，需要手动点继续才重试"）。
     */
internal fun Link.negotiateResume(s: Session, mid: String, name: String, size: Long): Long {
        val q = java.util.concurrent.ArrayBlockingQueue<Long>(1)
        resumeWaiters[mid] = q
        try {
            if (!sendOrDrop(s, Protocol.fileQuery(mid, name, size), "file_query", quiet = true)) {
                return -1L
            }
            val off = q.poll(3000, TimeUnit.MILLISECONDS)
            if (off == null) return -1L
            if (off <= 0L) return 0L
            return if (off == size) off else off - (off % Protocol.CHUNK_SIZE)
        } catch (t: Throwable) {
            return -1L
        } finally {
            resumeWaiters.remove(mid)
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

    // ==================== 续传完成 MD5 核对（端到端） ====================

    /** 一次续传核对的会话状态：两端 MD5 各自后台算，谁先就绪都不阻塞 */
internal class VerifyState(val mid: String, val item: MsgItem) {
        @Volatile var myMd5: String? = null
        @Volatile var peerMd5: String? = null
        @Volatile var finished = false
    }

    /** 流式计算 MD5（十六进制小写）；流打不开/读失败返回空串 */
internal fun Link.md5Of(ins: InputStream?): String {
        if (ins == null) return ""
        return try {
            val d = MessageDigest.getInstance("MD5")
            val buf = ByteArray(1 shl 16)
            ins.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    d.update(buf, 0, n)
                }
            }
            hex(d.digest())
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 发送端：续传文件发完后启动核对 —— 后台算**源文件** MD5，等对端回发**落盘文件** MD5 比对。
     * 超时（对端没回发 / 任一方没算完）只记日志跳过，绝不阻塞、不改传输状态。
     */
internal fun Link.startVerifyWatch(item: MsgItem, resumedFrom: Long) {
        val st = VerifyState(item.mid, item)
        if (verifyMap.putIfAbsent(item.mid, st) != null) return
        Thread({
            st.myMd5 = md5Of(openSource(Uri.parse(item.filePath)))
            compareVerify(st)
        }, "pp-tx-md5").apply { isDaemon = true }.start()
        Thread({
            try {
                Thread.sleep(verifyTimeoutMs)
            } catch (_: InterruptedException) {
            }
            if (!st.finished) {
                st.finished = true
                verifyMap.remove(item.mid)
                log("MD5 核对超时 ${item.fileName}（对端未回发或双方未算完，自 ${resumedFrom} B 续传），跳过")
            }
        }, "pp-verify-timeout").apply { isDaemon = true }.start()
        log("续传文件发送完成，等待对端 MD5 核对 ${item.fileName}（自 ${TextUtil.humanSize(resumedFrom)} 起）")
    }

    /** 接收端回发的落盘 MD5 到了 → 尝试比对 */
internal fun Link.onFileVerify(j: JSONObject) {
        val mid = j.optString("mid")
        val st = verifyMap[mid]
        if (st == null) {
            log("收到 MD5 核对帧但无在途核对（mid=$mid 已超时/非续传/旧版本对端），忽略")
            return
        }
        st.peerMd5 = j.optString("md5")
        compareVerify(st)
    }

    /** 双方 MD5 都就绪才比对；比对一次性收尾（finished 原子防重入） */
internal fun Link.compareVerify(st: VerifyState) {
        synchronized(st) {
            val mine = st.myMd5
            val peer = st.peerMd5
            if (st.finished || mine.isNullOrEmpty() || peer.isNullOrEmpty()) return
            st.finished = true
            verifyMap.remove(st.mid)
            if (mine.equals(peer, ignoreCase = true)) {
                log("MD5 核对一致 ${st.item.fileName}（$mine）")
            } else {
                log("MD5 核对不一致 ${st.item.fileName}：本地 $mine / 对端落盘 $peer")
                // 气泡标失败提醒用户重传；接收端文件已在，是否删除由用户决定
                okUpdate(st.item.copy(status = Status.FAILED))
            }
        }
    }

    /**
     * 读源文件前 offset 字节只做摘要（续传时补齐整文件 sha256 的前缀段），不发送。
     * 读失败返回 false，调用方改从头传。
     */
internal fun Link.digestPrefix(ins: InputStream, len: Long, digest: MessageDigest): Boolean {
        return try {
            val buf = ByteArray(1 shl 16)
            var left = len
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) return false
                digest.update(buf, 0, n)
                left -= n
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 发送一个文件（1MB 二进制分片 + 双 4MB 缓冲读源 + 断点续传）。
     *
     * @param relPath  文件夹成员时为目录内相对路径
     * @param dirId    所属目录气泡 mid（空 = 普通单文件）
     * @param agg      文件夹进度聚合器（普通单文件为 null）
     * @param throwOnFail true = 成员失败向上抛（整目录标失败）；false = 记待发队列自动重试
     */
    /** 正在发送中的 mid（含文件夹整体）：防止握手补发与在途传输重复发同一条 */
    internal val inFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 在途发送的 mid → 对端地址：发送停滞看门狗用（30s 无新字节 → 关该会话解阻塞） */
    internal val inFlightPeer = ConcurrentHashMap<String, String>()

    /** 在途发送的 mid → 最近一次写出新字节的时刻（发送停滞看门狗用） */
    internal val sendLastAt = ConcurrentHashMap<String, Long>()

    /** 一次「字节已写出、等对端回报落盘」的登记：气泡快照 + 当时用的对端地址 */
    internal class AckWait(internal val item: MsgItem, internal val peerIp: String)

    /**
     * 已把全部分片和 `file_done` 写出、但**对端还没回报落盘结果**的普通单文件：mid → 登记。
     *
     * 为什么写完 `file_done` 不能就算送达（2026-10-01 方案 2）：那一刻对端完全可能还在落盘、
     * 校验没过、或者回 ack 之前链路就断了。而原来的写法在这里就把条目出队并记了送达台账 ——
     * 于是这条文件既不会再由待发队列补发（不在队列里）、补发也会被台账闸门跳过（记成已送达），
     * 结果是**对端永远少这一份、本机气泡却报成功**（方案 2 治的方向与重复投递相反：漏传/假成功）。
     * 现在出队 + 记台账统一改由对端的 `ack` 帧驱动（见 [onSendAck]）；
     * 在等的这段时间条目一直留在待发队列里，任何意外下次握手都会自动补发，
     * 补发时先凭同一个 mid 问一遍对端「这条到没到」（见 [doSendFileInner] 的续传协商）。
     */
    internal val awaitingAck = ConcurrentHashMap<String, AckWait>()

    /*
     * 对端**提前回绝**（`ack(ok=false)` 在字节还没推完时就到）的 mid 记号。
     *
     * 为什么要有：`awaitingAck` 是写完 `file_done` 才挂上的，而接收端在建接收通道失败时
     * （存盘目录不可写 / 磁盘建不了文件）会在 `file_meta` 这一步立刻回 `ack false` ——
     * 那一刻我方还在闷头推分片，回执没人认领，等推完再挂 [awaitingAck] 就永远等不到第二份 ack。
     * 有了这个记号，收尾时就能把这条按失败处理（气泡标失败、条目留在待发队列下次重试）。
     */
    internal val rejectedAck: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /*
     * 送达台账（**已完整送达**的普通单文件）：键 = `mid@对端组键`，判据见 [Store.isDelivered]。
     *
     * 为什么要这张表（2026-10-01 用户报「发 2 个文件、电脑里躺 5 个」）：一次握手会同时
     * 触发两条投递路径 —— ① 待发队列补发（[flushPendingNow]）；② 对端 `hsync_pull`
     * 点名要它缺的那条（[resendHistoryFile]，按**原 mid** 重发）。两条都只判 [inFlight]，
     * 而发送队列是**串行**的：各自入队时对方还没跑，[inFlight] 当然是空的，于是同一条文件
     * 被完完整整发两遍。[inFlight] 在任务收尾时就把 mid 摘掉了，兜不住「先后各跑一次」，
     * 所以「送达过没有」要单独记一份 —— 落盘端（PC）已有同名闸门，那是最后一道，
     * 源头不该把同一份字节发两遍。
     *
     * 键里带对端**设备组键**：同一条消息可能被发给两台设备，那不算重复。
     * 台账由 Store 持久化（`delivered.json`）—— 只放内存的话，手机重启 / 进程被杀即归零，
     * 下一次握手又会把同一个 mid 推一遍，所以重启之后也必须还认得它。
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
     * 对端对这条文件回了落盘回报（`ack`）—— 送达与否以它为准（方案 2）。
     *
     * @param ok 对端是否已把这条完整落盘
     * @return true = 这是我方正在等的那条文件回执，调用方无需再按通用 ack 处理
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
        log("对端已确认落盘 ${w.item.fileName}，出待发队列")
        return true
    }

    /**
     * 发送入口（防强杀版）：**开传前**先把条目落进持久化待发队列，成功后才移除。
     * 这样任何时刻被强杀（包括传输中途），重启后连接上对端都会由握手自动补发，
     * 并凭同一个 mid 命中对端半成品注册表从断点续传 —— 不再依赖「失败 catch 补登记」。
     */
internal fun Link.doSendFile(
        uri: Uri,
        item: MsgItem,
        relPath: String = "",
        dirId: String = "",
        agg: DirProgress? = null,
        throwOnFail: Boolean = false
    ) {
        // 只有「普通单文件」由这里管待发队列；文件夹整体由 doSendFolder 管，成员不管
        if (dirId.isEmpty() && agg == null) {
            // 重复投递闸门（2026-10-01，判据见 [isFileDelivered]）：这条已经完整送达过，
            // 就**连队都不排、流量确认都不弹**。必须在这里判而不是只判 [inFlight] ——
            // 待发补发与 hsync 补齐都往同一条串行队列塞任务，前一个跑完 [inFlight] 已清空，
            // 后一个照样放行 = 同一份文件发两遍。
            // 文件夹成员（dirId/agg 非空）不受这里管：成员每次重发都用新 mid，
            // 去重由目录侧 dirSendState / 对端 DirRx 记账负责。
            if (isFileDelivered(item.mid, item.peerIp)) {
                log("文件 ${item.fileName} 本机已完整送达该设备，跳过重复发送")
                store?.removePending(item.mid)
                okUpdate(item.copy(progress = 100, status = Status.OK))
                return
            }
            queuePending(item)
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
            // 速率采样器统一收尾（2026-09-29）：一处覆盖 取消/失败/成功（含文件夹成员），
            // 成功路径里那次 endSpeed 只是提前收尾，这里重入幂等。
            endSpeed(item.mid)
        }
    }

    /**
     * 发送停滞看门狗：在途 mid 30s 没写出新字节（对端半死、socket 写阻塞）时，
     * 关掉对应会话强制解阻塞 → 发送循环抛异常 → 现有 catch → 进待发队列 → 重连后自动续传。
     * 由心跳线程每 [Link.STALL_SCAN_MS] 顺带调用（不新开线程）。
     *
     * 注：本地磁盘读停滞 30s+ 会被误判关会话 —— 概率极低，后果仅是重传（正确性无损）。
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
        // 目的会话按条目自带的 peerIp 解析 —— 会话中途切换对话设备也不会发错人
        val s = sessionForPending(item.peerIp)
        if (s == null) {
            if (throwOnFail) throw java.io.IOException("未连接")
            setFileVote(Link.VOTE_NO_NETWORK, true)   // 2.12 / D16：这就是"原本没网络"那一票
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
            // ① 断点续传协商：问对端「这条收到哪了」；普通单文件顺带就是重发前的送达询问
            var offset = 0L
            if (item.fileSize > 0) {
                offset = negotiateResume(s, item.mid, item.fileName, item.fileSize)
                if (offset < 0L) {
                    // 2.12 / D16：这一趟的续传请求**没发出去或 3s 没应答** ⇒ 保持暂停，
                    // 绝不退化成从头重发（用户："对方收到了就继续传输，没收到就暂停，手动点继续才重试"）
                    pauseInFlight(item, dirId, agg)
                    return
                }
                // 对端报的偏移 = 文件全长 → 它已经有完整一份，那就是送达确认：一个字节都不必再推，
                // 出队 + 记台账直接收尾（2026-10-01 方案 2）。专门兜「ack 丢帧 / 对端回 ack 前掉线，
                // 但它其实已经存好」这种假失败 —— 补发前问一句就收敛，既不重复推字节也不永远卡在队列。
                // 文件夹成员不判：成员每次重发都用新 mid，且提前 return 会打乱目录侧 dirSendState 记账。
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
            // 整文件 SHA-256 只服务「续传后复核」（设计口径）：一次性传完不再对每个字节做摘要。
            // offset 只在续传协商命中时才 > 0，所以这里按它决定要不要开摘要器。
            val digest = if (offset > 0) MessageDigest.getInstance("SHA-256") else null
            var ins: InputStream? = openSource(uri)
            // ⚠ 本地源打不开（SAF / 第三方 FileProvider 授权失效、文件已被删）**不是**网络故障：
            //   必须抛 SourceError。抛 IOException 会被下面的通用 catch 当成链路故障 → 关掉整条会话
            //   → 双方保活重连 → 握手补发同一条 → 又打不开 → 再关 …… 「连上就断」死循环。
            //   2026-09-21 真机实测：批量发几十个文件（MT 管理器 content://）时只要有一个 URI 失效，
            //   整批传输都会陷入 5s 一断的重连风暴，在途文件被反复打断。
            if (ins == null) throw SourceError("打不开源文件: $uri")
            if (offset > 0) {
                // ② 前缀摘要（顺带把流推进到 offset，之后直接续着读，不用 reopen）
                if (!digestPrefix(ins, offset, digest!!)) {
                    offset = 0
                    digest.reset()
                    log("前缀读取失败，改从头发送 ${item.fileName}")
                    runCatching { ins.close() }
                    ins = openSource(uri)
                    if (ins == null) throw SourceError("打不开源文件: $uri")
                }
            }
            ins.use { stream ->
                // ③ 双 4MB 缓冲交替读源（后台线程读盘，与网络发送重叠），1MB 二进制分片发出
                s.send(
                    Protocol.fileMeta(item.mid, item.fileName, item.fileSize, mime, "", offset, relPath, dirId)
                )
                val reader = BlockReader(stream, Protocol.RX_BUF_SIZE)
                try {
                    var seq = (offset / Protocol.CHUNK_SIZE).toInt()
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
                        // 读源中途失败（磁盘错误 / 文件被截断 / 授权中途失效）同样是**本地**问题：
                        // 抛 SourceError，绝不能被当成网络故障去关会话（原因同上面 openSource 那处）
                        if (n == -2 || buf == null) throw SourceError("读取源文件失败")
                        var off = 0
                        while (off < n) {
                            if (isCancelled(item.mid, dirId)) {
                                cancelled.remove(item.mid)
                                log("文件发送已取消 ${item.fileName}（已发 $sent B）")
                                return
                            }
                            // 2.12 / D16：在传的遇到投票 = 1 ⇒ 在**下一个分片边界**就地结束本次尝试
                            //（不劈开已入队的字节、不进 cancelled，条目回待发台账等拦截解除自动续发一次）
                            if (fileGateBlocked()) {
                                pauseInFlight(item, dirId, agg)
                                return
                            }
                            val len = minOf(Protocol.CHUNK_SIZE, n - off)
                            s.sendChunk(item.mid, seq, buf, off, len)
                            digest?.update(buf, off, len)
                            seq++
                            off += len
                            sent += len
                            // 发送停滞看门狗（v2.8）：每写出一块刷新「最近进度时刻」
                            sendLastAt[item.mid] = System.currentTimeMillis()
                            // 速率采样（字节级，2026-09-22）：进度是整数百分比，大文件太粗 → 气泡速率靠这里
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
                        // 这块发完了：**必须归还**给 free 队列，读线程才能继续填下一块。
                        // 只有 2 块缓冲，不还的话发完 2×4MB 后读线程永远阻塞在 free.take()，
                        // 消费端卡死在 ready.take() —— 大文件必然卡住（2026-09-13 实测踩坑）。
                        reader.recycle(buf)
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
            // 没开摘要（非续传）时发空串：接收端见空串即「对端未要求校验」，不做比对
            val sha = digest?.let { hex(it.digest()) } ?: ""
            s.send(Protocol.fileDone(item.mid, sha))
            if (agg != null) {
                // 文件夹成员：进度已由聚合器反映，气泡状态在整目录收尾时更新
            } else {
                // 传完即丢掉速率采样器：气泡那边靠「样本过期」自动隐藏数字，这里只是别留垃圾
                endSpeed(item.mid)
                okUpdate(item.copy(progress = 100, status = Status.OK))
            }
            if (dirId.isNotEmpty()) {
                dirSendState.getOrPut(dirId) { ConcurrentHashMap() }[relPath] = true
            }
            log("文件发送完成 ${item.fileName} ${TextUtil.humanSize(item.fileSize)}，等对端回报落盘")
            if (dirId.isEmpty() && agg == null) {
                if (rejectedAck.remove(item.mid)) {
                    // 对端在这趟开传前就回绝了（它那边存不下）：字节白推了，也不会再有第二份 ack ——
                    // 气泡如实标失败，条目留在待发队列，下次握手重来（见 [onSendAck]）
                    okUpdate(item.copy(progress = 100, status = Status.FAILED))
                    log("文件 ${item.fileName} 对端拒收，留在待发队列等补发")
                } else {
                    // **不在此处出待发队列、也不记送达台账**（2026-10-01 方案 2）：
                    // 挂进 [awaitingAck]，等对端的 `ack` 帧来说「我存好了」才算送达（[onSendAck]）。
                    // 条目留在队列里的这段时间就是兜底 —— 中途被杀、ack 丢帧，下次握手必会补发。
                    awaitingAck[item.mid] = AckWait(item, s.remoteIp)
                }
                // 触发过续传 → 等对端回发 MD5 做端到端核对（异步，不阻塞发送线程）
                if (offset > 0) {
                    startVerifyWatch(item, offset)
                }
            }
        } catch (t: Throwable) {
            cancelled.remove(item.mid)
            // 没走到「等 ack」就别留在待确认表里（条目按各自分支回待发/出队）
            awaitingAck.remove(item.mid)
            rejectedAck.remove(item.mid)
            if (t is SourceError) {
                // **本地**读不了源文件（被删 / 无权限 / SAF 授权失效）：链路本身是好的，
                // 因此绝不关连接（老逻辑在这里误杀连接，把后续所有传输一起带走），
                // 也不重试（重试同样是失败，只会无限循环）。
                log("文件发送失败（本地源不可读）${item.fileName}：${t.message}")
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
     *
     * 与网络故障**严格区分**：本地问题不该关连接、不该让整个文件夹停摆、也不该无限重试。
     */
    class SourceError(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

internal fun Link.openSource(uri: Uri): InputStream? = FileIO.openSource(ctx, uri)

    /**
     * 发送端读源：双 4MB 缓冲交替，后台线程读盘，与网络发送重叠。
     * 空闲缓冲从 [free] 领取、填满放回 [ready]；消费端发完归还。
     * len=-1 表示 EOF，len=-2 表示读源失败（error）。
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
