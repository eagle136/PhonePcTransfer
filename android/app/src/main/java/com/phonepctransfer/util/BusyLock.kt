package com.phonepctransfer.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * 传输期锁：**只在实际收发数据/通话时**持 [PowerManager.PARTIAL_WAKE_LOCK] 与
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF]，空闲立刻释放。
 *
 * 为什么需要：前台服务只保证进程不被杀，保证不了 CPU 不休眠、WiFi 不进省电模式。
 * 息屏后 WiFi 会被系统降级到省电档，TCP 发送窗口挂起 → 传输「传着传着就断」。
 *
 * 为什么只在传输期持：常驻持有会让待机耗电明显变差，而空闲时链路靠 15s ping 就够。
 *
 * 用法：按 tag 引用计数（同一个 tag 重复 begin 只算一次），全部 tag 退出才释放。
 */
internal object BusyLock {

    private const val TAG = "PP-BusyLock"

    private var appCtx: Context? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val tags = LinkedHashSet<String>()

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /** 开始一段忙期（tag 相同可重复调用） */
    fun begin(tag: String) {
        synchronized(this) {
            val first = tags.isEmpty()
            tags.add(tag)
            if (first && (wake == null || wake?.isHeld != true)) acquire()
        }
    }

    /** 结束一段忙期 */
    fun end(tag: String) {
        synchronized(this) {
            tags.remove(tag)
            if (tags.isEmpty()) release()
        }
    }

    private fun acquire() {
        val ctx = appCtx ?: return
        runCatching {
            if (wake == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pp:transfer")?.apply {
                    setReferenceCounted(false)
                }
            }
            wake?.let { if (!it.isHeld) it.acquire() }
        }.onFailure { Log.w(TAG, "获取 CPU 锁失败: ${it.message}") }
        runCatching {
            if (wifi == null) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                // 已废弃但 API 29+ 仍是最省事的高性能 WiFi 锁；失败不影响主流程
                @Suppress("DEPRECATION")
                wifi = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pp:transfer")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifi?.let { if (!it.isHeld) it.acquire() }
        }.onFailure { Log.w(TAG, "获取 WiFi 锁失败: ${it.message}") }
    }

    private fun release() {
        runCatching { wake?.let { if (it.isHeld) it.release() } }
            .onFailure { Log.w(TAG, "释放 CPU 锁失败: ${it.message}") }
        runCatching { wifi?.let { if (it.isHeld) it.release() } }
            .onFailure { Log.w(TAG, "释放 WiFi 锁失败: ${it.message}") }
    }
}
