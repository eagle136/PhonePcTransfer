package com.phonepctransfer.pc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * PC 端视频解码器：把对端发来的 HEVC / H.264 Annex-B 访问单元（AU）解成 BGRA 像素帧。
 *
 * <h3>为什么是子进程</h3>
 * Java 自身没有任何视频解码能力；本机实测**没有**系统 HEVC 解码 MFT
 * （`C:\Windows\System32\*hevc*` 无匹配、无 HEVC 扩展包），Media Foundation 路线不可用。
 * 因此随包带一个**裁剪到只剩解码的最小化 ffmpeg.exe**（2.89 MB，静态单文件），
 * 详见 VIDEO_CALL_PLAN §3.2 / §15.2。
 *
 * <h3>实测固化的命令</h3>
 * <pre>
 * ffmpeg -hide_banner -loglevel error
 *        -probesize 32 -analyzeduration 0 -flags low_delay -threads 1
 *        -f hevc -i pipe:0
 *        -f rawvideo -pix_fmt bgra -fps_mode passthrough pipe:1
 * </pre>
 * <ul>
 *   <li>{@code -probesize 32 -analyzeduration 0}：跳过流探测，首帧 1.11 s → 0.39 s</li>
 *   <li>{@code -threads 1}：稳态积压恒 5 帧；多线程会 5↔6 帧抖动</li>
 *   <li>{@code -pix_fmt bgra}：实测字节序确认为 B,G,R,A</li>
 *   <li><b>禁用</b> {@code -fflags nobuffer} / {@code -avioflags direct}：
 *       实测让裸 HEVC 管道解码**输出 0 帧**（{@code PPS id out of range}）</li>
 * </ul>
 *
 * <h3>线程模型（与 VIDEO_CALL_PLAN §8 一致）</h3>
 * <pre>
 *  网络读线程 ──enqueue()──► 有界队列(24) ──► [pp-video-feed] ──stdin──► ffmpeg
 *                                                     ▲                     │
 *                                             进程生命周期只归这条线程   stdout
 *                                                                           ▼
 *                                              [pp-video-read] ── Sink.onFrame() ──► Swing
 * </pre>
 * <ul>
 *   <li>{@link #enqueue} <b>只入队、立即返回</b>——网络读线程绝不能在这里阻塞
 *       （本项目踩过两次：读线程被堵 → 对端 35 s 判死 + 挂断抢锁死锁）。</li>
 *   <li>进程的启动/重启/销毁**只由 pp-video-feed 线程做**，因为 spawn + 探流可能耗时数百 ms。</li>
 *   <li>{@link #stop()} 立即返回，真正的 {@code destroyForcibly()} 丢后台线程 —— 不阻塞挂断路径。</li>
 * </ul>
 *
 * <h3>丢帧策略</h3>
 * 队列满时不逐帧丢（乱丢中间帧会让解码器输出花屏直到下一个 IDR），而是
 * <b>「丢到下一个 IDR」</b>：置 {@code dropUntilKey}，随后所有非关键帧一律丢弃，
 * 直到收到带 KEYFRAME 标志的 AU 才恢复。这也正是协议里 flag bit0 的用途。
 */
public final class FfmpegDecoder {

    /** 解码输出回调。**在 pp-video-read 线程上调用**：实现必须同步转换/拷贝，且禁止阻塞。 */
    public interface Sink {
        /**
         * @param bgra 复用缓冲，下帧会被覆写 —— 实现里必须当场转成自己的图像，不得长期持有
         * @param w    宽（像素）
         * @param h    高（像素）
         * @param len  有效字节数，正常 = w*h*4
         */
        void onFrame(byte[] bgra, int w, int h, int len);
    }

    // ==================== 帧标志（与 Protocol 对齐） ====================
    /** flag bit0：该 AU 是 IDR */
    public static final int FLAG_KEYFRAME = 0x01;
    /** flag bit1：载荷是参数集（VPS/SPS/PPS） */
    public static final int FLAG_CONFIG = 0x02;

    /** 待写队列上限（约 0.8 s @30fps）。超出即进入「丢到下一个 IDR」 */
    private static final int MAX_QUEUE = 24;

    /**
     * 一帧 AU 的硬上限（防御性）。
     * ⚠ 改前这里钉在 128KB，与协议 MAX_VIDEO_FRAME 同值 —— 那在带内是对的（发端本来就被
     *   同一道闸门挡住，128KB 以上的帧不可能到这里）。任务单 3.4 起 UDP 档把一帧切成
     *   ≤1188B/片上线、收端重组，**大 IDR 现在会真的出现**：再钉 128KB 就等于在收端把
     *   发端刚救回来的帧重新丢掉，花屏原样复发（修复清单 1.3 / 3.1 的另一半）。
     *   所以改为对齐协议自己的单帧硬上限 Protocol.MAX_FRAME —— 这是本仓库唯一有出处的
     *   "一帧最大多少字节"，不新设数字。
     * ⚠ 内存不会因此失控：队列深度仍由 MAX_QUEUE 与超限清空守着；而在最高档 2Mbps 下
     *   一秒的全部数据是 250KB，队列最坏实际占用约 24×250KB ≈ 6MB，8MB 那条只是协议层面的天花板。
     */
    private static final int MAX_AU = Protocol.MAX_FRAME;

    // ==================== 外部注入 ====================
    private final Sink sink;
    /** 用户配置的 ffmpeg 路径（空 = 自动探测） */
    private final String userPath;
    /**
     * 用户配置的硬件加速方式。
     * ⚠ 2026-10-03 口径变更（用户点「解码优先硬解，硬解不行就软解」）：**空 = 自动**，
     *   即先问这个 ffmpeg 支持哪些 hwaccel、按 {@link #HW_PREFERENCE} 取第一个；
     *   填了具体名字 = 只用这一个（仍带 {@link #hwAbandoned} 的软解兜底）。
     */
    private final String hwaccel;
    /** 随包内置的 ffmpeg 位置（`pc/bin/ffmpeg.exe`），探测优先级最高 */
    private final String bundledPath;

    // ==================== 硬解优先 / 软解兜底（2026-10-03 用户点） ====================

    /**
     * 硬解方式的尝试优先序（Windows 上 d3d11va 最通用，dxva2 是老兜底，qsv 只对 Intel 有效）。
     * 只在这个 exe **自己列出的** hwaccel 里挑，不猜 GPU 型号：列不出来就是没有。
     */
    private static final String[] HW_PREFERENCE = {"d3d11va", "dxva2", "qsv", "vaapi"};

    /** exe 绝对路径 → 该 exe 支持的 hwaccel 列表（跑一次 {@code -hwaccels} 就记住，不再重复 spawn） */
    private static final java.util.concurrent.ConcurrentHashMap<String, List<String>> HW_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, List<String>>();

    /** 硬解进程起来后多久**一帧都没出**就判它不行（毫秒）。喂得进去却不出帧 = 这条路走不通。 */
    private static final long HW_VERIFY_MS = 2000L;

    /** 本会话是否已经放弃硬解（feed 线程独占：一旦降级，后面所有 spawn 都只走软解，不再反复试） */
    private boolean hwAbandoned = false;
    /** 本次 spawn 实际用的 hwaccel（"" = 软解）；feed 线程独占 */
    private String runHwaccel = "";
    /** 本次 spawn 的时刻与当时的解码/喂帧基线，供 {@link #verifyHw} */
    private long spawnAtMs = 0L;
    private long decodedAtSpawn = 0L;
    private int fedSinceSpawn = 0;

    // ==================== 期望格式（由 video_cfg 控制帧设定） ====================
    private volatile String wantCodec = "";      // "hevc" / "h264"
    private volatile int wantW = 0;
    private volatile int wantH = 0;

    /** 已生效的格式（feed 线程独占读写；仅用于日志与变更判定） */
    private String runCodec = "";
    private int runW = 0;
    private int runH = 0;

    // ==================== 输出缩放（display box）====================

    /**
     * 画面窗口能用的**未旋转**像素盒（0/负 = 未设定 ⇒ 按源尺寸出帧）。
     *
     * 为什么要缩放：ffmpeg 出的 BGRA 只能走匿名管道，实测本机管道吞吐 ~82 MB/s
     * （1080x1920 的 8.3 MB/帧 = 101 ms/帧 ⇒ 上限 9.9 fps；720x1280 的 3.7 MB = 31 ms ⇒ 30 fps），
     * 与解码是软是硬无关。收端只有 10 fps 的吞吐，30 fps 的流就会把输入队列撑到 {@code MAX_QUEUE}
     * 整段清空、再等下一个 IDR（GOP=1s）⇒ 用户看到的「几秒卡一下」。
     * 而 1080x1920 的竖屏画面在 760x500 的窗口里本来就只占 281x500，多送的两倍字节全被 Swing 丢掉，
     * 所以「按窗口尺寸出帧」不掉画质，只砍管道载荷。
     */
    private volatile int boxW = 0;
    private volatile int boxH = 0;

    /**
     * 出帧像素上限（面积）。按实测 82 MB/s 的管道吞吐反推 30 fps 需要 ≤2.7 MB/帧 ≈ 0.68 MP，
     * 取整为 0.7 MP 封顶：窗口再大也不放大了送（Swing 会把超出窗口的像素插值掉，那是纯浪费）。
     */
    public static final int MAX_OUT_PIXELS = 700_000;

    /** 实际出帧尺寸（feed 线程独占）：≤ 源尺寸，{@code frameBuf} 按它预分配 */
    private int runOutW = 0;
    private int runOutH = 0;

    /** 上一次因 box 变化换进程的时刻：拖窗口会连发几十个体积相近的尺寸，靠它 + 面积阈值抑制 */
    private long lastBoxSpawnMs = 0L;

    /** 换进程最小间隔（ms）。小于这个间隔的 box 变化一律顺延到下一轮判定。 */
    private static final long BOX_SPAWN_MIN_GAP_MS = 1200L;

    // ==================== 队列 ====================
    /**
     * 队列元素：一个 AU 的字节 + 两个**不同零点**的时刻。
     *
     * · {@code recvMs} —— 本端收到并入队的时刻（墙钟）：只用于算排队时延（{@code lat}，任务单 1.3）。
     * · {@code frameTsMs} —— 发送端的**通话相对**时间戳（对端起呼=0 的 ms，任务单 1.1）：
     *   帧龄、上屏间隔用它；它与 {@code recvMs} 不同零点，**禁止相减**。
     */
    private static final class PendingAu {
        final byte[] au;
        final long recvMs;
        final long frameTsMs;
        /** 参数集（VPS/SPS/PPS）：喂进 ffmpeg 后**不出帧**，因此不进 vOut 配对环 */
        final boolean cfg;

        PendingAu(byte[] au, long recvMs, long frameTsMs, boolean cfg) {
            this.au = au;
            this.recvMs = recvMs;
            this.frameTsMs = frameTsMs;
            this.cfg = cfg;
        }
    }

    private final Object qLock = new Object();
    private final ArrayDeque<PendingAu> queue = new ArrayDeque<PendingAu>();

    /**
     * 「出队 → 上屏」配对环（任务单 1.2 的 vOut 段）：feed 线程在把 AU 写进 ffmpeg 前
     * 压入出队时刻，read 线程每交付一帧弹出配对。
     *
     * 配对依据：ffmpeg 用 `-threads 1 -flags low_delay -fps_mode passthrough`，
     * 喂入序 = 输出序且基本 1:1（参数集 AU 不出帧 → 弹出时按缺配跳过）。
     * 环满（输出落后 &gt; 64 帧）时丢最旧：宁可少样本，也不要错样本。
     * 头尾指针都在 qLock 下动（生产者/消费者各一处，锁竞争频率 = 帧率，可忽略）。
     */
    private static final int DEQ_RING = 64;
    private static final int DEQ_MASK = DEQ_RING - 1;
    private final long[] deqRing = new long[DEQ_RING];
    private int deqHead = 0;
    private int deqTail = 0;
    private boolean dropUntilKey = false;
    /** 当前是否处于「阻塞等待队列非空」状态，用于避免无谓唤醒 */
    private boolean waiting = false;

    // ==================== 生命期 ====================
    private volatile boolean running = false;
    private Thread feedThread;
    private Thread readThread;
    private Thread errThread;

    /**
     * 保护 proc/stdin/stdout 状态切换（含 running 检查）的单一锁：
     * stop() 快照、spawn() 注册、killProc() 快照都在此锁下互斥。
     * 临界区只做纯赋值（不含 pb.start()/waitFor/流 IO），
     * 保证 stop() 不会被进行中的 spawn 阻塞（维持「stop 立即返回」契约）。
     */
    private final Object procLock = new Object();

    /** 进程句柄。写侧走 procLock；read 线程的 owner 判断是无锁 volatile 读 */
    private volatile Process proc;
    private volatile OutputStream stdin;
    private volatile InputStream stdout;

    /** 复用读缓冲（预分配 + 紧凑 readFully，见 §15.2 踩坑：滑动缓冲会让积压缓慢累积） */
    private byte[] frameBuf = new byte[0];
    private int frameBufSize = 0;

    // ==================== 统计（供 video_fb 上报） ====================
    private final java.util.concurrent.atomic.AtomicLong decoded = new java.util.concurrent.atomic.AtomicLong();
    /**
     * **真丢帧**计数：只统计「收端跟不上」的那两类 —— 队列积压到上限被整段清掉、单帧超 MAX_AU。
     * 这个数喂 video_fb 的 drop%，是发送侧降档的判据之一，所以它必须**只**代表拥塞。
     */
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    /**
     * **换代重置**计数：解码进程重建时清掉的旧尺寸 AU、以及「等新 IDR 期间」主动丢弃的帧。
     * 它们是本端流水线的必然代价，与网络好坏无关 —— 混进 drop% 就是一条自增强的假拥塞信号：
     * 换一次分辨率 ⇒ 清一次队列 ⇒ drop%≈40% ⇒ 发送侧降一档 ⇒ 又换一次分辨率 ⇒ 又清一次队列…
     * （10-02 23:39 那一通就是这样从 1080P 一路跌到 480P 的）。
     * ⚠ 计数照常留痕（汇总行与 {@link #dropping()} 都说这件事），只是不再当拥塞证据。
     */
    private final java.util.concurrent.atomic.AtomicLong resetDropped = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 解码进程**换代**时的回调（feed 线程调用，必须非阻塞）：新进程只能从 IDR 起解，
     * 由 CallManager 在这里向对端要一个关键帧。null = 未接（行为与改前一致）。
     */
    private volatile Runnable reseedListener;

    /** {@link #requestKeySoon} 的限频窗（ms）：一次拥塞只喊一次，不给发送侧造成要帧风暴。 */
    private static final long KEY_ASK_MIN_GAP_MS = 500L;
    private long lastKeyAskMs = 0L;

    /** 见 {@link #reseedListener}。唯一调用点 = CallManager 建解码器时。 */
    public void setReseedListener(Runnable r) {
        reseedListener = r;
    }
    /** 最近一帧从「到达」到「写进 ffmpeg」的排队延迟（毫秒，**真实值**；0 = 还没喂过） */
    private volatile int lastQueueDelayMs = 0;
    /**
     * 接收端当前是否处于「丢帧期」：队列溢出后正在丢弃到下一个 IDR（任务单 1.3）。
     * 与 {@code lat} **分离上报**——旧口径把丢帧期折成 {@code lat=-1} 再在上报处夹成 0，
     * 于是「拥塞正在丢帧」这一件事在数字上完全消失（看起来 lat=0 很健康）。
     */
    private volatile boolean dropping = false;

    /** 最近一帧真正喂进解码进程的 AU 所带的发送端 ts（通话相对 ms；-1 = 还没喂过） */
    private volatile long lastFedFrameTsMs = -1L;

    // ==================== 字节流取证（默认关） ====================

    /**
     * 取证开关：环境变量 {@code PP_VIDEO_DUMP} 指向一个已存在的目录时，把**每个原样喂进解码进程的
     * AU** 另存一份到该目录（扩展名跟着 codec 走，可直接丢给同一条 ffmpeg 命令离线复解）。
     * 空/未设 = 一个字节都不落，行为与改前完全一致。
     *
     * <p>为什么要它：收端「灰白后卡死」有两种完全不同的根因 —— ①流本身坏（重组/切片改了 AU 字节，
     * 或参数集根本没进来），②流是好的、解码侧跟不上。前者换任何解码器都没用，后者才是性能问题。
     * 只看 ffmpeg 的报错分不开这两者，必须把**它实际收到的字节**留下来按 NAL 结构数一遍。
     *
     * <p>线程：只有 pp-video-feed 会开/写/关（{@link #spawn} 开、{@link #killProc} 关，两者同线程），
     * 所以本字段不加锁、不为 volatile。
     */
    private final String dumpDir = envDumpDir();
    private OutputStream dumpOut;

    /** 见 {@link #dumpDir}：只有指向**真实存在的目录**才生效，避免打错路径时静默不落地以为在取证。 */
    private static String envDumpDir() {
        String d = System.getenv("PP_VIDEO_DUMP");
        if (d == null) {
            return "";
        }
        d = d.trim();
        return (!d.isEmpty() && Files.isDirectory(Paths.get(d))) ? d : "";
    }

    public FfmpegDecoder(String bundledPath, String userPath, String hwaccel, Sink sink) {
        this.bundledPath = bundledPath;
        this.userPath = userPath == null ? "" : userPath.trim();
        this.hwaccel = hwaccel == null ? "" : hwaccel.trim();
        this.sink = sink;
    }

    // ==================== 探测 ====================

    /**
     * 找一个可用的 ffmpeg 可执行文件。
     *
     * 优先级：用户指定 → 随包内置（`pc/bin/ffmpeg.exe`）→ PATH → `C:\ffmpeg\bin` → MSYS2。
     * 只做**文件存在性**检查，不 spawn 进程（不引入启动延迟）。
     *
     * @return 绝对路径；找不到返回 null（调用方应提示用户安装或配置 ffmpegPath）
     */
    public static String probe(String userPath, String bundledPath) {
        for (String c : candidates(userPath, bundledPath)) {
            try {
                Path p = Paths.get(c);
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath().toString();
                }
            } catch (Exception ignore) {
                // 非法路径（比如 PATH 里的畸形项）直接跳过
            }
        }
        return null;
    }

    /** {@link #probe} 的候选表（原顺序，纯路径拼接，不做任何 IO 判定）。 */
    private static List<String> candidates(String userPath, String bundledPath) {
        List<String> cands = new ArrayList<String>();
        if (userPath != null && !userPath.trim().isEmpty()) {
            cands.add(userPath.trim());
        }
        if (bundledPath != null && !bundledPath.trim().isEmpty()) {
            cands.add(bundledPath.trim());
        }
        // PATH 搜索
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                cands.add(dir + File.separator + "ffmpeg.exe");
                cands.add(dir + File.separator + "ffmpeg");
            }
        }
        // 常见安装位置
        cands.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
        cands.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
        cands.add("C:\\msys64\\mingw64\\bin\\ffmpeg.exe");
        cands.add("C:\\msys64\\mingw64\\bin\\ffmpeg.exe");
        cands.add("/usr/bin/ffmpeg");
        cands.add("/usr/local/bin/ffmpeg");
        return cands;
    }

    /**
     * 本次解码用哪个 ffmpeg（2026-10-03 硬解优先）。
     *
     * <ul>
     *   <li>{@code preferHw=false}（用户显式指了路径，或本会话已判定硬解不行）⇒ 与 {@link #probe}
     *       完全同序：随包内置的那个先用。</li>
     *   <li>{@code preferHw=true} ⇒ 同序走一遍，取**第一个自己会硬解**的（每台机都不同，只能问 exe）；
     *       一个都不会硬解时退回原顺序第一个 ⇒ 随包内置的裁剪版仍是软解兜底，
     *       装到哪台机都能跑，不会因为「找不到会硬解的」而黑屏。</li>
     * </ul>
     *
     * ⚠ 每个候选只做一次 {@code -hwaccels} 探测并按路径缓存（{@link #HW_CACHE}），
     *   所以这条"多问一句"的成本是每 exe 一次 spawn，换尺寸/换代都不会重复付。
     */
    private String pickExe(boolean preferHw) {
        String firstExisting = null;
        for (String c : candidates(userPath, bundledPath)) {
            String abs;
            try {
                Path p = Paths.get(c);
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                abs = p.toAbsolutePath().toString();
            } catch (Exception ignore) {
                continue;   // 非法路径直接跳过
            }
            if (firstExisting == null) {
                firstExisting = abs;
            }
            if (preferHw && !hwAccelsOf(abs).isEmpty()) {
                return abs;
            }
        }
        return firstExisting;
    }

    /**
     * 这个 exe 支持哪些硬解方式（跑一次 {@code -hwaccels}，按 exe 路径缓存）。
     *
     * <p>输出长这样（第二行起是方法名，空表 = 这个 build 编译时没带任何 hwaccel）：
     * <pre>Hardware acceleration methods:
     * dxva2
     * qsv
     * d3d11va</pre>
     *
     * <p>只在 feed 线程调用，且 spawn 一次都不成功时返回空表（= 纯软解），不抛异常。
     */
    private static List<String> hwAccelsOf(String exe) {
        List<String> cached = HW_CACHE.get(exe);
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<String>();
        try {
            Process p = new ProcessBuilder(exe, "-hide_banner", "-hwaccels")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8));
            String line;
            boolean header = false;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!header) {
                    header = t.startsWith("Hardware acceleration methods");
                    continue;
                }
                if (!t.isEmpty() && !t.contains(" ")) {
                    out.add(t);
                }
            }
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Throwable ignore) {
            out.clear();
        }
        HW_CACHE.put(exe, out);
        return out;
    }

    /**
     * 本次 spawn 用哪个硬解方式：{@code ""} = 软解。
     * 用户显式填了就只填那个；留空 = 按 {@link #HW_PREFERENCE} 在这个 exe 的能力表里取第一个。
     */
    private String hwaccelFor(String exe) {
        if (hwAbandoned) {
            return "";
        }
        if (!hwaccel.isEmpty()) {
            return hwaccel;
        }
        List<String> support = hwAccelsOf(exe);
        for (String pref : HW_PREFERENCE) {
            if (support.contains(pref)) {
                return pref;
            }
        }
        return "";
    }

    /**
     * 硬解兜底（用户口径「硬解不行就软解」）：带 {@code -hwaccel} 的进程起来后
     * {@link #HW_VERIFY_MS} 内**喂进去 ≥5 帧却一帧都没出**，就判这条路不通 —— 杀进程、
     * 置 {@link #hwAbandoned}，本轮之后只用软解（不再反复试，避免每次换代都重踩一遍）。
     *
     * <p>只在 feed 线程调用，读的都是 feed 线程自己写的字段。
     */
    private void verifyHw() {
        if (runHwaccel.isEmpty() || spawnAtMs == 0L) {
            return;
        }
        if (System.currentTimeMillis() - spawnAtMs < HW_VERIFY_MS) {
            return;
        }
        if (fedSinceSpawn < 5) {
            // 没喂够帧就出不来是正常的（对端还没发/刚要了 IDR）：不作判据，基线继续等
            spawnAtMs = System.currentTimeMillis();
            return;
        }
        if (decoded.get() - decodedAtSpawn > 0) {
            return;   // 出过帧 = 硬解通了
        }
        Log.w("硬解（-hwaccel " + runHwaccel + "）" + HW_VERIFY_MS + "ms 内喂 " + fedSinceSpawn
                + " 帧零输出 ⇒ 本通话改用软解");
        hwAbandoned = true;
        fedSinceSpawn = 0;
        spawnAtMs = 0L;
        killProc();
    }

    /**
     * 随包内置 ffmpeg 的路径（`<app>/bin/ffmpeg.exe`）。
     *
     * <p>⚠️ 2026-09-21：**不能再用数据根目录去拼**了 —— 数据已搬到 `%APPDATA%\PhonePcTransfer`，
     * 而 ffmpeg 一直在**程序目录**里。按「运行中 jar / classes 所在目录」找：
     * <ul>
     *   <li>安装版（jpackage）：jar 在 `&lt;install&gt;/app/` → `&lt;install&gt;/app/bin/ffmpeg.exe`
     *       —— 这正是 jpackage `--input` 摊平后的位置；</li>
     *   <li>源码运行：classes 在 `&lt;pc&gt;/build/classes/` → 往上两级找到 `&lt;pc&gt;/bin/ffmpeg.exe`。</li>
     * </ul>
     * 全都找不到时返回第一个候选路径（报错信息里指认「本该在哪」）。
     */
    public static String bundledPath() {
        List<String> cands = bundledCandidates();
        for (String c : cands) {
            try {
                if (Files.isRegularFile(Paths.get(c))) {
                    return c;
                }
            } catch (Exception ignore) {
                // 路径畸形直接跳过
            }
        }
        return cands.isEmpty() ? "" : cands.get(0);
    }

    private static List<String> bundledCandidates() {
        List<String> out = new ArrayList<String>();
        try {
            Path code = Paths.get(FfmpegDecoder.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            if (dir != null) {
                out.add(dir.resolve("bin").resolve("ffmpeg.exe").toString());
                Path up1 = dir.getParent();
                if (up1 != null) {
                    out.add(up1.resolve("bin").resolve("ffmpeg.exe").toString());
                    Path up2 = up1.getParent();
                    if (up2 != null) {
                        out.add(up2.resolve("bin").resolve("ffmpeg.exe").toString());
                    }
                }
            }
        } catch (Throwable ignore) {
            // 拿不到 code source（罕见）→ 退到工作目录候选
        }
        Path cwd = Paths.get("").toAbsolutePath();
        out.add(cwd.resolve("bin").resolve("ffmpeg.exe").toString());
        out.add(cwd.resolve("pc").resolve("bin").resolve("ffmpeg.exe").toString());
        return out;
    }

    // ==================== 对外 API ====================

    /**
     * 设定/变更编码格式。**可能在网络读线程上被调用** → 只写 volatile 字段，
     * 真正的进程重启由 pp-video-feed 线程在下一轮循环里完成。
     *
     * @return true = 格式有变化（需要重启进程）
     */
    public boolean setFormat(String codec, int w, int h) {
        String c = normalizeCodec(codec);
        if (c.isEmpty() || w <= 0 || h <= 0) {
            Log.w("视频格式无效，忽略: codec=" + codec + " " + w + "x" + h);
            return false;
        }
        boolean changed = !c.equals(wantCodec) || w != wantW || h != wantH;
        wantCodec = c;
        wantW = w;
        wantH = h;
        if (changed) {
            Log.i("视频格式 → " + c + " " + w + "x" + h + (running ? "（将重启解码进程）" : ""));
        }
        return changed;
    }

    /**
     * 设定画面盒（像素，**未旋转**；90/270° 由调用方换算好轴再传，见 VideoWindow）。
     *
     * **可能在 EDT 上被调用** → 只写 volatile，不碰进程：换进程由 pp-video-feed
     * 在下一轮 {@link #outChangedMaterially} 判定里完成（带面积阈值 + 最小间隔抑制拖窗抖动）。
     */
    public void setDisplayBox(int w, int h) {
        if (w == boxW && h == boxH) {
            return;
        }
        boxW = w;
        boxH = h;
    }

    /** 当前盒下应当出帧的尺寸（偶数、≥16、不放大、受 {@link #MAX_OUT_PIXELS} 封顶）。 */
    private int[] targetOut(int w, int h) {
        double s = 1.0;
        int bw = boxW;
        int bh = boxH;
        if (bw > 0 && bh > 0 && w > 0 && h > 0) {
            s = Math.min(1.0, Math.min((double) bw / w, (double) bh / h));
        }
        int ow = evenAtLeast16((int) Math.round(w * s));
        int oh = evenAtLeast16((int) Math.round(h * s));
        long area = (long) ow * oh;
        if (area > MAX_OUT_PIXELS) {
            double c = Math.sqrt((double) MAX_OUT_PIXELS / (double) area);
            ow = evenAtLeast16((int) (ow * c));
            oh = evenAtLeast16((int) (oh * c));
        }
        return new int[]{Math.min(ow, evenAtLeast16(w)), Math.min(oh, evenAtLeast16(h))};
    }

    private static int evenAtLeast16(int v) {
        v &= ~1;
        return v < 16 ? 16 : v;
    }

    /**
     * 出帧尺寸是否需要换进程：面积变化 ≥1.3 倍**且**距上次因 box 换进程 ≥{@link #BOX_SPAWN_MIN_GAP_MS}。
     * 只在 feed 线程调用（读 runOutW/H 无需同步）。
     */
    private boolean outChangedMaterially(int w, int h) {
        if (runOutW <= 0 || runOutH <= 0) {
            return true;
        }
        int[] t = targetOut(w, h);
        long oldA = (long) runOutW * runOutH;
        long newA = (long) t[0] * t[1];
        if (oldA == newA) {
            return false;
        }
        long hi = Math.max(oldA, newA);
        long lo = Math.min(oldA, newA);
        if (hi < lo * 13 / 10) {
            return false;
        }
        return System.currentTimeMillis() - lastBoxSpawnMs >= BOX_SPAWN_MIN_GAP_MS;
    }

    /**
     * 入队一个访问单元（AU）。**只入队，立即返回** —— 网络读线程安全。
     *
     * 注意：{@code data} 由 Protocol.readFrame 每帧新分配（readFully），因此可以直接持有引用，
     * 不需要再拷贝一次（每帧 6 KB 的额外 memcpy 在 30 fps 下虽不致命，但没必要）。
     *
     * @param frameTsMs 该帧的发送端 ts（通话相对 ms），随帧带到喂解码器的时刻
     */
    public void enqueue(byte[] data, int len, int flag, long frameTsMs) {
        if (!running || data == null || len <= 0) {
            return;
        }
        if (len > MAX_AU) {
            dropped.incrementAndGet();
            Log.w("视频帧超限丢弃: " + len + " > " + MAX_AU);
            return;
        }
        boolean key = (flag & FLAG_KEYFRAME) != 0;
        // 参数集帧永远放行（丢了会导致解码器没法初始化）
        boolean cfg = (flag & FLAG_CONFIG) != 0;

        byte[] au = new byte[len];
        System.arraycopy(data, 0, au, 0, len);

        boolean askKey = false;
        try {
            synchronized (qLock) {
                if (dropUntilKey && !key && !cfg) {
                    // 换代/积压之后「等下一个 IDR」期间丢的帧：不是网络丢的，进重置账，不进 drop%
                    resetDropped.incrementAndGet();
                    return;
                }
                if (queue.size() >= MAX_QUEUE) {
                    int lost = queue.size();
                    queue.clear();
                    dropped.addAndGet(lost);
                    if (!key && !cfg) {
                        // 队列里全是非关键帧 → 从此刻起丢到下一个 IDR，避免解码器拿半截 GOP 花屏
                        dropUntilKey = true;
                        dropped.incrementAndGet();
                        dropping = true;   // 丢帧期独立上报；lat 保持上一次真实值，不再折成 -1/0
                        // 清完积压就主动要一个 IDR：发送侧 GOP=1s，不喊就要一直干等到下一个自然 IDR，
                        // 用户看到的正是「几秒卡一下」里最长的那一段静止。出锁后再发（见 finally）。
                        askKey = true;
                        return;
                    }
                }
                dropUntilKey = false;
                dropping = false;
                queue.addLast(new PendingAu(au, System.currentTimeMillis(), frameTsMs, cfg));
                if (waiting) {
                    qLock.notifyAll();
                }
            }
        } finally {
            if (askKey) {
                requestKeySoon();
            }
        }
    }

    /** {@link #enqueue} 积压清队后的补要 IDR，带最小间隔护栏（限频 = 一次拥塞只喊一次）。 */
    private void requestKeySoon() {
        long now = System.currentTimeMillis();
        if (now - lastKeyAskMs < KEY_ASK_MIN_GAP_MS) {
            return;
        }
        lastKeyAskMs = now;
        Runnable h = reseedListener;
        if (h != null) {
            h.run();
        }
    }

    /**
     * 收端重组**丢了半帧** ⇒ 参考链从这一刻起已断：后面每一帧都注定解不出。
     * 实测依据（10-03 09:50 那通的字节流离线复解）：809 个交付 AU 里 172 个被 ffmpeg 判
     * `Could not find ref` + `Error constructing the frame RPS` 直接跳过，172 全部落在
     * "某个 IDR 槽缺位"的那 4 个 GOP 里（29+28+87+28），每缺一帧 IDR 就静止 ~29 帧 ≈ 0.97s
     * —— 这就是「几秒钟卡一下」。以前只有"队列积压溢出"这一条路会认账，半帧丢失完全不认。
     *
     * 处置与积压同形、不新增机制：清掉队列里那些已经救不回来的帧（进**重置账**，不污染 drop%），
     * 置 {@code dropUntilKey} 让后续死链帧不再进管道，然后要一个 IDR（限频照旧
     * {@link #KEY_ASK_MIN_GAP_MS}）。清队这一步是必需的：不清的话新 IDR 排在 ≤{@code MAX_QUEUE}
     * 帧死链后面，恢复要多付半秒排队。由媒体 reader 线程调用，可重入。
     */
    public void notifyChainBroken() {
        synchronized (qLock) {
            int doomed = queue.size();
            if (doomed > 0) {
                queue.clear();
                resetDropped.addAndGet(doomed);
            }
            dropUntilKey = true;
            dropping = true;
        }
        requestKeySoon();
    }

    /** 启动解码（若已运行则无操作）。非阻塞：进程由 feed 线程拉起。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        dropUntilKey = false;
        dropping = false;
        Thread f = new Thread(new Runnable() {
            @Override
            public void run() {
                feedLoop();
            }
        }, "pp-video-feed");
        f.setDaemon(true);
        feedThread = f;
        f.start();
    }

    /**
     * 停止解码。**立即返回**：真正杀进程的动作丢给后台线程，绝不阻塞调用方
     * （挂断路径上任何阻塞都可能演变成死锁，本项目已有教训）。
     */
    public void stop() {
        synchronized (procLock) {
            if (!running && proc == null) {
                return;
            }
            running = false;
        }
        synchronized (qLock) {
            queue.clear();
            deqHead = 0;
            deqTail = 0;
            qLock.notifyAll();
        }
        // 只关闭 stdin 让 ffmpeg 自然退出；同时起独立线程兜底 destroyForcibly。
        // 快照在 procLock 下做：与 spawn() 的注册互斥，不存在「stop 快照到 null、
        // spawn 随后写入 proc」的孤儿窗口。
        final Process p;
        final OutputStream si;
        final InputStream so;
        synchronized (procLock) {
            p = proc;
            si = stdin;
            so = stdout;
            proc = null;
            stdin = null;
            stdout = null;
        }
        Thread k = new Thread(new Runnable() {
            @Override
            public void run() {
                runKill(p, si, so);
            }
        }, "pp-video-kill");
        k.setDaemon(true);
        k.start();
        if (feedThread != null) {
            feedThread.interrupt();
            feedThread = null;
        }
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        Log.i("视频解码停止（累计解码 " + decoded.get() + " 帧，丢弃 " + dropped.get()
                + " 帧，换代重置 " + resetDropped.get() + " 帧）");
    }

    public boolean isRunning() {
        return running;
    }

    public int width() {
        return runW;
    }

    public int height() {
        return runH;
    }

    /** 自上次调用以来累计解码帧数（供 video_fb 计算实际 fps） */
    public long takeDecodedCount() {
        return decoded.get();
    }

    /** 自上次调用以来累计丢弃帧数（**只含真丢帧**：积压清队 + 超 MAX_AU；换代重置见 {@link #takeResetDroppedCount()}） */
    public long takeDroppedCount() {
        return dropped.get();
    }

    /** 累计「换代重置」丢帧数（解码进程重建清队 + 等 IDR 期间丢的帧）。只进汇总行，不进 drop%。 */
    public long takeResetDroppedCount() {
        return resetDropped.get();
    }

    /** 最近一帧排队延迟（ms，真实值；丢帧期不再折成 -1，见 {@link #dropping()}） */
    public int lastQueueDelayMs() {
        return lastQueueDelayMs;
    }

    /**
     * 是否处于丢帧期（与 {@code dropUntilKey} 同置同清，任务单 1.3）。
     * 上报口径：{@code lat} 只说排队时延，「正在丢帧」由本字段单独说。
     */
    public boolean dropping() {
        return dropping;
    }

    /** 最近一帧喂进解码进程时的发送端 ts（通话相对 ms）；本端视频/音频对时与上屏计量用 */
    public long lastFedFrameTsMs() {
        return lastFedFrameTsMs;
    }

    // ==================== feed 线程：进程生命期 + 写 stdin ====================

    private void feedLoop() {
        try {
            while (running) {
                // 0) 硬解验证（用户口径「硬解不行就软解」）：不合格就地杀进程，下一轮自然走软解
                verifyHw();
                // 1) 格式未知 → 等 video_cfg
                if (wantCodec.isEmpty() || wantW <= 0) {
                    sleepQuiet(50);
                    continue;
                }
                // 2) 需要（重新）拉进程：格式变了，**或**画面盒变了（见 setDisplayBox）
                if (proc == null || !runCodec.equals(wantCodec) || runW != wantW || runH != wantH
                        || outChangedMaterially(wantW, wantH)) {
                    if (!spawn(wantCodec, wantW, wantH)) {
                        // 起不来（没找到 ffmpeg / spawn 失败）→ 退避重试，避免刷屏
                        sleepQuiet(1000);
                        continue;
                    }
                }
                // 3) 取一帧写下去
                PendingAu pending = null;
                long deqNano = 0L;
                synchronized (qLock) {
                    if (queue.isEmpty()) {
                        waiting = true;
                        try {
                            qLock.wait(200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        } finally {
                            waiting = false;
                        }
                        continue;
                    }
                    pending = queue.pollFirst();
                    if (pending != null) {
                        deqNano = System.nanoTime();
                        lastQueueDelayMs = (int) Math.min(9999,
                                System.currentTimeMillis() - pending.recvMs);
                    }
                }
                OutputStream os = stdin;
                if (os == null || pending == null) {
                    continue;
                }
                byte[] au = pending.au;
                try {
                    os.write(au);
                    os.flush();
                    if (dumpOut != null) {
                        try {
                            dumpOut.write(au);
                        } catch (IOException e) {
                            // 取证是旁路：磁盘出问题就只关取证，绝不能把通话带下水
                            Log.w("取证文件写入失败，停止取证: " + e.getMessage());
                            closeQuiet(dumpOut);
                            dumpOut = null;
                        }
                    }
                    lastFedFrameTsMs = pending.frameTsMs;
                    if (!pending.cfg) {
                        fedSinceSpawn++;   // 参数集本来就不出帧，不能算进硬解验证的「喂了 5 帧」
                    }
                    // 只有**会出帧**的 AU 才进配对环：参数集喂得进去但不产帧，压环会让
                    // 输出与出队时刻从这一刻起永久错开一帧（宁缺勿错配）。
                    if (!pending.cfg) {
                        pushDeqNano(deqNano);
                    }
                } catch (IOException e) {
                    // 管道断了（ffmpeg 挂了）→ 收摊，下一轮重新 spawn
                    if (running) {
                        Log.w("写入解码进程失败，将重启: " + e.getMessage());
                    }
                    // 锁内快照并置空 stdin 再关（与 stop 的快照互斥），随后杀进程
                    OutputStream dead;
                    synchronized (procLock) {
                        dead = stdin;
                        stdin = null;
                    }
                    closeQuiet(dead);
                    killProc();
                }
            }
        } finally {
            // 兜底：保证「拉起后必然有人负责杀」——覆盖 feed 线程在仍持有活进程时
            // 退出的任何路径（interrupt 唤醒 / running=false）。幂等：stop 已接手则空操作。
            killProc();
        }
    }

    /** 压入一个「已喂进 ffmpeg 的 AU」的出队时刻（feed 线程，qLock 下）。 */
    private void pushDeqNano(long nano) {
        synchronized (qLock) {
            deqRing[deqTail] = nano;
            deqTail = (deqTail + 1) & DEQ_MASK;
            if (deqTail == deqHead) {
                deqHead = (deqHead + 1) & DEQ_MASK;  // 满：丢最旧（宁少不错）
            }
        }
    }

    /**
     * 弹出一个配对时刻并记 vOut 段（read 线程）。
     * 环空 = 这一帧输出没有配对（例如参数集 AU 不出帧），直接跳过。
     */
    private void popDeqAndRecord() {
        long t0;
        synchronized (qLock) {
            if (deqHead == deqTail) {
                return;
            }
            t0 = deqRing[deqHead];
            deqHead = (deqHead + 1) & DEQ_MASK;
        }
        SegMeter.get().record(SegMeter.V_OUT, t0, System.nanoTime());
    }

    /** 清空配对环（进程换代 / 停止：旧时刻与新输出不再对应） */
    private void clearDeqRing() {
        synchronized (qLock) {
            deqHead = 0;
            deqTail = 0;
        }
    }

    /** 拉起 ffmpeg 进程（在 feed 线程内调用）。返回是否成功。 */
    private boolean spawn(String codec, int w, int h) {
        killProc();
        clearDeqRing();
        // 问题2（修复清单 1.4）：换分辨率 = 换进程，而输入 queue 里残留的是**旧尺寸**的 AU。
        // 改前这里只清配对环、没清输入队列 ⇒ 新 ffmpeg 被喂旧分辨率 AU，输出就是花屏/九宫格，
        // 并顺着 video_fb 的 drop% 把档位一路往下拉。
        // 一并进 dropUntilKey：新进程只能从一个 IDR 起解，尺寸凑巧对上的中途帧参考链也已断。
        synchronized (qLock) {
            int lost = queue.size();
            queue.clear();
            if (lost > 0) {
                resetDropped.addAndGet(lost);
            }
            dropUntilKey = true;
            dropping = true;   // 丢帧期独立上报，直到下一个 IDR 入队（与 enqueue 的清除点同一条）
        }
        // 用户没显式指定路径、且本会话还没判定硬解不行 ⇒ 优先挑一个「自己会硬解」的 exe（见 pickExe）
        String exe = pickExe(userPath.isEmpty() && !hwAbandoned);
        if (exe == null) {
            Log.e("找不到 ffmpeg：请把最小化 ffmpeg.exe 放到 pc/bin/ 下，"
                    + "或在 config.json 里设置 ffmpegPath");
            return false;
        }
        int[] out = targetOut(w, h);
        boolean scaled = out[0] != w || out[1] != h;
        int bytes = out[0] * out[1] * 4;
        // 硬解优先、软解兜底：hw 为空就是纯软解（这个 exe 没有 hwaccel，或本会话已降级过）
        final String hw = hwaccelFor(exe);
        List<String> cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        if (!hw.isEmpty()) {
            // 不加 -hwaccel_output_format，让 ffmpeg 自动回拷到系统内存，
            // 这样 -pix_fmt bgra 依然可用。
            cmd.add("-hwaccel");
            cmd.add(hw);
        }
        cmd.add("-probesize");
        cmd.add("32");
        cmd.add("-analyzeduration");
        cmd.add("0");
        cmd.add("-flags");
        cmd.add("low_delay");
        cmd.add("-threads");
        cmd.add("1");
        cmd.add("-f");
        cmd.add(codec);
        cmd.add("-i");
        cmd.add("pipe:0");
        if (scaled) {
            // 缩放交给 ffmpeg（swscale 是 C + SIMD），比在 Java 里逐像素降采样便宜两个数量级：
            // 目标只是让「每帧过管道的字节数」降到管道吞吐能跑满 30 fps 的水位以下。
            cmd.add("-vf");
            cmd.add("scale=" + out[0] + ":" + out[1] + ":flags=bilinear");
        }
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("bgra");
        cmd.add("-fps_mode");
        cmd.add("passthrough");
        cmd.add("pipe:1");

        // 提到 try 外：pb.start() 成功后若后续步骤抛异常（如 OOM），
        // catch 里仍能 destroyForcibly + 关闭已取得的流，不留孤儿进程
        Process p = null;
        OutputStream si = null;
        InputStream so = null;
        InputStream er = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            p = pb.start();
            si = p.getOutputStream();
            so = p.getInputStream();
            er = p.getErrorStream();

            // 在 procLock 下注册：与 stop() 的快照互斥。
            // 若 running 已为 false（spawn 期间发生 stop）→ 不注册，进程由本方法负责杀。
            boolean registered;
            synchronized (procLock) {
                registered = running;
                if (registered) {
                    proc = p;
                    stdin = si;
                    stdout = so;
                    runCodec = codec;
                    runW = w;
                    runH = h;
                    runOutW = out[0];
                    runOutH = out[1];
                    frameBufSize = bytes;
                    frameBuf = new byte[bytes];
                    runHwaccel = hw;
                    spawnAtMs = System.currentTimeMillis();
                    lastBoxSpawnMs = spawnAtMs;
                    decodedAtSpawn = decoded.get();
                    fedSinceSpawn = 0;
                }
            }
            if (!registered) {
                // stop() 已返回：不会再有人管这个进程，这里同步杀掉
                runKill(p, si, so);
                closeQuiet(er);
                return false;
            }
            Log.i("ffmpeg 已启动 [" + codec + " " + w + "x" + h
                    + (scaled ? "→出帧 " + out[0] + "x" + out[1] : "")
                    + " " + String.format("%.1fMB/帧", bytes / 1048576.0)
                    + (hw.isEmpty() ? " 软解" : " 硬解 " + hw) + "] → " + exe);
            openDump(codec);

            final Process proc0 = p;
            final InputStream err0 = er;
            final InputStream out0 = so;
            Thread e = new Thread(new Runnable() {
                @Override
                public void run() {
                    readErr(err0);
                }
            }, "pp-video-err");
            e.setDaemon(true);
            errThread = e;
            e.start();

            Thread r = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(out0, proc0);
                }
            }, "pp-video-read");
            r.setDaemon(true);
            readThread = r;
            r.start();
            // 新进程只能从一个 IDR 起解，而编码器 GOP=1s ⇒ 不主动要一个关键帧，画面就要白等到下一个
            // IDR（用户看到的「接通后卡几秒」）。请求走主 TCP 既有的 video_req 帧（I16 的控制落点之一），
            // 非阻塞：LinkCall 那边是 uiSendExec.execute。
            // ⚠ 只在**进程真的起来了**之后要帧：spawn 失败（没找到 ffmpeg / 起不来）时 feed 循环每秒
            //   重试一次，放在前面就会变成每秒一份 video_req 的周期性发包。
            final Runnable hook = reseedListener;
            if (hook != null) {
                hook.run();
            }
            return true;
        } catch (Exception e) {
            Log.e("启动 ffmpeg 失败: " + e);
            if (p != null) {
                // pb.start() 已成功但后续步骤抛异常 → 必须杀进程
                p.destroyForcibly();
            }
            closeQuiet(si);
            closeQuiet(so);
            closeQuiet(er);
            synchronized (procLock) {
                proc = null;
                stdin = null;
                stdout = null;
                runCodec = "";
            }
            return false;
        }
    }

    // ==================== read 线程：读 BGRA 原始帧 ====================

    private void readLoop(InputStream in, Process owner) {
        while (running && proc == owner) {
            int sz = frameBufSize;
            if (sz <= 0) {
                sleepQuiet(20);
                continue;
            }
            byte[] buf = frameBuf;
            int got = 0;
            boolean eof = false;
            while (got < sz) {
                int r;
                try {
                    r = in.read(buf, got, sz - got);
                } catch (IOException e) {
                    eof = true;
                    break;
                }
                if (r < 0) {
                    eof = true;
                    break;
                }
                got += r;
            }
            if (eof) {
                if (running && proc == owner) {
                    Log.w("解码进程输出结束（可能已退出）");
                }
                return;
            }
            decoded.incrementAndGet();
            Sink s = sink;
            if (s != null) {
                try {
                    // 复用缓冲直接交付：本回调运行在 pp-video-read 上，
                    // 下一帧要等本次返回后才会读进来，因此不存在覆写竞争。
                    s.onFrame(buf, runOutW, runOutH, sz);
                } catch (Throwable t) {
                    Log.w("视频帧回调异常: " + t);
                }
            }
            // 交付完成 → 弹出该帧的出队时刻记 vOut（PC 的「上屏」取回调返回；
            // VideoWindow 用 repaintPending 合帧，EDT 真实重绘不计入，由任务单 5 的 renderGapMs 对照）
            popDeqAndRecord();
        }
    }

    private void readErr(InputStream err) {
        if (err == null) {
            return;
        }
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(err, java.nio.charset.StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    Log.w("[ffmpeg] " + line);
                }
            }
        } catch (Exception ignore) {
            // 进程结束时流会断，正常
        } finally {
            closeQuiet(err);
        }
    }

    // ==================== 收尾 ====================

    /** 见 {@link #dumpDir}：每次解码进程换代开一个新文件，文件名带 codec/尺寸/换代时刻。 */
    private void openDump(String codec) {
        if (dumpDir.isEmpty()) {
            return;
        }
        String name = dumpDir + File.separator + "au_" + codec + "_" + runW + "x" + runH
                + "_" + System.currentTimeMillis() + ("hevc".equals(codec) ? ".265" : ".264");
        try {
            dumpOut = new java.io.BufferedOutputStream(
                    Files.newOutputStream(Paths.get(name)), 1 << 16);
            Log.i("取证开启（PP_VIDEO_DUMP）→ " + name);
        } catch (Exception e) {
            Log.w("取证文件创建失败，本次不取证: " + e);
            dumpOut = null;
        }
    }

    private void killProc() {
        Process p;
        OutputStream si;
        InputStream so;
        synchronized (procLock) {
            p = proc;
            si = stdin;
            so = stdout;
            proc = null;
            stdin = null;
            stdout = null;
            runCodec = "";
        }
        runKill(p, si, so);
        closeQuiet(dumpOut);
        dumpOut = null;
    }

    private void runKill(Process p, OutputStream si, InputStream so) {
        closeQuiet(si);
        if (p != null) {
            try {
                // 关掉 stdin 后 ffmpeg 通常会自己退出；给 200ms 宽限再强杀
                if (!p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroy();
                    if (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        closeQuiet(so);
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 归一化编码名：只接受 hevc / h264（h265 / avc 做别名兼容） */
    private static String normalizeCodec(String codec) {
        if (codec == null) {
            return "";
        }
        String c = codec.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.equals("h265") || c.equals("hevc")) {
            return "hevc";
        }
        if (c.equals("avc") || c.equals("h264")) {
            return "h264";
        }
        return "";
    }
}
