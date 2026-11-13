package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.view.View
import dev.ownednotes.core.*

/** Draft-only formatting samples. They never load PDF, photo or attachment assets. */
internal class PageFormatPreview(context: Context) : View(context) {
    var templateBitmap: android.graphics.Bitmap? = null; set(value) { field=value; invalidate() }
    var draft: Page? = null; set(value) { field=value; invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription="Page format preview" }
    override fun onDraw(c: Canvas) {
        val page=draft ?: return
        if(runCatching {page.paper.validate(page.width,page.height)}.isFailure)return
        val pad=dp(8f);val scale=minOf((width-2*pad)/page.width,(height-2*pad)/page.height)
        if(scale<=0)return
        c.save();c.translate((width-page.width*scale)/2,(height-page.height*scale)/2);c.scale(scale,scale)
        PaperRenderer.draw(c,page,paint,least=1f/scale)
        templateBitmap?.let { c.drawBitmap(it,null,android.graphics.RectF(0f,0f,page.width,page.height),paint.apply { style=Paint.Style.FILL;alpha=255;isFilterBitmap=true }) }
        paint.color=Tokens.GRAPHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=1f/scale
        c.drawRect(0f,0f,page.width,page.height,paint);c.restore()
    }
}

internal class TextStylePreview(context: Context) : View(context) {
    private var layout: StaticLayout?=null
    private var source=""
    private var style=TextStyle()
    init {contentDescription="Text style preview";setBackgroundColor(Color.WHITE)}
    fun show(text: String, value: TextStyle) {source=text.take(500).ifBlank {"The quick brown fox — Universität"};style=value;shape();invalidate()}
    private fun shape(){
        val pixels=((width-2*dp(8f))/resources.displayMetrics.density).toInt().coerceAtLeast(1)
        layout=TextRendering.build(TextRendering.Key(source,style,pixels))
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){shape()}
    override fun onDraw(c:Canvas){c.save();c.translate(dp(8f),dp(8f));c.scale(resources.displayMetrics.density,resources.displayMetrics.density);layout?.draw(c);c.restore()}
}
