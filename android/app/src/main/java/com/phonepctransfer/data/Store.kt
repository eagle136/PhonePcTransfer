package com.phonepctransfer.data

import android.content.Context
import android.util.Log
import com.phonepctransfer.util.FileIO
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** 消息历史 + 待发队列本地持久化（JSONL 追加写）：历史按设备分文件（最近 500 条），pending.jsonl 重连/手动同步时按设备补发 */
class Store(ctx: Context) {

    /** 清理半成品文件时要走 ContentResolver，留着应用上下文 */
    private val appCtx = ctx.applicationContext

    /** 历史目录：按对端 IP 分文件 */
    private val histDir = File(ctx.filesDir, "history")

    private val pendingFile = File(ctx.filesDir, "pending.jsonl")

    /** 待发队列内存镜像（P3-2）：addPending 只追加一行，去重不靠每次读盘；写全量时同步更新 */
    private var pendingCache: MutableList<MsgItem>? = null

    private val lock = Any()

    init {
        runCatching { histDir.mkdirs() }
        migrateLegacyHistory(ctx)
    }

    /** 旧版混合历史无法反推归属：改名归档，不污染按设备分文件的读取 */
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

    /** 「消息归属地址 → 会话组键」解析器，由 AppCore 注入（历史跟设备走不跟 IP 走；未注入时回退按 IP = 改造前行为） */
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
        // 组键 ≠ IP 说明身份已确认 → 把旧版按 IP 存的历史 lazy 并进组文件（失败保留旧文件，下次仍可读）
        if (k.isNotEmpty() && k != ip) mergeLegacyHistory(ip, f)
        return f
    }

    /** 把 `history/<ip>.jsonl` 并入 `history/<设备id>.jsonl`（一次性；整读整写，只有写成功才删旧文件） */
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
                histMids.clear()
            }
        }.onFailure { Log.w(TAG, "历史归并失败 $ip: ${it.message}") }
    }

    /** 每条历史的 mid 清单（文件路径 → 文件内出现顺序），给「满 500 就删最旧 50」的触发器做计数；结构性变更（删除/清空/归并）时整体清掉重新播种 */
    private val histMids = HashMap<String, LinkedHashSet<String>>()

    /**
     * 追加一条历史到「该消息所属设备」的文件里，**写完立刻触发一次条数检测**：
     * 该会话超过 [HIST_KEEP] 条（500）就删掉按 ts 最旧的 [HIST_DROP] 条（50），再原子重写文件。
     * 计数用内存 mid 清单（首次按文件播种），不必每条都整读文件；只有真正超限时才会读盘+重写。
     */
    fun appendHistory(m: MsgItem) {
        synchronized(lock) {
            runCatching {
                runCatching { histDir.mkdirs() }
                val f = historyFile(m.peerIp)
                f.appendText(m.toJson().toString() + "\n", StandardCharsets.UTF_8)
                trimIfNeeded(f, m.mid)
            }.onFailure { Log.w(TAG, "写历史失败: ${it.message}") }
        }
    }

    /** 触发器：该会话条数 > [HIST_KEEP] → 删最旧 [HIST_DROP] 条（按 ts，同一条 mid 只算一次） */
    private fun trimIfNeeded(f: File, newMid: String) {
        val set = histMids[f.absolutePath] ?: LinkedHashSet<String>().also {
            midsInOrder(f).forEach { mid -> it.add(mid) }
            histMids[f.absolutePath] = it
        }
        if (newMid.isNotEmpty()) set.add(newMid)
        if (set.size <= HIST_KEEP) return
        val rows = readHistoryRows(f)
        // 最旧按 ts 判（hsync 补发的旧消息追加在文件尾部，行序不能当时间序）
        val tsByMid = HashMap<String, Long>()
        rows.forEach { if (it.mid.isNotEmpty()) tsByMid[it.mid] = it.ts }
        val victims = tsByMid.entries.sortedBy { it.value }.take(HIST_DROP).map { it.key }.toHashSet()
        if (victims.isEmpty()) return
        writeHistoryFiltered(f, victims)
        histMids[f.absolutePath] = midsInOrder(f).toCollection(LinkedHashSet())
        Log.i(TAG, "历史触发清理：${f.name} ${tsByMid.size} 条 → 删最旧 ${victims.size} 条")
    }

    private fun readHistoryRows(f: File): List<MsgItem> {
        val rows = ArrayList<MsgItem>()
        f.readLines(StandardCharsets.UTF_8).forEach { ln ->
            val t = ln.trim()
            if (t.isEmpty()) return@forEach
            runCatching { rows.add(MsgItem.fromJson(JSONObject(t))) }
        }
        return rows
    }

    /** 文件里出现过的 mid（按行序，保持首次出现位置） */
    private fun midsInOrder(f: File): List<String> {
        val out = ArrayList<String>()
        runCatching {
            f.readLines(StandardCharsets.UTF_8).forEach { ln ->
                val t = ln.trim()
                if (t.isEmpty()) return@forEach
                val mid = runCatching { JSONObject(t).optString("mid") }.getOrDefault("")
                if (mid.isNotEmpty()) out.add(mid)
            }
        }.onFailure { Log.w(TAG, "读历史 mid 失败: ${it.message}") }
        return out
    }

    /** 剔除指定 mid 的行后原子重写（无 mid 的旧版裸行保留，不误删） */
    private fun writeHistoryFiltered(f: File, victims: Set<String>) {
        val kept = StringBuilder()
        f.readLines(StandardCharsets.UTF_8).forEach { ln ->
            val t = ln.trim()
            if (t.isEmpty()) return@forEach
            val mid = runCatching { JSONObject(t).optString("mid") }.getOrDefault("")
            if (mid.isNotEmpty() && mid in victims) return@forEach
            kept.append(t).append('\n')
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(kept.toString(), StandardCharsets.UTF_8)
        if (tmp.renameTo(f)) {
            Log.i(TAG, "历史已清理重写：${f.name}")
        } else {
            tmp.delete()
            Log.w(TAG, "历史清理替换失败（保留原文件）: ${f.name}")
        }
    }

    /** 读取指定设备最近 max 条历史；先按 mid 去重（后行胜出）**再**按条数截断（P3-1：以前先按行数截断，状态帧会把真正的消息挤出窗口） */
    fun loadHistory(peerIp: String, max: Int = 500): List<MsgItem> {
        synchronized(lock) {
            val f = historyFile(peerIp)
            if (!f.exists()) return emptyList()
            val rows = ArrayList<MsgItem>()
            runCatching {
                val lines = f.readLines(StandardCharsets.UTF_8)
                for (ln in lines) {
                    val t = ln.trim()
                    if (t.isEmpty()) continue
                    runCatching { rows.add(MsgItem.fromJson(JSONObject(t))) }
                }
            }.onFailure { Log.w(TAG, "读历史失败: ${it.message}") }
            val byMid = LinkedHashMap<String, MsgItem>()
            for (m in rows) {
                if (m.mid.isEmpty()) { byMid["__raw_${byMid.size}"] = m; continue }
                val prev = byMid[m.mid]
                byMid[m.mid] = if (prev == null) m else mergeLater(prev, m)
            }
            val all = ArrayList(byMid.values)
            return if (all.size <= max) all else ArrayList(all.subList(all.size - max, all.size))
        }
    }

    /** 该设备历史里是否已有此 mid（hsync 补齐/重复投递判据，与气泡、待发队列共用） */
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
                histMids.clear()
            }
        }
    }

    /** 只清空**某一台设备**的历史文件 */
    fun clearHistory(peerIp: String) {
        synchronized(lock) {
            runCatching { historyFile(peerIp).delete() }
            histMids.clear()
        }
    }

    /** 历史文件的组键清单（文件名去 .jsonl）；「清空全部」用它兜住不在设备名单里的孤立历史 */
    fun historyKeys(): List<String> =
        synchronized(lock) {
            runCatching {
                histDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".jsonl") }
                    ?.map { it.name.removeSuffix(".jsonl") }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

    /** 按 mid 批量删某设备的历史（「清空会话」用）：一趟读、一趟写，只动该设备自己的文件 */
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
                histMids.remove(f.absolutePath)   // 触发器计数作废，下次写入重新播种
            }.onFailure { Log.w(TAG, "批量删历史失败: ${it.message}") }
        }
    }

    /** 按 mid 删一条（取消帧只带 mid）：直接扫全部设备历史文件，mid 全局唯一即命中 */
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
                        histMids.remove(f.absolutePath)
                    }
                }
            }.onFailure { Log.w(TAG, "删历史失败: ${it.message}") }
        }
    }

    // ==================== 待发队列 ====================

    /** 待发队列变化通知（AppCore 据此重算 pendingCount）；四条增删路径全部汇聚在 writePending，不漏不重复；回调可能在任意线程，执行方须自己切线程（ui{}） */
    @Volatile
    var onPendingChanged: (() -> Unit)? = null

    fun addPending(m: MsgItem) {
        synchronized(lock) {
            // P3-2：只追加一行（不再整读整写）。批量发送 N 个文件从 O(N²) 降到 O(N)，内存镜像保证去重不靠读盘
            val cur = cacheOrLoadPending()
            if (cur.any { it.mid == m.mid }) return
            val p = m.copy(status = Status.PENDING)
            cur.add(p)
            runCatching {
                pendingFile.appendText(p.toJson().toString() + "\n", StandardCharsets.UTF_8)
            }.onFailure { Log.w(TAG, "追加待发队列失败: ${it.message}") }
            runCatching { onPendingChanged?.invoke() }
        }
    }

    fun removePending(mid: String) {
        synchronized(lock) {
            val cur = cacheOrLoadPending()
            if (cur.removeAll { it.mid == mid }) writePending(cur)
        }
    }

    /** 按 mid 批量删待发（「清空会话」用）：一趟读、一趟写，只动命中项 */
    fun removePending(mids: Collection<String>) {
        if (mids.isEmpty()) return
        synchronized(lock) {
            val set = mids.toHashSet()
            val cur = cacheOrLoadPending()
            val kept = cur.filter { it.mid !in set }
            if (kept.size != cur.size) writePending(kept)
        }
    }

    /** 内存镜像优先（P3-2）：没有镜像才读盘并播种 */
    private fun cacheOrLoadPending(): MutableList<MsgItem> =
        pendingCache ?: loadPendingInternal().also { pendingCache = it }

    fun loadPending(): List<MsgItem> = synchronized(lock) { cacheOrLoadPending().toList() }

    fun clearPending() = synchronized(lock) { writePending(emptyList()) }

    /**
     * 清除软件数据（版本变化时用户选「是」）：历史、待发队列、半成品台账、删除墓碑、已送达记录。
     * **不动已接收的文件**：成品在「下载/手机电脑互传」(MediaStore)，回退落点在
     * getExternalFilesDir()/received，都不在这个 filesDir 下 —— 这里只删台账，绝不碰文件本身。
     */
    fun clearAllData() = synchronized(lock) {
        pendingCache = null
        runCatching { histDir.listFiles()?.forEach { it.delete() } }
        runCatching { pendingFile.delete() }
        runCatching { tombFile.delete() }
        runCatching { partialsFile.delete() }
        runCatching { deliveredFile.delete() }
        runCatching {
            appCtx.filesDir.listFiles()
                ?.filter { it.name.startsWith("history.legacy-") }
                ?.forEach { it.delete() }
        }
        Log.i(TAG, "已清除软件数据（历史/待发/半成品台账/墓碑/已送达），已接收的文件保留")
    }

    /** 是否已有任何旧数据（区分「首次安装」与「老版本升级」：老版本从没写过版本记录，升级同样要问） */
    fun hasAnyData(): Boolean = synchronized(lock) {
        (histDir.listFiles()?.any { it.length() > 0 } ?: false) ||
            pendingFile.length() > 0 ||
            tombFile.length() > 0 ||
            partialsFile.length() > 0 ||
            deliveredFile.length() > 0
    }

    private fun loadPendingInternal(): MutableList<MsgItem> {
        if (!pendingFile.exists()) return mutableListOf()
        val out = mutableListOf<MsgItem>()
        runCatching {
            pendingFile.readLines(StandardCharsets.UTF_8).forEach { ln ->
                val t = ln.trim()
                if (t.isNotEmpty()) runCatching { out.add(MsgItem.fromJson(JSONObject(t))) }
            }
        }.onFailure { Log.w(TAG, "读待发队列失败: ${it.message}") }
        // 追加写后同 mid 可能有多行（崩溃半途/旧版本遗留）：后行胜出，避免一个文件发两遍
        if (out.size <= 1) return out
        val byMid = LinkedHashMap<String, MsgItem>()
        for (m in out) byMid[m.mid] = m
        return byMid.values.toMutableList()
    }

    private fun writePending(list: List<MsgItem>) {
        pendingCache = list.toMutableList()
        runCatching {
            pendingFile.writeText(
                list.joinToString("") { it.toJson().toString() + "\n" },
                StandardCharsets.UTF_8
            )
        }.onFailure { Log.w(TAG, "写待发队列失败: ${it.message}") }
        // 队列变了 → 通知界面重算条数（盘写失败也要通知；调用方可能正持有 [lock]，回调只 ui{} 不递归，不死锁）
        runCatching { onPendingChanged?.invoke() }
    }

    // ==================== 删除墓碑（P2-7）====================

    /**
     * 本机删掉一条**参与 hsync 的消息**（文本/图片）时落一条墓碑，挡住「删了又被补回来」：
     * ① 对端离线时 msg_del 丢失 → 本机墓碑让 hsync 差集里永远不再请求这条；
     * ② 重连握手时把 `sent=false` 的墓碑补发一次 msg_del（对端收到即同步删除并也落墓碑）；
     * ③ 超过 [TOMB_TTL_MS]（30 天）自动清掉，文件不会无限长。
     * 一行一条：`{"peerIp":"<设备组键>","mid":"...","ts":123,"sent":false}`；同一 (设备,mid) 后行胜出。
     */
    private val tombFile = File(ctx.filesDir, "deleted.jsonl")

    private data class Tomb(val key: String, val mid: String, val ts: Long, val sent: Boolean)

    /** 墓碑追加一条（sent=true 表示已成功通知过对端，仅用于握手补发判定） */
    fun addTomb(peerIp: String, mid: String, sent: Boolean = false) {
        if (mid.isEmpty()) return
        val key = keyOf(peerIp)
        if (key.isEmpty()) return
        synchronized(lock) {
            runCatching {
                tombFile.appendText(
                    JSONObject().put("peerIp", key).put("mid", mid)
                        .put("ts", System.currentTimeMillis()).put("sent", sent)
                        .toString() + "\n",
                    StandardCharsets.UTF_8
                )
            }.onFailure { Log.w(TAG, "写删除墓碑失败: ${it.message}") }
        }
    }

    /** 该设备名下仍未同步出去（或已删但需要挡住 hsync）的墓碑：mid 集合 */
    fun tombMids(peerIp: String): Set<String> =
        readTombs().values.filter { it.key == keyOf(peerIp) && !isTombExpired(it) }
            .map { it.mid }.toHashSet()

    /** 还没通知到对端的墓碑 mid（握手时补发 msg_del 用） */
    fun pendingTombs(peerIp: String): List<String> {
        val k = keyOf(peerIp)
        return readTombs().values
            .filter { it.key == k && !it.sent && !isTombExpired(it) }
            .map { it.mid }
    }

    /** 标记这些 mid 已通知过对端（追加 sent=true 行；读时后行胜出） */
    fun markTombsSent(peerIp: String, mids: Collection<String>) {
        if (mids.isEmpty()) return
        val k = keyOf(peerIp)
        if (k.isEmpty()) return
        synchronized(lock) {
            runCatching {
                val now = System.currentTimeMillis()
                val sb = StringBuilder()
                mids.forEach { mid ->
                    sb.append(
                        JSONObject().put("peerIp", k).put("mid", mid).put("ts", now)
                            .put("sent", true).toString()
                    ).append('\n')
                }
                tombFile.appendText(sb.toString(), StandardCharsets.UTF_8)
            }.onFailure { Log.w(TAG, "标记墓碑已发送失败: ${it.message}") }
        }
    }

    /** 清掉过期墓碑（30 天）；连接建立时顺手调用一次 */
    fun pruneTombs() {
        synchronized(lock) {
            runCatching {
                if (!tombFile.exists()) return
                val alive = readTombs().values.filter { !isTombExpired(it) }
                tombFile.writeText(
                    alive.joinToString("") {
                        JSONObject().put("peerIp", it.key).put("mid", it.mid)
                            .put("ts", it.ts).put("sent", it.sent).toString() + "\n"
                    },
                    StandardCharsets.UTF_8
                )
            }.onFailure { Log.w(TAG, "清理墓碑失败: ${it.message}") }
        }
    }

    private fun isTombExpired(t: Tomb): Boolean =
        System.currentTimeMillis() - t.ts > TOMB_TTL_MS

    /** 读全量墓碑（(设备组键,mid) 合并，后行胜出）；调用方保证在 lock 内或不并发写 */
    private fun readTombs(): LinkedHashMap<String, Tomb> {
        val out = LinkedHashMap<String, Tomb>()
        if (!tombFile.exists()) return out
        runCatching {
            tombFile.readLines(StandardCharsets.UTF_8).forEach { ln ->
                val t = ln.trim()
                if (t.isEmpty()) return@forEach
                runCatching {
                    val o = JSONObject(t)
                    val k = o.optString("peerIp", "")
                    val mid = o.optString("mid", "")
                    if (k.isEmpty() || mid.isEmpty()) return@forEach
                    out["$k\u0000$mid"] = Tomb(
                        k, mid, o.optLong("ts", 0L), o.optBoolean("sent", false)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读墓碑失败: ${it.message}") }
        return out
    }

    // ==================== 断点续传：半成品注册表 ====================

    /** 登记正在接收/未收完的文件（mid → uri/name/size/received，持久化到 partials.json）；对端重连后凭它 file_query 续传 */
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

    /** 清理孤儿半成品（取消帧丢失/重启后 mid 对不上，永不被 file_query 命中）：删「超过 maxAgeMs 未更新」条目连同文件 */
    fun purgeStalePartials(maxAgeMs: Long) {
        // 会话中也会调用（断链/发送队列空闲）：与 putPartial/removePartial 抢同一份 partials.json，必须同锁
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

    // ==================== 送达台账（同 mid 不重发，扛进程重启） ====================

    /** 「此 mid 已完整送达那台设备」台账（键 = mid@设备组键）：落盘扛进程重启，内存镜像 [delivered] 读写不碰盘 */
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

    /** 本端是否已把这个 mid 存成过完整收讫文件（与 PC 端 savedFileOf 同口径）；只认「对端发来 + FILE + 非目录 + OK」且留有 filePath/localUri；磁盘状态由调用方判 */
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

        /** 删除墓碑保留期：30 天（超过后对端那侧也早已 TTL 清空，不必再挡 hsync） */
        const val TOMB_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** 每个会话最多保留多少条（用户口径：500 条） */
        const val HIST_KEEP = 500

        /** 超过 [HIST_KEEP] 时一次删掉多少条最旧的（用户口径：50 条） */
        const val HIST_DROP = 50
    }
}
