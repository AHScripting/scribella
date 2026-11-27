package dev.ownednotes.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the printed text line under a highlighter stroke from rendered page pixels (P042).
 *
 * Android 13's PdfRenderer cannot extract text, so the line is found from ink instead: rows whose pixels are dark
 * across the stroke's span form a band; the band nearest the stroke, if it looks like one line of text, is the
 * line to highlight. Everything here is in raster pixels; callers convert to and from page coordinates.
 */
object TextLineSnap {
    /** The inked extent of one text line: rows [top, bottom] and columns [left, right], inclusive. */
    data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top + 1
        val centerY: Float get() = (top + bottom + 1) / 2f
    }

    /**
     * Returns the text line [points] (a highlighter stroke of [strokeWidth] pixels) was drawn over, or null to keep
     * the stroke freehand: when it is not mostly horizontal, too short, or the pixels under it are not one clear line.
     * [dark] reports whether a pixel inside [width] x [height] is ink.
     */
    fun find(points: List<Point>, strokeWidth: Float, width: Int, height: Int, dark: (x: Int, y: Int) -> Boolean): Band? {
        if (points.size < 2 || width <= 0 || height <= 0 || !(strokeWidth > 0f)) return null
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val span = maxX - minX
        if (span < max(24f, strokeWidth * 1.5f)) return null
        // A highlight across a line wobbles a little; anything steeper is a deliberate freehand mark.
        if (maxY - minY > max(strokeWidth, span * .2f)) return null
        val center = points.map { it.y }.sorted()[points.size / 2]
        val x0 = minX.roundToInt().coerceIn(0, width - 1); val x1 = maxX.roundToInt().coerceIn(0, width - 1)
        if (x1 - x0 < 8) return null
        val cy = center.roundToInt()
        if (cy !in 0 until height) return null

        val reach = (strokeWidth * .9f + 6f).roundToInt()
        val maxLine = max(strokeWidth * 2.5f, 12f).roundToInt()
        val maxGap = max(1, (strokeWidth * .08f).roundToInt())
        val rowCache = HashMap<Int, Float>()
        fun density(y: Int): Float = rowCache.getOrPut(y) {
            if (y !in 0 until height) 0f else {
                var count = 0
                for (x in x0..x1) if (dark(x, y)) count++
                count.toFloat() / (x1 - x0 + 1)
            }
        }
        // A row is part of a line when at least a sliver of its span is inked (thin rules or serifs count too).
        val threshold = .015f
        fun inked(y: Int) = density(y) >= threshold
        // Once seeded, a line extends through rows touched by only a few glyphs: the descenders of a q or a y.
        val few = max(2, ((x1 - x0 + 1) * .003f).roundToInt())
        fun touched(y: Int) = density(y) * (x1 - x0 + 1) >= few - .5f

        // Seed: the inked row closest to the stroke's centre, within reach.
        val seed = (0..reach).asSequence().flatMap { sequenceOf(cy - it, cy + it) }.firstOrNull { it in 0 until height && inked(it) } ?: return null
        // Grow the band both ways, bridging the small gaps inside a line (dots on i, the bar of an e).
        fun grow(from: Int, step: Int): Int? {
            var edge = from; var gap = 0; var y = from + step
            while (y in 0 until height) {
                if (touched(y)) { edge = y; gap = 0 } else if (++gap > maxGap) break
                if (abs(edge - seed) > maxLine) return null
                y += step
            }
            return edge
        }
        val top = grow(seed, -1) ?: return null
        val bottom = grow(seed, 1) ?: return null
        val lineHeight = bottom - top + 1
        if (lineHeight < 4 || lineHeight > maxLine) return null
        // The stroke must sit on the line, not merely brush its edge.
        if (center < top - strokeWidth * .6f || center > bottom + strokeWidth * .6f) return null

        // Text has gaps; a photo or filled box is solid ink.
        var sum = 0f
        for (y in top..bottom) sum += density(y)
        if (sum / lineHeight > .8f) return null

        // Trim to the inked columns under the stroke, letting the end reach the rest of a word it nearly covered.
        val slack = (lineHeight * .6f).roundToInt()
        val lo = max(0, x0 - slack); val hi = min(width - 1, x1 + slack)
        val columns = BooleanArray(hi - lo + 1) { i -> (top..bottom).any { dark(lo + i, it) } }
        val first = (x0 - lo until columns.size).firstOrNull { columns[it] } ?: return null
        val last = (x1 - lo downTo 0).firstOrNull { columns[it] } ?: return null
        if (last < first) return null
        var left = lo + first; var right = lo + last
        while (left - lo > 0 && left > x0 - slack && columns[left - lo - 1]) left--
        while (right - lo < columns.size - 1 && right < x1 + slack && columns[right - lo + 1]) right++
        val covered = (first..last).count { columns[it] }
        if (covered < (x1 - x0 + 1) * .3f) return null
        return wholeLine(Band(left, top, right, bottom), maxLine, width, height, dark)
    }

    /**
     * P734: [band]'s rows grown to its whole printed line, so a highlight over "a" or "on" is as tall as one over
     * "light", and lines of the same type match. The ink under a short stroke is only that word's: x-height letters
     * give a shorter band than ascenders and descenders. The line's run of words (across word spaces, not a column
     * gutter) gives its x-height zone, ascenders and descenders; a line with no descenders still gets room for them,
     * short of the next line's ink. The columns stay [band]'s. Returns [band] when the run does not read as one line.
     */
    private fun wholeLine(band: Band, maxLine: Int, width: Int, height: Int, dark: (x: Int, y: Int) -> Boolean): Band {
        val narrow = band.height
        val rows = band.top..band.bottom
        // The printed run around the band: columns inked in its rows, across word spaces but not a gutter.
        val space = max(4, (narrow * 1.2f).roundToInt())
        fun printed(x: Int) = rows.any { dark(x, it) }
        fun reach(from: Int, step: Int): Int {
            var edge = from; var blank = 0; var x = from + step
            while (x in 0 until width && blank <= space) { if (printed(x)) { edge = x; blank = 0 } else blank++; x += step }
            return edge
        }
        val left = reach(band.left, -1); val right = reach(band.right, 1)
        // Ink per row across the run, one band height above and below.
        val from = max(0, band.top - narrow); val to = min(height - 1, band.bottom + narrow)
        val counts = IntArray(to - from + 1) { i -> var n = 0; for (x in left..right) if (dark(x, from + i)) n++; n }
        fun ink(y: Int) = if (y in from..to) counts[y - from] else 0
        // The x-height zone (the cap height of a run with no lower case): the dense rows around the band's densest,
        // looking a row or two past the band so "a" finds the same densest row as "Typography".
        val peak = (band.top - 2..band.bottom + 2).maxBy { ink(it) }
        if (ink(peak) == 0) return band
        val dense = ink(peak) * .35f
        var core = peak; while (ink(core - 1) >= dense) core--
        var base = peak; while (ink(base + 1) >= dense) base++
        val xHeight = base - core + 1
        if (xHeight < 3 || xHeight > narrow * 1.25f + 1) return band
        // Ascenders and descenders: the rows a few glyphs still reach, up to the first all but empty row.
        val few = max(2, ((right - left + 1) * .003f).roundToInt())
        var top = core; while (top > core - xHeight && ink(top - 1) >= few) top--
        var bottom = base; while (bottom < base + (xHeight * .8f).roundToInt() && ink(bottom + 1) >= few) bottom++
        // Room for descenders on a line with none (0.4 x-height, as in most text faces), keeping a blank row before the
        // next line's ink. Faint tails of this line's own descenders run on until the first blank row.
        val room = base + (xHeight * .4f).roundToInt()
        if (bottom < room) {
            var y = bottom + 1
            while (y <= room && ink(y) > 0) y++
            val next = (y..room + 1).firstOrNull { ink(it) > 0 }
            bottom = max(bottom, minOf(room, to, if (next == null) Int.MAX_VALUE else next - 2))
        }
        top = min(top, band.top); bottom = max(bottom, band.bottom)
        if (bottom - top + 1 > maxLine) return band
        return Band(band.left, top, band.right, bottom)
    }

    /** The tallest band still taken for one line of text: a little over the stroke, or 5 % of the page for a thin pen over large print. */
    fun maxLineHeight(strokeWidth: Float, height: Int) = max(max(strokeWidth * 2.5f, 12f), height * .05f)

    /**
     * B04 (P132): the text lines a highlighter stroke covers. A stroke along one line gives that line, as [find] does;
     * a stroke that colours a whole region (back and forth, or broad, over several lines) gives every line it actually
     * coloured across, so each can be highlighted straight. Lines it only brushes or outlines (a circle around a
     * paragraph, a cross through it) are left out, and nothing at all means the stroke stays freehand.
     */
    fun findLines(points: List<Point>, strokeWidth: Float, width: Int, height: Int, pageHeight: Int = height, dark: (x: Int, y: Int) -> Boolean): List<Band> {
        // A stroke that colours two or more whole lines is a region, even when it is one straight pass; otherwise the one-line snap decides.
        val lines = region(points, strokeWidth, width, height, max(height, pageHeight), dark)
        if (lines.size >= 2) return lines
        return find(points, strokeWidth, width, height, dark)?.let { listOf(it) } ?: lines
    }

    /** [pageHeight]: the whole rendered page, when [height] is only a window of it. */
    private fun region(points: List<Point>, strokeWidth: Float, width: Int, height: Int, pageHeight: Int, dark: (x: Int, y: Int) -> Boolean): List<Band> {
        if (points.size < 2 || width <= 0 || height <= 0 || !(strokeWidth > 0f)) return emptyList()
        val radius = strokeWidth / 2f
        val x0 = kotlin.math.floor(points.minOf { it.x } - radius).toInt().coerceIn(0, width - 1)
        val x1 = kotlin.math.ceil(points.maxOf { it.x } + radius).toInt().coerceIn(0, width - 1)
        val y0 = kotlin.math.floor(points.minOf { it.y } - radius).toInt().coerceIn(0, height - 1)
        val y1 = kotlin.math.ceil(points.maxOf { it.y } + radius).toInt().coerceIn(0, height - 1)
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        if (w < 8 || h < 8 || w.toLong() * h > 4_000_000L) return emptyList()

        // The area the stroke swept: every pixel centre within half the stroke width of its path.
        val swept = BooleanArray(w * h)
        val path = if (points.size == 1) points + points else points
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val dx = b.x - a.x; val dy = b.y - a.y; val length2 = dx * dx + dy * dy
            val left = kotlin.math.floor(min(a.x, b.x) - radius).toInt().coerceIn(x0, x1)
            val right = kotlin.math.ceil(max(a.x, b.x) + radius).toInt().coerceIn(x0, x1)
            val top = kotlin.math.floor(min(a.y, b.y) - radius).toInt().coerceIn(y0, y1)
            val bottom = kotlin.math.ceil(max(a.y, b.y) + radius).toInt().coerceIn(y0, y1)
            for (y in top..bottom) for (x in left..right) {
                val px = x + .5f; val py = y + .5f
                val t = if (length2 > 0f) (((px - a.x) * dx + (py - a.y) * dy) / length2).coerceIn(0f, 1f) else 0f
                val ex = a.x + t * dx - px; val ey = a.y + t * dy - py
                if (ex * ex + ey * ey <= radius * radius) swept[(y - y0) * w + (x - x0)] = true
            }
        }
        fun swept(x: Int, y: Int) = y in y0..y1 && swept[(y - y0) * w + (x - x0)]

        // Text lines across the stroke's columns, looking a line height beyond it so partly coloured lines stay whole.
        val maxLine = maxLineHeight(strokeWidth, pageHeight).roundToInt()
        val top = max(0, y0 - maxLine); val bottom = min(height - 1, y1 + maxLine)
        val few = max(2, (w * .003f).roundToInt())
        val ink = IntArray(bottom - top + 1) { i -> (x0..x1).count { dark(it, top + i) } }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in ink.indices) {
            val inked = ink[i] >= few
            if (inked && start < 0) start = i
            if (!inked && start >= 0) { runs += (top + start) until (top + i); start = -1 }
        }
        if (start >= 0) runs += (top + start)..bottom
        // Join the pieces of one line (the dot of an i, an accent) across gaps much smaller than the line itself.
        val lines = mutableListOf<IntRange>()
        for (run in runs) {
            val last = lines.lastOrNull()
            val gap = if (last == null) Int.MAX_VALUE else run.first - last.last - 1
            if (last != null && gap <= max(2, (.25f * max(last.count(), run.count())).roundToInt())) lines[lines.size - 1] = last.first..run.last
            else lines += run
        }

        return lines.mapNotNull { rows ->
            val lineHeight = rows.count()
            if (lineHeight < 4 || lineHeight > maxLine || rows.last < y0 || rows.first > y1) return@mapNotNull null
            // Coloured columns: the stroke covers at least half the line's height there.
            val coloured = (x0..x1).filter { x -> rows.count { swept(x, it) } * 2 >= lineHeight }
            if (coloured.isEmpty()) return@mapNotNull null
            val first = coloured.first(); val last = coloured.last()
            // Colouring fills most of the stretch it spans; an outline or a cross only touches it here and there.
            if (coloured.size < (last - first + 1) * .5f || last - first < max(8f, lineHeight * .8f)) return@mapNotNull null
            // Text has gaps; a photo or filled box is solid ink.
            var sum = 0
            for (y in rows) for (x in first..last) if (dark(x, y)) sum++
            if (sum.toFloat() / (lineHeight * (last - first + 1)) > .8f) return@mapNotNull null
            // Trim to the printed columns, letting the ends reach the rest of a word the stroke nearly covered.
            val slack = (lineHeight * .6f).roundToInt()
            val printed = { x: Int -> rows.any { dark(x, it) } }
            var left = (first..last).firstOrNull(printed) ?: return@mapNotNull null
            var right = (last downTo first).firstOrNull(printed) ?: return@mapNotNull null
            if ((left..right).count(printed) < (last - first + 1) * .3f) return@mapNotNull null
            while (left > 0 && left > first - slack && printed(left - 1)) left--
            while (right < width - 1 && right < last + slack && printed(right + 1)) right++
            wholeLine(Band(left, rows.first, right, rows.last), maxLine, width, height, dark)
        }
    }
}