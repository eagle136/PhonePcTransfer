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
// 注意：`TextUnit.isSp` 是 TextUnit 的**成员属性**（不是顶层扩展）→ 不要写 import，写了会 Unresolved reference
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
 * 主界面：**左侧设备列表 + 右侧聊天**（聊天式布局）。
 *
 * 列表展开/收起（2026-09-15 改版，用户定调）：
 *  - 聊天区**右滑**展开、**左滑**收起
 *  - 展开时**点聊天区空白处**也收起（气泡里的按钮/图片自己消费点击，不受影响）
 *  - 点聊天区空白处**若输入法开着则一并收起**（2026-09-29 追加；与列表状态无关）
 *  - 左上角那枚「加长的三横」按一下同样切换
 *
 * 按钮与箭头全部精简：顶栏原来的「◀ 列表 / ☰ 设备」两个箭头与文字取消，
 * 顶栏里不再有任何按钮（只剩左上角那枚加长三横）。
 *
 * 设备列表栏顶部那一行（2026-09-15 用户定调）：左 = **「清空」**（清当前对话记录，带确认），
 * 右 = **「设置」**小字（设置页仍独立存在，功能自上而下排列，只是入口降为次要）。
 * 点击设备即定向连接，长按可改备注/IP。
 */
@Composable
fun ChatScreen() {
    var showSettings by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(true) }
    // 内置文件浏览器开关（2026-10-05）：**单窗口内的全屏页面**，取代系统文件选择器。
    // 「+」菜单里的「发送文件 / 发送文件夹」都开它（同一个页面：长按文件夹即选文件夹）。
    var browserOpen by remember { mutableStateOf(false) }
    // 侧栏展开/收起进度（0 = 完全收起，1 = 完全展开）。
    // **自驱动画**：本机（a16）系统动画时长被置 0，Compose 的 animate*AsState / tween
    // 会直接瞬跳（工作区硬规则），所以用 withFrameNanos 自己逐帧推进。
    // 状态放在这一层而不是子组件内部：收起动画跑完后子组件会退出组合，
    // 状态若在子组件里会被重置为初始值，下次展开又会瞬跳。
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

    // 点聊天区空白处要能收起输入法（2026-09-29 需求）。
    // 这里取三样东西，供空白点击的 lambda 使用：
    //  - focusManager：清焦点。**必须清**，否则下次点输入框时它仍处于 focused 状态，
    //    系统不会再弹 IME（表现为「点输入框键盘不出来」），这是只调 hide() 的经典坑。
    //  - view：拿 window 用（WindowInsetsControllerCompat 需要 window + view）。
    //  - 实际收起动作见文件末尾的 [dismissIme]。
    val focusManager = LocalFocusManager.current
    val hostView = LocalView.current

    val listState = rememberLazyListState()
    val msgs = AppCore.messages

    // 新消息到达后滚到底部（用即时滚动，不用动画）
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) runCatching { listState.scrollToItem(msgs.lastIndex) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 边距口径（2026-09-16 定稿，两轮修正后的最终形态）：**背景铺满、只有顶部让开通知栏、
        // 且通知栏那一条要用顶栏的底色（不是页面底色）**。
        //  - `Surface(fillMaxSize)` 负责兜底铺满（圆角屏被物理切掉的四个角靠它填满）。
        //  - **通知栏内边距不再加在这一行上**，而是**下沉到各个面板自己的 Surface 内部**
        //    （顶栏 [HeaderBar]、左侧 [DeviceListPanel]）：面板底色因此一直铺到屏幕最顶边，
        //    时间/电量那一条与顶栏同色，不再出现一条突兀的 `#14161A` 黑带（用户 2026-09-16 要求）。
        //  - 上一轮曾给这一行加 `padding(top/bottom = 通知栏高度)` 想「上下都留边距」——那是错的：
        //    只会露出两条与主界面异色的空带（a16 实测顶上、底下各多 160px），像「背景没铺满」。
        Row(modifier = Modifier.fillMaxSize()) {
            // 侧栏（含展开/收起动画）拆成独立子 composable：动画进度在它**内部**读取
            // （deferred read），每帧只重组这一块，不带着整个聊天区一起重组。
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
                    // 抽屉手势：聊天区**右滑展开**、**左滑收起**（一次性阈值判定，不做动画）
                    .drawerSwipe(
                        open = listOpen,
                        onOpen = { listOpen = true },
                        onClose = { listOpen = false }
                    )
            ) {
                // 列表展开时顶栏**不显示备注**（列表里已经列着备注，顶栏再写一遍是重复）
                HeaderBar(onToggleList = { listOpen = !listOpen }, showRemark = !listOpen)

                CallBanner()

                // 点聊天区**空白处**（2026-09-29 扩需求）：
                //  ① 左侧列表展开时 → 收起列表（原行为）
                //  ② **若输入法正开着 → 一并收起**（无论列表展开还是收起）
                // 气泡里的按钮/图片自己消费点击，所以只会命中没被消费的空当。
                //
                // ⚠ `pointerInput` 的键必须是 `listOpen`（不能写 Unit）：块内要读 `listOpen`，
                //   而 pointerInput(Unit) 的 lambda 只安装一次，捕获的是首次的值 → 永远停在 true，
                //   列表收起后再点空白不会生效。老代码靠 `if (listOpen)` 增删修饰符规避了这点，
                //   这里修饰符恒在，所以要把变化量挪到键上。
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

    // 内置文件浏览器：盖在整个聊天界面之上（单窗口内换页，不新开 Activity）
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
    // 「清空聊天记录」确认框（逐条删除）：枚举完成后 AppCore 才把计划投出来
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
    // 数据网络提醒 · 发起方向（本机在用数据流量，且即将发文件/语音/视频）——
    // 每次使用都弹一次，不记忆已同意的对端（用户 2026-09-17 定调）
    AppCore.dataNetConfirm?.let { c ->
        DataNetConfirmDialog(
            label = c.label,
            onDismiss = { AppCore.cancelDataNet() },
            onConfirm = { AppCore.acceptDataNet() }
        )
    }
    // 数据网络提醒 · 接收方向（对端在用数据流量，问本机是否同意接收）——
    // 拒绝时本机**什么都不记录**，只回一帧让对端如实提示
    AppCore.dataNetIncoming?.let { c ->
        IncomingDataConfirmDialog(
            label = c.label,
            onRefuse = { AppCore.rejectIncomingData() },
            onAgree = { AppCore.acceptIncomingData() }
        )
    }
    // 从系统「分享」进来：先问发给哪台设备（见 ShareDialog.kt）
    ShareTargetDialog()
    // 同 IP 多设备冲突（2026-10-05）：手动点了一台「同一 IP 上的另一台设备」时问一句，
    // 确认后才断开旧连接、把该地址的身份切过去
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
    // 大文件（>2GiB）提速提示：5s 自动消失的浮层（放在视频全屏界面**之下**，通话时不抢画面）
    BigTransferHint()
    // 视频通话全屏界面（视频模式 TALKING 时盖在最上层）
    if (AppCore.videoActive) VideoCallScreen()
}

/**
 * 「大文件提速提示」浮层（2026-09-22 用户要求）：传输**超过 2 GiB** 时出现在顶部，
 * **5s 自动消失**。
 *
 * - 纯提示：整块没有任何 pointer 修饰 → **不消费点击**，底下的聊天气泡/输入框照常可点，
 *   也不占布局（浮层，不是插进聊天流里的 Banner，避免顶部内容被顶下去又弹回来）。
 * - `LaunchedEffect` 的键带上 [AppCore.bigTransferHintTick]：连发两个大文件时文案一模一样，
 *   只靠文案做键的话第二条不会重新起算 5s（会被第一条的倒计时一起吞掉）。
 * - 底色用 `inverseSurface` 系（浅色主题下是深底白字、深色主题下反过来），与主题自动同源。
 */
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
 * 左侧设备栏 + **自驱展开/收起动画**（2026-09-19 用户要求「强制显示的动画」）。
 *
 * 为什么不用 `animate*AsState` / `tween`：本机（a16）系统动画时长被置 0，Compose 的动画
 * 体系会直接瞬跳，根本看不到过渡 —— 所以用 `withFrameNanos` 自己逐帧推进。
 *
 * 做法（2026-10-01 修正，见 [sideWidthSlide]）：
 *  - 宽度与内容滑出**在同一次布局测量里算出来**：外层报告宽度 = [LIST_W] × 进度，
 *    子内容按**固定** [LIST_W] 测量后左移 `LIST_W - 宽度` → 两者天然同值，聊天区与侧栏
 *    每帧严格贴在一起
 *  - 进度 ≈ 0 时整块退出组合（省开销；进度状态在父层，下次展开不会瞬跳）
 *
 * @param anim 动画进度读取器（0..1）。**故意用 lambda**：状态读取发生在内部，
 *             动画每帧只重组本组件，不会带着整个聊天区一起重组。
 */
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
 * 侧栏的「宽度 + 滑出」布局修饰符（2026-10-01 修断层）。
 *
 * 上一版是 `Modifier.width(LIST_W * p)` 撑布局、内容再用
 * `graphicsLayer { translationX = -(1-p)*LIST_W }` 滑出。断层（侧栏跟不上聊天区）的
 * 根因就在**这两处不在同一个阶段**：
 *  - `width` 属于**测量/摆放**，`graphicsLayer` 属于**绘制**，是两条独立的失效链路；
 *    动画期间聊天区的重测量、绘制失效顺序与侧栏的绘制层更新并不保证落在同一帧，
 *    中间就会看到分界边已经动了、侧栏内容还停在旧位置。
 *  - 绘制层的位移还有**整数像素吸附**，布局宽度是另一个口径的取整，两者每帧各差半像素
 *    到一像素，观感就是「抖 / 跟不上」。
 *
 * 现在把两件事并到**一次测量**里：子内容按固定 [w] 宽测量（面板不重排、不换行），
 * 左移 `w - w*p` 摆放，本修饰符对外报告 `w*p` 宽 —— 分界边位置与内容位移出自**同一个
 * 表达式**，物理上不可能再错开。裁切范围取本节点报告出来的宽度（即已滑出可视区的部分
 * 被裁掉），聊天区不会被侧栏底色盖住。
 *
 * @param p 动画进度（0 = 收起，1 = 展开）
 */
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
 * 「IP 冲突」确认弹窗（同 IP 多设备，2026-10-05）。
 *
 * 同一个地址上已经连着另一台设备时，手动点「同一 IP 上的另一台设备」不能闷头抢连接
 * （两台设备共用 IP，抢过来会变成「界面显示 A、收发其实给 B」），先问一句：
 * 确认 → 断开那台的连接、把这个地址切给这台（见 [AppCore.confirmConflictSwitch]）。
 */
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
 * 删除单条消息的确认弹窗（长按气泡触发）。
 * 文本/图片：对端在线会同步删除；离线时下次连接会重新同步回来（hsync）。
 * 文件/文件夹：只删本机记录，已落盘的文件不动。
 */
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
 * 「清空聊天记录」确认弹窗（2026-09-21 起改成**逐条删除**，与 PC 端同口径）。
 *
 * 为什么不是整删文件：整删只清了本机，对端历史里的文本/图片还在 —— 它一连上，
 * hsync 对账就把它们补回来（用户看到的是「清空根本没用」）。逐条删会把文本/图片的 mid
 * 用 `msg_del` 通知对端一起删。所以这里**必须如实说明**对方在不在线：不在线就是清不干净。
 */
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
 * 数据网络提醒弹窗 · **发起方向**（本机在运营商数据网络上，即将发文件/语音/视频）。
 *
 * 用户 2026-09-17 定调：**每次使用这个功能就提醒一次**（改造前是「同一对端同意一次后
 * 本次运行内不再弹」，用户要求去掉这个记忆）。同意 → 本次操作继续；取消 → 撤销本次操作
 * （文件会留在聊天里带红色叹号可点重试）。
 */
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
 * 数据网络提醒弹窗 · **接收方向**（对端请求用数据流量发过来，本机在数据网络上）。
 *
 * 拒绝后**本机什么都不记录**（不生成气泡、不写历史），只回一帧 ——
 * 对端那边会显示「对方拒绝了使用数据流量接受文件/语音通话/视频通话」。
 */
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
 * **顶栏 / 左栏第一行的高度**（2026-09-16 定）：`36.dp`，不用 Material 按钮默认的 48dp 最小触摸盒。
 *
 * 为什么值得单独拧这个数：顶栏第一行的实际高度由行内**最高的子项**决定，而 `TextButton` 自带
 * `minimumInteractiveComponentSize()` = 48dp，会把整行顶到 48dp；文字在这个盒子里垂直居中，
 * 于是文字上方凭空多出 ~16dp。a16 实测数据：
 *  - 系统状态栏带 `statusBars` = 160px（40dp），ColorOS 的时钟/电量墨迹只到 `y=117px`，
 *    带子底部那 43px 是**系统自己留的白**，应用改不了（也不该压进通知栏）；
 *  - 应用侧原本还叠了「顶栏 4dp 内边距 + 48dp 盒子里的 15.75dp 居中留白」= 19.75dp，
 *    用户反馈「a16 顶部空的比较多」。收到 36dp 后应用侧留白降到 ~9.75dp，整体观感与 a12 齐平。
 *
 * 触摸目标只有 36dp（Material 建议 48dp）—— 这是**用户明确要求**的取舍：顶部留白优先。
 * 改这一个数，顶栏与左栏第一行**同时**生效（两栏必须同高，否则第一行会错位）。
 */
internal val TOP_ROW_H = 36.dp

/**
 * 顶栏当前地址的**字符预算**（IPv6 超出即中间省略）。
 *
 * 20 ≈ 屏幕上 150dp 宽：IPv4（`192.168.1.14`，12 字符）原样；
 * IPv6（最长 39 字符）缩成 `2001:db8:…54:58c4:e458` 这种头尾可见的短写法 ——
 * 头 10 字符能认出网段前缀、尾 9 字符能认出这台机器的接口 ID，中间那段纯填充的十六进制没有信息量。
 * 定死一个数（不按宽度算）是为了让**不同机型、不同备注长度下中段宽度稳定**，居中的观感才一致。
 */
private const val IP_HEADER_BUDGET = 20

/**
 * 顶栏（2026-09-16 三改定版）：**第一行「左备注 / 中 地址▼」，第二行连接状态**。
 *
 * ```
 * [☰] 设备备注 ······· 192.168.1.13 ▼ ·······
 *                ● 已连接
 * ```
 *
 *  - **左** = 当前设备的**备注**（只认备注；没备注就留空，不回落广播名/IP）。
 *    左侧设备列表展开时不显示（[showRemark] = false）——列表里本来就列着备注，
 *    顶栏再写一遍既重复又把中间挤走。
 *  - **中** = **当前连接的地址 + 紧跟其后的 ▼**（一个紧凑整体），点它弹地址列表，
 *    当前选中的那条**高亮**（见 [AddressMenu]）。**IPv6 中间省略**（见 [middleShorten]）。
 *  - **第二行** = 连接状态（居中，口径与 PC 端 `headerState()` 一致）。
 *
 * ### 中段居中的三次反复（教训值得留着）
 * ① 一版：`[三横 52dp][备注][地址+▼][18dp 槽][weight]` —— 左右**固定宽度不等**，
 *    中段被左推 17dp，用户反馈「a16 显示是歪的」。
 * ② 二版：左右都改 `weight(1f)`，并在 ▼ 左侧补同宽透明槽让**IP 文字本身**居中
 *    （a16 实测 IP 中心 719 = 屏幕中心 720，数学上完全正确）。但**人眼量的是「看得见的整块」**：
 *    「IP + ▼」整体中心落在 747，用户反馈「布局不居中」。
 * ③ 三版：按用户口径把 ▼ 收回 IP 后面，左右仍各 `weight(1f)` —— 居中的是**「地址 ▼」整体**。
 *    配合 IPv6 中间省略把中段压窄，这个整体又短又正，两种量法都合格。
 *
 * 左上角只留一枚**加长的三横**（抽屉开关）：右滑开、左滑关、点聊天区空白关（并在输入法开着时收起输入法，见 [dismissIme]）。
 */
@Composable
private fun HeaderBar(onToggleList: () -> Unit, showRemark: Boolean) {
    // 状态栏内边距**加在 Surface 内部的 Column 上**（不是加在外层行上）：这样 Surface 的
    // `surfaceVariant` 底色会一直铺到屏幕最顶边，通知栏那一条与顶栏同色；内容仍被顶下来，
    // 不会与时间/电量重叠（2026-09-16 用户要求「通知栏顶部背景需要同步软件的」）。
    //
    // 顶部**不再叠自己的垂直内边距**（原来 `vertical = 4.dp` 去掉了）：a16 实测状态栏带本身
    // 就有 160px，而 ColorOS 的时钟/电量只画到 y=117px，带子底部那 43px 是**系统自己留的白** ——
    // 应用侧再叠 4dp 只会让「顶部空得多」更明显（用户 2026-09-16 反馈 a16 顶部偏空）。
    // 配合下面第一行按钮从 48dp 收到 40dp，应用自己的留白从 19.75dp 收到 11.75dp。
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 2.dp)
        ) {
            val grp = AppCore.currentGroup()
            // 地址列表开关：中段的 IP 与右段的 ▼ 共用（菜单锚点挂在中段，见下）
            var addrOpen by remember { mutableStateOf(false) }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 左区 = 三横 + 备注，**与右区等权重（各 weight 1f）** → 中段必然落在屏幕正中。
                // 不用「左侧固定 + 右侧补等宽占位」的写法：TextButton 在 Material 里还有最小触摸
                // 尺寸，实际占位会随 density / 系统字号变，写死的补偿值就对不上了
                //（2026-09-16 a16 实测：真机 1440×3136 / density 640 下中段照样能靠 weight 对齐）。
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 高度收到 40dp：TextButton 自带 **48dp 最小触摸尺寸**，会把整行顶到 48dp，
                    // 而文字在这个盒子里垂直居中 → 文字上方白白多出 ~16dp（a16 实测 63px）。
                    // 顶栏第一行只需要「放得下 ☰ + 地址」，40dp 足够；`Modifier.height` 是固定约束，
                    // 内部的 `minimumInteractiveComponentSize` 被夹住 → 不会再撑回 48dp（2026-09-16）。
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

                // 中：**当前地址 + ▼（紧挨在一起）**（2026-09-16 三改：箭头回到 IP 后面）。
                // 没选中设备时回落应用名，也没有可展开的地址列表。
                //
                // 左/右各一个 `weight(1f)` 空当 → **居中的是「IP ▼」这个看得见的整体**。
                // （二改曾把 ▼ 甩到行的最右端，用户明确要求「箭头应该挨着 ip 后面」，改回紧凑一组。）
                //
                // IPv6 太长（最长 39 字符）会把中段撑满整行，所以地址**中间省略**
                // （[middleShorten]，只对 IPv6 生效，IPv4 12 字符以内原样）；
                // 完整地址仍在 `contentDescription` 与展开的地址列表里，随时可核对。
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
                            // 「▼」字符 → 矢量 [Glyph.TRIANGLE_DOWN]（2026-09-19 去符号字符）。
                            // 字符 ▼ 的墨迹中心与 IP 文字的视觉中线不一致（不同字重差别更大），
                            // 矢量三角由自己定尺寸，与 titleSmall 的 IP 对齐才稳。
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
                // IDLE 时 peerLabel 带的是主动连接的失败备注（见 Link.stateNote），照实显示
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

/** 「已连接」用的绿：深色主题下 0x2E7D32 太暗，换成亮一点的绿保证可读。
 *  2026-10-01：改看**应用内主题**（[isAppDark]）而不是系统深色 —— 主题按钮切换后这里要跟着变 */
@Composable
private fun connectedColor(): Color =
    if (isAppDark()) Color(0xFF6BD48A) else Color(0xFF2E7D32)

/**
 * 顶栏的「地址列表」下拉（2026-09-16 二改）：**菜单本身**，锚点由调用处的 Box 决定。
 *
 * 一台设备可能有 WiFi / 热点 / USB 网卡等多个地址（同一个 deviceId），展开后列出全部，
 * **当前选中的那条高亮**；选一个即切换本组后续使用的地址（[AppCore.switchGroupIp]，
 * 会顺带连一次）。若那个地址暂时不通、而同组的另一个地址已有会话，收发仍走已有会话
 * （见 Link.sessionByGroup）——不会因为「点错地址」就把会话弄丢。
 *
 * 单地址设备展开后只有一条（高亮它自己），但 ▼ 照常显示：用户随时能确认「现在连的是谁」。
 */
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
 * 左上角的「加长的三横」（抽屉开关）。
 *
 * 不用 `☰` 字形：字形宽度被字体写死、还随字号变，画出来才够长够稳（28dp × 3 条）。
 */
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
 * 聊天区的抽屉手势：**右滑展开、左滑收起**。
 *
 * 阈值一次性判定、不做动画 —— a16 上 `animator_duration_scale=0`，任何
 * `animate*AsState`/`tween` 都会瞬间跳变，等于白写（见工作区约定）。
 *
 * 上下滑聊天记录不受影响：竖直方向由子级 `LazyColumn` 先消费，
 * `detectHorizontalDragGestures` 检测到被消费就会自动取消。
 */
private fun Modifier.drawerSwipe(open: Boolean, onOpen: () -> Unit, onClose: () -> Unit): Modifier =
    pointerInput(open) {
        // 56dp ≈ 拇指一次自然横滑的三分之一屏宽，太小会与滚动误触
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
 * 输入区上方的一条横幅（网络不可用 / 有待发消息）。
 *
 * @param warn 是否画一枚矢量警示图标（[Glyph.WARN]）。**2026-09-19 去 emoji 改造**：
 *   原来由调用方在文案里拼「⚠ 」前缀，等于把图标塞进字符串 —— 宽度、基线、颜色全看字体，
 *   这正是要摆脱的显示问题。现在图标由参数控制，与文字在同一 Row 里对齐、颜色取
 *   `onPrimaryContainer`（与横幅底色配套）。
 */
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
 * 通话状态条：非 IDLE 时显示在聊天区顶部。
 *  - TALKING：绿底「正在通话 <对方名> <mm:ss> [挂断]」
 *  - OUTGOING：黄底「正在呼叫 <对方名>… [取消]」
 *  - INCOMING：黄底「收到 <对方名> 的来电…」
 */
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
                    // 必须读快照状态 now（LaunchedEffect 每 500ms 写入）才会触发重组；
                    // 直接调 elapsedMs() 读系统时钟的话计时会冻结（2026-09-15 实测）。
                    val ms = AppCore.call.elapsedMs(now)
                    val s = ms / 1000
                    val mm = s / 60
                    val ss = s % 60
                    // 2.11 / §4.3⑥：带内档要在界面上**看得见**是降级（不是只进日志）。
                    // 读的是 CallManager 的降级标志位，不是 tier（D3）。
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
            // 声音输出通道切换（默认媒体；可切电话通道）。
            // 2026-09-16 用户定调：① 文案由「媒体/电话」改为「切换声音」——
            // 原来写的是当前通道名，看着像状态标签而不是按钮；② 原来是个 36dp 的圆钮，
            // 紧挨着右侧 36dp 的挂断圆钮（电话话筒图标），在窄屏上几乎贴到一起，
            // 现在改成 76dp 的胶囊并把它与右侧按钮的间距拉到 16dp，视觉上彻底分开。
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
            // 通话中红色「挂断」圆钮 / 呼出中红色「取消」圆钮（电话图标，与呼入弹窗同源）
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
 * 呼入弹窗（电话风格，2026-09-16 用户定调）：全屏深色遮罩，顶部来电人信息，
 * 底部左侧红色「拒绝」（挂断话筒图标）、右侧绿色「接听」（话筒图标）——
 * 语音来电与视频来电共用同一套图标，仅文案区分。
 */
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
 *
 * @param compact **左侧设备列表是否展开** —— 展开时输入区要收紧：
 *   不给占位文案、输入框最多 1 行（列表占掉半屏，输入区再长高就把聊天区挤没了）。
 *   完整聊天页（列表收起）时最多 5 行。
 */
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
            } else if (AppCore.pendingCount > 0) {
                Banner("待发送 ${AppCore.pendingCount} 条，连接后自动补发")
            }
            InputBar(compact = compact, onPickFile = onPickFile, onPickFolder = onPickFolder)
            // 底部空白垫高（2026-09-19 用户要求）：圆角屏（a16 那类大圆角）会把屏幕四角
            // 物理切掉一块，`navigationBars` 只让开手势条、**不含圆角**，于是最右下的
            // 「发送」会有一段压在圆角上。垫高量 = 「发送」二字所在字体的行高
            //（见 [sendTextLineHeight]，用户口径「发送按钮字体那么高」）。
            // 与 compact 无关：这是屏幕物理形状问题，不是布局疏密问题，两种态都垫。
            Spacer(Modifier.height(sendTextLineHeight()))
        }
    }
}

/**
 * 「发送」二字所在字体的**行高**（dp）—— 底部垫高就用它当量（用户 2026-09-19 定调）。
 *
 * 取 `typography.labelLarge.lineHeight`（M3 默认 20sp）而不是写死 20dp：系统字号调大时
 * 「发送」两个字会变高，垫高跟着变，观感才不会「字大了又贴回圆角上」。
 * 自定义主题把 lineHeight 留空（`Unspecified`）时回落到 20.dp。
 *
 * ⚠ **不是聊天区专用**（2026-09-21 用户要求）：左侧设备列表面板底部那行「设置」也用它 ——
 * 两个面板各自贴屏幕底边，垫高量必须同源，否则左栏的按钮比右侧输入框低一截
 *（见 `DeviceListPanel` 末尾的 `Spacer` + `navigationBars`）。所以这里必须是 `internal`
 * 而不是 file-private。
 */
@Composable
internal fun sendTextLineHeight(): Dp {
    val lh = MaterialTheme.typography.labelLarge.lineHeight
    return if (lh.isSp) with(LocalDensity.current) { lh.toDp() } else 20.dp
}

/**
 * 收起输入法（2026-09-29 需求：「点聊天消息空白处，如果输入法开着就关掉」）。
 *
 * 两步走，缺一不可：
 *  1. `clearFocus(force = true)` —— 让输入框真正失焦。只调 `hide()` 不清焦点的话，
 *     输入框仍是 focused 状态，用户下次点它时焦点没变化 → 系统**不会重新弹 IME**
 *     （表现为「键盘再也弹不出来」）。`force = true` 是因为强制清焦点才能保证生效。
 *  2. `WindowInsetsControllerCompat.hide(Type.ime())` —— 显式收起。
 *     AOSP 上第 1 步就会连带收起键盘，但 ColorOS / MIUI 实测存在「焦点清了键盘还在」，
 *     所以再补这一下；IME 本就没开时该调用无任何副作用（幂等）。
 *
 * 取不到 Activity（极少数多窗口/宿主场景）时只做第 1 步，不抛异常。
 * 草稿文本不受影响：输入框内容来自 `AppCore.inputText`（外部状态），失焦不会清空。
 *
 * ⚠ 可见性必须是 `internal`（不是 private）：Q2 确认「点气泡也收键盘」后，
 *   同包同模块的 Bubble.kt 要直接调它（同包连 import 都不用写）。
 */
internal fun dismissIme(focus: FocusManager, view: android.view.View) {
    focus.clearFocus(force = true)
    val win = (view.context as? Activity)?.window ?: return
    WindowInsetsControllerCompat(win, view).hide(WindowInsetsCompat.Type.ime())
}

/**
 * 输入框右侧的「发送」按钮（2026-09-16 用户定调）：**小圆角绿色底框 + 白色字体**。
 *
 * 不用 Material 的 `Button`：默认是胶囊形（50% 圆角）+ 主题紫底，与要的
 * 「小圆角绿色框」不符；这里用 Box 直接画，绿与「已连接」同源（0xFF2E7D32）。
 *
 * 宽度**不写死**（只给高度 + 左右内边距），系统大字体下「发送」两字变大也不会被裁掉。
 * 与输入框 `Alignment.Bottom` 对齐、再抬一点，落在输入框描边基线附近。
 *
 * 不做「空文本置灰」：与既有约定一致 —— 按钮一律可点，点了用 toast 说明原因。
 *
 * @param compact 左侧设备列表展开时**整条输入区压矮**（用户 2026-09-16 要求）：
 *   高 44dp → 36dp、底部抬升 6dp → 4dp。完整聊天页仍是 44dp 的定版尺寸
 *   （a16 `64.5×44dp` / a12 `64.7×44dp` 的实测结论只对完整页成立）。
 */
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
 * 「+」菜单每项前面的小图标。
 *
 * 不写 `tint`：交给 `Icon` 默认取 `LocalContentColor`（= 菜单项的文字色），
 * 深色/浅色主题下自动跟着走；四个图标（文件 / 文件夹 / 话筒 / 摄像机）本身就是
 * 同一套 24dp 网格的 Material 轮廓，缩到 20dp 视觉重量一致。
 */
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
    // 「+」菜单开关（发送文件 / 发送文件夹 / 语音通话 / 视频通话）
    var attachOpen by remember { mutableStateOf(false) }
    // 视频画质选择框开关（菜单里点「视频通话」先选画质，再发起）
    var videoPickOpen by remember { mutableStateOf(false) }
    // 垂直内边距：列表展开（compact）时收成 3dp —— 配合下面「输入框 40dp 最小高 + 发送钮 36dp」，
    // 整条输入区从 72dp 压到约 46dp，给被列表占掉半屏的聊天区多让出一行多的高度。
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
        // 底部只剩**一行**（2026-09-16 三改，用户定调）：输入框（只吃左侧余量）+ 紧跟其后的发送钮。
        // 原来占第二行的「通话 / 视频」两枚按钮**全部并进「+」菜单** —— 少一排按钮、
        // 底部更矮，聊天区多出一行的高度；而且「发文件 / 发文件夹 / 打电话」本来就是同一类
        // 「选一个动作」的东西，收进一个入口更顺。
        // 底对齐而不是居中：输入框最多 5 行、会长高，居中会让发送钮飘在中间。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = AppCore.inputText,
                onValueChange = { AppCore.inputText = it },
                // 高度：**列表展开时整体压低**（用户 2026-09-16 要求「左边列表展开时也要压低高度」）——
                // 输入框 56dp（Material 默认）→ 40dp；配合下面「+」钮 40×36、发送钮 36dp，
                // 这一行的高度取最高的那个，紧凑态落在 40dp（实测 a16 EditText 224px → 160px）。
                //
                // 必须用 `height(40.dp)`（**固定**约束）而不是 `heightIn(min = 40.dp)`：
                // 真机实测 `heightIn` 完全无效 —— OutlinedTextField 的最小高由
                // `modifier…defaultMinSize(minHeight = MinHeight)` 施加，而 `defaultMinSize`
                // 只在 `constraints.minHeight == 0` 时才兜底（见 foundation-layout 源码），
                // `heightIn(min=40)` 只是把 minHeight 从 0 抬到 40dp，等于**把 defaultMinSize 的门打开**
                // → 56dp 照样生效（a16 实测还是 56dp）。
                // 固定高度下 maxHeight 被钉成 40dp，内部的 `defaultMinSize` 虽然仍会写下 56dp 的
                // minHeight，但会被外层约束夹回 40dp；内容（单行文本 / 36dp 的「+」钮）都不超过 40dp，不会裁字。
                modifier = Modifier
                    .weight(1f)
                    .then(if (compact) Modifier.height(40.dp) else Modifier),
                // 占位文案：**左侧列表展开时不给**（列表占掉半屏、输入框只剩约 125dp 文本宽，
                // 写了就折行把输入框顶高）；另外文案本身也必须短（「输入内容」而不是长句）。
                placeholder = { if (!compact) Text("输入内容") },
                // 行数上限：完整聊天页 5 行；列表展开时**只给 1 行**。
                // 超出部分由 TextField 内部滚动承载 —— 手指在框内上下拖动即可看全文，
                // 光标移动时也会自动滚到可见处（Compose CoreTextField 内建，无需自己加 scroll）。
                maxLines = if (compact) 1 else 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { AppCore.sendText() }),
                // 点击即把光标放到点到的位置：这是 Compose TextField 的原生行为
                //（内部按点击的 x/y 反算字符偏移）。**注意别在外面套 pointerInput/detectTapGestures
                // 去消费事件**，否则光标会永远落在末尾（这条框所在的行没有别的点击处理，安全）。
                // 输入框内右侧「+」：原地展开 发送文件 / 发送文件夹 / 语音通话 / 视频通话
                trailingIcon = {
                    Box {
                        TextButton(
                            onClick = { attachOpen = !attachOpen },
                            // 紧凑态把「+」收成 40×36：TextButton 自带 48dp 最小触摸尺寸，
                            // 不压下去它会把整行反顶到 48dp（那一行的实际高度取最高的子项），压缩就白做了。
                            // `Modifier.size` 给的是**固定约束**，而 `minimumInteractiveComponentSize`
                            // 内部是 `constraints.constrain(...)`，会被固定约束夹住 → 这次撑不高。
                            modifier = if (compact) Modifier.size(width = 40.dp, height = 36.dp) else Modifier,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(
                            expanded = attachOpen,
                            onDismissRequest = { attachOpen = false }
                        ) {
                            // 四项各配一枚图标（`ic_file` / `ic_folder` / `ic_call` / `ic_videocam`，
                            // 都是 24dp 网格的 Material 轮廓，由 Icon 统一取 LocalContentColor 着色，
                            // 深/浅色主题都自动跟）—— 纯文字的四行菜单在深色底上很难一眼分开。
                            // 两项都开**应用内**的文件浏览器（2026-10-05 起不再用系统选择器）：
                            // 长按文件多选、长按文件夹即选整个文件夹，选完点底部「发送」。
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
                            // 语音通话 = 原来的「通话」按钮（R1 起一直在这里，本轮并进菜单）
                            DropdownMenuItem(
                                text = { Text("语音通话") },
                                leadingIcon = { MenuIcon(R.drawable.ic_call) },
                                onClick = { attachOpen = false; AppCore.startCall() }
                            )
                            // 视频通话 = 原来的「视频」按钮，仍先弹**画质选择框**
                            // （流畅 480P / 标清 720P / 高清 1080P/2M / 超清 1080P/5M），选完才发起；
                            // 该档位是**本次通话的上限**：网络变差会自动逐级降档，恢复后升回但不越此档。
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
        // 画质选择框挂在 Column 上（不再挂在某枚按钮上 —— 那两枚按钮已经不在界面里了）。
        //
        // 兼容硬闸门不在这里 —— `Link.sendVideoTo()` 与 `CallManager.startOutgoing()`
        // 都会在发帧前查对端 `hello.vid`，老端绝不会收到 0x03。
        // 「视频通话」菜单项也**不做置灰**：之前用 `peerSupportsVideo()` 置灰时，
        // 它读的是 link 的普通集合（非 Compose 状态），「点设备发起连接 → 对端握手成功」
        // 这条路径不触发重组 → 按钮永久灰死（2026-09-15 实测：手机互打按不出视频通话）。
        // 现在一律可点，具体原因（未连接 / 对端版本不支持 / 无摄像头 / 无权限）
        // 由 `startVideoCall()` 用 toast 说明 —— 与「避免用户感知点了没反应」的既有约定一致。
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
 * 视频画质选择框（「+」菜单里的**「视频通话」**点击后弹出，2026-09-16 三改）。
 *
 * 四个档位即四档编码分辨率/码率（见 [VideoLevel]）；本次选中的档位是**本次通话的上限**：
 * 之后 `QualityController` 仍会按网络状况自动降/升档（升降都是逐级），
 * 但**回升不会越过这里选的档**。起始档仍从「（推荐）」那一级起，
 * 选「超清」不代表一上来就按 5M 发。
 */
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
 * 视频通话全屏界面（计划 §5.3）：
 *  - 主区：远端画面 TextureView（按 `video_cfg.rot` 旋转）
 *  - 右下：本地小窗 TextureView（实时同一颗摄像头，前置做水平镜像）
 *  - 底部：挂断 / 静音 / 切换摄像头
 *  - 对端不能回传（PC 只收）时显示占位文案
 */
@Composable
private fun VideoCallScreen() {
    val session = AppCore.videoSession
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var summary by remember { mutableStateOf(AppCore.videoSummary()) }
    // 两个独立旋转钮记忆的**最终显示角**（对方画面 / 本地小窗，各自持久化，重启沿用）。
    // -1 = 从未手调 → 渲染侧走自适应基线。2026-09-16 定调：记**绝对角度**而非叠加量 ——
    // 基准角（对端 video_cfg.rot / 采集重启后的 rotationDegrees）通话间会漂，
    // 记叠加量对不上号（用户报「自动复原」的根因），钉绝对角度才稳。
    // 必须是**状态**：旋转只在渲染侧的两个 TextureView 上做，状态一变
    // update lambda 重建 → transform 必重跑（不依赖别的东西触发）。
    var remoteRot by remember { mutableIntStateOf(AppCore.videoRemoteRot) }
    var localRot by remember { mutableIntStateOf(AppCore.videoLocalRot) }
    val remoteSurface = remember { mutableStateOf<Surface?>(null) }

    // 视频缓冲尺寸（像素）**必须是状态**：远端 `remoteVideoW/H` 由 `video_cfg` 后到，
    // 早先实现里 `update` 现读 sess → buf 0→1920x1080 **不触发重组** → 变换不重算，
    // 远端画面一直走单位矩阵（直接铺满，等比从未生效；2026-09-22 日志 buf=0x0 实锤）。
    var bufRemoteW by remember { mutableIntStateOf(session.remoteVideoW) }
    var bufRemoteH by remember { mutableIntStateOf(session.remoteVideoH) }
    var bufLocalW by remember { mutableIntStateOf(session.localVideoW) }
    var bufLocalH by remember { mutableIntStateOf(session.localVideoH) }

    // ST「转置态」也必须是状态（2026-09-22 二次实测定案）。
    //
    // `onSurfaceTextureAvailable` 时 ST 还没有帧 → 矩阵是**单位阵** → stTransposed 读出 false；
    // 相机出帧后矩阵才变成转置（`u'=1-v, v'=1-u`），而此时**没有任何状态变化** → `update` 不重跑
    // → 等比按「未转置」算 → 宽高比偏 (bw/bh)²：
    //   · a16 预览缓冲 1920x1080 → 3.16 倍（用户报的「压扁」，一眼可见）
    //   · a12 是 640x480 → 1.78 倍，且中途收到过一次 `onSurfaceTextureSizeChanged` 顺带重算过 → 看着还好
    // 所以放进 500ms 心跳刷新，翻转即触发重组。
    var transLocal by remember { mutableStateOf(false) }
    var transRemote by remember { mutableStateOf(false) }
    // 两个 TextureView 的引用（非状态：只用于读 ST 矩阵，读出来的布尔值才是状态）
    val localTvRef = remember { TvRef() }
    val remoteTvRef = remember { TvRef() }

    // 摄像头朝向 = 两套旋转配置的「槽位键」（2026-09-19 用户定调）：
    //  - camFront  本机当前用的摄像头（本地小窗那套按它取）
    //  - peerFront 对端告知的摄像头（对方画面那套按它取；老对端恒后置）
    // 都不是快照状态 → 由下面 500ms 的心跳刷新，朝向**变了**才重新取槽（LaunchedEffect 键）。
    var camFront by remember { mutableStateOf(videoCamFront()) }
    var peerFront by remember { mutableStateOf(videoPeerFront()) }
    // 切摄像头（本机/对端都算）→ 换用那一套记住的角度：没手调过的槽是 -1 → 走自适应基线。
    // 对端前置与后置**同基线**（2026-09-22 用户定调，见 adaptiveRemoteRot）。
    LaunchedEffect(camFront) {
        localRot = AppCore.videoLocalRotFor(camFront)
        Log.i("PP-VideoTf", "本机摄像头=${if (camFront) "前置" else "后置"} → 小窗旋转槽=$localRot")
    }
    LaunchedEffect(peerFront) {
        remoteRot = AppCore.videoRemoteRotFor(peerFront)
        Log.i("PP-VideoTf", "对端摄像头=${if (peerFront) "前置" else "后置"} → 对方画面旋转槽=$remoteRot")
    }
    // 朝向变化即刻对齐（不等 500ms 心跳）：
    //  - peerCamTick：对端 video_cfg 到达（发送端 1s 心跳 / 切摄重发都会触发）
    //  - selfCamTick：本机「切换摄像头」按钮成功后触发（capture.facing 同步已更新）
    LaunchedEffect(AppCore.peerCamTick) {
        peerFront = videoPeerFront()
    }
    LaunchedEffect(AppCore.selfCamTick) {
        camFront = videoCamFront()
    }

    // 控件自动隐藏（2026-09-16 用户定调）：5s 无触摸隐藏全部按钮，点屏幕重新显示 5s。
    // showTick 是「重新计时」信号：任何显示/交互都 bump 它，LaunchedEffect 重启计时。
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

    // 视频通话期间常亮：画面在动但用户可能不触摸屏幕，灭屏会让采集/预览一起停。
    // 离开视频界面（挂断/收起）时在 onDispose 里清掉，避免把常亮泄漏给聊天界面。
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 计时 + 状态文案：必须显式读 now/summary 这两个快照状态才会触发重组
    LaunchedEffect(Unit) {
        while (AppCore.videoActive) {
            now = System.currentTimeMillis()
            summary = AppCore.videoSummary()
            // 摄像头朝向跟着心跳对齐（两套旋转配置靠它选槽）
            camFront = videoCamFront()
            peerFront = videoPeerFront()
            // 缓冲尺寸也跟着心跳刷新（后到/切档后变）→ 驱动两端 TextureView 重算变换
            bufRemoteW = session.remoteVideoW
            bufRemoteH = session.remoteVideoH
            bufLocalW = session.localVideoW
            bufLocalH = session.localVideoH
            // ST 矩阵「转置态」跟着心跳刷新（相机出帧后才会从单位阵变转置）→ 翻转即重组、重算
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

    // 顶部状态整体「下移一行文本高度」（2026-09-22 用户定调：先试两行，偏多 → 收回一行）：
    // 原来只留 16dp，圆角屏/状态栏那一条容易压住第一行。行高取正文样式的 lineHeight ——
    // 与上面那行「名字 + 时间」同源（`Text` 不传 style 就是 bodyLarge），系统大字体下跟着放大。
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

        // ===== 控件层（5s 无触摸自动隐藏）=====
        // 布局（2026-09-16 用户定调）：静音 = 画面左下角喇叭图标（静音加斜杠）；
        // 切换摄像头 / 旋转 一排往上放；下排居中红色「电话挂断」大圆钮。
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

            // 上排：切换摄像头 / 旋转对方画面 / 旋转小窗（居中）。
            // 两个旋转钮各自独立 +90° 循环、**持久化**（prefs，重启/下次通话沿用）；
            // 记的是**最终显示角**（绝对角度）—— 未手调时当前显示 = 自适应基线，
            // 第一次按下即把「基线位置 +90」记为绝对角，此后每次通话直接钉死，
            // 不随基准角漂移复原（2026-09-16 用户定调）。只改本机渲染，
            // 不动 video_cfg 协议，不影响 UI 层。
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
                // 与右侧的圆形控件（切换摄像头/旋转）刻意拉开距离：
                // 原来的间距太紧，胶囊与圆钮在窄屏上贴成一片（2026-09-16 用户反馈）
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

/** 通话界面的半透明圆形图标按钮（切换摄像头 / 旋转等上排控件），底部带文字标签方便识别 */
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
 * 通话界面的文字胶囊按钮（「切换声音」）。
 *
 * 2026-09-16 用户定调：① 文案改成「切换声音」（原来写的是当前通道名「媒体/电话」，
 * 看起来像状态标签）；② 从 56dp 圆钮改成 76×56 胶囊 —— 4 个字塞进圆里会挤，
 * 且原来和相邻的圆形控件（切换摄像头/挂断）在窄屏上贴得过近。
 */
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
 * 视频画面在 `TextureView` 上的「**等比缩放 + 旋转 + 居中**」矩阵（一期 GL 的替代品）。
 *
 * ### 根因（2026-09-15 用户报「左移设备画面下移 + 竖屏画面被压成横屏」）
 * `TextureView` 把 `SurfaceTexture` 缓冲区**各向异性拉伸铺满**整个 View（Camera2Basic 的
 * `configureTransform()` 正是按这个前提写的），于是：
 *  ① View 宽高比 ≠ 视频宽高比 → 画面被压扁（竖屏画面压成横屏）；
 *  ② 在这块已铺满的内容上**只绕 View 中心转 90°** → 内容的包围盒横过来、超出 View 被裁掉，
 *     可见区域随握姿变化在内容上滑动 → 「转设备时画面往下跑」。
 * 一期不做 GLES 中间层（计划 §3.6），所以只能在 `setTransform` 里把这两件事一次做对。
 *
 * ### 做法（每一步都对应上面一条症状）
 * ① `postScale(bw/vw, bh/vh)` —— **抵消** TextureView 的拉伸，内容回到真实像素比例；
 * ② 等比缩放 `k = min(vw/cw, vh/ch)`（**contain / 留边不裁**）——`cw·ch` 是**旋转之后**的
 *    内容尺寸（90°/270° 时宽高互换），保证转完仍在 View 内；
 * ③ 绕**内容中心**旋转（`rot` 已在上层取负号，见 [applyRemoteTransform]：
 *    `video_cfg.rot` 是逆时针语义，`Matrix.postRotate` 是顺时针，必须取负才对齐 PC 端）；
 * ④ 平移，使内容中心落在 View 中心 —— 旋转不再让画面跑偏；
 * ⑤ 前置镜像放最后（在**最终显示空间**水平翻，才是自拍镜面；放前面会变成翻转旋转方向）。
 *
 * @param bw / bh 视频缓冲区尺寸（远端 = 解码尺寸，本地 = 编码尺寸）
 * @param mirror  前置摄像头水平镜像
 * @return 参数不全（尺寸未定 / View 未测量）时返回单位矩阵 = 不加任何变换
 */
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
    // ② fill=true = 等比**裁剪填充**（居中裁掉溢出部分，画面填满 View 不变形）；
    //    fill=false = 等比**留边**（contain，全画面可见，两侧/上下留黑）。
    val k = if (fill) maxOf(vw / cw, vh / ch) else minOf(vw / cw, vh / ch)
    m.postScale(k, k, bw / 2f, bh / 2f)
    m.postRotate(r.toFloat(), bw / 2f, bh / 2f)             // ③ 绕内容中心旋转
    m.postTranslate((vw - bw) / 2f, (vh - bh) / 2f)         // ④ 内容中心 → View 中心
    if (mirror) m.postScale(-1f, 1f, vw / 2f, vh / 2f)      // ⑤ 前置镜像
    return m
}

/** 视频变换/探针日志的 TAG（排障：`adb logcat | grep PP-VideoTf`） */
private const val TAG_TF = "PP-VideoTf"

/** 变换只在这些值变化时打日志（`update` 每 500ms 跑一次，不去重会刷屏） */
private var lastRemoteTfKey = ""
private var lastLocalTfKey = ""

/**
 * 诊断探针（只读，2026-09-22 加）：打印 `SurfaceTexture` 的 UV 变换矩阵 + View 尺寸。
 *
 * 用途：判定 [videoFitMatrix] 第 ① 步的前提（「TextureView 把缓冲**各向异性**铺满 View」）
 * 是否成立 —— ST 的变换矩阵若含 90° 旋转或 crop/缩放（`getTransformMatrix` 里出现
 * 非 0/1 的交换项或非 1 的缩放项），说明内容已被 ST 转正/裁剪，① 就是在重复补偿或补反，
 * 表现为画面被压扁。
 */
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
 * 本地预览路径的旋转符号（2026-09-15 定调，历史根因见下）。
 *
 * `video_cfg.rot`/`rotationDegrees()` 的语义是「**逆时针**转多少度才正」；Android
 * `Matrix.postRotate` 正角是**顺时针**，所以本地预览取 `rot * ROT_SIGN`。
 * **二期（2026-09-22）起只有本地小窗还用这个符号**——对方画面走 GL 转正 + rot=0，
 * 不再经过它。
 */
private const val ROT_SIGN = -1

/**
 * 本地小窗的**公共基线**补偿角（顺时针，2026-09-15 23:10 两台实测一致）。
 *
 * 本地小窗仍直接吃 SurfaceTexture 缓冲（未套 ST 变换矩阵）——二期 GL 转正只作用于
 * **编码器输入**，预览路径保持一期原样，所以这个常数保留。
 */
private const val LOCAL_PREVIEW_EXTRA = 90

/**
 * 本机**前置**摄像头小窗额外补偿角（顺时针，2026-09-22 用户定调）。
 *
 * 用户口径：「本机前置时小窗默认态 = 点两次旋转按钮后的状态」，即现基线
 * （采集方向 + [LOCAL_PREVIEW_EXTRA]）再 +180；水平镜像行为不变。
 */
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
 * 自适应基线角（未手调时对方画面的显示角）。
 *
 * **二期（2026-09-22）：发送端 GL 已把数据转正**（微信同款，发出的数据方向就是正的），
 * `video_cfg.rot` 只表示「在正立数据之上还需顺时针补的角」——新版发送端恒发 0，
 * 默认即正立，无需任何按机型/朝向的补偿。手动旋转钮仍可加偏移（记忆槽，-1 未手调）。
 *
 * ⚠ 2026-09-22 二次实测校验：发送端那处 `glPipe.rotationDeg = capture.rotationDegrees()`
 * 残留（= 双重旋转，后置多转 90°）删掉之后，**后置与前置都不再需要任何补偿**——
 * 用户当时报的「后置要向右 90°、前置要向左 90°」这一对**相反**的偏差，根因就是它
 * （前后置 `rotationDegrees()` 相差 180）。所以这里**不加「对端前置 +180」**：
 * 那是「发送端不做 GL 转正」的旧架构下 PC 端 `CallManager.cfgRot` 的口径，
 * 现在两端都在发送端转正，前置/后置同基线。若将来实测对端前置确实反 180，
 * 再按 `sess.remoteCamFront` 在这里加一份基线 —— 别把 PC 那套老口径直接搬过来。
 */
private fun adaptiveRemoteRot(): Int {
    val sess = runCatching { AppCore.videoSession }.getOrNull() ?: return 0
    return normDeg(runCatching { sess.remoteRotation }.getOrDefault(0))
}

/**
 * 自适应基线角（未手调时本地小窗）：采集方向 + 公共基线 + **本机前置额外 180°**。
 * 预览路径（一期原始缓冲）未变，仍需要这组补偿。
 */
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
 * TextureView 引用持有器（非状态）：心跳里靠它读 ST 矩阵，把「矩阵是否转置」算成状态。
 */
private class TvRef {
    var tv: TextureView? = null
}

/**
 * ST 的 UV 矩阵是否把内容**转置**（`|m[1]| > |m[0]|`，即 `u'=1-v, v'=1-u`）。
 *
 * 2026-09-22 真机实测定案：相机 `SurfaceTexture.getTransformMatrix()` 就是这种转置矩阵
 * （`[0,-1,0,0, -1,0,0,0, 0,0,1,0, 1,1,0,1]`）——
 * **系统已把 sensor 横向缓冲转成竖立方向**。所以「内容在 View 里的真实宽高比」是
 * **交换后**的（1080:1920），而 [videoFitMatrix] 若仍按未交换的 1920:1080 算 ① 的抵消项，
 * 宽高比会差 `(bw/bh)²` —— 3.16 倍（1920x1080 缓冲）就是用户报的「小窗被压扁」。
 *
 * ⚠ 必须在**相机出帧之后**读（出帧前是单位阵 → 恒 false）。外层把它做成了状态。
 * 远端同理兜底：解码器 ST 实测只做垂直翻转（不转置），但若某机型转置也能自动适配。
 */
private fun stTransposed(st: SurfaceTexture?): Boolean {
    if (st == null) return false
    return runCatching {
        val m = FloatArray(16)
        st.getTransformMatrix(m)
        kotlin.math.abs(m[1]) > kotlin.math.abs(m[0])
    }.getOrDefault(false)
}

/**
 * 远端画面：**手调过就钉死在记忆的绝对角度**，否则走自适应基线 + 等比留边。
 *
 * @param pinnedRot 「旋转对方画面」按钮记忆的最终显示角（-1 = 未手调，走自适应）。
 * **必须从组合态传入**——update lambda 若无捕获会被 Compose 记忆成同一实例，
 * 按钮按下后 update 不保证重跑。也**不要**再给默认值（2026-09-19 起每个值按对端
 * 摄像头分成「前置/后置」两套，AppCore 上那个单值字段不再代表「当前该用哪个」）。
 * @param bufW/bufH 远端缓冲尺寸（**必须是组合态参数**）：它由 `video_cfg` 后到，
 * 早先实现里 `update` 从 `sess` 现读 → buf 从 0 变成实际尺寸**不触发重组** →
 * `AndroidView.update` 不重跑 → 远端画面一直走单位矩阵（直接铺满，等比从未生效）。
 * @param transposed ST 是否转置（**组合态**，心跳刷新；不要在 update 里现读 ST ——
 * 出帧前恒 false，读到的值会与重组时机脱节）。
 */
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
 * 本地小窗：**手调过就钉死在记忆的绝对角度**，否则走自适应基线；等比缩放 + 前置再水平镜像。
 *
 * 缓冲尺寸来自 `VideoCall.localVideoW/H` —— 相机吸附后的实际缓冲尺寸
 * （2026-09-16 起 `attachLocalPreview` 经 pickPreviewSize 吸附，可能 ≠ 编码尺寸）。
 * **必须从组合态传入**（切档/吸附后尺寸会变，现读不会触发重组）。
 *
 * ⚠ 相机 ST 是**转置矩阵**（内容已被系统转成竖立）→ 等比计算用**交换后**的宽高，
 * 否则宽高比差 `(bw/bh)²`（就是用户报的「小窗被压扁」，2026-09-22 实测）。
 *
 * @param pinnedRot 「旋转小窗」按钮记忆的最终显示角（-1 = 未手调，见 [applyRemoteTransform]；
 * 2026-09-19 起按**本机**前后摄分两套，故不给默认值）。
 * @param transposed ST 是否转置（**组合态**，见 [applyRemoteTransform]）。
 */
private fun applyLocalTransform(
    v: TextureView, pinnedRot: Int, bufW: Int, bufH: Int, transposed: Boolean
) {
    if (v.width == 0 || v.height == 0) return
    val sess = runCatching { AppCore.videoSession }.getOrNull() ?: return
    val rot = if (pinnedRot >= 0) pinnedRot else adaptiveLocalRot()
    val front = runCatching { sess.capture.isFront }.getOrDefault(false)
    val (bw, bh) = if (transposed) bufH to bufW else bufW to bufH
    // 本地小窗用**裁剪填充**（fill=true）：小窗是竖长条而采集缓冲是横向的，
    // contain 会把竖屏预览压成一条横幅（2026-09-15 23:24 用户实测）；居中裁剪才填满。
    v.setTransform(videoFitMatrix(v.width, v.height, bw, bh, rot, front, fill = true))
    val key =
        "${v.width}x${v.height} buf=${bw}x$bh(报${bufW}x$bufH) rot=$rot front=$front " +
            "转置=$transposed pinned=${pinnedRot >= 0}"
    if (key != lastLocalTfKey) {
        lastLocalTfKey = key
        Log.i(TAG_TF, "本地小窗变换：view=$key")
    }
}
