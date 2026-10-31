package com.phonepctransfer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 门禁：只有在「WiFi 已连接」或「本机热点已开启」或「有线以太网」时才允许传输。
 *
 * 三级判定，任一命中即放行：
 *  1. ConnectivityManager 的活动网络具备 TRANSPORT_WIFI / ETHERNET
 *  2. 反射 WifiManager.isWifiApEnabled() / getWifiApState()（Android 12 上是隐藏 API，失败不影响）
 *  3. 遍历网卡，存在 ap* / swlan* / softap* / wlan1 等热点桥接口且 isUp
 */
class LanGate(private val ctx: Context) {

    data class Info(
        val available: Boolean,
        /** WIFI / HOTSPOT / ETHERNET / NONE */
        val kind: String,
        val detail: String
    )

    fun check(force: Boolean = false): Info {
        if (force) return Info(true, "FORCED", "已强制启用（调试开关）")
        if (wifiOrEthernet()) return Info(true, "WIFI", "WiFi/以太网已连接")
        if (hotspotEnabled()) return Info(true, "HOTSPOT", "本机热点已开启")
        if (hotspotInterfaceUp() != null) return Info(true, "HOTSPOT", "检测到热点网卡")
        return Info(false, "NONE", "未连接 WiFi/热点（当前为数据网络）")
    }

    /**
     * 本机当前是否走**运营商数据网络**（无 WiFi / 以太网 / 热点）。
     *
     * 这是「使用数据流量」的唯一判据（v2.7）：发文件 / 语音 / 视频前，只有本机真的在
     * 蜂窝数据上才需要提醒本机用户 —— 与对端 IP 属于哪个网段无关（那只能说明
     * 「这条链路没走局域网」，不能说明「本机在花流量」）。
     *
     * @param force 调试开关（设置里的「强制启用」）为真时一律视为不在数据网络。
     */
    fun onCellular(force: Boolean = false): Boolean {
        if (force) return false
        if (wifiOrEthernet()) return false
        if (hotspotEnabled()) return false
        if (hotspotInterfaceUp() != null) return false
        return true
    }

    private fun wifiOrEthernet(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun hotspotEnabled(): Boolean {
        val wm = runCatching {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return false
        runCatching {
            val m = WifiManager::class.java.getMethod("isWifiApEnabled")
            m.isAccessible = true
            (m.invoke(wm) as? Boolean)?.let { return it }
        }
        runCatching {
            val m = WifiManager::class.java.getMethod("getWifiApState")
            m.isAccessible = true
            val st = (m.invoke(wm) as? Int) ?: -1
            // 13 = WIFI_AP_STATE_ENABLED
            if (st == 13) return true
        }
        return false
    }

    private fun hotspotInterfaceUp(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val n = nif.name.lowercase()
            val isHotspot = n.startsWith("ap") || n.contains("swlan") || n.contains("softap") ||
                n == "wlan1" || (n.startsWith("wlan") && n != "wlan0")
            if (isHotspot) return@runCatching nif.name
        }
        null
    }.getOrNull()

    /** 本机用于展示的 IPv4（优先非回环、非蜂窝的 wlan/ap 接口） */
    fun localIpv4(): String = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching "-"
        var fallback = "-"
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val n = nif.name.lowercase()
            if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("p2p")) continue
            for (ia in nif.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    if (n == "wlan0" || n.startsWith("ap") || n.startsWith("eth")) return@runCatching a.hostAddress ?: "-"
                    if (fallback == "-") fallback = a.hostAddress ?: "-"
                }
            }
        }
        fallback
    }.getOrDefault("-")

    companion object {
        private const val TAG = "PP-LanGate"

        fun log(s: String) = Log.i(TAG, s)

        /**
         * 对端 IP 是否属于「局域网侧」：私有 IPv4 段（10/8、172.16/12、192.168/16、
         * 链路本地 169.254/16、回环）+ IPv6 ULA/链路本地。
         *
         * **只在兼容旧版本对端时使用**（v2.7）：老端没有 `dm` 能力位、不会回
         * `data_ans`，只能沿用改造前的「对端不在局域网段 → 提示本机用户」这条老口径。
         * 新流程一律按 [onCellular]（本机是否在用流量）判定。
         */
        fun isLanPeerIp(ip: String): Boolean {
            val v = ip.substringBefore('%').trim()
            if (v.isEmpty() || v == "localhost") return true
            if (v.contains(':')) {
                val low = v.lowercase()
                return low.startsWith("fc") || low.startsWith("fd") || low.startsWith("fe8") ||
                    low.startsWith("fe9") || low.startsWith("fea") || low.startsWith("feb")
            }
            val p = v.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it < 0 || it > 255 }) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) ||
                (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
        }
    }
}
