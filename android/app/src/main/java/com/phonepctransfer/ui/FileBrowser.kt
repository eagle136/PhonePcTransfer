package com.phonepctransfer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonepctransfer.AppCore
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.util.LocalFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * 内置文件浏览器（2026-10-05）：**同一窗口内的全屏页面**，代替系统文件选择器。
 *
 * 为什么要有它：系统选择器（SAF）一次只能选一个文件、或只能整目录授权，用户想挑
 * 「某个目录里的几个文件」非常别扭（交互参考 HybridFileXfer 的 FileSelectAdapter 重做）。
 *
 * 交互（用户 2026-10-05 定稿）：
 *  - **长按文件** = 进入多选 / 切换这一条的选中；**点击已选中的 = 取消选择**
 *    （「选择文件只能靠长按」——所以多选态里点未选中的文件不加进来）
 *  - **长按文件夹** = 只选这个文件夹，并**清空已选文件**（两类互斥）
 *  - 多选里没有文件夹 → 「发送 N 个文件」逐个排队发；是文件夹 → 「发送文件夹」整目录发
 *  - 非多选态：点文件夹进目录、点文件无动作（与参考实现的交互一致）
 *  - 顶栏「↑」返回上级；每层记住滚动位置，返回时恢复
 *
 * 读手机文件的三条路径见 [LocalFs]：普通模式（「所有文件访问」）→ root 模式（设置里手动开）
 * → SAF 目录树（未授权时的降级，此时页面给「去授权 / 改用系统选择器选目录」的引导）。
 */
@Composable
internal fun FileBrowser(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 位置**栈**：末位 = 当前目录（返回上级 = 弹栈）。SAF 模式下没有「查父目录」的接口，
    // 只能靠栈记住来路 —— 与 HybridFileXfer 的 positions 链表同一思路。
    val stack = remember { mutableStateListOf<LocalFs.Loc>() }
    val loc = stack.lastOrNull()

    var list by remember { mutableStateOf(LocalFs.FsList()) }
    var loading by remember { mutableStateOf(true) }

    /** 自增即重新枚举当前目录（刷新 / 出错重试都靠它） */
    var reloadKey by remember { mutableIntStateOf(0) }

    /** 引导态：读不到手机文件时（没权限 / root 没拿到）显示引导页 */
    var guide by remember { mutableStateOf<Guide?>(null) }

    // 选择：文件多选 与 长按选中的文件夹 **互斥**（长按文件夹要忽略已选文件）
    var selFiles by remember { mutableStateOf<List<LocalFs.Entry>>(emptyList()) }
    var selDir by remember { mutableStateOf<LocalFs.Entry?>(null) }

    // 每层滚动位置（键 = 该层路径文案），返回上级时恢复
    val scrollAt = remember { mutableStateMapOf<String, Int>() }
    val listState = rememberLazyListState()

    fun clearSel() {
        selFiles = emptyList()
        selDir = null
    }

    fun curKey(): String = loc?.let { LocalFs.label(it) } ?: ""

    /** 上次由系统选择器授权的目录树（记录不全时返回 null）—— Android 17 上这是主路径 */
    fun savedTreeLoc(): LocalFs.Loc.Tree? {
        val u = AppCore.prefs.safTreeUri
        val d = AppCore.prefs.safTreeDocId
        if (u.isEmpty() || d.isEmpty()) return null
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return null
        return LocalFs.Loc.Tree(uri, d, AppCore.prefs.safTreeLabel.ifEmpty { d })
    }

    /**
     * 定起点（2026-10-05 调顺序）：root 模式（先探 su）→ 普通模式（「所有文件访问」）
     * → **上次用系统选择器授权的目录** → 引导页。
     *
     * 为什么把 SAF 目录排在引导页之前：Android 17 起「所有文件访问」失效（见
     * [LocalFs.isAndroid17Plus]），**系统自带文件选择器授权的目录才是主路径** ——
     * 用户授权过一次以后，再打开浏览器就该直接进那个目录，而不是又看一页引导。
     */
    fun initLoc() {
        val t = savedTreeLoc()
        when {
            LocalFs.rootEnabled -> scope.launch {
                val ok = withContext(Dispatchers.IO) { LocalFs.probeRoot(force = true) }
                if (ok) {
                    guide = null
                    stack.clear()
                    stack.add(LocalFs.Loc.Path(LocalFs.homePath()))
                } else {
                    guide = Guide.ROOT_FAILED
                }
            }
            // Android 17：整盘授权**形同虚设**（见 [LocalFs.isAndroid17Plus]）—— 已有系统选择器
            // 授权的目录就优先用它，免得每次打开都先撞一次「权限给了却读不到」的错误页
            t != null && LocalFs.isAndroid17Plus() -> {
                guide = null
                stack.clear()
                stack.add(t)
            }
            LocalFs.canBrowseNormal() -> {
                guide = null
                stack.clear()
                stack.add(LocalFs.Loc.Path(LocalFs.homePath()))
            }
            t != null -> {
                guide = null
                stack.clear()
                stack.add(t)
            }
            else -> {
                // 没有任何可用后端：Android 17 与老系统的引导文案/按钮不同
                //（17 上不能再劝用户去「所有文件访问」设置页 —— 那是死路）
                guide =
                    if (LocalFs.isAndroid17Plus()) Guide.ONLY_SYSTEM_PICKER else Guide.NO_ALL_FILES
            }
        }
    }

    /**
     * 系统自带的文件选择器：授权一棵目录树。降级路径与 **Android 17 主路径**都用它；
     * 授权成功即**记进 Prefs**（下次打开直接进这个目录，顶栏还有「换目录」）。
     */
    val treePick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                if (docId.isNullOrEmpty()) {
                    AppCore.toast("这个目录拿不到访问权，换一个再试")
                } else {
                    val label = treeLabel(ctx, uri, docId)
                    AppCore.prefs.safTreeUri = uri.toString()
                    AppCore.prefs.safTreeDocId = docId
                    AppCore.prefs.safTreeLabel = label
                    guide = null
                    stack.clear()
                    stack.add(LocalFs.Loc.Tree(uri, docId, label))
                    clearSel()
                }
            }
        }

    LaunchedEffect(Unit) { initLoc() }

    // 列目录：**必须在后台线程**（root 模式要起 su 进程，主线程会卡住界面）
    LaunchedEffect(loc, reloadKey) {
        val l = loc ?: return@LaunchedEffect
        loading = true
        val r = withContext(Dispatchers.IO) { LocalFs.list(ctx, l) }
        list = r
        loading = false
        // 记录的「上次授权目录」已失效（系统回收了授权 / 目录被删）：清掉记录 + 回引导页，
        // 并**明确告诉用户要重选** —— 不静默画一个空目录（沿用仓库铁律：失败绝不冒充空）
        if (r.error != null && l is LocalFs.Loc.Tree && l.treeUri.toString() == AppCore.prefs.safTreeUri) {
            AppCore.prefs.clearSafTree()
            stack.clear()
            guide =
                if (LocalFs.isAndroid17Plus()) Guide.ONLY_SYSTEM_PICKER else Guide.NO_ALL_FILES
            AppCore.toast("上次授权的目录已失效，请重新选一个")
        }
        // 恢复这一层上次的滚动位置（返回上级时回到原处，不被顶到列表头）
        runCatching { listState.scrollToItem(scrollAt[LocalFs.label(l)] ?: 0) }
    }

    fun rememberScroll() {
        scrollAt[curKey()] = listState.firstVisibleItemIndex
    }

    fun enter(e: LocalFs.Entry) {
        if (!e.isDir) return
        rememberScroll()
        clearSel()
        val l = loc
        if (l is LocalFs.Loc.Tree) {
            // SAF：子目录仍是「树 + 文档 id」，标签按「父标签/名字」拼出来给人看
            stack.add(LocalFs.Loc.Tree(l.treeUri, e.key, "${l.label}/${e.name}"))
        } else {
            stack.add(LocalFs.Loc.Path(e.key))
        }
    }

    fun up() {
        if (stack.size <= 1) {
            AppCore.toast("已经在最上面了")
            return
        }
        rememberScroll()
        clearSel()
        stack.removeAt(stack.lastIndex)
    }

    // ==================== 选择交互（用户 2026-10-05 定稿）====================

    fun onTap(e: LocalFs.Entry) {
        val d = selDir
        if (d != null) {
            // 已选中文件夹：点它自己 = 取消选择；点别的 = 忽略（先取消才能改选）
            if (e.isDir && e.key == d.key) selDir = null
            else AppCore.toast("已选中文件夹「${d.name}」：先点「取消选择」才能改选别的")
            return
        }
        if (selFiles.isNotEmpty()) {
            // 多选态**只做「取消已选中的」**：不动用长按就不进选择（用户口径）
            if (selFiles.any { it.key == e.key }) {
                selFiles = selFiles.filterNot { it.key == e.key }
            } else if (e.isDir) {
                AppCore.toast("多选里不混文件夹：长按它可以只选这个文件夹")
            }
            return
        }
        // 非选择态：点文件夹进目录；点文件无动作
        if (e.isDir) enter(e)
    }

    fun onLong(e: LocalFs.Entry) {
        if (e.isDir) {
            // 长按文件夹 = 只选它一个，并**清空已选文件**；再长按一次 = 取消
            val d = selDir
            selFiles = emptyList()
            selDir = if (d != null && d.key == e.key) null else e
            return
        }
        // 长按文件 = 切换多选；当前选着文件夹则互斥退出（改选文件）
        selDir = null
        selFiles = if (selFiles.any { it.key == e.key }) {
            selFiles.filterNot { it.key == e.key }
        } else {
            selFiles + e
        }
    }

    fun doSend() {
        if (AppCore.selectedPeerIp.isNullOrEmpty()) {
            AppCore.toast("还没有目标设备：关掉这里，先点左侧列表里的一台设备")
            return
        }
        val d = selDir
        if (d != null) {
            // 文件夹：整目录发（成员递归枚举在链路层做，见 FileIO.scanTree）
            AppCore.sendFolder(d.uri)
            onClose()
            return
        }
        if (selFiles.isEmpty()) return
        // 没有文件夹 → 发送选中的文件们（一批只确认一次流量，逐个排队）
        AppCore.sendFiles(selFiles.map { it.uri })
        onClose()
    }

    // 系统返回键（全仓第一个 BackHandler，只在这个页内页面上生效）：先取消选择，再往上一层目录走，
    // 已经在最上面就关掉浏览器 —— 不然在浏览器里按返回会把应用退到后台，与「这是一层页面」的直觉不符。
    // 位置必须在 [up] 之后：Kotlin 的局部函数只能引用**前面**声明过的。
    BackHandler {
        if (selDir != null || selFiles.isNotEmpty()) {
            clearSel()
        } else if (stack.size > 1) {
            up()
        } else {
            onClose()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserTopBar(
                path = loc?.let { LocalFs.label(it) } ?: "手机文件",
                sub = when {
                    LocalFs.rootEnabled -> "root 模式 · ${LocalFs.ROOT_MEDIA}"
                    loc is LocalFs.Loc.Tree -> "系统选择器授权目录"
                    else -> null
                },
                canUp = stack.size > 1,
                canPickTree = loc is LocalFs.Loc.Tree,
                onUp = { up() },
                onRefresh = { reloadKey++ },
                onPickTree = { treePick.launch(null) },
                onClose = onClose
            )

            val g = guide
            if (g != null) {
                GuideBody(
                    kind = g,
                    onGrantAllFiles = { openAllFilesSettings(ctx) },
                    onPickTree = { treePick.launch(null) },
                    onRetry = { initLoc() },
                    // Android 17 引导页的「开启 root 模式」：就地开 + 立刻探 su
                    //（通了直接进 /data/media/0，不通会切到 ROOT_FAILED 页如实说明）
                    onTurnOnRoot = {
                        AppCore.prefs.rootFs = true
                        LocalFs.rootEnabled = true
                        initLoc()
                    },
                    onTurnOffRoot = {
                        AppCore.prefs.rootFs = false
                        LocalFs.rootEnabled = false
                        AppCore.toast(
                            if (LocalFs.isAndroid17Plus()) "已关闭 root 模式"
                            else "已关闭 root 模式，改回「所有文件访问」"
                        )
                        initLoc()
                    },
                    onClose = onClose
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val err = list.error
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        err != null -> ErrorBody(
                            err,
                            onRetry = { reloadKey++ },
                            onPickTree = { treePick.launch(null) }
                        )
                        else -> EntryList(
                            entries = list.entries,
                            canUp = stack.size > 1,
                            selFiles = selFiles,
                            selDir = selDir,
                            listState = listState,
                            onUp = { up() },
                            onTap = { onTap(it) },
                            onLong = { onLong(it) }
                        )
                    }
                }
                BrowserBottomBar(
                    selCount = selFiles.size,
                    selDirName = selDir?.name,
                    onCancelSel = { clearSel() },
                    onSend = { doSend() }
                )
            }
        }
    }
}

/** 引导页的三种情形（都出现在「读不到手机文件」时） */
private enum class Guide {
    /**
     * **Android 17+**：整盘访问已被系统关闭（「所有文件访问」不再生效，见 [LocalFs.isAndroid17Plus]）——
     * 只剩两条路：用系统自带的文件选择器授权一个目录，或开 root。这一页**不提供**
     * 「去系统设置授权」按钮（那条路用户已经被系统顶回来过了）。
     */
    ONLY_SYSTEM_PICKER,

    /** 老系统（Android 11~16）：没有「所有文件访问」，且没开 root 模式 */
    NO_ALL_FILES,

    /** 开了 root 模式但没拿到 root（KernelSU/Magisk 未授权，或本机没 root） */
    ROOT_FAILED
}



// ==================== 子组件 ====================

/**
 * 顶栏：左「↑ 返回上级」、中间路径、右「刷新 / ✕ 关闭」。
 * 状态栏内边距加在 Surface **内部**（与 [HeaderBar] 同一口径）：
 * 底色一直铺到屏幕最顶边，时间/电量那一条与顶栏同色。
 */
@Composable
private fun BrowserTopBar(
    path: String,
    sub: String?,
    canUp: Boolean,
    /** SAF 树模式才给「换目录」（Android 17 上主要靠它改授权目录） */
    canPickTree: Boolean,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onPickTree: () -> Unit,
    onClose: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        // 栏 + 下缘分界线（2026-10-05 高对比度整改）：surfaceVariant 与页面底 background 只差
        // 1.04:1（浅）/1.11:1（深），不加线整条栏都「看不见」；线用 outline —— 对栏底 3.88:1（浅）/
        // 5.62:1（深），满足 WCAG 1.4.11 的 3:1。**不能**用 HorizontalDivider 的默认色
        // outlineVariant（浅色只有 ~1.35:1，等于没画；DeviceList/SettingsSheet 用默认色是列表内部分隔，
        // 语境不同）。分隔线放在 statusBars 内边距**之下** = 栏的视觉下缘。
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onUp, enabled = canUp) {
                    GlyphIcon(Glyph.ARROW_UP, 18.dp)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        path,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (sub != null) {
                        // root 模式要让人一眼看出「现在读的不是 /sdcard，而是它的底层目录」
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                if (canPickTree) TextButton(onClick = onPickTree) { Text("换目录") }
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onClose) { GlyphIcon(Glyph.CLOSE, 16.dp) }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 目录列表：首行「..（返回上级）」+ 条目（目录在前、文件在后，排序见 [LocalFs]） */
@Composable
private fun EntryList(
    entries: List<LocalFs.Entry>,
    canUp: Boolean,
    selFiles: List<LocalFs.Entry>,
    selDir: LocalFs.Entry?,
    listState: LazyListState,
    onUp: () -> Unit,
    onTap: (LocalFs.Entry) -> Unit,
    onLong: (LocalFs.Entry) -> Unit
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (canUp) {
            item(key = "parent-row") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onUp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.FOLDER, 20.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("..（返回上级）", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(entries, key = { it.key }) { e ->
            val selected = (selDir != null && selDir.key == e.key) || selFiles.any { it.key == e.key }
            EntryRow(e, selected, onTap = { onTap(e) }, onLong = { onLong(e) })
        }
        if (entries.isEmpty()) {
            item(key = "empty-row") {
                Text(
                    "这个文件夹是空的",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/** 选中行左侧竖条宽度（2026-10-05 高对比度整改定稿；配合 [EntryRow] 的 drawBehind） */
private val SEL_BAR_W = 3.dp

/** 一行：图标（选中 → 对勾）+ 名字 + 大小/时间 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: LocalFs.Entry, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 选中提示的「第三条通道」（2026-10-05 高对比度整改）：选中行底色 primaryContainer 与页面底
    // background 只差 1.13:1（浅）/1.61:1（深），底色这一路不足以让人一眼看出选中，补一条 primary
    // 竖条 —— 对页面底 浅 4.72:1 / 深 10.71:1（≥ WCAG 1.4.11 要求的 3:1），而且「左侧多一条」不依赖
    // 颜色，色觉障碍用户也认得出。
    // 画在 `.background(bg)` **之后**（先底色后竖条）；用 drawBehind 而不是铺一个 3dp 的 Box：
    // LazyColumn 给 item 的高度约束是 unbounded，fillMaxHeight 会退化成 0 高（得再套 IntrinsicSize.Min）。
    val barColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .drawBehind {
                if (selected) drawRect(color = barColor, size = Size(SEL_BAR_W.toPx(), size.height))
            }
            .combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(
            if (selected) Glyph.CHECK else if (e.isDir) Glyph.FOLDER else Glyph.FILE,
            20.dp, tint
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subText(e),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


/** 读不到目录时的说明 + 重试（**不画空列表** —— 失败与「空目录」必须让人分得清） */
@Composable
private fun ErrorBody(err: String, onRetry: () -> Unit, onPickTree: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // 「⚠」→ 矢量 [Glyph.WARN]（全仓去 emoji）；Row 顶对齐，图标留在第一行左侧
        Row {
            GlyphIcon(
                Glyph.WARN, 18.dp, MaterialTheme.colorScheme.error,
                Modifier.padding(top = 2.dp)
            )
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text("重试") }
            // Android 17：整盘读取被系统关掉时，「重试」永远是错的 —— 必须给出口，
            // 否则用户会卡死在这一页（现象：权限明明给了，却只看到拒绝读取）。
            if (LocalFs.isAndroid17Plus()) {
                Button(onClick = onPickTree) { Text("用系统选择器选目录") }
            }
        }
    }
}

/** 底栏：左侧选择摘要 + 「取消选择」，右侧发送按钮（文案随选择内容变） */
@Composable
private fun BrowserBottomBar(
    selCount: Int,
    selDirName: String?,
    onCancelSel: () -> Unit,
    onSend: () -> Unit
) {
    val hasSel = selDirName != null || selCount > 0
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        // 上缘分界线（2026-10-05 高对比度整改，理由同 [BrowserTopBar]）：outline 对栏底
        // 浅 3.88:1 / 深 5.62:1；线放在 navigationBars 内边距**之上** = 栏的视觉上缘。
        Column {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSel) {
                    Text(
                        text = selDirName?.let { "已选文件夹：$it" } ?: "已选 $selCount 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancelSel) { Text("取消选择") }
                } else {
                    Text(
                        text = "长按文件多选 · 长按文件夹选中整个文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(onClick = onSend, enabled = hasSel) {
                    Text(
                        selDirName?.let { "发送文件夹" }
                            ?: if (selCount > 0) "发送 $selCount 个文件" else "发送"
                    )
                }
            }
        }
    }
}


/** 引导页：读不到手机文件时给出的可行路径（Android 17 与老系统文案/按钮不同，见 [Guide]） */
@Composable
private fun GuideBody(
    kind: Guide,
    onGrantAllFiles: () -> Unit,
    onPickTree: () -> Unit,
    onRetry: () -> Unit,
    onTurnOnRoot: () -> Unit,
    onTurnOffRoot: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        when (kind) {
            // Android 17+：明确提醒「只能用系统自带文件选择器，或用 root 权限」——
            // 用户 2026-10-05 真机实测后要的口径，也是唯一两条走得通的路。
            // 按钮顺序＝系统选择器（主）→ root（次）；**故意不出现**「去系统设置授权」。
            Guide.ONLY_SYSTEM_PICKER -> {
                Text("Android 17：只能用系统选择器或 root", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "从 Android 17 起，系统不再让应用整盘浏览文件：「所有文件访问」就算给了也不生效" +
                        "（所以这里不再引导你去系统设置 —— 去了也会被系统顶回来）。\n\n" +
                        "现在只有两条路：\n" +
                        "① 用「系统自带的文件选择器」授权一个目录：浏览器只在这个目录里用，" +
                        "进去以后照样能长按多选、能整目录发送；\n" +
                        "② 开启 root 模式：用 su 直接读全盘（连 /Android/data 也能看）。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                // 两个按钮的文案都偏长，竖排避免窄屏挤成省略号
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickTree) { Text("用系统选择器选目录") }
                    OutlinedButton(onClick = onTurnOnRoot) { Text("开启 root 模式") }
                }
            }
            Guide.NO_ALL_FILES -> {
                Text("还没有「所有文件访问」权限", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "内置文件浏览器要读 /sdcard 上的任意目录，Android 11+ 需要这一档权限" +
                        "（系统限制，只能在设置页手动开）。\n\n" +
                        "不想开也行：用下面的系统选择器授权一个目录，浏览器就在那个目录里用。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGrantAllFiles) { Text("去系统设置授权") }
                    OutlinedButton(onClick = onPickTree) { Text("选一个目录来用") }
                }
            }
            Guide.ROOT_FAILED -> {
                Text("root 模式打不开 ${LocalFs.ROOT_MEDIA}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                // Android 17 上「所有文件访问」已失效 → 收尾建议按版本分流，别再劝用户改回那条路
                val tail = if (LocalFs.isAndroid17Plus()) "改用系统选择器授权一个目录即可。"
                else "改回「所有文件访问」即可。"
                Text(
                    "设置里开了「用 root 访问」，但没拿到 root 权限：\n" +
                        "· 装了 KernelSU/Magisk 的话，请在它的管理界面里给本应用授权；\n" +
                        "· 本机没有 root 的话，关掉那一项，$tail",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("重试") }
                    OutlinedButton(onClick = onTurnOffRoot) { Text("关闭 root 模式") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onClose) { Text("返回聊天") }
    }
}

// ==================== 小工具 ====================

/** 列表第二行：文件夹显示「文件夹 · 修改时间」，文件显示「大小 · 修改时间」 */
private fun subText(e: LocalFs.Entry): String {
    val t = if (e.mtime > 0) dateOf(e.mtime) else ""
    if (e.isDir) return if (t.isEmpty()) "文件夹" else "文件夹 · $t"
    val size = if (e.size > 0) TextUtil.humanSize(e.size) else "大小未知"
    return if (t.isEmpty()) size else "$size · $t"
}

/** 只从界面线程调用（SimpleDateFormat 非线程安全，这里给的是顶层单例） */
private val DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

private fun dateOf(ms: Long): String = DATE_FMT.format(java.util.Date(ms))

/** SAF 树的根目录显示名（拿不到就退回 documentId） */
private fun treeLabel(ctx: Context, treeUri: Uri, docId: String): String = runCatching {
    ctx.contentResolver.query(
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: docId

/** 跳「所有文件访问」设置页（API 30+ 才有这一档；个别 ROM 删了该页则退到总列表） */
private fun openAllFilesSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        .setData(Uri.parse("package:${ctx.packageName}"))
    if (runCatching { ctx.startActivity(appPage) }.isFailure) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure { AppCore.toast("这台机器没有这个设置页") }
    }
}
