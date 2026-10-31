package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * 应用图标渲染器 —— **PC 端唯一的图标来源**。
 *
 * <p>设计（2026-09-26 重绘）：现代渐变蓝圆角方块 + 白色电脑/手机剪影 + 双向流线箭头。
 * 几何在 108x108 viewport 里，4x 超采样出图后面积平均降采样，小尺寸不糊。
 *
 * <p>配色：
 * <ul>
 *   <li>背景 = 对角渐变 #42A5F5 → #1565C0（亮蓝到深蓝，比旧纯 #0E4C8A 更现代）</li>
 *   <li>设备外壳 = 纯白；屏幕内框透出背景色形成「挖空」</li>
 *   <li>上箭头（手机→电脑，左向）= 白；下箭头（电脑→手机，右向）= 亮青 #4FC3F7</li>
 * </ul>
 */
public final class AppIcon {

    private AppIcon() {
    }

    private static final float VIEW = 108f;
    private static final int SS = 4;

    private static final Color BG_FROM = new Color(0x42, 0xA5, 0xF5);
    private static final Color BG_TO   = new Color(0x15, 0x65, 0xC0);
    private static final Color SHELL = Color.WHITE;
    private static final Color ARROW_UP = Color.WHITE;
    private static final Color ARROW_DOWN = new Color(0x4F, 0xC3, 0xF7);

    public static BufferedImage render(int size) {
        int n = Math.max(1, size);
        BufferedImage big = paint(n * SS);
        return SS == 1 ? big : downscale(big, n);
    }

    private static BufferedImage paint(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            float r = n * 0.204f;
            g.setPaint(new GradientPaint(0, 0, BG_FROM, n, n, BG_TO));
            g.fill(new RoundRectangle2D.Float(0, 0, n, n, r, r));

            g.scale(n / VIEW, n / VIEW);

            // —— 电脑（左侧）：外壳右缘 x=52 ——
            g.setColor(SHELL);
            g.fill(new RoundRectangle2D.Float(14, 28, 38, 28, 4, 4));
            // 屏幕内框（挖空透出背景）
            g.setColor(BG_TO);
            g.fill(new RoundRectangle2D.Float(19, 33, 28, 18, 2, 2));
            // 支架颈 + 底座
            g.setColor(SHELL);
            g.fill(new Rectangle2D.Float(29, 56, 8, 4));
            g.fill(new RoundRectangle2D.Float(22, 60, 22, 4, 2, 2));

            // —— 手机（右侧）：左缘 x=76 ——
            g.setColor(SHELL);
            g.fill(new RoundRectangle2D.Float(76, 38, 22, 40, 5, 5));
            g.setColor(BG_TO);
            g.fill(new RoundRectangle2D.Float(80, 44, 14, 28, 2, 2));
            g.fill(new RoundRectangle2D.Float(83, 75, 8, 1.5f, 0.75f, 0.75f));

            // —— 双向箭头（空隙 x=52..76 之间）——
            // 上箭头：手机→电脑（向左），白色
            g.setColor(ARROW_UP);
            g.fill(new RoundRectangle2D.Float(58, 44, 14, 4, 2, 2));       // 横杆 58..72
            g.fill(tri(58, 41, 52, 46, 58, 51));                           // 左箭头尖 x=52

            // 下箭头：电脑→手机（向右），亮青
            g.setColor(ARROW_DOWN);
            g.fill(new RoundRectangle2D.Float(54, 64, 14, 4, 2, 2));       // 横杆 54..68
            g.fill(tri(68, 61, 74, 66, 68, 71));                           // 右箭头尖 x=74
        } finally {
            g.dispose();
        }
        return img;
    }

    private static Path2D.Float tri(float x1, float y1, float x2, float y2, float x3, float y3) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.closePath();
        return p;
    }

    private static BufferedImage downscale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int cells = SS * SS;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                for (int dy = 0; dy < SS; dy++) {
                    for (int dx = 0; dx < SS; dx++) {
                        int c = src.getRGB(x * SS + dx, y * SS + dy);
                        int a = c >>> 24;
                        aSum += a;
                        rSum += ((c >> 16) & 0xFF) * a;
                        gSum += ((c >> 8) & 0xFF) * a;
                        bSum += (c & 0xFF) * a;
                    }
                }
                int a = aSum / cells;
                int r = aSum == 0 ? 0 : rSum / aSum;
                int gg = aSum == 0 ? 0 : gSum / aSum;
                int b = aSum == 0 ? 0 : bSum / aSum;
                out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }

    public static java.util.List<java.awt.Image> renderSet() {
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128};
        java.util.List<java.awt.Image> list = new java.util.ArrayList<java.awt.Image>(sizes.length);
        for (int s : sizes) list.add(render(s));
        return list;
    }

    public static BufferedImage contactSheet(int size, int... sizes) {
        BufferedImage out = new BufferedImage(size * sizes.length, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new Color(0xF2, 0xF3, 0xF5));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            for (int i = 0; i < sizes.length; i++) {
                BufferedImage ic = render(sizes[i]);
                int pad = (size - sizes[i]) / 2;
                g.drawImage(ic, i * size + pad, pad, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }
}