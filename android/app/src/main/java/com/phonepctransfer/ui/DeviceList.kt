package com.phonepctransfer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonepctransfer.AppCore
import com.phonepctransfer.data.PeerGroup

/**
 * 左侧设备列表(可收起):单击某项→切为「当前对话设备」(没连才发起一次连接,失败不重试再点一次即可);长按某项→编辑备注/IP(或删除)。
 * 展开/收起由聊天区手势与顶栏「加长三横」负责;本面板内部不放收起箭头,动画自驱动(见 ChatScreen 的 SidePanel);「设置」固定在列表底部一行,清空记录在设置页里做。
 * 多设备是独立长连接(连着的多台同时亮绿点、互不影响);局域网发现:启动时主动扫一轮,之后被动接收对端广播,谁广播就把谁写进列表,结果文案显示在本机地址块下方。
 */
@Composable
fun DeviceListPanel(
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onPick: (PeerGroup) -> Unit,
    onEdit: (PeerGroup) -> Unit,
    onAdd: () -> Unit
) {
    // 「同 IP 的另一台设备」别名行长按后的小菜单（见 [AltDeviceDialog]）
    var altMenu by remember { mutableStateOf<PeerGroup?>(null) }
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        // 状态栏内边距加在面板自己的 Surface 内部:面板底色铺到屏幕最顶边,通知栏条与左栏同色(surfaceVariant),内容被顶下来不压时间/电量(与外层布局的分工见 ChatScreen 根 `Row` 的注释)。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // 顶部那一行（「清空」+「设置」）已整体删除：面板内容从本机地址块开始；设置入口移到底部固定行（见本 Column 末尾）。

            // 连接状态那行（「○ 未连接（点设备连接）」…）已删除（状态在顶栏第 2 行已有，不重复）；腾出来的位置放本机地址块。
            LocalIpBlock()

            // 局域网发现状态:启动时主动扫一轮 →「发现 N 台设备」/「无新设备」,之后纯被动(收到对端广播就刷新)。
            // 只有本机连了 WiFi/热点/以太网、且地址在私网段才真的扫(闸门在 AppCore.startScan)。
            if (AppCore.scanMessage.isNotEmpty()) {
                Text(
                    AppCore.scanMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (AppCore.scanning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 可交互按钮加边框（高对比口径）。Box 自绘（与底部按钮同口径）:TextButton 的 48dp 最小触摸目标会把边框盒撑得偏高,故「上下压小」。
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.2.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple()
                        ) { onAdd() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋设备", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Text(
                "单击设备 = 切到它（没连才连一次）\n断线后点一下即可重连",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 2.dp)
            )

            HorizontalDivider()

            if (AppCore.peerGroups.isEmpty()) {
                // weight(1f) 撑住中间：空态也保证底部「设置」行贴在面板最底下
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        "还没有设备。\n点「＋设备」手动添加，或等自动发现。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(AppCore.peerGroups, key = { it.key }) { g ->
                        DeviceRow(
                            group = g,
                            connected = AppCore.isGroupOnline(g),
                            // 别名行不带「当前对话」高亮：它的 ips 就是那个 IP，用 contains 判会把「同 IP 的另一台」也点亮
                            current = !g.alt && g.ips.contains(AppCore.selectedPeerIp),
                            onClick = { onPick(g) },
                            onLongClick = {
                                // 别名行不是一条真正的地址（只是「同一 IP 上还有另一台设备」）：不能进编辑弹窗改/删地址（等于动了别人的地址），改成弹「切换 / 不再显示」小菜单
                                if (g.alt) altMenu = g else onEdit(g)
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // 底部固定一行：「设置」+「主题」两个入口（各占一半宽）；占自己的高度、不覆盖列表内容（列表在它上方滚动）；
            // 高度沿用 [TOP_ROW_H]（36dp），与顶栏第一行同高。
            // 「主题」按钮：左边是太阳/月亮矢量图标，文字显示当前状态（浅色 → 太阳+「浅色」，深色 → 月亮+「深色」），点一下切换并持久化。
            // 整行按聊天输入框同一口径垫高（`navigationBars` + [sendTextLineHeight]，垫高加在 Surface 内部，与顶部 statusBars 同口径）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(TOP_ROW_H)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dark = isAppDark()
                PanelEntryButton(
                    glyph = Glyph.GEAR,
                    label = "设置",
                    cd = "设置",
                    onClick = onSettings
                )
                PanelEntryButton(
                    glyph = if (dark) Glyph.MOON else Glyph.SUN,
                    label = if (dark) "深色" else "浅色",
                    cd = "主题",
                    onClick = { toggleAppTheme(dark) }
                )
            }

            // 底部空白垫高：与聊天区 [BottomArea] 收尾 `Spacer` 同一个量（`sendTextLineHeight()`，见该函数的注释），左栏「设置」与右侧输入框贴在同一条基线上。
            Spacer(Modifier.height(sendTextLineHeight()))
        }
    }

    // 「同 IP 的另一台设备」别名行的长按小菜单（放最外层：弹窗不受面板裁剪影响）
    altMenu?.let { g ->
        AltDeviceDialog(
            g = g,
            onSwitch = {
                altMenu = null
                onPick(g)
            },
            onForget = {
                altMenu = null
                AppCore.forgetDevice(g.key)
            },
            onDismiss = { altMenu = null }
        )
    }
}

/**
 * 「同 IP 的另一台设备」别名行的长按菜单:别名行不是一条真正的地址,不能走编辑弹窗(那里会改/删那个地址)。
 * 只给两个动作:**切换到该设备**走 IP 冲突确认流程(见 [AppCore.confirmConflictSwitch]);
 * **不再显示**忘记这条记录并记进忽略表——否则对方每 2s 广播一次,2 秒后它又回来(同一台手机换/重装后留下的旧 deviceId 残行就是这么清掉的)。
 */
@Composable
private fun AltDeviceDialog(
    g: PeerGroup,
    onSwitch: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同 IP 的另一台设备") },
        text = {
            Column {
                Text("「${g.displayName}」出现在地址 ${g.curIp} 上，但该地址当前属于别的设备。")
                Text(
                    "「切换到该设备」会先问是否断开当前占用者的连接；" +
                        "「不再显示」只清本机这条记录，不影响对方。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onSwitch) { Text("切换到该设备") } },
        dismissButton = {
            Row {
                TextButton(onClick = onForget) { Text("不再显示") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 底部「设  置」入口的字号:17sp(labelLarge 14sp 偏小,比列表正文明显一档,又不至于撑破 138dp 宽的面板)。 */
private val SETTINGS_LABEL_SIZE = 17.sp

/**
 * 底部固定行的入口按钮（「设置」/「主题」共用）：等宽（weight 1f）、30dp 高（边框上下压小）、带边框，
 * 内容 = 左矢量图标 + 右文字（字号 [SETTINGS_LABEL_SIZE]）。
 * ⚠ 不能用 `TextButton`：M3 给按钮强制 48dp 最小触摸目标，外层再压 height 时内部内容会溢出边框（实测边框被撑缺、字被裁）。
 * 与「发送」按钮同口径：Box 自绘 + ripple 反馈，触摸热区虽只有 30dp 高，但整行其余留白仍可点。
 */
@Composable
private fun RowScope.PanelEntryButton(
    glyph: Glyph,
    label: String,
    cd: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(30.dp)
            .clip(shape)
            .border(
                width = 1.2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = shape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = SETTINGS_GEAR_SIZE)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = SETTINGS_LABEL_SIZE,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/** 本机地址行用的字号：11sp（与 labelSmall 一致，显式写出来是为了能按它算字符预算） */
private val IP_FONT_SIZE = 11.sp

/**
 * 估算一行能放几个字符用的「平均字宽 / 字号」比值。
 * hex 字符约 0.55em，这里取 0.62 故意偏保守——宁可少显示一两个字符，也不能让最后一个字符被裁掉半截（旁边 `overflow = Ellipsis` 是最后一道兜底）。
 */
private const val IP_CHAR_EM = 0.62f

/**
 * 「本机ip：」地址块（占据已删除的「○ 未连接…」那行位置）：标题与每行都居中、每行一个地址；
 * 点一下=复制该完整地址并提示（可直接粘进「＋设备」的 IP 框）；IPv6 太长时中间省略（头尾保留），完整值在点下去的复制结果里。
 * 内容与顺序由 AppCore.localIps（[com.phonepctransfer.util.LocalIps]）提供：局域网 v4 → 169.254 → 内网 v6 一个 → 公网 v6。
 */
@Composable
private fun LocalIpBlock() {
    val ips = AppCore.localIps
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "本机ip：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (ips.isEmpty()) {
            Text(
                "（暂无）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        } else {
            // 注意：BoxWithConstraints 本质是 Box，子项是叠放的——里面必须再套 Column，否则多行地址会全部画在同一行上；这里只借它量出可用宽度。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val budget = ipCharBudget(maxWidth)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ips.forEach { ip ->
                        Text(
                            // 只影响显示：点下去复制的是完整 ip
                            text = middleShorten(ip, budget),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = IP_FONT_SIZE,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            // 字符预算已经偏保守，正常不会再触发；万一字体比估算的宽，也只是尾部多个省略号
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                // 屏幕上可能是省略过的短写法，无障碍朗读 / UI 自动化读到的必须是**完整地址**
                                .semantics { contentDescription = ip }
                                .clickable { AppCore.copyForAdd(ip) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 一行能容纳的字符数：按可用宽度 / 字号估算，夹在 [6, 48] 之间防极端值 */
@Composable
private fun ipCharBudget(width: Dp): Int {
    val d = LocalDensity.current
    val usable = with(d) { width.toPx() } - with(d) { 6.dp.toPx() }
    val charPx = with(d) { IP_FONT_SIZE.toPx() } * IP_CHAR_EM
    return (usable / charPx).toInt().coerceIn(6, 48)
}

/**
 * 中间省略：保留头尾（IPv4 永远不会触发，只有 IPv6 会）。
 * `internal` 而非 `private`：聊天顶栏的当前地址也用同一套策略，两处必须同一口径。
 */
internal fun middleShorten(s: String, max: Int): String {
    if (s.length <= max || max < 6) return s
    val tail = (max - 1) / 2
    val head = max - 1 - tail
    return s.substring(0, head) + "…" + s.substring(s.length - tail)
}

/**
 * 设备列表的一行 = 一台设备（不是一条地址）：同一台设备（同一个 deviceId）的多个地址合成一行，
 * 副标题显示当前使用的地址，多地址时追加「N 个地址」，切换地址在聊天顶栏（或长按本行编辑）里做。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    group: PeerGroup,
    connected: Boolean,
    /** 是否是「当前对话」那台（聊天区正在显示它的记录） */
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 当前对话 = 主色容器；只连上（非当前）用次级底色，一眼能分清"连着"和"正在聊"
    val bg = when {
        current -> MaterialTheme.colorScheme.primaryContainer
        connected -> MaterialTheme.colorScheme.surface
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    // 深色主题下 0x2E7D32 在近黑底上几乎看不见，换亮绿（高对比口径）
                    .background(
                        if (connected) {
                            if (isAppDark()) Color(0xFF3CD97E) else Color(0xFF2E7D32)
                        } else Color(0xFF9E9E9E)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                group.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) androidx.compose.ui.text.font.FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 副标题：当前地址（+ 地址数量），没有备注/广播名时它就是唯一可读信息，照常显示；
        // 别名行（同 IP 多设备）额外标一句，否则用户只看到「同一个 IP 出现两次」；
        // 主行的「共用地址」也在这行提示（那些地址当前属于别的设备，只做提示不可切换）。
        Text(
            when {
                group.alt -> "${group.curIp} · 同 IP 的另一台设备"
                group.guestIps.isNotEmpty() -> {
                    val base = if (group.multi) "${group.curIp} · ${group.ips.size} 个地址"
                    else group.curIp
                    "$base · 共用: ${group.guestIps.joinToString(",")}"
                }
                group.multi -> "${group.curIp} · ${group.ips.size} 个地址"
                else -> group.curIp
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * **设备**编辑弹窗（长按设备行 / 「＋设备」）。
 * 备注 = 对对方设备的备注（同组的每个地址共用一份，仅本机可见）；
 * 地址列表 = 该设备已知的全部地址：点一行即切为当前使用，右侧 ✕ 从本机列表移除；
 * 添加地址 = 手动声明「这个地址也是这台设备」（连接后聊天记录/备注直接沿用）。
 * initial null = 新增一台设备（只填地址与备注）。
 */
@Composable
fun PeerEditDialog(initial: PeerGroup?, onDismiss: () -> Unit) {
    val isNew = initial == null
    var ip by remember(initial) { mutableStateOf("") }
    var remark by remember(initial) { mutableStateOf(initial?.remark ?: "") }
    var addIp by remember(initial) { mutableStateOf("") }
    // 地址列表要能即时反映增删 → 本地状态（初始值来自当前分组）
    var ips by remember(initial) { mutableStateOf(initial?.ips ?: emptyList()) }
    var curIp by remember(initial) { mutableStateOf(initial?.curIp ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加设备" else "编辑设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注名（对该设备）") },
                    placeholder = { Text("留空则显示对方广播名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.width(6.dp))
                if (isNew) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it.filter { c -> !c.isWhitespace() } },
                        label = { Text("IP 地址") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                } else {
                    Text(
                        "地址（点一行切为当前使用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    ips.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { curIp = a },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    (if (a == curIp) "● " else "○ ") + a,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // 至少留一个地址，否则这台设备就没有可连的入口了
                            if (ips.size > 1) {
                                TextButton(
                                    onClick = {
                                        ips = ips.filter { it != a }
                                        if (curIp == a) curIp = ips.firstOrNull().orEmpty()
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("移除", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = addIp,
                        onValueChange = { addIp = it.filter { c -> !c.isWhitespace() } },
                        label = { Text("添加地址（也是这台设备）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
                if (!isNew) {
                    Text(
                        "备注只存在本机；同一设备的多个地址共用同一份备注与聊天记录。删除只影响本机列表。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isNew) {
                        AppCore.savePeer(null, ip, remark)
                    } else {
                        val k = initial!!.key
                        AppCore.saveGroupRemark(k, remark)
                        // 新增地址（若填了且不与现有重复）
                        val nv = addIp.trim()
                        if (nv.isNotEmpty() && nv !in ips) {
                            AppCore.addGroupIp(k, nv)
                            ips = ips + nv
                        }
                        // 移除的地址：本地列表里有、当前不在列表里的就是被移除的
                        initial.ips.filter { it !in ips }.forEach { AppCore.removeGroupIp(k, it) }
                        // 当前使用地址变化 → 切换（会顺带连接一次）
                        if (curIp.isNotEmpty() && curIp != initial.curIp && curIp in ips) {
                            AppCore.switchGroupIp(curIp)
                        }
                    }
                    onDismiss()
                },
                enabled = if (isNew) ip.isNotBlank() else true
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = {
                        AppCore.deleteGroup(initial!!)
                        onDismiss()
                    }) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
