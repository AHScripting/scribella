package dev.ownednotes.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * P539: a small paper card that acts out a gesture with sample ink: the ink, the pen or fingers, then what happens. It
 * plays three times when it comes into view and rests on the frame that shows the gesture itself; with animations off it
 * only shows that frame. A tap plays it again. Nothing here touches a notebook.
 */
internal class GestureDemo(context: Context, private val kind: Kind, selects: Boolean = false) : View(context) {
    enum class Kind(val rest: Float) { SCRIBBLE(.55f), CIRCLE(.64f), HOLD(.64f), TWO_FINGERS(.42f) }

    /** Circle and hold only: the loop selects the ink, rather than becoming a clean shape. */
    var selects = selects; set(value) { if (field != value) { field = value; describe(); play() } }
    internal var progress = kind.rest; set(value) { field = value; invalidate() }
    internal val playing get() = player?.isRunning == true
    private var player: ValueAnimator? = null
    private var inView = false

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.RULE; style = Paint.Style.STROKE; strokeWidth = context.dp(1f) }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = context.dp(2.4f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = context.dp(12f); typeface = Tokens.SANS_MEDIUM }
    private val card = RectF()
    private val oval = RectF()
    private val ring = RectF()
    private val sample = Path()
    private val gesture = Path()
    private val traced = Path()
    private val measure = PathMeasure()
    private val tip = FloatArray(2)
    private var straightFrom = 0f
    private var straightTo = 0f

    init {
        describe(); isClickable = true
        setOnClickListener { play() }
    }

    private fun describe() {
        contentDescription = when (kind) {
            Kind.SCRIBBLE -> "Demonstration: scribbling over ink erases it"
            Kind.CIRCLE -> if (selects) "Demonstration: a loop around ink, held still, selects the ink"
                else "Demonstration: a loop around ink, held still, becomes a clean shape"
            Kind.HOLD -> "Demonstration: a wobbly line, held still, becomes a straight line"
            Kind.TWO_FINGERS -> "Demonstration: tapping with two fingers undoes the last stroke"
        } + ". Tap to play it again."
    }

    fun play() {
        player?.cancel(); player = null
        if (!Motion.enabled) { progress = kind.rest; return }
        player = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600; repeatCount = 2; interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (player === animation) { player = null; progress = kind.rest } }
            })
            start()
        }
    }
    private fun stop() { player?.cancel(); player = null; progress = kind.rest }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == inView) return
        inView = isVisible
        if (isVisible) play() else stop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(context.dpi(92f), heightMeasureSpec))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The drawing keeps its proportions on a wide settings page.
        val wide = min(w.toFloat(), context.dp(340f)); val left = (w - wide) / 2; val mid = h / 2f; val d = context.dp(1f)
        card.set(d, d * 4, w - d, h - d * 4)
        sample.rewind()
        (0..40).forEach { i ->
            val t = i / 40f; val x = left + wide * (.30f + .40f * t); val y = mid + d * 7 * sin(3 * PI * t).toFloat()
            if (i == 0) sample.moveTo(x, y) else sample.lineTo(x, y)
        }
        oval.set(left + wide * .22f, mid - d * 28, left + wide * .78f, mid + d * 28)
        straightFrom = left + wide * .2f; straightTo = left + wide * .8f
        gesture.rewind()
        when (kind) {
            Kind.SCRIBBLE -> (0..9).forEach { i ->
                val x = left + wide * (.26f + .48f * i / 9f); val y = mid + d * 19 * if (i % 2 == 0) 1 else -1
                if (i == 0) gesture.moveTo(x, y) else gesture.lineTo(x, y)
            }
            Kind.CIRCLE -> (0..60).forEach { i ->
                // Starts low on the left, so the pen stays on the card when it rests at the loop's end.
                val a = .75 * PI + 2.05 * PI * i / 60; val wobble = 1 + .035f * sin(3 * a).toFloat()
                val x = oval.centerX() + oval.width() / 2 * wobble * cos(a).toFloat(); val y = mid + oval.height() / 2 * wobble * sin(a).toFloat()
                if (i == 0) gesture.moveTo(x, y) else gesture.lineTo(x, y)
            }
            Kind.HOLD -> (0..80).forEach { i ->
                val t = i / 80f; val y = mid + d * (5 * sin(5 * PI * t) + 2 * sin(11 * PI * t)).toFloat()
                val x = straightFrom + (straightTo - straightFrom) * t
                if (i == 0) gesture.moveTo(x, y) else gesture.lineTo(x, y)
            }
            Kind.TWO_FINGERS -> Unit
        }
    }

    override fun onDraw(canvas: Canvas) {
        val corner = context.dp(10f)
        canvas.drawRoundRect(card, corner, corner, paper); canvas.drawRoundRect(card, corner, corner, edge)
        ink.pathEffect = null; ink.color = Tokens.INK; ink.alpha = 255
        when (kind) {
            Kind.SCRIBBLE -> {
                val gone = ramp(progress, .6f, .7f)
                if (gone < 1f) {
                    ink.alpha = alpha(1 - gone); canvas.drawPath(sample, ink)
                    trace(canvas, ramp(progress, TRACE, HOLD)); pen(canvas, progress < .6f)
                }
            }
            Kind.CIRCLE -> if (progress < RESULT) {
                canvas.drawPath(sample, ink)
                trace(canvas, ramp(progress, TRACE, HOLD)); pen(canvas); holdRing(canvas)
            } else if (selects) {
                ink.color = ACCENT; ink.alpha = 48; val thin = ink.strokeWidth; ink.strokeWidth = thin * 4; canvas.drawPath(sample, ink)
                ink.strokeWidth = thin; ink.alpha = 255; canvas.drawPath(sample, ink)
                ink.pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f); canvas.drawOval(oval, ink)
            } else {
                canvas.drawPath(sample, ink); canvas.drawOval(oval, ink)
            }
            Kind.HOLD -> if (progress < RESULT) {
                trace(canvas, ramp(progress, TRACE, HOLD)); pen(canvas); holdRing(canvas)
            } else canvas.drawLine(straightFrom, height / 2f, straightTo, height / 2f, ink)
            Kind.TWO_FINGERS -> {
                val gone = ramp(progress, .5f, .6f)
                if (gone < 1f) { ink.alpha = alpha(1 - gone); canvas.drawPath(sample, ink) }
                // The fingers come down, rest for a moment and lift.
                val press = min(ramp(progress, .25f, .33f), 1 - ramp(progress, .48f, .54f))
                if (press > 0f) {
                    fill.color = FINGER; fill.alpha = alpha(press * .6f)
                    val r = context.dp(12f) * (1.25f - .25f * press)
                    canvas.drawCircle(width / 2f - context.dp(17f), height / 2f, r, fill); canvas.drawCircle(width / 2f + context.dp(17f), height / 2f, r, fill)
                }
                val shown = min(ramp(progress, .58f, .64f), 1 - ramp(progress, .92f, .98f))
                if (shown > 0f) {
                    fill.color = PILL; fill.alpha = alpha(shown * .85f); label.alpha = alpha(shown)
                    val half = context.dp(28f); val tall = context.dp(14f)
                    ring.set(width / 2f - half, height / 2f - tall, width / 2f + half, height / 2f + tall)
                    canvas.drawRoundRect(ring, tall, tall, fill)
                    canvas.drawText("Undo", width / 2f, height / 2f - (label.ascent() + label.descent()) / 2, label)
                }
            }
        }
    }

    /** Draws [part] of the gesture's stroke and leaves its end in [tip]. */
    private fun trace(canvas: Canvas, part: Float) {
        measure.setPath(gesture, false)
        val length = measure.length * part
        traced.rewind(); measure.getSegment(0f, length, traced, true); measure.getPosTan(length, tip, null)
        canvas.drawPath(traced, ink)
    }
    private fun pen(canvas: Canvas, shown: Boolean = true) {
        if (!shown || progress < TRACE) return
        ink.color = PEN; ink.alpha = 255; ink.pathEffect = null
        val width = ink.strokeWidth; ink.strokeWidth = context.dp(4.5f)
        canvas.drawLine(tip[0] + context.dp(3f), tip[1] - context.dp(4f), tip[0] + context.dp(15f), tip[1] - context.dp(20f), ink)
        ink.strokeWidth = width; ink.color = Tokens.INK
    }
    /** The ring that fills while the pen is held still, as the canvas waits before acting. */
    private fun holdRing(canvas: Canvas) {
        if (progress < HOLD) return
        val r = context.dp(11f); val was = ink.strokeWidth
        ink.color = ACCENT; ink.strokeWidth = context.dp(2f)
        ring.set(tip[0] - r, tip[1] - r, tip[0] + r, tip[1] + r)
        canvas.drawArc(ring, -90f, 360f * ramp(progress, HOLD, RESULT), false, ink)
        ink.strokeWidth = was; ink.color = Tokens.INK
    }

    private fun ramp(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    private fun alpha(part: Float) = (255 * part).toInt().coerceIn(0, 255)

    private companion object {
        const val TRACE = .08f
        const val HOLD = .5f
        const val RESULT = .7f
        val ACCENT = LassoPreview.ACCENT
        val PEN = Color.rgb(112, 124, 138)
        val FINGER = Color.rgb(110, 118, 128)
        val PILL = Color.rgb(51, 51, 51)
    }
}
