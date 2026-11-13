package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * P069: the S Pen hover cursor. While the pen hovers over the page, a small card next to the nib shows how the
 * current pen or highlighter will draw (type, colour, width) and a dot marks its real width at the current zoom.
 * For the eraser a circle shows exactly where and how big the erase area is (dashed in Area mode).
 * Drawn on the canvas overlay, so it never touches ink, pages or the notebook.
 */
class ToolCursor(private val density: Float) : Drawable() {
    enum class Kind { PEN, HIGHLIGHTER, ERASER, LASSO, PAINT }
    /** What the pen would do on contact. Sizes are page units; [scale] is view pixels per page unit. */
    data class Look(val kind: Kind, val style: PenStyle, val eraserRadius: Float, val eraserMode: InkCanvas.EraserMode, val scale: Float,
                    val paintScope: InkCanvas.PaintScope = InkCanvas.PaintScope.ALL)

    var look: Look? = null; private set
    var visible = false; private set
    var x = 0f; private set
    var y = 0f; private set

    fun show(x: Float, y: Float, look: Look) {
        this.x = x; this.y = y; this.look = look; visible = true; invalidateSelf()
    }
    fun hide() { if (visible) { visible = false; invalidateSelf() } }

    private fun dp(v: Float) = v * density
    /** Eraser circle radius in view pixels: the real erase radius at the current zoom, never smaller than 2 dp. */
    fun ringRadius(): Float = look?.let { max(it.eraserRadius * it.scale, dp(2f)) } ?: 0f
    /** Nib dot radius in view pixels: half the real stroke width at the current zoom, never smaller than 1.5 dp. */
    fun nibRadius(): Float = look?.let { max(it.style.width * it.scale / 2, dp(1.5f)) } ?: 0f
    /** P194: the paint brush's reach ring in view pixels; the brush reaches the same distance on screen at any zoom. */
    fun reachRadius(): Float = dp(InkCanvas.PAINT_REACH_DP)
    /** P194: the paint brush card's caption, saying what brushed ink will become. */
    fun paintCaption(): String = when (look?.paintScope) {
        InkCanvas.PaintScope.COLOUR -> "Changes ink to this colour"
        InkCanvas.PaintScope.WIDTH -> "Changes ink to this thickness"
        else -> "Changes ink to this style"
    }
    /** The sample card, above-right of the nib, flipped left or below near the edges of the canvas. Empty for the eraser. */
    fun badge(): RectF {
        val look = look ?: return RectF()
        if (look.kind == Kind.ERASER) return RectF()
        val paint = look.kind == Kind.PAINT
        val w = if (paint) max(dp(PAINT_W), caption.measureText(paintCaption()) + dp(20f)) else dp(BADGE_W)
        val h = if (paint) dp(PAINT_H) else dp(BADGE_H); val gap = dp(14f) + if (paint) reachRadius() else nibRadius()
        val area = bounds
        val left = if (x + gap + w <= area.right) x + gap else x - gap - w
        val top = if (y - gap - h >= area.top) y - gap - h else y + gap
        return RectF(left, top, left + w, top + h)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f); color = Tokens.GRAPHITE; typeface = Tokens.SANS }
    private val wave = Path()
    private val waveMeasure = PathMeasure()
    private val segment = Path()

    override fun draw(canvas: Canvas) {
        val look = look ?: return
        if (!visible) return
        when (look.kind) {
            Kind.ERASER -> drawEraser(canvas, look)
            Kind.LASSO -> { drawNib(canvas, LassoPreview.ACCENT, dp(2f), 255); drawCard(canvas, look) }
            Kind.PEN -> { drawNib(canvas, look.style.color.toInt(), nibRadius(), look.style.alpha); drawCard(canvas, look) }
            Kind.HIGHLIGHTER -> { drawNib(canvas, look.style.color.toInt(), nibRadius(), HIGHLIGHT_ALPHA); drawCard(canvas, look) }
            Kind.PAINT -> { drawReach(canvas, look); drawPaintCard(canvas, look) }
        }
    }

    /** P194: a dashed ring in the pen's colour around everything the brush will reach, with a dot at the nib. */
    private fun drawReach(canvas: Canvas, look: Look) {
        val r = reachRadius(); val color = look.style.color.toInt()
        paint.shader = null; paint.pathEffect = null
        paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = 28
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.alpha = 200; paint.strokeWidth = dp(3f)
        canvas.drawCircle(x, y, r, paint)
        paint.color = color; paint.alpha = 255; paint.strokeWidth = dp(1.5f)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(2f)), 0f)
        canvas.drawCircle(x, y, r, paint)
        paint.pathEffect = null
        drawNib(canvas, color, dp(1.5f), 255)
    }

    /**
     * P194: the paint brush card. Plain handwriting, an arrow, then the same handwriting as brushed ink will become
     * (only its colour or thickness when the brush changes just that), with a caption saying so.
     */
    private fun drawPaintCard(canvas: Canvas, look: Look) {
        val card = badge()
        drawCardBox(canvas, card)
        val top = card.top + dp(15f); val pad = dp(10f); val arrow = card.left + card.width() * .38f
        val plainWidth = dp(1.4f)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        buildWave(card.left + pad, arrow - dp(7f), top, dp(4f))
        paint.color = PLAIN_INK; paint.strokeWidth = plainWidth
        canvas.drawPath(wave, paint)
        // The arrow: plain ink becomes the brushed ink.
        paint.color = Tokens.GRAPHITE; paint.strokeWidth = dp(1.5f)
        canvas.drawLine(arrow - dp(3f), top, arrow + dp(5f), top, paint)
        canvas.drawLine(arrow + dp(2f), top - dp(3f), arrow + dp(5f), top, paint)
        canvas.drawLine(arrow + dp(2f), top + dp(3f), arrow + dp(5f), top, paint)
        buildWave(arrow + dp(12f), card.right - pad, top, dp(4f))
        val style = look.style
        when (look.paintScope) {
            InkCanvas.PaintScope.ALL -> drawPenWave(canvas, style, sampleWidth(look))
            InkCanvas.PaintScope.COLOUR -> { paint.color = style.color.toInt(); paint.strokeWidth = plainWidth; canvas.drawPath(wave, paint) }
            InkCanvas.PaintScope.WIDTH -> { paint.color = PLAIN_INK; paint.strokeWidth = sampleWidth(look); canvas.drawPath(wave, paint) }
        }
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawText(paintCaption(), card.left + pad, card.bottom - dp(8f), caption)
    }

    private fun drawCardBox(canvas: Canvas, card: RectF) {
        paint.pathEffect = null; paint.shader = null
        paint.style = Paint.Style.FILL; paint.color = Tokens.SURFACE
        paint.setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(70, 0, 0, 0))
        canvas.drawRoundRect(card, dp(8f), dp(8f), paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE; paint.color = Tokens.RULE; paint.strokeWidth = dp(1f)
        canvas.drawRoundRect(card, dp(8f), dp(8f), paint)
    }

    /** One full sine wave from [left] to [right] around [cy], into [wave]. */
    private fun buildWave(left: Float, right: Float, cy: Float, amp: Float) {
        wave.rewind()
        for (i in 0..WAVE_POINTS) {
            val t = i / WAVE_POINTS.toFloat()
            val px = left + (right - left) * t; val py = cy - amp * sin(2 * PI * t).toFloat()
            if (i == 0) wave.moveTo(px, py) else wave.lineTo(px, py)
        }
    }

    /** [wave] drawn the way [style]'s pen type draws, at [width] view pixels. */
    private fun drawPenWave(canvas: Canvas, style: PenStyle, width: Float) {
        paint.color = style.color.toInt()
        when (style.kind) {
            PenKind.BALLPOINT -> { paint.strokeWidth = width; canvas.drawPath(wave, paint) }
            PenKind.CALLIGRAPHY -> CalligraphyBrush.draw(canvas, wave, paint, width * 1.4f)
            PenKind.FOUNTAIN, PenKind.FOUNTAIN_SOFT -> FountainInk.drawSample(canvas, wave, paint, width, requireNotNull(style.kind.nib))
            PenKind.PENCIL -> {
                paint.strokeWidth = width; paint.shader = PencilGrain.shader(paint.color)
                canvas.drawPath(wave, paint); paint.shader = null
            }
            PenKind.DASHED -> {
                paint.strokeWidth = width
                paint.pathEffect = DashPathEffect(floatArrayOf(dp(1.5f) + width * .3f, dp(3f) + width), 0f)
                canvas.drawPath(wave, paint); paint.pathEffect = null
            }
            PenKind.PRESSURE -> {
                waveMeasure.setPath(wave, false); val length = waveMeasure.length
                for (i in 0 until PRESSURE_STEPS) {
                    val t = (i + .5f) / PRESSURE_STEPS
                    paint.strokeWidth = width * (.4f + .8f * sin(PI * t).toFloat())
                    segment.rewind()
                    waveMeasure.getSegment(length * i / PRESSURE_STEPS, length * (i + 1) / PRESSURE_STEPS, segment, true)
                    canvas.drawPath(segment, paint)
                }
            }
        }
    }

    /** A dot as wide as the real stroke, with a light halo so it reads on dark ink and dark paper. */
    private fun drawNib(canvas: Canvas, color: Int, radius: Float, alpha: Int) {
        paint.pathEffect = null; paint.shader = null
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = 190
        canvas.drawCircle(x, y, radius + dp(1.25f), paint)
        paint.color = color; paint.alpha = alpha
        canvas.drawCircle(x, y, radius, paint)
        paint.alpha = 255
    }

    private fun drawEraser(canvas: Canvas, look: Look) {
        val r = ringRadius()
        paint.shader = null; paint.pathEffect = null
        paint.style = Paint.Style.FILL; paint.color = Color.argb(20, 30, 100, 220)
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE; paint.alpha = 200; paint.strokeWidth = dp(3f)
        canvas.drawCircle(x, y, r, paint)
        paint.color = Tokens.GRAPHITE; paint.alpha = 255; paint.strokeWidth = dp(1.25f)
        if (look.eraserMode == InkCanvas.EraserMode.AREA) paint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        canvas.drawCircle(x, y, r, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, dp(1f), paint)
    }

    private fun drawCard(canvas: Canvas, look: Look) {
        val card = badge()
        drawCardBox(canvas, card)
        buildWave(card.left + dp(8f), card.right - dp(8f), card.centerY(), dp(4f))
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        when (look.kind) {
            Kind.LASSO -> {
                paint.color = LassoPreview.ACCENT; paint.strokeWidth = dp(1.5f)
                paint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
                canvas.drawOval(card.left + dp(9f), card.top + dp(5f), card.right - dp(9f), card.bottom - dp(5f), paint)
                paint.pathEffect = null
            }
            Kind.HIGHLIGHTER -> {
                paint.color = look.style.color.toInt(); paint.alpha = HIGHLIGHT_ALPHA
                paint.strokeCap = Paint.Cap.BUTT; paint.strokeWidth = sampleWidth(look)
                canvas.drawPath(wave, paint); paint.alpha = 255
            }
            Kind.PEN -> drawPenWave(canvas, look.style, sampleWidth(look))
            Kind.ERASER, Kind.PAINT -> Unit
        }
        paint.strokeCap = Paint.Cap.ROUND
    }

    /** Card stroke width: page width on a log scale, 1.2–4.4 dp for pens and 4–10 dp for highlighters, like the toolbar samples. */
    internal fun sampleWidth(look: Look): Float {
        val f = (ln(look.style.width / .5f) / ln(100f)).coerceIn(0f, 1f)
        return if (look.kind == Kind.HIGHLIGHTER) dp(4f + 6f * f) else dp(1.2f + 3.2f * f)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val BADGE_W = 44f
        const val BADGE_H = 26f
        const val PAINT_W = 132f
        const val PAINT_H = 46f
        /** The "before" handwriting on the paint brush card: plain grey ink. */
        private val PLAIN_INK = 0xff8a929c.toInt()
        private const val WAVE_POINTS = 24
        private const val PRESSURE_STEPS = 16
        private val HIGHLIGHT_ALPHA = (255 * dev.ownednotes.core.InkStroke.HIGHLIGHTER_OPACITY).toInt()
        private val attached = WeakHashMap<InkCanvas, ToolCursor>()
        private val gates = WeakHashMap<InkCanvas, () -> Boolean>()

        /** The cursor attached to [ink], for tests. */
        fun of(ink: InkCanvas): ToolCursor? = attached[ink]

        /** What the pen would do on contact, with the same precedence as the canvas: eraser, then lasso, then highlighter or pen. */
        fun lookOf(ink: InkCanvas, toolType: Int, buttons: Int): Look {
            val button = buttons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
            val kind = when {
                toolType == MotionEvent.TOOL_TYPE_ERASER -> Kind.ERASER
                button && ink.buttonAction == InkCanvas.ButtonAction.ERASER -> Kind.ERASER
                button && ink.buttonAction == InkCanvas.ButtonAction.PEN -> Kind.PEN
                ink.eraser -> Kind.ERASER
                ink.lassoEnabled -> Kind.LASSO
                ink.painter -> Kind.PAINT
                ink.highlighter -> Kind.HIGHLIGHTER
                else -> Kind.PEN
            }
            return Look(kind, if (kind == Kind.HIGHLIGHTER) ink.highlighterStyle else ink.penStyle, ink.eraserRadius, ink.eraserMode, ink.pageScale, ink.paintScope)
        }

        /** Puts the cursor on [ink]'s overlay; [blocked] hides it (read-only, busy, library or settings open). */
        fun attach(ink: InkCanvas, blocked: () -> Boolean): ToolCursor {
            val cursor = ToolCursor(ink.resources.displayMetrics.density)
            attached[ink] = cursor; gates[ink] = blocked
            cursor.setBounds(0, 0, ink.width, ink.height)
            ink.overlay.add(cursor)
            ink.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> cursor.setBounds(0, 0, v.width, v.height) }
            return cursor
        }

        /**
         * Window-level stylus contact. Some S Pen event sequences omit HOVER_EXIT before touching;
         * relying on hover alone leaves its ring/card at the old position. Keep the existing eraser
         * ring at the latest contact, hide sample cards during writing, and never move it for fingers.
         * Samsung 211–214 contacts imply the side button even when buttonState is zero.
         */
        fun contact(ink: InkCanvas, event: MotionEvent) {
            val cursor = attached[ink] ?: return
            val samsung = InkCanvas.isSamsungContact(event)
            val action = if (samsung) when (event.actionMasked) {
                211 -> MotionEvent.ACTION_DOWN
                212 -> MotionEvent.ACTION_UP
                213 -> MotionEvent.ACTION_MOVE
                else -> MotionEvent.ACTION_CANCEL
            } else event.actionMasked
            when (action) {
                MotionEvent.ACTION_CANCEL -> { cursor.hide(); return }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> Unit
                else -> return
            }
            val index = (0 until event.pointerCount).firstOrNull {
                event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            } ?: return
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && event.actionIndex == index) {
                cursor.hide(); return
            }
            val buttons = event.buttonState or if (samsung) MotionEvent.BUTTON_STYLUS_PRIMARY else 0
            val look = lookOf(ink, event.getToolType(index), buttons)
            val at = IntArray(2).also { ink.getLocationInWindow(it) }
            val x = event.getX(index) - at[0]; val y = event.getY(index) - at[1]
            val over = ink.isShown && x in 0f..ink.width.toFloat() && y in 0f..ink.height.toFloat()
            if (look.kind == Kind.ERASER && over && gates[ink]?.invoke() != true && !ink.laserEnabled && ink.pageScale > 0f)
                cursor.show(x, y, look)
            else cursor.hide()
        }

        /**
         * Follows window-level hover events (window coordinates). The canvas itself never receives hover because it is not
         * clickable, so the activity passes every generic motion event here. Shown only for a stylus over the canvas;
         * hover exit (which also precedes contact) hides it.
         */
        fun hover(ink: InkCanvas, event: MotionEvent) {
            val cursor = attached[ink] ?: return
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    val tool = event.getToolType(0)
                    val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                    val location = IntArray(2).also { ink.getLocationInWindow(it) }
                    val x = event.x - location[0]; val y = event.y - location[1]
                    val over = ink.isShown && x in 0f..ink.width.toFloat() && y in 0f..ink.height.toFloat()
                    if (stylus && over && gates[ink]?.invoke() != true && ink.pageScale > 0f) cursor.show(x, y, lookOf(ink, tool, event.buttonState))
                    else cursor.hide()
                }
                MotionEvent.ACTION_HOVER_EXIT -> cursor.hide()
            }
        }
    }
}