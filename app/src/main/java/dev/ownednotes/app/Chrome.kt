package dev.ownednotes.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Visual tokens from docs/DESIGN-PROPOSAL.md (D5). Chrome is ink; paper is reserved for pages. */
object Tokens {
    const val INK = 0xff182636.toInt()
    const val INK_RAISED = 0xff22354a.toInt()
    const val INK_RULE = 0xff33475c.toInt()
    /** P186: the Glass board's desk, by day and by night. */
    const val DESK = 0xffe6e9ef.toInt()
    const val DESK_DARK = 0xff0d0f13.toInt()
    const val SURFACE = 0xfff7f9fb.toInt()
    const val GRAPHITE = 0xff5b6875.toInt()
    const val RULE = 0xffd6dce2.toInt()
    const val AMBER = 0xfff2a93b.toInt()
    const val FAULT = 0xffc2362b.toInt()
    const val ON_INK = 0xffe9eef3.toInt()
    const val ON_INK_DIM = 0xff8fa0b2.toInt()
    const val ON_INK_OFF = 0xff4c5d70.toInt()
    const val SAVED = 0xff6fbf8e.toInt()
    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** P186: the Glass board's 600 weight, for titles and the Library link. */
    val SANS_SEMIBOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
}

/**
 * P175 (G1): the Glass concept's tokens, from the design-system board. Light frosted panes float on the desk with a
 * hairline edge and a soft shadow; night panes are graphite. Views opt in one at a time (docs/GLASS-BUILD-PLAN.md).
 */
object Glass {
    const val ACCENT = 0xff0a66d9.toInt()
    const val ACCENT_NIGHT = 0xff0a84ff.toInt()
    const val TEXT = 0xff1d2433.toInt()
    const val TEXT_NIGHT = 0xfff2f4f8.toInt()
    const val SECONDARY = 0xff566074.toInt()
    const val SECONDARY_NIGHT = 0xffa3acbd.toInt()
    const val OFF = 0xffb4bbc7.toInt()
    const val OFF_NIGHT = 0xff545c69.toInt()
    const val PANE = 0xf5ffffff.toInt()
    // Opaque at night: there is no blur, and 4% of white paper through graphite showed as a ghost (P201 step 3).
    const val PANE_NIGHT = 0xff2a2f3a.toInt()
    const val EDGE = 0xffffffff.toInt()
    const val EDGE_NIGHT = 0x1affffff
    // P582: menus and tool boards float over the Library's white cards and the page. By day they are solid white with a
    // faint ink hairline: the 96% pane let the text behind show through the menu's own text, and its white edge vanished on white.
    const val FLOAT = 0xffffffff.toInt()
    const val FLOAT_EDGE = 0x241d2433
    fun float(context: Context, night: Boolean, radius: Float) =
        if (night) pane(context, true, radius) else rounded(FLOAT, context.dp(radius), FLOAT_EDGE, 1)
    const val RULE = 0x1f1d2433
    const val RULE_NIGHT = 0x24ffffff
    const val PILL = 26f
    const val CHIP = 12f
    /** P177 (G2): popover sheets and the grouped cards inside them. */
    const val CARD = 20f
    const val SHEET = 34f
    /** Thin glass: chip wells and grouped menu rows on a light pane (ink 6%). */
    const val THIN = 0x0f1d2433
    /** A selected chip's fill: accent at 14%. */
    const val SELECTED = 0x240a66d9
    /** P179 (G4): the pale desk the Library's covers sit on. */
    const val DESK = 0xffedf0f5.toInt()

    fun accent(night: Boolean) = if (night) ACCENT_NIGHT else ACCENT
    fun text(night: Boolean) = if (night) TEXT_NIGHT else TEXT
    fun secondary(night: Boolean) = if (night) SECONDARY_NIGHT else SECONDARY
    /** P614 (board 8): by day a floating pane's edge is ink at 18%, 1 dp, so white controls stay visible on a white page. */
    const val CHROME_EDGE = 0x2e1d2433
    /** A floating pane: frosted fill and a hairline edge (light at night). Give the view an elevation for its shadow. */
    fun pane(context: Context, night: Boolean, radius: Float = PILL) =
        if (night) rounded(PANE_NIGHT, context.dp(radius), EDGE_NIGHT, 1) else rounded(PANE, context.dp(radius), CHROME_EDGE, context.dpi(1f))
    /** A short hairline between groups inside a pane. */
    fun rule(context: Context, night: Boolean) = View(context).apply { setBackgroundColor(if (night) RULE_NIGHT else RULE) }
    /**
     * P180 (G5): shows an open dialog as a glass bottom sheet: on the bottom edge, at most 760dp wide, with rounded
     * top corners; graphite at night (P201 step 3). Content, buttons and guards are the dialog's own. Call after show().
     */
    fun sheet(dialog: android.app.Dialog) {
        val window = dialog.window ?: return
        val context = dialog.context
        val r = context.dp(SHEET)
        val night = context.night
        window.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Kit.sheet(night)); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setStroke(1, if (night) EDGE_NIGHT else EDGE)
        })
        window.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        window.setLayout(minOf(context.resources.displayMetrics.widthPixels, context.dpi(760f)), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        window.decorView.setPadding(context.dpi(8f), context.dpi(14f), context.dpi(8f), context.dpi(6f))
    }
}

fun Context.dp(value: Float) = value * resources.displayMetrics.density
fun Context.dpi(value: Float) = (dp(value) + .5f).toInt()
fun View.dp(value: Float) = context.dp(value)
fun View.dpi(value: Float) = context.dpi(value)
fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}
fun ripple(onDark: Boolean, radius: Float) =
    RippleDrawable(ColorStateList.valueOf(if (onDark) 0x30ffffff else 0x1f182636), null, rounded(-1, radius))

/** Motion from D5: short eased fades and slides, none at all when the system turns animations off. */
object Motion {
    const val SHORT = 150L
    const val MEDIUM = 200L
    val enabled: Boolean get() = android.animation.ValueAnimator.areAnimatorsEnabled()
    val easeOut = android.view.animation.DecelerateInterpolator(1.6f)
}

/** One icon set drawn for this app: 24-unit grid, 1.75-unit round strokes (1.75 dp at 24 dp). */
enum class Glyph { BACK, PREVIOUS, NEXT, UNDO, REDO, MORE, PAGES, PAGE_SETTINGS, CAPTURE, STUDY, ERASER_STROKE, ERASER_AREA, LASSO_FREEFORM, LASSO_RECTANGLE, ZOOM_IN, ZOOM_OUT, FIT, DUPLICATE, DELETE, CLOSE, RETRY, PLUS, GRIP, PANEL, SEARCH, SHARE, SHAPES, PANEL_LEFT, UNLOCK, MENU, PASTE, CUT, COPY, TEXT, CROP, RECORD }

object Glyphs {
    private val dash = DashPathEffect(floatArrayOf(2.3f, 2.1f), 0f)
    fun draw(canvas: Canvas, glyph: Glyph, paint: Paint) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.75f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.pathEffect = null
        val p = Path()
        fun line(vararg xy: Float) { p.moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 } }
        when (glyph) {
            Glyph.BACK, Glyph.PREVIOUS -> line(14.5f, 5.5f, 8f, 12f, 14.5f, 18.5f)
            Glyph.NEXT -> line(9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)
            Glyph.UNDO, Glyph.REDO -> {
                canvas.save()
                if (glyph == Glyph.REDO) canvas.scale(-1f, 1f, 12f, 12f)
                line(8f, 5f, 4f, 9f, 8f, 13f)
                p.moveTo(4f, 9f); p.lineTo(15f, 9f); p.arcTo(RectF(10f, 9f, 20f, 19f), -90f, 180f); p.lineTo(11f, 19f)
                canvas.drawPath(p, paint); canvas.restore(); return
            }
            Glyph.RECORD -> { canvas.drawCircle(12f,12f,6f,paint); return }
            Glyph.MORE -> {
                paint.style = Paint.Style.FILL
                listOf(5f, 12f, 19f).forEach { canvas.drawCircle(it, 12f, 1.6f, paint) }
                return
            }
            Glyph.CAPTURE -> {
                line(3f, 8f, 3f, 3f, 8f, 3f); line(16f, 3f, 21f, 3f, 21f, 8f)
                line(3f, 16f, 3f, 21f, 8f, 21f); line(16f, 21f, 21f, 21f, 21f, 16f)
                line(7f, 9f, 17f, 9f); line(7f, 14f, 17f, 14f)
            }
            Glyph.STUDY -> {
                line(12f, 5f, 7f, 3f, 2f, 4f, 2f, 19f, 7f, 18f, 12f, 20f, 17f, 18f, 22f, 19f, 22f, 4f, 17f, 3f, 12f, 5f, 12f, 20f)
            }
            // P186: "Page and paper", a ruled sheet, as on the Glass board.
            Glyph.PAGE_SETTINGS -> {
                p.addRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, Path.Direction.CW)
                line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
            }
            Glyph.PAGES -> {
                p.addRoundRect(RectF(5f, 3.5f, 15.5f, 17.5f), 1.5f, 1.5f, Path.Direction.CW)
                line(9f, 20.5f, 19f, 20.5f, 19f, 7.5f)
            }
            Glyph.ERASER_STROKE, Glyph.ERASER_AREA -> {
                line(3.5f, 14.5f, 12.5f, 5.5f, 19f, 12f, 12f, 19f, 8f, 19f); p.close()
                line(8f, 10f, 14.5f, 16.5f)
                line(14.5f, 19f, 20.5f, 19f)
                canvas.drawPath(p, paint)
                // Mode badge: a dashed area circle, or a short stroke for whole-stroke erasing.
                if (glyph == Glyph.ERASER_AREA) { paint.pathEffect = dash; canvas.drawCircle(19.5f, 4.5f, 2.8f, paint); paint.pathEffect = null }
                else { val s = Path(); s.moveTo(16.5f, 5.5f); s.cubicTo(17.5f, 2.5f, 19.5f, 7f, 21.5f, 3.5f); canvas.drawPath(s, paint) }
                return
            }
            Glyph.LASSO_FREEFORM -> {
                paint.pathEffect = dash; canvas.drawOval(RectF(4f, 4f, 20f, 15f), paint); paint.pathEffect = null
                p.moveTo(7.5f, 13.5f); p.quadTo(4.5f, 17f, 7.5f, 20f)
            }
            Glyph.LASSO_RECTANGLE -> {
                paint.pathEffect = dash; canvas.drawRect(RectF(4f, 5f, 20f, 17f), paint); paint.pathEffect = null
                paint.style = Paint.Style.FILL; canvas.drawCircle(20f, 17f, 2f, paint); return
            }
            Glyph.ZOOM_IN, Glyph.ZOOM_OUT -> {
                p.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW)
                line(15.5f, 15.5f, 20f, 20f)
                line(7.5f, 10.5f, 13.5f, 10.5f)
                if (glyph == Glyph.ZOOM_IN) line(10.5f, 7.5f, 10.5f, 13.5f)
            }
            Glyph.FIT -> { line(4f, 9f, 4f, 4f, 9f, 4f); line(15f, 4f, 20f, 4f, 20f, 9f); line(20f, 15f, 20f, 20f, 15f, 20f); line(9f, 20f, 4f, 20f, 4f, 15f) }
            Glyph.DUPLICATE -> {
                p.addRoundRect(RectF(8.5f, 8.5f, 20f, 20f), 2f, 2f, Path.Direction.CW)
                line(15.5f, 4f, 6f, 4f, 4f, 6f, 4f, 15.5f)
            }
            Glyph.DELETE -> {
                line(4f, 7f, 20f, 7f); line(9.5f, 7f, 9.5f, 4f, 14.5f, 4f, 14.5f, 7f)
                line(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
                line(10f, 11f, 10f, 16.5f); line(14f, 11f, 14f, 16.5f)
            }
            Glyph.CLOSE -> { line(6.5f, 6.5f, 17.5f, 17.5f); line(17.5f, 6.5f, 6.5f, 17.5f) }
            Glyph.RETRY -> {
                p.addArc(RectF(5f, 5f, 19f, 19f), 30f, 285f)
                line(18.5f, 3.5f, 18.4f, 8.2f, 13.8f, 7.6f)
            }
            Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.SEARCH -> { p.addCircle(11f, 11f, 7f, Path.Direction.CW); line(16f, 16f, 20f, 20f) }
            Glyph.SHARE -> {
                // A tray with an arrow leaving it: import and export.
                line(12f, 15f, 12f, 3f); line(8f, 7f, 12f, 3f, 16f, 7f)
                p.moveTo(6f, 11f); p.lineTo(6f, 19f); p.quadTo(6f, 21f, 8f, 21f); p.lineTo(16f, 21f); p.quadTo(18f, 21f, 18f, 19f); p.lineTo(18f, 11f)
            }
            // B11: a circle behind a square, for the Shapes button.
            Glyph.SHAPES -> { p.addCircle(9.5f, 9.5f, 5.5f, Path.Direction.CW); p.addRoundRect(RectF(11f, 11f, 20f, 20f), 1f, 1f, Path.Direction.CW) }
            Glyph.PANEL, Glyph.PANEL_LEFT -> {
                // Page panel: a frame divided on the side the panel opens (B09: PANEL_LEFT for the left; P186: the board's "Pages").
                p.addRoundRect(RectF(3f, 4f, 21f, 20f), 3f, 3f, Path.Direction.CW)
                val rule = if (glyph == Glyph.PANEL_LEFT) 9f else 15f
                line(rule, 4f, rule, 20f)
            }
            // B12: an open padlock, its shackle swung up off the right post.
            Glyph.UNLOCK -> { p.addRoundRect(RectF(5f, 11f, 19f, 20.5f), 2f, 2f, Path.Direction.CW); p.moveTo(8.5f, 11f); p.lineTo(8.5f, 7.5f); p.arcTo(RectF(8.5f, 3.5f, 15.5f, 10.5f), 180f, 180f); line(12f, 14.5f, 12f, 17f) }
            // B16: three lines, the Library's menu.
            Glyph.MENU -> { line(4.5f, 7f, 19.5f, 7f); line(4.5f, 12f, 19.5f, 12f); line(4.5f, 17f, 19.5f, 17f) }
            // B35 (P198): a clipboard, for Paste.
            Glyph.PASTE -> {
                p.addRoundRect(RectF(5f, 4.5f, 19f, 21f), 2f, 2f, Path.Direction.CW); p.addRoundRect(RectF(9f, 3f, 15f, 6.5f), 1.2f, 1.2f, Path.Direction.CW)
                line(8.5f, 11f, 15.5f, 11f); line(8.5f, 15f, 13.5f, 15f)
            }
            // P201 step 5: the Selection board's menu icons.
            Glyph.CUT -> { p.addCircle(6f, 18f, 3f, Path.Direction.CW); p.addCircle(18f, 18f, 3f, Path.Direction.CW); line(8f, 16f, 19f, 4f); line(16f, 16f, 5f, 4f) }
            Glyph.COPY -> {
                p.addRoundRect(RectF(8f, 8f, 20f, 20f), 2f, 2f, Path.Direction.CW)
                p.moveTo(16f, 8f); p.lineTo(16f, 6f); p.quadTo(16f, 4f, 14f, 4f); p.lineTo(6f, 4f); p.quadTo(4f, 4f, 4f, 6f)
                p.lineTo(4f, 14f); p.quadTo(4f, 16f, 6f, 16f); p.lineTo(8f, 16f)
            }
            Glyph.TEXT -> { line(4f, 6f, 20f, 6f); line(9f, 6f, 9f, 19f); line(4f, 19f, 14f, 19f) }
            Glyph.CROP -> { line(7f, 3f, 7f, 17f, 21f, 17f); line(3f, 7f, 17f, 7f, 17f, 21f) }
            Glyph.GRIP -> {
                // Drag handle: two columns of three dots.
                paint.style = Paint.Style.FILL
                for (x in floatArrayOf(9f, 15f)) for (y in floatArrayOf(6f, 12f, 18f)) canvas.drawCircle(x, y, 1.6f, paint)
                return
            }
        }
        canvas.drawPath(p, paint)
    }
}

/**
 * A 48 dp chrome control on ink. It can show a glyph, a text label, or a pen preset's ink sample.
 * `active` marks the selected tool; `override` shows the S Pen button's momentary eraser without
 * changing the selected tool; `suspended` marks the selected tool while that override is held.
 */
class ChromeButton(context: Context, label: String, glyph: Glyph? = null, text: String? = null) : View(context) {
    var glyph = glyph; set(value) { if (field != value) { field = value; invalidate() } }
    var text = text; set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var sample: PenStyle? = null; set(value) { if (field != value) { field = value; invalidate() } }
    var highlighterSample: PenStyle? = null; set(value) { if (field != value) { field = value; invalidate() } }
    /** P201 step 5: on Glass, a colour dot where the glyph stands (the selection menu's "Style…" shows the selection's colour). */
    var dot: Int? = null; set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    private val leads get() = glyph != null || dot != null
    private var heldEnabledAppearance: Boolean? = null
    private val visuallyEnabled get() = heldEnabledAppearance ?: isEnabled
    /** Input/save guards still disable actions; short-lived guards must not flash the toolbar. */
    internal fun holdEnabledAppearance(hold: Boolean) {
        val next = if (hold) heldEnabledAppearance ?: isEnabled else null
        if (next != heldEnabledAppearance) { heldEnabledAppearance = next; invalidate() }
    }
    var active = false; set(value) { if (field != value) { field = value; invalidate() } }
    var override = false; set(value) { if (field != value) { field = value; invalidate() } }
    var suspended = false; set(value) { if (field != value) { field = value; invalidate() } }
    /** P175 (G1): drawn on a Glass pane: dark glyphs, an accent chip when active, accent rings for override and suspension. */
    var glass = false; set(value) { if (field != value) { field = value; restyle() } }
    var night = false; set(value) { if (field != value) { field = value; restyle() } }
    /**
     * P175 (G1): on Glass, a pen or highlighter stands as its physical tool, tip toward the page at this angle
     * (0 up, 90 right, 180 down, 270 left); the selected one stands raised. Null keeps the ink-sample tile.
     * P560: the toolbar passes 0 at every edge, so its tools look the same wherever it is docked.
     */
    var uprightTip: Float? = null; set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    /** P186: a tool that is not a pen (eraser, lasso, shapes and ruler) standing in the palette beside the pens. */
    var uprightTool: ToolArt.Tool? = null; set(value) { if (field != value) { field = value; invalidate() } }
    /** P186: the Glass board's Library link: accent text, larger and semibold. */
    var emphasis = false; set(value) {
        if (field != value) {
            field = value
            textPaint.textSize = dp(if (value) 16f else 15f); textPaint.typeface = if (value) Tokens.SANS_SEMIBOLD else Tokens.SANS_MEDIUM
            requestLayout(); invalidate()
        }
    }
    private val slot get() = uprightTip != null
    private val sideways get() = uprightTip.let { it == 90f || it == 270f }
    /** P560: how tall the slot stands; a rail's are shorter, so its rows of tools fit a landscape screen. */
    var slotLength = SLOT; set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); typeface = Tokens.SANS_MEDIUM; fontFeatureSettings = "tnum" }
    private val box = RectF()
    private val suspendedDash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    private val samplePath = Path().apply { moveTo(6f, 22f); cubicTo(10f, 13f, 14f, 9f, 16.5f, 13f); cubicTo(18.5f, 16f, 15f, 21f, 19.5f, 21f); cubicTo(23f, 21f, 23.5f, 12f, 27f, 10f) }
    init {
        contentDescription = label; tooltipText = label
        isClickable = true; isFocusable = true
        foreground = ripple(true, dp(10f))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // P186: a palette slot is tall enough for a tool to stand in it (P614: 58 dp, from the board's 112 and then 76 dp,
        // so the page gets the room); 48 dp keeps the touch target.
        if (slot) { if (sideways) setMeasuredDimension(dpi(slotLength), dpi(48f)) else setMeasuredDimension(dpi(48f), dpi(slotLength)); return }
        val label = text
        val lead = if (emphasis) dp(10f) + dp(26f) else dp(12f) + dp(30f)
        val width = if (label == null) dpi(48f) else ((if (leads) lead else dp(12f)) + textPaint.measureText(label) + dp(if (emphasis) 18f else 14f)).toInt()
        setMeasuredDimension(max(width, dpi(48f)), dpi(48f))
    }
    override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); invalidate() } }
    private fun restyle() { foreground = ripple(!glass || night, dp(if (glass) Glass.CHIP else 10f)); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glass) { drawGlass(canvas); return }
        val inset = dp(2f)
        box.set(inset, inset, width - inset, height - inset)
        if (active || override) {
            paint.style = Paint.Style.FILL; paint.color = Tokens.INK_RAISED; paint.pathEffect = null
            canvas.drawRoundRect(box, dp(10f), dp(10f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
            paint.color = if (suspended) Tokens.ON_INK_DIM else Tokens.AMBER
            paint.pathEffect = if (suspended) suspendedDash else null
            box.inset(dp(1f), dp(1f)); canvas.drawRoundRect(box, dp(9f), dp(9f), paint); paint.pathEffect = null
        }
        val color = when { !visuallyEnabled -> Tokens.ON_INK_OFF; override -> Tokens.AMBER; else -> Tokens.ON_INK }
        val style = sample
        val marker = highlighterSample
        if (marker != null) drawSample(canvas, marker, highlighter = true)
        else if (style != null) drawSample(canvas, style)
        else glyph?.let {
            canvas.save()
            val left = if (text == null) (width - dp(24f)) / 2 else dp(12f)
            canvas.translate(left, (height - dp(24f)) / 2); canvas.scale(dp(1f), dp(1f))
            paint.color = color; Glyphs.draw(canvas, it, paint)
            canvas.restore()
        }
        text?.let {
            textPaint.color = if (visuallyEnabled) Tokens.ON_INK else Tokens.ON_INK_OFF
            val x = if (glyph != null) dp(42f) else dp(12f)
            canvas.drawText(it, x, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }
    private fun drawGlass(canvas: Canvas) {
        val accent = Glass.accent(night)
        val inset = dp(if (slot) 1f else 3f)
        box.set(inset, inset, width - inset, height - inset)
        val tip = uprightTip
        val tool = when {
            tip == null -> null
            uprightTool != null -> uprightTool
            highlighterSample != null -> ToolArt.Tool.HIGHLIGHTER
            else -> sample?.let { ToolArt.of(it.kind) }
        }
        // A raised tool marks itself; everything else gets a tinted chip.
        if ((active || override) && tool == null) {
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = accent; paint.alpha = if (night) 62 else 36
            canvas.drawRoundRect(box, dp(Glass.CHIP), dp(Glass.CHIP), paint); paint.alpha = 255
        }
        if (tool != null) drawUpright(canvas, tool, tip!!)
        else if (highlighterSample != null) drawSample(canvas, highlighterSample!!, highlighter = true)
        else if (sample != null) drawSample(canvas, sample!!)
        val color = when { !visuallyEnabled -> if (night) Glass.OFF_NIGHT else Glass.OFF; active || override -> accent; else -> Glass.text(night) }
        if (tool == null && sample == null && highlighterSample == null) glyph?.let {
            // P186: the board's glass icons are 22 dp; the Library chevron leads its accent link.
            canvas.save()
            val size = dp(22f)
            val left = if (text == null) (width - size) / 2 else dp(if (emphasis) 10f else 12f)
            canvas.translate(left, (height - size) / 2); canvas.scale(size / 24f, size / 24f)
            paint.color = if (emphasis && visuallyEnabled) accent else color; Glyphs.draw(canvas, it, paint)
            canvas.restore()
        }
        if (tool == null && sample == null && highlighterSample == null && glyph == null) dot?.let {
            val r = dp(9f); val cx = if (text == null) width / 2f else dp(12f) + dp(11f); val cy = height / 2f
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = if (night) Glass.RULE_NIGHT else 0x26_1D2433; canvas.drawCircle(cx, cy, r + dp(3.5f), paint)
            paint.color = if (night) Glass.PANE_NIGHT else -1; canvas.drawCircle(cx, cy, r + dp(2f), paint)
            paint.color = it; if (!visuallyEnabled) paint.alpha = 90; canvas.drawCircle(cx, cy, r, paint); paint.alpha = 255
        }
        text?.let {
            textPaint.color = when { !visuallyEnabled -> if (night) Glass.OFF_NIGHT else Glass.OFF; emphasis -> accent; else -> Glass.text(night) }
            val x = if (!leads) dp(12f) else if (emphasis) dp(36f) else dp(42f)
            canvas.drawText(it, x, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
        // The S Pen button's momentary tool gets a solid accent ring; the tool it suspends, a dashed one.
        if (override || (active && suspended)) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = accent
            paint.pathEffect = if (suspended) suspendedDash else null
            box.inset(dp(1f), dp(1f)); canvas.drawRoundRect(box, dp(Glass.CHIP - 1f), dp(Glass.CHIP - 1f), paint); paint.pathEffect = null
        }
    }

    /**
     * A tool standing in the palette like a tool in a holder, tip toward the page (P186: as on the Glass board, a
     * 28 dp wide tool in a 76 dp slot, sunk 16 dp until it is chosen and rises).
     */
    private fun drawUpright(canvas: Canvas, tool: ToolArt.Tool, tip: Float) {
        val marker = highlighterSample
        val short = dp(48f); val long = dp(slotLength)
        val count = canvas.save()
        canvas.clipRect(box)
        // Work in an upright frame (tip up) centred on the button, then turn it toward the page.
        canvas.translate(width / 2f, height / 2f); canvas.rotate(tip); canvas.translate(-short / 2, -long / 2)
        canvas.clipRect(0f, 0f, short, long)
        if (!visuallyEnabled) canvas.saveLayerAlpha(0f, 0f, short, long, 90)
        val scale = dp(28f) / ToolArt.WIDTH
        canvas.translate((short - dp(28f)) / 2, if (active) dp(2f) else dp(18f))
        canvas.scale(scale, scale)
        // The marker's barrel is a shade darker than the ink it lays down; a pen's is its ink; the lasso's loop is accent.
        val ink = when {
            uprightTool == ToolArt.Tool.LASSO -> Glass.accent(night)
            uprightTool == ToolArt.Tool.PAINT -> sample?.color?.toInt() ?: Glass.secondary(night)
            uprightTool != null -> Glass.secondary(night)
            marker != null -> ToolArt.mix(marker.color.toInt(), Tokens.INK, .1f)
            else -> sample?.color?.toInt() ?: Glass.secondary(night)
        }
        ToolArt.draw(canvas, tool, ink, ToolArt.PALETTE_LENGTH, fine = marker != null && marker.width <= ToolArt.FINE_HIGHLIGHTER)
        canvas.restoreToCount(count)
    }

    /** The pen preset is drawn as its own ink on a small tile of desk, so dark ink stays visible on dark chrome. */
    private fun drawSample(canvas: Canvas, style: PenStyle, highlighter: Boolean = false) {
        val size = dp(32f)
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2)
        paint.style = Paint.Style.FILL; paint.color = Tokens.DESK
        paint.alpha = if (visuallyEnabled) 255 else 90
        box.set(0f, 0f, size, size); canvas.drawRoundRect(box, dp(8f), dp(8f), paint)
        canvas.scale(size / 32f, size / 32f)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        if (highlighter) {
            // Faint print under a single translucent swash makes the marker distinct from a pen.
            paint.color = Tokens.INK; paint.alpha = if (visuallyEnabled) 52 else 18
            paint.strokeWidth = 1.2f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(8f, 13f, 24f, 13f, paint)
            canvas.drawLine(8f, 19f, 24f, 19f, paint)
            paint.color = style.inkColor.toInt()
            paint.alpha = (((style.inkColor ushr 24) and 255).toInt() * dev.ownednotes.core.InkStroke.HIGHLIGHTER_OPACITY *
                (if (visuallyEnabled) 1f else 90f / 255)).toInt()
            paint.strokeWidth = 5f + 7f * (ln(style.width / .5f) / ln(100f)).coerceIn(0f, 1f)
            paint.strokeCap = Paint.Cap.BUTT
            canvas.drawPath(samplePath, paint)
            paint.alpha = 255; paint.strokeCap = Paint.Cap.ROUND
            canvas.restore(); return
        }
        paint.color = style.color.toInt(); paint.alpha = if (visuallyEnabled) 255 else 90
        // Width 0.5–50 page units shown on a log scale between 1.2 and 6 units.
        val width = 1.2f + 4.8f * (ln(style.width / .5f) / ln(100f)).coerceIn(0f, 1f)
        paint.strokeWidth = width
        when (style.kind) {
            PenKind.BALLPOINT -> canvas.drawPath(samplePath, paint)
            PenKind.CALLIGRAPHY -> CalligraphyBrush.draw(canvas, samplePath, paint, width * 1.4f)
            PenKind.FOUNTAIN, PenKind.FOUNTAIN_SOFT -> FountainInk.drawSample(canvas, samplePath, paint, width, requireNotNull(style.kind.nib))
            // Grain at about one pixel per grain unit, whatever the 32-unit tile is scaled to.
            PenKind.PENCIL -> {
                paint.shader = PencilGrain.scaled(paint.color, 2f * 32f / size)
                canvas.drawPath(samplePath, paint); paint.shader = null
            }
            // Round caps add half the width to each end of a dash, so the off length includes the full width
            // and a 3-unit gap stays visible even for the thickest pen.
            PenKind.DASHED -> {
                paint.pathEffect = DashPathEffect(floatArrayOf(1.5f + width * .3f, 3f + width), 0f)
                canvas.drawPath(samplePath, paint); paint.pathEffect = null
            }
            // The pressure pen swells in the middle of the squiggle and tapers at both ends, like a real stroke.
            PenKind.PRESSURE -> {
                val length = sampleMeasure.length
                for (i in 0 until PRESSURE_STEPS) {
                    val t = (i + .5f) / PRESSURE_STEPS
                    paint.strokeWidth = width * (.4f + .8f * sin(PI * t).toFloat())
                    sampleSegment.rewind()
                    sampleMeasure.getSegment(length * i / PRESSURE_STEPS, length * (i + 1) / PRESSURE_STEPS, sampleSegment, true)
                    canvas.drawPath(sampleSegment, paint)
                }
            }
        }
        paint.alpha = 255
        canvas.restore()
    }
    /** For tests: the stroke width the sample is drawn at, in the 32-unit tile, at fraction [t] along the squiggle. */
    internal fun sampleWidthAt(style: PenStyle, t: Float): Float {
        val width = 1.2f + 4.8f * (ln(style.width / .5f) / ln(100f)).coerceIn(0f, 1f)
        return if (style.kind == PenKind.PRESSURE) width * (.4f + .8f * sin(PI * t).toFloat()) else width
    }
    private val sampleMeasure by lazy { PathMeasure(samplePath, false) }
    private val sampleSegment = Path()
    /** P614 (board 8): a palette slot is 58 dp (76 before), so the docked bar is 18 dp thinner and the page gets the room. */
    companion object { private const val PRESSURE_STEPS = 16; const val SLOT = 58f }
}

/**
 * P186: a colour dot in the Glass palette, as on the board: a 32 dp swatch in a 48 dp target. The chosen colour wears a
 * pane-coloured gap and an accent ring. With [color] null it is the "All colours" ring, a colour wheel with a pane centre.
 */
/**
 * P560: where to hold the toolbar: six dots on a soft chip, in a handle as tall as the palette (or as wide, on a rail), so a
 * finger or the pen finds it at once. Dragging it to an edge moves the toolbar; a tap offers the positions.
 */
class PaletteGrip(context: Context, private val night: Boolean) : View(context) {
    /** On a rail the handle lies across the palette, so the dots and chip turn with it. */
    var across = false; set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = RectF()
    init {
        isClickable = true; isFocusable = true
        tooltipText = "Hold and drag to move the toolbar"
        foreground = ripple(night, dp(Glass.CHIP))
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val long = dp(44f); val short = dp(26f); val cx = width / 2f; val cy = height / 2f
        val w = if (across) long else short; val h = if (across) short else long
        chip.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val accent = Glass.accent(night)
        paint.color = if (isPressed) accent else Glass.text(night)
        paint.alpha = if (isPressed) 60 else if (night) 34 else 20
        canvas.drawRoundRect(chip, dp(9f), dp(9f), paint)
        paint.color = if (isPressed) accent else Glass.secondary(night); paint.alpha = 255
        val gap = dp(9f); val r = dp(2.2f)
        for (along in -1..1) for (side in intArrayOf(-1, 1)) {
            val first = along * gap; val second = side * gap / 2
            canvas.drawCircle(cx + if (across) first else second, cy + if (across) second else first, r, paint)
        }
    }
}

class InkDot(context: Context, color: Int?, label: String) : View(context) {
    var color = color; set(value) { if (field != value) { field = value; invalidate() } }
    var chosen = false; set(value) { if (field != value) { field = value; invalidate() } }
    var night = false; set(value) { if (field != value) { field = value; invalidate() } }
    // B30: as on [ChromeButton], the guards held while a stroke is written and saved must not flash the dots.
    private var heldEnabledAppearance: Boolean? = null
    /** Whether the dot is drawn enabled, for tests. */
    internal val visuallyEnabled get() = heldEnabledAppearance ?: isEnabled
    internal fun holdEnabledAppearance(hold: Boolean) {
        val next = if (hold) heldEnabledAppearance ?: isEnabled else null
        if (next != heldEnabledAppearance) { heldEnabledAppearance = next; invalidate() }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheel = intArrayOf(0xffff3b30.toInt(), 0xffff9500.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(), 0xff00c7be.toInt(),
        0xff0a84ff.toInt(), 0xff5e5ce6.toInt(), 0xffbf5af2.toInt(), 0xffff3b30.toInt())
    init {
        contentDescription = label; tooltipText = label
        isClickable = true; isFocusable = true
        foreground = ripple(false, dp(24f))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(dpi(42f), widthMeasureSpec), resolveSize(dpi(48f), heightMeasureSpec))
    override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); invalidate() } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f; val y = height / 2f; val r = dp(16f)
        val dim = !visuallyEnabled
        if (dim) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 90)
        paint.style = Paint.Style.FILL; paint.shader = null
        if (chosen) {
            paint.color = Glass.accent(night); canvas.drawCircle(x, y, r + dp(5f), paint)
            paint.color = if (night) 0xff2c303a.toInt() else -1; canvas.drawCircle(x, y, r + dp(2.5f), paint)
        }
        val ink = color
        if (ink == null) {
            paint.shader = android.graphics.SweepGradient(x, y, wheel, null).also { s ->
                s.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, x, y) })
            }
            canvas.drawCircle(x, y, r, paint); paint.shader = null
            paint.color = if (night) 0xff2c303a.toInt() else -1; canvas.drawCircle(x, y, dp(10f), paint)
        } else {
            paint.color = ink or 0xff000000.toInt(); canvas.drawCircle(x, y, r, paint)
            // A hairline keeps white and near-desk colours visible on the pane.
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) 0x33ffffff else 0x1f1d2433
            canvas.drawCircle(x, y, r - dp(.5f), paint)
        }
        if (dim) canvas.restore()
    }
}
