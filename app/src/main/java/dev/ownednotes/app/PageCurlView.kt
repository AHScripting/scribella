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
     * [corner] its free corner, and [restX]/[turnedX] the tip's x when the leaf lies flat on this page / is turned away.
     * P587: [live] when the editor underneath shows the page on screen fully drawn, so this view leaves it in view. */
    private class Turn(val notebook: Notebook, val page: Page, val target: Page, val direction: TurnDirection,
        val leaf: Bitmap, val under: Bitmap, val leafBounds: RectF, val underBounds: RectF, val corner: PointF, val restX: Float, val turnedX: Float,
        val addPage: Boolean = false, val live: Boolean = false) {
        val back get() = direction == TurnDirection.PREVIOUS
        val start get() = if (back) turnedX else restX
        val end get() = if (back) restX else turnedX
    }
    private val requests = mutableListOf<PageThumbnails.Request>()
    private var warmed: List<PageThumbnails.Key> = emptyList()
    private var warming = 0
    /** P586: the way the last turn went, so the page after the next one is warmed too when flicking through quickly. */
    private var lastDirection: TurnDirection? = null
    private var current: Turn? = null
    private val tip = PointF()
    private var animator: ValueAnimator? = null
    private var revealed = false
    private var revealDeadline = 0L
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x44807158 }
    private val sourceCorners = FloatArray(8)
    private val shadowPoints = FloatArray(12)
    private val shadowPath = Path()
    private val shadowGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0xff000000.toInt(), 0x55000000, 0x00000000), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
    private val gradientMatrix = Matrix()
    private val matrixValues = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val frontMesh = FloatArray(VERTICES * 2)
    private val backMesh = FloatArray(VERTICES * 2)
    // One shade per mesh vertex (drawBitmapMesh multiplies the bitmap by it); sized generously.
    private val outlinePath = Path()
    /** The turning sheet's paper colour, for its back. */
    private var backPaper = 0xfff5f0e5.toInt()
    /**
     * P586: the sheet's back is plain paper, so it is drawn as its outline filled with a screen-sized gradient across the
     * roll. (P584 painted the gradient into a quarter-size bitmap carried by a mesh; on the tablet the stretched bitmap's
     * steps showed as squares.)
     */
    private val backShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val shadeStops = FloatArray(SHADE_STOPS)
    private val shadeColors = IntArray(SHADE_STOPS)
    /** P584: the front darkens evenly as it nears the roll (a gradient, not per-vertex shades on the grid). */
    private val riseGradient = LinearGradient(0f, 0f, 1f, 0f, 0xff000000.toInt(), 0, Shader.TileMode.CLAMP)
    /** P583: an even shade, for the page under the roll itself. */
    private val slabGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0), floatArrayOf(0f, .97f, 1f), Shader.TileMode.CLAMP)
    /**
     * P583: the turned-over sheet's shadow, drawn as its own shape blurred at a sixth of the size, so it fades evenly all
     * round (P581 drew a gradient strip per edge, which met in blocks at the corner and stepped along the edges).
     */
    private var shadowMask: Bitmap? = null
    private var shadowPixels = IntArray(0)
    private var shadowBlur = IntArray(0)
    private var shadowTemp = IntArray(0)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val maskMatrix = Matrix()
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowRect = RectF()
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
    private fun endSheet(bounds: RectF): Bitmap {
        val card = endCard!!
        val w = ceil(bounds.width()).toInt().coerceAtLeast(1); val h = ceil(bounds.height()).toInt().coerceAtLeast(1)
        val bitmap = endBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { endBitmap = it }
        val c = Canvas(bitmap)
        c.drawColor(deskColor)
        c.scale(w / bounds.width(), h / bounds.height()); c.translate(-bounds.left, -bounds.top)
        card.draw(c, bounds)
        return bitmap
    }
    val active get() = current != null
    val settling get() = animator != null || revealed
    /**
     * P586: the turn has landed and its page still covers the editor while that page's PDF draws. A finger coming down
     * now (the next quick flip) must leave it there: dropping it showed the bare, still-rendering page for a moment.
     */
    val holding get() = revealed && animator == null && current?.addPage == false
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
        val near = (index - 1..index + 1).mapNotNull { notebook.pages.getOrNull(it) }
        // P586: one page further in the direction of travel, so a quick next flip from a just-landed page can start at
        // once instead of waiting for that page's preview; only when the cache holds all four (about 6 MB each).
        val ahead = lastDirection?.let { notebook.pages.getOrNull(index + if (it == TurnDirection.NEXT) 2 else -2) }
        fun bytes(page: Page) = PREVIEW_WIDTH * (PREVIEW_WIDTH * page.height / page.width) * 4f
        val pages = if (ahead != null && (near + ahead).sumOf { bytes(it).toDouble() } * 1.15 <= thumbnails.maxBytes) near + ahead else near
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
    internal val warmState get() = "warming=$warming warmed=${warmed.size} pending=${thumbnails.pendingCount} cache=${thumbnails.sizeBytes / 1024}K/${thumbnails.maxBytes / 1024}K"
    private fun stopRequests() { requests.forEach { it.cancel() }; requests.clear(); warming = 0 }

    fun drag(originX: Float, originY: Float, dx: Float, dy: Float, bounds: RectF) {
        // P586: from a landed page still being held, the next turn starts at once; begin() takes over from the hold.
        if (settling && !holding) return
        lastBounds.set(bounds)
        var t = current?.takeUnless { holding }
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
            val other0 = if (end) null else thumbnails.cached(notebook, target.id, PREVIEW_WIDTH) ?: return
            val (shown, shownBounds) = currentPage(notebook, page, bounds) ?: return
            val other = other0 ?: endSheet(shownBounds)
            val back = direction == TurnDirection.PREVIOUS
            val targetBounds = if (end) RectF(shownBounds) else RectF().also { fitted(target, it) }
            // Forward: the current page is the leaf, the next page lies under it. Back: the previous page is the leaf.
            val leafBounds = if (back) targetBounds else RectF(shownBounds)
            val corner = PointF(leafBounds.right, if (originY < bounds.centerY()) leafBounds.top else leafBounds.bottom)
            t = Turn(notebook, page, target, direction, if (back) other else shown, if (back) shown else other,
                leafBounds, if (back) RectF(shownBounds) else targetBounds, corner, corner.x, corner.x - 2 * leafBounds.width(), addPage = end,
                live = canReveal())
            begin(t, corner, dy)
        }
        follow(t, dx, dy)
    }
    /** For tests: the turn leaves the page on screen to the editor underneath ([Turn.live]). */
    internal var usedLivePage = false; private set
    /** For tests: the last drawn roll, as (n.x, n.y, axis, radius); the roll spans n·p in [axis, axis + radius]. */
    internal var rollForTest: FloatArray? = null; private set
    /**
     * The page on screen as its cached preview. P587: while the editor underneath shows that page fully drawn ([Turn.live]),
     * the preview is only drawn where the page rises into the roll; everywhere it still lies flat, the editor's own sharp
     * drawing stays in view. (P581 copied the whole page from the editor when a finger came down, about 45 ms on the tablet
     * each time, which held up the start of every turn.)
     */
    private fun currentPage(notebook: Notebook, page: Page, bounds: RectF): Pair<Bitmap, RectF>? =
        thumbnails.cached(notebook, page.id, PREVIEW_WIDTH)?.let { it to RectF(bounds) }
    /**
     * P587: told when a turn starts (true) and when this view lets go of it (false), so the editor can keep its page as one
     * finished picture meanwhile instead of redrawing all its writing under the turning page on every frame.
     */
    var onTurning: (Boolean) -> Unit = {}
    private var turning = false
    private fun begin(t: Turn, corner: PointF, dy: Float) {
        if (!turning) { turning = true; onTurning(true) }
        removeCallbacks(reveal); animate().cancel(); revealed = false
        current = t; tip.set(t.start, corner.y); visibility = VISIBLE; alpha = 1f; baseDy = dy; usedLivePage = t.live
        samples.clear()
        // The back of the turning sheet is its own paper, sampled just inside a corner where ink rarely reaches.
        backPaper = t.leaf.let { it.getPixel((it.width * .02f).toInt().coerceIn(0, it.width - 1), (it.height * .5f).toInt().coerceIn(0, it.height - 1)) } or 0xff000000.toInt()
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
            val (shown, shownBounds) = currentPage(notebook, page, bounds) ?: return false
            val corner = PointF(shownBounds.right, if (originY < bounds.centerY()) shownBounds.top else shownBounds.bottom)
            t = Turn(notebook, page, page, TurnDirection.PREVIOUS, shown, endSheet(shownBounds),
                RectF(shownBounds), RectF(shownBounds), corner, corner.x, corner.x - 2 * shownBounds.width(), addPage = true)
            begin(t, corner, dy)
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
        // P584: the corner leads, rising a little toward the page's middle as it is pulled, as a held corner does; pulled
        // straight across, the fold ran down the whole edge for the first few pixels (a line flashing along the right side).
        val inward = if (t.corner.y <= t.leafBounds.centerY()) 1f else -1f
        val lean = min(along * .5f, t.leafBounds.height() * .05f)
        bindToSpine(t, t.start + sign * along, t.corner.y + dy - baseDy + inward * lean)
        val now = SystemClock.uptimeMillis()
        samples.addLast(now to tip.x)
        while (samples.size > 2 && now - samples.first().first > 90) samples.removeFirst()
        invalidate()
    }
    /** Recent (time, tip x) pairs, for the speed the page is let go at. */
    private val samples = ArrayDeque<Pair<Long, Float>>()
    /** The tip's speed along x when let go, in pixels per millisecond; 0 when the finger had stopped. */
    private fun releaseSpeed(): Float {
        if (samples.size < 2) return 0f
        val (t0, x0) = samples.first(); val (t1, x1) = samples.last()
        if (t1 - t0 < 8 || SystemClock.uptimeMillis() - t1 > 60) return 0f
        return (x1 - x0) / (t1 - t0)
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
        // P584: the roll starts πr/2 before the fold, so a corner on the fold was already lifting into it: pulled far down,
        // the spine's corner came off. Both spine corners stay flat: |S − tip|² ≤ |S − corner|² − (πr + 4)·length.
        val farY = if (cornerY <= bounds.centerY()) bounds.bottom else bounds.top
        repeat(3) {
            val length = hypot(t.corner.x - tip.x, t.corner.y - tip.y)
            if (length < 1f) return
            val margin = (PI.toFloat() * curlRadius(length, width) + 4f) * length
            for (sy in floatArrayOf(cornerY, farY)) {
                val sx = bounds.left
                val limit = ((t.corner.x - sx).pow(2) + (t.corner.y - sy).pow(2) - margin).coerceAtLeast(0f)
                val ox = tip.x - sx; val oy = tip.y - sy; val apart = ox * ox + oy * oy
                if (apart > limit && apart > 0f) { val k = sqrt(limit / apart); tip.set(sx + ox * k, sy + oy * k) }
            }
        }
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
            lastDirection = t.direction
            if (!(if (t.addPage) endCard?.open() == true else turn(t.direction))) { cancel(); return }
            revealDeadline = SystemClock.uptimeMillis() + 2000
            invalidate(); postOnAnimation(reveal)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { tip.set(endX, t.corner.y); finish(); return }
        landing = { tip.set(endX, t.corner.y); finish() }
        // P581: the page carries on at the speed it was let go and eases to rest (a cubic Hermite curve that starts at that
        // speed and never overshoots); its corner sweeps in a slight arc toward the page's middle, as paper does.
        val distance = abs(endX - startX)
        val toward = if (endX >= startX) 1f else -1f
        val speed = (releaseSpeed() * toward).coerceAtLeast(0f)
        // P587: 0.2 to 0.42 s let go still, down to 0.16 s for a quick flick (P581's 0.26 to 0.64 s felt slow on the tablet).
        val calm = 200f + 220f * distance / (t.leafBounds.width() * 2f)
        val time = if (speed > .05f && distance > 1f) (3 * distance / speed).coerceIn(160f, calm) else calm
        val push = min(speed * time, 3 * distance)
        val sweep = t.leafBounds.height() * .045f * (if (t.corner.y <= t.leafBounds.centerY()) 1f else -1f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = time.toLong()
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a -> val s = a.animatedValue as Float
                val s2 = s * s; val s3 = s2 * s
                val eased = (-2 * s3 + 3 * s2) + (if (distance > 0f) push / distance else 0f) * (s3 - 2 * s2 + s)
                val x = startX + (endX - startX) * eased
                val across = if (t.turnedX == t.restX) 0f else ((x - t.restX) / (t.turnedX - t.restX)).coerceIn(0f, 1f)
                val arc = sin(PI.toFloat() * across) * sweep * (s2 * (3 - 2 * s))
                tip.set(x, startY + (t.corner.y - startY) * eased.coerceIn(0f, 1f) + arc); invalidate()
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finish() } })
            start()
        }
    }
    private val reveal = object : Runnable {
        override fun run() {
            if (!revealed) return
            if (queued.isNotEmpty()) {
                if (startQueued()) return
                // The next page's preview is still being made; wait for it a little, holding this page meanwhile.
                if (SystemClock.uptimeMillis() < queuedDeadline) { postOnAnimation(this); return }
                queued.clear()
            }
            if (canReveal() || SystemClock.uptimeMillis() >= revealDeadline) fadeOut() else postOnAnimation(this)
        }
    }
    /**
     * P586: swipes that came while a landed page was held but too soon to draw their turn (the next page's preview not
     * made yet). Each plays as an ordinary turn as soon as it can, so a quick flick is neither lost nor shown as a bare page.
     */
    private val queued = ArrayDeque<TurnDirection>()
    private var queuedDeadline = 0L
    private val lastBounds = RectF()
    fun queue(direction: TurnDirection) {
        if (!holding || lastBounds.isEmpty) return
        // A swipe back cancels one still waiting forward, and the other way round.
        if (queued.lastOrNull()?.let { it != direction } == true) queued.removeAt(queued.size - 1) else queued.addLast(direction)
        queuedDeadline = SystemClock.uptimeMillis() + 2000
        animate().cancel(); alpha = 1f
        removeCallbacks(reveal); postOnAnimation(reveal)
    }
    private fun startQueued(): Boolean {
        val direction = queued.firstOrNull() ?: return false
        val b = RectF(lastBounds)
        val sign = if (direction == TurnDirection.NEXT) -1f else 1f
        drag(if (direction == TurnDirection.NEXT) b.right - 1f else b.left + 1f, b.bottom - b.height() * .2f, sign * 2f, 0f, b)
        if (revealed) return false
        queued.removeFirst()
        release(direction)
        return true
    }
    /** P581: the landed page melts into the live one underneath instead of being swapped for it in a single frame. */
    private fun fadeOut() {
        if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); return }
        val start = SystemClock.uptimeMillis()
        animate().alpha(0f).setDuration(170).setInterpolator(DecelerateInterpolator())
            .withEndAction { lastFadeForTest = SystemClock.uptimeMillis() - start; cancel() }
    }
    /** For tests (P589): how long the last landed page took to melt away, in ms (-1 before any). */
    internal var lastFadeForTest = -1L
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
        animate().cancel()
        removeCallbacks(reveal); queued.clear(); current = null; revealed = false; visibility = INVISIBLE; alpha = 1f; invalidate()
        shadowNode.discardDisplayList()
        if (turning) { turning = false; onTurning(false) }
    }
    fun dispose() { cancel(); stopRequests(); warmed = emptyList() }
    override fun onDetachedFromWindow() { cancel(); stopRequests(); warmed = emptyList(); super.onDetachedFromWindow() }

    /**
     * P581: the leaf rolls over a cylinder instead of folding along a crease. Seen from above, the flat part of the page
     * stays put up to the roll's axis; the roll shows the paper's back, shaded from its dark outer rim to a lit top; past
     * the roll the back lies flat over the page, its free corner exactly at [tip]. The radius grows as the page lifts and
     * shrinks to nothing as it lands, so it starts and ends flat. Meshes, not clip paths, so every edge is smooth.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = current ?: return
        // Once turned, only the destination shows until the editor has drawn it: the under page forward, the leaf back.
        if (revealed) { canvas.drawColor(deskColor); if (t.back) sheet(canvas, t.leaf, t.leafBounds) else sheet(canvas, t.under, t.underBounds); return }
        // P587: with the editor showing the page on screen ([Turn.live]), that page is left in view wherever it still lies
        // flat: the turning page up to its roll (forward), or the page underneath (back). Otherwise all is drawn here.
        val forwardLive = t.live && !t.back
        if (!t.live) { canvas.drawColor(deskColor); sheet(canvas, t.under, t.underBounds) }
        val length = hypot(t.corner.x - tip.x, t.corner.y - tip.y)
        if (length < 1f) { if (!forwardLive) canvas.drawBitmap(t.leaf, null, t.leafBounds, bitmapPaint); return }
        val nx = (t.corner.x - tip.x) / length; val ny = (t.corner.y - tip.y) / length
        val width = t.leafBounds.width()
        val r = curlRadius(length, width)
        // The roll's axis: where the corner, carried round the roll and laid flat again, lands on the tip.
        val axis = ((t.corner.x + tip.x) * nx + (t.corner.y + tip.y) * ny) / 2 - PI.toFloat() * r / 2
        val density = resources.displayMetrics.density
        bend(t, nx, ny, axis, r)
        rollForTest = floatArrayOf(nx, ny, axis, r)
        // 1. The roll's soft shadow on whatever lies under the lifted part. Under the roll itself the page is evenly in its
        // shade (P583: it showed bare and bright where the rolled top or bottom edge lets the page under it through).
        // Forward over the editor: where the page has lifted off, the next page shows (from a pixel past the axis, so the
        // clip's soft edge lies under the roll's own drawing).
        if (forwardLive) {
            canvas.save(); canvas.clipPath(halfPlane(nx, ny, axis + 1f))
            canvas.drawColor(deskColor); sheet(canvas, t.under, t.underBounds)
            canvas.restore()
        }
        corners(t.leafBounds)
        val under = (70 * lift(length, width)).toInt() + 20
        // P588: one shade from the axis out, even under the roll and fading past its rim. (P583 to P587 drew the roll and
        // beyond it as two bands that met in a bright line at the rim, lighter on one side: a second sheet's edge.)
        val reach = r * 2.3f + 10 * density
        band(canvas, nx, ny, axis, reach, under, true, underGradient(r / reach))
        // 2. The page itself, slightly darker where it starts to rise; over the editor, only where it rises.
        // P588: and never past the roll's rim. Beyond its top the front is squeezed onto the rim line, where the smoothing of
        // its edge let that squeezed paper and its ruled lines through as a fine dotted line along the back's edge.
        canvas.save(); canvas.clipPath(strip(nx, ny, if (forwardLive) axis - .5f else -1e9f, axis + r - .5f))
        canvas.drawBitmapMesh(t.leaf, COLUMNS, ROWS, frontMesh, 0, null, 0, bitmapPaint)
        canvas.restore()
        val rise = (25 * min(1f, r / 6f)).toInt()
        band(canvas, nx, ny, axis, r * 1.4f + 1f, rise, false, riseGradient)
        // P587: the same shade carries on over the rising page, so where the page's top or bottom edge shows the inside of
        // the roll, it is this page, its lines and writing, bending up (P586 painted it blank: a white patch under the corner).
        // P588: only over that page itself. Over the whole strip it also darkened the page underneath beside the roll, a
        // strip with straight edges of its own up to the top corner, like a second sheet turning with the first.
        val rising = frontRise(t, nx, ny, axis)
        if (!rising.isEmpty) {
            place(slabGradient, nx, ny, axis, r); shade.shader = slabGradient; shade.alpha = rise.coerceIn(0, 255)
            canvas.drawPath(rising, shade); shade.alpha = 255
        }
        // 3. The soft shadow the turned-over sheet casts on the page, a little away from the roll, then the back: its own
        // paper, shaded round the roll, and a fine line along its free edges (not along the roll, which its shading shows).
        val outline = backOutline(t, nx, ny, axis, r)
        val softness = 5f * density + r * .25f; val strength = (.14f + .2f * lift(length, width)).coerceIn(0f, .32f)
        val offset = density + r * .06f
        castShadow(canvas, outline, -nx * offset, -ny * offset, softness, strength)
        shadeBack(nx, ny, axis, r)
        if (!outline.isEmpty) { canvas.drawPath(outline, backShade); canvas.drawPath(outline, edge) }
    }
    /** How far the page is into its turn, 0 flat to 1 at the middle, back to 0 when it lies turned. */
    private fun lift(length: Float, width: Float) = sin(PI.toFloat() * (length / (2 * width)).coerceIn(0f, 1f)).coerceAtLeast(0f)
    /** The roll is widest mid-turn; it can never be so wide that the corner would still be on it. */
    private fun curlRadius(length: Float, width: Float) =
        min(width * .085f * lift(length, width).pow(.6f), length / PI.toFloat() * .9f).coerceAtLeast(.01f)
    /** Where every grid point of the leaf lands on its front (flat, or rising into the roll) and on its back. */
    private fun bend(t: Turn, nx: Float, ny: Float, axis: Float, r: Float) {
        val b = t.leafBounds
        val half = PI.toFloat() * r / 2; val whole = PI.toFloat() * r
        for (row in 0..ROWS) for (col in 0..COLUMNS) {
            val i = row * (COLUMNS + 1) + col
            val x = b.left + b.width() * col / COLUMNS; val y = b.top + b.height() * row / ROWS
            val d = x * nx + y * ny - axis
            // Front: unchanged before the axis, rising into the roll (hidden under it from above), squeezed to its rim beyond.
            // (Past the roll's top the front is hidden; it is tucked just inside the roll so no ink peeks out at its rim.)
            val front = when { d <= 0f -> 0f; d <= half -> r * sin(d / r) - d; else -> r - d }
            frontMesh[i * 2] = x + nx * front; frontMesh[i * 2 + 1] = y + ny * front
            // Back: on the upper half of the roll, then flat over the page.
            val back = when { d <= half -> r - d; d <= whole -> r * sin(d / r) - d; else -> -d - (d - whole) }
            backMesh[i * 2] = x + nx * back; backMesh[i * 2 + 1] = y + ny * back
        }
    }
    /** How lit the back is at [d] past the axis: dark at the roll's outer rim, lit along its top with a soft sheen, then plain. */
    private fun light(d: Float, r: Float): Float {
        fun round(angle: Float) =
            (.70f - .24f * cos(angle) + .06f * exp(-((angle - PI.toFloat() * .8f) / (PI.toFloat() * .12f)).pow(2))).coerceAtMost(1f)
        val whole = PI.toFloat() * r
        if (d < whole) return round((d / r).coerceIn(PI.toFloat() / 2, PI.toFloat()))
        val flat = ((d - whole) / (.3f * r)).coerceIn(0f, 1f)
        return round(PI.toFloat()) + (.97f - round(PI.toFloat())) * flat
    }
    /**
     * Sets [backShade] to the back's shading as a screen gradient across the roll: from its rim (r past the axis, seen from
     * above) over its top, then flat, plain paper .3r past the axis on the other side.
     */
    private fun shadeBack(nx: Float, ny: Float, axis: Float, r: Float) {
        if (r < 1f) { backShade.shader = null; backShade.color = lit(backPaper, .97f); return }
        val whole = PI.toFloat() * r
        for (k in 0 until SHADE_STOPS) {
            val f = k / (SHADE_STOPS - 1f)
            // Evenly along the paper (d from the rim at πr/2 to .3r past the roll), placed where each point lies on screen.
            val d = whole / 2 + (whole / 2 + .3f * r) * f
            val s = if (d <= whole) r * sin(d / r) else whole - d
            shadeStops[k] = ((r - s) / (1.3f * r)).coerceIn(0f, 1f); shadeColors[k] = lit(backPaper, light(d, r))
        }
        for (k in 1 until SHADE_STOPS) if (shadeStops[k] < shadeStops[k - 1]) shadeStops[k] = shadeStops[k - 1]
        val from = axis + r; val to = axis - .3f * r
        backShade.shader = LinearGradient(nx * from, ny * from, nx * to, ny * to, shadeColors, shadeStops, Shader.TileMode.CLAMP)
    }
    private fun lit(color: Int, level: Float): Int =
        Color.rgb((Color.red(color) * level).roundToInt().coerceIn(0, 255), (Color.green(color) * level).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * level).roundToInt().coerceIn(0, 255))
    /**
     * P587: on screen the shadow is blurred by the graphics chip, in a layer just big enough for it. Worked out on the
     * processor (below, still used when drawing into a bitmap), it took about 25 ms a frame on the tablet: the turn ran at
     * 26 to 30 frames a second on a 120 Hz screen.
     */
    private val shadowNode = RenderNode("page turn shadow")
    private val shadowBounds = RectF()
    /** The turned-over sheet's shadow: [outline] moved by ([dx], [dy]), blurred over about [softness], at [strength]. */
    @Suppress("DEPRECATION") // Path.computeBounds(RectF) is API 34.
    private fun castShadow(canvas: Canvas, outline: Path, dx: Float, dy: Float, softness: Float, strength: Float) {
        if (outline.isEmpty || strength <= 0f || width <= 0 || height <= 0) return
        if (canvas.isHardwareAccelerated) {
            // The spread of two box blurs of softness / 2 at 1/SHADOW_SCALE size: sigma = (softness + SHADOW_SCALE) / 2.45.
            val sigma = (softness + SHADOW_SCALE) / 2.45f
            val pad = ceil(sigma * 3f + 2f)
            outline.computeBounds(shadowBounds, true)
            shadowBounds.offset(dx, dy); shadowBounds.inset(-pad, -pad)
            if (!shadowBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return
            val left = floor(shadowBounds.left).toInt(); val top = floor(shadowBounds.top).toInt()
            shadowNode.setPosition(left, top, ceil(shadowBounds.right).toInt(), ceil(shadowBounds.bottom).toInt())
            val c = shadowNode.beginRecording()
            try {
                c.translate(dx - left, dy - top)
                maskPaint.color = Color.argb((255 * strength).toInt().coerceIn(0, 255), 0, 0, 0)
                c.drawPath(outline, maskPaint)
            } finally { shadowNode.endRecording() }
            // RenderEffect's blur takes a radius, which it turns into sigma = 0.57735 * radius + 0.5.
            val radius = ((sigma - .5f) / .57735f).coerceAtLeast(.1f)
            shadowNode.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
            canvas.drawRenderNode(shadowNode)
            return
        }
        maskPaint.color = Color.BLACK
        val w = width / SHADOW_SCALE + 2; val h = height / SHADOW_SCALE + 2
        val mask = shadowMask?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            shadowMask = it; shadowPixels = IntArray(w * h); shadowBlur = IntArray(w * h); shadowTemp = IntArray(w * h)
        }
        mask.eraseColor(0)
        val c = Canvas(mask)
        maskMatrix.setScale(1f / SHADOW_SCALE, 1f / SHADOW_SCALE); maskMatrix.preTranslate(dx, dy)
        c.concat(maskMatrix); c.drawPath(outline, maskPaint)
        mask.getPixels(shadowPixels, 0, w, 0, 0, w, h)
        for (i in 0 until w * h) shadowBlur[i] = shadowPixels[i] ushr 24
        // Two box blurs, each half the spread, come close to a Gaussian. The radius is fractional, so the shadow softens
        // smoothly as the page lifts (P583's whole steps showed as small jumps on a slow pull).
        val radius = (softness / SHADOW_SCALE / 2f).coerceAtLeast(1f)
        repeat(2) { boxBlur(shadowBlur, shadowTemp, w, h, radius) }
        for (i in 0 until w * h) shadowPixels[i] = shadowBlur[i] shl 24
        mask.setPixels(shadowPixels, 0, w, 0, 0, w, h)
        shadowPaint.alpha = (255 * strength).toInt().coerceIn(0, 255)
        shadowRect.set(0f, 0f, w * SHADOW_SCALE.toFloat(), h * SHADOW_SCALE.toFloat())
        canvas.drawBitmap(mask, null, shadowRect, shadowPaint)
    }
    /**
     * A box blur of radius [r] across, then down, [values] (w by h, 0..255), through [temp]. Outside counts as clear. A
     * fractional radius weighs the two taps just outside the whole part by its fraction.
     */
    private fun boxBlur(values: IntArray, temp: IntArray, w: Int, h: Int, r: Float) {
        val whole = r.toInt(); val part = r - whole
        val norm = 1f / (2 * whole + 1 + 2 * part)
        fun pass(from: IntArray, to: IntArray, lines: Int, length: Int, step: Int, stride: Int) {
            for (line in 0 until lines) {
                val base = line * stride
                fun at(k: Int) = if (k in 0 until length) from[base + k * step] else 0
                var sum = 0
                for (k in 0..minOf(whole, length - 1)) sum += from[base + k * step]
                for (k in 0 until length) {
                    to[base + k * step] = ((sum + part * (at(k - whole - 1) + at(k + whole + 1))) * norm).roundToInt()
                    sum += at(k + whole + 1) - at(k - whole)
                }
            }
        }
        pass(values, temp, h, w, 1, w)
        pass(temp, values, w, h, w, 1)
    }
    /** The back's outline: the leaf's border, carried onto the back, wherever the back shows. */
    private fun backOutline(t: Turn, nx: Float, ny: Float, axis: Float, r: Float): Path {
        outlinePath.rewind()
        val half = PI.toFloat() * r / 2
        val count = BORDER.size
        fun d(i: Int) = grid(t, BORDER[i], 0) * nx + grid(t, BORDER[i], 1) * ny - axis
        // P584: left open across the roll's rim, so the fine edge line follows only the sheet's own edges (a filled path
        // still closes it). Drawn closed, the line ran straight down the roll from top to bottom.
        val start = (0 until count).firstOrNull { d(it) < half } ?: return outlinePath
        var first = true
        fun add(x: Float, y: Float) { if (first) outlinePath.moveTo(x, y) else outlinePath.lineTo(x, y); first = false }
        for (k in 0 until count) {
            val i = (start + k) % count; val j = (i + 1) % count
            val di = d(i); val dj = d(j)
            if (di >= half) add(backMesh[BORDER[i] * 2], backMesh[BORDER[i] * 2 + 1])
            if ((di >= half) != (dj >= half)) {
                val f = (half - di) / (dj - di)
                val x = grid(t, BORDER[i], 0) + (grid(t, BORDER[j], 0) - grid(t, BORDER[i], 0)) * f
                val y = grid(t, BORDER[i], 1) + (grid(t, BORDER[j], 1) - grid(t, BORDER[i], 1)) * f
                add(x + nx * (r - half), y + ny * (r - half))
            }
        }
        return outlinePath
    }
    private fun grid(t: Turn, vertex: Int, axis: Int): Float {
        val b = t.leafBounds
        return if (axis == 0) b.left + b.width() * (vertex % (COLUMNS + 1)) / COLUMNS else b.top + b.height() * (vertex / (COLUMNS + 1)) / ROWS
    }
    /**
     * A shadow fading over [reach] from the line where n·p = [from], over the part of [sourceCorners] on that side. P587: only
     * the strip the shade covers is filled (every gradient ends clear), not the whole rest of the page: four bands each filled
     * up to half the page every frame, about 2 ms of the graphics chip's time on the tablet.
     */
    private fun band(canvas: Canvas, nx: Float, ny: Float, from: Float, reach: Float, alpha: Int, beyond: Boolean, gradient: LinearGradient = shadowGradient) {
        val toward = if (beyond) reach else -reach
        val side = clip(nx, ny, from, beyond, stripPoints)
        val count = clipPolygon(stripPoints, side, nx, ny, from + toward + if (beyond) 1f else -1f, !beyond, shadowPoints)
        if (count < 3) return
        place(gradient, nx, ny, from, toward)
        shade.shader = gradient; shade.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path(shadowPath, shadowPoints, count), shade)
        shade.alpha = 255
    }
    /** Lays [gradient] (0 to 1 along x) across the screen from the line n·p = [from] to n·p = [from] + [toward]. */
    private fun place(gradient: LinearGradient, nx: Float, ny: Float, from: Float, toward: Float) {
        matrixValues[0] = nx * toward; matrixValues[1] = -ny; matrixValues[2] = nx * from
        matrixValues[3] = ny * toward; matrixValues[4] = nx; matrixValues[5] = ny * from
        gradientMatrix.setValues(matrixValues); gradient.setLocalMatrix(gradientMatrix)
    }
    private var underShade: LinearGradient? = null
    private var underRoll = -1f
    /**
     * P588: the page underneath in the roll's shadow: even across the roll ([roll] of the way), then fading as
     * [shadowGradient] does. Made again only when the roll's share changes noticeably.
     */
    private fun underGradient(roll: Float): LinearGradient {
        val k = roll.coerceIn(0f, .9f)
        underShade?.takeIf { abs(underRoll - k) < .004f }?.let { return it }
        return LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0x55000000, 0),
            floatArrayOf(0f, k, k + .35f * (1 - k), 1f), Shader.TileMode.CLAMP).also { underShade = it; underRoll = k }
    }
    private val risePath = Path()
    /** P588: the turning page's front from the axis on (the part rising into the roll), from the grid's border. */
    private fun frontRise(t: Turn, nx: Float, ny: Float, axis: Float): Path {
        risePath.rewind()
        val count = BORDER.size
        fun d(i: Int) = grid(t, BORDER[i], 0) * nx + grid(t, BORDER[i], 1) * ny - axis
        var first = true
        fun add(x: Float, y: Float) { if (first) risePath.moveTo(x, y) else risePath.lineTo(x, y); first = false }
        for (i in 0 until count) {
            val j = (i + 1) % count
            val di = d(i); val dj = d(j)
            if (di >= 0f) add(frontMesh[BORDER[i] * 2], frontMesh[BORDER[i] * 2 + 1])
            if ((di >= 0f) != (dj >= 0f)) {
                // On the axis the front has not moved: the grid point itself.
                val f = -di / (dj - di)
                add(grid(t, BORDER[i], 0) + (grid(t, BORDER[j], 0) - grid(t, BORDER[i], 0)) * f,
                    grid(t, BORDER[i], 1) + (grid(t, BORDER[j], 1) - grid(t, BORDER[i], 1)) * f)
            }
        }
        if (!first) risePath.close()
        return risePath
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
    private val viewCorners = FloatArray(8)
    private val planePoints = FloatArray(12)
    private val planePath = Path()
    /** The part of this view where n·p >= [from], as a path to clip to (empty when there is none). */
    private fun halfPlane(nx: Float, ny: Float, from: Float): Path {
        val w = width.toFloat(); val h = height.toFloat()
        viewCorners[0] = 0f; viewCorners[1] = 0f; viewCorners[2] = w; viewCorners[3] = 0f
        viewCorners[4] = w; viewCorners[5] = h; viewCorners[6] = 0f; viewCorners[7] = h
        return path(planePath, planePoints, clip(nx, ny, from, true, planePoints, viewCorners))
    }
    private val stripPoints = FloatArray(12)
    private val slabPoints = FloatArray(16)
    private val slabPath = Path()
    /** P588: the part of this view where [from] <= n·p <= [to], as a path to clip to. */
    private fun strip(nx: Float, ny: Float, from: Float, to: Float): Path {
        val w = width.toFloat(); val h = height.toFloat()
        viewCorners[0] = 0f; viewCorners[1] = 0f; viewCorners[2] = w; viewCorners[3] = 0f
        viewCorners[4] = w; viewCorners[5] = h; viewCorners[6] = 0f; viewCorners[7] = h
        val side = clip(nx, ny, from, true, planePoints, viewCorners)
        return path(slabPath, slabPoints, clipPolygon(planePoints, side, nx, ny, to, false, slabPoints))
    }
    private fun clip(nx: Float, ny: Float, offset: Float, positive: Boolean, out: FloatArray, corners: FloatArray = sourceCorners): Int =
        clipPolygon(corners, 4, nx, ny, offset, positive, out)
    /** The part of the convex polygon [corners] ([size] points) where n·p >= [offset] ([positive]) or <= it, into [out]. */
    private fun clipPolygon(corners: FloatArray, size: Int, nx: Float, ny: Float, offset: Float, positive: Boolean, out: FloatArray): Int {
        var count = 0
        for (i in 0 until size) {
            val j = i * 2; val k = ((i + 1) % size) * 2
            val ax = corners[j]; val ay = corners[j + 1]
            val bx = corners[k]; val by = corners[k + 1]
            val da = (ax * nx + ay * ny - offset) * if (positive) 1 else -1
            val db = (bx * nx + by * ny - offset) * if (positive) 1 else -1
            if (da >= 0) { out[count * 2] = ax; out[count * 2 + 1] = ay; count++ }
            if ((da >= 0) != (db >= 0)) {
                val f = da / (da - db); out[count * 2] = ax + (bx - ax) * f; out[count * 2 + 1] = ay + (by - ay) * f; count++
            }
        }
        return count
    }
    companion object {
        private const val PREVIEW_WIDTH = 1024
        /** The turned-over sheet's shadow is worked out at 1/6 of the screen's size; it is soft, so nothing is lost. */
        private const val SHADOW_SCALE = 4
        /** Colour stops across the roll's shading gradient. */
        private const val SHADE_STOPS = 40
        private const val COLUMNS = 30
        private const val ROWS = 42
        private const val VERTICES = (COLUMNS + 1) * (ROWS + 1)
        /** The grid's border vertices in order round the sheet: top, right side, bottom, left side. */
        private val BORDER = IntArray(2 * (COLUMNS + ROWS)).also { out ->
            var k = 0
            for (col in 0 until COLUMNS) out[k++] = col
            for (row in 0 until ROWS) out[k++] = row * (COLUMNS + 1) + COLUMNS
            for (col in COLUMNS downTo 1) out[k++] = ROWS * (COLUMNS + 1) + col
            for (row in ROWS downTo 1) out[k++] = row * (COLUMNS + 1)
        }
    }
}
