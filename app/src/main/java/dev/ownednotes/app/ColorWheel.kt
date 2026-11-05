package dev.ownednotes.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P190: the colour circle pop-out for the pen and highlighter, in a tool [Popover]: hue around the circle, paler toward
 * the middle, a brightness slider under it and a before/new preview. Nothing changes until "Use colour"; tapping outside
 * cancels and "Before" goes back to the colour it opened with. The hex code is optional.
 * P200: [compact] is the quick ring's smaller circle: no title, hex code or note, with "Use" beside Before/New.
 */
class ColorWheel(context: Context, private val initial: Long, highlighter: Boolean, compact: Boolean = false, private val use: (Long) -> Unit) : LinearLayout(context) {
    private val hsv = FloatArray(3)
    /** The colour "Use colour" applies, opaque ARGB. */
    var color = initial; private set
    val disc = Disc(context)
    private val track = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.BLACK, Color.WHITE)).apply { cornerRadius = context.dp(5f) }
    val brightness = SeekBar(context).apply {
        max = 100
        progressDrawable = LayerDrawable(arrayOf(track)).apply { setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dpi(10f)) }
        thumb = ToolControls.Knob(dpi(34f), dp(15f)); splitTrack = false; background = null
        setPadding(dpi(17f), 0, dpi(17f), 0)
        contentDescription = "Brightness"
    }
    val before = half(context, "Before", true).apply {
        contentDescription = "Before: back to the colour it had"; isClickable = true; isFocusable = true
        setOnClickListener { show(initial, fromHex = false); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    }
    val after = half(context, "New", false)
    val hex = EditText(context).apply {
        setSingleLine(); hint = "#RRGGBB"; contentDescription = "Hex colour"; textSize = 13f; typeface = Tokens.SANS_MEDIUM
        setTextColor(Glass.text(context.night)); fontFeatureSettings = "tnum"; gravity = Gravity.CENTER
        background = rounded(ToolControls.wellFill(context.night), dp(12f)); setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }
    val useButton = TextView(context).apply {
        text = "Use colour"; textSize = 15f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = rounded(Glass.accent(context.night), dp(22f)); minHeight = dpi(44f); setPadding(dpi(22f), 0, dpi(22f), 0)
        isClickable = true; isFocusable = true; contentDescription = "Use colour"
    }
    private val problem = ToolControls.note(context, "").apply { visibility = GONE; setTextColor(Kit.dangerText(context.night)) }
    private var changing = false
    /** The pop-out holding this wheel, which "Use colour" closes. */
    private val popover = Popover.building

    init {
        orientation = VERTICAL
        if (!compact) ToolControls.title(this, "Any colour", if (highlighter) "Highlighter" else "Pen")
        else disc.contentDescription = if (highlighter) "Highlighter colour circle" else "Pen colour circle"
        val side = dpi(if (compact) 200f else 244f)
        addView(disc, LayoutParams(side, side).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(if (compact) 0f else 16f) })
        addView(LinearLayout(context).apply {
            // At large text the name has no room beside the bar.
            val stacked = context.largeText && !compact
            orientation = if (stacked) VERTICAL else HORIZONTAL; if (!stacked) gravity = Gravity.CENTER_VERTICAL
            if (!compact) addView(ToolControls.label(context, "Brightness"), LayoutParams(if (stacked) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(80f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(brightness, if (stacked) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)) else LayoutParams(0, dpi(40f), 1f))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(if (compact) 8f else 12f) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(before, LayoutParams(0, dpi(44f), 1f)); addView(after, LayoutParams(0, dpi(44f), 1f))
            if (compact) addView(useButton.apply { text = "Use"; setPadding(dpi(18f), 0, dpi(18f), 0) }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(10f) })
            else addView(hex, LayoutParams(dpi(if (context.largeText) 140f else 104f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(12f) })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(if (compact) 8f else 12f) })
        addView(problem)
        if (!compact) addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(ToolControls.note(context, "Tap outside to cancel.").apply { setPadding(0, 0, 0, 0) }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(useButton)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(16f) })

        disc.changed = { hue, saturation, first ->
            hsv[0] = hue; hsv[1] = saturation
            // From black or a very dark ink, touching the circle should show the colour, not more black.
            if (first && hsv[2] < .25f) hsv[2] = 1f
            showHsv()
        }
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (!changing) { hsv[2] = progress / 100f; showHsv() } }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (changing) return
                parse(s.toString())?.let { show(it, fromHex = true) }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        useButton.setOnClickListener {
            if (hex.hasFocus() && parse(hex.text.toString()) == null) { fail("Enter six hex digits, or choose on the circle."); return@setOnClickListener }
            val owner = popover
            runCatching { use(color) }.onSuccess { owner?.dismiss() }.onFailure { fail(it.message ?: "Cannot use this colour now") }
        }
        show(initial, fromHex = false)
    }

    private fun fail(message: String) { problem.text = message; problem.visibility = VISIBLE }
    private fun parse(text: String) = text.trim().removePrefix("#").takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) }?.let { 0xff000000L or it.toLong(16) }

    /** Shows [value] everywhere; a grey keeps the hue the circle had, so turning brightness up again stays on it. */
    private fun show(value: Long, fromHex: Boolean) {
        val hue = hsv[0]
        Color.colorToHSV(value.toInt(), hsv)
        if (hsv[1] == 0f) hsv[0] = hue
        color = value and 0xffffffffL or 0xff000000L
        refresh(updateHex = !fromHex)
    }
    private fun showHsv() { color = Color.HSVToColor(hsv).toLong() and 0xffffffffL; refresh(updateHex = true) }
    private fun refresh(updateHex: Boolean) {
        changing = true
        try {
            disc.show(hsv[0], hsv[1], hsv[2])
            brightness.progress = (hsv[2] * 100).roundToInt()
            brightness.stateDescription = "${brightness.progress} %"
            track.colors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            paint(before, initial); paint(after, color)
            after.contentDescription = "New colour: ${ColorNames.of(color)}, #%06X".format(color and 0xffffff)
            if (updateHex) hex.setText("#%06X".format(color and 0xffffff))
            problem.visibility = GONE
        } finally { changing = false }
    }
    private fun paint(half: TextView, value: Long) {
        (half.background as GradientDrawable).setColor(value.toInt())
        half.setTextColor(if (Color.luminance(value.toInt()) > .45f) 0xff1d2433.toInt() else Color.WHITE)
    }
    private fun half(context: Context, label: String, start: Boolean) = TextView(context).apply {
        text = label; textSize = 12.5f; typeface = Tokens.SANS_SEMIBOLD; gravity = Gravity.CENTER; maxLines = 1
        val r = dp(14f)
        background = GradientDrawable().apply {
            cornerRadii = if (start) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            setStroke(dpi(1f), 0x1f1d2433)
        }
    }

    /** The circle: hue by angle (red at the top, going clockwise), saturation from the white middle to the rim. */
    class Disc(context: Context) : View(context) {
        var changed: (hue: Float, saturation: Float, first: Boolean) -> Unit = { _, _, _ -> }
        private var hue = 0f; private var saturation = 0f; private var value = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: Shader? = null
        init { contentDescription = "Colour circle"; isClickable = true; isFocusable = true }
        fun show(h: Float, s: Float, v: Float) {
            hue = h; saturation = s; value = v
            stateDescription = "${ColorNames.of(Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xffffffffL)}. Hue ${h.roundToInt()} degrees, ${(s * 100).roundToInt()} % colour"
            invalidate()
        }
        // F219: no drag is needed: a screen reader's scroll gesture steps around the circle.
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            val step = when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 15f
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> 345f
                else -> return super.performAccessibilityAction(action, arguments)
            }
            changed((hue + step) % 360f, maxOf(saturation, .6f), false)
            return true
        }
        private val radius get() = minOf(width, height) / 2f - dp(10f)
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cx = w / 2f; val cy = h / 2f
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }
            val sweep = SweepGradient(cx, cy, hues, null).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) }) }
            val pale = RadialGradient(cx, cy, maxOf(radius, 1f), Color.WHITE, 0x00ffffff, Shader.TileMode.CLAMP)
            shader = ComposeShader(sweep, pale, PorterDuff.Mode.SRC_OVER)
        }
        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val cx = width / 2f; val cy = height / 2f; val r = radius
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(dp(6f), 0f, dp(2f), 0x2a141e37); paint.color = Color.WHITE; canvas.drawCircle(cx, cy, r, paint); paint.clearShadowLayer()
            // The circle always shows its colours at full brightness, so a dark ink still opens on every colour; the marker,
            // the brightness slider and "New" show the colour itself.
            paint.shader = shader; canvas.drawCircle(cx, cy, r, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = 0x1f1d2433; canvas.drawCircle(cx, cy, r, paint)
            val angle = Math.toRadians((hue - 90f).toDouble())
            val x = cx + (cos(angle) * saturation * r).toFloat(); val y = cy + (sin(angle) * saturation * r).toFloat()
            paint.style = Paint.Style.FILL; paint.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
            paint.setShadowLayer(dp(4f), 0f, dp(1.5f), 0x40141e37); canvas.drawCircle(x, y, dp(13f), paint); paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3.5f); paint.color = Color.WHITE; canvas.drawCircle(x, y, dp(13f), paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = e.x - width / 2f; val dy = e.y - height / 2f
                    val h = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f) % 360f + 360f) % 360f
                    changed(h, (hypot(dx, dy) / radius.coerceAtLeast(1f)).coerceIn(0f, 1f), e.actionMasked == MotionEvent.ACTION_DOWN)
                    if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
            return e.actionMasked == MotionEvent.ACTION_CANCEL
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> changed((hue + 357f) % 360f, saturation, false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> changed((hue + 3f) % 360f, saturation, false)
                KeyEvent.KEYCODE_DPAD_UP -> changed(hue, (saturation + .05f).coerceAtMost(1f), false)
                KeyEvent.KEYCODE_DPAD_DOWN -> changed(hue, (saturation - .05f).coerceAtLeast(0f), false)
                else -> return super.onKeyDown(code, event)
            }
            return true
        }
    }
}
