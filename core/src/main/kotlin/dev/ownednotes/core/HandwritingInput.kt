package dev.ownednotes.core

import kotlin.math.max

/** A read-only request for a future recognizer. Never feed original samples through area erasures. */
class HandwritingInput private constructor(
    val notebookId: String,
    private val sourcePage: Page,
    val strokeIds: List<String>,
    val strokes: List<List<Point>>,
    val bounds: InkTextBounds,
) {
    val pageId: String get() = sourcePage.id

    /** Review results are valid only while this exact source page is still current. */
    fun matches(notebook: Notebook, currentPageId: String): Boolean =
        notebook.id == notebookId && currentPageId == pageId &&
            notebook.pages.firstOrNull { it.id == pageId } == sourcePage

    companion object {
        const val MAX_STROKES = 512
        const val MAX_POINTS = 50_000
        private val pens = setOf(InkStroke.PEN, InkStroke.BALLPOINT, InkStroke.CALLIGRAPHY, InkStroke.PENCIL)

        /** Empty ids means the whole visible page. Positions use page units, independent of viewport zoom.
         * Native timestamps are relative to each stroke; omit them rather than invent cross-stroke timing.
         * Subtract area erasures from sampled centerlines; never send hidden source spans to recognition.
         */
        fun capture(notebook: Notebook, pageId: String, ids: Set<String> = emptySet()): HandwritingInput {
            val page = requireNotNull(notebook.pages.firstOrNull { it.id == pageId }) { "Page no longer exists." }
            val visible = page.drawnStrokes
            val visibleIds = visible.mapTo(HashSet()) { it.id }
            require(ids.all { it in visibleIds }) { "Selected ink is missing or hidden. Select it again." }
            val ink = visible.filter { (ids.isEmpty() || it.id in ids) && it.brush in pens }
            require(ink.isNotEmpty()) { "Select visible handwriting made with a pen." }
            require(ink.size <= MAX_STROKES) { "Too much handwriting. Select a smaller area." }
            require(ink.sumOf { VisibleHandwriting.work(it) } <= VisibleHandwriting.MAX_WORK) { "Erased ink is too complex. Select a smaller area." }
            val count = ink.sumOf { it.samples.size.toLong() }
            require(count in 1..MAX_POINTS.toLong() && ink.all { it.samples.isNotEmpty() }) {
                "Too many or missing pen points. Select a smaller area."
            }
            ink.forEach { stroke -> stroke.samples.forEach { sample ->
                require(sample.x.isFinite() && sample.y.isFinite() &&
                    kotlin.math.abs(sample.x) <= 1_000_000f && kotlin.math.abs(sample.y) <= 1_000_000f) {
                    "Invalid handwriting coordinates."
                }
            } }
            val remaining = ink.map { it.id to VisibleHandwriting.fragments(it) }.filter { it.second.isNotEmpty() }
            val fragments = remaining.flatMap { it.second }
            require(fragments.isNotEmpty()) { "No visible pen centerline remains. Select other handwriting." }
            require(fragments.size <= MAX_STROKES && fragments.sumOf { it.size.toLong() } <= MAX_POINTS) {
                "Too much remaining handwriting. Select a smaller area."
            }
            val all = fragments.flatten()
            val left = all.minOf { it.x }; val top = all.minOf { it.y }
            val right = all.maxOf { it.x }; val bottom = all.maxOf { it.y }
            val bounds = InkTextBounds(left, top, maxOf(left + 1f, right), maxOf(top + 1f, bottom))
            // Translation and uniform scale preserve layout and inter-stroke spacing, including dots.
            val scale = 1000f / max(1f, max(right - left, bottom - top))
            val points = fragments.map { fragment -> fragment.map { Point((it.x - left) * scale, (it.y - top) * scale) } }
            return HandwritingInput(notebook.id, page, remaining.map { it.first }, points, bounds)
        }
    }
}
