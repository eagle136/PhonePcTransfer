package com.phonepctransfer.net

/**
 * 单条传输的速率采样器（2026-09-22 新增，给气泡显示 `650K/s` / `0.95M/s` 用）。
 *
 * **为什么不用气泡里那个 progress 百分比反推**：progress 是**整数百分比**（0..100），
 * 对 2GB 文件 1% = 21MB、对 10GB 是 105MB —— 采样间隔会被拉成好几秒，
 * 恰恰是「大文件」这种最需要看速率的场景显示不连续。所以速率在**字节级**采样：
 * 发送循环（`sent += len`）与接收写盘（`onChunk`）本来就有精确字节数。
 *
 * 本类只做三件事，不碰 UI、不碰线程：
 *  - **节流**：最快 [MIN_INTERVAL_MS] 交一次样本（否则每 1MB 分片都要回调一次界面）
 *  - **平滑**：EMA（新样本权重 0.4）—— 1MB 分片 + TCP 抖动会让瞬时值乱跳
 *  - **归零**：字节总数倒退（断点续传/重置）时清掉历史，绝不给出负速率
 *
 * 回调跑在**调用线程**（发送线程 / 接收读线程），由上层负责切主线程。
 */
internal class SpeedMeter(private val now: () -> Long = { System.currentTimeMillis() }) {

    private var lastBytes = -1L
    private var lastAt = 0L
    private var ema = 0.0

    /** 累加到一个新的字节总数 [bytes]；够一个采样间隔就回调一次平滑速率（字节/秒） */
    @Synchronized
    fun sample(bytes: Long, emit: (Long) -> Unit) {
        val t = now()
        if (lastBytes < 0L) {          // 第一个样本只记基线，算不出速率
            lastBytes = bytes
            lastAt = t
            return
        }
        if (bytes < lastBytes) {       // 字节倒退（续传/重置）→ 重新取基线
            lastBytes = bytes
            lastAt = t
            ema = 0.0
            return
        }
        val dt = t - lastAt
        if (dt < MIN_INTERVAL_MS) return
        val inst = (bytes - lastBytes) * 1000.0 / dt
        ema = if (ema <= 0.0) inst else ema * 0.6 + inst * 0.4
        lastBytes = bytes
        lastAt = t
        emit(ema.toLong().coerceAtLeast(0L))
    }

    companion object {
        /** 采样最短间隔（ms）：再密是刷屏，再疏数字会「跳字」 */
        internal const val MIN_INTERVAL_MS = 500L
    }
}
