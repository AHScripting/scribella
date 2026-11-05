package dev.ownednotes.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

/** Images have their own ordered stack below handwriting; EXIF is applied before placement. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImageObject(val assetId: String, val placement: ImagePlacement,
    val id: String = UUID.randomUUID().toString(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val orientation: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val crop: ImageCrop = ImageCrop.FULL,
    /** P101 (schema 21): a locked photo is drawn but can't be selected or moved until unlocked. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
    /** Schema 30: shared mixed-object group on this page. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val group: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val layer: String? = null) {
    init { require(group==null || group.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid image group" } }
    fun croppedTo(crop: ImageCrop): ImageObject = if (crop == this.crop) this
        else copy(placement = crop.reframe(placement, this.crop), crop = crop)
    /** Raw normalized image pixels to page coordinates, in SVG/Canvas [a,b,c,d,e,f] order. */
    fun rawToPageTransform(): List<Float> {
        val m = ImageBackground(assetId, orientation).normalizedTransform()
        // Compose coefficients before rounding: subtracting Float page points moves filter phases at sharp edges.
        val angle = Math.toRadians(placement.degrees.toDouble())
        val cos = kotlin.math.cos(angle); val sin = kotlin.math.sin(angle)
        val w = placement.width.toDouble()/crop.width; val h = placement.height.toDouble()/crop.height
        val a = cos*w; val b = sin*w; val c = -sin*h; val d = cos*h
        val u = crop.left.toDouble()+crop.width/2.0; val v = crop.top.toDouble()+crop.height/2.0
        val e = placement.x+placement.width/2.0-a*u-c*v
        val f = placement.y+placement.height/2.0-b*u-d*v
        return listOf((a*m[0]+c*m[1]).toFloat(),(b*m[0]+d*m[1]).toFloat(),
            (a*m[2]+c*m[3]).toFloat(),(b*m[2]+d*m[3]).toFloat(),
            (a*m[4]+c*m[5]+e).toFloat(),(b*m[4]+d*m[5]+f).toFloat())
    }
}

/** All original images needed by a page, including shared backgrounds and movable objects. */
val Page.imageAssetIds: Set<String> get() = buildSet {
    imageBackground?.let { add(it.assetId) }
    images.forEach { add(it.assetId) }
}
