package com.phonepctransfer.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.phonepctransfer.AppCore

/**
 * 断链兜底拨号：**Doze/深度息屏下也能醒一次**（不持任何 wake lock）。
 *
 * ## 为什么需要
 * [Link] 的保活阶梯（5s/10s/30s）跑在普通 ScheduledExecutorService 上：熄屏进 Doze 后
 * CPU 挂起，定时器根本不跑。实测（2026-11-25 18:34→18:44）手机一次息屏：
 * - PC 端 `读循环异常: Connection reset` → `会话结束 192.168.5.13`；
 * - 手机端阶梯一次都没执行，10 分钟后（系统维护窗口/亮屏）才自己拨回来；
 * - 中间 PC 主动连 `192.168.5.13:28765` 全部 `Connect timed out`（手机射频睡着，SYN 收不到）。
 * 双端都在等对方 ⇒ 界面一直「连接失败」。
 *
 * 这里用 WAKEUP 型闹钟在断链后按 20s / 90s / 5min 各唤醒一次、拨一次：
 * - 只在「没有会话」时存在，握手成功/链路停止立刻取消；
 * - **不持 wake lock**：闹钟自带的唤醒窗口只够跑一次非阻塞 connect，比常驻 PARTIAL_WAKE_LOCK 省电得多；
 * - 三次都没通就停手，转纯被动等对端连入（与 keepAliveResolve 的「不无限敲」口径一致）。
 *
 * 真正能「只要不深度休眠就正常」的前提是**电池优化白名单**（见 util/BootPerms 的 `battery` 项）：
 * 白名单应用在 Doze 里仍可用网络，心跳/剪贴板同步才不会被整体掐掉；本闹钟只是没进白名单时的兜底。
 */
internal object KeepAliveAlarm {

    private const val TAG = "PP-KeepAlive"
    const val ACTION = "com.phonepctransfer.action.KEEPALIVE_DIAL"
    const val EXTRA_IP = "ip"
    const val EXTRA_ATTEMPT = "attempt"

    /** 三轮兜底：20s（普通息屏立刻拉起）/ 90s / 5min；之后不再敲 */
    private val DELAYS = longArrayOf(20_000L, 90_000L, 300_000L)

    /** 当前挂着闹钟的设备（stop() 时统一撤） */
    private val armed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun schedule(ctx: Context, ip: String, attempt: Int = 0) {
        val v = ip.trim()
        if (v.isEmpty() || attempt >= DELAYS.size) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx, v, attempt)
        armed.add(v)
        runCatching {
            val at = SystemClock.elapsedRealtime() + DELAYS[attempt]
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 用 allow-while-idle（不需要 SCHEDULE_EXACT_ALARM 权限）：Doze 下会被系统节流到
                // 十几分钟一次，但「普通息屏/轻休眠」下按点触发，正是我们需要的兜底
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }.onFailure {
            Log.w(TAG, "设置保活闹钟失败: ${it.message}")
        }
    }

    fun cancel(ctx: Context, ip: String) {
        val v = ip.trim()
        if (v.isEmpty()) return
        armed.remove(v)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // extras 不参与 PendingIntent 的相等判定，同一个 requestCode 就能撤掉这一台的全部档位
        runCatching { am.cancel(pendingIntent(ctx, v, 0)) }
    }

    fun cancelAll(ctx: Context) {
        for (ip in armed.toList()) cancel(ctx, ip)
    }

    /** 闹钟到点：还没连上就拨一次，并按下一档继续挂（连上/停止即收手） */
    internal fun onAlarm(ctx: Context, ip: String, attempt: Int) {
        val link = runCatching {
            if (AppCore.initialized) AppCore.link else null
        }.getOrNull()
        if (link == null || !link.running) {
            armed.remove(ip)
            return
        }
        if (link.isDeviceConnected(ip)) {
            armed.remove(ip)
            return
        }
        val next = attempt + 1
        link.log("保活闹钟唤醒：拨号 $ip（第 $next/${DELAYS.size} 次，Doze 兜底）")
        // fromUser=false：仍走后台闸门，避免这套兜底变成「后台乱连陌生设备」的后门
        link.connectTo(ip, quiet = true, tryGroup = true)
        if (next < DELAYS.size) {
            schedule(ctx, ip, next)
        } else {
            armed.remove(ip)
            link.log("保活闹钟三轮均未连通 → 转纯被动等待（亮屏/点设备可重新连接）")
        }
    }

    private fun pendingIntent(ctx: Context, ip: String, attempt: Int): PendingIntent {
        val i = Intent(ctx, KeepAliveAlarmReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_IP, ip)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, ip.hashCode() and 0x7fffffff, i, flags)
    }
}

/** 闹钟落点：只在进程还活着（前台服务在跑）时拨一次；进程没了就什么都不做，交给系统 START_STICKY 拉服务回来自动连。 */
class KeepAliveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveAlarm.ACTION) return
        val ip = intent.getStringExtra(KeepAliveAlarm.EXTRA_IP) ?: return
        val attempt = intent.getIntExtra(KeepAliveAlarm.EXTRA_ATTEMPT, 0)
        KeepAliveAlarm.onAlarm(context.applicationContext, ip, attempt)
    }
}
