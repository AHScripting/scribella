package dev.ownednotes.app

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import java.util.ArrayDeque

/** Local original PDF outlines. Notebook indices are resolved at the instant of navigation. */
internal object PdfContents {
    data class Entry(val title: String, val depth: Int, val sourcePage: Int?)
    fun sources(note: Notebook): List<String> = note.pages.mapNotNull { it.pdfBackground?.assetId }.distinct()
    fun target(note: Notebook, assetId: String, entry: Entry): Page? = entry.sourcePage?.let { index ->
        note.pages.firstOrNull { it.pdfBackground?.assetId == assetId && it.pdfBackground?.pageIndex == index }
    }
    /** A chapter includes its nested sections, ending at the next sibling/ancestor chapter. */
    fun pageIds(note:Notebook,assetId:String,entry:Entry?=null,entries:List<Entry> = emptyList()):Set<String> {
        val start=entry?.sourcePage
        if(entry!=null && start==null)return emptySet()
        val position=if(entry==null)-1 else entries.indexOf(entry)
        if(entry!=null && position<0)return emptySet()
        val end=if(entry==null)null else entries.drop(position+1).firstOrNull {it.depth<=entry.depth && (it.sourcePage ?: -1)>start!!}?.sourcePage
        return note.pages.filter {p->p.pdfBackground?.let {b->b.assetId==assetId && (start==null || b.pageIndex>=start) && (end==null || b.pageIndex<end)}==true}.map {it.id}.toSet()
    }
    fun read(context: Context, note: Notebook, assetId: String): List<Entry> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val asset = note.assets.first { it.id == assetId }
        return PdfAccess.withFile(context.cacheDir, asset) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfiumCore(context.applicationContext).newDocument(descriptor).use { document ->
                    val todo = ArrayDeque<Pair<Bookmark, Int>>()
                    document.getTableOfContents().asReversed().forEach { todo.addLast(it to 0) }
                    val entries = mutableListOf<Entry>()
                    while (todo.isNotEmpty()) {
                        require(entries.size < 4096) { "This PDF has too many contents entries to display" }
                        val (bookmark, depth) = todo.removeLast()
                        entries += Entry(bookmark.title.orEmpty().filterNot { it.isISOControl() }.trim().take(500).ifBlank { "Untitled chapter" },
                            depth, bookmark.pageIdx.takeIf { it in 0 until document.getPageCount().toLong() }?.toInt())
                        bookmark.children.asReversed().forEach { todo.addLast(it to depth + 1) }
                    }
                    entries
                }
            }
        }
    }
}
