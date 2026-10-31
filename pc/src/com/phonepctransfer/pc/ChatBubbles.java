package com.phonepctransfer.pc;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

// ==================================================================
// 聊天气泡渲染（拆分自 ChatWindow.java）：气泡布局、文本/文件气泡、
// 缩略图、手势（双击/右键）、拒绝重发行、圆角面板。持 ChatWindow 引用。
// ==================================================================

final class ChatBubbles {

    final ChatWindow W;

    ChatBubbles(ChatWindow W) {
        this.W = W;
    }

    // 原先这里有一个 LINK_COLOR（气泡里小按钮的文字色）—— 常驻按钮撤掉后它没有使用者了，
    // 一并删除。以后若再加气泡内文字链接，色值仍取 Material primary 同源的 #1565C0。

    // ==================== 传输速率刷新（2026-09-22）====================
    //
    // 气泡里的进度条文案是「87%  0.95M/s」，速率每 500ms 就变一次；而气泡本身只在**消息更新**
    // 时才重建（进度按整数百分比上报，10GB 文件 1% = 105MB，好几秒才来一次）。
    // 所以要一个只刷文案的轻量心跳：登记在用中的进度条，500ms 覆盖一次 setString，
    // 速率过期（暂停/卡住/传完）就退回只剩百分比。
    //
    // ⚠ 只在有在传的气泡时才起 Timer，全部传完自动 stop —— 不做常驻轮询。
    // ⚠ javax.swing.Timer 回调天然在 EDT 上，改组件文案安全。

    /** mid → 正在传输中的进度条（气泡被重建/移除时用 parent == null 判废并注销） */
    private static final java.util.Map<String, JProgressBar> LIVE_BARS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static javax.swing.Timer speedTicker;

    private static synchronized void ensureSpeedTicker() {
        if (speedTicker != null && speedTicker.isRunning()) {
            return;
        }
        speedTicker = new javax.swing.Timer(500, e -> refreshBarSpeeds());
        speedTicker.start();
    }

    private static void registerBar(String mid, JProgressBar pb) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        LIVE_BARS.put(mid, pb);
        ensureSpeedTicker();
    }

    private static void refreshBarSpeeds() {
        if (LIVE_BARS.isEmpty()) {
            if (speedTicker != null) {
                speedTicker.stop();
            }
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, JProgressBar>> it = LIVE_BARS.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, JProgressBar> en = it.next();
            JProgressBar pb = en.getValue();
            // 气泡被重建（同一条消息换新组件）或整条被删 → 老组件已脱离容器，注销
            if (pb == null || pb.getParent() == null) {
                it.remove();
                continue;
            }
            pb.setString(speedLabel(en.getKey(), pb.getValue()));
        }
    }

    /** 进度条文案：`87%` +（速率新鲜时）`  0.95M/s`（与安卓端气泡同口径） */
    private static String speedLabel(String mid, int pct) {
        String sp = Speeds.text(Speeds.bpsOf(mid));
        return sp.isEmpty() ? pct + "%" : pct + "%  " + sp;
    }

    void addBubble(Msg m) {
        W.mids.add(m.mid);
        W.bubbleMsgOf.put(m.mid, m);
        JComponent c = bubble(m, false);
        W.comps.add(c);
        W.msgPanel.add(c);
        W.msgPanel.revalidate();
    }

    void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = W.scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // ==================== 气泡 ====================

    JComponent bubble(Msg m, boolean compact) {
        boolean self = m.isSelf();

        // 覆盖 getMaximumSize：BoxLayout(Y_AXIS) 会按最大尺寸把多余视口高度分给每行，
        // 导致消息被撑开一整屏；限制「最大高度=内容高度」后每行紧贴排列。
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        // 相邻两条消息之间留出约两行文字高度的空隙（原来只有 2px，视觉上挤在一起）
        row.setBorder(BorderFactory.createEmptyBorder(6, 0, 14, 0));

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);

        JLabel meta = new JLabel((self ? "我" : peerName(m.peerIp)) + "  " + m.timeText() + m.statusSuffix());
        meta.setName("FileBubbleMeta"); // 探针白名单句柄（T06）：署名行
        // 档位：常规 15（=发送文件按钮同档）
        meta.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        meta.setForeground(Ui.BUBBLE_META);
        meta.setAlignmentX(self ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        // 为什么补余量（2026-09-28，本文件所有 padH 调用同此口径，其余处不再重复）：
        // JLabel 首选宽 = FontMetrics.stringWidth，是**零容差**的 advance 之和；而实测抗锯齿渲染的
        // 真实墨迹比它宽 5~13%（同源常量 ChatDevices.INK_SAFETY = 1.12），组件绘制又被硬裁到自身
        // bounds，于是署名行末尾的状态字（"已发送" 的 "送"）会被削掉右缘。留 12px 余量即按此口径兜底。
        // ⚠ padH 必须在本行**文本与字体都定好之后**调用：它按当前 pref 宽一次性加固定像素。
        padH(meta, 12);
        col.add(meta);

        JComponent body = Msg.KIND_FILE.equals(m.kind)
                ? fileBubble(m, self, compact)
                : textBubble(m, self, compact);
        col.add(body);

        row.add(col, self ? BorderLayout.EAST : BorderLayout.WEST);
        // 气泡手势：**左键短按 = 打开内容**（图片全屏查看、文件交给系统打开）、
        // **右键 = 菜单（打开/复制/删除）**。删除只能从右键菜单进（2026-10-15 用户定调）：
        // 原来还有一条「左键长按 600ms = 直接删除」，但它和「按住拖动划选气泡文字」是同一个动作，
        // 划词选中一超过 600ms 就弹出删除确认框，长按删除这条已整条撤掉。
        //
        // ⚠ 手柄**只挂气泡本体 body**（2026-09-21 修正）：以前挂的是整行 `row` ——
        // 它是满宽的 {@code BorderLayout} 容器，气泡靠 EAST/WEST 贴边，于是行内另一侧那一大片
        // **空白**也带着手柄，点在空白处照样弹菜单/触发删除（用户报就是这个）。
        // 改挂 body 后：署名行、气泡旁边的留白、行上下 6/14px 的间距都不再响应，只有气泡本身可点。
        // ⚠ Swing 鼠标事件**不冒泡**（实测 2026-09-16）→ 必须在 body 内部递归挂到每个子组件上。
        attachBubbleGestures(body, m);
        return row;
    }

    /**
     * 把气泡手势监听递归挂到组件树的每个节点上（挂前先清掉旧的，防重建叠加）。
     *
     * <p>**按钮上不挂**：气泡里的小按钮（取消发送、数据流量重试叹号）有自己的 ActionListener，
     * 而我们挂的是 JComponent 级 MouseListener —— Swing 不消费事件，同一个组件上的两套监听会
     * **同时触发**（点「重试」会顺带执行「短按打开文件」）。按钮自己管点击，跳过整棵子树。
     */
    void attachBubbleGestures(Component c, Msg m) {
        if (c instanceof javax.swing.AbstractButton) {
            return;
        }
        for (java.awt.event.MouseListener ml : c.getMouseListeners()) {
            if (ml instanceof BubbleGestureListener) {
                c.removeMouseListener(ml);
            }
        }
        c.addMouseListener(new BubbleGestureListener(m));
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                attachBubbleGestures(child, m);
            }
        }
    }

    /**
     * 气泡手势监听（可识别类型，重建气泡时能精准摘除旧监听）：
     * 左键交给气泡本身（文本划选 / 松开打开内容），删除只从右键菜单进。
     *
     * <p>左键不再有长按计时：按住拖动正是划选文字的手势，两者撞车。
     */
    private final class BubbleGestureListener extends java.awt.event.MouseAdapter {
        private final Msg m;

        BubbleGestureListener(Msg m) {
            this.m = m;
        }

        @Override
        public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
                showBubblePopup(e, m);
            }
        }

        @Override
        public void mouseReleased(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
                showBubblePopup(e, m);
                return;
            }
            if (SwingUtilities.isLeftMouseButton(e)) {
                openBubbleContent(m);
            }
        }
    }

    /**
     * 气泡右键菜单（2026-09-21 用户定调）：气泡上不再常驻任何按钮，功能全部收进这个小窗。
     *
     * <p>**文案一律中文**（用户 2026-09-21 明确要求，覆盖「PC 新文案一律英文」那条）——
     * 这是唯一一处中文的功能菜单（「展开全文 / 取消发送」本来就是中文）。
     *
     * <p>菜单项按消息类型与**当前可用性**现算，不做置灰：
     * <ul>
     *   <li>文本：复制文本（复制的是**完整原文**，不受气泡里「展开全文」的折叠影响）</li>
     *   <li>图片（已落盘且非传输中/失败）：查看大图 / 复制图片 / 在文件夹中显示</li>
     *   <li>普通文件（同上前提）：打开文件 / 在文件夹中显示</li>
     *   <li>文件夹（收到且已落盘）：在文件夹中显示</li>
     *   <li>任何一条都有：删除（文本/图片会同步通知在线对端，见 v2.4）</li>
     * </ul>
     * 传输中（progress 0..99）与 FAILED 的文件**不给**打开/复制/定位 —— 本地文件是不完整的，
     * 复制出去只会得到半张图（原「常驻按钮」版本同口径，只是从置灰改成不出现）。
     */
    void showBubblePopup(java.awt.event.MouseEvent e, Msg m) {
        javax.swing.JPopupMenu pm = buildBubbleMenu(m);
        pm.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * 组装气泡右键菜单（**只组装、不弹**：离屏自检 `tools/BubbleProbe.java` 要把它画进 PNG 核对文案）。
     * 菜单项规则见 {@link #showBubblePopup}。
     */
    javax.swing.JPopupMenu buildBubbleMenu(Msg m) {
        final boolean self = m.isSelf();
        javax.swing.JPopupMenu pm = new javax.swing.JPopupMenu();

        if (Msg.KIND_FILE.equals(m.kind)) {
            final File local = localFileOf(m);
            if (m.isDir) {
                if (!self && m.filePath != null && !m.filePath.isEmpty()) {
                    File dir = local;
                    if (dir != null && dir.exists()) {
                        menuItem(pm, "在文件夹中显示", () -> showInFolder(dir));
                    }
                }
            } else if (fileReady(m, local)) {
                final boolean isImage = isImageName(m.fileName);
                if (isImage) {
                    menuItem(pm, "查看大图", () -> ImageViewer.show(W.frame, local));
                    menuItem(pm, "复制图片", () -> copyImageToClipboard(local));
                } else {
                    menuItem(pm, "打开文件", () -> openLocalFile(local));
                }
                menuItem(pm, "在文件夹中显示", () -> showInFolder(local));
            }
        } else if (Msg.KIND_TEXT.equals(m.kind) && m.text != null && !m.text.isEmpty()) {
            menuItem(pm, "复制文本", () -> copyTextToClipboard(m.text));
        }

        if (pm.getComponentCount() > 0) {
            pm.addSeparator();
        }
        menuItem(pm, "删除", () -> deleteMessage(m));
        // 收尾套一遍当前主题：菜单是每次右键新建，UIManager 已能覆盖；
        // 显式过一遍是为挡住 LAF 差异 + 以后若有人把菜单改成缓存复用，不至于踩根因③。
        return Ui.theme(pm);
    }

    /** 菜单项（中文文案；与气泡手势同源，构造完即挂好监听） */
    private void menuItem(javax.swing.JPopupMenu pm, String text, final Runnable action) {
        javax.swing.JMenuItem it = new javax.swing.JMenuItem(text);
        it.addActionListener(ev -> action.run());
        pm.add(it);
    }

    /**
     * 这条文件消息现在能不能「打开 / 复制 / 定位」：本地文件真的在、
     * 而且不是「正在传输（progress 0..99）」「已失败」这两个中间态。
     */
    static boolean fileReady(Msg m, File local) {
        return local != null && local.isFile()
                && !(m.progress >= 0 && m.progress < 100)
                && !Msg.ST_FAILED.equals(m.status);
    }

    /** 气泡短按：打开对应内容——图片全屏查看、文件交给系统打开；文本/文件夹无动作 */
    void openBubbleContent(Msg m) {
        if (!Msg.KIND_FILE.equals(m.kind) || m.isDir) {
            return;
        }
        File local = localFileOf(m);
        if (local == null || !local.isFile()) {
            return;
        }
        if (isImageName(m.fileName)) {
            ImageViewer.show(W.frame, local);
        } else {
            openLocalFile(local);
        }
    }

    /**
     * 删除一条消息（右键菜单「删除」，v2.4 删除同步）。
     *
     * <p>Link 负责清历史/待发、（符合条件时）通知在线对端；这里只做确认与文案定调。
     *
     * <p>**只有「文本 + 图片」发 msg_del**（协议 §4.7 与安卓端 `AppCore.deleteMessage` 同口径）：
     * 文件/文件夹的删除只影响本机记录 —— 它们本来就不参与 hsync，不会被补回来，
     * 发给对端反而会让对端删掉一条不该删的记录（旧代码对所有 kind 都发，是偏差，已对齐）。
     */
    void deleteMessage(Msg m) {
        final boolean syncable = syncableKind(m);
        int opt = JOptionPane.showConfirmDialog(W.frame,
                syncable
                        ? "删除这条聊天记录？对方在线时会同步删除。"
                        : "删除这条记录？\n（文件 / 文件夹的删除只影响本机，已落盘的文件不会被删）",
                "删除消息", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        String ip = (m.peerIp == null || m.peerIp.isEmpty()) ? W.shownIp : m.peerIp;
        W.link.deleteMessage(ip, m.mid, syncable);
    }

    /**
     * 这条消息是否参与「删除同步」（msg_del）：**文本、以及非文件夹的图片**。
     * 与协议 §4.7 的 hsync 口径完全一致（参与对账的也就是这两类）。
     */
    static boolean syncableKind(Msg m) {
        if (m == null) {
            return false;
        }
        if (Msg.KIND_TEXT.equals(m.kind)) {
            return true;
        }
        return Msg.KIND_FILE.equals(m.kind) && !m.isDir && isImageName(m.fileName);
    }

    /**
     * 气泡署名：按消息**自带的对端 IP** 取名字，而不是按「当前连接的是谁」。
     * 这样即使中途切换过对话设备，旧消息的署名也不会被张冠李戴。
     */
    String peerName(String peerIp) {
        if (peerIp != null && !peerIp.isEmpty()) {
            if (peerIp.equals(W.link.selectedIp())) {
                String l = W.link.selectedLabel();
                if (l != null && !l.isEmpty()) {
                    return l;
                }
            }
            // 备注挂在**设备**上：同组任何地址都得到同一个名字（与气泡署名口径一致）
            if (W.store != null) {
                Store.Group g = W.store.groupOfIp(peerIp);
                if (g != null && g.displayName() != null && !g.displayName().isEmpty()) {
                    return g.displayName();
                }
            }
            return peerIp;
        }
        String p = W.link.selectedLabel();
        return (p == null || p.isEmpty()) ? "手机" : p;
    }

    JComponent textBubble(Msg m, boolean self, boolean compact) {
        final boolean truncated = Texts.isTruncated(m.text);
        final JTextArea area = new JTextArea(Texts.headTail(m.text, false));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        // 档位：常规 15（基准行，=发送文件按钮同档）
        area.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        // 气泡正文颜色跟主题（浅色近黑 / 深色白字，2026-09-27）；显式设色才能在
        // 主题切换的重着色遍历里被识别替换（默认黑是 LAF 值，不归主题管）
        area.setForeground(Ui.BUBBLE_TEXT);
        area.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        sizeArea(area);

        RoundedPanel rp = new RoundedPanel(self);
        rp.setLayout(new BoxLayout(rp, BoxLayout.Y_AXIS));
        if (Msg.KIND_CLIP.equals(m.kind)) {
            JLabel tag = new JLabel("[剪贴板]");
            // 档位：常规 15（=发送文件按钮同档）
            tag.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            tag.setForeground(Ui.BUBBLE_META);
            tag.setAlignmentX(Component.LEFT_ALIGNMENT);
            padH(tag, 12); // 末字 "]" 的右缘墨迹余量（口径见 bubble() 里 meta 首次调用的注释）
            rp.add(tag);
        }
        rp.add(area);

        if (truncated) {
            final boolean[] expanded = new boolean[]{false};
            JButton more = new JButton("展开全文（共 " + m.text.length() + " 字）");
            // 2026-09-29（「全部统一」配套）：链接式蓝字按钮保持 FLAT（不套灰卡、蓝字不被覆盖）
            Ui.flat(more);
            // 档位：常规 15（=发送文件按钮同档）
            more.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            more.setBorderPainted(false);
            more.setContentAreaFilled(false);
            more.setForeground(new Color(0x15, 0x65, 0xC0));
            more.setAlignmentX(Component.LEFT_ALIGNMENT);
            more.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    expanded[0] = !expanded[0];
                    area.setText(Texts.headTail(m.text, expanded[0]));
                    sizeArea(area);
                    more.setText(expanded[0] ? "收起" : "展开全文（共 " + m.text.length() + " 字）");
                    W.msgPanel.revalidate();
                }
            });
            rp.add(more);
        }

        // 动作入口一律收进**右键菜单**（2026-09-21 用户定调）：气泡里不再常驻「Copy」这类按钮，
        // 免得每条消息都挂一排字、把气泡撑得又长又乱。

        rp.setAlignmentX(self ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        return rp;
    }

    /**
     * 给气泡内文字 label 增加水平安全余量。
     *
     * <p>JLabel 默认首选宽 = FontMetrics 各字符 advance 之和，容差为 0；在真实窗口的
     * 多层容器（JScrollPane/BoxLayout）里，末字符（如 "KB" 的 B）右缘抗锯齿像素会被
     * 裁断、看起来像 E。这里把 label 的 pref/min/max 宽统一加 {@code extra}px，给末字符
     * 留出余量，不依赖字体度量的零容差。
     */
    static void padH(JLabel l, int extra) {
        Dimension d = l.getPreferredSize();
        Dimension nd = new Dimension(d.width + extra, d.height);
        l.setPreferredSize(nd);
        l.setMinimumSize(nd);
        l.setMaximumSize(nd);
    }

    JComponent fileBubble(Msg m, boolean self, boolean compact) {
        RoundedPanel rp = new RoundedPanel(self);
        rp.setLayout(new BoxLayout(rp, BoxLayout.Y_AXIS));

        if (m.isDir) {
            // 文件夹传输气泡：整目录一个气泡，进度按字节聚合
            JLabel name = new JLabel("[文件夹] " + Texts.middleEllipsis(m.fileName, 26));
            name.setName("FileBubbleName"); // 探针白名单句柄（T06）
            name.setToolTipText(m.fileName);
            // 档位：常规 15（=发送文件按钮同档）；文件名与元信息靠颜色分层，不用假粗（见 Fonts）
            name.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            name.setForeground(Ui.TEXT_MAIN);
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            padH(name, 12);
            rp.add(name);

            StringBuilder info = new StringBuilder();
            if (m.dirCount > 0) {
                info.append(m.dirCount).append(" 个文件");
            }
            if (m.fileSize > 0) {
                if (info.length() > 0) {
                    info.append(" · ");
                }
                info.append(Texts.humanSize(m.fileSize));
            }
            if (info.length() == 0) {
                info.append("大小未知");
            }
            JLabel sz = new JLabel(info.toString());
            sz.setName("FileBubbleSize"); // 探针白名单句柄（T06）
            // 档位：常规 15（=发送文件按钮同档）
            sz.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            sz.setForeground(Ui.BUBBLE_META);
            sz.setAlignmentX(Component.LEFT_ALIGNMENT);
            padH(sz, 12);
            rp.add(sz);

            // 进度条：DENIED（数据流量未同意）时**不画** —— 它并没有在传，画个 0% 条会误导
            if (m.progress >= 0 && m.progress < 100 && !Msg.ST_DENIED.equals(m.status)) {
                JProgressBar pb = new JProgressBar(0, 100);
                pb.setValue(m.progress);
                pb.setStringPainted(true);
                pb.setPreferredSize(new Dimension(220, 16));
                pb.setMaximumSize(new Dimension(220, 16));
                pb.setAlignmentX(Component.LEFT_ALIGNMENT);
                // 文案「87%  0.95M/s」由速率心跳刷新（见 registerBar / refreshBarSpeeds）
                pb.setString(speedLabel(m.mid, m.progress));
                registerBar(m.mid, pb);
                rp.add(pb);
            }
            // 文件夹同理：整目录收完（status==OK）才给「已保存」，**不显示路径**。
            // ⚠ 必须带 status==OK：文件夹的 m.filePath = dir.desc 在**第一个成员到达时**就已非空
            //   （LinkReceive 第 790/925/1022/1173 行），只判 filePath != null 会在传输中提前显示「已保存」。
            if (!self && Msg.ST_OK.equals(m.status) && m.filePath != null && !m.filePath.isEmpty()) {
                JLabel saved = new JLabel("已保存");
                saved.setName("FileBubbleSavedState");
                // 档位：常规 15（=发送文件按钮同档）
                saved.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
                saved.setForeground(Ui.SAVED_GREEN);
                saved.setAlignmentX(Component.LEFT_ALIGNMENT);
                padH(saved, 12);
                rp.add(saved);
            }
            addDenyRow(rp, m, self);
            addPauseRow(rp, m, self);
            if (self && (Msg.ST_SENDING.equals(m.status) || Msg.ST_PENDING.equals(m.status))) {
                JButton cancel = new JButton("取消发送");
                // 2026-09-29（「全部统一」配套）：链接式红字按钮保持 FLAT（不套灰卡、红字不被覆盖）
                Ui.flat(cancel);
                // 档位：常规 15（=发送文件按钮同档）
                cancel.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
                cancel.setBorderPainted(false);
                cancel.setContentAreaFilled(false);
                cancel.setForeground(new Color(0xB0, 0x2A, 0x2A));
                cancel.setAlignmentX(Component.LEFT_ALIGNMENT);
                cancel.addActionListener(e -> W.link.cancelFile(m.mid));
                rp.add(cancel);
            }
            rp.setAlignmentX(self ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            return rp;
        }

        final File local = localFileOf(m);
        boolean isImage = isImageName(m.fileName) && local != null && local.isFile();

        // 缩略图：本地文件存在且格式可解码时，异步加载（JDK 直读 / ffmpeg 兜底），
        // 避免在 EDT 上解码大图卡界面；解码结果按 mid 缓存，重载/重启后直接读缓存。
        if (isImage && thumbnailable(m.fileName)) {
            final JLabel pic = new JLabel("图片加载中…");
            // 档位：常规 15（=发送文件按钮同档）
            pic.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            pic.setForeground(Ui.BUBBLE_META);
            pic.setAlignmentX(Component.LEFT_ALIGNMENT);
            pic.setToolTipText("短按全屏查看 · 右键更多（复制 / 定位 / 删除）");
            pic.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            // ⚠ 这里**故意不用 padH**：padH 会把 pref/min/max 宽一次性钉死成
            //   「"图片加载中…" 文字宽 + extra」，而本标签稍后会被换成 220px 缩略图
            //   （pic.setIcon(done); pic.setText(null)，见下面的后台线程）—— 钉死的宽度装不下图。
            //   改用右内边距留余量：它参与 pref 计算（图标宽 + 4px），又随内容自然变化，
            //   文字阶段的末字右缘墨迹不贴边（余量口径同 meta 处注释，仍源自 INK_SAFETY=1.12）。
            pic.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            // 点击手势由气泡层的 BubbleGestureListener 统一处理（短按全屏、右键菜单），
            // 这里不再单独挂 mouseClicked（会双触发）
            rp.add(pic);
            final File cache = thumbCacheFile(m.mid);
            final java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread t = new Thread(() -> {
                // 重试：收到图片时这条气泡是**刚被重建**的，文件可能刚关流、系统还没把它索引好，
                // 一次 decode 失败很常见。以前失败就静默 return → 气泡永远停在「图片加载中…」，
                // 只有切会话（重载全部气泡）才会重试成功 —— 用户 2026-09-19 报的正是这个现象。
                // 现在失败按 0/300/600/1200ms 重试，仍失败才放弃并落日志。
                final long[] waits = {0L, 300L, 600L, 1200L};
                ImageIcon icon = null;
                for (int i = 0; i < waits.length && icon == null; i++) {
                    if (waits[i] > 0) {
                        try {
                            Thread.sleep(waits[i]);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (replaced.get()) {
                        return;
                    }
                    icon = thumbIcon(local, 220, cache);
                }
                if (icon == null) {
                    Log.i("缩略图解码失败（已重试 " + waits.length + " 次）：" + m.fileName
                            + "（" + local.getAbsolutePath() + "）");
                    return;
                }
                final ImageIcon done = icon;
                SwingUtilities.invokeLater(() -> {
                    if (replaced.getAndSet(true)) {
                        return;
                    }
                    pic.setIcon(done);
                    pic.setText(null);
                    // 图标（最大 220px）通常远大于原来的「图片加载中…」文字：只 revalidate 标签自己，
                    // BoxLayout 与滚动面板不会重排，结果是「图片已经设进去却看不见」。
                    // 必须让外层消息面板一起重新布局 + 重绘（与展开全文 :265 同一做法）。
                    pic.revalidate();
                    W.msgPanel.revalidate();
                    W.msgPanel.repaint();
                });
            }, "thumb-" + m.fileName);
            t.setDaemon(true);
            t.start();
        }

        JLabel name = new JLabel((isImage ? "[图片] " : "[文件] ") + Texts.middleEllipsis(m.fileName, 26));
        name.setName("FileBubbleName"); // 探针白名单句柄（T06）
        name.setToolTipText(m.fileName);
        // 档位：常规 15（=发送文件按钮同档）；文件名与元信息靠颜色分层，不用假粗（见 Fonts）
        name.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        name.setForeground(Ui.TEXT_MAIN);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        // 短按用系统默认程序打开的动作由气泡层的 BubbleGestureListener 统一处理
        if (!isImage && local != null && local.isFile()) {
            name.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }
        padH(name, 12);
        rp.add(name);

        JLabel sz = new JLabel(Texts.humanSize(m.fileSize));
        sz.setName("FileBubbleSize"); // 探针白名单句柄（T06）
        // 档位：常规 15（=发送文件按钮同档）
        sz.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        sz.setForeground(Ui.BUBBLE_META);
        sz.setAlignmentX(Component.LEFT_ALIGNMENT);
        padH(sz, 12);
        rp.add(sz);

        // 进度条：DENIED（数据流量未同意）时**不画** —— 它并没有在传，画个 0% 条会误导
        if (m.progress >= 0 && m.progress < 100 && !Msg.ST_DENIED.equals(m.status)) {
            JProgressBar pb = new JProgressBar(0, 100);
            pb.setValue(m.progress);
            pb.setStringPainted(true);
            pb.setPreferredSize(new Dimension(220, 16));
            pb.setMaximumSize(new Dimension(220, 16));
            pb.setAlignmentX(Component.LEFT_ALIGNMENT);
            // 文案「87%  0.95M/s」由速率心跳刷新（见 registerBar / refreshBarSpeeds）
            pb.setString(speedLabel(m.mid, m.progress));
            registerBar(m.mid, pb);
            rp.add(pb);
        }
        // 收到并已落盘：只给一枚绿色「已保存」标记，**绝不显示路径**
        // （2026-10-01 用户定调：气泡上始终不显示文件路径；口径与 Android 端 Bubble.kt 一致：
        //  绿色勾 + 「已保存」文字，而不是把 filePath 摊开）。
        // 条件三连：非自己发的 + 状态 OK + 本地路径非空 —— 缺 status==OK 会在传输中/失败时误报已保存。
        // 图片不排除（无 !isImage）：与 Android 端 Bubble.kt:330 同口径，图片落盘后同样显示「已保存」（见 A5/R4）。
        if (!self && Msg.ST_OK.equals(m.status)
                && m.filePath != null && !m.filePath.isEmpty()) {
            JLabel saved = new JLabel("已保存");
            saved.setName("FileBubbleSavedState");
            // 档位：常规 15（=发送文件按钮同档）
            saved.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            saved.setForeground(Ui.SAVED_GREEN);
            saved.setAlignmentX(Component.LEFT_ALIGNMENT);
            // 末字「存」的右缘墨迹余量（口径见 bubble() 里 meta 首次调用的注释，INK_SAFETY=1.12）
            padH(saved, 12);
            rp.add(saved);
        }

        // 「Copy image / Show in folder / 打开文件」等入口一律收进**右键菜单**（2026-09-21 用户定调），
        // 气泡里不再常驻按钮；可不可用由 [showBubblePopup] 现算（传输中/失败时不给打开与复制）。

        addDenyRow(rp, m, self);
        addPauseRow(rp, m, self);

        // 取消发送：只对自己发出的、尚未完成的文件显示；取消后两端同步不显示
        if (self && (Msg.ST_SENDING.equals(m.status) || Msg.ST_PENDING.equals(m.status))) {
            JButton cancel = new JButton("取消发送");
            // 2026-09-29（「全部统一」配套）：链接式红字按钮保持 FLAT（不套灰卡、红字不被覆盖）
            Ui.flat(cancel);
            // 档位：常规 15（=发送文件按钮同档）
            cancel.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            cancel.setBorderPainted(false);
            cancel.setContentAreaFilled(false);
            cancel.setForeground(new Color(0xB0, 0x2A, 0x2A));
            cancel.setAlignmentX(Component.LEFT_ALIGNMENT);
            cancel.addActionListener(e -> W.link.cancelFile(m.mid));
            rp.add(cancel);
        }

        rp.setAlignmentX(self ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        return rp;
    }

    // ==================== 数据流量未同意（v2.7）====================

    /**
     * 红色感叹号图标：**矢量绘制，等价于 Android 端那张 SVG**（红色实心圆 + 白色叹号）。
     *
     * <p>为什么不用字体里的 ⚠ / ❗ 字符：PC 端界面文案一律纯文本（Dialog 字体不保证有这些码位，
     * 实测 U+25BC 就画不出来，见设备列表的 DownTriangle）。手绘圆+叹号在任何字体下都一致。
     */
    private static final class DenyRetryIcon implements Icon {
        private final int side;

        DenyRetryIcon(int side) {
            this.side = side;
        }

        @Override
        public int getIconWidth() {
            return side;
        }

        @Override
        public int getIconHeight() {
            return side;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double r = side / 2.0;
            // 红色圆底（与 Android DenyRetryIcon 同色 #D32F2F）
            g2.setColor(new Color(0xD3, 0x2F, 0x2F));
            g2.fillOval(x, y, side, side);
            double w = Math.max(1.0, r * 0.26);
            g2.setColor(Color.WHITE);
            // 叹号竖条（圆角，宽 = 2r*0.26）
            int barW = (int) Math.round(w);
            int barH = (int) Math.round(r * 0.66);
            g2.fillRoundRect((int) Math.round(x + r - barW / 2.0), (int) Math.round(y + r * 0.30),
                    barW, Math.max(1, barH), barW, barW);
            // 叹号圆点
            int dot = Math.max(2, (int) Math.round(w * 0.62 * 2));
            g2.fillOval((int) Math.round(x + r - dot / 2.0),
                    (int) Math.round(y + r * 1.34 - dot / 2.0), dot, dot);
            g2.dispose();
        }
    }

    /** 可点的红色叹号按钮（消息末端）：点击 = 重试发送这条文件消息 */
    JButton denyRetryButton(final Msg m) {
        JButton b = new JButton(new DenyRetryIcon(15));
        b.setPreferredSize(new Dimension(20, 20));
        b.setMinimumSize(new Dimension(20, 20));
        b.setMaximumSize(new Dimension(20, 20));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setToolTipText("未同意使用数据流量 · 点一下重试发送");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.addActionListener(e -> W.link.retryFileSend(m));
        return b;
    }

    /**
     * 「数据流量未同意」提示行：文件气泡里把原因摊在文件名下方，末尾挂可点的红色叹号。
     *
     * <p>**只在 DENIED 状态渲染** —— 重试成功转 SENDING 后这一行自动消失，不需要额外清理字段。
     * 接收方向不会出现（对端未同意时，本机什么都不记录，见 AppCore/Link 的 replyDataAns）。
     */
    void addDenyRow(JPanel rp, final Msg m, boolean self) {
        if (!Msg.ST_DENIED.equals(m.status)) {
            return;
        }
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        String note = (m.text == null || m.text.isEmpty()) ? "未同意使用数据流量" : m.text;
        JLabel warn = new JLabel(note);
        // 档位：常规 15（=发送文件按钮同档）
        warn.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        warn.setForeground(new Color(0xD3, 0x2F, 0x2F));
        padH(warn, 12); // 红色警示文案末字右缘墨迹余量（口径见 bubble() 里 meta 首次调用的注释）
        row.add(warn);
        if (self) {
            row.add(Box.createHorizontalStrut(6));
            row.add(denyRetryButton(m));
        }
        row.add(Box.createHorizontalGlue());
        rp.add(row);
    }

    /**
     * 「暂停」提示行：灰色 ⏸ + 文案，发送方向末尾再挂一个可点的「继续」。
     *
     * <p>**只在 PAUSED 状态渲染** —— 重连续传（回 SENDING）或收尾（OK）后这一行自动消失，
     * 不需要额外清理字段。
     *
     * <p>两个方向都可能出现（2.12 / D16 起）：
     *  - 接收方向：本机收流停滞 30s，对端把半成品的偏移留在自己那侧（文案「重连后自动续传」）。
     *  - 发送方向：文件排他投票 = 1（通话中／没网络／被拒），在传的那条在分片边界就地暂停，
     *    条目留在待发台账（文案「解除后自动续发一次」）。投票翻回全 0 时会自动发**一次**
     *    file_query；对端没应答就停在这一行，等用户点「继续」（你定的 ⑬-1 口径）。
     */
    void addPauseRow(JPanel rp, final Msg m, boolean self) {
        if (!Msg.ST_PAUSED.equals(m.status)) {
            return;
        }
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel icon = new JLabel();
        icon.setIcon(new PauseIcon(12));
        icon.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(icon);
        row.add(Box.createHorizontalStrut(4));
        JLabel note = new JLabel(self
                ? "已暂停，解除后自动续发一次"
                : (m.progress >= 0 ? "已收 " + m.progress + "%，重连后自动续传" : "重连后自动续传"));
        // 档位：常规 15（=发送文件按钮同档）
        note.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
        note.setForeground(new Color(0x9E, 0x9E, 0x9E));
        padH(note, 12);
        row.add(note);
        if (self) {
            // 先例：DENIED 的红色叹号按钮（denyRetryButton，本文件上方），同样直接走 retryFileSend。
            JButton cont = new JButton("继续");
            Ui.flat(cont);
            cont.setFont(new Font(ChatWindow.UI_FONT, Font.PLAIN, Ui.FS_STD));
            cont.setBorderPainted(false);
            cont.setContentAreaFilled(false);
            cont.setForeground(new Color(0x2A, 0x6B, 0xB0));
            cont.setToolTipText("自动续发没续上时，点这里重试一次");
            cont.setAlignmentX(Component.LEFT_ALIGNMENT);
            cont.addActionListener(e -> W.link.retryFileSend(m));
            row.add(cont);
        }
        row.add(Box.createHorizontalGlue());
        rp.add(row);
    }

    /**
     * 暂停图标：两条竖杠（⏸ 等价，矢量绘制）。
     *
     * <p>为什么不用字体里的 ⏸ 字符：PC 端界面文案一律纯文本（Dialog 字体不保证覆盖这些码位，
     * 实测 U+25BC 就画不出来），手绘杠在任何字体下都一致。
     */
    private static final class PauseIcon implements Icon {
        private final int side;

        PauseIcon(int side) {
            this.side = side;
        }

        public int getIconWidth() {
            return side;
        }

        public int getIconHeight() {
            return side;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0x9E, 0x9E, 0x9E));
            double u = side / 24.0;
            g2.fillRoundRect((int) Math.round(x + 6.2 * u), (int) Math.round(y + 5 * u),
                    (int) Math.round(3.6 * u), (int) Math.round(14 * u), 3, 3);
            g2.fillRoundRect((int) Math.round(x + 14.2 * u), (int) Math.round(y + 5 * u),
                    (int) Math.round(3.6 * u), (int) Math.round(14 * u), 3, 3);
            g2.dispose();
        }
    }

    /** 把消息里的路径/地址映射到本地文件（用于加载图片） */
    static File localFileOf(Msg m) {
        String p = m.filePath;
        if (p == null || p.isEmpty()) {
            return null;
        }
        try {
            if (p.startsWith("file://")) {
                return new File(java.net.URI.create(p));
            }
            return new File(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用系统默认关联程序打开本地文件（PC 端：直接用系统默认，不做「无默认程序」判断）。
     * 系统无任何关联或抛异常时，弹窗告知失败原因与文件位置，不静默吞掉。
     */
    void openLocalFile(final File f) {
        if (f == null || !f.isFile()) {
            JOptionPane.showMessageDialog(W.frame, "文件不存在或已被移动。", "打开失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!java.awt.Desktop.isDesktopSupported()) {
            JOptionPane.showMessageDialog(W.frame, "当前环境不支持用系统程序打开文件。", "打开失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
            JOptionPane.showMessageDialog(W.frame, "当前系统不支持「打开文件」操作。", "打开失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            desktop.open(f);
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(W.frame,
                    "无法用系统默认程序打开该文件：\n" + t.getMessage()
                            + "\n\n文件位置：" + f.getAbsolutePath(),
                    "打开失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ==================== 气泡动作（2026-09-21 起全部走右键菜单）====================
    //
    // 原来这里有一对 helper：`miniButton()`（气泡里的 11px 无边框彩字按钮）+ `actionRow()`
    // （按钮行）。2026-09-19 用户要求「文本加复制、文件加打开」，于是每条气泡下面常驻一排
    // 「Copy / Copy image / Show in folder」—— 2026-09-21 用户改主意：**常驻按钮全部撤掉，
    // 改右键菜单**（文案中文）。两个 helper 随之变成死代码，一并删除，别再长回来。
    // 唯一剩下的气泡内按钮是「展开全文 / 收起」与「取消发送」（中文、条件性出现），
    // 以及数据流量未同意的红色叹号。

    /**
     * 把一条**文本消息**写进系统剪贴板（右键菜单「复制文本」；复制的是完整原文）。
     *
     * <p>不额外调 {@code sendClip}：PC 端本来就有剪贴板监听（见 [Clip] + `PcMain` 里的
     * {@code onClipChanged}），写进去之后由它按既有规则推给手机 —— 与「用户自己在别处复制」
     * 同一条路；在这里再推一次会推两遍。
     */
    void copyTextToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            W.setSub("已复制文本到剪贴板（" + text.length() + " 字）");
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(W.frame,
                    "复制到剪贴板失败：\n" + t.getMessage(),
                    "复制失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * 「Copy image」—— 把图片写进系统剪贴板（{@code DataFlavor.imageFlavor}，Windows 上即 CF_DIB）。
     *
     * <p>解码放**后台线程**：webp / avif / heic 要走系统 WIC 或 ffmpeg（外部进程），
     * 在 EDT 上跑会卡住整个窗口。
     *
     * <p>长边上限 4096：剪贴板里的图是拿去「粘贴到聊天 / 文档」的，没必要逐像素还原原图，
     * 但也不能拿 220px 的缩略图糊弄；4096 够清晰，也不会为一张 50MP 的图吃掉几百 MB 内存。
     * **故意不用 {@code readCached}**：那个缓存文件就是气泡缩略图用的
     * （{@code thumb_cache/<mid>.jpg}），借它落盘会把缩略图缓存顶掉。
     */
    void copyImageToClipboard(final File f) {
        if (f == null || !f.isFile()) {
            JOptionPane.showMessageDialog(W.frame, "文件不存在或已被移动。",
                    "复制图片失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        W.setSub("Decoding image…");
        Thread t = new Thread(() -> {
            BufferedImage img = null;
            try {
                img = ImageCodec.read(f, 4096);
            } catch (Throwable ignore) {
            }
            final BufferedImage out = img;
            SwingUtilities.invokeLater(() -> {
                if (out == null) {
                    W.setSub(" ");
                    JOptionPane.showMessageDialog(W.frame,
                            "这张图片解不开（" + f.getName() + "）。\n\n文件位置："
                                    + f.getAbsolutePath(),
                            "复制图片失败", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new ImageSelection(out), null);
                    W.setSub("已复制图片到剪贴板（" + out.getWidth() + "x" + out.getHeight() + "）");
                } catch (Throwable err) {
                    JOptionPane.showMessageDialog(W.frame,
                            "复制图片到剪贴板失败：\n" + err.getMessage(),
                            "复制图片失败", JOptionPane.WARNING_MESSAGE);
                }
            });
        }, "copy-image");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 「在文件夹中显示」—— 在系统文件管理器里定位并选中这个文件（右键菜单项，中文文案）。
     *
     * <p>Windows 用 {@code explorer /select,<路径>}：JDK 的
     * {@code Desktop.browseFileDirectory} 在 Windows 上并不保证实现可用，
     * 而 explorer 是各版本 Windows 都有的稳做法。⚠️ 参数拆法有讲究（2026-09-26 修）：
     * 路径不能与 {@code /select,} 拼成同一个参数——路径带空格时 Java 会把整个参数
     * 包上引号，变成 {@code explorer "/select,C:\a b\c.txt"}，explorer 解析不了
     * 这种形态，会静默回落打开「文档/主文件夹」（右键「在文件夹中显示」打开错位置的根因）。
     * 正确形态是 {@code /select,} 与**自带引号的路径**各占一个参数：
     * {@code new ProcessBuilder("explorer.exe", "/select,", "\"" + 路径 + "\"")}——
     * Java 对「首尾已是引号」的参数不再二次包引号，最终命令行即
     * {@code explorer /select, "C:\a b\c.txt"}（JDK6 起|Win7~Win11 均验证可行的形态）。
     * macOS 用 {@code open -R}；其余平台退化为「打开父目录」。
     */
    void showInFolder(final File f) {
        if (f == null || !f.exists()) {
            JOptionPane.showMessageDialog(W.frame,
                    "文件不存在：\n" + (f == null ? "" : f.getAbsolutePath()),
                    "打开所在文件夹失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // 路径单独加引号、与 /select, 分开传：路径带空格时整参包裹会失效（见方法注释）
                new ProcessBuilder("explorer.exe", "/select,",
                        "\"" + f.getAbsolutePath() + "\"").start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", f.getAbsolutePath()).start();
            } else if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f.getAbsoluteFile().getParentFile());
            } else {
                JOptionPane.showMessageDialog(W.frame, "当前系统不支持这个操作。",
                        "打开所在文件夹失败", JOptionPane.WARNING_MESSAGE);
            }
            W.setSub("已在文件夹中定位：" + f.getName());
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(W.frame,
                    "无法打开所在文件夹：\n" + t.getMessage()
                            + "\n\n文件位置：" + f.getAbsolutePath(),
                    "打开所在文件夹失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 图片剪贴板载体（{@code DataFlavor.imageFlavor} → Windows 的 CF_DIB） */
    private static final class ImageSelection implements Transferable {
        private final Image image;

        ImageSelection(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    static boolean isImageName(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif")
                || ext.equals("webp") || ext.equals("bmp") || ext.equals("heic") || ext.equals("heif")
                || ext.equals("avif");
    }

    /**
     * 能出缩略图的格式：jpg/png/gif/bmp 走 JDK 内置 ImageIO；
     * webp/avif/heic/heif 走 ffmpeg 兜底解码（本机若有 ffmpeg）。
     */
    static boolean thumbnailable(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("gif") || ext.equals("bmp")
                || ext.equals("webp") || ext.equals("avif")
                || ext.equals("heic") || ext.equals("heif");
    }

    /** 缩略图缓存文件：按消息 mid 命名，放在 store 工作目录的 thumb_cache 下 */
    File thumbCacheFile(String mid) {
        return W.store.root.resolve("thumb_cache").resolve(mid + ".jpg").toFile();
    }

    /** 生成缩略图 ImageIcon（长边不超过 max）；JDK 读不了的格式走 ffmpeg 兜底并落缓存 */
    static ImageIcon thumbIcon(File f, int max, File cache) {
        try {
            BufferedImage img = ImageCodec.readCached(f, max, cache);
            if (img == null) {
                return null;
            }
            double s = Math.min(1.0, (double) max / Math.max(img.getWidth(), img.getHeight()));
            int w = Math.max(1, (int) Math.round(img.getWidth() * s));
            int h = Math.max(1, (int) Math.round(img.getHeight() * s));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            return new ImageIcon(out);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 让 JTextArea 宽度贴合内容（上限 ChatWindow.BUBBLE_MAX_W），并算出换行后的高度 */
    void sizeArea(JTextArea area) {
        String text = area.getText();
        FontMetrics fm = area.getFontMetrics(area.getFont());
        int maxLine = 0;
        for (String ln : text.split("\n")) {
            maxLine = Math.max(maxLine, fm.stringWidth(ln));
        }
        int w = Math.max(80, Math.min(ChatWindow.BUBBLE_MAX_W, maxLine + 12));
        area.setSize(new Dimension(w, Short.MAX_VALUE));
        Dimension pref = area.getPreferredSize();
        area.setPreferredSize(new Dimension(w, pref.height));
        area.setMaximumSize(new Dimension(w, pref.height));
    }

    /** 圆角背景面板（气泡卡片）：**显式三层**堆叠 —— 消息框底色 → 气泡圆角卡片 → 文本/内容。
     *
     *  <p>底色在 paint 时按「自己/对方」**现取**（{@code Ui.BUBBLE_SELF/BUBBLE_PEER} 是 volatile
     *  静态字段，切主题时被重新赋值）：深浅主题切换后只需 repaint 就自动换色，
     *  不能把 Color 在构造时烤进字段 —— 那样切主题后老气泡永远停在旧配色。
     *
     *  <p>2026-09-28 改：原实现先 {@code fillRoundRect} 再 {@code super.paintComponent}
     *  （顺序反着、语义不清），现按需求把层次写死，并换成 {@link CardSkin} 的透明 PNG 圆角贴图
     *  （9 宫格拉伸），圆角半径仍是 16。 */
    private static final class RoundedPanel extends JPanel {
        private final boolean self;

        RoundedPanel(boolean self) {
            this.self = self;
            // 保持非不透明：第 1 层「消息框底色」由父面板 msgPanel（Ui.PAGE_BG）透出来，
            // 本面板一旦 opaque=true 就会把父底色整块刷掉，卡片外的圆角空隙也会变方。
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            // 第 1 层：消息框底色（由父面板 msgPanel 背景 Ui.PAGE_BG 画，见 ChatWindow.buildChat 的注释）——
            //         本面板非不透明，super 这一步不会覆盖它；显式先调是为了固定「背景在前」的顺序。
            super.paintComponent(g);
            // 第 2 层：气泡圆角卡片（透明 PNG 贴图 + 9 宫格；有边框需求时才传 border，气泡无边框）。
            //         CardSkin 内部自带抗锯齿，这里不再重复 setRenderingHint。
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                CardSkin.paint(g2, getWidth(), getHeight(), 16, self ? Ui.BUBBLE_SELF : Ui.BUBBLE_PEER, null);
            } finally {
                g2.dispose();
            }
            // 第 3 层：文本 / 内容（JTextArea / JLabel / 进度条）由 JComponent.paint 的 paintChildren
            //         在本方法之后画，永远盖在卡片之上 —— 这就是「背景→气泡→文字」的叠加保证，
            //         别把任何内容的绘制挪到这里。
        }
    }

}
