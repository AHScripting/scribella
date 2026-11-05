package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.media.ExifInterface
import android.view.MotionEvent
import android.view.View
import dev.ownednotes.core.ImageAsset
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** F105/F106 (P083): camera scans straightened by four corners, then given a document look. */
object ScanProcessing {
    enum class Look(val label: String) { ORIGINAL("Original"), DOCUMENT("Document"), GREY("Greyscale"), BLACK_WHITE("Black & white") }

    /** The pending camera capture; process-lifetime like the other insert targets. */
    var target: ImageInsertTarget? = null
    /**
     * P561: a page kept for this scan (P201 step 10), in order, until Add or Cancel: the finished JPEG and small preview,
     * and the photo behind it with its corners and look, so it can be fitted again as often as needed.
     */
    class KeptPage(var bytes: ByteArray, var preview: Bitmap, val photo: File, var corners: List<PointF>, var look: Look)
    val pages = mutableListOf<KeptPage>()
    /** P247: this scan takes its pages with the in-app camera (the scan screen's live view) rather than the system camera. */
    var live = false
    /** Tests turn the in-app camera off to drive the system camera. */
    var liveAllowed = true
    /** The look new pages of this scan start with. */
    var look = Look.DOCUMENT
    /** Where the live camera last took a page, so coming back from an edit does not take the same page again. */
    var lastTaken: FloatArray? = null
    /** Drops one kept page and its photo. */
    fun remove(page: KeptPage) { pages.remove(page); page.photo.delete(); page.preview.recycle() }
    /** Ends a scan: no target, no kept pages and none of their photos. */
    fun clear() {
        target = null; live = false; look = Look.DOCUMENT; lastTaken = null
        pages.forEach { it.photo.delete(); it.preview.recycle() }; pages.clear()
    }
    fun captureFile(context: Context) = File(context.cacheDir, "scans/capture.jpg").also { it.parentFile!!.mkdirs() }
    /** A new file for a kept page's photo. The first of a scan also clears any left behind when the app was last stopped. */
    fun photoFile(context: Context): File {
        val folder = File(context.cacheDir, "scans").also { it.mkdirs() }
        if (pages.isEmpty()) folder.listFiles { f -> f.name.startsWith("page-") || f.name == "last.jpg" }?.forEach { it.delete() }
        return File(folder, "page-${java.util.UUID.randomUUID()}.jpg")
    }
    /** Moves a capture to its own file, so the next capture does not overwrite it. */
    fun keepPhoto(context: Context, capture: File): File {
        val photo = photoFile(context)
        if (!capture.renameTo(photo)) { capture.copyTo(photo, overwrite = true); capture.delete() }
        return photo
    }

    /** Upright display/working copy, at most [longest] pixels on the longest edge. */
    fun decode(file: File, longest: Int = 2400): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The camera did not return a photo" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1; while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > longest * 2) inSampleSize *= 2
        }
        val raw = BitmapFactory.decodeFile(file.path, options) ?: error("The photo could not be decoded")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val scale = min(1f, longest.toFloat() / max(raw.width, raw.height))
        val m = Matrix().apply {
            when (orientation) {
                2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f)
                5 -> { postRotate(90f); postScale(-1f, 1f) }; 6 -> postRotate(90f)
                7 -> { postRotate(-90f); postScale(-1f, 1f) }; 8 -> postRotate(-90f)
            }
            postScale(scale, scale)
        }
        if (m.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    fun whole(bitmap: Bitmap) = listOf(PointF(0f, 0f), PointF(bitmap.width.toFloat(), 0f),
        PointF(bitmap.width.toFloat(), bitmap.height.toFloat()), PointF(0f, bitmap.height.toFloat()))
    fun inset(bitmap: Bitmap, f: Float = .06f) = whole(bitmap).map { p ->
        PointF(p.x + (bitmap.width / 2f - p.x) * f * 2, p.y + (bitmap.height / 2f - p.y) * f * 2) }

    /**
     * Page corners (top-left, top-right, bottom-right, bottom-left) of the largest bright region, or null when
     * the photo has no clear page against a darker background.
     */
    fun detect(bitmap: Bitmap): List<PointF>? {
        val k = min(1f, 200f / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * k).roundToInt()); val h = max(1, (bitmap.height * k).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== bitmap) small.recycle()
        val c = detectLuma(IntArray(pixels.size) { luminance(pixels[it]) }, w, h) ?: return null
        val corners = (0 until 4).map { PointF(c[it * 2] / k, c[it * 2 + 1] / k) }
        return corners.takeIf { area(it) > bitmap.width * bitmap.height * .1f && convex(it) }
    }

    /**
     * P247: the corners of the largest bright region in a [w]×[h] brightness grid, as x, y pairs (top-left, top-right,
     * bottom-right, bottom-left) in grid units, or null. Shared by photos ([detect]) and the live camera's small frames.
     */
    fun detectLuma(lum: IntArray, w: Int, h: Int): FloatArray? {
        val threshold = otsu(lum)
        val bright = BooleanArray(lum.size) { lum[it] > threshold }
        // Largest 4-connected bright region, so a reflection elsewhere does not pull the corners.
        val label = IntArray(lum.size) { -1 }; var best = -1; var bestSize = 0
        val queue = IntArray(lum.size)
        for (start in lum.indices) {
            if (!bright[start] || label[start] >= 0) continue
            var head = 0; var tail = 0; queue[tail++] = start; label[start] = start
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                fun visit(j: Int) { if (bright[j] && label[j] < 0) { label[j] = start; queue[tail++] = j } }
                if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1); if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
            }
            if (tail > bestSize) { bestSize = tail; best = start }
        }
        if (best < 0 || bestSize < lum.size * .08f || bestSize > lum.size * .97f) return null
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        for (i in lum.indices) if (label[i] == best) {
            val x = i % w; val y = i / w
            if (x + y < minSum) { minSum = x + y; tl = i }; if (x + y > maxSum) { maxSum = x + y; br = i }
            if (x - y > maxDiff) { maxDiff = x - y; tr = i }; if (x - y < minDiff) { minDiff = x - y; bl = i }
        }
        return FloatArray(8).also { out -> listOf(tl, tr, br, bl).forEachIndexed { n, i -> out[n * 2] = i % w + .5f; out[n * 2 + 1] = i / w + .5f } }
    }

    /** Straightens the quad into an upright page and applies [look]. The caller owns the returned bitmap. */
    fun straighten(source: Bitmap, corners: List<PointF>, look: Look, maxEdge: Int = 2400): Bitmap {
        require(corners.size == 4 && convex(corners) && area(corners) >= 64f) { "Move the corners around the page" }
        val (a, b, c, d) = corners
        val width = (dist(a, b) + dist(d, c)) / 2; val height = (dist(a, d) + dist(b, c)) / 2
        val k = min(1f, maxEdge / max(width, height))
        val ow = max(1, (width * k).roundToInt()); val oh = max(1, (height * k).roundToInt())
        val m = Matrix()
        check(m.setPolyToPoly(floatArrayOf(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y), 0,
            floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat()), 0, 4)) { "The corners do not form a page" }
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        enhance(out, look)
        return out
    }

    /** Flattens shadows by dividing by an estimate of the paper's brightness, then applies the look. */
    fun enhance(bitmap: Bitmap, look: Look) {
        if (look == Look.ORIGINAL) return
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val paper = paper(pixels, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val p = pixels[i]
            val gain = 255f / paper(x, y)
            pixels[i] = when (look) {
                Look.DOCUMENT -> Color.rgb(curve(Color.red(p) * gain), curve(Color.green(p) * gain), curve(Color.blue(p) * gain))
                Look.GREY -> curve(luminance(p) * gain).let { Color.rgb(it, it, it) }
                else -> (luminance(p) * gain).let { v -> if (v < 150f) 0 else if (v > 200f) 255 else ((v - 150f) * 255f / 50f).roundToInt() }.let { Color.rgb(it, it, it) }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
    /** Paper brightness per cell (a bright percentile, so ink does not count), smoothed and interpolated. */
    private fun paper(pixels: IntArray, w: Int, h: Int): (Int, Int) -> Float {
        val cell = max(8, max(w, h) / 32); val cw = (w + cell - 1) / cell; val ch = (h + cell - 1) / cell
        val grid = FloatArray(cw * ch)
        val histogram = IntArray(64)
        for (gy in 0 until ch) for (gx in 0 until cw) {
            histogram.fill(0); var n = 0
            var y = gy * cell
            while (y < min(h, (gy + 1) * cell)) {
                var x = gx * cell
                while (x < min(w, (gx + 1) * cell)) { histogram[luminance(pixels[y * w + x]) shr 2]++; n++; x += 2 }
                y += 2
            }
            var seen = 0; var bin = 63
            while (bin > 0 && seen + histogram[bin] < n * .1f) { seen += histogram[bin]; bin-- }
            grid[gy * cw + gx] = (bin * 4 + 2f).coerceAtLeast(64f)
        }
        val smooth = FloatArray(grid.size) { i ->
            val gx = i % cw; val gy = i / cw; var sum = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) { val x = gx + dx; val y = gy + dy; if (x in 0 until cw && y in 0 until ch) { sum += grid[y * cw + x]; n++ } }
            sum / n
        }
        return { x, y ->
            val fx = ((x + .5f) / cell - .5f).coerceIn(0f, cw - 1f); val fy = ((y + .5f) / cell - .5f).coerceIn(0f, ch - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(cw - 1, x0 + 1); val y1 = min(ch - 1, y0 + 1)
            val tx = fx - x0; val ty = fy - y0
            val top = smooth[y0 * cw + x0] * (1 - tx) + smooth[y0 * cw + x1] * tx
            val bottom = smooth[y1 * cw + x0] * (1 - tx) + smooth[y1 * cw + x1] * tx
            top * (1 - ty) + bottom * ty
        }
    }
    private fun curve(v: Float) = ((v - 22f) * 255f / 218f).roundToInt().coerceIn(0, 255)

    /** JPEG bytes within the notebook's image limit. */
    fun encode(bitmap: Bitmap): ByteArray {
        for (quality in listOf(90, 80, 70, 55)) {
            val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()
            if (bytes.size <= ImageAsset.MAX_BYTES) return bytes
        }
        error("The scan is too large")
    }

    fun luminance(p: Int) = (Color.red(p) * 54 + Color.green(p) * 183 + Color.blue(p) * 19) shr 8
    private fun otsu(lum: IntArray): Int {
        val histogram = IntArray(256).also { h -> lum.forEach { h[it]++ } }
        val total = lum.size; val sum = (0..255).sumOf { it.toLong() * histogram[it] }
        var sumB = 0L; var weightB = 0; var best = 0.0; var threshold = 127
        for (t in 0..255) {
            weightB += histogram[t]; if (weightB == 0) continue
            val weightF = total - weightB; if (weightF == 0) break
            sumB += t.toLong() * histogram[t]
            val meanB = sumB.toDouble() / weightB; val meanF = (sum - sumB).toDouble() / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }
    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
    fun area(c: List<PointF>) = kotlin.math.abs(c.indices.sumOf { i -> val a = c[i]; val b = c[(i + 1) % c.size]; (a.x * b.y - b.x * a.y).toDouble() }).toFloat() / 2
    /** The same outline with the corners handed on by one: [straighten] then gives the page a quarter turn clockwise. */
    fun turned(c: List<PointF>) = c.indices.map { i -> PointF(c[(i + c.size - 1) % c.size].x, c[(i + c.size - 1) % c.size].y) }
    /** The whole photo, with its corners in the same turn as [current]: the order that moves them least. */
    fun wholeLike(bitmap: Bitmap, current: List<PointF>): List<PointF> {
        var best = whole(bitmap)
        var way = best
        repeat(3) { way = turned(way); if (reach(way, current) < reach(best, current)) best = way }
        return best
    }
    private fun reach(a: List<PointF>, b: List<PointF>) = a.indices.sumOf { dist(a[it], b[it]).toDouble() }
    fun convex(c: List<PointF>): Boolean {
        val signs = c.indices.map { i ->
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
        }
        return signs.all { it > 0 } || signs.all { it < 0 }
    }
}

/** The photo with four draggable corner handles; corners are kept in photo pixels. */
class ScanCropView(context: Context, private val photo: Bitmap, corners: List<PointF>) : View(context) {
    var onCornersChanged: (() -> Unit)? = null
    val corners: MutableList<PointF> = corners.map { PointF(it.x, it.y) }.toMutableList()
    private val density = resources.displayMetrics.density
    private val fit = Matrix(); private val inverse = Matrix()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // P201 step 10: the Scan board's outline, a tinted page, a 3.5 dp edge and white handles ringed in the accent.
    private val dim = Paint().apply { color = 0x73000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x240a84ff }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Glass.ACCENT; strokeWidth = 3.5f * density; strokeJoin = Paint.Join.ROUND }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Glass.ACCENT; strokeWidth = 4f * density }
    private val path = Path()
    private val grip = RectF()
    private val topLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Tokens.SANS_SEMIBOLD; letterSpacing = .06f }
    /** The corner held (0..3), the edge held (4..7, an edge joins corners n-4 and n-3), or -1. */
    private var dragging = -1
    private var lastTouch = floatArrayOf(0f, 0f)
    /** From the touch to the corner held, in photo pixels, so taking hold of a corner does not make it jump. */
    private val grabbed = PointF()
    private val touch = PointF()

    // P561: the magnifier shown while a corner or edge is held, because the finger or pen hides that spot.
    private val lens = Matrix()
    private val lensClip = Path()
    private val lensBack = Paint().apply { color = 0xff1a1715.toInt() }
    private val lensEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Glass.ACCENT; strokeJoin = Paint.Join.ROUND }
    private val lensRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f * density }
    private val lensRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000; strokeWidth = 1f * density }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val heldRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * density }
    private var lensWay = 0
    /** True for the corner or edge held. */
    val holding get() = dragging >= 0
    /** The spot the magnifier enlarges, in this view: the corner held, or the nearest point of the edge held. */
    internal fun lensTarget(): FloatArray? = when {
        dragging < 0 -> null
        dragging < 4 -> cornerInView(dragging)
        else -> nearest(touch.x, touch.y, cornerInView(dragging - 4), cornerInView((dragging - 3) % 4))
    }
    /** The magnifier's centre, as x, y, then its radius; null when nothing is held. */
    internal fun lensCircle(): FloatArray? = lensTarget()?.let { placeLens(it[0], it[1]) }

    init { contentDescription = "Scan corners and edges" }
    fun setCorners(points: List<PointF>) { points.forEachIndexed { i, p -> corners[i].set(p) }; invalidate(); onCornersChanged?.invoke() }
    /** P567: the first corner becomes the page's top left, so handing the corners on by one turns the page a quarter clockwise. */
    fun turnClockwise() = setCorners(ScanProcessing.turned(corners))
    /** Where the page's top edge is once the page has been turned, so a narrow screen with no After pane shows it too. */
    private fun drawTop(canvas: Canvas) {
        val a = cornerInView(0); val b = cornerInView(1)
        if (b[0] - a[0] > abs(b[1] - a[1])) return
        val angle = atan2(b[1] - a[1], b[0] - a[0])
        canvas.save(); canvas.translate((a[0] + b[0]) / 2, (a[1] + b[1]) / 2); canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        val middle = 34f * density
        topLabel.textSize = 12f * density
        val half = topLabel.measureText("TOP") / 2 + 9f * density
        grip.set(-half, middle - 11f * density, half, middle + 11f * density)
        handle.color = Glass.ACCENT; canvas.drawRoundRect(grip, 11f * density, 11f * density, handle)
        canvas.drawText("TOP", 0f, middle + 4.2f * density, topLabel)
        canvas.restore()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 20f * density
        fit.setRectToRect(RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()), RectF(pad, pad, w - pad, h - pad), Matrix.ScaleToFit.CENTER)
        fit.invert(inverse)
    }
    fun cornerInView(i: Int) = floatArrayOf(corners[i].x, corners[i].y).also { fit.mapPoints(it) }
    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(photo, fit, photoPaint)
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        val shown = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()).also { fit.mapRect(it) }
        path.addRect(shown, Path.Direction.CW)
        (0 until 4).forEach { i -> val p = cornerInView(i); if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }
        path.close()
        canvas.drawPath(path, dim)
        path.reset(); (0 until 4).forEach { i -> val p = cornerInView(i); if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }; path.close()
        canvas.drawPath(path, fill); canvas.drawPath(path, edge)
        (0 until 4).forEach { i ->
            val a = cornerInView(i); val b = cornerInView((i + 1) % 4)
            val length = hypot(b[0] - a[0], b[1] - a[1])
            // A grip only where it clears both corner handles.
            if (length < 96f * density) return@forEach
            val long = (if (i + 4 == dragging) 18f else 15f) * density; val thick = 5f * density
            canvas.save(); canvas.translate((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            canvas.rotate(Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())).toFloat())
            grip.set(-long, -thick, long, thick)
            handle.color = Color.WHITE; canvas.drawRoundRect(grip, thick, thick, handle)
            canvas.restore()
        }
        (0 until 4).forEach { i ->
            val p = cornerInView(i)
            // The corner held is only a ring, so the white disc does not hide the spot being placed.
            if (i == dragging) { canvas.drawCircle(p[0], p[1], 18f * density, heldRing); return@forEach }
            val r = 15f * density
            handle.color = Color.WHITE; canvas.drawCircle(p[0], p[1], r, handle)
            canvas.drawCircle(p[0], p[1], r - 2f * density, ring)
        }
        drawTop(canvas)
        lensTarget()?.let { drawLens(canvas, it[0], it[1]) }
    }
    /** The photo around ([tx], [ty]) enlarged in a circle, with the page outline and a cross on the exact spot. */
    private fun drawLens(canvas: Canvas, tx: Float, ty: Float) {
        val (cx, cy, r) = placeLens(tx, ty)
        lens.set(fit); lens.postTranslate(-tx, -ty); lens.postScale(LENS_ZOOM, LENS_ZOOM); lens.postTranslate(cx, cy)
        canvas.save()
        lensClip.rewind(); lensClip.addCircle(cx, cy, r, Path.Direction.CW); canvas.clipPath(lensClip)
        canvas.drawCircle(cx, cy, r, lensBack)
        canvas.drawBitmap(photo, lens, photoPaint)
        // The outline, in the same enlargement but no thicker than it would be.
        lensClip.rewind()
        val quad = FloatArray(8) { i -> if (i % 2 == 0) corners[i / 2].x else corners[i / 2].y }.also { lens.mapPoints(it) }
        lensClip.moveTo(quad[0], quad[1]); (1 until 4).forEach { lensClip.lineTo(quad[it * 2], quad[it * 2 + 1]) }; lensClip.close()
        lensEdge.strokeWidth = 2f * density; canvas.drawPath(lensClip, lensEdge)
        // A cross with an open middle, in white on a darker line so it shows on both paper and desk.
        val near = 7f * density; val far = 20f * density
        for ((w, colour) in listOf(3.5f to 0x99000000.toInt(), 1.5f to Color.WHITE)) {
            cross.strokeWidth = w * density; cross.color = colour
            canvas.drawLine(cx - far, cy, cx - near, cy, cross); canvas.drawLine(cx + near, cy, cx + far, cy, cross)
            canvas.drawLine(cx, cy - far, cx, cy - near, cross); canvas.drawLine(cx, cy + near, cx, cy + far, cross)
        }
        canvas.restore()
        canvas.drawCircle(cx, cy, r + 2f * density, lensRim); canvas.drawCircle(cx, cy, r, lensRing)
    }
    /**
     * Where the magnifier goes, as x, y, radius: above the spot, clear of the hand and slid inwards near a side, or to the
     * left, right or below where there is no room. It keeps its side while that still fits, so it does not hop about.
     */
    private fun placeLens(tx: Float, ty: Float): FloatArray {
        val r = LENS_RADIUS * density; val reach = r + LENS_GAP * density
        val inX = tx.coerceIn(r, max(r, width - r)); val inY = ty.coerceIn(r, max(r, height - r))
        val ways = listOf(inX to ty - reach, tx - reach to inY, tx + reach to inY, inX to ty + reach)
        fun fits(p: Pair<Float, Float>) = p.first - r >= 0 && p.first + r <= width && p.second - r >= 0 && p.second + r <= height
        if (!fits(ways[lensWay])) lensWay = ways.indexOfFirst(::fits).coerceAtLeast(0)
        val (x, y) = ways[lensWay]
        return floatArrayOf(x.coerceIn(r, max(r, width - r)), y.coerceIn(r, max(r, height - r)), r)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = (0 until 4).minByOrNull { i -> cornerInView(i).let { hypot(it[0] - event.x, it[1] - event.y) } }
                    ?.takeIf { i -> cornerInView(i).let { hypot(it[0] - event.x, it[1] - event.y) } <= 48f * density } ?: edgeAt(event.x, event.y)
                if (dragging < 0) return false
                lastTouch = floatArrayOf(event.x, event.y).also { inverse.mapPoints(it) }
                if (dragging < 4) grabbed.set(corners[dragging].x - lastTouch[0], corners[dragging].y - lastTouch[1])
                touch.set(event.x, event.y); lensWay = 0
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val p = floatArrayOf(event.x, event.y).also { inverse.mapPoints(it) }
                if (dragging < 4) corners[dragging].set((p[0] + grabbed.x).coerceIn(0f, photo.width.toFloat()), (p[1] + grabbed.y).coerceIn(0f, photo.height.toFloat()))
                else moveEdge(dragging - 4, p[0] - lastTouch[0], p[1] - lastTouch[1])
                lastTouch = p; touch.set(event.x, event.y); onCornersChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        invalidate(); return true
    }
    /** The edge nearest this touch within 24 dp, as 4..7, or -1. */
    private fun edgeAt(x: Float, y: Float): Int {
        val (edge, away) = (0 until 4).map { i -> i to toSegment(x, y, cornerInView(i), cornerInView((i + 1) % 4)) }.minBy { it.second }
        return if (away <= 24f * density) edge + 4 else -1
    }
    private fun toSegment(x: Float, y: Float, a: FloatArray, b: FloatArray) = nearest(x, y, a, b).let { hypot(it[0] - x, it[1] - y) }
    private fun nearest(x: Float, y: Float, a: FloatArray, b: FloatArray): FloatArray {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val square = dx * dx + dy * dy
        val t = if (square == 0f) 0f else (((x - a[0]) * dx + (y - a[1]) * dy) / square).coerceIn(0f, 1f)
        return floatArrayOf(a[0] + dx * t, a[1] + dy * t)
    }
    /** Both corners of an edge move together, stopping as one at the photo's sides so the edge keeps its slope. */
    private fun moveEdge(edge: Int, dx: Float, dy: Float) {
        val a = corners[edge]; val b = corners[(edge + 1) % 4]
        val moveX = dx.coerceIn(-min(a.x, b.x), photo.width - max(a.x, b.x))
        val moveY = dy.coerceIn(-min(a.y, b.y), photo.height - max(a.y, b.y))
        a.offset(moveX, moveY); b.offset(moveX, moveY)
    }
    private companion object {
        /** How much larger than the photo on screen the magnifier shows it. */
        const val LENS_ZOOM = 3f
        const val LENS_RADIUS = 68f
        /** From the spot to the magnifier's rim: clear of a fingertip or the pen's tip. */
        const val LENS_GAP = 48f
    }
}
