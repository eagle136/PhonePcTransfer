package com.phonepctransfer.pc;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 本机**可以拿去「添加设备」**的地址枚举 —— 与安卓端 {@code util/LocalIps.kt} 同一套规则，
 * 两端列表内容与顺序保持一致（左栏 / 设备列表里逐行展示，点一下复制）。
 *
 * <p>输出顺序：
 * <ol>
 *   <li><b>局域网 IPv4</b>：192.168.* → 10.* → 172.16-31.* → 其它</li>
 *   <li><b>169.254.*</b> 链路本地 IPv4（拿不到 DHCP 时的兜底地址，用户要求一并列出，故排最末）</li>
 *   <li><b>IPv6 内网地址 1 个</b>：fc00::/7（ULA）优先，否则 fe80::/10（链路本地）</li>
 *   <li><b>IPv6 公网地址</b>：2000::/3（俗称 2xxx / 3xxx 开头），按数值升序</li>
 * </ol>
 *
 * <p>只统计**非蜂窝 / 非隧道**网卡：rmnet / ccmni / v4-* / p2p / tun / tap / dummy / sit / ip6tnl
 * 一律排除（PC 上一般用不到，但保持与安卓端同源，方便对照）。同一地址只出现一次。
 */
public final class LocalIps {

    private LocalIps() {
    }

    /** 网卡名前缀黑名单：蜂窝、464xlat、点对点、隧道、占位网卡 */
    private static final String[] EXCLUDED = {
            "rmnet", "ccmni", "v4-", "p2p", "tun", "tap", "dummy", "sit", "ip6tnl"
    };

    /** 排好序的地址列表；读不到就空列表 */
    public static List<String> list() {
        LinkedHashSet<String> v4 = new LinkedHashSet<String>();
        LinkedHashSet<String> v6Private = new LinkedHashSet<String>();
        LinkedHashSet<String> v6Public = new LinkedHashSet<String>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                if (excluded(name)) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (ia == null) {
                        continue;
                    }
                    InetAddress a = ia.getAddress();
                    if (a == null) {
                        continue;
                    }
                    // 去掉 %wlan0 / %eth0 这类 scope id：拿去「添加设备」的必须是不带 scope 的裸地址
                    String host = a.getHostAddress();
                    if (host == null) {
                        continue;
                    }
                    int pct = host.indexOf('%');
                    if (pct >= 0) {
                        host = host.substring(0, pct);
                    }
                    host = host.trim();
                    if (host.isEmpty()) {
                        continue;
                    }
                    if (a instanceof Inet4Address) {
                        if (!a.isLoopbackAddress()) {
                            v4.add(host);
                        }
                    } else if (a instanceof Inet6Address) {
                        if (a.isLoopbackAddress()) {
                            continue;
                        }
                        // v6 一律先归一到「8 组零填充展开式」再存：天然去重（同一地址的不同写法只留一个），
                        // 又是可比较的排序键；输出时再压成 RFC 5952 形式。
                        if (a.isLinkLocalAddress() || isUla(host)) {
                            v6Private.add(v6Key(host));
                        } else if (isGlobalV6(host)) {
                            v6Public.add(v6Key(host));
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // 枚举网卡失败不影响主流程：返回已收集到的部分
        }

        List<String> out = new ArrayList<String>();
        List<String> v4list = new ArrayList<String>(v4);
        Collections.sort(v4list, new Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                int r = Integer.compare(v4Rank(x), v4Rank(y));
                return r != 0 ? r : v4Key(x).compareTo(v4Key(y));
            }
        });
        out.addAll(v4list);

        // 「IPv6 内网地址一个」：ULA 与链路本地都算内网，只取排序后的第一个
        List<String> priv = new ArrayList<String>(v6Private);
        Collections.sort(priv);
        if (!priv.isEmpty()) {
            out.add(compressV6(priv.get(0)));
        }
        List<String> pub = new ArrayList<String>(v6Public);
        Collections.sort(pub);
        for (String k : pub) {
            out.add(compressV6(k));
        }
        return out;
    }

    private static boolean excluded(String lowerName) {
        for (String pre : EXCLUDED) {
            if (lowerName.startsWith(pre)) {
                return true;
            }
        }
        return false;
    }

    /**
     * **对端**地址是否属于局域网侧 —— 与安卓端 {@code LanGate.isLanPeerIp} 同一条口径
     * （10/8、127/8、192.168/16、169.254/16 + IPv6 ULA/链路本地），段集合必须逐条一致，
     * 否则同一对设备「手机认为能同步、电脑认为不能」，剪贴板会单边失联。
     *
     * <p>{@code ::ffff:a.b.c.d} 先解成 v4 再判：JVM 在双栈机器上常把 v4 会话的对端
     * 报成这种映射形式，直接按 v6 字符串判会漏。
     */
    public static boolean isLanPeer(String host) {
        String v = host == null ? "" : host.trim();
        int pct = v.indexOf('%');
        if (pct >= 0) {
            v = v.substring(0, pct);
        }
        if (v.isEmpty() || "localhost".equals(v)) {
            return true;
        }
        if (v.indexOf(':') >= 0) {
            String low = v.toLowerCase();
            if (low.startsWith("::ffff:")) {
                String mapped = low.substring("::ffff:".length());
                if (mapped.indexOf(':') < 0) {
                    return isLanPeer(mapped);
                }
            }
            return low.startsWith("fc") || low.startsWith("fd") || low.startsWith("fe8")
                    || low.startsWith("fe9") || low.startsWith("fea") || low.startsWith("feb");
        }
        String[] p = v.split("\\.", -1);
        if (p.length != 4) {
            return false;
        }
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(p[i]);
            } catch (Throwable t) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) {
                return false;
            }
        }
        return n[0] == 10 || n[0] == 127 || (n[0] == 192 && n[1] == 168)
                || (n[0] == 169 && n[1] == 254);
    }

    /** v4 排序优先级：越常用越靠前；169.254 是 DHCP 失败的兜底，压到最后 */
    private static int v4Rank(String host) {
        if (host.startsWith("192.168.")) {
            return 0;
        }
        if (host.startsWith("10.")) {
            return 1;
        }
        if (is172Private(host)) {
            return 2;
        }
        if (host.startsWith("169.254.")) {
            return 9;
        }
        return 5;
    }

    private static boolean is172Private(String host) {
        String[] p = host.split("\\.");
        if (p.length != 4 || !"172".equals(p[0])) {
            return false;
        }
        int n;
        try {
            n = Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return false;
        }
        return n >= 16 && n <= 31;
    }

    /** v4 组内按数值排（补零后字典序 = 数值序） */
    private static String v4Key(String host) {
        StringBuilder sb = new StringBuilder();
        String[] p = host.split("\\.");
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            int n;
            try {
                n = Integer.parseInt(p[i]);
            } catch (Throwable t) {
                n = 0;
            }
            String s = Integer.toString(n);
            for (int k = s.length(); k < 3; k++) {
                sb.append('0');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** 取首组（`:` 之前）当 16 进制数；解析不了返回 -1 */
    private static int firstGroup(String host) {
        int idx = host.indexOf(':');
        String g = idx >= 0 ? host.substring(0, idx) : host;
        try {
            return Integer.parseInt(g, 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** ULA = fc00::/7（首字节 0xFC / 0xFD） */
    private static boolean isUla(String host) {
        int g = firstGroup(host);
        return g >= 0 && (g & 0xFE00) == 0xFC00;
    }

    /** 公网单播 = 2000::/3（首组 2000..3fff，即用户说的「2xxx 开头」） */
    private static boolean isGlobalV6(String host) {
        int g = firstGroup(host);
        return g >= 0x2000 && g <= 0x3FFF;
    }

    /** v6 排序键：展开成 8 组零填充，字典序即数值序 */
    private static String v6Key(String host) {
        String[] parts = host.split("::", -1);
        String[] head = parts[0].isEmpty() ? new String[0] : parts[0].split(":");
        String[] tail = new String[0];
        if (parts.length > 1 && !parts[1].isEmpty()) {
            tail = parts[1].split(":");
        }
        List<String> groups = new ArrayList<String>();
        for (String h : head) {
            if (!h.isEmpty()) {
                groups.add(h);
            }
        }
        if (parts.length > 1) {
            int fill = 8 - groups.size() - tail.length;
            for (int i = 0; i < fill; i++) {
                groups.add("0");
            }
        }
        for (String t : tail) {
            if (!t.isEmpty()) {
                groups.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size() && i < 8; i++) {
            if (i > 0) {
                sb.append(':');
            }
            String g = groups.get(i).toLowerCase();
            for (int k = g.length(); k < 4; k++) {
                sb.append('0');
            }
            sb.append(g);
        }
        return sb.toString();
    }

    /**
     * 把 {@link #v6Key} 的展开式压成 RFC 5952 惯用写法：每组去前导零、**最长**的一段连续 0 组换成 `::`。
     *
     * <p>为什么非压不可：JVM 在 Windows 上 `getHostAddress()` 返回的是**不压缩**的展开式
     * （实测 {@code 2001:db8:614:3ba0:0:0:0:12}），又长又难认；压完是
     * {@code 2001:db8:614:3ba0::12}，与安卓端显示口径一致，粘到「＋设备」里照样能用。
     */
    private static String compressV6(String expanded) {
        String[] raw = expanded.split(":");
        String[] g = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i];
            int k = 0;
            while (k < t.length() - 1 && t.charAt(k) == '0') {
                k++;
            }
            g[i] = t.substring(k);
        }
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < g.length; i++) {
            if ("0".equals(g[i])) {
                if (curStart < 0) {
                    curStart = i;
                    curLen = 0;
                }
                curLen++;
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                }
            } else {
                curStart = -1;
                curLen = 0;
            }
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < g.length) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(g[i]);
            i++;
        }
        return sb.toString();
    }
}
