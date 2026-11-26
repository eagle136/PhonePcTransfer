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
import com.phonepctransfer.util.NameRules
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
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 手机↔电脑 TCP 链路(多设备独立长连接,与 docs/PROTOCOL.md 一致):两端都监听 28765 且都能主动 connect;
 * 每台对端一条独立会话互不干扰;点设备才(重)连接、失败不重试;5/10/30s 阶梯心跳、35s 无收包判死;写失败即废弃会话
 */
/** P4-5 配对覆盖层状态（Android Compose / PC Swing 共用同一语义） */
data class PairUi(
    val ip: String,
    val name: String,
    /** true = 本端显示 6 位码；false = 本端输入 6 位码。
     *  两端都不再自动弹：用户点哪台设备，哪端就出示码，对端才会切到数字键盘。 */
    val showCode: Boolean,
    val code: String = "",
    val attemptsLeft: Int = 3,
    /** 等待对方输入/确认的剩余毫秒（0 = 无倒计时） */
    val ttlMs: Long = 0L,
    /** 配对结果提示（成功/失败/超时）；空 = 进行中 */
    val message: String = "",
    val done: Boolean = false,
    val ok: Boolean = false
)

class Link(internal val ctx: Context, internal val prefs: Prefs) {

    /**
     * SocketChannel 直通输入流。
     *
     * **不能用 [java.nio.channels.Channels.newInputStream]**：它在阻塞模式下 `synchronized(channel)`
     * 后调 `read()`——对端没数据时读线程会一直抱着这把锁，同一条连接上的写线程（控制帧、file_query、
     * 取消帧……）全部排在后面，直到对端下一帧进来把读唤醒（实际就是心跳 ping，实测卡 5s）。
     * SocketChannel 本身保证「一个读线程 + 一个写线程」并发安全，读这一侧不需要额外互斥。
     */
    internal class ChannelIn(private val ch: SocketChannel) : InputStream() {
        private val one = ByteBuffer.allocate(1)

        override fun read(): Int {
            one.clear()
            val n = ch.read(one)          // 阻塞模式：>0 或 -1（不会有 0）
            return if (n <= 0) -1 else (one.get(0).toInt() and 0xff)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = ch.read(ByteBuffer.wrap(b, off, len))
            return if (n < 0) -1 else n
        }

        override fun available(): Int = 0
        override fun close() { runCatching { ch.close() } }
    }

    /**
     * SocketChannel 直通输出流（理由见 [ChannelIn]）：直接 `channel.write`，不碰 channel 监视器锁；
     * 写与写的互斥由 [Session.writeLock] 负责（零拷贝分片也在这把锁内）。
     */
    internal class ChannelOut(private val ch: SocketChannel) : OutputStream() {
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val bb = ByteBuffer.wrap(b, off, len)
            while (bb.hasRemaining()) {
                if (ch.write(bb) < 0) throw java.io.IOException("会话已关闭")
            }
        }

        override fun flush() {}
        override fun close() { runCatching { ch.close() } }
    }

    enum class State { STOPPED, IDLE, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State, peer: String)
        /**
         * P4-5 配对覆盖层状态。ui=null 表示关闭覆盖层。
         * showCode=true：显示 6 位码（接收方）；false：数字键盘输入（发起方）。
         */
        fun onPairState(ui: PairUi?) {}
        /** 会话集合变化(某台连上/断开):UI只刷设备列表,聊天区不受影响 */
        fun onPeersChanged() {}
        /** 新增一条消息（自己发 / 收到对端） */
        fun onMessageAdded(m: MsgItem)
        /**
         * 一批消息一次性到达（hsync 补齐）。**调用方已负责落盘**，实现方只更新界面；
         * 默认逐条回落到 [onMessageAdded]，自己落盘的实现（如 AppCore）必须覆写，
         * 否则会重复写历史、并在主线程上逐条做磁盘 I/O（配对成功后补几百条时界面就卡死）。
         */
        fun onMessagesAdded(list: List<MsgItem>) {
            for (m in list) onMessageAdded(m)
        }
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

    /** 当前正在配对覆盖层上的设备 IP（空 = 没有覆盖层） */
    @Volatile
    internal var pairUiIp: String = ""

    /**
     * 这条会话是不是覆盖层上那台设备。按**设备组键**比较（同设备换网卡/热点换地址时 raw IP 会变），
     * 否则配对完成/提交核对/取消都会因为地址不同而对不上，覆盖层就关不掉。
     */
    internal fun pairUiMatches(ip: String): Boolean {
        val cur = pairUiIp
        if (cur.isEmpty() || ip.isEmpty()) return false
        if (cur == ip) return true
        return runCatching { prefs.convKeyOf(cur) == prefs.convKeyOf(ip) }.getOrDefault(false)
    }

    /**
     * 用户点了设备但会话还没握手：等这次连接起来后按「用户主动发起」出示配对码。
     * 空 = 没有待办。带上时间戳，超过 [PAIR_TAP_INTENT_TTL_MS] 的连接不再补弹（避免几秒后的自动重连又把码弹出来）。
     */
    @Volatile
    private var pendingPairIp: String = ""

    @Volatile
    private var pendingPairAt: Long = 0L

    @Volatile
    var state: State = State.STOPPED
        internal set

    /** 状态栏附注文案（当前对话设备名 / 「连接失败，点设备重试」这类） */
    @Volatile
    var peerLabel: String = ""
        internal set

    @Volatile
    internal var running = false

    internal var serverSocket: ServerSocketChannel? = null
    internal val sessions = CopyOnWriteArrayList<Session>()

    /** 按对端IP索引的会话表(多设备并发的核心);每台连上的设备各一条独立长连接,互不干扰 */
    internal val byPeer = ConcurrentHashMap<String, Session>()

    /**
     * 2 期：数据通道（lane）连接池。key = 对端 IP，value = lane 号 → 会话。
     * <p>lane 0 永远是主连接（[byPeer] 里那条），这里只放 lane > 0 的纯数据连接；
     * 它们**不注册 peer**、不参与去重、不进 UI，只承担 BIN_CHUNK 分片。
     */
    internal val laneMap = ConcurrentHashMap<String, MutableMap<Int, Session>>()

    /**
     * 已声明走多通道、但 file_meta 还没到的 mid → 对端声明的通道数。
     * file_meta 建 Rx 时取走它决定用重排环还是 1 期双缓冲。
     */
    internal val multiMids = ConcurrentHashMap<String, Int>()

    /**
     * 多通道发送「等接收端建好 Rx」的 latch（mid → latch）。发送端先登记、再发 xe_begin+file_meta，
     * 收到对端 xe_ready 才起数据通道；超时 [XE_READY_WAIT_MS] 降级为主连接单通道发，不失败。
     * 根因：接收端只在解析到 file_meta 时才 createRx，早到的分片会因「未知 mid」被丢弃 →
     * 留下补不齐的单元缺口 → 重排环窗口卡死 30s。
     */
    internal val xeReadyLatches = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    internal fun expectXeReady(mid: String, latch: java.util.concurrent.CountDownLatch) {
        xeReadyLatches[mid] = latch
    }

    internal fun clearXeReady(mid: String) {
        xeReadyLatches.remove(mid)
    }


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

    /** 打点：与该对端有过消息/文件往来。任何异常都不能影响收发主流程
     *  （按设备身份记账：同设备换 IP / 多地址都归到同一条，不会各记一份） */
    internal fun trustContent(ip: String, isFile: Boolean) {
        runCatching { trusted?.markContent(peerKeyOf(ip), isFile) }
    }

    /** 清掉「后台建连被拒」这类过期提示（回前台/建连成功时调），免得用户点设备后仍看到旧文案 */
    internal fun clearGateNote() {
        if (stateNote.startsWith("后台不允许连接")) {
            stateNote = ""
            refreshState()
        }
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

    // ==================== 传输任务槽（同一时刻只跑一个传输任务）====================

    /**
     * 单任务槽：本机同一时刻只允许一个文件/文件夹传输任务（发送或接收；一个文件夹整体算一个）。
     * 其余请求一律排队、绝不并行：
     * · 发送侧在串行队列线程里等槽位（[awaitTaskSlot]，对端此时发来的内容会被它回 busy）；
     * · 接收侧占不到槽位就直接回带 mid 的 `busy`（见 [Protocol.busy]），发送端把条目留待发队列，
     *   等本机释放槽位的 `sync_req` 到了再自动补发。
     * 这样两台设备之间任意时刻只有一个任务在传，不存在「两边同时发文件夹」的目录名/落盘竞态。
     */
    internal val taskLock = Object()

    @Volatile internal var taskMid = ""
    @Volatile internal var taskPeer = ""
    @Volatile internal var taskKind = ""     // "send" / "recv"
    @Volatile internal var taskName = ""

    /** 占用者描述（busy 帧的 holder / 日志用）；空 = 空闲 */
    internal fun taskHolder(): String {
        val mid = taskMid
        if (mid.isEmpty()) return ""
        val what = if (taskKind == "send") "发送" else "接收"
        return "$what ${taskName.ifEmpty { mid }}"
    }

    /** 抢占任务槽（同 mid 幂等：同一文件夹的后续成员、同一条文件的续传都靠它重入）。false = 本机正忙别的任务 */
    internal fun claimTask(mid: String, peerIp: String, kind: String, name: String): Boolean {
        synchronized(taskLock) {
            if (taskMid.isNotEmpty() && taskMid != mid) return false
            taskMid = mid
            taskPeer = peerIp
            taskKind = kind
            taskName = name
            return true
        }
    }

    /**
     * 发送侧等槽位：槽被接收任务占着就排队，直到空出来（只允许在 pp-file-send 串行队列线程调用，
     * 绝不能放在网络读线程上——那里一阻塞，心跳/对端 ack 全读不到）。
     */
    internal fun awaitTaskSlot(
        mid: String, peerIp: String, kind: String, name: String,
        cancelled: () -> Boolean
    ): Boolean {
        synchronized(taskLock) {
            while (taskMid.isNotEmpty() && taskMid != mid) {
                if (!running || cancelled()) return false
                try {
                    taskLock.wait(500L)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            taskMid = mid
            taskPeer = peerIp
            taskKind = kind
            taskName = name
            return true
        }
    }

    /**
     * 释放任务槽（幂等）。默认随后给对端补一条 `sync_req`：之前被 busy 拦下的条目立刻重试一次，
     * 不用等下次握手。会话已断/取消等收尾路径传 notifyPeer=false。
     */
    internal fun releaseTask(mid: String, notifyPeer: Boolean = true) {
        var peer = ""
        synchronized(taskLock) {
            if (taskMid != mid) return
            peer = taskPeer
            taskMid = ""
            taskPeer = ""
            taskKind = ""
            taskName = ""
            taskLock.notifyAll()
        }
        if (peer.isEmpty() || !notifyPeer) return
        val s = sessionByGroup(peer) ?: return
        runCatching { sendOrDrop(s, Protocol.syncReq(), "sync_req", quiet = true) }
    }

    /** 按「对端 + 方向」释放（会话断开等没法按 mid 精确定位的收尾路径） */
    internal fun releaseTaskFor(peerIp: String, kind: String) {
        val mid = taskMid
        if (mid.isEmpty()) return
        if (taskPeer != peerIp || taskKind != kind) return
        releaseTask(mid, notifyPeer = false)
    }

    /**
     * 对端回 busy 的 mid（发送侧）。带 120s 过期，避免条目早就出队了记号还留着。
     * 发送循环在每个分片边界消费一次（[consumePeerBusy]），命中就停推、条目回待发队列。
     */
    internal val peerBusyMids = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun markPeerBusy(mid: String) {
        if (mid.isNotEmpty()) peerBusyMids[mid] = System.currentTimeMillis() + PEER_BUSY_TTL_MS
    }

    internal fun consumePeerBusy(mid: String): Boolean {
        val exp = peerBusyMids.remove(mid) ?: return false
        return System.currentTimeMillis() < exp
    }

    internal fun isPeerBusy(mid: String): Boolean =
        (peerBusyMids[mid] ?: 0L) > System.currentTimeMillis()

    /** 被回绝过的目录 mid（接收侧）：同一目录的成员 file_meta 也一律回 busy，不再隐式建目录 */
    internal val busyDirIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun markDirBusy(dirId: String) {
        if (dirId.isNotEmpty()) busyDirIds[dirId] = System.currentTimeMillis() + PEER_BUSY_TTL_MS
    }

    internal fun isDirBusy(dirId: String): Boolean {
        val exp = busyDirIds[dirId] ?: return false
        if (System.currentTimeMillis() < exp) return true
        busyDirIds.remove(dirId)
        return false
    }

    internal val myId: String get() = prefs.deviceId
    internal val myName: String get() = prefs.deviceName

    // ==================== 会话 ====================

    /**
     * 一条 TCP 会话。
     * <p>底层用 [SocketChannel] 而不是 [Socket]：文件分片的发送侧要靠
     * {@code FileChannel.transferTo(..., SocketChannel)} 走 sendfile/TransmitFile 零拷贝，
     * 目标必须是真的 SocketChannel（包一层流或 Socket 就会被 JDK 退化成普通拷贝）。
     * 控制帧/音视频帧走自带流式封装 [ChannelIn]/[ChannelOut]——**不能用 Channels.newInputStream/
     * newOutputStream**：它们在阻塞模式下会对 channel 对象加监视器锁，读线程停在 read() 等数据时
     * 一直持锁，写线程（控制帧/file_query/取消帧）全排在后面，只能等对端下一帧把读唤醒（实测卡 5s）。
     */
    internal inner class Session(val channel: SocketChannel, val outgoing: Boolean) {
        /** 底层 Socket 视图：只用于取地址、设选项（读写一律走 channel） */
        val socket: Socket get() = channel.socket()
        val input: InputStream = DataInputStream(BufferedInputStream(ChannelIn(channel)))
        val output: OutputStream = BufferedOutputStream(ChannelOut(channel))
        /** 写锁:保证帧不交错;心跳用tryLock,写锁被文件传输占用时跳过本轮ping */
        internal val writeLock = ReentrantLock()

        /**
         * 非文件帧（JSON/音频/视频）开始写的时刻；0 = 当前没有写在进行。
         * 心跳据此判「写卡死」：Socket 没有写超时，对端不读时 write 会阻塞十几分钟（P1-3）。
         */
        @Volatile
        internal var writeStartedAt = 0L

        /** 对端 IP，构造时定下（socket 关了也能读，用来做归属与状态判定） */
        val remoteIp: String =
            runCatching { channel.socket()?.inetAddress?.hostAddress }.getOrNull().orEmpty()

        @Volatile var peerId = ""
        @Volatile var peerName = ""
        @Volatile var handshaked = false

        // ===== P4-5 配对状态 =====
        /** 内容帧（文本/剪贴板/文件/通话/历史）只有 paired=true 才许发/收 */
        @Volatile internal var paired = false
        /** 1 = 本端显示 6 位码（连接接收方）；2 = 本端输入（连接发起方）；0 = 无配对 UI */
        @Volatile internal var pairRole = 0
        @Volatile internal var pairCode = ""
        @Volatile internal var pairAttemptsLeft = 3
        @Volatile internal var pairDeadline = 0L
        /** 本端是否为这条连接的发起方（进入配对时决定显示码/输入码） */
        internal val isDialer: Boolean get() = outgoing

        /** 本端在这条连接上实际带出去的配对 token（空=没带）；空 token 的 pair_ok 只认它 */
        @Volatile internal var pairTokenSent = ""
        /** 是否已经走完一次 onSessionReady（重发 hello 不再重复补发/hsync） */
        @Volatile internal var readyOnce = false

        /** 对端能力（hello 协商所得）。握手前全 false —— 因此默认绝不会发视频帧。 */
        val caps = PeerCaps()

        /** 2 期：本条连接的角色。0 = 主连接（唯一注册 peer 的那条）；>0 = 纯数据通道 */
        @Volatile internal var lane = 0
        /** 2 期：对端声明的最大通道数。老端不带 lanes 字段 → 1（不支持多通道） */
        @Volatile internal var peerLanes = 1
        /** 这条 lane 连接已握手、可写数据（主连接恒 true） */
        @Volatile internal var laneReady = false


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
            // P1-5：**不再显式设置 SO_SNDBUF/SO_RCVBUF**。
            // 应用层一旦设值，内核就关闭发送/接收窗口的自动调整（Linux tcp_moderate_rcvbuf、
            // Windows receive-window auto-tuning），在速率与 RTT 都在波动的无线链路上固定缓冲只会更差。
            // 需要 A/B 时把 SOCK_BUF_KB 改成 1024 / 4096，改回 0 即恢复内核自调。
            runCatching { socket.tcpNoDelay = true }
            if (SOCK_BUF_KB > 0) {
                runCatching {
                    socket.sendBufferSize = SOCK_BUF_KB * 1024
                    socket.receiveBufferSize = SOCK_BUF_KB * 1024
                }
            }
            runCatching {
                log("[sock] $remoteIp snd=${socket.sendBufferSize}" +
                        " rcv=${socket.receiveBufferSize}" + (if (outgoing) " (主动)" else " (被动)"))
            }
        }

        @Throws(Exception::class)
        fun send(j: JSONObject) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                writeStartedAt = System.currentTimeMillis()
                Protocol.write(output, j)
                lastTx = System.currentTimeMillis()
            } finally {
                writeStartedAt = 0L
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

        /**
         * 零拷贝发送一块（P1-4）：帧头走 channel，载荷由 [FileChannel.transferTo] 直接从文件
         * 送进 socket（Linux sendfile / Windows TransmitFile），数据不经过用户态内存。
         * <p>限制：调用前缓冲里不能有未刷的帧（本方法开头 flush），且 transferTo 一旦半途失败就不可续写，
         * 因此「已写出字节 > 0 再报错」直接抛出由上层断链重传；「一个字节都没写就失败」才回落到普通拷贝。
         */
        @Throws(Exception::class)
        fun sendChunkFile(mid: String, seq: Int, src: FileChannel, pos: Long, len: Int) {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                // 控制帧/音视频帧可能还压在 8KB 缓冲里：必须先落地，否则顺序错乱
                output.flush()
                val head = Protocol.chunkHeader(mid, seq, len)
                writeFully(ByteBuffer.wrap(head))
                var at = pos
                var remain = len
                var copied = 0L
                while (remain > 0) {
                    val n = try {
                        src.transferTo(at, remain.toLong(), channel)
                    } catch (t: Throwable) {
                        if (copied == 0L) {
                            // 零拷贝不被内核/文件系统支持：本块回落到普通拷贝（顺序仍然正确）
                            TxStats.zcFallback()
                            copyRange(src, at, remain)
                            remain = 0
                            break
                        }
                        throw t
                    }
                    if (n <= 0) {
                        if (copied == 0L) {
                            TxStats.zcFallback()
                            copyRange(src, at, remain)
                            remain = 0
                            break
                        }
                        throw java.io.IOException("transferTo 无进展 n=$n at=$at remain=$remain")
                    }
                    at += n
                    remain -= n.toInt()
                    copied += n
                }
                if (copied > 0) TxStats.zeroCopy(copied)
                lastTx = System.currentTimeMillis()
            } finally {
                writeLock.unlock()
            }
        }

        private fun writeFully(bb: ByteBuffer) {
            while (bb.hasRemaining()) {
                val n = channel.write(bb)
                if (n <= 0) throw java.io.IOException("channel closed while writing header")
            }
        }

        /** 回落拷贝用的直接缓冲：每会话复用一个，避免每块都新申请 1MB 直接内存 */
        private var zcTmp: ByteBuffer? = null

        /** 零拷贝不可用时的块内回落：FileChannel → 直接缓冲 → channel（仍是一次内核拷贝） */
        private fun copyRange(src: FileChannel, pos: Long, len: Int) {
            val tmp = zcTmp ?: ByteBuffer.allocateDirect(1 shl 20).also { zcTmp = it }
            var at = pos
            var remain = len
            while (remain > 0) {
                tmp.clear()
                tmp.limit(minOf(remain, tmp.capacity()))
                val r = src.read(tmp, at)
                if (r < 0) throw java.io.EOFException("源文件在 $at 处提前结束")
                tmp.flip()
                writeFully(tmp)
                at += r
                remain -= r
            }
        }

        /** 语音音频帧:与控制帧/文件分片共用写锁;返回写入耗时ms(上层判拥塞) */
        @Throws(Exception::class)
        fun sendAudio(seq: Int, ts: Long, buf: ByteArray, off: Int, len: Int): Long {
            writeLock.lock()
            try {
                if (closed) throw IllegalStateException("会话已关闭")
                val t0 = System.nanoTime()
                writeStartedAt = System.currentTimeMillis()
                Protocol.writeAudio(output, seq, ts, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeStartedAt = 0L
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
                writeStartedAt = System.currentTimeMillis()
                Protocol.writeVideo(output, seq, ts, flag, buf, off, len)
                lastTx = System.currentTimeMillis()
                return (System.nanoTime() - t0) / 1_000_000L
            } finally {
                writeStartedAt = 0L
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
                    writeStartedAt = System.currentTimeMillis()
                    Protocol.write(output, Protocol.ping())
                    lastTx = System.currentTimeMillis()
                    true
                }
            } catch (t: Throwable) {
                close()  // 与 sendOrDrop 同语义：发送失败即判死
                true
            } finally {
                writeStartedAt = 0L
                writeLock.unlock()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { channel.close() }
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

    /** 指定 deviceId 的设备已连上但还没配对（设备列表显示「未配对，点一下配对」用） */
    fun isPeerUnpaired(deviceId: String): Boolean {
        val s = byPeer[deviceId] ?: return false
        return s.handshaked && !s.closed && !s.paired
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

    /** 指定设备已连上但还没配对（设备列表提示用；没连接=false） */
    fun isDeviceUnpaired(ip: String): Boolean {
        val s = sessionByGroup(ip) ?: return false
        return !s.paired
    }

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
        if (frameExec.isShutdown) frameExec = newFrameExec()
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
        // Doze 兜底闹钟一并撤：链路都停了，再唤醒拨号没有意义
        KeepAliveAlarm.cancelAll(ctx)
        // 发送线程池一并停：停止后排队任务不得再对着已关的 socket 写
        keepAliveExec.shutdownNow()
        fileSendExec.shutdownNow()
        sendExec.shutdownNow()
        frameExec.shutdownNow()
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (s in sessions) s.close()
        sessions.clear()
        byPeer.clear()
        // 停止前取消的mid不会被发送循环处理到,清掉避免残留(重启后不应有"幽灵取消"命中新传输)
        cancelled.clear()
        // P2-6：停链路 = 所有运行时态作废。接收上下文必须先 abort（释放 BusyLock）再清表，
        // 否则僵尸 Rx 一直占内存、下次 start 后同一 mid 还会命中旧上下文
        runCatching {
            synchronized(rxMap) {
                rxMap.values.toList().forEach { abortRx(it) }
                rxMap.clear()
            }
        }
        synchronized(dirMap) { dirMap.clear() }
        pausedMids.clear()
        resumeWaiters.clear()
        blkAckWaiters.clear()
        resumePrep.clear()
        resumeNeedBlk.clear()
        dataAnsBoxes.clear()
        awaitingAck.clear()
        inFlight.clear()
        inFlightPeer.clear()
        rejectedAck.clear()
        dirHaveBox.clear()
        synchronized(laneMap) { laneMap.clear() }
        multiMids.clear()
        xeReadyLatches.values.forEach { runCatching { it.countDown() } }
        xeReadyLatches.clear()
        dirMissingBox.clear()
        verifyMap.clear()
        cancelTombs.clear()
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
    fun selectAndConnect(ip: String, label: String? = null, fromUser: Boolean = true) {
        selectTarget(ip, label)
        connectTo(ip, fromUser = fromUser)
    }

    /** 发起一次连接尝试(不重试);已有健康会话则不做事;quiet=true不打"请求连接"日志(保活用)
     *  fromUser=true 表示「用户主动点设备」：这是显式授权，**不受后台闸门限制**
     *  （闸门只拦后台自动重连/启动自动连接这类用户没参与的建连） */
    fun connectTo(
        ip: String,
        quiet: Boolean = false,
        tryGroup: Boolean = true,
        fromUser: Boolean = false
    ) {
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
        // 硬规则:后台只允许连"已知设备"(配对过 或 有消息/文件往来);其余只许前台点击建连;
        // 入向同规则:后台陌生设备主动连入由incomingGate拒接(见adopt)
        // 用户主动点设备 = 显式授权 → fromUser 时整条闸门都不查（这块以前只放行"前台可见"，
        // 于是「配对后切后台→保活重连被拒→回到前台点名字仍看到后台不允许」的假死就是这里来的）
        if (!fromUser && outgoingGate?.invoke(v) == true) {
            stateNote = "后台不允许连接未授权设备（点一下设备即可主动连接）"
            log("后台建连被拒：$v 不在已知设备名单（需配对，或收发过消息/文件）")
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

    /** 票③的一条记录：只对这台设备生效；wasCellular = 拒绝发生时本机是否在数据网 */
    private class DenyVote(val peerIp: String, val at: Long, val wasCellular: Boolean)

    /** 票③登记表：设备组键 → 记录（同一台设备的多个地址用 convKeyOf 归并） */
    private val denyVotes = java.util.concurrent.ConcurrentHashMap<String, DenyVote>()

    /** 文件/目录发送的排他投票:裁决=三票取或；1时新条目不入网改走待发队列,
     *  在传那条在下一分片/成员边界就地暂停。
     *  **票③（被拒）必须带 peerIp 才生效**：无参调用只判票①②，避免「A 被拒 ⇒ B 也发不出去」；
     *  与 AvTransport.aggregationAllowed() 是两个判据，不许共用读口 */
    fun fileGateBlocked(peerIp: String = ""): Boolean {
        val key = peerKeyOf(peerIp)
        if (key.isEmpty()) return hasDenyVote(peerIp)
        return inCallKeys.contains(key) || offlineKeys.contains(key) || hasDenyVote(peerIp)
    }

    /** 票①：通话中的设备组键（按设备投——与 A 通话不再堵住发给 B 的文件） */
    private val inCallKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 票②：原本"没网络"的设备组键（按设备投——A 离线不再堵住发给 B 的文件） */
    private val offlineKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 设备组键（与 sessionByGroup 同一判据；取不到时退回 IP 原串） */
    internal fun peerKeyOf(ip: String): String {
        if (ip.isEmpty()) return ""
        val k = runCatching { prefs.convKeyOf(ip) }.getOrNull().orEmpty()
        return k.ifEmpty { ip }
    }

    /** 票③是否仍在拦这台设备（到期即清，避免「拒一次永久发不出」） */
    internal fun hasDenyVote(peerIp: String): Boolean {
        if (peerIp.isEmpty() || denyVotes.isEmpty()) return false
        val k = peerKeyOf(peerIp)
        val v = denyVotes[k] ?: return false
        if (System.currentTimeMillis() - v.at > DENY_TTL_MS) {
            denyVotes.remove(k, v)
            log("[文件排他] 票③到期自动解除（设备 ${v.peerIp}）")
            return false
        }
        return true
    }

    /** 投票③：只登记这一台设备；记下拒绝时本机是否在数据网，供「切到不计费网络」自动解除 */
    internal fun denyConsent(peerIp: String, note: String) {
        val key = peerKeyOf(peerIp)
        if (key.isEmpty()) return
        val wasCellular = runCatching { localOnCellular?.invoke() == true }.getOrDefault(false)
        denyVotes[key] = DenyVote(peerIp, System.currentTimeMillis(), wasCellular)
        log("[文件排他] 票③投 1（设备 $peerIp）：$note；作用域=该设备，10 分钟或切到局域网后自动解除")
    }

    /** 清票③。flush=true 时只补发这台设备的待发条目（到期清票不要 flush，免得在数据网上重弹确认框） */
    internal fun clearDenyConsent(peerIp: String, flush: Boolean) {
        val key = peerKeyOf(peerIp)
        var removed = denyVotes.remove(key) != null
        if (!removed && peerIp.isNotEmpty()) {
            // 气泡重试常带的是断线前记下的 IP：按记录里的 peerIp 再兜底清一次
            val it = denyVotes.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.peerIp == peerIp) {
                    it.remove()
                    removed = true
                }
            }
        }
        if (!removed) return
        log("[文件排他] 票③清 0（设备 $peerIp）")
        if (flush && !fileGateBlocked(peerIp)) flushPending(peerIp)
    }

    /** 显式恢复入口（设置页/设备横幅可调）：清该设备的票③并立刻补发它的待发条目 */
    fun resumeFileSend(peerIp: String) = clearDenyConsent(peerIp, flush = true)

    /**
     * 票③自动解除（心跳每 5s 顺带跑）：
     * ① 拒绝时本机在数据网、现在已切到不计费网，且该设备已是局域网地址 → 视为「重新获得同意」，清票并只补发这台设备；
     * ② 到期（[DENY_TTL_MS]）→ 只清票不补发（避免在数据网上又自动弹确认框）。
     */
    internal fun pruneDenyVotes() {
        if (denyVotes.isEmpty()) return
        val now = System.currentTimeMillis()
        val onCellular = runCatching { localOnCellular?.invoke() == true }.getOrDefault(false)
        for ((k, v) in denyVotes) {
            val expired = now - v.at > DENY_TTL_MS
            val freeNet = v.wasCellular && !onCellular && LanGate.isLanPeerIp(v.peerIp)
            if (!expired && !freeNet) continue
            if (denyVotes.remove(k, v)) {
                log("[文件排他] 票③自动解除（${if (expired) "到期" else "已切到不计费网络"}，设备 ${v.peerIp}）")
                if (freeNet) flushPending(v.peerIp)
            }
        }
    }

    /** 给入口日志用的真实原因（不再一律写「通话期不入网」） */
    internal fun gateReason(peerIp: String): String {
        val key = peerKeyOf(peerIp)
        return when {
            key.isNotEmpty() && inCallKeys.contains(key) -> "通话中不入网"
            key.isNotEmpty() && offlineKeys.contains(key) -> "当前无连接"
            hasDenyVote(peerIp) -> "上次已拒绝使用数据流量（设备 $peerIp）"
            else -> "文件排他"
        }
    }

    /** 写一票（按设备）：票①/②只作用于 peerIp 对应的那台设备；全 0 那一刻只续发这台设备的条目 */
    @Synchronized
    fun setFileVote(which: Int, on: Boolean, peerIp: String = "") {
        val key = peerKeyOf(peerIp)
        if (key.isEmpty()) return
        val before = fileGateBlocked(peerIp)
        when (which) {
            VOTE_IN_CALL -> if (on) inCallKeys.add(key) else inCallKeys.remove(key)
            VOTE_NO_NETWORK -> if (on) offlineKeys.add(key) else offlineKeys.remove(key)
            // 票③改走 denyConsent / clearDenyConsent（按设备、限时），不再走这张票
            else -> return
        }
        val after = fileGateBlocked(peerIp)
        if (before == after) return
        log(
            if (after) "[文件排他] 投票翻转 0→1 ⇒ 该设备的文件与目录不入网、在传的暂停（D16，设备 $peerIp）"
            else "[文件排他] 投票翻转 1→0 ⇒ 该设备自动续发一次（D16，设备 $peerIp）"
        )
        // 票②的解除点=握手完成（那里本来就有 flushPending(ip)），不重复触发
        if (!after && which == VOTE_IN_CALL) flushPending(peerIp)
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

    /**
     * P4-4：hsync/msg_del/配对核对等「按帧触发」的后台任务统一走这个有界池。
     * 不再每帧 new Thread：0 核 + 3 线程 + 128 队列，空闲 60s 回收；队列满直接丢弃并记日志。
     */
    @Volatile
    internal var frameExec: java.util.concurrent.ThreadPoolExecutor = newFrameExec()

    private fun newFrameExec(): java.util.concurrent.ThreadPoolExecutor {
        val pool = java.util.concurrent.ThreadPoolExecutor(
            0, 3, 60L, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
            { r -> Thread(r, "pp-frame-task").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        )
        pool.allowCoreThreadTimeOut(true)
        return pool
    }

    internal fun runFrameTask(tag: String, block: () -> Unit) {
        try {
            if (frameExec.isShutdown) return
            frameExec.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    log("后台帧任务失败($tag): ${t.javaClass.simpleName} ${t.message}")
                }
            }
        } catch (re: java.util.concurrent.RejectedExecutionException) {
            log("后台帧任务过多，丢弃 $tag（P4-4 限流）")
        }
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
        // ===== P4-5 闸门：未配对的会话只准走心跳与配对握手，任何内容帧直接丢 =====
        // 不再以 handshaked 为前提：TCP 连上后、hello 到达前同样要拦——
        // 否则跳过 hello 直发内容帧就能绕过配对往本机写消息/文件（首次连接必须先输配对码）
        if (s.lane == 0 && !s.paired && type !in PAIRED_ALLOWED_FRAMES) {
            log("未配对会话 ${s.remoteIp} 发来 $type，已丢弃（内容通道要配对通过后才放行）")
            return
        }
        // lane 会话只承载分片与通道控制帧，其余内容帧一律丢弃（防绕过配对闸门注水）
        if (s.lane > 0 && type !in LANE_ALLOWED_FRAMES) {
            log("数据通道 lane=${s.lane} 收到非通道帧 $type，已丢弃")
            return
        }
        when (type) {
            "hello" -> onHello(s, j)
            "ping" -> sendOrDrop(s, Protocol.pong(), "pong", quiet = true)
            "pong" -> Unit
            "pair_need" -> runFrameTask("pair_need") { onPairNeed(s, j) }
            "pair_submit" -> runFrameTask("pair_submit") { onPairSubmit(s, j) }
            "pair_ok" -> runFrameTask("pair_ok") { onPairOk(s, j) }
            "pair_fail" -> runFrameTask("pair_fail") { onPairFail(s, j) }
            "pair_cancel" -> runFrameTask("pair_cancel") { onPairCancel(s) }
            "text" -> {
                val mid = j.optString("mid", newMid())
                // 统一按mid查重:待发补发/hsync补齐可能重放同一条;已有mid不再生成第二气泡,ack照回
                if (store?.hasMid(s.remoteIp, mid) == true) {
                    log("文本 mid=$mid 本机已有（重复投递），跳过")
                } else {
                    val m = MsgItem(
                        mid = mid,
                        dir = Dir.PEER, kind = Kind.TEXT,
                        text = Protocol.limitText(j.optString("text", "")),
                        ts = Protocol.saneTs(j.optLong("ts", System.currentTimeMillis())),
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
            "clip_pull" -> runFrameTask("clip_pull") {
                // 对端请求本机剪贴板缓冲:读得到回正文,读不到(后台无焦点静默失败)回空,如实反映
                // 剪贴板读取是 binder IPC，放到帧任务池，别拖慢网络读线程的心跳/判死
                val text = Clip(ctx).read() ?: ""
                sendOrDrop(
                    s,
                    Protocol.clipState(text, if (text.isEmpty()) "" else Clip.sha1(text)),
                    "clip_state"
                )
                if (text.isNotEmpty()) log("已应答对端剪贴板请求 ${text.length} 字")
            }
            // file_meta 必须留在读线程同步建 Rx（早到的分片会因查不到 mid 被丢）；
            // file_done / dir_end 要等落盘收尾（最长 60s），放单线程收尾队列：
            // 读线程若被磁盘拖住，心跳的 pong 和对端的 ack 都读不到 → 自己把自己判死。
            "file_meta" -> onFileFrame(s, j)
            "file_done" -> runRxFinalize("file_done") { onFileFrame(s, j) }
            // ===== 2 期多通道：开传声明在 file_meta 之前到达，接收端据此把该 mid 建成重排环 =====
            "xe_begin" -> onXeBegin(s, j)
            "xe_end" -> {
                val emid = j.optString("mid", "")
                if (emid.isNotEmpty()) multiMids.remove(emid)
            }
            "xe_ready" -> {
                // 接收端 Rx 已建好 → 放行发送端的多通道线程
                val rmid = j.optString("mid", "")
                if (rmid.isNotEmpty()) xeReadyLatches[rmid]?.countDown()
            }
            "lane_ok" -> log("数据通道 lane=${j.optInt("lane", 0)} 对端已挂载")
            "file_query" -> onFileQuery(s, j)
            "file_resume" -> onFileResume(j)
            "file_verify" -> onFileVerify(j)
            // 32MB 分块大小校验：发送端发起核对请求 / 接收端回核验结果
            "blk_ack" -> onBlkAck(j)
            "blk_verify" -> runFrameTask("blk_verify") { onBlkVerify(s, j) }
            "dir_meta" -> onDirMeta(s, j)
            "dir_have" -> onDirHave(j)
            "dir_end" -> runRxFinalize("dir_end") { onDirEnd(s, j) }
            "dir_missing" -> onDirMissing(j)
            "file_cancel" -> onRemoteFileCancel(j.optString("mid"))
            "sync_req" -> flushPending(s.remoteIp)
            "hsync_mids" -> runFrameTask("hsync_mids") { onHsyncMids(s, j) }
            "hsync_pull" -> runFrameTask("hsync_pull") { onHsyncPull(s, j) }
            "hsync_msg" -> runFrameTask("hsync_msg") { onHsyncMsg(s, j) }
            "msg_del" -> runFrameTask("msg_del") { onRemoteMsgDel(s, j) }
            "ack" -> {
                val ackMid = j.optString("mid", "")
                // 文件夹成员的回执：只记账，要不要重发由 dir_end 的清单对账说了算
                if (onMemberAck(ackMid, j.optBoolean("ok", true))) return
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
                val bmid = j.optString("mid", "")
                if (bmid.isNotEmpty()) {
                    // 新版「设备忙（单任务槽）」：对端正在传别的任务。不关会话——
                    // 条目留待发队列，等对端任务结束发的 sync_req 到了再自动补发。
                    val holder = j.optString("holder", "")
                    val bname = j.optString("name", "")
                    markPeerBusy(bmid)
                    log(
                        "对端设备忙" + (if (holder.isEmpty()) "" else "（正在 $holder）") +
                            "：${bname.ifEmpty { bmid }} 已排在待发队列，等对端空闲后自动重试"
                    )
                    stateNote = "对端设备忙：任务已排队"
                    return
                }
                // 老格式（只有 holder）：旧版同一时刻只服务一个对端，维持原行为
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
        // 自连闸门：对端自称的 deviceId 就是本机 ⇒ 连到了自己（本机 IP / 127.0.0.1 被当设备填进了列表）。
        // 放进来会绑出「自己是自己的设备」的组、并且因为该行 lastId=本机 id 被列表过滤而删不掉，之后每次启动都自连。
        if (pid == myId) {
            log("握手：对端自报 deviceId 与本机相同（${s.remoteIp}），判定自连，关闭会话")
            s.close()
            sessions.remove(s)
            return
        }
        // ===== 版本闸门（v4）：hello 必须自报协议版本，不一致直接拒连（与 PC 同口径）=====
        val peerVer = j.optInt("ver", 0)
        if (peerVer != Protocol.PROTO_VER) {
            log(
                "对端协议 v$peerVer 与本端 v${Protocol.PROTO_VER} 不一致（缺 ver 按 0 计），拒绝连接；" +
                    "请把两端都升级到最新版本"
            )
            s.close()
            sessions.remove(s)
            stateNote = "对端版本过旧（协议 v$peerVer），请同时升级两端"
            return
        }
        // ===== 分片大小闸门（v3）：old 端不带 chunk → 按 1MB 处理，两端 offset 换算不一致会把文件写错位 =====
        val peerChunk = j.optInt("chunk", 1024 * 1024)
        if (peerChunk != Protocol.CHUNK_SIZE) {
            log(
                "对端分片 ${peerChunk}B 与本端 ${Protocol.CHUNK_SIZE}B 不一致（旧版本），拒绝连接；" +
                    "请把两端都升级到最新版本"
            )
            s.close()
            sessions.remove(s)
            stateNote = "对端版本过旧（分片 ${peerChunk}B），请同时升级两端"
            return
        }
        // ===== P4-1/P4-5 版本闸门：32MB 分块校验与配对是硬依赖，老端不带字段直接拒连 =====
        val peerBlk = j.optInt("blk", 0)
        if (peerBlk != Protocol.BLOCK_SIZE || !j.optBoolean("pr", false)) {
            log("对端不支持分块校验/配对（blk=$peerBlk pr=${j.optBoolean("pr", false)}），拒绝连接；请同时升级两端")
            s.close()
            sessions.remove(s)
            stateNote = "对端版本过旧（缺少分块校验/配对），请同时升级两端"
            return
        }
        // ===== 2 期：数据通道（lane）连接只挂载，不注册 peer、不参与去重 =====
        s.peerLanes = j.optInt("lanes", 1).coerceAtLeast(1)
        val jlane = j.optInt("lane", 0)
        if (jlane > 0) {
            // 安全闸门：lane 必须从属于一条已握手、已配对的同设备主连接，否则不挂
            val main = byPeer[pid]
            if (main == null || main.closed || !main.handshaked || !main.paired) {
                log("拒绝挂载数据通道 lane=$jlane：${s.remoteIp} 无已配对主连接（peer=$pid）")
                s.close()
                sessions.remove(s)
                return
            }
            s.lane = jlane
            s.peerId = pid
            s.peerName = j.optString("name", pid)
            s.handshaked = true
            s.laneReady = true
            s.paired = true   // lane 从属主连接，配对由主连接负责
            synchronized(laneMap) { laneMap.getOrPut(s.remoteIp) { HashMap() }[jlane] = s }
            log("数据通道 lane=$jlane 已挂载 ${s.remoteIp}（对端 lanes=${s.peerLanes}）")
            runCatching { sendOrDrop(s, Protocol.laneOk(jlane), "lane_ok", quiet = true) }
            return
        }
        // 拨号方自己的 lane 会话：收到的是对端把自己当主连接发来的 hello（lane=0），同样不能走去重
        if (s.lane > 0) {
            val main = byPeer[pid]
            if (main == null || main.closed || !main.handshaked || !main.paired) {
                log("数据通道 lane=${s.lane} 主连接未就绪（peer=$pid），关闭")
                s.close()
                sessions.remove(s)
                return
            }
            s.peerId = pid
            s.peerName = j.optString("name", pid)
            s.handshaked = true
            s.laneReady = true
            s.paired = true
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
        // 设备已可达：撤掉这一台的 Doze 兜底闹钟（无论本次是谁拨的，都说明链路已恢复）
        KeepAliveAlarm.cancel(ctx, s.remoteIp)
        // "双方连接过"在这里才成立→记进已知设备名单;放在去重之前(被丢弃那条也算真实连接)
        // 带上 pid 当身份键：名单按设备身份存，IP 变了/同设备多地址不会分裂，也不会被白名单误挡
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
                // 新连接顶掉旧连接时，把旧连接上**进行中的配对**迁移过来：
                // 不迁移的话，刚出示的码/正在输入的角色会随去重一起消失，对端提交的 pair_submit
                // 就没人核对（两端都停在配对界面，用户看到的就是「卡死」）
                if (!exist.paired && exist.pairRole != 0 && !s.paired) {
                    s.pairRole = exist.pairRole
                    s.pairCode = exist.pairCode
                    s.pairAttemptsLeft = exist.pairAttemptsLeft
                    s.pairDeadline = exist.pairDeadline
                    log("配对：进行中的配对（role=${s.pairRole}）已迁移到新连接（${s.remoteIp}）")
                }
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

        // 同一台设备换了局域网地址：新的连上、旧的自动删 —— 这一步**要等身份验证通过**才做
        // （token 命中 / 6 位码配对成功，见 onSessionReady）。握手只证明"对端自称是这个 id"，
        // 地址被 DHCP 复用给别的设备时不能凭自称就把这台设备的历史地址删掉。
        // 第一台连上的设备默认成为当前对话目标;选中目标无健康会话时也跟过去;
        // 判据按设备(组内任一地址健康)不能用精确IP,否则另一台设备的握手会抢走当前对话
        if (selectedIp.isEmpty() || sessionByGroup(selectedIp) == null) {
            selectedIp = s.remoteIp
            selectedLabel = s.peerName
        } else if (pid.isNotEmpty() && prefs.convKeyOf(selectedIp) == pid && selectedIp != s.remoteIp) {
            // 当前对话就是这台设备：这次是在它的新地址上握手的，选中地址跟到新地址上，
            // 免得顶栏/聊天区停在一条马上要被清掉的老 IP 上
            selectedIp = s.remoteIp
            selectedLabel = s.peerName
        }
        notifyPeers()
        refreshState()
        // P4-5：已配对（hello.tok 命中）→ 直接放行；否则进入 6 位码配对（内容帧在此之前一律丢弃）
        startPairingOrActivate(s, j)
    }

    /**
     * 配对入口。
     * <ul>
     *   <li>双方都持有同一 token：发起方等 pair_ok；接收方回 pair_ok 后直接放行。</li>
     *   <li>用户点了设备：由点的那一端出示 6 位码（见 [requestPair]），对端收到 pair_need 才切到数字键盘。</li>
     *   <li>用户没点：**两端都不弹**。会话留在「已连接、未配对」，设备列表提示「未配对，点一下配对」；
     *       用户点一下设备才出示配对码（早期「发起方自动弹」会把启动自动连接变成糊一脸 PIN 页）。</li>
     * </ul>
     */
    internal fun startPairingOrActivate(s: Session, hello: JSONObject) {
        val pid = s.peerId
        val peerTok = hello.optString("tok", "")
        val myTok = runCatching { prefs.pairedTokenOf(pid) }.getOrDefault("")
        val tokenMatch = myTok.isNotEmpty() && peerTok.isNotEmpty() &&
            MessageDigest.isEqual(myTok.toByteArray(), peerTok.toByteArray())
        if (!tokenMatch && myTok.isNotEmpty() && peerTok.isEmpty()) {
            // 对端这次没带 token ≠ 它清过数据：可能只是它换了 IP（按 IP 查表查空）、
            // 或者刚启动还没读到缓存、或者只更新了一端。**绝不能在这里删除本机配对记录**
            // ——旧实现正是在这里 unpair，重启/换 DHCP 一次就要重新配对，而且两端互相删、再也回不去。
            // 保留记录：这次按"未配对"走 6 位码流程；下次对端带上 token 就还能直接放行。
            log("配对：对端本次未带 token（${s.remoteIp}），按未配对处理，保留本机配对记录")
            // 本机记录还在、只是**这次带过去的 token 不对**（按地址查空/地址被别的设备占了）⇒
            // 用按 deviceId 取到的权威 token 补发一次 hello。两端都这么做就能互相核对上，
            // 不用用户重新配对（这就是"连接过的设备反复要验证"的根因之一）。
            if (s.pairTokenSent != myTok) {
                s.pairTokenSent = myTok
                io { if (!s.closed) resendHello(s, myTok) }
                log("配对：对端没带 token，已按 deviceId 补发一次带 token 的 hello（${s.remoteIp}）")
            }
        }
        if (tokenMatch) {
            // 身份核对通过 ⇒ 把「设备 → 本次地址」的缓存刷新到这条连接上：
            // 设备换过 IP 时旧缓存会失效/被 DHCP 复用，刷新后下次按地址兜底也查得到
            runCatching { prefs.markPaired(pid, s.peerName, myTok, s.remoteIp) }
            // token 已经放行 → 用户之前点的配对意图自动作废
            if (matchesPendingPair(s)) clearPendingPair()
            if (!s.outgoing) {
                // 接收方：token 已核对通过，明确告诉发起方「不用配对」
                s.paired = true
                sendOrDrop(s, Protocol.pairOk(""), "pair_ok", quiet = true)
                onSessionReady(s)
            } else {
                // 已就绪会话重发 hello（能力更新）：保持配对态，不重走等待
                if (s.paired) {
                    log("配对：已就绪会话收到 hello 重发，保持配对（${s.remoteIp}）")
                    return
                }
                // 本机这次 hello 带过去的 token 不是权威值（它按地址取、地址缓存被清过或被别的设备占了）⇒
                // 对端拿不到能对上号的 token，只能当我们没配对。用按 deviceId 取到的权威 token 补发一次 hello：
                // 对端据此重新核对，命中就直接回 pair_ok，不用用户重新配对。
                if (s.pairTokenSent != myTok) {
                    s.pairTokenSent = myTok
                    io { if (!s.closed) resendHello(s, myTok) }
                    log("配对：本次 hello 的 token 与设备身份不符，已按 deviceId 补发一次（${s.remoteIp}）")
                }
                // 发起方：token 已带过去，等 pair_ok 或 pair_need（由心跳看 pairDeadline）
                s.paired = false
                s.pairRole = 0
                s.pairDeadline = System.currentTimeMillis() + PAIR_TTL_MS
                log("配对：本机持有 token，等待对端确认（${s.remoteIp}）")
            }
            return
        }
        // 已配对且内容通道已放行的会话，收到 hello 重发（能力更新/解码器枚举完）时 token 可能对不上
        // （本端换过 IP、或对端只更新了一端）——**不能因此把会话降级成未配对**：
        // 旧实现在这里 s.paired=false，随后该连接上所有内容帧被配对闸门丢掉，用户看到的是连接忽然"半死"。
        if (s.paired) {
            log("配对：已就绪会话收到 token 对不上的 hello 重发，保持配对（${s.remoteIp}）")
            return
        }
        // 配对进行中（本端已出示码 / 正在输入码）时收到 hello 重发（例如视频解码器枚举完后的能力更新），
        // **绝不能把配对状态清掉**：旧实现会清空 pairRole/pairCode，对端随后提交的码就被当成
        // 「没有待核对的码」静默丢弃；若这里还重新生成一次码，用户照着旧码输永远不对 —— 两端都停在配对界面。
        if (s.pairRole != 0 || s.pairCode.isNotEmpty()) {
            log("配对：配对进行中（role=${s.pairRole}），忽略 hello 重发对配对状态的重置（${s.remoteIp}）")
            return
        }
        s.paired = false
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS
        s.pairRole = 0
        s.pairCode = ""
        // 不设 pairDeadline：没弹窗就不该因为「配对超时」把一条好连接关掉
        s.pairDeadline = 0L
        if (matchesPendingPair(s)) {
            clearPendingPair()
            log("配对：${s.peerName}($pid) 是用户刚点的设备，连接已建立，出示配对码")
            showPairAsCode(s)
        } else {
            // 本机是发起方、但用户没点这台设备（启动自动连接 / 扫描后自动连接）：
            // **不自动出示**。自动弹配对码与设计相反（设备列表/PC 端都写着「未配对，点一下配对」），
            // 现象就是「选了保留数据，一启动就被糊一脸 PIN 页」；用户点一下设备走 requestPair 即可。
            log("配对：${s.peerName}($pid) 已连接但未配对，等用户点设备再出示配对码（不自动弹）")
        }
    }

    /** 用户主动点了一台设备：未配对就走「本端出示 6 位码 → 对端输入」。
     *  连接还没起来时只记下意图，握手完成后再出示（见 [startPairingOrActivate]）。 */
    fun requestPair(ip: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        val s = sessionByGroup(v)
        if (s == null || s.closed || !s.handshaked) {
            pendingPairIp = v
            pendingPairAt = System.currentTimeMillis()
            log("配对：已记下用户配对意图（$v），连接建立后出示配对码")
            return
        }
        if (s.paired) return
        // 已经在输入码（弹窗还开着）：保持现状，别把它切成「出示码」，否则两端都可能亮码、谁都没法输
        if (s.pairRole == 2) return
        clearPendingPair()
        showPairAsCode(s)
    }

    /** 本端出示 6 位码并通知对端弹出数字键盘（只在用户主动点设备时调用，不再随连接自动弹） */
    private fun showPairAsCode(s: Session) {
        if (s.paired || s.closed) return
        // 已经在出示码：沿用旧码，只把覆盖层重新推给界面。重新生成会让用户照着旧码输永远不对
        if (s.pairRole == 1 && s.pairCode.isNotEmpty()) {
            showPairUi(
                PairUi(
                    ip = s.remoteIp, name = s.peerName, showCode = true, code = s.pairCode,
                    attemptsLeft = s.pairAttemptsLeft, ttlMs = PAIR_TTL_MS
                )
            )
            return
        }
        val code = newPairCode()
        s.pairCode = code
        s.pairRole = 1
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS
        s.pairDeadline = System.currentTimeMillis() + PAIR_TTL_MS
        log("配对：用户主动发起 ${s.peerName}(${s.peerId})，本端出示 6 位码，等待对方输入")
        showPairUi(
            PairUi(
                ip = s.remoteIp, name = s.peerName, showCode = true, code = code,
                attemptsLeft = PAIR_MAX_ATTEMPTS, ttlMs = PAIR_TTL_MS
            )
        )
        // 点设备/握手线程都可能在主线程：帧一律丢进发送队列，别在调用线程直接写 socket
        io { if (!s.closed) sendOrDrop(s, Protocol.pairNeed(PAIR_TTL_MS), "pair_need", quiet = true) }
    }

    /** 用户点的地址是否就是这条会话的设备（同设备换地址也算同一台） */
    private fun matchesPendingPair(s: Session): Boolean {
        val want = pendingPairIp
        if (want.isEmpty()) return false
        if (System.currentTimeMillis() - pendingPairAt > PAIR_TAP_INTENT_TTL_MS) return false
        if (want == s.remoteIp) return true
        return runCatching { prefs.convKeyOf(want) }.getOrNull() ==
            runCatching { prefs.convKeyOf(s.remoteIp) }.getOrNull()
    }

    private fun clearPendingPair() {
        pendingPairIp = ""
        pendingPairAt = 0L
    }

    /** 配对完成：内容通道正式可用（补发待发消息 + 历史同步） */
    internal fun onSessionReady(s: Session) {
        if (s.closed) return
        val first = !s.readyOnce
        s.readyOnce = true
        s.paired = true
        s.pairRole = 0
        s.pairCode = ""
        s.pairDeadline = 0L
        // 配对成功即「已知设备」：配对码是用户授权，不再要求先收发过消息/文件
        // （否则配对完不发消息、过一会后台重连就被自己的白名单挡掉）
        runCatching { trusted?.markPaired(s.remoteIp, s.peerName, s.peerId) }
        // 设备身份=deviceId+token：本次用的是这条地址，就把 token 的地址缓存对齐过来
        // （旧地址清理在握手阶段做，见 startPairingOrActivate；这里只管 token 缓存）
        val readyTok = runCatching { prefs.pairedTokenOf(s.peerId) }.getOrDefault("")
        if (readyTok.isNotEmpty()) {
            runCatching { prefs.markPaired(s.peerId, s.peerName, readyTok, s.remoteIp) }
        }
        // ==== 身份验证通过之后，才替换这台设备的历史地址 ====
        // 先验证、再替换：握手阶段只做身份绑定（谁自称是谁），**不删任何旧地址**；
        // 只有 token 命中 / 6 位码配对成功（走到这里）才认定「同一台设备换了局域网地址」，
        // 新的连上、旧的自动删。否则 DHCP 把老地址分给别的设备时会误删这台设备的历史记录。
        // 判"旧地址还有没有会话"必须按精确 IP（isConnectedIp），不能按设备组判，否则新地址的会话
        // 会让旧地址一直算"连着的"，旧地址永远删不掉。
        if (s.peerId.isNotEmpty()) {
            val selWasThisDevice = selectedIp.isNotEmpty() && prefs.convKeyOf(selectedIp) == s.peerId
            val synced = runCatching {
                prefs.syncGroupLanIp(s.peerId, s.remoteIp) { ip -> isConnectedIp(ip) }
            }.getOrDefault(false)
            if (synced) {
                log("设备换地址：${s.peerName}(${s.peerId}) 局域网地址已切到 ${s.remoteIp}")
                // 当前对话就是这台设备：选中地址跟到刚验证通过的新地址上（旧地址马上被删）
                if (selWasThisDevice && selectedIp != s.remoteIp) {
                    selectedIp = s.remoteIp
                    selectedLabel = s.peerName
                }
            }
        }
        if (matchesPendingPair(s)) clearPendingPair()
        if (pairUiMatches(s.remoteIp)) {
            pairUiIp = ""
            listener?.onPairState(null)
        }
        notifyPeers()
        refreshState()
        if (!first) {
            // 重发 hello 触发的二次 ready：只刷新状态，不再重复补发/hsync
            return
        }
        // 这台设备可达→撤掉它自己的"没网络"票；下面 flushPending 负责续发
        setFileVote(VOTE_NO_NETWORK, false, s.remoteIp)
        flushPending(s.remoteIp)
        if (s.caps.hs) {
            runFrameTask("hsync_start") { startHsync(s) }
        }
    }

    private fun showPairUi(ui: PairUi) {
        pairUiIp = ui.ip
        listener?.onPairState(ui)
    }

    /** 发起方用户提交 6 位码（覆盖层数字键盘「确认」调用；**可能在主线程**，发送必须异步） */
    fun submitPairCode(ip: String, code: String) {
        val v = ip.trim()
        val digits = code.filter { it.isDigit() }.take(PAIR_CODE_LEN)
        if (digits.length != PAIR_CODE_LEN) return
        val s = sessionByGroup(v)
        if (s == null || s.closed || s.pairRole != 2 || s.paired) {
            // 连接被重连替换 / 对端已取消 / 已超时：不能一声不响地什么都不做，
            // 否则键盘按了像卡死。给明确结果并收掉本端覆盖层，用户重新点设备即可再配。
            log("配对：提交被忽略（会话=${s?.remoteIp ?: v} role=${s?.pairRole ?: -1}）")
            showPairUi(
                PairUi(
                    v, s?.peerName ?: "", showCode = false, code = "",
                    attemptsLeft = 0, done = true, ok = false,
                    message = "配对状态已失效，请关闭后重新点设备配对"
                )
            )
            return
        }
        // 先本地切「正在验证…」，再把帧丢进发送队列：
        // 主线程直接写 socket 会等写锁（文件分片可能正占着），对端读得慢时界面就卡在这里
        showPairUi(
            PairUi(
                ip = s.remoteIp, name = s.peerName, showCode = false, code = "",
                attemptsLeft = s.pairAttemptsLeft, ttlMs = PAIR_TTL_MS,
                message = "正在验证…"
            )
        )
        io { if (!s.closed) sendOrDrop(s, Protocol.pairSubmit(digits), "pair_submit", quiet = true) }
    }

    /**
     * 放弃本次配对（覆盖层「取消」调用）：只关掉两端的配对弹窗，**保留连接**。
     * 之前是直接断会话，而两端启动时都会自动连，断完几秒又被连上、码又弹出来——用户根本取消不掉。
     * 现在连接留在「已连接但未配对」，用户下次点设备再重新出示码。
     */
    fun cancelPairing(ip: String) {
        val s = sessionByGroup(ip)
        if (s != null && s.paired) {
            // 已经配对成功：只把这层提示关掉
            clearPairUi()
            return
        }
        log("配对：用户取消 ${s?.peerName ?: ip}(${s?.remoteIp ?: ip})，保留连接")
        clearPendingPair()
        if (s != null) {
            s.pairRole = 0
            s.pairCode = ""
            s.pairDeadline = 0L
            if (!s.closed && s.handshaked) {
                // 通知对端把它那半边的配对弹窗也关掉（否则对端会一直停在输入框）；
                // 取消按钮在主线程，发送一律走 io，别让写锁把界面卡住
                io { if (!s.closed) sendOrDrop(s, Protocol.pairCancel(), "pair_cancel", quiet = true) }
            }
        }
        // 不管有没有会话都要关掉本端覆盖层：旧实现里会话已断时这里直接 return，弹窗会卡住关不掉
        clearPairUi()
    }

    private fun clearPairUi() {
        pairUiIp = ""
        listener?.onPairState(null)
    }

    /** 对端在它那边点了「取消」：关掉本端弹窗，连接保留 */
    internal fun onPairCancel(s: Session) {
        if (s.paired) return
        log("配对：对端取消（${s.remoteIp}）")
        s.pairRole = 0
        s.pairCode = ""
        s.pairDeadline = 0L
        if (pairUiMatches(s.remoteIp)) clearPairUi()
    }

    internal fun onPairSubmit(s: Session, j: JSONObject) {
        if (s.paired) return
        val expect = s.pairCode
        if (s.pairRole != 1 || expect.length != PAIR_CODE_LEN) {
            // 本端此刻没有可核对的码（连接被重连替换 / 已取消 / 已超时）：
            // 明确回一帧 pair_cancel 让对端从「正在验证…」退出并关掉键盘，
            // 本端若还挂着覆盖层也一并收掉。旧实现直接 return ⇒ 两端都停在配对界面（用户看到的就是「卡死」）。
            log("配对：收到提交但本端没有待核对的码（role=${s.pairRole}），回 pair_cancel 让对端退出")
            if (!s.closed) sendOrDrop(s, Protocol.pairCancel(), "pair_cancel", quiet = true)
            val keepIp = s.remoteIp
            val keepName = s.peerName
            s.pairRole = 0
            s.pairCode = ""
            s.pairDeadline = 0L
            if (pairUiMatches(keepIp)) {
                showPairUi(
                    PairUi(
                        keepIp, keepName, showCode = false, code = "",
                        attemptsLeft = 0, done = true, ok = false,
                        message = "配对状态已失效，请关闭后重新点设备配对"
                    )
                )
            }
            return
        }
        val code = j.optString("code", "")
        val ok = code.length == PAIR_CODE_LEN &&
            MessageDigest.isEqual(expect.toByteArray(), code.toByteArray())
        if (ok) {
            val token = newPairToken()
            runCatching { prefs.markPaired(s.peerId, s.peerName, token, s.remoteIp) }
            sendOrDrop(s, Protocol.pairOk(token), "pair_ok", quiet = true)
            log("配对成功：${s.peerName}(${s.peerId})")
            onSessionReady(s)
        } else {
            s.pairAttemptsLeft -= 1
            if (s.pairAttemptsLeft <= 0) {
                log("配对失败：${s.peerName} 连续输错，关闭会话")
                sendOrDrop(s, Protocol.pairFail(0), "pair_fail", quiet = true)
                showPairUi(
                    PairUi(
                        s.remoteIp, s.peerName, showCode = true, code = "",
                        attemptsLeft = 0, done = true, ok = false, message = "配对码错误次数过多，请重连后重新配对"
                    )
                )
                dropSession(s)
            } else {
                sendOrDrop(s, Protocol.pairFail(s.pairAttemptsLeft), "pair_fail", quiet = true)
                showPairUi(
                    PairUi(
                        s.remoteIp, s.peerName, showCode = true, code = s.pairCode,
                        attemptsLeft = s.pairAttemptsLeft, ttlMs = PAIR_TTL_MS,
                        message = "配对码不正确，还剩 ${s.pairAttemptsLeft} 次"
                    )
                )
            }
        }
    }

    /**
     * 对端点了设备、出示它的码，本端切到数字键盘。
     * 两端都可能主动点：如果本端此刻也在出示码（two-side 同时发起），按 deviceId 定一方出示、一方输入，
     * 否则两边都亮码、谁都没法输入。比较口径两端对称（myId 与 peerId 互换），结果必然只有一方保留码。
     */
    internal fun onPairNeed(s: Session, j: JSONObject) {
        if (s.paired) return
        val ttl = j.optLong("ttl", PAIR_TTL_MS)
        if (s.pairRole == 1 && s.pairCode.isNotEmpty()) {
            if (myId <= s.peerId) {
                log("配对：两端同时发起，本端继续出示配对码（${s.remoteIp}）")
                return
            }
            log("配对：两端同时发起，本端改为输入对端的配对码（${s.remoteIp}）")
            s.pairCode = ""
        }
        s.pairRole = 2
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS
        s.pairDeadline = System.currentTimeMillis() + ttl
        showPairUi(
            PairUi(
                s.remoteIp, s.peerName, showCode = false, code = "",
                attemptsLeft = s.pairAttemptsLeft, ttlMs = ttl,
                message = "请输入对方设备上显示的 6 位配对码"
            )
        )
    }

    internal fun onPairOk(s: Session, j: JSONObject) {
        val token = j.optString("token", "")
        if (token.isNotEmpty()) {
            runCatching { prefs.markPaired(s.peerId, s.peerName, token, s.remoteIp) }
        } else if (prefs.pairedTokenOf(s.peerId).isEmpty()) {
            log("配对确认回来了但本机没有 token（会话=${s.remoteIp}），按未配对处理")
            return
        } else if (!s.outgoing || s.pairTokenSent.isEmpty()) {
            // 空 token 的 pair_ok 只对"确实带过 token 的发起方"有效，防伪造对端空口放行
            log("配对确认缺 token 且本端未带过 token（会话=${s.remoteIp}），按未配对处理")
            return
        }
        log("配对成功（token 校验）：${s.peerName}(${s.peerId})")
        onSessionReady(s)
    }

    internal fun onPairFail(s: Session, j: JSONObject) {
        val left = j.optInt("left", 0)
        s.pairAttemptsLeft = left.coerceAtLeast(0)
        if (left <= 0) {
            showPairUi(
                PairUi(
                    s.remoteIp, s.peerName, showCode = false, code = "",
                    attemptsLeft = 0, done = true, ok = false, message = "配对码错误次数过多，请重连后重新配对"
                )
            )
            dropSession(s)
        } else {
            showPairUi(
                PairUi(
                    s.remoteIp, s.peerName, showCode = false, code = "",
                    attemptsLeft = left, ttlMs = PAIR_TTL_MS,
                    message = "配对码不正确，还剩 $left 次"
                )
            )
        }
    }

    private fun newPairCode(): String =
        String.format(java.util.Locale.US, "%06d", PAIR_RANDOM.nextInt(1_000_000))

    private fun newPairToken(): String {
        val b = ByteArray(16)
        PAIR_RANDOM.nextBytes(b)
        return b.joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }
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

    // ==================== 文件：接收（缓冲池 + 断点续传） ====================

    /** 一个正在接收的文件:缓冲池(3块各8MB)轮转,攒满1MB即入队交落盘线程、自己取空闲块继续填;
     *  没有空闲块才排队等(背压);正在写盘的块绝不会再被读线程复用。见 LinkReceive.Rx */
    internal val rxMap = HashMap<String, Rx>()
    internal val dirMap = HashMap<String, DirRx>()

    /** 被停滞暂停的单文件mid(v2.8):下一个进度帧到达时气泡回"发送中"并清标记;目录成员不用标记 */
    internal val pausedMids = ConcurrentHashMap.newKeySet<String>()

    /** 保留多少条「已收尾」的目录记录（供同一 mid 重发时复用目录名，见 pruneDirs） */
    internal val MAX_KEEP_DIRS = 32

    /** 发送端等 file_resume 应答用：mid → 容量为 1 的信箱 */
    internal val resumeWaiters =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Long>>()

    // ===== 32MB 分块大小校验（不做 hash） =====
    /** 接收端的大小核验结果：ok=false 请从 0 重来；offset=实际核验通过的续传起点 */
    internal class BlkAckResult(val ok: Boolean, val offset: Long)
    /** 发送端等「大小核验 + 截断就绪」确认用（mid → 核验结果） */
    internal val blkAckWaiters =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<BlkAckResult>>()

    /** 接收端待续传准备（file_query 命中半成品 → 等发送端选好块起点再做 truncate + createRx） */
    internal class ResumePrep(
        val mid: String, val name: String, val size: Long, val peerIp: String, val uri: String
    )
    internal val resumePrep = ConcurrentHashMap<String, ResumePrep>()
    /** file_resume 里 needBlk=true：续传前先做 32MB 分块大小校验 */
    internal val resumeNeedBlk = ConcurrentHashMap<String, Boolean>()

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
            val t = Protocol.limitText(text)
            val s = target()
            val item = MsgItem(
                mid = newMid(), dir = Dir.SELF, kind = Kind.TEXT, text = t,
                peerIp = s?.remoteIp ?: selectedIp
            )
            // 主动发文本 → 该设备有过消息往来（即刻送达或进待发队列都算一次记录）
            trustContent(item.peerIp, isFile = false)
            if (s != null && sendOrDrop(s, Protocol.text(item.mid, t), "text")) {
                emit(item)
                log("已发送文本 ${t.length} 字 → ${item.peerIp}")
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
                // 无设备丢弃口径（S7）：连"选中设备"都没有时就不暂存——曾经以 peerIp="" 塞进待发队列，
                // sessionForPending("") 恒 null，那条永远补发不出去，只是静默积压。
                // 有选中设备（只是离线）仍按原语义暂存，连上后补发。
                val sel = selectedIp
                if (sel.isNullOrEmpty()) {
                    log("剪贴板未推送：当前没有已连接设备、也没有选中设备（不暂存）")
                    return@io
                }
                log("剪贴板暂存待发：选中设备 $sel 当前未连接，连上后补发")
                queuePending(
                    MsgItem(
                        mid = newMid(), dir = Dir.SELF, kind = Kind.CLIP, text = text,
                        peerIp = sel
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

    /**
     * 取消墓碑：用户点「取消发送」后把 mid **长期**记住（不随发送循环消费掉）。
     *
     * 为什么要有：[cancelled] 是「当前这次尝试到此为止」的一次性信号，发送循环读到就 remove。
     * 但同一条文件可能已经排进了串行发送队列（syncNow→flushPendingNow 的「待发补发」任务），
     * 循环一消费掉标记，排队的那个任务就会拿同一个 mid **重新开传**——于是取消完过几秒
     * 对端又冒出气泡继续传，本端气泡已经没了、看不到进度。
     * 墓碑只在用户显式「继续/重试」（[forgetCanceled]）或整条重新发送时清除。
     */
    private val cancelTombs: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.LinkedHashSet<String>()
    )

    /** 记一条取消墓碑（最多留 [CANCEL_TOMB_MAX] 条，超出按最老的淘汰，防止无限增长） */
    internal fun markCanceled(mid: String) {
        if (mid.isEmpty()) return
        synchronized(cancelTombs) {
            cancelTombs.add(mid)
            if (cancelTombs.size > CANCEL_TOMB_MAX) {
                val it = cancelTombs.iterator()
                while (cancelTombs.size > CANCEL_TOMB_MAX && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
    }

    /** 用户显式重试/继续：清掉墓碑，允许这一条重新发送 */
    internal fun forgetCanceled(mid: String) {
        if (mid.isEmpty()) return
        synchronized(cancelTombs) { cancelTombs.remove(mid) }
    }

    /** 这条（或它所属的文件夹）是否已被用户取消且还没被「继续」 */
    internal fun isCanceled(mid: String, dirId: String = ""): Boolean =
        cancelTombs.contains(mid) || (dirId.isNotEmpty() && cancelTombs.contains(dirId))

    /**
     * 文件夹发送时对端「已经在盘上」的成员清单（dir_have）信箱：dirMid → 容量 1 的信箱。
     * 断链重发整目录时对端先回报这份清单，发送端据此跳过，只补真正缺的文件（局域网，按文件粒度即可，不做字节级续传）。
     */
    internal val dirHaveBox =
        ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<List<String>>>()

    /** 成员 mid → 「所属目录 mid + \\u0001 + 成员 relPath」：成员 ack 到达时据此记账（S15/S24） */
    internal val dirMemberAck = ConcurrentHashMap<String, String>()

    /** 文件夹成员回执：只做记账/日志（是否需要重发由 dir_end 的清单对账决定，不再维护另一份完成表） */
    internal fun Link.onMemberAck(mid: String, ok: Boolean): Boolean {
        val v = dirMemberAck.remove(mid) ?: return false
        val sep = v.indexOf('\u0001')
        if (sep <= 0) return true
        val relPath = v.substring(sep + 1)
        if (!ok) {
            log("成员 $relPath 对端回报未收妥，等 dir_end 清单对账后补发")
        }
        return true
    }

    /** 整目录收尾/取消时清掉该目录的成员回执登记（防无界增长） */
    internal fun Link.clearMemberAcks(dirId: String) {
        if (dirId.isEmpty()) return
        val it = dirMemberAck.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.startsWith("$dirId\u0001")) it.remove()
        }
    }

    internal fun isCancelled(mid: String, dirId: String): Boolean =
        cancelled.contains(mid) || (dirId.isNotEmpty() && cancelled.contains(dirId)) ||
            isCanceled(mid, dirId)

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
        if (peerDm) {
            when (askPeerDataConsent(s, mid, kind, name, size)) {
                PeerConsent.YES -> {}
                PeerConsent.REFUSED -> return DataConsent.REMOTE_REFUSED
                // P2-1：90s 无应答 ≠ 拒绝。单独返回，上层留在待发队列/给「对方未确认」文案，且不投票③
                PeerConsent.TIMEOUT -> return DataConsent.REMOTE_TIMEOUT
            }
        }
        return DataConsent.OK
    }

    internal enum class PeerConsent { YES, REFUSED, TIMEOUT }

    /** 发 data_ask 并等 data_ans；超时/发送失败一律按「未同意」处理（宁可少发，不偷偷花流量） */
    internal fun askPeerDataConsent(
        s: Session, mid: String, kind: String, name: String, size: Long
    ): PeerConsent {
        val box = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        dataAnsBoxes[mid] = box
        return try {
            if (!sendOrDrop(s, Protocol.dataAsk(mid, kind, name, size), "data_ask", quiet = true)) {
                log("数据流量确认：对端会话不可用，按未同意处理")
                return PeerConsent.TIMEOUT
            }
            val r = runCatching { box.poll(DATA_ANS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull()
            if (r == null) {
                log("数据流量确认：对端 ${DATA_ANS_WAIT_MS / 1000}s 内未应答，按超时处理（不投票③）")
                PeerConsent.TIMEOUT
            } else if (r) {
                PeerConsent.YES
            } else {
                PeerConsent.REFUSED
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
    /**
     * 「必须让用户看见」的提示通道（发送前校验失败等）。可在任意后台线程直接调用，
     * 由实现方（AppCore）负责切主线程弹 Toast。
     */
    @Volatile
    var notice: ((String) -> Unit)? = null

    /** 提示用户 + 记日志（校验失败/空文件夹忽略等） */
    internal fun notifyUser(msg: String) {
        log(msg)
        val n = notice
        if (n != null) runCatching { n(msg) }
    }

    fun sendFile(uri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val name = FileIO.queryName(ctx, uri)
            // 发送前名校验：超 MAX_NAME_BYTES（200）/ 含 Linux-Windows 敏感字符 → 提示并**取消本次发送**
            NameRules.fileNameError(name)?.let { err ->
                notifyUser("$err，已取消发送")
                return@Thread
            }
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
            if (s == null || fileGateBlocked(item.peerIp)) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true, item.peerIp)
                    log("离线，文件 $name 进入待发队列")
                } else {
                    log("[文件排他] ${gateReason(item.peerIp)}：文件 $name 排队，恢复后自动续发（D16）")
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
                    setFileVote(VOTE_NO_NETWORK, true, item.peerIp)   // 2.12：排队期间掉线 = "原本没网络"那一票
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
                    DataConsent.REMOTE_TIMEOUT -> holdForDataTimeout(item)
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true, item.peerIp)   // 2.12：确认期间掉线 = "原本没网络"票
                        okUpdate(item.copy(status = Status.PENDING))
                        queuePending(item)
                        log("确认期间会话断开，文件 $name 进入待发队列")
                        wakeUp()
                    }
                }
            }
        }, "pp-send-file").apply { isDaemon = true }.start()
    }

    /** 主动发文件夹(发给当前对话设备);**空文件夹一律忽略**(不建气泡/不进待发/不发 dir_meta);treeUri支持SAF目录树或本机file://路径 */
    fun sendFolder(treeUri: Uri, preGranted: Boolean = false) {
        val ip = selectedIp
        Thread({
            val scan = FileIO.scanTree(ctx, treeUri)
            val files = scan.files
            val name = FileIO.queryName(ctx, treeUri)
            if (scan.skipped > 0) {
                log("注意：目录过深/过大或部分子树读取失败，${scan.skipped} 个分支未包含在传输中")
            }
            // 空文件夹直接忽略：不记清单、不出气泡、不入待发（双端统一规则）
            if (files.isEmpty() && scan.skipped == 0) {
                log("空文件夹已忽略：$name 内没有任何文件，本次未发送")
                return@Thread
            }
            // 一个文件都没读到、却又有整块读失败:是"读不到"不是"空的",不照发count=0的假成功
            if (files.isEmpty()) {
                log("文件夹内容读取失败（${scan.skipped} 个分支未读到），已中止发送 —— 请确认该文件夹仍可访问")
                return@Thread
            }
            // 发送前名校验：文件夹名 + 每个成员的相对路径；任一项不合格 → 提示并取消整次发送
            NameRules.folderError(name, files.map { it.relPath })?.let { err ->
                notifyUser("$err，已取消发送")
                return@Thread
            }
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
            if (s == null || fileGateBlocked(item.peerIp)) {
                emit(item.copy(status = Status.PENDING))
                queuePending(item)
                if (s == null) {
                    setFileVote(VOTE_NO_NETWORK, true, item.peerIp)
                    log("离线，文件夹 $name（${files.size} 个文件）进入待发队列")
                } else {
                    log("[文件排他] ${gateReason(item.peerIp)}：文件夹 $name（${files.size} 个文件）排队，恢复后自动续发（D16）")
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
                    setFileVote(VOTE_NO_NETWORK, true, item.peerIp)   // 2.12：排队期间掉线 = "原本没网络"那一票
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
                    DataConsent.REMOTE_TIMEOUT -> holdForDataTimeout(item)
                    DataConsent.NO_SESSION -> {
                        setFileVote(VOTE_NO_NETWORK, true, item.peerIp)   // 2.12：确认期间掉线 = "原本没网络"票
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

    /**
     * P2-1：对端 [DATA_ANS_WAIT_MS] 内没有应答 —— **这不是拒绝**。
     * 气泡回到「待发送」、条目留在待发台账，等下次握手或手动重试再问一次；
     * 不投票③（否则一次对端卡死就把整台设备的文件发送锁 10 分钟）。
     */
    internal fun Link.holdForDataTimeout(item: MsgItem) {
        val p = item.copy(status = Status.PENDING, progress = 0)
        emit(p)
        queuePending(p)
        log("[数据流量] 对端 ${DATA_ANS_WAIT_MS / 1000}s 未确认「${item.fileName}」，留在待发队列（不投票③）")
    }

    /** 把文件消息置为"数据流量未同意":气泡留在聊天里,红色叹号可重试;备注写进text(只DENIED时渲染);
     *  只有真的"被拒"才投第三票,偶发失败出口(未连接/不可读)显式传false归到VOTE_NO_NETWORK */
    internal fun denyFileByData(item: MsgItem, note: String, voteDenied: Boolean = true) {
        store?.removePending(item.mid)
        okUpdate(item.copy(status = Status.DENIED, progress = -1, text = note))
        log("文件「${item.fileName}」未发送：$note")
        if (voteDenied) {
            // 2.12/D16：被拒=票③，但作用域只到这台设备（按设备组键记，10 分钟或切到局域网自动解除）
            denyConsent(item.peerIp.ifEmpty { selectedIp }, note)
        }
    }

    /** 重试一条"数据流量未同意"的文件(红色叹号触发):重新完整走提醒流程;
     *  同意→onMessageMoved挪到最新再发送;再拒→保持DENIED */
    fun retryFileSend(
        mid: String, uri: Uri, name: String, size: Long, isDir: Boolean, peerIp: String
    ) {
        enqueueFileSend("重试发送 $name") {
            // 用户显式「继续/重试」：清掉取消墓碑（否则会被当成已取消直接跳过）
            forgetCanceled(mid)
            cancelled.remove(mid)
            // 发送前名校验：不合格 → 提示并把这条标失败/清待发（取消本次传输）
            val nameErr = if (isDir) NameRules.folderNameError(name) else NameRules.fileNameError(name)
            if (nameErr != null) {
                notifyUser("$nameErr，已取消发送")
                store?.removePending(mid)
                denyFileByData(
                    MsgItem(mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name, peerIp = peerIp),
                    "$nameErr，已取消发送",
                    voteDenied = false
                )
                return@enqueueFileSend
            }
            // 文件夹成员逐段名校验必须在**数据流量确认之前**完成：不合规直接取消，别让用户白点一次确认
            var dirScan: FileIO.TreeScan? = null
            if (isDir) {
                val scan = FileIO.scanTree(ctx, uri)
                dirScan = scan
                if (scan.files.isEmpty()) {
                    // 空文件夹忽略 / 读不到：都不重发，如实收尾（本地原因 ≠ 用户拒绝，不许投全局票）
                    val why = if (scan.skipped > 0) "文件夹已不可读" else "空文件夹已忽略（未发送）"
                    log("重试取消：$why（$name）")
                    denyFileByData(
                        MsgItem(
                            mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                            isDir = true, peerIp = peerIp
                        ),
                        why, voteDenied = false
                    )
                    return@enqueueFileSend
                }
                NameRules.folderError(name, scan.files.map { it.relPath })?.let { err ->
                    notifyUser("$err，已取消发送")
                    store?.removePending(mid)
                    denyFileByData(
                        MsgItem(
                            mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                            isDir = true, peerIp = peerIp
                        ),
                        "$err，已取消发送", voteDenied = false
                    )
                    return@enqueueFileSend
                }
            }
            // 2.12/D16:手动重试仍是该设备票③的显式清零点;放在入口而不是成功后,
            // 再被拒时denyConsent会重新投 1
            clearDenyConsent(peerIp, flush = false)
            val s = sessionByGroup(peerIp)
            if (s == null) {
                setFileVote(VOTE_NO_NETWORK, true, peerIp)   // 归票 ②：握手完成自动清，不靠下一次手动重试
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
                        // 扫描与逐段校验已在确认之前完成，这里直接开传
                        val scan = dirScan ?: FileIO.scanTree(ctx, uri)
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
                DataConsent.REMOTE_TIMEOUT -> holdForDataTimeout(
                    MsgItem(
                        mid = mid, dir = Dir.SELF, kind = Kind.FILE, fileName = name,
                        fileSize = size, filePath = uri.toString(), localUri = uri.toString(),
                        progress = 0, isDir = isDir, status = Status.PENDING, peerIp = peerIp
                    )
                )
                DataConsent.NO_SESSION -> {
                    // 会话在这一步断了：归票 ②（握手完成自动清），不是用户拒绝 ⇒ 不投全局票
                    setFileVote(VOTE_NO_NETWORK, true, peerIp)
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

    /** 待发条目的目的会话:严格按条目自带的 peerIp 解析。
     *  条目没带目的设备时**绝不**回落当前对话设备（防内容串台；不兼容旧版空 peerIp 数据） */
    internal fun sessionForPending(peerIp: String): Session? = sessionByGroup(peerIp)

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
    /** 对端报缺失后最多补发几轮（局域网一次补齐即可；再缺说明是真发不过去，如实报失败） */
    internal val DIR_RESEND_ROUNDS = 1

    /** 等 dir_missing 回执的时长：对端要先把本轮全部成员落盘、算校验和才核对 */
    internal val DIR_MISSING_WAIT_MS = 60_000L

    /** 等对端「已有成员清单」（dir_have）的时长：局域网同一事件循环内就回，1s 足够兜老端 */
    internal val DIR_HAVE_WAIT_MS = 1_000L

    /** dir_meta 成员清单在线上最多多少字符（超了就不带，退化为「只按数量核对」，避免撑爆 8MB 帧上限） */
    internal val DIR_LIST_MAX_CHARS = 2 * 1024 * 1024




    /** 发送端在途核对表（普通单文件续传才登记） */
    internal val verifyMap = ConcurrentHashMap<String, VerifyState>()

    /** 核对超时：整文件按一个验证单元计，单块超时 10s；超时只记日志不改传输结果 */
    internal val verifyTimeoutMs = 10_000L


    /** 取消正在发送/待发送的文件或文件夹(两端同步):本端移除并删待发/历史,发file_cancel让对端中止;
     *  发送循环下轮读到cancelled自行中断(不再发file_done);同时记墓碑,防止队列里排着的
     *  「待发补发」任务用同一个 mid 重新开传(否则取消完对端又冒出一个气泡继续收) */
    fun cancelFile(mid: String) {
        if (mid.isEmpty()) return
        io {
            cancelled.add(mid)
            markCanceled(mid)
            dirHaveBox.remove(mid)
            clearMemberAcks(mid)
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
     *  sendOrDrop会把该会话判失效⇒"点击立即断线");返回"有没有可用会话",写出尽力而为。
     *  ip=帧内主叫自己的地址(对端用它回 accept/end);peerIp=被叫地址,只用于找会话,两者语义不可混用 */
    fun sendCallInvite(
        name: String, ip: String, peerIp: String, vid: Boolean = false, lv: Int = 1, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ): Boolean {
        // P3-8：按**被叫 ip**精确找会话（与 sendCallAccept/sendCallMedia 同口径）——
        // 用 target() 会把邀请发给「当前对话设备」；而拿帧内 callerIp（本机地址）找会话则恒 null，呼出必失败
        val s = sessionOf(peerIp) ?: return false
        io { sendOrDrop(s, Protocol.callInvite(name, ip, vid, lv, af, mport, mudp, msid), "call_invite") }
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

    /** 对单条会话重发 hello（配对自愈用：把按 deviceId 取到的权威 token 带过去，须在 io 线程调用） */
    private fun resendHello(s: Session, tok: String) {
        s.pairTokenSent = tok
        val j = Protocol.hello(
            myId, myName, Build.MODEL ?: "android",
            localVidCap, localCamCap, localVCodecs,
            lanes = Link.LANES, lane = s.lane,
            tok = tok
        )
        sendOrDrop(s, j, "hello", quiet = true)
    }

    /** 本端能力晚到(如解码器枚举在后台完成)时,把所有已握手会话的hello重发一次 */
    fun resendHello() {
        io {
            for (s in sessions) {
                if (!s.handshaked || s.closed) continue
                // 重发 hello 同样按 deviceId 优先取 token（能力更新时对端会重新校验，不能因为换 IP 就漏 token）
                val tok = prefs.pairedTokenFor(s.remoteIp)
                s.pairTokenSent = tok
                val j = Protocol.hello(
                    myId, myName, Build.MODEL ?: "android",
                    localVidCap, localCamCap, localVCodecs,
                    lanes = Link.LANES, lane = s.lane,
                    tok = tok
                )
                sendOrDrop(s, j, "hello", quiet = true)
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
        var sent = 0   // P3-7：只统计**真的写出去**的条数，别拿 mids.size 虚报（调用方都在工作线程，io{} 是同步执行）
        io {
            var i = 0
            while (i < mids.size) {
                val chunk = mids.subList(i, minOf(i + MSG_DEL_CHUNK, mids.size))
                if (sendOrDrop(s, Protocol.msgDel(chunk), "msg_del")) sent += chunk.size
                i += MSG_DEL_CHUNK
            }
            log("清空：已逐条通知对端删除 $sent/${mids.size} 条（文本/图片）")
        }
        return sent
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

        /**
         * 会话 socket 的收发缓冲（KB）。0 = 不显式设置，交给内核窗口自动调整（默认，P1-5 结论）。
         * A/B 时改成 1024 / 4096 即可切回固定缓冲，无需改别处。
         */
        internal const val SOCK_BUF_KB = 0

        /**
         * 并发数据通道数（含主连接）。
         * **已回到单连接 = 1**：真机实测单通道续传 1.1GB 约 8s（140MB/s），第二条 TCP 连接没有实测收益，
         * 却多一条独立断链路径（任一连接被 reset 即整趟失败）；带宽改由自适应帧大小去跑满（见 AdaptiveFrame）。
         * hello 用该值广播，老端据此不会再拨 lane（lanesFor 取 min）。lane 代码保留但不再拨号。
         */
        internal const val LANES = 1

        /** lane 拨号超时：与主连接一致，3s 足够局域网/热点 */
        internal const val LANE_CONNECT_TIMEOUT_MS = 3000

        /** 等 lane 握手（对端 hello 回来）的上限；超时按该 lane 不可用处理，降级不失败 */
        internal const val LANE_HANDSHAKE_WAIT_MS = 2500L

        /**
         * 等接收端「Rx 已建好」的 xe_ready 上限（毫秒）。局域网往返是毫秒级，
         * 2s 只用于兜住对端不支持/异常 → 本文件降级为主连接单通道发，不失败。
         */
        internal const val XE_READY_WAIT_MS = 2000L

        /**
         * 发送侧零拷贝开关（P1-4 A/B）。
         * Android 的 transferTo 走内核 sendfile，恒为真收益；Windows 的 JDK 可能内部回落成拷贝循环，
         * 若 PC 端实测反而更慢，把这个常量（和 PC 端同名字段）改成 false 即整体回到流式路径。
         */
        internal const val ZC_SEND = true

        /** 半成品过期时限：3 天没人续传的孤儿半成品（取消帧丢失/对端重传换 mid）启动时回收 */
        const val PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000

        // ===== P4-5 配对 =====
        /** 配对码位数（6 位；输入长度、核对、生成三处统一用它） */
        internal const val PAIR_CODE_LEN = 6
        /** 配对码/等待输入的有效期（2 分钟） */
        internal const val PAIR_TTL_MS = 120_000L
        internal const val PAIR_MAX_ATTEMPTS = 3
        internal val PAIR_RANDOM = SecureRandom()

        /** 未配对会话仍允许的帧（心跳 + 配对握手本身） */
        internal val PAIRED_ALLOWED_FRAMES = setOf(
            "hello", "ping", "pong", "busy",
            "pair_need", "pair_submit", "pair_ok", "pair_fail", "pair_cancel"
        )

        /** 用户点设备后等会话握手的最长时限：超过就不再补弹配对码（防几秒后的自动重连又弹出） */
        internal const val PAIR_TAP_INTENT_TTL_MS = 30_000L

        /** lane 数据通道允许的帧（分片与通道控制），其余内容帧一律不认 */
        internal val LANE_ALLOWED_FRAMES = setOf(
            "hello", "ping", "pong", "lane_ok", "xe_begin", "xe_end", "xe_ready", "busy"
        )

        /** 判「假连接（半开）」的收包空闲阈值：超过一轮心跳周期（5s）仍无收包才算 */
        internal const val STALE_RX_MS = 20_000L

        /** 接收/发送停滞判定阈值:30s无任何真实进度;对端进程活着但发送线程卡死时心跳帧保不了业务帧 */
        internal const val STALL_TIMEOUT_MS = 30_000L

        /** 对端 busy 记号的有效期：条目通常几秒内就会重试，120s 只为兜「条目还在队列但没被触发」的残留 */
        internal const val PEER_BUSY_TTL_MS = 120_000L

        /** 非文件帧（控制/音频/视频）单次写超时：一帧最大 128KB，15s 写不完只可能是对端不读（P1-3） */
        internal const val CONTROL_WRITE_TIMEOUT_MS = 15_000L

        /** 停滞看门狗扫描周期（= 心跳周期）：心跳线程每 5s 顺带扫一次，不新开线程 */
        internal const val STALL_SCAN_MS = 5_000L

        /** 票③（被拒）的存活时长：到期自动解除，不再「拒一次永久发不出」 */
        internal const val DENY_TTL_MS = 10L * 60 * 1000

        /** 取消墓碑保留上限（只防内存无限增长；一次会话里取消不了几千条） */
        internal const val CANCEL_TOMB_MAX = 256

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
        /** 票 ③：原本"被拒绝"（数据流量确认被拒时写；作用域=该设备，10 分钟或切到局域网后自动解除） */
        const val VOTE_CONSENT_DENIED = 2
    }
}
