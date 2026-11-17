package com.phonepctransfer.util

import java.util.concurrent.atomic.AtomicLong

// ==================================================================
// 通话计数器——M1/M2/M3/M8/M10 判据载体；与 PC 端 `pc/CallCounters.java` 同名同序（19 条），
// 改一处必须同时改两处；顺序 = 下方声明顺序 = 打印顺序（口径见各条声明处 javadoc）：
//   1–13 基础：audioDroppedByLock（文件传输抢占通道丢，M3 恒 0）/ audioDroppedByAge（入队前超龄 >200ms，与视频分开计）/
//     audioMutedByBacklog（「静音+清到最新」次数）/ audioPlcUsed（Opus PLC 补帧）/ videoDroppedByAge（M2 主要证据）/
//     videoDroppedByGate（单帧闸门拦下，IDR/CONFIG 豁免但计数）/ videoReassembleFail（缺片/错序/长度不符）/ keyframeReq（本端关键帧请求条数）/
//     windowStall（发送窗口停摆，M8/M4 证据）/ fbLost（反馈缺失）/ ctrlOwnerViolation（非 owner 同名字段被忽略，恒 0）/
//     ctrlResent（只计 300ms 重发批）/ mediaSuspectedBlocked（1s 无反馈 ⇒ 疑似被拦）
//   14–16 收端视频取证：videoSliceMissing（缺片数之和）/ videoSliceLate（迟到片数，与 Missing 一比即分诊）/ videoKeyframeLost（丢掉的半帧带 IDR/CONFIG，参考链起点没了）；
//   17 迟到音频：audioDroppedByLateSeq（seq ≤ playedSeq 被迟到闸门丢；证明补静音自锁）；
//   18–19 水位与短读：audioTrimmedByBacklog（水位法裁回目标时丢的最旧帧数）/ audioShortReadFrames（采集读不满补零帧数 ——「听感断续而计数全 0」的静默通道）；
// 热路径：inc 只做一次自增，不加锁/不拼字符串/不打日志；add(n) 只允许低频事件；汇总行只在挂断时拼一次（logSummary）；
// ++ 点必须在「本通 TALKING」分支里，清理线程与上一通残留媒体线程不许再加。
// ==================================================================
object CallCounters {

    /** 单个计数器：名字与属性同行绑定，避免汇总行按索引错位贴错标签 */
    class Counter(val name: String) {
        private val value = AtomicLong()

        fun inc() {
            value.incrementAndGet()
        }

        /**
         * 一次累加 n（n 可以 >1）。用途只限"按量"的取证计数（如缺片**片数**）；
         * 调用点必须在低频事件上，不许进每包/每帧的热路径（热路径口径仍是：一次自增、不拼字符串、不打日志）。（与 PC 端同名同义）
         */
        fun add(n: Long) {
            if (n > 0) {
                value.addAndGet(n)
            }
        }

        fun get(): Long = value.get()

        fun reset() {
            value.set(0L)
        }
    }

    // ==================== 19 个计数器（与 PC 端 CallCounters.java **逐条同序**：1–13 基础、
    //                        14–16 收端视频取证三条、17 迟到音频、18–19 音频水位与采集短读） ====================

    val audioDroppedByLock = Counter("audioDroppedByLock")
    val audioDroppedByAge = Counter("audioDroppedByAge")
    val audioMutedByBacklog = Counter("audioMutedByBacklog")
    val audioPlcUsed = Counter("audioPlcUsed")
    val videoDroppedByAge = Counter("videoDroppedByAge")
    val videoDroppedByGate = Counter("videoDroppedByGate")
    val videoReassembleFail = Counter("videoReassembleFail")
    val keyframeReq = Counter("keyframeReq")
    val windowStall = Counter("windowStall")
    val fbLost = Counter("fbLost")
    val ctrlOwnerViolation = Counter("ctrlOwnerViolation")
    val ctrlResent = Counter("ctrlResent")
    val mediaSuspectedBlocked = Counter("mediaSuspectedBlocked")

    /** 收端视频取证三条：收端重组器丢半帧时进，双端同名同序（接线点唯一 = `VideoReassembler.reportLost`）。 */
    val videoSliceMissing = Counter("videoSliceMissing")
    val videoSliceLate = Counter("videoSliceLate")
    val videoKeyframeLost = Counter("videoKeyframeLost")

    /** 迟到音频取证：迟到音频帧被丢，双端同名同加，排在各自末尾。 */
    val audioDroppedByLateSeq = Counter("audioDroppedByLateSeq")

    /**
     * （播放侧）：水位法把队列裁回目标水位时丢掉的**最旧**音频帧数。
     * 与 [audioDroppedByLateSeq] 的区别：这里是本端主动"晚到的不追了"，
     * 那里是帧到了却被 playedSeq 判死。与它一样只加不减。
     */
    val audioTrimmedByBacklog = Counter("audioTrimmedByBacklog")

    /**
     * （发送侧）：采集线程一次 read 没读满一帧、被补零后照常 seq++ 的帧数。
     * 这些帧**发出去了、对端也收到了**，所以双端所有接收计数都不会动，
     * 但里面是静音 ⇒ 对端听到一个 frameMs 的洞。这是"听感断续而计数全 0"的唯一静默通道。
     */
    val audioShortReadFrames = Counter("audioShortReadFrames")

    /** 打印顺序 = 上面的声明顺序；引用的是对象本身，改名不会错位 */
    private val all = arrayOf(
        audioDroppedByLock, audioDroppedByAge, audioMutedByBacklog, audioPlcUsed,
        videoDroppedByAge, videoDroppedByGate, videoReassembleFail, keyframeReq,
        windowStall, fbLost, ctrlOwnerViolation, ctrlResent, mediaSuspectedBlocked,
        videoSliceMissing, videoSliceLate, videoKeyframeLost,
        audioDroppedByLateSeq, audioTrimmedByBacklog, audioShortReadFrames
    )

    /**
     * 每次进入 TALKING 前清零（与 CallClock.reset / SegMeter.start 同位置，双端同序）。
     * 计数器是**本次通话**的计数，不跨通话累加，否则 M3 的「恒 0」判据会被上一通的残留污染。
     */
    fun reset() {
        for (c in all) c.reset()
    }

    /**
     * 唯一打印点：挂断时打一行，17 个名字全部出现（含值为 0 的）——「缺行就是没接上」，不许省略 0 值项；
     * 打印走调用方给的 sink（不在 util 里依赖 AppCore，与 SegMeter 同一做法）。
     */
    fun logSummary(printer: (String) -> Unit) {
        val sb = StringBuilder("通话计数")
        for (c in all) sb.append(' ').append(c.name).append('=').append(c.get())
        printer(sb.toString())
    }
}
