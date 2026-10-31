package com.phonepctransfer.pc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * 无界面协议测试客户端——用来在真机联调之前把 PC 端链路单独验证干净。
 *
 * 用法：
 *   java -cp dist/PhonePcTransfer-PC.jar com.phonepctransfer.pc.TestClient [host] [port] <cmd> [arg]
 * 命令：
 *   idle              连上后只监听并打印收包（自动 pong）
 *   text  <内容>       发文本
 *   clip  <内容>       发剪贴板
 *   file  <路径>       发文件
 *   pull              请求拉取 PC 剪贴板缓冲
 *   sync              发 sync_req
 */
public final class TestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_TCP_PORT;
        String cmd = args.length > 2 ? args[2] : "idle";
        String arg = args.length > 3 ? args[3] : "";
        // 可选第 5 个参数：本端 deviceId。会话去重规则是 preferOutgoing = (myId < peerId)，
        // 对端会据此决定保留「自己发起的」还是「接受的」那条连接——所以要跟手机端连上，
        // 这个 id 必须排在手机 id 之前（手机 id 形如 android-xxxxxxxx）。
        String myId = args.length > 4 ? args[4] : "android-testclnt";

        System.out.println("[TestClient] 连接 " + host + ":" + port + " 本端 id=" + myId);
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 3000);
        System.out.println("[TestClient] 已连接");

        final DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

        Protocol.write(out, Protocol.hello(myId, "TestClient", "java-test"));

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Map<String, Object> m = Protocol.read(in);
                        if (m == null) {
                            System.out.println("[TestClient] 对端关闭连接");
                            return;
                        }
                        String type = Json.str(m, "type", "");
                        if ("ping".equals(type)) {
                            synchronized (out) {
                                Protocol.write(out, Protocol.pong());
                            }
                            System.out.println("[TestClient] ← ping (已回 pong)");
                            continue;
                        }
                        if ("clip_state".equals(type)) {
                            String t = Json.str(m, "text", "");
                            System.out.println("[TestClient] ← clip_state  " + t.length() + " 字");
                            System.out.println("---- 剪贴板正文开始 ----");
                            System.out.println(t);
                            System.out.println("---- 剪贴板正文结束 ----");
                            continue;
                        }
                        if ("text".equals(type)) {
                            String t = Json.str(m, "text", "");
                            System.out.println("[TestClient] ← text  " + Texts.preview(t, 80)
                                    + "  (" + t.length() + " 字)");
                            continue;
                        }
                        if ("clip".equals(type)) {
                            String t = Json.str(m, "text", "");
                            System.out.println("[TestClient] ← clip  " + Texts.preview(t, 80)
                                    + "  (" + t.length() + " 字)");
                            continue;
                        }
                        if ("clip_notify".equals(type)) {
                            System.out.println("[TestClient] ← clip_notify  len="
                                    + Json.lng(m, "len", 0) + "  preview="
                                    + Json.str(m, "preview", ""));
                            continue;
                        }
                        if ("file_meta".equals(type)) {
                            System.out.println("[TestClient] ← file_meta  "
                                    + Json.str(m, "name", "") + "  " + Json.lng(m, "size", 0) + " B");
                            continue;
                        }
                        System.out.println("[TestClient] ← " + Json.stringify(m));
                    }
                } catch (Throwable t) {
                    if (!"socket closed".equalsIgnoreCase(String.valueOf(t.getMessage()))) {
                        System.out.println("[TestClient] 读循环结束: " + t);
                    }
                }
            }
        }, "test-reader");
        reader.setDaemon(true);
        reader.start();

        Thread.sleep(600);
        switch (cmd) {
            case "text":
                synchronized (out) {
                    Protocol.write(out, Protocol.text(newMid(), arg));
                }
                System.out.println("[TestClient] → text " + arg.length() + " 字");
                break;
            case "clip":
                synchronized (out) {
                    Protocol.write(out, Protocol.clip(newMid(), arg, Clip.sha1(arg)));
                }
                System.out.println("[TestClient] → clip " + arg.length() + " 字");
                break;
            case "file":
                sendFile(out, new File(arg));
                break;
            case "pull":
                synchronized (out) {
                    Protocol.write(out, Protocol.clipPull());
                }
                System.out.println("[TestClient] → clip_pull");
                break;
            case "sync":
                synchronized (out) {
                    Protocol.write(out, Protocol.syncReq());
                }
                System.out.println("[TestClient] → sync_req");
                break;
            default:
                System.out.println("[TestClient] idle 模式，等待收包（Ctrl+C 退出）");
        }

        long wait = "idle".equals(cmd) ? 60000L : 6000L;
        Thread.sleep(wait);
        s.close();
        System.out.println("[TestClient] 退出");
    }

    private static void sendFile(DataOutputStream out, File f) throws Exception {
        if (!f.isFile()) {
            System.out.println("[TestClient] 文件不存在: " + f.getAbsolutePath());
            return;
        }
        String mid = newMid();
        synchronized (out) {
            Protocol.write(out, Protocol.fileMeta(mid, f.getName(), f.length(), "application/octet-stream", ""));
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[Protocol.CHUNK_SIZE];
        int seq = 0;
        long sent = 0;
        FileInputStream fis = new FileInputStream(f);
        try {
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
                String b64 = Base64.getEncoder().encodeToString(
                        n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
                synchronized (out) {
                    Protocol.write(out, Protocol.fileChunk(mid, seq++, b64));
                }
                sent += n;
            }
        } finally {
            fis.close();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", Byte.valueOf(b)));
        }
        synchronized (out) {
            Protocol.write(out, Protocol.fileDone(mid, hex.toString()));
        }
        System.out.println("[TestClient] → file " + f.getName() + " " + sent + " B");
    }

    private static String newMid() {
        return "t" + System.currentTimeMillis() % 100000000L;
    }
}
