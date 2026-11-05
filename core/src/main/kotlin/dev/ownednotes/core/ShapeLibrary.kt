package dev.ownednotes.core

import dev.ownednotes.core.ShapeRecognizer.Kind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * B11: the shapes offered by the Shapes button. Each is one clean pen line drawn in a box of the given size,
 * the same outlines hold-to-shape snaps to (the saved shapes come from [ShapeRecognizer.saved]). Pure; page units.
 */
object ShapeLibrary {
    /** [aspect] is height / width of the shape's box. */
    enum class Choice(val label: String, val kind: Kind, val aspect: Float) {
        LINE("Line", Kind.LINE, 0f),
        ARROW("Arrow", Kind.ARROW, .4f),
        RECTANGLE("Rectangle", Kind.RECTANGLE, .7f),
        CIRCLE("Circle", Kind.CIRCLE, 1f),
        TRIANGLE("Triangle", Kind.TRIANGLE, .87f),
        STAR("Star", Kind.STAR, .95f),
        HEART("Heart", Kind.HEART, .9f),
        CHECK("Check mark", Kind.CHECK, .75f),
        LIGHTNING("Lightning", Kind.LIGHTNING, 1.6f),
    }

    /** The outline in a unit box (y grows downward); [closed] outlines end where they start. */
    fun unitOutline(choice: Choice): List<Point> = when (choice) {
        Choice.LINE -> listOf(Point(0f, 0f), Point(1f, 0f))
        // Shaft to the tip, out to one barb, back to the tip and out to the other, in one stroke.
        Choice.ARROW -> listOf(Point(0f, .5f), Point(1f, .5f), Point(.78f, 0f), Point(1f, .5f), Point(.78f, 1f))
        Choice.RECTANGLE -> listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f), Point(0f, 0f))
        Choice.CIRCLE -> List(97) { val a = -PI / 2 + 2 * PI * it / 96; Point((.5 + .5 * cos(a)).toFloat(), (.5 + .5 * sin(a)).toFloat()) }
        Choice.TRIANGLE -> listOf(Point(.5f, 0f), Point(1f, 1f), Point(0f, 1f), Point(.5f, 0f))
        else -> ShapeRecognizer.saved.first { it.kind == choice.kind }.let { if (it.closed) it.outline + it.outline.first() else it.outline }
    }

    /** The shape [width] page units wide, centred on [centre], as one densified polyline with points [spacing] apart. */
    fun path(choice: Choice, centre: Point, width: Float, spacing: Float = 2f): List<Point> {
        require(width.isFinite() && width > 0f && centre.x.isFinite() && centre.y.isFinite()) { "Invalid shape box" }
        val height = width * choice.aspect
        val left = centre.x - width / 2; val top = centre.y - height / 2
        return ShapeRecognizer.densify(unitOutline(choice).map { Point(left + it.x * width, top + it.y * height) }, spacing)
    }
}
