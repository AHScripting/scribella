package dev.ownednotes.app

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.*
import dev.ownednotes.core.Page

// P173: PenTypeChoices (the pen tool tray) now lives in ToolArt.kt.

/** Draws only selected visible native ink. It has no PDF/image/file asset access. */
internal class HandwritingSourcePreview(context: Context, page: Page, ids: Set<String>) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink=page.drawnStrokes.filter { it.id in ids }.map { stroke -> Triple(InkGeometry.visible(stroke),stroke,page.opacityOf(stroke)) }
    private val bounds=RectF().apply { ink.forEach { (path,_,_) -> val r=RectF();path.computeBounds(r,true);if(!r.isEmpty)union(r) } }
    init {contentDescription="Handwriting source preview, handwriting only";setBackgroundColor(Color.WHITE)}
    override fun onDraw(c: Canvas) {
        if(bounds.isEmpty)return
        val pad=dp(12f);val scale=minOf((width-2*pad)/bounds.width(),(height-2*pad)/bounds.height()).coerceAtLeast(.0001f)
        c.save();c.translate((width-bounds.width()*scale)/2,(height-bounds.height()*scale)/2);c.scale(scale,scale);c.translate(-bounds.left,-bounds.top)
        ink.forEach { (path,stroke,opacity) -> InkGeometry.draw(c,stroke,paint,opacity,path) };c.restore()
    }
}
