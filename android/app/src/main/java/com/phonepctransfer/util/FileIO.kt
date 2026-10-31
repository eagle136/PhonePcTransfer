package com.phonepctransfer.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 文件收发相关工具 */
object FileIO {

    private const val TAG = "PP-FileIO"

    /** 收到的文件落盘目录名（应用专属，无需任何权限） */
    private const val APP_DIR = "received"

    /** 在"下载"里建的中文子目录 */
    const val DOWNLOAD_SUBDIR = "手机电脑互传"

    /**
     * @param out   写入用的输出流
     * @param desc  给人看的落盘位置描述（如「下载/手机电脑互传/a.png」）
     * @param uri   **可用于再次读取**的地址：MediaStore 为 content:// URI，回退时为绝对路径。
     *              图片消息靠它加载缩略图/原图（desc 只是文案，不能直接打开）。
     */
    data class Sink(val out: OutputStream, val desc: String, val uri: String)

    /** 图片扩展名集合（与 ui/ImageViews 的 isImageName、PC 端 ChatWindow.isImageName 三处一致） */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

    /** 按文件名判定是否图片（链路层 hsync 判「这条 FILE 可不可以补齐/同步删除」用） */
    fun isImageFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    fun queryName(ctx: Context, uri: Uri): String {
        // file:// 走不了 ContentResolver（query 返回 null），直接按路径取文件名。
        // 「分享进来但当时没连上」的内容会先落到应用私有目录再发，走的就是这条分支，
        // 不特判的话名字会变成 `1757..._IMG_1234.jpg` 这种带时间戳前缀的临时名。
        if ("file" == uri.scheme) {
            runCatching {
                val p = uri.path
                if (!p.isNullOrEmpty()) return File(p).name
            }
        }
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) {
                    val n = c.getString(i)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
    }

    fun querySize(ctx: Context, uri: Uri): Long {
        if ("file" == uri.scheme) {
            runCatching {
                val p = uri.path
                if (!p.isNullOrEmpty()) {
                    val f = File(p)
                    val len = f.length()
                    // 应用身份能 stat 到就直接用；stat 不到（root 模式下的 /data/media/0/...）
                    // 过 su 问一次 —— 发送进度、断点续传协商都依赖这个大小
                    if (len > 0 || f.canRead()) return len
                    if (LocalFs.rootEnabled) {
                        val rs = LocalFs.rootSizeOf(p)
                        if (rs >= 0) return rs
                    }
                    return len
                }
            }
        }
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) return c.getLong(i)
            }
        }
        return -1L
    }

    /**
     * 打开一个**源文件**用于发送。
     *
     * - `file://`：先本地 `FileInputStream`（最快）；打不开就**回落 root** —— root 模式下路径是
     *   `/data/media/0/...`，应用身份读不了，只能过 `su -c cat`。
     * - 其余（`content://`，含 SAF 文档）：交给 ContentResolver。
     * - 都拿不到 → null。上层（`LinkSend.doSendFileInner`）会把它抛成 `SourceError` 如实报失败，
     *   **绝不返回空流**（那会把 0 字节当成功发出去）。
     */
    fun openSource(ctx: Context, uri: Uri): InputStream? {
        if (uri.scheme == "file") {
            val p = uri.path
            if (!p.isNullOrEmpty()) {
                runCatching { java.io.FileInputStream(File(p)) }.getOrNull()?.let { return it }
                return if (LocalFs.rootEnabled) LocalFs.openRootStream(p) else null
            }
        }
        return runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * 为接收到的文件创建输出流。
     * API 29+ 走 MediaStore Downloads（免存储权限，用户在"下载/手机电脑互传"里能看到）；
     * 失败或 API 28 及以下退回到应用专属外部目录。
     *
     * @param relPath 文件夹成员时为「文件夹内相对路径」（如 `sub/a.txt`），落进同名子目录；
     *                null/空 = 普通单文件，直接落「下载/手机电脑互传」根目录。
     */
    fun createSink(ctx: Context, name: String, mime: String, relPath: String? = null): Sink {
        // ⚠️ 唯一名必须自己先算好（见 uniqueFileName）：一旦交给 MediaStore 去重，
        //    它会往**整个名字末尾**追加（`xxx.apk.1`、`xxx.jpeg (1)`），扩展名被顶到中间，
        //    系统按扩展名认类型时直接认不出来（APK 装不上、图片/视频打不开）。
        val safe = uniqueFileName(ctx, name, relPath)
        val subDir = relDirOf(relPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                // 文件夹成员：RELATIVE_PATH = 手机电脑互传/<文件夹>/<子目录>，MediaStore 自动建父目录
                val rp = if (subDir.isEmpty()) {
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR"
                } else {
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/$subDir"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rp)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@runCatching
                val os = ctx.contentResolver.openOutputStream(uri) ?: return@runCatching
                // 回读实际落盘名：万一仍被系统改名，气泡文案也不说谎
                val actual = displayNameOf(ctx, uri) ?: safe
                val desc = rp.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/") + "/$actual"
                return Sink(os, desc, uri.toString())
            }.onFailure { Log.w(TAG, "MediaStore 落盘失败，回退应用目录: ${it.message}") }
        }
        val dir = fallbackDir(ctx, subDir)
        dir.mkdirs()
        // 走到这里说明 MediaStore 那条路没走成：落点在应用私有目录，跟下载目录**不是同一个位置**，
        // 上面算出的 safe 有没有被占在这儿才知道 —— 原子创建、撞名就地换号，绝不覆盖旧文件。
        val f = createExclusiveIn(dir, safe)
        return Sink(f.outputStream(), f.absolutePath, f.absolutePath)
    }

    /** 相对路径里的目录部分（逐段清洗）；没有子目录返回空串 */
    private fun relDirOf(relPath: String?): String {
        if (relPath.isNullOrEmpty()) return ""
        val head = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""
        return head.split('/').filter { it.isNotEmpty() }.joinToString("/") { sanitize(it) }
    }

    private fun displayNameOf(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 本次进程内已占用的「相对目录 + 文件名」→ **该次落盘的绝对路径**。
     * 同一批接收里多条并发创建时，MediaStore 的可见性有滞后，光靠查询会撞名 → 先占坑。
     * 坑只是并发闸门，**不充当重名证据**：坑里那个文件已经不在了（用户删过、或失败后清了半成品），
     * 坑就作废、名字重新可用 —— 下一轮同名落盘用回原名，不再一路 ` (1)`、` (2)`。
     */
    private val reserved = java.util.Collections.synchronizedMap(HashMap<String, String>())

    /** MediaStore 分支的真实落盘目录：`<外部存储根>/Download/手机电脑互传/<子目录>` */
    private fun publicDir(subDir: String): File {
        val dl = File(LocalFs.normalRoot(), Environment.DIRECTORY_DOWNLOADS)
        return if (subDir.isEmpty()) File(dl, DOWNLOAD_SUBDIR) else File(dl, "$DOWNLOAD_SUBDIR/$subDir")
    }

    /** 回退分支的落盘目录：应用私有 `files/received/<子目录>` */
    private fun fallbackDir(ctx: Context, subDir: String): File {
        val base = appReceivedDir(ctx)
        return if (subDir.isEmpty()) base else File(base, subDir)
    }

    /**
     * 为收到的文件挑一个**实际不冲突**的落盘名：序号插在扩展名前（`a.txt` → `a (1).txt`）。
     *
     * 与 PC 端 `LinkNames.createUniqueFile` 同口径（两端规则必须一致）。冲突与否看**落盘位置上到底
     * 躺没躺着一个活文件**，不看台账记没记过，三条判据：
     *   ① 本进程占过坑 → 坑里那条路径还在才算冲突；已经不在了 = 坑作废；
     *   ② MediaStore 有同名条目 → 条目指向的文件还在才算冲突；文件被外界删了、只剩条目（幽灵条目）
     *      且条目属于本应用 → 删掉它把名字还回来（留着的话系统自己改名，产出 `a.txt.1` 这种坏名）；
     *   ③ 落盘目录里真有同名文件 → 冲突。
     * 应用私有回退目录不再挡下载目录的路（两个位置互不相干）；真回退时由 [createExclusiveIn] 就地换号。
     */
    fun uniqueFileName(ctx: Context, name: String, relPath: String?): String {
        val base = sanitize(name)
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        val subDir = relDirOf(relPath)
        val rp = if (subDir.isEmpty()) "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR"
        else "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/$subDir"
        // API<29 恒走回退目录，落点就是它；29+ 正常落下载目录
        val landing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publicDir(subDir)
        else fallbackDir(ctx, subDir)
        if (reserved.size > 20000) reserved.clear()
        for (i in 0 until 100000) {
            val cand = if (i == 0) base else "$stem ($i)$ext"
            val key = "$rp/$cand".lowercase()
            // 坑只在自己那条路径还躺着东西时才算冲突；已经不在了 = 作废，继续往下按实况判
            val taken = reserved[key]
            if (taken != null && File(taken).exists()) continue
            if (liveMediaConflict(ctx, rp, cand)) continue
            if (File(landing, cand).exists()) continue
            reserved[key] = File(landing, cand).absolutePath
            return cand
        }
        val last = "${stem}_${System.currentTimeMillis()}$ext"
        reserved["$rp/$last".lowercase()] = File(landing, last).absolutePath
        return last
    }

    /**
     * MediaStore 里的同名条目到底算不算冲突：条目指向的文件**还在**才算。
     *
     * 分两步走，第一步刻意维持改动前的行为与最小投影：
     *   第一步按 `RELATIVE_PATH + DISPLAY_NAME` 查有没有同名条目（只取 `_ID`）——没有、或查询本身
     *          失败 → 空闲（与老代码一致，不给正常路径加新的失败面）；
     *   第二步只对「确实有同名条目」的少数情况逐条验实况：文件还在 = 冲突；`_data` 读不到、
     *          条目不是本应用建的、或连所在目录都读不到（分区存储下 `File.exists()` 会对活文件
     *          假阴性）= 证据不足，保守算冲突，**绝不误删还在用的条目**；
     *          目录读得到而文件不在 = 幽灵条目（用户在系统文件管理器/root 里直接删了文件），
     *          删掉它把名字还回来 —— 留着的话系统会自己改名，产出 `a.txt.1` 这种扩展名被顶坏的坏名。
     */
    private fun liveMediaConflict(ctx: Context, rp: String, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rows = runCatching {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$rp/", displayName),
                null
            )?.use { c ->
                ArrayList<Long>().apply { while (c.moveToNext()) add(c.getLong(0)) }
            }
        }.getOrNull() ?: return false
        val ghosts = ArrayList<Long>()
        for (id in rows) {
            val row = runCatching {
                ctx.contentResolver.query(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    null, null, null
                )?.use { c ->
                    if (!c.moveToFirst()) null else (c.getString(0).orEmpty() to c.getString(1))
                }
            }.getOrNull() ?: return true
            val (data, owner) = row
            val f = if (data.isEmpty()) null else File(data)
            if (f != null && f.exists()) return true
            if (f == null || owner != ctx.packageName || f.parentFile?.exists() != true) return true
            ghosts.add(id)
        }
        if (ghosts.isNotEmpty()) {
            ghosts.forEach { id ->
                runCatching {
                    ctx.contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null
                    )
                }
            }
            Log.i(TAG, "同名条目 $displayName 的文件已不在，清掉 ${ghosts.size} 条幽灵条目，本次用回原名")
        }
        return false
    }

    /** 回退目录里原子创建（与 PC 端同一手法）：谁建成谁用，撞名就地往后换号 */
    private fun createExclusiveIn(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 0 until 100000) {
            val cand = if (i == 0) name else "$stem ($i)$ext"
            val f = File(dir, cand)
            runCatching { if (f.createNewFile()) return f }
        }
        return File(dir, name)
    }

    /**
     * 续传：以**追加**模式重新打开已存在的半成品。
     * MediaStore 用 "wa" 模式；回退路径用 FileOutputStream(append=true)。
     */
    fun appendSink(ctx: Context, uri: String): OutputStream? {
        return runCatching {
            if (uri.startsWith("content://")) {
                ctx.contentResolver.openOutputStream(Uri.parse(uri), "wa")
            } else {
                java.io.FileOutputStream(File(uri), true)
            }
        }.getOrNull()
    }

    /** 删除半成品（content:// 或绝对路径），尽力而为 */
    fun deletePartial(ctx: Context, uri: String) {
        runCatching {
            if (uri.startsWith("content://")) {
                ctx.contentResolver.delete(Uri.parse(uri), null, null)
            } else if (uri.isNotEmpty()) {
                File(uri).delete()
            }
        }
    }

    /**
     * 为收到的文件夹挑一个不重名的名字。
     * MediaStore：查「下载/手机电脑互传/<候选>」是否已有条目；回退路径看磁盘目录。
     */
    fun uniqueFolderName(ctx: Context, name: String): String {
        val base = sanitize(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (i in 0 until 100000) {
                val cand = if (i == 0) base else "$base ($i)"
                val rp = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/$cand"
                val exists = runCatching {
                    ctx.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf("$rp/"),
                        null
                    )?.use { it.count > 0 } ?: false
                }.getOrDefault(false)
                if (!exists) return cand
            }
        } else {
            for (i in 0 until 100000) {
                val cand = if (i == 0) base else "$base ($i)"
                if (!File(appReceivedDir(ctx), cand).exists()) return cand
            }
        }
        return "${base}_${System.currentTimeMillis()}"
    }

    // ==================== 发送端：SAF 目录树枚举 ====================

    data class TreeFile(val uri: Uri, val relPath: String, val size: Long)

    /** 目录树扫描结果：文件 + 空子目录（相对路径）+ 被上限截断的分支数 */
    class TreeScan(
        val files: List<TreeFile>,
        /** 子树里一个文件都没有的目录（相对路径）；接收端用于重建空目录结构 */
        val emptyDirs: List<String>,
        /** 因深度/数量上限被跳过的分支数——绝不让内容无声消失 */
        val skipped: Int
    )

    private const val MAX_TREE_DEPTH = 64
    private const val MAX_TREE_FILES = 20000

    /** 递归枚举 SAF 目录树。空目录与截断数一并返回，不再静默丢失 */
    fun listTree(ctx: Context, treeUri: Uri): TreeScan {
        val files = ArrayList<TreeFile>()
        val sc = ScanCtx()
        runCatching {
            // 起点用 getDocumentId（不是 getTreeDocumentId）：SAF「选目录」拿到的树 uri 取到的是
            // 树的根文档，而浏览器里长按选中的**子文件夹**（buildDocumentUriUsingTree 生成）取到的
            // 就是那个子文件夹本身 —— 两种形态都能从它开始枚举（buildChildDocumentsUriUsingTree
            // 会自己从 uri 里取 treeId，子文档 uri 照样能用）。
            val startDoc = DocumentsContract.getDocumentId(treeUri)
            walkTree(ctx, treeUri, startDoc, "", files, sc, 0)
        }.onFailure {
            // 整体枚举失败也要计数：否则会把「根本没读进来」当成「文件夹本来就是空的」
            sc.skipped++
            Log.w(TAG, "枚举目录树失败: ${it.message}")
        }
        return TreeScan(files, sc.emptyDirs, sc.skipped)
    }

    /**
     * 按 Uri 形态选枚举方式：`file://` 走本机路径（root 模式过 su，见 [listTreePath]），
     * 其余（SAF 内容 uri，含树内子文档）走 [listTree]。
     *
     * 用户发送 / 气泡重试 / 待发补发三处统一走它 —— 这样「内置浏览器选出来的文件夹」与
     * 「系统 SAF 选出来的文件夹」在链路层完全同构。
     */
    fun scanTree(ctx: Context, uri: Uri): TreeScan {
        val path = if (uri.scheme == "file") uri.path else null
        return if (!path.isNullOrEmpty()) listTreePath(ctx, path) else listTree(ctx, uri)
    }

    /**
     * 递归枚举**本机路径**目录（内置文件浏览器「长按文件夹发送」用）。
     *
     * 与 SAF 版 [listTree] 逐条对齐：同样的深度/数量上限、同样的 emptyDirs/skipped 语义 ——
     * **枚举失败绝不冒充空目录**（这是「传文件夹漏文件」的根治手段，换个后端也不能丢）。
     * 目录列举交给 [LocalFs]（普通 / root 两种模式都在那里分流）。
     */
    fun listTreePath(ctx: Context, root: String): TreeScan {
        val files = ArrayList<TreeFile>()
        val sc = ScanCtx()
        walkPath(ctx, root, "", files, sc, 0)
        return TreeScan(files, sc.emptyDirs, sc.skipped)
    }

    /** @return 子树内文件数（0 = 整棵子树无文件 → 记入空目录） */
    private fun walkPath(
        ctx: Context, dir: String, prefix: String,
        out: MutableList<TreeFile>, sc: ScanCtx, depth: Int
    ): Int {
        if (depth > MAX_TREE_DEPTH) {
            sc.skipped++
            Log.w(TAG, "目录超过 $MAX_TREE_DEPTH 层，跳过: $prefix")
            return 0
        }
        if (out.size >= MAX_TREE_FILES) {
            sc.skipped++
            Log.w(TAG, "文件数超过 $MAX_TREE_FILES，跳过: $prefix")
            return 0
        }
        val listed = LocalFs.list(ctx, LocalFs.Loc.Path(dir))
        if (listed.error != null) {
            // 读不到的分支**必须**计入 skipped：否则会被当成「空目录」，
            // 接收端重建出一个空目录、里面的文件凭空消失（与 SAF 版同一条硬约束）
            sc.skipped++
            Log.w(TAG, "枚举子目录失败($prefix): ${listed.error}")
            return 0
        }
        var count = 0
        for (e in listed.entries) {
            if (out.size >= MAX_TREE_FILES) {
                sc.skipped++
                Log.w(TAG, "文件数超过 $MAX_TREE_FILES，后续条目跳过: $prefix")
                break
            }
            val rel = if (prefix.isEmpty()) sanitize(e.name) else "$prefix/${sanitize(e.name)}"
            if (e.isDir) {
                count += walkPath(ctx, e.key, rel, out, sc, depth + 1)
            } else {
                out.add(TreeFile(e.uri, rel, if (e.size > 0) e.size else querySize(ctx, e.uri)))
                count++
            }
        }
        if (count == 0 && prefix.isNotEmpty()) sc.emptyDirs.add(prefix)
        return count
    }

    private class ScanCtx {
        val emptyDirs = ArrayList<String>()
        var skipped = 0
    }

    /** @return 子树内文件数（0 = 整棵子树无文件 → 记入空目录） */
    private fun walkTree(
        ctx: Context, treeUri: Uri, parentDoc: String, prefix: String,
        out: MutableList<TreeFile>, sc: ScanCtx, depth: Int
    ): Int {
        if (depth > MAX_TREE_DEPTH) {
            sc.skipped++
            Log.w(TAG, "目录超过 $MAX_TREE_DEPTH 层，跳过: $prefix")
            return 0
        }
        if (out.size >= MAX_TREE_FILES) {
            sc.skipped++
            Log.w(TAG, "文件数超过 $MAX_TREE_FILES，跳过: $prefix")
            return 0
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDoc)
        var count = 0
        // 是否真的读到了这个目录的内容。**枚举失败绝不能冒充「空目录」** ——
        // 老代码在 query 抛异常时 count 保持 0，于是把一个「整棵子树都被漏掉」的目录
        // 记成空目录：PC 端还会照着重建成一个空目录，用户看到的就是
        // 「文件夹在、里面的文件不见了」。这是「传文件夹漏文件」最隐蔽的一条路径。
        var readOk = false
        runCatching {
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                readOk = true
                while (c.moveToNext()) {
                    if (out.size >= MAX_TREE_FILES) {
                        sc.skipped++
                        Log.w(TAG, "文件数超过 $MAX_TREE_FILES，后续条目跳过: $prefix")
                        break
                    }
                    val docId = c.getString(0)
                    val name = c.getString(1)
                    if (docId.isNullOrEmpty() || name.isNullOrEmpty()) {
                        // 条目字段缺失时**不再静默 continue**：计入 skipped，让上层明确提示
                        sc.skipped++
                        Log.w(TAG, "条目字段缺失（docId/name 为空），已跳过: $prefix")
                        continue
                    }
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val rel = if (prefix.isEmpty()) sanitize(name) else "$prefix/${sanitize(name)}"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        count += walkTree(ctx, treeUri, docId, rel, out, sc, depth + 1)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        out.add(TreeFile(uri, rel, if (size >= 0) size else querySize(ctx, uri)))
                        count++
                    }
                }
            } ?: run {
                sc.skipped++
                Log.w(TAG, "枚举子目录返回空游标，已跳过: $prefix")
            }
        }.onFailure {
            sc.skipped++
            Log.w(TAG, "枚举子目录失败($prefix): ${it.message}（该分支已计入 skipped，不会静默丢失）")
        }
        if (readOk && count == 0 && prefix.isNotEmpty()) {
            sc.emptyDirs.add(prefix)
        }
        return count
    }

    fun appReceivedDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, APP_DIR).apply { mkdirs() }

    /** Windows 保留设备名（接收端可能是 PC） */
    private val reservedName = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")

    private fun isEmoji(cp: Int): Boolean =
        (cp in 0x1F000..0x1FAFF) || (cp in 0x2600..0x27BF) || (cp in 0x2B00..0x2BFF) ||
            (cp in 0x2300..0x23FF) || (cp in 0xFE00..0xFE0F) ||
            cp == 0x200D || cp == 0x20E3

    /** emoji 直接删除（PC 端部分字体显示不了；中日韩等正常文字不受影响） */
    private fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun randomTag(): String =
        String.format(java.util.Locale.US, "%06x", java.util.Random().nextInt(0x1000000))

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        var cleaned = stripEmoji(base).replace(Regex("[\\\\:*?\"<>|]"), "_").trim()
        // Windows 不允许结尾的点/空格
        while (cleaned.isNotEmpty() && (cleaned.last() == '.' || cleaned.last() == ' ')) {
            cleaned = cleaned.dropLast(1)
        }
        if (reservedName.matches(cleaned)) cleaned += "_"
        if (cleaned.isEmpty()) return "file_${randomTag()}"
        // 原名几乎全是 emoji（如「😀.png」只剩扩展名）→ 随机前缀补主名
        if (cleaned.startsWith(".")) {
            val stem = cleaned.substringBeforeLast('.', "")
            if (stem.isBlank()) {
                val dot = cleaned.indexOf('.')
                return "file_${randomTag()}${cleaned.substring(dot)}"
            }
        }
        return cleaned
    }
}
