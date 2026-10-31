package com.phonepctransfer.pc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UDP 广播发现（**低噪声版**）。
 *
 * 发包策略：
 *  - **启动只广播一次**（`sendLoop`），告诉局域网「我在这儿」；此后线程待命，不产生任何
 *    周期性网络行为。
 *  - 常驻 `recvLoop` 纯被动接收对端 beacon，收到谁就把谁回调给上层（自动进设备列表）。
 *  - 唯一的例外是 `scanOnce()`：启动时那一次有界扫描。它发的 beacon 带 `q:"1"`
 *    （query 标记），**收到的对端会回一次自己的 beacon** —— 这样"被动方"不需要周期广播
 *    也能被扫到，同时"后启动的一方发现先启动的一方"这个老问题不靠 2s 周期广播解决。
 *    回应带每设备 2s 限频，且回应帧不带 q，不会来回反弹。
 *
 * 一次 `scanOnce()` 里其实有**两条路径**（2026-09-21 增加第二条）：
 *  ① **广播询问** —— 发 `q:1` 的 beacon 到各网卡广播地址 + `255.255.255.255`；
 *  ② **网段扫描** —— 只扫**本机地址所在的 /24**，逐地址单播同一个询问帧
 *     （本机 `10.1.2.3` → 只扫 `10.1.2.1` ~ `10.1.2.254`）。
 *     广播在 AP 隔离 / 交换机组播抑制 / 大子网（如 /16）里常常到不了对端，
 *     逐地址单播能直接命中。
 */
public final class Discovery {

    public interface Listener {
        void onPeer(String ip, String name, String id, int port);
    }

    private final String deviceId;
    private final String deviceName;
    private final int tcpPort;
    private final int udpPort;
    private final Listener listener;

    /** 每发多少个地址歇一次（见 sweepSubnet） */
    private static final int SWEEP_BURST = 16;
    private static final long SWEEP_PACE_MS = 8L;

    /** 扫描窗口内补发广播询问的间隔 */
    private static final long RESEND_TICK_MS = 800L;

    /** 限频表清扫阈值：超过 1h 的条目直接删（见 sweepStaleReplies） */
    private static final long REPLY_TTL_MS = 3_600_000L;

    /** 网段扫描地址范围：跳过 .0（网络号）与 .255（广播） */
    private static final int SWEEP_MIN = 1;
    private static final int SWEEP_MAX = 254;

    private volatile boolean running = false;
    private volatile DatagramSocket recvSocket;
    private Thread sendThread;
    private Thread recvThread;

    /** 串行化 beacon 发送：启动广播与主动扫描（scanOnce）可能并发调用 */
    private final Object sendLock = new Object();

    /** 主动扫描期间用于收集"回应"的设备 IP；为 null 表示当前没有扫描在进行 */
    private volatile java.util.Set<String> scanCollector;

    /** 对「扫描询问」回应的限频表：IP → 上次回应时刻（防被反复唤醒时刷屏）
     *  超过 1h 的条目在 shouldReply 入口顺手清扫，长时间运行保持有界 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastReplyAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    public Discovery(String deviceId, String deviceName, int tcpPort, int udpPort, Listener l) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.listener = l;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        sendThread = new Thread(this::sendLoop, "pp-disc-send");
        sendThread.setDaemon(true);
        sendThread.start();
        recvThread = new Thread(this::recvLoop, "pp-disc-recv");
        recvThread.setDaemon(true);
        recvThread.start();
        Log.i("UDP 发现已启动（端口 " + udpPort + "）");
    }

    public synchronized void stop() {
        running = false;
        DatagramSocket s = recvSocket;
        if (s != null) {
            s.close();
        }
        recvSocket = null;
    }

    private byte[] beacon() {
        return beacon(false);
    }

    /** @param query true = 扫描询问帧，收到的一方应回一次自己的 beacon */
    private byte[] beacon(boolean query) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("app", Protocol.APP_TAG);
        m.put("id", deviceId);
        m.put("name", deviceName);
        m.put("port", Integer.valueOf(tcpPort));
        m.put("model", "pc");
        if (query) {
            m.put("q", Integer.valueOf(1));
        }
        return Json.stringify(m).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 启动广播线程：**只发一次**（这是本端唯一的主动广播）。
     *
     * 发完就待命 —— 没有周期广播，没有轮询，局域网里只有对端开口时我们才回应（见 recvLoop）。
     * 线程本身留着只是让 stop() 的语义保持统一（daemon 线程，随进程退出）。
     */
    private void sendLoop() {
        try {
            sendBeaconOnce(false);
            Log.i("启动广播已发出（仅此一次，之后纯被动接收对端广播）");
        } catch (Throwable t) {
            Log.w("启动广播发送失败: " + t);
        }
        while (running) {
            sleep(60000);
        }
    }

    /** 立刻广播一轮 beacon。启动广播与主动扫描共用，加锁串行化。 */
    private void sendBeaconOnce() {
        sendBeaconOnce(false);
    }

    private void sendBeaconOnce(boolean query) {
        synchronized (sendLock) {
            DatagramSocket s = null;
            try {
                byte[] payload = beacon(query);
                s = new DatagramSocket();
                s.setBroadcast(true);
                for (InetAddress addr : broadcastAddresses()) {
                    try {
                        s.send(new DatagramPacket(payload, payload.length, addr, udpPort));
                    } catch (Throwable ignore) {
                        // 单个网卡发送失败不影响其它
                    }
                }
            } catch (Throwable t) {
                Log.w("广播发送失败: " + t);
            } finally {
                if (s != null) {
                    s.close();
                }
            }
        }
    }

    /**
     * 主动扫描一轮（兼容旧签名：不做网段扫描，只发广播询问）。
     */
    public java.util.List<String> scanOnce(long timeoutMs) {
        return scanOnce(timeoutMs, null);
    }

    /**
     * 主动扫描一轮：先发一个带 `q` 的询问，再在 timeoutMs 窗口内每约 800ms 补发一次，
     * 收集这期间**收到回应**（对端 beacon）的设备 IP。
     *
     * 窗口内的顺序（2026-09-21）：
     *  1. 先广播一轮询问；
     *  2. 紧接着做**网段扫描**：本机 IPv4 所属 /24 的 `.1`~`.254` 逐个单播同一个询问帧
     *     （本机是 `10.1.2.3` 就只扫 `10.1.2.x`，跳过自己）；
     *  3. 余下时间每 800ms 补广播一次；**窗口过半**时对「还没回应的地址」补扫一轮
     *     （首轮可能因 ARP 未收敛而丢包）。
     *
     * 回应的设备同时也会走 Listener.onPeer 被自动加入设备列表。
     *
     * @param ownIpv4 本机 IPv4；为空或不属于私网段（10/8、172.16/12、192.168/16）时
     *   **只做广播路径**，不做网段扫描
     * @return 本次窗口内收到回应的 IP 列表（已去重、保序）；未运行返回空列表
     */
    public java.util.List<String> scanOnce(long timeoutMs, String ownIpv4) {
        if (!running) {
            return new java.util.ArrayList<String>();
        }
        java.util.Set<String> seen =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
        scanCollector = seen;
        long startedAt = System.currentTimeMillis();
        try {
            // ① 先广播一轮（老路径，别丢：网段扫描漏掉的地址靠它兜）
            try {
                sendBeaconOnce(true);
            } catch (Throwable t) {
                Log.w("扫描广播失败: " + t);
            }

            // ② 网段扫描：只扫本网段 /24
            String prefix = subnetPrefixOf(ownIpv4);
            java.util.List<String> targets =
                    prefix == null ? new java.util.ArrayList<String>() : subnetTargets(prefix, ownIpv4);
            boolean retryDone = targets.isEmpty();
            if (!targets.isEmpty()) {
                Log.i("网段扫描 " + prefix + ".1-254（" + targets.size() + " 个地址，跳过本机 "
                        + ownIpv4 + "）");
                sweepSubnet(targets);
            }

            // ③ 收集窗口
            long left = timeoutMs - (System.currentTimeMillis() - startedAt);
            while (running && left > 0) {
                try {
                    sendBeaconOnce(true);
                } catch (Throwable t) {
                    Log.w("扫描广播失败: " + t);
                }
                long slice = Math.min(RESEND_TICK_MS, left);
                sleep(slice);
                left -= slice;
                if (!retryDone && timeoutMs - left >= timeoutMs / 2) {
                    retryDone = true;
                    java.util.List<String> retry = new java.util.ArrayList<String>();
                    synchronized (seen) {
                        for (String h : targets) {
                            if (!seen.contains(h)) {
                                retry.add(h);
                            }
                        }
                    }
                    if (!retry.isEmpty()) {
                        Log.i("网段补扫 " + retry.size() + " 个未回应地址");
                        sweepSubnet(retry);
                    }
                }
            }
        } finally {
            scanCollector = null;
        }
        synchronized (seen) {
            return new java.util.ArrayList<String>(seen);
        }
    }

    /**
     * 逐地址单播询问帧。**自己开一个临时 socket**（不碰 [sendLock] / 广播 socket），
     * 所以扫描期间对端询问的回应路径不会被卡住。
     *
     * 每 [SWEEP_BURST] 个地址歇 [SWEEP_PACE_MS]：254 个包瞬时打出去会把网卡发送队列 +
     * ARP 未解析队列顶满（内核会把超出的包直接丢掉），节流后命中率明显更高。
     */
    private void sweepSubnet(java.util.List<String> hosts) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            byte[] payload = beacon(true);
            int sent = 0;
            for (String h : hosts) {
                if (!running) {
                    return;
                }
                try {
                    s.send(new DatagramPacket(payload, payload.length,
                            InetAddress.getByName(h), udpPort));
                } catch (Throwable ignore) {
                    // 单个地址发送失败不影响其它
                }
                sent++;
                if (sent % SWEEP_BURST == 0) {
                    sleep(SWEEP_PACE_MS);
                }
            }
        } catch (Throwable t) {
            Log.w("网段扫描发包失败: " + t);
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    /** 本网段 /24 的候选地址（`.1`~`.254`，跳过自己；`.0` 网络号与 `.255` 广播不发） */
    private static java.util.List<String> subnetTargets(String prefix, String ownIpv4) {
        java.util.List<String> out = new java.util.ArrayList<String>(254);
        for (int i = SWEEP_MIN; i <= SWEEP_MAX; i++) {
            String h = prefix + "." + i;
            if (h.equals(ownIpv4)) {
                continue;
            }
            out.add(h);
        }
        return out;
    }

    /**
     * 本机 IPv4 是否属于「值得逐地址扫 /24」的私网段；是则返回前三段
     * （`10.1.2.3` → `10.1.2`），否则 null。
     *
     * 只认 10/8、172.16/12、192.168/16：这三段才可能同时坐着本机与对端。
     * 公网地址、链路本地 169.254/16、IPv6 一律不扫 —— 逐个扫 254 个不属于自己的地址
     * 既扫不到对端，也容易被当成端口扫描。
     */
    public static String subnetPrefixOf(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        int cut = v.indexOf('%');
        if (cut >= 0) {
            v = v.substring(0, cut).trim();
        }
        if (v.isEmpty()) {
            return null;
        }
        String[] p = v.split("\\.");
        if (p.length != 4) {
            return null;
        }
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (o[i] < 0 || o[i] > 255) {
                return null;
            }
        }
        boolean priv = o[0] == 10 || (o[0] == 192 && o[1] == 168)
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31);
        return priv ? (o[0] + "." + o[1] + "." + o[2]) : null;
    }

    /** 本机用于判定网段的 IPv4：优先返回 192.168.*，否则回退到任何站点本地地址 */
    public static String localIpv4() {
        String fallback = "";
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String n = nif.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("p2p")) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String host = a.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) {
                        continue;
                    }
                    // 优先 192.168.*（正是要扫的网段），其次任意可用地址
                    if (host.startsWith("192.168.")) {
                        return host;
                    }
                    if (fallback.isEmpty()) {
                        fallback = host;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return fallback;
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignore) {
        }
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) {
                        out.add(b);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private void recvLoop() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(udpPort));
            recvSocket = s;
            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                s.receive(pkt);
                String text = new String(pkt.getData(), 0, pkt.getLength(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> m;
                try {
                    m = Json.parseObject(text);
                } catch (Exception e) {
                    continue;
                }
                if (!Protocol.APP_TAG.equals(Json.str(m, "app", ""))) {
                    continue;
                }
                String id = Json.str(m, "id", "");
                if (id.isEmpty() || id.equals(deviceId)) {
                    continue;
                }
                String ip = pkt.getAddress() == null ? null : pkt.getAddress().getHostAddress();
                if (ip == null || ip.startsWith("127.")) {
                    continue;
                }
                // 正在主动扫描时，把这个"回应"记进本轮结果
                java.util.Set<String> col = scanCollector;
                if (col != null) {
                    col.add(ip);
                }
                if (listener != null) {
                    listener.onPeer(ip, Json.str(m, "name", ip), id, Json.integer(m, "port", Protocol.DEFAULT_TCP_PORT));
                }
                // 对方在扫描（带了 q）→ 回一次自己的 beacon，让它不用等我们周期广播也能发现我们。
                // 回应帧不带 q，所以不会互相反弹；并且按 IP 2s 限频，被反复唤醒也不会刷屏。
                if (Json.integer(m, "q", 0) == 1 && shouldReply(ip)) {
                    Log.i("回应 " + ip + " 的扫描询问");
                    sendBeaconOnce(false);
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.w("广播监听退出: " + t);
            }
        }
    }

    /** 对同一 IP 的扫描询问最多每 2s 回一次，避免被反复唤醒时互相刷包 */
    private boolean shouldReply(String ip) {
        long now = System.currentTimeMillis();
        sweepStaleReplies(now);
        Long prev = lastReplyAt.get(ip);
        if (prev != null && now - prev.longValue() < 2000L) {
            return false;
        }
        lastReplyAt.put(ip, Long.valueOf(now));
        return true;
    }

    /** 顺手清扫限频表中超过 1h 的条目：限频只看 2s，更旧的已无保留价值（Map 很小，O(n) 可接受） */
    private void sweepStaleReplies(long now) {
        long cutoff = now - REPLY_TTL_MS;
        for (Map.Entry<String, Long> e : lastReplyAt.entrySet()) {
            if (e.getValue().longValue() < cutoff) {
                lastReplyAt.remove(e.getKey());
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }
}
