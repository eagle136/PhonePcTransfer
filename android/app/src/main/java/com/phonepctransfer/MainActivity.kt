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

/**
 * 唯一的 Activity。
 *
 * 生命周期 → 剪贴板联动语义：
 *  - onResume / onPause        → 「应用是否可见」。**挂小窗时是 RESUMED**，
 *                                剪贴板轮询挂在这个状态上（不是焦点上），所以小窗里照样能同步。
 *  - onWindowFocusChanged      → 「是否持有窗口焦点」。读剪贴板真正需要的是它（Android 10+）；
 *                                失焦**不停**轮询，只在拿到焦点时补读一次。
 *  - onUserLeaveHint           → 用户主动离开，此刻仍有焦点，是补推剪贴板的可靠时机。
 *
 * 另外这里还是**系统「分享」的入口**（manifest 里给 SEND / SEND_MULTIPLE 注册了全部 MIME 类型）：
 * 别的应用分享文件过来 → 解析出 Uri → 交给 AppCore 弹「发送到哪台设备」→ 选定即开始传。
 */
class MainActivity : ComponentActivity() {

    // 文件/文件夹的挑选已改为**应用内**的 [com.phonepctransfer.ui.FileBrowser]（2026-10-05）：
    // 不再用 ActivityResultContracts.OpenDocument / OpenDocumentTree 那套系统选择器。
    // （SAF 授权对话框只在「没有『所有文件访问』」的降级路径上出现，由浏览器页面自己发起。）

    // ==================== 启动权限引导（一项一问，2026-10-01）====================
    //
    // 冷启动把「未授予、且没被永久跳过」的项排成一队，**一项一弹**，三个出口：
    //   前往   → 发起该项申请（运行时弹框 / 跳对应系统设置页）
    //   取消   → 本次跳过，下次冷启动再问
    //   不需要 → 只把这一项写进 Prefs.permNeverAsk，以后不再问该项
    // 任何一项不给都不拦启动：队列走完后统一 toast 提醒「相关功能可能异常」。
    // 无障碍保活不在这支队列里，另走独立一问两按钮（见 [maybeAskA11yKeepAlive]）。
    // 队列走完时，如果本次**刚授予过**「本地网络 / 文件读取」（BootPerms.rebuildOnGrant）→
    // 自动退出并重新拉起一次：授权前建好的 socket 与设备列表是「半授权」状态的产物，
    // 不换进程就会「点了允许照样连不上」（见 [restartForPermissions]）。

    /** 待询问队列（= BootPerms.list()，已授予与「不需要」的项在推进时跳过） */
    private val bootQueue = mutableListOf<BootPerm>()

    /** 队列游标：指向「正在询问」的那一项 */
    private var bootIdx = 0

    /** 当前正在询问的权限项；null = 不弹。用 Compose 状态，Activity 置值即驱动界面重组 */
    private val bootAskItem = mutableStateOf<BootPerm?>(null)

    /** 「要不要使用无障碍保活」这一问是否显示（本次冷启动只问一次） */
    private val a11yAsk = mutableStateOf(false)

    /**
     * 本次冷启动里是否**刚授予过** [BootPerms.rebuildOnGrant]（本地网络 / 文件读取）里的某一项。
     * 命中就在队列走完时用一次干净的重启把链路和设备列表重建起来（见 [restartForPermissions]）。
     */
    private var bootRebuildNeeded = false

    /** 运行时权限：单个申请（一次只弹一个系统框，由回调驱动下一项） */
    private val bootRuntimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = bootQueue.getOrNull(bootIdx)
            // 拒绝**不阻断**流程：后面还有别的权限要问，且这些权限多是「用到才必需」。
            // 只给一句提示，让用户知道哪块功能会缺。
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
                // 走到回调里说明本次「已请求过」：此时 shouldShowRequestPermissionRationale
                // 返回 false 才是真正的「永久拒绝（不再询问）」；否则是普通拒绝，再点还会弹框。
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
        // Android 15+（API 35）对 targetSdk 35+ 的应用强制 edge-to-edge；这里显式开启让低版本行为一致。
        // 系统栏内边距交给 ChatScreen 的顶栏/底栏各自消费（否则内容会被状态栏、导航栏压住）。
        enableEdgeToEdge()
        // 关掉系统给系统栏自动叠的「对比度蒙层」：三键导航时 Android 会在导航栏区域叠一层半透明黑，
        // 会把应用主题色盖成一条黑边（该开关 API 29+ 起才有）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        AppCore.init(applicationContext)
        // 上一次是因为「刚授予了本地网络 / 文件权限」而自杀重启的 → 说明一句为什么闪了一下
        //（提示只能在重启后弹：重启前的进程马上要死，toast 来不及交给系统）
        if (AppCore.prefs.permRestartNotice) {
            AppCore.prefs.permRestartNotice = false
            AppCore.toast("权限已生效，已重启应用以重建连接与设备列表")
        }
        // 录音权限申请入口：通话/视频按钮点击且未授权时触发。
        // ⚠ 不能在这里用 shouldShowRequestPermissionRationale 预判「永久拒绝」——
        // 该 API 在「首次请求前」同样返回 false，会把第一次点通话误判成永久拒绝、
        // 从而根本不弹权限框（表现为「点了没反应」）。正确做法是直接申请，
        // 在回调里（已请求过）再区分是普通拒绝还是永久拒绝。
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
        // 来电启动意图（全屏意图 / 通知按钮）——必须在 setContent 之前处理：
        // showWhenLocked 越早设越稳；「接听」动作也要在相机权限申请入口注册好之后才执行。
        applyCallIntent(intent)
        // 启动权限引导先跑：一项一弹，每一问的三个出口都在 MainActivity 的 onBootPerm* 里。
        // 必须排在 setContent **之前**，界面才能首帧就把当前那一问画出来。
        startBootPermAsks()
        setContent {
            // 主题状态在**根作用域**读取后传给 PPTheme（见 Theme.kt 的注释）：
            // 切换时整个内容 lambda 重新执行，配色保证当帧跟上新主题。
            val dark = isAppDark()
            // 系统栏样式**显式钉死**成当前主题（2026-10-01 用户反馈：浅色模式下
            // 通知栏时间还是白的）。onCreate 里那次 enableEdgeToEdge() 是 auto=跟随系统，
            // ColorOS 在系统深色 + 应用浅色时会把状态栏图标一直按白色画，
            // 只设 isAppearanceLightStatusBars 会被它盖回去；每次主题变化重设一次固定样式。
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val barStyle = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(barStyle, barStyle)
                onDispose { }
            }
            PPTheme(dark = dark) {
                // 锁屏来电（2026-09-22）：呼入有结果（接听/拒绝/超时）后撤销「盖在锁屏之上」，
                // 免得应用长期凌驾锁屏（那等于锁屏上能直接翻聊天记录）。
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

    /**
     * 应用已在后台/已被 singleTask 收起时，别的应用又分享过来：走这里，**不重建界面**。
     * 系统把本任务带到前台后回调，所以用户看到的就是「应用跳到前台 + 弹选择框」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // 应用已在后台、来电通知把界面顶到锁屏之上：这里接住（singleTask 不重建界面）
        applyCallIntent(intent)
    }

    /**
     * 解析系统「分享」并交给 AppCore → 界面弹「发送到哪台设备」。
     * 非分享 Intent（如点通知/点图标进来的 ACTION_MAIN）会被 [ShareIn.parse] 直接滤掉。
     */
    private fun handleShareIntent(intent: Intent?) {
        val payload = ShareIn.parse(this, intent) ?: return
        AppCore.onShared(payload)
        // 提前把链路拉起来（此刻确实在前台，满足 targetSdk 34+ 的 eligible-state 校验），
        // 这样用户选完设备往往链路已经就绪，可以直接开始传、不用先落到待发队列。
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

    /**
     * 用户主动离开（HOME / 最近任务 / 切换应用）。此回调早于 onPause，此时 Activity 仍持有
     * 焦点，是补推剪贴板的唯一可靠时机（失焦后系统不让读剪贴板）。
     */
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
        // onCreate 里注入的两个权限申请 lambda 捕获了本 Activity，却被 AppCore 单例长期持有：
        // 配置变更（旋转）后旧 Activity 会被多活一份，直到下次 onCreate 覆盖。这里主动断开。
        // 时序安全：配置变更时旧 Activity 完全销毁后才创建新实例，新 onCreate 会重新注入；
        // 置 null 后 AppCoreCall 的 ?.invoke 自然跳过，比「调用已死 Activity 的 launcher」更安全。
        AppCore.recordPermRequester = null
        AppCore.cameraPermRequester = null
    }

    /**
     * 前台服务常驻拉起（WiFi 门禁已移除，数据网络下也运行）。启动点全部位于前台生命周期，
     * 满足 targetSdk 34+ 对 startForeground 的 eligible-state 硬校验。
     */
    private fun ensureService() {
        AppCore.refreshLan()
        val i = Intent(this, TransferService::class.java)
        runCatching { startForegroundService(i) }
    }

    // ==================== 锁屏来电（2026-09-22） ====================

    /**
     * 处理来电相关的启动意图（由 [CallNotif] 的全屏意图 / 通知按钮拉起）：
     *  - [CallNotif.EXTRA_CALL_UI] = true → 允许本界面盖在锁屏**之上**（用户在锁屏上直接接听/挂断）
     *  - [CallNotif.EXTRA_CALL_ACTION] = accept/reject → 通知按钮的动作；进程被系统重建时在此兜底执行
     */
    private fun applyCallIntent(intent: Intent?) {
        val i = intent ?: return
        if (!i.getBooleanExtra(CallNotif.EXTRA_CALL_UI, false)) return
        showOverLockScreen()
        val act = i.getStringExtra(CallNotif.EXTRA_CALL_ACTION)
        // ⚠️ 取完立刻清掉：Activity 重建（配置变更 / 被系统回收后恢复）会拿**同一个 Intent**
        //    再走一遍 onCreate，不清就会「重建一次 = 又接听/又拒绝一次」。
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

    /**
     * 冷启动入口：算出待问队列，从第一项开始**一项一弹**。
     *
     * 这里只弹说明，**不申请任何东西** —— 申请必须等用户在单项弹窗上点「前往」。
     * 核心语义不变：先把这一项的用途讲清楚，再要这一项。
     */
    private fun startBootPermAsks() {
        bootQueue.clear()
        bootQueue += BootPerms.list()
        bootIdx = 0
        askNextBootPerm()
    }

    /**
     * 推进器：弹出 [bootIdx] 那一行的说明弹窗。
     *  - 已授予 / 已选过「不需要」→ 直接让位给下一项
     *  - 队列走完 → 刚授予过「本地网络 / 文件读取」就先重启进程一次（[restartForPermissions]，
     *    旧 socket 不随权限变化）；否则提醒「没给会怎样」→ 再问独立的那一题（无障碍保活）
     *
     * ⚠️ 一次只问一项：批量申请在部分 ROM 上会把多个权限框合成一个不可读的长列表。
     */
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
        // 刚授予过「本地网络 / 文件读取」→ 先干净重启一次再继续（新进程里这些项已被视为满足，
        // 不会重复重启；缺的项照旧逐项问，「没给会怎样」的提醒与无障碍那一问也放到重启之后）。
        if (bootRebuildNeeded) {
            restartForPermissions()
            return
        }
        remindMissingPerms()
        maybeAskA11yKeepAlive()
    }

    /**
     * 刚授予过 [BootPerms.rebuildOnGrant]（本地网络 / 文件读取）→ **自杀并重新拉起自己一次**。
     *（2026-10-01 用户定调：避免授权过程中在缺权限状态下继续跑功能）
     *
     * 为什么必须换进程：权限生效前绑好的 28765/28766 socket 不会随权限变化 —— Android 16+ 没允许
     * 本地网络时，socket 已经在「按 uid 丢弃局域网包」的规则下建立了，点了允许旧进程照样连不上
     * 任何设备（Android 16 机型 实测：授权后立刻连仍超时，重启进程才通）。设备列表与在途会话同理。
     *
     * 顺序讲究：`send()` 是同步 binder 调用，返回时启动请求已经登记在系统侧，紧接着杀掉本进程，
     * 系统会为新界面另起一个干净进程。提示不在这里弹（进程马上就没了，toast 发不出去），
     * 改成写 [Prefs.permRestartNotice]，由重启后的第一个 onCreate 说明一句。
     */
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

    /**
     * 权限没给**不拦使用**，但要说清哪块功能会受影响（用户定调：可以不给，但要提醒）。
     *
     * 实测 a12/MIUI12、a16/ColorOS16：全屏意图会被 ROM 自家闸门静默拦掉，锁屏来电真正靠得住的
     * 是**显示悬浮窗**（AOSP 层硬编码的后台起界面豁免；清单必须声明 SYSTEM_ALERT_WINDOW，
     * 否则悬浮窗管理列表里根本没有本应用 —— 2026-09-22 踩过）。这两项没开的结果是「能收通知、
     * 点通知接听」，但不会自动把接听界面顶到锁屏之上。只 toast 一句，不再自动跳设置页：
     * 跳 = 变相逼用户开，与「可以不给」矛盾（旧的单点跳转引导由此取代）。
     */
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

    /**
     * 「要不要使用无障碍保活」——**独立一问**，不掺进权限队列（2026-10-01 用户定调）。
     * 已开启、已选过「不需要」、或本次冷启动已经问过 → 都不再打扰；一次冷启动最多问一次。
     */
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
