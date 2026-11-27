package dev.ownednotes.app

import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/**
 * P721: keeps a full-screen view's content clear of the camera hole (the display cutout) and of the navigation bars where
 * the window reaches under them. Android 15 and later draw every app edge to edge, under the camera and the gesture bar;
 * the Pixel showed the camera over the editor's bar and the writing. The view's background still fills the screen; the
 * overlap is added to its own padding. It is measured from where the view really is, so a window that already stops
 * short of the cutout and the bars (Android 14 and earlier, a tablet with no cutout) gets nothing added.
 * The status bar is not counted: Scribella hides it, and it only slides over for a moment when pulled down.
 */
internal object SafeArea {
    /** Pads [view] clear of the cutout and the navigation bars from now on, on top of the padding it has now. */
    fun keep(view: View) {
        val base = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        val update = Runnable { apply(view, base) }
        view.setOnApplyWindowInsetsListener { v, insets -> v.post(update); insets }
        view.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob -> if (l != ol || t != ot || r != or_ || b != ob) v.post(update) }
        view.post(update)
    }
    /** The parts of [view] under the cutout or the navigation bars, as left, top, right, bottom; null before it is shown. */
    fun overlap(view: View): IntArray? {
        val insets = view.rootWindowInsets ?: return null
        val clear = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars())
        val at = IntArray(2).also { view.getLocationInWindow(it) }
        val window = view.rootView
        return intArrayOf(max(0, clear.left - at[0]), max(0, clear.top - at[1]),
            max(0, at[0] + view.width - (window.width - clear.right)), max(0, at[1] + view.height - (window.height - clear.bottom)))
    }
    private fun apply(view: View, base: IntArray) {
        if (view.width == 0) return
        val o = overlap(view) ?: return
        val left = base[0] + o[0]; val top = base[1] + o[1]; val right = base[2] + o[2]; val bottom = base[3] + o[3]
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom)
            view.setPadding(left, top, right, bottom)
    }
}
