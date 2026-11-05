package dev.ownednotes.app

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.validate
import java.io.File
import kotlin.math.ceil

/** Original streams/resources are cloned, never rendered. Android makes only local vector overlays. */
internal object SourcePdfExport {
    fun create(notebook: Notebook, cache: File, reviewed: Boolean, text: ((Page) -> PageText.Result?)?, editableInk: Boolean = false, imageEdge:Int=2048): File {
        notebook.validate()
        require(notebook.pages.all { it.width <= 14_400 && it.height <= 14_400 }) {
            "PDF export supports page edges up to 14,400 points"
        }
        val workspace = File.createTempFile("pdf-write-", "", cache)
        check(workspace.delete() && workspace.mkdir()) { "Cannot create PDF export workspace" }
        val destination = File.createTempFile("export-", ".pdf", cache)
        fun memory() = MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace)
        try {
            PDDocument(memory()).use { output ->
                notebook.pages.forEach { page -> output.addPage(PDPage(PDRectangle(ceil(page.width), ceil(page.height)))) }
                val embeddedText = BooleanArray(notebook.pages.size)
                // One source open at a time. LayerUtility clones shared resources once per original.
                notebook.assets.forEach { asset ->
                    PdfAccess.withFile(workspace, asset) { file ->
                        PDDocument.load(file, memory()).use { original ->
                            val layers = LayerUtility(output)
                            val stripper = if (text != null) PDFTextStripper() else null
                            notebook.pages.forEachIndexed { index, page ->
                                val background = page.pdfBackground
                                if (background?.assetId == asset.id) {
                                    require(background.pageIndex in 0 until original.numberOfPages) { "PDF page not found" }
                                    val source = original.getPage(background.pageIndex)
                                    val form = layers.importPageAsForm(original, source)
                                    // Preserve page rotation as metadata, so text selection/extraction keeps
                                    // the original text direction. Only crop/scale the raw page coordinates.
                                    val target = output.getPage(index)
                                    val rotation = ((source.rotation % 360) + 360) % 360
                                    require(rotation in listOf(0, 90, 180, 270)) { "Unsupported PDF rotation" }
                                    target.rotation = rotation
                                    if (rotation == 90 || rotation == 270) target.mediaBox = PDRectangle(ceil(page.height), ceil(page.width))
                                    form.setMatrix(AffineTransform())
                                    form.bBox = source.cropBox
                                    PDPageContentStream(output, target).use { content ->
                                        content.transform(sourceTransform(source, target))
                                        content.drawForm(form)
                                    }
                                    if (stripper != null) {
                                        stripper.startPage = background.pageIndex + 1
                                        stripper.endPage = background.pageIndex + 1
                                        embeddedText[index] = stripper.getText(original).isNotBlank()
                                    }
                                }
                            }
                        }
                    }
                }
                notebook.pages.forEachIndexed { index, page ->
                    val pdf = page.pdfBackground != null
                    val layerText = if (embeddedText[index]) null else text
                    if (pdf && page.strokes.isEmpty() && page.images.isEmpty() && page.textBoxes.isEmpty() &&
                        page.attachments.isEmpty() && layerText == null && (!reviewed || page.activeReviewedInk.isEmpty())) return@forEachIndexed
                    val single = PdfExport.selectPages(notebook, setOf(index + 1))
                    val overlay = PdfExport.createRendered(single, workspace, reviewed, layerText, objectsOnly = pdf, includeInk = !editableInk, imageEdge=imageEdge)
                    try {
                        PDDocument.load(overlay, memory()).use { layer ->
                            val form = LayerUtility(output).importPageAsForm(layer, 0)
                            PDPageContentStream(output, output.getPage(index), PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                                content.transform(overlayTransform(output.getPage(index)))
                                content.drawForm(form)
                            }
                        }
                    } finally { overlay.delete() }
                    if (editableInk) InkPdfAnnotations.append(output, output.getPage(index), page, workspace, notebook)
                }
                output.save(destination)
            }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            // Only this invocation's private scratch directory; never any notebook or original.
            workspace.deleteRecursively()
        }
    }

    private fun sourceTransform(source: PDPage, target: PDPage): Matrix {
        val crop = source.cropBox
        require(crop.width > 0f && crop.height > 0f) { "Invalid PDF crop box" }
        val sx = target.mediaBox.width / crop.width
        val sy = target.mediaBox.height / crop.height
        return Matrix(sx, 0f, 0f, sy, -crop.lowerLeftX * sx, -crop.lowerLeftY * sy)
    }

    /** Display-oriented Android overlay -> unrotated target PDF coordinates. */
    internal fun overlayTransform(target: PDPage): Matrix = when (target.rotation) {
        90 -> Matrix(0f, 1f, -1f, 0f, target.mediaBox.width, 0f)
        180 -> Matrix(-1f, 0f, 0f, -1f, target.mediaBox.width, target.mediaBox.height)
        270 -> Matrix(0f, -1f, 1f, 0f, 0f, target.mediaBox.height)
        else -> Matrix()
    }
}
