package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;

/**
 * 弹菜单配色回归探针（2026-09-28）—— 纯离屏、无 GUI、不抢端口，实例开着也能跑。
 *
 * <p>它守的是三件事：
 * <ol>
 *   <li>弹菜单外框色（R1/R2「太浅 / 看不见」）与相邻底的亮度差 ≥45；</li>
 *   <li>菜单项底色跟主题（改前恒为 LAF 亮灰 #EEEEEE，深色下与近白文字撞成一团）；</li>
 *   <li>**长期存活**的弹菜单在运行期切主题后能被 Ui.theme() 刷新（托盘菜单的根因）。</li>
 * </ol>
 *
 * <p>⚠ JPopupMenu 是重型弹窗，**画不进父组件的 printAll**；但把它自己 setSize + 递归 doLayout
 * 后直接 printAll(自己) 是可以的（本探针就是这么量的）。别指望在 UiWinProbe 的整窗图里看到菜单。
 */
public final class ThemeMenuProbe {

    static int fail = 0;
    static final int TOL_LUM = 45;    // 边框 vs 相邻底的最小亮度差
    static final int TOL_TEXT = 150;  // 菜单项行内文字 vs 底的最小亮度极差

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "../shots";
        new File(out).mkdirs();

        Ui.install();

        // ① 启动期两条路径（= PcMain.main 的顺序：install → applyDark(d)）
        Ui.applyDark(false);
        check("浅色(启动期)", out + "/probe_menu_light.png");
        Ui.applyDark(true);
        check("深色(启动期)", out + "/probe_menu_dark.png");

        // ② 运行期切换路径：浅色建好的菜单 → 切深色 → Ui.theme() 必须把它刷过来
        //    （= 托盘菜单场景；改前这里停在浅色，就是 R4 的根因）
        Ui.applyDark(false);
        JPopupMenu longLived = build();
        Ui.applyDark(true, null);
        System.out.println("=== 运行期切到深色 + Ui.theme()（托盘菜单场景）===");
        Ui.theme(longLived);
        checkMenu("tray-sim", longLived, Ui.MENU_BG, Ui.MENU_FG, Ui.MENU_BORDER, out + "/probe_menu_tray_sim.png");

        // ③ 悬停行铺底：Metal 用 MenuItem.selectionBackground，必须实测（别照抄猜测）
        JPopupMenu armed = build();
        Ui.theme(armed);
        JMenuItem first = (JMenuItem) armed.getComponents()[0];
        first.getModel().setRollover(true);
        first.getModel().setArmed(true);
        armed.setSize(armed.getPreferredSize());
        layoutAll(armed);
        BufferedImage aimg = render(armed);
        Rectangle r = first.getBounds();
        int y = r.y + r.height / 2;
        String armedColor = hex(aimg, r.x + 3, y);
        String normalColor = hex(aimg, r.x + 3, Math.min(aimg.getHeight() - 1, r.y + r.height + 8));
        boolean m10 = same(armedColor, hexOf(Ui.MENU_SEL_BG)) && same(normalColor, hexOf(Ui.MENU_BG));
        System.out.println((m10 ? "  ✓ " : "  ✗ ") + "M10 悬停行=" + armedColor + "（期望 "
                + hexOf(Ui.MENU_SEL_BG) + "） 非悬停行=" + normalColor + "（期望 " + hexOf(Ui.MENU_BG) + "）");
        if (!m10) fail++;

        System.out.println(fail == 0 ? "MENU THEME PROBE PASS" : ("MENU THEME PROBE FAIL(" + fail + ")"));
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------- 断言主体 ----------

    static void check(String tag, String png) throws Exception {
        System.out.println("=== " + tag + " ===");
        checkMenu(tag, build(), Ui.MENU_BG, Ui.MENU_FG, Ui.MENU_BORDER, png);
    }

    static void checkMenu(String tag, JPopupMenu pm, Color bg, Color fg, Color border, String png)
            throws Exception {
        // M1/M3 popup 底色
        yes("M1/M3 popup.bg == " + hexOf(bg), same(pm.getBackground(), bg));

        // M2/M4 边框色：必须能从 Border 里读出 MENU_BORDER
        Border b = pm.getBorder();
        Color bc = (b instanceof LineBorder) ? ((LineBorder) b).getLineColor() : null;
        yes("M2/M4 popup.border == " + hexOf(border) + "（实际 "
                + (bc == null ? String.valueOf(b) : hexOf(bc)) + "）", same(bc, border));

        // M5 菜单项底色/文字色
        for (Component c : pm.getComponents()) {
            if (c instanceof JMenuItem) {
                JMenuItem mi = (JMenuItem) c;
                yes("M5 item '" + mi.getText() + "' bg/fg == " + hexOf(bg) + "/" + hexOf(fg),
                        same(mi.getBackground(), bg) && same(mi.getForeground(), fg));
                // M12 字体（字体管理器返回的家族名大小写不固定 —— 本机实测 getFontName() 是
                // "Microsoft Yahei UI"，故按小写前缀比对；判据仍是「YaHei 12、中文不成方块」）
                yes("M12 字体 == Microsoft YaHei UI/12",
                        mi.getFont() != null && mi.getFont().getSize() == 12
                                && mi.getFont().getFontName().toLowerCase().startsWith("microsoft yahei"));
            }
        }

        if (png == null) return;
        pm.setSize(pm.getPreferredSize());
        layoutAll(pm);
        BufferedImage img = render(pm);
        ImageIO.write(img, "png", new File(png));
        int w = img.getWidth();
        int h = img.getHeight();

        // M6 外圈 1px 环
        String ring = hex(img, w / 2, 0);
        yes("M6 外圈 = " + ring + "（期望 " + hexOf(border) + "）", same(ring, hexOf(border)));

        // M7 边框 vs 相邻底亮度差
        int gl = lumOf(hex(img, w / 2, 0));
        int gb = lumOf(hex(img, w / 2, Math.min(2, h - 1)));
        boolean m7 = Math.abs(gl - gb) >= TOL_LUM;
        yes("M7 边框与相邻底亮度差 = " + Math.abs(gl - gb) + "（≥" + TOL_LUM + "；"
                + ring + " vs " + hex(img, w / 2, Math.min(2, h - 1)) + "）", m7);

        // M8/M9 菜单项行：底色众数 + 行内亮度极差
        for (Component c : pm.getComponents()) {
            if (!(c instanceof JMenuItem)) continue;
            Rectangle r = c.getBounds();
            int y = r.y + Math.min(r.height - 1, Math.max(1, r.height / 2));
            Map<String, Integer> hist = new HashMap<String, Integer>();
            int min = 999, max = -1;
            for (int x = Math.max(2, r.x + 1); x < Math.min(w - 2, r.x + r.width - 1); x++) {
                String k = hex(img, x, y);
                Integer n = hist.get(k);
                hist.put(k, n == null ? 1 : n + 1);
                int l = lumOf(k);
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
            String top = "?"; int topN = -1;
            for (Map.Entry<String, Integer> e : hist.entrySet()) {
                if (e.getValue() > topN) { topN = e.getValue(); top = e.getKey(); }
            }
            yes("M8 item '" + ((JMenuItem) c).getText() + "' 底色众数 = " + top
                    + "（期望 " + hexOf(bg) + "）", same(top, hexOf(bg)));
            yes("M9 同行文字亮度极差 = " + (max - min) + "（≥" + TOL_TEXT + "）", (max - min) >= TOL_TEXT);
        }
    }

    // ---------- 工具 ----------

    static JPopupMenu build() {
        JPopupMenu pm = new JPopupMenu();
        JMenuItem a = new JMenuItem("复制文本");
        JMenuItem b = new JMenuItem("在文件夹中显示");
        pm.add(a);
        pm.addSeparator();
        pm.add(b);
        return pm;
    }

    /** 递归 doLayout：容器没挂在窗口上时 validate() 不生效（同 BubbleProbe.layoutAll） */
    static void layoutAll(Component c) {
        if (c instanceof Container) {
            Container ct = (Container) c;
            ct.doLayout();
            for (Component ch : ct.getComponents()) layoutAll(ch);
        }
    }

    static BufferedImage render(JPopupMenu pm) {
        int w = Math.max(1, pm.getWidth()), h = Math.max(1, pm.getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xFF, 0x00, 0xFF));   // 洋红铺底：没画到的地方一眼可见
        g.fillRect(0, 0, w, h);
        pm.printAll(g);
        g.dispose();
        return img;
    }

    static void yes(String msg, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + msg);
        if (!ok) fail++;
    }

    static boolean same(String a, String b) { return a != null && a.equalsIgnoreCase(b); }
    static boolean same(Color a, Color b) {
        return a != null && b != null && a.getRGB() == b.getRGB();
    }
    static String hexOf(Color c) {
        return c == null ? "null" : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    static String hex(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return "越界";
        return hexOf(new Color(img.getRGB(x, y)));
    }
    static int lumOf(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
