package dev.ownednotes.core

import kotlin.math.abs

/** A gesture-local set of page/object alignment anchors. It never changes saved geometry. */
class AlignmentGuides(private val source: Box, private val targets: List<Box>) {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        init { require(listOf(left, top, right, bottom).all { it.isFinite() } && right >= left && bottom >= top) }
        val xs get() = listOf((left + right) / 2, left, right)
        val ys get() = listOf((top + bottom) / 2, top, bottom)
    }
    private data class Anchor(val at: Float, val box: Box)
    private val xs = targets.flatMap { b -> b.xs.map { Anchor(it, b) } }
    private val ys = targets.flatMap { b -> b.ys.map { Anchor(it, b) } }
    private val sourceX = source.xs
    private val sourceY = source.ys
    data class Result(val dx: Float, val dy: Float, val lines: List<Pair<Point, Point>>)
    fun snap(dx: Float, dy: Float, tolerance: Float): Result {
        require(dx.isFinite() && dy.isFinite() && tolerance.isFinite() && tolerance >= 0f)
        data class Match(val correction: Float, val target: Box, val at: Float)
        fun match(anchors: List<Anchor>, ownAnchors: List<Float>, delta: Float): Match? {
            var best: Match? = null
            for (anchor in anchors) for (own in ownAnchors) {
                val correction = anchor.at - (own + delta)
                if (abs(correction) <= tolerance && (best == null || abs(correction) < abs(best.correction))) best = Match(correction, anchor.box, anchor.at)
            }
            return best
        }
        val x = match(xs, sourceX, dx); val y = match(ys, sourceY, dy)
        val nx = dx + (x?.correction ?: 0f); val ny = dy + (y?.correction ?: 0f)
        val lines = buildList {
            x?.let { add(Point(it.at, minOf(source.top + ny, it.target.top)) to Point(it.at, maxOf(source.bottom + ny, it.target.bottom))) }
            y?.let { add(Point(minOf(source.left + nx, it.target.left), it.at) to Point(maxOf(source.right + nx, it.target.right), it.at)) }
        }
        return Result(nx, ny, lines)
    }
}
