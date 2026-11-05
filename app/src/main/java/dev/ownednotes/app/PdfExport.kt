package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import dev.ownednotes.core.attachmentAssetIds
import dev.ownednotes.core.hasBackground
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.PaperStyle
import dev.ownednotes.core.validate
import dev.ownednotes.core.imageAssetIds
import java.io.File
import kotlin.math.ceil

/** Sharing copy: original PDF content plus vector overlays; native originals remain read-only. */
object PdfExport {
    /** One-based page numbers/ranges; export always follows notebook order. */
    fun selectPages(notebook: Notebook, range: String, includePaper: Boolean = true): Notebook =
        selectPages(notebook, pageNumbers(notebook, range), includePaper)
    /** P201 step 8: the one-based page numbers [numbers] (the Export sheet's chosen pages), in notebook order. */
    fun selectPages(notebook: Notebook, numbers: Set<Int>, includePaper: Boolean = true): Notebook {
        notebook.validate()
        require(numbers.isNotEmpty() && numbers.all { it in 1..notebook.pages.size }) { "Choose pages within 1–${notebook.pages.size}" }
        val pages = notebook.pages.filterIndexed { index, _ -> index + 1 in numbers }
            .map { if (includePaper) it else it.copy(paper = PaperStyle.DEFAULT) }
        val assets = pages.mapNotNull { it.pdfBackground?.assetId }.toSet()
        val images = pages.flatMap { it.imageAssetIds }.toSet()
        return notebook.copy(pages = pages, assets = notebook.assets.filter { it.id in assets }, imageAssets = notebook.imageAssets.filter { it.id in images }, attachmentAssets = notebook.attachmentAssets.filter { it.id in pages.flatMap { page -> page.attachmentAssetIds } }).also { it.validate() }
    }
    /** P201 step 8: the page numbers a range such as "1, 3-5" names; the message says what is wrong with it. */
    fun pageNumbers(notebook: Notebook, range: String): Set<Int> {
        notebook.validate()
        require(range.isNotBlank() && range.length <= 200) { "Enter pages such as 1, 3-5" }
        val selected = mutableSetOf<Int>()
        range.split(',').forEach { part ->
            val token = part.trim()
            val match = Regex("([0-9]+)(?:\\s*[-–]\\s*([0-9]+))?").matchEntire(token)
            require(match != null) { "Enter pages such as 1, 3-5" }
            val first = match.groupValues[1].toIntOrNull()
            val last = if (match.groupValues[2].isEmpty()) first else match.groupValues[2].toIntOrNull()
            require(first != null && last != null && first in 1..notebook.pages.size && last in first..notebook.pages.size) {
                "Choose ascending ranges within 1–${notebook.pages.size}"
            }
            selected.addAll(first..last)
        }
        return selected
    }
    /**
     * [text] gives the recognised text of a PDF or image page (P094, F127). It is laid over the picture as an
     * invisible layer so viewers can search and copy it. Without it, pages export as before.
     */
    internal fun create(notebook: Notebook, cache: File, includeReviewedHandwriting: Boolean = true, editableInk: Boolean = false, smallerFile:Boolean=false, text: ((Page) -> PageText.Result?)? = null): File {
        notebook.validate()
        if (editableInk || notebook.pages.any { it.pdfBackground != null }) {
            try { return SourcePdfExport.create(notebook, cache, includeReviewedHandwriting, text, editableInk, if(smallerFile)1024 else 2048) }
            catch (error: java.io.IOException) {
                if (editableInk) throw error // Never silently flatten an explicitly editable export.
                // A PDF readable by Android may use a feature unsupported by this writer.
                // Retain the previous sharing path; source/native originals stay unchanged.
            }
        }
        return createRendered(notebook, cache, includeReviewedHandwriting, text, imageEdge=if(smallerFile)1024 else 2048)
    }

    /** Existing Android writer; also makes transparent, vector-only overlays one page at a time. */
    internal fun createRendered(notebook: Notebook, cache: File, includeReviewedHandwriting: Boolean = true,
                                text: ((Page) -> PageText.Result?)? = null, objectsOnly: Boolean = false, includeInk: Boolean = true, imageEdge:Int=2048): File {
        notebook.validate()
        require(notebook.pages.all { it.width <= 14_400 && it.height <= 14_400 }) { "PDF export supports page edges up to 14,400 points" }
        val destination = File.createTempFile("export-", ".pdf", cache)
        try {
            val document = PdfDocument()
            try {
                val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
                notebook.pages.forEachIndexed { index, source ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(ceil(source.width).toInt(), ceil(source.height).toInt(), index + 1).create())
                    try {
                        if (objectsOnly) {
                            page.canvas.save()
                            try {
                                page.canvas.clipRect(0f, 0f, source.width, source.height)
                                ExportRendering.drawObjects(page.canvas, notebook, source, inkPaint, imagePaint, includeInk = includeInk, imageEdge=imageEdge)
                            } finally { page.canvas.restore() }
                        } else ExportRendering.drawPage(page.canvas, notebook, source, cache, inkPaint, imagePaint, includeInk = includeInk, imageEdge=imageEdge)
                        if (text != null && source.hasBackground) text(source)?.let { drawTextLayer(page.canvas, it) }
                        if (includeReviewedHandwriting) {
                            val lines = source.activeReviewedInk.flatMap { record ->
                                val parts = record.text.lines(); val box = record.bounds
                                val height = (box.bottom - box.top) / parts.size
                                parts.mapIndexed { i, line -> PageText.Line(line, android.graphics.RectF(box.left, box.top + i*height, box.right, box.top + (i+1)*height)) }
                            }
                            drawTextLayer(page.canvas, PageText.Result(lines, emptyList()))
                        }
                    } finally { document.finishPage(page) }
                }
                destination.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
            return destination
        } catch (error: Throwable) { destination.delete(); throw error }
    }

    /** Each line at its box: alpha 1/255 because the PDF writer drops fully transparent text; stretched to the box width. */
    internal fun drawTextLayer(canvas: Canvas, result: PageText.Result) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(1, 0, 0, 0) }
        result.lines.forEach { line ->
            val box = line.box
            if (line.text.isBlank() || box.width() <= 0f || box.height() <= 0f) return@forEach
            paint.textSize = box.height() * .9f
            val width = paint.measureText(line.text).takeIf { it > 0f } ?: return@forEach
            canvas.save()
            try {
                canvas.translate(box.left, box.bottom - paint.descent() * .5f)
                canvas.scale(box.width() / width, 1f)
                canvas.drawText(line.text, 0f, 0f, paint)
            } finally { canvas.restore() }
        }
    }
}
