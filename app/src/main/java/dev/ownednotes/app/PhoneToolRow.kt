package dev.ownednotes.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * P730 (design canvas board 23, Look A, chosen 27 November): a phone's tools in one row. As many as fit stand in the
 * row, in the bar's own order, chosen by rank (pens, highlighters, eraser, lasso, shapes, paint brush, Fit, colours,
 * then the + buttons); the rest wait behind [more], as on the tablet's slim bar (board 14). Each button keeps its place
 * in a holder while it is lent to the small bar above ([lend], [giveBack]), so it comes back to the same spot.
 */
internal class PhoneToolRow(context: Context, val more: View) : LinearLayout(context) {
    private class Place(val view: View, val rank: Int, val holder: FrameLayout)
    private val places = mutableListOf<Place>()
    /** While the waiting buttons are in the bar above, the row keeps its split (their holders are empty). */
    private var lent: List<Place> = emptyList()

    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** The buttons in the bar's order, each with its rank: a lower rank keeps its place in the row longer. */
    fun setPlaces(list: List<Pair<View, Int>>) {
        giveBack()
        removeAllViews(); places.clear()
        for ((view, rank) in list) {
            (view.parent as? ViewGroup)?.removeView(view)
            val holder = FrameLayout(context).apply { clipChildren = false; addView(view, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)) }
            places += Place(view, rank, holder)
            addView(holder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        (more.parent as? ViewGroup)?.removeView(more)
        addView(more, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** The shown buttons waiting behind », in the bar's order. */
    val waiting: List<View> get() = places.filter { it.holder.visibility == GONE && it.view.visibility != GONE }.map { it.view }
    /** The buttons standing in the row now, for tests. */
    val standing: List<View> get() = places.filter { it.holder.visibility != GONE && it.view.visibility != GONE }.map { it.view }

    /** Takes the waiting buttons out of their holders for the bar above; [giveBack] returns them. */
    fun lend(): List<View> {
        giveBack()
        lent = places.filter { it.holder.visibility == GONE && it.view.visibility != GONE }
        lent.forEach { it.holder.removeView(it.view) }
        return lent.map { it.view }
    }
    fun giveBack() {
        if (lent.isEmpty()) return
        lent.forEach { p ->
            (p.view.parent as? ViewGroup)?.removeView(p.view)
            p.holder.addView(p.view, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        lent = emptyList()
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (lent.isEmpty() && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) split(MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight)
        super.onMeasure(widthSpec, heightSpec)
    }

    private fun split(room: Int) {
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val widths = places.associateWith { p -> if (p.view.visibility == GONE) 0 else { p.view.measure(any, any); p.view.measuredWidth } }
        val keep = if (widths.values.sum() <= room) places.toSet() else {
            more.measure(any, any)
            var left = room - more.measuredWidth
            val fits = places.filter { widths[it] == 0 }.toMutableSet()
            // In rank order until one does not fit: the rest wait, so a later tool never jumps ahead of an earlier one.
            for (p in places.sortedBy { it.rank }) {
                val w = widths.getValue(p)
                if (w == 0) continue
                if (w > left) break
                fits += p; left -= w
            }
            fits
        }
        for (p in places) { val v = if (p in keep) VISIBLE else GONE; if (p.holder.visibility != v) p.holder.visibility = v }
        val m = if (places.any { it !in keep }) VISIBLE else GONE
        if (more.visibility != m) more.visibility = m
    }
}

/**
 * P730 (board 23): » at the end of a phone's palette, 36 dp wide. It is lit (the board's accent chip) while its small bar is
 * open, and while the tool in use is one of the buttons waiting behind it, so the tool in hand always shows on the bar.
 */
internal class PhoneMoreButton(context: Context, night: Boolean) : View(context) {
    private val icon = Kit.Icon(SlimBar.OVERFLOW, Glass.accent(night), context.dpi(22f))
    private val chip = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Glass.accent(night); alpha = if (night) 62 else 36 }
    private val box = android.graphics.RectF()
    var lit = false; set(value) { if (field != value) { field = value; invalidate() } }
    init {
        isClickable = true; isFocusable = true
        contentDescription = "More tools"; tooltipText = contentDescription
        foreground = ripple(night, dp(12f))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dpi(36f), dpi(48f))
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (lit) { box.set(dp(1f), (height - dp(40f)) / 2, width - dp(1f), (height + dp(40f)) / 2); canvas.drawRoundRect(box, dp(12f), dp(12f), chip) }
        val s = dpi(22f); val l = (width - s) / 2; val t = (height - s) / 2
        icon.setBounds(l, t, l + s, t + s); icon.draw(canvas)
    }
}
