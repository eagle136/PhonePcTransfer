package com.phonepctransfer.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 传输协议 v2:帧 = [4B 大端 uint32 长度][payload];长度字段最高位 0 = JSON 控制帧,1 = 二进制帧(低 31 位是 payload 长度)。
 * 文件分片为 1MB 原始二进制(v1 的 256KB+base64+JSON 带来 33% 膨胀);断点续传 = file_query/file_resume;文件夹传输 = dir_meta 声明目录 + file_meta 带 dirId/relPath。
 * 与 PC 端 com.phonepctransfer.pc.Protocol.java 严格一致
 */
object Protocol {

    /** v3：分片 1MB→4MB（传输提速一期）。两端必须同值，靠 hello.chunk 校验，不一致直接拒连 */
    const val PROTO_VER = 4
    const val DEFAULT_TCP_PORT = 28765
    const val DEFAULT_UDP_PORT = 28766

    /** JSON 控制帧上限 8 MB（载荷长度字段也用它约束二进制帧） */
    const val MAX_FRAME = 8 * 1024 * 1024

    /**
     * 文件分片大小（原始字节，二进制帧）。
     * <p>v3 起 4MB：帧数降到 1/4，帧头/刷缓冲/syscall 次数同步下降，长肥管道下单帧窗口利用率更高。
     * ⚠️ 该值参与接收端 offset 换算（{@code seq * CHUNK_SIZE}），收发两端必须一致，改动等于换协议。
     * <p>注意：**这不是线上每帧的实际大小**。单文件发送走 [AdaptiveFrame]（1M 起步、8M 封顶），
     * 这里的 4MB 只是「续传偏移/多通道单元」的换算单位与 hello 兼容闸门。
     */
    const val CHUNK_SIZE = 4 * 1024 * 1024

    /** 续传/传输校验块：32MB（P4-1，双端同值） */
    const val BLOCK_SIZE = 32 * 1024 * 1024

    /** 单条文本上限（16k 字符，P4-6；不做送达验证） */
    const val MAX_TEXT_CHARS = 16384

    fun limitText(s: String?): String {
        val t = s ?: return ""
        return if (t.length > MAX_TEXT_CHARS) t.substring(0, MAX_TEXT_CHARS) else t
    }

    /** 对端可控时间戳只允许「不晚于本机时钟 5 分钟」；非法/未来值回落到 now（防止未来消息永久占住排序与裁剪） */
    fun saneTs(ts: Long): Long {
        val now = System.currentTimeMillis()
        return if (ts <= 0L || ts > now + 5 * 60_000L) now else ts
    }

    /**
     * 接收端缓冲池单块大小：每块 8MB（2026-11-22 由 4MB 抬到 8MB，与发送端自适应顶档齐平），
     * 3 块轮转（见 LinkReceive.RX_BUF_COUNT），攒满 1MB 即交落盘线程。
     * <p>必须 ≥ 发送端最大帧，否则大帧走不进「直接读进轮转缓冲」的零拷贝路径，退化成每帧现分配。
     * ⚠️ 该值不进 hello 校验，单端改了不会拒连，只会让对端退化成逐帧分配 —— 所以仍要双端一起改。
     */
    const val RX_BUF_SIZE = 8 * 1024 * 1024

    /** 二进制帧标志位（长度字段最高位） */
    const val BIN_FLAG = 0x80000000.toInt()

    /** 二进制帧子类型：文件分片 */
    const val BIN_CHUNK: Byte = 0x01

    /** 二进制帧子类型：语音通话音频帧（Opus 编码，0.2s 一帧） */
    const val BIN_AUDIO: Byte = 0x02

    /**
     * 二进制帧子类型：视频帧（HEVC / H.264 Annex-B 裸流，**一包 = 恰好一个访问单元**）。
     * ⚠ 只有对端 hello 广播了 `vid=1` 才允许发（见 Link.peerVidCap）；老版本对端遇到 0x03 会抛 IOException 断链
     */
    const val BIN_VIDEO: Byte = 0x03

    /** 视频帧 flag：该帧为 IDR（接收端据此丢帧对齐 / 快速恢复） */
    const val VIDEO_FLAG_KEYFRAME = 0x01

    /** 视频帧 flag：载荷是编解码配置（VPS/SPS/PPS），收到即重建解码器 */
    const val VIDEO_FLAG_CONFIG = 0x02

    /**
     * 视频单帧硬闸门 128 KB：视频/音频/文件分片共用同一条会话 writeLock,超大 IDR 会让 write() 长时间独占写锁挡住一切,
     * 超限直接丢弃并记日志
     */
    const val MAX_VIDEO_FRAME = 128 * 1024

    // ==================== 媒体数据报线格式（任务单 3.1 / 方案 §4.4）====================

    /**
     * 数据报版本，占 [0] 的高 4 位；低 4 位是流类型。
     * 媒体通道**自己的**数据报：UDP 档原样发，媒体 TCP 档发同一份报（字节流上怎么框帧归 2.6）；带内档不用它（仍是 BIN_AUDIO/BIN_VIDEO 帧制, I3 例外条款）。
     * ⚠ 不新增任何 `BIN_*` 主 TCP 二进制子类型（老端读帧遇到未知子类型会直接 IOException 断链, I8）
     */
    const val DG_VER = 1

    /** [0] = (版本 shl 4) or 流类型 */
    const val DG_VER_SHIFT = 4
    const val DG_TYPE_MASK = 0x0F

    /** [0] 低 4 位：流类型。四种，与 §4.4 的表一一对应，不多不少 */
    const val DG_TYPE_AUDIO = 1
    const val DG_TYPE_VIDEO_SLICE = 2
    const val DG_TYPE_CTRL = 3
    const val DG_TYPE_FEEDBACK = 4

    /**
     * ctrl 子类型：载荷**首字节**（§4.4 两个候选位置中定死取后者 —— [0] 低 4 位已被四个流类型占着,
     * 再塞五种 ctrl 就得挤掉类型位）
     */
    const val CT_PROBE = 1
    const val CT_KEYFRAME_REQ = 2
    const val CT_STOP_RESUME = 3
    const val CT_LEVEL_SYNC = 4
    const val CT_STATE_SNAPSHOT = 5

    /** 固定头 8B：[0] ver/type｜[1..2] streamSeq｜[3] flags｜[4..5] sliceIdx｜[6..7] sliceCount */
    const val DG_HEADER_LEN = 8

    /**
     * 头后 4B ts = 发送端**通话相对**毫秒（`util/CallClock.elapsedMs()`，起呼=0），照带内帧的 4B 大端先例；
     * 两端各自零点，接收端**不得**拿它与本端墙钟相减（S1 / 任务单 3.8 的两段式帧龄）
     */
    const val DG_TS_LEN = 4

    /** 一份报的固定开销（不含 payload）= 8 + 4 */
    const val DG_OVERHEAD = DG_HEADER_LEN + DG_TS_LEN

    /** ctrl **整报**上限 64B（§4.4）；音频/视频/反馈报不受它约束（它们的上限是 3.2 的 1200B） */
    const val DG_CTRL_MAX_REPORT = 64

    /**
     * [3] flags：**只有这三**位有定义；bit0/bit1 位序照带内视频帧的 [VIDEO_FLAG_*]（生产者迁过来不用重映射）,
     * bit2 是新加的"帧内最后一片"
     */
    const val DG_FLAG_IDR = 0x01
    const val DG_FLAG_CONFIG = 0x02
    const val DG_FLAG_LAST_SLICE = 0x04

    /** flags 已定义位（bit3..bit7 保留：收到非 0 不报错，但不得当语义用）= 0x01 or 0x02 or 0x04 */
    const val DG_FLAG_ALL = 0x07

    /** 音频 / ctrl / feedback **不分片**：sliceIdx / sliceCount 恒 0 / 1（§4.4，编解码两侧都硬校验） */
    const val DG_SLICE_IDX_NONE = 0
    const val DG_SLICE_COUNT_NONE = 1

    /**
     * ctrl/state_snapshot 的 payload **定长 14B**（不含子类型首字节）：
     * epoch u16 | seq u32 | ownerBits u8 | videoLevel u8 | videoBrKbps u16 |
     * videoFps u8 | audioBrKbps u8 | flags u16
     */
    const val DG_SNAPSHOT_LEN = 14

    /** 快照那份 ctrl 报的固定正文：1B 子类型 + 14B ⇒ 整报 = 12 + 15 = 27B，远在 64B 以内 */
    const val DG_SNAPSHOT_BODY = 1 + DG_SNAPSHOT_LEN

    const val SNAP_OFF_EPOCH = 0         // u16 本次媒体会话号
    const val SNAP_OFF_SEQ = 2           // u32 会话内单调递增
    const val SNAP_OFF_OWNER = 6         // u8
    const val SNAP_OFF_VIDEO_LEVEL = 7   // u8
    const val SNAP_OFF_VIDEO_BR = 8      // u16 kbps
    const val SNAP_OFF_VIDEO_FPS = 10    // u8
    const val SNAP_OFF_AUDIO_BR = 11     // u8 kbps
    const val SNAP_OFF_FLAGS = 12        // u16

    /**
     * ownerBits（[6]）：这份快照由哪一组 owner 决策（§3-D14-1 的两组各占一位，其余保留）。
     * ⚠ 这里只定**位含义**；判权规则（非 owner 写来的同名字段忽略并计 ctrlOwnerViolation）在 3.13。
     */
    const val OWNER_BIT_PUSH = 0x01
    const val OWNER_BIT_RECV = 0x02

    /**
     * 快照 flags（[12..13] 低 7 位）：**位序 = §4.4 括号里的书写序，一位不多一位不少**
     *（aggregated/camOn/muted/sendingPaused/wantVideo/needKeyframe/bg；前四位是推流端 owner 组、后三位是收流端组，顺序照 D14-1 字段表）
     */
    const val SNAP_FLAG_AGGREGATED = 0x0001
    const val SNAP_FLAG_CAM_ON = 0x0002
    const val SNAP_FLAG_MUTED = 0x0004
    const val SNAP_FLAG_SENDING_PAUSED = 0x0008
    const val SNAP_FLAG_WANT_VIDEO = 0x0010
    const val SNAP_FLAG_NEED_KEYFRAME = 0x0020
    const val SNAP_FLAG_BG = 0x0040

    /** flags 已定义位掩码（bit7..bit15 保留）= 上面七个的按位或 */
    const val SNAP_FLAG_ALL = 0x007F

    const val APP_TAG = "PPTransfer"

    // ==================== 帧编解码 ====================

    /**
     * 写一帧 JSON。
     * <p>**不加全局锁**（原 `@Synchronized` 锁的是 Protocol 单例，跨会话串行，一个半死连接会拖住
     * 所有设备的文本/ack/ping）：写与写的互斥已由调用方 [Link.Session.writeLock] 按会话保证，
     * 本函数只操作传入的 out，不碰任何共享可变状态（`chunkHeader` 用 ThreadLocal）。
     */
    @Throws(IOException::class)
    fun write(out: OutputStream, msg: JSONObject) {
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_FRAME) throw IOException("帧过大: ${body.size}")
        val len = byteArrayOf(
            (body.size ushr 24).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte()
        )
        out.write(len)
        out.write(body)
        out.flush()
    }

    /**
     * 分片帧头缓存（每线程一份）：同一 mid 的 6 字节前缀（子类型/midLen/mid）只算一次，
     * 之后每帧只覆盖 4B 帧长与 4B seq —— 不再每帧 new 两个数组（原实现每帧 2 次分配 + 3 次 write）。
     * <p>用 ThreadLocal 而不是全局缓存：写线程之间不共享可变数组，免加锁、免串包。
     */
    private class ChunkHead {
        var mid: String = ""
        var buf: ByteArray = ByteArray(0)
        var seqOff: Int = 0
    }

    private val chunkHeadTl = ThreadLocal.withInitial { ChunkHead() }

    /**
     * 生成一块分片帧头（复用线程私有缓冲）：[4B 帧长][1B 子类型][1B midLen][mid][4B seq]。
     * 普通发送路径由 [writeChunk] 直接写；零拷贝路径（P1-4）拿它走 SocketChannel 一次写出。
     */
    @Throws(IOException::class)
    fun chunkHeader(mid: String, seq: Int, len: Int): ByteArray {
        val ch = chunkHeadTl.get()
        var buf = ch.buf
        if (ch.mid != mid) {
            val midB = mid.toByteArray(Charsets.UTF_8)
            if (midB.size > 255) throw IOException("mid 过长")
            ch.mid = mid
            // [0..3] 帧长 | [4] 子类型 | [5] midLen | [6..] mid | [seqOff..seqOff+3] seq
            buf = ByteArray(10 + midB.size)
            buf[4] = BIN_CHUNK
            buf[5] = midB.size.toByte()
            System.arraycopy(midB, 0, buf, 6, midB.size)
            ch.buf = buf
            ch.seqOff = 6 + midB.size
        }
        val payloadLen = (buf.size - 4) + len
        if (payloadLen > MAX_FRAME) throw IOException("分片帧过大: $payloadLen")
        val fl = BIN_FLAG or payloadLen
        buf[0] = (fl ushr 24).toByte()
        buf[1] = (fl ushr 16).toByte()
        buf[2] = (fl ushr 8).toByte()
        buf[3] = fl.toByte()
        val so = ch.seqOff
        buf[so] = (seq ushr 24).toByte()
        buf[so + 1] = (seq ushr 16).toByte()
        buf[so + 2] = (seq ushr 8).toByte()
        buf[so + 3] = seq.toByte()
        return buf
    }

    /**
     * 写一个二进制文件分片帧。
     * <p>线上字节序列与原实现逐字节相同（4B 帧长｜子类型｜midLen｜mid｜seq｜载荷），
     * 只是把「帧长 + 帧头」合并成一次 write，并去掉逐帧 flush：
     * 大载荷（≥ 缓冲）走 BufferedOutputStream 的直写分支，小尾块才需要 flush 把缓冲推出去。
     */
    @Throws(IOException::class)
    fun writeChunk(out: OutputStream, mid: String, seq: Int, data: ByteArray, off: Int, len: Int) {
        val buf = chunkHeader(mid, seq, len)
        out.write(buf)
        if (len > 0) out.write(data, off, len)
        // 尾块（< 8KB 会话缓冲）留在缓冲里不会自己出去；大块已直写，无需再刷
        if (len < 8192) out.flush()
    }

    /** 一帧：JSON 控制帧、二进制文件分片帧、或二进制语音帧 */
    sealed class Frame {
        class Json(val j: JSONObject) : Frame()
        /**
         * data 为分片字节；有效范围 [off, off+len)。
         * [direct] = true 表示载荷由读循环**直接读进** [data]（接收端 4MB 轮转缓冲），
         * 已省掉「先分配到临时数组再 memcpy 进缓冲」的一次分配 + 一次拷贝。
         */
        class Chunk(
            val mid: String, val seq: Int, val data: ByteArray, val len: Int,
            val off: Int = 0, val direct: Boolean = false
        ) : Frame()
        /**
         * 语音音频帧：seq 单调递增；ts 为发送端**通话相对**时间戳（对端起呼=0 的 ms，4B 无符号；
         * 抖动缓冲对时/帧龄用。两端各自零点，接收端不拿它与本端墙钟相减）。
         */
        class Audio(val seq: Int, val ts: Long, val data: ByteArray, val len: Int) : Frame()
        /**
         * 视频帧：seq 单调递增（接收侧去重），ts 为发送端**通话相对**毫秒（与同通话音频帧同一零点，
         * A/V 时间轴对齐），flag 见 VIDEO_FLAG_*，data 为一个完整访问单元（AU）的 Annex-B 裸流。
         */
        class Video(
            val seq: Int, val ts: Long, val flag: Int, val data: ByteArray, val len: Int
        ) : Frame() {
            val keyframe: Boolean get() = (flag and VIDEO_FLAG_KEYFRAME) != 0
            val config: Boolean get() = (flag and VIDEO_FLAG_CONFIG) != 0
        }
    }

    /** 分片载荷落点：接收端在拿到分片头后指定「直接写进哪块缓冲的哪个偏移」 */
    class ChunkSink(val buf: ByteArray, val off: Int)

    /** 把 len 字节直接读进 buf 的 off 处（不分配、不二次拷贝） */
    @Throws(IOException::class)
    private fun readFullyInto(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var at = off
        var remain = len
        while (remain > 0) {
            val n = input.read(buf, at, remain)
            if (n < 0) throw EOFException()
            at += n
            remain -= n
        }
    }

    /** @return 帧内容；流正常结束返回 null */
    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame? = readFrameInto(input) { _, _, _ -> null }

    /**
     * 带直读落点的帧读取：解析到文件分片头时先问 [resolve]「这块要落到哪」。
     * <p>[resolve] 返回非空 → 载荷不分配临时数组，直接读进指定缓冲（快路径）；
     * 返回 null → 退回原逻辑（分配 ByteArray），保证乱序/未知 mid/跨缓冲等异常路径行为不变。
     */
    @Throws(IOException::class)
    fun readFrameInto(
        input: InputStream,
        resolve: (mid: String, seq: Int, len: Int) -> ChunkSink?
    ): Frame? {
        val dis = if (input is DataInputStream) input else DataInputStream(input)
        val v = try {
            dis.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (v == BIN_FLAG) throw IOException("非法帧长度: 0")
        val binary = (v and BIN_FLAG) != 0
        val n = v and (BIN_FLAG - 1)
        if (n <= 0 || n > MAX_FRAME) throw IOException("非法帧长度: $v")
        if (!binary) {
            val buf = ByteArray(n)
            dis.readFully(buf)
            return Frame.Json(JSONObject(String(buf, Charsets.UTF_8)))
        }
        // 二进制帧：先读小头部拿到子类型，再分派
        val sub = dis.readByte()
        return when (sub) {
            BIN_CHUNK -> {
                val midLen = dis.readByte().toInt() and 0xFF
                val midB = ByteArray(midLen)
                dis.readFully(midB)
                val seq = dis.readInt()
                val dataLen = n - 2 - midLen - 4
                if (dataLen < 0) throw IOException("二进制帧长度不符: $n")
                val mid = String(midB, Charsets.UTF_8)
                val sink = resolve(mid, seq, dataLen)
                if (sink != null) {
                    if (dataLen > 0) readFullyInto(dis, sink.buf, sink.off, dataLen)
                    Frame.Chunk(mid, seq, sink.buf, dataLen, sink.off, true)
                } else {
                    val data = ByteArray(dataLen)
                    TxStats.alloc(1)
                    if (dataLen > 0) dis.readFully(data)
                    Frame.Chunk(mid, seq, data, dataLen)
                }
            }
            BIN_AUDIO -> {
                val seq = dis.readInt()
                val ts = dis.readInt().toLong() and 0xFFFFFFFFL
                val dataLen = n - 1 - 8
                if (dataLen < 0) throw IOException("音频帧长度不符: $n")
                val data = ByteArray(dataLen)
                if (dataLen > 0) dis.readFully(data)
                Frame.Audio(seq, ts, data, dataLen)
            }
            BIN_VIDEO -> {
                // 1B sub + 4B seq + 4B ts + 1B flag + N(AU)
                val seq = dis.readInt()
                val ts = dis.readInt().toLong() and 0xFFFFFFFFL
                val flag = dis.readByte().toInt() and 0xFF
                val dataLen = n - 1 - 8 - 1
                if (dataLen < 0) throw IOException("视频帧长度不符: $n")
                val data = ByteArray(dataLen)
                if (dataLen > 0) dis.readFully(data)
                Frame.Video(seq, ts, flag, data, dataLen)
            }
            else -> throw IOException("未知二进制帧子类型: $sub")
        }
    }

    // ==================== 消息构造 ====================

    fun msg(type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("ts", System.currentTimeMillis())
        .put("v", PROTO_VER)

    fun hello(
        id: String, name: String, model: String,
        vid: Boolean = false, cam: Boolean = false, vcodec: List<String> = emptyList(),
        lanes: Int = 1, lane: Int = 0, tok: String = ""
    ): JSONObject = msg("hello")
        .put("id", id)
        .put("name", name)
        .put("model", model)
        .put("ver", PROTO_VER)
        // 分片大小协商：老端不带该字段 → 按 1MB 处理，onHello 直接拒连，避免错位写坏文件
        .put("chunk", CHUNK_SIZE)
        // 2 期：多通道能力协商。lanes = 本端最多能同时用的通道数（1 = 不支持）；
        // lane = 本条连接的角色（0 = 主连接，>0 = 纯数据通道）。老端忽略这两个字段 → 自动单通道。
        .put("lanes", lanes)
        .put("lane", lane)
        // ===== 能力协商（老端不认识这些字段，直接忽略；本端凭对端的 vid 决定敢不敢发视频帧）=====
        .put("vid", vid)           // 本端可**接收**视频
        .put("cam", cam)           // 本端有摄像头、可**发送**视频
        .put("vcodec", org.json.JSONArray(vcodec))  // 本端可**解码**的编码列表
        .put("hs", true)           // 本端支持历史同步（hsync，v2.3 起；老端忽略）
        .put("hd", true)           // 本端支持「消息删除同步」（msg_del，v2.4 起；老端忽略）
        .put("dl", true)           // 本端支持「文件夹完整性校验」（dir_meta.list/dir_end/dir_missing，v2.6 起；老端忽略）
        .put("dm", true)           // 本端支持「数据流量确认」（data_ask/data_ans，v2.7 起；老端忽略）
        // P4-1 32MB 分块校验 / P4-5 配对能力；老端不带 → onHello 拒连并提示同时升级
        .put("blk", BLOCK_SIZE)
        .put("pr", true)
        .put("tok", tok)

    // ==================== 数据流量确认（v2.7，仅双方 dm=1 时使用）====================

    /**
     * 发起方 → 对端：**用数据流量做这件事，你同意吗？**（两端各自网络环境独立：发起方在 WiFi 不代表接收方不在数据网络上;
     * 对端在非数据网络下**自动应答同意**，不打扰用户）。kind=file/voice/video；name 为文件（夹）名，通话为空
     */
    fun dataAsk(mid: String, kind: String, name: String = "", size: Long = 0L): JSONObject =
        msg("data_ask")
            .put("mid", mid)
            .put("kind", kind)
            .put("name", name)
            .put("size", size)

    /** 对端 → 发起方：是否同意本次用数据流量接收（false = 拒绝，发起方应中止并如实提示） */
    fun dataAns(mid: String, ok: Boolean): JSONObject = msg("data_ans")
        .put("mid", mid)
        .put("ok", ok)

    // ==================== 消息删除同步（v2.4，仅双方 hd=1 时使用）====================

    /**
     * 通知对端「这些消息已在本机删除」，对端同步移除（只对文本/图片发；
     * 文件与文件夹的删除只影响本机，不发此帧）。对端收到后**不再回传**，无回环。
     */
    fun msgDel(mids: List<String>): JSONObject = msg("msg_del")
        .put("mids", org.json.JSONArray(mids))

    // ==================== 历史同步（v2.3，仅双方 hs=1 时使用）====================

    /** 握手后互发：本机持有的该对端消息 mid 清单（最近 HSYNC_SCAN 条，TEXT + 图片 FILE） */
    fun hsyncMids(mids: List<String>): JSONObject = msg("hsync_mids")
        .put("mids", org.json.JSONArray(mids))

    /** 请求对端补发缺失消息的内容（mid 清单） */
    fun hsyncPull(mids: List<String>): JSONObject = msg("hsync_pull")
        .put("mids", org.json.JSONArray(mids))

    /**
     * 补发文本消息内容。dir 是**对端视角**的方向（本端持有的 SELF 翻转为 PEER，反之亦然）。
     * 文件不走这里 —— 沿用现有 file_meta/chunks/file_done 管线按**原 mid** 重发。
     */
    fun hsyncMsg(items: org.json.JSONArray): JSONObject = msg("hsync_msg")
        .put("items", items)

    fun ping(): JSONObject = msg("ping")

    fun pong(): JSONObject = msg("pong")

    /** 拒绝连接：本机已有活跃会话（holder=正在会话的对端名），请求方应退避排队 */
    fun busy(holder: String): JSONObject = msg("busy")
        .put("holder", holder)

    /**
     * 设备忙（单任务槽，v3.x）：本机正在传 [holder] 描述的任务，拒绝本次 [mid]/[name]。
     * 带 mid 的 busy 与老格式（只有 holder）区分开：新端收到后只把该条目留在待发队列，
     * 等对端发 `sync_req`（任务槽释放）再自动补发，**不关会话**。
     * kind: file / dir / member（member=文件夹成员，接收端按目录 mid 回绝）。
     */
    fun busy(mid: String, name: String, holder: String, kind: String = "file"): JSONObject = msg("busy")
        .put("mid", mid)
        .put("name", name)
        .put("holder", holder)
        .put("kind", kind)

    fun text(mid: String, text: String): JSONObject = msg("text")
        .put("mid", mid)
        .put("text", text)

    fun clip(mid: String, text: String, sha1: String): JSONObject = msg("clip")
        .put("mid", mid)
        .put("text", text)
        .put("sha1", sha1)

    fun clipNotify(len: Int, sha1: String, preview: String): JSONObject = msg("clip_notify")
        .put("len", len)
        .put("sha1", sha1)
        .put("preview", preview)

    fun clipPull(): JSONObject = msg("clip_pull")

    fun clipState(text: String, sha1: String): JSONObject = msg("clip_state")
        .put("text", text)
        .put("sha1", sha1)

    /**
     * 文件开始。offset > 0 表示续传（从该字节起继续）；relPath 非空表示文件夹成员；
     * dirId 指向所属目录气泡的 mid。
     */
    fun fileMeta(
        mid: String, name: String, size: Long, mime: String, sha256: String,
        offset: Long = 0L, relPath: String = "", dirId: String = ""
    ): JSONObject {
        val m = msg("file_meta")
            .put("mid", mid)
            .put("name", name)
            .put("size", size)
            .put("mime", mime)
            .put("sha256", sha256)
        if (offset > 0) m.put("offset", offset)
        if (relPath.isNotEmpty()) m.put("relPath", relPath)
        if (dirId.isNotEmpty()) m.put("dirId", dirId)
        return m
    }

    @JvmOverloads
    fun fileChunk(mid: String, seq: Int, b64: String): JSONObject = msg("file_chunk")
        .put("mid", mid)
        .put("seq", seq)
        .put("data", b64)

    fun fileDone(mid: String, sha256: String): JSONObject = msg("file_done")
        .put("mid", mid)
        .put("sha256", sha256)

    // ==================== 多通道传输（2 期，v4）====================

    /**
     * 多通道开传声明：**必须在 file_meta 之前**发，接收端据此把该文件的接收通道建成
     * 「3 槽重排环」而不是 1 期的双缓冲顺序缓冲。
     * unit = 单元字节数（当前 = CHUNK_SIZE）；lanes = 本次实际并发的通道数。
     */
    fun xeBegin(mid: String, unit: Int, lanes: Int, startAt: Long): JSONObject =
        msg("xe_begin")
            .put("mid", mid)
            .put("unit", unit)
            .put("lanes", lanes)
            .put("startAt", startAt)

    /** 多通道收尾：所有单元已发完（file_done 紧跟其后） */
    fun xeEnd(mid: String): JSONObject = msg("xe_end").put("mid", mid)

    /**
     * 接收上下文就绪应答：接收端已把该 mid 的 Rx（多通道时含 3 槽重排环）建好并登记进 rxMap，
     * 发送端收到后才允许起数据通道发分片；否则先到的分片会因「未知 mid」被丢弃，
     * 留下补不齐的单元缺口（2 期首测 1GB 卡死 30s 的根因）。
     */
    fun xeReady(mid: String): JSONObject = msg("xe_ready").put("mid", mid)

    /** 数据通道就绪应答（对端挂载成功后回发，拨号方据此确认该 lane 可用） */
    fun laneOk(lane: Int): JSONObject = msg("lane_ok").put("lane", lane)

    /**
     * 发送方取消某个文件/文件夹传输。
     * 接收端收到后中止接收、删除半成品文件，并把该条消息从聊天界面移除（两端同步不显示）。
     */
    fun fileCancel(mid: String): JSONObject = msg("file_cancel")
        .put("mid", mid)

    /** 断点续传询问：发送方开传前问对端有没有半成品 */
    fun fileQuery(mid: String, name: String, size: Long): JSONObject = msg("file_query")
        .put("mid", mid)
        .put("name", name)
        .put("size", size)

    /**
     * 断点续传应答。ok=true 时 offset 为对端已有字节数（对齐分片边界），
     * sha256 为前缀摘要（对端自行维护/重算，可能为空）；ok=false 从头传。
     */
    fun fileResume(mid: String, ok: Boolean, offset: Long, sha256: String): JSONObject =
        fileResume(mid, ok, offset, sha256, false)

    /**
     * 断点续传应答（大小校验版）。
     * needBlk=true：对端命中了磁盘上的半成品，offset 已向下对齐到 32MB 边界；
     * 发送端发 [blkVerify] 请求按块核对大小，等 [blkAck] 回到最终偏移后再发数据。
     */
    fun fileResume(
        mid: String, ok: Boolean, offset: Long, sha256: String, needBlk: Boolean
    ): JSONObject = msg("file_resume")
        .put("mid", mid)
        .put("ok", ok)
        .put("offset", offset)
        .put("sha256", sha256)
        .put("needBlk", needBlk)

    // ==================== 32MB 分块大小校验（不做 hash） ====================

    /**
     * 发送端 → 接收端：发起前缀大小校验——按 32MB 一块核对 [0, offset) 是否都在盘上，
     * 接收端截断到最后一个完整块边界、建好续传通道后回 [blkAck]（带实际核验偏移）。
     * offset=0 表示放弃续传、从头接收（接收端清掉半成品）。
     */
    fun blkVerify(mid: String, offset: Long): JSONObject = msg("blk_verify")
        .put("mid", mid)
        .put("offset", offset)

    /**
     * 接收端 → 发送端：大小校验结果。ok=false 请从 0 重来；
     * offset = 实际核验通过的续传起点（可能小于请求值：盘上字节不够时退到最后一个完整块边界）。
     */
    fun blkAck(mid: String, idx: Int, ok: Boolean, offset: Long): JSONObject = msg("blk_ack")
        .put("mid", mid)
        .put("idx", idx)
        .put("ok", ok)
        .put("offset", offset)

    // ==================== P4-5 设备配对（6 位码）====================

    /** 接收方 → 发起方：未配对/ token 不匹配，请在发起方输入本机显示的 6 位码 */
    fun pairNeed(ttlMs: Long): JSONObject = msg("pair_need").put("ttl", ttlMs)

    /** 发起方 → 接收方：用户输入的 6 位配对码 */
    fun pairSubmit(code: String): JSONObject = msg("pair_submit").put("code", code)

    /** 接收方 → 发起方：配对成功（token 为空 = 仅确认既有 hello.tok 校验通过） */
    fun pairOk(token: String): JSONObject = msg("pair_ok").put("token", token)

    /** 接收方 → 发起方：配对码错误，left=剩余次数 */
    fun pairFail(left: Int): JSONObject = msg("pair_fail").put("left", left)

    /** 任一端 → 对端：用户点了弹窗「取消」，请对端也把配对弹窗关掉（连接保留，未配对） */
    fun pairCancel(): JSONObject = msg("pair_cancel")

    /** 文件夹传输开始：name=文件夹名，fileCount=成员数，totalSize=总字节。
     *  先给清单再发数据：list=成员相对路径（目录结构），sizes=与 list 逐项对应的文件字节数，
     *  接收端按清单存放并核对每个成员的大小 */
    fun dirMeta(
        mid: String, name: String, fileCount: Int, totalSize: Long,
        emptyDirs: List<String> = emptyList(),
        /** 成员相对路径清单（接收端据此核对缺失）。为空 = 不带清单，退化为「只按数量核对」 */
        list: List<String> = emptyList(),
        /** 因深度/数量上限被跳过的分支数（>0 时对端明确提示，不再无声消失） */
        skipped: Int = 0,
        /** 成员大小清单（与 list 逐项对应；为空 = 不带大小，只按路径核对） */
        sizes: List<Long> = emptyList()
    ): JSONObject {
        val m = msg("dir_meta")
            .put("mid", mid)
            .put("name", name)
            .put("fileCount", fileCount)
            .put("totalSize", totalSize)
            .put("dirs", org.json.JSONArray(emptyDirs))
        if (list.isNotEmpty()) m.put("list", org.json.JSONArray(list))
        if (skipped > 0) m.put("skipped", skipped)
        if (sizes.isNotEmpty() && sizes.size == list.size) m.put("sizes", org.json.JSONArray(sizes))
        return m
    }

    // ==================== 文件夹完整性校验（v2.6，仅双方 dl=1 时使用）====================

    /**
     * 文件夹成员发完（无论成败）后的收尾帧：接收端凭此**明确知道传输结束**，立刻用 dir_meta.list 与实收清单求差集、把缺失项回发 dir_missing
     *（老流程下接收端只能靠 doneFiles>=count 猜「收齐了」，成员在发送端被跳过/失败时永远等不到 → 静默缺文件）
     */
    fun dirEnd(mid: String, sent: Int, last: Boolean): JSONObject = msg("dir_end")
        .put("mid", mid)
        .put("sent", sent)
        .put("last", last)

    /**
     * 接收端回执：这些成员没到（或落盘/校验失败）。发送端据此**重发缺失项**，
     * 而不是把整个文件夹当成发送成功。
     */
    fun dirMissing(mid: String, items: List<String>): JSONObject = msg("dir_missing")
        .put("mid", mid)
        .put("items", org.json.JSONArray(items))

    /**
     * 接收端在收到 dir_meta 后立刻回报「本机已经在盘上的成员」：
     * 断链重发整目录时发送端据此跳过这些成员，只补真正缺的文件（局域网按文件粒度即可）。
     */
    fun dirHave(mid: String, items: List<String>): JSONObject = msg("dir_have")
        .put("mid", mid)
        .put("items", org.json.JSONArray(items))

    /**
     * 续传完成核对（大小版）：接收端把落盘文件的实际字节数回发给发送端比对。
     * 只对触发过续传的普通单文件发送；发送端按整文件 10s 超时等待，不阻塞任何线程。
     */
    fun fileVerify(mid: String, size: Long): JSONObject = msg("file_verify")
        .put("mid", mid)
        .put("size", size)

    fun syncReq(): JSONObject = msg("sync_req")

    fun ack(mid: String, ok: Boolean): JSONObject = msg("ack")
        .put("mid", mid)
        .put("ok", ok)

    // ==================== 语音通话 ====================

    /**
     * 写一个语音音频帧（Opus 编码，0.2s 一帧）。载荷 = [1B 子类型 BIN_AUDIO][4B seq 大端][4B ts 大端(ms)][opus data...]。
     * ts = 发送端**通话相对**毫秒（`util/CallClock.elapsedMs()`，起呼=0），4B 无符号；6h 上限通话不会回绕，禁止再塞绝对墙钟
     */
    @Throws(IOException::class)
    fun writeAudio(out: OutputStream, seq: Int, ts: Long, data: ByteArray, off: Int, len: Int) {
        val payloadLen = 1 + 8 + len
        if (payloadLen > MAX_FRAME) throw IOException("音频帧过大: $payloadLen")
        val fl = BIN_FLAG or payloadLen
        out.write(
            byteArrayOf(
                (fl ushr 24).toByte(), (fl ushr 16).toByte(), (fl ushr 8).toByte(), fl.toByte()
            )
        )
        out.write(BIN_AUDIO.toInt())
        out.write(
            byteArrayOf(
                (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
                (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte()
            )
        )
        if (len > 0) out.write(data, off, len)
        out.flush()
    }

    /**
     * 呼叫请求（呼出方 → 被叫方）。callerName/callerIp 用于弹窗展示。
     * af = 本端期望的**语音帧长（ms）**：20 = 低延迟，老端读到 0 → 两端都按 200ms 兼容模式跑；
     * mport = 本端**媒体端口**（0 = 未 bind/未声明 → 对端只能走带内档）；mudp = 本端媒体端口是否 UDP；
     * msid = 本次通话随机 token（串话隔离用，**不是安全边界**，S12），空 = 未声明。
     * ⚠ 协商字段**只有这三个**（没有 cport/cip，独立控制 TCP 通道已取消）；新标识只走 JSON 可选字段，**没有**新增二进制帧子类型（未知子类型会让老端 IOException 断链）
     */
    fun callInvite(
        callerName: String, callerIp: String, vid: Boolean = false, lv: Int = 0, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ): JSONObject = msg("call_invite")
        .put("name", callerName)
        .put("ip", callerIp)
        .put("vid", vid)   // true = 视频呼叫
        .put("lv", lv)     // 呼叫方本次选的档位上限（0=流畅 480P / 1=标清 720P / 2=高清 1080P/2M / 3=超清 1080P/5M）
        .put("af", af)     // 期望语音帧长（ms）；0/缺省 = 未声明 → 兼容 200ms
        .put("mport", mport)  // 媒体端口；0/缺省 = 未声明 → 带内档（缺省即回退，I11）
        .put("mudp", mudp)    // 媒体端口是否 UDP；false/缺省 = 未声明
        .put("msid", msid)    // 本次通话随机 token；空/缺省 = 未声明

    /**
     * 同意接听（被叫方 → 呼出方）。vid = 接听方是否接受视频（自己没法收就回 false，两端取与）；lv = 接听方可接受的档位（两端取 min）；
     * af = 接听方期望的语音帧长（ms），语义同 [callInvite]；mport/mudp/msid = 接听方本端媒体通道三元组（缺省回退口径同 [callInvite]）。
     * ⚠ **没有**新增 ip 字段：call_accept 从来不带本机地址，媒体方向靠 probe + learn-by-receiving 定（任务单 2.7）
     */
    fun callAccept(
        vid: Boolean = false, lv: Int = 0, af: Int = 0,
        mport: Int = 0, mudp: Boolean = false, msid: String = ""
    ): JSONObject = msg("call_accept")
        .put("vid", vid)
        .put("lv", lv)
        .put("af", af)
        .put("mport", mport)
        .put("mudp", mudp)
        .put("msid", msid)

    /**
     * 振铃期的**逐档媒体协商帧**（方案 §4.3① 的「交换」那一步）：callInvite/callAccept 各只有一次发言机会，
     * 被叫在第 2/3/4 档换 bind 后没有别的帧能把本端新端口带出去，主叫就学不到对端新端口。只新增 JSON 的 `type` 值，**没有**新增二进制帧子类型（I8）。
     * mport = 本端**本档** bind 到的媒体端口（0 = 已落带内档第五档）；mudp = 本端媒体端口是否 UDP；msid = 本次通话随机 token（串话隔离，**不是安全边界**，S12）。
     * tierOrdinal = 本端当前档在 [AvTransport.Tier] 序列里的序号；两端序号**不保证相等**（安卓跳过两个 443 档），只进日志，连通性判据是 probe 本身
     */
    fun callMedia(
        mport: Int, mudp: Boolean, msid: String, tierOrdinal: Int
    ): JSONObject = msg("call_media")
        .put("mport", mport)
        .put("mudp", mudp)
        .put("msid", msid)
        .put("mtier", tierOrdinal)   // 只进日志：两端档位序列长度不同，序号不能当判据

    /** 拒绝接听（被叫方 → 呼出方）。 */
    fun callReject(): JSONObject = msg("call_reject")

    /** 忙：已有通话，静默拒绝（不打扰，只回一帧让对方收线）。 */
    fun callBusy(): JSONObject = msg("call_busy")

    /** 挂断（任一方发起，含 6 小时超时强制挂断）。 */
    fun callEnd(reason: String = ""): JSONObject = msg("call_end")
        .put("reason", reason)

    /** 呼出方在对方接听前主动取消。 */
    fun callCancel(): JSONObject = msg("call_cancel")

    // ==================== 视频通话 ====================

    /**
     * 写一个视频帧（HEVC / H.264 Annex-B 裸流）。
     * 载荷 = [1B 子类型 BIN_VIDEO][4B seq 大端][4B ts 大端(ms)][1B flag][N 字节 AU]，长度字段 = BIN_FLAG or (1+4+4+1+N)。
     * **一个包 = 恰好一个访问单元（AU）**：接收端直接把载荷喂给解码器；ts = 发送端通话相对毫秒（与同通话音频帧同一零点, 两端各自零点）。
     * flag: bit0=KEYFRAME(IDR)、bit1=CONFIG(参数集)，见 VIDEO_FLAG_*
     */
    @Throws(IOException::class)
    fun writeVideo(
        out: OutputStream, seq: Int, ts: Long, flag: Int, data: ByteArray, off: Int, len: Int
    ) {
        if (len > MAX_VIDEO_FRAME) throw IOException("视频帧超 128KB 闸门: $len")
        val payloadLen = 1 + 8 + 1 + len
        if (payloadLen > MAX_FRAME) throw IOException("视频帧过大: $payloadLen")
        val fl = BIN_FLAG or payloadLen
        out.write(
            byteArrayOf(
                (fl ushr 24).toByte(), (fl ushr 16).toByte(), (fl ushr 8).toByte(), fl.toByte()
            )
        )
        out.write(BIN_VIDEO.toInt())
        out.write(
            byteArrayOf(
                (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
                (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(),
                flag.toByte()
            )
        )
        if (len > 0) out.write(data, off, len)
        out.flush()
    }

    /**
     * 编码参数变更通知（发送方 → 接收方）；改分辨率时接收方要重启解码器。
     * front = 本端摄像头是否**前置**：接收端据此决定「对方画面」默认显示角（对端用前置时默认多转 180°）。
     * 兼容性：`cam` 是 JSON 里的**新增键**，老对端（不带此键）按 optInt(...,0) 拿后置 → 行为与改造前完全一致，不需 hello 能力位门控
     */
    fun videoCfg(
        codec: String, w: Int, h: Int, fps: Int, br: Int, rot: Int, front: Boolean
    ): JSONObject = msg("video_cfg")
        .put("codec", codec)
        .put("w", w)
        .put("h", h)
        .put("fps", fps)
        .put("br", br)
        .put("rot", rot)
        .put("cam", if (front) 1 else 0)

    /**
     * 接收侧健康反馈（接收方 → 发送方，每 2s 一次），供发送侧升降档判断:
     * drop 丢帧百分比；lat 排队延迟 ms（**真实值**，不再用 -1 当丢帧哨兵）；dropping 接收端正处丢帧期（与 PC 端同名同义）；kbps 实际接收码率
     */
    fun videoFb(fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int): JSONObject = msg("video_fb")
        .put("fps", fps)
        .put("drop", drop)
        .put("lat", lat)
        .put("dropping", dropping)
        .put("kbps", kbps)

    /**
     * 请求对端临时停/开视频（如接收端切后台）。
     * key = 请求**立刻一个 IDR**（接收端解码器刚换代，等不起 1s GOP）；只在 true 时落键，老对端缺键按 false（I11）
     */
    fun videoReq(on: Boolean, key: Boolean = false): JSONObject = msg("video_req")
        .put("on", on)
        .also { if (key) it.put("key", true) }

    // ==================== 媒体数据报编解码（任务单 3.1）====================

    /**
     * 一份解码后的媒体数据报。[payload] **含** ctrl 子类型首字节（原样保留，本对象不猜语义）。
     */
    class Datagram(
        val ver: Int,
        val type: Int,
        /** 按 type 独立计数的流内序号（S10）；16 位，回绕由调用方自己处理 */
        val streamSeq: Int,
        val flags: Int,
        val sliceIdx: Int,
        val sliceCount: Int,
        /** 发送端通话相对毫秒 */
        val ts: Long,
        val payload: ByteArray,
        /** payload 有效长度 */
        val len: Int
    ) {
        fun flag(bit: Int): Boolean = (flags and bit) != 0

        /** ctrl 子类型；非 ctrl 报或载荷为空 ⇒ -1（调用方丢弃，不当成"子类型 0"） */
        fun ctrlSubtype(): Int =
            if (type == DG_TYPE_CTRL && len >= 1) payload[0].toInt() and 0xFF else -1
    }

    /** 14B 快照的解码结果。字段与 [AvTransport.Ctrl] 一一对应；
     *  两者的映射与 owner 判权归 3.13，本对象只管字节。 */
    class Snapshot(
        val epoch: Int,
        val seq: Long,
        val ownerBits: Int,
        val videoLevel: Int,
        val videoBrKbps: Int,
        val videoFps: Int,
        val audioBrKbps: Int,
        val flags: Int
    ) {
        fun flag(bit: Int): Boolean = (flags and bit) != 0
        fun pushOwner(): Boolean = (ownerBits and OWNER_BIT_PUSH) != 0
        fun recvOwner(): Boolean = (ownerBits and OWNER_BIT_RECV) != 0
    }

    /**
     * 编一份数据报：type=[DG_TYPE_*]，streamSeq=该流独立序号（0–65535），flags=[DG_FLAG_*] 按位或（非视频报传 0）；
     * sliceIdx/sliceCount=视频切片下标/总切片数（**非视频报必须 0/1**）；payload 可为 null（此时 len 必须 0），ctrl 报子类型首字节走 [encodeCtrl]
     */
    @Throws(IOException::class)
    fun encodeDatagram(
        type: Int, streamSeq: Int, flags: Int, sliceIdx: Int, sliceCount: Int, ts: Long,
        payload: ByteArray?, off: Int, len: Int
    ): ByteArray {
        checkType(type)
        checkU16("streamSeq", streamSeq)
        checkU16("sliceIdx", sliceIdx)
        checkU16("sliceCount", sliceCount)
        if (type != DG_TYPE_VIDEO_SLICE &&
            (sliceIdx != DG_SLICE_IDX_NONE || sliceCount != DG_SLICE_COUNT_NONE)
        ) {
            throw IOException("非视频报的分片字段恒 0/1，实际 $sliceIdx/$sliceCount")
        }
        // 视频报自己的形状约束：一帧至少 1 片，下标从 0 起 —— 这是 8B 头**内部**的不变式，
        // 不是 3.2 的重组策略（那里才管"缺任一片整帧丢"）；放这里，重组缓冲就永远见不到 idx>=count
        if (type == DG_TYPE_VIDEO_SLICE &&
            (sliceCount < DG_SLICE_COUNT_NONE || sliceIdx >= sliceCount)
        ) {
            throw IOException("视频分片形状非法: idx=$sliceIdx count=$sliceCount")
        }
        if (len < 0 || (payload == null && len > 0)) throw IOException("数据报载荷非法: $len")
        checkTs(ts)
        val b = ByteArray(DG_OVERHEAD + len)
        b[0] = ((DG_VER shl DG_VER_SHIFT) or type).toByte()
        putU16(b, 1, streamSeq)
        b[3] = flags.toByte()
        putU16(b, 4, sliceIdx)
        putU16(b, 6, sliceCount)
        putU32(b, DG_HEADER_LEN, ts)
        if (len > 0) System.arraycopy(payload!!, off, b, DG_OVERHEAD, len)
        return b
    }

    /**
     * 编一份 ctrl 数据报：子类型首字节由本方法写入，"整报 ≤64B" 也只在这一处守（ctrl 五个出口全从这里走）。
     * body = 子类型之后的正文，可为 null；probe 的 msid 就放这里
     */
    @Throws(IOException::class)
    fun encodeCtrl(
        ctrlSubtype: Int, streamSeq: Int, ts: Long, body: ByteArray?, off: Int, len: Int
    ): ByteArray {
        if (ctrlSubtype < CT_PROBE || ctrlSubtype > CT_STATE_SNAPSHOT) {
            throw IOException("未知 ctrl 子类型: $ctrlSubtype")
        }
        if (len < 0 || (body == null && len > 0)) throw IOException("ctrl 正文非法: $len")
        val report = DG_OVERHEAD + 1 + len
        if (report > DG_CTRL_MAX_REPORT) {
            throw IOException("ctrl 整报超 ${DG_CTRL_MAX_REPORT}B: $report")
        }
        val payload = ByteArray(1 + len)
        payload[0] = ctrlSubtype.toByte()
        if (len > 0) System.arraycopy(body!!, off, payload, 1, len)
        return encodeDatagram(
            DG_TYPE_CTRL, streamSeq, 0,
            DG_SLICE_IDX_NONE, DG_SLICE_COUNT_NONE, ts, payload, 0, payload.size
        )
    }

    /** 把一份控制状态写成定长 14B 正文（不含子类型首字节）。字段顺序 = §4.4 的书写顺序。 */
    @Throws(IOException::class)
    fun encodeSnapshot(
        epoch: Int, seq: Long, ownerBits: Int, videoLevel: Int, videoBrKbps: Int,
        videoFps: Int, audioBrKbps: Int, flags: Int
    ): ByteArray {
        checkU16("epoch", epoch)
        if (seq < 0 || seq > 0xFFFFFFFFL) throw IOException("seq 超 32 位: $seq")
        checkU8("ownerBits", ownerBits)
        checkU8("videoLevel", videoLevel)
        checkU16("videoBrKbps", videoBrKbps)
        checkU8("videoFps", videoFps)
        checkU8("audioBrKbps", audioBrKbps)
        checkU16("flags", flags)
        val b = ByteArray(DG_SNAPSHOT_LEN)
        putU16(b, SNAP_OFF_EPOCH, epoch)
        putU32(b, SNAP_OFF_SEQ, seq)
        b[SNAP_OFF_OWNER] = ownerBits.toByte()
        b[SNAP_OFF_VIDEO_LEVEL] = videoLevel.toByte()
        putU16(b, SNAP_OFF_VIDEO_BR, videoBrKbps)
        b[SNAP_OFF_VIDEO_FPS] = videoFps.toByte()
        b[SNAP_OFF_AUDIO_BR] = audioBrKbps.toByte()
        putU16(b, SNAP_OFF_FLAGS, flags)
        return b
    }

    /** 定长 14B 快照 + 子类型 → 一份 ctrl 报（恒 27B） */
    @Throws(IOException::class)
    fun encodeSnapshotCtrl(
        streamSeq: Int, ts: Long,
        epoch: Int, seq: Long, ownerBits: Int, videoLevel: Int, videoBrKbps: Int,
        videoFps: Int, audioBrKbps: Int, flags: Int
    ): ByteArray {
        val body = encodeSnapshot(
            epoch, seq, ownerBits, videoLevel, videoBrKbps, videoFps, audioBrKbps, flags
        )
        return encodeCtrl(CT_STATE_SNAPSHOT, streamSeq, ts, body, 0, body.size)
    }

    /**
     * 解一份数据报。
     * 畸形报（长度不足 / 版本不认识 / 类型未知 / 非视频报却带了分片）**一律抛 IOException**：
     * UDP 收包侧 catch 住直接丢这一份，不许吞成"看着像空报"的返回值（半解的报会被当成合法帧送进重组缓冲, 3.2 的"缺任一片整帧丢"就永远不触发）
     */
    @Throws(IOException::class)
    fun decodeDatagram(buf: ByteArray, off: Int, len: Int): Datagram {
        if (len < DG_OVERHEAD) throw IOException("数据报过短: $len")
        if (off < 0 || off + len > buf.size) throw IOException("数据报越界: off=$off len=$len cap=${buf.size}")
        val b0 = buf[off].toInt() and 0xFF
        val ver = b0 ushr DG_VER_SHIFT
        val type = b0 and DG_TYPE_MASK
        if (ver != DG_VER) throw IOException("数据报版本不认识: $ver")
        checkType(type)
        val streamSeq = getU16(buf, off + 1)
        val flags = buf[off + 3].toInt() and 0xFF
        val sliceIdx = getU16(buf, off + 4)
        val sliceCount = getU16(buf, off + 6)
        if (type != DG_TYPE_VIDEO_SLICE &&
            (sliceIdx != DG_SLICE_IDX_NONE || sliceCount != DG_SLICE_COUNT_NONE)
        ) {
            throw IOException("非视频报的分片字段恒 0/1，实际 $sliceIdx/$sliceCount")
        }
        if (type == DG_TYPE_VIDEO_SLICE &&
            (sliceCount < DG_SLICE_COUNT_NONE || sliceIdx >= sliceCount)
        ) {
            throw IOException("视频分片形状非法: idx=$sliceIdx count=$sliceCount")
        }
        val ts = getU32(buf, off + DG_HEADER_LEN)
        val payloadLen = len - DG_OVERHEAD
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) System.arraycopy(buf, off + DG_OVERHEAD, payload, 0, payloadLen)
        return Datagram(ver, type, streamSeq, flags, sliceIdx, sliceCount, ts, payload, payloadLen)
    }

    /**
     * 从 ctrl 报正文（**已去掉子类型首字节**）解出 14B 快照；
     * 定长是硬要求：长度不等直接抛，不做"截断凑数"
     */
    @Throws(IOException::class)
    fun decodeSnapshotBody(body: ByteArray, off: Int, len: Int): Snapshot {
        if (len != DG_SNAPSHOT_LEN) throw IOException("快照正文必须定长 ${DG_SNAPSHOT_LEN}B，实际 $len")
        if (off < 0 || off + len > body.size) throw IOException("快照正文越界")
        return Snapshot(
            getU16(body, off + SNAP_OFF_EPOCH),
            getU32(body, off + SNAP_OFF_SEQ),
            body[off + SNAP_OFF_OWNER].toInt() and 0xFF,
            body[off + SNAP_OFF_VIDEO_LEVEL].toInt() and 0xFF,
            getU16(body, off + SNAP_OFF_VIDEO_BR),
            body[off + SNAP_OFF_VIDEO_FPS].toInt() and 0xFF,
            body[off + SNAP_OFF_AUDIO_BR].toInt() and 0xFF,
            getU16(body, off + SNAP_OFF_FLAGS)
        )
    }

    /** 从数据报直接解快照：类型、子类型、正文长度三项都核对，任一不符抛 */
    @Throws(IOException::class)
    fun decodeSnapshot(dg: Datagram): Snapshot {
        if (dg.type != DG_TYPE_CTRL) throw IOException("不是 ctrl 报: type=${dg.type}")
        if (dg.ctrlSubtype() != CT_STATE_SNAPSHOT) {
            throw IOException("ctrl 子类型不是 state_snapshot: ${dg.ctrlSubtype()}")
        }
        if (dg.len != DG_SNAPSHOT_BODY) {
            throw IOException("快照报正文必须定长 ${DG_SNAPSHOT_BODY}B，实际 ${dg.len}")
        }
        return decodeSnapshotBody(dg.payload, 1, dg.len - 1)
    }

    @Throws(IOException::class)
    private fun checkType(type: Int) {
        if (type < DG_TYPE_AUDIO || type > DG_TYPE_FEEDBACK) {
            throw IOException("未知数据报类型: $type")
        }
    }

    @Throws(IOException::class)
    private fun checkTs(ts: Long) {
        if (ts < 0 || ts > 0xFFFFFFFFL) throw IOException("ts 必须是通话相对无符号 32 位毫秒: $ts")
    }

    @Throws(IOException::class)
    private fun checkU8(name: String, v: Int) {
        if (v < 0 || v > 0xFF) throw IOException("$name 超 8 位: $v")
    }

    @Throws(IOException::class)
    private fun checkU16(name: String, v: Int) {
        if (v < 0 || v > 0xFFFF) throw IOException("$name 超 16 位: $v")
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun getU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun getU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
}

/**
 * 「数据流量确认」的结果。
 * 触发判据是**两端各自的网络环境**（不是对端 IP 网段）：本机在数据网络 → 本机提醒一次（每次使用都提醒，不记忆）；
 * 对端在数据网络 → [Protocol.dataAsk] 让对端自己提醒；任何一侧拒绝，本次操作中止（文件在聊天里留一条带红色叹号的可重试消息）
 */
enum class DataConsent {
    /** 同意（本机不需要提醒，或本机与对端都同意） */
    OK,

    /** 本机用户拒绝「用数据流量」，本次不发起 */
    LOCAL_REFUSED,

    /** 对端拒绝用数据流量接收 */
    REMOTE_REFUSED,

    /**
     * 对端 [DATA_ANS_WAIT_MS] 内**没有应答**（进程被杀/半死/帧丢了）。
     * 与 REMOTE_REFUSED 必须分开：超时不是用户拒绝，不许投票③、也不该提示「对方拒绝」。
     */
    REMOTE_TIMEOUT,

    /** 目标设备当前没有健康会话（退化为待发/直接失败） */
    NO_SESSION
}

/** 数据流量确认相关的固定文案（两端与三个场景一一对应） */
object DataConsentText {

    const val KIND_FILE = "file"
    const val KIND_VOICE = "voice"
    const val KIND_VIDEO = "video"

    /** 操作名（本机确认框里的「$label」） */
    fun labelOf(kind: String, name: String = ""): String = when (kind) {
        KIND_VOICE -> "语音通话"
        KIND_VIDEO -> "视频通话"
        else -> if (name.isEmpty()) "文件" else "文件"
    }

    /** 接收方确认框里的对象描述（文件带上名字，通话只说类型） */
    fun incomingLabelOf(kind: String, name: String): String = when (kind) {
        KIND_VOICE -> "语音通话"
        KIND_VIDEO -> "视频通话"
        else -> if (name.isEmpty()) "文件" else "文件 $name"
    }

    /** 发起方看到「对端拒绝用数据流量接收」时的提示文本（斜杠按三个场景分别取值：文件/语音通话/视频通话） */
    fun peerRefusedText(kind: String): String = when (kind) {
        KIND_VOICE -> "对方拒绝了使用数据流量接受语音通话"
        KIND_VIDEO -> "对方拒绝了使用数据流量接受视频通话"
        else -> "对方拒绝了使用数据流量接受文件"
    }

    /** 对端 90s 无应答（≠拒绝）：提示语必须区分，避免用户误以为对方点了拒绝 */
    fun peerTimeoutText(kind: String): String = when (kind) {
        KIND_VOICE -> "对方未确认（90 秒无响应），语音通话未发起"
        KIND_VIDEO -> "对方未确认（90 秒无响应），视频通话未发起"
        else -> "对方未确认（90 秒无响应），已留在待发队列"
    }
}
