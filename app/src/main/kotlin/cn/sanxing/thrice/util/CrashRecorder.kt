package cn.sanxing.thrice.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃记录：把未捕获异常堆栈写入 `filesDir/crash/crash-<时间>.log`，
 * 再交回系统默认处理器（保持原有闪退行为）。
 *
 * 用于排查用户反馈的「偶发闪退」。写入目录是应用私有目录，且
 * `res/xml/backup_rules.xml` / `data_extraction_rules.xml` 均为白名单式 `<include>`，
 * 崩溃日志**不会进入云备份或换机迁移**。
 *
 * 即便如此，堆栈里仍可能夹带 AI 请求异常（URL、请求头、Key 片段）或笔记内容片段，
 * 因此写盘前统一走一遍 [redact]，避免日志外泄时把secret一并带走。
 */
object CrashRecorder {

    /** 保留的崩溃日志份数。 */
    private const val KEEP_COUNT = 10

    fun install(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(context.applicationContext, thread, throwable) }
            default?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        // 按修改时间倒序（最新在前）；跳过前 KEEP_COUNT 份，其余（更旧的）删除
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_COUNT)
            ?.forEach { runCatching { it.delete() } }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.log")
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("time: ${Date()}")
            pw.println("thread: ${thread.name} (${thread.id})")
            pw.println("version: ${runCatching { versionName(context) }.getOrNull()}")
            pw.println("----------------------------------------")
            throwable.printStackTrace(pw)
        }
        file.writeText(redact(sw.toString()))
    }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }.versionName
    }

    /**
     * 抹掉日志里可能出现的凭据：API Key / token / Authorization 头等。
     * 保守替换，只做正则遮蔽，不改变其它内容的可读性。
     */
    private fun redact(text: String): String {
        val rules = listOf(
            Regex("(?i)(api[_-]?key\\s*[=:]\\s*)(\\S+)") to "$1<redacted>",
            Regex("(?i)((?:bearer|token|secret|password|passwd)\\s*[=:]\\s*)(\\S+)") to "$1<redacted>",
            Regex("(?i)(x-api-key\\s*[:=]\\s*)(\\S+)") to "$1<redacted>",
            Regex("sk-[A-Za-z0-9_\\-]{8,}") to "sk-<redacted>"
        )
        var out = text
        for ((regex, replacement) in rules) out = out.replace(regex, replacement)
        return out
    }
}
