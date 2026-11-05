package dev.ownednotes.app

import dev.ownednotes.core.Notebook
import dev.ownednotes.core.NotebookPackage
import java.io.File
import java.io.InputStream

/** Shared entry point for library multi-import; caller owns the source stream. */
object NotebookImport {
    const val OFFICE_PDF_HELP = "This file can't be converted here. Open it in Word or PowerPoint, choose Save as PDF or Print → PDF, then import the PDF."
    private fun office(convert: () -> Result): Result = try { convert() }
        catch (error: java.io.IOException) {
            if (Thread.currentThread().isInterrupted) throw error
            throw java.io.IOException(OFFICE_PDF_HELP, error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(OFFICE_PDF_HELP, error)
        } catch (error: IllegalStateException) {
            throw IllegalStateException(OFFICE_PDF_HELP, error)
        }

    data class Result(val notebook: Notebook, val report: ImportReport? = null)

    fun read(cache: File, input: InputStream, name: String,ownedDirectory: File? = null): Result {
        if (name.substringAfterLast('.').equals("pdf", ignoreCase = true)) {
            val (notebook, report) = PdfSupport.importWithReport(cache, input, name.substringBeforeLast('.'),ownedDirectory)
            return Result(notebook, report)
        }
        if (name.substringAfterLast('.').equals("pptx",ignoreCase=true)) return office { PptxImport.read(cache,input,name,ownedDirectory) }
        if (name.substringAfterLast('.').equals("docx",ignoreCase=true)) return office { DocxImport.read(cache,input,name,ownedDirectory) }
        if (name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("doc", "ppt", "docm", "pptm", "pps", "ppsx"))
            throw IllegalArgumentException(OFFICE_PDF_HELP)
        return Result(ownedDirectory?.let { NotebookPackage.read(input,it) } ?: NotebookPackage.read(input))
    }
}
