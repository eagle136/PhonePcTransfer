package com.phonepctransfer.pc;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.Font;
import java.awt.Frame;

/**
 * PC 端**语音通话**独立窗口（2026-09-16 用户定调）：
 * 上部 = 对方备注（大字）+ IP（小字）；中部 = 通话状态（正在呼叫…/通话中）+ 时长（大字）；
 * 底部 = 红色「挂断」圆形电话图标（与视频窗口、呼入弹窗同源）。
 *
 * 生命周期：窗口只在通话期间存在，由 ChatWindow 按通话状态机驱动创建/关闭；
 * **关窗 = 挂断**（与 VideoWindow 一致）。视频通话不走本窗口（有专门的 VideoWindow）。
 *
 * <p>注意：PC 端**禁止 emoji**（YaHei 会渲染成方框），按钮全部用中文文本。
 */
public final class VoiceCallWindow {

    /** 窗口上的动作回调（都发生在 EDT 上） */
    public interface Actions {
        /** 点「挂断」或直接关窗 */
        void onHangup();
    }

    private final JFrame frame;
    /** 对方备注（大字） */
    private final JLabel nameLbl;
    /** 对方 IP（小字） */
    private final JLabel ipLbl;
    /** 状态：正在呼叫… / 通话中 / 对方来电… */
    private final JLabel statusLbl;
    /** 通话时长（大字，TALKING 时每 500ms 刷新） */
    private final JLabel timeLbl;
    /** 自绘外壳（标题栏 + 圆角底）；setPeer 同步标题文字用 */
    private final WindowChrome.DialogChrome chrome;
    private Actions actions;
    private boolean closing = false;

    private VoiceCallWindow(Frame owner, String peerName) {
        String label = (peerName == null || peerName.isEmpty()) ? "对端" : peerName;
        frame = new JFrame("语音通话 - " + label);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);
        // 自绘标题栏：去原生装饰（硬约束：setVisible 之前）
        frame.setUndecorated(true);
        // 高 = 内容 420 + 标题栏 32
        frame.setSize(320, 420 + WindowChrome.DIALOG_TITLE_H);
        // 不置顶、不抢焦点（2026-09-16 用户反馈：振铃期窗口挡住主窗/来电弹窗）。
        // 摆到主窗右侧；主窗最大化等放不下时回正中，普通窗口点击才会带到前面。
        frame.setLocationRelativeTo(owner);
        if (owner != null && owner.isShowing()) {
            int x = owner.getX() + owner.getWidth() + 24;
            java.awt.Rectangle sb = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            if (x + frame.getWidth() <= sb.x + sb.width
                    && owner.getX() + owner.getWidth() <= sb.x + sb.width) {
                frame.setLocation(x, Math.max(sb.y, frame.getY()));
            }
        }

        // 关窗 = 挂断：不直接 dispose，交给状态机走完整挂断链路，
        // 状态回 IDLE 后 ChatWindow 会调 close() 关窗
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                fireHangup();
            }
        });

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(new Color(28, 30, 34));
        root.setBorder(BorderFactory.createEmptyBorder(28, 16, 24, 16));
        frame.add(root, BorderLayout.CENTER);
        // 自绘 chrome（必须在 setVisible 之前）：把整个内容面板（含 root）
        // 包进圆角外壳 + 深灰标题栏；X = 挂断；最小化钮照常缩到任务栏（2026-10-30 用户定调）
        chrome = WindowChrome.install(frame, "语音通话 - " + label, true, this::fireHangup);

        // 大字号（21px）：全局关 AA 下 outline 会锯齿 → 用 AALabel 局部显式开 AA
        nameLbl = new Ui.AALabel(label, SwingUtilities.CENTER);
        nameLbl.setForeground(new Color(232, 236, 242));
        nameLbl.setFont(new Font(Fonts.FAMILY, Font.PLAIN, 21));
        nameLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(nameLbl);

        ipLbl = new JLabel(" ", SwingUtilities.CENTER);
        ipLbl.setForeground(new Color(150, 156, 165));
        // 档位：常规 15（=发送文件按钮同档）
        ipLbl.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
        ipLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(ipLbl);

        root.add(Box.createVerticalGlue());

        statusLbl = new JLabel("正在呼叫…", SwingUtilities.CENTER);
        statusLbl.setForeground(new Color(200, 205, 212));
        // 档位：常规 15（=发送文件按钮同档）
        statusLbl.setFont(new Font(Fonts.FAMILY, Font.PLAIN, Ui.FS_STD));
        statusLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(statusLbl);

        // 大字号（36px）：全局关 AA 下 outline 会锯齿 → 用 AALabel 局部显式开 AA
        timeLbl = new Ui.AALabel("00:00", SwingUtilities.CENTER);
        timeLbl.setForeground(new Color(255, 255, 255));
        timeLbl.setFont(new Font(Font.MONOSPACED, Font.BOLD, 36)); // 等宽保留（分支乙），34→36 = S2 映射
        timeLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(timeLbl);

        root.add(Box.createVerticalGlue());

        // 底部：红色「挂断」圆形电话图标（与 Android 端、视频窗口同源）
        JButton hangBtn = CallIcons.circleButton(CallIcons.hangup(26),
                new Color(0xD3, 0x2F, 0x2F), "挂断", 60);
        hangBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        hangBtn.addActionListener(e -> fireHangup());
        root.add(hangBtn);
    }

    /** 创建并显示窗口（不抢焦点、不置顶，位置在主窗右侧） */
    public static VoiceCallWindow open(Frame owner, String peerName) {
        VoiceCallWindow w = new VoiceCallWindow(owner, peerName);
        w.frame.setVisible(true);
        return w;
    }

    public void setActions(Actions a) {
        this.actions = a;
    }

    /** 更新对方备注与 IP（EDT 上调用） */
    public void setPeer(String name, String ip) {
        if (name != null && !name.isEmpty()) {
            nameLbl.setText(name);
            frame.setTitle("语音通话 - " + name);   // 任务栏 / 无障碍
            chrome.setTitle("语音通话 - " + name); // 同步自绘标题栏（否则改备注后标题不刷新）
        }
        ipLbl.setText((ip == null || ip.isEmpty()) ? " " : ip);
    }

    /** 更新状态文字（EDT 上调用） */
    public void setStatus(String text) {
        statusLbl.setText(text);
    }

    /** 更新通话时长（EDT 上调用；非通话状态时置灰显示 00:00） */
    public void setElapsed(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long ss = s % 60;
        timeLbl.setText(h > 0
                ? String.format("%d:%02d:%02d", h, m, ss)
                : String.format("%02d:%02d", m, ss));
    }

    /**
     * 是否显示通话计时（用户 2026-09-19 定调）。
     *
     * 呼叫中 / 振铃期只是「呼叫提示」——不该出现 00:00 这种通话页才有的东西；
     * 双方接听后（TALKING）才切为完整通话页，计时区此时才出现。
     */
    public void setShowTimer(boolean show) {
        if (timeLbl.isVisible() == show) {
            return;
        }
        timeLbl.setVisible(show);
        java.awt.Container p = timeLbl.getParent();
        if (p != null) {
            p.revalidate();
            p.repaint();
        }
    }

    /** 关闭窗口（通话已结束时由 ChatWindow 调用；幂等） */
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        frame.dispose();
    }

    private void fireHangup() {
        if (actions != null) {
            actions.onHangup();
        }
    }
}
