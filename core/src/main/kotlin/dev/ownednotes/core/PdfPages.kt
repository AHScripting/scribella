package dev.ownednotes.core

/** User page numbers are one-based. Output follows original document order without duplicates. */
object PdfPages {
    fun select(text: String, total: Int, limit: Int = NotebookLimits.PDF_PAGES): List<Int> {
        require(total > 0 && limit > 0) { "No pages available" }
        require(text.length <= 20_000) { "Page selection is too long" }
        if (text.isBlank() || text.trim().equals("all", true)) {
            require(total <= limit) { "Choose at most $limit pages" }
            return (0 until total).toList()
        }
        val selected = sortedSetOf<Int>()
        for (part in text.split(',')) {
            val match = Regex("\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?").matchEntire(part)
            require(match != null) { "Use page numbers and ranges, for example 1-3, 7, 10-12" }
            val first = match.groupValues[1].toIntOrNull() ?: error("Invalid page number")
            val last = if (match.groupValues[2].isEmpty()) first else
                requireNotNull(match.groupValues[2].toIntOrNull()) { "Invalid page number" }
            require(first in 1..total && last in first..total) { "Pages must be between 1 and $total, with ranges in ascending order" }
            require(last.toLong() - first + 1 <= limit) { "Choose at most $limit pages" }
            for (number in first..last) {
                selected.add(number - 1)
                require(selected.size <= limit) { "Choose at most $limit pages" }
            }
        }
        return selected.toList()
    }
}
