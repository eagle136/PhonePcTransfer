package com.phonepctransfer.pc;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.security.MessageDigest;

/**
 * PC 剪贴板监听与写入。
 *
 * 行为契约（PLAN.md 第 3 节）：
 *  - 本机复制的文本自动进缓冲区，**只保留最新一条**
 *  - 变化时回调 listener（由 PcMain 转成 clip_notify 广播给手机，不主动推正文）
 *  - 把手机发来的内容写入系统剪贴板时置 suppressSha1，避免被自己的监听器再抓一次形成死循环
 *
 * 实现说明：Windows 上没有跨平台的剪贴板变更事件可用，采用 400ms 轮询 +
 * GetClipboardSequenceNumber 等价的哈希比较；getContents 在多进程抢占剪贴板时会抛
 * IllegalStateException，这里做重试。
 */
public final class Clip {

    public interface Listener {
        /** 本机剪贴板出现了新内容（非本程序写入） */
        void onClipChanged(String text, String sha1);
    }

    private static final long POLL_MS = 400;

    private final Listener listener;

    private volatile String buffer = "";
    private volatile String bufferSha1 = "";
    private volatile String lastSeenSha1 = "";
    private volatile String suppressSha1 = "";
    private volatile boolean running = false;

    private volatile Clipboard clipboard;
    private Thread poller;

    public Clip(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        // 优先读取当前剪贴板，避免启动时把已有内容当成"刚复制"
        String cur = readClipboard();
        if (cur != null && !cur.isEmpty()) {
            lastSeenSha1 = sha1(cur);
            buffer = cur;
            bufferSha1 = lastSeenSha1;
        }

        poller = new Thread(this::pollLoop, "pp-clip");
        poller.setDaemon(true);
        poller.start();
        Log.i("剪贴板监听已启动（间隔 " + POLL_MS + "ms）");
    }

    public synchronized void stop() {
        running = false;
        Thread t = poller;
        if (t != null) {
            t.interrupt();
        }
        poller = null;
    }

    private void pollLoop() {
        while (running) {
            try {
                String text = readClipboard();
                if (text != null && !text.isEmpty()) {
                    String sha = sha1(text);
                    if (!sha.equals(lastSeenSha1)) {
                        lastSeenSha1 = sha;
                        if (sha.equals(suppressSha1)) {
                            // 是我们刚写进去的，捕获进缓冲即可，不再回推手机
                            buffer = text;
                            bufferSha1 = sha;
                        } else {
                            buffer = text;
                            bufferSha1 = sha;
                            Log.i("检测到本机剪贴板变化：" + text.length() + " 字");
                            if (listener != null) {
                                listener.onClipChanged(text, sha);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w("剪贴板轮询异常: " + t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String readClipboard() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Clipboard cb = clipboard();
                if (cb == null) {
                    return null;
                }
                Transferable t = cb.getContents(null);
                if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    return null;
                }
                Object o = t.getTransferData(DataFlavor.stringFlavor);
                return o == null ? null : o.toString();
            } catch (Throwable e) {
                // 剪贴板被其它进程占用，稍等重试
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private Clipboard clipboard() {
        Clipboard c = clipboard;
        if (c == null) {
            synchronized (this) {
                if (clipboard == null) {
                    try {
                        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    } catch (Throwable e) {
                        Log.w("获取系统剪贴板失败: " + e);
                    }
                }
                c = clipboard;
            }
        }
        return c;
    }

    /** 把文本写入系统剪贴板，并标记为"本程序写入"（防回环） */
    public void setClipboard(String text) {
        if (text == null) {
            return;
        }
        String sha = sha1(text);
        suppressSha1 = sha;
        lastSeenSha1 = sha;
        buffer = text;
        bufferSha1 = sha;
        try {
            Clipboard cb = clipboard();
            if (cb == null) {
                return;
            }
            cb.setContents(new StringSelection(text), null);
            Log.i("已写入系统剪贴板：" + text.length() + " 字");
        } catch (Throwable e) {
            Log.w("写入系统剪贴板失败: " + e);
        }
    }

    /** 缓冲区最新一条（可能为空） */
    public String buffer() {
        return buffer;
    }

    public String bufferSha1() {
        return bufferSha1;
    }

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "len-" + s.length() + "-" + s.hashCode();
        }
    }
}
