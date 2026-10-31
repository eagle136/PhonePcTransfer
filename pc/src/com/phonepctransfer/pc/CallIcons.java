package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;

import javax.swing.Icon;
import javax.swing.JButton;

/**
 * 通话界面矢量图标（2026-09-16 用户定调：接听/挂断用「电话图标」风格，与 Android 端同构）。
 *
 * 全部用 {@link Graphics2D} 手绘（PC 端禁 emoji，YaHei 会渲染成方框；也不引外部图片资源）。
 * 提供两块能力：
 * <ol>
 *   <li>{@link #speakerOn}/{@link #speakerOff}/{@link #hangup}/{@link #answer}/
 *       {@link #rotate} —— 白色矢量图标（画在深色/彩色圆底上）。</li>
 *   <li>{@link #circleButton} —— 圆形图标按钮（悬停/按下有亮度反馈）。</li>
 * </ol>
 */
public final class CallIcons {

    private CallIcons() {
    }

    // ==================== 图标 ====================

    /** 喇叭（有声） */
    public static Icon speakerOn(int size) {
        return draw(size, (g2, s) -> {
            float f = s / 24f;
            g2.setStroke(stroke(1.8f * f));
            // 喇叭本体：方口 + 喇叭锥
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            p.moveTo(3 * f, 9.5f * f);
            p.lineTo(6.5f * f, 9.5f * f);
            p.lineTo(11 * f, 5 * f);
            p.lineTo(11 * f, 19 * f);
            p.lineTo(6.5f * f, 14.5f * f);
            p.lineTo(3 * f, 14.5f * f);
            p.closePath();
            g2.fill(p);
            // 两道声波弧
            arc(g2, 13.5f * f, 12 * f, 3.2f * f, 55);
            arc(g2, 13.5f * f, 12 * f, 6.4f * f, 55);
        });
    }

    /** 喇叭（静音 = 本体 + 一条斜杠） */
    public static Icon speakerOff(int size) {
        return draw(size, (g2, s) -> {
            float f = s / 24f;
            g2.setStroke(stroke(1.8f * f));
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            p.moveTo(3 * f, 9.5f * f);
            p.lineTo(6.5f * f, 9.5f * f);
            p.lineTo(11 * f, 5 * f);
            p.lineTo(11 * f, 19 * f);
            p.lineTo(6.5f * f, 14.5f * f);
            p.lineTo(3 * f, 14.5f * f);
            p.closePath();
            g2.fill(p);
            // 斜杠（静音）
            g2.setStroke(stroke(2.2f * f));
            g2.draw(new java.awt.geom.Line2D.Float(
                    14.5f * f, 7.5f * f, 21.5f * f, 16.5f * f));
        });
    }

    /** 挂断：水平话筒（弧顶朝上，两端朝下） */
    public static Icon hangup(int size) {
        return draw(size, (g2, s) -> {
            float f = s / 24f;
            g2.setStroke(stroke(3.2f * f));
            // AWT 角度正扫朝下（y 向下），dome（拱顶朝上）= start 180°、extent +180°：
            // 左端(9点) → 顶(12点) → 右端(3点)
            g2.draw(new Arc2D.Float(
                    4.5f * f, 8f * f, 15 * f, 12 * f, 180f, 180f, Arc2D.OPEN));
        });
    }

    /** 接听：斜置话筒（与挂断同形，整体顺时针转 45°） */
    public static Icon answer(int size) {
        return draw(size, (g2, s) -> {
            float f = s / 24f;
            g2.setStroke(stroke(3.2f * f));
            g2.rotate(Math.toRadians(45), s / 2.0, s / 2.0);
            g2.draw(new Arc2D.Float(
                    4.5f * f, 8f * f, 15 * f, 12 * f, 180f, 180f, Arc2D.OPEN));
        });
    }

    /** 旋转（顺时针）：3/4 圆弧 + 开口处的箭头 */
    public static Icon rotate(int size) {
        return draw(size, (g2, s) -> {
            float f = s / 24f;
            g2.setStroke(stroke(1.9f * f));
            // 3/4 圆弧（开口朝右上）
            g2.draw(new java.awt.geom.Arc2D.Float(5.5f * f, 5.5f * f, 13 * f, 13 * f,
                    30f, 280f, Arc2D.OPEN));
            // 箭头：沿顺时针方向指出去
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            p.moveTo(15.2f * f, 3.2f * f);
            p.lineTo(19.4f * f, 7.6f * f);
            p.lineTo(13.6f * f, 8.6f * f);
            p.closePath();
            g2.fill(p);
        });
    }

    // ==================== 圆形按钮 ====================
    /**
     * 圆形图标按钮：实心圆底 + 居中图标，悬停/按下有亮度反馈。
     *
     * @param icon   白色矢量图标（见上）
     * @param bg     圆底颜色（接听绿 / 挂断红 / 半透明黑）
     * @param tip    悬停提示（兼当无障碍名）
     * @param size   按钮直径（px）
     */
    public static JButton circleButton(Icon icon, Color bg, String tip, int size) {
        JButton b = new JButton(icon) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g2 = (Graphics2D) g0.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    Color c = bg;
                    if (getModel().isPressed()) {
                        c = bg.darker();
                    } else if (getModel().isRollover()) {
                        c = bg.brighter();
                    }
                    g2.setColor(c);
                    g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                } finally {
                    g2.dispose();
                }
                // 圆底画完后由 super 画图标
                super.paintComponent(g0);
            }
        };
        // 2026-09-29（「全部统一」配套）：自绘圆底图标按钮保持 FLAT（不套 Modern 灰卡）
        Ui.flat(b);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setPreferredSize(new java.awt.Dimension(size, size));
        b.setMaximumSize(new java.awt.Dimension(size, size));
        b.setMinimumSize(new java.awt.Dimension(size, size));
        return b;
    }

    // ==================== 内部工具 ====================

    private interface Painter {
        void paint(Graphics2D g2, int size);
    }

    private static Icon draw(int size, Painter painter) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);
                    g2.translate(x, y);
                    g2.setColor(Color.WHITE);
                    painter.paint(g2, size);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    private static java.awt.BasicStroke stroke(float w) {
        return new java.awt.BasicStroke(w, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND);
    }

    /** 以 (cx,cy) 为圆心、r 为半径画朝右张开的声波弧 */
    private static void arc(Graphics2D g2, float cx, float cy, float r, double span) {
        g2.draw(new Arc2D.Double(cx - r, cy - r, r * 2.0, r * 2.0,
                -span / 2, span, Arc2D.OPEN));
    }
}
