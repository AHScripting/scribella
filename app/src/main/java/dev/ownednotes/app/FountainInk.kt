package dev.ownednotes.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * P618 (board 11): a fountain pen, studied from real ones. Ink flows from the nib as fast as the hand allows, so a slow
 * stroke lays a wider, darker line and a quick one a narrower, paler one. Pulling the nib down spreads its tines and
 * widens the line; pushing it up keeps it fine. Where the nib rests, lands or lifts, ink pools darker.
 *
 * Two nibs: [EVERYDAY], a firm nib where speed and direction matter and pressure only a little; [SOFT], a flexible
 * nib whose downstrokes swell with pressure while upstrokes stay hairlines. The width comes from the brush, so the
 * saved outlines carry it; the shading and pools are only how this app fills those outlines ([fill]), as with pencil.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
object FountainInk {
    enum class Nib { EVERYDAY, SOFT }

    /** Speed in brush widths per second where the nib is at its slowest and fastest look. */
    const val SLOW = 15f
    const val FAST = 110f
    /** How much paler the fastest ink is (mixed toward white), and how much darker a pool is (mixed toward black). */
    const val SHADING = .3f
    const val POOL = .25f
    /** A pause this long with the nib still leaves a pool. */
    const val REST_MS = 120L

    private fun linear(source: BrushBehavior.Source, from: Float, to: Float, low: Float, high: Float, millis: Long) = BrushBehavior.Builder()
        .setSource(source).setTarget(BrushBehavior.Target.SIZE_MULTIPLIER)
        .setSourceValueRangeStart(from).setSourceValueRangeEnd(to).setTargetModifierRangeStart(low).setTargetModifierRangeEnd(high)
        .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP).setResponseCurve(EasingFunction.Predefined.LINEAR)
        .setResponseTimeMillis(millis).build()

    /** Slow is wide, fast is narrow; behaviours on one target multiply. */
    private val speed get() = linear(BrushBehavior.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, SLOW, FAST, 1.22f, .72f, 40)

    /** The soft nib: .4 straight up, .64 across, and down .64 + (.25 + 1.1 × pressure), halved into the target's 0..2. */
    private val softShape: BrushBehavior get() {
        fun source(s: BrushBehavior.Source, from: Float, to: Float) = BrushBehavior.SourceNode(s, from, to, BrushBehavior.OutOfRange.CLAMP)
        fun direction(from: Float, to: Float) = BrushBehavior.DampingNode(BrushBehavior.DampingSource.TIME_IN_SECONDS, .025f,
            source(BrushBehavior.Source.NORMALIZED_DIRECTION_Y, from, to))
        fun c(v: Float) = BrushBehavior.ConstantNode(v)
        fun sum(a: BrushBehavior.ValueNode, b: BrushBehavior.ValueNode) = BrushBehavior.BinaryOpNode(BrushBehavior.BinaryOp.SUM, a, b)
        fun product(a: BrushBehavior.ValueNode, b: BrushBehavior.ValueNode) = BrushBehavior.BinaryOpNode(BrushBehavior.BinaryOp.PRODUCT, a, b)
        val across = direction(-1f, 0f)
        val down = direction(0f, 1f)
        val pressure = source(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f)
        val half = sum(sum(c(.2f), product(c(.12f), across)), product(down, sum(c(.125f), product(c(.55f), pressure))))
        return BrushBehavior(listOf(BrushBehavior.TargetNode(BrushBehavior.Target.SIZE_MULTIPLIER, 0f, 2f, half)))
    }

    private fun family(id: String, behaviours: List<BrushBehavior>) = BrushFamily.Builder()
        .setCoat(BrushTip.Builder().setScaleX(1f).setScaleY(1f).setCornerRounding(1f)
            .setBehaviors(behaviours + StockBrushes.predictionFadeOutBehavior).build(), BrushPaint())
        .setClientBrushFamilyId(id)
        .build()

    val everyday: BrushFamily = family("dev.ownednotes.fountain", listOf(speed,
        linear(BrushBehavior.Source.NORMALIZED_DIRECTION_Y, -1f, 1f, .7f, 1.02f, 25),
        linear(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f, .92f, 1.08f, 20)))
    val soft: BrushFamily = family("dev.ownednotes.fountain-soft", listOf(speed, softShape))

    fun isFountain(id: String?) = id == everyday.clientBrushFamilyId || id == soft.clientBrushFamilyId

    /** The width a nib gives a stroke heading [down] (−1 straight up … 1 straight down) at [pressure], before speed. */
    fun shape(nib: Nib, down: Float, pressure: Float): Float = when (nib) {
        Nib.EVERYDAY -> (.86f + .16f * down) * (.92f + .16f * pressure)
        Nib.SOFT -> if (down > 0) .64f + down * (.25f + 1.1f * pressure) else .64f + .24f * down
    }

    private fun mix(color: Int, toward: Int, t: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 255) * (1 - t) + ((toward shr shift) and 255) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** Thumbnails and exports draw off the main thread, so each thread has its own paints. */
    private class Tools {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val run = Path()
        val bounds = RectF()
    }
    private val tools = ThreadLocal.withInitial { Tools() }

    /**
     * P664: a saved stroke's shading and pools, painted once into a small picture of the stroke's area. The stroke then
     * draws as one filled outline with that picture as its paint, the same work for the GPU as a ballpoint stroke.
     * Clipping every stroke to its outline and painting its shading on every frame made a full page lag.
     */
    private class Shaded(val stroke: dev.ownednotes.core.InkStroke, val shader: BitmapShader, val scale: Float, val bytes: Int) {
        var used = 0L
    }
    /** Keyed by the outline Path itself (Path keeps Object identity), most recently drawn last. */
    private val shaded = LinkedHashMap<Path, Shaded>(64, .75f, true)
    private var shadedBytes = 0L
    /** Pictures not drawn for [IDLE_MS] make room past [BUDGET]; when the page itself needs more, the rest draw clipped. */
    private const val BUDGET = 64L * 1024 * 1024
    private const val IDLE_MS = 1500L
    /** The sharpest picture (pixels per page unit) and its longest side. */
    private const val MOST = 8f
    private const val SIDE = 2048
    private val probe = ThreadLocal.withInitial { FloatArray(4) }
    private val probeBounds = ThreadLocal.withInitial { RectF() }
    /**
     * A dense page has hundreds of pictures to paint the first time it shows. Each frame paints them for at most
     * [FRAME_NS]; the rest draw clipped once more and [takePending] asks the page for another frame.
     */
    private const val FRAME_NS = 12_000_000L
    private var windowStart = 0L
    private var windowSpent = 0L
    @Volatile private var pending = false
    /** Whether some strokes still wait for their picture; the finished-ink view then draws once more. */
    fun takePending(): Boolean = pending.also { pending = false }
    /** Tests turn the cache off to compare with the clipped drawing. */
    @Volatile internal var cacheShading = true
    /** Tests: draw through the cache on any canvas, not only a hardware one. */
    @Volatile internal var cacheOnSoftware = false

    /** [fill] for a saved stroke, over [outline] (its visible shape). */
    fun fill(canvas: Canvas, outline: Path, stroke: dev.ownednotes.core.InkStroke, paint: Paint, opacity: Float = 1f) {
        // Thumbnails and exports draw fresh outlines on bitmaps, where a cache would never be hit again.
        if (cacheShading && (canvas.isHardwareAccelerated || cacheOnSoftware)) {
            // Off screen: nothing to draw, and its picture can make room for strokes that show.
            @Suppress("DEPRECATION") if (canvas.quickReject(outline, Canvas.EdgeType.AA)) return
        }
        // P677: a stroke under [PLAIN_PX] pixels wide (small writing, thumbnails, a zoomed-out page) shows no shading or
        // pools a reader could see, so it draws as plain ink without a picture or a clip.
        if (stroke.width * pixels(canvas) < PLAIN_PX) { plain(canvas, outline, stroke.color.toInt(), paint, opacity); return }
        if (cacheShading && (canvas.isHardwareAccelerated || cacheOnSoftware)) shadedFor(outline, stroke, onScreen(canvas))?.let {
            drawShaded(canvas, outline, stroke, it, paint, opacity); return
        }
        val n = stroke.samples.size
        val x = FloatArray(n); val y = FloatArray(n); val t = LongArray(n)
        stroke.samples.forEachIndexed { i, s -> x[i] = s.x; y[i] = s.y; t[i] = s.timeMs }
        fill(canvas, outline, stroke.color.toInt(), stroke.width, x, y, t, n, paint, opacity)
    }

    /**
     * Fills [outline] with fountain ink: the ink [color], paler along quick parts of the stroke, with darker pools where
     * it began, ended and rested. [x], [y], [t] are the stroke's [count] samples (page units, milliseconds) and [width]
     * its brush width. The shading never leaves the outline, and translucent ink keeps one even opacity. [opacity] is
     * the stroke's layer opacity.
     */
    fun fill(canvas: Canvas, outline: Path, color: Int, width: Float, x: FloatArray, y: FloatArray, t: LongArray, count: Int, paint: Paint, opacity: Float = 1f) {
        if (width * pixels(canvas) < PLAIN_PX) { plain(canvas, outline, color, paint, opacity); return }
        val tools = tools.get(); val line = tools.line; val dot = tools.dot; val run = tools.run; val bounds = tools.bounds
        val alpha = (Color.alpha(color) * opacity).roundToInt().coerceIn(1, 255)
        val ink = color or 0xff000000.toInt()
        outline.computeBounds(bounds, true)
        val layered = alpha < 255
        if (layered) canvas.saveLayerAlpha(bounds, alpha) else canvas.save()
        paint.shader = null; paint.blendMode = null; paint.style = Paint.Style.FILL; paint.color = ink
        canvas.drawPath(outline, paint)
        if (count > 1 && width > 0f) {
            canvas.clipPath(outline)
            shade(canvas, ink, width, x, y, t, count, line, dot, run)
        }
        canvas.restore()
    }

    /** The shading runs and pools of [fill], unclipped. */
    private fun shade(canvas: Canvas, ink: Int, width: Float, x: FloatArray, y: FloatArray, t: LongArray, count: Int, line: Paint, dot: Paint, run: Path) {
        // Shading: speed (widths per second, smoothed as a hand is) sets how pale each stretch is; stretches of
        // the same shade are drawn as one line wider than the nib can get, which the outline then trims.
        line.strokeWidth = width * 2.1f
        var speed = 0f; var level = -1
        run.rewind()
        for (i in 1 until count) {
            val dt = max(t[i] - t[i - 1], 1L)
            val now = hypot(x[i] - x[i - 1], y[i] - y[i - 1]) / width / (dt / 1000f)
            speed = if (i == 1) now else speed * .55f + now * .45f
            val fast = ((speed - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
            val next = (fast * 8).roundToInt()
            if (next != level) {
                if (level > 0 && !run.isEmpty) { line.color = mix(ink, Color.WHITE, SHADING * level / 8f); canvas.drawPath(run, line) }
                run.rewind(); run.moveTo(x[i - 1], y[i - 1]); level = next
            }
            run.lineTo(x[i], y[i])
        }
        if (level > 0 && !run.isEmpty) { line.color = mix(ink, Color.WHITE, SHADING * level / 8f); canvas.drawPath(run, line) }
        // Pools: where the nib landed, lifted and rested.
        dot.color = mix(ink, Color.BLACK, POOL)
        val radius = width * .58f
        canvas.drawCircle(x[0], y[0], radius, dot)
        canvas.drawCircle(x[count - 1], y[count - 1], radius, dot)
        var anchor = 0; var pooled = false
        for (i in 1 until count - 1) {
            if (hypot(x[i] - x[anchor], y[i] - y[anchor]) > width * .35f) { anchor = i; pooled = false; continue }
            if (!pooled && anchor > 0 && t[i] - t[anchor] >= REST_MS) { canvas.drawCircle(x[anchor], y[anchor], radius * 1.05f, dot); pooled = true }
        }
    }

    /** Below this many pixels wide a fountain stroke draws as plain ink (P677). */
    const val PLAIN_PX = 2f
    /** Tests: draw every stroke shaded, however small. */
    @Volatile internal var plainSmall = true

    /** Pixels per page unit on [canvas], as is. */
    @Suppress("DEPRECATION")
    private fun pixels(canvas: Canvas): Float {
        if (!plainSmall) return Float.MAX_VALUE
        val v = probe.get()!!; v[0] = 1f; v[1] = 0f; v[2] = 0f; v[3] = 1f
        canvas.matrix.mapVectors(v)
        val scale = kotlin.math.sqrt(minOf(v[0] * v[0] + v[1] * v[1], v[2] * v[2] + v[3] * v[3]))
        return if (scale.isFinite() && scale > 0f) scale else Float.MAX_VALUE
    }

    /** One plain fill of [outline] in [color] at the stroke's opacity. */
    private fun plain(canvas: Canvas, outline: Path, color: Int, paint: Paint, opacity: Float) {
        paint.shader = null; paint.blendMode = null; paint.style = Paint.Style.FILL
        paint.color = color or 0xff000000.toInt(); paint.alpha = (Color.alpha(color) * opacity).roundToInt().coerceIn(1, 255)
        canvas.drawPath(outline, paint)
    }

    /** Screen pixels per page unit on [canvas], rounded up to a step of a quarter so small zooms reuse the picture. */
    @Suppress("DEPRECATION")
    private fun onScreen(canvas: Canvas): Float {
        val v = probe.get()!!; v[0] = 0f; v[1] = 0f; v[2] = 1f; v[3] = 1f
        canvas.matrix.mapVectors(v)
        val scale = kotlin.math.sqrt(maxOf(v[0] * v[0] + v[1] * v[1], v[2] * v[2] + v[3] * v[3]) / 2f)
        return if (!scale.isFinite() || scale <= 0f) 2f else (kotlin.math.ceil(scale * 4f) / 4f).coerceIn(1f, MOST)
    }

    private fun shadedFor(outline: Path, stroke: dev.ownednotes.core.InkStroke, scale: Float): Shaded? {
        val now = android.os.SystemClock.uptimeMillis()
        val held: Shaded?
        synchronized(shaded) {
            held = shaded[outline]?.takeIf { it.stroke === stroke }
            if (held != null && held.scale >= scale) { held.used = now; return held }
        }
        // Room first, so a full budget never paints a picture only to throw it away; then a softer picture is better than none.
        val bounds = probeBounds.get()!!; outline.computeBounds(bounds, true)
        val side = minOf(scale, SIDE / (bounds.width() + 2f), SIDE / (bounds.height() + 2f))
        val bytes = ((bounds.width() + 2f) * side + 1f).toLong() * ((bounds.height() + 2f) * side + 1f).toLong() * 4
        synchronized(shaded) {
            val idle = shaded.values.iterator()
            var free = BUDGET - shadedBytes + (held?.bytes ?: 0)
            while (free < bytes && idle.hasNext()) {
                val old = idle.next(); if (old === held) continue
                if (now - old.used < IDLE_MS) break
                shadedBytes -= old.bytes; free += old.bytes; idle.remove()
            }
            if (free < bytes) { held?.used = now; return held }
        }
        val start = System.nanoTime()
        synchronized(shaded) {
            if (start - windowStart > 16_000_000L) { windowStart = start; windowSpent = 0L }
            if (windowSpent > FRAME_NS) { pending = true; return held }
        }
        val built = build(outline, stroke, scale)
        synchronized(shaded) { windowSpent += System.nanoTime() - start }
        if (built == null) return held
        synchronized(shaded) {
            shaded.remove(outline)?.let { shadedBytes -= it.bytes }
            built.used = now; shaded[outline] = built; shadedBytes += built.bytes
        }
        return built
    }

    /** The stroke's ink and shading over its outline's bounds, at [wanted] pixels per unit; the outline trims it when drawn. */
    private fun build(outline: Path, stroke: dev.ownednotes.core.InkStroke, wanted: Float): Shaded? {
        val count = stroke.samples.size; val width = stroke.width
        val bounds = RectF(); outline.computeBounds(bounds, true)
        bounds.inset(-1f, -1f)
        if (bounds.isEmpty || !bounds.width().isFinite() || !bounds.height().isFinite()) return null
        val scale = minOf(wanted, SIDE / bounds.width(), SIDE / bounds.height())
        val w = maxOf(1, kotlin.math.ceil(bounds.width() * scale).toInt()); val h = maxOf(1, kotlin.math.ceil(bounds.height() * scale).toInt())
        val ink = stroke.color.toInt() or 0xff000000.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(ink)
        if (count > 1 && width > 0f) {
            val x = FloatArray(count); val y = FloatArray(count); val t = LongArray(count)
            stroke.samples.forEachIndexed { i, s -> x[i] = s.x; y[i] = s.y; t[i] = s.timeMs }
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale); canvas.translate(-bounds.left, -bounds.top)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            shade(canvas, ink, width, x, y, t, count, line, Paint(Paint.ANTI_ALIAS_FLAG), Path())
        }
        bitmap.prepareToDraw()
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply { setScale(1 / scale, 1 / scale); postTranslate(bounds.left, bounds.top) })
        return Shaded(stroke, shader, wanted, w * h * 4)
    }

    private fun drawShaded(canvas: Canvas, outline: Path, stroke: dev.ownednotes.core.InkStroke, shaded: Shaded, paint: Paint, opacity: Float) {
        val alpha = (Color.alpha(stroke.color.toInt()) * opacity).roundToInt().coerceIn(1, 255)
        // One draw, so translucent ink keeps one even opacity without a layer.
        paint.blendMode = null; paint.style = Paint.Style.FILL; paint.color = Color.BLACK; paint.alpha = alpha
        val filter = paint.isFilterBitmap; paint.isFilterBitmap = true
        paint.shader = shaded.shader
        canvas.drawPath(outline, paint)
        paint.shader = null; paint.isFilterBitmap = filter
    }

    /**
     * The pen menus' sample: [path] drawn in short pieces whose width follows the nib's direction (down wide, up
     * fine) at an even hand, with pools at both ends.
     */
    fun drawSample(canvas: Canvas, path: Path, paint: Paint, width: Float, nib: Nib, steps: Int = 28) {
        val measure = PathMeasure(path, false); val length = measure.length
        if (length <= 0f) return
        val segment = Path(); val tan = FloatArray(2); val pos = FloatArray(2)
        val cap = paint.strokeCap; val style = paint.style
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until steps) {
            measure.getPosTan(length * (i + .5f) / steps, null, tan)
            paint.strokeWidth = width * shape(nib, tan[1], .55f)
            segment.rewind()
            measure.getSegment(length * i / steps, length * (i + 1) / steps + .5f, segment, true)
            canvas.drawPath(segment, paint)
        }
        val color = paint.color
        paint.style = Paint.Style.FILL; paint.color = mix(color, Color.BLACK, POOL) and 0xffffff or (color and 0xff000000.toInt())
        measure.getPosTan(0f, pos, null); canvas.drawCircle(pos[0], pos[1], width * .55f, paint)
        measure.getPosTan(length, pos, null); canvas.drawCircle(pos[0], pos[1], width * .55f, paint)
        paint.color = color; paint.style = style; paint.strokeCap = cap
    }
}
