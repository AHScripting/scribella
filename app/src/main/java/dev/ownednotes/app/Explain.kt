package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import kotlin.math.roundToInt

/**
 * P729 (design canvas board 21): the (i) button and the small card it opens. A panel keeps only its controls and a few
 * words; each explanation moves behind an (i), as a short list of picture lines. A tap anywhere closes the card.
 */
internal object Explain {
    /** One line of the card: an icon on a tinted disc and a short sentence. */
    data class Line(val path: String, val color: Int, val text: String)

    /** The card last opened, for tests. */
    @VisibleForTesting var last: PopupWindow? = null

    /**
     * The (i): a 22 dp outline icon, on a pale accent circle while its card is open. The view is 44 dp so it is easy to
     * hit; the circle drawn in it is the board's 32 dp. [label] is what a screen reader says.
     */
    class Button(context: Context, label: String) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.accentFill(night) }
        private val idle = Kit.Icon(INFO, Glass.secondary(night), context.dpi(22f))
        private val open = Kit.Icon(INFO, Glass.accent(night), context.dpi(22f))
        init {
            contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true
            // A borderless ripple the size of the drawn circle.
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f1d2433), null, null)
                .apply { radius = context.dpi(16f) }
        }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dpi(44f), dpi(44f))
        override fun setActivated(activated: Boolean) { super.setActivated(activated); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (isActivated) canvas.drawCircle(cx, cy, dp(16f), paint)
            val icon = if (isActivated) open else idle
            val s = dpi(22f)
            icon.setBounds((cx - s / 2f).roundToInt(), (cy - s / 2f).roundToInt(), (cx + s / 2f).roundToInt(), (cy + s / 2f).roundToInt())
            icon.draw(canvas)
        }
    }

    /** Opens the card under [anchor]: [title], an optional [body] paragraph, then [lines]. */
    fun show(anchor: View, title: String, lines: List<Line>, body: String? = null): PopupWindow {
        val context = anchor.context
        val night = context.night
        val width = minOf(context.dpi(370f), context.resources.displayMetrics.widthPixels - context.dpi(32f))
        lateinit var window: PopupWindow
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.float(context, night, 20f)
            setPadding(dpi(16f), dpi(16f), dpi(18f), dpi(22f))
            addView(text(context, title, 15f, Tokens.SANS_MEDIUM).apply { setPadding(dpi(2f), 0, 0, 0); isAccessibilityHeading = true })
            body?.let { addView(text(context, it, 14f, Tokens.SANS).apply { setPadding(dpi(2f), 0, 0, 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) }) }
            for (line in lines) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                addView(View(context).apply { background = Disc(line.color, line.path, 17f / 30f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
                addView(text(context, line.text, 14f, Tokens.SANS).apply { setPadding(0, dpi(5f), 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(12f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
            isClickable = true
            setOnClickListener { window.dismiss() }
        }
        window = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = context.dp(14f)
            setOnDismissListener { anchor.isActivated = false; if (last === this) last = null }
        }
        anchor.isActivated = true
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val toEnd = at[0] + anchor.width / 2 > context.resources.displayMetrics.widthPixels / 2
        window.showAsDropDown(anchor, 0, -context.dpi(4f), if (toEnd) Gravity.END else Gravity.START)
        last = window
        return window
    }

    private fun text(context: Context, value: String, size: Float, face: android.graphics.Typeface) = TextView(context).apply {
        text = value; textSize = size; typeface = face; includeFontPadding = false
        setTextColor(Glass.text(context.night))
        if (size <= 14f) lineHeight = context.dpi(20f)
    }

    const val INFO = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5.5M12 7.8h.01"

    /** The board's `disc`: a circle of [color] at 12% with the icon [path] in [color], [share] of the disc wide; or a dashed ring. */
    class Disc(private val color: Int, path: String?, private val share: Float, private val fill: Float = .12f, private val dashed: Boolean = false) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = path?.let { Kit.Icon(it, color, 0) }
        override fun draw(canvas: Canvas) {
            val b = bounds; val r = b.width() / 2f
            if (dashed) {
                val w = r / 18f // 2 dp on a 72 dp disc
                paint.style = Paint.Style.STROKE; paint.strokeWidth = w; paint.color = alpha(color, .55f)
                paint.pathEffect = DashPathEffect(floatArrayOf(w * 3f, w * 2.5f), 0f)
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - w / 2f, paint)
                paint.pathEffect = null
            } else {
                paint.style = Paint.Style.FILL; paint.color = alpha(color, fill * Color.alpha(color) / 255f)
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
            }
            icon?.let {
                val s = b.width() * share
                it.setBounds((b.exactCenterX() - s / 2f).roundToInt(), (b.exactCenterY() - s / 2f).roundToInt(),
                    (b.exactCenterX() + s / 2f).roundToInt(), (b.exactCenterY() + s / 2f).roundToInt())
                it.draw(canvas)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** A filled circle of [color] with a white icon: the tick before "Synced 2 min ago". */
    class Badge(private val color: Int, path: String, private val size: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val art = androidx.core.graphics.PathParser.createPathFromPathData(path)
        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, paint)
            val s = b.width() * .7f
            canvas.save()
            canvas.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f); canvas.scale(s / 24f, s / 24f)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 3f
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(art, paint)
            canvas.restore()
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun alpha(color: Int, a: Float) = (color and 0xffffff) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
}
