package dev.ownednotes.app

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/** P097 (F024): a flat nib held at a fixed 45° angle, so a stroke is wide across the nib and thin along it. */
@OptIn(ExperimentalInkCustomBrushApi::class)
object CalligraphyBrush {
    const val NIB_DEGREES = 45f
    /** Thickness of the nib edge relative to its width. */
    const val EDGE = .2f
    // androidx.ink 1.0.0 marks custom brush families experimental and library-restricted. The saved outlines,
    // not this brush, are what a notebook keeps, so a later library change can only alter new strokes.
    @SuppressLint("RestrictedApi")
    val family: BrushFamily = BrushFamily.Builder()
        .setCoat(BrushTip.Builder().setScaleX(1f).setScaleY(EDGE).setCornerRounding(.3f).setRotationDegrees(NIB_DEGREES).build(), BrushPaint())
        .build()

    /** Width of a nib stroke moving in direction [radians] (screen y down), as a fraction of the full nib. */
    fun widthFactor(radians: Double) = EDGE + (1 - EDGE) * abs(sin(radians - Math.toRadians(NIB_DEGREES.toDouble()))).toFloat()

    /** Draws [path] in small pieces whose width follows the nib, for the pen previews. */
    fun draw(canvas: Canvas, path: Path, paint: Paint, width: Float, steps: Int = 24) {
        val measure = PathMeasure(path, false); val length = measure.length
        if (length <= 0f) return
        val segment = Path(); val tan = FloatArray(2)
        val cap = paint.strokeCap; paint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until steps) {
            measure.getPosTan(length * (i + .5f) / steps, null, tan)
            paint.strokeWidth = width * widthFactor(atan2(tan[1].toDouble(), tan[0].toDouble()))
            segment.rewind()
            measure.getSegment(length * i / steps, length * (i + 1) / steps + .5f, segment, true)
            canvas.drawPath(segment, paint)
        }
        paint.strokeCap = cap
    }
}
