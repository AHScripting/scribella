package dev.ownednotes.app

import dev.ownednotes.core.Notebook

/**
 * P122 (F195): what one import brought over and what it left out. Every importer that can drop content returns one,
 * and the app shows it after the import whenever something was left out. The original file is never changed.
 */
class ImportReport(val source: String, val brought: List<String>, val skipped: List<String>) {
    val complete get() = skipped.isEmpty()
    fun text(): String = buildString {
        append("Brought over: ").append(brought.joinToString(", ").ifEmpty { "nothing" }).append('.')
        if (skipped.isNotEmpty()) append("\n\nLeft out: ").append(skipped.joinToString(", ")).append('.')
        append("\n\nThe original ").append(source).append(" is unchanged.")
    }

    companion object {
        fun count(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
        /** What a newly imported notebook holds, e.g. "2 pages, 1 PDF background, 3 ink strokes, 1 picture". */
        fun contents(notebook: Notebook): List<String> = buildList {
            val pages = notebook.pages
            add(count(pages.size, "page", "pages"))
            pages.count { it.pdfBackground != null || it.imageBackground != null }.takeIf { it > 0 }?.let { add(count(it, "page background", "page backgrounds")) }
            pages.sumOf { it.strokes.size }.takeIf { it > 0 }?.let { add(count(it, "ink stroke", "ink strokes")) }
            pages.sumOf { it.images.size }.takeIf { it > 0 }?.let { add(count(it, "picture", "pictures")) }
            pages.sumOf { it.textBoxes.size }.takeIf { it > 0 }?.let { add(count(it, "text box", "text boxes")) }
        }
    }
}
