package com.phonepctransfer.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 本机文件访问的**唯一入口**（内置文件浏览器 + 「发送文件夹」的递归枚举都走这里）。
 *
 * 三种后端（同一套 [Entry] 输出，上层不必关心差异）：
 *  1. **普通模式**：`java.io.File` 直读。需要「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 才能
 *     浏览 /sdcard 全盘；Android 11+ 即使有该权限，`/Android/data`、`/Android/obb` 也读不到
 *     —— 那是系统的 FUSE 限制，不是我们的 bug（列表页会如实说明）。
 *  2. **root 模式**（设置里手动开，默认关）：所有枚举与读取都过 `su -c`，浏览根改为
 *     `/data/media/0`（= /sdcard 的底层真实目录，见 [ROOT_MEDIA]），于是 `/Android/data` 也能读。
 *     KernelSU 与 Magisk 的 `su` 都支持 `su -c "命令"`，所以一套实现同时覆盖两者。
 *  3. **SAF 模式**（未授权「所有文件访问」时的降级）：只在用户授权的那棵目录树内浏览。
 *
 * 铁律（沿用仓库既有约束）：**枚举失败绝不冒充空目录** —— 失败一律返回 [FsList.error] 非空，
 * 调用方必须如实提示（这条与 `FileIO.listTree` 的 skipped 记账同源，是「传文件夹漏文件」的
 * 根治手段）。
 */
object LocalFs {

    private const val TAG = "PP-LocalFs"

    /** Android 11（API 30）起才有「所有文件访问」这一档 */
    private val SDK_ALL_FILES = Build.VERSION_CODES.R

    /** Android 17 = API 37（Google 从这一档开始收紧整盘访问，见 [isAndroid17Plus]） */
    private const val SDK_ANDROID_17 = 37

    /**
     * 是否 Android 17+（2026-10-05 新增，用户真机实测驱动）。
     *
     * 为什么单列一条：Android 17 上「所有文件访问」(MANAGE_EXTERNAL_STORAGE) **已经不再生效** ——
     * 权限声明还在、设置页也打得开，但**授予后系统仍不放行整盘读取**，并提示「只能使用系统自带的
     * 文件选择器」。所以在这个版本上：
     *  · 引导页**不能再把用户推去「所有文件访问」设置页**（死路，用户已被系统顶回来过）；
     *  · 必须直接给两条可用路径：**系统自带文件选择器（SAF 目录树）** 或 **root 模式**。
     * 判定用 API level（33=Android 13、37=Android 17，一一对应），不看 ROM 自定义版本号。
     *
     * 本机 SDK 证据（`platforms/android-37.0`）：`Manifest.permission.MANAGE_EXTERNAL_STORAGE`
     * 与 `Environment.isExternalStorageManager()` 都还在（`api-versions.xml` 里 `since="30"`、
     * 无 deprecated 标记）→ 是**运行期策略收紧**，不是 API 被删。
     */
    fun isAndroid17Plus(): Boolean = Build.VERSION.SDK_INT >= SDK_ANDROID_17

    /** root 模式下的浏览根：/sdcard 的底层目录（su 可读 /Android/data） */
    const val ROOT_MEDIA = "/data/media/0"

    /**
     * root 模式开关（由 AppCore 从 Prefs 注入；设置页手动开，**默认关**）。
     *
     * 注意：开关只是「用户想用 root」，真能不能用由 [probeRoot] 决定 —— 开关开着但 root 没授权时，
     * 列表会**如实报错**（而不是画一个空目录让人以为手机是空的）。
     */
    @Volatile
    var rootEnabled: Boolean = false

    /** 普通模式的根（通常是 /storage/emulated/0；个别 ROM 不是这个路径） */
    fun normalRoot(): String =
        runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/sdcard")

    /** 浏览起点：root 模式 = /data/media/0，否则 = 外部存储根 */
    fun homePath(): String = if (rootEnabled) ROOT_MEDIA else normalRoot()

    /**
     * 普通模式能否浏览全盘：API < 30（分区存储之前）恒可；API 30+ 看「所有文件访问」。
     * 返回 false 且 root 也没开时，浏览器要显示「去授权 / 用 SAF 选目录树」的引导页。
     */
    fun canBrowseNormal(): Boolean =
        Build.VERSION.SDK_INT < SDK_ALL_FILES ||
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 一条目录项。
     *
     * @param key 同一目录内的唯一标识：普通/root 模式 = 绝对路径；SAF 模式 = documentId
     * @param uri **发送这一项时用的地址**：普通/root = `file://`，SAF = 树内文档 `content://`
     *            （单文件直接发它；文件夹把它当「树」交给 [FileIO.scanTree]）
     * @param mtime 毫秒；0 = 未知（root 模式下 stat 失败时会出现，界面按「未知」处理）
     */
    data class Entry(
        val name: String,
        val key: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val uri: Uri
    )

    /** 枚举结果：**失败必须带 error**（调用方不许把失败当空目录画） */
    class FsList(val entries: List<Entry> = emptyList(), val error: String? = null)

    /** 浏览位置：本机路径（普通/root 模式）或 SAF 授权树内的一份文档 */
    sealed interface Loc {
        data class Path(val path: String) : Loc
        data class Tree(val treeUri: Uri, val docId: String, val label: String) : Loc
    }

    /** 顶栏显示的路径文案 */
    fun label(loc: Loc): String = when (loc) {
        is Loc.Path -> loc.path
        is Loc.Tree -> loc.label
    }


    // ==================== 列目录 ====================

    /** 列一个位置的内容（阻塞调用：root 模式会起进程；调用方放后台线程） */
    fun list(ctx: Context, loc: Loc): FsList = when (loc) {
        is Loc.Path -> listPath(loc.path)
        is Loc.Tree -> listTreeChildren(ctx, loc)
    }

    /** 普通 / root 模式列目录 */
    private fun listPath(path: String): FsList {
        if (rootEnabled) return listPathRoot(path)
        val d = File(path)
        if (!d.isDirectory) return FsList(error = "打不开这个文件夹：$path")
        val kids = d.listFiles() ?: return FsList(
            error = if (isAndroid17Plus()) {
                // Android 17：整盘访问已被系统关闭，再指路「去设置页授权」就是误导（见 [isAndroid17Plus]）
                "系统拒绝读取：$path\n\n" +
                    "Android 17 起系统只允许用「系统自带的文件选择器」选目录，或用 root 权限访问：\n" +
                    "· 用引导页的「用系统选择器选目录」授权一个目录，浏览器就在那个目录里用；\n" +
                    "· 或在设置里开启 root 模式，直接读 /data/media/0。"
            } else {
                "系统拒绝读取：$path\n\n" +
                    "· Android 11+ 需要「所有文件访问」才能浏览全盘（设置页可授予）；\n" +
                    "· 或在设置里开启 root 模式直接读 /data/media/0。"
            }
        )
        val out = ArrayList<Entry>(kids.size)
        for (f in kids) {
            val dir = runCatching { f.isDirectory }.getOrDefault(false)
            val sz = if (dir) 0L else runCatching { f.length() }.getOrDefault(0L)
            val mt = runCatching { f.lastModified() }.getOrDefault(0L)
            out += Entry(f.name, f.absolutePath, dir, sz, mt, Uri.fromFile(f))
        }
        sortEntries(out)
        return FsList(out)
    }

    /**
     * root 模式列目录：**一条 shell 命令跑完整目录**。
     *
     * 为什么不是「先 ls 拿名字、再逐个 stat」：那样每个条目都要 fork 一次 toybox，
     * 一个几百项的目录会慢到不可用；这里循环在 shell 里跑，只有一次 `su` 进程开销。
     * 输出格式固定四列（类型/大小/mtime/名字，**名字放最后**），按 TAB 切分 ——
     * 这样带空格的目录名不会被切坏（只有含 TAB/换行的极端名字会失真，可接受）。
     */
    private fun listPathRoot(path: String): FsList {
        val r = su(listCmd(path))
        if (r.code != 0) {
            return FsList(
                error = "root 读取失败（${r.firstLine()}）：$path\n\n" +
                    "· 请在 KernelSU / Magisk 里给本应用 root 权限；\n" +
                    "· 或在设置里关掉 root 模式，改用「所有文件访问」。"
            )
        }
        val out = ArrayList<Entry>()
        for (line in r.out.lineSequence()) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size < 4) continue
            val name = p[3]
            if (name.isEmpty() || name == "." || name == "..") continue
            val child = if (path.endsWith("/")) path + name else "$path/$name"
            out += Entry(
                name = name,
                key = child,
                isDir = p[0] == "D",
                size = p[1].toLongOrNull() ?: 0L,
                // stat %Y 是秒；失败时给 0（界面按「未知」显示，不伪造时间）
                mtime = (p[2].toLongOrNull() ?: 0L) * 1000L,
                uri = Uri.fromFile(File(child))
            )
        }
        sortEntries(out)
        return FsList(out)
    }

    /** SAF 模式列目录（用户授权的那棵树之内） */
    private fun listTreeChildren(ctx: Context, loc: Loc.Tree): FsList {
        val childUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(loc.treeUri, loc.docId)
        }.getOrNull() ?: return FsList(error = "SAF 目录地址无效（授权可能已失效）")
        val out = ArrayList<Entry>()
        try {
            val c = ctx.contentResolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ), null, null, null
            ) ?: return FsList(error = "读不到这个文件夹（授权可能已失效，请重新选目录）")
            c.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    val size = if (it.isNull(3)) 0L else it.getLong(3)
                    val mt = if (it.isNull(4)) 0L else it.getLong(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Entry(
                        name = name,
                        key = id,
                        isDir = isDir,
                        size = if (isDir) 0L else size,
                        mtime = mt,
                        uri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, id)
                    )
                }
            }
        } catch (t: Throwable) {
            return FsList(error = "读不到这个文件夹：${t.message ?: t.javaClass.simpleName}")
        }
        sortEntries(out)
        return FsList(out)
    }

    /** 目录在前、文件在后，同类按名字（不区分大小写）——与 PC 端文件列表同口径 */
    private fun sortEntries(list: MutableList<Entry>) {
        list.sortWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    // ==================== root：su 封装 ====================

    /**
     * 跑一次 `su -c <cmd>` 并拿回结果。
     *
     * stdout/stderr **合并**读（输出都很小，不会撑满管道）；非 0 退出码一律由调用方当失败处理
     * —— KernelSU/Magisk 拒绝授权时 `su` 自己就以非 0 退出并打印原因，正好当提示文案用。
     */
    private fun su(cmd: String, timeoutSec: Long = 25): Sh {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            // 先读干管道再 waitFor：子进程写满管道缓冲会永久阻塞（经典坑）
            val text = runCatching { p.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val done = runCatching { p.waitFor(timeoutSec, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!done) {
                runCatching { p.destroy() }
                Sh(text, "su 超时（${timeoutSec}s）", -1)
            } else {
                Sh(text, "", p.exitValue())
            }
        } catch (t: Throwable) {
            // su 不存在（没装 KernelSU/Magisk，或 ROM 不带 su）
            Sh("", "su 不可用：${t.javaClass.simpleName} ${t.message}", -9)
        }
    }

    private class Sh(val out: String, val err: String, val code: Int) {
        /** 失败时给人看的一句（优先 stderr；这里两者已合并，直接取第一行非空文本） */
        fun firstLine(): String =
            err.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: "无输出"
    }

    /** shell 单引号包裹（内部单引号按 `'\''` 转义），防路径里的空格/引号拆命令 */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * root 列目录脚本：一次进程跑完整个目录。
     * `cd` 失败用 exit 7 明确区分「目录不存在/不可读」与「目录本来就是空的」。
     */
    private fun listCmd(dir: String): String =
        "cd " + shq(dir) + " 2>/dev/null || exit 7; " +
            "ls -A 2>/dev/null | while IFS= read -r f; do " +
            "if [ -d \"\$f\" ]; then t=D; s=0; else t=F; s=\$(stat -c %s -- \"\$f\" 2>/dev/null); fi; " +
            "m=\$(stat -c %Y -- \"\$f\" 2>/dev/null); " +
            "printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$m\" \"\$f\"; " +
            "done"

    /** root 探测结果缓存（60s 内不重复起进程；每次进浏览器都会调一次） */
    @Volatile
    private var rootOk = false

    @Volatile
    private var rootOkAt = 0L

    /**
     * 测一次 root（`su -c id` 看 uid=0）。结果缓存 [ROOT_CACHE_MS]，避免频繁弹授权/起进程。
     * **不抛异常**：拿不到就当 false，由调用方给出「去授权」的口径。
     */
    fun probeRoot(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - rootOkAt < ROOT_CACHE_MS) return rootOk
        val r = su("id", 10)
        rootOk = r.code == 0 && r.out.contains("uid=0")
        rootOkAt = now
        Log.i(TAG, "root 探测：${if (rootOk) "可用" else "不可用（${r.firstLine()}）"}")
        return rootOk
    }

    private const val ROOT_CACHE_MS = 60_000L

    /**
     * root 模式读一个文件（`su -c cat`）。
     *
     * **先判可读**：不可读直接返回 null，让上层抛 `SourceError` 如实报失败 ——
     * 绝不能给一个「空流」把 0 字节当成功发出去（那会变成对端收到空文件还不知道）。
     * 返回的流 close 时会一并销毁 `cat` 子进程（否则它会一直挂着）。
     */
    fun openRootStream(path: String): InputStream? {
        if (!probeRoot()) return null
        val chk = su("test -r " + shq(path), 10)
        if (chk.code != 0) {
            Log.w(TAG, "root 读不了：$path（${chk.firstLine()}）")
            return null
        }
        return try {
            val p = ProcessBuilder("su", "-c", "cat -- " + shq(path) + " 2>/dev/null").start()
            ProcStream(p)
        } catch (t: Throwable) {
            Log.w(TAG, "su cat 起不来：${t.message}")
            null
        }
    }

    /** `su -c cat` 的输出流：close 时销毁子进程 */
    private class ProcStream(private val p: Process) : InputStream() {
        private val ins = p.inputStream
        override fun read(): Int = ins.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = ins.read(b, off, len)
        override fun available(): Int = runCatching { ins.available() }.getOrDefault(0)
        override fun close() {
            runCatching { ins.close() }
            runCatching { p.destroy() }
        }
    }

    /** root 模式取文件大小（发送时算进度/续传要用）；失败返回 -1（调用方保留原值） */
    fun rootSizeOf(path: String): Long {
        val r = su("stat -c %s -- " + shq(path), 10)
        if (r.code != 0) return -1L
        return r.out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: -1L
    }
}

