package dev.ownednotes.app

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.ownednotes.core.InkStroke
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A small paper card in the pen and highlighter menus (P051) showing how the current tool writes: a sample stroke
 * built with the same androidx.ink brush, colour and width as the page, filled the way the page fills ink.
 * The highlighter sample crosses a line of grey "words" so its tint over print is visible too.
 */
class StrokePreview(context: Context, val highlighter: Boolean) : View(context) {
    var style: PenStyle = PenStyle()
        set(value) {
            if (field == value) return
            field = value; ink = null
            contentDescription = describe(value)
            invalidate()
        }
    private var ink: Path? = null
    /** P618: the sample's points on the card and their times, for the fountain pen's shading and pools. */
    private val xs = FloatArray(64); private val ys = FloatArray(64); private val ts = LongArray(64)
    private val card = RectF()
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe3e7ee.toInt(); style = Paint.Style.STROKE }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.GRAPHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { contentDescription = describe(style) }

    private fun describe(style: PenStyle) =
        "${if (highlighter) "Highlighter" else style.kind.label + " pen"} preview, width ${"%.1f".format(java.util.Locale.ROOT, style.width).removeSuffix(".0")}"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(context.dpi(80f), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { ink = null }

    /** Page units to pixels: about one dp per unit, shrunk so the widest tips still fit the card. */
    internal fun scale(): Float = min(context.dp(1f), (height - context.dp(20f)) * .6f / style.width).coerceAtLeast(.05f)

    override fun onDraw(canvas: Canvas) {
        val inset = context.dp(1f)
        card.set(inset, context.dp(4f), width - inset, height - context.dp(4f))
        val radius = context.dp(18f)
        canvas.drawRoundRect(card, radius, radius, paper)
        edge.strokeWidth = context.dp(1f)
        canvas.drawRoundRect(card, radius, radius, edge)
        if (highlighter) drawWords(canvas)
        val path = ink ?: build().also { ink = it }
        fill.color = style.inkColor.toInt()
        if (highlighter) { fill.alpha = (255 * InkStroke.HIGHLIGHTER_OPACITY).roundToInt(); fill.blendMode = BlendMode.MULTIPLY } else fill.blendMode = null
        // The page's graphite at the card's own scale, so the tooth is the size it will be on the page at 100%.
        fill.shader = if (!highlighter && style.kind == PenKind.PENCIL) PencilGrain.scaled(fill.color, scale()) else null
        if (!highlighter && style.kind.nib != null) FountainInk.fill(canvas, path, style.inkColor.toInt(), style.width * scale(), xs, ys, ts, xs.size, fill)
        else canvas.drawPath(path, fill)
    }

    /** Short rounded bars with word-like gaps, centred where the highlighter sample runs. */
    private fun drawWords(canvas: Canvas) {
        val h = context.dp(7f); val y = card.centerY() - h / 2
        var x = card.left + context.dp(24f); var i = 0
        val lengths = floatArrayOf(34f, 52f, 22f, 61f, 40f, 28f, 47f)
        words.alpha = 150
        while (true) {
            val w = context.dp(lengths[i++ % lengths.size])
            if (x + w > card.right - context.dp(24f)) break
            canvas.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, words)
            x += w + context.dp(9f)
        }
    }

    private fun brush(): Brush = if (highlighter)
        Brush.createWithColorIntArgb(HighlighterBrush.family, style.color.toInt(), style.width, .1f)
    else Brush.createWithColorIntArgb(PenSmoothing.family(when (style.kind) {
        PenKind.PRESSURE -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        PenKind.BALLPOINT -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        PenKind.DASHED -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
        PenKind.CALLIGRAPHY -> CalligraphyBrush.family
        PenKind.PENCIL -> PencilWetRenderer.family
        PenKind.FOUNTAIN -> FountainInk.everyday
        PenKind.FOUNTAIN_SOFT -> FountainInk.soft
    }, style.smoothing), style.inkColor.toInt(), style.width, .1f)

    /**
     * The sample in page units, then mapped onto the card. A pen writes one long S, as on the Glass board (P187), whose
     * pressure rises and falls (so the pressure pen visibly swells); a highlighter makes one straight pass across the words.
     */
    private fun build(): Path {
        val result = Path()
        if (width <= 0 || height <= 0) return result
        val scale = scale()
        val margin = context.dp(24f) + style.width * scale / 2
        val length = (width - 2 * margin) / scale
        if (length <= 0f) return result
        val amplitude = if (highlighter) 0f else ((height - context.dp(20f)) / scale - style.width) / 2 * .55f
        val batch = MutableStrokeInputBatch()
        val count = 64
        for (i in 0 until count) {
            val t = i / (count - 1f)
            val pressure = if (highlighter) .7f else .2f + .75f * sin(PI * t).toFloat()
            batch.add(InputToolType.STYLUS, t * length, -amplitude * sin(2 * PI * t).toFloat(), i * 10L, pressure = pressure)
            xs[i] = margin + t * length * scale; ys[i] = height / 2f - amplitude * sin(2 * PI * t).toFloat() * scale; ts[i] = i * 10L
        }
        val stroke = Stroke(brush(), batch)
        val shape = stroke.shape
        val point = MutableVec()
        for (group in 0 until shape.getRenderGroupCount()) for (outline in 0 until shape.getOutlineCount(group)) {
            val vertices = shape.getOutlineVertexCount(group, outline)
            if (vertices < 3) continue
            val polygon = Path()
            for (index in 0 until vertices) {
                shape.populateOutlinePosition(group, outline, index, point)
                if (index == 0) polygon.moveTo(point.x, point.y) else polygon.lineTo(point.x, point.y)
            }
            polygon.close()
            // Outlines of one stroke are unioned, as on the page, so a translucent highlighter never doubles up.
            result.op(polygon, Path.Op.UNION)
        }
        result.transform(Matrix().apply { setScale(scale, scale); postTranslate(margin, height / 2f) })
        return result
    }
}
