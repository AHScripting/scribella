package dev.ownednotes.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.ownednotes.core.NotebookLibrary
import java.io.File
import java.io.IOException
import java.util.Locale

/** User-requested copies only. Run on the repository IO queue; never replace a destination document. */
internal object BatchPdfExport {
    data class Result(val exported: Int, val failed: List<String>, val packages: Int = 0, val includeNative: Boolean = false) {
        val message get() = (if (includeNative) "$exported PDFs and $packages editable notebooks exported" else "$exported PDF${if (exported == 1) "" else "s"} exported") +
            if (failed.isEmpty()) "." else "; ${failed.size} failed. ${failed.first()}"
    }

    fun run(context: Context, tree: Uri, library: NotebookLibrary, ids: List<String>, cache: File, includeNative: Boolean = false): Result {
        require(ids.isNotEmpty() && ids.size == ids.distinct().size)
        val resolver = context.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        val names = mutableSetOf<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        (resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("Cannot read the export folder")).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0).orEmpty().lowercase(Locale.ROOT)
        }
        var exported = 0
        var packages = 0
        val failed = mutableListOf<String>()
        fun copy(staged: File, title: String, extension: String, mime: String) {
            val stem = exportFileName(title).trim('.').ifEmpty { "Notebook" }
            var suffix = 1
            var name = "$stem.$extension"
            while (name.lowercase(Locale.ROOT) in names) { suffix++; name = "$stem ($suffix).$extension" }
            names += name.lowercase(Locale.ROOT)
            val created = DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: throw IOException("Cannot create $name")
            try {
                (resolver.openOutputStream(created, "wt") ?: throw IOException("Cannot write $name")).use { output ->
                    staged.inputStream().use { it.copyTo(output) }
                }
                resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) names += it.getString(0).orEmpty().lowercase(Locale.ROOT)
                }
            } catch (e: Exception) {
                // Only this iteration's newly created incomplete copy can be removed.
                val cleaned = runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
                throw IOException((e.message ?: "Cannot write $name") + if (cleaned) "" else " (an incomplete copy may remain)", e)
            }
        }
        ids.forEach { id ->
            val notebook = try { library.read(id) } catch (e: Exception) {
                failed += "$id: ${e.message ?: "Cannot read notebook"}"
                return@forEach
            }
            var pdf: File? = null
            try {
                pdf = PdfExport.create(notebook, cache, includeReviewedHandwriting = true)
                copy(pdf, notebook.title, "pdf", "application/pdf")
                exported++
            } catch (e: Exception) {
                failed += "${notebook.title} (PDF): ${e.message ?: "Cannot export"}"
            } finally { pdf?.delete() }
            if (includeNative) {
                var native: File? = null
                try {
                    native = File.createTempFile("notes-batch-", ".inknote", cache)
                    native.outputStream().use { dev.ownednotes.core.NotebookPackage.write(notebook, it) }
                    copy(native, notebook.title, "inknote", "application/zip")
                    packages++
                } catch (e: Exception) {
                    failed += "${notebook.title} (editable): ${e.message ?: "Cannot export"}"
                } finally { native?.delete() }
            }
        }
        return Result(exported, failed, packages, includeNative)
    }
}
