package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「不同设备的聊天有没有真的分开」自检（2026-09-21 加，用户报「不同设备的聊天窗口混在一起」）。
 *
 * <p>三路各查一遍，全都**不动用户真实数据**（把 `pc/config.json` + `pc/history/*.jsonl` 复制到临时目录再读）：
 * <ol>
 *   <li><b>数据层</b>：每个设备组 `loadHistory(组当前地址)` 拿到的 mid 集合，两两不许有交集；</li>
 *   <li><b>界面层</b>：离屏构造 {@link ChatWindow}，逐组切换后 `win.mids` 必须 = 该组自己的历史（+ 该组待发）；</li>
 *   <li><b>串台探针</b>：手工投三条消息 —— peerIp 属于当前设备、属于别的设备、以及**空 peerIp**，
 *       看它们各自会不会落进当前会话（空 peerIp 是最危险的漏法：判据写成「不是别人的就画」时人人可进）；</li>
 * </ol>
 *
 * <pre>
 * javac -cp build/classes -d build/probe tools/ChatSepProbe.java
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" com.phonepctransfer.pc.ChatSepProbe
 * </pre>
 *
 * 只用于自检，**不进**发布 jar。
 */
public final class ChatSepProbe {

    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path src = Store.defaultRoot();
        Path tmp = new File("build/tmp/sepprobe-" + System.currentTimeMillis()).toPath();
        Files.createDirectories(tmp.resolve("history"));
        Path cfg = src.resolve("config.json");
        if (!Files.exists(cfg)) {
            System.out.println("FAIL 找不到 " + cfg);
            System.exit(2);
        }
        Files.copy(cfg, tmp.resolve("config.json"));
        int files = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src.resolve("history"), "*.jsonl")) {
            for (Path p : ds) {
                Files.copy(p, tmp.resolve("history").resolve(p.getFileName().toString()));
                files++;
            }
        }
        System.out.println("源 store = " + src.toAbsolutePath() + "（配置 1 份 + 历史 " + files + " 份）");
        System.out.println("临时 store = " + tmp.toAbsolutePath());

        Store store = new Store(tmp);
        store.load();
        List<Store.Group> gs = store.groups();
        System.out.println("设备组数 = " + gs.size());

        // ===== ① 数据层：逐组读历史 =====
        Map<String, Set<String>> byCurIp = new LinkedHashMap<String, Set<String>>();
        Map<String, String> keyOfCurIp = new LinkedHashMap<String, String>();
        for (Store.Group g : gs) {
            Set<String> mids = new LinkedHashSet<String>();
            for (Map<String, Object> r : store.loadHistory(g.curIp, 500)) {
                String mid = Json.str(r, "mid", "");
                if (!mid.isEmpty()) {
                    mids.add(mid);
                }
            }
            byCurIp.put(g.curIp, mids);
            keyOfCurIp.put(g.curIp, g.key);
            System.out.println(String.format("  数据层 组键=%-30s curIp=%-42s ips=%d 历史=%d 条 名=%s",
                    g.key, g.curIp, g.ips.size(), mids.size(), g.name));
        }
        List<String> ips = new ArrayList<String>(byCurIp.keySet());
        for (int i = 0; i < ips.size(); i++) {
            for (int j = i + 1; j < ips.size(); j++) {
                Set<String> a = new LinkedHashSet<String>(byCurIp.get(ips.get(i)));
                a.retainAll(byCurIp.get(ips.get(j)));
                if (!a.isEmpty()) {
                    fail++;
                    System.out.println("  FAIL 数据层串台：" + ips.get(i) + " 与 " + ips.get(j)
                            + " 共有 " + a.size() + " 条 mid");
                }
            }
        }

        // ===== ② 界面层：离屏 ChatWindow，逐组切换 =====
        Link link = new Link();
        link.setStore(store);
        final ChatWindow win = new ChatWindow(link, store);   // 不 showWindow：不弹窗、不抢端口
        flushEdt();
        for (Store.Group g : gs) {
            win.shownIp = g.curIp;          // 等价于 switchConversation 里的赋值（不碰链路）
            win.reloadHistory();
            flushEdt();
            Set<String> ui = new LinkedHashSet<String>(win.mids);
            Set<String> own = byCurIp.get(g.curIp);
            Set<String> foreign = new LinkedHashSet<String>(ui);
            foreign.removeAll(own);
            // 待发条目允许出现在界面里（不属于历史）—— 单独算清
            Set<String> pendingMids = new LinkedHashSet<String>();
            for (Msg m : store.loadPending()) {
                if (m.mid != null && !m.mid.isEmpty()) {
                    pendingMids.add(m.mid);
                }
            }
            foreign.removeAll(pendingMids);
            System.out.println(String.format("  界面层 组键=%-30s 气泡=%d（历史 %d / 待发 %d）",
                    g.key, ui.size(), own.size(), pendingMids.size()));
            if (!foreign.isEmpty()) {
                fail++;
                System.out.println("  FAIL 界面串台：多出 " + foreign.size() + " 条不属于本设备的 mid："
                        + new ArrayList<String>(foreign).subList(0, Math.min(5, foreign.size())));
            }
            if (!ui.containsAll(own)) {
                fail++;
                Set<String> missing = new LinkedHashSet<String>(own);
                missing.removeAll(ui);
                System.out.println("  FAIL 界面漏条：缺 " + missing.size() + " 条（可能是 >200 条的窗口上限）");
            }
        }

        // ===== ③ 串台探针：手工投消息 =====
        if (gs.size() >= 2) {
            Store.Group cur = gs.get(0);
            Store.Group other = gs.get(1);
            win.shownIp = cur.curIp;
            win.reloadHistory();
            flushEdt();
            int before = win.mids.size();

            Msg mine = text("PROBE-MINE-" + System.nanoTime(), cur.curIp);
            win.onMessageAdded(mine);
            flushEdt();
            boolean mineOk = win.mids.contains(mine.mid);

            Msg alien = text("PROBE-ALIEN-" + System.nanoTime(), other.curIp);
            win.onMessageAdded(alien);
            flushEdt();
            boolean alienLeaked = win.mids.contains(alien.mid);

            Msg orphan = text("PROBE-ORPHAN-" + System.nanoTime(), "");
            win.onMessageAdded(orphan);
            flushEdt();
            boolean orphanLeaked = win.mids.contains(orphan.mid);

            System.out.println(String.format("  串台探针 当前=%-20s 本设备消息进入=%s 别的设备消息泄漏=%s 空 peerIp 泄漏=%s",
                    cur.curIp, mineOk, alienLeaked, orphanLeaked));
            if (!mineOk) {
                fail++;
                System.out.println("  FAIL 本设备的消息没能进聊天区");
            }
            if (alienLeaked) {
                fail++;
                System.out.println("  FAIL 别的设备的消息漏进了当前会话");
            }
            if (orphanLeaked) {
                fail++;
                System.out.println("  FAIL peerIp 为空的消息漏进了当前会话（归属不明的消息没有丢弃）");
            }
            System.out.println("  （气泡数 " + before + " → " + win.mids.size() + "）");
        }

        System.out.println(fail == 0 ? "CHAT SEP PROBE PASS" : "CHAT SEP PROBE FAIL（" + fail + " 项）");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static Msg text(String mid, String peerIp) {
        Msg m = new Msg();
        m.mid = mid;
        m.peerIp = peerIp;
        m.dir = Msg.DIR_PEER;
        m.kind = Msg.KIND_TEXT;
        m.text = "probe";
        m.ts = System.currentTimeMillis();
        m.status = Msg.ST_OK;
        return m;
    }

    /** 把 EDT 上已排队的事件跑完（onMessageAdded/Updated 都是 invokeLater） */
    private static void flushEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // 空任务：排在前面那些 invokeLater 之后，回来就说明它们都跑完了
            }
        });
    }
}
