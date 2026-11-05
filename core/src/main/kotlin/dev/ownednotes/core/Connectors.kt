package dev.ownednotes.core

import dev.ownednotes.core.ShapeRecognizer.Kind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * F059: a connector is an ordinary straight line or arrow shape whose ends sit exactly on two closed shapes' outlines,
 * on the line between their centres. P550: a photo's or text box's frame works as an end too. P552: an elbow connector
 * is an ordinary polyline that turns two right angles between the ends, and is recognised only if it traces that exact path. It is recognised from that geometry and has no format of its own, so older
 * readers see a plain line. A line drawn by hand is never exact enough to become one, and a connector moved by
 * itself simply stops being one.
 */
object Connectors {
    data class Link(val connector: String, val from: String, val to: String)
    private const val TOLERANCE = 1f
    /** Shortest connector, in page units; shapes closer than this (or overlapping) are not connected. */
    const val MIN_LENGTH = 8f
    /** How far past an end the elbow's level ray is aimed; any distance beyond the page works. */
    private const val REACH = 10_000f
    private val OPEN = setOf(Kind.LINE, Kind.ARROW, Kind.CHECK, Kind.POLYLINE, Kind.CURVE)

    /** A closed shape a connector can end on. */
    fun isEndpoint(stroke: InkStroke): Boolean {
        val kind = stroke.shapeKind ?: return false
        if (kind in OPEN || stroke.samples.size < 3 || stroke.isHighlighter) return false
        val a = stroke.samples.first(); val b = stroke.samples.last()
        return hypot(a.x - b.x, a.y - b.y) <= TOLERANCE
    }

    /** P550: something a connector can end on, by its id and closed outline: a closed shape, or a photo's or text box's frame. */
    class End(val id: String, val outline: List<Point>)
    fun end(stroke: InkStroke): End? = if (isEndpoint(stroke)) End(stroke.id, stroke.samples.map { Point(it.x, it.y) }) else null
    fun end(image: ImageObject): End = End(image.id, frame(image.placement))
    fun end(box: TextBox): End = End(box.id, frame(box.placement))
    private fun frame(placement: ImagePlacement) = placement.corners().let { it + it.first() }
    /** Every end on [page], shapes first in drawing order, then photos, then text boxes. */
    fun ends(page: Page): List<End> = page.strokes.mapNotNull(::end) + page.images.map(::end) + page.textBoxes.map(::end)

    private fun centre(end: End): Point {
        val xs = end.outline.map { it.x }; val ys = end.outline.map { it.y }
        return Point((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
    }

    /** P557: [ends] in the order to join them one after another: left to right when they spread wider than tall, else top to bottom. */
    fun chain(ends: List<End>): List<End> {
        if (ends.size < 2) return ends
        val centres = ends.map(::centre)
        val wide = centres.maxOf { it.x } - centres.minOf { it.x } >= centres.maxOf { it.y } - centres.minOf { it.y }
        val order = if (wide) compareBy<Int>({ centres[it].x }, { centres[it].y }) else compareBy<Int>({ centres[it].y }, { centres[it].x })
        return ends.indices.sortedWith(order).map { ends[it] }
    }

    /** Where a ray from the end's centre toward [toward] first crosses its outline. */
    private fun boundary(shape: End, toward: Point): Point? {
        val c = centre(shape)
        val dx = toward.x - c.x; val dy = toward.y - c.y
        var best = Float.MAX_VALUE
        shape.outline.zipWithNext { a, b ->
            val ex = b.x - a.x; val ey = b.y - a.y
            val denominator = dx * ey - dy * ex
            if (abs(denominator) < 1e-9f) return@zipWithNext
            val t = ((a.x - c.x) * ey - (a.y - c.y) * ex) / denominator
            val u = ((a.x - c.x) * dy - (a.y - c.y) * dx) / denominator
            if (t > 1e-4f && u in 0f..1f && t < best) best = t
        }
        return if (best == Float.MAX_VALUE) null else Point(c.x + dx * best, c.y + dy * best)
    }

    /** The connector's two ends, from [from]'s outline to [to]'s, or null if the shapes overlap or touch. */
    fun route(from: InkStroke, to: InkStroke): Pair<Point, Point>? = route(end(from) ?: return null, end(to) ?: return null)
    fun route(from: End, to: End): Pair<Point, Point>? {
        if (from.id == to.id || from.outline.size < 3 || to.outline.size < 3) return null
        val ca = centre(from); val cb = centre(to)
        val dx = cb.x - ca.x; val dy = cb.y - ca.y
        val span = hypot(dx, dy)
        if (span < 1f) return null
        val p = boundary(from, cb) ?: return null
        val q = boundary(to, ca) ?: return null
        return if (((q.x - p.x) * dx + (q.y - p.y) * dy) / span < MIN_LENGTH) null else p to q
    }

    /** The connector's drawn path: a straight line, or an arrow whose head points at [to]. */
    fun path(kind: Kind, from: Point, to: Point, width: Float): List<Point> {
        require(kind == Kind.LINE || kind == Kind.ARROW) { "A connector is a line or an arrow" }
        if (kind == Kind.LINE) return ShapeRecognizer.densify(listOf(from, to), 2f)
        val length = hypot(to.x - from.x, to.y - from.y)
        val ux = (to.x - from.x) / length; val uy = (to.y - from.y) / length
        val head = minOf(length * .3f, maxOf(12f, width * 4f)); val side = head * .9f
        val back = Point(to.x - ux * head, to.y - uy * head)
        val first = Point(back.x - uy * side, back.y + ux * side); val second = Point(back.x + uy * side, back.y - ux * side)
        return ShapeRecognizer.densify(listOf(from, to, first, to, second), 2f)
    }

    /**
     * P552: an elbow connector's corners: out of [from]'s side level with its centre, across in two right angles, and into
     * the facing side of [to], level with its centre; an arrow adds its head at [to]. Null if they overlap along that way.
     */
    fun elbow(from: End, to: End, arrow: Boolean, width: Float): List<Point>? {
        if (from.id == to.id || from.outline.size < 3 || to.outline.size < 3) return null
        val ca = centre(from); val cb = centre(to)
        val across = abs(cb.x - ca.x) >= abs(cb.y - ca.y)
        val s = if (across) sign(cb.x - ca.x) else sign(cb.y - ca.y)
        if (s == 0f) return null
        val p = boundary(from, if (across) Point(ca.x + s * REACH, ca.y) else Point(ca.x, ca.y + s * REACH)) ?: return null
        val q = boundary(to, if (across) Point(cb.x - s * REACH, cb.y) else Point(cb.x, cb.y - s * REACH)) ?: return null
        if ((if (across) q.x - p.x else q.y - p.y) * s < MIN_LENGTH) return null
        val bends = if (across) ((p.x + q.x) / 2).let { listOf(Point(it, p.y), Point(it, q.y)) } else ((p.y + q.y) / 2).let { listOf(Point(p.x, it), Point(q.x, it)) }
        val line = listOf(p) + bends + q
        if (!arrow) return line
        val last = bends.last(); val length = hypot(q.x - last.x, q.y - last.y)
        val ux = (q.x - last.x) / length; val uy = (q.y - last.y) / length
        val head = minOf(length * .6f, maxOf(12f, width * 4f)); val side = head * .9f
        val back = Point(q.x - ux * head, q.y - uy * head)
        return line + listOf(Point(back.x - uy * side, back.y + ux * side), q, Point(back.x + uy * side, back.y - ux * side))
    }
    /**
     * P554: the middle of the connector between [from] and [to] (halfway between where it leaves and where it arrives,
     * which is halfway along a straight line or an elbow) and the way the line runs there. Null where it cannot be drawn.
     */
    fun middle(from: End, to: End, elbow: Boolean): Pair<Point, Point>? {
        if (!elbow) return route(from, to)?.let { (p, q) -> Point((p.x + q.x) / 2, (p.y + q.y) / 2) to Point(q.x - p.x, q.y - p.y) }
        val c = elbow(from, to, false, 1f) ?: return null
        val along = Point(c[2].x - c[1].x, c[2].y - c[1].y).takeIf { it.x != 0f || it.y != 0f } ?: Point(c[1].x - c[0].x, c[1].y - c[0].y)
        return Point((c[0].x + c[3].x) / 2, (c[0].y + c[3].y) / 2) to along
    }

    /**
     * Where a label [width] by [height] sits: by the line's middle, above a mostly level line and to the right of a mostly
     * upright one, with its nearest corner [gap] clear of the line at any slant.
     */
    fun labelPlacement(from: End, to: End, elbow: Boolean, width: Float, height: Float, gap: Float): ImagePlacement? {
        val (m, along) = middle(from, to, elbow) ?: return null
        val length = hypot(along.x, along.y).takeIf { it > 0f } ?: return null
        var nx = -along.y / length; var ny = along.x / length
        if (if (abs(along.x) >= abs(along.y)) ny > 0f else nx < 0f) { nx = -nx; ny = -ny }
        val reach = gap + width / 2 * abs(nx) + height / 2 * abs(ny)
        return ImagePlacement(m.x + nx * reach - width / 2, m.y + ny * reach - height / 2, width, height)
    }

    /** P554: the connector's label: the one text box grouped with it and nothing else. Connect makes that group. */
    fun label(page: Page, connector: String): TextBox? {
        val group = page.strokes.firstOrNull { it.id == connector }?.group ?: return null
        if (page.strokes.count { it.group == group } != 1 || page.images.any { it.group == group }) return null
        return page.textBoxes.singleOrNull { it.group == group }
    }

    /** Whether [samples] run exactly along [corners]: same ends, every sample on the path and every corner reached. */
    private fun traces(samples: List<Sample>, corners: List<Point>): Boolean {
        if (samples.size < 2 || !close(Point(samples.first().x, samples.first().y), corners.first()) || !close(Point(samples.last().x, samples.last().y), corners.last())) return false
        fun onPath(x: Float, y: Float) = corners.zipWithNext().any { (a, b) ->
            val ex = b.x - a.x; val ey = b.y - a.y; val l = ex * ex + ey * ey
            val k = if (l == 0f) 0f else (((x - a.x) * ex + (y - a.y) * ey) / l).coerceIn(0f, 1f)
            hypot(a.x + ex * k - x, a.y + ey * k - y) <= TOLERANCE
        }
        return samples.all { onPath(it.x, it.y) } && corners.all { c -> samples.any { close(Point(it.x, it.y), c) } }
    }
    /** A connector found on a page, with how it is drawn. */
    class Found(val link: Link, val elbow: Boolean, val arrow: Boolean)
    /** P555: the stroke [id] on [page] as a connector, or null when it is not one. */
    fun connector(page: Page, id: String): Found? = found(page).firstOrNull { it.link.connector == id }

    private fun close(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y) <= TOLERANCE
    private fun near(shape: End, p: Point): Boolean {
        val xs = shape.outline.map { it.x }; val ys = shape.outline.map { it.y }
        return p.x >= xs.min() - TOLERANCE && p.x <= xs.max() + TOLERANCE && p.y >= ys.min() - TOLERANCE && p.y <= ys.max() + TOLERANCE
    }

    /** Every connector on [page] and the two shapes (or photos or text boxes) it joins. */
    fun links(page: Page): List<Link> = found(page).map { it.link }
    private fun found(page: Page): List<Found> {
        val shapes = ends(page)
        if (shapes.size < 2) return emptyList()
        return page.strokes.mapNotNull { stroke ->
            if (stroke.shapeKind == Kind.POLYLINE && stroke.samples.size >= 2 && !stroke.isHighlighter) {
                val start = stroke.samples.first().let { Point(it.x, it.y) }
                for (a in shapes) if (near(a, start)) for (b in shapes) if (b !== a) for (arrow in listOf(false, true)) {
                    val corners = elbow(a, b, arrow, stroke.width) ?: continue
                    if (traces(stroke.samples, corners)) return@mapNotNull Found(Link(stroke.id, a.id, b.id), elbow = true, arrow = arrow)
                }
                return@mapNotNull null
            }
            if (stroke.shapeKind != Kind.LINE && stroke.shapeKind != Kind.ARROW) return@mapNotNull null
            // A labelled connector is grouped with its label alone; its ends are read as if it were ungrouped.
            val alone = stroke.group?.let { g -> page.strokes.count { it.group == g } == 1 && page.images.none { it.group == g } } ?: false
            val ends = ShapePoints.from(if (alone) stroke.copy(group = null) else stroke)?.handles?.takeIf { it.size == 2 } ?: return@mapNotNull null
            val tail = ends[0]; val tip = ends[1]
            for (a in shapes) if (near(a, tail)) for (b in shapes) if (b !== a && near(b, tip)) {
                val route = route(a, b) ?: continue
                if (close(route.first, tail) && close(route.second, tip)) return@mapNotNull Found(Link(stroke.id, a.id, b.id), elbow = false, arrow = stroke.shapeKind == Kind.ARROW)
            }
            null
        }
    }

    /**
     * After an edit, connectors whose shapes moved, resized or were reshaped follow them, and connectors whose shape was
     * removed go too. A connector that was itself changed, or whose shapes now overlap, is left as it is.
     * [redraw] gives the connector its new path and outlines, keeping its id and style; null leaves it unchanged.
     */
    fun follow(before: Page, after: Page, redraw: (InkStroke, List<Point>) -> InkStroke?): Page {
        if (before === after) return after
        val now = after.strokes.associateBy { it.id }
        val photos = after.images.associateBy { it.id }; val boxes = after.textBoxes.associateBy { it.id }
        val changed = before.strokes.filter { isEndpoint(it) && now[it.id] !== it }.mapTo(HashSet()) { it.id }
        before.images.filterTo(ArrayList()) { photos[it.id] !== it }.mapTo(changed) { it.id }
        before.textBoxes.filterTo(ArrayList()) { boxes[it.id] !== it }.mapTo(changed) { it.id }
        if (changed.isEmpty()) return after
        // An end that is still there but can no longer take a connector (a shape redrawn open) leaves it alone.
        fun endNow(id: String): Pair<Boolean, End?> = now[id]?.let { true to end(it) } ?: photos[id]?.let { true to end(it) } ?: boxes[id]?.let { true to end(it) } ?: (false to null)
        val replaced = HashMap<String, InkStroke?>()
        // P554: how far each rerouted connector's middle moved, so its label keeps its place beside it.
        val shifts = HashMap<String, (ImagePlacement) -> ImagePlacement>()
        val endsBefore by lazy { ends(before).associateBy { it.id } }
        fun shift(link: Link, elbow: Boolean, a: End, b: End) {
            if (label(before, link.connector) == null) return
            val was = middle(endsBefore[link.from] ?: return, endsBefore[link.to] ?: return, elbow)?.first ?: return
            val now = middle(a, b, elbow)?.first ?: return
            val gap = 4f + before.strokes.first { it.id == link.connector }.width
            shifts[link.connector] = { at ->
                // A label still where Connect put it is placed afresh, clear of the line at its new slant; one moved by hand keeps its offset.
                val placed = labelPlacement(endsBefore.getValue(link.from), endsBefore.getValue(link.to), elbow, at.width, at.height, gap)
                val fresh = if (placed != null && abs(placed.x - at.x) < .5f && abs(placed.y - at.y) < .5f) labelPlacement(a, b, elbow, at.width, at.height, gap) else null
                fresh ?: at.copy(x = at.x + now.x - was.x, y = at.y + now.y - was.y)
            }
        }
        for (found in found(before)) {
            val link = found.link
            if (link.from !in changed && link.to !in changed) continue
            val old = before.strokes.first { it.id == link.connector }
            if (now[link.connector] !== old) continue
            val (hasA, a) = endNow(link.from); val (hasB, b) = endNow(link.to)
            if (!hasA || !hasB) { replaced[link.connector] = null; continue }
            if (found.elbow) {
                val corners = elbow(a ?: continue, b ?: continue, found.arrow, old.width) ?: continue
                if (traces(old.samples, corners)) continue
                replaced[link.connector] = redraw(old, ShapeRecognizer.densify(corners, 2f))?.copy(id = old.id) ?: continue
                shift(link, true, a, b)
                continue
            }
            val ends = route(a ?: continue, b ?: continue) ?: continue
            val handles = ShapePoints.from(old.copy(group = null))!!.handles
            if (ends.first == handles[0] && ends.second == handles[1]) continue
            val next = redraw(old, path(old.shapeKind!!, ends.first, ends.second, old.width)) ?: continue
            replaced[link.connector] = next.copy(id = old.id)
            shift(link, false, a!!, b!!)
        }
        if (replaced.isEmpty()) return after
        val gone = replaced.filterValues { it == null }.keys
        // A label goes with its connector and follows it, unless it was itself changed in this edit.
        val labels = replaced.keys.mapNotNull { id -> label(before, id)?.let { it.id to (id to it) } }.toMap()
        val text = if (labels.isEmpty()) after.textBoxes else after.textBoxes.mapNotNull { box ->
            val (connector, was) = labels[box.id] ?: return@mapNotNull box
            when {
                connector in gone -> null
                box != was -> box
                else -> shifts[connector]?.let { place -> box.copy(placement = place(box.placement)) } ?: box
            }
        }
        return after.copy(strokes = after.strokes.mapNotNull { if (it.id in replaced) replaced[it.id] else it }, textBoxes = text,
            reviewedInk = if (gone.isEmpty()) after.reviewedInk else after.reviewedInk.filterNot { record -> record.strokeIds.any { it in gone } })
    }
}
