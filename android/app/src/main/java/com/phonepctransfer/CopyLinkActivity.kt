package com.phonepctransfer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/** "复制链接"入口：让本应用出现在系统"用浏览器打开"选择框，选中即把链接复制到剪贴板并立刻关闭（透明无界面）；复制走 AppCore 链路，链接也能同步到电脑 */
class CopyLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = extractLink(intent)
        if (link.isNullOrBlank()) {
            // 极少数应用给出的是空 data / 空 EXTRA_TEXT：如实说一句，不静默失败
            toast("没拿到链接")
            finish()
            return
        }
        if (!copyViaAppCore(link)) {
            // 兜底：AppCore 起不来（理论上不会）也要把链接复制出去，只是不做同步
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("PPTransfer", link))
            }
            toast("已复制链接：${short(link)}")
        }
        finish()
    }

    /** @return 是否走了 AppCore 那条带同步的路径 */
    private fun copyViaAppCore(link: String): Boolean = runCatching {
        AppCore.init(applicationContext)
        AppCore.copyTextToClipboard(link, hint = "已复制链接：${short(link)}")
    }.onFailure {
        Log.w(TAG, "走 AppCore 复制失败，退化为纯本地复制: ${it.message}")
    }.isSuccess

    /** 取链接：先取 Intent.getData，再退 EXTRA_TEXT（少数应用只把链接塞在附加文本里） */
    private fun extractLink(intent: Intent?): String? {
        val d = intent?.dataString
        if (!d.isNullOrBlank()) return d.trim()
        return intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 提示里的链接做长度上限：Toast 太长会被系统截断，看不全反而像出错 */
    private fun short(link: String): String =
        if (link.length <= LINK_HINT_MAX) link else link.take(LINK_HINT_MAX) + "…"

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "PP-CopyLink"

        /** 提示文案里最多显示的链接长度 */
        const val LINK_HINT_MAX = 120
    }
}
