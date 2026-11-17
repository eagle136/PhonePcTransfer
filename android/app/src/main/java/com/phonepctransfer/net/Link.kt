package com.phonepctransfer.net

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Peer
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 手机↔电脑 TCP 链路(多设备独立长连接,与 docs/PROTOCOL.md 一致):两端都监听 28765 且都能主动 connect;
 * 每台对端一条独立会话互不干扰;点设备才(重)连接、失败不重试;15s ping、35s 无收包判死;写失败即废弃会话
 */
class Link(internal val ctx: Context, internal val prefs: Prefs) {

    enum class State { STOPPED, IDLE, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State, peer: String)
        /** 会话集合变化(某台连上/断开):UI只刷设备列表,聊天区不受影响 */
        fun onPeersChanged() {}
        /** 新增一条消息（自己发 / 收到对端） */
        fun onMessageAdded(m: MsgItem)
        /** 已有消息的状态或进度变化 */
        fun onMessageUpdated(m: MsgItem)
        /** 一条消息被移除(任一方取消):界面移除+本地历史/待发队列删掉,两端都不再显示 */
        fun onMessageRemoved(mid: String) {}
        /** hsync一批补齐完成:补入count条;实现方按ts重排聊天区(补入按到达顺序追加) */
        fun onHsyncApplied(ip: String, count: Int) {}
        /** 需要把文本写入本机剪贴板（来自对端）。剪贴板同步不生成气泡，只写剪贴板。 */
        fun onClipToClipboard(text: String)
        /** 旧握手遗留:对端对clip_pull回"缓冲为空"时触发;纯被动模型下恒不会发生,保留仅为兼容 */
        fun onPeerClipEmpty() {}
        /** 这份剪贴板内容本机是否"已经知道";true则不重复处理,哈希由实现方持久化 */
        fun shouldSkipPeerClip(sha1: String): Boolean = false
        fun onLog(s: String)

        // ==================== 语音通话回调 ====================
        /** 收到呼叫请求（来自 peerIp 的对端，name 为对方名）。实现方据此弹接听窗。 */
        fun onCallInvite(peerIp: String, name: String) {}
        /** 收到同意接听 */
        fun onCallAccept(peerIp: String) {}
        /** 收到拒绝接听 */
        fun onCallReject(peerIp: String) {}
        /** 收到「忙」：对方已有通话，本端静默收线 */
        fun onCallBusy(peerIp: String) {}
        /** 收到挂断（reason 为空=正常挂断，非空=超时等） */
        fun onCallEnd(peerIp: String, reason: String) {}
        /** 收到呼出方取消（接听前） */
        fun onCallCancel(peerIp: String) {}
        /** 收到一帧对端音频(读线程回调,尽快转交播放队列);ts是通话相对时间戳,不可与墙钟比 */
        fun onCallAudio(peerIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {}

        // ==================== 视频通话回调 ====================
        /** 视频呼叫请求(带视频协商):默认回落到纯语音版onCallInvite,不漏来电 */
        fun onCallInviteVideo(
            peerIp: String, name: String, vid: Boolean, lv: Int, af: Int = 0,
            mport: Int = 0, mudp: Boolean = false, msid: String = ""
        ) {
            onCallInvite(peerIp, name)
        }
        /** 视频接听应答（带档位、语音帧长与媒体通道协商），字段语义同 [onCallInviteVideo] */
        fun onCallAcceptVideo(
            peerIp: String, vid: Boolean, lv: Int, af: Int = 0,
            mport: Int = 0, mudp: Boolean = false, msid: String = ""
        ) {
            onCallAccept(peerIp)
        }
        /** 振铃期逐档媒体协商(§4.3①交换):对端把当前档本端三元组带过来;默认不做事 */
        fun onCallMedia(
            peerIp: String, mport: Int, mudp: Boolean, msid: String, mtier: Int
        ) {}
        /** 收到一帧对端视频(网络读线程回调):只许入队,禁止初始化解码器(阻塞读线程⇒35s判死) */
        fun onVideoFrame(peerIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {}
        /** 编码参数变更(必要时重建解码器);front=对端前置镜头,据此定默认显示角 */
        fun onVideoCfg(
            peerIp: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int,
            front: Boolean
        ) {}
        /** 接收侧健康反馈(发送侧升降档用);lat=对端排队时延ms,dropping=对端丢帧期 */
        fun onVideoFb(peerIp: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int) {}
        /** 接收端请求临时停/开视频；[key] = 请求立刻给一个 IDR（对端解码器刚重建，等不起 1s GOP） */
        fun onVideoReq(peerIp: String, on: Boolean, key: Boolean) {}

        // ==================== 数据流量确认（v2.7）====================

        /** 对端请求"用数据流量发":AppCore决定弹框或直接同意;绝不在读线程里阻塞等用户 */
        fun onDataAsk(peerIp: String, mid: String, kind: String, name: String, size: Long) {}

        /** 一条消息被移到"最新一条"(目前只有"数据流量拒绝后重试成功"这一条路径) */
        fun onMessageMoved(mid: String) {}
    }

    /** 对端能力（hello 协商所得）。发给某台设备前必须先看这个。 */
    class PeerCaps(
        @Volatile var vid: Boolean = false,
        @Volatile var cam: Boolean = false,
        @Volatile var vcodec: List<String> = emptyList(),
        /** 对端支持历史同步(hsync,v2.3);老端不带→false→不补齐 */
        @Volatile var hs: Boolean = false,
        /** 对端支持消息删除同步(msg_del,v2.4);老端不带→false→删除不跨端 */
        @Volatile var hd: Boolean = false,
        /** 对端支持文件夹完整性校验(dir_meta.list/dir_end/dir_missing,v2.6);老端→不做清单核对 */
        @Volatile var dl: Boolean = false,

        /** 对端支持数据流量确认(data_ask/data_ans,v2.7);老端→沿用"不在局域网段就提醒"老口径 */
        @Volatile var dm: Boolean = false
    )

    /** 本端能力(由AppCore注入,握手时广播);必须在link.start()之前设置,否则首轮握手广播错误能力 */
    @Volatile
    var localVidCap: Boolean = false

    @Volatile
    var localCamCap: Boolean = false

    @Volatile
    var localVCodecs: List<String> = emptyList()

    @Volatile
    var listener: Listener? = null

    @Volatile
    var state: State = State.STOPPED
        internal set

    /** 状态栏附注文案（当前对话设备名 / 「连接失败，点设备重试」这类） */
    @Volatile
    var peerLabel: String = ""
        internal set

    @Volatile
    internal var running = false

    internal var serverSocket: ServerSocket? = null
    internal val sessions = CopyOnWriteArrayList<Session>()

    /** 按对端IP索引的会话表(多设备并发的核心);每台连上的设备各一条独立长连接,互不干扰 */
    internal val byPeer = ConcurrentHashMap<String, Session>()

    /** 当前对话（也是主动发送的唯一目标）设备 IP；空 = 未选中 */
    @Volatile
    var selectedIp: String = ""
        internal set

    /** 当前对话设备在设备列表里的显示名 */
    @Volatile
    var selectedLabel: String = ""
        internal set

    /** 一次性连接请求队列(同设备候选地址按优先级排);只在点击设备/启动自动连接时投递 */
    @Volatile
    internal var pendingConnectIps: MutableList<String>? = null

    /** "点的那个地址连不上、同设备另一地址连上了"的回调(请求地址→实际连上的地址) */
    @Volatile
    var onGroupFallback: ((requestedIp: String, actualIp: String) -> Unit)? = null

    // ==================== 传输速率（气泡显示用）====================

    /** 传输速率回调(mid,字节/秒),节流最快500ms一次;回调在发送/读线程,上层自切主线程 */
    @Volatile
    var onSpeed: ((mid: String, bps: Long) -> Unit)? = null

    private val speedMeters = java.util.concurrent.ConcurrentHashMap<String, SpeedMeter>()

    /** 记一笔字节总数（发送/接收循环里调）：够一个采样间隔就回调一次 [onSpeed] */
    internal fun markSpeed(mid: String, bytes: Long) {
        if (mid.isEmpty() || bytes <= 0L) return
        val m = speedMeters.computeIfAbsent(mid) { SpeedMeter() }
        m.sample(bytes) { bps -> onSpeed?.invoke(mid, bps) }
    }

    /** 这一条传完/失败/取消 → 丢掉采样器（避免下次同 mid 拿着旧基线算出假速率） */
    internal fun endSpeed(mid: String) {
        if (mid.isNotEmpty()) speedMeters.remove(mid)
    }

    /** 生成一台设备的候选地址顺序:传入地址排第一,同设备其它地址按最近在线时间倒序 */
    internal fun groupCandidates(ip: String, tryGroup: Boolean = true): List<String> {
        val v = ip.trim()
        if (v.isEmpty()) return emptyList()
        if (!tryGroup) return listOf(v)
        val out = LinkedHashSet<String>()
        out.add(v)
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        if (k.isEmpty() || k == v) return out.toList()
        val others: List<Peer> = runCatching { prefs.peers }.getOrDefault(emptyList())
        others.filter { p ->
            p.ip != v && p.ip.isNotBlank() &&
                runCatching { prefs.convKeyOf(p.ip) }.getOrNull() == k
        }
            .sortedByDescending { it.lastSeenAt }
            .forEach { out.add(it.ip) }
        return out.toList()
    }

    /** 被动连上 / 已连上：撤掉同设备还没轮到的候选地址（不再白试） */
    internal fun clearPendingFor(ip: String) {
        val q = pendingConnectIps ?: return
        val v = ip.trim()
        if (v.isEmpty()) return
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        val drop = q.firstOrNull { c ->
            c == v || (k.isNotEmpty() && runCatching { prefs.convKeyOf(c) }.getOrNull() == k)
        }
        if (drop != null) {
            pendingConnectIps = null
            log("设备 $v 已可达，取消剩余候选地址 ${q.joinToString(", ")}")
        }
    }

    /** 出向连接闸门(AppCore注入,true=拒绝):"后台只允许连已知设备"的落点 */
    @Volatile
    var outgoingGate: ((ip: String) -> Boolean)? = null

    /** 入向连接闸门(AppCore注入,true=拒绝):后台不接受陌生设备主动连入;命中即close不发hello */
    @Volatile
    var incomingGate: ((ip: String) -> Boolean)? = null

    /** "本机当前是否走运营商数据网络"(AppCore注入);只有本机真在花钱流量时才需提醒 */
    @Volatile
    var localOnCellular: (() -> Boolean)? = null

    /** 本机"数据流量提醒"确认框(AppCore注入);会阻塞到用户点按钮,只许专用后台线程调 */
    @Volatile
    var localDataConsent: ((peerIp: String, label: String) -> Boolean)? = null

    /** "已知设备"名单(AppCore注入);只增不减,与聊天记录解耦(清空消息不影响判定) */
    @Volatile
    var trusted: com.phonepctransfer.data.TrustedPeers? = null

    /** 打点：与该对端有过消息/文件往来。任何异常都不能影响收发主流程 */
    internal fun trustContent(ip: String, isFile: Boolean) {
        runCatching { trusted?.markContent(ip, isFile) }
    }

    /** 正在连接中的目标 IP（仅用于状态展示） */
    @Volatile
    internal var connectingIp: String? = null

    /** 状态附注（「连接失败，点设备重试」这类） */
    @Volatile
    internal var stateNote = ""

    /** 兼容旧调用：当前对话设备的 IP（未选中为 null） */
    val peerIp: String?
        get() = selectedIp.ifEmpty { null }

    internal val reconnectLock = Object()

    internal var acceptThread: Thread? = null
    internal var connectThread: Thread? = null
    internal var heartbeatThread: Thread? = null

    internal val myId: String get() = prefs.deviceId
    internal val myName: String get() = prefs.deviceName

    // ==================== 会话 ====================

    internal inner class Session(val socket: Socket, val outgoing: Boolean) {
        val input: InputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        /** 写锁:保证帧不交错;心跳用tryLock,写锁被文件传输占用时跳过本轮ping */
        internal val writeLock = ReentrantLock()

        /** 对端 IP，构造时定下（socket 关了也能读，用来做归属与状态判定） */
        val remoteIp: String =
            runCatching { socket.inetAddress?.hostAddress }.getOrNull().orEmpty()

        @Volatile var peerId = ""
        @Volatile var peerName = ""
        @Volatile var handshaked = false

        /** 对端能力（hello 协商所得）。握手前全 false —— 因此默认绝不会发视频帧。 */
        val caps = PeerCaps()

        @Volatile var lastRx = System.currentTimeMillis()
        @Volatile var lastTx = System.currentTimeMillis()
        /** 已发出 ping 但之后未收到任何帧（含 pong）时为 true —— 判死依据，防自身 ping 刷 lastTx 骗过看门狗 */
        @Volatile var pingOutstanding = false
        @Volatile var closed = false

        /** 收到任何帧(pong/数据分片/音视频/控制)时调:刷新存活证据并清 ping 未回标志(判死只看收包) */
        internal fun touchRx() {
            lastRx = System.currentTimeMillis()
            pingOutstanding = false
        }

        init {
            // 大一点的收发缓冲：分片 1MB 起，默认 8KB 太小会白白多走系统调用
            runCatching {
                socket.sendBufferSize = 1 shl 20
                socket.receiveBufferSize = 1 shl 20
                // 诊断:读回内核实际值;窗口缩放手握时已定死,这里再设只改缓冲,读回值仅供对照
                log("[sock] $remoteIp snd=${socket.sendBufferSize}" +
                        " rcv=${socket.receiveBufferSize}" + (if (outgoing) " (主动)" else " (被动)"))
            }
        }

        @Throws(Exception::class)
        fun send(j: JSONObject) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                Protocol.write(output, j)
                lastTx = System.currentTimeMillis()
            } finally {
                writeLock.unlock()
            }
        }

        /** 二进制文件分片：与 JSON 控制帧共用写锁，保证帧不交错 */
        @Throws(Exception::class)
        fun sendChunk(mid: String, seq: Int, buf: ByteArray, off: Int, len: Int) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                Protocol.writeChunk(output, mid, seq, buf, off, len)
                lastTx = System.currentTimeMillis()
            } finally {
                writeLock.unlock()
            }
        }

        /** 语音音频帧:与控制帧/文件分片共用写锁;返回写入耗时ms(上层判拥塞) */
        @Throws(Exception::class)
        fun sendAudio(seq: Int, ts: Long, buf: ByteArray, off: Int, len: Int): Long {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                val t0 = System.nanoTime()
                Protocol.writeAudio(output, seq, ts, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeLock.unlock()
            }
        }

        /** 视频帧:与音频/文件分片共用写锁;返回写入耗时ms;超阈值只记drop不重发 */
        @Throws(Exception::class)
        fun sendVideo(seq: Int, ts: Long, flag: Int, buf: ByteArray, off: Int, len: Int): Long {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                val t0 = System.nanoTime()
                Protocol.writeVideo(output, seq, ts, flag, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeLock.unlock()
            }
        }

        /** 心跳专用tryLock发ping:false=写锁被文件传输占用(链路活着,跳过);发送异常转判死 */
        fun tryPing(): Boolean {
            if (!writeLock.tryLock()) return false
            return try {
                if (closed) {
                    true
                } else {
                    Protocol.write(output, Protocol.ping())
                    lastTx = System.currentTimeMillis()
                    true
                }
            } catch (t: Throwable) {
                close()  // 与 sendOrDrop 同语义：发送失败即判死
                true
            } finally {
                writeLock.unlock()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }

    /** 按IP找已握手且活着的会话;同IP多台时优先返回组键一致那条(Prefs认定的活动设备) */
    internal fun sessionOf(ip: String?): Session? {
        if (ip.isNullOrEmpty()) return null
        val want = runCatching { prefs.convKeyOf(ip) }.getOrNull().orEmpty()
        var fallback: Session? = null
        for (s in sessions) {
            if (!s.handshaked || s.closed || ip != s.remoteIp) continue
            if (want.isNotEmpty() && want == s.peerId) return s
            if (fallback == null) fallback = s
        }
        return fallback
    }

    /** 指定 deviceId 的设备是否有健康会话（同 IP 多设备的别名行打点用） */
    fun isPeerConnected(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val s = byPeer[deviceId] ?: return false
        return s.handshaked && !s.closed
    }

    /** 该地址上正连着、但不是keepDevId这台设备的对端名(同IP多设备冲突判定用) */
    fun foreignPeerAt(ip: String, keepDevId: String): String? {
        val v = ip.trim()
        if (v.isEmpty()) return null
        for (s in sessions) {
            if (!s.handshaked || s.closed || v != s.remoteIp) continue
            if (keepDevId.isNotEmpty() && keepDevId == s.peerId) continue
            return s.peerName.ifEmpty { s.peerId }
        }
        return null
    }

    /** 断开某地址上的会话(同IP多设备冲突时"断开之前的连接");只断旧设备会话,别的不许动 */
    fun disconnectAt(ip: String, keepDevId: String = ""): Int {
        val v = ip.trim()
        if (v.isEmpty()) return 0
        var n = 0
        for (s in sessions) {
            if (v != s.remoteIp) continue
            if (keepDevId.isNotEmpty() && keepDevId == s.peerId) continue
            log("断开 $v 上的旧连接（${s.peerName.ifEmpty { s.peerId }}，切换设备）")
            s.close()
            sessions.remove(s)
            if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
            n++
        }
        if (n > 0) {
            notifyPeers()
            refreshState()
        }
        return n
    }

    /** 当前会话(主动发送目标);未选中/未连接返回null;选中地址没会话时回落同设备另一地址 */
    internal fun target(): Session? = sessionByGroup(selectedIp)

    /** 指定 IP 的设备当前是否已连接（设备列表打点用，与选中与否无关） */
    fun isConnectedIp(ip: String): Boolean = sessionOf(ip) != null

    /** 指定设备是否可达:组内任意地址有健康会话即算(不该取决于选了哪个地址) */
    fun isDeviceConnected(ip: String): Boolean = sessionByGroup(ip) != null

    /** 已经连上的设备 IP 集合 */
    val connectedIps: Set<String>
        get() {
            val out = LinkedHashSet<String>()
            for (s in sessions) {
                if (s.handshaked && !s.closed) out.add(s.remoteIp)
            }
            return out
        }

    // ==================== 生命周期 ====================

    fun start() {
        if (running) return
        running = true
        // 线程池是一次性的:stop()会shutdownNow,重启链路必须重建,否则execute抛RejectedExecutionException
        if (keepAliveExec.isShutdown) keepAliveExec = newKeepAliveExec()
        if (fileSendExec.isShutdown) fileSendExec = newFileSendExec()
        if (sendExec.isShutdown) sendExec = newSendExec()
        store?.purgeStalePartials(PARTIAL_MAX_AGE_MS)
        log(
            "链路启动：本机 id=$myId name=$myName 监听 ${prefs.tcpPort}" +
                "（协议 v${Protocol.PROTO_VER}，多设备独立长连接）"
        )

        acceptThread = Thread({ acceptLoop() }, "pp-accept").apply { isDaemon = true }.also { it.start() }
        connectThread = Thread({ connectLoop() }, "pp-connect").apply { isDaemon = true }.also { it.start() }
        heartbeatThread = Thread({ heartbeatLoop() }, "pp-heartbeat").apply { isDaemon = true }.also { it.start() }
        refreshState()
    }

    fun stop() {
        if (!running) return
        running = false
        keepAliveJobs.values.forEach { it.cancel(false) }
        keepAliveJobs.clear()
        // 发送线程池一并停：停止后排队任务不得再对着已关的 socket 写
        keepAliveExec.shutdownNow()
        fileSendExec.shutdownNow()
        sendExec.shutdownNow()
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (s in sessions) s.close()
        sessions.clear()
        byPeer.clear()
        // 停止前取消的mid不会被发送循环处理到,清掉避免残留(重启后不应有"幽灵取消"命中新传输)
        cancelled.clear()
        pendingConnectIps = null
        connectingIp = null
        wakeUp()
        setState(State.STOPPED, "")
        log("链路已停止")
    }

    /** 当前对话设备是否已连接 */
    fun isConnected(): Boolean = target() != null

    /** 点选一台设备→设为当前对话目标;只改目标不碰别的会话(多设备互不干扰) */
    fun selectTarget(ip: String, label: String? = null) {
        val v = ip.trim()
        if (v.isEmpty()) return
        selectedIp = v
        if (!label.isNullOrEmpty()) selectedLabel = label
        refreshState()
    }

    /** 点选设备并按需连接:已连着只切过去,没连才发起一次尝试;失败不重试(只有点击才触发重连) */
    fun selectAndConnect(ip: String, label: String? = null) {
        selectTarget(ip, label)
        connectTo(ip)
    }

    /** 发起一次连接尝试(不重试);已有健康会话则不做事;quiet=true不打"请求连接"日志(保活用) */
    fun connectTo(ip: String, quiet: Boolean = false, tryGroup: Boolean = true) {
        val v = ip.trim()
        if (v.isEmpty()) return
        val ex = sessionOf(v)
        if (ex != null) {
            // 已有会话≠健康:TCP半开时对端进程死了socket仍"连着"但收不到帧;
            // 超一轮心跳周期无收包且ping未回⇒判失效、废弃旧会话并重连
            val rxIdle = System.currentTimeMillis() - ex.lastRx
            if (!(ex.pingOutstanding && rxIdle > STALE_RX_MS)) {
                if (!quiet) log("已连接 $v，无需重连")
                refreshState()
                return
            }
            log("会话 $v 已 ${rxIdle}ms 无收包（疑似半开），废弃后重连")
            ex.close()
            sessions.remove(ex)
            if (ex.peerId.isNotEmpty()) byPeer.remove(ex.peerId, ex)
            notifyPeers()
        }
        // 同设备的另一个地址已经连着 → 这台设备已可达，不用再拨（见 sessionByGroup）
        if (sessionByGroup(v) != null) {
            if (!quiet) log("设备 $v 已通过同设备其它地址连接，无需重连")
            refreshState()
            return
        }
        // 硬规则:后台只允许连"已知设备"(连接过+有消息/文件往来);其余只许前台点击建连;
        // 入向同规则:后台陌生设备主动连入由incomingGate拒接(见adopt)
        if (outgoingGate?.invoke(v) == true) {
            stateNote = "后台不允许连接未知设备"
            log("后台建连被拒：$v 不在已知设备名单（需先前台连接并收发过消息/文件）")
            refreshState()
            return
        }
        val cands = groupCandidates(v, tryGroup)
        pendingConnectIps = java.util.Collections.synchronizedList(ArrayList(cands))
        stateNote = ""
        if (!quiet) {
            log(
                if (cands.size > 1) "请求连接设备 $v（依次尝试 ${cands.joinToString(" → ")}，成功即停、失败不重试）"
                else "请求连接设备 $v（手动触发，失败不自动重试）"
            )
        }
        wakeUp()
    }

    // ==================== 保活重连 ====================

    /** 连接成功过就保持长连接;只对本端发起成功过的会话自动重连;断开后5s/10s/30s重试,仍不通转纯被动 */
    @Volatile
    internal var keepAliveExec: java.util.concurrent.ScheduledExecutorService =
        newKeepAliveExec()

    private fun newKeepAliveExec(): java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pp-keepalive").apply { isDaemon = true }
        }
    internal val keepAliveJobs =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /** "本端正在传文件/通话"判据(AppCore注入);保活阶梯走完时定转纯被动还是继续重试 */
    @Volatile
    var keepAliveBusy: (() -> Boolean)? = null

    // ==================== 2.12 / D16：文件发送的排他投票表 ====================

    /** 文件/目录发送的排他投票:裁决=三票取最大(任一为1即1);1时新条目不入网改走待发队列,
     *  在传那条在下一分片/成员边界就地暂停;与AvTransport.aggregationAllowed()是两个判据,不许共用读口 */
    fun fileGateBlocked(): Boolean = voteInCall || voteNoNetwork || voteConsentDenied

    @Volatile
    private var voteInCall = false

    @Volatile
    private var voteNoNetwork = false

    @Volatile
    private var voteConsentDenied = false

    /** 写一票:只在校裁翻转(有1⇄全0)时打日志;全0那一刻自动触发一次续发;
     *  VOTE_NO_NETWORK的解除点=握手完成(那里已有flushPending),不重复触发免得同条排两次 */
    @Synchronized
    fun setFileVote(which: Int, on: Boolean) {
        val before = fileGateBlocked()
        when (which) {
            VOTE_IN_CALL -> voteInCall = on
            VOTE_NO_NETWORK -> voteNoNetwork = on
            VOTE_CONSENT_DENIED -> voteConsentDenied = on
            else -> return
        }
        val after = fileGateBlocked()
        if (before == after) return
        log(
            if (after) "[文件排他] 投票翻转 0→1 ⇒ 文件与目录不入网、在传的暂停（D16）"
            else "[文件排他] 投票翻转 1→0 ⇒ 自动续发一次（D16）"
        )
        if (!after && which != VOTE_NO_NETWORK) flushPending()
    }

    // ==================== 文件发送队列 ====================

    /** 文件/文件夹发送队列:单线程串行,一次只传一个;不共用sendExec(消息/ack/剪贴板不能排队在文件后),
     *  音视频/消息按帧粒度抢写锁,不进这条队列 */
    @Volatile
    internal var fileSendExec: java.util.concurrent.ExecutorService =
        newFileSendExec()

    private fun newFileSendExec(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pp-file-send").apply { isDaemon = true }
        }

    /** 当前排队等待的文件 / 文件夹条数（不含正在传的那一个） */
    internal fun fileSendQueueDepth(): Int =
        (fileSendExec as? java.util.concurrent.ThreadPoolExecutor)?.queue?.size ?: 0



    // ==================== 帧处理 ====================

    /** 这条链路允许同步剪贴板吗——只有局域网;判据用LanGate.isLanPeerIp,先解掉::ffff:映射 */
    internal fun clipLinkAllowed(ip: String?): Boolean {
        val v = ip?.trim()?.substringBefore('%') ?: return false
        if (v.isEmpty()) return false
        val bare = if (v.lowercase().startsWith("::ffff:")) v.substring(7) else v
        return LanGate.isLanPeerIp(bare)
    }

    internal fun handleFrame(s: Session, j: JSONObject) {
        val type = j.optString("type")
        // 剪贴板只走局域网链路:其余链路收到就当没收到,不答clip_pull/clip_state;文字/文件/通话不受影响
        if (type.startsWith("clip") && !clipLinkAllowed(s.remoteIp)) return
        when (type) {
            "hello" -> onHello(s, j)
            "ping" -> sendOrDrop(s, Protocol.pong(), "pong", quiet = true)
            "pong" -> Unit
            "text" -> {
                val mid = j.optString("mid", newMid())
                // 统一按mid查重:待发补发/hsync补齐可能重放同一条;已有mid不再生成第二气泡,ack照回
                if (store?.hasMid(s.remoteIp, mid) == true) {
                    log("文本 mid=$mid 本机已有（重复投递），跳过")
                } else {
                    val m = MsgItem(
                        mid = mid,
                        dir = Dir.PEER, kind = Kind.TEXT,
                        text = j.optString("text", ""),
                        ts = j.optLong("ts", System.currentTimeMillis()),
                        peerIp = s.remoteIp
                    )
                    trustContent(s.remoteIp, isFile = false)
                    emit(m)
                }
                sendOrDrop(s, Protocol.ack(mid, true), "ack", quiet = true)
            }
            "clip" -> {
                // 剪贴板同步不是"发消息":直接写本机剪贴板,不生成气泡不进聊天记录
                val text = j.optString("text", "")
                if (text.isNotEmpty() && !isDuplicateClip(text)) {
                    listener?.onClipToClipboard(text)
                }
            }
            "clip_state" -> {
                val text = j.optString("text", "")
                if (text.isNotEmpty()) {
                    if (!isDuplicateClip(text)) {
                        listener?.onClipToClipboard(text)
                        log("已接收对端剪贴板 ${text.length} 字")
                    }
                } else {
                    // 对端明说缓冲为空→反过来把本机的推过去;本机也读不到时AppCore什么都不做
                    log("对端剪贴板为空，改为推送本机剪贴板")
                    listener?.onPeerClipEmpty()
                }
            }
            "clip_notify" -> {
                // 兼容旧 PC 的通知帧（新 PC 已改为直接推正文）。纯被动：不回拉。
                log("电脑剪贴板更新（${j.optInt("len", 0)} 字）")
            }
            "clip_pull" -> {
                // 对端请求本机剪贴板缓冲:读得到回正文,读不到(后台无焦点静默失败)回空,如实反映
                val text = Clip(ctx).read() ?: ""
                sendOrDrop(
                    s,
                    Protocol.clipState(text, if (text.isEmpty()) "" else Clip.sha1(text)),
                    "clip_state"
                )
                if (text.isNotEmpty()) log("已应答对端剪贴板请求 ${text.length} 字")
            }
            "file_meta", "file_done" -> onFileFrame(s, j)
            "file_query" -> onFileQuery(s, j)
            "file_resume" -> onFileResume(j)
            "file_verify" -> onFileVerify(j)
            "dir_meta" -> onDirMeta(s, j)
            "dir_end" -> onDirEnd(s, j)
            "dir_missing" -> onDirMissing(j)
            "file_cancel" -> onRemoteFileCancel(j.optString("mid"))
            "sync_req" -> flushPending(s.remoteIp)
            "hsync_mids" -> Thread({ onHsyncMids(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "hsync_pull" -> Thread({ onHsyncPull(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "hsync_msg" -> Thread({ onHsyncMsg(s, j) }, "pp-hsync").apply { isDaemon = true }.start()
            "msg_del" -> Thread({ onRemoteMsgDel(s, j) }, "pp-msgdel").apply { isDaemon = true }.start()
            "ack" -> {
                val ackMid = j.optString("mid", "")
                // 我方正在等这条文件的落盘回报 → 送达成立与否**由它决定**（出待发队列 + 记台账）
                if (onSendAck(ackMid, j.optBoolean("ok", true))) return
                // 其余ack(文本/剪贴板/文件夹):本地成功路径已置OK,再markStatus会发裸帧污染文件气泡,故只记日志
                log("对端已确认收到 mid=$ackMid")
            }
            "call_invite" -> {
                // 带视频协商(vid/lv)、语音帧长(af)与媒体三元组(mport/mudp/msid);缺省即回退⇒只落带内档
                listener?.onCallInviteVideo(
                    s.remoteIp, j.optString("name", ""),
                    j.optBoolean("vid", false), j.optInt("lv", 1), j.optInt("af", 0),
                    j.optInt("mport", 0), j.optBoolean("mudp", false), j.optString("msid", "")
                )
            }
            "call_accept" -> listener?.onCallAcceptVideo(
                s.remoteIp, j.optBoolean("vid", false), j.optInt("lv", 1), j.optInt("af", 0),
                j.optInt("mport", 0), j.optBoolean("mudp", false), j.optString("msid", "")
            )
            "call_media" ->
                // 振铃期逐档协商(任务单2.5);老对端不发;读到缺省=-1"未声明"
                listener?.onCallMedia(
                    s.remoteIp, j.optInt("mport", 0), j.optBoolean("mudp", false),
                    j.optString("msid", ""), j.optInt("mtier", -1)
                )
            "call_reject" -> listener?.onCallReject(s.remoteIp)
            "call_busy" -> listener?.onCallBusy(s.remoteIp)
            "call_end" -> listener?.onCallEnd(s.remoteIp, j.optString("reason", ""))
            "call_cancel" -> listener?.onCallCancel(s.remoteIp)
            "video_cfg" -> listener?.onVideoCfg(
                s.remoteIp, j.optString("codec", "hevc"),
                j.optInt("w", 1280), j.optInt("h", 720), j.optInt("fps", 30),
                j.optInt("br", 1500), j.optInt("rot", 0),
                // cam：1 = 前置，0/缺省 = 后置（老对端不带此键 → 行为与改造前一致）
                j.optInt("cam", 0) != 0
            )
            "video_fb" -> listener?.onVideoFb(
                s.remoteIp, j.optInt("fps", 0), j.optInt("drop", 0),
                j.optInt("lat", 0),
                // dropping：老对端不带此键 = false（与改造前行为一致）
                j.optBoolean("dropping", false), j.optInt("kbps", 0)
            )
            // key：老对端不带此键 = false（与改造前行为一致）
            "video_req" -> listener?.onVideoReq(
                s.remoteIp, j.optBoolean("on", true), j.optBoolean("key", false)
            )
            // ==================== 数据流量确认（v2.7）====================
            // data_ask交上层(可能弹框问本机用户);data_ans直接投进等待信箱(阻塞等结果的唤醒信号,不许过可能被拖延的队列)
            "data_ask" -> listener?.onDataAsk(
                s.remoteIp, j.optString("mid", ""), j.optString("kind", DataConsentText.KIND_FILE),
                j.optString("name", ""), j.optLong("size", 0L)
            )
            "data_ans" -> {
                val mid = j.optString("mid", "")
                dataAnsBoxes[mid]?.offer(j.optBoolean("ok", false))
            }
            "busy" -> {
                // 只可能来自旧版本对端(同一时刻只服务一个对端);新版不发,本端也不自动重试
                val holder = j.optString("holder", "")
                log(
                    "对端（旧版本）返回忙" + (if (holder.isEmpty()) "" else "：正与 $holder 会话") +
                        "，本端不自动重试，稍后再点一次设备"
                )
                stateNote = "对端忙（旧版本同时只服务一个对端），稍后再点一次"
                s.close()
                sessions.remove(s)
                if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
                notifyPeers()
                refreshState()
            }
            else -> log("未知帧类型: ${j.optString("type")}")
        }
    }

    /** 握手完成:把会话并入集合;不同设备一律照收,只对同设备双连接去重(preferOutgoing=myId<peerId) */
    internal fun onHello(s: Session, j: JSONObject) {
        val pid = j.optString("id", "")
        if (pid.isEmpty()) {
            s.close()
            sessions.remove(s)
            return
        }
        s.peerId = pid
        s.peerName = j.optString("name", pid)
        s.handshaked = true

        // ===== 能力协商(§4.3兼容性闸门):老端不带字段→vid=false→永不发视频帧(老端遇0x03会断链) =====
        s.caps.vid = j.optBoolean("vid", false)
        s.caps.cam = j.optBoolean("cam", false)
        val vc = mutableListOf<String>()
        val arr = j.optJSONArray("vcodec")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) vc.add(v)
            }
        }
        s.caps.vcodec = vc
        s.caps.hs = j.optBoolean("hs", false)
        s.caps.hd = j.optBoolean("hd", false)
        s.caps.dl = j.optBoolean("dl", false)
        // 数据流量确认（v2.7）：老端不带该位 → 不发 data_ask，退回「对端不在局域网段就提醒本机」老口径
        s.caps.dm = j.optBoolean("dm", false)

        log(
            "握手成功 ← ${s.peerName}($pid) ${s.remoteIp} ${if (s.outgoing) "出向" else "入向"}" +
                " caps[vid=${s.caps.vid}, cam=${s.caps.cam}, vcodec=$vc, hs=${s.caps.hs}, hd=${s.caps.hd}, dl=${s.caps.dl}]"
        )
        // "双方连接过"在这里才成立→记进已知设备名单;放在去重之前(被丢弃那条也算真实连接)
        runCatching { trusted?.markConnected(s.remoteIp, s.peerName, pid) }

        // ===== 设备身份绑定:对端id绑到当前IP;同id多IP=同一设备多地址⇒Prefs自动成组;
        //  必须早于hsync/flushPending(那两步按设备组键读写) =====
        val bound = runCatching { prefs.bindDeviceIfFree(s.remoteIp, pid) }.getOrDefault(false)
        if (bound) {
            log("设备身份绑定：${prefs.describeDevice(s.remoteIp)}")
            // 绑定变了=组键可能从"IP"变"deviceId":通知重建设备列表,组键真变时AppCore重开该设备聊天界面
            notifyPeers()
        }
        // 入向且本机列表没有该地址→登记一条(不覆盖备注);握手是拿到其身份的唯一来源,不登记抽屉里不出现
        runCatching {
            if (prefs.peers.none { it.ip == s.remoteIp }) {
                prefs.upsertPeer(
                    s.remoteIp, remark = null, lastId = pid, lastName = s.peerName,
                    seenAt = System.currentTimeMillis()
                )
                log("新设备已登记：${s.peerName} @ ${s.remoteIp}")
            }
        }

        var reject = false
        val exist = byPeer[pid]
        if (exist != null && exist !== s && !exist.closed && exist.handshaked) {
            // 同设备双连接（两端同时启动各连了一次）：保留符合 preferOutgoing 的那条
            val preferOutgoing = myId < pid
            val take = (s.outgoing == preferOutgoing)
            log("重复会话去重：${if (take) "保留新连接" else "保留旧连接"}（同设备双连接）")
            if (!take) {
                reject = true
            } else {
                exist.close()
                sessions.remove(exist)
                byPeer.remove(pid, exist)
            }
        }
        if (reject) {
            s.close()
            sessions.remove(s)
            return
        }
        byPeer[pid] = s
        // 连上了 → 保活重连任务完成使命
        cancelKeepAlive(s.remoteIp)
        // 这台设备已可达 → 撤掉还没轮到的候选地址（可能是被动连上，也可能是对端抢先拨通）
        clearPendingFor(s.remoteIp)

        // 第一台连上的设备默认成为当前对话目标;选中目标无健康会话时也跟过去;
        // 判据按设备(组内任一地址健康)不能用精确IP,否则另一台设备的握手会抢走当前对话
        if (selectedIp.isEmpty() || sessionByGroup(selectedIp) == null) {
            selectedIp = s.remoteIp
            selectedLabel = s.peerName
        }
        notifyPeers()
        refreshState()
        // 这台设备可达→撤掉"没网络"那一票;下面既有flushPending负责续发(这一票不再自动flush,免排两次)
        setFileVote(VOTE_NO_NETWORK, false)
        // 只补发**发给这台设备**的待发内容；剪贴板不做任何握手协商（读取只由焦点切换触发）
        flushPending(s.remoteIp)
        // 历史同步：双方都支持（hs=1）时互报 mid 清单、各取所需（见 onHsyncMids）
        if (s.caps.hs) {
            Thread({ startHsync(s) }, "pp-hsync").apply { isDaemon = true }.start()
        }
    }

    /** 这条会话是不是当前对话目标 */
    internal fun isSelected(s: Session): Boolean = s.remoteIp == selectedIp

    internal fun setState(st: State, peer: String) {
        if (state == st && peerLabel == peer) return
        state = st
        peerLabel = peer
        listener?.onState(st, peer)
    }

    /** 依据"会话集合+当前选中目标"推导对外状态;推导不命令式赋值,不受去重关连接顺序影响 */
    internal fun refreshState() {
        val st: State
        val label: String
        if (!running) {
            st = State.STOPPED
            label = ""
        } else if (target() != null) {
            st = State.CONNECTED
            label = selectedLabel.ifEmpty { selectedIp }
        } else if (connectingIp != null) {
            st = State.CONNECTING
            label = ""
        } else {
            st = State.IDLE
            label = stateNote
        }
        setState(st, label)
    }

    /** 会话集合变化（连上 / 断开）时通知 UI 刷设备列表 */
    internal fun notifyPeers() {
        listener?.onPeersChanged()
    }

    // ==================== 文件：接收（双 4MB 缓冲 + 断点续传） ====================

    /** 一个正在接收的文件:双缓冲(各4MB),一块填满交落盘线程自己切另一块;都未落完才阻塞(背压);
     *  digest覆盖整个文件(含续传前缀),file_done时与整文件sha256比对 */
    internal val rxMap = HashMap<String, Rx>()
    internal val dirMap = HashMap<String, DirRx>()

    /** 被停滞暂停的单文件mid(v2.8):下一个进度帧到达时气泡回"发送中"并清标记;目录成员不用标记 */
    internal val pausedMids = ConcurrentHashMap.newKeySet<String>()

    /** 保留多少条「已收尾」的目录记录（供同一 mid 重发时复用目录名，见 pruneDirs） */
    internal val MAX_KEEP_DIRS = 32

    /** 发送端等 file_resume 应答用：mid → 容量为 1 的信箱 */
    internal val resumeWaiters =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Long>>()

    /** 发起端等data_ans应答用:mid→容量1的信箱;读完即摘,不随传输次数增长 */
    internal val dataAnsBoxes =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Boolean>>()

    /** 启动落盘线程：从 pending 取满块写盘，注册表同步刷新（供断点续传跨重启找回半成品） */

    // ==================== 发送 ====================

    /** 发送线程池(单线程保序);socket写必须离开主线程(否则NetworkOnMainThreadException),所有发送入口经过这里 */
    @Volatile
    internal var sendExec: java.util.concurrent.ExecutorService =
        newSendExec()

    private fun newSendExec(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pp-send").apply { isDaemon = true }
        }

    internal inline fun io(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            sendExec.execute { block() }
        } else {
            block()
        }
    }

    /** 主动发文本:目标是当前对话设备;未连接/失败入待发队列(带peer,只补发回这台) */
    fun sendText(text: String) {
        io {
            val s = target()
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.TEXT, text = text,
                peerIp = s?.remoteIp ?: selectedIp
            )
            // 主动发文本 → 该设备有过消息往来（即刻送达或进待发队列都算一次记录）
            trustContent(item.peerIp, isFile = false)
            if (s != null && sendOrDrop(s, Protocol.text(item.mid, text), "text")) {
                emit(item)
                log("已发送文本 ${text.length} 字 → ${item.peerIp}")
                return@io
            }
            log("文本暂存待发（等待连接）")
            emit(item.copy(status = Status.PENDING))
            queuePending(item)
            wakeUp()
        }
    }

    /** 主动推送本机剪贴板:不发气泡不进聊天记录,对端直接写进自己剪贴板(冲突时后写覆盖) */
    fun sendClip(text: String, sha1: String) {
        io {
            // 记下这份内容：之后从对端回读到的同一份不必再处理（避免重复写剪贴板）
            lastClipEmitSha1 = sha1
            // 剪贴板是同步通道不是消息：广播给**所有已连接设备**（多设备下每台都该拿到）
            val ips = connectedIps
            if (ips.isEmpty()) {
                log("剪贴板暂存待发（当前无已连接设备，连上后自动补发）")
                queuePending(
                    MsgItem(
                        mid = newMid(), dir = Dir.SELF, kind = Kind.CLIP, text = text,
                        peerIp = selectedIp ?: ""
                    )
                )
                wakeUp()
                return@io
            }
            var sent = 0
            for (ip in ips) {
                val s = sessionOf(ip) ?: continue
                if (!clipLinkAllowed(s.remoteIp)) continue
                sendOrDrop(s, Protocol.clip(newMid(), text, sha1), "clip")
                sent++
            }
            if (sent > 0) log("已推送剪贴板到 $sent 台设备（${text.length} 字）")
        }
    }

    /** 已被本端取消的文件 mid 集合：发送循环每轮检查，命中即中断 */
    internal val cancelled: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 文件夹发送状态：dirMid → 已完成的成员 relPath（重发时跳过） */
    internal val dirSendState = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()

    internal fun isCancelled(mid: String, dirId: String): Boolean =
        cancelled.contains(mid) || (dirId.isNotEmpty() && cancelled.contains(dirId))

    // ==================== 数据流量确认（v2.7）====================

    /** 供上层为一次确认生成 mid（确认帧与真正的传输共用同一个号，便于对账） */
    fun newConsentMid(): String = newMid()

    /** 发文件/语音/视频前统一过这道闸门,会阻塞(本机数据网→弹框;对端dm→data_ask等data_ans);
     *  绝不在主线程/读线程/io{}里调(ANR或卡死队列),调用点用专用线程 */
    fun ensureDataConsent(
        peerIp: String, mid: String, kind: String, label: String,
        name: String = "", size: Long = 0L
    ): DataConsent {
        val s = sessionByGroup(peerIp) ?: return DataConsent.NO_SESSION
        val peerDm = s.caps.dm
        // ① 本机提醒:本机在数据网;对端老版本时退回改造前口径(对端IP不在局域网段⇒仍提醒)
        val onCellular = runCatching { localOnCellular?.invoke() == true }.getOrDefault(false)
        if (onCellular || (!peerDm && !LanGate.isLanPeerIp(s.remoteIp))) {
            val ok = runCatching { localDataConsent?.invoke(s.remoteIp, label) == true }
                .getOrDefault(false)
            if (!ok) {
                log("本机未同意为「$label」消耗数据流量，已取消")
                return DataConsent.LOCAL_REFUSED
            }
        }
        // ② 对端确认:对端在自己数据网下会提醒其用户;不在数据网则立即回同意
        if (peerDm && !askPeerDataConsent(s, mid, kind, name, size)) {
            return DataConsent.REMOTE_REFUSED
        }
        return DataConsent.OK
    }

    /** 发 data_ask 并等 data_ans；超时/发送失败一律按「未同意」处理（宁可少发，不偷偷花流量） */
    internal fun askPeerDataConsent(
        s: Session, mid: String, kind: String, name: String, size: Long
    ): Boolean {
        val box = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        dataAnsBoxes[mid] = box
        return try {
            if (!sendOrDrop(s, Protocol.dataAsk(mid, kind, name, size), "data_ask", quiet = true)) {
                log("数据流量确认：对端会话不可用，按未同意处理")
                return false
            }
            val r = runCatching { box.poll(DATA_ANS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull()
            if (r == null) {
                log("数据流量确认：对端 ${DATA_ANS_WAIT_MS / 1000}s 内未应答，按未同意处理")
                false
            } else {
                r
            }
        } finally {
            dataAnsBoxes.remove(mid)
        }
    }

    /** 应答对端的数据流量确认(AppCore在用户点完/判定非数据网后调);内部走io{},读/主线程可调 */
    fun replyDataAsk(peerIp: String, mid: String, ok: Boolean) {
        if (mid.isEmpty()) return
        io {
            val s = sessionByGroup(peerIp)
            if (s == null) {
                log("数据流量确认应答丢弃：与 $peerIp 已无会话")
                return@io
            }
            sendOrDrop(s, Protocol.dataAns(mid, ok), "data_ans", quiet = true)
        }
    }

    // ==================== 主动发送 ====================

    /** 主动发文件(发给当前对话设备);v2.7起"数据流量确认"插在出气泡与开传之间,
     *  任一侧拒绝⇒气泡留在聊天里置DENIED(红色叹号可重试);整段跑在专用线程 */
    fun sendFile(uri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val name = FileIO.queryName(ctx, uri)
            val size = FileIO.querySize(ctx, uri)
            val s = sessionByGroup(ip)
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                fileSize = size, filePath = uri.toString(), localUri = uri.toString(), progress = 0,
                peerIp = s?.remoteIp ?: ip
            )
            // 主动发文件 → 该设备有过文件往来（已知设备判定的一半）
            trustContent(item.peerIp, isFile = true)
            // 2.12/D16:投票为1(通话中/没网络/被拒)⇒一律不入网,改走待发台账排队;
            //   拦截解除(全0)那一刻setFileVote自动触发一次续发;拦在入口而不是写者里
            if (s == null || fileGateBlocked()) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true)
                    log("离线，文件 $name 进入待发队列")
                } else {
                    log("[文件排他] 通话期不入网：文件 $name 排队，结束后自动续发（D16）")
                }
                wakeUp()
                return@Thread
            }
            emit(item.copy(status = Status.PENDING))
            // 确认+传输整段进【串行队列】:一次只传一个,前一个完成立刻上下一个;
            //   排队期间气泡PENDING,真正开传才刷SENDING
            enqueueFileSend("发送文件 $name") {
                val cs = sessionByGroup(item.peerIp)
                if (cs == null) {
                    okUpdate(item.copy(status = Status.PENDING))
                    queuePending(item)
                    setFileVote(VOTE_NO_NETWORK, true)   // 2.12：排队期间掉线 = "原本没网络"那一票
                    log("排队期间会话断开，文件 $name 进入待发队列")
                    wakeUp()
                    return@enqueueFileSend
                }
                val fileKind = DataConsentText.KIND_FILE
                val consent = if (preGranted) DataConsent.OK else
                    ensureDataConsent(cs.remoteIp, item.mid, fileKind, "发送文件", name, size)
                when (consent) {
                    DataConsent.OK -> {
                        okUpdate(item.copy(status = Status.SENDING))
                        doSendFile(uri, item)
                    }
                    DataConsent.LOCAL_REFUSED -> denyFileByData(item, LOCAL_DENY_TEXT)
                    DataConsent.REMOTE_REFUSED ->
                        denyFileByData(item, DataConsentText.peerRefusedText(fileKind))
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true)   // 2.12：确认期间掉线 = "原本没网络"票
                        okUpdate(item.copy(status = Status.PENDING))
                        queuePending(item)
                        log("确认期间会话断开，文件 $name 进入待发队列")
                        wakeUp()
                    }
                }
            }
        }, "pp-send-file").apply { isDaemon = true }.start()
    }

    /** 主动发文件夹(发给当前对话设备),空文件夹也照发;treeUri支持SAF目录树或本机file://路径 */
    fun sendFolder(treeUri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val scan = FileIO.scanTree(ctx, treeUri)
            val files = scan.files
            if (scan.skipped > 0) {
                log("注意：目录过深/过大或部分子树读取失败，${scan.skipped} 个分支未包含在传输中")
            }
            // 一个文件都没读到、却又有整块读失败:是"读不到"不是"空的",不照发count=0的假成功
            if (files.isEmpty() && scan.skipped > 0) {
                log("文件夹内容读取失败（${scan.skipped} 个分支未读到），已中止发送 —— 请确认该文件夹仍可访问")
                return@Thread
            }
            val name = FileIO.queryName(ctx, treeUri)
            val total = files.fold(0L) { acc, f -> acc + f.size.coerceAtLeast(0) }
            val s = sessionByGroup(ip)
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                fileSize = total, filePath = treeUri.toString(), localUri = treeUri.toString(),
                progress = 0, isDir = true, dirCount = files.size,
                peerIp = s?.remoteIp ?: ip
            )
            // 主动发文件夹 → 同上（空文件夹也打点，与接收侧 dir_meta 对称）
            trustContent(item.peerIp, isFile = true)
            // 2.12 / D16：同 sendFile —— 投票 = 1 时文件夹也不入网，整目录排队
            if (s == null || fileGateBlocked()) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true)
                    log("离线，文件夹 $name（${files.size} 个文件）进入待发队列")
                } else {
                    log("[文件排他] 通话期不入网：文件夹 $name（${files.size} 个文件）排队，结束后自动续发（D16）")
                }
                wakeUp()
                return@Thread
            }
            emit(item.copy(status = Status.PENDING))
            // 同上：整个文件夹算**一个**排队任务，其成员在队列线程内顺序发（一次仍只有一个文件在传）
            enqueueFileSend("发送文件夹 $name") {
                val cs = sessionByGroup(item.peerIp)
                if (cs == null) {
                    okUpdate(item.copy(status = Status.PENDING))
                    queuePending(item)
                    setFileVote(VOTE_NO_NETWORK, true)   // 2.12：排队期间掉线 = "原本没网络"那一票
                    log("排队期间会话断开，文件夹 $name 进入待发队列")
                    wakeUp()
                    return@enqueueFileSend
                }
                val kind = DataConsentText.KIND_FILE
                val consent = if (preGranted) DataConsent.OK else
                    ensureDataConsent(cs.remoteIp, item.mid, kind, "发送文件夹", name, total)
                when (consent) {
                    DataConsent.OK -> {
                        okUpdate(item.copy(status = Status.SENDING))
                        doSendFolder(treeUri, item, files, scan.emptyDirs, scan.skipped)
                    }
                    DataConsent.LOCAL_REFUSED -> denyFileByData(item, LOCAL_DENY_TEXT)
                    DataConsent.REMOTE_REFUSED ->
                        denyFileByData(item, DataConsentText.peerRefusedText(kind))
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true)   // 2.12：确认期间掉线 = "原本没网络"票
                        okUpdate(item.copy(status = Status.PENDING))
                        queuePending(item)
                        wakeUp()
                    }
                }
            }
        }, "pp-send-dir").apply { isDaemon = true }.start()
    }

    /** 本机拒绝用数据流量时的备注（气泡上那一行，只在 [Status.DENIED] 时显示） */
    internal val LOCAL_DENY_TEXT get() = "未同意使用数据流量"

    /** 把文件消息置为"数据流量未同意":气泡留在聊天里,红色叹号可重试;备注写进text(只DENIED时渲染);
     *  只有真的"被拒"才投第三票,偶发失败出口(未连接/不可读)显式传false归到VOTE_NO_NETWORK */
    internal fun denyFileByData(item: MsgItem, note: String, voteDenied: Boolean = true) {
        store?.removePending(item.mid)
        okUpdate(item.copy(status = Status.DENIED, progress = -1, text = note))
        log("文件「${item.fileName}」未发送：$note")
        if (voteDenied) {
            // 2.12/D16:被拒=全局票,清零点只有一个=用户在气泡上手动重试(retryFileSend入口)
            setFileVote(VOTE_CONSENT_DENIED, true)
        }
    }

    /** 重试一条"数据流量未同意"的文件(红色叹号触发):重新完整走提醒流程;
     *  同意→onMessageMoved挪到最新再发送;再拒→保持DENIED */
    fun retryFileSend(
        mid: String, uri: Uri, name: String, size: Long, isDir: Boolean, peerIp: String
    ) {
        enqueueFileSend("重试发送 $name") {
            // 2.12/D16:手动重试就是"被拒"这一票的清零点;放入口而不是成功后,
            // 再被拒时denyFileByData会重新投1
            setFileVote(VOTE_CONSENT_DENIED, false)
            val s = sessionByGroup(peerIp)
            if (s == null) {
                setFileVote(VOTE_NO_NETWORK, true)   // 归票 ②：握手完成自动清，不靠下一次手动重试
                denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    "设备未连接，点设备重连后再试",
                    voteDenied = false
                )
                return@enqueueFileSend
            }
            val label = if (isDir) "发送文件夹" else "发送文件"
            val kind = DataConsentText.KIND_FILE
            when (ensureDataConsent(s.remoteIp, mid, kind, label, name, size)) {
                DataConsent.OK -> {
                    listener?.onMessageMoved(mid)
                    val item = MsgItem(
                        mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                        fileSize = size, filePath = uri.toString(), localUri = uri.toString(),
                        progress = 0, isDir = isDir, status = Status.SENDING, peerIp = s.remoteIp
                    )
                    okUpdate(item)
                    store?.removePending(mid)
                    if (isDir) {
                        val scan = FileIO.scanTree(ctx, uri)
                        if (scan.files.isEmpty() && scan.emptyDirs.isEmpty() && scan.skipped == 0) {
                            log("重试失败：文件夹 $name 已读不到内容")
                            // 本地读不到 ≠ 用户拒绝，不许投全局票
                            denyFileByData(item, "文件夹已不可读", voteDenied = false)
                            return@enqueueFileSend
                        }
                        doSendFolder(uri, item, scan.files, scan.emptyDirs, scan.skipped)
                    } else {
                        doSendFile(uri, item)
                    }
                }
                DataConsent.LOCAL_REFUSED -> denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    LOCAL_DENY_TEXT
                )
                DataConsent.REMOTE_REFUSED -> denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    DataConsentText.peerRefusedText(kind)
                )
                DataConsent.NO_SESSION -> {
                    // 会话在这一步断了：归票 ②（握手完成自动清），不是用户拒绝 ⇒ 不投全局票
                    setFileVote(VOTE_NO_NETWORK, true)
                    denyFileByData(
                        MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                        "设备未连接，点设备重连后再试",
                        voteDenied = false
                    )
                }
            }
        }
    }

    /** 待发队列自动补发(重连后flushPendingNow甩到专用线程):与手动重试同一道数据流量闸门;
     *  拒绝→移出待发队列、气泡置DENIED,不反复骚扰 */

    /** 待发条目的目的会话:优先条目自带的peerIp(断线前记下的目标),为空才回落当前对话设备;
     *  绝不"随便找一台连着就发"(那正是内容串台) */
    internal fun sessionForPending(peerIp: String): Session? =
        sessionByGroup(peerIp) ?: if (peerIp.isEmpty()) target() else null

    /** 取"某台设备"的会话:先按IP精确匹配,找不到回落同设备(同组键)任意健康会话;
     *  一台设备可能有多个地址,选中的和会话建在的不同时按组键相等视为同一台 */
    internal fun sessionByGroup(ip: String): Session? {
        sessionOf(ip)?.let { return it }
        val v = ip.trim()
        if (v.isEmpty()) return null
        val k = runCatching { prefs.convKeyOf(v) }.getOrNull().orEmpty()
        if (k.isEmpty()) return null
        for (s in sessions) {
            if (s.handshaked && !s.closed && runCatching { prefs.convKeyOf(s.remoteIp) }.getOrNull() == k) {
                return s
            }
        }
        return null
    }

    /** 发送端等「对端文件夹核对回执」用：dirMid → 容量 1 的信箱（[onDirMissing] 投递） */
    internal val dirMissingBox =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<List<String>>>()

    /** 对端报缺失后最多补发几轮；再缺说明是真发不过去，如实报失败而不是死循环 */
    internal val DIR_RESEND_ROUNDS = 2

    /** 等 dir_missing 回执的时长：对端要先把本轮全部成员落盘、算校验和才核对 */
    internal val DIR_MISSING_WAIT_MS = 60_000L

    /** dir_meta 成员清单在线上最多多少字符（超了就不带，退化为「只按数量核对」，避免撑爆 8MB 帧上限） */
    internal val DIR_LIST_MAX_CHARS = 2 * 1024 * 1024




    /** 发送端在途核对表（普通单文件续传才登记） */
    internal val verifyMap = ConcurrentHashMap<String, VerifyState>()

    /** 核对超时：大文件 MD5 双方都要读一遍全盘，给足 2 分钟；超时只记日志不改传输结果 */
    internal val verifyTimeoutMs = 120_000L


    /** 取消正在发送/待发送的文件或文件夹(两端同步):本端移除并删待发/历史,发file_cancel让对端中止;
     *  发送循环下轮读到cancelled自行中断(不再发file_done) */
    fun cancelFile(mid: String) {
        if (mid.isEmpty()) return
        io {
            cancelled.add(mid)
            dirSendState.remove(mid)
            // 先查目的设备：取消通知要发给那台，别发给当前对话的另一台
            val dest = store?.loadPending()?.firstOrNull { it.mid == mid }?.peerIp.orEmpty()
            store?.removePending(mid)
            listener?.onMessageRemoved(mid)
            val s = sessionOf(dest) ?: if (dest.isEmpty()) target() else null
            if (s != null) {
                sendOrDrop(s, Protocol.fileCancel(mid), "file_cancel")
            }
            log("已取消文件传输 $mid")
        }
    }

    // ==================== 语音通话发送 ====================

    /** 向指定IP发一帧音频;会话不存在/失败只记日志(通话层兜底静音);返回写入耗时ms,-1=未发送 */
    fun sendAudioTo(ip: String, seq: Int, ts: Long, data: ByteArray, len: Int): Long {
        val s = sessionOf(ip) ?: return -1L
        return try {
            s.sendAudio(seq, ts, data, 0, len)
        } catch (t: Throwable) {
            log("音频帧发送失败(${t.javaClass.simpleName}): ${t.message}")
            -1L
        }
    }

    /** 通话控制帧:六个入口全部由UI回调(主线程)调,必须走io{}(主线程写socket抛NetworkOnMainThreadException,
     *  sendOrDrop会把该会话判失效⇒"点击立即断线");返回"有没有可用会话",写出尽力而为 */
    fun sendCallInvite(
        name: String, ip: String, vid: Boolean = false, lv: Int = 1, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ): Boolean {
        if (target() == null) return false
        io {
            target()?.let {
                sendOrDrop(it, Protocol.callInvite(name, ip, vid, lv, af, mport, mudp, msid), "call_invite")
            }
        }
        return true
    }

    /** 同意接听:vid=是否接受视频,lv=可接受档位(两端取min),af=期望语音帧长;末三项=本端媒体三元组,语义同sendCallInvite */
    fun sendCallAccept(
        ip: String, vid: Boolean = false, lv: Int = 1, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(
                    it, Protocol.callAccept(vid, lv, af, mport, mudp, msid), "call_accept", quiet = true
                )
            }
        }

    /** 振铃期逐档交换本端媒体三元组(§4.3①);由pp-av-negotiate线程调,仍走io{}队列(控制帧不许在调用线程写socket);
     *  返回有没有可用会话;false=主TCP已无会话⇒调用方按⑦处置 */
    fun sendCallMedia(ip: String, mport: Int, mudp: Boolean, msid: String, tierOrdinal: Int): Boolean {
        val s = sessionOf(ip) ?: return false
        io { sendOrDrop(s, Protocol.callMedia(mport, mudp, msid, tierOrdinal), "call_media", quiet = true) }
        return true
    }

    fun sendCallReject(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callReject(), "call_reject", quiet = true) } }

    fun sendCallBusy(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callBusy(), "call_busy", quiet = true) } }

    fun sendCallEnd(ip: String, reason: String = "") =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callEnd(reason), "call_end", quiet = true) } }

    fun sendCallCancel(ip: String) =
        io { sessionOf(ip)?.let { sendOrDrop(it, Protocol.callCancel(), "call_cancel", quiet = true) } }

    // ==================== 视频通话发送 ====================

    /** 该设备的对端能力（未握手/未连接返回 null）。按**设备**找会话（见 [sessionByGroup]） */
    fun peerCapsOf(ip: String?): PeerCaps? = sessionByGroup(ip.orEmpty())?.caps

    /** 本端能力晚到(如解码器枚举在后台完成)时,把所有已握手会话的hello重发一次 */
    fun resendHello() {
        io {
            val j = Protocol.hello(
                myId, myName, Build.MODEL ?: "android",
                localVidCap, localCamCap, localVCodecs
            )
            for (s in sessions) {
                if (s.handshaked && !s.closed) sendOrDrop(s, j, "hello", quiet = true)
            }
            log("已重发 hello（vid=${localVidCap} cam=${localCamCap} vcodec=${localVCodecs}）")
        }
    }

    /** 对端是否声明可接收视频（**发任何视频帧前的硬闸门**） */
    fun peerVidCap(ip: String?): Boolean = peerCapsOf(ip)?.vid == true

    /** 向指定IP发一帧视频;闸门:对端未广播vid=1直接丢弃(老端遇0x03会断链);超128KB也丢;
     *  返回写入耗时ms,-1=未发送(无会话/无能力/超闸门) */
    fun sendVideoTo(ip: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int): Long {
        if (len > Protocol.MAX_VIDEO_FRAME) {
            log("视频帧超 128KB 闸门（${len}B），丢弃以免长时间独占写锁")
            // 计数器口径:被单帧闸门拦下必须计数并上报,否则这条静默丢就是下一个"lat恒0"
            com.phonepctransfer.util.CallCounters.videoDroppedByGate.inc()
            return -1L
        }
        val s = sessionOf(ip) ?: return -1L
        if (!s.caps.vid) return -1L
        return try {
            s.sendVideo(seq, ts, flag, data, 0, len)
        } catch (t: Throwable) {
            log("视频帧发送失败(${t.javaClass.simpleName}): ${t.message}")
            -1L
        }
    }

    /** 编码参数变更通知(控制帧,主线程安全);front=本端摄像头是否前置(video_cfg.cam),接收端据此选默认显示角 */
    fun sendVideoCfg(
        ip: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int, front: Boolean
    ) = io {
        sessionOf(ip)?.let {
            sendOrDrop(
                it, Protocol.videoCfg(codec, w, h, fps, br, rot, front), "video_cfg", quiet = true
            )
        }
    }

    /** 接收侧健康反馈（每 2s 一次，供对端升降档）。dropping = 本机此刻正在丢帧（任务单 1.3） */
    fun sendVideoFb(ip: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(it, Protocol.videoFb(fps, drop, lat, dropping, kbps), "video_fb", quiet = true)
            }
        }

    /** 接收端请求临时停/开视频（如切后台） */
    fun sendVideoReq(ip: String, on: Boolean) =
        io {
            sessionOf(ip)?.let {
                sendOrDrop(it, Protocol.videoReq(on), "video_req", quiet = true)
            }
        }

    /** 当前对话实际可达的对端地址(通话呼叫目标);优先会话remoteIp而非selectedIp:
     *  切到组内另一地址后会话仍建在原地址,报selectedIp会让呼叫卡在"正在呼叫" */
    fun currentPeerIp(): String = target()?.remoteIp ?: selectedIp

    /** 手动同步：只对当前对话设备冲刷待发内容（点一次试一次，不重连其他设备；剪贴板纯被动不拉） */
    fun syncNow() {
        io {
            log("手动同步")
            val s = target()
            if (s != null) {
                sendOrDrop(s, Protocol.syncReq(), "sync_req")
                flushPendingNow(s.remoteIp)
            } else {
                wakeUp()
            }
        }
    }

    // ==================== 待发队列 ====================

    /** 由 AppCore 注入，避免 Link 直接依赖 Store */
    var store: com.phonepctransfer.data.Store? = null


    /** 对外入口：可能被主线程调用，统一转到发送线程。补发所有连着的设备各自的条目 */
    fun flushPending() {
        io { flushPendingNow(null) }
    }

    /** 只补发**发给某台设备**的待发条目（某台刚握手成功时调用） */
    fun flushPending(onlyIp: String) {
        io { flushPendingNow(onlyIp) }
    }


    // ==================== 消息删除同步（v2.4，仅双方 hd=1 时使用）====================

    /** 本机删除一条文本/图片后通知对端同步移除;只发帧不碰本地数据;对端不在线/老端静默跳过(hsync下次补回) */
    fun deleteMessage(peerIp: String, mid: String) {
        if (mid.isEmpty()) return
        // 按设备找会话：删除通知要发给「那台设备」，它可能挂在同组的另一个地址上
        val s = sessionByGroup(peerIp) ?: return
        if (!s.handshaked || s.closed || !s.caps.hd) return
        sendOrDrop(s, Protocol.msgDel(listOf(mid)), "msg_del")
        log("已通知对端删除消息 mid=$mid")
    }

    /** 这台设备现在收得到msg_del吗(在线+握手完成+对端hd);界面要在确认之前给出在线口径 */
    fun canSendMsgDel(peerIp: String): Boolean {
        val s = if (peerIp.isEmpty()) null else sessionByGroup(peerIp)
        return s != null && s.handshaked && !s.closed && s.caps.hd
    }

    /** 批量删除通知(清空会话用):把参与同步的mid(文本+图片)分片发msg_del;只发帧,本地由AppCore一次清完;
     *  返回实际请求对端删除的条数,0=对端不在线/不支持hd */
    fun deleteMessages(peerIp: String, mids: List<String>): Int {
        if (mids.isEmpty()) return 0
        val s = sessionByGroup(peerIp) ?: return 0
        if (!s.handshaked || s.closed || !s.caps.hd) return 0
        io {
            var i = 0
            while (i < mids.size) {
                val chunk = mids.subList(i, minOf(i + MSG_DEL_CHUNK, mids.size))
                sendOrDrop(s, Protocol.msgDel(chunk), "msg_del")
                i += MSG_DEL_CHUNK
            }
            log("清空：已逐条通知对端删除 ${mids.size} 条（文本/图片）")
        }
        return mids.size
    }

    /** 这台设备名下还有传输在途吗(正在收/正在发);"清空"要在此拒绝(清掉会丢落地注册项);按设备组键比对 */
    fun hasActiveTransfer(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val k = runCatching { prefs.convKeyOf(ip) }.getOrNull().orEmpty()
        fun same(v: String): Boolean =
            v.isNotEmpty() && runCatching { prefs.convKeyOf(v) }.getOrNull() == k
        synchronized(rxMap) { if (rxMap.values.any { same(it.peerIp) }) return true }
        val act = inFlight.toList()
        if (act.isEmpty()) return false
        // 发送侧只有 mid 集合（不带 IP）→ 用待发队列里的归属反查这台设备
        return store?.loadPending()?.any { it.mid in act && same(it.peerIp) } == true
    }

    /** 本机是否**有任何**文件传输在途（任台设备在收 / 在发）——保活「传输中不降级」判定用 */
    fun hasAnyActiveTransfer(): Boolean =
        synchronized(rxMap) { rxMap.isNotEmpty() } || inFlight.isNotEmpty()


    // ==================== 二进制工具 ====================


    // ==================== 工具 ====================

    /** 最近一次已入列的对端剪贴板哈希——同一份内容反复拉取时不重复生成气泡 */
    @Volatile
    internal var lastClipEmitSha1 = ""

    internal fun isDuplicateClip(text: String): Boolean {
        val sha = Clip.sha1(text)
        if (sha == lastClipEmitSha1) return true
        // 跨重启有效的判断：这条内容本机已经推出去过 / 已经拉回来过，界面上本来就有气泡
        if (listener?.shouldSkipPeerClip(sha) == true) {
            lastClipEmitSha1 = sha
            return true
        }
        lastClipEmitSha1 = sha
        return false
    }

    internal fun emit(m: MsgItem) {
        listener?.onMessageAdded(m)
    }

    internal fun okUpdate(m: MsgItem) {
        listener?.onMessageUpdated(m)
    }

    internal fun markStatus(mid: String, st: Status) {
        if (mid.isEmpty()) return
        listener?.onMessageUpdated(MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.TEXT, status = st))
    }

    internal fun waitFor(ms: Long) {
        synchronized(reconnectLock) {
            runCatching { reconnectLock.wait(ms) }
        }
    }

    internal fun wakeUp() {
        synchronized(reconnectLock) { runCatching { reconnectLock.notifyAll() } }
    }

    /** 统一消息id:两端同构的32位十六进制全局唯一串;接收查重/待发/历史合并/hsync补齐都以它为唯一判据 */
    internal fun newMid(): String = UUID.randomUUID().toString().replace("-", "")

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    internal fun log(s: String) {
        Log.i(TAG, s)
        listener?.onLog(s)
    }

    companion object {
        internal const val TAG = "PP-Link"

        /** 半成品过期时限：3 天没人续传的孤儿半成品（取消帧丢失/对端重传换 mid）启动时回收 */
        const val PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000

        /** 判「假连接（半开）」的收包空闲阈值：超过一轮心跳周期（5s）仍无收包才算 */
        internal const val STALE_RX_MS = 20_000L

        /** 接收/发送停滞判定阈值:30s无任何真实进度;对端进程活着但发送线程卡死时心跳帧保不了业务帧 */
        internal const val STALL_TIMEOUT_MS = 30_000L

        /** 停滞看门狗扫描周期（= 心跳周期）：心跳线程每 5s 顺带扫一次，不新开线程 */
        internal const val STALL_SCAN_MS = 5_000L

        /** 未完成目录闲置回收时限：10 分钟无活动即回收（防「没走到 dir_end 的目录永留」） */
        internal const val DIR_STALE_MS = 10L * 60 * 1000

        /** hsync：双方各扫描最近多少条历史参与对账 */
        internal const val HSYNC_SCAN = 300

        /** hsync：单次 pull 最多请求多少条（超出的等下次连接再补） */
        internal const val HSYNC_PULL_MAX = 200

        /** hsync：文件自动补齐的大小上限；更大的文件不补（避免误触发 GB 级重传） */
        internal const val HSYNC_FILE_MAX = 64L * 1024 * 1024

        /** 等对端数据流量确认（data_ans）的最长时间：对端要弹框等用户点，给足时间 */
        internal const val DATA_ANS_WAIT_MS = 90_000L

        /** 「清空会话」逐条删除时，单帧 `msg_del` 最多带多少个 mid（多了会撞帧长上限） */
        internal const val MSG_DEL_CHUNK = 200

        // ---- 2.12 / D16：文件排他投票表的三个票号（与 PC 端 Link.VOTE_* 同号同义） ----
        /** 票 ①：通话中（**仅 TALKING**，接通后；由 CallManager 的状态迁移写） */
        const val VOTE_IN_CALL = 0
        /** 票 ②：原本"没网络"（发送入口发现无会话时写，握手完成清） */
        const val VOTE_NO_NETWORK = 1
        /** 票 ③：原本"被拒绝"（数据流量确认被拒时写，清零点 = 用户在气泡上手动重试） */
        const val VOTE_CONSENT_DENIED = 2
    }
}
