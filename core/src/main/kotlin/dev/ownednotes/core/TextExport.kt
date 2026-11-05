package dev.ownednotes.core

/**
 * P107 (F189): a notebook's text as plain text or Markdown. Each page in order gives its bookmark name, its typed
 * text boxes in reading order, the printed text read from its PDF, image or photos ([printed], null when not read),
 * saved reviewed handwriting and the names of attached files. Unreviewed handwriting is not converted.
 */
object TextExport {
    const val NO_TEXT = "No typed or printed text on this page."

    fun text(notebook: Notebook, printed: (Page) -> List<String>?): String = buildString {
        append(notebook.title.trim().ifEmpty { "Notebook" }).append("\n")
        notebook.pages.forEachIndexed { index, page ->
            append("\n\n").append(heading(index, page, " · ")).append("\n\n")
            val parts = parts(page, printed(page))
            if (parts.isEmpty()) append("(").append(NO_TEXT).append(")\n")
            parts.forEach { (kind, body) ->
                when (kind) {
                    Kind.PRINTED -> append("Printed text:\n").append(body).append("\n\n")
                    Kind.FILES -> append("Attached files: ").append(body).append("\n\n")
                    Kind.HANDWRITING -> append("Reviewed handwriting:\n").append(body).append("\n\n")
                    Kind.TYPED -> append(body).append("\n\n")
                }
            }
            while (endsWith("\n\n")) setLength(length - 1)
        }
    }.trimEnd() + "\n"

    fun markdown(notebook: Notebook, printed: (Page) -> List<String>?): String = buildString {
        append("# ").append(escapeLine(notebook.title.trim().ifEmpty { "Notebook" })).append("\n")
        notebook.pages.forEachIndexed { index, page ->
            if (!endsWith("\n\n")) append("\n")
            append("## ").append(escapeLine(heading(index, page, " · "))).append("\n\n")
            val parts = parts(page, printed(page))
            if (parts.isEmpty()) append("*").append(NO_TEXT).append("*\n\n")
            parts.forEach { (kind, body) ->
                when (kind) {
                    Kind.PRINTED -> append("**Printed text**\n\n").append(body.split("\n\n").joinToString("\n\n") { paragraph(it) }).append("\n\n")
                    Kind.FILES -> append("**Attached files:** ").append(escapeLine(body)).append("\n\n")
                    Kind.HANDWRITING -> append("**Reviewed handwriting**\n\n").append(paragraph(body)).append("\n\n")
                    Kind.TYPED -> append(paragraph(body)).append("\n\n")
                }
            }
        }
    }.trimEnd() + "\n"

    private enum class Kind { TYPED, PRINTED, FILES, HANDWRITING }

    private fun heading(index: Int, page: Page, separator: String) =
        "Page ${index + 1}" + (page.bookmark?.trim()?.takeIf { it.isNotEmpty() }?.let { separator + it } ?: "")

    private fun parts(page: Page, printed: List<String>?): List<Pair<Kind, String>> {
        val typed = page.textBoxes.sortedWith(compareBy({ it.placement.y }, { it.placement.x }))
            .map { tidy(it.text) }.filter { it.isNotEmpty() }.map { Kind.TYPED to it }
        val read = printed.orEmpty().map { tidy(it) }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.let { listOf(Kind.PRINTED to it.joinToString("\n\n")) }.orEmpty()
        val files = page.attachments.map { it.name.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.let { listOf(Kind.FILES to it.joinToString(", ")) }.orEmpty()
        val handwriting = page.activeReviewedInk.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).map { Kind.HANDWRITING to tidy(it.text) }
        return typed + read + handwriting + files
    }

    /** Unix line ends, no trailing spaces, at most one blank line in a row. */
    private fun tidy(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        .map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n')

    /** One paragraph: its lines kept as hard line breaks, blank lines kept as paragraph breaks. */
    private fun paragraph(text: String) = text.split("\n\n").joinToString("\n\n") { block ->
        block.lines().joinToString("  \n") { escapeLine(it) }
    }

    private val inline = Regex("""[\\`*_\[\]<>#|~]""")
    /** Escapes Markdown so the text reads back exactly as written, including list and heading markers at line start. */
    fun escapeLine(line: String): String {
        val escaped = line.replace(inline) { "\\" + it.value }
        // Leading spaces would make a code block, so the indent is dropped.
        val rest = escaped.trimStart(' ').ifEmpty { return escaped }
        return when {
            rest.startsWith("-") || rest.startsWith("+") || rest.startsWith("=") -> "\\" + rest
            Regex("""^\d+[.)]""").containsMatchIn(rest) -> rest.replaceFirst(Regex("""^(\d+)([.)])"""), "$1\\\\$2")
            else -> rest
        }
    }
}
