package com.phonepctransfer.pc;

import java.awt.Color;
import javax.swing.SwingUtilities;

/** 2026-09-29 诊断：打印窗口组件树 + shadowPane 的父链 */
public final class TreeDump {
    public static void main(String[] args) throws Exception {
        Ui.install();
        Ui.applyDark(false);
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow w = new ChatWindow(link, store);
        SwingUtilities.invokeAndWait(() -> {
            w.frame.setLocation(360, 240);
            w.showWindow();
        });
        Thread.sleep(1000);
        dump(w.frame.getRootPane(), 0);
        System.exit(0);
    }

    private static void dump(java.awt.Component c, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        String name = c.getClass().getSimpleName();
        if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getText() != null) {
            name += "[...]";
        }
        Color bg = c instanceof java.awt.Component ? ((java.awt.Component) c).getBackground() : null;
        System.out.println(sb + name + (c.isShowing() ? "" : " (hidden)")
                + "  bg=" + (bg == null ? "null" : String.format("#%06X", bg.getRGB() & 0xFFFFFF))
                + " opaque=" + c.isOpaque());
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                if (depth < 4) dump(k, depth + 1);
            }
        }
    }
}
