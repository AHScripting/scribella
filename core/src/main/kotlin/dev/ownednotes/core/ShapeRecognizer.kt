package dev.ownednotes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hold-to-shape: turns a finished freehand centreline into a clean line, rectangle, quadrilateral, ellipse/circle,
 * triangle, polygon of five to eight corners, or arrow. Pure and deterministic; page units in, page units out. The returned [Shape.path] is one
 * continuous polyline that starts near the stroke's first point and runs in the stroke's direction, so a morph
 * between the two reads naturally.
 */
object ShapeRecognizer {
    /** [POLYLINE] and [CURVE] come only from the shape pen's [tidy]: a stroke near no known shape, straightened or smoothed. */
    @kotlinx.serialization.Serializable
    enum class Kind { LINE, ARROW, TRIANGLE, RECTANGLE, QUADRILATERAL, POLYGON, CIRCLE, ELLIPSE, STAR, HEART, CHECK, LIGHTNING, POLYLINE, CURVE }
    data class Shape(val kind: Kind, val path: List<Point>, val closed: Boolean)

    /** Most corners a closed outline may have and still snap to straight edges (P047: pentagon to octagon). */
    const val MAX_POLYGON = 8

    /** Smallest bounding-box diagonal, in page units, that may snap; tiny marks (dots, accents) stay freehand. */
    const val MIN_SIZE = 16f
    /** Lines within this many degrees of a multiple of 45 snap onto it, the same tolerance as selection rotation. */
    const val ANGLE_SNAP_DEGREES = 4.0

    fun recognize(input: List<Point>, spacing: Float = 2f): Shape? {
        require(spacing.isFinite() && spacing > 0f) { "Invalid spacing" }
        val drawn = clean(input)
        if (drawn.size < 2 || diagonal(drawn) < MIN_SIZE) return null
        val points = closeLoop(settle(drawn, diagonal(drawn)), diagonal(drawn))
        if (points.size < 2) return null
        val diagonal = diagonal(points)
        if (diagonal < MIN_SIZE) return null
        val length = length(points)
        val gap = dist(points.first(), points.last())
        // A closed outline is at least twice its diagonal long; a line is about one diagonal.
        val closed = points.size >= 6 && gap <= .2f * diagonal && length > 1.8f * diagonal
        val geometric = if (closed) closedShape(points, diagonal) else openShape(points, diagonal)
        val shape = savedShape(points, closed, geometric) ?: geometric
        return shape?.let { it.copy(path = densify(it.path, spacing)) }
    }

    private fun clean(input: List<Point>) = input.filter { it.x.isFinite() && it.y.isFinite() }.fold(mutableListOf<Point>()) { list, p ->
        if (list.isEmpty() || dist(list.last(), p) > 1e-3f) list.add(p); list
    }

    /**
     * B32 (P195): what the hand adds around a held shape. The pen trembling in place during the hold becomes one point
     * where it rested, and a short piece that turns sharply away at either end (the nib hooking as it lands or stops)
     * is dropped. Neither adds length, corners or a stray side.
     */
    private fun settle(points: List<Point>, diagonal: Float): List<Point> {
        // The resting point: where the last few samples gather. The tremble may stray past [rest] now and then, so the
        // rest begins after the last sample that was clearly away (twice as far), at the first one back within reach.
        val rest = max(3f, .02f * diagonal)
        val tail = points.takeLast(5)
        val anchor = Point(tail.map { it.x }.average().toFloat(), tail.map { it.y }.average().toFloat())
        val away = points.indices.lastOrNull { dist(points[it], anchor) > 2 * rest } ?: return points
        val end = (away + 1..points.lastIndex).firstOrNull { dist(points[it], anchor) <= rest } ?: points.lastIndex
        if (end < 1) return points
        val still = points.subList(end, points.size)
        // A pen that trembled went back and forth; one that simply ended there passed straight through.
        val trembled = length(still) - dist(still.first(), still.last()) > rest / 4
        val rested = if (!trembled) points else points.subList(0, end) + Point(still.map { it.x }.average().toFloat(), still.map { it.y }.average().toFloat())
        return unhook(unhook(rested, diagonal).reversed(), diagonal).reversed()
    }

    /** Drops a last piece shorter than 6% of the shape that turns more than 45° away from the line before it. */
    private fun unhook(points: List<Point>, diagonal: Float): List<Point> {
        if (points.size < 6) return points
        val reach = max(3f, .06f * diagonal)
        var from = points.lastIndex; var walked = 0f
        while (from > 0 && walked < 3 * reach) { walked += dist(points[from - 1], points[from]); from-- }
        if (from == 0) return points
        val corners = simplify(points.subList(from, points.size), max(1f, .015f * diagonal))
        if (corners.size < 3) return points
        val (a, b, c) = corners.takeLast(3)
        if (dist(b, c) > reach) return points
        val turn = abs(normalize(atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble()) - atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())))
        if (Math.toDegrees(turn) <= 45.0) return points
        return points.subList(0, points.lastIndexOf(b) + 1)
    }

    /**
     * B32 (P195): an outline drawn on past its start is cut where it passes the start again, so the overlap never adds
     * a side. Measured only over the last 40% of the stroke; a stroke that never comes back stays as it is.
     */
    private fun closeLoop(points: List<Point>, diagonal: Float): List<Point> {
        if (points.size < 6) return points
        val total = length(points); var walked = 0f; var best = -1; var nearest = Float.MAX_VALUE
        for (i in 1 until points.size) {
            walked += dist(points[i - 1], points[i])
            if (walked >= .6f * total) { val d = dist(points[i], points.first()); if (d < nearest) { nearest = d; best = i } }
        }
        return if (best > 0 && nearest <= .2f * diagonal) points.subList(0, best + 1) else points
    }

    /**
     * B26 (P138): the shape pen. Hold-to-shape snaps only a stroke it is sure of and keeps odd corners as drawn; the
     * shape pen makes every stroke a clean shape. The stroke is compared with the ideal form of every known shape (a
     * rough square is compared with a true square, a rough circle with a true circle) and the closest wins. When none is
     * close, the stroke itself is tidied: straightened into clean segments, or smoothed into a curve. Null only for marks
     * too small to be shapes.
     */
    fun tidy(input: List<Point>, spacing: Float = 2f): Shape? {
        require(spacing.isFinite() && spacing > 0f) { "Invalid spacing" }
        val points = clean(input)
        if (points.size < 2) return null
        val diagonal = diagonal(points)
        if (diagonal < MIN_SIZE) return null
        val closed = points.size >= 6 && dist(points.first(), points.last()) <= .3f * diagonal && length(points) > 1.6f * diagonal
        val shape = if (closed) tidyClosed(points, diagonal) else tidyOpen(points, diagonal)
        return shape.copy(path = densify(shape.path, spacing))
    }

    /** How far a stroke may stray from an ideal shape (mean plus a share of the worst, per diagonal) and still be it. */
    const val TIDY_TOLERANCE = .1f
    /** Each corner adds this to a shape's score, so a wobbly circle stays a circle rather than an octagon. */
    private const val CORNER_COST = .004f

    /** Mean distance from the stroke to [shape], plus a third of the worst, as a share of [diagonal]. */
    private fun score(stroke: List<Point>, shape: List<Point>, diagonal: Float): Float {
        val distances = stroke.map { p -> shape.zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) } }
        return (distances.average().toFloat() + distances.max() / 3) / diagonal
    }

    private fun tidyClosed(points: List<Point>, diagonal: Float): Shape {
        val ring = points + points.first()
        val probe = if (ring.size > 160) resample(ring, 160) else ring
        // Ideal shapes (circle, square, even triangle, regular polygon, saved shape) win over the drawn corners whenever
        // they are close enough: a bad square should become a good square, even though its own corners hug it closer.
        val candidates = mutableListOf<Triple<Shape, Float, Boolean>>()
        fun consider(shape: Shape, cost: Float, ideal: Boolean = true) { candidates += Triple(shape, score(probe, shape.path + shape.path.first(), diagonal) + cost, ideal) }
        fitEllipse(ring)?.let { fit ->
            // Nearly round is round.
            val round = max(fit.rx, fit.ry) <= 1.25 * min(fit.rx, fit.ry)
            consider((if (round) fit.copy(rx = (fit.rx + fit.ry) / 2, ry = (fit.rx + fit.ry) / 2, theta = 0.0) else fit).toShape(), 0f)
        }
        for (n in 3..MAX_POLYGON) corners(points, diagonal, n)?.let { val (shape, ideal) = ideal(it); consider(shape, CORNER_COST * n, ideal) }
        bestSaved(points, true)?.let { consider(it, CORNER_COST * 2) }
        // The drawn corners themselves only when they follow the stroke closely; a blob is smoothed instead.
        val best = candidates.filter { it.third && it.second <= TIDY_TOLERANCE }.minByOrNull { it.second }?.first
            ?: candidates.filter { !it.third && it.second <= TIDY_TOLERANCE / 2 }.minByOrNull { it.second }?.first
        val shape = best ?: Shape(Kind.CURVE, smooth(points, true, diagonal), true)
        return Shape(shape.kind, align(shape.path, points), true)
    }

    private fun tidyOpen(points: List<Point>, diagonal: Float): Shape {
        val a = points.first(); val b = points.last(); val chord = dist(a, b)
        if (chord >= .5f * diagonal && maxDeviation(points, a, b) <= max(.1f * chord, 3f)) return Shape(Kind.LINE, listOf(a, snapAngle(a, b, 8.0)), false)
        listOf(.05f, .08f, .035f).firstNotNullOfOrNull { arrow(points, diagonal, it) }?.let { return it }
        val probe = if (points.size > 160) resample(points, 160) else points
        bestSaved(points, false)?.takeIf { score(probe, it.path, diagonal) <= TIDY_TOLERANCE }?.let { return it }
        // Near no known shape: clean straight segments when it is made of a few, otherwise a smooth curve.
        val corners = simplify(points, .04f * diagonal)
        // Straight only when each piece really is straight; a wave simplified to a few pieces bulges off them.
        val straight = corners.zipWithNext().all { (a, b) ->
            val from = points.indexOf(a); val to = points.indexOf(b)
            points.subList(from, to + 1).maxOf { segmentDistance(it, a, b) } <= .06f * dist(a, b) + .01f * diagonal
        }
        if (corners.size <= 7 && straight && score(probe, corners, diagonal) <= .06f) {
            return Shape(Kind.POLYLINE, corners.drop(1).fold(listOf(corners.first())) { path, p -> path + snapAngle(path.last(), p, 8.0) }, false)
        }
        return Shape(Kind.CURVE, smooth(points, false, diagonal), false)
    }

    /** The stroke's [n] most telling corners (closed), or null when no simplification gives exactly [n]. */
    private fun corners(points: List<Point>, diagonal: Float, n: Int): List<Point>? {
        var low = .005f * diagonal; var high = .4f * diagonal
        repeat(24) {
            val epsilon = (low + high) / 2
            val ring = simplifyClosed(points, epsilon)
            when {
                ring.size == n -> return ring
                ring.size > n -> low = epsilon
                else -> high = epsilon
            }
        }
        return null
    }

    /**
     * The ideal form of a drawn polygon (square or rectangle, equilateral triangle, regular polygon) and true; or its
     * own corners and false when it is none of those.
     */
    private fun ideal(corners: List<Point>): Pair<Shape, Boolean> {
        val n = corners.size
        if (n == 4) {
            val angles = corners.indices.map { i -> interior(corners[(i + 3) % 4], corners[i], corners[(i + 1) % 4]) }
            if (angles.all { abs(it - 90.0) <= 30.0 }) return rectangle(corners, square = .2f) to true
        }
        val kind = when (n) { 3 -> Kind.TRIANGLE; 4 -> Kind.QUADRILATERAL; else -> Kind.POLYGON }
        val even = regular(corners, .25f, 20.0)
        return Shape(kind, even ?: corners, true) to (even != null)
    }

    private fun interior(p: Point, c: Point, n: Point) =
        Math.toDegrees(abs(normalize(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()) - atan2((n.y - c.y).toDouble(), (n.x - c.x).toDouble()))))

    /** The closest saved shape stretched to the stroke's box, whatever its fit; null for lines and flat marks. */
    private fun bestSaved(points: List<Point>, closed: Boolean): Shape? {
        val minX = points.minOf { it.x }; val minY = points.minOf { it.y }
        val w = points.maxOf { it.x } - minX; val h = points.maxOf { it.y } - minY
        if (w < .15f * max(w, h) || h < .15f * max(w, h)) return null
        val drawn = sample(points.map { Point((it.x - minX) / w, (it.y - minY) / h) }, closed)
        val (match, fit) = saved.filter { it.closed == closed }.map { it to error(drawn, sample(it.outline, closed), closed) }.minByOrNull { it.second.first } ?: return null
        val path = (if (fit.second) match.outline.reversed() else match.outline).map { Point(minX + it.x * w, minY + it.y * h) }
        return Shape(match.kind, path, closed)
    }

    /** Evenly spaced points along the stroke, averaged with their neighbours: the same line without the shake. */
    private fun smooth(points: List<Point>, closed: Boolean, diagonal: Float): List<Point> {
        val count = (length(points) / (.015f * diagonal)).roundToInt().coerceIn(24, 240)
        var p = if (closed) resample(points + points.first(), count + 1).dropLast(1) else resample(points, count)
        val reach = 3
        repeat(3) {
            val source = p
            p = List(source.size) { i ->
                if (!closed && (i == 0 || i == source.lastIndex)) source[i] else {
                    var sx = 0f; var sy = 0f; var total = 0f
                    for (k in -reach..reach) {
                        val q = if (closed) source[((i + k) % source.size + source.size) % source.size] else source[(i + k).coerceIn(0, source.lastIndex)]
                        val weight = (reach + 1 - abs(k)).toFloat()
                        sx += q.x * weight; sy += q.y * weight; total += weight
                    }
                    Point(sx / total, sy / total)
                }
            }
        }
        return p
    }

    /**
     * B10: saved shapes are tried first. Each is an outline in a unit box (y grows downward), drawn in one stroke.
     * A stroke matches when, stretched to the same box, it runs close to the saved outline (any start point and
     * direction for closed outlines, either direction for open ones) and the plain shape does not fit clearly better.
     */
    class Saved(val kind: Kind, val closed: Boolean, val outline: List<Point>)

    val saved: List<Saved> by lazy {
        fun star(order: List<Int>, inner: Float?): List<Point> {
            val outer = List(5) { k -> val a = -PI / 2 + 2 * PI * k / 5; Point((.5 + .5 * cos(a)).toFloat(), (.5 + .5 * sin(a)).toFloat()) }
            if (inner == null) return order.map { outer[it] }
            return (0 until 10).map { k ->
                if (k % 2 == 0) outer[k / 2] else { val a = -PI / 2 + PI * k / 5; Point((.5 + inner * cos(a)).toFloat(), (.5 + inner * sin(a)).toFloat()) }
            }
        }
        val heart = List(96) { k ->
            val t = 2 * PI * k / 96
            Point((16 * sin(t).let { it * it * it }).toFloat(), (-(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))).toFloat())
        }
        listOf(
            Saved(Kind.STAR, true, unitBox(star(listOf(0, 2, 4, 1, 3), null))),
            Saved(Kind.STAR, true, unitBox(star(emptyList(), .19f))),
            Saved(Kind.HEART, true, unitBox(heart)),
            Saved(Kind.CHECK, false, listOf(Point(0f, .55f), Point(.35f, 1f), Point(1f, 0f))),
            Saved(Kind.LIGHTNING, false, unitBox(listOf(Point(.75f, 0f), Point(.15f, .55f), Point(.85f, .45f), Point(.25f, 1f)))),
        )
    }

    /** Largest mean distance, as a share of the stroke's box, at which a stroke still counts as a saved shape. */
    const val SAVED_TOLERANCE = .07f
    private const val MATCH_POINTS = 64

    private fun savedShape(points: List<Point>, closed: Boolean, geometric: Shape?): Shape? {
        val minX = points.minOf { it.x }; val minY = points.minOf { it.y }
        val w = points.maxOf { it.x } - minX; val h = points.maxOf { it.y } - minY
        if (w < .15f * max(w, h) || h < .15f * max(w, h)) return null // lines and flat marks are never saved shapes
        fun toUnit(p: Point) = Point((p.x - minX) / w, (p.y - minY) / h)
        fun toPage(p: Point) = Point(minX + p.x * w, minY + p.y * h)
        val drawn = sample(points.map(::toUnit), closed)
        val plain = geometric?.let { error(drawn, sample(it.path.map(::toUnit), it.closed), closed).first } ?: Float.MAX_VALUE
        var best: Pair<Saved, Pair<Float, Boolean>>? = null
        for (candidate in saved.filter { it.closed == closed }) {
            val fit = error(drawn, sample(candidate.outline, closed), closed)
            if (best == null || fit.first < best.second.first) best = candidate to fit
        }
        val (match, fit) = best ?: return null
        // Saved shapes come first: one wins unless the plain shape fits clearly better (a pentagram is also a five-corner polygon).
        if (fit.first > SAVED_TOLERANCE || fit.first > plain + .03f) return null
        // The saved outline itself (exact corners), placed in the stroke's box and run in the stroke's direction.
        val path = (if (fit.second) match.outline.reversed() else match.outline).map(::toPage)
        return if (closed) Shape(match.kind, align(path, points), true) else Shape(match.kind, path, false)
    }

    private fun unitBox(points: List<Point>): List<Point> {
        val minX = points.minOf { it.x }; val minY = points.minOf { it.y }
        val w = points.maxOf { it.x } - minX; val h = points.maxOf { it.y } - minY
        return points.map { Point((it.x - minX) / w, (it.y - minY) / h) }
    }
    private fun sample(path: List<Point>, closed: Boolean) =
        if (closed) resample(path + path.first(), MATCH_POINTS + 1).dropLast(1) else resample(path, MATCH_POINTS)

    /** Mean distance of the best pairing (any start on a closed outline), and whether the outline runs against the stroke. */
    private fun error(drawn: List<Point>, outline: List<Point>, closed: Boolean): Pair<Float, Boolean> {
        val n = drawn.size
        var best = Float.MAX_VALUE; var reversed = false
        for ((candidate, backwards) in listOf(outline to false, outline.reversed() to true)) {
            for (shift in if (closed) 0 until n else 0 until 1) {
                var sum = 0f
                for (i in 0 until n) { sum += dist(drawn[i], candidate[(i + shift) % n]); if (sum >= best * n) break }
                val mean = sum / n
                if (mean < best) { best = mean; reversed = backwards }
            }
        }
        return best to reversed
    }

    private fun openShape(points: List<Point>, diagonal: Float): Shape? {
        val a = points.first(); val b = points.last()
        val chord = dist(a, b)
        if (chord >= .6f * diagonal && maxDeviation(points, a, b) <= max(.08f * chord, 2.5f) && length(points) <= 1.2f * chord) {
            return Shape(Kind.LINE, listOf(a, snapAngle(a, b)), false)
        }
        return arrow(points, diagonal)
    }

    /** One-stroke arrow: tail → tip → barb, back to the tip → other barb. Barbs come out symmetric about the shaft. */
    private fun arrow(points: List<Point>, diagonal: Float, detail: Float = .05f): Shape? {
        val corners = simplify(points, detail * diagonal)
        if (corners.size != 5) return null
        val (tail, tip, barb1, tip2, barb2) = corners
        val shaft = dist(tail, tip)
        if (dist(tip, tip2) > .25f * shaft) return null
        val l1 = dist(tip, barb1); val l2 = dist(tip2, barb2)
        if (min(l1, l2) < .08f * shaft || max(l1, l2) > .7f * shaft) return null
        // Segments between barbs and the tip must be straight enough to count as strokes.
        val back = atan2((tail.y - tip.y).toDouble(), (tail.x - tip.x).toDouble())
        fun spread(barb: Point, from: Point) = normalize(atan2((barb.y - from.y).toDouble(), (barb.x - from.x).toDouble()) - back)
        val s1 = spread(barb1, tip); val s2 = spread(barb2, tip2)
        val d1 = Math.toDegrees(abs(s1)); val d2 = Math.toDegrees(abs(s2))
        if (d1 !in 10.0..75.0 || d2 !in 10.0..75.0 || s1 * s2 > 0) return null
        val straightTip = snapAngle(tail, Point((tip.x + tip2.x) / 2, (tip.y + tip2.y) / 2))
        val angle = Math.toRadians((d1 + d2) / 2)
        val barb = (l1 + l2) / 2
        val direction = atan2((tail.y - straightTip.y).toDouble(), (tail.x - straightTip.x).toDouble())
        fun barbAt(sign: Double) = Point((straightTip.x + barb * cos(direction + sign * angle)).toFloat(), (straightTip.y + barb * sin(direction + sign * angle)).toFloat())
        val first = barbAt(if (s1 > 0) 1.0 else -1.0); val second = barbAt(if (s1 > 0) -1.0 else 1.0)
        return Shape(Kind.ARROW, listOf(tail, straightTip, first, straightTip, second), false)
    }

    private fun closedShape(points: List<Point>, diagonal: Float): Shape? {
        val ring = points + points.first()
        val ellipse = fitEllipse(ring)
        val ellipseError = ellipse?.let { meanDistance(ring, it.ring(96)) / diagonal } ?: Float.MAX_VALUE
        // B32 (P195): each corner count's outline and how far the stroke strays from it. A missed corner leaves part
        // of the stroke far off a side, which wobble and rounding never do.
        val fits = (3..MAX_POLYGON).mapNotNull { n ->
            corners(points, diagonal, n)?.let { refine(points, it, diagonal) }?.takeIf { it.size >= 3 }?.let { polygon ->
                val outline = polygon + polygon.first()
                val distances = deviations(ring, outline)
                Triple(polygon, trimmedMean(distances) / diagonal, distances[distances.size * 19 / 20] <= .15f * length(outline) / polygon.size)
            }
        }
        // The fewest corners after which another corner no longer halves the error: a hexagon's sixth corner still
        // does, a wobble's extra corner does not.
        val chosen = fits.indices.firstOrNull { i ->
            val (_, error, tight) = fits[i]
            error <= POLYGON_FIT && tight && (i == fits.lastIndex || fits[i + 1].second >= .5f * error)
        }?.let { fits[it] }
        val polygon = chosen?.first; val polygonError = chosen?.second ?: Float.MAX_VALUE
        // A curve fits a polygon too, so the polygon must fit clearly better than an ellipse. Real corners also gather
        // the stroke's turning at a few points, where a curve turns evenly, which settles five or more corners.
        val curve = ellipse?.let { fitError(ring, it.ring(96), diagonal) } ?: Float.MAX_VALUE
        val polygonWins = polygon != null && polygonError < curve &&
            (polygonError <= .5f * curve || polygon.size > 4 && cornerShare(points, polygon) >= SHARP_CORNERS)
        val shape = when {
            polygonWins -> polygonShape(polygon!!)
            ellipse != null && ellipseError <= .045f -> ellipse.toShape()
            else -> null
        } ?: return null
        return Shape(shape.kind, align(shape.path, points), true)
    }

    /**
     * B32 (P195): each side as the straight line through the middle of its stretch of stroke, and each corner where
     * neighbouring sides meet, so a rounded or overshot corner lands where the hand aimed. Sides that meet nearly
     * straight (the stroke started partway along a side) are one side.
     */
    private fun refine(points: List<Point>, corners: List<Point>, diagonal: Float): List<Point> {
        val n = corners.size; val size = points.size
        val at = corners.map { c -> points.indices.minBy { dist(points[it], c) } }
        if (at.zipWithNext().any { (a, b) -> b <= a }) return corners
        val sides = (0 until n).map { i ->
            val from = at[i]; val to = if (i + 1 < n) at[i + 1] else at[0] + size
            val trim = (to - from) / 5
            if (to - trim - (from + trim) < 2) return corners
            line((from + trim..to - trim).map { points[it % size] })
        }
        return (0 until n).mapNotNull { i ->
            val a = sides[(i + n - 1) % n]; val b = sides[i]
            val cross = a.dx * b.dy - a.dy * b.dx
            if (abs(cross) < sin(Math.toRadians(12.0))) return@mapNotNull null
            val t = ((b.x - a.x) * b.dy - (b.y - a.y) * b.dx) / cross
            Point((a.x + t * a.dx).toFloat(), (a.y + t * a.dy).toFloat()).takeIf { dist(it, corners[i]) <= .25f * diagonal } ?: corners[i]
        }
    }

    /** A straight line: a point on it and a unit direction. */
    private class Line(val x: Double, val y: Double, val dx: Double, val dy: Double)
    /** The total-least-squares line through [points]. */
    private fun line(points: List<Point>): Line {
        val cx = points.sumOf { it.x.toDouble() } / points.size; val cy = points.sumOf { it.y.toDouble() } / points.size
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        points.forEach { val x = it.x - cx; val y = it.y - cy; xx += x * x; yy += y * y; xy += x * y }
        val angle = .5 * atan2(2 * xy, xx - yy)
        return Line(cx, cy, cos(angle), sin(angle))
    }

    /** Manual marker geometry uses our existing least-squares line fit rather than the nib endpoints.
     * All samples must stay in a narrow band and follow a short, open path. Hold-to-shape is unchanged.
     */
    internal fun fittedStraightAxis(input:List<Point>):Pair<Point,Point>? {
        if(input.size !in 2..512 || input.any {!it.x.isFinite() || !it.y.isFinite() || abs(it.x)>1000000 || abs(it.y)>1000000})return null
        val fit=line(input)
        val projected=input.map {(it.x-fit.x)*fit.dx+(it.y-fit.y)*fit.dy}
        val lo=projected.min();val hi=projected.max();val span=hi-lo
        if(span<MIN_SIZE || length(input)>1.35*span || input.any {abs((it.x-fit.x)*fit.dy-(it.y-fit.y)*fit.dx)>span*.12})return null
        return Point((fit.x+fit.dx*lo).toFloat(),(fit.y+fit.dy*lo).toFloat()) to Point((fit.x+fit.dx*hi).toFloat(),(fit.y+fit.dy*hi).toFloat())
    }

    /**
     * Distance from a closed stroke to [outline], per diagonal: the mean of the nearest 80% of its points, so the
     * rounding of a corner does not count against the corner.
     */
    private fun fitError(ring: List<Point>, outline: List<Point>, diagonal: Float) = trimmedMean(deviations(ring, outline)) / diagonal
    /** Each stroke point's distance from [outline], smallest first. */
    private fun deviations(ring: List<Point>, outline: List<Point>) = ring.map { p -> outline.zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) } }.sorted()
    private fun trimmedMean(sorted: List<Float>) = sorted.subList(0, max(1, sorted.size * 4 / 5)).average().toFloat()

    /** Mean distance, per diagonal, at which drawn corners still count as a polygon. */
    private const val POLYGON_FIT = .035f
    /** Share of a closed stroke's turning that must happen at its corners for them to be real corners. */
    private const val SHARP_CORNERS = .85f

    /**
     * B32 (P195): the share of a closed stroke's turning that happens near [corners]: about 1 for a polygon, however
     * wobbly or rounded, and about half for a circle or ellipse, which turns evenly all the way round.
     */
    private fun cornerShare(points: List<Point>, corners: List<Point>): Float {
        val m = 120
        val ring = resample(points + points.first(), m + 1).dropLast(1)
        val headings = List(m) { i -> val a = ring[i]; val b = ring[(i + 1) % m]; atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) }
        val turns = List(m) { i -> normalize(headings[i] - headings[(i + m - 1) % m]) }
        val total = abs(turns.sum())
        if (total < PI) return 0f
        val reach = max(2, m / (4 * corners.size))
        val claimed = BooleanArray(m)
        // Signed sums, so wobble along a straight side cancels out instead of looking like turning; each step counts once.
        val atCorners = corners.sumOf { c ->
            val i = ring.indices.minBy { dist(ring[it], c) }
            abs((-reach..reach).map { (i + it + m) % m }.filter { !claimed[it] }.onEach { claimed[it] = true }.sumOf { turns[it] })
        }
        return (atCorners / total).toFloat().coerceAtMost(1f)
    }

    private fun polygonShape(vertices: List<Point>): Shape {
        if (vertices.size == 3) return Shape(Kind.TRIANGLE, regular(vertices) ?: vertices, true)
        if (vertices.size > 4) return Shape(Kind.POLYGON, regular(vertices) ?: vertices, true)
        val angles = vertices.indices.map { i ->
            val p = vertices[(i + 3) % 4]; val c = vertices[i]; val n = vertices[(i + 1) % 4]
            Math.toDegrees(abs(normalize(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()) - atan2((n.y - c.y).toDouble(), (n.x - c.x).toDouble()))))
        }
        if (angles.any { abs(it - 90.0) > 20.0 }) return Shape(Kind.QUADRILATERAL, vertices, true)
        return rectangle(vertices)
    }

    /** The rectangle through four corners; with [square], sides within that share of each other become a square. */
    private fun rectangle(vertices: List<Point>, square: Float? = null): Shape {
        // Orientation: mean edge direction folded onto a quarter turn, then snapped to the page axes when close.
        var sx = 0.0; var sy = 0.0
        for (i in 0 until 4) {
            val a = vertices[i]; val b = vertices[(i + 1) % 4]
            val theta = 4 * atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val weight = dist(a, b).toDouble(); sx += weight * cos(theta); sy += weight * sin(theta)
        }
        var theta = atan2(sy, sx) / 4
        if (abs(Math.toDegrees(theta)) <= ANGLE_SNAP_DEGREES * 2) theta = 0.0
        val c = cos(theta); val s = sin(theta)
        val us = vertices.map { it.x * c + it.y * s }.sorted(); val vs = vertices.map { -it.x * s + it.y * c }.sorted()
        var left = (us[0] + us[1]) / 2; var right = (us[2] + us[3]) / 2
        var top = (vs[0] + vs[1]) / 2; var bottom = (vs[2] + vs[3]) / 2
        if (square != null && abs((right - left) - (bottom - top)) <= square * max(right - left, bottom - top)) {
            val half = ((right - left) + (bottom - top)) / 4; val cu = (left + right) / 2; val cv = (top + bottom) / 2
            left = cu - half; right = cu + half; top = cv - half; bottom = cv + half
        }
        fun corner(u: Double, v: Double) = Point((u * c - v * s).toFloat(), (u * s + v * c).toFloat())
        return Shape(Kind.RECTANGLE, listOf(corner(left, top), corner(right, top), corner(right, bottom), corner(left, bottom)), true)
    }

    /**
     * P047: a drawn polygon whose sides and corners are nearly equal becomes the regular polygon with the same centre,
     * size and rotation; the rotation settles onto an upright pose (flat base, or a corner straight down) when close.
     * Null keeps the corners as drawn.
     */
    private fun regular(vertices: List<Point>, sideTolerance: Float = .15f, angleTolerance: Double = 15.0): List<Point>? {
        val n = vertices.size
        val sides = vertices.indices.map { dist(vertices[it], vertices[(it + 1) % n]) }
        val side = sides.average().toFloat()
        if (sides.any { abs(it - side) > sideTolerance * side }) return null
        val expected = 180.0 * (n - 2) / n
        val turnsEqual = vertices.indices.all { i ->
            val p = vertices[(i + n - 1) % n]; val c = vertices[i]; val q = vertices[(i + 1) % n]
            val interior = Math.toDegrees(abs(normalize(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()) - atan2((q.y - c.y).toDouble(), (q.x - c.x).toDouble()))))
            abs(interior - expected) <= angleTolerance
        }
        if (!turnsEqual) return null
        val cx = vertices.sumOf { it.x.toDouble() } / n; val cy = vertices.sumOf { it.y.toDouble() } / n
        val radius = vertices.sumOf { hypot(it.x - cx, it.y - cy) } / n
        // Rotation modulo one corner step: the circular mean of n × each corner's angle.
        var sx = 0.0; var sy = 0.0
        vertices.forEach { val a = n * atan2(it.y - cy, it.x - cx); sx += cos(a); sy += sin(a) }
        var phase = atan2(sy, sx) / n
        // Upright poses sit every half step from straight down (page y grows downward).
        val step = PI / n
        val upright = PI / 2 + ((phase - PI / 2) / step).roundToInt() * step
        if (abs(phase - upright) <= min(Math.toRadians(ANGLE_SNAP_DEGREES * 2), step / 3)) phase = upright
        return List(n) { k -> val a = phase + 2 * PI * k / n; Point((cx + radius * cos(a)).toFloat(), (cy + radius * sin(a)).toFloat()) }
    }

    private data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val theta: Double) {
        val circle get() = max(rx, ry) <= 1.12 * min(rx, ry)
        fun at(t: Double): Point {
            val (a, b, angle) = if (circle) Triple((rx + ry) / 2, (rx + ry) / 2, 0.0) else Triple(rx, ry, theta)
            val x = a * cos(t); val y = b * sin(t)
            return Point((cx + x * cos(angle) - y * sin(angle)).toFloat(), (cy + x * sin(angle) + y * cos(angle)).toFloat())
        }
        fun ring(count: Int) = List(count + 1) { at(2 * PI * it / count) }
        fun toShape() = Shape(if (circle) Kind.CIRCLE else Kind.ELLIPSE, ring(96).dropLast(1), true)
    }

    /** Moment fit: centroid and principal axes of the outline weighted by arc length, then radii from mean projections. */
    private fun fitEllipse(ring: List<Point>): Ellipse? {
        var total = 0.0; var cx = 0.0; var cy = 0.0
        ring.zipWithNext { a, b -> val w = dist(a, b).toDouble(); total += w; cx += w * (a.x + b.x) / 2; cy += w * (a.y + b.y) / 2 }
        if (total <= 0) return null
        cx /= total; cy /= total
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        ring.zipWithNext { a, b ->
            val w = dist(a, b).toDouble(); val x = (a.x + b.x) / 2 - cx; val y = (a.y + b.y) / 2 - cy
            xx += w * x * x; yy += w * y * y; xy += w * x * y
        }
        var theta = .5 * atan2(2 * xy, xx - yy)
        if (abs(Math.toDegrees(theta)) <= 8.0) theta = 0.0
        else if (abs(abs(Math.toDegrees(theta)) - 90.0) <= 8.0) theta = PI / 2
        val c = cos(theta); val s = sin(theta)
        // Along a circle's perimeter the variance per axis is r²/2; refine() then corrects ellipse eccentricity.
        val uu = (xx * c * c + 2 * xy * c * s + yy * s * s) / total
        val vv = (xx * s * s - 2 * xy * c * s + yy * c * c) / total
        val rx = sqrt(2 * uu); val ry = sqrt(2 * vv)
        if (!(rx > 0 && ry > 0) || max(rx, ry) > 6 * min(rx, ry)) return null
        return refine(ring, Ellipse(cx, cy, rx, ry, theta))
    }

    /** Fixed-point passes that scale each radius until the outline's normalized radius averages 1 along that axis. */
    private fun refine(ring: List<Point>, start: Ellipse): Ellipse {
        var e = start
        repeat(4) {
            val c = cos(e.theta); val s = sin(e.theta)
            var su = 0.0; var sv = 0.0; var nu = 0.0; var nv = 0.0
            ring.forEach { p ->
                val x = p.x - e.cx; val y = p.y - e.cy
                val u = x * c + y * s; val v = -x * s + y * c
                val k = hypot(u / e.rx, v / e.ry)
                if (k > 0) {
                    val wu = (u / e.rx / k).let { it * it }; val wv = (v / e.ry / k).let { it * it }
                    su += wu * k; nu += wu; sv += wv * k; nv += wv
                }
            }
            if (nu > 0 && nv > 0) e = e.copy(rx = e.rx * su / nu, ry = e.ry * sv / nv)
        }
        return e
    }

    /** Rotates a closed vertex/sample list to start nearest the stroke start and to run in the stroke's direction. */
    private fun align(path: List<Point>, stroke: List<Point>): List<Point> {
        val oriented = if ((signedArea(path) >= 0) == (signedArea(stroke) >= 0)) path else path.reversed()
        val start = oriented.indices.minBy { dist(oriented[it], stroke.first()) }
        return oriented.drop(start) + oriented.take(start) + oriented[start]
    }

    /** Hold detector: the pen counts as held once it has stayed within [radius] of an anchor for [holdMs]. */
    class HoldDetector(private val radius: Float, private val holdMs: Long) {
        init { require(radius.isFinite() && radius > 0f && holdMs > 0) { "Invalid hold settings" } }
        private var anchor: Point? = null
        private var anchorTime = 0L
        private var consumed = false
        fun reset() { anchor = null; consumed = false }
        /** Feed every centreline sample in time order. */
        fun add(point: Point, timeMs: Long) {
            val current = anchor
            if (current == null || dist(current, point) > radius) { anchor = point; anchorTime = timeMs; consumed = false }
        }
        /** Time left until a hold, measured at [nowMs] (the pen may report no events while perfectly still). Null when idle. */
        fun remaining(nowMs: Long): Long? = anchor?.takeIf { !consumed }?.let { max(0L, holdMs - (nowMs - anchorTime)) }
        fun held(nowMs: Long) = remaining(nowMs) == 0L
        /** This hold was used (or refused); the next one needs the pen to move first. */
        fun consume() { consumed = true }
    }

    /** Resamples both paths to the same count by arc length and interpolates; t=0 is [from], t=1 is [to]. */
    fun morph(from: List<Point>, to: List<Point>, t: Float, count: Int = 128): List<Point> {
        require(from.isNotEmpty() && to.isNotEmpty() && count >= 2)
        val a = resample(from, count); val b = resample(to, count)
        val k = t.coerceIn(0f, 1f)
        return List(count) { Point(a[it].x + (b[it].x - a[it].x) * k, a[it].y + (b[it].y - a[it].y) * k) }
    }

    fun resample(path: List<Point>, count: Int): List<Point> {
        require(path.isNotEmpty() && count >= 2)
        val total = length(path)
        if (path.size == 1 || total == 0f) return List(count) { path.first() }
        val result = ArrayList<Point>(count)
        var segment = 0; var walked = 0f
        for (i in 0 until count) {
            val target = total * i / (count - 1)
            while (segment < path.size - 2 && walked + dist(path[segment], path[segment + 1]) < target) { walked += dist(path[segment], path[segment + 1]); segment++ }
            val a = path[segment]; val b = path[segment + 1]; val d = dist(a, b)
            val f = if (d == 0f) 0f else ((target - walked) / d).coerceIn(0f, 1f)
            result.add(Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
        return result
    }

    /** Inserts points so no step exceeds [spacing]; every corner stays exactly in place. */
    fun densify(path: List<Point>, spacing: Float): List<Point> {
        if (path.size < 2) return path
        val result = mutableListOf(path.first())
        path.zipWithNext { a, b ->
            val steps = max(1, (dist(a, b) / spacing).roundToInt().coerceAtMost(10_000))
            for (i in 1..steps) result.add(if (i == steps) b else Point(a.x + (b.x - a.x) * i / steps, a.y + (b.y - a.y) * i / steps))
        }
        return result
    }

    private fun snapAngle(a: Point, b: Point, tolerance: Double = ANGLE_SNAP_DEGREES): Point {
        val degrees = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
        val nearest = (degrees / 45.0).roundToInt() * 45.0
        if (abs(degrees - nearest) > tolerance) return b
        val r = dist(a, b).toDouble(); val radians = Math.toRadians(nearest)
        // Exact axes, without trig noise, so snapped lines stay perfectly horizontal or vertical.
        val (c, s) = when (((nearest % 360) + 360) % 360) { 0.0 -> 1.0 to 0.0; 90.0 -> 0.0 to 1.0; 180.0 -> -1.0 to 0.0; 270.0 -> 0.0 to -1.0; else -> cos(radians) to sin(radians) }
        return Point((a.x + r * c).toFloat(), (a.y + r * s).toFloat())
    }

    private fun normalize(angle: Double): Double { var a = angle; while (a > PI) a -= 2 * PI; while (a < -PI) a += 2 * PI; return a }
    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun length(points: List<Point>) = points.zipWithNext().fold(0f) { sum, (a, b) -> sum + dist(a, b) }
    private fun diagonal(points: List<Point>) = hypot(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    private fun signedArea(points: List<Point>) = points.indices.sumOf { i -> val a = points[i]; val b = points[(i + 1) % points.size]; a.x.toDouble() * b.y - b.x.toDouble() * a.y }

    private fun segmentDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val l = dx * dx + dy * dy
        val f = if (l == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0f, 1f)
        return hypot(a.x + f * dx - p.x, a.y + f * dy - p.y)
    }
    private fun maxDeviation(points: List<Point>, a: Point, b: Point) = points.maxOf { segmentDistance(it, a, b) }
    private fun meanDistance(points: List<Point>, polyline: List<Point>): Float =
        points.map { p -> polyline.zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) } }.average().toFloat()

    /** Ramer–Douglas–Peucker on an open polyline. */
    fun simplify(points: List<Point>, epsilon: Float): List<Point> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size); keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque(listOf(0 to points.lastIndex))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var index = -1; var worst = epsilon
            for (i in start + 1 until end) { val d = segmentDistance(points[i], points[start], points[end]); if (d > worst) { worst = d; index = i } }
            if (index >= 0) { keep[index] = true; stack.add(start to index); stack.add(index to end) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** RDP on a closed ring: split at the point farthest from the start, so the seam never hides a corner. */
    private fun simplifyClosed(points: List<Point>, epsilon: Float): List<Point> {
        val far = points.indices.maxBy { dist(points[it], points.first()) }
        val first = simplify(points.subList(0, far + 1), epsilon)
        val second = simplify(points.subList(far, points.size) + points.first(), epsilon)
        var ring = (first + second.drop(1)).dropLast(1)
        // The seam point itself is only a corner when the turn there is real.
        var changed = true
        while (changed && ring.size > 3) {
            changed = false
            for (i in ring.indices) {
                val p = ring[(i + ring.size - 1) % ring.size]; val n = ring[(i + 1) % ring.size]
                if (segmentDistance(ring[i], p, n) <= epsilon) { ring = ring.filterIndexed { j, _ -> j != i }; changed = true; break }
            }
        }
        return ring
    }
}
