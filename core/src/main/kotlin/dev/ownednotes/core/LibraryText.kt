package dev.ownednotes.core

/**
 * P128 (F144): finds notebooks by the words in their saved reviewed handwriting and typed text boxes, for the Library
 * search. Each notebook's text is read once and kept in memory against its file time; notebook files are only read.
 * Unreviewed ink is never guessed at: only handwriting the user reviewed and saved (and that still matches its ink) counts.
 */
class LibraryText(private val library: NotebookLibrary) {
    private val cache = HashMap<String, Pair<Long, String>>()

    /**
     * Ids of [entries] whose text contains [query], in [entries] order. [open], the notebook being edited, is searched
     * as it is now instead of its file. Unreadable notebooks never match. Safe to call from any thread.
     */
    @Synchronized fun matching(entries: List<LibraryEntry>, query: String, open: Notebook? = null): Set<String> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptySet()
        cache.keys.retainAll(entries.filter { it.availableOffline }.map { it.id }.toSet())
        return entries.filter { it.error == null && it.availableOffline && text(it, open).contains(needle) }.mapTo(LinkedHashSet()) { it.id }
    }

    private fun text(entry: LibraryEntry, open: Notebook?): String {
        if (open != null && open.id == entry.id) return of(open)
        cache[entry.id]?.takeIf { entry.modified != 0L && it.first == entry.modified }?.let { return it.second }
        val text = runCatching { of(library.read(entry.id)) }.getOrDefault("")
        cache[entry.id] = entry.modified to text
        return text
    }

    companion object {
        /** Collapses whitespace and case, like Search in notebook, so a phrase over two lines still matches. */
        fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

        /** Saved reviewed handwriting still tied to its ink, then typed text boxes, of every page. */
        fun of(notebook: Notebook): String = normalize(notebook.pages.flatMap { page ->
            page.activeReviewedInk.map { it.text } + page.textBoxes.map { it.text }
        }.joinToString("\n"))
    }
}
