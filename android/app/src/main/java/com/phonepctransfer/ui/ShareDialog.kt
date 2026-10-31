package com.phonepctransfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonepctransfer.AppCore
import com.phonepctransfer.data.PeerGroup

/**
 * 从系统「分享」进来后的目标设备选择框。
 *
 * 流程：别的应用点分享 → 选中本应用 → 这里选一台设备 → 定向连接该设备并立刻开始传输
 *（选完即在前台看着进度走）。候选就是左侧设备列表里的那几台，已连接的那台默认选中。
 *
 * 状态放在 [AppCore.pendingShare]，所以应用冷启动 / 已在后台被 singleTask 唤起两条路径
 * 都能落到同一个弹窗上。
 */
@Composable
fun ShareTargetDialog() {
    val payload = AppCore.pendingShare ?: return
    // 选目标按**设备**（组）而不是按地址：同一台机器有多个地址时只列一条，
    // 实际走它当前使用的那个地址（见 AppCore.shareTo）
    val peers = AppCore.peerGroups

    var picked by remember(payload) {
        mutableStateOf(
            AppCore.currentGroup()?.key ?: peers.firstOrNull()?.key
        )
    }
    // 冷启动时设备列表可能这一帧还没读出来（peers 先空后有），选中项失效就补个默认值
    LaunchedEffect(payload, peers.size) {
        if (picked == null || peers.none { it.key == picked }) {
            picked = AppCore.currentGroup()?.key ?: peers.firstOrNull()?.key
        }
    }
    val target = peers.firstOrNull { it.key == picked }

    AlertDialog(
        onDismissRequest = { AppCore.cancelShare() },
        title = { Text("发送到哪台设备？") },
        text = {
            Column {
                Text(
                    payload.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                ThinDivider()

                if (peers.isEmpty()) {
                    Text(
                        "还没有可用设备。\n请先在左侧列表「＋设备」填上电脑 IP，" +
                            "或确认电脑端已在同一 WiFi 下运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        peers.forEach { p ->
                            PeerChoiceRow(
                                peer = p,
                                checked = p.key == picked,
                                connected = AppCore.isGroupOnline(p),
                                onPick = { picked = p.key }
                            )
                        }
                    }
                }

                if (!AppCore.lanInfo.available) {
                    Spacer(Modifier.size(6.dp))
                    // 「⚠」→ 矢量 [Glyph.WARN]（2026-09-19 去 emoji）。Row 默认顶对齐：
                    // 这段文字会折成好几行，图标留在**第一行**左侧才像「警告标记」，
                    // 垂直居中会让它飘在整段文字的中间。
                    Row {
                        GlyphIcon(
                            Glyph.WARN,
                            size = 13.dp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Text(
                            "当前没有可用的 WiFi / 热点（${AppCore.lanInfo.detail}）。" +
                                "选中后内容会先排队，网络恢复会自动发送。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { target?.let { AppCore.shareTo(it) } },
                enabled = target != null
            ) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = { AppCore.cancelShare() }) { Text("取消") }
        }
    )
}

@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun PeerChoiceRow(peer: PeerGroup, checked: Boolean, connected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = checked, onClick = onPick)
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (connected) "${peer.curIp} · 已连接" else peer.curIp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
