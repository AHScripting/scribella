package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The page strip (design build-order step 5): the current notebook's pages as a vertical list of previews with
 * page numbers, the current page marked in amber. Tapping a page selects it; long-press then drag reorders it.
 * It talks to the notebook only through [NotebookRepository]'s public API, and refuses selection and reordering
 * while the repository is loading, a file operation runs, or pen input is in progress. Scrolling is always allowed.
 *
 * Pass [thumbnails] to share a renderer with other views; otherwise the panel creates one and closes it when detached.
 */
class PagePanel(
    context: Context,
    private var repository: NotebookRepository,
    thumbnails: PageThumbnails? = null,
) : View(context) {
    /** Called after a tap selected a page (also when it was already current). */
    var onPageActions: ((pageId: String) -> Unit)? = null
    var onPageSelected: ((pageId: String) -> Unit)? = null
    /** Called after a drag moved a page; indices are zero-based positions before and after. */
    var onPageMoved: ((pageId: String, from: Int, to: Int) -> Unit)? = null
    /** Called with a short user-facing reason when a tap or drag was refused. */
    var onRefused: ((reason: String) -> Unit)? = null
    /** P178 (G3): called when choosing starts or stops, or the chosen pages change. */
    var onChoiceChanged: (() -> Unit)? = null
    /** P178 (G3): Glass colours; graphite glass at night. */
    var night = false
        set(value) { field = value; setBackgroundColor(if (value) NIGHT_PANE else LIGHT_PANE); invalidate() }

    private val ownsThumbnails = thumbnails == null
    val thumbnails: PageThumbnails = thumbnails ?: PageThumbnails(context.cacheDir)

    private class Pending(val key: PageThumbnails.Key, val request: PageThumbnails.Request)
    private class Drag(val snapshot: Notebook, val pageId: String, val from: Int, val grabOffset: Float, val grabX: Float) { var target = from }

    /**
     * P201 step 6: the page organizer's grid, as on the Glass "Page organizer" board: as many columns of previews as fit,
     * "Page N" under each ("Page N · open" for the current one), a red ribbon on bookmarked pages and the choice tick at the
     * foot. The strip stays one column.
     */
    var grid = false
        set(value) { field = value; offset = 0f; requestThumbnails(); invalidate() }

    private var notebook: Notebook? = null
    private var selectedId: String? = null
    private val shown = hashMapOf<String, Bitmap>()
    private val shownKeys = hashMapOf<String, PageThumbnails.Key>()
    private val failedKeys = hashMapOf<String, PageThumbnails.Key>()
    private val pending = hashMapOf<String, Pending>()
    private val observer: () -> Unit = { onRepositoryChanged() }

    private val sidePad get() = if (grid) dp(28f) else dp(16f)
    private val topPad get() = if (grid) dp(18f) else dp(14f)
    private val labelHeight get() = if (grid) dp(32f) else dp(24f)
    private val gap get() = if (grid) dp(18f) else dp(12f)
    private val corner get() = if (grid) dp(8f) else dp(6f)
    private val colGap get() = dp(26f)
    private val cols get() = if (!grid || width == 0) 1 else ((width - 2 * sidePad + colGap) / (dp(GRID_CELL_DP) + colGap)).toInt().coerceAtLeast(1)
    private val cellWidth get() = ((width - 2 * sidePad - (cols - 1) * colGap) / cols).coerceAtLeast(1f)
    private fun colLeft(index: Int) = sidePad + (index % cols) * (cellWidth + colGap)
    private val ribbon = Path().apply { moveTo(1f, 0f); lineTo(13f, 0f); lineTo(13f, 20f); lineTo(7f, 15f); lineTo(1f, 20f); close() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.INK }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); typeface = Tokens.SANS_MEDIUM; fontFeatureSettings = "tnum"; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val pill = RectF()
    private val clip = Path()
    /** When each preview arrived from a background render, for its fade-in; cache hits appear at once. */
    private val arrived = hashMapOf<String, Long>()
    private var pressedIndex = -1

    private var offset = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var actionDown = -1
    private var downIndex = -1
    private var scrolling = false
    /** Set when a drag was cancelled or refused: the rest of that gesture does nothing. */
    private var spent = false
    private var drag: Drag? = null
    private var dragX = 0f
    private var dragY = 0f
    private var autoScrolling = false
    private val longPress = Runnable { startDrag() }
    private val autoScroll = object : Runnable {
        override fun run() {
            if (drag == null) { autoScrolling = false; return }
            val edge = dp(56f)
            val step = when {
                dragY < edge -> -(edge - dragY) / edge * dp(16f)
                dragY > height - edge -> (dragY - (height - edge)) / edge * dp(16f)
                else -> 0f
            }
            val before = offset
            if (step != 0f) setOffset(offset + step)
            if (offset != before) { updateTarget(); invalidate(); postOnAnimation(this) } else autoScrolling = false
        }
    }

    /** P178 (G3): while choosing, a tap toggles a page in [chosenIds] instead of opening it; reordering and page menus wait. */
    var choosing = false; private set
    private val chosen = linkedSetOf<String>()
    /** The chosen pages, in notebook order. */
    val chosenIds: Set<String> get() = pageIds.filter { it in chosen }.toCollection(linkedSetOf())

    init {
        setBackgroundColor(LIGHT_PANE)
        isClickable = true; isFocusable = true
        contentDescription = "Pages"
    }

    fun startChoosing(): Boolean {
        if (repository.readOnly) { onRefused?.invoke("Enable editing before choosing pages"); return false }
        refusal?.let { onRefused?.invoke(it); return false }
        if (notebook == null) return false
        cancelDrag(); choosing = true; chosen.clear(); describe(); invalidate(); onChoiceChanged?.invoke()
        return true
    }
    fun stopChoosing() {
        if (!choosing) return
        choosing = false; chosen.clear(); describe(); invalidate(); onChoiceChanged?.invoke()
    }
    /** Toggles [pageId] while choosing; false when refused or not choosing. */
    fun toggleChosen(pageId: String): Boolean {
        if (!choosing || pageId !in pageIds) return false
        refusal?.let { onRefused?.invoke(it); return false }
        if (!chosen.remove(pageId)) chosen.add(pageId)
        describe(); invalidate(); onChoiceChanged?.invoke()
        return true
    }
    private fun describe() {
        contentDescription = notebook?.let {
            if (choosing) "Pages, choosing, ${chosen.size} of ${it.pages.size} chosen" else "Pages, ${it.pages.size} pages, page ${currentIndex + 1} current"
        } ?: "Pages"
    }

    // ---- State exposed for the editor and tests ----

    val pageIds: List<String> get() = notebook?.pages?.map { it.id }.orEmpty()
    val pageCount: Int get() = notebook?.pages?.size ?: 0
    val bookmarkedPageIds: List<String> get() = notebook?.pages?.filter { it.bookmark != null }?.map { it.id }.orEmpty()
    /** Index of the current (selected) page, or -1. */
    val currentIndex: Int get() = notebook?.pages?.indexOfFirst { it.id == selectedId } ?: -1
    val dragging: Boolean get() = drag != null
    /** The preview currently drawn for a page, or null while it renders. */
    fun shownThumbnail(pageId: String): Bitmap? = shown[pageId]
    /** True when no preview render is outstanding. */
    val thumbnailsIdle: Boolean get() = pending.isEmpty()
    /** View-relative bounds of a page's preview at [index], at the current scroll position. */
    fun pageBounds(index: Int): RectF? {
        val pages = notebook?.pages ?: return null
        if (index !in pages.indices || width == 0) return null
        return RectF().also { thumbRect(pages[index], index, tops(pages)[index] - offset, it) }
    }
    /** Separate menu target below each preview; holding the preview still reorders it. */
    fun pageActionBounds(index: Int): RectF? = pageBounds(index)?.let {
        val right = colLeft(index) + cellWidth
        RectF(right - dp(40f), it.bottom, right + dp(8f), it.bottom + labelHeight)
    }
    fun requestPageActions(pageId: String): Boolean {
        if (choosing || refusal != null || repository.readOnly || !repository.canChangeNotebook || pageId !in pageIds) return false
        onPageActions?.invoke(pageId) ?: return false
        return true
    }
    /** Why selection/reordering is refused right now, or null when allowed. */
    val refusal: String? get() = when {
        repository.session == null || repository.busy -> "The notebook is still opening"
        repository.fileBusy -> "Wait for the file operation to finish"
        repository.inputBusy -> "Finish writing first"
        else -> null
    }

    /** Scrolls so the current page is fully visible. */
    fun revealCurrent() {
        val pages = notebook?.pages ?: return
        val index = currentIndex.takeIf { it >= 0 } ?: return
        val top = tops(pages)[index]
        val bottom = top + rowHeight(pages, index / cols)
        if (top - topPad < offset) setOffset(top - topPad) else if (bottom > offset + height) setOffset(bottom - height + gap)
        invalidate()
    }

    // ---- Repository and thumbnails ----

    override fun onAttachedToWindow() { super.onAttachedToWindow(); repository.listen(observer) }

    /** P530 (F081/F082): the panel lists the active pane's document. */
    internal fun retarget(document: NotebookRepository) {
        if (document === repository) return
        if (isAttachedToWindow) { repository.unlisten(observer); document.listen(observer) }
        repository = document
        onRepositoryChanged()
    }

    override fun onDetachedFromWindow() {
        repository.unlisten(observer)
        cancelDrag(); removeCallbacks(longPress)
        pending.values.forEach { it.request.cancel() }; pending.clear()
        if (ownsThumbnails) { thumbnails.close(); shown.clear(); shownKeys.clear() }
        super.onDetachedFromWindow()
    }

    private fun onRepositoryChanged() {
        val next = repository.session?.notebook
        val selectionChanged = repository.selectedPageId != selectedId
        drag?.let { if (next !== it.snapshot || refusal != null) cancelDrag() }
        val otherNotebook = next?.id != notebook?.id
        if (otherNotebook) { offset = 0f; shown.clear(); shownKeys.clear(); failedKeys.clear(); arrived.clear() }
        notebook = next; selectedId = repository.selectedPageId
        // Choosing ends with its notebook (or read-only mode); pages that left the notebook leave the choice.
        if (choosing && (otherNotebook || next == null || repository.readOnly)) { choosing = false; chosen.clear(); onChoiceChanged?.invoke() }
        else if (choosing) { chosen.retainAll(next!!.pages.map { it.id }.toSet()); onChoiceChanged?.invoke() }
        describe()
        setOffset(offset)
        requestThumbnails()
        if (selectionChanged) revealCurrent()
        invalidate()
    }

    private val thumbWidth get() = cellWidth
    private val thumbPixels get() = thumbWidth.roundToInt().coerceIn(1, PageThumbnails.MAX_EDGE)

    private fun requestThumbnails() {
        val current = notebook ?: return
        if (width == 0) return
        val ids = current.pages.map { it.id }.toSet()
        (pending.keys - ids).forEach { pending.remove(it)?.request?.cancel() }
        shown.keys.retainAll(ids); shownKeys.keys.retainAll(ids); failedKeys.keys.retainAll(ids)
        val pixels = thumbPixels
        for (page in current.pages) {
            val key = thumbnails.key(current, page, pixels)
            if (shownKeys[page.id] == key || failedKeys[page.id] == key || pending[page.id]?.key == key) continue
            pending.remove(page.id)?.request?.cancel()
            val hit = thumbnails.cached(current, page.id, pixels)
            if (hit != null) { shown[page.id] = hit; shownKeys[page.id] = key; continue }
            val id = page.id
            var request: PageThumbnails.Request? = null
            request = thumbnails.request(current, id, pixels) { result ->
                if (pending[id]?.request === request) pending.remove(id)
                result.onSuccess {
                    if (shown[id] == null && Motion.enabled) arrived[id] = SystemClock.uptimeMillis()
                    shown[id] = it; shownKeys[id] = key; failedKeys.remove(id)
                }.onFailure { failedKeys[id] = key }
                invalidate()
            }
            pending[id] = Pending(key, request!!)
        }
    }

    // ---- Layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dpi(PREFERRED_WIDTH_DP), widthMeasureSpec), getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A new size re-anchors from the top, so a scroll forced by an early, shorter layout pass never leaves
        // the first page clipped; the current page is then revealed as usual.
        if (h != oldh) offset = 0f
        setOffset(offset); requestThumbnails(); revealCurrent()
    }

    /** Display size of a page's preview: full strip width, very tall pages capped at twice the width. */
    private fun thumbSize(page: Page): Pair<Float, Float> {
        val maxWidth = thumbWidth
        val height = maxWidth * page.height / page.width
        return if (height <= maxWidth * 2) maxWidth to height else (maxWidth * 2 * page.width / page.height) to maxWidth * 2
    }
    /** A row is as tall as its tallest preview, plus the label and the gap; in the strip each page is its own row. */
    private fun rowHeight(order: List<Page>, row: Int): Float {
        var tallest = 0f
        for (i in row * cols until minOf(order.size, (row + 1) * cols)) tallest = maxOf(tallest, thumbSize(order[i]).second)
        return tallest + labelHeight + gap
    }
    /** Each row's top, then the end of the last row. */
    private fun rowTops(order: List<Page>): FloatArray {
        val rows = (order.size + cols - 1) / cols
        val tops = FloatArray(rows + 1); var y = topPad
        for (row in 0 until rows) { tops[row] = y; y += rowHeight(order, row) }
        tops[rows] = y
        return tops
    }
    private fun tops(order: List<Page>): FloatArray { val rows = rowTops(order); return FloatArray(order.size) { rows[it / cols] } }
    /** P201 step 6: room after the last row, so a bar floating over the panel's foot never hides it. */
    var footer = 0f
    private fun contentHeight(): Float = (notebook?.pages?.let { rowTops(it).last() } ?: topPad) + gap + footer
    private fun maxOffset() = (contentHeight() - height).coerceAtLeast(0f)
    private fun setOffset(value: Float) { offset = value.coerceIn(0f, maxOffset()) }
    private fun thumbRect(page: Page, index: Int, viewTop: Float, out: RectF) {
        val (w, h) = thumbSize(page)
        val left = colLeft(index) + (cellWidth - w) / 2
        out.set(left, viewTop, left + w, viewTop + h)
    }
    private fun indexAt(x: Float, y: Float): Int {
        val pages = notebook?.pages ?: return -1
        val rows = rowTops(pages)
        val contentY = y + offset
        val row = (0 until rows.size - 1).firstOrNull { contentY >= rows[it] && contentY < rows[it + 1] - gap / 2 } ?: return -1
        val col = if (cols == 1) 0 else ((x - sidePad + colGap / 2) / (cellWidth + colGap)).toInt().coerceIn(0, cols - 1)
        return (row * cols + col).takeIf { it < pages.size } ?: -1
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pages = notebook?.pages ?: return
        val active = drag
        val order = if (active == null) pages else pages.filterNot { it.id == active.pageId }.toMutableList().apply { add(active.target, pages[active.from]) }
        val tops = tops(order)
        order.forEachIndexed { index, page ->
            val top = tops[index] - offset
            if (top > height || top + rowHeight(order, index / cols) < 0) return@forEachIndexed
            if (page.id == active?.pageId) drawGhost(canvas, page, index, top) else drawCell(canvas, page, index, top, lifted = false)
        }
        if (active != null) {
            val page = pages[active.from]
            drawCell(canvas, page, active.target, dragY - active.grabOffset, lifted = true, left = if (cols > 1) dragX - active.grabX else null)
        }
    }

    /** P201 step 6: where the lifted page will land, a dashed accent outline as on the organizer board. */
    private fun drawGhost(canvas: Canvas, page: Page, index: Int, top: Float) {
        thumbRect(page, index, top, rect)
        paint.style = Paint.Style.FILL; paint.color = Glass.accent(night); paint.alpha = 10
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.alpha = 115
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.pathEffect = null; paint.alpha = 255
    }

    /**
     * One preview: a soft two-layer shadow (deeper while lifted), the page with rounded corners fading in when its
     * render arrives, an accent ring on the current page, and the page number below (an accent pill when current).
     * P178 (G3): Glass colours; while choosing, a check circle marks each page and chosen pages carry the ring instead.
     */
    private fun drawCell(canvas: Canvas, page: Page, index: Int, top: Float, lifted: Boolean, left: Float? = null) {
        thumbRect(page, index, top, rect)
        if (left != null) rect.offsetTo(left, rect.top)
        val cellRight = if (left != null) rect.centerX() + cellWidth / 2 else colLeft(index) + cellWidth
        val accent = Glass.accent(night)
        val picked = choosing && page.id in chosen
        val current = if (choosing) picked else page.id == selectedId
        if (lifted) { canvas.save(); canvas.scale(1.04f, 1.04f, rect.centerX(), rect.centerY()) }
        // Elevation: a wide ambient layer and a tight key layer below; lighter on the light glass.
        shadowPaint.setShadowLayer(if (lifted) dp(18f) else dp(8f), 0f, if (lifted) dp(8f) else dp(3f), if (lifted) 0x66000000 else if (night) 0x55000000 else 0x2a000000)
        canvas.drawRoundRect(rect, corner, corner, shadowPaint)
        shadowPaint.setShadowLayer(dp(1.5f), 0f, dp(1f), if (night) 0x40000000 else 0x24000000)
        canvas.drawRoundRect(rect, corner, corner, shadowPaint)
        clip.reset(); clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        paint.style = Paint.Style.FILL; paint.color = if (night) 0xff2f3540.toInt() else 0xffe3e7ee.toInt(); paint.alpha = 255
        val bitmap = shown[page.id]
        val fade = arrived[page.id]?.let { ((SystemClock.uptimeMillis() - it) / FADE_MS.toFloat()).coerceIn(0f, 1f) } ?: 1f
        if (bitmap == null || fade < 1f) canvas.drawRect(rect, paint)
        if (bitmap != null) {
            paint.alpha = (Motion.easeOut.getInterpolation(fade) * 255).roundToInt()
            canvas.drawBitmap(bitmap, null, rect, paint); paint.alpha = 255
            if (fade < 1f) postInvalidateOnAnimation() else arrived.remove(page.id)
        }
        if (index == pressedIndex && !lifted) { paint.color = 0x1a182636; canvas.drawRect(rect, paint) }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        if (current || lifted) {
            paint.color = accent; paint.strokeWidth = dp(2.5f)
            rect.inset(-dp(3.5f), -dp(3.5f)); canvas.drawRoundRect(rect, corner + dp(3.5f), corner + dp(3.5f), paint); rect.inset(dp(3.5f), dp(3.5f))
        } else {
            paint.color = if (night) Glass.RULE_NIGHT else Glass.RULE; paint.strokeWidth = dp(1f)
            rect.inset(-dp(.5f), -dp(.5f)); canvas.drawRoundRect(rect, corner, corner, paint); rect.inset(dp(.5f), dp(.5f))
        }
        if (grid && page.bookmark != null) {
            paint.style = Paint.Style.FILL; paint.color = BOOKMARK_RED
            canvas.save(); canvas.translate(rect.right - dp(25f), rect.top - dp(2f)); canvas.scale(dp(1f), dp(1f))
            canvas.drawPath(ribbon, paint); canvas.restore()
        }
        if (choosing) drawCheck(canvas, picked, accent)
        if (lifted) canvas.restore()
        val baseline = rect.bottom + labelHeight / 2 + dp(2f) - (labelPaint.descent() + labelPaint.ascent()) / 2
        if (grid) {
            // The board's label: "Page N" in grey, the open page in the accent.
            val open = !choosing && page.id == selectedId
            labelPaint.color = if (open) Kit.accentText(night) else Glass.secondary(night)
            labelPaint.typeface = Tokens.SANS_SEMIBOLD
            canvas.drawText("Page ${index + 1}" + if (open) " · open" else "", rect.centerX(), baseline, labelPaint)
            labelPaint.typeface = Tokens.SANS_MEDIUM
        } else {
            val label = (index + 1).toString() + if (page.bookmark != null) " ★" else ""
            if (current) {
                val half = maxOf(labelPaint.measureText(label) / 2 + dp(8f), dp(13f))
                pill.set(rect.centerX() - half, baseline + labelPaint.ascent() - dp(3f), rect.centerX() + half, baseline + labelPaint.descent() + dp(3f))
                paint.style = Paint.Style.FILL; paint.color = accent
                canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint)
                labelPaint.color = android.graphics.Color.WHITE
            } else labelPaint.color = Glass.secondary(night)
            canvas.drawText(label, rect.centerX(), baseline, labelPaint)
        }
        if (choosing) return
        paint.style = Paint.Style.FILL; paint.color = Glass.secondary(night)
        val menuX = cellRight - dp(12f)
        for (dy in listOf(-4f, 0f, 4f)) canvas.drawCircle(menuX, rect.bottom + labelHeight / 2 + dp(dy), dp(1.3f), paint)
    }

    /** P178 (G3): a check circle in the preview's top-right corner; filled with a tick when the page is chosen. */
    private fun drawCheck(canvas: Canvas, picked: Boolean, accent: Int) {
        // P201 step 6: in the grid the tick sits at the foot, 26 dp, as on the organizer board.
        val r = if (grid) dp(13f) else dp(11f); val cx = rect.right - dp(8f) - r
        val cy = if (grid) rect.bottom - dp(8f) - r else rect.top + dp(8f) + r
        paint.style = Paint.Style.FILL; paint.color = if (picked) accent else 0xccffffff.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
        paint.color = if (picked) android.graphics.Color.WHITE else 0x661d2433
        canvas.drawCircle(cx, cy, r, paint)
        if (!picked) return
        paint.strokeWidth = dp(2f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        clip.reset(); clip.moveTo(cx - dp(4.5f), cy); clip.lineTo(cx - dp(1.2f), cy + dp(3.5f)); clip.lineTo(cx + dp(5f), cy - dp(3.5f))
        canvas.drawPath(clip, paint)
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y; scrolling = false; spent = false
                downIndex = indexAt(event.x, event.y)
                actionDown = if (choosing) -1 else downIndex.takeIf { pageActionBounds(it)?.contains(event.x, event.y) == true } ?: -1
                if (downIndex >= 0 && actionDown < 0) {
                    if (!choosing) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    pressedIndex = downIndex; invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (spent) Unit
                else if (drag != null) { dragX = event.x; dragY = event.y; updateTarget(); startAutoScroll(); invalidate() }
                else {
                    if (!scrolling && (abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop)) { scrolling = true; removeCallbacks(longPress); pressedIndex = -1 }
                    if (scrolling) { setOffset(offset - (event.y - lastY)); invalidate() }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    spent -> Unit
                    drag != null -> drop()
                    scrolling -> fling()
                    actionDown >= 0 -> { if (pageActionBounds(actionDown)?.contains(event.x, event.y) == true) pageIds.getOrNull(actionDown)?.let { requestPageActions(it) } }
                    downIndex >= 0 && indexAt(event.x, event.y) == downIndex -> tap(downIndex)
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); cancelDrag(); endGesture() }
        }
        return true
    }

    private fun endGesture() { velocity?.recycle(); velocity = null; downIndex = -1; actionDown = -1; scrolling = false; if (pressedIndex >= 0) { pressedIndex = -1; invalidate() } }

    private fun fling() {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
        val vy = tracker.yVelocity
        if (abs(vy) < minFling) return
        scroller.fling(0, offset.roundToInt(), 0, -vy.roundToInt(), 0, 0, 0, maxOffset().roundToInt())
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { setOffset(scroller.currY.toFloat()); postInvalidateOnAnimation() }
    }

    private fun tap(index: Int) {
        val pages = notebook?.pages ?: return
        if (choosing) { toggleChosen(pages[index].id); return }
        refusal?.let { onRefused?.invoke(it); return }
        val id = pages[index].id
        if (repository.selectedPageId != id) repository.selectPage(id)
        onPageSelected?.invoke(id)
    }

    private fun startDrag() {
        if (repository.readOnly) { onRefused?.invoke("Enable editing before reordering pages"); spent = true; return }
        val snapshot = notebook ?: return
        if (downIndex !in snapshot.pages.indices || scrolling) return
        refusal?.let { onRefused?.invoke(it); spent = true; return }
        val top = tops(snapshot.pages)[downIndex] - offset
        thumbRect(snapshot.pages[downIndex], downIndex, top, rect)
        drag = Drag(snapshot, snapshot.pages[downIndex].id, downIndex, lastY - top, lastX - rect.left); pressedIndex = -1
        dragX = lastX; dragY = lastY
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** The dragged page goes before the first remaining page whose midpoint is below the lifted page's centre. */
    private fun updateTarget() {
        val active = drag ?: return
        val pages = active.snapshot.pages
        val (w, h) = thumbSize(pages[active.from])
        if (cols > 1) {
            // The grid: the slot whose preview centre is nearest the lifted page's centre.
            val cx = dragX - active.grabX + w / 2; val cy = dragY - active.grabOffset + h / 2 + offset
            val tops = tops(pages)
            active.target = pages.indices.minByOrNull { slot ->
                val dx = colLeft(slot) + cellWidth / 2 - cx; val dy = tops[slot] + thumbSize(pages[slot]).second / 2 - cy
                dx * dx + dy * dy
            } ?: active.from
            return
        }
        val centre = dragY - active.grabOffset + (h + labelHeight + gap) / 2 + offset
        var y = topPad; var target = 0
        for (page in pages) {
            if (page.id == active.pageId) continue
            val ch = thumbSize(page).second + labelHeight + gap
            if (centre > y + ch / 2) { target++; y += ch } else break
        }
        active.target = target
    }

    private fun startAutoScroll() { if (!autoScrolling) { autoScrolling = true; postOnAnimation(autoScroll) } }

    private fun drop() {
        val active = drag ?: return
        drag = null; removeCallbacks(autoScroll); autoScrolling = false
        invalidate()
        if (active.target == active.from) return
        refusal?.let { onRefused?.invoke(it); return }
        if (repository.session?.notebook !== active.snapshot) { onRefused?.invoke("The notebook changed; try again"); return }
        // movePage acts on the selected page only, so the dragged page becomes current first.
        if (repository.selectedPageId != active.pageId) repository.selectPage(active.pageId)
        if (repository.movePage(active.snapshot, active.pageId, active.target)) onPageMoved?.invoke(active.pageId, active.from, active.target)
        else onRefused?.invoke("The page could not be moved; try again")
    }

    private fun cancelDrag() {
        if (drag == null) return
        spent = true; drag = null; removeCallbacks(autoScroll); autoScrolling = false; invalidate()
    }

    companion object {
        const val PREFERRED_WIDTH_DP = 168f
        /** P201 step 6: the organizer board's preview width; the grid fits as many columns of at least this as it can. */
        const val GRID_CELL_DP = 168f
        const val BOOKMARK_RED = 0xffff3b30.toInt()
        private const val FADE_MS = 180L
        /** P178 (G3): the strip's glass, opaque so previews never show the page through it. */
        const val LIGHT_PANE = 0xfff3f5f8.toInt()
        const val NIGHT_PANE = 0xff22262e.toInt()
    }
}
