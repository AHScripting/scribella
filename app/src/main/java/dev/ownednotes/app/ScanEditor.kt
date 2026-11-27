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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    /** P689 (B1): this scan takes its next pages with Google's document scanner. */
    var google = false
    /** The look new pages of this scan start with. */
    var look = Look.DOCUMENT
    /** Where the live camera last took a page, so coming back from an edit does not take the same page again. */
    var lastTaken: FloatArray? = null
    /** Drops one kept page and its photo. */
    fun remove(page: KeptPage) { pages.remove(page); page.photo.delete(); page.preview.recycle() }
    /** Ends a scan: no target, no kept pages and none of their photos. */
    fun clear() {
        target = null; live = false; google = false; look = Look.DOCUMENT; lastTaken = null
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

    /**
     * P686: a shared photo kept as a page of this scan, as the shutter keeps one: the page found in it (or the whole photo)
     * straightened in [look], with a small preview. [file] is moved to become the page's photo, so it can be fitted again.
     * Runs off the main thread; the caller adds the page to [pages] on the main thread.
     */
    fun keepShared(context: Context, file: File, look: Look, find: Boolean = true): KeptPage {
        val photo = decode(file)
        try {
            // P689: a page from Google's scanner is already found and straightened, so it starts whole.
            val corners = (if (find) detect(photo) else null) ?: whole(photo)
            val page = finishPage(file, photo, corners, look)
            try {
                val k = SHARED_PREVIEW_EDGE.toFloat() / max(page.width, page.height)
                val small = Bitmap.createScaledBitmap(page, max(1, (page.width * k).roundToInt()), max(1, (page.height * k).roundToInt()), true)
                    .let { if (it === page) page.copy(Bitmap.Config.ARGB_8888, false) else it }
                val kept = File(File(context.cacheDir, "scans").also { it.mkdirs() }, "page-${java.util.UUID.randomUUID()}.jpg")
                if (!file.renameTo(kept)) { file.copyTo(kept, overwrite = true); file.delete() }
                return KeptPage(encode(page), small, kept, corners, look)
            } finally { page.recycle() }
        } finally { photo.recycle() }
    }
    /** The size of a kept page's preview in the scan's row, as the scan screen makes it. */
    private const val SHARED_PREVIEW_EDGE = 240
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
        val k = min(1f, DETECT_GRID.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * k).roundToInt()); val h = max(1, (bitmap.height * k).roundToInt())
        val lum = lumaGrid(bitmap, w, h)
        val (region, inside) = brightRegion(lum, w, h) ?: return null
        // P693: each side then moves onto the page's straight edge, which a shadow over a corner does not hide.
        val c = PageEdges.refine(lum, w, h, region, inside)
        val corners = (0 until 4).map { PointF(c[it * 2] * bitmap.width / w, c[it * 2 + 1] * bitmap.height / h) }
        return corners.takeIf { area(it) > bitmap.width * bitmap.height * .1f && convex(it) }
    }
    /** P693: the grid [detect] looks for the page in; edges need more cells than the bright region alone did (200). */
    private const val DETECT_GRID = 400
    /** Brightness of [bitmap] averaged over each cell of a [w]×[h] grid, so fine print and noise do not alias into edges. */
    private fun lumaGrid(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val bw = bitmap.width; val bh = bitmap.height
        val sum = LongArray(w * h); val count = IntArray(w * h); val row = IntArray(bw)
        for (y in 0 until bh) {
            bitmap.getPixels(row, 0, bw, 0, y, bw, 1)
            val gy = min(h - 1, y * h / bh) * w
            for (x in 0 until bw) { val i = gy + min(w - 1, x * w / bw); sum[i] += luminance(row[x]).toLong(); count[i]++ }
        }
        return IntArray(w * h) { if (count[it] == 0) 0 else (sum[it] / count[it]).toInt() }
    }

    /**
     * P247: the corners of the largest bright region in a [w]×[h] brightness grid, as x, y pairs (top-left, top-right,
     * bottom-right, bottom-left) in grid units, or null. Shared by photos ([detect]) and the live camera's small frames.
     */
    fun detectLuma(lum: IntArray, w: Int, h: Int): FloatArray? = brightRegion(lum, w, h)?.first
    /** [detectLuma]'s corners and the region's cells. */
    private fun brightRegion(lum: IntArray, w: Int, h: Int): Pair<FloatArray, BooleanArray>? {
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
        val corners = FloatArray(8).also { out -> listOf(tl, tr, br, bl).forEachIndexed { n, i -> out[n * 2] = i % w + .5f; out[n * 2 + 1] = i / w + .5f } }
        return corners to BooleanArray(lum.size) { label[it] == best }
    }

    /** P689 (A2): the long edge of a finished scanned page, 300 dpi for A4. Corners are still dragged on the lighter [decode] copy. */
    const val PAGE_EDGE = 3508
    /**
     * The finished page: [corners] (on the [working] copy) straightened in [look] from [file] read again at [PAGE_EDGE], so a
     * large camera photo keeps its detail. Falls back to the working copy when the file is gone, no larger, or does not fit in
     * memory. The caller owns the returned bitmap.
     */
    fun finishPage(file: File?, working: Bitmap, corners: List<PointF>, look: Look): Bitmap {
        val full = file?.takeIf { it.isFile }?.let { f -> try { decode(f, PAGE_EDGE) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } }
        if (full == null || full.width <= working.width && full.height <= working.height) {
            if (full !== working) full?.recycle()
            return straighten(working, corners, look, PAGE_EDGE)
        }
        return try {
            val kx = full.width.toFloat() / working.width; val ky = full.height.toFloat() / working.height
            straighten(full, corners.map { PointF(it.x * kx, it.y * ky) }, look, PAGE_EDGE)
        } catch (e: OutOfMemoryError) { straighten(working, corners, look, PAGE_EDGE) } finally { full.recycle() }
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

    /**
     * Flattens shadows by dividing by an estimate of the paper's brightness, then applies the look. P709: the rows are shared
     * out over the phone's cores and the paper estimate is read from per-column tables, the same sums as before, so a 3508-pixel
     * page no longer waits seconds here.
     */
    fun enhance(bitmap: Bitmap, look: Look) {
        if (look == Look.ORIGINAL) return
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val balance = FloatArray(3)
        val paper = paper(pixels, w, h, balance)
        val br = balance[0]; val bg = balance[1]; val bb = balance[2]
        val smooth = paper.smooth; val cw = paper.cw; val ch = paper.ch; val cell = paper.cell
        // Where each column falls between two cells of the paper estimate, worked out once instead of once per pixel.
        val x0s = IntArray(w); val x1s = IntArray(w); val txs = FloatArray(w)
        for (x in 0 until w) {
            val fx = ((x + .5f) / cell - .5f).coerceIn(0f, cw - 1f); val x0 = fx.toInt()
            x0s[x] = x0; x1s[x] = min(cw - 1, x0 + 1); txs[x] = fx - x0
        }
        inRows(h, w) { from, to ->
            for (y in from until to) {
                val fy = ((y + .5f) / cell - .5f).coerceIn(0f, ch - 1f)
                val y0 = fy.toInt(); val y1 = min(ch - 1, y0 + 1); val ty = fy - y0
                val r0 = y0 * cw; val r1 = y1 * cw
                for (x in 0 until w) {
                    val i = y * w + x; val p = pixels[i]
                    val x0 = x0s[x]; val x1 = x1s[x]; val tx = txs[x]
                    val top = smooth[r0 + x0] * (1 - tx) + smooth[r0 + x1] * tx
                    val bottom = smooth[r1 + x0] * (1 - tx) + smooth[r1 + x1] * tx
                    val gain = 255f / (top * (1 - ty) + bottom * ty)
                    pixels[i] = when (look) {
                        Look.DOCUMENT -> Color.rgb(curve(Color.red(p) * gain * br), curve(Color.green(p) * gain * bg), curve(Color.blue(p) * gain * bb))
                        Look.GREY -> curve(luminance(p) * gain).let { Color.rgb(it, it, it) }
                        else -> (luminance(p) * gain).let { v -> if (v < 150f) 0 else if (v > 200f) 255 else ((v - 150f) * 255f / 50f).roundToInt() }.let { Color.rgb(it, it, it) }
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
    /** [paper]'s estimate: the paper's brightness per [cell]-sized cell of a [cw] x [ch] grid, smoothed. */
    private class Paper(val smooth: FloatArray, val cell: Int, val cw: Int, val ch: Int)
    /** P709: the phone's cores, shared by every scan. Its threads are daemons, so they never keep the app alive. */
    private val workers: ExecutorService by lazy {
        val n = max(2, min(8, Runtime.getRuntime().availableProcessors()))
        Executors.newFixedThreadPool(n) { r -> Thread(r, "scan-rows").apply { isDaemon = true } }
    }
    /** Runs [block] over rows 0 until [h] in bands, one per core; a small picture (a preview) is done on this thread. */
    private fun inRows(h: Int, w: Int, block: (from: Int, to: Int) -> Unit) {
        val n = max(1, min(8, Runtime.getRuntime().availableProcessors()))
        if (n == 1 || w.toLong() * h < 400_000L || Thread.currentThread().name == "scan-rows") return block(0, h)
        val band = (h + n - 1) / n
        val jobs = (1 until n).mapNotNull { k -> val from = k * band; val to = min(h, from + band); if (from < to) workers.submit { block(from, to) } else null }
        var failure: Throwable? = null
        try { block(0, min(h, band)) } catch (t: Throwable) { failure = t }
        for (job in jobs) try { job.get() } catch (e: ExecutionException) { if (failure == null) failure = e.cause ?: e }
        failure?.let { throw it }
    }
    /**
     * Paper brightness per cell (a bright percentile, so ink does not count), smoothed and interpolated. P693: a picture's
     * cells take the paper around them, so the picture keeps its tones; a shadow fades in gradually, so it still counts as paper.
     * [balance] receives one gain per colour channel that turns the paper's colour (a warm lamp, cream paper) neutral.
     */
    private fun paper(pixels: IntArray, w: Int, h: Int, balance: FloatArray): Paper {
        val cell = max(8, max(w, h) / 32); val cw = (w + cell - 1) / cell; val ch = (h + cell - 1) / cell
        val grid = FloatArray(cw * ch)
        val colour = Array(3) { FloatArray(cw * ch) }
        inRows(ch, w * cell / 4) { fromRow, toRow ->
        val histogram = IntArray(64)
        for (gy in fromRow until toRow) for (gx in 0 until cw) {
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
            // The paper's colour here: the average of the pixels as bright as the paper.
            var r = 0L; var g = 0L; var b = 0L; var k = 0
            y = gy * cell
            while (y < min(h, (gy + 1) * cell)) {
                var x = gx * cell
                while (x < min(w, (gx + 1) * cell)) {
                    val p = pixels[y * w + x]
                    if (luminance(p) shr 2 >= bin) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); k++ }
                    x += 2
                }
                y += 2
            }
            if (k > 0) { colour[0][gy * cw + gx] = r.toFloat() / k; colour[1][gy * cw + gx] = g.toFloat() / k; colour[2][gy * cw + gx] = b.toFloat() / k }
        }
        }
        // A picture starts where the brightness drops sharply from one cell to the next, and takes in the cells beside it
        // that are as dark compared with the paper it started from.
        val isPaper = BooleanArray(grid.size) { true }
        val level = FloatArray(grid.size); val stack = IntArray(grid.size); var top = 0
        for (i in grid.indices) {
            val gx = i % cw; val gy = i / cw; var m = 0f
            for (dy in -1..1) for (dx in -1..1) { val x = gx + dx; val y = gy + dy; if ((dx != 0 || dy != 0) && x in 0 until cw && y in 0 until ch) m = max(m, grid[y * cw + x]) }
            if (grid[i] < GUARD * m) { isPaper[i] = false; level[i] = m; stack[top++] = i }
        }
        while (top > 0) {
            val i = stack[--top]; val gx = i % cw; val gy = i / cw
            fun grow(j: Int) { if (isPaper[j] && grid[j] < GUARD * level[i]) { isPaper[j] = false; level[j] = level[i]; stack[top++] = j } }
            if (gx > 0) grow(i - 1); if (gx < cw - 1) grow(i + 1); if (gy > 0) grow(i - cw); if (gy < ch - 1) grow(i + cw)
        }
        // White balance: the typical colour of the paper cells, each channel scaled to the paper's brightness.
        val ratios = Array(3) { FloatArray(grid.size) }; var count = 0
        for (i in grid.indices) {
            if (!isPaper[i]) continue
            val l = (colour[0][i] * 54 + colour[1][i] * 183 + colour[2][i] * 19) / 256
            if (l < 1f) continue
            for (c in 0 until 3) ratios[c][count] = colour[c][i] / l
            count++
        }
        for (c in 0 until 3) balance[c] = if (count == 0) 1f else (1f / ratios[c].copyOf(count).sorted()[count / 2]).coerceIn(.7f, 1.4f)
        // Picture cells take the paper around them, spreading inwards from the paper cells.
        val known = isPaper.copyOf()
        while (true) {
            var changed = false
            val next = grid.copyOf(); val now = known.copyOf()
            for (i in grid.indices) {
                if (known[i]) continue
                val gx = i % cw; val gy = i / cw; var sum = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) { val x = gx + dx; val y = gy + dy; if (x in 0 until cw && y in 0 until ch && known[y * cw + x]) { sum += grid[y * cw + x]; n++ } }
                if (n > 0) { next[i] = sum / n; now[i] = true; changed = true }
            }
            if (!changed) break
            next.copyInto(grid); now.copyInto(known)
        }
        val smooth = FloatArray(grid.size) { i ->
            val gx = i % cw; val gy = i / cw; var sum = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) { val x = gx + dx; val y = gy + dy; if (x in 0 until cw && y in 0 until ch) { sum += grid[y * cw + x]; n++ } }
            sum / n
        }
        return Paper(smooth, cell, cw, ch)
    }
    /** P693: a cell this much darker than the paper next to it is a picture; a shadow darkens far less from cell to cell. */
    private const val GUARD = .8f
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
    private val lensPhoto = Matrix()
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
    /**
     * P689: a straight crop. The four sides stay square to the photo: a corner drags its two sides with it and a side moves
     * only across. Switching it on squares the corners to the box around them, keeping which corner is the top left.
     */
    var straight = false
        set(value) { field = value; if (value) setCorners(squared(corners)) else invalidate() }
    fun setCorners(points: List<PointF>) {
        val placed = if (angle == 0f) points else fitted(points)
        placed.forEachIndexed { i, p -> corners[i].set(p) }; invalidate(); onCornersChanged?.invoke()
    }

    // ---- P720: straighten. The photo is shown turned by [angle] about its middle, inside its own frame; [corners] are kept
    // in that turned photo (so a straight crop stays square on screen) and [photoCorners] gives them back in the photo.
    /** Degrees the photo is turned, clockwise, at most 45 either way. */
    var angle = 0f; private set
    private val turn = Matrix(); private val unturn = Matrix(); private val drawn = Matrix()
    /** P720: the scan board's tint over the chosen part; the picture crop shows a picture in its own colours. */
    var tint = true
    /** P720: a fine grid inside the outline, shown while the photo is being straightened. */
    var grid = false
        set(value) { field = value; invalidate() }
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /**
     * Turns the photo to [degrees]. The outline becomes [base] (the outline before straightening started, so turning back
     * gives it back) made smaller about its middle until it lies on the photo.
     */
    fun setAngle(degrees: Float, base: List<PointF> = corners.map { PointF(it.x, it.y) }) {
        angle = degrees.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        turn.setRotate(angle, photo.width / 2f, photo.height / 2f); turn.invert(unturn)
        drawn.set(turn); drawn.postConcat(fit)
        fitted(base).forEachIndexed { i, p -> corners[i].set(p) }
        invalidate(); onCornersChanged?.invoke()
    }
    /** The corners in the photo itself, for straightening: the turned outline turned back. */
    fun photoCorners(): List<PointF> = corners.map { c -> floatArrayOf(c.x, c.y).also { unturn.mapPoints(it) }.let { PointF(it[0], it[1]) } }
    /** On the photo's frame and on the turned photo (a little play, so the photo's own edge counts). */
    private fun onPhoto(x: Float, y: Float): Boolean {
        val play = .5f
        if (x < -play || y < -play || x > photo.width + play || y > photo.height + play) return false
        if (angle == 0f) return true
        val p = floatArrayOf(x, y).also { unturn.mapPoints(it) }
        return p[0] >= -play && p[1] >= -play && p[0] <= photo.width + play && p[1] <= photo.height + play
    }
    private fun allOnPhoto(points: List<PointF>) = points.all { onPhoto(it.x, it.y) }
    /** [points] made smaller about their middle (or the photo's, when theirs is off the photo) until all lie on the photo. */
    private fun fitted(points: List<PointF>): List<PointF> {
        if (allOnPhoto(points)) return points.map { PointF(it.x, it.y) }
        var mx = points.sumOf { it.x.toDouble() }.toFloat() / points.size; var my = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        if (!onPhoto(mx, my)) { mx = photo.width / 2f; my = photo.height / 2f }
        fun scaled(s: Float) = points.map { PointF(mx + (it.x - mx) * s, my + (it.y - my) * s) }
        var low = 0f; var high = 1f
        repeat(16) { val s = (low + high) / 2; if (allOnPhoto(scaled(s))) low = s else high = s }
        return scaled(low)
    }
    /** After a drag: when it took the outline off the turned photo, it goes only as far as it stays on. */
    private fun keepOnPhoto(before: List<PointF>) {
        if (allOnPhoto(corners) || !allOnPhoto(before)) return
        val after = corners.map { PointF(it.x, it.y) }
        fun at(t: Float) = before.indices.map { i -> PointF(before[i].x + (after[i].x - before[i].x) * t, before[i].y + (after[i].y - before[i].y) * t) }
        var low = 0f; var high = 1f
        repeat(14) { val t = (low + high) / 2; if (allOnPhoto(at(t))) low = t else high = t }
        at(low).forEachIndexed { i, p -> corners[i].set(p) }
    }
    private fun drawGrid(canvas: Canvas) {
        val q = (0 until 4).map { cornerInView(it) }
        fun along(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        for (n in 1 until GRID) {
            val t = n.toFloat() / GRID
            gridLine.strokeWidth = (if (n % 2 == 0) 1.2f else .7f) * density; gridLine.color = if (n % 2 == 0) 0xb3ffffff.toInt() else 0x59ffffff
            val top = along(q[0], q[1], t); val bottom = along(q[3], q[2], t); canvas.drawLine(top[0], top[1], bottom[0], bottom[1], gridLine)
            val left = along(q[0], q[3], t); val right = along(q[1], q[2], t); canvas.drawLine(left[0], left[1], right[0], right[1], gridLine)
        }
    }
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
        drawn.set(turn); drawn.postConcat(fit)
    }
    fun cornerInView(i: Int) = floatArrayOf(corners[i].x, corners[i].y).also { fit.mapPoints(it) }
    override fun onDraw(canvas: Canvas) {
        val shown = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()).also { fit.mapRect(it) }
        // P720: a straightened photo turns inside its own frame.
        canvas.save(); canvas.clipRect(shown)
        canvas.drawBitmap(photo, drawn, photoPaint)
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(shown, Path.Direction.CW)
        (0 until 4).forEach { i -> val p = cornerInView(i); if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }
        path.close()
        canvas.drawPath(path, dim)
        canvas.restore()
        path.reset(); (0 until 4).forEach { i -> val p = cornerInView(i); if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }; path.close()
        if (tint) canvas.drawPath(path, fill)
        if (grid) drawGrid(canvas)
        canvas.drawPath(path, edge)
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
        lensPhoto.set(turn); lensPhoto.postConcat(lens)
        canvas.drawBitmap(photo, lensPhoto, photoPaint)
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
                val before = if (angle == 0f) null else corners.map { PointF(it.x, it.y) }
                val p = floatArrayOf(event.x, event.y).also { inverse.mapPoints(it) }
                val x = (p[0] + grabbed.x).coerceIn(0f, photo.width.toFloat()); val y = (p[1] + grabbed.y).coerceIn(0f, photo.height.toFloat())
                if (dragging < 4) { if (straight) moveStraightCorner(dragging, x, y) else corners[dragging].set(x, y) }
                else if (straight) moveStraightEdge(dragging - 4, p[0] - lastTouch[0], p[1] - lastTouch[1])
                else moveEdge(dragging - 4, p[0] - lastTouch[0], p[1] - lastTouch[1])
                before?.let { keepOnPhoto(it) }
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
    /** The smallest side a straight crop keeps, so it never turns inside out. */
    private val leastSide get() = max(8f, max(photo.width, photo.height) * .02f)
    /** A straight corner stays on its side of the opposite corner; the neighbour sharing its x takes the new x, the other the new y. */
    private fun moveStraightCorner(i: Int, x: Float, y: Float) {
        val held = corners[i]; val opposite = corners[(i + 2) % 4]
        val nx = if (held.x < opposite.x) min(x, opposite.x - leastSide) else max(x, opposite.x + leastSide)
        val ny = if (held.y < opposite.y) min(y, opposite.y - leastSide) else max(y, opposite.y + leastSide)
        for (n in listOf(corners[(i + 1) % 4], corners[(i + 3) % 4])) {
            if (abs(n.x - held.x) <= abs(n.y - held.y)) n.x = nx else n.y = ny
        }
        held.set(nx, ny)
    }
    /** A straight side moves only across itself, stopping at the photo's edge and short of the opposite side. */
    private fun moveStraightEdge(edge: Int, dx: Float, dy: Float) {
        val a = corners[edge]; val b = corners[(edge + 1) % 4]; val far = corners[(edge + 2) % 4]
        if (abs(b.x - a.x) >= abs(b.y - a.y)) {
            val limit = if (a.y < far.y) (0f to far.y - leastSide) else (far.y + leastSide to photo.height.toFloat())
            val y = (a.y + dy).coerceIn(limit.first, max(limit.first, limit.second)); a.y = y; b.y = y
        } else {
            val limit = if (a.x < far.x) (0f to far.x - leastSide) else (far.x + leastSide to photo.width.toFloat())
            val x = (a.x + dx).coerceIn(limit.first, max(limit.first, limit.second)); a.x = x; b.x = x
        }
    }
    /** The box around [points], its corners handed out so the one nearest the first point's corner of the box leads. */
    private fun squared(points: List<PointF>): List<PointF> {
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }; val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val box = listOf(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom))
        val lead = box.indices.minBy { hypot(box[it].x - points[0].x, box[it].y - points[0].y) }
        return box.indices.map { box[(lead + it) % 4] }
    }
    private companion object {
        /** How much larger than the photo on screen the magnifier shows it. */
        const val LENS_ZOOM = 3f
        const val LENS_RADIUS = 68f
        /** From the spot to the magnifier's rim: clear of a fingertip or the pen's tip. */
        const val LENS_GAP = 48f
        /** P720: the straighten grid's divisions; every second line is brighter (thirds). */
        const val GRID = 6
        const val MAX_ANGLE = 45f
    }
}
