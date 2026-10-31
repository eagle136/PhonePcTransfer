package com.phonepctransfer.data

import org.json.JSONObject

enum class Dir { SELF, PEER }

enum class Kind {
    TEXT, CLIP, FILE,

    /**
     * 通话记录（v2.7）：语音/视频通话结束后在本机记一条，文本形如
     * 「语音通话 00:12」/「视频通话 对方拒绝」/「语音通话 未发起（未同意使用数据流量）」。
     * **只记本机**，不跨端同步（hsync 只对账文本与图片）。
     */
    CALL
}

enum class Status {
    OK, PENDING, SENDING, FAILED,

    /**
     * 数据流量未同意（v2.7，文件专用）：本机或对端拒绝为本次传输消耗数据流量。
     * 消息**保留在聊天里**并在末尾显示可点击的红色感叹号，点了重新走一遍提醒流程。
     */
    DENIED,

    /**
     * 接收停滞暂停（v2.8）：30s 无任何进度（对端半死但 TCP 心跳正常）→ 中止本路接收，
     * 半成品保留（不删文件、不清注册表），对端重连后 file_query 自动续传。
     * 气泡渲染灰色 ⏸ + 「已收 pct%，重连后自动续传」；file_meta 重到 / 续传首 chunk 到达时回 SENDING。
     */
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
    /**
     * 发出时=content Uri 字符串；接收时=落盘后的路径/描述
     */
    val filePath: String = "",
    /**
     * **可直接再次读取**的文件地址（图片消息靠它加载缩略图/原图）。
     * 发出时=选中的 content Uri；接收时=落盘的 content Uri（MediaStore）或绝对路径。
     * 与 filePath 的区别：filePath 是人看的文案（如「下载/手机电脑互传/a.png」），不能直接打开。
     */
    val localUri: String = "",
    val ts: Long = System.currentTimeMillis(),
    val status: Status = Status.OK,
    /** 0..100，-1 表示无进度 */
    val progress: Int = -1,
    /** 文件夹传输气泡（dir_meta）：该条是一个文件夹而不是单个文件 */
    val isDir: Boolean = false,
    /** 文件夹传输的成员文件数（isDir=true 时有效） */
    val dirCount: Int = 0,
    /**
     * **这条消息属于哪台对端设备**（对端 IP）。
     *
     * 协议帧里没有「发给谁」的字段 —— 归属完全由 TCP 会话决定：从哪条会话读到就属于哪台。
     * 有了它，聊天区只显示当前对话设备的消息，A→B 的内容不会跑进 C 的界面与历史。
     * 空串 = 归属未知（旧版本写下的历史），按「当前对话设备」对待。
     */
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

    /**
     * 超过 HEAD+TAIL 时返回 "头 HEAD 字 + 省略提示 + 尾 TAIL 字"，否则原样返回。
     */
    fun headTail(s: String, expanded: Boolean = false): String {
        if (expanded || !isTruncated(s)) return s
        val omitted = s.length - HEAD - TAIL
        return s.take(HEAD) + "\n⋯（中间省略 $omitted 字）⋯\n" + s.takeLast(TAIL)
    }

    /**
     * 长文件名中间省略：保留头部与尾部（含扩展名），中间以 … 代替。
     * 例：超长文件名 20260913会议纪要最终版.mp4 → 20260913会议纪…最终版.mp4
     */
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

    /**
     * 传输速率文案（2026-09-22 用户口径，与 PC 端 `Speeds.text` **必须完全一致**）：
     *  - **小于 801 KB/s → 整数 K**，不带小数：`650K/s`
     *  - **801 KB/s 及以上 → M，保留两位小数**：`0.95M/s`、`12.34M/s`
     *
     * 门槛定在 801K（而不是 1024K）：0.79M 这种值的两位小数位几乎不跳动，
     * 还不如直接用 K 读数；进入 M 区间后两位小数才能反映真实变化。
     */
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
