package com.phonepctransfer.data

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.phonepctransfer.net.Protocol
import com.phonepctransfer.video.VideoLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 设备列表里的一台对端设备：ip=连接地址，remark=用户备注（回退广播名→IP），lastId/lastName=发现时记录的标识与广播名，devs=该 IP 上出现过的全部设备身份 */
data class Peer(
    val ip: String,
    val remark: String = "",
    val lastId: String = "",
    val lastName: String = "",
    val lastSeenAt: Long = 0L,
    /** 同 IP 出现过的全部设备身份（含 lastId）：先到者为活动设备，后来者登记为别名（用户确认后切换，见 [Prefs.promoteDev]）；各记自己最近一次广播/握手时间 */
    val devs: List<PeerDev> = emptyList()
) {
    /** 列表里显示的名字：备注 > 广播名 > IP */
    val displayName: String get() = remark.ifBlank { lastName.ifBlank { ip } }

    fun withSeen(id: String, name: String, at: Long): Peer =
        copy(lastId = id.ifBlank { lastId }, lastName = name.ifBlank { lastName }, lastSeenAt = at)

    /** 该 IP 上的某个设备身份记录（没有返回 null） */
    fun dev(id: String): PeerDev? = devs.firstOrNull { it.id == id }

    fun toJson(): JSONObject {
        val j = JSONObject()
            .put("ip", ip)
            .put("remark", remark)
            .put("lastId", lastId)
            .put("lastName", lastName)
            .put("lastSeenAt", lastSeenAt)
        // 没有别名时**不写这个 key**：老配置格式一字不变（向后兼容、便于人工比对）
        if (devs.isNotEmpty()) {
            val arr = JSONArray()
            devs.forEach { arr.put(it.toJson()) }
            j.put("devs", arr)
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): Peer = Peer(
            ip = j.optString("ip", ""),
            remark = j.optString("remark", ""),
            lastId = j.optString("lastId", ""),
            lastName = j.optString("lastName", ""),
            lastSeenAt = j.optLong("lastSeenAt", 0L),
            devs = runCatching {
                val a = j.optJSONArray("devs") ?: return@runCatching emptyList<PeerDev>()
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { PeerDev.fromJson(it) }
                }.filter { it.id.isNotEmpty() }.distinctBy { it.id }.take(MAX_DEVS)
            }.getOrDefault(emptyList())
        )

        /** 别名表上限：同 IP 超过这个数就不再记新的（防止异常广播把配置撑爆） */
        const val MAX_DEVS = 8
    }
}

/** 同一个 IP 上出现过的一台设备身份（见 [Peer.devs]） */
data class PeerDev(
    val id: String,
    val name: String = "",
    val seenAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("seenAt", seenAt)

    companion object {
        fun fromJson(j: JSONObject): PeerDev = PeerDev(
            id = j.optString("id", ""),
            name = j.optString("name", ""),
            seenAt = j.optLong("seenAt", 0L)
        )
    }
}

/** 设备组：同一台对端设备（同 deviceId）下的全部地址；组键=对端 deviceId（未知回退该 IP），备注仅本机可见，历史/待发按组键归并，curIp=组内当前地址 */
data class PeerGroup(
    /** 组键：对端 deviceId；未知设备时 = 该 IP */
    val key: String,
    /** 对「对方设备」的备注（仅本机可见） */
    val remark: String = "",
    /** 对方最近一次广播名（回退显示用） */
    val name: String = "",
    /** 组内全部已知地址 */
    val ips: List<String> = emptyList(),
    /** 组内当前使用的地址 */
    val curIp: String = "",
    val lastSeenAt: Long = 0L,
    /** 「同 IP 多设备」别名行：该 IP 当前活动设备是别人，点击先弹 IP 冲突确认框 */
    val alt: Boolean = false,
    /** 去重显示名（同名设备自动加 -2/-3）；空=走常规回退，不动用户备注 */
    val shownName: String = "",
    /** 该设备「共用」的地址（其上活动设备是别人）：只做提示，不进 ips、不参与连接/切换 */
    val guestIps: List<String> = emptyList()
) {
    /** 列表/顶栏显示名：去重名 > 备注 > 广播名 > 当前地址 */
    val displayName: String get() = shownName.ifBlank { remark.ifBlank { name.ifBlank { curIp } } }

    /** 是否多地址（多地址时界面给出「切换地址」入口） */
    val multi: Boolean get() = ips.size > 1
}

/** 轻量配置存储（SharedPreferences） */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("pp_prefs", Context.MODE_PRIVATE)

    /** ip→组键解析缓存（convKeyOf 在发送热路径上）；改变绑定关系的写操作都要清表 */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // 身份绑定与组备注的一次性播种（老数据升级）
        runCatching { seedIdentity() }
        // 旋转默认基线改版后的一次性迁移：作废旧的手调记忆角（对方画面二次单独作废）
        runCatching { resetRotSchemeOnce() }
        runCatching { resetRotRemoteOnce() }
    }

    var deviceName: String
        get() = sp.getString(K_NAME, null) ?: defaultName()
        set(v) = sp.edit().putString(K_NAME, v).apply()

    val deviceId: String by lazy {
        val exist = sp.getString(K_ID, null)
        if (exist.isNullOrEmpty()) {
            val id = "android-" + UUID.randomUUID().toString().substring(0, 8)
            sp.edit().putString(K_ID, id).apply()
            id
        } else exist
    }

    var tcpPort: Int
        get() = sp.getInt(K_TCP, Protocol.DEFAULT_TCP_PORT)
        set(v) = sp.edit().putInt(K_TCP, v).apply()

    var udpPort: Int
        get() = sp.getInt(K_UDP, Protocol.DEFAULT_UDP_PORT)
        set(v) = sp.edit().putInt(K_UDP, v).apply()

    /** 上次选中的设备 IP：启动扫描结束后自动试连一次以恢复上次会话（连不上不重试） */
    var lastSelectedIp: String
        get() = sp.getString(K_LAST_SEL, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_SEL, v).apply()

    /** 权限询问里点过「不需要」的项 id（含 a11y）：该项冷启动不再问；权限本身仍按需申请 */
    var permNeverAsk: Set<String>
        get() = sp.getStringSet(K_PERM_NEVER, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_PERM_NEVER, v.toSet()).apply()

    /** 「刚因授权而自杀重启」的一次性标记：重启后第一个 onCreate 读掉并 toast 解释 */
    var permRestartNotice: Boolean
        get() = sp.getBoolean(K_PERM_RESTART, false)
        set(v) = sp.edit().putBoolean(K_PERM_RESTART, v).apply()

    // ==================== 「这类文件用什么程序打开」的本应用私有记忆 ====================

    /** 「这类文件用什么程序打开」按 MIME 记（只写本应用自己的 SP，不碰系统默认应用表）；命中时用显式组件 intent 直接启动 */
    fun openerOf(mime: String): ComponentName? =
        sp.getString(K_OPENER + mime, null)?.let { ComponentName.unflattenFromString(it) }

    fun rememberOpener(mime: String, comp: ComponentName) {
        sp.edit().putString(K_OPENER + mime, comp.flattenToString()).apply()
    }

    /** 记住的程序已被卸载/停用 → 忘掉这条，下次回到弹列表让用户重选 */
    fun forgetOpener(mime: String) {
        sp.edit().remove(K_OPENER + mime).apply()
    }

    /** 设置页展示用：`(mime, "包名/类名")` 按 mime 排序 */
    fun openers(): List<Pair<String, String>> =
        sp.all
            .filterKeys { it.startsWith(K_OPENER) }
            .mapNotNull { (k, v) ->
                val mime = k.substring(K_OPENER.length)
                val comp = v as? String
                if (mime.isEmpty() || comp.isNullOrEmpty()) null else mime to comp
            }
            .sortedBy { it.first }

    /** 全部清除（设置页「重置」）；返回清掉的条数，供 toast 核对 */
    fun clearOpeners(): Int {
        val keys = sp.all.keys.filter { it.startsWith(K_OPENER) }
        if (keys.isEmpty()) return 0
        sp.edit().apply { keys.forEach { remove(it) } }.apply()
        return keys.size
    }

    // ==================== 设备列表（备注 + IP） ====================

    /** 设备列表（IP + 备注）；旧版纯 IP 集合首次读取时自动迁移 */
    var peers: List<Peer>
        get() {
            val raw = sp.getString(K_PEERS, null)
            if (!raw.isNullOrEmpty()) {
                runCatching {
                    val arr = JSONArray(raw)
                    val out = ArrayList<Peer>(arr.length())
                    for (i in 0 until arr.length()) {
                        runCatching { Peer.fromJson(arr.getJSONObject(i)) }
                            .getOrNull()
                            ?.takeIf { it.ip.isNotEmpty() && it.lastId != deviceId }
                            ?.let { out.add(it) }
                    }
                    return out
                }
            }
            // 迁移：旧版只存了 IP 集合
            val legacy = sp.getStringSet(K_IPS, emptySet())?.sorted() ?: emptyList()
            return legacy.map { Peer(ip = it) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            sp.edit().putString(K_PEERS, arr.toString()).apply()
            // 设备行的 lastId 会参与组键解析（老数据回退路径）→ 缓存必须失效
            keyCache.clear()
        }

    /** 兼容旧代码：候选 IP 列表（从设备列表派生） */
    val peerIps: List<String> get() = peers.map { it.ip }

    /** 新增/更新一台设备。同 IP 多设备三规则：活动设备→常规刷新；还在（30s 内有动静）→只登记别名，绝不改 lastId/绑定/组状态；已离开（≥30s）→允许接管。@return true=用户可见的结构变化（纯时间戳刷新不算） */
    fun upsertPeer(
        ip: String,
        remark: String? = null,
        lastId: String? = null,
        lastName: String? = null,
        seenAt: Long = 0L
    ): Boolean {
        val v = ip.trim()
        if (v.isEmpty()) return false
        // 自己的 deviceId 不入库（否则链路层陷入自连循环）
        if (lastId == deviceId) return false
        val did = lastId?.trim().orEmpty()
        val dn = lastName?.trim().orEmpty()
        val cur = peers.toMutableList()
        val idx = cur.indexOfFirst { it.ip == v }
        if (idx < 0) {
            cur.add(
                Peer(
                    ip = v,
                    remark = remark?.trim().orEmpty(),
                    lastId = did,
                    lastName = dn,
                    lastSeenAt = seenAt,
                    devs = if (did.isEmpty()) emptyList() else listOf(PeerDev(did, dn, seenAt))
                )
            )
            peers = cur
            return true
        }
        val old = cur[idx]
        var p = old
        var structural = false
        // 备注为 null 时视作「不改备注」；显式传 "" 才会清空
        if (remark != null && remark.trim() != old.remark) {
            p = p.copy(remark = remark.trim())
            structural = true
        }
        val active = old.lastId.trim()
        // 别名表同时记活动设备自己：「是否离开」只看它自己的最近广播/握手时间（peer.lastSeenAt 会被同 IP 别的设备刷新）
        if (did.isNotEmpty()) {
            val (dv, ch) = noteDev(old.devs, did, dn, seenAt)
            if (dv !== old.devs) p = p.copy(devs = dv)
            if (ch) structural = true
        }
        if (did.isNotEmpty() && did != active && (active.isEmpty() || devGone(old, active))) {
            // 接管：身份换成它。组状态（备注/当前地址/旋转角/标记）由 bindDevice 搬家 + 清 keyCache
            val (dv, _) = if (active.isEmpty() || p.dev(active) != null
                || devStaleForAlias(old.lastSeenAt, DEV_ALIAS_KEEP_MS)
            ) {
                // 升级兼容：给旧活动设备补一条记录（太久没出现的不补，免幽灵行）
                p.devs to false
            } else {
                noteDev(p.devs, active, old.lastName, old.lastSeenAt)
            }
            p = p.copy(lastId = did, devs = dv)
            // 它真的占到地址了 → 用户之前「不再显示」的标记作废
            unignoreDev(did)
            bindDevice(v, did)
            structural = true
        }
        // 广播名只跟着活动设备走（否则同 IP 两台设备的广播会把显示名来回改）
        if (dn.isNotEmpty() && (did.isEmpty() || did == p.lastId) && dn != old.lastName) {
            p = p.copy(lastName = dn)
            structural = true
        }
        if (seenAt > old.lastSeenAt) p = p.copy(lastSeenAt = seenAt)
        if (p != old) {
            cur[idx] = p
            peers = cur
        }
        return structural
    }

    /** 别名表记/刷新一台设备身份（时间戳静默刷新）；@return 新列表 + 是否结构变化（纯时间戳刷新不算） */
    private fun noteDev(
        devs: List<PeerDev>,
        id: String,
        name: String,
        at: Long
    ): Pair<List<PeerDev>, Boolean> {
        if (id.isEmpty()) return devs to false
        val i = devs.indexOfFirst { it.id == id }
        if (i < 0) {
            if (devs.size >= Peer.MAX_DEVS) return devs to false
            return (devs + PeerDev(id, name, at)) to true
        }
        val d = devs[i]
        val n = if (name.isNotEmpty()) name else d.name
        val s = if (at > d.seenAt) at else d.seenAt
        if (n == d.name && s == d.seenAt) return devs to false
        val out = devs.toMutableList()
        out[i] = d.copy(name = n, seenAt = s)
        return out to (n != d.name)
    }

    /** 「活动设备」多久没动静就算离开了（广播/握手周期是 2s，30s 足够判定关机 / 换网） */
    private val DEV_TAKEOVER_MS = 30_000L

    /** 该地址上的活动设备是否已离开（只看它自己的最近广播/握手时间，不看 peer.lastSeenAt） */
    private fun devGone(p: Peer, activeId: String): Boolean {
        val seen = p.dev(activeId)?.seenAt ?: p.lastSeenAt
        return seen <= 0L || System.currentTimeMillis() - seen > DEV_TAKEOVER_MS
    }

    /** 接管时「被顶下去的旧活动设备」还值不值得留一条别名行（超过时限 = 多半是旧 deviceId，不留） */
    private val DEV_ALIAS_KEEP_MS = 10 * 60_000L

    /** 别名行显示时限：换/重装后 deviceId 变更的旧身份永不广播，太久没露面的不再列出；真正在场的设备记录一直新鲜，不会误藏 */
    private val DEV_ALIAS_SHOW_MS = 60 * 60_000L

    /** 某个设备身份是否已经超过 [limitMs] 没出现过 */
    private fun devStaleForAlias(seenAt: Long, limitMs: Long): Boolean =
        seenAt <= 0L || System.currentTimeMillis() - seenAt > limitMs

    // ==================== 别名行「不再显示」 ====================

    /** 用户明确删掉的别名设备：设备身份 → "1"（见 [forgetDev] / [unignoreDev]） */
    private fun ignoredDevsMap(): MutableMap<String, String> = jsonMap(K_IGNORED_DEVS)

    /** 忘掉「这台设备在此地址出现过」的别名记录并记入忽略表（否则对方下次广播又加回来）；它真占到地址时标记自动解除 */
    fun forgetDev(deviceId: String): Boolean {
        val d = deviceId.trim()
        if (d.isEmpty()) return false
        var changed = false
        val cur = peers.toMutableList()
        for (i in cur.indices) {
            val p = cur[i]
            if (p.dev(d) != null) {
                cur[i] = p.copy(devs = p.devs.filterNot { it.id == d })
                changed = true
            }
        }
        if (changed) peers = cur
        val m = ignoredDevsMap()
        if (!m.containsKey(d)) {
            m[d] = "1"
            putJsonMap(K_IGNORED_DEVS, m)
            changed = true
        }
        return changed
    }

    /** 某台设备真的占到地址（成为活动设备）→ 取消「不再显示」的标记 */
    private fun unignoreDev(id: String) {
        if (id.isEmpty()) return
        val m = ignoredDevsMap()
        if (m.remove(id) != null) putJsonMap(K_IGNORED_DEVS, m)
    }

    /** 修改一台设备的 IP 与备注（长按编辑弹窗用）。oldIp 定位原条目，newIp 可改。 */
    fun updatePeer(oldIp: String, newIp: String, remark: String): Boolean {
        val cur = peers.toMutableList()
        val idx = cur.indexOfFirst { it.ip == oldIp }
        if (idx < 0) return false
        val target = newIp.trim().ifEmpty { oldIp }
        // 若目标 IP 与另一条重复，则合并（保留目标条目的发现信息）
        val dup = cur.indexOfFirst { it.ip == target && it != cur[idx] }
        if (dup >= 0) {
            val keep = cur[dup]
            cur.removeAt(if (dup > idx) dup else idx)
            val k = cur.indexOfFirst { it.ip == target }
            cur[k] = keep.copy(remark = remark.trim())
        } else {
            cur[idx] = cur[idx].copy(ip = target, remark = remark.trim())
        }
        peers = cur
        return true
    }

    /** 解除某地址的身份绑定（只动绑定表与解析缓存，设备行由调用方处理） */
    fun unbindDevice(ip: String): Boolean {
        val v = ip.trim()
        if (v.isEmpty()) return false
        val m = bindingsMap()
        if (!m.containsKey(v)) return false
        m.remove(v)
        putJsonMap(K_BINDINGS, m)
        keyCache.clear()
        return true
    }

    // ==================== 网段派生的设备标记 ====================
    //
    // 只记「哪台设备被标记」：不记地址/时间/次数/原因；解除只发生在删除设备（见 [removePeer]），不自动过期。

    private fun blockedGroups(): MutableMap<String, String> = jsonMap(K_BLOCKED_GROUPS)

    /** 标记一台设备（键用 [convKeyOf] 的派生键：已识别身份是 deviceId，否则是该地址本身） */
    fun markBlockedGroup(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        val m = blockedGroups()
        if (m.containsKey(k)) return
        m[k] = "1"
        putJsonMap(K_BLOCKED_GROUPS, m)
    }

    /** 该地址所属设备是否已被标记（纯内存查表，落在每个新建连接的入口上） */
    fun isBlockedAddr(ip: String): Boolean {
        val k = convKeyOf(ip)
        return k.isNotEmpty() && blockedGroups().containsKey(k)
    }

    private fun clearBlockedGroup(key: String) {
        val m = blockedGroups()
        if (m.remove(key) != null) putJsonMap(K_BLOCKED_GROUPS, m)
    }

    /** 删除一个地址并一并解除身份绑定（只删行留绑定的话，重新加回同一地址会立刻并入老设备的组） */
    fun removePeer(ip: String): Boolean {
        val v = ip.trim()
        val key = convKeyOf(v)
        val cur = peers.toMutableList()
        val removed = cur.removeAll { it.ip == v }
        val unbound = unbindDevice(v)
        if (removed) peers = cur
        // 设备标记只随「这台设备一个地址都不剩」解除：从组里摘掉一条地址不算删设备。
        if (key.isNotEmpty() && peers.none { convKeyOf(it.ip) == key }) clearBlockedGroup(key)
        return removed || unbound
    }

    /** 手工添加一个地址（时间戳写 now，免被 pruneStaleIps 当场清掉） */
    fun addPeerIp(ip: String): Boolean = upsertPeer(ip, seenAt = System.currentTimeMillis())

    fun removePeerIp(ip: String): Boolean = removePeer(ip)

    /** 启动时清理长期没用过的地址：只清理超过 keepMin 条地址的设备组，保底留最近 keepMin 条 + 当前地址；删除走 [removePeer]（连带解绑）。@return 实际删掉的地址条数 */
    fun pruneStaleIps(maxAgeDays: Int = 5, keepMin: Int = 2): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        val byKey = LinkedHashMap<String, MutableList<Peer>>()
        for (p in peers) {
            if (p.ip.isBlank()) continue
            byKey.getOrPut(convKeyOf(p.ip)) { mutableListOf() }.add(p)
        }
        val curMap = groupCurMap()
        var removed = 0
        for ((key, rows) in byKey) {
            if (rows.size <= keepMin) continue          // 两条以内的设备不动
            val keep = mutableSetOf<String>()
            curMap[key]?.takeIf { it.isNotBlank() }?.let { keep += it }
            rows.sortedByDescending { it.lastSeenAt }.take(keepMin).forEach { keep += it.ip }
            for (p in rows) {
                if (p.ip in keep) continue
                // lastSeenAt == 0 表示本机从没记录过它的活动 → 同样算陈旧（手工添加的已写 now，不会命中）
                if (p.lastSeenAt > 0L && p.lastSeenAt >= cutoff) continue
                if (removePeer(p.ip)) removed++
            }
        }
        return removed
    }

    /** 剪贴板同步总开关：开=焦点回自己时推一次本机剪贴板，接收纯被动；关=纯手动 */
    var clipSyncMode: Boolean
        get() = sp.getBoolean(K_CLIP_SYNC_MODE, true)
        set(v) = sp.edit().putBoolean(K_CLIP_SYNC_MODE, v).apply()

    /** 主动发送文本/文件后自动同步一次 */
    var autoSyncOnSend: Boolean
        get() = sp.getBoolean(K_AUTO_SYNC, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SYNC, v).apply()

    /** 强制启用传输（WiFi/热点判定失灵的 ROM 上调试用） */
    var forceLanEnabled: Boolean
        get() = sp.getBoolean(K_FORCE, false)
        set(v) = sp.edit().putBoolean(K_FORCE, v).apply()

    /** 内置文件浏览器用 root(su) 直读 /data/media/0（= /sdcard 底层目录）；默认关，开着时 /Android/data、/Android/obb 也能浏览 */
    var rootFs: Boolean
        get() = sp.getBoolean(K_ROOT_FS, false)
        set(v) = sp.edit().putBoolean(K_ROOT_FS, v).apply()

    /** 内置文件浏览器上次由系统选择器授权的目录树（Android 17 起为主路径，存下免每次重选）；授权失效时枚举如实报错，界面清记录并引导重选 */
    var safTreeUri: String
        get() = sp.getString(K_SAF_TREE_URI, "") ?: ""
        set(v) = sp.edit().putString(K_SAF_TREE_URI, v).apply()

    var safTreeDocId: String
        get() = sp.getString(K_SAF_TREE_DOC, "") ?: ""
        set(v) = sp.edit().putString(K_SAF_TREE_DOC, v).apply()

    /** 顶栏显示的目录名（拿不到时可能是 documentId） */
    var safTreeLabel: String
        get() = sp.getString(K_SAF_TREE_LABEL, "") ?: ""
        set(v) = sp.edit().putString(K_SAF_TREE_LABEL, v).apply()

    /** 丢掉失效的目录树记录（枚举失败时调用，见 FileBrowser 里的失效处理） */
    fun clearSafTree() {
        sp.edit().remove(K_SAF_TREE_URI).remove(K_SAF_TREE_DOC).remove(K_SAF_TREE_LABEL).apply()
    }

    /** 主题模式：-1 跟随系统（默认）/ 0 浅色 / 1 深色（点「主题」切换并固定） */
    var themeMode: Int
        get() = sp.getInt(K_THEME_MODE, -1)
        set(v) = sp.edit().putInt(K_THEME_MODE, v).apply()

    /** 电脑端是否自动把收到的内容写入剪贴板（提示用，实际由 PC 端控制） */
    var showClipToast: Boolean
        get() = sp.getBoolean(K_CLIP_TOAST, true)
        set(v) = sp.edit().putBoolean(K_CLIP_TOAST, v).apply()

    /** 剪贴板去重哈希持久化：进程重启后不再把残留剪贴板再回推一遍 */
    var lastPushedSha1: String
        get() = sp.getString(K_PUSHED_SHA, "") ?: ""
        set(v) = sp.edit().putString(K_PUSHED_SHA, v).apply()

    var lastPulledSha1: String
        get() = sp.getString(K_PULLED_SHA, "") ?: ""
        set(v) = sp.edit().putString(K_PULLED_SHA, v).apply()

    // ==================== 视频通话 ====================

    /** 视频通话总开关（全局回退：关=行为等同改造前纯语音通话） */
    var videoEnabled: Boolean
        get() = sp.getBoolean(K_VIDEO_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_VIDEO_ENABLED, v).apply()

    /** 视频档位上限（档位表见 [VideoLevel]；这里与选档框选的档都是上限不是起始档）。默认值与钳位统一写 VideoLevel.MAX.idx，勿写字面量 */
    var videoMaxLevel: Int
        get() = sp.getInt(K_VIDEO_MAX_LEVEL, VideoLevel.MAX.idx)
        set(v) = sp.edit().putInt(K_VIDEO_MAX_LEVEL, v.coerceIn(0, VideoLevel.MAX.idx)).apply()

    /** 「旋转对方画面」记住的最终显示角度（绝对角，-1 = 从未手调 → 渲染侧走自适应基线） */
    var videoRemoteRot: Int
        get() = sp.getInt(K_VIDEO_REMOTE_ROT, -1)
        set(v) = sp.edit().putInt(K_VIDEO_REMOTE_ROT, if (v < 0) -1 else ((v % 360) + 360) % 360).apply()

    /** 「旋转小窗」钮记忆的最终显示角（语义同 [videoRemoteRot]，-1 = 未手调） */
    var videoLocalRot: Int
        get() = sp.getInt(K_VIDEO_LOCAL_ROT, -1)
        set(v) = sp.edit().putInt(K_VIDEO_LOCAL_ROT, if (v < 0) -1 else ((v % 360) + 360) % 360).apply()

    // ==================== 设备身份（deviceId）与设备组 ====================
    //
    // 身份来源：本机 = [deviceId]，对端由 UDP 广播 / TCP hello 的 id 字段带来（不需要改协议）
    //
    // 绑定规则：连接时对端自报 id → 绑到当前 IP（[bindDevice]）；同 id 多个 IP → 自动归组，备注按设备记（仅本机可见）

    /** 简短的 id 展示（日志/界面用）：超长只留头尾 */
    private fun shortId(id: String): String =
        if (id.length <= 12) id else id.substring(0, 6) + "…" + id.substring(id.length - 4)

    private fun jsonMap(key: String): MutableMap<String, String> {
        val raw = sp.getString(key, null) ?: return LinkedHashMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) out[k] = o.optString(k, "")
            out
        }.getOrDefault(LinkedHashMap())
    }

    private fun putJsonMap(key: String, v: Map<String, String>) {
        sp.edit().putString(key, JSONObject(v).toString()).apply()
    }

    /** 权威绑定表：ip → 对端 deviceId（对端在 hello/广播里自报的那个 id） */
    private fun bindingsMap(): MutableMap<String, String> = jsonMap(K_BINDINGS)

    /** 把某 IP 绑定到对端设备 id（一对多=自动成组）；@return true = 绑定关系变化（调用方据此刷新界面） */
    fun bindDevice(ip: String, deviceId: String): Boolean {
        val v = ip.trim()
        val d = deviceId.trim()
        if (v.isEmpty() || d.isEmpty()) return false
        // 绑定前的组键：若自成一组（手工添加、还没连过），绑定后其组级状态（备注/当前地址/旋转角）要跟着搬
        val oldKey = convKeyOf(v)
        // 用户手工把这个地址指给这台设备 = 明确要用它 → 作废「不再显示」标记
        unignoreDev(d)
        var changed = false
        val m = bindingsMap()
        if (m[v] != d) {
            m[v] = d
            putJsonMap(K_BINDINGS, m)
            keyCache.clear()
            changed = true
        }
        // peer 行同步 lastId（旧读法/界面仍会看它，保持单值一致）
        val cur = peers.toMutableList()
        val idx = cur.indexOfFirst { it.ip == v }
        if (idx >= 0 && cur[idx].lastId != d) {
            cur[idx] = cur[idx].copy(lastId = d)
            peers = cur
            changed = true
        }
        if (changed && oldKey != d) migrateGroupState(oldKey, d)
        return changed
    }

    /** 握手/发现带来的被动绑定：仅当地址还没有别的活动设备（或那台已离开）才绑，后来者只登记别名；手动 bindDevice 仍是强绑定。@return true = 绑定关系变化 */
    fun bindDeviceIfFree(ip: String, deviceId: String): Boolean {
        val v = ip.trim()
        val d = deviceId.trim()
        if (v.isEmpty() || d.isEmpty()) return false
        val p = peers.firstOrNull { it.ip == v }
        val active = p?.lastId?.trim().orEmpty()
        if (p != null && active.isNotEmpty() && active != d && !devGone(p, active)) {
            // 地址被一台**还在线**的设备占着 → 不抢绑定，只把它记进别名表（列表里多一行）
            val (dv, ch) = noteDev(p.devs, d, "", System.currentTimeMillis())
            if (ch || dv !== p.devs) {
                val cur = peers.toMutableList()
                val idx = cur.indexOfFirst { it.ip == v }
                if (idx >= 0) {
                    cur[idx] = cur[idx].copy(devs = dv)
                    peers = cur
                }
            }
            return false
        }
        return bindDevice(v, d)
    }

    /** 把旧组键上的本机状态（备注/当前地址/旋转角/设备标记）搬到新组键（新地址并入已有设备时调用）；目标组已有值不覆盖，只补空缺 */
    private fun migrateGroupState(oldKey: String, newKey: String) {
        if (oldKey.isEmpty() || newKey.isEmpty() || oldKey == newKey) return
        val rem = jsonMap(K_REMARKS)
        val oldRem = rem.remove(oldKey)
        if (!rem.containsKey(newKey) && !oldRem.isNullOrBlank()) rem[newKey] = oldRem
        putJsonMap(K_REMARKS, rem)

        val cur = jsonMap(K_GROUP_CUR)
        val oldCur = cur.remove(oldKey)
        if (!cur.containsKey(newKey) && !oldCur.isNullOrBlank()) cur[newKey] = oldCur
        putJsonMap(K_GROUP_CUR, cur)

        // 设备标记也按组键存，换绑时跟着搬（目标组已有则不重复）
        val marks = jsonMap(K_BLOCKED_GROUPS)
        if (marks.remove(oldKey) != null && !marks.containsKey(newKey)) marks[newKey] = "1"
        putJsonMap(K_BLOCKED_GROUPS, marks)

        // 每设备的视频旋转角同样按组键存（含 `|f`/`|b` 前后槽），后缀必须跟着一起搬
        for (key in listOf(K_ROT_REMOTE, K_ROT_LOCAL)) {
            val rot = jsonMap(key)
            for (suffix in listOf("", "|f", "|b")) {
                val from = oldKey + suffix
                val to = newKey + suffix
                val oldRot = rot.remove(from) ?: continue
                if (!rot.containsKey(to) && oldRot.isNotBlank()) rot[to] = oldRot
            }
            putJsonMap(key, rot)
        }
    }

    /** 会话组键：对端 deviceId（未知回退该 IP）；历史/待发/消息归属都用它 */
    fun convKeyOf(ip: String): String {
        val v = ip.trim()
        if (v.isEmpty()) return ""
        keyCache[v]?.let { return it }
        val bound = bindingsMap()[v]
        val k = if (!bound.isNullOrEmpty()) bound
        else peers.firstOrNull { it.ip == v }?.lastId?.trim().orEmpty().ifEmpty { v }
        keyCache[v] = k
        return k
    }

    /** 组内当前使用的地址（没记过就用组内第一条 / 上次选中的那条） */
    private fun groupCurMap(): MutableMap<String, String> = jsonMap(K_GROUP_CUR)

    fun setGroupCurIp(key: String, ip: String) {
        val k = key.trim()
        if (k.isEmpty() || ip.isBlank()) return
        val m = groupCurMap()
        if (m[k] == ip.trim()) return
        m[k] = ip.trim()
        putJsonMap(K_GROUP_CUR, m)
    }

    /** 全部设备组（一设备一组，按列表顺序）；主组后追加别名行，最后统一同名去重。@param isConnected 供界面绿点判断 */
    fun groups(isConnected: (String) -> Boolean = { false }): List<PeerGroup> {
        val byKey = LinkedHashMap<String, MutableList<Peer>>()
        for (p in peers) {
            if (p.ip.isBlank()) continue
            byKey.getOrPut(convKeyOf(p.ip)) { mutableListOf() }.add(p)
        }
        val rem = jsonMap(K_REMARKS)
        val curMap = groupCurMap()
        val lastSel = lastSelectedIp
        val out = ArrayList<PeerGroup>(byKey.size)
        for ((k, rows) in byKey) {
            val ips = rows.map { it.ip }
            val newest = rows.maxByOrNull { it.lastSeenAt }
            val rowRemark = rows.firstOrNull { it.remark.isNotBlank() }?.remark.orEmpty()
            var c = curMap[k].orEmpty()
            if (c.isEmpty() || c !in ips) {
                c = when {
                    lastSel.isNotBlank() && lastSel in ips -> lastSel
                    else -> ips.firstOrNull { isConnected(it) } ?: ips.first()
                }
            }
            out.add(
                PeerGroup(
                    key = k,
                    remark = rem[k] ?: rowRemark,
                    name = newest?.lastName.orEmpty(),
                    ips = ips,
                    curIp = c,
                    lastSeenAt = newest?.lastSeenAt ?: 0L
                )
            )
        }
        // ===== 同 IP 多设备：把每个地址上「非活动设备」的身份也列出来 =====
        // 硬约束：一台设备只能出一行（行键 = 设备身份，重复行键会让 LazyColumn 直接抛异常）。
        //   · 已有主行（在别的地址上是活动设备）→ 只往那一行补 guestIps（纯提示，不参与连接/切换）；
        //   · 一个地址都没占到的「纯客人」→ 才单独开别名行（点击走 IP 冲突确认框）。
        // 忽略表读一次（别在循环里反复读 SharedPreferences + 解 JSON）
        val ignored = ignoredDevsMap()
        val alts = LinkedHashMap<String, PeerGroup>()
        for (p in peers) {
            if (p.ip.isBlank()) continue
            val active = p.lastId.trim()
            for (d in p.devs) {
                if (d.id.isEmpty() || d.id == active) continue
                // 用户明确删过的（见 forgetDev）→ 不再列出来；等它真占到地址时自动解除
                if (ignored.containsKey(d.id)) continue
                // 太久没露面的别名（多半是换/重装前的旧身份）不再显示；它再广播/握手一次记录变新，行自己回来（见 DEV_ALIAS_SHOW_MS）
                if (devStaleForAlias(d.seenAt, DEV_ALIAS_SHOW_MS)) continue
                val oi = out.indexOfFirst { it.key == d.id }
                if (oi >= 0) {
                    // 已有主行 → 只补一条「共用地址」提示，绝不开第二行（自己 ips 里已有的不算「共用」）
                    val o = out[oi]
                    if (o.guestIps.none { it == p.ip } && o.ips.none { it == p.ip }) {
                        out[oi] = o.copy(guestIps = o.guestIps + p.ip)
                    }
                    continue
                }
                val prev = alts[d.id]
                alts[d.id] = if (prev == null) {
                    PeerGroup(
                        key = d.id,
                        remark = rem[d.id].orEmpty(),
                        name = d.name,
                        ips = listOf(p.ip),
                        curIp = p.ip,
                        lastSeenAt = d.seenAt,
                        alt = true,
                        guestIps = emptyList()
                    )
                } else if (d.seenAt > prev.lastSeenAt) {
                    // 这台「纯客人」设备出现在多个地址上：最近那个当主地址，旧的降为「共用地址」
                    prev.copy(
                        ips = listOf(p.ip),
                        curIp = p.ip,
                        lastSeenAt = d.seenAt,
                        guestIps = if (p.ip == prev.curIp || prev.guestIps.contains(prev.curIp)) {
                            prev.guestIps
                        } else {
                            prev.guestIps + prev.curIp
                        }
                    )
                } else {
                    if (prev.guestIps.contains(p.ip)) prev
                    else prev.copy(guestIps = prev.guestIps + p.ip)
                }
            }
        }
        out.addAll(alts.values)
        return dedupeNames(out)
    }

    /** 同名去重：第 2/3… 台同名设备显示为 名字-2/-3（只写 shownName，不动 remark/name） */
    private fun dedupeNames(gs: List<PeerGroup>): List<PeerGroup> {
        val used = HashSet<String>()
        val out = ArrayList<PeerGroup>(gs.size)
        for (g in gs) {
            val base = g.displayName
            if (base.isEmpty() || used.add(base)) {
                out.add(g)
                continue
            }
            var n = 2
            var cand = "$base-$n"
            while (!used.add(cand)) {
                n++
                cand = "$base-$n"
            }
            out.add(g.copy(shownName = cand))
        }
        return out
    }

    /** 该地址当前的**活动设备** id（未识别身份 / 地址不存在时为空串） */
    fun activeDevOf(ip: String): String =
        peers.firstOrNull { it.ip == ip.trim() }?.lastId?.trim().orEmpty()

    /** 切换某地址的活动设备（同 IP 多设备冲突：用户确认「断开之前的连接」后调用）：旧设备降为别名，新设备升为活动，旧组键上的本机状态搬到新组键；@return true = 切换成功 */
    fun promoteDev(ip: String, deviceId: String): Boolean {
        val v = ip.trim()
        val d = deviceId.trim()
        if (v.isEmpty() || d.isEmpty()) return false
        val cur = peers.toMutableList()
        val idx = cur.indexOfFirst { it.ip == v }
        if (idx < 0) return false
        val p = cur[idx]
        // 组键必须在动绑定 / 动 peer.lastId **之前**取：convKeyOf 的兜底会读 peer.lastId
        val old = convKeyOf(v)
        if (d == old) {
            // 已经是活动设备：补一条它自己的活跃记录即可
            if (p.dev(d) == null) {
                val (dv, _) = noteDev(p.devs, d, p.lastName, System.currentTimeMillis())
                cur[idx] = p.copy(devs = dv)
                peers = cur
            }
            unignoreDev(d)
            return true
        }
        var devs = p.devs
        // 升级兼容：旧活动设备还没进别名表时补一条
        if (old.isNotEmpty() && old != v && p.dev(old) == null) {
            devs = noteDev(devs, old, p.lastName, p.lastSeenAt).first
        }
        if (p.dev(d) == null) {
            devs = noteDev(devs, d, "", System.currentTimeMillis()).first
        }
        val m = bindingsMap()
        m[v] = d
        putJsonMap(K_BINDINGS, m)
        cur[idx] = p.copy(lastId = d, devs = devs)
        peers = cur
        migrateGroupState(old, d)
        // 该地址从此属于新设备（顶栏地址菜单 / 下次启动自动连接都读它）
        setGroupCurIp(d, v)
        unignoreDev(d)
        return true
    }

    /** 该组键对应的设备组（找不到返回 null） */
    fun groupOf(key: String): PeerGroup? = groups().firstOrNull { it.key == key.trim() }

    /** 某个地址所属的设备组 */
    fun groupOfIp(ip: String): PeerGroup? {
        val k = convKeyOf(ip)
        return if (k.isEmpty()) null else groupOf(k)
    }

    /** 对某个地址所属**设备**的备注（组备注优先，回退旧版的每 IP 备注） */
    fun remarkOf(ip: String): String {
        val v = ip.trim()
        if (v.isEmpty()) return ""
        val k = convKeyOf(v)
        val m = jsonMap(K_REMARKS)
        if (m.containsKey(k)) return m[k].orEmpty()
        return peers.firstOrNull { it.ip == v }?.remark.orEmpty()
    }

    /** 写「对某设备」的备注（组级），同组所有 peer 行一起同步 */
    fun setGroupRemark(key: String, remark: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        val r = remark.trim()
        val m = jsonMap(K_REMARKS)
        m[k] = r
        putJsonMap(K_REMARKS, m)
        val cur = peers.toMutableList()
        var ch = false
        cur.forEachIndexed { i, p ->
            if (convKeyOf(p.ip) == k && p.remark != r) {
                cur[i] = p.copy(remark = r)
                ch = true
            }
        }
        if (ch) peers = cur
    }

    /** 按地址写备注（内部换算成组键，见 [setGroupRemark]） */
    fun setRemarkOf(ip: String, remark: String) = setGroupRemark(convKeyOf(ip), remark)

    // ==================== 每设备视频旋转角（R5） ====================
    //
    // 旋转角按组键（= 设备）记，不再全局（换设备不串味）；
    // 再按摄像头朝向分两套（`组键|f`/`|b`），老裸键只提升为后置。

    private fun rotMap(isRemote: Boolean) = jsonMap(if (isRemote) K_ROT_REMOTE else K_ROT_LOCAL)

    private fun putRotMap(isRemote: Boolean, m: Map<String, String>) =
        putJsonMap(if (isRemote) K_ROT_REMOTE else K_ROT_LOCAL, m)

    /** 组键 + 朝向 → 存储键（`|f` 前置 / `|b` 后置） */
    private fun rotSlotKey(key: String, front: Boolean): String =
        key.trim() + if (front) "|f" else "|b"

    private fun rotOf(isRemote: Boolean, key: String, front: Boolean): Int {
        val k = key.trim()
        if (k.isEmpty()) return -1
        val m = rotMap(isRemote)
        m[rotSlotKey(k, front)]?.toIntOrNull()?.let { return it }
        // 后置槽缺 → 回落老版的裸键（一次性语义，不写回，避免把前置槽也污染成老值）
        if (!front) m[k]?.toIntOrNull()?.let { return it }
        return -1
    }

    private fun setRotOf(isRemote: Boolean, key: String, front: Boolean, deg: Int) {
        val k = key.trim()
        if (k.isEmpty()) return
        val m = rotMap(isRemote)
        m[rotSlotKey(k, front)] = (if (deg < 0) -1 else ((deg % 360) + 360) % 360).toString()
        putRotMap(isRemote, m)
    }

    /** 该设备「对方画面」记忆角；-1 = 从未手调（渲染侧走自适应基线）。peerFront = 对端此刻用前置摄像头 */
    fun rotRemoteOf(key: String, peerFront: Boolean = false): Int =
        rotOf(true, key, peerFront)

    fun setRotRemoteOf(key: String, deg: Int, peerFront: Boolean = false) =
        setRotOf(true, key, peerFront, deg)

    /** 该设备的「本地小窗」记忆角；语义同 [rotRemoteOf]，front = **本机**当前摄像头 */
    fun rotLocalOf(key: String, front: Boolean = false): Int = rotOf(false, key, front)

    fun setRotLocalOf(key: String, deg: Int, front: Boolean = false) =
        setRotOf(false, key, front, deg)

    /** 一次性迁移：旋转默认基线改版后作废旧记忆角（幂等） */
    private fun resetRotSchemeOnce() {
        if (sp.getBoolean(K_ROT_SCHEME_0922, false)) return
        sp.edit().remove(K_ROT_REMOTE).remove(K_ROT_LOCAL)
            .putBoolean(K_ROT_SCHEME_0922, true).apply()
    }

    /** 一次性迁移：只清「对方画面」记忆角（旧角是在歪基线上手调的，对新默认无意义；本机小窗不动） */
    private fun resetRotRemoteOnce() {
        if (sp.getBoolean(K_ROT_REMOTE_RESET_0922B, false)) return
        sp.edit().remove(K_ROT_REMOTE).putBoolean(K_ROT_REMOTE_RESET_0922B, true).apply()
    }

    /** 一次性播种：老数据（lastId / remark / 组当前地址）提升为组级字段（幂等） */
    private fun seedIdentity() {
        val rows = peers
        if (rows.isEmpty()) return
        // ① 绑定表：从广播/握手写进 peer 行的 lastId 灌进权威绑定表
        val b = bindingsMap()
        var bc = false
        for (p in rows) {
            val id = p.lastId.trim()
            if (id.isNotEmpty() && b[p.ip] != id) {
                b[p.ip] = id
                bc = true
            }
        }
        if (bc) {
            putJsonMap(K_BINDINGS, b)
            keyCache.clear()
        }
        // ② 组备注：老版本的每 IP 备注提升为「该设备的备注」
        val rm = jsonMap(K_REMARKS)
        var rc = false
        for (p in rows) {
            if (p.remark.isBlank()) continue
            val k = convKeyOf(p.ip)
            if (rm[k].isNullOrEmpty()) {
                rm[k] = p.remark
                rc = true
            }
        }
        if (rc) putJsonMap(K_REMARKS, rm)
        // ③ 组当前地址
        val cm = groupCurMap()
        var cc = false
        for (g in groups()) {
            if (cm[g.key].isNullOrEmpty()) {
                cm[g.key] = g.curIp
                cc = true
            }
        }
        if (cc) putJsonMap(K_GROUP_CUR, cm)
    }

    /** 供日志解释「为什么这两个 IP 是一台设备」 */
    fun describeDevice(ip: String): String {
        val v = ip.trim()
        val k = convKeyOf(v)
        val g = groupOf(k)
        val n = g?.ips?.size ?: 1
        return "$v → 设备 ${shortId(k)}（本机该设备共 $n 个地址）"
    }

    private fun defaultName(): String =
        (Build.MODEL ?: "Android").replace(" ", "-").take(20)

    private companion object {
        const val K_ID = "device_id"
        const val K_NAME = "device_name"
        const val K_TCP = "tcp_port2"
        const val K_UDP = "udp_port2"
        const val K_IPS = "peer_ips"
        const val K_PEERS = "peer_list"
        const val K_CLIP_SYNC_MODE = "clip_sync_mode"
        const val K_AUTO_SYNC = "auto_sync_on_send"
        const val K_FORCE = "force_lan_enabled"
        const val K_ROOT_FS = "root_file_access"
        /** 系统选择器授权的目录树：uri / docId / 显示名（Android 17 起是主路径，见 Prefs.safTreeUri） */
        const val K_SAF_TREE_URI = "saf_tree_uri"
        const val K_SAF_TREE_DOC = "saf_tree_doc"
        const val K_SAF_TREE_LABEL = "saf_tree_label"
        /** 主题模式：-1 跟随系统 / 0 浅色 / 1 深色 */
        const val K_THEME_MODE = "theme_mode"
        const val K_CLIP_TOAST = "show_clip_toast"
        const val K_PUSHED_SHA = "clip_last_pushed_sha1"
        const val K_PULLED_SHA = "clip_last_pulled_sha1"
        const val K_LAST_SEL = "last_selected_ip"
        /** 逐项权限询问里选过「不需要」的项 id 集合（见 Prefs.permNeverAsk） */
        const val K_PERM_NEVER = "perm_never_ask"
        /** 刚因授予权限而自杀重启，重启后第一次 onCreate 消费（见 Prefs.permRestartNotice） */
        const val K_PERM_RESTART = "perm_restart_notice"
        /** 「这类文件用这个程序打开」的前缀键，后接 MIME（见 Prefs.openerOf） */
        const val K_OPENER = "opener."
        const val K_VIDEO_ENABLED = "video_enabled"
        const val K_VIDEO_MAX_LEVEL = "video_max_level"
        const val K_VIDEO_REMOTE_ROT = "video_remote_rot"
        const val K_VIDEO_LOCAL_ROT = "video_local_rot"

        // ===== 设备身份与分组 =====
        /** 权威绑定表 ip → 对端 deviceId */
        const val K_BINDINGS = "ip_bindings"
        /** 组备注 deviceId → 备注（仅本机可见） */
        const val K_REMARKS = "device_remarks"
        /** 组当前地址 deviceId → ip */
        const val K_GROUP_CUR = "group_cur_ip"
        /** 网段派生的设备标记：组键 → "1"（不含原因、不含地址、不含时间） */
        const val K_BLOCKED_GROUPS = "group_marks"
        /** 用户明确删掉的别名设备（「同 IP 上另一台设备」那行）→ 不再显示，见 [forgetDev] */
        const val K_IGNORED_DEVS = "group_ignored_devs"
        /** 每设备记忆的旋转角 */
        const val K_ROT_REMOTE = "video_rot_remote_map"
        const val K_ROT_LOCAL = "video_rot_local_map"

        /** 旋转默认方案改版的一次性清除标记 */
        const val K_ROT_SCHEME_0922 = "video_rot_scheme_0922_reset"

        /** 仅清「对方画面」记忆角的一次性标记（发送端 GL 双重旋转修复后） */
        const val K_ROT_REMOTE_RESET_0922B = "video_rot_remote_reset_0922b"
    }
}
