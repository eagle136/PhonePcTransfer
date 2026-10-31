package com.phonepctransfer.data

import android.content.Context
import android.util.Log
import com.phonepctransfer.util.FileIO
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 消息历史 + 待发队列的本地持久化（JSONL 追加写）。
 *  - `history/<对端IP>.jsonl` 每个设备一个文件，最近 500 条 —— 聊天记录按设备隔离，
 *    A→B 的内容不会出现在 C 的记录里（与 PC 端同构）
 *  - `pending.jsonl` 未送达的主动发送项（每条自带 peer），重连或手动同步时**按设备**补发
 */
class Store(ctx: Context) {

    /** 清理半成品文件时要走 ContentResolver，留着应用上下文 */
    private val appCtx = ctx.applicationContext

    /** 历史目录：按对端 IP 分文件 */
    private val histDir = File(ctx.filesDir, "history")

    private val pendingFile = File(ctx.filesDir, "pending.jsonl")
    private val lock = Any()

    init {
        runCatching { histDir.mkdirs() }
        migrateLegacyHistory(ctx)
    }

    /**
     * 老版本把所有设备的历史混写在 `filesDir/history.jsonl` 里，无法反推每条属于谁。
     * 不猜归属，直接改名归档（`history.legacy-<时间戳>.jsonl`），避免它污染按设备分文件的读取。
     */
    private fun migrateLegacyHistory(ctx: Context) {
        runCatching {
            val old = File(ctx.filesDir, "history.jsonl")
            if (old.exists() && old.length() > 0) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val dest = File(ctx.filesDir, "history.legacy-$stamp.jsonl")
                if (old.renameTo(dest)) {
                    Log.i(TAG, "旧版合并历史已归档为 ${dest.name}")
                }
            }
        }
    }

    // ==================== 历史（按对端设备分文件） ====================

    /** 文件名安全化：IP 里的 `.` 留着无妨，只清掉路径分隔符与非法字符 */
    private fun safeKey(peerIp: String): String {
        val v = peerIp.trim().replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        return if (v.isEmpty()) "_unknown" else v
    }

    /**
     * 「消息归属地址 → 会话组键」解析器，由 AppCore 注入（= Prefs.convKeyOf）。
     *
     * 为什么要解耦成回调：历史文件必须与**设备**对齐而不是与 IP 对齐 ——
     * 同一台设备换网卡/换 WiFi 后 IP 变了，聊天记录不该跟着断成两份
     * （2026-09-16 用户定调：多 IP 同设备自动成组）。
     * Store 本身不认识 Prefs，所以由上层注入；未注入时按 IP 处理（= 改造前行为）。
     */
    @Volatile
    var keyResolver: ((String) -> String)? = null

    /** 已尝试过归并的地址（失败也不反复重试，避免每次读写历史都做一次无用的文件操作） */
    private val mergeTried = HashSet<String>()

    /** 会话组键（对端 deviceId；未知设备回退该 IP） */
    private fun keyOf(peerIp: String): String {
        val v = peerIp.trim()
        if (v.isEmpty()) return ""
        val k = runCatching { keyResolver?.invoke(v) }.getOrNull()
        return if (k.isNullOrEmpty()) v else k
    }

    private fun historyFile(peerIp: String): File {
        val ip = peerIp.trim()
        val k = keyOf(ip)
        val f = File(histDir, safeKey(if (k.isEmpty()) ip else k) + ".jsonl")
        // 组键 ≠ IP 说明这台设备的身份已经确认 → 把旧版按 IP 存的历史并进组文件。
        // lazy 做（第一次真正读写这个设备时才动），失败保留旧文件、下次仍可读。
        if (k.isNotEmpty() && k != ip) mergeLegacyHistory(ip, f)
        return f
    }

    /**
     * 把 `history/<ip>.jsonl` 并入 `history/<设备id>.jsonl`（一次性）。
     *
     * 合并策略：整读整写（两个文件都只有最近若干行，成本可忽略）。
     * 只有写成功才删旧文件 —— 写失败时旧文件留着，[loadHistory] 仍能读到内容，
     * 不会出现「记录凭空消失」。
     */
    private fun mergeLegacyHistory(ip: String, dest: File) {
        if (ip.isEmpty()) return
        val legacy = File(histDir, safeKey(ip) + ".jsonl")
        if (!legacy.exists() || legacy.absolutePath == dest.absolutePath) return
        if (legacy.length() <= 0L) {
            legacy.delete()
            return
        }
        if (!mergeTried.add(ip)) return
        runCatching {
            val old = legacy.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
            if (old.isEmpty()) {
                legacy.delete()
                return@runCatching
            }
            val cur = if (dest.exists()) dest.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
            else emptyList()
            dest.writeText(
                (cur + old).joinToString("") { it + "\n" },
                StandardCharsets.UTF_8
            )
            if (legacy.delete()) {
                Log.i(TAG, "历史归并到设备文件：$ip → ${dest.name}（${old.size} 行）")
            }
        }.onFailure { Log.w(TAG, "历史归并失败 $ip: ${it.message}") }
    }

    /** 追加一条历史到「该消息所属设备」的文件里 */
    fun appendHistory(m: MsgItem) {
        synchronized(lock) {
            runCatching {
                runCatching { histDir.mkdirs() }
                historyFile(m.peerIp).appendText(m.toJson().toString() + "\n", StandardCharsets.UTF_8)
            }.onFailure { Log.w(TAG, "写历史失败: ${it.message}") }
        }
    }

    /**
     * 读取**指定设备**最近 max 条历史。
     *
     * **按 mid 合并去重（后行胜出）**：同一条消息可能因「file_meta 时落一次、
     * file_done 终态再补一次」在文件里有多行 —— 加载时合成一条，空字段用前行补齐。
     * 这同时治愈早期版本留下的重复行（重复 mid 进 LazyColumn 会直接崩）。
     */
    fun loadHistory(peerIp: String, max: Int = 500): List<MsgItem> {
        synchronized(lock) {
            val f = historyFile(peerIp)
            if (!f.exists()) return emptyList()
            val rows = ArrayList<MsgItem>()
            runCatching {
                val lines = f.readLines(StandardCharsets.UTF_8)
                val from = (lines.size - max).coerceAtLeast(0)
                for (i in from until lines.size) {
                    val ln = lines[i].trim()
                    if (ln.isEmpty()) continue
                    runCatching { rows.add(MsgItem.fromJson(JSONObject(ln))) }
                }
            }.onFailure { Log.w(TAG, "读历史失败: ${it.message}") }
            val byMid = LinkedHashMap<String, MsgItem>()
            for (m in rows) {
                if (m.mid.isEmpty()) { byMid["__raw_${byMid.size}"] = m; continue }
                val prev = byMid[m.mid]
                byMid[m.mid] = if (prev == null) m else mergeLater(prev, m)
            }
            return ArrayList(byMid.values)
        }
    }

    /**
     * 统一消息 id 查重（2026-09-16）：该设备的历史里是否已有这条 mid。
     * hsync 补齐与重复投递的判定**只认 mid**，与气泡、待发队列共用同一判据。
     */
    fun hasMid(peerIp: String, mid: String): Boolean {
        if (mid.isEmpty()) return false
        synchronized(lock) {
            val f = historyFile(peerIp)
            if (!f.exists()) return false
            return runCatching {
                f.useLines { lines ->
                    lines.any { ln ->
                        ln.contains("\"mid\":\"$mid\"") &&
                            runCatching { JSONObject(ln).optString("mid") == mid }
                                .getOrDefault(false)
                    }
                }
            }.getOrDefault(false)
        }
    }

    /** 历史合并：后行覆盖前行可变字段，前行补齐后行的空字段（同 AppCore.merge 语义） */
    private fun mergeLater(old: MsgItem, new: MsgItem): MsgItem = old.copy(
        text = new.text.ifEmpty { old.text },
        fileName = new.fileName.ifEmpty { old.fileName },
        fileSize = if (new.fileSize != 0L) new.fileSize else old.fileSize,
        filePath = new.filePath.ifEmpty { old.filePath },
        localUri = new.localUri.ifEmpty { old.localUri },
        status = new.status,
        // 未在传的状态（DENIED/FAILED/OK）一律按后行取值，别把前一行的进度条带回来
        progress = if (new.progress >= 0 || new.status != Status.OK) new.progress else old.progress,
        ts = if (new.ts != 0L) new.ts else old.ts,
        isDir = new.isDir || old.isDir,
        dirCount = if (new.dirCount != 0) new.dirCount else old.dirCount,
        peerIp = new.peerIp.ifEmpty { old.peerIp }
    )

    /** 清空**全部**设备的历史文件 */
    fun clearHistory() {
        synchronized(lock) {
            runCatching {
                histDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".jsonl")) f.delete()
                }
            }
        }
    }

    /** 只清空**某一台设备**的历史文件 */
    fun clearHistory(peerIp: String) {
        synchronized(lock) { runCatching { historyFile(peerIp).delete() } }
    }

    /**
     * 历史文件的组键清单（文件名去掉 `.jsonl`）。
     *
     * 「清空全部」用它兜住**不在设备名单里的孤立历史**（设备被删过、或换过 deviceId）——
     * 那些记录没有任何候选地址能反查出来，只能按文件键去清。
     */
    fun historyKeys(): List<String> =
        synchronized(lock) {
            runCatching {
                histDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".jsonl") }
                    ?.map { it.name.removeSuffix(".jsonl") }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

    /**
     * 按 mid 批量删除**某一台设备**的历史（「清空会话」逐条删除用）：**一趟读、一趟写**。
     *
     * 为什么不循环调 [removeFromHistory]：那样每条都要把整个历史文件读一遍写一遍
     * （N 条就是 N 次全文件重写，条数一多就是 O(N²) 的磁盘往返）。
     * 这里只动**这台设备自己的文件**（按组键解析 → 同一台设备其它地址的往来也在同一份里），
     * 不会波及别的设备。
     */
    fun removeFromHistory(peerIp: String, mids: Collection<String>) {
        if (mids.isEmpty()) return
        synchronized(lock) {
            val f = historyFile(peerIp)
            if (!f.exists()) return
            val set = mids.toHashSet()
            runCatching {
                val kept = f.readLines(StandardCharsets.UTF_8).filter { ln ->
                    val t = ln.trim()
                    if (t.isEmpty()) {
                        false
                    } else {
                        val mid = runCatching { JSONObject(t).optString("mid") }.getOrDefault("")
                        // 坏行（解不出 mid）保留 —— 与单条版口径一致，宁可留脏行也不吞记录
                        mid.isEmpty() || mid !in set
                    }
                }
                f.writeText(
                    if (kept.isEmpty()) "" else kept.joinToString("") { it + "\n" },
                    StandardCharsets.UTF_8
                )
            }.onFailure { Log.w(TAG, "批量删历史失败: ${it.message}") }
        }
    }

    /**
     * 按 mid 删除一条历史，**在所有设备的历史文件里找**。
     *
     * 取消文件的通知里只带 mid（协议里也不需要带归属），而历史是按设备分文件的，
     * 所以直接扫一遍目录最省事：mid 是全局唯一随机串，命中即该条。
     */
    fun removeFromHistory(mid: String) {
        if (mid.isEmpty()) return
        synchronized(lock) {
            runCatching {
                histDir.listFiles()?.forEach { f ->
                    if (!f.isFile || !f.name.endsWith(".jsonl")) return@forEach
                    val lines = f.readLines(StandardCharsets.UTF_8)
                    var hit = false
                    val kept = lines.filter { ln ->
                        val t = ln.trim()
                        if (t.isEmpty()) false
                        else {
                            val same = runCatching { JSONObject(t).optString("mid") == mid }
                                .getOrDefault(false)
                            if (same) hit = true
                            !same
                        }
                    }
                    if (hit) {
                        f.writeText(
                            if (kept.isEmpty()) "" else kept.joinToString("") { it + "\n" },
                            StandardCharsets.UTF_8
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "删历史失败: ${it.message}") }
        }
    }

    // ==================== 待发队列 ====================

    /**
     * 待发队列**变化通知**（AppCore 侧据此重算 `pendingCount`：聊天区底部
     * 「待发送 N 条，连接后自动补发」横幅 + 前台通知副标题都读它）。
     *
     * 为什么必须有这条通道（2026-10-05 修，用户报「对方上线自动续传后界面不刷新，
     * 切一下会话才变」）：待发条目的增删有一半发生在**链路层**，那条路径上没有任何
     * `Listener.onMessageUpdated` ——
     *  - 补发/续传跑完后，条目是由对端的 `ack` 帧驱动才出队的（`LinkSend.onSendAck`
     *    → `store.removePending`），而出队那一刻界面收不到任何消息级回调；
     *  - 同理 `flushPendingNow` 里「已送达 / 对端不在线」两种直接出队。
     * AppCore 原先只在「消息新增 / 状态更新 / 消息移除」时重算 pendingCount，而
     * **文件发送的终态帧（OK）比出队更早**，那一刻条目还在队列里 → 数字停在旧值上
     * 再也回不去，只有换一次会话（[AppCore.reloadConversation] 会重算）才消失。
     *
     * 触发点只有 [writePending] 一处 —— 四条增删路径（addPending /
     * removePending(mid) / removePending(mids) / clearPending）全部汇聚到它，
     * 不漏也不重复。回调可能在任意线程（Store 的调用方包括 pp-send / pp-file-send），
     * **回调里必须自己切线程**（AppCore 用 `ui{}`）。
     */
    @Volatile
    var onPendingChanged: (() -> Unit)? = null

    fun addPending(m: MsgItem) {
        synchronized(lock) {
            val cur = loadPendingInternal()
            if (cur.any { it.mid == m.mid }) return
            cur.add(m.copy(status = Status.PENDING))
            writePending(cur)
        }
    }

    fun removePending(mid: String) {
        synchronized(lock) {
            val cur = loadPendingInternal()
            if (cur.removeAll { it.mid == mid }) writePending(cur)
        }
    }

    /**
     * 按 mid 批量删待发（「清空会话」用）：**一趟读、一趟写**。
     * 只删命中项 —— 别的设备没发出去的条目一条都不能少。
     */
    fun removePending(mids: Collection<String>) {
        if (mids.isEmpty()) return
        synchronized(lock) {
            val set = mids.toHashSet()
            val cur = loadPendingInternal()
            val kept = cur.filter { it.mid !in set }
            if (kept.size != cur.size) writePending(kept)
        }
    }

    fun loadPending(): List<MsgItem> = synchronized(lock) { loadPendingInternal() }

    fun clearPending() = synchronized(lock) { writePending(emptyList()) }

    private fun loadPendingInternal(): MutableList<MsgItem> {
        if (!pendingFile.exists()) return mutableListOf()
        val out = mutableListOf<MsgItem>()
        runCatching {
            pendingFile.readLines(StandardCharsets.UTF_8).forEach { ln ->
                val t = ln.trim()
                if (t.isNotEmpty()) runCatching { out.add(MsgItem.fromJson(JSONObject(t))) }
            }
        }.onFailure { Log.w(TAG, "读待发队列失败: ${it.message}") }
        return out
    }

    private fun writePending(list: List<MsgItem>) {
        runCatching {
            pendingFile.writeText(
                list.joinToString("") { it.toJson().toString() + "\n" },
                StandardCharsets.UTF_8
            )
        }.onFailure { Log.w(TAG, "写待发队列失败: ${it.message}") }
        // 待发队列变了 → 通知界面重算条数（见 [onPendingChanged]）。
        // 放在 runCatching 之外：盘写失败也要通知（内存语义已变，界面按真实读盘结果重算）。
        // ⚠ 调用方可能正持有 [lock]（addPending/removePending 都在 synchronized 块内）：
        //   AppCore 的回调只是 `ui{...}`（主线程时同步执行 = 同线程可重入锁，不会死锁）。
        runCatching { onPendingChanged?.invoke() }
    }

    // ==================== 断点续传：半成品注册表 ====================

    /**
     * 正在接收/未接收完的文件登记（mid → uri/name/size/received），持久化到 partials.json。
     * 发送端断线重连后发 file_query，接收端凭它找到半成品继续追加，而不是推倒重来。
     */
    fun putPartial(mid: String, uri: String, name: String, size: Long, received: Long) {
        if (mid.isEmpty() || uri.isEmpty()) return
        synchronized(lock) {
            val cur = loadPartials()
            cur[mid] = JSONObject().put("uri", uri).put("name", name)
                .put("size", size).put("received", received)
                .put("ts", System.currentTimeMillis())
            writePartials(cur)
        }
    }

    fun getPartial(mid: String): JSONObject? = synchronized(lock) { loadPartials()[mid] }

    fun removePartial(mid: String) {
        if (mid.isEmpty()) return
        synchronized(lock) {
            val cur = loadPartials()
            if (cur.remove(mid) != null) writePartials(cur)
        }
    }

    /**
     * 清理孤儿半成品：发送端取消时取消帧丢失、或接收端重启后 mid 对不上，
     * 这类条目永远不会被 file_query 命中。启动时清掉「超过 maxAgeMs 未更新」的条目
     * （连同半成品文件一起删）；无 ts 的旧版条目一并视为过期。
     */
    fun purgeStalePartials(maxAgeMs: Long) {
        // 会话中也会调用（断链/发送队列空闲，plan 07）：与 putPartial/removePartial 抢同一份
        // partials.json，必须同锁，否则 purge 的整表回写会丢掉并发写入的新条目
        synchronized(lock) {
            runCatching {
                val cur = loadPartials()
                if (cur.isEmpty()) return
                val now = System.currentTimeMillis()
                val it = cur.entries.iterator()
                var n = 0
                while (it.hasNext()) {
                    val e = it.next().value
                    val uri = e.optString("uri")
                    val ts = e.optLong("ts", 0L)
                    val stale = ts <= 0L || now - ts > maxAgeMs
                    if (!stale) continue
                    if (uri.isNotEmpty()) FileIO.deletePartial(appCtx, uri)
                    it.remove()
                    n++
                }
                if (n > 0) {
                    writePartials(cur)
                    Log.i(TAG, "清理过期半成品 $n 项（含文件）")
                }
            }.onFailure { Log.w(TAG, "清理半成品注册表失败: ${it.message}") }
        }
    }

    private val partialsFile get() = File(pendingFile.parentFile, "partials.json")

    private fun loadPartials(): MutableMap<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        runCatching {
            if (partialsFile.exists()) {
                val root = JSONObject(partialsFile.readText(StandardCharsets.UTF_8))
                for (k in root.keys()) out[k] = root.getJSONObject(k)
            }
        }.onFailure { Log.w(TAG, "读半成品注册表失败: ${it.message}") }
        return out
    }

    private fun writePartials(map: Map<String, JSONObject>) {
        runCatching {
            val root = JSONObject()
            for ((k, v) in map) root.put(k, v)
            partialsFile.writeText(root.toString(), StandardCharsets.UTF_8)
        }.onFailure { Log.w(TAG, "写半成品注册表失败: ${it.message}") }
    }

    // ==================== 送达台账（同 mid 不重发，要扛进程重启） ====================

    /**
     * 「这个 mid 已完整送达那台设备」的持久化台账，键 = `mid@设备组键`。
     *
     * 为什么必须落盘：只放内存的话，手机重启 / 进程被速冻杀掉就归零，下一次握手
     * 待发队列和 hsync 补齐又会把同一个 mid 推一遍 —— 对端只能再存一份 `(1)`。
     * 内存镜像 [delivered] 是为读写不碰磁盘，文件只是它的备份。
     */
    private val delivered = LinkedHashMap<String, Long>()
    private var deliveredLoaded = false

    private val deliveredFile get() = File(pendingFile.parentFile, "delivered.json")

    /** 记一条送达；已记过就不回写整表 */
    fun markDelivered(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            ensureDeliveredLoaded()
            if (delivered.containsKey(key)) return
            delivered[key] = System.currentTimeMillis()
            while (delivered.size > DELIVERED_MAX) {
                var oldestKey = ""
                var oldestTs = Long.MAX_VALUE
                for ((k, ts) in delivered) {
                    if (ts < oldestTs) {
                        oldestKey = k
                        oldestTs = ts
                    }
                }
                if (oldestKey.isEmpty()) break
                delivered.remove(oldestKey)
            }
            writeDelivered()
        }
    }

    fun isDelivered(key: String): Boolean {
        if (key.isEmpty()) return false
        synchronized(lock) {
            ensureDeliveredLoaded()
            return delivered.containsKey(key)
        }
    }

    private fun ensureDeliveredLoaded() {
        if (deliveredLoaded) return
        deliveredLoaded = true
        runCatching {
            if (!deliveredFile.exists()) return
            val root = JSONObject(deliveredFile.readText(StandardCharsets.UTF_8))
            for (k in root.keys()) delivered[k] = root.optLong(k, 0L)
        }.onFailure { Log.w(TAG, "读送达台账失败: ${it.message}") }
    }

    private fun writeDelivered() {
        runCatching {
            val root = JSONObject()
            for ((k, ts) in delivered) root.put(k, ts)
            deliveredFile.writeText(root.toString(), StandardCharsets.UTF_8)
        }.onFailure { Log.w(TAG, "写送达台账失败: ${it.message}") }
    }

    // ==================== 接收侧查重 ====================

    /**
     * 本端是否已把这个 mid 存成过一条完整的收讫文件（与 PC 端 `savedFileOf` 同口径）。
     *
     * 只认「对端发来 + FILE + 非目录 + 状态 OK」，且留着 filePath 或 localUri。
     * **文件还在不在、字节数对不对由调用方量** —— Store 不判断磁盘状态，
     * 这样用户删掉收到的文件后同 mid 仍能正常重收。
     */
    fun savedFileOf(peerIp: String, mid: String): MsgItem? {
        if (mid.isEmpty()) return null
        synchronized(lock) {
            val f = historyFile(peerIp)
            if (!f.exists()) return null
            var hit: MsgItem? = null
            runCatching {
                f.useLines { lines ->
                    for (ln in lines) {
                        val t = ln.trim()
                        if (t.isEmpty() || !t.contains("\"mid\":\"$mid\"")) continue
                        val m = runCatching { MsgItem.fromJson(JSONObject(t)) }.getOrNull() ?: continue
                        if (m.mid != mid || m.kind != Kind.FILE || m.isDir) continue
                        if (m.dir != Dir.PEER || m.status != Status.OK) continue
                        if (m.filePath.isEmpty() && m.localUri.isEmpty()) continue
                        hit = m
                    }
                }
            }.onFailure { Log.w(TAG, "查已存文件失败: ${it.message}") }
            return hit
        }
    }

    private companion object {
        const val TAG = "PP-Store"

        /** 台账条数上限：按最旧送达时间淘汰，够覆盖几千次传输的重复投递窗口 */
        const val DELIVERED_MAX = 512
    }
}
