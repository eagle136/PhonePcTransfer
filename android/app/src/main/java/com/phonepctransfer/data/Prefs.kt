package com.phonepctransfer.data

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.phonepctransfer.net.Protocol
import com.phonepctransfer.video.VideoLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 设备列表里的一台对端设备。
 *  - `ip`        连接地址（用户可改）
 *  - `remark`    用户备注名（长按/右键可改）；为空时回退到广播名、再回退到 IP
 *  - `lastId/…`  UDP 自动发现时记录的对方标识与广播名，用于显示与去重
 *  - `devs`      同一个 IP 上出现过的全部设备身份（2026-10-05 同 IP 多设备，含 lastId 那一台）
 */
data class Peer(
    val ip: String,
    val remark: String = "",
    val lastId: String = "",
    val lastName: String = "",
    val lastSeenAt: Long = 0L,
    /**
     * 同一个 IP 上出现过的全部设备身份（含当前 [lastId] 那一台）：id + 广播名 +
     * **它自己**最近一次广播/握手的时间。
     *
     * 为什么需要：两台不同的物理设备可能落在同一个 IP 上（同一路由 DHCP 复用、或者一端
     * 主动连入导致本端只能看到对方的 IP）。旧模型「一个 IP = 一台设备」会让两台设备抢同一行
     * —— 广播每 2s 交替覆盖 lastId，组键来回漂移、备注/当前地址被反复搬运（「切换不过去 + 卡」）。
     * 现在：谁先在这个 IP 上被识别谁就是**活动设备**，后来者只登记在这里当**别名**
     * （列表里显示成「名字-2」那一行），等用户手动确认（断开旧连接）才切换（见 [Prefs.promoteDev]）；
     * 活动设备自己那条记录用来判「它是否已离开」。
     */
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

/**
 * 设备**组**：同一台对端设备（同一个 deviceId）下的全部地址条目。
 *
 * 为什么要有这一层（2026-09-16 用户定调）：
 *  一台设备可能同时有多张网卡 / 多个地址（WiFi 192.168.1.13、热点 192.168.43.1、
 *  USB 网卡…）。旧模型「一个 IP = 一台设备」，于是同一台机器会在列表里出现好几次，
 *  备注要分别设置、聊天记录也被切成好几份。现在：
 *   - 组键 [key] = 对端设备 id（未知设备回退成该 IP，行为与改造前一致）
 *   - 备注挂在**组**上（= 对「对方设备」备注），仅本机可见，绝不上协议
 *   - 聊天记录/待发队列按组键归并（见 Store.keyResolver）
 *   - [curIp] = 本组当前使用的地址（顶栏可切换），连接与发送优先走它
 */
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
    /**
     * 是否是「同 IP 多设备」里的**别名行**（2026-10-05）：这个 IP 当前的活动设备是别人，
     * 本行只是同一地址上出现过的另一台设备。点击时先弹 IP 冲突确认框，不直接连。
     */
    val alt: Boolean = false,
    /**
     * 去重后的列表显示名（同名设备自动加 `-2`/`-3`，见 [Prefs.groups]）。
     * 为空 = 用 remark/name/curIp 的常规回退；非空 = 强制用它（不动用户写的备注）。
     */
    val shownName: String = "",
    /**
     * 该设备**共用**的地址（2026-10-05）：这些地址当前的活动设备是别人，本行只是
     * 「这台设备也在这个地址上出现过」。**只做提示** —— 不进 [ips]、不参与连接目标与地址
     * 切换，也不算「本设备的地址」；想真正切过去要走 IP 冲突确认流程（见 AppCore.connectToGroup）。
     */
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

    /**
     * ip → 组键 的解析缓存。
     *
     * `convKeyOf` 位于**发送热路径**上（语音发送 50 帧/秒都会走 Link.target()），
     * 每次都去解析两份 JSON 会白白浪费主线程/网络线程的时间，故缓存；
     * 任何会改变绑定关系的写操作（绑定 / 增删改设备）都要清表。
     */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // 身份绑定与组备注的一次性播种（老数据升级 + 首次运行）
        runCatching { seedIdentity() }
        // 旋转默认方案 2026-09-22 改版：清掉按旧基线校出来的手调记忆角，让新默认生效
        runCatching { resetRotSchemeOnce() }
        // 同日二次：发送端 GL 双重旋转修复后「对方画面」整体回正，远端旧记忆角一并作废
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

    /**
     * 上次选中的设备 IP：应用启动扫描结束后自动对它尝试一次连接，
     * 这样冷启动后不点设备也能恢复上一次的会话（连不上无害，不重试）。
     */
    var lastSelectedIp: String
        get() = sp.getString(K_LAST_SEL, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_SEL, v).apply()

    /**
     * 逐项权限询问里点过「不需要」的项 id（= [BootPerm.id]，另含 `a11y` 保活那一问）。
     * 命中即冷启动不再问这一项；其它项不受影响，下次照旧提醒。
     * 权限本身仍会在用到时按需申请（通话的录音/相机、装 APK 等）。
     */
    var permNeverAsk: Set<String>
        get() = sp.getStringSet(K_PERM_NEVER, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_PERM_NEVER, v.toSet()).apply()

    /**
     * 「刚因为授予权限而自杀重启」的一次性标记：重启前的进程来不及弹提示（提示要发给系统，
     * 进程却已经死了），所以写在这里，由重启后的第一个 onCreate 读掉并 toast 解释一句。
     */
    var permRestartNotice: Boolean
        get() = sp.getBoolean(K_PERM_RESTART, false)
        set(v) = sp.edit().putBoolean(K_PERM_RESTART, v).apply()

    // ==================== 「这类文件用什么程序打开」的本应用私有记忆 ====================

    /**
     * 用户在选择器里点过的程序，按 MIME 记：键 `opener.<mime>` → `"包名/类名"`。
     *
     * **只写在本应用自己的 SharedPreferences 里，不碰系统的默认应用表**（用户定的「仅自己有效」）：
     * 命中时由 [com.phonepctransfer.util.openReceivedFile] 用**显式组件** intent 直接启动，
     * 系统那边既不会被设默认、也看不出本应用记了什么；重置只需清这几条键。
     */
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

    /**
     * 设备列表：每台对端 = 一个 IP + 备注（可长按修改）。
     * 首次读取时若只有旧版的纯 IP 集合（K_IPS），自动迁移成 Peer（备注留空）。
     */
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

    /**
     * 新增或更新一台设备。
     *
     * 「同 IP 多设备」（2026-10-05）三条规则：
     *  - 来的 id == 该 IP 的活动设备 → 常规刷新（名字 / 时间）；
     *  - 来的 id != 活动设备，且**活动设备还在**（30s 内有过广播/握手）→ 只登记为别名，
     *    **绝不改 lastId / 绑定、不搬组状态**（= 后来者直接跳过；这才是「切换不过去」的根治）；
     *  - 活动设备**已经离开**（≥30s 没动静，通常是 IP 被路由重新分配）→ 允许接管。
     *
     * @return true 表示列表发生了**用户可见的结构变化**（新增 / 备注 / 身份 / 广播名 / 别名）。
     *         纯时间戳刷新不算 —— 对端每 2s 广播一次，算了界面就会刷屏（2026-09-13 用户实测）。
     */
    fun upsertPeer(
        ip: String,
        remark: String? = null,
        lastId: String? = null,
        lastName: String? = null,
        seenAt: Long = 0L
    ): Boolean {
        val v = ip.trim()
        if (v.isEmpty()) return false
        // 自己的 deviceId 不入库：一旦把自己当成对端，链路层就会每 5s 连一次本机 28765
        //（连上 → 同设备双连接 → 去重关掉 → 保活重连），转成停不下来的自连循环
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
        // 别名表同时记**活动设备自己**：它「是否已经离开」只能看它**自己**最近一次广播/握手
        // 的时间，不能拿 peer.lastSeenAt —— 那会被同 IP 另一台设备的广播一直刷新。
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
                // 升级兼容：老配置的旧活动设备补一条，否则接管后它就从列表里消失了；
                // 但它已经太久没出现（多半是换/重装前的 deviceId）时**不补** —— 补了就是幽灵行
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
        // 广播名只跟着**活动设备**走：否则两台同 IP 设备的广播会把这个显示名来回改
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

    /**
     * 在别名表里记/刷新一台设备身份（时间戳每次都刷，静默）。
     *
     * @return `first` = 新列表（无变化时原样返回同一个实例）；`second` = 是否发生**结构变化**
     *         （新出现的身份 / 名字变了）。沿用 lastSeenAt 的老口径：纯时间刷新不算变化。
     */
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

    /**
     * 该地址上的活动设备是否已经离开。
     *
     * 只看**它自己**最近一次广播/握手的时间（别名表里那条），不看 peer.lastSeenAt ——
     * 后者会被同 IP 另一台设备的广播一直刷新，会让「旧设备已关机」永远判不出来。
     */
    private fun devGone(p: Peer, activeId: String): Boolean {
        val seen = p.dev(activeId)?.seenAt ?: p.lastSeenAt
        return seen <= 0L || System.currentTimeMillis() - seen > DEV_TAKEOVER_MS
    }

    /** 接管时「被顶下去的旧活动设备」还值不值得留一条别名行（超过时限 = 多半是旧 deviceId，不留） */
    private val DEV_ALIAS_KEEP_MS = 10 * 60_000L

    /**
     * 别名行的**显示**时限：太久没露过面的「同一 IP 上的另一台设备」不再列出来。
     *
     * 为什么必须有（2026-10-05 用户实测）：同一台手机换/重装后 deviceId 变了，旧身份永远不会再
     * 广播，却可能躺在别名表里 —— 列表上就多一条「永远点不开的幽灵行」，点它还会弹「是否断开
     * 当前设备并切过去」。真正在场的设备每 2s 广播 / 握手一次，记录一直新鲜，不会误藏。
     */
    private val DEV_ALIAS_SHOW_MS = 60 * 60_000L

    /** 某个设备身份是否已经超过 [limitMs] 没出现过 */
    private fun devStaleForAlias(seenAt: Long, limitMs: Long): Boolean =
        seenAt <= 0L || System.currentTimeMillis() - seenAt > limitMs

    // ==================== 别名行「不再显示」（2026-10-05） ====================

    /** 用户明确删掉的别名设备：设备身份 → "1"（见 [forgetDev] / [unignoreDev]） */
    private fun ignoredDevsMap(): MutableMap<String, String> = jsonMap(K_IGNORED_DEVS)

    /**
     * 忘掉「这台设备在这个地址上出现过」的别名记录（别名行长按「不再显示这台设备」）。
     *
     * 必须**同时记进忽略表**：别名行来自对方广播/握手时自报的 id，只删记录的话对方下一次广播
     *（2s 后）就又把它加回来 —— 用户看到的是「怎么删都删不掉」。
     * 它**真的占到某个地址**、或用户手工把地址指给它时，忽略标记自动解除（见 [unignoreDev]）。
     */
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

    /**
     * 解除某个地址的身份绑定（「移除地址 / 删除设备」时调用，见 [removePeer]）。
     *
     * 只动 [K_BINDINGS] 与解析缓存，不碰设备行 —— 调用方自己决定那行留不留。
     */
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
    // 只记「哪台设备被标记」这一件事：不记地址、不记时间、不记次数，也不写任何原因文案
    // —— 命中细节一律不落盘。解除只发生在删除设备（见 [removePeer]），不做自动过期。

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

    /**
     * 从设备列表删掉一个地址，并**一并解除它的身份绑定**。
     *
     * 为什么必须解绑：绑定表是「ip → deviceId」的权威答案，[convKeyOf] 先查它。若只删设备行
     * 而留着绑定，用户之后在「＋设备」里手动把同一个地址加回来时，它**立刻又回到老那台设备的组里**
     * （note：只有等该地址自己广播/握手带上**另一个** id 才会被覆盖）——
     * 表现就是「明明加的是新设备，却并进了别人的聊天记录」。删地址 = 本机不再认为它属于那台设备。
     */
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

    /**
     * 手工添加一个地址。
     *
     * 时间戳写「现在」：手工填的地址在本机从未被广播/握手续过号（`lastSeenAt` 会停在 0），
     * 若写 0，[pruneStaleIps] 会把它当成「很旧」当场清掉 —— 用户刚加完、下次启动就没了。
     * 记成 now = 视为刚刚使用过，受 5 天保护期。
     */
    fun addPeerIp(ip: String): Boolean = upsertPeer(ip, seenAt = System.currentTimeMillis())

    fun removePeerIp(ip: String): Boolean = removePeer(ip)

    /**
     * 启动时清理「长期没用过的地址」。
     *
     * 规则（用户 2026-09-16 定调）：
     *  - 只清理**超过 [keepMin] 条地址的设备组**：只有两条（或更少）的组整体跳过；
     *  - 组内 `lastSeenAt` 早于 `now - maxAgeDays` 天的地址删掉；
     *  - **保底留最近使用的 [keepMin] 条**（外加组内当前使用的那个地址永不删）——
     *    避免「很久没连过的设备被清成空列表」，用户下次手动连同一个地址时列表里还有它。
     *
     * 删除走 [removePeer]（连带解除 `ip → deviceId` 绑定），避免留下指向已删地址的绑定
     * 让之后手动加回同一地址时又并进老设备的聊天记录。
     *
     * @return 实际删掉的地址条数
     */
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

    /**
     * 剪贴板同步模式（总开关，默认开）。
     *
     * 开启后：
     *  - **焦点切回自身**（onResume / onWindowFocus(true)）→ 读取一次本机剪贴板并推送
     *  - 接收纯被动：对端推来的剪贴板直接写入本机，不轮询、不注册变化监听、不主动拉取
     *
     * 关闭后退化为纯手动：只有点「立即推送剪贴板」/「同步」才会动作。
     */
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

    /**
     * 内置文件浏览器：**用 root(su) 直接读 /data/media/0**（= /sdcard 的底层真实目录）。
     *
     * 默认关 —— 手机有「所有文件访问」时不需要它；开了才走 `su`：
     * 好处是连 `/Android/data`、`/Android/obb` 也能浏览（那两处是系统的 FUSE 限制，
     * 光有 MANAGE_EXTERNAL_STORAGE 也读不到）。KernelSU / Magisk 的 su 都支持。
     */
    var rootFs: Boolean
        get() = sp.getBoolean(K_ROOT_FS, false)
        set(v) = sp.edit().putBoolean(K_ROOT_FS, v).apply()

    /**
     * 内置文件浏览器上次由**系统选择器**授权的那棵目录树（2026-10-05 新增）。
     *
     * 为什么要存：Android 17 起「所有文件访问」失效（见 LocalFs.isAndroid17Plus），
     * **系统自带文件选择器授权的目录成了主路径**；不存的话每次打开浏览器都要重新点一遍系统选择器。
     * 三个字段一起用（判空看 uri 与 docId）；授权本身由 `takePersistableUriPermission` 长期持有，
     * 万一被系统回收、目录被删，枚举会**如实报错** → 界面清掉记录并引导重选（不静默）。
     */
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

    /**
     * 主题模式：-1 = 跟随系统（默认），0 = 浅色，1 = 深色。
     * 由主界面底部「主题」按钮写入（点一次即在浅/深之间翻转，并固定下来不再跟随系统）。
     */
    var themeMode: Int
        get() = sp.getInt(K_THEME_MODE, -1)
        set(v) = sp.edit().putInt(K_THEME_MODE, v).apply()

    /** 电脑端是否自动把收到的内容写入剪贴板（提示用，实际由 PC 端控制） */
    var showClipToast: Boolean
        get() = sp.getBoolean(K_CLIP_TOAST, true)
        set(v) = sp.edit().putBoolean(K_CLIP_TOAST, v).apply()

    /**
     * 剪贴板去重哈希持久化。
     * 进程重启后若本机剪贴板内容与上次已同步过的一致，就不再重复推送——
     * 否则每次冷启动都会把残留的剪贴板再回推一次（形成无意义的回声）。
     */
    var lastPushedSha1: String
        get() = sp.getString(K_PUSHED_SHA, "") ?: ""
        set(v) = sp.edit().putString(K_PUSHED_SHA, v).apply()

    var lastPulledSha1: String
        get() = sp.getString(K_PULLED_SHA, "") ?: ""
        set(v) = sp.edit().putString(K_PULLED_SHA, v).apply()

    // ==================== 视频通话 ====================

    /**
     * 视频通话总开关（**全局回退开关**）。
     * 置 false 时视频相关代码完全不激活，行为与改造前的纯语音通话一致。
     */
    var videoEnabled: Boolean
        get() = sp.getBoolean(K_VIDEO_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_VIDEO_ENABLED, v).apply()

    /**
     * 视频档位上限（0 = 流畅 480P/500k，1 = 标清 720P/1.5M，2 = 高清 1080P/2M，
     * 3 = 超清 1080P/5M —— 表在 [VideoLevel]，10-03 由三档加到四档）。
     * 实际使用档位 = min(本机上限, 对端上限, 用户发起时选的档位)。
     * 这里与选档框选的档都是**上限**，不是起始档（起始最高只到 [VideoLevel.DEFAULT]，
     * 见 `CallManager.startVideo`）。
     * ⚠ 默认值与钳位都写 [VideoLevel.MAX].idx，不写字面量 2：上一版这里钉死 2，加档时
     *   「超清」会被静默夹回高清，而选档框照样列得出来（那才是最难查的一种"选了没用"）。
     */
    var videoMaxLevel: Int
        get() = sp.getInt(K_VIDEO_MAX_LEVEL, VideoLevel.MAX.idx)
        set(v) = sp.edit().putInt(K_VIDEO_MAX_LEVEL, v.coerceIn(0, VideoLevel.MAX.idx)).apply()

    /**
     * 「旋转对方画面」钮记忆的**最终显示角**（顺时针 0/90/180/270）。
     * **-1 = 从未手调过** → 渲染侧走自适应基线（`video_cfg.rot` + 公共基线）。
     *
     * 2026-09-16 用户定调「记住上次视频旋转的角度，不要自动复原」：旧版记的是
     * 叠加修正量（trim），但基准角（对端 `video_cfg.rot`、采集重启后的
     * `rotationDegrees`）在通话间会漂 —— 记叠加量对不上号，等于每次都要重按。
     * 改记**绝对角度**：按钮那刻画面转到的最终角被持久化，之后每次通话直接钉死。
     */
    var videoRemoteRot: Int
        get() = sp.getInt(K_VIDEO_REMOTE_ROT, -1)
        set(v) = sp.edit().putInt(K_VIDEO_REMOTE_ROT, if (v < 0) -1 else ((v % 360) + 360) % 360).apply()

    /** 「旋转小窗」钮记忆的最终显示角（语义同 [videoRemoteRot]，-1 = 未手调） */
    var videoLocalRot: Int
        get() = sp.getInt(K_VIDEO_LOCAL_ROT, -1)
        set(v) = sp.edit().putInt(K_VIDEO_LOCAL_ROT, if (v < 0) -1 else ((v % 360) + 360) % 360).apply()

    // ==================== 设备身份（deviceId）与设备组 ====================
    //
    // 身份来源：本机 id = [deviceId]（首次启动随机生成，卸载前不变）；
    // 对端 id 由 UDP 广播与 TCP hello 的 `id` 字段带过来——**不需要改协议**。
    //
    // 绑定规则（2026-09-16 用户定调）：
    //   连接对方时它会发送一次自己的 id → 本机把它绑到当前 IP 上（[bindDevice]）。
    //   多个 IP 绑到同一个 id → 自动归成一组；备注按**设备**（组）记，仅本机可见。

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

    /**
     * 把某个 IP 绑定到对端设备 id 上。
     *
     * 这是「连接时对方发送一次自己的 id，本机自动绑定到当前 IP」的落点：一对多绑定
     * （同一 id 多个 IP）= 自动成组。
     *
     * @return true 表示绑定关系发生了变化（调用方据此刷新界面）
     */
    fun bindDevice(ip: String, deviceId: String): Boolean {
        val v = ip.trim()
        val d = deviceId.trim()
        if (v.isEmpty() || d.isEmpty()) return false
        // 绑定**前**这个地址的组键：若它自成一组（= 地址自身，例如手工添加、还没连过的行），
        // 绑上之后这一组就要并进设备 d —— 组上挂的本机状态（备注 / 当前地址 / 旋转角）要跟着搬。
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

    /**
     * 握手 / 发现带来的**被动绑定**：只有当目标地址**还没有别的活动设备**（或那台已经离开）时才真的绑定。
     *
     * 为什么需要（2026-10-05 同 IP 多设备）：两台设备落在同一个 IP 上时，双方每次握手/广播都会
     * 自报自己的 id —— 若照旧无条件绑定，两个 id 会每 2s 轮流覆盖 `bindings[ip]`，组键来回漂移、
     * 备注/当前地址被反复搬运（用户看到的「切换不过去 + 卡」）。被动路径必须让**先来的那台守住地址**，
     * 后来者只登记成别名（列表里的「名字-2」那行），要不要换由用户点那行时确认
     *（见 AppCore.connectToGroup / confirmConflictSwitch）。
     *
     * 用户手工声明「这个地址也属于某台设备」（[Prefs.bindDevice] 的其它调用点）仍是强绑定 ——
     * 那是明确意图，不是被动发现。
     *
     * @return true 表示绑定关系发生了变化
     */
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

    /**
     * 把一个组键上挂的本机状态搬到另一个组键（新地址连上后被并进已有设备时调用）。
     *
     * 场景：用户手工加过一个地址 X（当时它自成一组，组键 = X），之后 X 连上来、对端自报的
     * deviceId 是本机早就认识的设备 A —— 绑定表把 X 并进 A。若不做这一步，用户给 X 写过的
     * 备注、X 记的「当前地址」、X 上的视频旋转角都会留在旧组键 X 上取不出来
     *（表现：备注没了 / 旋转角归零）。
     *
     * **目标组已有值时不覆盖**（A 的备注优先于 X 的备注），只补空缺。
     */
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

        // 设备标记也挂在组键上：换绑时不搬过去，这台设备一改地址就脱离标记（组内扩散失效）
        val marks = jsonMap(K_BLOCKED_GROUPS)
        if (marks.remove(oldKey) != null && !marks.containsKey(newKey)) marks[newKey] = "1"
        putJsonMap(K_BLOCKED_GROUPS, marks)

        // 每设备的视频旋转角（R5）同样是按组键存的；2026-09-19 起每个组键下还有
        // 「前置/后置」两个槽（`组键|f` / `组键|b`），**后缀必须跟着一起搬**，
        // 只搬裸键的话换绑后两套角会整片丢失（表现：旋转角突然回到自适应基线）。
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

    /**
     * 会话组键：对端 deviceId（已知时），否则回退该 IP。
     * 聊天记录/待发队列/消息归属一律用它，不看具体 IP。
     */
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

    /**
     * 全部设备组（一设备一组，按设备列表出现顺序）。
     *
     * 主组之后还会追加「同 IP 多设备」的**别名行**（[PeerGroup.alt]，2026-10-05），
     * 最后统一做同名去重（第 2/3 台同名 → `名字-2`/`名字-3`）。
     *
     * @param isConnected 可选：判断某个地址当前是否有健康会话（界面打绿点用）
     */
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
        // ===== 同 IP 多设备（2026-10-05）：把每个地址上「非活动设备」的身份也列出来 =====
        // 硬约束：**一台设备只能出一行**（行键 = 设备身份）。重复行键会让
        // `LazyColumn(items(key = ...))` **直接抛异常**，界面也会出现同一台设备两行。
        //   · 已有主行（在别的地址上是活动设备）→ 只往那一行补 guestIps（纯提示，不参与连接/切换）；
        //   · 一个地址都没占到的「纯客人」→ 才单独开别名行（点击走 IP 冲突确认框）。
        // 于是主行键 = 活动设备身份（未识别时是原始 IP）、别名行键 = 没占到任何地址的设备身份，
        // 两类键天然不可能重合 —— 唯一性由构造保证。
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
                // 太久没露面的别名（多半是换/重装前的旧身份）不再显示；它一旦再广播/握手，
                // 记录立刻变新鲜，这行会自己回来（见 DEV_ALIAS_SHOW_MS）
                if (devStaleForAlias(d.seenAt, DEV_ALIAS_SHOW_MS)) continue
                val oi = out.indexOfFirst { it.key == d.id }
                if (oi >= 0) {
                    // 已经有主行 → 只补一条「共用地址」提示，绝不开第二行
                    //（自己 ips 里已有的地址不算「共用」—— 那是配置里绑定与 peer.lastId 不一致时的兜底）
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

    /**
     * 同名去重：列表里第 2/3… 台同名设备显示成 `名字-2`、`名字-3`。
     *
     * 只写 [PeerGroup.shownName]，不动 remark（用户备注）/ name（广播名）——
     * 备注编辑、地址切换、气泡归属文案都读原始字段，不会被后缀污染。
     */
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

    /**
     * 把某个地址的**活动设备**切换成另一台（同 IP 多设备冲突：用户确认「断开之前的连接」后调用）。
     *
     * 为什么要显式切换：同一个 IP 在任一瞬间只能对应一台设备（[convKeyOf] 在发送热路径上，
     * 只能给单值答案）。列表里的「别名行」点击时弹冲突确认框，用户点「是」之后就走这里：
     * 旧活动设备降级为别名（随时能再切回来）、新设备升为活动设备，并把挂在旧组键上的本机状态
     * （备注 / 当前地址 / 旋转角 / 设备标记）搬到新组键上 —— 否则用户写完的备注、选过的地址
     * 会留在旧设备那边取不出来。
     *
     * @return true 表示切换成功（地址或身份不合法时返回 false）
     */
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
        // 升级兼容：旧活动设备还没进别名表时补一条，否则切换后它就从列表里消失了
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
        // 这个地址从现在起属于新设备（顶栏地址菜单 / 下次启动自动连接都读它）
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

    /**
     * 写「对某设备」的备注（组级）。
     * 同组所有 peer 行一起同步，保证任何旧读法拿到同一个答案。
     */
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
    // 用户定调：「对于每个设备聊天窗口，记住旋转角度，只要不卸载客户端就一直沿用」。
    // 旧版是**全局**两个角度，换设备就串味；现在按组键（= 设备）分别记。
    //
    // 2026-09-19 再分一层：**按摄像头朝向分两套**（前置 / 后置各自独立保存）。
    // 同一台机器的前后摄传感器朝向、镜像规则都可能不同，一套角喂两颗摄像头必然有一颗不对；
    // 存储上就是在组键后面拼朝向后缀（`组键|f` / `组键|b`），键空间不变，一眼能看出归属。
    // **兼容**：老数据只有不带后缀的裸键 → 只提升成**后置**（默认摄像头就是后置，
    // 前端「前置默认多 180°」那条规则也依赖前置槽为空）。

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

    /**
     * 该设备的「对方画面」记忆角；-1 = 从未手调（渲染侧走自适应基线）。
     *
     * @param peerFront 对端此刻用的是不是前置摄像头（两套配置各存各的）
     */
    fun rotRemoteOf(key: String, peerFront: Boolean = false): Int =
        rotOf(true, key, peerFront)

    fun setRotRemoteOf(key: String, deg: Int, peerFront: Boolean = false) =
        setRotOf(true, key, peerFront, deg)

    /** 该设备的「本地小窗」记忆角；语义同 [rotRemoteOf]，front = **本机**当前摄像头 */
    fun rotLocalOf(key: String, front: Boolean = false): Int = rotOf(false, key, front)

    fun setRotLocalOf(key: String, deg: Int, front: Boolean = false) =
        setRotOf(false, key, front, deg)

    /**
     * 一次性迁移（2026-09-22）：旋转默认基线改版（对端前置去 180 / 本机前置加 180），
     * 旧方案下点旋转钮留下的记忆角全部作废——不清掉会一直盖住新默认。幂等。
     */
    private fun resetRotSchemeOnce() {
        if (sp.getBoolean(K_ROT_SCHEME_0922, false)) return
        sp.edit().remove(K_ROT_REMOTE).remove(K_ROT_LOCAL)
            .putBoolean(K_ROT_SCHEME_0922, true).apply()
    }

    /**
     * 一次性迁移（2026-09-22 二次，发送端 GL 修复后）：只清「对方画面」记忆角。
     *
     * 背景：发送端 `sendCfgNow()` 里残留的 `glPipe.rotationDeg = rotationDegrees()` 让编码数据
     * **多转了 90°**（后置）—— 用户是在这个歪掉的默认上手调旋转钮的，那些角度对新默认
     * （正立）毫无意义，留着会一直盖住默认基线。本机小窗基线没变，**不动**它的记忆角。
     * 幂等。
     */
    private fun resetRotRemoteOnce() {
        if (sp.getBoolean(K_ROT_REMOTE_RESET_0922B, false)) return
        sp.edit().remove(K_ROT_REMOTE).putBoolean(K_ROT_REMOTE_RESET_0922B, true).apply()
    }

    /**
     * 一次性播种：把老数据（peer 行的 lastId / remark / 没有组当前地址）提升成组级字段。
     * 幂等，重复调用无副作用。
     */
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

        // ===== 设备身份与分组（2026-09-16）=====
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

        /** 旋转默认方案 2026-09-22 改版的一次性清除标记 */
        const val K_ROT_SCHEME_0922 = "video_rot_scheme_0922_reset"

        /** 2026-09-22 二次（发送端 GL 双重旋转修复）：仅清「对方画面」记忆角的一次性标记 */
        const val K_ROT_REMOTE_RESET_0922B = "video_rot_remote_reset_0922b"
    }
}
