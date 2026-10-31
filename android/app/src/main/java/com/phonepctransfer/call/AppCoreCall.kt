package com.phonepctransfer.call

import com.phonepctransfer.AppCore

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Peer
import com.phonepctransfer.data.PeerGroup
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.Store
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.data.TrustedPeers
import com.phonepctransfer.net.DataConsent
import com.phonepctransfer.net.DataConsentText
import com.phonepctransfer.net.Discovery
import com.phonepctransfer.net.Link
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import com.phonepctransfer.util.LocalIps
import com.phonepctransfer.util.SharedItem
import com.phonepctransfer.util.SharedPayload
import com.phonepctransfer.voice.CallManager
import com.phonepctransfer.video.VideoLevel
import java.io.File

// ==================================================================
// 剪贴板联动 + 语音/视频通话操作（v2.7 拆分自 AppCore.kt）。全部为 AppCore 的扩展函数。
// ==================================================================

    /**
     * onResume：应用可见了。焦点随后由 onWindowFocus(true) 触发读取。
     * 两个入口都会调 pushClipIfChanged()，靠 sha1 去重 + 2s 去抖保证只同步一次。
     */
internal fun AppCore.onAppResumed() {
        appVisible = true
        refreshLan()
        link.flushPending()
        // 回前台自愈：选定的那台设备没有**健康会话**（被系统冻结 / 息屏掐断 socket 后
        // 会话已死，readLoop 判死会摘掉它）→ 重扫一轮，扫描末尾会自动连回「上次设备」
        // （autoConnectLast；已知设备即便在后台也放行）。只认选定过的那台，不做盲目重连。
        if (running && selectedPeerIp != null && !link.isConnected()) {
            appendLog("回前台自愈：$selectedPeerIp 无健康会话，重新扫描并尝试连接")
            startScan()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundAt < 2000L) return
        lastForegroundAt = now
        // 焦点切回自身：读取一次本机剪贴板并同步（无焦点时读不到，自然跳过）
        if (prefs.clipSyncMode) pushClipIfChanged()
    }

    /** onPause：应用不可见了（真正切后台，不是挂小窗）。不读不推。 */
internal fun AppCore.onAppPaused() {
        if (!appVisible) return
        appVisible = false
        if (foregroundActive) appendLog("已切到后台")
        foregroundActive = false
    }

    /**
     * 窗口焦点变化 —— **这是剪贴板读取的唯一触发点**。
     * 拿到焦点的这一刻才能读剪贴板（Android 10+ 焦点闸门）：读一次并同步。
     */
internal fun AppCore.onWindowFocus(hasFocus: Boolean) {
        if (hasFocus) {
            foregroundActive = true
            if (prefs.clipSyncMode) pushClipIfChanged()
        } else {
            foregroundActive = false
        }
    }

    /**
     * 用户主动离开应用（HOME / 最近任务 / 切换应用）时调用。
     * 新语义下不做任何读取：离开不触发同步，内容等下次焦点切回时再推。
     */
internal fun AppCore.onUserLeaveHint() {
        // 纯被动模型：离开应用不读剪贴板（保留空实现，MainActivity 生命周期挂钩用）
    }

    /**
     * 读本机剪贴板，与上次推送哈希不同才推送。读不到（无焦点/后台）直接返回，不报错。
     *
     * 读取只发生在「应用可见/持有焦点」时：前台轮询 + 系统剪贴板变化回调都走这里。
     * 后台读剪贴板（无障碍焦点 overlay、Shizuku 等）已全部移除，本函数只依赖进程内的
     * ClipboardManager，后台返回 null 即代表读不到。
     *
     * @param force 忽略"已经推过 / 刚从对端拉回来"的去重判断，无条件推一次。
     *   用于握手协商的第二步：对端明说它缓冲为空，本机就该把内容补上去，
     *   哪怕这份内容此前已经推过（对端可能是重启后缓冲丢了）。
     */
internal fun AppCore.pushClipIfChanged(force: Boolean = false) {
        val text = clip.read() ?: return
        if (text.isBlank()) return
        val sha = Clip.sha1(text)
        if (!force) {
            if (sha == clip.lastPushedSha1) return
            // 刚由对端写入的内容不再回推，避免死循环
            if (sha == clip.lastPulledSha1) {
                clip.lastPushedSha1 = sha
                prefs.lastPushedSha1 = sha
                return
            }
        }
        clip.lastPushedSha1 = sha
        prefs.lastPushedSha1 = sha
        if (link.isConnected()) link.sendClip(text, sha)
    }

    /**
     * 把对端发来的剪贴板内容直接写进本机剪贴板。
     *
     * 剪贴板同步**不是「发消息」**：这里只写剪贴板 + 打日志，不生成任何聊天气泡
     * （气泡留给文本/文件）。冲突策略是简单后写覆盖——后到的那次写入直接生效。
     */
internal fun AppCore.writeClipboard(text: String) {
        val sha = Clip.sha1(text)
        if (sha == clip.lastPulledSha1) return
        clip.write(text)
        // 标记为本机已推过，避免把它当成"用户新复制的内容"再推回对端
        clip.lastPushedSha1 = sha
        clip.lastPulledSha1 = sha
        prefs.lastPushedSha1 = sha
        prefs.lastPulledSha1 = sha
        // 后台写剪贴板是系统允许的（受限的只是读），所以同步模式下手机没开屏也能收到电脑复制的内容
        appendLog("已接收对端剪贴板并写入本机（${if (appVisible) "前台" else "后台"}，${text.length} 字）")
    }

    /** 发起呼叫（按钮触发）。已有通话时静默拒绝。 */
internal fun AppCore.startCall() {
        if (call.isBusy()) {
            toast("已有通话进行中")
            return
        }
        // 未选定设备：先提示，避免无声失败（之前只写日志，看起来像「点了没反应」）
        val ip = link.currentPeerIp()
        if (ip.isEmpty()) {
            toast("请先选择并连接一台设备")
            return
        }
        // 选中了但当前没有健康会话（未连上 / 半开假连接）：先触发一次（重）连接再提示，
        // 而不是直接报「设备未连接」—— 否则用户感知就是「点了通话按钮就掉线」。
        if (!link.isConnected()) {
            link.connectTo(ip)
            toast("设备未连接，正在尝试连接，请稍后重试")
            return
        }
        // 无录音权限时先申请，授权后再真正发起
        val hasPerm = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            recordPermRequester?.invoke { startCall() }
            return
        }
        startCallWithConsent(ip, video = false, levelIdx = VideoLevel.DEFAULT.idx)
    }

    /**
     * 通话发起前的**数据流量确认**（v2.7，每次发起都问一次，不记忆）：
     * 本机在数据网络 → 先问本机用户；对端在数据网络 → 由 data_ask 让对端问它自己的用户。
     * 任一环节拒绝 → 不发起，并记一条通话消息（「未发起（未同意使用数据流量）」/
     * 「对方拒绝了使用数据流量」）。
     *
     * ⚠ 必须跑在**专用线程**：Link 要弹框等用户点按钮、还要等对端回帧，会阻塞。
     */
internal fun AppCore.startCallWithConsent(ip: String, video: Boolean, levelIdx: Int) {
        val label = if (video) "视频通话" else "语音通话"
        val kind = if (video) DataConsentText.KIND_VIDEO else DataConsentText.KIND_VOICE
        Thread({
            val consent = link.ensureDataConsent(ip, link.newConsentMid(), kind, label)
            ui {
                when (consent) {
                    DataConsent.OK -> {
                        val ok = if (video) call.startOutgoing(video = true, levelIdx = levelIdx)
                        else call.startOutgoing()
                        if (!ok) toast("设备未连接，无法发起$label")
                    }
                    DataConsent.LOCAL_REFUSED -> {
                        addCallRecord(ip, video, 0L, "未发起（未同意使用数据流量）", true)
                        toast("已取消：未同意使用数据流量")
                    }
                    DataConsent.REMOTE_REFUSED -> {
                        addCallRecord(ip, video, 0L, "对方拒绝了使用数据流量", true)
                        toast(DataConsentText.peerRefusedText(kind))
                    }
                    DataConsent.NO_SESSION -> toast("设备未连接，无法发起$label")
                }
            }
        }, "pp-call-consent").apply { isDaemon = true }.start()
    }

internal fun AppCore.acceptCall() {
        // 视频来电：先确保拿到相机权限再同意 —— 没有相机权限就无法解码显示远端画面，
        // 而接听后立刻 TALKING 会错过授权时机（表现为"接了但一片黑"）。
        val videoIncoming = incomingCall?.third == true
        if (videoIncoming &&
            app.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermRequester?.invoke { call.acceptIncoming() }
            return
        }
        call.acceptIncoming()
    }

internal fun AppCore.rejectCall() = call.rejectIncoming()

internal fun AppCore.hangupCall() = call.hangup()

internal fun AppCore.cancelCall() = call.cancelOutgoing()

    /**
     * 记一条**通话记录消息**（v2.7，用户定调「语音、视频以消息方式记录」）。
     *
     * 文本两种形态：
     *  - 通过话 → 「语音通话 00:12」/「视频通话 01:02:03」
     *  - 没接通 → 「语音通话 对方拒绝」/「视频通话 无人接听」/
     *             「语音通话 未发起（未同意使用数据流量）」/「视频通话 对方拒绝了使用数据流量」
     *
     * 只记在本机（hsync 只对账文本与图片，通话记录不跨端同步）。
     */
internal fun AppCore.addCallRecord(
        peerIp: String, video: Boolean, durationMs: Long, reason: String, outgoing: Boolean
    ) {
        if (peerIp.isEmpty()) return
        val label = if (video) "视频通话" else "语音通话"
        val text = if (durationMs >= 1000L) "$label ${formatDuration(durationMs)}" else "$label $reason"
        val item = MsgItem(
            mid = java.util.UUID.randomUUID().toString().replace("-", ""),
            dir = if (outgoing) Dir.SELF else Dir.PEER,
            kind = Kind.CALL,
            text = text,
            ts = System.currentTimeMillis(),
            status = Status.OK,
            peerIp = peerIp
        )
        store.appendHistory(item)
        if (belongsToCurrent(item)) {
            messages.add(item)
            if (messages.size > MAX_UI_MSGS) {
                repeat(messages.size - MAX_UI_MSGS) { messages.removeAt(0) }
            }
        }
        appendLog("通话记录已保存：$text")
    }

    /** 通话时长文案：不足 1 小时用 mm:ss，超过用 h:mm:ss */
internal fun AppCore.formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    /** 免提 ⇄ 听筒切换（通话状态条按钮），返回切换后的免提状态 */
internal fun AppCore.toggleCallSpeaker(): Boolean {
        val v = call.toggleSpeaker()
        callSpeakerOn = v
        return v
    }

    /** 切换通话音频通道（媒体 ⇄ 电话），返回切换后是否媒体通道 */
internal fun AppCore.toggleCallChannel(): Boolean {
        val media = !callMediaChannel
        call.setAudioChannel(media)
        callMediaChannel = media
        toast(if (media) "已切换为媒体通道（媒体音量，耳机自动接管）" else "已切换为电话通道")
        return media
    }

    /**
     * 「旋转对方画面」钮：在当前显示角上 +90°（跨 360 回绕），返回新角供 UI 提示。
     * 按**当前显示角**算而不是叠加量 —— 未手调过时当前显示 = 自适应基线，第一次
     * 按下即把基线位置 +90 记为绝对角，此后钉死。
     *
     * 归属（R5，2026-09-16）：写进**当前通话/对话那台设备**的记忆里 ——
     * 每台设备各记各的，切设备不会串味，卸载前一直沿用。
     * 归属优先取「通话对端」（通话中切设备也不该改到别的设备身上），否则取当前对话设备。
     *
     * 归属再分一层（2026-09-19）：**按对端摄像头朝向分两套**（前置/后置各记各的）——
     * 对端切摄像头时默认显示角不同（前置默认多 180°），两套混在一起必然互相打架。
     *
     * @param peerFront 对端此刻用的是不是前置摄像头
     */
internal fun AppCore.setVideoRemoteRot(deg: Int, peerFront: Boolean = false): Int {
        videoRemoteRot = ((deg % 360) + 360) % 360
        prefs.setRotRemoteOf(rotOwnerKey(), videoRemoteRot, peerFront)
        return videoRemoteRot
    }

    /** 「旋转小窗」钮：同 [setVideoRemoteRot]，独立记忆，按**本机**摄像头朝向分两套 */
internal fun AppCore.setVideoLocalRot(deg: Int, front: Boolean = false): Int {
        videoLocalRot = ((deg % 360) + 360) % 360
        prefs.setRotLocalOf(rotOwnerKey(), videoLocalRot, front)
        return videoLocalRot
    }

    /** 该设备 + 对端朝向 对应的「对方画面」记忆角（-1 = 未手调 → 走自适应基线） */
internal fun AppCore.videoRemoteRotFor(peerFront: Boolean): Int =
        prefs.rotRemoteOf(rotOwnerKey(), peerFront)

    /** 该设备 + 本机摄像头朝向 对应的「本地小窗」记忆角（-1 = 未手调） */
internal fun AppCore.videoLocalRotFor(front: Boolean): Int = prefs.rotLocalOf(rotOwnerKey(), front)

    /** 旋转角归属的设备：通话中 = 通话对端；否则 = 当前对话设备 */
internal fun AppCore.rotOwnerKey(): String {
        val cp = callPeerIp
        val ip = if (cp.isNotEmpty()) cp else (selectedPeerIp ?: "")
        return prefs.convKeyOf(ip)
    }

    /**
     * 对端是否支持视频接收（「视频」按钮据此置灰）。
     *
     * ⚠ 必须先读一次 [peerCapsTick]：能力来自 `link.sessions[].caps`（普通字段），
     * 握手完成不会自动触发重组 —— 不读它，按钮会永久停在「未连接时」算出的
     * 那个 false 上（2026-09-15 实测：手机互打按不出视频通话，与 PC 却正常）。
     * 读一下就建立了 Compose 订阅，握手后（onPeersChanged）自动重新求值。
     */
internal fun AppCore.peerSupportsVideo(): Boolean {
        @Suppress("UNUSED_EXPRESSION") peerCapsTick
        val ip = link.currentPeerIp()
        return ip.isNotEmpty() && link.peerVidCap(ip)
    }

    /**
     * 发起视频通话（「视频」按钮 → 画质选择框 → 这里）。
     *
     * 失败前置全部给出明确 toast —— 避免用户感知「点了没反应」。
     *
     * @param levelIdx 本次通话的**画质上限**：0 = 流畅 480P，1 = 标清 720P，
     *                 2 = 高清 1080P/2M，3 = 超清 1080P/5M（默认取 [VideoLevel.DEFAULT] = 2）。
     *                 网络变差时自动逐级降档（1080P → 720P → 480P），恢复后逐级升回，
     *                 但**升不过这里选的档**；选 3 也先按 2M 出帧（见 `CallManager.startVideo`）。
     */
internal fun AppCore.startVideoCall(levelIdx: Int = VideoLevel.DEFAULT.idx) {
        if (!prefs.videoEnabled) {
            toast("视频通话已在设置中关闭")
            return
        }
        if (call.isBusy()) {
            toast("已有通话进行中")
            return
        }
        val ip = link.currentPeerIp()
        if (ip.isEmpty()) {
            toast("请先选择并连接一台设备")
            return
        }
        if (!link.isConnected()) {
            link.connectTo(ip)
            toast("设备未连接，正在尝试连接，请稍后重试")
            return
        }
        if (!link.localCamCap) {
            toast("本机没有可用摄像头，无法发起视频通话")
            return
        }
        if (!link.peerVidCap(ip)) {
            toast("对端版本不支持视频（会断链），请先升级对端")
            return
        }
        // 权限链：录音 + 相机（授权后重走本方法，所以档位必须一路带着）
        val hasAudio = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            recordPermRequester?.invoke { startVideoCall(levelIdx) }
            return
        }
        val hasCam = app.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            // 授权后重走一遍本方法（此时录音已授权，会直接进入发起）
            cameraPermRequester?.invoke { startVideoCall(levelIdx) }
            return
        }
        startCallWithConsent(ip, video = true, levelIdx = levelIdx)
    }

    /**
     * 切换前后摄（R8）。
     *
     * 成功后 bump [AppCore.selfCamTick]：`VideoCapture.open()` 里 facing 是**同步**更新的，
     * 此刻读 `capture.isFront` 已是新朝向 → 通话界面立刻把本地小窗切到新朝向的旋转槽位
     * （不等 500ms 界面心跳）。
     */
internal fun AppCore.switchCamera() {
        if (!call.switchCamera()) {
            toast("无法切换摄像头（仅一个可用或无视频通话）")
            return
        }
        selfCamTick++
    }

    /** 静音开关 */
internal fun AppCore.toggleMute() {
        videoMuted = call.toggleMute()
        toast(if (videoMuted) "已静音" else "已取消静音")
    }
