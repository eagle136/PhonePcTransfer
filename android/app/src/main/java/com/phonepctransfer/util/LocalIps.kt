package com.phonepctransfer.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 枚举本机**可以拿去「添加设备」**的地址，供设备列表逐行展示 / 点击复制。
 *
 * 输出顺序（2026-09-15 用户定调）：
 *  1. **局域网 IPv4** —— 192.168.* → 10.* → 172.16-31.* → 其它
 *  2. **169.254.\*** 链路本地 IPv4（Windows 拿不到 DHCP 时的兜底地址，用户要求一并列出，
 *     所以排在最末而不像 [LanGate.localIpv4] 那样直接丢掉）
 *  3. **IPv6 内网地址 1 个** —— fc00::/7（ULA）优先，否则 fe80::/10（链路本地）
 *  4. **IPv6 公网地址** —— 2000::/3（俗称 2xxx / 3xxx 开头），按数值升序
 *
 * **本机在数据网络时**（[list] 的 `includeCellular = true`，2026-09-19 用户要求）额外追加：
 *  5. **蜂窝网卡的 IPv6 公网地址** —— 手机在 4G/5G 下运营商通常下发一个公网 v6，
 *     对方若也在 v6 网络上可以直接用它连过来；不显示的话本机在数据网络下就是「（暂无）」，
 *     用户没东西可复制。
 *
 * 常规网卡黑名单：p2p / tun / tap / dummy / ifb（内核中间层，实际是 rmnet 的兄弟接口，
 * 只有 fe80，会把「内网 v6」那行顶掉）/ sit / ip6tnl（点对点与隧道地址拿给对端连不上）；
 * **蜂窝网卡（rmnet / ccmni / v4-464xlat）默认同样排除** —— 蜂窝 v4 基本在 NAT 后面，
 * 列出来只会误导；只有显式 `includeCellular` 时**只取其中的 IPv6 公网地址**。
 * 同一地址只出现一次；任何异常都返回已收集到的部分（不抛、不影响界面）。
 */
object LocalIps {

    /** 蜂窝 / 464xlat 网卡前缀：默认排除，`includeCellular` 时只取其 IPv6 公网地址 */
    private val CELLULAR = listOf("rmnet", "ccmni", "v4-")

    /** 其它黑名单：点对点、隧道、占位网卡、内核中间层（ifb0/1/2 只有 fe80，会顶掉可用地址） */
    private val EXCLUDED = listOf("p2p", "tun", "tap", "dummy", "ifb", "sit", "ip6tnl")

    /**
     * 排好序的地址列表；读不到就空列表。
     *
     * @param includeCellular 本机是否正在走运营商数据网络。为真时额外收集蜂窝网卡的
     *   **IPv6 公网地址**（链路本地 / ULA / IPv4 仍不列）并放在最末。
     */
    fun list(includeCellular: Boolean = false): List<String> {
        val v4 = LinkedHashSet<String>()
        // v6 一律先归一到「8 组零填充展开式」再存：既天然去重（同一地址的不同写法只留一个），
        // 又是可比较的排序键，输出时再压成 RFC 5952 形式。
        val v6Private = LinkedHashSet<String>()
        val v6Public = LinkedHashSet<String>()
        val v6Cellular = LinkedHashSet<String>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase() ?: continue
                val cellular = CELLULAR.any { name.startsWith(it) }
                if (cellular && !includeCellular) continue
                if (!cellular && EXCLUDED.any { name.startsWith(it) }) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia?.address ?: continue
                    // 去掉 %wlan0 这类 scope id：拿去做「添加设备」的地址必须是不带 scope 的裸地址
                    val host = a.hostAddress?.substringBefore('%')?.trim() ?: continue
                    if (host.isEmpty()) continue
                    when {
                        // 蜂窝 v4 在运营商 NAT 后面，给对端也连不上，一律不列
                        a is Inet4Address -> if (!cellular && !a.isLoopbackAddress) v4.add(host)
                        a is Inet6Address -> {
                            if (a.isLoopbackAddress) continue
                            when {
                                // 蜂窝网卡上的 fe80::/ULA 只在本链路内有意义，对端用不了
                                a.isLinkLocalAddress -> if (!cellular) v6Private.add(v6Key(host))
                                isUla(host) -> if (!cellular) v6Private.add(v6Key(host))
                                isGlobalV6(host) ->
                                    (if (cellular) v6Cellular else v6Public).add(v6Key(host))
                            }
                        }
                    }
                }
            }
        }
        val out = ArrayList<String>(v4.size + v6Private.size + v6Public.size + v6Cellular.size)
        out += v4.sortedWith(compareBy({ v4Rank(it) }, { v4Key(it) }))
        // 「ipv6 内网地址一个」：ULA 与链路本地都算内网，只取排序后的第一个
        out += v6Private.sorted().take(1).map { compressV6(it) }
        out += v6Public.sorted().map { compressV6(it) }
        // 数据网络下的蜂窝 v6 排最后：能连上就用它，别抢局域网地址的位置
        out += v6Cellular.sorted().map { compressV6(it) }
        return out.distinct()
    }

    /** v4 排序优先级：越常用越靠前；169.254 是 DHCP 失败的兜底，压到最后 */
    private fun v4Rank(host: String): Int = when {
        host.startsWith("192.168.") -> 0
        host.startsWith("10.") -> 1
        is172Private(host) -> 2
        host.startsWith("169.254.") -> 9
        else -> 5
    }

    private fun is172Private(host: String): Boolean {
        val p = host.split('.')
        if (p.size != 4 || p[0] != "172") return false
        return (p[1].toIntOrNull() ?: 0) in 16..31
    }

    /** v4 组内按数值排（补零后字典序 = 数值序） */
    private fun v4Key(host: String): String =
        host.split('.').joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(3, '0') }

    /** ULA = fc00::/7（首字节 0xFC / 0xFD） */
    private fun isUla(host: String): Boolean {
        val g = host.substringBefore(':').toIntOrNull(16) ?: return false
        return (g and 0xFE00) == 0xFC00
    }

    /** 公网单播 = 2000::/3（首组 2000..3fff，即用户说的「2xxx 开头」） */
    private fun isGlobalV6(host: String): Boolean {
        val g = host.substringBefore(':').toIntOrNull(16) ?: return false
        return g in 0x2000..0x3FFF
    }

    /** v6 排序键：展开成 8 组零填充，字典序即数值序 */
    private fun v6Key(host: String): String {
        val parts = host.split("::")
        val head = parts[0].split(':').filter { it.isNotEmpty() }
        val tail = if (parts.size > 1) parts[1].split(':').filter { it.isNotEmpty() } else emptyList()
        val groups = if (parts.size > 1) {
            head + List((8 - head.size - tail.size).coerceAtLeast(0)) { "0" } + tail
        } else {
            head
        }
        return groups.take(8).joinToString(":") { it.padStart(4, '0').lowercase() }
    }

    /**
     * 把 [v6Key] 的展开式压成 RFC 5952 惯用写法：每组去前导零，**最长**的一段连续 0 组换成 `::`。
     *
     * 为什么非压不可：Windows / JVM 的 `getHostAddress()` 返回的是**不压缩**的展开式
     * （实测 `2001:db8:614:3ba0:0:0:0:12`），又长又难认；压完是 `2001:db8:614:3ba0::12`，
     * 两端显示口径一致，粘到「＋设备」里也照样能用。
     */
    private fun compressV6(expanded: String): String {
        val g = expanded.split(':').map { s ->
            var k = 0
            while (k < s.length - 1 && s[k] == '0') k++
            s.substring(k)
        }
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        g.forEachIndexed { i, s ->
            if (s == "0") {
                if (curStart < 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        var i = 0
        while (i < g.size) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && sb.last() != ':') sb.append(':')
            sb.append(g[i])
            i++
        }
        return sb.toString()
    }
}
