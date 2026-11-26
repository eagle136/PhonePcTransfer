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
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import com.phonepctransfer.net.Link.Session

// 连接引擎(拆分自 Link.kt):保活重连、监听/连接循环、读循环、心跳、发送与弃会话

/** 保活重连节奏：断开 → 等 5s/10s/30s 各主动拨一次，仍不通转纯被动 */
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
 * 保活重连状态机(单线程池,链式一次性调度,任一时刻每台设备只有一个在途任务):
 * 阶梯拨号 → 最后一拨后判定:恢复即取消;本端正在传输/通话→不降级,每 30s 继续拨并再判定;否则转纯被动
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
        // 绑定重试:覆盖安装可能让旧进程短暂占住端口,每 3s 重试直到成功或停止;
        // 通配绑定始终失败(如 127.0.0.1:端口被系统长期占用)则退回绑本机局域网 IPv4,协议行为不变
        var bound: ServerSocketChannel? = null
        var attempt = 0
        var fallbackLogged = false
        while (running && bound == null) {
            try {
                val ss = ServerSocketChannel.open()
                ss.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
                ss.bind(InetSocketAddress(prefs.tcpPort))
                bound = ss
            } catch (t: Throwable) {
                attempt++
                val lan = localLanIpv4()
                if (lan != null) {
                    try {
                        val ss = ServerSocketChannel.open()
                        ss.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
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
                runCatching { sk.socket().tcpNoDelay = true }
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
     * 连接工人:只在有人点了设备(或启动自动连接)时才动一次,点一次试一次、失败不重试(不轮询、不频繁发包);
     * 请求是一串同设备候选地址,串行逐个试:某个连上并握手成功→立刻停;全部失败→清空队列,等对端被动连入或用户再点
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
                    // SocketChannel 的 socket 适配器支持带超时的 connect；超时语义与原实现一致
                    val sk = SocketChannel.open()
                    sk.socket().tcpNoDelay = true
                    sk.socket().connect(InetSocketAddress(dst, prefs.tcpPort), 3000)
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

    /** 等这条会话完成握手(hello/ping 任一帧);true = 该地址确实是我们对端 */
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

    /**
     * 启动快速直连（用户要求：启动先连已有设备，超时 1s）：
     * - 只试「这一台设备」的候选地址（同设备其它地址按最近在线排序，见 [groupCandidates]），**总预算 [budgetMs] 共享**；
     * - 每个地址的 TCP 超时 = 剩余预算，TCP 通后等 hello 握手也在预算内（超时即弃、不重试）；
     * - 不走 [pendingConnectIps]（那条队列是 3s 口径的用户点击路径），也不占 connectLoop；
     * - 启动直连由「用户打开应用」触发（等价于前台点击设备），因此不查 [outgoingGate] 的后台未知设备拦截。
     * @return true = 设备已可达（可能是进来前就已连着的会话）；false = 预算内没连上，调用方转扫描
     */
internal fun Link.fastConnect(ip: String, budgetMs: Long): Boolean {
        val v = ip.trim()
        if (v.isEmpty() || !running) return false
        sessionByGroup(v)?.let {
            refreshState()
            return true
        }
        val deadline = System.currentTimeMillis() + budgetMs
        for (dst in groupCandidates(v)) {
            if (!running) return false
            sessionByGroup(dst)?.let { return true }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            var s: Session? = null
            try {
                val sk = SocketChannel.open()
                sk.socket().tcpNoDelay = true
                sk.socket().connect(InetSocketAddress(dst, prefs.tcpPort), left.toInt())
                log("启动直连 $dst:${prefs.tcpPort} TCP 已通，等待握手（剩余 ${deadline - System.currentTimeMillis()}ms）")
                val ns = adopt(sk, outgoing = true)
                s = ns
                val wait = deadline - System.currentTimeMillis()
                if (wait > 0 && waitHandshake(ns, wait)) {
                    log("启动直连 $dst 握手成功（预算 ${budgetMs}ms 内）")
                    if (dst != v) runCatching { onGroupFallback?.invoke(v, dst) }
                    return true
                }
                log("启动直连 $dst 未在预算内握手（非本应用 / 对端无响应），放弃后转扫描")
                dropSession(ns)
            } catch (t: Throwable) {
                log("启动直连 $dst:${prefs.tcpPort} 失败: ${t.message}")
                runCatching { s?.close() }
            }
        }
        return false
    }

internal fun Link.adopt(socket: SocketChannel, outgoing: Boolean, lane: Int = 0): Session {
        val s = Session(socket, outgoing)
        s.lane = lane
        // 网段判定只在这一处：命中 → 不进 sessions、不发 hello、不启读线程，立即 close 放掉 FD。
        // 出向侧由现成的 waitHandshake（见 closed 即 false）→ dropSession 归入「已连通但未握手」老口径，
        // 入向侧 acceptLoop 本来就用不到返回值。不打任何日志。
        val rip = socket.socket()?.inetAddress?.hostAddress ?: ""
        if (BlocklistIpv6.isHighRisk(rip) || prefs.isBlockedAddr(rip)) {
            val key = prefs.convKeyOf(rip)
            prefs.markBlockedGroup(key)
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
        log("接纳${if (outgoing) "主动" else "被动"}连接 ${socket.socket()?.inetAddress?.hostAddress}" +
            (if (lane > 0) "（数据通道 lane=$lane）" else ""))
        // P4-5：对这台设备配对过，就把共享 token 带过去（按 deviceId 优先、IP 兜底），双方都认就跳过 6 位码。
        // 只认 IP 的话，重启/换 WiFi 后 DHCP 换了地址就查空 ⇒ hello 不带 token ⇒ 对端把你当新设备要求重配。
        val helloTok = prefs.pairedTokenFor(s.remoteIp)
        s.pairTokenSent = helloTok
        val ok = sendOrDrop(
            s,
            Protocol.hello(
                myId, myName, Build.MODEL ?: "android", localVidCap, localCamCap, localVCodecs,
                lanes = Link.LANES, lane = lane,
                tok = helloTok
            ),
            "hello"
        )
        if (!ok) return s
        Thread({ readLoop(s) }, if (lane > 0) "pp-read-l$lane" else "pp-read").apply { isDaemon = true }.start()
        return s
    }

    // ==================== 2 期：数据通道（lane）连接池 ====================

    /**
     * 取本次传输可用的通道集合（含主连接）。want 与对端能力取小；某条 lane 建不起来就按实际条数发，
     * **不因此失败**（最差退化为 1 期单通道）。
     */
internal fun Link.lanesFor(main: Session, want: Int): List<Session> {
        val n = minOf(want, main.peerLanes).coerceAtLeast(1)
        val list = ArrayList<Session>(n)
        list.add(main)
        if (n <= 1) return list
        for (l in 1 until n) {
            val exist = synchronized(laneMap) { laneMap[main.remoteIp]?.get(l) }
            if (exist != null && !exist.closed && exist.laneReady) {
                list.add(exist)
                continue
            }
            val ns = runCatching { dialLane(main, l) }.getOrNull() ?: break
            list.add(ns)
        }
        if (list.size < n) log("多通道：只建起 ${list.size}/$n 条通道，按实际条数发送")
        return list
    }

    /** 拨一条数据通道：连上 → adopt(lane) → 等对端 hello 完成挂载；失败返回 null（调用方降级） */
internal fun Link.dialLane(main: Session, lane: Int): Session? {
        val ip = main.remoteIp
        if (ip.isEmpty()) return null
        val sk = SocketChannel.open()
        try {
            sk.socket().tcpNoDelay = true
            sk.socket().connect(InetSocketAddress(ip, prefs.tcpPort), Link.LANE_CONNECT_TIMEOUT_MS)
            val ns = adopt(sk, outgoing = true, lane = lane)
            val t0 = System.currentTimeMillis()
            while (running && !ns.closed && System.currentTimeMillis() - t0 < Link.LANE_HANDSHAKE_WAIT_MS) {
                if (ns.laneReady) {
                    synchronized(laneMap) { laneMap.getOrPut(ip) { HashMap() }[lane] = ns }
                    log("数据通道 lane=$lane 建立 $ip:${prefs.tcpPort}")
                    return ns
                }
                waitFor(50)
            }
            log("数据通道 lane=$lane 握手超时（$ip），降级不用它")
            runCatching { ns.close() }
            return null
        } catch (t: Throwable) {
            log("数据通道 lane=$lane 连接失败（$ip）：${t.message}")
            runCatching { sk.close() }
            return null
        }
    }

    /** 主连接断开时顺手收掉它的所有 lane（避免半开连接堆积） */
internal fun Link.closeLanes(ip: String) {
        val m = synchronized(laneMap) { laneMap.remove(ip) } ?: return
        for ((l, s) in m) {
            runCatching { s.close() }
            sessions.remove(s)
            log("数据通道 lane=$l 随主连接关闭")
        }
    }

internal fun Link.readLoop(s: Session) {
        try {
            while (running && !s.closed) {
                val rfT0 = System.nanoTime()
                // 快路径：解析到分片头就先问「这块落到哪」，能直读进 4MB 轮转缓冲就不分配临时数组
                val f = Protocol.readFrameInto(s.input) { mid, seq, len ->
                    reserveDirectChunk(mid, seq, len)
                }
                TxStats.read(System.nanoTime() - rfT0)
                when (f) {
                    null -> break
                    is Protocol.Frame.Json -> {
                        s.touchRx()
                        handleFrame(s, f.j)
                    }
                    is Protocol.Frame.Chunk -> {
                        s.touchRx()
                        // P4-5 闸门补全：二进制分片同样受配对约束（原来只拦 JSON，
                        // 跳过 hello 直灌分片/音频/视频可绕过配对）；未配对静默丢弃
                        if (s.paired) onChunk(s, f.mid, f.seq, f.data, f.len, f.off, f.direct)
                    }
                    is Protocol.Frame.Audio -> {
                        s.touchRx()
                        if (s.paired) listener?.onCallAudio(s.remoteIp, f.seq, f.ts, f.data, f.len)
                    }
                    is Protocol.Frame.Video -> {
                        // ⚠ 网络读线程：监听方只能入队，绝不能在这里做解码器/camera 初始化
                        //   （本项目踩过两次：读线程被堵 → 对端 35s 判死 + 挂断抢锁死锁）
                        s.touchRx()
                        if (s.paired) listener?.onVideoFrame(s.remoteIp, f.seq, f.ts, f.flag, f.data, f.len)
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
                (if (s.handshaked) "" else " (未握手)") +
                (if (s.lane > 0) " (lane ${s.lane})" else ""))
            // 这一台已经断了：任何还挂着的兜底闹钟先作废，只在「本次确实出向断链」时重新挂
            KeepAliveAlarm.cancel(ctx, s.remoteIp)
            if (s.lane > 0) {
                // 数据通道断开：只摘自己，**不能**中止该对端的接收（主连接还在传）
                synchronized(laneMap) { laneMap[s.remoteIp]?.let { if (it[s.lane] === s) it.remove(s.lane) } }
            } else {
                // 去重收尾保护：同设备已有另一条握手完成且未关闭的会话（本条是被淘汰/半旧的连接）时，
                // 不能按 IP 清扫接收与 lane，否则会把新会话上刚建立的传输误杀
                val newerAlive = sessions.any {
                    it !== s && !it.closed && it.handshaked && it.remoteIp == s.remoteIp
                }
                if (newerAlive) {
                    log("会话结束 ${s.remoteIp}：同 IP 已有新会话，跳过接收/通道清扫")
                } else {
                    abortRxsFor(s.remoteIp)
                    closeLanes(s.remoteIp)
                }
            }
            notifyPeers()
            // 保活重连：本端发起成功过的会话断开后按 5s/10s/30s 阶梯自动重连，
            // 仍不通转纯被动（传输/通话中除外，见 runKeepAliveResolve）。
            // 入向会话不重试 —— 本方向可能被对端防火墙挡，由对端保活。lane 会话不触发保活。
            if (running && s.lane == 0 && s.handshaked && s.outgoing) {
                scheduleKeepAlive(s.remoteIp)
                // 兜底第二道：阶梯跑在普通线程池上，息屏进 Doze 后定时器根本不跑（实测断链 10 分钟无一次重拨）。
                // 这里再挂一串 WAKEUP 闹钟（20s/90s/5min，不持锁），Doze 也可能被系统唤醒拨号；握手成功/停链路即取消
                KeepAliveAlarm.schedule(ctx, s.remoteIp)
            }
            refreshState()
        }
    }

    /**
     * 心跳循环(每 5s 一次):ping + 假连接判定 + 停滞看门狗(每 tick 顺带跑:30s 无进度→停成"已暂停"、半成品保留、重连后自动续传;另回收闲置 10 分钟的未完成目录)
     */
internal fun Link.heartbeatLoop() {
        while (running) {
            waitFor(Link.STALL_SCAN_MS)
            if (!running) return
            // 熄屏保活对齐（每 5s 一次，自愈）：熄屏且有会话就持 CPU/WiFi 锁 + Doze 兜底闹钟，
            // 亮屏或会话全断就释放。放在最前面，避免下面的看门狗把 tick 拖长时锁状态一直不对。
            IdleHeartbeat.onTick()
            // 停滞看门狗（5s 周期）：先收后发，再回收闲置目录；
            // 顺便撤掉 60s 没等到落盘回执的登记（只清表，不改气泡/不出队，见 LinkSend.scanAckTimeout）
            scanStalledRx()
            scanStalledSends()
            scanAckTimeout()
            pruneDenyVotes()     // 票③到期/切到不计费网络自动解除（P1-1）
            pruneDirs()
            for (s in sessions) {
                if (s.closed) continue
                // 写看门狗（P1-3）：Socket 没有写超时，对端不读时 write 会一直阻塞并占着写锁；
                // 关会话让阻塞中的写立即抛 AsynchronousCloseException，写线程自己清标记、上层判断链
                val wAt = s.writeStartedAt
                if (wAt > 0 && System.currentTimeMillis() - wAt > Link.CONTROL_WRITE_TIMEOUT_MS) {
                    log("非文件帧写入超过 ${Link.CONTROL_WRITE_TIMEOUT_MS / 1000}s 未完成（对端不读/半死），关闭会话解阻塞")
                    s.close()
                    continue
                }
                if (!s.handshaked && System.currentTimeMillis() - s.lastRx > 8000) {
                    log("握手超时，关闭会话")
                    s.close()
                    continue
                }
                // P4-5 配对超时：到点还没配好就关会话并提示，避免半开连接一直挂在列表里
                if (s.handshaked && !s.paired && s.pairDeadline > 0 &&
                    System.currentTimeMillis() > s.pairDeadline
                ) {
                    log("配对超时（${s.peerName} ${s.remoteIp}），通知对端关弹窗并关闭会话")
                    // 先告诉对端「这次配对作废」：否则对端会一直停在出示码/输入键盘的界面上，
                    // 自己这边的超时提示关掉了，对端却像卡死一样（两端状态必须一起收）
                    if (!s.closed) {
                        runCatching { sendOrDrop(s, Protocol.pairCancel(), "pair_cancel", quiet = true) }
                    }
                    if (pairUiMatches(s.remoteIp)) {
                        pairUiIp = ""
                        listener?.onPairState(
                            PairUi(
                                s.remoteIp, s.peerName,
                                showCode = s.pairRole == 1, code = "",
                                attemptsLeft = 0, done = true, ok = false,
                                message = "配对超时，请重连后重新配对"
                            )
                        )
                    }
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

    /**
     * 立即给所有已握手会话补一次 ping（后台心跳闹钟/Doze 兜底用）。
     * 走 [io] 队列，绝不在广播（主线程）上写 socket；tryLock 失败说明正在传文件，跳过即可。
     */
    internal fun Link.pokeHeartbeat() {
        for (s in sessions) {
            if (s.closed || !s.handshaked) continue
            io {
                runCatching {
                    if (s.tryPing()) {
                        s.pingOutstanding = true
                    }
                }
            }
        }
    }

    // ==================== 发送（失败即判死） ====================

    /**
     * 统一发送入口:任何异常都视为该会话已失效(立刻关闭、状态按会话集合重算);
     * 只动这一条会话(别的设备连接不受影响),不重连(用户点设备才会再连)
     */
internal fun Link.sendOrDrop(s: Session, j: JSONObject, what: String, quiet: Boolean = false): Boolean {
        // ===== P4-5 发送侧闸门：未配对会话不允许发内容帧（配对帧/心跳在白名单里）；
        // 与接收侧同口径，不以 handshaked 为前提（握手前也拦） =====
        val frameType = j.optString("type", "")
        if (s.lane == 0 && !s.paired && frameType !in Link.PAIRED_ALLOWED_FRAMES) {
            if (!quiet) log("未配对会话 ${s.remoteIp} 试图发送 $frameType，已拦截（请先完成配对）")
            return false
        }
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

    /** 会话失效:关闭并从集合摘除,状态按会话集合重算;只影响这一条会话 */
internal fun Link.dropSession(s: Session) {
        s.close()
        sessions.remove(s)
        if (s.peerId.isNotEmpty()) byPeer.remove(s.peerId, s)
        notifyPeers()
        refreshState()
    }

// 隐性调试出口已按定稿要求整块删除（原 BLOCK_DEBUG / blockDebugReport）
