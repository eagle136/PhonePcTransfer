import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * DPI 缩放行为探针（只在 125% 的本机跑一次，用来定改法）：
 *
 *  1. 打印逻辑屏 / 缩放矩阵 / 屏幕分辨率 / 屏幕尺寸
 *  2. 真实显示一个含 12pt 文字与 200px 定宽面板的窗口
 *  3. 用 Robot 全屏抓物理像素，数出：
 *     - 窗口白块的实际物理宽 vs 逻辑宽  → 组件像素是否被 ×scale
 *     - 文字墨迹的实际物理宽 vs stringWidth → 字体是否被 ×scale
 *
 *  判读：
 *   两组都 ≈×1.25 → JVM 已整体等比缩放（布局天然 scale-invariant，不用动）
 *   组件 ×1.25 而文字 ≈×1.0 → 字体不吃变换（常见错配，需要显式缩字体）
 *   两组都 ≈×1.0 → 进程 DPI-unaware（Windows 整窗位图拉伸，布局也不破，只是糊）
 */
public final class DpiProbe {

    public static void main(String[] args) throws Exception {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice dev = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = dev.getDefaultConfiguration();
        AffineTransform tr = gc.getDefaultTransform();
        System.out.println("gc.bounds(logical)=" + gc.getBounds());
        System.out.println("defaultTransform=" + tr.getScaleX() + "," + tr.getScaleY());
        System.out.println("toolkit.screenResolution(dpi)=" + Toolkit.getDefaultToolkit().getScreenResolution());
        System.out.println("toolkit.screenSize=" + Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("sun.java2d.dpiaware=" + System.getProperty("sun.java2d.dpiaware"));
        System.out.println("sun.java2d.uiScale=" + System.getProperty("sun.java2d.uiScale"));

        final String text = "测试宽度测量0123456789ABab";
        final Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 12);

        java.awt.Frame fr = new java.awt.Frame() {
            @Override public void paint(Graphics g) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        fr.setUndecorated(true);
        fr.setBackground(Color.WHITE);
        fr.setLayout(new java.awt.BorderLayout());

        // 左：文字标签；右：200px 定宽灰块
        javax.swing.JLabel lb = new javax.swing.JLabel(text);
        lb.setFont(font);
        lb.setForeground(Color.BLACK);
        lb.setOpaque(false);
        lb.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        javax.swing.JPanel fix = new javax.swing.JPanel();
        fix.setOpaque(true);
        fix.setBackground(new Color(200, 200, 200));
        fix.setPreferredSize(new Dimension(200, 40));
        fr.add(lb, java.awt.BorderLayout.CENTER);
        fr.add(fix, java.awt.BorderLayout.EAST);

        fr.pack();
        int lw = fr.getWidth();
        int lh = fr.getHeight();
        int sw = lb.getFontMetrics(font).stringWidth(text);
        System.out.println("frame.logical=" + lw + "x" + lh);
        System.out.println("label.logical=" + lb.getPreferredSize().width + "x" + lb.getPreferredSize().height);
        System.out.println("stringWidth(12pt text)=" + sw);
        int swG = -1;
        fr.setLocation(60, 60);
        fr.setVisible(true);
        try {
            Thread.sleep(1200);
            // EDT 上再量一次「已显示组件」的字体度量与 graphics 度量
            final int[] out = new int[2];
            java.awt.event.ActionListener al = e -> { };
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                out[0] = lb.getFontMetrics(font).stringWidth(text);
                Graphics2D g2 = (Graphics2D) lb.getGraphics();
                if (g2 != null) {
                    out[1] = g2.getFontMetrics(font).stringWidth(text);
                    g2.dispose();
                }
            });
            System.out.println("componentFM.width=" + out[0] + "  graphicsFM.width=" + out[1]);

            Robot robot = new Robot();
            BufferedImage shot = robot.createScreenCapture(
                    new Rectangle(0, 0, Toolkit.getDefaultToolkit().getScreenSize().width * 3,
                            Toolkit.getDefaultToolkit().getScreenSize().height * 3));
            // 物理分辨率
            System.out.println("robotCapture=" + shot.getWidth() + "x" + shot.getHeight());

            // 找白块：从 (100,100) 开始向右找第一段连续纯白行/列（窗口在 60,60 逻辑）
            int wx0 = -1, wy0 = -1, wx1 = -1, wy1 = -1;
            int px0 = 60, py0 = 60; // 逻辑位置；物理可能 ×scale，先大范围搜 60..600
            outer:
            for (int y = 40; y < 600; y++) {
                for (int x = 40; x < 900; x++) {
                    if (isWhite(shot, x, y)) {
                        wx0 = x; wy0 = y;
                        break outer;
                    }
                }
            }
            if (wx0 < 0) {
                System.out.println("WHITE BOX NOT FOUND");
                return;
            }
            int x = wx0;
            while (x + 1 < shot.getWidth() && isWhite(shot, x + 1, wy0 + 3)) x++;
            wx1 = x;
            int y = wy0;
            while (y + 1 < shot.getHeight() && isWhite(shot, wx0 + 3, y + 1)) y++;
            wy1 = y;
            int bw = wx1 - wx0 + 1;
            int bh = wy1 - wy0 + 1;
            System.out.println("whiteBox.dev=" + bw + "x" + bh + " at " + wx0 + "," + wy0);
            System.out.println("box.scaleX=" + (double) bw / lw);

            // 灰块（200 逻辑宽）物理宽：在白块内找 200 灰（r=g=b≈200）
            int gy0 = wy0 + bh / 2;
            int g0 = -1, g1 = -1;
            for (int xx = wx0; xx <= wx1; xx++) {
                if (isGray(shot, xx, gy0)) {
                    if (g0 < 0) g0 = xx;
                    g1 = xx;
                }
            }
            if (g0 >= 0) {
                System.out.println("gray(200 logical).dev=" + (g1 - g0 + 1)
                        + "  scaleX=" + (double) (g1 - g0 + 1) / 200.0);
            } else {
                System.out.println("GRAY NOT FOUND");
            }

            // 文字墨迹：白块内找暗像素 bbox
            int ix0 = Integer.MAX_VALUE, iy0 = Integer.MAX_VALUE, ix1 = -1, iy1 = -1;
            for (int yy = wy0; yy <= wy1; yy++) {
                for (int xx = wx0; xx <= wx1; xx++) {
                    if (isDark(shot, xx, yy)) {
                        if (xx < ix0) ix0 = xx;
                        if (xx > ix1) ix1 = xx;
                        if (yy < iy0) iy0 = yy;
                        if (yy > iy1) iy1 = yy;
                    }
                }
            }
            if (ix1 >= 0) {
                int inkW = ix1 - ix0 + 1;
                int inkH = iy1 - iy0 + 1;
                System.out.println("ink.dev=" + inkW + "x" + inkH);
                FontMetrics fm = lb.getFontMetrics(font);
                System.out.println("inkDev/stringWidth=" + String.format("%.3f", (double) inkW / sw));
                System.out.println("fm.height(logical)=" + fm.getHeight());
                System.out.println("inkH/fm.height=" + String.format("%.3f", (double) inkH / fm.getHeight()));
            } else {
                System.out.println("INK NOT FOUND");
            }
        } finally {
            fr.setVisible(false);
            fr.dispose();
        }
        System.out.println("DPI PROBE DONE");
    }

    private static boolean isWhite(BufferedImage img, int x, int y) {
        int c = img.getRGB(x, y);
        return (c & 0xFFFFFF) >= 0xFAFAFA;
    }

    private static boolean isDark(BufferedImage img, int x, int y) {
        int c = img.getRGB(x, y);
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r < 110 && g < 110 && b < 110;
    }

    private static boolean isGray(BufferedImage img, int x, int y) {
        int c = img.getRGB(x, y);
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.abs(r - 200) < 25 && Math.abs(g - 200) < 25 && Math.abs(b - 200) < 25;
    }
}
