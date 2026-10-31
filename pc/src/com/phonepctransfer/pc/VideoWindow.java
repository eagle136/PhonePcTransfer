package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * PC 端视频窗口（**只显示，不采集**）。
 *
 * 职责：把 {@link FfmpegDecoder} 解出来的 BGRA 帧按"适应窗口 + letterbox + 旋转"画出来，
 * 并提供 [静音] [挂断] 浮动圆钮 + 标题栏右上角的 [放大/还原]（双击画面同样切换）。
 *
 * <h3>三处关键设计</h3>
 * <ol>
 *   <li><b>最新帧优先</b>：解码线程调用 {@link #onFrame}，若上一次重绘还没被 EDT 执行完，
 *       就直接丢弃本帧（不排队）——视频通话的正确取舍，排队只会让延迟无限增长。</li>
 *   <li><b>双缓冲 + 原子交换</b>：解码线程只写 back 图，画的时候只读 front 图，
 *       交换在锁内做 → 不会画出半帧（撕裂）。</li>
 *   <li><b>旋转在绘制侧做</b>：协议里的 {@code rot} 只是元数据，解码出来的仍是采集方向的
 *       横屏图；这里用 {@code Graphics2D.rotate} 旋转后再重新 letterbox，
 *       因此旋转时窗口比例变化也能自适应。<b>最终角 = 对端基线 + 本机手调角</b>
 *      （后者由画面上的旋转钮改，按设备记在 {@code Store.rotRemote}）。</li>
 * </ol>
 *
 * <p>注意：PC 端**禁止 emoji**（YaHei 会渲染成方框）；既有按钮保持中文文本
 *（2026-09-16 定调：**本轮新加的文案一律英文**，不再新增中文）。
 */
public final class VideoWindow {

    /** 窗口上的动作回调（都发生在 EDT 上） */
    public interface Actions {
        /** 点「挂断」或直接关窗 */
        void onHangup();

        /** 点「静音」，参数是切换后的静音状态 */
        void onToggleMute(boolean muted);

        /**
         * 点「旋转」：参数是叠加后的**本机手调角**（0/90/180/270）。
         * 外部负责按设备持久化（见 {@code Store.setRotRemoteOf}）。
         */
        default void onRotate(int deg) {
        }
    }

    private final JFrame frame;
    private final VideoPanel panel;
    /**
     * 画面上的浮动圆钮：静音（左下）/ 旋转（挂断左侧）/ 挂断（下中，红色大圆）。
     *
     * 全部按钮**默认隐藏**，鼠标移进窗口才出现（[poke]），停 5s 又自动隐藏
     *（2026-09-16 用户定调：「鼠标移到窗口内才显示」）。
     *
     * <p><b>全屏钮不在这里</b>（2026-10-03）：原来它是一个默认隐藏的右下圆钮，
     * 用户三轮报告「视频窗上没有全屏按钮，鼠标移上去也没有」⇒ 改到标题栏右上角，
     * 与普通程序一样常驻可见；画面上的入口改为**双击切换**（对应用户「双击没反应」）。
     */
    private final JButton muteBtn;
    private final JButton rotBtn;
    private final JButton hangBtn;
    /** 控件自动隐藏定时器：5s 无鼠标活动隐藏全部按钮，动一下鼠标/点一下重新显示 */
    private javax.swing.Timer hideTimer;

    private Actions actions;
    private boolean muted = false;

    /** 播放画面（解码线程写、EDT 读；交换在 imgLock 内） */
    private final Object imgLock = new Object();
    private BufferedImage front;
    private BufferedImage back;
    private int[] pxBuf;
    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    /** 画面盒监听（由 CallManager 挂到解码器上，EDT 调用） */
    private volatile CallManager.BoxListener boxListener;
    /** 上一次报出去的盒（去重：paintComponent 每次重绘都会试着报，尺寸没变就是两次整数比较） */
    private int lastPubW = -1;
    private int lastPubH = -1;

    /** 对端给的旋转基线：0/90/180/270，来自 video_cfg.rot（每次通话重新下发） */
    private volatile int rotation = 0;
    /**
     * **用户手动叠加**的旋转角：0/90/180/270，按设备持久化（Store.rotRemote）。
     *
     * 与基线分开记的原因：基线是对端采集参数推导出来的（`video_cfg.rot`），每次通话、
     * 每次对端重启采集都可能变；用户手调的是「我这台机器上想看正的角度」，与基线无关。
     * 渲染时取 `(rotation + userRot) % 360`。
     */
    private volatile int userRot = 0;
    private volatile String status = "正在连接视频…";

    private boolean fullscreen = false;
    private java.awt.Rectangle savedBounds;
    /** 自绘外壳（标题栏 + 圆角底）；进/退全屏时圆角归零/恢复、标题栏钮换「放大 ↔ 还原」 */
    private final WindowChrome.DialogChrome chrome;
    /** 进全屏前的窗口背景（退出时恢复；支持透明的设备上为透明） */
    private Color savedBg;

    private VideoWindow(Window owner, String peerLabel) {
        String title = "视频通话 - " + (peerLabel == null || peerLabel.isEmpty() ? "对端" : peerLabel);
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(360, 260));
        // 自绘标题栏：去原生装饰（硬约束：setVisible 之前）
        frame.setUndecorated(true);

        panel = new VideoPanel();
        frame.add(panel, BorderLayout.CENTER);

        // ===== 底部状态条：只放状态文字；操作按钮改为画面浮动圆钮（与 Android 端同构） =====
        JPanel bar = new JPanel();
        bar.setLayout(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        bar.setBackground(new Color(28, 30, 34));

        // 左侧状态文字（分辨率 / 帧率 / 码率 / 延迟）
        javax.swing.JLabel st = new javax.swing.JLabel(status);
        st.setForeground(new Color(200, 205, 212));
        // 档位：常规 15（=发送文件按钮同档）
        st.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
        panel.statusLabel = st;
        bar.add(st, BorderLayout.WEST);
        frame.add(bar, BorderLayout.SOUTH);

        // 自绘 chrome（必须在 setVisible 之前）：把整个内容面板（含 panel + 状态条）
        // 包进圆角外壳 + 深灰标题栏；X = 挂断（与 windowClosing 同语义）。
        // 第 3 参 = 最小化钮（2026-10-30 用户定调）；第 4/5 参 = 标题栏右上角常驻
        // 「放大/还原」钮，动作就是切全屏（2026-10-03）。
        chrome = WindowChrome.install(frame, title, true, true, this::toggleFullscreen, () -> {
            Actions a = actions;
            if (a != null) {
                a.onHangup();
            } else {
                close();
            }
        });

        // ===== 画面浮动控件（默认隐藏，鼠标移进窗口才显示，2026-09-16 用户定调） =====
        Color ctrlBg = new Color(0, 0, 0, 115); // 半透明黑
        muteBtn = CallIcons.circleButton(CallIcons.speakerOn(24), ctrlBg, "静音", 44);
        // 旋转钮：手调本机看到的画面角度，按设备记住（用户定调「电脑端新加的部分不要用中文」）
        rotBtn = CallIcons.circleButton(CallIcons.rotate(22), ctrlBg, "Rotate", 44);
        hangBtn = CallIcons.circleButton(CallIcons.hangup(26),
                new Color(0xD3, 0x2F, 0x2F), "挂断", 60);
        panel.setLayout(null);
        panel.add(muteBtn);
        panel.add(rotBtn);
        panel.add(hangBtn);

        // 任何鼠标活动都让控件重新显示并重新计时（点在按钮上同样算活动）
        java.awt.event.MouseAdapter pokeListener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                // 「鼠标移到窗口内才显示」：进窗口这一下也当活动（不移动也会触发）
                poke();
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                poke();
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                poke();
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                poke();
            }
        };
        panel.addMouseListener(pokeListener);
        panel.addMouseMotionListener(pokeListener);
        panel.addMouseWheelListener(pokeListener);
        muteBtn.addMouseListener(pokeListener);
        muteBtn.addMouseMotionListener(pokeListener);
        rotBtn.addMouseListener(pokeListener);
        rotBtn.addMouseMotionListener(pokeListener);
        hangBtn.addMouseListener(pokeListener);
        hangBtn.addMouseMotionListener(pokeListener);

        // 双击画面 = 切全屏/还原（2026-10-03 用户定调：「双击没反应」）。
        // 只挂在 panel 上：三个浮动圆钮是独立子组件，它们自己消费点击，不会连带切换。
        panel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleFullscreen();
                }
            }
        });

        muteBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                muted = !muted;
                muteBtn.setIcon(muted ? CallIcons.speakerOff(24) : CallIcons.speakerOn(24));
                muteBtn.setToolTipText(muted ? "取消静音" : "静音");
                Actions a = actions;
                if (a != null) {
                    a.onToggleMute(muted);
                }
            }
        });
        hangBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Actions a = actions;
                if (a != null) {
                    a.onHangup();
                }
            }
        });

        rotBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rotateByUser();
            }
        });

        // 5s 无活动隐藏控件
        hideTimer = new javax.swing.Timer(5000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideControls();
            }
        });
        hideTimer.start();
        // 控件**默认隐藏**：鼠标移进窗口（mouseEntered / 任何移动）才出现
        hideControls();

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // 关窗 = 挂断（窗口只在通话期间存在）
                Actions a = actions;
                if (a != null) {
                    a.onHangup();
                } else {
                    close();
                }
            }
        });

        // ESC：全屏时退出全屏；非全屏时不做任何事（避免误挂断）
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pp-exit-fs");
        frame.getRootPane().getActionMap().put("pp-exit-fs", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (fullscreen) {
                    toggleFullscreen();
                }
            }
        });

        // 高 = 内容 500 + 标题栏 32
        frame.setSize(760, 500 + WindowChrome.DIALOG_TITLE_H);
        if (owner != null) {
            frame.setLocationRelativeTo(owner);
        } else {
            frame.setLocationByPlatform(true);
        }
    }

    private static JButton mkButton(String text) {
        JButton b = new JButton(text);
        // 档位：常规 15（=发送文件按钮同档）
        b.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
        b.setFocusPainted(false);
        return b;
    }

    /** 鼠标有活动：显示全部控件并重新计时（5s 后隐藏） */
    private void poke() {
        muteBtn.setVisible(true);
        rotBtn.setVisible(true);
        hangBtn.setVisible(true);
        if (hideTimer != null) {
            hideTimer.restart();
        }
    }

    /** 隐藏全部浮动控件（窗口刚打开时 / 5s 无鼠标活动） */
    private void hideControls() {
        muteBtn.setVisible(false);
        rotBtn.setVisible(false);
        hangBtn.setVisible(false);
    }

    /**
     * 点「旋转」：叠加 90° 并立即重绘，同时回调外部**按设备**持久化。
     *
     * 渲染用的是 `(对端基线 + 本机手调角) % 360`；手调角按设备记在 `Store.rotRemote`，
     * 下次和这台设备通话时自动装回（用户定调：只要不卸载客户端就一直沿用）。
     */
    private void rotateByUser() {
        int next = (userRot + 90) % 360;
        userRot = next;
        panel.repaint();
        Actions a = actions;
        if (a != null) {
            a.onRotate(next);
        }
    }

    /** 装回本机手调的旋转角（打开窗口时由外部按设备读取）。可任意线程调用。 */
    public void setUserRotation(int deg) {
        int d = ((deg % 360) + 360) % 360;
        if (d != 0 && d != 90 && d != 180 && d != 270) {
            d = 0;
        }
        userRot = d;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                panel.repaint();
            }
        });
    }

    /** 当前本机手调的旋转角（0/90/180/270） */
    public int userRotation() {
        return userRot;
    }

    /** 打开视频窗口（**必须在 EDT 上调用**） */
    public static VideoWindow open(Window owner, String peerLabel) {
        VideoWindow w = new VideoWindow(owner, peerLabel);
        w.frame.setVisible(true);
        return w;
    }

    public void setActions(Actions a) {
        this.actions = a;
    }

    /** 挂画面盒监听：挂上即按当前布局补报一次。可任意线程调用。 */
    public void setBoxListener(CallManager.BoxListener l) {
        boxListener = l;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                publishBox();
            }
        });
    }

    /**
     * 把「画面能用的像素盒」报给解码器（EDT）。
     *
     * ⚠ 报的是**未旋转轴**：解码器收到的是未旋转的源尺寸，它按 ≤1 等比往盒子里适配；
     * 而 90/270° 时画面在窗口里占的是互换后的那条边，所以这里先把盒子轴互换再报。
     */
    private void publishBox() {
        CallManager.BoxListener l = boxListener;
        if (l == null) {
            return;
        }
        int pw = panel.getWidth();
        int ph = panel.getHeight();
        if (pw <= 0 || ph <= 0) {
            return;   // 还没布局：下一次重绘会再报
        }
        int rot = (rotation + userRot) % 360;
        boolean swap = rot == 90 || rot == 270;
        int bw = swap ? ph : pw;
        int bh = swap ? pw : ph;
        if (bw == lastPubW && bh == lastPubH) {
            return;
        }
        lastPubW = bw;
        lastPubH = bh;
        l.onBox(bw, bh);
    }

    public boolean isVisible() {
        return frame.isVisible();
    }

    public JFrame frame() {
        return frame;
    }

    /** 设置旋转（video_cfg.rot）。可任意线程调用。 */
    public void setRotation(int rot) {
        int r = ((rot % 360) + 360) % 360;
        if (r != 0 && r != 90 && r != 180 && r != 270) {
            r = 0;
        }
        if (r == rotation) {
            return;
        }
        rotation = r;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                panel.repaint();
            }
        });
    }

    /** 更新底部状态文字。可任意线程调用。 */
    public void setStatus(String s) {
        status = s == null ? "" : s;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (panel.statusLabel != null) {
                    panel.statusLabel.setText(status);
                }
            }
        });
    }

    /**
     * 投递一帧（BGRA）。**在解码线程上调用** —— 内部只做像素格式转换 + 触发重绘，
     * 不阻塞、不排队；EDT 来不及就丢帧（见类注释「最新帧优先」）。
     */
    public void onFrame(byte[] bgra, int w, int h) {
        if (bgra == null || w <= 0 || h <= 0) {
            return;
        }
        int need = w * h * 4;
        if (bgra.length < need) {
            return;
        }
        // 上一次重绘还没轮到 → 直接丢本帧
        if (!repaintPending.compareAndSet(false, true)) {
            return;
        }
        try {
            if (back == null || back.getWidth() != w || back.getHeight() != h) {
                back = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                pxBuf = new int[w * h];
            }
            int[] px = pxBuf;
            int n = w * h;
            for (int i = 0, j = 0; i < n; i++, j += 4) {
                // 实测像素序为 B,G,R,A（见 VIDEO_CALL_PLAN §15.2）
                int b = bgra[j] & 0xFF;
                int g = bgra[j + 1] & 0xFF;
                int r = bgra[j + 2] & 0xFF;
                int a = bgra[j + 3] & 0xFF;
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            back.setRGB(0, 0, w, h, px, 0, w);
            synchronized (imgLock) {
                BufferedImage t = front;
                front = back;
                back = t;
            }
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    repaintPending.set(false);
                    panel.repaint();
                }
            });
        }
    }

    /** 清空画面（回到"等待画面"占位态） */
    public void clearFrame() {
        synchronized (imgLock) {
            front = null;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                panel.repaint();
            }
        });
    }

    public void close() {
        // 先摘回调，防止 dispose 触发的 windowClosing 再回调一次 onHangup（死循环）
        actions = null;
        // 停掉控件自动隐藏定时器：repeats Timer 被静态 TimerQueue 持有，
        // 匿名回调又持 VideoWindow.this → 不停则 dispose 后整棵窗口树永久驻留
        if (hideTimer != null) {
            hideTimer.stop();
            hideTimer = null;
        }
        if (fullscreen) {
            exitFullscreen();
        }
        frame.setVisible(false);
        frame.dispose();
        synchronized (imgLock) {
            front = null;
            back = null;
        }
    }

    // ==================== 全屏 ====================

    private void toggleFullscreen() {
        if (fullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        savedBounds = frame.getBounds();
        // 全屏/最大化 = 满屏矩形：圆角归零、背景改不透明（否则四角透出桌面）；
        // 「不支持全屏 → 改为最大化」分支同样要走这套（否则那条路径四角透出桌面）。
        // 标题栏**不再隐藏**（2026-10-03）：它就是「放大/还原」钮的落点，一隐藏全屏里
        // 就没有可见出口了（画面右下那个浮动圆钮三轮都被用户报告「看不见」）。
        chrome.setCornerRadius(0);
        chrome.setMaximized(true);
        savedBg = frame.getBackground();
        frame.setBackground(Color.BLACK);
        if (!dev.isFullScreenSupported()) {
            Log.w("当前显示设备不支持全屏，改为最大化");
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            fullscreen = true;
            return;
        }
        if (!frame.isUndecorated()) {   // 正常已在构造时 undecorated；保护性分支
            frame.dispose();
            frame.setUndecorated(true);
        }
        dev.setFullScreenWindow(frame);
        frame.setVisible(true);
        fullscreen = true;
        panel.requestFocusInWindow();
    }

    private void exitFullscreen() {
        GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        try {
            dev.setFullScreenWindow(null);
        } catch (Exception ignore) {
            // 忽略：部分驱动在非全屏状态下抛异常
        }
        frame.dispose();
        // 注意：这里**不能** setUndecorated(false)——那会把原生白色标题栏请回来
        //（R3 在"全屏进出一次之后"复现）；只恢复圆角/透明 + 钮字形
        chrome.setCornerRadius(WindowChrome.RADIUS);
        chrome.setMaximized(false);
        if (savedBg != null) {
            frame.setBackground(savedBg);
        }
        if (savedBounds != null) {
            frame.setBounds(savedBounds);
        }
        frame.setVisible(true);
        fullscreen = false;
    }

    // ==================== 绘制面板 ====================

    private final class VideoPanel extends JPanel {

        javax.swing.JLabel statusLabel;

        VideoPanel() {
            setBackground(Color.BLACK);
            setOpaque(true);
        }

        /**
         * 浮动圆钮手动定位（null 布局）：静音左下、**旋转在挂断左侧**、挂断下中；
         * 底部一行统一 `h - 16 - 高度`，随窗口尺寸重排。
         */
        @Override
        public void doLayout() {
            super.doLayout();
            if (muteBtn == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            int gap = 16;
            muteBtn.setBounds(gap, h - gap - muteBtn.getHeight(),
                    muteBtn.getWidth(), muteBtn.getHeight());
            int hx = (w - hangBtn.getWidth()) / 2;
            hangBtn.setBounds(hx, h - gap - hangBtn.getHeight(),
                    hangBtn.getWidth(), hangBtn.getHeight());
            // 与挂断同一行、左侧留 16px；窗口很窄时也不会越界（x 最小为 0 由 Swing 兜住）
            rotBtn.setBounds(Math.max(gap, hx - gap - rotBtn.getWidth()),
                    h - gap - rotBtn.getHeight(),
                    rotBtn.getWidth(), rotBtn.getHeight());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            publishBox();
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());

                BufferedImage img;
                synchronized (imgLock) {
                    img = front;
                }
                if (img == null) {
                    drawPlaceholder(g);
                    return;
                }
                int pw = getWidth();
                int ph = getHeight();
                // 最终角 = 对端给的基线 + 本机手调角（手调角按设备持久化）
                int rot = (rotation + userRot) % 360;
                boolean swap = (rot == 90 || rot == 270);
                int cw = swap ? img.getHeight() : img.getWidth();
                int ch = swap ? img.getWidth() : img.getHeight();
                double scale = Math.min((double) pw / cw, (double) ph / ch);
                g.translate(pw / 2.0, ph / 2.0);
                if (rot != 0) {
                    g.rotate(Math.toRadians(rot));
                }
                g.scale(scale, scale);
                g.drawImage(img, -img.getWidth() / 2, -img.getHeight() / 2, null);
            } finally {
                g.dispose();
            }
        }

        private void drawPlaceholder(Graphics2D g) {
            String s = status == null || status.isEmpty() ? "等待对方画面…" : status;
            g.setColor(new Color(170, 175, 182));
            // 档位：常规 15（=发送文件按钮同档）
            g.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(s)) / 2;
            int y = getHeight() / 2;
            g.drawString(s, Math.max(8, x), y);
        }
    }
}
