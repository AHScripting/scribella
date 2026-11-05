package dev.ownednotes.app

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.PdfAsset
import dev.ownednotes.core.PdfBackground
import dev.ownednotes.core.standardShapeFit
import java.io.File

/**
 * P412 (B52): new PDF pages that show an imported page's PDF background centred on a Standard-shaped page with blank
 * margins, so every renderer keeps drawing a background over the whole page. The original PDF is cloned, never changed.
 * Runs off the main thread.
 */
internal object StandardPageShape {
    fun padded(notebook: Notebook, ids: Set<String>, cache: File, owned: File): Map<String, Pair<PdfAsset, PdfBackground>> {
        val targets = notebook.pages.filter { it.id in ids && it.pdfBackground != null && it.standardShapeFit() != null }
        if (targets.isEmpty()) return emptyMap()
        val workspace = File.createTempFile("pdf-pad-", "", cache)
        check(workspace.delete() && workspace.mkdir()) { "Cannot create PDF workspace" }
        fun memory() = MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace)
        try {
            val result = mutableMapOf<String, Pair<PdfAsset, PdfBackground>>()
            targets.groupBy { it.pdfBackground!!.assetId }.forEach { (assetId, pages) ->
                val asset = notebook.assets.first { it.id == assetId }
                // Pages showing the same source page at the same size share one padded page.
                val keys = pages.map { Triple(it.pdfBackground!!.pageIndex, it.width, it.height) }.distinct()
                val file = File(workspace, "padded.pdf")
                // The stored original, never an unlocked working copy: a padded copy must not carry decrypted content.
                asset.withVerifiedFile(workspace) { source ->
                    val original = try { PDDocument.load(source, memory()) }
                        catch (locked: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw IllegalArgumentException("Password-protected PDF pages keep their shape") }
                    original.use {
                        require(!original.isEncrypted) { "Password-protected PDF pages keep their shape" }
                        PDDocument(memory()).use { output ->
                            val layers = LayerUtility(output)
                            keys.forEach { (index, width, height) ->
                                require(index in 0 until original.numberOfPages) { "PDF page not found" }
                                val page = original.getPage(index)
                                require(((page.rotation % 360) + 360) % 360 == 0) { "Rotated PDF pages keep their shape" }
                                val crop = page.cropBox
                                require(crop.width > 0f && crop.height > 0f) { "Invalid PDF crop box" }
                                val fit = dev.ownednotes.core.Page(width = width, height = height).standardShapeFit()!!
                                val target = PDPage(PDRectangle(fit.width, fit.height)).also(output::addPage)
                                val form = layers.importPageAsForm(original, page)
                                form.setMatrix(AffineTransform()); form.bBox = crop
                                // The old page area: scaled uniformly, centred; PDF y runs upwards.
                                val sx = width * fit.scale / crop.width; val sy = height * fit.scale / crop.height
                                val bottom = fit.height - fit.dy - height * fit.scale
                                PDPageContentStream(output, target).use { content ->
                                    content.transform(Matrix(sx, 0f, 0f, sy, fit.dx - crop.lowerLeftX * sx, bottom - crop.lowerLeftY * sy))
                                    content.drawForm(form)
                                }
                            }
                            output.save(file)
                        }
                    }
                }
                // Small results stay in memory, so an abandoned change leaves no stored file behind.
                val padded = if (file.length() <= PdfAsset.LEGACY_MAX_BYTES) PdfAsset.fromBytes(file.readBytes())
                    else file.inputStream().use { PdfAsset.fromStream(it, owned) }
                file.delete()
                pages.forEach { page ->
                    val index = keys.indexOf(Triple(page.pdfBackground!!.pageIndex, page.width, page.height))
                    result[page.id] = padded to PdfBackground(padded.id, index)
                }
            }
            return result
        } finally {
            // Only this invocation's private scratch directory; never any notebook or original.
            workspace.deleteRecursively()
        }
    }
}
