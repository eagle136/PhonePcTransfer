package com.phonepctransfer.pc;

import java.util.concurrent.atomic.AtomicLong;

// ==================================================================
// 通话计数器（任务单 1.7）——M1/M2/M3/M8/M10 的判据载体。
//
// 与安卓端 `android/.../util/CallCounters.kt` **同名同序**（改一处必须同时改两处），
// 这批名字同时被 方案 §5-S17/D14/D15 与 任务单 3.12 引用，**逐字符**一致才算得上对：
// 这里简写一分，验收时两端就有一端对不上。
//
// 本单只建「名字 + 一处定义 + 一处打印」，计数值暂时全为 0 是**预期状态**；
// 真实 ++ 点由任务单 3.12 / 4.x / 5.x 接上，每条要求「有且只有一个 ++ 点」，
// 没有任何计数器可以「建了但没人加」（那就是下一个 lat 恒 0）。
//
// 口径（谁加、加什么；本单不实现，只写清楚，免得 3.12 接单时凭印象改名）：
//   audioDroppedByLock     音频帧因文件传输抢占通道而被丢 —— M3 要求正常通话恒 0
//   audioDroppedByAge      音频入队前超龄（>200ms）丢弃；与视频**分开计**，合一个数就没法判 M1/M2 各自达标没有
//   audioMutedByBacklog    积压超阈值触发「静音 + 清到最新」的次数（取代旧快进日志）
//   audioPlcUsed           Opus PLC（decode(null)）补帧次数
//   videoDroppedByAge      视频帧超龄丢弃 —— M2 的主要证据
//   videoDroppedByGate     视频帧被单帧闸门拦下的次数（IDR/CONFIG 豁免静默丢，但必须计数并上报）
//   videoReassembleFail    分片重组失败（缺片 / 错序 / 长度不符）
//   keyframeReq            本端发出的关键帧请求条数
//   windowStall            发送窗口停摆（拥塞背压）次数 —— M8/M4 的证据
//   fbLost                 反馈帧缺失计数（与 mediaSuspectedBlocked 的 1s 判据同源）
//   ctrlOwnerViolation     非 owner 写来的同名字段被忽略的次数 —— 正常通话恒 0
//   ctrlResent             **只**统计 300ms 重发那一批；合并窗里被合掉的帧不进这个数
//   mediaSuspectedBlocked  1s 收不到任何反馈 ⇒ 疑似被拦（第 5 轮新增）。
//                          ⚠ 这一条是「日志行 + 计数」两件事：本单只把**名字**定死，
//                            日志内容由任务单 2.9 / 3.7 填。
//   videoSliceMissing      半帧被丢时**缺的片数**之和（不是帧数，帧数已是 videoReassembleFail）。
//   videoSliceLate         片迟到的**片数**：这一片的 streamSeq 最近在环里被扫掉过 ⇒ 它到了、但已经晚了
//                          （环容量 64 个半帧 ≈ 2s 记忆，见 AvTransport.VideoReassembler）。
//                          与 videoSliceMissing 一比就是分诊：late≈0 而 missing 大 = 片根本没到本端；
//                          late 与 missing 同量级 = 片到了但超窗，那才轮到改 REORDER_WINDOW_MS。
//   videoKeyframeLost      丢掉的半帧带 IDR/CONFIG 标志的次数 —— 参考链起点没了，才是卡死的直接原因。
//                          ⚠ 这三条 10-03 第三轮联调（PC 收端 64KB UDP 内核缓冲）时**只有 PC 侧**有接线点，
//                            同日 A+C 那轮把安卓收端重组器补齐（同名同序、同样三个 ++ 点、同一行日志），
//                            所以本节开头的「双端同名同序」重新对全部 17 条成立，不再有例外。
//   audioDroppedByLateSeq  10-03 T6 取证：音频帧**到了**但 seq ≤ playedSeq（这一格已被补静音或
//                          积压快进推过去）⇒ 被 onAudioReceived 的迟到闸门丢掉。
//                          ⚠ 与 audioDroppedByAge 不是一回事：ByAge 量「入队前超龄 >200ms」，
//                            这一条量「严格按序播放下位置已过期」；同一帧可两条并现，
//                            但只有这条能证明「塌的是补静音自锁，不是链路没送到」。
//                          这条**双端同名同加**（安卓 VoiceEngine.kt / PC VoiceEngine.java 各一个
//                            ++ 点，都排在本端末尾），所以它不像上面三条那样欠对端一个名字。
//
// 热路径约束：++ 只做一次 AtomicLong 自增，不加锁、不拼字符串、不加日志；
// 汇总行只在挂断时拼一次（logSummary，唯一打印点），通话期间不打。
//
// 接 ++ 点时（任务单 3.12/4.x/M3）必须满足：该自增在「本通仍在 TALKING」的分支里。
// 挂断后清理线程（pp-voice-stop）、以及**上一通残留的媒体线程**都不许再往这里加，
// 否则 M3 的「audioDroppedByLock 恒 0」判的是下一通的数，验收数字不可信。
// ==================================================================
public final class CallCounters {

    /** 单个计数器：名字与字段同行绑定，避免汇总行按索引错位贴错标签 */
    public static final class Counter {
        public final String name;
        private final AtomicLong value = new AtomicLong();

        Counter(String name) {
            this.name = name;
        }

        public void inc() {
            value.incrementAndGet();
        }

        /**
         * 一次累加 n（n 可以 >1）。用途只限"按量"的取证计数（如缺片**片数**），
         * 调用点必须在低频事件上（半帧被丢 = 每秒至多个位数），不许进每包/每帧的热路径 ——
         * 热路径口径仍是上面那条：一次自增、不拼字符串、不打日志。
         */
        public void add(long n) {
            if (n > 0) {
                value.addAndGet(n);
            }
        }

        public long get() {
            return value.get();
        }

        void reset() {
            value.set(0L);
        }
    }

    // ==================== 19 个计数器（与安卓端 CallCounters.kt **逐条同序**：1–13 基础、
    //                        14–16 收端视频取证三条、17 迟到音频、18–19 10-06 音频水位与采集短读） ====================

    public final Counter audioDroppedByLock = new Counter("audioDroppedByLock");
    public final Counter audioDroppedByAge = new Counter("audioDroppedByAge");
    public final Counter audioMutedByBacklog = new Counter("audioMutedByBacklog");
    public final Counter audioPlcUsed = new Counter("audioPlcUsed");
    public final Counter videoDroppedByAge = new Counter("videoDroppedByAge");
    public final Counter videoDroppedByGate = new Counter("videoDroppedByGate");
    public final Counter videoReassembleFail = new Counter("videoReassembleFail");
    public final Counter keyframeReq = new Counter("keyframeReq");
    public final Counter windowStall = new Counter("windowStall");
    public final Counter fbLost = new Counter("fbLost");
    public final Counter ctrlOwnerViolation = new Counter("ctrlOwnerViolation");
    public final Counter ctrlResent = new Counter("ctrlResent");
    public final Counter mediaSuspectedBlocked = new Counter("mediaSuspectedBlocked");

    /** 10-03 取证三条：收端重组器丢半帧时进，双端同名同序（安卓接线点同为 `VideoReassembler.reportLost`）。 */
    public final Counter videoSliceMissing = new Counter("videoSliceMissing");
    public final Counter videoSliceLate = new Counter("videoSliceLate");
    public final Counter videoKeyframeLost = new Counter("videoKeyframeLost");

    /** 10-03 T6 取证：迟到音频帧被丢，双端同名同加，排在各自末尾。 */
    public final Counter audioDroppedByLateSeq = new Counter("audioDroppedByLateSeq");

    /**
     * 10-06 T2 取证（播放侧）：总水位法把队列裁回目标水位时丢掉的**最旧**音频帧数。
     * 与 {@link #audioDroppedByLateSeq} 的区别：这里是本端主动"晚到的不追了"，
     * 那里是帧到了却被 playedSeq 判死。与 {@link #audioDroppedByLateSeq} 一样只加不减。
     */
    public final Counter audioTrimmedByBacklog = new Counter("audioTrimmedByBacklog");

    /**
     * 10-06 T3.1 取证（发送侧）：采集线程一次 `read` 没读满一帧、被补零后照常 seq++ 的帧数。
     * 这些帧**发出去了、对端也收到了**，所以双端所有接收计数都不会动，
     * 但里面是静音 ⇒ 对端听到一个 frameMs 的洞。这是"听感断续而计数全 0"的唯一静默通道。
     */
    public final Counter audioShortReadFrames = new Counter("audioShortReadFrames");

    /** 打印顺序 = 上面的声明顺序；引用的是字段本身，改名不会错位 */
    private final Counter[] all = {
            audioDroppedByLock, audioDroppedByAge, audioMutedByBacklog, audioPlcUsed,
            videoDroppedByAge, videoDroppedByGate, videoReassembleFail, keyframeReq,
            windowStall, fbLost, ctrlOwnerViolation, ctrlResent, mediaSuspectedBlocked,
            videoSliceMissing, videoSliceLate, videoKeyframeLost, audioDroppedByLateSeq,
            audioTrimmedByBacklog, audioShortReadFrames
    };

    public interface Printer {
        void println(String line);
    }

    private static final CallCounters INSTANCE = new CallCounters();

    public static CallCounters get() {
        return INSTANCE;
    }

    /**
     * 每次进入 TALKING 前清零（与 CallClock.reset / SegMeter.start 同位置，双端同序）。
     * 计数器是**本次通话**的计数，不跨通话累加，否则 M3 的「恒 0」判据会被上一通的残留污染。
     */
    public void reset() {
        for (Counter c : all) {
            c.reset();
        }
    }

    /**
     * 唯一打印点：挂断时打一行，17 个名字**全部**出现（含值为 0 的）。
     * 任务单 6 的判据是「缺行就是没接上」，所以这里不许省略 0 值项。
     */
    public void logSummary(Printer printer) {
        StringBuilder sb = new StringBuilder("通话计数");
        for (Counter c : all) {
            sb.append(' ').append(c.name).append('=').append(c.get());
        }
        printer.println(sb.toString());
    }

    private CallCounters() {
    }
}
