package com.phonepctransfer.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.phonepctransfer.AppCore
import com.phonepctransfer.util.BusyLock

/**
 * 后台心跳保活：**熄屏期间也要把心跳包按周期发出去**。
 *
 * ## 为什么需要（2026-11-25 实测）
 * 手机一熄屏，ColorOS/系统立刻把 WiFi 降到省电档（甚至直接休眠射频）：
 * - 5 秒内 TCP 就被对端/网关拆掉 → PC 端 `读循环异常: Connection reset` → 会话结束；
 * - [Link.heartbeatLoop] 跑在普通线程上，CPU 挂起后 5s tick 不再执行，连"发现断了再拨号"都做不到；
 * - [BusyLock] 之前只在**传输/通话**期间持锁，空闲待机不持 ⇒ 熄屏即掉线是必然。
 *
 * ## 做法
 * - **熄屏 + 至少一条已握手会话**：持 [BusyLock]（PARTIAL_WAKE_LOCK + 高性能 WiFi 锁），
 *   心跳线程照常 5s 一次 ping，WiFi radio 不掉档；
 * - 另挂一个 `setAndAllowWhileIdle` 闹钟（60s，自续期）做 **Doze 兜底**：
 *   深度休眠里系统只放行维护窗口，闹钟到点就补发一次心跳，尽量不让 NAT/路由器把这条连接忘掉；
 * - **亮屏 / 会话全部断开**：立刻释放锁、撤闹钟 —— 空闲待机不耗电，口径与 [BusyLock] 一致；
 * - 每 5s 由心跳线程调一次 [onTick] 自愈：进程可能错过广播（被冻结时广播会延迟），
 *   以 `PowerManager.isInteractive` 和会话集合的**当前值**为准，避免锁泄漏。
 */
internal object IdleHeartbeat {

    private const val TAG = "PP-IdleHB"
    private const val BUSY_TAG = "idle-hb"
    private const val INTERVAL_MS = 60_000L
    internal const val ACTION = "com.phonepctransfer.action.IDLE_HB"

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var screenOff = false
    private var armed = false

    /** 前台服务启动时调用：注册亮/灭屏广播并按当前状态对齐一次 */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            screenOff = true
                            if (hasSession()) {
                                Log.i(TAG, "熄屏：开始后台心跳保活（CPU/WiFi 锁 + 5s ping）")
                            }
                        }
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            screenOff = false
                            Log.i(TAG, "亮屏：释放后台心跳保活")
                        }
                    }
                    sync()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    app.registerReceiver(r, filter)
                }
                receiver = r
            }.onFailure { Log.w(TAG, "注册亮灭屏广播失败: ${it.message}") }
        }
        screenOff = !isInteractive(app)
        sync()
    }

    fun stop() {
        val app = appCtx
        receiver?.let { r ->
            runCatching { app?.unregisterReceiver(r) }
        }
        receiver = null
        appCtx = null
        screenOff = false
        BusyLock.end(BUSY_TAG)
        disarm()
    }

    /**
     * 每 5s 心跳 tick 调用（{@link Link.heartbeatLoop}）：
     * 以当前真实状态对齐锁与闹钟，防止「广播丢了 / 会话在熄屏期间断开」导致锁泄漏或该持有的没持。
     */
    fun onTick() {
        val app = appCtx ?: return
        val nowOff = !isInteractive(app)
        if (nowOff != screenOff) {
            screenOff = nowOff
        }
        sync()
    }

    /** 闹钟落点：补发一次心跳并续期（Doze 里到点才跑一次） */
    fun onAlarm() {
        // setAndAllowWhileIdle 是**一次性**闹钟：到点即作废，先把标志清掉，sync() 才会重新挂下一轮
        armed = false
        val app = appCtx ?: return
        if (!screenOff || !hasSession()) {
            sync()
            return
        }
        val link = runCatching {
            if (AppCore.initialized) AppCore.link else null
        }.getOrNull() ?: return
        link.log("后台心跳：Doze 兜底唤醒，补发一次 ping")
        link.pokeHeartbeat()
        sync()
    }

    private fun sync() {
        val app = appCtx ?: return
        if (screenOff && hasSession()) {
            BusyLock.begin(BUSY_TAG)
            arm(app)
        } else {
            BusyLock.end(BUSY_TAG)
            disarm()
        }
    }

    private fun hasSession(): Boolean = runCatching {
        AppCore.initialized && AppCore.link.connectedIps.isNotEmpty()
    }.getOrDefault(false)

    private fun isInteractive(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm == null || pm.isInteractive
    }.getOrDefault(true)

    private fun arm(ctx: Context) {
        if (armed) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            // 不需要 SCHEDULE_EXACT_ALARM：普通息屏按点触发；深度 Doze 会被节流到维护窗口，正好用来续命
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent(ctx)
            )
            armed = true
        }.onFailure { Log.w(TAG, "设置后台心跳闹钟失败: ${it.message}") }
    }

    private fun disarm() {
        if (!armed) return
        val app = appCtx
        if (app != null) {
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            runCatching { am?.cancel(pendingIntent(app)) }
        }
        armed = false
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, IdleHeartbeatReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0x3301, i, flags)
    }
}

/** 闹钟落点（不导出，只由本应用自己 set 的闹钟触发） */
class IdleHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IdleHeartbeat.ACTION) return
        IdleHeartbeat.onAlarm()
    }
}
