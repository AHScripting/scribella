package dev.ownednotes.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Insert a fresh native object; library metadata and bytes remain reusable and unchanged. */
fun DocumentSession.insertSticker(pageId: String, record: StickerRecord, asset: ImageAsset): String {
    require(record.photoOnly) { "Use mixed insertion for this sticker" }
    val source = requireNotNull(record.image)
    require(source.assetId == asset.id) { "Sticker image and original differ" }
    val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Sticker target page is missing")
    val p = source.placement
    val angle = Math.toRadians(p.degrees.toDouble())
    val boundsWidth = abs(cos(angle)) * p.width + abs(sin(angle)) * p.height
    val boundsHeight = abs(sin(angle)) * p.width + abs(cos(angle)) * p.height
    val scale = minOf(1.0, page.width * .8 / boundsWidth, page.height * .8 / boundsHeight)
    val width = (p.width * scale).toFloat()
    val height = (p.height * scale).toFloat()
    val placement = ImagePlacement((page.width - width) / 2f, (page.height - height) / 2f, width, height, p.degrees)
    return insertImageObject(pageId, asset, placement, source.orientation, source.crop)
}
