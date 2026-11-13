package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.validate
import dev.ownednotes.core.hasObjectLayers
import java.io.File
import kotlin.math.roundToInt

data class PageImageSnapshot(val notebook: Notebook, val pageId: String, val smallerFile:Boolean=false)

/** Opaque sharing copy, bounded to 2048 pixels on the longest edge. */
object PageImageExport {
    fun create(notebook: Notebook, pageId: String, cache: File, smallerFile:Boolean=false): File {
        notebook.validate()
        val page = requireNotNull(notebook.pages.firstOrNull { it.id == pageId }) { "Page not found" }
        val scale = 2048f / maxOf(page.width, page.height)
        val width = (page.width * scale).roundToInt().coerceIn(1, 2048)
        val height = (page.height * scale).roundToInt().coerceIn(1, 2048)
        val destination = File.createTempFile("export-image-", ".png", cache)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(width / page.width, height / page.height)
                ExportRendering.drawPage(canvas, notebook, page, cache, imageEdge=if(smallerFile)1024 else 2048)
                bitmap.setHasAlpha(false)
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
            } finally { bitmap.recycle() }
            return destination
        } catch (error: Throwable) { destination.delete(); throw error }
    }
}

/** Shared PDF/PNG export drawing; live editor rendering keeps its existing caches. */
internal object ExportRendering {
    fun drawObjects(canvas: Canvas, notebook: Notebook, page: Page,
        inkPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG), imagePaint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
        decodedPhotos: MutableMap<String, Bitmap>? = null, includeInk: Boolean = true, imageEdge:Int=2048) {
        page.paintSlices.forEach { slice ->
        if (includeInk || !page.hasObjectLayers) PhotoRendering.drawPage(canvas, slice, notebook.imageAssets, longestEdge=imageEdge, paint = imagePaint, decoded = decodedPhotos)
        if (includeInk || !page.hasObjectLayers) TextRendering.drawPage(canvas, slice)
        AttachmentRendering.drawPage(canvas, slice, notebook.attachmentAssets)
        if (includeInk) InkGeometry.flatHighlights(slice.drawnStrokes).let { flat -> slice.drawnStrokes.forEach { stroke -> InkGeometry.draw(canvas, stroke, inkPaint, slice.opacityOf(stroke), flat[stroke]) } }
        }
    }

    fun drawPage(canvas: Canvas, notebook: Notebook, page: Page, cache: File,
                 inkPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG), imagePaint: Paint = Paint(Paint.FILTER_BITMAP_FLAG), includeInk: Boolean = true, imageEdge:Int=2048) {
        val state = canvas.save()
        try {
            canvas.clipRect(0f, 0f, page.width, page.height)
            PaperRenderer.draw(canvas, page, inkPaint)
            val bitmap = page.pdfBackground?.let { background ->
                PdfSupport.render(cache, notebook.assets.first { it.id == background.assetId }, background)
            } ?: page.imageBackground?.let { background ->
                ImageSupport.render(notebook.imageAssets.first { it.id == background.assetId }, background, imageEdge)
            }
            try {
                bitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width, page.height), imagePaint) }
                drawObjects(canvas, notebook, page, inkPaint, imagePaint, includeInk = includeInk, imageEdge=imageEdge)
            } finally { bitmap?.recycle() }
        } finally { canvas.restoreToCount(state) }
    }
}
