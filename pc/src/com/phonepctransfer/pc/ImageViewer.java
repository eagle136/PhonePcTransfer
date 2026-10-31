package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 全屏图片查看器：
 *  - 打开即「适应屏幕」
 *  - **双击**在「适应屏幕」与「原图 1:1」之间切换
 *  - 按住左键拖动平移；ESC 或右上角悬浮按钮关闭
 */
public final class ImageViewer {

    private ImageViewer() {
    }

    public static void show(Window owner, File f) {
        if (f == null || !f.isFile()) {
            return;
        }
        // 解码放后台线程：大图解几秒也不能卡住 EDT（否则主窗先假死、查看器再出问题）
        // daemon：解码卡住（外部 ffmpeg 派生）时不推迟 JVM 退出
        Thread decode = new Thread(() -> {
            // 长边上限 3000px：足够全屏查看，又不至于解出会撑爆内存的巨图
            BufferedImage img = ImageCodec.read(f, 3000);
            SwingUtilities.invokeLater(() -> {
                if (img == null) {
                    JOptionPane.showMessageDialog(owner, "无法读取图片：\n" + f.getAbsolutePath());
                    return;
                }
                new ViewerWindow(owner, img, f.getName()).setVisible(true);
            });
        }, "image-viewer-decode");
        decode.setDaemon(true);
        decode.start();
    }

    private static final class ViewerWindow extends JDialog {

        private final ImagePanel panel;
        private final JButton close;

        ViewerWindow(Window owner, BufferedImage img, String title) {
            super(owner, title, ModalityType.APPLICATION_MODAL);
            setUndecorated(true);
            // 覆盖除任务栏外的整个屏幕，保证「全屏」观感又随时能关掉
            Rectangle b = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            setBounds(b);

            panel = new ImagePanel(img);

            // 关闭按钮用「×」(U+00D7)：雅黑有字形；「✕」(U+2715) 无字形会渲染成「口」
            close = new JButton("×");
            // 2026-09-29（「全部统一」配套）：自设深灰底白字「×」保持 FLAT（不套 Modern 灰卡）
            Ui.flat(close);
            // 档位：常规 15（=发送文件按钮同档）
            close.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
            close.setForeground(Color.WHITE);
            close.setBackground(new Color(0x33, 0x33, 0x33));
            close.setOpaque(true);
            close.setFocusPainted(false);
            close.setMargin(new Insets(2, 10, 2, 10));
            close.setBorder(BorderFactory.createLineBorder(new Color(0xFF, 0xFF, 0xFF, 110)));
            close.setToolTipText("关闭（ESC）");
            close.addActionListener(e -> dispose());

            // 布局交给 doLayout：窗口首次显示和每次尺寸变化都会走这里。
            // 旧实现只在构造期 + componentResized 里摆放 —— setBounds 先于
            // 监听器注册，首次显示不触发 resize，图片面板永远 0×0 → 白屏。
            final JLayeredPane lp = new JLayeredPane() {
                @Override
                public void doLayout() {
                    panel.setBounds(0, 0, getWidth(), getHeight());
                    close.setBounds(Math.max(0, getWidth() - 56), 14, 46, 34);
                }
            };
            lp.add(panel, JLayeredPane.DEFAULT_LAYER);
            lp.add(close, JLayeredPane.PALETTE_LAYER);
            setContentPane(lp);

            // ESC 关闭
            JComponent rp = getRootPane();
            rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeViewer");
            rp.getActionMap().put("closeViewer", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });
        }
    }

    /** 自绘图片面板：承担双击切换与拖动平移 */
    private static final class ImagePanel extends JPanel {

        private final BufferedImage img;
        private boolean oneToOne;
        private int offX;
        private int offY;
        private Point drag;

        ImagePanel(BufferedImage img) {
            this.img = img;
            setBackground(Color.BLACK);
            setOpaque(true);

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        oneToOne = !oneToOne;
                        offX = 0;
                        offY = 0;
                        repaint();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    drag = e.getPoint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    drag = null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (drag == null) {
                        return;
                    }
                    offX += e.getX() - drag.x;
                    offY += e.getY() - drag.y;
                    drag = e.getPoint();
                    clampOffset();
                    repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    clampOffset();
                    repaint();
                }
            });
        }

        private double scale() {
            if (oneToOne) {
                return 1.0;
            }
            if (getWidth() <= 0 || getHeight() <= 0) {
                return 1.0;
            }
            return Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
        }

        private void clampOffset() {
            double s = scale();
            int maxX = (int) Math.max(0, (img.getWidth() * s - getWidth()) / 2);
            int maxY = (int) Math.max(0, (img.getHeight() * s - getHeight()) / 2);
            offX = Math.max(-maxX, Math.min(maxX, offX));
            offY = Math.max(-maxY, Math.min(maxY, offY));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            double s = scale();
            int w = Math.max(1, (int) Math.round(img.getWidth() * s));
            int h = Math.max(1, (int) Math.round(img.getHeight() * s));
            int x = (getWidth() - w) / 2 + offX;
            int y = (getHeight() - h) / 2 + offY;
            g2.drawImage(img, x, y, w, h, null);

            // 档位：常规 15（=发送文件按钮同档）
            g2.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
            g2.setColor(new Color(0xCC, 0xCC, 0xCC));
            String hint = oneToOne
                    ? "1:1 原图 · 双击返回适应屏幕 · 拖动平移 · ESC 关闭"
                    : "适应屏幕 · 双击 1:1 原图 · 拖动平移 · ESC 关闭";
            g2.drawString(hint, 16, getHeight() - 14);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    Math.min(img.getWidth(), 1200),
                    Math.min(img.getHeight(), 900));
        }
    }
}
