package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.LruCache
import dev.ownednotes.core.AttachmentAsset
import dev.ownednotes.core.attachmentAssetIds
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.imageAssetIds
import dev.ownednotes.core.ImageAsset
import dev.ownednotes.core.hasBackground
import dev.ownednotes.core.Page
import dev.ownednotes.core.PdfAsset
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Page previews for the page strip and library (design build-order steps 5 and 7).
 * Public methods run on the main thread; rendering runs on a private background thread, never NotesApplication.io,
 * so previews cannot delay saves. Delivered bitmaps are shared with callers and never recycled here;
 * a cancelled render's never-delivered output is owned here and can be released.
 */
class PageThumbnails(
    private val scratch: File,
    maxBytes: Int = previewBudget(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "page-thumbnails").apply { isDaemon = true }
    },
) {
    /** The signature is the page's content hash (strokes, size and PDF background, whose asset id is a SHA-256). */
    data class Key(val notebookId: String, val pageId: String, val width: Int, val signature: Int)

    class Request internal constructor(private val onCancel: (Request) -> Unit) {
        var cancelled = false; private set
        fun cancel() { if (!cancelled) { cancelled = true; onCancel(this) } }
    }

    private class Job(val key: Key) {
        val waiting = linkedMapOf<Request, (Result<Bitmap>) -> Unit>()
        @Volatile var cancelled = false
        var future: Future<*>? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Key, Bitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val running = hashMapOf<Key, Job>()
    private var closed = false
    // Snapshots share unchanged Page instances, so hashing each instance once keeps repeated lookups cheap.
    private val signatures = IdentityHashMap<Page, Int>()
    var hits = 0; private set
    var misses = 0; private set
    val sizeBytes get() = cache.size()
    val maxBytes get() = cache.maxSize()
    val pendingCount get() = running.size

    // B23 (P137): strokes are immutable too, so after a pen lift only the new stroke is hashed, not every sample on the page.
    private val strokeHashes = IdentityHashMap<dev.ownednotes.core.InkStroke, Int>()

    fun key(notebook: Notebook, page: Page, width: Int): Key {
        if (signatures.size > 256) signatures.clear()
        return Key(notebook.id, page.id, outputSize(page, width).first, signatures.getOrPut(page) { signature(page) })
    }

    /** The page's content hash: the same for equal pages, as [Page.hashCode] is. */
    private fun signature(page: Page): Int {
        if (strokeHashes.size > 50_000) strokeHashes.clear()
        return page.strokes.fold(page.copy(strokes = emptyList()).hashCode()) { hash, stroke -> 31 * hash + strokeHashes.getOrPut(stroke) { stroke.hashCode() } }
    }

    /** Synchronous cache lookup for binding a view; null means call [request]. */
    fun cached(notebook: Notebook, pageId: String, width: Int): Bitmap? {
        checkMain()
        if (closed) return null
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: return null
        return cache.get(key(notebook, page, width))?.also { hits++ }
    }

    /**
     * Delivers the preview on the main thread, immediately when cached. Identical concurrent requests share one render.
     * A cancelled request never receives its callback; the render itself stops once no request is waiting for it.
     */
    fun request(notebook: Notebook, pageId: String, width: Int, callback: (Result<Bitmap>) -> Unit): Request {
        checkMain()
        check(!closed) { "Page previews are closed" }
        require(width in 1..MAX_EDGE) { "Thumbnail width must be 1–$MAX_EDGE pixels" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: throw IllegalArgumentException("Page not found")
        val key = key(notebook, page, width)
        val request = Request { cancelled -> running[key]?.let { job -> if (job.waiting.remove(cancelled) != null && job.waiting.isEmpty()) abandon(job) } }
        cache.get(key)?.let { hits++; callback(Result.success(it)); return request }
        misses++
        val job = running.getOrPut(key) { start(Job(key), page, notebook.assets.firstOrNull { it.id == page.pdfBackground?.assetId }, notebook.imageAssets.firstOrNull { it.id == page.imageBackground?.assetId }, notebook.imageAssets.filter { it.id in page.imageAssetIds }, notebook.attachmentAssets.filter { it.id in page.attachmentAssetIds }) }
        job.waiting[request] = callback
        return request
    }

    /** Drops cached previews of a notebook, or of one page, e.g. after a notebook is deleted or replaced. */
    fun invalidate(notebookId: String, pageId: String? = null) {
        checkMain()
        if (closed) return
        running.values.filter { it.key.notebookId == notebookId && (pageId == null || it.key.pageId == pageId) }.forEach { abandon(it) }
        cache.snapshot().keys.filter { it.notebookId == notebookId && (pageId == null || it.pageId == pageId) }.forEach { cache.remove(it) }
        if (pageId == null) { signatures.clear(); strokeHashes.clear() }
        queueDecodedPurge(DecodedScope(notebookId, pageId))
    }

    fun clear() {
        checkMain()
        if (closed) return
        running.values.toList().forEach { abandon(it) }
        cache.evictAll(); signatures.clear(); strokeHashes.clear()
        queueDecodedPurge(DecodedScope(null, null))
    }

    fun close() {
        checkMain()
        if (closed) return
        clear(); closed = true
        // Cancelled renders finish before the final purge, so their private pixels cannot be recycled mid-draw.
        // Never wait on PDF/bitmap work from the UI thread; already-delivered composites belong to their callers.
        executor.shutdown()
    }

    private fun start(job: Job, page: Page, asset: PdfAsset?, image: ImageAsset?, photos: List<ImageAsset>, files: List<AttachmentAsset>): Job {
        check(page.pdfBackground == null || asset != null) { "PDF asset missing for page" }
        job.future = executor.submit {
            val result = if (job.cancelled) null else runCatching {
                render(page, asset, image, photos, files, job.key.width, DecodedScope(job.key.notebookId, job.key.pageId)) { job.cancelled }
            }
            main.post { finish(job, result) }
        }
        return job
    }

    private fun finish(job: Job, result: Result<Bitmap?>?) {
        val bitmap = result?.getOrNull()
        if (job.cancelled || running[job.key] !== job || result == null || (result.isSuccess && bitmap == null)) {
            // This output was never cached or delivered; it is not shared with a View.
            bitmap?.recycle()
            if (running[job.key] === job) running.remove(job.key)
            job.waiting.clear()
            return
        }
        if (bitmap != null) {
            // A newer signature for the same page and width supersedes the old preview.
            cache.snapshot().keys.filter { it.notebookId == job.key.notebookId && it.pageId == job.key.pageId && it.width == job.key.width }.forEach { cache.remove(it) }
            cache.put(job.key, bitmap)
        }
        val outcome = if (bitmap != null) Result.success(bitmap) else Result.failure(result.exceptionOrNull()!!)
        try {
            // Keep ownership until delivery ends: a callback may invalidate this page or cancel a sibling.
            job.waiting.toList().forEach { (request, callback) ->
                if (!job.cancelled && !request.cancelled) {
                    job.waiting.remove(request)
                    callback(outcome)
                }
            }
        } finally {
            job.waiting.clear()
            if (running[job.key] === job) running.remove(job.key)
        }
    }

    private fun abandon(job: Job) {
        job.cancelled = true; job.future?.cancel(false)
        job.waiting.clear()
        if (running[job.key] === job) running.remove(job.key)
    }

    /**
     * B33 (P196): writing on a page re-renders its preview after every stroke. Its PDF page (at preview size) and decoded
     * photos are kept here, render thread only, so the next preview draws only the ink again.
     */
    private val decoded = LinkedHashMap<String, Bitmap>(8, .75f, true)
    private data class DecodedScope(val notebookId: String?, val pageId: String?) {
        fun matches(owner: DecodedScope) = notebookId == null || notebookId == owner.notebookId && (pageId == null || pageId == owner.pageId)
    }
    // Decoded maps are render-worker only. Main-thread invalidations coalesce into one queued purge.
    private val decodedOwners = hashMapOf<String, MutableSet<DecodedScope>>()
    private val purgeLock = Any()
    private val pendingPurges = linkedSetOf<DecodedScope>()
    private var purgeQueued = false
    private fun queueDecodedPurge(scope: DecodedScope) {
        val enqueue = synchronized(purgeLock) {
            if (scope.notebookId == null) pendingPurges.clear()
            if (pendingPurges.none { it.notebookId == null }) {
                pendingPurges.removeAll { scope.matches(it) }; pendingPurges += scope
            }
            if (purgeQueued) false else { purgeQueued = true; true }
        }
        if (!enqueue) return
        executor.execute {
            val scopes = synchronized(purgeLock) { pendingPurges.toList().also { pendingPurges.clear(); purgeQueued = false } }
            val iterator = decoded.entries.iterator()
            while (iterator.hasNext()) {
                val (key, bitmap) = iterator.next()
                val owners = decodedOwners[key]
                owners?.removeAll { owner -> scopes.any { it.matches(owner) } }
                if (owners.isNullOrEmpty()) { iterator.remove(); decodedOwners.remove(key); bitmap.recycle() }
            }
        }
    }
    /** Diagnostic seam, invoked on the supplied serial executor by isolated instrumentation only. */
    internal fun decodedSnapshotForTests(): Map<String, Bitmap> = decoded.toMap()
    private val decodedBudget = defaultBudget().toLong()
    private fun trimDecoded() {
        var bytes = decoded.values.sumOf { it.allocationByteCount.toLong() }
        val eldest = decoded.entries.iterator()
        while (bytes > decodedBudget && eldest.hasNext()) {
            val entry = eldest.next(); val bitmap = entry.value
            bytes -= bitmap.allocationByteCount; decodedOwners.remove(entry.key); eldest.remove(); bitmap.recycle()
        }
    }

    /** Draws like InkCanvas's dry layer: white page, PDF background, then the visible (erased) ink fill. Returns null if cancelled. */
    private fun render(page: Page, asset: PdfAsset?, image: ImageAsset?, photos: List<ImageAsset>, files: List<AttachmentAsset>, width: Int, owner: DecodedScope, cancelled: () -> Boolean): Bitmap? {
        val (w, h) = outputSize(page, width)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val decodedKeys = page.images.map { "${it.assetId}@${maxOf(w, h).coerceIn(1, 2048)}" }.toMutableSet()
        page.pdfBackground?.let { decodedKeys += "pdf:${asset!!.id}:${it.pageIndex}:${w}x$h" }
        try {
            bitmap.eraseColor(page.paper.color.toInt())
            val canvas = Canvas(bitmap)
            page.pdfBackground?.let { background ->
                val pdf = decoded.getOrPut("pdf:${asset!!.id}:${background.pageIndex}:${w}x$h") {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        try { renderPdf(asset, background.pageIndex, it) } catch (error: Throwable) { it.recycle(); throw error }
                    }
                }
                canvas.drawBitmap(pdf, 0f, 0f, null)
            }
            page.imageBackground?.let { ImageSupport.draw(requireNotNull(image) { "Image asset missing for page" }, it, bitmap) }
            canvas.scale(w / page.width, h / page.height)
            canvas.clipRect(0f, 0f, page.width, page.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (!page.hasBackground) PaperRenderer.draw(canvas, page, paint)
            page.paintSlices.forEach { slice ->
            PhotoRendering.drawPage(canvas, slice, photos, maxOf(w, h), decoded = decoded)
            TextRendering.drawPage(canvas, slice)
            AttachmentRendering.drawPage(canvas, slice, files)
            val flat = InkGeometry.flatHighlights(slice.drawnStrokes)
            slice.drawnStrokes.forEachIndexed { index, stroke ->
                if (index % 32 == 0 && cancelled()) { bitmap.recycle(); return null }
                InkGeometry.draw(canvas, stroke, paint, slice.opacityOf(stroke), flat[stroke])
            }
            }
            if (cancelled()) { bitmap.recycle(); return null }
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
        finally {
            decodedKeys.filter { it in decoded }.forEach { decodedOwners.getOrPut(it) { linkedSetOf() } += owner }
            trimDecoded()
        }
    }

    /** Renders straight at preview size, unlike PdfSupport.render's 2048-pixel editor bitmap. */
    private fun renderPdf(asset: PdfAsset, pageIndex: Int, target: Bitmap) {
        PdfAccess.withFile(scratch, asset) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "PDF page not found" }
                    renderer.openPage(pageIndex).use { it.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "PageThumbnails is main-thread only" }

    companion object {
        const val MAX_EDGE = 1024

        /** Very tall pages are capped at MAX_EDGE * 4 pixels high, which narrows the result below the requested width. */
        fun outputSize(page: Page, width: Int): Pair<Int, Int> {
            val scale = min(width.coerceIn(1, MAX_EDGE) / page.width, MAX_EDGE * 4f / page.height)
            return (page.width * scale).roundToInt().coerceIn(1, MAX_EDGE) to (page.height * scale).roundToInt().coerceIn(1, MAX_EDGE * 4)
        }

        fun defaultBudget(): Int = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(32L * 1024 * 1024).toInt()
        /**
         * P581: the preview cache must hold the three full previews a page turn needs (this page and both neighbours, about
         * 6 MB each). A sixteenth of a phone's 192 MB limit is 12 MB, so they pushed each other out and the curl never got ready.
         */
        fun previewBudget(): Int = maxOf(defaultBudget().toLong(), minOf(Runtime.getRuntime().maxMemory() / 8, 24L * 1024 * 1024)).toInt()
    }
}
