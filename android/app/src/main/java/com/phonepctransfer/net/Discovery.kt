package com.phonepctransfer.net

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * UDP 28766 广播发现（**低噪声版**）。
 *
 * 发包策略：
 *  - **启动只广播一次**（[sendLoop]），告诉局域网「我在这儿」；此后线程待命，
 *    不产生任何周期性网络行为（前台常驻期间也不会反复发包）。
 *  - 常驻 [recvLoop] 纯被动接收对端 beacon，收到谁就把谁回调给上层（自动进设备列表）。
 *  - 唯一的例外是 [scanOnce]：启动时那一次有界扫描。它发的 beacon 带 `q:"1"`
 *    （query 标记），**收到的对端会回一次自己的 beacon** —— 这样"被动方"不需要周期广播
 *    也能被扫到，同时"后启动的一方发现先启动的一方"这个老问题不靠 2s 周期广播解决。
 *    回应带每设备 2s 限频，且回应帧不带 q，不会来回反弹。
 *
 * 一次 [scanOnce] 里其实有**两条路径**（2026-09-21 增加第二条）：
 *  ① **广播询问** —— 发 `q:1` 的 beacon 到各网卡广播地址 + `255.255.255.255`；
 *  ② **网段扫描** —— 只扫**本机地址所在的 /24**，逐地址单播同一个询问帧
 *     （本机 `10.1.2.3` → 只扫 `10.1.2.1` ~ `10.1.2.254`）。
 *     广播在 AP 隔离、跨 AP、大子网（如 /16）里常常到不了对端，逐地址单播能直接命中。
 */
class Discovery(
    private val deviceId: String,
    private val deviceName: String,
    private val tcpPort: Int,
    private val udpPort: Int,
    private val onPeer: (ip: String, name: String, id: String, port: Int) -> Unit
) {

    @Volatile
    private var running = false
    private var recvSocket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null

    /** 串行化 beacon 发送：启动广播与主动扫描（scanOnce）可能并发调用 */
    private val sendLock = Any()

    /**
     * 主动扫描期间用于收集"回应"的设备 IP；为 null 表示当前没有扫描在进行。
     * recvLoop 每收到一个对端 beacon 就往里丢一份（见 scanOnce）。
     */
    @Volatile
    private var scanCollector: MutableSet<String>? = null

    /** 对「扫描询问」回应的限频表：IP → 上次回应时刻（防被反复唤醒时刷屏）
     *  超过 1h 的条目在 [shouldReply] 入口顺手清扫，长时间运行保持有界 */
    private val lastReplyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        if (running) return
        running = true
        Thread({ recvLoop() }, "pp-discovery-recv").apply { isDaemon = true }.start()
        Thread({ sendLoop() }, "pp-discovery-send").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { recvSocket?.close() }
        runCatching { sendSocket?.close() }
        recvSocket = null
        sendSocket = null
    }

    private fun beacon(): ByteArray = beacon(false)

    /** @param query true = 扫描询问帧，收到的一方应回一次自己的 beacon */
    private fun beacon(query: Boolean): ByteArray {
        val o = JSONObject()
            .put("app", Protocol.APP_TAG)
            .put("id", deviceId)
            .put("name", deviceName)
            .put("port", tcpPort)
            .put("model", "android")
        if (query) o.put("q", 1)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 启动广播线程：**只发一次**（这是本端唯一的主动广播）。
     *
     * 发完就待命 —— 没有周期广播，没有轮询，局域网里只有对端开口时我们才回应（见 recvLoop）。
     */
    private fun sendLoop() {
        runCatching { sendBeaconOnce(false) }
            .onSuccess { Log.i(TAG, "启动广播已发出（仅此一次，之后纯被动接收对端广播）") }
            .onFailure { Log.w(TAG, "启动广播发送失败: ${it.message}") }
        while (running) {
            sleep(60_000)
        }
    }

    /** 立刻广播一轮 beacon。启动广播与主动扫描共用，加锁串行化。 */
    private fun sendBeaconOnce(query: Boolean = false) {
        synchronized(sendLock) {
            val s = ensureSendSocket() ?: return
            val payload = beacon(query)
            for (addr in broadcastAddresses()) {
                runCatching {
                    s.send(DatagramPacket(payload, payload.size, addr, udpPort))
                }
            }
        }
    }

    private fun ensureSendSocket(): DatagramSocket? {
        val cur = sendSocket
        if (cur != null && !cur.isClosed) return cur
        return runCatching { DatagramSocket().apply { broadcast = true } }
            .getOrNull()
            ?.also { sendSocket = it }
    }

    /**
     * 主动扫描一轮：先发一个带 `q` 的询问，再在 [timeoutMs] 窗口内每约 800ms 补发一次，
     * 收集这期间**收到回应**（对端 beacon）的设备 IP。
     *
     * 窗口给 3000ms 是为了留够对端「收到询问 → 回一次」的往返余量（对端回应有 2s 限频）。
     * 扫描期内回应的设备同时也会走 [onPeer] 回调被自动加入设备列表。
     *
     * 窗口内的顺序（2026-09-21）：
     *  1. 先广播一轮询问；
     *  2. 紧接着做**网段扫描**：本机 IPv4 所属 /24 的 `.1`~`.254` 逐个单播同一个询问帧
     *     （本机是 `10.1.2.3` 就只扫 `10.1.2.x`，跳过自己）；
     *  3. 余下时间每 800ms 补广播一次；**窗口过半**时对「还没回应的地址」补扫一轮
     *     （首轮可能因 ARP 未收敛而丢包）。
     *
     * @param ownIpv4 本机主 IPv4。为空、或不属于私网段（10/8、172.16/12、192.168/16）时
     *   **只做广播路径**，不做网段扫描 —— 公网 / 蜂窝地址逐个扫 254 个地址毫无意义。
     * @param skipIps 本机**全部** IPv4（多地址设备：WiFi + 热点切换后会同时挂着两个）。
     *   只跳过 [ownIpv4] 的话，扫描会打到自己的另一个地址上 —— 本机自己应答、自己入列，
     *   之后链路层就一直对着自己重连。
     * @return 本次窗口内收到回应的 IP 集合（已去重）；非运行状态返回空集
     */
    fun scanOnce(
        timeoutMs: Long = 3000,
        ownIpv4: String? = null,
        skipIps: List<String> = emptyList()
    ): Set<String> {
        if (!running) return emptySet()
        val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        scanCollector = seen
        val startedAt = System.currentTimeMillis()
        try {
            // ① 先广播一轮（老路径，别丢：网段扫描漏掉的地址靠它兜）
            runCatching { sendBeaconOnce(true) }

            // ② 网段扫描：只扫本网段 /24
            val prefix = subnetPrefixOf(ownIpv4)
            val own = (skipIps + listOfNotNull(ownIpv4)).toSet()
            val targets = if (prefix == null) emptyList() else subnetTargets(prefix, own)
            var retryDone = targets.isEmpty()
            if (targets.isNotEmpty()) {
                Log.i(TAG, "网段扫描 $prefix.1-254（${targets.size} 个地址，跳过本机 ${own.joinToString()}）")
                sweepSubnet(targets)
            }

            // ③ 收集窗口
            var left = timeoutMs - (System.currentTimeMillis() - startedAt)
            while (running && left > 0) {
                runCatching { sendBeaconOnce(true) }
                val slice = minOf(RESEND_TICK_MS, left)
                sleep(slice)
                left -= slice
                if (!retryDone && timeoutMs - left >= timeoutMs / 2) {
                    retryDone = true
                    val retry = targets.filterNot { it in seen }
                    if (retry.isNotEmpty()) {
                        Log.i(TAG, "网段补扫 ${retry.size} 个未回应地址")
                        sweepSubnet(retry)
                    }
                }
            }
        } finally {
            scanCollector = null
        }
        return synchronized(seen) { seen.toSet() }
    }

    /**
     * 逐地址单播询问帧。**不整段持锁**（否则扫描期间[recvLoop]的回应会被卡住），
     * 只在每包 send 时短暂进 [sendLock]。
     *
     * 每 [SWEEP_BURST] 个地址歇 [SWEEP_PACE_MS]：254 个包瞬时打出去会把网卡发送队列 +
     * ARP 未解析队列顶满（内核会把超出的包直接丢掉），节流后命中率明显更高。
     */
    private fun sweepSubnet(hosts: List<String>) {
        val s = synchronized(sendLock) { ensureSendSocket() } ?: return
        val payload = beacon(true)
        var sent = 0
        for (h in hosts) {
            if (!running) return
            val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: continue
            synchronized(sendLock) {
                runCatching { s.send(DatagramPacket(payload, payload.size, addr, udpPort)) }
            }
            sent++
            if (sent % SWEEP_BURST == 0) sleep(SWEEP_PACE_MS)
        }
    }

    /** 本网段 /24 的候选地址（`.1`~`.254`，跳过本机全部地址；`.0` 网络号与 `.255` 广播不发） */
    private fun subnetTargets(prefix: String, own: Set<String>): List<String> {
        val out = ArrayList<String>(254)
        for (i in SWEEP_MIN..SWEEP_MAX) {
            val h = "$prefix.$i"
            if (h in own) continue
            out.add(h)
        }
        return out
    }

    /** 计算所有网卡的广播地址（含 255.255.255.255 兜底） */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (!out.contains(b)) out.add(b)
                }
            }
        }
        return out
    }

    private fun recvLoop() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(udpPort))
            }
            recvSocket = s
            val buf = ByteArray(2048)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val j = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (j.optString("app") != Protocol.APP_TAG) continue
                val id = j.optString("id")
                if (id.isEmpty() || id == deviceId) continue
                val ip = pkt.address?.hostAddress ?: continue
                if (ip.startsWith("127.")) continue
                // 正在主动扫描时，把这个"回应"记进本轮结果
                scanCollector?.add(ip)
                onPeer(ip, j.optString("name", ip), id, j.optInt("port", Protocol.DEFAULT_TCP_PORT))
                // 对方在扫描（带了 q）→ 回一次自己的 beacon，让它不用等我们周期广播也能发现我们。
                // 回应帧不带 q，所以不会互相反弹；并且按 IP 2s 限频，被反复唤醒也不会刷屏。
                if (j.optInt("q", 0) == 1 && shouldReply(ip)) {
                    Log.i(TAG, "回应 $ip 的扫描询问")
                    sendBeaconOnce(false)
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "广播监听退出: ${t.message}")
        }
    }

    /** 对同一 IP 的扫描询问最多每 2s 回一次，避免被反复唤醒时互相刷包 */
    private fun shouldReply(ip: String): Boolean {
        val now = System.currentTimeMillis()
        sweepStaleReplies(now)
        val prev = lastReplyAt.put(ip, now)
        return prev == null || now - prev >= 2000L
    }

    /** 顺手清扫限频表中超过 1h 的条目：限频只看 2s，更旧的已无保留价值（Map 很小，O(n) 可接受） */
    private fun sweepStaleReplies(now: Long) {
        val cutoff = now - REPLY_TTL_MS
        lastReplyAt.entries.removeIf { it.value < cutoff }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignore: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "PP-Discovery"

        /** 每发多少个地址歇一次（见 [sweepSubnet]） */
        private const val SWEEP_BURST = 16
        private const val SWEEP_PACE_MS = 8L

        /** 扫描窗口内补发广播询问的间隔 */
        private const val RESEND_TICK_MS = 800L

        /** 限频表清扫阈值：超过 1h 的条目直接删（见 [sweepStaleReplies]） */
        private const val REPLY_TTL_MS = 3_600_000L

        /** 网段扫描地址范围：跳过 .0（网络号）与 .255（广播） */
        private const val SWEEP_MIN = 1
        private const val SWEEP_MAX = 254

        /**
         * 本机 IPv4 是否属于「值得逐地址扫 /24」的私网段；是则返回前三段
         * （`10.1.2.3` → `10.1.2`），否则 null。
         *
         * 只认 10/8、172.16/12、192.168/16：这三段才可能同时坐着本机与对端。
         * 公网地址、蜂窝 4G/5G 地址、链路本地 169.254/16、IPv6 一律不扫 ——
         * 逐个扫 254 个不属于自己的地址既扫不到对端，也容易被当成端口扫描。
         */
        fun subnetPrefixOf(ip: String?): String? {
            val v = ip?.trim()?.substringBefore('%') ?: return null
            if (v.isEmpty()) return null
            val p = v.split('.')
            if (p.size != 4) return null
            val o = IntArray(4)
            for (i in 0..3) {
                val n = p[i].toIntOrNull() ?: return null
                if (n < 0 || n > 255) return null
                o[i] = n
            }
            val private = o[0] == 10 || (o[0] == 192 && o[1] == 168) ||
                (o[0] == 172 && o[1] in 16..31)
            return if (private) "${o[0]}.${o[1]}.${o[2]}" else null
        }
    }
}
