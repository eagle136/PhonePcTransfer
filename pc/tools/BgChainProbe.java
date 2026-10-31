package com.phonepctransfer.pc;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;

/**
 * 2026-09-29 调试：复现「深色启动→切浅色后左栏变灰 #6B727B」。
 * 切前/切后 dump 左栏每个组件的 bg（hex + 实例身份 hash），定位谁把底色换成了 #6B727B。
 */
public final class BgChainProbe {

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale", "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "off");
        System.setProperty("swing.aatext", "false");
        Ui.install();

        Ui.applyDark(true);
        ChatWindow win = open();
        dump(win, "BEFORE toggle (dark)");

        boolean toDark = !Ui.isDark();
        Ui.applyDark(toDark, win.frame.getContentPane());
        Ui.field(win.input);
        Field f = ChatDevices.class.getDeclaredField("themeBtn");
        f.setAccessible(true);
        JButton btn = (JButton) f.get(win.devices);
        btn.setText("切到浅色");
        win.refreshDevices();
        win.msgPanel.revalidate();
        win.msgPanel.repaint();
        SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(800);
        dump(win, "AFTER toggle (light)");
        System.exit(0);
    }

    private static ChatWindow open() throws Exception {
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        win.frame.setBackground(java.awt.Color.BLACK);
        SwingUtilities.invokeAndWait(() -> {
            win.frame.setLocation(360, 240);
            win.showWindow();
            win.frame.toFront();
        });
        Thread.sleep(1200);
        return win;
    }

    private static void dump(ChatWindow win, String tag) throws Exception {
        final StringBuilder sb = new StringBuilder();
        SwingUtilities.invokeAndWait(() -> {
            sb.append("\n===== ").append(tag).append(" =====\n");
            walk(win.frame, 0, sb);
        });
        System.out.print(sb);
    }

    private static void walk(Component c, int depth, StringBuilder sb) {
        String name = c.getClass().getSimpleName();
        String extra = "";
        if (c instanceof JLabel) {
            String t = ((JLabel) c).getText();
            if (t != null && t.length() > 14) t = t.substring(0, 14);
            extra = "[" + (t == null ? "" : t.replace('\n', ' ')) + "]";
        }
        Color bg = c.getBackground();
        String bgd = bg == null ? "null" : String.format("#%06X(id=%d)", bg.getRGB(), System.identityHashCode(bg));
        sb.append(String.format("%s%s%s bg=%s opaque=%s%n",
                "  ".repeat(depth), name, extra, bgd, c.isOpaque()));
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                walk(k, depth + 1, sb);
            }
        }
    }
}
