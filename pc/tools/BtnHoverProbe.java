package com.phonepctransfer.pc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * 按钮默认态 vs hover 态像素对比探针（2026-09-29 用户反馈：
 * 「切换主题/设置/通话/发送文件」按钮默认发糊、鼠标移过去变清晰）。
 *
 * 复现 PcMain 的渲染环境（uiScale=1.0 + 文本 AA 关 + 宋体），
 * 建 4 个 SECONDARY 按钮，默认态抓屏 → 强制 rollover 再抓屏，
 * 4x 放大输出 + 文字区像素指标。
 */
public final class BtnHoverProbe {

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale", "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "off");
        System.setProperty("swing.aatext", "false");
        Ui.install();
        Ui.applyDark(false); // 浅色

        final JButton[] btns = new JButton[4];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("btn");
            JPanel p = new JPanel(new GridLayout(1, 0, 8, 0));
            p.setOpaque(true);
            p.setBackground(Ui.SURFACE);
            String[] names = { "切到深色", "设置", "通话", "发送文件" };
            for (int i = 0; i < 4; i++) {
                JButton b = new JButton(names[i]);
                b.setFont(new Font(Ui.FONT, Font.PLAIN, i < 2 ? 13 : 14));
                btns[i] = Ui.secondary(b);
                p.add(b);
            }
            f.setContentPane(p);
            f.setSize(640, 80);
            f.setLocation(360, 240);
            f.setVisible(true);
        });
        Thread.sleep(1500);

        File out = new File("pc/build/probe/btnhover");
        out.mkdirs();

        // 1) 默认态
        shot(f(out, "default"), 360, 240, 640, 80);
        // 2) 全部按钮强制 rollover
        final JButton[] bs = btns;
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : bs) {
                b.getModel().setRollover(true);
            }
        });
        Thread.sleep(800);
        shot(f(out, "hover"), 360, 240, 640, 80);

        // 3) 字体信息
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : btns) {
                Font ft = b.getFont();
                System.out.println("btn[" + b.getText() + "] font=" + ft.getFamily()
                        + " size=" + ft.getSize() + " style=" + ft.getStyle());
            }
        });
        System.out.println("### done -> " + out.getAbsolutePath());
        System.exit(0);
    }

    static File f(File d, String n) { return new File(d, n + ".png"); }

    static void shot(File file, int x, int y, int w, int h) throws Exception {
        Robot r = new Robot();
        BufferedImage img = r.createScreenCapture(new Rectangle(x, y, w, h));
        ImageIO.write(img, "png", file);
        System.out.println("shot -> " + file.getAbsolutePath());
        // 4x 放大版便于目视
        BufferedImage big = new BufferedImage(w * 4, h * 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, 0, 0, w * 4, h * 4, null);
        g.dispose();
        ImageIO.write(big, "png", new File(file.getParentFile(), file.getName().replace(".png", "_4x.png")));
    }
}
