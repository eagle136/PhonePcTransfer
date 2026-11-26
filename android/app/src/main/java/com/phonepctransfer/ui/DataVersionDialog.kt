package com.phonepctransfer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 版本变化提示（启动一问）：是否需要清除应用数据。
 *
 * 「清除数据」→ 聊天记录/待发队列/半成品台账/设备与设置全部重置（**已接收的文件保留**）；
 * 「保留数据」→ 只把数据里记录的版本号改成当前版本，之后不再提示。
 * 必须二选一：返回键/点弹窗外不关闭（否则下次启动还会问，等于没回答）。
 */
@Composable
fun DataVersionDialog(
    fromVersion: String,
    toVersion: String,
    onClear: () -> Unit,
    onKeep: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 必须选一项，不允许关掉 */ },
        title = { Text("检测到版本变化", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "版本：" + (fromVersion.ifBlank { "未知" }) + " → " + (toVersion.ifBlank { "当前版本" })
                    + "\n\n是否需要清除应用数据（不影响已接收的文件）？"
                    + "\n\n清除：聊天记录、待发队列、半成品台账、设备与设置全部重置。"
                    + "\n保留：沿用现有数据，只更新数据里的版本记录，之后不再提示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = { TextButton(onClick = onClear) { Text("清除数据") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("保留数据") } },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}
