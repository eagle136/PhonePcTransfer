package com.phonepctransfer.util

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.phonepctransfer.AppCore
import java.io.File

private const val FILEPROVIDER_AUTHORITY = "com.phonepctransfer.fileprovider"

/** 一个 tag 覆盖「弹列表 / 记住 / 命中记忆」整条链，验收时 `adb logcat -s OpenFile` 就够 */
private const val TAG = "OpenFile"

/**
 * 用**用户当场选的**程序打开聊天里收到的文件（非图片），并让本应用**记住**「这类文件用哪个程序」。
 *
 * 两条分支（[openOrAsk]）：
 *  - 本应用已记住这个 MIME → 用**显式组件** intent 直接启动那个程序（不再弹列表）；
 *  - 没记住 → 弹系统「打开方式」列表，并在列表带上回调，把用户这次点选的程序记下来。
 *
 * 为什么不用裸 `startActivity(ACTION_VIEW)`：裸启动会命中**系统**里已设的默认程序、不再询问，
 * 用户既改不了也没机会换别的程序；`Intent.createChooser` 则无论有没有默认程序都会弹列表
 * （官方 intents-filters 文档明确：即使之前已为该 intent 设过默认应用，选择器仍会显示）。
 * 记忆也不写回系统的默认应用表 —— 只在设置页「重置」即可全部清掉，仅本应用有效。
 *  - 完全没有能处理的程序 → 捕获 ActivityNotFoundException，toast 提示，不崩溃。
 *
 * **切勿用 `resolveActivity()` 预判能否打开**：Android 11+ 的包可见性过滤会让未声明
 * `<queries>` 的应用把它判成 null（哪怕系统里装着能打开的 App），从而误判为「没有程序」
 * 而放弃启动 —— 表现就是「点了文件一点反应都没有」。`startActivity` 本身不受该过滤限制，
 * 列表由系统解析进程（ChooserActivity）生成，同样不受本应用可见性限制，交回系统最可靠。
 * 命中记忆走的是显式组件 intent，也不受可见性限制，所以整套方案不需要加 `<queries>`。
 *
 * @param uriStr 文件地址：优先传 `MsgItem.localUri`（可被再次读取的地址），没有再用 filePath。
 *   其值要么是 content://（MediaStore 落盘，可直接授权外部应用读取），
 *   要么是绝对路径（API<29 或 MediaStore 失败回退），后者必须经 FileProvider 暴露。
 * @param fileName 仅用于推断 MIME 类型。
 */
fun openReceivedFile(ctx: Context, uriStr: String, fileName: String) {
    if (uriStr.isEmpty()) {
        Toast.makeText(ctx, "该文件没有本地副本", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = if (uriStr.startsWith("content://")) {
        Uri.parse(uriStr)
    } else {
        // 绝对路径：经 FileProvider 转成可被外部应用读取、且带读权限的 content://
        runCatching { FileProvider.getUriForFile(ctx, FILEPROVIDER_AUTHORITY, File(uriStr)) }
            .getOrNull() ?: run {
                Toast.makeText(ctx, "无法打开该文件", Toast.LENGTH_SHORT).show()
                return
            }
    }

    // APK：与其它文件同一套（记住→直用，没记住→弹列表并记住）。
    // Android 8+ 要求**发起安装的应用**先开「允许安装应用」
    // 开关，未开启时系统安装器要么直接闪退要么报错（且各 ROM 表现不同）。
    // 流程：已开启 → 弹选择安装程序；未开启 → 引导跳系统设置开一次（每应用一次性，之后不再提示）。
    // `.apk.1 / .apk.2` 是**旧版本**遗留在下载目录里的名字（那时靠 MediaStore 去重，
    // 它把数字追加在扩展名后面）；现在落盘自己算唯一名（`xx (1).apk`），但旧文件仍要能装。
    if (Regex("\\.apk(\\.\\d+)?$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) {
        openApk(ctx, uri)
        return
    }

    val mime = guessMime(ctx, uri, fileName)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        // 授权给被启动的外部应用读取该文件
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    openOrAsk(ctx, view, mime, "打开方式")
}

/** 推断 MIME：content:// 由 provider 给出最准 → 扩展名 → 内置兜底表 */
private fun guessMime(ctx: Context, uri: Uri, fileName: String): String {
    if (uri.scheme == "content") {
        runCatching { ctx.contentResolver.getType(uri) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isNotEmpty()) {
        runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return FileIO.mimeOf(fileName)
}

/** APK 安装入口：查「允许安装应用」开关 → 已开走 openOrAsk（记住→直接用，没记住→弹列表），未开先引导跳系统设置 */
private fun openApk(ctx: Context, uri: Uri) {
    if (ctx.packageManager.canRequestPackageInstalls()) {
        startInstall(ctx, uri)
        return
    }
    Toast.makeText(
        ctx, "首次安装 APK：请在下个页面允许本应用「安装应用」，之后不再提示",
        Toast.LENGTH_LONG
    ).show()
    try {
        ctx.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // 个别 ROM 没有该设置页：仍尝试直接装（装不装得成由系统定）
        startInstall(ctx, uri)
    }
}

/** APK 这类包 MIME：记忆键与 intent 类型都用它，不信 provider 对 `.apk` 的推断 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** APK → 列出所有能装的应用包，用户点谁就用谁装（并把这一类记下来） */
private fun startInstall(ctx: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    openOrAsk(ctx, view, APK_MIME, "选择安装程序")
}

/**
 * 本应用记住过这个 MIME → 直接用显式组件启动那个程序；没记住 → 弹系统「打开方式」列表并记住点选。
 *
 * 显式组件 intent 不查 intent filter、也不受 Android 11+ 包可见性限制，所以命中记忆这条
 * 不需要 `<queries>`。启动失败（那个应用被卸载/停用）就把这条忘掉，退回弹列表重选。
 */
private fun openOrAsk(ctx: Context, target: Intent, mime: String, title: String) {
    val saved = AppCore.prefs.openerOf(mime)
    if (saved != null) {
        if (runCatching { ctx.startActivity(Intent(target).setComponent(saved)) }.isSuccess) return
        Log.w(TAG, "记住的程序起不来，忘掉这条重选：mime=$mime comp=$saved")
        AppCore.prefs.forgetOpener(mime)
    }
    startActivityViaChooser(ctx, target, mime, title)
}

/**
 * 弹系统「打开方式」列表，并带上 `EXTRA_CHOSEN_COMPONENT_INTENT_SENDER` 回调：用户点选后，
 * 选择器把选中的组件用 `Intent.EXTRA_CHOSEN_COMPONENT` 填进这个 PendingIntent 再广播出去
 * （由 [OpenChoiceReceiver] 记账），**启动动作仍由选择器自己做** —— 所以我们只记，
 * 绝不再自己 start 一次（否则双开）。
 */
private fun startActivityViaChooser(ctx: Context, target: Intent, mime: String, title: String) {
    val callback = Intent(ctx, OpenChoiceReceiver::class.java)
        .putExtra(OpenChoiceReceiver.EXTRA_MIME, mime)
    val sender = PendingIntent.getBroadcast(
        ctx, 0, callback,
        PendingIntent.FLAG_UPDATE_CURRENT or
            // 组件是选择器在 send() 时才填进 PendingIntent 的：标 IMMUTABLE 就填不进去
            // （Android 12+ 要求显式声明可变性），回调里会读不到 EXTRA_CHOSEN_COMPONENT。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    )
    val chooser = Intent.createChooser(target, title).apply {
        // 3 参的 createChooser(target, title, sender) 在这个 SDK 上收的是 IntentSender，
        // 直接按文档写法把 sender 塞进 extra 更明确（系统内部就是这么做的）
        putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, sender.intentSender)
        // 调用方可能是 application context（后台/服务里点消息），必须新建任务栈
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        ctx.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "没有可处理该文件的应用", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "打开失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 选择器的点选回调：把「这类文件 → 这个程序」记进**本应用自己的**偏好（不写系统的默认应用表，
 * 所以设置页一个「重置」就能全部清掉，且不影响系统里任何其它应用）。
 * 回调 intent 是显式指向本类的，`exported=false` 也收得到。
 */
class OpenChoiceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mime = intent.getStringExtra(EXTRA_MIME)
        @Suppress("DEPRECATION")   // getParcelableExtra(String) 在新 API 上被 Class 重载取代，minSdk 28 只能用旧的
        val comp: ComponentName? = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        if (mime.isNullOrEmpty() || comp == null) {
            Log.w(TAG, "选择器回调缺 mime/组件，没记住：mime=$mime comp=$comp")
            return
        }
        if (!AppCore.initialized) {
            Log.w(TAG, "进程未初始化，本次选择没记住：$mime → $comp")
            return
        }
        AppCore.prefs.rememberOpener(mime, comp)
        Log.i(TAG, "记住打开方式：$mime → $comp")
    }

    companion object {
        /** 这条记忆要挂到哪个 MIME 上（由 [startActivityViaChooser] 塞进回调 intent） */
        const val EXTRA_MIME = "pp_opener_mime"
    }
}
