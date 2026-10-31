package com.phonepctransfer.pc;

import java.awt.Color;
import javax.swing.SwingUtilities;

/** 2026-09-29 诊断：完整组件树 + 父指针 + contentPane 身份 */
public final class TreeDump2 {
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
        System.out.println("contentPane = " + id(w.frame.getContentPane()));
        dump(w.frame, 0);
        System.exit(0);
    }

    static String id(java.awt.Component c) {
        return c.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(c));
    }

    private static void dump(java.awt.Component c, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cn = c.getClass().getSimpleName();
        if (cn.isEmpty()) cn = "anon(" + c.getClass().getEnclosingClass().getSimpleName() + "$anon)";
        Color bg = ((java.awt.Component) c).getBackground();
        System.out.println(sb + cn
                + "  bg=" + (bg == null ? "null" : String.format("#%06X", bg.getRGB() & 0xFFFFFF))
                + " opaque=" + c.isOpaque()
                + " parent=" + (c.getParent() == null ? "-" : c.getParent().getClass().getSimpleName()));
        if (c instanceof java.awt.Container && depth < 6) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                dump(k, depth + 1);
            }
        }
    }
}
