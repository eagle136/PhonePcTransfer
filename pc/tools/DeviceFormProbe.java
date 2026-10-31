package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * 离屏探针（2026-10-04）：「添加设备」对话框两个输入框的**文字 vs 框底**实测对比度。
 *
 * <p>同一帧里并排渲染两种框，直接给出「改前 / 改后」的数字：
 * <ul>
 *   <li>{@code raw }：裸 {@code new JTextField()} + 主题 UIManager 默认前景
 *       （= 改前的真身，框底吃 LAF 白）；</li>
 *   <li>{@code field}：套 {@link Ui#field} —— 与聊天输入框同一条路径（= 改后）。</li>
 * </ul>
 *
 * <p>判据：只量**内芯**（x/y 各避开圆角边框一圈），框底 = 内芯众数色，
 * 文字墨迹 = 内芯里占比最大的非底色（AA 关，字形像素基本是纯前景）；
 * 报 WCAG 相对亮度比。比值 &lt; 3 即肉眼「几乎看不见」。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" \
 *      com.phonepctransfer.pc.DeviceFormProbe
 * </pre>
 *
 * 只用于自检：编译进 build/probe，**不进** dist 里的发布 jar。
 */
public final class DeviceFormProbe {

    private static final String TEXT = "192.168.1.108";
    private static final int W = 300;
    private static final int H = 36;
    /** 内芯取样区：Ui.field 的边框留白是 12px，圆角环画在最外圈 */
    private static final int INSET_X = 16, INSET_Y = 9;

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale", "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "off");
        System.setProperty("swing.aatext", "false");

        File shotDir = new File("../shots");
        PrintWriter rep = new PrintWriter(new java.io.FileWriter(
                new File(shotDir, "device_field_report.txt"), false));
        rep.println("device add/edit dialog - text field contrast (WCAG ratio, <3 = invisible)");

        for (boolean dark : new boolean[]{true, false}) {
            Ui.install();
            Ui.applyDark(dark);
            String theme = dark ? "DARK " : "LIGHT";
            rep.println("== " + theme + " TEXT_MAIN=" + hex(Ui.TEXT_MAIN)
                    + " FIELD_DARK=" + hex(Ui.FIELD_DARK) + " SURFACE=" + hex(Ui.SURFACE));

            JTextField raw = new JTextField(TEXT);
            raw.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            raw.setHorizontalAlignment(SwingConstants.LEFT);

            JTextField fixed = Ui.field(new JTextField(TEXT));
            fixed.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));

            measure(theme + " raw  (before)", raw, rep);
            measure(theme + " field(after )", fixed, rep);

            BufferedImage img = new BufferedImage(W, H * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            raw.setSize(W, H);
            raw.paint(g);
            g.translate(0, H);
            fixed.setSize(W, H);
            fixed.paint(g);
            g.dispose();
            File f = new File(shotDir, "device_field_" + (dark ? "dark" : "light") + ".png");
            ImageIO.write(img, "png", f);
        }
        rep.close();
        System.out.println("OK -> shots/device_field_report.txt");
        System.exit(0);
    }

    /** 内芯底色（众数）与文字墨迹（占比最大的非底色）的对比度 */
    private static void measure(String tag, JTextField tf, PrintWriter rep) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        tf.setSize(W, H);
        tf.paint(g);
        g.dispose();

        LinkedHashMap<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();
        int n = 0;
        for (int y = INSET_Y; y < H - INSET_Y; y++) {
            for (int x = INSET_X; x < W - INSET_X; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                Integer c = counts.get(rgb);
                counts.put(rgb, c == null ? 1 : c + 1);
                n++;
            }
        }
        int bgRgb = 0, bgN = -1, inkRgb = bgRgb, inkN = -1;
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bgN) {
                bgN = e.getValue();
                bgRgb = e.getKey();
            }
        }
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getKey() != bgRgb && e.getValue() > inkN) {
                inkN = e.getValue();
                inkRgb = e.getKey();
            }
        }
        double r = ratio(lum(inkRgb), lum(bgRgb));
        rep.println(tag
                + " | compFg=" + hex(tf.getForeground())
                + " | fieldBg(mode)=" + hex(bgRgb) + " " + bgN + "/" + n + "px"
                + " | ink=" + hex(inkRgb) + " " + inkN + "px"
                + " | contrast=" + String.format("%.2f:1", r)
                + (r < 3.0 ? "  << INVISIBLE" : "  OK"));
    }

    private static double lum(int rgb) {
        double[] c = new double[3];
        c[0] = ((rgb >> 16) & 0xFF) / 255.0;
        c[1] = ((rgb >> 8) & 0xFF) / 255.0;
        c[2] = (rgb & 0xFF) / 255.0;
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double ratio(double a, double b) {
        double hi = Math.max(a, b), lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static String hex(Color c) {
        return hex(c.getRGB() & 0xFFFFFF);
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }
}
