package com.phonepctransfer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonepctransfer.call.addCallRecord
import com.phonepctransfer.call.pushClipIfChanged
import com.phonepctransfer.call.writeClipboard
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Peer
import com.phonepctransfer.data.PeerGroup
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.Store
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.data.TrustedPeers
import com.phonepctransfer.net.DataConsent
import com.phonepctransfer.net.DataConsentText
import com.phonepctransfer.net.Discovery
import com.phonepctransfer.net.Link
import com.phonepctransfer.net.PairUi
import com.phonepctransfer.net.TransferKeywords
import com.phonepctransfer.net.fastConnect
import com.phonepctransfer.util.BusyLock
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import com.phonepctransfer.util.LocalFs
import com.phonepctransfer.util.LocalIps
import com.phonepctransfer.util.SharedItem
import com.phonepctransfer.util.SharedPayload
import com.phonepctransfer.voice.CallManager
import com.phonepctransfer.video.VideoLevel
import java.io.File

/** 全局单例：组件装配与 UI 状态中枢；剪贴板读取仅焦点切换触发，接收为纯被动 */
object AppCore {

    internal const val TAG = "PP-AppCore"
    internal const val MAX_UI_MSGS = 800
    internal const val LAN_TICK_MS = 5000L

    /** 主动扫描收集窗口 */
    internal const val SCAN_WINDOW_MS = 3000L
    /** 扫描后逐台主动连接的单台预算（每台只试一次：握手成功即放行，超时视为不可达/非本应用） */
    internal const val SCAN_DIAL_BUDGET_MS = 1500L

    /** 分享内容转存目录 */
    internal const val INBOX_DIR = "share_inbox"

    /** 转存内容保留时长 */
    internal const val INBOX_TTL_MS = 3L * 24 * 3600_000

    /** 大文件提速提示的体积门槛（2GiB） */
    internal const val BIG_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024

    /** 提速提示文案 */
    internal const val BIG_TRANSFER_HINT =
        "如果双方都使用WiFi而不是一方使用了有线，开启热点的方式速度通常会快几倍"

    /** 分享选定设备后等链路建立的时长 */
    internal const val SHARE_CONNECT_WAIT_MS = 2500L

    /** 数据网络提醒确认框最长等待 */
    internal const val DATA_CONSENT_WAIT_SEC = 120L

    internal lateinit var app: Context

    lateinit var prefs: Prefs
        internal set
    lateinit var store: Store
        internal set

    /** 后台可连接白名单 */
    lateinit var trusted: TrustedPeers
        internal set

    lateinit var clip: Clip
        internal set
    lateinit var gate: LanGate
        internal set
    lateinit var link: Link
        internal set
    lateinit var call: CallManager
        internal set
    internal var discovery: Discovery? = null

    internal val main = Handler(Looper.getMainLooper())
    internal var initialized = false

    // ==================== UI 可见状态 ====================

    /** 聊天区当前消息（只属于选中设备） */
    val messages = mutableStateListOf<MsgItem>()

    /** 设备原始列表（每 IP 一行） */
    val peers = mutableStateListOf<Peer>()

    /** 设备组视图（同设备多地址一行） */
    val peerGroups = mutableStateListOf<PeerGroup>()

    /** 当前对话设备 IP */
    var selectedPeerIp by mutableStateOf<String?>(null)
        internal set

    /** 已连接设备 IP 集合 */
    var connectedPeers by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** 对端能力/会话变化信号（自增） */
    var peerCapsTick by mutableIntStateOf(0)
        internal set

    var linkState by mutableStateOf(Link.State.STOPPED)
        internal set
    /** P4-5：需要显示配对覆盖层时非空（显示 6 位码 / 数字键盘输入） */
    var pairUi by mutableStateOf<PairUi?>(null)
        internal set
    var peerLabel by mutableStateOf("")
        internal set
    var lanInfo by mutableStateOf(LanGate.Info(false, "NONE", "未初始化"))
        internal set
    /** 本机是否有 VPN 在运行 */
    var vpnActive by mutableStateOf(false)
        internal set
    var localIp by mutableStateOf("-")
        internal set

    /** 本机全部可用地址（数据网时追加蜂窝 IPv6 公网） */
    var localIps by mutableStateOf<List<String>>(emptyList())
        internal set
    var running by mutableStateOf(false)
        internal set
    var pendingCount by mutableStateOf(0)
        internal set

    /**
     * 「真的还在等」的待发条数 = 待发台账条数 − 正在传的文件条数。
     *
     * 待发台账是防强杀用的：doSendFile 开传**之前**就把自己写进去了，所以待发条数在正常传输时
     * 也是 >0。界面若直接拿 pendingCount 提示「待发送 N 条，连接后自动补发」，就会把「正在发」
     * 误报成「未连接」（用户口径里的"取消后还是显示未连接"就是这个）。
     */
    val waitingCount: Int
        get() = (pendingCount - messages.count {
            it.kind == Kind.FILE && it.status == Status.SENDING
        }).coerceAtLeast(0)
    var lastLog by mutableStateOf("")
        internal set
    var inputText by mutableStateOf("")

    /** 清空聊天记录的待确认计划 */
    var clearConfirm by mutableStateOf<ClearPlan?>(null)
        internal set

    /** 清空后未能通知对端的提示 */
    var clearWarn by mutableStateOf<String?>(null)
        internal set

    // ==================== 语音通话 UI 状态 ====================

    /** 当前通话状态（供界面显示「正在通话」条与弹窗） */
    var callState by mutableStateOf(CallManager.State.IDLE)
        internal set

    /** P2-8：前台服务类型重申报入口（TransferService 创建时注入，销毁时清空）；通话起止各调一次 */
    @Volatile
    internal var fgsTypeRefresher: (() -> Unit)? = null

    /** 当前通话对端 IP（空 = 无通话） */
    var callPeerIp by mutableStateOf("")
        internal set

    /** 当前通话对端显示名 */
    var callPeerName by mutableStateOf("")
        internal set

    /** 对端 video_cfg 到达计数 */
    var peerCamTick by mutableIntStateOf(0)
        internal set

    /** 本机切摄像头计数 */
    var selfCamTick by mutableIntStateOf(0)
        internal set

    /** 收到呼入请求（待用户同意/拒绝）；null = 无呼入。第三项 = 是否视频来电 */
    var incomingCall by mutableStateOf<Triple<String, String, Boolean>?>(null)
        internal set

    // ==================== 视频通话 UI 状态 ====================

    /** 视频是否已激活（用于切到视频全屏界面） */
    var videoActive by mutableStateOf(false)
        internal set

    /** 视频界面状态文案（档位 / 帧率 / 对端状态） */
    fun videoSummary(): String = runCatching { call.video.summary() }.getOrDefault("")

    /** 是否静音 */
    var videoMuted by mutableStateOf(false)
        internal set

    /** 通话是否免提输出（默认开） */
    var callSpeakerOn by mutableStateOf(true)
        internal set

    /** 通话音频通道：true=媒体（默认），false=电话 */
    var callMediaChannel by mutableStateOf(true)
        internal set

    /** 对端是否回传画面（false → 显示「对方仅接收视频」占位） */
    var peerSendsVideo by mutableStateOf(false)
        internal set

    /** 最近一次局域网扫描的结果文案 */
    var scanMessage by mutableStateOf("")
        internal set

    /** 是否正在扫描（UI 用它禁用「扫描」按钮） */
    var scanning by mutableStateOf(false)
        internal set

    // ==================== 传输速率 ====================

    /** 传输速率快照（mid→字节/秒） */
    private val speeds = mutableStateMapOf<String, Long>()

    /** `mid` → 速率样本的时刻（判断「还在传吗」，见 [SPEED_FRESH_MS]） */
    private val speedsAt = mutableStateMapOf<String, Long>()

    /** 速率样本有效期，过期视为未传输 */
    private const val SPEED_FRESH_MS = 1500L

    /** 气泡用：mid 当前速率（字节/秒） */
    fun speedBpsOf(mid: String, now: Long = System.currentTimeMillis()): Long {
        val bps = speeds[mid] ?: return 0L
        val at = speedsAt[mid] ?: return 0L
        return if (now - at <= SPEED_FRESH_MS) bps else 0L
    }

    /** 这条已经结束（或状态回退）→ 清掉速率，别让气泡拿着旧数字 */
    private fun clearSpeed(mid: String) {
        speeds.remove(mid)
        speedsAt.remove(mid)
    }

    // ==================== 大文件提速提示 ====================

    /** 大文件提速提示文案（弹一次，5s 自消） */
    var bigTransferHint by mutableStateOf<String?>(null)
        internal set

    /** 提示重显计数器 */
    var bigTransferHintTick by mutableLongStateOf(0L)
        internal set

    /** 提示关掉（界面 5s 倒计时到点后调） */
    fun dismissBigTransferHint() {
        bigTransferHint = null
    }

    /** 已经为哪条消息提示过（同一条的合并/进度帧不再重复弹） */
    private var bigHintMid = ""

    /** 剪贴板同步模式是否开启 */
    var clipSyncOn by mutableStateOf(true)
        internal set

    /** 应用可见（小窗/分屏时也 true） */
    @Volatile
    var appVisible = false
        internal set

    /** 应用持有窗口焦点（Android 10+ 读剪贴板的前提） */
    @Volatile
    var foregroundActive = false
        internal set

    // ==================== 初始化 ====================

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = ctx.applicationContext
            BusyLock.init(app)
            prefs = Prefs(app)
            clipSyncOn = prefs.clipSyncMode
            // 旧全局旋转角一次性播种给上次选中设备
            runCatching {
                val seedKey = prefs.convKeyOf(prefs.lastSelectedIp)
                if (seedKey.isNotEmpty()) {
                    if (prefs.rotRemoteOf(seedKey) < 0 && prefs.videoRemoteRot >= 0) {
                        prefs.setRotRemoteOf(seedKey, prefs.videoRemoteRot)
                    }
                    if (prefs.rotLocalOf(seedKey) < 0 && prefs.videoLocalRot >= 0) {
                        prefs.setRotLocalOf(seedKey, prefs.videoLocalRot)
                    }
                }
            }
            videoRemoteRot = prefs.rotRemoteOf(prefs.convKeyOf(prefs.lastSelectedIp))
            videoLocalRot = prefs.rotLocalOf(prefs.convKeyOf(prefs.lastSelectedIp))
            // 首次读取迁移旧版设备列表
            reloadPeers()
            // 启动清理陈旧设备地址
            runCatching {
                val n = prefs.pruneStaleIps()
                if (n > 0) {
                    reloadPeers()
                    appendLog("已清理 $n 个 5 天未使用的设备地址")
                }
            }
            store = Store(app)
            // 历史/待发按设备 id 归并
            store.keyResolver = { ip -> prefs.convKeyOf(ip) }
            // 启动恢复上次会话（选中+载入历史，不连接）
            runCatching {
                val lastSel = prefs.lastSelectedIp
                if (lastSel.isNotBlank()) {
                    val key = prefs.convKeyOf(lastSel)
                    val g = if (key.isEmpty()) null
                    else prefs.groups().firstOrNull { it.key == key }
                    if (g != null && g.curIp.isNotBlank()) {
                        selectedPeerIp = g.curIp
                        restoredPeerIp = g.curIp
                        reloadConversation(g.curIp)
                        appendLog("启动恢复上次会话：${g.displayName}（${g.curIp}）")
                    }
                }
            }
            // 同步内置文件浏览器 root 开关
            LocalFs.rootEnabled = prefs.rootFs
            // 待发队列变化时重算横幅/通知（主线程）
            store.onPendingChanged = { ui { pendingCount = store.loadPending().size } }
            trusted = TrustedPeers(app)
            // 启动补种：本机已配对过的设备 = 用户已授权 → 直接进「已知设备」名单。
            // 否则老版本升级上来、或「配对完还没发消息」的设备，后台重连会被自己的白名单挡掉
            runCatching {
                val pairedIds = prefs.pairedDeviceIds()
                for (id in pairedIds) {
                    trusted.markPaired(id, prefs.pairedNameOf(id), id)
                }
                if (pairedIds.isNotEmpty()) {
                    appendLog("已知设备：补入 ${pairedIds.size} 台已配对设备，当前后台可连接 ${trusted.count()} 台")
                }
            }
            clip = Clip(app)
            gate = LanGate(app)
            link = Link(app, prefs)
            link.store = store
            link.trusted = trusted
            // 视频能力（握手里广播，须在 link.start() 前设置）
            // 本端能否收视频（关开关即不参与视频）
            link.localVidCap = prefs.videoEnabled
            // 本机有无摄像头（查系统特性，无需相机权限）
            link.localCamCap = runCatching {
                app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
            }.getOrDefault(false)
            // 本端可解码编码列表（pp-load 线程算后重发 hello）
            link.localVCodecs = emptyList()
            call = CallManager(link, app)
            call.maxVideoLevelIdx = prefs.videoMaxLevel
            // 出向闸门：后台只放行已知设备
            // 判定键用**设备组键（deviceId）**而不是裸 IP：同设备换地址 / DHCP 变了照样认得，
            // 不然配对成功后 IP 一变就被当成陌生设备踢掉
            link.outgoingGate = { ip ->
                if (appVisible) {
                    false
                } else if (trusted.isTrusted(trustKeyOf(ip))) {
                    appendLog("后台放行连接：$ip 是已知设备（${trusted.describe(trustKeyOf(ip))}）")
                    false
                } else {
                    true
                }
            }
            // 入向闸门：后台不接受陌生设备（同口径：配对过或有往来才放行）
            link.incomingGate = { ip -> !appVisible && !trusted.isTrusted(trustKeyOf(ip)) }
            // 传输/通话中保活不转纯被动
            link.keepAliveBusy = { call.isBusy() || link.hasAnyActiveTransfer() }
            // 数据流量确认：按本机是否运营商数据网
            link.localOnCellular = { gate.onCellular(prefs.forceLanEnabled) }
            link.localDataConsent = { peerIp, label -> askLocalDataConsent(peerIp, label) }
            // 同设备多地址连上时跟随切换（主线程）
            link.onGroupFallback = { requested, actual ->
                ui {
                    if (selectedPeerIp == requested || selectedPeerIp.isNullOrEmpty()) {
                        selectedPeerIp = actual
                        prefs.lastSelectedIp = actual
                        prefs.setGroupCurIp(prefs.convKeyOf(actual), actual)
                        refreshGroups()
                        appendLog("${peerLabelOf(requested)} 改由 $actual 连通（同一台设备的另一个地址）")
                    }
                }
            }
            // 速率回调（主线程）
            link.onSpeed = { mid, bps ->
                ui {
                    speeds[mid] = bps
                    speedsAt[mid] = System.currentTimeMillis()
                }
            }
            // 发送前校验失败等必须让用户看见的提示（toast 内部已切主线程）
            link.notice = { msg -> toast(msg) }
            link.listener = object : Link.Listener {
                override fun onState(state: Link.State, peer: String) {
                    ui {
                        linkState = state
                        peerLabel = peer
                        connectedPeers = link.connectedIps
                        // 会话变化 → 能力控件重新求值
                        peerCapsTick++
                        // 界面未选中时跟随链路选中第一台
                        val t = link.selectedIp
                        // 健康会话按设备判（非精确 IP）
                        if (t.isNotEmpty() && t != selectedPeerIp &&
                            (selectedPeerIp.isNullOrEmpty() || !link.isDeviceConnected(selectedPeerIp!!))
                        ) {
                            // 例外：启动恢复的「上次会话」还没连上时不许抢（见 restoredPeerIp）
                            val restoring = restoredPeerIp.isNotEmpty() &&
                                restoredPeerIp == selectedPeerIp &&
                                !link.isDeviceConnected(restoredPeerIp)
                            if (!restoring) {
                                selectedPeerIp = t
                                reloadConversation(t)
                            }
                        }
                        // 刚连上只冲刷待发内容；剪贴板不拉不推（读取只由焦点切换触发，接收纯被动）
                        if (state == Link.State.CONNECTED) {
                            link.flushPending()
                        }
                    }
                }

                /** 会话集合变化：刷新连接点与能力信号 */
                override fun onPeersChanged() = ui {
                    connectedPeers = link.connectedIps
                    peerCapsTick++
                    // 握手可能新增设备绑定，重建设备/分组视图
                    reloadPeers()
                    // 链路掉线兜底触发通话结束，防状态卡死
                    val cp = call.peerIp
                    if (cp.isNotEmpty() && !link.isConnectedIp(cp)) call.onPeerDisconnected(cp)
                }

                /** P4-5：配对覆盖层（显示 8 位码 / 数字键盘输入）；null = 关掉 */
                override fun onPairState(uiState: PairUi?) = ui {
                    pairUi = uiState
                }
                override fun onMessageAdded(m: MsgItem) = ui { addMessage(m) }
                /**
                 * hsync 成批补齐：链路侧**已经在帧线程上整批落盘**，这里只做界面合并。
                 * 以前是逐条回调 addMessage，在主线程上每条都写一次历史文件、还和帧线程抢 Store 锁，
                 * 配对成功后一次补几百条 ⇒ 界面几秒~几十秒无响应（用户看到的「输入配对码后卡死」）。
                 */
                override fun onMessagesAdded(list: List<MsgItem>) = ui {
                    if (list.isEmpty()) return@ui
                    // mid → 下标一次建好：逐条 indexOfFirst 在几百条一起补入时是 O(n²)，主线程会明显迟钝
                    val idxByMid = HashMap<String, Int>(messages.size)
                    messages.forEachIndexed { i, x -> if (x.mid.isNotEmpty()) idxByMid[x.mid] = i }
                    for (m in list) {
                        if (m.mid.isNotEmpty() && m.peerIp.isNotEmpty()) midPeer[m.mid] = m.peerIp
                        maybeHintBigTransfer(m)
                        // 本机明确删过的 mid 不许被补回来（墓碑已在链路侧过滤，这里再兜一道）
                        if (removedMids.containsKey(m.mid)) continue
                        if (!belongsToCurrent(m)) continue
                        val idx = idxByMid[m.mid] ?: -1
                        if (idx >= 0 && idx < messages.size) {
                            messages[idx] = merge(messages[idx], m)
                        } else {
                            messages.add(m)
                            if (m.mid.isNotEmpty()) idxByMid[m.mid] = messages.size - 1
                        }
                    }
                    while (messages.size > MAX_UI_MSGS) messages.removeAt(0)
                    pendingCount = store.loadPending().size
                }
                override fun onMessageUpdated(m: MsgItem) = ui { updateMessage(m) }
                override fun onMessageRemoved(mid: String) = ui { removeMessage(mid) }

                override fun onHsyncApplied(ip: String, count: Int) = ui {
                    // 补入旧消息按 ts 重排（组键比较，可能另一地址）
                    val cur = selectedPeerIp
                    if (cur != null && prefs.convKeyOf(ip) == prefs.convKeyOf(cur) && count > 0) {
                        val sorted = messages.sortedBy { it.ts }
                        messages.clear()
                        messages.addAll(sorted)
                    }
                    appendLog("历史同步：已从对端补齐 $count 条")
                }
                override fun onClipToClipboard(text: String) = ui { writeClipboard(text) }

                /** 遗留回调（不再主动拉取），恒不发生 */
                override fun onPeerClipEmpty() {}

                override fun shouldSkipPeerClip(sha1: String): Boolean =
                    sha1 == clip.lastPushedSha1 || sha1 == clip.lastPulledSha1

                override fun onLog(s: String) = ui { lastLog = s }

                // ==================== 语音通话回调转发 ====================
                override fun onCallInvite(peerIp: String, name: String) {
                    ui {
                        call.onIncoming(peerIp, name)
                    }
                }
                override fun onCallAccept(peerIp: String) = ui { call.onAccept(peerIp) }

                // ==================== 视频协商回调 ====================
                // af=对端期望语音帧长(20=低延迟;0=老版本未声明);mport/mudp/msid=媒体三元组(未声明⇒只能带内档)
                override fun onCallInviteVideo(
                    peerIp: String, name: String, vid: Boolean, lv: Int, af: Int,
                    mport: Int, mudp: Boolean, msid: String
                ) {
                    ui { call.onIncoming(peerIp, name, vid, lv, af, mport, mudp, msid) }
                }
                override fun onCallAcceptVideo(
                    peerIp: String, vid: Boolean, lv: Int, af: Int,
                    mport: Int, mudp: Boolean, msid: String
                ) =
                    ui { call.onAccept(peerIp, vid, lv, af, mport, mudp, msid) }

                // 振铃期逐档媒体协商:只是三个字段覆盖+一次标志写入,不阻塞⇒同其余通话控制帧的ui{}主线程口径
                override fun onCallMedia(
                    peerIp: String, mport: Int, mudp: Boolean, msid: String, mtier: Int
                ) =
                    ui { call.onCallMedia(peerIp, mport, mudp, msid, mtier) }

                // 视频帧/反馈：**不经 ui{}**，链路层保证是轻量入队（解码在独立线程）
                override fun onVideoFrame(
                    peerIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int
                ) {
                    call.onVideoFrame(peerIp, seq, ts, flag, data, len)
                }
                override fun onVideoCfg(
                    peerIp: String, codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int,
                    front: Boolean
                ) {
                    call.onVideoCfg(peerIp, codec, w, h, fps, br, rot, front)
                    // 读线程回调：cfg（rot/cam）有货就通知 UI 重选旋转槽位（ui{} 投主线程）
                    ui { peerCamTick++ }
                }

                override fun onVideoFb(
                    peerIp: String, fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int
                ) = call.onVideoFb(peerIp, fps, drop, lat, dropping, kbps)

                override fun onVideoReq(peerIp: String, on: Boolean, key: Boolean) =
                    call.onVideoReq(peerIp, on, key)

                override fun onCallReject(peerIp: String) = ui { call.onReject(peerIp) }
                override fun onCallBusy(peerIp: String) = ui { call.onBusy(peerIp) }
                override fun onCallEnd(peerIp: String, reason: String) = ui { call.onRemoteEnd(peerIp, reason) }
                override fun onCallCancel(peerIp: String) = ui { call.onCancel(peerIp) }
                override fun onCallAudio(
                    peerIp: String, seq: Int, ts: Long, data: ByteArray, len: Int
                ) {
                    call.onAudio(peerIp, seq, ts, data, len)
                }

                // ==================== 数据流量确认 ====================
                override fun onDataAsk(
                    peerIp: String, mid: String, kind: String, name: String, size: Long
                ) {
                    ui { onDataAskInternal(peerIp, mid, kind, name, size) }
                }

                /** 重试同意后把那条文件消息挪到时间线最末（用户要求「移动到最新一条」） */
                override fun onMessageMoved(mid: String) = ui { moveMessageToLatest(mid) }
            }

            // 通话状态机装配：把状态变化/呼入/结束映射到 UI 状态
            call.setListener(object : CallManager.Listener {
                override fun onStateChanged(state: CallManager.State, peerIp: String, peerName: String) {
                    // P2-8：通话状态一变就重申报 FGS 类型（TALKING 起加 microphone、结束时去掉）
                    runCatching { fgsTypeRefresher?.invoke() }
                    ui {
                        callState = state
                        callPeerIp = peerIp
                        callPeerName = peerName
                        // 仅 INCOMING 态保留来电弹窗，离开即清空（防弹窗盖屏/按钮失灵）
                        if (state != CallManager.State.INCOMING) {
                            incomingCall = null
                            // 呼入已有结果⇒撤掉锁屏来电通知,否则带全屏意图的通知一直挂在锁屏上
                            CallNotif.cancel(app)
                        }
                        val talking = state == CallManager.State.TALKING
                        // 对端设备的旋转记忆：进视频界面前装载好，否则沿用上一个设备的角
                        if (talking) {
                            val rk = prefs.convKeyOf(peerIp)
                            videoRemoteRot = prefs.rotRemoteOf(rk)
                            videoLocalRot = prefs.rotLocalOf(rk)
                        }
                        videoActive = talking && call.videoMode
                        videoMuted = talking && call.isMuted()
                        callSpeakerOn = call.speakerOn
                        callMediaChannel = call.isMediaChannel
                        // 对端有摄像头才会回传画面；PC 端 cam=false → 界面显示占位文案
                        peerSendsVideo = talking && link.peerCapsOf(peerIp)?.cam == true
                        if (!talking) videoMuted = false
                    }
                }
                override fun onIncomingCall(peerIp: String, peerName: String, vid: Boolean) {
                    // 三元组第三位 = 是否视频来电（弹窗标题 + 是否申请相机权限）
                    ui { incomingCall = Triple(peerIp, peerName, vid) }
                    // 锁屏接听：HIGH 重要性+全屏意图通知，锁屏上可接听/挂断，非锁屏退化为横幅
                    CallNotif.show(app, peerIp, peerName, vid)
                }
                override fun onMediaNotice(text: String) {
                    // 媒体提示（看门狗线程→main）
                    ui {
                        lastLog = text
                        toast(text)
                    }
                }
                override fun onCallEnded(
                    peerIp: String, reason: String, video: Boolean, durationMs: Long, outgoing: Boolean
                ) {
                    ui {
                        incomingCall = null
                        // 来电通知（锁屏接听用）随之撤掉，避免通话结束后还留一条「来电」在锁屏上
                        CallNotif.cancel(app)
                        lastLog = "通话结束：$reason"
                        // 只写 lastLog 用户根本注意不到（表现为「接听后什么反应都没有」）——必须 toast
                        toast("通话结束：$reason")
                        // 通话以消息留痕（时长/拒绝原因）
                        addCallRecord(peerIp, video, durationMs, reason, outgoing)
                    }
                }
            })

            // 启动即恢复:聊天区按需在选定设备后载入(见reloadConversation);剪贴板同步不是发消息,不保留痕迹
            Thread({
                // 冷启动恢复剪贴板去重状态，避免把上次已同步过的内容再回推一次
                clip.lastPushedSha1 = prefs.lastPushedSha1
                clip.lastPulledSha1 = prefs.lastPulledSha1
                ui { pendingCount = store.loadPending().size }
                // 视频能力：MediaCodecList 枚举放后台线程算，算完重发 hello 让对端看到
                runCatching {
                    val vc = com.phonepctransfer.video.VideoCodecs.decodable
                    if (vc != link.localVCodecs) {
                        link.localVCodecs = vc
                        link.resendHello()
                    }
                }
                refreshLan()
            }, "pp-load").apply { isDaemon = true }.start()

            main.post(lanTick)
            cleanInbox()
            initialized = true
        }
    }

    internal inline fun ui(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post { block() }
    }

    // ==================== 门禁与运行控制 ====================

    fun refreshLan() {
        val info = gate.check(prefs.forceLanEnabled)
        lanInfo = info
        // VPN 每 5s 对一次：开启/关闭都写进应用内日志，界面横幅跟着变
        val vpn = gate.vpnActive()
        if (vpn != vpnActive) {
            vpnActive = vpn
            appendLog(if (vpn) "检测到 VPN 已开启：局域网连接/传输可能失败" else "VPN 已关闭，局域网连接恢复")
        }
        localIp = gate.localIpv4()
        // 设备列表"本机ip"列表:网卡可能随WiFi开关变化,每轮对一次;本机在数据网时照样列地址(只取蜂窝IPv6公网)
        val ips = LocalIps.list(includeCellular = onCellular())
        if (ips != localIps) {
            localIps = ips
            appendLog("本机地址列表更新（${ips.size} 个）：${ips.joinToString(" | ").ifEmpty { "无" }}")
        }
        // 常驻运行不检测WiFi；数据网发起语音/视频/传文件时弹流量提醒（每次使用都提醒，双向）
        if (!running) startRun()
    }

    internal fun startRun() {
        if (running) return
        running = true
        link.start()
        val d = Discovery(
            context = app,
            deviceId = prefs.deviceId,
            deviceName = prefs.deviceName,
            tcpPort = prefs.tcpPort,
            udpPort = prefs.udpPort
        ) { ip, name, id, _ ->
            // 自动发现：既有设备刷新发现信息，新设备直接入列表（默认备注=对方广播名）
            if (onPeerDiscovered(ip, name, id)) {
                ui { lastLog = "发现设备 ${name.ifEmpty { ip }} @ $ip，已加入设备列表" }
            }
        }
        d.start()
        discovery = d
        appendLog("传输已启动（${lanInfo.kind}）")
        // 启动顺序（用户指定）：先直连「已有设备」（预算 1s），连上就不再扫；连不上才扫局域网（见 bootConnectThenScan）
        bootConnectThenScan()
    }

    fun stopRun() {
        if (!running) return
        running = false
        discovery?.stop()
        discovery = null
        link.stop()
        scanning = false
        scanMessage = ""
        appendLog("传输已停止（${lanInfo.detail}）")
    }

    /** 周期巡检：刷新门禁/VPN/本机地址状态 */
    internal val lanTick = object : Runnable {
        override fun run() {
            if (initialized) refreshLan()
            main.postDelayed(this, LAN_TICK_MS)
        }
    }

    // ==================== 启动直连（先连已有设备，再扫局域网） ====================

    /** 启动直连预算：1s 内 TCP+握手都没成，就当「已有设备」暂时不可达，转局域网扫描（用户指定 1s） */
    internal const val BOOT_CONNECT_TIMEOUT_MS = 1000L

    /** 启动直连流程是否在跑（@Volatile：startRun 可能在 pp-load 线程、回前台在 main，两处都可能触发） */
    @Volatile
    private var bootFlowRunning = false

    /**
     * 启动链路顺序（用户要求）：**先直连「已有设备」**（上次选中的那台，预算 [BOOT_CONNECT_TIMEOUT_MS]）——
     * 1s 内握手成功就直接恢复会话、不扫局域网；超时/失败才走原来的「主动扫一轮 + 扫描末尾自动连接」。
     * 只试一次、不重试：设备换了 IP 的兜底仍由扫描后的 [autoConnectLast] 负责。
     * 冷启动与回前台自愈都走这里（两处可能并发触发，bootFlowRunning 挡住第二次）。
     */
    internal fun bootConnectThenScan() {
        if (bootFlowRunning || scanning) return
        val target = bootConnectTarget()
        if (target.isEmpty()) {
            appendLog("启动直连：没有「已有设备」记录，直接扫局域网")
            startScan()
            return
        }
        // 链路没起来 / 没有可用局域网（数据网络、非私网段）：拨号注定失败，交给 startScan 走它自己的跳过分支并给出原因
        if (!running || !lanInfo.available || Discovery.subnetPrefixOf(localIp) == null) {
            startScan()
            return
        }
        if (link.isDeviceConnected(target)) {
            // 服务重入等场景：已经连着就直接用，不重复拨号也不扫（反正扫出来也连不了第二遍）
            appendLog("启动直连：${peerLabelOf(target)}（$target）已是健康会话，跳过局域网扫描")
            ui {
                scanMessage = ""
                syncSelectionTo(target)
            }
            return
        }
        // 复用 scanning 挡住扫描重入（扫描文案区同时显示「正在连接…」）；全部在主线程改，避免与 startScan 抢标志位
        bootFlowRunning = true
        ui {
            scanning = true
            scanMessage = "正在连接 ${peerLabelOf(target)}…"
        }
        appendLog(
            "启动直连 ${peerLabelOf(target)}（$target），超时 ${BOOT_CONNECT_TIMEOUT_MS}ms，" +
                "失败再扫局域网"
        )
        Thread({
            val ok = runCatching { link.fastConnect(target, BOOT_CONNECT_TIMEOUT_MS) }.getOrDefault(false)
            bootFlowRunning = false
            if (ok) {
                appendLog("启动直连成功：${peerLabelOf(target)}（$target），跳过局域网扫描")
                ui {
                    scanning = false
                    scanMessage = ""
                    syncSelectionTo(target)
                }
            } else {
                appendLog("启动直连 ${target} 未在 ${BOOT_CONNECT_TIMEOUT_MS}ms 内完成，转局域网扫描")
                // scanning 先复位再扫描，两步都在主线程顺序执行，startScan 不会误判「已在扫描」
                ui {
                    scanning = false
                    scanMessage = ""
                    startScan()
                }
            }
        }, "pp-boot-connect").apply { isDaemon = true }.start()
    }

    /**
     * 启动直连的「已有设备」：优先上次选中的那台（[Prefs.lastSelectedIp]）；
     * 没有记录时列表里只有一台就用它，多台则不猜（交给扫描按 online 结果挑）。
     *
     * 设备身份是 deviceId+token，地址只是它当下的接入点：重启/换 WiFi 后 DHCP 换了 IP，
     * 记忆里的旧地址就是废的（拨它只会白等 1s）。同一台设备组里最近出现过哪个局域网地址就用哪个。
     */
    private fun bootConnectTarget(): String {
        val remembered = prefs.lastSelectedIp.trim()
        if (remembered.isNotEmpty()) {
            // 只在「记忆本身是局域网地址」时跟随迁移：手动添加的公网 IP 是用户明确配置的接入点，不抢
            if (!prefs.isLanIpv4(remembered)) return remembered
            val key = prefs.convKeyOf(remembered)
            val fresh = prefs.freshestLanIpOf(key)
            if (fresh.isNotEmpty() && fresh != remembered) {
                prefs.lastSelectedIp = fresh
                prefs.setGroupCurIp(key, fresh)
                appendLog("启动直连：${peerLabelOf(key)} 换了局域网地址 $remembered → $fresh（同一设备，身份不变）")
                return fresh
            }
            return remembered
        }
        val list = prefs.peers
        return if (list.size == 1) list.first().ip else ""
    }

    /** 直连成功后把界面/记忆对齐到这台设备（聊天记录按设备组键载入）；必须在主线程调用 */
    private fun syncSelectionTo(ip: String) {
        if (selectedPeerIp != ip) {
            selectedPeerIp = ip
            reloadConversation(ip)
        }
        // 用户/启动直连选定了这台 → 启动恢复保护到此结束
        restoredPeerIp = ""
        prefs.lastSelectedIp = ip
        prefs.setGroupCurIp(prefs.convKeyOf(ip), ip)
    }

    // ==================== 局域网扫描（启动即扫） ====================

    /** 主动扫描一轮局域网设备（启动/回前台自愈时调用，之后纯被动）：本机有局域网且地址在私网段才扫，窗口内广播+逐地址单播询问 */
    internal fun startScan() {
        val d = discovery
        if (!running || d == null) {
            ui { scanMessage = "传输未运行，无法扫描" }
            return
        }
        if (scanning) return
        // 闸门①:没WiFi/热点/以太网=本机在数据网络,没有"局域网"可扫
        if (!lanInfo.available) {
            ui { scanMessage = "WiFi 未连接，已跳过扫描" }
            appendLog("跳过局域网扫描：未连接 WiFi/热点/以太网（当前为数据网络）")
            return
        }
        // 闸门 ②：本机地址要落在私网段，才有本网段 /24 可扫
        val ip = localIp
        val prefix = Discovery.subnetPrefixOf(ip)
        if (prefix == null) {
            ui { scanMessage = "本机不在局域网私有网段（$ip），已跳过扫描" }
            appendLog("跳过局域网扫描：本机 $ip 不属于私网段（10/172.16-31/192.168）")
            return
        }
        scanning = true
        ui { scanMessage = "正在扫描 $prefix.1-254…" }
        appendLog("开始扫描局域网（本机 $ip，本网段 $prefix.1-254）")
        Thread({
            // 稍等一下再发：确保 Discovery 接收 socket 已 bind，避免首轮白扫
            runCatching { Thread.sleep(300) }
            val found = runCatching { d.scanOnce(SCAN_WINDOW_MS, ip, localIps) }.getOrDefault(emptySet())
            ui {
                scanning = false
                scanMessage = if (found.isEmpty()) "无新设备" else "发现 ${found.size} 台设备"
            }
            if (found.isEmpty()) {
                appendLog("扫描结束：无新设备")
            } else {
                appendLog("扫描结束：发现 ${found.size} 台设备（${found.joinToString(", ")}）")
            }
            // 扫描结束后：先把「上次选中的设备」接上（冷启动无需手动点设备即可恢复会话），
            // 再把扫到的其余地址挨个主动拨一次（每台只试一次、串行，互不顶掉）
            val primary = autoConnectLast(found)
            dialScannedOnce(found, primary)
        }, "pp-scan").apply { isDaemon = true }.start()
    }

    /**
     * 扫描到的每个地址都主动拨一次（串行、每台只试一次，不弹配对码）。
     *
     * 为什么不用 selectAndConnect 逐个调：[LinkConn] 的 pendingConnectIps 只有一个槽，
     * 后一次会把前一次的候选顶掉，实际效果是"只有最后一台被拨"。
     * [fastConnect] 是阻塞式单次尝试（共享预算、不占队列、不查后台闸门），正好当"拨一次"的原语。
     *
     * 结果口径：
     * - 之前配对过的：hello 带 token → 对端核对通过 → 直接放行，并把它的历史地址换成这次这条
     *   （替换动作在 [Link.onSessionReady] 里，验证通过之后才做）。
     * - 没连接过的：握手成功但停在「未配对」进列表，等用户点它才出示配对码。
     * - 不是本应用的地址（扫描 UDP 应答来源可能是别的程序）：握手失败，直接丢弃会话，不进列表。
     */
    private fun dialScannedOnce(found: Set<String>, primary: String) {
        val candidates = found.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return
        Thread({
            val own = localIps.toSet()
            var tried = 0
            var okCount = 0
            for (addr in candidates.sorted()) {
                if (!running) return@Thread
                val ip = addr.trim()
                if (ip.isEmpty() || ip in own || ip == primary) continue
                if (link.isConnectedIp(ip)) continue
                // 同一台设备已经在别的地址上连通了（换过 IP 的常见情形）：不必再拨，也不去扰它
                val key = runCatching { prefs.convKeyOf(ip) }.getOrDefault(ip)
                if (key.isNotEmpty() && key != ip && link.isPeerConnected(key)) {
                    appendLog("扫描后逐台连接：$ip 所属设备已有会话，跳过")
                    continue
                }
                tried++
                val ok = runCatching { link.fastConnect(ip, SCAN_DIAL_BUDGET_MS) }.getOrDefault(false)
                if (ok) {
                    okCount++
                    appendLog("扫描后逐台连接：$ip 握手成功")
                } else {
                    appendLog("扫描后逐台连接：$ip 未响应（不是本应用/不可达），跳过")
                }
            }
            if (tried > 0) appendLog("扫描后逐台连接结束：共试 $tried 台，握手成功 $okCount 台")
        }, "pp-scan-dial").apply { isDaemon = true }.start()
    }

    /** 启动扫描结束后自动连接一台设备（只试一次）：优先上次选中的，其次列表唯一的一台，否则不猜。
     *  返回选中并已拨号的目标地址（没有明确目标时为空串），供 [dialScannedOnce] 跳过重复拨号 */
    internal fun autoConnectLast(found: Set<String>): String {
        if (link.isConnected()) {
            appendLog("启动自动连接：已有会话，跳过")
            return ""
        }
        val remembered = prefs.lastSelectedIp
        val list = prefs.peers
        val online = found.filter { it.isNotEmpty() }
        // 设备身份按 deviceId 认：扫描到的地址即使和记忆里那条不同，只要落在同一设备组，就直接切过去连
        val rememberedKey = if (remembered.isNotEmpty()) prefs.convKeyOf(remembered) else ""
        val sameDevOnline = if (remembered.isNotEmpty() && prefs.isLanIpv4(remembered)
            && rememberedKey.isNotEmpty() && rememberedKey != remembered
        ) {
            online.firstOrNull { prefs.convKeyOf(it) == rememberedKey }
        } else null
        val ip = when {
            remembered.isNotEmpty() && remembered in online -> remembered
            sameDevOnline != null -> sameDevOnline
            remembered.isNotEmpty() && list.any { it.ip == remembered } -> remembered
            prefs.isLanIpv4(remembered) && rememberedKey.isNotEmpty() &&
                prefs.freshestLanIpOf(rememberedKey).isNotEmpty() ->
                prefs.freshestLanIpOf(rememberedKey)
            online.size == 1 -> online.first()
            list.size == 1 -> list.first().ip
            else -> ""
        }
        if (ip.isEmpty()) {
            appendLog(
                "启动自动连接：无明确目标，跳过" +
                    "（记录=${remembered.ifEmpty { "无" }}，扫描到 ${online.size} 台，列表 ${list.size} 台）"
            )
            return ""
        }
        if (link.isConnectedIp(ip)) {
            appendLog("$ip 已有会话，无需自动连接")
            return ip
        }
        ui { selectedPeerIp = ip }
        reloadConversation(ip)
        // 记住它：下次启动优先按「上次设备」走，不再依赖「列表里只有一台」这种巧合
        prefs.lastSelectedIp = ip
        link.selectAndConnect(ip, peerLabelOf(ip), fromUser = false)
        appendLog("启动自动连接 $ip（本轮扫描到 ${online.size} 台）")
        return ip
    }

    // ==================== 设备列表（左侧栏） ====================

    internal fun reloadPeers() {
        peers.clear()
        peers.addAll(prefs.peers)
        // 设备换地址（旧地址被删/链路已切到同设备的新地址）：当前对话跟上，
        // 否则聊天、待发队列会挂在一条已经不存在的地址上（组键不变，内容不用重载）
        val sel = selectedPeerIp
        // init() 里首刷设备列表时 link 还没 new（见 init 中 reloadPeers 早于 link = Link(...)），
        // 直接读 link.selectedIp 会抛「lateinit property link has not been initialized」= 启动必崩
        val linkSel = if (::link.isInitialized) link.selectedIp.trim() else ""
        if (!sel.isNullOrBlank() && linkSel.isNotEmpty() && sel != linkSel
            && peers.any { it.ip == linkSel } && !link.isDeviceConnected(sel)
        ) {
            selectedPeerIp = linkSel
            restoredPeerIp = ""
        }
        refreshGroups()
        reopenConversationIfKeyChanged()
    }

    /** 当前对话设备的组键快照(见reopenConversationIfKeyChanged) */
    internal var convKeySnapshot: String = ""

    /** 启动恢复的"上次会话"地址（空=没恢复过）：防止第一台握手设备抢走界面，用户主动点选后清空 */
    internal var restoredPeerIp: String = ""

    /** 设备身份被识别后若当前对话组键变化则重新加载聊天（否则历史/待发/旋转角会停在旧记录上） */
    internal fun reopenConversationIfKeyChanged() {
        val ip = selectedPeerIp
        if (ip.isNullOrBlank()) {
            convKeySnapshot = ""
            return
        }
        val k = prefs.convKeyOf(ip)
        if (k == convKeySnapshot) return
        val firstLoad = convKeySnapshot.isEmpty()
        convKeySnapshot = k
        if (firstLoad) return
        reloadConversation(ip)
        // 让用户知道「刚才那条地址并进了哪台设备」，否则只看到列表少了一行
        prefs.groupOf(k)?.let { toast("新地址已并入「${it.displayName}」") }
    }

    /** 重建设备组视图（一设备一行）。新增/删除/改备注/绑定身份后都要刷一次 */
    internal fun refreshGroups() {
        val src = prefs.groups { ip -> ip in connectedPeers }
        peerGroups.clear()
        // 兜底去重：行键必须唯一（LazyColumn 遇重复 key 会抛异常），保留先出现的行
        peerGroups.addAll(src.distinctBy { it.key })
    }

    /** 当前对话设备所属的组（未选中/找不到返回 null） */
    fun currentGroup(): PeerGroup? {
        val ip = selectedPeerIp ?: return null
        val k = prefs.convKeyOf(ip)
        return peerGroups.firstOrNull { it.key == k }
    }

    /** 该设备组是否在线（组内任意一个地址有健康会话即算在线；别名行看它自己的身份） */
    fun isGroupConnected(g: PeerGroup): Boolean =
        if (g.alt) link.isPeerConnected(g.key) else g.ips.any { it in connectedPeers }

    /** 切换当前设备组使用的地址（顶栏"地址"菜单）：同组另一地址已有健康会话时收发仍走那条 */
    fun switchGroupIp(ip: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        val k = prefs.convKeyOf(v)
        prefs.setGroupCurIp(k, v)
        selectedPeerIp = v
        // 用户主动选设备 → 启动恢复的保护到此结束
        restoredPeerIp = ""
        prefs.lastSelectedIp = v
        link.selectAndConnect(v, peerLabelOf(v))
        // 用户主动切到该设备的某个地址：未配对同样出示配对码
        runCatching { link.requestPair(v) }
        reloadConversation(v)
        refreshGroups()
        appendLog("切换设备地址：${peerLabelOf(v)} → $v")
    }

    /** UDP 发现一台设备：刷新既有条目或把新设备加入列表
     *  @return true=列表有变化(需要刷新界面) */
    internal fun onPeerDiscovered(ip: String, name: String, id: String): Boolean {
        // 广播里带的对端 id = 身份来源之一（另一处是 TCP 握手的 hello）→ 绑定到该地址
        if (id.isNotEmpty()) runCatching { prefs.bindDeviceIfFree(ip, id) }
        val changed = prefs.upsertPeer(
            ip, remark = null, lastId = id, lastName = name,
            seenAt = System.currentTimeMillis()
        )
        if (changed) {
            ui {
                reloadPeers()
                // 被动接收唤醒:不在扫描窗口内也要刷新状态文案,否则会停在"无新设备"与列表自相矛盾
                if (!scanning) scanMessage = "发现设备 ${name.ifEmpty { ip }}，已加入列表"
            }
        }
        // 同一台设备换了局域网地址（重启 / 换 WiFi 后 DHCP 换 IP）：身份（deviceId+token）不变，
        // 直接把「当前地址」切到新地址，下次连接就拨它，不会拿着旧地址白等、也更不会因此重新配对。
        // 只切记忆/组当前地址，不在发现回调里主动拨号（拨号仍由启动直连 / 扫描末尾的自动连接负责）
        val remembered = prefs.lastSelectedIp.trim()
        if (remembered.isNotEmpty() && remembered != ip && prefs.isLanIpv4(ip)
            && prefs.isLanIpv4(remembered) && !link.isDeviceConnected(remembered)
        ) {
            val key = prefs.convKeyOf(remembered)
            if (key.isNotEmpty() && key != remembered && prefs.convKeyOf(ip) == key) {
                prefs.setGroupCurIp(key, ip)
                prefs.lastSelectedIp = ip
                appendLog("设备换地址：${peerLabelOf(key)} $remembered → $ip（同一设备，身份不变）")
                ui {
                    if (selectedPeerIp == remembered) {
                        selectedPeerIp = ip
                        reloadConversation(ip)
                    }
                    refreshGroups()
                }
            }
        }
        return changed
    }

    /** 点击设备列表某一项 → 切为当前对话设备并按需连一次（连着的其他设备完全不受影响） */
    fun connectTo(p: Peer) {
        connectTo(p.ip, p.displayName)
    }

    // ==================== 同 IP 多设备：IP 冲突确认 ====================

    /** "同IP多设备"冲突待确认：地址已被另一台设备占用时挂起询问，确认后才断开旧连接并切换身份 */
    class ConflictSwitch(
        val ip: String,
        /** 正占着这个地址的那台设备（显示名） */
        val holder: String,
        /** 要切换过去的设备身份（= PeerGroup.key） */
        val key: String,
        val name: String
    )

    var conflictSwitch by mutableStateOf<ConflictSwitch?>(null)
        internal set

    /** 点击设备组：按该组当前地址连接；地址被占用时先询问是否断开旧连接（自动连接则直接跳过） */
    fun connectToGroup(g: PeerGroup) {
        val ip = g.curIp
        val holder = link.foreignPeerAt(ip, g.key)
        if (holder != null) {
            conflictSwitch = ConflictSwitch(ip, holder, g.key, g.displayName)
            return
        }
        if (g.alt) {
            // 该地址上没有别的设备连着 → 直接把身份切过来（别名行不切换身份是连不上的）
            prefs.promoteDev(ip, g.key)
            reloadPeers()
        }
        connectTo(ip, g.displayName)
    }

    /** 用户在 IP 冲突确认框里点了「断开并切换」 */
    fun confirmConflictSwitch() {
        val c = conflictSwitch ?: return
        conflictSwitch = null
        link.disconnectAt(c.ip, c.key)
        prefs.promoteDev(c.ip, c.key)
        reloadPeers()
        connectTo(c.ip, c.name)
        appendLog("IP 冲突：已断开「${c.holder}」的连接并切换到「${c.name}」（${c.ip}）")
    }

    /** 用户在 IP 冲突确认框里点了「取消」：保持现状（不连、不切） */
    fun cancelConflictSwitch() {
        conflictSwitch = null
    }

    /** 别名行长按"不再显示这台设备"：记入忽略表（成为活动设备时忽略标记自动解除） */
    fun forgetDevice(devKey: String) {
        val k = devKey.trim()
        if (k.isEmpty()) return
        prefs.forgetDev(k)
        reloadPeers()
        appendLog("已不再显示这台设备记录（$k）")
    }

    /** 是否本机地址（本机局域网地址 / 全部本机地址 / 环回）：这类地址不能当作"对方设备" */
    internal fun isSelfAddress(ip: String): Boolean {
        val v = ip.trim()
        if (v.isEmpty()) return false
        if (v == "localhost" || v == "::1" || v == "0.0.0.0") return true
        if (v.startsWith("127.")) return true
        if (v == localIp) return true
        return localIps.any { it.trim() == v }
    }

    /** 按地址连接（设备组切换地址时复用同一入口） */
    internal fun connectTo(ip: String, label: String?) {
        val v = ip.trim()
        if (v.isEmpty()) return
        // 自连闸门：本机地址/环回地址不是"对方设备"，拨过去只会连到自己（握手也会被 toLink 侧拒掉）
        if (isSelfAddress(v)) {
            appendLog("忽略：$v 是本机地址/环回地址，不能作为连接目标")
            return
        }
        selectedPeerIp = v
        // 用户主动点设备 → 启动恢复的保护到此结束
        restoredPeerIp = ""
        // 记住上次选中的设备：下次冷启动扫描完会自动尝试连它一次
        prefs.lastSelectedIp = v
        // 记住「这台设备当前用的地址」，顶栏地址菜单据此回显
        prefs.setGroupCurIp(prefs.convKeyOf(v), v)
        link.selectAndConnect(v, label ?: peerLabelOf(v))
        // 用户主动点设备 → 若这台还没配对就出示配对码（双端都不再随连接自动弹）
        runCatching { link.requestPair(v) }
        reloadConversation(v)
        refreshGroups()
        appendLog("当前对话设备：${label ?: peerLabelOf(v)}（$v）")
    }

    // ==================== P4-5 配对覆盖层动作 ====================

    /** 发起方在覆盖层数字键盘上输完 6 位码后提交 */
    fun submitPairCode(code: String) {
        val uiNow = pairUi ?: return
        if (uiNow.done || uiNow.showCode) return
        link.submitPairCode(uiNow.ip, code)
    }

    /** 覆盖层「取消」：只关弹窗、保留连接；下次点设备再重新出示配对码 */
    fun cancelPairing() {
        val uiNow = pairUi ?: return
        if (uiNow.done) {
            pairUi = null
            return
        }
        link.cancelPairing(uiNow.ip)
        // 兜底：链路侧找不到会话时也必须把覆盖层关掉（旧实现会在这里卡住）
        pairUi = null
    }


    /** 载入某台设备的聊天记录到聊天区：历史/待发均按设备组键（deviceId）过滤，同设备多地址共用同一份记录 */
    internal fun reloadConversation(peerIp: String?) {
        val ip = peerIp.orEmpty()
        val key = prefs.convKeyOf(ip)
        // 换设备 → 装回该设备记住的旋转角
        videoRemoteRot = prefs.rotRemoteOf(key)
        videoLocalRot = prefs.rotLocalOf(key)
        Thread({
            val his =
                if (ip.isEmpty()) emptyList()
                else store.loadHistory(ip, 500).filter { it.kind != Kind.CLIP }
            val pend =
                if (ip.isEmpty()) emptyList()
                else store.loadPending().filter {
                    it.peerIp.isEmpty() || prefs.convKeyOf(it.peerIp) == key
                }
            ui {
                messages.clear()
                messages.addAll(his)
                for (p in pend) {
                    if (messages.none { it.mid == p.mid }) messages.add(p)
                }
                // 待发项没有 ts 排序保证，统一按时间排一次
                val sorted = messages.sortedBy { it.ts }
                messages.clear()
                messages.addAll(sorted)
                if (messages.size > MAX_UI_MSGS) {
                    repeat(messages.size - MAX_UI_MSGS) { messages.removeAt(0) }
                }
                pendingCount = store.loadPending().size
            }
        }, "pp-load-conv").apply { isDaemon = true }.start()
    }

    /** 保存设备（新增或编辑）：备注是设备级的，同设备其它地址一起看到同一个备注 */
    fun savePeer(oldIp: String?, ip: String, remark: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        // 「添加设备」填的是本机地址/环回：存下来会绑出"自己连自己"的组，之后每次启动都自连且删不掉
        if (oldIp.isNullOrEmpty() && isSelfAddress(v)) {
            appendLog("忽略：$v 是本机地址/环回地址，不能作为设备地址添加")
            return
        }
        if (oldIp.isNullOrEmpty()) {
            prefs.upsertPeer(v, remark = remark)
        } else {
            prefs.updatePeer(oldIp, v, remark)
            prefs.setRemarkOf(v, remark)
            if (selectedPeerIp != null && selectedPeerIp == oldIp && oldIp != v) selectedPeerIp = v
        }
        reloadPeers()
        appendLog("已保存设备 $v")
    }

    // ==================== 设备与分组操作 ====================

    /** 对某台设备（组）设置备注 —— 仅本机可见，不上协议 */
    fun saveGroupRemark(key: String, remark: String) {
        prefs.setGroupRemark(key, remark)
        reloadPeers()
        appendLog("已保存备注：${remark.ifBlank { "（清空）" }}")
    }

    /** 手动给设备补一个地址：绑定 ip→设备id，从新地址连上时沿用该组的记录与备注 */
    fun addGroupIp(key: String, ip: String) {
        val v = ip.trim()
        if (v.isEmpty() || key.isBlank()) return
        prefs.upsertPeer(v)
        prefs.bindDevice(v, key)
        reloadPeers()
        appendLog("已为设备 ${peerLabelOf(v)} 添加地址 $v")
    }

    /** 从某台设备的地址列表里移除一个地址（只影响本机列表，不影响对端） */
    fun removeGroupIp(key: String, ip: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        prefs.removePeer(v)
        if (selectedPeerIp == v) {
            selectedPeerIp = null
            messages.clear()
        }
        reloadPeers()
        appendLog("已移除地址 $v")
    }

    /**
     * 删除设备前的会话清理：必须在解绑前执行，才能按 deviceId 找到同组会话和历史文件。
     * 在线时先发 msg_del 让对端同步删文本/图片；否则只删设备行，重连后 hsync 会把旧消息补回来。
     * 已落盘的实际文件不动。
     */
    private fun purgeConversationForDeviceDelete(ip: String): Int {
        val part = planConversation(ip).firstOrNull()
        val all = part?.all.orEmpty()
        val sync = part?.sync.orEmpty()
        val notified = if (sync.isEmpty()) 0 else link.deleteMessages(ip, sync)
        // P2-7：删整台设备同样落墓碑，防止重新发现后 hsync 又把旧消息补回来
        sync.forEach { store.addTomb(ip, it, sent = notified > 0) }
        if (all.isNotEmpty()) store.removePending(all)
        // planConversation 不枚举旧版 CLIP 历史；删整台设备时连文件一起清掉，避免留下孤儿记录。
        store.clearHistory(ip)
        for (mid in all) removedMids[mid] = true
        pendingCount = store.loadPending().size
        return notified
    }

    /** 删除整台设备（先清会话并通知在线对端，再删组内地址、绑定与备注记忆） */
    fun deleteGroup(g: PeerGroup) {
        val clearSelection = selectedPeerIp?.let { prefs.convKeyOf(it) == g.key } == true
        val clearRemembered = prefs.lastSelectedIp in g.ips
        val notified = purgeConversationForDeviceDelete(g.curIp)
        g.ips.forEach { prefs.removePeer(it) }
        prefs.setGroupRemark(g.key, "")
        if (clearSelection) {
            selectedPeerIp = null
            messages.clear()
        }
        if (clearRemembered) prefs.lastSelectedIp = ""
        reloadPeers()
        appendLog("已删除设备 ${g.displayName}（${g.ips.size} 个地址，通知对端删除 $notified 条文本/图片）")
    }

    /** 从设备列表删除一台设备（保留旧入口：按单个地址删） */
    fun deletePeer(p: Peer) {
        val notified = purgeConversationForDeviceDelete(p.ip)
        prefs.removePeer(p.ip)
        if (selectedPeerIp == p.ip) {
            // 只是把"当前对话"清空；已连着的会话交给 Link 自己判定（断了就是断了）
            selectedPeerIp = null
            messages.clear()
        }
        if (prefs.lastSelectedIp == p.ip) prefs.lastSelectedIp = ""
        reloadPeers()
        appendLog("已删除设备 ${p.displayName}（${p.ip}，通知对端删除 $notified 条文本/图片）")
    }

    /** 某台设备当前是否在线（组内任意地址有会话即在线；设备列表逐台打点） */
    fun isGroupOnline(g: PeerGroup): Boolean =
        if (g.alt) link.isPeerConnected(g.key) else g.ips.any { it in connectedPeers }

    /** 某台设备已连上但还没配对（列表里提示「未配对，点一下配对」，避免用户以为已经能传） */
    fun isGroupUnpaired(g: PeerGroup): Boolean =
        if (g.alt) link.isPeerUnpaired(g.key) else g.ips.any { link.isDeviceUnpaired(it) }

    /** 本机设备名：唯一允许出现在出网字段里的名字（私有备注绝不外发） */
    val myDeviceName: String get() = prefs.deviceName

    /** 按消息归属地址找设备显示名（同组各地址同名；找不到回落"电脑"）：仅本机界面用，绝不写进出网字段 */
    fun peerLabelOf(ip: String?): String {
        if (ip.isNullOrEmpty()) return "电脑"
        val key = prefs.convKeyOf(ip)
        val g = peerGroups.firstOrNull { it.key == key } ?: prefs.groupOfIp(ip)
        return g?.displayName ?: ip
    }

    // ==================== 消息 ====================

    /** mid→归属对端IP（updated 帧不带 peerIp，终态落历史时回这里查）：LRU 封顶 2000 条，仅主线程访问 */
    internal val midPeer = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 2000
    }

    /** 明确移除过的 mid（LRU 封顶 2000）：防止随后到达的进度/终态帧把已删气泡补回来；只记内存 */
    internal val removedMids = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > 2000
    }

    /** 这条消息是否属于当前展示的设备：按设备组键判（同设备多地址共用一份会话，不能按 IP 字符串比） */
    internal fun belongsToCurrent(m: MsgItem): Boolean {
        val sel = selectedPeerIp ?: return m.peerIp.isEmpty()
        if (m.peerIp.isEmpty()) return true
        return prefs.convKeyOf(m.peerIp) == prefs.convKeyOf(sel)
    }

    /** 首次出现超过 2GiB 的文件/文件夹气泡时弹一次提速提示（按 mid 去重，收发都提示，文件夹看聚合大小） */
    private fun maybeHintBigTransfer(m: MsgItem) {
        if (m.kind != Kind.FILE || m.fileSize <= BIG_TRANSFER_BYTES) return
        if (m.mid.isEmpty() || m.mid == bigHintMid) return
        if (messages.any { it.mid == m.mid }) return
        bigHintMid = m.mid
        bigTransferHint = BIG_TRANSFER_HINT
        bigTransferHintTick++
        appendLog("本次传输约 ${m.fileSize / (1024 * 1024)}MB（超过 2GB），已提示改用 WiFi/热点提速")
    }

    /** 新增一条消息：历史一律落盘，界面只收留当前对话设备的那条 */
    internal fun addMessage(m: MsgItem) {
        // 接收侧文本命中名单 ⇒ 当作没收到：不落盘/不上屏/不影响未读（ack 在 Link 里直接回，发送端照常显示成功）
        if (m.dir == Dir.PEER && m.kind == Kind.TEXT && TransferKeywords.isSilentlyFiltered(m.text)) return
        if (m.mid.isNotEmpty() && m.peerIp.isNotEmpty()) midPeer[m.mid] = m.peerIp
        // 大文件（>2GiB）提速提示：只气泡首次出现时弹一次
        maybeHintBigTransfer(m)
        // 终态帧（收完/失败）顺手回收速率快照 —— 气泡里的数字本来就按「样本过期」自动隐藏
        if (m.kind == Kind.FILE && (m.status == Status.OK || m.status == Status.FAILED)) clearSpeed(m.mid)
        // 与 PC 端对称：added 一律先落盘，且自己的 added 行必须先于终态裸帧进历史，否则重载后气泡缺文本/文件名
        if (m.kind != Kind.CLIP && m.peerIp.isNotEmpty()) {
            store.appendHistory(m)
        }
        if (!belongsToCurrent(m)) {
            pendingCount = store.loadPending().size
            return
        }
        val idx = messages.indexOfFirst { it.mid == m.mid }
        if (idx >= 0) {
            messages[idx] = merge(messages[idx], m)
        } else {
            messages.add(m)
        }
        if (messages.size > MAX_UI_MSGS) {
            repeat(messages.size - MAX_UI_MSGS) { messages.removeAt(0) }
        }
        pendingCount = store.loadPending().size
    }

    internal fun updateMessage(m: MsgItem) {
        // 传完/失败 → 清掉速率快照（气泡里的数字按「样本过期」隐藏，这里是回收）
        if (m.kind == Kind.FILE && (m.status == Status.OK || m.status == Status.FAILED)) clearSpeed(m.mid)
        // 终态补写历史：自己发的文件完成/失败只有 updated 回调，必须落盘；只落带实质内容的帧（裸状态帧只更新 UI）
        val hasBody = m.fileName.isNotEmpty() || m.text.isNotEmpty()
        // DENIED 也要落盘：否则重启后该文件变回"待发送"，红色叹号与重试入口消失
        if (m.kind != Kind.CLIP && hasBody &&
            (m.status == Status.OK || m.status == Status.FAILED || m.status == Status.DENIED)
        ) {
            val idx = messages.indexOfFirst { it.mid == m.mid }
            val full = when {
                idx >= 0 && belongsToCurrent(m) -> merge(messages[idx], m)
                m.peerIp.isNotEmpty() -> m
                midPeer.containsKey(m.mid) -> m.copy(peerIp = midPeer[m.mid]!!)
                else -> null
            }
            if (full != null) store.appendHistory(full)
        }
        if (!belongsToCurrent(m)) {
            pendingCount = store.loadPending().size
            return
        }
        val idx2 = messages.indexOfFirst { it.mid == m.mid }
        if (idx2 >= 0) {
            messages[idx2] = merge(messages[idx2], m)
        } else if (hasBody && m.mid.isNotEmpty() && midPeer.containsKey(m.mid) &&
            !removedMids.containsKey(m.mid)
        ) {
            // 这条 mid 界面上还没有气泡：满足归属且未被移除时补建，否则进度/完成帧会被整个丢弃
            messages.add(m)
            if (messages.size > MAX_UI_MSGS) {
                repeat(messages.size - MAX_UI_MSGS) { messages.removeAt(0) }
            }
        }
        pendingCount = store.loadPending().size
    }

    /** 移除一条消息（任一方取消）：界面列表、待发队列、本地历史三处一起删，避免重进应用又冒出来 */
    internal fun removeMessage(mid: String) {
        if (mid.isEmpty()) return
        // 记一笔「明确删掉的 mid」：随后的进度/终态帧不许把气泡补回来（见 [removedMids]）
        removedMids[mid] = true
        val idx = messages.indexOfFirst { it.mid == mid }
        if (idx >= 0) messages.removeAt(idx)
        store.removePending(mid)
        store.removeFromHistory(mid)
        pendingCount = store.loadPending().size
    }

    /** 长按删除一条消息：文本/图片先发 msg_del 通知对端再删本地三处（对端离线只删本机）；文件/文件夹只删本机记录 */
    fun deleteMessage(mid: String) {
        val m = messages.firstOrNull { it.mid == mid } ?: return
        val syncable = syncableKind(m)
        if (syncable) {
            val ip = m.peerIp.ifEmpty { selectedPeerIp ?: "" }
            val online = link.canSendMsgDel(ip)
            // P2-7：先落墓碑（sent=false 时握手会补发一次 msg_del），删了才不会被 hsync 补回来
            store.addTomb(ip, mid, sent = online)
            link.deleteMessage(ip, mid)
        }
        removeMessage(mid)
        appendLog(if (syncable) "已删除（对端在线时同步删除）" else "已删除记录")
    }

    internal fun merge(old: MsgItem, new: MsgItem): MsgItem = old.copy(
        text = new.text.ifEmpty { old.text },
        fileName = new.fileName.ifEmpty { old.fileName },
        fileSize = if (new.fileSize != 0L) new.fileSize else old.fileSize,
        filePath = new.filePath.ifEmpty { old.filePath },
        localUri = new.localUri.ifEmpty { old.localUri },
        status = new.status,
        progress = if (new.progress >= 0 || new.status != Status.OK) new.progress else old.progress,
        ts = if (new.ts != 0L) new.ts else old.ts,
        isDir = new.isDir || old.isDir,
        dirCount = if (new.dirCount != 0) new.dirCount else old.dirCount,
        // 归属以原消息为准：状态/进度更新帧不一定带 peer
        peerIp = new.peerIp.ifEmpty { old.peerIp }
    )

    // ==================== 主动发送 ====================

    /** 发送前的守卫：必须先选定「当前对话设备」，否则提示（不猜发给谁） */
    fun requireSelectedPeer(): Boolean {
        if (selectedPeerIp.isNullOrEmpty()) {
            appendLog("还没有选择目标设备：请先点左侧设备列表里的一台")
            return false
        }
        return true
    }

    fun sendText() {
        val t = inputText
        if (t.isBlank()) return
        if (!requireSelectedPeer()) return
        inputText = ""
        link.sendText(t)
        if (prefs.autoSyncOnSend) link.syncNow()
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (!requireSelectedPeer()) return
        link.sendText(text)
        if (prefs.autoSyncOnSend) link.syncNow()
    }

    /** 发文件：数据流量确认已下沉到 Link.sendFile（需在出气泡与开传之间完成），拒绝时气泡留红色叹号可重试 */
    fun sendFile(uri: Uri) {
        if (!requireSelectedPeer()) return
        sendFileInternal(uri)
    }

    /** 真正发起一次文件发送(数据流量确认由Link内部完成);分享批量路径复用 */
    internal fun sendFileInternal(uri: Uri, preGranted: Boolean = false) {
        link.sendFile(uri, preGranted)
        if (prefs.autoSyncOnSend) link.syncNow()
    }

    /** 发送整个文件夹（SAF 目录树）；递归枚举成员，目录气泡聚合进度 */
    fun sendFolder(treeUri: Uri) {
        if (!requireSelectedPeer()) return
        link.sendFolder(treeUri)
        if (prefs.autoSyncOnSend) link.syncNow()
    }

    /** 发送多个文件（浏览器多选）：一批只确认一次数据流量，通过后逐个进串行队列；设备未连接则逐个进待发队列 */
    fun sendFiles(uris: List<Uri>) {
        if (!requireSelectedPeer()) return
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            // 单个文件走原路径：行为与「＋ → 发送文件」完全一致（含离线进待发）
            sendFileInternal(uris[0])
            return
        }
        val ip = selectedPeerIp.orEmpty()
        appendLog("准备发送 ${uris.size} 个文件到 ${peerLabelOf(ip)}")
        Thread({
            val ready = link.isDeviceConnected(ip)
            if (ready) {
                val label = "发送 ${uris.size} 个文件"
                val consent = link.ensureDataConsent(
                    ip, link.newConsentMid(), DataConsentText.KIND_FILE, label
                )
                if (consent != DataConsent.OK) {
                    ui {
                        when (consent) {
                            DataConsent.REMOTE_REFUSED ->
                                toast(DataConsentText.peerRefusedText(DataConsentText.KIND_FILE))
                            // P2-1：对端 90s 无应答不是拒绝，提示要区分
                            DataConsent.REMOTE_TIMEOUT ->
                                toast("对方未确认（90 秒无响应），本次批量发送已取消")
                            else -> toast("已取消本次发送")
                        }
                    }
                    appendLog("批量发送已取消（$consent）")
                    return@Thread
                }
            } else {
                appendLog("设备未连接：${uris.size} 个文件先进入待发队列，连上后自动补发")
            }
            ui {
                uris.forEach { sendFileInternal(it, preGranted = ready) }
                if (prefs.autoSyncOnSend) link.syncNow()
            }
        }, "pp-send-files").apply { isDaemon = true }.start()
    }

    /** 重试"未同意/暂停"的文件（红色叹号触发）：完整重走提醒流程，同意后挪到最新位置再开传 */
    fun retryFileSend(mid: String) {
        val m = messages.firstOrNull { it.mid == mid } ?: return
        if (m.kind != Kind.FILE || (m.status != Status.DENIED && m.status != Status.PAUSED)) return
        val raw = m.filePath.ifEmpty { m.localUri }
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || raw.isEmpty()) {
            toast("这条记录已找不到源文件，请重新选择发送")
            return
        }
        appendLog("重试发送「${m.fileName}」，重新确认数据流量")
        link.retryFileSend(
            mid, uri, m.fileName, m.fileSize, m.isDir,
            m.peerIp.ifEmpty { selectedPeerIp.orEmpty() }
        )
    }

    /** 把一条消息挪到时间线最末(用户要求:重试同意后"把信息移动到最新一条") */
    internal fun moveMessageToLatest(mid: String) {
        val idx = messages.indexOfFirst { it.mid == mid }
        if (idx < 0) return
        val moved = messages[idx].copy(ts = System.currentTimeMillis())
        messages.removeAt(idx)
        messages.add(moved)
        if (moved.peerIp.isNotEmpty()) store.appendHistory(moved)
    }

    /** 取消一个正在发送 / 待发送的文件（两端同步取消、都不显示） */
    fun cancelFile(mid: String) = link.cancelFile(mid)

    fun syncNow() = link.syncNow()

    // ==================== 数据流量确认（双向） ====================

    /** 本机数据流量提醒（发起方向）：非空时界面弹确认框，用户选择经 done 唤醒等待线程 */
    class DataConsentAsk(
        val id: String, val peerIp: String, val label: String, val done: (Boolean) -> Unit
    )

    /**
     * 发起方向的确认队列（P2-1）：原先只有一个槽位，两路并发（文件 + 通话/分享）时后者覆盖前者，
     * 先来的线程只能等 120s 超时被取消。改成队列后按 id 精确定位、逐个处理。
     */
    var dataNetConfirms by mutableStateOf<List<DataConsentAsk>>(emptyList())
        internal set

    /** 界面只渲染队首，处理完自动补位 */
    val dataNetConfirm: DataConsentAsk? get() = dataNetConfirms.firstOrNull()

    /** 对端请求"用数据流量发给我"（接收方向）：非空时弹接收提醒框（本机非数据网络时不会到这里） */
    class DataConsentIncoming(val peerIp: String, val mid: String, val label: String) {
        /** 同一台设备的同一条请求只入队一次 */
        fun sameAs(o: DataConsentIncoming) = peerIp == o.peerIp && mid == o.mid
    }

    /** 接收方向的确认队列（P2-1）：同上，多台对端同时请求时不再互相覆盖 */
    var dataNetIncomings by mutableStateOf<List<DataConsentIncoming>>(emptyList())
        internal set

    val dataNetIncoming: DataConsentIncoming? get() = dataNetIncomings.firstOrNull()

    /** 本机当前是否在运营商数据网络;每次使用都重新判定、绝不记忆(每次用就提醒一次) */
    fun onCellular(): Boolean =
        runCatching { gate.onCellular(prefs.forceLanEnabled) }.getOrDefault(false)

    /** 弹"数据网络提醒"并阻塞等用户选择（供 Link 专用线程调用；误在主线程调用按"未同意"处理，不 ANR） */
    internal fun askLocalDataConsent(peerIp: String, label: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "数据流量提醒不能在主线程等待用户选择，按未同意处理")
            return false
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val id = java.util.UUID.randomUUID().toString()
        ui {
            appendLog("本机走数据网络，发起「$label」前需确认数据流量" +
                if (dataNetConfirms.isEmpty()) "" else "（前面还有 ${dataNetConfirms.size} 条待确认）")
            dataNetConfirms = dataNetConfirms + DataConsentAsk(id, peerIp, label) { r ->
                ok.set(r)
                latch.countDown()
            }
        }
        val waited = runCatching {
            latch.await(DATA_CONSENT_WAIT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!waited) {
            // 只摘掉自己这一条，不能把并发进来的别的请求一起清掉
            ui { dataNetConfirms = dataNetConfirms.filterNot { it.id == id } }
            appendLog("数据流量提醒 $DATA_CONSENT_WAIT_SEC 秒未确认，本次操作已取消")
        }
        return ok.get()
    }

    /** 用户同意本机使用数据流量（本次操作继续） */
    fun acceptDataNet(id: String) = finishDataConsent(id, true)

    /** 用户拒绝本机使用数据流量（本次操作取消；文件会留在聊天里带红色叹号可重试） */
    fun cancelDataNet(id: String) = finishDataConsent(id, false)

    internal fun finishDataConsent(id: String, ok: Boolean) {
        val c = dataNetConfirms.firstOrNull { it.id == id } ?: return
        dataNetConfirms = dataNetConfirms.filterNot { it.id == id }
        c.done(ok)
    }

    /** 接收方向：同意对端用数据流量发过来（对方才开始发；本机此时才创建/接收消息） */
    fun acceptIncomingData(peerIp: String, mid: String) {
        val c = dataNetIncomings.firstOrNull { it.peerIp == peerIp && it.mid == mid } ?: return
        dataNetIncomings = dataNetIncomings.filterNot { it.sameAs(c) }
        link.replyDataAsk(c.peerIp, c.mid, true)
        appendLog("已同意对端「${c.label}」使用数据流量传输")
    }

    /** 接收方向拒绝:本机什么都不记录(不生成气泡、不写历史)——只回一帧让对端如实提示 */
    fun rejectIncomingData(peerIp: String, mid: String) {
        val c = dataNetIncomings.firstOrNull { it.peerIp == peerIp && it.mid == mid } ?: return
        dataNetIncomings = dataNetIncomings.filterNot { it.sameAs(c) }
        link.replyDataAsk(c.peerIp, c.mid, false)
        appendLog("已拒绝对端「${c.label}」使用数据流量（本机不记录）")
    }

    /** 对端问「能不能用数据流量发给我」：本机不在数据网络就当场同意，不打扰用户 */
    internal fun onDataAskInternal(
        peerIp: String, mid: String, kind: String, name: String, size: Long
    ) {
        val label = DataConsentText.incomingLabelOf(kind, name)
        if (!onCellular()) {
            link.replyDataAsk(peerIp, mid, true)
            appendLog("对端请求「$label」：本机非数据网络，已自动同意")
            return
        }
        // P2-1：接收方向也排队（原先单槽位，多台对端同时请求会互相覆盖）
        val c = DataConsentIncoming(peerIp, mid, label)
        ui {
            if (dataNetIncomings.none { it.sameAs(c) }) {
                dataNetIncomings = dataNetIncomings + c
                appendLog("对端请求「$label」使用数据流量，等待本机确认")
            }
        }
    }

    // ==================== 系统「分享」进来：选设备 → 开始传 ====================

    /** 待处理的分享内容:非空时主界面弹"发送到哪台设备";由MainActivity解析ACTION_SEND后写入 */
    var pendingShare by mutableStateOf<SharedPayload?>(null)
        internal set

    /** 收到一次系统分享（应用已被系统拉到前台），等用户选目标设备 */
    fun onShared(payload: SharedPayload) {
        pendingShare = payload
        appendLog("收到系统分享：${payload.summary().replace('\n', ' ')}，等待选择目标设备")
    }

    fun cancelShare() {
        if (pendingShare == null) return
        pendingShare = null
        appendLog("已取消本次分享发送")
    }

    /** 选定目标设备后定向连接并开始传输：等链路建立超时则先转存到应用目录再入队 */
    fun shareTo(g: PeerGroup) {
        val payload = pendingShare ?: return
        val ip = g.curIp
        pendingShare = null
        connectTo(ip, g.displayName)
        appendLog("分享内容将发送到 ${g.displayName}（$ip）")
        Thread({
            if (payload.items.isEmpty()) {
                payload.text?.let { t -> ui { sendText(t) } }
                return@Thread
            }
            var ready = false
            val deadline = System.currentTimeMillis() + SHARE_CONNECT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                // 该设备组任意地址有会话即算就绪(同组回落,见Link.sessionByGroup)
                if (link.isDeviceConnected(ip)) {
                    ready = true
                    break
                }
                runCatching { Thread.sleep(150) }
            }
            // 数据流量确认：一批只提醒一次；链路未建立时不问，进待发队列
            if (ready) {
                val label = "发送分享内容（${payload.items.size} 项）"
                val consent = link.ensureDataConsent(
                    ip, link.newConsentMid(), DataConsentText.KIND_FILE, label
                )
                if (consent != DataConsent.OK) {
                    ui {
                        when (consent) {
                            DataConsent.REMOTE_REFUSED ->
                                toast(DataConsentText.peerRefusedText(DataConsentText.KIND_FILE))
                            DataConsent.REMOTE_TIMEOUT ->
                                toast("对方未确认（90 秒无响应），本次分享发送已取消")
                            else -> toast("已取消本次分享发送")
                        }
                    }
                    appendLog("分享发送已取消（$consent）")
                    return@Thread
                }
            }
            val uris = ArrayList<Uri>()
            if (ready) {
                payload.items.forEach { uris += it.uri }
                appendLog("链路已就绪，直接发送 ${uris.size} 个文件")
            } else {
                appendLog("链路尚未建立，先把 ${payload.items.size} 个文件转到应用目录再排队发送")
                payload.items.forEach { item -> stashToInbox(item)?.let { uris += it } }
                val lost = payload.items.size - uris.size
                if (lost > 0) appendLog("有 $lost 个文件读取失败，已跳过")
            }
            ui {
                uris.forEach { sendFileInternal(it, preGranted = true) }
                if (uris.isNotEmpty()) syncNow()
            }
        }, "pp-share").apply { isDaemon = true }.start()
    }

    /** 把分享内容复制进应用私有目录，返回可长期读取的 file:// Uri（每文件一个子目录，不互相覆盖） */
    internal fun stashToInbox(item: SharedItem): Uri? {
        val safe = item.name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "share_${System.currentTimeMillis()}" }
        val sub = File(File(app.cacheDir, INBOX_DIR), "s${System.currentTimeMillis()}")
        return runCatching {
            sub.mkdirs()
            val f = File(sub, safe)
            app.contentResolver.openInputStream(item.uri)?.use { ins ->
                f.outputStream().use { out -> ins.copyTo(out, 1 shl 16) }
            } ?: throw java.io.IOException("打不开源文件")
            Uri.fromFile(f)
        }.getOrElse {
            Log.w(TAG, "转存分享内容失败 ${item.name}: ${it.message}")
            runCatching { sub.deleteRecursively() }
            null
        }
    }

    /** 清理分享转存目录里超过 [INBOX_TTL_MS] 的残留（正常发完没有引用） */
    internal fun cleanInbox() {
        Thread({
            val kids = File(app.cacheDir, INBOX_DIR).listFiles()
            if (kids != null) {
                val cutoff = System.currentTimeMillis() - INBOX_TTL_MS
                var n = 0
                for (k in kids) {
                    if (k.lastModified() < cutoff) {
                        runCatching { k.deleteRecursively() }.onSuccess { if (it) n++ }
                    }
                }
                if (n > 0) appendLog("清理分享暂存 $n 项")
            }
        }, "pp-inbox").apply { isDaemon = true }.start()
    }

    /** 剪贴板同步模式切换:开启时立刻做一次读取推送(用户此刻在应用内、持有焦点,读得到) */
    fun onClipSyncModeChanged(enabled: Boolean) {
        prefs.clipSyncMode = enabled
        clipSyncOn = enabled
        appendLog(
            if (enabled) "剪贴板同步模式：已开启（切回本应用时读取一次并同步；接收为纯被动）"
            else "剪贴板同步模式：已关闭（改为手动）"
        )
        if (enabled) pushClipIfChanged()
    }

    // ==================== 剪贴板联动 ====================

    internal var lastForegroundAt = 0L


    // ==================== 杂项 ====================

    /** "清空聊天记录"：逐条删（msg_del 通知对端一起删，防 hsync 补回），有选定设备只清该台，没选清全部；传输在途时拒绝 */
    fun clearHistory() {
        if (clearConfirm != null) return
        val ip = selectedPeerIp
        Thread({
            val parts = if (ip.isNullOrEmpty()) planAllConversations() else planConversation(ip)
            if (parts.isEmpty()) {
                appendLog("没有可清空的聊天记录")
                // 顶栏「清空」是静默入口，没记录时只写日志=点了没反应，补一句 toast
                toast("没有可清空的聊天记录")
                return@Thread
            }
            val busy = parts.filter { link.hasActiveTransfer(it.ip) }
            if (busy.isNotEmpty()) {
                appendLog("还有传输任务进行中（${busy.size} 台设备）：等传完再清空")
                toast("还有传输任务进行中，等传完再清空")
                return@Thread
            }
            val total = parts.sumOf { it.all.size }
            val sync = parts.sumOf { it.sync.size }
            val offline = parts.any { it.addr.isNotEmpty() && it.sync.isNotEmpty() && !link.canSendMsgDel(it.addr) }
            ui {
                clearConfirm = ClearPlan(
                    whole = ip.isNullOrEmpty(),
                    label = if (ip.isNullOrEmpty()) "全部设备" else peerLabelOf(ip),
                    total = total,
                    syncable = sync,
                    offline = offline,
                    orphan = parts.count { it.addr.isEmpty() && it.sync.isNotEmpty() },
                    parts = parts
                )
            }
        }, "pp-clear-plan").apply { isDaemon = true }.start()
    }

    /** 用户点了「取消」/点了弹窗外 */
    fun cancelClearHistory() {
        clearConfirm = null
    }

    /** 用户点了「清空」：[clearConfirm] 里的计划真正执行 —— 本地一次清完 + 逐条通知对端。
     *  必须整体在工作线程跑：deleteMessages 里的 io{} 在主线程会转投异步执行并立即返回，
     *  这里若继续拿返回值就会恒得 0（误报"删除命令没发出去"），墓碑也被错误标成 sent=false；
     *  历史文件整读整写同样不该压在主线程。 */
    fun confirmClearHistory() {
        val p = clearConfirm ?: return
        clearConfirm = null
        Thread({
            var notified = 0
            val cleared = HashSet<String>()
            for (part in p.parts) {
                cleared.addAll(part.all)
                if (part.sync.isNotEmpty() && part.addr.isNotEmpty()) {
                    // 返回 0 = 对端不在线 / 不支持 hd：这时只清了本机，下面会如实提示
                    val sent = link.deleteMessages(part.addr, part.sync)
                    // P2-7：每条同步删除都落墓碑；没发出去的 sent=false，下次握手补发 msg_del
                    part.sync.forEach { store.addTomb(part.addr, it, sent = sent > 0) }
                    notified += sent
                } else if (part.sync.isNotEmpty()) {
                    part.sync.forEach { store.addTomb(part.addr.ifEmpty { part.ip }, it, sent = false) }
                }
                store.removePending(part.all)
                store.removeFromHistory(part.ip, part.all)
            }
            ui {
                if (p.whole) messages.clear() else messages.removeAll { it.mid in cleared }
                // 清掉的记进 removedMids：防止期间排进来的在途传输终态帧把气泡补回来
                for (id in cleared) removedMids[id] = true
                pendingCount = store.loadPending().size
                appendLog("已逐条删除 ${p.total} 条记录（通知对端 $notified 条）")
                val missed = p.syncable - notified
                if (missed > 0) {
                    clearWarn = "已清掉本机的 ${p.total} 条记录。\n\n" +
                        "但其中 $missed 条文本/图片的删除命令没能发给对方（对方不在线，" +
                        "或这台设备已不在名单里）—— 对方下次连上时，它们可能被历史同步补回来。\n" +
                        "要彻底清掉，请在对端在线时再清空一次。"
                }
            }
        }, "pp-clear-run").apply { isDaemon = true }.start()
    }

    /** 枚举一台设备要清的 mid：历史全量+待发，分出参与删除同步的部分（文本+图片），同组另一地址一并清掉 */
    private fun planConversation(ip: String): List<ClearPart> {
        val key = prefs.convKeyOf(ip)
        val seen = LinkedHashSet<String>()
        val all = ArrayList<String>()
        val sync = ArrayList<String>()
        for (m in store.loadHistory(ip, Int.MAX_VALUE)) {
            if (m.kind == Kind.CLIP) continue
            if (m.mid.isEmpty() || !seen.add(m.mid)) continue
            all.add(m.mid)
            if (syncableKind(m)) sync.add(m.mid)
        }
        for (p in store.loadPending()) {
            if (p.mid.isEmpty()) continue
            if (p.peerIp.isNotEmpty() && prefs.convKeyOf(p.peerIp) != key) continue
            if (!seen.add(p.mid)) continue
            all.add(p.mid)
            if (syncableKind(p)) sync.add(p.mid)
        }
        if (all.isEmpty()) return emptyList()
        // 地址得能寻址才谈得上通知对端：孤立历史文件传空串（sync 条目只清本机，确认框单独说明）
        return listOf(ClearPart(ip = ip, addr = if (isAddressable(ip)) ip else "", all = all, sync = sync))
    }

    /** 枚举全部设备：候选地址取名单+已连+待发+聊天区归属，按组键去重（代表地址优先取当前连着的），孤立历史文件也兜进来 */
    private fun planAllConversations(): List<ClearPart> {
        val cands = LinkedHashSet<String>()
        cands.addAll(prefs.peerIps)
        cands.addAll(link.connectedIps)
        for (p in store.loadPending()) if (p.peerIp.isNotEmpty()) cands.add(p.peerIp)
        for (m in messages) if (m.peerIp.isNotEmpty()) cands.add(m.peerIp)
        val byKey = LinkedHashMap<String, String>()
        for (c in cands) {
            val k = prefs.convKeyOf(c)
            val cur = byKey[k]
            byKey[k] = if (cur == null || (!link.isConnectedIp(cur) && link.isConnectedIp(c))) c else cur
        }
        val out = ArrayList<ClearPart>()
        for (rep in byKey.values) out.addAll(planConversation(rep))
        // 名单里没有、但磁盘上还有的历史（设备被删过）—— 也要能清掉，否则「清空」名不副实
        for (k in store.historyKeys()) {
            if (k in byKey) continue
            out.addAll(planConversation(k))
        }
        return out
    }

    /** 这个串是「能寻址的地址」吗（IPv4 / IPv6）；组键（deviceId）不是 */
    private fun isAddressable(s: String): Boolean =
        s.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || s.contains(':')

    /** 一条消息是否参与删除同步(协议§4.7只有文本+图片);文件/文件夹/剪贴板/通话记录只删本机,不会被hsync补回 */
    internal fun syncableKind(m: MsgItem): Boolean =
        m.kind == Kind.TEXT ||
            (m.kind == Kind.FILE && !m.isDir && FileIO.isImageFileName(m.fileName))

    /** 清空完成提示（对方不在线时会补回来）——用户点掉后清空 */
    fun dismissClearWarn() {
        clearWarn = null
    }

    // ==================== 已知设备（后台可连接名单） ====================

    /** 已知设备名单的查询键：优先**设备身份**（deviceId），拿不到身份才退回地址。
     *  名单按身份存 → 同设备换 IP、同设备多地址都认得出，不会被当成陌生设备 */
    private fun trustKeyOf(ip: String): String =
        runCatching { prefs.convKeyOf(ip) }.getOrDefault("").ifEmpty { ip.trim() }

    /** 设置界面展示用：哪些设备在后台也允许连本机 */
    fun trustedSummary(): String = trusted.summary()

    /** 已知设备台数 */
    fun trustedCount(): Int = trusted.count()

    /** 清空已知设备名单（显式操作）：之后后台不再放行任何设备，需先前台连一次 */
    fun clearTrusted() {
        trusted.clear()
        appendLog("已清空已知设备名单：后台不再接受任何设备的连接（先前台连一次即可恢复）")
    }

    fun clearPending() {
        store.clearPending()
        pendingCount = 0
        appendLog("已清空待发队列")
    }

    internal fun appendLog(s: String) {
        Log.i(TAG, s)
        ui { lastLog = s }
    }

    /** 供通话状态机等非 AppCore 内部代码写入日志（公开入口） */
    fun appendLogPublic(s: String) = appendLog(s)

    /** 复制本机地址：写本机剪贴板并当场推给已连接对端（记 sha1 防重复推，复用 link.sendClip） */
    fun copyForAdd(ip: String) {
        val t = ip.trim()
        if (t.isEmpty()) return
        val connected = link.isConnected()
        ui {
            clip.write(t)
            val sha = Clip.sha1(t)
            clip.lastPushedSha1 = sha
            prefs.lastPushedSha1 = sha
            if (connected) link.sendClip(t, sha)
        }
        toast(if (connected) "已复制并同步给对端：$t" else "已复制：$t")
        appendLog(
            "复制本机地址 $t（${if (connected) "已同步给对端，可直接在对端「＋设备」里粘贴" else "未连接，只写本机剪贴板"}）"
        )
    }

    /** 长按消息菜单"复制文本"：写入剪贴板并走同步链路（必须 force=true，否则刚写入的内容会被当回声吃掉） */
    fun copyTextToClipboard(text: String, hint: String = "已复制文本") {
        if (text.isEmpty()) return
        ui {
            clip.write(text)
            if (prefs.clipSyncMode) pushClipIfChanged(force = true)
        }
        toast(hint)
        appendLog("复制文本（${text.length} 字）到本机剪贴板")
    }

    // ==================== 版本变更 → 是否清除数据 ====================

    /** 当前软件版本号（versionName）；取不到返回空串（此时一律不提示、不动数据） */
    fun currentVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 数据里记录的旧版本号（提示文案用；空 = 老版本升级/从未记录） */
    fun recordedDataVersion(): String = prefs.dataVersion.ifBlank { "未知（旧版本）" }

    /**
     * 版本变更检查（启动时调一次）：
     * 数据记录的版本号与当前版本不一致 → true，界面负责询问「是否需要清除应用数据」；
     * 首次安装（无记录且无任何旧数据）静默对齐，不问；
     * **老版本升级**（没有记录但磁盘上已有历史/待发/台账）同样返回 true —— 旧版本从没写过记录。
     */
    fun needAskDataVersionChange(): Boolean {
        val cur = currentVersionName()
        if (cur.isEmpty()) return false
        val rec = prefs.dataVersion
        if (rec.isEmpty()) {
            if (!store.hasAnyData()) {
                prefs.dataVersion = cur
                return false
            }
            appendLog("版本检查：检测到旧数据但没有版本记录，按版本变化处理")
            return true
        }
        return rec != cur
    }

    /** 选「否」：只把数据记录的版本号改成当前版本，之后不再提示 */
    fun keepDataAndAlignVersion() {
        val cur = currentVersionName()
        // 同步落盘：用户答完可能马上划掉应用，apply() 的异步写丢了就又会弹
        prefs.commitDataVersion(cur)
        appendLog("版本变更（$cur）：保留本地数据，已把数据版本记录更新为当前版本")
    }

    /** 选「是」：清除软件数据（**不影响已接收的文件**），并把数据版本记录写成当前版本 */
    fun clearDataAndAlignVersion() {
        val cur = currentVersionName()
        // 必须 clearAllSync：这个函数后面紧接着 restartApp() 杀进程，
        // apply() 的异步落盘会被打断 → 旧 data_version 还在 → 下次启动再弹一次「检测到版本变化」
        runCatching { prefs.clearAllSync() }.onFailure { Log.w("PP", "清空设置失败: ${it.message}") }
        runCatching { store.clearAllData() }.onFailure { Log.w("PP", "清空台账失败: ${it.message}") }
        runCatching { trusted.clear() }.onFailure { Log.w("PP", "清空信任设备失败: ${it.message}") }
        prefs.commitDataVersion(cur)
        appendLog("版本变更（$cur）：已清除软件数据（下载目录里已接收的文件保留）")
    }

    /** 短提示：主线程弹 Toast，用于操作无可见反馈时给用户明确回应（避免「点了没反应」错觉） */
    fun toast(msg: String) {
        val ctx = app
        main.post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 语音通话操作 ====================


    /** MainActivity 注入的申请录音权限入口：必须是"带延续"形式，否则视频触发的授权回调会误走语音发起 */
    var recordPermRequester: ((() -> Unit) -> Unit)? = null

    /** MainActivity 注入的申请相机权限入口：必须"带延续"，授权回调里继续原动作（接听/发起视频） */
    var cameraPermRequester: ((() -> Unit) -> Unit)? = null


    // ==================== 视频通话操作 ====================

    /** 视频通话总开关（设置界面用） */
    var videoEnabled: Boolean
        get() = prefs.videoEnabled
        set(v) {
            prefs.videoEnabled = v
            link.localVidCap = v
            link.resendHello()
        }

    /** 档位上限（与对端协商取 min）：0=流畅480P，1=标清720P，2=高清1080P，3=超清1080P，默认不设限 */
    var videoMaxLevel: Int
        get() = prefs.videoMaxLevel
        set(v) {
            prefs.videoMaxLevel = v
            call.maxVideoLevelIdx = prefs.videoMaxLevel
        }

    /** "旋转"钮记忆的最终显示角：对方画面/本地预览各一套持久化，纯渲染侧修正（-1=未手调）；初值在 init() 里回填 */
    @Volatile
    var videoRemoteRot: Int = -1
        internal set

    @Volatile
    var videoLocalRot: Int = -1
        internal set


    /** 视频会话（UI 挂 Surface 用）；无通话时也可安全取用 */
    val videoSession get() = call.video

    fun shortClipPreview(s: String): String = TextUtil.headTail(s, expanded = false)

    fun isFileMessage(m: MsgItem): Boolean = m.kind == Kind.FILE
}

/** "清空聊天记录"单台设备的 mid 清单（枚举产出、确认后执行）：all=全部，sync=其中文本+图片（发 msg_del 通知对端） */
data class ClearPart(
    val ip: String,
    val addr: String,
    val all: List<String>,
    val sync: List<String>
)

/** 「清空」确认框的内容：条数与在线状态在**确认之前**就如实算好 */
data class ClearPlan(
    /** true = 没选设备 → 清全部设备 */
    val whole: Boolean,
    val label: String,
    val total: Int,
    val syncable: Int,
    /** 有设备离线（它的文本/图片删不掉、可能被同步回来） */
    val offline: Boolean,
    /** 找不到对应设备的孤立历史条数（只能清本机） */
    val orphan: Int,
    val parts: List<ClearPart>
)
