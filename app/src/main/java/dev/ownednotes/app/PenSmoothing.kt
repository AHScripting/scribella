package dev.ownednotes.app

import android.annotation.SuppressLint
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.ExperimentalInkCustomBrushApi

/** Local tool preference only: notebooks retain the resulting canonical outlines. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object PenSmoothing {
    /** The midpoint retains the installed stock/custom family's original input model. */
    @SuppressLint("RestrictedApi")
    fun family(base: BrushFamily, level: Int): BrushFamily {
        require(level in 0..100)
        if (level == 50) return base
        return base.toBuilder().setInputModel(BrushFamily.SlidingWindowModel(1L + level * 79L / 100L, 180)).build()
    }
    fun label(level: Float) = when (level.toInt()) { 0 -> "Low"; 50 -> "Standard"; 100 -> "High"; else -> "${level.toInt()} %" }
}
