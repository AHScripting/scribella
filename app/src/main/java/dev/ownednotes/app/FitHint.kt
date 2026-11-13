package dev.ownednotes.app

import android.widget.EditText

/**
 * P600: a one-line box's hint is clipped mid-word when it is wider than the box (the Library search showed
 * "Search, including handw"). This shows the first wording, longest first, that fits whole; the last one is used when
 * none fits. The box's spoken label (contentDescription) is not touched.
 */
internal fun EditText.fitHint(vararg wordings: String) {
    hint = wordings.last()
    addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        val room = right - left - totalPaddingLeft - totalPaddingRight
        val want = if (room <= 0) wordings.last() else wordings.firstOrNull { paint.measureText(it) <= room } ?: wordings.last()
        if (hint?.toString() != want) post { hint = want }
    }
}
