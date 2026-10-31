package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.io.File;

/** 2026-09-29 诊断：完整复刻 stage B（浅色建窗→切深色），dump 各层 bg + 窗口 bounds + 实际像素 */
public final class StageBDiag {
    public static void main(String[] args) throws Exception {
        Ui.install();
        Ui.applyDark(false);
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        win.frame.setBackground(Color.BLACK);   // --opaque 等效
        Msg m1 = new Msg(); m1.mid="d1"; m1.dir = Msg.DIR_SELF; m1.kind = Msg.KIND_TEXT; m1.peerIp="192.168.1.99";
        m1.text = "清晰度基准 1234567890 ABCabc 中英混排"; m1.ts = 1759136400000L;
        Msg m2 = new Msg(); m2.mid="d2"; m2.dir = Msg.DIR_PEER; m2.kind = Msg.KIND_CLIP; m2.peerIp="192.168.1.99";
        m2.text = "[剪贴板] 剪贴板内容 9876543210 xyzXYZ 中英混排"; m2.ts = 1759136400000L;
        win.bubbles.addBubble(m1); win.bubbles.addBubble(m2);
        SwingUtilities.invokeAndWait(() -> {
            win.frame.setLocation(360, 240);
            win.showWindow();
        });
        Thread.sleep(1500);
        dumpLayers("before_toggle", win);
        // 切深色（复刻 toggleLikeUser 的渲染侧）
        final ChatWindow w = win;
        SwingUtilities.invokeAndWait(() -> {
            Ui.applyDark(true, w.frame.getContentPane());
            w.frame.getContentPane().revalidate();
            w.frame.getContentPane().repaint();
        });
        Thread.sleep(1500);
        dumpLayers("after_toggle", win);
        // 截图 + 像素
        Robot robot = new Robot();
        BufferedImage img = robot.createScreenCapture(new Rectangle(360, 240, 952, 734));
        ImageIO.write(img, "png", new File("pc/build/probe/blur3/diag_B.png"));
        for (int x : new int[]{0, 2, 8, 14, 16, 17, 18, 20}) {
            System.out.printf("  pixel(%d,367) = #%06X%n", x, img.getRGB(x, 367) & 0xFFFFFF);
        }
        System.exit(0);
    }

    private static void dumpLayers(String tag, ChatWindow w) {
        System.out.println("== " + tag + "  frameBounds=" + w.frame.getBounds());
        java.awt.Component[] chain = {w.frame, w.frame.getRootPane()};
        for (java.awt.Component c : chain) dump(c, 1);
        dump(w.frame.getLayeredPane(), 1);
        dump(w.frame.getContentPane(), 1);
        java.awt.Container cp = w.frame.getContentPane();
        for (java.awt.Component k : cp.getComponents()) dump(k, 2);
    }

    private static void dump(java.awt.Component c, int indent) {
        Color bg = c.getBackground();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) sb.append("  ");
        String cn = c.getClass().getSimpleName();
        if (cn.isEmpty()) cn = "anon";
        System.out.println(sb + cn + "  bg=" + (bg == null ? "null" : String.format("#%06X a=%d", bg.getRGB() & 0xFFFFFF, bg.getAlpha()))
                + " opaque=" + c.isOpaque());
    }
}
