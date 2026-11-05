package dev.ownednotes.core

import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/** Read-only study adapter metadata. Contains no transcript, bookmark, asset bytes or notebook title.
 * The routing hint is conservative, not proof that handwriting contains no private information.
 * The caller must fully validate a package first; remote reading uses StudyInk only.
 */
object StudyManifest {
    private val json = Json { encodeDefaults = true }

    @Serializable data class Manifest(
        val contractVersion: Int = 2,
        val notebookId: String,
        // Features only (1–25); the on-disk container can independently be compact schema 26.
        val minimumFeatureSchema: Int,
        val pages: List<Entry>,
    )
    @Serializable data class Entry(
        val pageId: String,
        val number: Int,
        val contentSha256: String,
        val handwritingSha256: String,
        val remoteExport: String = "ink-only",
        val pdfBackground: Boolean,
        val imageBackground: Boolean,
        val embeddedImages: Boolean,
        val attachments: Boolean,
        val typedText: Boolean,
        val routing: String,
    )

    /** Hash all decoded page fields, including schema-26 point data, without a giant JSON String. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun contentHash(page: Page): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { json.encodeToStream(page, it) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun build(notebook: Notebook): Manifest = Manifest(
        notebookId = notebook.id,
        minimumFeatureSchema = notebook.schemaVersion(),
        pages = notebook.pages.mapIndexed { index, page ->
            val restricted = page.pdfBackground != null || page.imageBackground != null ||
                page.images.isNotEmpty() || page.attachments.isNotEmpty() || page.textBoxes.isNotEmpty()
            Entry(pageId = page.id, number = index + 1, contentSha256 = contentHash(page),
                handwritingSha256 = contentHash(StudyInk.page(page)), pdfBackground = page.pdfBackground != null,
                imageBackground = page.imageBackground != null, embeddedImages = page.images.isNotEmpty(),
                attachments = page.attachments.isNotEmpty(), typedText = page.textBoxes.isNotEmpty(),
                routing = if (restricted) "restricted-mixed-content" else "handwriting-candidate")
        },
    )

    fun encode(notebook: Notebook): String = json.encodeToString(build(notebook))
}
