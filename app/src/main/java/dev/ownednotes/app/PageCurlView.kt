package dev.ownednotes.app

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.TurnDirection
import kotlin.math.*

/** Presentation only: shared immutable page previews, a diagonal paper fold, and release settling.
 * Never owns ink, document history or page selection. All calls and callbacks are on the UI thread.
 */
@SuppressLint("ViewConstructor") // Programmatic-only view needs repository/renderer dependencies.
class PageCurlView(
    context: Context,
    private val repository: NotebookRepository,
    private val thumbnails: PageThumbnails,
    private val turn: (TurnDirection) -> Boolean,
    private val canReveal: () -> Boolean,
) : View(context) {
    /** B08 (P131): a forward turn lifts the current page off the next one; a back turn is that motion reversed, so the
     * previous page's leaf comes back from the left and lays itself over the current page. [leaf] is the moving sheet,
     * [corner] its free corner, and [restX]/[turnedX] the tip's x when the leaf lies flat on this page / is turned away. */
    private class Turn(val notebook: Notebook, val page: Page, val target: Page, val direction: TurnDirection,
        val leaf: Bitmap, val under: Bitmap, val leafBounds: RectF, val underBounds: RectF, val corner: PointF, val restX: Float, val turnedX: Float,
        val addPage: Boolean = false) {
        val back get() = direction == TurnDirection.PREVIOUS
        val start get() = if (back) turnedX else restX
        val end get() = if (back) restX else turnedX
    }
    private val requests = mutableListOf<PageThumbnails.Request>()
    private var warmed: List<PageThumbnails.Key> = emptyList()
    private var warming = 0
    private var current: Turn? = null
    private val tip = PointF()
    private var animator: ValueAnimator? = null
    private var revealed = false
    private var revealDeadline = 0L
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x44807158 }
    private val sourceCorners = FloatArray(8)
    private val frontPoints = FloatArray(12)
    private val backPoints = FloatArray(12)
    private val frontPath = Path()
    private val backPath = Path()
    private val shadowPoints = FloatArray(12)
    private val shadowPath = Path()
    private val shadowGradient = LinearGradient(0f, 0f, 1f, 0f, 0x38000000, 0x00000000, Shader.TileMode.CLAMP)
    private val reflection = Matrix()
    private val gradientMatrix = Matrix()
    private val matrixValues = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val ridgeGradient = LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x0050432e, 0x16756240, 0x55a28c65, 0x11ffffff), floatArrayOf(0f, .55f, .9f, 1f), Shader.TileMode.CLAMP)
    var deskColor: Int = Tokens.DESK
    /** B13 (P136): the blank "Add page" sheet after the last page. A forward turn on the last page lifts the page off it,
     * and turning back from it lays the last page back over it, the same paper motion as between two pages. */
    interface EndCard {
        /** A page may be added after the last one. */
        val available: Boolean
        /** Draws the sheet as it looks when open, for the page at [page] in this view's coordinates. */
        fun draw(canvas: Canvas, page: RectF)
        /** The forward turn finished: show the sheet at once, without its own slide. */
        fun open(): Boolean
        /** Hides the open sheet while the last page turns back over it, or shows it again if that turn is let go. */
        fun conceal(hidden: Boolean)
        /** The back turn finished: close the sheet at once. */
        fun close()
    }
    var endCard: EndCard? = null
    private var endBitmap: Bitmap? = null
    private fun endSheet(shown: Bitmap, bounds: RectF): Bitmap {
        val card = endCard!!
        val bitmap = endBitmap?.takeIf { it.width == shown.width && it.height == shown.height }
            ?: Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888).also { endBitmap = it }
        val c = Canvas(bitmap)
        c.drawColor(deskColor)
        c.scale(shown.width / bounds.width(), shown.height / bounds.height()); c.translate(-bounds.left, -bounds.top)
        card.draw(c, bounds)
        return bitmap
    }
    val active get() = current != null
    val settling get() = animator != null || revealed
    val progress: Float get() = current?.let { t -> abs(tip.x - t.start) / (t.leafBounds.width() * 2f) } ?: 0f
    /** The finger's travel in the turn's direction; what decides whether it completes, wherever the paper's corner can reach. */
    private var pull = 0f
    /** The finger's vertical travel when the turn began, which may be long after it touched down. */
    private var baseDy = 0f
    private var landing: (() -> Unit)? = null
    /** For tests: the free corner's position, and the spine's two corners on its side. */
    internal val tipPosition get() = current?.let { PointF(tip.x, tip.y) }
    internal val leafWidth get() = current?.leafBounds?.width() ?: 0f
    internal val spineCorners get() = current?.let { t -> RectF(t.leafBounds.left, t.corner.y, t.leafBounds.left, if (t.corner.y <= t.leafBounds.top) t.leafBounds.bottom else t.leafBounds.top) }
    val previewsReady: Boolean get() {
        val notebook = repository.session?.notebook ?: return false
        val index = notebook.pages.indexOfFirst { it.id == repository.selectedPageId }
        return notebook.pages.getOrNull(index)?.let { thumbnails.cached(notebook, it.id, PREVIEW_WIDTH) } != null &&
            listOf(index - 1, index + 1).filter { it in notebook.pages.indices }.all { thumbnails.cached(notebook, notebook.pages[it].id, PREVIEW_WIDTH) != null }
    }
    init { visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Keep the current/adjacent pages warm without rebuilding PDF/ink on a gesture frame. */
    fun sync(enabled: Boolean) {
        val notebook = repository.session?.notebook
        current?.let { t ->
            val expected = if (revealed) t.target.id else t.page.id
            if (!enabled || notebook !== t.notebook || repository.selectedPageId != expected) cancel()
        }
        if (!enabled || notebook == null) { stopRequests(); warmed = emptyList(); return }
        val index = notebook.pages.indexOfFirst { it.id == repository.selectedPageId }
        val pages = (index - 1..index + 1).mapNotNull { notebook.pages.getOrNull(it) }
        val keys = pages.map { thumbnails.key(notebook, it, PREVIEW_WIDTH) }
        if (keys == warmed && pages.all { thumbnails.cached(notebook, it.id, PREVIEW_WIDTH) != null }) return
        // In-flight requests already correspond to this snapshot; don't cancel/restart on every refresh.
        if (keys == warmed && warming > 0) return
        stopRequests(); warmed = keys
        warming = pages.size
        pages.forEach { page -> requests += thumbnails.request(notebook, page.id, PREVIEW_WIDTH) { result ->
            warming--
            if (result.isFailure) { warmed = emptyList() } // retry on the next idle refresh; instant turns still work
        } }
    }
    private fun stopRequests() { requests.forEach { it.cancel() }; requests.clear(); warming = 0 }

    fun drag(originX: Float, originY: Float, dx: Float, dy: Float, bounds: RectF) {
        if (settling) return
        var t = current
        if (t == null) {
            if (abs(dx) < 1f && abs(dy) < 1f) return
            val notebook = repository.session?.notebook ?: return
            val index = notebook.pages.indexOfFirst { it.id == repository.selectedPageId }
            val direction = if (abs(dx) >= abs(dy) * .3f) {
                if (dx < 0) TurnDirection.NEXT else TurnDirection.PREVIOUS
            } else if (originX >= bounds.centerX()) TurnDirection.NEXT else TurnDirection.PREVIOUS
            val next = notebook.pages.getOrNull(index + if (direction == TurnDirection.NEXT) 1 else -1)
            // Past the last page the blank "Add page" sheet lies under it (B13).
            val end = next == null && direction == TurnDirection.NEXT && endCard?.available == true
            if (next == null && !end) return
            val page = notebook.pages.getOrNull(index) ?: return
            val target = next ?: page
            val shown = thumbnails.cached(notebook, page.id, PREVIEW_WIDTH) ?: return
            val other = if (end) endSheet(shown, bounds) else thumbnails.cached(notebook, target.id, PREVIEW_WIDTH) ?: return
            val back = direction == TurnDirection.PREVIOUS
            val targetBounds = if (end) RectF(bounds) else RectF().also { fitted(target, it) }
            // Forward: the current page is the leaf, the next page lies under it. Back: the previous page is the leaf.
            val leafBounds = if (back) targetBounds else RectF(bounds)
            val corner = PointF(leafBounds.right, if (originY < bounds.centerY()) leafBounds.top else leafBounds.bottom)
            t = Turn(notebook, page, target, direction, if (back) other else shown, if (back) shown else other,
                leafBounds, if (back) RectF(bounds) else targetBounds, corner, corner.x, corner.x - 2 * leafBounds.width(), addPage = end)
            current = t; tip.set(t.start, corner.y); visibility = VISIBLE; baseDy = dy
        }
        follow(t, dx, dy)
    }

    /** B13 (P136): from the open "Add page" sheet, a pull to the right lays the last page back over it.
     * Returns false when no curl can be drawn (no preview yet, or curls are off), so the caller closes the sheet itself. */
    fun dragFromEnd(originY: Float, dx: Float, dy: Float, bounds: RectF): Boolean {
        if (settling) return current?.addPage == true
        var t = current
        if (t == null) {
            val card = endCard ?: return false
            val notebook = repository.session?.notebook ?: return false
            val page = notebook.pages.firstOrNull { it.id == repository.selectedPageId } ?: return false
            val shown = thumbnails.cached(notebook, page.id, PREVIEW_WIDTH) ?: return false
            val corner = PointF(bounds.right, if (originY < bounds.centerY()) bounds.top else bounds.bottom)
            t = Turn(notebook, page, page, TurnDirection.PREVIOUS, shown, endSheet(shown, bounds),
                RectF(bounds), RectF(bounds), corner, corner.x, corner.x - 2 * bounds.width(), addPage = true)
            current = t; tip.set(t.start, corner.y); visibility = VISIBLE; baseDy = dy
            card.conceal(true)
        }
        if (!t.addPage) return false
        follow(t, dx, dy)
        return true
    }

    private fun follow(t: Turn, dx: Float, dy: Float) {
        val sign = if (t.direction == TurnDirection.NEXT) -1f else 1f
        // Finger origin need not be the literal corner; displacement pulls the nearest corner with it.
        val along = (dx * sign).coerceIn(0f, t.leafBounds.width() * 2f)
        pull = along
        bindToSpine(t, t.start + sign * along, t.corner.y + dy - baseDy)
        invalidate()
    }

    /**
     * P560: real paper stays bound along its spine, so the corner can only reach a half disc: within a leaf's width of the
     * spine's near end (or the fold would tear the sheet off the spine), and no further out than the page edge it started on.
     * Beyond that edge the fold swings to the page's diagonal, and a tiny step flips half the sheet over: the flicker.
     * Each projection below moves two nearby points no further apart, so the paper can't leap further than the finger moved.
     * Inside the half disc the far end of the spine is always on the tip's side too.
     */
    private fun bindToSpine(t: Turn, x: Float, y: Float) {
        val bounds = t.leafBounds
        val cornerY = t.corner.y
        val py = if (cornerY <= bounds.centerY()) maxOf(y, cornerY) else minOf(y, cornerY)
        val dx = x - bounds.left; val dy = py - cornerY
        val reach = hypot(dx, dy); val width = bounds.width()
        if (reach > width && reach > 0f) tip.set(bounds.left + dx * width / reach, cornerY + dy * width / reach)
        else tip.set(x, py)
    }

    /** A deliberate slow pull also completes; velocity is only an optional fast-swipe shortcut. */
    fun release(fastTurn: TurnDirection? = null) {
        val t = current ?: return
        if (settling) return
        val complete = (fastTurn == t.direction && pull > 1f) || pull >= t.leafBounds.width() * .35f
        val startX = tip.x; val startY = tip.y
        val endX = if (complete) t.end else t.start
        fun finish() {
            animator = null; landing = null
            if (!complete) { cancel(); return }
            // Selection can become unavailable during settling (file picker, pen, notebook switch).
            if (repository.session?.notebook !== t.notebook || repository.selectedPageId != t.page.id) { cancel(); return }
            // Back from the "Add page" sheet: the last page is already drawn underneath, so the curl can simply go.
            if (t.addPage && t.back) { endCard?.close(); cancel(); return }
            revealed = true // refresh inside turn() must expect the destination, not the old page
            if (!(if (t.addPage) endCard?.open() == true else turn(t.direction))) { cancel(); return }
            revealDeadline = SystemClock.uptimeMillis() + 2000
            invalidate(); postOnAnimation(reveal)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { tip.set(endX, t.corner.y); finish(); return }
        landing = { tip.set(endX, t.corner.y); finish() }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (180 + (abs(endX - startX) / (t.leafBounds.width() * 2f) * 160)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { a -> val f = a.animatedValue as Float
                tip.set(startX + (endX - startX) * f, startY + (t.corner.y - startY) * f); invalidate()
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finish() } })
            start()
        }
    }
    private val reveal = object : Runnable {
        override fun run() {
            if (!revealed) return
            if (canReveal() || SystemClock.uptimeMillis() >= revealDeadline) cancel() else postOnAnimation(this)
        }
    }
    /** P560: a released turn lands at once, so a finger coming down mid-animation carries on from its destination. */
    fun settleNow() {
        val land = landing ?: return
        animator?.let { it.removeAllListeners(); it.cancel() }
        land()
    }
    fun cancel() {
        animator?.let { it.removeAllListeners(); it.cancel() }; animator = null; landing = null
        // A back turn from the "Add page" sheet that was let go (or interrupted) shows the sheet again.
        current?.takeIf { it.addPage && it.back }?.let { endCard?.conceal(false) }
        removeCallbacks(reveal); current = null; revealed = false; visibility = INVISIBLE; invalidate()
    }
    fun dispose() { cancel(); stopRequests(); warmed = emptyList() }
    override fun onDetachedFromWindow() { cancel(); stopRequests(); warmed = emptyList(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = current ?: return
        canvas.drawColor(deskColor)
        // Once turned, only the destination shows until the editor has drawn it: the under page forward, the leaf back.
        if (revealed) { if (t.back) sheet(canvas, t.leaf, t.leafBounds) else sheet(canvas, t.under, t.underBounds); return }
        sheet(canvas, t.under, t.underBounds)
        val length = hypot(t.corner.x - tip.x, t.corner.y - tip.y)
        if (length < 1f) { canvas.drawBitmap(t.leaf, null, t.leafBounds, bitmapPaint); return }
        val nx = (t.corner.x - tip.x) / length; val ny = (t.corner.y - tip.y) / length
        val mx = (t.corner.x + tip.x) / 2; val my = (t.corner.y + tip.y) / 2
        val offset = mx * nx + my * ny
        if (t.back) {
            // The returning leaf is still lifted beyond its fold, so it shades the current page just past that line.
            corners(t.underBounds)
            val count = clip(nx, ny, offset, true, shadowPoints)
            val reach = min(36f * resources.displayMetrics.density, length * .25f).coerceAtLeast(1f)
            matrixValues[0] = nx * reach; matrixValues[1] = -ny; matrixValues[2] = mx
            matrixValues[3] = ny * reach; matrixValues[4] = nx; matrixValues[5] = my
            gradientMatrix.setValues(matrixValues); shadowGradient.setLocalMatrix(gradientMatrix); shade.shader = shadowGradient
            canvas.drawPath(path(shadowPath, shadowPoints, count), shade)
        }
        corners(t.leafBounds)
        val frontCount = clip(nx, ny, offset, false, frontPoints)
        val backCount = clip(nx, ny, offset, true, backPoints)
        for (i in 0 until backCount) {
            val j = i * 2; val d = backPoints[j] * nx + backPoints[j + 1] * ny - offset
            backPoints[j] -= 2 * nx * d; backPoints[j + 1] -= 2 * ny * d
        }
        val front = path(frontPath, frontPoints, frontCount)
        val back = path(backPath, backPoints, backCount)
        canvas.save(); canvas.clipPath(front); canvas.drawBitmap(t.leaf, null, t.leafBounds, bitmapPaint); canvas.restore()
        // Cast shadow below the turned paper, then its lightly translucent reversed content.
        canvas.save(); canvas.translate(nx * -7, ny * -7); paper.color = 0x30000000; canvas.drawPath(back, paper); canvas.restore()
        canvas.save(); canvas.clipPath(back)
        paper.color = 0xfff5f0e5.toInt(); canvas.drawPath(back, paper)
        matrixValues[0] = 1 - 2 * nx * nx; matrixValues[1] = -2 * nx * ny; matrixValues[2] = 2 * nx * offset
        matrixValues[3] = -2 * nx * ny; matrixValues[4] = 1 - 2 * ny * ny; matrixValues[5] = 2 * ny * offset
        reflection.setValues(matrixValues)
        canvas.save(); canvas.concat(reflection); bitmapPaint.alpha = 35
        canvas.drawBitmap(t.leaf, null, t.leafBounds, bitmapPaint); bitmapPaint.alpha = 255; canvas.restore()
        val ridge = min(64f * resources.displayMetrics.density, length * .4f).coerceAtLeast(1f)
        matrixValues[0] = nx * (ridge + 1); matrixValues[1] = -ny; matrixValues[2] = mx - nx * ridge
        matrixValues[3] = ny * (ridge + 1); matrixValues[4] = nx; matrixValues[5] = my - ny * ridge
        gradientMatrix.setValues(matrixValues); ridgeGradient.setLocalMatrix(gradientMatrix); shade.shader = ridgeGradient
        canvas.drawPath(back, shade); canvas.restore()
        canvas.drawPath(back, edge)
    }
    /** A flat page with its soft desk shadow. */
    private fun sheet(canvas: Canvas, bitmap: Bitmap, r: RectF) {
        paper.color = 0x22000000; canvas.drawRect(r.left - 2, r.top + 2, r.right + 2, r.bottom + 4, paper)
        canvas.drawBitmap(bitmap, null, r, bitmapPaint)
    }
    private fun corners(r: RectF) {
        sourceCorners[0] = r.left; sourceCorners[1] = r.top; sourceCorners[2] = r.right; sourceCorners[3] = r.top
        sourceCorners[4] = r.right; sourceCorners[5] = r.bottom; sourceCorners[6] = r.left; sourceCorners[7] = r.bottom
    }
    /** P176 (G1b): where a fitted page goes, clear of the floating chrome; matches [InkCanvas.fit]. */
    var fitArea: () -> RectF = { RectF(0f, 0f, width.toFloat(), height.toFloat()) }
    private fun fitted(page: Page, result: RectF) {
        val area = fitArea()
        val scale = min((area.width() - 24f) / page.width, (area.height() - 24f) / page.height)
        val w = page.width * scale; val h = page.height * scale
        result.set(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2)
    }
    private fun path(path: Path, points: FloatArray, count: Int): Path {
        path.rewind()
        if (count > 0) {
            path.moveTo(points[0], points[1])
            for (i in 1 until count) path.lineTo(points[i * 2], points[i * 2 + 1])
            path.close()
        }
        return path
    }
    private fun clip(nx: Float, ny: Float, offset: Float, positive: Boolean, out: FloatArray): Int {
        var count = 0
        for (i in 0..3) {
            val j = i * 2; val k = ((i + 1) % 4) * 2
            val ax = sourceCorners[j]; val ay = sourceCorners[j + 1]
            val bx = sourceCorners[k]; val by = sourceCorners[k + 1]
            val da = (ax * nx + ay * ny - offset) * if (positive) 1 else -1
            val db = (bx * nx + by * ny - offset) * if (positive) 1 else -1
            if (da >= 0) { out[count * 2] = ax; out[count * 2 + 1] = ay; count++ }
            if ((da >= 0) != (db >= 0)) {
                val f = da / (da - db); out[count * 2] = ax + (bx - ax) * f; out[count * 2 + 1] = ay + (by - ay) * f; count++
            }
        }
        return count
    }
    companion object { private const val PREVIEW_WIDTH = 1024 }
}
