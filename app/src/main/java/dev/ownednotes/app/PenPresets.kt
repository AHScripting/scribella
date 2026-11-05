package dev.ownednotes.app

import android.content.SharedPreferences

/**
 * Saved pen styles shown as separate pens on the tool bar (design build-order step 3).
 * The selected preset always mirrors `InkCanvas.penStyle`, so editing the pen edits its preset.
 * Stored as `penPresets` ("color:width:kind" separated by ';') and `penPreset` (selected index).
 * Legacy two-field styles decode as pressure pens.
 */
class PenPresets(private val preferences: SharedPreferences, current: PenStyle) {
    companion object {
        const val COUNT = 3
        private fun defaults(current: PenStyle) = listOf(current, PenStyle(0xff1565c0, 2.5f), PenStyle(0xffc62828, 8f))
        fun encode(styles: List<PenStyle>) = styles.joinToString(";") { "${it.color}:${it.width}:${it.kind.name}" }
        /** Unreadable or out-of-range stored values fall back to defaults, slot by slot. */
        fun decode(text: String?, current: PenStyle): List<PenStyle> {
            val fallback = defaults(current)
            val parts = text?.split(';').orEmpty()
            return List(COUNT) { i ->
                parts.getOrNull(i)?.split(':')?.takeIf { it.size in 2..3 }?.let { fields ->
                    runCatching { PenStyle(fields[0].toLong(), fields[1].toFloat(), fields.getOrNull(2)?.let(PenKind::valueOf) ?: PenKind.PRESSURE) }.getOrNull()
                } ?: fallback[i]
            }
        }
    }

    private val styles = decode(preferences.getString("penPresets", null), current).toMutableList()
    var selected = preferences.getInt("penPreset", 0).takeIf { it in 0 until COUNT } ?: 0
        private set
    init {
        // The live pen wins over a stale stored copy of the selected preset (for example after an upgrade).
        styles[selected] = current
    }
    val all: List<PenStyle> get() = styles.toList()
    operator fun get(index: Int) = styles[index]

    fun select(index: Int) {
        require(index in 0 until COUNT) { "No such pen" }
        selected = index; save()
    }
    /** Records the live pen into the selected preset; returns true when something changed. */
    fun sync(live: PenStyle): Boolean {
        if (styles[selected] == live) return false
        styles[selected] = live; save(); return true
    }
    private fun save() { preferences.edit().putString("penPresets", encode(styles)).putInt("penPreset", selected).apply() }
}
