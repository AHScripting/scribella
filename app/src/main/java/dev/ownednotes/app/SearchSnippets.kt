package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P567: the part of a page to show for a match, in page units: the match with room around it, about half a page wide so
 * its neighbours show, kept on the page. With no known place it is the top of the page.
 */
internal fun snippetArea(pageWidth: Float, pageHeight: Float, boxes: List<RectF>, aspect: Float): RectF {
    if (boxes.isEmpty()) return RectF(0f, 0f, pageWidth, min(pageHeight, pageWidth / aspect))
    val left = boxes.minOf { it.left }; val top = boxes.minOf { it.top }; val right = boxes.maxOf { it.right }; val bottom = boxes.maxOf { it.bottom }
    val room = max(bottom - top, pageWidth * .02f)
    var width = min(pageWidth, max(right - left + room * 2, pageWidth * .5f))
    var height = min(pageHeight, max(bottom - top + room * 2, width / aspect))
    width = min(pageWidth, max(width, height * aspect))
    height = min(pageHeight, max(height, width / aspect))
    val x = ((left + right - width) / 2).coerceIn(0f, pageWidth - width)
    val y = ((top + bottom - height) / 2).coerceIn(0f, pageHeight - height)
    return RectF(x, y, x + width, y + height)
}

/** One result of Search in notebook: a small picture of the place on the page with the match marked, and its words below. */
internal class SearchHitRow(context: Context, val hit: PageText.Hit, private val page: Page, private val night: Boolean) : TextView(context) {
    private val density = resources.displayMetrics.density
    private val pictureHeight = 128f * density
    private val gap = 10f * density
    private val corner = 10f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frame = RectF(); private val place = RectF(); private val clip = Path()
    private var picture: Bitmap? = null
    private var area: RectF? = null
    /** Whether a picture of the page was asked for, for [SearchSnippets]. */
    var asked = false
    /** Whether the picture of the place is showing, for tests. */
    val showing get() = picture != null

    init { setPadding(0, (gap + pictureHeight + gap * .6f).roundToInt(), 0, gap.roundToInt()) }

    private fun measureFrame() = frame.set(0f, gap, min(width.toFloat(), 460f * density), gap + pictureHeight)

    /** Keeps only the part it shows, so a long list doesn't hold a whole page for every row. */
    fun show(whole: Bitmap) {
        measureFrame()
        if (frame.isEmpty) return
        val shown = snippetArea(page.width, page.height, hit.boxes, frame.width() / frame.height())
        val scale = whole.width / page.width
        val x = (shown.left * scale).roundToInt().coerceIn(0, whole.width - 1)
        val y = (shown.top * scale).roundToInt().coerceIn(0, whole.height - 1)
        val w = (shown.width() * scale).roundToInt().coerceIn(1, whole.width - x)
        val h = (shown.height() * scale).roundToInt().coerceIn(1, whole.height - y)
        picture = Bitmap.createBitmap(whole, x, y, w, h); area = shown
        invalidate()
    }

    fun forget() { picture = null; area = null; asked = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        measureFrame()
        fill.color = if (night) 0x24ffffff else 0x10000000
        canvas.drawRoundRect(frame, corner, corner, fill)
        val image = picture; val shown = area
        if (image != null && shown != null) {
            canvas.save()
            clip.reset(); clip.addRoundRect(frame, corner, corner, Path.Direction.CW); canvas.clipPath(clip)
            val scale = min(frame.width() / shown.width(), frame.height() / shown.height())
            val left = frame.centerX() - shown.width() * scale / 2; val top = frame.centerY() - shown.height() * scale / 2
            place.set(left, top, left + shown.width() * scale, top + shown.height() * scale)
            canvas.drawBitmap(image, null as Rect?, place, smooth)
            fill.color = MARK
            val margin = 2f * density
            hit.boxes.forEach { box ->
                place.set((box.left - shown.left) * scale + left - margin, (box.top - shown.top) * scale + top - margin,
                    (box.right - shown.left) * scale + left + margin, (box.bottom - shown.top) * scale + top + margin)
                canvas.drawRoundRect(place, 4f * density, 4f * density, fill)
            }
            canvas.restore()
        }
        line.color = if (night) 0x33ffffff else 0x1f000000
        canvas.drawRoundRect(frame, corner, corner, line)
    }

    private companion object { const val MARK = 0x66ffbf00 }
}

/** Gives the rows of an open search their pictures, only for those near the screen. */
internal class SearchSnippets(private val thumbnails: PageThumbnails, private val note: Notebook, private val scroll: ScrollView, private val rows: LinearLayout) {
    private val requests = mutableListOf<PageThumbnails.Request>()
    private val located = HashSet<String>()
    private var closed = false
    /** Reads a page whose text has no known place; set by the search. */
    var locate: (Page) -> Unit = {}

    init {
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> load() }
        rows.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> load() }
    }

    private fun load() {
        if (closed || scroll.height == 0) return
        val near = scroll.scrollY - scroll.height..scroll.scrollY + scroll.height * 2
        val far = scroll.scrollY - scroll.height * 5..scroll.scrollY + scroll.height * 6
        for (index in 0 until rows.childCount) {
            val row = rows.getChildAt(index) as? SearchHitRow ?: continue
            if (row.width == 0) continue
            val isNear = row.bottom >= near.first && row.top <= near.last
            if (!isNear) {
                if (row.showing && (row.bottom < far.first || row.top > far.last)) row.forget()
                continue
            }
            if (!row.hit.typed && !row.hit.handwriting && row.hit.boxes.isEmpty() && PageText.readable(pageOf(row)) && PageText.cachedPage(pageOf(row)) == null && located.add(row.hit.pageId)) locate(pageOf(row))
            if (row.asked) continue
            row.asked = true
            val cached = thumbnails.cached(note, row.hit.pageId, WIDTH)
            if (cached != null) row.show(cached)
            else requests += thumbnails.request(note, row.hit.pageId, WIDTH) { result -> result.getOrNull()?.let { row.show(it) } }
        }
    }

    private fun pageOf(row: SearchHitRow) = note.pages[row.hit.pageIndex]

    fun clear() { requests.forEach { it.cancel() }; requests.clear() }
    fun close() { closed = true; clear() }

    companion object {
        const val MOST_ROWS = 200
        private const val WIDTH = PageThumbnails.MAX_EDGE
    }
}
