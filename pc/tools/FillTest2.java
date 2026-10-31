package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 2026-09-29 诊断：opaque frame 下 contentPane(bg=白, non-opaque) 填不填白 */
public final class FillTest2 {
    public static void main(String[] args) throws Exception {
        JFrame f = new JFrame();
        f.setUndecorated(true);
        f.setBackground(Color.BLACK);
        JPanel cp = (JPanel) f.getContentPane();
        cp.setBackground(Color.WHITE);
        cp.setOpaque(false);
        f.setSize(200, 100);
        f.setLocation(400, 300);
        SwingUtilities.invokeAndWait(() -> f.setVisible(true));
        Thread.sleep(1200);
        Robot r = new Robot();
        BufferedImage img = r.createScreenCapture(new Rectangle(400, 300, 200, 100));
        System.out.printf("opaque-frame center = #%06X%n", img.getRGB(100, 50) & 0xFFFFFF);
        System.exit(0);
    }
}
