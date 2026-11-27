package dev.ownednotes.app

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P726: Android's own record of why the app's process ended, for the stops the crash note cannot see because the app's
 * own error handler never ran: "not responding", a crash in native code, or being ended by Android for memory. Android
 * keeps the last few for each app, also across updates. Times, reasons and memory, and for "not responding" the code
 * lines of the main thread and of this app's other threads. Never notebook content. Personal tools only (Library sync,
 * Last exits).
 */
object ExitHistory {
    /** The text shown, newest exit first; [max] 0 is every exit Android still keeps (an install alone adds three). Never throws. */
    fun describe(context: Context, max: Int = 0): String {
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, max)
        }.getOrElse { return "Android did not give the app's exit records: ${it.message}" }
        if (exits.isEmpty()) return "Android has no record of the app's earlier exits."
        val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.US)
        return buildString {
            for (e in exits) {
                append(time.format(Date(e.timestamp))).append(" · ").append(reason(e.reason))
                e.processName?.removePrefix(context.packageName)?.takeIf { it.isNotEmpty() }?.let { append(" · process ").append(it) }
                append('\n')
                e.description?.takeIf { it.isNotBlank() }?.let { append("  Android says: ").append(it.take(300)).append('\n') }
                append("  Memory: ").append(e.pss / 1024).append(" MB (").append(e.rss / 1024).append(" MB resident) · ").append(importance(e.importance))
                if (e.reason == ApplicationExitInfo.REASON_SIGNALED || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) append(" · signal ").append(e.status)
                else if (e.status != 0) append(" · status ").append(e.status)
                append('\n')
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { read(it, 4_000_000) } }.getOrNull()
                    if (trace.isNullOrEmpty()) append("  (no thread record kept)\n") else append(threads(trace))
                }
                append('\n')
            }
        }.trimEnd()
    }

    private fun read(reader: java.io.Reader, limit: Int): String {
        val out = StringBuilder(); val buffer = CharArray(8192)
        while (out.length < limit) { val n = reader.read(buffer); if (n < 0) break; out.append(buffer, 0, n) }
        return out.toString()
    }

    internal fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "the app closed itself"
        ApplicationExitInfo.REASON_SIGNALED -> "ended by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "ended by Android: the phone was low on memory"
        ApplicationExitInfo.REASON_CRASH -> "stopped with an error (the crash note has it)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ended by Android: used too much"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped on request (force stop, install or test)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on ended"
        ApplicationExitInfo.REASON_OTHER -> "ended by Android (other)"
        ApplicationExitInfo.REASON_FREEZER -> "ended while frozen in the background"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app was turned off or changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was updated"
        else -> "reason $code"
    }

    private fun importance(level: Int): String = when {
        level <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        level <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        level <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "working in the background"
        else -> "in the background"
    }

    /**
     * From an Android "not responding" record: the main thread's header and code lines, then up to six other threads
     * that run this app's code, with their first lines. Native lines are left out.
     */
    internal fun threads(trace: String, owner: String = "dev.ownednotes."): String {
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        for (line in trace.lineSequence()) {
            when {
                line.startsWith("\"") -> current = mutableListOf(line).also { blocks += it }
                line.isBlank() -> current = null
                else -> current?.add(line)
            }
        }
        fun shown(block: List<String>, lines: Int) = buildString {
            append("  ").append(block.first().take(160)).append('\n')
            block.drop(1).filter { it.trimStart().startsWith("at ") || it.trimStart().startsWith("- ") }.take(lines)
                .forEach { append("    ").append(it.trim().take(200)).append('\n') }
        }
        val main = blocks.firstOrNull { it.first().startsWith("\"main\"") }
        val others = blocks.filter { it !== main && it.any { line -> line.contains("at $owner") } }.take(6)
        if (main == null && others.isEmpty()) return "  (no threads found in the record)\n"
        return buildString {
            main?.let { append(shown(it, 30)) }
            others.forEach { append(shown(it, 12)) }
        }
    }
}
