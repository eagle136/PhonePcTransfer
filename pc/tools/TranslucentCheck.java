package com.phonepctransfer.pc;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.io.File;

/** 2026-09-29 诊断：真实不透明窗（translucent）浅色建窗→切深色，看 margin 像素 */
public final class TranslucentCheck {
    public static void main(String[] args) throws Exception {
        Ui.install();
        Ui.applyDark(false);
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        SwingUtilities.invokeAndWait(() -> {
            win.frame.setLocation(360, 240);
            win.showWindow();
            win.frame.toFront();
        });
        Thread.sleep(1500);
        Robot robot = new Robot();
        BufferedImage img = robot.createScreenCapture(new Rectangle(360, 240, 952, 734));
        ImageIO.write(img, "png", new File("pc/build/probe/blur3/diag_light.png"));
        for (int x : new int[]{0, 8, 14, 16, 20}) {
            System.out.printf("  light  pixel(%d,367) = #%06X%n", x, img.getRGB(x, 367) & 0xFFFFFF);
        }
        final ChatWindow w = win;
        SwingUtilities.invokeAndWait(() -> {
            Ui.applyDark(true, w.frame.getContentPane());
            w.frame.getContentPane().revalidate();
            w.frame.getContentPane().repaint();
        });
        Thread.sleep(1500);
        BufferedImage img2 = robot.createScreenCapture(new Rectangle(360, 240, 952, 734));
        ImageIO.write(img2, "png", new File("pc/build/probe/blur3/diag_dark_after_toggle.png"));
        for (int x : new int[]{0, 8, 14, 16, 20}) {
            System.out.printf("  dark   pixel(%d,367) = #%06X%n", x, img2.getRGB(x, 367) & 0xFFFFFF);
        }
        System.exit(0);
    }
}
