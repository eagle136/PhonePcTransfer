package com.phonepctransfer.pc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一条聊天消息（与 Android 端 MsgItem 对齐） */
public final class Msg {

    public static final int DIR_SELF = 0;
    public static final int DIR_PEER = 1;


    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_CLIP = "CLIP";
    public static final String KIND_FILE = "FILE";
    /** 通话记录（v2.7）：语音/视频通话结束后在本机记一条，文本形如「语音通话 00:12」 */
    public static final String KIND_CALL = "CALL";

    public static final String ST_OK = "OK";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_SENDING = "SENDING";
    public static final String ST_FAILED = "FAILED";
    /**
     * 数据流量未同意（v2.7，文件专用）：对端拒绝为本次传输消耗数据流量。
     * 消息保留在聊天里，末尾显示红色感叹号（点一下重试发送）。
     */
    public static final String ST_DENIED = "DENIED";
    /**
     * 接收停滞暂停（v2.8）：30s 无任何进度（对端半死但 TCP 心跳正常）→ 中止本路接收，
     * 半成品保留（不删文件、不清注册表），对端重连后 file_query 自动续传。
     * 气泡渲染灰色 ⏸ + 「已收 pct%，重连后自动续传」；file_meta 重到 / 续传首 chunk 到达时回 SENDING。
     */
    public static final String ST_PAUSED = "PAUSED";

    public String mid = "";
    public int dir = DIR_PEER;
    public String kind = KIND_TEXT;
    /**
     * 这条消息属于哪台对端设备（对端 IP）。
     *
     * 多设备同时在线时，聊天区、历史文件、待发队列一律按它分区：
     *  - 收到：取帧所在 TCP 会话的对端 IP（socket 身份即归属，协议无需带路由字段）
     *  - 发出：取发送那一刻「当前选中设备」的 IP，之后即使切换会话也不会改变归属
     * 所以 A→B 的内容只会写进 B 的会话，不会漏到 C 的聊天记录里。
     */
    public String peerIp = "";
    public String text = "";
    public String fileName = "";
    public long fileSize = 0L;
    public String filePath = "";
    public long ts = System.currentTimeMillis();
    public String status = ST_OK;
    public int progress = -1;
    /** 文件夹传输气泡（dir_meta）：该条是一个文件夹而不是单个文件 */
    public boolean isDir = false;
    /** 文件夹传输的成员文件数（isDir=true 时有效） */
    public int dirCount = 0;

    public boolean isSelf() {
        return dir == DIR_SELF;
    }

    public String timeText() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(ts));
    }

    public String statusSuffix() {
        if (ST_PENDING.equals(status)) {
            return " · 待发送";
        }
        if (ST_SENDING.equals(status)) {
            return " · 发送中";
        }
        if (ST_FAILED.equals(status)) {
            return " · 失败";
        }
        // DENIED 的说明已经摊在气泡里（文件名下方 + 末尾红色叹号），这里不再叠字
        if (ST_PAUSED.equals(status)) {
            return " · 已暂停";
        }
        return "";
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mid", mid);
        m.put("peer", peerIp == null ? "" : peerIp);
        m.put("dir", dir == DIR_SELF ? "SELF" : "PEER");
        m.put("kind", kind);
        m.put("text", text);
        m.put("fileName", fileName);
        m.put("fileSize", Long.valueOf(fileSize));
        m.put("filePath", filePath);
        m.put("ts", Long.valueOf(ts));
        m.put("status", status);
        m.put("isDir", Boolean.valueOf(isDir));
        m.put("dirCount", Integer.valueOf(dirCount));
        return m;
    }
}
