package dev.ownednotes.app

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.ownednotes.core.*
import dev.ownednotes.core.Point
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Visible PDF detail over the existing page image. All live state belongs to the main thread. */
internal class PdfTileLayer(private val app: NotesApplication, private val changed: () -> Unit) {
    internal class Identity(private val page: Page) {
        override fun equals(other: Any?) = other is Identity && other.page === page
        override fun hashCode() = System.identityHashCode(page)
    }
    internal data class Source(val assetId: String, val index: Int, val width: Float, val height: Float, val content: Identity?)
    internal data class Key(val source: Source, val scale: Float, val column: Int, val row: Int)
    internal data class Request(val key: Key, val note: Notebook, val page: Page, val flattened: Boolean)
    internal data class Batch(val images: List<Pair<Key, Bitmap>>, val failed: Set<Key>, val error: String?)
    data class Stats(val residentBytes: Long, val tiles: Int, val missing: Int, val rendering: Boolean, val error: String?)
    private var attached = false
    private val generation = AtomicLong()
    private var rendering = false
    private var wanted = linkedMapOf<Key, Request>()
    private var frame = linkedMapOf<Key, Request>()
    private val cache = LinkedHashMap<Key, Bitmap>(32, .75f, true)
    private val failed = mutableSetOf<Key>()
    private var bytes = 0L
    private var lastError: String? = null
    private val sourceRect = Rect(1, 1, EDGE + 1, EDGE + 1)
    private val destination = RectF()
    val stats get() = Stats(bytes, cache.size, wanted.keys.count { it !in cache && it !in failed }, rendering, lastError)
    fun attach() { attached = true }
    fun detach() {
        attached = false; generation.incrementAndGet(); wanted.clear(); frame.clear(); failed.clear(); cache.clear(); bytes = 0
        // Display lists can still reference displayed bitmaps. Never recycle those on eviction/detach.
    }
    fun beginFrame() { frame.clear() }
    fun draw(canvas: Canvas, note: Notebook, page: Page, viewport: Matrix, viewBounds: RectF, flattened: Boolean = false) {
        val background = page.pdfBackground ?: return
        val values = FloatArray(9); viewport.getValues(values)
        val pixelsPerUnit = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        if (!pixelsPerUnit.isFinite() || pixelsPerUnit <= 0f || max(page.width, page.height) * pixelsPerUnit <= 2048f) return
        // Quarter-octave levels bound oversampling while avoiding a new cache level for every pinch sample.
        val scale = 2.0.pow(ceil(log2(pixelsPerUnit.toDouble()) * 4.0) / 4.0).toFloat()
        val inverse = Matrix(); if (!viewport.invert(inverse)) return
        val visible = RectF(viewBounds); inverse.mapRect(visible)
        if (!visible.intersect(0f, 0f, page.width, page.height)) return
        val source = Source(background.assetId, background.pageIndex, page.width, page.height, if (flattened) Identity(page) else null)
        val left = floor(visible.left * scale / EDGE).toInt().coerceAtLeast(0)
        val top = floor(visible.top * scale / EDGE).toInt().coerceAtLeast(0)
        val right = (ceil(visible.right * scale / EDGE).toInt() - 1).coerceAtLeast(left)
        val bottom = (ceil(visible.bottom * scale / EDGE).toInt() - 1).coerceAtLeast(top)
        for (row in top..bottom) for (column in left..right) {
            val key = Key(source, scale, column, row)
            frame[key] = Request(key, note, page, flattened)
            cache[key]?.let { bitmap ->
                destination.set(column * EDGE / scale, row * EDGE / scale, (column+1) * EDGE / scale, (row+1) * EDGE / scale)
                canvas.drawBitmap(bitmap, sourceRect, destination, PAINT)
            }
        }
    }
    fun endFrame() {
        if (wanted.keys != frame.keys) {
            generation.incrementAndGet(); failed.clear(); lastError = null
            val currentSources = frame.keys.map { it.source }.toSet()
            val stale = cache.entries.iterator()
            while (stale.hasNext()) {
                val entry = stale.next()
                if (entry.key.source.content != null && entry.key.source !in currentSources) {
                    bytes -= entry.value.allocationByteCount; stale.remove()
                }
            }
        }
        wanted = LinkedHashMap(frame)
        startBatch()
    }
    private fun startBatch() {
        if (!attached || rendering) return
        val requests = wanted.values.filter { it.key !in cache && it.key !in failed }.take(8)
        if (requests.isEmpty()) return
        val version = generation.get(); rendering = true
        app.pageRendering.execute {
            val batch = render(app, requests) { generation.get() != version }
            app.main.post {
                rendering = false
                for ((key, bitmap) in batch.images) {
                    if (!attached || version != generation.get() || key !in wanted) bitmap.recycle()
                    else {
                        cache.put(key, bitmap)?.let { bytes -= it.allocationByteCount }
                        bytes += bitmap.allocationByteCount
                    }
                }
                if (version == generation.get()) { failed.addAll(batch.failed); if (batch.error != null) lastError = batch.error }
                // Prefer evicting tiles outside the visible region; displayed images are left for GC.
                fun trim(onlyOutside: Boolean) {
                    val iterator = cache.entries.iterator()
                    while (bytes > MAX_BYTES && iterator.hasNext()) {
                        val entry = iterator.next()
                        if (!onlyOutside || entry.key !in wanted) {
                            if (entry.key in wanted) {
                                // Keep the base image here rather than repeatedly rendering and evicting the same tile.
                                failed.add(entry.key); lastError = "Visible PDF detail exceeds the cache budget"
                            }
                            bytes -= entry.value.allocationByteCount; iterator.remove()
                        }
                    }
                }
                trim(true); trim(false)
                changed(); startBatch()
            }
        }
    }
    internal fun pixelAt(page: Page, point: Point): Int? {
        val key = wanted.keys.firstOrNull { it.source.assetId == page.pdfBackground?.assetId && it.source.index == page.pdfBackground?.pageIndex &&
            floor(point.x * it.scale / EDGE).toInt() == it.column && floor(point.y * it.scale / EDGE).toInt() == it.row } ?: return null
        val bitmap = cache[key] ?: return null
        return bitmap.getPixel((point.x * key.scale - key.column * EDGE).toInt() + 1, (point.y * key.scale - key.row * EDGE).toInt() + 1)
    }
    companion object {
        const val EDGE = 512
        const val MAX_BYTES = 64L * 1024 * 1024
        private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
        internal fun render(app: NotesApplication, requests: List<Request>, cancelled: () -> Boolean): Batch {
            val images = mutableListOf<Pair<Key, Bitmap>>(); val failures = mutableSetOf<Key>(); var error: String? = null
            for ((_, group) in requests.groupBy { it.key.source }) {
                if (cancelled()) break
                val first = group.first()
                try {
                    val asset = first.note.assets.first { it.id == first.key.source.assetId }
                    PdfAccess.withFile(app.cacheDir, asset) { file ->
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer -> renderer.openPage(first.key.source.index).use { pdf ->
                                val decoded = mutableMapOf<String, Bitmap>()
                                try {
                                    for (request in group) {
                                        if (cancelled()) break
                                        val key = request.key
                                        val bitmap = Bitmap.createBitmap(EDGE+2, EDGE+2, Bitmap.Config.ARGB_8888)
                                        try {
                                            bitmap.eraseColor(Color.WHITE)
                                            val matrix = Matrix().apply {
                                                setScale(request.page.width / pdf.width * key.scale, request.page.height / pdf.height * key.scale)
                                                postTranslate(1f - key.column * EDGE, 1f - key.row * EDGE)
                                            }
                                            pdf.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            if (request.flattened) {
                                                val canvas = Canvas(bitmap)
                                                canvas.translate(1f - key.column * EDGE, 1f - key.row * EDGE); canvas.scale(key.scale, key.scale)
                                                canvas.clipRect(0f, 0f, request.page.width, request.page.height)
                                                ExportRendering.drawObjects(canvas, request.note, request.page, decodedPhotos = decoded)
                                            }
                                            images += key to bitmap
                                        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
                                    }
                                } finally { decoded.values.forEach { it.recycle() } }
                            } }
                        }
                    }
                } catch (failure: Throwable) { failures.addAll(group.map { it.key }); error = failure.message ?: "PDF detail unavailable" }
            }
            return Batch(images, failures, error)
        }
    }
}
