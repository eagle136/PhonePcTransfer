package com.phonepctransfer.net

/**
 * 单条传输的速率采样器（给气泡显示 `650K/s` / `0.95M/s` 用）。
 * 在**字节级**采样（progress 是整数百分比，大文件 1% 是几十 MB，百分比反推采样间隔太粗;
 * 发送循环/接收写盘本来就有精确字节数）；只做三件事: 节流(最快 [MIN_INTERVAL_MS] 交一次样本)+平滑(EMA, 新样本权重 0.4)+归零(字节倒退时清历史, 绝不负速率)。
 * 回调跑在**调用线程**，由上层负责切主线程
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
