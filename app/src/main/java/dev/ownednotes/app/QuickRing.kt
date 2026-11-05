package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P183 (G8), redrawn in P199 as on the Glass board "Dark with quick ring": the finger quick ring is a glass disc of
 * slices around a centre disc. Each slice is an icon over a short label, and the tool in use fills with the accent. The
 * centre is Paste (B35, P198), always there and dimmed when nothing can be pasted. A finger may slide across the ring and
 * lift on a slice. The ring only reports taps; EditorFrame runs each through the tool bar's own buttons.
 */
internal class QuickRing(context: Context, private val night: Boolean, val slices: List<Slice>, val paste: Slice, private val onMiss: () -> Unit = {}) : FrameLayout(context) {
    val side = dpi(DIAMETER)
    private val centre = side / 2f
    private val step = 360f / slices.size
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedge = Path()
    private var pressed: View? = null
    private var missed = false

    init {
        contentDescription = "Quick tools"
        background = Glass.pane(context, night, DIAMETER / 2f); elevation = dp(16f)
        setWillNotDraw(false)
        // A label is as wide as its slice allows at the middle of the band, at most the board's 76 dp.
        val chord = 2 * MID * sin(Math.toRadians(step / 2.0)).toFloat() - 6f
        val w = dpi(minOf(76f, chord)); val h = dpi(SLICE_HEIGHT)
        slices.forEachIndexed { index, slice ->
            val angle = Math.toRadians(-90.0 + step * index)
            addView(slice, LayoutParams(w, h).apply {
                leftMargin = (centre + dp(MID) * cos(angle)).roundToInt() - w / 2
                topMargin = (centre + dp(MID) * sin(angle)).roundToInt() - h / 2
            })
        }
        val core = dpi(CORE * 2)
        addView(paste, LayoutParams(core, core).apply { leftMargin = (centre - core / 2f).roundToInt(); topMargin = leftMargin })
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
    }

    override fun onDraw(canvas: Canvas) {
        val outer = dp(OUTER); val inner = dp(INNER); val accent = Glass.accent(night)
        val hint = (accent and 0xffffff) or (if (night) 0x52000000 else 0x30000000)
        slices.forEachIndexed { index, slice ->
            val start = -90f - step / 2 + step * index + GAP; val sweep = step - GAP * 2
            wedge.reset()
            wedge.arcTo(centre - outer, centre - outer, centre + outer, centre + outer, start, sweep, true)
            wedge.arcTo(centre - inner, centre - inner, centre + inner, centre + inner, start + sweep, -sweep, false)
            wedge.close()
            paint.style = Paint.Style.FILL
            paint.color = when { slice.on -> accent; slice.isPressed -> hint; night -> 0x0affffff; else -> 0x0a1d2433 }
            canvas.drawPath(wedge, paint)
            if (!slice.on) { paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) 0x14ffffff else 0x141d2433; canvas.drawPath(wedge, paint) }
        }
        // The board's centre disc, darker than the ring.
        paint.style = Paint.Style.FILL
        paint.color = when { paste.isPressed -> hint; night -> 0xd914161c.toInt(); else -> Glass.THIN }
        canvas.drawCircle(centre, centre, dp(CORE), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) 0x1affffff else Glass.RULE
        canvas.drawCircle(centre, centre, dp(CORE), paint)
    }

    /** The slice or centre under a point, by angle and distance rather than by the label boxes. */
    private fun at(x: Float, y: Float): View? {
        val r = hypot(x - centre, y - centre)
        if (r <= dp(CORE)) return paste
        if (r < dp(INNER) || r > dp(OUTER) + dp(4f)) return null
        val degrees = (Math.toDegrees(atan2((y - centre).toDouble(), (x - centre).toDouble())) + 90 + step / 2 + 720) % 360
        return slices[(degrees / step).toInt().coerceIn(0, slices.size - 1)]
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = at(event.x, event.y)?.takeIf { it.isEnabled }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { missed = hypot(event.x - centre, event.y - centre) > dp(OUTER) + dp(4f); press(target) }
            MotionEvent.ACTION_MOVE -> press(target)
            MotionEvent.ACTION_UP -> { val hit = pressed; press(null); if (hit != null) hit.performClick() else if (missed) onMiss() }
            MotionEvent.ACTION_CANCEL -> press(null)
        }
        return true
    }
    private fun press(view: View?) {
        if (view === pressed) return
        pressed?.isPressed = false; pressed = view; view?.isPressed = true; invalidate()
    }

    /**
     * One slice, or the centre: an icon over a short label, and an optional second line. [icon] draws in a 24-unit box;
     * its paint already carries the slice's ink (white on the accent, dimmed when disabled).
     */
    internal class Slice(context: Context, private val night: Boolean, val label: String, description: String, val on: Boolean = false,
        enabled: Boolean = true, val detail: String? = null, private val icon: (Canvas, Paint) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); typeface = Tokens.SANS_SEMIBOLD; textAlign = Paint.Align.CENTER }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); typeface = Tokens.SANS_MEDIUM; textAlign = Paint.Align.CENTER }
        init {
            contentDescription = description + if (on) ", selected" else ""; tooltipText = description
            isClickable = true; isFocusable = true; isEnabled = enabled; isSelected = on
        }
        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); (parent as? View)?.invalidate() }
        override fun onDraw(canvas: Canvas) {
            val off = if (night) Glass.OFF_NIGHT else Glass.OFF
            val ink = when { !isEnabled -> off; on -> Color.WHITE; else -> Glass.text(night) }
            val size = dp(ICON); val line = dp(15f)
            val top = (height - (size + dp(4f) + line * if (detail != null) 2 else 1)) / 2
            val count = canvas.save(); canvas.translate((width - size) / 2, top); canvas.scale(size / 24f, size / 24f)
            if (!isEnabled) canvas.saveLayerAlpha(-6f, -6f, 30f, 30f, 110)
            paint.reset(); paint.isAntiAlias = true; paint.color = ink; icon(canvas, paint)
            canvas.restoreToCount(count)
            val room = width - dp(4f)
            text.color = ink
            var y = top + size + dp(4f) + line - dp(3.5f)
            canvas.drawText(TextUtils.ellipsize(label, text, room, TextUtils.TruncateAt.END).toString(), width / 2f, y, text)
            detail?.let {
                y += line; small.color = when { !isEnabled -> off; on -> Color.WHITE; else -> Glass.secondary(night) }
                canvas.drawText(TextUtils.ellipsize(it, small, room, TextUtils.TruncateAt.END).toString(), width / 2f, y, small)
            }
        }
    }

    companion object {
        const val DIAMETER = 320f
        const val OUTER = 152f
        const val INNER = 62f
        const val CORE = 56f
        const val MID = (OUTER + INNER) / 2
        const val GAP = 1.2f
        const val ICON = 28f
        const val SLICE_HEIGHT = 62f

        /** A palette tool lying as if held, tip down to the left, in a slice's 24-unit icon box. */
        fun tool(canvas: Canvas, tool: ToolArt.Tool, ink: Int) {
            canvas.save()
            canvas.translate(12f, 12f); canvas.rotate(225f); canvas.scale(.3f, .3f); canvas.translate(-ToolArt.WIDTH / 2, -ToolArt.LENGTH / 2)
            ToolArt.draw(canvas, tool, ink); canvas.restore()
        }
        /**
         * The board's swatch: the colour in use with a soft ring of itself. A colour that would vanish into the ring (dark
         * ink at night, white by day) gets a hairline.
         */
        fun swatch(canvas: Canvas, color: Long, night: Boolean, paint: Paint) {
            val c = color.toInt() or 0xff000000.toInt(); val light = Color.luminance(c)
            paint.style = Paint.Style.FILL; paint.color = c; canvas.drawCircle(12f, 12f, 8f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.alpha = 128; canvas.drawCircle(12f, 12f, 10.8f, paint)
            if (if (night) light < .2f else light > .8f) {
                paint.strokeWidth = 1f; paint.color = if (night) 0x8cffffff.toInt() else 0x591d2433
                canvas.drawCircle(12f, 12f, 8f, paint); canvas.drawCircle(12f, 12f, 10.8f, paint)
            }
        }
    }
}
