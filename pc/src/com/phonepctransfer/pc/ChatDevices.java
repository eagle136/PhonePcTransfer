package com.phonepctransfer.pc;

import java.awt.*;
import java.awt.image.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;

// ==================================================================
// 左侧设备面板（拆分自 ChatWindow.java）：设备列表、本机 IP 地址块、
// 添加/编辑设备对话框、DeviceRow 自绘行。持 ChatWindow 引用。
// ==================================================================

final class ChatDevices {

    final ChatWindow W;

    ChatDevices(ChatWindow W) {
        this.W = W;
    }

    private final JPanel deviceListPanel = new JPanel();
    private final JLabel deviceStateLabel = new JLabel(" ");
    /** 设备列表滚动面板（离屏探针量竖条宽度：10px，比聊天区 12 窄） */
    JScrollPane deviceScroll;

    /**
     * 左栏顶部「本机ip：」地址块（2026-09-15 与安卓端设备列表对齐）。
     * 每行一个地址、点击复制；地址随 WiFi 开关变化，由 {@W.link #refreshLocalIps()} 重建。
     */
    private JPanel localIpPanel;
    /** 上一次画出来的地址列表：内容没变就不重建，避免每 5s 无谓重绘 */
    private java.util.List<String> localIpCache = Collections.<String>emptyList();

    // ==================== 左侧：设备列表 ====================

    JComponent buildDevicePanel() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setPreferredSize(new Dimension(ChatWindow.DEVICE_PANEL_W, 10));
        wrap.setBackground(Ui.SURFACE);
        wrap.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.DIVIDER));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(8, 6, 4, 6));

        // 顶部一行已撤（2026-09-23 用户定调）：「设置」按钮挪到**左栏底部**，
        // 与接收目录同行（左路径右按钮）——顶部只剩本机地址块，更干净。

        // 本机地址块：放在按钮行下面（与安卓端设备列表里的位置一致）——
        // 目的是「在这台机器上复制这一台的 IP，去对端『＋设备』里粘贴」，所以放在最顺手的位置。
        localIpPanel = buildLocalIpBox();
        head.add(localIpPanel);

        JPanel acts = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        acts.setOpaque(false);
        acts.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton add = W.smallBtn("＋设备", e -> editPeer(null));
        acts.add(add);
        head.add(acts);

        // 「单击设备 = 切到它并连接 / 右键改备注/IP · 断线后点一下重连」那两行小字已删
        //（2026-09-21 用户定调）：设备行的工具提示里已有同样信息，左栏留白更干净。

        wrap.add(head, BorderLayout.NORTH);

        deviceListPanel.setLayout(new BoxLayout(deviceListPanel, BoxLayout.Y_AXIS));
        deviceListPanel.setBackground(Ui.SURFACE);
        deviceScroll = new JScrollPane(deviceListPanel);
        deviceScroll.setBorder(null);
        // 横条必须关掉：行宽是按 200px 栏宽定的，一旦出现竖直滚动条，视口就窄了十几像素，
        // 于是"永远差一点点"地冒出横向滚动条（实测左栏底部一条灰条）。
        deviceScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        deviceScroll.getVerticalScrollBar().setUnitIncrement(12);
        // 自定义滚动条（R4）：条宽 10，比聊天区 12 窄（Q4/Q6 定稿）
        Ui.installScrollBar(deviceScroll, 10);
        wrap.add(deviceScroll, BorderLayout.CENTER);

        // 左栏底部（2026-09-27 用户定调，两行布局）：
        //   上行 = 接收（保存）目录路径，**居中**；太长做中间省略（头留盘符/根、尾留最后一级），
        //   完整路径始终挂在工具提示里。目录在「设置」里可改，每 5s 跟一次。
        //   下行 = 「深色/浅色」主题切换按钮 + 「设置」按钮，**整组居中**。
        //
        // 2026-09-28 彻底修复（用户反馈：路径右边显示不全、两按钮右边框缺失）：
        //   1) 按钮行改用 BoxLayout X + 3 段等宽 glue：布局引擎把行的**实际**内容宽均分给
        //      「左空隙 / 中间间距 / 右空隙」。无论实际行宽、按钮宽是多少（任意 DPI / 字体度量），
        //      两按钮永远完整、居中、间距相等；且行最窄宽度 = 两按钮宽之和（min 尺寸保证），
        //      任何情况下都不会裁边。（旧 FlowLayout 按固定 184px 推 hgap，实际行宽略窄时
        //      整组溢出 1~2px，「设置」按钮右缘被裁。）
        //   2) 上行文本的省略预算改按行**实际**内容宽计算（不再假定固定 200px），
        //      保证文字墨迹宽恒小于行宽，末字符永不贴到面板边缘。
        //   3) 按钮边框改「填充环」绘制（见 Ui.ModernButtonUI）：细描边在分数设备坐标
        //      （如 Windows 125% 缩放——旧钉定值，2026-09-29 起钉 100%；「跟随系统缩放」模式下仍会出现）
        //      四条边落点不同、视觉粗细不一（一边几乎隐形）；
        //      填充环在任何 DPI 下四条边覆盖结构相同、粗细恒定。
        infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);
        infoPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 6));
        // 档位：最小 13（=IP 悬停提示同档）
        dirLabel.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_MIN));
        dirLabel.setForeground(Ui.TEXT_SUB);
        dirLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        // BoxLayout 按 alignmentX 在行内居中；不设的话短文本会偏左
        dirLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        // 2026-09-28 用户附图（左栏底部路径「D:\Downloads」首字 D、末字 载 各被削掉半边）根因修复：
        //   JLabel 的首选宽 = FontMetrics.stringWidth，是**零容差**度量；而抗锯齿 + 分数步进下的
        //   真实墨迹比度量宽 5~13%（同源常量 INK_SAFETY=1.12，见本文件中间省略那一节），
        //   组件绘制被裁剪到自身 bounds ⇒ 首/末字形被 label 自己的左右缘削掉。
        //   显式放开 max 尺寸，BoxLayout Y_AXIS 就会把它拉满整行（本处是在 X 轴方向被拉伸），
        //   文字再由上面的 CENTER 对齐在行内居中 ⇒ 墨迹永远落在 label 内部，度量误差彻底失效。
        //   这与顶栏 stateLine / addrBtn 的修法同源（ChatWindow.buildHeader 里已有同样一行）。
        // 为什么不能再靠「省略预算」解决：省略预算只能保证**度量**宽 < 行宽，而 label 自身宽仍等于
        //   文字的度量宽、两侧一点余量都没有 —— 墨迹照样被 label 自己的 bounds 裁掉（附图就是这一处）。
        dirLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        infoPanel.add(dirLabel);
        // 文案 = 点击后进入的目标模式（2026-09-27 用户定调：避免「深色/浅色」单字语义不清）；
        // 保持 4 字：两按钮默认 padding(64px/个) 下，5 字 + 「设置」在 184px 行宽里 0 间距也放不下
        themeBtn = W.smallBtn(Ui.isDark() ? "切到浅色" : "切到深色", e -> toggleTheme());
        // 档位：常规 15（按钮=发送文件按钮同档）
        themeBtn.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        themeBtn.setToolTipText("点击切换到" + (Ui.isDark() ? "浅色" : "深色") + "模式（当前：" + (Ui.isDark() ? "深色" : "浅色") + "）");
        settingsBtn = W.smallBtn("设置", e -> ChatWindow.SettingsDialog.show(W.frame, W.store, W.link));
        // 档位：常规 15（按钮=发送文件按钮同档）
        settingsBtn.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        // 紧凑 padding：默认 margin(14) + ModernButtonUI border(18) 每侧 32px，
        // 两个按钮 + 间距在 200px 栏宽下放不下（实测「设置」按钮被裁掉）→ 收窄到 6/10
        themeBtn.setMargin(new Insets(2, 6, 2, 6));
        themeBtn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        settingsBtn.setMargin(new Insets(2, 6, 2, 6));
        settingsBtn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        // 按钮行：BoxLayout X + 3 段等宽 glue（原因见上方「彻底修复」注释）
        JPanel btnRow = new JPanel();
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnRow.add(Box.createHorizontalGlue());
        btnRow.add(themeBtn);
        btnRow.add(Box.createHorizontalGlue());
        btnRow.add(settingsBtn);
        btnRow.add(Box.createHorizontalGlue());
        infoPanel.add(btnRow);
        wrap.add(infoPanel, BorderLayout.SOUTH);
        refreshSaveDir();
        return wrap;
    }

    /**
     * 离屏探针专用（UiWinProbe --long）：塞填充行强制设备列表出竖直滚动条，
     * 供量条宽（10 vs 聊天区 12）。不碰 store / 设备数据。
     */
    void probePadDeviceList(int n) {
        for (int i = 0; i < n; i++) {
            JLabel filler = new JLabel("probe filler " + i);
            // 档位：常规 15（=发送文件按钮同档）
            filler.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            filler.setForeground(Ui.TEXT_SUB);
            deviceListPanel.add(filler);
        }
        deviceListPanel.revalidate();
    }

    /** 左栏底部按钮行左：「深色 / 浅色」主题切换按钮（2026-09-27 新增，点击即切换并持久化） */
    private JButton themeBtn;

    /**
     * 切换深色 / 浅色主题（左下角按钮触发）。
     *
     * <p>流程：写 Store（持久化）→ {@link Ui#applyDark}（换调色板 + UIManager + 整棵组件树
     * 旧色换新色）→ 重套输入框配色 → 刷设备列表/顶栏（自定义 paint 的部分 repaint 生效）。
     * 必须在 EDT 上（Swing 组件操作），按钮回调天然满足。
     */
    private void toggleTheme() {
        boolean toDark = !Ui.isDark();
        W.store.setDarkTheme(toDark);
        Ui.applyDark(toDark, W.frame.getContentPane());
        // 输入框的底色/边框是 Ui.field 烤进去的，重套一遍跟随新主题（监听有防叠加标记，不会重复挂）
        Ui.field(W.input);
        themeBtn.setText(toDark ? "切到浅色" : "切到深色");
        themeBtn.setToolTipText("点击切换到" + (toDark ? "浅色" : "深色") + "模式（当前：" + (toDark ? "深色" : "浅色") + "）");
        // 设备行 / 顶栏状态行是自绘或每次刷新时现设色的，重刷一遍让它们按新调色板重画
        refreshDevices();
        W.msgPanel.revalidate();
        W.msgPanel.repaint();
    }

    /** 左栏底部容器（dirLabel + 按钮行）：refreshSaveDir 用它取行实际内容宽算省略预算 */
    private JPanel infoPanel;
    /** 左栏底部上行：**当前接收（保存）目录路径**（单行居中、超长中间省略；设置页里可改；由 ChatWindow 的 5s 心跳跟随） */
    private final JLabel dirLabel = new JLabel(" ");
    /** 左栏底部按钮行右：「设置」按钮（2026-09-23 从左栏顶部挪下来） */
    private JButton settingsBtn;
    /** 上次画出来的目录文本：没变就不重写（避免每 5s 无谓重绘/闪烁） */
    private String dirCache;

    /**
     * 刷新左栏底部的接收目录：**只显示路径本身**，不加任何前缀文案。
     *
     * <p>排版（2026-09-27 用户定调）：**单行、居中**；放不下就对整条路径做
     * **中间省略**（头留盘符/根、尾留最后一级）。完整路径挂在工具提示里。
     */
    void refreshSaveDir() {
        if (W.store == null) {
            return;
        }
        final String dir = W.store.receivedDirText();
        SwingUtilities.invokeLater(() -> {
            if (dir.equals(dirCache)) {
                return;
            }
            dirCache = dir;
            // 路径独占整行：可用宽 = 行**实际**内容宽（面板宽 - 1px 分隔条 - 左右 padding(10+6)）
            // - 安全余量(8)；不再假定固定 200px，保证文字墨迹宽恒小于行宽、末字符不贴边。
            // 2026-09-28 注释更正：dirLabel 现在被**拉满整行**（见 buildDevicePanel 里的
            // setMaximumSize），文本在行内居中，不再被 BasicLabelUI 的 min/max 钉死在文字尺寸上。
            // 省略预算仍按**行**宽算，是为了保证墨迹宽 < 行宽、两侧各留出余量（拉满后 label 宽 = 行宽，
            // 按行宽算与按 label 宽算等价），不再是被迫为之。
            int rowW = (infoPanel != null && infoPanel.getWidth() > 0)
                    ? infoPanel.getWidth() - 16
                    : ChatWindow.DEVICE_PANEL_W - 17;
            FontMetrics fm = dirLabel.getFontMetrics(dirLabel.getFont());
            dirLabel.setText(middleEllipsis(dir, fm, rowW - 8));
            dirLabel.setToolTipText(dir);
        });
    }

    /** 添加 / 编辑设备（右键菜单与「＋设备」共用）。null = 新增一台设备 */
    void editPeer(final Store.Group group) {
        final boolean isNew = group == null;
        final String title = isNew ? "添加设备" : "编辑设备";
        final JDialog d = new JDialog(W.frame, title, true);
        // 自绘 chrome（必须在 setVisible 之前）；X = dispose（与「取消」同语义）
        WindowChrome.install(d, title, d::dispose);

        // 3 个有状态输入在这里创建并传给 buildDeviceForm：下方「保存」按钮要读它们
        // Ui.field：底色/文字/边框跟主题（深色 = #1B1C1F 底 + 近白字）。
        // 不套的话这两个框吃 LAF 默认白底，而 Ui.installLafColors 已把 TextField.foreground
        // 换成主题的 TEXT_MAIN（深色下近白）→ 白底近白字，2026-10-04 用户反馈"字看不见"的根因。
        final JTextField ipField = Ui.field(new JTextField());
        // 档位：常规 15（=发送文件按钮同档）
        ipField.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        final JTextField remarkField = Ui.field(new JTextField(isNew ? "" : group.remark));
        // 档位：常规 15（=发送文件按钮同档）
        remarkField.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        // 已有设备：点现有地址即切为该组当前地址
        final String[] pickIp = new String[]{isNew ? "" : group.curIp};

        d.add(buildDeviceForm(group, isNew, ipField, remarkField, pickIp), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        if (!isNew) {
            JButton del = Ui.secondary(new JButton("删除"));
            del.addActionListener(e -> {
                // 删整台设备（组内所有地址）
                for (String a : group.ips) {
                    W.store.removePeer(a);
                }
                W.store.setGroupRemark(group.key, "");
                refreshDevices();
                d.dispose();
            });
            btns.add(del);
        }
        JButton cancel = Ui.secondary(new JButton("取消"));
        cancel.addActionListener(e -> d.dispose());
        btns.add(cancel);

        JButton ok = Ui.primary(new JButton("保存"));
        ok.addActionListener(e -> {
            String ip = ipField.getText() == null ? "" : ipField.getText().trim();
            String remark = remarkField.getText() == null ? "" : remarkField.getText().trim();
            if (isNew) {
                if (ip.isEmpty()) {
                    JOptionPane.showMessageDialog(d, "IP 不能为空");
                    return;
                }
                probeThen(d, ok, ip, () -> {
                    W.store.upsertPeer(ip, remark, null, null, 0L);
                    refreshDevices();
                    d.dispose();
                });
            } else {
                // 只有真的新增了一条地址才探测；只改备注 / 换现有地址不探测
                final boolean newAddr = !ip.isEmpty() && !group.ips.contains(ip);
                Runnable apply = () -> {
                    W.store.setGroupRemark(group.key, remark);
                    if (newAddr) {
                        // 手动声明「这个地址也是这台设备」：绑定后聊天记录/备注直接沿用
                        W.store.upsertPeer(ip, null, group.key, null, 0L);
                        W.store.bindDevice(ip, group.key);
                    }
                    if (pickIp[0] != null && !pickIp[0].isEmpty() && !pickIp[0].equals(group.curIp)) {
                        W.switchGroupAddress(group.key, pickIp[0]);
                    }
                    refreshDevices();
                    d.dispose();
                };
                if (newAddr) {
                    probeThen(d, ok, ip, apply);
                } else {
                    apply.run();
                }
            }
        });
        btns.add(ok);
        d.add(btns, BorderLayout.SOUTH);

        // 高按内容首选（DialogChrome 的首选高已含 32px 标题栏）：地址条数多时写死 380 会把
        // 最后的提示行裁掉（2026-09-30），保底仍是原来的观感高度
        int need = d.getContentPane().getPreferredSize().height + 8;
        d.setSize(360, Math.max((isNew ? 240 : 380) + WindowChrome.DIALOG_TITLE_H, need));
        d.setLocationRelativeTo(W.frame);
        d.setVisible(true);
    }

    /**
     * 添加地址前的连通性探测（2026-10-30 用户定调：ping 3s 无响应就提示并放弃本次添加）。
     *
     * <p>判定 = 3s 预算内 **ICMP ping 或 应用端口 TCP** 任一通过：
     * 手机 App 没开时 ping 仍然通（地址本身没错，允许添加）；对方系统拦 ICMP 时，
     * 应用端口能建连就说明地址可用。两者都超时才按「地址错误或有防火墙」拦下。
     *
     * <p>探测放在后台线程：3s 全在 EDT 上等会把整个对话框冻住。
     * 探测期间「保存」置灰改字，避免连点叠出多个探测。
     */
    private void probeThen(final JDialog d, final JButton ok, final String ip, final Runnable onReachable) {
        final int port = W.store == null ? Protocol.DEFAULT_TCP_PORT : W.store.tcpPort();
        ok.setEnabled(false);
        ok.setText("检测中…");
        Thread t = new Thread(() -> {
            final boolean reach = addrReachable(ip, port, PROBE_MS);
            SwingUtilities.invokeLater(() -> {
                // 探测期间用户可能已经「取消 / 关窗」：此时什么都不做，更不能把地址加进去
                if (!d.isDisplayable()) {
                    return;
                }
                ok.setText("保存");
                ok.setEnabled(true);
                if (reach) {
                    onReachable.run();
                    return;
                }
                Log.w("添加地址 " + ip + " 被拦下：" + PROBE_MS + "ms 内 ping 与应用端口 " + port + " 均无响应");
                Object[] onlyOk = {"确定"};
                JOptionPane.showOptionDialog(d,
                        "目标地址错误或有防火墙，可以尝试对方连接本机地址或关闭ipv6防火墙",
                        "无法连接该地址", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE, null, onlyOk, onlyOk[0]);
            });
        }, "addr-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 添加设备时的探测预算（用户定调 3s） */
    private static final int PROBE_MS = 3000;

    /**
     * 地址可达性探测：ICMP 与 TCP 两条路**并发**试，任一成功即返回 true，最多等 {@code budgetMs}。
     * 两条都是 daemon 线程，窗口关掉后不会拖着进程不退。
     */
    static boolean addrReachable(String host, int tcpPort, int budgetMs) {
        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread icmp = new Thread(() -> {
            try {
                if (java.net.InetAddress.getByName(host).isReachable(budgetMs)) {
                    ok.set(true);
                }
            } catch (Throwable ignore) {
                // 解析失败 / 权限不足：按这条路不可达处理，由 TCP 那条决定
            }
        }, "addr-probe-icmp");
        Thread tcp = new Thread(() -> {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, tcpPort), budgetMs);
                ok.set(true);
            } catch (Throwable ignore) {
                // 拒绝 / 超时 / 解析失败同样按不可达处理
            }
        }, "addr-probe-tcp");
        icmp.setDaemon(true);
        tcp.setDaemon(true);
        icmp.start();
        tcp.start();
        long deadline = System.currentTimeMillis() + budgetMs;
        try {
            icmp.join(budgetMs);
            long left = deadline - System.currentTimeMillis();
            if (left > 0) {
                tcp.join(left);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    /**
     * 设备添加/编辑对话框内容（表单：地址 + 备注 + 现有地址 + 提示）。
     * 抽成独立静态方法：离屏探针（tools/ChromeProbe）可单独调用它，
     * 套上 DialogChrome 独立验证，不必启动整个 ChatWindow。
     */
    static JComponent buildDeviceForm(Store.Group group, boolean isNew,
                                      JTextField ipField, JTextField remarkField, String[] pickIp) {
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 6));
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        form.add(formLabel(isNew ? "IP 地址" : "添加地址（也是这台设备）"));
        form.add(ipField);

        form.add(formLabel("备注名（对该设备，仅本机可见）"));
        form.add(remarkField);

        // 已有设备：列出现有地址，点一个即切为该组的当前地址
        if (!isNew) {
            form.add(formLabel("现有地址"));
            JPanel ipBox = new JPanel();
            ipBox.setLayout(new BoxLayout(ipBox, BoxLayout.Y_AXIS));
            for (final String a : group.ips) {
                final JButton b = new JButton((a.equals(pickIp[0]) ? "● " : "○ ") + a);
                // 档位：常规 15（=发送文件按钮同档）
                b.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
                b.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
                b.setMargin(new java.awt.Insets(2, 6, 2, 6));
                b.setToolTipText("点击 = 把该设备当前使用的地址切到这里");
                b.addActionListener(e -> {
                    pickIp[0] = a;
                    // 重画所有按钮前缀（● 当前 / ○ 其它）
                    for (Component c2 : ipBox.getComponents()) {
                        if (c2 instanceof JButton) {
                            JButton bb = (JButton) c2;
                            Object ipOfBtn = bb.getClientProperty("ip");
                            if (ipOfBtn != null) {
                                bb.setText((ipOfBtn.equals(pickIp[0]) ? "● " : "○ ") + ipOfBtn);
                            }
                        }
                    }
                });
                b.putClientProperty("ip", a);
                ipBox.add(b);
            }
            form.add(ipBox);
        }

        // 提示文字：以前在 CSS 里写 font-size:10px —— 宋体 10px 下笔画并成墨块，看着像假粗且
        // 字形变形（2026-09-30 用户反馈）。字号走 FS_MIN 档，颜色用 TEXT_SUB 跟主题（原来写死 #666）。
        JLabel tip = new JLabel("<html>备注留空时显示对方广播名。<br>同一设备的多个地址共用备注与聊天记录。</html>");
        tip.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_MIN));
        tip.setForeground(Ui.TEXT_SUB);
        form.add(tip);
        return form;
    }

    /** 表单标签（与 ChatWindow.label 同风格；static 以便 buildDeviceForm 离屏独立调用） */
    private static JLabel formLabel(String text) {
        JLabel l = new JLabel(text);
        // 档位：常规 15（=发送文件按钮同档）
        l.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        return l;
    }

    /** 设备列表里的一行：自绘（状态点 + 名字 + IP），整体响应单击/右键 */
    /**
     * 设备列表的一行 = **一台设备**（不是一条地址）。
     *
     * 同一台设备（同一 deviceId）的多个地址合成一行：副标题显示当前使用的地址，
     * 多地址时追加「N 个地址」；切换地址在顶栏的地址菜单里（或右键「修改备注 / 地址」）。
     */
    private final class DeviceRow extends JPanel {
        private final Store.Group group;
        private final boolean connected;
        /** 是否是「当前对话」的那台（聊天区正在显示它的记录） */
        private final boolean current;

        DeviceRow(final Store.Group group, boolean connected, boolean current) {
            this.group = group;
            this.connected = connected;
            this.current = current;
            setOpaque(true);
            setPreferredSize(new Dimension(ChatWindow.DEVICE_PANEL_W - 18, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setToolTipText("<html>" + group.curIp
                    + (group.alt ? "<br>同一 IP 上的另一台设备 — 点击可切换" : "")
                    + (group.multi() ? "<br>" + group.ips.size() + " 个地址" : "")
                    + (group.guestIps.isEmpty() ? ""
                            : "<br>共用地址（现属别的设备）: "
                                    + String.join("、", group.guestIps))
                    + (connected ? "<br>已连接" : "<br>未连接 — 单击连接")
                    + (current ? "<br>当前对话设备" : "") + "</html>");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                        if (group.alt) {
                            // 同 IP 多设备（2026-10-05）：先弹 IP 冲突确认框，
                            // 确认后才断开旧连接、把该地址的身份切过来
                            W.switchConflictDevice(group);
                            return;
                        }
                        // 点一次 = 切为当前对话 + （没连才）连一次；失败不自动重试
                        W.switchConversation(group.curIp, group.displayName());
                        if (W.link.isDeviceConnected(group.curIp)) {
                            W.setSub("当前对话：" + group.displayName() + "（" + group.curIp + "）");
                        } else {
                            W.setSub("正在连接 " + group.displayName() + "（" + group.curIp + "）——失败就再点一次");
                        }
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    popup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    popup(e);
                }

                private void popup(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        return;
                    }
                    JPopupMenu pm = new JPopupMenu();
                    // 档位：常规 15（=发送文件按钮同档）
                    Font f = new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD);
                    JMenuItem conn = new JMenuItem(connectItemText());
                    conn.setFont(f);
                    conn.addActionListener(a -> {
                        if (group.alt) {
                            // 别名行没有自己的地址：只能走「冲突确认 → 断开旧连接 → 切换」
                            W.switchConflictDevice(group);
                        } else {
                            W.switchConversation(group.curIp, group.displayName());
                        }
                        refreshDevices();
                    });
                    JMenuItem edit = new JMenuItem("修改备注 / 地址…");
                    edit.setFont(f);
                    edit.addActionListener(a -> editPeer(group));
                    pm.add(conn);
                    // 别名行不是一条真正的地址（它只是「同一 IP 上还有另一台设备」），
                    // 不能改备注 / 地址，也不该借着「删除」把那个地址本身删掉 —— 但它必须能被删：
                    // 例如同一台手机换了 deviceId 之后留下的旧身份残行。删除 = 忘记这台设备
                    // + 记进忽略表（否则对方 2s 后一次广播又把它加回来，表现为「删不掉」）。
                    if (group.alt) {
                        JMenuItem forget = new JMenuItem("删除（不再显示这台设备）");
                        forget.setFont(f);
                        forget.addActionListener(a -> {
                            W.store.forgetDev(group.key);
                            W.setSub("已不再显示 " + group.displayName() + "（" + group.curIp + "）");
                            refreshDevices();
                        });
                        pm.addSeparator();
                        pm.add(forget);
                    } else {
                        pm.add(edit);
                    }
                    // 多地址：右键直接给出地址切换子菜单（同组的另一个地址一按即切）
                    if (group.multi()) {
                        JMenu addr = new JMenu("切换地址");
                        addr.setFont(f);
                        for (final String a : group.ips) {
                            JMenuItem mi = new JMenuItem(
                                    (a.equals(group.curIp) ? "● " : "○ ") + a
                                            + (W.link.isDeviceConnected(a) ? "  已连接" : ""));
                            mi.setFont(f);
                            mi.addActionListener(x -> W.switchGroupAddress(group.key, a));
                            addr.add(mi);
                        }
                        pm.add(addr);
                    }
                    JMenuItem del = new JMenuItem("删除");
                    del.setFont(f);
                    del.addActionListener(a -> {
                        // 删整台设备（组内全部地址）
                        for (String x : group.ips) {
                            W.store.removePeer(x);
                        }
                        W.store.setGroupRemark(group.key, "");
                        refreshDevices();
                    });
                    if (!group.alt) {
                        pm.addSeparator();
                        pm.add(del);
                    }
                    // 收尾套一遍当前主题（含「切换地址」JMenu 子菜单，由 theme 递归覆盖）
                    Ui.theme(pm);
                    pm.show(DeviceRow.this, e.getX(), e.getY());
                }

                /** 已连接就是「切换到该对话」，没连才是「连接」（失败不重试，再点一次即可） */
                private String connectItemText() {
                    if (group.alt) {
                        return "切换到该设备（同 IP 冲突）";
                    }
                    String base = connected ? "切换到该对话" : "连接";
                    return current ? base + "（当前）" : base;
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 文字必须显式关 AA：本方法下面直接 drawString，而 KEY_ANTIALIASING=ON 会被 Java2D
            // 拿去兜底渲染文字（实测 15px 宋体多出 545 个灰阶像素 = 发糊）。整窗重绘时上游
            // WindowChrome 已把 TEXT_AA 钉成 OFF，但局部重绘（hover/刷新行）走的是默认提示，
            // 两条路径不一致 → 名字一会儿清楚一会儿糊（2026-09-30，口径见 Fonts 类注释）。
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            // 当前对话 = 淡蓝底 + 左侧色条；已连接（非当前）也淡蓝但不带色条
            if (current) {
                g2.setColor(Ui.ROW_CURRENT);
            } else if (connected) {
                g2.setColor(Ui.ROW_CONNECTED);
            } else {
                g2.setColor(Ui.SURFACE);
            }
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (current) {
                g2.setColor(new Color(0x15, 0x65, 0xC0));
                g2.fillRect(0, 0, 3, getHeight());
            }

            g2.setColor(connected ? Ui.DOT_ONLINE : Ui.DOT_OFFLINE);
            g2.fillOval(10, 13, 8, 8);

            // 档位：常规 15（=发送文件按钮同档）；当前行不假粗（宋体无粗体字面，见 Fonts），
            // 由 ROW_CURRENT 底色 + 左侧蓝条标识
            g2.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            g2.setColor(Ui.TEXT_MAIN);
            g2.drawString(ellipsize(g2, group.displayName(), ChatWindow.DEVICE_PANEL_W - 44), 24, 22);

            // 副标题：当前地址（+ 地址数量）。与显示名相同（没备注也没广播名）时省略
            String sub = group.curIp;
            if (!sub.equals(group.displayName())) {
                // 档位：最小 13（=IP 悬停提示同档）
                g2.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_MIN));
                g2.setColor(Ui.TEXT_SUB);
                g2.drawString(middleEllipsis(sub, g2.getFontMetrics(), ChatWindow.DEVICE_PANEL_W - 44), 24, 37);
            }
            g2.dispose();
        }
    }

    static String ellipsize(Graphics2D g2, String s, int maxPx) {
        if (s == null) {
            return "";
        }
        FontMetrics fm = g2.getFontMetrics();
        int budget = inkBudget(maxPx);
        if (fm.stringWidth(s) <= budget) {
            return s;
        }
        String ell = "…";
        int i = s.length();
        while (i > 0 && fm.stringWidth(s.substring(0, i) + ell) > budget) {
            i--;
        }
        return s.substring(0, Math.max(0, i)) + ell;
    }
    // ==================== 左栏：本机地址（点击复制） ====================

    /** 地址行字体：等宽，IPv6 的 8 组对齐好认（Windows 自带 Consolas；缺了会回退默认字体）。
     *  字号 13 = S2 映射（旧 11 @125% → 100% 下 13，2026-09-29）；= Ui.FS_MIN，刻意不跟档位联动 */
    private static final Font IP_FONT = new Font("Consolas", Font.PLAIN, 13);
    // IP 文字色不再烤成 static final Color（主题切换后老 label 会停在旧色）——
    // 一律用 Ui.IP_TEXT / Ui.IP_CAPTION 现取；已建 label 的旧色由 Ui.applyDark 的重着色遍历替换。
    /** 可靠的离屏 FontMetrics：组件未显示时 getFontMetrics 可能不准，
     *  从 BufferedImage 的 Graphics 获取，保证和实际渲染一致 */
    private static final FontMetrics IP_FM;
    static {
        BufferedImage probeImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probeImg.createGraphics();
        IP_FM = pg.getFontMetrics(IP_FONT);
        pg.dispose();
    }

    /** IP 文字最大宽度：栏宽 - head左右边距(12) - 安全余量(6) */
    private static final int IP_TEXT_W = ChatWindow.DEVICE_PANEL_W - 18;

    private JPanel buildLocalIpBox() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        refreshLocalIps();
        return p;
    }

    /**
     * 重建「本机ip：」块：标题居中，下面**每行一个地址**，点一下复制。
     *
     * <p>太长的地址（IPv6 能到 39 字符，200px 栏宽放不下）走**中间省略** —— 两端都留着，
     * 网段前缀和后缀仍然认得出；**完整地址在工具提示里**，点下去复制的也是完整地址。
     *
     * <p>必须 EDT 调用（Swing 组件操作）；内容与上次相同直接返回，不做无谓重绘。
     */
    void refreshLocalIps() {
        if (localIpPanel == null) {
            return;
        }
        final java.util.List<String> ips = LocalIps.list();
        if (ips.equals(localIpCache)) {
            return;
        }
        localIpCache = ips;
        localIpPanel.removeAll();

        // 档位：最小 13（=IP 悬停提示同档）
        localIpPanel.add(ipRow("本机ip：", new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_MIN), Ui.IP_CAPTION));

        if (ips.isEmpty()) {
            // 档位：最小 13（=IP 悬停提示同档）
            localIpPanel.add(ipRow("（暂无）", new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_MIN), Ui.IP_CAPTION));
        } else {
            for (final String ip : ips) {
                JLabel l = ipRow(ip, IP_FONT, Ui.IP_TEXT);
                // 中间省略：用**该 label 真实字体的 FontMetrics**量宽度（离屏 IP_FM 在 Consolas 下
                // 比真实渲染宽约 12px，会算错）；文字目标宽 = 行宽 - 两侧余量(20)，保证居中后
                // 头尾都不贴边、末字符抗锯齿不被裁（IPv4 很短，不会触发）。
                FontMetrics realFm = l.getFontMetrics(IP_FONT);
                int targetW = (ChatWindow.DEVICE_PANEL_W - 12) - 20;
                l.setText(middleEllipsis(ip, realFm, targetW));
                l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                l.setToolTipText("点击复制：" + ip);
                l.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        copyLocalIp(ip);
                    }
                });
                localIpPanel.add(l);
            }
        }
        localIpPanel.revalidate();
        localIpPanel.repaint();
    }

    /**
     * 造一行地址（标题行与地址行共用）：**每行都撑满栏宽再在行内居中**。
     *
     * <p>不这么做的话 BoxLayout 会按「最宽那行的轴心」对齐所有行 —— 实测窄行会整体左偏
     * 5~6px（栏宽 199、最宽行 168 时，居中中心落在 84 而不是 89.5），肉眼看得出没对齐。
     * 统一宽度后所有行的 x 都是 0，行内 CENTER 让它们共用同一条中心轴。
     */
    static JLabel ipRow(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        l.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        // 每行固定为内容区宽度（栏宽-左右边距）：文字在行内 CENTER，
        // 行宽始终比省略后的文字宽 20px，末字符不贴边、不被裁。
        Dimension row = new Dimension(ChatWindow.DEVICE_PANEL_W - 12, 20);
        l.setPreferredSize(row);
        l.setMinimumSize(row);
        l.setMaximumSize(row);
        return l;
    }

    /**
     * 复制本机地址到系统剪贴板。
     *
     * <p>不走 {@code Clip.setClipboard}（那条路会置 suppressSha1 = 本程序写入、不再回推对端）：
     * 这里恰恰**要让剪贴板监听把地址推给手机** —— 用户在手机上「＋设备」里直接粘贴即可，
     * 这正是「复制的 ip 需要可以直接用于添加 ip」的用法。防回环由 Clip 自己的 sha1 去重负责。
     */
    void copyLocalIp(String ip) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(ip), null);
            W.setSub("已复制：" + ip + (W.link.isConnected() ? "（已同步给手机）" : ""));
        } catch (Throwable t) {
            W.setSub("复制失败：" + ip + " —— " + t);
        }
    }

    /**
     * 字制度量 → 实际渲染墨迹的安全系数。
     *
     * <p>FontMetrics.stringWidth 按**整数字符步进**累加，而实际渲染（抗锯齿 + 分数步进）比它宽
     * 5~13%（Consolas 11px 实测 25 字符串：度量 140px、渲染 147px @100%；157px @125% DPI）。
     * 所有「按度量裁字符串」的入口都必须先除以这个系数，否则省略后的头尾仍会超出容器被裁
     * （2026-09-27 用户实测：本机 ip 块 IPv6 两端被裁、顶栏「● 已连接」尾字被裁）。
     */
    static final double INK_SAFETY = 1.12;

    /** 按安全系数折算后的实际可用墨迹预算 */
    static int inkBudget(int maxPx) {
        return (int) (maxPx / INK_SAFETY);
    }

    /**
     * 中间省略：与安卓端 {@code middleShorten} 同一策略 —— 保留头尾、中间换成「…」。
     * 英文等宽字体下按像素量宽度，逐字符收紧直到放得下。
     * 度量先除以 {@link #INK_SAFETY}（见上，渲染墨迹比 stringWidth 宽）。
     */
    static String middleEllipsis(String s, FontMetrics fm, int maxPx) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int budget = inkBudget(maxPx);
        if (fm.stringWidth(s) <= budget) {
            return s;
        }
        final String ell = "…";
        for (int keep = s.length() - 1; keep > 4; keep--) {
            int tail = keep / 2;
            int head = keep - tail;
            String cand = s.substring(0, head) + ell + s.substring(s.length() - tail);
            if (fm.stringWidth(cand) <= budget) {
                return cand;
            }
        }
        return s.substring(0, Math.max(1, s.length() / 2)) + ell;
    }

    /** JLabel 不自动折行：长信息（候选 IP / 接收目录）用 HTML 定宽包裹；& < > 必须转义 */
    static String wrapHtml(String s, int widthPx) {
        String t = s == null ? "" : s;
        t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body style='width:" + widthPx + "px'>" + t + "</body></html>";
    }

    /** 左栏顶部的状态行（来自 PcMain 的连接状态文案 / W.stateShort()），定宽换行避免撑破 200px 栏宽 */
    void setDeviceState(String text) {
        deviceStateLabel.setText(wrapHtml(text, ChatWindow.DEVICE_PANEL_W - 44));
    }

    /**
     * 重建设备列表（连接状态 / 设备增删改后调用）。
     *
     * **每台设备各自独立判断连接状态**：连着的多台会同时亮绿点，互不影响 ——
     * 这正是「多设备独立长连接」在界面上最直观的体现。另外单独标出「当前对话」那台。
     */
    public void refreshDevices() {
        SwingUtilities.invokeLater(() -> {
            deviceListPanel.removeAll();
            // 按**设备组**列：同一台设备（同一 deviceId）的多个地址合成一条
            java.util.List<Store.Group> groups = (W.store == null)
                    ? new ArrayList<Store.Group>() : W.store.groups();
            if (groups.isEmpty()) {
                JLabel empty = new JLabel("<html>还没有设备。<br>点「＋设备」添加，<br>或等自动发现。</html>");
                // 档位：常规 15（=发送文件按钮同档）
                empty.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
                empty.setForeground(Ui.TEXT_SUB);
                empty.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                empty.setAlignmentX(Component.LEFT_ALIGNMENT);
                deviceListPanel.add(empty);
            } else {
                for (Store.Group g : groups) {
                    boolean online = false;
                    if (g.alt) {
                        // 别名行：看**这台设备身份**自己的会话 —— 那个 IP 上连着的可能是别人
                        online = W.link.isPeerConnected(g.key);
                    } else {
                        for (String a : g.ips) {
                            if (W.link.isDeviceConnected(a)) {
                                online = true;
                                break;
                            }
                        }
                    }
                    boolean cur = W.shownIp != null && !W.shownIp.isEmpty()
                            && W.convKey(W.shownIp).equals(g.key);
                    deviceListPanel.add(new DeviceRow(g, online, cur));
                }
            }
            deviceListPanel.add(Box.createVerticalGlue());
            setDeviceState(W.stateShort());
            refreshLocalIps();
            refreshSaveDir();
            W.refreshHeader();
            W.reopenConversationIfKeyChanged();
            deviceListPanel.revalidate();
            deviceListPanel.repaint();
        });
    }

}
