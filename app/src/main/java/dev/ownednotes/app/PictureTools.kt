package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * P720: the crop screen's straighten dial. A ruler of degrees slides under a fixed marker, as in a photo editor: drag it
 * sideways to turn the picture, at most 45 degrees either way, a tenth of a degree at a time. It snaps to level (0) with a
 * tick of the phone. The value shows above the marker. TalkBack and arrow keys move it half a degree at a time.
 */
internal class AngleDial(context: Context) : View(context) {
    /** Degrees, clockwise. */
    var value = 0f
        set(v) { field = (v * 10).roundToInt() / 10f; contentDescription = "Straighten, ${label(field)}"; invalidate() }
    /** Called as the value changes; [first] is true for the first change of a drag. */
    var onChange: ((value: Float, first: Boolean) -> Unit)? = null
    /** A drag started (true) or ended (false), so the picture can show its grid meanwhile. */
    var onHold: ((Boolean) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val perDegree get() = 9f * density
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10.5f * density; typeface = Kit.Type.CAPTION.typeface }
    private val reading = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 14f * density; typeface = Kit.Type.HEADLINE.typeface; color = 0xffffffff.toInt() }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT_NIGHT; strokeCap = Paint.Cap.ROUND }
    private val notch = Path()
    private var downX = 0f
    private var downValue = 0f
    private var moved = false

    init {
        isFocusable = true; isClickable = true; value = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val base = height - 22f * density; val half = width / 2f
        // The reading at the top, clear of the marker below it.
        canvas.drawText(label(value), cx, 14f * density, reading)
        val first = kotlin.math.ceil(value - half / perDegree).toInt().coerceAtLeast(-MAX)
        val last = kotlin.math.floor(value + half / perDegree).toInt().coerceAtMost(MAX)
        for (d in first..last) {
            val x = cx + (d - value) * perDegree
            // Fades out towards the ends, so the ruler reads as turning away.
            val fade = (1f - ((x - cx) / half).let { it * it }).coerceIn(0f, 1f)
            val major = d % 5 == 0
            val length = (if (d == 0) 20f else if (major) 15f else 8f) * density
            tick.strokeWidth = (if (major) 1.8f else 1.1f) * density
            tick.color = 0xffffffff.toInt(); tick.alpha = ((if (major) 220 else 120) * fade).toInt()
            canvas.drawLine(x, base - length, x, base, tick)
            if (d % 15 == 0) {
                numbers.color = 0xffffffff.toInt(); numbers.alpha = (170 * fade).toInt()
                canvas.drawText("$d°", x, base + 15f * density, numbers)
            }
        }
        // The marker: a line with a small point on top, where the reading is taken.
        val top = 21f * density
        marker.strokeWidth = 3f * density
        canvas.drawLine(cx, top + 4f * density, cx, base + 2f * density, marker)
        notch.rewind(); notch.moveTo(cx - 5f * density, top); notch.lineTo(cx + 5f * density, top); notch.lineTo(cx, top + 6f * density); notch.close()
        marker.style = Paint.Style.FILL; canvas.drawPath(notch, marker); marker.style = Paint.Style.STROKE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downValue = value; moved = false; parent?.requestDisallowInterceptTouchEvent(true); onHold?.invoke(true) }
            MotionEvent.ACTION_MOVE -> {
                var next = (downValue - (event.x - downX) / perDegree).coerceIn(-MAX.toFloat(), MAX.toFloat())
                // Level catches the dial, with a tick, as it passes.
                if (abs(next) < SNAP) next = 0f
                next = (next * 10).roundToInt() / 10f
                if (next != value) {
                    if (next == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val first = !moved; moved = true; value = next; onChange?.invoke(next, first)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onHold?.invoke(false)
        }
        return true
    }
    /** Moves by [step] degrees as one change (keys, TalkBack). */
    fun nudge(step: Float) {
        val next = (value + step).coerceIn(-MAX.toFloat(), MAX.toFloat())
        if (next == value) return
        value = next; onChange?.invoke(value, true); announceForAccessibility(label(value))
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(-STEP); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(STEP); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, -MAX.toFloat(), MAX.toFloat(), value)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { nudge(STEP); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { nudge(-STEP); true }
        android.R.id.accessibilityActionSetProgress -> {
            arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.let { nudge(it.coerceIn(-MAX.toFloat(), MAX.toFloat()) - value) }; true
        }
        else -> super.performAccessibilityAction(action, arguments)
    }

    companion object {
        const val MAX = 45
        private const val SNAP = .4f
        private const val STEP = .5f
        /** "0°", "3.5°", "−2°": a real minus sign, and no ".0". */
        fun label(v: Float): String {
            val text = if (v == v.roundToInt().toFloat()) abs(v.roundToInt()).toString() else "%.1f".format(java.util.Locale.ROOT, abs(v))
            return (if (v < 0) "−" else "") + text + "°"
        }
    }
}

/**
 * P720: a look's small preview for the crop screen: the picture as it will come out, fitted in a rounded square, ringed
 * in the accent while chosen. It holds no bitmap of its own: the screen owns and recycles them.
 */
internal class LookThumb(private val side: Int, private val radius: Float, private val density: Float) : Drawable() {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidateSelf() }
    var chosen = false
        set(v) { field = v; invalidateSelf() }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24ffffff }
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF(); private val clip = Path(); private val m = Matrix()
    override fun draw(canvas: Canvas) {
        val b = bounds; box.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(box, radius, radius, back)
        bitmap?.takeIf { !it.isRecycled }?.let { bmp ->
            canvas.save(); clip.rewind(); clip.addRoundRect(box, radius, radius, Path.Direction.CW); canvas.clipPath(clip)
            val inset = 5f * density
            m.setRectToRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
                RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), Matrix.ScaleToFit.CENTER)
            canvas.drawBitmap(bmp, m, picture); canvas.restore()
        }
        ring.strokeWidth = (if (chosen) 2.5f else 1f) * density; ring.color = if (chosen) Glass.ACCENT_NIGHT else 0x33ffffff
        val half = ring.strokeWidth / 2
        box.inset(half, half); canvas.drawRoundRect(box, radius - half, radius - half, ring)
    }
    override fun getIntrinsicWidth() = side
    override fun getIntrinsicHeight() = side
    override fun setAlpha(alpha: Int) { picture.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { picture.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
