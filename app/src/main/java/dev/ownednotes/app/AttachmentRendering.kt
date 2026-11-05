package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.ownednotes.core.AttachmentAsset
import dev.ownednotes.core.FileAttachment
import dev.ownednotes.core.Page

/** Draws attached-file cards; matches SvgRenderer's card so exports and the editor agree. */
internal object AttachmentRendering {
    private const val CARD = 0xfff6f8fb.toInt(); private const val BORDER = 0xffc5cfda.toInt(); private const val ICON = 0xff3f6b94.toInt()
    private const val NAME = 0xff172a3a.toInt(); private const val SIZE = 0xff5b6b7b.toInt()

    fun drawPage(canvas: Canvas, page: Page, files: List<AttachmentAsset>) {
        if (page.attachments.isEmpty()) return
        val sizes = files.associate { it.id to it.size }
        page.attachments.forEach { card -> draw(canvas, card, sizes[card.assetId]?.let(FileAttachment::sizeText).orEmpty()) }
    }

    fun draw(canvas: Canvas, card: FileAttachment, sizeText: String) {
        val p = card.placement
        val unit = minOf(p.height, p.width / 3f)
        val pad = unit * .16f; val icon = unit - 2 * pad
        val saved = canvas.save()
        try {
            canvas.translate(p.x, p.y)
            canvas.rotate(p.degrees, p.width / 2, p.height / 2)
            canvas.clipRect(0f, 0f, p.width, p.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val box = RectF(1f, 1f, p.width - 1f, p.height - 1f)
            paint.style = Paint.Style.FILL; paint.color = CARD
            canvas.drawRoundRect(box, unit * .14f, unit * .14f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = BORDER
            canvas.drawRoundRect(box, unit * .14f, unit * .14f, paint)
            val top = (p.height - icon) / 2
            paint.style = Paint.Style.FILL; paint.color = ICON
            canvas.drawRoundRect(RectF(pad, top, pad + icon, top + icon), icon * .14f, icon * .14f, paint)
            val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xffffffff.toInt(); textSize = icon * .28f; textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText(card.label, pad + icon / 2, p.height / 2 + icon * .1f, label)
            val textX = pad * 2 + icon
            val room = (p.width - textX - pad).coerceAtLeast(1f)
            val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = NAME; textSize = unit * .24f; typeface = Typeface.SANS_SERIF }
            canvas.drawText(TextUtils.ellipsize(card.name, name, room, TextUtils.TruncateAt.MIDDLE).toString(), textX, p.height / 2 - unit * .04f, name)
            val size = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SIZE; textSize = unit * .19f; typeface = Typeface.SANS_SERIF }
            canvas.drawText(sizeText, textX, p.height / 2 + unit * .26f, size)
        } finally { canvas.restoreToCount(saved) }
    }

    /** The topmost card under a page point, honoring rotation. */
    fun hit(page: Page, x: Float, y: Float): FileAttachment? = page.attachments.lastOrNull { card ->
        val p = card.placement
        val r = Math.toRadians(-p.degrees.toDouble())
        val dx = x - (p.x + p.width / 2); val dy = y - (p.y + p.height / 2)
        val lx = (dx * Math.cos(r) - dy * Math.sin(r)).toFloat(); val ly = (dx * Math.sin(r) + dy * Math.cos(r)).toFloat()
        kotlin.math.abs(lx) <= p.width / 2 && kotlin.math.abs(ly) <= p.height / 2
    }
}
