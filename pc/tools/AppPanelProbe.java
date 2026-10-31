package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 端到端校验（2026-09-28）：用**真实应用类**（Ui/ChatWindow/ChatDevices/Store/Link）
 * 构建真实设备面板 → 布局 → 1x 与 1.25x 渲染成 PNG + 打印几何。
 * 与 InfoPanelProbe（手工复刻结构）不同，这里跑的是 dist jar 里同一份代码。
 *
 * <p>2026-09-28 追加断言（全部基于**布局后的真实 bounds** 与**渲染后的像素**，失败即 ✗ 且退出码非 0）：
 * <ul>
 *   <li><b>A1</b> dirLabel 墨迹余量 ≥3px（12% 安全系数口径 = ChatDevices.INK_SAFETY）；</li>
 *   <li><b>A2</b> themeBtn/settingsBtn 文字墨迹不被按钮内容宽裁掉（余量 ≥2px）；</li>
 *   <li><b>A3</b> 真实 {@link Ui#secondary} 按钮在 1.0 / 1.25 下四边环厚 ≥1 且极差 ≤1
 *       —— 直接回归 2026-09-27 用户实测「设置按钮上边框消失」；</li>
 *   <li><b>A4</b> {@link CardSkin} 走的是**真·PNG 往返**（ImageIO.write 得回 BufferedImage 且四角透明）。</li>
 * </ul>
 */
public final class AppPanelProbe {

    /** 断言失败计数：>0 ⇒ 退出码 1（探针要能被脚本/CI 判定） */
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        // uiScale 必须在任何 AWT 类初始化之前设（同 PcMain）
        System.setProperty("sun.java2d.uiScale", "1.25");
        Ui.install();
        Path root = Store.defaultRoot();
        Store store = new Store(root);
        store.load();
        Ui.applyDark(store.darkTheme());
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);

        JPanel info = findInfoPanel(win.frame);
        if (info == null) {
            System.out.println("FATAL: info panel not found");
            System.exit(2);
        }
        // 必须真正显示一次让布局跑完（doLayout 不显示不生效）；窗口只闪一下
        win.frame.setVisible(true);
        Thread.sleep(1500);
        // 显式刷一次目录文本（构造期那次 invokeLater 可能还没跑）
        win.devices.refreshSaveDir();
        javax.swing.SwingUtilities.invokeAndWait(() -> win.frame.validate());

        System.out.println("receivedDirText = " + store.receivedDirText());
        System.out.println("infoPanel bounds=" + info.getBounds());
        System.out.println("dirLabel text='" + ((JLabel) info.getComponent(0)).getText()
                + "' bounds=" + info.getComponent(0).getBounds());
        for (Component c : info.getComponents()) {
            System.out.println("  child " + c.getClass().getSimpleName() + " bounds=" + c.getBounds());
            if (c instanceof JPanel) {
                for (Component c2 : ((JPanel) c).getComponents()) {
                    String t = c2 instanceof JButton ? " '" + ((JButton) c2).getText() + "'" : "";
                    System.out.println("    " + c2.getClass().getSimpleName() + t + " bounds=" + c2.getBounds());
                }
            }
        }

        render(info, "app_panel_1.png", 1.0);
        render(info, "app_panel_125.png", 1.25);

        // ===== 断言（真实布局 + 真实像素）=====
        JLabel dirLabel = (JLabel) info.getComponent(0);
        JButton themeBtn = null;
        JButton settingsBtn = null;
        JButton anyBtn = null;
        for (Component c : info.getComponents()) {
            if (!(c instanceof JPanel)) {
                continue;
            }
            for (Component c2 : ((JPanel) c).getComponents()) {
                if (c2 instanceof JButton) {
                    JButton b = (JButton) c2;
                    anyBtn = anyBtn == null ? b : anyBtn;
                    if ("设置".equals(b.getText())) {
                        settingsBtn = b;
                    } else if (b.getText() != null && b.getText().startsWith("切到")) {
                        themeBtn = b;
                    }
                }
            }
        }
        System.out.println("===== 断言（真实布局 bounds + 渲染像素）=====");
        checkDirLabelInk(dirLabel);
        checkButtonInk("themeBtn", themeBtn);
        checkButtonInk("settingsBtn", settingsBtn);
        // A3 用真实产品按钮（若左栏按钮没找到，才退化新建一个 Ui.secondary）
        checkButtonShell(themeBtn != null ? themeBtn : Ui.secondary(new JButton("切到深色")), "themeBtn");
        checkCardSkinPng();

        System.out.println(fail == 0 ? "APP PANEL PROBE PASS" : ("APP PANEL PROBE FAIL (" + fail + ")"));
        System.out.println("done");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ==================== A1 ====================

    /**
     * A1：dirLabel 墨迹余量。label 已被 max 尺寸拉满整行，文本居中 ⇒ 两侧余量 = (labelW - inkW)/2。
     * inkW 按 1.12 安全系数算（抗锯齿真实墨迹比 FontMetrics.stringWidth 宽 5~13%，同源口径）。
     */
    static void checkDirLabelInk(JLabel l) {
        String text = l.getText() == null ? "" : l.getText();
        Font_ fm = new Font_(l);
        int labelW = l.getWidth();
        int textW = fm.stringWidth(text);
        int inkW = (int) Math.ceil(textW * 1.12);
        int margin = (labelW - inkW) / 2;
        boolean ok = margin >= 3;
        System.out.println((ok ? "  ✓ " : "  ✗ ") + "A1 dirLabel 墨迹余量：text='" + text + "'"
                + " labelW=" + labelW + " textW(fm)=" + textW + " inkW(×1.12)=" + inkW
                + " 两侧余量=" + margin + "px (要求≥3px)"
                + " font=" + l.getFont().getFamily() + "@" + l.getFont().getSize2D()
                + " max=" + l.getMaximumSize() + " alignX=" + l.getAlignmentX());
        if (!ok) {
            fail++;
        }
    }

    /** 只包一层 FontMetrics，避免在断言里反复写 l.getFontMetrics(l.getFont()) */
    static final class Font_ {
        private final java.awt.FontMetrics fm;

        Font_(JLabel l) {
            this.fm = l.getFontMetrics(l.getFont());
        }

        int stringWidth(String s) {
            return fm.stringWidth(s);
        }
    }

    // ==================== A2 ====================

    /** A2：按钮文字墨迹不被按钮内容宽裁掉。内容宽 = getWidth() - insets(左右) */
    static void checkButtonInk(String tag, JButton b) {
        if (b == null) {
            System.out.println("  ✗ A2 " + tag + "：按钮未找到（真实左栏里没有这个按钮？）");
            fail++;
            return;
        }
        String text = b.getText() == null ? "" : b.getText();
        int textW = b.getFontMetrics(b.getFont()).stringWidth(text);
        int inkW = (int) Math.ceil(textW * 1.12);
        java.awt.Insets ins = b.getInsets();
        int contentW = b.getWidth() - ins.left - ins.right;
        int margin = contentW - inkW;
        boolean ok = margin >= 2;
        System.out.println((ok ? "  ✓ " : "  ✗ ") + "A2 " + tag + " 文字余量：text='" + text + "'"
                + " 按钮=" + b.getWidth() + "x" + b.getHeight()
                + " insets=" + ins.left + "/" + ins.right + " contentW=" + contentW
                + " textW(fm)=" + textW + " inkW(×1.12)=" + inkW
                + " 余量=" + margin + "px (要求≥2px)"
                + " font=" + b.getFont().getSize2D() + "pt pref=" + b.getPreferredSize()
                + " ui=" + b.getUI().getClass().getName());
        if (!ok) {
            fail++;
        }
    }

    // ==================== A3 ====================

    static final Color MAGENTA = new Color(0xFF, 0x00, 0xFF);

    /**
     * A3：把**真实产品按钮**单独渲染到 ARGB 图（父底色 = 洋红，按钮自身白底 + 边框色），
     * 分别在 scale 1.0 / 1.25 下（{@code g2.scale(s,s)}）量四边环厚，四边中点各一条扫描线。
     *
     * <p>判据与两个坑详见 {@link #countRing}（按边框本色数、允许跳过最外 2 圈半像素混色）。
     * 断言口径：四边都必须有本色带（回归 2026-09-27「设置按钮上边框消失」），
     * 且「最外一圈本色」四边同深（同心）；厚度本身受抗锯齿过渡像素影响只打印不强判。
     *
     * <p>尺寸为什么取高 34：200px 栏宽下真实按钮高只有 24、圆角 9，水平中线 y=11.5 正好穿过圆角，
     * 左右扫描线会落在透明角上量出 0（第一版的坑，会冤枉产品）。34 高让左右中线远离圆角。
     */
    static void checkButtonShell(JButton src, String tag) {
        for (double scale : new double[] { 1.0, 1.25 }) {
            checkButtonShell(src, tag, scale);
        }
    }

    static void checkButtonShell(JButton src, String tag, double scale) {
        JButton b = uiSecondary(new JButton(src.getText() == null ? "" : src.getText()));
        b.setFont(src.getFont());
        b.setMargin(src.getMargin() == null ? new java.awt.Insets(2, 6, 2, 6) : src.getMargin());
        int bw = Math.max(44, src.getWidth());
        int bh = 34;                          // 左右扫描线 y≈17，远离 arc=9 的圆角区
        int pad = 16;
        b.setSize(bw, bh);
        int iw = (int) Math.round((bw + 2 * pad) * scale);
        int ih = (int) Math.round((bh + 2 * pad) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(MAGENTA);
        g.fillRect(0, 0, iw, ih);        // 父底色：醒目洋红，透明区必须透出它
        g.scale(scale, scale);           // 组件坐标 → 设备像素（模拟分数 DPI 缩放）
        b.paint(g);
        g.dispose();

        // 卡片在设备像素里的外接框（只要不是纯洋红就算卡片覆盖到了）
        int x0 = iw;
        int y0 = ih;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                if (!isMagenta(img.getRGB(x, y))) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) {
            System.out.println("  ✗ A3 " + tag + " @" + (int) Math.round(scale * 100)
                    + "%：按钮一个像素都没画出来");
            fail++;
            return;
        }
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        // 四边中点各一条扫描线，数「≈边框本色」的像素个数（厚度）+ 最外一圈本色距外接框的距离
        int[] top = countRing(img, cx, y0, 0, 1);
        int[] bottom = countRing(img, cx, y1, 0, -1);
        int[] left = countRing(img, x0, cy, 1, 0);
        int[] right = countRing(img, x1, cy, -1, 0);
        int[] th = { top[0], bottom[0], left[0], right[0] };
        int[] di = { top[1], bottom[1], left[1], right[1] };
        int min = Math.min(Math.min(th[0], th[1]), Math.min(th[2], th[3]));
        int max = Math.max(Math.max(th[0], th[1]), Math.max(th[2], th[3]));
        int dMin = Math.min(Math.min(di[0], di[1]), Math.min(di[2], di[3]));
        int dMax = Math.max(Math.max(di[0], di[1]), Math.max(di[2], di[3]));
        // 四角透明：卡片圆角半径 9，外接框的**四个最角**像素必然落在圆角之外 ⇒ 应透出洋红。
        // 实测其中个别角是「洋红 + 一点白」的过渡像素（贴图 +0.5 半像素几何），故要求 4 个里 ≥3 个命中，
        // 并把四个原始值打印出来供人工复核（而不是用全等去卡一个栅格化细节）。
        int[] corners = {
            img.getRGB(x0, y0), img.getRGB(x1, y0), img.getRGB(x0, y1), img.getRGB(x1, y1),
        };
        int cn = 0;
        for (int c : corners) {
            if (isMagenta(c)) {
                cn++;
            }
        }
        boolean cornerClear = cn >= 3;
        // 判据：四边都必须有本色带（没有哪条边整条消失），且「最外一圈本色」四边同深（同心）。
        // 厚度会被抗锯齿过渡像素带偏（实测 1.0 下 1~2px、1.25 下 1~3px），故只打印不强判。
        boolean ok = min >= 1 && (dMax - dMin) <= 1 && cornerClear;
        System.out.println((ok ? "  ✓ " : "  ✗ ") + "A3 " + tag + " 圆角卡片环厚 @"
                + (int) Math.round(scale * 100) + "%：按钮 " + bw + "x" + bh + " → 贴图 " + iw + "x" + ih
                + "，卡片像素框=[" + x0 + "," + y0 + " " + (x1 - x0 + 1) + "x" + (y1 - y0 + 1) + "]"
                + " 四边环厚 上=" + top[0] + " 下=" + bottom[0] + " 左=" + left[0] + " 右=" + right[0]
                + "（极差=" + (max - min) + "）"
                + " 本色起始距离 上=" + top[1] + " 下=" + bottom[1] + " 左=" + left[1] + " 右=" + right[1]
                + "（极差=" + (dMax - dMin) + "）"
                + " 四角透出洋红=" + cn + "/4 " + hex(corners)
                + (ok ? "" : "  ← 有边整条消失 / 四边不同心（2026-09-27 回归点）"));
        if (!ok) {
            fail++;
        }
    }

    /**
     * 从外接框最外圈往卡片内部数「≈边框本色 {@link #RING_COLOR}」的连续像素个数，
     * 返回 {@code {边框厚度, 最外一圈本色距外接框的距离}}。
     *
     * <p><b>两个必须知道的坑（都实测踩过）：</b>
     * <ol>
     *   <li><b>不能按「≈洋红 / ≈白」分类像素</b>：卡片边缘是抗锯齿混色带，按颜色相近判会算出
     *       「左边 13px 环」这种荒谬值（洋红与白的浅混色既不像底也不像填充）；</li>
     *   <li><b>也不能按「覆盖率 ≥0.5」数</b>：边框本色 #E2E5EA 与填充白的覆盖率分别是 0.90 / 1.00，
     *       都过阈值 ⇒ 整块填充白都会被算成环，量出「四边各 7px」这种被循环上限截断的假值。
     *       按本色 + 容差 20 数才是对的。</li>
     * </ol>
     * 允许跳过最外 2 圈：贴图几何含 {@code +0.5} 半像素偏移，最外一圈只有约 4% 覆盖率
     * （「洋红 + 一点白」），既非本色也非填充白，属栅格化细节，不该被算成「边消失」。
     */
    static int[] countRing(BufferedImage img, int x, int y, int dx, int dy) {
        for (int i = 0; i < 3; i++) {
            int px = x + dx * i;
            int py = y + dy * i;
            if (px < 0 || py < 0 || px >= img.getWidth() || py >= img.getHeight()) {
                break;
            }
            if (!near(new Color(img.getRGB(px, py), true), RING_COLOR, RING_COLOR_TOL)) {
                continue;
            }
            int n = 0;
            for (int j = i; j < 10; j++) {
                int qx = x + dx * j;
                int qy = y + dy * j;
                if (qx < 0 || qy < 0 || qx >= img.getWidth() || qy >= img.getHeight()) {
                    break;
                }
                if (!near(new Color(img.getRGB(qx, qy), true), RING_COLOR, RING_COLOR_TOL)) {
                    break;
                }
                n++;
            }
            return new int[] { n, i };
        }
        return new int[] { 0, -1 };
    }

    /** 贴图环带的**本色**（与 Ui 的 BTN_BORDER 同值；那是 private 常量，探针按值对照） */
    static final Color RING_COLOR = new Color(0xE2, 0xE5, 0xEA);

    /** 本色判定容差（每通道） */
    static final int RING_COLOR_TOL = 20;

    static boolean isMagenta(int rgb) {
        return near(new Color(rgb, true), MAGENTA);   // 每通道容差 ≤8
    }

    // ==================== A4 ====================

    /**
     * A4：{@link CardSkin} 的贴图确实是「PNG 往返」产物，而不是直接用的内存 BufferedImage。
     *
     * <p>为什么用反射读私有缓存而不是改产品代码开个 getter：产品的封装是对的（贴图是内部实现），
     * 探针不该为自检反过来污染产品 API。反射失败（模块/权限）时退化为「渲染四角透明」检查 ——
     * 那也能证明圆角卡片透明生效，只是不能直接证明走了 PNG 编码那一步。
     */
    static void checkCardSkinPng() {
        // 先走一遍真实绘制入口，把按钮贴图灌进缓存（拿到的就一定是产品实际用的那张）
        JButton b = uiSecondary(new JButton("设置"));
        boolean cornerAlpha = renderOffscreen(b);
        try {
            Field f = CardSkin.class.getDeclaredField("CACHE");
            f.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) f.get(null);
            Method cardPng = CardSkin.class.getDeclaredMethod("cardPng", int.class, Color.class, Color.class, double.class);
            cardPng.setAccessible(true);
            Object img = cardPng.invoke(null, 9, Color.WHITE, new Color(0xE2, 0xE5, 0xEA), 1.8);
            boolean isBi = img instanceof BufferedImage;
            String cls = img == null ? "null" : img.getClass().getName();
            // ⚠ 不能断言 getType()==TYPE_INT_ARGB：PNG 解码回来的是 4BYTE_ABGR 之类（实测），
            //   类型号随编码/解码器而变，真正该断言的是「色彩模型带 alpha」+「四角 alpha=0」。
            boolean hasAlpha = isBi && ((BufferedImage) img).getColorModel().hasAlpha();
            String typeStr = isBi ? String.valueOf(((BufferedImage) img).getType()) : "-";
            boolean wrotePng = false;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                wrotePng = isBi && ImageIO.write((BufferedImage) img, "png", bo) && bo.size() > 0;
                if (wrotePng) {
                    System.out.println("       PNG 字节=" + bo.size() + "B（ImageIO 编码成功 ⇒ 是可解码的 PNG 贴图）");
                }
            } catch (Throwable t) {
                System.out.println("       ImageIO.write 抛异常：" + t);
            }
            boolean cornerClear = false;
            boolean centerFill = false;
            if (isBi) {
                BufferedImage bi = (BufferedImage) img;
                int s = bi.getWidth() - 1;
                cornerClear = (bi.getRGB(0, 0) >>> 24) == 0 && (bi.getRGB(s, 0) >>> 24) == 0
                        && (bi.getRGB(0, s) >>> 24) == 0 && (bi.getRGB(s, s) >>> 24) == 0;
                int c = bi.getRGB(bi.getWidth() / 2, bi.getHeight() / 2);
                centerFill = (c >>> 24) == 0xFF && near(new Color(c, true), Color.WHITE);
            }
            boolean ok = isBi && hasAlpha && wrotePng && cornerClear && centerFill;
            System.out.println((ok ? "  ✓ " : "  ✗ ") + "A4 CardSkin PNG 往返：缓存条目=" + cache.size()
                    + " Image.class=" + cls + " 带alpha=" + hasAlpha + "(type=" + typeStr + ")"
                    + " 可编码PNG=" + wrotePng + " 四角alpha=0=" + cornerClear + " 中心=填充白=" + centerFill);
            if (!ok) {
                fail++;
            }
        } catch (Throwable t) {
            // 退化路径：至少证明圆角卡片四角真的透出父底色（透明生效）
            System.out.println((cornerAlpha ? "  ✓ " : "  ✗ ") + "A4（反射不可用，退化判据）CardSkin 圆角透明："
                    + t + " → 按钮四角透出父底色=" + cornerAlpha);
            if (!cornerAlpha) {
                fail++;
            }
        }
    }

    static JButton uiSecondary(JButton b) {
        return Ui.secondary(b);
    }

    /** 渲染一个真实 secondary 按钮到洋红底上，返回「四角是否透出父底色」 */
    static boolean renderOffscreen(JButton b) {
        int bw = 80;
        int bh = 30;
        b.setSize(bw, bh);
        BufferedImage img = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(MAGENTA);
        g.fillRect(0, 0, bw, bh);
        b.paint(g);
        g.dispose();
        return isMagenta(img.getRGB(0, 0)) && isMagenta(img.getRGB(bw - 1, 0))
                && isMagenta(img.getRGB(0, bh - 1)) && isMagenta(img.getRGB(bw - 1, bh - 1));
    }

    // ==================== 像素/几何工具 ====================

    static boolean near(Color a, Color b) {
        return near(a, b, 8);
    }

    /** 把若干像素值打成 #RRGGBB 列表（复核用） */
    static String hex(int[] rgbs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rgbs.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("#%06X", rgbs[i] & 0xFFFFFF));
        }
        return sb.append(']').toString();
    }

    static boolean near(Color a, Color b, int tol) {
        return Math.abs(a.getRed() - b.getRed()) <= tol
                && Math.abs(a.getGreen() - b.getGreen()) <= tol
                && Math.abs(a.getBlue() - b.getBlue()) <= tol;
    }

    /** 找左栏底部 info 面板：恰好 2 个子组件 = JLabel + 内含 2 个按钮（其一文案「设置」）的 JPanel */
    static JPanel findInfoPanel(Container c) {
        if (c instanceof JPanel) {
            JPanel p = (JPanel) c;
            Component[] ch = p.getComponents();
            if (ch.length == 2 && ch[0] instanceof JLabel && ch[1] instanceof JPanel) {
                int btns = 0;
                boolean hasSettings = false;
                for (Component x : ((JPanel) ch[1]).getComponents()) {
                    if (x instanceof JButton) {
                        btns++;
                        if ("设置".equals(((JButton) x).getText())) {
                            hasSettings = true;
                        }
                    }
                }
                if (btns == 2 && hasSettings) {
                    return p;
                }
            }
        }
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                JPanel r = findInfoPanel((Container) ch);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    static void render(JComponent comp, String name, double scale) throws Exception {
        int w = comp.getWidth();
        int h = comp.getHeight();
        BufferedImage img = new BufferedImage((int) Math.round(w * scale), (int) Math.round(h * scale),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(scale, scale);
        comp.paint(g);
        g.dispose();
        Path p = Path.of(name);
        Files.deleteIfExists(p);
        ImageIO.write(img, "png", Files.createFile(p).toFile());
        System.out.println("wrote " + name + " (" + w + "x" + h + " @ " + scale + ")");
    }
}
