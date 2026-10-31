package com.phonepctransfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.phonepctransfer.util.BootPerm

/**
 * 单项权限说明弹窗（2026-10-01 用户定调：**一项一问**，不再合并成一张清单）。
 *
 * 每个权限单独弹一次，用户这一项怎么答只影响这一项：
 *  - 「前往」  → 由 MainActivity 发起该项的申请（运行时弹框 / 跳对应系统设置页）
 *  - 「取消」  → 本次不申请，**下次冷启动再问**（不落任何标记）
 *  - 「不需要」→ 该项 id 写进 Prefs.permNeverAsk，以后不再问该项，其它项照旧
 *
 * 返回键 / 点弹窗外等同「取消」：这一问不再是启动闸门（旧版点「取消退出」会杀进程，
 * 把「先看一眼设备列表再决定」的用法堵死了），所以静默关掉没有任何风险。
 */
@Composable
fun PermAskDialog(
    perm: BootPerm,
    onGo: () -> Unit,
    onCancel: () -> Unit,
    onNever: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("使用「${perm.title}」需要授权", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                perm.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onGo) { Text("前往") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNever) { Text("不需要") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    )
}

/**
 * 「要不要使用无障碍保活」——**独立一问，两按钮**（2026-10-01 用户定调）。
 *
 * 和权限队列分开：保活不是任何功能的前提，不与悬浮窗 / 通知那些项混在一起。
 *  - 「需要」  → 跳系统无障碍设置页（= 原清单里那一项的流程）
 *  - 「不需要」→ 记 Prefs.permNeverAsk 里的 `a11y`，以后冷启动不再问
 *  - 点弹窗外 / 返回键 → 本次不选、**也不记标记**，下次冷启动还会问
 * 设置页里的「去开启后台保活无障碍」不受这里任何结果影响，随时可手动开启。
 */
@Composable
fun A11yKeepAliveDialog(
    onNeed: () -> Unit,
    onNoNeed: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("要不要使用无障碍保活", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                "开启后本应用退到后台时更难被系统冻结，文件传输与通话不会中断。" +
                    "该服务不读取屏幕内容、不读取剪贴板、不响应任何操作。" +
                    "选「不需要」以后不再询问，之后可在设置页里手动开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onNeed) { Text("需要") }
        },
        dismissButton = {
            TextButton(onClick = onNoNeed) { Text("不需要") }
        }
    )
}
