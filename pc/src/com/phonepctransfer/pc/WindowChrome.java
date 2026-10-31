package com.phonepctransfer.pc;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * 现代窗口外壳（离屏渲染）：去原生标题栏 + 圆角内容区 + 柔和阴影 + 自绘标题栏。
 *
 * <p>「老土感」的最大来源是 JFrame 自带的 Windows 原生标题栏和直角边框。这里：
 * <ol>
 *   <li>{@link JFrame#setUndecorated(true)} 去掉原生装饰；</li>
 *   <li>{@link JFrame#setBackground(Color)} 设为全透明，让窗口四周边角可见；</li>
 *   <li>根面板四周留出 {@link #SHADOW} 的透明边，在那里用多层半透明圆角矩形模拟柔和投影；</li>
 *   <li>中间一个 {@link RoundedContent} 面板，paintComponent 里画白色圆角底，
 *       paintChildren 用 {@link RoundRectangle2D} 做裁剪，子组件不会溢出圆角；</li>
 *   <li>内容区顶部叠一条 {@link TitleBar}：图标 + 标题 + 最小化/关闭按钮，整条可拖动。</li>
 * </ol>
 *
 * <p>必须在窗口 {@code setVisible(true)} 之前装好（{@code setUndecorated} 的硬约束）。
 */
final class WindowChrome {

    private WindowChrome() { }

    /** 内容区圆角半径（px） */
    static final int RADIUS = 10;
    /** 四周阴影宽度（px）——根面板在内容区外留出这么多透明边画投影 */
    static final int SHADOW = 16;
    /** 自绘标题栏高度（px）——主窗 */
    static final int TITLE_H = 42;
    /** 弹窗/通话窗标题栏高度（px）——比主窗矮一截，观感更轻 */
    static final int DIALOG_TITLE_H = 32;

    /**
     * 把内容面板包进现代窗口外壳。
     *
     * @param frame   目标窗口（调用方需先 setUndecorated(true) + setBackground(透明)）
     * @param content 原来要放进窗口的内容（左栏 + 聊天区整体）
     * @param onClose 点关闭按钮时的回调（= 隐藏到托盘，不是 dispose）
     * @return 应 add 到 frame 的根面板
     */
    static JComponent wrap(JFrame frame, JComponent content, Runnable onClose) {
        // —— 阴影层：四周透明边，paintComponent 里画柔和投影 ——
        JPanel shadowPane = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int rx = SHADOW, ry = SHADOW;
                int rw = getWidth() - SHADOW * 2;
                int rh = getHeight() - SHADOW * 2;
                // 从内到外逐层扩大、alpha 递减，模拟高斯模糊投影
                for (int i = SHADOW; i >= 1; i--) {
                    double t = (double) i / SHADOW;          // 0=最内圈 1=最外圈
                    int alpha = (int) (36 * (1 - t) * (1 - t)); // 二次衰减，中心更浓
                    g2.setColor(new Color(0, 0, 0, Math.max(0, alpha)));
                    g2.fillRoundRect(rx - i, ry - i, rw + i * 2, rh + i * 2,
                            RADIUS + i, RADIUS + i);
                }
                g2.dispose();
            }
        };
        shadowPane.setOpaque(false);
        shadowPane.setBorder(BorderFactory.createEmptyBorder(SHADOW, SHADOW, SHADOW, SHADOW));

        shadowPane.add(new RoundedContent(content, onClose), BorderLayout.CENTER);
        return shadowPane;
    }

    /**
     * 画窗口最外圈 1px 细线（2026-09-29 新增）。
     *
     * <p>为什么要它：深色 SURFACE = 纯黑、浅色 SURFACE = 纯白，窗口四边与桌面/阴影
     * 亮度差太小，用户看不出"界面到哪儿为止"。这里沿圆角路径画一圈 WIN_BORDER 细线。
     *
     * <p>两个关键实现点：
     * <ol>
     *   <li><b>坐标内缩 0.5px</b>：BasicStroke(1f) 以路径为中心线，画在 (0,0) 会有一半落在
     *       组件外并被裁掉（看起来只有 0.5px、发虚）。用 (0.5, 0.5, w-1, h-1) 让整条线贴在
     *       组件内部，1px 完整、锐利。</li>
     *   <li><b>corner &lt;= 0 时直接返回</b>：全屏态圆角已被归零，满屏再描一圈边等于屏幕上
     *       多出一条黑线，属回归。</li>
     * </ol>
     * 调用方必须在 paintChildren 的 super.paintChildren(...) **之后**调用，保证线在最上层。
     */
    private static void paintWindowBorder(Graphics2D g2, int w, int h, int corner) {
        if (corner <= 0 || w <= 2 || h <= 2) {
            return;
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Ui.WIN_BORDER);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, corner, corner));
    }

    /**
     * 弹窗/通话窗外壳（Q5 = 只要圆角不要阴影）：圆角 SURFACE 底 + 圆角裁剪子组件 + 顶部自绘标题栏。
     *
     * <p>与主窗 {@link RoundedContent} 的区别：没有 SHADOW 投影层；圆角半径可运行时归零
     *（VideoWindow 全屏：满屏矩形不能四角透明透出桌面）。
     */
    static final class DialogChrome extends JPanel {
        private final TitleBar bar;
        private int corner = RADIUS;

        DialogChrome(TitleBar bar, JComponent content) {
            super(new BorderLayout());
            this.bar = bar;
            setOpaque(false);
            add(bar, BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
        }

        /**
         * 进全屏圆角归零 / 退出恢复 {@link #RADIUS}。
         *
         * <p>必须同步给标题栏：它自绘的深灰底也带 RADIUS 圆角，全屏（corner=0）时不同步
         * 就会在满屏顶角露出两块 SURFACE 色的圆弧缺口（2026-10-03 标题栏不再随全屏隐藏，
         * 这条路径第一次真的会画到屏幕上）。
         */
        void setCornerRadius(int r) {
            corner = r;
            bar.setCorner(r);
            repaint();
        }

        /** 改备注后同步标题文字（语音窗 setPeer 用） */
        void setTitle(String t) {
            bar.setTitle(t);
        }

        /**
         * 同步标题栏最大化钮的字形（全屏 = 双框「还原」/ 窗口态 = 单框「放大」），
         * 并顺带锁/放标题栏拖动（满屏态不该被拖走）。
         *
         * <p>没装最大化钮的窗口（来电弹窗、语音窗）不必调用；真调了也只是换不了字形，
         *   现有调用方里只有 VideoWindow 装了该钮。
         */
        void setMaximized(boolean maximized) {
            bar.setMaximized(maximized);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // 圆角内容底跟主题（深色 = 纯黑）；读动态字段，换主题重绘即变色
            g2.setColor(Ui.SURFACE);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), corner, corner);
            g2.dispose();
        }

        @Override protected void paintChildren(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // 关键：KEY_ANTIALIASING=ON 会被子组件的 create() 继承，Java2D 在**没有显式
            // KEY_TEXT_ANTIALIASING** 时用 KEY_ANTIALIASING 兜底画文字 → 宋体小字从点阵退化成
            // 灰度抗锯齿轮廓（发糊）。整窗重绘（切主题）走这条路，鼠标 hover 的局部重绘由
            // RepaintManager 直接画子组件、默认提示是无 AA → 清晰，于是「切主题糊、移过去清」。
            // 这里显式关掉文字 AA，让两条路径渲染口径一致（圆角裁剪仍用形状 AA，角不变锯齿）。
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), corner, corner));
            super.paintChildren(g2);
            // 外框细线（2026-09-29）：主窗与弹窗/通话窗同一套视觉；corner=0（全屏）自动跳过
            g2.setClip(null);
            paintWindowBorder(g2, getWidth(), getHeight(), corner);
            g2.dispose();
        }
    }

    /**
     * 给弹窗 JDialog 装自绘外壳（Q5 = 只要圆角不要阴影，无 SHADOW 层）。
     *
     * <p><b>必须在 {@code setVisible(true)} 之前调用</b>（setUndecorated 的硬约束）。
     * 设备不支持逐像素透明时自动退化为不透明直角窗（深灰标题栏仍在，只丢圆角）。
     *
     * @return 装好的外壳（供同步标题 / 全屏圆角归零等）
     */
    static DialogChrome install(JDialog d, String title, Runnable onClose) {
        return installWindow(d, title, false, false, null, onClose);
    }

    /**
     * 给独立 JFrame（通话窗）装自绘外壳。约束同 {@link #install(JDialog, String, Runnable)}。
     *
     * @param showMinimize 是否显示最小化按钮（通话窗只留关闭）
     */
    static DialogChrome install(JFrame f, String title, boolean showMinimize, Runnable onClose) {
        return installWindow(f, title, showMinimize, false, null, onClose);
    }

    /**
     * 同上，再加一个**最大化/还原**钮（VideoWindow 用，2026-10-03）。
     *
     * <p>为什么放标题栏而不是画面上：画面浮动圆钮默认隐藏、5s 自动收起，用户三轮都报告
     * 「视频窗上没有全屏按钮」；普通窗口的放大钮在右上角，一眼就在那里。
     *
     * @param onMaximize 点击钮的回调（全屏/还原由调用方决定；本类只负责字形跟着
     *                   {@link DialogChrome#setMaximized(boolean)} 换）
     */
    static DialogChrome install(JFrame f, String title, boolean showMinimize,
                                boolean showMaximize, Runnable onMaximize, Runnable onClose) {
        return installWindow(f, title, showMinimize, showMaximize, onMaximize, onClose);
    }

    private static DialogChrome installWindow(Window w, String title,
                                              boolean showMinimize,
                                              boolean showMaximize, Runnable onMaximize,
                                              Runnable onClose) {
        // setUndecorated / contentPane 是 Frame / Dialog 各自的方法，按类型分派
        java.awt.Container oldPane;
        if (w instanceof JFrame) {
            ((JFrame) w).setUndecorated(true);
            oldPane = ((JFrame) w).getContentPane();
        } else {
            ((JDialog) w).setUndecorated(true);
            oldPane = ((JDialog) w).getContentPane();
        }
        // 圆角前提：逐像素透明；不支持则退化为不透明直角窗（主诉求深灰标题栏不受影响）
        boolean translucent = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        if (translucent) {
            w.setBackground(new Color(0, 0, 0, 0));
            ((JPanel) oldPane).setOpaque(false);
        }
        TitleBar bar = new TitleBar(title, showMinimize, showMaximize, onMaximize, onClose);
        DialogChrome chrome = new DialogChrome(bar, (JComponent) oldPane);
        if (w instanceof JFrame) {
            ((JFrame) w).setContentPane(chrome);
        } else {
            ((JDialog) w).setContentPane(chrome);
        }
        // 本窗不在主窗组件树里，applyDark 够不着：主题切换靠监听重绘 + 显式重设标题文字色。
        // 窗口 dispose（windowClosed）后自动摘除监听 —— 来电弹窗/通话窗每次打开都新建外壳，
        // 不摘的话 Ui.THEME_LISTENERS 会永久持有整棵 chrome 树（长期运行累积泄漏）。
        final DialogChrome chromeRef = chrome;
        final Runnable hook = new Runnable() {
            @Override public void run() {
                bar.titleLabel().setForeground(Ui.CHROME_FG);
                chromeRef.repaint();
            }
        };
        Ui.addThemeListener(hook);
        w.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                Ui.removeThemeListener(hook);
            }
        });
        return chrome;
    }

    /**
     * 圆角内容容器：白色圆角背景 + 标题栏。
     * 子组件绘制时用圆角矩形裁剪，保证不溢出圆角。
     */
    private static final class RoundedContent extends JPanel {
        RoundedContent(JComponent content, Runnable onClose) {
            super(new BorderLayout());
            setOpaque(false);
            add(new TitleBar(onClose), BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // 圆角内容区底色跟主题走（深色 = 纯黑，2026-09-27）；读动态字段，repaint 自动换色
            g2.setColor(Ui.SURFACE);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), RADIUS, RADIUS);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override protected void paintChildren(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // 同 DialogChrome：形状 AA 不能顺带把子组件文字变成抗锯齿（切主题整窗重绘时按钮文字发糊）
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), RADIUS, RADIUS));
            super.paintChildren(g2);
            // 外框细线：必须在子组件画完之后（否则被两侧栏/聊天区底盖住），
            // 且先解除圆角裁剪 —— 否则描边的抗锯齿外沿会被切掉半像素、看起来发虚
            g2.setClip(null);
            paintWindowBorder(g2, getWidth(), getHeight(), RADIUS);
            g2.dispose();
        }
    }

    /**
     * 自绘标题栏：图标 + 标题（左）+ 最小化/最大化/关闭（右），整条可拖动窗口。
     *
     * <p>两种用法：
     * <ul>
     *   <li>主窗（wrap 路径）：透明底，叠在 RoundedContent 的 SURFACE 圆角底上，
     *       文字用 TEXT_SUB（原行为保持）；</li>
     *   <li>弹窗/通话窗（install 路径）：自绘 CHROME_BG 深灰底（上两角圆角）、
     *       文字 CHROME_FG、高度用更矮的 DIALOG_TITLE_H。</li>
     * </ul>
     */
    private static final class TitleBar extends JPanel {
        private final JLabel titleLabel;
        private final boolean chromeBg;
        /** 最大化钮（showMaximize=false 时为 null）—— setMaximized 要拿到它换字形 */
        private final WinButton maxBtn;
        /** 自绘底的圆角半径，跟随 {@link DialogChrome#setCornerRadius(int)}（全屏 = 0） */
        private int corner = RADIUS;
        /** 已全屏/最大化时禁止拖动（与普通程序一致；满屏被拖走就是真 bug） */
        private boolean dragEnabled = true;

        /** 主窗路径（原行为）：固定标题「手机电脑互传」、带最小化、透明底 */
        TitleBar(Runnable onClose) {
            this("手机电脑互传", true, false, null, onClose, false);
        }

        /** 弹窗/通话窗路径：标题参数化；showMinimize=false 时只留关闭按钮；自绘 CHROME_BG 底 */
        TitleBar(String title, boolean showMinimize, boolean showMaximize,
                 Runnable onMaximize, Runnable onClose) {
            this(title, showMinimize, showMaximize, onMaximize, onClose, true);
        }

        private TitleBar(String title, boolean showMinimize, boolean showMaximize,
                         Runnable onMaximize, Runnable onClose, boolean chromeBg) {
            this.chromeBg = chromeBg;
            setLayout(new BorderLayout());
            int h = chromeBg ? DIALOG_TITLE_H : TITLE_H;
            setPreferredSize(new Dimension(100, h));
            setOpaque(false);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                    chromeBg ? Ui.CHROME_LINE : Ui.DIVIDER));

            // 左：小图标 + 标题（BoxLayout 水平排列 + 垂直居中）
            JPanel left = new JPanel();
            left.setLayout(new javax.swing.BoxLayout(left, javax.swing.BoxLayout.X_AXIS));
            left.setOpaque(false);
            left.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
            JLabel icon = new JLabel(new javax.swing.ImageIcon(AppIcon.render(16)));
            icon.setAlignmentY(CENTER_ALIGNMENT);
            titleLabel = new JLabel(title);
            // 档位：常规 15（=发送文件按钮同档）
            titleLabel.setFont(new Font(Ui.FONT, Font.PLAIN, Ui.FS_STD));
            titleLabel.setForeground(chromeBg ? Ui.CHROME_FG : Ui.TEXT_SUB);
            titleLabel.setAlignmentY(CENTER_ALIGNMENT);
            left.add(icon);
            left.add(javax.swing.Box.createHorizontalStrut(8));
            left.add(titleLabel);
            add(left, BorderLayout.WEST);

            // 右：最小化 / 最大化 / 关闭
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            if (showMinimize) {
                right.add(new WinButton(WinButton.Kind.MINIMIZE, h));
            }
            // 必须**先于** CLOSE add：FlowLayout(RIGHT) 按插入顺序从左往右排，
            // 这样最大化钮才紧邻 X 的左侧（普通 Windows 程序的排法）。
            if (showMaximize) {
                maxBtn = new WinButton(WinButton.Kind.MAXIMIZE, onMaximize, h);
                if (chromeBg) {
                    maxBtn.setOnChrome();
                }
                right.add(maxBtn);
            } else {
                maxBtn = null;
            }
            right.add(new WinButton(WinButton.Kind.CLOSE, onClose, h));
            add(right, BorderLayout.EAST);

            // 整条标题栏拖动窗口（按钮自身不拖，由它们消费鼠标事件）
            MouseAdapter drag = new MouseAdapter() {
                private Point start;
                @Override public void mousePressed(MouseEvent e) {
                    // 全屏/最大化态不可拖（与普通程序一致）：此时 setLocation 会把满屏窗
                    // 挪出屏，画面被切掉一半，而用户看不到任何恢复入口。
                    if (dragEnabled) {
                        start = e.getPoint();
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (start == null || !dragEnabled) return;
                    Window w = SwingUtilities.getWindowAncestor(TitleBar.this);
                    if (w != null) {
                        w.setLocation(w.getX() + e.getX() - start.x,
                                      w.getY() + e.getY() - start.y);
                    }
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
        }

        /** 改备注后同步标题文字（语音窗 setPeer 用） */
        void setTitle(String t) {
            titleLabel.setText(t);
        }

        /** 取标题 label（主题监听里显式重设文字色） */
        JLabel titleLabel() {
            return titleLabel;
        }

        /** 同步全屏态：锁/放标题栏拖动，并把字形交给最大化钮（没装该钮就只锁拖动） */
        void setMaximized(boolean maximized) {
            dragEnabled = !maximized;
            if (maxBtn != null) {
                maxBtn.setMaximized(maximized);
            }
        }

        /** 跟随外壳圆角（全屏 = 0）：满屏时标题栏顶角不能露圆弧缺口 */
        void setCorner(int r) {
            corner = r;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!chromeBg) {
                return; // 主窗：底由 RoundedContent 提供，保持透明
            }
            // 弹窗/通话窗：自绘 CHROME_BG 底。
            // 填充高度故意拉到 h+TITLE_H：下两角的圆弧被裁到标题栏外 → 可见下缘是直角，
            // 上两角圆弧与整窗四角圆角一致（否则 undecorated 窗顶角变直角）。
            // 半径读动态 corner：全屏时外壳已归零，这里继续用 RADIUS 会在顶角露圆弧缺口。
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Ui.CHROME_BG);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight() + TITLE_H,
                    corner, corner));
            g2.dispose();
        }
    }

    /** 窗口控制按钮：最小化 / 最大化（还原）/ 关闭。自己画字形（不靠字体，避免缺字渲染成豆腐块） */
    private static final class WinButton extends JComponent {
        enum Kind { MINIMIZE, MAXIMIZE, CLOSE }

        private final Kind kind;
        /** CLOSE = 关闭回调，MAXIMIZE = 放大/还原回调，MINIMIZE 忽略它（走系统 ICONIFIED） */
        private final Runnable onClick;
        private boolean hover;
        /** 仅 MAXIMIZE 用：true = 当前已是全屏/最大化态 ⇒ 画「还原」双框 */
        private boolean maximized;
        /** 底是 {@link Ui#CHROME_BG}（弹窗/通话窗标题栏）而非主窗内容底 ⇒ hover 另推一档 */
        private boolean onChrome;

        WinButton(Kind kind) { this(kind, null, TITLE_H); }
        WinButton(Kind kind, int h) { this(kind, null, h); }
        WinButton(Kind kind, Runnable onClose) { this(kind, onClose, TITLE_H); }
        WinButton(Kind kind, Runnable onClick, int h) {
            this.kind = kind;
            this.onClick = onClick;
            setPreferredSize(new Dimension(46, h));
            // 放大钮带 hover 提示（新文案一律英文，2026-09-16 定调）；字形之外再多一层「认得出」
            if (kind == Kind.MAXIMIZE) {
                setToolTipText("Fullscreen");
            }
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (kind == Kind.CLOSE || kind == Kind.MAXIMIZE) {
                        if (onClick != null) onClick.run();
                        return;
                    }
                    Window w = SwingUtilities.getWindowAncestor(WinButton.this);
                    if (w instanceof JFrame) {
                        ((JFrame) w).setState(JFrame.ICONIFIED);
                    }
                }
            });
        }

        /** 切全屏后由 {@link TitleBar#setMaximized(boolean)} 调，只换字形/提示不改行为 */
        void setMaximized(boolean maximized) {
            this.maximized = maximized;
            if (kind == Kind.MAXIMIZE) {
                setToolTipText(maximized ? "Restore" : "Fullscreen");
            }
            repaint();
        }

        /**
         * 该钮画在自绘标题栏底（{@link Ui#CHROME_BG}）上：hover 底色要从底色的明暗推一档。
         *
         * <p>为什么不能用主窗那档固定浅灰 0xF0F1F4：浅色主题下 CHROME_BG **就等于** 0xF0F1F4
         * ⇒ hover 一点反馈都没有；深色主题下它是一块刺眼的近白。两档都不像「按钮在响应鼠标」，
         * 而 hover 无反馈正是用户报告「鼠标移上去也没有」的成因之一。
         */
        void setOnChrome() {
            onChrome = true;
        }

        /** 按底色明暗反方向挪一档：浅底加深、深底提亮（阈值 128 即可，两主题都离中性远） */
        private static Color chromeHoverBg() {
            Color bg = Ui.CHROME_BG;
            int d = bg.getRed() > 128 ? -18 : 22;
            return new Color(clamp255(bg.getRed() + d),
                             clamp255(bg.getGreen() + d),
                             clamp255(bg.getBlue() + d));
        }

        private static int clamp255(int v) {
            return v < 0 ? 0 : (v > 255 ? 255 : v);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            // hover 背景：关闭=红；画在标题栏底上的按底色推一档；其余=浅灰
            if (hover) {
                g2.setColor(kind == Kind.CLOSE
                        ? new Color(0xE8, 0x11, 0x23)
                        : (onChrome ? chromeHoverBg() : new Color(0xF0, 0xF1, 0xF4)));
                g2.fillRect(0, 0, w, h);
            }

            // 字形颜色
            g2.setColor(hover && kind == Kind.CLOSE
                    ? Color.WHITE
                    : Ui.TEXT_SUB);

            int cx = w / 2, cy = h / 2;
            if (kind == Kind.MINIMIZE) {
                // 一条横线
                g2.fillRect(cx - 6, cy - 1, 12, 1);
            } else if (kind == Kind.MAXIMIZE) {
                // 照 Windows 11 的「放大/还原」字形画：边长 10.5 的**圆角**方块、描边 1.2px、
                // 还原态两框沿对角错开 2.5px（用户 2026-10-03 给的系统截图就是这个样式）。
                // 这里**开**形状 AA：圆角关 AA 会锯齿成阶梯；1px 直线的发虚问题只有方角直角框才有，
                // 圆角描边本来就要靠 AA 才顺。
                final float s = 10.5f, arc = 3f, off = 2.5f;
                g2.setStroke(new BasicStroke(1.2f));
                // 未最大化 = 居中的单框；还原 = 前框往左下、后框往右上各错 off/2（两框合起来仍居中）
                float fx = cx - (maximized ? off / 2 : 0f) - s / 2;
                float fy = cy + (maximized ? off / 2 : 0f) - s / 2;
                Shape front = new RoundRectangle2D.Float(fx, fy, s, s, arc, arc);
                if (maximized) {
                    // 后框只画"没被前框盖住"的那几段：描边形状减去前框区域（含圆角），
                    // 否则后框的下边/左边会从两个方框中间的缝里露出来。
                    Shape backStroke = new BasicStroke(1.2f).createStrokedShape(
                            new RoundRectangle2D.Float(fx + off, fy - off, s, s, arc, arc));
                    Area back = new Area(backStroke);
                    back.subtract(new Area(front));
                    g2.fill(back);
                }
                g2.draw(front);
            } else {
                // ✕ 两条交叉线（stroke 1px）
                int s = 5;
                g2.drawLine(cx - s, cy - s, cx + s, cy + s);
                g2.drawLine(cx + s, cy - s, cx - s, cy + s);
            }
            g2.dispose();
        }
    }
}
