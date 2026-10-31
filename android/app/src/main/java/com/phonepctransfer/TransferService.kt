package com.phonepctransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.phonepctransfer.net.Link

/**
 * 前台服务：让链路在应用退到后台/挂小窗时依然存活。
 *
 * 启动前置条件（targetSdk 34+ 的硬校验）：
 *  1) 定位/网络类权限已授予 —— 本服务只要 INTERNET/通知
 *  2) 应用处于 eligible state（前台/宽限期）—— 因此只由 MainActivity 在 onResume 里拉起
 * 服务内部 startForeground 全部包 runCatching 兜底。
 *
 * ⚠️ ColorOS 速冻豁免（2026-09-15 a16 实测）：
 * OplusHansManager 对退后台应用 ~6s 后 cgroup freeze + OplusNetd DestroyAllSocket，
 * dataSync 型 FGS 不能豁免（实测被冻、连接 RST）。唯一可靠豁免 = 带活跃 MediaSession
 * （STATE_PLAYING）的 mediaPlayback 型 FGS —— 音乐类应用从不被冻，照此模拟。
 * 因此本服务同时声明 dataSync|mediaPlayback 两种类型，并持有一个"播放中"的 MediaSession。
 */
class TransferService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** 是否已成功 startForeground（未成功时不允许重复申报类型） */
    private var fgsStarted = false

    /** 上次申报的前台服务类型，用于避免无谓的重复 startForeground */
    private var lastFgsType = 0

    /** 假媒体会话：只为让 ColorOS Hans 把本应用当"正在放音乐"而豁免速冻，不真的出声 */
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppCore.init(applicationContext)
        setupMediaSession()
    }

    /**
     * 创建"正在播放"的 MediaSession —— Hans 速冻豁免的关键。
     * 不设任何轨/不发声，只是让系统媒体面板认为有一个活跃播放。
     */
    private fun setupMediaSession() {
        if (mediaSession != null) return
        runCatching {
            val ms = MediaSession(this, "PhonePcTransferLink")
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
            ms.setActive(true)
            mediaSession = ms
            log("MediaSession 已激活（ColorOS 速冻豁免）")
        }.onFailure { Log.w(TAG, "MediaSession 创建失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }
        AppCore.refreshLan()
        startTick()
        return START_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        val n = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val t = currentFgsType()
                startForeground(NOTIF_ID, n, t)
                lastFgsType = t
            } else {
                startForeground(NOTIF_ID, n)
            }
            fgsStarted = true
            true
        }.getOrElse {
            Log.e(TAG, "startForeground 失败: ${it.message}")
            false
        }
    }

    /**
     * 当前要申报的前台服务类型。
     *
     * 基线 = dataSync|mediaPlayback（ColorOS 速冻豁免所需，见类注释）。
     * **camera 只在 CAMERA 权限已授予时才加**：
     * Android 14 起，申报 `camera` 类型时若没有 CAMERA 权限 → startForeground 抛
     * SecurityException → 服务起不来（本项目已知的「manifest/runtime 不一致」类事故）。
     * 恒加 camera 会让「从不打视频的用户」也承担这个风险，故按权限条件添加。
     *
     * 类型始终是 manifest 的超集子集关系：manifest 声明了 dataSync|mediaPlayback|camera，
     * runtime 只会申报其中的子集 → 不会触发「runtime 多要类型」的崩溃。
     */
    private fun currentFgsType(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return t
    }

    /**
     * 视频通话开始时调用：把前台服务类型"升级"到含 camera。
     *
     * 首次 startForeground 时用户可能还没授予相机权限（那时不能申报 camera），
     * 授权后需要重新申报一次 —— API 29+ 允许重复调用 startForeground 更新类型。
     * 注意：**只允许扩大**，缩类型在部分 ROM 上会抛异常，因此这里不做降级。
     */
    private fun refreshFgsType() {
        if (!fgsStarted) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val want = currentFgsType()
        if (want == lastFgsType) return
        runCatching {
            startForeground(NOTIF_ID, buildNotification(), want)
            lastFgsType = want
            Log.i(TAG, "前台服务类型更新为 $want")
        }.onFailure { Log.w(TAG, "更新前台服务类型失败: ${it.message}") }
    }

    private fun startTick() {
        if (tick != null) return
        val r = object : Runnable {
            override fun run() {
                // WiFi 门禁已移除：服务常驻，不再因「没 WiFi」自行收摊
                updateNotification()
                // 相机权限可能在服务启动之后才授予（用户第一次点「视频」时才会弹框）→
                // 每轮 tick 复查一次，授权后自动把 FGS 类型升级到含 camera。
                refreshFgsType()
                main.postDelayed(this, 5000)
            }
        }
        tick = r
        main.postDelayed(r, 5000)
    }

    override fun onDestroy() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        fgsStarted = false
        lastFgsType = 0
        // 假媒体会话随服务销毁一起释放，避免泄漏后残留在系统媒体面板里
        runCatching { mediaSession?.release() }
        mediaSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun statusLine(): String {
        val s = AppCore.linkState
        // 多设备：状态按「在线台数」说，单台才点名（当前选中目标的名字）
        val n = AppCore.connectedPeers.size
        val base = when (s) {
            Link.State.CONNECTED ->
                if (n > 1) "已连接 $n 台设备" else "已连接 ${AppCore.peerLabel.ifEmpty { "电脑" }}"
            Link.State.CONNECTING ->
                if (n > 0) "连接中…（已在线 $n 台）" else "正在连接…"
            Link.State.IDLE -> "未连接"
            Link.State.STOPPED -> "未运行"
        }
        val pending = AppCore.pendingCount
        return if (pending > 0) "$base · 待发 $pending 条" else base
    }

    private fun buildNotification(): Notification {
        // 点通知 = 把应用切到前台。
        // Intent 按「点桌面图标」的标准形态构造：ACTION_MAIN + CATEGORY_LAUNCHER，
        // 并显式带上 NEW_TASK | CLEAR_TOP | SINGLE_TOP | RESET_TASK_IF_NEEDED ——
        // 裸 Intent(context, MainActivity) 在 singleTask 下偶尔会只在最近任务里换个位置而不真的
        // 浮到最前；带上这几个标志后行为和点图标完全一致：已有任务整体提到前台、复用原 Activity
        // （走 onNewIntent，界面状态不丢），从未启动过才新建。
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // ⚠️ 故意不用 Notification.MediaStyle：MediaStyle + sessionToken 会让系统把这条通知
        // 渲染成「媒体播放卡片」（音乐播放器同款外观，还进通知栏媒体区）。
        // ColorOS 的 Hans 豁免看的是 FGS 类型（mediaPlayback）+ 活跃 MediaSession，
        // 不看通知样式 —— 去掉 MediaStyle 后豁免照常生效，通知恢复普通常驻条外观。
        // 假会话也不设 Callback / 不带播放动作处理，媒体按键仍路由给真正的音乐应用，
        // 不影响正常音乐播放。
        return b.setContentTitle(getString(R.string.app_name))
            // 正文换成「别划掉」的提醒（2026-09-22 用户定调）：这条通知 = 前台服务本体，
            // 划掉即失去前台服务保护，后台容易被系统清理 → 用会话来提示用户保留。
            // 连接状态/待发条数下沉到 subText（小字），信息没丢，只是不再占正文。
            .setContentText(getString(R.string.notif_keep_hint))
            .setSubText(statusLine())
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(pi)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun log(s: String) = Log.i(TAG, s)

    companion object {
        private const val TAG = "PP-Service"
        private const val CHANNEL_ID = "pp_transfer"
        private const val NOTIF_ID = 0x5050
    }
}
