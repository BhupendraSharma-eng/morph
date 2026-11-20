package bhupendra.morph.data

import android.content.Context
import android.os.Build
import bhupendra.morph.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Local-only crash capture (single user, nothing leaves the phone). Crashes are written to
 * Android/data/bhupendra.morph/files/crashes/ — pull with:
 *   adb pull /sdcard/Android/data/bhupendra.morph/files/crashes
 * and the next launch shows a card so a crash never goes unnoticed.
 */
object CrashReporter {
    private fun dir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes").apply { mkdirs() }
    private fun pending(context: Context) = File(context.filesDir, "unseen_crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val report = buildString {
                    appendLine("Morph ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}")
                    appendLine("${LocalDateTime.now()} · thread ${thread.name}")
                    appendLine()
                    append(trace)
                }
                val d = dir(context)
                File(d, "crash-${System.currentTimeMillis()}.txt").writeText(report)
                d.listFiles()?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
                pending(context).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the last unseen crash report once, then forgets it. */
    fun consumeUnseen(context: Context): String? {
        val f = pending(context)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
