package com.phonepctransfer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phonepctransfer.data.TextUtil

/** 分享进来的一个文件：可读 Uri + 展示名 + 字节数（未知为 -1） */
data class SharedItem(val uri: Uri, val name: String, val size: Long)

/**
 * 一次分享的内容。文件与纯文本二选一（两者都没有时 [ShareIn.parse] 返回 null）。
 *
 * 注意：分享给的读权限是**临时**的（跟着收到 Intent 的 Activity 走，不是持久授权），
 * 所以 [uri] 只在本次应用存活期内可读 —— 详见 AppCore.shareTo 里对「没连上就先落盘」的处理。
 */
data class SharedPayload(val items: List<SharedItem>, val text: String?) {

    val totalSize: Long get() = items.fold(0L) { acc, i -> acc + i.size.coerceAtLeast(0) }

    /** 弹窗里给用户看的一行摘要 */
    fun summary(): String {
        if (items.isEmpty()) {
            val n = text?.length ?: 0
            return "一段文本（$n 字）"
        }
        val size = if (totalSize > 0) " · ${TextUtil.humanSize(totalSize)}" else ""
        val names = items.take(3).joinToString("\n") { "· " + it.name }
        val more = if (items.size > 3) "\n…另外还有 ${items.size - 3} 个" else ""
        return "${items.size} 个文件$size\n$names$more"
    }
}

/**
 * 解析系统「分享」Intent（ACTION_SEND / ACTION_SEND_MULTIPLE，manifest 里注册的是全部 MIME 类型）。
 *
 * 取数优先级：EXTRA_STREAM（文件/图片/视频/音频…）> EXTRA_TEXT（纯文本分享）。
 * 两者都为空就返回 null（比如某些应用只发一个空 Intent），调用方直接忽略。
 */
object ShareIn {

    @Suppress("DEPRECATION") // getParcelableExtra(String) 在 33+ 被标记弃用但仍可用，且天然兼容低版本
    fun parse(ctx: Context, intent: Intent?): SharedPayload? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val uris = ArrayList<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()

        if (uris.isEmpty()) {
            return if (text.isNullOrEmpty()) null else SharedPayload(emptyList(), text)
        }
        val distinct = LinkedHashSet(uris).toList()
        val items = distinct.map { u ->
            SharedItem(uri = u, name = FileIO.queryName(ctx, u), size = FileIO.querySize(ctx, u))
        }
        // 有文件时忽略 EXTRA_TEXT：多数应用会顺手带上标题/说明，发过去只会多出一条莫名其妙的文本
        return SharedPayload(items, null)
    }
}
