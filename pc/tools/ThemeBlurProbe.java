package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * 主题切换「文本变糊」诊断探针（2026-09-29 新增，真窗 + Robot 抓屏）。
 *
 * <p>目的：定位「启动时（浅色）聊天文本清晰，切换主题后永久模糊」的真因。
 * 判据是**像素级**的：全局关 AA 后，字号落在宋体内嵌点阵档位上的文本，其笔画像素
 * 应当只有「背景色」与「前景色」两种值；一旦渲染管线发生 alpha 合成 / 缩放 / 抗锯齿接管，
 * 就会出现大量中间灰度（软边）。所以：
 * <ul>
 *   <li>{@code inkN}  = 亮度 &lt; 140 的像素数（墨迹总量，模糊后基本不变）</li>
 *   <li>{@code midN}  = 亮度落在 60..200 的**中间**像素数（软边像素，**清晰时≈0**）</li>
 *   <li>{@code mid/ink} = 软化率，越大越糊</li>
 * </ul>
 *
 * <p>阶段（每个阶段都出 PNG + 打印指标）：
 * <ol>
 *   <li>P1 启动浅色（与 PcMain 同一路径：窗口建立前只装浅色调色板）</li>
 *   <li>P2 运行期切深色（= ChatDevices.toggleTheme 那条路：applyDark(d, contentPane)）</li>
 *   <li>P3 再切回浅色</li>
 *   <li>P4 现窗强制 revalidate+repaint（看「重绘能否自愈」）</li>
 *   <li>P5 拆窗重建（看「新窗口是否回到清晰」）</li>
 * </ol>
 *
 * <p>变体：{@code --opaque} = 主窗不设逐像素透明背景（关掉 PiP/分层窗口路径），
 * 用于验证「透明窗 + 主题重绘」是否是根因。
 *
 * <pre>
 * javac -encoding UTF-8 -cp pc/build/classes -d pc/build/probe pc/tools/ThemeBlurProbe.java
 * java  -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false \
 *       -cp "pc/build/classes;pc/build/probe" \
 *       com.phonepctransfer.pc.ThemeBlurProbe [--opaque] [输出目录]
 * </pre>
 *
 * <p>只用于自检，编译进 build/probe，不进发布 jar。
 */
public final class ThemeBlurProbe {

    private static Store store;
    private static Link link;
    private static ChatWindow win;
    private static String outDir = "pc/build/probe/blur";
    private static boolean opaqueWindow = false;

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if ("--opaque".equals(a)) {
                opaqueWindow = true;
            } else if (!a.startsWith("--")) {
                outDir = a;
            }
        }
        new File(outDir).mkdirs();

        System.out.println("### ThemeBlurProbe  opaqueWindow=" + opaqueWindow);
        System.out.println("uiScale         = " + System.getProperty("sun.java2d.uiScale"));
        System.out.println("systemAA        = " + System.getProperty("awt.useSystemAAFontSettings"));
        System.out.println("swing.aatext    = " + System.getProperty("swing.aatext"));
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        System.out.println("device          = " + gd.getDisplayMode().getWidth() + "x"
                + gd.getDisplayMode().getHeight()
                + " transform=" + gd.getDefaultConfiguration().getDefaultTransform());

        buildWindow("P1");

        shot("P1_light_start");
        step("P2", () -> {
            Ui.applyDark(true, win.frame.getContentPane());
            Ui.field(win.input);
            win.refreshDevices();
            win.msgPanel.revalidate();
            win.msgPanel.repaint();
        });
        step("P3", () -> {
            Ui.applyDark(false, win.frame.getContentPane());
            Ui.field(win.input);
            win.refreshDevices();
            win.msgPanel.revalidate();
            win.msgPanel.repaint();
        });
        step("P4", () -> {
            win.frame.getContentPane().revalidate();
            win.frame.getContentPane().repaint();
            win.msgPanel.revalidate();
            win.msgPanel.repaint();
        });
        // P5：拆窗重建（不带任何主题切换，仍按当前调色板建）
        final ChatWindow old = win;
        SwingUtilities.invokeAndWait(old.frame::dispose);
        win = null;
        buildWindow("P5");
        shot("P5_recreated");

        System.out.println("### done -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    private static void buildWindow(String tag) throws Exception {
        store = new Store(Store.defaultRoot());
        store.load();
        link = new Link();
        link.setStore(store);
        win = new ChatWindow(link, store);
        if (opaqueWindow) {
            win.frame.setBackground(Color.BLACK);
        }
        // 一条自己发的文本气泡 + 一条对方的：覆盖 BUBBLE_TEXT 两种底色
        Msg m1 = new Msg();
        m1.mid = tag + "-self";
        m1.dir = Msg.DIR_SELF;
        m1.kind = Msg.KIND_TEXT;
        m1.peerIp = "192.168.1.99";
        m1.text = "清晰度基准 1234567890 ABCabc 中英混排";
        win.bubbles.addBubble(m1);
        Msg m2 = new Msg();
        m2.mid = tag + "-peer";
        m2.dir = Msg.DIR_PEER;
        m2.kind = Msg.KIND_TEXT;
        m2.peerIp = "192.168.1.99";
        m2.text = "对端气泡 9876543210 xyzXYZ 中英混排";
        win.bubbles.addBubble(m2);
        SwingUtilities.invokeAndWait(() -> {
            win.showWindow();
            win.frame.toFront();
        });
        Thread.sleep(1500);
    }

    private static void step(String tag, Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        Thread.sleep(1200);
        shot(tag);
    }

    /** 真窗抓屏 + 指标（整窗 + 聊天区两块） */
    private static void shot(String tag) throws Exception {
        Point loc = win.frame.getLocationOnScreen();
        int w = win.frame.getWidth();
        int h = win.frame.getHeight();
        Robot robot = new Robot();
        robot.setAutoDelay(30);
        BufferedImage img = robot.createScreenCapture(new Rectangle(loc.x, loc.y, w, h));
        File f = new File(outDir, tag + (opaqueWindow ? "_opaque" : "_trans") + ".png");
        ImageIO.write(img, "png", f);

        // 聊天区在窗口内的相对矩形（msgPanel 左上角 → 窗口坐标）
        Rectangle msg = null;
        try {
            Point p = SwingUtilities.convertPoint(win.msgPanel, 0, 0,
                    win.frame.getContentPane());
            msg = new Rectangle(p.x + win.frame.getContentPane().getX(),
                    p.y + win.frame.getContentPane().getY(),
                    win.msgPanel.getWidth(), win.msgPanel.getHeight());
        } catch (Throwable ignore) {
            // 窗口已拆：只统计整窗
        }
        System.out.println();
        System.out.println("--- " + tag + "  window " + w + "x" + h + " @ " + loc.x + "," + loc.y
                + "  msgPanel=" + msg);
        metrics("全窗  ", img, new Rectangle(0, 0, w, h));
        if (msg != null && msg.width > 4 && msg.height > 4) {
            metrics("聊天区", img, clamp(msg, w, h));
        }
        System.out.println("    png   " + f.getAbsolutePath());
    }

    private static Rectangle clamp(Rectangle r, int w, int h) {
        int x1 = Math.max(0, r.x), y1 = Math.max(0, r.y);
        int x2 = Math.min(w, r.x + r.width), y2 = Math.min(h, r.y + r.height);
        return new Rectangle(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
    }

    /** 亮度直方图口径：墨迹 / 中间灰（软边）/ 软化率 */
    private static void metrics(String label, BufferedImage img, Rectangle r) {
        long ink = 0, mid = 0, px = 0;
        long[] hist = new long[8];
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int rgb = img.getRGB(x, y);
                int lum = luma(rgb);
                px++;
                if (lum < 140) {
                    ink++;
                }
                if (lum > 60 && lum < 200) {
                    mid++;
                    hist[Math.min(7, (lum - 60) / 18)]++;
                }
            }
        }
        System.out.printf("    %s px=%7d ink=%6d mid=%7d  mid/ink=%.4f%n",
                label, px, ink, mid, ink == 0 ? 0.0 : (double) mid / ink);
    }

    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
