package dev.ownednotes.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A floating light Glass sheet (P177, G2: card radius, hairline edge, soft shadow) anchored to chrome.
 * P187: a [tool] popover takes the Glass board's 30 dp radius and 22 dp padding. No popover is wider than the screen
 * less 8 dp on each side.
 */
class Popover(private val anchor: View, width: Float? = 340f, private val tool: Boolean = false, passThrough: Boolean = false,
    build: (LinearLayout) -> Unit) {
    private val context = anchor.context
    private val pixels = width?.let { minOf(context.dpi(it), context.resources.displayMetrics.widthPixels - context.dpi(16f)) }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (tool) dpi(22f).let { setPadding(it, it, it, it) } else setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(18f))
        if (pixels != null) minimumWidth = pixels
        building = this@Popover
        try { build(this) } finally { building = null }
    }
    private val scroll = android.widget.ScrollView(context).apply { addView(content) }
    val window = PopupWindow(scroll, pixels ?: ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        // P201 step 3: graphite at night, like the rest of the night chrome; its controls follow the context's night.
        setBackgroundDrawable(Glass.float(context, context.night, if (tool) 30f else Glass.CARD))
        elevation = context.dp(16f)
        isOutsideTouchable = true
        // P624: a pass-through board takes no focus, so a touch outside it closes it and still reaches what is under it:
        // the pen on the page draws, a button works. A focusable popover would swallow that touch.
        if (passThrough) isFocusable = false
        animationStyle = R.style.Scribella_PopoverMotion
    }
    /**
     * P547: a tool popover taller than [available] (the pen board in landscape) lays its sections out in two columns
     * side by side, so it fits without scrolling. It stays one column when two would not fit across the screen.
     */
    private fun splitIfTall(available: Int) {
        if (!tool || pixels == null || content.childCount < 2 || content.orientation != LinearLayout.VERTICAL) return
        content.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        if (content.measuredHeight <= available) return
        val inner = pixels - content.paddingLeft - content.paddingRight
        val gap = context.dpi(22f)
        val total = content.paddingLeft + inner * 2 + gap + content.paddingRight
        if (total > context.resources.displayMetrics.widthPixels - context.dpi(16f)) return
        val parts = (0 until content.childCount).map { content.getChildAt(it) }
        val heights = parts.map { val p = it.layoutParams as? ViewGroup.MarginLayoutParams; it.measuredHeight + (p?.topMargin ?: 0) + (p?.bottomMargin ?: 0) }
        val whole = heights.sum()
        val split = (1 until parts.size).minByOrNull { k -> heights.take(k).sum().let { maxOf(it, whole - it) } }!!
        content.removeAllViews()
        content.orientation = LinearLayout.HORIZONTAL
        content.minimumWidth = total
        listOf(parts.subList(0, split), parts.subList(split, parts.size)).forEachIndexed { index, side ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                side.forEachIndexed { i, view ->
                    val params = view.layoutParams as? LinearLayout.LayoutParams
                    if (i == 0 && index == 1) params?.topMargin = 0
                    addView(view, params ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }, LinearLayout.LayoutParams(inner, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index == 1) leftMargin = gap })
        }
        window.width = total
    }
    fun show(alignEnd: Boolean = false) {
        val frame = android.graphics.Rect(); anchor.getWindowVisibleDisplayFrame(frame)
        splitIfTall(frame.height() - context.dpi(32f))
        window.height=fit()
        window.showAsDropDown(anchor,0,context.dpi(6f),if(alignEnd) Gravity.END else Gravity.START)
    }
    private fun fit(): Int {
        val frame=android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(frame)
        val width=window.width.takeIf { it > 0 } ?: context.dpi(340f)
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.UNSPECIFIED)
        return minOf(content.measuredHeight,(frame.height()-context.dpi(32f)).coerceAtLeast(1))
    }
    /**
     * P201 step 8: a sheet whose sections change (the Export board's range field, its formats' own sections) set this so
     * the popover opened by [show] grows and shrinks with it instead of scrolling its buttons out of sight.
     */
    var grows = false
    init {
        // P644: the app turns without being rebuilt; a popover placed against a button that has since moved closes
        // when the screen's width changes (turning, split screen). A keyboard changes only the height and keeps it.
        val turned = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && oldRight > oldLeft && window.isShowing) window.dismiss()
        }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { anchor.rootView.removeOnLayoutChangeListener(turned); anchor.rootView.addOnLayoutChangeListener(turned) }
            override fun onViewDetachedFromWindow(v: View) { anchor.rootView.removeOnLayoutChangeListener(turned) }
        })
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (grows && window.isShowing && bottom - top != oldBottom - oldTop) {
                val height = fit()
                if (height != window.height) content.post { if (window.isShowing) window.update(anchor, 0, context.dpi(6f), window.width, height) }
            }
        }
    }

    /** Context controls can be near either screen edge. Keep the menu visible on the roomier side. */
    fun showAboveOrBelow() {
        val frame = android.graphics.Rect(); anchor.getWindowVisibleDisplayFrame(frame)
        val screen = IntArray(2); val local = IntArray(2)
        anchor.getLocationOnScreen(screen); anchor.getLocationInWindow(local)
        frame.offset(local[0] - screen[0], local[1] - screen[1])
        val margin = context.dpi(8f); val gap = context.dpi(6f)
        val width = minOf(if (window.width > 0) window.width else context.dpi(300f), frame.width() - margin * 2).coerceAtLeast(1)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = minOf(content.measuredHeight, frame.height() - margin * 2).coerceAtLeast(1)
        val above = local[1] - gap - height
        val below = local[1] + anchor.height + gap
        val top = when {
            above >= frame.top + margin -> above
            below + height <= frame.bottom - margin -> below
            else -> above.coerceIn(frame.top + margin, maxOf(frame.top + margin, frame.bottom - margin - height))
        }
        window.width = width; window.height = height
        window.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY,
            local[0].coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - width)), top)
    }

    /**
     * Opens on the given side of the anchor: a docked tool bar puts its popovers toward the page.
     * P580: kept between the status and navigation bars (the visible frame, not the whole window), and a board opening
     * above a bottom palette stays above it and scrolls, so on a phone the pen board no longer runs under both bars.
     */
    fun showToward(side: Int) {
        if (side == Gravity.BOTTOM) return show()
        val frame = android.graphics.Rect(); anchor.getWindowVisibleDisplayFrame(frame)
        val screen = IntArray(2); val at = IntArray(2)
        anchor.getLocationOnScreen(screen); anchor.getLocationInWindow(at)
        frame.offset(at[0] - screen[0], at[1] - screen[1])
        val margin = context.dpi(8f); val gap = context.dpi(6f)
        val whole = (frame.height() - 2 * margin).coerceAtLeast(1)
        val above = at[1] - gap - (frame.top + margin)
        val room = if (side == Gravity.TOP && above >= context.dpi(240f)) above else whole
        splitIfTall(room)
        val width = if (window.width > 0) window.width else {
            content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); content.measuredWidth
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = minOf(content.measuredHeight, room).coerceAtLeast(1)
        val (x, y) = when (side) {
            Gravity.TOP -> at[0] to at[1] - height - gap
            Gravity.END -> at[0] + anchor.width + gap to at[1]
            else -> at[0] - width - gap to at[1]
        }
        window.height = height
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START,
            x.coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - width - margin)),
            y.coerceIn(frame.top + margin, maxOf(frame.top + margin, frame.bottom - height - margin)))
    }
    /** B17 (P136): opens beside the anchor (a notebook in the library), on whichever side has room, top-aligned with it. */
    fun showBeside() {
        val root = anchor.rootView
        val margin = context.dpi(8f); val gap = context.dpi(4f)
        val width = if (window.width > 0) window.width else {
            content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); content.measuredWidth
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = minOf(content.measuredHeight, root.height - 2 * margin)
        val at = IntArray(2); anchor.getLocationInWindow(at)
        val right = at[0] + anchor.width + gap
        val x = if (right + width <= root.width - margin || at[0] - gap - width < margin) right else at[0] - gap - width
        window.height = height
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START,
            x.coerceIn(margin, maxOf(margin, root.width - width - margin)), at[1].coerceIn(margin, maxOf(margin, root.height - height - margin)))
    }
    /** P197 (B34): a choice that belongs to no single control, such as which app to import from, opens mid-screen. */
    fun showCentered() {
        val root = anchor.rootView
        val width = window.width.takeIf { it > 0 } ?: context.dpi(340f)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        window.height = minOf(content.measuredHeight, root.height - context.dpi(32f)).coerceAtLeast(1)
        window.showAtLocation(root, Gravity.CENTER, 0, 0)
    }
    /** P200: centred on ([x], [y]) in the window, where the quick ring stood, and kept on screen. */
    fun showAround(x: Int, y: Int) {
        val root = anchor.rootView
        val margin = context.dpi(8f)
        val width = window.width.takeIf { it > 0 } ?: context.dpi(340f)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = minOf(content.measuredHeight, root.height - 2 * margin).coerceAtLeast(1)
        window.height = height
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START,
            (x - width / 2).coerceIn(margin, maxOf(margin, root.width - width - margin)), (y - height / 2).coerceIn(margin, maxOf(margin, root.height - height - margin)))
    }
    fun dismiss() = window.dismiss()
    val isShowing get() = window.isShowing
    companion object {
        /** The popover whose content is being built, so controls inside it can close it (P072 eyedropper). */
        var building: Popover? = null; private set
    }
}

object ToolControls {
    val penColors = longArrayOf(0xff182636, 0xff000000, 0xff1565c0, 0xffc62828, 0xff2e7d32, 0xff6a1b9a, 0xffef6c00, 0xff616161)
    val penColorNames = arrayOf("Original ink", "Black", "Blue", "Red", "Green", "Purple", "Orange", "Gray")

    fun heading(context: Context, text: String, top: Float = 4f) = TextView(context).apply {
        this.text = text; textSize = 16f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.text(context.night))
        setPadding(0, dpi(top), 0, dpi(8f))
    }
    fun caption(context: Context, text: String) = TextView(context).apply {
        this.text = text; textSize = 14f; typeface = Tokens.SANS; setTextColor(Glass.secondary(context.night))
        setLineSpacing(0f, 1.15f); setPadding(0, dpi(2f), 0, dpi(8f))
    }

    /** Segmented control in a thin glass well; the selected option is an accent chip (P177, G2). */
    fun segmented(context: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(thin(context.night), dp(Glass.CHIP))
            setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
        }
        val items = mutableListOf<TextView>()
        fun paint(current: Int) = items.forEachIndexed { index, item ->
            val on = index == current
            val night = context.night
            item.background = if (on) rounded(Kit.accentFill(night), item.dp(Glass.CHIP - 3f)) else ripple(night, item.dp(Glass.CHIP - 3f))
            item.setTextColor(if (on) Kit.accentText(night) else Glass.text(night))
            item.isSelected = on
        }
        options.forEachIndexed { index, label ->
            val item = TextView(context).apply {
                text = label; textSize = 15f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER
                minHeight = dpi(42f); setPadding(dpi(6f), 0, dpi(6f), 0)
                // B02: a label never wraps; it shrinks a little when the options are too wide for the row.
                maxLines = 1
                shrinkToFit(11f, 15f); accessibilityDelegate = OptionInGroup
                setOnClickListener {
                    runCatching { onSelect(index) }.onSuccess { paint(index); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            items += item
            // Longer labels get more of the row, so "Calligraphy" is not squeezed into the same width as "Pencil".
            val share = item.paint.measureText(label) + item.dp(12f)
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, share))
        }
        paint(selected)
        return row
    }

    /**
     * P187: a colour dot as on the Glass board: 30 dp of colour with a hairline edge in a 44 dp cell; the chosen dot gets
     * a white ring inside an accent ring. The custom colour is the colour wheel, the eyedropper a grey chip.
     */
    private class Swatch(context: Context, val color: Long?, label: String, val pipette: Boolean = false) : View(context) {
        var on = false; set(value) { field = value; isSelected = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var wheel: Shader? = null
        init {
            contentDescription = label; tooltipText = label; isClickable = true; foreground = ripple(context.night, dp(22f))
            // A saved colour's name is only its code; say roughly what it looks like too.
            if (color != null && label.contains('#')) stateDescription = ColorNames.of(color)
        }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dpi(44f), dpi(44f))
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            wheel = SweepGradient(w / 2f, h / 2f, intArrayOf(0xffff3b30.toInt(), 0xffff9500.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(),
                0xff00c7be.toInt(), 0xff0a84ff.toInt(), 0xff5e5ce6.toInt(), 0xffbf5af2.toInt(), 0xffff3b30.toInt()), null)
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = dp(15f)
            paint.style = Paint.Style.FILL; paint.shader = null
            val night = context.night
            if (on) { paint.color = Glass.accent(night); canvas.drawCircle(cx, cy, r + dp(5f), paint); paint.color = gap(night); canvas.drawCircle(cx, cy, r + dp(2.5f), paint) }
            when {
                pipette -> {
                    // Eyedropper: a grey chip with a pipette (bulb up-right, tip down-left).
                    paint.color = wellFill(night); canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE; paint.color = optionInk(night); paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = dp(2.5f)
                    canvas.drawLine(cx - dp(5.5f), cy + dp(5.5f), cx + dp(2f), cy - dp(2f), paint)
                    paint.strokeWidth = dp(1.5f); canvas.drawLine(cx - dp(7f), cy + dp(7f), cx - dp(5.5f), cy + dp(5.5f), paint)
                    paint.strokeWidth = dp(2f); canvas.drawLine(cx + dp(.5f), cy - dp(5f), cx + dp(5f), cy - dp(.5f), paint)
                    paint.style = Paint.Style.FILL; canvas.drawCircle(cx + dp(4.5f), cy - dp(4.5f), dp(3f), paint)
                    paint.strokeCap = Paint.Cap.BUTT
                }
                color == null -> {
                    paint.shader = wheel; canvas.drawCircle(cx, cy, r, paint); paint.shader = null
                    paint.color = gap(night); canvas.drawCircle(cx, cy, r * 22f / 34f, paint)
                }
                else -> {
                    paint.color = color.toInt(); canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) 0x33ffffff else 0x1f1d2433; canvas.drawCircle(cx, cy, r - dp(.5f), paint)
                }
            }
        }
    }

    /**
     * P187: views laid left to right, wrapping onto as many rows as the width needs (the colour dots).
     * P613: [gapX] and [gapY] space them, as the Page & paper chips; hidden children take no room.
     */
    internal class Flow(context: Context, private val gapX: Int = 0, private val gapY: Int = 0) : ViewGroup(context) {
        private fun place(width: Int, at: (View, Int, Int) -> Unit): Int {
            var x = 0; var y = 0; var row = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                if (x > 0) x += gapX
                if (x > 0 && x + child.measuredWidth > width) { x = 0; y += row + gapY; row = 0 }
                at(child, x, y); x += child.measuredWidth; row = maxOf(row, child.measuredHeight)
            }
            return y + row
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
            measureChildren(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val height = place(width) { _, _, _ -> }
            val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
            setMeasuredDimension(if (width == Int.MAX_VALUE) shown.sumOf { it.measuredWidth } + gapX * maxOf(0, shown.size - 1) else width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            place(r - l) { child, x, y -> child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight) }
        }
    }

    /** The colour rows; [refresh] rebuilds them after the palette or the saved colours change (P129). */
    class SwatchGrid(context: Context, private val build: (SwatchGrid) -> Unit) : LinearLayout(context) {
        init { orientation = VERTICAL; refresh() }
        fun refresh() { removeAllViews(); build(this) }
    }

    fun swatches(context: Context, selected: Long, onPick: (Long) -> Unit, onCustom: () -> Unit, highlighter: Boolean = false, eyedropper: (() -> Unit)? = null): SwatchGrid {
        val app = context.applicationContext as NotesApplication
        val collection = InkColors(app.getSharedPreferences(app.preferencesName, Context.MODE_PRIVATE))
        var current = selected
        return SwatchGrid(context) { column ->
            val presets = if (highlighter) InkColors.highlights else collection.presets
            val saved = collection.saved.filter { c -> presets.none { it.first == c } }
            val colors = (presets + saved.map { it to "Saved #%06X".format(it and 0xffffff) } +
                listOf(current to "Current #%06X".format(current and 0xffffff))).distinctBy { it.first }
            val all = colors.map { (color, name) -> Swatch(context, color, name) } + Swatch(context, null, "Choose any colour")
            fun mark(color: Long) {
                all.forEach { it.on = it.color == color }
                all.last().on = false
            }
            all.forEach { swatch ->
                swatch.setOnClickListener {
                    val color = swatch.color
                    if (color == null) onCustom() else runCatching { onPick(color) }.onSuccess { current = color; mark(color) }
                }
                // P129 (F029): touch and hold a saved colour to remove it from the collection.
                val color = swatch.color
                if (color != null && color in saved) swatch.setOnLongClickListener {
                    collection.forget(color)
                    column.announceForAccessibility("Removed saved colour #%06X".format(color and 0xffffff))
                    column.refresh(); true
                }
            }
            val pipette = eyedropper?.let { pick -> Swatch(context, null, "Pick a colour from ink", pipette = true).apply { setOnClickListener { pick() } } }
            column.addView(Flow(context).apply { (all + listOfNotNull(pipette)).forEach { addView(it) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (saved.isNotEmpty()) column.addView(note(context, "Touch and hold a saved colour to remove it."))
            mark(current)
        }
    }

    /** Log-scale slider for document-unit sizes; [format] renders the value with tabular digits. */
    fun slider(context: Context, label: String, min: Float, max: Float, value: Float, round: (Float) -> Float, format: (Float) -> String, onChange: (Float) -> Unit): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(8f), 0, 0) }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val night = context.night
        val name = TextView(context).apply { text = label; textSize = 15f; typeface = Tokens.SANS; setTextColor(Glass.text(night)) }
        val shown = TextView(context).apply { textSize = 15f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(night)); fontFeatureSettings = "tnum"; text = format(value) }
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); header.addView(shown)
        val steps = 1000
        fun toProgress(v: Float) = (ln(v / min) / ln(max / min) * steps).roundToInt().coerceIn(0, steps)
        fun fromProgress(p: Int) = round((min * exp(ln(max / min) * p / steps.toFloat()))).coerceIn(min, max)
        val bar = SeekBar(context).apply {
            this.max = steps; progress = toProgress(value)
            progressTintList = ColorStateList.valueOf(Glass.accent(night))
            thumbTintList = ColorStateList.valueOf(Glass.accent(night))
            progressBackgroundTintList = ColorStateList.valueOf(if (night) Glass.RULE_NIGHT else Glass.RULE)
            minimumHeight = dpi(44f)
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = fromProgress(progress)
                    runCatching { onChange(next) }.onSuccess { shown.text = format(next) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        column.addView(header); column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(44f)))
        return column
    }

    fun roundWidth(v: Float) = if (v < 10f) (v * 2).roundToInt() / 2f else v.roundToInt().toFloat()
    fun formatUnits(v: Float) = if (v % 1f == 0f) "${v.toInt()}" else "%.1f".format(java.util.Locale.ROOT, v)

    // P187: the Glass board's tool popover type and colours; P201 step 3 adds their night values on the graphite pane.
    private fun labelInk(night: Boolean) = if (night) 0xff9aa3b5.toInt() else 0xff566074.toInt()
    internal fun wellFill(night: Boolean) = if (night) 0xff353b47.toInt() else 0xffeef1f6.toInt()
    internal fun optionInk(night: Boolean) = if (night) 0xffd4d9e2.toInt() else 0xff3a4253.toInt()
    /** The chosen option's text on its pill: accent by day, white on the night pill. */
    internal fun chosenInk(night: Boolean) = if (night) Color.WHITE else 0xff0a5cc4.toInt()
    /** The pill under a well's chosen option. */
    internal fun pill(night: Boolean) = if (night) 0xff586071.toInt() else Color.WHITE
    /** The ring of pane colour between a chosen colour dot and its accent ring. */
    private fun gap(night: Boolean) = if (night) 0xff2a2f3a.toInt() else Color.WHITE
    private fun thin(night: Boolean) = if (night) 0x1affffff else Glass.THIN
    /** Space between the sections of a tool popover (the board uses 18 dp; 16 keeps it shorter in landscape). */
    private const val GAP = 16f
    /** Nine shapes sit as five over four, so no tile is left alone on a row. */
    private const val SHAPE_COLUMNS = 5
    /** Page units are PDF points. */
    private const val MM_PER_UNIT = 25.4f / 72f

    /** P187: a tool popover's title row: the tool's name in bold, with an optional grey chip on the right ("Pen 1 of 3"). */
    fun title(column: LinearLayout, text: String, chip: String? = null): TextView {
        val c = column.context
        val name = TextView(c).apply {
            this.text = text; textSize = 19f; typeface = Typeface.create(Tokens.SANS, 700, false); setTextColor(Glass.text(c.night))
            letterSpacing = -.01f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (chip != null) addView(TextView(c).apply {
                this.text = chip; textSize = 12f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(labelInk(c.night)); maxLines = 1
                background = rounded(wellFill(c.night), dp(10f)); setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f))
            })
        })
        return name
    }
    /** P187: a section label in a tool popover: small, semibold and grey. */
    fun label(context: Context, text: String) = TextView(context).apply {
        // P592: a long label wraps (large text on a phone cut "When you hold the highlighter still" in half).
        this.text = text; textSize = 13f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(labelInk(context.night)); maxLines = 3
    }
    /** P187: a short explanation under a tool popover control. */
    fun note(context: Context, text: String) = TextView(context).apply {
        this.text = text; textSize = 12.5f; typeface = Tokens.SANS; setTextColor(labelInk(context.night)); setLineSpacing(0f, 1.1f); setPadding(0, dpi(6f), 0, 0)
    }
    private fun section(column: LinearLayout, view: View, gap: Float = GAP) =
        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = column.dpi(gap) })
    /** A labelled section: [label] over [view], with the board's 10 dp between them. */
    private fun group(column: LinearLayout, label: String, view: View) {
        val c = column.context
        section(column, LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c, label))
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(8f) })
        })
    }
    fun millimetres(units: Float) = (units * MM_PER_UNIT).let { if (it < 10f) "%.1f mm".format(java.util.Locale.ROOT, it) else "${it.roundToInt()} mm" }

    /**
     * P187: a small segmented well of text chips for a secondary choice beside a section label (the pen palettes).
     * Same contract as [segmented]: one TextView child per option, `isSelected` on the chosen one; the choice slides
     * like the larger wells ([SlidingWell]).
     */
    fun chips(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) = SlidingWell(context, context.dp(9f), pill(context.night)).apply {
        background = rounded(wellFill(context.night), dp(12f))
        dpi(3f).let { setPadding(it, it, it, it) }
        val items = labels.map { label ->
            TextView(context).apply {
                text = label; textSize = 12f; maxLines = 1; gravity = Gravity.CENTER; includeFontPadding = false
                minHeight = dpi(30f); setPadding(dpi(10f), 0, dpi(10f), 0); accessibilityDelegate = OptionInGroup
            }
        }
        fun paint(current: Int, animate: Boolean) {
            items.forEachIndexed { index, item ->
                val on = index == current
                item.isSelected = on
                item.typeface = if (on) Tokens.SANS_SEMIBOLD else Tokens.SANS_MEDIUM
                item.setTextColor(if (on) chosenInk(context.night) else optionInk(context.night))
            }
            select(current, animate)
        }
        items.forEachIndexed { index, item ->
            item.setOnClickListener { runCatching { onSelect(index) }.onSuccess { paint(index, true); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } }
            // P592: with large text on a phone "Thickness" ran past its chip; there each chip gets a share of the row by its
            // words and a set height, and its words shrink to fit. Normal text keeps the chips as they were.
            if (context.largeText) {
                item.shrinkToFit(9f, 12f)
                addView(item, LinearLayout.LayoutParams(0, maxOf(dpi(30f), (item.paint.fontSpacing * 1.3f).toInt()), item.paint.measureText(item.text.toString()) + dp(20f)))
            } else addView(item)
        }
        paint(selected, false)
    }

    /** P187: the Glass board's slider: an 8 dp track filled in accent and a raised white knob. */
    private fun glassBar(context: Context, label: String) = SeekBar(context).apply {
        val track = rounded(Kit.track(context.night), dp(4f))
        val fill = ClipDrawable(rounded(Glass.accent(context.night), dp(4f)), Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            for (i in 0..1) { setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(i, dpi(8f)) }
        }
        thumb = Knob(dpi(34f), dp(15f)); splitTrack = false; background = null
        setPadding(dpi(17f), 0, dpi(17f), 0)
        contentDescription = label
    }
    /** The slider's knob: a white disc with a soft shadow and a hairline edge (also the colour circle's brightness, P190). */
    internal class Knob(private val size: Int, private val radius: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            paint.setShadowLayer(radius * .27f, 0f, radius * .13f, 0x40141e37); canvas.drawCircle(cx, cy, radius, paint); paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = radius / 30f; paint.color = 0x14141e37; canvas.drawCircle(cx, cy, radius, paint)
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    /** An ink dot of [size] dp; the size presets and the slider's ends. */
    private class Dot(context: Context, private val size: Float, private val cell: Float = size) : View(context) {
        var color = Tokens.INK; set(value) { field = value; invalidate() }
        var on = false; set(value) { field = value; isSelected = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(dpi(cell), w), dpi(cell))
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (on) { paint.color = Kit.accentFill(context.night); canvas.drawCircle(cx, cy, dp(cell) / 2, paint) }
            paint.color = color or 0xff000000.toInt(); canvas.drawCircle(cx, cy, dp(size) / 2, paint)
        }
    }

    /**
     * P187: the Glass board's thickness control: the label with the size in millimetres, a log-scale slider between a
     * small and a large dot, and optional [presets] as dots of growing size underneath. [tint] colours the dots.
     */
    class SizeControl internal constructor(context: Context, label: String, value: Float, private val min: Float, private val max: Float,
        private val presets: List<Float>, private val round: (Float) -> Float, private val onChange: (Float) -> Unit) : LinearLayout(context) {
        private val steps = 1000
        private val shown = TextView(context).apply { textSize = 13f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(context.night)); fontFeatureSettings = "tnum" }
        val bar = glassBar(context, label)
        private val ends = listOf(Dot(context, 6f), Dot(context, 16f))
        private val choices = presets.mapIndexed { index, size ->
            Dot(context, PRESET_DOTS[index.coerceAtMost(PRESET_DOTS.lastIndex)], 40f).apply {
                contentDescription = "$label ${millimetres(size)}"; isClickable = true
            }
        }
        var tint = Tokens.INK
            set(value) { field = value; (ends + choices).forEach { it.color = value } }

        private fun toProgress(v: Float) = (ln(v / min) / ln(max / min) * steps).roundToInt().coerceIn(0, steps)
        private fun fromProgress(p: Int) = round(min * exp(ln(max / min) * p / steps.toFloat())).coerceIn(min, max)
        private fun show(v: Float) { shown.text = millimetres(v); bar.stateDescription = millimetres(v); choices.forEachIndexed { i, dot -> dot.on = abs(presets[i] - v) < .01f } }

        init {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(label(context, label), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(shown)
            })
            bar.max = steps; bar.progress = toProgress(value)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = fromProgress(progress)
                    runCatching { onChange(next) }.onSuccess { show(next) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ends[0]); addView(bar, LayoutParams(0, dpi(40f), 1f)); addView(ends[1])
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
            if (choices.isNotEmpty()) addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; setPadding(dpi(10f), 0, dpi(10f), 0)
                choices.forEachIndexed { i, dot ->
                    dot.setOnClickListener {
                        runCatching { onChange(presets[i]) }.onSuccess {
                            bar.progress = toProgress(presets[i]); show(presets[i]); dot.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    addView(dot, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
            show(value)
        }
        private companion object { val PRESET_DOTS = floatArrayOf(4f, 6f, 9f, 12f, 16f) }
    }

    /** P187: a one-line slider (label, track, value) for a secondary setting under a [SizeControl]. */
    internal fun inlineSlider(context: Context, label: String, min: Float, max: Float, value: Float, round: (Float) -> Float,
        format: (Float) -> String, onChange: (Float) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val steps = 100
        val shown = TextView(context).apply {
            textSize = 13f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(context.night)); fontFeatureSettings = "tnum"
            gravity = Gravity.END; text = format(value); maxLines = 1
        }
        val bar = glassBar(context, label).apply {
            this.max = steps; progress = ((value - min) / (max - min) * steps).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = round(min + (max - min) * progress / steps.toFloat())
                    runCatching { onChange(next) }.onSuccess { shown.text = format(next); stateDescription = format(next) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            stateDescription = format(value)
        }
        if (context.largeText) {
            // No room beside the bar: the name and value go above it.
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(label(context, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(shown)
            })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
            return@apply
        }
        addView(label(context, label), LinearLayout.LayoutParams(dpi(64f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bar, LinearLayout.LayoutParams(0, dpi(40f), 1f))
        // P547: wide enough for "Standard" on one line; a fixed width keeps the bar still while the value changes.
        addView(shown, LinearLayout.LayoutParams(dpi(64f), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * P187: laid out as the Glass board's pen popover: the pen's name (and which saved pen it is), the pen kinds as
     * tiles, the sample stroke, colour, thickness and what holding the pen still does. Opacity, the palettes, saved
     * colours and the eyedropper keep their places inside those sections.
     */
    fun pen(column: LinearLayout, ink: InkCanvas, title: String = "Pen", assistChanged: () -> Unit = {}, custom: () -> Unit) {
        val c = column.context
        val name = title(column, "${ink.penStyle.kind.label} pen", title.takeIf { it != "Pen" })
        val preview = StrokePreview(c, highlighter = false).apply { style = ink.penStyle }
        section(column, Kit.button(c,"Zoom writing box",Kit.Button.TINTED) {ink.onZoomWritingRequested()})
        val finger = Kit.Switch(c, ink.drawWithFinger, "Draw with finger") { enabled -> ink.selectDrawWithFinger(enabled) }
        section(column, Kit.row(c, "Draw with finger", "One finger writes; two fingers move or zoom.", trailing = finger) { finger.performClick() })
        // P415: the hand your writing is rewritten in, tried on a writing pad first.
        Popover.building.let { owner ->
            section(column, Kit.row(c, "Handwriting style", HandStyle.summary(c), chevron = true) { ink.handStyle.flush(); owner?.dismiss(); HandStyleSheet.show(c) }, 4f)
        }
        var choices: PenTypeChoices? = null
        var size: SizeControl? = null
        var refreshSmoothing: () -> Unit = {}
        var nibs: View? = null
        fun choose(style: PenStyle) {
            val previousKind = ink.penStyle.kind
            ink.selectPenStyle(style); val now = ink.penStyle
            preview.style = now; choices?.style = now; size?.tint = now.color.toInt(); name.text = "${now.kind.label} pen"
            nibs?.visibility = if (now.kind.nib != null) View.VISIBLE else View.GONE
            if (previousKind != now.kind) refreshSmoothing()
        }
        choices = PenTypeChoices(c, ink.penStyle) { choose(ink.penStyle.copy(kind = it)) }
        section(column, choices)
        // P618: the fountain pen's two nibs, beside their label; shown only while a fountain pen is in use.
        nibs = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(c, "Nib"))
                addView(note(c, "Soft swells on downstrokes as you press."))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chips(c, listOf("Everyday", "Soft"), if (ink.penStyle.kind == PenKind.FOUNTAIN_SOFT) 1 else 0) {
                choose(ink.penStyle.copy(kind = if (it == 1) PenKind.FOUNTAIN_SOFT else PenKind.FOUNTAIN))
            }.apply { contentDescription = "Fountain pen nib" })
            visibility = if (ink.penStyle.kind.nib != null) View.VISIBLE else View.GONE
        }
        section(column, nibs, GAP - 6f)
        section(column, preview)
        val smoothing = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        refreshSmoothing = {
            smoothing.removeAllViews()
            smoothing.addView(inlineSlider(c, "Smoothing", 0f, 100f, ink.penStyle.smoothing.toFloat(),
                { (it / 5f).roundToInt() * 5f }, PenSmoothing::label) { choose(ink.penStyle.copy(smoothing = it.toInt())) })
        }
        refreshSmoothing(); section(column, smoothing)
        val owner = Popover.building
        val grid = swatches(c, ink.penStyle.color, { choose(ink.penStyle.copy(color = it)) }, custom,
            eyedropper = owner?.let { o -> { o.dismiss(); Eyedropper.arm(ink, highlighter = false) } })
        // P129 (F029): pen palettes, beside the Colour label; the choice is kept in the app's preferences.
        val app = c.applicationContext as NotesApplication
        val colors = InkColors(app.getSharedPreferences(app.preferencesName, Context.MODE_PRIVATE))
        section(column, LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(c).apply {
                orientation = if (c.largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                if (!c.largeText) gravity = Gravity.CENTER_VERTICAL
                addView(label(c, "Colour"), if (c.largeText) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chips(c, InkColors.palettes.map { it.first }, colors.palette) { colors.selectPalette(it); grid.refresh() }
                    .apply { contentDescription = "Pen palette" })
            })
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(4f) })
        }, GAP - 4f)
        size = SizeControl(c, "Thickness", ink.penStyle.width, .5f, 50f, listOf(1f, 2f, 4f, 7f, 12f), ::roundWidth) {
            choose(ink.penStyle.copy(width = it))
        }.apply { tint = ink.penStyle.color.toInt() }
        section(column, size, GAP - 4f)
        // P089: whole-stroke opacity; new strokes keep it, existing ink is unchanged.
        section(column, inlineSlider(c, "Opacity", .1f, 1f, ink.penStyle.opacity, { ((it * 20).roundToInt() / 20f).coerceIn(.1f, 1f) }, { "${(it * 100).roundToInt()} %" }) {
            choose(ink.penStyle.copy(opacity = it))
        }, 4f)
        assist(column, ink, false, assistChanged)
    }
    fun writingPreferences(column: LinearLayout) {
        val c = column.context
        column.addView(heading(c, "Scribble to erase", 12f))
        column.addView(segmented(c, listOf("Off", "On"), if (ScribbleErase.enabled(c)) 1 else 0) {
            ScribbleErase.setEnabled(c, it == 1)
        }.apply { contentDescription = "Scribble to erase" })
        column.addView(demo(GestureDemo(c, GestureDemo.Kind.SCRIBBLE)))
        column.addView(caption(c, "Scribble back and forth over ink to erase it. Undo brings the ink back and keeps the scribble."))
        column.addView(heading(c, "Circle and hold", 12f))
        val circle = GestureDemo(c, GestureDemo.Kind.CIRCLE, LoopSelect.enabled(c))
        column.addView(segmented(c, listOf("Shape", "Select"), if (LoopSelect.enabled(c)) 1 else 0) {
            LoopSelect.setEnabled(c, it == 1); circle.selects = it == 1
        }.apply { contentDescription = "Circle and hold" })
        column.addView(demo(circle))
        column.addView(caption(c, "Select: draw a loop around ink and hold the pen still to select it. Needs Hold assist set to Line or Shapes."))
    }
    /** P539: a gesture demonstration under its control. */
    internal fun demo(view: GestureDemo) = view.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dpi(8f); bottomMargin = context.dpi(4f) } }
    /** P187: the highlighter popover in the pen popover's layout: sample, colour, thickness, PDF lines and hold assist. */
    fun highlighter(column: LinearLayout, ink: InkCanvas, assistChanged: () -> Unit = {}, custom: () -> Unit) {
        val c = column.context
        title(column, "Highlighter")
        val preview = StrokePreview(c, highlighter = true).apply { style = ink.highlighterStyle }
        section(column, preview)
        var size: SizeControl? = null
        fun choose(style: PenStyle) { ink.selectHighlighterStyle(style); preview.style = ink.highlighterStyle; size?.tint = ink.highlighterStyle.color.toInt() }
        val owner = Popover.building
        group(column, "Colour", swatches(c, ink.highlighterStyle.color, { choose(ink.highlighterStyle.copy(color = it)) }, custom, highlighter = true,
            eyedropper = owner?.let { o -> { o.dismiss(); Eyedropper.arm(ink, highlighter = true) } }))
        // P617 (board 11): in the colour card, off at first: going over a highlight adds, never darkens.
        section(column, LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(c, "Mix colours"))
                addView(note(c, "Overlaps darken and blend. Off: going over a highlight never darkens it.").apply { setPadding(0, dpi(2f), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Kit.Switch(c, ink.highlighterMix, "Mix highlighter colours") { ink.selectHighlighterMix(it) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dpi(12f) })
        }, GAP - 6f)
        size = SizeControl(c, "Thickness", ink.highlighterStyle.width, .5f, 50f, listOf(6f, 10f, 14f, 20f, 28f), ::roundWidth) {
            choose(ink.highlighterStyle.copy(width = it))
        }.apply { tint = ink.highlighterStyle.color.toInt() }
        section(column, size, GAP - 4f)
        group(column, "Text line assistance", LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(VisualOptions.well(c, listOf("Freehand", "Follow text lines"), listOf(Pictograms.freeMark, Pictograms.followLines), if (ink.highlighterFollowsText) 1 else 0) {
                ink.selectHighlighterFollowsText(it == 1)
            }.apply { contentDescription = "Highlighter PDF and text-box line assistance" })
            addView(note(c, "Follows PDF, scanned text and text-box lines. Unclear lines stay freehand; Undo restores your mark."))
        })
        assist(column, ink, true, assistChanged)
    }
    /**
     * B27 (P192): restyles selected handwriting, laid out like the pen popover: the pen kinds as tiles (when the selection
     * has [pens]), a sample, colour and thickness. [start] is the first stroke's look; [draft] holds what was changed
     * (kept across a trip to the colour circle through [custom]). Nothing changes until "Apply", which hands [apply] only
     * what was touched here; tapping outside cancels. [highlights]: the selection has highlighter strokes.
     */
    fun selection(column: LinearLayout, start: PenStyle, draft: InkSelection.Restyle, strokes: Int, pens: Boolean, highlights: Boolean,
        custom: (InkSelection.Restyle) -> Unit, apply: (InkSelection.Restyle) -> Unit,
        fillEligible: Boolean = false, initialFill: Long? = null, customFill: (InkSelection.Restyle) -> Unit = {}) {
        val c = column.context
        val marker = !pens
        var change = draft
        fun shown() = PenStyle(change.color ?: start.color, change.width ?: start.width, change.kind ?: start.kind)
        title(column, "Selected ink", if (strokes == 1) "1 stroke" else "$strokes strokes")
        val preview = StrokePreview(c, highlighter = marker).apply { style = shown() }
        var choices: PenTypeChoices? = null
        var size: SizeControl? = null
        fun choose(next: InkSelection.Restyle) {
            change = next; val now = shown()
            preview.style = now; choices?.style = now; size?.tint = now.color.toInt()
        }
        if (pens) {
            choices = PenTypeChoices(c, shown()) { choose(change.copy(kind = it)) }
            section(column, choices)
        }
        section(column, preview)
        group(column, if(fillEligible) "Outline colour" else "Colour", swatches(c, shown().color, { choose(change.copy(color = it)) }, { custom(change) }, highlighter = marker))
        if(fillEligible) {
            val initial=if(change.fillChanged) change.fillColor else initialFill
            val colours=swatches(c,initial ?: start.color,{ choose(change.copy(fillChanged=true,fillColor=it)) },{ customFill(change) })
            colours.contentDescription="Shape fill colours"
            colours.visibility=if(initial == null) View.GONE else View.VISIBLE
            val owner=Popover.building
            group(column,"Fill",Kit.segmented(c,listOf("No fill","Colour"),if(initial == null) 0 else 1) { index ->
                choose(change.copy(fillChanged=true,fillColor=if(index==0) null else change.fillColor ?: initialFill ?: start.color))
                colours.visibility=if(index==0) View.GONE else View.VISIBLE
                owner?.grows = true
            }.apply { contentDescription="Shape fill mode" })
            section(column,colours)
        }
        size = SizeControl(c, "Thickness", shown().width, .5f, 50f,
            if (marker) listOf(6f, 10f, 14f, 20f, 28f) else listOf(1f, 2f, 4f, 7f, 12f), ::roundWidth) {
            choose(change.copy(width = it))
        }.apply { tint = shown().color.toInt() }
        section(column, size, GAP - 4f)
        val error = note(c, "").apply { setTextColor(Tokens.FAULT); visibility = View.GONE }
        val owner = Popover.building
        val applyButton = TextView(c).apply {
            text = "Apply"; textSize = 15f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(Glass.accent(c.night), dp(22f)); minHeight = dpi(44f); setPadding(dpi(22f), 0, dpi(22f), 0)
            contentDescription = "Apply to the selected ink"
            setOnClickListener {
                runCatching { apply(change) }.onSuccess { owner?.dismiss() }
                    .onFailure { error.text = it.message ?: "Cannot restyle the selection now"; error.visibility = View.VISIBLE }
            }
        }
        val hint = if (pens && highlights) "Highlighter strokes keep their tip. Tap outside to cancel." else "Tap outside to cancel."
        section(column, LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(note(c, hint).apply { setPadding(0, 0, dpi(12f), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(applyButton)
        })
        column.addView(error)
    }
    /**
     * B31 (P193): the paint brush popover: what it paints, then the settings it paints with, which are the selected pen's
     * (kind tiles, sample, colour and thickness, as in the pen popover). Highlights take the highlighter's settings.
     */
    fun paint(column: LinearLayout, ink: InkCanvas, changed: () -> Unit, custom: () -> Unit) {
        val c = column.context
        title(column, "Paint brush")
        column.addView(note(c, "Brush over ink to give it these settings. Highlights take the highlighter's colour and thickness.").apply { setPadding(0, dpi(2f), 0, 0) })
        group(column, "Paints", chips(c, InkCanvas.PaintScope.entries.map { it.label }, ink.paintScope.ordinal) {
            ink.selectPaintScope(InkCanvas.PaintScope.entries[it]); changed()
        }.apply { contentDescription = "What the paint brush changes" })
        val preview = StrokePreview(c, highlighter = false).apply { style = ink.penStyle }
        section(column, Kit.button(c,"Zoom writing box",Kit.Button.TINTED) {ink.onZoomWritingRequested()})
        val finger = Kit.Switch(c, ink.drawWithFinger, "Draw with finger") { enabled -> ink.selectDrawWithFinger(enabled) }
        section(column, Kit.row(c, "Draw with finger", "One finger writes; two fingers move or zoom.", trailing = finger) { finger.performClick() })
        var choices: PenTypeChoices? = null
        var size: SizeControl? = null
        fun choose(style: PenStyle) {
            ink.selectPenStyle(style); val now = ink.penStyle
            preview.style = now; choices?.style = now; size?.tint = now.color.toInt(); changed()
        }
        choices = PenTypeChoices(c, ink.penStyle) { choose(ink.penStyle.copy(kind = it)) }
        section(column, choices)
        section(column, preview)
        group(column, "Colour", swatches(c, ink.penStyle.color, { choose(ink.penStyle.copy(color = it)) }, custom))
        size = SizeControl(c, "Thickness", ink.penStyle.width, .5f, 50f, listOf(1f, 2f, 4f, 7f, 12f), ::roundWidth) {
            choose(ink.penStyle.copy(width = it))
        }.apply { tint = ink.penStyle.color.toInt() }
        section(column, size, GAP - 4f)
    }
    private fun assist(column: LinearLayout, ink: InkCanvas, marker: Boolean, changed: () -> Unit) {
        group(column, if (marker) "When you hold the highlighter still" else "When you hold the pen still",
            VisualOptions.well(column.context, listOf("Freehand", "Line", "Shapes"), Pictograms.holdAssist,
                (if (marker) ink.highlighterAssist else ink.penAssist).ordinal) {
                ink.selectHoldAssist(marker, HoldAssist.entries[it])
                changed()
            }.apply { contentDescription = if (marker) "Highlighter hold assist" else "Pen hold assist" })
    }
    fun eraser(column: LinearLayout, ink: InkCanvas, changed: () -> Unit) {
        val c = column.context
        title(column, "Eraser")
        column.addView(note(c, "Also used while the pen button is held.").apply { setPadding(0, dpi(2f), 0, 0) })
        val preview = EraserPreview(c)
        fun show() = preview.apply {
            mode = ink.eraserMode; target = ink.eraserTarget; radius = ink.eraserRadius
            penColor = ink.penStyle.color; markColor = ink.highlighterStyle.color
        }
        section(column, show(), GAP - 4f)
        group(column, "Erase", VisualOptions.well(c, InkCanvas.EraserTarget.entries.map { it.label }, Pictograms.eraserTargets, ink.eraserTarget.ordinal) {
            ink.selectEraserTarget(InkCanvas.EraserTarget.entries[it]); show(); changed()
        }.apply { contentDescription = "Eraser target" })
        group(column, "How it erases", VisualOptions.well(c, listOf("Whole stroke", "Area"), Pictograms.eraserModes, ink.eraserMode.ordinal) {
            check(!ink.isEditing) { "Wait for input completion" }
            ink.eraserMode = InkCanvas.EraserMode.entries[it]; show(); changed()
        }.apply { contentDescription = "Eraser mode" })
        section(column, SizeControl(c, "Size", ink.eraserRadius * 2, 2f, 200f, emptyList(), { it.roundToInt().toFloat() }) {
            ink.selectEraserRadius(it / 2); show()
        }.apply { tint = 0xff9aa3b2.toInt() }, GAP - 4f)
    }
    fun lasso(column: LinearLayout, ink: InkCanvas, changed: () -> Unit) {
        val c = column.context
        title(column, "Lasso")
        val preview = LassoPreview(c)
        fun show() = preview.apply { shape = ink.selectionOptions.shape; rule = ink.selectionOptions.rule }
        section(column, show())
        group(column, "Shape", VisualOptions.well(c, listOf("Freeform", "Rectangle"), Pictograms.lassoShapes, ink.selectionOptions.shape.ordinal) {
            ink.selectLassoOptions(ink.selectionOptions.copy(shape = InkSelection.Shape.entries[it])); show(); changed()
        }.apply { contentDescription = "Lasso shape" })
        group(column, "Select ink that is", VisualOptions.well(c, listOf("Touching", "Fully enclosed"), Pictograms.lassoRules, ink.selectionOptions.rule.ordinal) {
            ink.selectLassoOptions(ink.selectionOptions.copy(rule = InkSelection.Rule.entries[it])); show(); changed()
        }.apply { contentDescription = "Lasso rule" })
        group(column, "Pick up only", VisualOptions.well(c, InkSelection.Filter.entries.map { it.label }, Pictograms.lassoFilters, ink.selectionOptions.filter.ordinal) {
            ink.selectLassoOptions(ink.selectionOptions.copy(filter = InkSelection.Filter.entries[it])); changed()
        }.apply { contentDescription = "Lasso filter" })
    }

    /**
     * P590: the Shapes board. The shape pen first (the frequent choice), then the shapes to insert as tiles, then the
     * ruler: its drawing scale and Show/Hide. Every control runs through the editor's guards via the callbacks.
     */
    fun shapes(column: LinearLayout, ink: InkCanvas, canInsert: Boolean, shapePen: (Boolean) -> Unit, rulerScale: (Int) -> Unit,
               ruler: () -> Unit, insert: (dev.ownednotes.core.ShapeLibrary.Choice) -> Unit) {
        val c = column.context; val night = c.night
        title(column, "Shapes", if (ink.shapePen) "Shape pen on" else null)
        group(column, "Shape pen", LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Kit.segmented(c, listOf("On", "Off"), if (ink.shapePen) 0 else 1) { shapePen(it == 0) }.apply { contentDescription = "Shape pen" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(note(c, "Everything you draw becomes a shape."))
        })
        val tiles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        dev.ownednotes.core.ShapeLibrary.Choice.entries.chunked(SHAPE_COLUMNS).forEachIndexed { r, choices ->
            tiles.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                (0 until SHAPE_COLUMNS).forEach { i ->
                    val choice = choices.getOrNull(i)
                    val tile = if (choice == null) View(c) else EditorFrame.ShapeIcon(c, choice).apply {
                        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33ffffff else 0x1f1d2433),
                            rounded(Kit.group(night), dp(14f)), rounded(-1, dp(14f)))
                        isEnabled = canInsert; alpha = if (canInsert) 1f else .38f
                        setOnClickListener { insert(choice) }
                    }
                    addView(tile, LinearLayout.LayoutParams(0, dpi(58f), 1f).apply { if (i > 0) marginStart = dpi(8f) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = c.dpi(8f) })
        }
        group(column, "Insert a shape", tiles)
        group(column, "Ruler scale", LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Kit.segmented(c, listOf("Screen", "1:1", "1:10", "1:100"), RulerScale.ratios.indexOf(ink.ruler.scaleRatio)) { rulerScale(RulerScale.ratios[it]) }
                .apply { contentDescription = "Ruler scale" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(note(c, "Drawing distances are in cm and follow zoom."))
        })
        section(column, Kit.button(c, if (ink.rulerShown) "Hide ruler" else "Show ruler", Kit.Button.TINTED, small = true) { ruler() }, GAP - 4f)
    }

    /** A 48 dp text row for menus on a Glass pane, day or night; with [icon], a coloured icon square leads it (P177, G2). */
    fun menuRow(context: Context, label: String, enabled: Boolean, icon: Glyph? = null, tint: Int = Glass.ACCENT, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 16f; typeface = Tokens.SANS; gravity = Gravity.CENTER_VERTICAL
        minHeight = dpi(48f); setPadding(dpi(12f), 0, dpi(12f), 0)
        val night = context.night
        setTextColor(if (enabled) Glass.text(night) else if (night) 0x61f2f4f8 else 0x611d2433)
        icon?.let {
            setCompoundDrawablesRelative(IconSquare(it, tint, dpi(28f)).apply { alpha = if (enabled) 255 else 97 }, null, null, null)
            compoundDrawablePadding = dpi(12f)
        }
        isEnabled = enabled; background = ripple(night, dp(Glass.CHIP))
        setOnClickListener { action() }
    }
    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(if (context.night) Glass.RULE_NIGHT else Glass.RULE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)).apply {
            topMargin = dpi(8f); bottomMargin = dpi(8f); marginStart = dpi(12f); marginEnd = dpi(12f)
        }
    }
    /** P605: a section name in an icon-grid menu: 13 sp medium grey, no box around the group. */
    /** P605/P606: the full-width pill that can lead a tile menu ("Find a command", "Back to the notebook"). */
    fun pill(context: Context, text: String, path: String, action: () -> Unit) = TextView(context).apply {
        val night = context.night
        this.text = text; contentDescription = text; textSize = 15f; typeface = Tokens.SANS; gravity = Gravity.CENTER_VERTICAL
        setTextColor(Glass.secondary(night)); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        setCompoundDrawablesRelative(Kit.Icon(path, Glass.secondary(night), dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) }, null, null, null)
        compoundDrawablePadding = dpi(10f); setPadding(dpi(14f), 0, dpi(14f), 0)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33ffffff else 0x1f1d2433),
            rounded(if (night) 0xff353b47.toInt() else 0xffffffff.toInt(), dp(22f), if (night) Glass.RULE_NIGHT else Glass.RULE, dpi(1f)), null)
        setOnClickListener { action() }
    }
    fun pillParams(context: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(44f)).apply { context.dpi(2f).let { setMargins(it, it, it, context.dpi(8f)) } }
    /** P605: tile menus have rounder corners than other popovers, as in the design canvas. */
    const val TILE_MENU_RADIUS = 24f
    fun tileCaption(context: Context, text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(context.night))
        lineHeight = sp(context, 16f); setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(4f))
    }
    /**
     * P605: [tiles] four to a row, each a quarter of the menu's width; a short last row keeps its tiles at the left. With
     * larger system text the rows hold three, then two, so no name has to break inside a word.
     */
    fun tileGrid(context: Context, tiles: List<View>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val columns = tileColumns(context)
        tiles.chunked(columns).forEach { chunk ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                chunk.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                repeat(columns - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    fun tileColumns(context: Context) = context.resources.configuration.fontScale.let { if (it >= 1.6f) 2 else if (it >= 1.25f) 3 else 4 }
    internal fun sp(context: Context, value: Float) =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics).roundToInt()
    /** A grouped card of menu rows in thin glass; groups replace divider lines in Glass menus (P177, G2). */
    fun menuGroup(context: Context, first: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(thin(context.night), dp(Glass.CHIP + 2f))
        setPadding(0, dpi(2f), 0, dpi(2f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (!first) topMargin = dpi(10f) }
    }
}

/**
 * P605: one tile of an icon-grid menu, as the redesign sketches draw it. A 44 dp well in [tint] at 12% (22% at night) holds a
 * 24 dp drawing ([path], see [MenuIcons]) in [tint]; [short] sits under it, 12 sp medium, two lines at most. [label] is the
 * command's full name: what a screen reader says and what holding the tile shows. Greyed at 40% when not [enabled]; a tool
 * that is [on] fills its well and draws in white; [fault] (removes work) names the tile in red.
 */
class MenuTile(context: Context, val label: String, val short: String, path: String, tint: Int, enabled: Boolean,
               on: Boolean = false, fault: Boolean = false, action: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val night = context.night
        minimumHeight = dpi(88f); setPadding(dpi(2f), dpi(8f), dpi(2f), dpi(8f))
        background = ripple(night, dp(16f))
        addView(android.widget.ImageView(context).apply {
            background = rounded(if (on) tint else androidx.core.graphics.ColorUtils.setAlphaComponent(tint, if (night) 56 else 31), dp(14f))
            setImageDrawable(Kit.Icon(path, if (on) Color.WHITE else tint, dpi(24f)))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dpi(44f), dpi(44f)))
        addView(TextView(context).apply {
            text = short; textSize = 12f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; lineHeight = ToolControls.sp(context, 15f)
            setTextColor(if (!fault) Glass.text(night) else if (night) Kit.dangerText(true) else Tokens.FAULT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        contentDescription = label; tooltipText = label
        isSelected = on; isEnabled = enabled; alpha = if (enabled) 1f else .4f
        setOnClickListener { action() }
    }
}

/** A rounded square in [tint] with a white [glyph], leading a Glass menu row (P177, G2). */
class IconSquare(private val glyph: Glyph, private val tint: Int, private val size: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 255
    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.style = Paint.Style.FILL; paint.pathEffect = null
        paint.color = tint; paint.alpha = Color.alpha(tint) * shown / 255
        canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), b.width() * .28f, b.width() * .28f, paint)
        val glyphSize = b.width() * .7f
        canvas.save()
        canvas.translate(b.exactCenterX() - glyphSize / 2f, b.exactCenterY() - glyphSize / 2f)
        canvas.scale(glyphSize / 24f, glyphSize / 24f)
        paint.color = Color.WHITE; paint.alpha = shown
        Glyphs.draw(canvas, glyph, paint)
        canvas.restore()
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { shown = alpha; invalidateSelf() }
    override fun getAlpha() = shown
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
