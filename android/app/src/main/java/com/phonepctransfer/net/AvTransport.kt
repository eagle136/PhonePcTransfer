package com.phonepctransfer.net

import android.util.Log
import com.phonepctransfer.util.CallClock
import com.phonepctransfer.util.CallCounters
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Random

// ==================================================================
// 媒体传输模块（任务单 2.1 骨架 · 方案 D1/D2/D3/D8）
//
// 与 PC 端 `pc/AvTransport.java` **同名同构**（改一处必须同时改两处）。
//
// 对上只暴露这一套（D3）：
//   start(spec) / sendAudio(...) / sendVideo(...) / sendCtrl(state) / stop()
//   回调 onAudio / onVideo / onCtrl / onStats
// 通话层（voice/CallManager / VoiceEngine / video/VideoCall）**只调这套接口**，
// 底下是五档里的哪一档对上层透明，且**通话中途永不变**（I17）。
//
// 数据报头（8B）、ctrl 的 14B 快照布局、发送窗口、100ms 反馈、超龄丢弃都在**任务单 3**；
// 端口 bind 的"怎么 bind"在 **2.3**（bindTier / Bound / stop() 的释放），
// "什么时候试哪一档"在 **2.5**（振铃期定档时序），媒体 TCP 预建与"未选中即关"在 **2.6**
// —— 2.6 已落：一轮 = 同一端口族的 UDP 与 TCP 两侧（roundsForThisEnd / bindRound），
// UDP 侧不通就在**同一轮**里进 ④，本档没被选中的那条（以及定档后已空转的 TCP 监听）立即关，
// 预建好的那条媒体 TCP 连接留在 mediaTcp 保持空闲（D11），由任务单 3.3 的 writer 直接接手。
// 2.5 已把七步时序接进本类，但**媒体帧的实际承载**仍恒为 INBAND —— 自有 writer 要到任务单 3.3
// 才存在（见 [mediaUsesMainTcp]）⇒ 定档结论今天只进日志、⑥ 的降级标志与后续各单的判断依据，
// 不改变媒体帧走法（行为与 2.1/改前一致）。上层一行不用改（这就是 2.1 的自查项）。
//
// 安卓与 PC 的两处结构性差异（不是口径差异，是后面几条要处理的）：
//   · 安卓非 root 无法 bind <1024 ⇒ 两个 443 档在安卓**整体跳过**（2.3 已落：
//     [tiersForThisEnd] 从 Tier.values() 里滤掉权限档，本端没有 443 的 bind 代码路径）；
//   · 安卓有视频发送半，PC 没有（R2）⇒ 本端的 sendVideo 是活跃路径。
//
// 控制面现状（如实写，别当成已接好）：
//   今天带内的控制帧是 video_cfg / video_req、反馈帧是 video_fb，仍走 Link 既有路径，
//   由 任务单 3.13（ctrl 快照 + owner 判权 + epoch/seq 去重）与 D12（媒体通道起来后
//   停用 2s video_fb、改 100ms 反馈）迁移进来。sendCtrl/onCtrl/onStats 是**声明**，
//   当前无生产者/消费者 —— 与"lat 恒 0"的区别是这里没有任何消费点在读一个假数字，
//   验收门也不读它们（任务单 6 读的是 1.7 的计数器与 1.2 的分段行）。
//
// 线程域（D2 · 任务单 2.2）：
//   pp-av-audio —— 已落：音频解码 + 写播放设备（voice/VoiceEngine.playLoop）。单列的原因是
//     AudioTrack.write 在设备缓冲满时必然阻塞，不能并进媒体主循环；daemon，
//     退出条件 = playRunning 置 false + stopPlay 里 stop/release 轨道（让阻塞的 write 立即返回），
//     双端无 join。
//   pp-av-loop —— ⚠ 本条**未建线程体**，如实标注而不是建个空转壳：它的六项职责里
//     只有"收帧分发"在 2.2 时点有真实工作（且今天由数据域读线程 pp-read 直接转发，
//     2.3 起 UDP 收包才需要本端分发）；"定时发送 / 停发窗口 / 超龄丢弃 / ctrl 合并窗与双发"
//     四项的前提是本端自有 writer（UDP 或媒体 TCP），那要到 2.3–2.6 才存在；"反馈"
//     现为 pp-video-fb 的 2s video_fb，改 100ms 归 D12 / 任务单 3。
//     另外方案与任务单都没写主循环的**定时周期数字** ⇒ 按"不许在规划里猜"，
//     周期与线程体一起在 2.3 起建 writer 时落地（见任务单 2 单子状态待点）。
//   保留原名（D2 明确"既有相机 / GL / 编码取帧 / ffmpeg 管道线程保留"，本端对应
//     MediaCodec 与相机）：pp-voice-rec（采集+编码）、pp-call-audio（编码取帧发送循环）、
//     pp-video-enc / -dec（MediaCodec drain）、pp-video-cam / -gl（HandlerThread，
//     相机取帧与 GL 转正）、pp-video-cfg / -video-fb / -video-switch（控制与反馈，3 里迁）。
//   锁纪律（I3）：媒体域与数据域**零共享锁**。本类现在唯一的写锁接触点就是 INBAND 一档：
//     sendAudio/sendVideo 直接转 Link.sendAudioTo/sendVideoTo → Session.sendAudio(:479)
//     /sendVideo(:499) 的 writeLock —— 这是 I3 的**书面唯一例外**（改前同一条锁，未新增）。
//     2.3–2.6 起 UDP / 媒体 TCP 分支里**不许**出现 writeLock；自查 = grep 本文件 writeLock，
//     只允许命中注释与本句，不许命中任何代码行。
// ==================================================================
class AvTransport(private val link: Link, private val sink: Sink) {

    /**
     * 五档（方案 §4.1 / D8）。**声明顺序即定档顺序**，试档按 ①→⑤ 推进。
     * ⚠ 全工程只此一处定义档位序列，2.3 的端口选择与 2.5 的定档时序都必须读这里，
     * 不得在 CallManager 里再存一份。
     * ⚠ 2.6（待点 ⑧ 点 (a)「并档」）之后，**时序里跑的"一档"= 同一端口族的 UDP 与 TCP 两侧**
     * （①/②/③ 与 ④ 在同一轮内连着做，见 [roundsForThisEnd]）；本枚举仍是 §4.1 那五条**通道**，
     * 一条没合并，声明序在这里的作用变成"同一轮内 UDP 侧先于 TCP 侧"。
     */
    enum class Tier {
        UDP_443,         // ① UDP 443（安卓无 root ⇒ 本档整体跳过，不是"试了失败"）
        UDP_RANDOM,      // ② UDP 随机 4000–50000
        TCP_443,         // ③ 媒体 TCP 443（安卓同①跳过）
        TCP_RANDOM,      // ④ 媒体 TCP 随机 4000–50000
        INBAND           // ⑤ 消息端口带内（主 TCP，受 D16 通话期文件排他约束）
    }

    // ---- 任务单 2.3：端口选择（方案 §4.1 / U11①，第 5 轮定稿）----
    // 与 PC 端 `AvTransport.java` 的 2.3 段**同序同值**，唯一差异是 tiersForThisEnd() 的过滤。
    companion object {
        private const val TAG = "PP-Av"

        /** 随机档区间，含端点（§4.1：4000–50000）。 */
        const val MEDIA_PORT_MIN = 4000
        const val MEDIA_PORT_MAX = 50000

        /**
         * 避让清单：**照方案 §4.1（:345–346）与本单 2.3 逐项、按原序抄写**，17 项，双端必须一字不差。
         * 28765 / 28766 是本程序自己的消息端口与发现端口（Protocol.kt:25–26）；
         * 1900 = SSDP/UPnP 占它（§4.6，与"不申请 UPnP 映射"互相印证）；5353 / 5357 = mDNS / WS-Discovery。
         * ⚠ 3000 3128 1900 3389 四项落在 4000 以下，随机档实际撞不到（区间内 13 项）；
         *   留着是为了让这份清单与方案一一对得上，不要"顺手精简"。
         */
        val PORT_AVOID = intArrayOf(
            8080, 8000, 8008, 8009, 8888, 3000, 3128, 5000, 5001, 5353,
            5357, 1900, 3389, 7000, 9000, 28765, 28766
        )

        /**
         * 每档随机端口最多实试 8 次；仍失败退系统分配（port 0）；再失败 = 本档不成立，进下一档。
         * ⚠ 2.6 待点 ⑧ 点 (a)：这个 8 是**每种协议各 8 次**（同一轮里 UDP 侧与 TCP 侧各自数满 8，
         * 两侧再各有一次"系统分配"退路），不是整档合计 8 —— 所以随机档一轮最多 16 次真实 bind。
         */
        const val BIND_MAX_ATTEMPTS = 8

        /** 是否为 <1024 的权限档：本端**整体跳过**（非 root 无法 bind），只用于下面那一次过滤。 */
        private fun isPrivileged(t: Tier): Boolean = t == Tier.UDP_443 || t == Tier.TCP_443

        /**
         * 本端要试的档序列 = 五个档**滤掉两个 443 档**（§4.1：安卓非 root 无法 bind <1024 ⇒
         * 整体跳过，不是"试了失败"；所以本端**没有** 443 的 bind 分支，443 只出现在枚举与这份过滤里）。
         * ⚠ 序列从 [Tier.values] 推出来 —— 不许在别处再存一份档位表（2.3 硬自查）。
         */
        fun tiersForThisEnd(): List<Tier> = Tier.values().filterNot { isPrivileged(it) }

        /** 本档是否 UDP 档（probe 发数据报）；false = 媒体 TCP 档（试一条连接）。2.5 的时序按这个分两条支。 */
        fun isUdp(t: Tier): Boolean = t == Tier.UDP_443 || t == Tier.UDP_RANDOM

        // ---- 任务单 2.6：一轮 = 同一端口族的 UDP 与 TCP 两侧（D11 / S11，2026-10-02 待点 ⑧ 点 (a)）----

        /**
         * 定档时序里的一**轮**：§4.1 同一端口族（443 或随机）的 UDP 档与媒体 TCP 档。
         * 轮由 UDP 档领着 ⇒ udpTier 恒非 null；tcpTier 可为 null，含义是
         * "同族的 TCP 档不在本端的档位序列里"。今天两端都不会走到那个 null：§4.1 的过滤是
         * **整族**滤的（安卓把两个 443 档一起滤掉，剩下的随机族两侧都在），所以本端这里
         * 只有〔随机族〕这一轮，且两侧都在。
         * ⚠ 这里只是**把 §4.1 的两条通道配成一对**，不新增端口族、不新增数字：
         *   bind 仍走 2.3 的 [bindTier]（每侧各自 8 次），探测仍走 2.5 的 ②③ 与 ④，
         *   窗口仍是那一个 300ms。
         */
        private class Round(val udpTier: Tier, val tcpTier: Tier?)

        /**
         * 本端的轮序列 = [tiersForThisEnd] 里每条 UDP 档配上同族的 TCP 档（有则配，没有则 null）。
         * ⚠ 仍是从那**一份**档位序列推出来的（2.3 的硬自查："全工程只此一处定义档位序列"
         * 到 2.6 读作"轮也必须从这里推，不许在别处再存一份端口族表"）：
         * PC = 〔443 族，随机族〕两轮，本端 = 〔随机族〕一轮（443 族整族被滤掉），
         * 带内不进轮序列（它是 ⑥ 那条退路，不是一次探测）。
         */
        private fun roundsForThisEnd(): List<Round> {
            val tiers = tiersForThisEnd()
            return tiers.filter { isUdp(it) }.map {
                val fam = if (it == Tier.UDP_443) Tier.TCP_443 else Tier.TCP_RANDOM
                Round(it, if (tiers.contains(fam)) fam else null)
            }
        }

        // ---- 任务单 2.5：振铃期探测与定档时序（方案 §4.3 / U11②⑥，第 5 轮口径）----
        // 三个数**全部来自 §4.3**（②的间隔与条数、③的判定窗），本条不新增任何数字。
        // 2.6（并档）也没有新增数字：④ 的 connect / accept 超时用的还是下面这一个 PROBE_WINDOW_MS。

        /** §4.3②：拿到对端本档端口后各发 3 个 probe。 */
        const val PROBE_COUNT = 3

        /** §4.3②：probe 间隔 30ms。 */
        const val PROBE_INTERVAL_MS = 30L

        /**
         * §4.3③：300ms 判定窗。⚠ 同一个窗口还兼作 §4.3①「交换三元组」的等待窗
         * —— 方案只给了这一个数，对端本档端口没在窗口里广告出来就按本档不通处理（不另设等待时长）。
         * 2.6 起 ④ 的媒体 TCP connect / accept 超时也是这一个数（同一轮里 ②③ 与 ④ 各占一个窗口）。
         */
        const val PROBE_WINDOW_MS = 300L

        // ---- 任务单 3.2：视频分片与重组（方案 §4.4 / §3-3.2，第 5 轮口径）----
        // 单帧 AU 若 >1200B 会被 IP 分片、一个 IP 碎片的 MTU 丢包就会带走整帧 ⇒ 视频按 ≤1200B 切片；
        // 音频 20ms@64k≈175B / 3 帧聚合≈440B、ctrl ≤64B 都在单报之下 ⇒ 都不分片。
        // 这两个数是**从 §4.4 推出来的**（DG_OVERHEAD=12 已由 3.1 定死），不是新增的第三个数字。
        // 与 PC 端 `AvTransport.java` 的 3.2 段**同值同序**。

        /** §4.4：单报（含 12B 头+ts）≤1200B，避免 IP 分片。 */
        const val DG_MAX_REPORT = 1200

        /** 一份视频分片能装的 AU 正文上限 = 1200 − DG_OVERHEAD(12) = 1188B（§4.4 推导，非新增数字）。 */
        const val DG_SLICE_MAX_PAYLOAD = DG_MAX_REPORT - Protocol.DG_OVERHEAD

        /**
         * 媒体 UDP 的**内核**收发缓冲（字节）。与 PC 端 `AvTransport.UDP_BUFFER_BYTES` 同值。
         * 按最坏单帧突发反推：实测 1080p 关键帧 AU 最大 143590B = 121 片 = 145KB，发端队列上限
         * [VIDEO_QUEUE_MAX_SLICES]=512 片 ⇒ 理论最坏 594KB，1MB 留一倍余量；本机内核
         * rmem_max/wmem_max=8388608B（10-03 rooted adb 实测），1MB 合法。
         */
        const val UDP_BUFFER_BYTES = 1 shl 20

        /**
         * 一个 AU 从**末片到达**起最多等多久凑齐，超时整帧丢（基准 2026-10-03 由用户点 A：首片→末片；
         * 窗值 40ms 不变，与 PC 同值同序）。
         *
         * ⚠ 历史：原 §3-3.2 的 10ms 与发端"每 tick 最多 16 片"在数学上互斥 —— 片数 &gt;16 的 AU 至少要跨
         *   1 个 20ms tick 才能排完，而 10ms 窗在它收到第二 tick 的片之前就已经把整帧删掉 ⇒
         *   **任何 &gt;19KB 的 AU 永远凑不齐**（实测 `videoReassembleFail=169`、关键帧一个都没到位，画面灰白后卡死）。
         *   点到 40ms 后配 64 片/tick 覆盖 ≤128 片（≈152KB）的 AU，再大的单帧仍会超窗整帧丢。
         * ⚠ 2026-10-03 17:01 那通 73.5s 实测（PC 收端取证）：被判死的 20 个帧片数 **76–202 片**（15 个是关键帧），
         *   扫掉瞬间的到手片数最多的两个值正是 **64 和 128**（= tick 预算的整数倍），而
         *   `videoSliceLate=874` / `videoSliceMissing=3125` ⇒ 缺的片 28% 后来到了（迟到环只有 64 格，是下界）
         *   ——**片在路上，是窗先把它判死**：内核缓冲当时已是 `rcv=1048576B`，与信号无关。
         *   后果就是"隔一段时间爆卡一下"：丢 IDR ⇒ 整 GOP 一帧不出 ⇒ 定格 ~1s。
         * ⚠ 改末片起算后，"40ms 内没等到新片"才算死 ⇒ 只要这帧还在进片就继续等，大 IDR 不再被自己的
         *   发送节奏判死。代价如实写：真丢片时判死时刻后移（最坏 = 窗值 + 一片的间隔）。
         *   ⚠ 本端**没有** PC 那条 `dropUntilKey` + `video_req` 恢复路径：收端既无等关键帧门，也无 IDR 请求
         *   发送点（`Link.sendVideoReq` 在本仓库无调用方）⇒ 判死后这里只删半帧 + 计数。要不要接、怎么接
         *   是另一件事（差异详情见 VideoReassembler.reportLost 的注释，等你点）。
         *   配合 [VIDEO_SLICES_PER_TICK] 的 256 片/tick，一个最大关键帧在一个 tick(20ms) 内就发完了。
         */
        const val REORDER_WINDOW_MS = 40L

        /**
         * 重组缓冲并发帧上限（机制性上界，不是延迟口径）：@30fps、40ms 窗 ⇒ 同时在途 ≤2 帧，
         * 给 16 足够；命中即丢**最旧帧**并计 `videoReassembleFail` ⇒ 半帧绝不会长期占内存
         * （任务单 3.2「缓冲有上限且超时必清」）。16 是为"缓冲不无限涨"设的结构上限，非 QoS 数字。
         */
        const val REASSEMBLE_MAX_FRAMES = 16

        // ---- 任务单 3.3/3.4：单写者的节拍（2026-10-03 用户点：tick 20ms 不变、视频单 tick 16→64→256 片）----

        /** pp-av-loop 的发送 tick = 20ms（对齐一个音频包周期）。用户定的，非从码里推。 */
        const val SEND_TICK_MS = 20L

        /**
         * 视频单 tick 发送预算 = 256 片（2026-10-03 用户点 C：64→256，按片数、非字节/帧）。
         * 定这个数的依据是 17:01 那通的实测分布：被判死的帧片数 **76–202 片**，最大 202 片（≈234KB）
         * ⇒ 按 64 片/tick 要 4 个 tick = **60–80ms** 才发得完，而收端窗只有 40ms ⇒ 这类关键帧**结构性凑不齐**。
         * 256 = 覆盖实测最大 202 片的一个 tick ⇒ 整帧关键帧一次突发发完（20ms）。
         * 256 份报 = 256×1200B = 307,200B/tick ⇒ 排空能力上限 ≈123Mbps（LAN 无压力），稳态用不到这个预算
         *   （2Mbps@30fps 均帧 ≈ 7 片）。**音频/控制仍旧先排空**，所以音频最坏被挡的时间没变（还是一个 tick=20ms）。
         * ⚠实测：一次 307KB 的突发必须落在发端 SO_SNDBUF 里 —— 安卓侧在 bind 前请求 1MB（T2），
         *   **生效值本轮还没读到**（17:01 那段 PP-AvTransport 已被 logcat 环挤掉）；验收单必须看到手机侧
         *   那行 `缓冲 snd=…B` ≥ 307,200B 才算这条落实。
         */
        const val VIDEO_SLICES_PER_TICK = 256

        /** 视频在途片队列硬顶（结构上界，防内存无限涨）；满则丢当前帧不入队并计 videoDroppedByGate。 */
        const val VIDEO_QUEUE_MAX_SLICES = 512

        /**
         * 把一个 AU 按 ≤[DG_SLICE_MAX_PAYLOAD]B 切成若干 video_slice 报（§4.4，双端同字节）。
         *
         * ⚠ **帧内每片共用同一个 `streamSeq`**（一次 getAndIncrement 领一个数）：这样
         *   ① 重组可按 streamSeq 分帧；② 3.6 的 loss% 与 3.5 的"视频 4 帧"窗口都成立在**帧**粒度上
         *   （若按片各发一个 seq，一个 60 片的大 IDR 会被当成 60 帧、窗口瞬间打满）。
         *   —— 这是方案只写"按 type 独立计数"、没写"按帧还是按片"处**我的推论**（与 S2/3.5 对齐），
         *      标出来等你点；改法只需把下面这一处序号领取改回片粒度，重组器仍按 streamSeq 分帧。
         * ⚠ flag 原样透传（IDR/CONFIG），末片再置 [Protocol.DG_FLAG_LAST_SLICE] 标记帧边界（§4.4 [3]bit2）。
         * ⚠ 本方法只产字节：真正的写 socket 在 **3.3 的 writer**（唯一写点，I3 纪律）。
         *
         * @return 该 AU 的切片总数（0 = len≤0 空切片，调用方按不发送处理）
         */
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

        // ---- 任务单 2.9：通话期存活判定（方案 S23 / P10）。两个数都来自方案原文，本条不新增数字。----

        /**
         * S23：本端已发包、但**这一秒内一条反馈都没收到** ⇒ 记一行 `mediaSuspectedBlocked`
         * （日志 + 计数 + UI 提示），**不回退、不换端口、不重开探测**（I17）。
         * ⚠ 这个数同时就是存活线程的检查周期 ⇒ "1s 判据"与"每秒查一次"共用一个常数，不另设 tick 周期
         *   （任务单 2 待点 ① 说的那个"主循环周期"仍然没有数，本条不是主循环）。
         */
        const val FEEDBACK_SUSPECT_MS = 1_000L

        /**
         * P10（2026-10-02 用户认可的默认处置）：通话**中途**媒体通道彻底无流（10s 双向 0 包）
         * ⇒ 自动挂断 + 提示"呼叫中断"。本模块只**上报**，挂断由通话状态机做（媒体存活由它决定，I14）。
         */
        const val NO_STREAM_HANGUP_MS = 10_000L

        /**
         * 按 §4.1 为一档 bind 本端资源。**失败一律静默**（§4.1 明文：443 被本机其它服务占用是
         * 正常情况，不得报错、不得因此挂断 —— S18 的核实问题在这里是一条硬自查）：
         * 返回 null = 本档不成立，调用方进下一档。不抛异常、不打日志（日志由 2.5 的定档时序
         * 按"每档一行结果"来打，逐次 bind 失败打日志＝1.6 之后的又一处刷屏）。
         * ⚠ 两个权限档在本端**直接判不成立**，不去 bind、也不"试一下再说"：正常时序根本到不了
         *   这里（[tiersForThisEnd] 已经滤掉），万一有人绕过序列直接调它们，也必须等于"本档不成立"。
         * ⚠ INBAND（第五档）没有端口可 bind，它复用主 TCP ⇒ 恒返回 null，由调用方按带内处理。
         * ⚠ **调用点只在 2.5/2.6 的定档时序里**（§4.3①「先 bind 出本档端口，再交换 mport/mudp/msid」，
         *   而 2.6 的"本档"= 同一端口族的 UDP 与 TCP 两侧）：第 0 轮两侧由 [start] bind
         *   （要早于协商报文），其余轮两侧由 [negotiate] 逐轮 bind —— 两处都只经过 [bindRound]，
         *   即**每侧各调一次本方法**（每协议各 8 次就落在这里）。
         *   2.3 落地时本方法还没有调用者，那一句的"start() 只登记 INBAND"从 2.5 起已不成立 ——
         *   start() 现在确实会占住端口（2.6 起是一轮里最多两个），释放路径见 [stop]
         *   与 negotiate() 的每一处出口。
         */
        fun bindTier(t: Tier): Bound? = when (t) {
            Tier.UDP_443, Tier.TCP_443 -> null
            Tier.UDP_RANDOM -> tryBindRandom(t, true)
            Tier.TCP_RANDOM -> tryBindRandom(t, false)
            Tier.INBAND -> null
        }

        /** 随机档：避让端口**不占 bind 名额**（它是"跳过"，不是"试了失败"）；8 次实试后转系统分配。 */
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

        /**
         * UDP bind：用**不带 REUSEADDR** 的普通构造。与 Discovery.kt:238–240 的发现端口相反，
         * 这里刻意不放开地址复用 —— 媒体口被两个 socket 同时绑上，包就会被上一通残留的
         * （或本机另一个）收包线程抢走，表现为"这通听不到对端"。bind 撞口就按本档失败、进下一个随机数。
         *
         * ⚠ 内核缓冲**必须在 bind 之前**设：bind 之后再设只改 JDK 内部缓冲、不动内核队列。
         *   与 PC 端 `AvTransport.tryBindUdp` 同口径（那边默认 65536B 装不下一帧 121 片的关键帧突发，
         *   实测把整 GOP 打成静止；这一侧覆盖反方向 PC→手机的同口突发）。见 plan 第 9 节。
         */
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

        /**
         * 媒体 TCP 监听。形状照本仓库姊妹链路的既有监听先例（LinkConn.kt:131–133 消息端口：
         * 无参构造 + reuseAddress = true + bind）—— 端口**每通新建、不复用上一通的**（§4.1），
         * 上一通"未选中即关"的那条连接可能还在这台机器的 TIME_WAIT 里，不加 REUSEADDR 就会
         * 让下一通在同一随机端口上无故 bind 失败。backlog 用默认值（不新增数字）；
         * accept 侧在 2.6 已落（[probeMediaTcpRound]，测通即把那条连接留在 [mediaTcp] 空闲保持），
         * 本方法只负责把端口占出来。
         */
        private fun tryBindTcpListener(t: Tier, port: Int, systemAssigned: Boolean): Bound? = try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            Bound(t, s.localPort, systemAssigned, null, s)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 一档 bind 到的本端资源：UDP 档只有 [udp]，媒体 TCP 档只有 [tcpListener]。
     * ⚠ 2.6 之后一个 [Round] 里**同时持有两份** Bound（[boundUdp] / [boundTcp]），
     * "未选中的那条立即关"就是关这一档里没被定档采纳的那**一份**；本类只保证 [release] 幂等。
     */
    class Bound(
        val tier: Tier,
        val port: Int,
        /** true = 8 次实试都失败后由系统分配（bind port 0）；数字照样广告给对端。 */
        val systemAssigned: Boolean,
        val udp: DatagramSocket?,
        val tcpListener: ServerSocket?
    ) {
        /** 幂等关闭：释放已 bind 的端口/监听。2.9 的五条退出路径与 2.3 的呼叫失败分支都靠这一句。 */
        fun release() {
            runCatching { udp?.close() }
            runCatching { tcpListener?.close() }
        }
    }

    /**
     * 一次媒体会话的入参（每通新建、不复用上一通的，§4.1）。
     *
     * ⚠ 归一化口径与 PC 端 Spec 构造函数逐字一致：地址/token 为 null ⇒ 空串；
     *   [peerMport] 为负 ⇒ 收口成 0（「未声明」只有一个表达，时序里不许再判负数）。
     */
    class Spec(
        peerIp: String?,
        /**
         * 本端为本次通话生成的随机 token。2026-10-02 用户点「每端各自生成」（本单待点 ⑤）
         * ⇒ 原来那**一个** msid 槽拆成本端/对端两份：本端用来给 probe 打戳，对端用来校验入向。
         */
        localMsid: String?,
        /** 对端 token（invite / accept 初值，之后每档由 call_media 覆盖）；空 = 对端未声明（老端） */
        peerMsid: String?,
        /** 对端媒体端口（2.4 协商来；0 = 对端未声明） */
        peerMport: Int,
        /** 对端媒体端口是否 UDP（2.4 协商来；false = 对端未声明） */
        val peerMudp: Boolean,
        /**
         * true = 本端是主叫。只影响**媒体 TCP 档由谁发起连接**（主叫拨、被叫只 accept）：
         * 方案 §4.3 没写谁拨，两端都拨会在同一窗口里开出两条连接 ⇒ 这里定成一方拨、一方接
         * （任务单 2.5 块「我的推论③」）。
         */
        val caller: Boolean
    ) {
        val peerIp: String = peerIp ?: ""
        val localMsid: String = localMsid ?: ""
        val peerMsid: String = peerMsid ?: ""
        val peerMport: Int = peerMport.coerceAtLeast(0)

        companion object {
            /** 2.4 之前的过渡构造：只有对端地址，协商字段全缺省 ⇒ 恒落带内 */
            fun inband(peerIp: String): Spec = Spec(peerIp, "", "", 0, false, false)
        }
    }

    /**
     * 控制状态全量快照（D14）。字段名逐字符照 §3-D14-1 的两类 owner，
     * 任务单 3.1/3.13 定 14B 线格式时用的就是同一批名字。
     * ⚠ 测量值（queueMs / dropPct / latMs / renderGapMs / loss / jitter）**不在这里**
     * —— 它们走媒体通道的 100ms 反馈（见 [Stats]）。
     */
    class Ctrl {
        var epoch = 0                 // 本次媒体会话号（跨通话不回绕）
        var seq = 0L                  // 会话内单调递增；旧 epoch 或 seq ≤ 已见最大值直接丢

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

    /** 反馈/测量值（D14-1 明确"不属于 ctrl"的那一组），由任务单 3 的 100ms 反馈填充 */
    class Stats {
        var queueMs = 0L
        var dropPct = 0
        var latMs = 0L
        var renderGapMs = 0L
        var loss = 0
        var jitter = 0L
    }

    /**
     * 上层收到的四个回调。
     * ⚠ onAudio/onVideo 带 fromIp 是 2.1 的**过渡口径**：双端今天对源地址的处理不一致
     * （PC 视频按通话 peerIp 过滤、安卓视频由 VideoCall 自己"首源锁定"），
     * 任务单 2.7 的 learn-by-receiving 把源地址收进模块后，这个参数才会从回调里去掉。
     * onCtrl/onStats 在 3.13 / D12 迁移前没有生产者，默认空实现（不假装在工作）。
     * ⚠ 2.9 在这"四个"之外**多出两个 default**（下面那两条存活事件）：模块只上报、不处置，
     *   而"挂断 / 提示"必须是状态机的事（I14「媒体存活由通话状态机决定」）⇒ 回调绕不开，
     *   用 default 是为了不破坏 2.1 那份接口的现有实现者。
     */
    interface Sink {
        fun onAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int)

        fun onVideo(fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int)

        fun onCtrl(ctrl: Ctrl) {}

        fun onStats(stats: Stats) {}

        /**
         * 2.9 / S23：本档"1s 内一条反馈都没收到"，**上升沿**回调一次（恢复后再次发生才再报，
         * 1.6 的"不出现周期性日志"口径同样约束这里）。
         *
         * @param text 模块已经拼好的那一行（含 S23 要求的五项），UI 照显示即可，不要再自己凑数
         */
        fun onMediaSuspectedBlocked(text: String) {}

        /**
         * 2.9 / P10：通话中途媒体通道彻底无流达 [NO_STREAM_HANGUP_MS] ⇒ 上层**自动挂断 + 提示**。
         * ⚠ 本模块不挂断自己（媒体存活由通话状态机决定，I14），这里只是把判据结果送出去。
         */
        fun onMediaNoStream() {}
    }

    @Volatile
    private var spec = Spec.inband("")

    @Volatile
    private var tier = Tier.INBAND

    @Volatile
    private var started = false

    /**
     * 本会话**当前这一轮**已 bind 的两侧资源（2.6 并档）：UDP 侧与媒体 TCP 侧各一份，
     * 任一份可为 null = 该侧 bind 不成立 / 本端不试该侧（本端的两个 443 档就是这样被跳过的）。
     * 定档后只留被采纳的那一侧（未选中的那条立即关，D11 / S11），
     * 落带内或叫停 ⇒ 两侧全关（[releaseBound]）。
     */
    @Volatile
    private var boundUdp: Bound? = null

    @Volatile
    private var boundTcp: Bound? = null

    /** 已 bind 的是第几轮（[roundsForThisEnd] 的下标）；-1 = 当前没有持有任何轮的资源。 */
    @Volatile
    private var boundRound = -1

    /**
     * 2.6 的**预建并保持空闲**的那条媒体 TCP 连接（D11）：④ 握成之后留在这里，
     * 任务单 3.3 的 writer 直接用它、不再付一次握手。⚠ 今天（3.3 之前）本字段**一个字节都不写**
     * —— 写侧与读侧都在 3.3，§4.4 那条 2B 大端前缀也跟到那里落地，本条不留半开连接之外的空写。
     * 定档成 UDP / 落带内 / 时序被叫停 / [stop] ⇒ 立即关（"未选中的那条立即关"包含它）。
     * ⚠ **任务单 2.8 的硬边界（这条连接的身份）**：[mediaTcp] 与 [Bound] 里那条媒体 TCP **监听**都是
     *   **裸 socket**，本模块从不把它们交给 Link —— 它们**不进** `Link.byPeer`
     *   （deviceId → Session 那张单值映射，net/Link.kt:254）、**不参与**同设备双连接去重
     *   （net/Link.kt:1022-1041）、**一个 hello 副作用都不跑**（设备表更新 / 绑定 / 清候选地址 /
     *   selectedIp 跟随 / flushPending / hsync 全都不沾）。
     *   本模块对 Link 的全部调用只有 isConnectedIp / sendAudioTo / sendVideoTo / sendCallMedia 四个，
     *   没有第五个能注册会话 ⇒ "媒体会话与数据会话互不干扰"是靠**没有入口**成立的，不是靠判断成立的。
     *   将来真要登记媒体会话（2.10 的入向放行、3.4 的分发都要按 (deviceId, msid) 找会话），
     *   注册键就是那一对，而且**必须另建一张表**，不许写进 byPeer（那是单值映射，一台设备的第二条
     *   连接会把数据会话挤掉 —— 正是 2.8 要避免的形状）。
     */
    @Volatile
    private var mediaTcp: Socket? = null

    // ---- 任务单 2.5：**对端**广告的三元组（invite/accept 给初值，之后每轮 call_media 覆盖）——
    // 本端拿它当探测目标（probe 发往对端 UDP 侧、媒体 TCP 档连对端 TCP 侧），不是本端自己的端口。
    // ⚠ 2.6 并档之后**按协议分两个槽**：对端一轮里发两份三元组（mudp=true 一份、false 一份），
    //   互相不覆盖 —— 单槽会把"同档 TCP 侧已广告"冲掉，④ 就永远等不到目标端口。
    @Volatile
    private var peerUdpPort = 0

    @Volatile
    private var peerTcpPort = 0

    @Volatile
    private var peerMsid = ""

    /**
     * 对端自报「我现在在第几**轮**」（2.6 并档：同一轮的两份 call_media 带同一个数）。
     * ⚠ **不作连通性判据**：两端轮序列长度不同（本端滤掉两个 443 档 ⇒ 一轮，PC 两轮），
     *   序号对不上是常态；判据只有「收到带本通 msid 的 probe」（UDP）
     *   与「连上了对端广告的媒体 TCP 端口」（TCP 档）两条。本字段只进日志。
     */
    @Volatile
    private var peerTierOrdinal = -1

    /**
     * 任务单 2.7（D10 learn-by-receiving）：媒体面**实际该往哪儿打** = 最近一次从对端收到媒体包
     * 的那个源地址:端口。
     * ⚠ 全模块**只有一个写入点**：[notePeerSource]，而它只许在"收到包"那一处被调
     *   （今天 = ②③ 里认出对端 probe 那一次；任务单 3.4 的 reader 收到 audio/video_slice/ctrl/
     *   feedback 时报**同一个方法**）⇒ 2.7 的硬自查"更新发送目标的地方只有收到包这一处"就这样成立。
     * null = 还没收到过对端任何媒体包 ⇒ 用**初值**（invite/accept 里的 IP + 对端广告的端口，
     *   见 [mediaTarget] 里那条退路）。⚠ 那是"初值"不是"唯一目标"：D10 说的正是多地址设备
     *   "界面地址≠会话地址"，所以一旦有了实测收包源，一律以它为准。
     */
    @Volatile
    private var peerMediaTarget: InetSocketAddress? = null

    /** 定档时序被叫停（挂断 / 取消 / 呼叫失败 / [stop]）；probe 循环每一步都查它。 */
    @Volatile
    private var negotiationStopped = false

    /** ⑥「五档全不通 ⇒ 落带内」＝明示降级。2.11 的 UI 与 4.8 的"聚合恒关（I13）"读它。 */
    @Volatile
    private var inbandDowngraded = false

    /** ctrl 报的 streamSeq，按类型独立计数（3.1 / S10）。16 位取模，回绕由 3.5 的在途窗口按模 2^16 处理。 */
    private val ctrlStreamSeq = java.util.concurrent.atomic.AtomicLong()

    /**
     * 任务单 3.2 的视频切片重组器（每通新建、随会话生命周期，与 §4.1「每通不复用上一通」一致）。
     * ⚠ 投放点 = [avRecvLoop]（收到 video_slice 报）；3.3 已接上真实调用者（不再是零调用死码）。
     */
    private val videoReassembler = VideoReassembler()

    // ---- 任务单 3.3/3.4：媒体面单写者 + 严格优先级队列（仅 UDP 档；带内仍走主 TCP writeLock）----

    /** 音频流 seq（按 type 独立计数，S10）；16 位取模。 */
    private val audioStreamSeq = java.util.concurrent.atomic.AtomicLong()
    /** 视频流 seq：**每帧领一个号**（帧内所有片共用），16 位取模。 */
    private val videoStreamSeq = java.util.concurrent.atomic.AtomicLong()

    // ---- 投递侧的扩展序号（RTP 的 extended seqno 口径，任务单 3.4）----
    // ⚠ 为什么必须扩展：线上 streamSeq 是 **16 位**（3.1 的字节制 + checkU16），而下游三处判据全都
    //   按**单调递增的 int** 写：PC CallManager.handleInboundVideo 的 `seq <= vLastSeq`、
    //   安卓 VideoCall.onRemoteFrame 的 `seq <= lastVideoSeq`、两端 VoiceEngine 的
    //   `seq <= playedSeq` 丢迟到帧 + `next = playedSeq + 1` 严格续播。改前带内帧制的 seq 是 **32 位**
    //   （Protocol.writeAudio/writeVideo 那 4 字节），所以这些判据一直成立；UDP 档若直接把 16 位交下去，
    //   音频约 22 分钟（50 包/s）、视频约 36 分钟（30 帧/s）在 65535→0 回绕后，**每一帧都会被当成重复帧丢掉**
    //   —— 表现是通话中途画面与声音永久冻死。这里把回绕展开成单调 int，下游一字不改。
    // ⚠ 只在"前向回绕"时进位（u16 比上一包小且差值 > 半程 32768）；小幅乱序（重组窗 40ms 内）不进位，
    //   交给下游那三条单调判据自然处理。
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

    /** 四条优先级队列：写者按 audio>ctrl>feedback>video 排空（§4.5 / I2）。元素=已编码整报字节。 */
    private val qAudio = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qCtrl = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qFeedback = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val qVideo = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** pp-av-loop（写者）/ pp-av-recv（reader）。null = 未启动或已关。唯一启动点 = [selectUdpSide]。 */
    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var recvThread: Thread? = null

    /** [answerPeerProbe] 已回数的 probe 份数，上限 [PROBE_COUNT]。写读都在 pp-av-recv，归零在 [start]。 */
    @Volatile
    private var probeReplies = 0

    /** 一帧重组完成后交给消费者的 AU（§4.4：IDR/CONFIG 位原样带出，供 3.9 / 解码器判关键帧）。 */
    class PendingAu(val flag: Int, val ts: Long, val data: ByteArray, val len: Int)

    /**
     * 任务单 3.2 的视频切片重组器（方案 §3-3.2「缺任一片整帧丢；乱序重组窗内凑不齐就整帧丢」——
     * 窗值 2026-10-03 由用户从 10ms 点成 40ms，理由见 [REORDER_WINDOW_MS]）。
     *
     * ⚠ 键 = `dg.streamSeq`：**帧内每片共用同一个 streamSeq**（见 [sliceVideoAu] 的推论），
     *   所以一帧 = 一个 streamSeq 桶，重组按帧聚合、"视频 4 帧"窗口（3.5）也成立在帧粒度上。
     *   这条推论若被否，本类无需改（键仍是 streamSeq），只改发送侧序号领取粒度。
     * ⚠ **消费者是 [avRecvLoop]**（收到 video_slice 报调 [add]）；3.3 已接上真实调用者。
     * ⚠ 并发：UDP 侧单 reader 线程串行调用；本类**不碰** writeLock（I3：与数据域零共享锁）。
     */
    class VideoReassembler {

        private class Frame(val count: Int, val flag: Int, val ts: Long, val firstRxAtMs: Long) {
            val parts = arrayOfNulls<ByteArray>(count)
            val have = BooleanArray(count)
            var got = 0
            /**
             * 最近一片到达的时刻（A 方案，2026-10-03）：超窗判据用它，不再用 [firstRxAtMs]。
             * 只在 reader 单线程域读写（投片在 `synchronized(f)` 内），不需要 @Volatile。
             */
            var lastRxAtMs: Long = firstRxAtMs
        }

        private val frames = java.util.concurrent.ConcurrentHashMap<Int, Frame>()

        /** 当前并发重组中的帧数（供缓冲上限判定与 S23 观测）。 */
        fun size(): Int = frames.size

        // ---- 取证环：最近被扫掉的半帧 seq（容量 64 个半帧 ≈ 2s 记忆，与 PC 端同值同口径）----
        // 用途只有一个：判断"缺的片后来到了没有"。
        //   late≈0 且 videoSliceMissing 大 ⇒ 片根本没到本端（收端缓冲/网络丢）；
        //   late 与 missing 同量级 ⇒ 片到了、但晚于 40ms 窗 ⇒ 那才是 REORDER_WINDOW_MS 的事。
        // 常态零成本：没扫掉过任何半帧时 sweptCount==0，每次投片只是一次 int 比较。
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

        /**
         * 这个 seq 最近被扫掉过吗（= 现在来的这片是**窗后迟到的片**）。
         * ⚠ 按**片**计一次（不是按帧）：与 `videoSliceMissing` 的片粒度同尺，两个数才能直接比大小。
         * 不做"命中即忘掉"：环满自然淘汰，代价是一片迟到的半帧最多计 (count-got) 次，
         * 而那正好就是"迟到的片数"，口径自洽。
         */
        private fun isSweptRecently(seq: Int): Boolean {
            for (i in 0 until sweptCount) {
                if (sweptSeqs[i] == seq) {
                    return true
                }
            }
            return false
        }

        /**
         * 丢帧处置的统一出口（与 PC 端 `VideoReassembler.reportLost` 同口径、同一行日志格式）：
         * 三条取证计数 + 记入迟到环 + 一行日志。只在 reader 线程调（[sweepTimeoutMs] / [evictOverflow]
         * 的同一个循环里）。
         *
         * ⚠ 与 PC 的**一处如实差异**：PC 末尾还有 `LossListener → sink.onVideoChainBroken(key)`
         *   （丢半帧就去要 IDR）。安卓侧**没接**：本端收端既没有 `dropUntilKey` 那样的等关键帧门，
         *   也没有 `video_req(key)` 的发送点，在这里建一个没人接的接口就是本仓库红线禁止的
         *   "建了但没人加"。要接得先把恢复路径本身定下来（等你点）。
         *
         * @param why 只为日志可读：`"超时"`（超 40ms 窗）或 `"超上限"`（重组缓冲满）
         */
        private fun reportLost(seq: Int, f: Frame, why: String) {
            val missing = f.count - f.got
            val key = (f.flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG)) != 0
            CallCounters.videoReassembleFail.inc()
            // 取证三条：半帧的**片数**、以及它是不是参考链起点。与 videoReassembleFail（半帧**个数**）
            // 并读才能分诊（口径详见 CallCounters.kt 头部）。
            CallCounters.videoSliceMissing.add(missing.toLong())
            if (key) {
                CallCounters.videoKeyframeLost.inc()
            }
            rememberSwept(seq)
            // 逐事件一行：正常通话**整通不出现这条**，出现即缺片。A 方案后判死看**末片至今**
            // （≤ 窗值 + 一次扫窗间隔），首片至今只是这帧总共拖了多久。
            val nowMs = System.currentTimeMillis()
            Log.w(TAG, "[视频重组] 半帧$why 缺 $missing/${f.count} 片" +
                    (if (key) "（关键帧）" else "") + " 到手 ${f.got} 片" +
                    " 末片至今 ${nowMs - f.lastRxAtMs}ms 首片至今 ${nowMs - f.firstRxAtMs}ms" +
                    " seq=$seq")
        }

        /**
         * 投一片。返回：非 null = 该帧**刚好凑齐**、交出的整帧 AU；null = 帧尚未完整 / 被去重丢弃。
         * 畸形报（type 不是 video_slice、idx≥count、count<1）由调用方（reader）先按 [Protocol.decodeDatagram]
         * 抛出丢弃；本方法只处理形状合法的业务片。
         * ⚠ 副作用（取证）：这个 seq 若在迟到环里（最近被扫掉过）⇒ `videoSliceLate`+1，
         *   除计数外不改任何行为（与 PC 端同口径）。
         *
         * @param nowMs 本端单调接收时刻（epoch ms），用于新建桶的首片时刻与超窗基准（末片起算）
         */
        fun add(dg: Protocol.Datagram, nowMs: Long): PendingAu? {
            val seq = dg.streamSeq
            val count = dg.sliceCount
            val idx = dg.sliceIdx
            val f = frames.computeIfAbsent(seq) { Frame(count, dg.flags, dg.ts, nowMs) }
            // 同帧的 count 必须一致：count 不符 = 串帧（老 seq 复用），直接丢这一片不污染缓冲。
            if (f.count != count) {
                return null
            }
            // 取证：这个 seq 最近被扫掉过 ⇒ 本片是"窗后迟到的片"，按**片**计一次。
            // 除计数外**不改任何行为**（片照常入新桶；新桶反正凑不齐，会被再次扫掉并再计一次 missing）。
            // 常态零成本：环空时这里只是一次 int 比较（没缺过片就完全不扫环）。
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
                // A 方案：每来一片就把超窗基准往后推（判据见 [sweepTimeoutMs]）。
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
                // ⚠ 交付的 flag 只保留与带内帧制**同义**的那两位（IDR / CONFIG）：
                //   bit2 是数据报专有的 LAST_SLICE，单片帧时会落在首片上，
                //   带出去的 flag 不该混着另一套帧制的位（下游现在都按位与取值，行为不变，
                //   但不剥掉它就是下一个"两套真相"的苗头）。
                return PendingAu(
                    f.flag and (Protocol.DG_FLAG_IDR or Protocol.DG_FLAG_CONFIG),
                    f.ts, au, total)
            }
        }

        /**
         * 超龄整帧丢（§3-3.2「缺任一片整帧丢，超窗整帧丢」；窗值见 [REORDER_WINDOW_MS]，
         * 2026-10-03 用户点 10ms→40ms）：从**末片到达**起算（A 方案，同日由首片改末片，理由见
         * [REORDER_WINDOW_MS] 的口径块），超过 [REORDER_WINDOW_MS] 仍没等到新片、又没凑齐的帧
         * 直接删并计一次 `videoReassembleFail`（连同三条取证计数与一行日志，都走 [reportLost]）。
         * 由 reader 每收一批片后调一次（3.4）；不另起定时器（3.14「无周期心跳」同口径）。
         */
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

        /**
         * 缓冲上限（§3-3.2「重组缓冲有上限且超时必清，不会因半帧长期占内存」）：超过
         * [REASSEMBLE_MAX_FRAMES] ⇒ 丢**最旧帧**（首片时刻最早者）并计数。
         * ⚠ CONFIG/IDR 帧不参与丢弃（任务单 3.2「CONFIG/参数集帧不参与丢弃」是本仓库既有先例）：
         *   遇最旧是关键帧时跳过它去丢次旧的非关键帧；全缓冲都是关键帧则本轮不丢。
         */
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

        /** 会话结束 / 定档切换时清空缓冲（幂等）。残留半帧按失败计，与超时/溢出同口径。 */
        fun clear() {
            val n = frames.size
            frames.clear()
            // 迟到环随本通作废：下一通不该拿上一通扫掉的 seq 来判"这片迟到"
            sweptNext = 0
            sweptCount = 0
            repeat(n) { CallCounters.videoReassembleFail.inc() }
            // ⚠ 这里**不**走 reportLost：挂断残留不是"通话期丢片"的证据，
            //   给它加 videoSliceMissing / videoKeyframeLost 会把验收数字污染成恒非零。
        }
    }

    // ---- 任务单 2.9：S23 那一行日志的内容就取自下面这五个量（缺一项就不达标）----

    /**
     * 本端在媒体面**已发包数**。⚠ 3.3 之前只有带内那一条写者（[mediaUsesMainTcp] 恒 true），
     * 所以这个数今天统计的是"交给写者的媒体帧"，不是"从 UDP/媒体 TCP 发出去的报" —— 但那时
     * [hasFeedbackChannel] 恒 false，本条的判定根本不跑，不存在"拿这个数误报"。
     */
    @Volatile
    private var localSentPackets = 0L

    /** 本端**收包计数**（媒体面的包，今天 = 对端 probe；3.4 的 reader 起来后一并计入）。 */
    @Volatile
    private var localRcvPackets = 0L

    /** **收到的反馈数**。⚠ 生产者要到任务单 3.7（100ms 反馈帧的 reader）才存在，今天恒 0（不假装在工作）。 */
    @Volatile
    private var feedbackRcvPackets = 0L

    /** 对端最后一包到达时刻（epoch ms）；0 = 本通还没收到过任何媒体包。 */
    @Volatile
    private var peerLastRxAtMs = 0L

    /** 最后一次收到反馈的时刻（epoch ms）。同上，今天没有生产者。 */
    @Volatile
    private var lastFeedbackAtMs = 0L

    /**
     * 2.9 的存活检查线程（`pp-av-alive`）。null = 未启动或已关。
     * ⚠ **唯一**启动点是 [selectUdpSide] / [selectTcpSide]（定档采纳成四档之一时），
     * 且启动前先过 [hasFeedbackChannel] ⇒ 带内档永远不起这条线程（不留一个空转壳，同 2.2 口径）。
     * 关闭 = [stop] 摘引用 + `interrupt()`，循环本身还每 tick 查 [started]。
     */
    @Volatile
    private var aliveThread: Thread? = null

    /** "疑似被拦"是否已就本次事件上报过（上升沿只报一次；重新收到反馈后复位）。 */
    @Volatile
    private var suspectedBlockedLatched = false

    /** 双向都静默的起点（epoch ms）；0 = 当前不是无流状态。P10 的 10s 从它算起。 */
    @Volatile
    private var bothQuietSinceMs = 0L

    /**
     * 2.10 / P6 的**注入点**：媒体入向的"① 通话中 + ② 已知设备 + 网段黑名单"这三半。
     * 本模块没有这三张表（通话状态机、已知设备名单、黑名单都在上层），所以由 CallManager 在
     * openTransport 里注入；判据本体见 [admitMediaInbound]。
     * null = 未注入 ⇒ 一律**拒绝**（不放行来源不明的媒体连接）。
     * ⚠ 只影响**被叫侧的媒体 TCP accept**；UDP 侧的入向今天已有 [isPeerProbe] 的 msid 校验，
     *   本条不动它（口径差异与理由见任务单 2.10 块）。
     */
    @Volatile
    var mediaInboundAdmit: ((remoteIp: String) -> Boolean)? = null

    /**
     * 建媒体会话：登记 spec，并**立刻 bind 第 0 档的两侧**（§4.3①「各自按 §4.1 顺序 bind 出
     * 本档端口（**UDP + 媒体 TCP 监听**）」——2.6 并档之后"本档"就是一整个 [Round]，两侧都在这里
     * bind 出来，端口要随 invite / accept / call_media 出去，所以必须早于协商报文）。
     * 2.1 的「start() 只登记 INBAND」从 2.5 起改成真正的时序入口：定档由 [negotiate] 跑完。
     */
    fun start(spec: Spec?) {
        val s = spec ?: Spec.inband("")
        this.spec = s
        this.tier = Tier.INBAND
        this.started = true
        // 2.4 落的那三个 wire 字段从本条起**有读者**了（三元组进模块，供 probe 定目标与校验 msid）
        // ⚠ 2.6：invite / accept 只带**一份**三元组（§4.2 那三个字段没扩，I8/I11 的口径不动），
        //   按 mudp 落进对应那一侧的槽；另一侧等对端第一轮的两份 call_media 来填。
        this.peerUdpPort = if (s.peerMudp) s.peerMport else 0
        this.peerTcpPort = if (s.peerMudp) 0 else s.peerMport
        this.peerMsid = s.peerMsid
        this.peerTierOrdinal = -1
        // 2.7：发送目标随每一通重新学（端口本就每通新建、不复用上一通的，§4.1）
        this.peerMediaTarget = null
        this.negotiationStopped = false
        this.inbandDowngraded = false
        // 2.9：五个计数 + 两条闩锁随每一通归零（计数跨通累积就会把 S23 那行的"已发/已收"判成上一通的数）
        this.localSentPackets = 0L
        this.localRcvPackets = 0L
        this.feedbackRcvPackets = 0L
        this.peerLastRxAtMs = 0L
        this.lastFeedbackAtMs = 0L
        this.suspectedBlockedLatched = false
        this.bothQuietSinceMs = 0L
        this.probeReplies = 0
        // 3.4：投递侧的扩展序号随每一通重新起算（流内 16 位序号按通独立，展开的高位自然也跟着归零）。
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

    /**
     * ①：bind 一轮的两侧（2.6）。每侧**各自**走 2.3 的 [bindTier]（各自 8 次实试 +
     * 各自一次系统分配退路）⇒ 这就是待点 ⑧ 点 (a)「每协议各 8 次」的落点。
     * 失败静默（§4.1），由调用方按"该侧不成立"处理；本方法不打日志（日志仍按每轮一行，1.6）。
     * ⚠ 本端两个 443 档的 udpTier / tcpTier 在 [roundsForThisEnd] 里就不会出现，
     *   所以这里没有"安卓跳过 443"的分支 —— 与 PC 唯一的差异仍然只是序列长度。
     */
    private fun bindRound(r: Round) {
        this.boundUdp = bindTier(r.udpTier)
        this.boundTcp = if (r.tcpTier == null) null else bindTier(r.tcpTier)
    }

    /** 本端**已 bind、等着随协商报文带出去**的媒体端口；0 = 本档两侧都不成立 / 走带内。 */
    fun mediaPort(): Int = advertisedSide()?.port ?: 0

    /** [mediaPort] 那个端口是否 UDP（与 mediaPort() 同一时刻读才自洽）。 */
    fun mediaIsUdp(): Boolean {
        val b = advertisedSide() ?: return false
        return isUdp(b.tier)
    }

    /**
     * invite / accept 那**一份**三元组该带哪一侧：2.6 一轮里有两个本端端口，而 §4.2 只给了
     * 一个 mport + 一个 mudp ⇒ 按 §4.1 的声明序取 **UDP 侧优先**，UDP 侧不成立时才退 TCP 侧
     * （另一侧由 [advertise] 的第二份 call_media 带出去，不靠这两个字段）。
     */
    private fun advertisedSide(): Bound? = boundUdp ?: boundTcp

    /** 本端本次通话的 token —— CallManager 把它写进 invite/accept/call_media 的 msid。 */
    fun localMsid(): String = spec.localMsid

    /**
     * 收到对端的媒体三元组。三个来源归这一个入口：`call_invite` / `call_accept`
     * （2.4 的那一份三元组）与 `call_media`（2.5 的交换，2.6 起**一轮两份**：
     * mudp=true 那份进 UDP 槽、mudp=false 那份进 TCP 槽，互不覆盖）。
     *
     * @param mtier 对端自报的本轮序号；只进日志，见 [peerTierOrdinal]
     */
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

    /**
     * 振铃期一次跑完的**定档时序**（方案 §4.3 七步 / U11②⑥）。
     * ⚠ 会按窗口阻塞 ⇒ 只能在独立线程上调用（CallManager 的 pp-av-negotiate），绝不能在
     *   主线程上跑；本方法不做任何通话状态迁移，只回结论。
     *
     * 七步对位（2.6 并档后）：① = [bindRound]（一轮两侧）+ [advertise]（两份三元组）；
     * ②③ = [probeUdpRound]；④ = [probeMediaTcpRound]，**在同一轮里紧跟着 ②③**，不回 ①
     * （这就是 D11 / S11 省掉的那"再开一轮"）；
     * ⑤ = 循环末尾 releaseBound + 进下一轮；⑥⑦ = [settleInband]。
     * ⚠ 同一轮里 UDP 侧先探、TCP 侧后探：两侧都通时取 UDP（§4.1 的声明序就是 D8 的偏好序）。
     *
     * ⚠ 与 PC 端唯一的结构差异就是**轮数**：本端 [tiersForThisEnd] 已经把两个 443 档滤掉，
     *   所以 [roundsForThisEnd] 只有〔随机族〕一轮，这里**不写任何"安卓跳过 443"的分支**。
     *
     * @return true = 已定档（含 ⑥ 的带内档，此时 [mediaDowngradedToInband] 为 true）；
     *         false = ⑦ 带内也不成立 ⇒ 提示呼叫失败、**不进 TALKING**（U11②）
     */
    fun negotiate(): Boolean {
        val s = spec
        if (!started || negotiationStopped || s.peerIp.isEmpty()) {
            releaseBound()
            return false
        }
        if (!s.caller && peerMsid.isEmpty()) {
            // 被叫侧的 invite 是**权威**的「对端声明了没有」证据：空 = 主叫不认这三个字段（老端）
            // ⇒ 没有可探测的对端端口，直接按 §4.2 的缺省回退落带内（老端只要求不断链，U11⑦ / S19）
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
                // ⑤「换下一档端口，回到 ①」：上一轮失败时已整轮释放，这里给新一轮 bind 两侧。
                // 第 0 轮已由 start() bind 过（§4.3① 要它早于协商报文）⇒ boundRound==0，这里跳过。
                releaseBound()
                bindRound(r)
                boundRound = i
            }
            // stop() 与这一次 bind 之间的竞态：stop() 是「先置标志、再 releaseBound」，
            // 所以本端必须「先写入 bound、再查标志」（两边都是 @Volatile ⇒ 这一对里至少有一边看得见另一边）。
            // 少了这一查，振铃期正好点挂断时，刚 bind 出来的那两个端口就没有人释放（对象已被摘掉，
            // 只能等 GC 的 socket finalizer，不是确定性的关闭）。
            if (negotiationStopped) {
                releaseBound()
                return false
            }
            val u = boundUdp
            val tc = boundTcp
            if (u == null && tc == null) {
                // §4.1 明文：bind 失败静默跳下一档，不报错、不挂断（S18 的核实项在这条分支成立）
                logTier(r, i, "本档两侧 bind 都不成立 ⇒ 静默进下一档")
                continue
            }
            advertise(i)
            // ②③：本档 UDP 侧。对端那一侧没广告端口时 probe 不发（peerReadyFor），窗口自然走空
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
            // ④：同一档的媒体 TCP 侧 —— 端口在 ① 就 bind 好了，所以这里不回 ①（D11 / S11）
            if (tc != null && probeMediaTcpRound(tc)) {
                if (negotiationStopped) {
                    // 预建那条也在 releaseBound 的范围里：窗口刚握好就被叫停 ⇒ 不留连接
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

    /**
     * 定档采纳 UDP 侧（2.6 的"未选中的那条立即关"）：同档那条媒体 TCP **监听**关掉 ——
     * 它一条连接都没握过，留着只会平白多占一个端口（M6"不留半开连接"）。
     * UDP 侧的 Bound 继续持有（任务单 3.3 的 writer 用那个 socket 发包）。
     */
    private fun selectUdpSide(t: Tier) {
        tier = t
        val b = boundTcp
        boundTcp = null
        b?.release()
        // 3.3：采纳 UDP 档 ⇒ 起本端媒体 IO（唯一 writer + reader）。必须在 startAliveWatchdog 之前：
        //   存活判定读 hasFeedbackChannel()＝!mediaUsesMainTcp()，而后者要等 writerThread 起来了才变 false。
        startMediaIo()
        // 2.9：定档采纳成四档之一 ⇒ 这一档有反馈通道了，存活判定从这里开始跑（带内档走不到这里）
        startAliveWatchdog()
    }

    /**
     * 定档采纳媒体 TCP 侧：未选中的 UDP 侧立即关；**TCP 监听也关**（本通只保留 ④ 握成的那一条，
     * 不再接受第二条连接）。预建的那条在 [mediaTcp] 里、不属于任何 Bound，
     * 所以这里走的 [Bound.release] 不会把它一起关掉（这正是"未选中即关"与"预建保留"
     * 两条要求在同一段代码里共存的原因）。
     */
    private fun selectTcpSide(t: Tier) {
        tier = t
        val u = boundUdp
        boundUdp = null
        u?.release()
        val b = boundTcp
        boundTcp = null
        b?.release()
        // ⚠ 3.3 本轮只落了 UDP 侧的自有 writer/reader；媒体 TCP 档的自有 IO 未建 ⇒ 不在此起 startMediaIo。
        //   于是 mediaUsesMainTcp() 对本档仍 true（媒体帧照走带内），hasFeedbackChannel() 也恒 false ⇒
        //   下面这条 startAliveWatchdog 走不进去（判据没过），不会为一条其实还在带内的通道空起线程。
        startAliveWatchdog()
    }

    // ---- 任务单 3.3/3.4：媒体 IO（UDP 档的自有 writer + reader）----

    /**
     * 起本端 UDP 媒体 IO 的两条线程。**唯一调用点 = [selectUdpSide]**（定档采纳成 UDP 档时）。
     * ⚠ 幂等：已在跑就不重复起。关 = [stop] 里 [stopMediaIo]。
     * ⚠ I3：这两条线程**只碰媒体 socket，绝不碰 Link 的 writeLock**。写 socket 的调用点全工程只有一处
     *   = [avWriteLoop] 里的 [sendUdp]（媒体面）。
     */
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

    /**
     * 关本端 UDP 媒体 IO：先摘引用（⇒ [udpEgressActive] 立即 false，sendAudio/sendVideo 同步回落带内）、
     * interrupt 叫醒 sleep 的写者与阻塞的 receive，再清四条队列。socket 本身由 releaseBound 关。
     */
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

    /**
     * 写者线程体：每 [SEND_TICK_MS] 一个 tick，严格优先级排空
     * （音频全部 → 控制全部 → 反馈全部 → 视频最多 [VIDEO_SLICES_PER_TICK] 片，§4.5 / I2 / S16）。
     * ⚠ 唯一媒体写 socket 处。退出：!started 或本端不再是活的 UDP 档（[udpEgressActive] 变 false）。
     */
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

    /**
     * 把一份已编码的数据报发到对端媒体目标（[mediaTarget] 以实测收包源优先）。
     * ⚠ 发送失败静默（UDP 无发送侧反馈，丢这报由 3.6 的 loss% 事后体现，不刷屏）。成功计 localSentPackets。
     */
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

    /**
     * reader 线程体：收 boundUdp 上的数据报，按类型分发。
     * ⚠ 2.7：收到任何媒体面包都调 [notePeerSource]（发送目标唯一写入点）+ [notePeerMediaPacket]（计数+时刻）。
     * ⚠ 投递时 fromIp 用 [spec] 的 peerIp（本 app 同时只有一通，媒体 UDP 已隐式属于这通 ⇒ 满足下游 peerIp
     *   过滤又不要求源地址==界面地址，兼容 D10 多网卡）。逐包 msid 鉴权在音频/视频数据报上无字段承载
     *   （3.10 的诚实边界），本条不假装做了。
     * ⚠ 畸形报（decodeDatagram 抛）直接丢这一份，不断链、不计数。
     */
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
                    // 缓冲上限在这里生效（§3-3.2「重组缓冲有上限」）：凑齐的帧已经出桶，
                    // 剩下的都是还缺片的 ⇒ 超上限就丢最旧的非关键帧并计数。
                    videoReassembler.evictOverflow()
                    if (au != null) {
                        sink.onVideo(peer, extendVideoSeq(dg.streamSeq), au.ts, au.flag, au.data, au.len)
                    }
                }
                else -> {
                    // ctrl / feedback：本轮不在此消费（ctrl 仍走带内 Link 路径；反馈归 3.6/3.7）。
                    // 唯一的例外是对端的 probe —— 不回数会把两端劈成「一边 UDP、一边带内」，见
                    // [answerPeerProbe]。校验仍旧只认 msid 匹配的那份（[isPeerProbe]）。
                    if (dg.type == Protocol.DG_TYPE_CTRL && isPeerProbe(rx, p.length)) {
                        answerPeerProbe()
                    }
                }
            }
        }
    }

    /**
     * §4.3① 的「交换」：2.6 起一轮把本档**两侧**都带给对端 ⇒ 两份 call_media
     * （mudp=true / false 各一份，带同一个轮号 i；端口 0 = 该侧不成立，对端据此不去探那一侧）。
     * 第 0 轮主叫侧的 **UDP 那份不重发** —— 它已经随 call_invite 出去了（CallManager 在发 invite
     * 之前就从 [mediaPort] 取过这份值，而 mediaPort() 按 §4.1 声明序优先给 UDP 侧）；
     * **TCP 那份必须发**：invite 里没有第二个端口位（§4.2 只有 mport/mudp/msid，扩字段归待点 ⑥）。
     * 被叫侧的两份都走 call_media（call_accept 的语义是「我接了」，不能拿来预播报，
     * 这是 2026-10-02 待点 ⑥ 点 (a) 的落点）。
     */
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

    /**
     * ②③：本档 UDP 的双向连通性测试。一个 300ms 窗口里同时干三件事 ——
     * 等对端把本档端口广告出来（①的交换，没有另一个数）、发 3 个 probe（间隔 30ms）、
     * 收对端 probe 并校验 msid。
     *
     * ⚠ 本方法**只定方向上的"路"**：收到 probe 的源地址此刻不采信为发送目标（那是 2.7 的
     *   learn-by-receiving），也不把 payload 交给任何消费者（数据面在任务单 3）。
     */
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
                // ⚠ 等待上限必须是**探针间隔**而不是整窗剩余：钉 left 时这一次 receive 会把 300ms 窗口
                //   一路睡完（除非正好有包进来），probe 2/3 永远发不出去。主叫侧的 probe 1 又必须等
                //   对端的 call_media 落到 peerUdpPort 才发得出（peerReadyFor 闸门），于是两端各自的
                //   窗口里都只可能有一份探针、甚至零份 ⇒ 谁都收不到对端 probe ⇒ 恒落第五档。
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
                // 2.7（D10）：这是本模块**今天唯一的媒体面收包点** ⇒ 发送目标只在这里按实测源更新
                notePeerSource(p.socketAddress)
                notePeerMediaPacket()   // 2.9：同一个收包点，收包计数与"最后一包到达时刻"也在这里进
                return true
            }
        }
        return false
    }

    /**
     * ②：一份 probe ctrl 报 = `encodeCtrl(CT_PROBE, streamSeq, ts, msid 原文)`。
     * ⚠ 字节一律由任务单 3.1 那份 codec 产生（本单待点 ⑦ 的口径：2.5 **不许**自己拼字节）。
     * ⚠ 发送失败不判死：UDP 没有发送侧反馈，剩下的 probe 照发，成不成由窗口内有没有收到对端包定。
     */
    private fun sendProbe(b: Bound) {
        val u = b.udp ?: return
        if (u.isClosed) return
        val report = encodeProbe() ?: return
        try {
            u.send(DatagramPacket(report, report.size, mediaTarget(peerUdpPort)))
        } catch (e: IOException) {
            // 静默：端口已关 / 对端地址不可达都属本档不通，交给窗口判定（不在这里刷屏，1.6）
        } catch (e: RuntimeException) {
            // 同上
        }
    }

    /** 一份 probe ctrl 报的字节（[sendProbe] 与 reader 的回数共用同一份编码，不许有第二套）。 */
    private fun encodeProbe(): ByteArray? {
        val body = spec.localMsid.toByteArray(Charsets.UTF_8)
        return try {
            Protocol.encodeCtrl(
                Protocol.CT_PROBE,
                (ctrlStreamSeq.getAndIncrement() and 0xFFFFL).toInt(),
                CallClock.elapsedMs(), body, 0, body.size
            )
        } catch (e: IOException) {
            null   // msid 超 ctrl 报上限：本模块的 token 恒为 36 字符 UUID，走不到这里
        }
    }

    /**
     * 已采纳 UDP 档之后，reader 收到对端 probe 时**回数一份**（只入 [qCtrl]，媒体 socket 的唯一
     * 写点仍是 [avWriteLoop] —— I3）。
     *
     * 为什么必须有：两端各自的 300ms 窗口是**独立起算**的（主叫在发 invite 时、被叫在收到 invite
     * 时），一方被 UI 线程拖住晚进窗口就先定完档、停止 probe，另一方此后再也收不到 probe ⇒
     * 一边 UDP、一边带内的劈裂态：带内那方发的帧我们收得到，它却收不到我们的媒体报（本端已按
     * UDP 档发，对端端口在它落带内时已被 releaseBound 关掉）。劈裂在用户侧的表现是「对端没画面」，
     * 比两端都带内更糟。
     *
     * ⚠ 上限直接复用 [PROBE_COUNT]，不新设数字：probe 是幂等的连通性回声，对端收到第一份就定档，
     *   三份封顶 ⇒ 两端互相回数的最坏情况也是个位数报文，不会形成回声风暴。计数随每通归零（[start]）。
     */
    private fun answerPeerProbe() {
        if (probeReplies >= PROBE_COUNT) {
            return
        }
        val rep = encodeProbe() ?: return
        probeReplies++
        qCtrl.add(rep)
    }

    /**
     * 2.7（D10）：媒体面发送目标的**唯一写入点**。只允许在"从对端收到一份媒体面的包"那一处调用
     * —— 今天 = ②③ 里认出对端 probe 的那一次；任务单 3.4 的 reader 收到 audio / video_slice /
     * ctrl / feedback 时报的必须是**同一个方法**（不许在别处再写一次 peerMediaTarget）。
     * 源变了才记一行日志（1.6：不出现周期性日志）。
     */
    private fun notePeerSource(from: SocketAddress?) {
        val a = from as? InetSocketAddress ?: return
        val addr = a.address ?: return
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

    /**
     * 2.7：本端现在该往哪个地址:端口打。有实测收包源就用它；还没有任何媒体包时退回**初值**
     * （invite/accept 里的 IP + 对端这一侧广告出来的端口）。
     *
     * @param fallbackPort 只在"还没收到过包"时用那个端口（UDP 侧传 peerUdpPort）
     */
    private fun mediaTarget(fallbackPort: Int): InetSocketAddress {
        val t = peerMediaTarget
        if (t != null) {
            return t
        }
        return InetSocketAddress(spec.peerIp, fallbackPort)
    }

    /**
     * 2.7：媒体 TCP 档**主叫拨号**该拨哪个 IP —— 还是"实测收包源的 IP 优先，没收到过包才用
     * invite/accept 里的 IP"。端口**不**跟着实测源走：那个 socketAddress 的端口是**对端 UDP 侧**的，
     * 与媒体 TCP 的监听端口无关，所以这里只借 IP（这正是 D10 要的"多地址设备别打错网卡"）。
     */
    private fun mediaTargetIp(): String {
        val t = peerMediaTarget
        return t?.address?.hostAddress ?: spec.peerIp
    }

    /** 收到的这份报是不是**本通对端**发来的 probe（类型 + 子类型 + msid 三项都对才算）。 */
    private fun isPeerProbe(buf: ByteArray, len: Int): Boolean {
        val want = peerMsid
        if (want.isEmpty()) {
            return false   // 还没拿到对端 token ⇒ 无从校验，一律不算通（§4.3②「带 msid」的硬自查）
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
            false   // 3.1 的口径：畸形报直接丢这一份，不吞成「看着像空报」
        }
    }

    /**
     * ④：本档媒体 TCP 的连通性尝试（2.6：**与 ②③ 同一轮**，本侧端口在 ① 就 bind 好了，
     * 所以 UDP 侧不通时直接进这里，不再回 ① 重开一轮 —— D11 / S11 省掉的就是那一次往返等待）。
     * ⚠ 2.5 那句"测通就立刻关掉"从 2.6 起作废，改成**预建并保持空闲**（D11）：握成的这条留在
     *   [mediaTcp]，任务单 3.3 的 writer 直接用它、不再付一次握手；
     *   没被采纳（定档 UDP / 落带内 / ⑦ 呼叫失败 / 时序被叫停 / [stop]）时由 [releaseBound] 关 ——
     *   关它的每一条路径都在那一个出口里，含 2.9 的四条退出路径。
     * ⚠ 保留 ≠ 使用：本条**一个字节都不往它上面写**。§4.4 那条「2B 大端长度前缀 + 报文体」的
     *   写侧与读侧必须同一处，落点是 3.3 的 writer（那边才有唯一的写入口），3.2/3.3 不许再造第二种框帧。
     * ⚠ 主叫拨、被叫接（方案没写谁拨，见任务单 2.5 块「我的推论③」）：两端都拨会在同一窗口里开出两条。
     * ⚠ 主叫侧对端还没广告本侧端口时**不空转等待**：直接按本侧不通换下一档
     *   —— 两端各自单调推进，对端随后广告时会落到它自己序列里的同一族档上（失效模式②）。
     */
    private fun probeMediaTcpRound(b: Bound): Boolean {
        val ls = b.tcpListener ?: return false
        if (ls.isClosed) return false
        if (spec.caller) {
            if (!peerReadyFor(false)) {
                return false
            }
            val so = Socket()
            return try {
                // 连接超时 = 本档窗口（§4.3 只给了这一个数）
                // 2.7（D10）：拨的 IP 用 mediaTargetIp()（实测收包源优先），端口仍是对端广告的 TCP 侧
                so.connect(InetSocketAddress(mediaTargetIp(), peerTcpPort), PROBE_WINDOW_MS.toInt())
                mediaTcp = so   // D11：预建保持，3.3 的 writer 接手
                true
            } catch (e: IOException) {
                // 拒连 / 超时 = 本档媒体 TCP 不通，静默换下一档；这条没用的连接就地关，不留半开
                runCatching { so.close() }
                false
            }
        }
        return try {
            ls.setSoTimeout(PROBE_WINDOW_MS.toInt())
            val so = ls.accept()
            // 2.10（P6 / S14 + 2.6 记下的必办项）：被叫侧这条 accept **过去不做来源校验**，
            // 窗口内任何同网段主机连进来都会被采纳成预建那条 ⇒ 串话入口。现在按三分句拒。
            val rip = so.inetAddress?.hostAddress ?: ""
            if (!admitMediaInbound(rip)) {
                Log.i(TAG, "[媒体准入] 被叫侧拒收一条媒体 TCP 入向：$rip" +
                        "（通话中 / 已知设备 / msid 三条件不成立，P6 / S14）⇒ 不采纳，本档媒体 TCP 按不通处理")
                runCatching { so.close() }
                false
            } else {
                mediaTcp = so   // 被叫侧只收一条：窗口内**通过准入的**那条就是预建
                true
            }
        } catch (e: IOException) {
            false   // 窗口内没人连进来 / 监听已被 stop() 关掉
        }
    }

    /**
     * 媒体入向的准入判据（2.10 / P6 / S14）。⚠ 只用于**被叫侧 accept** 这一条：
     * 主叫侧那条是我们自己拨出去的（[mediaTargetIp] 已在 2.7 以实测收包源为准），
     * 远端地址就是拨号目标，没有"陌生入向"这回事 ⇒ 不经本方法。
     *
     * 三个条件是**与**关系，缺一律拒（S14 明文"不能整条关掉闸门"，这里也不许整条放开）：
     *   ① 通话中、② 已知设备、②b 不在既有网段黑名单 —— 这三样只有本机有表（通话状态机、
     *      已知设备名单、高风险段/设备级标记），由 [mediaInboundAdmit] 注入；
     *   ③ msid 匹配 —— 本模块这一半：本通必须已经拿到**对端声明的** token（[peerMsid] 非空）。
     *      空 = 对端是老端、没认这三个字段（§4.2 缺省回退），那它根本不该有媒体 TCP 连进来；
     *      放进来的话这条连接不属于任何已协商的媒体会话。
     *
     * ⚠ 诚实边界：TCP 侧**线上**逐帧 msid 校验今天做不了 —— ④ 只握手、零字节（D11 预建），
     *   第一个带 msid 的字节要到任务单 3.3/3.4 的 writer+reader 才出现。到那时按 S12 的口径做：
     *   不匹配的报**直接丢且不计入 loss**（串扰不许污染 QoS 判据）。今天能闭合的是
     *   "设备身份 + 本通有 token"这一半，而 2.6 那条必办项要的正是"3.3 的 writer 接手之前关上"。
     * ⚠ 注入缺失（判据为 null）按**拒绝**处理：宁可定档落带内，不放开一道来源不明的媒体连接。
     */
    private fun admitMediaInbound(remoteIp: String): Boolean {
        if (remoteIp.isEmpty() || peerMsid.isEmpty()) {
            return false   // ③
        }
        val admit = mediaInboundAdmit ?: return false   // 未注入 ⇒ 拒绝
        return admit(remoteIp)   // ①②②b（本机的表）
    }

    /**
     * 对端本档**这一侧**的端口能不能用来探（token 已到、该侧端口有效）。
     * ⚠ 2.6 并档之后两个槽独立：对端 UDP 侧广告 0 不影响它的 TCP 侧可用（一轮两份 call_media
     *   各写一个槽，谁也不冲谁）—— 这正是 2.6 把 2.5 那个单槽（peerPort/peerUdp）拆成两个的原因。
     */
    private fun peerReadyFor(wantUdp: Boolean): Boolean =
        peerMsid.isNotEmpty() && (if (wantUdp) peerUdpPort > 0 else peerTcpPort > 0)

    /**
     * ⑥⑦：五档全不通 ⇒ 落消息端口带内并**明示降级**；主 TCP 已无会话 ⇒ ⑦ 呼叫失败。
     *
     * @param why 进日志的原因（⑤ 走满 / 老端未声明）
     */
    private fun settleInband(why: String): Boolean {
        // 带内档复用主 TCP ⇒ 不留任何已 bind 的媒体端口，也不留 ④ 预建的那条连接
        // （§4.1：INBAND 没有端口可 bind；releaseBound 是两侧 + 预建的唯一出口）
        releaseBound()
        if (!link.isConnectedIp(spec.peerIp)) {
            // ⑦：带内也不成立 ⇒ 提示呼叫失败、不进 TALKING（U11②，2026-10-02 用户点）
            Log.i(TAG, "[媒体定档] $why，且主 TCP 会话已不在 ⇒ ⑦ 呼叫失败，不进 TALKING")
            return false
        }
        tier = Tier.INBAND
        inbandDowngraded = true
        // ⑥：UI 明示降级（2.11 读 mediaDowngradedToInband()）+ D16 的通话期文件排他（2.12）
        //   + 聚合恒关（I13，4.8 读同一个标志）。这里先把日志落实，两个消费者各归各单。
        Log.i(TAG, "[媒体定档] $why ⇒ ⑥ 落消息端口带内（第五档）。降级提示：本次通话期间文件不发送、" +
                "网络外排队（D16）；聚合恒关（I13）")
        return true
    }

    /**
     * 每轮一行结果（2.3 把日志推迟到这里，就是为了不出现"每次 bind 失败一行"的刷屏，1.6）。
     * 2.6 并档：一行里带本档**两侧**的端口（这一行在采纳 / 释放之前打，所以两侧都还在）。
     */
    private fun logTier(r: Round, i: Int, result: String) {
        val u = boundUdp
        val tc = boundTcp
        Log.i(TAG, "[媒体定档] 第${i + 1}轮 ${r.udpTier} / ${r.tcpTier}" +
                "（本端 UDP ${u?.port ?: 0}、媒体 TCP ${tc?.port ?: 0}）→ $result")
    }

    /**
     * ⑥ 分支置位：本次通话落到了带内档（降级）。上层只读不写 ——
     * 2.11 的 UI 明示与 4.8 的「聚合恒关（I13）」都读这一个标志。
     */
    fun mediaDowngradedToInband(): Boolean = inbandDowngraded

    /**
     * 本通媒体的**实际承载**是否仍是主 TCP 带内。
     * ⚠ 判据是"媒体帧**实际**还在走带内写者"，不是"档定成了哪一档"：任务单 3.3 起 UDP 档已有自有
     *   writer/reader（[udpEgressActive]），媒体帧离开主 TCP ⇒ 本方法对 UDP 档返回 false。
     *   媒体 TCP 档今天仍走带内（自有 writer 只落了 UDP 侧，见 3.3 边界），带内复用主 TCP —— 两者本方法恒 true。
     *   D16 的通话期文件闸门（2.12）与聚合恒关（4.8）必须读**本方法而不是 tier()** ——
     *   读 tier 会在「已定档 UDP、writer 还没起」这段时间里把文件放进主 TCP 抢写锁。
     */
    fun mediaUsesMainTcp(): Boolean = !udpEgressActive()

    /**
     * UDP 档的媒体 writer/reader 是否已就绪（媒体帧真的能从 `boundUdp.udp` 发出去）。
     * ⚠ 不硬编码档位：[selectTcpSide] 会把 [boundUdp] 置 null、[settleInband] 全关 ⇒ "仍有活的 UDP socket
     *   且 writer 线程已起"三者同时成立 ⟺ 采纳的是 UDP 档且本端 IO 在跑 ⇒ 带内 / 媒体 TCP / 定档前一律 false。
     */
    private fun udpEgressActive(): Boolean {
        if (!started) {
            return false
        }
        val u = boundUdp?.udp ?: return false
        return !u.isClosed && writerThread != null
    }

    /**
     * 2.11 / I13：**本次通话允不允许出现聚合包**。
     * I13 的原话是"聚合包只允许出现在 UDP / 媒体 TCP 路径；带内回退恒单帧一包"，
     * 而 §2.3 那张表里"聚合 3 帧 +40ms"在带内档是直接击穿 200ms 硬顶的那一项（S6）⇒
     * 带内档必须**恒关**，与 P5 那套"连续 2 次停发窗口才启用"的触发判据无关：
     * 触发判据归任务单 4（那边才有停发窗口的真实来源），本方法只是 I13 这道**硬闸**。
     * ⚠ 读 [mediaUsesMainTcp] 而不是 [tier]：理由与 2.12 的文件闸门同一条 ——
     *   "已定档 UDP/媒体 TCP、媒体却还在带内写"这段时间里，聚合也还没资格开。
     *   3.3 之前本方法**恒 false**（mediaUsesMainTcp() 恒 true）⇒ 今天没有任何消费者会因此改变行为，
     *   它是给 4.8 / 5.x 的聚合启用点准备的唯一读点（不许在别处再判一次 tier）。
     */
    fun aggregationAllowed(): Boolean = !mediaUsesMainTcp()

    /**
     * 2.9 的**前置闸门**（S28 的 ①）：本档到底有没有"100ms 反馈"这条通道。
     * ⚠ 判据是"媒体帧**实际**走在四档里"，不是"档已定成四档之一" —— 3.3 之前
     *   [mediaUsesMainTcp] 恒 true，所以本方法今天**恒 false** ⇒ 两条存活判定整体休眠
     *   （行为中性，与 2.5–2.8 同口径）。带内档没有反馈通道，照抄"1s 无反馈"就会每秒误报一次，
     *   所以这条闸门是判定的第一步，不是优化。
     */
    fun hasFeedbackChannel(): Boolean = !mediaUsesMainTcp() && tier != Tier.INBAND

    /**
     * 2.9 / S23：反馈帧的**唯一**计入口。生产者要到任务单 3.7（100ms 反馈帧的 reader）才存在，
     * 今天零调用者 —— 与"lat 恒 0"的区别是这里没有任何消费点在读一个假数字，
     * 判定被 [hasFeedbackChannel] 挡在门外）。3.7 落地时必须调这里，不许另立第二个计数。
     */
    fun noteFeedback() {
        feedbackRcvPackets++
        lastFeedbackAtMs = System.currentTimeMillis()
    }

    /**
     * 2.7 的收包计入口（与 [notePeerSource] 同一个调用点、成对被调）：
     * 只统计"从对端收到的媒体面的包"，喂给 S23 那一行的"本端收包计数"与 P10 的"双向 0 包"。
     */
    private fun notePeerMediaPacket() {
        localRcvPackets++
        peerLastRxAtMs = System.currentTimeMillis()
    }

    /**
     * 2.9：起存活检查线程。**只在定档采纳成有反馈通道的那四档时被调**（见两个调用点），
     * 带内档走不到那里 ⇒ 不存在"每条通话都挂一个空转线程"。
     *
     * 线程域：这条线程只做"读四个计数 + 打一行日志 + 回调上层"，
     * 不持任何数据域锁、不碰 socket（I3），也**绝不做**换档 / 换端口 / 重开探测（I17 ——
     * 2.5 的原 2.9 那条"1s 无反馈触发回退切换"已就地删除，本方法里搜不到任何切换调用）。
     * 退出条件三条，任一成立即退：[started]=false（[stop]）、被 `interrupt()`（同处）、
     * [hasFeedbackChannel] 变 false。daemon ⇒ 不拦进程退出。
     */
    private fun startAliveWatchdog() {
        if (aliveThread != null || !started || !hasFeedbackChannel()) {
            return
        }
        val t = Thread({ aliveLoop() }, "pp-av-alive")
        t.isDaemon = true
        aliveThread = t
        t.start()
    }

    /** [startAliveWatchdog] 的线程体。周期就是 S23 的那个 1s，不另设数字。 */
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
            // ---- S23：本端已发包、这一秒一条反馈都没收到 ⇒ 疑似中途被拦（只提示，不切换）----
            // ⚠ 前提"本通**收到过**反馈"（fb > 0）：S23 判的是"反馈本来在流、突然停了"，
            //   而反馈帧的消费者要到 3.7（reader 读 DG_TYPE_FEEDBACK）才存在；在那之前本通恒 0 条反馈，
            //   若不加这条前提，每一条 UDP 档通话都会稳定误报一次"疑似中途被拦"。
            //   3.7 落地后反馈开始到达 ⇒ 本判据自动生效，这里无需再改。
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
            // ---- P10：双向 0 包达 10s ⇒ 上报"无流"，挂断由状态机做 ----
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

    /**
     * 关存活线程：摘引用 + interrupt（[stop] 里调，与 releaseBound 一道构成本会话的收尾）。
     * 可重入：没起过（带内档 / 从未定档）就是空操作。
     */
    private fun shutdownAliveThread() {
        val t = aliveThread
        aliveThread = null
        t?.interrupt()
    }

    /**
     * 当前档。⚠ 只给模块内部与日志用：上层不许读它做分支（D3 —— 五档互换时通话层零改动）。
     */
    fun tier(): Tier = tier

    /**
     * 发一帧音频。
     *
     * @return 写入耗时 ms；-1 = 未发送（无会话 / 异常 / 未 start）。UDP 档自有 writer 下恒 0（非阻塞），
     *         语音码率自适应**不得**把这个 0 当拥塞信号（反馈才是判据）。
     */
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
        // 带内写者（INBAND / 媒体 TCP 档）：主 TCP 的既有链路，写锁、帧格式、128KB 闸门一概不变（I8 / I3 例外）。
        val took = link.sendAudioTo(s.peerIp, seq, ts, data, len)
        if (took >= 0L) {
            localSentPackets++   // 2.9：S23 的"本端已发 N 包"与 P10 的"本端这一秒有没有发"都读这个数
        }
        return took
    }

    /**
     * 出向音频队列的待写帧数（任务单 3.3 取证，与 PC AvTransport.pendingAudioDatagrams 同名同义）。
     * UDP 档下 [sendAudio] 只入队、真正的 socket 写在 pp-av-loop 这一个写点：写者一慢（异常/被视频挤），
     * 这里就无界堆积，对端听到的是「突然来一堆帧」，而本端所有收端计数器都不会动。
     * 带内 / 媒体 TCP 档不经过这条队列 ⇒ 恒 0，日志里照打，用来区分档位。
     */
    val pendingAudioDatagrams: Int get() = qAudio.size

    /** 发一帧视频（安卓是活跃路径；PC 侧同签名方法当前无调用者——R2 无采集）
     *  ⚠ UDP 档：按 ≤[DG_SLICE_MAX_PAYLOAD]B 切片入视频队列（帧内共用一个 videoStreamSeq），
     *    写者每 tick 最多发 [VIDEO_SLICES_PER_TICK] 片 ⇒ 大 IDR 不再被 128KB 闸门整帧丢（问题2 根因之一）。
     */
    fun sendVideo(seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int): Long {
        val s = spec
        if (!started || len <= 0 || s.peerIp.isEmpty()) return -1L
        if (udpEgressActive()) {
            val projected = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD
            if (qVideo.size + projected > VIDEO_QUEUE_MAX_SLICES) {
                // 队列硬顶（3.5 帧级在途窗口之前的粗护栏）：丢当前帧不入队，且必须计数
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

    /**
     * 发一份控制快照。⚠ 载体待接：3.13 落 ctrl 的 14B 线格式，
     * 带内档按 D14-3 退回主 TCP 既有帧。在那之前本方法不做任何事（也没有调用者）。
     */
    fun sendCtrl(ctrl: Ctrl) {
        // 3.13：ownerBits / epoch / seq 上线后由这里按当前档发出（媒体报或带内帧）
    }

    /**
     * 收帧入口：只允许 App 接线层（AppCore 的 Link.listener 转发）调用。
     * 模块**不做源地址过滤** —— 改前 PC 在 CallManager 里按 peerIp 过滤音频/视频、
     * 安卓音频同、安卓视频由 VideoCall 首源锁定；2.7 统一收进模块。
     */
    fun deliverAudio(fromIp: String, seq: Int, ts: Long, data: ByteArray, len: Int) {
        if (!started) return
        notePeerMediaPacket()   // 2.9：这是"从对端收到媒体包"的另一个入口（3.4 之后是主入口）
        sink.onAudio(fromIp, seq, ts, data, len)
    }

    fun deliverVideo(fromIp: String, seq: Int, ts: Long, flag: Int, data: ByteArray, len: Int) {
        if (!started) return
        notePeerMediaPacket()   // 2.9：同上
        sink.onVideo(fromIp, seq, ts, flag, data, len)
    }

    /**
     * 关会话。带内档没有端口可关（改前也是如此）；2.3 起这里会释放本会话已 bind 的
     * UDP socket / 媒体 TCP 监听（[Bound.release]，幂等）；
     * **2.6 起这里还关 ④ 预建并保持空闲的那条媒体 TCP**（[mediaTcp]）。
     * 2.6 自查项「预建连接在 挂断 / 异常 / 切后台 / **呼叫失败** 四条路径里都有关闭调用」成立的方式，
     * 就是那四条全部汇聚到本方法、而本方法只走 [releaseBound] 这**一个**出口（与 2.9 对齐）。
     * 2.5 起这里还负责**叫停在跑的定档时序**（振铃期用户就点挂断 / 无人接听超时）。
     * 2.9 的五条退出路径全部汇聚到本方法。
     */
    fun stop() {
        started = false
        // 定档时序可能还在 pp-av-negotiate 上跑（振铃期用户就点了挂断 / 无人接听超时）：
        // 先叫停，循环每一步都查这个标志；下面那次 releaseBound() 负责把此刻已 bind 的端口放掉。
        negotiationStopped = true
        spec = Spec.inband("")
        peerUdpPort = 0
        peerTcpPort = 0
        peerMsid = ""
        peerTierOrdinal = -1
        peerMediaTarget = null   // 2.7：实测收包源随本通一起作废（下一通重新学）
        inbandDowngraded = false
        tier = Tier.INBAND
        // 2.9：五个计数与两条闩锁一并作废（与 start 的归零成对 —— 残留值绝不会流到下一通的 S23 日志里）
        localSentPackets = 0L
        localRcvPackets = 0L
        feedbackRcvPackets = 0L
        peerLastRxAtMs = 0L
        lastFeedbackAtMs = 0L
        suspectedBlockedLatched = false
        bothQuietSinceMs = 0L
        mediaInboundAdmit = null   // 2.10：注入的准入判据随本通作废（下一通由 openTransport 重新配）
        // 3.2：残留半帧随本通作废（下一通重新建缓冲）；此处清空把未完成帧按失败计一次，
        //   与超时/溢出同口径 —— 挂断时正在凑的那几帧本就是"缺任一片整帧丢"。
        videoReassembler.clear()
        // 3.3：先关本端 UDP 媒体 IO（摘 writer/reader 引用 ⇒ sendAudio/sendVideo 立即回落带内、
        //   不再往将死的队列塞，下一通的 startMediaIo 幂等判据也不会被上一通的残留引用挡住），
        //   interrupt 叫醒 sleep 中的写者与阻塞中的 receive，再清四条队列。
        stopMediaIo()
        // 2.9 的硬要求"结束后记得关闭传输线程"：本会话的两条线程都在这里收 ——
        //   pp-av-negotiate：上面 negotiationStopped=true 叫停，循环每一步都查它（最迟本档窗口结束）；
        //   pp-av-alive：下面摘引用 + interrupt（睡眠中的线程立即退出，不等下一个 tick）。
        // ⚠ 五条退出路径（挂断 / 异常 / 对端消失 / 切后台销毁 / 呼叫失败）全部经 closeTransport 走到本方法，
        //   所以"关线程 + 关 UDP + 关预建与未选中的媒体 TCP + 释放已 bind 端口"只有这一个出口。
        shutdownAliveThread()
        // 2.3 + 2.6：释放本会话已 bind 的两侧端口与监听，以及预建那条连接。
        // 先摘引用再关 ⇒ 重复调用（2.9 的五条退出路径都会汇到这里、呼叫失败分支、以及定档时
        // 未采纳的那一侧）不会二次关闭出异常，Bound.release() 与 socket close 自身都是幂等的。
        releaseBound()
    }

    /**
     * 叫停在跑的定档时序（2.9 的挂断 / 取消 / 呼叫失败路径与 [stop] 共用）。
     * 幂等、不阻塞：只置标志，probe 循环最迟在本档窗口结束时退出。
     * ⚠ 本方法**不关**任何东西：2.9 的退出路径要么调 [stop]，要么当时序在标志上退出来拿结论时，
     *   由 [negotiate] 自己的 releaseBound() 收尾（含 2.6 的预建连接）。
     */
    fun stopNegotiation() {
        negotiationStopped = true
    }

    /**
     * 摘引用并释放本端持有的**全部**媒体资源：本档 UDP 侧、本档媒体 TCP 监听，
     * 以及 2.6 预建并保持空闲的那条 [mediaTcp]。
     * 时序里每一处"本档不通"、⑥⑦ 的落带内 / 呼叫失败，以及 stop() 都走这一个出口。
     */
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
