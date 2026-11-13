package dev.ownednotes.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.ownednotes.core.AttachmentAsset
import dev.ownednotes.core.attachmentAssetIds
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.imageAssetIds
import dev.ownednotes.core.ImageAsset
import dev.ownednotes.core.hasBackground
import dev.ownednotes.core.Page
import dev.ownednotes.core.PageLayout
import dev.ownednotes.core.PdfAsset
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Read-only continuous mode (design decision D1, groundwork for build-order step 6): the whole notebook as a vertical
 * strip laid out by core [PageLayout]. Each page near the viewport is drawn from its own screen-resolution bitmap
 * (white page, PDF background, visible dry ink as InkCanvas draws it), rendered on this view's own background thread.
 * Only the visible pages plus [overscan] are rendered; bitmaps that leave that band are recycled to stay within
 * [budgetBytes]. Fingers scroll with a fling; the pen does nothing. Main thread only. Not yet mounted by the editor.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ContinuousPageView(
    context: Context,
    private val scratch: File = context.cacheDir,
    val budgetBytes: Long = defaultBudget(),
) : View(context) {
    /** Called when the page at the viewport centre changes, including the first layout of a notebook. */
    var onCurrentPageChanged: (index: Int, pageId: String) -> Unit = { _, _ -> }
    /** Called on the main thread when a page cannot be rendered (e.g. its PDF asset is unreadable); the page stays white. */
    var onRenderFailed: (pageId: String, message: String) -> Unit = { _, _ -> }
    /** Called after any scroll, layout or notebook change, so an editor laid over a page can follow it (D011). */
    var onViewportChanged: () -> Unit = {}
    /** Desk colour behind the pages (design token); presentation only. */
    var deskColor = Tokens.DESK; set(value) { field = value; invalidate() }
    /** Extra band rendered above and below the viewport, as a fraction of the viewport height. */
    var overscan = .5f; set(value) { require(value.isFinite() && value >= 0f); field = value; update() }

    private class Entry(val page: Page, val bitmap: Bitmap) { val bytes get() = bitmap.allocationByteCount.toLong() }
    private class Job(val page: Page, val width: Int, val height: Int) {
        @Volatile var cancelled = false
        var future: Future<*>? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    // Access-ordered, so iteration starts at the least recently drawn page.
    private val cache = LinkedHashMap<String, Entry>(16, .75f, true)
    private val jobs = hashMapOf<String, Job>()
    private val failed = hashMapOf<String, Page>()
    private val scroller = OverScroller(context)
    private val configuration = ViewConfiguration.get(context)
    private var velocity: VelocityTracker? = null
    private var activePointer = -1
    private var dragging = false
    private var downY = 0f
    private var lastY = 0f
    private var reportedIndex = -1
    private var reportedPageId: String? = null

    var notebook: Notebook? = null; private set
    var layout: PageLayout? = null; private set
    var scrollOffset = 0f; private set
    var evictions = 0; private set

    val pageCount get() = layout?.slots?.size ?: 0
    /** P176 (G1b): bands at the top and bottom covered by floating chrome. The strip scrolls under them; its ends stop clear of them. */
    var insetTop = 0f; private set
    var insetBottom = 0f; private set
    fun setInsets(top: Float, bottom: Float) {
        checkMain()
        if (top == insetTop && bottom == insetBottom) return
        val anchor = layout?.pageAtScroll(readingTop)?.let { notebook?.pages?.getOrNull(it.pageIndex)?.id to it.point.y }
        insetTop = top; insetBottom = bottom
        rebuildLayout(anchor)
    }
    /** Document y at the top of the uncovered part of the viewport: where reading starts. */
    val readingTop get() = scrollOffset + insetTop
    val minScrollOffset get() = 0f - insetTop
    val maxScrollOffset get() = layout?.let { (it.contentHeight + trailingSpace(it) + insetBottom - height).coerceAtLeast(0f - insetTop) } ?: 0f
    private fun offsetFor(layout: PageLayout, index: Int, localY: Float) =
        (layout.slots[index].top + localY * layout.scale - insetTop).coerceIn(minScrollOffset, (layout.maxScroll(height.toFloat()) + insetBottom).coerceAtLeast(minScrollOffset))
    /** B07 (P131): leave room for one more page after the last, where the editor offers "Add page". Off by default. */
    var addPageSlot = false; set(value) { if (field != value) { field = value; if (scrollOffset > maxScrollOffset) scrollOffset = maxScrollOffset; update() } }
    /** The empty slot after the last page, the last page's size one gap below it, in view coordinates; null when [addPageSlot] is off. */
    val addPageBounds: RectF? get() = layout?.takeIf { addPageSlot }?.slots?.lastOrNull()?.let { last ->
        val top = last.bottom + layout!!.gap - scrollOffset
        RectF(last.left, top, last.right, top + last.height)
    }
    private fun trailingSpace(layout: PageLayout) = if (addPageSlot) layout.slots.lastOrNull()?.let { it.height + layout.gap } ?: 0f else 0f
    val visibleRange: IntRange get() = layout?.takeIf { height > 0 }?.visiblePages(scrollOffset, scrollOffset + height) ?: IntRange.EMPTY
    /** Pages that should hold a bitmap: the visible ones plus the overscan band. */
    val renderRange: IntRange get() = layout?.takeIf { height > 0 }?.visiblePages(scrollOffset, scrollOffset + height, height * overscan) ?: IntRange.EMPTY
    val currentPage: Int? get() = layout?.takeIf { height > 0 && !it.isEmpty }?.currentPage(scrollOffset, scrollOffset + height)
    val cachedBytes get() = cache.values.sumOf { it.bytes }
    /** Page indices with a bitmap in memory, in page order. */
    val cachedPages: List<Int> get() = notebook?.pages?.mapIndexedNotNull { index, page -> index.takeIf { page.id in cache } }.orEmpty()
    val pendingRenders get() = jobs.size
    /** Pages chosen for a bitmap: [visibleRange] always, then as much of the overscan band as fits in the budget. */
    var planned: List<Int> = emptyList(); private set
    /** True when every planned page holds an up-to-date bitmap (or failed) and nothing is rendering. */
    val renderIdle get() = jobs.isEmpty() && planned.all { fresh(it) || notebook!!.pages[it].let { page -> failed[page.id] === page } }
    val isFlinging get() = !scroller.isFinished

    /** Shows [notebook] (a repository snapshot), keeping the page at the viewport top in place when the same notebook changes. */
    fun setNotebook(notebook: Notebook?) {
        checkMain()
        val anchor = layout?.pageAtScroll(scrollOffset)?.let { this.notebook?.pages?.getOrNull(it.pageIndex)?.id to it.point.y }
        val sameNotebook = notebook != null && notebook.id == this.notebook?.id
        this.notebook = notebook
        val ids = notebook?.pages?.map { it.id }?.toSet().orEmpty()
        cache.keys.filter { it !in ids }.forEach { drop(it) }
        jobs.keys.filter { it !in ids }.forEach { cancel(it) }
        failed.keys.retainAll(ids)
        rebuildLayout(if (sameNotebook) anchor else null)
    }

    /** Scrolls so page [index]'s local y [localY] (page units) is at the viewport top, clamped to the strip. */
    fun scrollToPage(index: Int, localY: Float = 0f, smooth: Boolean = false) {
        checkMain()
        val layout = layout ?: return
        require(index in layout.slots.indices) { "Page not found" }
        val target = offsetFor(layout, index, localY)
        scroller.forceFinished(true)
        if (smooth) {
            scroller.startScroll(0, scrollOffset.roundToInt(), 0, (target - scrollOffset).roundToInt())
            postInvalidateOnAnimation()
        } else setScrollOffset(target)
    }

    /** Moves the strip so document y [offset] is at the viewport top, clamped to the scrollable range. */
    fun setScrollOffset(offset: Float) {
        checkMain()
        require(offset.isFinite()) { "Invalid scroll offset" }
        val clamped = offset.coerceIn(minScrollOffset, maxScrollOffset)
        if (clamped == scrollOffset) return
        scrollOffset = clamped
        update()
    }

    /** A page's rectangle in this view's coordinates, or null when there is no such page. */
    fun pageBounds(index: Int): RectF? = layout?.slots?.getOrNull(index)?.let { RectF(it.left, it.top - scrollOffset, it.right, it.bottom - scrollOffset) }

    /** The bitmap currently drawn for page [index], for tests and diagnostics. It may be recycled once the page scrolls away. */
    fun renderedBitmap(index: Int): Bitmap? = notebook?.pages?.getOrNull(index)?.let { cache[it.id]?.bitmap }

    /** Stops rendering and frees every bitmap; the view can be shown again afterwards. */
    fun release() {
        checkMain()
        jobs.keys.toList().forEach { cancel(it) }
        cache.keys.toList().forEach { drop(it) }
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }

    override fun onDetachedFromWindow() {
        scroller.forceFinished(true)
        release()
        executor?.shutdownNow(); executor = null
        velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val anchor = layout?.pageAtScroll(readingTop)?.let { notebook?.pages?.getOrNull(it.pageIndex)?.id to it.point.y }
        rebuildLayout(anchor)
    }

    private fun rebuildLayout(anchor: Pair<String?, Float>?) {
        val pages = notebook?.pages
        val margin = dp(16f)
        layout = if (pages == null || width <= 2 * margin) null else PageLayout.fitToWidth(pages, width.toFloat(), gap = margin, margin = margin)
        val index = anchor?.first?.let { id -> pages?.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        scroller.forceFinished(true)
        scrollOffset = if (index != null) offsetFor(layout!!, index, anchor!!.second) else if (anchor == null) minScrollOffset else scrollOffset.coerceIn(minScrollOffset, maxScrollOffset)
        update()
    }

    /** Re-derives the wanted pages after a scroll, layout or notebook change: reports the current page, schedules renders and enforces the budget. */
    private fun update() {
        invalidate()
        onViewportChanged()
        planned = emptyList()
        val layout = layout ?: return
        val pages = notebook?.pages ?: return
        if (height <= 0) return
        currentPage?.let { index ->
            val id = pages[index].id
            if (index != reportedIndex || id != reportedPageId) { reportedIndex = index; reportedPageId = id; onCurrentPageChanged(index, id) }
        }
        if (!isAttachedToWindow) return
        // Visible pages first, then the overscan band nearest the viewport, as far as the budget allows.
        val visible = visibleRange
        var projected = 0L
        planned = renderRange.sortedBy { if (it in visible) -1 else minOf(abs(it - visible.first), abs(it - visible.last)) }.filter { index ->
            val (w, h) = targetSize(layout, index)
            projected += w.toLong() * h * 4
            index in visible || projected <= budgetBytes
        }
        val plannedIds = planned.map { pages[it].id }.toSet()
        jobs.keys.filter { it !in plannedIds }.forEach { cancel(it) }
        planned.forEach { index ->
            val page = pages[index]
            if (fresh(index) || failed[page.id] === page) return@forEach
            val (w, h) = targetSize(layout, index)
            val job = jobs[page.id]
            if (job != null && job.page === page && job.width == w) return@forEach
            if (job != null) cancel(page.id)
            start(page, w, h)
        }
        enforceBudget()
    }

    private fun fresh(index: Int): Boolean {
        val page = notebook?.pages?.getOrNull(index) ?: return false
        val entry = cache[page.id] ?: return false
        return entry.page === page && entry.bitmap.width == targetSize(layout ?: return false, index).first
    }

    /** Bitmap size for a slot: its size in pixels, scaled down so one page uses at most a third of the budget. */
    private fun targetSize(layout: PageLayout, index: Int): Pair<Int, Int> {
        val slot = layout.slots[index]
        var w = slot.width.roundToInt().coerceAtLeast(1).toDouble()
        var h = slot.height.roundToInt().coerceAtLeast(1).toDouble()
        val cap = budgetBytes / 3.0
        if (w * h * 4 > cap) { val factor = sqrt(cap / (w * h * 4)); w *= factor; h *= factor }
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    private fun start(page: Page, w: Int, h: Int) {
        val asset = notebook?.assets?.firstOrNull { it.id == page.pdfBackground?.assetId }
        val image = notebook?.imageAssets?.firstOrNull { it.id == page.imageBackground?.assetId }
        val photos = notebook?.imageAssets?.filter { it.id in page.imageAssetIds }.orEmpty()
        val files = notebook?.attachmentAssets?.filter { it.id in page.attachmentAssetIds }.orEmpty()
        val job = Job(page, w, h)
        jobs[page.id] = job
        val executor = executor ?: Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "continuous-pages").apply { isDaemon = true }
        }.also { executor = it }
        job.future = executor.submit {
            val result = if (job.cancelled) null else runCatching { render(page, asset, image, photos, files, w, h) { job.cancelled } }
            main.post { finish(job, result) }
        }
    }

    private fun finish(job: Job, result: Result<Bitmap?>?) {
        val id = job.page.id
        if (jobs[id] === job) jobs.remove(id)
        val bitmap = result?.getOrNull()
        val current = notebook?.pages?.firstOrNull { it.id == id }
        if (job.cancelled || !isAttachedToWindow || current !== job.page) { bitmap?.recycle(); return }
        if (result == null) return
        if (bitmap == null) {
            result.exceptionOrNull()?.let { failed[id] = job.page; onRenderFailed(id, it.message ?: it.javaClass.simpleName) }
            return
        }
        cache.remove(id)?.bitmap?.recycle()
        cache[id] = Entry(job.page, bitmap)
        enforceBudget()
        invalidate()
    }

    /** Evicts least recently drawn bitmaps outside the planned pages, then outside the visible pages. Visible pages always keep theirs. */
    private fun enforceBudget() {
        val pages = notebook?.pages ?: return
        val wanted = planned.map { pages[it].id }.toSet()
        val visible = visibleRange.map { pages[it].id }.toSet()
        var total = cachedBytes
        for (keep in listOf(wanted, visible)) {
            if (total <= budgetBytes) return
            for (id in cache.keys.toList()) {
                if (total <= budgetBytes) return
                if (id in keep) continue
                total -= cache.getValue(id).bytes
                drop(id); evictions++
            }
        }
    }

    private fun drop(id: String) { cache.remove(id)?.bitmap?.recycle() }

    private fun cancel(id: String) { jobs.remove(id)?.let { it.cancelled = true; it.future?.cancel(false) } }

    // ---- Drawing ----

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowBounds = RectF()
    private val pageBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(deskColor)
        val layout = layout ?: return
        val pages = notebook?.pages ?: return
        val unit = resources.displayMetrics.density
        for (index in visibleRange) {
            val slot = layout.slots[index]
            pageBounds.set(slot.left, slot.top - scrollOffset, slot.right, slot.bottom - scrollOffset)
            // The same soft two-layer shadow as InkCanvas, in screen pixels.
            shadowBounds.set(pageBounds)
            shadowPaint.color = 0x14000000; shadowBounds.offset(0f, unit); canvas.drawRoundRect(shadowBounds, unit * 2, unit * 2, shadowPaint)
            shadowPaint.color = 0x0c000000; shadowBounds.inset(-unit * 2, -unit * 2); shadowBounds.offset(0f, unit * 2); canvas.drawRoundRect(shadowBounds, unit * 6, unit * 6, shadowPaint)
            pagePaint.color = pages[index].paper.color.toInt()
            canvas.drawRect(pageBounds, pagePaint)
            // A stale bitmap (older content or width) stays on screen until its replacement arrives.
            cache[pages[index].id]?.let { canvas.drawBitmap(it.bitmap, null, pageBounds, bitmapPaint) }
        }
    }

    /** Draws like InkCanvas's dry layer: white page, PDF background, then the visible (erased) ink fill. Returns null if cancelled. */
    private fun render(page: Page, asset: PdfAsset?, image: ImageAsset?, photos: List<ImageAsset>, files: List<AttachmentAsset>, w: Int, h: Int, cancelled: () -> Boolean): Bitmap? {
        check(page.pdfBackground == null || asset != null) { "PDF asset missing for page" }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(page.paper.color.toInt())
            page.pdfBackground?.let { background -> renderPdf(asset!!, background.pageIndex, bitmap) }
            page.imageBackground?.let { ImageSupport.draw(requireNotNull(image) { "Image asset missing for page" }, it, bitmap) }
            if (cancelled()) { bitmap.recycle(); return null }
            val canvas = Canvas(bitmap)
            canvas.scale(w / page.width, h / page.height)
            canvas.clipRect(0f, 0f, page.width, page.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (!page.hasBackground) PaperRenderer.draw(canvas, page, paint)
            page.paintSlices.forEach { slice ->
            PhotoRendering.drawPage(canvas, slice, photos, maxOf(w, h))
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
    }

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

    // ---- Finger scrolling ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val finger = { index: Int -> event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endGesture()
                // Only a finger scrolls; a pen gesture is consumed and does nothing.
                if (!finger(0)) return true
                scroller.forceFinished(true)
                activePointer = event.getPointerId(0)
                downY = event.y; lastY = event.y
                velocity = (velocity ?: VelocityTracker.obtain()).also { it.clear(); it.addMovement(event) }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) {
                // Hand the gesture to another finger still down, if any.
                val next = (0 until event.pointerCount).firstOrNull { it != event.actionIndex && finger(it) }
                if (next == null) endGesture() else { activePointer = event.getPointerId(next); lastY = event.getY(next) }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return true
                velocity?.addMovement(event)
                val y = event.getY(index)
                if (!dragging && abs(y - downY) > configuration.scaledTouchSlop) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) setScrollOffset(scrollOffset + (lastY - y))
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocity
                if (dragging && tracker != null && event.findPointerIndex(activePointer) >= 0) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val vy = tracker.getYVelocity(activePointer)
                    if (abs(vy) > configuration.scaledMinimumFlingVelocity) fling(-vy)
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun endGesture() { activePointer = -1; dragging = false; velocity?.clear() }

    /** Stops a fling or smooth scroll in progress. */
    fun stopFling() { scroller.forceFinished(true) }

    /** Flings the strip; [velocityY] is in pixels per second, positive toward later pages. */
    fun fling(velocityY: Float) {
        checkMain()
        scroller.forceFinished(true)
        scroller.fling(0, scrollOffset.roundToInt(), 0, velocityY.roundToInt(), 0, 0, minScrollOffset.roundToInt(), maxScrollOffset.roundToInt())
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setScrollOffset(scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "ContinuousPageView is main-thread only" }

    companion object {
        /** A quarter of the heap, between 16 and 128 MiB: about three full-width pages on the Tab S7+ in portrait. */
        fun defaultBudget(): Long = (Runtime.getRuntime().maxMemory() / 4).coerceIn(16L * 1024 * 1024, 128L * 1024 * 1024)
    }
}
