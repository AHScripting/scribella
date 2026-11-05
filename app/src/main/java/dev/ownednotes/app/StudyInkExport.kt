package dev.ownednotes.app

import dev.ownednotes.core.Notebook
import dev.ownednotes.core.StudyInk
import dev.ownednotes.core.StudyManifest
import dev.ownednotes.core.validate
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.json.JSONArray

/** Explicit remote-readable copy. Never include the native package or source assets. */
object StudyInkExport {
    fun create(source: Notebook, cache: File): File {
        source.validate()
        val metadata = StudyManifest.build(source)
        val destination = File.createTempFile("handwriting-only-", ".zip", cache)
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                source.pages.forEachIndexed { index, page ->
                    val safe = Notebook(id = source.id, title = "Handwriting", pages = listOf(StudyInk.page(page)))
                    val png = PageImageExport.create(safe, page.id, cache)
                    try {
                        zip.putNextEntry(ZipEntry("page-${index + 1}.png"))
                        png.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } finally { png.delete() }
                }
                val pages = JSONArray()
                metadata.pages.forEach { pages.put(JSONObject().put("pageId", it.pageId).put("number", it.number)
                    .put("handwritingSha256", it.handwritingSha256).put("image", "page-${it.number}.png")) }
                val manifest = JSONObject().put("contractVersion", 1).put("content", "visible-native-ink-only")
                    .put("notebookId", source.id).put("pages", pages)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return destination
        } catch (error: Throwable) { destination.delete(); throw error }
    }
}
