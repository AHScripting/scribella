package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import androidx.core.graphics.PathParser
import dev.ownednotes.core.ImagePlacement
import dev.ownednotes.core.Page
import dev.ownednotes.core.Point
import dev.ownednotes.core.TextBox
import dev.ownednotes.core.inkNote

/**
 * P680 (B68, board 18): a handwritten sticky note. Board sizes are in dp at the note's usual size; on the page one board dp
 * is [U] page units, so the note scales with the page like any other object. Open: a rounded, shadowed card with a
 * 30 dp header strip (colour dot, 6-dot grip, Fold, More) and a curved resize grip. Folded: a 34 dp tab with a turned corner.
 */
internal object InkNoteLook {
    const val U = 1.25f
    const val HEADER = 30 * U
    /** P681 (board 19): typed words sit 12 dp in from the note's sides, under the header. */
    const val TEXT_INSET = 12 * U
    private const val RADIUS = 12 * U
    /** The size a new note opens at, in page units (240 x 200 dp on the board). */
    const val NEW_WIDTH = 240 * U
    const val NEW_HEIGHT = 200 * U
    const val MIN_WIDTH = 120 * U
    const val MIN_HEIGHT = 90 * U
    /** Board 18's colours, yellow first. */
    val COLORS = listOf(0xffffe680L to "Yellow", 0xffffc4d6L to "Pink", 0xffc8ecc0L to "Green", 0xffc4e0ffL to "Blue", 0xffffd3a8L to "Orange")

    enum class Part { DOT, GRIP, FOLD, MORE, RESIZE, BODY, TAB }
    /** Which part of [box] a page point is on, or null when it is outside the note. */
    fun part(box: TextBox, point: Point): Part? {
        val p = box.placement
        if (!p.contains(point)) return null
        if (box.sticky?.collapsed == true) return Part.TAB
        val x = point.x - p.x; val y = point.y - p.y
        if (y <= HEADER) return when {
            x <= 34 * U -> Part.DOT
            x >= p.width - 42 * U -> Part.MORE
            x >= p.width - 70 * U -> Part.FOLD
            else -> Part.GRIP
        }
        if (x >= p.width - 34 * U && y >= p.height - 34 * U) return Part.RESIZE
        return Part.BODY
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val grip = PathParser.createPathFromPathData("M9 7h.01M15 7h.01M9 12h.01M15 12h.01M9 17h.01M15 17h.01")
    private val fold = PathParser.createPathFromPathData("M6 12h12")
    private val more = PathParser.createPathFromPathData("M6 12h.01M12 12h.01M18 12h.01")
    private val resize = PathParser.createPathFromPathData("M20 9v4a7 7 0 0 1 -7 7H9")
    private val rect = RectF()

    fun draw(canvas: Canvas, box: TextBox, layout: StaticLayout, controls: Boolean, text: Boolean = true) {
        val note = box.sticky ?: return
        val p = box.placement
        val saved = canvas.save()
        try {
            canvas.translate(p.x, p.y)
            canvas.rotate(p.degrees, p.width / 2, p.height / 2)
            if (note.collapsed) { tab(canvas, p.width, p.height, note.color.toInt()); return }
            rect.set(0f, 0f, p.width, p.height)
            // Two shadows: 0 12 28 rgba(29,36,51,.30) and 0 2 6 rgba(29,36,51,.18).
            fill.color = note.color.toInt()
            fill.setShadowLayer(14 * U, 0f, 12 * U, 0x4d1d2433)
            canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
            fill.setShadowLayer(3 * U, 0f, 2 * U, 0x2e1d2433)
            canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
            fill.clearShadowLayer()
            val body = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW) })
            // Header strip: rgba(0,0,0,.06) with an inset bottom line rgba(0,0,0,.10).
            fill.color = 0x0f000000; canvas.drawRect(0f, 0f, p.width, HEADER, fill)
            fill.color = 0x1a000000; canvas.drawRect(0f, HEADER - .5f * U, p.width, HEADER, fill)
            if (text && box.text.isNotEmpty()) {
                canvas.save(); canvas.translate(TEXT_INSET, HEADER + 6 * U); layout.draw(canvas); canvas.restore()
            }
            canvas.restoreToCount(body)
            // Inset edge .5 px rgba(0,0,0,.12).
            line.color = 0x1f000000; line.strokeWidth = .5f * U
            rect.inset(.25f * U, .25f * U); canvas.drawRoundRect(rect, RADIUS, RADIUS, line)
            if (!controls) return
            // Colour dot: 14 dp at left 10 / top 8, 2 px white inset and a 1.5 px rgba(0,0,0,.3) ring.
            val cx = 17 * U; val cy = 15 * U
            fill.color = 0xffffffff.toInt(); canvas.drawCircle(cx, cy, 7 * U, fill)
            fill.color = note.color.toInt(); canvas.drawCircle(cx, cy, 5 * U, fill)
            line.color = 0x4d000000; line.strokeWidth = 1.5f * U; canvas.drawCircle(cx, cy, 6.25f * U, line)
            icon(canvas, grip, p.width / 2 - 9 * U, 6 * U, 0x66000000, 2.6f)
            icon(canvas, fold, p.width - 56 * U, 6 * U, 0x9e000000.toInt(), 2.2f)
            icon(canvas, more, p.width - 28 * U, 6 * U, 0x9e000000.toInt(), 2.8f)
            icon(canvas, resize, p.width - 22 * U, p.height - 24 * U, 0x59000000, 2f)
        } finally { canvas.restoreToCount(saved) }
    }

    /** An 18 dp icon from a 24-unit path, its stroke width in path units. */
    private fun icon(canvas: Canvas, path: Path, x: Float, y: Float, color: Int, width: Float) {
        val scale = 18 * U / 24
        canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale)
        line.color = color; line.strokeWidth = width; canvas.drawPath(path, line)
        canvas.restore()
    }

    /** The folded tab: radius 8 4 8 8, two shadows, a turned corner and three lines. */
    private fun tab(canvas: Canvas, w: Float, h: Float, color: Int) {
        val k = w / 34
        val radii = floatArrayOf(8 * k, 8 * k, 4 * k, 4 * k, 8 * k, 8 * k, 8 * k, 8 * k)
        val corner = 12.24f * k
        val shape = Path().apply { addRoundRect(RectF(0f, 0f, w, h), radii, Path.Direction.CW) }
        // The turned corner leaves the top-right triangle open.
        shape.op(Path().apply { moveTo(w - corner, 0f); lineTo(w, 0f); lineTo(w, corner); close() }, Path.Op.DIFFERENCE)
        fill.color = color
        fill.setShadowLayer(4 * k, 0f, 3 * k, 0x471d2433); canvas.drawPath(shape, fill)
        fill.clearShadowLayer()
        line.color = 0x2e000000; line.strokeWidth = .5f * k; canvas.drawPath(shape, line)
        val turned = Path().apply { moveTo(w - corner, 0f); lineTo(w, corner); lineTo(w - corner, corner); close() }
        fill.color = 0xffffffff.toInt(); canvas.drawPath(turned, fill)
        fill.color = 0x29000000; canvas.drawPath(turned, fill)
        line.color = 0x8c1d2a5c.toInt(); line.strokeWidth = 2.5f * k
        listOf(Triple(7f, 11f, 18f), Triple(7f, 17f, 18f), Triple(7f, 23f, 10f)).forEach { (x, y, len) ->
            canvas.drawLine(x * k + line.strokeWidth / 2, y * k, (x + len) * k - line.strokeWidth / 2, y * k, line)
        }
    }

    /** Ink of a handwritten note is cut at the note's rounded edge; [shown] is where the note is while it is dragged or resized. */
    inline fun clipInk(canvas: Canvas, slice: Page, shown: ImagePlacement?, draw: () -> Unit) {
        val note = slice.inkNote ?: return draw()
        val p = note.placement; val s = shown ?: p
        val saved = canvas.save()
        try {
            canvas.translate(s.x - p.x, s.y - p.y)
            canvas.clipPath(Path().apply { addRoundRect(RectF(p.x, p.y, p.x + s.width, p.y + s.height), CLIP_RADIUS, CLIP_RADIUS, Path.Direction.CW) })
            draw()
        } finally { canvas.restoreToCount(saved) }
    }
    const val CLIP_RADIUS = 12 * U
}
