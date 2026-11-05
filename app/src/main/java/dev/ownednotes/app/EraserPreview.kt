package dev.ownednotes.app

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.ownednotes.core.InkStroke
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A small paper card in the eraser menu (P053): a pen line and a highlight in the user's current colours, crossed by
 * the eraser circle at its current size. Whole-stroke mode fades the strokes the circle would remove; area mode cuts
 * a gap where it sits. Only the kinds the All / Pen / Highlights choice accepts are affected.
 */
class EraserPreview(context: Context) : View(context) {
    var mode = InkCanvas.EraserMode.STROKE; set(value) { if (field != value) { field = value; changed() } }
    var target = InkCanvas.EraserTarget.ALL; set(value) { if (field != value) { field = value; changed() } }
    /** Eraser radius in page units (1–100), shown at about one dp per unit and shrunk to fit the card. */
    var radius = 12f; set(value) { if (field != value) { field = value; changed() } }
    var penColor = 0xff182636; set(value) { if (field != value) { field = value; changed() } }
    var markColor = 0xfff9d43a; set(value) { if (field != value) { field = value; changed() } }

    private val card = RectF()
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.RULE; style = Paint.Style.STROKE }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.MULTIPLY }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Tokens.GRAPHITE }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 30, 100, 220) }
    private val wave = Path()
    private val hole = Path()

    init { contentDescription = describe() }

    private fun changed() { contentDescription = describe(); invalidate() }
    private fun describe() = "${if (mode == InkCanvas.EraserMode.AREA) "Area" else "Whole-stroke"} eraser preview, " +
        "${(radius * 2).roundToInt()} units across, erases ${when (target) {
            InkCanvas.EraserTarget.ALL -> "all ink"; InkCanvas.EraserTarget.PEN -> "pen ink"; InkCanvas.EraserTarget.HIGHLIGHTER -> "highlights" }}"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(context.dpi(76f), heightMeasureSpec))
    }

    private val centreY get() = height / 2f
    private val centreX get() = width / 2f
    /** The circle's on-screen radius: one dp per page unit until it would leave the card. */
    internal fun circleRadius(): Float = min(radius * context.dp(1f), height / 2f - context.dp(10f)).coerceAtLeast(context.dp(1.5f))
    /** The pen sample: a wave through the card centre with a 160 dp period. */
    internal fun penY(x: Float): Float = centreY - context.dp(18f) * sin(2 * PI * (x - centreX) / context.dp(160f)).toFloat()
    internal fun penStart() = context.dp(24f)

    override fun onDraw(canvas: Canvas) {
        val inset = context.dp(1f); val radiusCard = context.dp(10f)
        card.set(inset, context.dp(4f), width - inset, height - context.dp(4f))
        canvas.drawRoundRect(card, radiusCard, radiusCard, paper)
        edge.strokeWidth = context.dp(1f); canvas.drawRoundRect(card, radiusCard, radiusCard, edge)

        val r = circleRadius()
        hole.rewind(); hole.addCircle(centreX, centreY, r, Path.Direction.CW)
        // Highlighter first so the pen line lies on top of it, as on a page where the highlight was added later
        // and multiplies over it either way.
        drawAffected(canvas, target.acceptsHighlights) {
            mark.color = markColor.toInt()
            mark.alpha = (255 * InkStroke.HIGHLIGHTER_OPACITY * it).roundToInt()
            val h = context.dp(7f)
            canvas.drawRect(penStart() - context.dp(4f), centreY - h, width - penStart() + context.dp(4f), centreY + h, mark)
        }
        drawAffected(canvas, target.acceptsPen) {
            pen.color = penColor.toInt(); pen.alpha = (255 * it).roundToInt(); pen.strokeWidth = context.dp(3f)
            wave.rewind()
            var x = penStart(); wave.moveTo(x, penY(x))
            while (x < width - penStart()) { x = min(x + context.dp(2f), width - penStart()); wave.lineTo(x, penY(x)) }
            canvas.drawPath(wave, pen)
        }
        canvas.drawPath(hole, spot)
        ring.strokeWidth = context.dp(1.25f)
        ring.pathEffect = if (mode == InkCanvas.EraserMode.AREA) DashPathEffect(floatArrayOf(context.dp(4f), context.dp(3f)), 0f) else null
        canvas.drawPath(hole, ring)
    }

    /** Draws one sample stroke as the eraser would leave it: faded if whole-stroke erasing would remove it, cut by the circle in area mode. */
    private inline fun drawAffected(canvas: Canvas, affected: Boolean, draw: (opacity: Float) -> Unit) {
        when {
            !affected -> draw(1f)
            mode == InkCanvas.EraserMode.STROKE -> draw(FADED)
            else -> { canvas.save(); canvas.clipOutPath(hole); draw(1f); canvas.restore() }
        }
    }

    private val InkCanvas.EraserTarget.acceptsPen get() = this != InkCanvas.EraserTarget.HIGHLIGHTER
    private val InkCanvas.EraserTarget.acceptsHighlights get() = this != InkCanvas.EraserTarget.PEN

    companion object { const val FADED = .22f }
}
