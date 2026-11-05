package dev.ownednotes.app

import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Reusable action surface; callers supply actions and an anchor, never document mutations. */
class ContextToolbar(context: Context) : HorizontalScrollView(context) {
    val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    init {
        visibility = GONE
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        background = rounded(Tokens.INK, dp(12f)); elevation = dp(8f)
        setPadding(dpi(6f), 0, dpi(6f), 0)
        addView(actions, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    /**
     * P175 (G1): a floating Glass pill instead of an ink bar; its chrome buttons follow. P201 step 5: as on the
     * Selection board, 56 dp tall with 20 dp corners and a short hairline between the visible actions.
     */
    var glass: Boolean? = null
        private set
    fun useGlass(night: Boolean) {
        glass = night
        background = Glass.pane(context, night, 20f); elevation = dp(10f)
        setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f))
        actions.dividerDrawable = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
            setColor(if (night) Glass.RULE_NIGHT else Glass.RULE); setSize(dpi(1f), dpi(24f))
        }, dpi(1f), 0, dpi(1f), 0)
        actions.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        for (i in 0 until actions.childCount) (actions.getChildAt(i) as? ChromeButton)?.let { it.glass = true; it.night = night }
    }
    fun setActions(views: List<View>) {
        actions.removeAllViews()
        views.forEach { actions.addView(it) }
        glass?.let { night -> views.forEach { (it as? ChromeButton)?.apply { glass = true; this.night = night } } }
        scrollTo(0, 0)
    }
    /**
     * Anchor coordinates are in the containing editor. Prefer above, otherwise below/clamped.
     * P176 (G1b): [topInset] and [bottomInset] are bands covered by floating chrome; the bar stays clear of them.
     */
    fun place(anchor: RectF, availableWidth: Int, availableHeight: Int, topInset: Int = 0, bottomInset: Int = 0): Boolean {
        if (topInset > 0 || bottomInset > 0) {
            val clear = availableHeight - topInset - bottomInset
            if (clear <= 0) { visibility = GONE; return false }
            val shifted = RectF(anchor).apply { offset(0f, -topInset.toFloat()) }
            // Place in the clear band's coordinates, so an unchanged position still reports "not moved".
            translationY -= topInset
            val moved = place(shifted, availableWidth, clear)
            translationY += topInset
            return moved
        }
        if (availableWidth <= 0 || availableHeight <= 0 || !RectF.intersects(anchor, RectF(0f, 0f, availableWidth.toFloat(), availableHeight.toFloat()))) {
            visibility = GONE; return false
        }
        val margin = minOf(dpi(8f), availableWidth / 4, availableHeight / 4)
        val gap = dpi(10f)
        actions.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val width = minOf(actions.measuredWidth + paddingLeft + paddingRight, availableWidth - 2 * margin).coerceAtLeast(1)
        val height = minOf(actions.measuredHeight + paddingTop + paddingBottom, availableHeight - 2 * margin).coerceAtLeast(1)
        val left = (anchor.centerX() - width / 2f).coerceIn(margin.toFloat(), (availableWidth - margin - width).toFloat()).roundToInt().toFloat()
        val above = anchor.top - gap - height
        val below = anchor.bottom + gap
        val top = when {
            above >= margin -> above
            below + height <= availableHeight - margin -> below
            else -> above.coerceIn(margin.toFloat(), (availableHeight - margin - height).toFloat())
        }.roundToInt().toFloat()
        val moved = translationX != left || translationY != top || layoutParams.width != width || layoutParams.height != height
        if (layoutParams.width != width || layoutParams.height != height) layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
        translationX = left; translationY = top; visibility = VISIBLE
        return moved
    }
}
