package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 无界面「推同一个文件 N 次」测试器 —— 专用于回归「对端重名落盘把数字加在扩展名后面」。
 *
 * 它**不自己造包**，驱动真实的 {@link Link#sendFile(File)}（PC 端 UI 里「+ → 发送文件」那条链路），
 * 只是把界面换成命令行，可以无人值守连发同名文件，再回到设备侧查落盘名：
 *   期望 `报告.txt`、`报告 (1).txt`；出现 `报告.txt.1` / `报告.txt (1)` 就是坏名字
 *   （系统按扩展名认类型时认不出 → APK 装不上、图片/视频打不开）。
 *
 * 用法：
 *   java -cp "dist/PhonePcTransfer-PC.jar;build/probe" \
 *        com.phonepctransfer.pc.FilePushTest <对端IP> <文件绝对路径> [次数=2] [每轮超时秒=120]
 *
 * 退出码：0=每次都收齐  2=参数错  3=连不上  4=有失败  5=超时
 *
 * 与 FolderPushTest 同惯例：只编译进 build/probe，**不进** dist 发布 jar。
 */
public final class FilePushTest {

    private static volatile String curState = "?";
    private static volatile Msg lastFile;
    private static final List<String> logs = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: FilePushTest <对端IP> <文件绝对路径> [次数=2] [每轮超时秒=120]");
            System.exit(2);
        }
        final String ip = args[0];
        final File f = new File(args[1]);
        final int times = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        final long perMs = (args.length > 3 ? Long.parseLong(args[3]) : 120L) * 1000L;
        if (!f.isFile() || !f.canRead()) {
            System.out.println("不是可读文件: " + f.getAbsolutePath());
            System.exit(2);
        }

        Path root = Store.defaultRoot();
        final Store store = new Store(root);
        store.load();
        System.out.println("[push] 工作目录 " + root.toAbsolutePath());
        System.out.println("[push] 本机 " + store.deviceId() + " / " + store.deviceName());
        System.out.println("[push] 目标 " + ip + "  文件 " + f.getAbsolutePath()
                + "（" + f.length() + " B）× " + times + " 次");

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
        System.out.println("[push] 已连接，开始连发同名文件");

        int ok = 0;
        String prevMid = null;
        for (int i = 1; i <= times; i++) {
            System.out.println("---- 第 " + i + " 次发送 ----");
            link.sendFile(f);
            long tSend = System.currentTimeMillis();
            Msg m = null;
            while (System.currentTimeMillis() - tSend < perMs) {
                Msg cur = lastFile;
                if (cur != null && !cur.mid.equals(prevMid)) {
                    m = cur;
                    if (!Msg.ST_SENDING.equals(cur.status) && !Msg.ST_PENDING.equals(cur.status)) {
                        break;
                    }
                }
                Thread.sleep(200L);
            }
            if (m == null || Msg.ST_SENDING.equals(m.status) || Msg.ST_PENDING.equals(m.status)) {
                System.out.println("[push] 第 " + i + " 次超时，最后状态="
                        + (m == null ? "（没抓到文件消息）" : m.status));
                System.exit(5);
            }
            prevMid = m.mid;
            System.out.println("   文件名=" + m.fileName + "  状态=" + m.status
                    + "  " + m.fileSize + " B  耗时=" + (System.currentTimeMillis() - tSend) / 1000 + "s");
            if (Msg.ST_OK.equals(m.status)) {
                ok++;
            } else {
                System.out.println("   失败: " + m.status);
            }
        }

        System.out.println("==================== 结果 ====================");
        System.out.println("成功 " + ok + " / " + times + " 次");
        System.out.println("对端落盘名请到设备侧核对："
                + "adb shell ls '/sdcard/Download/手机电脑互传/'"
                + "  期望「" + f.getName() + "」+「" + expectedSecond(f.getName()) + "」");
        System.out.println("==============================================");
        System.exit(ok == times ? 0 : 4);
    }

    /** 给个期望样例：序号必须插在扩展名前 */
    private static String expectedSecond(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (1)";
        }
        return name.substring(0, dot) + " (1)" + name.substring(dot);
    }

    /** 只跟踪「我方发出的、非目录的那条」 */
    private static void note(Msg m) {
        if (m == null || m.isDir || Msg.DIR_SELF != m.dir) return;
        lastFile = m;
    }
}
