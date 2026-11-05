package dev.ownednotes.app

import android.content.SharedPreferences

/** Reusable custom RGB colors; fixed presets never disappear when another color is selected. */
class InkColors(private val preferences: SharedPreferences) {
    val saved: List<Long> get() = preferences.getString("customInkColors", "").orEmpty().split(',')
        .mapNotNull { it.toLongOrNull(16) }.filter { it in 0xff000000L..0xffffffffL }.distinct().take(12)
    fun remember(color: Long) {
        require(color in 0xff000000L..0xffffffffL)
        if (palettes.any { (_, colors) -> colors.any { it.first == color } }) return
        store(listOf(color) + saved)
    }
    /** P129 (F029): removes a saved colour; palette colours are not saved, so they cannot be removed. */
    fun forget(color: Long) = store(saved - color)
    private fun store(colors: List<Long>) = preferences.edit().putString("customInkColors", colors.distinct().take(12)
        .joinToString(",") { it.toString(16) }).apply()

    /**
     * B36: the quick colours (the tool bar's dots, then the rest of the finger ring), one list per tool. Until a colour is
     * used they are the saved colours, then the tool's presets; after that every colour used enters, palette ones too.
     */
    fun quick(highlighter: Boolean): List<Long> {
        val used = preferences.getString(quickKey(highlighter), null)?.split(',')
            ?.mapNotNull { it.toLongOrNull(16) }?.filter { it in 0xff000000L..0xffffffffL } ?: saved
        return (used + (if (highlighter) highlights else presets).map { it.first }).distinct().take(QUICK)
    }
    /**
     * A colour already on a dot keeps its place, so tapping the dots never shuffles them; any other enters first. The list
     * is kept from the first use, so colours saved with the other tool no longer move it.
     */
    fun use(color: Long, highlighter: Boolean) {
        val now = quick(highlighter)
        val next = if (now.indexOf(color) in 0 until QUICK_DOTS) now else (listOf(color) + (now - color)).take(QUICK)
        val key = quickKey(highlighter)
        val stored = next.joinToString(",") { it.toString(16) }
        if (preferences.getString(key, null) != stored) preferences.edit().putString(key, stored).apply()
    }
    private fun quickKey(highlighter: Boolean) = if (highlighter) "quickHighlighterColors" else "quickPenColors"

    /** P129 (F029): the pen palette shown in the pen controls; the highlighter keeps its own set. */
    val palette: Int get() = preferences.getInt("inkPalette", 0).takeIf { it in palettes.indices } ?: 0
    fun selectPalette(index: Int) {
        require(index in palettes.indices)
        preferences.edit().putInt("inkPalette", index).apply()
    }
    val presets get() = palettes[palette].second

    companion object {
        /** B36: the finger ring shows [QUICK] quick colours; the tool bar shows the first [QUICK_DOTS]. */
        const val QUICK = 6; const val QUICK_DOTS = 4
        val defaults = listOf(
            0xff182636L to "Original ink", 0xff000000L to "Black", 0xff1565c0L to "Blue",
            0xffc62828L to "Red", 0xff2e7d32L to "Green", 0xff6a1b9aL to "Purple",
            0xffef6c00L to "Orange", 0xff616161L to "Gray", 0xfff9d43aL to "Yellow",
            0xffff8fabL to "Pink", 0xff00b8d4L to "Cyan", 0xffffffffL to "White",
        )
        val highlights = listOf(defaults[8], defaults[6], defaults[9], 0xff7ed957L to "Lime",
            defaults[10], 0xffb39ddbL to "Lavender", defaults[2], defaults[3], defaults[4], defaults[5], defaults[1], defaults[11])
        val palettes = listOf(
            "Classic" to defaults,
            "Pastel" to listOf(
                0xff90caf9L to "Pastel blue", 0xfff8bbd0L to "Pastel pink", 0xffa5d6a7L to "Mint",
                0xffce93d8L to "Lilac", 0xffffcc80L to "Peach", 0xfffff59dL to "Butter",
                0xff81d4faL to "Sky", 0xffff8a80L to "Coral", 0xffc5e1a5L to "Sage",
                0xffb39ddbL to "Lavender", 0xffd7ccc8L to "Sand", 0xff90a4aeL to "Slate",
            ),
            "Vivid" to listOf(
                0xff000000L to "Black", 0xff2962ffL to "Electric blue", 0xffd50000L to "Crimson",
                0xff00c853L to "Emerald", 0xffaa00ffL to "Violet", 0xffff6d00L to "Tangerine",
                0xffc51162L to "Magenta", 0xff00bfa5L to "Turquoise", 0xffffd600L to "Lemon",
                0xff304ffeL to "Indigo", 0xff64dd17L to "Lime green", 0xffff4081L to "Hot pink",
            ),
            "Earth" to listOf(
                0xff3e2723L to "Espresso", 0xff6d4c41L to "Umber", 0xffbf5b3aL to "Terracotta",
                0xffc79a2eL to "Ochre", 0xff6b7a2aL to "Olive", 0xff4e6b3aL to "Moss",
                0xff2e4d36L to "Forest", 0xff4a6274L to "Slate blue", 0xffa1887fL to "Clay",
                0xff8d3b22L to "Rust", 0xff8a8578L to "Stone", 0xff37383aL to "Charcoal",
            ),
        )
    }
}
