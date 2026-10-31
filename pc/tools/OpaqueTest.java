package com.phonepctransfer.pc;

import java.awt.Color;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 2026-09-29 诊断：透明 vs 不透明 frame 下 rootPane/layeredPane/contentPane 的 isOpaque */
public final class OpaqueTest {
    public static void main(String[] args) throws Exception {
        JFrame t = new JFrame("translucent");
        t.setUndecorated(true);
        t.setBackground(new Color(0, 0, 0, 0));
        ((JPanel) t.getContentPane()).setOpaque(false);
        t.setSize(200, 100);
        t.setVisible(true);
        dump("translucent frame", t);

        JFrame o = new JFrame("opaque");
        o.setUndecorated(true);
        o.setBackground(Color.BLACK);
        o.setSize(200, 100);
        o.setVisible(true);
        dump("opaque frame", o);

        // 再试：透明 frame 改设不透明 bg 后
        t.setBackground(Color.BLACK);
        dump("translucent->opaque bg", t);
        System.exit(0);
    }

    private static void dump(String tag, JFrame f) {
        System.out.println("== " + tag);
        System.out.println("  frame      opaque=" + f.isOpaque());
        System.out.println("  rootPane   opaque=" + f.getRootPane().isOpaque());
        System.out.println("  layered    opaque=" + f.getLayeredPane().isOpaque());
        System.out.println("  content    opaque=" + f.getContentPane().isOpaque());
        System.out.println("  glass      opaque=" + f.getGlassPane().isOpaque());
    }
}
