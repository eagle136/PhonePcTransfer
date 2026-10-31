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
import com.phonepctransfer.net.TransferKeywords
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

/**
 * 全局单例装配 + UI 状态中枢。
 *
 * 剪贴板联动（2026-09-15 用户定调：**读取只由焦点切换触发，接收纯被动**）：
 *  - 同步模式开（默认）：**焦点切回自身**（onResume / onWindowFocus(true)）时读取一次
 *    本机剪贴板并推送；不做任何轮询、不注册变化监听、不主动拉取对端
 *  - 接收纯被动：对端推来的剪贴板（clip/clip_state 帧）直接写本机剪贴板，
 *    不回拉、不应答拉取之外的任何主动动作
 *  - 同步模式关：纯手动，只有点「立即推送剪贴板」/「同步」才动作
 *
 * 读取本机剪贴板只发生在「持有焦点」时（系统硬限制，Android 10+）。后台复制内容的
 * 读取（无障碍焦点 overlay、Shizuku 等）已全部移除；后台**仅能**接收对端写入的剪贴板。
 *
 * 剪贴板同步**不是「发消息」**：它直接写本机剪贴板 + 打一条日志，不生成任何聊天气泡
 * （文本/文件才走消息通道），所以聊天记录里不会出现剪贴板内容。
 */
object AppCore {

    internal const val TAG = "PP-AppCore"
    internal const val MAX_UI_MSGS = 800
    internal const val LAN_TICK_MS = 5000L

    /**
     * 一轮主动扫描的收集窗口。窗口内包含**本网段 /24 逐地址单播扫描**（2026-09-21 新增）。
     * 3s 是「广播 + 254 个地址单播 + 对端回应 2s 限频」三者的折中：单播扫描本身只要
     * 一两百毫秒，余下时间都在等对端那一次回应。
     */
    internal const val SCAN_WINDOW_MS = 3000L

    /** 分享内容临时转存目录（cache 下，系统可回收）：连不上时先把内容拷进来再排队 */
    internal const val INBOX_DIR = "share_inbox"

    /** 转存内容保留时长：超过就清掉（正常发完即无引用，留着只是防待发队列还没轮到） */
    internal const val INBOX_TTL_MS = 3L * 24 * 3600_000

    /**
     * 「大文件提速提示」的体积门槛：**2 GiB**（2026-09-22 用户要求「超过 2g」）。
     * 超过就提示换「双方都走 WiFi / 本机开热点」，因为那条链路通常比「一方有线」快好几倍。
     */
    internal const val BIG_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024

    /** 提示文案（用户原话，一字不改） */
    internal const val BIG_TRANSFER_HINT =
        "如果双方都使用WiFi而不是一方使用了有线，开启热点的方式速度通常会快几倍"

    /** 分享选定设备后等链路建立的时长；等不到就先把内容落盘再入队 */
    internal const val SHARE_CONNECT_WAIT_MS = 2500L

    /**
     * 「数据网络提醒」确认框的最长等待时间（v2.7）。
     * 超时按**未同意**处理并撤掉弹框 —— 宁可少发一次，也绝不在用户没同意的情况下花流量。
     */
    internal const val DATA_CONSENT_WAIT_SEC = 120L

    internal lateinit var app: Context

    lateinit var prefs: Prefs
        internal set
    lateinit var store: Store
        internal set

    /**
     * 已知设备名单（后台允许连接白名单）—— 连接过 + 有消息/文件往来。
     * **与 [store] 的聊天记录解耦**：`clearHistory()` 不会动它（用户明确要求）。
     */
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

    /** 聊天区当前显示的消息 —— **只属于 [selectedPeerIp] 这台设备** */
    val messages = mutableStateListOf<MsgItem>()

    /**
     * 设备列表（左侧栏）。来源 = 手动添加/编辑的条目 + UDP 自动发现的设备。
     * 每项含 IP 与备注（长按可改）；**点击某项 = 切为当前对话设备并按需连接一次**。
     *
     * ⚠ 这是「每 IP 一行」的原始视图，界面请用 [peerGroups]（一设备一行，见下）。
     */
    val peers = mutableStateListOf<Peer>()

    /**
     * 设备**组**视图（界面用）：同一台设备（同一 deviceId）的多个地址合成一条 ——
     * 手机换 WiFi / 热点 / 插网线都是同一台，备注与聊天记录不再被切成好几份。
     * 组内当前使用的地址由 [PeerGroup.curIp] 给出，可在聊天顶栏切换（见 [switchGroupIp]）。
     */
    val peerGroups = mutableStateListOf<PeerGroup>()

    /**
     * 当前对话设备 IP（左侧列表高亮的那台）。null = 还没选。
     *
     * 主动发送（文本/文件/剪贴板）只发给它；聊天区只显示它的往来。
     * 会话本身是多设备并存的：切到另一台**不会断开**原来那台。
     */
    var selectedPeerIp by mutableStateOf<String?>(null)
        internal set

    /** 已连上的设备 IP 集合：列表按它**逐台**亮绿点，多台可同时亮（互不影响） */
    var connectedPeers by mutableStateOf<Set<String>>(emptySet())
        internal set

    /**
     * 对端能力 / 会话集合变化信号（自增计数）。
     *
     * ⚠ 存在的唯一理由：`link.sessions` 与握手得到的 `caps` 都是**普通集合/字段**，
     * 不是 Compose 状态。「视频」按钮的可用性要读 `link.peerVidCap()`，
     * 而「点设备 → 发起连接 → 对端握手成功」这条路径上**没有任何被 InputBar 读取的
     * 状态发生变化**，所以握手完成后按钮不会重新求值、会永久停在置灰状态。
     * 实测表现：把手机与 PC 通话当对照之所以「正常」，只是因为 PC 是**先连进来**、
     * 用户后点它，点的那一下刚好触发了一次重组。
     *
     * 读它的地方：`peerSupportsVideo()` —— 只要按能力置灰的界面调了那个方法，
     * 就会自动订阅本信号。
     */
    var peerCapsTick by mutableIntStateOf(0)
        internal set

    var linkState by mutableStateOf(Link.State.STOPPED)
        internal set
    var peerLabel by mutableStateOf("")
        internal set
    var lanInfo by mutableStateOf(LanGate.Info(false, "NONE", "未初始化"))
        internal set
    var localIp by mutableStateOf("-")
        internal set

    /**
     * 本机全部可用地址（设备列表里逐行展示、点一下复制）。
     *
     * 顺序见 [LocalIps]：局域网 v4 → 169.254 链路本地 → IPv6 内网 1 个 → IPv6 公网。
     * **本机在数据网络时**额外追加蜂窝网卡的 IPv6 公网地址（见 [LocalIps.list] 的 includeCellular）。
     * 随 [refreshLan] 每 5s 对一次网卡；内容相同不会重新赋值（Compose 结构相等 → 不触发重组）。
     */
    var localIps by mutableStateOf<List<String>>(emptyList())
        internal set
    var running by mutableStateOf(false)
        internal set
    var pendingCount by mutableStateOf(0)
        internal set
    var lastLog by mutableStateOf("")
        internal set
    var inputText by mutableStateOf("")

    /**
     * 「清空聊天记录」的待确认计划（枚举完成后才有值 → 弹确认框）。
     * 条数与「对方在不在线」必须在**确认之前**就如实算出来，不能按完之后再猜。
     */
    var clearConfirm by mutableStateOf<ClearPlan?>(null)
        internal set

    /** 清完之后「该通知的没通知到」的如实提示（对方上线会把文本/图片同步回来） */
    var clearWarn by mutableStateOf<String?>(null)
        internal set

    // ==================== 语音通话 UI 状态 ====================

    /** 当前通话状态（供界面显示「正在通话」条与弹窗） */
    var callState by mutableStateOf(CallManager.State.IDLE)
        internal set

    /** 当前通话对端 IP（空 = 无通话） */
    var callPeerIp by mutableStateOf("")
        internal set

    /** 当前通话对端显示名 */
    var callPeerName by mutableStateOf("")
        internal set

    /**
     * 对端 `video_cfg` 到达计数（含切摄重发 + 发送端 1s 心跳）。
     * 读线程收到即 `ui{}` bump —— 通话界面据此**立刻**重读对端摄像头朝向并切换
     * 「对方画面」的旋转配置槽位，不等 500ms 界面心跳。
     */
    var peerCamTick by mutableIntStateOf(0)
        internal set

    /** 本机切摄像头计数（切换成功即 bump）→ 本地小窗旋转槽位立刻联动 */
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

    /** 当前通话是否免提（扬声器）输出。默认开——通话通道默认听筒级输出声音很小 */
    var callSpeakerOn by mutableStateOf(true)
        internal set

    /** 当前通话音频通道：true=媒体（默认，媒体音量、自动跟随耳机），false=电话（通话音量） */
    var callMediaChannel by mutableStateOf(true)
        internal set

    /** 对端是否回传画面（false → 显示「对方仅接收视频」占位） */
    var peerSendsVideo by mutableStateOf(false)
        internal set

    /**
     * 最近一次局域网扫描的结果文案，设备列表里直接展示：
     * 「正在扫描 10.1.2.1-254…」/「发现 N 台设备」/「无新设备」/
     * 「WiFi 未连接，已跳过扫描」/「本机不在局域网私有网段，已跳过扫描」
     */
    var scanMessage by mutableStateOf("")
        internal set

    /** 是否正在扫描（UI 用它禁用「扫描」按钮） */
    var scanning by mutableStateOf(false)
        internal set

    // ==================== 传输速率（气泡显示，2026-09-22）====================

    /**
     * 传输速率快照：`mid` → 字节/秒（发送与接收两侧都由 Link 在字节级采样，见 `Link.markSpeed`）。
     *
     * 为什么是**快照状态**：气泡里那行「87% · 0.95M/s」直接读它，值一变就只有那一行重组
     * （拆成了独立的小 composable，见 `ui/Bubble.kt TransferLine`）。
     */
    private val speeds = mutableStateMapOf<String, Long>()

    /** `mid` → 速率样本的时刻（判断「还在传吗」，见 [SPEED_FRESH_MS]） */
    private val speedsAt = mutableStateMapOf<String, Long>()

    /**
     * 速率有效期（ms）：超过这么久没有新样本就当作「没在传」→ 气泡**不显示**速率。
     *
     * 这一条同时覆盖三个要求：**传完消失**（完成时不再有新样本）、**排队/暂停中不显示**
     * （待发队列里压根没有字节流动）、**卡住不显示**（半死不活的链路不显示一个骗人的数字）。
     */
    private const val SPEED_FRESH_MS = 1500L

    /**
     * 气泡用：`mid` 这条当前速率（字节/秒）；**0 = 未知 / 已过期 → 不显示**。
     * @param now 调用方传入的时间戳（气泡自己 400ms 心跳刷新，传进来看过期与否）
     */
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

    // ==================== 大文件提速提示（2026-09-22 用户要求）====================

    /**
     * 传输**超过 [BIG_TRANSFER_BYTES]** 时的提速提示文案；null = 不显示。
     *
     * 只弹「新出现的文件气泡」那一次（见 [maybeHintBigTransfer]），界面侧 **5s 自动消失**
     * （`ChatScreen.BigTransferHint`）—— 纯提示，不带按钮、不阻断操作。
     */
    var bigTransferHint by mutableStateOf<String?>(null)
        internal set

    /**
     * 同一个提示的重显计数器。
     *
     * 连发两个大文件时文案完全一致 → 只改 [bigTransferHint] 的话值没变，Compose 不重组、
     * `LaunchedEffect` 的键也不变 → 5s 计时不会重新起算（第二条会被前一条的计时一起吞掉）。
     * 界面侧把「文案 + 这个计数」一起当 `LaunchedEffect` 的键，每次触发都重新计时。
     */
    var bigTransferHintTick by mutableLongStateOf(0L)
        internal set

    /** 提示关掉（界面 5s 倒计时到点后调） */
    fun dismissBigTransferHint() {
        bigTransferHint = null
    }

    /** 已经为哪条消息提示过（同一条的合并/进度帧不再重复弹） */
    private var bigHintMid = ""

    /** 剪贴板同步模式是否开启（UI 用，避免直接读 Prefs 拿不到重组） */
    var clipSyncOn by mutableStateOf(true)
        internal set

    /** 应用可见（onResume ~ onPause）。**挂小窗/分屏时仍为 true**——剪贴板轮询靠它存活。 */
    @Volatile
    var appVisible = false
        internal set

    /**
     * 应用持有窗口焦点。这才是「读剪贴板」真正需要的东西（Android 10+ 的硬限制）。
     *
     * 为什么必须和 appVisible 分开：挂小窗/分屏时应用明明可见（用户看得到、点得到），
     * 但窗口焦点在系统看来可能不在它身上 → hasWindowFocus() 为 false。
     * 如果拿它当轮询的存活条件，一挂小窗轮询就停了，"复制了不同步"就是这么来的。
     */
    @Volatile
    var foregroundActive = false
        internal set

    // ==================== 初始化 ====================

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = ctx.applicationContext
            prefs = Prefs(app)
            clipSyncOn = prefs.clipSyncMode
            // 旋转角改成**按设备**记（R5：每个设备聊天窗口各自沿用上次的角度）。
            // 旧版的全局角只在首次升级时播种给「上次选中的那台设备」，之后各记各的。
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
            // 设备列表：首次读取会把旧版的纯 IP 集合迁移成带备注的 Peer
            reloadPeers()
            // 启动清理：删掉 5 天没用过、且该设备还有别的地址可用的陈旧地址。
            // 只有两条地址的设备整体跳过；保底留最近使用的两条 + 当前使用的那条（见 Prefs.pruneStaleIps）。
            runCatching {
                val n = prefs.pruneStaleIps()
                if (n > 0) {
                    reloadPeers()
                    appendLog("已清理 $n 个 5 天未使用的设备地址")
                }
            }
            store = Store(app)
            // 历史/待发按**设备**（deviceId）归并：多 IP 同一设备 = 同一份聊天记录
            //（解析器实现在 Prefs.convKeyOf，Store 只认回调，见 Store.keyResolver）
            store.keyResolver = { ip -> prefs.convKeyOf(ip) }
            // 内置文件浏览器的 root 开关（设置页可改，见 Prefs.rootFs）：
            // 开 = 所有文件访问都过 su、浏览根改成 /data/media/0；关 = 普通 File API。
            // 这里只在启动时同步一次，设置页改动时会就地再同步（见 SettingsSheet 的 root 开关）。
            LocalFs.rootEnabled = prefs.rootFs
            // 待发条目的增删有一半发生在链路层（补发跑完由对端 ack 驱动出队、补发时发现
            // 已送达 / 会话又断了的直接出队）——那条路径**没有**任何消息级回调，界面上的
            // 「待发送 N 条，连接后自动补发」横幅与前台通知副标题全靠这里重算。
            // 少了它，数字会停在旧值上不刷新，换一次会话才消失（2026-10-05 修）。
            // Store 的调用方可能在任意线程 → 一律 ui{} 切主线程再写 Compose 状态。
            store.onPendingChanged = { ui { pendingCount = store.loadPending().size } }
            trusted = TrustedPeers(app)
            clip = Clip(app)
            gate = LanGate(app)
            link = Link(app, prefs)
            link.store = store
            link.trusted = trusted
            // ===== 视频能力（握手里广播；必须在 link.start() 之前设）=====
            // vid：本端能否收视频（关掉开关就完全不参与视频，等价于全局回退）
            link.localVidCap = prefs.videoEnabled
            // cam：本机有没有摄像头（只查系统特性，不需要相机权限）
            link.localCamCap = runCatching {
                app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
            }.getOrDefault(false)
            // vcodec：本端可解码的编码列表。MediaCodecList 枚举稍贵 → 放 pp-load 线程算完再重发 hello
            link.localVCodecs = emptyList()
            call = CallManager(link, app)
            call.maxVideoLevelIdx = prefs.videoMaxLevel
            // 出向连接闸门：**前台不受限；后台只放行「已知设备」**
            // （连接过 + 有消息/文件往来，名单见 [trusted]，与聊天记录解耦）。
            // 旧规则是「后台一律拒绝新建连接」——副作用：进程被系统重启、或熄屏解冻后
            // 触发的自动重连第一次就被自己挡掉，连接再也回不来（2026-09-15 用户定调改）。
            // 未知设备仍然只能在**前台**由用户点击建立连接；入向 accept 不受限。
            link.outgoingGate = { ip ->
                if (appVisible) {
                    false
                } else if (trusted.isTrusted(ip)) {
                    appendLog("后台放行连接：$ip 是已知设备（${trusted.describe(ip)}）")
                    false
                } else {
                    true
                }
            }
            // 入向闸门（2026-10-01 定稿）：**后台不接受陌生设备主动连入**。
            // 与出向同判据：已知设备（连接过 + 有消息/文件往来）放行，其余直接拒接。
            // 前台不受限 —— 用户在界面上点过的新设备第一次由 PC 连进来仍正常握手。
            link.incomingGate = { ip -> !appVisible && !trusted.isTrusted(ip) }
            // 保活重连「不降级」判据：本机正在传文件 / 通话（语音或视频）时，
            // 5/10/30s 阶梯走完也不转纯被动，按 30s 继续重试（见 runKeepAliveResolve）。
            link.keepAliveBusy = { call.isBusy() || link.hasAnyActiveTransfer() }
            // ===== 数据流量确认（v2.7）=====
            // 判据一律是**本机是否在运营商数据网络**（不是对端 IP 的网段）：
            // 本机在用流量 → 每次发起都提醒本机用户；对端在用流量 → 由 data_ask 让对端自己提醒。
            link.localOnCellular = { gate.onCellular(prefs.forceLanEnabled) }
            link.localDataConsent = { peerIp, label -> askLocalDataConsent(peerIp, label) }
            // ===== 同设备多地址回落（2026-09-19）=====
            // 点的是 A 地址、实际连上的是同一台设备的 B 地址 → 把「当前对话地址」跟到 B，
            // 否则顶栏/地址菜单回显的还是那个不通的 A，看着像「连上了又没连上」。
            // ⚠ 回调在 pp-connect 线程 → 一律切回主线程再动界面状态。
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
            // ===== 传输速率（气泡显示，2026-09-22）=====
            // 字节级采样在 Link 的发送/接收循环里（`markSpeed`），这里只负责把它放进快照状态。
            // ⚠ 回调在发送线程/接收读线程上 → 必须 `ui{}` 切主线程再写 Compose 状态。
            link.onSpeed = { mid, bps ->
                ui {
                    speeds[mid] = bps
                    speedsAt[mid] = System.currentTimeMillis()
                }
            }
            link.listener = object : Link.Listener {
                override fun onState(state: Link.State, peer: String) {
                    ui {
                        linkState = state
                        peerLabel = peer
                        connectedPeers = link.connectedIps
                        // 会话可用性变了 → 按能力置灰的控件（「视频」按钮）必须重新求值
                        peerCapsTick++
                        // 链路侧自动选中了第一台连上的设备而界面还没选过 → 跟着切过去，别留空白。
                        // 当前选中的设备没有健康会话时也跟着切（Link 侧已改为跟随实际会话）：
                        // 否则停在选择旧 IP/旧设备的状态里，顶栏显示「未连接」、发消息进待发收不到。
                        val t = link.selectedIp
                        // 「有没有健康会话」按**设备**判（组内任意地址），不是精确 IP：
                        // 多地址设备（界面选 .13、会话建在 .210）会被 isConnectedIp 误判成掉线，
                        // 于是界面被链路侧的自动选中拽到别的设备上（2026-09-30）
                        if (t.isNotEmpty() && t != selectedPeerIp &&
                            (selectedPeerIp.isNullOrEmpty() || !link.isDeviceConnected(selectedPeerIp!!))
                        ) {
                            selectedPeerIp = t
                            reloadConversation(t)
                        }
                        // 刚连上只冲刷待发内容；剪贴板不拉不推（读取只由焦点切换触发，接收纯被动）
                        if (state == Link.State.CONNECTED) {
                            link.flushPending()
                        }
                    }
                }

                /**
                 * 会话集合变化（某台连上 / 断开）→ 只刷设备列表的绿点；
                 * 聊天区不受影响 —— 别的设备的连接抖动不会打断当前对话。
                 *
                 * 同时 bump [peerCapsTick]：握手里解析出的 `caps`（vid/cam/vcodec）
                 * 是普通字段，不 bump 就没有任何信号通知界面重新求值
                 * （Link.onHello → notifyPeers() 这条路径是能力就绪的唯一出口）。
                 */
                override fun onPeersChanged() = ui {
                    connectedPeers = link.connectedIps
                    peerCapsTick++
                    // 会话变化可能带着新的「IP → 设备 id」绑定，甚至带来一台新登记的设备
                    // （入向握手时 Link 会 upsert）→ 设备与分组视图整体重建一次
                    reloadPeers()
                    // 通话对端掉线：EndCall 必须由这里兜底。
                    // 链路层掉线只走 onState/onPeersChanged，CallManager 不在那条链路上，
                    // 不补这一下 state 会卡在 TALKING（最坏 6h）→ 计时条一直转、
                    // 「视频」按钮因 callState != IDLE 永久置灰（2026-09-15 实测）。
                    val cp = call.peerIp
                    if (cp.isNotEmpty() && !link.isConnectedIp(cp)) call.onPeerDisconnected(cp)
                }

                override fun onMessageAdded(m: MsgItem) = ui { addMessage(m) }
                override fun onMessageUpdated(m: MsgItem) = ui { updateMessage(m) }
                override fun onMessageRemoved(mid: String) = ui { removeMessage(mid) }

                override fun onHsyncApplied(ip: String, count: Int) = ui {
                    // 补入的旧消息是按到达顺序追加的，按 ts 重排一次才符合时间线。
                    // 比较用**设备组键**：补齐可能发生在同设备的另一个地址上。
                    val cur = selectedPeerIp
                    if (cur != null && prefs.convKeyOf(ip) == prefs.convKeyOf(cur) && count > 0) {
                        val sorted = messages.sortedBy { it.ts }
                        messages.clear()
                        messages.addAll(sorted)
                    }
                    appendLog("历史同步：已从对端补齐 $count 条")
                }
                override fun onClipToClipboard(text: String) = ui { writeClipboard(text) }

                /**
                 * 旧握手协商的遗留回调：本机主动拉取后对端回「缓冲为空」才会触发。
                 * 读取已改为只由焦点切换触发、不再主动拉取 → 恒不会发生，保持空实现。
                 */
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
                // af = 对端期望的语音帧长（20 = 低延迟；0 = 老版本未声明）
                // mport/mudp/msid = 对端声明的媒体通道三元组（0 / false / 空 = 未声明 → 只能走带内档）
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

                // 振铃期逐档媒体协商（任务单 2.5）：只是三个字段覆盖 + 一次标志写入，
                // 不做任何阻塞 ⇒ 与其余通话控制帧同一条 ui{} 主线程口径
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

                // ==================== 数据流量确认（v2.7）====================
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
                    ui {
                        callState = state
                        callPeerIp = peerIp
                        callPeerName = peerName
                        // 只有「正在等待用户决定」的 INCOMING 态才需要保留来电弹窗；
                        // 一旦离开 INCOMING（同意→TALKING / 拒绝·超时·取消→IDLE）立即清空，
                        // 否则弹窗会一直盖屏、且同意/拒绝按钮会因 state 已变而 early-return（点了没反应）。
                        if (state != CallManager.State.INCOMING) {
                            incomingCall = null
                            // 呼入已有结果（接听/拒绝/超时/对端取消）→ 撤掉锁屏来电通知，
                            // 否则那条带全屏意图的通知会一直挂在锁屏上
                            CallNotif.cancel(app)
                        }
                        val talking = state == CallManager.State.TALKING
                        // 通话对端那台设备自己的旋转记忆（R5）：进视频界面前装载好，
                        // 否则会沿用上一个设备的角（VideoCallScreen 用 remember 取初值）。
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
                    // 锁屏接听（2026-09-22）：发一条 HIGH 重要性 + 全屏意图的来电通知 ——
                    // 息屏/锁屏时由系统把通话界面顶到锁屏之上，同时通知上带「接听/挂断」按钮。
                    // 非锁屏状态下这条通知退化成横幅，不影响应用内的来电弹窗。
                    CallNotif.show(app, peerIp, peerName, vid)
                }
                override fun onMediaNotice(text: String) {
                    // 2.9 / S23 的 UI 提示：只提示，**不做任何切换动作**（I17）。
                    // 看门狗线程调过来 ⇒ 按本工程惯例统一 marshal 到 main。
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
                        // v2.7：语音/视频通话**以消息方式留痕**（时长 / 拒绝原因）
                        addCallRecord(peerIp, video, durationMs, reason, outgoing)
                    }
                }
            })

            // 启动即恢复：聊天区按需在"选定设备"后载入（见 reloadConversation）。
            // 剪贴板同步不是「发消息」，聊天区不保留它的痕迹。
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
        localIp = gate.localIpv4()
        // 设备列表里的「本机ip：」列表：网卡可能随 WiFi 开关变化，每轮对一次。
        // 本机在数据网络时**照样列地址**（只取蜂窝的 IPv6 公网地址）：否则整块变成「（暂无）」，
        // 用户没东西可复制给对端（2026-09-19 用户要求）。
        val ips = LocalIps.list(includeCellular = onCellular())
        if (ips != localIps) {
            localIps = ips
            appendLog("本机地址列表更新（${ips.size} 个）：${ips.joinToString(" | ").ifEmpty { "无" }}")
        }
        // 门禁已移除（2026-09-16 用户定调）：启动与运行**不再检测 WiFi**——
        // 有没有 WiFi 都常驻运行；本机在数据网络时发起语音/视频/传文件会先弹流量提醒
        // （v2.7 起为**每次使用都提醒一次**，双向，见 Link.ensureDataConsent）。
        if (!running) startRun()
    }

    internal fun startRun() {
        if (running) return
        running = true
        link.start()
        val d = Discovery(
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
        // 启动即主动扫一轮：有局域网（WiFi/热点/以太网）+ 本机在私网段才真的发（见 startScan 的闸门）
        startScan()
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

    /** 周期性门禁检查：仅 WiFi / 热点下才运行 */
    internal val lanTick = object : Runnable {
        override fun run() {
            if (initialized) refreshLan()
            main.postDelayed(this, LAN_TICK_MS)
        }
    }

    // ==================== 局域网扫描（启动即扫） ====================

    /**
     * 主动扫描一轮局域网设备。**只在启动时 / 回前台自愈时调用**，之后不再主动扫，
     * 改为纯被动：Discovery 的接收线程持续监听 28766，谁广播就把谁写进设备列表。
     *
     * 两道闸门（2026-09-21 起）：
     *  ① **本机没连 WiFi 就不扫**（热点 / 有线以太网同样算「有局域网」）。手机走运营商
     *     数据网时自身地址常常就是 `10.x`（CGNAT 内网地址），照扫会把 254 个运营商侧
     *     地址全打一遍 —— 既扫不到对端，也白耗流量与电。
     *  ② 本机地址必须落在**私网段**（10/8、172.16/12、192.168/16），才有「本网段 /24」
     *     可扫；公网 / 链路本地 / IPv6 一律跳过并如实提示。
     *
     * 扫描窗口（[SCAN_WINDOW_MS]）内 Discovery 会做两件事：广播询问 + **本网段 /24
     * 逐地址单播询问**（本机 `10.1.2.3` → 只扫 `10.1.2.1`~`10.1.2.254`）。
     *
     * 窗口内**回应**的设备会经由 Discovery.onPeer → onPeerDiscovered 自动进入设备列表；
     * 本函数只负责汇报结果：有回应 →「发现 N 台设备」，一个都没有 →「无新设备」。
     *
     * 设为 internal（只有 AppCore 内部调用）：界面上没有「重新扫描」入口，避免反复主动广播。
     */
    internal fun startScan() {
        val d = discovery
        if (!running || d == null) {
            ui { scanMessage = "传输未运行，无法扫描" }
            return
        }
        if (scanning) return
        // 闸门 ①：没 WiFi / 热点 / 以太网 = 本机在数据网络，没有「局域网」可扫
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
            // 扫描结束后自动尝试连接「上次选中的设备」——冷启动无需手动点设备即可恢复会话
            autoConnectLast(found)
        }, "pp-scan").apply { isDaemon = true }.start()
    }

    /**
     * 启动扫描结束后：自动尝试连接一台设备（已连接则跳过）。
     * 只试一次、失败不重试 —— 之后仍由用户点击设备来（重）连接。
     *
     * 目标选择（按优先级）：
     *  ① 上次点过的设备，且这一轮扫描它**在线**（最可靠）；
     *  ② 上次点过的设备仍在设备列表里（对端可能只是没回广播，仍值得试一次）；
     *  ③ 没有记录 / 记录已失效：本轮扫描**只发现一台** → 就是它（用户说的「扫描到的设备」）；
     *  ④ 设备列表里也只有一台 → 就是它；
     *  ⑤ 其余（多台候选、无法判断）→ 不猜，跳过并如实记日志。
     *
     * ⚠ 只认 `lastSelectedIp` 是不够的：手机上常见的情形是对端主动连过来，
     * 本机用户从没点过列表项 → 该 key 为空 → 启动后什么都不做（用户反馈「不会自动连接」）。
     *
     * @param found 本轮扫描真正有回应的设备 IP（UDP 响应集合）
     */
    internal fun autoConnectLast(found: Set<String>) {
        if (link.isConnected()) {
            appendLog("启动自动连接：已有会话，跳过")
            return
        }
        val remembered = prefs.lastSelectedIp
        val list = prefs.peers
        val online = found.filter { it.isNotEmpty() }
        val ip = when {
            remembered.isNotEmpty() && remembered in online -> remembered
            remembered.isNotEmpty() && list.any { it.ip == remembered } -> remembered
            online.size == 1 -> online.first()
            list.size == 1 -> list.first().ip
            else -> ""
        }
        if (ip.isEmpty()) {
            appendLog(
                "启动自动连接：无明确目标，跳过" +
                    "（记录=${remembered.ifEmpty { "无" }}，扫描到 ${online.size} 台，列表 ${list.size} 台）"
            )
            return
        }
        if (link.isConnectedIp(ip)) {
            appendLog("$ip 已有会话，无需自动连接")
            return
        }
        ui { selectedPeerIp = ip }
        reloadConversation(ip)
        // 记住它：下次启动优先按「上次设备」走，不再依赖「列表里只有一台」这种巧合
        prefs.lastSelectedIp = ip
        link.selectAndConnect(ip, peerLabelOf(ip))
        appendLog("启动自动连接 $ip（本轮扫描到 ${online.size} 台）")
    }

    // ==================== 设备列表（左侧栏） ====================

    internal fun reloadPeers() {
        peers.clear()
        peers.addAll(prefs.peers)
        refreshGroups()
        reopenConversationIfKeyChanged()
    }

    /**
     * 当前对话设备的**组键快照**（见 [reopenConversationIfKeyChanged]）。
     */
    internal var convKeySnapshot: String = ""

    /**
     * 身份被识别后，如果当前对话的**组键变了**，重新打开这台设备的聊天界面。
     *
     * 为什么需要：设备身份是**握手 / 收到广播时才知道**的。用户先看到的可能是一条以 IP 当
     * 组键的临时行（手工添加的地址、或只来得及建连、还没握手的设备）；一旦对端自报
     * deviceId，这行就被并进本机早已认识的那台设备 —— 组键从 `IP` 变成 `deviceId`。
     * 组键一变，历史文件（`<deviceId>.jsonl`）、待发队列、旋转角读的都是**另一份**了：
     * 不重载就会停在旧组键读出来的空列表（或旧记录）上，看着像「聊天记录丢了」。
     *
     * 这里只在**真的换了身份**时重载；首次装载（快照为空）交给各自的选中流程，避免重复读盘。
     */
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
        // 兜底去重：行键必须唯一 —— `LazyColumn(items(key = ...))` 遇到重复 key 会直接抛异常
        //（2026-10-05：「同 IP 多设备」时同一台设备一度会出主行 + 别名行两行）。
        // 正常路径由 Prefs.groups() 的构造保证唯一，这里只是最后一道保险，保留先出现的那行。
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

    /**
     * 切换当前设备组使用的地址（聊天顶栏「地址」菜单，R3）。
     *
     * 组的语义是「同一台设备」：切到某个地址后，若那个地址连不上而组内另一个地址
     * 已有健康会话，收发仍走那条会话（见 Link.target 的同组回落），不会因为
     * 点错一个地址就把和这台设备的会话弄丢。
     */
    fun switchGroupIp(ip: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        val k = prefs.convKeyOf(v)
        prefs.setGroupCurIp(k, v)
        selectedPeerIp = v
        prefs.lastSelectedIp = v
        link.selectAndConnect(v, peerLabelOf(v))
        reloadConversation(v)
        refreshGroups()
        appendLog("切换设备地址：${peerLabelOf(v)} → $v")
    }

    /**
     * UDP 发现一台设备：刷新既有条目的发现信息，或把新设备加入列表。
     * @return true 表示列表有变化（需要刷新界面）
     */
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
                // 被动接收唤醒：不在扫描窗口内也要刷新状态文案，
                // 否则会一直停在启动扫描时的「无新设备」上，和列表内容自相矛盾。
                if (!scanning) scanMessage = "发现设备 ${name.ifEmpty { ip }}，已加入列表"
            }
        }
        return changed
    }

    /** 点击设备列表某一项 → 切为当前对话设备并按需连一次（连着的其他设备完全不受影响） */
    fun connectTo(p: Peer) {
        connectTo(p.ip, p.displayName)
    }

    // ==================== 同 IP 多设备：IP 冲突确认（2026-10-05） ====================

    /**
     * 「同 IP 多设备」冲突待确认：非空时界面弹确认框。
     *
     * 同一个地址上已经有**另一台**设备的连接时（两台设备落在同一 IP、各自连进来），
     * 本端不会闷头把连接抢过来 —— 那会变成「界面显示 A、收发其实给 B」。用户手动点
     * 那台设备时挂起在这里问一句，确认后才断开旧连接、把身份切过去（[confirmConflictSwitch]）。
     */
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

    /**
     * 点击设备**组**：按该组当前使用的地址连接（地址在聊天顶栏或编辑弹窗里切换）。
     *
     * 与自动连接的区别（用户 2026-10-05 定调）：自动连接遇到「该地址已被别的设备占用」
     * 直接跳过（[Link.connectTo] 的既有行为）；**手动点击**要问一句是否断开旧连接。
     */
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

    /**
     * 别名行（「同 IP 的另一台设备」）长按「不再显示这台设备」。
     *
     * 忘记它在各地址上的出现记录，并记进忽略表 —— 否则对方每 2s 一次广播，2 秒后这行又回来。
     * 它以后真的占到某个地址（成为活动设备）时，忽略标记自动解除（见 Prefs.unignoreDev）。
     */
    fun forgetDevice(devKey: String) {
        val k = devKey.trim()
        if (k.isEmpty()) return
        prefs.forgetDev(k)
        reloadPeers()
        appendLog("已不再显示这台设备记录（$k）")
    }

    /** 按地址连接（设备组切换地址时复用同一入口） */
    internal fun connectTo(ip: String, label: String?) {
        val v = ip.trim()
        if (v.isEmpty()) return
        selectedPeerIp = v
        // 记住上次选中的设备：下次冷启动扫描完会自动尝试连它一次
        prefs.lastSelectedIp = v
        // 记住「这台设备当前用的地址」，顶栏地址菜单据此回显
        prefs.setGroupCurIp(prefs.convKeyOf(v), v)
        link.selectAndConnect(v, label ?: peerLabelOf(v))
        reloadConversation(v)
        refreshGroups()
        appendLog("当前对话设备：${label ?: peerLabelOf(v)}（$v）")
    }

    /**
     * 载入某台设备的聊天记录到聊天区（点击设备 / 首台自动选中时调用）。
     *
     * 历史是按**设备**（组键 = 对端 deviceId）存的，所以这里只读得到这一台的内容 ——
     * A→B 的消息不会出现在 C 的记录里；同一台设备的另一个地址（换网卡/换 WiFi）
     * 与它共用同一份记录。待发条目同样按**组键**过滤。
     */
    internal fun reloadConversation(peerIp: String?) {
        val ip = peerIp.orEmpty()
        val key = prefs.convKeyOf(ip)
        // 换设备 → 把这台设备自己记住的旋转角装回来（R5）
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

    /**
     * 保存设备（新增或编辑）。oldIp 为 null/空 = 新增；否则按 oldIp 定位后改 IP 与备注。
     *
     * 备注是**设备级**的：同一台设备的其它地址会一起看到同一个备注（见 [saveGroupRemark]）。
     */
    fun savePeer(oldIp: String?, ip: String, remark: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
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

    // ==================== 设备与分组操作（界面用，2026-09-16） ====================

    /** 对某台设备（组）设置备注 —— 仅本机可见，不上协议 */
    fun saveGroupRemark(key: String, remark: String) {
        prefs.setGroupRemark(key, remark)
        reloadPeers()
        appendLog("已保存备注：${remark.ifBlank { "（清空）" }}")
    }

    /**
     * 手动给某台设备补一个地址（用户明确知道这两个地址是同一台机器）。
     *
     * 绑定方向是 ip → 该设备 id：之后从新地址连上时，聊天记录与备注直接沿用这一组，
     * 不会被当成新设备。地址合法性与端口由 Link 负责，这里只登记。
     */
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

    /** 删除整台设备（组内所有地址 + 该设备的备注记忆） */
    fun deleteGroup(g: PeerGroup) {
        g.ips.forEach { prefs.removePeer(it) }
        prefs.setGroupRemark(g.key, "")
        if (selectedPeerIp != null && prefs.convKeyOf(selectedPeerIp!!) == g.key) {
            selectedPeerIp = null
            messages.clear()
        }
        reloadPeers()
        appendLog("已删除设备 ${g.displayName}（${g.ips.size} 个地址）")
    }

    /** 从设备列表删除一台设备（保留旧入口：按单个地址删） */
    fun deletePeer(p: Peer) {
        prefs.removePeer(p.ip)
        if (selectedPeerIp == p.ip) {
            // 只是把"当前对话"清空；已连着的会话交给 Link 自己判定（断了就是断了）
            selectedPeerIp = null
            messages.clear()
        }
        reloadPeers()
        appendLog("已删除设备 ${p.displayName}（${p.ip}）")
    }

    /** 某台设备当前是否在线（组内任意地址有会话即在线；设备列表逐台打点） */
    fun isGroupOnline(g: PeerGroup): Boolean =
        if (g.alt) link.isPeerConnected(g.key) else g.ips.any { it in connectedPeers }

    /**
     * 本机设备名（首次启动生成/用户可改）——**唯一允许出现在出网字段里的名字**。
     *
     * 备注（[peerLabelOf] 返回的东西）是「仅自己可见」的：hello.name、beacon.name、
     * call_invite.name 一律用它，绝不把用户对别人的私有备注发出去。
     */
    val myDeviceName: String get() = prefs.deviceName

    /**
     * 按消息归属地址找**该设备**的显示名（备注挂在设备上，同组的任何地址都得到同一个名字）。
     * 找不到回落「电脑」——IP 不在列表（老历史记录）时兜底。
     *
     * ⚠ 返回的名字**只用于本机界面**：备注是用户私有的，绝不能写进出网字段
     * （hello.name / beacon.name / call_invite.name 一律用 prefs.deviceName）。
     */
    fun peerLabelOf(ip: String?): String {
        if (ip.isNullOrEmpty()) return "电脑"
        val key = prefs.convKeyOf(ip)
        val g = peerGroups.firstOrNull { it.key == key } ?: prefs.groupOfIp(ip)
        return g?.displayName ?: ip
    }

    // ==================== 消息 ====================

    /**
     * mid → 归属对端 IP。updated 帧（进度/完成/失败）不带 peerIp，终态落历史时回这里查；
     * LRU 封顶 2000 条。只在主线程（ui{}）访问，无需加锁。
     */
    internal val midPeer = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 2000
    }

    /**
     * **明确移除过**的 mid（[removeMessage] 记一笔，LRU 封顶 2000）。
     *
     * 唯一用途：给 [updateMessage] 的「气泡不在列表里就补建」兜底 —— 用户长按删掉的、
     * 或链路取消掉的（`file_cancel`）那条，不许被随后到达的进度 / 终态帧复活。
     * 只记内存：重启后历史里本来就没有它（删除时同步清了历史），不会自己冒出来。
     */
    internal val removedMids = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > 2000
    }

    /**
     * 这条消息是否属于聊天区当前展示的那台设备。
     *
     * 判据是**设备组键**而不是 IP 字符串：同一台设备的两个地址（换网卡/热点）
     * 归同一份会话，消息里带的还是当时那个 IP，用 IP 比较会把它们判成两台设备。
     */
    internal fun belongsToCurrent(m: MsgItem): Boolean {
        val sel = selectedPeerIp ?: return m.peerIp.isEmpty()
        if (m.peerIp.isEmpty()) return true
        return prefs.convKeyOf(m.peerIp) == prefs.convKeyOf(sel)
    }

    /**
     * 新出现一条超过 [BIG_TRANSFER_BYTES] 的文件/文件夹气泡 → 弹一次提速提示（2026-09-22 用户要求）。
     *
     * 判据是「这条 mid 还不在 [messages] 里」（= 首次出现），不是看进度：
     * 发/收两端都会先 emit 一条带 `fileName` + `fileSize` 的气泡，之后的进度帧与终态帧
     * 走 [updateMessage] 合并 —— 所以只挂在 [addMessage] 上 + 按 mid 去重就恰好一次。
     * 不走 [reloadConversation]（历史是整批塞进列表，不经这里）→ 切设备/重启不会重弹。
     *
     * 两个方向都提示（收/发都算「传输」）；文件夹看聚合总大小。
     */
    private fun maybeHintBigTransfer(m: MsgItem) {
        if (m.kind != Kind.FILE || m.fileSize <= BIG_TRANSFER_BYTES) return
        if (m.mid.isEmpty() || m.mid == bigHintMid) return
        if (messages.any { it.mid == m.mid }) return
        bigHintMid = m.mid
        bigTransferHint = BIG_TRANSFER_HINT
        bigTransferHintTick++
        appendLog("本次传输约 ${m.fileSize / (1024 * 1024)}MB（超过 2GB），已提示改用 WiFi/热点提速")
    }

    /**
     * 新增一条消息。
     *
     * **历史一律落盘**（Store 按 `m.peerIp` 分文件）—— 别的设备的往来也留档，切过去就能看到；
     * 但**界面只收留当前对话设备的那条**：A→B 的内容不会画进 C 的聊天区。
     */
    internal fun addMessage(m: MsgItem) {
        // 接收侧文本命中名单 → 当作没收到：不落盘、不进归属表、不上屏、不影响未读。
        // ack 是在 Link 的 text 分支里直接回的，不经这里，所以发送端照旧看到「发送成功」。
        if (m.dir == Dir.PEER && m.kind == Kind.TEXT && TransferKeywords.isSilentlyFiltered(m.text)) return
        if (m.mid.isNotEmpty() && m.peerIp.isNotEmpty()) midPeer[m.mid] = m.peerIp
        // 大文件（>2GiB）提速提示（2026-09-22）：只在**气泡首次出现**这一刻弹
        maybeHintBigTransfer(m)
        // 终态帧（收完/失败）顺手回收速率快照 —— 气泡里的数字本来就按「样本过期」自动隐藏
        if (m.kind == Kind.FILE && (m.status == Status.OK || m.status == Status.FAILED)) clearSpeed(m.mid)
        // 与 PC 端对称：added 一律先落盘（不再限定 dir==PEER || status==OK）。
        // 自己发的消息 added 行（带完整 fileName/ts）必须先于终态裸帧进历史 ——
        // 否则发送期间切走设备时，终态裸帧（只有 mid+status）会成为历史第一行，
        // 重载后 mergeLater 拿不到前行补齐，气泡就成了空文本/空文件名。
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
        // 终态补写历史：自己发出的文件完成/失败时只有 updated 一个回调，不在这里落盘的话，
        // 重载/重启后这条就凭空消失了（与 PC 端同源）。裸状态帧不带 peerIp，
        // 必须先归属（自带 peerIp，或能从当前会话列表里找到原消息）才落，否则会写错文件。
        // 只落「带实质内容」的帧：裸状态帧（fileName/text 都空，如对端 ack）只更新 UI，不写历史。
        val hasBody = m.fileName.isNotEmpty() || m.text.isNotEmpty()
        // DENIED 也要落盘（v2.7）：否则重启后那条「数据流量未同意」的文件又变成「待发送」，
        // 红色叹号与重试入口一起消失。
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
            // 这条 mid **界面上还没有气泡** —— 以前这里什么都不做，于是进度 / 完成帧被整个
            // 丢弃：表现就是「必须再切一次会话才看得到正确的状态」（切会话会走
            // [reloadConversation] 从历史重建气泡，而历史是照写的，所以切过去就对了）。
            //
            // 什么时候会没有气泡（都不是「消息丢了」，只是界面这一份被清掉/还没建）：
            //  - 重连时的 reloadConversation 抢在状态帧前面把在途气泡清掉（它按磁盘重建，
            //    这一瞬间的状态帧就打在空列表上）；
            //  - 发送端「排队发送」：气泡可能还没建（历史里有、界面列表里没有）。
            // PC 端 ChatWindow.onMessageUpdated 早在 2026-09-19 就补建气泡治过同一个坑
            //（注释原文：「必须再切一次会话才看得到」），这里与它对奔。
            //
            // 补建的前提是**归属与身份都确定**：
            //  - belongsToCurrent(m)（上面已判）= 属于当前这台设备，不会串台；
            //  - midPeer 里有它（[addMessage] 记的）= 本进程确实见过这条消息，
            //    不给「已经被删掉 / 别人的」mid 凭空造气泡；
            //  - removedMids 里没有它 = 这条不是**明确删掉**的那条（见 [removedMids]）；
            //  - hasBody = 帧里带实质内容（裸状态帧不造气泡，避免出现空文件名/空文本的泡）。
            messages.add(m)
            if (messages.size > MAX_UI_MSGS) {
                repeat(messages.size - MAX_UI_MSGS) { messages.removeAt(0) }
            }
        }
        pendingCount = store.loadPending().size
    }

    /**
     * 一条消息被移除（文件被任一方取消）。取消后**两端都不再显示**：
     * 从界面列表、待发队列、本地历史（所有设备的历史文件里找）三处一起删掉，
     * 避免重进应用又冒出来。
     */
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

    /**
     * 长按删除一条消息（v2.4，删除同步）：
     *  - 文本/图片：先发 `msg_del` 通知对端（对端在线且 hd=1 才生效），再删本地三处 ——
     *    两边在线时双方同时消失；对端离线时只删本机，这条内容下次连接会被 hsync 自动补回来。
     *  - 文件/文件夹：只删本机记录，不通知对端、也不会被补回（已落盘的实际文件不动）。
     */
    fun deleteMessage(mid: String) {
        val m = messages.firstOrNull { it.mid == mid } ?: return
        val syncable = syncableKind(m)
        if (syncable) {
            link.deleteMessage(m.peerIp.ifEmpty { selectedPeerIp ?: "" }, mid)
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

    /**
     * 发文件。数据流量确认（本机提醒 + 对端确认）已下沉到 `Link.sendFile`，
     * 因为它要在「先出气泡(PENDING)」和「真正开传(SENDING)」之间完成 —— 拒绝时
     * 气泡留在聊天里带红色叹号可重试（见 [retryFileSend]）。
     */
    fun sendFile(uri: Uri) {
        if (!requireSelectedPeer()) return
        sendFileInternal(uri)
    }

    /** 真正发起一次文件发送（数据流量确认由 Link 内部完成）；分享批量路径复用 */
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

    /**
     * 发送**多个文件**（内置文件浏览器多选后点「发送」；与「分享」批量同一口径）。
     *
     * 为什么不逐个调 [sendFile]：那样每个文件都会单独弹一次「数据流量提醒」，
     * 同一个动作被问 N 次。这里沿用 `shareTo` 的口径 —— **一批只确认一次**，
     * 通过后逐个 `sendFileInternal(preGranted = true)` 进发送串行队列
     * （一次仍只传一个文件，见 Link.fileSendExec）。
     *
     * 设备没连上时不问（那时也问不到对端）：逐个按各自身份进待发队列，连上自动补发。
     */
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
                        if (consent == DataConsent.REMOTE_REFUSED) {
                            toast(DataConsentText.peerRefusedText(DataConsentText.KIND_FILE))
                        } else {
                            toast("已取消本次发送")
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

    /**
     * 重试一条「数据流量未同意」**或「文件排他暂停」**的文件（气泡末尾的红色叹号 / 「继续」触发）。
     * 重新完整走一遍提醒流程；同意后由 Link 回调把这条挪到最新位置再开传。
     *
     * 2.12 / D16：状态判据放宽到 DENIED ∪ PAUSED —— PC 端 `Link.retryFileSend(Msg)` 从来不看状态，
     * 这里不收 PAUSED 的话，安卓气泡上的「继续」点了没反应（两端行为不一致）。
     */
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

    /** 把一条消息挪到时间线最末（用户要求：重试同意后「把信息移动到最新一条」） */
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

    // ==================== 数据流量确认（v2.7，双向） ====================

    /**
     * 本机「数据流量提醒」（**发起方向**）：非空时界面弹确认框。
     *
     * 由 [askLocalDataConsent] 在后台线程投递到主线程并**阻塞等待**，
     * 用户点「同意/取消」→ [acceptDataNet]/[cancelDataNet] → [done] 唤醒那个线程。
     */
    class DataConsentAsk(val peerIp: String, val label: String, val done: (Boolean) -> Unit)

    var dataNetConfirm by mutableStateOf<DataConsentAsk?>(null)
        internal set

    /**
     * 对端请求「用数据流量发给我」（**接收方向**）：非空时界面弹接收提醒框。
     * 本机不在数据网络时**不会**走到这里（直接自动同意，不打扰用户）。
     */
    class DataConsentIncoming(val peerIp: String, val mid: String, val label: String)

    var dataNetIncoming by mutableStateOf<DataConsentIncoming?>(null)
        internal set

    /**
     * 本机当前是否在运营商数据网络上。
     *
     * **每次使用都重新判定、绝不记忆**（用户 2026-09-17 定调：每次用这个功能就提醒一次）。
     */
    fun onCellular(): Boolean =
        runCatching { gate.onCellular(prefs.forceLanEnabled) }.getOrDefault(false)

    /**
     * 弹「数据网络提醒」并**阻塞**等本机用户选择（供 Link 在专用线程里调用）。
     *
     * 由调用方保证不在主线程/读线程 —— 这里再加一道保险：真在主线程调用时按「未同意」处理，
     * 宁可取消一次操作，也绝不 ANR 或卡住会话读循环。
     */
    internal fun askLocalDataConsent(peerIp: String, label: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "数据流量提醒不能在主线程等待用户选择，按未同意处理")
            return false
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        ui {
            appendLog("本机走数据网络，发起「$label」前需确认数据流量")
            dataNetConfirm = DataConsentAsk(peerIp, label) { r ->
                ok.set(r)
                latch.countDown()
            }
        }
        val waited = runCatching {
            latch.await(DATA_CONSENT_WAIT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!waited) {
            ui { dataNetConfirm = null }
            appendLog("数据流量提醒 $DATA_CONSENT_WAIT_SEC 秒未确认，本次操作已取消")
        }
        return ok.get()
    }

    /** 用户同意本机使用数据流量（本次操作继续） */
    fun acceptDataNet() = finishDataConsent(true)

    /** 用户拒绝本机使用数据流量（本次操作取消；文件会留在聊天里带红色叹号可重试） */
    fun cancelDataNet() = finishDataConsent(false)

    internal fun finishDataConsent(ok: Boolean) {
        val c = dataNetConfirm ?: return
        dataNetConfirm = null
        c.done(ok)
    }

    /** 接收方向：同意对端用数据流量发过来（对方才开始发；本机此时才创建/接收消息） */
    fun acceptIncomingData() {
        val c = dataNetIncoming ?: return
        dataNetIncoming = null
        link.replyDataAsk(c.peerIp, c.mid, true)
        appendLog("已同意对端「${c.label}」使用数据流量传输")
    }

    /**
     * 接收方向：拒绝。
     * **本机什么都不记录**（不生成气泡、不写历史）—— 只回一帧让对端如实提示。
     */
    fun rejectIncomingData() {
        val c = dataNetIncoming ?: return
        dataNetIncoming = null
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
        dataNetIncoming = DataConsentIncoming(peerIp, mid, label)
    }

    // ==================== 系统「分享」进来：选设备 → 开始传 ====================

    /**
     * 待处理的分享内容：非空时主界面弹「发送到哪台设备」。
     * 由 MainActivity 解析 ACTION_SEND / ACTION_SEND_MULTIPLE 后写入（见 util/ShareIn.kt）。
     */
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

    /**
     * 选定目标设备 → 定向连接并开始传输。
     *
     * 分享给的 Uri 读权限是**临时**的（跟着收到 Intent 的 Activity 走，不是持久授权），所以：
     *  ① 先等最多 [SHARE_CONNECT_WAIT_MS]：链路一起就直接从原 Uri 发，不做多余的一次落盘；
     *  ② 等不到就先把内容转存进应用私有目录（cache/share_inbox/…）再入队 ——
     *     否则待发队列里那条在应用重启后必然打不开源文件，变成永远发不出去的僵尸条目。
     */
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
                // 该设备组任意地址有会话即算就绪（同组回落，见 Link.sessionByGroup）
                if (link.isDeviceConnected(ip)) {
                    ready = true
                    break
                }
                runCatching { Thread.sleep(150) }
            }
            // 数据流量确认：**一批只提醒一次**（这是同一个「使用功能」的动作），
            // 确认通过后逐个文件走 preGranted 直接开传，不重复弹框。
            // 链路还没起来时不问（那时也问不到对端），按老口径进待发队列。
            if (ready) {
                val label = "发送分享内容（${payload.items.size} 项）"
                val consent = link.ensureDataConsent(
                    ip, link.newConsentMid(), DataConsentText.KIND_FILE, label
                )
                if (consent != DataConsent.OK) {
                    ui {
                        if (consent == DataConsent.REMOTE_REFUSED) {
                            toast(DataConsentText.peerRefusedText(DataConsentText.KIND_FILE))
                        } else {
                            toast("已取消本次分享发送")
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

    /**
     * 把分享进来的内容复制进应用私有目录，返回可长期读取的 `file://` Uri。
     * 每个文件放一个子目录（`<时间戳>/<原名>`）：这样文件名保持原样
     *（FileIO 对 file:// 取 path 末段做展示名），也不会互相覆盖。
     */
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

    /**
     * 剪贴板同步模式切换。开启时立刻做一次读取推送（用户此刻在应用内、持有焦点，读得到）。
     */
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

    /**
     * 「清空聊天记录」：**逐条删除**（不是整删历史文件），与 PC 端 `clearCurrentConversation`
     * 同一口径（2026-09-21 用户定调）。
     *
     * <p>为什么必须逐条：整删文件只清了本机 —— 对端历史里那些文本/图片还在，它一连上，
     * hsync 对账发现「你有我没有」就**补回来**，用户看到的就是「清空根本没用」。
     * 逐条删会把文本/图片的 mid 按协议 §4.7 用 `msg_del` 通知对端一起删。
     *
     * <p>范围：有选定设备 → 只清这一台（历史按**设备组键**存，该设备别的地址的往来一起清）；
     * 没选设备（设置页里直接点）→ 清全部设备，逐台走同一条路径。
     *
     * <p>枚举是磁盘活（要读整份历史）→ 后台线程做，结果投到 [clearConfirm] 交用户确认；
     * 真正执行在 [confirmClearHistory]。**有传输在途时拒绝**（枚举到的 mid 里就有正在传的那条）。
     */
    fun clearHistory() {
        if (clearConfirm != null) return
        val ip = selectedPeerIp
        Thread({
            val parts = if (ip.isNullOrEmpty()) planAllConversations() else planConversation(ip)
            if (parts.isEmpty()) {
                appendLog("没有可清空的聊天记录")
                return@Thread
            }
            val busy = parts.filter { link.hasActiveTransfer(it.ip) }
            if (busy.isNotEmpty()) {
                appendLog("还有传输任务进行中（${busy.size} 台设备）：等传完再清空")
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

    /** 用户点了「清空」：[clearConfirm] 里的计划真正执行 —— 本地一次清完 + 逐条通知对端 */
    fun confirmClearHistory() {
        val p = clearConfirm ?: return
        clearConfirm = null
        var notified = 0
        val cleared = HashSet<String>()
        for (part in p.parts) {
            cleared.addAll(part.all)
            if (part.sync.isNotEmpty() && part.addr.isNotEmpty()) {
                // 返回 0 = 对端不在线 / 不支持 hd：这时只清了本机，下面会如实提示
                notified += link.deleteMessages(part.addr, part.sync)
            }
            store.removePending(part.all)
            store.removeFromHistory(part.ip, part.all)
        }
        if (p.whole) messages.clear() else messages.removeAll { it.mid in cleared }
        // 清掉的那些也记进 [removedMids]：万一条目里混着一条在途传输（枚举与确认之间
        // 刚好排进来的），它的终态帧不许把气泡补回来。逐个 put（不用 putAll）——
        // LRU 的封顶只在 put 里生效。
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

    /**
     * 枚举**一台设备**要清的 mid：历史全量 + 待发，并分出参与删除同步的那部分（文本 + 图片）。
     *
     * 历史按**组键**存（`Store.keyResolver` → `Prefs.convKeyOf`），所以同一台设备另一个地址的
     * 往来也在同一份文件里，一并清掉。`max = Int.MAX_VALUE` = 不截断（UI 只装 500 条，这里要清干净）。
     */
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
        // 地址得是「能寻址的」才谈得上通知对端：孤立历史文件（设备已从名单删掉）传空串，
        // 此时 sync 条目只清本机，确认框里会单独说明。
        return listOf(ClearPart(ip = ip, addr = if (isAddressable(ip)) ip else "", all = all, sync = sync))
    }

    /**
     * 枚举**全部设备**：候选地址取「设备名单 + 当前已连 + 待发归属 + 聊天区消息归属」，
     * 按组键去重（一台设备只清一次，代表地址优先取当前连着的那条）；再把**不在名单里的
     * 孤立历史文件**也兜进来，逐台走 [planConversation]。
     */
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

    /**
     * 一条消息是否**参与删除同步**（协议 §4.7：只有文本 + 图片）。
     * 文件/文件夹/剪贴板/通话记录只删本机记录，不通知对端、也不会被 hsync 补回。
     */
    internal fun syncableKind(m: MsgItem): Boolean =
        m.kind == Kind.TEXT ||
            (m.kind == Kind.FILE && !m.isDir && FileIO.isImageFileName(m.fileName))

    /** 清空完成提示（对方不在线时会补回来）——用户点掉后清空 */
    fun dismissClearWarn() {
        clearWarn = null
    }

    // ==================== 已知设备（后台可连接名单） ====================

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

    /**
     * 复制一个**本机地址**（设备列表里点一下那行 IP）：写本机剪贴板 + **当场推给已连接的对端**。
     *
     * 为什么不走「焦点切换时自动读剪贴板」那条路：那条路是给**用户随手复制的内容**用的，
     * 而这里的目的很明确 —— 让对端能直接粘到「＋设备」的 IP 框里。等切焦点再推就太晚了，
     * 所以当场推一次；同时把 sha1 记进 lastPushedSha1，免得回前台时再把同一份内容推第二遍。
     *
     * 复用 `link.sendClip`（它内部自带 `io{}`，不会在主线程写 socket）。
     */
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

    /**
     * 长按消息菜单的「复制文本」（2026-09-19）：把这条纯文本消息写进**本机剪贴板**。
     *
     * 写完**顺手走一遍既有的剪贴板同步链路**（[pushClipIfChanged]），与「用户自己在别的应用里
     * 复制」表现完全一致：同步模式开着就推给已连接的对端、关着就只写本机 —— 不在这里自己
     * `sendClip`，那样会把用户的同步开关当空气。
     *
     * ⚠ 必须 `force = true`：`Clip.write` 会把这条内容的 sha1 记进 `lastPulledSha1`
     *（那个字段的本意是「刚从对端拉回来、别回推」），不 force 的话 `pushClipIfChanged`
     * 会把我们刚写进去的内容当回声吃掉，从而**永远不推**。
     *
     * `clip.write` 内部已处理线程：非主线程会 post 到主线程（Android 12+ 对后台写剪贴板有限制），
     * 这里仍包一层 `ui{}` 与 [copyForAdd] 保持一致。
     *
     * @param hint 提示文案（2026-10-05 起可定制：「复制链接」入口要提示得具体一点，
     *   默认值就是原来那句「已复制文本」，既有调用方的表现不变）。
     */
    fun copyTextToClipboard(text: String, hint: String = "已复制文本") {
        if (text.isEmpty()) return
        ui {
            clip.write(text)
            if (prefs.clipSyncMode) pushClipIfChanged(force = true)
        }
        toast(hint)
        appendLog("复制文本（${text.length} 字）到本机剪贴板")
    }

    /** 短提示：主线程弹 Toast，用于操作无可见反馈时给用户明确回应（避免「点了没反应」错觉） */
    fun toast(msg: String) {
        val ctx = app
        main.post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 语音通话操作 ====================


    /**
     * 由 MainActivity 注入：申请录音权限的入口；参数是「授权后要做的事」。
     *
     * 必须是「带延续」的形式 —— 否则从「视频」按钮触发的录音授权，回调会误走语音发起
     * （用户点视频却打了一通语音电话）。
     */
    var recordPermRequester: ((() -> Unit) -> Unit)? = null

    /**
     * 由 MainActivity 注入：申请相机权限的入口；参数同样是「授权后要做的事」。
     *
     * 与录音权限一样必须是「带延续」的形式 —— 视频来电的接听、以及首次发起视频，
     * 都要在授权回调里继续原动作，而不是被固定成"发起视频"（语音来电接听会走错分支）。
     */
    var cameraPermRequester: ((() -> Unit) -> Unit)? = null


    // ==================== 视频通话操作（R1） ====================

    /** 视频通话总开关（设置界面用） */
    var videoEnabled: Boolean
        get() = prefs.videoEnabled
        set(v) {
            prefs.videoEnabled = v
            link.localVidCap = v
            link.resendHello()
        }

    /**
     * 档位上限（两端协商与用户设置取 min）：0 = 流畅 480P/500k，
     * 1 = 标清 720P/1.5M，2 = 高清 1080P/2M，3 = 超清 1080P/5M（默认 = 不设限）。
     */
    var videoMaxLevel: Int
        get() = prefs.videoMaxLevel
        set(v) {
            prefs.videoMaxLevel = v
            call.maxVideoLevelIdx = prefs.videoMaxLevel
        }

    /**
     * 「旋转」钮记忆的**最终显示角**（2026-09-16 用户定调，两个独立 + **持久化**）：
     *  - [videoRemoteRot] 对方画面；[videoLocalRot] 本地小窗预览。
     *
     * 均为**纯本机渲染侧**修正（ChatScreen.applyRemote/LocalTransform 读取），
     * 不动 `video_cfg.rot` 跨端协议、不影响 UI 层。-1 = 从未手调 → 渲染侧走
     * 自适应基线。记住的是**绝对角度**而非叠加量：基准角（对端 video_cfg.rot /
     * 采集重启后的 rotationDegrees）通话间会漂，钉绝对角度才能「不自动复原」。
     *
     * 2026-09-19 起每个值**按摄像头朝向分两套**存在 prefs 里（前置 / 后置各一套）：
     *  - 对方画面：按**对端**摄像头（[videoRemoteRotFor]）；
     *  - 本地小窗：按**本机**摄像头（[videoLocalRotFor]）。
     * 这两个字段只作「当前生效值」的缓存（跟最后读/写的那套同步），权威值在 prefs。
     *
     * ⚠ 初值**不能**写成 `= prefs.videoRemoteRot`：object 属性初始化器在
     * `<clinit>`（类加载）执行，而 `prefs` 到 `init(ctx)` 才赋值 → 启动即
     * `lateinit property prefs has not been initialized` 闪退（09-16 实锤）。
     * 真实初值在 init() 里回填。
     */
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

/**
 * 「清空聊天记录」的一台设备要清的 mid 清单（枚举阶段产出，确认后执行）。
 *
 * @param ip   历史文件定位用（一般是设备代表地址；孤立历史文件时是它的组键）
 * @param addr 通知对端用的地址；**空串 = 找不到对应设备**（设备已从名单里删掉），
 *             这种只能清本机，确认框里会单独写一行说明
 * @param all  要清掉的**全部** mid（含文件/文件夹/剪贴板等不参与同步的类型）
 * @param sync 其中「文本 + 图片」的 mid（要按协议 §4.7 发 `msg_del` 通知对端的那部分）
 */
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
