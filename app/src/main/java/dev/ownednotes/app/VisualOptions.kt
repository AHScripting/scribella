package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** P173: a small drawing that shows what an option does. [box] is where to draw, in pixels; [on] is the chosen state. */
fun interface Pictogram { fun draw(canvas: Canvas, box: RectF, on: Boolean) }

private class PictogramDrawable(private val art: Pictogram, private val w: Int, private val h: Int, private val night: Boolean) : Drawable() {
    var on = false
    private val box = RectF()
    override fun draw(canvas: Canvas) { box.set(bounds); Pictograms.drawing(night) { art.draw(canvas, box, on) } }
    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * An illustrated option: the drawing above a short name. It stays a TextView, so the name is still what
 * accessibility and the existing tests find and click; the selected tile gets the amber ring.
 */
@android.annotation.SuppressLint("AppCompatCustomView") // the app uses framework widgets throughout
class OptionTile internal constructor(context: Context, label: String, art: Pictogram, artWidth: Float, artHeight: Float) : TextView(context) {
    private val drawing = PictogramDrawable(art, context.dpi(artWidth), context.dpi(artHeight), context.night).apply { setBounds(0, 0, context.dpi(artWidth), context.dpi(artHeight)) }
    init {
        text = label; tooltipText = label; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
        shrinkToFit(9f, 13f)
        setCompoundDrawables(null, drawing, null, null); compoundDrawablePadding = dpi(6f)
        setPadding(dpi(4f), dpi(10f), dpi(4f), dpi(8f))
        foreground = ripple(context.night, dp(12f))
        mark(false)
    }
    /** The tile's own height: the drawing plus room for one line of name. */
    internal val natural = dpi(10f + 8f + 6f + 20f) + context.dpi(artHeight)
    fun mark(on: Boolean) {
        isSelected = on; drawing.on = on
        // P177 (G2): Glass selection is an accent ring on an accent-tinted card; P201 step 3: graphite cards at night.
        val night = context.night
        background = if (on) rounded(if (night) 0xff2c3f5c.toInt() else 0xffeaf2fc.toInt(), dp(Glass.CHIP), Glass.accent(night), dpi(2f))
            else rounded(if (night) 0xff343a47.toInt() else Color.WHITE, dp(Glass.CHIP), if (night) Glass.RULE_NIGHT else Glass.RULE, dpi(1f))
        typeface = if (on) Tokens.SANS_MEDIUM else Tokens.SANS
        setTextColor(if (on) Kit.accentText(night) else Glass.secondary(night))
        invalidate()
    }
    fun redraw() = invalidate()
}

object VisualOptions {
    /**
     * Illustrated replacement for [ToolControls.segmented], with the same contract: one TextView child per option in
     * order, `isSelected` on the chosen one, and a failed [onSelect] leaves the selection as it was.
     */
    fun row(context: Context, labels: List<String>, art: List<Pictogram>, selected: Int,
        artWidth: Float? = null, artHeight: Float? = null, onSelect: (Int) -> Unit): LinearLayout {
        val compact = labels.size > 3
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(2f), 0, dpi(4f)) }
        val tiles = labels.mapIndexed { index, label ->
            OptionTile(context, label, art[index], artWidth ?: if (compact) 40f else 48f, artHeight ?: if (compact) 27f else 32f)
        }
        fun paint(current: Int) = tiles.forEachIndexed { index, tile -> tile.mark(index == current) }
        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener {
                runCatching { onSelect(index) }.onSuccess { paint(index); tile.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            row.addView(tile, LinearLayout.LayoutParams(0, tile.natural, 1f).apply { if (index > 0) marginStart = context.dpi(6f) })
        }
        paint(selected)
        return row
    }

    /**
     * P187: the Glass board's segmented well, for tool popovers: a grey well of options, each a small drawing over its
     * name; the chosen one sits on a [SlidingWell] pill with accent text. Same contract as [row], about 20 dp shorter.
     */
    fun well(context: Context, labels: List<String>, art: List<Pictogram>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = SlidingWell(context, context.dp(12f), ToolControls.pill(context.night)).apply {
            background = rounded(ToolControls.wellFill(context.night), dp(16f))
            dpi(4f).let { setPadding(it, it, it, it) }
        }
        val tiles = labels.mapIndexed { index, label -> WellOption(context, label, art[index]) }
        fun paint(current: Int, animate: Boolean) { tiles.forEachIndexed { index, tile -> tile.mark(index == current) }; row.select(current, animate) }
        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener {
                runCatching { onSelect(index) }.onSuccess { paint(index, true); tile.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            row.addView(tile, LinearLayout.LayoutParams(0, context.dpi(58f), 1f).apply { if (index > 0) marginStart = context.dpi(4f) })
        }
        paint(selected, false)
        return row
    }
}

/**
 * P187: a row of options in a grey well with the chosen one on a flat white pill (no shadow). Choosing another option
 * moves the pill like something thick and soft: the leading edge sets off first and the trailing edge follows, so the
 * pill stretches and thins on the way, then the trailing edge overshoots a little and the pill squashes as it settles.
 */
class SlidingWell(context: Context, private val radius: Float, pill: Int = Color.WHITE) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pill }
    private val from = RectF(); private val target = RectF(); private val pill = RectF()
    private var chosen = -1
    private var progress = 1f
    private var motion: android.animation.ValueAnimator? = null
    private val lead = android.view.animation.DecelerateInterpolator(2.2f)
    private val trail = android.view.animation.OvershootInterpolator(1.6f)

    init { orientation = HORIZONTAL; setWillNotDraw(false) }

    /** Puts the pill under option [index]; with [animate], it travels there from where it is now. */
    fun select(index: Int, animate: Boolean) {
        if (index == chosen) return
        val travel = animate && chosen >= 0 && isLaidOut && !pill.isEmpty
        motion?.cancel()
        chosen = index
        if (!travel) { progress = 1f; invalidate(); return }
        from.set(pill); progress = 0f
        motion = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { motion?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val child = getChildAt(chosen) ?: return
        target.set(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat())
        if (progress >= 1f) pill.set(target) else {
            val ahead = lead.getInterpolation((progress / .7f).coerceAtMost(1f))
            val behind = trail.getInterpolation(((progress - .16f) / .84f).coerceIn(0f, 1f))
            val right = target.centerX() >= from.centerX()
            val left = lerp(from.left, target.left, if (right) behind else ahead)
            val end = lerp(from.right, target.right, if (right) ahead else behind)
            // Stretched thin while it travels, a touch taller while it squashes on arrival.
            val width = (end - left).coerceAtLeast(target.height() * .6f)
            val natural = lerp(from.width(), target.width(), progress)
            val tall = (1f / kotlin.math.sqrt(width / natural)).coerceIn(.8f, 1.04f)
            val half = target.height() * tall / 2
            pill.set(left, target.centerY() - half, left + width, target.centerY() + half)
        }
        val r = minOf(radius, pill.height() / 2)
        canvas.drawRoundRect(pill, r, r, paint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** P187: one option in a [VisualOptions.well]. */
@android.annotation.SuppressLint("AppCompatCustomView") // the app uses framework widgets throughout
private class WellOption(context: Context, label: String, art: Pictogram) : TextView(context) {
    private val drawing = PictogramDrawable(art, context.dpi(33f), context.dpi(22f), context.night).apply { setBounds(0, 0, context.dpi(33f), context.dpi(22f)) }
    init {
        text = label; tooltipText = label; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
        // P599/P602: "Highlighter" in the Lasso's six choices showed as "Highlig", then "Highlight", on a 320 dp phone; it may
        // go smaller there until the whole word fits.
        shrinkToFitWords(7.5f, 12f)
        setCompoundDrawables(null, drawing, null, null); compoundDrawablePadding = dpi(3f)
        // No ripple: the sliding pill is the touch feedback, and a dark ripple flashed over it.
        setPadding(dpi(3f), dpi(5f), dpi(3f), dpi(5f))
    }
    fun mark(on: Boolean) {
        isSelected = on; drawing.on = on
        typeface = if (on) Tokens.SANS_SEMIBOLD else Tokens.SANS_MEDIUM
        setTextColor(if (on) ToolControls.chosenInk(context.night) else ToolControls.optionInk(context.night))
        invalidate()
    }
}

/**
 * P173: a scrolling strip of illustrated cards in place of a named drop-down. It keeps the Spinner-like
 * [selectedItemPosition] / [setSelection] contract and the same item order, so callers keep their index logic.
 */
class VisualChoiceStrip(context: Context, label: String, names: List<String>, art: List<Pictogram>,
    cardWidth: Float = 76f, artWidth: Float = 56f, artHeight: Float = 64f, descriptions: List<String> = names) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(4f), 0, dpi(6f)) }
    val tiles = names.mapIndexed { index, name -> OptionTile(context, name, art[index], artWidth, artHeight).apply { contentDescription = descriptions[index] } }
    var onSelect: (Int) -> Unit = {}
    var selectedItemPosition = 0
        private set

    init {
        contentDescription = label; isHorizontalScrollBarEnabled = false; isFillViewport = false
        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { if (isEnabled) { setSelection(index); tile.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
            row.addView(tile, LinearLayout.LayoutParams(dpi(cardWidth), tile.natural).apply { if (index > 0) marginStart = dpi(8f) })
        }
        addView(row)
        mark()
    }

    fun setSelection(index: Int) {
        require(index in tiles.indices) { "No such choice" }
        selectedItemPosition = index; mark(); onSelect(index)
        tiles[index].let { tile -> post { if (tile.left < scrollX || tile.right > scrollX + width) smoothScrollTo(tile.left - dpi(8f), 0) } }
    }

    /** Shows [index] as chosen without acting on it, for a choice that follows other edits. */
    fun showSelected(index: Int) { if (index in tiles.indices) { selectedItemPosition = index; mark() } }

    /** Redraws the cards, for drawings that depend on other choices. */
    fun redraw() = tiles.forEach { it.redraw() }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled); alpha = if (enabled) 1f else .45f
        tiles.forEach { it.isEnabled = enabled }
    }

    private fun mark() = tiles.forEachIndexed { index, tile ->
        tile.mark(index == selectedItemPosition)
        tile.stateDescription = if (index == selectedItemPosition) "Selected" else null
    }
}

/**
 * Small drawings for tool options, in a 48 x 32 unit box. Chosen tiles draw in ink, others in graphite. P201 step 3: at
 * night (inside [drawing]) ink is near-white and graphite a light grey, for the graphite popover.
 */
object Pictograms {
    private var night = false
    /** Draws [block] with the night or day colours; drawings happen on the main thread, one at a time. */
    internal fun drawing(night: Boolean, block: () -> Unit) {
        val was = this.night; this.night = night
        try { block() } finally { this.night = was }
    }
    private val graphite get() = if (night) 0xff9aa3b5.toInt() else Tokens.GRAPHITE
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(2.6f, 2.2f), 0f)
    const val MARK = 0xfff4d54b.toInt()
    private val GHOST get() = if (night) 0xff586071.toInt() else 0xffd3dae1.toInt()
    private val PRINT get() = if (night) 0xff687184.toInt() else 0xffc5cdd5.toInt()

    private fun ink(on: Boolean) = if (on) (if (night) 0xfff2f4f8.toInt() else Tokens.INK) else graphite
    private inline fun units(canvas: Canvas, box: RectF, block: () -> Unit) {
        canvas.save(); canvas.translate(box.left, box.top); canvas.scale(box.width() / 48f, box.height() / 32f); block(); canvas.restore()
    }
    private fun pen(color: Int, width: Float = 2.2f, dashed: Boolean = false) = stroke.apply {
        this.color = color; strokeWidth = width; pathEffect = if (dashed) dash else null
    }
    private fun wave(x0: Float, x1: Float, y: Float, amp: Float): Path {
        path.reset(); path.moveTo(x0, y)
        val step = (x1 - x0) / 4
        path.cubicTo(x0 + step * .5f, y - amp, x0 + step * 1.5f, y - amp, x0 + step * 2, y)
        path.cubicTo(x0 + step * 2.5f, y + amp, x0 + step * 3.5f, y + amp, x1, y)
        return path
    }
    private fun band(canvas: Canvas, x0: Float, x1: Float, y: Float, alpha: Int = 200) {
        stroke.pathEffect = null; stroke.color = MARK; stroke.alpha = alpha; stroke.strokeWidth = 6.5f; stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(x0, y, x1, y, stroke); stroke.strokeCap = Paint.Cap.ROUND; stroke.alpha = 255
    }
    private fun print(canvas: Canvas, vararg ys: Float) {
        fill.color = PRINT
        ys.forEachIndexed { i, y -> canvas.drawRoundRect(7f, y - 1.3f, if (i % 2 == 0) 41f else 34f, y + 1.3f, 1.3f, 1.3f, fill) }
    }
    /** A tiny eraser leaning on its corner, the eraser tool's mark. */
    private fun eraser(canvas: Canvas, x: Float, y: Float) {
        canvas.save(); canvas.translate(x, y); canvas.rotate(-35f)
        fill.color = 0xfff7f2eb.toInt(); canvas.drawRoundRect(-3.5f, -5f, 3.5f, 1f, 1.2f, 1.2f, fill)
        fill.color = 0xff3d6a96.toInt(); canvas.drawRect(-3.5f, -.5f, 3.5f, 5f, fill)
        stroke.pathEffect = null; stroke.color = graphite; stroke.strokeWidth = .6f; canvas.drawRoundRect(-3.5f, -5f, 3.5f, 5f, 1.2f, 1.2f, stroke)
        canvas.restore()
    }

    // Hold assist: what holding the pen still at the end of a stroke does.
    val freehand = Pictogram { c, box, on -> units(c, box) {
        path.reset(); path.moveTo(8f, 22f); path.cubicTo(14f, 7f, 19f, 27f, 25f, 15f); path.cubicTo(30f, 6f, 35f, 25f, 41f, 11f)
        c.drawPath(path, pen(ink(on)))
    } }
    val straightLine = Pictogram { c, box, on -> units(c, box) {
        path.reset(); path.moveTo(9f, 23f); path.cubicTo(17f, 15f, 21f, 22f, 28f, 14f); path.cubicTo(32f, 10f, 35f, 13f, 39f, 9f)
        c.drawPath(path, pen(GHOST, 1.6f))
        c.drawLine(9f, 23f, 39f, 9f, pen(ink(on)))
        fill.color = ink(on); c.drawCircle(9f, 23f, 2.2f, fill); c.drawCircle(39f, 9f, 2.2f, fill)
    } }
    val shapes = Pictogram { c, box, on -> units(c, box) {
        path.reset(); path.moveTo(17f, 7f); path.cubicTo(27f, 6f, 27f, 25f, 16f, 25f); path.cubicTo(6f, 25f, 8f, 9f, 18f, 9.5f)
        c.drawPath(path, pen(GHOST, 1.6f))
        c.drawCircle(17f, 16f, 8.5f, pen(ink(on)))
        path.reset(); path.moveTo(33f, 8f); path.lineTo(42f, 24f); path.lineTo(24.5f, 24f); path.close()
        c.drawPath(path, pen(ink(on)))
    } }
    val holdAssist = listOf(freehand, straightLine, shapes)

    // Highlighter: free marks or marks that settle onto printed PDF lines.
    val freeMark = Pictogram { c, box, _ -> units(c, box) {
        print(c, 9f, 16f, 23f)
        path.reset(); path.moveTo(6f, 18f); path.cubicTo(14f, 12f, 22f, 21f, 30f, 14.5f); path.cubicTo(35f, 11f, 39f, 15f, 42f, 13f)
        stroke.color = MARK; stroke.alpha = 200; stroke.strokeWidth = 6.5f; stroke.pathEffect = null; c.drawPath(path, stroke); stroke.alpha = 255
    } }
    val followLines = Pictogram { c, box, on -> units(c, box) {
        print(c, 9f, 16f, 23f)
        band(c, 6f, 42f, 16f)
        fill.color = ink(on); c.drawRect(5f, 12f, 6.2f, 20f, fill); c.drawRect(41.8f, 12f, 43f, 20f, fill)
    } }

    // Eraser target: what the eraser removes is drawn solid; what it leaves alone is faint.
    private fun target(erasesInk: Boolean, erasesMarks: Boolean) = Pictogram { c, box, on -> units(c, box) {
        band(c, 6f, 40f, 12f, if (erasesMarks) 210 else 60)
        c.drawPath(wave(6f, 36f, 20f, 7f), pen(if (erasesInk) ink(on) else GHOST))
        eraser(c, 41f, 24f)
    } }
    val eraserTargets = listOf(target(true, true), target(true, false), target(false, true))

    // Eraser mode: a touched stroke disappears entirely, or only where the eraser passes.
    val wholeStroke = Pictogram { c, box, on -> units(c, box) {
        c.drawPath(wave(6f, 40f, 17f, 8f), pen(GHOST, 2.2f, dashed = true))
        eraser(c, 23f, 15f)
        fill.color = ink(on); c.drawCircle(8.5f, 27f, 1.2f, fill); c.drawCircle(14f, 27f, 1.2f, fill); c.drawCircle(19.5f, 27f, 1.2f, fill)
    } }
    val areaErase = Pictogram { c, box, on -> units(c, box) {
        val hole = Path().apply { addCircle(23f, 17f, 6.5f, Path.Direction.CW) }
        c.save(); c.clipOutPath(hole); c.drawPath(wave(6f, 40f, 17f, 8f), pen(ink(on))); c.restore()
        c.drawCircle(23f, 17f, 6.5f, pen(graphite, 1.1f, dashed = true))
    } }
    val eraserModes = listOf(wholeStroke, areaErase)

    // Lasso.
    private fun squiggle(c: Canvas, color: Int) {
        path.reset(); path.moveTo(17f, 18f); path.cubicTo(20f, 11f, 24f, 22f, 27f, 15f); path.cubicTo(29f, 11f, 31f, 18f, 33f, 15f)
        c.drawPath(path, pen(color, 2f))
    }
    val freeformLasso = Pictogram { c, box, on -> units(c, box) {
        squiggle(c, ink(on))
        path.reset(); path.moveTo(12f, 15f); path.cubicTo(12f, 4f, 36f, 3f, 39f, 13f); path.cubicTo(42f, 24f, 28f, 29f, 19f, 27f)
        path.cubicTo(13f, 26f, 11f, 21f, 12f, 15f)
        c.drawPath(path, pen(graphite, 1.2f, dashed = true))
    } }
    val rectangleLasso = Pictogram { c, box, on -> units(c, box) {
        squiggle(c, ink(on))
        c.drawRoundRect(10f, 6f, 40f, 27f, 2f, 2f, pen(graphite, 1.2f, dashed = true))
    } }
    private fun rule(touching: Boolean) = Pictogram { c, box, on -> units(c, box) {
        c.drawCircle(19f, 16f, 11f, pen(graphite, 1.2f, dashed = true))
        c.drawPath(wave(13f, 24f, 16f, 4f), pen(ink(on), 2f))
        path.reset(); path.moveTo(26f, 10f); path.cubicTo(32f, 12f, 36f, 18f, 44f, 24f)
        c.drawPath(path, pen(if (touching) ink(on) else GHOST, 2f))
    } }
    val lassoShapes = listOf(freeformLasso, rectangleLasso)
    val lassoRules = listOf(rule(true), rule(false))

    private val inkOnly = Pictogram { c, box, on -> units(c, box) { c.drawPath(wave(8f, 40f, 16f, 8f), pen(ink(on))) } }
    private val marksOnly = Pictogram { c, box, _ -> units(c, box) { print(c, 11f, 21f); band(c, 6f, 42f, 11f) } }
    private val textOnly = Pictogram { c, box, on -> units(c, box) {
        fill.color = ink(on); fill.textSize = 17f; fill.typeface = Tokens.SANS_MEDIUM; fill.textAlign = Paint.Align.CENTER
        c.drawText("Aa", 24f, 22f, fill); fill.textAlign = Paint.Align.LEFT
    } }
    private val imagesOnly = Pictogram { c, box, on -> units(c, box) {
        c.drawRoundRect(11f, 5f, 37f, 27f, 2.5f, 2.5f, pen(ink(on), 1.8f))
        path.reset(); path.moveTo(12f, 25f); path.lineTo(20f, 15f); path.lineTo(26f, 21f); path.lineTo(29f, 18f); path.lineTo(36f, 25f)
        fill.color = if (on) 0xff7fa7cc.toInt() else 0xffb4c3d1.toInt(); c.drawPath(path, fill)
        fill.color = Tokens.AMBER; c.drawCircle(30f, 11f, 2.6f, fill)
    } }
    private val everything = Pictogram { c, box, on -> units(c, box) {
        band(c, 6f, 26f, 9f)
        c.drawPath(wave(6f, 26f, 22f, 5f), pen(ink(on), 1.8f))
        fill.color = ink(on); fill.textSize = 11f; fill.typeface = Tokens.SANS_MEDIUM; c.drawText("A", 30f, 14f, fill)
        c.drawRoundRect(31f, 18f, 42f, 27f, 1.5f, 1.5f, pen(ink(on), 1.4f))
    } }
    val lassoFilters = listOf(everything, inkOnly, marksOnly, textOnly, imagesOnly, shapes)

    /** A palette as its first colours, side by side. */
    fun palette(colors: List<Long>) = Pictogram { c, box, on -> units(c, box) {
        val shown = colors.take(5)
        shown.forEachIndexed { i, color ->
            val x = 24f + (i - (shown.size - 1) / 2f) * 8.4f
            fill.color = color.toInt() or 0xff000000.toInt(); c.drawCircle(x, 16f, 5.4f, fill)
            if (Color.luminance(fill.color) > .8f) c.drawCircle(x, 16f, 5.4f, pen(Tokens.RULE, .8f))
        }
        if (!on) { fill.color = if (night) 0x40353b47 else 0x40ffffff; c.drawRect(0f, 0f, 48f, 32f, fill) }
    } }
}
