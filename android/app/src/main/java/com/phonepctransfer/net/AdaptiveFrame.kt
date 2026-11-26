package com.phonepctransfer.net

import android.util.Log
import java.util.Locale

/**
 * 自适应分片大小控制器（单连接下尽量把带宽跑满）。
 *
 * 档位 64K→8M，**默认从 1M 起步**（避免小档爬坡把起步速度拖慢）；
 * 每 [WINDOW_MS] 评估一次：吞吐不降、单帧写入没有明显背压 → 升一档；一旦发现抖动 → 立即降两档。
 *
 * 判据：
 * 1. 窗口吞吐环比：本 0.5s 与上一 0.5s 的 B/s 比，掉到 [DROP_RATIO] 以下视为抖动；
 * 2. 单帧写耗时：`sendChunk` 是阻塞写，发送缓冲满时它会卡住 —— 卡顿帧占比超 1/4 视为背压。
 *    （TCP 层拿不到 RTT/重传，只能靠"写不动"这个外显信号）
 *
 * 顶档 8M 的依据（2026-11-22 抬档）：接收端轮转缓冲 [Protocol.RX_BUF_SIZE] 已双端同步提到 8MB，
 * 8M 级帧能整帧直读进轮转缓冲走零拷贝；再高不行 —— 协议单帧硬上限 [Protocol.MAX_FRAME] 就是 8MB。
 * ⚠ 8M 是**载荷**档位：帧头(6B+mid)也占 MAX_FRAME 额度，发送侧必须用 [framePayloadCap] 再夹一次载荷。
 *
 * 线程安全：一个传输任务一个实例，只在发送线程内调用，不加锁。
 */
internal class AdaptiveFrame(private val label: String = "") {

    companion object {
        private const val TAG = "PP-Send"

        /**
         * 档位表 64K→8M。顶档 8M 是**载荷**档位，而 [Protocol.MAX_FRAME](8MB) 是整帧上限：
         * 帧头(6B + mid)还要占额度，所以发送侧一律再夹 [framePayloadCap] → 一帧正好卡在 8MB，
         * 接收端轮转缓冲 [Protocol.RX_BUF_SIZE](8MB) 仍能整块直读，不额外分配。
         */
        val GEARS = intArrayOf(
            64 * 1024,
            128 * 1024,
            256 * 1024,
            512 * 1024,
            1024 * 1024,
            2 * 1024 * 1024,
            4 * 1024 * 1024,
            8 * 1024 * 1024
        )

        /** 默认档位下标 = 1M */
        const val DEFAULT_INDEX = 4

        const val WINDOW_MS = 500L
        /** 吞吐环比低于该比例视为抖动 */
        const val DROP_RATIO = 0.75
        /** 单帧写超过该耗时视为背压（绝对下限；正常局域网 1M 帧在几十微秒到几毫秒） */
        const val SLOW_FRAME_MS = 60L
    }

    private var idx = DEFAULT_INDEX
    private var windowStart = System.nanoTime()
    private var windowBytes = 0L
    private var windowFrames = 0
    private var slowFrames = 0
    private var lastTp = 0.0

    /** 升档次数（诊断用） */
    var ups = 0
        private set

    /** 降档次数（诊断用） */
    var drops = 0
        private set

    fun size(): Int = GEARS[idx]

    fun gearBytes(): Int = GEARS[idx]

    /** 每帧发完后调用：len=本帧载荷字节，writeNanos=本次写入耗时 */
    fun onFrame(len: Int, writeNanos: Long) {
        windowBytes += len.toLong()
        windowFrames++
        if (isSlowFrame(len, writeNanos)) slowFrames++
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS * 1_000_000L) return
        val tp = windowBytes * 1e9 / elapsed.toDouble()
        val slowHeavy = windowFrames > 0 && slowFrames * 4 >= windowFrames
        val shrunk = lastTp > 0.0 && tp < lastTp * DROP_RATIO
        val before = idx
        if (slowHeavy || shrunk) {
            if (idx > 0) {
                idx = (idx - 2).coerceAtLeast(0)
                drops++
            }
        } else if (lastTp <= 0.0 || tp >= lastTp) {
            if (idx < GEARS.size - 1) {
                idx++
                ups++
            }
        }
        if (idx != before) {
            // 档位变化日志（每 0.5s 窗口最多一行，只记变化点，不污染热路径）
            logGear(before, tp, shrunk, slowHeavy)
        }
        lastTp = tp
        windowStart = now
        windowBytes = 0
        windowFrames = 0
        slowFrames = 0
    }

    /**
     * 慢帧判据（背压信号）：单帧写耗时超过「按上一窗口吞吐这一帧本该花多久」的 2 倍才算卡，
     * 且不低于 [SLOW_FRAME_MS] 的绝对下限。
     * <p>为什么不能只用 60ms：顶档 8M 帧在 130MB/s 下天然就要 ~64ms，固定 60ms 会把顶档帧
     * 全判成慢帧 → 每窗口 slowHeavy 成立 → 8M 与 2M 之间来回抖（抬档等于白抬）。
     */
    private fun isSlowFrame(len: Int, writeNanos: Long): Boolean {
        var floor = SLOW_FRAME_MS * 1_000_000L
        if (lastTp > 0.0) {
            val expect = (len * 1e9 / lastTp).toLong()
            floor = maxOf(floor, expect * 2)
        }
        return writeNanos > floor
    }

    /** 档位变化一行：`[frame] 文件 升档 4M→8M 吞吐=128.3MB/s 慢帧=0/16 已到顶档` */
    private fun logGear(from: Int, tp: Double, shrunk: Boolean, slowHeavy: Boolean) {
        val sb = StringBuilder(96)
        sb.append("[frame] ")
        if (label.isNotEmpty()) sb.append(label).append(' ')
        sb.append(if (idx > from) "升档 " else "降档 ")
            .append(gearName(GEARS[from])).append('→').append(gearName(GEARS[idx]))
            .append(" 吞吐=").append(String.format(Locale.US, "%.1f", tp / (1024.0 * 1024.0))).append("MB/s")
            .append(" 慢帧=").append(slowFrames).append('/').append(windowFrames)
        if (shrunk && lastTp > 0.0) {
            sb.append(" 环比=").append(String.format(Locale.US, "%.0f%%", (tp / lastTp - 1.0) * 100.0))
        }
        if (slowHeavy) sb.append(" 慢帧超标")
        if (idx == GEARS.size - 1) sb.append(" 已到顶档")
        Log.i(TAG, sb.toString())
    }

    private fun gearName(bytes: Int): String =
        if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)}M" else "${bytes / 1024}K"

    /** 重置（每个文件重新开始时调用，避免上一个文件的窗口数据污染判断） */
    fun reset() {
        idx = DEFAULT_INDEX
        windowStart = System.nanoTime()
        windowBytes = 0
        windowFrames = 0
        slowFrames = 0
        lastTp = 0.0
        ups = 0
        drops = 0
    }
}
