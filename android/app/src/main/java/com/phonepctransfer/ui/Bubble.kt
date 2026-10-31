package com.phonepctransfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import com.phonepctransfer.util.openReceivedFile
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonepctransfer.AppCore
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.TextUtil
import kotlinx.coroutines.delay

/**
 * 消息气泡：**自己发出的靠右、收到的靠左**。
 * 超过 300+150 字的内容只显示头尾，短按可展开/收起。
 * 图片文件显示缩略图；文件名一律**中间省略**（长名不折行、扩展名始终可见）。
 * **整个气泡统一手势**（combinedClickable，子组件不再各自消费点击）：
 *  - **短按** = 打开内容：文本 → 展开/收起；图片 → 全屏查看；已完成的文件 → 交给系统打开
 *  - **长按** = 弹小菜单（2026-09-19 改版，原来是直接弹删除确认）：
 *      「复制文本」**只在纯文本消息（Kind.TEXT）上出现**，其余一律只有「删除」；
 *      「删除」仍走二次确认（文本/图片会同步删除在线对端的记录，见 AppCore.deleteMessage）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: MsgItem,
    peerName: String,
    onOpenImage: (String) -> Unit,
    onDelete: () -> Unit = {}
) {
    val isSelf = m.dir == Dir.SELF
    var expanded by remember(m.mid) { mutableStateOf(false) }
    val truncated = TextUtil.isTruncated(m.text)
    val shown = if (m.kind == Kind.FILE) "" else TextUtil.headTail(m.text, expanded)
    // 旧历史记录可能没有 localUri（改造前收到的图），因此只用文件名判定；
    // 真正的可加载地址由 rememberImageSource 兜底解析（这里解析一次，短按与缩略图共用）
    val isImage = m.kind == Kind.FILE && !m.isDir && isImageName(m.fileName)
    val imageSource = if (isImage) rememberImageSource(m.localUri, m.fileName) else null
    val ctx = LocalContext.current
    // 点气泡也要收起输入法（2026-09-29 Q2「微信式」）。与 ChatScreen 的空白热区同一套动作，
    // 函数体在 ChatScreen.kt（internal，同包可直接调、无需 import）。
    val focusManager = LocalFocusManager.current
    val hostView = LocalView.current
    // 长按小菜单开关；「复制文本」只在纯文本消息（Kind.TEXT）上出现，且内容非空
    var menuOpen by remember(m.mid) { mutableStateOf(false) }
    val canCopyText = m.kind == Kind.TEXT && m.text.isNotEmpty()

    // 短按行为：点文件 = 打开对应内容；文本 = 展开/收起；其余无动作
    val onShortPress: () -> Unit = when {
        m.kind == Kind.TEXT -> { { expanded = !expanded } }
        isImage && imageSource != null -> { { onOpenImage(imageSource) } }
        m.kind == Kind.FILE && !m.isDir && !isImage && m.status == Status.OK
                && (m.localUri.isNotEmpty() || m.filePath.isNotEmpty()) -> {
            { openReceivedFile(ctx, m.localUri.ifEmpty { m.filePath }, m.fileName) }
        }
        else -> { {} }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 330.dp),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
        ) {
            Text(
                text = if (isSelf) "我" else peerName.ifEmpty { "电脑" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
            )
            // Box 只为给长按菜单当锚点：DropdownMenu 的位置是相对**父容器**算的，
            // 不套这一层的话菜单会跑到整条消息（含时间行）的左上角去。
            Box {
                Surface(
                    color = if (isSelf) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (isSelf) 12.dp else 3.dp,
                        bottomEnd = if (isSelf) 3.dp else 12.dp
                    ),
                    // 整个气泡统一手势：短按打开内容、长按弹小菜单（2026-09-19 起不再直接删）。
                    // 缩略图/文件名不再挂 clickable（之前子组件消费点击导致长按很难触发）；
                    // 仅「展开全文」「取消发送」两个 TextButton 保留自身点击
                    modifier = Modifier.combinedClickable(
                        // 先收键盘再执行原动作：顺序不能反 ——
                        // 原动作可能打开全屏图片 / 跳系统应用（新建 Dialog / 切窗口），
                        // 那时再取 window 收 IME 会落到错的 window 上。
                        onClick = { dismissIme(focusManager, hostView); onShortPress() },
                        onLongClick = { menuOpen = true }
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        when (m.kind) {
                            Kind.FILE -> FileBubbleBody(
                                m = m,
                                isSelf = isSelf,
                                isImage = isImage,
                                imageSource = imageSource
                            )

                            // 剪贴板标签：**矢量图标 + 文字**（2026-09-19 去 emoji，见 [GlyphIcon]）。
                            // 图标与文字必须同行 Row 居中对齐 —— 塞进字符串里就会各按各的基线走，
                            // 这正是原来 emoji 方案「忽高忽低、某些机型变方框」的根源。
                            Kind.CLIP -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GlyphIcon(
                                        Glyph.CLIPBOARD,
                                        size = 12.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "剪贴板",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                                Text(text = shown, style = MaterialTheme.typography.bodyMedium)
                            }

                            // 通话记录（v2.7）：纯文本留痕 ——「语音通话 00:12」「视频通话 对方拒绝」
                            Kind.CALL -> Text(text = m.text, style = MaterialTheme.typography.bodyMedium)

                            Kind.TEXT -> Text(text = shown, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (truncated) {
                            TextButton(
                                onClick = { dismissIme(focusManager, hostView); expanded = !expanded },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = if (expanded) "收起" else "展开全文（共 ${m.text.length} 字）",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                // 长按小菜单（2026-09-19）：**复制文本只在纯文本消息上出现**（用户定调：
                // 剪贴板气泡、通话记录、文件/图片都没有这一项，只有「删除」）。
                // 「删除」不直接删：仍然走原来的二次确认（[DeleteMessageDialog]）。
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canCopyText) {
                        DropdownMenuItem(
                            text = { Text("复制文本") },
                            onClick = {
                                menuOpen = false
                                AppCore.copyTextToClipboard(m.text)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
            // 消息末端：时间 + 状态；「数据流量未同意」的文件消息再挂一枚可点的红色感叹号
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp)
            ) {
                Text(
                    text = TextUtil.time(m.ts) + statusSuffix(m),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isSelf && m.kind == Kind.FILE && m.status == Status.DENIED) {
                    DenyRetryIcon { dismissIme(focusManager, hostView); AppCore.retryFileSend(m.mid) }
                }
            }
        }
    }
}

/**
 * 文件 / 文件夹名的**中间省略字符预算**（2026-09-19 用户要求「长文件名省略中间，太长」）。
 *
 * 为什么不沿用 `TextUtil.middleEllipsis` 的默认 26：这一行是 **bodyMedium（14sp）+ 粗体**
 * 且带前缀，26 个汉字 ≈ 364dp、加前缀 ≈ 394dp，而气泡内宽只有
 * `330dp − 左右各 10dp = 310dp` → 一定会折成两行（这正是用户看到的「太长」）。
 * 20 个汉字 ≈ 280dp + 前缀 ≈ 310dp，正好一行放得下；扩展名由 `middleEllipsis` 保证留在尾部，
 * 另有 `maxLines = 1` 兜底（系统大字号下宁可尾部截断也不折行）。
 *
 * 前缀口径（2026-09-19 去 emoji 后变宽裕）：原先是「📄 / 📁 」emoji ≈ 30dp，
 * 现在换成 [GlyphIcon] 的 14dp 矢量图 + 5dp 间距 = **19dp**，比原来省 11dp ——
 * 所以 20 字这个预算只会更宽松，**不需要跟着调**（避免动一个已经稳定的观感点）；
 * 若日后想再多显示两个字符，把它调到 22 即可。
 *
 * 图片名那一行是 labelSmall（约 11sp）、没有前缀，预算仍是默认的 26。
 */
private const val FILE_NAME_BUDGET = 20

/** 文件 / 文件夹名前面那枚矢量图标的尺寸（与原 emoji 的视觉大小对齐） */
private val FILE_GLYPH_SIZE = 14.dp

/** 图标与文件名之间的间距 */
private val FILE_GLYPH_GAP = 5.dp

@Composable
private fun FileBubbleBody(
    m: MsgItem,
    isSelf: Boolean,
    isImage: Boolean,
    imageSource: String?
) {
    // 「取消发送」按钮也收键盘（2026-09-29 T11）：本函数是独立 composable，
    // MessageBubble 里的 focusManager/hostView 不在作用域 → 这里再取一次
    // （LocalFocusManager / LocalView 任意 composable 都能取，与 MessageBubble 同一套动作）。
    val focusManager = LocalFocusManager.current
    val hostView = LocalView.current
    if (m.isDir) {
        // 文件夹传输气泡：整目录一个气泡，进度按字节聚合
        // 前缀「📁」→ 矢量 [Glyph.FOLDER]（2026-09-19）
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.FOLDER, size = FILE_GLYPH_SIZE)
            Text(
                text = TextUtil.middleEllipsis(m.fileName, FILE_NAME_BUDGET),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = FILE_GLYPH_GAP)
            )
        }
        Text(
            text = buildString {
                if (m.dirCount > 0) append("${m.dirCount} 个文件")
                if (m.fileSize > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(TextUtil.humanSize(m.fileSize))
                }
                if (isEmpty()) append("大小未知")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (isImage) {
        // 图片：小图预览；短按（气泡层）打开全屏。
        // readyKey 传「是否已收完」（progress 到 100 或状态不再是 OK）：只在**收完那一刻**
        // 变一次 → 重新解码，保证图片收完立即出现，不必切走再切回（2026-09-19 修）。
        // 不用裸 progress 当键：接收中它每几百毫秒变一次，会把解码协程反复打断重来。
        ImageThumb(
            source = imageSource,
            maxSize = 200.dp,
            readyKey = m.progress >= 100 || m.status != Status.OK
        )
        // 图片文件名**中间省略**（2026-09-19）：原来直接摊 `m.fileName`，超长名会一路
        // 撑满气泡宽度还折行。缩略图下这行是 labelSmall（约 11sp），26 个字符 ≈ 286dp，
        // 落在气泡内宽之内；`maxLines = 1 + Ellipsis` 只是兜底（系统大字号下尾部截断，
        // 绝不折行 —— 折行会让「缩略图 + 文件名」整块看着很臃肿）。
        Text(
            text = TextUtil.middleEllipsis(m.fileName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    } else {
        // 文件名不再单独 clickable——短按打开由气泡层的 combinedClickable 统一处理
        // 前缀「📄」→ 矢量 [Glyph.FILE]（2026-09-19）
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.FILE, size = FILE_GLYPH_SIZE)
            Text(
                text = TextUtil.middleEllipsis(m.fileName, FILE_NAME_BUDGET),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = FILE_GLYPH_GAP)
            )
        }
    }

    // 目录气泡已在信息行里带上了文件数与总大小，不再重复显示
    if (!m.isDir) {
        Text(
            text = if (m.fileSize > 0) TextUtil.humanSize(m.fileSize) else "大小未知",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 进度条：DENIED（数据流量未同意）时**不画** —— 它并没有在传，画个 0% 条会误导
    // 2026-10-01 高对比口径：实心段 = 主题主色（浅=蓝 / 深=绿），轨道 = [trackColor]
    //（浅色浅灰、深色近白，与参考截图滑条一致），加粗到 6dp 圆头。
    if (m.progress in 0..99 && m.status != Status.DENIED) {
        LinearProgressIndicator(
            progress = { m.progress / 100f },
            color = MaterialTheme.colorScheme.primary,
            trackColor = trackColor(),
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp)
        )
        TransferLine(mid = m.mid, status = m.status, pct = m.progress)
    }

    // 自己发出的消息 filePath 是 content:// URI，摊开没有意义；
    // 只有收到的文件才显示「已保存」绿色钩（左下角，不再冗长显示路径）
    // 「✅」→ 矢量 [Glyph.CHECK]（2026-09-19）
    if (!isSelf && m.status == Status.OK && m.filePath.isNotEmpty()) {
        // 深色主题下 0x2E7D32 太暗，改用与主题主色同档的亮绿（高对比，2026-10-01）
        val savedGreen = if (isAppDark()) Color(0xFF3CD97E) else Color(0xFF2E7D32)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 3.dp)
        ) {
            GlyphIcon(Glyph.CHECK, size = 12.dp, color = savedGreen)
            Text(
                text = "已保存",
                style = MaterialTheme.typography.labelSmall,
                color = savedGreen,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    // 数据流量被拒（本机或对端）：把原因摊在文件名下面 —— 只在 DENIED 状态渲染，
    // 所以重试成功（转 SENDING）后这一行会自动消失，不需要额外清理字段。
    // 「⚠」→ 矢量 [Glyph.WARN]（2026-09-19）
    if (m.kind == Kind.FILE && m.status == Status.DENIED && m.text.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 3.dp)
        ) {
            GlyphIcon(Glyph.WARN, size = 12.dp, color = Color(0xFFD32F2F))
            Text(
                text = m.text,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD32F2F),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    // 暂停（PAUSED）—— 两个方向都会出现（2.12 / D16 起）：
    //  - 接收方向：本机收流停滞 30s，对端那侧留着半成品（文案「重连后自动续传」，无按钮）。
    //  - 发送方向：文件排他投票 = 1（通话中／没网络／被拒），在传的那条在分片或成员边界就地暂停，
    //    条目留在待发台账。投票翻回全 0 时自动发**一次** file_query；对端没应答就停在这一行，
    //    等用户点末尾的「继续」（与 DENIED 的红色叹号同一先例，都走 AppCore.retryFileSend）。
    // 只在 PAUSED 态渲染 —— 续传成功（回 SENDING）或收尾 OK 后这一行自动消失。
    // 「⏸」→ 矢量 [Glyph.PAUSE]（2026-09-26）
    if (m.kind == Kind.FILE && m.status == Status.PAUSED) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 3.dp)
        ) {
            GlyphIcon(Glyph.PAUSE, size = 12.dp, color = Color(0xFF9E9E9E))
            Text(
                text = when {
                    isSelf -> "已暂停，解除后自动续发一次"
                    m.progress >= 0 -> "已收 ${m.progress}%，重连后自动续传"
                    else -> "重连后自动续传"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(start = 4.dp)
            )
            if (isSelf) {
                TextButton(
                    onClick = { dismissIme(focusManager, hostView); AppCore.retryFileSend(m.mid) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("继续", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // 取消发送：只对自己发出的、尚未完成的文件显示；取消后两端同步不显示
    if (isSelf && (m.status == Status.SENDING || m.status == Status.PENDING)) {
        TextButton(
            onClick = { dismissIme(focusManager, hostView); AppCore.cancelFile(m.mid) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text("取消发送", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 红色感叹号图标（**矢量绘制，等价于一张 SVG**：红色实心圆 + 白色叹号）。
 *
 * 用在「数据流量未同意」而留在聊天里的文件消息上（消息末尾）。点一下**重试发送**：
 * 会重新走一遍数据流量提醒流程（本机提醒 + 对端确认），同意后这条会被移到最新一条再开传。
 */
@Composable
private fun DenyRetryIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(18.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val r = size.minDimension / 2f
            drawCircle(color = Color(0xFFD32F2F), radius = r)
            val w = r * 0.26f
            // 叹号竖条
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(center.x - w / 2f, r * 0.30f),
                size = Size(w, r * 0.66f),
                cornerRadius = CornerRadius(w / 2f)
            )
            // 叹号圆点
            drawCircle(
                color = Color.White,
                radius = w * 0.62f,
                center = Offset(center.x, r * 1.34f)
            )
        }
    }
}

/**
 * 进度行：「`87%`」+（正在传时）「` · 0.95M/s`」（2026-09-22 用户要求，两端同口径）。
 *
 * 单独拆成一个小 composable 的理由：速率每 500ms 就变一次，如果让整个气泡跟着重组
 * （里面还有图片解码、手势、多个 Text）代价太大 —— 拆开后**只有这一行**重组。
 *
 * 速率什么时候不显示（都不需要额外清理字段）：
 *  - 状态不是 [Status.SENDING]（待发队列里等着 = 用户说的「暂停」）
 *  - 样本过期（`AppCore.speedBpsOf` 判 1.5s 没新样本：卡住 / 传完）
 * 所以传完那一刻数字自己就消失了。
 */
@Composable
private fun TransferLine(mid: String, status: Status, pct: Int) {
    // 自己走 400ms 心跳：速率是「时间敏感」的，没有新样本时要能自动消失；
    // 心跳只在 SENDING 期间跑，传完/待发状态直接不转。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mid, status) {
        while (status == Status.SENDING) {
            delay(SPEED_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val bps = if (status == Status.SENDING) AppCore.speedBpsOf(mid, now) else 0L
    val sp = TextUtil.speed(bps)
    Text(
        text = if (sp.isEmpty()) "$pct%" else "$pct% · $sp",
        style = MaterialTheme.typography.labelSmall
    )
}

/** 速率行的心跳间隔（ms）：比 AppCore 的 1.5s 过期窗口密，消失得及时 */
private const val SPEED_TICK_MS = 400L

private fun statusSuffix(m: MsgItem): String = when (m.status) {
    Status.OK -> ""
    Status.PENDING -> " · 待发送"
    Status.SENDING -> " · 发送中"
    Status.FAILED -> " · 失败"
    // 「数据流量未同意」的说明已经写在气泡里（文件名下方 + 末尾红色叹号），
    // 这里不再叠一串字，避免把一行挤成两行。
    Status.DENIED -> ""
    // 接收停滞暂停（v2.8）：灰色 ⏸ 行在文件名下方，时间行补一个短后缀
    Status.PAUSED -> " · 已暂停"
}
