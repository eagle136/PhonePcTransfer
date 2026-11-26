package com.phonepctransfer.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「已知设备」名单（后台能否连接的白名单）。
 *
 * 判定口径（与 PC 端 `Store.isTrustedAddr` 完全一致）：
 * **连接过（握手成功）** 且满足下面任一条：
 * - 与本机**配对成功**（持有同一 token，配对码=用户授权）——只看配对、不发消息也必须算已知；
 * - 与该设备有过消息/文件往来（收或发）。
 *
 * 键口径：**设备身份（deviceId）**，拿不到身份时才退回 IP。名单按身份存，DHCP/换网导致 IP 变化不会失效；
 * 同设备多地址也自然归并。旧版按 IP 存的名单在读取时按记录里的 mid 重排键。
 *
 * 独立落盘，不受清空消息影响、只增不减；首次从聊天记录迁移一次种子；内部加锁。
 */
class TrustedPeers(ctx: Context) {

    /** 一台已知设备的记录 */
    class Peer(
        /** 名单键：对端 deviceId（拿不到身份时退回 IP） */
        var key: String,
        /** 最近一次见到的地址（仅展示/兜底匹配；IP 变了不影响判定） */
        var ip: String,
        var mid: String = "",
        var name: String = "",
        var firstAt: Long = 0L,
        var lastAt: Long = 0L,
        /** 是否与它成功握过手（= 双方连接过） */
        var connected: Boolean = false,
        /** 是否与本机配对成功（持有同一 token）——配对即用户授权 */
        var paired: Boolean = false,
        /** 往来文本条数（收 + 发） */
        var msgs: Int = 0,
        /** 往来文件个数（收 + 发，文件夹成员逐个计） */
        var files: Int = 0
    ) {
        /** 连接过 **且**（配对过 或 有消息/文件往来） → 后台允许连接 */
        val trusted: Boolean get() = connected && (paired || msgs > 0 || files > 0)
    }

    private val jsonFile = File(ctx.filesDir, FILE_NAME)
    private val lock = Any()
    private val map = LinkedHashMap<String, Peer>()

    /** 合并落盘：往来计数每条消息都在涨，不能每条都整文件重写（大名单会拖慢收发热路径） */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable { synchronized(lock) { save() } }

    private fun saveSoon() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 5000L)
    }

    /** 是否已做过「从聊天记录迁移」。落盘保存，保证只跑一次（清空名单不会让它再跑） */
    private var migrated = false

    init {
        load()
        if (!migrated) seedFromHistory()
    }

    /** 一次性从已有历史做种子迁移：非空历史文件 = 连接过且有往来（即「已知设备」定义）；防止升级后老对端后台重连被挡 */
    private fun seedFromHistory() {
        runCatching {
            val histDir = File(jsonFile.parentFile, "history")
            var seeded = 0
            for (f in histDir.listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".jsonl")) continue
                if (f.name.startsWith("history.legacy")) continue
                if (f.length() <= 0L) continue
                // 历史文件名是组键（新版本=deviceId，老版本=IP），直接当名单键用
                val key = f.name.removeSuffix(".jsonl")
                if (key.isEmpty() || key == "_unknown") continue
                // 一行历史 = 一条往来记录；文件非空即代表与该设备连接过
                val lines = runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(1)
                synchronized(lock) {
                    val p = find(key) ?: Peer(key = key, ip = if (looksLikeIp(key)) key else "").also {
                        map[it.key] = it
                    }
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
    fun markConnected(addr: String, name: String, mid: String) {
        touch(addr, name, mid) { p, _ -> p.connected = true }
    }

    /**
     * 配对成功（token 校验通过）：配对码本身就是用户授权，**不需要再等消息/文件往来**
     * ——否则「配对完不发消息、过一会后台重连」会被自己的白名单挡掉（历史 bug）。
     */
    fun markPaired(addr: String, name: String, mid: String) {
        touch(addr, name, mid) { p, _ ->
            p.connected = true
            p.paired = true
        }
    }

    /** 有消息 / 文件往来（收或发都算）—— 与 [markConnected] / [markPaired] 一起构成「已知设备」 */
    fun markContent(addr: String, isFile: Boolean) {
        touch(addr, "", "") { p, _ ->
            if (isFile) p.files++ else p.msgs++
        }
    }

    /**
     * 统一的落点：[addr] 可能是一个地址、一个 deviceId，也可能只是组键；
     * [mid] 是本端刚拿到的设备身份（握手/配对时才有）。能匹配到既有记录就更新，匹配不到按身份新建。
     */
    private fun touch(addr: String, name: String, mid: String, block: (Peer, String) -> Unit) {
        val a = addr.trim()
        val id = mid.trim()
        if (a.isEmpty() && id.isEmpty()) return
        synchronized(lock) {
            val p = find(a) ?: find(id) ?: Peer(
                key = id.ifEmpty { a },
                ip = if (looksLikeIp(a)) a else ""
            ).also { map[it.key] = it }
            if (a.isNotEmpty() && looksLikeIp(a)) p.ip = a
            if (id.isNotEmpty()) {
                p.mid = id
                // 老记录（键是 IP）拿到身份后升级成身份键：IP 变了不再失效，也不会和同设备其它地址分裂成两条
                if (p.key != id && looksLikeIp(p.key)) {
                    map.remove(p.key)
                    p.key = id
                    map[id] = p
                }
            }
            if (name.isNotEmpty()) p.name = name
            p.lastAt = System.currentTimeMillis()
            block(p, a)
            saveSoon()
        }
    }

    // ==================== 查询 ====================

    /** 查找一台设备的记录：[any] 可以是 deviceId、IP 或名字，按 键 → IP → 身份 依次兜底 */
    private fun find(any: String): Peer? {
        val v = any.trim()
        if (v.isEmpty()) return null
        map[v]?.let { return it }
        return map.values.firstOrNull { it.ip == v || (it.mid.isNotEmpty() && it.mid == v) }
    }

    /** 后台是否允许与 [any] 建立连接（[any] 可为 IP 或设备身份）：连接过 +（配对过 或有消息/文件往来） */
    fun isTrusted(any: String): Boolean = synchronized(lock) {
        find(any)?.trusted == true
    }

    /** 命中「已知设备」的台数（设置界面展示） */
    fun count(): Int = synchronized(lock) { map.values.count { it.trusted } }

    /** 一句话说明该设备的判定依据（日志里用来解释「为什么放行/为什么拒绝」） */
    fun describe(any: String): String = synchronized(lock) {
        val p = find(any) ?: return@synchronized "无记录"
        val who = p.name.ifEmpty { p.mid.ifEmpty { p.ip.ifEmpty { "未知设备" } } }
        "$who：连接过=${p.connected}，配对=${p.paired}，消息=${p.msgs}，文件=${p.files}"
    }

    /** 设置界面的概览文案 */
    fun summary(): String = synchronized(lock) {
        val t = map.values.filter { it.trusted }
        if (t.isEmpty()) "暂无（后台不接受任何设备的连接）"
        else t.size.toString() + " 台：" + t.joinToString("、") { it.ip.ifEmpty { it.key } }
    }

    /** 清空名单（用户显式操作）：后台不再放行任何设备；只清名单不清 migrated 标记，防止重启后被历史种回来 */
    fun clear() {
        synchronized(lock) {
            map.clear()
            migrated = true
            save()
        }
        Log.i(TAG, "已知设备名单已清空")
    }

    // ==================== 落盘 ====================

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
                val mid = o.optString("mid", "")
                // 旧版以 IP 为键：有身份就重排到身份键，IP 换掉也仍然认得这台设备
                val key = o.optString("key", "").ifEmpty { mid.ifEmpty { ip } }
                if (key.isEmpty() && ip.isEmpty()) continue
                map[key] = Peer(
                    key = key,
                    ip = ip,
                    mid = mid,
                    name = o.optString("name", ""),
                    firstAt = o.optLong("firstAt", 0L),
                    lastAt = o.optLong("lastAt", 0L),
                    connected = o.optBoolean("connected", false),
                    paired = o.optBoolean("paired", false),
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
                        .put("key", p.key)
                        .put("ip", p.ip)
                        .put("mid", p.mid)
                        .put("name", p.name)
                        .put("firstAt", p.firstAt)
                        .put("lastAt", p.lastAt)
                        .put("connected", p.connected)
                        .put("paired", p.paired)
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

    /** 这个串像不像一个 IPv4/IPv6 地址（用来区分「身份键」和「地址」） */
    private fun looksLikeIp(s: String): Boolean {
        val v = s.trim()
        if (v.isEmpty() || v.startsWith("android-") || v.startsWith("pc-")) return false
        return v.contains('.') || v.contains(':')
    }

    companion object {
        private const val TAG = "PP-Trusted"
        private const val FILE_NAME = "trusted_peers.json"
    }
}
