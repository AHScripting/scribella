package dev.ownednotes.app

import android.graphics.Bitmap
import dev.ownednotes.core.*
import java.io.ByteArrayOutputStream
import java.io.File

internal data class SvgExportSnapshot(val notebook: Notebook, val pageId: String)

/** Full current-page sharing copy; independent of the strictly ink-only study exporter. */
internal object SvgExport {
    fun create(notebook: Notebook, pageId: String, cache: File): File {
        val source = requireNotNull(notebook.pages.firstOrNull { it.id==pageId }) { "Page not found" }
        val used = source.images.map { it.assetId }.toSet() + listOfNotNull(source.imageBackground?.assetId)
        var images = notebook.imageAssets.filter { it.id in used }
        require(images.sumOf { it.size.toLong() } <= 48L*1024*1024) { "This page is too large for SVG. Export it as PDF instead." }
        val page = source.pdfBackground?.let { background ->
            val original = notebook.assets.first { it.id==background.assetId }
            val bitmap = PdfSupport.render(cache,original,background)
            val bytes = try { ByteArrayOutputStream().also {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray() }finally {bitmap.recycle()}
            val image=ImageAsset.fromBytes(bytes);images=images+image
            source.copy(pdfBackground=null,imageBackground=ImageBackground(image.id))
        } ?: source
        val output=File.createTempFile("export-svg-",".svg",cache)
        try {
            output.writeText(SvgRenderer.render(page,images,notebook.attachmentAssets),Charsets.UTF_8)
            return output
        }catch(error:Throwable){output.delete();throw error}
    }
}
