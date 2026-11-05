package dev.ownednotes.app

import android.graphics.RectF
import dev.ownednotes.core.Page

/** Transient selection, independent of ink/history. Coordinates stay in the current notebook page. */
internal class PdfTextSelection(private val page: Page, private val content: PdfTextContent.Result) {
    private var anchor: IntRange? = null
    var start = 0; private set
    var end = 0; private set
    var boxes: List<RectF> = emptyList(); private set
    val text get() = content.text.substring(start,end)
    fun matches(current: Page?) = current != null && current.id == page.id && current.pdfBackground == page.pdfBackground &&
        current.width == page.width && current.height == page.height

    private fun word(glyph: PdfTextContent.Glyph): IntRange {
        var first=glyph.start; var last=glyph.end
        while(first>0 && !content.text[first-1].isWhitespace()) first--
        while(last<content.text.length && !content.text[last].isWhitespace()) last++
        return first until last
    }
    private fun nearest(x: Float,y: Float): Pair<PdfTextContent.Glyph,Float>? {
        var best: PdfTextContent.Glyph?=null;var distance=Float.POSITIVE_INFINITY
        content.glyphs.forEach { glyph ->
            if(!content.text[glyph.start].isWhitespace()) {
                val box=glyph.box
                val dx=maxOf(box.left-x,0f,x-box.right);val dy=maxOf(box.top-y,0f,y-box.bottom)
                val squared=dx*dx+dy*dy
                if(squared<distance) { best=glyph;distance=squared }
            }
        }
        return best?.let { it to distance }
    }
    fun begin(x:Float,y:Float,tolerance:Float) {
        val found=nearest(x,y)?.takeIf { it.second<=tolerance*tolerance }?.first
        anchor=found?.let(::word)
        if(anchor==null) { start=0;end=0;boxes=emptyList() } else update(anchor!!)
    }
    fun move(x:Float,y:Float) { if(anchor!=null) nearest(x,y)?.first?.let { update(word(it)) } }
    private fun update(target:IntRange) {
        val origin=anchor ?: return
        start=minOf(origin.first,target.first);end=maxOf(origin.last,target.last)+1
        boxes=content.boxes(start,end)
    }
    fun finish(cancel:Boolean=false) { anchor=null;if(cancel) { start=0;end=0;boxes=emptyList() } }
}
