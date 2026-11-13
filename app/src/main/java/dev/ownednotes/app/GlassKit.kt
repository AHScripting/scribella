package dev.ownednotes.app

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** True when the system is in night mode; every [Kit] part reads it from the context it is built with. */
val Context.night: Boolean
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** F219: text is set much larger than normal, so controls with room for one size of text wrap or rearrange. */
internal val Context.largeText get() = resources.configuration.fontScale > 1.3f

/** F219: a name in a fixed space stays whole: it goes up to [maxSp] with the reader's text size, and below that size down to [minDp] if it must. */
internal fun TextView.shrinkToFit(minDp: Float, maxSp: Float) {
    val least = context.dpi(minDp)
    setAutoSizeTextTypeUniformWithConfiguration(least, maxOf(least + 1, android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp, resources.displayMetrics).toInt()), 1, android.util.TypedValue.COMPLEX_UNIT_PX)
}

/** P639: text drawn on a canvas: [baseDp] at normal text size, growing with the reader's text size up to [maxDp]. Never below [baseDp]. */
internal fun Context.readerSize(baseDp: Float, maxDp: Float): Float =
    maxOf(dp(baseDp), minOf(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, baseDp, resources.displayMetrics), dp(maxDp)))

/** P639: sets [paint] to [most], or smaller (not below [least]) so [text] fits [room]; longer text is still shortened by the caller. */
internal fun Paint.fitText(text: CharSequence, room: Float, most: Float, least: Float) {
    textSize = most
    val wide = measureText(text, 0, text.length)
    if (wide > room && wide > 0f) {
        // Width is not quite in proportion to size (letters snap to pixels), so step down until it really fits.
        textSize = maxOf(least, most * room / wide)
        while (textSize > least && measureText(text, 0, text.length) > room) textSize = maxOf(least, textSize - 0.5f)
    }
}

/** F219: a screen reader says which control an option belongs to, not a bare "Off". */
internal object OptionInGroup : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val group = (host.parent as? View)?.contentDescription
        if (!group.isNullOrBlank()) info.contentDescription = "$group: ${host.contentDescription ?: (host as? TextView)?.text}"
    }
}

/**
 * P201: the shared Glass kit, from the design-system and kit boards. New screens are built from these parts so they
 * look like the rest of the app by day and by night without choosing colours. See docs/GLASS-DESIGN-GUIDE.md.
 *
 * Every part takes the [Context] it is built with and draws for that context's day or night ([night]). Parts that act
 * call their handler inside runCatching and only show the new state when the handler did not throw, like the tool
 * popovers, so busy, read-only and stale guards in the handler keep working.
 */
object Kit {
    // Colours beyond [Glass]'s accent, text and pane.
    const val DONE = 0xff34c759.toInt()
    const val NOTICE = 0xffff9500.toInt()
    const val DANGER = 0xffd93a3a.toInt()
    const val PURPLE = 0xffaf52de.toInt()
    const val TEAL = 0xff30b0c7.toInt()
    const val PINK = 0xffff2d55.toInt()
    const val INDIGO = 0xff5856d6.toInt()
    const val GREY = 0xff8e8e93.toInt()

    fun accentText(night: Boolean) = if (night) 0xff6cb2ff.toInt() else 0xff0a5cc4.toInt()
    fun accentFill(night: Boolean) = if (night) 0x330a84ff else 0x1f0a66d9
    fun dangerText(night: Boolean) = if (night) 0xffff6b63.toInt() else 0xffc02d2d.toInt()
    fun dangerFill(night: Boolean) = if (night) 0x2eff453a else 0x1fd93a3a
    fun done(night: Boolean) = if (night) 0xff30d158.toInt() else DONE
    /** Section caps over a group. */
    fun caps(night: Boolean) = if (night) 0xff8e97a8.toInt() else 0xff6b7487.toInt()
    /** A grouped list card on a pane or sheet. */
    fun group(night: Boolean) = if (night) 0xff343a47.toInt() else 0xfff4f6fa.toInt()
    fun separator(night: Boolean) = if (night) 0x1fffffff else 0xffe3e7ee.toInt()
    /** The well a segmented control or field sits in. */
    fun well(night: Boolean) = if (night) 0x3d8e96a8 else 0x24768096
    /** The selected segment's chip. */
    fun chip(night: Boolean) = if (night) 0xff5b6372.toInt() else Color.WHITE
    fun track(night: Boolean) = if (night) 0xff4a505c.toInt() else 0xffe3e7ee.toInt()
    fun switchOff(night: Boolean) = if (night) 0xff4a505c.toInt() else 0xffd5dae2.toInt()
    /** A sheet's own fill: opaque, so text on it never sits on the page behind. */
    fun sheet(night: Boolean) = if (night) 0xff23272f.toInt() else 0xfffbfcfe.toInt()
    fun desk(night: Boolean) = if (night) 0xff1a1d24.toInt() else Tokens.DESK
    /**
     * P627: full screen. The status bar stays hidden in the app, the Library and the editor alike, and over its sheets;
     * a swipe down from the top edge shows it for a moment. The navigation bar stays.
     */
    fun fullScreen(window: android.view.Window) {
        runCatching {
            window.insetsController?.apply {
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(android.view.WindowInsets.Type.statusBars())
            }
        }
    }
    /** A card straight on the desk (the Settings cards): white by day, graphite by night. */
    fun cardFill(night: Boolean) = if (night) Glass.PANE_NIGHT else Color.WHITE

    /** The board's type scale. Sizes are sp; the app uses the system sans in place of Inter. */
    enum class Type(val size: Float, val weight: Int) {
        LARGE_TITLE(32f, 700), TITLE(24f, 700), HEADLINE(17f, 600), BODY(15f, 400), CAPTION(12.5f, 500);
        val typeface: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, weight, false) }
    }

    /** A line of text in [type]; secondary text is for hints and values. */
    /** [name]: the text holds a name someone typed (a notebook title), which never shrinks far to keep a long word whole (P654). */
    fun text(context: Context, text: CharSequence, type: Type = Type.BODY, secondary: Boolean = false, name: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = type.size; typeface = type.typeface; includeFontPadding = false
        setTextColor(if (secondary) Glass.secondary(context.night) else Glass.text(context.night))
        if (type == Type.LARGE_TITLE || type == Type.TITLE) letterSpacing = -.01f
        setLineSpacing(0f, 1.15f)
        keepWordsWhole(leastShare = if (name) NAME_SHARE else 0f)
    }

    /** A section header over a group: small grey caps, as "PRESSURE" or "STYLUS" on the Settings board. */
    fun section(context: Context, text: String) = TextView(context).apply {
        this.text = text.uppercase(); textSize = 12.5f; typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
        letterSpacing = .024f; setTextColor(caps(context.night)); maxLines = if (context.largeText) Int.MAX_VALUE else 1
        setPadding(context.dpi(4f), context.dpi(18f), context.dpi(4f), context.dpi(8f))
        isFocusable = false; isAccessibilityHeading = true
    }

    // ---------------------------------------------------------------------------------------------------------- Icons

    /**
     * A 24-unit icon: one of the app's [Glyph]s or an SVG path copied from a board (`d` attribute). Drawn as a stroke
     * in [color], or white on a rounded square of [color] when [square] is set (the coloured squares leading menu rows).
     */
    class Icon private constructor(private val glyph: Glyph?, private val path: Path?, var color: Int, private val square: Boolean,
        private val size: Int, private val fill: Boolean) : Drawable() {
        constructor(glyph: Glyph, color: Int, size: Int, square: Boolean = false) : this(glyph, null, color, square, size, false)
        /** [pathData] is SVG path data on a 24-unit grid; [fill] fills it instead of stroking it. */
        constructor(pathData: String, color: Int, size: Int, square: Boolean = false, fill: Boolean = false) :
            this(null, androidx.core.graphics.PathParser.createPathFromPathData(pathData), color, square, size, fill)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shown = 255
        override fun draw(canvas: Canvas) {
            val b = bounds
            var art = b.width().toFloat()
            if (square) {
                paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = color; paint.alpha = Color.alpha(color) * shown / 255
                canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), b.width() * .27f, b.width() * .27f, paint)
                art *= .62f
            }
            canvas.save()
            canvas.translate(b.exactCenterX() - art / 2f, b.exactCenterY() - art / 2f)
            canvas.scale(art / 24f, art / 24f)
            paint.color = if (square) Color.WHITE else color; paint.alpha = (if (square) 255 else Color.alpha(color)) * shown / 255
            if (glyph != null) {
                if (square) paint.strokeWidth = 2f
                Glyphs.draw(canvas, glyph, paint)
            } else if (path != null) {
                paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE; paint.pathEffect = null
                paint.strokeWidth = if (square) 2f else 1.75f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
            }
            canvas.restore()
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { shown = alpha; invalidateSelf() }
        override fun getAlpha() = shown
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Board paths for icons the app's [Glyph] set does not have. */
    object Paths {
        const val SHIELD = "M12 3l7 3v5.5c0 4.3-2.9 8-7 9.5-4.1-1.5-7-5.2-7-9.5V6l7-3zM9 12l2.2 2.2L15.5 10"
        const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
        const val CHEVRON = "M9.5 5.5L16 12l-6.5 6.5"
        const val CHEVRON_DOWN = "M6 9.5l6 6 6-6"
        const val MINUS = "M5 12h14"
        const val PEN = "M4 20l4.2-1 10.6-10.6a2.1 2.1 0 0 0-3-3L5.2 16 4 20zM14.5 6.5l3 3"
        const val HAND = "M8 13V5.5a1.5 1.5 0 0 1 3 0V12M11 11V4a1.5 1.5 0 0 1 3 0v7M14 11V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 7-6.5 7-2.6 0-4-1.3-5.3-3.4L3.4 14.8a1.5 1.5 0 0 1 2.4-1.7L8 15"
        const val PAPER = "M6 3h9l4 4v14H6zM15 3v4h4M9 12h7M9 16h7"
        const val TEXT = "M5 6V4.5h14V6M12 4.5v15M9 19.5h6"
        const val MOON = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
        const val LOCK = "M6 11h12v9.5H6zM8.5 11V7.5a3.5 3.5 0 0 1 7 0V11"
        const val CLOUD = "M7 18.5a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4.5 4.5 0 0 1-.5 9z"
        const val KEYBOARD = "M3 6.5h18v11H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10"
        const val SUN = "M12 7.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"
        const val FOLDER = "M3 6.5h6l2 2h10v10.5H3z"
        const val IMAGE = "M3.5 5h17v14h-17zM3.5 16l5-5 4 4 2.5-2.5 5.5 5.5M15.5 9.5h.01"
        const val DOCUMENT = "M6 3h9l4 4v14H6zM15 3v4h4"
        const val CAMERA = "M3 8h4l1.8-2.5h6.4L17 8h4v11H3zM12 10a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z"
    }

    // -------------------------------------------------------------------------------------------------------- Buttons

    enum class Button { PRIMARY, TINTED, GLASS, DANGER, PLAIN, DARK }

    /**
     * The kit's buttons: a 48 dp pill (36 dp when [small]). PRIMARY is the one main action of a sheet; TINTED a second
     * action; GLASS a floating control (the Library pill); DANGER a destructive action; PLAIN a text action such as
     * Cancel; DARK the small "Done" on a pill. Disabled buttons fade to 40%.
     */
    @android.annotation.SuppressLint("AppCompatCustomView") // the app uses framework widgets throughout
    fun button(context: Context, label: String, style: Button = Button.PRIMARY, glyph: Glyph? = null, small: Boolean = false,
        onClick: () -> Unit) = object : TextView(context) {
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .4f }
    }.apply {
        val night = context.night
        val height = if (small) 36f else 48f
        text = label; textSize = if (small) 14f else 15f; typeface = Type.HEADLINE.typeface; gravity = Gravity.CENTER
        // Keep the default Glass layout; enlarged accessibility text may wrap its action label.
        maxLines = if (context.largeText) 3 else 1
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        minHeight = dpi(height); minimumHeight = dpi(height)
        val pad = if (style == Button.PLAIN) 10f else if (small) 16f else 22f
        setPadding(dpi(pad), 0, dpi(pad), 0)
        val radius = dp(height / 2)
        val (fill, ink) = when (style) {
            Button.PRIMARY -> Glass.accent(night) to Color.WHITE
            Button.TINTED -> accentFill(night) to accentText(night)
            Button.GLASS -> (if (night) Glass.PANE_NIGHT else Glass.PANE) to Glass.text(night)
            Button.DANGER -> dangerFill(night) to dangerText(night)
            Button.PLAIN -> Color.TRANSPARENT to (if (night) accentText(true) else Glass.accent(false)) // night: the lighter accent text (P641)
            Button.DARK -> (if (night) Glass.TEXT_NIGHT else Glass.TEXT) to (if (night) Glass.TEXT else Color.WHITE)
        }
        setTextColor(ink)
        val surface = if (style == Button.GLASS) Glass.pane(context, night, height / 2) else rounded(fill, radius)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if (style == Button.PRIMARY || style == Button.DARK) 0x40ffffff else 0x1f1d2433),
            surface, rounded(-1, radius))
        if (style == Button.PRIMARY) { elevation = dp(3f); outlineSpotShadowColor = Glass.accent(night); outlineAmbientShadowColor = Glass.accent(night) }
        if (style == Button.GLASS) elevation = dp(4f)
        if (glyph != null) {
            setCompoundDrawablesRelative(Icon(glyph, ink, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) }, null, null, null)
            compoundDrawablePadding = dpi(6f)
        }
        isClickable = true; isFocusable = true
        setOnClickListener { runCatching(onClick) }
    }

    /**
     * P646: [view] catches every tap so nothing behind it receives one (a side panel, a full-screen panel), but a screen
     * reader is not offered it as a button, since tapping it does nothing.
     */
    fun blockTouches(view: View) {
        view.isClickable = true
        view.accessibilityDelegate = TOUCH_BLOCKER
    }
    private val TOUCH_BLOCKER = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.isClickable = false; info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
    }

    /** A round 48 dp glass button showing only [glyph]; [label] is what TalkBack and the tooltip say. */
    fun iconButton(context: Context, label: String, glyph: Glyph, dark: Boolean = false, onClick: () -> Unit) = object : View(context) {
        // Always 48 dp: a plain view asked to wrap its content would take all the room it is offered.
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dpi(48f), dpi(48f))
    }.apply {
        val night = context.night
        val ink = if (dark) Color.WHITE else Glass.text(night)
        val icon = Icon(glyph, ink, dpi(22f))
        val disc = if (dark) rounded(if (night) 0xff3a4150.toInt() else Glass.TEXT, dp(24f)) else Glass.pane(context, night, 24f)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433),
            LayerDrawable(arrayOf(disc, icon)).apply { setLayerGravity(1, Gravity.CENTER); setLayerSize(1, dpi(22f), dpi(22f)) }, rounded(-1, dp(24f)))
        elevation = dp(4f); contentDescription = label; tooltipText = label
        minimumWidth = dpi(48f); minimumHeight = dpi(48f)
        layoutParams = ViewGroup.MarginLayoutParams(dpi(48f), dpi(48f))
        isClickable = true; isFocusable = true
        setOnClickListener { runCatching(onClick) }
    }

    // ------------------------------------------------------------------------------------------------------- Controls

    /**
     * The board's switch: 52×32, green when on, with a white knob that slides. It reads as a switch to TalkBack.
     * [onChange] runs before the switch moves; if it throws, the switch stays where it was.
     */
    /** [small] is the 42×26 switch inside a chip (the scan screen's Auto); [night] forces the dark colours on a dark screen. */
    class Switch(context: Context, checked: Boolean, label: String, private val small: Boolean = false,
                 private val night: Boolean = context.night, private val onChange: (Boolean) -> Unit) : View(context) {
        var isChecked = checked; private set
        private var position = if (checked) 1f else 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = ToolControls.Knob(dpi(28f), dp(14f))
        private var motion: ValueAnimator? = null

        init { contentDescription = label; isClickable = true; isFocusable = true; setOnClickListener { toggle() } }

        /** Sets the state without calling the handler, as when the screen refreshes. */
        fun setChecked(value: Boolean) { if (value == isChecked) return; isChecked = value; slide(false) }

        private fun toggle() {
            val next = !isChecked
            if (runCatching { onChange(next) }.isFailure) return
            isChecked = next; slide(true); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        private fun slide(animate: Boolean) {
            motion?.cancel()
            val target = if (isChecked) 1f else 0f
            if (!animate || !Motion.enabled) { position = target; invalidate(); return }
            motion = ValueAnimator.ofFloat(position, target).apply {
                duration = Motion.MEDIUM; interpolator = Motion.easeOut
                addUpdateListener { position = it.animatedValue as Float; invalidate() }; start()
            }
        }
        override fun onMeasure(w: Int, h: Int) = if (small) setMeasuredDimension(dpi(42f), dpi(26f)) else setMeasuredDimension(dpi(52f), dpi(32f))
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val r = h / 2
            paint.color = blend(switchOff(night), done(night), position)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)
            val cx = r + (width - h) * position
            knob.setBounds((cx - r).toInt(), 0, (cx + r).toInt(), h.toInt()); knob.draw(canvas)
        }
        override fun onDetachedFromWindow() { motion?.cancel(); super.onDetachedFromWindow() }
        override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = isChecked
        }
        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xff) + ((((b shr shift) and 0xff) - ((a shr shift) and 0xff)) * t)).roundToInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /**
     * The board's segmented control: a grey well with the chosen option on a raised chip that slides between options.
     * Same contract as [ToolControls.segmented]: one TextView child per option, `isSelected` on the chosen one.
     */
    fun segmented(context: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        segmented(context, options, selected, null, onSelect)

    /**
     * Picture options ([pictures], one per option, drawn 36 dp tall left of the words) make 64 dp segments, as the
     * board's "Apply to: This page / All 12 pages".
     */
    fun segmented(context: Context, options: List<String>, selected: Int, pictures: List<Pictogram>?, onSelect: (Int) -> Unit) =
        SlidingWell(context, context.dp(if (pictures != null) 11f else 9f), chip(context.night)).apply {
            val night = context.night
            background = rounded(well(night), dp(if (pictures != null) 14f else 12f))
            // Auto-sized (smaller) words would otherwise sit on the larger words' baseline, below the middle.
            isBaselineAligned = false
            dpi(if (pictures != null) 4f else 3f).let { setPadding(it, it, it, it) }
            val items = options.mapIndexed { index, label ->
                TextView(context).apply {
                    text = label; textSize = 14f; maxLines = 1; gravity = Gravity.CENTER; includeFontPadding = false
                    minHeight = dpi(if (pictures != null) 64f else 36f); setPadding(dpi(10f), 0, dpi(10f), 0)
                    shrinkToFitWords(11f, 14f); accessibilityDelegate = OptionInGroup
                    pictures?.get(index)?.let { art ->
                        val picture = PictureDrawable(art).apply { setBounds(0, 0, dpi(30f), dpi(38f)) }
                        setCompoundDrawablesRelative(picture, null, null, null); compoundDrawablePadding = dpi(10f)
                        tag = picture; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        // Centre picture and words together: the side padding takes up what they leave of the width.
                        addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                            val tv = v as TextView
                            val inner = picture.bounds.width() + tv.compoundDrawablePadding + tv.paint.measureText(label).toInt()
                            val side = ((r - l - inner) / 2).coerceAtLeast(dpi(6f))
                            if (tv.paddingStart != side) tv.post { tv.setPaddingRelative(side, 0, side, 0) }
                        }
                    }
                }
            }
            fun paint(current: Int, animate: Boolean) {
                items.forEachIndexed { index, item ->
                    val on = index == current
                    item.isSelected = on
                    (item.tag as? PictureDrawable)?.let { it.on = on; item.invalidate() }
                    item.typeface = if (on) Type.HEADLINE.typeface else Tokens.SANS_MEDIUM
                    item.setTextColor(if (on) Glass.text(night) else Glass.secondary(night))
                }
                select(current, animate)
            }
            items.forEachIndexed { index, item ->
                item.setOnClickListener { runCatching { onSelect(index) }.onSuccess { paint(index, true); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
                // P595: with large text the words shrink to fit (as P593's chips): auto-size needs a set height, and each
                // segment's share follows its words, so "Standard" isn't split while "A4" leaves it room.
                if (context.largeText && pictures == null)
                    addView(item, LinearLayout.LayoutParams(0, maxOf(dpi(36f), (item.paint.fontSpacing * 1.3f).toInt()), item.paint.measureText(item.text.toString()) + dp(20f)))
                else addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            paint(selected, false)
        }

    /** The board's slider: a label and value over an 8 dp track filled in accent, with a raised white knob. */
    fun slider(context: Context, label: String, min: Float, max: Float, value: Float, format: (Float) -> String,
        round: (Float) -> Float = { it }, onChange: (Float) -> Unit) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.VERTICAL
        val shown = TextView(context).apply {
            textSize = 13f; typeface = Type.HEADLINE.typeface; setTextColor(Glass.text(night)); fontFeatureSettings = "tnum"; text = format(value)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label; textSize = 13f; typeface = Type.HEADLINE.typeface; setTextColor(Glass.secondary(night)); maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shown)
        })
        val steps = 1000
        addView(SeekBar(context).apply {
            val fill = ClipDrawable(rounded(Glass.accent(night), dp(4f)), Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(rounded(track(night), dp(4f)), fill)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                for (i in 0..1) { setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(i, dpi(8f)) }
            }
            thumb = ToolControls.Knob(dpi(30f), dp(15f)); splitTrack = false; background = null
            setPadding(dpi(15f), 0, dpi(15f), 0)
            contentDescription = label
            this.max = steps; progress = ((value - min) / (max - min) * steps).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = round(min + (max - min) * progress / steps.toFloat()).coerceIn(min, max)
                    runCatching { onChange(next) }.onSuccess { shown.text = format(next) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
    }

    /**
     * P201 step 5: the Selection board's format bar: a 54 dp glass bar of 44 dp [key]s, in groups split by short
     * hairlines. It scrolls sideways when the sheet is narrow.
     */
    fun formatBar(context: Context, groups: List<List<View>>) = android.widget.HorizontalScrollView(context).apply {
        val night = context.night
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        setPadding(dpi(2f), dpi(6f), dpi(2f), dpi(10f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.pane(context, night, 18f); elevation = dp(6f)
            minimumHeight = dpi(54f); setPadding(dpi(7f), dpi(5f), dpi(7f), dpi(5f))
            groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(if (night) Glass.RULE_NIGHT else Glass.RULE) },
                    LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { marginStart = dpi(5f); marginEnd = dpi(5f) })
                group.forEach { key -> addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44f)).apply { marginEnd = dpi(2f) }) }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A 44 dp key for [formatBar]; [label] names it for TalkBack. [mark] lights it like a chosen chip. */
    fun key(context: Context, text: CharSequence, label: String, onTap: (() -> Unit)? = null) = TextView(context).apply {
        this.text = text; contentDescription = label; tooltipText = label
        gravity = Gravity.CENTER; minWidth = dpi(44f); minHeight = dpi(44f); setPadding(dpi(6f), 0, dpi(6f), 0)
        textSize = 16f; typeface = Type.HEADLINE.typeface; fontFeatureSettings = "tnum"
        foreground = ripple(context.night, dp(12f))
        if (onTap != null) setOnClickListener { runCatching(onTap) }
        mark(this, false)
    }

    /** Shows [key] (or any view in a [formatBar]) as on or off: accent at 12% behind accent text, as the board's Bold. */
    fun mark(key: View, on: Boolean) {
        val night = key.context.night
        key.isSelected = on
        key.background = rounded(if (!on) Color.TRANSPARENT else if (night) 0x3d0a84ff else 0x1f0a66d9, key.dp(12f))
        (key as? TextView)?.setTextColor(if (on) accentText(night) else Glass.text(night))
    }

    /** A − value + stepper for small counts (line spacing, copies). [format] shows the value between the buttons. */
    fun stepper(context: Context, label: String, value: Int, range: IntRange, format: (Int) -> String = { it.toString() },
        onChange: (Int) -> Unit) = Stepper(context, label, value, range, format, onChange)

    /** The [stepper]: children are the − button, the value, the + button. */
    class Stepper(context: Context, label: String, value: Int, private val range: IntRange, private val format: (Int) -> String,
        private val onChange: (Int) -> Unit) : LinearLayout(context) {
        private val night = context.night
        var value = value.coerceIn(range.first, range.last); private set
        private val shown = TextView(context).apply {
            textSize = 15f; typeface = Type.HEADLINE.typeface; setTextColor(Glass.text(night)); gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"; minWidth = dpi(56f); text = format(this@Stepper.value)
        }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(well(night), dp(12f)); dpi(3f).let { setPadding(it, it, it, it) }
            addView(step(-1, Icon(Paths.MINUS, Glass.text(night), dpi(18f)), "Less $label"), LayoutParams(dpi(40f), dpi(34f)))
            addView(shown)
            addView(step(1, Icon(Glyph.PLUS, Glass.text(night), dpi(18f)), "More $label"), LayoutParams(dpi(40f), dpi(34f)))
        }
        private fun step(by: Int, icon: Icon, name: String) = View(context).apply {
            background = LayerDrawable(arrayOf(rounded(chip(night), dp(9f)), icon)).apply {
                setLayerGravity(1, Gravity.CENTER); setLayerSize(1, dpi(18f), dpi(18f))
            }
            contentDescription = name; isClickable = true
            setOnClickListener {
                val next = (this@Stepper.value + by).coerceIn(range.first, range.last)
                if (next != this@Stepper.value) runCatching { onChange(next) }.onSuccess { set(next); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
        }
        /** Shows [next] without calling the handler, as when another choice changes the value. */
        fun set(next: Int) { value = next.coerceIn(range.first, range.last); shown.text = format(value) }
    }

    /**
     * A row of filter chips (34 dp), one chosen: dark when chosen, pale otherwise, as the Library's filters and the
     * scan modes. Each chip is a TextView with `isSelected` on the chosen one. Wrap it in a HorizontalScrollView when
     * the labels may not fit.
     */
    fun chips(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL
        val items = labels.map { label ->
            TextView(context).apply {
                text = label; textSize = 14f; typeface = Type.HEADLINE.typeface; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
                minHeight = dpi(34f); setPadding(dpi(14f), 0, dpi(14f), 0)
            }
        }
        fun paint(current: Int) = items.forEachIndexed { index, item ->
            val on = index == current
            item.isSelected = on
            item.background = rounded(if (on) Glass.text(night) else if (night) 0x1affffff else 0xfff1f3f7.toInt(), item.dp(17f))
            item.setTextColor(if (on) (if (night) Glass.TEXT else Color.WHITE) else Glass.text(night))
        }
        items.forEachIndexed { index, item ->
            item.setOnClickListener { runCatching { onSelect(index) }.onSuccess { paint(index); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
            addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dpi(8f) })
        }
        paint(selected)
    }

    /** A text field on a grey well: search, titles. [glyph] leads it (Search). */
    fun field(context: Context, hint: String, value: String = "", glyph: Glyph? = null) = EditText(context).apply {
        val night = context.night
        this.hint = hint; setText(value); textSize = 15f; typeface = Tokens.SANS; isSingleLine = true
        setTextColor(Glass.text(night)); setHintTextColor(Glass.secondary(night))
        background = rounded(well(night), dp(12f)); minHeight = dpi(44f)
        setPadding(dpi(12f), 0, dpi(12f), 0)
        if (glyph != null) {
            setCompoundDrawablesRelative(Icon(glyph, Glass.secondary(night), dpi(18f)).apply { setBounds(0, 0, dpi(18f), dpi(18f)) }, null, null, null)
            compoundDrawablePadding = dpi(8f)
        }
    }

    // ---------------------------------------------------------------------------------------------- Groups and rows

    /**
     * A grouped list card: rows on a rounded pale card with inset hairlines between them, as on the Settings board.
     * Add rows with [row]; separators are drawn between visible rows. On the desk (Settings) pass [cardFill] and a 20 dp
     * radius: white cards by day, graphite by night.
     */
    class Group(context: Context, fill: Int = group(context.night), radius: Float = 16f) : LinearLayout(context) {
        private val night = context.night
        private val line = Paint().apply { color = separator(night); strokeWidth = context.dp(1f) / 1.5f }
        init { orientation = VERTICAL; background = rounded(fill, context.dp(radius)); clipToOutline = true; setWillNotDraw(false) }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility == VISIBLE }
            for (i in 0 until shown.size - 1) {
                val child = shown[i]
                val inset = (child.getTag(R_INSET) as? Int) ?: dpi(16f)
                canvas.drawLine(inset.toFloat(), child.bottom.toFloat(), width.toFloat(), child.bottom.toFloat(), line)
            }
        }
    }
    private val R_INSET = R.id.kit_inset

    /**
     * One row of a [Group] or menu: an optional coloured [icon] square, a [title] with an optional [detail] line, then
     * a [value] ("Bottom") and chevron, or a [trailing] view such as a [Switch]. [menu] rows are 46 dp, list rows 52.
     * A row with [onClick] is a button; its title is what TalkBack reads. [danger] colours the title red.
     */
    fun row(context: Context, title: String, detail: String? = null, icon: Drawable? = null, value: String? = null,
        trailing: View? = null, chevron: Boolean = false, menu: Boolean = false, danger: Boolean = false,
        onClick: (() -> Unit)? = null) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(if (detail != null) 60f else if (menu) 46f else 52f)
        setPadding(dpi(if (menu) 12f else 16f), dpi(6f), dpi(if (menu) 12f else 14f), dpi(6f))
        var inset = dpi(if (menu) 12f else 16f)
        if (icon != null) {
            val size = dpi(30f)
            addView(View(context).apply { background = icon; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = dpi(12f) })
            inset += size + dpi(12f)
        }
        setTag(R_INSET, inset)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title; textSize = 15f; typeface = Tokens.SANS; includeFontPadding = false
                setTextColor(if (danger) dangerText(night) else Glass.text(night)); maxLines = if (context.largeText) Int.MAX_VALUE else 2
                keepWordsWhole()
            })
            if (detail != null) addView(TextView(context).apply {
                text = detail; textSize = 12.5f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night)); maxLines = if (context.largeText) Int.MAX_VALUE else 2
                setPadding(0, dpi(3f), 0, 0); setLineSpacing(0f, 1.1f)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) addView(TextView(context).apply {
            text = value; textSize = 15f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night)); maxLines = 1
            setPadding(dpi(8f), 0, dpi(4f), 0)
        })
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) })
        if (chevron) addView(View(context).apply {
            background = Icon(Paths.CHEVRON, if (night) 0x66ffffff else 0x661d2433, dpi(16f)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dpi(16f), dpi(16f)))
        if (onClick != null) {
            isClickable = true; isFocusable = true
            background = ripple(night, 0f)
            contentDescription = listOfNotNull(title, value).joinToString(", ")
            setOnClickListener { runCatching(onClick) }
        } else if (trailing is Switch) {
            // The whole row flips its switch, so the target is the row, not the 52 dp switch.
            setOnClickListener { trailing.performClick() }
        }
        if (trailing is Switch) {
            // P646: one stop for a screen reader, which says the title and the switch's state; the switch itself is skipped.
            trailing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Switch::class.java.name; info.isCheckable = true; info.isChecked = trailing.isChecked
                }
            }
        }
    }

    /** A coloured 30 dp icon square leading a row. */
    fun square(context: Context, glyph: Glyph, color: Int) = Icon(glyph, color, context.dpi(30f), square = true)
    fun square(context: Context, pathData: String, color: Int) = Icon(pathData, color, context.dpi(30f), square = true)

    /** A floating glass card for menus and popovers: rounded pane, hairline edge and shadow. */
    fun card(context: Context, radius: Float = 22f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.pane(context, context.night, radius); elevation = dp(12f)
        dpi(6f).let { setPadding(it, it, it, it) }
    }

    /** The green privacy note with a shield: "Your ink never leaves this device…". */
    fun privacy(context: Context, text: String) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = rounded(if (night) 0x2930d158 else 0x1a34c759, dp(14f))
        setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        addView(View(context).apply { background = Icon(Paths.SHIELD, done(night), dpi(22f)) }, LinearLayout.LayoutParams(dpi(22f), dpi(22f)).apply { marginEnd = dpi(10f) })
        addView(TextView(context).apply {
            this.text = text; textSize = 13.5f; typeface = Tokens.SANS_MEDIUM; setLineSpacing(0f, 1.1f)
            setTextColor(if (night) 0xff7ee2a0.toInt() else 0xff1f7a3a.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ---------------------------------------------------------------------------------------------------------- Tiles

    /**
     * A visual choice: [art] (a paper, a cover, a format) over a [label], with an accent ring and a check badge when
     * chosen, as the paper and export tiles. Read by TalkBack as a radio-like button named [label].
     * P613: [compact] is the Page & paper board's small tile: the art alone in a 60×80 frame (the art draws its own page),
     * a 12 dp name under it, and a ring with no badge when chosen. Its width comes from the grid cell.
     */
    class Tile(context: Context, art: View, label: String, chosen: Boolean, private val compact: Boolean = false, private val onChoose: () -> Unit) : LinearLayout(context) {
        private val night = context.night
        private val frame = FrameLayout(context)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(2.5f); color = Glass.accent(night) }
        private val badge = Icon(Paths.CHECK, Color.WHITE, context.dpi(12f))
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.accent(night) }
        private val name = TextView(context).apply {
            text = label; textSize = if (compact) 12f else 13f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            // A tile's width is fixed, so at large text its name shrinks only as far as it must to stay whole.
            if (context.largeText) shrinkToFit(11f, if (compact) 12f else 13f)
        }
        var chosen = chosen
            set(value) { field = value; paint() }
        /** The other tiles of a single choice ([oneOf]); choosing this tile clears them. */
        internal var siblings: List<Tile> = emptyList()

        init {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setWillNotDraw(false)
            if (compact) {
                frame.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(frame, LayoutParams(dpi(60f), dpi(80f)))
                addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(5f) })
                setPadding(0, dpi(4f), 0, dpi(2f))
            } else {
            frame.background = rounded(if (night) 0x14ffffff else 0xffffffff.toInt(), dp(14f), if (night) Glass.RULE_NIGHT else Glass.RULE, 1)
            frame.clipToOutline = true
            dpi(6f).let { frame.setPadding(it, it, it, it) }
            frame.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (context.largeText) dpi(32f) else ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
            dpi(4f).let { setPadding(it, it, it, it) }
            }
            contentDescription = label; isClickable = true; isFocusable = true
            setOnClickListener {
                if (siblings.isNotEmpty() && this.chosen) return@setOnClickListener
                if (runCatching(onChoose).isSuccess) {
                    siblings.forEach { it.chosen = false }; this.chosen = true; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            paint()
        }
        private fun paint() {
            isSelected = chosen
            name.typeface = if (chosen) Type.HEADLINE.typeface else Tokens.SANS
            name.setTextColor(if (chosen) accentText(night) else Glass.text(night))
            frame.getChildAt(0)?.invalidate(); invalidate()
        }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!chosen) return
            if (compact) {
                val r = RectF(frame.left - dp(3f), frame.top - dp(3f), frame.right + dp(3f), frame.bottom + dp(3f))
                ring.strokeWidth = dp(2f); canvas.drawRoundRect(r, dp(11f), dp(11f), ring); return
            }
            val r = RectF(frame.left - dp(2f), frame.top - dp(2f), frame.right + dp(2f), frame.bottom + dp(2f))
            canvas.drawRoundRect(r, dp(16f), dp(16f), ring)
            val cx = r.right - dp(10f); val cy = r.top + dp(10f)
            canvas.drawCircle(cx, cy, dp(10f), disc)
            badge.setBounds((cx - dp(7f)).toInt(), (cy - dp(7f)).toInt(), (cx + dp(7f)).toInt(), (cy + dp(7f)).toInt())
            badge.draw(canvas)
        }
        override fun getAccessibilityClassName(): CharSequence = android.widget.RadioButton::class.java.name
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = chosen
        }
    }

    /** Makes the [tiles] a single choice: choosing one clears the others. Tiles keep their own guarded handlers. */
    fun oneOf(tiles: List<Tile>) = tiles.forEach { tile -> tile.siblings = tiles - tile }

    /** A [Pictogram] (a page, a paper, a size) as a view, for a [Tile]'s art. It redraws when its tile is chosen. */
    fun picture(context: Context, art: Pictogram) = object : View(context) {
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            art.draw(canvas, box, (parent?.parent as? View)?.isSelected == true)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** A [Pictogram] as a drawable, for a picture segment. */
    internal class PictureDrawable(private val art: Pictogram) : Drawable() {
        var on = false
        private val box = RectF()
        override fun draw(canvas: Canvas) { box.set(bounds); art.draw(canvas, box, on) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * A row of 36 dp colour dots, one chosen with an accent ring, as the paper board's "Paper colour". [lines] draws
     * each colour as three lines on white, for line colours. [onSelect] gets the index; if it throws, nothing moves.
     * [mark] shows a colour as chosen without calling the handler (none, when it is not one of the dots).
     * P613: [compact] dots are 26 dp on a 34 dp pitch, as the Page & paper board's Colour and Lines rows.
     */
    class Swatches(context: Context, private val colors: List<Pair<Int, String>>, selected: Int?, private val lines: Boolean = false,
        private val compact: Boolean = false, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
        private val night = context.night
        private val dots: List<View> = colors.mapIndexed { index, (color, name) ->
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onMeasure(w: Int, h: Int) = if (compact) setMeasuredDimension(dpi(34f), dpi(34f)) else setMeasuredDimension(dpi(46f), dpi(46f))
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f; val r = dp(if (compact) 13f else 18f)
                    if (isSelected) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (compact) 2f else 2.5f); paint.color = Glass.accent(night)
                        canvas.drawCircle(cx, cy, r + dp(if (compact) 3f else 4f), paint)
                    }
                    paint.style = Paint.Style.FILL; paint.color = if (lines) Color.WHITE else color
                    canvas.drawCircle(cx, cy, r, paint)
                    if (lines) {
                        paint.color = color; paint.strokeWidth = dp(if (compact) 1.6f else 2f)
                        val half = r * .56f; val step = r * .28f
                        for (i in -1..1) canvas.drawLine(cx - half, cy + i * step, cx + half, cy + i * step, paint)
                    }
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) 0x4dffffff else 0x241d2433
                    canvas.drawCircle(cx, cy, r, paint)
                }
            }.apply {
                contentDescription = name; tooltipText = name; isClickable = true; isFocusable = true
                setOnClickListener { if (runCatching { onSelect(index) }.isSuccess) { show(index); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
            }
        }
        init {
            orientation = HORIZONTAL
            dots.forEach { addView(it) }
            show(selected)
        }
        private fun show(index: Int?) = dots.forEachIndexed { i, dot ->
            dot.isSelected = i == index; dot.stateDescription = if (i == index) "Selected" else null; dot.invalidate()
        }
        /** Rings the dot of [color], or none. */
        fun mark(color: Int) = show(colors.indexOfFirst { it.first == color }.takeIf { it >= 0 })
    }

    // ---------------------------------------------------------------------------------------------- Sheets and toasts

    /**
     * A Glass sheet: a header with [cancel] on the left, the [title] in the middle and the main [action] on the right,
     * over a scrolling [body]. By default it rises from the bottom edge, at most [maxWidth] dp wide; [centred] shows it
     * as a floating card instead. [onAction] returns true to close the sheet, false to keep it open (a guard refused).
     */
    /**
     * P595: a sheet's header. The title is centred between Cancel and the action when it fits there; when it doesn't (a
     * phone with large text: "Cancel" ran into "New notebook" and Create covered its end), it takes its own line under
     * them, with the whole width. Either way it never runs under a button.
     */
    internal class SheetHeader(context: Context) : ViewGroup(context) {
        var start: View? = null; var title: TextView? = null; var end: View? = null
        var stacked = false; private set
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            listOfNotNull(start, end).forEach { it.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), free) }
            val side = maxOf(start?.measuredWidth ?: 0, end?.measuredWidth ?: 0)
            val room = inner - 2 * (side + context.dpi(8f))
            val t = title
            val buttons = maxOf(start?.measuredHeight ?: 0, end?.measuredHeight ?: 0)
            var content = buttons
            val row = maxOf(buttons, minimumHeight - paddingTop - paddingBottom)
            if (t != null) {
                val natural = t.paint.measureText(t.text.toString()) + t.compoundPaddingLeft + t.compoundPaddingRight
                stacked = natural > room
                t.measure(MeasureSpec.makeMeasureSpec(if (stacked) inner else room.coerceAtLeast(0), MeasureSpec.AT_MOST), free)
                content = if (stacked) row + t.measuredHeight else maxOf(buttons, t.measuredHeight)
            }
            setMeasuredDimension(width, maxOf(minimumHeight, content + paddingTop + paddingBottom))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l; val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val buttons = maxOf(start?.measuredHeight ?: 0, end?.measuredHeight ?: 0)
            val row = if (stacked) maxOf(buttons, minimumHeight - paddingTop - paddingBottom) else b - t - paddingTop - paddingBottom
            fun place(v: View?, atStart: Boolean) = v?.let {
                val left = if (atStart != rtl) paddingLeft else w - paddingRight - it.measuredWidth
                val y = paddingTop + (row - it.measuredHeight) / 2
                it.layout(left, y, left + it.measuredWidth, y + it.measuredHeight)
            }
            place(start, true); place(end, false)
            title?.let {
                val x = (w - it.measuredWidth) / 2
                val y = if (stacked) paddingTop + row else paddingTop + (row - it.measuredHeight) / 2
                it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
            }
        }
    }

    class Sheet(val context: Context, title: String, private val action: String? = null, cancel: String = "Cancel",
        private val maxWidth: Float = 760f, private val centred: Boolean = false, subtitle: String? = null, private val onAction: () -> Boolean = { true }) {
        private val night = context.night
        val dialog = Dialog(context)
        /** Add the sheet's content here. */
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22f), dpi(4f), dpi(22f), dpi(22f))
        }
        val actionButton: TextView?
        val titleView: TextView

        init {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val r = dp(Glass.SHEET)
                background = GradientDrawable().apply {
                    setColor(sheet(night))
                    cornerRadii = if (centred) FloatArray(8) { r } else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setStroke(1, if (night) Glass.EDGE_NIGHT else Glass.EDGE)
                }
                clipToOutline = true
            }
            if (!centred) root.addView(View(context).apply { background = rounded(if (night) 0x40ffffff else 0x261d2433, dp(2.5f)) },
                LinearLayout.LayoutParams(context.dpi(40f), context.dpi(5f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = context.dpi(8f) })
            val header = SheetHeader(context).apply { minimumHeight = dpi(56f); setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(4f)) }
            header.start = button(context, cancel, Button.PLAIN) { dialog.dismiss() }.also { header.addView(it) }
            titleView = text(context, title, Type.HEADLINE).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isAccessibilityHeading = true }
            header.title = titleView.also { header.addView(it) }
            actionButton = action?.let { label ->
                button(context, label, Button.PRIMARY, small = true) { if (onAction()) dialog.dismiss() }.also { header.end = it; header.addView(it) }
            }
            root.addView(header)
            // P582: what the sheet acts on (a notebook's long name) goes on its own line under the title, with the sheet's whole
            // width, so the title stays short and nothing is cut between the Cancel and action buttons on a phone.
            subtitle?.let { line -> root.addView(text(context, line, Type.BODY).apply {
                setTextColor(Glass.secondary(night)); gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(22f), 0, dpi(22f), dpi(6f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            root.addView(ScrollView(context).apply { isFillViewport = true; addView(body) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setWindowAnimations(if (centred) android.R.style.Animation_Dialog else android.R.style.Animation_InputMethod)
                setDimAmount(.28f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }

        /** Adds [view] to the body with the kit's 16 dp gap above it. */
        fun add(view: View, gap: Float = 16f) = view.also {
            body.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (body.childCount > 0) topMargin = context.dpi(gap) })
        }

        fun show(): Sheet {
            dialog.show()
            tieToActivity()
            dialog.window?.let { fullScreen(it) }
            fit()
            return this
        }
        /** Sizes the sheet to the screen as it is now; again after the tablet turns (P644). */
        private fun fit() {
            val metrics = context.resources.displayMetrics
            dialog.window?.apply {
                setGravity(if (centred) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                val tall = (metrics.heightPixels * if (centred) .82f else .9f).toInt()
                setLayout(minOf(metrics.widthPixels - if (centred) context.dpi(48f) else 0, context.dpi(maxWidth)), ViewGroup.LayoutParams.WRAP_CONTENT)
                decorView.setPadding(0, 0, 0, 0)
                decorView.post { if (decorView.height > tall) setLayout(attributes.width, tall) }
            }
        }
        fun dismiss() = dialog.dismiss()

        private val turnedListeners = mutableListOf<() -> Unit>()
        /**
         * P650: runs [arrange] each time the screen's width changes while the sheet is open (after the sheet is sized
         * again), so a layout picked for the width at opening (columns side by side or stacked) is picked again after
         * the tablet turns instead of squeezing two columns onto a phone held upright.
         */
        fun onTurned(arrange: () -> Unit) { turnedListeners += arrange }

        private var tied = false
        /**
         * P642: when the activity ends (the tablet turns, or the app closes) with this sheet open, its window goes with it
         * instead of leaking. Its listeners are cleared first, as the system's own clean-up does, so nothing runs on the
         * ending activity. Watches only while the sheet is on screen.
         */
        private fun tieToActivity() {
            if (tied) return
            val owner = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<androidx.lifecycle.LifecycleOwner>().firstOrNull() ?: return
            tied = true
            val ending = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY && dialog.isShowing) {
                    dialog.setOnDismissListener(null); dialog.setOnCancelListener(null); runCatching { dialog.dismiss() }
                }
            }
            owner.lifecycle.addObserver(ending)
            // P644: the app turns without being rebuilt, so a sheet open while the tablet turns is sized again for the new screen.
            val screen = (owner as? android.app.Activity)?.window?.decorView
            val turned = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft && dialog.isShowing) { fit(); turnedListeners.forEach { it() } }
            }
            screen?.addOnLayoutChangeListener(turned)
            dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { owner.lifecycle.addObserver(ending); screen?.removeOnLayoutChangeListener(turned); screen?.addOnLayoutChangeListener(turned) }
                override fun onViewDetachedFromWindow(v: View) { owner.lifecycle.removeObserver(ending); screen?.removeOnLayoutChangeListener(turned) }
            })
        }
    }

    /**
     * The board's undo toast: a 52 dp glass pill with a coloured dot, the message and an Undo button, near the bottom of
     * [host]. It leaves after four seconds; [onUndo] runs at most once.
     * P201 step 5: [compact] is the Selection board's shape-snap pill (40 dp, semibold caption, tinted action); with
     * [topMargin] the pill hangs from the top of [host] instead.
     */
    fun toast(host: ViewGroup, message: String, dot: Int = DONE, actionLabel: String? = "Undo", compact: Boolean = false, topMargin: Int? = null, onAction: (() -> Unit)? = null): View {
        val context = host.context
        val night = context.night
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.pane(context, night, if (compact) 20f else 26f); elevation = dp(10f)
            if (compact) { minimumHeight = dpi(40f); setPadding(dpi(14f), dpi(5f), dpi(5f), dpi(5f)) }
            else { minimumHeight = dpi(52f); setPadding(dpi(18f), dpi(8f), dpi(8f), dpi(8f)) }
            addView(View(context).apply { background = rounded(dot, dp(4f)) }, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { marginEnd = dpi(if (compact) 8f else 10f) })
            addView(text(context, message).apply {
                maxLines = 2
                if (compact) { typeface = Tokens.SANS_SEMIBOLD; textSize = 13.5f } else typeface = Tokens.SANS_MEDIUM
            })
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var gone = false
        fun leave() {
            if (gone) return; gone = true
            if (Motion.enabled) pill.animate().alpha(0f).translationY(pill.dp(16f)).setDuration(Motion.MEDIUM).withEndAction { host.removeView(pill) }.start()
            else host.removeView(pill)
        }
        if (actionLabel != null && onAction != null) pill.addView(button(context, actionLabel, if (compact) Button.TINTED else Button.PRIMARY, small = true) {
            if (!gone) { leave(); onAction() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, if (compact) context.dpi(30f) else ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(if (compact) 10f else 14f) })
        else pill.setPadding(pill.paddingLeft, pill.paddingTop, context.dpi(20f), pill.paddingBottom)
        val params = if (topMargin != null) FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { this.topMargin = topMargin }
        else FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = context.dpi(28f) }
        host.addView(pill, params)
        if (Motion.enabled) { pill.alpha = 0f; pill.translationY = pill.dp(16f); pill.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.easeOut).start() }
        pill.postDelayed(::leave, 4000)
        return pill
    }
}
