package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A small paper card in the lasso menu (P054): three short ink strokes, one inside the selection outline, one crossing
 * it and one outside. The outline takes the chosen shape and P049's dashed accent look; the strokes the chosen rule
 * would pick turn accent blue with a soft halo, so "Touching" and "Fully enclosed" read at a glance.
 */
class LassoPreview(context: Context) : View(context) {
    var shape = InkSelection.Shape.FREEFORM; set(value) { if (field != value) { field = value; changed() } }
    var rule = InkSelection.Rule.TOUCHING; set(value) { if (field != value) { field = value; changed() } }

    private val card = RectF()
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.RULE; style = Paint.Style.STROKE }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 30, 100, 220) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val region = Path()
    private val line = Path()

    init { contentDescription = describe() }

    private fun changed() { contentDescription = describe(); invalidate() }
    private fun describe() = "${if (shape == InkSelection.Shape.RECTANGLE) "Rectangle" else "Freeform"} lasso preview, selects ink " +
        if (rule == InkSelection.Rule.TOUCHING) "touching the outline" else "fully inside the outline"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(context.dpi(76f), heightMeasureSpec))
    }

    /** Sample strokes as fractions of the width: inside, crossing the outline's right edge, outside. */
    private val spans = listOf(.16f to .40f, .44f to .66f, .74f to .92f)
    private val centreY get() = height / 2f
    private fun waveY(index: Int, x: Float): Float {
        val (from, to) = spans[index]
        val t = (x / width - from) / (to - from)
        return centreY + context.dp(6f) * sin(3 * PI * t).toFloat() * if (index == 1) -1 else 1
    }
    /** A point on each stroke where the wave crosses the centre line; the crossing stroke's lies outside the outline. */
    internal fun probe(index: Int): PointF {
        val (from, to) = spans[index]
        return PointF(width * (from + (to - from) * (if (index == 1) 2f else 1f) / 3f), centreY)
    }
    /** Whether the chosen rule picks sample [index] (0 inside, 1 crossing, 2 outside). */
    internal fun picks(index: Int) = index == 0 || (index == 1 && rule == InkSelection.Rule.TOUCHING)
    internal fun regionRight() = width * .52f

    override fun onDraw(canvas: Canvas) {
        val inset = context.dp(1f); val corner = context.dp(10f)
        card.set(inset, context.dp(4f), width - inset, height - context.dp(4f))
        canvas.drawRoundRect(card, corner, corner, paper)
        edge.strokeWidth = context.dp(1f); canvas.drawRoundRect(card, corner, corner, edge)

        region.rewind()
        if (shape == InkSelection.Shape.RECTANGLE) {
            region.addRect(width * .08f, centreY - context.dp(24f), regionRight(), centreY + context.dp(24f), Path.Direction.CW)
        } else {
            // A hand-drawn-looking loop: an ellipse with a gentle three-lobed wobble, exact at its rightmost point.
            val cx = width * .30f; val rx = regionRight() - cx; val ry = context.dp(26f)
            for (i in 0..96) {
                val a = 2 * PI * i / 96
                val k = 1 + .05f * sin(3 * a).toFloat()
                val x = cx + rx * k * cos(a).toFloat(); val y = centreY + ry * k * sin(a).toFloat()
                if (i == 0) region.moveTo(x, y) else region.lineTo(x, y)
            }
            region.close()
        }
        canvas.drawPath(region, tint)
        outline.strokeWidth = context.dp(1.25f)
        outline.pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(4f)), 0f)
        canvas.drawPath(region, outline)

        for (index in spans.indices) {
            val (from, to) = spans[index]
            line.rewind()
            var x = width * from; line.moveTo(x, waveY(index, x))
            while (x < width * to) { x = min(x + context.dp(2f), width * to); line.lineTo(x, waveY(index, x)) }
            if (picks(index)) {
                ink.color = ACCENT; ink.alpha = 48; ink.strokeWidth = context.dp(9f); canvas.drawPath(line, ink)
                ink.alpha = 255; ink.strokeWidth = context.dp(3f); canvas.drawPath(line, ink)
            } else {
                ink.color = Tokens.INK; ink.strokeWidth = context.dp(3f); canvas.drawPath(line, ink)
            }
        }
    }

    companion object { val ACCENT = Color.rgb(30, 100, 220) }
}
