package com.phonepctransfer.ui

import com.phonepctransfer.call.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonepctransfer.AppCore
import com.phonepctransfer.util.ClipCaptureService
import com.phonepctransfer.util.LocalFs

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tcpPort by remember { mutableStateOf(AppCore.prefs.tcpPort.toString()) }
    var udpPort by remember { mutableStateOf(AppCore.prefs.udpPort.toString()) }
    var autoSync by remember { mutableStateOf(AppCore.prefs.autoSyncOnSend) }
    var forceLan by remember { mutableStateOf(AppCore.prefs.forceLanEnabled) }
    var clipSync by remember { mutableStateOf(AppCore.prefs.clipSyncMode) }
    // 内置文件浏览器的 root 开关（2026-10-05）：本地一件状态，开关与 Prefs/LocalFs 同步
    var rootFs by remember { mutableStateOf(AppCore.prefs.rootFs) }
    // 本应用记住的「这类文件用哪个程序打开」（2026-10-15）：本地快照，重置后要立刻反映到界面上
    var openers by remember { mutableStateOf(AppCore.prefs.openers()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("设备管理", style = MaterialTheme.typography.labelLarge)
                Text(
                    "点设备即连接；长按可改备注 / IP / 删除。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "当前设备：${
                        AppCore.peers.joinToString { "${it.displayName}(${it.ip})" }
                            .ifEmpty { "（无）" }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = tcpPort,
                        onValueChange = { tcpPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("TCP 端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                    OutlinedTextField(
                        value = udpPort,
                        onValueChange = { udpPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("UDP 端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(onClick = {
                    tcpPort.toIntOrNull()?.let { AppCore.prefs.tcpPort = it }
                    udpPort.toIntOrNull()?.let { AppCore.prefs.udpPort = it }
                    AppCore.stopRun()
                    AppCore.refreshLan()
                }) { Text("应用端口并重启传输") }

                HorizontalDivider()
                Text("剪贴板", style = MaterialTheme.typography.labelLarge)
                SwitchRow("剪贴板同步模式（全自动）", clipSync) {
                    clipSync = it
                    AppCore.onClipSyncModeChanged(it)
                }
                Text(
                    if (clipSync)
                        "已开启：双向同步剪贴板。"
                    else
                        "已关闭：需手动推送或同步。",
                    style = MaterialTheme.typography.bodySmall
                )
                SwitchRow("主动发送后自动同步一次", autoSync) {
                    autoSync = it; AppCore.prefs.autoSyncOnSend = it
                }
                TextButton(onClick = { AppCore.pushClipIfChanged(force = true) }) {
                    Text("立即推送本机剪贴板")
                }

                HorizontalDivider()
                A11yRow()

                // 某一问上点「不需要」会把那一项永久关掉；这里留一个总反悔入口，
                // 否则「显示悬浮窗 / 全屏通知」这类只能手动开的权限就再没有任何引导路径了。
                TextButton(onClick = {
                    AppCore.prefs.permNeverAsk = emptySet()
                    AppCore.toast("下次启动时会逐项重新询问权限与后台保活")
                }) { Text("重新开启启动权限与保活询问") }

                SwitchRow("强制启用传输（调试）", forceLan) {
                    forceLan = it; AppCore.prefs.forceLanEnabled = it; AppCore.refreshLan()
                }

                HorizontalDivider()
                Text("存储与目录", style = MaterialTheme.typography.labelLarge)
                Text(
                    "接收的文件保存到「下载/手机电脑互传」",
                    style = MaterialTheme.typography.bodySmall
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val granted = Environment.isExternalStorageManager()
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }) { Text(if (granted) "所有文件访问权限：已授予" else "授予所有文件访问权限（可选）") }
                }

                // ---------- 内置文件浏览器的 root 模式（2026-10-05）----------
                // 默认关。有「所有文件访问」时不需要它；开了才走 su（KernelSU/Magisk 都支持），
                // 文件浏览器与「发送文件夹」的浏览根变成 /data/media/0 —— 好处是连
                // /Android/data、/Android/obb 也能读（那两处是系统的 FUSE 限制，光有
                // MANAGE_EXTERNAL_STORAGE 也读不到）。用户定调：这一项只在这里手动开。
                SwitchRow("文件浏览用 root 读 /data/media/0（须 KernelSU/Magisk 授权）", rootFs) {
                    rootFs = it
                    AppCore.prefs.rootFs = it
                    LocalFs.rootEnabled = it
                    AppCore.toast(
                        if (it) "已开启：文件浏览器改读 /data/media/0（首次会向 root 管理器申请授权）"
                        else "已关闭：文件浏览器回到「所有文件访问」模式"
                    )
                }
                Text(
                    "开启后浏览器与发送文件夹都过 su；关掉即回普通模式，随时可切。" +
                        "本机没有 root 时请保持关闭，否则浏览器会提示读不到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rootFs) {
                    TextButton(onClick = {
                        // su 探测要起进程 → 后台跑；toast 自己会回主线程
                        AppCore.toast("正在测试 root …")
                        Thread {
                            val ok = LocalFs.probeRoot(force = true)
                            AppCore.toast(
                                if (ok) "root 可用：/data/media/0 可以读了"
                                else "root 不可用：请在 KernelSU/Magisk 里给本应用授权，或关掉这一项"
                            )
                        }.apply { isDaemon = true }.start()
                    }) { Text("测试 root 权限") }
                }

                HorizontalDivider()
                Text("文件打开方式", style = MaterialTheme.typography.labelLarge)
                Text(
                    "点文件时选过一次程序，本应用就记住「这类文件用它打开」，以后同类文件直接用它；" +
                        "换过程序就改的是这条记忆。系统里的默认应用不受影响。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    openers.joinToString("\n") { "${it.first} → ${openerLabel(ctx, it.second)}" }
                        .ifEmpty { "当前：（还没记住任何类型，点文件时会弹列表）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    val n = AppCore.prefs.clearOpeners()
                    openers = AppCore.prefs.openers()
                    AppCore.toast(
                        if (n > 0) "已清除 $n 条记住的打开方式，下次点文件重新选程序"
                        else "没有需要清除的打开方式"
                    )
                }) { Text("重置记住的打开方式") }

                HorizontalDivider()
                Text("后台可连接的设备（已知设备）", style = MaterialTheme.typography.labelLarge)
                Text(
                    "连接过的设备退后台后仍可连回本机；其它设备只能在前台手动连接。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("当前：${AppCore.trustedSummary()}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { AppCore.clearTrusted() }) { Text("清空已知设备名单") }

                HorizontalDivider()
                Text("最近日志", style = MaterialTheme.typography.labelLarge)
                Text(AppCore.lastLog.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)

                // 「清空」单独成区：**逐条删除**（2026-09-21 起，与 PC 端同口径）——
                // 不再整删历史文件，否则对端一连上就被 hsync 把文本/图片补回来。
                HorizontalDivider()
                Text("清空", style = MaterialTheme.typography.labelLarge)
                Text(
                    "逐条删除记录，对方在线时同步删除；已传完的文件不受影响。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { AppCore.clearHistory() }) { Text("清空聊天记录") }
                    TextButton(onClick = { AppCore.clearPending() }) { Text("清空待发") }
                }
            }
        }
    )
}

/**
 * 后台保活（无障碍，推荐）。
 *
 * 仅用于让本进程在后台更难被系统冻结/杀掉，从而保证文件传输与语音通话在退到
 * 后台后仍能持续运行。本服务不读取任何屏幕内容、不读取剪贴板。
 */
@Composable
private fun A11yRow() {
    val ctx = LocalContext.current
    // 从系统无障碍设置页返回时自动刷新状态（MainActivity 本身就是 LifecycleOwner）
    val owner = ctx as? androidx.lifecycle.LifecycleOwner
    var enabled by remember { mutableStateOf(ClipCaptureService.isEnabled(ctx)) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = ClipCaptureService.isEnabled(ctx)
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    Text(
        "后台保活（无障碍，推荐）：仅用于保持后台传输与通话连接，不读取屏幕内容与剪贴板。",
        style = MaterialTheme.typography.bodySmall
    )
    TextButton(onClick = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }) {
        // 「✓」→ 矢量 [Glyph.CHECK]（2026-09-19 去 emoji/符号字符）。
        // 图标放**文字前面**：原本文案是「已开启 ✓（点此管理）」，那个尾巴上的 ✓ 是
        // 「状态标记」而不是标点，提到行首当状态指示更清楚，也不会在换行时被甩到下一行。
        // 只在已开启时出现 —— 没开启时不必再画一个「否」的图标。
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (enabled) GlyphIcon(Glyph.CHECK, size = 14.dp)
            Text(
                text = if (enabled) "后台保活无障碍：已开启（点此管理）"
                else "去开启后台保活无障碍（推荐）",
                modifier = Modifier.padding(start = if (enabled) 5.dp else 0.dp)
            )
        }
    }
}

/**
 * 记住的打开方式那行要显示的应用名。入参是 `"包名/类名"`。
 *
 * 取不到 label 是**正常情况**（Android 11+ 包可见性：本应用没声明 `<queries>`，
 * 用 PM 查别的应用信息会抛 NameNotFoundException），此时退回显示包名 ——
 * 只是这一行不够好看，打开文件那条路径用的是显式组件 intent，不受可见性影响。
 */
private fun openerLabel(ctx: Context, flatComponent: String): String {
    val pkg = flatComponent.substringBefore('/')
    return runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
