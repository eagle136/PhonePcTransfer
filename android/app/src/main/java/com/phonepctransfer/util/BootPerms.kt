package com.phonepctransfer.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 启动权限引导的**清单模型**（2026-09-22）。
 *
 * 为什么要有这一层：应用启动时先给用户一张「要哪些权限、各用来干什么」的说明单，
 * 用户点确认后**按这里定义的顺序逐个**发起申请（运行时权限走系统弹框，特殊权限跳系统设置页）。
 * 权限与用途文案集中在这一处，弹窗与申请流程共用同一份数据 —— 不会出现「弹窗写了、流程漏申请」
 * 或「申请了、弹窗没解释」的错位。
 *
 * 顺序原则：先运行时权限（系统弹框，能一次点头过），后特殊权限（要跳设置页，打断感强）。
 * 内部再按「能不能连上设备 → 基础功能 → 通话」排，用户点取消的临界点越靠后，能用的功能越多。
 */
enum class PermKind {
    /** 运行时权限：`RequestPermission` 系统弹框，能直接授予 */
    RUNTIME,

    /** 特殊权限：无法弹框授予，只能跳系统设置页让用户手动开 */
    SPECIAL
}

/**
 * 一条待申请权限。
 *
 * @param id       稳定标识（日志 / 抑制旧引导逻辑用，不用来展示）
 * @param title    弹窗里的名称（也是 toast 提示里的名字）
 * @param purpose  用途说明（弹窗正文，必须写清「用来干什么 / 不给会怎样」）
 * @param kind     申请方式
 * @param runtime  仅 [PermKind.RUNTIME]：Manifest 里的权限名
 */
data class BootPerm(
    val id: String,
    val title: String,
    val purpose: String,
    val kind: PermKind,
    val runtime: String? = null
)

object BootPerms {

    /** 引入「本地网络」门禁的最低 API（Android 16）；此版本以下系统不做局域网拦截 */
    private const val SDK_LOCAL_NETWORK_GATE = 36

    /**
     * 本地网络（`ACCESS_LOCAL_NETWORK`，API 36+）。
     *
     * **排在队列最前**：不给它，局域网里的 TCP 会 connect 超时、UDP 双向静默消失，
     * 表现就是「一台设备都扫不到 / 连不上」——其它权限再怎么给都没用（2026-10-01 在
     * Android 17 的 Android 16 机型 上实测：同机 shell 用户能连通，App 自己的 uid 被系统丢包）。
     */
    const val ID_LAN = "lan"

    /** 通知（API 33+ 才是运行时权限） */
    const val ID_NOTIF = "notif"

    /** 图片读取（API 33+ 用 READ_MEDIA_IMAGES；更低版本被存储权限覆盖） */
    const val ID_MEDIA_IMG = "media_img"

    /** 视频读取（API 33+ 独有） */
    const val ID_MEDIA_VIDEO = "media_video"

    /** 音频读取（API 33+ 独有） */
    const val ID_MEDIA_AUDIO = "media_audio"

    /** 低版本（≤32）的整体存储读取 */
    const val ID_STORAGE = "storage"

    /** 麦克风 */
    const val ID_MIC = "mic"

    /** 摄像头 */
    const val ID_CAMERA = "camera"

    /** 显示悬浮窗（锁屏来电弹界面的主路径） */
    const val ID_OVERLAY = "overlay"

    /** 全屏通知（API 34+ 会被系统默认收回） */
    const val ID_FSI = "fsi"

    /** 安装未知来源应用 */
    const val ID_INSTALL = "install"

    /**
     * 后台保活（无障碍服务）。
     *
     * **不在 [list] 里**（2026-10-01 用户定调）：它不是任何功能的前提，混在权限队列里强制引导，
     * 等于逼用户在冷启动路上开一个「读屏幕」观感极强的系统开关。现在由启动阶段**独立一问**
     * （两按钮：需要 / 不需要），拒过就再也不问，随时可在设置页手动开启。
     * 这里的 id 同时作为「不需要」在 Prefs.permNeverAsk 里的键。
     */
    const val ID_A11Y = "a11y"

    /**
     * 授予之后**要重启进程一次**的项：本地网络 + 文件读取（API 33+ 的三条 READ_MEDIA_*，
     * 低版本是整体存储）。
     *
     * 为什么是这两类：它们分别是「连得上设备」和「挑得了文件」的前提，而**授权前建好的
     * socket 不会随权限变化** —— Android 16+ 未允许本地网络时，28765/28766 已经在「按 uid
     * 丢弃局域网包」的规则下绑好了，授权后旧进程照样连不上任何设备（2026-10-01 Android 16 机型 实测：
     * 授权后立刻连仍超时，重启进程才通）。留在原地继续用 = 用户点了允许却还是「功能异常」。
     *
     * 其余项（通知 / 麦克风 / 相机 / 悬浮窗 / 全屏通知 / 装应用）授予后不需要重启，就地继续问答。
     */
    val rebuildOnGrant: Set<String> = setOf(
        ID_LAN, ID_MEDIA_IMG, ID_MEDIA_VIDEO, ID_MEDIA_AUDIO, ID_STORAGE
    )

    /**
     * 当前设备上**全部适用**的权限，按申请顺序排列。
     *
     * 会按 SDK 版本裁剪：API 33+ 用三条 READ_MEDIA_*、低版本用一条 READ_EXTERNAL_STORAGE；
     * USE_FULL_SCREEN_INTENT 只在 API 34+ 才存在（低版本无此权限，列了反而误导）；
     * ACCESS_LOCAL_NETWORK 只在 API 36+ 才拦（低版本列出来会一直显示「未授予」）。
     *
     * 队列里的每一项**单独一问**（三按钮：前往 / 取消 / 不需要），不再合并成一张说明单；
     * 无障碍保活见 [ID_A11Y]，它不在这一列里。
     */
    fun list(): List<BootPerm> {
        val out = mutableListOf<BootPerm>()

        // ---------- 运行时权限：连不上任何设备的那一道坎，放在最前 ----------
        if (Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK_GATE) {
            out += BootPerm(
                ID_LAN, "本地网络",
                "与同一局域网里的电脑、另一台手机直连互传（Android 16+ 不允许则全部设备都扫不到、也连不上）",
                PermKind.RUNTIME, Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }

        // ---------- 运行时权限：文件传输的基础 ----------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += BootPerm(
                ID_NOTIF, "通知",
                "显示文件传输进度、接收提醒与来电通知；不授权则退到后台看不到任何提示",
                PermKind.RUNTIME, Manifest.permission.POST_NOTIFICATIONS
            )
            out += BootPerm(
                ID_MEDIA_IMG, "图片",
                "在手机里挑选要发送的图片；只读取你自己选中的文件，不做相册扫描",
                PermKind.RUNTIME, Manifest.permission.READ_MEDIA_IMAGES
            )
            out += BootPerm(
                ID_MEDIA_VIDEO, "视频",
                "在手机里挑选要发送的视频文件",
                PermKind.RUNTIME, Manifest.permission.READ_MEDIA_VIDEO
            )
            out += BootPerm(
                ID_MEDIA_AUDIO, "音频",
                "在手机里挑选要发送的音频/音乐文件",
                PermKind.RUNTIME, Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            out += BootPerm(
                ID_STORAGE, "存储",
                "在手机里挑选要发送的文件（图片 / 视频 / 音频）",
                PermKind.RUNTIME, Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        // ---------- 运行时权限：通话 ----------
        out += BootPerm(
            ID_MIC, "录音",
            "语音通话与视频通话的麦克风；不授权则无法发起或接听通话",
            PermKind.RUNTIME, Manifest.permission.RECORD_AUDIO
        )
        out += BootPerm(
            ID_CAMERA, "相机",
            "视频通话时采集你的画面；不授权则只能打语音电话",
            PermKind.RUNTIME, Manifest.permission.CAMERA
        )

        // ---------- 特殊权限：跳系统设置页手动开启 ----------
        out += BootPerm(
            ID_OVERLAY, "显示悬浮窗",
            "来电时把接听界面盖到锁屏之上，不用先解锁就能接（系统限制，必须在设置页手动开启）",
            PermKind.SPECIAL
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out += BootPerm(
                ID_FSI, "全屏通知",
                "来电话时全屏弹出接听界面（Android 14+ 系统会默认收回，需手动允许）",
                PermKind.SPECIAL
            )
        }
        out += BootPerm(
            ID_INSTALL, "安装应用",
            "聊天里点收到的 APK 消息时直接调起安装器，不用再去文件管理器里找",
            PermKind.SPECIAL
        )
        return out
    }

    /** 该项当前是否**已授予 / 已开启**（已满足则流程直接跳过，不再打扰） */
    fun granted(ctx: Context, p: BootPerm): Boolean = when (p.id) {
        ID_OVERLAY -> Settings.canDrawOverlays(ctx)

        // 低版本无此开关，视作已满足（list() 里本来也不会列它）
        ID_FSI -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

        ID_INSTALL -> ctx.packageManager.canRequestPackageInstalls()

        else -> p.runtime?.let {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } ?: true
    }

    /**
     * 特殊权限对应的系统设置页；返回 null 表示「运行时权限」或本机没有该设置页
     *（调用方应把它当作已满足、继续下一项，而不是卡住整个队列）。
     */
    fun settingsIntent(ctx: Context, p: BootPerm): Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (p.id) {
            ID_OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            ID_FSI -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            ID_INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            else -> null
        }
    }
}
