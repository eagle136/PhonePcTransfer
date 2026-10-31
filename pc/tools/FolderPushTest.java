package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 无界面「推文件夹」测试器 —— 用来回归验证「传文件夹漏件」这类问题。
 *
 * 它**不自己造包**，而是驱动真实的 {@link Link#sendFolder(File)}（也就是 PC 端 UI 里
 * 「+ → 发送文件夹」走的那条链路，含 v2.6 的 dir_end / dir_missing 完整性闭环），
 * 只是把界面那一层换成命令行，这样可以在无人值守的情况下反复跑、并把日志落盘。
 *
 * 用法：
 *   java -cp "dist/PhonePcTransfer-PC.jar;build/probe" \
 *        com.phonepctransfer.pc.FolderPushTest <对端IP> <文件夹绝对路径> [超时秒]
 *
 * 退出码：0=收齐  2=参数错  3=连不上  4=发送失败（含漏件判 FAILED）  5=超时
 *
 * 与 UiProbe 同惯例：只编译进 build/probe，**不进** dist 发布 jar（打包清单只收 src/**）。
 */
public final class FolderPushTest {

    private static volatile String curState = "?";
    private static volatile Msg folderMsg;
    private static final List<String> logs = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: FolderPushTest <对端IP> <文件夹绝对路径> [超时秒]");
            System.exit(2);
        }
        final String ip = args[0];
        final File dir = new File(args[1]);
        final long budgetMs = (args.length > 2 ? Long.parseLong(args[2]) : 600L) * 1000L;
        if (!dir.isDirectory()) {
            System.out.println("不是目录或不可访问: " + dir.getAbsolutePath());
            System.exit(2);
        }

        Path root = Store.defaultRoot();
        final Store store = new Store(root);
        store.load();
        System.out.println("[push] 工作目录 " + root.toAbsolutePath());
        System.out.println("[push] 本机 " + store.deviceId() + " / " + store.deviceName());
        System.out.println("[push] 目标 " + ip + "  文件夹 " + dir.getAbsolutePath());

        final Link link = new Link();
        link.setStore(store);
        link.setListener(new Link.Listener() {
            @Override public void onState(Link.State s, String peer) {
                curState = s.name();
                System.out.println("[state] " + s + " peer=" + peer);
            }
            @Override public void onMessageAdded(Msg m) { note(m); }
            @Override public void onMessageUpdated(Msg m) { note(m); }
            @Override public void onClipToClipboard(String text) { /* 测试器不落系统剪贴板 */ }
            @Override public void onLog(String s) {
                synchronized (logs) { logs.add(s); }
                System.out.println("[log] " + s);
            }
        });

        link.start();
        System.out.println("[push] 正在连接 " + ip + " ...");
        link.selectAndConnect(ip, null);

        long t0 = System.currentTimeMillis();
        while (!"CONNECTED".equals(curState) && System.currentTimeMillis() - t0 < 30_000L) {
            Thread.sleep(300L);
        }
        if (!"CONNECTED".equals(curState)) {
            System.out.println("[push] 连接失败，当前状态=" + curState);
            System.exit(3);
        }
        System.out.println("[push] 已连接，开始发送");

        link.sendFolder(dir);

        // 等这条目录消息落定（SENDING/PENDING → OK/FAILED）
        long tStart = System.currentTimeMillis();
        long lastPrint = 0L;
        while (System.currentTimeMillis() - tStart < budgetMs) {
            Msg m = folderMsg;
            if (m != null) {
                String st = m.status;
                if (!Msg.ST_SENDING.equals(st) && !Msg.ST_PENDING.equals(st)) {
                    report(m, System.currentTimeMillis() - tStart);
                    System.exit(Msg.ST_OK.equals(st) ? 0 : 4);
                }
                long now = System.currentTimeMillis();
                if (now - lastPrint > 2000L) {
                    lastPrint = now;
                    System.out.println("[进度] " + m.progress + "%  status=" + st
                            + "  " + m.fileName + "  " + m.fileSize + "B");
                }
            }
            Thread.sleep(200L);
        }
        System.out.println("[push] 超时（" + budgetMs / 1000 + "s）未收尾，最后状态="
                + (folderMsg == null ? "（没抓到目录消息）" : folderMsg.status));
        System.exit(5);
    }

    /** 只跟踪「我方发出的、isDir 的那条」——文件夹整体完成度就看它 */
    private static void note(Msg m) {
        if (m == null || !m.isDir || Msg.DIR_SELF != m.dir) return;
        if (folderMsg == null || m.mid.equals(folderMsg.mid)) folderMsg = m;
    }

    private static void report(Msg m, long costMs) {
        System.out.println("==================== 结果 ====================");
        System.out.println("文件夹   : " + m.fileName);
        System.out.println("状态     : " + m.status + "（" + (Msg.ST_OK.equals(m.status)
                ? "对端已确认收齐" : "未收齐/失败") + "）");
        System.out.println("成员数   : " + m.dirCount);
        System.out.println("总字节   : " + m.fileSize);
        System.out.println("耗时     : " + (costMs / 1000) + "." + (costMs % 1000 / 100) + "s");
        synchronized (logs) {
            List<String> hit = new ArrayList<String>();
            for (String s : logs) {
                if (s.contains("dir_missing") || s.contains("缺失") || s.contains("补发")
                        || s.contains("dir_end") || s.contains("文件夹") || s.contains("FAILED")
                        || s.contains("完成")) {
                    hit.add(s);
                }
            }
            if (!hit.isEmpty()) {
                System.out.println("---- 关键日志 ----");
                for (String s : hit) System.out.println("  " + s);
            }
        }
        System.out.println("==============================================");
    }
}
