package com.phonepctransfer.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * HEVC / H.264 **硬件**编码器（Surface 输入，零拷贝）（计划 §5.2）。
 *
 * 取帧方式：`dequeueOutputBuffer` → 按 `BUFFER_FLAG_CODEC_CONFIG` 区分
 * 「配置帧（VPS/SPS/PPS）」与「普通帧」，首帧 IDR 标 `KEYFRAME` 位。
 *
 * ⚠ 输出是 **Annex-B 裸流**（MediaCodec 编码器的默认输出格式），
 * 正好对上 PC 端 `ffmpeg -f hevc -i pipe:0` 的输入要求，**不需要任何转换**。
 */
class VideoEncoder(
    /** 每编出一帧回调（在 pp-video-enc 线程）。isConfig=true 时应发 CONFIG 帧。 */
    private val onFrame: (data: ByteArray, len: Int, isConfig: Boolean, isKeyframe: Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "PP-VideoEnc"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var surface: Surface? = null

    @Volatile
    var mime: String = VideoCodecs.MIME_HEVC
        private set

    /** 参数集是否已经作为 CONFIG 帧发出去过（避免重复发） */
    @Volatile
    private var configSent = false

    /** [resendConfig] 的待办标志：真正发出在 [drainLoop]（编码器线程）里，理由见 [resendConfig] */
    @Volatile
    private var pendingConfigResend = false

    val width: Int get() = w
    val height: Int get() = h

    private var w = 0
    private var h = 0

    /**
     * 启动编码器。
     * @return 输入 Surface；null = 失败（调用方应禁用视频）
     */
    fun start(mimeWanted: String, width: Int, height: Int, fps: Int, bitrate: Int): Surface? {
        stop()
        val name = VideoCodecs.findHardwareEncoder(mimeWanted)
        if (name == null) {
            onError("找不到 ${VideoCodecs.name(mimeWanted)} 硬件编码器")
            return null
        }
        return try {
            mime = mimeWanted
            w = width
            h = height

            val fmt = MediaFormat.createVideoFormat(mimeWanted, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // GOP = 1s：丢帧后能在 1s 内通过 IDR 恢复
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // 低延迟实时通话**必须 CBR**：VBR 峰值会打爆共用 writeLock
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                if (Build.VERSION.SDK_INT >= 30) {
                    // KEY_LOW_LATENCY 是 API 30 才有的字段，低版本引用会 NoSuchFieldError
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime
                }
            }

            val c = MediaCodec.createByCodecName(name)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val s = c.createInputSurface()
            surface = s
            codec = c
            configSent = false
            pendingConfigResend = false
            c.start()
            running = true
            thread = Thread({ drainLoop(c) }, "pp-video-enc").apply { isDaemon = true }.also { it.start() }
            Log.i(TAG, "编码器启动：$name（${VideoCodecs.name(mimeWanted)} ${width}x$height@$fps ${bitrate / 1000}k CBR）")
            s
        } catch (t: Throwable) {
            onError("编码器启动失败（$name）: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { codec?.release() }
            codec = null
            running = false
            null
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buf = ByteArray(256 * 1024)
        try {
            while (running) {
                // 对端刚换代/刚接入 ⇒ 在下一个出帧节拍里补发一份参数集（发帧动作留在编码器线程，见 resendConfig）
                if (pendingConfigResend) {
                    pendingConfigResend = false
                    configSent = false
                    emitCsdFromFormat(c.outputFormat, resend = true)
                }
                val idx = try {
                    c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                } catch (t: Throwable) {
                    if (running) onError("编码器取帧异常: ${t.message}")
                    break
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 兜底：万一编码器没吐 CODEC_CONFIG 缓冲，就从输出格式里把 csd 拿出来发一次
                        emitCsdFromFormat(c.outputFormat)
                    }
                    idx >= 0 -> {
                        if (info.size > 0) {
                            if (buf.size < info.size) buf = ByteArray(info.size)
                            val bb: ByteBuffer? = c.getOutputBuffer(idx)
                            if (bb != null) {
                                bb.position(info.offset)
                                bb.limit(info.offset + info.size)
                                bb.get(buf, 0, info.size)
                                val isConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val isKey =
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                if (isConfig) {
                                    configSent = true
                                    onFrame(buf.copyOf(info.size), info.size, true, false)
                                } else {
                                    onFrame(buf, info.size, false, isKey)
                                }
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) onError("编码输出循环异常: ${t.message}")
        }
    }

    private fun emitCsdFromFormat(fmt: MediaFormat, resend: Boolean = false) {
        if (configSent) return
        val out = java.io.ByteArrayOutputStream(256)
        for (key in listOf("csd-0", "csd-1")) {
            val bb = runCatching { fmt.getByteBuffer(key) }.getOrNull() ?: continue
            if (!bb.hasRemaining()) continue
            val arr = ByteArray(bb.remaining())
            bb.position(0)
            bb.get(arr)
            out.write(arr)
        }
        val data = out.toByteArray()
        if (data.isNotEmpty()) {
            configSent = true
            Log.i(TAG, "从输出格式取到参数集 ${data.size}B，作为 CONFIG 帧发送${if (resend) "（对端要求重发）" else ""}")
            onFrame(data, data.size, true, false)
        } else if (resend) {
            // 只在「对端要求重发」这条路上报警：起播时吐不出 csd 是合法的（编码器会自己给 CODEC_CONFIG 缓冲）
            Log.w(TAG, "重发参数集失败：输出格式里没有 csd-0/csd-1")
        }
    }

    /** 档位切换（一期只改码率 → 一次 setParameters，**不 stop、不重建**） */
    fun setBitrate(bitrate: Int) {
        val c = codec ?: return
        runCatching {
            val b = Bundle()
            b.putInt(MediaFormat.KEY_BIT_RATE, bitrate)
            c.setParameters(b)
            Log.i(TAG, "码率切换 → ${bitrate / 1000}k")
        }.onFailure { onError("setBitrate 失败: ${it.message}") }
    }

    /** 强制下一个编码帧为 IDR（切档 / 新接入时对齐用） */
    fun requestKeyFrame() {
        val c = codec ?: return
        runCatching {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
            c.setParameters(b)
        }.onFailure { Log.w(TAG, "requestKeyFrame 失败: ${it.message}") }
    }

    /**
     * 请求**重发一份参数集**（对端解码器刚换代/刚接入时用，与 [requestKeyFrame] 配对）。
     *
     * ⚠ 参数集每个编码器实例只发一次（[configSent]），而 PC 的解码进程既晚于通话起步、
     *   又会随每次换尺寸重建 ⇒ 新进程从来没有解析到过 VPS/SPS/PPS，日志表现为一整段
     *   `[hevc] PPS id out of range: 0`，画面灰白后卡死。光要 IDR 不够：那台机的编码器
     *   不吐 CODEC_CONFIG 输出缓冲、也不把参数集内联进 IDR（08:37:59 那通实测），必须再发一次 csd。
     * ⚠ 只置标志、由 [drainLoop]（编码器线程）真正发出 —— 本方法可能在**网络读线程**上被调，
     *   而发送侧的 `seq` 自增没有跨线程保护，从读线程直接发帧会造出重复 seq（收端按单调去重丢帧）。
     */
    fun resendConfig() {
        pendingConfigResend = true
    }

    fun stop() {
        running = false
        val c = codec ?: return
        codec = null
        runCatching { thread?.join(300) }
        thread = null
        runCatching { c.stop() }
        runCatching { c.release() }
        runCatching { surface?.release() }
        surface = null
        Log.i(TAG, "编码器已停止")
    }
}
