package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Paint
import dev.ownednotes.core.Page

/** Shared background path for editor, previews, continuous strip and vector PDF export. */
object PaperRenderer {
    /** [least] is the thinnest line (and half the smallest dot) in page units; P634: small previews pass one pixel so the lines stay visible. */
    fun draw(canvas: Canvas, page: Page, paint: Paint, least: Float = 0f) {
        paint.style = Paint.Style.FILL; paint.blendMode = null; paint.color = page.paper.color.toInt()
        canvas.drawRect(0f, 0f, page.width, page.height, paint)
        paint.color = page.paper.guideColor.toInt(); paint.strokeWidth = maxOf(page.paper.weight, least)
        page.paper.guides(page.width, page.height, { x1, y1, x2, y2 ->
            paint.style = Paint.Style.STROKE; canvas.drawLine(x1, y1, x2, y2, paint)
        }, { x, y, radius ->
            paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, maxOf(radius, least / 2f), paint)
        })
        paint.style = Paint.Style.FILL
    }
}
