package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.Paths;

/**
 * 同一个 mid 连推 N 次 —— 专测**接收端**（手机）的同 mid 落盘查重。
 *
 * 走真实的 {@link LinkSend#doSendFile} 管线（file_query → file_meta → 分片 → file_done），
 * 只是把 mid 固定下来：第 2 次起，接收端应当凭历史里的 OK 记录判定「已存过」，
 * 用 file_resume(offset=全尺寸) 让发送端一个字节都不发，并回 ack 跳过落盘。
 *
 * 用法：java -cp "dist/PhonePcTransfer-PC.jar;build/probes" \
 *        com.phonepctransfer.pc.SameMidPushProbe <对端IP> <文件> [次数=2] [本端数据目录]
 */
public final class SameMidPushProbe {

    static volatile String curState = "?";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: SameMidPushProbe <对端IP> <文件> [次数=2] [本端数据目录]");
            System.exit(2);
        }
        final String ip = args[0];
        final File f = new File(args[1]);
        final int times = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        if (!f.isFile() || !f.canRead()) {
            System.out.println("不是可读文件: " + f.getAbsolutePath());
            System.exit(2);
        }
        String home = args.length > 3 ? args[3]
                : System.getProperty("java.io.tmpdir") + File.separator + "pp-samemid";
        final Store store = new Store(Paths.get(home));
        store.load();

        final Link link = new Link();
        link.setStore(store);
        link.setListener(new Link.Listener() {
            @Override public void onState(Link.State s, String peer) {
                curState = s.name();
            }
            @Override public void onMessageAdded(Msg m) { }
            @Override public void onMessageUpdated(Msg m) { }
            @Override public void onClipToClipboard(String text) { }
            @Override public void onLog(String s) {
                System.out.println("[pc-log] " + s);
            }
        });
        link.start();
        link.selectAndConnect(ip, null);
        long t0 = System.currentTimeMillis();
        while (!"CONNECTED".equals(curState) && System.currentTimeMillis() - t0 < 30_000L) {
            Thread.sleep(200L);
        }
        if (!"CONNECTED".equals(curState)) {
            System.out.println("[probe] 连不上 " + ip + "，状态=" + curState);
            System.exit(3);
        }
        System.out.println("[probe] 已连接 " + ip + "，本机 " + store.deviceId());

        final Msg item = new Msg();
        item.mid = link.newMid();
        item.peerIp = ip;
        item.dir = Msg.DIR_SELF;
        item.kind = Msg.KIND_FILE;
        item.fileName = f.getName();
        item.fileSize = f.length();
        item.filePath = f.getAbsolutePath();
        item.progress = 0;
        item.status = Msg.ST_PENDING;
        System.out.println("[probe] 固定 mid=" + item.mid + " 文件 " + f.getName()
                + " " + f.length() + " B，连推 " + times + " 次");

        for (int i = 1; i <= times; i++) {
            long t = System.currentTimeMillis();
            String err = "";
            try {
                link.send.doSendFile(f, item, "", "", null, true);
            } catch (Throwable t2) {
                err = " 异常=" + t2;
            }
            System.out.println("[probe] 第 " + i + " 次推完，耗时="
                    + (System.currentTimeMillis() - t) + "ms" + err);
            Thread.sleep(1200L);
        }
        link.stop();
        System.out.println("[probe] 完成。核对手机：adb shell ls '/sdcard/Download/手机电脑互传/'");
    }
}
