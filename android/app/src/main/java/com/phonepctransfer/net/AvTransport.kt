package com.phonepctransfer.net

import android.util.Log
import com.phonepctransfer.util.CallClock
import com.phonepctransfer.util.CallCounters
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Random

// 媒体传输模块:与PC端pc/AvTransport.java同名同构,改一处必须同时改两处
// 五档对上层透明,通话中永不变(I17);媒体域与数据域零共享锁(唯一例外INBAND走Link writeLock)
// 安卓差异:非root无法bind<1024⇒443档整体跳过;有视频发送半,PC没有
class AvTransport(private val link: Link, private val sink: Sink) {

    /** 五档(§4.1/D8):声明顺序即定档顺序;全工程只此一处定义档位序列 */
    enum class Tier {
        UDP_443,         // ① UDP 443(安卓非root⇒整体跳过)
        UDP_RANDOM,      // ② UDP 随机 4000–50000
        TCP_443,         // ③ 媒体 TCP 443（安卓同①跳过）
        TCP_RANDOM,      // ④ 媒体 TCP 随机 4000–50000
        INBAND           // ⑤ 消息端口带内（主 TCP，受 D16 通话期文件排他约束）
    }

    // ---- 2.3:端口选择(§4.1/U11①);与PC端同序同值,差异仅在过滤 ----
    companion object {
        private const val TAG = "PP-Av"

        /** 随机档区间，含端点（§4.1：4000–50000）。 */
        const val MEDIA_PORT_MIN = 4000
        const val MEDIA_PORT_MAX = 50000

        /** 避让清单:照§4.1(:345-346)按原序抄17项,双端一字不差,勿精简 */
        val PORT_AVOID = intArrayOf(
            8080, 8000, 8008, 8009, 8888, 3000, 3128, 5000, 5001, 5353,
            5357, 1900, 3389, 7000, 9000, 28765, 28766
        )

        /** 每档随机端口最多实试8次;仍败退系统分配(port0);再败=本档不成立。每种协议各8次 */
        const val BIND_MAX_ATTEMPTS = 8

        /** 是否为<1024的权限档:本端整体跳过(非root无法bind),只用于下面那次过滤 */
        private fun isPrivileged(t: Tier): Boolean = t == Tier.UDP_443 || t == Tier.TCP_443

        /** 本端档序列:五个档滤掉两个443档(非root);从Tier.values()推,别处不存第二份 */
        fun tiersForThisEnd(): List<Tier> = Tier.values().filterNot { isPrivileged(it) }

        /** 本档是否UDP档(false=媒体TCP档);2.5时序按此分两支 */
        fun isUdp(t: Tier): Boolean = t == Tier.UDP_443 || t == Tier.UDP_RANDOM

        // ---- 2.6:一轮=同一端口族UDP与TCP两侧(D11) ----

        /** 一轮=§4.1同端口族的UDP档+媒体TCP档;udpTier恒非null,tcpTier可null */
        private class Round(val udpTier: Tier, val tcpTier: Tier?)

        /** 本端轮序列:每条UDP档配同族TCP档;仍从一份档位序列推;带内不进轮 */
        private fun roundsForThisEnd(): List<Round> {
            val tiers = tiersForThisEnd()
            return tiers.filter { isUdp(it) }.map {
                val fam = if (it == Tier.UDP_443) Tier.TCP_443 else Tier.TCP_RANDOM
                Round(it, if (tiers.contains(fam)) fam else null)
            }
        }

        // ---- 2.5:振铃期探测与定档时序(§4.3);三个数全来自§4.3,不新增 ----

        /** §4.3②：拿到对端本档端口后各发 3 个 probe。 */
        const val PROBE_COUNT = 3

        /** §4.3②：probe 间隔 30ms。 */
        const val PROBE_INTERVAL_MS = 30L

        /** §4.3③:300ms判定窗;兼作①交换三元组的等待窗与④connect/accept超时 */
        const val PROBE_WINDOW_MS = 300L

        // ---- 3.2:视频分片与重组(§4.4推导;AU>1200B会IP分片⇒视频按≤1200B切片;音频/ctrl不分片) ----

        /** §4.4：单报（含 12B 头+ts）≤1200B，避免 IP 分片。 */
        const val DG_MAX_REPORT = 1200

        /** 一份视频分片能装的AU正文上限=1200−12=1188B(§4.4推导,非新增数字) */
        const val DG_SLICE_MAX_PAYLOAD = DG_MAX_REPORT - Protocol.DG_OVERHEAD

        /** 媒体UDP内核收发缓冲1MB:按最坏单帧突发145KB与队列上限594KB反推,留一倍余量 */
        const val UDP_BUFFER_BYTES = 1 shl 20

        /** 重组窗40ms:末片到达起算,超时无新片整帧丢;与PC同值同序 */
        const val REORDER_WINDOW_MS = 40L

        /** 重组缓冲并发帧上限16:机制性上界非延迟口径;命中丢最旧帧并计数 */
        const val REASSEMBLE_MAX_FRAMES = 16

        // ---- 3.3/3.4:单写者节拍(tick 20ms,视频每tick 256片;用户点) ----

        /** pp-av-loop 的发送 tick = 20ms（对齐一个音频包周期）。用户定的，非从码里推。 */
        const val SEND_TICK_MS = 20L

        /** 视频每tick预算256片:覆盖实测最大IDR(202片),一个tick发完 */
        const val VIDEO_SLICES_PER_TICK = 256

        /** 在途片队列顶512:满则丢当前帧不入队并计videoDroppedByGate */
        const val VIDEO_QUEUE_MAX_SLICES = 512

        /** AU切≤1188B的video_slice报:帧内片共用streamSeq;只产字节,写点在3.3 */
        @Throws(IOException::class)
        fun sliceVideoAu(
            streamSeq: Int, flag: Int, au: ByteArray, off: Int, len: Int, ts: Long,
            out: (idx: Int, count: Int, report: ByteArray) -> Unit
        ): Int {
            if (len <= 0) {
                return 0
            }
            val count = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD
            for (i in 0 until count) {
                val so = off + i * DG_SLICE_MAX_PAYLOAD
                val sl = minOf(DG_SLICE_MAX_PAYLOAD, off + len - so)
                val sliceFlag = (flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG)) or
                        (if (i == count - 1) Protocol.DG_FLAG_LAST_SLICE else 0)
                val report = Protocol.encodeDatagram(
                    Protocol.DG_TYPE_VIDEO_SLICE, streamSeq, sliceFlag, i, count, ts, au, so, sl)
                out(i, count, report)
            }
            return count
        }

        // ---- 2.9:通话期存活判定(S23/P10);两个数都来自方案原文 ----

        /** S23:1s无反馈⇒记mediaSuspectedBlocked;不回退/不换端口(I17);兼检查周期 */
        const val FEEDBACK_SUSPECT_MS = 1_000L

        /** P10:通话中途双向10s零包⇒自动挂断+提示;本模块只上报,挂断归状态机(I14) */
        const val NO_STREAM_HANGUP_MS = 10_000L

        /** 为一档bind本端资源:失败静默返null;443权限档与INBAND恒null;仅定档时序调用 */
        fun bindTier(t: Tier): Bound? = when (t) {
            Tier.UDP_443, Tier.TCP_443 -> null
            Tier.UDP_RANDOM -> tryBindRandom(t, true)
            Tier.TCP_RANDOM -> tryBindRandom(t, false)
            Tier.INBAND -> null
        }

        /** 随机档:避让口不占bind名额;8次实试后转系统分配 */
        private fun tryBindRandom(t: Tier, udp: Boolean): Bound? {
            val r = Random()
            var tries = 0
            var picks = 0
            while (tries < BIND_MAX_ATTEMPTS && picks < BIND_MAX_ATTEMPTS * 8) {
                picks++
                val p = MEDIA_PORT_MIN + r.nextInt(MEDIA_PORT_MAX - MEDIA_PORT_MIN + 1)
                if (isAvoided(p)) continue
                tries++
                val b = if (udp) tryBindUdp(t, p, false) else tryBindTcpListener(t, p, false)
                if (b != null) return b
            }
            return if (udp) tryBindUdp(t, 0, true) else tryBindTcpListener(t, 0, true)
        }

        private fun isAvoided(p: Int): Boolean = PORT_AVOID.contains(p)

        /** UDP bind:不带REUSEADDR(防上一通残留抢包);内核缓冲必须在bind前设 */
        private fun tryBindUdp(t: Tier, port: Int, systemAssigned: Boolean): Bound? {
            var u: DatagramSocket? = null
            return try {
                u = DatagramSocket(null as SocketAddress?)
                u.receiveBufferSize = UDP_BUFFER_BYTES
                u.sendBufferSize = UDP_BUFFER_BYTES
                u.bind(InetSocketAddress(port))
                Log.i(TAG, "[媒体 UDP] 口 " + u.localPort + " 缓冲 rcv=" + u.receiveBufferSize
                        + "B snd=" + u.sendBufferSize + "B（请求 " + UDP_BUFFER_BYTES + "B）")
                Bound(t, u.localPort, systemAssigned, u, null)
            } catch (e: IOException) {
                u?.close()
                null
            }
        }

        /** 媒体TCP监听:REUSEADDR(防上一通TIME_WAIT);端口每通新建,只负责占口 */
        private fun tryBindTcpListener(t: Tier, port: Int, systemAssigned: Boolean): Bound? = try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            Bound(t, s.localPort, systemAssigned, null, s)
        } catch (e: IOException) {
            null
        }
    }

    /** 一档bind到的资源:UDP档只有udp,媒体TCP档只有tcpListener;release幂等 */
    class Bound(
        val tier: Tier,
        val port: Int,
        /** true=8次实试都败后系统分配(bind port 0);数字照样广告给对端 */
        val systemAssigned: Boolean,
        val udp: DatagramSocket?,
        val tcpListener: ServerSocket?
    ) {
        /** 幂等关闭:释放已bind的端口/监听;各退出路径都靠这一句 */
        fun release() {
            runCatching { udp?.close() }
            runCatching { tcpListener?.close() }
        }
    }

    /** 一次媒体会话的入参:每通新建不复用(§4.1);归一化口径与PC端Spec构造逐字一致 */
    class Spec(
        peerIp: String?,
        /** 本端随机token:给probe打戳;每端各自生成 */
        localMsid: String?,
        /** 对端token(invite/accept初值,每档call_media覆盖);空=对端未声明(老端) */
        peerMsid: String?,
        /** 对端媒体端口（2.4 协商来；0 = 对端未声明） */
        peerMport: Int,
        /** 对端媒体端口是否 UDP（2.4 协商来；false = 对端未声明） */
        val peerMudp: Boolean,
        /** true=本端主叫:只影响媒体TCP档谁发起连接(主叫拨、被叫只accept) */
        val caller: Boolean
    ) {
        val peerIp: String = peerIp ?: ""
        val localMsid: String = localMsid ?: ""
        val peerMsid: String = peerMsid ?: ""
        val peerMport: Int = peerMport.coerceAtLeast(0)

        companion object {
            /** 2.4前过渡构造:只有对端地址,协商字段全缺省⇒恒落带内 */
            fun inband(peerIp: String): Spec = Spec(peerIp, "", "", 0, false, false)
        }
    }

    /** 控制状态全量快照(D14):字段名照§3-D14-1;测量值不在这里,走100ms反馈 */
    class Ctrl {
        var epoch = 0                 // 本次媒体会话号（跨通话不回绕）
        var seq = 0L                  // 会话内单调递增;旧epoch或seq≤已见最大值直接丢

        // ---- 推流端 owner ----
        var videoLevel = 0
        var videoBrKbps = 0
        var videoFps = 0
        var audioBrKbps = 0
        var aggregated = false
        var camOn = false
        var muted = false
        var sendingPaused = false

        // ---- 收流端 owner ----
        var wantVideo = false
        var needKeyframe = false
        var bg = false
    }

    /** 反馈/测量值(D14-1"不属于ctrl"那组),由100ms反馈填充 */
    class Stats {
        var queueMs = 0L
        var dropPct = 0
        var latMs = 0L
        var renderGapMs = 0L
        var loss = 0
        var jitter = 0L
    }

    /** 上层回调:fromIp为2.1过渡口径;存活两事件只上报,处置归状态机 */
    interface Sink {
        fun onAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int)

        fun onVideo(fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int)

        fun onCtrl(ctrl: Ctrl) {}

        fun onStats(stats: Stats) {}

        /** S23:1s无反馈的上升沿回调一次;text是拼好的一行,UI照显示 */
        fun onMediaSuspectedBlocked(text: String) {}

        /** P10:中途无流达10s⇒上层自动挂断+提示;本模块只送判据结果 */
        fun onMediaNoStream() {}
    }

    @Volatile
    private var spec = Spec.inband("")

    @Volatile
    private var tier = Tier.INBAND

    @Volatile
    private var started = false

    /** 本轮已bind的两侧资源:各一份可null;定档后只留采纳侧,其余全关 */
    @Volatile
    private var boundUdp: Bound? = null

    @Volatile
    private var boundTcp: Bound? = null

    /** 已bind的是第几轮(roundsForThisEnd下标);-1=未持任何轮资源 */
    @Volatile
    private var boundRound = -1

    /** 2.6预建保持空闲的媒体TCP(D11):3.3 writer直接接手;从不交给Link(不进byPeer/不跑hello副作用) */
    @Volatile
    private var mediaTcp: Socket? = null

    // ---- 2.5:对端广告三元组(invite/accept初值,每轮call_media覆盖);按协议分两槽 ----
    @Volatile
    private var peerUdpPort = 0

    @Volatile
    private var peerTcpPort = 0

    @Volatile
    private var peerMsid = ""

    /** 对端自报"当前第几轮"(同轮两份call_media带同一个数);不作连通性判据,只进日志 */
    @Volatile
    private var peerTierOrdinal = -1

    /** 2.7(D10):实际发送目标=最近收包源地址:端口;唯一写入点notePeerSource */
    @Volatile
    private var peerMediaTarget: InetSocketAddress? = null

    /** 定档时序被叫停(挂断/取消/呼叫失败/stop);probe循环每步查它 */
    @Volatile
    private var negotiationStopped = false

    /** ⑥五档全不通⇒落带内(明示降级);2.11 UI与4.8聚合恒关(I13)读它 */
    @Volatile
    private var inbandDowngraded = false

    /** ctrl报的streamSeq:按类型独立计数(S10);16位取模,回绕由3.5在途窗处理 */
    private val ctrlStreamSeq = java.util.concurrent.atomic.AtomicLong()

    /** 3.2切片重组器:每通新建;投放点=avRecvLoop(收到video_slice报) */
    private val videoReassembler = VideoReassembler()

    // ---- 3.3/3.4:媒体面单写者+严格优先级队列(仅UDP档;带内走主TCP writeLock) ----

    /** 音频流seq:按type独立计数(S10);16位取模 */
    private val audioStreamSeq = java.util.concurrent.atomic.AtomicLong()
    /** 视频流seq:每帧领一个号(帧内所有片共用);16位取模 */
    private val videoStreamSeq = java.util.concurrent.atomic.AtomicLong()

    // 投递侧扩展序号(RTP extended seqno口径):16位回绕展开成单调int,下游判据不改
    private var audioSeqExt = 0
    private var audioSeqLastU16 = -1
    private var videoSeqExt = 0
    private var videoSeqLastU16 = -1

    /** 音频：把 16 位 streamSeq 展开成单调 int 后投递。 */
    private fun extendAudioSeq(u16: Int): Int {
        val last = audioSeqLastU16
        if (last >= 0 && u16 < last && last - u16 > 32768) {
            audioSeqExt++
        }
        audioSeqLastU16 = u16
        return (audioSeqExt shl 16) or u16
    }

    /** 视频：同上，按**帧**（重组完成）粒度展开。 */
    private fun extendVideoSeq(u16: Int): Int {
        val last = videoSeqLastU16
        if (last >= 0 && u16 < last && last - u16 > 32768) {
            videoSeqExt++
        }
        videoSeqLastU16 = u16
        return (videoSeqExt shl 16) or u16
    }

    /** 四条优先级队列:写者按audio>ctrl>feedback>video排空(§4.5/I2) */
    private val qAudio = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qCtrl = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qFeedback = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qVideo = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** pp-av-loop(写者)/pp-av-recv(reader);null=未启动或已关;唯启点=selectUdpSide */
    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var recvThread: Thread? = null

    /** answerPeerProbe已回数的probe份数,上限PROBE_COUNT;写读都在pp-av-recv,归零在start */
    @Volatile
    private var probeReplies = 0

    /** 重组完成交消费者的AU:IDR/CONFIG位原样带出(供判关键帧) */
    class PendingAu(val flag: Int, val ts: Long, val data: ByteArray, val len: Int)

    /** 3.2切片重组器:缺片/超窗整帧丢;键=streamSeq,一帧一桶;不碰writeLock(I3) */
    class VideoReassembler {

        private class Frame(val count: Int, val flag: Int, val ts: Long, val firstRxAtMs: Long) {
            val parts = arrayOfNulls<ByteArray>(count)
            val have = BooleanArray(count)
            var got = 0
            /** 最近一片到达时刻:超窗判据用它(非首片);reader单线程域读写 */
            var lastRxAtMs: Long = firstRxAtMs
        }

        private val frames = java.util.concurrent.ConcurrentHashMap<Int, Frame>()

        /** 当前并发重组中的帧数（供缓冲上限判定与 S23 观测）。 */
        fun size(): Int = frames.size

        // T4取证环:记最近被扫掉的半帧seq(容量64),判"缺的片后来没到"
        private val sweptSeqs = IntArray(64)
        private var sweptNext = 0
        private var sweptCount = 0

        private fun rememberSwept(seq: Int) {
            sweptSeqs[sweptNext] = seq
            sweptNext = (sweptNext + 1) % sweptSeqs.size
            if (sweptCount < sweptSeqs.size) {
                sweptCount++
            }
        }

        /** 该seq最近被扫掉过吗(窗后迟到片):按片计一次,不做命中即忘 */
        private fun isSweptRecently(seq: Int): Boolean {
            for (i in 0 until sweptCount) {
                if (sweptSeqs[i] == seq) {
                    return true
                }
            }
            return false
        }

        /** 丢帧统一出口(与PC同口径):取证计数+迟到环+日志;仅reader线程调 */
        private fun reportLost(seq: Int, f: Frame, why: String) {
            val missing = f.count - f.got
            val key = (f.flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG)) != 0
            CallCounters.videoReassembleFail.inc()
            // 取证三条:半帧片数+是否参考链起点;与半帧个数并读分诊
            CallCounters.videoSliceMissing.add(missing.toLong())
            if (key) {
                CallCounters.videoKeyframeLost.inc()
            }
            rememberSwept(seq)
            // 逐事件一行:正常通话不出现;A方案后判死看末片至今
            val nowMs = System.currentTimeMillis()
            Log.w(TAG, "[视频重组] 半帧$why 缺 $missing/${f.count} 片" +
                    (if (key) "（关键帧）" else "") + " 到手 ${f.got} 片" +
                    " 末片至今 ${nowMs - f.lastRxAtMs}ms 首片至今 ${nowMs - f.firstRxAtMs}ms" +
                    " seq=$seq")
        }

        /** 投一片:非null=刚凑齐的整AU;seq在迟到环则videoSliceLate+1 */
        fun add(dg: Protocol.Datagram, nowMs: Long): PendingAu? {
            val seq = dg.streamSeq
            val count = dg.sliceCount
            val idx = dg.sliceIdx
            val f = frames.computeIfAbsent(seq) { Frame(count, dg.flags, dg.ts, nowMs) }
            // 同帧count必须一致:不符=串帧(老seq复用),丢这一片
            if (f.count != count) {
                return null
            }
            // T4:seq在迟到环⇒窗后迟到片,按片计一次;常态零成本
            if (isSweptRecently(seq)) {
                CallCounters.videoSliceLate.inc()
            }
            synchronized(f) {
                if (f.have[idx]) {
                    return null   // 去重：同片重复到达直接忽略（不计失败）
                }
                val part = dg.payload.copyOf(dg.len)
                f.parts[idx] = part
                f.have[idx] = true
                f.got++
                // A方案:每来一片把超窗基准往后推(判据见sweepTimeoutMs)
                f.lastRxAtMs = nowMs
                if (f.got < f.count) {
                    return null   // 尚未凑齐
                }
                var total = 0
                for (i in 0 until f.count) {
                    total += f.parts[i]!!.size
                }
                val au = ByteArray(total)
                var o = 0
                for (i in 0 until f.count) {
                    System.arraycopy(f.parts[i]!!, 0, au, o, f.parts[i]!!.size)
                    o += f.parts[i]!!.size
                }
                frames.remove(seq)
                // 交付flag只留与带内帧制同义的IDR/CONFIG两位(bit2是报专有LAST_SLICE)
                return PendingAu(
                    f.flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG),
                    f.ts, au, total)
            }
        }

        /** 超龄整帧丢:末片起算超40ms无新片且未凑齐⇒删并计数;reader每批调 */
        fun sweepTimeoutMs(nowMs: Long) {
            for ((key, f) in frames) {
                if (nowMs - f.lastRxAtMs > REORDER_WINDOW_MS) {
                    val swept = frames.remove(key)
                    if (swept != null) {
                        reportLost(key, swept, "超时")
                    }
                }
            }
        }

        /** 缓冲上限:超16帧丢最旧非关键帧并计数;全关键帧则本轮不丢 */
        fun evictOverflow() {
            while (frames.size > REASSEMBLE_MAX_FRAMES) {
                val victim = oldestEvictable() ?: return   // 全是 CONFIG/IDR ⇒ 不丢
                val removed = frames.remove(victim)
                if (removed != null) {
                    reportLost(victim, removed, "超上限")
                }
            }
        }

        private fun oldestEvictable(): Int? {
            var best: Int? = null
            var bestAt = Long.MAX_VALUE
            for ((key, f) in frames) {
                if ((f.flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG)) != 0) {
                    continue   // 关键帧 / 参数集不参与丢弃
                }
                if (f.firstRxAtMs < bestAt) {
                    bestAt = f.firstRxAtMs
                    best = key
                }
            }
            return best
        }

        /** 会话结束/定档切换时清空缓冲(幂等);残留半帧按失败计 */
        fun clear() {
            val n = frames.size
            frames.clear()
            // 迟到环随本通作废:不用上一通的seq判迟到
            sweptNext = 0
            sweptCount = 0
            repeat(n) { CallCounters.videoReassembleFail.inc() }
            // 不走reportLost:挂断残留非通话期丢片证据,会把验收数字污染成恒非零
        }
    }

    // ---- 2.9:S23那行日志取自下面五个量(缺一项就不达标) ----

    /** 本端媒体面已发包数;3.3前只带内一条写者,反馈通道未起时判定不跑 */
    @Volatile
    private var localSentPackets = 0L

    /** 本端收包计数(媒体面包:对端probe,及3.4 reader) */
    @Volatile
    private var localRcvPackets = 0L

    /** 收到的反馈数;3.7前无生产者,恒0 */
    @Volatile
    private var feedbackRcvPackets = 0L

    /** 对端最后一包到达时刻(epoch ms);0=本通还没收到过任何媒体包 */
    @Volatile
    private var peerLastRxAtMs = 0L

    /** 最后收到反馈的时刻(epoch ms);暂无生产者 */
    @Volatile
    private var lastFeedbackAtMs = 0L

    /** 2.9存活检查线程(pp-av-alive);唯启点=定档采纳成四档且先过hasFeedbackChannel */
    @Volatile
    private var aliveThread: Thread? = null

    /** 疑似被拦是否已就本事件上报过(上升沿只报一次;收反馈后复位) */
    @Volatile
    private var suspectedBlockedLatched = false

    /** 双向都静默的起点(epoch ms);0=当前不是无流状态;P10的10s从它算起 */
    @Volatile
    private var bothQuietSinceMs = 0L

    /** 2.10/P6注入点:媒体入向的"通话中+已知设备+黑名单"三半;只影响被叫侧TCP accept */
    @Volatile
    var mediaInboundAdmit: ((remoteIp: String) -> Boolean)? = null

    /** 建媒体会话:登记spec并立刻bind第0档两侧(端口要早于协商报文);定档由negotiate跑完 */
    fun start(spec: Spec?) {
        val s = spec ?: Spec.inband("")
        this.spec = s
        this.tier = Tier.INBAND
        this.started = true
        // 三元组进模块(probe定目标与校验msid);invite/accept只带一份,按mudp落对应侧槽
        this.peerUdpPort = if (s.peerMudp) s.peerMport else 0
        this.peerTcpPort = if (s.peerMudp) 0 else s.peerMport
        this.peerMsid = s.peerMsid
        this.peerTierOrdinal = -1
        // 2.7:发送目标随每通重新学(端口本就每通新建不复用,§4.1)
        this.peerMediaTarget = null
        this.negotiationStopped = false
        this.inbandDowngraded = false
        // 2.9:五个计数+两条闩锁随每通归零(跨通累积会把S23判成上一通的数)
        this.localSentPackets = 0L
        this.localRcvPackets = 0L
        this.feedbackRcvPackets = 0L
        this.peerLastRxAtMs = 0L
        this.lastFeedbackAtMs = 0L
        this.suspectedBlockedLatched = false
        this.bothQuietSinceMs = 0L
        this.probeReplies = 0
        // 3.4:投递侧扩展序号随每通重新起算(流内16位序号按通独立)
        this.audioSeqExt = 0
        this.audioSeqLastU16 = -1
        this.videoSeqExt = 0
        this.videoSeqLastU16 = -1
        this.audioStreamSeq.set(0L)
        this.videoStreamSeq.set(0L)
        val r = roundsForThisEnd()
        if (r.isNotEmpty()) {
            bindRound(r[0])
            this.boundRound = 0
        }
    }

    /** ①:bind一轮的两侧,每侧各自走bindTier(8次实试+系统分配退路);失败静默不打日志 */
    private fun bindRound(r: Round) {
        this.boundUdp = bindTier(r.udpTier)
        this.boundTcp = if (r.tcpTier == null) null else bindTier(r.tcpTier)
    }

    /** 本端已bind、等着随协商报文带出去的媒体端口;0=两侧都不成立/走带内 */
    fun mediaPort(): Int = advertisedSide()?.port ?: 0

    /** mediaPort那个端口是否UDP(须与mediaPort同一时刻读) */
    fun mediaIsUdp(): Boolean {
        val b = advertisedSide() ?: return false
        return isUdp(b.tier)
    }

    /** invite/accept那份三元组带哪侧:声明序UDP侧优先,UDP不成立才退TCP侧 */
    private fun advertisedSide(): Bound? = boundUdp ?: boundTcp

    /** 本端本次通话token:CallManager写进invite/accept/call_media的msid */
    fun localMsid(): String = spec.localMsid

    /** 收到对端媒体三元组:call_invite/call_accept与call_media归一个入口;一轮两份互不覆盖 */
    fun offerPeerTriple(mport: Int, mudp: Boolean, msid: String?, mtier: Int) {
        val p = mport.coerceAtLeast(0)
        if (mudp) {
            this.peerUdpPort = p
        } else {
            this.peerTcpPort = p
        }
        this.peerMsid = msid ?: ""
        this.peerTierOrdinal = mtier
    }

    /** 振铃期定档时序(§4.3七步):按窗口阻塞,只许pp-av-negotiate线程调;只回结论 */
    fun negotiate(): Boolean {
        val s = spec
        if (!started || negotiationStopped || s.peerIp.isEmpty()) {
            releaseBound()
            return false
        }
        if (!s.caller && peerMsid.isEmpty()) {
            // 被叫侧invite是"对端声明了没有"的权威证据:空=老端⇒直接落带内(老端只要求不断链)
            return settleInband("主叫未声明媒体三元组（老端）⇒ 按缺省回退落带内")
        }
        val rounds = roundsForThisEnd()
        for (i in rounds.indices) {
            if (negotiationStopped) {
                releaseBound()
                return false
            }
            val r = rounds[i]
            if (boundRound != i) {
                // ⑤:上一轮失败时已整轮释放,这里给新一轮bind两侧;第0轮已bind跳过
                releaseBound()
                bindRound(r)
                boundRound = i
            }
            // 与stop()的竞态:必须"先写bound再查标志",否则振铃期挂断时端口没人释放
            if (negotiationStopped) {
                releaseBound()
                return false
            }
            val u = boundUdp
            val tc = boundTcp
            if (u == null && tc == null) {
                // §4.1:bind失败静默跳下一档,不报错不挂断
                logTier(r, i, "本档两侧 bind 都不成立 ⇒ 静默进下一档")
                continue
            }
            advertise(i)
            // ②③:本档UDP侧;对端没广告端口时probe不发(peerReadyFor),窗口自然走空
            if (u != null && probeUdpRound(u)) {
                if (negotiationStopped) {
                    releaseBound()
                    return false
                }
                logTier(r, i, "定档 ${u.tier}（②③ 的窗口内收到对端 probe；" +
                        "同档未选中的媒体 TCP 侧立即关，2.6）")
                selectUdpSide(u.tier)
                return true
            }
            // ④:同档媒体TCP侧;端口①已bind好,不回①(D11/S11)
            if (tc != null && probeMediaTcpRound(tc)) {
                if (negotiationStopped) {
                    // 预建那条也在releaseBound范围里:窗口刚握好被叫停⇒不留连接
                    releaseBound()
                    return false
                }
                logTier(r, i, "定档 ${tc.tier}（④ 握成的连接留在 mediaTcp 空闲保持，D11；" +
                        "同档未选中的 UDP 侧与 TCP 监听立即关）")
                selectTcpSide(tc.tier)
                return true
            }
            logTier(r, i, "本档两侧都不通（②③ 窗口内没收到对端 probe，④ 也没握起连接）" +
                    "⇒ ⑤ 换下一档端口，回到 ①")
            releaseBound()
        }
        if (negotiationStopped) {
            releaseBound()
            return false
        }
        return settleInband("五档全不通")
    }

    /** 定档采纳UDP侧:同档TCP监听立即关(不留半开连接);UDP的Bound继续持有供writer */
    private fun selectUdpSide(t: Tier) {
        tier = t
        val b = boundTcp
        boundTcp = null
        b?.release()
        // 3.3:采纳UDP档⇒起媒体IO(唯一writer),必须在存活检查之前
        startMediaIo()
        // 2.9:采纳成四档之一⇒有反馈通道,存活判定从这里起(带内档不到这里)
        startAliveWatchdog()
    }

    /** 定档采纳媒体TCP侧:UDP侧与TCP监听立即关;只保留④握成那条(在mediaTcp) */
    private fun selectTcpSide(t: Tier) {
        tier = t
        val u = boundUdp
        boundUdp = null
        u?.release()
        val b = boundTcp
        boundTcp = null
        b?.release()
        // 本轮只落 UDP 侧自有 IO：TCP 档的媒体 IO 尚未实现，媒体实际走主连接带内。
        // S29：如实标降级（UI 要提示）并释放 ④ 预建的那条 mediaTcp —— 留着既占一条连接又容易
        // 被误判成"TCP 档生效"
        inbandDowngraded = true
        val mt = mediaTcp
        mediaTcp = null
        runCatching { mt?.close() }
        Log.i(TAG, "[媒体定档] TCP 档 $t 的媒体 IO 未实现，媒体回落带内并已释放预建连接（UI 显示降级）")
        startAliveWatchdog()
    }

    // ---- 3.3/3.4:媒体IO(UDP档的自有writer+reader) ----

    /** 起UDP媒体IO两线程:唯调点=selectUdpSide,幂等;只碰媒体socket绝不碰Link writeLock(I3) */
    private fun startMediaIo() {
        if (writerThread != null || recvThread != null || !started) {
            return
        }
        val w = Thread({ avWriteLoop() }, "pp-av-loop").apply { isDaemon = true }
        writerThread = w
        w.start()
        val r = Thread({ avRecvLoop() }, "pp-av-recv").apply { isDaemon = true }
        recvThread = r
        r.start()
    }

    /** 关UDP媒体IO:先摘引用(回落带内)再interrupt+清四队列;socket由releaseBound关 */
    private fun stopMediaIo() {
        val w = writerThread
        val r = recvThread
        writerThread = null
        recvThread = null
        qAudio.clear()
        qCtrl.clear()
        qFeedback.clear()
        qVideo.clear()
        w?.interrupt()
        r?.interrupt()
    }

    /** 写者线程:每20ms一个tick,严格优先级排空;唯一媒体写socket处 */
    private fun avWriteLoop() {
        while (started && udpEgressActive()) {
            drainAll(qAudio)
            drainAll(qCtrl)
            drainAll(qFeedback)
            var budget = VIDEO_SLICES_PER_TICK
            while (budget-- > 0) {
                val rep = qVideo.poll() ?: break
                sendUdp(rep)
            }
            try {
                Thread.sleep(SEND_TICK_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun drainAll(q: java.util.concurrent.ConcurrentLinkedQueue<ByteArray>) {
        while (true) {
            val rep = q.poll() ?: return
            sendUdp(rep)
        }
    }

    /** 把已编码数据报发到对端媒体目标(mediaTarget以实测收包源优先);失败静默,成功计localSentPackets */
    private fun sendUdp(report: ByteArray) {
        val u = boundUdp?.udp ?: return
        if (u.isClosed) {
            return
        }
        try {
            u.send(java.net.DatagramPacket(report, report.size, mediaTarget(peerUdpPort)))
            localSentPackets++
        } catch (e: IOException) {
            // 静默
        } catch (e: RuntimeException) {
            // 静默
        }
    }

    /** reader线程:收boundUdp数据报按类型分发;收到任何媒体包都notePeerSource */
    private fun avRecvLoop() {
        val rx = ByteArray(DG_MAX_REPORT)
        val p = java.net.DatagramPacket(rx, rx.size)
        while (started && udpEgressActive()) {
            val u = boundUdp?.udp ?: return
            if (u.isClosed) {
                return
            }
            try {
                u.soTimeout = 200
                p.length = rx.size
                u.receive(p)
            } catch (e: SocketTimeoutException) {
                videoReassembler.sweepTimeoutMs(System.currentTimeMillis())
                continue
            } catch (e: IOException) {
                return
            } catch (e: RuntimeException) {
                return
            }
            val now = System.currentTimeMillis()
            // S28：只接受通话对端的源地址——定档后不再让任意主机的"合法格式"媒体包进来播放/改目标
            val srcIp = (p.socketAddress as? InetSocketAddress)?.address?.hostAddress
            if (spec.peerIp.isNotEmpty() && !sameHost(srcIp, spec.peerIp)) {
                continue
            }
            notePeerSource(p.socketAddress)
            notePeerMediaPacket()
            val dg = try {
                Protocol.decodeDatagram(rx, 0, p.length)
            } catch (e: IOException) {
                continue
            }
            val peer = spec.peerIp
            when (dg.type) {
                Protocol.DG_TYPE_AUDIO ->
                    sink.onAudio(peer, extendAudioSeq(dg.streamSeq), dg.ts, dg.payload, dg.len)
                Protocol.DG_TYPE_VIDEO_SLICE -> {
                    videoReassembler.sweepTimeoutMs(now)
                    val au = videoReassembler.add(dg, now)
                    // 缓冲上限在此生效:超上限丢最旧非关键帧并计数
                    videoReassembler.evictOverflow()
                    if (au != null) {
                        sink.onVideo(peer, extendVideoSeq(dg.streamSeq), au.ts, au.flag, au.data, au.len)
                    }
                }
                else -> {
                    // ctrl/feedback本轮不在此消费(带内走Link,反馈归3.6/3.7);例外是对端probe
                    if (dg.type == Protocol.DG_TYPE_CTRL && isPeerProbe(rx, p.length)) {
                        answerPeerProbe()
                    }
                }
            }
        }
    }

    /** ①交换:一轮两份call_media(mudp=true/false);主叫第0轮UDP份不重发(已随invite出去) */
    private fun advertise(i: Int) {
        val msid = spec.localMsid
        val peer = spec.peerIp
        if (!(spec.caller && i == 0)) {
            val u = boundUdp
            link.sendCallMedia(peer, u?.port ?: 0, true, msid, i)
        }
        val tc = boundTcp
        link.sendCallMedia(peer, tc?.port ?: 0, false, msid, i)
    }

    /** ②③:本档UDP双向连通性测试:300ms窗内等广告+发3probe+收probe校msid;只定路不定目标 */
    private fun probeUdpRound(b: Bound): Boolean {
        val u = b.udp ?: return false
        if (u.isClosed) return false
        val t0 = System.currentTimeMillis()
        var sent = 0
        var nextProbeAt = t0
        val rx = ByteArray(Protocol.DG_CTRL_MAX_REPORT)
        val p = DatagramPacket(rx, rx.size)
        while (started && !negotiationStopped) {
            val left = PROBE_WINDOW_MS - (System.currentTimeMillis() - t0)
            if (left <= 0) {
                return false
            }
            if (sent < PROBE_COUNT && System.currentTimeMillis() >= nextProbeAt && peerReadyFor(true)) {
                sendProbe(b)
                sent++
                nextProbeAt = System.currentTimeMillis() + PROBE_INTERVAL_MS
            }
            try {
                // 等待上限=探针间隔(非整窗剩余):否则probe2/3发不出,两端恒落第五档
                u.setSoTimeout(minOf(left, PROBE_INTERVAL_MS).toInt())
                p.setLength(rx.size)
                u.receive(p)
            } catch (e: SocketTimeoutException) {
                continue   // 窗口还没走完，回循环头重算 left
            } catch (e: IOException) {
                return false   // socket 被 stop() 关掉 / 收包异常：本档按不通处理，不上抛
            } catch (e: RuntimeException) {
                return false
            }
            if (isPeerProbe(rx, p.length)) {
                // 2.7:唯一的媒体面收包点⇒发送目标只在这里按实测源更新
                notePeerSource(p.socketAddress)
                notePeerMediaPacket()   // 2.9:同一收包点,计数与最后包时刻也在这里进
                return true
            }
        }
        return false
    }

    /** ②:probe ctrl报字节由3.1 codec产生(不许自己拼);发送失败不判死,由窗口定 */
    private fun sendProbe(b: Bound) {
        val u = b.udp ?: return
        if (u.isClosed) return
        val report = encodeProbe() ?: return
        try {
            u.send(DatagramPacket(report, report.size, mediaTarget(peerUdpPort)))
        } catch (e: IOException) {
            // 静默:端口已关/对端不可达都属本档不通,交给窗口判定
        } catch (e: RuntimeException) {
            // 同上
        }
    }

    /** probe ctrl报字节:sendProbe与reader回数共用同一份编码,不许有第二套 */
    private fun encodeProbe(): ByteArray? {
        val body = spec.localMsid.toByteArray(Charsets.UTF_8)
        return try {
            Protocol.encodeCtrl(
                Protocol.CT_PROBE,
                (ctrlStreamSeq.getAndIncrement() and 0xFFFFL).toInt(),
                CallClock.elapsedMs(), body, 0, body.size
            )
        } catch (e: IOException) {
            null   // msid超ctrl报上限:token恒36字符UUID,走不到这里
        }
    }

    /** 定UDP档后reader收到对端probe⇒回数一份(只入qCtrl);防两端窗口独立起算的劈裂态 */
    private fun answerPeerProbe() {
        if (probeReplies >= PROBE_COUNT) {
            return
        }
        val rep = encodeProbe() ?: return
        probeReplies++
        qCtrl.add(rep)
    }

    /** 2.7:媒体面发送目标的唯一写入点;只许在"收到对端媒体包"处调;源变才打日志 */
    private fun notePeerSource(from: SocketAddress?) {
        val a = from as? InetSocketAddress ?: return
        val addr = a.address ?: return
        // S28：源 IP 必须等于通话对端——UDP 源地址可伪造，不能凭一个包就把后续媒体目标改写过去
        val expect = spec.peerIp
        if (expect.isNotEmpty() && !sameHost(addr.hostAddress, expect)) {
            Log.w(TAG, "[媒体目标] 拒绝非对端源 ${addr.hostAddress}:${a.port}（期望 $expect），忽略该包")
            return
        }
        val old = peerMediaTarget
        if (old != null && old == a) {
            return   // 源没变 ⇒ 不写、不打日志
        }
        peerMediaTarget = a
        Log.i(TAG, "[媒体目标] 以实测收包源为准：" +
                (if (old == null) "首次 " else "$old → ") +
                addr.hostAddress + ":" + a.port +
                "（invite/accept 里的 IP 只是初值，不是唯一目标 —— D10 / 2.7）")
    }

    /** 两个地址是不是同一台主机（同字符串优先，IPv4/IPv6 文本差异走 InetAddress 比较） */
    private fun sameHost(a: String?, b: String): Boolean {
        if (a.isNullOrEmpty()) return false
        if (a == b) return true
        return runCatching { InetAddress.getByName(a) == InetAddress.getByName(b) }.getOrDefault(false)
    }

    /** 2.7:该往哪打:有实测收包源用它;没有退回初值(invite/accept的IP+对端广告端口) */
    private fun mediaTarget(fallbackPort: Int): InetSocketAddress {
        val t = peerMediaTarget
        if (t != null) {
            return t
        }
        return InetSocketAddress(spec.peerIp, fallbackPort)
    }

    /** 2.7:媒体TCP主叫拨号拨哪个IP:实测收包源IP优先;端口不跟实测源(那是UDP侧的) */
    private fun mediaTargetIp(): String {
        val t = peerMediaTarget
        return t?.address?.hostAddress ?: spec.peerIp
    }

    /** 这份报是不是本通对端的probe(类型+子类型+msid三项都对才算) */
    private fun isPeerProbe(buf: ByteArray, len: Int): Boolean {
        val want = peerMsid
        if (want.isEmpty()) {
            return false   // 还没拿到对端token⇒无从校验,一律不算通
        }
        return try {
            val dg = Protocol.decodeDatagram(buf, 0, len)
            if (dg.type != Protocol.DG_TYPE_CTRL || dg.ctrlSubtype() != Protocol.CT_PROBE) {
                false
            } else {
                val got = String(dg.payload, 1, dg.len - 1, Charsets.UTF_8)
                want == got
            }
        } catch (malformed: IOException) {
            false   // 3.1口径:畸形报直接丢这一份
        }
    }

    /** ④:本档媒体TCP连通性尝试(与②③同轮):测通预建保持空闲(D11);主叫拨、被叫接 */
    private fun probeMediaTcpRound(b: Bound): Boolean {
        val ls = b.tcpListener ?: return false
        if (ls.isClosed) return false
        if (spec.caller) {
            if (!peerReadyFor(false)) {
                return false
            }
            val so = Socket()
            return try {
                // 连接超时=本档窗口;拨的IP用mediaTargetIp()(实测源优先),端口是对端广告的TCP侧
                so.connect(InetSocketAddress(mediaTargetIp(), peerTcpPort), PROBE_WINDOW_MS.toInt())
                mediaTcp = so   // D11：预建保持，3.3 的 writer 接手
                true
            } catch (e: IOException) {
                // 拒连/超时=本档不通,静默换下一档;没用的连接就地关不留半开
                runCatching { so.close() }
                false
            }
        }
        return try {
            ls.setSoTimeout(PROBE_WINDOW_MS.toInt())
            val so = ls.accept()
            // 2.10(P6/S14):被叫侧accept按三分句拒,防同网段主机串话
            val rip = so.inetAddress?.hostAddress ?: ""
            if (!admitMediaInbound(rip)) {
                Log.i(TAG, "[媒体准入] 被叫侧拒收一条媒体 TCP 入向：$rip" +
                        "（通话中 / 已知设备 / msid 三条件不成立，P6 / S14）⇒ 不采纳，本档媒体 TCP 按不通处理")
                runCatching { so.close() }
                false
            } else {
                mediaTcp = so   // 被叫侧只收一条:窗口内通过准入的那条就是预建
                true
            }
        } catch (e: IOException) {
            false   // 窗口内没人连进来/监听已被stop()关掉
        }
    }

    /** 媒体入向准入判据(2.10/P6/S14):只用于被叫侧accept;通话中+已知设备+黑名单+msid与关系 */
    private fun admitMediaInbound(remoteIp: String): Boolean {
        if (remoteIp.isEmpty() || peerMsid.isEmpty()) {
            return false   // ③
        }
        val admit = mediaInboundAdmit ?: return false   // 未注入 ⇒ 拒绝
        return admit(remoteIp)   // ①②②b（本机的表）
    }

    /** 对端本档这一侧端口能不能用来探(token已到+端口有效);两槽独立互不冲 */
    private fun peerReadyFor(wantUdp: Boolean): Boolean =
        peerMsid.isNotEmpty() && (if (wantUdp) peerUdpPort > 0 else peerTcpPort > 0)

    /** ⑥⑦:五档全不通⇒落带内并明示降级;主TCP已无会话⇒⑦呼叫失败 */
    private fun settleInband(why: String): Boolean {
        // 带内档复用主TCP⇒不留已bind媒体端口,也不留④预建连接(releaseBound是唯一出口)
        releaseBound()
        if (!link.isConnectedIp(spec.peerIp)) {
            // ⑦:带内也不成立⇒呼叫失败,不进TALKING
            Log.i(TAG, "[媒体定档] $why，且主 TCP 会话已不在 ⇒ ⑦ 呼叫失败，不进 TALKING")
            return false
        }
        tier = Tier.INBAND
        inbandDowngraded = true
        // ⑥:落带内⇒UI降级提示+文件排他(D16)+聚合恒关(I13);消费者各归各单
        Log.i(TAG, "[媒体定档] $why ⇒ ⑥ 落消息端口带内（第五档）。降级提示：本次通话期间文件不发送、" +
                "网络外排队（D16）；聚合恒关（I13）")
        return true
    }

    /** 每轮一行结果:不出现"每次bind失败一行"的刷屏;一行带两侧端口 */
    private fun logTier(r: Round, i: Int, result: String) {
        val u = boundUdp
        val tc = boundTcp
        Log.i(TAG, "[媒体定档] 第${i + 1}轮 ${r.udpTier} / ${r.tcpTier}" +
                "（本端 UDP ${u?.port ?: 0}、媒体 TCP ${tc?.port ?: 0}）→ $result")
    }

    /** ⑥:本次通话落到带内档(降级);上层只读,2.11 UI与4.8聚合恒关读它 */
    fun mediaDowngradedToInband(): Boolean = inbandDowngraded

    /** 本通媒体实际承载是否仍主TCP带内:判"帧实际走带内写者"而非tier;D16/聚合恒关必须读本方法 */
    fun mediaUsesMainTcp(): Boolean = !udpEgressActive()

    /** UDP档媒体IO是否就绪(帧真能从boundUdp.udp发出);不硬编码档位,带内/TCP档/定档前一律false */
    private fun udpEgressActive(): Boolean {
        if (!started) {
            return false
        }
        val u = boundUdp?.udp ?: return false
        return !u.isClosed && writerThread != null
    }

    /** 本次通话允不允许聚合包(I13硬闸):读mediaUsesMainTcp而非tier;3.3前恒false */
    fun aggregationAllowed(): Boolean = !mediaUsesMainTcp()

    /** 2.9前置闸门(S28①):本档有没有100ms反馈通道;判据=帧实际走四档,非"档已定成四档" */
    fun hasFeedbackChannel(): Boolean = !mediaUsesMainTcp() && tier != Tier.INBAND

    /** 2.9/S23:反馈帧唯一计入口;3.7前零调用者,3.7落地时必须调这里,不许另立第二个计数 */
    fun noteFeedback() {
        feedbackRcvPackets++
        lastFeedbackAtMs = System.currentTimeMillis()
    }

    /** 2.7收包计入口(与notePeerSource成对):只计对端媒体面包,喂S23与P10 */
    private fun notePeerMediaPacket() {
        localRcvPackets++
        peerLastRxAtMs = System.currentTimeMillis()
    }

    /** 2.9:起存活检查线程;只在定档采纳成有反馈通道的四档时调;绝不换档/换端口/重开探测(I17) */
    private fun startAliveWatchdog() {
        if (aliveThread != null || !started || !hasFeedbackChannel()) {
            return
        }
        val t = Thread({ aliveLoop() }, "pp-av-alive")
        t.isDaemon = true
        aliveThread = t
        t.start()
    }

    /** aliveWatchdog线程体:周期=S23的1s,不另设数字 */
    private fun aliveLoop() {
        var prevSent = localSentPackets
        var prevRcv = localRcvPackets
        var prevFb = feedbackRcvPackets
        while (started && hasFeedbackChannel()) {
            try {
                Thread.sleep(FEEDBACK_SUSPECT_MS)
            } catch (e: InterruptedException) {
                return   // stop() 叫停 ⇒ 确定性退出（不靠"下一次循环发现 started=false"）
            }
            if (!started) {
                return
            }
            val sent = localSentPackets
            val rcv = localRcvPackets
            val fb = feedbackRcvPackets
            val sentInWindow = sent != prevSent
            val rcvInWindow = rcv != prevRcv
            val fbInWindow = fb != prevFb
            prevSent = sent
            prevRcv = rcv
            prevFb = fb
            // S23:本端已发+本1s零反馈⇒疑似中途被拦(只提示不切换)
            // 前提fb>0:判"反馈在流突然停了";3.7前恒0条反馈,不加会每条UDP档通话误报
            if (sent > 0L && fb > 0L && !fbInWindow) {
                if (!suspectedBlockedLatched) {
                    suspectedBlockedLatched = true
                    CallCounters.mediaSuspectedBlocked.inc()
                    val line = "[mediaSuspectedBlocked] 本档=$tier" +
                            " / 本端已发 $sent 包 / 本 1s 收到 0 条反馈" +
                            "（累计 $fb）/ 对端最后一包到达 " +
                            (if (peerLastRxAtMs == 0L) "无（本通没收到过媒体包）"
                            else "$peerLastRxAtMs（${System.currentTimeMillis() - peerLastRxAtMs}ms 前）") +
                            " / 本端收包计数 $rcv" +
                            " ⇒ 疑似中途被拦（S23）：不回退、不换端口、不重开探测（I17）"
                    Log.i(TAG, line)
                    sink.onMediaSuspectedBlocked(line)
                }
            } else if (fbInWindow) {
                suspectedBlockedLatched = false   // 收到过反馈 ⇒ 闩锁复位，下次才还能报
            }
            // P10:双向0包达10s⇒上报"无流",挂断由状态机做
            if (sentInWindow || rcvInWindow) {
                bothQuietSinceMs = 0L
            } else {
                val now = System.currentTimeMillis()
                if (bothQuietSinceMs == 0L) {
                    bothQuietSinceMs = now
                } else if (now - bothQuietSinceMs >= NO_STREAM_HANGUP_MS) {
                    bothQuietSinceMs = 0L   // 一次事件只报一次；报完状态机就挂断了
                    sink.onMediaNoStream()
                }
            }
        }
    }

    /** 关存活线程:摘引用+interrupt;没起过(带内档/从未定档)就是空操作 */
    private fun shutdownAliveThread() {
        val t = aliveThread
        aliveThread = null
        t?.interrupt()
    }

    /** 当前档:只给模块内部与日志用;上层不许读它做分支(D3) */
    fun tier(): Tier = tier

    /** 发一帧音频;返回写入耗时ms,-1=未发送;UDP档writer下恒0,不得当拥塞信号 */
    fun sendAudio(seq: Int, ts: Long, data: ByteArray, len: Int): Long {
        val s = spec
        if (!started || len <= 0 || s.peerIp.isEmpty()) return -1L
        if (udpEgressActive()) {
            // 媒体 UDP 档：编一份 audio 数据报入音频队列，真正 socket 写在 pp-av-loop（唯一写点）。
            // seq 用本模块按 type 独立计数的 audioStreamSeq（S10），不是 caller 的 seq。
            return try {
                val sq = (audioStreamSeq.getAndIncrement() and 0xFFFFL).toInt()
                val rep = Protocol.encodeDatagram(
                    Protocol.DG_TYPE_AUDIO, sq, 0, 0, 1, ts, data, 0, len)
                qAudio.add(rep)
                0L
            } catch (e: IOException) {
                -1L
            }
        }
        // 带内写者(INBAND/媒体TCP档):主TCP既有链路,写锁/帧格式/128KB闸门不变(I8/I3例外)
        val took = link.sendAudioTo(s.peerIp, seq, ts, data, len)
        if (took >= 0L) {
            localSentPackets++   // 2.9:S23"已发N包"与P10"本秒有没有发"都读这个数
        }
        return took
    }

    /** 出向音频队列待写帧数(3.3取证,与PC同名同义);带内/TCP档不经过⇒恒0 */
    val pendingAudioDatagrams: Int get() = qAudio.size

    /** 发一帧视频(安卓活跃路径):UDP档按≤1188B切片入队,帧内共用一个videoStreamSeq */
    fun sendVideo(seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int): Long {
        val s = spec
        if (!started || len <= 0 || s.peerIp.isEmpty()) return -1L
        if (udpEgressActive()) {
            val projected = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD
            if (qVideo.size + projected > VIDEO_QUEUE_MAX_SLICES) {
                // 队列硬顶:丢当前帧不入队,且必须计数
                com.phonepctransfer.util.CallCounters.videoDroppedByGate.inc()
                return -1L
            }
            return try {
                val vq = (videoStreamSeq.getAndIncrement() and 0xFFFFL).toInt()
                val n = sliceVideoAu(vq, flag, data, 0, len, ts) { _, _, rep -> qVideo.add(rep) }
                if (n > 0) 0L else -1L
            } catch (e: IOException) {
                -1L
            }
        }
        val took = link.sendVideoTo(s.peerIp, seq, ts, flag, data, len)
        if (took >= 0L) {
            localSentPackets++   // 2.9：同 sendAudio
        }
        return took
    }

    /** 发一份控制快照:载体待接(3.13的14B线格式);之前本方法不做任何事 */
    fun sendCtrl(ctrl: Ctrl) {
        // 3.13：ownerBits / epoch / seq 上线后由这里按当前档发出（媒体报或带内帧）
    }

    /** 收帧入口:只许App接线层调;模块不做源地址过滤,2.7统一收进模块 */
    fun deliverAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {
        if (!started) return
        notePeerMediaPacket()   // 2.9:"从对端收到媒体包"的另一个入口(3.4后是主入口)
        sink.onAudio(fromIp, seq, ts, data, len)
    }

    fun deliverVideo(fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {
        if (!started) return
        notePeerMediaPacket()   // 2.9：同上
        sink.onVideo(fromIp, seq, ts, flag, data, len)
    }

    /** 关会话:释放已bind端口/监听与④预建连接;五条退出路径都汇到本方法一个出口 */
    fun stop() {
        started = false
        // 定档时序可能还在跑:先叫停(循环每步查标志),下面releaseBound放掉已bind端口
        negotiationStopped = true
        spec = Spec.inband("")
        peerUdpPort = 0
        peerTcpPort = 0
        peerMsid = ""
        peerTierOrdinal = -1
        peerMediaTarget = null   // 2.7:实测收包源随本通作废(下一通重新学)
        inbandDowngraded = false
        tier = Tier.INBAND
        // 2.9:五个计数与两条闩锁一并作废(与start归零成对,残值不流到下一通的S23日志)
        localSentPackets = 0L
        localRcvPackets = 0L
        feedbackRcvPackets = 0L
        peerLastRxAtMs = 0L
        lastFeedbackAtMs = 0L
        suspectedBlockedLatched = false
        bothQuietSinceMs = 0L
        mediaInboundAdmit = null   // 2.10:注入的准入判据随本通作废(下一通openTransport重新配)
        // 3.2:残留半帧随本通作废,未完成帧按失败计(与超时/溢出同口径)
        videoReassembler.clear()
        // 3.3:先关UDP媒体IO(摘writer/reader引用⇒回落带内),再清四条队列
        stopMediaIo()
        // 2.9:两条线程都在这里收(negotiate靠标志叫停,alive靠摘引用+interrupt);五路径只有一个出口
        shutdownAliveThread()
        // 2.3+2.6:释放两侧端口与监听及预建连接;先摘引用再关⇒重复调用不二次关闭
        releaseBound()
    }

    /** 叫停在跑的定档时序:只置标志,幂等不阻塞;本方法不关任何东西 */
    fun stopNegotiation() {
        negotiationStopped = true
    }

    /** 释放本端全部媒体资源:UDP侧+TCP监听+预建mediaTcp;唯一出口 */
    private fun releaseBound() {
        val u = boundUdp
        boundUdp = null
        val t = boundTcp
        boundTcp = null
        val so = mediaTcp
        mediaTcp = null
        boundRound = -1
        u?.release()
        t?.release()
        if (so != null) {
            runCatching { so.close() }
        }
    }
}
