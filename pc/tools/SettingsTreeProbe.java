package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;

/** 设置窗内容子件实际 bounds 全量打印（2of3 B5.6 裁边定位） */
public final class SettingsTreeProbe {

    public static void main(String[] args) throws Exception {
        Ui.install();
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);

        JDialog d = new JDialog((java.awt.Frame) null, "设置", false);
        WindowChrome.install(d, "设置", d::dispose);
        JComponent content = ChatWindow.SettingsDialog.buildContent(d, store, link);
        JButton ok = Ui.primary(new JButton("关闭"));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        south.add(ok);
        d.add(content, BorderLayout.CENTER);
        d.add(south, BorderLayout.SOUTH);
        d.setSize(470, 420 + WindowChrome.DIALOG_TITLE_H);
        d.setVisible(true);
        SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(600);

        dump(content, 0);
        System.out.println("south actual = " + south.getSize());
        System.exit(0);
    }

    static void dump(Component c, int indent) {
        StringBuilder sb = new StringBuilder("  ".repeat(indent));
        String text = "";
        if (c instanceof javax.swing.JLabel) {
            text = " \"" + ((javax.swing.JLabel) c).getText().replace("\n", "\\n")
                    .substring(0, Math.min(18, ((javax.swing.JLabel) c).getText().replace("\n", "\\n").length())) + "\"";
        }
        sb.append(c.getClass().getSimpleName()).append(text)
          .append(" bounds=").append(c.getBounds())
          .append(" pref=").append(c.getPreferredSize());
        System.out.println(sb);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                dump(k, indent + 1);
            }
        }
    }
}
