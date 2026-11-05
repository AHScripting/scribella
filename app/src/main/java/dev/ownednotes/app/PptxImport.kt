package dev.ownednotes.app

import dev.ownednotes.core.*
import java.io.File
import java.io.InputStream

/** Genuine local static slide import; original source remains an attached file, outside handwriting-only output. */
internal object PptxImport {
    fun read(cache: File,input: InputStream,name: String,ownedDirectory: File? = null): NotebookImport.Result =
        OfficeOriginal.read(cache,input,".pptx",ownedDirectory) { source,original ->
            OfficeArchive.open(source).use { archive ->
                val index=OfficeDocumentIndex.read(archive)
                require(index.kind == OfficeDocumentIndex.Kind.SLIDES) { "This file is not a supported PowerPoint presentation" }
                val rendered=PptxRender(archive,index).render(cache)
                try {
                    val title=name.substringBeforeLast('.').trim().take(500).ifEmpty { "Imported presentation" }
                    val slides=rendered.file.inputStream().use { PdfSupport.import(cache,it,title,ownedDirectory) }
                    val sourcePage=Page(textBoxes=listOf(TextBox("Original presentation",ImagePlacement(60f,60f,880f,64f),TextStyle(size=28f,weight=700))),
                        attachments=listOf(FileAttachment(original.id,FileAttachment.cleanName(name),"application/vnd.openxmlformats-officedocument.presentationml.presentation",FileAttachment.cardPlacement(1000f,1400f,1))))
                    val notebook=slides.copy(pages=slides.pages+sourcePage,attachmentAssets=listOf(original)).also { it.validate() }
                    NotebookImport.Result(notebook,ImportReport("PowerPoint presentation",listOf("${index.slides.size} rendered slides", "the unchanged original presentation on a final source-file page"),rendered.warnings))
                } finally { rendered.file.delete() }
            }
    }
}
