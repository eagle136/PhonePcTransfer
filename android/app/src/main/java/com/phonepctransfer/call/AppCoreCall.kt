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

// 剪贴板联动 + 语音/视频通话操作（均为 AppCore 的扩展函数）

    /** onResume：应用可见（焦点读取由 onWindowFocus 触发）；两入口都走 pushClipIfChanged，sha1 去重 + 2s 去抖 */
internal fun AppCore.onAppResumed() {
        appVisible = true
        refreshLan()
        link.flushPending()
        // 回前台自愈：选定设备无健康会话（被冻结/息屏掐断）→ 重扫一轮，扫描末尾自动连回上次设备
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

    /** 窗口焦点变化 —— 剪贴板读取的唯一触发点（Android 10+ 只有拿到焦点这一刻能读） */
internal fun AppCore.onWindowFocus(hasFocus: Boolean) {
        if (hasFocus) {
            foregroundActive = true
            if (prefs.clipSyncMode) pushClipIfChanged()
        } else {
            foregroundActive = false
        }
    }

    /** 用户主动离开应用：不读不推（空实现，供 MainActivity 生命周期挂钩） */
internal fun AppCore.onUserLeaveHint() {
    }

    /** 读本机剪贴板，哈希与上次推送不同才推（仅可见/持焦点时能读到）；force 忽略去重无条件推一次，用于握手时对方明说缓冲为空 */
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

    /** 把对端剪贴板内容写入本机（非「发消息」：不生成气泡），冲突后写覆盖 */
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
        // 未选定设备：先提示，避免无声失败
        val ip = link.currentPeerIp()
        if (ip.isEmpty()) {
            toast("请先选择并连接一台设备")
            return
        }
        // 选中但无健康会话：先触发（重）连接再提示，避免「点了通话按钮就掉线」的感知
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

    /** 通话发起前的数据流量确认（每次问、不记忆）：本机在数据网问本机、对端在数据网由对端问；任一拒绝不发起并记通话消息。须跑专用线程（Link 弹框+等对端帧会阻塞） */
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
        // 视频来电：先确保拿到相机权限再同意（否则无法解码远端画面，且立刻 TALKING 会错过授权时机）
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

    /** 记一条通话记录消息（通过话→时长，未接通→原因）；只记本机，不跨端同步 */
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

    /** 「旋转对方画面」钮：当前显示角 +90°（360 回绕）并钉为绝对角，返回新角；记忆归属通话对端（否则当前对话设备），按对端摄像头朝向分两套 */
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

    /** 对端是否支持视频接收（「视频」按钮据此置灰）；先读 peerCapsTick 建立 Compose 订阅，否则停在「未连接时」的 false */
internal fun AppCore.peerSupportsVideo(): Boolean {
        @Suppress("UNUSED_EXPRESSION") peerCapsTick
        val ip = link.currentPeerIp()
        return ip.isNotEmpty() && link.peerVidCap(ip)
    }

    /** 发起视频通话（各失败前置都给明确 toast）；levelIdx 为本次画质上限：网络变差自动降档、恢复升回但不超过此档 */
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

    /** 切换前后摄；成功后 bump selfCamTick（facing 同步更新，本地小窗立即切到新朝向旋转槽位） */
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
