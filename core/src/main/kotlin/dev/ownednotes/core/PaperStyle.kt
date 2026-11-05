package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

@Serializable enum class PaperPattern { PLAIN, RULED, GRID, DOTS, ISOMETRIC }

/** Document-space paper. Guides are background geometry, never selectable/erasable ink. */
@Serializable data class PaperStyle(
    val pattern: PaperPattern = PaperPattern.PLAIN,
    val color: Long = 0xffffffffL,
    val guideColor: Long = 0xffced7e0L,
    val spacing: Float = 32f,
    val weight: Float = .75f,
) {
    companion object { val DEFAULT = PaperStyle() }
    private fun columns(width: Float) = (ceil(width / spacing).toInt() - 1).coerceAtLeast(0)
    private fun rows(height: Float) = (ceil(height / spacing).toInt() - 1).coerceAtLeast(0)
    /** P092 isometric: vertical lines spacing apart and 30-degree lines whose intercepts step by the triangle side. */
    private val side get() = 2.0 * spacing / sqrt(3.0)
    private fun slanted(width: Float, height: Float) = 2L * ((height + width / sqrt(3.0)) / side).toLong() + 2L
    fun validate(width: Float, height: Float) {
        require(color in 0xff000000L..0xffffffffL && guideColor in 0xff000000L..0xffffffffL) { "Paper colors must be opaque RGB" }
        require(spacing.isFinite() && spacing in 16f..256f && weight.isFinite() && weight in .25f..3f) { "Invalid paper spacing/weight" }
        require(width.isFinite() && height.isFinite() && width in 1f..100_000f && height in 1f..100_000f) { "Invalid paper dimensions" }
        val count = when (pattern) {
            PaperPattern.PLAIN -> 0L
            PaperPattern.RULED -> rows(height).toLong()
            PaperPattern.GRID -> rows(height).toLong() + columns(width)
            PaperPattern.DOTS -> rows(height).toLong() * columns(width)
            PaperPattern.ISOMETRIC -> columns(width).toLong() + slanted(width, height)
        }
        require(count <= 40_000L) { "Paper guides are too dense for this page; increase spacing" }
    }
    /** Shared deterministic guide placement for Android and portable SVG. Page edges have no guide. */
    fun guides(width: Float, height: Float, line: (Float, Float, Float, Float) -> Unit, dot: (Float, Float, Float) -> Unit) {
        validate(width, height)
        if (pattern == PaperPattern.RULED || pattern == PaperPattern.GRID)
            for (row in 1..rows(height)) line(0f, row * spacing, width, row * spacing)
        if (pattern == PaperPattern.GRID || pattern == PaperPattern.ISOMETRIC)
            for (column in 1..columns(width)) line(column * spacing, 0f, column * spacing, height)
        if (pattern == PaperPattern.DOTS)
            for (row in 1..rows(height)) for (column in 1..columns(width)) dot(column * spacing, row * spacing, weight)
        if (pattern == PaperPattern.ISOMETRIC) isometric(width, height, line)
    }
    /** Lines y = +-x/sqrt(3) + j*side through the lattice nodes (k*spacing, (m + k/2)*side), clipped to the page; corner-only touches are skipped. */
    private fun isometric(width: Float, height: Float, line: (Float, Float, Float, Float) -> Unit) {
        val slope = 1.0 / sqrt(3.0); val w = width.toDouble(); val h = height.toDouble()
        for (sign in intArrayOf(1, -1)) {
            val m = sign * slope; val low = if (sign > 0) -w * slope else 0.0; val high = if (sign > 0) h else h + w * slope
            var j = floor(low / side).toLong() + 1
            while (j * side < high) {
                val c = j * side; val x0 = -c / m; val xh = (h - c) / m
                val x1 = maxOf(0.0, minOf(x0, xh)); val x2 = minOf(w, maxOf(x0, xh))
                if (x2 - x1 > 1e-6) line(x1.toFloat(), (m * x1 + c).coerceIn(0.0, h).toFloat(), x2.toFloat(), (m * x2 + c).coerceIn(0.0, h).toFloat())
                j++
            }
        }
    }
}
