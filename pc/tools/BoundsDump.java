package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** 2026-09-29 诊断：A(启动即深色) vs B(浅→深) 两条路径下左栏组件 bounds/底色对比 */
public final class BoundsDump {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("a")) {
            Ui.applyDark(true);
            open(false);
        } else {
            Ui.applyDark(false);
            open(true);
        }
        System.exit(0);
    }

    private static void open(boolean toggle) throws Exception {
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        SwingUtilities.invokeAndWait(() -> {
            win.frame.setLocation(360, 240);
            win.showWindow();
        });
        Thread.sleep(1200);
        if (toggle) {
            SwingUtilities.invokeAndWait(() -> {
                Ui.applyDark(true, win.frame.getContentPane());
                Ui.field(win.input);
                win.refreshDevices();
                win.msgPanel.revalidate();
                win.msgPanel.repaint();
            });
            Thread.sleep(1200);
        }
        final ChatWindow w = win;
        SwingUtilities.invokeAndWait(() -> dump(w.frame.getContentPane()));
        SwingUtilities.invokeAndWait(w.frame::dispose);
    }

    private static void dump(Container root) {
        walk(root, 0);
    }

    private static void walk(Component c, int depth) {
        if (c instanceof Container) {
            // 只关心左栏区域（窗口坐标 x < 230）或聊天区滚动容器
            java.awt.Rectangle b = c.getBounds();
            boolean interesting = (c.getClass().getSimpleName().contains("Scroll")
                    || c.getClass().getSimpleName().contains("Viewport")
                    || (b.x < 230 && b.x + b.width > 10 && b.y > 50 && b.y < 700));
            if (interesting) {
                String label = textOf(c);
                System.out.printf("%s%s  bounds=(%d,%d %dx%d)  bg=%s opaque=%b%s%n",
                        "  ".repeat(depth), c.getClass().getSimpleName(),
                        b.x, b.y, b.width, b.height, hex(c.getBackground()), c.isOpaque(),
                        label.isEmpty() ? "" : "  [" + label + "]");
            }
            for (Component k : ((Container) c).getComponents()) {
                walk(k, depth + 1);
            }
        }
    }

    private static String textOf(Component c) {
        try {
            if (c instanceof AbstractButton) return ((AbstractButton) c).getText() == null ? "" : ((AbstractButton) c).getText();
            if (c instanceof JLabel) return ((JLabel) c).getText() == null ? "" : ((JLabel) c).getText();
            if (c instanceof JTextComponent) return ((JTextComponent) c).getText() == null ? "" : ((JTextComponent) c).getText();
        } catch (Throwable ignore) { }
        return "";
    }

    private static String hex(Color c) {
        return c == null ? "null" : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
