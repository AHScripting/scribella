package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import dev.ownednotes.core.Page
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P538: the page itself with its recognised words tinted. Dragging from a word chooses every word up to the one under
 * the touch, in reading order, and reports the run through [choose] as offsets in the recognised text. A touch away from
 * any word stays with the scroll view, so the margins scroll a page taller than the dialog.
 */
internal class TextWordPicker(context: Context, private val page: Page, read: PageText.Result,
    private val choose: (Int, Int) -> Unit) : View(context) {
    private val words = read.lines.flatMap { it.words }.sortedBy { it.start }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var picture: Bitmap? = null
    private var anchor = -1
    private var reach = -1
    internal val pictureReady get() = picture != null

    init { contentDescription = "Page with its printed words. Drag across words to choose them, or use the text list." }

    /** Takes ownership of [bitmap]; it is freed when the view leaves the window. */
    fun show(bitmap: Bitmap) { picture?.recycle(); picture = bitmap; invalidate() }
    fun clear() { anchor = -1; reach = -1; invalidate() }
    /** The word's box in this view's pixels. */
    fun wordBox(index: Int, into: RectF = RectF()) = into.apply {
        val k = width / page.width; val b = words[index].box
        set(b.left * k, b.top * k, b.right * k, b.bottom * k)
    }
    val wordCount get() = words.size

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * page.height / page.width).roundToInt())
    }
    override fun onDraw(canvas: Canvas) {
        paint.color = Color.WHITE; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        picture?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint) }
        val first = min(anchor, reach); val last = max(anchor, reach)
        words.indices.forEach { i ->
            paint.color = if (anchor >= 0 && i in first..last) CHOSEN else WORD
            canvas.drawRoundRect(wordBox(i, box), dp(2f), dp(2f), paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val at = nearest(event.x, event.y)
                if (at < 0 || away(at, event.x, event.y) > dp(14f)) return false
                anchor = at; reach = at; report()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (anchor >= 0) {
                val at = nearest(event.x, event.y)
                if (at != reach) { reach = at; report() }
            }
        }
        invalidate(); return true
    }
    override fun onDetachedFromWindow() { picture?.recycle(); picture = null; super.onDetachedFromWindow() }

    private fun report() = choose(words[min(anchor, reach)].start, words[max(anchor, reach)].end)
    private fun nearest(x: Float, y: Float) = words.indices.minByOrNull { away(it, x, y) } ?: -1
    private fun away(index: Int, x: Float, y: Float): Float {
        val b = wordBox(index, box)
        return kotlin.math.hypot(max(max(b.left - x, x - b.right), 0f), max(max(b.top - y, y - b.bottom), 0f))
    }

    private companion object {
        const val WORD = 0x1c0a84ff
        const val CHOSEN = 0x660a84ff
    }
}
