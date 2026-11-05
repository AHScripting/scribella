package dev.ownednotes.core

/** Explicit recognition-only choices. Never transforms the notebook or its source-identity snapshot. */
data class HandwritingLayout(val quarterTurns: Int = 0, val columns: Int = 1, val columnFraction: Float = .5f,
    val tiltDegrees: Float = 0f, val slantDegrees: Float = 0f) {
    init { require(quarterTurns in 0..3 && columns in 1..2 && columnFraction.isFinite() && columnFraction in .05f.. .95f &&
        tiltDegrees.isFinite() && tiltDegrees in -45f..45f && slantDegrees.isFinite() && slantDegrees in -30f..30f) { "Unsupported handwriting layout." } }

    /** Clockwise turns in screen coordinates. Translation/uniform scaling preserve every fragment and point. */
    fun oriented(strokes: List<List<Point>>): List<List<Point>> {
        HandwritingLines.validate(strokes)
        val turned = strokes.map { stroke -> stroke.map { p -> when (quarterTurns) {
            1 -> Point(-p.y, p.x)
            2 -> Point(-p.x, -p.y)
            3 -> Point(p.y, -p.x)
            else -> p
        } } }
        val adjusted = if (tiltDegrees == 0f) turned else {
            val radians = Math.toRadians(tiltDegrees.toDouble())
            val cosine = kotlin.math.cos(radians); val sine = kotlin.math.sin(radians)
            turned.map { stroke -> stroke.map { p ->
                Point((p.x * cosine - p.y * sine).toFloat(), (p.x * sine + p.y * cosine).toFloat())
            } }
        }
        // Recognition-only shear follows rotation: correct leaning letters without tilting rows.
        val shear = kotlin.math.tan(Math.toRadians(slantDegrees.toDouble()))
        val straightened = if (slantDegrees == 0f) adjusted else adjusted.map { stroke ->
            stroke.map { p -> Point((p.x + shear * p.y).toFloat(), p.y) }
        }
        return HandwritingLines.line(straightened.indices.toList(), straightened).strokes
    }

    /** Explicit placement aid, not column/text detection. The user must review the chosen gap.
     * A cut may not cross any fragment, and must leave ink on both sides within the picker range.
     */
    fun columnGap(strokes: List<List<Point>>): Float? {
        val intervals = oriented(strokes).map { points -> points.minOf { it.x } to points.maxOf { it.x } }
            .sortedWith(compareBy<Pair<Float, Float>> { it.first }.thenBy { it.second })
        val left = intervals.first().first; val width = intervals.maxOf { it.second } - left
        if (width <= 0f) return null
        var end = intervals.first().second
        val gaps = mutableListOf<Pair<Float, Float>>()
        intervals.drop(1).forEach { (start, stop) ->
            if (start > end) {
                val cut = ((end + start) / 2f - left).div(width).coerceIn(.05f, .95f)
                val x = left + cut * width
                if (x > end && x < start) gaps += (start - end) to cut
            }
            end = maxOf(end, stop)
        }
        return gaps.sortedWith(compareByDescending<Pair<Float, Float>> { it.first }
            .thenBy { kotlin.math.abs(it.second - columnFraction) }.thenBy { it.second }).firstOrNull()?.second
    }

    /** Left column first, then right; each column top-to-bottom, preserving capture order within each row.
     * The gutter is a fraction of the oriented ink bounds, shown in the picker. Crossing/empty columns
     * require a different selection; never split a continuous stroke, guess its meaning or omit it.
     */
    fun prepare(strokes: List<List<Point>>, separateLines: Boolean): List<HandwritingLine> {
        val turned = if (quarterTurns == 0 && tiltDegrees == 0f && slantDegrees == 0f) strokes.also { HandwritingLines.validate(it) } else oriented(strokes)
        val groups = if (columns == 1) listOf(turned.indices.toList()) else {
            val right = turned.maxOf { stroke -> stroke.maxOf { it.x } }
            val start = turned.minOf { stroke -> stroke.minOf { it.x } }
            val cut = start + (right - start) * columnFraction
            val sides = turned.map { stroke ->
                val left = stroke.minOf { it.x }; val end = stroke.maxOf { it.x }
                require(!(left < cut && end > cut)) { "Handwriting crosses the column divider. Move it into a clear gap or choose one column." }
                if (end <= cut) 0 else 1
            }
            (0..1).map { side -> turned.indices.filter { sides[it] == side }.also {
                require(it.isNotEmpty()) { "Both columns need handwriting. Choose one column or select both columns." }
            } }
        }
        return groups.flatMap { indices ->
            if (separateLines) HandwritingLines.split(indices.map { turned[it] }).map { line ->
                line.copy(strokeIndices = line.strokeIndices.map { indices[it] })
            } else listOf(HandwritingLines.line(indices, turned))
        }.also { require(it.size <= HandwritingLines.MAX_LINES) { "More than 32 handwriting lines. Select a smaller area." } }
    }
}
