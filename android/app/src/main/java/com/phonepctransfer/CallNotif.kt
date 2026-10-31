package com.phonepctransfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.phonepctransfer.call.acceptCall
import com.phonepctransfer.call.rejectCall

/**
 * 呼入通知（2026-09-22）：**锁屏上也能接听/挂断**的语音/视频来电。
 *
 * 三条路一起给，尽量保证任何 ROM 上都有可用的接听入口：
 *  1. **全屏意图**（`setFullScreenIntent`）→ 锁屏/息屏时由系统直接把 MainActivity 顶到
 *     锁屏**之上**（靠 `MainActivity.applyCallIntent` 里的 `setShowWhenLocked`），
 *     来电界面就是应用里那个电话风格的「拒绝 / 接听」大圆钮，接听后通话界面继续盖在锁屏上。
 *     ⚠️ Android 10+ 需 `USE_FULL_SCREEN_INTENT` 权限；Android 14+ 非「通话/闹钟」类应用的
 *     该权限会被系统默认收回，需用户手动允许（启动时按项询问；不给也照样能用第 2、3 条路，
 *     只在 `MainActivity.remindMissingPerms()` 里 toast 提醒「来电不会全屏弹出」）。
 *  2. **通知按钮「接听 / 挂断」** → 走广播（[CallActionReceiver]），**锁屏上点一下即可生效、
 *     不需要先解锁**（点 Activity 型 PendingIntent 才会弹解锁框）。全屏意图被 ROM 拦掉时这条路仍可用。
 *  3. **点通知本体** → 打开应用（锁屏上按 `showWhenLocked` 直接盖在锁屏之上）。
 *
 * 通道：独立通道 + `IMPORTANCE_HIGH`（全屏意图的硬前提）＋ `VISIBILITY_PUBLIC`
 * （锁屏「隐藏敏感通知内容」时也照原样显示来电人），通道**不出声**——
 * 铃声由 [com.phonepctransfer.voice.CallManager.startRinger] 自己播，避免双响。
 *
 * 与前台服务通知（[TransferService]）完全分开：ID 不同、通道不同，互不覆盖。
 */
object CallNotif {

    internal const val TAG = "PP-CallNotif"

    /** 独立通道（与前台服务的 pp_transfer 分开，便于用户单独静音来电） */
    const val CHANNEL_ID = "pp_call"

    /** 呼入通知 ID（前台服务是 0x5050，错开） */
    const val NOTIF_ID = 0x5051

    /** 「把来电界面顶到锁屏之上」的启动标记（MainActivity 读） */
    const val EXTRA_CALL_UI = "pp_call_ui"

    /** 通知按钮的动作（[CallActionReceiver] 与 MainActivity 共用）：accept / reject */
    const val EXTRA_CALL_ACTION = "pp_call_action"
    const val ACTION_ACCEPT = "accept"
    const val ACTION_REJECT = "reject"

    /** 通知通道：HIGH + 锁屏公开 + 不出声（铃声由应用自己播） */
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.call_notif_channel_name),
            // HIGH 是「全屏意图会真的拉起界面」的硬前提（LOW/DEFAULT 只会静默挂一条）
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.call_notif_channel_desc)
            setShowBadge(true)
            // 铃声由 CallManager.startRinger 播（系统铃声 + USAGE_NOTIFICATION_RINGTONE），
            // 通道再出声会双响；震动也交给铃声那条链路，这里保持安静。
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** 是否有通知权限（Android 13+ 才需要运行时授权，没授权就不发了，发也是白费） */
    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 收到呼入 → 展示来电通知（含全屏意图 + 接听/挂断按钮）。
     * 重复呼入（同一设备重拨）会直接覆盖同一条通知。
     */
    fun show(ctx: Context, peerIp: String, name: String, video: Boolean) {
        if (!canPost(ctx)) {
            Log.w(TAG, "无通知权限：来电通知未展示（全屏意图随之失效）")
            return
        }
        runCatching {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, build(ctx, peerIp, name, video))
            wakeScreen(ctx)
            tryStartIncomingUi(ctx)
            Log.i(TAG, "来电通知已展示（${if (video) "视频" else "语音"} $peerIp name=$name）")
        }.onFailure { Log.w(TAG, "展示来电通知失败: ${it.message}") }
    }

    /** 呼入结束（接听/拒绝/超时/对端取消）→ 撤掉来电通知 + 熄掉点亮用的唤醒锁 */
    fun cancel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
        releaseWake()
    }

    // ==================== 点亮屏幕（2026-09-22 实测补的兜底） ====================
    // 实测 a12/MIUI12：全屏意图被「后台弹出界面」拦掉 → Activity 起不来 → setTurnScreenOn
    // 根本没机会执行，来电时屏幕全程黑着（只有铃声）。所以不再依赖 ROM 放行：
    // 直接持一把带 ACQUIRE_CAUSES_WAKEUP 的 FULL_WAKE_LOCK 把屏幕点亮到锁屏，
    // 锁屏上就是带「接听 / 挂断」按钮的来电通知（广播型按钮无需解锁即可点）。

    private var wakeLock: PowerManager.WakeLock? = null

    /** 亮屏时长：比 90s 呼入超时短很多，平衡可见性与耗电；超时回灭后通知仍在，点电源键可见 */
    private const val WAKE_MS = 30_000L

    /**
     * 主动把来电界面拉到前台（含锁屏之上，配合 MainActivity 的 showWhenLocked/turnScreenOn）。
     *
     * 2026-09-22 实测：a12/MIUI12 与 a16/ColorOS16 的全屏意图都会被 ROM 自家的
     * 「锁屏显示 / 后台弹出界面」闸门**静默拦掉**（a16 连 canUseFullScreenIntent()=true 都拦），
     * 界面从来没起来过。而 **持有悬浮窗权限（SYSTEM_ALERT_WINDOW）是 AOSP 层硬编码的
     * 后台起界面豁免**，跨 ROM 可靠 —— 所以权限在手就直接自己 startActivity。
     *
     * 没权限时也照试一次：应用「最近用过」的短窗口期内系统同样放行，能弹就是赚。
     * 失败被 ROM 静默吞掉，不影响通知/铃声路径。
     */
    private fun tryStartIncomingUi(ctx: Context) {
        runCatching { ctx.startActivity(activityIntent(ctx, null)) }
            .onFailure { Log.w(TAG, "后台拉起来电界面失败: ${it.message}") }
    }

    private fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            releaseWake()
            @Suppress("DEPRECATION") // FULL_WAKE_LOCK 虽废弃但仍是「点亮到锁屏」唯一不依赖 Activity 的途径
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "pp:incoming_call"
            )
            wl.acquire(WAKE_MS)
            wakeLock = wl
        }.onFailure { Log.w(TAG, "点亮屏幕失败: ${it.message}") }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * 打开通话界面的 Intent（MainActivity 是 singleTask，已在后台则走 onNewIntent）。
     * 形态与「点桌面图标」一致 + 带 [EXTRA_CALL_UI]，让界面允许盖在锁屏之上。
     */
    fun activityIntent(ctx: Context, action: String?): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            this.action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_CALL_UI, true)
            if (!action.isNullOrEmpty()) putExtra(EXTRA_CALL_ACTION, action)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }

    private fun actionIntent(ctx: Context, action: String): Intent =
        Intent(ctx, CallActionReceiver::class.java).setAction("com.phonepctransfer.CALL_$action")

    private fun build(ctx: Context, peerIp: String, name: String, video: Boolean): Notification {
        // 三个 PendingIntent 用不同 requestCode，避免 FLAG_UPDATE_CURRENT 互相顶掉
        val open = PendingIntent.getActivity(
            ctx, 1, activityIntent(ctx, null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val accept = PendingIntent.getBroadcast(
            ctx, 2, actionIntent(ctx, ACTION_ACCEPT).putExtra(EXTRA_CALL_ACTION, ACTION_ACCEPT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reject = PendingIntent.getBroadcast(
            ctx, 3, actionIntent(ctx, ACTION_REJECT).putExtra(EXTRA_CALL_ACTION, ACTION_REJECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        val iconCall = Icon.createWithResource(ctx, R.drawable.ic_call)
        val iconEnd = Icon.createWithResource(ctx, R.drawable.ic_call_end)
        return b.setContentTitle(
                ctx.getString(
                    if (video) R.string.call_notif_video else R.string.call_notif_voice
                )
            )
            .setContentText(name.ifBlank { peerIp })
            .setSubText(peerIp)
            .setSmallIcon(R.drawable.ic_call)
            .setCategory(Notification.CATEGORY_CALL)
            // 锁屏「隐藏敏感内容」也要照原样显示来电人 —— 否则锁屏上只剩「内容已隐藏」，没法判断是谁打来
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            // true = 锁屏/息屏时直接以全屏界面出现（非锁屏状态则退化成横幅）
            .setFullScreenIntent(open, true)
            // 锁屏上点一下即生效、无需解锁（广播型 PendingIntent 不走解锁流程）
            .addAction(
                Notification.Action.Builder(
                    iconEnd, ctx.getString(R.string.call_notif_reject), reject
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    iconCall, ctx.getString(R.string.call_notif_answer), accept
                ).build()
            )
            .build()
    }
}

/**
 * 来电通知按钮的接收器（锁屏上点「接听 / 挂断」走这里，**不需要先解锁**）。
 *
 * 为什么用广播而不是直接把按钮做成「打开界面」的 Activity PendingIntent：
 * 锁屏上点 Activity 型 PendingIntent 会先弹解锁框（多一步，来电就来不及了）；
 * 广播型则立即送达本应用。进程还活着（有前台服务常驻）时直接完成接听/拒绝。
 *
 * 唯一需要界面介入的情况是**视频来电 + 相机权限未授予** —— 动态权限只能在 Activity 里申请，
 * 这时回退成「拉起界面」（用户刚点过通知，系统会给短时的后台起界面白名单），
 * 由 MainActivity 走完整的授权 → 接听流程。
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(CallNotif.EXTRA_CALL_ACTION) ?: return
        val ctx = context.applicationContext
        Log.i(CallNotif.TAG, "来电通知操作：$action")
        if (!AppCore.initialized) {
            // 进程已被系统回收（极少见）：无从「接听」——拉起界面让用户重新点
            Log.w(CallNotif.TAG, "进程未初始化：改拉起界面")
            runCatching { ctx.startActivity(CallNotif.activityIntent(ctx, action)) }
            CallNotif.cancel(ctx)
            return
        }
        when (action) {
            CallNotif.ACTION_ACCEPT -> {
                val video = AppCore.incomingCall?.third == true
                val needCam = video &&
                    ctx.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                if (needCam) {
                    // 相机权限只能走 Activity 的动态申请 → 交给界面（用户刚点了通知，允许起界面）
                    runCatching { ctx.startActivity(CallNotif.activityIntent(ctx, action)) }
                        .onFailure { AppCore.toast("请先授予相机权限再接听") }
                } else {
                    AppCore.acceptCall()
                    // 尽量把通话界面顶到锁屏之上（接听后还要能挂断）
                    runCatching { ctx.startActivity(CallNotif.activityIntent(ctx, null)) }
                }
            }
            CallNotif.ACTION_REJECT -> AppCore.rejectCall()
        }
        // 接听后 CallManager 会走 onStateChanged 撤掉；这里无条件再撤一次（拒绝/异常路径兜底）
        CallNotif.cancel(ctx)
    }
}
