// 高风险 IPv6 阻断清单生成器（唯一事实来源：C:\ipdata\highrisk_ipv6_blocklist.txt）
//
// 用途：读源清单 → 解析 CIDR → 按前缀长度分组升序 → 生成 Android / PC 两份
//       BlocklistIpv6.java（仅 package 行不同，数据与算法字节一致）+ TransferKeywords.java。
// 产物头部写死「条数 + sha256 + 生成时间 + 源文件名」，双端漂移一眼可见。
//
// 用法（项目根目录，JBR 的 javac/java）：
//   tools\ipdata\gen.bat C:\ipdata\highrisk_ipv6_blocklist.txt
// 手工：
//   java GenBlocklist <list.txt> <dirA> <pkgA> <dirB> <pkgB>
//
// ⚠ 源清单只读不改；要换名单就换文件重新生成，不许手工往产物里增删条目。
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public final class GenBlocklist {

    /** 一条 CIDR：128 位起始值拆成 hi/lo 两个 long + 前缀长度 */
    static final class Seg {
        final int plen;
        final long hi;
        final long lo;
        Seg(int plen, long hi, long lo) { this.plen = plen; this.hi = hi; this.lo = lo; }
    }

    // 关键词词表：A 组（诈骗专属）+ B 组（靠附加条件收住）。C 组（黄金/美元/客服）明确不纳入。
    static final String[] WORDS = {
        "炒股", "荐股", "带你炒", "个股推荐", "虚拟币", "虚拟货币", "加密货币", "比特币",
        "btc", "usdt", "泰达币", "区块链", "赌场", "网赌", "博彩", "开盘号码", "稳赚", "包赢",
        "返水", "充值送", "老师带单", "跟单", "杀猪盘", "内幕消息", "杠杆爆仓回本", "买彩票",
        "投资", "理财", "转账", "缅甸",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("usage: java GenBlocklist <list.txt> <dirA> <pkgA> <dirB> <pkgB>");
            System.exit(2);
            return;
        }
        Path src = Paths.get(args[0]);
        Target[] targets = { new Target(Paths.get(args[1]), args[2]), new Target(Paths.get(args[3]), args[4]) };

        List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
        List<Seg> segs = new ArrayList<Seg>();
        int bad = 0;
        String lastData = "";
        for (String raw : lines) {
            String s = raw == null ? "" : raw.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int slash = s.indexOf('/');
            if (slash <= 0 || slash == s.length() - 1) {
                System.out.println("[SKIP] 不是 CIDR 行: " + s);
                bad++;
                continue;
            }
            int plen;
            try {
                plen = Integer.parseInt(s.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                System.out.println("[SKIP] 前缀长度非法: " + s);
                bad++;
                continue;
            }
            if (plen < 1 || plen > 128) {
                System.out.println("[SKIP] 前缀长度越界: " + s);
                bad++;
                continue;
            }
            long[] v = parseV6(s.substring(0, slash).trim());
            if (v == null) {
                System.out.println("[SKIP] 地址解析失败: " + s);
                bad++;
                continue;
            }
            // 起始值必须对齐到前缀边界，否则源清单本身有问题（防止把带主机位的地址当段首）
            long[] m = mask(plen);
            if ((v[0] & m[0]) != v[0] || (v[1] & m[1]) != v[1]) {
                System.out.println("[SKIP] 段首未对齐前缀: " + s);
                bad++;
                continue;
            }
            segs.add(new Seg(plen, v[0], v[1]));
            lastData = s;
        }
        if (bad > 0) {
            System.out.println("[FAIL] 有 " + bad + " 行无法解析，源清单疑似不完整/被改动 —— 中止生成");
            System.exit(1);
        }
        if (segs.isEmpty()) {
            System.out.println("[FAIL] 清单为空");
            System.exit(1);
        }

        // 分组 + 升序（无符号），同时去掉重复段
        TreeMap<Integer, List<Seg>> byLen = new TreeMap<Integer, List<Seg>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Seg g : segs) {
            String key = g.plen + "/" + Long.toHexString(g.hi) + "/" + Long.toHexString(g.lo);
            if (!seen.add(key)) {
                continue;
            }
            List<Seg> bucket = byLen.get(Integer.valueOf(g.plen));
            if (bucket == null) {
                bucket = new ArrayList<Seg>();
                byLen.put(Integer.valueOf(g.plen), bucket);
            }
            bucket.add(g);
        }
        Comparator<Seg> cmp = new Comparator<Seg>() {
            public int compare(Seg a, Seg b) {
                int c = Long.compareUnsigned(a.hi, b.hi);
                return c != 0 ? c : Long.compareUnsigned(a.lo, b.lo);
            }
        };
        for (List<Seg> bucket : byLen.values()) {
            bucket.sort(cmp);
        }

        // 数据指纹：按「前缀长度 + 起始值」规范序逐行拼起来算 sha256（与源文件行序无关）
        StringBuilder canon = new StringBuilder();
        int total = 0;
        for (java.util.Map.Entry<Integer, List<Seg>> en : byLen.entrySet()) {
            for (Seg g : en.getValue()) {
                canon.append(en.getKey().intValue()).append('/')
                     .append(hex(g.hi)).append(hex(g.lo)).append('\n');
                total++;
            }
        }
        String sha = sha256(canon.toString().getBytes(StandardCharsets.UTF_8));
        String at = java.time.LocalDateTime.now().withNano(0)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String srcName = src.getFileName().toString();

        System.out.println("[OK] 源文件 " + srcName + " 末数据行: " + lastData);
        System.out.println("[OK] 条数=" + total + " 分组=" + byLen.size());
        for (java.util.Map.Entry<Integer, List<Seg>> en : byLen.entrySet()) {
            System.out.println("     /" + en.getKey() + " -> " + en.getValue().size() + " 段");
        }
        System.out.println("[OK] DATA_SHA256=" + sha + " 生成时间=" + at);

        String data = renderData(byLen, total, srcName, sha, at);
        String words = renderWords(total, srcName, sha, at);
        for (Target t : targets) {
            Files.createDirectories(t.dir);
            Path f1 = t.dir.resolve("BlocklistIpv6.java");
            Path f2 = t.dir.resolve("TransferKeywords.java");
            String matcher = MATCHER_BODY.replace("%PKG%", t.pkg).replace("%DATA%", data);
            String keywords = KEYWORD_BODY.replace("%PKG%", t.pkg).replace("%WORDS%", words);
            if (matcher.contains("%DATA%") || keywords.contains("%WORDS%")) {
                throw new IllegalStateException("模板占位符未替换");
            }
            Files.write(f1, matcher.getBytes(StandardCharsets.UTF_8));
            Files.write(f2, keywords.getBytes(StandardCharsets.UTF_8));
            System.out.println("[WRITE] " + f1 + " (" + Files.size(f1) + "B)");
            System.out.println("[WRITE] " + f2 + " (" + Files.size(f2) + "B)");
        }
        System.out.println("[DONE] 两份产物已写出；接着跑 VerifyBlocklist 做边界与反向断言");
    }

    /** 渲染数据表（头注释 + 常量 + 分组数组），注入模板的 %DATA% 处 */
    static String renderData(TreeMap<Integer, List<Seg>> byLen, int total, String srcName,
                             String sha, String at) {
        StringBuilder sb = new StringBuilder();
        sb.append("    /** 段数（生成时实测） */\n");
        sb.append("    public static final int SEGMENT_COUNT = ").append(total).append(";\n");
        sb.append("    /** 源清单文件名（只读，勿改） */\n");
        sb.append("    public static final String SOURCE_FILE = \"").append(srcName).append("\";\n");
        sb.append("    /** 数据指纹：按「前缀长度/起始值」规范序逐行 sha256，与源文件行序无关 */\n");
        sb.append("    public static final String DATA_SHA256 = \"").append(sha).append("\";\n");
        sb.append("    /** 生成时间（本文件由 tools/ipdata/GenBlocklist.java 产出，勿手工编辑） */\n");
        sb.append("    public static final String GENERATED_AT = \"").append(at).append("\";\n\n");

        sb.append("    private static final int[] PLEN = {");
        boolean first = true;
        for (Integer k : byLen.keySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(k.intValue());
            first = false;
        }
        sb.append("};\n\n");

        sb.append("    private static final long[][] HI = {\n");
        for (List<Seg> bucket : byLen.values()) {
            sb.append("        {");
            appendLongs(sb, bucket, true);
            sb.append("},\n");
        }
        sb.append("    };\n\n");

        sb.append("    private static final long[][] LO = {\n");
        for (List<Seg> bucket : byLen.values()) {
            sb.append("        {");
            appendLongs(sb, bucket, false);
            sb.append("},\n");
        }
        sb.append("    };\n");
        return sb.toString();
    }

    static void appendLongs(StringBuilder sb, List<Seg> bucket, boolean hi) {
        for (int i = 0; i < bucket.size(); i++) {
            Seg g = bucket.get(i);
            if (i > 0) {
                sb.append(',');
            }
            if (i % 4 == 0) {
                sb.append('\n').append("            ");
            } else {
                sb.append(' ');
            }
            sb.append("0x").append(hex(hi ? g.hi : g.lo)).append('L');
        }
        sb.append('\n').append("        ");
    }

    static String renderWords(int total, String srcName, String sha, String at) {
        StringBuilder sb = new StringBuilder();
        sb.append("    /** 词表条数（A 组 26 + B 组 4） */\n");
        sb.append("    public static final int WORD_COUNT = ").append(WORDS.length).append(";\n");
        sb.append("    /** 词表指纹：按数组顺序逐行 sha256 */\n");
        StringBuilder w = new StringBuilder();
        for (String s : WORDS) {
            w.append(s).append('\n');
        }
        sb.append("    public static final String WORDS_SHA256 = \"")
          .append(sha256(w.toString().getBytes(StandardCharsets.UTF_8))).append("\";\n");
        sb.append("    /** 与名单同批生成（勿手工编辑本文件；词表在 GenBlocklist.WORDS 里维护） */\n");
        sb.append("    public static final String GENERATED_AT = \"").append(at).append("\";\n");
        sb.append("    public static final String SOURCE_FILE = \"").append(srcName).append("\";\n");
        sb.append("    public static final int LIST_SEGMENT_COUNT = ").append(total).append(";\n");
        sb.append("    public static final String LIST_SHA256 = \"").append(sha).append("\";\n\n");
        sb.append("    private static final String[] WORDS = {\n");
        for (int i = 0; i < WORDS.length; i++) {
            sb.append("        \"").append(WORDS[i]).append('"');
            sb.append(i == WORDS.length - 1 ? "" : ",");
            sb.append('\n');
        }
        sb.append("    };\n");
        return sb.toString();
    }

    static String hex(long v) {
        String s = Long.toHexString(v);
        StringBuilder sb = new StringBuilder(16);
        for (int i = s.length(); i < 16; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", Byte.valueOf(x)));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 掩码：返回 {hiMask, loMask}，前缀长度 p */
    static long[] mask(int p) {
        long hm;
        long lm;
        if (p <= 0) {
            hm = 0L;
            lm = 0L;
        } else if (p >= 128) {
            hm = -1L;
            lm = -1L;
        } else if (p == 64) {
            hm = -1L;
            lm = 0L;
        } else if (p < 64) {
            hm = -1L << (64 - p);
            lm = 0L;
        } else {
            hm = -1L;
            lm = -1L << (128 - p);
        }
        return new long[]{hm, lm};
    }

    /**
     * IPv6 文本 → {hi, lo}；支持 `::` 压缩与 `::ffff:1.2.3.4` 内嵌 IPv4。
     * 解析失败返回 null。纯 IPv4 点分地址也返回 null（本期不拦 v4，交给调用方判）。
     */
    static long[] parseV6(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.isEmpty() || s.indexOf(':') < 0) {
            return null;
        }
        int dc = s.indexOf("::");
        if (dc != s.lastIndexOf("::")) {
            return null;
        }
        String head = dc < 0 ? s : s.substring(0, dc);
        String tail = dc < 0 ? "" : s.substring(dc + 2);
        List<Long> hv = groups(head);
        if (hv == null) {
            return null;
        }
        List<Long> tv = tail.isEmpty() && dc >= 0 ? new ArrayList<Long>() : groups(tail);
        if (tv == null) {
            return null;
        }
        int n;
        List<Long> all = new ArrayList<Long>();
        if (dc < 0) {
            if (hv.size() != 8) {
                return null;
            }
            all.addAll(hv);
            n = 8;
        } else {
            n = hv.size() + tv.size();
            if (n > 7) {
                return null;
            }
            all.addAll(hv);
            for (int i = n; i < 8; i++) {
                all.add(Long.valueOf(0));
            }
            all.addAll(tv);
        }
        if (all.size() != 8) {
            return null;
        }
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 4; i++) {
            hi = (hi << 16) | (all.get(i).longValue() & 0xFFFFL);
        }
        for (int i = 4; i < 8; i++) {
            lo = (lo << 16) | (all.get(i).longValue() & 0xFFFFL);
        }
        return new long[]{hi, lo};
    }

    /** 拆一组 16 进制段；末段若是点分 IPv4，展开成两个 16 位组 */
    static List<Long> groups(String part) {
        List<Long> out = new ArrayList<Long>();
        if (part.isEmpty()) {
            return out;
        }
        String[] ps = part.split(":", -1);
        for (String p : ps) {
            if (p.isEmpty()) {
                return null;
            }
            if (p.indexOf('.') >= 0) {
                String[] q = p.split("[.]", -1);
                if (q.length != 4) {
                    return null;
                }
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) {
                    try {
                        b[i] = Integer.parseInt(q[i]);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (b[i] < 0 || b[i] > 255) {
                        return null;
                    }
                }
                out.add(Long.valueOf(((b[0] << 8) | b[1]) & 0xFFFF));
                out.add(Long.valueOf(((b[2] << 8) | b[3]) & 0xFFFF));
                continue;
            }
            if (p.length() > 4) {
                return null;
            }
            long v;
            try {
                v = Long.parseLong(p, 16);
            } catch (NumberFormatException e) {
                return null;
            }
            out.add(Long.valueOf(v & 0xFFFFL));
        }
        return out;
    }

    static final class Target {
        final Path dir;
        final String pkg;
        Target(Path dir, String pkg) { this.dir = dir; this.pkg = pkg; }
    }

    // ==================== 产物模板（算法两份一致，只有 package 不同）====================

    static final String MATCHER_BODY = """
package %PKG%;

// 由 tools/ipdata/GenBlocklist.java 生成，勿手工编辑。
// 匹配口径：完整 CIDR（128 位起始值二分），绝不按前几位 hex 粗判 ——
// 高风险段与国内段在首段（2001 / 2400–2407 / 2a04–2a14）大面积重合。

public final class BlocklistIpv6 {

%DATA%
    private BlocklistIpv6() { }

    /**
     * 对端地址是否命中高风险清单。
     *
     * 一律返回 false 的情形（宁漏不误伤）：纯 IPv4、IPv4 映射地址（::ffff:x.x.x.x）、
     * 回环 ::1、未指定 ::、fe80::/10 链路本地、fc00::/7 ULA、解析不了的字符串。
     */
    public static boolean isHighRisk(String addr) {
        long[] v = parse(addr);
        if (v == null) {
            return false;
        }
        if (isExempt(v[0], v[1])) {
            return false;
        }
        // ::ffff:x.x.x.x（含 ::ffff:127.0.0.1 / ::ffff:192.168.1.5）：本期不拦 IPv4
        if (v[0] == 0L && (v[1] >>> 16) == 0xFFFFL) {
            return false;
        }
        return inTable(v[0], v[1]);
    }

    /** 调试出口用：命中了哪一组前缀长度（-1 = 未命中）。不写日志、不落盘 */
    public static int hitPrefixLen(String addr) {
        long[] v = parse(addr);
        if (v == null || isExempt(v[0], v[1]) || (v[0] == 0L && (v[1] >>> 16) == 0xFFFFL)) {
            return -1;
        }
        for (int g = 0; g < PLEN.length; g++) {
            long[] m = mask(PLEN[g]);
            if (contains(g, v[0] & m[0], v[1] & m[1])) {
                return PLEN[g];
            }
        }
        return -1;
    }

    static boolean inTable(long hi, long lo) {
        for (int g = 0; g < PLEN.length; g++) {
            long[] m = mask(PLEN[g]);
            if (contains(g, hi & m[0], lo & m[1])) {
                return true;
            }
        }
        return false;
    }

    /** 硬豁免：这些地址无论清单怎么写都不拦 */
    static boolean isExempt(long hi, long lo) {
        if (hi == 0L && (lo == 1L || lo == 0L)) {
            return true;                       // ::1 回环 / :: 未指定
        }
        if ((hi & 0xFFC0000000000000L) == 0xFE80000000000000L) {
            return true;                       // fe80::/10 链路本地（出不了本地链路）
        }
        if ((hi & 0xFE00000000000000L) == 0xFC00000000000000L) {
            return true;                       // fc00::/7 ULA（含 fdxx，不跨路由器传播）
        }
        return false;
    }

    static long[] mask(int p) {
        if (p <= 0) {
            return new long[]{0L, 0L};
        }
        if (p >= 128) {
            return new long[]{-1L, -1L};
        }
        if (p == 64) {
            return new long[]{-1L, 0L};
        }
        if (p < 64) {
            return new long[]{-1L << (64 - p), 0L};
        }
        return new long[]{-1L, -1L << (128 - p)};
    }

    /** 第 g 组的段首集合里有 (hi, lo) 吗（组内按无符号升序，二分） */
    static boolean contains(int g, long hi, long lo) {
        long[] h = HI[g];
        long[] l = LO[g];
        int a = 0;
        int b = h.length - 1;
        while (a <= b) {
            int mid = (a + b) >>> 1;
            int c = Long.compareUnsigned(h[mid], hi);
            if (c == 0) {
                c = Long.compareUnsigned(l[mid], lo);
            }
            if (c == 0) {
                return true;
            }
            if (c < 0) {
                a = mid + 1;
            } else {
                b = mid - 1;
            }
        }
        return false;
    }

    /** IPv6 文本 → {hi, lo}；带 %zone 先剥掉；纯 IPv4 点分地址返回 null */
    public static long[] parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.isEmpty() || s.indexOf(':') < 0) {
            return null;
        }
        int dc = s.indexOf("::");
        if (dc != s.lastIndexOf("::")) {
            return null;
        }
        String head = dc < 0 ? s : s.substring(0, dc);
        String tail = dc < 0 ? "" : s.substring(dc + 2);
        long[] hv = groups(head);
        if (hv == null) {
            return null;
        }
        long[] all;
        if (dc < 0) {
            if (hv.length != 8) {
                return null;
            }
            all = hv;
        } else {
            // 注意：getHostAddress() 给的是不压缩形式（无 "::"），绝不能在这里判成解析失败
            long[] tv = groups(tail);
            if (tv == null) {
                return null;
            }
            if (hv.length + tv.length > 7) {
                return null;
            }
            all = new long[8];
            System.arraycopy(hv, 0, all, 0, hv.length);
            System.arraycopy(tv, 0, all, 8 - tv.length, tv.length);
        }
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 4; i++) {
            hi = (hi << 16) | (all[i] & 0xFFFFL);
        }
        for (int i = 4; i < 8; i++) {
            lo = (lo << 16) | (all[i] & 0xFFFFL);
        }
        return new long[]{hi, lo};
    }

    /** 一组 16 进制段；末段允许点分 IPv4（展开成两个 16 位组） */
    static long[] groups(String part) {
        if (part.isEmpty()) {
            return new long[0];
        }
        String[] ps = part.split(":", -1);
        long[] tmp = new long[ps.length * 2];
        int n = 0;
        for (String p : ps) {
            if (p.isEmpty()) {
                return null;
            }
            if (p.indexOf('.') >= 0) {
                String[] q = p.split("[.]", -1);
                if (q.length != 4) {
                    return null;
                }
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) {
                    try {
                        b[i] = Integer.parseInt(q[i]);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (b[i] < 0 || b[i] > 255) {
                        return null;
                    }
                }
                tmp[n++] = ((b[0] << 8) | b[1]) & 0xFFFFL;
                tmp[n++] = ((b[2] << 8) | b[3]) & 0xFFFFL;
                continue;
            }
            if (p.length() > 4) {
                return null;
            }
            long v;
            try {
                v = Long.parseLong(p, 16);
            } catch (NumberFormatException e) {
                return null;
            }
            tmp[n++] = v & 0xFFFFL;
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** 条数自检（调试出口用；产物头部那条注释是权威值） */
    public static int segmentCount() {
        int n = 0;
        for (long[] g : HI) {
            n += g.length;
        }
        return n;
    }
}
""";

    static final String KEYWORD_BODY = """
package %PKG%;

import java.util.Locale;

// 由 tools/ipdata/GenBlocklist.java 生成，勿手工编辑（词表在 GenBlocklist.WORDS 里维护）。
// 判定口径（写死，顺序不可调）：命中词表 且 codePointCount < 30 且
// 去掉「数字与数字之间」的空格（含全角）后最长连续数字段 >= 12。
// 只作用于接收侧文本消息；剪贴板、文件备注、通话不过滤。

public final class TransferKeywords {

%WORDS%
    /** 字数上限：等于或超过就不判（长文本多为正常叙述） */
    public static final int MAX_TEXT_CODEPOINTS = 30;
    /** 连续数字段阈值：全工程唯一可调常量，收紧口径只动它，不动词表 */
    public static final int MIN_DIGIT_RUN = 12;

    private TransferKeywords() { }

    /** 这条接收文本是否应当「静默丢弃」（发送端照旧收到 ack，四处不留痕迹） */
    public static boolean isSilentlyFiltered(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.codePointCount(0, text.length()) >= MAX_TEXT_CODEPOINTS) {
            return false;
        }
        String norm = normalize(text);
        boolean wordHit = false;
        for (String w : WORDS) {
            if (norm.indexOf(w) >= 0) {
                wordHit = true;
                break;
            }
        }
        if (!wordHit) {
            return false;
        }
        return maxDigitRunAfterGlue(norm) >= MIN_DIGIT_RUN;
    }

    /** 全角数字折半角 + 拉丁小写化（只影响 BTC / USDT 两条，故只做一次） */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 只粘「数字与数字之间」的空白（含全角空格）后，求最长连续数字段 */
    static int maxDigitRunAfterGlue(String s) {
        int best = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                run++;
                if (run > best) {
                    best = run;
                }
                continue;
            }
            if (isSpaceish(c) && run > 0 && nextIsDigit(s, i)) {
                continue;                       // 数字之间的空格：跳过，游标继续
            }
            run = 0;
        }
        return best;
    }

    static boolean isSpaceish(char c) {
        return c == 0x0020 || c == 0x0009 || c == 0x000A || c == 0x000D || c == 0x00A0
                || c == 0x1680 || c == 0x2007 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    /** 下一个非空白字符是不是数字（往前看，连续多个空格也只粘一次） */
    static boolean nextIsDigit(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpaceish(c)) {
                continue;
            }
            return c >= '0' && c <= '9';
        }
        return false;
    }

    public static int wordCount() {
        return WORDS.length;
    }
}
""";
}
