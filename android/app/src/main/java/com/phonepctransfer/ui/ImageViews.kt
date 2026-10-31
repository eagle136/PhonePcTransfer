package com.phonepctransfer.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** 图片扩展名（按文件名判定，唯一实现在 util/FileIO.isImageFileName，链路层共用） */
fun isImageName(name: String): Boolean =
    com.phonepctransfer.util.FileIO.isImageFileName(name)

// ==================== 解码 ====================

private fun openStream(ctx: Context, localUri: String): InputStream? = runCatching {
    when {
        localUri.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(localUri))
        localUri.startsWith("file://") -> FileInputStream(File(Uri.parse(localUri).path!!))
        localUri.isNotEmpty() -> FileInputStream(File(localUri))
        else -> null
    }
}.getOrNull()

/**
 * 按上限降采样解码（两遍：先读尺寸算 inSampleSize，再真正解码），避免大图 OOM。
 * @param maxPx 长边最大像素
 */
private fun decodeDownsampled(ctx: Context, localUri: String, maxPx: Int): ImageBitmap? {
    if (localUri.isEmpty()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(ctx, localUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = openStream(ctx, localUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        bmp?.asImageBitmap()
    }.getOrNull()
}

/**
 * 解码（带重试）。
 *
 * **为什么必须重试**（2026-09-19 修「收到图片要切窗口才显示」）：图片消息在 `file_meta`
 * 阶段就写进了 `localUri`，那一刻文件才刚 insert、**内容还是 0 字节**；如果此时就解码，
 * 必然失败。而 `source`（= localUri）在收完之后**不会变**，`LaunchedEffect` 便再也不会
 * 重跑 —— 界面就永远停在「载入中…」，直到 Composable 被销毁重建（这就是「切走再切回
 * 才显示」的由来）。所以这里改为：失败按 0/250/500/1000ms 重试 4 次；
 * 同时 `readyKey` 一旦变化（消息从「接收中」变成「已完成」）立即重新解码。
 *
 * @param readyKey 完成信号（调用方传消息的进度/状态）；变了就重新解码
 */
@Composable
private fun rememberDecoded(source: String, maxPx: Int, readyKey: Any? = null): ImageBitmap? {
    val ctx = LocalContext.current
    var bmp by remember(source, maxPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, maxPx, readyKey) {
        if (source.isEmpty()) {
            bmp = null
            return@LaunchedEffect
        }
        // 已解出过的图先留着（重试期间不闪白），新结果到了再替换
        var got: ImageBitmap? = null
        val retryDelays = longArrayOf(0L, 250L, 500L, 1000L)
        for ((i, d) in retryDelays.withIndex()) {
            if (d > 0L) delay(d)
            got = withContext(Dispatchers.IO) { decodeDownsampled(ctx, source, maxPx) }
            if (got != null) break
        }
        bmp = got
    }
    return bmp
}

/**
 * 解析出一份「可加载」的图片来源：
 *  1. 消息自带 `localUri`（本次改造后新收到的图片都有）→ 直接用；
 *  2. 旧历史记录没有 `localUri` → 按文件名去 MediaStore「下载/手机电脑互传」里回查。
 * 都拿不到返回 null（界面上显示占位文案）。
 */
@Composable
fun rememberImageSource(localUri: String, fileName: String): String? {
    val ctx = LocalContext.current
    val want = isImageName(fileName)
    var src by remember(localUri, fileName) {
        mutableStateOf(if (want && localUri.isNotEmpty()) localUri else null)
    }
    LaunchedEffect(localUri, fileName) {
        if (!want) {
            src = null
            return@LaunchedEffect
        }
        if (localUri.isNotEmpty()) {
            src = localUri
            return@LaunchedEffect
        }
        src = withContext(Dispatchers.IO) { lookupInMediaStore(ctx, fileName) }
    }
    return src
}

/** 旧记录兜底：按文件名在 MediaStore 下载目录里回查可读的 content:// 地址 */
private fun lookupInMediaStore(ctx: Context, name: String): String? {
    if (name.isEmpty()) return null
    return runCatching {
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
        val wh = sel + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf(name, "%手机电脑互传%")
        var out: String? = null
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, wh, args,
            MediaStore.MediaColumns._ID + " DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                out = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                ).toString()
            }
        }
        out
    }.getOrNull()
}

// ==================== 聊天页里的小图 ====================

/**
 * 聊天气泡里的图片缩略图：按原始宽高比缩放、长边不超过 [maxSize]。
 * 不消费任何点击——短按/长按手势统一由外层气泡的 combinedClickable 处理。
 * @param source 已解析好的可加载地址（见 rememberImageSource）
 * @param readyKey 接收完成信号（进度/状态）：变化即重新解码，保证**收完立刻显示**
 */
@Composable
fun ImageThumb(
    source: String?,
    modifier: Modifier = Modifier,
    maxSize: Dp = 200.dp,
    readyKey: Any? = null
) {
    val bmp = rememberDecoded(source.orEmpty(), maxPx = 512, readyKey = readyKey)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22000000)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp == null) {
            // 「🖼」→ 矢量 [Glyph.IMAGE]（2026-09-19 去 emoji）；图标与文字同行居中
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                GlyphIcon(
                    Glyph.IMAGE,
                    size = 13.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (source == null) "图片（未找到文件）" else "载入中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        } else {
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            val w = if (ratio >= 1f) maxSize else maxSize * ratio
            val h = if (ratio >= 1f) maxSize / ratio else maxSize
            Image(
                bitmap = bmp,
                contentDescription = "图片",
                modifier = Modifier.size(w, h),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ==================== 全屏查看器 ====================

/**
 * 全屏图片查看：
 *  - 打开即「适应屏幕」（完整显示）
 *  - **双击**在「适应屏幕」与「原图 1:1」之间切换（1:1 = 1 图像像素占 1 屏幕像素）
 *  - 单指拖动平移，双指捏合缩放
 *  - 右上角悬浮关闭按钮
 */
@Composable
fun ImageViewerDialog(localUri: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ImageViewer(localUri, onClose)
    }
}

@Composable
private fun ImageViewer(localUri: String, onClose: () -> Unit) {
    // 全屏查看尽量按原图分辨率解码；长边上限 6000px 兜底防 OOM
    val bmp = rememberDecoded(localUri, maxPx = 6000)

    var box by remember { mutableStateOf(IntSize.Zero) }
    // scale 以「适应屏幕」为 1.0 基准；1:1 原图 = 1 / fitRatio
    var scale by remember(localUri) { mutableStateOf(1f) }
    var offset by remember(localUri) { mutableStateOf(Offset.Zero) }
    var oneToOne by remember(localUri) { mutableStateOf(false) }

    /** 适应屏幕的基础比例（图像像素 → 屏幕像素） */
    fun fitRatio(): Float {
        val b = bmp ?: return 1f
        if (box.width <= 0 || box.height <= 0) return 1f
        return min(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
    }

    /** 平移越界收敛：图片小于容器时回中，大于容器时夹到边缘 */
    fun clampOffset() {
        val b = bmp ?: return
        val w = b.width * fitRatio() * scale
        val h = b.height * fitRatio() * scale
        val maxX = ((w - box.width) / 2f).coerceAtLeast(0f)
        val maxY = ((h - box.height) / 2f).coerceAtLeast(0f)
        offset = Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { box = it }
    ) {
        // ---- 图片层：铺满整屏承载手势（点击/拖动/双击）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(localUri) {
                    detectTapGestures(onDoubleTap = {
                        oneToOne = !oneToOne
                        scale = if (oneToOne) 1f / fitRatio() else 1f
                        offset = Offset.Zero
                    })
                }
                .pointerInput(localUri) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = scale
                        val new = (old * zoom).coerceIn(0.5f, 12f)
                        // 以捏合中心为锚点缩放：让中心点下的图像内容保持不动
                        val k = new / old
                        val cx = box.width / 2f
                        val cy = box.height / 2f
                        offset = Offset(
                            centroid.x - cx - (centroid.x - cx - offset.x) * k,
                            centroid.y - cy - (centroid.y - cy - offset.y) * k
                        )
                        scale = new
                        offset += pan
                        clampOffset()
                    }
                }
        ) {
            if (bmp == null) {
                Text(
                    "载入中…",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // 布局固定铺满屏 + ContentScale.Fit 锁纵横比，缩放/平移走
                // graphicsLayer 绘制期变换 —— 不参与测量，不受父容器 max
                // 约束钳制，双指缩放不变形（旧 size+FillBounds 会变形）
                Image(
                    bitmap = bmp,
                    contentDescription = "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }
        }

        // ---- 模式提示（左下角）----
        Text(
            text = if (oneToOne) "1:1 原图 · 双击返回适应屏幕" else "适应屏幕 · 双击 1:1 原图 · 拖动可平移",
            color = Color(0xCCFFFFFF),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, bottom = 14.dp)
        )

        // ---- 右上角悬浮关闭按钮（在图片层之上，优先响应点击）----
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable(onClick = onClose)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // 「✕」字符 → 矢量 [Glyph.CLOSE]（2026-09-19）。20.sp 的字形换成 18dp 图标：
            // 叉线的端点更干净（字符 ✕ 在不同字重下粗细差别很大），且不依赖字体覆盖。
            GlyphIcon(Glyph.CLOSE, size = 18.dp, color = Color.White)
        }
    }
}
