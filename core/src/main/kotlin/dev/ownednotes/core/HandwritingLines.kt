package dev.ownednotes.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

data class HandwritingLine(val strokeIndices: List<Int>, val strokes: List<List<Point>>)

/** Horizontal layout heuristic, independent of stroke capture order across lines.
 * All fragments occur exactly once; within a line their original order is retained.
 * Small accents/dots attach after body clustering so they cannot create a spurious line.
 * This does not infer columns, rotation, or split a continuous stroke across lines.
 */
object HandwritingLines {
    const val MAX_LINES = 32
    private data class Box(val index: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val center get() = (top + bottom) / 2f
        val height get() = bottom - top
    }
    internal fun validate(strokes: List<List<Point>>) {
        require(strokes.isNotEmpty() && strokes.size <= HandwritingInput.MAX_STROKES &&
            strokes.sumOf { it.size.toLong() } <= HandwritingInput.MAX_POINTS &&
            strokes.all { it.isNotEmpty() && it.all { p -> p.x.isFinite() && p.y.isFinite() && abs(p.x) <= 1_000_000 && abs(p.y) <= 1_000_000 } }) {
            "Invalid or oversized handwriting layout. Select a smaller area."
        }
    }
    fun split(strokes: List<List<Point>>): List<HandwritingLine> {
        validate(strokes)
        val boxes = strokes.mapIndexed { index, points -> Box(index, points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }) }
        val heights = boxes.map { it.height }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return listOf(line(strokes.indices.toList(), strokes))
        // Nearest-rank80th percentile keeps a short cursive body above up to three tiny marks,
        // while avoiding a lone tall outlier once there are several body strokes.
        val typical = heights[(heights.size * 4 + 4) / 5 - 1]
        val bodies = boxes.filter { it.height >= typical * .35f }.sortedWith(compareBy<Box> { it.center }.thenBy { it.index })
        var groups = mutableListOf<MutableList<Box>>()
        // Body boxes enter each group in ascending center order. Keep the same upper median
        // without allocating and sorting every prefix. Bands freeze before unsorted marks attach.
        fun center(group: List<Box>) = group[group.size / 2].center
        bodies.forEach { box ->
            val last = groups.lastOrNull()
            if (last != null && abs(box.center - center(last)) <= typical * .65f) last += box
            else groups += mutableListOf(box)
        }
        groups = joinNearFragments(groups, strokes, typical)
        require(groups.size <= MAX_LINES) { "More than32 handwriting lines. Select a smaller area or turn off separate lines." }
        val bodyIds = bodies.mapTo(HashSet()) { it.index }
        // Freeze body bands before attaching marks; a sequence of accents must not grow a band
        // toward another line and steal its later dots.
        val bands = groups.map { group -> Triple(group.minOf { it.top }, group.maxOf { it.bottom }, center(group)) }
        boxes.filter { it.index !in bodyIds }.forEach { mark ->
            val target = bands.indices.minWith(compareBy<Int> { index ->
                maxOf(0f, bands[index].first - mark.bottom, mark.top - bands[index].second)
            }.thenBy { abs(mark.center - bands[it].third) }.thenBy { it })
            groups[target] += mark
        }
        return groups.map { group -> line(group.map { it.index }.sorted(), strokes) }
    }

    /** Skeletonised letters can have a detached descender almost touching another sample in the same glyph.
     * Rejoin only a narrow singleton immediately below a frozen multi-stroke body band. Bounding-box overlap
     * alone is insufficient: actual sampled ink must nearly touch. Do not grow a band through a chain of fragments.
     * Exhausting the proximity budget leaves remaining groups unchanged instead of doing unbounded pair work.
     */
    private fun joinNearFragments(groups: List<MutableList<Box>>, strokes: List<List<Point>>, typical: Float): MutableList<MutableList<Box>> {
        val joined = groups.map { it.toMutableList() }
        val epsilon = typical.toDouble() * .025
        val epsilonSquared = epsilon * epsilon
        var work = 0
        for (index in 1 until groups.size) {
            val fragment = groups[index].singleOrNull() ?: continue
            val parent = groups[index - 1]
            if (parent.size < 2 || fragment.right - fragment.left > typical * .8f || fragment.height > typical * 1.1f) continue
            val top = parent.minOf { it.top }; val bottom = parent.maxOf { it.bottom }
            if (fragment.top < parent[parent.size / 2].center || fragment.bottom <= bottom || fragment.top > bottom + epsilon ||
                fragment.left > parent.maxOf { it.right } + epsilon || fragment.right < parent.minOf { it.left } - epsilon || fragment.top < top) continue
            val nearby = mutableListOf<Point>()
            for (body in parent) for (point in strokes[body.index]) {
                if (++work > 250_000) return joined.filterTo(mutableListOf()) { it.isNotEmpty() }
                if (point.x >= fragment.left - epsilon && point.x <= fragment.right + epsilon &&
                    point.y >= fragment.top - epsilon && point.y <= fragment.bottom + epsilon) nearby += point
            }
            var touches = false
            search@ for (point in strokes[fragment.index]) for (other in nearby) {
                if (++work > 250_000) return joined.filterTo(mutableListOf()) { it.isNotEmpty() }
                val dx = point.x.toDouble() - other.x; val dy = point.y.toDouble() - other.y
                if (dx * dx + dy * dy <= epsilonSquared) { touches = true; break@search }
            }
            if (touches) { joined[index - 1] += fragment; joined[index].clear() }
        }
        return joined.filterTo(mutableListOf()) { it.isNotEmpty() }
    }

    internal fun line(indices: List<Int>, source: List<List<Point>>): HandwritingLine {
        val selected = indices.map { source[it] }; val all = selected.flatten()
        val left = all.minOf { it.x }; val top = all.minOf { it.y }
        val scale = 1000f / max(1f, max(all.maxOf { it.x } - left, all.maxOf { it.y } - top))
        return HandwritingLine(indices, selected.map { points -> points.map { Point((it.x - left) * scale, (it.y - top) * scale) } })
    }
}

/** Bounded paragraph alternatives, ordered by sum of per-line candidate ranks.
 * Rank is not a confidence score. Never return a paragraph missing a failed/empty line.
 */
object HandwritingParagraphs {
    fun candidates(lines: List<List<String>>): List<String> {
        require(lines.isNotEmpty() && lines.size <= HandwritingLines.MAX_LINES) { "Invalid handwriting line count." }
        val choices = lines.mapIndexed { index, values ->
            values.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10).also {
                require(it.isNotEmpty()) { "No text recognised on line${index + 1}. Select that line and try again." }
            }
        }
        val queue = PriorityQueue<List<Int>> { a, b ->
            val rank = a.sum().compareTo(b.sum())
            if (rank != 0) rank else a.indices.firstNotNullOfOrNull { i -> a[i].compareTo(b[i]).takeIf { it != 0 } } ?: 0
        }
        val first = List(lines.size) { 0 }; queue += first
        val seen = hashSetOf(first); val result = mutableListOf<String>()
        while (queue.isNotEmpty() && result.size < 10) {
            val indices = queue.remove()
            val text = indices.indices.joinToString("\n") { choices[it][indices[it]] }
            require(text.length <= 4096) { "Recognised text is too long. Select a smaller area." }
            result += text
            indices.indices.forEach { index ->
                if (indices[index] + 1 < choices[index].size) {
                    val next = indices.toMutableList().also { it[index]++ }.toList()
                    if (seen.add(next)) queue += next
                }
            }
        }
        return result
    }
}
