package com.phonepctransfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 应用内**矢量图标**（2026-09-19 用户定调：两端去 emoji，改用等价 SVG 图标）。
 *
 * 为什么必须换：原来界面上写的是 `📋 📁 📄 ✅ 🖼 ⚠ ✕ ▼` 这类字符。
 *  - emoji（📋📁📄✅🖼）在不同 ROM / 字体下**宽度、颜色、基线全不一样**，ColorOS 上是彩色
 *    贴图、某些精简字体下直接是**方框**（tofu）；
 *  - `✕ ▼` 这类几何符号虽然覆盖率高，但字号一变就与旁边的文字**对不齐**（不在同一视觉中线）。
 *
 * 这里的做法与 PC 端 [com.phonepctransfer.pc.CallIcons] 同构：**全部用 Canvas 手绘**
 *（等价于一张 SVG），不引 drawable 资源、不依赖任何字体覆盖 —— 缩放无损、颜色由参数给、
 * 与文字用 `Row(verticalAlignment = CenterVertically)` 对齐，彻底摆脱「显示问题」。
 *
 * 设计稿坐标系固定 **24×24**，实际绘制时统一乘 `u = minDimension / 24`，因此任何尺寸都清晰。
 *
 * @param kind 图标种类
 * @param size 图标边长（正方形）。建议与相邻文字的字号同量级（labelSmall ≈ 11~13dp）
 * @param color 着色；**默认取 `LocalContentColor`**（跟随按钮/气泡的当前文字色），
 *              需要语义色（绿√ / 红⚠ / 白✕）时显式传入
 */
internal enum class Glyph {
    /** 剪贴板（剪贴板气泡标签） */
    CLIPBOARD,

    /** 文件夹（文件夹传输气泡） */
    FOLDER,

    /** 文档（单个文件气泡） */
    FILE,

    /** 对勾（已保存 / 已开启） */
    CHECK,

    /** 警示三角 + 镂空叹号（顶栏 Banner / 分享弹窗 / 流量被拒原因） */
    WARN,

    /** 图片（缩略图占位） */
    IMAGE,

    /** 叉（全屏查看的关闭按钮） */
    CLOSE,

    /** 实心下三角（顶栏地址下拉箭头） */
    TRIANGLE_DOWN,

    /** 齿轮（左侧面板底部的「设置」入口） */
    GEAR,

    /** 暂停（接收停滞暂停气泡：灰色 ⏸ + 「已收 pct%，重连后自动续传」） */
    PAUSE,

    /** 太阳（底部「主题」按钮：当前为浅色态时显示） */
    SUN,

    /** 月亮（底部「主题」按钮：当前为深色态时显示） */
    MOON,

    /** 向上箭头（内置文件浏览器的「返回上级目录」） */
    ARROW_UP
}

/**
 * 画一枚矢量图标。
 *
 * 用法（与文字同行时**必须**用 Row 居中对齐，不要塞进字符串）：
 * ```
 * Row(verticalAlignment = Alignment.CenterVertically) {
 *     GlyphIcon(Glyph.FILE, 14.dp)
 *     Text(name, modifier = Modifier.padding(start = 5.dp))
 * }
 * ```
 */
@Composable
internal fun GlyphIcon(
    kind: Glyph,
    size: Dp,
    color: Color? = null,
    modifier: Modifier = Modifier
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier = modifier.size(size)) {
        // u = 设计稿 1 单位对应的像素；**用 minDimension**（不是 width）→ 非正方形约束下也不变形
        val u = this.size.minDimension / 24f
        when (kind) {
            Glyph.CLIPBOARD -> drawClipboard(u, tint)
            Glyph.FOLDER -> drawFolder(u, tint)
            Glyph.FILE -> drawFile(u, tint)
            Glyph.CHECK -> drawCheck(u, tint)
            Glyph.WARN -> drawWarn(u, tint)
            Glyph.IMAGE -> drawImageGlyph(u, tint)
            Glyph.CLOSE -> drawClose(u, tint)
            Glyph.TRIANGLE_DOWN -> drawTriangleDown(u, tint)
            Glyph.GEAR -> drawGear(u, tint)
            Glyph.PAUSE -> drawPause(u, tint)
            Glyph.SUN -> drawSun(u, tint)
            Glyph.MOON -> drawMoon(u, tint)
            Glyph.ARROW_UP -> drawArrowUp(u, tint)
        }
    }
}

/** 极坐标 → 画布坐标（角度以**正右方为 0°、顺时针为正**，与 24×24 设计稿一致） */
private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
}

/** 剪贴板：圆角外框 + 顶部实心夹子（= 原「📋」） */
private fun DrawScope.drawClipboard(u: Float, c: Color) {
    drawRoundRect(
        color = c,
        topLeft = Offset(5f * u, 4.6f * u),
        size = Size(14f * u, 16.4f * u),
        cornerRadius = CornerRadius(2.2f * u),
        style = Stroke(width = 1.9f * u, join = StrokeJoin.Round)
    )
    drawRoundRect(
        color = c,
        topLeft = Offset(9f * u, 2.4f * u),
        size = Size(6f * u, 4.4f * u),
        cornerRadius = CornerRadius(1.5f * u),
        style = Fill
    )
}

/** 文件夹：上标签 + 主体两块实心圆角矩形（= 原「📁」） */
private fun DrawScope.drawFolder(u: Float, c: Color) {
    drawRoundRect(
        color = c,
        topLeft = Offset(2.4f * u, 5.2f * u),
        size = Size(9.4f * u, 6.4f * u),
        cornerRadius = CornerRadius(1.7f * u),
        style = Fill
    )
    drawRoundRect(
        color = c,
        topLeft = Offset(2.4f * u, 8.2f * u),
        size = Size(19.2f * u, 12.2f * u),
        cornerRadius = CornerRadius(2.5f * u),
        style = Fill
    )
}

/** 文档：折角纸张（= 原「📄」） */
private fun DrawScope.drawFile(u: Float, c: Color) {
    val body = Path().apply {
        moveTo(6f * u, 2.6f * u)
        lineTo(13.6f * u, 2.6f * u)
        lineTo(19f * u, 8f * u)
        lineTo(19f * u, 21.4f * u)
        lineTo(6f * u, 21.4f * u)
        close()
    }
    drawPath(body, c, style = Stroke(width = 1.8f * u, join = StrokeJoin.Round))
    val fold = Path().apply {
        moveTo(13.6f * u, 2.6f * u)
        lineTo(13.6f * u, 8f * u)
        lineTo(19f * u, 8f * u)
    }
    drawPath(fold, c, style = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 对勾（= 原「✅ / ✓」，颜色由调用方给） */
private fun DrawScope.drawCheck(u: Float, c: Color) {
    val p = Path().apply {
        moveTo(4.6f * u, 12.8f * u)
        lineTo(9.8f * u, 18f * u)
        lineTo(19.4f * u, 6.4f * u)
    }
    drawPath(p, c, style = Stroke(width = 2.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * 警示三角（= 原「⚠」）：实心三角 + **镂空**叹号。
 *
 * 镂空靠 `PathFillType.EvenOdd`：三角与叹号两个子路径重叠的部分被挖空，
 * 于是叹号呈现为底色（气泡/横幅的背景色），不需要知道背景具体是什么颜色 —— 这是
 * 「drawPath 组合挖孔」的标准写法，比画两遍（先画三角再画一个背景色的叹号）稳。
 */
private fun DrawScope.drawWarn(u: Float, c: Color) {
    val p = Path().apply {
        fillType = PathFillType.EvenOdd
        // 三角形（顶点在上，底边在下的等腰）
        moveTo(12f * u, 2.4f * u)
        lineTo(22.4f * u, 20.8f * u)
        lineTo(1.6f * u, 20.8f * u)
        close()
        // 叹号竖条
        addRoundRect(
            RoundRect(
                left = 10.85f * u, top = 9f * u, right = 13.15f * u, bottom = 15.4f * u,
                radiusX = 1.15f * u, radiusY = 1.15f * u
            )
        )
        // 叹号圆点
        addOval(Rect(center = Offset(12f * u, 17.9f * u), radius = 1.3f * u))
    }
    drawPath(p, c)
}

/** 图片：圆角外框 + 太阳 + 山（= 原「🖼」） */
private fun DrawScope.drawImageGlyph(u: Float, c: Color) {
    drawRoundRect(
        color = c,
        topLeft = Offset(2.6f * u, 4f * u),
        size = Size(18.8f * u, 16f * u),
        cornerRadius = CornerRadius(2.6f * u),
        style = Stroke(width = 1.8f * u, join = StrokeJoin.Round)
    )
    drawCircle(color = c, radius = 1.75f * u, center = Offset(8.6f * u, 9.4f * u))
    val hill = Path().apply {
        moveTo(4.4f * u, 18.2f * u)
        lineTo(10f * u, 12.4f * u)
        lineTo(13.8f * u, 16.2f * u)
        lineTo(16.6f * u, 13.6f * u)
        lineTo(19.6f * u, 18.2f * u)
        close()
    }
    drawPath(hill, c)
}

/** 叉（= 原「✕」，全屏查看的关闭按钮） */
private fun DrawScope.drawClose(u: Float, c: Color) {
    val w = 2.3f * u
    drawLine(c, Offset(6f * u, 6f * u), Offset(18f * u, 18f * u), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(c, Offset(18f * u, 6f * u), Offset(6f * u, 18f * u), strokeWidth = w, cap = StrokeCap.Round)
}

/**
 * 向上箭头（内置文件浏览器的「返回上级目录」）。
 * 竖线 + 两撇斜线，与 [drawClose] 同一套线宽口径，放进 24×24 网格不会与旁边的字错位。
 */
private fun DrawScope.drawArrowUp(u: Float, c: Color) {
    val w = 2.2f * u
    drawLine(
        c, Offset(12f * u, 19.6f * u), Offset(12f * u, 5.4f * u),
        strokeWidth = w, cap = StrokeCap.Round
    )
    val head = Path().apply {
        moveTo(5.8f * u, 11.6f * u)
        lineTo(12f * u, 5.2f * u)
        lineTo(18.2f * u, 11.6f * u)
    }
    drawPath(head, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 实心下三角（= 原「▼」，顶栏地址下拉箭头；PC 端同款叫 DownTriangle） */
private fun DrawScope.drawTriangleDown(u: Float, c: Color) {
    val p = Path().apply {
        moveTo(4.4f * u, 8.8f * u)
        lineTo(19.6f * u, 8.8f * u)
        lineTo(12f * u, 17.2f * u)
        close()
    }
    drawPath(p, c)
}

/**
 * 齿轮（= 设置入口）。
 *
 * 齿形用极坐标逐点算：一圈 8 个齿，每个齿是「齿根宽、齿顶窄」的梯形，
 * 齿与齿之间沿**齿根圆**用 6 段折线近似圆弧（不依赖 `arcTo` 的隐式连线语义，结果稳定）。
 * 中心孔同样是 EvenOdd 挖出来的 —— 所以它画在任何底色上都对。
 */
private fun DrawScope.drawGear(u: Float, c: Color) {
    val c0 = Offset(12f * u, 12f * u)
    val rOuter = 10.4f * u   // 齿顶圆
    val rBody = 7.9f * u     // 齿根圆
    val rHole = 3.2f * u     // 中心孔
    val teeth = 8
    val step = 360f / teeth
    val halfBottom = step * 0.23f
    val halfTop = step * 0.13f

    val p = Path().apply {
        fillType = PathFillType.EvenOdd
        for (i in 0 until teeth) {
            val a = i * step - 90f          // 第一个齿朝正上方
            val p1 = polar(c0, rBody, a - halfBottom)
            val p2 = polar(c0, rOuter, a - halfTop)
            val p3 = polar(c0, rOuter, a + halfTop)
            val p4 = polar(c0, rBody, a + halfBottom)
            if (i == 0) moveTo(p1.x, p1.y) else lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            lineTo(p4.x, p4.y)
            // 沿齿根圆走到下一个齿的起点（正向角度递增，不会绕回）
            val aStart = a + halfBottom
            val aEnd = (i + 1) * step - 90f - halfBottom
            for (k in 1..6) {
                val q = polar(c0, rBody, aStart + (aEnd - aStart) * k / 6f)
                lineTo(q.x, q.y)
            }
        }
        close()
        addOval(Rect(center = c0, radius = rHole))
    }
    drawPath(p, c)
}

/** 暂停：两条竖杠（= 原「⏸」；字体覆盖不保证，统一矢量） */
private fun DrawScope.drawPause(u: Float, c: Color) {
    drawRoundRect(
        color = c,
        topLeft = Offset(6.2f * u, 5f * u),
        size = Size(3.6f * u, 14f * u),
        cornerRadius = CornerRadius(1.2f * u)
    )
    drawRoundRect(
        color = c,
        topLeft = Offset(14.2f * u, 5f * u),
        size = Size(3.6f * u, 14f * u),
        cornerRadius = CornerRadius(1.2f * u)
    )
}

/** 齿轮图标的推荐尺寸（左侧面板底部「设置」行用） */
internal val SETTINGS_GEAR_SIZE = 16.dp

/** 太阳（= 主题按钮「浅色」态）：**空心**圆环 + 8 根放射线（2026-10-01 用户定空心） */
private fun DrawScope.drawSun(u: Float, c: Color) {
    val center = Offset(12f * u, 12f * u)
    drawCircle(
        color = c,
        radius = 4.4f * u,
        center = center,
        style = Stroke(width = 1.8f * u)
    )
    for (i in 0 until 8) {
        val a = i * 45f
        drawLine(
            color = c,
            start = polar(center, 6.9f * u, a),
            end = polar(center, 10.4f * u, a),
            strokeWidth = 1.8f * u,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 月亮（= 主题按钮「深色」态）：**空心**月牙轮廓。
 *
 * 用两段圆弧围成：外圆（r9.5，圆心 12,12）走左下长弧到两个尖角，
 * 再沿「挖除圆」（r9，圆心 15,7）的弧折回 —— 两圆交点 (6.41,4.32) 与
 * (21.41,13.32) 是解出来的精确值，弧与弧在尖角处无缝闭合。
 * 描边（Round join）而非填充，与太阳同为空心风格。
 */
private fun DrawScope.drawMoon(u: Float, c: Color) {
    val outer = Rect(2.5f * u, 2.5f * u, 21.5f * u, 21.5f * u)
    val inner = Rect(6f * u, -2f * u, 24f * u, 16f * u)
    val p = Path().apply {
        moveTo(6.41f * u, 4.32f * u)
        arcTo(outer, 234f, -226f, forceMoveTo = false)
        arcTo(inner, 44.6f, 152.7f, forceMoveTo = false)
        close()
    }
    drawPath(
        p, c,
        style = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
