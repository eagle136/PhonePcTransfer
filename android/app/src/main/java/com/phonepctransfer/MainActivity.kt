package com.phonepctransfer

import com.phonepctransfer.call.*
import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.phonepctransfer.ui.A11yKeepAliveDialog
import com.phonepctransfer.ui.ChatScreen
import com.phonepctransfer.ui.PPTheme
import com.phonepctransfer.ui.isAppDark
import com.phonepctransfer.ui.PermAskDialog
import com.phonepctransfer.util.BootPerm
import com.phonepctransfer.util.BootPerms
import com.phonepctransfer.util.ClipCaptureService
import com.phonepctransfer.util.PermKind
import com.phonepctransfer.util.ShareIn
import com.phonepctransfer.voice.CallManager
import kotlin.system.exitProcess

/** 唯一 Activity，兼系统「分享」入口；剪贴板联动挂生命周期状态（可见即轮询、拿焦点补读、主动离开补推） */
class MainActivity : ComponentActivity() {

    // 文件/文件夹挑选用应用内 FileBrowser，不再走系统 SAF 选择器（无「所有文件访问」的降级路径由浏览器页面发起）

    // ==================== 启动权限引导 ====================
    // 冷启动对未授予且未永久跳过的项一项一弹（前往/取消/不需要），不给不拦启动；
    // 无障碍保活另走独立一问；本次刚授予本地网络/文件读取则队列走完后重启进程一次重建链路（见 [restartForPermissions]）。

    /** 待询问队列（= BootPerms.list()，已授予与「不需要」的项在推进时跳过） */
    private val bootQueue = mutableListOf<BootPerm>()

    /** 队列游标：指向「正在询问」的那一项 */
    private var bootIdx = 0

    /** 当前正在询问的权限项；null = 不弹。用 Compose 状态，Activity 置值即驱动界面重组 */
    private val bootAskItem = mutableStateOf<BootPerm?>(null)

    /** 「要不要使用无障碍保活」这一问是否显示（本次冷启动只问一次） */
    private val a11yAsk = mutableStateOf(false)

    /** 本次冷启动是否刚授予过本地网络/文件读取（是则队列走完后重启一次重建链路） */
    private var bootRebuildNeeded = false

    /** 运行时权限：单个申请（一次只弹一个系统框，由回调驱动下一项） */
    private val bootRuntimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = bootQueue.getOrNull(bootIdx)
            // 拒绝不阻断流程，仅提示相关功能可能异常
            if (!granted && p != null) AppCore.toast("未授予「${p.title}」，相关功能可能异常")
            // 授予的是「前提性」权限 → 记下来，本轮问完重启一次（旧 socket 不随权限变化）
            if (granted && p != null && p.id in BootPerms.rebuildOnGrant) bootRebuildNeeded = true
            bootIdx++
            askNextBootPerm()
        }

    /** 特殊权限：跳系统设置页，返回后继续下一项 */
    private val bootSpecialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val p = bootQueue.getOrNull(bootIdx)
            if (p != null && !BootPerms.granted(this, p)) {
                AppCore.toast("「${p.title}」未开启，相关功能可能异常，可稍后在系统设置里再开")
            }
            bootIdx++
            askNextBootPerm()
        }

    /** 权限请求的「授权后要做的事」：音频/相机各自独立，避免回调跑错分支 */
    private var afterAudioGranted: (() -> Unit)? = null
    private var afterCameraGranted: (() -> Unit)? = null

    /** 录音权限：通话前动态申请 */
    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cont = afterAudioGranted
            afterAudioGranted = null
            if (granted) {
                cont?.invoke() ?: AppCore.startCall()
            } else {
                // 已请求过后 rationale=false 才是永久拒绝（不再询问），否则是普通拒绝再点还会弹
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    AppCore.toast("录音权限已被拒绝，请到系统设置 → 应用 → 权限 中开启")
                } else {
                    AppCore.toast("未授予录音权限，无法发起通话")
                }
            }
        }

    /** 相机权限：视频通话前动态申请 */
    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cont = afterCameraGranted
            afterCameraGranted = null
            if (granted) {
                cont?.invoke() ?: AppCore.startVideoCall()
            } else {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    AppCore.toast("相机权限已被拒绝，请到系统设置 → 应用 → 权限 中开启")
                } else {
                    AppCore.toast("未授予相机权限，无法发起视频通话")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 显式开启 edge-to-edge（各版本行为一致），系统栏内边距由 ChatScreen 顶栏/底栏各自消费
        enableEdgeToEdge()
        // 关闭系统栏对比度蒙层（API 29+，否则三键导航叠一条黑边）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        AppCore.init(applicationContext)
        // 因授权触发自杀重启的，重启后说明一句（toast 只能重启后弹）
        if (AppCore.prefs.permRestartNotice) {
            AppCore.prefs.permRestartNotice = false
            AppCore.toast("权限已生效，已重启应用以重建连接与设备列表")
        }
        // 录音权限申请入口：直接申请，回调里再区分普通/永久拒绝（rationale API 首请求前也返回 false，不能预判）
        AppCore.recordPermRequester = { after ->
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                after()
            } else {
                afterAudioGranted = after
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        // 相机权限同上（视频通话与「接听视频来电」都走这里）
        AppCore.cameraPermRequester = { after ->
            if (checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                after()
            } else {
                afterCameraGranted = after
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        // 来电启动意图（须先于 setContent：showWhenLocked 越早越稳，接听动作还依赖相机申请入口已注册）
        applyCallIntent(intent)
        // 启动权限引导须先于 setContent，首帧才能画出当前那一问
        startBootPermAsks()
        setContent {
            // 主题状态在根作用域读取后传给 PPTheme，保证配色当帧跟上新主题
            val dark = isAppDark()
            // 系统栏样式显式钉死成当前主题（ColorOS 浅色主题下状态栏图标否则一直画白）
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val barStyle = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(barStyle, barStyle)
                onDispose { }
            }
            PPTheme(dark = dark) {
                // 呼入有结果后撤销「盖在锁屏之上」，避免应用长期凌驾锁屏
                val inc = AppCore.incomingCall
                val talking = AppCore.callState == CallManager.State.TALKING
                LaunchedEffect(inc != null, talking) {
                    if (inc == null && !talking) exitLockScreenOverlay()
                }
                ChatScreen()
                // 权限逐项一问（盖在主界面之上）：当前那一问为空就不显示
                bootAskItem.value?.let { p ->
                    PermAskDialog(
                        perm = p,
                        onGo = {
                            bootAskItem.value = null
                            onBootPermGo(p)
                        },
                        onCancel = { onBootPermCancel() },
                        onNever = { onBootPermNever(p.id) }
                    )
                }
                // 「要不要使用无障碍保活」：权限队列走完才问，与上面那些项互不相干
                if (a11yAsk.value) {
                    A11yKeepAliveDialog(
                        onNeed = { openA11ySettings() },
                        onNoNeed = { declineA11yKeepAlive() },
                        onDismiss = { a11yAsk.value = false }
                    )
                }
            }
        }
        handleShareIntent(intent)
    }

    /** 后台收到分享：不重建界面，任务带到前台后直接处理 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // 应用已在后台、来电通知把界面顶到锁屏之上：这里接住（singleTask 不重建界面）
        applyCallIntent(intent)
    }

    /** 解析系统「分享」交 AppCore 弹「发送到哪台设备」（非分享 Intent 被 [ShareIn.parse] 滤掉） */
    private fun handleShareIntent(intent: Intent?) {
        val payload = ShareIn.parse(this, intent) ?: return
        AppCore.onShared(payload)
        // 前台提前拉起链路，选完设备往往可直接开传、不必落待发队列
        ensureService()
    }

    override fun onResume() {
        super.onResume()
        AppCore.onAppResumed()
        ensureService()
    }

    override fun onPause() {
        AppCore.onAppPaused()
        super.onPause()
    }

    /** 用户主动离开（早于 onPause、仍持有焦点），是补推剪贴板的唯一可靠时机 */
    override fun onUserLeaveHint() {
        AppCore.onUserLeaveHint()
        super.onUserLeaveHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppCore.onWindowFocus(hasFocus)
        if (hasFocus) ensureService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 两个权限申请 lambda 捕获了本 Activity 却被 AppCore 单例长期持有：销毁时主动断开，防调用已死 Activity 的 launcher
        AppCore.recordPermRequester = null
        AppCore.cameraPermRequester = null
    }

    /** 前台服务常驻拉起（启动点都在前台生命周期，满足 startForeground 校验） */
    private fun ensureService() {
        AppCore.refreshLan()
        val i = Intent(this, TransferService::class.java)
        runCatching { startForegroundService(i) }
    }

    // ==================== 锁屏来电 ====================

    /** 处理来电启动意图（[CallNotif] 全屏意图/通知按钮拉起）：允许盖锁屏之上，并兜底执行接听/挂断动作 */
    private fun applyCallIntent(intent: Intent?) {
        val i = intent ?: return
        if (!i.getBooleanExtra(CallNotif.EXTRA_CALL_UI, false)) return
        showOverLockScreen()
        val act = i.getStringExtra(CallNotif.EXTRA_CALL_ACTION)
        // 动作取完立刻清掉，防 Activity 重建后拿同一 Intent 重复执行
        i.removeExtra(CallNotif.EXTRA_CALL_ACTION)
        when (act) {
            CallNotif.ACTION_ACCEPT -> AppCore.acceptCall()
            CallNotif.ACTION_REJECT -> AppCore.rejectCall()
        }
    }

    /** 允许本界面显示在锁屏之上，并在来电时点亮屏幕（API 27+ 的官方开关：Window flag 的封装） */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        runCatching {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }.onFailure { Log.w(TAG, "设置锁屏显示失败: ${it.message}") }
    }

    /** 呼入结束 → 撤销「盖在锁屏之上」（由 setContent 里的 LaunchedEffect 触发） */
    private fun exitLockScreenOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        runCatching {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    /** 冷启动入口：建待问队列并从第一项开始一项一弹（只弹说明，申请等用户点「前往」） */
    private fun startBootPermAsks() {
        bootQueue.clear()
        bootQueue += BootPerms.list()
        bootIdx = 0
        askNextBootPerm()
    }

    /** 推进器：一项一弹（已授予/「不需要」跳过）；队列走完若刚授予本地网络/文件读取先重启，否则提醒缺项并问无障碍保活 */
    private fun askNextBootPerm() {
        val never = AppCore.prefs.permNeverAsk
        while (bootIdx < bootQueue.size) {
            val p = bootQueue[bootIdx]
            if (BootPerms.granted(this, p) || p.id in never) {
                bootIdx++
                continue
            }
            bootAskItem.value = p
            return
        }
        bootAskItem.value = null
        // 刚授予过本地网络/文件读取 → 先重启一次再继续（新进程视为已满足，不会重复重启）
        if (bootRebuildNeeded) {
            restartForPermissions()
            return
        }
        remindMissingPerms()
        maybeAskA11yKeepAlive()
    }

    /** 自杀并重新拉起自己一次：旧 socket 不随权限变化，授权后旧进程照样连不上；提示 toast 推迟到重启后首个 onCreate */
    private fun restartForPermissions() {
        AppCore.prefs.permRestartNotice = true
        val i = packageManager.getLaunchIntentForPackage(packageName)
        if (i == null) {
            Log.w(TAG, "拿不到本应用启动 Intent：仅结束当前任务，不重启")
            finishAndRemoveTask()
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        runCatching { pi.send() }.onFailure { Log.w(TAG, "重新拉起失败: ${it.message}") }
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    /** 「前往」→ 发起当前项的申请：运行时权限弹系统框，特殊权限跳它自己对应的设置页 */
    private fun onBootPermGo(p: BootPerm) {
        when (p.kind) {
            PermKind.RUNTIME -> bootRuntimeLauncher.launch(p.runtime!!)

            PermKind.SPECIAL -> {
                val i = BootPerms.settingsIntent(this, p)
                if (i == null) {
                    // 本机压根没这个开关（例如低版本没有全屏通知）：当作已满足，推进
                    bootIdx++
                    askNextBootPerm()
                } else if (runCatching { bootSpecialLauncher.launch(i) }.isFailure) {
                    // 个别 ROM 删了这个设置页：不阻断，继续下一项
                    Log.w(TAG, "权限设置页打不开: ${p.id}")
                    bootIdx++
                    askNextBootPerm()
                }
            }
        }
    }

    /** 「取消」→ 本次跳过这一项，下次冷启动照旧再问 */
    private fun onBootPermCancel() {
        bootAskItem.value = null
        bootIdx++
        askNextBootPerm()
    }

    /** 「不需要」→ 只把这一项写进永久跳过表，其它项不受影响 */
    private fun onBootPermNever(id: String) {
        bootAskItem.value = null
        AppCore.prefs.permNeverAsk = AppCore.prefs.permNeverAsk + id
        bootIdx++
        askNextBootPerm()
    }

    /** 权限没给不拦使用，只 toast 提醒受影响的功能（锁屏来电实际靠「显示悬浮窗」而非全屏意图） */
    private fun remindMissingPerms() {
        // 本地网络（API 36+）没给 = 一台设备都连不上，优先级高于下面两条「功能受限」的提醒
        val lan = BootPerms.list().firstOrNull { it.id == BootPerms.ID_LAN }
        if (lan != null && !BootPerms.granted(this, lan)) {
            AppCore.toast("未允许「本地网络」，系统会丢弃本机的局域网数据包：扫不到设备、也连不上任何设备")
        }
        // 文件读取没给 = 选不了要发送的文件（本地网络上面单独说过，这里不重复）
        val noFile = BootPerms.list().filter {
            it.id != BootPerms.ID_LAN && it.id in BootPerms.rebuildOnGrant &&
                !BootPerms.granted(this, it)
        }
        if (noFile.isNotEmpty()) {
            AppCore.toast("未授予「${noFile.joinToString("、") { it.title }}」：选不了要发送的文件")
        }
        if (!Settings.canDrawOverlays(this)) {
            AppCore.toast(
                "未开启「显示悬浮窗」，锁屏来电只能以通知形式提示；" +
                    "MIUI/ColorOS 的「锁屏显示 / 后台弹出界面」也在同一处开"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != true
        ) {
            AppCore.toast("未允许「全屏通知」，来电不会全屏弹出，锁屏上需点通知里的「接听」")
        }
    }

    /** 「无障碍保活」独立一问（不掺权限队列，一次冷启动最多问一次） */
    private fun maybeAskA11yKeepAlive() {
        if (a11yAsk.value) return
        if (BootPerms.ID_A11Y in AppCore.prefs.permNeverAsk) return
        if (ClipCaptureService.isEnabled(this)) return
        a11yAsk.value = true
    }

    /** 保活弹窗「需要」→ 跳系统无障碍设置页（= 原来那一项的流程），返回后不再追问 */
    private fun openA11ySettings() {
        a11yAsk.value = false
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { Log.w(TAG, "无障碍设置页打不开: ${it.message}") }
    }

    /** 保活弹窗「不需要」→ 记进跳过表，以后冷启动不再问（设置页随时仍可手动开） */
    private fun declineA11yKeepAlive() {
        a11yAsk.value = false
        AppCore.prefs.permNeverAsk = AppCore.prefs.permNeverAsk + BootPerms.ID_A11Y
    }

    private companion object {
        const val TAG = "PP-MainAct"
    }
}
