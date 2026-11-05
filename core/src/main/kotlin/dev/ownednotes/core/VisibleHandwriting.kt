package dev.ownednotes.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Remaining sampled centerlines for recognition, not a reconstruction of the filled outline.
 * Subtract analytic round capsules, including transformed masks. Never join across an erased gap.
 * Slightly expand masks to keep float-rounded boundary points out of the erased region.
 */
internal object VisibleHandwriting {
    const val MAX_WORK = 250_000L
    private data class V(val x: Double, val y: Double) {
        operator fun minus(other: V) = V(x - other.x, y - other.y)
        fun dot(other: V) = x * other.x + y * other.y
    }
    private data class Span(val lo: Double, val hi: Double)
    private data class Mask(val sweep: EraserSweep, val points: List<V>)

    fun work(stroke: InkStroke): Long = maxOf(1, stroke.samples.size - 1).toLong() *
        stroke.erasures.sumOf { maxOf(1, it.points.size - 1).toLong() }

    private fun local(p: Point, sweep: EraserSweep): V {
        val basis = sweep.basis ?: return V(p.x.toDouble(), p.y.toDouble())
        val det = basis.a.toDouble() * basis.d - basis.b.toDouble() * basis.c
        require(listOf(basis.a, basis.b, basis.c, basis.d).all { it.isFinite() } && abs(det) >= 1e-8) { "Invalid eraser basis." }
        return V((basis.d * p.x.toDouble() - basis.c * p.y.toDouble()) / det,
            (basis.a * p.y.toDouble() - basis.b * p.x.toDouble()) / det)
    }

    fun fragments(stroke: InkStroke): List<List<Point>> {
        val points = stroke.samples.map { Point(it.x, it.y) }
        if (stroke.erasures.isEmpty()) return listOf(points)
        require(work(stroke) <= MAX_WORK) { "Erased ink is too complex. Select a smaller area." }
        val masks = stroke.erasures.map { sweep ->
            require(sweep.radius.isFinite() && sweep.radius > 0f && sweep.points.isNotEmpty() &&
                sweep.points.all { it.x.isFinite() && it.y.isFinite() }) { "Invalid eraser sweep." }
            Mask(sweep, sweep.points.map { local(it, sweep) })
        }
        val result = mutableListOf<List<Point>>()
        var current = mutableListOf<Point>()
        var count = 0
        fun flush() { if (current.isNotEmpty()) { result += current; current = mutableListOf() }
            require(result.size <= HandwritingInput.MAX_STROKES) { "Too many remaining ink fragments. Select a smaller area." } }
        fun add(point: Point) {
            if (current.lastOrNull() != point) { current += point; count++ }
            require(count <= HandwritingInput.MAX_POINTS) { "Too many remaining pen points. Select a smaller area." }
        }
        val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
        segments.forEach { (a, b) ->
            val removed = mutableListOf<Span>()
            masks.forEach { mask ->
                val start = local(a, mask.sweep); val end = local(b, mask.sweep)
                val magnitude = maxOf(abs(start.x), abs(start.y), abs(end.x), abs(end.y),
                    mask.points.maxOf { maxOf(abs(it.x), abs(it.y)) })
                val radius = mask.sweep.radius + .002 + magnitude * 2e-7
                if (mask.points.size == 1) circle(start, end, mask.points[0], radius)?.let(removed::add)
                else mask.points.zipWithNext().forEach { (c, d) -> capsule(start, end, c, d, radius, removed) }
            }
            val merged = mutableListOf<Span>()
            removed.sortedBy { it.lo }.forEach { span ->
                val previous = merged.lastOrNull()
                if (previous != null && span.lo <= previous.hi) merged[merged.lastIndex] = Span(previous.lo, maxOf(previous.hi, span.hi))
                else merged += span
            }
            var cursor = 0.0
            fun keep(lo: Double, hi: Double) {
                if (lo > 0.0) flush()
                fun at(t: Double) = Point((a.x + (b.x.toDouble() - a.x) * t).toFloat(), (a.y + (b.y.toDouble() - a.y) * t).toFloat())
                add(at(lo)); add(at(hi))
                if (hi < 1.0) flush()
            }
            merged.forEach { span ->
                if (span.lo > cursor) keep(cursor, span.lo)
                flush(); cursor = maxOf(cursor, span.hi)
            }
            if (cursor < 1.0) keep(cursor, 1.0)
        }
        flush()
        return result
    }

    private fun circle(a: V, b: V, center: V, radius: Double): Span? {
        val v = b - a; val q = center - a; val length2 = v.dot(v)
        if (length2 == 0.0) return if (q.dot(q) <= radius * radius) Span(0.0, 1.0) else null
        val middle = q.dot(v) / length2
        val cross = q.x * v.y - q.y * v.x
        val remaining = radius * radius - cross * cross / length2
        if (remaining < 0.0) return null
        val half = sqrt(remaining / length2)
        val lo = maxOf(0.0, middle - half); val hi = minOf(1.0, middle + half)
        return if (lo <= hi) Span(lo, hi) else null
    }

    private fun capsule(a: V, b: V, c: V, d: V, radius: Double, spans: MutableList<Span>) {
        circle(a, b, c, radius)?.let(spans::add)
        val axis = d - c; val length = hypot(axis.x, axis.y)
        if (length == 0.0) return
        circle(a, b, d, radius)?.let(spans::add)
        val u = V(axis.x / length, axis.y / length); val n = V(-u.y, u.x)
        val start = a - c; val delta = b - a
        var lo = 0.0; var hi = 1.0
        fun slab(value: Double, velocity: Double, minimum: Double, maximum: Double): Boolean {
            if (velocity == 0.0) return value in minimum..maximum
            val t1 = (minimum - value) / velocity; val t2 = (maximum - value) / velocity
            lo = maxOf(lo, minOf(t1, t2)); hi = minOf(hi, maxOf(t1, t2))
            return lo <= hi
        }
        if (slab(start.dot(u), delta.dot(u), 0.0, length) && slab(start.dot(n), delta.dot(n), -radius, radius)) spans += Span(lo, hi)
    }
}

/** Remaining native-coordinate centerlines for local PDF Ink export; erased gaps are never joined. */
fun InkStroke.visibleCenterlines(): List<List<Point>> = VisibleHandwriting.fragments(this)
