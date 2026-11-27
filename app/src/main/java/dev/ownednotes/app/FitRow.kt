package dev.ownednotes.app

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * P730: buttons side by side, sharing the row's width, that stack one above the other (each the row's width) when a
 * label would not fit whole on its line. Before, rows like this stacked only when the text size passed 130%; at 130%
 * on a 384 dp phone (a common Samsung setting) "Read handwriting" and "Mark question" lost their ends.
 * Add the buttons with plain LayoutParams; the row sets their widths, weights and the [gap] between them.
 */
internal class FitRow(context: Context, private val gap: Int) : LinearLayout(context) {
    init { orientation = HORIZONTAL }

    /** True while the buttons stand one above the other. */
    val stacked get() = orientation == VERTICAL

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        arrange(HORIZONTAL)
        super.onMeasure(widthSpec, heightSpec)
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && (0 until childCount).any { cut(getChildAt(it)) }) {
            arrange(VERTICAL)
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    /** A label longer than its line: on one line it is cut short, wrapped it splits a word. */
    private fun cut(view: View): Boolean {
        val text = view as? TextView ?: return false
        if (text.visibility == GONE) return false
        val room = text.measuredWidth - text.compoundPaddingLeft - text.compoundPaddingRight
        return text.paint.measureText(text.text.toString()) > room + 0.5f
    }

    private fun arrange(way: Int) {
        orientation = way
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as? LayoutParams ?: LayoutParams(0, LayoutParams.WRAP_CONTENT).also { child.layoutParams = it }
            val lead = if (first || child.visibility == GONE) 0 else gap
            // Left, not start: a start margin set while measuring is not resolved until the next layout pass (the app is left to right).
            if (way == HORIZONTAL) { params.width = 0; params.weight = 1f; params.leftMargin = lead; params.topMargin = 0 }
            else { params.width = LayoutParams.MATCH_PARENT; params.weight = 0f; params.leftMargin = 0; params.topMargin = lead }
            if (child.visibility != GONE) first = false
        }
    }
}
