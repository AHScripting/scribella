package dev.ownednotes.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/**
 * P714: when the app stops with an error, a short note of why is kept: the time, the thread, what sync was doing, memory
 * in use, and the error's type, message and code lines. Never notebook content. The next start shows it once, so it can
 * be copied and passed on; a copy stays in [SHOWN] until the next stop.
 */
object CrashNote {
    private const val FILE = "last-crash.txt"
    private const val SHOWN = "last-crash-shown.txt"
    /**
     * P718: memory set aside at the start and let go first when the app stops, so the note, the memory report and its
     * summary screen have room even when the app's memory is full (on the Pixel: 64 KB left). Android refuses even
     * small allocations while less than 1% of the heap is free after a collection, so it is 1/32 of the heap
     * (16 MB of 512 MB): 1 MB did not let the note be written. Personal tools only.
     */
    @Volatile private var reserve: ByteArray? = null

    fun install(context: Context) {
        val app = context.applicationContext
        val files = app.filesDir
        val reports = runCatching { app.personalTools }.getOrDefault(false)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (reports) reserve = runCatching { ByteArray((Runtime.getRuntime().maxMemory() / 32).toInt()) }.getOrNull()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            reserve = null
            try { File(files, FILE).writeText(describe(thread, error)) } catch (_: Throwable) {}
            // P716: out of memory: a picture of what filled it, read into a summary on the device (personal tools).
            try {
                if (reports && generateSequence(error) { it.cause }.take(8).any { it is OutOfMemoryError } && MemoryReport.take(app, "The app stopped: out of memory")) {
                    // P718: its summary opens by itself, on a screen in a process of its own that stays when this one stops.
                    MemoryReport.open(app)
                    File(files, FILE).appendText("Memory report: saved on this device; Memory summary reads it.\n")
                }
            } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    internal fun describe(thread: Thread, error: Throwable, now: Long = System.currentTimeMillis()): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val out = StringBuilder(4096)
        out.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))).append('\n')
        out.append("Android: ").append(android.os.Build.VERSION.SDK_INT).append('\n')
        out.append("Thread: ").append(thread.name).append('\n')
        val sync = try { if (LibrarySync.running) LibrarySync.progressText.ifEmpty { "running" } else "not running" } catch (_: Throwable) { "?" }
        out.append("Sync: ").append(sync).append('\n')
        out.append("Memory: ").append(usedMb).append(" of ").append(maxMb).append(" MB\n")
        val seen = IdentityHashMap<Throwable, Boolean>()
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 4 && seen.put(cause, true) == null) {
            out.append(if (depth == 0) "Error: " else "Caused by: ").append(cause.javaClass.name)
            cause.message?.let { out.append(": ").append(it.take(300)) }
            out.append('\n')
            for (frame in cause.stackTrace.take(25)) out.append("  at ").append(frame.toString()).append('\n')
            cause = cause.cause; depth++
        }
        return out.toString()
    }

    /** The note from the last stop, once: it moves to [SHOWN], so the next start shows nothing. */
    fun takeShown(context: Context): String? {
        val files = context.filesDir
        val file = File(files, FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.copyTo(File(files, SHOWN), overwrite = true) }
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
