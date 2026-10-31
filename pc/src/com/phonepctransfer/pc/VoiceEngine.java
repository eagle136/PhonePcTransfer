package com.phonepctransfer.pc;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PC 端语音引擎：实时麦克风采集 → Opus 编码 → 一帧发出；
 * 收端：Opus 解码 → 按 seq 严格对齐播放。
 *
 * 与 Android 端 voice/VoiceEngine.kt 严格同构：
 *  - 48kHz 单声道 16bit。
 *  - **语音通话帧长 20ms（低延迟，2026-09-15 起默认）**，对端是老版本时回落 200ms
 *    （协商字段 = `call_invite`/`call_accept` 的 `af`）。Opus 子帧恒为 20ms，
 *    所以 20ms 帧 = 1 个子帧、200ms 帧 = 10 个子帧。
 *  - 采集/播放线缓冲只要 **2 帧**（旧实现是 4 帧 × 200ms = 800ms，是通话延迟的最大来源）。
 *  - 播放端按 seq 排序、严格按序播放；缺帧最多等 {@link #FRAME_WAIT_MS} 再补静音跳过
 *    （时间轴对齐，误差不累积）。
 */
public final class VoiceEngine {

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 1;

    /**
     * 纯语音通话帧长：**低延迟模式 = 20ms**（1 个 Opus 子帧）。
     * 只有对端也声明支持时才启用（帧长是收发硬对称常量）。
     */
    public static final int VOICE_FRAME_MS_LOWLAT = 20;

    /** 纯语音通话帧长的**兼容模式**（对端是老版本时用）：200ms */
    public static final int VOICE_FRAME_MS = 200;

    /**
     * 视频通话帧长 40ms（= 2 个 20ms Opus 子帧）。
     * 压低音频链路时延以对齐视频（唇音同步，计划 §3.7），
     * 顺带把「音频被卡住的粒度」缩到 40ms（视频帧与音频共用 writeLock）。
     */
    public static final int VIDEO_FRAME_MS = 40;

    public static final int OPUS_FRAME_MS = 20;
    public static final int OPUS_FRAME_SAMPLES = SAMPLE_RATE * OPUS_FRAME_MS / 1000; // 960

    /** 语音通话初始码率（2026-09-15 起从 96k 降到 64k） */
    public static final int BITRATE_VOICE = 64000;

    /** 语音通话最低码率（弱网自适应下限，见 CallManager 的码率自适应采样） */
    public static final int BITRATE_VOICE_LO = 32000;

    /** 视频通话档位码率：L0 → 32k；L1~L3 → 64k（常量名沿用 L1，未随四档改名） */
    public static final int BITRATE_VIDEO_L0 = 32000;
    public static final int BITRATE_VIDEO_L1 = 64000;

    /** 播放端缺帧轮询间隔（ms）：不预缓冲，队列空时多久回来看一眼 */
    public static final long POLL_MS = 4L;

    /** 缺帧容忍窗口（ms）—— 轻量抖动缓冲；超时才补静音跳过 */
    public static final long FRAME_WAIT_MS = 30L;

    /**
     * 播放积压上限（ms，2026-09-15 用户报「弱网后语音延迟好几十秒」的根因修复）。
     * 旧 playLoop 严格按 seq 追播：卡顿期间积压的帧恢复后按到达速率播放，永远追不完，
     * 每卡一次延迟净增一截。积压超过 BACKLOG_MAX_MS 直接跳到最新帧
     * （保留 BACKLOG_KEEP_MS 平滑），跳过的帧按缺帧计数。正常通话永不触发。
     */
    public static final int BACKLOG_MAX_MS = 600;
    public static final int BACKLOG_KEEP_MS = 120;

    // ==================== 10-06 播放水位（双向收敛，替代"只靠 600ms 悬崖"） ====================
    //
    // 症状（10-05 两场 PC↔PC 实测）：开播第 2 秒一次 line.write 阻塞 312ms ⇒ 队列 16 帧，
    // 此后 aBuf p50 290–304ms **整场不回落**，而 audioDroppedByLateSeq=0、积压快进 0 次 ——
    // 因为播放端消费速率被声卡钉死在 1:1，涨上去的垫层永远留着，唯一的回落是 600ms 悬崖。
    // 加上"缺帧恒等 30ms 再写静音"= 50ms 墙钟只推进 20ms 时间轴 ⇒ 每个缺帧净再加 1.5 帧。
    //
    // 两条口径分工（安卓侧同值同语义；两端的坏方向相反，见方案 §4）：
    //   · PLAY_TARGET_MS 管**上界**（队列太深 ⇒ 主动裁最旧）：治 PC 的"落后到达 ⇒ 固化垫层"。
    //     上界只判队列，不判总水位：线内那段是 write 恒阻塞造成的固定偏移，只能等声卡消化、
    //     裁不掉；若拿「总水位 − 队列」当上界，线内 42ms 时上界=38ms < 目标 80ms ⇒ 两条规则自相矛盾。
    //   · PLAY_TOTAL_MAX_MS 管**下界**（总水位太高 ⇒ 本格不取帧）：治安卓 AudioTrack.write 不阻塞时的
    //     "playedSeq 跑赢声卡 ⇒ 迟到闸门把后续帧全判死"的自锁。
    //   · 缺帧的 30ms 等待窗口只在队列 ≤ 目标时生效（等满 30ms 再写 20ms 静音 = 每个缺帧净加 1.5 帧，
    //     正是垫层的来源）。600ms 悬崖原样留着当兜底，正常通话已经够不着。

    /** 队列水位上限（ms）：队列深度超过它就每格裁掉最旧一帧，收敛到本值（不含线内） */
    public static final int PLAY_TARGET_MS = 80;

    /** 总水位上限（ms，队列 + 线内未播）：超过则本格不从队列取帧，等声卡自己消化 */
    public static final int PLAY_TOTAL_MAX_MS = 120;

    /**
     * 连续"不取帧"的格数上限（× {@link #POLL_MS} ≈ 100ms）—— **保命放行**。
     * 线内口径失真、或设备 head 根本不推进（standby）时，纯水位判据会把播放线程永久钉住；
     * 到上限就照常取帧。设备真在消费时水位每毫秒都在降，正常通话打不满这一档。
     */
    public static final int PLAY_HOLD_MAX_TICKS = 25;

    /** 队列水位的帧数下限（帧长 200ms 的兼容模式下 2 帧 = 400ms，不让水位法把老端节奏改坏） */
    private int targetFrames() {
        return Math.max(2, (int) (PLAY_TARGET_MS / frameMs));
    }

    /** 总水位的毫秒下限（3 帧）：20ms→120、40ms→120、200ms→600（= 悬崖值 ⇒ 兼容模式下 hold 恒不生效） */
    private long totalMaxMs() {
        return Math.max((long) PLAY_TOTAL_MAX_MS, 3L * frameMs);
    }

    /**
     * 当前帧长（ms）。由 {@link #configure} 在 startRecord/startPlay **之前**一次性设定，
     * **通话中不切换** —— 收发两端帧长是硬对称常量。
     */
    private volatile int frameMs = VOICE_FRAME_MS;

    /** 当前 Opus 码率（对解码端透明，可随时改） */
    private volatile int bitrate = BITRATE_VOICE;

    /**
     * 静音开关（视频通话窗口的「静音」按钮）。
     *
     * 语义 = **不发送**，而不是发送静音帧：编码直接跳过以省 CPU，但 **seq 仍然递增**，
     * 这样对端看到的是"缺帧"，走它既有的补静音逻辑 —— 与 Android 端同构。
     */
    private volatile boolean muted = false;

    public void setMuted(boolean m) {
        muted = m;
        Log.i(m ? "麦克风已静音" : "麦克风已取消静音");
    }

    public boolean isMuted() {
        return muted;
    }

    private int frameSamples() {
        return SAMPLE_RATE * frameMs / 1000;
    }

    private int frameBytes() {
        return frameSamples() * 2;
    }

    private int opusSubframes() {
        return frameMs / OPUS_FRAME_MS;
    }

    /**
     * 设定本次通话的音频参数。**必须在 startRecord()/startPlay() 之前调用**。
     *
     * @param videoMode    true = 视频通话（40ms 帧长，不受 af 协商影响）
     * @param videoBitrate 视频通话码率（L0 32k / L1~L3 64k）；语音模式忽略
     * @param voiceFrameMs 纯语音通话帧长：对端也支持时传 {@link #VOICE_FRAME_MS_LOWLAT}（20ms），
     *                     否则必须传 {@link #VOICE_FRAME_MS}（200ms，与老端对齐）
     */
    public synchronized void configure(boolean videoMode, int videoBitrate, int voiceFrameMs) {
        if (videoMode) {
            frameMs = VIDEO_FRAME_MS;
        } else {
            frameMs = (voiceFrameMs <= VOICE_FRAME_MS_LOWLAT)
                    ? VOICE_FRAME_MS_LOWLAT : VOICE_FRAME_MS;
        }
        bitrate = videoMode ? videoBitrate : BITRATE_VOICE;
        Log.i("音频配置：帧长 " + frameMs + "ms，码率 " + (bitrate / 1000) + "k，模式="
                + (videoMode ? "视频"
                : (frameMs <= VOICE_FRAME_MS_LOWLAT ? "语音(低延迟)" : "语音(兼容 200ms)")));
    }

    /** 兼容旧调用：不指定语音帧长时按低延迟模式 */
    public synchronized void configure(boolean videoMode, int videoBitrate) {
        configure(videoMode, videoBitrate, VOICE_FRAME_MS_LOWLAT);
    }

    /** 档位联动：只改 Opus 码率（解码端透明，无需重建解码器） */
    public void setBitrate(int b) {
        bitrate = b;
        try {
            OpusEncoder enc = encoder;
            if (enc != null) {
                enc.setBitrate(b);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 编码好的一帧 Opus 数据 */
    public static final class EncodedFrame {
        public final int seq;
        public final byte[] data;

        /** 编码完的时刻（本端 nanoTime）—— aTx 段「编码完→交给传输」的起点（任务单 1.2） */
        public final long encDoneNano;

        public EncodedFrame(int seq, byte[] data, long encDoneNano) {
            this.seq = seq;
            this.data = data;
            this.encDoneNano = encDoneNano;
        }
    }

    public interface Listener {
        void onEncoded(EncodedFrame frame);
    }

    /** 采集线程运行标志（与播放**独立**）：旧版两者共用一个标志，stopRecord 会把播放线程一起停掉 */
    private final AtomicBoolean recordRunning = new AtomicBoolean(false);

    /** 播放线程运行标志 */
    private final AtomicBoolean playRunning = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile OpusEncoder encoder;
    private volatile OpusDecoder decoder;
    private volatile TargetDataLine recordLine;
    private volatile SourceDataLine playLine;

    private Thread recordThread;
    private Thread playThread;

    /** 队列元素：一帧 Opus 载荷 + 它的发送端 ts（任务单 1.1 把 ts 一路带到消费点） */
    private static final class Inbound {
        final byte[] opus;

        /** 发送端**通话相对**时间戳（对端起呼=0 的 ms）；只与本端视频帧同轴比较，不与墙钟比 */
        final long ts;

        /** 本端入队完成的 nanoTime —— aBuf 段「入队→出队」的起点（任务单 1.2） */
        final long rxNano;

        Inbound(byte[] opus, long ts, long rxNano) {
            this.opus = opus;
            this.ts = ts;
            this.rxNano = rxNano;
        }
    }

    private final ConcurrentSkipListMap<Integer, Inbound> playQueue =
            new ConcurrentSkipListMap<Integer, Inbound>();

    /** 最近一帧**实际写出播放线**的发送端 ts（通话相对 ms，-1 = 尚未播出） */
    private volatile long lastPlayedTsMs = -1L;

    public long lastPlayedTsMs() {
        return lastPlayedTsMs;
    }

    /** 播放未就绪/播放线程已死时的丢帧日志节流（最多 1 行/秒，防刷屏） */
    private final java.util.concurrent.atomic.AtomicLong lastDropLogAt =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 事件日志节流窗口（ms，任务单 1.6）：缺帧/补静音/积压快进**不再逐次打印**，
     * 攒成 1 行/秒的汇总。20ms 帧下这三类最高 ~33 行/s，逐次打等于让磁盘与格式化
     * 压在播放线程的节拍下（方案 §2.4㉒）。安卓侧同名同窗口。
     */
    private static final long EVENT_SUMMARY_MS = 1000L;

    /** 播放未就绪期间被丢的帧数（接收线程累加，汇总行读并清零） */
    private final java.util.concurrent.atomic.AtomicInteger notReadyDropped =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 迟到帧（{@code seq <= playedSeq}）被丢的本秒计数与最大落后帧数（任务单 T6 取证）。
     * 与 {@link #notReadyDropped} 各自独立：两条闸门同时触发时互不掩盖，谁丢的分数看得清。
     * 只在「确实丢了」这条支路上读写（正常通话恒 0，不进热路径）。
     */
    private final java.util.concurrent.atomic.AtomicInteger lateSeqDropped =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger lateSeqMaxLag =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 迟到帧日志节流时刻（最多 1 行/秒，与 {@link #lastDropLogAt} 同款） */
    private final java.util.concurrent.atomic.AtomicLong lastLateSeqLogAt =
            new java.util.concurrent.atomic.AtomicLong(0);

    // 播放线程事件计数（任务单 1.6）：只有播放线程自己读写 ⇒ 不用原子类，也无竞争
    private int evSilenceFill;      // 缺帧补静音跳过次数
    private int evBacklogJump;      // 积压快进次数
    private int evBacklogDropped;   // 快进跳过的帧数
    private int evTrim;             // 10-06：水位裁帧次数（本秒，打进汇总行；场末另计 CallCounters）
    private int evHoldCap;          // 10-06：本秒"保命放行"次数（连续 hold 到上限 ⇒ 水位判据失去意义）
    private int holdTicks;          // 10-06：连续"总水位太高、本格不取帧"的格数
    private long evSummaryAt;       // 上次打播放事件汇总的时刻（ms）

    private volatile int playedSeq = -1;

    // ==================== 接收质量统计（语音码率自适应判据） ====================

    /** 收到的音频帧数（缺帧率的分母） */
    private final java.util.concurrent.atomic.AtomicInteger receivedCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 缺帧补静音跳过的次数 */
    private final java.util.concurrent.atomic.AtomicInteger missedCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public int receivedFrames() {
        return receivedCount.get();
    }

    public int missedFrames() {
        return missedCount.get();
    }

    /** 当前播放队列积压帧数（>1 = 送达慢于播放 → 链路拥塞） */
    public int pendingFrames() {
        return playQueue.size();
    }

    // ==================== 积压口径：队列 + 线内（任务单 1.5） ====================

    /** PCM 折算系数：48kHz × 16bit × 单声道 = 96000 B/s ⇒ **96 字节 = 1ms** */
    private static final long WIRE_BYTES_PER_MS = 96L;

    /** 旧口径（只算队列）：队列帧数 × frameMs。不含线内 ⇒ 比真实积压虚好 40–43ms */
    public long backlogQueueMs() {
        return (long) playQueue.size() * frameMs;
    }

    /**
     * 线内未播部分（ms）= (播放线缓冲 − 当前可写字节) / 96，即已经 {@code write()} 出去、
     * 但声卡还没播掉的 PCM。播放节流全靠 write() 阻塞（方案 A1），这一垫层是真实积压。
     *
     * **降级口径：返回 -1 = 本端此刻拿不到线内字节**（playLine 未 open / 已 close / 线路异常）。
     * 调用方必须把 -1 原样打成"不可测"，**不许折成 0 少算**（任务单 1.5 自查项）。
     *
     * 两个已知偏差（方向相反，都小于一帧，写下来免得下轮当 bug 查）：
     *  · {@code available()} 的 javadoc 明说缓冲可能处在混音器中间态 ⇒ 可写字节偏小
     *    ⇒ 本值**偏大**（多算，安全方向）；
     *  · 正在 {@code write()} 里阻塞、还没拷进缓冲的那点零头不在任何一项里 ⇒ **偏小**；
     *    安卓端用"先记账再 write"消掉了同一段盲区（见 VoiceEngine.kt 的 writeTrack）。
     */
    public long backlogWireMs() {
        SourceDataLine line = playLine;
        if (line == null) {
            return -1L;
        }
        try {
            long unplayed = (long) line.getBufferSize() - line.available();
            return unplayed <= 0L ? 0L : unplayed / WIRE_BYTES_PER_MS;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /*
     * 音频积压总口径（ms）= 队列 ms + max(0, 线内 ms)（方案 A3）。
     * 10-06 起有两个消费者：playLoop 的水位下界（{@link #PLAY_TOTAL_MAX_MS}）、
     * CallManager 的 pp-voice-aq 采样（那行「音频积压」日志 + 码率自适应判据）。
     * 相加式**写在各自消费点**（一次读队列、一次读线内，分量与总和对得上），双端同式：
     * 安卓 voice/VoiceEngine.kt 的 playLoop、voice/CallManager.kt 的同一位置。
     * 线内不可测（-1）时只算队列，不折成 0 少算（任务单 1.5 自查项）。
     */

    /** 当前帧长（ms）：只读，通话中不变；给码率自适应换算水位阈值用 */
    public int frameMs() {
        return frameMs;
    }

    public void resetStats() {
        receivedCount.set(0);
        missedCount.set(0);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ==================== 发送（采集 + 编码） ====================

    public synchronized boolean startRecord() {
        if (recordLine != null) {
            return true;
        }
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                Log.w("麦克风线路不支持: " + fmt);
                return false;
            }
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            // 低延迟关键：采集线缓冲只要**约 2 帧**（20ms 帧 → 4KB≈43ms；
            // 旧实现 4×200ms = 800ms，是通话延迟的最大来源）。下限 4KB 防 overrun。
            line.open(fmt, Math.max(4096, frameBytes() * 2));
            // 提前绑定 recordLine：后续步骤（编码器构造/建线程）抛异常时，catch 可经它关闭采集线
            recordLine = line;
            encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(bitrate);
            recordRunning.set(true);
            recordThread = new Thread(this::recordLoop, "pp-voice-rec");
            recordThread.setDaemon(true);
            recordThread.start();
            Log.i("开始录音（48kHz 单声道，Opus " + (bitrate / 1000) + "k，" + frameMs + "ms/帧）");
            return true;
        } catch (Throwable t) {
            Log.w("启动录音失败: " + t);
            // 启动失败：释放已 open 的采集线（Windows 混音器句柄），复位状态，保证可重试
            recordRunning.set(false);
            recordThread = null;
            if (recordLine != null) {
                try {
                    recordLine.close();
                } catch (Throwable ignore) {
                }
                recordLine = null;
            }
            encoder = null;
            return false;
        }
    }

    private void recordLoop() {
        TargetDataLine line = recordLine;
        if (line == null) {
            return;
        }
        try {
            line.start();
            byte[] pcm = new byte[frameBytes()];
            short[] shortBuf = new short[OPUS_FRAME_SAMPLES];
            int seq = 0;
            // ---- 10-06 T3.1/T3.2 发端取证（只加仪表，不改任何发送行为）----
            // T3.1 短读：补零后**照常 seq++ 发出去** ⇒ 对端收到一个"合法但全静音"的帧，
            //      双端所有接收计数都不会动 ⇒ 这是"听感断续而计数全 0"的唯一静默通道，必须单独计。
            // T3.2 aPace：按 seq 反推每帧**应到时刻**，量偏差（正 = 本端产出比实时慢）。
            //      为什么不看 aEnc：20ms 帧下实测 aEnc p50=30ms 而每秒样本 n=50 ⇒ 分布是双峰
            //      （多数格 30ms + 少数格 0ms），判不出"驱动按 ~30ms 突发给数据"还是"采集线程真的落后"。
            //      稳态 aPace 应有界（±几 ms）；单调增长 = 断续的根在发端，与收端水位无关 ⇒ 另开单。
            final int[] paceWin = new int[64];   // 1s 窗口最多 50 帧（20ms 帧）⇒ 64 是纯防御
            int paceN = 0;
            long baseNano = 0L;
            int winFrames = 0;
            int winShortRead = 0;
            int winShortLastFilled = 0;
            long winNano = 0L;
            while (recordRunning.get()) {
                long t0 = System.nanoTime();
                int filled = 0;
                while (filled < frameBytes()) {
                    int n = line.read(pcm, filled, frameBytes() - filled);
                    if (n <= 0) {
                        break;
                    }
                    filled += n;
                }
                if (filled < frameBytes()) {
                    java.util.Arrays.fill(pcm, filled, frameBytes(), (byte) 0);
                    winShortRead++;
                    winShortLastFilled = filled;
                    CallCounters.get().audioShortReadFrames.inc();
                }
                byte[] out = encodeFrame(pcm, shortBuf);
                if (out != null) {
                    long t1 = System.nanoTime();
                    SegMeter.get().record(SegMeter.A_ENC, t0, t1);
                    if (baseNano == 0L) {
                        baseNano = t1;
                        winNano = t1;
                    } else if (paceN < paceWin.length) {
                        long d = (t1 - baseNano) / 1_000_000L - (long) seq * frameMs;
                        paceWin[paceN++] = (int) Math.max(-9999L, Math.min(9999L, d));
                    }
                    winFrames++;
                    // 静音时**不发送**，但 seq 照常前进：对端看到缺帧 → 走补静音分支，
                    // 时间轴不会因为我们少发帧而漂移。
                    if (!muted) {
                        Listener l = listener;
                        if (l != null) {
                            l.onEncoded(new EncodedFrame(seq, out, t1));
                        }
                    }
                    seq++;
                }
                // 1 行/秒（与 EVENT_SUMMARY_MS 同窗）；没产出帧的窗口也要说话（那正是最糟的情况）
                long nowNano = System.nanoTime();
                if (baseNano != 0L && nowNano - winNano >= EVENT_SUMMARY_MS * 1_000_000L) {
                    int ideal = (int) (EVENT_SUMMARY_MS / frameMs);
                    String pace;
                    if (paceN == 0) {
                        pace = "-";
                    } else {
                        java.util.Arrays.sort(paceWin, 0, paceN);
                        pace = paceWin[paceN >> 1] + "/"
                                + paceWin[Math.min(paceN - 1, paceN * 95 / 100)] + "/"
                                + paceWin[paceN - 1];
                    }
                    Log.w("采集节拍 aPace p50/p95/max=" + pace + "ms（1s 窗口）：帧数 "
                            + winFrames + "/理想 " + ideal + "，短读 " + winShortRead + " 次"
                            + (winShortRead > 0 ? "（最后 filled=" + winShortLastFilled + "B）" : ""));
                    winNano = nowNano;
                    winFrames = 0;
                    paceN = 0;
                    winShortRead = 0;
                    winShortLastFilled = 0;
                }
            }
        } catch (Throwable t) {
            Log.w("录音循环异常: " + t);
        }
    }

    private byte[] encodeFrame(byte[] pcm, short[] shortBuf) {
        OpusEncoder enc = encoder;
        if (enc == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
            for (int i = 0; i < opusSubframes(); i++) {
                int base = i * OPUS_FRAME_SAMPLES;
                for (int j = 0; j < OPUS_FRAME_SAMPLES; j++) {
                    int lo = pcm[base * 2 + j * 2] & 0xFF;
                    int hi = pcm[base * 2 + j * 2 + 1] & 0xFF;
                    int s = lo | (hi << 8);
                    if (s >= 0x8000) {
                        s -= 0x10000;
                    }
                    shortBuf[j] = (short) s;
                }
                byte[] outBuf = new byte[1275];
                int n = enc.encode(shortBuf, 0, OPUS_FRAME_SAMPLES, outBuf, 0, outBuf.length);
                if (n <= 0) {
                    throw new IllegalStateException("Opus 编码返回 0 字节");
                }
                if (n > 0xFFFF) {
                    throw new IllegalStateException("Opus 子帧过长");
                }
                out.write((n >>> 8) & 0xFF);
                out.write(n & 0xFF);
                out.write(outBuf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w("Opus 编码失败: " + t);
            return null;
        }
    }

    public synchronized void stopRecord() {
        if (recordLine == null) {
            return;
        }
        recordRunning.set(false);
        try {
            recordLine.stop();
            recordLine.close();
        } catch (Throwable ignore) {
        }
        recordLine = null;
        encoder = null;
        Log.i("停止录音");
    }

    // ==================== 接收（解码 + 播放） ====================

    public void onAudioReceived(int seq, long ts, byte[] data, int len) {
        long t0 = System.nanoTime();
        if (len <= 0) {
            return;
        }
        // 闸门：播放未启动或播放线程已死 → 直接丢帧，防止 playQueue 只进不出
        if (playLine == null || !playRunning.get()) {
            // 任务单 1.6：最多 1 行/s，但每行**必须带上被节流掉的条数**（不许只留最后一条）。
            // 计数放这儿而不是播放侧：播放线程已死时才没人打汇总，这条得自己会说话。
            notReadyDropped.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastDropLogAt.get();
            if (now - last >= EVENT_SUMMARY_MS && lastDropLogAt.compareAndSet(last, now)) {
                Log.w("播放未就绪/播放线程已死，丢弃音频帧 seq=" + seq
                        + "（本秒累计丢弃 " + notReadyDropped.getAndSet(0) + " 帧）");
            }
            return;
        }
        receivedCount.incrementAndGet();
        // 已播放过的迟到帧直接丢（积压追赶后补发的旧帧不该再进队列）
        // played 一次读快照：playedSeq 由播放线程推进，连读两次会让行里的「已播到」与判据不同步
        int played = playedSeq;
        if (played >= 0 && seq <= played) {
            // 任务单 T6（10-03 取证）：这条闸门原先**静默**——无计数、无日志，而下面的 A_IN
            // 记录点又在它之后，被丢的帧连 aIn 都不计 ⇒ 音频塌成 1–3 帧/s 时，日志里只剩
            // 「缺帧补静音」一个症状，判不出是链路没送到还是本端已把时间轴推过头。
            // 现在补计数 + 1 行/秒的日志（行内带被节流掉的条数与本窗口最大落后帧数）。
            CallCounters.get().audioDroppedByLateSeq.inc();
            lateSeqDropped.incrementAndGet();
            int lag = played - seq;
            if (lag > lateSeqMaxLag.get()) {
                lateSeqMaxLag.set(lag);
            }
            long now = System.currentTimeMillis();
            long last = lastLateSeqLogAt.get();
            if (now - last >= EVENT_SUMMARY_MS && lastLateSeqLogAt.compareAndSet(last, now)) {
                Log.w("迟到帧丢弃：seq=" + seq + " 已播到=" + played + "（落后 " + lag + " 帧）"
                        + "（本秒累计丢弃 " + lateSeqDropped.getAndSet(0) + " 帧，"
                        + "本窗口最大落后 " + lateSeqMaxLag.getAndSet(0) + " 帧）");
            }
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);
        long rxNano = System.nanoTime();
        playQueue.put(Integer.valueOf(seq), new Inbound(copy, ts, rxNano));
        SegMeter.get().record(SegMeter.A_IN, t0, rxNano);
    }

    public synchronized boolean startPlay() {
        if (playLine != null) {
            return true;
        }
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                Log.w("扬声器线路不支持: " + fmt);
                return false;
            }
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            // 回放线缓冲同样**约 2 帧**（旧实现 4×200ms = 800ms）
            line.open(fmt, Math.max(4096, frameBytes() * 2));
            // 提前绑定 playLine：后续步骤（解码器构造/建线程）抛异常时，catch 可经它关闭回放线
            playLine = line;
            decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
            playRunning.set(true);
            playedSeq = -1;
            lastPlayedTsMs = -1L;
            resetPlayEvents();
            // 媒体域线程 pp-av-audio（任务单 2.2 / 方案 D2）：音频解码 + 写播放设备。
            // write 在设备缓冲满时必然阻塞 ⇒ 不能并进媒体主循环 pp-av-loop，这是 D2 单列它的原因。
            // daemon；退出条件 = playRunning 置 false，且 stopPlay 里的 stop/close 让阻塞中的
            // write 立即返回。无 join：线程退出后引用留在字段里，下一次 startPlay 覆盖它。
            playThread = new Thread(this::playLoop, "pp-av-audio");
            playThread.setDaemon(true);
            playThread.start();
            Log.i("开始播放（帧长 " + frameMs + "ms，缓冲 "
                    + Math.max(4096, frameBytes() * 2) + "B，严格按 seq 顺序播放）");
            return true;
        } catch (Throwable t) {
            Log.w("启动播放失败: " + t);
            // 启动失败：释放已 open 的回放线（Windows 混音器句柄），复位状态，保证可重试
            playRunning.set(false);
            playThread = null;
            if (playLine != null) {
                try {
                    playLine.close();
                } catch (Throwable ignore) {
                }
                playLine = null;
            }
            decoder = null;
            playedSeq = -1;
            lastPlayedTsMs = -1L;
            return false;
        }
    }

    private void playLoop() {
        SourceDataLine line = playLine;
        if (line == null) {
            return;
        }
        short[] shortBuf = new short[frameSamples()];
        long missSince = 0L;
        try {
            line.start();
            while (playRunning.get()) {
                // 事件日志 1 行/秒汇总（任务单 1.6）：本轮只加计数，这里攒够了才打一行
                flushPlayEventSummary(false);
                // 积压超限快速追赶（见 BACKLOG_MAX_MS 注释）：跳到最新帧，旧帧按缺帧丢弃
                int maxBacklog = Math.max(2, BACKLOG_MAX_MS / frameMs);
                if (playQueue.size() > maxBacklog) {
                    int newest = playQueue.lastKey();
                    int jumpTo = newest - Math.max(1, BACKLOG_KEEP_MS / frameMs);
                    if (jumpTo > playedSeq) {
                        int dropped = playQueue.headMap(Integer.valueOf(jumpTo), true).size();
                        playQueue.headMap(Integer.valueOf(jumpTo), true).clear();
                        missedCount.addAndGet(dropped);
                        playedSeq = jumpTo;
                        missSince = 0L;
                        // 原来这里逐次 Log.w（带 seq 与丢帧数）⇒ 改成计数，由上面的汇总行
                        // 带出"快进 N 次（跳过 M 帧）"；seq 细节本单不进验收（任务单 1 只看量）
                        evBacklogJump++;
                        evBacklogDropped += dropped;
                    }
                }
                // ---- 10-06 水位下界：总水位（队列 + 线内）太高 ⇒ 本格不取帧，等声卡自己消化 ----
                // 治的是"write 不阻塞时播放循环跑赢声卡 ⇒ playedSeq 把后续帧全判死"的自锁方向
                // （PC 上 write 基本恒阻塞 ⇒ 这一档很少触发；安卓同构代码里它是主力）。
                // 线内不可测（-1）时只按队列算：宁可少 hold 一拍，也不要把不可测当 0 少算。
                int depth = playQueue.size();
                long wireMsNow = backlogWireMs();
                long totalMs = (long) depth * frameMs + (wireMsNow < 0L ? 0L : wireMsNow);
                if (depth > 0 && totalMs > totalMaxMs()) {
                    if (holdTicks < PLAY_HOLD_MAX_TICKS) {
                        holdTicks++;
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    evHoldCap++;   // 连续 hold 到上限 ⇒ 设备没在消费或口径失真，放行取帧（保命）
                }
                // ---- 10-06 水位上界：队列深过目标水位 ⇒ 每格丢最旧一帧，渐进收敛 ----
                // （上面 600ms 悬崖一次砍 ~450ms 的兜底保留；正常情况下它已经够不着了。）
                if (depth > targetFrames()) {
                    Integer oldest = playQueue.firstKey();
                    if (oldest != null && oldest.intValue() <= playedSeq + 1) {
                        playQueue.remove(oldest);
                        playedSeq = oldest.intValue();
                        missedCount.incrementAndGet();
                        CallCounters.get().audioTrimmedByBacklog.inc();
                        evTrim++;
                        missSince = 0L;
                        continue;
                    }
                    // oldest > playedSeq+1 ⇒ 下一格本来就缺，交给下面的缺帧分支（不等就直接补静音，
                    // 推进 playedSeq 同样把水位降下来，不用碰真实帧）
                }
                int next = playedSeq + 1;
                long dqNano = System.nanoTime();
                Inbound slot = playQueue.remove(Integer.valueOf(next));
                if (slot != null) {
                    SegMeter.get().record(SegMeter.A_BUF, slot.rxNano, dqNano);
                    decodeAndWrite(line, slot.opus, shortBuf);
                    SegMeter.get().record(SegMeter.A_OUT, dqNano, System.nanoTime());
                    // 播出点读取发送端 ts（通话相对）；A/V 对时与分段延迟计量用（任务单 1.2）
                    lastPlayedTsMs = slot.ts;
                    playedSeq = next;
                    missSince = 0L;
                    holdTicks = 0;
                    while (!playQueue.isEmpty()
                            && playQueue.firstKey().intValue() <= playedSeq) {
                        playQueue.pollFirstEntry();
                    }
                } else if (playedSeq < 0) {
                    Thread.sleep(POLL_MS);
                } else {
                    // 缺帧：轻量容忍 —— 一帧最多等 FRAME_WAIT_MS，超时才补静音跳过。
                    // （20ms 帧下必须这么写：不等就会「晚到几毫秒即丢弃」，通话断续。）
                    // 10-06：**只有队列还在水位之下才等**。等满 30ms 再写静音 = 50ms 墙钟只推进
                    // 一帧时间轴 ⇒ 每个缺帧净给队列加 1.5 帧；队列已经深过水位时这 30ms 是纯亏，
                    // 后面的帧就在手里，直接补静音反而把水位收回来。
                    long now = System.currentTimeMillis();
                    if (missSince == 0L) {
                        missSince = now;
                    }
                    if (playQueue.isEmpty()
                            || (depth <= targetFrames() && now - missSince < FRAME_WAIT_MS)) {
                        Thread.sleep(POLL_MS);
                    } else {
                        // 缺帧但后续帧已在缓冲：补静音跳过，保持时间轴对齐
                        writeSilence(line, shortBuf);
                        playedSeq = next;
                        missSince = 0L;
                        missedCount.incrementAndGet();
                        // 原来这里逐次 Log.w（20ms 帧下最高 ~33 行/s）⇒ 只加计数，
                        // 由 flushPlayEventSummary 每秒带出次数（任务单 1.6）
                        evSilenceFill++;
                    }
                }
            }
            // 正常收线（挂断）：把最后一个不足 1s 的窗口打成尾巴，别静默吞掉
            flushPlayEventSummary(true);
        } catch (Throwable t) {
            Log.w("播放循环异常: " + t);
            flushPlayEventSummary(true);
            // 播放线程死亡：复位运行标志与线路引用（假活自愈），onAudioReceived 闸门随即开始丢帧；
            // 清队列+复位 playedSeq：防旧高 seq 残帧在下次通话触发积压快进、把新帧全跳掉
            playRunning.set(false);
            try {
                line.close();
            } catch (Throwable ignore) {
            }
            playLine = null;
            decoder = null;
            playQueue.clear();
            playedSeq = -1;
            lastPlayedTsMs = -1L;
        }
    }

    /** 新一场播放：事件计数与节流窗口归零（防上一场的尾巴混进这一场的汇总行） */
    private void resetPlayEvents() {
        evSilenceFill = 0;
        evBacklogJump = 0;
        evBacklogDropped = 0;
        evTrim = 0;
        evHoldCap = 0;
        holdTicks = 0;
        evSummaryAt = 0L;
        notReadyDropped.set(0);
        lastDropLogAt.set(0L);
        lateSeqDropped.set(0);
        lateSeqMaxLag.set(0);
        lastLateSeqLogAt.set(0L);
    }

    /**
     * 播放事件汇总（任务单 1.6）：窗口 {@link #EVENT_SUMMARY_MS} 内最多一行，
     * 行里**带上被节流掉的次数**（不许只留最后一条）；这一窗口没事件就不打。
     *
     * @param force 播放线程退出前调 true —— 不足一个窗口的尾巴也要打出来，不静默吞
     */
    private void flushPlayEventSummary(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - evSummaryAt < EVENT_SUMMARY_MS) {
            return;
        }
        evSummaryAt = now;
        if (evSilenceFill == 0 && evBacklogJump == 0 && evTrim == 0 && evHoldCap == 0) {
            return;
        }
        Log.w("音频播放事件汇总（" + EVENT_SUMMARY_MS + "ms 窗口）：缺帧补静音 " + evSilenceFill
                + " 次，积压快进 " + evBacklogJump + " 次（跳过 " + evBacklogDropped + " 帧）"
                + "，水位裁帧 " + evTrim + " 帧，保命放行 " + evHoldCap + " 次"
                + "，当前队列 " + playQueue.size() + " 帧/" + backlogQueueMs() + "ms"
                + " 线内 " + backlogWireMs() + "ms");
        evSilenceFill = 0;
        evBacklogJump = 0;
        evBacklogDropped = 0;
        evTrim = 0;
        evHoldCap = 0;
    }

    private void decodeAndWrite(SourceDataLine line, byte[] opus, short[] shortBuf) {
        OpusDecoder dec = decoder;
        if (dec == null) {
            return;
        }
        try {
            int written = 0;
            int off = 0;
            while (off + 2 <= opus.length && written < frameSamples()) {
                int flen = ((opus[off] & 0xFF) << 8) | (opus[off + 1] & 0xFF);
                off += 2;
                if (flen <= 0 || off + flen > opus.length) {
                    break;
                }
                short[] outBuf = new short[OPUS_FRAME_SAMPLES];
                int n = dec.decode(opus, off, flen, outBuf, 0, OPUS_FRAME_SAMPLES, false);
                int copy = Math.min(n, frameSamples() - written);
                System.arraycopy(outBuf, 0, shortBuf, written, copy);
                written += copy;
                off += flen;
            }
            if (written < frameSamples()) {
                java.util.Arrays.fill(shortBuf, written, frameSamples(), (short) 0);
            }
            byte[] pcm = shortArrayToBytes(shortBuf, frameSamples());
            line.write(pcm, 0, pcm.length);
        } catch (Throwable t) {
            Log.w("解码播放失败: " + t);
            writeSilence(line, shortBuf);
        }
    }

    private void writeSilence(SourceDataLine line, short[] shortBuf) {
        java.util.Arrays.fill(shortBuf, (short) 0);
        byte[] pcm = shortArrayToBytes(shortBuf, frameSamples());
        try {
            line.write(pcm, 0, pcm.length);
        } catch (Throwable ignore) {
        }
    }

    private static byte[] shortArrayToBytes(short[] s, int n) {
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = s[i] & 0xFFFF;
            b[i * 2] = (byte) (v & 0xFF);
            b[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
        }
        return b;
    }

    public synchronized void stopPlay() {
        if (playLine == null) {
            return;
        }
        playRunning.set(false);
        try {
            playLine.stop();
            playLine.close();
        } catch (Throwable ignore) {
        }
        playLine = null;
        decoder = null;
        playQueue.clear();
        playedSeq = -1;
        lastPlayedTsMs = -1L;
        Log.i("停止播放");
    }

    public void stopAll() {
        stopRecord();
        stopPlay();
    }
}
