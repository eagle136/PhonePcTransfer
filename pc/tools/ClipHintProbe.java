package com.phonepctransfer.pc;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JLabel;

/** clipHint HTML 标签隔离实验：preferred 尺寸 + 离屏渲染行数 */
public final class ClipHintProbe {

    public static void main(String[] args) throws Exception {
        Ui.install();
        JLabel hint = new JLabel("<html><body style='width:400px'><span style='font-size:11px;color:#666'>"
                + "本机复制的内容会<b>自动</b>推给手机，平时不用点这两个按钮。"
                + "手机端受系统限制，只在切回前台时读一次自己的剪贴板，所以「拉取」要等它在手机上操作过才有内容。"
                + "</span></body></html>");
        System.out.println("label font = " + hint.getFont());
        System.out.println("preferred  = " + hint.getPreferredSize());

        int w = 446, h = 48; // 与设置窗内实际 bounds 一致
        hint.setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        hint.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
        System.out.println("saved " + args[0] + " " + w + "x" + h);
        System.exit(0);
    }
}
