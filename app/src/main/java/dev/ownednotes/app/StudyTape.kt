package dev.ownednotes.app

import android.graphics.Path
import android.graphics.RectF
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.Sample
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * P085 study tape (F158): an opaque rounded strip laid over selected ink. It is stored as an ordinary stroke with the
 * [InkStroke.TAPE] brush, so the Lasso moves, recolours and deletes it and every renderer draws it covered. Peeling it
 * back is live-editor state only; a reopened notebook, an export or a thumbnail always shows the tape.
 */
object StudyTape {
    /** Warm kraft paper: reads as tape on white, cream and dark paper, and is light enough to write on. */
    const val COLOR = 0xffd9c7a0
    /** Page units around the covered ink, and the corner radius. */
    const val PADDING = 8f
    const val RADIUS = 6f

    /** A strip covering [bounds] (page units) plus [PADDING]. Samples are its four corners; the outline is the rounded shape. */
    fun cover(bounds: RectF, color: Long = COLOR): InkStroke {
        val box = RectF(bounds).apply { inset(-PADDING, -PADDING) }
        val r = min(RADIUS, min(box.width(), box.height()) / 2)
        val outline = buildList {
            // Clockwise from the top-right corner arc, eight segments per corner.
            listOf(Triple(box.right - r, box.top + r, -90.0), Triple(box.right - r, box.bottom - r, 0.0),
                Triple(box.left + r, box.bottom - r, 90.0), Triple(box.left + r, box.top + r, 180.0)).forEach { (cx, cy, start) ->
                for (i in 0..8) {
                    val a = Math.toRadians(start + 90.0 * i / 8)
                    add(Point(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()))
                }
            }
        }
        val corners = listOf(Point(box.left, box.top), Point(box.right, box.top), Point(box.right, box.bottom), Point(box.left, box.bottom))
        return InkStroke(color = color, width = 1f, brush = InkStroke.TAPE,
            samples = corners.mapIndexed { i, p -> Sample(p.x, p.y, i.toLong(), .5f) }, outlines = listOf(outline))
    }

    /** The topmost tape whose visible shape contains page point ([x], [y]), or null. */
    fun at(strokes: List<InkStroke>, paths: Map<String, Path>, x: Float, y: Float, slop: Float): String? {
        val probe = Path().apply { addCircle(x, y, slop, Path.Direction.CW) }
        return strokes.lastOrNull { it.isTape && paths[it.id]?.let { path -> InkGeometry.intersects(path, probe) } == true }?.id
    }
}
