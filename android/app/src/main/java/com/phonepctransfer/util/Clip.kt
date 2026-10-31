package com.phonepctransfer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.MessageDigest

/**
 * 剪贴板读写。
 *
 * Android 自 API 29 起没有公开给普通应用的 READ_CLIPBOARD 权限，读剪贴板的真实前提是
 * "应用处于前台或持有焦点"；后台/服务里读会返回 null。这里统一做兜底：读失败返回 null，
 * 不抛异常、不影响主流程。
 */
class Clip(private val ctx: Context) {

    private val cm: ClipboardManager? by lazy {
        runCatching { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }
            .getOrNull()
    }

    /** 最近一次已推送给 PC 的内容哈希，用于去重 */
    @Volatile
    var lastPushedSha1: String = ""

    /** 最近一次从 PC 拉回并写入本机剪贴板的内容哈希 */
    @Volatile
    var lastPulledSha1: String = ""

    /** 读剪贴板文本；无权限/无焦点/无内容时返回 null */
    fun read(): String? {
        val mgr = cm ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !mgr.hasPrimaryClip()) return null
            val clip = mgr.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val cs = clip.getItemAt(0).coerceToText(ctx)?.toString()
            if (cs.isNullOrEmpty()) null else cs
        } catch (t: Throwable) {
            // 后台无焦点时部分 ROM 会直接抛 SecurityException
            Log.w(TAG, "读剪贴板失败: ${t.message}")
            null
        }
    }

    /**
     * 写剪贴板。必须在主线程调用（Android 12+ 对后台写剪贴板有限制）。
     */
    fun write(text: String) {
        val mgr = cm ?: return
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mgr.setPrimaryClip(ClipData.newPlainText("PPTransfer", text))
            } else {
                Handler(Looper.getMainLooper()).post {
                    runCatching { mgr.setPrimaryClip(ClipData.newPlainText("PPTransfer", text)) }
                }
            }
        }.onFailure { Log.w(TAG, "写剪贴板失败: ${it.message}") }
        lastPulledSha1 = sha1(text)
    }

    // ==================== 剪贴板变化监听 ====================

    private var watcher: ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * 注册系统剪贴板变化回调（必须在主线程）。
     *
     * 为什么不能只靠定时轮询：读剪贴板要求应用"持有焦点"，而焦点在 onPause/onUserLeaveHint
     * 这些回调里其实已经被系统收走了（Android 10+ 对失去焦点的应用让 hasPrimaryClip() 恒返回
     * false，静默失败）。轮询周期内发生的"复制完立刻离开"就会漏掉。
     * 变化回调是在复制的瞬间由系统直接投递到本进程的，那一刻应用必然还持有焦点，
     * 因此读得到——这是唯一不依赖时序运气的主路径（2026-09-12 a12 实测）。
     */
    fun startWatch(onChange: () -> Unit) {
        val mgr = cm ?: return
        stopWatch()
        val l = ClipboardManager.OnPrimaryClipChangedListener { onChange() }
        watcher = l
        runCatching { mgr.addPrimaryClipChangedListener(l) }
            .onFailure { Log.w(TAG, "注册剪贴板监听失败: ${it.message}") }
    }

    fun stopWatch() {
        val mgr = cm ?: return
        watcher?.let { runCatching { mgr.removePrimaryClipChangedListener(it) } }
        watcher = null
    }

    companion object {
        private const val TAG = "PP-Clip"

        fun sha1(s: String): String = try {
            val md = MessageDigest.getInstance("SHA-1")
            val d = md.digest(s.toByteArray(Charsets.UTF_8))
            d.joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            "len-${s.length}-${s.hashCode()}"
        }
    }
}
