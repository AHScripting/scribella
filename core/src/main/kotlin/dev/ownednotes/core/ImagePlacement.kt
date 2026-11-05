package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Upright image rectangle, rotated about its centre. Coordinates are page units, independent of screen zoom. */
@Serializable
data class ImagePlacement(val x: Float, val y: Float, val width: Float, val height: Float, val degrees: Float = 0f) {
    init {
        require(listOf(x, y, degrees).all { it.isFinite() && abs(it) <= 1_000_000f }) { "Invalid image position or rotation" }
        require(width.isFinite() && height.isFinite() && width in 1f..100_000f && height in 1f..100_000f) { "Invalid image size" }
        require(corners().all { abs(it.x) <= 1_000_000f && abs(it.y) <= 1_000_000f }) { "Image placement exceeds coordinate limits" }
    }

    /** Normalized upright image point (0..1 on each axis) to the page; values outside the image are supported. */
    fun pagePoint(u: Float, v: Float): Point {
        require(u.isFinite() && v.isFinite()) { "Invalid image point" }
        val radians = Math.toRadians(degrees.toDouble())
        val dx = (u.toDouble() - .5) * width; val dy = (v.toDouble() - .5) * height
        return Point((x + width / 2.0 + cos(radians) * dx - sin(radians) * dy).toFloat(),
            (y + height / 2.0 + sin(radians) * dx + cos(radians) * dy).toFloat())
    }

    /** Page point to normalized upright image coordinates, before any crop or EXIF transform. */
    fun unitPoint(point: Point): Point {
        require(point.x.isFinite() && point.y.isFinite()) { "Invalid page point" }
        val radians = Math.toRadians(degrees.toDouble())
        val dx = point.x - (x + width / 2.0); val dy = point.y - (y + height / 2.0)
        return Point((.5 + (cos(radians) * dx + sin(radians) * dy) / width).toFloat(),
            (.5 + (-sin(radians) * dx + cos(radians) * dy) / height).toFloat())
    }

    fun contains(point: Point): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite()) return false
        val unit = unitPoint(point)
        return unit.x in -0.00001f..1.00001f && unit.y in -0.00001f..1.00001f
    }

    fun corners(): List<Point> = listOf(pagePoint(0f, 0f), pagePoint(1f, 0f), pagePoint(1f, 1f), pagePoint(0f, 1f))
    fun translated(dx: Float, dy: Float): ImagePlacement = copy(x = x + dx, y = y + dy)

    companion object {
        /** Initial placement occupies at most 80% of either page edge and preserves the upright source aspect ratio. */
        fun fit(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): ImagePlacement {
            require(imageWidth in 1..100_000 && imageHeight in 1..100_000) { "Invalid image dimensions" }
            require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth in 1f..100_000f && pageHeight in 1f..100_000f) { "Invalid page dimensions" }
            val scale = minOf(pageWidth * .8 / imageWidth, pageHeight * .8 / imageHeight)
            val width = (imageWidth * scale).toFloat(); val height = (imageHeight * scale).toFloat()
            require(width >= 1f && height >= 1f) { "Image aspect ratio is too extreme for this page" }
            return ImagePlacement((pageWidth - width) / 2f, (pageHeight - height) / 2f, width, height)
        }
    }
}
