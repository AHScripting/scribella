package dev.ownednotes.core

/**
 * P680 (B68, schema 38): a handwritten sticky note. Its ink is ordinary strokes whose [InkStroke.note] names the note,
 * so it moves, folds and is deleted with the note, and is drawn over the page ink, clipped to the note.
 */
object StickyInk {
    /** Side of a folded handwritten note's tab, in page units. */
    const val TAB = 42f
    /** Ink sticky notes on [page], in drawing order. */
    fun notes(page: Page): List<TextBox> = page.textBoxes.filter { it.sticky?.ink == true }

    /**
     * After an edit from [old] to [new]: ink of a note that moved moves with it (unless the edit moved that ink too),
     * ink of a deleted note is deleted, and ink naming a note that was never on this page (a paste) becomes plain page ink.
     */
    fun follow(old: Page, new: Page): Page {
        if (new.strokes.none { it.note != null }) return new
        val before = notes(old).associateBy { it.id }
        val now = notes(new).associateBy { it.id }
        val oldStrokes = old.strokes.associateBy { it.id }
        var changed = false
        val strokes = new.strokes.mapNotNull { stroke ->
            val id = stroke.note ?: return@mapNotNull stroke
            val note = now[id]; val was = before[id]
            when {
                note == null && was != null && stroke.id in oldStrokes -> { changed = true; null }
                note == null -> { changed = true; stroke.copy(note = null) }
                was != null && (note.placement.x != was.placement.x || note.placement.y != was.placement.y) &&
                    oldStrokes[stroke.id].let { it === stroke || it == stroke } -> {
                    changed = true; stroke.translated(note.placement.x - was.placement.x, note.placement.y - was.placement.y)
                }
                else -> stroke
            }
        }
        return if (changed) new.copy(strokes = strokes) else new
    }
}

/** The handwritten note a paint slice draws (its ink is clipped to it), or null for any other slice. */
val Page.inkNote: TextBox? get() = textBoxes.singleOrNull()?.takeIf { it.sticky?.ink == true }

/** The open handwritten note on top at [point], if any; new pen ink that starts there belongs to it. */
fun Page.inkNoteAt(point: Point): TextBox? = StickyInk.notes(this).lastOrNull {
    it.sticky?.collapsed == false && canEdit(it) && it.placement.contains(point)
}
