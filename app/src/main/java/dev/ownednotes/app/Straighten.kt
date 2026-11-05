package dev.ownednotes.app

import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max

/** P077 straighten handwriting (F060): how far a selected line of writing slopes. */
object Straighten {
    /** Writing steeper than this is more likely meant that way (a margin note, a diagram label). */
    const val MAX_DEGREES = 30f
    /** Below this the line already reads as level; turning it would only blur the ink. */
    const val MIN_DEGREES = .5f

    sealed interface Result
    data class Turn(val degrees: Float) : Result
    object Level : Result
    object NotALine : Result

    /**
     * The clockwise turn (as `transformStrokes` takes it) that makes [strokes]' writing line level. The line is the
     * least-squares fit through the ink, resampled evenly along each stroke so slow, dense samples do not outweigh fast
     * ones. Ink must be clearly wider than tall to count as a line of writing.
     */
    fun turn(strokes: List<InkStroke>): Result {
        val points = strokes.flatMap { resample(it.samples.map { s -> Point(s.x, s.y) }) }
        if (points.size < 2) return NotALine
        val mx = points.sumOf { it.x.toDouble() } / points.size; val my = points.sumOf { it.y.toDouble() } / points.size
        var sxx = 0.0; var sxy = 0.0
        points.forEach { val dx = it.x - mx; sxx += dx * dx; sxy += dx * (it.y - my) }
        if (sxx <= 0.0) return NotALine
        val radians = atan(sxy / sxx)
        val slope = Math.toDegrees(radians).toFloat()
        if (abs(slope) > MAX_DEGREES) return NotALine
        // Measured along and across the fitted line, the ink must be clearly wider than tall.
        val c = Math.cos(radians); val s = Math.sin(radians)
        val along = points.map { (it.x - mx) * c + (it.y - my) * s }
        val across = points.map { -(it.x - mx) * s + (it.y - my) * c }
        if (along.max() - along.min() < 1.5 * max(1.0, across.max() - across.min())) return NotALine
        if (abs(slope) < MIN_DEGREES) return Level
        // Ink rising to the right has a negative slope on the y-down page; a clockwise turn by the same amount levels it.
        return Turn(-slope)
    }

    private fun resample(points: List<Point>): List<Point> {
        if (points.size < 2) return points
        val length = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        if (length <= 0f) return points.take(1)
        val step = max(.5f, length / 64f)
        val out = mutableListOf(points.first()); var carry = 0f
        points.zipWithNext { a, b ->
            val d = hypot(b.x - a.x, b.y - a.y)
            var t = step - carry
            while (t <= d) { out += Point(a.x + (b.x - a.x) * t / d, a.y + (b.y - a.y) * t / d); t += step }
            carry = (carry + d) % step
        }
        return out
    }
}
