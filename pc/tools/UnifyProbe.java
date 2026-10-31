package com.phonepctransfer.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * 2026-09-29「全部统一这样显示」验证探针：
 *  ① 普通 JButton（无任何 Ui.* 样式）→ 全局 ButtonUI=ModernButtonUI → 应画 SECONDARY 灰卡
 *  ② Ui.flat 蓝字按钮 → 无卡片、蓝字保留（按钮中心采样 = 父容器底色）
 *  ③ Ui.flat 深底白字「×」→ 无卡片、深底保留（中心采样 ≈ #333333）
 *  ④ Ui.secondary / Ui.primary → 回归确认（灰卡 #F0F2F6 / 蓝卡 #1565C0）
 * 按按钮实际 bounds 中心采样，避免误采父容器底色。
 */
public class UnifyProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale", "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "off");
        System.setProperty("swing.aatext", "false");
        Ui.install();

        JFrame frame = new JFrame("UnifyProbe");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new GridLayout(5, 1, 8, 8));
        root.setBackground(Ui.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JButton plain = new JButton("普通按钮");
        JButton flatBlue = Ui.flat(new JButton("扁平蓝字按钮"));
        flatBlue.setForeground(new Color(0x15, 0x65, 0xC0));
        JButton flatX = Ui.flat(new JButton("×"));
        flatX.setForeground(Color.WHITE);
        flatX.setBackground(new Color(0x33, 0x33, 0x33));
        flatX.setOpaque(true);
        JButton sec = Ui.secondary(new JButton("次要按钮"));
        JButton pri = Ui.primary(new JButton("主按钮"));
        root.add(plain); root.add(flatBlue); root.add(flatX); root.add(sec); root.add(pri);

        frame.setContentPane(root);
        frame.setSize(420, 320);
        frame.setLocation(360, 240);
        frame.setVisible(true);
        SwingUtilities.invokeAndWait(() -> frame.getRootPane().revalidate());

        Thread.sleep(1200);
        BufferedImage shot = new Robot().createScreenCapture(new java.awt.Rectangle(360, 240, 420, 320));
        File out = new File("pc/build/probe/unify/UnifyProbe.png");
        out.getParentFile().mkdirs();
        ImageIO.write(shot, "png", out);
        System.out.println("shot -> " + out.getAbsolutePath());

        Point origin = frame.getLocation();
        check(shot, "plain    ", plain, origin);
        check(shot, "flatBlue ", flatBlue, origin);
        check(shot, "flatX    ", flatX, origin);
        check(shot, "secondary", sec, origin);
        check(shot, "primary  ", pri, origin);
        System.exit(0);
    }

    /** 按钮内部靠右缘 6px 处 12x6 区域众数色（在 padding 带内，避开居中的文字墨迹） */
    static void check(BufferedImage img, String label, JButton b, Point origin) {
        Point p = b.getLocationOnScreen();
        int cx = p.x - origin.x + 4;
        int cy = p.y - origin.y + b.getHeight() / 2;
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (int y = cy - 3; y < cy + 3; y++) for (int x = cx; x < cx + 8; x++) {
            int q = img.getRGB(x, y);
            String hex = String.format("#%02X%02X%02X", (q >> 16) & 255, (q >> 8) & 255, q & 255);
            counts.merge(hex, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream().sorted((a, b2) -> b2.getValue() - a.getValue()).limit(3)
                .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue()).append(") "));
        System.out.println(label + " center=" + cx + "," + cy + " size=" + b.getWidth() + "x" + b.getHeight() + "  " + sb);
    }
}
