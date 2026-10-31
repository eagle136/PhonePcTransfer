package com.phonepctransfer.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 后台保活无障碍服务。
 *
 * 仅用于让本进程在后台更难被系统冻结 / 杀掉，从而保证文件传输与语音通话
 * 在退到后台后仍能持续运行（ColorOS/Hans 等 ROM 对普通后台进程会在数秒内冻结
 * 并掐断 socket）。本服务**不订阅任何屏幕内容、不读取剪贴板、不做选区捕获**——
 * 所有 onAccessibilityEvent 一律忽略，无任何屏幕 / 剪贴板读取逻辑。
 */
class ClipCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        Log.i(TAG, "后台保活无障碍服务已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 保活用途：不读取任何屏幕内容 / 剪贴板，忽略全部事件
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "PP-A11y"

        /** 无障碍服务是否已启用（仅用于设置页提示，不读取任何内容） */
        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val name = ClipCaptureService::class.java.name
            return s.split(':').any { it.endsWith("/$name") }
        }
    }
}
