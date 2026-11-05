package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import dev.ownednotes.core.Page
import dev.ownednotes.core.canSee
import dev.ownednotes.core.TextAlignment
import dev.ownednotes.core.TextBox
import dev.ownednotes.core.TextStyle
import kotlin.math.ceil

/** Layout copies may normalize line breaks; source text, IDs and styles are never changed. */
internal object TextRendering {
    data class Key(val text: String, val style: TextStyle, val width: Int)
    fun key(box: TextBox) = Key(if(box.sticky?.collapsed==true) "" else box.text, box.style, ceil(box.placement.width.toDouble()).toInt().coerceIn(1,100000))
    fun build(key: Key): StaticLayout {
        val style = key.style
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = style.size
            color = style.color.toInt()
            typeface = Typeface.create(Typeface.create(style.font.cssFamily,Typeface.NORMAL),style.weight,style.italic)
            isUnderlineText = style.underline
        }
        val text = key.text.replace("\r\n","\n").replace('\r','\n')
        return StaticLayout.Builder.obtain(text,0,text.length,paint,key.width)
            .setAlignment(when(style.alignment) {
                TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
            })
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false).setLineSpacing(0f,1.2f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }
    /** No layout creation on the live draw path; the caller supplies a pre-shaped immutable layout. */
    fun draw(canvas: Canvas, box: TextBox, layout: StaticLayout) {
        val p = box.placement
        val saved = canvas.save()
        try {
            canvas.translate(p.x,p.y)
            canvas.rotate(p.degrees,p.width/2,p.height/2)
            canvas.clipRect(0f,0f,p.width,p.height)
            box.sticky?.let { note ->
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=note.color.toInt() }
                canvas.drawRect(0f,0f,p.width,p.height,paint)
                if(note.collapsed) {
                    val fold=minOf(p.width,p.height)*.3f
                    paint.color=box.style.color.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1f
                    canvas.drawPath(android.graphics.Path().apply {moveTo(p.width-fold,0f);lineTo(p.width-fold,fold);lineTo(p.width,fold)},paint)
                }
            }
            if(box.sticky?.collapsed!=true)layout.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
    /** IO export/preview path; the cache dies with this page render. */
    fun drawPage(canvas: Canvas, page: Page) {
        val layouts = mutableMapOf<Key,StaticLayout>()
        page.textBoxes.filter { page.canSee(it.layer) }.forEach { box -> val key=key(box); canvas.withObjectOpacity(page, box.layer) { draw(canvas,box,layouts.getOrPut(key) { build(key) }) } }
    }
}
