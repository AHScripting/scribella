package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.RectF
import android.text.StaticLayout
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.Page
import dev.ownednotes.core.TextBox
import dev.ownednotes.core.Sample
import dev.ownednotes.core.TextLineSnap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** P042: finds the printed line a highlighter stroke covers on a rendered PDF page, in page coordinates. */
internal object TextHighlight {
    /** B41: detect the locally visible printed content, including native text, in a bounded stroke crop.
     * No notebook images, ink, OCR or attachment content is included. Layouts are already cached by the editor. */
    fun pageBands(page: Page, pdf: Bitmap?, samples: List<Sample>, width: Float,
        layout: (TextBox) -> StaticLayout?): List<RectF> {
        if (page.textBoxes.isEmpty()) return pdf?.let { bands(it, page.width, page.height, samples, width) } ?: emptyList()
        if (samples.size < 2) return emptyList()
        val scale = min(1f, 2048f / max(page.width, page.height))
        val margin = (TextLineSnap.maxLineHeight(width * scale, ceil(page.height * scale).toInt()) + 8f) / scale + width
        val left = floor((samples.minOf { it.x } - margin) * scale) / scale
        val top = floor((samples.minOf { it.y } - margin) * scale) / scale
        val right = min(page.width, samples.maxOf { it.x } + margin)
        val bottom = min(page.height, samples.maxOf { it.y } + margin)
        val x = max(0f, left); val y = max(0f, top)
        val w = ceil((right - x) * scale).toInt(); val h = ceil((bottom - y) * scale).toInt()
        if (w <= 0 || h <= 0 || w.toLong() * h > 8_000_000L) return emptyList()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale); canvas.translate(-x, -y)
            canvas.clipRect(0f, 0f, page.width, page.height)
            pdf?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width, page.height), null) }
            page.textBoxes.forEach { box -> layout(box)?.let { TextRendering.draw(canvas, box, it) } }
            val local = samples.map { it.copy(x = it.x - x, y = it.y - y) }
            return bands(bitmap, w / scale, h / scale, local, width, page.height)
                .map { RectF(it).apply { offset(x, y) } }
        } finally { bitmap.recycle() }
    }
    /**
     * [bitmap] is the PDF page rendered to fill a [pageWidth] x [pageHeight] page. Returns the inked band of the text
     * line under [samples] (a highlighter of [width] page units), or null when the stroke should stay freehand.
     */
    fun band(bitmap: Bitmap, pageWidth: Float, pageHeight: Float, samples: List<Sample>, width: Float): RectF? {
        if (samples.size < 2 || bitmap.isRecycled || pageWidth <= 0f || pageHeight <= 0f) return null
        val sx = bitmap.width / pageWidth; val sy = bitmap.height / pageHeight
        val points = samples.map { Point(it.x * sx, it.y * sy) }
        val rasterWidth = width * sy
        // Only the pixels the detector can reach are copied: the stroke's span plus a line height around it.
        val margin = max(rasterWidth * 2.5f, 12f) + rasterWidth + 8f
        val left = floor(points.minOf { it.x } - margin).toInt().coerceIn(0, bitmap.width)
        val top = floor(points.minOf { it.y } - margin).toInt().coerceIn(0, bitmap.height)
        val right = ceil(points.maxOf { it.x } + margin).toInt().coerceIn(0, bitmap.width)
        val bottom = ceil(points.maxOf { it.y } + margin).toInt().coerceIn(0, bitmap.height)
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0 || w.toLong() * h > 4_000_000L) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        val band = TextLineSnap.find(points.map { Point(it.x - left, it.y - top) }, rasterWidth, w, h) { x, y -> isInk(pixels[y * w + x]) } ?: return null
        return RectF((band.left + left) / sx, (band.top + top) / sy, (band.right + 1 + left) / sx, (band.bottom + 1 + top) / sy)
    }

    /**
     * B04 (P132): every printed line under [samples], in page coordinates. One line for a stroke along a line, as [band];
     * each coloured line when the stroke colours a region over several lines. Empty means the stroke stays freehand.
     */
    fun bands(bitmap: Bitmap, pageWidth: Float, pageHeight: Float, samples: List<Sample>, width: Float,
        referenceHeight: Float = pageHeight): List<RectF> {
        if (samples.size < 2 || bitmap.isRecycled || pageWidth <= 0f || pageHeight <= 0f) return emptyList()
        val sx = bitmap.width / pageWidth; val sy = bitmap.height / pageHeight
        val points = samples.map { Point(it.x * sx, it.y * sy) }
        val rasterWidth = width * sy
        // The pixels the detector can reach: the stroke's span plus the tallest line around it.
        val referencePixels = ceil(referenceHeight * sy).toInt()
        val margin = TextLineSnap.maxLineHeight(rasterWidth, referencePixels) + rasterWidth + 8f
        val left = floor(points.minOf { it.x } - margin).toInt().coerceIn(0, bitmap.width)
        val top = floor(points.minOf { it.y } - margin).toInt().coerceIn(0, bitmap.height)
        val right = ceil(points.maxOf { it.x } + margin).toInt().coerceIn(0, bitmap.width)
        val bottom = ceil(points.maxOf { it.y } + margin).toInt().coerceIn(0, bitmap.height)
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0 || w.toLong() * h > 8_000_000L) return emptyList()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        // Line heights are judged against the whole page, not the copied window.
        return TextLineSnap.findLines(points.map { Point(it.x - left, it.y - top) }, rasterWidth, w, h, referencePixels) { x, y -> isInk(pixels[y * w + x]) }
            .map { RectF((it.left + left) / sx, (it.top + top) / sy, (it.right + 1 + left) / sx, (it.bottom + 1 + top) / sy) }
    }
    /** The straight highlight replacing a freehand stroke: its samples, nominal width and Ink stroke (for canonical outlines). */
    class Straight(val samples: List<Sample>, val width: Float, val stroke: Stroke)

    /**
     * A horizontal highlighter stroke along [band]'s centre that covers its height with a little margin. [brushFor]
     * builds the highlighter brush for a nominal width; the chisel tip's covered height is measured, not assumed.
     */
    fun straighten(band: RectF, drawn: List<Sample>, brushFor: (Float) -> Brush): Straight? {
        if (drawn.isEmpty() || band.width() <= 0f || band.height() <= 0f) return null
        val pressure = drawn.map { it.pressure }.sorted()[drawn.size / 2].coerceIn(.2f, 1f)
        val count = max(16, (band.width() / 8f).toInt()).coerceAtMost(400)
        val samples = List(count) { i -> Sample(band.left + band.width() * i / (count - 1), band.centerY(), i * 4L, pressure) }
        fun build(width: Float): Stroke {
            val batch = MutableStrokeInputBatch()
            samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
            return Stroke(brushFor(width), batch)
        }
        val target = band.height() * 1.12f
        val probe = build(target)
        val covered = heightOf(probe)
        if (!(covered > 0f)) return null
        val width = (target * target / covered).coerceIn(1f, 200f)
        val stroke = if (abs(width - target) < .01f) probe else build(width)
        return Straight(samples, width, stroke)
    }

    private fun heightOf(stroke: Stroke): Float {
        val shape = stroke.shape
        val point = MutableVec()
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (group in 0 until shape.getRenderGroupCount()) for (outline in 0 until shape.getOutlineCount(group))
            for (i in 0 until shape.getOutlineVertexCount(group, outline)) {
                shape.populateOutlinePosition(group, outline, i, point); top = min(top, point.y); bottom = max(bottom, point.y)
            }
        return bottom - top
    }

    /** Printed ink: opaque and clearly darker than paper (coloured text counts; pale tints and scan noise do not). */
    fun isInk(color: Int): Boolean {
        if (Color.alpha(color) < 128) return false
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 < 160_000
    }
}
