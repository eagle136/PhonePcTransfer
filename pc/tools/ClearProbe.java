package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「清空当前会话」的数据层自检（2026-09-21 加）—— **无 GUI、无 socket、只碰临时目录**。
 *
 * <p>清空是破坏性操作，改法又是「逐条删除」，所以把数据层的三条不变量单独测一遍：
 * <ol>
 *   <li>{@link Store#removeFromHistory(String, java.util.Collection)} 只动**目标设备**的历史文件
 *       —— 别的设备一条都不能少（历史按组键分文件，串台就是灾难）；</li>
 *   <li>{@link Store#removePending(java.util.Collection)} 只移除命中的 mid，其余待发条目保留；</li>
 *   <li>单条版 {@link Store#removeFromHistory(String, String)} 没被批量版顶掉（回归）。</li>
 * </ol>
 *
 * <pre>
 * javac -cp build/classes -d build/probe tools/ClearProbe.java
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" com.phonepctransfer.pc.ClearProbe
 * </pre>
 *
 * 只用于自检，**不进**发布 jar。
 */
public final class ClearProbe {

    static int fail = 0;

    public static void main(String[] args) throws Exception {
        // 每跑一次换一个目录：不做删除，规避沙箱的批量删除拦截
        Path root = new File("build/tmp/clearprobe-" + System.currentTimeMillis()).toPath();
        Store store = new Store(root);
        System.out.println("临时 store = " + root.toAbsolutePath());

        String a = "192.168.1.13";
        String b = "192.168.1.14";
        List<String> aMids = Arrays.asList("a1", "a2", "a3");
        for (String mid : aMids) {
            store.appendHistory(a, rec(mid, "TEXT", "给 A 的第 " + mid + " 条"));
        }
        store.appendHistory(b, rec("b1", "TEXT", "给 B 的文本"));
        store.appendHistory(b, rec("b2", "FILE", "给 B 的图片"));

        for (String mid : aMids) {
            store.addPending(pending(mid, a));
        }
        store.addPending(pending("b4", b));   // 别的设备的待发，不能被误删

        System.out.println("清空前：A 历史=" + store.loadHistory(a, 999).size()
                + " B 历史=" + store.loadHistory(b, 999).size()
                + " 待发=" + store.loadPending().size());

        // === 模拟 LinkHsync.deleteMessages 的两步（清空走的就是这两条）===
        store.removePending(aMids);
        int removed = store.removeFromHistory(a, aMids);

        int aLeft = store.loadHistory(a, 999).size();
        int bLeft = store.loadHistory(b, 999).size();
        int pendLeft = store.loadPending().size();
        System.out.println("清空后：A 历史=" + aLeft + "（删掉 " + removed + "） B 历史=" + bLeft
                + " 待发=" + pendLeft);

        check("A 的历史被清空", aLeft == 0);
        check("B 的历史一条不少（2 条，未串台）", bLeft == 2);
        check("只删掉该设备的 3 条待发（B 的 b4 还在）", pendLeft == 1);
        check("删掉的行数 = 3", removed == 3);
        check("B 的待发仍是 b4",
                store.loadPending().isEmpty() || "b4".equals(store.loadPending().get(0).mid));

        // 回归：单条版还能用（cancelFile 走的是它）
        store.removeFromHistory(b, "b1");
        check("单条删历史仍生效（B 剩 1 条）", store.loadHistory(b, 999).size() == 1);

        System.out.println(fail == 0 ? "CLEAR PROBE PASS" : ("CLEAR PROBE FAIL (" + fail + ")"));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            fail++;
        }
    }

    static Map<String, Object> rec(String mid, String kind, String text) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("mid", mid);
        r.put("peer", "");
        r.put("dir", "SELF");
        r.put("kind", kind);
        r.put("text", text);
        r.put("fileName", "FILE".equals(kind) ? "x.png" : "");
        r.put("fileSize", 100);
        r.put("ts", System.currentTimeMillis());
        r.put("status", "OK");
        return r;
    }

    static Msg pending(String mid, String peerIp) {
        Msg m = new Msg();
        m.mid = mid;
        m.peerIp = peerIp;
        m.dir = Msg.DIR_SELF;
        m.kind = Msg.KIND_TEXT;
        m.text = "待发 " + mid;
        m.ts = System.currentTimeMillis();
        m.status = Msg.ST_PENDING;
        return m;
    }
}
