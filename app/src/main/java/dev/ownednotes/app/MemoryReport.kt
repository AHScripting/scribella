package dev.ownednotes.app

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.OpenableColumns
import dev.ownednotes.core.HeapSummary
import java.io.File
import java.io.FilterOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P716: a picture of the app's memory (an Android heap dump), for finding what fills the 512 MB an app may use. On the
 * Pixel the app ran out of it while the emulator, with the same German notebook, used about 100 MB, so the Pixel's own
 * memory has to be seen. Taken once per run when the app's memory stays above [HIGH] of its limit after a collection,
 * and when the app stops with OutOfMemoryError; the later one replaces the earlier. It stays in the app's own storage
 * until the user deletes it or a new one replaces it (P717: saving no longer deletes it, since OneDrive on the Pixel took
 * the file and kept none of it). It holds whatever was in memory, notebooks included, so the app never sends it anywhere
 * by itself; [begin] reads it on the device into a short text of class names and numbers. Personal tools only.
 */
object MemoryReport {
    private const val FILE = "memory-report.hprof"
    private const val ABOUT = "memory-report.txt"
    private const val SUMMARY = "memory-report-summary.txt"
    /** The report's second name while it is read. */
    private const val READING = "memory-report-reading.hprof"
    /** The share of the memory limit that counts as high. */
    const val HIGH = 0.70
    private const val CHECK_MS = 30_000L
    @Volatile private var watching = false
    @Volatile private var taken = false

    private fun file(context: Context) = File(context.filesDir, FILE)
    fun available(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }
    /** When and why the report was taken, and its size, for the button that saves it. */
    fun about(context: Context): String? = if (!available(context)) null else
        (runCatching { File(context.filesDir, ABOUT).readText().trim() }.getOrNull() ?: "Memory report") + " · ${file(context).length() / (1024 * 1024)} MB"
    fun fileName(): String = "notes-memory-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".hprof.gz"

    private fun usedMb(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / (1024 * 1024) }
    private fun maxMb() = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    /** Writes the heap to the report file ([why]: what happened). Never throws; false when it could not be written. */
    fun take(context: Context, why: String): Boolean {
        val files = context.applicationContext.filesDir
        val part = File(files, "$FILE.part")
        return try {
            val used = usedMb()
            part.delete()
            // P717: what is still in use, not garbage waiting to be collected (the dump does not collect first).
            Runtime.getRuntime().gc()
            Debug.dumpHprofData(part.path)
            val target = File(files, FILE)
            if (!part.renameTo(target)) { target.delete(); check(part.renameTo(target)) }
            File(files, ABOUT).writeText("$why, ${SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())}, $used of ${maxMb()} MB in use")
            File(files, SUMMARY).delete()
            taken = true
            true
        } catch (_: Throwable) { part.delete(); false }
    }

    /** Looks at the app's memory every 30 s while it runs; one report per run when it stays high. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        Thread({
            while (!taken) {
                SystemClock.sleep(CHECK_MS)
                val runtime = Runtime.getRuntime()
                fun high() = runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * HIGH
                // P718: a report already here (often from the app stopping out of memory) is not replaced by this one.
                if (!high() || available(app)) continue
                System.gc(); SystemClock.sleep(1000)
                if (high() && !available(app)) take(app, "Memory high")
            }
        }, "memory-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** What [save] wrote, and the size the chosen place reports afterwards (null when it does not say). */
    class Saved(val written: Long, val reported: Long?)

    /**
     * Copies the report, compressed (gzip, about a third of the size), to [uri] chosen by the user. The report stays here:
     * P717, OneDrive's save dialog accepted the whole file and kept 0 bytes of it. Call off the main thread.
     */
    fun save(context: Context, uri: Uri): Saved {
        val source = file(context)
        check(source.isFile) { "There is no memory report" }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the chosen file")
        var written = 0L
        val counting = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            override fun write(b: Int) { out.write(b); written++ }
        }
        object : GZIPOutputStream(counting, 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }.use { gz ->
            source.inputStream().use { it.copyTo(gz, 1 shl 20) }
        }
        val reported = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        return Saved(written, reported)
    }

    /** Removes the report and what goes with it. */
    fun delete(context: Context) {
        val files = context.filesDir
        // memory-report-failed.txt and memory-report-step.txt: left by P717's reader.
        for (name in listOf(FILE, ABOUT, SUMMARY, READING, "$FILE.part", "memory-report-failed.txt", "memory-report-step.txt")) File(files, name).delete()
    }

    /** Writes [text] whole or not at all. */
    private fun publish(file: File, text: String) {
        val part = File(file.path + ".part")
        part.writeText(text)
        if (!part.renameTo(file)) { file.delete(); check(part.renameTo(file)) { "Cannot write ${file.name}" } }
    }

    /**
     * P718: opens the memory summary ([MemoryReportActivity], in the ":report" process). The app's process only asks for
     * the screen, which needs next to no memory: on the Pixel that process was too full even to wait for a reading
     * (P717 read in another process, but waited and polled here). Never throws; false when the screen could not be asked for.
     */
    fun open(context: Context): Boolean =
        try { context.startActivity(MemoryReportActivity.intent(context)); true } catch (_: Throwable) { false }

    /** In the ":report" process: a reading of the report ([stamp]: its size and time), with its progress and outcome. */
    class Reading(internal val stamp: String) {
        @Volatile var step = ""
        @Volatile var text: String? = null
        @Volatile var failed: String? = null
        val done get() = text != null || failed != null
    }
    @Volatile private var current: Reading? = null
    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    /**
     * In the ":report" process: the summary of the report on this device: the one kept beside it, the reading under way,
     * or a new reading. The text holds class and field names and numbers only (HeapSummary: what fills the memory and
     * what holds it), so it can be copied and passed on without moving hundreds of MB.
     */
    @Synchronized internal fun begin(context: Context): Reading {
        val app = context.applicationContext
        val report = file(app)
        if (!report.isFile) return Reading("").apply { failed = "there is no memory report on this device" }
        val stamp = stamp(report)
        current?.let { if (it.stamp == stamp && it.failed == null) return it }
        val kept = File(app.filesDir, SUMMARY)
        if (kept.isFile && kept.length() > 0) runCatching { kept.readText() }.getOrNull()?.let { text ->
            return Reading(stamp).apply { this.text = text }.also { current = it }
        }
        val reading = Reading(stamp)
        current = reading
        Thread({ read(app, reading) }, "memory-summary").start()
        return reading
    }

    /**
     * Reads the report into [reading] and, while the report is still the same one, keeps the text beside it. The report
     * is read through a second name (a hard link), so a new report taken meanwhile does not change it between passes.
     * Never throws.
     */
    private fun read(context: Context, reading: Reading) {
        val files = context.filesDir
        val report = file(context)
        val held = File(files, READING)
        var peak = usedMb()
        try {
            val why = runCatching { File(files, ABOUT).readText().trim() }.getOrNull() ?: "Memory report"
            held.delete()
            val source = if (runCatching { android.system.Os.link(report.path, held.path) }.isSuccess) held else report
            val started = SystemClock.elapsedRealtime()
            val body = HeapSummary.of(source) { step -> peak = maxOf(peak, usedMb()); reading.step = step }
            peak = maxOf(peak, usedMb())
            val text = "Memory summary: $why\nAndroid ${android.os.Build.VERSION.SDK_INT}, read in " +
                "${(SystemClock.elapsedRealtime() - started) / 1000} s by a process of its own (up to $peak of ${maxMb()} MB)\n$body"
            if (stamp(report) == reading.stamp) runCatching { publish(File(files, SUMMARY), text) }
            reading.text = text
        } catch (e: Throwable) {
            reading.failed = "${e.javaClass.simpleName}: ${e.message ?: ""} (reader: up to ${maxOf(peak, usedMb())} of ${maxMb()} MB)"
        } finally { held.delete() }
    }
}
