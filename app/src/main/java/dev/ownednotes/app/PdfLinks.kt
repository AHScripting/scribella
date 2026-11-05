package dev.ownednotes.app

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.ownednotes.core.*
import io.legere.pdfiumandroid.PdfiumCore
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Local PDF annotations only; independent of ink, OCR and the save queue. */
internal object PdfLinks {
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "notes-pdf-links").apply { isDaemon = true }
    }
    data class Link(val bounds: RectF, val sourcePage: Int?, val uri: String?)
    fun read(context: Context, notebook: Notebook, page: Page): List<Link> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val background = page.pdfBackground ?: return emptyList()
        val asset = notebook.assets.firstOrNull { it.id == background.assetId } ?: return emptyList()
        return PdfAccess.withFile(context.cacheDir, asset) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfiumCore(context.applicationContext).newDocument(descriptor).use { document ->
                    requireNotNull(document.openPage(background.pageIndex)).use { pdf ->
                        val w = ceil(page.width * 4.0).toInt(); val h = ceil(page.height * 4.0).toInt()
                        pdf.getPageLinks().take(4096).mapNotNull { link ->
                            val mapped = pdf.mapRectToDevice(0, 0, w, h, 0, link.bounds)
                            val box = RectF(mapped.left * page.width / w, mapped.top * page.height / h,
                                mapped.right * page.width / w, mapped.bottom * page.height / h).apply { sort() }
                            if (box.isEmpty || !box.intersect(0f, 0f, page.width, page.height)) null
                            else {
                                // This binding may populate destPageIdx even on URI actions.
                                val destination = link.destPageIdx?.takeIf { link.uri.isNullOrBlank() && it in 0 until document.getPageCount() }
                                Link(box, destination, link.uri)
                            }
                        }
                    }
                }
            }
        }
    }
    fun target(notebook: Notebook, source: Page, link: Link): Page? = link.sourcePage?.let { index ->
        notebook.pages.firstOrNull { it.pdfBackground?.assetId == source.pdfBackground?.assetId &&
            it.pdfBackground?.pageIndex == index }
    }
    fun webUri(value: String?): Uri? = value?.takeIf { it.length <= 8192 }?.let { raw ->
        runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        }
    }
}
