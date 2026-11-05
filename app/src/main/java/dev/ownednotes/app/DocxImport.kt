package dev.ownednotes.app

import dev.ownednotes.core.*
import java.io.File
import java.io.InputStream

/** Original-preserving local Word import. Caller retains source stream ownership. */
internal object DocxImport {
    fun read(cache: File,input: InputStream,name: String,ownedDirectory: File? = null): NotebookImport.Result =
        OfficeOriginal.read(cache,input,".docx",ownedDirectory) { source,original ->
            OfficeArchive.open(source).use { archive ->
                val index=OfficeDocumentIndex.read(archive)
                require(index.kind==OfficeDocumentIndex.Kind.WORD) { "This file is not a supported Word document" }
                val rendered=DocxRender(archive,index).render(cache)
                try {
                    val title=name.substringBeforeLast('.').trim().take(500).ifEmpty { "Imported document" }
                    val pages=rendered.file.inputStream().use { PdfSupport.import(cache,it,title,ownedDirectory) }
                    val sourcePage=Page(textBoxes=listOf(TextBox("Original Word document",ImagePlacement(60f,60f,880f,64f),TextStyle(size=28f,weight=700))),
                        attachments=listOf(FileAttachment(original.id,FileAttachment.cleanName(name),"application/vnd.openxmlformats-officedocument.wordprocessingml.document",FileAttachment.cardPlacement(1000f,1400f,1))))
                    val note=pages.copy(pages=pages.pages+sourcePage,attachmentAssets=listOf(original)).also { it.validate() }
                    NotebookImport.Result(note,ImportReport("Word document",listOf("${rendered.pages} locally rendered pages","the unchanged original document on a final source-file page"),rendered.warnings))
                } finally { rendered.file.delete() }
            }
    }
}
