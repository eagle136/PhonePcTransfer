package com.phonepctransfer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.phonepctransfer.AppCore

// 高对比配色（2026-10-01 用户定稿，参考截图）：
//  - 浅色：蓝主色 + 浅灰蓝底；进度条/滑轨浅灰，实心段蓝色
//  - 深色：绿主色 + 近黑底；进度条轨道近白，实心段绿色
private val Light = lightColorScheme(
    primary = Color(0xFF1D5FEF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF0A2A66),
    surfaceVariant = Color(0xFFE9EDF4),
    onSurfaceVariant = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFEFF1F6)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3CD97E),
    primaryContainer = Color(0xFF0E3D24),
    onPrimaryContainer = Color(0xFFD6FFE7),
    surfaceVariant = Color(0xFF16181A),
    onSurfaceVariant = Color(0xFFEAEAEA),
    surface = Color(0xFF1F2124),
    background = Color(0xFF0A0B0C)
)

/** 进度条未填充轨道：浅色=浅灰，深色=近白（与截图滑轨一致，保证高对比） */
@Composable
internal fun trackColor(): Color =
    if (isAppDark()) Color(0xFFE6E6E6) else Color(0xFFC9CFDA)

/**
 * 主题模式（-1 跟随系统 / 0 浅色 / 1 深色）。
 * 初值从 Prefs 读；[toggleAppTheme] 是唯一写入口（同时落盘）。
 */
private var themeMode by mutableIntStateOf(AppCore.prefs.themeMode)

/** 当前是否深色主题（Compose 读取，切换后整个界面跟着重组） */
@Composable
internal fun isAppDark(): Boolean = when (themeMode) {
    0 -> false
    1 -> true
    else -> isSystemInDarkTheme()
}

/** 主题按钮：在当前深浅之间翻转并持久化（翻过一次后不再跟随系统） */
internal fun toggleAppTheme(darkNow: Boolean) {
    val m = if (darkNow) 0 else 1
    themeMode = m
    AppCore.prefs.themeMode = m
}

/**
 * @param dark 由调用方（setContent 根作用域）读 [isAppDark] 传进来。
 * **不能**在这里自己读：实测 PPTheme 内部读状态时，切主题只让按钮文案重组，
 * 整棵树的 LocalColorScheme 不刷新，要等一次结构性变化才跟上（2026-10-01）。
 *
 * 系统栏图标外观**不在这里管**：见 MainActivity 的 `enableEdgeToEdge(window, …)` ——
 * 只设 `isAppearanceLightStatusBars` 会被 ColorOS 的 auto（跟随系统）样式盖回去。
 */
@Composable
fun PPTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
