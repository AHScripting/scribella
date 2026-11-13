package dev.ownednotes.app

import kotlin.math.roundToInt

enum class PenKind(val label: String, val brushId: String) {
    PRESSURE("Pressure", dev.ownednotes.core.InkStroke.PEN),
    BALLPOINT("Ballpoint", dev.ownednotes.core.InkStroke.BALLPOINT),
    DASHED("Dashed", dev.ownednotes.core.InkStroke.DASHED),
    /** P097 (F024): flat nib at a fixed angle (schema 18). */
    CALLIGRAPHY("Calligraphy", dev.ownednotes.core.InkStroke.CALLIGRAPHY),
    /** P127 (F023): pressure-sensitive line filled with graphite grain (schema 25). */
    PENCIL("Pencil", dev.ownednotes.core.InkStroke.PENCIL),
    /** P618 (board 11): fountain pen, everyday firm nib (schema 37). */
    FOUNTAIN("Fountain", dev.ownednotes.core.InkStroke.FOUNTAIN),
    /** P618: fountain pen with a soft, flexible nib; one tile with [FOUNTAIN] in the pen menus, told apart by its Nib choice. */
    FOUNTAIN_SOFT("Soft fountain", dev.ownednotes.core.InkStroke.FOUNTAIN_SOFT);
    /** The fountain nib, or null for every other pen. */
    val nib get() = when (this) { FOUNTAIN -> FountainInk.Nib.EVERYDAY; FOUNTAIN_SOFT -> FountainInk.Nib.SOFT; else -> null }
    companion object {
        /** The pen kind that drew a saved stroke; the pressure pen for anything else. */
        fun forBrush(brush: String) = entries.firstOrNull { it.brushId == brush } ?: PRESSURE
    }
}

/** Pen style with an opaque RGB [color]; [opacity] (P089) applies to whole pen strokes. Sizes are document units, independent of screen zoom. */
data class PenStyle(val color: Long = 0xff182636, val width: Float = 4f, val kind: PenKind = PenKind.PRESSURE, val opacity: Float = 1f, val smoothing: Int = 50) {
    init {
        require(smoothing in 0..100) { "Smoothing must be 0–100" }
        require(color in 0xff000000L..0xffffffffL) { "Choose an opaque RGB color" }
        require(width.isFinite() && width in .5f..50f) { "Pen width must be 0.5–50 page units" }
        require(opacity.isFinite() && opacity in .1f..1f) { "Opacity must be 10–100 %" }
    }
    /** The saved stroke colour: opacity in the alpha byte (fully opaque stays 0xff). */
    val inkColor: Long get() = (alpha.toLong() shl 24) or (color and 0xffffffL)
    val alpha: Int get() = (opacity * 255).roundToInt().coerceIn(dev.ownednotes.core.InkStroke.MIN_ALPHA.toInt(), 255)
    companion object {
        /** Splits a saved stroke colour back into an opaque colour and an opacity, e.g. for the eyedropper. */
        fun opacityOf(color: Long) = ((color ushr 24).toInt() / 255f).coerceIn(.1f, 1f)
    }
}
