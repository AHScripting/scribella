package dev.ownednotes.app

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

/**
 * P074 scribble to erase (F036): a dense back-and-forth pen scribble over ink removes the strokes it covers.
 * Detection is geometric only and in page units, so it behaves the same at every zoom.
 */
object ScribbleErase {
    private const val PREFERENCE = "scribbleErase"
    /** Sharp turn-backs a scribble needs: five means at least six passes over the ink. */
    private const val MIN_REVERSALS = 5
    /** Path length against the bounds' half-perimeter: a scribble goes over the same area several times. */
    private const val MIN_DENSITY = 1.2f
    /** How far back along its swing the pen must come, as a fraction of the swing, for a turn-back to count. */
    private const val TURN_BACK = .5f
    /** How much of the pen's movement, with its travel removed, runs along one line (1) rather than in every direction (0). */
    private const val MIN_LINEARITY = .7

    fun enabled(context: Context) = preferences(context).getBoolean(PREFERENCE, true)
    fun setEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(PREFERENCE, enabled).apply()
    private fun preferences(context: Context) = (context.applicationContext as NotesApplication).let {
        it.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE)
    }

    /** True when [points] read as a scribble: dense, with at least [MIN_REVERSALS] sharp turn-backs. */
    fun isScribble(points: List<Point>): Boolean {
        if (points.size < 12) return false
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }
        val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val w = right - left; val h = bottom - top
        val extent = max(w, h)
        if (extent <= 0f) return false
        val length = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        if (length < MIN_DENSITY * (w + h)) return false
        // Even spacing makes the test independent of pen speed; the step ignores tremor smaller than 1/30 of the scribble.
        val path = resample(points, extent / 30f)
        if (path.size < 4) return false
        // Remove steady travel across the word before measuring the axis the pen swings along.
        // Otherwise wide landscape zigzags select their monotonic forward axis and have no counted turn-backs.
        val driftX = (path.last().x - path.first().x).toDouble() / (path.size - 1)
        val driftY = (path.last().y - path.first().y).toDouble() / (path.size - 1)
        var c = 0.0; var s = 0.0; var energy = 0.0
        path.zipWithNext { a, b ->
            val dx = b.x - a.x - driftX; val dy = b.y - a.y - driftY
            c += dx * dx - dy * dy; s += 2 * dx * dy; energy += dx * dx + dy * dy
        }
        val angle = atan2(s, c) / 2
        val ax = cos(angle).toFloat(); val ay = sin(angle).toFloat()
        if (reversals(path.map { it.x * ax + it.y * ay }) >= MIN_REVERSALS) return true
        // B05 (P131): over a large area the pen travels along its own swing (diagonal or sideways scrubbing), so measured
        // on the page the travel swamps each swing. Measure again with the travel removed, but only for motion that goes
        // back and forth along one line; loops ("eee", "ooo") move in every direction and must stay handwriting.
        if (energy <= 0.0 || hypot(c, s) / energy < MIN_LINEARITY) return false
        return reversals(path.mapIndexed { i, p -> (p.x - (i * driftX).toFloat()) * ax + (p.y - (i * driftY).toFloat()) * ay }) >= MIN_REVERSALS
    }

    /** Turn-backs along [along]: one counts once the pen has come back at least half the swing, so jitter and small letters never count. */
    private fun reversals(along: List<Float>): Int {
        val swing = along.max() - along.min()
        if (swing <= 0f) return 0
        val back = swing * TURN_BACK
        var reversals = 0; var rising: Boolean? = null; var high = along[0]; var low = along[0]
        for (p in along) {
            high = max(high, p); low = min(low, p)
            when (rising) {
                null -> if (p - low >= back) { rising = true; high = p } else if (high - p >= back) { rising = false; low = p }
                true -> if (high - p >= back) { reversals++; rising = false; low = p }
                false -> if (p - low >= back) { reversals++; rising = true; high = p }
            }
        }
        return reversals
    }

    /**
     * Ids of the strokes [scribble] erases on [strokes]: none unless it is a scribble; otherwise each stroke whose ink
     * touches the scribble's ink and whose bounds lie at least half inside the scribble's bounds. [visible] gives a stroke's ink.
     */
    fun covered(scribble: InkStroke, strokes: List<InkStroke>, visible: (InkStroke) -> Path): Set<String> {
        if (scribble.isHighlighter || !isScribble(scribble.samples.map { Point(it.x, it.y) })) return emptySet()
        val ink = InkGeometry.visible(scribble)
        val box = RectF().also { ink.computeBounds(it, true) }
        val bounds = RectF()
        return strokes.filter { stroke ->
            if (stroke.id == scribble.id || stroke.samples.isEmpty()) return@filter false
            // Cheap sample-bounds test first, so only nearby strokes build their outline path.
            val pad = stroke.width
            val near = stroke.samples.any { it.x + pad >= box.left && it.x - pad <= box.right && it.y + pad >= box.top && it.y - pad <= box.bottom }
            if (!near) return@filter false
            val path = visible(stroke); path.computeBounds(bounds, true)
            inside(bounds, box) >= .5f && InkGeometry.intersects(path, ink)
        }.mapTo(linkedSetOf()) { it.id }
    }

    /** Fraction of [inner]'s area that lies inside [outer]; a zero-area box counts by its overlapping extent. */
    private fun inside(inner: RectF, outer: RectF): Float {
        val w = min(inner.right, outer.right) - max(inner.left, outer.left)
        val h = min(inner.bottom, outer.bottom) - max(inner.top, outer.top)
        if (w < 0f || h < 0f) return 0f
        val fw = if (inner.width() > 0f) w / inner.width() else 1f
        val fh = if (inner.height() > 0f) h / inner.height() else 1f
        return fw * fh
    }

    private fun resample(points: List<Point>, step: Float): List<Point> {
        val out = mutableListOf(points.first())
        var carry = 0f
        for (k in 1 until points.size) {
            val a = points[k - 1]; val b = points[k]
            val d = hypot(b.x - a.x, b.y - a.y)
            if (d == 0f) continue
            var t = step - carry
            while (t <= d) { out += Point(a.x + (b.x - a.x) * t / d, a.y + (b.y - a.y) * t / d); t += step }
            carry = d - (t - step)
        }
        return out
    }
}
