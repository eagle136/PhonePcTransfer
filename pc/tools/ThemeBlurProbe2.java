package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * 主题切换「文本变淡/变糊」根因定位（2026-09-29 第二轮）。
 *
 * <p>第一轮（{@link ThemeBlurProbe}）结论：浅色启动时气泡文字最深像素 lum=34（锐利黑）；
 * 切深色再切回浅色后最深像素 lum=112（**整片变淡**，无全饱和核心像素），
 * 且 repaint 不恢复、拆窗重建才恢复 —— 典型「绘制期 Graphics2D 被换掉渲染提示
 * （文本 AA 打开 / 复合模式被改）」的特征，而不是字体或字号问题。
 *
 * <p>本轮做两件事：
 * <ol>
 *   <li><b>HintSpy</b>：往消息区首尾各塞一个探针面板，在真实 paint 期打印
 *       Graphics2D 的 identityHashCode / KEY_TEXT_ANTIALIASING / KEY_FRACTIONALMETRICS /
 *       KEY_ANTIALIASING / Composite / Transform —— 直接看是谁把哪个 Graphics 改了；</li>
 *   <li><b>触发面隔离</b>：把 toggleTheme 那条路拆成 5 个独立阶段，
 *       逐段抓屏量「最深像素 lum」，定位到底哪一步开始变淡。</li>
 * </ol>
 *
 * <pre>
 * javac -encoding UTF-8 -cp pc/build/classes -d pc/build/probe pc/tools/ThemeBlurProbe2.java
 * java  -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false \
 *       -Dsun.java2d.uiScale=1.0 -cp "pc/build/classes;pc/build/probe" \
 *       com.phonepctransfer.pc.ThemeBlurProbe2 [输出目录]
 * </pre>
 */
public final class ThemeBlurProbe2 {

    private static Store store;
    private static Link link;
    private static ChatWindow win;
    private static javax.swing.JTextArea areaSelf;   // 自己那条气泡的正文（几何/颜色都以它为准）
    private static String outDir = "pc/build/probe/blur2";
    private static String phase = "P0";
    private static final java.util.Set<String> LOGGED = new java.util.HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && !args[0].startsWith("--")) {
            outDir = args[0];
        }
        new File(outDir).mkdirs();

        build();

        shot("P0_baseline");
        run("P1_revalidate", () -> {
            win.msgPanel.revalidate();
            win.msgPanel.repaint();
        });
        run("P2_ui_field", () -> Ui.field(win.input));
        run("P3_refresh_devices", () -> {
            win.refreshDevices();
            win.msgPanel.revalidate();
            win.msgPanel.repaint();
        });
        run("P4_applyDark_true_only", () -> Ui.applyDark(true, win.frame.getContentPane()));
        run("P5_applyDark_false_only", () -> Ui.applyDark(false, win.frame.getContentPane()));
        run("P6_laf_only_toDark", () -> Ui.applyDark(true, null));
        run("P7_laf_only_toLight", () -> Ui.applyDark(false, null));

        System.out.println();
        System.out.println("### 拆窗重建");
        final ChatWindow old = win;
        SwingUtilities.invokeAndWait(old.frame::dispose);
        win = null;
        build();
        shot("P8_recreated");
        System.out.println("### done -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    private static void build() throws Exception {
        store = new Store(Store.defaultRoot());
        store.load();
        link = new Link();
        link.setStore(store);
        win = new ChatWindow(link, store);
        Msg m1 = new Msg();
        m1.mid = "p2-self";
        m1.dir = Msg.DIR_SELF;
        m1.kind = Msg.KIND_TEXT;
        m1.peerIp = "192.168.1.99";
        m1.text = "清晰度基准 1234567890 ABCabc 中英混排";
        javax.swing.JComponent bubble = win.bubbles.textBubble(m1, true, false);
        areaSelf = findArea(bubble);
        win.msgPanel.add(bubble);
        // 首尾各一个探针（Swing 的 paintChildren 从后往前画 → 后加的先生效）
        win.msgPanel.add(new HintSpy("A-tail"));
        win.msgPanel.add(new HintSpy("B-head"), 0);
        SwingUtilities.invokeAndWait(() -> {
            win.showWindow();
            win.frame.toFront();
        });
        Thread.sleep(1500);
    }

    /** 气泡里那个 JTextArea（正文） */
    private static javax.swing.JTextArea findArea(java.awt.Component c) {
        if (c instanceof javax.swing.JTextArea) {
            return (javax.swing.JTextArea) c;
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                javax.swing.JTextArea r = findArea(k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 正文所在屏幕矩形（留 1px 边，避免把气泡圆角算进来） */
    private static Rectangle tight() {
        Point p = areaSelf.getLocationOnScreen();
        return new Rectangle(p.x + 1, p.y + 1,
                Math.max(2, areaSelf.getWidth() - 2), Math.max(2, areaSelf.getHeight() - 2));
    }

    private static void run(String tag, Runnable r) throws Exception {
        phase = tag;
        LOGGED.clear();
        SwingUtilities.invokeAndWait(r);
        Thread.sleep(1200);
        shot(tag);
    }

    private static void shot(String tag) throws Exception {
        Point loc = win.frame.getLocationOnScreen();
        int w = win.frame.getWidth(), h = win.frame.getHeight();
        BufferedImage img = new Robot().createScreenCapture(new Rectangle(loc.x, loc.y, w, h));
        File f = new File(outDir, tag + ".png");
        ImageIO.write(img, "png", f);
        Rectangle frame = new Rectangle(loc.x, loc.y, w, h);
        Rectangle t = tight();
        Rectangle rel = new Rectangle(Math.max(0, t.x - frame.x), Math.max(0, t.y - frame.y),
                Math.min(t.width, w), Math.min(t.height, h));
        System.out.printf("%-26s area=%s fg=%-28s min=%3d p1=%3d p50=%3d soft=%5.2f%%%n",
                tag, rel.width + "x" + rel.height,
                colorTest(areaSelf.getForeground()) + "/Ui.BUBBLE_TEXT" + colorTest(Ui.BUBBLE_TEXT),
                minLum(img, rel), pctLum(img, rel, 1), pctLum(img, rel, 50), softPct(img, rel));
    }

    private static String colorTest(Color c) {
        if (c == null) {
            return "null";
        }
        return String.format("#%02X%02X%02X a=%d", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static int minLum(BufferedImage img, Rectangle r) {
        int m = 255;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                m = Math.min(m, lum(img.getRGB(x, y)));
            }
        }
        return m;
    }

    private static int pctLum(BufferedImage img, Rectangle r, int p) {
        int[] hist = new int[256];
        int n = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                hist[lum(img.getRGB(x, y))]++;
                n++;
            }
        }
        int want = n * p / 100, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= want) {
                return i;
            }
        }
        return 255;
    }

    private static double softPct(BufferedImage img, Rectangle r) {
        int[] hist = new int[256];
        int n = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                hist[lum(img.getRGB(x, y))]++;
                n++;
            }
        }
        int p5 = pct(hist, n, 5), p95 = pct(hist, n, 95);
        int lo = p5 + 30, hi = p95 - 30;
        if (hi <= lo) {
            return 0;
        }
        int soft = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int v = lum(img.getRGB(x, y));
                if (v > lo && v < hi) {
                    soft++;
                }
            }
        }
        return 100.0 * soft / n;
    }

    private static int pct(int[] hist, int n, int p) {
        int want = n * p / 100, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= want) {
                return i;
            }
        }
        return 255;
    }

    private static int lum(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** 真实 paint 期的渲染提示探针：谁改了 Graphics 里的 hint，这里一眼可见 */
    private static final class HintSpy extends JPanel {
        private final String tag;

        HintSpy(String tag) {
            this.tag = tag;
            setOpaque(true);
            setBackground(Color.WHITE);
            JLabel l = new JLabel("spy " + tag);
            l.setFont(new Font(Fonts.FAMILY, Font.PLAIN, 15));
            l.setForeground(new Color(0x1F, 0x23, 0x28));
            add(l);
            setPreferredSize(new java.awt.Dimension(240, 26));
            setMaximumSize(new java.awt.Dimension(240, 26));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            RenderingHints h = g2.getRenderingHints();
            String line = String.format(
                    "[spy %s @%s] g#%d  textAA=%s  fracMetrics=%s  shapeAA=%s  comp=%s  tx=%s",
                    tag, phase, System.identityHashCode(g),
                    h.get(RenderingHints.KEY_TEXT_ANTIALIASING),
                    h.get(RenderingHints.KEY_FRACTIONALMETRICS),
                    h.get(RenderingHints.KEY_ANTIALIASING),
                    g2.getComposite(),
                    g2.getTransform().getScaleX());
            if (LOGGED.add(line)) {
                System.out.println(line);
            }
        }
    }
}
