package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import dev.ownednotes.core.Page
import dev.ownednotes.core.PageRoom
import kotlin.math.roundToInt

/**
 * P544 (F063) task: add room to the page you are writing on instead of starting a new page. P546 adds Up and Left.
 * Room above or to the left moves what is on the page along with its paper, so nothing changes size or its place on it.
 * Scope: current blank page. Home: Page actions. Frequency: sometimes per notebook.
 * Destructive: none; nothing scales or changes its place on the paper, and one Undo takes the room away again.
 * PDF and image pages keep their original size.
 */
internal object PageRoomSheet {
    /** The page as it is, with the new room drawn in dashes around it, so the choice can be seen before it is made. */
    internal class Preview(context: Context, private val page: Page) : View(context) {
        private val night = context.night
        var grown: Pair<Float, Float>? = null
            set(value) { field = value; invalidate() }
        var direction = PageRoom.Direction.DOWN
        private val sheet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.well(night) }
        private val room = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; alpha = 40 }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Glass.ACCENT; style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f)
            pathEffect = DashPathEffect(floatArrayOf(context.dp(5f), context.dp(4f)), 0f)
        }
        private val box = RectF()
        init { contentDescription = "Page with more room"; minimumHeight = context.dpi(180f) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
        override fun onDraw(canvas: Canvas) {
            val (w, h) = grown ?: (page.width to page.height)
            val scale = minOf(width / w, height / h)
            val left = (width - w * scale) / 2; val top = (height - h * scale) / 2
            box.set(left, top, left + w * scale, top + h * scale)
            canvas.drawRect(box, room); canvas.drawRect(box, edge)
            val shift = grown?.let { PageRoom.shift(page, direction, it) } ?: dev.ownednotes.core.Point(0f, 0f)
            val x = left + shift.x * scale; val y = top + shift.y * scale
            box.set(x, y, x + page.width * scale, y + page.height * scale)
            canvas.drawRect(box, sheet)
        }
    }

    fun show(activity: MainActivity): Kit.Sheet? {
        val ink = activity.ink
        val page = (activity.application as NotesApplication).activeDocument.currentPage ?: return null
        val c: Context = activity
        var direction = PageRoom.Direction.DOWN
        var amount = 1f
        lateinit var preview: Preview
        lateinit var summary: TextView
        lateinit var sheet: Kit.Sheet
        fun update() {
            val grown = PageRoom.grown(page, direction, amount)
            preview.direction = direction; preview.grown = grown
            sheet.actionButton?.isEnabled = grown != null
            val moves = direction == PageRoom.Direction.UP || direction == PageRoom.Direction.LEFT
            summary.text = if (grown == null) "This page has all the room it can take." else "New size ${grown.first.roundToInt()} × ${grown.second.roundToInt()}. " +
                (if (moves) "What is on the page moves ${if (direction == PageRoom.Direction.UP) "down" else "right"} with its paper, keeping its size." else "What is on the page stays where it is.")
        }
        sheet = Kit.Sheet(c, "More room on this page", "Add room", onAction = {
            ink.addRoom(direction, amount).also { if (it) { activity.refresh(); activity.frame.revealRoom(page.id, if (direction == PageRoom.Direction.UP || direction == PageRoom.Direction.LEFT) 0f else (page.height - 80f).coerceAtLeast(0f)) } }
        })
        preview = Preview(c, page)
        summary = Kit.text(c, "", Kit.Type.CAPTION, secondary = true)
        sheet.add(preview, 0f)
        sheet.add(Kit.segmented(c, listOf("Down", "Right", "Down & right", "Up", "Left"), 0) { direction = PageRoom.Direction.values()[it]; update() }.apply { contentDescription = "Where to add room" }, 12f)
        sheet.add(Kit.segmented(c, listOf("Half a page", "One page", "Two pages"), 1) { amount = listOf(.5f, 1f, 2f)[it]; update() }.apply { contentDescription = "How much room" }, 8f)
        sheet.add(summary, 12f)
        update()
        return sheet.show()
    }
}
