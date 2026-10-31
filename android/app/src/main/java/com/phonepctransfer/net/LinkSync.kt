package com.phonepctransfer.net

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phonepctransfer.data.Dir
import com.phonepctransfer.data.Kind
import com.phonepctransfer.data.MsgItem
import com.phonepctransfer.data.Prefs
import com.phonepctransfer.data.Status
import com.phonepctransfer.data.TextUtil
import com.phonepctransfer.util.Clip
import com.phonepctransfer.util.FileIO
import com.phonepctransfer.util.LanGate
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock


import com.phonepctransfer.net.Link.Session

// ======================================================================
// 待发队列与 hsync 历史同步（v2.7 拆分自 Link.kt）。全部为 Link 的扩展函数。
// ======================================================================

internal fun Link.resendPendingFile(uri: Uri, p: MsgItem) {
        val s = sessionByGroup(p.peerIp)
        if (s == null) return // 会话没了：条目留在待发队列，下次握手再试
        val label = if (p.isDir) "发送文件夹" else "发送文件"
        val kind = DataConsentText.KIND_FILE
        val item = p.copy(status = Status.SENDING, progress = 0, peerIp = s.remoteIp)
        when (ensureDataConsent(s.remoteIp, p.mid, kind, label, p.fileName, p.fileSize)) {
            DataConsent.OK -> {
                okUpdate(item)
                if (p.isDir) {
                    val scan = FileIO.scanTree(ctx, uri)
                    if (scan.files.isEmpty() && scan.emptyDirs.isEmpty() && scan.skipped == 0) {
                        denyFileByData(item, "文件夹已不可读", voteDenied = false)
                        return
                    }
                    doSendFolder(uri, item, scan.files, scan.emptyDirs, scan.skipped)
                } else {
                    doSendFile(uri, item)
                }
            }
            DataConsent.LOCAL_REFUSED -> denyFileByData(item, LOCAL_DENY_TEXT)
            DataConsent.REMOTE_REFUSED -> denyFileByData(item, DataConsentText.peerRefusedText(kind))
            DataConsent.NO_SESSION -> {} // 会话已断：留在待发队列等下次
        }
    }

internal fun Link.queuePending(item: MsgItem) {
        store?.addPending(item)
    }

    /**
     * 补发待发队列。
     *
     * @param onlyIp 非空时只补发**目的设备是它**的条目 —— 别的设备的条目留在队列里，
     *   等它们自己连上再发。这样"补发"也不会把 A 的内容发到 B。
     */
internal fun Link.flushPendingNow(onlyIp: String?) {
        val st = store ?: return
        val pending = st.loadPending()
        if (pending.isEmpty()) return
        // 按**设备**（组键）过滤，不按 IP 字符串：待发条目可能是从这台设备的另一个
        // 地址排进去的（换 WiFi/热点），按 IP 比会把同一台设备的待发内容永远漏掉。
        val onlyKey = onlyIp?.let { runCatching { prefs.convKeyOf(it) }.getOrNull() }
        var sent = 0
        for (p in pending) {
            if (!running) return
            // 按设备过滤：条目自带 peerIp 且不属于本次目标那台 → 跳过
            if (onlyKey != null && p.peerIp.isNotEmpty()) {
                val k = runCatching { prefs.convKeyOf(p.peerIp) }.getOrNull()
                if (k != onlyKey) continue
            }
            when (p.kind) {
                // 通话记录永远只是本机留痕，不会进待发队列（存到也直接清掉）
                Kind.CALL -> {
                    st.removePending(p.mid)
                    continue
                }
                Kind.TEXT -> {
                    val s = sessionForPending(p.peerIp) ?: continue
                    if (sendOrDrop(s, Protocol.text(p.mid, p.text), "text")) {
                        st.removePending(p.mid)
                        markStatus(p.mid, Status.OK)
                        sent++
                    }
                }
                Kind.CLIP -> {
                    val s = sessionForPending(p.peerIp) ?: continue
                    // 剪贴板只走局域网链路：非局域网就留在待发里，等这台设备以局域网地址连上再补
                    if (!clipLinkAllowed(s.remoteIp)) continue
                    if (sendOrDrop(s, Protocol.clip(p.mid, p.text, Clip.sha1(p.text)), "clip")) {
                        st.removePending(p.mid)
                        markStatus(p.mid, Status.OK)
                        sent++
                    }
                }
                Kind.FILE -> {
                    if (cancelled.contains(p.mid)) {
                        st.removePending(p.mid)
                        continue
                    }
                    // 已完整送达过的条目当场出队（2026-10-01 重复投递根源修）：
                    // 补发与对端 hsync_pull 点名要同一条时，两条都会往同一条串行队列塞任务，
                    // 前一个跑完 inFlight 就空了 → 同一条文件被发两遍。判据见 [isFileDelivered]。
                    if (isFileDelivered(p.mid, p.peerIp)) {
                        st.removePending(p.mid)
                        log("待发条目 ${p.fileName} 此前已完整送达，直接出队")
                        continue
                    }
                    if (sessionForPending(p.peerIp) == null) continue
                    val uri = runCatching { Uri.parse(p.filePath) }.getOrNull() ?: continue
                    // v2.7：**补发与手动发送走同一道数据流量闸门** —— 重连后自动补发同样
                    // 「每次使用都提醒一次」，绝不因为用户没盯着屏幕就偷偷花流量。
                    // 确认会阻塞等本机用户点、还要等对端 data_ans（最长 90s），而本方法的调用点里
                    // 有 io{}（pp-send 单线程）——在那里阻塞会把整个发送队列卡死 → 整条补发甩到专用线程。
                    // 条目仍留在待发队列，直到发送成功（doSendFile/doSendFolder 内部移除）；
                    // 若同 mid 已在途（补发撞上在途传输），inFlight 守卫会让本次直接返回。
                    sent++
                    // 与用户手动发送共用**同一条串行队列**（2026-09-21 排队发送）：
                    // 补发不会与在途传输并行，仍然一次只传一个文件 —— 老写法各起一条线程，
                    // 一次握手就能同时开几十条补发流，正是"几十个文件一起发就乱"的来源之一。
                    enqueueFileSend("补发 ${p.fileName}") { resendPendingFile(uri, p) }
                }
            }
        }
        if (sent > 0) {
            log("补发待发队列 $sent 条" + if (onlyIp != null) "（仅 $onlyIp）" else "")
        }
    }

    // ==================== 历史同步（hsync，v2.3）====================

    /**
     * 以**统一消息 id（mid）为唯一判据**的双端聊天内容对齐：
     *
     *   握手(hs=1) → 双方各发 hsync_mids（本机持有的该对端 mid 清单）
     *             → 各自算差集 → hsync_pull 请求缺失项
     *             → 对端补发：文本走 hsync_msg（方向按对端视角翻转）；
     *               文件沿用 file_meta/chunks/file_done 管线按**原 mid** 重发
     *             → 接收端按 mid 查重落库（历史/待发/气泡三处共用同一判据）
     *
     * 这样无论消息是「发了没送到」还是「一端清空/重装丢了」，下次连上自动补齐；
     * 重放/重发也不会产生重复气泡。只在双方 hello 广播 hs=1 时启用（老端不认识这些帧，
     * 走 default 只记日志，天然兼容）。
     */
internal fun Link.startHsync(s: Session) {
        if (!s.handshaked || s.closed) return
        val st = store ?: return
        // v2.4 收窄：hsync 只对账「文本 + 图片」——非图片文件与文件夹**绝不**跨端补齐
        //（未完成的传输由待发队列 + 断点续传兜底，与消息状态无关）
        val mids = st.loadHistory(s.remoteIp, Link.HSYNC_SCAN)
            .filter { hsyncSyncable(it) && it.mid.isNotEmpty() }
            .map { it.mid }
        if (mids.isEmpty()) return
        sendOrDrop(s, Protocol.hsyncMids(mids), "hsync_mids", quiet = true)
    }

internal fun Link.onHsyncMids(s: Session, j: JSONObject) {
        val st = store ?: return
        val mine = st.loadHistory(s.remoteIp, Link.HSYNC_SCAN)
            .filter { !it.isDir && it.mid.isNotEmpty() }
            .map { it.mid }
            .toSet()
        val arr = j.optJSONArray("mids") ?: return
        val missing = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optString(i, "")
            if (m.isNotEmpty() && !mine.contains(m)) missing.add(m)
        }
        if (missing.isEmpty()) return
        log("历史同步：对端持有 ${arr.length()} 条，本机缺 ${missing.size} 条，请求补齐")
        sendOrDrop(s, Protocol.hsyncPull(missing.take(Link.HSYNC_PULL_MAX)), "hsync_pull", quiet = true)
    }

internal fun Link.onHsyncPull(s: Session, j: JSONObject) {
        val st = store ?: return
        val arr = j.optJSONArray("mids") ?: return
        val byMid = st.loadHistory(s.remoteIp, Link.HSYNC_SCAN).associateBy { it.mid }
        val texts = org.json.JSONArray()
        var nText = 0
        var nFile = 0
        for (i in 0 until arr.length()) {
            val mid = arr.optString(i, "")
            val m = byMid[mid] ?: continue
            when {
                m.kind == Kind.TEXT && m.text.isNotEmpty() -> {
                    // 方向按**对端视角**翻转：本端持有的 SELF（我发的）在对端应显示为收到
                    texts.put(
                        org.json.JSONObject()
                            .put("mid", m.mid)
                            .put("dir", if (m.dir == Dir.SELF) "PEER" else "SELF")
                            .put("kind", "TEXT")
                            .put("text", m.text)
                            .put("ts", m.ts)
                    )
                    nText++
                }
                m.kind == Kind.FILE && !m.isDir && com.phonepctransfer.util.FileIO.isImageFileName(m.fileName) &&
                    m.fileSize in 1..Link.HSYNC_FILE_MAX && resendHistoryFile(s, m) -> nFile++
            }
        }
        if (nText > 0) {
            sendOrDrop(s, Protocol.hsyncMsg(texts), "hsync_msg", quiet = true)
        }
        if (nText + nFile > 0) {
            log("历史同步：向对端补发 $nText 条文本、$nFile 个文件")
        }
    }

internal fun Link.onHsyncMsg(s: Session, j: JSONObject) {
        val st = store ?: return
        val arr = j.optJSONArray("items") ?: return
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("mid", "")
            val text = o.optString("text", "")
            if (mid.isEmpty() || text.isEmpty()) continue
            // 统一 mid 查重：本机已有该条（含任意设备文件里的历史）就不重复入列
            if (st.hasMid(s.remoteIp, mid)) continue
            emit(
                MsgItem(
                    mid = mid,
                    dir = if (o.optString("dir", "PEER") == "SELF") Dir.SELF else Dir.PEER,
                    kind = Kind.TEXT,
                    text = text,
                    ts = o.optLong("ts", System.currentTimeMillis()),
                    status = Status.OK,
                    peerIp = s.remoteIp
                )
            )
            added++
        }
        if (added > 0) {
            listener?.onHsyncApplied(s.remoteIp, added)
            log("历史同步：本机补入 $added 条文本")
        }
    }

    /**
     * 按原 mid 重发一条历史文件消息（hsync 补齐用）。
     * 沿用 doSendFile 管线：断点续传、sha256 校验、待发兜底全部复用；
     * 接收端凭 file_meta 里的原 mid 生成气泡 —— 内容与 mid 双双对齐。
     */
internal fun Link.resendHistoryFile(s: Session, m: MsgItem): Boolean {
        if (inFlight.contains(m.mid)) return false
        // 本次进程已经把它完整送达过 → 不再重发（2026-10-01 重复投递根源修）。
        // 对端的「缺失清单」是在它自己的握手时刻算的，很可能早于我们刚跑完的那次补发；
        // 真丢了（重装/清历史）的情况极少，且手机进程重启后台账即清，下次握手照常补齐。
        if (isFileDelivered(m.mid, s.remoteIp)) {
            log("历史补齐 ${m.fileName} 本机已完整送达，跳过重发")
            return false
        }
        val raw = m.localUri.ifEmpty { m.filePath }
        if (raw.isEmpty()) return false
        val uri = when {
            raw.startsWith("content:") || raw.startsWith("file:") -> Uri.parse(raw)
            else -> Uri.fromFile(java.io.File(raw))
        }
        val item = m.copy(
            dir = Dir.SELF,
            status = Status.SENDING,
            progress = 0,
            peerIp = s.remoteIp
        )
        // 预检源文件可读：doSendFileInner 的 catch 把任何异常都当会话死亡（关会话），
        // 源文件已不存在的补齐项绝不能走进去，否则补一次历史就把连接搞断
        val probe = runCatching { openSource(uri) }.getOrNull()
        if (probe == null) {
            log("历史同步：文件 ${m.fileName} 源已不可读，跳过")
            return false
        }
        runCatching { probe.close() }
        // 与其它文件发送共用同一条串行队列（2026-09-21）：hsync 补图也不许与在途传输并行。
        // 入队即返回 true（= 已受理）—— 真正的失败在队列线程里记日志并清掉待发兜底。
        enqueueFileSend("历史补齐 ${m.fileName}") {
            try {
                // 不入待发兜底之前的 emit：气泡本端本来就有（历史里那条），进度更新按 mid 合并
                doSendFile(uri, item)
            } catch (t: Throwable) {
                log("历史同步：文件 ${m.fileName} 重发失败(${t.message})")
                store?.removePending(m.mid)
            }
        }
        return true
    }

    /**
     * 一条历史消息是否参与 hsync 对账/补齐：**只有文本和图片**（v2.4 收窄）。
     * 文件与文件夹绝不跨端补齐 —— 未完成的传输走待发队列 + 断点续传，已完成的与消息状态无关。
     */
internal fun Link.hsyncSyncable(m: MsgItem): Boolean =
        when {
            m.kind == Kind.TEXT -> true
            m.kind == Kind.FILE && !m.isDir &&
                com.phonepctransfer.util.FileIO.isImageFileName(m.fileName) -> true
            else -> false
        }

    /**
     * 对端删除了若干文本/图片：本端把界面气泡、历史、待发三处一起移除。
     * 只处理本地，**不再回传** msg_del —— 无回环。对未知 mid 幂等（nothing to do）。
     */
internal fun Link.onRemoteMsgDel(s: Session, j: JSONObject) {
        val st = store ?: return
        val arr = j.optJSONArray("mids") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val mid = arr.optString(i, "")
            if (mid.isEmpty()) continue
            st.removePending(mid)
            st.removeFromHistory(mid)
            listener?.onMessageRemoved(mid)
            n++
        }
        if (n > 0) log("对端删除了 $n 条消息，本端同步移除")
    }
