package dev.ownednotes.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * B07 (P131): the blank page after the last one, with an "Add page" button. In continuous mode it follows the empty
 * slot [ContinuousPageView] leaves after the last page and only the button takes touches. In single-page mode it is
 * [covering]: it slides in over the last page when a swipe tries to go past it, and a swipe back or a tap beside the
 * button closes it. Presentation only; the editor decides when a page may be added.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class AddPageCard(context: Context, onAdd: () -> Unit, private val onDismiss: () -> Unit) : FrameLayout(context) {
    val button = ChromeButton(context, "Add page", Glyph.PLUS, "Add page").apply {
        // P178 (G3): the Glass accent pill.
        background = rounded(Glass.ACCENT, dp(Glass.PILL)); elevation = dp(6f)
        setOnClickListener { onAdd() }
    }
    /** The empty page in this view's coordinates, or null when none is in view. */
    var ghost: RectF? = null; set(value) { field = value; place(); invalidate() }
    var paperColor = 0xffffffff.toInt(); set(value) { field = value; invalidate() }
    var deskColor = Tokens.DESK; set(value) { field = value; invalidate() }
    /** Single-page mode: the card hides the desk and the last page, and takes every touch. */
    var covering = false; set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0x660a66d9
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    /** B13 (P136): a finger pull back toward the last page, as (originY, dx, dy); returns false when nothing follows it. */
    var onPullBack: (Float, Float, Float) -> Boolean = { _, _, _ -> false }
    /** Lets go of [onPullBack]; true for a quick swipe. */
    var onLetGo: (Boolean) -> Unit = {}
    private var pulling = false
    private var velocity: android.view.VelocityTracker? = null

    init {
        setWillNotDraw(false)
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(48f)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom); place()
    }

    private fun place() {
        val r = ghost
        // Invisible rather than gone, so the button keeps its size for [drawSheet].
        button.visibility = if (r == null) View.INVISIBLE else View.VISIBLE
        if (r == null || button.width == 0) return
        button.translationX = r.centerX() - button.left - button.width / 2f
        button.translationY = buttonCenterY(r) - button.top - button.height / 2f
    }
    // The button stays on screen while any of the empty page shows.
    private fun buttonCenterY(r: RectF) = r.centerY().coerceIn(dp(56f), (height - dp(56f)).coerceAtLeast(dp(56f)))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (covering) canvas.drawColor(deskColor)
        ghost?.let { paper(canvas, it) }
    }

    private fun paper(canvas: Canvas, r: RectF) {
        val corner = dp(6f)
        // Paper, faded toward the desk, so it reads as a page not yet made.
        fill.color = paperColor; fill.alpha = 0xb8
        canvas.drawRoundRect(r, corner, corner, fill)
        val inset = outline.strokeWidth / 2
        canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, corner, corner, outline)
    }

    /** B13 (P136): the open card's empty page and button for [r], drawn into [canvas] in this view's coordinates
     * (the page curl shows it under the last page). The desk is the caller's. */
    fun drawSheet(canvas: Canvas, r: RectF) {
        paper(canvas, r)
        if (button.width == 0) return
        canvas.save()
        canvas.translate(r.centerX() - button.width / 2f, buttonCenterY(r) - button.height / 2f)
        button.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!covering) return false
        val dx = event.x - downX; val dy = event.y - downY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; pulling = false
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                // A pull to the right lays the last page back over this card (B13); the page follows the finger.
                if (!pulling && dx > slop && abs(dx) > abs(dy)) pulling = onPullBack(downY, dx, dy)
                else if (pulling) onPullBack(downY, dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val fast = (velocity?.xVelocity ?: 0f) > ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
                when {
                    pulling -> onLetGo(fast)
                    // A tap beside the button, or a swipe back toward the last page, closes the card.
                    (abs(dx) < slop && abs(dy) < slop) || (dx > slop * 3 && abs(dx) > abs(dy)) -> onDismiss()
                }
                pulling = false
            }
            MotionEvent.ACTION_CANCEL -> { if (pulling) onLetGo(false); pulling = false }
        }
        velocity?.addMovement(event)
        return true
    }
}
