package com.phonepctransfer.data

import org.json.JSONObject

enum class Dir { SELF, PEER }

enum class Kind {
    TEXT, CLIP, FILE,

    /** 通话记录：通话结束后本机记一条（通过话→时长，未接通→原因），不跨端同步 */
    CALL
}

enum class Status {
    OK, PENDING, SENDING, FAILED,

    /** 数据流量未同意（文件专用）：消息保留在聊天里，末尾显示可点击红色叹号（点了重新走提醒） */
    DENIED,

    /** 接收停滞暂停：30s 无进度→中止本路接收并保留半成品，对端重连后自动续传（file_meta 重到/续传首 chunk 到达时回 SENDING） */
    PAUSED
}

/** 一条聊天消息（两端 UI 一致的数据模型） */
data class MsgItem(
    val mid: String,
    val dir: Dir,
    val kind: Kind,
    val text: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    /** 发出时=content Uri 字符串；接收时=落盘后的路径/描述 */
    val filePath: String = "",
    /** 可直接再次读取的文件地址（图片靠它加载缩略图/原图）；filePath 是人看文案，不能直接打开 */
    val localUri: String = "",
    val ts: Long = System.currentTimeMillis(),
    val status: Status = Status.OK,
    /** 0..100，-1 表示无进度 */
    val progress: Int = -1,
    /** 文件夹传输气泡（dir_meta）：该条是一个文件夹而不是单个文件 */
    val isDir: Boolean = false,
    /** 文件夹传输的成员文件数（isDir=true 时有效） */
    val dirCount: Int = 0,
    /** 该消息归属的对端设备（IP）：归属由 TCP 会话决定；空串=未知（旧版历史），按当前对话设备对待 */
    val peerIp: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mid", mid)
        .put("dir", dir.name)
        .put("kind", kind.name)
        .put("text", text)
        .put("fileName", fileName)
        .put("fileSize", fileSize)
        .put("filePath", filePath)
        .put("localUri", localUri)
        .put("ts", ts)
        .put("status", status.name)
        .put("isDir", isDir)
        .put("dirCount", dirCount)
        .put("peer", peerIp)

    companion object {
        fun fromJson(j: JSONObject): MsgItem = MsgItem(
            mid = j.optString("mid"),
            dir = runCatching { Dir.valueOf(j.optString("dir", "PEER")) }.getOrDefault(Dir.PEER),
            kind = runCatching { Kind.valueOf(j.optString("kind", "TEXT")) }.getOrDefault(Kind.TEXT),
            text = j.optString("text", ""),
            fileName = j.optString("fileName", ""),
            fileSize = j.optLong("fileSize", 0L),
            filePath = j.optString("filePath", ""),
            localUri = j.optString("localUri", ""),
            ts = j.optLong("ts", System.currentTimeMillis()),
            status = runCatching { Status.valueOf(j.optString("status", "OK")) }
                .getOrDefault(Status.OK),
            isDir = j.optBoolean("isDir", false),
            dirCount = j.optInt("dirCount", 0),
            peerIp = j.optString("peer", "")
        )
    }
}

/** 长内容头尾截断 */
object TextUtil {

    const val HEAD = 300
    const val TAIL = 150

    fun isTruncated(s: String): Boolean = s.length > HEAD + TAIL

    /** 超 HEAD+TAIL 返回「头 HEAD 字 + 省略提示 + 尾 TAIL 字」，否则原样 */
    fun headTail(s: String, expanded: Boolean = false): String {
        if (expanded || !isTruncated(s)) return s
        val omitted = s.length - HEAD - TAIL
        return s.take(HEAD) + "\n⋯（中间省略 $omitted 字）⋯\n" + s.takeLast(TAIL)
    }

    /** 长文件名中间省略：保头尾（含扩展名），中间以 … 代替 */
    fun middleEllipsis(name: String, max: Int = 26): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && dot < name.length - 1 && name.length - dot <= 10) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val keep = (max - ext.length - 1).coerceAtLeast(6) // 1 = 省略号
        val head = (keep + 1) / 2
        val tail = keep - head
        return base.take(head) + "…" + base.takeLast(tail) + ext
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    /** 传输速率文案（必须与 PC 端 Speeds.text 完全一致）：<801K 用整数 K，及以上用 M 保留两位小数 */
    fun speed(bps: Long): String {
        if (bps <= 0L) return ""
        if (bps < 801L * 1024) return "${bps / 1024}K/s"
        // Locale.ROOT：小数分隔符恒为点，别跟系统区域变成逗号（PC 端 Speeds.text 同此口径）
        return String.format(java.util.Locale.ROOT, "%.2fM/s", bps / 1048576.0)
    }

    fun time(ts: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return f.format(java.util.Date(ts))
    }
}
