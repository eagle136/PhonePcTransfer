package com.phonepctransfer.pc;

// 媒体传输模块:五档协商+单写UDP;通话中不改档,仅带内用writeLock
public final class AvTransport {

    /** 五档:声明序即试档序①→⑤;档表只此一处定义 */
    public enum Tier {
        UDP_443,         // ① UDP 443（安卓无 root ⇒ 本档整体跳过，不是"试了失败"）
        UDP_RANDOM,      // ② UDP 随机 4000–50000
        TCP_443,         // ③ 媒体 TCP 443（安卓同①跳过）
        TCP_RANDOM,      // ④ 媒体 TCP 随机 4000–50000
        INBAND           // ⑤ 消息端口带内（主 TCP，受 D16 通话期文件排他约束）
    }

    // ---- 端口选择(方案§4.1) ----

    /** 随机档区间，含端点（§4.1：4000–50000）。 */
    public static final int MEDIA_PORT_MIN = 4000;
    public static final int MEDIA_PORT_MAX = 50000;

    /** 避让清单:双端一字不差,勿精简 */
    public static final int[] PORT_AVOID = {
            8080, 8000, 8008, 8009, 8888, 3000, 3128, 5000, 5001, 5353,
            5357, 1900, 3389, 7000, 9000, 28765, 28766
    };

    /** 每档每协议实试8次,败退系统分配(port 0),再进下档 */
    public static final int BIND_MAX_ATTEMPTS = 8;

    /** 443档口:仅bindTier两分支用;档表仍只Tier一份 */
    public static final int PRIVILEGED_MEDIA_PORT = 443;

    /** 本端档序列:PC全试,安卓滤权限档;一律从Tier#values()推 */
    public static java.util.List<Tier> tiersForThisEnd() {
        return java.util.Arrays.asList(Tier.values());
    }

    /** 本档是否UDP档(false=媒体TCP档);协商时序按此分两支 */
    public static boolean isUdp(Tier t) {
        return t == Tier.UDP_443 || t == Tier.UDP_RANDOM;
    }

    // ---- 一轮=同端口族UDP与TCP两侧 ----

    /** 一轮=UDP档+同族媒体TCP档;tcpTier可null */
    private static final class Round {
        final Tier udpTier;
        final Tier tcpTier;

        Round(Tier udpTier, Tier tcpTier) {
            this.udpTier = udpTier;
            this.tcpTier = tcpTier;
        }
    }

    /** 本端轮序列:档序列每UDP档配同族TCP档;带内不进轮列 */
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

    // ---- 振铃期探测与定档时序:数全来自§4.3,不新增 ----

    /** §4.3②：拿到对端本档端口后各发 3 个 probe。 */
    public static final int PROBE_COUNT = 3;

    /** §4.3②：probe 间隔 30ms。 */
    public static final long PROBE_INTERVAL_MS = 30L;

    /** 300ms判定窗:兼三元组交换等待窗与④连接/接受超时 */
    public static final long PROBE_WINDOW_MS = 300L;

    // ---- 视频分片与重组:切片≤1200B防IP分片;数由§4.4推导 ----

    /** 单报(含12B头+ts)≤1200B,避免IP分片 */
    public static final int DG_MAX_REPORT = 1200;

    /** 分片体上限=1200−12B头=1188B(§4.4推导) */
    public static final int DG_SLICE_MAX_PAYLOAD = DG_MAX_REPORT - Protocol.DG_OVERHEAD;

    /** 媒体UDP内核缓冲1MB:默认65536B只装54片,大关键帧必缺片 */
    static final int UDP_BUFFER_BYTES = 1 << 20;

    /** 重组窗40ms:末片起算,无新片判死;与安卓同值同序 */
    public static final long REORDER_WINDOW_MS = 40L;

    /** 并发重组上限16帧:满丢最旧帧并计数,关键帧不丢 */
    public static final int REASSEMBLE_MAX_FRAMES = 16;

    // ---- 单写者节拍:tick 20ms,视频每tick 256片 ----

    /** 发送tick 20ms:对齐一个音频包周期 */
    public static final long SEND_TICK_MS = 20L;

    /** 视频每tick预算256片:覆盖实测最大IDR(202片),一tick发完 */
    public static final int VIDEO_SLICES_PER_TICK = 256;

    /** 在途片队列顶512:满则丢当前帧不入队并计数(videoDroppedByGate) */
    public static final int VIDEO_QUEUE_MAX_SLICES = 512;

    /** AU切≤1188B的video_slice报:帧内片共用streamSeq;只产字节,写点在单写者 */
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

    /** 逐片出口:writer把report写进socket */
    public interface SliceSink {
        void slice(int idx, int count, byte[] report) throws java.io.IOException;
    }

    /** 重组完成后交消费者的AU:IDR/CONFIG位原样带出(判关键帧) */
    public static final class PendingAu {
        public final int flag;
        /** 发送端通话相对ms(取首片dg.ts),交sink.onVideo的ts位 */
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

    /** 切片重组器:缺片/超窗整帧丢;键=streamSeq,一帧一桶 */
    public static final class VideoReassembler {

        private static final class Frame {
            final int count;
            final byte[][] parts;
            final boolean[] have;
            final int flag;
            final long ts;
            final long firstRxAtMs;
            /** 最近一片到达时刻:超窗判据用它(非首片);reader单线程域 */
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

        /** 当前并发重组中的帧数（供缓冲上限判定与观测）。 */
        public int size() {
            return frames.size();
        }

        /** 半帧丢失通报:参考链已断,上层停喂死链帧并向对端要IDR */
        public interface LossListener {
            void onFrameLost(int seq, int missing, int count, boolean key);
        }

        private volatile LossListener lossListener;

        // 取证环:记最近被扫掉的半帧seq(容量64),判"缺的片后来没到"
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

        /** 该seq最近被扫掉过吗(窗后迟到片):按片计一次,不做命中即忘 */
        private boolean isSweptRecently(int seq) {
            for (int i = 0; i < sweptCount; i++) {
                if (sweptSeqs[i] == seq) {
                    return true;
                }
            }
            return false;
        }

        /** 唯一接线点=本类构造(转Sink#onVideoChainBroken) */
        public void setLossListener(LossListener l) {
            lossListener = l;
        }

        /** 丢帧统一出口:取证计数+迟到环+日志+通报;仅reader线程调 */
        private void reportLost(int seq, Frame f, String why) {
            int missing = f.count - f.got;
            boolean key = (f.flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG)) != 0;
            CallCounters.get().videoReassembleFail.inc();
            // 取证:半帧片数+是否参考链起点;与半帧个数并读分诊
            CallCounters.get().videoSliceMissing.add(missing);
            if (key) {
                CallCounters.get().videoKeyframeLost.inc();
            }
            rememberSwept(seq);
            // 逐事件一行:正常通话不出现;判死看末片至今
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

        /** 投一片:非null=刚凑齐的整AU;seq在迟到环则videoSliceLate+1 */
        public PendingAu add(Protocol.Datagram dg, long nowMs) {
            int seq = dg.streamSeq;
            int count = dg.sliceCount;
            int idx = dg.sliceIdx;
            Frame f = frames.compute(seq, (k, cur) -> {
                if (cur == null) {
                    // 新建时按count预算(形状校验已保证≥1且idx<count)
                    return new Frame(count, dg.flags, dg.ts, nowMs);
                }
                return cur;
            });
            // 同帧count必须一致:不符=串帧(老seq复用),丢这一片
            if (f.count != count) {
                return null;
            }
            // seq在迟到环⇒窗后迟到片,按片计一次
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
                // 每来一片把超窗基准往后推(判据见sweepTimeoutMs)
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
                // 交付flag只留与带内帧制同义的IDR/CONFIG两位(bit2是报专有LAST_SLICE)
                return new PendingAu(f.flag & (Protocol.DG_FLAG_IDR | Protocol.DG_FLAG_CONFIG),
                        f.ts, au, total);
            }
        }

        /** 超龄整帧丢:末片起算超40ms无新片且未凑齐⇒删并计数;reader每批调 */
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

        /** 缓冲上限:超16帧丢最旧非关键帧并计数;全关键帧则不丢 */
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

        /** 会话结束/定档切换时清空缓冲(幂等);残留半帧按失败计 */
        public void clear() {
            int n = frames.size();
            frames.clear();
            // 迟到环随本通作废:不用上一通的seq判迟到
            sweptNext = 0;
            sweptCount = 0;
            for (int i = 0; i < n; i++) {
                CallCounters.get().videoReassembleFail.inc();
            }
            // 不走reportLost:挂断残留非通话期丢片证据,也不触发要IDR
        }
    }

    // ---- 通话期存活判定,数来自方案原文 ----

    /** 1s无反馈⇒记mediaSuspectedBlocked;不回退/不换端口;兼检查周期 */
    public static final long FEEDBACK_SUSPECT_MS = 1_000L;

    /** 通话中途双向10s零包⇒上报无流,挂断由状态机做 */
    public static final long NO_STREAM_HANGUP_MS = 10_000L;

    /** 一档bind到的资源:UDP档只有udp,媒体TCP档只有tcpListener;release幂等 */
    public static final class Bound {
        public final Tier tier;
        public final int port;
        /** true=8次实试都败后系统分配(port 0);数字照样广告 */
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

        /** 幂等关闭:释放已bind的端口/监听;各退出路径都靠它 */
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

    /** 为一档bind本端资源:失败静默返null(不报错不挂断);INBAND恒null;仅定档时序调用 */
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

    /** 随机档:避让口不占bind名额;8次实试后转系统分配 */
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

    /** UDP bind:不带REUSEADDR(防上一通残留抢包);内核缓冲必须在bind前设1MB */
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

    /** 媒体TCP监听:REUSEADDR(防上一通TIME_WAIT);端口每通新建,只负责占口 */
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
        /** 本端随机token:给probe打戳;每端各自生成 */
        public final String localMsid;
        /** 对端token(invite/accept初值,每档call_media覆盖);空=老端未声明 */
        public final String peerMsid;
        /** 对端媒体端口（协商来；0 = 对端未声明） */
        public final int peerMport;
        /** 对端媒体端口是否 UDP（协商来；false = 对端未声明） */
        public final boolean peerMudp;
        /** true=本端主叫:只定媒体TCP档谁发起连接(主叫拨,被叫accept) */
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

        /** 过渡构造:只有对端地址,协商字段缺省⇒恒落带内 */
        public static Spec inband(String peerIp) {
            return new Spec(peerIp, "", "", 0, false, false);
        }
    }

    /** 控制状态全量快照:字段名照§3-D14-1;测量值在Stats */
    public static final class Ctrl {
        public int epoch;                 // 本次媒体会话号（跨通话不回绕）
        public long seq;                  // 会话内单调递增;旧epoch/seq≤已见最大值直接丢

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

    /** 反馈/测量值(不属于ctrl那组),由100ms反馈填充 */
    public static final class Stats {
        public long queueMs;
        public int dropPct;
        public long latMs;
        public long renderGapMs;
        public int loss;
        public long jitter;
    }

    /** 上层回调:存活两事件只上报,处置归状态机 */
    public interface Sink {
        void onAudio(String fromIp, int seq, long ts, byte[] data, int len);

        void onVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len);

        /** 重组丢半帧⇒参考链断:上层停喂死链帧并向对端要IDR */
        default void onVideoChainBroken(boolean key) {
        }

        default void onCtrl(Ctrl ctrl) {
        }

        default void onStats(Stats stats) {
        }

        /** 1s无反馈的上升沿报一次;text是拼好的一行,UI照显示 */
        default void onMediaSuspectedBlocked(String text) {
        }

        /** 中途无流达10s⇒上层自动挂断+提示;本模块只送判据 */
        default void onMediaNoStream() {
        }
    }

    private final Link link;
    private final Sink sink;

    private volatile Spec spec = Spec.inband("");
    private volatile Tier tier = Tier.INBAND;
    private volatile boolean started;
    /** 本轮已bind的两侧资源:各一份可null;定档后只留采纳侧,其余全关 */
    private volatile Bound boundUdp;
    private volatile Bound boundTcp;
    /** 已bind的是第几轮(roundsForThisEnd下标);-1=未持任何轮资源 */
    private volatile int boundRound = -1;
    /** 预建保持空闲的媒体TCP:writer直接用,免再握手 */
    private volatile java.net.Socket mediaTcp;

    // ---- 对端广告三元组(invite/accept初值,每轮call_media覆盖);按协议分两槽 ----
    private volatile int peerUdpPort;
    private volatile int peerTcpPort;
    private volatile String peerMsid = "";
    /** 对端自报"当前第几轮"(同轮两份call_media带同一个数);不作连通性判据,只进日志 */
    private volatile int peerTierOrdinal = -1;
    /** 实际发送目标=最近收包源地址:端口;唯一写入点notePeerSource */
    private volatile java.net.InetSocketAddress peerMediaTarget;
    /** 定档时序被叫停(挂断/取消/呼叫失败/stop);probe循环每步查 */
    private volatile boolean negotiationStopped;
    /** ⑥五档全不通⇒落带内(明示降级);上层降级提示与聚合恒关读它 */
    private volatile boolean inbandDowngraded;
    /** ctrl报的streamSeq:按类型独立计数;16位取模,回绕由在途窗处理 */
    private final java.util.concurrent.atomic.AtomicLong ctrlStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /** 切片重组器:每通新建;投放点=avRecvLoop(收到video_slice报) */
    private final VideoReassembler videoReassembler = new VideoReassembler();

    // ---- 媒体面单写者+严格优先级队列(仅UDP档;带内走主TCP writeLock) ----

    /** 音频流seq:按type独立计数;16位取模 */
    private final java.util.concurrent.atomic.AtomicLong audioStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();
    /** 视频流seq:每帧领一个号(帧内所有片共用);16位取模 */
    private final java.util.concurrent.atomic.AtomicLong videoStreamSeq =
            new java.util.concurrent.atomic.AtomicLong();

    // 投递侧扩展序号(RTP extended seqno口径):16位回绕展开成单调int,下游判据不改
    private int audioSeqExt;
    private int audioSeqLastU16 = -1;
    private int videoSeqExt;
    private int videoSeqLastU16 = -1;

    /** 音频:16位streamSeq展开成单调int后投递;单reader线程,免锁 */
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

    /** 四条优先级队列:写者按audio>ctrl>feedback>video顺序排空(§4.5) */
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qAudio =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qCtrl =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qFeedback =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> qVideo =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** pp-av-loop(写者)/pp-av-recv(reader);null=未启或已关;唯启=selectUdpSide */
    private volatile Thread writerThread;
    private volatile Thread recvThread;

    /** 已回数的probe份数(上限PROBE_COUNT);写读都在pp-av-recv,start归零 */
    private volatile int probeReplies;

    // ---- 存活检查:那行日志取自下面五个量(缺一项就不达标) ----

    /** 本端已发包数(交给写者的媒体帧);定档前判定不跑,不会误报 */
    private volatile long localSentPackets;
    /** 本端收包计数(媒体面;reader起前只有对端probe) */
    private volatile long localRcvPackets;
    /** 收到的反馈数;反馈通道接入前恒0 */
    private volatile long feedbackRcvPackets;
    /** 对端最后一包到达时刻(epoch ms);0=本通还没收到过 */
    private volatile long peerLastRxAtMs;
    /** 最后收到反馈的时刻(epoch ms);0=本通还没收到过 */
    private volatile long lastFeedbackAtMs;

    /** 存活检查线程(pp-av-alive);唯启=定档采纳四档之一;带内档不起 */
    private volatile Thread aliveThread;
    /** "疑似被拦"是否已就本次上报(上升沿只报一次;收反馈后复位) */
    private volatile boolean suspectedBlockedLatched;
    /** 双向静默起点(epoch ms);0=非无流态;无流10s从它算 */
    private volatile long bothQuietSinceMs;
    /** 媒体入向准入注入点:"通话中+已知设备+黑名单"三半;null=拒绝 */
    private volatile java.util.function.Predicate<String> mediaInboundAdmit;

    /** 注入媒体入向准入判据:openTransport与start同一处配,stop随本通清;只影响被叫侧accept */
    public void setMediaInboundAdmit(java.util.function.Predicate<String> admit) {
        mediaInboundAdmit = admit;
    }

    public AvTransport(Link link, Sink sink) {
        this.link = link;
        this.sink = sink;
        // 半帧丢=参考链断:只通报不定处置(处置在CallManager→FfmpegDecoder)
        videoReassembler.setLossListener((seq, missing, count, key) -> sink.onVideoChainBroken(key));
    }

    /** 建媒体会话:登记spec并立刻bind第0档两侧(端口要早于协商报文出去) */
    public void start(Spec spec) {
        this.spec = spec == null ? Spec.inband("") : spec;
        this.tier = Tier.INBAND;
        this.started = true;
        Spec s = this.spec;
        // 三元组进模块(probe定目标/校验msid);invite/accept只带一份,按mudp落对应侧槽
        this.peerUdpPort = s.peerMudp ? s.peerMport : 0;
        this.peerTcpPort = s.peerMudp ? 0 : s.peerMport;
        this.peerMsid = s.peerMsid;
        this.peerTierOrdinal = -1;
        // 发送目标随每通重新学(端口每通新建不复用)
        this.peerMediaTarget = null;
        this.negotiationStopped = false;
        this.inbandDowngraded = false;
        // 五个计数+两条闩锁随每通归零(跨通累积会误报存活日志)
        this.localSentPackets = 0L;
        this.localRcvPackets = 0L;
        this.feedbackRcvPackets = 0L;
        this.peerLastRxAtMs = 0L;
        this.lastFeedbackAtMs = 0L;
        this.suspectedBlockedLatched = false;
        this.bothQuietSinceMs = 0L;
        this.probeReplies = 0;
        // 扩展序号随每通重新起算(16位序号按通独立,高位归零)
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

    /** ①:bind一轮两侧,各自走bindTier(8次实试+系统分配退路);失败静默不打日志 */
    private void bindRound(Round r) {
        this.boundUdp = bindTier(r.udpTier);
        this.boundTcp = r.tcpTier == null ? null : bindTier(r.tcpTier);
    }

    /** 本端已bind等着随协商报文带出的媒体端口;0=两侧都不成立/走带内 */
    public int mediaPort() {
        Bound b = advertisedSide();
        return b == null ? 0 : b.port;
    }

    /** mediaPort()那个端口是否UDP(与mediaPort()同一时刻读才自洽) */
    public boolean mediaIsUdp() {
        Bound b = advertisedSide();
        return b != null && isUdp(b.tier);
    }

    /** invite/accept那份三元组带哪侧:§4.1声明序UDP优先,UDP不成立退TCP */
    private Bound advertisedSide() {
        Bound u = boundUdp;
        return u != null ? u : boundTcp;
    }

    /** 本端本次通话的token:CallManager写进invite/accept/call_media的msid */
    public String localMsid() {
        return spec.localMsid;
    }

    /** 收到对端媒体三元组:invite/accept一份+call_media每轮两份(mudp分槽互不覆盖) */
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

    /** 振铃期定档时序(§4.3七步):按窗口阻塞,只在独立线程跑;true=已定档 */
    public boolean negotiate() {
        Spec s = spec;
        if (!started || negotiationStopped || s.peerIp.isEmpty()) {
            releaseBound();
            return false;
        }
        if (!s.caller && peerMsid.isEmpty()) {
            // 被叫侧invite是"对端声明了没有"的权威证据:空=老端⇒直接落带内
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
                // ⑤:上一轮已整轮释放,给新一轮bind两侧;第0轮已由start()bind
                releaseBound();
                bindRound(r);
                boundRound = i;
            }
            // stop()与本bind的竞态:先写bound再查标志(均volatile);缺查会泄漏刚bind的端口
            if (negotiationStopped) {
                releaseBound();
                return false;
            }
            Bound u = boundUdp;
            Bound tc = boundTcp;
            if (u == null && tc == null) {
                // §4.1:bind失败静默跳下一档,不报错不挂断
                logTier(r, i, "本档两侧 bind 都不成立 ⇒ 静默进下一档");
                continue;
            }
            advertise(i);
            // ②③:本档UDP侧;对端没广告端口时probe不发,窗口走空
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
            // ④:同档媒体TCP侧;端口①已bind好,不回①
            if (tc != null && probeMediaTcpRound(tc)) {
                if (negotiationStopped) {
                    // 预建那条也在releaseBound范围:窗口刚握好被叫停⇒不留连接
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

    /** 定档采纳UDP侧:同档TCP监听立即关;起媒体IO与存活看门狗 */
    private void selectUdpSide(Tier t) {
        tier = t;
        Bound b = boundTcp;
        boundTcp = null;
        if (b != null) {
            b.release();
        }
        // 起媒体IO(唯一writer+reader);须在startAliveWatchdog前
        startMediaIo();
        // 采纳成四档之一⇒有反馈通道,存活判定从这里起(带内档不到这里)
        startAliveWatchdog();
    }

    /** 定档采纳TCP侧:UDP侧与TCP监听立即关;只留④握成的那条(mediaTcp) */
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
        // 本轮只落UDP侧自有IO:TCP档mediaUsesMainTcp仍true,看门狗判据不过不起线程
        startAliveWatchdog();
    }

    // ---- 媒体IO(UDP档的自有writer+reader) ----

    /** 起UDP媒体IO两线程:唯一调用=selectUdpSide;幂等;不碰Link writeLock */
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

    /** 关UDP媒体IO:摘引用+interrupt+清四队列(幂等);socket由releaseBound关 */
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

    /** 写者线程:每20ms一个tick,严格优先级排空;唯一写socket处 */
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

    /** 发一份数据报到媒体目标:失败静默(丢报由loss%体现);成功计localSentPackets */
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

    /** reader线程:收boundUdp数据报按类型分发;收到任何媒体包都notePeerSource */
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
                // 缓冲上限在此生效:超上限丢最旧非关键帧并计数
                videoReassembler.evictOverflow();
                if (au != null) {
                    sink.onVideo(peer, extendVideoSeq(dg.streamSeq), au.ts, au.flag, au.data, au.len);
                }
            }
            // ctrl/feedback本轮不在此消费(带内走Link,反馈归反馈通道);例外是对端probe
            else if (dg.type == Protocol.DG_TYPE_CTRL && isPeerProbe(rx, p.getLength())) {
                answerPeerProbe();
            }
        }
    }

    /** ①交换:一轮两份call_media(mudp=true/false);主叫第0轮UDP份不重发(已随invite) */
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

    /** ②③:UDP双向连通测试;300ms窗内发3probe+收对端probe校验msid;只定路不交数据 */
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
                // 等待上限=探针间隔(非整窗剩余):否则probe2/3发不出,两端恒落第五档
                u.setSoTimeout((int) Math.min(left, PROBE_INTERVAL_MS));
                p.setLength(rx.length);
                u.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                continue;   // 窗口还没走完，回循环头重算 left
            } catch (java.io.IOException | RuntimeException e) {
                return false;   // socket 被 stop() 关掉 / 收包异常：本档按不通处理，不上抛
            }
            if (isPeerProbe(rx, p.getLength())) {
                // 唯一的媒体面收包点⇒发送目标只在这里按实测源更新
                notePeerSource(p.getSocketAddress());
                notePeerMediaPacket();   // 同一个收包点，收包计数与"最后一包到达时刻"也在这里进
                return true;
            }
        }
        return false;
    }

    /** ②:一份probe ctrl报;字节由codec产;发送失败不判死,由窗口定 */
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
            // 静默:端口已关/对端不可达都属本档不通,交给窗口判定
        }
    }

    /** 一份probe ctrl报的字节:sendProbe与回数共用同一份编码,不许第二套 */
    private byte[] encodeProbe() {
        try {
            byte[] body = spec.localMsid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return Protocol.encodeCtrl(Protocol.CT_PROBE,
                    (int) (ctrlStreamSeq.getAndIncrement() & 0xFFFFL),
                    CallClock.elapsedMs(), body, 0, body.length);
        } catch (java.io.IOException | RuntimeException e) {
            return null;   // msid超ctrl报上限:token恒36字符UUID,走不到这里
        }
    }

    /** 定UDP档后收到对端probe⇒回数一份(只入qCtrl);防两端窗口错位劈裂成一边UDP一边带内 */
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

    /** 媒体面发送目标的唯一写入点;只许在"收到对端媒体包"处调;源变才打日志 */
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

    /** 该往哪打:有实测收包源用它;没有退回初值(invite/accept的IP+对端广告端口) */
    private java.net.InetSocketAddress mediaTarget(int fallbackPort) {
        java.net.InetSocketAddress t = peerMediaTarget;
        if (t != null) {
            return t;
        }
        return new java.net.InetSocketAddress(spec.peerIp, fallbackPort);
    }

    /** 媒体TCP主叫拨号拨哪个IP:实测收包源IP优先;端口不跟实测源(那是UDP侧的) */
    private String mediaTargetIp() {
        java.net.InetSocketAddress t = peerMediaTarget;
        return t == null || t.getAddress() == null
                ? spec.peerIp : t.getAddress().getHostAddress();
    }

    /** 这份报是不是本通对端的probe(类型+子类型+msid三项都对才算) */
    private boolean isPeerProbe(byte[] buf, int len) {
        String want = peerMsid;
        if (want.isEmpty()) {
            return false;   // 没拿到对端token⇒无从校验,一律不算通(§4.3②硬自查)
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
            return false;   // 畸形报直接丢这一份，不吞成「看着像空报」
        }
    }

    /** ④:媒体TCP连通尝试(与②③同轮);测通即预建保持空闲;主叫拨被叫接 */
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
                // 连接超时=本档窗口;拨的IP用mediaTargetIp(),端口仍是对端广告的TCP侧
                so.connect(new java.net.InetSocketAddress(mediaTargetIp(), peerTcpPort),
                        (int) PROBE_WINDOW_MS);
                mediaTcp = so;   // 预建保持，writer接手
                return true;
            } catch (java.io.IOException e) {
                // 拒连/超时=本档不通,静默换下一档;没用的连接就地关不留半开
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
            // 被叫侧accept必须来源校验,三分句拒
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
            mediaTcp = so;   // 被叫侧只收一条:窗口内通过准入的那条就是预建
            return true;
        } catch (java.io.IOException e) {
            return false;   // 窗口内没人连进来 / 监听已被 stop() 关掉
        }
    }

    /** 媒体入向准入判据:只用于被叫侧accept;三条件与关系,缺一律拒 */
    private boolean admitMediaInbound(String remoteIp) {
        if (remoteIp == null || remoteIp.isEmpty() || peerMsid.isEmpty()) {
            return false;   // ③
        }
        java.util.function.Predicate<String> admit = mediaInboundAdmit;
        return admit != null && admit.test(remoteIp);   // ①②②b（上层的表）
    }

    /** 对端本档这一侧端口能否用来探(token已到+端口有效);两槽独立互不冲 */
    private boolean peerReadyFor(boolean wantUdp) {
        return !peerMsid.isEmpty() && (wantUdp ? peerUdpPort > 0 : peerTcpPort > 0);
    }

    /** ⑥⑦:五档全不通⇒落消息端口带内并明示降级;主TCP无会话⇒呼叫失败 */
    private boolean settleInband(String why) {
        // 带内复用主TCP:不留已bind媒体端口,也不留④预建那条(releaseBound唯一出口)
        releaseBound();
        if (!link.isConnectedIp(spec.peerIp)) {
            // ⑦：带内也不成立 ⇒ 提示呼叫失败、不进 TALKING
            Log.i("[媒体定档] " + why + "，且主 TCP 会话已不在 ⇒ ⑦ 呼叫失败，不进 TALKING");
            return false;
        }
        tier = Tier.INBAND;
        inbandDowngraded = true;
        // ⑥:落带内⇒UI降级提示+文件排他+聚合恒关
        Log.i("[媒体定档] " + why + " ⇒ ⑥ 落消息端口带内（第五档）。降级提示：本次通话期间文件不发送、"
                + "网络外排队（D16）；聚合恒关（I13）");
        return true;
    }

    /** 每轮一行结果(带两侧端口):避免bind失败逐次刷屏 */
    private void logTier(Round r, int i, String result) {
        Bound u = boundUdp;
        Bound tc = boundTcp;
        Log.i("[媒体定档] 第" + (i + 1) + "轮 " + r.udpTier + " / " + r.tcpTier
                + "（本端 UDP " + (u == null ? 0 : u.port)
                + "、媒体 TCP " + (tc == null ? 0 : tc.port) + "）→ " + result);
    }

    /** ⑥:本次通话落到带内档(降级);上层只读 */
    public boolean mediaDowngradedToInband() {
        return inbandDowngraded;
    }

    /** 媒体实际承载是否仍走主TCP:判据是媒体帧实际走带内写者,不是定成了哪档 */
    public boolean mediaUsesMainTcp() {
        return !udpEgressActive();
    }

    /** UDP档writer/reader是否就绪:不硬编码档位,三件同时成立⟺UDP档且IO在跑 */
    private boolean udpEgressActive() {
        if (!started) {
            return false;
        }
        Bound b = boundUdp;
        java.net.DatagramSocket u = b == null ? null : b.udp;
        return u != null && !u.isClosed() && writerThread != null;
    }

    /** 本次通话允不允许聚合包(硬闸):读mediaUsesMainTcp而非tier;定档前恒false */
    public boolean aggregationAllowed() {
        return !mediaUsesMainTcp();
    }

    /** 存活判定前置闸门:本档有没有100ms反馈通道;定档前恒false⇒判定休眠 */
    public boolean hasFeedbackChannel() {
        return !mediaUsesMainTcp() && tier != Tier.INBAND;
    }

    /** 反馈帧唯一计入口;不许另立第二个计数 */
    public void noteFeedback() {
        feedbackRcvPackets++;
        lastFeedbackAtMs = System.currentTimeMillis();
    }

    /** 收包计入口(与notePeerSource成对):只计对端媒体面包,喂存活判定 */
    private void notePeerMediaPacket() {
        localRcvPackets++;
        peerLastRxAtMs = System.currentTimeMillis();
    }

    /** 起存活检查线程;只在有反馈通道的四档起,带内档不起;绝不换档/换端口 */
    private void startAliveWatchdog() {
        if (aliveThread != null || !started || !hasFeedbackChannel()) {
            return;
        }
        Thread t = new Thread(this::aliveLoop, "pp-av-alive");
        t.setDaemon(true);
        aliveThread = t;
        t.start();
    }

    /** aliveWatchdog线程体:周期=1s,不另设数字 */
    private void aliveLoop() {
        long prevSent = localSentPackets;
        long prevRcv = localRcvPackets;
        long prevFb = feedbackRcvPackets;
        while (started && hasFeedbackChannel()) {
            try {
                Thread.sleep(FEEDBACK_SUSPECT_MS);
            } catch (InterruptedException e) {
                return;   // stop()叫停⇒确定性退出
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
            // 本端已发+本1s零反馈⇒疑似中途被拦(只提示不切换)
            // 前提fb>0:判"反馈在流突然停了";不加会每条通话误报
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
            // 双向0包达10s⇒上报无流,挂断由状态机做
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

    /** 关存活线程:摘引用+interrupt;没起过就是空操作 */
    private void shutdownAliveThread() {
        Thread t = aliveThread;
        aliveThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** 当前档:只给模块内部与日志用,上层不许读它做分支(D3) */
    public Tier tier() {
        return tier;
    }

    /** 发一帧音频;UDP档入队由pp-av-loop写,带内走主TCP;返回写时ms(-1=未发) */
    public long sendAudio(int seq, long ts, byte[] data, int len) {
        Spec s = spec;
        if (!started || data == null || len <= 0 || s.peerIp.isEmpty()) {
            return -1L;
        }
        if (udpEgressActive()) {
            // 媒体UDP档:编一份audio报入队;seq用audioStreamSeq非caller的seq
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
        // 带内写者(INBAND/媒体TCP档):主TCP既有链路,写锁/帧格式/128KB闸门不变
        long took = link.sendAudioTo(s.peerIp, seq, ts, data, len);
        if (took >= 0L) {
            localSentPackets++;   // 存活判定读这个数
        }
        return took;
    }

    /** 出向音频队列待写帧数:带内/媒体TCP档不经过队列⇒恒0 */
    public int pendingAudioDatagrams() {
        return qAudio.size();
    }

    /** 发一帧视频:PC当前无调用者(保双端接口同构);UDP档切片入队,大IDR不再被128KB闸门丢 */
    public long sendVideo(int seq, long ts, int flag, byte[] data, int len) {
        Spec s = spec;
        if (!started || data == null || len <= 0 || s.peerIp.isEmpty()) {
            return -1L;
        }
        if (udpEgressActive()) {
            int projected = (len + DG_SLICE_MAX_PAYLOAD - 1) / DG_SLICE_MAX_PAYLOAD;
            if (qVideo.size() + projected > VIDEO_QUEUE_MAX_SLICES) {
                // 队列硬顶(粗护栏):丢当前帧不入队,必须计数
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
            localSentPackets++;   // 同sendAudio
        }
        return took;
    }

    /** 发一份控制快照;线格式未落地,本方法目前不做任何事 */
    public void sendCtrl(Ctrl ctrl) {
        // ownerBits/epoch/seq上线后由这里按当前档发出(媒体报或带内帧)
    }

    /** 收帧入口:只许App接线层(PcMain转发)调;模块不做源地址过滤 */
    public void deliverAudio(String fromIp, int seq, long ts, byte[] data, int len) {
        if (!started) {
            return;
        }
        notePeerMediaPacket();   // 收媒体包的另一入口
        sink.onAudio(fromIp, seq, ts, data, len);
    }

    public void deliverVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len) {
        if (!started) {
            return;
        }
        notePeerMediaPacket();   // 同上
        sink.onVideo(fromIp, seq, ts, flag, data, len);
    }

    /** 关会话:释放已bind端口/监听与④预建连接;各退出路径都汇到本方法一个出口 */
    public void stop() {
        started = false;
        // 定档时序可能还在跑(振铃期挂断/超时):先叫停;releaseBound放掉已bind端口
        negotiationStopped = true;
        spec = Spec.inband("");
        peerUdpPort = 0;
        peerTcpPort = 0;
        peerMsid = "";
        peerTierOrdinal = -1;
        peerMediaTarget = null;   // 实测收包源随本通一起作废（下一通重新学）
        inbandDowngraded = false;
        tier = Tier.INBAND;
        // 五个计数与两条闩锁一并作废(残留值绝不流到下一通的存活日志)
        localSentPackets = 0L;
        localRcvPackets = 0L;
        feedbackRcvPackets = 0L;
        peerLastRxAtMs = 0L;
        lastFeedbackAtMs = 0L;
        suspectedBlockedLatched = false;
        bothQuietSinceMs = 0L;
        mediaInboundAdmit = null;   // 准入判据随本通作废(下通openTransport重配)
        // 残留半帧随本通作废;清空把未完成帧按失败计一次
        videoReassembler.clear();
        // 先关UDP媒体IO(回落带内+叫醒写者/receive),再清四条队列
        stopMediaIo();
        // 收本会话两条线程(negotiate靠上面标志叫停,alive摘引用+interrupt)
        shutdownAliveThread();
        // 释放两侧端口/监听与预建连接;先摘引用再关⇒重复调用不异常
        releaseBound();
    }

    /** 叫停在跑的定档时序:只置标志,幂等不阻塞;本方法不关任何东西 */
    public void stopNegotiation() {
        negotiationStopped = true;
    }

    /** 释放本端全部媒体资源:UDP侧+TCP监听+预建mediaTcp;唯一出口 */
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
