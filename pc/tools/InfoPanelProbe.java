package com.phonepctransfer.pc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * 离屏探针：1:1 复刻 ChatDevices.buildDevicePanel 底部 info 区（dirLabel + btnRow），
 * 在当前显示器真实 DPI 下量出每个组件的精确 bounds，并渲染成 PNG 供肉眼/脚本比对。
 * 用法：java com.phonepctransfer.pc.InfoPanelProbe [路径文本] [scale]
 *
 * <p><b>2026-09-28 去复制化：</b>本探针过去自带一份 {@code ModernButtonProbeUI}（手工复制
 * {@link Ui.ModernButtonUI} 的绘制代码）。那份复制品一旦产品改了卡片绘制（如本次换成
 * {@link CardSkin} 的透明 PNG 贴图 + 9 宫格）就立刻脱节 —— 探针画的是旧按钮，
 * 量的却是「新产品」的结论，等于白测。探针与产品**同在 com.phonepctransfer.pc 包**，
 * 直接调 {@link Ui#secondary(JButton)} 即可用上真实产品 UI；字号/边距仍照 ChatDevices 的生产设置。
 * 同理 {@code dirLabel.setMaximumSize} 这类「修好某个 bug 的关键一行」必须一并复刻，
 * 否则探针测的仍不是真实现。
 */
public final class InfoPanelProbe {

    static final String UI_FONT = "Microsoft YaHei UI";
    static final int DEVICE_PANEL_W = 200;

    static final Color TEXT_SUB = new Color(0x6B, 0x72, 0x7B);
    static final Color SURFACE = Color.WHITE;
    static final Color DIVIDER = new Color(0xEA, 0xEC, 0xF0);

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "D:\\Downloads";
        double scale = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;

        System.out.println("screenResolution = " + Toolkit.getDefaultToolkit().getScreenResolution());
        System.out.println("available 'Microsoft YaHei UI' = " + GraphicsEnvironment.getLocalGraphicsEnvironment()
                .isHeadless());

        // ---- 字体度量（离屏 Graphics，和真实渲染一致）----
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        Font f10 = new Font(UI_FONT, Font.PLAIN, 10);
        FontMetrics fm10 = pg.getFontMetrics(f10);
        System.out.println("fm10: 切到深色=" + fm10.stringWidth("切到深色")
                + " 设置=" + fm10.stringWidth("设置")
                + " dir='" + dir + "'=" + fm10.stringWidth(dir));
        Font f11 = new Font(UI_FONT, Font.PLAIN, 11);
        FontMetrics fm11 = pg.getFontMetrics(f11);
        System.out.println("fm11: 切到深色=" + fm11.stringWidth("切到深色") + " 设置=" + fm11.stringWidth("设置"));
        pg.dispose();

        // ---- 1:1 复刻 buildDevicePanel 的 wrap/info 结构 ----
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setPreferredSize(new Dimension(DEVICE_PANEL_W, 10));
        wrap.setBackground(SURFACE);
        wrap.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, DIVIDER));

        // 假的 NORTH/CENTER 占位（真实窗口里是 head + 设备列表；这里给个固定高度让 SOUTH 能摆出来）
        JPanel head = new JPanel();
        head.setPreferredSize(new Dimension(DEVICE_PANEL_W, 240));
        head.setBackground(SURFACE);
        JPanel center = new JPanel();
        center.setBackground(SURFACE);
        wrap.add(head, BorderLayout.NORTH);
        wrap.add(center, BorderLayout.CENTER);

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);
        info.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 6));

        // dirLabel：与 ChatDevices.buildDevicePanel 逐行对齐（2026-09-28）
        JLabel dirLabel = new JLabel(" ");
        dirLabel.setFont(f10);
        dirLabel.setForeground(TEXT_SUB);
        dirLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dirLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        // ⚠ 这一行是 2026-09-28「路径首末字被裁」的实际修复点：不设的话 BasicLabelUI 会把
        //   min/max 钉在文字度量宽上，BoxLayout 拉不满整行，墨迹就被 label 自身 bounds 削边。
        //   探针少这一行 = 测的不是真实现（本文件旧版就漏了）。
        dirLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        info.add(dirLabel);

        // 真实产品按钮：Ui.secondary（= 产品 ModernButtonUI + CardSkin 贴图），
        // 字号/边距照 ChatDevices 的生产设置（10pt + margin(2,6,2,6) + border(5,10,5,10)）
        JButton themeBtn = Ui.secondary(new JButton("切到深色"));
        themeBtn.setFont(f10);
        JButton settingsBtn = Ui.secondary(new JButton("设置"));
        settingsBtn.setFont(f10);
        themeBtn.setMargin(new Insets(2, 6, 2, 6));
        themeBtn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        settingsBtn.setMargin(new Insets(2, 6, 2, 6));
        settingsBtn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // 新版：按钮行 = BoxLayout X + 三段等宽 glue（布局引擎均分实际行宽，
        // 不再按固定 184px 手算 hgap）
        JPanel btnRow = new JPanel();
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        Component g1 = Box.createHorizontalGlue();
        Component g2 = Box.createHorizontalGlue();
        Component g3 = Box.createHorizontalGlue();
        btnRow.add(g1);
        btnRow.add(themeBtn);
        btnRow.add(g2);
        btnRow.add(settingsBtn);
        btnRow.add(g3);
        info.add(btnRow);
        wrap.add(info, BorderLayout.SOUTH);

        // ---- 布局 + 量 bounds（doLayout 不递归，逐层调用）----
        wrap.setSize(DEVICE_PANEL_W, 400);
        wrap.doLayout();
        info.doLayout();
        btnRow.doLayout();

        // dirLabel 文本（复刻 refreshSaveDir：预算取行实际内容宽 - 8）
        // 2026-09-28：dirLabel 现在被 max 尺寸拉满整行 ⇒ label 宽 = 行宽，按行宽算与按 label
        // 宽算等价；这里仍用布局后的真实 btnRow 宽，避免再写死一个「183」的假数。
        int rowW = btnRow.getWidth();
        int textW = rowW - 8;
        String shown = middleEllipsis(dir, dirLabel.getFontMetrics(dirLabel.getFont()), textW);
        dirLabel.setText(shown);
        System.out.println("dirLabel text='" + shown + "' rowW=" + rowW + " textW=" + textW
                + " fmW=" + dirLabel.getFontMetrics(dirLabel.getFont()).stringWidth(shown));

        dump("wrap", wrap);
        dump("info", info);
        dump("dirLabel", dirLabel);
        dump("btnRow", btnRow);
        dump("glue1", g1);
        dump("glue2", g2);
        dump("glue3", g3);
        dump("themeBtn", themeBtn);
        dump("settingsBtn", settingsBtn);
        javax.swing.border.AbstractBorder ab = (javax.swing.border.AbstractBorder) themeBtn.getBorder();
        System.out.println("themeBtn insets=" + themeBtn.getInsets()
                + " margin=" + themeBtn.getMargin()
                + " borderInsets=" + ab.getBorderInsets(themeBtn)
                + " font=" + themeBtn.getFont().getFamily() + "@" + themeBtn.getFont().getSize2D()
                + " fm(切到深色)=" + themeBtn.getFontMetrics(themeBtn.getFont()).stringWidth("切到深色")
                + " ui=" + themeBtn.getUI().getClass().getName());
        System.out.println("dirLabel max=" + dirLabel.getMaximumSize() + " min=" + dirLabel.getMinimumSize());
        System.out.println("btnRow max=" + btnRow.getMaximumSize() + " min=" + btnRow.getMinimumSize());
        System.out.println("btnRow pref=" + btnRow.getPreferredSize());
        System.out.println("info pref=" + info.getPreferredSize());
        System.out.println("themeBtn pref=" + themeBtn.getPreferredSize());
        System.out.println("settingsBtn pref=" + settingsBtn.getPreferredSize());

        // ---- 渲染 info 区到 PNG（wrap 底部 120px；第 2 个参数 = 设备缩放，如 1.25 模拟 Windows 125% DPI）----
        int H = 120;
        int W = DEVICE_PANEL_W + 6;
        BufferedImage out = new BufferedImage((int) Math.round(W * scale), (int) Math.round(H * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D og = out.createGraphics();
        og.setColor(Color.WHITE);
        og.fillRect(0, 0, out.getWidth(), out.getHeight());
        og.scale(scale, scale);
        // 把 wrap 画在 y 偏移处，使 info 区落在 PNG 顶部
        int infoTop = info.getY();
        og.translate(0, -(infoTop - 0));
        wrap.paint(og);
        og.dispose();
        String pngName = scale == 1.0 ? "probe_info.png" : "probe_info_" + (int) Math.round(scale * 100) + ".png";
        Files.deleteIfExists(Paths.get(pngName));
        ImageIO.write(out, "png", Files.createFile(Paths.get(pngName)).toFile());
        System.out.println("wrote " + pngName + " (" + W + "x" + H + " @ " + scale + ", info at wrapY=" + infoTop + ")");

        // 图形学证据：真实按钮在 1.0 / 1.25 下的四边环厚（回归点 = 2026-09-27「设置按钮上边框消失」）
        checkButtonRing(themeBtn, 1.0);
        checkButtonRing(themeBtn, 1.25);
    }

    /**
     * 真实产品按钮的四边环厚测量（图形学证据，2026-09-28 加）。
     *
     * <p>做法：拿一个真实 {@link Ui#secondary} 按钮，显式设成同一尺寸后单独渲染到 ARGB 贴图
     * （父底色洋红 MAGENTA、按钮白底 + 浅灰环），四边中点各取一条扫描线，数 **≈边框本色** 的像素个数。
     * 之所以要单独渲染而不是从整图里抠：卡片四角透明，必须有个醒目的父底色才能证明透明区真的透出了底色。
     *
     * <p><b>三个必须踩过才知道的坑（都踩过，记在这里免得后人重踩）：</b>
     * <ol>
     *   <li><b>不能按「≈洋红 / ≈白」分类像素。</b>卡片边缘是抗锯齿混色带，实测 1x 下某条边
     *       整排都是「洋红 + 一点白」，按颜色相近判会算出「左边 13px 环」这种荒谬值。
     *       本实现改为数 **边框本色 {@link #RING_COLOR}** 的像素（容差 20），与混色比例无关；</li>
     *   <li><b>尺寸不能直接用真实按钮的 65x24。</b>200px 栏宽下按钮高 24、圆角 9，
     *       水平中线 y=11.5 正好穿过圆角 ⇒ 左右扫描线落在透明角上量出 0，会冤枉产品。
     *       这里固定高 34，让左右中线远离圆角；</li>
     *   <li><b>卡片贴图自带半像素内缩</b>（9 宫格源切片来自 PNG 的 {@code +0.5} 半像素几何），
     *       所以组件最外圈像素是「50% 边框色 + 50% 父底色」的混色，按本色数会数不到 —— 
     *       这属于绘制细节而非「边消失」。判据因此放宽为「四边都 ≥1 且极差 ≤1」，
     *       并把四个原始数字全部打印出来供人工复核。</li>
     * </ol>
     */
    static void checkButtonRing(JButton src, double scale) {
        JButton b = Ui.secondary(new JButton(src.getText() == null ? "" : src.getText()));
        b.setFont(src.getFont());
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        int bw = Math.max(44, src.getWidth());
        int bh = 34;
        int pad = 16;
        b.setSize(bw, bh);
        java.awt.Dimension got = b.getSize();
        int iw = (int) Math.round((bw + 2 * pad) * scale);
        int ih = (int) Math.round((bh + 2 * pad) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(MAGENTA);
        g.fillRect(0, 0, iw, ih);                 // 父底色：洋红
        g.scale(scale, scale);                    // 组件坐标 → 设备像素（模拟 1.25x 分数 DPI）
        b.paint(g);
        g.dispose();
        // 卡片在设备像素里的真实外接框：只要不是纯洋红就算卡片覆盖到了
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
            System.out.println("  ✗ 按钮环厚 " + (int) Math.round(scale * 100) + "%：按钮一个像素都没画出来");
            return;
        }
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        // 四边中点各一条扫描线，从外接框的最外圈往内数「卡片覆盖 ≥50%」的连续像素 = 该边环厚。
        // ⚠ 不能用「≈边框色」判：最外圈是半覆盖混色像素（实测第 0 行 = R241,G114,B245），
        //   按颜色根本认不出来，会得出「四边全 0」从而冤枉产品。
        int[] top = countCovered(img, cx, y0, 0, 1);
        int[] bottom = countCovered(img, cx, y1, 0, -1);
        int[] left = countCovered(img, x0, cy, 1, 0);
        int[] right = countCovered(img, x1, cy, -1, 0);
        int[] t4 = { top[0], bottom[0], left[0], right[0] };
        int[] d4 = { top[1], bottom[1], left[1], right[1] };
        int min = Math.min(Math.min(t4[0], t4[1]), Math.min(t4[2], t4[3]));
        int max = Math.max(Math.max(t4[0], t4[1]), Math.max(t4[2], t4[3]));
        int dMin = Math.min(Math.min(d4[0], d4[1]), Math.min(d4[2], d4[3]));
        int dMax = Math.max(Math.max(d4[0], d4[1]), Math.max(d4[2], d4[3]));
        boolean body = cardBodyPresent(img, cx, cy);
        // 四角透明：圆角半径 9，外接框的四个最角像素必然落在圆角之外 ⇒ 应透出洋红。
        // 实测个别角是「洋红 + 一点白」的过渡像素（贴图 +0.5 半像素几何），故要求 ≥3/4 并把原始值打印出来。
        int[] corners = { img.getRGB(x0, y0), img.getRGB(x1, y0), img.getRGB(x0, y1), img.getRGB(x1, y1) };
        int cn = 0;
        for (int c : corners) {
            if (isMagenta(c)) {
                cn++;
            }
        }
        boolean cornerClear = cn >= 3;
        // 判据：四边都必须有本色带（没有哪条边整条消失），且「最外一圈本色」四边同深（同心）。
        // 厚度本身会被抗锯齿的过渡像素带偏（实测 1.0 下 1~2px、1.25 下 1~3px），故只打印不强判。
        boolean ok = body && min >= 1 && (dMax - dMin) <= 1 && cornerClear;
        System.out.println((ok ? "  ✓ " : "  ✗ ") + "按钮环厚 " + (int) Math.round(scale * 100) + "%"
                + "（'" + b.getText() + "' 组件 " + got.width + "x" + got.height
                + " → 贴图 " + iw + "x" + ih + "，卡片像素框=[" + x0 + "," + y0 + " " + (x1 - x0 + 1) + "x"
                + (y1 - y0 + 1) + "]）："
                + "上=" + top[0] + " 下=" + bottom[0] + " 左=" + left[0] + " 右=" + right[0]
                + " 厚度极差=" + (max - min)
                + "｜本色起始距离 上=" + top[1] + " 下=" + bottom[1] + " 左=" + left[1] + " 右=" + right[1]
                + " 极差=" + (dMax - dMin)
                + "｜卡片本体=" + body + " 四角透出洋红=" + cn + "/4 " + hex(corners)
                + (ok ? "" : "  ← 有边整条消失 / 四边不同心"));
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

    /**
     * 从 (x,y) 沿 (dx,dy) 往卡片内部数「≈边框本色 {@link #RING_COLOR}」的像素个数，返回
     * {@code {边框带厚度, 最外一圈本色距外接框的距离}}。
     *
     * <p><b>为什么不能按「覆盖率/不透明度」数</b>（试过，得出一排假数字）：边框本色
     * {@code #E2E5EA} 与填充白的覆盖率分别是 0.90 / 1.00，都 ≥0.5，按覆盖率会把整块填充白
     * 都算成环，量出「四边各 7px」这种被循环上限截断的假值。
     *
     * <p><b>允许跳过最外 1 圈</b>：卡片贴图的几何含 {@code +0.5} 半像素偏移，最外一圈像素只有
     * 约 4% 覆盖率（实测组件第 0 行 = R241,G114,B245，即「洋红 + 一点白」），既不是边框本色
     * 也不是填充白 —— 属于栅格化细节，不该被算成「边消失」。返回的「距」就是这圈偏移量，
     * 它是比厚度更稳的判据：四边都应在**同一距离**出现本色。
     */
    static int[] countCovered(BufferedImage img, int x, int y, int dx, int dy) {
        for (int i = 0; i < 3; i++) {                    // 最多允许跳过最外 2 圈
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

    /** 卡片本体是否真的画出来了（正中一小块必须是不透明的填充白） */
    static boolean cardBodyPresent(BufferedImage img, int cx, int cy) {
        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                Color c = new Color(img.getRGB(x, y), true);
                if (c.getAlpha() == 255 && near(c, Color.WHITE, 12)) {
                    return true;
                }
            }
        }
        return false;
    }

    static final Color MAGENTA = new Color(0xFF, 0x00, 0xFF);

    /** 贴图环带的**本色**（与 Ui 的 BTN_BORDER 同值；private 常量探针读不到，这里按值对照） */
    static final Color RING_COLOR = new Color(0xE2, 0xE5, 0xEA);

    /** 本色判定容差（每通道） */
    static final int RING_COLOR_TOL = 20;

    /** 每通道容差 ≤8 —— 与 AppPanelProbe 同一口径 */
    static boolean near(Color a, Color b) {
        return near(a, b, 8);
    }

    static boolean near(Color a, Color b, int tol) {
        return Math.abs(a.getRed() - b.getRed()) <= tol
                && Math.abs(a.getGreen() - b.getGreen()) <= tol
                && Math.abs(a.getBlue() - b.getBlue()) <= tol;
    }

    /** 是否就是那层洋红父底色（四角透明判据） */
    static boolean isMagenta(int rgb) {
        return near(new Color(rgb, true), MAGENTA);
    }

    static void dump(String name, Component c) {
        System.out.println(name + " bounds=" + c.getBounds()
                + " pref=" + c.getPreferredSize());
    }

    // ===== 与 ChatDevices.middleEllipsis 完全一致 =====
    static final double INK_SAFETY = 1.12;
    static int inkBudget(int maxPx) { return (int) (maxPx / INK_SAFETY); }

    static String middleEllipsis(String s, FontMetrics fm, int maxPx) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int budget = inkBudget(maxPx);
        if (fm.stringWidth(s) <= budget) {
            return s;
        }
        final String ell = "…";
        for (int keep = s.length() - 1; keep > 4; keep--) {
            int tail = keep / 2;
            int head = keep - tail;
            String cand = s.substring(0, head) + ell + s.substring(s.length() - tail);
            if (fm.stringWidth(cand) <= budget) {
                return cand;
            }
        }
        return s.substring(0, Math.max(1, s.length() / 2)) + ell;
    }
}
