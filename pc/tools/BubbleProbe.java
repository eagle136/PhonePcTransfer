package com.phonepctransfer.pc;

import java.awt.*;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * 气泡自检探针（2026-09-21 加，配套两处改动）—— **纯离屏、无 GUI、不抢端口**：
 *
 * <ol>
 *   <li><b>点击作用域</b>：验证气泡手势手柄只挂在**气泡本体**上，而不是满宽的整行
 *       （整行挂手柄 → 点在气泡旁边的空白处也会弹菜单/删消息，用户报的 bug）。
 *       断言：整行 row 的 MouseListener 数 = 0；气泡本体（RoundedPanel/JTextArea）上有
 *       {@code BubbleGestureListener}；把 row 拉到 800 宽后硬算布局，气泡宽度远小于 800。</li>
 *   <li><b>常驻按钮已撤</b>：断言气泡内不再有 JButton（「Copy / Copy image / Show in folder」全删），
 *       功能收进右键菜单（菜单项是中文，见 {@link ChatBubbles#showBubblePopup}）。</li>
 * </ol>
 *
 * <pre>
 * javac -cp build/classes -d build/probe tools/BubbleProbe.java
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" com.phonepctransfer.pc.BubbleProbe [证明图.png]
 * </pre>
 *
 * 传了输出路径就顺手把「气泡长什么样 + 右键菜单长什么样」画成 PNG（离屏 printAll，不开窗口）。
 *
 * 只用于自检，**不进**发布 jar。
 */
public final class BubbleProbe {

    static int fail = 0;

    /** 「气泡内无路径」扫描命中计数（单独统计，便于一眼看出是否回归） */
    static int pathHits = 0;

    public static void main(String[] args) throws Exception {
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        win.showWindow();
        Thread.sleep(600);

        ChatBubbles b = win.bubbles;

        Msg text = new Msg();
        text.mid = "probe-text";
        text.dir = Msg.DIR_SELF;
        text.kind = Msg.KIND_TEXT;
        text.text = "探针文本消息：这一条的宽度应该只占气泡那么大";
        text.ts = System.currentTimeMillis();
        text.status = Msg.ST_OK;

        // 找一张真实的本地图片当「已落盘、可用」的图片消息（找不到就退化成不可用，菜单只剩「删除」）
        File sample = firstExisting("../shots", "../pp_check.png");
        Msg img = new Msg();
        img.mid = "probe-img";
        img.dir = Msg.DIR_PEER;
        img.kind = Msg.KIND_FILE;
        img.fileName = sample == null ? "probe.png" : sample.getName();
        img.filePath = sample == null ? "" : sample.getAbsolutePath();
        img.fileSize = sample == null ? 12345 : sample.length();
        img.progress = -1;
        img.ts = System.currentTimeMillis();
        img.status = Msg.ST_OK;

        Msg doc = new Msg();
        doc.mid = "probe-doc";
        doc.dir = Msg.DIR_SELF;
        doc.kind = Msg.KIND_FILE;
        doc.fileName = "README.md";
        doc.filePath = new File("../README.md").getAbsolutePath();
        doc.fileSize = 168000;
        doc.progress = -1;
        doc.ts = System.currentTimeMillis();
        doc.status = Msg.ST_OK;

        check("文本气泡", b.bubble(text, false));

        // 收到并已落盘的普通文件（以前会在气泡底部摊 D:\...）
        Msg recv = new Msg();
        recv.mid = "probe-recv";
        recv.dir = Msg.DIR_PEER;
        recv.kind = Msg.KIND_FILE;
        recv.fileName = "2026-09-17_10-44-56.csv";
        recv.filePath = new File("build/probe_dummy.csv").getAbsolutePath(); // 只填字段，不读盘
        recv.fileSize = 13990;
        recv.progress = -1;
        recv.ts = System.currentTimeMillis();
        recv.status = Msg.ST_OK;

        // 收到并已落盘的文件夹（以前会摊「已保存到 D:\...」）
        Msg recvDir = new Msg();
        recvDir.mid = "probe-dir";
        recvDir.dir = Msg.DIR_PEER;
        recvDir.kind = Msg.KIND_FILE;
        recvDir.isDir = true;
        recvDir.dirCount = 12;
        recvDir.fileName = "素材文件夹";
        recvDir.filePath = new File("build/probe_dummy_dir").getAbsolutePath();
        recvDir.fileSize = 88_000_000L;
        recvDir.progress = 100;
        recvDir.ts = System.currentTimeMillis();
        recvDir.status = Msg.ST_OK;

        // 传输中的文件夹：filePath 已非空但 status=SENDING → 不得出现「已保存」（T05 闸门用例）
        Msg midDir = new Msg();
        midDir.mid = "probe-dir-mid";
        midDir.dir = Msg.DIR_PEER;
        midDir.kind = Msg.KIND_FILE;
        midDir.isDir = true;
        midDir.dirCount = 12;
        midDir.fileName = "素材文件夹";
        midDir.filePath = new File("build/probe_dummy_dir").getAbsolutePath();
        midDir.fileSize = 88_000_000L;
        midDir.progress = 40;
        midDir.ts = System.currentTimeMillis();
        midDir.status = Msg.ST_SENDING;

        checkNoPath("收到·普通文件", b.bubble(recv, false));
        checkNoPath("收到·文件夹", b.bubble(recvDir, false));
        checkNoPath("收到·图片", b.bubble(img, false));
        checkNoPath("自己发的文件", b.bubble(doc, false));
        checkNoPath("文本", b.bubble(text, false));
        checkNoPath("文件夹·传输中", b.bubble(midDir, false));

        check("图片气泡", b.bubble(img, false));

        // 2026-09-28 新增：气泡「背景 → 圆角卡片 → 文字」三层叠加的**像素级**断言
        checkLayers(b.bubble(text, false), text);

        // 顺手把「气泡长什么样 + 右键菜单长什么样」画成 PNG（离屏，不开窗口）
        if (args.length > 0) {
            try {
                renderProof(args[0], b, text, img, doc);
            } catch (Throwable t) {
                System.out.println("渲染证明图失败：" + t);
            }
        }

        System.out.println("路径泄漏 JLabel = " + pathHits);
        System.out.println(fail == 0 ? "BUBBLE PROBE PASS" : ("BUBBLE PROBE FAIL (" + fail + ")"));
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * 气泡「无路径」扫描（2026-10-01 加，配套「气泡上始终不显示文件路径」改动）。
     *
     * <p>为什么要扫组件树而不是断言某个变量：气泡布局是运行时拼出来的，
     * 只有遍历真实 JLabel 才能证明「没有任何一个标签把绝对路径画出来」。
     *
     * <p>红线判据（命中即 FAIL）：标签文本里出现 Windows 盘符路径（X:\）、file:// URI、
     * 或旧文案「已保存到」。白名单按 {@code setName} 句柄放行项目自有标签；
     * FileBubbleName 的 tooltip 是**文件名**（不是路径），不在判据内。
     */
    static void checkNoPath(String tag, JComponent row) {
        System.out.println("--- 路径泄漏扫描：" + tag + " ---");
        int h = Math.max(1, row.getPreferredSize().height);
        row.setSize(800, h);
        layoutAll(row);

        List<JLabel> labels = new ArrayList<JLabel>();
        collectLabels(row, labels);

        List<String> dump = new ArrayList<String>();
        int hits = 0;
        for (JLabel l : labels) {
            String t = l.getText() == null ? "" : l.getText();
            String nm = l.getName() == null ? "" : l.getName();
            dump.add((nm.isEmpty() ? l.getClass().getSimpleName() : nm) + "='" + t + "'");
            // 判据：盘符路径 / file URI / 旧文案
            if (t.matches(".*[A-Za-z]:\\\\.*") || t.contains("file://") || t.contains("已保存到")) {
                System.out.println("  ✗ 气泡内出现路径文字：" + nm + " = " + t);
                hits++;
            }
            // 反向保护：白名单之外的标签工具提示里带盘符路径也算泄漏
            String tip = l.getToolTipText();
            if (tip != null && (tip.matches(".*[A-Za-z]:\\\\.*") || tip.contains("file://"))) {
                System.out.println("  ✗ 气泡内标签 tooltip 带路径：" + nm + " = " + tip);
                hits++;
            }
        }
        System.out.println("  标签清单 = " + dump);
        if (hits == 0) {
            System.out.println("  ✓ 气泡内无路径文字（标签数=" + labels.size() + ")");
        } else {
            pathHits += hits;
            fail += hits;
        }
    }

    /** 递归收集组件树里的 JLabel（气泡里的文字都在 JLabel / JTextArea 上） */
    static void collectLabels(Component c, List<JLabel> out) {
        if (c instanceof JLabel) {
            out.add((JLabel) c);
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                collectLabels(ch, out);
            }
        }
    }

    /** 第一个存在的**文件**（相对 pc/ 目录；目录不算 —— 传 ../shots 会命中目录） */
    static File firstExisting(String... cands) {
        for (String p : cands) {
            File f = new File(p);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /**
     * 离屏画一张「气泡 + 右键菜单」对照图：上半是消息面板里的几种气泡
     * （注意气泡旁边那一大片留白**没有**常驻按钮），下半是三种右键菜单（中文）。
     */
    static void renderProof(String out, ChatBubbles b, Msg text, Msg img, Msg doc) throws Exception {
        Msg peerText = new Msg();
        peerText.mid = "probe-peer";
        peerText.dir = Msg.DIR_PEER;
        peerText.kind = Msg.KIND_TEXT;
        peerText.text = "对方发来的文本：只有气泡本身能点，右键出菜单";
        peerText.ts = System.currentTimeMillis();
        peerText.status = Msg.ST_OK;

        javax.swing.JPanel strip = new javax.swing.JPanel();
        strip.setLayout(new javax.swing.BoxLayout(strip, javax.swing.BoxLayout.Y_AXIS));
        strip.setBackground(Color.WHITE);
        strip.add(b.bubble(text, false));
        strip.add(b.bubble(peerText, false));
        strip.add(b.bubble(img, false));
        strip.add(b.bubble(doc, false));
        // 图片缩略图是异步解码的，等一下让 EDT 把图标贴上去
        Thread.sleep(1500);

        javax.swing.JPopupMenu mText = b.buildBubbleMenu(text);
        javax.swing.JPopupMenu mImg = b.buildBubbleMenu(img);
        javax.swing.JPopupMenu mDoc = b.buildBubbleMenu(doc);
        dumpMenu("文本", mText);
        dumpMenu("图片", mImg);
        dumpMenu("普通文件", mDoc);

        javax.swing.JPanel root = new javax.swing.JPanel(null);
        root.setBackground(Color.WHITE);
        // 高度按内容算（图片气泡带缩略图，会明显比文本高）
        int stripH = Math.max(1, strip.getPreferredSize().height);
        strip.setSize(620, stripH);
        layoutAll(strip);
        strip.setBounds(6, 6, 620, stripH);
        root.add(strip);

        root.setSize(660, stripH + 12);
        BufferedImage im = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, im.getWidth(), im.getHeight());
        root.printAll(g);
        g.dispose();
        javax.imageio.ImageIO.write(im, "png", new File(out));
        System.out.println("proof = " + out + " " + im.getWidth() + "x" + im.getHeight());
        // ⚠ 右键菜单**画不进 PNG**：JPopupMenu 是重量级弹出窗口（自带窗口，不随父组件 printAll）。
        //   所以菜单只用文字核对（条目组成 + 中文文案），别指望在图里看到它。
    }

    /** 把菜单条目文案打出来核对（`---` = 分隔线） */
    static void dumpMenu(String tag, javax.swing.JPopupMenu pm) {
        StringBuilder sb = new StringBuilder();
        for (Component c : pm.getComponents()) {
            if (c instanceof javax.swing.JSeparator) {
                sb.append("--- ");
            } else if (c instanceof javax.swing.JMenuItem) {
                sb.append(((javax.swing.JMenuItem) c).getText()).append(' ');
            }
        }
        System.out.println("菜单[" + tag + "] = " + sb.toString().trim());
    }

    /** 检查一个气泡行：手柄位置 + 无按钮 */
    static void check(String tag, JComponent row) {
        System.out.println("--- " + tag + " ---");
        // 拉到 800 宽（模拟消息面板把整行撑满）后**递归**硬算布局，看气泡实际占多宽：
        // 整行若真的满宽、而气泡只有两百来像素，那「点空白处也触发」的旧 bug 就没有生存空间。
        int h = Math.max(1, row.getPreferredSize().height);
        row.setSize(800, h);
        layoutAll(row);
        Component body = findBubbleBody(row);

        List<Component> holders = new ArrayList<Component>();
        collectHolders(row, holders);
        int rowListeners = count(row);
        System.out.println("row 宽=" + row.getWidth() + " | 气泡本体=" + (body == null ? "未找到" : body.getClass().getSimpleName())
                + " 宽=" + (body == null ? -1 : body.getWidth())
                + " | row 上的手柄数=" + rowListeners
                + " | 带手柄的组件=" + holders.size());

        if (rowListeners != 0) {
            System.out.println("  ✗ 整行还挂着手柄 → 点在空白处会触发");
            fail++;
        }
        if (holders.isEmpty()) {
            System.out.println("  ✗ 气泡本体完全没有手柄 → 点气泡也没反应");
            fail++;
        }
        if (body != null && body.getWidth() >= 780) {
            System.out.println("  ✗ 气泡本体几乎占满整行（说明手柄画在了整行上）");
            fail++;
        }
        // 气泡里不该再有常驻按钮
        List<String> buttons = new ArrayList<String>();
        collectButtons(row, buttons);
        System.out.println("  气泡内按钮=" + buttons);
        if (!buttons.isEmpty()) {
            System.out.println("  ✗ 气泡里还有常驻按钮（应全部收进右键菜单）");
            fail++;
        }
    }

    /** 递归 doLayout：容器没挂在窗口上时 validate() 不生效，只能自己一层层算 */
    static void layoutAll(Component c) {
        if (c instanceof Container) {
            Container ct = (Container) c;
            ct.doLayout();
            for (Component ch : ct.getComponents()) {
                layoutAll(ch);
            }
        }
    }

    /** 气泡本体 = 带圆角背景那层（ChatBubbles.RoundedPanel），私有的 → 用类名判 */
    static Component findBubbleBody(Component c) {
        if (c.getClass().getSimpleName().equals("RoundedPanel")) {
            return c;
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                Component r = findBubbleBody(ch);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    static int count(Component c) {
        int n = 0;
        for (MouseListener ml : c.getMouseListeners()) {
            if (ml.getClass().getSimpleName().contains("BubbleGesture")) {
                n++;
            }
        }
        return n;
    }

    static void collectHolders(Component c, List<Component> out) {
        if (count(c) > 0) {
            out.add(c);
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                collectHolders(ch, out);
            }
        }
    }

    static void collectButtons(Component c, List<String> out) {
        if (c instanceof AbstractButton) {
            AbstractButton ab = (AbstractButton) c;
            String t = ab.getText();
            out.add(ab.getClass().getSimpleName() + ":'" + (t == null ? "" : t) + "'");
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                collectButtons(ch, out);
            }
        }
    }

    // ==================================================================
    // 2026-09-28 新增：气泡三层叠加断言（B1~B4）
    //
    // 为什么要做像素级断言：ChatBubbles.RoundedPanel 这次改成「显式三层」——
    //   super.paintComponent（背景层） → CardSkin.paint（圆角卡片层） → paintChildren（文字层）。
    // 只看几何/bounds 是看不出层次反了的（旧代码就是先 fillRoundRect 再 super，
    // 顺序反着但 bounds 完全一样）。必须真的渲染一遍、按像素归属判定谁盖在谁上面：
    //   B1 卡片四角外 = Ui.PAGE_BG ⇒ 圆角没溢出、背景层在最下；
    //   B2 卡片内缩 3px 一圈 = Ui.BUBBLE_SELF ⇒ 卡片层真的画了；
    //   B3 气泡内 ≈ Ui.BUBBLE_TEXT 的像素 ≥30 ⇒ 文字画在卡片**之上**（没被卡片盖住/擦掉）；
    //   B4 文字墨迹包围盒完整落在气泡内、四边各留 ≥2px ⇒ 文字既没被气泡边界裁掉、也没贴边。
    // ==================================================================

    static final int TOL_STACK = 8;   // 「≈某层颜色」容差（和 AppPanelProbe A3 同口径）
    static final int TOL_TEXT = 40;   // 文字抗锯齿后墨迹像素与字色差异大得多，单独放宽

    static void checkLayers(JComponent row, Msg m) {
        System.out.println("--- 气泡三层叠加（像素级）---");
        // 拉到 800 宽（模拟消息面板把整行撑满）；height 用首选高，别让 BoxLayout 撑开
        int h = Math.max(1, row.getPreferredSize().height);
        row.setSize(800, h);
        layoutAll(row);
        Component body = findBubbleBody(row);
        if (body == null) {
            System.out.println("  ✗ 未找到气泡本体（RoundedPanel）→ 无法做层次断言");
            fail++;
            return;
        }

        // 渲染整行到 TYPE_INT_RGB：先用 Ui.PAGE_BG 铺底，模拟消息框背景层（第 1 层）
        Color pageBg = Ui.PAGE_BG;
        BufferedImage img = new BufferedImage(Math.max(1, row.getWidth()), Math.max(1, row.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(pageBg);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        row.printAll(g);   // printAll 才会递归子组件（气泡本体 + 文字）
        g.dispose();

        // 气泡本体在 row 坐标系里的位置（body 是 row 的孙子，必须换算，不能直接用 body.getBounds()）
        Point off = SwingUtilities.convertPoint(body, new Point(0, 0), row);
        Rectangle br = new Rectangle(off.x, off.y, body.getWidth(), body.getHeight());
        System.out.println("  row=" + row.getWidth() + "x" + row.getHeight() + " 气泡=" + br
                + "（" + body.getClass().getSimpleName() + "）PAGE_BG=" + hex(pageBg)
                + " BUBBLE_SELF=" + hex(Ui.BUBBLE_SELF) + " BUBBLE_PEER=" + hex(Ui.BUBBLE_PEER)
                + " BUBBLE_TEXT=" + hex(Ui.BUBBLE_TEXT));

        Color cardFill = (m.dir == Msg.DIR_SELF) ? Ui.BUBBLE_SELF : Ui.BUBBLE_PEER;

        // ---- B1 卡片不越圆角：四角外 1~2px 应 ≈ PAGE_BG ----
        boolean[] corners = {
            near(img, br.x + 1, br.y + 1, pageBg, TOL_STACK),
            near(img, br.x + br.width - 2, br.y + 1, pageBg, TOL_STACK),
            near(img, br.x + 1, br.y + br.height - 2, pageBg, TOL_STACK),
            near(img, br.x + br.width - 2, br.y + br.height - 2, pageBg, TOL_STACK),
            near(img, br.x + 2, br.y + 2, pageBg, TOL_STACK),
            near(img, br.x + br.width - 3, br.y + 2, pageBg, TOL_STACK),
            near(img, br.x + 2, br.y + br.height - 3, pageBg, TOL_STACK),
            near(img, br.x + br.width - 3, br.y + br.height - 3, pageBg, TOL_STACK),
        };
        int hit = 0;
        for (boolean c : corners) {
            if (c) {
                hit++;
            }
        }
        boolean b1 = hit == corners.length;   // 圆角半径 16px ⇒ 四角 1~2px 处必然全在圆角之外
        System.out.println((b1 ? "  ✓ " : "  ✗ ") + "B1 卡片不越圆角：气泡四角外 1~2px 像素命中 PAGE_BG="
                + hit + "/" + corners.length + "（逐点=" + java.util.Arrays.toString(corners) + "）");
        if (!b1) {
            fail++;
        }

        // ---- B2 卡片层存在：矩形内四边各内缩 3px 的一圈应 ≈ 气泡填色 ----
        int inset = 3;
        int x0 = br.x + inset;
        int x1 = br.x + br.width - 1 - inset;
        int y0 = br.y + inset;
        int y1 = br.y + br.height - 1 - inset;
        int ringTotal = 0;
        int ringHit = 0;
        for (int x = x0; x <= x1; x++) {
            ringTotal += 2;
            if (near(img, x, y0, cardFill, TOL_STACK)) {
                ringHit++;
            }
            if (near(img, x, y1, cardFill, TOL_STACK)) {
                ringHit++;
            }
        }
        for (int y = y0 + 1; y <= y1 - 1; y++) {
            ringTotal += 2;
            if (near(img, x0, y, cardFill, TOL_STACK)) {
                ringHit++;
            }
            if (near(img, x1, y, cardFill, TOL_STACK)) {
                ringHit++;
            }
        }
        boolean b2 = ringTotal > 0 && ringHit == ringTotal;
        System.out.println((b2 ? "  ✓ " : "  ✗ ") + "B2 卡片层存在：内缩 " + inset + "px 一圈命中气泡填色 "
                + hex(cardFill) + " = " + ringHit + "/" + ringTotal
                + "（样本 左上=" + hex(img, x0, y0) + " 右上=" + hex(img, x1, y0)
                + " 左下=" + hex(img, x0, y1) + " 右下=" + hex(img, x1, y1) + "）");
        if (!b2) {
            fail++;
        }

        // ---- B3/B4 文字层：气泡内 ≈ BUBBLE_TEXT 的墨迹像素 ----
        Color textCol = Ui.BUBBLE_TEXT;
        int ink = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = Math.max(0, br.y); y < Math.min(img.getHeight(), br.y + br.height); y++) {
            for (int x = Math.max(0, br.x); x < Math.min(img.getWidth(), br.x + br.width); x++) {
                if (near(img, x, y, textCol, TOL_TEXT)) {
                    ink++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        boolean b3 = ink >= 30;
        if (ink == 0) {
            System.out.println((b3 ? "  ✓ " : "  ✗ ") + "B3 文字在上层：墨迹像素=" + ink + "（要求≥30）"
                    + " ← 一个字都没匹配上，卡片可能把文字盖住了");
            System.out.println("  ✗ B4 文字不被裁：无墨迹可判（B3 已失败）");
            fail += 2;
        } else {
            Rectangle inkBox = new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
            int mL = minX - br.x;
            int mR = br.x + br.width - 1 - maxX;
            int mT = minY - br.y;
            int mB = br.y + br.height - 1 - maxY;
            System.out.println((b3 ? "  ✓ " : "  ✗ ") + "B3 文字在上层：气泡内 ≈BUBBLE_TEXT(" + hex(textCol)
                    + "±" + TOL_TEXT + ") 的墨迹像素=" + ink + "（要求≥30）");
            if (!b3) {
                fail++;
            }
            boolean b4 = mL >= 2 && mR >= 2 && mT >= 2 && mB >= 2;
            System.out.println((b4 ? "  ✓ " : "  ✗ ") + "B4 文字不被裁：墨迹包围盒=" + inkBox
                    + "⊂ 气泡=" + br + " 四边余量 左=" + mL + " 右=" + mR + " 上=" + mT + " 下=" + mB
                    + "（要求各≥2px）");
            if (!b4) {
                fail++;
            }
        }
    }

    static boolean near(BufferedImage img, int x, int y, Color want, int tol) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return false;
        }
        return near(new Color(img.getRGB(x, y)), want, tol);
    }

    static boolean near(Color a, Color b, int tol) {
        return Math.abs(a.getRed() - b.getRed()) <= tol
                && Math.abs(a.getGreen() - b.getGreen()) <= tol
                && Math.abs(a.getBlue() - b.getBlue()) <= tol;
    }

    static String hex(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return "越界";
        }
        return hex(new Color(img.getRGB(x, y)));
    }

    static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
