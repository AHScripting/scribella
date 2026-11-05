package dev.ownednotes.app

import android.graphics.RectF
import dev.ownednotes.core.Notebook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * P106 (F142): read page text kept on disk under the app cache, so it survives restarts without touching notebook
 * files. Entries are keyed by content (asset ids are SHA-256 of the bytes) and are only ever a cache: a missing,
 * damaged or mismatched entry reads as absent and is deleted, and Android may clear the folder at any time.
 */
internal class SearchIndex(private val folder: File, private val budgetBytes: Long = MAX_BYTES,
    private val entryLimit: Int = MAX_ENTRIES, private val maintenanceEvery: Int = 16) {
    init { require(budgetBytes > 0 && entryLimit > 0 && maintenanceEvery > 0) }
    // Multiple owners of the same directory share a bounded set of locks; no unbounded path registry.
    private val lock = locks[(folder.absoluteFile.normalize().path.hashCode() and Int.MAX_VALUE) % locks.size]
    private var initialized = false
    private var writes = 0
    private fun file(key: String) = File(folder, MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")

    fun get(key: String): JSONObject? = synchronized(lock) {
        if (!safeFolder()) return@synchronized null
        maintain()
        val file = file(key)
        if (!plain(file)) return@synchronized null
        runCatching {
            val bytes = file.inputStream().use { it.readNBytes(MAX_ENTRY_BYTES + 1) }
            check(bytes.size <= MAX_ENTRY_BYTES)
            JSONObject(bytes.toString(Charsets.UTF_8)).takeIf { it.optInt("v") == VERSION && it.optString("key") == key }!!
                .getJSONObject("data").also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrElse { file.delete(); null }
    }

    fun put(key: String, data: JSONObject) = synchronized(lock) {
        runCatching {
            val bytes = JSONObject().put("v", VERSION).put("key", key).put("data", data).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_ENTRY_BYTES || !safeFolder(create = true)) return@runCatching
            maintain()
            val target = file(key)
            val temp = File(folder, target.name + ".tmp-" + Thread.currentThread().id)
            if (!absentOrPlain(target) || !absentOrPlain(temp)) return@runCatching
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) { target.delete(); if (!temp.renameTo(target)) temp.delete() }
            target.setLastModified(System.currentTimeMillis())
            maintain(written = true)
        }
        Unit
    }

    /** Entries held and their bytes on disk. */
    fun size(): Pair<Int, Long> = synchronized(lock) {
        if (!safeFolder()) return@synchronized 0 to 0L
        maintain()
        val files = entries()
        files.size to files.sumOf { it.length() }
    }
    /** P108 (F190): removes every entry, including half-written ones. Only this cache folder is touched. */
    fun clear() = synchronized(lock) {
        if (safeFolder()) folder.listFiles()?.filter { plain(it) }?.forEach { it.delete() }
        initialized = true; writes = 0
    }

    private fun safeFolder(create: Boolean = false): Boolean = runCatching {
        if (create && !Files.exists(folder.toPath(), NOFOLLOW_LINKS)) Files.createDirectories(folder.toPath())
        Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)
    }.getOrDefault(false)
    private fun plain(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    private fun absentOrPlain(file: File) = !Files.exists(file.toPath(), NOFOLLOW_LINKS) || plain(file)
    private fun entries() = folder.listFiles { f -> f.name.endsWith(".json") && plain(f) }.orEmpty().toList()

    /** First access reclaims old oversized caches; regular maintenance amortizes directory scans. */
    private fun maintain(written: Boolean = false) {
        if (initialized && (!written || ++writes < maintenanceEvery)) return
        initialized = true; writes = 0
        val files = entries().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        var bytes = files.sumOf { it.length() }; var count = files.size
        for (file in files) {
            val length = file.length()
            if (length > MAX_ENTRY_BYTES || bytes > budgetBytes || count > entryLimit) {
                if (file.delete()) { bytes -= length; count-- }
            }
        }
        val expired = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { tempName.matches(it.name) && plain(it) && it.lastModified() < expired }
            ?.forEach { it.delete() }
    }

    companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 4000
        const val MAX_BYTES = 32 * 1024 * 1024L
        const val MAX_ENTRY_BYTES = 1024 * 1024
        private val locks = Array(16) { Any() }
        private val tempName = Regex("[a-f0-9]{64}\\.json\\.tmp-[0-9]+")

        fun box(r: RectF) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())
        fun box(a: JSONArray) = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())

        fun lines(lines: List<PageText.Line>) = JSONArray().apply {
            lines.forEach { line ->
                put(JSONObject().put("t", line.text).put("b", box(line.box)).put("w", JSONArray().apply {
                    line.words.forEach { put(JSONObject().put("t", it.text).put("b", box(it.box)).put("s", it.start).put("e", it.end)) }
                }))
            }
        }
        fun lines(a: JSONArray): List<PageText.Line> = (0 until a.length()).map { i ->
            val line = a.getJSONObject(i); val words = line.getJSONArray("w")
            PageText.Line(line.getString("t"), box(line.getJSONArray("b")), (0 until words.length()).map { j ->
                val w = words.getJSONObject(j)
                PageText.Word(w.getString("t"), box(w.getJSONArray("b")), w.getInt("s"), w.getInt("e"))
            })
        }
        fun strings(values: List<String>) = JSONArray().apply { values.forEach { put(it) } }
        fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    }
}

/**
 * P106 (F142): reads the open notebook's PDF, image and photo pages in the background, one page at a time on its own
 * low-priority thread (never on NotesApplication.io, which does saves), so Search in notebook finds printed text at
 * once. A newer notebook snapshot replaces the walk in progress; pages already read are skipped.
 */
/** Compares by reference: a page's stroke list is replaced, never changed, when its ink changes. */
private class Identity(val value: Any) {
    override fun equals(other: Any?) = other is Identity && other.value === value
    override fun hashCode() = System.identityHashCode(value)
}

internal class SearchIndexer(private val app: NotesApplication) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "search-index").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var scheduled: List<Any>? = null
    /** True once the latest walk has finished, for tests. */
    @Volatile var idle = true; private set
    /** B19 (P136): called on the main thread after a page's handwriting was read, so an open search can update. */
    var onInkRead: (() -> Unit)? = null
    /** Quiet time after the last change before handwriting is read, so writing is not interrupted. */
    @Volatile var inkDelayMs = 1500L

    /** Main thread: called on every repository change; starts a walk only when the readable pages changed. */
    fun schedule(notebook: Notebook?) {
        val pages = notebook?.pages?.filter { PageText.readable(it) }.orEmpty()
        // B19 (P136): pages with ink, read for handwriting search; the same stroke list means the same ink.
        val inked = notebook?.pages?.filter { InkText.readable(it) }.orEmpty()
        val signature = listOf<Any>(notebook?.id.orEmpty(), PersonalVocabulary.snapshot.fingerprint) + pages.map { listOf(it.pdfBackground, it.imageBackground, it.width, it.height, it.images.map { i -> i.assetId to i.orientation }) } +
            inked.map { Identity(it.drawnStrokes) }
        if (signature == scheduled) return
        scheduled = signature
        val mine = generation.incrementAndGet()
        if (notebook == null || (pages.isEmpty() && inked.isEmpty())) { idle = true; return }
        idle = false
        executor.execute {
            try {
                readPrinted(notebook, pages, { generation.get() != mine }, progress = {
                    app.main.post { if (generation.get() == mine) onInkRead?.invoke() }
                })
                if (generation.get() != mine) return@execute
                if (inked.isEmpty()) return@execute
                // A newer change during the pause replaces this walk; the selected page is read first.
                val until = System.currentTimeMillis() + inkDelayMs
                while (System.currentTimeMillis() < until) { if (generation.get() != mine) return@execute; Thread.sleep(50) }
                val first = app.activeDocument.selectedPageId
                for (page in inked.sortedBy { if (it.id == first) 0 else 1 }) {
                    if (generation.get() != mine) return@execute
                    if (InkText.cachedPage(page) != null) continue
                    val before = InkText.pagesRead
                    val ready = try { InkText.read(notebook.id, page) { generation.get() != mine } } catch (_: Exception) { false }
                    if (!ready) {
                        // A later event may retry identical ink once the model/reader is available again.
                        // An old walk must not invalidate a newer walk's deduplication signature.
                        app.main.post { if (generation.get() == mine) scheduled = null }
                        return@execute
                    }
                    if (InkText.pagesRead != before) app.main.post { onInkRead?.invoke() }
                }
            } finally { if (generation.get() == mine) idle = true }
        }
    }

    private fun readPrinted(notebook: Notebook, pages: List<dev.ownednotes.core.Page>, cancelled: () -> Boolean,
        progress: () -> Unit, read: (Boolean) -> Unit = {}) {
        PdfBookText.index(app, notebook, cancelled, progress)
        for ((index, page) in pages.withIndex()) {
            if (cancelled()) return
            val embedded = PdfBookText.cached(page)
            val ok = if (!embedded.isNullOrBlank() && page.images.isEmpty()) true
                else runCatching { if (PageText.cachedPage(page) == null) PageText.readPage(app, notebook, page) }.isSuccess
            read(ok)
            if ((index + 1) % 16 == 0) progress()
        }
        if (!cancelled()) progress()
    }

    /** The same serial low-priority reader as automatic indexing, never the package-save executor. */
    fun readForSearch(notebook: Notebook, cancelled: () -> Boolean, progress: () -> Unit,
        read: (Boolean) -> Unit) {
        executor.execute { readPrinted(notebook, notebook.pages.filter { PageText.readable(it) }, cancelled, progress, read) }
    }

    /** P567: a PDF's own text has no positions, so a page shown in a search result is read once to find the place. */
    fun locate(notebook: Notebook, page: dev.ownednotes.core.Page, cancelled: () -> Boolean, done: () -> Unit) {
        executor.execute {
            if (cancelled()) return@execute
            runCatching { if (PageText.cachedPage(page) == null) PageText.readPage(app, notebook, page) }
            app.main.post(done)
        }
    }

    /**
     * P108 (F190): stops the walk in progress, empties the index and the memory caches, then reads [notebook]'s pages
     * again, then calls [done] on the main thread. Notebook files are never touched; if this is interrupted, pages are read again when needed.
     */
    fun rebuild(notebook: Notebook?, done: () -> Unit = {}) {
        scheduled = null
        val mine = generation.incrementAndGet()
        idle = false
        executor.execute {
            PageText.clearMemory(); InkText.clearMemory(); PageText.index?.clear()
            // The walk queues behind this on the same thread, so [done] runs once the pages were read again.
            app.main.post { if (generation.get() == mine) schedule(notebook); executor.execute { app.main.post(done) } }
        }
    }
}
