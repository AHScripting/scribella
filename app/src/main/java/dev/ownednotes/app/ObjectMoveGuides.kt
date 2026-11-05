package dev.ownednotes.app

import android.graphics.RectF
import dev.ownednotes.core.*
import kotlin.math.hypot

/** Targets are captured on contact, never recomputed on each drag frame. */
internal class ObjectMoveGuides(page: Page, source: RectF, excluded: ObjectIds, private val unit: Float) {
    private fun RectF.box() = AlignmentGuides.Box(left, top, right, bottom)
    private fun ImagePlacement.box(): AlignmentGuides.Box {
        val p = corners()
        return AlignmentGuides.Box(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
    }
    private val engine = AlignmentGuides(source.box(), buildList {
        add(AlignmentGuides.Box(0f, 0f, page.width, page.height))
        page.images.filter { it.id !in excluded.images && page.canSee(it.layer) && page.objectOpacity(it.layer) > 0f }.forEach { add(it.placement.box()) }
        page.textBoxes.filter { it.id !in excluded.texts && page.canSee(it.layer) && page.objectOpacity(it.layer) > 0f }.forEach { add(it.placement.box()) }
        page.attachments.forEach { add(it.placement.box()) }
        val bounds = RectF()
        page.drawnStrokes.asReversed().asSequence().filter { it.id !in excluded.strokes && page.opacityOf(it) > 0f && (it.color ushr 24) != 0L }.take(256).forEach {
            val path = InkGeometry.visible(it)
            if (!path.isEmpty) { path.computeBounds(bounds, true); add(bounds.box()) }
        }
    })
    var lines: List<Pair<Point, Point>> = emptyList(); private set
    fun move(dx: Float, dy: Float): Point {
        if (hypot(dx, dy) < 3f * unit) { lines = emptyList(); return Point(dx, dy) }
        val result = engine.snap(dx, dy, 6f * unit)
        lines = result.lines
        return Point(result.dx, result.dy)
    }
    companion object {
        fun bounds(placement: ImagePlacement): RectF {
            val p = placement.corners()
            return RectF(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
        }
    }
}
