package com.phonepctransfer.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 传输协议 v2：帧 = [4 字节大端 uint32 长度][载荷]
 *
 * v2 起（2026-09-13）：
 *  - 长度字段最高位（0x80000000）为 0 → JSON 控制帧；为 1 → 二进制分片帧（低 31 位为载荷长度）。
 *    文件分片从「256KB + base64 + JSON」改为「1MB + 裸二进制」，消除 33% base64 膨胀和
 *    大字符串 JSON 编解码开销 —— 这是传输速率只有无线网络 35% 左右的主因。
 *  - 断点续传：file_query / file_resume 控制帧（见 docs/PROTOCOL.md §4.3）
 *  - 文件夹传输：dir_meta 帧声明目录，file_meta 携带 dirId/relPath
 *
 * 与 PC 端 com.phonepctransfer.pc.Protocol.java 严格一致。
 */
object Protocol {

    const val PROTO_VER = 2
    const val DEFAULT_TCP_PORT = 28765
    const val DEFAULT_UDP_PORT = 28766

    /** JSON 控制帧上限 8 MB（载荷长度字段也用它约束二进制帧） */
    const val MAX_FRAME = 8 * 1024 * 1024

    /** 文件分片大小（原始字节，二进制帧） */
    const val CHUNK_SIZE = 1 * 1024 * 1024

    /** 接收端双缓冲：每块 4MB，一块写满切另一块、满块交给落盘线程 */
    const val RX_BUF_SIZE = 4 * 1024 * 1024

    /** 二进制帧标志位（长度字段最高位） */
    const val BIN_FLAG = 0x80000000.toInt()

    /** 二进制帧子类型：文件分片 */
    const val BIN_CHUNK: Byte = 0x01

    /** 二进制帧子类型：语音通话音频帧（Opus 编码，0.2s 一帧） */
    const val BIN_AUDIO: Byte = 0x02

    /**
     * 二进制帧子类型：视频帧（HEVC / H.264 Annex-B 裸流，**一包 = 恰好一个访问单元**）。
     *
     * ⚠ 向后兼容闸门：老版本对端 readFrame 遇到 0x03 会抛 IOException → 断链。
     * 因此**只有对端 hello 广播了 `vid=1` 才允许发**（见 Link.peerVidCap）。
     */
    const val BIN_VIDEO: Byte = 0x03

    /** 视频帧 flag：该帧为 IDR（接收端据此丢帧对齐 / 快速恢复） */
    const val VIDEO_FLAG_KEYFRAME = 0x01

    /** 视频帧 flag：载荷是编解码配置（VPS/SPS/PPS），收到即重建解码器 */
    const val VIDEO_FLAG_CONFIG = 0x02

    /**
     * 视频单帧硬闸门 128 KB。
     *
     * 视频帧与音频帧、文件分片**共用同一条会话的 writeLock**：一个超大 IDR 会让
     * `write()` 长时间独占写锁 → 音频/心跳/文件全被挡住。超限直接丢弃并记日志。
     */
    const val MAX_VIDEO_FRAME = 128 * 1024

    // ==================== 媒体数据报线格式（任务单 3.1 / 方案 §4.4）====================

    /**
     * 数据报版本，占 [0] 的高 4 位；低 4 位是流类型。
     *
     * 这是**媒体通道自己的**数据报：UDP 档原样发，媒体 TCP 档发同一份报（怎么在字节流上框帧
     * 方案没写，归 2.6）。带内档不用它 —— 那里仍是上面的 [BIN_AUDIO]/[BIN_VIDEO] 帧制（I3 例外条款）。
     *
     * ⚠ 本节**没有**新增任何 `BIN_*` 主 TCP 二进制子类型：老端读帧遇到未知子类型会直接
     *   IOException 断链（I8，与 [callInvite] 里那句提醒同一个理由）。
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
     * ctrl 子类型：载荷**首字节**。§4.4 给了两个候选位置（[0] 的低 4 位 / payload 首字节），
     * 本条定死取后者 —— [0] 低 4 位已经被四个流类型占着，再塞五种 ctrl 就得挤掉类型位。
     */
    const val CT_PROBE = 1
    const val CT_KEYFRAME_REQ = 2
    const val CT_STOP_RESUME = 3
    const val CT_LEVEL_SYNC = 4
    const val CT_STATE_SNAPSHOT = 5

    /** 固定头 8B：[0] ver/type｜[1..2] streamSeq｜[3] flags｜[4..5] sliceIdx｜[6..7] sliceCount */
    const val DG_HEADER_LEN = 8

    /**
     * 头后 4B ts = 发送端**通话相对**毫秒（`util/CallClock.elapsedMs()`，起呼=0）。
     * 宽度与零点口径照带内音视频帧的既有先例（[writeAudio] / [writeVideo] 各 4B 大端）；
     * 两端各自零点，接收端**不得**拿它与本端墙钟相减（S1 / 任务单 3.8 的两段式帧龄）。
     */
    const val DG_TS_LEN = 4

    /** 一份报的固定开销（不含 payload）= 8 + 4 */
    const val DG_OVERHEAD = DG_HEADER_LEN + DG_TS_LEN

    /**
     * ctrl **整报**上限 64B（§4.4）。音频/视频/反馈报不受它约束 —— 它们的上限是 3.2 的 1200B。
     */
    const val DG_CTRL_MAX_REPORT = 64

    /**
     * [3] flags：**只有这三**位有定义。bit0/bit1 的位序照带内视频帧的 [VIDEO_FLAG_*]
     * （IDR=0x01、CONFIG=0x02）⇒ 带内生产者迁过来时这两位不用重映射；bit2 是新加的"帧内最后一片"。
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
     * 快照 flags（[12..13] 低 7 位）：**位序 = §4.4 括号里的书写序，一位不多一位不少** ——
     * aggregated / camOn / muted / sendingPaused / wantVideo / needKeyframe / bg。
     * 前四位是推流端 owner 组、后三位是收流端组（顺序照 D14-1 的字段表，不是按字母）。
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

    @Synchronized
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
     * 写一个二进制文件分片帧。
     * 载荷 = [1B 子类型][1B midLen][mid UTF-8][4B seq 大端][data...]，一次性写出后 flush。
     */
    @Throws(IOException::class)
    fun writeChunk(out: OutputStream, mid: String, seq: Int, data: ByteArray, off: Int, len: Int) {
        val midB = mid.toByteArray(Charsets.UTF_8)
        if (midB.size > 255) throw IOException("mid 过长")
        val payloadLen = 1 + 1 + midB.size + 4 + len
        if (payloadLen > MAX_FRAME) throw IOException("分片帧过大: $payloadLen")
        val fl = BIN_FLAG or payloadLen
        out.write(
            byteArrayOf(
                (fl ushr 24).toByte(), (fl ushr 16).toByte(), (fl ushr 8).toByte(), fl.toByte()
            )
        )
        val head = ByteArray(2 + midB.size + 4)
        head[0] = BIN_CHUNK
        head[1] = midB.size.toByte()
        System.arraycopy(midB, 0, head, 2, midB.size)
        head[2 + midB.size] = (seq ushr 24).toByte()
        head[3 + midB.size] = (seq ushr 16).toByte()
        head[4 + midB.size] = (seq ushr 8).toByte()
        head[5 + midB.size] = seq.toByte()
        out.write(head)
        out.write(data, off, len)
        out.flush()
    }

    /** 一帧：JSON 控制帧、二进制文件分片帧、或二进制语音帧 */
    sealed class Frame {
        class Json(val j: JSONObject) : Frame()
        /** data 为原始分片字节（有效范围 [0, len)） */
        class Chunk(val mid: String, val seq: Int, val data: ByteArray, val len: Int) : Frame()
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

    /** @return 帧内容；流正常结束返回 null */
    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame? {
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
                val data = ByteArray(dataLen)
                if (dataLen > 0) dis.readFully(data)
                Frame.Chunk(String(midB, Charsets.UTF_8), seq, data, dataLen)
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
        vid: Boolean = false, cam: Boolean = false, vcodec: List<String> = emptyList()
    ): JSONObject = msg("hello")
        .put("id", id)
        .put("name", name)
        .put("model", model)
        .put("ver", PROTO_VER)
        // ===== 能力协商（老端不认识这些字段，直接忽略；本端凭对端的 vid 决定敢不敢发视频帧）=====
        .put("vid", vid)           // 本端可**接收**视频
        .put("cam", cam)           // 本端有摄像头、可**发送**视频
        .put("vcodec", org.json.JSONArray(vcodec))  // 本端可**解码**的编码列表
        .put("hs", true)           // 本端支持历史同步（hsync，v2.3 起；老端忽略）
        .put("hd", true)           // 本端支持「消息删除同步」（msg_del，v2.4 起；老端忽略）
        .put("dl", true)           // 本端支持「文件夹完整性校验」（dir_meta.list/dir_end/dir_missing，v2.6 起；老端忽略）
        .put("dm", true)           // 本端支持「数据流量确认」（data_ask/data_ans，v2.7 起；老端忽略）

    // ==================== 数据流量确认（v2.7，仅双方 dm=1 时使用）====================

    /**
     * 发起方 → 对端：**用数据流量做这件事，你同意吗？**
     *
     * 为什么要有这一帧：每条链路两端各自的网络环境是独立的 —— 发起方在 WiFi 不代表
     * 接收方不在运营商数据网络上。想让「接收方也在用流量」时能先问一句，
     * 就必须由发起方在对端开口之前问；对端在非数据网络下**自动应答同意**（不打扰用户）。
     *
     * `kind`：`file` / `voice` / `video`；`name` 为文件（夹）名，通话为空。
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
        msg("file_resume")
            .put("mid", mid)
            .put("ok", ok)
            .put("offset", offset)
            .put("sha256", sha256)

    /** 文件夹传输开始：name=文件夹名，fileCount=成员数，totalSize=总字节 */
    fun dirMeta(
        mid: String, name: String, fileCount: Int, totalSize: Long,
        emptyDirs: List<String> = emptyList(),
        /** 成员相对路径清单（接收端据此核对缺失）。为空 = 不带清单，退化为「只按数量核对」 */
        list: List<String> = emptyList(),
        /** 因深度/数量上限被跳过的分支数（>0 时对端明确提示，不再无声消失） */
        skipped: Int = 0
    ): JSONObject {
        val m = msg("dir_meta")
            .put("mid", mid)
            .put("name", name)
            .put("fileCount", fileCount)
            .put("totalSize", totalSize)
            .put("dirs", org.json.JSONArray(emptyDirs))
        if (list.isNotEmpty()) m.put("list", org.json.JSONArray(list))
        if (skipped > 0) m.put("skipped", skipped)
        return m
    }

    // ==================== 文件夹完整性校验（v2.6，仅双方 dl=1 时使用）====================

    /**
     * 文件夹成员发完（无论成败）后的收尾帧。接收端凭此**明确知道传输结束**，
     * 立刻用 dir_meta.list 与实收清单求差集，把缺失项回发 dir_missing。
     *
     * 为什么必须有这一帧：老流程下接收端只能靠 doneFiles>=count 猜「收齐了」，
     * 而成员在发送端被跳过/发送失败时接收端永远等不到 → 静默缺文件。
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
     * 续传完成核对：接收端把落盘文件的 MD5 回发给发送端比对（端到端完整性）。
     * 只对触发过续传的普通单文件发送；发送端异步核对，不阻塞任何线程。
     */
    fun fileVerify(mid: String, md5: String): JSONObject = msg("file_verify")
        .put("mid", mid)
        .put("md5", md5)

    fun syncReq(): JSONObject = msg("sync_req")

    fun ack(mid: String, ok: Boolean): JSONObject = msg("ack")
        .put("mid", mid)
        .put("ok", ok)

    // ==================== 语音通话 ====================

    /**
     * 写一个语音音频帧（Opus 编码，0.2s 一帧）。
     * 载荷 = [1B 子类型 BIN_AUDIO][4B seq 大端][4B ts 大端(ms)][opus data...]。
     *
     * ts = 发送端**通话相对**毫秒（`util/CallClock.elapsedMs()`，起呼=0），4B 无符号；
     * 6h 上限通话不会回绕。禁止再塞绝对墙钟。
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
     *
     * @param af 本端期望的**语音帧长（ms）**：20 = 低延迟模式。老版本对端不带这个字段
     *           （读到 0）→ 两端都按 200ms 兼容模式跑，与改造前行为一致。
     * @param mport 本端**媒体端口**（任务单 2.4 / 方案 §4.2）；0 = 未 bind / 未声明 → 对端只能走带内档
     * @param mudp  本端媒体端口是否 UDP；false = 未声明（媒体 TCP / 带内）
     * @param msid  本次通话的随机 token（串话隔离用，**不是安全边界**，S12）；空 = 未声明
     *
     * ⚠ 协商字段**只有这三个**：没有 `cport` / `cip`——独立控制 TCP 通道已取消（U9 / N9）。
     * ⚠ 新标识只走 JSON 可选字段，**没有**新增二进制帧子类型（未知子类型会让老端直接抛 IOException
     *   断链，读帧处那句 "未知二进制帧子类型"，I8）。
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
     * 同意接听（被叫方 → 呼出方）。
     * @param vid 接听方是否接受视频（自己没法收视频就回 false，两端取与）
     * @param lv  接听方可接受的档位，两端取 min
     * @param af  接听方期望的语音帧长（ms），语义同 [callInvite]
     * @param mport/mudp/msid 接听方本端媒体通道三元组，语义与缺省回退口径同 [callInvite]
     *
     * ⚠ **没有**新增 ip 字段：call_accept 从来不带本机地址（对照 [callInvite] 独有的 name/ip 两项），
     *   媒体方向靠 probe + learn-by-receiving 定（任务单 2.7），不靠报文里的地址声明。
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
     * 振铃期的**逐档媒体协商帧**（任务单 2.5 / 方案 §4.3① 的「交换」那一步）。
     *
     * 为什么需要第三条帧：§4.2 只把三元组挂在 [callInvite] / [callAccept] 上，而这两条各有
     * 一次发言机会（主叫发 invite、被叫发 accept）。§4.3⑤ 要求「本档不通就换下一档、回到 ①」
     * —— 被叫在第 2/3/4 档**没有任何帧**能把新 bind 出来的本端端口带出去，
     * 主叫就永远学不到对端的新端口，双向 probe 无法继续。
     * （2026-10-02 用户点 (a)：允许新增这条 JSON 协商帧；§4.2 标题「不新增往返」读作
     * **不新增 TCP 往返、不新增二进制子类型**，主 TCP 早已连好，多一条 JSON 帧不算往返。）
     *
     * ⚠ 只新增 JSON 的 `type` 值，**没有**新增二进制帧子类型（I8：未知二进制子类型会让
     *   老端直接抛 IOException 断链）。老端读到未知 JSON type 只打一行「未知帧类型」日志、
     *   不断链（[Link] 读帧处的 else 分支），所以 I11 的「不得断链」仍然成立。
     *
     * @param mport       本端**本档** bind 到的媒体端口；0 = 本端已落带内档（第五档）
     * @param mudp        本端媒体端口是否 UDP（true = UDP 档，false = 媒体 TCP 档或带内）
     * @param msid        本端为本次通话生成的随机 token（每端各自生成，2026-10-02 用户点；
     *                    串话隔离用，**不是安全边界**，S12）
     * @param tierOrdinal 本端当前档在 [AvTransport.Tier] 序列里的序号。
     *                    ⚠ 两端序号**不保证相等**（安卓按 §4.1 跳过两个 443 档），
     *                    所以本字段只用于日志与「对端走到第几档」，连通性判据是 probe 本身
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
     *
     * 载荷 = [1B 子类型 BIN_VIDEO][4B seq 大端][4B ts 大端(ms)][1B flag][N 字节 AU]
     * 长度字段 = BIN_FLAG or (1 + 4 + 4 + 1 + N)。
     *
     * **一个包 = 恰好一个访问单元（AU）**：接收端不需要扫 `00 00 01` 起始码，
     * 直接把载荷喂给解码器即可。`ts` 为**发送端通话相对毫秒**（`util/CallClock.elapsedMs()`，
     * 与同一通话的音频帧同一零点），用于 A/V 时间轴对齐；两端各自零点，不与本端墙钟相减。
     *
     * @param flag bit0=KEYFRAME(IDR)、bit1=CONFIG(参数集)，见 VIDEO_FLAG_*
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
     * 编码参数变更通知（发送方 → 接收方）。改分辨率时接收方要重启解码器。
     *
     * @param front 本端摄像头是否**前置**（2026-09-19 用户要求）。接收端据此决定
     *   「对方画面」的默认显示角：对端用前置时默认多转 180°（= 点了两次旋转钮）。
     *   **兼容性**：`cam` 是 JSON 里的**新增键**，老对端（不带此键）按 `optInt(...,0)`
     *   拿到后置 → 行为与改造前完全一致，不需要 hello 能力位门控（也没有未知二进制子类型）。
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
     * 接收侧健康反馈（接收方 → 发送方，每 2s 一次），供发送侧升降档判断。
     * @param drop 丢帧百分比；lat 排队延迟 ms（**真实值**，不再用 -1 当丢帧哨兵）；
     *             dropping 接收端正处丢帧期（任务单 1.3，与 PC 端同名同义）；kbps 实际接收码率
     */
    fun videoFb(fps: Int, drop: Int, lat: Int, dropping: Boolean, kbps: Int): JSONObject = msg("video_fb")
        .put("fps", fps)
        .put("drop", drop)
        .put("lat", lat)
        .put("dropping", dropping)
        .put("kbps", kbps)

    /**
     * 请求对端临时停/开视频（如接收端切后台）。
     *
     * @param key 请求**立刻一个 IDR**（接收端解码器刚换代，等不起 1s GOP）。
     *            只在 true 时落键 —— 老对端的帧与改前逐字节一致，新对端缺键按 false（I11）。
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
     * 编一份数据报。
     *
     * @param type       [DG_TYPE_*]
     * @param streamSeq  该流的独立序号（0–65535）
     * @param flags      [DG_FLAG_*] 的按位或；非视频报传 0
     * @param sliceIdx   视频切片下标；**非视频报必须 0**
     * @param sliceCount 该帧总切片数；**非视频报必须 1**
     * @param payload    可为 null（此时 len 必须为 0）；ctrl 报的子类型首字节请走
     *                   [encodeCtrl]，不要手工塞
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
        // 不是 3.2 的重组策略（那里才管"缺任一片整帧丢"）。放在这里，重组缓冲就永远
        // 见不到 idx>=count 这种不可能的组合。
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
     * 编一份 ctrl 数据报：子类型首字节由本方法写入，"整报 ≤64B" 也只在**这一处**守
     * （ctrl 的五个出口 —— 2.5 的 probe、3.9 的 keyframe_req、3.13 的快照，加 stop_resume /
     * level_sync —— 全从这里走，所以校验只需要一个地方）。
     *
     * @param body 子类型之后的正文，可为 null；probe 的 msid 就放这里
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
     *
     * 畸形报（长度不足 / 版本不认识 / 类型未知 / 非视频报却带了分片）**一律抛 IOException**：
     * UDP 收包侧 catch 住直接丢这一份就够，不许吞成"看着像空报"的返回值 ——
     * 半解的报会被当成合法帧送进重组缓冲，那就是 3.2 的"缺任一片整帧丢"永远不触发。
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
     * 从 ctrl 报的正文（**已去掉子类型首字节**）解出 14B 快照。
     * 定长是硬要求：长度不等直接抛，不做"截断凑数"（§4.4 写的是**定长** 14B）。
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
 * 「数据流量确认」的结果（v2.7）。
 *
 * 触发这一套流程的判据是**两端各自的网络环境**，不是对端 IP 的网段：
 *  - 本机在运营商数据网络上 → 本机提醒一次（每次使用都提醒，不记忆）
 *  - 对端在运营商数据网络上 → 由 [Protocol.dataAsk] 让对端自己提醒
 * 任何一侧拒绝，本次操作就中止；文件会在聊天里留一条带红色叹号的可重试消息。
 */
enum class DataConsent {
    /** 同意（本机不需要提醒，或本机与对端都同意） */
    OK,

    /** 本机用户拒绝「用数据流量」，本次不发起 */
    LOCAL_REFUSED,

    /** 对端拒绝用数据流量接收 */
    REMOTE_REFUSED,

    /** 目标设备当前没有健康会话（退化为待发/直接失败） */
    NO_SESSION
}

/** 数据流量确认相关的固定文案（两端与三个场景一一对应，见用户 2026-09-17 定调） */
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

    /**
     * 发起方看到「对端拒绝用数据流量接收」时的提示文本，斜杠按三个场景分别取值：
     * 「对方拒绝了使用数据流量接受文件 / 接受语音通话 / 接受视频通话」
     */
    fun peerRefusedText(kind: String): String = when (kind) {
        KIND_VOICE -> "对方拒绝了使用数据流量接受语音通话"
        KIND_VIDEO -> "对方拒绝了使用数据流量接受视频通话"
        else -> "对方拒绝了使用数据流量接受文件"
    }
}
