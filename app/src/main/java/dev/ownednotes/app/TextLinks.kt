package dev.ownednotes.app

import android.graphics.Path
import android.graphics.Region
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import dev.ownednotes.core.*
import kotlin.math.ceil

/** Uses the same shaped text as rendering; work happens on a tap, never on the live draw path. */
internal object TextLinks {
    fun hit(page:Page,point:Point):Uri? {
        if(!point.x.isFinite() || !point.y.isFinite())return null
        for(slice in page.paintSlices.asReversed()) {
            if(slice.attachments.any { it.placement.contains(point) })return null
            val box=slice.textBoxes.asReversed().firstOrNull { page.canSee(it.layer) && page.objectOpacity(it.layer)>0f &&
                (it.sticky!=null || (it.style.color ushr 24)!=0L) && it.placement.contains(point) }
            if(box!=null)return if(box.sticky?.collapsed==true || box.style.color ushr 24==0L)null else hitBox(box,point)
            if(slice.images.any { page.canSee(it.layer) && page.objectOpacity(it.layer)>0f && it.placement.contains(point) })return null
        }
        return null
    }
    private fun hitBox(box:TextBox,point:Point):Uri? {
        val local=box.placement.unitPoint(point)
        val x=local.x*box.placement.width;val y=local.y*box.placement.height
        if(x<0f || y<0f || x>=box.placement.width || y>=box.placement.height)return null
        val layout=TextRendering.build(TextRendering.key(box))
        if(y>=layout.height)return null
        val text=SpannableString(layout.text)
        Linkify.addLinks(text,Linkify.WEB_URLS)
        val clip=Region(0,0,ceil(box.placement.width.toDouble()).toInt(),minOf(layout.height,ceil(box.placement.height.toDouble()).toInt()))
        val path=Path();val region=Region()
        for(link in text.getSpans(0,text.length,URLSpan::class.java)) {
            val uri=PdfLinks.webUri(link.url) ?: continue
            path.reset();layout.getSelectionPath(text.getSpanStart(link),text.getSpanEnd(link),path)
            region.setPath(path,clip)
            if(region.contains(x.toInt(),y.toInt()))return uri
        }
        return null
    }
}
