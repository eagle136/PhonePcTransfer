package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 设置窗高度核算：内容 preferred 高 vs 窗口可分配高（2of3 B5.6 裁边回归定位） */
public final class SettingsHeightProbe {

    public static void main(String[] args) throws Exception {
        Ui.install();
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);

        JDialog d = new JDialog((java.awt.Frame) null, "设置", false); // 探针：非模态，避免主线程阻塞
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

        System.out.println("content preferred = " + content.getPreferredSize());
        System.out.println("content actual    = " + content.getSize());
        System.out.println("south preferred   = " + south.getPreferredSize());
        System.out.println("south actual      = " + south.getSize());
        System.out.println("contentPane       = " + d.getContentPane().getSize());
        System.out.println("dialog            = " + d.getSize()
                + " titleH=" + WindowChrome.DIALOG_TITLE_H);
        System.exit(0);
    }
}
