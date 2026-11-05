package dev.ownednotes.app

import dev.ownednotes.core.AttachmentAsset
import java.io.File
import java.io.InputStream

/** One exact source snapshot; never closes the caller stream or publishes a failed import. */
internal object OfficeOriginal {
    fun <T> read(cache: File,input: InputStream,extension: String,ownedDirectory: File?,build: (File,AttachmentAsset)->T): T {
        fun render(original: AttachmentAsset): T {
            val source=File.createTempFile("office-source-",extension,cache)
            try {
                source.outputStream().use { original.writeTo(it) }
                return build(source,original)
            } finally { source.delete() }
        }
        return if(ownedDirectory!=null) AttachmentAsset.fromStream(input,ownedDirectory) { render(it) }
        else render(AttachmentAsset.fromBytes(input.readNBytes(AttachmentAsset.MAX_BYTES+1)))
    }
}
