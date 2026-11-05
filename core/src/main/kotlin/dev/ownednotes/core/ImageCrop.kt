package dev.ownednotes.core

import kotlinx.serialization.Serializable

/** Nondestructive rectangle in upright source coordinates, after EXIF and before placement. */
@Serializable
data class ImageCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init {
        require(listOf(left,top,right,bottom).all { it.isFinite() && it in 0f..1f }) { "Crop edges must be within the image" }
        require(right-left >= MIN_EXTENT && bottom-top >= MIN_EXTENT) { "Crop rectangle is empty or too small" }
    }
    val width get() = right-left
    val height get() = bottom-top

    /** Upright source point to crop-local coordinates. Outside points remain outside, so renderers can clip. */
    fun unitPoint(u: Float, v: Float): Point {
        require(u.isFinite() && v.isFinite()) { "Invalid crop point" }
        return Point((u-left)/width,(v-top)/height)
    }

    /** Replace a previous crop without moving or stretching source pixels, including for rotated photos. */
    fun reframe(placement: ImagePlacement, previous: ImageCrop = FULL): ImagePlacement {
        if (this == previous) return placement
        val w=placement.width*(width/previous.width)
        val h=placement.height*(height/previous.height)
        val centre=placement.pagePoint(((left+right)*.5f-previous.left)/previous.width,
            ((top+bottom)*.5f-previous.top)/previous.height)
        // ImagePlacement validates the whole result before a caller can commit it.
        return ImagePlacement(centre.x-w/2,centre.y-h/2,w,h,placement.degrees)
    }

    companion object {
        const val MIN_EXTENT = .000001f
        val FULL = ImageCrop()
    }
}
