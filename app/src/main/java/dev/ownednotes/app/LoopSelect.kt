package dev.ownednotes.app

import android.content.Context
import android.graphics.Path
import dev.ownednotes.core.Point
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * P075 circle and hold to select (F049): a held pen loop around ink selects that ink instead of snapping to a shape.
 * Off by default, because it changes what holding a circle drawn around a word does.
 */
object LoopSelect {
    private const val PREFERENCE = "circleSelect"

    fun enabled(context: Context) = preferences(context).getBoolean(PREFERENCE, false)
    fun setEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(PREFERENCE, enabled).apply()
    private fun preferences(context: Context) = (context.applicationContext as NotesApplication).let {
        it.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE)
    }

    /**
     * The area a closed loop encloses, or null when [points] are not one: the ends must meet within a fifth of the loop's
     * size, it must be at least [minSize] across, not a thin back-and-forth line, and long enough to go around.
     */
    fun area(points: List<Point>, minSize: Float): Path? {
        if (points.size < 8) return null
        val w = points.maxOf { it.x } - points.minOf { it.x }
        val h = points.maxOf { it.y } - points.minOf { it.y }
        val size = max(w, h)
        if (size < minSize || min(w, h) < size * .15f) return null
        if (hypot(points.last().x - points.first().x, points.last().y - points.first().y) > size * .2f) return null
        val length = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        // A circle is about 3.1 times its size around, a flat 3:1 oval about 2.2.
        if (length < 2.1f * size) return null
        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            points.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
    }
}
