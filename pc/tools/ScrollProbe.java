package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * 最小滚动条离屏探针（T11 诊断用）：只装一个 12 宽 FlatScrollBarUI 垂直滚动条，
 * 用可识别底色 #112233 垫底，隔离观察轨道 / 滑块 / 箭头按钮的真实渲染。
 * 不进 dist，只编译到 build/probe。
 */
public final class ScrollProbe {

    /** 递归 doLayout：离屏组件树 validate() 不生效（LayoutProbe 实测），必须手动递归 */
    static void layoutRec(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layoutRec((Container) ch);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "dark";
        Ui.applyDark("dark".equals(mode));

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x11, 0x22, 0x33)); // 可识别底色
        JTextArea ta = new JTextArea();
        for (int i = 0; i < 200; i++) {
            ta.append("line " + i + "\n");
        }
        JScrollPane sp = new JScrollPane(ta);
        Ui.installScrollBar(sp, 12);
        p.add(sp);
        p.setSize(200, 300);
        layoutRec(p); // 离屏组件树必须递归 doLayout（validate() 不生效，LayoutProbe 实测）
        System.out.println("panel " + p.getBounds());
        System.out.println("sp    " + sp.getBounds());
        JScrollBar vbar = sp.getVerticalScrollBar();
        System.out.println("vbar  " + vbar.getBounds());
        for (java.awt.Component ch : vbar.getComponents()) {
            System.out.println("   btn " + ch.getClass().getSimpleName()
                    + " " + ch.getBounds() + " visible=" + ch.isVisible());
        }
        BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        p.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File("shots/scroll_" + mode + ".png"));
        System.out.println("saved scroll_" + mode + ".png");
        System.exit(0);
    }
}