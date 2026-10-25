package app.forkcast

import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A local crash report (REVIEW-2 S7; roadmap 2.0 Phase 2). When the app closes on an uncaught
 * error, a short report is written to app storage that isn't backed up; the next launch offers
 * to save it (Today's card) and nothing is ever sent. It holds the app and Android versions, the
 * device model and the error's class names and code locations only: never the error's message,
 * which can carry food names, notes, server replies or a key.
 */
object CrashReports {
    private const val DIR = "crash"
    private const val FILE = "last.txt"
    private const val MAX_FRAMES = 60
    private const val MAX_CAUSES = 8

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, report(error, thread == Looper.getMainLooper().thread, Instant.now())) }
            // Android's own handling (the "app closed" dialog and process exit) carries on.
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): File? = file(context).takeIf { it.isFile }

    fun discard(context: Context) {
        file(context).delete()
    }

    internal fun write(context: Context, text: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    /** The whole report. Messages are left out on purpose; see the class comment. */
    internal fun report(error: Throwable, onMainThread: Boolean, at: Instant): String = buildString {
        appendLine("Forkcast crash report")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Time: ${at.truncatedTo(ChronoUnit.MINUTES)}")
        appendLine("Thread: ${if (onMainThread) "main" else "background"}")
        appendLine()
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            appendLine(if (depth == 0) current.javaClass.name else "Caused by: ${current.javaClass.name}")
            current.stackTrace.take(MAX_FRAMES).forEach { appendLine("    at ${frame(it)}") }
            if (current.stackTrace.size > MAX_FRAMES) appendLine("    … ${current.stackTrace.size - MAX_FRAMES} more")
            current = current.cause
            depth++
        }
    }

    /** "class.method(File.kt:12)", rebuilt field by field so nothing else can slip in. */
    private fun frame(e: StackTraceElement): String {
        val where = when {
            e.isNativeMethod -> "Native Method"
            e.fileName != null && e.lineNumber >= 0 -> "${e.fileName}:${e.lineNumber}"
            e.fileName != null -> e.fileName
            else -> "Unknown Source"
        }
        return "${e.className}.${e.methodName}($where)"
    }

    private fun file(context: Context) = File(File(context.noBackupFilesDir, DIR), FILE)
}
