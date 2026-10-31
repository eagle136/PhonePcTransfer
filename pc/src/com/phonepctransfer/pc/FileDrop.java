package com.phonepctransfer.pc;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;

/**
 * 把文件 / 文件夹**拖进窗口** → 弹「是否发送到 &lt;当前会话设备&gt;」确认 → 确认后开始传输。
 *
 * 装载点有两处，缺一不可：
 *  ① 窗口根面板（{@link #install}）：覆盖聊天区、设备列表、状态栏等一切**自己没有 DropTarget**
 *     的区域 —— DnD 会从鼠标下最深的组件往上找，找到根面板为止；
 *  ② 底部输入框（{@link #installWithFallback}）：JTextField 自带一个 TransferHandler，
 *     拖文件进去它会把**文件路径当文本插进输入框**，而且它同时负责自己的 Ctrl+C / Ctrl+V。
 *     所以这里不能覆盖、只能包一层：文件列表我们自己处理，其余原样转发给它自带的处理器
 *     （直接 setTransferHandler(null) 会把输入框的复制/粘贴一起废掉）。
 *
 * 目标设备 = **当前活动会话**（左侧列表点了哪台就是哪台）。<b>设备离线也接受拖入</b>
 *（2026-10-30 用户定调）：内容进待发队列，该设备握手成功时由 {@code Link.flushPending}
 * 自动补发；同时顺手发起一次连接尝试，设备其实在线就当场连上、马上发出去。
 * **一台设备都没选**时仍然只提示、不发送 —— 没有目标地址，待发记录无处归属。
 */
final class FileDrop {

    /** 确认框里最多逐条列出的项目数，超出只报总数 */
    private static final int MAX_LIST = 8;

    private FileDrop() {
    }

    /** 装载到普通容器（窗口根面板） */
    static void install(JComponent target, JFrame owner, Link link, ChatWindow win) {
        target.setTransferHandler(new Handler(owner, link, win, null));
    }

    /** 装载到自带 TransferHandler 的组件（输入框）：文件列表我们处理，其余转发给原处理器 */
    static void installWithFallback(JComponent target, JFrame owner, Link link, ChatWindow win) {
        TransferHandler original = target.getTransferHandler();
        target.setTransferHandler(new Handler(owner, link, win, original));
    }

    private static final class Handler extends TransferHandler {

        private final JFrame owner;
        private final Link link;
        private final ChatWindow win;

        /** 被包住的原始处理器（输入框自带）；null = 没有需要转发的 */
        private final TransferHandler original;

        Handler(JFrame owner, Link link, ChatWindow win, TransferHandler original) {
            this.owner = owner;
            this.link = link;
            this.win = win;
            this.original = original;
        }

        // ==================== 拖放（DnD）路径 ====================

        @Override
        public boolean canImport(TransferSupport support) {
            if (isFileList(support.getDataFlavors())) {
                return true;
            }
            return original != null && original.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (isFileList(support.getDataFlavors())) {
                return importFiles(support.getTransferable());
            }
            if (original == null) {
                return false;
            }
            // TransferSupport.getComponent() 的静态类型是 Component，这里落到实处才能转发给原处理器
            java.awt.Component c = support.getComponent();
            return c instanceof JComponent
                    && original.importData((JComponent) c, support.getTransferable());
        }

        // ==================== 剪贴板路径 ====================
        // 粘贴走的是 (comp, transferable) 这一对重载，必须一并实现，
        // 否则输入框的 Ctrl+V 会因为换过处理器而失效。

        @Override
        public boolean canImport(JComponent comp, DataFlavor[] flavors) {
            if (isFileList(flavors)) {
                return true;
            }
            return original != null && original.canImport(comp, flavors);
        }

        @Override
        public boolean importData(JComponent comp, Transferable t) {
            if (isFileList(t.getTransferDataFlavors())) {
                // 在资源管理器里复制文件后来这里 Ctrl+V，同样按「发送」处理
                return importFiles(t);
            }
            return original != null && original.importData(comp, t);
        }

        // ==================== 复制 / 剪切：必须转发，否则输入框不能复制 ====================

        @Override
        public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
            if (original != null) {
                original.exportToClipboard(comp, clip, action);
            } else {
                super.exportToClipboard(comp, clip, action);
            }
        }

        @Override
        public int getSourceActions(JComponent comp) {
            return original != null ? original.getSourceActions(comp) : super.getSourceActions(comp);
        }

        // ==================== 落地逻辑 ====================

        private static boolean isFileList(DataFlavor[] flavors) {
            if (flavors == null) {
                return false;
            }
            for (DataFlavor f : flavors) {
                if (f != null && DataFlavor.javaFileListFlavor.equals(f)) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private boolean importFiles(Transferable t) {
            try {
                Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                if (!(data instanceof List)) {
                    return false;
                }
                return confirmAndSend((List<File>) data);
            } catch (Throwable ex) {
                Log.w("拖入内容解析失败: " + ex);
                return false;
            }
        }

        /**
         * 弹确认框并发送。返回 true 表示本次拖入已被处理（拖拽系统会显示「接受」光标）。
         */
        private boolean confirmAndSend(List<File> raw) {
            // 去重（同一次拖拽可能给出重复项）+ 丢掉读不到的（中途被删 / 无权限）
            LinkedHashSet<File> set = new LinkedHashSet<File>();
            for (File f : raw) {
                if (f != null && f.exists()) {
                    set.add(f);
                }
            }
            final List<File> items = new ArrayList<File>(set);
            if (items.isEmpty()) {
                win.setSub("拖入的内容读不到（已被删除或无访问权限）");
                Log.w("拖入 " + raw.size() + " 项，全部不可访问");
                return false;
            }

            win.showWindow();

            // 多设备模型：必须先有「当前对话设备」—— 没有目标地址待发记录无处归属，只能说清楚
            // 以「界面当前显示的对话」为准（win.shownIp），避免链路层 selectedIp 与界面脱节时发错设备
            String selIp = win.shownIp();
            if (selIp == null || selIp.isEmpty()) {
                selIp = link.selectedIp();
            }
            if (selIp == null || selIp.isEmpty()) {
                JOptionPane.showMessageDialog(owner,
                        "还没有选择目标设备，无法发送。\n\n"
                                + "请在左侧设备列表点一下目标设备后再拖入。\n"
                                + "（设备暂时不在线也可以拖：内容会先排队，等它连上后自动发送。）",
                        "未选择设备", JOptionPane.WARNING_MESSAGE);
                win.setSub("拖入 " + items.size() + " 项，但还没有选择目标设备");
                Log.w("拖入 " + items.size() + " 项被拒绝：未选择目标设备");
                return false;
            }

            // 把链路的发送目标对齐到界面正在显示的对话（shownIp）：
            // 启动自动连接多台设备后 selectedIp 与 shownIp 可能各停在一台（2026-09-23 修），
            // 不对齐的话 sendFile 实际发给 selectedIp 那台，气泡却按 shownIp 归属 ——
            // 聊天区什么都不显示，看着就是「确认后毫无反应」。
            // ⚠ 必须早于下面的发送循环与 selectedLabel() 读取。
            win.syncTargetToShown();

            // 离线不拦（2026-10-30 用户定调）：sendFile/sendFolder 在无会话时自动进待发队列，
            // 该设备握手成功时由 flushPending 补发。这里只记下状态用于文案与之后的就地连接。
            // 判据与设备列表绿点同口径 —— **按设备**（组内任意地址有健康会话），见 sendFile 路由。
            final boolean online = link.isDeviceConnected(selIp);

            String who = link.selectedLabel();
            if (who == null || who.isEmpty()) {
                who = "手机";
            }
            String ip = selIp;

            Object[] opts = {"发送", "取消"};
            int r = JOptionPane.showOptionDialog(owner, confirmText(items, who, ip, online),
                    "确认发送", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, opts, opts[0]);
            if (r != 0) {
                win.setSub("已取消发送（拖入 " + items.size() + " 项）");
                Log.i("拖拽发送已取消：" + items.size() + " 项");
                return false;
            }

            int nf = 0;
            int nd = 0;
            for (File f : items) {
                if (f.isDirectory()) {
                    link.sendFolder(f);
                    nd++;
                } else {
                    link.sendFile(f);
                    nf++;
                }
            }
            if (online) {
                win.setSub("已开始发送 " + items.size() + " 项到 " + who);
                Log.i("拖拽发送 " + items.size() + " 项 → " + who
                        + "（文件 " + nf + " / 文件夹 " + nd + "）");
            } else {
                win.setSub("离线排队 " + items.size() + " 项 → " + who + "，连上后自动发送");
                Log.i("拖拽发送 " + items.size() + " 项 → " + who
                        + "（离线，已进待发队列：文件 " + nf + " / 文件夹 " + nd + "）");
                // 设备只是"链路没建起来"（刚启动、断线后未重连）时，这一次尝试就能连上，
                // 握手处的 flushPending 会立刻把刚排进去的内容发出去；真离线则静默失败，
                // 等用户点设备或对端主动连过来时再补发。
                link.connectTo(selIp, true);
            }
            return true;
        }

        /** 确认框正文：发给谁 + 逐条列出（超出上限只报数）+ 合计 */
        private static String confirmText(List<File> items, String who, String ip, boolean online) {
            StringBuilder sb = new StringBuilder();
            sb.append("是否将这些内容发送到 ").append(who);
            if (ip != null && !ip.isEmpty()) {
                sb.append("（").append(ip).append("）");
            }
            sb.append("？\n\n");
            if (!online) {
                sb.append("该设备当前不在线：内容会先加入待发队列，它连上后自动发送。\n\n");
            }

            int files = 0;
            int dirs = 0;
            int shown = 0;
            long total = 0L;
            for (File f : items) {
                boolean dir = f.isDirectory();
                if (dir) {
                    dirs++;
                } else {
                    files++;
                    total += Math.max(0L, f.length());
                }
                if (shown < MAX_LIST) {
                    sb.append("· ");
                    if (dir) {
                        sb.append("[文件夹] ").append(f.getName()).append("（文件夹）");
                    } else {
                        sb.append("[文件] ").append(f.getName())
                                .append("    ").append(Texts.humanSize(f.length()));
                    }
                    sb.append('\n');
                    shown++;
                }
            }
            if (items.size() > shown) {
                sb.append("…另外还有 ").append(items.size() - shown).append(" 项\n");
            }

            sb.append('\n').append("共 ").append(items.size()).append(" 项：");
            if (files > 0) {
                sb.append(files).append(" 个文件");
            }
            if (files > 0 && dirs > 0) {
                sb.append(" + ");
            }
            if (dirs > 0) {
                sb.append(dirs).append(" 个文件夹");
            }
            sb.append("，文件合计 ").append(Texts.humanSize(total));
            if (dirs > 0) {
                sb.append("\n（文件夹按其中实际文件逐个发送，空目录不会被传过去）");
            }
            return sb.toString();
        }
    }
}
