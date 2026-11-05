package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import dev.ownednotes.core.ImageAsset
import dev.ownednotes.core.ImageCrop
import dev.ownednotes.core.ImageObject
import dev.ownednotes.core.Page
import dev.ownednotes.core.canSee

/** Raw image pixels retain alpha; the object transform applies EXIF and placement exactly once. */
internal object PhotoRendering {
    fun draw(canvas: Canvas, image: ImageObject, bitmap: Bitmap, paint: Paint) {
        val m = image.rawToPageTransform()
        val matrix = Matrix().apply { setValues(floatArrayOf(
            m[0] / bitmap.width, m[2] / bitmap.height, m[4],
            m[1] / bitmap.width, m[3] / bitmap.height, m[5], 0f, 0f, 1f)) }
        val saved = canvas.save()
        try {
            if (image.crop != ImageCrop.FULL) {
                val corners=image.placement.corners()
                canvas.clipPath(Path().apply {
                    moveTo(corners[0].x,corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x,it.y) };close()
                })
            }
            canvas.drawBitmap(bitmap, matrix, paint)
        } finally { canvas.restoreToCount(saved) }
    }

    /**
     * IO-thread render for exports/previews; shared source images decode once per page, then are released.
     * B33 (P196): with [decoded] (keyed by asset id and edge) the caller keeps the decoded images for its next page instead.
     */
    fun drawPage(canvas: Canvas, page: Page, assets: List<ImageAsset>, longestEdge: Int = 2048,
                 paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG), decoded: MutableMap<String, Bitmap>? = null) {
        if (page.images.isEmpty()) return
        val edge = longestEdge.coerceIn(1, 2048)
        val bitmaps = decoded ?: mutableMapOf()
        try {
            page.images.filter { page.canSee(it.layer) }.forEach { image ->
                val bitmap = bitmaps.getOrPut("${image.assetId}@$edge") {
                    val asset = requireNotNull(assets.firstOrNull { it.id == image.assetId }) { "Image object asset missing" }
                    ImageSupport.decodeRaw(asset, edge)
                }
                canvas.withObjectOpacity(page, image.layer) { draw(canvas, image, bitmap, paint) }
            }
        } finally { if (decoded == null) bitmaps.values.forEach { it.recycle() } }
    }
}
