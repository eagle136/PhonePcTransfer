package com.phonepctransfer.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「已知设备」名单 —— 决定**后台**能不能与某台设备建立连接。
 *
 * 规则（2026-09-15 用户定调，替换旧的「后台一律拒绝新建连接」）：
 *   双方**连接过**（成功握手）**且**有过**消息 / 文件**往来 → 该对端在后台也允许连接；
 *   其余对端仍然只允许在**前台**由用户点击建立连接。
 *
 * 为什么必须单独一份存储（而不是直接读聊天记录）：
 *   `Store.clearHistory()` 删的是 `history/<ip>.jsonl`，「这台设备是不是自己人」
 *   不该因为用户**清空消息**而丢失 —— 因此本名单单独落盘 `filesDir/trusted_peers.json`，
 *   只增不减（除非用户在设置里显式清空）。
 *
 * 首次运行会**从已有聊天记录迁移一次种子**（老对端不该因为升级变成陌生设备），
 * 之后两个存储彻底解耦：清空消息不影响名单，清空名单也不会被聊天记录种回来。
 *
 * 线程：读写都可能来自网络读线程（收到 text / file_meta 就打点），内部加锁 + 小文件整写。
 */
class TrustedPeers(ctx: Context) {

    /** 一台已知设备的记录 */
    class Peer(
        val ip: String,
        var mid: String = "",
        var name: String = "",
        var firstAt: Long = 0L,
        var lastAt: Long = 0L,
        /** 是否与它成功握过手（= 双方连接过） */
        var connected: Boolean = false,
        /** 往来文本条数（收 + 发） */
        var msgs: Int = 0,
        /** 往来文件个数（收 + 发，文件夹成员逐个计） */
        var files: Int = 0
    ) {
        /** 连接过 **且** 有消息/文件往来 → 后台允许连接 */
        val trusted: Boolean get() = connected && (msgs > 0 || files > 0)
    }

    private val jsonFile = File(ctx.filesDir, FILE_NAME)
    private val lock = Any()
    private val map = LinkedHashMap<String, Peer>()

    /** 是否已做过「从聊天记录迁移」。落盘保存，保证只跑一次（清空名单不会让它再跑） */
    private var migrated = false

    init {
        load()
        if (!migrated) seedFromHistory()
    }

    /**
     * 从**已有的聊天记录**做一次种子迁移：每个非空 `history/<ip>.jsonl` 都代表
     * 「与该设备连接过、且有过消息/文件往来」—— 正是「已知设备」的定义。
     *
     * 不做这一步的话，升级到本版本后老对端会被当成陌生设备，后台重连第一次就被自己挡掉
     * （用户感知＝「新版本把重连搞坏了」）。
     *
     * 只跑一次（`migrated` 落盘）：种完、或用户显式清空名单之后都不会再回头读聊天记录
     * —— 这正是「清空消息不影响判定」的落点（清空消息后名单仍然在，判定结果不变）。
     */
    private fun seedFromHistory() {
        runCatching {
            val histDir = File(jsonFile.parentFile, "history")
            var seeded = 0
            for (f in histDir.listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".jsonl")) continue
                if (f.name.startsWith("history.legacy")) continue
                if (f.length() <= 0L) continue
                val ip = f.name.removeSuffix(".jsonl")
                if (ip.isEmpty() || ip == "_unknown") continue
                // 一行历史 = 一条往来记录；文件非空即代表与该设备连接过
                val lines = runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(1)
                synchronized(lock) {
                    val p = map.getOrPut(ip) { Peer(ip = ip) }
                    p.connected = true
                    if (p.msgs == 0 && p.files == 0) {
                        p.msgs = lines
                        if (p.firstAt == 0L) p.firstAt = f.lastModified()
                        if (p.lastAt == 0L) p.lastAt = f.lastModified()
                    }
                }
                seeded++
            }
            migrated = true
            synchronized(lock) { save() }
            if (seeded > 0) Log.i(TAG, "已知设备：从聊天记录迁移 $seeded 台（连接过且有往来）")
        }.onFailure { Log.w(TAG, "从聊天记录迁移已知设备失败：${it.message}") }
    }

    // ==================== 写入 ====================

    /** 握手成功（= 双方连接过）：刷新名字/设备号与时间，不清零已有往来计数 */
    fun markConnected(ip: String, name: String, mid: String) {
        val key = ip.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            val p = ensure(key)
            p.connected = true
            if (name.isNotEmpty()) p.name = name
            if (mid.isNotEmpty()) p.mid = mid
            p.lastAt = System.currentTimeMillis()
            save()
        }
    }

    /** 有消息 / 文件往来（收或发都算）—— 与 [markConnected] 一起构成「已知设备」 */
    fun markContent(ip: String, isFile: Boolean) {
        val key = ip.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            val p = ensure(key)
            if (isFile) p.files++ else p.msgs++
            p.lastAt = System.currentTimeMillis()
            save()
        }
    }

    // ==================== 查询 ====================

    /** 后台是否允许与 [ip] 建立连接：连接过 + 有消息/文件往来 */
    fun isTrusted(ip: String): Boolean = synchronized(lock) {
        map[ip.trim()]?.trusted == true
    }

    /** 命中「已知设备」的台数（设置界面展示） */
    fun count(): Int = synchronized(lock) { map.values.count { it.trusted } }

    /** 一句话说明该设备的判定依据（日志里用来解释「为什么放行/为什么拒绝」） */
    fun describe(ip: String): String = synchronized(lock) {
        val p = map[ip.trim()] ?: return@synchronized "无记录"
        val who = p.name.ifEmpty { p.mid.ifEmpty { "未知设备" } }
        "$who：连接过=${p.connected}，消息=${p.msgs}，文件=${p.files}"
    }

    /** 设置界面的概览文案 */
    fun summary(): String = synchronized(lock) {
        val t = map.values.filter { it.trusted }
        if (t.isEmpty()) "暂无（后台不接受任何设备的连接）"
        else t.size.toString() + " 台：" + t.joinToString("、") { it.ip }
    }

    /**
     * 清空名单（用户显式操作）：之后后台不再放行任何设备，需要重新在**前台**连一次再说。
     *
     * 注意：只清名单、**不解除** [migrated] 标记 —— 否则重启后会被聊天记录种回来，
     * 用户感知就是「清了跟没清一样」。
     */
    fun clear() {
        synchronized(lock) {
            map.clear()
            migrated = true
            save()
        }
        Log.i(TAG, "已知设备名单已清空")
    }

    // ==================== 落盘 ====================

    private fun ensure(key: String): Peer =
        map.getOrPut(key) { Peer(ip = key, firstAt = System.currentTimeMillis(), lastAt = System.currentTimeMillis()) }

    private fun load() {
        runCatching {
            if (!jsonFile.exists()) return
            val txt = jsonFile.readText()
            if (txt.isBlank()) return
            val root = JSONObject(txt)
            migrated = root.optBoolean("migrated", false)
            val arr = root.optJSONArray("peers") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ip = o.optString("ip", "")
                if (ip.isEmpty()) continue
                map[ip] = Peer(
                    ip = ip,
                    mid = o.optString("mid", ""),
                    name = o.optString("name", ""),
                    firstAt = o.optLong("firstAt", 0L),
                    lastAt = o.optLong("lastAt", 0L),
                    connected = o.optBoolean("connected", false),
                    msgs = o.optInt("msgs", 0),
                    files = o.optInt("files", 0)
                )
            }
            Log.i(TAG, "已知设备名单：${map.size} 条，其中可用（后台放行）${map.values.count { it.trusted }} 台")
        }.onFailure { Log.w(TAG, "读取已知设备名单失败：${it.message}") }
    }

    /** 整文件重写（先写 .tmp 再改名，避免写一半被打断留下坏 JSON） */
    private fun save() {
        runCatching {
            val arr = JSONArray()
            for (p in map.values) {
                arr.put(
                    JSONObject()
                        .put("ip", p.ip)
                        .put("mid", p.mid)
                        .put("name", p.name)
                        .put("firstAt", p.firstAt)
                        .put("lastAt", p.lastAt)
                        .put("connected", p.connected)
                        .put("msgs", p.msgs)
                        .put("files", p.files)
                )
            }
            val tmp = File(jsonFile.parentFile, jsonFile.name + ".tmp")
            tmp.writeText(JSONObject().put("migrated", migrated).put("peers", arr).toString())
            if (!tmp.renameTo(jsonFile)) {
                jsonFile.delete()
                if (!tmp.renameTo(jsonFile)) Log.w(TAG, "已知设备名单改名失败")
            }
        }.onFailure { Log.w(TAG, "写入已知设备名单失败：${it.message}") }
    }

    companion object {
        private const val TAG = "PP-Trusted"
        private const val FILE_NAME = "trusted_peers.json"
    }
}
