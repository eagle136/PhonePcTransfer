package com.phonepctransfer.pc;

import java.awt.Color;
import javax.swing.SwingUtilities;

/** 2026-09-29 诊断：打印 frame/rootPane/layeredPane/contentPane 的 bg（深浅两种建窗路径） */
public final class RootBgProbe {
    public static void main(String[] args) throws Exception {
        // 路径 A：深色建窗
        Ui.install();
        Ui.applyDark(true);
        dump("A_dark_startup", build());
        // 路径 B：浅色建窗 → 切深色
        Ui.applyDark(false);
        ChatWindow w = build();
        dump("B_light_start", w);
        SwingUtilities.invokeAndWait(() -> {
            Ui.applyDark(true, w.frame.getContentPane());
            w.frame.getContentPane().revalidate();
            w.frame.getContentPane().repaint();
        });
        Thread.sleep(800);
        dump("B_after_toggle", w);
        System.exit(0);
    }

    private static ChatWindow build() throws Exception {
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow w = new ChatWindow(link, store);
        w.frame.setBackground(Color.BLACK);
        SwingUtilities.invokeAndWait(() -> {
            w.frame.setLocation(360, 240);
            w.showWindow();
        });
        Thread.sleep(1200);
        return w;
    }

    private static void dump(String tag, ChatWindow w) {
        java.awt.Container root = w.frame.getRootPane();
        System.out.println("== " + tag);
        System.out.println("  frame        " + hex(w.frame.getBackground()) + " opaque=" + w.frame.isOpaque());
        System.out.println("  rootPane     " + hex(root.getBackground()) + " opaque=" + root.isOpaque());
        for (java.awt.Component c : root.getComponents()) {
            System.out.println("  " + c.getClass().getSimpleName() + "  " + hex(bg(c)) + " opaque=" + c.isOpaque());
            if (c instanceof java.awt.Container) {
                for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                    System.out.println("    " + k.getClass().getSimpleName() + "  " + hex(bg(k)) + " opaque=" + k.isOpaque());
                }
            }
        }
    }

    private static Color bg(java.awt.Component c) {
        return c instanceof java.awt.Component ? ((java.awt.Component) c).getBackground() : null;
    }

    private static String hex(Color c) {
        return c == null ? "null" : String.format("#%06X a=%d", c.getRGB() & 0xFFFFFF, c.getAlpha());
    }
}
