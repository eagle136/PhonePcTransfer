package com.phonepctransfer.pc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输协议 v2：帧 = [4 字节大端 uint32 长度][载荷]。
 *
 * v2 起（2026-09-13）：
 *  - 长度字段最高位（0x80000000）为 0 → JSON 控制帧；为 1 → 二进制分片帧（低 31 位为载荷长度）。
 *    文件分片从「256KB + base64 + JSON」改为「1MB + 裸二进制」，消除 33% base64 膨胀和
 *    大字符串 JSON 编解码开销 —— 这是传输速率只有无线网络 35% 左右的主因。
 *  - 断点续传：file_query / file_resume 控制帧（见 docs/PROTOCOL.md §4.3）
 *  - 文件夹传输：dir_meta 帧声明目录，file_meta 携带 dirId/relPath
 *
 * 与 Android 端 com.phonepctransfer.net.Protocol.kt 严格一致。
 */
public final class Protocol {

    public static final int PROTO_VER = 2;
    public static final int DEFAULT_TCP_PORT = 28765;
    public static final int DEFAULT_UDP_PORT = 28766;
    /** JSON 控制帧上限 8 MB（二进制帧长度同样受它约束） */
    public static final int MAX_FRAME = 8 * 1024 * 1024;
    /** 文件分片大小（原始字节，二进制帧） */
    public static final int CHUNK_SIZE = 1 * 1024 * 1024;
    /** 接收端双缓冲：每块 4MB，一块写满切另一块、满块交给落盘线程 */
    public static final int RX_BUF_SIZE = 4 * 1024 * 1024;
    /** 二进制帧标志位（长度字段最高位） */
    public static final int BIN_FLAG = 0x80000000;
    /** 二进制帧子类型：文件分片 */
    public static final byte BIN_CHUNK = 0x01;

    /** 二进制帧子类型：语音通话音频帧（Opus 编码，0.2s 一帧） */
    public static final byte BIN_AUDIO = 0x02;

    /**
     * 二进制帧子类型：视频帧（HEVC / H.264 Annex-B 裸流，**一包 = 恰好一个访问单元**）。
     *
     * ⚠ 向后兼容闸门：老版本对端 readFrame 遇到 0x03 会抛 IOException → 断链。
     * 因此**只有对端 hello 广播了 vid=1 才允许发**（见 Link.peerVidCap）。
     */
    public static final byte BIN_VIDEO = 0x03;

    /** 视频帧 flag：该帧为 IDR */
    public static final int VIDEO_FLAG_KEYFRAME = 0x01;

    /** 视频帧 flag：载荷是编解码配置（VPS/SPS/PPS），收到即重建解码器 */
    public static final int VIDEO_FLAG_CONFIG = 0x02;

    /**
     * 视频单帧硬闸门 128 KB。
     * 视频帧与音频帧、文件分片共用同一条会话的 writeLock，超大 IDR 会长时间独占写锁。
     */
    public static final int MAX_VIDEO_FRAME = 128 * 1024;

    // ==================== 媒体数据报线格式（任务单 3.1 / 方案 §4.4）====================

    /**
     * 数据报版本，占 [0] 的高 4 位；低 4 位是流类型。
     *
     * <p>这是**媒体通道自己的**数据报：UDP 档原样发，媒体 TCP 档发同一份报（怎么在字节流上
     * 框帧方案没写，归 2.6）。带内档不用它 —— 那里仍是上面的 {@code BIN_AUDIO}/{@code BIN_VIDEO}
     * 帧制（I3 的例外条款）。</p>
     *
     * <p>⚠ 本节**没有**新增任何 {@code BIN_*} 主 TCP 二进制子类型：老端读帧遇到未知子类型会
     * 直接 IOException 断链（I8，与本类 {@code callInvite} 里那句提醒同一个理由）。</p>
     */
    public static final int DG_VER = 1;

    /** [0] = (版本 &lt;&lt; 4) | 流类型 */
    public static final int DG_VER_SHIFT = 4;
    public static final int DG_TYPE_MASK = 0x0F;

    /** [0] 低 4 位：流类型。四种，与 §4.4 的表一一对应，不多不少 */
    public static final int DG_TYPE_AUDIO = 1;
    public static final int DG_TYPE_VIDEO_SLICE = 2;
    public static final int DG_TYPE_CTRL = 3;
    public static final int DG_TYPE_FEEDBACK = 4;

    /**
     * ctrl 子类型：载荷**首字节**。§4.4 给了两个候选位置（[0] 的低 4 位 / payload 首字节），
     * 本条定死取后者 —— [0] 低 4 位已经被四个流类型占着，再塞五种 ctrl 就得挤掉类型位。
     */
    public static final int CT_PROBE = 1;
    public static final int CT_KEYFRAME_REQ = 2;
    public static final int CT_STOP_RESUME = 3;
    public static final int CT_LEVEL_SYNC = 4;
    public static final int CT_STATE_SNAPSHOT = 5;

    /** 固定头 8B：[0] ver/type｜[1..2] streamSeq｜[3] flags｜[4..5] sliceIdx｜[6..7] sliceCount */
    public static final int DG_HEADER_LEN = 8;

    /**
     * 头后 4B ts = 发送端**通话相对**毫秒（{@link CallClock#elapsedMs()}，起呼=0）。
     * 宽度与零点口径照带内音视频帧的既有先例（{@link #writeAudio} / {@link #writeVideo} 各 4B 大端）；
     * 两端各自零点，接收端**不得**拿它与本端墙钟相减（S1 / 任务单 3.8 的两段式帧龄）。
     */
    public static final int DG_TS_LEN = 4;

    /** 一份报的固定开销（不含 payload）= 8 + 4 */
    public static final int DG_OVERHEAD = DG_HEADER_LEN + DG_TS_LEN;

    /**
     * ctrl **整报**上限 64B（§4.4）。音频/视频/反馈报不受它约束 —— 它们的上限是 3.2 的 1200B。
     */
    public static final int DG_CTRL_MAX_REPORT = 64;

    /**
     * [3] flags：**只有这三**位有定义。bit0/bit1 的位序照带内视频帧的 {@code VIDEO_FLAG_*}
     * （IDR=0x01、CONFIG=0x02）⇒ 带内生产者迁过来时这两位不用重映射；bit2 是新加的"帧内最后一片"。
     */
    public static final int DG_FLAG_IDR = 0x01;
    public static final int DG_FLAG_CONFIG = 0x02;
    public static final int DG_FLAG_LAST_SLICE = 0x04;

    /** flags 已定义位（bit3..bit7 保留：收到非 0 不报错，但不得当语义用） */
    public static final int DG_FLAG_ALL = DG_FLAG_IDR | DG_FLAG_CONFIG | DG_FLAG_LAST_SLICE;

    /** 音频 / ctrl / feedback **不分片**：sliceIdx / sliceCount 恒 0 / 1（§4.4，编解码两侧都硬校验） */
    public static final int DG_SLICE_IDX_NONE = 0;
    public static final int DG_SLICE_COUNT_NONE = 1;

    /**
     * ctrl/state_snapshot 的 payload **定长 14B**（不含子类型首字节）：
     * epoch u16 | seq u32 | ownerBits u8 | videoLevel u8 | videoBrKbps u16 |
     * videoFps u8 | audioBrKbps u8 | flags u16
     */
    public static final int DG_SNAPSHOT_LEN = 14;

    /** 快照那份 ctrl 报的固定正文：1B 子类型 + 14B ⇒ 整报 = 12 + 15 = 27B，远在 64B 以内 */
    public static final int DG_SNAPSHOT_BODY = 1 + DG_SNAPSHOT_LEN;

    public static final int SNAP_OFF_EPOCH = 0;         // u16 本次媒体会话号
    public static final int SNAP_OFF_SEQ = 2;           // u32 会话内单调递增
    public static final int SNAP_OFF_OWNER = 6;         // u8
    public static final int SNAP_OFF_VIDEO_LEVEL = 7;   // u8
    public static final int SNAP_OFF_VIDEO_BR = 8;      // u16 kbps
    public static final int SNAP_OFF_VIDEO_FPS = 10;    // u8
    public static final int SNAP_OFF_AUDIO_BR = 11;     // u8 kbps
    public static final int SNAP_OFF_FLAGS = 12;        // u16

    /**
     * ownerBits（[6]）：这份快照由哪一组 owner 决策（§3-D14-1 的两组各占一位，其余保留）。
     * ⚠ 这里只定**位含义**；判权规则（非 owner 写来的同名字段忽略并计 ctrlOwnerViolation）在 3.13。
     */
    public static final int OWNER_BIT_PUSH = 0x01;
    public static final int OWNER_BIT_RECV = 0x02;

    /**
     * 快照 flags（[12..13] 低 7 位）：**位序 = §4.4 括号里的书写序，一位不多一位不少** ——
     * aggregated / camOn / muted / sendingPaused / wantVideo / needKeyframe / bg。
     * 前四位是推流端 owner 组、后三位是收流端组（顺序照 D14-1 的字段表，不是按字母）。
     */
    public static final int SNAP_FLAG_AGGREGATED = 0x0001;
    public static final int SNAP_FLAG_CAM_ON = 0x0002;
    public static final int SNAP_FLAG_MUTED = 0x0004;
    public static final int SNAP_FLAG_SENDING_PAUSED = 0x0008;
    public static final int SNAP_FLAG_WANT_VIDEO = 0x0010;
    public static final int SNAP_FLAG_NEED_KEYFRAME = 0x0020;
    public static final int SNAP_FLAG_BG = 0x0040;

    /** flags 已定义位掩码（bit7..bit15 保留） */
    public static final int SNAP_FLAG_ALL = 0x007F;

    public static final String APP_TAG = "PPTransfer";

    private Protocol() {
    }

    public static void write(OutputStream out, Map<String, Object> msg) throws IOException {
        byte[] body = Json.stringify(msg).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME) {
            throw new IOException("帧过大: " + body.length);
        }
        byte[] len = new byte[4];
        len[0] = (byte) (body.length >>> 24);
        len[1] = (byte) (body.length >>> 16);
        len[2] = (byte) (body.length >>> 8);
        len[3] = (byte) body.length;
        out.write(len);
        out.write(body);
        out.flush();
    }

    /**
     * 写一个二进制文件分片帧。
     * 载荷 = [1B 子类型][1B midLen][mid UTF-8][4B seq 大端][data...]，一次性写出后 flush。
     */
    public static void writeChunk(OutputStream out, String mid, int seq,
                                  byte[] data, int off, int len) throws IOException {
        byte[] midB = mid.getBytes(StandardCharsets.UTF_8);
        if (midB.length > 255) {
            throw new IOException("mid 过长");
        }
        int payloadLen = 1 + 1 + midB.length + 4 + len;
        if (payloadLen > MAX_FRAME) {
            throw new IOException("分片帧过大: " + payloadLen);
        }
        int fl = BIN_FLAG | payloadLen;
        out.write(new byte[]{
                (byte) (fl >>> 24), (byte) (fl >>> 16), (byte) (fl >>> 8), (byte) fl
        });
        byte[] head = new byte[2 + midB.length + 4];
        head[0] = BIN_CHUNK;
        head[1] = (byte) midB.length;
        System.arraycopy(midB, 0, head, 2, midB.length);
        head[2 + midB.length] = (byte) (seq >>> 24);
        head[3 + midB.length] = (byte) (seq >>> 16);
        head[4 + midB.length] = (byte) (seq >>> 8);
        head[5 + midB.length] = (byte) seq;
        out.write(head);
        out.write(data, off, len);
        out.flush();
    }

    /** 一帧：JSON 控制帧或二进制分片帧 */
    public static abstract class Frame {
        /** JSON 控制帧 */
        public static final class JsonF extends Frame {
            public final Map<String, Object> j;

            JsonF(Map<String, Object> j) {
                this.j = j;
            }
        }

        /** 二进制文件分片帧；data 有效范围 [0, len) */
        public static final class Chunk extends Frame {
            public final String mid;
            public final int seq;
            public final byte[] data;
            public final int len;

            Chunk(String mid, int seq, byte[] data, int len) {
                this.mid = mid;
                this.seq = seq;
                this.data = data;
                this.len = len;
            }
        }

        /**
         * 语音音频帧：seq 单调递增；ts 为发送端**通话相对**时间戳（对端起呼=0 的 ms，
         * 4B 无符号；抖动缓冲对时/帧龄用，两端各自零点，接收端不拿它与本端墙钟相减）。
         */
        public static final class Audio extends Frame {
            public final int seq;
            public final long ts;
            public final byte[] data;
            public final int len;

            Audio(int seq, long ts, byte[] data, int len) {
                this.seq = seq;
                this.ts = ts;
                this.data = data;
                this.len = len;
            }
        }

        /** 视频帧：seq 单调递增；ts 为发送端**通话相对**时间戳（对端起呼=0 的 ms，与音频同零点）；
         *  flag 见 VIDEO_FLAG_* */
        public static final class Video extends Frame {
            public final int seq;
            public final long ts;
            public final int flag;
            public final byte[] data;
            public final int len;

            Video(int seq, long ts, int flag, byte[] data, int len) {
                this.seq = seq;
                this.ts = ts;
                this.flag = flag;
                this.data = data;
                this.len = len;
            }

            public boolean keyframe() {
                return (flag & VIDEO_FLAG_KEYFRAME) != 0;
            }

            public boolean config() {
                return (flag & VIDEO_FLAG_CONFIG) != 0;
            }
        }
    }

    /** @return 帧内容；流正常结束返回 null */
    public static Frame readFrame(InputStream in) throws IOException {        byte[] len = readFully(in, 4);
        if (len == null) {
            return null;
        }
        int v = ((len[0] & 0xFF) << 24) | ((len[1] & 0xFF) << 16) | ((len[2] & 0xFF) << 8) | (len[3] & 0xFF);
        boolean binary = (v & BIN_FLAG) != 0;
        int n = v & (BIN_FLAG - 1);
        if (n <= 0 || n > MAX_FRAME) {
            throw new IOException("非法帧长度: " + v);
        }
        if (!binary) {
            byte[] body = readFully(in, n);
            if (body == null) {
                throw new EOFException("帧体不完整");
            }
            return new Frame.JsonF(Json.parseObject(new String(body, StandardCharsets.UTF_8)));
        }
        int sub = in.read();
        if (sub == (BIN_AUDIO & 0xFF)) {
            int seq = readInt(in);
            long ts = readInt(in) & 0xFFFFFFFFL;
            int dataLen = n - 1 - 8;
            if (dataLen < 0) {
                throw new IOException("音频帧长度不符: " + n);
            }
            byte[] adata = new byte[dataLen];
            if (dataLen > 0) {
                byte[] d = readFully(in, dataLen);
                if (d == null) {
                    throw new EOFException("音频数据不完整");
                }
                adata = d;
            }
            return new Frame.Audio(seq, ts, adata, dataLen);
        }
        if (sub != (BIN_CHUNK & 0xFF)) {
            if (sub == (BIN_VIDEO & 0xFF)) {
                int vseq = readInt(in);
                long vts = readInt(in) & 0xFFFFFFFFL;
                int flag = in.read() & 0xFF;
                int vlen = n - 1 - 8 - 1;
                if (vlen < 0) {
                    throw new IOException("视频帧长度不符: " + n);
                }
                byte[] vdata = new byte[vlen];
                if (vlen > 0) {
                    byte[] d = readFully(in, vlen);
                    if (d == null) {
                        throw new EOFException("视频数据不完整");
                    }
                    vdata = d;
                }
                return new Frame.Video(vseq, vts, flag, vdata, vlen);
            }
            throw new IOException("未知二进制帧子类型: " + sub);
        }
        int midLen = in.read() & 0xFF;
        byte[] midB = readFully(in, midLen);
        if (midB == null) {
            throw new EOFException("二进制帧头不完整");
        }
        int seq = readInt(in);
        int dataLen = n - 2 - midLen - 4;
        if (dataLen < 0) {
            throw new IOException("二进制帧长度不符: " + n);
        }
        byte[] data = new byte[dataLen];
        if (dataLen > 0) {
            byte[] d = readFully(in, dataLen);
            if (d == null) {
                throw new EOFException("分片数据不完整");
            }
            data = d;
        }
        return new Frame.Chunk(new String(midB, StandardCharsets.UTF_8), seq, data, dataLen);
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] b = readFully(in, 4);
        if (b == null) {
            throw new EOFException("二进制帧头不完整");
        }
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        if (n == 0) {
            return new byte[0];
        }
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                return null;
            }
            off += r;
        }
        return buf;
    }

    // ==================== 消息构造 ====================

    public static Map<String, Object> msg(String type) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("ts", System.currentTimeMillis());
        m.put("v", Integer.valueOf(PROTO_VER));
        return m;
    }

    public static Map<String, Object> hello(String id, String name, String model) {
        return hello(id, name, model, false, false, null);
    }

    /**
     * 握手 + 视频能力协商（与 Android 端同构）。
     *
     * 老版本对端不认识 {@code vid/cam/vcodec} 字段 → 天然忽略，因此**不发生断链**；
     * 而新版本只有在看到对端 {@code vid=true} 时才允许发视频二进制帧
     * （老端收到未知二进制子类型 0x03 会直接 IOException 断链，见 readFrame）。
     *
     * @param vid   本端**可接收**视频
     * @param cam   本端**有摄像头可发送**（PC 恒 false —— R2 只收不发）
     * @param vcodec 本端**可解码**的编码列表，如 ["hevc","h264"]
     */
    public static Map<String, Object> hello(String id, String name, String model,
                                            boolean vid, boolean cam,
                                            java.util.List<String> vcodec) {
        Map<String, Object> m = msg("hello");
        m.put("id", id);
        m.put("name", name);
        m.put("model", model);
        m.put("ver", Integer.valueOf(PROTO_VER));
        m.put("vid", Boolean.valueOf(vid));
        m.put("cam", Boolean.valueOf(cam));
        m.put("vcodec", vcodec == null ? new java.util.ArrayList<String>() : vcodec);
        m.put("hs", Boolean.TRUE);
        m.put("hd", Boolean.TRUE);
        m.put("dl", Boolean.TRUE);
        // 数据流量确认（v2.7）：PC 恒为「不在运营商数据网络」，收到 data_ask 一律自动同意
        m.put("dm", Boolean.TRUE);
        return m;
    }

    // ==================== 数据流量确认（v2.7，仅双方 dm=1 时使用）====================

    /**
     * 发起方 → 对端：用数据流量做这件事，你同意吗？（语义见 Android 端同构实现）
     * kind: file / voice / video；name 为文件（夹）名，通话为空。
     */
    public static Map<String, Object> dataAsk(String mid, String kind, String name, long size) {
        Map<String, Object> m = msg("data_ask");
        m.put("mid", mid);
        m.put("kind", kind);
        m.put("name", name == null ? "" : name);
        m.put("size", Long.valueOf(size));
        return m;
    }

    /** 对端 → 发起方：是否同意本次用数据流量接收 */
    public static Map<String, Object> dataAns(String mid, boolean ok) {
        Map<String, Object> m = msg("data_ans");
        m.put("mid", mid);
        m.put("ok", Boolean.valueOf(ok));
        return m;
    }

    // ==================== 消息删除同步（v2.4，仅双方 hd=1 时使用）====================

    /**
     * 通知对端「这些消息已在本机删除」，对端同步移除（只对文本/图片发；
     * 文件与文件夹的删除只影响本机，不发此帧）。对端收到后**不再回传**，无回环。
     */
    public static Map<String, Object> msgDel(java.util.List<String> mids) {
        Map<String, Object> m = msg("msg_del");
        m.put("mids", mids);
        return m;
    }

    // ==================== 历史同步（v2.3，仅双方 hs=1 时使用）====================

    /** 握手后互发：本机持有的该对端消息 mid 清单（最近 HSYNC_SCAN 条，TEXT + 图片 FILE） */
    public static Map<String, Object> hsyncMids(java.util.List<String> mids) {
        Map<String, Object> m = msg("hsync_mids");
        m.put("mids", mids);
        return m;
    }

    /** 请求对端补发缺失消息的内容（mid 清单） */
    public static Map<String, Object> hsyncPull(java.util.List<String> mids) {
        Map<String, Object> m = msg("hsync_pull");
        m.put("mids", mids);
        return m;
    }

    /**
     * 补发文本消息内容。dir 是**对端视角**的方向（本端持有的 SELF 翻转为 PEER，反之亦然）。
     * 文件不走这里 —— 沿用现有 file_meta/chunks/file_done 管线按**原 mid** 重发。
     */
    public static Map<String, Object> hsyncMsg(java.util.List<Map<String, Object>> items) {
        Map<String, Object> m = msg("hsync_msg");
        m.put("items", items);
        return m;
    }

    public static Map<String, Object> ping() {
        return msg("ping");
    }

    public static Map<String, Object> pong() {
        return msg("pong");
    }

    /** 拒绝连接：本机已有活跃会话（holder=正在会话的对端名），请求方应退避排队 */
    public static Map<String, Object> busy(String holder) {
        Map<String, Object> m = msg("busy");
        m.put("holder", holder);
        return m;
    }

    public static Map<String, Object> text(String mid, String text) {
        Map<String, Object> m = msg("text");
        m.put("mid", mid);
        m.put("text", text);
        return m;
    }

    public static Map<String, Object> clip(String mid, String text, String sha1) {
        Map<String, Object> m = msg("clip");
        m.put("mid", mid);
        m.put("text", text);
        m.put("sha1", sha1);
        return m;
    }

    public static Map<String, Object> clipNotify(int len, String sha1, String preview) {
        Map<String, Object> m = msg("clip_notify");
        m.put("len", Integer.valueOf(len));
        m.put("sha1", sha1);
        m.put("preview", preview);
        return m;
    }

    public static Map<String, Object> clipState(String text, String sha1) {
        Map<String, Object> m = msg("clip_state");
        m.put("text", text == null ? "" : text);
        m.put("sha1", sha1 == null ? "" : sha1);
        return m;
    }

    public static Map<String, Object> clipPull() {
        return msg("clip_pull");
    }

    public static Map<String, Object> syncReq() {
        return msg("sync_req");
    }

    /**
     * 文件开始。offset > 0 表示续传（从该字节起继续）；relPath 非空表示文件夹成员；
     * dirId 指向所属目录气泡的 mid。
     */
    public static Map<String, Object> fileMeta(String mid, String name, long size, String mime,
                                               String sha256, long offset, String relPath, String dirId) {
        Map<String, Object> m = msg("file_meta");
        m.put("mid", mid);
        m.put("name", name);
        m.put("size", Long.valueOf(size));
        m.put("mime", mime == null ? "application/octet-stream" : mime);
        m.put("sha256", sha256 == null ? "" : sha256);
        if (offset > 0) {
            m.put("offset", Long.valueOf(offset));
        }
        if (relPath != null && !relPath.isEmpty()) {
            m.put("relPath", relPath);
        }
        if (dirId != null && !dirId.isEmpty()) {
            m.put("dirId", dirId);
        }
        return m;
    }

    public static Map<String, Object> fileDone(String mid, String sha256) {
        Map<String, Object> m = msg("file_done");
        m.put("mid", mid);
        m.put("sha256", sha256 == null ? "" : sha256);
        return m;
    }

    /**
     * 发送方取消某个文件/文件夹传输。
     * 接收端收到后中止接收、删除半成品文件，并把该条消息从聊天界面移除（两端同步不显示）。
     */
    public static Map<String, Object> fileCancel(String mid) {
        Map<String, Object> m = msg("file_cancel");
        m.put("mid", mid);
        return m;
    }

    /** 断点续传询问：发送方开传前问对端有没有半成品 */
    public static Map<String, Object> fileQuery(String mid, String name, long size) {
        Map<String, Object> m = msg("file_query");
        m.put("mid", mid);
        m.put("name", name);
        m.put("size", Long.valueOf(size));
        return m;
    }

    /**
     * 断点续传应答。ok=true 时 offset 为对端已有字节数（对齐分片边界），
     * sha256 为前缀摘要（对端自行维护/重算，可能为空）；ok=false 从头传。
     */
    public static Map<String, Object> fileResume(String mid, boolean ok, long offset, String sha256) {
        Map<String, Object> m = msg("file_resume");
        m.put("mid", mid);
        m.put("ok", Boolean.valueOf(ok));
        m.put("offset", Long.valueOf(offset));
        m.put("sha256", sha256 == null ? "" : sha256);
        return m;
    }

    /** 文件夹传输开始：name=文件夹名，fileCount=成员数，totalSize=总字节 */
    public static Map<String, Object> dirMeta(String mid, String name, int fileCount, long totalSize,
                                              List<String> emptyDirs) {
        return dirMeta(mid, name, fileCount, totalSize, emptyDirs, null, 0);
    }

    /**
     * 文件夹传输开始（v2.6 扩充）。
     *
     * @param memberList 成员相对路径清单。接收端收到 dir_end 时用它求差集，把没到的成员
     *                   回发 dir_missing 让本端补发 —— 这是「文件夹静默缺文件」的根治手段。
     *                   为空 = 不带清单，退化为「只按数量核对」（老端行为）。
     * @param skipped    因深度/数量上限被跳过的分支数；&gt;0 时对端明确提示，不让内容无声消失
     */
    public static Map<String, Object> dirMeta(String mid, String name, int fileCount, long totalSize,
                                              List<String> emptyDirs, List<String> memberList,
                                              int skipped) {
        Map<String, Object> m = msg("dir_meta");
        m.put("mid", mid);
        m.put("name", name);
        m.put("fileCount", Integer.valueOf(fileCount));
        m.put("totalSize", Long.valueOf(totalSize));
        // 子树里没有文件的空目录（相对路径）；对端据此重建空目录结构。旧端忽略此字段
        m.put("dirs", emptyDirs == null ? new ArrayList<String>() : emptyDirs);
        if (memberList != null && !memberList.isEmpty()) {
            m.put("list", memberList);
        }
        if (skipped > 0) {
            m.put("skipped", Integer.valueOf(skipped));
        }
        return m;
    }

    // ==================== 文件夹完整性校验（v2.6，仅双方 dl=1 时使用）====================

    /**
     * 文件夹成员发完（无论成败）后的收尾帧。接收端凭此**明确知道传输结束**，
     * 立刻用 dir_meta.list 与实收清单求差集，把缺失项回发 dir_missing。
     *
     * 为什么必须有这一帧：老流程下接收端只能靠 doneFiles&gt;=count 猜「收齐了」，
     * 而成员在发送端被跳过 / 发送失败时接收端永远等不到 → 静默缺文件。
     */
    public static Map<String, Object> dirEnd(String mid, int sent, boolean last) {
        Map<String, Object> m = msg("dir_end");
        m.put("mid", mid);
        m.put("sent", Integer.valueOf(sent));
        m.put("last", Boolean.valueOf(last));
        return m;
    }

    /**
     * 接收端回执：这些成员没到（或落盘/校验失败）。发送端据此**重发缺失项**，
     * 而不是把整个文件夹当成发送成功。
     */
    public static Map<String, Object> dirMissing(String mid, List<String> items) {
        Map<String, Object> m = msg("dir_missing");
        m.put("mid", mid);
        m.put("items", items == null ? new ArrayList<String>() : items);
        return m;
    }

    /**
     * 续传完成核对：接收端把落盘文件的 MD5 回发给发送端比对（端到端完整性）。
     * 只对触发过续传的普通单文件发送；发送端异步核对，不阻塞任何线程。
     */
    public static Map<String, Object> fileVerify(String mid, String md5) {
        Map<String, Object> m = msg("file_verify");
        m.put("mid", mid);
        m.put("md5", md5 == null ? "" : md5);
        return m;
    }

    public static Map<String, Object> ack(String mid, boolean ok) {
        Map<String, Object> m = msg("ack");
        m.put("mid", mid);
        m.put("ok", Boolean.valueOf(ok));
        return m;
    }

    // ==================== 语音通话 ====================

    /**
     * 写一个语音音频帧（Opus 编码，0.2s 一帧）。
     * 载荷 = [1B 子类型 BIN_AUDIO][4B seq 大端][4B ts 大端(ms)][opus data...]。
     *
     * ts = 发送端**通话相对**毫秒（{@link CallClock#elapsedMs()}，起呼=0），4B 无符号；
     * 6h 上限通话不会回绕。禁止再塞绝对墙钟。
     */
    public static void writeAudio(OutputStream out, int seq, long ts,
                                  byte[] data, int off, int len) throws IOException {
        int payloadLen = 1 + 8 + len;
        if (payloadLen > MAX_FRAME) {
            throw new IOException("音频帧过大: " + payloadLen);
        }
        int fl = BIN_FLAG | payloadLen;
        out.write(new byte[]{
                (byte) (fl >>> 24), (byte) (fl >>> 16), (byte) (fl >>> 8), (byte) fl
        });
        out.write(BIN_AUDIO & 0xFF);
        out.write(new byte[]{
                (byte) (seq >>> 24), (byte) (seq >>> 16), (byte) (seq >>> 8), (byte) seq,
                (byte) (ts >>> 24), (byte) (ts >>> 16), (byte) (ts >>> 8), (byte) ts
        });
        if (len > 0) {
            out.write(data, off, len);
        }
        out.flush();
    }

    /** 呼叫请求（呼出方 → 被叫方）。callerName/callerIp 用于弹窗展示。 */
    public static Map<String, Object> callInvite(String callerName, String callerIp) {
        return callInvite(callerName, callerIp, false, 1, 0);
    }

    /** 呼叫请求 + 视频协商（vid=true 为视频呼叫，lv=呼叫方起始档位）。 */
    public static Map<String, Object> callInvite(String callerName, String callerIp,
                                                 boolean vid, int lv) {
        return callInvite(callerName, callerIp, vid, lv, 0);
    }

    /**
     * 呼叫请求 + 视频协商 + 语音帧长协商。
     *
     * @param af 本端期望的语音帧长（ms）：20 = 低延迟模式；0/缺省 = 未声明 → 200ms 兼容模式
     */
    public static Map<String, Object> callInvite(String callerName, String callerIp,
                                                 boolean vid, int lv, int af) {
        return callInvite(callerName, callerIp, vid, lv, af, 0, false, "");
    }

    /**
     * 呼叫请求 + 视频/语音帧长协商 + **媒体通道协商**（任务单 2.4 / 方案 §4.2）。
     *
     * <p>三个字段都是**可选、缺省即回退**：本端不声明就发 0 / false / 空串，对端读到同样的
     * 缺省值 ⇒ 自动落带内档（第五档）。老端根本不认识这些字段 ⇒ 收不到也不断链（I11 改窄：
     * 不再承诺其行为指标，但**不得因新字段断链**）。</p>
     *
     * <p>⚠ 协商字段**只有这三个**。没有 {@code cport} / {@code cip} —— 那是已被取消的独立
     * 控制 TCP 通道需要的东西（§4.2 第 5 轮口径 / U9 / N9）。</p>
     *
     * <p>⚠ 这里只有 JSON 可选字段，**没有**新增任何二进制帧子类型 —— 未知子类型会被老端
     * 直接抛 IOException 断链（本类读帧处那句 "未知二进制帧子类型"，I8）。</p>
     *
     * @param mport 本端媒体端口；0 = 未 bind / 未定档 ⇒ 对端只能走带内
     * @param mudp  本端媒体端口是否 UDP；false = 未声明（媒体 TCP / 带内）
     * @param msid  本次通话的随机 token（串话隔离用，**不是安全边界**，S12）；空 = 未声明
     */
    public static Map<String, Object> callInvite(String callerName, String callerIp,
                                                 boolean vid, int lv, int af,
                                                 int mport, boolean mudp, String msid) {
        Map<String, Object> m = msg("call_invite");
        m.put("name", callerName == null ? "" : callerName);
        m.put("ip", callerIp == null ? "" : callerIp);
        m.put("vid", Boolean.valueOf(vid));
        m.put("lv", Integer.valueOf(lv));
        m.put("af", Integer.valueOf(af));
        m.put("mport", Integer.valueOf(mport));
        m.put("mudp", Boolean.valueOf(mudp));
        m.put("msid", msid == null ? "" : msid);
        return m;
    }

    public static Map<String, Object> callAccept() {
        return callAccept(false, 1, 0);
    }

    /** 同意接听 + 视频协商（vid=接听方是否接受视频；lv=可接受档位，两端取 min）。 */
    public static Map<String, Object> callAccept(boolean vid, int lv) {
        return callAccept(vid, lv, 0);
    }

    /** 同意接听 + 视频协商 + 语音帧长协商（af 语义同 callInvite）。 */
    public static Map<String, Object> callAccept(boolean vid, int lv, int af) {
        return callAccept(vid, lv, af, 0, false, "");
    }

    /**
     * 同意接听 + 语音帧长协商 + **媒体通道协商**（字段语义与缺省回退口径同
     * {@link #callInvite(String, String, boolean, int, int, int, boolean, String)}）。
     *
     * <p>⚠ **没有**新增 ip 字段：call_accept 从来不带本机地址（对照 callInvite 独有的
     * {@code name}/{@code ip} 两项），媒体方向靠 probe + learn-by-receiving 定（任务单 2.7），
     * 不靠报文里的地址声明。</p>
     */
    public static Map<String, Object> callAccept(boolean vid, int lv, int af,
                                                 int mport, boolean mudp, String msid) {
        Map<String, Object> m = msg("call_accept");
        m.put("vid", Boolean.valueOf(vid));
        m.put("lv", Integer.valueOf(lv));
        m.put("af", Integer.valueOf(af));
        m.put("mport", Integer.valueOf(mport));
        m.put("mudp", Boolean.valueOf(mudp));
        m.put("msid", msid == null ? "" : msid);
        return m;
    }

    /**
     * 振铃期的**逐档媒体协商帧**（任务单 2.5 / 方案 §4.3① 的「交换」那一步）。
     *
     * <p>为什么需要第三条帧：§4.2 只把三元组挂在 {@code call_invite} / {@code call_accept} 上，
     * 而这两条各只有一次发言机会（主叫发 invite、被叫发 accept）。§4.3⑤ 要求「本档不通就换下一档、
     * 回到 ①」——被叫在第 2/3/4 档**没有任何帧**能把新 bind 出来的本端端口带出去，
     * 主叫就永远学不到对端的新端口，双向 probe 无法继续。
     * （2026-10-02 用户点 (a)：允许新增这条 JSON 协商帧；§4.2 标题「不新增往返」读作
     * **不新增 TCP 往返、不新增二进制子类型**，主 TCP 早已连好，多一条 JSON 帧不算往返。）</p>
     *
     * <p>⚠ 只新增 JSON 的 {@code type} 值，**没有**新增二进制帧子类型（I8：未知二进制子类型会让
     * 老端直接抛 IOException 断链）。老端读到未知 JSON type 只打一行「未知帧类型」日志、不断链
     * （{@code Link} 读帧处的 default 分支），所以 I11 的「不得断链」仍然成立。</p>
     *
     * @param mport       本端**本档** bind 到的媒体端口；0 = 本端已落带内档（第五档）
     * @param mudp        本端媒体端口是否 UDP（true = UDP 档，false = 媒体 TCP 档或带内）
     * @param msid        本端为本次通话生成的随机 token（每端各自生成，2026-10-02 用户点；
     *                    串话隔离用，**不是安全边界**，S12）
     * @param tierOrdinal 本端当前档在 {@code AvTransport.Tier} 序列里的序号。
     *                    ⚠ 两端序号**不保证相等**（安卓按 §4.1 跳过两个 443 档），
     *                    所以本字段只用于日志与「对端走到第几档」，连通性判据是 probe 本身
     */
    public static Map<String, Object> callMedia(int mport, boolean mudp, String msid, int tierOrdinal) {
        Map<String, Object> m = msg("call_media");
        m.put("mport", Integer.valueOf(mport));
        m.put("mudp", Boolean.valueOf(mudp));
        m.put("msid", msid == null ? "" : msid);
        m.put("mtier", Integer.valueOf(tierOrdinal));
        return m;
    }

    public static Map<String, Object> callReject() {
        return msg("call_reject");
    }

    /** 忙：已有通话，静默拒绝。 */
    public static Map<String, Object> callBusy() {
        return msg("call_busy");
    }

    /** 挂断（任一方发起，含 6 小时超时强制挂断）。 */
    public static Map<String, Object> callEnd(String reason) {
        Map<String, Object> m = msg("call_end");
        m.put("reason", reason == null ? "" : reason);
        return m;
    }

    public static Map<String, Object> callCancel() {
        return msg("call_cancel");
    }

    // ==================== 视频通话 ====================

    /**
     * 写一个视频帧（HEVC / H.264 Annex-B 裸流）。
     *
     * 载荷 = [1B 子类型 BIN_VIDEO][4B seq 大端][4B ts 大端(ms)][1B flag][N 字节 AU]
     * 长度字段 = BIN_FLAG | (1 + 4 + 4 + 1 + N)。
     *
     * **约定：一个包 = 恰好一个访问单元（AU）**，因此接收端不需要扫 {@code 00 00 01} 起始码，
     * 直接把载荷喂给解码器即可。ts 为**发送端通话相对毫秒**（{@link CallClock#elapsedMs()}，
     * 与同一通话的音频帧同一零点），用于 A/V 时间轴对齐；两端各自零点，接收端不拿它比本端墙钟。
     *
     * @param flag bit0=KEYFRAME(IDR)、bit1=CONFIG(参数集)，见 VIDEO_FLAG_*
     */
    public static void writeVideo(OutputStream out, int seq, long ts, int flag,
                                  byte[] data, int off, int len) throws IOException {
        if (len > MAX_VIDEO_FRAME) {
            throw new IOException("视频帧超 128KB 闸门: " + len);
        }
        int payloadLen = 1 + 8 + 1 + len;
        if (payloadLen > MAX_FRAME) {
            throw new IOException("视频帧过大: " + payloadLen);
        }
        int fl = BIN_FLAG | payloadLen;
        out.write(new byte[]{
                (byte) (fl >>> 24), (byte) (fl >>> 16), (byte) (fl >>> 8), (byte) fl
        });
        out.write(BIN_VIDEO & 0xFF);
        out.write(new byte[]{
                (byte) (seq >>> 24), (byte) (seq >>> 16), (byte) (seq >>> 8), (byte) seq,
                (byte) (ts >>> 24), (byte) (ts >>> 16), (byte) (ts >>> 8), (byte) ts,
                (byte) flag
        });
        if (len > 0) {
            out.write(data, off, len);
        }
        out.flush();
    }

    /** 编码参数变更通知（发送方 → 接收方）。改分辨率时接收方要重启解码器。 */
    public static Map<String, Object> videoCfg(String codec, int w, int h,
                                               int fps, int br, int rot) {
        Map<String, Object> m = msg("video_cfg");
        m.put("codec", codec == null ? "" : codec);
        m.put("w", Integer.valueOf(w));
        m.put("h", Integer.valueOf(h));
        m.put("fps", Integer.valueOf(fps));
        m.put("br", Integer.valueOf(br));
        m.put("rot", Integer.valueOf(rot));
        return m;
    }

    /**
     * 接收侧健康反馈（接收方 → 发送方，每 2s 一次），供发送侧升降档判断。
     *
     * @param fps      实际解码帧率
     * @param drop     丢帧百分比
     * @param lat      排队延迟 ms（**真实值**；任务单 1.3 起不再用 -1 当丢帧哨兵）
     * @param dropping 接收端此刻是否正处于丢帧期（与 lat 分开说，两者不再混在一个数里）
     * @param kbps     实际接收码率
     */
    public static Map<String, Object> videoFb(int fps, int drop, int lat, boolean dropping, int kbps) {
        Map<String, Object> m = msg("video_fb");
        m.put("fps", Integer.valueOf(fps));
        m.put("drop", Integer.valueOf(drop));
        m.put("lat", Integer.valueOf(lat));
        m.put("dropping", Boolean.valueOf(dropping));
        m.put("kbps", Integer.valueOf(kbps));
        return m;
    }

    /** 请求对端临时停/开视频（如接收端切后台） */
    public static Map<String, Object> videoReq(boolean on) {
        return videoReq(on, false);
    }

    /**
     * 请求对端临时停/开视频。
     *
     * @param key true = 同时请求**立刻一个 IDR**（本端解码进程刚换代，只能从关键帧起解，
     *            而编码器 GOP=1s ⇒ 不主动要就要白等最多一秒）。只在 true 时落键：老对端收到的
     *            帧与改前逐字节一致，新对端缺键按 false（I11）。
     */
    public static Map<String, Object> videoReq(boolean on, boolean key) {
        Map<String, Object> m = msg("video_req");
        m.put("on", Boolean.valueOf(on));
        if (key) {
            m.put("key", Boolean.TRUE);
        }
        return m;
    }

    // ==================== 媒体数据报编解码（任务单 3.1）====================

    /**
     * 一份解码后的媒体数据报。{@code payload} **含** ctrl 子类型首字节（原样保留，本类不猜语义）。
     */
    public static final class Datagram {
        public final int ver;
        public final int type;
        /** 按 type 独立计数的流内序号（S10）；16 位，回绕由调用方自己处理 */
        public final int streamSeq;
        public final int flags;
        public final int sliceIdx;
        public final int sliceCount;
        /** 发送端通话相对毫秒 */
        public final long ts;
        public final byte[] payload;
        /** payload 有效长度 */
        public final int len;

        Datagram(int ver, int type, int streamSeq, int flags, int sliceIdx, int sliceCount,
                 long ts, byte[] payload, int len) {
            this.ver = ver;
            this.type = type;
            this.streamSeq = streamSeq;
            this.flags = flags;
            this.sliceIdx = sliceIdx;
            this.sliceCount = sliceCount;
            this.ts = ts;
            this.payload = payload;
            this.len = len;
        }

        public boolean flag(int bit) {
            return (flags & bit) != 0;
        }

        /** ctrl 子类型；非 ctrl 报或载荷为空 ⇒ -1（调用方丢弃，不当成"子类型 0"） */
        public int ctrlSubtype() {
            if (type != DG_TYPE_CTRL || len < 1) {
                return -1;
            }
            return payload[0] & 0xFF;
        }
    }

    /** 14B 快照的解码结果。字段与 {@link AvTransport.Ctrl} 一一对应；
     *  两者的映射与 owner 判权归 3.13，本类只管字节。 */
    public static final class Snapshot {
        public final int epoch;
        public final long seq;
        public final int ownerBits;
        public final int videoLevel;
        public final int videoBrKbps;
        public final int videoFps;
        public final int audioBrKbps;
        public final int flags;

        Snapshot(int epoch, long seq, int ownerBits, int videoLevel, int videoBrKbps,
                 int videoFps, int audioBrKbps, int flags) {
            this.epoch = epoch;
            this.seq = seq;
            this.ownerBits = ownerBits;
            this.videoLevel = videoLevel;
            this.videoBrKbps = videoBrKbps;
            this.videoFps = videoFps;
            this.audioBrKbps = audioBrKbps;
            this.flags = flags;
        }

        public boolean flag(int bit) {
            return (flags & bit) != 0;
        }

        public boolean pushOwner() {
            return (ownerBits & OWNER_BIT_PUSH) != 0;
        }

        public boolean recvOwner() {
            return (ownerBits & OWNER_BIT_RECV) != 0;
        }
    }

    /**
     * 编一份数据报。
     *
     * @param type       {@code DG_TYPE_*}
     * @param streamSeq  该流的独立序号（0–65535）
     * @param flags      {@code DG_FLAG_*} 的按位或；非视频报传 0
     * @param sliceIdx   视频切片下标；**非视频报必须 0**
     * @param sliceCount 该帧总切片数；**非视频报必须 1**
     * @param payload    可为 null（此时 len 必须为 0）；ctrl 报的子类型首字节请走
     *                   {@link #encodeCtrl}，不要手工塞
     */
    public static byte[] encodeDatagram(int type, int streamSeq, int flags,
                                        int sliceIdx, int sliceCount, long ts,
                                        byte[] payload, int off, int len) throws IOException {
        checkType(type);
        checkU16("streamSeq", streamSeq);
        checkU16("sliceIdx", sliceIdx);
        checkU16("sliceCount", sliceCount);
        if (type != DG_TYPE_VIDEO_SLICE
                && (sliceIdx != DG_SLICE_IDX_NONE || sliceCount != DG_SLICE_COUNT_NONE)) {
            throw new IOException("非视频报的分片字段恒 0/1，实际 " + sliceIdx + "/" + sliceCount);
        }
        // 视频报自己的形状约束：一帧至少 1 片，下标从 0 起 —— 这是 8B 头**内部**的不变式，
        // 不是 3.2 的重组策略（那里才管"缺任一片整帧丢"）。放在这里，重组缓冲就永远
        // 见不到 idx>=count 这种不可能的组合。
        if (type == DG_TYPE_VIDEO_SLICE && (sliceCount < DG_SLICE_COUNT_NONE || sliceIdx >= sliceCount)) {
            throw new IOException("视频分片形状非法: idx=" + sliceIdx + " count=" + sliceCount);
        }
        if (len < 0 || (payload == null && len > 0)) {
            throw new IOException("数据报载荷非法: " + len);
        }
        checkTs(ts);
        byte[] b = new byte[DG_OVERHEAD + len];
        b[0] = (byte) ((DG_VER << DG_VER_SHIFT) | type);
        putU16(b, 1, streamSeq);
        b[3] = (byte) flags;
        putU16(b, 4, sliceIdx);
        putU16(b, 6, sliceCount);
        putU32(b, DG_HEADER_LEN, ts);
        if (len > 0) {
            System.arraycopy(payload, off, b, DG_OVERHEAD, len);
        }
        return b;
    }

    /**
     * 编一份 ctrl 数据报：子类型首字节由本方法写入，"整报 ≤64B" 也只在**这一处**守
     * （ctrl 的五个出口 —— 2.5 的 probe、3.9 的 keyframe_req、3.13 的快照，加 stop_resume /
     * level_sync —— 全从这里走，所以校验只需要一个地方）。
     *
     * @param body 子类型之后的正文，可为 null；probe 的 msid 就放这里
     */
    public static byte[] encodeCtrl(int ctrlSubtype, int streamSeq, long ts,
                                    byte[] body, int off, int len) throws IOException {
        if (ctrlSubtype < CT_PROBE || ctrlSubtype > CT_STATE_SNAPSHOT) {
            throw new IOException("未知 ctrl 子类型: " + ctrlSubtype);
        }
        if (len < 0 || (body == null && len > 0)) {
            throw new IOException("ctrl 正文非法: " + len);
        }
        int report = DG_OVERHEAD + 1 + len;
        if (report > DG_CTRL_MAX_REPORT) {
            throw new IOException("ctrl 整报超 " + DG_CTRL_MAX_REPORT + "B: " + report);
        }
        byte[] payload = new byte[1 + len];
        payload[0] = (byte) ctrlSubtype;
        if (len > 0) {
            System.arraycopy(body, off, payload, 1, len);
        }
        return encodeDatagram(DG_TYPE_CTRL, streamSeq, 0,
                DG_SLICE_IDX_NONE, DG_SLICE_COUNT_NONE, ts, payload, 0, payload.length);
    }

    /** 把一份控制状态写成定长 14B 正文（不含子类型首字节）。字段顺序 = §4.4 的书写顺序。 */
    public static byte[] encodeSnapshot(int epoch, long seq, int ownerBits, int videoLevel,
                                        int videoBrKbps, int videoFps, int audioBrKbps,
                                        int flags) throws IOException {
        checkU16("epoch", epoch);
        if (seq < 0 || seq > 0xFFFFFFFFL) {
            throw new IOException("seq 超 32 位: " + seq);
        }
        checkU8("ownerBits", ownerBits);
        checkU8("videoLevel", videoLevel);
        checkU16("videoBrKbps", videoBrKbps);
        checkU8("videoFps", videoFps);
        checkU8("audioBrKbps", audioBrKbps);
        checkU16("flags", flags);
        byte[] b = new byte[DG_SNAPSHOT_LEN];
        putU16(b, SNAP_OFF_EPOCH, epoch);
        putU32(b, SNAP_OFF_SEQ, seq);
        b[SNAP_OFF_OWNER] = (byte) ownerBits;
        b[SNAP_OFF_VIDEO_LEVEL] = (byte) videoLevel;
        putU16(b, SNAP_OFF_VIDEO_BR, videoBrKbps);
        b[SNAP_OFF_VIDEO_FPS] = (byte) videoFps;
        b[SNAP_OFF_AUDIO_BR] = (byte) audioBrKbps;
        putU16(b, SNAP_OFF_FLAGS, flags);
        return b;
    }

    /** 定长 14B 快照 + 子类型 → 一份 ctrl 报（恒 27B） */
    public static byte[] encodeSnapshotCtrl(int streamSeq, long ts,
                                            int epoch, long seq, int ownerBits, int videoLevel,
                                            int videoBrKbps, int videoFps, int audioBrKbps,
                                            int flags) throws IOException {
        byte[] body = encodeSnapshot(epoch, seq, ownerBits, videoLevel, videoBrKbps,
                videoFps, audioBrKbps, flags);
        return encodeCtrl(CT_STATE_SNAPSHOT, streamSeq, ts, body, 0, body.length);
    }

    /**
     * 解一份数据报。
     *
     * <p>畸形报（长度不足 / 版本不认识 / 类型未知 / 非视频报却带了分片）**一律抛 IOException**：
     * UDP 收包侧 catch 住直接丢这一份就够，不许吞成"看着像空报"的返回值 ——
     * 半解的报会被当成合法帧送进重组缓冲，那就是 3.2 的"缺任一片整帧丢"永远不触发。</p>
     */
    public static Datagram decodeDatagram(byte[] buf, int off, int len) throws IOException {
        if (buf == null || len < DG_OVERHEAD) {
            throw new IOException("数据报过短: " + len);
        }
        if (off < 0 || off + len > buf.length) {
            throw new IOException("数据报越界: off=" + off + " len=" + len + " cap=" + buf.length);
        }
        int b0 = buf[off] & 0xFF;
        int ver = b0 >>> DG_VER_SHIFT;
        int type = b0 & DG_TYPE_MASK;
        if (ver != DG_VER) {
            throw new IOException("数据报版本不认识: " + ver);
        }
        checkType(type);
        int streamSeq = getU16(buf, off + 1);
        int flags = buf[off + 3] & 0xFF;
        int sliceIdx = getU16(buf, off + 4);
        int sliceCount = getU16(buf, off + 6);
        if (type != DG_TYPE_VIDEO_SLICE
                && (sliceIdx != DG_SLICE_IDX_NONE || sliceCount != DG_SLICE_COUNT_NONE)) {
            throw new IOException("非视频报的分片字段恒 0/1，实际 " + sliceIdx + "/" + sliceCount);
        }
        if (type == DG_TYPE_VIDEO_SLICE && (sliceCount < DG_SLICE_COUNT_NONE || sliceIdx >= sliceCount)) {
            throw new IOException("视频分片形状非法: idx=" + sliceIdx + " count=" + sliceCount);
        }
        long ts = getU32(buf, off + DG_HEADER_LEN);
        int payloadLen = len - DG_OVERHEAD;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(buf, off + DG_OVERHEAD, payload, 0, payloadLen);
        }
        return new Datagram(ver, type, streamSeq, flags, sliceIdx, sliceCount, ts, payload, payloadLen);
    }

    /**
     * 从 ctrl 报的正文（**已去掉子类型首字节**）解出 14B 快照。
     * 定长是硬要求：长度不等直接抛，不做"截断凑数"（§4.4 写的是**定长** 14B）。
     */
    public static Snapshot decodeSnapshotBody(byte[] body, int off, int len) throws IOException {
        if (len != DG_SNAPSHOT_LEN) {
            throw new IOException("快照正文必须定长 " + DG_SNAPSHOT_LEN + "B，实际 " + len);
        }
        if (body == null || off < 0 || off + len > body.length) {
            throw new IOException("快照正文越界");
        }
        return new Snapshot(
                getU16(body, off + SNAP_OFF_EPOCH),
                getU32(body, off + SNAP_OFF_SEQ),
                body[off + SNAP_OFF_OWNER] & 0xFF,
                body[off + SNAP_OFF_VIDEO_LEVEL] & 0xFF,
                getU16(body, off + SNAP_OFF_VIDEO_BR),
                body[off + SNAP_OFF_VIDEO_FPS] & 0xFF,
                body[off + SNAP_OFF_AUDIO_BR] & 0xFF,
                getU16(body, off + SNAP_OFF_FLAGS));
    }

    /** 从数据报直接解快照：类型、子类型、正文长度三项都核对，任一不符抛 */
    public static Snapshot decodeSnapshot(Datagram dg) throws IOException {
        if (dg == null) {
            throw new IOException("空数据报");
        }
        if (dg.type != DG_TYPE_CTRL) {
            throw new IOException("不是 ctrl 报: type=" + dg.type);
        }
        if (dg.ctrlSubtype() != CT_STATE_SNAPSHOT) {
            throw new IOException("ctrl 子类型不是 state_snapshot: " + dg.ctrlSubtype());
        }
        if (dg.len != DG_SNAPSHOT_BODY) {
            throw new IOException("快照报正文必须定长 " + DG_SNAPSHOT_BODY + "B，实际 " + dg.len);
        }
        return decodeSnapshotBody(dg.payload, 1, dg.len - 1);
    }

    private static void checkType(int type) throws IOException {
        if (type < DG_TYPE_AUDIO || type > DG_TYPE_FEEDBACK) {
            throw new IOException("未知数据报类型: " + type);
        }
    }

    private static void checkTs(long ts) throws IOException {
        if (ts < 0 || ts > 0xFFFFFFFFL) {
            throw new IOException("ts 必须是通话相对无符号 32 位毫秒: " + ts);
        }
    }

    private static void checkU8(String name, int v) throws IOException {
        if (v < 0 || v > 0xFF) {
            throw new IOException(name + " 超 8 位: " + v);
        }
    }

    private static void checkU16(String name, int v) throws IOException {
        if (v < 0 || v > 0xFFFF) {
            throw new IOException(name + " 超 16 位: " + v);
        }
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static int getU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static long getU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    // ==================== 兼容垫片（TestClient 等旧调用方） ====================

    /** 旧版读取入口：只认 JSON 帧，遇到 v2 二进制分片帧会抛错（调试工具够用） */
    public static Map<String, Object> read(InputStream in) throws IOException {
        Frame f = readFrame(in);
        if (f instanceof Frame.JsonF) {
            return ((Frame.JsonF) f).j;
        }
        throw new IOException("收到 v2 二进制帧，旧 read() 不支持（TestClient 请勿用于收文件）");
    }

    /** 旧版 5 参 fileMeta（无续传/目录字段） */
    public static Map<String, Object> fileMeta(String mid, String name, long size,
                                               String mime, String sha256) {
        return fileMeta(mid, name, size, mime, sha256, 0L, "", "");
    }

    /** 旧版 base64 JSON 分片（v1 兼容，仅 TestClient 使用） */
    public static Map<String, Object> fileChunk(String mid, int seq, String b64) {
        Map<String, Object> m = msg("file_chunk");
        m.put("mid", mid);
        m.put("seq", Integer.valueOf(seq));
        m.put("data", b64);
        return m;
    }
}
