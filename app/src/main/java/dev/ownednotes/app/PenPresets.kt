package dev.ownednotes.app

import android.content.SharedPreferences

/**
 * Saved pen styles shown as separate pens on the tool bar (design build-order step 3).
 * The selected preset always mirrors `InkCanvas.penStyle`, so editing the pen edits its preset.
 * Stored as `penPresets` ("color:width:kind" separated by ';') and `penPreset` (selected index).
 * Legacy two-field styles decode as pressure pens.
 *
 * P619 (board 10): the list grows and shrinks. A new install starts with one pen, the + on the tool bar adds one (up to
 * [MAX]) and holding a pen removes it (never the last). Stored lists keep their length, so people who had three pens keep
 * three. The same class keeps the highlighters, with their own keys and vivid colours ([highlighters]).
 */
class PenPresets(private val preferences: SharedPreferences, current: PenStyle,
    private val listKey: String = "penPresets", private val indexKey: String = "penPreset",
    private val fallback: (PenStyle) -> List<PenStyle> = ::defaults) {
    companion object {
        /** The pens of the three-pen tool bar before P619; they stay the colours of added pens and the fallbacks. */
        const val COUNT = 3
        const val MAX = 8
        private fun defaults(current: PenStyle) = listOf(current, PenStyle(0xff1565c0, 2.5f), PenStyle(0xffc62828, 8f))
        /** Board 10's vivid highlighters: yellow first, then green and pink for added ones. */
        val VIVID = listOf(0xffffe600L, 0xff2ee84aL, 0xffff2d95L, 0xffff9f0aL, 0xff00c7ffL, 0xffbf5af2L, 0xffff453aL, 0xffa6ff00L)
        private fun highlighterDefaults(current: PenStyle) = listOf(current) + VIVID.drop(1).map { current.copy(color = it) }
        fun encode(styles: List<PenStyle>) = styles.joinToString(";") { "${it.color}:${it.width}:${it.kind.name}" }
        /** Unreadable or out-of-range stored values fall back to defaults, slot by slot; nothing stored is one pen. */
        fun decode(text: String?, current: PenStyle, fallback: (PenStyle) -> List<PenStyle> = ::defaults): List<PenStyle> {
            val defaults = fallback(current)
            val parts = text?.takeIf { it.isNotBlank() }?.split(';')?.take(MAX) ?: return listOf(current)
            return parts.mapIndexed { i, part ->
                part.split(':').takeIf { it.size in 2..3 }?.let { fields ->
                    runCatching { PenStyle(fields[0].toLong(), fields[1].toFloat(), fields.getOrNull(2)?.let(PenKind::valueOf) ?: PenKind.PRESSURE) }.getOrNull()
                } ?: defaults[i % defaults.size]
            }
        }
        /** The highlighters' presets, kept beside the pens'. */
        fun highlighters(preferences: SharedPreferences, current: PenStyle) =
            PenPresets(preferences, current, "highlighterPresets", "highlighterPreset", ::highlighterDefaults)
    }

    private val styles = decode(preferences.getString(listKey, null), current, fallback).toMutableList()
    var selected = preferences.getInt(indexKey, 0).takeIf { it in styles.indices } ?: 0
        private set
    init {
        // The live pen wins over a stale stored copy of the selected preset (for example after an upgrade).
        styles[selected] = current
    }
    val all: List<PenStyle> get() = styles.toList()
    val size get() = styles.size
    val canAdd get() = styles.size < MAX
    val canRemove get() = styles.size > 1
    operator fun get(index: Int) = styles[index]

    fun select(index: Int) {
        require(index in styles.indices) { "No such pen" }
        selected = index; save()
    }
    /** Records the live pen into the selected preset; returns true when something changed. */
    fun sync(live: PenStyle): Boolean {
        if (styles[selected] == live) return false
        styles[selected] = live; save(); return true
    }
    /** Adds a pen after the others, in the first default colour not on the bar yet, and selects it. Returns its index. */
    fun add(): Int {
        check(canAdd) { "No room for another pen" }
        val base = styles[selected]
        val unused = fallback(base).drop(1).firstOrNull { d -> styles.none { it.color == d.color } }
        styles += unused?.let { base.copy(color = it.color, width = it.width) } ?: base
        selected = styles.lastIndex; save(); return selected
    }
    /** Removes pen [index] (never the last one); the selection stays on the same pen, or moves to a neighbour. */
    fun remove(index: Int) {
        require(index in styles.indices) { "No such pen" }
        check(canRemove) { "The last pen stays" }
        styles.removeAt(index)
        if (selected > index) selected--
        selected = selected.coerceIn(0, styles.lastIndex); save()
    }
    private fun save() { preferences.edit().putString(listKey, encode(styles)).putInt(indexKey, selected).apply() }
}
