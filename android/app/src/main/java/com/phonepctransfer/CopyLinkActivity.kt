package com.phonepctransfer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * 「复制链接」入口（2026-10-05，用户需求）：**让本应用出现在系统「用浏览器打开」的选择框里**，
 * 选中它 = 把链接复制到剪贴板 → 提示一句 → 立刻关闭（用户看不到任何界面）。
 *
 * 名字来源：选择框里显示的是**这个 Activity 的 `android:label`**（manifest 里写死「复制链接」），
 * 与「浏览器」注册方式一致 —— 只是我们并不渲染网页。
 *
 * 为什么单独一个 Activity、而不是给主界面加 VIEW 过滤器：
 *  - 主界面 [MainActivity] 是 `singleTask` 的聊天窗口，点链接会把**整个聊天界面**带到前台，
 *    与「复制完就关」冲突；
 *  - 这个 Activity 用透明主题（`Theme.PhonePcTransfer.CopyLink`）+ 空布局，`onCreate` 干完活
 *    立即 `finish()`；manifest 里还配了 `taskAffinity=""`（不归入主界面任务，避免把聊天界面
 *    一起顶到前台）、`excludeFromRecents`、`noHistory`。
 *
 * 复制走 [AppCore.copyTextToClipboard]（= 「长按消息 → 复制文本」同一条路径）：写本机剪贴板 +
 * 剪贴板同步开着且电脑已连接时顺手推给电脑 + 提示 + 日志。`AppCore.init` 是幂等的 ——
 * 应用本来就在跑时这里几乎零成本；没在跑则顺带把应用链路叫起来（这是「链接也能同步到电脑」
 * 的代价，用户已确认要这个行为）。
 */
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

    /**
     * 取链接：先 [Intent.getData]（正常的「用浏览器打开」），再退 `EXTRA_TEXT`
     * （少数应用/自定义弹窗只把它塞在附加文本里，data 为空）。
     */
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
