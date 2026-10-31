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
// 连接引擎（v2.7 拆分自 Link.kt）：保活重连、监听/连接循环、读循环、心跳、发送与弃会话。
// ======================================================================

/** 保活重连节奏（2026-10-01 定稿）：断开 → 等 5s/10s/30s 各主动拨一次，仍不通转纯被动 */
internal val KEEP_ALIVE_LADDER_MS = longArrayOf(5_000L, 10_000L, 30_000L)
/** 最后一次拨号后等多久判定结果（TCP 连接 3s + 握手等待 2.5s，留余量） */
internal const val KEEP_ALIVE_RESOLVE_MS = 10_000L
/** 判定「不降级」时（本端正在传输/通话）保活重拨的节奏 */
internal const val KEEP_ALIVE_BUSY_MS = 30_000L

internal fun Link.scheduleKeepAlive(ip: String) {
        if (keepAliveJobs.containsKey(ip)) return
        log("对端 $ip 会话断开，启动保活重连（5s/10s/30s 各重试一次；仍不通转纯被动等待，不再主动敲）")
        runKeepAliveDial(ip, 0)
    }

/**
 * 保活重连状态机（单线程池 [Link.keepAliveExec]，链式一次性调度，任一时刻每台设备只有一个在途任务）：
 * 阶梯拨号（[KEEP_ALIVE_LADDER_MS]）→ 最后一拨后判定：恢复即取消；本端正在传输/通话
 * （[Link.keepAliveBusy]）→ 不降级，每 30s 继续拨并再判定；否则转纯被动。
 */
internal fun Link.runKeepAliveDial(ip: String, attempt: Int) {
        if (!running) return
        val lastLadder = KEEP_ALIVE_LADDER_MS.size - 1
        val delayMs = if (attempt <= lastLadder) KEEP_ALIVE_LADDER_MS[attempt] else KEEP_ALIVE_BUSY_MS
        keepAliveJobs[ip] = keepAliveExec.schedule({
            try {
                if (!running) {
                    keepAliveJobs.remove(ip)
                    return@schedule
                }
                // 已有会话（任何方向重新连上的，含同设备的另一个地址）→ 保活任务完成使命
                if (sessionByGroup(ip) != null) {
                    cancelKeepAlive(ip)
                    return@schedule
                }
                // 一台设备多个地址：先试原来那个，不通再依次试同设备的其它地址（成功即停）
                connectTo(ip, quiet = true, tryGroup = true)
                if (attempt < lastLadder) runKeepAliveDial(ip, attempt + 1)
                else runKeepAliveResolve(ip)
            } catch (t: Throwable) {
                Log.w(Link.TAG, "保活重连异常: ${t.message}")
            }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

/** 阶梯拨完/忙时重拨后的判定：连上→撤；本端忙→继续拨；否则→转纯被动 */
internal fun Link.runKeepAliveResolve(ip: String) {
        if (!running) return
        keepAliveJobs[ip] = keepAliveExec.schedule({
            try {
                if (!running) {
                    keepAliveJobs.remove(ip)
                    return@schedule
                }
                if (sessionByGroup(ip) != null) {
                    cancelKeepAlive(ip)
                    return@schedule
                }
                if (keepAliveBusy?.invoke() == true) {
                    log("对端 $ip 保活未恢复，但本机正在传文件/通话 → 不转被动，每 30s 继续重试")
                    runKeepAliveDial(ip, KEEP_ALIVE_LADDER_MS.size)
                } else {
                    keepAliveJobs.remove(ip)
                    log("对端 $ip 三次重试（5s/10s/30s）均未连通 → 已转纯被动：等待对端连入，点设备可重新主动连接")
                }
            } catch (t: Throwable) {
                Log.w(Link.TAG, "保活重连判定异常: ${t.message}")
            }
        }, KEEP_ALIVE_RESOLVE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

internal fun Link.cancelKeepAlive(ip: String) {
        keepAliveJobs.remove(ip)?.cancel(false)
    }

    // ==================== 监听 / 连接 ====================

internal fun Link.acceptLoop() {
        // 绑定重试：覆盖安装（pm install -r）后旧进程可能被保活机制短暂拉住、尚未释放
        // TCP 端口，新进程一次 bind 失败就永久退出会让设备「谁都连不上」（2026-09-15
        // a12 实测 EADDRINUSE 后装聋）。每 3s 重试直到成功或 Link 停止。
        // 进一步（2026-09-15 a12 二次实测）：MIUI 的 scene-daemon 会**长期占用
        // 127.0.0.1:8765**，通配绑定（*:8765）与 loopback 上的监听冲突，重试永远失败。
        // 此时退回绑定本机局域网 IPv4 —— 与 127.0.0.1 的占用者不冲突，协议行为不变。
        var bound: ServerSocket? = null
        var attempt = 0
        var fallbackLogged = false
        while (running && bound == null) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(prefs.tcpPort))
                bound = ss
            } catch (t: Throwable) {
                attempt++
                val lan = localLanIpv4()
                if (lan != null) {
                    try {
                        val ss = ServerSocket()
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(InetAddress.getByName(lan), prefs.tcpPort))
                        bound = ss
                        log("通配绑定 ${prefs.tcpPort} 失败（${t.message}），已改为绑定本机 IP $lan:${prefs.tcpPort}")
                        continue
                    } catch (t2: Throwable) {
                        // 落到下面的通用重试
                    }
                }
                if (!fallbackLogged) {
                    log("监听 ${prefs.tcpPort} 端口被占用（${t.message}），每 3 秒重试" +
                        (if (lan == null) "（未找到可用的局域网 IPv4）" else "（绑定 $lan 也失败）"))
                    fallbackLogged = true
                }
                waitFor(3000)
            }
        }
        val ss = bound ?: return
        try {
            serverSocket = ss
            log("开始监听 TCP ${prefs.tcpPort}" + if (attempt > 0) "（重试 $attempt 次后成功）" else "")
            while (running) {
                val sk = ss.accept() ?: continue
                runCatching { sk.tcpNoDelay = true }
                adopt(sk, outgoing = false)
            }
        } catch (t: Throwable) {
            if (running) log("监听退出: ${t.message}")
        }
    }

    /** 找本机局域网 IPv4（site-local 且非 loopback），找不到返回 null */
internal fun Link.localLanIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /**
     * 连接工人：**只在有人点了设备（或启动自动连接）时才动一次**，失败不重试。
     *
     * 旧版是个 while 轮询循环（连不上就 1s→2s→5s 一直敲，还要处理 busy 退避），
     * 多端在线时会互相刷日志、互相挤连接。现在改成"点一次、试一次"：请求由 connectTo()
     * 投递，处理完即清空，之后线程只是睡着，不再产生任何网络行为 —— 这也是
     *「不主动频繁发包」的一部分。
     *
     * 2026-09-19「一台设备多个地址」：请求是**一串**候选地址（见 [Link.groupCandidates]），
     * 这里串行逐个试：**某个地址连上并握手成功 → 立刻停**，后面同设备的地址一律不再试；
     * 全部失败 → 队列清空、**不再重试**，直到对端被动连过来或用户再点一次设备。
     */
internal fun Link.connectLoop() {
        while (running) {
            val queue = pendingConnectIps
            if (queue.isNullOrEmpty()) {
                waitFor(1500)
                continue
            }
            val requested = queue.firstOrNull().orEmpty()
            var done = false
            while (running && !done) {
                // 队列已被别人接管（对端被动连上 / 用户又点了别的设备）→ 立刻让位，不再抢着拨
                if (pendingConnectIps !== queue) break
                val dst = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.removeAt(0)
                }
                if (dst == null) break
                // 设备层已可达（刚有会话连上 / 被动接纳）→ 剩余候选全部作废
                if (sessionByGroup(dst) != null) {
                    pendingConnectIps = null
                    refreshState()
                    break
                }
                connectingIp = dst
                stateNote = ""
                refreshState()
                var s: Session? = null
                try {
                    val sk = Socket()
                    sk.tcpNoDelay = true
                    runCatching {
                        sk.sendBufferSize = 1 shl 20
                        sk.receiveBufferSize = 1 shl 20
                    }
                    sk.connect(InetSocketAddress(dst, prefs.tcpPort), 3000)
                    log("主动连接 $dst:${prefs.tcpPort} 成功")
                    val ns = adopt(sk, outgoing = true)
                    s = ns
                    // TCP 通 ≠ 这台设备在线：地址可能被别的主机占用 / 端口被别的服务占着。
                    // 等一帧 hello 握手确认「对端确实是我们的对端」才算试通；否则继续下一个地址。
                    if (waitHandshake(ns, HANDSHAKE_WAIT_MS)) {
                        log("设备地址 $dst 握手成功，停止尝试其余地址")
                        pendingConnectIps = null
                        if (dst != requested) {
                            log("请求的是 $requested，实际连上的是同设备的 $dst")
                            runCatching { onGroupFallback?.invoke(requested, dst) }
                        }
                        done = true
                    } else {
                        log("地址 $dst 已连通但未握手（非本应用 / 对端无响应），换下一个地址")
                        dropSession(ns)
                    }
                } catch (t: Throwable) {
                    log("连接 $dst:${prefs.tcpPort} 失败: ${t.message}" +
                        if (queue.isEmpty()) "（该设备全部地址均不可达，不自动重试）" else "（换下一个地址）")
                    runCatching { s?.close() }
                } finally {
                    connectingIp = null
                    refreshState()
                }
            }
            val mine = pendingConnectIps === queue
            if (mine) pendingConnectIps = null
            if (!done && mine) {
                // 全部候选都失败：如实提示，不做任何重试（重连只能靠用户再点一次设备）
                stateNote = "连接失败，点设备重试"
                refreshState()
            }
        }
    }

    /**
     * 等这条会话完成握手（hello/ping 任一帧回来即可）。
     * @return true = 已握手（该地址确实是我们的对端）
     */
internal fun Link.waitHandshake(s: Session, ms: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - t0 < ms) {
            if (s.handshaked) return true
            if (s.closed) return false
            waitFor(100)
        }
        return s.handshaked
    }

    /** 单地址握手确认窗口：局域网内足够，超时即换下一个地址 */
internal val Link.HANDSHAKE_WAIT_MS: Long get() = 2500L

internal fun Link.adopt(socket: Socket, outgoing: Boolean): Session {
        val s = Session(socket, outgoing)
        // 网段判定只在这一处：命中 → 不进 sessions、不发 hello、不启读线程，立即 close 放掉 FD。
        // 出向侧由现成的 waitHandshake（见 closed 即 false）→ dropSession 归入「已连通但未握手」老口径，
        // 入向侧 acceptLoop 本来就用不到返回值。不打任何日志。
        val rip = socket.inetAddress?.hostAddress ?: ""
        if (BlocklistIpv6.isHighRisk(rip) || prefs.isBlockedAddr(rip)) {
            val key = prefs.convKeyOf(rip)
            prefs.markBlockedGroup(key)
            blockDebugReport(key)          // 定稿时随行删除
            runCatching { s.close() }
            return s
        }
        // 入向闸门（策略在 AppCore）：**后台不接受陌生设备主动连入**，只放行已知设备。
        // 不打招呼、不进 sessions，直接 close 放掉 FD。出向不受此限（对端前台用户点击建立）。
        if (!outgoing && rip.isNotEmpty() && incomingGate?.invoke(rip) == true) {
            log("后台拒接陌生设备入向连接：$rip")
            runCatching { s.close() }
            return s
        }
        sessions.add(s)
        TxStats.printer = { log(it) }   // 诊断行并入应用内日志（未绑定前只打 logcat）
        log("接纳${if (outgoing) "主动" else "被动"}连接 ${socket.inetAddress?.hostAddress}")
        val ok = sendOrDrop(
            s,
            Protocol.hello(myId, myName, Build.MODEL ?: "android", localVidCap, localCamCap, localVCodecs),
            "hello"
        )
        if (!ok) return s
        Thread({ readLoop(s) }, "pp-read").apply { isDaemon = true }.start()
        return s
    }

internal fun Link.readLoop(s: Session) {
        try {
            while (running && !s.closed) {
                val rfT0 = System.nanoTime()
                val f = Protocol.readFrame(s.input)
                TxStats.read(System.nanoTime() - rfT0)
                when (f) {
                    null -> break
                    is Protocol.Frame.Json -> {
                        s.touchRx()
                        handleFrame(s, f.j)
                    }
                    is Protocol.Frame.Chunk -> {
                        s.touchRx()
                        onChunk(s, f.mid, f.seq, f.data, f.len)
                    }
                    is Protocol.Frame.Audio -> {
                        s.touchRx()
                        listener?.onCallAudio(s.remoteIp, f.seq, f.ts, f.data, f.len)
                    }
                    is Protocol.Frame.Video -> {
                        // ⚠ 网络读线程：监听方只能入队，绝不能在这里做解码器/camera 初始化
                        //   （本项目踩过两次：读线程被堵 → 对端 35s 判死 + 挂断抢锁死锁）
                        s.touchRx()
                        listener?.onVideoFrame(s.remoteIp, f.seq, f.ts, f.flag, f.data, f.len)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running && !s.closed) log("读循环异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            s.close()
            sessions.remove(s)
            if (s.peerId.isNotEmpty()) {
                // 只在映射确实指向这条会话时才摘，避免把去重后保留的那条挤掉
                byPeer.remove(s.peerId, s)
            }
            log("会话结束 ${s.remoteIp} peer=${s.peerName.ifEmpty { "?" }}" +
                if (s.handshaked) "" else " (未握手)")
            abortRxsFor(s.remoteIp)
            notifyPeers()
            // 保活重连：本端发起成功过的会话断开后按 5s/10s/30s 阶梯自动重连，
            // 仍不通转纯被动（传输/通话中除外，见 runKeepAliveResolve）。
            // 入向会话不重试 —— 本方向可能被对端防火墙挡，由对端保活。
            if (running && s.handshaked && s.outgoing) {
                scheduleKeepAlive(s.remoteIp)
            }
            refreshState()
        }
    }

    /**
     * 心跳循环（每 5s 一次，见 [Link.STALL_SCAN_MS]）：ping + 假连接判定 + 停滞看门狗。
     * 停滞看门狗每 tick 顺带跑（不新开线程）：接收侧 30s 无进度 / 发送侧 30s 无新字节
     * → 停成「已暂停」，半成品保留，重连后自动续传；另回收闲置 10 分钟的未完成目录。
     */
internal fun Link.heartbeatLoop() {
        while (running) {
            waitFor(Link.STALL_SCAN_MS)
            if (!running) return
            // 停滞看门狗（5s 周期）：先收后发，再回收闲置目录
            scanStalledRx()
            scanStalledSends()
            pruneDirs()
            for (s in sessions) {
                if (s.closed) continue
                if (!s.handshaked && System.currentTimeMillis() - s.lastRx > 8000) {
                    log("握手超时，关闭会话")
                    s.close()
                    continue
                }
                // 判死只看收包：自身 ping 只会刷 lastTx（内核 ACK 即"成功"），
                // 不能作为对端存活证据。pingOutstanding=true 表示上一轮 ping
                // 之后 15s 内连 pong 都没等到，两轮即 35s+ 无任何收包 → 判死。
                val rxIdle = System.currentTimeMillis() - s.lastRx
                if (s.handshaked && s.pingOutstanding && rxIdle > 35000) {
                    log("对端 35s 无响应（ping 无 pong），判定断链（rx 空闲 ${rxIdle}ms）")
                    s.close()
                    continue
                }
                // 只有 ping 真发出去了才置 pingOutstanding；tryLock 失败（正在传文件）
                // 就跳过本轮 —— 否则「想发没发成」会把活跃会话误判死
                if (s.tryPing()) {
                    s.pingOutstanding = true
                }
            }
        }
    }

    // ==================== 发送（失败即判死） ====================

    /**
     * 统一发送入口：任何异常都视为该会话已失效——立刻关闭它、让状态按会话集合重算。
     *
     * 只动这一条会话：**别的设备的连接不受影响**（多设备互不干扰）。
     * 不重连 —— 断了就是断了，用户点设备才会再连。
     */
internal fun Link.sendOrDrop(s: Session, j: JSONObject, what: String, quiet: Boolean = false): Boolean {
        return try {
            s.send(j)
            true
        } catch (nm: android.os.NetworkOnMainThreadException) {
            // 调用方漏走 io{}，在主线程上写了 socket。这是**调用方的错，不是链路坏了** ——
            // 绝不能据此关会话（旧行为＝点一下通话按钮就把连接判死 ＝「点击立即断线」），
            // 改为投到 pp-send 补发一次，补发真失败才判断链。
            Log.w(Link.TAG, "$what 在主线程被拒（调用方未走 io{}），转 pp-send 补发")
            io {
                runCatching { s.send(j) }.onFailure { t ->
                    log("$what 补发失败(${t.javaClass.simpleName}: ${t.message})，判定断链")
                    dropSession(s)
                }
            }
            true
        } catch (t: Throwable) {
            if (!quiet) {
                log("$what 发送失败(${t.javaClass.name}: ${t.message})，判定断链（点设备可重连）")
                Log.w(Link.TAG, "$what 发送失败，异常栈：", t)
            }
            dropSession(s)
            false
        }
    }

    /**
     * 会话失效：关掉并从集合摘除，状态按会话集合重算。
     * 只影响这一条会话 —— 别的设备的连接不受影响（多设备互不干扰）。
     */
internal fun Link.dropSession(s: Session) {
        s.close()
        sessions.remove(s)
        if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
        notifyPeers()
        refreshState()
    }

// ============================================================================
// ===== 隐性调试出口 —— 定稿（GATE-A2 / 任务 4.4）时整块删除，含 adopt 里那一行调用 =====
// ============================================================================
// 开关是编译期常量：日常与发布包恒为 false，不输出任何内容；开发期排查时改成 true。
internal const val BLOCK_DEBUG = false

private val blockDebugHits = java.util.concurrent.atomic.AtomicInteger()

internal fun blockDebugReport(key: String) {
    if (!BLOCK_DEBUG) return
    Log.d(Link.TAG, "设备已标记 $key（累计 ${blockDebugHits.incrementAndGet()} 次）")
}
