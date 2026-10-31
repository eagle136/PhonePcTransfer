package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * 第三轮：主题切换「文字变淡」全量对比度审计（2026-09-29）。
 *
 * <p>前两轮已证明：{@code Ui.applyDark} 用**颜色值**做 HashMap 键，深色板里
 * CHROME_FG / TEXT_MAIN / BUBBLE_TEXT 同值 #E8EAED → 后写覆盖 → 切回浅色时
 * 气泡正文被映射成 CHROME_FG 的浅灰 #6B727B（实测最深像素 34→112）。
 *
 * <p>用户反馈「两个方向都糊」，所以本轮把审计做全：
 * <ol>
 *   <li><b>对比度审计</b>：遍历组件树，对每个带文字的组件算「前景 vs 有效底色」的
 *       WCAG 对比度，列出 &lt; 3.0 的全部条目（这就是「糊」的客观定义）；</li>
 *   <li><b>两条路径对比</b>：<i>启动即深色</i>（PcMain 路径 applyDark(true) 后建窗）
 *       vs <i>浅色启动后运行期切深色</i>（toggleTheme 路径），同一窗口坐标抓屏像素对比 ——
 *       两个方向各自的偏差都能定位到具体组件。</li>
 * </ol>
 *
 * <pre>
 * javac -encoding UTF-8 -cp pc/build/classes -d pc/build/probe pc/tools/ThemeBlurProbe3.java
 * java  -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false \
 *       -Dsun.java2d.uiScale=1.0 -cp "pc/build/classes;pc/build/probe" \
 *       com.phonepctransfer.pc.ThemeBlurProbe3 [输出目录]
 * </pre>
 */
public final class ThemeBlurProbe3 {

    private static String outDir = "pc/build/probe/blur3";

    /** 2026-09-29：--opaque = 主窗不透明（关掉逐像素透明路径），让像素差分不受桌面背景干扰 */
    private static boolean opaqueWindow = false;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);   // 2026-09-29：失败也要退进程，否则 AWT 线程会让 JVM 挂住
        }
    }

    private static void run(String[] args) throws Exception {
        String stage = "all";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            // 2026-07-09（3of3 C2.4）：支持 --phase A|B|C|diff（含 --phase=X 写法），
            // 与既有的裸小写 a/b/c/diff 参数等价；各段仍各自独立进程跑。
            if (a.equals("--phase") && i + 1 < args.length) {
                stage = args[++i].toLowerCase();
            } else if (a.startsWith("--phase=")) {
                stage = a.substring("--phase=".length()).toLowerCase();
            } else if (a.equals("a") || a.equals("b") || a.equals("c") || a.equals("diff")) {
                stage = a;
            } else if (a.equals("--opaque")) {
                opaqueWindow = true;
            } else if (!a.startsWith("--")) {
                outDir = a;
            }
        }
        new File(outDir).mkdirs();

        // 2026-09-29：单进程连开三个窗口会在第 2 个窗口卡住（Link 构造 + TCP 28765 端口竞争），
        // 所以支持「一个阶段一个进程」：a / b / c 各自独立进程跑，diff 离线做像素差分。
        // 不带阶段参数 = 旧行为（单进程跑全部阶段）。
        if (stage.equals("a") || stage.equals("all")) {
            // ---------- 路径 A：启动即深色（PcMain 路径） ----------
            Ui.applyDark(true);
            ChatWindow a = open();
            shot("A_dark_at_startup");
            audit("A 启动即深色", a);
            SwingUtilities.invokeAndWait(a.frame::dispose);
        }
        if (stage.equals("b") || stage.equals("all")) {
            // ---------- 路径 B：浅色启动 → 运行期切深色（toggleTheme 路径） ----------
            Ui.applyDark(false);
            ChatWindow b = open();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    toggleLikeUser(b);   // 真实用户路径的渲染侧效果（不落盘）
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(1200);
            shot("B_dark_after_toggle");
            audit("B 浅色启动后切深色", b);
            SwingUtilities.invokeAndWait(b.frame::dispose);
        }
        if (stage.equals("c") || stage.equals("all")) {
            // ---------- 路径 C：深色启动 → 运行期切浅色 ----------
            Ui.applyDark(true);
            ChatWindow c = open();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    toggleLikeUser(c);   // 真实用户路径的渲染侧效果（不落盘）
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(1200);
            shot("C_light_after_toggle");
            audit("C 深色启动后切浅色", c);
            SwingUtilities.invokeAndWait(c.frame::dispose);
        }
        if (stage.equals("diff") || stage.equals("all")) {
            // ---------- 像素差分：A（启动即深色）vs B（切出来的深色） ----------
            diff(new File(outDir, "A_dark_at_startup.png"), new File(outDir, "B_dark_after_toggle.png"));
        }

        System.out.println("### done -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    private static ChatWindow open() throws Exception {
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        if (opaqueWindow) {
            win.frame.setBackground(java.awt.Color.BLACK);   // 不透明窗：圆角外不再透出桌面
        }
        Msg m1 = new Msg();
        m1.mid = "p3-self";
        m1.dir = Msg.DIR_SELF;
        m1.kind = Msg.KIND_TEXT;
        m1.peerIp = "192.168.1.99";
        m1.text = "清晰度基准 1234567890 ABCabc 中英混排";
        m1.ts = 1759136400000L;   // 2026-09-29：钉死时间戳，A/B 两次运行截图的时间文字逐像素一致
        win.bubbles.addBubble(m1);
        Msg m2 = new Msg();
        m2.mid = "p3-peer";
        m2.dir = Msg.DIR_PEER;
        m2.kind = Msg.KIND_CLIP;
        m2.peerIp = "192.168.1.99";
        m2.text = "剪贴板内容 9876543210 xyzXYZ 中英混排";
        m2.ts = 1759136400000L;   // 同上
        win.bubbles.addBubble(m2);
        SwingUtilities.invokeAndWait(() -> {
            win.frame.setLocation(360, 240);   // 三条路径同一个窗口坐标，便于像素差分
            win.showWindow();
            win.frame.toFront();
        });
        Thread.sleep(1500);
        return win;
    }

    /** 等价于 ChatDevices.toggleTheme() 的**渲染侧效果**（2026-09-29）：
     * applyDark + 输入框重套 + 按钮文字/提示 + 设备列表刷新。
     * 不调 W.store.setDarkTheme —— 探针不碰用户真实 config.json。
     * themeBtn 是 ChatDevices 的 private 字段，同包也破不了 private，用反射取。 */
    private static void toggleLikeUser(ChatWindow win) throws Exception {
        boolean toDark = !Ui.isDark();
        Ui.applyDark(toDark, win.frame.getContentPane());
        Ui.field(win.input);
        java.lang.reflect.Field f = ChatDevices.class.getDeclaredField("themeBtn");
        f.setAccessible(true);
        JButton btn = (JButton) f.get(win.devices);
        btn.setText(toDark ? "切到浅色" : "切到深色");
        btn.setToolTipText("点击切换到" + (toDark ? "浅色" : "深色") + "模式（当前：" + (toDark ? "深色" : "浅色") + "）");
        win.refreshDevices();
        win.msgPanel.revalidate();
        win.msgPanel.repaint();
    }

    private static void shot(String tag) throws Exception {
        Point loc = new Point(360, 240);
        Robot robot = new Robot();
        robot.mouseMove(0, 0);   // 2026-09-29：把鼠标移离窗口，避免 hover 态参与像素差分
        BufferedImage img = robot.createScreenCapture(
                new Rectangle(loc.x, loc.y, 952, 734));
        ImageIO.write(img, "png", new File(outDir, tag + ".png"));
        System.out.println("shot " + tag);
    }

    /** 对比度审计：列出所有「前景 vs 有效底色」对比度 < 3.0 的带文字组件 */
    private static void audit(String tag, ChatWindow win) throws Exception {
        final List<Container> roots = new ArrayList<Container>();
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isVisible() && w instanceof java.awt.Frame) {
                roots.add(w);
            }
        }
        final Map<String, String> bad = new LinkedHashMap<String, String>();
        final Map<String, String> all = new LinkedHashMap<String, String>();
        SwingUtilities.invokeAndWait(() -> {
            for (Container r : roots) {
                walk(r, "", bad, all);
            }
        });
        System.out.println();
        System.out.println("===== 对比度审计 " + tag + "（< 3.0 的条目）");
        if (bad.isEmpty()) {
            System.out.println("  （无）");
        }
        for (Map.Entry<String, String> e : bad.entrySet()) {
            System.out.println("  " + e.getKey() + "  " + e.getValue());
        }
        System.out.println("  -- 全部带文字组件 " + all.size() + " 个，低于 3.0 的 " + bad.size() + " 个");
    }

    private static void walk(Component c, String path, Map<String, String> bad, Map<String, String> all) {
        String name = c.getClass().getSimpleName();
        String label = textOf(c);
        String p = path + "/" + name + (label.isEmpty() ? "" : "[" + trim(label) + "]");
        if (!label.isEmpty()) {
            Color fg = c.getForeground();
            Color bg = effectiveBg(c);
            double ratio = contrast(fg, bg);
            String fsize = "";
            if (c.getFont() != null) {
                fsize = c.getFont().getSize() + "px" + (c.getFont().isBold() ? "B" : "");
            }
            String desc = String.format("%s fg=%s bg=%s ratio=%.2f %s",
                    fsize, hex(fg), hex(bg), ratio, label);
            all.put(p, desc);
            if (ratio < 3.0) {
                bad.put(p, desc);
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                walk(k, p, bad, all);
            }
        }
    }

    private static String textOf(Component c) {
        String t = null;
        try {
            if (c instanceof AbstractButton) {
                t = ((AbstractButton) c).getText();
            } else if (c instanceof JLabel) {
                t = ((JLabel) c).getText();
            } else if (c instanceof JTextArea) {
                t = ((JTextArea) c).getText();
            } else if (c instanceof JTextField) {
                t = ((JTextField) c).getText();
            }
        } catch (Throwable ignore) {
            //
        }
        // 2026-09-29：JLabel 未设文本时 getText() 返回 null，walk() 里 label.isEmpty() 会 NPE
        return t == null ? "" : t;
    }

    /** 有效底色：向上找第一个不透明祖先的 background */
    private static Color effectiveBg(Component c) {
        Component p = c;
        while (p != null) {
            if (p.isOpaque() && p.getBackground() != null) {
                return p.getBackground();
            }
            p = p.getParent();
        }
        return Color.WHITE;
    }

    private static double contrast(Color a, Color b) {
        if (a == null || b == null) {
            return 21;
        }
        double l1 = rel(a), l2 = rel(b);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double rel(Color c) {
        double r = c.getRed() / 255.0, g = c.getGreen() / 255.0, b = c.getBlue() / 255.0;
        r = f(r); g = f(g); b = f(b);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double f(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static String hex(Color c) {
        return c == null ? "null" : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String trim(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private static void diff(File a, File b) throws Exception {
        BufferedImage ia = ImageIO.read(a), ib = ImageIO.read(b);
        int w = Math.min(ia.getWidth(), ib.getWidth()), h = Math.min(ia.getHeight(), ib.getHeight());
        int n = 0, x1 = w, y1 = h, x2 = -1, y2 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = ia.getRGB(x, y), pb = ib.getRGB(x, y);
                if (pa != pb) {
                    n++;
                    x1 = Math.min(x1, x); y1 = Math.min(y1, y);
                    x2 = Math.max(x2, x); y2 = Math.max(y2, y);
                }
            }
        }
        System.out.println();
        System.out.println("===== 像素差分 A(启动即深色) vs B(切出的深色)");
        System.out.println("  不同像素 " + n + " / " + (w * h)
                + (n > 0 ? "  包围盒 x" + x1 + ".." + x2 + " y" + y1 + ".." + y2 : ""));
        // 按 8x8 网格汇总差异热点，方便定位是哪一块
        if (n > 0) {
            int gs = 32;
            Map<String, Integer> hot = new LinkedHashMap<String, Integer>();
            for (int gy = 0; gy + gs <= h; gy += gs) {
                for (int gx = 0; gx + gs <= w; gx += gs) {
                    int cnt = 0;
                    for (int y = gy; y < gy + gs; y++) {
                        for (int x = gx; x < gx + gs; x++) {
                            if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                                cnt++;
                            }
                        }
                    }
                    if (cnt > 0) {
                        hot.put("x" + gx + "-" + (gx + gs) + " y" + gy + "-" + (gy + gs), cnt);
                    }
                }
            }
            List<Map.Entry<String, Integer>> es = new ArrayList<Map.Entry<String, Integer>>(hot.entrySet());
            es.sort((p, q) -> q.getValue() - p.getValue());
            int shown = 0;
            for (Map.Entry<String, Integer> e : es) {
                System.out.println("   热点块 " + e.getKey() + "  差异像素 " + e.getValue());
                if (++shown >= 12) {
                    System.out.println("   …（共 " + es.size() + " 个差异块）");
                    break;
                }
            }
        }
    }
}
