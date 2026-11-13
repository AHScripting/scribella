package dev.ownednotes.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * P621 (design canvas board 10, "Slim-Bar"): the editor folded into one slim bar at the top. From the left: back to the
 * Library and the notebook's name, the pens and a +, the highlighters and a +, eraser, lasso, shapes, text and study tape,
 * three colour dots, and at the right Undo, Redo, Study, Search, the notebook menu and the pin that brings back the full
 * toolbar. Its buttons never grow: every slot is 24 dp tall at a fixed width; only the name gives way when the bar is
 * short of room.
 *
 * P628 (board 12, "Slim bar, modern: one bar with wells"): no flat strip any more. The bar is one rounded toolbar, 30 dp
 * tall with a soft shadow, floating 8 dp from the screen's edges; inside it the pens, the highlighters, the tools, the
 * colours, Undo and Redo, and Study, Search and the menu each sit in a faint well with a hairline border, so the areas
 * read at a glance but it stays one toolbar. Back with the name has no well; the pin is a solid accent circle at the
 * right end. The tool in hand is lifted by a white chip that springs to the next one taken. [reveal] stretches the bar
 * out of the pin to the left, the wells and slots popping in as its edge passes them; [conceal] rolls it all back into
 * the pin. [Kind.DIVIDER] and [Kind.GAP] only end one well and start the next.
 *
 * A finger (or a hovering S Pen) can slide along the bar: the slot under it lights up, a pen shows its faded tip under
 * the bar ([onOver], drawn by [SlimPeek]) and letting go takes it. Holding a pen offers to remove it, lit while held.
 * Each slot is its own view with a TalkBack name, so TalkBack and tests reach them as buttons.
 */
class SlimBar(context: Context, private val night: Boolean) : ViewGroup(context) {
    enum class Kind { ACTION, NAME, PEN, ADD, TOOL, COLOUR, PIN, DIVIDER, GAP }

    /** One place on the bar. [path] is a 24-unit SVG icon; pens carry [style] (and [marker] for highlighters). */
    inner class Slot(val kind: Kind, private val widthDp: Float = 0f, val label: String = "", private val path: String? = null) : View(context) {
        var style: PenStyle? = null; set(value) { if (field != value) { field = value; invalidate() } }
        var marker = false; set(value) { if (field != value) { field = value; invalidate() } }
        var chosen = false; set(value) { if (field != value) { field = value; invalidate(); thumbSoon() } }
        var colour: Int? = null; set(value) { if (field != value) { field = value; invalidate() } }
        var text: String? = null; set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
        /** Under the finger or the hovering pen. */
        var lit = false; set(value) { if (field != value) { field = value; invalidate() } }
        private val icon = path?.let { androidx.core.graphics.PathParser.createPathFromPathData(it) }
        init {
            if (kind == Kind.DIVIDER || kind == Kind.GAP) importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            else { contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true }
        }
        internal fun natural(): Int = when (kind) {
            Kind.NAME -> (namePaint.measureText(text.orEmpty()) + context.dp(12f)).toInt().coerceAtMost(context.dpi(NAME_MAX))
            Kind.DIVIDER, Kind.GAP -> 0
            else -> context.dpi(widthDp)
        }

        override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); invalidate() } }

        override fun onDraw(canvas: Canvas) {
            // During a stroke or a save the bar keeps its look, as the full toolbar does; taps wait for the real state.
            val on = isEnabled || holding
            val off = if (night) Glass.OFF_NIGHT else Glass.OFF
            if (kind == Kind.PIN) drawPin(canvas, on)
            if (lit && on) {
                if (kind == Kind.PIN) chip(canvas, Color.WHITE, 51) else chip(canvas, Glass.accent(night), if (night) 56 else 31)
            }
            when (kind) {
                Kind.ACTION, Kind.TOOL -> drawIcon(canvas, when {
                    !on -> off
                    chosen -> chosenInk
                    else -> Glass.text(night)
                }, 17f, 1.85f)
                Kind.ADD -> {
                    paint.style = Paint.Style.FILL; paint.color = if (night) 0x420a84ff else 0x1f0a66d9
                    if (!on) paint.alpha = paint.alpha / 2
                    canvas.drawCircle(width / 2f, height / 2f, context.dp(9f), paint)
                    drawIcon(canvas, if (!on) off else chosenInk, 12f, 2.6f)
                }
                Kind.PIN -> drawIcon(canvas, if (on) Color.WHITE else 0xb3ffffff.toInt(), 15f, 2.1f)
                Kind.NAME -> {
                    namePaint.color = if (on) Glass.text(night) else off
                    val shown = TextUtils.ellipsize(text.orEmpty(), namePaint, width - context.dp(8f), TextUtils.TruncateAt.END).toString()
                    canvas.drawText(shown, context.dp(4f), height / 2f - (namePaint.descent() + namePaint.ascent()) / 2, namePaint)
                }
                Kind.PEN -> drawPen(canvas, on)
                Kind.COLOUR -> colour?.let { drawDot(canvas, it, on) }
                Kind.DIVIDER, Kind.GAP -> Unit
            }
        }
        private fun chip(canvas: Canvas, color: Int, alpha: Int) {
            paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = alpha
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, context.dp(12f), context.dp(12f), paint); paint.alpha = 255
        }
        /** Board 12's pin: a solid accent circle with a soft accent shadow (drawn past the slot; the bar does not clip). */
        private fun drawPin(canvas: Canvas, on: Boolean) {
            paint.style = Paint.Style.FILL; paint.color = Glass.accent(night)
            if (!on) paint.alpha = 160
            paint.setShadowLayer(context.dp(4.3f), 0f, context.dp(2f), 0x590a66d9)
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
            paint.clearShadowLayer(); paint.alpha = 255
        }
        private fun drawIcon(canvas: Canvas, ink: Int, sizeDp: Float, stroke: Float) {
            val p = icon ?: return
            val size = context.dp(sizeDp)
            canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24f, size / 24f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            paint.color = ink; canvas.drawPath(p, paint)
            canvas.restore()
        }
        /** The tool lying at the board's slant (52°, tip down and to the left) in its own ink. */
        private fun drawPen(canvas: Canvas, on: Boolean) {
            val s = style ?: return
            val tool = if (marker) ToolArt.Tool.HIGHLIGHTER else ToolArt.of(s.kind)
            val ink = if (marker) ToolArt.mix(s.color.toInt(), Tokens.INK, .1f) else s.color.toInt()
            val count = canvas.save()
            if (!on) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 90)
            // The board's 164 x 128 view of a 200-unit pen fits a 26 x 24 box.
            val scale = minOf(context.dp(26f) / 164f, context.dp(24f) / 128f)
            canvas.translate(width / 2f, height / 2f); canvas.scale(scale, scale); canvas.rotate(232f)
            canvas.translate(-ToolArt.WIDTH / 2, -PEN_LENGTH / 2)
            ToolArt.draw(canvas, tool, ink, PEN_LENGTH, fine = marker && s.width <= ToolArt.FINE_HIGHLIGHTER)
            canvas.restoreToCount(count)
        }
        private fun drawDot(canvas: Canvas, color: Int, on: Boolean) {
            val r = context.dp(6f); val cx = width / 2f; val cy = height / 2f
            paint.style = Paint.Style.FILL
            if (chosen) {
                paint.color = color; canvas.drawCircle(cx, cy, r + context.dp(3.5f), paint)
                paint.color = ringGap; canvas.drawCircle(cx, cy, r + context.dp(2f), paint)
            }
            paint.color = color; if (!on) paint.alpha = 90
            canvas.drawCircle(cx, cy, r, paint); paint.alpha = 255
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1f); paint.color = if (night) 0x2effffff else 0x1f000000
            canvas.drawCircle(cx, cy, r - context.dp(.5f), paint)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(12.5f); typeface = Tokens.SANS_SEMIBOLD }
    // Board 12's colours: the bar frosted white by day and graphite by night, the wells a faint tint of ink.
    private val barFill = if (night) 0xf52a2f3a.toInt() else 0xf5fafbfd.toInt()
    private val barEdge = if (night) 0x1affffff else 0x141d2433
    private val wellFill = if (night) 0x0fffffff else 0x0b1d2433
    private val wellEdge = if (night) 0x14ffffff else 0x121d2433
    private val ringGap = if (night) 0xff2a2f3a.toInt() else 0xfff4f6fa.toInt()
    private val chosenInk = if (night) Kit.accentText(true) else Glass.accent(false)
    private var start: List<Slot> = emptyList()
    private var end: List<Slot> = emptyList()
    /** One area of the bar: the slots it holds and its well; back with the name, and the pin, have no well. */
    private class Area(val slots: List<Slot>) {
        val pin = slots.any { it.kind == Kind.PIN }
        val plain = pin || slots.any { it.kind == Kind.NAME }
        val rect = RectF()
    }
    private var areas: List<Area> = emptyList()
    /** The slot under the finger or the hovering pen, or null; the editor shows the pen's tip under it. */
    var onOver: ((Slot?) -> Unit)? = null
    private var under: Slot? = null
    private var held = false
    /** True while the editor holds its chrome's look (a stroke or a save); see [Slot.onDraw]. */
    var holding = false; set(value) { if (field != value) { field = value; (start + end).forEach { it.invalidate() } } }
    private val hold = Runnable { under?.takeIf { it.kind == Kind.PEN && it.isEnabled }?.let { held = true; it.performLongClick() } }

    init {
        setWillNotDraw(false)
        clipChildren = false; clipToPadding = false
        contentDescription = "Tools"
    }

    /** Lays out [left] from the start and [right] at the end, the room between them left empty. */
    fun setSlots(left: List<Slot>, right: List<Slot>) {
        under?.lit = false; under = null
        removeAllViews(); start = left; end = right
        (left + right).forEach { addView(it) }
        areas = split(left) + split(right)
        thumb = null; thumbAnim?.cancel()
        requestLayout(); invalidate()
    }
    private fun split(slots: List<Slot>): List<Area> {
        val out = mutableListOf<Area>(); var run = mutableListOf<Slot>()
        fun close() { if (run.isNotEmpty()) out += Area(run); run = mutableListOf() }
        slots.forEach { if (it.kind == Kind.DIVIDER || it.kind == Kind.GAP) close() else run += it }
        close()
        return out
    }

    /**
     * P632: the least width the bar needs, its name given way entirely. Its buttons never shrink, so on a narrower screen
     * (a phone, or a tablet held upright with many pens and highlighters) the editor keeps the full toolbar instead.
     */
    fun needed(): Int {
        val fixed = (start + end).filter { it.kind != Kind.NAME }.sumOf { it.natural() }
        val leftAreas = split(start).size; val rightAreas = split(end).size
        val extras = (leftAreas + rightAreas) * 2 * context.dpi(PAD) + (maxOf(0, leftAreas - 1) + maxOf(0, rightAreas - 1)) * context.dpi(GAP)
        return 2 * (context.dpi(EDGE) + context.dpi(INSET)) + fixed + extras + if (leftAreas > 0 && rightAreas > 0) context.dpi(GAP) else 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val room = width - needed()
        (start + end).forEach { slot ->
            val w = if (slot.kind == Kind.NAME) slot.natural().coerceAtMost(maxOf(0, room)) else slot.natural()
            slot.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(context.dpi(SLOT), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, context.dpi(HEIGHT))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = context.dpi(PAD); val gap = context.dpi(GAP); val inner = context.dpi(EDGE) + context.dpi(INSET)
        val top = context.dpi(SLOT_TOP); val wellTop = context.dp(WELL_TOP); val wellBottom = wellTop + context.dp(WELL)
        fun breaks(slot: Slot, x: Int) = slot.layout(x, top, x, top + slot.measuredHeight)
        var x = inner
        var opened = false
        start.forEach {
            if (it.kind == Kind.DIVIDER || it.kind == Kind.GAP) { if (opened) { x += pad + gap; opened = false }; breaks(it, x); return@forEach }
            if (!opened) { x += pad; opened = true }
            it.layout(x, top, x + it.measuredWidth, top + it.measuredHeight); x += it.measuredWidth
        }
        var right = r - l - inner
        opened = false
        end.asReversed().forEach {
            if (it.kind == Kind.DIVIDER || it.kind == Kind.GAP) { if (opened) { right -= pad + gap; opened = false }; breaks(it, right); return@forEach }
            if (!opened) { right -= pad; opened = true }
            it.layout(right - it.measuredWidth, top, right, top + it.measuredHeight); right -= it.measuredWidth
        }
        areas.forEach { area ->
            area.rect.set((area.slots.first().left - pad).toFloat(), wellTop, (area.slots.last().right + pad).toFloat(), wellBottom)
        }
        if (thumbAnim?.isRunning != true) placeThumb(false)
        if (motion != null) pose(motionT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The one bar, its left edge sweeping out of the pin and back.
        val shellA = shellAlpha()
        if (shellA > 0f) {
            val radius = context.dp(BAR) / 2
            box.set(shellLeft(), context.dp(BAR_TOP), width - context.dp(EDGE), context.dp(BAR_TOP) + context.dp(BAR))
            barPaint.style = Paint.Style.FILL; barPaint.color = barFill
            if (night) barPaint.setShadowLayer(context.dp(11f), 0f, context.dp(4f), faded(0x59000000, shellA))
            else barPaint.setShadowLayer(context.dp(6f), 0f, context.dp(2f), faded(0x1f1d2433, shellA))
            barPaint.alpha = (Color.alpha(barFill) * shellA).toInt()
            canvas.drawRoundRect(box, radius, radius, barPaint)
            barPaint.clearShadowLayer()
            barPaint.style = Paint.Style.STROKE; barPaint.strokeWidth = context.dp(1f); barPaint.color = faded(barEdge, shellA)
            box.inset(-context.dp(.5f), -context.dp(.5f))
            canvas.drawRoundRect(box, radius + context.dp(.5f), radius + context.dp(.5f), barPaint)
        }
        // A faint well around each area.
        val wellR = context.dp(WELL) / 2
        areas.forEach { area ->
            if (area.plain) return@forEach
            val (p, a) = wellState(area)
            if (a <= 0f) return@forEach
            val count = canvas.save()
            val s = .6f + .4f * p
            canvas.scale(s, s, area.rect.centerX(), area.rect.centerY())
            barPaint.style = Paint.Style.FILL; barPaint.color = faded(wellFill, a)
            canvas.drawRoundRect(area.rect, wellR, wellR, barPaint)
            barPaint.style = Paint.Style.STROKE; barPaint.strokeWidth = context.dp(1f); barPaint.color = faded(wellEdge, a)
            box.set(area.rect); box.inset(context.dp(.5f), context.dp(.5f))
            canvas.drawRoundRect(box, wellR - context.dp(.5f), wellR - context.dp(.5f), barPaint)
            canvas.restoreToCount(count)
        }
        // The raised chip under the tool in hand.
        val shown = thumbAlpha()
        if (thumb != null && shown > 0f && !thumbBox.isEmpty) {
            val r = context.dp(12f)
            barPaint.style = Paint.Style.FILL
            val fill = if (night) 0x29ffffff else Color.WHITE
            barPaint.color = faded(fill, shown)
            barPaint.setShadowLayer(context.dp(1.7f), 0f, context.dp(1f), faded(if (night) 0x59000000 else 0x381d2433, shown))
            canvas.drawRoundRect(thumbBox, r, r, barPaint)
            barPaint.clearShadowLayer()
            if (!night) {
                barPaint.style = Paint.Style.STROKE; barPaint.strokeWidth = context.dp(.5f); barPaint.color = faded(0x141d2433, shown)
                canvas.drawRoundRect(thumbBox, r, r, barPaint)
            }
        }
    }
    /** [color] with its alpha scaled by [a]. */
    private fun faded(color: Int, a: Float) = (color and 0xffffff) or ((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt() shl 24)

    // ---- P628: the raised chip springs from tool to tool. ----
    private var thumb: Slot? = null
    private val thumbBox = RectF(); private val thumbFrom = RectF(); private val thumbTo = RectF()
    private var thumbAnim: ValueAnimator? = null
    private val thumbCheck = Runnable { placeThumb(true) }
    private fun thumbSoon() { removeCallbacks(thumbCheck); post(thumbCheck) }
    private fun placeThumb(animate: Boolean) {
        val next = (start + end).firstOrNull { it.chosen && (it.kind == Kind.PEN || it.kind == Kind.TOOL) }
        if (next == null) { thumbAnim?.cancel(); thumb = null; invalidate(); return }
        thumbTo.set(next.left.toFloat(), next.top.toFloat(), next.right.toFloat(), next.bottom.toFloat())
        val moving = animate && thumb != null && next !== thumb && isLaidOut && !thumbBox.isEmpty && ValueAnimator.areAnimatorsEnabled()
        thumb = next
        thumbAnim?.cancel(); thumbAnim = null
        if (!moving) { thumbBox.set(thumbTo); invalidate(); return }
        thumbFrom.set(thumbBox)
        thumbAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = THUMB_MS; interpolator = SOFT
            addUpdateListener {
                val f = it.animatedValue as Float
                thumbBox.set(thumbFrom.left + (thumbTo.left - thumbFrom.left) * f, thumbTo.top,
                    thumbFrom.right + (thumbTo.right - thumbFrom.right) * f, thumbTo.bottom)
                invalidate()
            }
            start()
        }
    }

    // ---- P628: the bar stretches out of the pin and rolls back into it. ----
    private var motion: ValueAnimator? = null
    private var revealing = true
    private var motionT = 0f
    /** False while the bar is stretching out or rolling in. */
    val settled get() = motion == null
    /** Stretches the bar out of the pin to the left, each well and its slots popping in as its edge passes them. */
    fun reveal(done: (() -> Unit)? = null) = play(true, done)
    /** Rolls everything back into the pin, the farthest first, the bar's edge following. */
    fun conceal(done: (() -> Unit)? = null) = play(false, done)
    private fun play(revealing: Boolean, done: (() -> Unit)?) {
        motion?.cancel()
        this.revealing = revealing
        val total = if (revealing) {
            val slots = areas.maxOfOrNull { inDelay(it) + 40f + (it.slots.size - 1) * SLOT_STEP + SLOT_IN } ?: 0f
            maxOf(BAR_IN_MS, THUMB_IN + FADE_MS, slots)
        } else maxOf(BAR_OUT_MS, BAR_FADE_AT + BAR_FADE_MS, CONCEAL_STEP + CONCEAL_MS)
        motionT = 0f
        motion = ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong(); interpolator = LinearInterpolator()
            addUpdateListener { motionT = it.animatedValue as Float; pose(motionT) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (motion === animation) motion = null
                    rest()
                    if (!cancelled) done?.invoke()
                }
            })
        }
        pose(0f)
        motion?.start()
    }
    private fun rest() {
        (start + end).forEach { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        invalidate()
    }
    private fun pinX(): Float = areas.lastOrNull { it.pin }?.rect?.centerX() ?: (width - context.dp(EDGE + INSET + PIN_W / 2))
    /** How far from the pin an area lies, 0 at the pin to 1 across the whole bar. */
    private fun far(area: Area) = if (width == 0) 0f else (abs(pinX() - area.rect.centerX()) / width).coerceIn(0f, 1f)
    private fun inDelay(area: Area) = far(area) * REVEAL_SPREAD
    private fun outDelay(area: Area) = (1 - far(area)) * CONCEAL_STEP
    /** Where the bar's left edge is now: full width at rest, a [COLLAPSED] pill round the pin at the start or end. */
    private fun shellLeft(): Float {
        val full = context.dp(EDGE); val folded = width - context.dp(EDGE) - context.dp(COLLAPSED)
        if (motion == null) return full
        val f = if (revealing) SOFT.getInterpolation((motionT / BAR_IN_MS).coerceIn(0f, 1f))
        else 1 - AWAY.getInterpolation((motionT / BAR_OUT_MS).coerceIn(0f, 1f))
        return folded + (full - folded) * f
    }
    private fun shellAlpha(): Float = when {
        motion == null -> 1f
        revealing -> (motionT / BAR_FADE_MS).coerceIn(0f, 1f)
        else -> (1 - (motionT - BAR_FADE_AT) / BAR_FADE_MS).coerceIn(0f, 1f)
    }
    /** A well's spring progress and opacity at the current moment. */
    private fun wellState(area: Area): Pair<Float, Float> {
        if (motion == null) return 1f to 1f
        val t = motionT
        return if (revealing) {
            val d = inDelay(area)
            SPRING.getInterpolation(((t - d) / WELL_MS).coerceIn(0f, 1f)) to ((t - d) / FADE_MS).coerceIn(0f, 1f)
        } else {
            val d = outDelay(area)
            (1 - AWAY.getInterpolation(((t - d) / CONCEAL_MS).coerceIn(0f, 1f))) to (1 - (t - d) / FADE_MS).coerceIn(0f, 1f)
        }
    }
    private fun thumbAlpha(): Float {
        if (motion == null) return 1f
        return if (revealing) ((motionT - THUMB_IN) / FADE_MS).coerceIn(0f, 1f) else (1 - motionT / 100f).coerceIn(0f, 1f)
    }
    /** Every slot's pop at time [t]: from 60% and a little raised, springing to its place. */
    private fun pose(t: Float) {
        val lift = context.dp(3f)
        areas.forEach { area ->
            area.slots.forEachIndexed { j, slot ->
                val q: Float; val a: Float
                if (revealing) {
                    val d = inDelay(area) + 40f + j * SLOT_STEP
                    q = SPRING.getInterpolation(((t - d) / SLOT_IN).coerceIn(0f, 1f)); a = ((t - d) / 120f).coerceIn(0f, 1f)
                } else {
                    val d = maxOf(0f, outDelay(area) - 20f)
                    q = 1 - AWAY.getInterpolation(((t - d) / 140f).coerceIn(0f, 1f)); a = (1 - (t - d) / 100f).coerceIn(0f, 1f)
                }
                val s = .6f + .4f * q
                slot.alpha = a; slot.scaleX = s; slot.scaleY = s
                slot.translationY = -lift * (1 - q) * s
            }
        }
        invalidate()
    }

    /** The slot under [x], if the finger is still near the bar. */
    private fun slotAt(x: Float, y: Float): Slot? {
        if (y < -context.dp(12f) || y > height + context.dp(28f)) return null
        return (start + end).firstOrNull { it.kind != Kind.DIVIDER && it.kind != Kind.GAP && x >= it.left && x < it.right }
    }
    private fun over(slot: Slot?) {
        if (slot === under) return
        under?.lit = false; under = slot; slot?.lit = true
        onOver?.invoke(slot)
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false; over(slotAt(event.x, event.y))
                if (under?.kind == Kind.PEN) postDelayed(hold, timeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val slot = slotAt(event.x, event.y)
                if (slot !== under && !held) { removeCallbacks(hold); over(slot); if (slot?.kind == Kind.PEN) postDelayed(hold, timeout) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                val slot = under; over(null)
                if (!held && slot != null && slot.isEnabled) slot.performClick()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); over(null) }
        }
        return true
    }

    /** A hovering S Pen lights the slot under it like a finger; TalkBack's touch exploration keeps its own hovering. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val a11y = context.getSystemService(AccessibilityManager::class.java)
        if (a11y?.isTouchExplorationEnabled == true) return super.dispatchHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> over(slotAt(event.x, event.y))
            MotionEvent.ACTION_HOVER_EXIT -> over(null)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold); removeCallbacks(thumbCheck); over(null)
        motion?.cancel(); motion = null; thumbAnim?.cancel(); rest()
        super.onDetachedFromWindow()
    }

    companion object {
        /** P628: a 30 dp bar with 2 dp above and below it, for its shadow. */
        const val HEIGHT = 34f
        const val SLOT = 24f
        private const val BAR = 30f; private const val BAR_TOP = 2f
        /** The wells: 26 dp, 1 dp round their 24 dp slots top and bottom. */
        private const val WELL = 26f; private const val WELL_TOP = 4f; private const val SLOT_TOP = 5f
        /** A well's end to its first or last slot, between wells, the bar from the screen's edges, the bar to its wells. */
        const val PAD = 2f; const val GAP = 6f; const val EDGE = 8f; const val INSET = 3f
        /** The bar's width round the pin when it is all rolled in. */
        private const val COLLAPSED = 30f
        private const val PEN_LENGTH = 200f
        /** The board's slot widths, in dp. */
        const val ACTION_W = 26f; const val BACK_W = 30f; const val PEN_W = 30f; const val ADD_W = 22f; const val PIN_W = 24f
        const val COLOUR_W = 22f; const val NAME_MAX = 160f
        // Board 12's motion, in ms, from the moment the full bars have gone. The bar's edge sweeps out over BAR_IN_MS;
        // a well springs in after up to REVEAL_SPREAD by its distance from the pin, its slots 40 ms later, SLOT_STEP
        // apart. Going in, the farthest well leaves first and the bar's edge follows, fading from BAR_FADE_AT.
        private const val BAR_IN_MS = 400f; private const val BAR_FADE_MS = 100f; private const val WELL_MS = 260f
        private const val REVEAL_SPREAD = 220f; private const val FADE_MS = 140f
        private const val SLOT_IN = 260f; private const val SLOT_STEP = 16f; private const val THUMB_IN = 190f
        private const val CONCEAL_MS = 160f; private const val CONCEAL_STEP = 110f
        private const val BAR_OUT_MS = 270f; private const val BAR_FADE_AT = 200f
        private const val THUMB_MS = 340L
        /** CSS cubic-bezier(.34,1.56,.64,1): a spring with a small overshoot. */
        internal val SPRING = PathInterpolator(.34f, 1.56f, .64f, 1f)
        /** cubic-bezier(.34,1.35,.64,1): a softer spring, for the bar's edge, the raised chip and the full toolbar. */
        internal val SOFT = PathInterpolator(.34f, 1.35f, .64f, 1f)
        /** cubic-bezier(.4,0,1,1): leaving, speeding up. */
        internal val AWAY = PathInterpolator(.4f, 0f, 1f, 1f)
        // The board's 24-unit icons.
        const val BACK = "M15 5l-7 7 7 7"
        const val ADD = "M12 6v12M6 12h12"
        const val ERASER = "M8 20h12M4 15l9-9 6 6-7 7H8z"
        const val LASSO = "M12 5c4.4 0 8 2.2 8 5s-3.6 5-8 5-8-2.2-8-5 3.6-5 8-5zM7 14c-1 2 0 4 2 5"
        const val SHAPES = "M4 14h7v7H4zM17 3l4 7h-8zM17.5 14a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z"
        const val TEXT = "M5 6V4h14v2M12 4v16M9 20h6"
        const val UNDO = "M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3"
        const val REDO = "M15 14l5-5-5-5M20 9H10a6 6 0 0 0 0 12h3"
        const val STUDY = "M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4zM9 9h6M9 13h4"
        const val SEARCH = "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM15.5 15.5L20 20"
        const val MORE = "M6 12h.01M12 12h.01M18 12h.01"
        const val PIN = "M9 4h6l-1 6 3 3H7l3-3zM12 13v7"
        const val FOLD = "M6 15l6-6 6 6"
    }
}

/**
 * P621 (board 10): the faded pen under the slim bar. The tip of the pen under the finger (nib, point, felt or bristles)
 * fades in from the strip and is solid at its end, with a stroke written in its style, size and colour, and its name.
 * Only a little bigger than the button; it glides from pen to pen. It never takes touches.
 */
class SlimPeek(context: Context, private val night: Boolean) : View(context) {
    var style: PenStyle? = null; private set
    var marker = false; private set
    var label = ""; private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(11f); typeface = Tokens.SANS_MEDIUM; color = Color.WHITE }
    private val wave = Path().apply { moveTo(10f, 30f); cubicTo(30f, 8f, 50f, 52f, 70f, 30f); cubicTo(90f, 8f, 110f, 8f, 130f, 30f); cubicTo(150f, 52f, 170f, 52f, 190f, 30f) }
    private val box = RectF()

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(style: PenStyle, marker: Boolean, label: String) {
        this.style = style; this.marker = marker; this.label = label; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dpi(WIDTH), context.dpi(TIP + 2f + STROKE + 2f + 19f))

    override fun onDraw(canvas: Canvas) {
        val s = style ?: return
        val tool = if (marker) ToolArt.Tool.HIGHLIGHTER else ToolArt.of(s.kind)
        val ink = if (marker) ToolArt.mix(s.color.toInt(), Tokens.INK, .1f) else s.color.toInt()
        // The tip, nib down: the first 74 units of the upright tool, turned over, faded toward the strip.
        val tipH = context.dp(TIP); val scale = tipH / 74f; val tipW = ToolArt.WIDTH * scale
        val left = (width - tipW) / 2
        val layer = canvas.saveLayer(left - context.dp(4f), 0f, left + tipW + context.dp(4f), tipH, null)
        canvas.save()
        canvas.translate(left + tipW, tipH); canvas.scale(scale, scale); canvas.rotate(180f)
        canvas.clipRect(-6f, 0f, ToolArt.WIDTH + 6f, 74f)
        ToolArt.draw(canvas, tool, ink, 90f, fine = marker && s.width <= ToolArt.FINE_HIGHLIGHTER)
        canvas.restore()
        mask.shader = LinearGradient(0f, 0f, 0f, tipH, intArrayOf(0, 0x8c000000.toInt(), Color.BLACK, Color.BLACK),
            floatArrayOf(0f, .12f, .3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(left - context.dp(4f), 0f, left + tipW + context.dp(4f), tipH, mask)
        canvas.restoreToCount(layer)
        // The stroke, on the board's 200 x 60 wave, 84 x 26 dp.
        val strokeTop = tipH + context.dp(2f); val strokeW = context.dp(84f)
        canvas.save()
        canvas.translate((width - strokeW) / 2, strokeTop); canvas.scale(strokeW / 200f, context.dp(STROKE) / 60f)
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.pathEffect = null; paint.shader = null
        paint.color = s.color.toInt() or 0xff000000.toInt()
        val units = 200f / strokeW * context.dp(1f)
        if (marker) {
            paint.strokeCap = Paint.Cap.BUTT; paint.alpha = 128
            paint.strokeWidth = (s.width * .3f).coerceIn(3f, 9f) * units
            canvas.drawPath(wave, paint)
        } else {
            paint.strokeCap = Paint.Cap.ROUND
            val w = (s.width * .45f).coerceIn(1f, 6f) * units
            paint.strokeWidth = w
            when (s.kind) {
                PenKind.DASHED -> { paint.pathEffect = DashPathEffect(floatArrayOf(w * 1.2f, w * 2.4f), 0f); canvas.drawPath(wave, paint) }
                PenKind.CALLIGRAPHY -> CalligraphyBrush.draw(canvas, wave, paint, w * 1.4f)
                PenKind.FOUNTAIN, PenKind.FOUNTAIN_SOFT -> FountainInk.drawSample(canvas, wave, paint, w, requireNotNull(s.kind.nib))
                PenKind.PENCIL -> { paint.alpha = 210; canvas.drawPath(wave, paint) }
                else -> canvas.drawPath(wave, paint)
            }
        }
        paint.alpha = 255; paint.pathEffect = null
        canvas.restore()
        // The name, white on a dark pill.
        val textW = labelPaint.measureText(label)
        val pillTop = strokeTop + context.dp(STROKE) + context.dp(2f)
        box.set((width - textW) / 2 - context.dp(8f), pillTop, (width + textW) / 2 + context.dp(8f), pillTop + context.dp(17f))
        paint.style = Paint.Style.FILL; paint.color = if (night) 0xd9000000.toInt() else 0xb81d2433.toInt()
        canvas.drawRoundRect(box, context.dp(8f), context.dp(8f), paint)
        canvas.drawText(label, (width - textW) / 2, box.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
    }

    companion object { const val WIDTH = 108f; const val TIP = 75f; const val STROKE = 26f }
}

/**
 * P625: one kind on the menu the + after the pens or the highlighters opens: the pen drawn whole and tip-up on a card, its
 * name below; the card tints while pressed. A TextView, so its name stays findable and clickable like any other option.
 */
@android.annotation.SuppressLint("AppCompatCustomView") // the app uses framework widgets throughout
internal class AddChoice(context: Context, private val tool: ToolArt.Tool, label: String, private val ink: Int, private val fine: Boolean = false) :
    android.widget.TextView(context) {
    private val night = context.night
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    init {
        text = label; contentDescription = label; tooltipText = label
        textSize = 12f; includeFontPadding = false; maxLines = 1
        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        shrinkToFitWords(7.5f, 12f)
        setPadding(context.dpi(2f), 0, context.dpi(2f), context.dpi(10f))
        typeface = Tokens.SANS_MEDIUM; setTextColor(ToolControls.optionInk(night))
        isClickable = true; isFocusable = true
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        box.set(context.dp(3f), 0f, width - context.dp(3f), height.toFloat())
        card.color = if (isPressed) Glass.accent(night) else Glass.text(night); card.alpha = if (isPressed) 40 else 12
        canvas.drawRoundRect(box, context.dp(16f), context.dp(16f), card)
        val scale = context.dp(WIDE) / ToolArt.WIDTH
        val count = canvas.save()
        canvas.translate((width - context.dp(WIDE)) / 2f, context.dp(10f)); canvas.scale(scale, scale)
        ToolArt.draw(canvas, tool, ink, ToolArt.LENGTH, fine)
        canvas.restoreToCount(count)
        super.onDraw(canvas)
    }
    companion object {
        /** The pen is drawn this wide (so 66 dp tall); the card is [TALL] dp, room for it and its name. */
        const val WIDE = 22f
        const val TALL = 108f
    }
}
