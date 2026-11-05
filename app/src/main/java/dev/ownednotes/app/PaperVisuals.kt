package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import dev.ownednotes.core.PaperPattern
import dev.ownednotes.core.PaperStyle
import kotlin.math.max

/** P173: paper and page drawings shared by the page, paper and size choosers. */
object PaperArt {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dashed = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    private val sheet = RectF()

    /**
     * Paper in [page] as a close-up of a page corner 300 page units wide, so ruled, grid and dot spacing stay
     * distinguishable at thumbnail size. Guides are at least one pixel.
     */
    fun closeUp(canvas: Canvas, page: RectF, paper: PaperStyle, paint: Paint, u: Float) {
        paint.style = Paint.Style.FILL; paint.color = paper.color.toInt(); canvas.drawRect(page, paint)
        canvas.save(); canvas.clipRect(page)
        val scale = page.width() / 300f; val step = paper.spacing * scale
        paint.color = paper.guideColor.toInt(); paint.strokeWidth = max(1f, paper.weight * scale * 1.6f)
        when (paper.pattern) {
            PaperPattern.PLAIN -> {}
            PaperPattern.RULED, PaperPattern.GRID -> {
                var y = page.top + step; while (y < page.bottom) { canvas.drawLine(page.left, y, page.right, y, paint); y += step }
                if (paper.pattern == PaperPattern.GRID) { var x = page.left + step; while (x < page.right) { canvas.drawLine(x, page.top, x, page.bottom, paint); x += step } }
            }
            PaperPattern.DOTS -> {
                val r = max(u * .9f, paper.weight * scale * 1.2f)
                var y = page.top + step; while (y < page.bottom) { var x = page.left + step; while (x < page.right) { canvas.drawCircle(x, y, r, paint); x += step }; y += step }
            }
            PaperPattern.ISOMETRIC -> {
                // P092: the shared guide placement, drawn over a 300-unit-wide corner of the page.
                canvas.save(); canvas.translate(page.left, page.top); canvas.scale(scale, scale); paint.strokeWidth /= scale
                paper.guides(page.width() / scale, page.height() / scale, { x1, y1, x2, y2 -> canvas.drawLine(x1, y1, x2, y2, paint) }, { _, _, _ -> })
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /**
     * A page of the given proportions centred in [box], lifted by a soft shadow. [paper] fills it (null: plain white);
     * [imported] lays grey "print" over it, for pages whose PDF or picture background is kept.
     */
    fun page(canvas: Canvas, box: RectF, width: Float, height: Float, paper: PaperStyle?, on: Boolean,
        fill: Float = .9f, imported: Boolean = false, outline: Boolean = false): RectF {
        val u = box.height() / 64f
        val scale = minOf(box.width() * fill / width, box.height() * fill / height)
        val w = width * scale; val h = height * scale
        sheet.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        val r = 2.5f * u
        if (!outline) {
            paint.style = Paint.Style.FILL; paint.color = 0x220f1822
            canvas.drawRoundRect(sheet.left + .6f * u, sheet.top + 1.4f * u, sheet.right + .6f * u, sheet.bottom + 1.8f * u, r, r, paint)
            canvas.save(); canvas.clipRect(sheet)
            if (paper != null) closeUp(canvas, sheet, paper, paint, u * .5f) else { paint.color = Color.WHITE; canvas.drawRect(sheet, paint) }
            if (imported) {
                paint.style = Paint.Style.FILL; paint.color = 0xffaab4be.toInt()
                var y = sheet.top + 7f * u; var i = 0
                while (y < sheet.bottom - 4f * u) {
                    canvas.drawRoundRect(sheet.left + 4f * u, y, sheet.right - (if (i++ % 3 == 2) 12f else 4f) * u, y + 1.6f * u, .8f * u, .8f * u, paint); y += 4.4f * u
                }
                paint.color = 0xffd7dee5.toInt(); canvas.drawRect(sheet.left + 4f * u, sheet.top + 2.6f * u, sheet.centerX(), sheet.top + 5f * u, paint)
            }
            canvas.restore()
        }
        line.pathEffect = if (outline) dashed else null
        line.strokeWidth = if (outline) 1.4f * u else .9f * u
        line.color = if (outline) Tokens.GRAPHITE else if (on) 0xff9aa6b2.toInt() else 0xffc0c9d2.toInt()
        canvas.drawRoundRect(sheet, r, r, line)
        line.pathEffect = null
        return RectF(sheet)
    }

    /** Template card: a portrait page showing the paper. */
    fun template(style: () -> PaperStyle?, imported: () -> Boolean = { false }) = Pictogram { c, box, on ->
        page(c, box, 3f, 4f, style(), on, .94f, imported())
    }

    enum class SizeMark { NONE, KEEP, DOUBLE, CUSTOM }

    /** Size card: a page silhouette in the size's proportions with the chosen paper; marks explain Keep, 2× and Custom. */
    fun size(proportions: () -> Pair<Float, Float>, paper: () -> PaperStyle?, mark: SizeMark = SizeMark.NONE, imported: () -> Boolean = { false }) = Pictogram { c, box, on ->
        val (w, h) = proportions()
        val u = box.height() / 64f
        when (mark) {
            SizeMark.DOUBLE -> {
                val outer = page(c, box, w, h, paper(), on, .92f)
                val inner = RectF(outer.left, outer.bottom - outer.height() / 2, outer.left + outer.width() / 2, outer.bottom)
                line.pathEffect = dashed; line.strokeWidth = 1.3f * u; line.color = Tokens.GRAPHITE; c.drawRect(inner, line); line.pathEffect = null
                arrow(c, inner.right - 1.5f * u, inner.top + 1.5f * u, outer.right - 3.5f * u, outer.top + 3.5f * u, u, if (on) Tokens.INK else Tokens.GRAPHITE)
            }
            SizeMark.CUSTOM -> {
                val outer = page(c, box, w, h, null, on, .82f, outline = true)
                line.color = if (on) Tokens.INK else Tokens.GRAPHITE; line.strokeWidth = 1.6f * u
                c.drawLine(outer.right - 7f * u, outer.bottom + 2f * u, outer.right + 2f * u, outer.bottom - 7f * u, line)
                c.drawLine(outer.right - 2.5f * u, outer.bottom + 2f * u, outer.right + 2f * u, outer.bottom - 2.5f * u, line)
            }
            SizeMark.KEEP -> page(c, box, w, h, paper(), on, .9f, imported())
            SizeMark.NONE -> page(c, box, w, h, paper(), on, .9f)
        }
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, color: Int) {
        line.color = color; line.strokeWidth = 1.6f * u
        c.drawLine(x0, y0, x1, y1, line)
        c.drawLine(x1, y1, x1 - 4.5f * u, y1, line); c.drawLine(x1, y1, x1, y1 + 4.5f * u, line)
    }

    /** Scope: one page, or the whole stack of pages. */
    fun scope(all: Boolean) = Pictogram { c, box, on ->
        if (all) {
            val u = box.height() / 64f
            for (i in 2 downTo 1) {
                val shifted = RectF(box.left + i * 5f * u, box.top - i * 4f * u, box.right + i * 5f * u, box.bottom - i * 4f * u)
                page(c, shifted, 3f, 4f, null, false, .78f)
            }
            page(c, box, 3f, 4f, null, on, .78f)
        } else page(c, box, 3f, 4f, null, on, .78f)
    }

    /** Portrait or landscape: the same sheet turned. */
    fun orientation(landscape: Boolean, paper: () -> PaperStyle?) = Pictogram { c, box, on ->
        if (landscape) page(c, box, 4f, 3f, paper(), on, .86f) else page(c, box, 3f, 4f, paper(), on, .86f)
    }
}

/**
 * A paper colour as a little sheet with the current guides across it, so the user sees paper and lines together.
 * A null [color] is the custom chip that opens the full colour picker.
 */
internal class PaperColorChip(context: Context, val color: Long?, label: String, private val sample: () -> PaperStyle, private val guides: Boolean) : View(context) {
    var on = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    init { contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true; foreground = ripple(false, dp(10f)) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dpi(44f), dpi(48f))
    override fun onDraw(canvas: Canvas) {
        val r = dp(8f)
        box.set(dp(7f), dp(9f), width - dp(7f), height - dp(9f))
        if (on) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2.5f); paint.color = Tokens.AMBER
            canvas.drawRoundRect(box.left - dp(4f), box.top - dp(4f), box.right + dp(4f), box.bottom + dp(4f), r + dp(3f), r + dp(3f), paint)
        }
        val base = sample()
        if (color == null) {
            // Custom: a colour wheel ring around the current value.
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.SweepGradient(box.centerX(), box.centerY(),
                intArrayOf(0xffff5252.toInt(), 0xffffd740.toInt(), 0xff69f0ae.toInt(), 0xff40c4ff.toInt(), 0xffb388ff.toInt(), 0xffff5252.toInt()), null)
            canvas.drawRoundRect(box, r, r, paint); paint.shader = null
            paint.color = (if (guides) base.guideColor else base.color).toInt()
            canvas.drawRoundRect(box.left + dp(4f), box.top + dp(4f), box.right - dp(4f), box.bottom - dp(4f), r - dp(3f), r - dp(3f), paint)
            return
        }
        val style = if (guides) base.copy(guideColor = color, pattern = PaperPattern.RULED, spacing = 64f, weight = 3f)
            else base.copy(color = color, pattern = if (base.pattern == PaperPattern.PLAIN) PaperPattern.RULED else base.pattern, spacing = 64f, weight = 3f)
        canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        PaperArt.closeUp(canvas, box, style, paint, dp(1f))
        canvas.restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = Tokens.RULE
        canvas.drawRoundRect(box, r, r, paint)
    }
}

/** A row of paper or guide colour chips; the last opens the colour picker. */
internal class PaperColorRow(context: Context, private val chips: List<PaperColorChip>) : LinearLayout(context) {
    init { orientation = HORIZONTAL; chips.forEach { addView(it) } }
    /** Rings the matching chip, or the custom chip when the colour is not one of the swatches. */
    fun mark(current: Long) {
        val match = chips.any { it.color == current }
        chips.forEach { it.on = if (it.color == null) !match else it.color == current }
    }
    fun redraw() = chips.forEach { it.invalidate() }
}

object PaperColors {
    val papers = listOf(0xffffffffL to "White", 0xfffaf5eaL to "Cream", 0xfffdf3c4L to "Legal yellow", 0xffe9f5eeL to "Mint",
        0xffeaf2fbL to "Sky", 0xfffbececL to "Blush", 0xfff0ecf8L to "Lavender", 0xffe8d9bfL to "Kraft", 0xff2b3440L to "Slate", 0xff1a1d21L to "Night")
    val guides = listOf(0xffced7e0L to "Soft grey", 0xff8fb4d8L to "Blue", 0xffa9b5c1L to "Grey", 0xffd8ccb4L to "Sand",
        0xffe3a9a9L to "Rose", 0xffa8cdb4L to "Green", 0xff5b6875L to "Graphite")
}
