package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.ownednotes.core.*
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/** PDF parsing and rendering run on background executors, never on the UI thread. */
object PdfSupport {
    data class Prepared(val asset: PdfAsset, val total: Int)

    fun prepare(cache: File, input: InputStream,ownedDirectory: File? = null): Prepared = withAsset(cache,input,ownedDirectory) { asset ->
        withRenderer(cache, asset) { renderer ->
            require(renderer.pageCount > 0) { "PDF has no pages" }
            Prepared(asset, renderer.pageCount)
        }
    }

    /** Only selected pages are opened/render-probed; original indices remain attached to them. */
    fun importSelected(cache: File, prepared: Prepared, title: String, indices: List<Int>): Notebook {
        require(indices.isNotEmpty() && indices.size <= NotebookLimits.PDF_PAGES && indices.distinct() == indices && indices.all { it in 0 until prepared.total }) { "Invalid page selection" }
        return withRenderer(cache, prepared.asset) { renderer ->
            require(renderer.pageCount == prepared.total) { "PDF changed" }
            val pages = indices.map { index -> renderer.openPage(index).use { page ->
                require(page.width in 1..100_000 && page.height in 1..100_000) { "Invalid PDF page dimensions" }
                val probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try { page.render(probe, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                finally { probe.recycle() }
                Page(width = page.width.toFloat(), height = page.height.toFloat(), pdfBackground = PdfBackground(prepared.asset.id, index))
            } }
            Notebook(title = title.trim().take(500).ifBlank { "Imported PDF" }, pages = pages, assets = listOf(prepared.asset)).also { it.validate() }
        }
    }
    private fun <T> withRenderer(cache: File, asset: PdfAsset, operation: (PdfRenderer) -> T): T {
        return PdfAccess.withFile(cache, asset) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { operation(it) }
            }
        }
    }
    private fun <T> withAsset(cache: File,input: InputStream,ownedDirectory: File?,operation: (PdfAsset)->T): T =
        PdfAsset.fromStream(input, ownedDirectory ?: cache) { asset ->
            // Password entry needs the unpublished original to survive the prompt. Other failures
            // still escape the callback so its existing rollback removes only its owned copy.
            try { Result.success(operation(asset)) }
            catch (locked: PdfAccess.PasswordRequired) { Result.failure(locked) }
        }.getOrThrow()

    fun import(cache: File, input: InputStream, title: String,ownedDirectory: File? = null): Notebook = read(cache, input, title, firstPagesOnly = false,ownedDirectory).first

    /** P122 (F195): Import PDF keeps the first [NotebookLimits.PDF_PAGES] pages of a longer PDF and reports the rest instead of refusing it (16 before B24, P140). */
    fun importWithReport(cache: File, input: InputStream, title: String,ownedDirectory: File? = null): Pair<Notebook, ImportReport> {
        val (notebook, total) = read(cache, input, title, firstPagesOnly = true,ownedDirectory)
        val skipped = if (total > notebook.pages.size) listOf(ImportReport.count(total - notebook.pages.size, "page after page ${NotebookLimits.PDF_PAGES}", "pages after page ${NotebookLimits.PDF_PAGES}")) else emptyList()
        return notebook to ImportReport("PDF", ImportReport.contents(notebook), skipped)
    }

    private fun read(cache: File, input: InputStream, title: String, firstPagesOnly: Boolean,ownedDirectory: File?): Pair<Notebook, Int> = withAsset(cache,input,ownedDirectory) { asset ->
        withRenderer(cache, asset) { renderer ->
            require(renderer.pageCount >= 1 && (firstPagesOnly || renderer.pageCount <= NotebookLimits.PDF_PAGES)) { "PDFs with up to ${NotebookLimits.PDF_PAGES} pages can be inserted" }
            val pages = (0 until minOf(NotebookLimits.PDF_PAGES, renderer.pageCount)).map { index ->
                renderer.openPage(index).use { page ->
                    require(page.width in 1..100_000 && page.height in 1..100_000) { "Invalid PDF page dimensions" }
                    // Exercise every page before activating the notebook, with bounded scratch memory.
                    val probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    try { page.render(probe, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    finally { probe.recycle() }
                    Page(width = page.width.toFloat(), height = page.height.toFloat(), pdfBackground = PdfBackground(asset.id, index))
                }
            }
            Notebook(title = title.trim().take(500).ifBlank { "Imported PDF" }, pages = pages, assets = listOf(asset)).also { it.validate() } to renderer.pageCount
        }
    }

    /** Bounded import preview, independent of full-page rendering and visible-page caches. */
    internal fun thumbnail(cache:File,prepared:Prepared,index:Int):Bitmap=withRenderer(cache,prepared.asset) {renderer->
        require(index in 0 until renderer.pageCount)
        renderer.openPage(index).use {page->
            val scale=240f/maxOf(page.width,page.height)
            val bitmap=Bitmap.createBitmap((page.width*scale).roundToInt().coerceAtLeast(1),(page.height*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap}
            catch(error:Throwable){bitmap.recycle();throw error}
        }
    }

    fun render(cache: File, asset: PdfAsset, background: PdfBackground): Bitmap = withRenderer(cache, asset) { renderer ->
        require(background.pageIndex in 0 until renderer.pageCount) { "PDF page not found" }
        renderer.openPage(background.pageIndex).use { page ->
            val scale = 2048f / maxOf(page.width, page.height)
            val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }
}
