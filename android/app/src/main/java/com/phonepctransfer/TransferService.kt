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

/** 前台服务：后台/小窗时保活链路；仅前台拉起（满足 startForeground 校验），持有「播放中」MediaSession 换取 ColorOS 速冻豁免 */
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

    /** 创建「播放中」的 MediaSession（不设轨/不发声），Hans 速冻豁免的关键 */
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

    /** 当前申报的 FGS 类型：基线 dataSync|mediaPlayback（速冻豁免），CAMERA 权限已授予才加 camera（无权限申报会抛 SecurityException）；申报类型恒为 manifest 声明的子集 */
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

    /** 相机权限授予后把 FGS 类型重新申报为含 camera（API 29+ 允许重复申报更新；只允许扩大不降级） */
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
                // 服务常驻（WiFi 门禁已移除），每轮刷通知；相机权限可能后授予，每轮复查并升级 FGS 类型
                updateNotification()
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
        // 释放假媒体会话，免残留系统媒体面板
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
        // 点通知 = 点桌面图标：ACTION_MAIN + LAUNCHER + NEW_TASK|CLEAR_TOP|SINGLE_TOP|RESET_TASK_IF_NEEDED，已有任务整体提到前台并复用原 Activity
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
        // 故意不用 MediaStyle（否则渲染成媒体播放卡片）；Hans 豁免看 FGS 类型+活跃会话而非通知样式；媒体按键仍路由给真正的音乐应用
        return b.setContentTitle(getString(R.string.app_name))
            // 正文是「别划掉」提醒（划掉即失前台服务保护），连接状态/待发条数下沉到 subText
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
