import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成物校验器（不进 app，也不进 jar；只在沙箱里跑）。
 *
 * 参照实现刻意与产物无关：段表用 InetAddress + BigInteger 解析，命中判定用逐段线性比对，
 * 因此「产物 == 参照」这条断言不是自我循环。
 *
 * 用法：java -cp build;build/pc VerifyBlocklist <blocklist.txt> <cn_ipv6.txt>
 */
public class VerifyBlocklist {

    static final BigInteger ALL = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    static class Seg {
        BigInteger start;
        int plen;

        Seg(BigInteger s, int p) {
            start = s;
            plen = p;
        }

        BigInteger hostMask() {
            return ALL.shiftRight(128 - plen);
        }
    }

    static int fails = 0;
    static int checks = 0;

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            fails++;
            if (fails <= 40) {
                System.out.println("[FAIL] " + what);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: VerifyBlocklist <blocklist.txt> <cn_ipv6.txt>");
            return;
        }
        List<Seg> segs = load(args[0]);
        System.out.println("[INFO] 清单段数=" + segs.size()
                + " 产物 SEGMENT_COUNT=" + com.phonepctransfer.pc.BlocklistIpv6.SEGMENT_COUNT
                + " 产物自检=" + com.phonepctransfer.pc.BlocklistIpv6.segmentCount());
        check(segs.size() == com.phonepctransfer.pc.BlocklistIpv6.SEGMENT_COUNT, "段数与产物常量不一致");
        check(com.phonepctransfer.pc.BlocklistIpv6.segmentCount() == segs.size(), "产物内数组条数不一致");

        // 1) 清单里不能有与硬豁免重叠的段（否则等于自己给自己挖坑）
        for (Seg s : segs) {
            check(!exempt(s.start), "清单段首落在硬豁免区: " + toV6(s.start));
        }

        // 2) 全量正例 + 每段边界前后：产物必须与参照逐项一致
        int n = segs.size();
        long[] sh = new long[n];
        long[] sl = new long[n];
        long[] mh = new long[n];
        long[] ml = new long[n];
        for (int i = 0; i < n; i++) {
            Seg s = segs.get(i);
            sh[i] = s.start.shiftRight(64).longValue();
            sl[i] = s.start.and(ALL.shiftRight(64)).longValue();
            long[] m = new long[2];
            masks(s.plen, m);
            mh[i] = m[0];
            ml[i] = m[1];
        }
        int boundaryDiff = 0;
        for (Seg s : segs) {
            BigInteger lo = s.start, hi = s.start.or(s.hostMask());
            BigInteger[] samples = {
                    lo,                              // 段首
                    hi,                              // 段尾
                    lo.signum() > 0 ? lo.subtract(BigInteger.ONE) : null,
                    hi.compareTo(ALL) < 0 ? hi.add(BigInteger.ONE) : null,
            };
            for (BigInteger a : samples) {
                if (a == null) {
                    continue;
                }
                boolean ref = refBlocked(a, sh, sl, mh, ml);
                boolean got = com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(toV6(a));
                if (ref != got) {
                    boundaryDiff++;
                    check(false, "产物/参照不一致 " + toV6(a) + " ref=" + ref + " got=" + got);
                }
            }
            check(com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(toV6(lo)), "段首未命中 " + toV6(lo));
            check(com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(toV6(hi)), "段尾未命中 " + toV6(hi));
        }
        System.out.println("[OK] 全量段首/段尾 + 边界外一格 比对完成，不一致=" + boundaryDiff);

        // 3) 硬豁免
        String[] exemptCases = {
                "::ffff:127.0.0.1", "::ffff:192.168.1.5", "::ffff:10.0.0.1",
                "::1", "::", "fe80::1", "fe80:0:0:0:abcd::1", "fd00::1", "fc12::34",
                "127.0.0.1", "192.168.1.5", "10.10.10.10", "", "not-an-ip", ":::x",
                "2001:db8::1%eth0",
        };
        for (String a : exemptCases) {
            check(!com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(a), "豁免/非法输入被判命中: [" + a + "]");
        }
        System.out.println("[OK] 豁免与非法输入 " + exemptCases.length + " 例全部为 false");

        // 4) 带 %zone、大小写混写、压缩零 三种写法要判成同一个结果
        BigInteger base = segs.get(0).start;
        String plain = toV6(base);
        String zone = plain + "%wlan0";
        String upper = plain.toUpperCase();
        String compressed = compress(plain);
        boolean p0 = com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(plain);
        check(p0, "首段段基本身未命中: " + plain);
        check(com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(zone) == p0, "%zone 形式判定不一致: " + zone);
        check(com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(upper) == p0, "大写形式判定不一致: " + upper);
        check(com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(compressed) == p0, "压缩零形式判定不一致: " + compressed);
        System.out.println("[OK] 同址多写法一致: " + plain + " / " + compressed + " / " + zone);

        // 5) 国内参考集反向断言（只允许两处已定稿冲突父段）
        String[] conflictParents = {"2401:5560::/32", "2405:1640::/32"};
        List<Seg> cn = load(args[1]);
        int cnHit = 0;
        List<String> offenders = new ArrayList<String>();
        for (Seg s : cn) {
            String a = toV6(s.start);
            if (com.phonepctransfer.pc.BlocklistIpv6.isHighRisk(a)) {
                cnHit++;
                if (!insideAny(a, conflictParents)) {
                    offenders.add(a + "/" + s.plen);
                }
            }
        }
        System.out.println("[INFO] cn_ipv6 段数=" + cn.size() + " 判命中=" + cnHit
                + " 其中落在冲突父段之外=" + offenders.size());
        for (String o : offenders) {
            check(false, "国内段被误拦（非已定稿冲突段）: " + o);
        }

        // 6) 词表正/反例 + 语料命中率报表
        keywords();

        // 7) 剪贴板局域网门控判据（PC 侧 LocalIps.isLanPeer，口径必须与安卓端 LanGate.isLanPeerIp 一致）
        lanPeer();

        System.out.println("[RESULT] 断言 " + checks + " 条，失败 " + fails + " 条");
        if (fails > 0) {
            System.exit(1);
        }
    }

    // ---------- IPv6 参照实现 ----------

    /** 参照侧命中：对段表逐性线性扫描 + 按前缀长度掩码比对（不用产物的分组二分） */
    static boolean refBlocked(BigInteger v, long[] sh, long[] sl, long[] mh, long[] ml) {
        if (v == null || exempt(v)) {
            return false;
        }
        long hi = v.shiftRight(64).longValue();
        long lo = v.and(ALL.shiftRight(64)).longValue();
        for (int i = 0; i < sh.length; i++) {
            if ((hi & mh[i]) == (sh[i] & mh[i]) && (lo & ml[i]) == (sl[i] & ml[i])) {
                return true;
            }
        }
        return false;
    }

    static void masks(int plen, long[] out) {
        if (plen >= 64) {
            out[0] = -1L;
            out[1] = plen == 64 ? 0L : (-1L << (128 - plen));
        } else {
            out[0] = -1L << (64 - plen);
            out[1] = 0L;
        }
    }

    static boolean exempt(BigInteger v) {
        if (v.equals(BigInteger.ZERO) || v.equals(BigInteger.ONE)) {
            return true;
        }
        BigInteger hi64 = v.shiftRight(64);
        if (hi64.and(BigInteger.valueOf(0xFFC0L)).equals(BigInteger.valueOf(0xFE80L))) {
            return true;                       // fe80::/10
        }
        if (hi64.and(BigInteger.valueOf(0xFE00L)).equals(BigInteger.valueOf(0xFC00L))) {
            return true;                       // fc00::/7
        }
        // ::ffff:x.x.x.x（高 80 位为 0，其后 16 位为 FFFF）
        return v.shiftRight(32).equals(BigInteger.valueOf(0xFFFFL));
    }

    /** 独立解析：交给 JDK，再拼成 128 位无符号整数；纯 IPv4 返回 null */
    static BigInteger parseAddr(String text) throws Exception {
        String s = text.trim();
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.isEmpty() || s.indexOf(':') < 0) {
            return null;
        }
        InetAddress ia = InetAddress.getByName(s);
        byte[] b = ia.getAddress();
        if (b.length == 4) {
            return null;
        }
        if (ia instanceof Inet4Address) {
            return null;
        }
        return new BigInteger(1, b);
    }

    static List<Seg> load(String file) throws Exception {
        List<Seg> out = new ArrayList<Seg>();
        for (String line : Files.readAllLines(Paths.get(file))) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            Seg s = parseSeg(t);
            if (s != null) {
                out.add(s);
            } else {
                System.out.println("[WARN] 无法解析: " + t);
            }
        }
        return out;
    }

    static Seg parseSeg(String cidr) throws Exception {
        String[] p = cidr.split("/", -1);
        BigInteger v = parseAddr(p[0]);
        if (v == null) {
            return null;
        }
        int plen = Integer.parseInt(p[1].trim());
        BigInteger host = ALL.shiftRight(128 - plen);
        if (!v.and(host).equals(BigInteger.ZERO)) {
            return null;                       // 未对齐
        }
        return new Seg(v, plen);
    }

    /** 该地址是否落在给定若干 CIDR 之一内（用于「已定稿冲突父段」例外） */
    static boolean insideAny(String addr, String[] cidrs) throws Exception {
        BigInteger v = parseAddr(addr);
        if (v == null) {
            return false;
        }
        for (String c : cidrs) {
            Seg s = parseSeg(c);
            if (s == null) {
                continue;
            }
            BigInteger m = ALL.shiftLeft(128 - s.plen).and(ALL);
            if (v.and(m).equals(s.start)) {
                return true;
            }
        }
        return false;
    }

    /** 128 位 → 全展开 8 组文本（产物解析器不需要压缩形式） */
    static String toV6(BigInteger v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) {
            long g = v.shiftRight(i * 16).and(BigInteger.valueOf(0xFFFFL)).longValue();
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(Long.toHexString(g));
        }
        return sb.toString();
    }

    /** 把全展开地址压成 :: 形式（找一个最长的全零游程） */
    static String compress(String full) {
        String[] g = full.split(":", -1);
        int bestStart = -1, bestLen = 0, cs = -1, cl = 0;
        for (int i = 0; i < g.length; i++) {
            if (g[i].equals("0")) {
                if (cs < 0) {
                    cs = i;
                    cl = 0;
                }
                cl++;
                if (cl > bestLen) {
                    bestLen = cl;
                    bestStart = cs;
                }
            } else {
                cs = -1;
            }
        }
        if (bestStart < 0) {
            return full;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Long.toHexString(Long.parseLong(g[i], 16)));
        }
        return sb.toString();
    }

    // ---------- 剪贴板局域网门控（PC LocalIps.isLanPeer ↔ 安卓 LanGate.isLanPeerIp）----------

    /**
     * 判据表按安卓端 LanGate.kt:124–136 逐条抄：10/8、127/8、192.168/16、169.254/16
     * + v6 ULA(fc00::/7)/链路本地(fe80::/10) + ::ffff: 映射先解回 v4。
     * 两端不一致会出现「手机肯同步、电脑不肯」的单边失联，所以这里按同一张表断言。
     */
    static void lanPeer() {
        String[] lan = {
                "192.168.1.5", "10.1.2.3", "127.0.0.1", "169.254.10.20", "localhost", "",
                "  192.168.1.5  ", "192.168.001.5",
                "fc00::1", "fd12:3456::78", "FE80::1", "fe80:0:0:0:1:2:3:4", "fe80::1%wlan0",
                "::ffff:192.168.1.5", "::ffff:10.0.0.1", "::ffff:127.0.0.1",
        };
        String[] wan = {
                "8.8.8.8", "1.2.3.4", "2001:4860:4000::1", "2001:db8:614:3ba0::12",
                "2001:df0:5f80::", "::ffff:8.8.8.8", "::1",
                "172.16.0.1", "172.31.0.1",
                "abc", "256.1.1.1", "192.168.1", "192.168.1.5.7", "192.168.x.5",
        };
        for (String ip : lan) {
            check(com.phonepctransfer.pc.LocalIps.isLanPeer(ip), "局域网地址被判非局域网: [" + ip + "]");
        }
        for (String ip : wan) {
            check(!com.phonepctransfer.pc.LocalIps.isLanPeer(ip), "非局域网地址被判局域网: [" + ip + "]");
        }
        System.out.println("[OK] 剪贴板局域网判据 " + (lan.length + wan.length) + " 例与安卓口径一致"
                + "（含 172.16/12 两端都不认、::1 两端都不认）");
    }

    // ---------- 词表 ----------

    static void keywords() {
        // 逐条写死预期：正例必须丢，反例必须留
        String[] mustFilter = {
                "btc 138001380001234",
                "USDT 138001380001234",
                "博彩138001380001234",
                "荐股 138001380001234",
                "杀猪盘 138001380001234",
                "内幕消息 138 0013 8000 1234",
                "虚拟币 １３８００１３８０００１２３４",
                "博彩 138 0013 8000 1234",
                "充值送 1380013800012345",
                "稳赚 138001380001234 加我",
                "缅甸 138001380001234",
                "理财 138001380001234",
                "投资 138001380001234",
                "跟单 138001380001234",
                "区块链 138001380001234",
                "炒股的 138001380001234",
                "包赢 138001380001234",
                "返水 138001380001234",
                "买彩票 138001380001234",
                "开盘号码 138001380001234",
                "老师带单 138001380001234",
                "杠杆爆仓回本 138001380001234",
                "赌场 138001380001234",
                "网赌 138001380001234",
                "比特币 138001380001234",
                "泰达币 138001380001234",
                "加密货币 138001380001234",
                "虚拟货币 138001380001234",
                "个股推荐 138001380001234",
                "带你炒 138001380001234",
        };
        String[] mustKeep = {
                "btc 13800138000",                     // 数字段只有 11 位
                "今天btc行情怎么样",                     // 无数字
                "我转账给你了 1380013",                  // 7 位
                "投资这条路很长，慢慢来",                  // 无数字
                "缅甸的朋友新年快乐",                     // 无数字
                "138001380001234",                    // 纯数字无词
                "订单号 13800138000123456789",          // 无词
                "理财课今天讲到第三章，作业是第 12 题",       // 词有、数字短
                "荐股",                                 // 只有词
                "138 0013 8000 1234",                 // 数字够长、无词
                "这是很长的文本用来验证三十字上限的口径判断是否正确生效的情况说明示例补充",  // >=30 字
                "btc 138 0013 8000 1234 这条太长所以不判",  // >=30 字，长度闸门挡住
                "明天 10 点开会记得带电脑",
                "彩票站地址在楼下",
                "快递单号 SF1380013800012",
                " casino 138001380001234",            // 词表里没有 casino
                "转账 138-0013-8000-1234",            // 连字符不粘（已定稿漏面）
                "转账 138 0013 8",                    // 空格粘完也只有 11 位
                "btc 1 2 3",
                "投资 1380013800",
                "",
        };
        int pOk = 0, kOk = 0;
        for (String s : mustFilter) {
            boolean got = com.phonepctransfer.pc.TransferKeywords.isSilentlyFiltered(s);
            check(got, "应丢未丢: " + s);
            if (got) {
                pOk++;
            }
        }
        for (String s : mustKeep) {
            boolean got = com.phonepctransfer.pc.TransferKeywords.isSilentlyFiltered(s);
            check(!got, "应留被丢: " + s);
            if (!got) {
                kOk++;
            }
        }
        System.out.println("[OK] 词表正例 " + pOk + "/" + mustFilter.length
                + "，反例 " + kOk + "/" + mustKeep.length);

        // 语料命中率报表（不断言，只出数）：模拟 200 条日常短消息
        String[] tpl = {
                "好的", "收到", "在吗", "明天见", "谢谢", "哈哈", "路上堵车",
                "订单 138001380001234", "电话 13800138000", "卡号 6222021380013800012",
                "btc 138001380001234", "USDT 138001380001234", "荐股 138001380001234",
                "转账 138001380001234", "投资 138001380001234", "缅甸 138001380001234",
                "群号 123456789", "验证码 482913", "快递单号 7812345678901",
                "理财 138 0013 8000 1234",
        };
        int n = 0, hit = 0;
        for (int r = 0; r < 10; r++) {
            for (String t : tpl) {
                n++;
                if (com.phonepctransfer.pc.TransferKeywords.isSilentlyFiltered(t)) {
                    hit++;
                }
            }
        }
        System.out.println("[INFO] 语料 " + n + " 条，命中 " + hit + " 条（命中率 "
                + (hit * 100.0 / n) + "%）；词表 "
                + com.phonepctransfer.pc.TransferKeywords.wordCount() + " 条，SHA="
                + com.phonepctransfer.pc.TransferKeywords.WORDS_SHA256);
    }
}
