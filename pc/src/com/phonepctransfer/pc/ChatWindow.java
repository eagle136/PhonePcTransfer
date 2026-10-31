package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Swing 聊天窗口：**左侧设备列表 + 右侧聊天**。
 *  - 设备列表：**单击某项 = 切为当前对话设备**（没连才发起一次连接，失败不自动重试，
 *    再点一次即可）；已连接的多台会各自亮绿点，互不影响。**右键**可改备注/IP 或删除
 *  - 聊天区只显示**当前对话设备**的往来 —— 归属按消息自带的 peerIp 判定，
 *    A→B 的内容不会出现在 C 的记录里
 *  - 消息：自己发的靠右、收到的靠左
 *  - 图片文件显示缩略图，点击全屏查看（双击 1:1、拖动平移、右上角关闭）
 *  - 自己发出且尚未完成的文件可「取消发送」（两端同步取消、都不显示）
 *  - **文件/文件夹可直接拖进窗口**：弹确认框 → 确认后发到当前对话设备（见 FileDrop）
 *
 * 关窗 = 隐藏到托盘（后台继续收发）。
 */
public final class ChatWindow {

    static final int BUBBLE_MAX_W = 430;
    static final int DEVICE_PANEL_W = 200;
    /** UI 字体族：统一走 {@link Fonts#FAMILY}（优先宋体，缺失回退系统默认） */
    static final String UI_FONT = Fonts.FAMILY;

    /** 顶栏左右两侧的固定占位宽度（px）：两边等宽，中间「当前地址 ▼」才是窗口正中 */
    private static final int SIDE_SLOT = 80;

    final Link link;
    final Store store;

    private CallManager call;
    /** 语音通话独立窗口（只在通话期间存在；视频通话走 VideoWindow，不经这里） */
    private VoiceCallWindow callWindow;
    /** 通话窗口刷新定时器（500ms：时长/状态；空闲时停止） */
    private javax.swing.Timer callTicker;

    final JFrame frame = new JFrame("手机电脑互传 — PC 端");
    final ChatBubbles bubbles = new ChatBubbles(this);
    final ChatDevices devices = new ChatDevices(this);

    public void refreshDevices() { devices.refreshDevices(); }

    void setDeviceState(String text) { devices.setDeviceState(text); }
    final JPanel msgPanel = new JPanel();
    final JScrollPane scroll;
    /** 顶栏居中第 1 行：**当前对话设备**（备注优先，没有备注就显示 IP） */
    private final JLabel remarkLabel = new JLabel(" ");
    /** 顶栏居中第 2 行：连接状态（● 已连接 / ○ 未连接…），已连接时用绿字 */
    private final JLabel stateLine = new JLabel(" ");
    /**
     * ⚠️ **已不显示在任何界面上**（2026-09-21）：左栏底部改成「只显示接收目录」一行后，
     * 这两条状态文案没有落点了。字段与 setSub/setClipBuffer 的调用点全部保留 ——
     * 它们只是写进一个不在组件树里的 JLabel（无副作用、不会 NPE），
     * 将来想把瞬时提示接回某个位置（顶栏第 3 行 / 状态栏）时，改这一处即可。
     */
    final JLabel subLabel = new JLabel(" ");
    final JLabel clipLabel = new JLabel("剪贴板缓冲：（空）");
    /** 顶栏中段：**当前连接地址 + ▼**（挨在一起的一个紧凑整体，居中的就是它） */
    private final JButton addrBtn = new JButton("");
    /** 顶栏中段容器（BoxLayout Y_AXIS）：refreshHeader 改文案后要清它的尺寸缓存（见 refreshHeader 注释） */
    private final JPanel headerMid = new JPanel();
    /** headerMid 的 BoxLayout 引用（buildHeader 里赋值），供 refreshHeader 调 invalidateLayout */
    private BoxLayout headerMidLayout;
    /** 底部输入框（包内可见：ChatDevices.toggleTheme 切主题后要重套配色） */
    final JTextField input = new JTextField();


    /** 记录每条消息对应的组件，便于原地更新状态/进度/移除 */
    final List<String> mids = new ArrayList<String>();
    final List<JComponent> comps = new ArrayList<JComponent>();
    /** mid → 消息本体（v2.7：重试成功要把这条挪到最末并刷新 ts，得拿得到原对象） */
    final java.util.Map<String, Msg> bubbleMsgOf = new java.util.HashMap<String, Msg>();

    /** 上次发送文件所在的目录（下次打开选择器直接落在这里） */
    private File lastSendDir;

    /**
     * 聊天区当前展示的对端 IP。
     *
     * 与 {@code link.selectedIp()} 含义相同（点击设备时两者一起切），单独留一份是为了
     * 在不改动 Link 的前提下判断「这条消息该不该画进聊天区」—— 归属一律按消息自带的
     * {@code peerIp} 比对，A→B 的内容永远不会落到 C 的界面里。
     */
    volatile String shownIp = "";

    /** 聊天区当前展示的对端 IP（供 FileDrop 等取「界面当前对话」做发送目标） */
    public String shownIp() {
        return shownIp == null ? "" : shownIp;
    }

    public ChatWindow(Link link, Store store) {
        this.link = link;
        this.store = store;

        // 2026-09-28 消息区「三层叠加」契约（与 ChatBubbles.RoundedPanel 的改动配套，行为不变，
        // 这里只是把契约钉死在代码里，避免以后有人顺手把某层改成透明/重排导致圆角卡片漏底）：
        //   第 1 层（最下）= 消息框背景：msgPanel 不透明 + Ui.PAGE_BG（即本处两行）。它是整片
        //     聊天区的底色，也是圆角卡片**四角透明处**透出来的东西。
        //   第 2 层（中）= 气泡圆角卡片本体：ChatBubbles.RoundedPanel.paintComponent 里用
        //     透明 PNG 贴图 CardSkin.paint(...) 画（不透明 JPanel 才有意义，见第 1 层）。
        //   第 3 层（上）= 文本/内容：气泡内的 JTextArea / JLabel / 进度条，由 paintChildren
        //     在卡片绘制**之后**画（Swing 固定顺序 paint → paintBorder → paintChildren），
        //     所以内容永远压在卡片上，不需要调用方做 z 序处理。
        // 显式写 setOpaque(true)：JPanel 默认本就是 true，写出来只是把契约钉死（第 2 层的
        // 圆角透明像素依赖它来透出第 1 层底色）。
        msgPanel.setOpaque(true);
        msgPanel.setLayout(new BoxLayout(msgPanel, BoxLayout.Y_AXIS));
        msgPanel.setBackground(Ui.PAGE_BG);
        msgPanel.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));

        scroll = new JScrollPane(msgPanel);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // 滚动区默认边框是 LAF 的浅色细线，深色主题下是一圈刺眼的白框（2026-09-27 离屏渲染实测）
        // → 显式换成主题分割线（MatteBorder：applyDark 的重着色遍历认得它，切主题跟着换色）
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Ui.DIVIDER));
        // 自定义滚动条（R4）：黑轨道 / 白箭头 / 绿滑块，条宽 12（Q6 定稿）
        Ui.installScrollBar(scroll, 12);

        // -- Modern window chrome: undecorated + rounded + shadow + custom titlebar (see WindowChrome) --
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0));
        // contentPane must be non-opaque too, otherwise it paints an opaque rect over the transparent corners
        ((JPanel) frame.getContentPane()).setOpaque(false);

        JPanel content = new JPanel(new BorderLayout());
        content.add(devices.buildDevicePanel(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.add(buildHeader(), BorderLayout.NORTH);
        main.add(scroll, BorderLayout.CENTER);
        main.add(buildFooter(), BorderLayout.SOUTH);
        content.add(main, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(WindowChrome.wrap(frame, content, () -> frame.setVisible(false)),
                BorderLayout.CENTER);

        frame.setSize(920 + WindowChrome.SHADOW * 2,
                      660 + WindowChrome.TITLE_H + WindowChrome.SHADOW * 2);
        frame.setMinimumSize(new Dimension(640 + WindowChrome.SHADOW * 2,
                                          420 + WindowChrome.TITLE_H + WindowChrome.SHADOW * 2));
        frame.setLocationByPlatform(true);
        // 窗口/任务栏图标 = 与安卓端同源的图标（多尺寸一起给，Windows 按需挑，缩放栏才不会糊）
        frame.setIconImages(AppIcon.renderSet());
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                frame.setVisible(false);
            }
        });

        reloadHistory();
        refreshDevices();

        // 网卡地址会随 WiFi / 网线开关变化（笔记本换网段是常事）→ 每 5s 对一次；
        // 内容没变 refreshLocalIps 会直接返回（不重建、不重绘）。javax.swing.Timer 回调跑在 EDT。
        // 顺带对一次「接收目录」：设置页改完目录后左栏底部那行要跟着变（没变则同样直接返回）。
        javax.swing.Timer ipTicker = new javax.swing.Timer(5000, e -> {
            devices.refreshLocalIps();
            devices.refreshSaveDir();
        });
        ipTicker.setRepeats(true);
        ipTicker.start();

        // 拖放接收：根面板覆盖整窗（聊天区/设备列表/状态栏），输入框单独包一层
        //（JTextField 自带处理器会把拖入的文件路径插成文本、还要管自己的复制粘贴，见 FileDrop 注释）
        FileDrop.install(frame.getRootPane(), frame, link, this);
        FileDrop.installWithFallback(input, frame, link, this);
    }



    /**
     * 当前显示地址的**组键快照**（见 [reopenConversationIfKeyChanged]）。
     */
    private String shownKey = "";

    /**
     * 身份被识别后，如果当前会话的**组键变了**，重新打开这台设备的聊天界面。
     *
     * 为什么需要：设备身份是**握手 / 收到广播时才知道**的。用户先看到的可能是一条以 IP 当
     * 组键的临时行（手工添加的地址）；一旦对端自报 deviceId，这行就被并进本机早已认识的
     * 那台设备 —— 组键从 `IP` 变成 `deviceId`。组键一变，历史文件（`<deviceId>.jsonl`）与
     * 待发队列读的都是**另一份**了：不重载就会停在旧组键读出来的空列表（或旧记录）上，
     * 看着像「聊天记录丢了」（2026-09-16 与安卓端同修）。
     *
     * 这里只在**真的换了身份**时重载；首次装载（快照为空）交给各自的选中流程，避免重复读盘。
     */
    void reopenConversationIfKeyChanged() {
        String ip = shownIp;
        if (ip == null || ip.isEmpty()) {
            shownKey = "";
            return;
        }
        String k = convKey(ip);
        if (k.equals(shownKey)) {
            return;
        }
        boolean firstLoad = shownKey.isEmpty();
        shownKey = k;
        if (firstLoad) {
            return;
        }
        reloadHistory();
        if (store != null) {
            Store.Group g = currentGroup();
            if (g != null) {
                // 新提示用英文（用户定调：电脑端新加的部分不要用中文）
                setSub("Merged into \"" + g.displayName() + "\"");
            }
        }
    }

    /**
     * 切换当前对话设备：设为目标、按需连接一次、重载该设备的历史。
     *
     * 只动这一台 —— 其他设备的会话与聊天记录完全不受影响。
     * 同时记住「这台设备当前用哪个地址」（组当前地址，供顶栏地址菜单回显）。
     */
    public void switchConversation(String ip, String label) {
        if (ip == null || ip.trim().isEmpty()) {
            return;
        }
        String v = ip.trim();
        shownIp = v;
        if (store != null) {
            store.setGroupCurIp(store.convKeyOf(v), v);
        }
        link.selectAndConnect(v, label);
        // 记住用户手动选中的设备，供下次启动扫描后自动连接一次（与安卓端 lastSelectedIp 对齐）
        store.setLastIp(v);
        reloadHistory();
        refreshDevices();
    }

    /**
     * 同设备多地址回落（2026-09-19）：请求的地址连不上、同一台设备的另一个地址连上了。
     *
     * 把「当前对话地址」跟到真正连上的那个地址 —— 否则顶栏与地址菜单回显的还是那个
     * 不通的地址，看着像「连上了又没连上」。EDT 调用（由 PcMain 转过来）。
     */
    void onGroupFallback(String requested, String actual) {
        if (actual == null || actual.isEmpty()) {
            return;
        }
        if (shownIp == null || shownIp.isEmpty() || shownIp.equals(requested)) {
            shownIp = actual;
            if (store != null) {
                store.setGroupCurIp(store.convKeyOf(actual), actual);
                store.setLastIp(actual);
            }
            refreshDevices();
            setSub("Connected via " + actual + " (another address of the same device)");
        }
    }

    /**
     * 点击「同 IP 的另一台设备」条目（{@link Store.Group#alt}）：先确认 IP 冲突，再切过去。
     *
     * <p>同一个 IP 上出现两台设备时（2026-10-05），本端只认**先来的那台**当活动设备
     * （Store 的活动设备 / 组键），后来者只是列表里的别名行。用户点别名行 = 想改用那台，
     * 但该地址的会话还握在别人手里 —— 贸然切换会变成「界面显示 A、收发其实给 B」。
     * 所以先弹确认框：确认 → 断开该地址上的旧连接、把身份切换过去
     * （{@link Store#promoteDev}）→ 再切对话。
     */
    void switchConflictDevice(final Store.Group g) {
        if (g == null || g.curIp == null || g.curIp.isEmpty()) {
            return;
        }
        final String ip = g.curIp;
        final String name = g.displayName();
        final String holder = link == null ? null : link.foreignPeerAt(ip, g.key);
        if (holder == null) {
            // 该地址上没有别的设备连着（旧连接已断 / 本来就没有）→ 直接把身份切过来
            if (store != null) {
                store.promoteDev(ip, g.key);
            }
            switchConversation(ip, name);
            return;
        }
        int opt = JOptionPane.showConfirmDialog(frame,
                "地址 " + ip + " 已被「" + holder + "」占用。\n"
                        + "是否断开它的连接，并切换到「" + name + "」？",
                "IP 冲突", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) {
            // 用户选了「否」：保持现状（不连、不切），只回一句提示
            setSub("IP conflict: " + ip + " is held by \"" + holder + "\"");
            return;
        }
        if (link != null) {
            link.disconnectAt(ip, g.key);
        }
        if (store != null) {
            store.promoteDev(ip, g.key);
        }
        switchConversation(ip, name);
        setSub("已切换到 " + name + "（" + ip + "）");
    }

    /**
     * 切换当前设备组使用的地址（顶栏地址菜单）。
     *
     * 组 = 同一台设备：切到某个地址后若那个地址暂时连不上而同组的另一个地址已有会话，
     * 收发仍走已有会话（见 Link.sessionByGroup），不会因为点错一个地址就丢了会话。
     */
    void switchGroupAddress(String key, String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return;
        }
        String v = ip.trim();
        if (store != null) {
            store.setGroupCurIp(key, v);
        }
        switchConversation(v, null);
        setSub("已切换到地址 " + v);
    }

    /**
     * 左栏状态行的短文案。
     *
     * <p>2026-09-21 用户定调：**「N 台在线」这类在线数不再上界面**（顶栏 / 左栏都不出现），
     * 只留「连没连上、连的是谁」。多设备同时在线是常态，数字对用户没有决策价值。
     */
    String stateShort() {
        switch (link.state()) {
            case CONNECTED:
                String p = link.selectedLabel();
                return "● " + (p == null || p.isEmpty()
                        ? (link.selectedIp().isEmpty() ? "已连接" : link.selectedIp()) : p);
            case CONNECTING:
                return "○ 连接中…";
            case IDLE:
                String note = link.peerLabel();
                return (note == null || note.isEmpty()) ? "○ 未连接（点设备连接）" : "○ " + note;
            default:
                return "○ 未运行";
        }
    }


    JLabel label(String text) {
        JLabel l = new JLabel(text);
        // 档位：常规 15（=发送文件按钮同档）
        l.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        return l;
    }

    JButton smallBtn(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        // 档位：常规 15（按钮=发送文件按钮同档）
        b.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        b.addActionListener(l);
        return Ui.secondary(b);
    }


    // ==================== 顶部 ====================

    /**
     * 顶栏（2026-09-16 定版，与安卓端 HeaderBar 同构；2026-09-21 右端加回「清空」）：
     * <pre>
     * 设备备注 ················ 192.168.1.12 ▼ ······ [清空]
     *              ● 已连接
     * </pre>
     *  - 左 = 设备**备注**（仅本机可见；没备注就留白，不回落广播名/IP）
     *  - 中 = **当前连接地址 + ▼**（挨在一起的一个紧凑整体；IPv6 中间省略），
     *         点开可确认/切换本组用哪个地址（当前项高亮）
     *  - 第二行 = 连接状态（居中）
     *
     * 左栏与聊天区**都没有收起功能**：PC 是一屏宽窗口，设备列表常驻 —— 所以这里不像安卓
     * 那样需要「列表展开时隐藏备注」的分支。
     *
     * 原来挤在这一行的 4 个按钮各有去处：
     *  - 「清空消息」→ **本行右端「清空」**（2026-09-21 从左栏左上角搬来：它清的是当前对话设备，
     *    贴在聊天区右上角才顺手）
     *  - 「设置」→ 左栏顶部右侧小字（设置页仍独立存在）
     *  - 「拉取手机剪贴板 / 推送剪贴板到手机」→ 设置页里新增的「剪贴板」分区
     */
    private JComponent buildHeader() {
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBackground(Ui.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.DIVIDER),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        // 左：设备备注。装在一个**固定宽度**的容器里，右侧再放一个同宽空占位 ——
        // 左右等宽时中间那段（当前地址 ▼）才是**窗口正中**（否则会被左侧备注宽度推偏）。
        // 一律 PLAIN，不用 BOLD：宋体没有粗体字面，BOLD 会被合成假粗（糊、字形走样），见 Fonts 注释
        // 档位：常规 15（=发送文件按钮同档）
        remarkLabel.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        remarkLabel.setForeground(Ui.TEXT_MAIN);
        // 2026-09-28 修复（用户反馈「圆角卡片带来的各种显示问题」同源）：JLabel 的首选宽 =
        // FontMetrics.stringWidth（零容差），而真实墨迹比度量宽 5~13%，长备注的末字会被 label
        // 自己的右缘削掉。这里在右侧补 8px 空内边距当余量（与本工程其它省略预算的余量口径一致）。
        // 代价说明：这 8px 会算进 leftBox 的实际宽度，把中段地址整体右移一点点 —— 这是既有
        // 「按备注实际宽度占位」模型的固有代价，刻意**不**引入新的居中模型（SIDE_SLOT 固定宽度
        // 那套已废弃，别为了居中把它改回来）。
        remarkLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        JPanel leftBox = new JPanel(new BorderLayout());
        leftBox.setOpaque(false);
        // leftBox 不设固定宽度，按备注文字实际宽度占位
        leftBox.add(remarkLabel, BorderLayout.WEST);
        top.add(leftBox, BorderLayout.WEST);
        // 右段：**「清空」按钮**（2026-09-21 用户定调：从左栏左上角搬到这里）——
        // 它清的就是「当前对话设备」的记录，贴着聊天区右上角才顺手。
        // 仍装在**固定宽度**的容器里、对齐 EAST：左右等宽时中间那段（地址 ▼）才是窗口正中
        //（口径与安卓端 HeaderBar 一致，改宽度前先看上面 SIDE_SLOT 的注释）。
        JPanel rightBox = new JPanel(new BorderLayout());
        rightBox.setOpaque(false);
        // rightBox 不设固定宽度，按"清空"按钮实际宽度占位
        JButton clear = Ui.subtle(new JButton("清空"));
        // 档位：常规 15（=发送文件按钮同档）
        clear.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        clear.setToolTipText("清空与当前对话设备的聊天 / 文件传输记录（只清记录，已传的文件不删）");
        clear.addActionListener(e -> clearCurrentConversation());
        rightBox.add(clear, BorderLayout.EAST);
        top.add(rightBox, BorderLayout.EAST);

        headerMidLayout = new BoxLayout(headerMid, BoxLayout.Y_AXIS);
        headerMid.setLayout(headerMidLayout);
        headerMid.setOpaque(false);
        // 档位：常规 15（=发送文件按钮同档）
        stateLine.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        stateLine.setForeground(Ui.TEXT_SUB);
        stateLine.setAlignmentX(Component.CENTER_ALIGNMENT);
        stateLine.setHorizontalAlignment(javax.swing.SwingConstants.CENTER); // 拉满行宽后必须显式居中（JLabel 默认左对齐）
        // BoxLayout Y_AXIS 沿 X 轴只给子组件 **max 宽度**，而 JLabel 的 getMaximumSize() == pref
        // （按 FontMetrics 度量）—— 实际渲染墨迹比度量宽 5~12%（分数步进 + 抗锯齿），
        // 尾字就被 label 自己的右缘裁掉（「● 已连接」的「接」缺右半，2026-09-27 实测）。
        // 拉成满行宽 + 文字居中，墨迹永远落在 label 内部，度量误差就无所谓了。
        // ⚠ 高度**不能**写 getPreferredSize().height：此刻组件还没进已显示的容器，
        // UI delegate 未安装，getPreferredSize() 返回 0×0 → 把行高钉成 0（2026-09-27
        // 「已连接上面的 IP 不见了」根因，addrBtn 零高被 BoxLayout 照单全收）。
        // 真实窗口里 headerMid 高度 = 两行 pref 之和（BorderLayout NORTH 按 pref 定高），
        // 高度放开不会有拉伸；即便有富余，文字纵向也是居中的。
        stateLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        // 第一行：当前地址 + ▼（箭头常驻，点开可确认/切换本组用的是哪个地址）
        // 档位：常规 15（=发送文件按钮同档）
        addrBtn.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        addrBtn.setForeground(new Color(0x15, 0x65, 0xC0));
        addrBtn.setBorder(BorderFactory.createEmptyBorder());
        addrBtn.setContentAreaFilled(false);
        addrBtn.setFocusPainted(false);
        // 2026-09-29（「全部统一」配套）：全局 ButtonUI = ModernButtonUI 后，顶栏地址按钮
        // 保持扁平蓝字+三角图标（不套灰卡、不被覆盖前景色）
        Ui.flat(addrBtn);
        addrBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        addrBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        // 同 stateLine：JButton 的 max == pref，长地址省略后的墨迹仍可能比度量宽 → 拉满行宽
        //（高度同样放开，理由见上面 stateLine 的注释 —— 预 UI 阶段 pref 高度是 0）
        addrBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        // 箭头放在**文字右侧**（Swing 默认是图标在左），并且用小三角图标而不是 "▼" 字形
        // —— 见 DownTriangle 的注释（默认字体没有合适字形，会渲染成小逗号）。
        addrBtn.setIcon(new DownTriangle(11, 6, new Color(0x15, 0x65, 0xC0)));
        addrBtn.setIconTextGap(8);
        addrBtn.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
        addrBtn.setVisible(false);
        addrBtn.addActionListener(e -> showAddrMenu());
        headerMid.add(addrBtn);
        headerMid.add(stateLine);
        top.add(headerMid, BorderLayout.CENTER);

        // 语音通话已改为独立窗口（VoiceCallWindow，2026-09-16）：顶部不再放
        // 「正在通话 <对方> <时间> [挂断]」状态条
        return top;
    }

    /**
     * 刷新顶栏：左 = 当前设备的备注，中 = 当前地址 ▼ + 第二行连接状态。
     *
     * 名字按用户定调只认**备注**（不像气泡署名那样回落到广播名）；没备注就留白。
     * 地址那一行取**设备组**的 curIp：同一设备换地址后这里跟着变（备注仍是同一个）。
     */
    void refreshHeader() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshHeader);
            return;
        }
        Store.Group g = currentGroup();
        // 左：备注（只认备注；没有就留一个空格占位，保证行高稳定）
        String remark = (g == null || g.remark == null) ? "" : g.remark;
        remarkLabel.setText(remark.isEmpty() ? " " : remark);
        // 中：当前地址 + ▼（箭头是按钮上的小三角图标，挨在地址右侧；见 buildHeader）
        // **IPv6 中间省略**，否则 39 字符会把中段撑满整行
        if (g != null && g.curIp != null && !g.curIp.isEmpty()) {
            addrBtn.setText(headerAddrText(g.curIp));
            addrBtn.setVisible(true);
        } else {
            addrBtn.setVisible(false);
        }
        stateLine.setText(headerState());
        // 已连接 = 绿字（深色主题下也够亮的绿），其余灰色（跟主题：浅色深灰 / 深色亮灰）
        stateLine.setForeground(link.state() == Link.State.CONNECTED
                ? new Color(0x1B, 0x7F, 0x3C) : Ui.TEXT_SUB);
        // ⚠️ 必须在这里清 BoxLayout 的尺寸缓存：
        // BoxLayout 把子组件的 min/pref/max 缓存在 xChildren/yTotal 里，只在
        // add/removeLayoutComponent 时重建（checkRequests 见 yTotal != null 直接返回）。
        // 上面 setText/setVisible 改的是**已存在**子组件，不会触发重建 —— 不清的话
        // stateLine/addrBtn 永远停在「首次布局」时的宽度（首次布局时 stateLine 还是
        // 初始空格 ≈6px、addrBtn 不可见 0px），文案变长后右端被裁切（「已连接」显示不全
        // 就是这个）。invalidateLayout 把缓存字段置空，下一次布局 pass 按当前文案重建。
        headerMidLayout.invalidateLayout(headerMid);
    }

    /**
     * 顶栏当前地址的显示文案（2026-09-16 三改，与安卓端 `IP_HEADER_BUDGET = 20` 同口径）：
     * **IPv4 原样，IPv6 中间省略**。
     *
     * IPv6 最长 39 字符，直接铺开会把顶栏中段撑满整行、还在中文备注旁边显得很脏；
     * 缩成 `2001:db8:…54:58c4:e458` 这种头尾可见的写法 —— 头段认网段前缀、尾段认接口 ID，
     * 中间那截纯填充的十六进制没有信息量。完整地址在展开的地址列表里逐条可见。
     *
     * 预算按**像素**给（粗体 14px 下 ≈ 130px ≈ 18 个字符），逐字符收紧直到放得下，
     * 这样换字体 / 换系统缩放都不会溢出。
     */
    private String headerAddrText(String ip) {
        FontMetrics fm = addrBtn.getFontMetrics(addrBtn.getFont());
        return ChatDevices.middleEllipsis(ip, fm, 130);
    }

    /**
     * 顶栏地址按钮右侧那枚小三角（▼）—— **画出来的，不靠字形**。
     *
     * 原因：直接写 `"▼"`（U+25BC）在 Windows 默认 Dialog 字体下**没有合适的字形**，
     * 渲染出来是一个贴基线的小逗号状残迹（2026-09-16 离屏渲染放大实测），
     * 既不像箭头也看不出可点。用 Graphics2D 画一个 9×6 的实心三角，任何 JVM / 字体下都一样。
     * （安卓端不存在这个问题：系统字体有完整的 U+25BC 字形，截图里是正常实心三角。）
     */
    private static final class DownTriangle implements Icon {
        private final int w;
        private final int h;
        private final Color color;

        DownTriangle(int w, int h, Color color) {
            this.w = w;
            this.h = h;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return w;
        }

        @Override
        public int getIconHeight() {
            return h;
        }

        @Override
        public void paintIcon(Component comp, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillPolygon(
                    new int[] { x, x + w, x + w / 2 },
                    new int[] { y + 1, y + 1, y + h - 1 }, 3);
            g2.dispose();
        }
    }

    /**
     * 顶栏「地址 ▼」的弹出菜单：列出该设备全部地址，**当前选中的那条高亮**，
     * 点一个即切（会顺带连一次）。单地址设备也允许展开（只有一条，高亮它自己）。
     */
    private void showAddrMenu() {
        Store.Group g = currentGroup();
        if (g == null) {
            return;
        }
        JPopupMenu pm = new JPopupMenu();
        // 档位：常规 15（=发送文件按钮同档）
        Font f = new Font(UI_FONT, Font.PLAIN, Ui.FS_STD);
        JMenuItem onItem = null;
        for (final String a : g.ips) {
            boolean on = a.equals(g.curIp);
            JMenuItem mi = new JMenuItem((on ? "● " : "○ ") + a
                    + (link.isDeviceConnected(a) ? "  已连接" : ""));
            // 当前项不靠假粗区分（宋体无粗体字面，见 Fonts），● + 下面的 ACCENT 色已足够
            mi.setFont(f);
            if (on) {
                // 高亮当前项：只记住引用，**不立即设色** —— 必须先过 Ui.theme(pm) 再设 ACCENT，
                // 顺序反了高亮色会被 theme 的 MENU_FG 覆盖（与安卓端 AddressMenu 的主色口径一致）
                onItem = mi;
            }
            mi.addActionListener(x -> switchGroupAddress(g.key, a));
            pm.add(mi);
        }
        Ui.theme(pm);
        if (onItem != null) onItem.setForeground(Ui.ACCENT);   // 高亮必须放 theme 之后
        // addrBtn 现在拉满 headerMid 行宽（见 buildHeader），x=0 会把菜单甩到页面最左；
        // 地址文字在按钮里是**居中**的 → 菜单也按按钮中心水平对齐（再钳回屏幕内）。
        int x = Math.max(0, (addrBtn.getWidth() - pm.getPreferredSize().width) / 2);
        pm.show(addrBtn, x, addrBtn.getHeight());
    }

    /** 顶栏第 2 行的连接状态（与安卓端 HeaderBar 第二行同一口径；**不带在线数**，见 stateShort） */
    private String headerState() {
        switch (link.state()) {
            case CONNECTED:
                return "● 已连接";
            case CONNECTING:
                return "○ 正在连接…";
            case IDLE: {
                String note = link.peerLabel();
                return (note == null || note.isEmpty()) ? "○ 未连接（点设备连接）" : "○ " + note;
            }
            default:
                return "○ 未运行";
        }
    }

    private JButton makeBtn(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        // 档位：常规 15（=发送文件按钮同档）
        b.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        b.addActionListener(l);
        return b;
    }

    // ==================== 底部 ====================

    /**
     * 底部输入区：**只有输入框 + 右侧三个按钮**。
     *
     * <p>原来压在下面的那行小字（「也可以直接把文件 / 文件夹拖进窗口 —— 会先问一句…」）
     * 已删（2026-09-21 用户定调）：拖放本来就是直觉操作，拖进去自然有确认框，
     * 不需要在界面底部长期占一行说明。
     */
    private JComponent buildFooter() {
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setBackground(Ui.SURFACE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.DIVIDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        // 档位：常规 15（=发送文件按钮同档）
        input.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        Ui.field(input);
        input.addActionListener(e -> sendInput());
        bottom.add(input, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        right.setOpaque(false);
        JButton send = Ui.primary(new JButton("发送"));
        // 档位：常规 15（=发送文件按钮同档）
        send.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        send.addActionListener(e -> sendInput());
        JButton call = Ui.secondary(new JButton("通话"));
        // 档位：常规 15（=发送文件按钮同档）
        call.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        call.addActionListener(e -> startCall());
        JButton file = Ui.secondary(new JButton("发送文件"));
        // 档位：常规 15（基准行，=发送文件按钮）
        file.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        file.addActionListener(e -> pickAndSendFile());
        right.add(send);
        right.add(call);
        right.add(file);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    /**
     * 发送前的守卫：必须有「当前对话设备」。
     * 多设备模型下不再有「全局唯一连接」，所以必须明确发给谁 —— 没选就提示，不猜。
     */
    private boolean requireTarget() {
        if (shownIp == null || shownIp.isEmpty()) {
            setSub("先点左边一台设备，选好要发给谁");
            JOptionPane.showMessageDialog(frame, "请先在左边设备列表点一台设备，再发送。",
                    "还没选设备", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        syncTargetToShown();
        return true;
    }

    /**
     * 发送前把链路的「当前目标」对齐到界面正在显示的对话（shownIp）。
     *
     * <p>为什么需要：selectedIp 与 shownIp 是**双轨状态**。启动自动连接多台设备时，
     * {@code Link.onHello} 会因「当前选中没有健康会话」把 selectedIp 拉回先握手的那台，
     * 而 shownIp 的跟随条件（见 {@link #setState}）在 shownIp 的设备也连着时**不跟** ——
     * 两台都在线时很容易停在「界面显示 B、链路指向 A」。此后拖文件虽然用 shownIp 过闸，
     * {@code link.sendFile/sendText} 实际发的是 selectedIp 那台：消息归属按 peerIp=A 判定，
     * 气泡进不了正在显示的 B 的会话 —— 表现就是「确认发送后聊天区毫无反应」（2026-09-23 修）。
     *
     * <p>对齐方向：**链路向界面看齐**（shownIp 是用户在界面上看到的真相），
     * 而不是把界面拽去链路那边 —— 后者会造成「看着 A 却发给 B」的错位感。
     * shownIp 为空时反向兜底：直接采用链路的当前目标并补一次历史加载。
     */
    void syncTargetToShown() {
        String sel = link.selectedIp();
        if (shownIp == null || shownIp.isEmpty()) {
            if (sel != null && !sel.isEmpty() && !sel.equals(shownIp)) {
                shownIp = sel;
                reloadHistory();
                refreshDevices();
            }
            return;
        }
        if (sel.equals(shownIp)) {
            return;
        }
        Store.Group g = currentGroup();
        link.selectTarget(shownIp, g == null ? null : g.displayName());
    }

    private void sendInput() {
        String t = input.getText();
        if (t == null || t.trim().isEmpty()) {
            return;
        }
        if (!requireTarget()) {
            return;
        }
        input.setText("");
        link.sendText(t);
        link.syncNow();
    }

    /**
     * 发起语音通话（按钮触发）。已有通话时静默拒绝。
     *
     * v2.7：先过**数据流量确认**闸门 —— PC 恒不在数据网络，本机不弹框，
     * 但会发 data_ask 问对端（对端在数据网络下会先问它自己的用户）。
     * 确认在后台线程做（绝不能阻塞 EDT），结果回到 EDT 再真正发起。
     */
    private void startCall() {
        if (!requireTarget()) {
            return;
        }
        if (call == null) {
            return;
        }
        final String ip = link.currentPeerIp();
        link.ensureDataConsentAsync(ip, DataConsent.KIND_VOICE, "语音通话", c -> {
            if (c == DataConsent.OK) {
                call.startOutgoing();
                return;
            }
            if (c == DataConsent.REMOTE_REFUSED) {
                appendCallRecord(ip, false, 0L, "对方拒绝了使用数据流量", true);
                setSub(DataConsent.peerRefusedText(DataConsent.KIND_VOICE));
                return;
            }
            setSub("设备未连接，无法发起通话");
        });
    }

    private void pickAndSendFile() {
        if (!requireTarget()) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择要发送到手机的文件");
        if (lastSendDir != null && lastSendDir.isDirectory()) {
            fc.setCurrentDirectory(lastSendDir);
        }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (f != null && f.isFile()) {
                lastSendDir = f.getParentFile();
                link.sendFile(f);
            }
        }
    }

    /** 发送整个文件夹：递归枚举成员，目录气泡聚合进度 */
    private void pickAndSendFolder() {
        if (!requireTarget()) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择要发送到手机的文件夹");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (lastSendDir != null && lastSendDir.isDirectory()) {
            fc.setCurrentDirectory(lastSendDir);
        }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (f != null && f.isDirectory()) {
                lastSendDir = f.getParentFile();
                link.sendFolder(f);
            }
        }
    }

    // ==================== 消息渲染 ====================

    /**
     * 按当前对话设备重载聊天区（切换设备时也走这里）。
     *
     * 历史文件按对端分文件存（`history/<ip>.jsonl`），所以这里天然只读到一个设备的内容 ——
     * A→B 的消息不可能出现在 C 的记录里。待发队列同样按 `peerIp` 过滤。
     */
    void reloadHistory() {
        if (store == null) {
            return;
        }
        final String ip = shownIp;
        mids.clear();
        comps.clear();
        bubbleMsgOf.clear();
        msgPanel.removeAll();
        // 同一条消息可能同时出现在待发队列与历史里（重发成功后），mid 只画一次
        java.util.Set<String> seen = new java.util.HashSet<String>();
        if (ip == null || ip.isEmpty()) {
            msgPanel.revalidate();
            msgPanel.repaint();
            return;
        }
        for (Msg m : store.loadPending()) {
            // 未送达的主动消息也先显示出来（剪贴板不算消息，跳过；别的设备的待发不进本会话）。
            // 判据是**设备**（组键）：条目可能是从这台设备的另一个地址排进去的。
            if (!Msg.KIND_CLIP.equals(m.kind) && sameDevice(m.peerIp, ip)
                    && !m.mid.isEmpty() && seen.add(m.mid)) {
                bubbles.addBubble(m);
            }
        }
        List<java.util.Map<String, Object>> his = store.loadHistory(ip, 200);
        // 按 ts 排序：hsync 补进来的旧消息是追加在文件尾部的，不排会跑到时间线最后
        his.sort(java.util.Comparator.comparingLong(
                r -> Json.lng(r, "ts", 0L)));
        for (java.util.Map<String, Object> r : his) {
            Msg m = new Msg();
            m.mid = Json.str(r, "mid", "");
            m.peerIp = Json.str(r, "peer", ip);
            m.dir = "SELF".equals(Json.str(r, "dir", "PEER")) ? Msg.DIR_SELF : Msg.DIR_PEER;
            m.kind = Json.str(r, "kind", Msg.KIND_TEXT);
            m.text = Json.str(r, "text", "");
            m.fileName = Json.str(r, "fileName", "");
            m.fileSize = Json.lng(r, "fileSize", 0L);
            m.filePath = Json.str(r, "filePath", "");
            m.ts = Json.lng(r, "ts", System.currentTimeMillis());
            m.status = Json.str(r, "status", Msg.ST_OK);
            m.isDir = Json.bool(r, "isDir", false);
            m.dirCount = Json.integer(r, "dirCount", 0);
            // 剪贴板同步不是「发消息」：早期版本写进历史的 CLIP 记录不再显示
            if (!Msg.KIND_CLIP.equals(m.kind) && !m.mid.isEmpty() && seen.add(m.mid)) {
                bubbles.addBubble(m);
            }
        }
        bubbles.scrollToBottom();
    }

    /**
     * 清空当前会话的全部聊天/文件传输记录（确认后执行）。
     *
     * <p>**做法是「逐条删除」，不是把历史文件整删**（2026-09-21 用户要求）：
     * 把本会话（含该设备的全部地址，历史按组键存）里每一条记录的 mid 捞出来，
     * 走 {@link Link#deleteMessages} 逐条清掉历史 / 待发 / 气泡，其中**文本 + 图片**
     * 还要把 mid 打成 `msg_del` 发给对端 —— 对方也删掉之后，双方 mids 清单里都没有它，
     * 下次连接 hsync 自然补不回来。
     *
     * <p>旧做法（{@code store.clearConversation}）只删本机历史文件：对端历史里那几条还在，
     * 它一旦上线对账就把整段聊天又补回来 —— 正是用户报的「对方设备一启动就同步回来了」。
     *
     * <p>边界：文件/文件夹/剪贴板/通话记录**只删本机**（协议 §4.7，不跨端传删除，也永不被 hsync 补回）；
     * 已落盘的实际文件一律不动。**对端离线时**删除命令发不出去，文本/图片下次连接可能被补回 ——
     * 这种情况会明确弹窗告知（不能假装清干净了）。
     */
    void clearCurrentConversation() {
        if (shownIp == null || shownIp.isEmpty()) {
            // 左栏底部那行小字撤掉后（2026-09-21），提示必须用弹窗 —— 光写状态行用户看不到，
            // 按了「清空」会像"没反应"。（按钮就在聊天区右上角，没选设备时最容易被顺手点到。）
            JOptionPane.showMessageDialog(frame,
                    "请先在左侧设备列表点一台设备，再清空消息。",
                    "还没选设备", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (link.hasActiveTransfer(shownIp)) {
            JOptionPane.showMessageDialog(frame,
                    "该设备还有传输任务进行中，等传输结束后再清空。",
                    "清空消息", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String ip = shownIp;
        final String name = bubbles.peerName(ip);

        // ① 先枚举要清的 mid（历史全量 + 待发），并分出「参与删除同步」的那部分（文本 + 图片）。
        //    历史按组键存 → 同一台设备的另一个地址的往来也在同一份文件里，一并清掉。
        final List<String> allMids = new ArrayList<String>();
        final List<String> syncMids = new ArrayList<String>();
        final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        if (store != null) {
            // ⚠ loadHistory 对 >2MB 的历史只读尾部 2MB（极端长会话可能有更早的行枚举不到，
            //   那部分本来就超出 UI 的 200 条窗口，留在文件里不影响界面）
            for (java.util.Map<String, Object> r : store.loadHistory(ip, Integer.MAX_VALUE)) {
                String mid = Json.str(r, "mid", "");
                if (mid.isEmpty() || !seen.add(mid)) {
                    continue;
                }
                allMids.add(mid);
                if (LinkHsync.hsyncSyncable(r)) {
                    syncMids.add(mid);
                }
            }
            for (Msg m : store.loadPending()) {
                if (m == null || m.mid == null || m.mid.isEmpty() || !sameDevice(m.peerIp, ip)) {
                    continue;
                }
                if (!seen.add(m.mid)) {
                    continue;
                }
                allMids.add(m.mid);
                if (ChatBubbles.syncableKind(m)) {
                    syncMids.add(m.mid);
                }
            }
        }

        // ② 对端能不能收到删除命令（在线 + 支持 hd）—— 直接决定提示语，不能含糊
        final boolean canNotify = link.canSendMsgDel(ip);
        int opt = JOptionPane.showConfirmDialog(frame,
                "清空与 " + name + " 的 " + allMids.size() + " 条记录？\n"
                        + "（逐条删除：文本和图片会同时通知对方一起删除，"
                        + "已传输落盘的文件不会被删）\n\n"
                        + (canNotify
                                ? "对方在线，会一起清掉。"
                                : "注意：对方当前不在线，本次只清本机 —— "
                                        + "对方上线后这些文本/图片可能被同步回来。"),
                "清空消息", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }

        // ③ 逐条执行删除（本地一次清完 + 一条/多条 msg_del 发出去）
        int notified = allMids.isEmpty() ? 0 : link.deleteMessages(ip, allMids, syncMids);
        // 缩略图缓存随记录一起清（缓存文件不是用户文件，删了也只影响重新加载速度）
        for (String mid : allMids) {
            File th = bubbles.thumbCacheFile(mid);
            if (th.exists()) {
                th.delete();
            }
        }
        reloadHistory();
        setSub("已清空与 " + name + " 的 " + allMids.size() + " 条记录（通知对方 "
                + notified + " 条）");
        // 没通知到对端就必须说清楚：否则用户以为清干净了，下次连上又冒出来
        if (!syncMids.isEmpty() && notified == 0) {
            JOptionPane.showMessageDialog(frame,
                    "已清掉本机的 " + allMids.size() + " 条记录。\n\n"
                            + "但对方不在线（或不支持删除同步），这 " + syncMids.size()
                            + " 条文本/图片的删除命令没能发出去 ——\n"
                            + "对方下次连上时，它们可能被历史同步补回来。\n"
                            + "要彻底清掉，请在对端在线时再清空一次。",
                    "清空消息", JOptionPane.WARNING_MESSAGE);
        }
    }

    public void onMessageAdded(final Msg m) {
        SwingUtilities.invokeLater(() -> {
            // 归属不明的消息**直接丢弃**，绝不画进当前会话（2026-09-21 修）。
            //
            // 判据一律是组键（= 对端 deviceId），不是 IP 字符串：同一台设备的另一个地址
            // （换网卡/热点）属于同一份会话。
            //
            // ⚠️ 这里以前写的是「peerIp 非空且不是本设备 → return」——空 peerIp 于是被**放行**，
            //    谁发的都往当前打开的会话里塞。触发场景很常见：B 发来一个文件（file_meta 那条
            //    气泡原本漏了 peerIp），你正开着 A 的会话 → 文件气泡出现在 A 里，看着就是
            //    「不同设备的聊天混在一起」。漏 peerIp 的源头已各自补上，这里再兜一道：
            //    归属不明的消息宁可不显示，也不能串台（PcMain 会先用 mid 反查一遍归属再送过来）。
            if (m.peerIp == null || m.peerIp.isEmpty()) {
                Log.i("归属不明的消息不显示（mid=" + m.mid + "）");
                return;
            }
            if (!sameDevice(m.peerIp, shownIp)) {
                return;
            }
            if (mids.contains(m.mid)) {
                return;
            }
            bubbles.addBubble(m);
            bubbles.scrollToBottom();
        });
    }

    public void onMessageUpdated(final Msg m) {
        SwingUtilities.invokeLater(() -> {
            int idx = mids.indexOf(m.mid);
            if (idx < 0) {
                // 该 mid 还没有气泡。两种来路：①接收中的气泡被一次 reloadHistory 清掉了
                // （还没写进历史、只在界面上的在途气泡，如 file_meta 刚落下的那条），
                // ②历史里本来就没有这条。以前这里直接 return，于是「文件接收完成」的更新被
                // 彻底丢弃 —— 表现就是必须再切一次会话才看得到那张图（2026-09-19 修）。
                //
                // ⚠️ 补建的前提是**归属确定**（2026-09-21 修）：以前 peerIp 为空时 foreign 判定为
                //    false，于是别的设备的传输进度/完成帧会在这里凭空补出一个气泡到当前会话里。
                //    现在空 peerIp 一律不补建（PcMain 会先用 mid 反查归属，查得到就带上了）。
                boolean unknown = m.peerIp == null || m.peerIp.isEmpty();
                boolean foreign = !unknown && !sameDevice(m.peerIp, shownIp);
                boolean noBody = (m.fileName == null || m.fileName.isEmpty())
                        && (m.text == null || m.text.isEmpty());
                if (unknown || foreign || noBody) {
                    return;
                }
                bubbles.addBubble(m);
                bubbles.scrollToBottom();
                return;
            }
            // 记住最新状态（重试/移动时要拿得到最新的 text/status）
            Msg cur = bubbleMsgOf.get(m.mid);
            if (cur != null) {
                if (m.text != null && !m.text.isEmpty()) {
                    cur.text = m.text;
                }
                cur.status = m.status;
                cur.progress = m.progress;
                if (m.fileName != null && !m.fileName.isEmpty()) {
                    cur.fileName = m.fileName;
                }
                if (m.filePath != null && !m.filePath.isEmpty()) {
                    cur.filePath = m.filePath;
                }
                if (m.peerIp != null && !m.peerIp.isEmpty()) {
                    cur.peerIp = m.peerIp;
                }
            }
            JComponent old = comps.get(idx);
            // ⚠ 必须用**合并后的 cur** 重建，不能用更新帧 m（2026-09-23 修）：
            // 发送链路的进度/完成帧不带 filePath（见 LinkSend 的状态帧构造），
            // 拿 m 重建会让 localFileOf 落空 → 图片缩略图消失、[图片] 退化为 [文件]，
            // 只有切换会话（reloadHistory 从历史读回 filePath）才能恢复。
            JComponent fresh = bubbles.bubble(cur != null ? cur : m, true);
            msgPanel.remove(old);
            msgPanel.add(fresh, idx);
            comps.set(idx, fresh);
            msgPanel.revalidate();
            msgPanel.repaint();
        });
    }

    /** 一条消息被移除（文件被任一方取消）——从聊天区摘掉，两端都不再显示 */
    public void onMessageRemoved(String mid) {
        SwingUtilities.invokeLater(() -> {
            int idx = mids.indexOf(mid);
            if (idx < 0) {
                return;
            }
            JComponent c = comps.get(idx);
            msgPanel.remove(c);
            mids.remove(idx);
            comps.remove(idx);
            bubbleMsgOf.remove(mid);
            msgPanel.revalidate();
            msgPanel.repaint();
            // 缩略图缓存随消息一起清理，避免堆积
            File th = bubbles.thumbCacheFile(mid);
            if (th.exists()) {
                th.delete();
            }
        });
    }

    /**
     * 一条消息在时间线上被移到「最新一条」（v2.7）：把它的气泡摘下来重新挂到末尾，
     * 并刷新 ts（历史按 ts 排序，重载后才不会又跳回原位）。
     */
    public void onMessageMoved(String mid) {
        SwingUtilities.invokeLater(() -> {
            int idx = mids.indexOf(mid);
            if (idx < 0) {
                return;
            }
            JComponent c = comps.remove(idx);
            mids.remove(idx);
            msgPanel.remove(c);
            if (c instanceof JComponent) {
                // 气泡里带 msg 对象：重新构造一个带新 ts 的气泡（沿用原消息内容）
                Msg old = bubbleMsgOf.get(mid);
                if (old != null) {
                    old.ts = System.currentTimeMillis();
                    if (store != null) {
                        store.appendHistory(old.peerIp, old.toJson());
                    }
                    c = bubbles.bubble(old, false);
                }
            }
            mids.add(mid);
            comps.add(c);
            msgPanel.add(c);
            msgPanel.revalidate();
            msgPanel.repaint();
            bubbles.scrollToBottom();
        });
    }

    /** 历史同步（hsync）补齐完成：重载当前对话（reloadHistory 已按 ts 排序） */    public void onHsyncApplied(String ip, int count) {
        SwingUtilities.invokeLater(() -> {
            // 比较用组键：补齐可能发生在同一台设备的另一个地址上
            if (count > 0 && ip != null && sameDevice(ip, shownIp)) {
                reloadHistory();
                setSub("历史同步：已从对端补齐 " + count + " 条");
            }
        });
    }

    public void setState(final String text, final String sub) {
        SwingUtilities.invokeLater(() -> {
            // text = 连接状态文案（PcMain 给的）→ 现在落到**左栏**；顶栏第 1 行是设备名、第 2 行是状态
            setDeviceState(text);
            setSub(sub);
            // 链路侧自动选中了设备（首台连上 / 当前选中没有健康会话时跟随）而界面没跟 → 切过去
            String t = link.selectedIp();
            if (t != null && !t.isEmpty() && !t.equals(shownIp)
                    && (shownIp == null || shownIp.isEmpty() || !link.isDeviceConnected(shownIp))) {
                shownIp = t;
                reloadHistory();
            }
            refreshDevices();
        });
    }

    // ==================== 设备分组（同一 deviceId 的多个地址 = 一台设备） ====================

    /** 会话组键（对端 deviceId；未知设备回退 IP） */
    String convKey(String ip) {
        if (store == null || ip == null || ip.isEmpty()) {
            return ip == null ? "" : ip;
        }
        return store.convKeyOf(ip);
    }

    /** 两个地址是否属于同一台设备（组键相等） */
    private boolean sameDevice(String ip1, String ip2) {
        if (ip1 == null || ip1.isEmpty() || ip2 == null || ip2.isEmpty()) {
            return false;
        }
        if (ip1.equals(ip2)) {
            return true;
        }
        String k1 = convKey(ip1);
        return !k1.isEmpty() && k1.equals(convKey(ip2));
    }

    /** 当前对话设备所属的组（未选中/找不到返回 null） */
    Store.Group currentGroup() {
        if (store == null || shownIp == null || shownIp.isEmpty()) {
            return null;
        }
        return store.groupOfIp(shownIp);
    }

    public void setSub(String sub) {
        SwingUtilities.invokeLater(() -> subLabel.setText(
                ChatDevices.wrapHtml(sub == null ? " " : sub, DEVICE_PANEL_W - 44)));
    }

    public void setClipBuffer(String text) {
        SwingUtilities.invokeLater(() -> clipLabel.setText(ChatDevices.wrapHtml(
                "剪贴板缓冲（只留最新一条）：" + (text == null || text.isEmpty()
                        ? "（空）" : Texts.preview(text, 40) + "  [" + text.length() + " 字]"),
                DEVICE_PANEL_W - 44)));
    }

    public void showWindow() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    // ==================== 语音通话 UI ====================

    /** 注入通话状态机（PcMain 装配）。 */
    public void setCallManager(CallManager c) {
        this.call = c;
        c.setListener(new CallManager.Listener() {
            @Override
            public void onStateChanged(CallManager.State state, String peerIp, String peerName) {
                SwingUtilities.invokeLater(() -> refreshCallBanner());
            }

            @Override
            public void onIncomingCall(String peerIp, String peerName, boolean video) {
                SwingUtilities.invokeLater(() -> showIncomingDialog(peerIp, peerName, video));
            }

            @Override
            public void onMediaNotice(String text) {
                // 2.9 / S23 的 UI 提示：只落状态文案，**不做任何切换动作**（I17）。
                // setSub 自己 invokeLater，所以这里可以直接在网络/看门狗线程上调。
                setSub(text);
            }

            @Override
            public void onCallEnded(String peerIp, String reason) {
                SwingUtilities.invokeLater(() -> {
                    refreshCallBanner();
                    setSub("通话结束：" + reason);
                });
            }

            @Override
            public void onCallEnded(String peerIp, String reason, boolean video,
                                    long durationMs, boolean outgoing) {
                SwingUtilities.invokeLater(() -> {
                    refreshCallBanner();
                    setSub("通话结束：" + reason);
                    // v2.7：语音/视频通话以消息方式留痕（时长 / 拒绝原因），与 Android 端同构
                    appendCallRecord(peerIp, video, durationMs, reason, outgoing);
                });
            }
        });
    }

    /**
     * 记一条**通话记录消息**（v2.7）。
     * 文本两种形态：通过话 → 「语音通话 00:12」；没接通 → 「语音通话 对方拒绝」。
     * 只记本机（hsync 只对账文本与图片，通话记录不跨端同步）。
     */
    public void appendCallRecord(String peerIp, boolean video, long durationMs,
                                 String reason, boolean outgoing) {
        if (peerIp == null || peerIp.isEmpty()) {
            return;
        }
        Msg m = new Msg();
        m.mid = java.util.UUID.randomUUID().toString().replace("-", "");
        m.peerIp = peerIp;
        m.dir = outgoing ? Msg.DIR_SELF : Msg.DIR_PEER;
        m.kind = Msg.KIND_CALL;
        String label = video ? "视频通话" : "语音通话";
        m.text = (durationMs >= 1000L) ? (label + " " + formatDuration(durationMs))
                : (label + " " + (reason == null ? "" : reason));
        m.ts = System.currentTimeMillis();
        m.status = Msg.ST_OK;
        if (store != null) {
            store.appendHistory(m.peerIp, m.toJson());
        }
        onMessageAdded(m);
    }

    /** 通话时长文案：不足 1 小时用 mm:ss，超过用 h:mm:ss */
    public static String formatDuration(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return (h > 0) ? String.format("%d:%02d:%02d", Long.valueOf(h), Long.valueOf(m), Long.valueOf(s))
                : String.format("%02d:%02d", Long.valueOf(m), Long.valueOf(s));
    }

    /**
     * 按通话状态刷新**语音通话独立窗口**（VoiceCallWindow）；无通话时关闭窗口。
     * 视频通话走 VideoWindow（PcMain 创建），这里直接忽略。
     */
    private void refreshCallBanner() {
        CallManager.State st = call == null ? CallManager.State.IDLE : call.state();
        // 来电振铃阶段（INCOMING）**不开通话页**：屏幕上只应有「来电」那一个弹窗
        // （用户 2026-09-19 要求：接听语音/视频不得同时弹两个窗口，同意后才显示通话页面）。
        // 用户点「接听」→ 状态转 TALKING → 下一次刷新才把通话页建出来。
        // 视频来电的通话页是 VideoWindow（CallManager 在 TALKING 后才 open），这里只管语音窗。
        if (st == CallManager.State.IDLE || st == CallManager.State.INCOMING
                || call.isVideoMode()) {
            closeCallWindow();
            return;
        }
        String name = displayNameOf(call.peerIp(), call.peerName());
        String ip = call.peerIp();
        if (callWindow == null) {
            callWindow = VoiceCallWindow.open(frame, name);
            // 新建即按状态摆好：呼叫中是「呼叫提示」，不显示计时区
            callWindow.setShowTimer(st == CallManager.State.TALKING);
            callWindow.setActions(() -> {
                if (call != null) {
                    call.hangup();
                }
            });
        }
        callWindow.setPeer(name, ip);
        if (st == CallManager.State.TALKING) {
            // 已接通 → 切为完整通话页（出现计时）
            callWindow.setShowTimer(true);
            // 2.11 / §4.3⑥：带内档要在界面上**看得见**是降级（不是只进日志）。
            // 读的是 CallManager 的降级标志位，不是 tier（D3）。
            callWindow.setStatus(call.mediaDowngraded()
                    ? "通话中（媒体降级：走消息通道）" : "通话中");
            callWindow.setElapsed(call.elapsedMs());
            if (callTicker == null) {
                callTicker = new javax.swing.Timer(500, e -> {
                    if (call != null && call.state() == CallManager.State.TALKING
                            && !call.isVideoMode() && callWindow != null) {
                        callWindow.setElapsed(call.elapsedMs());
                    }
                });
            }
            callTicker.start();
        } else {
            // 呼出中（呼叫提示，用户 2026-09-19 定调）：只显示「正在呼叫…」，不显示计时
            callWindow.setShowTimer(false);
            callWindow.setStatus("正在呼叫…");
            callWindow.setElapsed(0);
        }
    }

    /** 关闭语音通话窗口并停掉刷新定时器（幂等） */
    private void closeCallWindow() {
        if (callTicker != null) {
            callTicker.stop();
        }
        if (callWindow != null) {
            callWindow.close();
            callWindow = null;
        }
    }

    private String displayNameOf(String ip, String fallback) {
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        if (store != null && ip != null) {
            // 备注按设备（组）取：同组任何地址拿到同一个名字
            Store.Group g = store.groupOfIp(ip);
            if (g != null && g.displayName() != null && !g.displayName().isEmpty()) {
                return g.displayName();
            }
            for (Peer p : store.peers()) {
                if (ip.equals(p.ip)) {
                    return p.displayName();
                }
            }
        }
        return ip == null ? "" : ip;
    }

    /**
     * 来电弹窗（2026-09-16 用户定调，与 Android 端同构）：深色面板 + 底部
     * 红色「拒绝」/ 绿色「接听」圆形电话图标按钮（语音、视频来电共用）。
     */
    private void showIncomingDialog(String ip, String name, boolean video) {
        String shown = displayNameOf(ip, name);
        final JDialog dlg = new JDialog(frame, video ? "视频来电" : "来电", true);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        // 共享拒绝回调：标题栏 X 与「拒绝」按钮都走它（与旧 windowClosing 语义 1:1）
        final Runnable reject = () -> {
            dlg.dispose();
            call.rejectIncoming();
        };
        // 兜底：Alt+F4 / 系统关闭仍走 windowClosing（保留旧行为）
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                reject.run();
            }
        });
        // 自绘 chrome（必须在 setVisible 之前）；X = 拒绝
        WindowChrome.install(dlg, video ? "视频来电" : "来电", reject);
        dlg.add(buildIncomingPanel(shown, ip, video, reject,
                () -> {
                    dlg.dispose();
                    call.acceptIncoming();
                }), BorderLayout.CENTER);
        dlg.setSize(380, 280 + WindowChrome.DIALOG_TITLE_H);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    /**
     * 来电弹窗内容（类型 + 名称 + IP + 拒绝/接听按钮）。
     * 抽成独立静态方法：离屏探针（tools/ChromeProbe）可单独调用它，
     * 套上 DialogChrome 独立验证，不必启动整个 ChatWindow。
     */
    private static JComponent buildIncomingPanel(String shown, String ip, boolean video,
                                                 Runnable onReject, Runnable onAccept) {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(new Color(0x0E, 0x10, 0x13));
        root.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JPanel mid = new JPanel(new GridLayout(0, 1, 0, 4));
        mid.setOpaque(false);
        JLabel kindLabel = new JLabel(video ? "视频来电（本机只接收画面）" : "语音来电", JLabel.CENTER);
        kindLabel.setForeground(new Color(0x8B, 0xD6, 0x9E));
        // 档位：常规 15（=发送文件按钮同档）
        kindLabel.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        // 大字号（22px）：全局关 AA 下 outline 会锯齿 → 用 AALabel 局部显式开 AA
        JLabel nameLabel = new Ui.AALabel(shown, JLabel.CENTER);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font(UI_FONT, Font.PLAIN, 22));
        JLabel ipLabel = new JLabel("IP：" + ip, JLabel.CENTER);
        ipLabel.setForeground(new Color(140, 146, 154));
        // 档位：常规 15（=发送文件按钮同档）
        ipLabel.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
        mid.add(kindLabel);
        mid.add(nameLabel);
        mid.add(ipLabel);
        root.add(mid, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 72, 0));
        btns.setOpaque(false);
        JButton reject = CallIcons.circleButton(CallIcons.hangup(28),
                new Color(0xD3, 0x2F, 0x2F), "拒绝", 64);
        reject.addActionListener(e -> onReject.run());
        JButton accept = CallIcons.circleButton(CallIcons.answer(28),
                new Color(0x2E, 0x7D, 0x32), "接听", 64);
        accept.addActionListener(e -> onAccept.run());
        btns.add(reject);
        btns.add(accept);
        root.add(btns, BorderLayout.SOUTH);

        return root;
    }

    public void hideWindow() {
        frame.setVisible(false);
    }

    public boolean isVisible() {
        return frame.isVisible();
    }

    /** 设置对话框：接收文件的保存位置（设备管理在主界面左侧列表里） */
    static final class SettingsDialog {
        static void show(final JFrame owner, final Store store, final Link link) {
            final JDialog d = new JDialog(owner, "设置", true);
            // 共享回调：标题栏 X 与「关闭」按钮都走它（X 不 syncNow 就是行为回退）
            final Runnable close = () -> {
                d.dispose();
                link.syncNow();
            };
            // 自绘 chrome（必须在 setVisible 之前）；内容加到返回的 chrome 上
            WindowChrome.install(d, "设置", close);
            d.add(buildContent(d, store, link), BorderLayout.CENTER);

            JButton ok = Ui.primary(new JButton("关闭"));
            ok.addActionListener(e -> close.run());
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
            south.add(ok);
            d.add(south, BorderLayout.SOUTH);

            // 高按内容首选（contentPane=DialogChrome 的首选高已含 32px 标题栏）：
            // 2026-09-30 说明文字从 CSS 11px 提到 FS_MIN 档后占的行更高，写死 420 会裁掉最后一段。
            int need = d.getContentPane().getPreferredSize().height + 8;
            d.setSize(470, Math.max(420 + WindowChrome.DIALOG_TITLE_H, need));
            d.setLocationRelativeTo(owner);
            d.setVisible(true);
        }

        /**
         * 设置窗口内容（保存位置 + 剪贴板）。
         * 抽成独立静态方法：离屏探针（tools/ChromeProbe）可单独调用它，
         * 套上 DialogChrome 独立验证，不必启动整个 ChatWindow。
         */
        static JComponent buildContent(JDialog owner, Store store, Link link) {
            JPanel dirBox = new JPanel();
            dirBox.setLayout(new BoxLayout(dirBox, BoxLayout.Y_AXIS));
            dirBox.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

            JLabel t1 = new JLabel("收到文件的保存位置");
            // 档位：常规 15（=发送文件按钮同档）；标题与说明的层级由颜色区分（不用假粗）
            t1.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            t1.setAlignmentX(Component.LEFT_ALIGNMENT);
            dirBox.add(t1);

            final JLabel dirLabel = new JLabel(store.receivedDirText());
            // 档位：常规 15（=发送文件按钮同档）
            dirLabel.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            dirLabel.setForeground(Ui.TEXT_SUB);
            dirLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            dirBox.add(dirLabel);

            JPanel dirBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            dirBtns.setOpaque(false);
            dirBtns.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton change = Ui.secondary(new JButton("更改…"));
            change.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("选择接收文件的保存位置");
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setAcceptAllFileFilterUsed(false);
                File cur = new File(store.receivedDirText());
                if (cur.isDirectory()) {
                    fc.setCurrentDirectory(cur);
                }
                if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION
                        && fc.getSelectedFile() != null) {
                    store.setSaveDir(fc.getSelectedFile().getAbsolutePath());
                    dirLabel.setText(store.receivedDirText());
                    Log.i("接收目录已改为: " + store.receivedDirText());
                }
            });
            dirBtns.add(change);

            JButton reset = Ui.secondary(new JButton("恢复默认"));
            reset.addActionListener(e -> {
                store.setSaveDir("");
                dirLabel.setText(store.receivedDirText());
                Log.i("接收目录已恢复默认: " + store.receivedDirText());
            });
            dirBtns.add(reset);

            JButton open = Ui.secondary(new JButton("打开目录"));
            open.addActionListener(e -> {
                try {
                    File f = new File(store.receivedDirText());
                    if (!f.isDirectory()) {
                        f.mkdirs();
                    }
                    java.awt.Desktop.getDesktop().open(f);
                } catch (Throwable t) {
                    JOptionPane.showMessageDialog(owner,
                            "打开目录失败：" + t + "\n路径：" + store.receivedDirText());
                }
            });
            dirBtns.add(open);
            dirBox.add(dirBtns);

            JLabel hint = new JLabel("目录不可写时会自动回落到默认目录；同名文件自动加 (1)(2) 不覆盖。");
            // 档位：常规 15（=发送文件按钮同档）
            hint.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            hint.setForeground(Ui.TEXT_SUB);
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            dirBox.add(hint);

            // 说明文字：以前在 CSS 里写 font-size:11px —— 宋体 11px 下笔画并成墨块，看着像假粗
            // 且字形变形（2026-09-30 用户反馈）。字号回到工程档位 FS_MIN，颜色用 TEXT_SUB 跟主题。
            JLabel devHint = new JLabel("<html><body style='width:400px'>"
                    + "设备管理在左侧列表（常驻展开）：单击某项即连接、切为当前对话，右键可改备注/IP 或删除。"
                    + "聊天界面右上角的「清空」= 清空当前对话设备的记录。</body></html>");
            devHint.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_MIN));
            devHint.setForeground(Ui.TEXT_SUB);
            devHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            devHint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            dirBox.add(devHint);

            // ===== 剪贴板（2026-09-15 从顶栏挪进来）=====
            // 本机复制内容本来就会自动推给手机，这两个按钮只是手动补偿；与安卓端设置页同构。
            JPanel clipBox = new JPanel();
            clipBox.setLayout(new BoxLayout(clipBox, BoxLayout.Y_AXIS));
            clipBox.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));

            JLabel t2 = new JLabel("剪贴板");
            // 档位：常规 15（=发送文件按钮同档）
            t2.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            t2.setAlignmentX(Component.LEFT_ALIGNMENT);
            clipBox.add(t2);

            JPanel clipBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            clipBtns.setOpaque(false);
            clipBtns.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton pullBtn = Ui.secondary(new JButton("拉取手机剪贴板"));
            pullBtn.addActionListener(e -> link.pullPeerClip());
            clipBtns.add(pullBtn);

            JButton pushBtn = Ui.secondary(new JButton("推送剪贴板到手机"));
            pushBtn.addActionListener(e -> {
                String b = link.clipBuffer();
                if (b == null || b.isEmpty()) {
                    JOptionPane.showMessageDialog(owner, "本机剪贴板缓冲为空，先在电脑上复制点文本。",
                            "剪贴板", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                link.sendClip(b, Clip.sha1(b));
            });
            clipBtns.add(pushBtn);
            clipBox.add(clipBtns);

            // 同 devHint：去掉 CSS 里的 font-size:11px 与写死的 color:#666（深色主题下灰字贴黑底
            // 几乎看不见），字号走 FS_MIN 档、颜色走 TEXT_SUB；<b> 假粗一并去掉
            JLabel clipHint = new JLabel("<html><body style='width:400px'>"
                    + "本机复制的内容会自动推给手机，平时不用点这两个按钮。"
                    + "手机端受系统限制，只在切回前台时读一次自己的剪贴板，所以「拉取」要等它在手机上操作过才有内容。"
                    + "</body></html>");
            clipHint.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_MIN));
            clipHint.setForeground(Ui.TEXT_SUB);
            clipHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            clipBox.add(clipHint);

            // ===== 开机自启（2026-10-01）=====
            JPanel bootBox = new JPanel();
            bootBox.setLayout(new BoxLayout(bootBox, BoxLayout.Y_AXIS));
            bootBox.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));

            JLabel t3 = new JLabel("开机自启");
            // 档位：常规 15（=发送文件按钮同档）
            t3.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            t3.setAlignmentX(Component.LEFT_ALIGNMENT);
            bootBox.add(t3);

            final JLabel bootState = new JLabel(" ");
            // 档位：常规 15（=发送文件按钮同档）
            bootState.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            bootState.setForeground(Ui.TEXT_SUB);
            bootState.setAlignmentX(Component.LEFT_ALIGNMENT);
            bootBox.add(bootState);

            JPanel bootBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            bootBtns.setOpaque(false);
            bootBtns.setAlignmentX(Component.LEFT_ALIGNMENT);

            final JButton bootBtn = Ui.secondary(new JButton("开启自启"));
            bootBtns.add(bootBtn);
            bootBox.add(bootBtns);

            final java.util.concurrent.atomic.AtomicBoolean bootOn =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            // 状态文案与按钮文案同源（都只看 bootOn），避免两处各写一遍走岔
            final Runnable render = () -> {
                bootState.setText(bootOn.get()
                        ? "已开启：开机后自动启动，直接进托盘、不弹界面"
                        : "已关闭");
                bootBtn.setText(bootOn.get() ? "关闭自启" : "开启自启");
            };

            if (!Autostart.supported()) {
                bootState.setText("当前系统不支持开机自启（仅 Windows）");
                bootBtn.setVisible(false);
            } else {
                // 读注册表要起一个 powershell 进程（0.2~0.5s）→ 后台读，别拖慢设置页出现
                bootState.setText("正在读取当前状态…");
                bootBtn.setEnabled(false);
                Thread reader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String cur = Autostart.registeredCommand();
                        SwingUtilities.invokeLater(() -> {
                            bootOn.set(cur != null);
                            render.run();
                            bootBtn.setEnabled(true);
                        });
                    }
                }, "pp-autostart-read");
                reader.setDaemon(true);
                reader.start();

                bootBtn.addActionListener(e -> {
                    final boolean want = !bootOn.get();
                    bootBtn.setEnabled(false);
                    bootState.setText(want ? "正在注册开机自启…" : "正在取消开机自启…");
                    Thread worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (want) {
                                Autostart.enable();
                            } else {
                                Autostart.disable();
                            }
                            // 以注册表里的实际结果为准：组策略/精简系统可能拦住 HKCU 写入
                            final boolean now = Autostart.registeredCommand() != null;
                            SwingUtilities.invokeLater(() -> {
                                bootOn.set(now);
                                render.run();
                                if (now != want) {
                                    bootState.setText(want
                                            ? "注册未生效，详见 logs/pc.log"
                                            : "取消未生效，详见 logs/pc.log");
                                }
                                bootBtn.setEnabled(true);
                            });
                        }
                    }, "pp-autostart");
                    worker.setDaemon(true);
                    worker.start();
                });
            }

            JLabel bootHint = new JLabel("<html><body style='width:340px'>"
                    + "开启后每次登录 Windows 会在后台自动启动，只出现托盘图标、不弹出聊天窗口。"
                    + "注册项写在当前用户的启动项里（任务管理器 →「启动应用」可查看和管理），不需要管理员权限。"
                    + "</body></html>");
            bootHint.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_MIN));
            bootHint.setForeground(Ui.TEXT_SUB);
            bootHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            bootBox.add(bootHint);

            // ===== 视频接收画质上限（2026-10-04：安卓加了第四档「超清 1080P/5M」后，
            //       PC 这个上限以前只能编辑 config.json —— setVideoMaxLevel 全仓库没有调用方）=====
            JPanel videoBox = new JPanel();
            videoBox.setLayout(new BoxLayout(videoBox, BoxLayout.Y_AXIS));
            videoBox.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));

            JLabel t4 = new JLabel("视频接收画质上限");
            // 档位：常规 15（=发送文件按钮同档）
            t4.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            t4.setAlignmentX(Component.LEFT_ALIGNMENT);
            videoBox.add(t4);

            final JLabel videoState = new JLabel(" ");
            // 档位：常规 15（=发送文件按钮同档）
            videoState.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_STD));
            videoState.setForeground(Ui.TEXT_SUB);
            videoState.setAlignmentX(Component.LEFT_ALIGNMENT);
            videoBox.add(videoState);

            // 四档名与安卓 `VideoLevel` 表**同序同字**（下标即 idx）；分辨率/码率是表里的原值，不新设数字。
            // 做成四枚常驻钮而不是下拉：本仓库没有任何 JComboBox 先例，主题是自绘的，
            // 原生下拉在深色下是一块白底 —— 与「控件常驻可见、单击直接切」的既有口径也不符。
            final String[] lvNames = {"流畅", "标清", "高清（推荐）", "超清"};
            final String[] lvDescs = {"480P/0.5M", "720P/1.5M", "1080P/2M", "1080P/5M"};
            JPanel videoBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            videoBtns.setOpaque(false);
            videoBtns.setAlignmentX(Component.LEFT_ALIGNMENT);
            final JButton[] lvBtns = new JButton[lvNames.length];
            final Runnable renderVideo = () -> {
                int cur = store.videoMaxLevel();
                for (int i = 0; i < lvBtns.length; i++) {
                    if (i == cur) {
                        Ui.primary(lvBtns[i]);
                    } else {
                        Ui.secondary(lvBtns[i]);
                    }
                }
                videoState.setText("当前上限：" + lvNames[cur] + " " + lvDescs[cur]);
            };
            for (int i = 0; i < lvBtns.length; i++) {
                final int idx = i;
                lvBtns[i] = Ui.secondary(new JButton(lvNames[i]));
                lvBtns[i].addActionListener(e -> {
                    store.setVideoMaxLevel(idx);      // 内部 save()，立刻写回 config.json
                    renderVideo.run();
                    Log.i("视频接收上限已设为 L" + idx + "（" + lvNames[idx] + " " + lvDescs[idx] + "）");
                });
                videoBtns.add(lvBtns[i]);
            }
            renderVideo.run();   // 打开页面就上一次状态：高亮当前档 + 填「当前上限：…」
            videoBox.add(videoBtns);

            JLabel videoHint = new JLabel("<html><body style='width:340px'>"
                    + "本端能接收的最高档位：每次通话实际取「手机上选的」与这里的较小值，"
                    + "这里调低会把手机的高档夹下来。改完下一通生效，正在进行的通话不变；"
                    + "点一下就写入 config.json（老机器上文件里存的可能是旧值，打开这一页就能看到现状）。"
                    + "</body></html>");
            videoHint.setFont(new Font(UI_FONT, Font.PLAIN, Ui.FS_MIN));
            videoHint.setForeground(Ui.TEXT_SUB);
            videoHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            videoBox.add(videoHint);

            JPanel box = new JPanel();
            box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
            box.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            box.add(dirBox);
            box.add(clipBox);
            box.add(bootBox);
            box.add(videoBox);
            return box;
        }
    }
}
