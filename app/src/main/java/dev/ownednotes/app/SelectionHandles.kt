package dev.ownednotes.app

import android.graphics.RectF
import android.graphics.Matrix
import dev.ownednotes.core.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Page-space layout with screen-sized targets; indices run clockwise from the top left. */
class SelectionHandles(bounds: RectF, val unit: Float, minY: Float) {
    val box = RectF(bounds).apply {
        inset(-5 * unit, -5 * unit)
        val extraX = (72 * unit - width()).coerceAtLeast(0f) / 2
        val extraY = (72 * unit - height()).coerceAtLeast(0f) / 2
        inset(-extraX, -extraY)
        val safeTop = minY + 7 * unit
        if (top < safeTop && bounds.top >= safeTop) offset(0f, safeTop - top)
    }
    val points = listOf(
        Point(box.left, box.top), Point(box.centerX(), box.top), Point(box.right, box.top),
        Point(box.right, box.centerY()), Point(box.right, box.bottom), Point(box.centerX(), box.bottom),
        Point(box.left, box.bottom), Point(box.left, box.centerY()),
        Point(box.centerX(), if (box.top - 38 * unit >= minY) box.top - 32 * unit else box.bottom + 32 * unit),
    )
    fun hit(point: Point): Int? = points.indices.minByOrNull {
        hypot(points[it].x - point.x, points[it].y - point.y)
    }?.takeIf { hypot(points[it].x - point.x, points[it].y - point.y) <= 12 * unit }
    fun pivot(index: Int): Point = if (index == 8) Point(box.centerX(), box.centerY()) else points[(index + 4) % 8]
    /** Decoration length stays screen-sized even when the selected ink is stretched. */
    fun rotationStem(transform: Matrix): FloatArray {
        val rotation = points[8]
        val baseY = if (rotation.y < box.top) box.top else box.bottom
        val line = floatArrayOf(rotation.x, baseY, rotation.x, rotation.y)
        transform.mapPoints(line)
        val dx = line[2] - line[0]; val dy = line[3] - line[1]
        val length = hypot(dx, dy)
        if (length > 0f) {
            line[2] = line[0] + dx / length * 32 * unit
            line[3] = line[1] + dy / length * 32 * unit
        }
        return line
    }
    fun scale(index: Int, start: Point, current: Point): Float {
        val pivot = pivot(index)
        val dx = start.x - pivot.x; val dy = start.y - pivot.y
        val value = when (index) {
            1, 5 -> (current.y - pivot.y) / dy
            3, 7 -> (current.x - pivot.x) / dx
            else -> ((current.x - pivot.x) * dx + (current.y - pivot.y) * dy) / (dx * dx + dy * dy)
        }
        return if (value.isFinite()) value.coerceIn(.05f, 20f) else 1f
    }
    companion object {
        fun snapped(degrees: Float): Float {
            val nearest = round(degrees / 45f) * 45f
            return if (abs(degrees - nearest) <= 4f) nearest else degrees
        }
        /** The live label shown while [handle] is dragged: the angle for the rotate handle, otherwise the size. */
        fun readout(handle: Int, scale: Float, scaleY: Float, degrees: Float): String = when (handle) {
            8 -> {
                var turn = round(degrees).toInt() % 360
                if (turn > 180) turn -= 360 else if (turn <= -180) turn += 360
                "$turn°"
            }
            1, 5 -> "${round(scaleY * 100).toInt()}%"
            else -> "${round(scale * 100).toInt()}%"
        }
    }
}
