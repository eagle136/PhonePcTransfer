package com.phonepctransfer.pc;

// ==================================================================
// 媒体传输模块（任务单 2.1 骨架 · 方案 D1/D2/D3/D8）
//
// 对上只暴露这一套（D3）：
//   start(spec) / sendAudio(...) / sendVideo(...) / sendCtrl(state) / stop()
//   回调 onAudio / onVideo / onCtrl / onStats
// 通话层（CallManager / VoiceEngine / 视频半）**只调这套接口**，
// 底下是五档里的哪一档对上层透明，且**通话中途永不变**（I17）。
//
// 数据报头（8B）、ctrl 的 14B 快照布局、发送窗口、100ms 反馈、超龄丢弃都在**任务单 3**；
// 端口 bind 的"怎么 bind"在 **2.3**（bindTier / Bound / stop() 的释放），
// "什么时候试哪一档"在 **2.5**（振铃期定档时序），媒体 TCP 预建与"未选中即关"在 **2.6**
// —— 2.6 已落：一轮 = 同一端口族的 UDP 与 TCP 两侧（roundsForThisEnd / bindRound），
// UDP 侧不通就在**同一轮**里进 ④，本档没被选中的那条（以及定档后已空转的 TCP 监听）立即关，
// 预建好的那条媒体 TCP 连接留在 mediaTcp 保持空闲（D11），由任务单 3.3 的 writer 直接接手。
// 2.5 已把七步时序接进本类。**任务单 3.3 起媒体帧的实际承载按档分两条**：定档采纳 UDP 档 ⇒ 起
// pp-av-loop（唯一 writer）+ pp-av-recv（reader），audio/video_slice 真走 UDP socket（见
// {@link #mediaUsesMainTcp()} 的 {@link #udpEgressActive()} 判据）；媒体 TCP 档的自有 writer **本轮未落**
// ⇒ 本方法对 TCP 档仍 true（媒体仍带内，与 3.3 改前一致），带内档复用主 TCP。上层一行不用改（D3）。
//
// 控制面现状（如实写，别当成已接好）：
//   今天带内的控制帧是 video_cfg / video_req、反馈帧是 video_fb，仍走 Link 既有路径，
//   由 任务单 3.13（ctrl 快照 + owner 判权 + epoch/seq 去重）与 D12（媒体通道起来后
//   停用 2s video_fb、改 100ms 反馈）迁移进来。sendCtrl/onCtrl/onStats 是**声明**，
//   当前无生产者/消费者 —— 与"lat 恒 0"的区别是这里没有任何消费点在读一个假数字，
//   验收门也不读它们（任务单 6 读的是 1.7 的计数器与 1.2 的分段行）。
//
// 线程域（D2 · 任务单 2.2）：
//   pp-av-audio —— 已落：音频解码 + 写播放设备（VoiceEngine.playLoop）。单列的原因是
//     SourceDataLine.write 在设备缓冲满时必然阻塞，不能并进媒体主循环；daemon，
//     退出条件 = playRunning 置 false + stopPlay 里 stop/close 播放线（让阻塞的 write 立即返回），
//     双端无 join。
//   pp-av-loop —— 3.3 已建线程体（{@link #avWriteLoop}）：唯一 writer，每 {@link #SEND_TICK_MS}=20ms 一个
//     tick，严格优先级排空（音频→控制→反馈→视频≤{@link #VIDEO_SLICES_PER_TICK}片）。周期与视频预算两个数
//     由用户 2026-10-03 定（方案 §4.5 只写"每 tick / 单 tick 预算"没给数字）。收帧分发在姊妹线程 pp-av-recv
//     （{@link #avRecvLoop}）。"停发窗口 / 超龄丢弃 / 100ms 反馈 / ctrl 合并窗双发"仍归 3.5–3.14，本条未做。
//     ⚠ 当前仅 UDP 档有自有 writer；媒体 TCP 档 writer 未落，其媒体帧仍走带内（见上）。
//   保留原名（D2 明确"既有相机 / GL / 编码取帧 / ffmpeg 管道线程保留"）：
//     pp-voice-rec（采集+编码）、pp-call-audio（编码取帧发送循环）、
//     pp-video-feed / -read / -err / -kill（ffmpeg 管道）、pp-video-fb（反馈，3 里迁）。
//   锁纪律（I3）：媒体域与数据域**零共享锁**。本类现在唯一的写锁接触点就是 INBAND 一档：
//     sendAudio/sendVideo 直接转 Link.sendAudioTo/sendVideoTo → Session.sendAudio(:812)
//     /sendVideo(:832) 的 writeLock —— 这是 I3 的**书面唯一例外**（改前同一条锁，未新增）。
//     2.3–2.6 起 UDP / 媒体 TCP 分支里**不许**出现 writeLock；自查 = grep 本文件 writeLock，
//     只允许命中注释与本句，不许命中任何代码行。
// ==================================================================
public final class AvTransport {

    /**
     * 五档（方案 §4.1 / D8）。**声明顺序即定档顺序**，试档按 ①→⑤ 推进。
     * ⚠ 全工程只此一处定义档位序列，2.3 的端口选择与 2.5 的定档时序都必须读这里，
     * 不得在 CallManager 里再存一份。
     * ⚠ 2.6（待点 ⑧ 点 (a)「并档」）之后，**时序里跑的"一档"= 同一端口族的 UDP 与 TCP 两侧**
     * （①/②/③ 与 ④ 在同一轮内连着做，见 {@link #roundsForThisEnd()}）；本枚举仍是 §4.1 那五条
     * **通道**，一条没合并，声明序在这里的作用变成"同一轮内 UDP 侧先于 TCP 侧"。
     */
    public enum Tier {
        UDP_443,         // ① UDP 443（安卓无 root ⇒ 本档整体跳过，不是"试了失败"）
        UDP_RANDOM,      // ② UDP 随机 4000–50000
        TCP_443,         // ③ 媒体 TCP 443（安卓同①跳过）
        TCP_RANDOM,      // ④ 媒体 TCP 随机 4000–50000
        INBAND           // ⑤ 消息端口带内（主 TCP，受 D16 通话期文件排他约束）
    }

    // ---- 任务单 2.3：端口选择（方案 §4.1 / U11①，第 5 轮定稿）----

    /** 随机档区间，含端点（§4.1：4000–50000）。 */
    public static final int MEDIA_PORT_MIN = 4000;
    public static final int MEDIA_PORT_MAX = 50000;

    /**
     * 避让清单：**照方案 §4.1（:345–346）与本单 2.3 逐项、按原序抄写**，17 项，双端必须一字不差。
     * 28765 / 28766 是本程序自己的消息端口与发现端口（Protocol.java:28–29）；
     * 1900 = SSDP/UPnP 占它（§4.6，与"不申请 UPnP 映射"互相印证）；5353 / 5357 = mDNS / WS-Discovery。
     * ⚠ 3000 3128 1900 3389 四项落在 4000 以下，随机档实际撞不到（区间内 13 项）；
     *   留着是为了让这份清单与方案一一对得上，不要"顺手精简"。
     */
    public static final int[] PORT_AVOID = {
            8080, 8000, 8008, 8009, 8888, 3000, 3128, 5000, 5001, 5353,
            5357, 1900, 3389, 7000, 9000, 28765, 28766
    };

    /**
     * 每档随机端口最多实试 8 次；仍失败退系统分配（port 0）；再失败 = 本档不成立，进下一档。
     * ⚠ 2.6 待点 ⑧ 点 (a)：这个 8 是**每种协议各 8 次**（同一轮里 UDP 侧与 TCP 侧各自数满 8，
     * 两侧再各有一次"系统分配"退路），不是整档合计 8 —— 所以随机档一轮最多 16 次真实 bind。
     */
    public static final int BIND_MAX_ATTEMPTS = 8;

    /** 443 档的端口号。⚠ 这个数只在本类的 bindTier 两个权限档分支里用，档位序列仍只有 {@link Tier} 一份。 */
    public static final int PRIVILEGED_MEDIA_PORT = 443;

    /**
     * 本端要试的档序列。PC：五个全试（§4.1 "先 443，再随机"）—— 安卓那一端在这一步
     * 就把两个权限档滤掉（安卓侧 tiersForThisEnd 有 isPrivileged 过滤，本端没有，
     * 这是 §4.1 明文要求的**唯一**一处结构差异）。
     * ⚠ 序列一律从 {@link Tier#values()} 推出来 —— 不许在别处再存一份档位表（2.3 硬自查）。
     */
    public static java.util.List<Tier> tiersForThisEnd() {
        return java.util.Arrays.asList(Tier.values());
    }

    /** 本档是否 UDP 档（probe 发数据报）；false = 媒体 TCP 档（试一条连接）。2.5 的时序按这个分两条支。 */
    public static boolean isUdp(Tier t) {
        return t == Tier.UDP_443 || t == Tier.UDP_RANDOM;
    }

    // ---- 任务单 2.6：一轮 = 同一端口族的 UDP 与 TCP 两侧（D11 / S11，2026-10-02 待点 ⑧ 点 (a)）----

    /**
     * 定档时序里的一**轮**：§4.1 同一端口族（443 或随机）的 UDP 档与媒体 TCP 档。
     * 轮由 UDP 档领着 ⇒ {@link #udpTier} 恒非 null；{@link #tcpTier} 可为 null，含义是
     * "同族的 TCP 档不在本端的档位序列里"。今天两端都不会走到那个 null：§4.1 的过滤是**整族**滤的
     * （安卓把两个 443 档一起滤掉，剩下的随机族两侧都在）。
     * ⚠ 这里只是**把 §4.1 的两条通道配成一对**，不新增端口族、不新增数字：
     *   bind 仍走 2.3 的 bindTier（每侧各自 8 次），探测仍走 2.5 的 ②③ 与 ④，窗口仍是那一个 300ms。
     */
    private static final class Round {
        final Tier udpTier;
        final Tier tcpTier;

        Round(Tier udpTier, Tier tcpTier) {
            this.udpTier = udpTier;
            this.tcpTier = tcpTier;
        }
    }

    /**
     * 本端的轮序列 = {@link #tiersForThisEnd()} 里每条 UDP 档配上同族的 TCP 档（有则配，没有则 null）。
     * ⚠ 仍是从那**一份**档位序列推出来的（2.3 的硬自查："全工程只此一处定义档位序列"
     * 到 2.6 读作"轮也必须从这里推，不许在别处再存一份端口族表"）：
     * PC = 〔443 族，随机族〕两轮，安卓 = 〔随机族〕一轮（443 族整族被滤掉），带内不进轮序列（它是 ⑥ 那条退路）。
     */
    private static java.util.List<Round> roundsForThisEnd() {
        java.util.List<Tier> tiers = tiersForThisEnd();
        java.util.List<Round> rounds = new java.util.ArrayList<>();
        for (Tier t : tiers) {
            if (!isUdp(t)) {
                continue;   // TCP 档由它那一族的 UDP 档领走
            }
            Tier fam = t == Tier.UDP_443 ? Tier.TCP_443 : Tier.TCP_RANDOM;
            rounds.add(new Round(t, tiers.contains(fam) ? fam : null));
        }
        return rounds;
    }

    // ---- 任务单 2.5：振铃期探测与定档时序（方案 §4.3 / U11②⑥，第 5 轮口径）----
    // 三个数**全部来自 §4.3**（②的间隔与条数、③的判定窗），本条不新增任何数字。
    // 2.6（并档）也没有新增数字：④ 的 connect / accept 超时用的还是下面这一个 PROBE_WINDOW_MS。

    /** §4.3②：拿到对端本档端口后各发 3 个 probe。 */
    public static final int PROBE_COUNT = 3;

    /** §4.3②：probe 间隔 30ms。 */
    public static final long PROBE_INTERVAL_MS = 30L;

    /**
     * §4.3③：300ms 判定窗。⚠ 同一个窗口还兼作 §4.3①「交换三元组」的等待窗
     * —— 方案只给了这一个数，对端本档端口没在窗口里广告出来就按本档不通处理（不另设等待时长）。
     * 2.6 起 ④ 的媒体 TCP connect / accept 超时也是这一个数（同一轮里 ②③ 与 ④ 各占一个窗口）。
     */
    public static final long PROBE_WINDOW_MS = 300L;

    // ---- 任务单 3.2：视频分片与重组（方案 §4.4 / §3-3.2，第 5 轮口径）----
    // 单帧 AU 若 >1200B 会被 IP 分片、一个 IP 碎片的 MTU 丢包就会带走整帧 ⇒ 视频按 ≤1200B 切片；
    // 音频 20ms@64k≈175B / 3 帧聚合≈440B、ctrl ≤64B 都在单报之下 ⇒ 都不分片。
    // 这两个数是**从 §4.4 推出来的**（DG_OVERHEAD=12 已由 3.1 定死），不是新增的第三个数字。

    /** §4.4：单报（含 12B 头+ts）≤1200B，避免 IP 分片。 */
    public static final int DG_MAX_REPORT = 1200;

    /** 一份视频分片能装的 AU 正文上限 = 1200 − DG_OVERHEAD(12) = 1188B（§4.4 推导，非新增数字）。 */
    public static final int DG_SLICE_MAX_PAYLOAD = DG_MAX_REPORT - Protocol.DG_OVERHEAD;

    /**
     * 媒体 UDP 的**内核**收发缓冲（字节）。按最坏单帧突发反推：实测 1080p 关键帧 AU 最大 143590B
     * = 121 片 = 145KB，而 Windows/JDK 的 DatagramSocket 默认 SO_RCVBUF 只有 65536B（实测，装 54 片）；
     * 发端队列上限 {@code VIDEO_QUEUE_MAX_SLICES}=512 片 ⇒ 理论最坏突发 594KB，1MB 仍有一倍余量。
     * 详见 {@link #tryBindUdp} 的口径与 plan 第 9 节的 A/B 数字。
     */
    static final int UDP_BUFFER_BYTES = 1 << 20;

    /**
     * 一个 AU 从**末片到达**起最多等多久凑齐，超时整帧丢（基准 2026-10-03 由用户点 A：首片→末片）。
     *
     * ⚠ 历史：原 §3-3.2 的 10ms 与发端"每 tick 最多 16 片"在数学上互斥 —— 片数 &gt;16 的 AU 至少要跨
     *   1 个 20ms tick 才能排完，而 10ms 窗在它收到第二 tick 的片之前就已经把整帧删掉 ⇒
     *   **任何 &gt;19KB 的 AU 永远凑不齐**（实测 {@code videoReassembleFail=169}、关键帧一个都没到位）。
     *   点到 40ms 后配 64 片/tick 覆盖 ≤128 片（≈152KB）的 AU，再大的单帧仍会超窗整帧丢。
     * ⚠ 2026-10-03 17:01 那通 73.5s 实测把这条边界照出来了：被判死的 20 个帧片数 **76–202 片**
     *   （其中 15 个是关键帧），扫掉瞬间的到手片数最多的两个值就是 **64 和 128**（= tick 预算的整数倍），
     *   而 {@code videoSliceLate=874} / {@code videoSliceMissing=3125} ⇒ 缺的片里 28% 后到了（迟到环只有
     *   64 格，这是下界）——**片在路上，是窗先把它判死**，与信号、与内核缓冲都无关（{@code rcv=1048576B}
     *   当场可见）。后果：丢 IDR ⇒ 整 GOP 一帧不出 ⇒ 定格 ~1s（日志里那四根 {@code vOut max}
     *   1248/1295/1354/1552ms 就是"隔一段时间爆卡一下"）。
     * ⚠ 改成末片起算后，窗值**不变**（还是 40ms），变的是"40ms 里没来新片才算死"：一帧只要还在进片
     *   就继续等 ⇒ 大 IDR 不再被自己的发送节奏判死。代价如实写：真丢片时判死时刻后移，最坏
     *   = 窗值 + 一片的间隔；判死后仍然走同一条 {@code dropUntilKey} + {@code video_req} 恢复路径。
     * 与安卓同值同序。
     */
    public static final long REORDER_WINDOW_MS = 40L;

    /**
     * 重组缓冲并发帧上限（机制性上界，不是延迟口径）：@30fps、40ms 窗 ⇒ 同时在途 ≤2 帧，
     * 给 16 足够；命中即丢**最旧帧**并计 {@code videoReassembleFail} ⇒ 半帧绝不会长期占内存
     * （任务单 3.2「缓冲有上限且超时必清」）。16 是为"缓冲不无限涨"设的结构上限，非 QoS 数字。
     */
    public static final int REASSEMBLE_MAX_FRAMES = 16;

    // ---- 任务单 3.3/3.4：单写者的节拍（2026-10-03 用户点：tick 20ms 不变、视频单 tick 16→64→256 片）----

    /**
     * pp-av-loop 的发送 tick = 20ms（对齐一个音频包周期）。3.3 的写者与 3.4 的定时发送共用这一个数。
     * ⚠ 这是方案 §4.5 只写"每 tick"没给数字处，由用户 2026-10-03 定的（不是我从码里推的）。
     */
    public static final long SEND_TICK_MS = 20L;

    /**
     * 视频单 tick 发送预算 = 256 片（2026-10-03 用户点 C：64→256，按片数、非字节/帧）。
     * 定这个数的依据是 17:01 那通的实测分布：被判死的帧片数 **76–202 片**，最大 202 片（≈234KB）
     * ⇒ 按 64 片/tick 要 4 个 tick = **60–80ms** 才发得完，而收端窗只有 40ms ⇒ 这类关键帧**结构性凑不齐**。
     * 256 = 覆盖实测最大 202 片的一个 tick ⇒ 一个整 IDR 一次突发发完（20ms），A 方案的窗基准只是它的兜底。
     * ⚠ 每 tick 仍旧音频先排空、控制/反馈插在片之间（§4.5 / S16），所以音频最坏被挡的时间没变
     *   （还是一个 tick = 20ms），变的只是每 tick 的突发量：256×1200B = **307,200B**。
     * ⚠实测：这个突发必须落在发端 SO_SNDBUF 里 —— PC 侧 {@link #UDP_BUFFER_BYTES}=1MB（T1，生效值已打日志）；
     *   安卓侧同样在 bind 前请求 1MB（T2），**它的生效值本轮还没读到**（17:01 那段 PP-AvTransport 已被
     *   logcat 环挤掉），验收单里必须看到手机侧那行 `缓冲 snd=…B` 才能确认这条路成立。
     * 队列深度上限见 {@link #VIDEO_QUEUE_MAX_SLICES}（512 片，256/tick 下 = 两个 tick 的排空量）。
     */
    public static final int VIDEO_SLICES_PER_TICK = 256;

    /**
     * 视频在途片队列上限（结构上界，防内存无限涨）：满则**丢当前帧不入队**并计 {@code videoDroppedByGate}。
     * ⚠ 这是"排队不等于把延迟转移到自己身上"（3.5 停发窗口）之前的粗护栏：@16片/tick、20ms ⇒ 稳态在途≈320片，
     *   给 3.5 的帧级窗口留出余量。真正按帧的在途窗口归 3.5，本常量只是队列尺寸的硬顶，不是 QoS 数。
     */
    public static final int VIDEO_QUEUE_MAX_SLICES = 512;

    /**
     * 把一个 AU 按 ≤{@link #DG_SLICE_MAX_PAYLOAD}B 切成若干 video_slice 报（§4.4，双端同字节）。
     *
     * ⚠ **帧内每片共用同一个 {@code streamSeq}**（一次 getAndIncrement 领一个数）：这样
     *   ① 重组可按 streamSeq 分帧；② 3.6 的 loss% 与 3.5 的"视频 4 帧"窗口都成立在**帧**粒度上
     *   （若按片各发一个 seq，一个 60 片的大 IDR 会被当成 60 帧、窗口瞬间打满）。
     *   —— 这是方案只写"按 type 独立计数"、没写"按帧还是按片"处**我的推论**（与 S2/3.5 对齐），
     *      标出来等你点；改法只需把下面这一处序号领取改回片粒度，重组器仍按 streamSeq 分帧。
     * ⚠ flag 原样透传（IDR/CONFIG），末片再置 {@code DG_FLAG_LAST_SLICE} 标记帧边界（§4.4 [3]bit2）。
     * ⚠ 本方法只产字节：真正的写 socket 在 **3.3 的 writer**（唯一写点，I3 纪律）。
     *
     * @return 该 AU 的切片总数（0 = len≤0 空切片，调用方按不发送处理）
     */
    public static int sliceVideoAu(int streamSeq, int flag, byte[] au, int off, int len, long ts,
                                   SliceSink out) throws java.io.IOException {
        if (len <= 0) {
            return 0;
        }
        int count = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD;
        for (int i = 0; i < count; i++) {
            int so = off + i * DG_SLICE_MAX_PAYLOAD;
            int sl = Math.min(DG_SLICE_MAX_PAYLOAD, off + len - so);
            int sliceFlag = (flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG))
                    | (i == count - 1 ? Protocol.DG_FLAG_LAST_SLICE : 0);
            byte[] report = Protocol.encodeDatagram(Protocol.DG_TYPE_VIDEO_SLICE,
                    streamSeq, sliceFlag, i, count, ts, au, so, sl);
            out.slice(i, count, report);
        }
        return count;
    }

    /** {@link #sliceVideoAu} 的逐片出口（3.3 的 writer 实现它，把 report 写进 socket）。 */
    public interface SliceSink {
        void slice(int idx, int count, byte[] report) throws java.io.IOException;
    }

    /**
     * 一帧重组完成后交给消费者的 AU（§4.4：IDR/CONFIG 位原样带出，供 3.9 / FfmpegDecoder 判关键帧）。
     */
    public static final class PendingAu {
        public final int flag;
        /** 发送端通话相对 ms（取该帧首片的 dg.ts），交 sink.onVideo 的 ts 位。 */
        public final long ts;
        public final byte[] data;
        public final int len;

        PendingAu(int flag, long ts, byte[] data, int len) {
            this.flag = flag;
            this.ts = ts;
            this.data = data;
            this.len = len;
        }
    }

    /**
     * 任务单 3.2 的视频切片重组器（方案 §3-3.2「缺任一片整帧丢；超窗整帧丢」——
     * 窗值 2026-10-03 由用户从 10ms 点成 40ms，理由见 {@link #REORDER_WINDOW_MS}）。
     *
     * ⚠ 键 = {@code dg.streamSeq}：**帧内每片共用同一个 streamSeq**（见 {@link #sliceVideoAu} 的推论），
     *   所以一帧 = 一个 streamSeq 桶，重组按帧聚合、"视频 4 帧"窗口（3.5）也成立在帧粒度上。
     *   这条推论若被否，本类无需改（键仍是 streamSeq），只改发送侧序号领取粒度。
     * ⚠ **消费者是 3.4 的 reader**（收到 video_slice 报调 {@link #add}）；本条此刻零调用者，
     *   与 3.1 的编解码同形（有意边界，见任务单 3 块"本条零调用者"）。
     * ⚠ 并发：UDP 侧单 reader 线程串行调用；媒体 TCP 侧 writer/reader 也各在单线程域。
     *   {@code ConcurrentHashMap} 只为与 S23 存活线程读 {@link #size} 的可见性，不构成跨域锁（I3：
     *   本类**不碰** writeLock）。
     */
    public static final class VideoReassembler {

        private static final class Frame {
            final int count;
            final byte[][] parts;
            final boolean[] have;
            final int flag;
            final long ts;
            final long firstRxAtMs;
            /**
             * 最近一片到达的时刻（A 方案，2026-10-03）：超窗判据用它，不再用 {@link #firstRxAtMs}。
             * 不加 volatile：投片与扫窗都在 reader 单线程域（写点在 {@code synchronized(f)} 内），
             * 与同类里既有的 {@code got} 同构（安卓侧字段声明也一致）。
             */
            long lastRxAtMs;
            int got;

            Frame(int count, int flag, long ts, long firstRxAtMs) {
                this.count = count;
                this.parts = new byte[count][];
                this.have = new boolean[count];
                this.flag = flag;
                this.ts = ts;
                this.firstRxAtMs = firstRxAtMs;
                this.lastRxAtMs = firstRxAtMs;
            }
        }

        private final java.util.concurrent.ConcurrentHashMap<Integer, Frame> frames =
                new java.util.concurrent.ConcurrentHashMap<>();

        /** 当前并发重组中的帧数（供缓冲上限判定与 S23 观测）。 */
        public int size() {
            return frames.size();
        }

        /**
         * 半帧被丢（超窗 / 超上限）时的通报。⚠ 语义是**参考链已断**，不是"丢了一帧画面"：
         * 缺的若是 IDR，之后整 GOP 在解码器里一帧都出不来（10-03 实测 17 个半帧带走 172 帧、
         * 每丢一个 IDR 静止 ~0.97s）⇒ 上层要立刻停止喂死链帧并要 IDR，而不是等下一个自然 IDR。
         */
        public interface LossListener {
            void onFrameLost(int seq, int missing, int count, boolean key);
        }

        private volatile LossListener lossListener;

        // ---- T4 ② 的取证环：最近被扫掉的半帧 seq（容量 = 修复单 T4 里点的 64）----
        // 用途只有一个：判断"缺的片后来到了没有"。
        //   late≈0 且 videoSliceMissing 大 ⇒ 片根本没到本端（10-03 实测的内核缓冲丢片）；
        //   late 与 missing 同量级 ⇒ 片到了、但晚于 40ms 窗 ⇒ 那才是 REORDER_WINDOW_MS 的事。
        // 常态零成本：没扫掉过任何半帧时 {@code sweptCount==0}，每次投片只是一次 int 比较。
        private static final int SWEPT_SEQ_RING = 64;
        private final int[] sweptSeqs = new int[SWEPT_SEQ_RING];
        private int sweptNext;
        private int sweptCount;

        private void rememberSwept(int seq) {
            sweptSeqs[sweptNext] = seq;
            sweptNext = (sweptNext + 1) % SWEPT_SEQ_RING;
            if (sweptCount < SWEPT_SEQ_RING) {
                sweptCount++;
            }
        }

        /**
         * 这个 seq 最近被扫掉过吗（= 现在来的这片是**窗后迟到的片**）。
         * ⚠ 按**片**计一次（不是按帧）：与 {@code videoSliceMissing} 的片粒度同尺，两个数才能直接比大小。
         * 不做"命中即忘掉"：环满自然淘汰（64 个半帧 ≈ 2s 的记忆），代价是一片迟到的半帧最多计 (count-got) 次，
         * 而那正好就是"迟到的片数"，口径自洽。
         */
        private boolean isSweptRecently(int seq) {
            for (int i = 0; i < sweptCount; i++) {
                if (sweptSeqs[i] == seq) {
                    return true;
                }
            }
            return false;
        }

        /** 唯一接线点 = {@link AvTransport} 的构造（转成 {@link Sink#onVideoChainBroken}）。 */
        public void setLossListener(LossListener l) {
            lossListener = l;
        }

        /**
         * 丢帧处置的统一出口：三条取证计数 + 记入迟到环 + 一行日志，再把事实通报给上层。
         * 本方法只在 reader 线程调（{@link #sweepTimeoutMs} / {@link #evictOverflow} 的同一个循环里）。
         *
         * @param why 只为日志可读：{@code "超时"}（超 40ms 窗）或 {@code "超上限"}（重组缓冲满）
         */
        private void reportLost(int seq, Frame f, String why) {
            int missing = f.count - f.got;
            boolean key = (f.flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG)) != 0;
            CallCounters.get().videoReassembleFail.inc();
            // 取证三条（10-03）：半帧的**片数**、以及它是不是参考链起点。
            // 与 videoReassembleFail（半帧**个数**）并读才能分诊：
            //   missing 大 + late≈0 ⇒ 片根本没到内核缓冲（收端缓冲/网络丢）；
            //   missing 小 + late 大 ⇒ 片到了但晚于窗口 ⇒ 才是 REORDER_WINDOW_MS 的事。
            CallCounters.get().videoSliceMissing.add(missing);
            if (key) {
                CallCounters.get().videoKeyframeLost.inc();
            }
            rememberSwept(seq);
            // 逐事件一行：正常通话**整通不出现这条**，出现即缺片。与汇总行的分工——
            // 这行给"哪一帧、缺几片、是不是关键帧、等了多久"，挂断那行只给总量。
            // A 方案后判死看**末片至今**（≤ 窗值 + 一次扫窗间隔），首片至今只是这帧总共拖了多久。
            long nowMs = System.currentTimeMillis();
            long lastRx = f.lastRxAtMs;
            Log.w("[视频重组] 半帧" + why + " 缺 " + missing + "/" + f.count + " 片"
                    + (key ? "（关键帧）" : "") + " 到手 " + f.got + " 片"
                    + " 末片至今 " + (nowMs - lastRx) + "ms 首片至今 " + (nowMs - f.firstRxAtMs) + "ms"
                    + " seq=" + seq);
            LossListener l = lossListener;
            if (l != null) {
                l.onFrameLost(seq, missing, f.count, key);
            }
        }

        /**
         * 投一片。返回：非 null = 该帧**刚好凑齐**、交出的整帧 AU；null = 帧尚未完整 / 被去重丢弃。
         * 畸形报（type 不是 video_slice、idx≥count、count<1）由调用方（reader）先按 {@code decodeDatagram}
         * 抛出丢弃；本方法只处理形状合法的业务片。
         * ⚠ 副作用（T4 ②）：这个 seq 若在迟到环里（最近被扫掉过）⇒ {@code videoSliceLate}+1，
         *   除计数外**不改任何行为**（片照常入新桶；新桶反正凑不齐，会被再次扫掉并再计一次 missing）。
         *
         * @param nowMs 本端单调接收时刻（epoch ms），用于新建桶的首片时刻与超窗基准（末片起算）
         */
        public PendingAu add(Protocol.Datagram dg, long nowMs) {
            int seq = dg.streamSeq;
            int count = dg.sliceCount;
            int idx = dg.sliceIdx;
            Frame f = frames.compute(seq, (k, cur) -> {
                if (cur == null) {
                    // 新建时按 count 预算（count 已由 encode/decode 的形状校验保证 ≥1 且 idx<count）
                    return new Frame(count, dg.flags, dg.ts, nowMs);
                }
                return cur;
            });
            // 同帧的 count / flag 必须一致：count 不符 = 串帧（老 seq 复用），直接丢这一片不污染缓冲。
            if (f.count != count) {
                return null;
            }
            // T4 ②：这个 seq 最近被扫掉过 ⇒ 本片是"窗后迟到的片"，按**片**计一次。
            // 常态零成本：环空时这里只是一次 int 比较（没缺过片就完全不扫环）。
            if (isSweptRecently(seq)) {
                CallCounters.get().videoSliceLate.inc();
            }
            synchronized (f) {
                if (f.have[idx]) {
                    return null;   // 去重：同片重复到达直接忽略（不计失败）
                }
                byte[] part = new byte[dg.len];
                System.arraycopy(dg.payload, 0, part, 0, dg.len);
                f.parts[idx] = part;
                f.have[idx] = true;
                f.got++;
                // A 方案：每来一片就把超窗基准往后推（判据见 {@link #sweepTimeoutMs}）。
                f.lastRxAtMs = nowMs;
                if (f.got < f.count) {
                    return null;   // 尚未凑齐
                }
                // 凑齐：按片序拼成整帧 AU。
                int total = 0;
                for (int i = 0; i < f.count; i++) {
                    total += f.parts[i].length;
                }
                byte[] au = new byte[total];
                int o = 0;
                for (int i = 0; i < f.count; i++) {
                    System.arraycopy(f.parts[i], 0, au, o, f.parts[i].length);
                    o += f.parts[i].length;
                }
                frames.remove(seq);
                // ⚠ 交付的 flag 只保留与带内帧制**同义**的那两位（IDR / CONFIG）：
                //   bit2 是数据报专有的 LAST_SLICE，单片帧时会落在首片上，带出去的 flag
                //   不该混着另一套帧制的位（下游都按位与取值，行为不变，但不剥掉就是
                //   下一个"两套真相"的苗头）。
                return new PendingAu(f.flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG),
                        f.ts, au, total);
            }
        }

        /**
         * 超龄整帧丢（§3-3.2「缺任一片整帧丢，超窗整帧丢」；窗值见 {@link #REORDER_WINDOW_MS}，
         * 2026-10-03 用户点 10ms→40ms）：从**末片到达**起算（A 方案，同日改，理由见
         * {@link #REORDER_WINDOW_MS} 的口径块），超过 {@link #REORDER_WINDOW_MS} 仍没等到新片、
         * 又没凑齐的帧直接删并计一次 {@code videoReassembleFail}（连同 T4 的三条取证计数与一行日志，
         * 都走 {@link #reportLost}）。
         * 由 reader 每收一批片后调一次（3.4）；不另起定时器（3.14「无周期心跳」同口径）。
         */
        public void sweepTimeoutMs(long nowMs) {
            for (java.util.Map.Entry<Integer, Frame> e : frames.entrySet()) {
                Frame f = e.getValue();
                if (nowMs - f.lastRxAtMs > REORDER_WINDOW_MS) {
                    // remove 返回非 null 才计，避免并发下重复计数
                    if (frames.remove(e.getKey()) != null) {
                        reportLost(e.getKey(), f, "超时");
                    }
                }
            }
        }

        /**
         * 缓冲上限（§3-3.2「重组缓冲有上限且超时必清，不会因半帧长期占内存」）：超过
         * {@link #REASSEMBLE_MAX_FRAMES} ⇒ 丢**最旧帧**（首片时刻最早者）并计数。
         * ⚠ CONFIG/IDR 帧不参与丢弃（任务单 3.2「CONFIG/参数集帧不参与丢弃」是本仓库既有先例）：
         *   遇最旧是关键帧时，跳过它去丢次旧的非关键帧；全缓冲都是关键帧则本轮不丢（宁可短窗超限，
         *   也不丢参考链起点）。
         */
        public void evictOverflow() {
            while (frames.size() > REASSEMBLE_MAX_FRAMES) {
                Integer victim = oldestEvictable();
                if (victim == null) {
                    return;   // 全是 CONFIG/IDR ⇒ 不丢
                }
                Frame victimFrame = frames.remove(victim);
                if (victimFrame != null) {
                    reportLost(victim, victimFrame, "超上限");
                }
            }
        }

        private Integer oldestEvictable() {
            Integer best = null;
            long bestAt = Long.MAX_VALUE;
            for (java.util.Map.Entry<Integer, Frame> e : frames.entrySet()) {
                Frame f = e.getValue();
                if ((f.flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG)) != 0) {
                    continue;   // 关键帧 / 参数集不参与丢弃
                }
                if (f.firstRxAtMs < bestAt) {
                    bestAt = f.firstRxAtMs;
                    best = e.getKey();
                }
            }
            return best;
        }

        /** 会话结束 / 定档切换时清空缓冲（幂等）。残留半帧按失败计，与超时同口径。 */
        public void clear() {
            int n = frames.size();
            frames.clear();
            // 迟到环随本通作废：下一通不该拿上一通扫掉的 seq 来判"这片迟到"
            sweptNext = 0;
            sweptCount = 0;
            for (int i = 0; i < n; i++) {
                CallCounters.get().videoReassembleFail.inc();
            }
            // ⚠ 这里**不**走 reportLost：挂断残留不是"通话期丢片"的证据，
            //   给它加 videoSliceMissing / videoKeyframeLost 会把验收数字污染成恒非零；
            //   更不许在这里触发关键帧请求（onVideoChainBroken → 解码器要 IDR，而对象已经在拆）。
        }
    }

    // ---- 任务单 2.9：通话期存活判定（方案 S23 / P10）。两个数都来自方案原文，本条不新增数字。----

    /**
     * S23：本端已发包、但**这一秒内一条反馈都没收到** ⇒ 记一行 {@code mediaSuspectedBlocked}
     * （日志 + 计数 + UI 提示），**不回退、不换端口、不重开探测**（I17）。
     * ⚠ 这个数同时就是存活线程的检查周期 ⇒ "1s 判据"与"每秒查一次"共用一个常数，不另设 tick 周期
     *   （任务单 2 待点 ① 说的那个"主循环周期"仍然没有数，本条不是主循环）。
     */
    public static final long FEEDBACK_SUSPECT_MS = 1_000L;

    /**
     * P10（2026-10-02 用户认可的默认处置）：通话**中途**媒体通道彻底无流（10s 双向 0 包）
     * ⇒ 自动挂断 + 提示"呼叫中断"。本模块只**上报**，挂断由通话状态机做（媒体存活由它决定，I14）。
     */
    public static final long NO_STREAM_HANGUP_MS = 10_000L;

    /**
     * 一档 bind 到的本端资源：UDP 档只有 {@code udp}，媒体 TCP 档只有 {@code tcpListener}。
     * ⚠ 2.6 之后一个 {@link Round} 里**同时持有两份** Bound（{@link #boundUdp} / {@link #boundTcp}），
     * "未选中的那条立即关"就是关这一档里没被定档采纳的那**一份**；本类只保证 {@link #release()} 幂等。
     */
    public static final class Bound {
        public final Tier tier;
        public final int port;
        /** true = 8 次实试都失败后由系统分配（bind port 0）；数字照样广告给对端。 */
        public final boolean systemAssigned;
        final java.net.DatagramSocket udp;
        final java.net.ServerSocket tcpListener;

        Bound(Tier tier, int port, boolean systemAssigned,
              java.net.DatagramSocket udp, java.net.ServerSocket tcpListener) {
            this.tier = tier;
            this.port = port;
            this.systemAssigned = systemAssigned;
            this.udp = udp;
            this.tcpListener = tcpListener;
        }

        /** 幂等关闭：释放已 bind 的端口/监听。2.9 的五条退出路径与 2.3 的呼叫失败分支都靠这一句。 */
        public void release() {
            java.net.DatagramSocket u = udp;
            if (u != null) {
                try {
                    u.close();
                } catch (Throwable ignore) {
                }
            }
            java.net.ServerSocket s = tcpListener;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * 按 §4.1 为一档 bind 本端资源。**失败一律静默**（§4.1 明文：443 被本机其它服务占用是
     * 正常情况，不得报错、不得因此挂断 —— S18 的核实项在这里是一条硬自查）：
     * 返回 null = 本档不成立，调用方进下一档。不抛异常、不打日志（日志由 2.5 的定档时序
     * 按"每档一行结果"来打，逐次 bind 失败打日志＝1.6 之后的又一处刷屏）。
     * ⚠ INBAND（第五档）没有端口可 bind，它复用主 TCP ⇒ 恒返回 null，由调用方按带内处理。
     * ⚠ Windows 首次监听新端口可能弹防火墙授权框（§4.3）：2.13 的程序级放行生效就不该弹；
     *   仍弹说明规则没生效或指向错的 exe（S26）⇒ 本方法把"弹框后被关闭"表达成返回 null。
     * ⚠ **调用点只在 2.5/2.6 的定档时序里**（§4.3①「先 bind 出本档端口，再交换 mport/mudp/msid」，
     *   而 2.6 的"本档"= 同一端口族的 UDP 与 TCP 两侧）：第 0 轮两侧由 {@link #start} bind
     *   （要早于协商报文），其余轮两侧由 {@link #negotiate} 逐轮 bind —— 两处都只经过
     *   {@link #bindRound}，即**每侧各调一次本方法**（每协议各 8 次就落在这里）。
     *   2.3 落地时本方法还没有调用者，那一句的"start() 只登记 INBAND"从 2.5 起已不成立 ——
     *   start() 现在确实会占住端口（2.6 起是一轮里最多两个），释放路径见 {@link #stop()}
     *   与 negotiate() 的每一处出口。
     */
    static Bound bindTier(Tier t) {
        switch (t) {
            case UDP_443:
                return tryBindUdp(t, PRIVILEGED_MEDIA_PORT, false);
            case TCP_443:
                return tryBindTcpListener(t, PRIVILEGED_MEDIA_PORT, false);
            case UDP_RANDOM:
                return tryBindRandom(t, true);
            case TCP_RANDOM:
                return tryBindRandom(t, false);
            case INBAND:
            default:
                return null;
        }
    }

    /** 随机档：避让端口**不占 bind 名额**（它是"跳过"，不是"试了失败"）；8 次实试后转系统分配。 */
    private static Bound tryBindRandom(Tier t, boolean udp) {
        java.util.Random r = new java.util.Random();
        int tries = 0;
        int picks = 0;
        while (tries < BIND_MAX_ATTEMPTS && picks < BIND_MAX_ATTEMPTS * 8) {
            picks++;
            int p = MEDIA_PORT_MIN + r.nextInt(MEDIA_PORT_MAX - MEDIA_PORT_MIN + 1);
            if (isAvoided(p)) {
                continue;
            }
            tries++;
            Bound b = udp ? tryBindUdp(t, p, false) : tryBindTcpListener(t, p, false);
            if (b != null) {
                return b;
            }
        }
        return udp ? tryBindUdp(t, 0, true) : tryBindTcpListener(t, 0, true);
    }

    private static boolean isAvoided(int p) {
        for (int a : PORT_AVOID) {
            if (a == p) {
                return true;
            }
        }
        return false;
    }

    /**
     * UDP bind：用**不带 REUSEADDR** 的普通构造。与 Discovery.java:411–413 的发现端口相反，
     * 这里刻意不放开地址复用 —— 媒体口被两个 socket 同时绑上，包就会被上一通残留的
     * （或本机另一个）收包线程抢走，表现为"这通听不到对端"。bind 撞口就按本档失败、进下一个随机数。
     *
     * ⚠ 收发内核缓冲**必须在 bind 之前**设：bind 之后再 setReceiveBufferSize 只改 JDK 内部缓冲，
     *   内核队列仍旧是默认值。默认值在 Windows 上是 **65536B**（10-03 实测），而一帧 1080p 关键帧
     *   的突发最大 143590B = **121 片 = 145KB**（见 plan 第 9 节），65536B 只装得下 54 片 ⇒
     *   关键帧注定缺片，而重组是"缺任一片整帧丢"，于是每丢一帧 IDR 就带走整 GOP（实测 29 帧不出画面
     *   ≈ 0.97s 静止，就是"几秒卡一下"）。1MB 按实测最坏突发（145KB）与发端队列上限
     *   （{@code VIDEO_QUEUE_MAX_SLICES}=512 片 = 594KB）反推，留一倍余量；环回 A/B 显示每片处理
     *   0~500µs 全档 0 缺片（默认缓冲在 50µs 档就缺 41/121）。
     */
    private static Bound tryBindUdp(Tier t, int port, boolean systemAssigned) {
        java.net.DatagramSocket u = null;
        try {
            u = new java.net.DatagramSocket(null);
            u.setReceiveBufferSize(UDP_BUFFER_BYTES);
            u.setSendBufferSize(UDP_BUFFER_BYTES);
            u.bind(new java.net.InetSocketAddress(port));
            Log.i("[媒体 UDP] 口 " + u.getLocalPort() + " 缓冲 rcv=" + u.getReceiveBufferSize()
                    + "B snd=" + u.getSendBufferSize() + "B（请求 " + UDP_BUFFER_BYTES + "B）");
            return new Bound(t, u.getLocalPort(), systemAssigned, u, null);
        } catch (java.io.IOException e) {
            if (u != null) {
                u.close();
            }
            return null;
        }
    }

    /**
     * 媒体 TCP 监听。形状照本仓库姊妹链路的既有监听先例（Link.java:989–991 消息端口：
     * 无参构造 + setReuseAddress(true) + bind）—— 端口**每通新建、不复用上一通的**（§4.1），
     * 上一通"未选中即关"的那条连接可能还在这台机器的 TIME_WAIT 里，不加 REUSEADDR 就会
     * 让下一通在同一随机端口上无故 bind 失败。backlog 用默认值（不新增数字）；
     * accept 侧在 2.6 已落（{@link #probeMediaTcpRound}，测通即把那条连接留在 {@link #mediaTcp} 空闲保持），
     * 本方法只负责把端口占出来。
     */
    private static Bound tryBindTcpListener(Tier t, int port, boolean systemAssigned) {
        try {
            java.net.ServerSocket s = new java.net.ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(port));
            return new Bound(t, s.getLocalPort(), systemAssigned, null, s);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 一次媒体会话的入参（每通新建、不复用上一通的，§4.1） */
    public static final class Spec {
        public final String peerIp;
        /**
         * 本端为本次通话生成的随机 token。2026-10-02 用户点「每端各自生成」（本单待点 ⑤）
         * ⇒ 原来那**一个** msid 槽拆成本端/对端两份：本端用来给 probe 打戳，对端用来校验入向。
         */
        public final String localMsid;
        /** 对端 token（invite / accept 初值，之后每档由 call_media 覆盖）；空 = 对端未声明（老端） */
        public final String peerMsid;
        /** 对端媒体端口（2.4 协商来；0 = 对端未声明） */
        public final int peerMport;
        /** 对端媒体端口是否 UDP（2.4 协商来；false = 对端未声明） */
        public final boolean peerMudp;
        /**
         * true = 本端是主叫。只影响**媒体 TCP 档由谁发起连接**（主叫拨、被叫只 accept）：
         * 方案 §4.3 没写谁拨，两端都拨会在同一窗口里开出两条连接 ⇒ 这里定成一方拨、一方接
         * （任务单 2.5 块「我的推论③」）。
         */
        public final boolean caller;

        public Spec(String peerIp, String localMsid, String peerMsid,
                    int peerMport, boolean peerMudp, boolean caller) {
            this.peerIp = peerIp == null ? "" : peerIp;
            this.localMsid = localMsid == null ? "" : localMsid;
            this.peerMsid = peerMsid == null ? "" : peerMsid;
            this.peerMport = Math.max(0, peerMport);
            this.peerMudp = peerMudp;
            this.caller = caller;
        }

        /** 2.4 之前的过渡构造：只有对端地址，协商字段全缺省 ⇒ 恒落带内 */
        public static Spec inband(String peerIp) {
            return new Spec(peerIp, "", "", 0, false, false);
        }
    }

    /**
     * 控制状态全量快照（D14）。字段名逐字符照 §3-D14-1 的两类 owner，
     * 任务单 3.1/3.13 定 14B 线格式时用的就是同一批名字。
     * ⚠ 测量值（queueMs / dropPct / latMs / renderGapMs / loss / jitter）**不在这里**
     * —— 它们走媒体通道的 100ms 反馈（见 {@link Stats}）。
     */
    public static final class Ctrl {
        public int epoch;                 // 本次媒体会话号（跨通话不回绕）
        public long seq;                  // 会话内单调递增；旧 epoch 或 seq ≤ 已见最大值直接丢

        // ---- 推流端 owner ----
        public int videoLevel;
        public int videoBrKbps;
        public int videoFps;
        public int audioBrKbps;
        public boolean aggregated;
        public boolean camOn;
        public boolean muted;
        public boolean sendingPaused;

        // ---- 收流端 owner ----
        public boolean wantVideo;
        public boolean needKeyframe;
        public boolean bg;
    }

    /** 反馈/测量值（D14-1 明确"不属于 ctrl"的那一组），由 任务单 3 的 100ms 反馈填充 */
    public static final class Stats {
        public long queueMs;
        public int dropPct;
        public long latMs;
        public long renderGapMs;
        public int loss;
        public long jitter;
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
    public interface Sink {
        void onAudio(String fromIp, int seq, long ts, byte[] data, int len);

        void onVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len);

        /**
         * 视频分片重组**丢了半帧** ⇒ 参考链已断（{@link VideoReassembler.LossListener} 的转发）。
         * 上层唯一该做的事是让解码器停止喂死链帧并向对端要一个 IDR；本模块不自己决定处置（I14 同口径：
         * 媒体判据在这里，通话/解码的处置在那里）。
         *
         * @param key 缺的那半帧是不是关键帧/参数集帧（true ⇒ 后面整 GOP 都解不出，静止按秒计）
         */
        default void onVideoChainBroken(boolean key) {
        }

        default void onCtrl(Ctrl ctrl) {
        }

        default void onStats(Stats stats) {
        }

        /**
         * 2.9 / S23：本档"1s 内一条反馈都没收到"，**上升沿**回调一次（恢复后再次发生才再报，
         * 1.6 的"不出现周期性日志"口径同样约束这里）。
         *
         * @param text 模块已经拼好的那一行（含 S23 要求的五项），UI 照显示即可，不要再自己凑数
         */
        default void onMediaSuspectedBlocked(String text) {
        }

        /**
         * 2.9 / P10：通话中途媒体通道彻底无流达 {@link #NO_STREAM_HANGUP_MS} ⇒ 上层**自动挂断 + 提示**。
         * ⚠ 本模块不挂断自己（媒体存活由通话状态机决定，I14），这里只是把判据结果送出去。
         */
        default void onMediaNoStream() {
        }
    }

    private final Link link;
    private final Sink sink;

    private volatile Spec spec = Spec.inband("");
    private volatile Tier tier = Tier.INBAND;
    private volatile boolean started;
    /**
     * 本会话**当前这一轮**已 bind 的两侧资源（2.6 并档）：UDP 侧与媒体 TCP 侧各一份，
     * 任一份可为 null = 该侧 bind 不成立 / 本端不试该侧。定档后只留被采纳的那一侧
     * （未选中的那条立即关，D11 / S11），落带内或叫停 ⇒ 两侧全关（{@link #releaseBound()}）。
     */
    private volatile Bound boundUdp;
    private volatile Bound boundTcp;
    /** 已 bind 的是第几轮（{@link #roundsForThisEnd()} 的下标）；-1 = 当前没有持有任何轮的资源。 */
    private volatile int boundRound = -1;
    /**
     * 2.6 的**预建并保持空闲**的那条媒体 TCP 连接（D11）：④ 握成之后留在这里，
     * 任务单 3.3 的 writer 直接用它、不再付一次握手。⚠ 今天（3.3 之前）本字段**一个字节都不写**
     * —— 写侧与读侧都在 3.3，§4.4 那条 2B 大端前缀也跟到那里落地，本条不留半开连接之外的空写。
     * 定档成 UDP / 落带内 / 时序被叫停 / stop() ⇒ 立即关（"未选中的那条立即关"包含它）。
     * ⚠ **任务单 2.8 的硬边界（这条连接的身份）**：{@code mediaTcp} 与 {@link Bound} 里那条媒体 TCP
     *   **监听**都是**裸 socket**，本模块从不把它们交给 Link —— 它们**不进**
     *   {@code Link.byPeer}（deviceId → Session 那张单值映射，Link.java:284-285）、
     *   **不参与**同设备双连接去重（Link.java:1624 起）、**一个 hello 副作用都不跑**
     *   （设备表更新 / 绑定 / 清候选地址 / selectedIp 跟随 / flushPending / hsync 全都不沾）。
     *   本模块对 Link 的全部调用只有 isConnectedIp / sendAudioTo / sendVideoTo / sendCallMedia 四个，
     *   没有第五个能注册会话 ⇒ "媒体会话与数据会话互不干扰"这条是靠**没有入口**成立的，不是靠判断成立的。
     *   将来真要登记媒体会话（2.10 的入向放行、3.4 的分发都要按 (deviceId, msid) 找会话），
     *   注册键就是那一对，而且**必须另建一张表**，不许写进 byPeer（那是单值映射，一台设备第二条连接
     *   会把数据会话挤掉 —— 正是 2.8 要避免的形状）。
     */
    private volatile java.net.Socket mediaTcp;

    // ---- 任务单 2.5：**对端**广告的三元组（invite/accept 给初值，之后每轮 call_media 覆盖）——
    // 本端拿它当探测目标（probe 发往对端 UDP 侧、媒体 TCP 档连对端 TCP 侧），不是本端自己的端口。
    // ⚠ 2.6 并档之后**按协议分两个槽**：对端一轮里发两份三元组（mudp=true 一份、false 一份），
    //   互相不覆盖 —— 单槽会把"同档 TCP 侧已广告"冲掉，④ 就永远等不到目标端口。
    private volatile int peerUdpPort;
    private volatile int peerTcpPort;
    private volatile String peerMsid = "";
    /**
     * 对端自报「我现在在第几**轮**」（2.6 并档：同一轮的两份 call_media 带同一个数）。
     * ⚠ **不作连通性判据**：两端轮序列长度不同（安卓 1 轮 + 带内、PC 2 轮 + 带内，
     * 因为 §4.1 让安卓跳过两个 443 档），序号对不上是常态；判据只有「收到带本通 msid 的 probe」
     * （UDP）与「连上了对端广告的媒体 TCP 端口」（TCP 档）两条。本字段只进日志。
     */
    private volatile int peerTierOrdinal = -1;
    /**
     * 任务单 2.7（D10 learn-by-receiving）：媒体面**实际该往哪儿打** = 最近一次从对端收到媒体包
     * 的那个源地址:端口。
     * ⚠ 全模块**只有一个写入点**：{@link #notePeerSource}，而它只许在"收到包"那一处被调
     *   （今天 = ②③ 里认出对端 probe 那一次；任务单 3.4 的 reader 收到 audio/video_slice/ctrl/feedback
     *   时报**同一个方法**）⇒ 2.7 的硬自查"更新发送目标的地方只有收到包这一处"就这样成立。
     * null = 还没收到过对端任何媒体包 ⇒ 用**初值**（invite/accept 里的 IP + 对端广告的端口，
     *   {@link #mediaTarget} 里那条退路）。⚠ 那是"初值"，不是"唯一目标"：D10 说的正是
     *   多地址设备"界面地址≠会话地址"（本项目实测过 192.168.1.13 与 vgate0 172.30.0.192），
     *   所以一旦有了实测收包源，一律以它为准。
     */
    private volatile java.net.InetSocketAddress peerMediaTarget;
    /** 定档时序被叫停（挂断 / 取消 / 呼叫失败 / stop()）；probe 循环每一步都查它。 */
    private volatile boolean negotiationStopped;
    /** ⑥「五档全不通 ⇒ 落带内」＝明示降级。2.11 的 UI 与 4.8 的"聚合恒关（I13）"读它。 */
    private volatile boolean inbandDowngraded;
    /** ctrl 报的 streamSeq，按类型独立计数（3.1 / S10）。16 位取模，回绕由 3.5 的在途窗口按模 2^16 处理。 */
    private final java.util.concurrent.atomic.AtomicLong ctrlStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 任务单 3.2 的视频切片重组器（每通新建、随会话生命周期，与 §4.1「每通不复用上一通」一致）。
     * ⚠ 投放点 = {@link #avRecvLoop}（收到 video_slice 报）；3.3 已接上真实调用者（不再是零调用死码）。
     */
    private final VideoReassembler videoReassembler = new VideoReassembler();

    // ---- 任务单 3.3/3.4：媒体面单写者 + 严格优先级队列（仅 UDP 档；带内仍走主 TCP writeLock）----

    /** 音频流 seq（按 type 独立计数，S10）；16 位取模。 */
    private final java.util.concurrent.atomic.AtomicLong audioStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();
    /** 视频流 seq：**每帧领一个号**（帧内所有片共用），16 位取模。 */
    private final java.util.concurrent.atomic.AtomicLong videoStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();

    // ---- 投递侧的扩展序号（RTP 的 extended seqno 口径，任务单 3.4）----
    // ⚠ 为什么必须扩展：线上 streamSeq 是 **16 位**（3.1 的字节制 + checkU16），而下游三处判据全都按
    //   **单调递增的 int** 写：本端 CallManager.handleInboundVideo 的 {@code seq <= vLastSeq}、
    //   安卓 VideoCall.onRemoteFrame 的 {@code seq <= lastVideoSeq}、两端 VoiceEngine 的
    //   {@code seq <= playedSeq} 丢迟到帧 + {@code next = playedSeq + 1} 严格续播。改前带内帧制的 seq
    //   是 **32 位**（Protocol.writeAudio/writeVideo 那 4 字节），所以这些判据一直成立；UDP 档若直接把
    //   16 位交下去，音频约 22 分钟（50 包/s）、视频约 36 分钟（30 帧/s）在 65535→0 回绕后，
    //   **每一帧都会被当成重复帧丢掉** —— 表现是通话中途画面与声音永久冻死。
    //   这里把回绕展开成单调 int，下游一字不改。
    // ⚠ 只在"前向回绕"时进位（u16 比上一包小且差值 > 半程 32768）；小幅乱序（重组窗 40ms 内）不进位，
    //   交给下游那三条单调判据自然处理。
    private int audioSeqExt;
    private int audioSeqLastU16 = -1;
    private int videoSeqExt;
    private int videoSeqLastU16 = -1;

    /** 音频：把 16 位 streamSeq 展开成单调 int 后投递。单 reader 线程调用 ⇒ 不必加锁。 */
    private int extendAudioSeq(int u16) {
        int last = audioSeqLastU16;
        if (last >= 0 && u16 < last && last - u16 > 32768) {
            audioSeqExt++;
        }
        audioSeqLastU16 = u16;
        return (audioSeqExt << 16) | u16;
    }

    /** 视频：同上，按**帧**（重组完成）粒度展开。 */
    private int extendVideoSeq(int u16) {
        int last = videoSeqLastU16;
        if (last >= 0 && u16 < last && last - u16 > 32768) {
            videoSeqExt++;
        }
        videoSeqLastU16 = u16;
        return (videoSeqExt << 16) | u16;
    }

    /** 四条优先级队列：写者按 audio>ctrl>feedback>video 的顺序排空（§4.5 / I2）。元素=已编码的整报字节。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qAudio =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qCtrl =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qFeedback =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qVideo =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** pp-av-loop（写者）/ pp-av-recv（reader）。null = 未启动或已关。唯一启动点 = {@link #selectUdpSide}。 */
    private volatile Thread writerThread;
    private volatile Thread recvThread;

    /** {@link #answerPeerProbe} 已回数的 probe 份数，上限 {@link #PROBE_COUNT}。写读都在 pp-av-recv，归零在 {@link #start}。 */
    private volatile int probeReplies;

    // ---- 任务单 2.9：S23 那一行日志的内容就取自下面这五个量（缺一项就不达标）----

    /**
     * 本端在媒体面**已发包数**。⚠ 3.3 之前只有带内那一条写者（{@link #mediaUsesMainTcp()} 恒 true），
     * 所以这个数今天统计的是"交给写者的媒体帧"，不是"从 UDP/媒体 TCP 发出去的报" —— 但那时
     * {@link #hasFeedbackChannel()} 恒 false，本条的判定根本不跑，不存在"拿这个数误报"。
     */
    private volatile long localSentPackets;
    /** 本端**收包计数**（媒体面的包，今天 = 对端 probe；3.4 的 reader 起来后一并计入）。 */
    private volatile long localRcvPackets;
    /** **收到的反馈数**。⚠ 生产者要到任务单 3.7（100ms 反馈帧的 reader）才存在，今天恒 0（不假装在工作）。 */
    private volatile long feedbackRcvPackets;
    /** 对端最后一包到达时刻（epoch ms）；0 = 本通还没收到过任何媒体包。 */
    private volatile long peerLastRxAtMs;
    /** 最后一次收到反馈的时刻（epoch ms）。同上，今天没有生产者。 */
    private volatile long lastFeedbackAtMs;

    /**
     * 2.9 的存活检查线程（{@code pp-av-alive}）。null = 未启动或已关。
     * ⚠ **唯一**启动点是 {@link #selectUdpSide} / {@link #selectTcpSide}（定档采纳成四档之一时），
     * 且启动前先过 {@link #hasFeedbackChannel()} ⇒ 带内档永远不起这条线程（不留一个空转壳，同 2.2 口径）。
     * 关闭 = {@link #stop()} 摘引用 + {@code interrupt()}，循环本身还每 tick 查 {@code started}。
     */
    private volatile Thread aliveThread;
    /** "疑似被拦"是否已就本次事件上报过（上升沿只报一次；重新收到反馈后复位）。 */
    private volatile boolean suspectedBlockedLatched;
    /** 双向都静默的起点（epoch ms）；0 = 当前不是无流状态。P10 的 10s 从它算起。 */
    private volatile long bothQuietSinceMs;
    /**
     * 2.10 / P6 的**注入点**：媒体入向的"① 通话中 + ② 已知设备 + 网段黑名单"这三半。
     * 本模块没有这三张表（通话状态机、已知设备名单、黑名单都在上层），所以由
     * {@link #setMediaInboundAdmit} 注入；判据本体见 {@link #admitMediaInbound}。
     * null = 未注入 ⇒ 一律**拒绝**（不放行来源不明的媒体连接）。
     */
    private volatile java.util.function.Predicate<String> mediaInboundAdmit;

    /**
     * 注入媒体入向的准入判据（2.10 / P6 / S14）。由 CallManager 在 openTransport 里、
     * 与 {@link #start()} 同一处配好；{@link #stop()} 里随本通一起清掉。
     * ⚠ 只影响**被叫侧的媒体 TCP accept**；UDP 侧的入向今天已有 {@link #isPeerProbe} 的 msid 校验，
     *   本条不动它（口径差异与理由见任务单 2.10 块）。
     */
    public void setMediaInboundAdmit(java.util.function.Predicate<String> admit) {
        mediaInboundAdmit = admit;
    }

    public AvTransport(Link link, Sink sink) {
        this.link = link;
        this.sink = sink;
        // 半帧丢失 = 参考链断掉，本模块只通报、不定处置（处置在 CallManager → FfmpegDecoder）。
        videoReassembler.setLossListener((seq, missing, count, key) -> sink.onVideoChainBroken(key));
    }

    /**
     * 建媒体会话：登记 spec，并**立刻 bind 第 0 档的两侧**（§4.3①「各自按 §4.1 顺序 bind 出
     * 本档端口（**UDP + 媒体 TCP 监听**）」——2.6 并档之后"本档"就是一整个 {@link Round}，
     * 两侧都在这里 bind 出来，端口要随 invite / accept / call_media 出去，所以必须早于协商报文）。
     * 2.1 的「start() 只登记 INBAND」从 2.5 起改成真正的时序入口：定档由 {@link #negotiate()} 跑完。
     */
    public void start(Spec spec) {
        this.spec = spec == null ? Spec.inband("") : spec;
        this.tier = Tier.INBAND;
        this.started = true;
        Spec s = this.spec;
        // 2.4 落的那三个 wire 字段从本条起**有读者**了（三元组进模块，供 probe 定目标与校验 msid）
        // ⚠ 2.6：invite / accept 只带**一份**三元组（§4.2 那三个字段没扩，I8/I11 的口径不动），
        //   按 mudp 落进对应那一侧的槽；另一侧等对端第一轮的两份 call_media 来填。
        this.peerUdpPort = s.peerMudp ? s.peerMport : 0;
        this.peerTcpPort = s.peerMudp ? 0 : s.peerMport;
        this.peerMsid = s.peerMsid;
        this.peerTierOrdinal = -1;
        // 2.7：发送目标随每一通重新学（端口本就每通新建、不复用上一通的，§4.1）
        this.peerMediaTarget = null;
        this.negotiationStopped = false;
        this.inbandDowngraded = false;
        // 2.9：五个计数 + 两条闩锁随每一通归零（计数跨通累积就会把 S23 那行的"已发/已收"判成上一通的数）
        this.localSentPackets = 0L;
        this.localRcvPackets = 0L;
        this.feedbackRcvPackets = 0L;
        this.peerLastRxAtMs = 0L;
        this.lastFeedbackAtMs = 0L;
        this.suspectedBlockedLatched = false;
        this.bothQuietSinceMs = 0L;
        this.probeReplies = 0;
        // 3.4：投递侧的扩展序号随每一通重新起算（流内 16 位序号按通独立，展开的高位跟着归零）。
        this.audioSeqExt = 0;
        this.audioSeqLastU16 = -1;
        this.videoSeqExt = 0;
        this.videoSeqLastU16 = -1;
        this.audioStreamSeq.set(0L);
        this.videoStreamSeq.set(0L);
        java.util.List<Round> r = roundsForThisEnd();
        if (!r.isEmpty()) {
            bindRound(r.get(0));
            this.boundRound = 0;
        }
    }

    /**
     * ①：bind 一轮的两侧（2.6）。每侧**各自**走 2.3 的 {@link #bindTier}（各自 8 次实试 +
     * 各自一次系统分配退路）⇒ 这就是待点 ⑧ 点 (a)「每协议各 8 次」的落点。
     * 失败静默（§4.1），由调用方按"该侧不成立"处理；本方法不打日志（日志仍按每轮一行，1.6）。
     */
    private void bindRound(Round r) {
        this.boundUdp = bindTier(r.udpTier);
        this.boundTcp = r.tcpTier == null ? null : bindTier(r.tcpTier);
    }

    /** 本端**已 bind、等着随协商报文带出去**的媒体端口；0 = 本档两侧都不成立 / 走带内。 */
    public int mediaPort() {
        Bound b = advertisedSide();
        return b == null ? 0 : b.port;
    }

    /** {@link #mediaPort()} 那个端口是否 UDP（与 mediaPort() 同一时刻读才自洽）。 */
    public boolean mediaIsUdp() {
        Bound b = advertisedSide();
        return b != null && isUdp(b.tier);
    }

    /**
     * invite / accept 那**一份**三元组该带哪一侧：2.6 一轮里有两个本端端口，而 §4.2 只给了
     * 一个 mport + 一个 mudp ⇒ 按 §4.1 的声明序取 **UDP 侧优先**，UDP 侧不成立时才退 TCP 侧
     * （另一侧由 {@link #advertise} 的第三份 call_media 带出去，不靠这两个字段）。
     */
    private Bound advertisedSide() {
        Bound u = boundUdp;
        return u != null ? u : boundTcp;
    }

    /** 本端本次通话的 token —— CallManager 把它写进 invite/accept/call_media 的 msid。 */
    public String localMsid() {
        return spec.localMsid;
    }

    /**
     * 收到对端的媒体三元组。三个来源归这一个入口：{@code call_invite} / {@code call_accept}
     * （2.4 的那一份三元组）与 {@code call_media}（2.5 的交换，2.6 起**一轮两份**：
     * mudp=true 那份进 UDP 槽、mudp=false 那份进 TCP 槽，互不覆盖）。
     *
     * @param mtier 对端自报的本轮序号；只进日志，见 {@link #peerTierOrdinal}
     */
    public void offerPeerTriple(int mport, boolean mudp, String msid, int mtier) {
        int p = Math.max(0, mport);
        if (mudp) {
            this.peerUdpPort = p;
        } else {
            this.peerTcpPort = p;
        }
        this.peerMsid = msid == null ? "" : msid;
        this.peerTierOrdinal = mtier;
    }

    /**
     * 振铃期一次跑完的**定档时序**（方案 §4.3 七步 / U11②⑥）。
     * ⚠ 会按窗口阻塞 ⇒ 只能在独立线程上调用（CallManager 的 pp-av-negotiate），绝不能在
     *   EDT（PC）上跑；本方法不做任何通话状态迁移，只回结论。
     *
     * 七步对位（2.6 并档后）：① = {@link #bindRound}（一轮两侧）+ {@link #advertise}（两份三元组）；
     * ②③ = {@link #probeUdpRound}；④ = {@link #probeMediaTcpRound}，**在同一轮里紧跟着 ②③**，
     * 不回 ①（这就是 D11 / S11 省掉的那"再开一轮"）；
     * ⑤ = 循环末尾 releaseBound + 进下一轮；⑥⑦ = {@link #settleInband}。
     * ⚠ 同一轮里 UDP 侧先探、TCP 侧后探：两侧都通时取 UDP（§4.1 的声明序就是 D8 的偏好序）。
     *
     * @return true = 已定档（含 ⑥ 的带内档，此时 {@link #mediaDowngradedToInband()} 为 true）；
     *         false = ⑦ 带内也不成立 ⇒ 提示呼叫失败、**不进 TALKING**（U11②）
     */
    public boolean negotiate() {
        Spec s = spec;
        if (!started || negotiationStopped || s.peerIp.isEmpty()) {
            releaseBound();
            return false;
        }
        if (!s.caller && peerMsid.isEmpty()) {
            // 被叫侧的 invite 是**权威**的「对端声明了没有」证据：空 = 主叫不认这三个字段（老端）
            // ⇒ 没有可探测的对端端口，直接按 §4.2 的缺省回退落带内（老端只要求不断链，U11⑦ / S19）
            return settleInband("主叫未声明媒体三元组（老端）⇒ 按缺省回退落带内");
        }
        java.util.List<Round> rounds = roundsForThisEnd();
        for (int i = 0; i < rounds.size(); i++) {
            if (negotiationStopped) {
                releaseBound();
                return false;
            }
            Round r = rounds.get(i);
            if (boundRound != i) {
                // ⑤「换下一档端口，回到 ①」：上一轮失败时已整轮释放，这里给新一轮 bind 两侧。
                // 第 0 轮已由 start() bind 过（§4.3① 要它早于协商报文）⇒ boundRound==0，这里跳过。
                releaseBound();
                bindRound(r);
                boundRound = i;
            }
            // stop() 与这一次 bind 之间的竞态：stop() 是「先置标志、再 releaseBound」，
            // 所以本端必须「先写入 bound、再查标志」（两边都是 volatile ⇒ 这一对里至少有一边看得见另一边）。
            // 少了这一查，振铃期正好点挂断时，刚 bind 出来的那两个端口就没有人释放（对象已被摘掉，
            // 只能等 GC 的 socket finalizer，不是确定性的关闭）。
            if (negotiationStopped) {
                releaseBound();
                return false;
            }
            Bound u = boundUdp;
            Bound tc = boundTcp;
            if (u == null && tc == null) {
                // §4.1 明文：bind 失败静默跳下一档，不报错、不挂断（S18 的核实项在这条分支成立）
                logTier(r, i, "本档两侧 bind 都不成立 ⇒ 静默进下一档");
                continue;
            }
            advertise(i);
            // ②③：本档 UDP 侧。对端那一侧没广告端口时 probe 不发（peerReadyFor），窗口自然走空
            if (u != null && probeUdpRound(u)) {
                if (negotiationStopped) {
                    releaseBound();
                    return false;
                }
                logTier(r, i, "定档 " + u.tier + "（②③ 的窗口内收到对端 probe；"
                        + "同档未选中的媒体 TCP 侧立即关，2.6）");
                selectUdpSide(u.tier);
                return true;
            }
            // ④：同一档的媒体 TCP 侧 —— 端口在 ① 就 bind 好了，所以这里不回 ①（D11 / S11）
            if (tc != null && probeMediaTcpRound(tc)) {
                if (negotiationStopped) {
                    // 预建那条也在 releaseBound 的范围里：窗口刚握好就被叫停 ⇒ 不留连接
                    releaseBound();
                    return false;
                }
                logTier(r, i, "定档 " + tc.tier + "（④ 握成的连接留在 mediaTcp 空闲保持，D11；"
                        + "同档未选中的 UDP 侧与 TCP 监听立即关）");
                selectTcpSide(tc.tier);
                return true;
            }
            logTier(r, i, "本档两侧都不通（②③ 窗口内没收到对端 probe，④ 也没握起连接）"
                    + "⇒ ⑤ 换下一档端口，回到 ①");
            releaseBound();
        }
        if (negotiationStopped) {
            releaseBound();
            return false;
        }
        return settleInband("五档全不通");
    }

    /**
     * 定档采纳 UDP 侧（2.6 的"未选中的那条立即关"）：同档那条媒体 TCP **监听**关掉 ——
     * 它一条连接都没握过，留着只会平白多占一个端口（M6"不留半开连接"）。
     * UDP 侧的 Bound 继续持有（任务单 3.3 的 writer 用那个 socket 发包）。
     */
    private void selectUdpSide(Tier t) {
        tier = t;
        Bound b = boundTcp;
        boundTcp = null;
        if (b != null) {
            b.release();
        }
        // 3.3：采纳 UDP 档 ⇒ 起本端媒体 IO（唯一 writer + reader）。必须在 startAliveWatchdog 之前：
        //   存活判定读 hasFeedbackChannel()＝!mediaUsesMainTcp()，而后者要等 writerThread 起来了才变 false。
        startMediaIo();
        // 2.9：定档采纳成四档之一 ⇒ 这一档有反馈通道了，存活判定从这里开始跑（带内档不会走到这里）
        startAliveWatchdog();
    }

    /**
     * 定档采纳媒体 TCP 侧：未选中的 UDP 侧立即关；**TCP 监听也关**（本通只保留 ④ 握成的那一条，
     * 不再接受第二条连接）。预建的那条在 {@link #mediaTcp} 里、不属于任何 Bound，
     * 所以这里走的 {@link Bound#release()} 不会把它一起关掉（这正是"未选中即关"与"预建保留"
     * 两条要求在同一段代码里共存的原因）。
     */
    private void selectTcpSide(Tier t) {
        tier = t;
        Bound u = boundUdp;
        boundUdp = null;
        if (u != null) {
            u.release();
        }
        Bound b = boundTcp;
        boundTcp = null;
        if (b != null) {
            b.release();
        }
        // ⚠ 3.3 本轮只落了 UDP 侧的自有 writer/reader；媒体 TCP 档的自有 IO 未建 ⇒ 不在此起 startMediaIo。
        //   于是 mediaUsesMainTcp() 对本档仍 true（媒体帧照走带内），hasFeedbackChannel() 也恒 false ⇒
        //   下面这条 startAliveWatchdog 走不进去（判据没过），不会为一条其实还在带内的通道空起线程。
        //   预建那条 mediaTcp 仍握着不用（与 2.6 口径一致），由未落的 TCP 侧 writer 接手。
        startAliveWatchdog();
    }

    // ---- 任务单 3.3/3.4：媒体 IO（UDP 档的自有 writer + reader）----

    /**
     * 起本端 UDP 媒体 IO 的两条线程。**唯一调用点 = {@link #selectUdpSide}**（定档采纳成 UDP 档时）。
     * ⚠ 幂等：已在跑就不重复起（writerThread!=null 直接回）。关 = {@link #stop()} 里 {@link #stopMediaIo()}。
     * ⚠ I3 纪律：这两条线程**只碰媒体 socket，绝不碰 Link 的 writeLock**（带内那条锁是 I3 的唯一例外，
     *   不在这里）。写 socket 的调用点全工程只有一处 = {@link #avWriteLoop} 里的 {@link #sendUdp}。
     */
    private void startMediaIo() {
        if (writerThread != null || recvThread != null || !started) {
            return;
        }
        Thread w = new Thread(this::avWriteLoop, "pp-av-loop");
        w.setDaemon(true);
        writerThread = w;
        w.start();
        Thread r = new Thread(this::avRecvLoop, "pp-av-recv");
        r.setDaemon(true);
        recvThread = r;
        r.start();
    }

    /**
     * 关本端 UDP 媒体 IO：摘引用 + interrupt + 清四条队列（幂等）。
     * ⚠ 先摘 writerThread/recvThread 引用，让 {@link #udpEgressActive()} 立即变 false ⇒
     *   sendAudio/sendVideo 后续调用**同步回落带内**（不会往已死的队列塞）。socket 本身由 releaseBound 关，
     *   阻塞中的 receive 会因 socket close 抛异常退出，interrupt 负责叫醒 sleep 中的写者 tick。
     */
    private void stopMediaIo() {
        Thread w = writerThread;
        Thread r = recvThread;
        writerThread = null;
        recvThread = null;
        qAudio.clear();
        qCtrl.clear();
        qFeedback.clear();
        qVideo.clear();
        if (w != null) {
            w.interrupt();
        }
        if (r != null) {
            r.interrupt();
        }
    }

    /**
     * 写者线程体：每 {@link #SEND_TICK_MS} 一个 tick，按严格优先级排空
     * （音频全部 → 控制全部 → 反馈全部 → 视频最多 {@link #VIDEO_SLICES_PER_TICK} 片，§4.5 / I2 / S16）。
     * ⚠ 唯一写 socket 处。256 片/tick 覆盖实测最大 IDR（202 片）⇒ 一个关键帧通常在一个 tick 内发完，
     *   不再需要"摊到多个 tick"；只有超出预算的帧（&gt;256 片）才跨 tick，那时音频仍能在下一 tick 顶开视频。
     * ⚠ 退出：{@code !started}（stop）或本端不再是活的 UDP 档（{@link #udpEgressActive()} 变 false）⇒ 收尾退出。
     */
    private void avWriteLoop() {
        while (started && udpEgressActive()) {
            drainAll(qAudio);
            drainAll(qCtrl);
            drainAll(qFeedback);
            int budget = VIDEO_SLICES_PER_TICK;
            byte[] rep;
            while (budget-- > 0 && (rep = qVideo.poll()) != null) {
                sendUdp(rep);
            }
            try {
                Thread.sleep(SEND_TICK_MS);
            } catch (InterruptedException e) {
                return;   // stop() 叫停 ⇒ 确定性退出
            }
        }
    }

    private void drainAll(java.util.concurrent.ConcurrentLinkedQueue<byte[]> q) {
        byte[] rep;
        while ((rep = q.poll()) != null) {
            sendUdp(rep);
        }
    }

    /**
     * 把一份已编码的数据报发到对端媒体目标（{@link #mediaTarget} 以实测收包源优先）。
     * ⚠ 发送失败静默（socket 关 / 短暂不可达）：UDP 无发送侧反馈，丢这报由 3.6 的 loss% 事后体现，
     *   不在这里刷屏（1.6）。成功一份计一个 {@code localSentPackets}（2.9 的 S23/P10 读它）。
     */
    private void sendUdp(byte[] report) {
        Bound b = boundUdp;
        java.net.DatagramSocket u = b == null ? null : b.udp;
        if (u == null || u.isClosed()) {
            return;
        }
        try {
            u.send(new java.net.DatagramPacket(report, report.length, mediaTarget(peerUdpPort)));
            localSentPackets++;
        } catch (java.io.IOException | RuntimeException e) {
            // 静默：见上
        }
    }

    /**
     * reader 线程体：收 boundUdp 上的数据报，按类型分发。
     * ⚠ 2.7：收到任何一个媒体面包都要调 {@link #notePeerSource}（发送目标唯一写入点，与 probe 同一方法）
     *   和 {@link #notePeerMediaPacket}（收包计数 + 最后一包到达时刻，喂 S23/P10）。
     * ⚠ 从地址：本 app 同时只有一通，媒体 UDP 已隐式属于这通 ⇒ 投递时用 {@code spec.peerIp} 作 fromIp
     *   （满足下游 CallManager 的 peerIp 过滤，且不要求源地址==界面地址，兼容 D10 多网卡）。
     *   真正的逐包 msid 鉴权在音频/视频数据报上没有字段承载（3.10 的诚实边界），本条不假装做了。
     * ⚠ 畸形报（decodeDatagram 抛）直接丢这一份（3.1 口径），不断链、不计数。
     */
    private void avRecvLoop() {
        byte[] rx = new byte[DG_MAX_REPORT];
        java.net.DatagramPacket p = new java.net.DatagramPacket(rx, rx.length);
        while (started && udpEgressActive()) {
            Bound b = boundUdp;
            java.net.DatagramSocket u = b == null ? null : b.udp;
            if (u == null || u.isClosed()) {
                return;
            }
            try {
                u.setSoTimeout(200);
                p.setLength(rx.length);
                u.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                videoReassembler.sweepTimeoutMs(System.currentTimeMillis());
                continue;   // 只是没包，回循环头；顺带清超龄半帧
            } catch (java.io.IOException | RuntimeException e) {
                return;   // socket 被 stop() 关掉 ⇒ 退出
            }
            long now = System.currentTimeMillis();
            java.net.SocketAddress from = p.getSocketAddress();
            notePeerSource(from);
            notePeerMediaPacket();
            Protocol.Datagram dg;
            try {
                dg = Protocol.decodeDatagram(rx, 0, p.getLength());
            } catch (java.io.IOException malformed) {
                continue;   // 畸形报丢弃
            }
            String peer = spec.peerIp;
            if (dg.type == Protocol.DG_TYPE_AUDIO) {
                sink.onAudio(peer, extendAudioSeq(dg.streamSeq), dg.ts, dg.payload, dg.len);
            } else if (dg.type == Protocol.DG_TYPE_VIDEO_SLICE) {
                videoReassembler.sweepTimeoutMs(now);
                PendingAu au = videoReassembler.add(dg, now);
                // 缓冲上限在这里生效（§3-3.2「重组缓冲有上限」）：凑齐的帧已经出桶，
                // 剩下的都是还缺片的 ⇒ 超上限就丢最旧的非关键帧并计数。
                videoReassembler.evictOverflow();
                if (au != null) {
                    sink.onVideo(peer, extendVideoSeq(dg.streamSeq), au.ts, au.flag, au.data, au.len);
                }
            }
            // ctrl / feedback：本轮不在此消费（ctrl 仍走带内 Link 路径；反馈归 3.6/3.7）。
            // 唯一的例外是对端的 probe —— 不回数会把两端劈成「一边 UDP、一边带内」，见
            // {@link #answerPeerProbe}。校验仍旧只认 msid 匹配的那份（{@link #isPeerProbe}）。
            else if (dg.type == Protocol.DG_TYPE_CTRL && isPeerProbe(rx, p.getLength())) {
                answerPeerProbe();
            }
        }
    }

    /**
     * §4.3① 的「交换」：2.6 起一轮把本档**两侧**都带给对端 ⇒ 两份 call_media
     * （mudp=true / false 各一份，带同一个轮号 i；端口 0 = 该侧不成立，对端据此不去探那一侧）。
     * 第 0 轮主叫侧的 **UDP 那份不重发** —— 它已经随 call_invite 出去了（CallManager 在发 invite
     * 之前就从 {@link #mediaPort()} 取过这份值，而 mediaPort() 按 §4.1 声明序优先给 UDP 侧）；
     * **TCP 那份必须发**：invite 里没有第二个端口位（§4.2 只有 mport/mudp/msid，扩字段归待点 ⑥）。
     * 被叫侧的两份都走 call_media（call_accept 的语义是「我接了」，不能拿来预播报，
     * 这是 2026-10-02 待点 ⑥ 点 (a) 的落点）。
     */
    private void advertise(int i) {
        String msid = spec.localMsid;
        String peer = spec.peerIp;
        if (!(spec.caller && i == 0)) {
            Bound u = boundUdp;
            link.sendCallMedia(peer, u == null ? 0 : u.port, true, msid, i);
        }
        Bound tc = boundTcp;
        link.sendCallMedia(peer, tc == null ? 0 : tc.port, false, msid, i);
    }

    /**
     * ②③：本档 UDP 的双向连通性测试。一个 300ms 窗口里同时干三件事 ——
     * 等对端把本档端口广告出来（①的交换，没有另一个数）、发 3 个 probe（间隔 30ms）、
     * 收对端 probe 并校验 msid。
     *
     * ⚠ 本方法**只定方向上的"路"**：收到 probe 的源地址此刻不采信为发送目标（那是 2.7 的
     *   learn-by-receiving），也不把 payload 交给任何消费者（数据面在任务单 3）。
     */
    private boolean probeUdpRound(Bound b) {
        java.net.DatagramSocket u = b.udp;
        if (u == null || u.isClosed()) {
            return false;
        }
        long t0 = System.currentTimeMillis();
        int sent = 0;
        long nextProbeAt = t0;
        byte[] rx = new byte[Protocol.DG_CTRL_MAX_REPORT];
        java.net.DatagramPacket p = new java.net.DatagramPacket(rx, rx.length);
        while (started && !negotiationStopped) {
            long left = PROBE_WINDOW_MS - (System.currentTimeMillis() - t0);
            if (left <= 0) {
                return false;
            }
            if (sent < PROBE_COUNT && System.currentTimeMillis() >= nextProbeAt && peerReadyFor(true)) {
                sendProbe(b);
                sent++;
                nextProbeAt = System.currentTimeMillis() + PROBE_INTERVAL_MS;
            }
            try {
                // ⚠ 等待上限必须是**探针间隔**而不是整窗剩余（与安卓同一条、同一理由）：钉 left 时
                //   这一次 receive 会把 300ms 窗口一路睡完，probe 2/3 永远发不出去 ⇒ 两端各自的窗口里
                //   最多一份探针、主叫侧甚至零份（它要先收到对端 call_media 才过 peerReadyFor 闸门）
                //   ⇒ 谁都收不到对端 probe ⇒ 局域网也恒落第五档。
                u.setSoTimeout((int) Math.min(left, PROBE_INTERVAL_MS));
                p.setLength(rx.length);
                u.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                continue;   // 窗口还没走完，回循环头重算 left
            } catch (java.io.IOException | RuntimeException e) {
                return false;   // socket 被 stop() 关掉 / 收包异常：本档按不通处理，不上抛
            }
            if (isPeerProbe(rx, p.getLength())) {
                // 2.7（D10）：这是本模块**今天唯一的媒体面收包点** ⇒ 发送目标只在这里按实测源更新
                notePeerSource(p.getSocketAddress());
                notePeerMediaPacket();   // 2.9：同一个收包点，收包计数与"最后一包到达时刻"也在这里进
                return true;
            }
        }
        return false;
    }

    /**
     * ②：一份 probe ctrl 报 = {@code encodeCtrl(CT_PROBE, streamSeq, ts, msid 原文)}。
     * ⚠ 字节一律由任务单 3.1 那份 codec 产生（本单待点 ⑦ 的口径：2.5 **不许**自己拼字节）。
     * ⚠ 发送失败不判死：UDP 没有发送侧反馈，剩下的 probe 照发，成不成由窗口内有没有收到对端包定。
     */
    private void sendProbe(Bound b) {
        java.net.DatagramSocket u = b.udp;
        if (u == null || u.isClosed()) {
            return;
        }
        byte[] report = encodeProbe();
        if (report == null) {
            return;
        }
        try {
            u.send(new java.net.DatagramPacket(report, report.length,
                    mediaTarget(peerUdpPort)));
        } catch (java.io.IOException | RuntimeException e) {
            // 静默：端口已关 / 对端地址不可达都属本档不通，交给窗口判定（不在这里刷屏，1.6）
        }
    }

    /** 一份 probe ctrl 报的字节（{@link #sendProbe} 与 reader 的回数共用同一份编码，不许有第二套）。 */
    private byte[] encodeProbe() {
        try {
            byte[] body = spec.localMsid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return Protocol.encodeCtrl(Protocol.CT_PROBE,
                    (int) (ctrlStreamSeq.getAndIncrement() & 0xFFFFL),
                    CallClock.elapsedMs(), body, 0, body.length);
        } catch (java.io.IOException | RuntimeException e) {
            return null;   // msid 超 ctrl 报上限：本模块的 token 恒为 36 字符 UUID，走不到这里
        }
    }

    /**
     * 已采纳 UDP 档之后，reader 收到对端 probe 时**回数一份**（只入 {@link #qCtrl}，媒体 socket
     * 的唯一写点仍是 {@link #avWriteLoop} —— I3）。
     *
     * 为什么必须有：两端各自的 300ms 窗口是**独立起算**的（主叫发 invite 时、被叫收到 invite 时），
     * 一方被 UI 线程拖住晚进窗口就先定完档、停止 probe，另一方此后再也收不到 probe ⇒
     * 一边 UDP、一边带内的劈裂态：带内那方发的帧我们收得到，它却收不到我们的媒体报（本端已按 UDP 档
     * 发出去，而对端那份端口在它落带内时已被 releaseBound 关掉）。劈裂在用户侧的表现是「对端没画面」，
     * 比两端都带内更糟。
     *
     * ⚠ 上限直接复用 {@link #PROBE_COUNT}，不新设数字：probe 是幂等的连通性回声，对端收到第一份就
     *   定档，三份封顶 ⇒ 两端互相回数的最坏情况也是个位数报文，不形成回声风暴。计数随每通归零（{@link #start}）。
     */
    private void answerPeerProbe() {
        if (probeReplies >= PROBE_COUNT) {
            return;
        }
        byte[] rep = encodeProbe();
        if (rep == null) {
            return;
        }
        probeReplies++;
        qCtrl.add(rep);
    }

    /**
     * 2.7（D10）：媒体面发送目标的**唯一写入点**。只允许在"从对端收到一份媒体面的包"那一处调用
     * —— 今天 = ②③ 里认出对端 probe 的那一次；任务单 3.4 的 reader 收到 audio / video_slice /
     * ctrl / feedback 时报的必须是**同一个方法**（不许在别处再写一次 peerMediaTarget）。
     * 源变了才记一行日志（1.6：不出现周期性日志；一行都嫌多的话，3.4 之后再收进统计）。
     */
    private void notePeerSource(java.net.SocketAddress from) {
        if (!(from instanceof java.net.InetSocketAddress a) || a.getAddress() == null) {
            return;
        }
        java.net.InetSocketAddress old = peerMediaTarget;
        if (old != null && old.equals(a)) {
            return;   // 源没变 ⇒ 不写、不打日志
        }
        peerMediaTarget = a;
        Log.i("[媒体目标] 以实测收包源为准：" + (old == null ? "首次 " : old + " → ")
                + a.getAddress().getHostAddress() + ":" + a.getPort()
                + "（invite/accept 里的 IP 只是初值，不是唯一目标 —— D10 / 2.7）");
    }

    /**
     * 2.7：本端现在该往哪个地址:端口打。有实测收包源就用它；还没有任何媒体包时退回**初值**
     * （invite/accept 里的 IP + 对端这一侧广告出来的端口）。
     *
     * @param fallbackPort 只在"还没收到过包"时用那个端口（UDP 侧传 peerUdpPort）
     */
    private java.net.InetSocketAddress mediaTarget(int fallbackPort) {
        java.net.InetSocketAddress t = peerMediaTarget;
        if (t != null) {
            return t;
        }
        return new java.net.InetSocketAddress(spec.peerIp, fallbackPort);
    }

    /**
     * 2.7：媒体 TCP 档**主叫拨号**该拨哪个 IP —— 还是"实测收包源的 IP 优先，没收到过包才用
     * invite/accept 里的 IP"。端口**不**跟着实测源走：那个 socketAddress 的端口是**对端 UDP 侧**的，
     * 与媒体 TCP 的监听端口无关，所以这里只借 IP（这正是 D10 要的"多地址设备别打错网卡"）。
     */
    private String mediaTargetIp() {
        java.net.InetSocketAddress t = peerMediaTarget;
        return t == null || t.getAddress() == null
                ? spec.peerIp : t.getAddress().getHostAddress();
    }

    /** 收到的这份报是不是**本通对端**发来的 probe（类型 + 子类型 + msid 三项都对才算）。 */
    private boolean isPeerProbe(byte[] buf, int len) {
        String want = peerMsid;
        if (want.isEmpty()) {
            return false;   // 还没拿到对端 token ⇒ 无从校验，一律不算通（§4.3②「带 msid」的硬自查）
        }
        try {
            Protocol.Datagram dg = Protocol.decodeDatagram(buf, 0, len);
            if (dg.type != Protocol.DG_TYPE_CTRL || dg.ctrlSubtype() != Protocol.CT_PROBE) {
                return false;
            }
            String got = new String(dg.payload, 1, dg.len - 1,
                    java.nio.charset.StandardCharsets.UTF_8);
            return want.equals(got);
        } catch (java.io.IOException malformed) {
            return false;   // 3.1 的口径：畸形报直接丢这一份，不吞成「看着像空报」
        }
    }

    /**
     * ④：本档媒体 TCP 的连通性尝试（2.6：**与 ②③ 同一轮**，本侧端口在 ① 就 bind 好了，
     * 所以 UDP 侧不通时直接进这里，不再回 ① 重开一轮 —— D11 / S11 省掉的就是那一次往返等待）。
     * ⚠ 2.5 那句"测通就立刻关掉"从 2.6 起作废，改成**预建并保持空闲**（D11）：握成的这条留在
     *   {@link #mediaTcp}，任务单 3.3 的 writer 直接用它、不再付一次握手；
     *   没被采纳（定档 UDP / 落带内 / ⑦ 呼叫失败 / 时序被叫停 / stop()）时由
     *   {@link #releaseBound()} 关 —— 关它的每一条路径都在那一个出口里，含 2.9 的四条退出路径。
     * ⚠ 保留 ≠ 使用：本条**一个字节都不往它上面写**。§4.4 那条「2B 大端长度前缀 + 报文体」的
     *   写侧与读侧必须同一处，落点是 3.3 的 writer（那边才有唯一的写入口），3.2/3.3 不许再造第二种框帧。
     * ⚠ 主叫拨、被叫接（方案没写谁拨，见任务单 2.5 块「我的推论③」）：两端都拨会在同一窗口里开出两条。
     * ⚠ 主叫侧对端还没广告本侧端口时**不空转等待**：直接按本侧不通换下一档
     *   —— 两端各自单调推进，对端随后广告时会落到它自己序列里的同一族档上（失效模式②）。
     */
    private boolean probeMediaTcpRound(Bound b) {
        java.net.ServerSocket ls = b.tcpListener;
        if (ls == null || ls.isClosed()) {
            return false;
        }
        if (spec.caller) {
            if (!peerReadyFor(false)) {
                return false;
            }
            java.net.Socket so = new java.net.Socket();
            try {
                // 连接超时 = 本档窗口（§4.3 只给了这一个数）
                // 2.7（D10）：拨的 IP 用 mediaTargetIp()（实测收包源优先），端口仍是对端广告的 TCP 侧
                so.connect(new java.net.InetSocketAddress(mediaTargetIp(), peerTcpPort),
                        (int) PROBE_WINDOW_MS);
                mediaTcp = so;   // D11：预建保持，3.3 的 writer 接手
                return true;
            } catch (java.io.IOException e) {
                // 拒连 / 超时 = 本档媒体 TCP 不通，静默换下一档；这条没用的连接就地关，不留半开
                try {
                    so.close();
                } catch (Throwable ignore) {
                }
                return false;
            }
        }
        try {
            ls.setSoTimeout((int) PROBE_WINDOW_MS);
            java.net.Socket so = ls.accept();
            // 2.10（P6 / S14 + 2.6 记下的必办项）：被叫侧这条 accept **过去不做来源校验**，
            // 窗口内任何同网段主机连进来都会被采纳成预建那条 ⇒ 串话入口。现在按三分句拒。
            String rip = so.getInetAddress().getHostAddress();
            if (!admitMediaInbound(rip)) {
                Log.i("[媒体准入] 被叫侧拒收一条媒体 TCP 入向：" + rip
                        + "（通话中 / 已知设备 / msid 三条件不成立，P6 / S14）⇒ 不采纳，本档媒体 TCP 按不通处理");
                try {
                    so.close();
                } catch (Throwable ignore) {
                }
                return false;
            }
            mediaTcp = so;   // 被叫侧只收一条：窗口内**通过准入的**那条就是预建
            return true;
        } catch (java.io.IOException e) {
            return false;   // 窗口内没人连进来 / 监听已被 stop() 关掉
        }
    }

    /**
     * 媒体入向的准入判据（2.10 / P6 / S14）。⚠ 只用于**被叫侧 accept** 这一条：
     * 主叫侧那条是我们自己拨出去的（{@link #mediaTargetIp()} 已在 2.7 以实测收包源为准），
     * 远端地址就是拨号目标，没有"陌生入向"这回事 ⇒ 不经本方法。
     *
     * 三个条件是**与**关系，缺一律拒（S14 明文"不能整条关掉闸门"，这里也不许整条放开）：
     *   ① 通话中、② 已知设备、②b 不在既有网段黑名单 —— 这三样只有上层有表（通话状态机 +
     *      已知设备名单 + 高风险段/设备级标记），经 {@link #setMediaInboundAdmit} 注入；
     *   ③ msid 匹配 —— 本模块这一半：本通必须已经拿到**对端声明的** token（{@code peerMsid} 非空）。
     *      空 = 对端是老端、没认这三个字段（§4.2 缺省回退），那它根本不该有媒体 TCP 连进来；
     *      放进来的话这条连接不属于任何已协商的媒体会话。
     *
     * ⚠ 诚实边界：TCP 侧**线上**逐帧 msid 校验今天做不了 —— ④ 只握手、零字节（D11 预建），
     *   第一个带 msid 的字节要到任务单 3.3/3.4 的 writer+reader 才出现。到那时按 S12 的口径做：
     *   不匹配的报**直接丢且不计入 loss**（串扰不许污染 QoS 判据）。今天能闭合的是
     *   "设备身份 + 本通有 token"这一半，而 2.6 那条必办项要的正是"3.3 的 writer 接手之前关上"。
     * ⚠ 注入缺失（hook 为 null）按**拒绝**处理：宁可定档落带内，不放开一道来源不明的媒体连接。
     */
    private boolean admitMediaInbound(String remoteIp) {
        if (remoteIp == null || remoteIp.isEmpty() || peerMsid.isEmpty()) {
            return false;   // ③
        }
        java.util.function.Predicate<String> admit = mediaInboundAdmit;
        return admit != null && admit.test(remoteIp);   // ①②②b（上层的表）
    }

    /**
     * 对端本档**这一侧**的端口能不能用来探（token 已到、该侧端口有效）。
     * ⚠ 2.6 并档之后两个槽独立：对端 UDP 侧广告 0 不影响它的 TCP 侧可用（一轮两份 call_media
     *   各写一个槽，谁也不冲谁）—— 这正是 2.6 把 2.5 那个单槽（peerPort/peerUdp）拆成两个的原因。
     */
    private boolean peerReadyFor(boolean wantUdp) {
        return !peerMsid.isEmpty() && (wantUdp ? peerUdpPort > 0 : peerTcpPort > 0);
    }

    /**
     * ⑥⑦：五档全不通 ⇒ 落消息端口带内并**明示降级**；主 TCP 已无会话 ⇒ ⑦ 呼叫失败。
     *
     * @param why 进日志的原因（⑤ 走满 / 老端未声明）
     */
    private boolean settleInband(String why) {
        // 带内档复用主 TCP ⇒ 不留任何已 bind 的媒体端口，也不留 ④ 预建的那条连接
        // （§4.1：INBAND 没有端口可 bind；releaseBound 是两侧 + 预建的唯一出口）
        releaseBound();
        if (!link.isConnectedIp(spec.peerIp)) {
            // ⑦：带内也不成立 ⇒ 提示呼叫失败、不进 TALKING（U11②，2026-10-02 用户点）
            Log.i("[媒体定档] " + why + "，且主 TCP 会话已不在 ⇒ ⑦ 呼叫失败，不进 TALKING");
            return false;
        }
        tier = Tier.INBAND;
        inbandDowngraded = true;
        // ⑥：UI 明示降级（2.11 读 mediaDowngradedToInband()）+ D16 的通话期文件排他（2.12）
        //   + 聚合恒关（I13，4.8 读同一个标志）。这里先把日志落实，两个消费者各归各单。
        Log.i("[媒体定档] " + why + " ⇒ ⑥ 落消息端口带内（第五档）。降级提示：本次通话期间文件不发送、"
                + "网络外排队（D16）；聚合恒关（I13）");
        return true;
    }

    /**
     * 每轮一行结果（2.3 把日志推迟到这里，就是为了不出现"每次 bind 失败一行"的刷屏，1.6）。
     * 2.6 并档：一行里带本档**两侧**的端口（这一行在采纳 / 释放之前打，所以两侧都还在）。
     */
    private void logTier(Round r, int i, String result) {
        Bound u = boundUdp;
        Bound tc = boundTcp;
        Log.i("[媒体定档] 第" + (i + 1) + "轮 " + r.udpTier + " / " + r.tcpTier
                + "（本端 UDP " + (u == null ? 0 : u.port)
                + "、媒体 TCP " + (tc == null ? 0 : tc.port) + "）→ " + result);
    }

    /**
     * ⑥ 分支置位：本次通话落到了带内档（降级）。上层只读不写 ——
     * 2.11 的 UI 明示与 4.8 的「聚合恒关（I13）」都读这一个标志。
     */
    public boolean mediaDowngradedToInband() {
        return inbandDowngraded;
    }

    /**
     * 本通媒体的**实际承载**是否仍是主 TCP 带内。
     * ⚠ 判据是"媒体帧**实际**还在走带内写者"，不是"档定成了哪一档"：任务单 3.3 起 UDP 档已有自有
     *   writer/reader（{@link #udpEgressActive()}），媒体帧离开主 TCP ⇒ 本方法对 UDP 档返回 false。
     *   媒体 TCP 档今天仍走带内（自有 writer 只落了 UDP 侧，见 3.3 边界），带内复用主 TCP —— 两者本方法恒 true，
     *   行为与 3.3 改前逐字一致。
     *   D16 的通话期文件闸门（2.12）与聚合恒关（4.8）必须读**本方法而不是 tier()** ——
     *   读 tier 会在「已定档 UDP、writer 还没起」这段时间里把文件放进主 TCP 抢写锁。
     */
    public boolean mediaUsesMainTcp() {
        return !udpEgressActive();
    }

    /**
     * UDP 档的媒体 writer/reader 是否已就绪（媒体帧真的能从 {@code boundUdp.udp} 发出去）。
     * ⚠ 不硬编码档位：{@link #selectTcpSide} 会把 {@code boundUdp} 置 null、{@link #settleInband} 全关，
     *   所以"仍有活的 UDP socket 且 writer 线程已起"这三件同时成立 ⟺ 采纳的是 UDP 档且本端 IO 在跑 ⇒
     *   带内 / 媒体 TCP / 定档前 一律 false。
     */
    private boolean udpEgressActive() {
        if (!started) {
            return false;
        }
        Bound b = boundUdp;
        java.net.DatagramSocket u = b == null ? null : b.udp;
        return u != null && !u.isClosed() && writerThread != null;
    }

    /**
     * 2.11 / I13：**本次通话允不允许出现聚合包**。
     * I13 的原话是"聚合包只允许出现在 UDP / 媒体 TCP 路径；带内回退恒单帧一包"，
     * 而 §2.3 那张表里"聚合 3 帧 +40ms"在带内档是直接击穿 200ms 硬顶的那一项（S6）⇒
     * 带内档必须**恒关**，与 P5 那套"连续 2 次停发窗口才启用"的触发判据无关：
     * 触发判据归任务单 4（那边才有停发窗口的真实来源），本方法只是 I13 这道**硬闸**。
     * ⚠ 读 [mediaUsesMainTcp()] 而不是 [tier()]：理由与 2.12 的文件闸门同一条 ——
     *   "已定档 UDP/媒体 TCP、媒体却还在带内写"这段时间里，聚合也还没资格开。
     *   3.3 之前本方法**恒 false**（mediaUsesMainTcp() 恒 true）⇒ 今天没有任何消费者会因此改变行为，
     *   它是给 4.8 / 5.x 的聚合启用点准备的唯一读点（不许在别处再判一次 tier）。
     */
    public boolean aggregationAllowed() {
        return !mediaUsesMainTcp();
    }

    /**
     * 2.9 的**前置闸门**（S28 的 ①）：本档到底有没有"100ms 反馈"这条通道。
     * ⚠ 判据是"媒体帧**实际**走在四档里"，不是"档已定成四档之一" —— 3.3 之前
     *   {@link #mediaUsesMainTcp()} 恒 true，所以本方法今天**恒 false** ⇒ 两条存活判定整体休眠
     *   （行为中性，与 2.5–2.8 同口径）。带内档没有反馈通道，照抄"1s 无反馈"就会每秒误报一次，
     *   所以这条闸门是判定的第一步，不是优化。
     */
    public boolean hasFeedbackChannel() {
        return !mediaUsesMainTcp() && tier != Tier.INBAND;
    }

    /**
     * 2.9 / S23：反馈帧的**唯一**计入口。生产者 = 任务单 3.7 的 100ms 反馈 reader（今天还不存在，
     * 所以本方法零调用者 —— 与"lat 恒 0"的区别是这里没有任何消费点在读一个假数字，判定被
     * {@link #hasFeedbackChannel()} 挡在门外）。3.7 落地时必须调这里，不许另立第二个计数。
     */
    public void noteFeedback() {
        feedbackRcvPackets++;
        lastFeedbackAtMs = System.currentTimeMillis();
    }

    /**
     * 2.7 的收包计入口（与 {@link #notePeerSource} 同一个调用点、成对被调）：
     * 只统计"从对端收到的媒体面的包"，喂给 S23 那一行的"本端收包计数"与 P10 的"双向 0 包"。
     */
    private void notePeerMediaPacket() {
        localRcvPackets++;
        peerLastRxAtMs = System.currentTimeMillis();
    }

    /**
     * 2.9：起存活检查线程。**只在定档采纳成有反馈通道的那四档时被调**（见 {@link #startAliveWatchdog}
     * 的两个调用点），带内档走不到那里 ⇒ 不存在"每条通话都挂一个空转线程"。
     *
     * <p>线程域：这条线程只做"读四个计数 + 打一行日志 + 回调上层"，
     * 不持任何数据域锁、不碰 socket（I3），也**绝不做**换档 / 换端口 / 重开探测（I17 ——
     * 2.5 的原 2.9 那条"1s 无反馈触发回退切换"已就地删除，本方法里搜不到任何切换调用）。
     * <p>退出条件三条，任一成立即退：{@code started=false}（{@link #stop()}）、
     * 被 {@code interrupt()}（同处）、{@link #hasFeedbackChannel()} 变 false。daemon ⇒ 不拦 JVM 退出。
     */
    private void startAliveWatchdog() {
        if (aliveThread != null || !started || !hasFeedbackChannel()) {
            return;
        }
        Thread t = new Thread(this::aliveLoop, "pp-av-alive");
        t.setDaemon(true);
        aliveThread = t;
        t.start();
    }

    /** {@link #startAliveWatchdog} 的线程体。周期就是 S23 的那个 1s，不另设数字。 */
    private void aliveLoop() {
        long prevSent = localSentPackets;
        long prevRcv = localRcvPackets;
        long prevFb = feedbackRcvPackets;
        while (started && hasFeedbackChannel()) {
            try {
                Thread.sleep(FEEDBACK_SUSPECT_MS);
            } catch (InterruptedException e) {
                return;   // stop() 叫停 ⇒ 确定性退出（不靠"下一次循环发现 started=false"）
            }
            if (!started) {
                return;
            }
            long sent = localSentPackets;
            long rcv = localRcvPackets;
            long fb = feedbackRcvPackets;
            boolean sentInWindow = sent != prevSent;
            boolean rcvInWindow = rcv != prevRcv;
            boolean fbInWindow = fb != prevFb;
            prevSent = sent;
            prevRcv = rcv;
            prevFb = fb;
            // ---- S23：本端已发包、这一秒一条反馈都没收到 ⇒ 疑似中途被拦（只提示，不切换）----
            // ⚠ 前提"本通**收到过**反馈"（fb > 0）：S23 判的是"反馈本来在流、突然停了"，
            //   而反馈帧的消费者要到 3.7（reader 读 DG_TYPE_FEEDBACK）才存在；在那之前本通恒 0 条反馈，
            //   若不加这条前提，每一条 UDP 档通话都会稳定误报一次"疑似中途被拦"。
            //   3.7 落地后反馈开始到达 ⇒ 本判据自动生效，这里无需再改。
            if (sent > 0L && fb > 0L && !fbInWindow) {
                if (!suspectedBlockedLatched) {
                    suspectedBlockedLatched = true;
                    CallCounters.get().mediaSuspectedBlocked.inc();
                    String line = "[mediaSuspectedBlocked] 本档=" + tier
                            + " / 本端已发 " + sent + " 包 / 本 1s 收到 0 条反馈"
                            + "（累计 " + fb + "）/ 对端最后一包到达 "
                            + (peerLastRxAtMs == 0L ? "无（本通没收到过媒体包）"
                            : peerLastRxAtMs + "（" + (System.currentTimeMillis() - peerLastRxAtMs) + "ms 前）")
                            + " / 本端收包计数 " + rcv
                            + " ⇒ 疑似中途被拦（S23）：不回退、不换端口、不重开探测（I17）";
                    Log.i(line);
                    sink.onMediaSuspectedBlocked(line);
                }
            } else if (fbInWindow) {
                suspectedBlockedLatched = false;   // 收到过反馈 ⇒ 闩锁复位，下次才还能报
            }
            // ---- P10：双向 0 包达 10s ⇒ 上报"无流"，挂断由状态机做 ----
            if (sentInWindow || rcvInWindow) {
                bothQuietSinceMs = 0L;
            } else {
                long now = System.currentTimeMillis();
                if (bothQuietSinceMs == 0L) {
                    bothQuietSinceMs = now;
                } else if (now - bothQuietSinceMs >= NO_STREAM_HANGUP_MS) {
                    bothQuietSinceMs = 0L;   // 一次事件只报一次；报完状态机就挂断了
                    sink.onMediaNoStream();
                }
            }
        }
    }

    /**
     * 关存活线程：摘引用 + interrupt（{@link #stop()} 里调，与 releaseBound 一道构成本会话的收尾）。
     * 可重入：没起过（带内档 / 从未定档）就是空操作。
     */
    private void shutdownAliveThread() {
        Thread t = aliveThread;
        aliveThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 当前档。⚠ 只给模块内部与日志用：上层不许读它做分支（D3 —— 五档互换时通话层零改动）。
     */
    public Tier tier() {
        return tier;
    }

    /**
     * 发一帧音频。
     *
     * @return 写入耗时 ms；-1 = 未发送（无会话 / 异常 / 未 start）。UDP 档自有 writer 下恒 0（非阻塞），
     *         语音码率自适应**不得**把这个 0 当拥塞信号（反馈才是判据）。
     */
    public long sendAudio(int seq, long ts, byte[] data, int len) {
        Spec s = spec;
        if (!started || data == null || len <= 0 || s.peerIp.isEmpty()) {
            return -1L;
        }
        if (udpEgressActive()) {
            // 媒体 UDP 档：编一份 audio 数据报入音频队列，真正的 socket 写在 pp-av-loop（唯一写点）。
            // seq 用本模块按 type 独立计数的 audioStreamSeq（S10），不是 caller 的 seq。
            try {
                int sq = (int) (audioStreamSeq.getAndIncrement() & 0xFFFFL);
                byte[] rep = Protocol.encodeDatagram(Protocol.DG_TYPE_AUDIO, sq, 0,
                        0, 1, ts, data, 0, len);
                qAudio.add(rep);
                return 0L;
            } catch (java.io.IOException e) {
                return -1L;
            }
        }
        // 带内写者（INBAND / 媒体 TCP 档）：主 TCP 的既有链路，写锁、帧格式、128KB 闸门一概不变（I8 / I3 例外）。
        long took = link.sendAudioTo(s.peerIp, seq, ts, data, len);
        if (took >= 0L) {
            localSentPackets++;   // 2.9：S23 的"本端已发 N 包"与 P10 的"本端这一秒有没有发"都读这个数
        }
        return took;
    }

    /**
     * 出向音频队列的待写帧数（任务单 3.3 取证）。UDP 档下 {@link #sendAudio} 只入队、
     * 真正的 socket 写在 pp-av-loop 这一个写点：写者一慢（异常/被视频挤），这里就无界堆积，
     * 对端看到的是「突然来一堆帧」，而本端任何计数器都不会动。
     * 带内 / 媒体 TCP 档不经过这条队列 ⇒ 恒 0，日志里照打，用来区分档位。
     */
    public int pendingAudioDatagrams() {
        return qAudio.size();
    }

    /**
     * 发一帧视频。PC 无采集能力（R2），本方法在 PC 侧当前**没有调用者**，
     * 保留是为了双端接口同构（安卓视频半走这里）。
     * ⚠ UDP 档：按 ≤{@link #DG_SLICE_MAX_PAYLOAD}B 切片入视频队列（帧内共用一个 videoStreamSeq），
     *   写者每 tick 最多发 {@link #VIDEO_SLICES_PER_TICK} 片 ⇒ 大 IDR 不再被 128KB 闸门整帧丢（问题2 根因之一）。
     */
    public long sendVideo(int seq, long ts, int flag, byte[] data, int len) {
        Spec s = spec;
        if (!started || data == null || len <= 0 || s.peerIp.isEmpty()) {
            return -1L;
        }
        if (udpEgressActive()) {
            int projected = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD;
            if (qVideo.size() + projected > VIDEO_QUEUE_MAX_SLICES) {
                // 队列硬顶（3.5 帧级在途窗口之前的粗护栏）：丢当前帧不入队，且必须计数（不能再"建了没人加"）
                CallCounters.get().videoDroppedByGate.inc();
                return -1L;
            }
            try {
                int vq = (int) (videoStreamSeq.getAndIncrement() & 0xFFFFL);
                int n = sliceVideoAu(vq, flag, data, 0, len, ts, (idx, count, rep) -> qVideo.add(rep));
                return n > 0 ? 0L : -1L;
            } catch (java.io.IOException e) {
                return -1L;
            }
        }
        long took = link.sendVideoTo(s.peerIp, seq, ts, flag, data, len);
        if (took >= 0L) {
            localSentPackets++;   // 2.9：同 sendAudio（视频半在安卓走这里，PC 本方法当前无调用者）
        }
        return took;
    }

    /**
     * 发一份控制快照。⚠ 载体待接：3.13 落 ctrl 的 14B 线格式，
     * 带内档按 D14-3 退回主 TCP 既有帧。在那之前本方法不做任何事（也没有调用者）。
     */
    public void sendCtrl(Ctrl ctrl) {
        // 3.13：ownerBits / epoch / seq 上线后由这里按当前档发出（媒体报或带内帧）
    }

    /**
     * 收帧入口：只允许 App 接线层（PcMain 的 Link.Listener 转发）调用。
     * 模块**不做源地址过滤** —— 改前 PC 在 CallManager 里按 peerIp 过滤音频/视频、
     * 安卓音频同、安卓视频由 VideoCall 首源锁定；2.7 统一收进模块。
     */
    public void deliverAudio(String fromIp, int seq, long ts, byte[] data, int len) {
        if (!started) {
            return;
        }
        notePeerMediaPacket();   // 2.9：这是"从对端收到媒体包"的另一个入口（3.4 之后是主入口）
        sink.onAudio(fromIp, seq, ts, data, len);
    }

    public void deliverVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len) {
        if (!started) {
            return;
        }
        notePeerMediaPacket();   // 2.9：同上
        sink.onVideo(fromIp, seq, ts, flag, data, len);
    }

    /**
     * 关会话。带内档没有端口可关（改前也是如此）；2.3 起这里会释放本会话已 bind 的
     * UDP socket / 媒体 TCP 监听（{@link Bound#release()}，幂等）；
     * **2.6 起这里还关 ④ 预建并保持空闲的那条媒体 TCP**（{@code mediaTcp}）。
     * 2.6 自查项「预建连接在 挂断 / 异常 / 切后台 / **呼叫失败** 四条路径里都有关闭调用」成立的方式，
     * 就是那四条全部汇聚到本方法、而本方法只走 {@link #releaseBound()} 这**一个**出口（与 2.9 对齐）。
     * 2.9 的五条退出路径全部汇聚到本方法。
     */
    public void stop() {
        started = false;
        // 定档时序可能还在 pp-av-negotiate 上跑（振铃期用户就点了挂断 / 无人接听超时）：
        // 先叫停，循环每一步都查这个标志；下面那次 releaseBound() 负责把此刻已 bind 的端口放掉。
        negotiationStopped = true;
        spec = Spec.inband("");
        peerUdpPort = 0;
        peerTcpPort = 0;
        peerMsid = "";
        peerTierOrdinal = -1;
        peerMediaTarget = null;   // 2.7：实测收包源随本通一起作废（下一通重新学）
        inbandDowngraded = false;
        tier = Tier.INBAND;
        // 2.9：五个计数与两条闩锁一并作废（与 start 的归零成对 —— 残留值绝不会流到下一通的 S23 日志里）
        localSentPackets = 0L;
        localRcvPackets = 0L;
        feedbackRcvPackets = 0L;
        peerLastRxAtMs = 0L;
        lastFeedbackAtMs = 0L;
        suspectedBlockedLatched = false;
        bothQuietSinceMs = 0L;
        mediaInboundAdmit = null;   // 2.10：注入的准入判据随本通作废（下一通由 openTransport 重新配）
        // 3.2：残留半帧随本通作废（下一通重新建缓冲）；此处清空把未完成帧按失败计一次，
        //   与超时/溢出同口径 —— 挂断时正在凑的那几帧本就是"缺任一片整帧丢"。
        videoReassembler.clear();
        // 3.3：先关本端 UDP 媒体 IO（摘 writer/reader 引用 ⇒ sendAudio/sendVideo 立即回落带内、
        //   不再往将死的队列塞），interrupt 叫醒 sleep 中的写者与阻塞中的 receive，再清四条队列。
        stopMediaIo();
        // 2.9 的硬要求"结束后记得关闭传输线程"：本会话的两条线程都在这里收 ——
        //   pp-av-negotiate：上面 negotiationStopped=true 叫停，循环每一步都查它（最迟本档窗口结束）；
        //   pp-av-alive：下面摘引用 + interrupt（睡眠中的线程立即退出，不等下一个 tick）。
        // ⚠ 五条退出路径（挂断 / 异常 / 对端消失 / 切后台销毁 / 呼叫失败）全部经 closeTransport 走到本方法，
        //   所以"关线程 + 关 UDP + 关预建与未选中的媒体 TCP + 释放已 bind 端口"只有这一个出口。
        shutdownAliveThread();
        // 2.3 + 2.6：释放本会话已 bind 的两侧端口与监听，以及预建那条连接。
        // 先摘引用再关 ⇒ 重复调用（2.9 的五条退出路径都会汇到这里、呼叫失败分支、以及定档时
        // 未采纳的那一侧）不会二次关闭出异常，Bound.release() 与 socket close 自身都是幂等的。
        releaseBound();
    }

    /**
     * 叫停在跑的定档时序（2.9 的挂断 / 取消 / 呼叫失败路径与 {@link #stop()} 共用）。
     * 幂等、不阻塞：只置标志，probe 循环最迟在本档窗口结束时退出。
     * ⚠ 本方法**不关**任何东西：2.9 的退出路径要么调 {@link #stop()}，要么当时序在标志上退出来
     *   拿结论时，由 {@link #negotiate()} 自己的 releaseBound() 收尾（含 2.6 的预建连接）。
     */
    public void stopNegotiation() {
        negotiationStopped = true;
    }

    /**
     * 摘引用并释放本端持有的**全部**媒体资源：本档 UDP 侧、本档媒体 TCP 监听，
     * 以及 2.6 预建并保持空闲的那条 {@code mediaTcp}。
     * 时序里每一处"本档不通"、⑥⑦ 的落带内 / 呼叫失败，以及 stop() 都走这一个出口。
     */
    private void releaseBound() {
        Bound u = boundUdp;
        boundUdp = null;
        Bound t = boundTcp;
        boundTcp = null;
        java.net.Socket so = mediaTcp;
        mediaTcp = null;
        boundRound = -1;
        if (u != null) {
            u.release();
        }
        if (t != null) {
            t.release();
        }
        if (so != null) {
            try {
                so.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
