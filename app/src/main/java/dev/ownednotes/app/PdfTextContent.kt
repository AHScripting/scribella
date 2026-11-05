package dev.ownednotes.app

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.PdfAsset
import io.legere.pdfiumandroid.PdfiumCore
import android.os.ParcelFileDescriptor
import kotlin.math.ceil

/** Embedded PDF text only. Worker-thread, bounded and local; never reads handwriting or placed images. */
internal object PdfTextContent {
    const val MAX_CHARACTERS = 20_000
    data class Glyph(val start: Int, val end: Int, val box: RectF)
    class Result(val text: String, val glyphs: List<Glyph>) {
        fun boxes(start: Int, end: Int): List<RectF> {
            require(start in 0..text.length && end in start..text.length)
            if (start == end) return emptyList()
            return glyphs.filter { it.end > start && it.start < end }.map { RectF(it.box) }
        }
    }

    fun read(context: Context, notebook: Notebook, page: Page): Result {
        val background = requireNotNull(page.pdfBackground) { "This page has no PDF background" }
        val asset = notebook.assets.first { it.id == background.assetId }
        return read(context, asset, background.pageIndex, page.width, page.height)
    }

    fun read(context: Context, asset: PdfAsset, pageIndex: Int, width: Float, height: Float): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PDF text must be read off the UI thread" }
        require(width.isFinite() && height.isFinite() && width in 1f..100_000f && height in 1f..100_000f)
        return PdfAccess.withFile(context.cacheDir, asset) { file ->
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                readDocument(context,descriptor,pageIndex,width,height)
            }
        }
    }
    /** Search needs text only, not per-character hit boxes. One verified source lease for the walk. */
    fun readBook(context: Context, asset: PdfAsset, indices: List<Int>, cancelled: () -> Boolean,
        received: (Int, String) -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        PdfAccess.withFile(context.cacheDir, asset) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfiumCore(context.applicationContext).newDocument(descriptor).use { document ->
                    for (index in indices) {
                        if (cancelled()) return@use
                        require(index in 0 until document.getPageCount())
                        requireNotNull(document.openPage(index)).use { page ->
                            page.openTextPage().use { text ->
                                val count = text.textPageCountChars()
                                require(count in 0..MAX_CHARACTERS) { "Too much PDF text on this page" }
                                val value = text.textPageGetText(0, count).orEmpty().replace("\u0000", "")
                                    .replace("\r\n", "\n").replace('\r', '\n')
                                received(index, value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readDocument(context: Context,descriptor: ParcelFileDescriptor,pageIndex: Int,width: Float,height: Float): Result =
        PdfiumCore(context.applicationContext).newDocument(descriptor).use { document ->
            require(pageIndex in 0 until document.getPageCount()) { "PDF page is missing" }
            requireNotNull(document.openPage(pageIndex)) { "PDF page could not be opened" }.use { pdfPage ->
                pdfPage.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars()
                    require(count in 0..MAX_CHARACTERS) { "Too much PDF text on this page to select safely" }
                    val text = StringBuilder()
                    val glyphs = mutableListOf<Glyph>()
                    // PDFium maps intrinsic rotation and crop origin. A larger virtual viewport retains sub-point boxes.
                    val mapWidth = ceil(width * 4.0).toInt()
                    val mapHeight = ceil(height * 4.0).toInt()
                    var previousCR = false
                    repeat(count) { index ->
                        val raw = textPage.textPageGetText(index, 1).orEmpty().replace("\u0000", "")
                        val character = if (raw == "\r") "\n" else raw
                        if (raw == "\n" && previousCR) { previousCR = false; return@repeat }
                        previousCR = raw == "\r"
                        if (character.isEmpty()) return@repeat
                        val start = text.length
                        text.append(character)
                        require(text.length <= MAX_CHARACTERS * 2) { "Too much PDF text on this page" }
                        textPage.textPageGetCharBox(index)?.let { source ->
                            require(listOf(source.left, source.top, source.right, source.bottom).all { it.isFinite() }) { "Invalid PDF text bounds" }
                            val mapped = pdfPage.mapRectToDevice(0, 0, mapWidth, mapHeight, 0, source)
                            val box = RectF(mapped.left * width / mapWidth, mapped.top * height / mapHeight,
                                mapped.right * width / mapWidth, mapped.bottom * height / mapHeight).apply { sort() }
                            // Empty synthetic whitespace has no hit target; off-crop content cannot be selected on the page.
                            if (!box.isEmpty && box.intersect(0f, 0f, width, height)) glyphs += Glyph(start, text.length, box)
                        }
                    }
                    Result(text.toString(), glyphs)
                }
            }
        }
}
