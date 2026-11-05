package dev.ownednotes.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local-only boundary: only allowlisted ink and numeric geometry leave the validated notebook. */
object PcInkExport {
    @Serializable data class Manifest(val version: Int = 1, val content: String = "visible-native-ink-svg",
        val notebookId: String, val pages: List<Entry>)
    @Serializable data class Entry(val pageId: String, val number: Int, val handwritingSha256: String,
        val width: Float, val height: Float, val svg: String)

    fun write(notebook: Notebook, output: File) {
        require(notebook.pages.size in 1..2000) { "Unsupported study page count" }
        require(notebook.id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid study notebook identity" }
        require(notebook.pages.all { it.id.matches(Regex("[A-Za-z0-9-]{1,80}")) }) { "Invalid study page identity" }
        require(!output.exists() && output.mkdir()) { "Choose a new study output directory" }
        val hashes = StudyManifest.build(notebook).pages
        val pages = notebook.pages.mapIndexed { index, page ->
            val svg = StudyInk.svg(page).toByteArray(Charsets.UTF_8)
            require(svg.size <= 64 * 1024 * 1024) { "Study ink page is too large" }
            val name = "page-${index + 1}.svg"
            Files.newOutputStream(File(output, name).toPath(), CREATE_NEW).use { it.write(svg) }
            Entry(page.id, index + 1, hashes[index].handwritingSha256, page.width, page.height, name)
        }
        val metadata = Json { encodeDefaults = true }.encodeToString(Manifest(notebookId = notebook.id, pages = pages)).toByteArray(Charsets.UTF_8)
        require(metadata.size <= 1024 * 1024) { "Study manifest is too large" }
        Files.newOutputStream(File(output, "manifest.json").toPath(), CREATE_NEW).use { it.write(metadata) }
    }
}
