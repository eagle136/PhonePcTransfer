package com.phonepctransfer.ui

import com.phonepctransfer.call.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// 注意:`TextUnit.isSp`是TextUnit的成员属性(不是顶层扩展)→不要写import,写了会Unresolved reference
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.phonepctransfer.AppCore
import com.phonepctransfer.ClearPlan
import com.phonepctransfer.R
import com.phonepctransfer.data.Peer
import com.phonepctransfer.data.PeerGroup
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.net.Link
import com.phonepctransfer.voice.CallManager
import com.phonepctransfer.video.VideoLevel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 主界面:左侧设备列表+右侧聊天。
 * 列表展开/收起:聊天区右滑展开、左滑收起;展开时点聊天区空白也收起(输入法开着则一并收起);左上角"加长三横"切换。
 * 设备列表顶行:左="清空"(清当前对话记录,带确认),右="设置"入口;点击设备即定向连接,长按可改备注/IP */
@Composable
fun ChatScreen() {
    var showSettings by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(true) }
    // 内置文件浏览器开关:单窗口内的全屏页面,取代系统文件选择器;
    // 「+」菜单里的"发送文件/发送文件夹"都开它(同一个页面:长按文件夹即选文件夹)
    var browserOpen by remember { mutableStateOf(false) }
    // 侧栏展开/收起进度(0=完全收起,1=完全展开)。
    // 自驱动画:本机系统动画时长被置0、Compose动画会瞬跳,用withFrameNanos逐帧推进;状态放本层(收起动画跑完子组件退出组合,放子组件里会被重置致下次展开又瞬跳)
    var sideAnim by remember { mutableStateOf(1f) }
    LaunchedEffect(listOpen) {
        val from = sideAnim
        val to = if (listOpen) 1f else 0f
        if (from == to) return@LaunchedEffect
        var t0 = 0L
        while (true) {
            var done = false
            withFrameNanos { now ->
                if (t0 == 0L) t0 = now
                val ms = (now - t0) / 1_000_000f
                val p = (ms / SIDE_ANIM_MS).coerceIn(0f, 1f)
                val e = 1f - (1f - p) * (1f - p) * (1f - p)   // easeOutCubic：起步快、收尾稳
                sideAnim = from + (to - from) * e
                if (p >= 1f) done = true
            }
            if (done) break
        }
        sideAnim = to
    }
    var editing by remember { mutableStateOf<PeerGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MsgItem?>(null) }

    // 点聊天区空白处要能收起输入法。这里取focusManager(清焦点;不清的话再点输入框仍是聚焦态,系统不会再弹IME——只调hide()的经典坑)与view
    // (拿window用;实际收起动作见文件末尾的[dismissIme])
    val focusManager = LocalFocusManager.current
    val hostView = LocalView.current

    val listState = rememberLazyListState()
    val msgs = AppCore.messages

    // 新消息到达后滚到底部（用即时滚动，不用动画）
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) runCatching { listState.scrollToItem(msgs.lastIndex) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 边距口径:背景铺满、只有顶部让开通知栏、且通知栏那一条要用顶栏的底色(不是页面底色);
        // 通知栏内边距下沉到各面板自己的Surface(顶栏[HeaderBar]、左侧[DeviceListPanel]):面板底色铺到屏幕最顶边,时间/电量条与顶栏同色
        Row(modifier = Modifier.fillMaxSize()) {
            // 侧栏(含展开/收起动画)拆成独立子composable:动画进度在它内部读取(deferred read),每帧只重组这一块,不带着整个聊天区一起重组
            SidePanel(
                anim = { sideAnim },
                onSettings = { showSettings = true },
                onPick = { AppCore.connectToGroup(it) },
                onEdit = { editing = it },
                onAdd = { adding = true }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 抽屉手势:聊天区右滑展开、左滑收起(一次性阈值判定,不做动画)
                    .drawerSwipe(
                        open = listOpen,
                        onOpen = { listOpen = true },
                        onClose = { listOpen = false }
                    )
            ) {
                // 列表展开时顶栏不显示备注(列表里已经列着备注,顶栏再写一遍是重复)
                HeaderBar(onToggleList = { listOpen = !listOpen }, showRemark = !listOpen)

                CallBanner()

                // 点聊天区空白:①列表展开⇒收起列表 ②输入法开着⇒一并收起;
                // 气泡里的按钮/图片自己消费点击,只会命中没被消费的空当。
                // ⚠pointerInput的键必须是listOpen(不能写Unit):lambda只安装一次、捕获的是首次的值⇒永远停在true,列表收起后再点空白不生效;这里修饰符恒在,变化量要挪到键上
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(listOpen) {
                            detectTapGestures {
                                if (listOpen) listOpen = false
                                dismissIme(focusManager, hostView)
                            }
                        }
                ) {
                    if (msgs.isEmpty()) {
                        Text(
                            text = "还没有消息。\n点左侧设备连接，然后在下面输入文本，或点「+」发送文件 / 打电话。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(msgs, key = { it.mid }) { m ->
                                MessageBubble(
                                    m = m,
                                    peerName = AppCore.peerLabelOf(m.peerIp),
                                    onOpenImage = { viewingImage = it },
                                    onDelete = { deleting = m }
                                )
                            }
                        }
                    }
                }

                BottomArea(
                    compact = listOpen,
                    onPickFile = { browserOpen = true },
                    onPickFolder = { browserOpen = true }
                )
            }
        }
    }

    // 内置文件浏览器:盖在整个聊天界面之上(单窗口内换页,不新开Activity)
    if (browserOpen) FileBrowser(onClose = { browserOpen = false })

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    if (adding) PeerEditDialog(initial = null, onDismiss = { adding = false })
    editing?.let { p -> PeerEditDialog(initial = p, onDismiss = { editing = null }) }
    deleting?.let { m ->
        DeleteMessageDialog(
            onDismiss = { deleting = null },
            onConfirm = {
                AppCore.deleteMessage(m.mid)
                deleting = null
            }
        )
    }
    viewingImage?.let { uri ->
        ImageViewerDialog(localUri = uri, onClose = { viewingImage = null })
    }
    // "清空聊天记录"确认框(逐条删除):枚举完成后AppCore才把计划投出来
    AppCore.clearConfirm?.let { p ->
        ClearHistoryDialog(
            plan = p,
            onDismiss = { AppCore.cancelClearHistory() },
            onConfirm = { AppCore.confirmClearHistory() }
        )
    }
    // 清完之后「该通知的没通知到」的如实提示
    AppCore.clearWarn?.let { w ->
        ClearWarnDialog(msg = w, onClose = { AppCore.dismissClearWarn() })
    }
    // 数据网络提醒·发起方向(本机在用数据流量,且即将发文件/语音/视频)——每次使用都弹一次,不记忆已同意的对端
    AppCore.dataNetConfirm?.let { c ->
        DataNetConfirmDialog(
            label = c.label,
            onDismiss = { AppCore.cancelDataNet() },
            onConfirm = { AppCore.acceptDataNet() }
        )
    }
    // 数据网络提醒·接收方向(对端在用数据流量,问本机是否同意接收)——拒绝时本机什么都不记录,只回一帧让对端如实提示
    AppCore.dataNetIncoming?.let { c ->
        IncomingDataConfirmDialog(
            label = c.label,
            onRefuse = { AppCore.rejectIncomingData() },
            onAgree = { AppCore.acceptIncomingData() }
        )
    }
    // 从系统"分享"进来:先问发给哪台设备(见ShareDialog.kt)
    ShareTargetDialog()
    // 同IP多设备冲突:手动点了一台"同一IP上的另一台设备"时问一句,确认后才断开旧连接、把该地址的身份切过去
    AppCore.conflictSwitch?.let { c ->
        IpConflictDialog(
            ip = c.ip,
            holder = c.holder,
            name = c.name,
            onDismiss = { AppCore.cancelConflictSwitch() },
            onConfirm = { AppCore.confirmConflictSwitch() }
        )
    }
    // 呼入弹窗（显示对方 IP + 备注，同意/拒绝）
    IncomingCallDialog()
    // 大文件(>2GiB)提速提示:5s自动消失的浮层(放在视频全屏界面之下,通话时不抢画面)
    BigTransferHint()
    // 视频通话全屏界面（视频模式 TALKING 时盖在最上层）
    if (AppCore.videoActive) VideoCallScreen()
}

/**
 * "大文件提速提示"浮层:传输超过2GiB时出现在顶部,5s自动消失。
 * 纯提示:无pointer修饰⇒不消费点击,不占布局(浮层,不是插进聊天流里的Banner);
 * LaunchedEffect的键带bigTransferHintTick(连发两个大文件时文案相同,只靠文案做键第二条不会重新起算5s);底色用inverseSurface系,与主题自动同源 */
@Composable
private fun BigTransferHint() {
    val text = AppCore.bigTransferHint ?: return
    val tick = AppCore.bigTransferHintTick
    LaunchedEffect(text, tick) {
        delay(BIG_HINT_MS)
        AppCore.dismissBigTransferHint()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** 「大文件提速提示」停留时长（用户要求 5s） */
private const val BIG_HINT_MS = 5000L

/** 侧栏展开后的宽度；收起时按动画进度线性收缩 */
private val LIST_W = 138.dp

/** 侧栏展开/收起动画时长（ms）—— 自驱实现，不受系统动画时长设置影响 */
private const val SIDE_ANIM_MS = 190f

/**
 * 左侧设备栏+自驱展开/收起动画(本机系统动画时长被置0、Compose动画会瞬跳,故用withFrameNanos逐帧推进)。
 * 宽度与内容滑出在同一次布局测量里算出(见[sideWidthSlide]);进度≈0时整块退出组合省开销。
 * anim=动画进度读取器(0..1),故意用lambda:状态读取在内部,每帧只重组本组件 */
@Composable
private fun SidePanel(
    anim: () -> Float,
    onSettings: () -> Unit,
    onPick: (PeerGroup) -> Unit,
    onEdit: (PeerGroup) -> Unit,
    onAdd: () -> Unit
) {
    val p = anim().coerceIn(0f, 1f)
    if (p <= 0.001f) return
    Box(
        modifier = Modifier
            .sideWidthSlide(LIST_W, p)
            .fillMaxHeight()
    ) {
        DeviceListPanel(
            modifier = Modifier.fillMaxHeight(),
            onSettings = onSettings,
            onPick = onPick,
            onEdit = onEdit,
            onAdd = onAdd
        )
    }
}

/**
 * 侧栏的"宽度+滑出"布局修饰符:旧版width(测量/摆放)+graphicsLayer(绘制)分属两条失效链路,动画期间分界边与内容位置会错开;
 * 现并入一次测量:子内容按固定宽[w]测量、左移`w - w*p`摆放,对外报告`w*p`宽——分界边位置与内容位移出自同一表达式;裁切取本节点报告宽,聊天区不会被侧栏底色盖住。
 * p=动画进度(0=收起,1=展开) */
private fun Modifier.sideWidthSlide(w: Dp, p: Float): Modifier =
    layout { measurable, constraints ->
        val full = with(density) { w.roundToPx() }
        val shown = (full * p).roundToInt().coerceIn(0, full)
        val placeable = measurable.measure(
            constraints.copy(minWidth = full, maxWidth = full)
        )
        layout(shown, placeable.height) {
            placeable.placeRelative(-(full - shown), 0)
        }
    }

/**
 * "IP冲突"确认弹窗(同IP多设备):同一地址上已连着另一台设备时,手动点"同一IP上的另一台设备"不能闷头抢连接
 * (两台共用IP,抢过来会变成"界面显示A、收发其实给B"),先问一句:确认⇒断开那台的连接、把该地址切给这台(见[AppCore.confirmConflictSwitch]) */
@Composable
private fun IpConflictDialog(
    ip: String,
    holder: String,
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("IP 冲突") },
        text = {
            Column {
                Text("地址 $ip 已被「$holder」占用。")
                Text(
                    "是否断开「$holder」的连接，并切换到「$name」？",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("断开并切换") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 删除单条消息的确认弹窗(长按气泡触发)。
 * 文本/图片:对端在线会同步删除,离线时下次连接会重新同步回来(hsync);文件/文件夹:只删本机记录,已落盘的文件不动 */
@Composable
private fun DeleteMessageDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除消息") },
        text = { Text("删除这条聊天记录？对方在线时会同步删除。") },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * "清空聊天记录"确认弹窗(逐条删除,与PC端同口径)。
 * 为什么不是整删文件:整删只清了本机,它一连上hsync对账就把对端历史里的文本/图片补回来(用户看到的是"清空根本没用");
 * 逐条删会用msg_del通知对端一起删文本/图片的mid,所以这里必须如实说明对方在不在线:不在线就是清不干净 */
@Composable
private fun ClearHistoryDialog(plan: ClearPlan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空聊天记录") },
        text = {
            Column {
                Text(
                    if (plan.whole) "清空全部设备的 ${plan.total} 条记录？"
                    else "清空与「${plan.label}」的 ${plan.total} 条记录？"
                )
                Text(
                    "逐条删除：文本和图片会同时通知对方一起删；已经传完的文件不会被删除；" +
                        "「已知设备」名单不受影响（不影响谁能后台连本机）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (plan.syncable > 0) {
                    Text(
                        when {
                            plan.offline && plan.orphan > 0 ->
                                "注意：有设备当前不在线，也有 ${plan.orphan} 条找不到对应设备" +
                                    "（已从名单里删掉）—— 这些只清本机，对方上线后可能被同步回来。"
                            plan.offline ->
                                "注意：对方当前不在线，本次只清本机 —— 对方上线后这些文本/图片" +
                                    "可能被同步回来。"
                            plan.orphan > 0 ->
                                "其中 ${plan.orphan} 条找不到对应设备，只清本机。"
                            else -> "对方在线，会一起清掉。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 清空之后「该通知的没通知到」的提示：不假装清干净，明说对方上线可能补回来 */
@Composable
private fun ClearWarnDialog(msg: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("清空未彻底") },
        text = { Text(msg) },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } }
    )
}

/**
 * 数据网络提醒弹窗·发起方向(本机在运营商数据网络上,即将发文件/语音/视频)。
 * 每次使用都提醒一次(无"同意一次后本次运行内不再弹"的记忆);
 * 同意⇒本次操作继续;取消⇒撤销本次操作(文件会留在聊天里带红色叹号可点重试) */
@Composable
private fun DataNetConfirmDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据网络提醒") },
        text = {
            Column {
                Text("当前为数据网络，「$label」需要消耗数据流量，注意使用费用。")
                Text(
                    "确定要继续吗？",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("同意") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 数据网络提醒弹窗·接收方向(对端请求用数据流量发过来,本机在数据网络上)。
 * 拒绝后本机什么都不记录,只回一帧——对端那边会显示"对方拒绝了使用数据流量接受文件/语音通话/视频通话" */
@Composable
private fun IncomingDataConfirmDialog(
    label: String,
    onRefuse: () -> Unit,
    onAgree: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onRefuse,
        title = { Text("数据网络提醒") },
        text = {
            Column {
                Text("对方请求通过数据流量向本机发送「$label」，接收会消耗本机数据流量，注意使用费用。")
                Text(
                    "同意后才开始接收；拒绝不会在本机留下任何记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { Button(onClick = onAgree) { Text("同意接收") } },
        dismissButton = { TextButton(onClick = onRefuse) { Text("拒绝") } }
    )
}

/** 左侧「加长三横」按钮的固定宽度 */
private val HAMBURGER_SLOT = 52.dp

/**
 * 顶栏/左栏第一行的高度:36.dp,不用Material按钮默认的48dp最小触摸盒(TextButton的minimumInteractiveComponentSize()=48dp会把整行顶到48dp,垂直居中的文字上方凭空多出~16dp)。
 * 触摸目标只有36dp(Material建议48dp)——用户明确要求的取舍:顶部留白优先。
 * 改这一个数,顶栏与左栏第一行同时生效(两栏必须同高,否则第一行会错位) */
internal val TOP_ROW_H = 36.dp

/**
 * 顶栏当前地址的字符预算(IPv6超出即中间省略):20≈屏幕上150dp宽;IPv4(12字符)原样;
 * IPv6(最长39字符)缩成头尾可见的短写法(头10字符认网段前缀、尾9字符认接口ID,中间纯填充十六进制无信息量)。
 * 定死一个数(不按宽度算)是为了让不同机型、不同备注长度下中段宽度稳定,居中的观感一致 */
private const val IP_HEADER_BUDGET = 20

/**
 * 顶栏:第一行"左备注/中 地址▼",第二行连接状态(居中,与PC端`headerState()`一致)。
 * 左=当前设备备注(没备注留空,不回落广播名/IP;列表展开时不显示);中=当前连接地址+紧跟其后的▼(一个紧凑整体,点它弹[AddressMenu]并高亮当前选中;IPv6中间省略);
 * 居中对象是"地址 ▼"整体(▼跟在IP后面,左右各`weight(1f)`);左上角只留加长三横(抽屉开关:右滑开、左滑关、点聊天区空白关,输入法开着时一并收起) */
@Composable
private fun HeaderBar(onToggleList: () -> Unit, showRemark: Boolean) {
    // 状态栏内边距加在Surface内部的Column上(不是加在外层行上):Surface的底色铺到屏幕最顶边,通知栏条与顶栏同色,内容仍被顶下来不与时间/电量重叠。
    // 顶部不再叠自己的垂直内边距:状态栏带本身有系统留的白,再叠4dp只会让"顶部空得多"更明显(配合第一行按钮48dp→40dp,应用留白从19.75dp收到11.75dp)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 2.dp)
        ) {
            val grp = AppCore.currentGroup()
            // 地址列表开关:中段的IP与右段的▼共用(菜单锚点挂在中段,见下)
            var addrOpen by remember { mutableStateOf(false) }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 左区=三横+备注,与右区等权重(各weight 1f)⇒中段必然落在屏幕正中。
                // 不用"左侧固定+右侧补等宽占位"的写法:TextButton在Material里还有最小触摸尺寸,实际占位会随density/系统字号变,写死的补偿值就对不上(a16实测靠weight对齐)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 高度收到40dp:TextButton自带48dp最小触摸尺寸,会把整行顶到48dp,而文字在这个盒子里垂直居中⇒文字上方白白多出~16dp(a16实测63px);
                    // 顶栏第一行只需要"放得下☰+地址",40dp足够;Modifier.height是固定约束,内部的minimumInteractiveComponentSize被夹住⇒不会再撑回48dp
                    TextButton(
                        onClick = onToggleList,
                        modifier = Modifier.width(HAMBURGER_SLOT).height(TOP_ROW_H),
                        contentPadding = PaddingValues(0.dp)
                    ) { LongHamburger() }

                    // 备注：列表展开时不显示（列表里本来就列着）
                    val remark = if (showRemark) grp?.remark.orEmpty() else ""
                    if (remark.isNotBlank()) {
                        Text(
                            remark,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                // 中:当前地址+▼(紧挨在一起);没选中设备时回落应用名,也没有可展开的地址列表。
                // 左/右各一个weight(1f)空当⇒居中的是"IP ▼"整体(箭头挨着ip后面);IPv6太长(最长39字符)故中间省略([middleShorten],只对IPv6生效),完整地址仍在contentDescription与展开的地址列表里
                if (grp != null && grp.curIp.isNotBlank()) {
                    val shortIp = middleShorten(grp.curIp, IP_HEADER_BUDGET)
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { addrOpen = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .semantics { contentDescription = "切换设备地址，当前 ${grp.curIp}" }
                        ) {
                            Text(
                                shortIp,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // "▼"→矢量[Glyph.TRIANGLE_DOWN](字符墨迹中心与IP文字视觉中线不一致,矢量三角自己定尺寸对齐才稳)
                            GlyphIcon(
                                Glyph.TRIANGLE_DOWN,
                                size = 10.dp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                        AddressMenu(open = addrOpen, g = grp, onDismiss = { addrOpen = false })
                    }
                } else {
                    Text("手机电脑互传", style = MaterialTheme.typography.titleSmall)
                }

                // 右区：与左区同权重的余量（左右等权 = 中段真居中）
                Spacer(Modifier.weight(1f))
            }

            // 第二行：连接状态（居中）
            val n = AppCore.connectedPeers.size
            val grey = MaterialTheme.colorScheme.onSurfaceVariant
            val (st, stColor) = when (AppCore.linkState) {
                Link.State.CONNECTED ->
                    (if (n > 1) "● 已连接 · $n 台在线" else "● 已连接") to connectedColor()
                Link.State.CONNECTING -> "○ 正在连接…" to grey
                // IDLE时peerLabel带的是主动连接的失败备注(见Link.stateNote),照实显示
                Link.State.IDLE ->
                    (if (AppCore.peerLabel.isEmpty()) "○ 未连接（点设备连接）"
                    else "○ ${AppCore.peerLabel}") to grey
                Link.State.STOPPED -> "○ 未运行" to grey
            }
            Text(
                st,
                style = MaterialTheme.typography.labelSmall,
                color = stColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** "已连接"用的绿:深色主题下0x2E7D32太暗,换亮一点的绿保证可读;看应用内主题([isAppDark])而不是系统深色 */
@Composable
private fun connectedColor(): Color =
    if (isAppDark()) Color(0xFF6BD48A) else Color(0xFF2E7D32)

/**
 * 顶栏的"地址列表"下拉(锚点由调用处的Box决定):一台设备可能有WiFi/热点/USB网卡等多个地址(同一个deviceId),展开后列出全部,当前选中的那条高亮;
 * 选一个即切换本组后续使用的地址([AppCore.switchGroupIp],会顺带连一次);若那个地址暂时不通、而同组另一地址已有会话,收发仍走已有会话(见Link.sessionByGroup)——不因"点错地址"丢会话。
 * 单地址设备展开后只有一条,但▼照常显示:用户随时能确认"现在连的是谁" */
@Composable
private fun AddressMenu(open: Boolean, g: PeerGroup, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        g.ips.forEach { ip ->
            val on = ip == g.curIp
            val online = ip in AppCore.connectedPeers
            DropdownMenuItem(
                text = {
                    Text(
                        buildString {
                            append(if (on) "● " else "○ ")
                            append(ip)
                            if (online) append("   已连接")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                onClick = {
                    onDismiss()
                    if (!on) AppCore.switchGroupIp(ip)
                }
            )
        }
    }
}

/**
 * 左上角的"加长的三横"(抽屉开关):不用`☰`字形(字形宽度被字体写死、还随字号变,画出来才够长够稳,28dp×3条) */
@Composable
private fun LongHamburger() {
    val c = MaterialTheme.colorScheme.onSurface
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(2.dp)
                    .background(c, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * 聊天区的抽屉手势:右滑展开、左滑收起;阈值一次性判定、不做动画(本机animator_duration_scale=0,Compose动画会瞬间跳变,等于白写)。
 * 上下滑聊天记录不受影响:竖直方向由子级LazyColumn先消费,detectHorizontalDragGestures检测到被消费就会自动取消 */
private fun Modifier.drawerSwipe(open: Boolean, onOpen: () -> Unit, onClose: () -> Unit): Modifier =
    pointerInput(open) {
        // 56dp≈拇指一次自然横滑的三分之一屏宽,太小会与滚动误触
        val threshold = 56.dp.toPx()
        var dx = 0f
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f },
            onDragCancel = { dx = 0f },
            onHorizontalDrag = { _, amount -> dx += amount },
            onDragEnd = {
                if (dx >= threshold && !open) onOpen()
                else if (dx <= -threshold && open) onClose()
            }
        )
    }


/**
 * 输入区上方的一条横幅(网络不可用/有待发消息)。
 * warn=是否画矢量警示图标([Glyph.WARN]):原来由调用方在文案里拼"⚠"前缀(宽度、基线、颜色全看字体);
 * 现在图标由参数控制,与文字在同一Row里对齐、颜色取onPrimaryContainer(与横幅底色配套) */
@Composable
private fun Banner(text: String, warn: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (warn) {
            GlyphIcon(
                Glyph.WARN,
                size = 13.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 通话状态条:非IDLE时显示在聊天区顶部。
 * TALKING:绿底"正在通话 <对方名> <mm:ss> [挂断]";OUTGOING:黄底"正在呼叫 <对方名>… [取消]";INCOMING:黄底"收到 <对方名> 的来电…" */
@Composable
private fun CallBanner() {
    val state = AppCore.callState
    if (state == CallManager.State.IDLE) return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state != CallManager.State.IDLE) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val name = AppCore.callPeerName.ifBlank { AppCore.callPeerIp }
    val bg = if (state == CallManager.State.TALKING)
        Color(0xFF2E7D32) else Color(0xFFB8860B)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = when (state) {
                CallManager.State.TALKING -> {
                    // 必须读快照状态now(LaunchedEffect每500ms写入)才会触发重组;直接调elapsedMs()读系统时钟的话计时会冻结(实测)
                    val ms = AppCore.call.elapsedMs(now)
                    val s = ms / 1000
                    val mm = s / 60
                    val ss = s % 60
                    // §4.3⑥:带内档要在界面上看得见是降级(不是只进日志)。读的是CallManager的降级标志位,不是tier(D3)
                    val downgraded = if (AppCore.call.mediaDowngraded) "（媒体降级：走消息通道）" else ""
                    "正在通话 $name  %02d:%02d".format(mm, ss) + downgraded
                }
                CallManager.State.OUTGOING -> "正在呼叫 $name…"
                else -> "收到 $name 的来电…"
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            // 声音输出通道切换(默认媒体;可切电话通道)。文案"切换声音"(原来写当前通道名,看着像状态标签而不是按钮)。
            // 原来是36dp圆钮,紧挨右侧36dp挂断圆钮,窄屏上几乎贴到一起;现在76dp胶囊+16dp间距,视觉上彻底分开
            if (state == CallManager.State.TALKING) {
                val media = AppCore.callMediaChannel
                IconButton(
                    onClick = { AppCore.toggleCallChannel() },
                    modifier = Modifier
                        .width(76.dp)
                        .height(34.dp)
                        .background(
                            if (media) Color(0xFF1565C0) else Color(0xFF6D4C41),
                            RoundedCornerShape(17.dp)
                        )
                ) {
                    Text(
                        "切换声音",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(16.dp))
                // 免提 ⇄ 听筒（仅电话通道下有意义；媒体通道输出跟随系统路由）
                if (!media) {
                    val on = AppCore.callSpeakerOn
                    IconButton(
                        onClick = { AppCore.toggleCallSpeaker() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (on) Color(0xFF1565C0) else Color.White.copy(alpha = 0.25f),
                                CircleShape
                            )
                    ) {
                        Text(
                            if (on) "免提" else "听筒",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }
            }
            // 通话中红色"挂断"圆钮/呼出中红色"取消"圆钮(电话图标,与呼入弹窗同源)
            if (state == CallManager.State.TALKING || state == CallManager.State.OUTGOING) {
                val hangup = state == CallManager.State.TALKING
                IconButton(
                    onClick = { if (hangup) AppCore.hangupCall() else AppCore.cancelCall() },
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFD32F2F), CircleShape)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_call_end),
                        contentDescription = if (hangup) "挂断" else "取消",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 呼入弹窗(电话风格):全屏深色遮罩,顶部来电人信息,底部左侧红色"拒绝"(挂断话筒图标)、右侧绿色"接听"(话筒图标)——语音/视频来电共用同一套图标,仅文案区分 */
@Composable
private fun IncomingCallDialog() {
    val incoming = AppCore.incomingCall ?: return
    val ip = incoming.first
    val name = incoming.second
    val isVideo = incoming.third
    Dialog(
        onDismissRequest = { /* 不能点空白关闭，必须明确选择 */ },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xF20E1013))) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isVideo) "视频来电" else "语音来电",
                    color = Color(0xFF8BD69E),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    name.ifBlank { ip },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "IP：$ip",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall
                )
                if (isVideo) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "对方发起了视频通话",
                        color = Color(0xFFFFC46B),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(84.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拒绝（红 · 挂断话筒）
                IconButton(
                    onClick = { AppCore.rejectCall() },
                    modifier = Modifier
                        .size(70.dp)
                        .background(Color(0xFFD32F2F), CircleShape)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_call_end),
                        contentDescription = "拒绝",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                // 接听（绿 · 话筒）
                IconButton(
                    onClick = { AppCore.acceptCall() },
                    modifier = Modifier
                        .size(70.dp)
                        .background(Color(0xFF2E7D32), CircleShape)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_call),
                        contentDescription = "接听",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

/**
 * 底部输入区外壳。
 * compact=左侧设备列表是否展开——展开时输入区收紧:不给占位文案、输入框最多1行(列表占掉半屏);完整聊天页(列表收起)时最多5行 */
@Composable
private fun BottomArea(compact: Boolean, onPickFile: () -> Unit, onPickFolder: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 抬到软键盘之上；键盘收起时让出导航栏高度
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            if (!AppCore.lanInfo.available) {
                Banner(AppCore.lanInfo.detail, warn = true)
            } else if (AppCore.vpnActive) {
                Banner("检测到 VPN 已开启，网络连接可能失败；建议关闭 VPN 或把本应用加入放行名单", warn = true)
            } else if (AppCore.pendingCount > 0) {
                Banner("待发送 ${AppCore.pendingCount} 条，连接后自动补发")
            }
            InputBar(compact = compact, onPickFile = onPickFile, onPickFolder = onPickFolder)
            // 底部空白垫高:圆角屏会把屏幕四角物理切掉,`navigationBars`只让开手势条、不含圆角,最右下的"发送"会压在圆角上。
            // 垫高量=「发送」二字所在字体的行高([sendTextLineHeight],用户口径"发送按钮字体那么高");与compact无关(屏幕物理形状问题,两种态都垫)
            Spacer(Modifier.height(sendTextLineHeight()))
        }
    }
}

/**
 * 「发送」二字所在字体的行高(dp)——底部垫高就用它当量。
 * 取typography.labelLarge.lineHeight(M3默认20sp)而不是写死20dp(系统字号调大时"发送"变高、垫高跟着变);自定义主题把lineHeight留空时回落到20.dp。
 * ⚠不是聊天区专用:左侧设备列表面板底部那行"设置"也用它——两个面板各自贴屏幕底边,垫高量必须同源,否则左栏按钮比右侧输入框低一截;必须是internal */
@Composable
internal fun sendTextLineHeight(): Dp {
    val lh = MaterialTheme.typography.labelLarge.lineHeight
    return if (lh.isSp) with(LocalDensity.current) { lh.toDp() } else 20.dp
}

/**
 * 收起输入法。两步缺一不可:
 * 1. `clearFocus(force=true)`——只调hide()不清焦点的话,下次点输入框系统不会重新弹IME("键盘再也弹不出来");
 * 2. `WindowInsetsControllerCompat.hide(ime)`——显式收起(ColorOS/MIUI存在"焦点清了键盘还在";幂等)。
 * 取不到Activity时只做第1步;草稿文本来自外部状态,不受影响;必须internal(同包Bubble.kt直接调它) */
internal fun dismissIme(focus: FocusManager, view: android.view.View) {
    focus.clearFocus(force = true)
    val win = (view.context as? Activity)?.window ?: return
    WindowInsetsControllerCompat(win, view).hide(WindowInsetsCompat.Type.ime())
}

/**
 * 输入框右侧的"发送"按钮:小圆角绿色底框+白色字体。
 * 不用Material Button(默认胶囊形+主题紫底不符),用Box直接画,绿与"已连接"同源;宽度不写死(只给高度+左右内边距),大字体下文字不被裁;与输入框Alignment.Bottom对齐再抬一点。
 * 按钮一律可点,点了用toast说明原因(不做"空文本置灰")。
 * compact=左侧列表展开时整条输入区压矮(44dp→36dp、底部抬升6dp→4dp) */
@Composable
private fun SendButton(compact: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(bottom = if (compact) 4.dp else 6.dp)
            .height(if (compact) 36.dp else 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2E7D32))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp)
            .semantics { contentDescription = "发送" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "发送",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * "+"菜单每项前面的小图标:不写tint,交给Icon默认取LocalContentColor(=菜单项的文字色),深/浅色主题自动跟着走;
 * 四个图标(文件/文件夹/话筒/摄像机)本身就是同一套24dp网格的Material轮廓,缩到20dp视觉重量一致 */
@Composable
private fun MenuIcon(res: Int) {
    Icon(
        painterResource(res),
        contentDescription = null,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun InputBar(compact: Boolean, onPickFile: () -> Unit, onPickFolder: () -> Unit) {
    // "+"菜单开关(发送文件/发送文件夹/语音通话/视频通话)
    var attachOpen by remember { mutableStateOf(false) }
    // 视频画质选择框开关（菜单里点「视频通话」先选画质，再发起）
    var videoPickOpen by remember { mutableStateOf(false) }
    // 垂直内边距:列表展开(compact)时收成3dp——配合下面"输入框40dp最小高+发送钮36dp",整条输入区从72dp压到约46dp,给被列表占掉半屏的聊天区多让出一行多的高度
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (compact) 3.dp else 8.dp,
                bottom = if (compact) 3.dp else 8.dp
            )
    ) {
        // 底部只剩一行:输入框(只吃左侧余量)+紧跟其后的发送钮。原来占第二行的"通话/视频"两枚按钮全部并进"+"菜单——少一排按钮、底部更矮,聊天区多出一行高度。
        // 底对齐而不是居中:输入框最多5行、会长高,居中会让发送钮飘在中间
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = AppCore.inputText,
                onValueChange = { AppCore.inputText = it },
                // 高度:列表展开时整体压低——输入框56dp(Material默认)→40dp;这一行高度取最高的子项,紧凑态落在40dp。
                // 必须用height(40.dp)(固定约束)而不是heightIn(min=40.dp):OutlinedTextField的最小高由defaultMinSize(minHeight=MinHeight)施加,
                // 而它只在constraints.minHeight==0时才兜底,heightIn(min=40)只是把minHeight从0抬到40dp,等于把门打开⇒56dp照样生效(真机实测)。
                // 固定高度下内部defaultMinSize写下的56dp minHeight会被外层约束夹回40dp;内容都不超过40dp,不会裁字
                modifier = Modifier
                    .weight(1f)
                    .then(if (compact) Modifier.height(40.dp) else Modifier),
                // 占位文案:左侧列表展开时不给(列表占掉半屏、输入框只剩约125dp文本宽,写了就折行把输入框顶高);另外文案本身也必须短("输入内容"而不是长句)
                placeholder = { if (!compact) Text("输入内容") },
                // 行数上限:完整聊天页5行;列表展开时只给1行。
                // 超出部分由TextField内部滚动承载——手指在框内上下拖动即可看全文,光标移动时也会自动滚到可见处(Compose CoreTextField内建,无需自己加scroll)
                maxLines = if (compact) 1 else 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { AppCore.sendText() }),
                // 点击即把光标放到点到的位置:这是Compose TextField的原生行为(内部按点击的x/y反算字符偏移)。
                // 注意别在外面套pointerInput/detectTapGestures去消费事件,否则光标会永远落在末尾(这条框所在的行没有别的点击处理,安全)。
                // 输入框内右侧"+":原地展开 发送文件/发送文件夹/语音通话/视频通话
                trailingIcon = {
                    Box {
                        TextButton(
                            onClick = { attachOpen = !attachOpen },
                            // 紧凑态把"+"收成40×36:TextButton自带48dp最小触摸尺寸,不压下去它会把整行反顶到48dp(那一行的实际高度取最高的子项),压缩就白做了;
                            // Modifier.size给的是固定约束,而minimumInteractiveComponentSize内部是constraints.constrain(...),会被固定约束夹住⇒这次撑不高
                            modifier = if (compact) Modifier.size(width = 40.dp, height = 36.dp) else Modifier,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(
                            expanded = attachOpen,
                            onDismissRequest = { attachOpen = false }
                        ) {
                            // 四项各配一枚图标(ic_file/ic_folder/ic_call/ic_videocam,都是24dp网格的Material轮廓,由Icon统一取LocalContentColor着色,深/浅色主题都自动跟)——纯文字的四行菜单在深色底上很难一眼分开
                            // 两项都开应用内的文件浏览器(不再用系统选择器):长按文件多选、长按文件夹即选整个文件夹,选完点底部"发送"
                            DropdownMenuItem(
                                text = { Text("发送文件") },
                                leadingIcon = { MenuIcon(R.drawable.ic_file) },
                                onClick = { attachOpen = false; onPickFile() }
                            )
                            DropdownMenuItem(
                                text = { Text("发送文件夹") },
                                leadingIcon = { MenuIcon(R.drawable.ic_folder) },
                                onClick = { attachOpen = false; onPickFolder() }
                            )
                            // 语音通话=原来的"通话"按钮(本轮并进菜单)
                            DropdownMenuItem(
                                text = { Text("语音通话") },
                                leadingIcon = { MenuIcon(R.drawable.ic_call) },
                                onClick = { attachOpen = false; AppCore.startCall() }
                            )
                            // 视频通话=原来的"视频"按钮,仍先弹画质选择框(流畅480P/标清720P/高清1080P/2M/超清1080P/5M),选完才发起;
                            // 该档位是本次通话的上限:网络变差会自动逐级降档,恢复后升回但不越此档
                            DropdownMenuItem(
                                text = { Text("视频通话") },
                                leadingIcon = { MenuIcon(R.drawable.ic_videocam) },
                                onClick = { attachOpen = false; videoPickOpen = true }
                            )
                        }
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            SendButton(compact = compact) { AppCore.sendText() }
        }
        // 画质选择框挂在Column上(不再挂在某枚按钮上——那两枚按钮已经不在界面里了)。
        // 兼容硬闸门不在这里——Link.sendVideoTo()与CallManager.startOutgoing()都会在发帧前查对端hello.vid,老端绝不会收到0x03。
        // "视频通话"菜单项不做置灰:之前peerSupportsVideo()读的是link的普通集合(非Compose状态),连接路径不触发重组⇒按钮永久灰死(实测:手机互打按不出视频通话);
        // 现在一律可点,具体原因(未连接/对端版本不支持/无摄像头/无权限)由startVideoCall()用toast说明
        if (videoPickOpen) {
            VideoLevelPickDialog(
                onDismiss = { videoPickOpen = false },
                onPick = { lv ->
                    videoPickOpen = false
                    AppCore.startVideoCall(lv.idx)
                }
            )
        }
    }
}

/**
 * 视频画质选择框("+"菜单里的"视频通话"点击后弹出)。
 * 四个档位即四档编码分辨率/码率(见[VideoLevel]);本次选中的档位是本次通话的上限:之后QualityController仍按网络自动逐级降/升档,但回升不越过这里选的档。
 * 起始档仍从"(推荐)"那一级起,选"超清"不代表一上来就按5M发 */
@Composable
private fun VideoLevelPickDialog(onDismiss: () -> Unit, onPick: (VideoLevel) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("视频画质") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "选择本次视频通话的最高画质；网络变差会自动降档，恢复后自动升回，不会超过所选档。",
                    style = MaterialTheme.typography.bodySmall
                )
                for (lv in VideoLevel.values()) {
                    Button(
                        onClick = { onPick(lv) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${lv.pickLabel}　${kbpsText(lv.videoKbps)}", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 码率文案：500 → 「0.5M」、1500 → 「1.5M」、2000 → 「2M」、5000 → 「5M」 */
private fun kbpsText(kbps: Int): String =
    if (kbps % 1000 == 0) "${kbps / 1000}M" else "%.1fM".format(kbps / 1000f)

/**
 * 视频通话全屏界面(计划§5.3):主区=远端画面TextureView(按video_cfg.rot旋转);右下=本地小窗TextureView(实时同一颗摄像头,前置做水平镜像);
 * 底部=挂断/静音/切换摄像头;对端不能回传(PC只收)时显示占位文案 */
@Composable
private fun VideoCallScreen() {
    val session = AppCore.videoSession
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var summary by remember { mutableStateOf(AppCore.videoSummary()) }
    // 两个独立旋转钮记忆的最终显示角(对方画面/本地小窗,各自持久化,重启沿用)。
    // -1=从未手调⇒渲染侧走自适应基线。记绝对角度而非叠加量——基准角(对端video_cfg.rot/采集重启后的rotationDegrees)通话间会漂,记叠加量对不上号(用户报"自动复原"的根因),钉绝对角度才稳。
    // 必须是状态:旋转只在渲染侧的两个TextureView上做,状态一变update lambda重建⇒transform必重跑(不依赖别的东西触发)
    var remoteRot by remember { mutableIntStateOf(AppCore.videoRemoteRot) }
    var localRot by remember { mutableIntStateOf(AppCore.videoLocalRot) }
    val remoteSurface = remember { mutableStateOf<Surface?>(null) }

    // 视频缓冲尺寸(像素)必须是状态:远端remoteVideoW/H由video_cfg后到,早先实现里update现读sess⇒buf0→1920x1080不触发重组⇒变换不重算,远端画面一直走单位矩阵(直接铺满,等比从未生效;日志buf=0x0实锤)
    var bufRemoteW by remember { mutableIntStateOf(session.remoteVideoW) }
    var bufRemoteH by remember { mutableIntStateOf(session.remoteVideoH) }
    var bufLocalW by remember { mutableIntStateOf(session.localVideoW) }
    var bufLocalH by remember { mutableIntStateOf(session.localVideoH) }

    // ST"转置态"也必须是状态:onSurfaceTextureAvailable时ST还没有帧⇒矩阵是单位阵⇒stTransposed读出false;
    // 相机出帧后矩阵才变成转置,而此时没有任何状态变化⇒update不重跑⇒等比按"未转置"算⇒宽高比偏(bw/bh)²(a16预览1920x1080→3.16倍"压扁",一眼可见)。
    // 所以放进500ms心跳刷新,翻转即触发重组
    var transLocal by remember { mutableStateOf(false) }
    var transRemote by remember { mutableStateOf(false) }
    // 两个TextureView的引用(非状态:只用于读ST矩阵,读出来的布尔值才是状态)
    val localTvRef = remember { TvRef() }
    val remoteTvRef = remember { TvRef() }

    // 摄像头朝向=两套旋转配置的"槽位键":camFront本机当前用的摄像头(本地小窗那套按它取);peerFront对端告知的摄像头(对方画面那套按它取;老对端恒后置)。
    // 都不是快照状态⇒由下面500ms的心跳刷新,朝向变了才重新取槽(LaunchedEffect键)
    var camFront by remember { mutableStateOf(videoCamFront()) }
    var peerFront by remember { mutableStateOf(videoPeerFront()) }
    // 切摄像头(本机/对端都算)⇒换用那一套记住的角度:没手调过的槽是-1⇒走自适应基线。
    // 对端前置与后置同基线(见adaptiveRemoteRot)
    LaunchedEffect(camFront) {
        localRot = AppCore.videoLocalRotFor(camFront)
        Log.i("PP-VideoTf", "本机摄像头=${if (camFront) "前置" else "后置"} → 小窗旋转槽=$localRot")
    }
    LaunchedEffect(peerFront) {
        remoteRot = AppCore.videoRemoteRotFor(peerFront)
        Log.i("PP-VideoTf", "对端摄像头=${if (peerFront) "前置" else "后置"} → 对方画面旋转槽=$remoteRot")
    }
    // 朝向变化即刻对齐(不等500ms心跳):
    //  - peerCamTick:对端video_cfg到达(发送端1s心跳/切摄重发都会触发)
    //  - selfCamTick:本机"切换摄像头"按钮成功后触发(capture.facing同步已更新)
    LaunchedEffect(AppCore.peerCamTick) {
        peerFront = videoPeerFront()
    }
    LaunchedEffect(AppCore.selfCamTick) {
        camFront = videoCamFront()
    }

    // 控件自动隐藏:5s无触摸隐藏全部按钮,点屏幕重新显示5s。
    // showTick是"重新计时"信号:任何显示/交互都bump它,LaunchedEffect重启计时
    var controlsVisible by remember { mutableStateOf(true) }
    var showTick by remember { mutableLongStateOf(0L) }
    fun pokeControls() {
        controlsVisible = true
        showTick++
    }
    LaunchedEffect(showTick) {
        if (!controlsVisible) return@LaunchedEffect
        delay(5000)
        controlsVisible = false
    }

    // 视频通话期间常亮:画面在动但用户可能不触摸屏幕,灭屏会让采集/预览一起停。
    // 离开视频界面(挂断/收起)时在onDispose里清掉,避免把常亮泄漏给聊天界面
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 计时+状态文案:必须显式读now/summary这两个快照状态才会触发重组
    LaunchedEffect(Unit) {
        while (AppCore.videoActive) {
            now = System.currentTimeMillis()
            summary = AppCore.videoSummary()
            // 摄像头朝向跟着心跳对齐（两套旋转配置靠它选槽）
            camFront = videoCamFront()
            peerFront = videoPeerFront()
            // 缓冲尺寸也跟着心跳刷新(后到/切档后变)⇒驱动两端TextureView重算变换
            bufRemoteW = session.remoteVideoW
            bufRemoteH = session.remoteVideoH
            bufLocalW = session.localVideoW
            bufLocalH = session.localVideoH
            // ST矩阵"转置态"跟着心跳刷新(相机出帧后才会从单位阵变转置)⇒翻转即重组、重算
            val tl = stTransposed(localTvRef.tv?.surfaceTexture)
            val tr = stTransposed(remoteTvRef.tv?.surfaceTexture)
            if (tl != transLocal) {
                transLocal = tl
                Log.i(TAG_TF, "本地 ST 转置态 → $tl")
            }
            if (tr != transRemote) {
                transRemote = tr
                Log.i(TAG_TF, "远端 ST 转置态 → $tr")
            }
            delay(500)
        }
    }

    val ms = AppCore.call.elapsedMs(now)
    val mm = ms / 1000 / 60
    val ss = ms / 1000 % 60
    val name = AppCore.callPeerName.ifBlank { AppCore.callPeerIp }

    // 顶部状态整体"下移一行文本高度":原来只留16dp,圆角屏/状态栏那一条容易压住第一行;
    // 行高取正文样式的lineHeight——与上面那行"名字+时间"同源(Text不传style就是bodyLarge),系统大字体下跟着放大
    val topLineHeight = MaterialTheme.typography.bodyLarge.lineHeight
    val topDrop = if (topLineHeight.isSp) {
        with(LocalDensity.current) { topLineHeight.toDp() }
    } else {
        24.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 点任意处显示控件 5s（按钮自身消费点击，不会误触发这里）
            .pointerInput(Unit) { detectTapGestures(onTap = { pokeControls() }) }
    ) {
        // ===== 远端画面 =====
        if (AppCore.peerSendsVideo) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val tv = TextureView(ctx)
                    remoteTvRef.tv = tv
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            Log.i(TAG_TF, "远端 ST 就绪 st=${w}x$h view=${tv.width}x${tv.height}")
                            val s = Surface(st)
                            remoteSurface.value = s
                            session.attachRemoteSurface(s)
                            applyRemoteTransform(tv, remoteRot, bufRemoteW, bufRemoteH, transRemote)
                            tv.postDelayed({ logStProbe("远端", st, tv) }, 1500)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            Log.i(TAG_TF, "远端 ST 尺寸变化 st=${w}x$h view=${tv.width}x${tv.height}")
                            applyRemoteTransform(tv, remoteRot, bufRemoteW, bufRemoteH, transRemote)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            remoteSurface.value?.release()
                            remoteSurface.value = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    tv
                },
                update = { tv ->
                    remoteTvRef.tv = tv
                    applyRemoteTransform(tv, remoteRot, bufRemoteW, bufRemoteH, transRemote)
                }
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("对方仅接收视频，无画面回传", color = Color.White)
                Text(
                    "（PC 端只收不发）",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ===== 本地小窗 =====
        AndroidView(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 92.dp)
                .width(104.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.DarkGray),
            factory = { ctx ->
                val tv = TextureView(ctx)
                localTvRef.tv = tv
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        Log.i(TAG_TF, "本地 ST 就绪 st=${w}x$h view=${tv.width}x${tv.height}")
                        AppCore.videoSession.attachLocalPreview(st)
                        applyLocalTransform(tv, localRot, bufLocalW, bufLocalH, transLocal)
                        tv.postDelayed({ logStProbe("本地", st, tv) }, 1500)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        Log.i(TAG_TF, "本地 ST 尺寸变化 st=${w}x$h view=${tv.width}x${tv.height}")
                        applyLocalTransform(tv, localRot, bufLocalW, bufLocalH, transLocal)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        AppCore.videoSession.detachLocalPreview()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                tv
            },
            update = { tv ->
                localTvRef.tv = tv
                applyLocalTransform(tv, localRot, bufLocalW, bufLocalH, transLocal)
            }
        )

        // ===== 顶部状态（整体比原来低一行文本，见 topDrop）=====
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp + topDrop)
        ) {
            Text("$name  %02d:%02d".format(mm, ss), color = Color.White)
            Text(
                summary.ifEmpty { "视频接通中…" },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ===== 控件层(5s无触摸自动隐藏)=====
        // 布局:静音=画面左下角喇叭图标(静音加斜杠);切换摄像头/旋转一排往上放;下排居中红色"电话挂断"大圆钮
        if (controlsVisible) {
            // 静音（左下角喇叭）
            IconButton(
                onClick = {
                    AppCore.toggleMute()
                    pokeControls()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, bottom = 104.dp)
                    .size(52.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    painterResource(
                        if (AppCore.videoMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
                    ),
                    contentDescription = if (AppCore.videoMuted) "取消静音" else "静音",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // 上排:切换摄像头/旋转对方画面/旋转小窗(居中)。
            // 两个旋转钮各自独立+90°循环、持久化(prefs,重启/下次通话沿用);记的是最终显示角(绝对角度)——未手调时当前显示=自适应基线,第一次按下即把"基线位置+90"记为绝对角,此后每次通话直接钉死。只改本机渲染
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 172.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                RoundTextControl(
                    label = "切换声音",
                    desc = if (AppCore.callMediaChannel) "声音：媒体" else "声音：电话",
                    active = AppCore.callMediaChannel
                ) {
                    AppCore.toggleCallChannel()
                    pokeControls()
                }
                // 与右侧的圆形控件(切换摄像头/旋转)刻意拉开距离:原来的间距太紧,胶囊与圆钮在窄屏上贴成一片
                Spacer(Modifier.width(28.dp))
                RoundControl(R.drawable.ic_switch_camera, "切换摄像头") {
                    AppCore.switchCamera()
                    pokeControls()
                }
                RoundControl(R.drawable.ic_rotate, "旋转对方画面") {
                    val cur = if (remoteRot >= 0) remoteRot else adaptiveRemoteRot()
                    remoteRot = AppCore.setVideoRemoteRot(cur + 90, peerFront)
                    AppCore.toast("对方画面 ${remoteRot}°·对端${if (peerFront) "前置" else "后置"}")
                    pokeControls()
                }
                RoundControl(R.drawable.ic_rotate_self, "旋转小窗预览") {
                    val cur = if (localRot >= 0) localRot else adaptiveLocalRot()
                    localRot = AppCore.setVideoLocalRot(cur + 90, camFront)
                    AppCore.toast("小窗 ${localRot}°·本机${if (camFront) "前置" else "后置"}")
                    pokeControls()
                }
            }

            // 下排：挂断（红色电话图标，底部带文字）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { AppCore.hangupCall() },
                    modifier = Modifier
                        .size(74.dp)
                        .background(Color(0xFFD32F2F), CircleShape)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_call_end),
                        contentDescription = "挂断",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Text(
                    "挂断",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 通话界面的半透明圆形图标按钮(切换摄像头/旋转等上排控件),底部带文字标签方便识别 */
@Composable
private fun RoundControl(iconRes: Int, desc: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = desc,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            desc,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * 通话界面的文字胶囊按钮("切换声音"):文案"切换声音"(原来写当前通道名"媒体/电话",看起来像状态标签);
 * 从56dp圆钮改成76×56胶囊——4个字塞进圆里会挤,且原来和相邻圆形控件(切换摄像头/挂断)在窄屏上贴得过近 */
@Composable
private fun RoundTextControl(label: String, desc: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .width(76.dp)
                .height(56.dp)
                .background(
                    if (active) Color(0xFF1565C0) else Color.Black.copy(alpha = 0.45f),
                    RoundedCornerShape(28.dp)
                )
        ) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        Text(
            desc,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * 视频画面在TextureView上的"等比缩放+旋转+居中"矩阵(一期GL的替代品):TextureView把缓冲区各向异性拉伸铺满整个View⇒宽高比不符时画面被压扁、只绕View中心转90°时可见区域随握姿在内容上滑动,故在setTransform一次做对:
 * ①postScale(bw/vw,bh/vh)抵消拉伸;②等比k=min(vw/cw,vh/ch)(contain留边不裁);③绕内容中心旋转(video_cfg.rot逆时针语义、postRotate顺时针,上层取负对齐PC端);④平移使内容中心落View中心;⑤前置镜像放最后(最终显示空间水平翻才是自拍镜面)。
 * bw/bh=视频缓冲区尺寸(远端=解码尺寸,本地=编码尺寸);mirror=前置摄像头水平镜像;参数不全时返回单位矩阵 */
internal fun videoFitMatrix(
    vw: Int, vh: Int, bw: Int, bh: Int, rot: Int, mirror: Boolean = false,
    fill: Boolean = false
): Matrix {
    val m = Matrix()
    if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return m
    val r = ((rot % 360) + 360) % 360
    val swap = r == 90 || r == 270
    m.postScale(bw / vw.toFloat(), bh / vh.toFloat())       // ① 抵消 TextureView 的拉伸
    val cw = (if (swap) bh else bw).toFloat()               // 旋转后的内容宽
    val ch = (if (swap) bw else bh).toFloat()               // 旋转后的内容高
    // ② fill=true=等比裁剪填充(居中裁掉溢出部分,画面填满View不变形);
    //    fill=false=等比留边(contain,全画面可见,两侧/上下留黑)
    val k = if (fill) maxOf(vw / cw, vh / ch) else minOf(vw / cw, vh / ch)
    m.postScale(k, k, bw / 2f, bh / 2f)
    m.postRotate(r.toFloat(), bw / 2f, bh / 2f)             // ③ 绕内容中心旋转
    m.postTranslate((vw - bw) / 2f, (vh - bh) / 2f)         // ④ 内容中心 → View 中心
    if (mirror) m.postScale(-1f, 1f, vw / 2f, vh / 2f)      // ⑤ 前置镜像
    return m
}

/** 视频变换/探针日志的 TAG（排障：`adb logcat | grep PP-VideoTf`） */
private const val TAG_TF = "PP-VideoTf"

/** 变换只在这些值变化时打日志(update每500ms跑一次,不去重会刷屏) */
private var lastRemoteTfKey = ""
private var lastLocalTfKey = ""

/**
 * 诊断探针(只读):打印SurfaceTexture的UV变换矩阵+View尺寸。
 * 用途:判定[videoFitMatrix]第①步的前提("TextureView把缓冲各向异性铺满View")是否成立——ST矩阵若含90°旋转或crop/缩放,说明内容已被ST转正/裁剪,①就是在重复补偿或补反,表现为画面被压扁 */
private fun logStProbe(tag: String, st: SurfaceTexture, tv: TextureView?) {
    val f = FloatArray(16)
    runCatching { st.getTransformMatrix(f) }
        .onSuccess {
            val s = f.joinToString(",") { v -> String.format(java.util.Locale.US, "%.3f", v) }
            Log.i(TAG_TF, "$tag ST矩阵=[$s] view=${tv?.width}x${tv?.height}")
        }
        .onFailure { Log.i(TAG_TF, "$tag ST矩阵读取失败：${it.message}") }
}

/**
 * 本地预览路径的旋转符号:video_cfg.rot/rotationDegrees()的语义是"逆时针转多少度才正";Matrix.postRotate正角是顺时针,所以本地预览取rot*ROT_SIGN。
 * 二期起只有本地小窗还用这个符号——对方画面走GL转正+rot=0,不再经过它 */
private const val ROT_SIGN = -1

/**
 * 本地小窗的公共基线补偿角(顺时针,两台实测一致):本地小窗仍直接吃SurfaceTexture缓冲(未套ST变换矩阵),二期GL转正只作用于编码器输入,预览路径保持一期原样 */
private const val LOCAL_PREVIEW_EXTRA = 90

/**
 * 本机前置摄像头小窗额外补偿角(顺时针):用户口径"本机前置时小窗默认态=点两次旋转按钮后的状态",即现基线(采集方向+[LOCAL_PREVIEW_EXTRA])再+180 */
private const val LOCAL_FRONT_EXTRA_ROT = 180

/** 角度归一化到 0..359（顺时针） */
private fun normDeg(d: Int): Int = ((d % 360) + 360) % 360

/** 本机当前摄像头是否前置（读不到按后置） */
private fun videoCamFront(): Boolean =
    runCatching { AppCore.videoSession.capture.isFront }.getOrDefault(false)

/** 对端当前摄像头是否前置（来自 `video_cfg.cam`；老对端/PC 发送端恒后置） */
private fun videoPeerFront(): Boolean =
    runCatching { AppCore.videoSession.remoteCamFront }.getOrDefault(false)

/**
 * 自适应基线角(未手调时对方画面的显示角):二期起发送端GL已把数据转正,新版发送端恒发video_cfg.rot=0,默认正立、无需按机型/朝向补偿(手动旋转钮仍可加偏移)。
 * ⚠这里不加"对端前置+180"(那是发送端不做GL转正的旧架构下的PC端口径);若将来实测对端前置确实反180,再按sess.remoteCamFront在这里加基线 */
private fun adaptiveRemoteRot(): Int {
    val sess = runCatching { AppCore.videoSession }.getOrNull() ?: return 0
    return normDeg(runCatching { sess.remoteRotation }.getOrDefault(0))
}

/**
 * 自适应基线角(未手调时本地小窗):采集方向+公共基线+本机前置额外180°。
 * 预览路径(一期原始缓冲)未变,仍需要这组补偿 */
private fun adaptiveLocalRot(): Int {
    val sess = runCatching { AppCore.videoSession }.getOrNull() ?: return LOCAL_PREVIEW_EXTRA
    val extra = if (runCatching { sess.capture.isFront }.getOrDefault(false)) {
        LOCAL_FRONT_EXTRA_ROT
    } else {
        0
    }
    return normDeg(
        runCatching { sess.capture.rotationDegrees() * ROT_SIGN + LOCAL_PREVIEW_EXTRA + extra }
            .getOrDefault(0)
    )
}

/**
 * TextureView引用持有器(非状态):心跳里靠它读ST矩阵,把"矩阵是否转置"算成状态 */
private class TvRef {
    var tv: TextureView? = null
}

/**
 * ST的UV矩阵是否把内容转置(`|m[1]| > |m[0]|`,即`u'=1-v, v'=1-u`)。
 * 真机实测:相机SurfaceTexture.getTransformMatrix()就是这种转置矩阵——系统已把sensor横向缓冲转成竖立方向,"内容在View里的真实宽高比"是交换后的(1080:1920);
 * [videoFitMatrix]若仍按未交换尺寸算①的抵消项,宽高比会差(bw/bh)²(1920x1080缓冲=3.16倍,即"小窗被压扁")。
 * ⚠必须在相机出帧之后读(出帧前是单位阵⇒恒false);远端同理兜底(解码器ST实测只做垂直翻转不转置,某机型转置也能自动适配) */
private fun stTransposed(st: SurfaceTexture?): Boolean {
    if (st == null) return false
    return runCatching {
        val m = FloatArray(16)
        st.getTransformMatrix(m)
        kotlin.math.abs(m[1]) > kotlin.math.abs(m[0])
    }.getOrDefault(false)
}

/**
 * 远端画面:手调过就钉死在记忆的绝对角度,否则走自适应基线+等比留边。
 * pinnedRot="旋转对方画面"按钮记忆的最终显示角(-1=未手调,走自适应);必须从组合态传入——update lambda若无捕获会被Compose记忆成同一实例,按钮按下后update不保证重跑;不给默认值(每个值按对端摄像头分"前置/后置"两套)。
 * bufW/bufH=远端缓冲尺寸(由video_cfg后到,现读不触发重组⇒远端画面一直走单位矩阵);transposed=ST是否转置(组合态,心跳刷新;不要在update里现读ST——出帧前恒false) */
private fun applyRemoteTransform(
    v: TextureView, pinnedRot: Int, bufW: Int, bufH: Int, transposed: Boolean
) {
    if (v.width == 0 || v.height == 0) return
    val rot = if (pinnedRot >= 0) pinnedRot else adaptiveRemoteRot()
    val (bw, bh) = if (transposed) bufH to bufW else bufW to bufH
    v.setTransform(videoFitMatrix(v.width, v.height, bw, bh, rot))
    val key =
        "${v.width}x${v.height} buf=${bw}x$bh(报${bufW}x$bufH) rot=$rot pinned=${pinnedRot >= 0}"
    if (key != lastRemoteTfKey) {
        lastRemoteTfKey = key
        Log.i(TAG_TF, "远端画面变换：view=$key")
    }
}

/**
 * 本地小窗:手调过就钉死在记忆的绝对角度,否则走自适应基线;等比缩放+前置再水平镜像。
 * 缓冲尺寸来自VideoCall.localVideoW/H(相机吸附后的实际缓冲尺寸,可能≠编码尺寸);必须从组合态传入(切档/吸附后尺寸会变,现读不会触发重组)。
 * ⚠相机ST是转置矩阵(内容已被系统转成竖立)⇒等比计算用交换后的宽高,否则宽高比差(bw/bh)²(即"小窗被压扁")。
 * pinnedRot="旋转小窗"按钮记忆的最终显示角(-1=未手调,见[applyRemoteTransform];按本机前后摄分两套,故不给默认值);transposed=ST是否转置(组合态,见[applyRemoteTransform]) */
private fun applyLocalTransform(
    v: TextureView, pinnedRot: Int, bufW: Int, bufH: Int, transposed: Boolean
) {
    if (v.width == 0 || v.height == 0) return
    val sess = runCatching { AppCore.videoSession }.getOrNull() ?: return
    val rot = if (pinnedRot >= 0) pinnedRot else adaptiveLocalRot()
    val front = runCatching { sess.capture.isFront }.getOrDefault(false)
    val (bw, bh) = if (transposed) bufH to bufW else bufW to bufH
    // 本地小窗用裁剪填充(fill=true):小窗是竖长条而采集缓冲是横向的,contain会把竖屏预览压成一条横幅(用户实测);居中裁剪才填满
    v.setTransform(videoFitMatrix(v.width, v.height, bw, bh, rot, front, fill = true))
    val key =
        "${v.width}x${v.height} buf=${bw}x$bh(报${bufW}x$bufH) rot=$rot front=$front " +
            "转置=$transposed pinned=${pinnedRot >= 0}"
    if (key != lastLocalTfKey) {
        lastLocalTfKey = key
        Log.i(TAG_TF, "本地小窗变换：view=$key")
    }
}
