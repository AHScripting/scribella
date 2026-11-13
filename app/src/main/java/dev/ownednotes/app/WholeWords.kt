package dev.ownednotes.app

import android.util.TypedValue
import android.widget.TextView

/**
 * P602: like [shrinkToFit], but a word is never cut or split. Autosize stops at its smallest size, and when even that is
 * too wide for the longest word (the Lasso's "Highlighter" on a 320 dp phone), the one-line label showed its first line
 * only ("Highlight"). Here the smallest size drops just enough for the longest word to fit whole, but not below [floorDp].
 * Wider screens never reach the floor, so they keep exactly the size [shrinkToFit] gives.
 */
internal fun TextView.shrinkToFitWords(minDp: Float, maxSp: Float, floorDp: Float = 5f) {
    shrinkToFit(minDp, maxSp)
    val most = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, resources.displayMetrics).toInt()
    addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        val room = right - left - totalPaddingLeft - totalPaddingRight
        if (room <= 0) return@addOnLayoutChangeListener
        val least = context.dpi(minDp).toFloat()
        val probe = android.text.TextPaint(paint).apply { textSize = least }
        val longest = text.split(Regex("\\s+")).maxOfOrNull { probe.measureText(it) } ?: return@addOnLayoutChangeListener
        val need = if (longest <= room) least else maxOf(context.dpi(floorDp).toFloat(), least * room / longest - 1f)
        val want = need.toInt().coerceAtLeast(1)
        if (autoSizeMinTextSize != want) post { setAutoSizeTextTypeUniformWithConfiguration(want, maxOf(want + 1, most), 1, TypedValue.COMPLEX_UNIT_PX) }
    }
}

/**
 * P603: a title or label never splits a word across lines ("Settin / gs" on a 320 dp phone with 200% text). When its
 * longest word is wider than the view, the text gets just small enough for that word to fit; when there is room again
 * it goes back to the size it was given. A size set later by the caller is respected as the new starting size.
 * P654: a name someone typed ([leastShare] > 0) shrinks only when that share of its size or more is enough for its
 * longest word; otherwise it keeps its size and a long unbroken word (a 60-letter folder name) is shortened with "…" by
 * the view's own ellipsize, instead of shrinking the whole name until it can't be read.
 */
/** P654: how far a notebook, folder or tag name may shrink to keep its longest word whole; past that it ends in "…". */
internal const val NAME_SHARE = .8f

internal fun TextView.keepWordsWhole(floorDp: Float = 8f, leastShare: Float = 0f) {
    var given = 0f; var ours = -1f
    addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        if (textSize != ours) given = textSize
        val room = right - left - totalPaddingLeft - totalPaddingRight
        if (room <= 0 || text.isNullOrEmpty() || autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_NONE) return@addOnLayoutChangeListener
        val probe = android.text.TextPaint(paint).apply { textSize = given }
        val longest = text.split(Regex("\\s+")).maxOfOrNull { probe.measureText(it) } ?: return@addOnLayoutChangeListener
        val fit = given * room / longest - 1f
        val want = if (longest <= room || (leastShare > 0f && fit < given * leastShare)) given else maxOf(context.dpi(floorDp).toFloat(), fit)
        if (kotlin.math.abs(want - textSize) > .5f) { ours = want; post { setTextSize(TypedValue.COMPLEX_UNIT_PX, want) } }
    }
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { requestLayout() }
    })
}
