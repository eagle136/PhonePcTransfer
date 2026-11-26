package com.phonepctransfer.util

/**
 * 发送端文件名/相对路径校验（与 PC 端 LinkNames.checkName 同一口径，双端同步改）：
 *  ① 名字按 UTF-8 计算不得超过 [MAX_NAME_BYTES] 字节（含扩展名）→ 超出直接提示「文件名过长」并禁止发送；
 *  ② 不得含可能影响 Linux/Windows 落盘或路径解析的字符 → 提示后取消本次传输任务。
 *
 * 校验失败 = **整次发送请求取消**（单文件 / 批量 / 文件夹一致）；接收端仍保留
 * FileIO.sanitize 作为防御，并对超长名做 [MAX_NAME_BYTES] 字节截断（见 FileIO.clampName）。
 */
object NameRules {

    /**
     * 文件名上限（UTF-8 字节数，含扩展名）。
     * 取 200：仍低于 ext4/NTFS 单段 255 字节硬上限，又足够容纳真实长名
     * （原 48 字节把「项目名+日期+后缀」这类正常文件名挡在发送前）。
     */
    const val MAX_NAME_BYTES = 200

    /** 可能影响 Linux / Windows 文件系统的字符（/ 与 \ 都会破坏路径段，: 会把文件送进 NTFS 备用流） */
    private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** Windows 保留设备名（NTFS 上无法创建同名文件/目录） */
    private val RESERVED = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")

    fun utf8Bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * 校验一个名字（单文件取文件名；目录取目录名；文件夹成员取路径末段）。
     * @return null = 通过；否则为**不含主语**的原因，由调用方拼上「文件名 / 文件夹名 / 成员」前缀
     */
    fun checkName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "为空"
        if (name != raw) return "不能以空格开头或结尾"
        val bytes = utf8Bytes(name)
        if (bytes > MAX_NAME_BYTES) {
            return "过长（${bytes} 字节，上限 ${MAX_NAME_BYTES} 字节）"
        }
        for (ch in name) {
            if (ch in ILLEGAL || ch.code < 0x20 || ch.code == 0x7F) {
                return "含特殊字符「${escape(ch)}」，可能影响 Linux/Windows 文件系统"
            }
        }
        if (name == "." || name == "..") return "不能是「$name」"
        if (name.endsWith(".")) return "不能以点结尾"
        if (RESERVED.matches(name)) return "「$name」是系统保留名"
        return null
    }

    /** 单文件 / 目录名的失败原因（给用户看的完整句子） */
    fun fileNameError(name: String): String? = checkName(name)?.let { "文件名$it" }

    /** 目录名的失败原因 */
    fun folderNameError(name: String): String? = checkName(name)?.let { "文件夹名$it" }

    /**
     * 校验文件夹内成员的相对路径（逐段查：每段都要能独立落成目录/文件名）。
     * @return null = 通过；否则为给用户看的失败理由（带上是哪个成员）
     */
    fun relPathError(relPath: String): String? {
        val segs = relPath.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return "成员「$relPath」文件名为空"
        for ((i, seg) in segs.withIndex()) {
            val reason = checkName(seg) ?: continue
            val full = segs.joinToString("/")
            return if (i == segs.size - 1) {
                "成员「$full」的文件名$reason"
            } else {
                "成员「$full」的目录段「$seg」$reason"
            }
        }
        return null
    }

    /** 文件夹整体校验：目录名 + 全部成员相对路径 */
    fun folderError(folderName: String, relPaths: List<String>): String? {
        folderNameError(folderName)?.let { return it }
        for (rel in relPaths) {
            relPathError(rel)?.let { return it }
        }
        return null
    }

    /** 按码点截断到 [maxBytes]（UTF-8），保证不劈开代理对 */
    fun trimToBytes(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (utf8Bytes(s) <= maxBytes) return s
        val sb = StringBuilder()
        var i = 0
        var used = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + n > maxBytes) break
            sb.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** 给字符串预留 [reserveBytes]（接收端后续追加 " (n)" 序号后仍不超上限） */
    fun trimForOrdinal(s: String, reserveBytes: Int = 8): String {
        if (utf8Bytes(s) + reserveBytes <= MAX_NAME_BYTES) return s
        return trimToBytes(s, MAX_NAME_BYTES - reserveBytes)
    }

    private fun escape(ch: Char): String {
        if (ch.code < 0x20 || ch.code == 0x7F) {
            return String.format(java.util.Locale.US, "\\x%02X", ch.code)
        }
        return ch.toString()
    }
}
