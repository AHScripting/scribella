package dev.ownednotes.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.TurnDirection
import kotlin.math.*

/** Landscape two-page spread (N003): pages pair 1–2, 3–4, … like an open book. Geometry only; never touches the document. */
object BookSpread {
    /** Index of the left page of the spread holding [index]. */
    fun start(index: Int) = index - index % 2
    /** The other page of [index]'s spread, or null for the last page of an odd notebook. */
    fun partner(index: Int, count: Int): Int? = (if (index % 2 == 0) index + 1 else index - 1).takeIf { it in 0 until count }

    /**
     * P677: the notebook's spreads in reading order, as page indexes (one or two each). Pages pair 1-2, 3-4, ... until
     * something breaks the rhythm, and the pages after it pair on from there: a pair the user chose ([chosen]: the page it
     * was chosen from, then the page shown beside it) stays together where the first of those lies, the earlier page on
     * the left; landscape paper shows alone. Pairing strictly by page number left the pages around a chosen pair alone
     * and a turn back from it landed on the pair again.
     */
    fun groups(pages: List<Page>, chosen: List<Pair<String, String>>): List<IntArray> {
        val at = HashMap<String, Int>(pages.size * 2); pages.forEachIndexed { i, p -> at[p.id] = i }
        val mate = HashMap<Int, Int>(); val anchor = HashSet<Int>()
        chosen.forEach { (from, beside) ->
            val i = at[from] ?: return@forEach; val j = at[beside] ?: return@forEach
            if (i != j && i !in mate && j !in mate) { mate[i] = j; mate[j] = i; anchor += i }
        }
        fun wide(i: Int) = pages[i].width > pages[i].height
        val placed = BooleanArray(pages.size); val out = ArrayList<IntArray>()
        for (i in pages.indices) {
            if (placed[i]) continue
            val m = mate[i]
            val group = when {
                m != null && i in anchor -> intArrayOf(minOf(i, m), maxOf(i, m))
                m != null -> continue // shown with the page it was chosen from, where that page lies
                wide(i) -> intArrayOf(i)
                i + 1 < pages.size && !placed[i + 1] && i + 1 !in mate && !wide(i + 1) -> intArrayOf(i, i + 1)
                else -> intArrayOf(i)
            }
            group.forEach { placed[it] = true }; out += group
        }
        return out
    }
    /** Whether spread [g] of [groups] shows as two slots: a pair, or a portrait last page with the right slot empty. */
    fun isSpread(groups: List<IntArray>, g: Int, pages: List<Page>): Boolean {
        val group = groups.getOrNull(g) ?: return false
        return group.size == 2 || (group[0] == pages.lastIndex && pages[group[0]].let { it.width <= it.height })
    }

    /**
     * Screen slots for a spread: both pages share one height and meet at the spine. Without a right page the right
     * slot keeps the left page's shape, so the left page does not jump when the last page of an odd notebook is reached.
     */
    fun slots(viewWidth: Float, viewHeight: Float, margin: Float, left: Page, right: Page?, outLeft: RectF, outRight: RectF) {
        val aL = left.width / left.height
        val aR = (right ?: left).let { it.width / it.height }
        val h = min(viewHeight - 2 * margin, (viewWidth - 2 * margin) / (aL + aR)).coerceAtLeast(1f)
        val wL = aL * h; val wR = aR * h
        val x = (viewWidth - wL - wR) / 2; val y = (viewHeight - h) / 2
        outLeft.set(x, y, x + wL, y + h); outRight.set(x + wL, y, x + wL + wR, y + h)
    }

    /** Soft shading where a page curves into the spine. Reuses one shader; no per-frame allocation. */
    class Gutter {
        private val gradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0x00000000, 0x06000000, 0x1c000000), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        private val local = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        /** Shades the [fraction] of [page] next to the spine; [alpha] scales the whole shade. */
        fun draw(canvas: Canvas, page: RectF, spineOnRight: Boolean, fraction: Float = .07f, alpha: Int = 255) {
            val w = page.width() * fraction
            local.reset()
            if (spineOnRight) { local.setScale(w, 1f); local.postTranslate(page.right - w, 0f) }
            else { local.setScale(-w, 1f); local.postTranslate(page.left + w, 0f) }
            gradient.setLocalMatrix(local); paint.alpha = alpha
            if (spineOnRight) canvas.drawRect(page.right - w, page.top, page.right, page.bottom, paint)
            else canvas.drawRect(page.left, page.top, page.left + w, page.bottom, paint)
        }
    }
}

/**
 * Presentation only: while a finger turns a spread, the sheet rotates about the spine with perspective, showing its
 * front, then its back landing on the other side. Optionally the other side shows through faintly. Previews come from
 * [PageThumbnails]; the live editor returns once the turn settles. Never owns ink, history or selection; UI thread only.
 */
@SuppressLint("ViewConstructor") // Programmatic-only view needs repository/renderer dependencies.
class BookFlipView(
    context: Context,
    private val repository: NotebookRepository,
    private val thumbnails: PageThumbnails,
    private val jump: (Int) -> Boolean,
    private val canReveal: () -> Boolean,
) : View(context) {
    /** Faces are named by where they lie flat: [rightFace] on the right slot, [leftFace] on the left. */
    private class Flip(val notebook: Notebook, val page: Page, val target: Page, val offset: Int, val direction: TurnDirection,
        val staticLeft: Bitmap, val staticRight: Bitmap?, val leftFace: Bitmap, val rightFace: Bitmap, val left: RectF, val right: RectF)
    private val requests = mutableListOf<PageThumbnails.Request>()
    private var warmed: List<PageThumbnails.Key> = emptyList()
    private var warming = 0
    private var current: Flip? = null
    /** P585: called when the turning sheet appears or goes, so the editor can hide what lies over the slots. */
    var onShownChanged: () -> Unit = {}
    /** 0 = the sheet lies on the right slot, PI = on the left. */
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private var revealed = false
    private var revealDeadline = 0L
    var seeThrough = true
    var deskColor: Int = Tokens.DESK
    val active get() = current != null
    val settling get() = animator != null || revealed
    /** 0 at rest, 1 when the sheet has landed on the destination side. */
    val progress: Float get() = current?.let { if (it.direction == TurnDirection.NEXT) angle / PI.toFloat() else 1 - angle / PI.toFloat() } ?: 0f
    val previewsReady: Boolean get() {
        val notebook = repository.session?.notebook ?: return false
        return window(notebook).all { thumbnails.cached(notebook, it.id, PREVIEW_WIDTH) != null }
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val throughPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.MULTIPLY; alpha = 46 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x33000000 }
    private val castGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0x38000000, 0x12000000, 0x00000000), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
    private val castPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = castGradient }
    private val castMatrix = Matrix()
    private val gutter = BookSpread.Gutter()
    private val sheet = Matrix()
    private val through = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val faceBounds = RectF()
    private val union = RectF()
    init { visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** P677: the notebook's spreads ([BookSpread.groups]); the editor adds the pairs the user chose. */
    var spreads: (Notebook) -> List<IntArray> = { BookSpread.groups(it.pages, emptyList()) }
    /** The pages a turn from the current spread can show: previous, current and next spreads. */
    private fun window(notebook: Notebook): List<Page> {
        val index = notebook.pages.indexOfFirst { it.id == repository.selectedPageId }
        if (index < 0) return emptyList()
        val groups = spreads(notebook); val g = groups.indexOfFirst { index in it }
        if (g < 0) return emptyList()
        return (g - 1..g + 1).flatMap { groups.getOrNull(it)?.toList().orEmpty() }.map { notebook.pages[it] }
    }

    /** Keep the neighbouring spreads warm so a gesture frame never waits on PDF or ink rendering. */
    fun sync(enabled: Boolean) {
        val notebook = repository.session?.notebook
        current?.let { f ->
            val expected = if (revealed) f.target.id else f.page.id
            if (!enabled || notebook !== f.notebook || repository.selectedPageId != expected) cancel()
        }
        if (!enabled || notebook == null) { stopRequests(); warmed = emptyList(); return }
        val pages = window(notebook)
        val keys = pages.map { thumbnails.key(notebook, it, PREVIEW_WIDTH) }
        if (keys == warmed && pages.all { thumbnails.cached(notebook, it.id, PREVIEW_WIDTH) != null }) return
        if (keys == warmed && warming > 0) return
        stopRequests(); warmed = keys; warming = pages.size
        pages.forEach { page -> requests += thumbnails.request(notebook, page.id, PREVIEW_WIDTH) { result ->
            warming--
            if (result.isFailure) warmed = emptyList() // retry on the next idle refresh; instant turns still work
        } }
    }
    private fun stopRequests() { requests.forEach { it.cancel() }; requests.clear(); warming = 0 }

    /** [left]/[right] are the fitted spread slots in this view's coordinates; [dx] is the finger's total travel. */
    fun drag(dx: Float, left: RectF, right: RectF) {
        if (settling) return
        var f = current
        if (f == null) {
            if (abs(dx) < 1f) return
            f = begin(if (dx < 0) TurnDirection.NEXT else TurnDirection.PREVIOUS, left, right) ?: return
            current = f; angle = if (f.direction == TurnDirection.NEXT) 0f else PI.toFloat(); visibility = VISIBLE; onShownChanged()
        }
        val cos = if (f.direction == TurnDirection.NEXT) 1 + dx / f.right.width() else -1 + dx / f.left.width()
        angle = acos(cos.coerceIn(-1f, 1f))
        invalidate()
    }

    private fun begin(direction: TurnDirection, left: RectF, right: RectF): Flip? {
        val notebook = repository.session?.notebook ?: return null
        val pages = notebook.pages
        val index = pages.indexOfFirst { it.id == repository.selectedPageId }
        val page = pages.getOrNull(index) ?: return null
        val groups = spreads(notebook); val g = groups.indexOfFirst { index in it }
        val here = groups.getOrNull(g) ?: return null
        fun preview(i: Int) = pages.getOrNull(i)?.let { thumbnails.cached(notebook, it.id, PREVIEW_WIDTH) }
        // P677: only between two spreads; a page shown alone turns without the sheet.
        return if (direction == TurnDirection.NEXT) {
            val next = groups.getOrNull(g + 1) ?: return null
            if (here.size < 2 || !BookSpread.isSpread(groups, g + 1, pages)) return null
            // The current right page turns over; the next spread's left page is printed on its back.
            Flip(notebook, page, pages[next[0]], next[0] - index, direction,
                staticLeft = preview(here[0]) ?: return null,
                staticRight = if (next.size > 1) preview(next[1]) ?: return null else null,
                leftFace = preview(next[0]) ?: return null, rightFace = preview(here[1]) ?: return null,
                left = RectF(left), right = RectF(right))
        } else {
            val previous = groups.getOrNull(g - 1) ?: return null
            if (previous.size < 2) return null
            Flip(notebook, page, pages[previous[1]], previous[1] - index, direction,
                staticLeft = preview(previous[0]) ?: return null,
                staticRight = if (here.size > 1) preview(here[1]) ?: return null else null,
                leftFace = preview(here[0]) ?: return null, rightFace = preview(previous[1]) ?: return null,
                left = RectF(left), right = RectF(right))
        }
    }

    /** A pull past 35% or a fast swipe the same way completes; anything else settles back. */
    fun release(fastTurn: TurnDirection? = null) {
        val f = current ?: return
        if (settling) return
        val complete = (fastTurn == f.direction && progress > .01f) || progress >= .35f
        val landed = if (f.direction == TurnDirection.NEXT) PI.toFloat() else 0f
        val rest = PI.toFloat() - landed
        val end = if (complete) landed else rest
        fun finish() {
            animator = null; landing = null
            if (!complete) { cancel(); return }
            // Selection can become unavailable during settling (file picker, pen, notebook switch).
            if (repository.session?.notebook !== f.notebook || repository.selectedPageId != f.page.id) { cancel(); return }
            revealed = true // refresh inside jump() must expect the destination, not the old page
            if (!jump(f.offset)) { cancel(); return }
            revealDeadline = SystemClock.uptimeMillis() + 2000
            invalidate(); postOnAnimation(reveal)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { angle = end; finish(); return }
        landing = { angle = end; finish() }
        val from = angle
        animator = ValueAnimator.ofFloat(from, end).apply {
            duration = (200 + abs(end - from) / PI.toFloat() * 260).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { a -> angle = a.animatedValue as Float; invalidate() }
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
    private var landing: (() -> Unit)? = null
    /** P560: a released turn lands at once, so a finger coming down mid-animation carries on from its destination. */
    fun settleNow() {
        val land = landing ?: return
        animator?.let { it.removeAllListeners(); it.cancel() }
        land()
    }
    fun cancel() {
        animator?.let { it.removeAllListeners(); it.cancel() }; animator = null; landing = null
        val shown = current != null
        removeCallbacks(reveal); current = null; revealed = false; visibility = INVISIBLE; invalidate()
        if (shown) onShownChanged()
    }
    fun dispose() { cancel(); stopRequests(); warmed = emptyList() }
    override fun onDetachedFromWindow() { cancel(); stopRequests(); warmed = emptyList(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = current ?: return
        canvas.drawColor(deskColor)
        union.set(f.left); union.union(f.right)
        val unit = resources.displayMetrics.density
        fill.color = 0x14000000; canvas.drawRect(union.left - unit, union.top + unit, union.right + unit, union.bottom + unit * 3, fill)
        canvas.drawBitmap(f.staticLeft, null, f.left, bitmapPaint)
        gutter.draw(canvas, f.left, spineOnRight = true)
        f.staticRight?.let { canvas.drawBitmap(it, null, f.right, bitmapPaint); gutter.draw(canvas, f.right, spineOnRight = false) }

        val spine = f.left.right
        val onRight = angle < PI / 2
        val width = if (onRight) f.right.width() else f.left.width()
        val lift = sin(angle)
        // A simple perspective: the free edge rises toward the viewer and so looks taller.
        val depth = 1f / (1f - lift / 4.5f)
        val outerX = spine + width * cos(angle) * depth
        if (abs(outerX - spine) < 1f) return
        val half = f.left.height() / 2 * depth; val cy = f.left.centerY()

        // Shadow the sheet casts on the uncovered part of the page below it.
        val reach = width * .3f * lift
        if (reach > 1f) {
            castMatrix.setScale(if (onRight) reach else -reach, 1f); castMatrix.postTranslate(outerX, 0f)
            castGradient.setLocalMatrix(castMatrix)
            canvas.save()
            if (onRight) canvas.clipRect(outerX, f.right.top, f.right.right, f.right.bottom)
            else canvas.clipRect(f.left.left, f.left.top, outerX, f.left.bottom)
            canvas.drawRect(if (onRight) outerX else outerX - reach, union.top, if (onRight) outerX + reach else outerX, union.bottom, castPaint)
            canvas.restore()
        }

        dst[0] = spine; dst[1] = f.left.top; dst[2] = outerX; dst[3] = cy - half
        dst[4] = outerX; dst[5] = cy + half; dst[6] = spine; dst[7] = f.left.bottom
        val face = if (onRight) f.rightFace else f.leftFace
        val back = if (onRight) f.leftFace else f.rightFace
        if (!map(sheet, face, leftFace = !onRight)) return
        canvas.save(); canvas.concat(sheet)
        faceBounds.set(0f, 0f, face.width.toFloat(), face.height.toFloat())
        canvas.drawBitmap(face, 0f, 0f, bitmapPaint)
        canvas.restore()
        // The other side shows through faintly, mirrored, as thin paper does against the light.
        if (seeThrough && map(through, back, leftFace = onRight)) {
            canvas.save(); canvas.concat(through); canvas.drawBitmap(back, 0f, 0f, throughPaint); canvas.restore()
        }
        canvas.save(); canvas.concat(sheet)
        // Darken toward the spine and as the sheet stands up, away from the light.
        gutter.draw(canvas, faceBounds, spineOnRight = !onRight, fraction = .07f + .25f * lift)
        fill.color = Color.argb((46 * lift).roundToInt(), 0, 0, 0); canvas.drawRect(faceBounds, fill)
        canvas.restore()
        canvas.drawLine(outerX, cy - half, outerX, cy + half, edge)
    }

    /** Maps a face bitmap onto the sheet quad in [dst]; its spine edge is its left edge on the right side, and vice versa. */
    private fun map(out: Matrix, bitmap: Bitmap, leftFace: Boolean): Boolean {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        if (leftFace) { src[0] = w; src[1] = 0f; src[2] = 0f; src[3] = 0f; src[4] = 0f; src[5] = h; src[6] = w; src[7] = h }
        else { src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f; src[4] = w; src[5] = h; src[6] = 0f; src[7] = h }
        return out.setPolyToPoly(src, 0, dst, 0, 4)
    }

    companion object { const val PREVIEW_WIDTH = 768 }
}
