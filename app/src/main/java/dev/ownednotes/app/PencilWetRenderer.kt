package dev.ownednotes.app

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.PI

/**
 * B01 (P134): live pencil ink drawn with the same graphite grain as finished pencil strokes, so the pencil looks like
 * a pencil while it is being drawn, not only after the pen lifts. Every other wet stroke uses the library renderer.
 * The canvas it is given already maps page units to the screen (the transform is only a hint), so the grain and the
 * outline line up exactly with the finished stroke that replaces it (B21, P137: drawing through the transform again
 * put live pencil in the wrong place and too big on a zoomed or scrolled page).
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
class PencilWetRenderer(private val fallback: CanvasStrokeRenderer = CanvasStrokeRenderer.create()) : CanvasStrokeRenderer {
    companion object {
        /** Tilt (radians from upright) where the lead's side starts to touch the paper, and where it lies flat on it. */
        const val SIDE_START = .75f
        const val SIDE_FLAT = 1.3f
        /**
         * How much wider the mark grows with the pencil on its side: androidx.ink 1.0 holds a tip to twice the brush size
         * however its multipliers combine (measured on the tablet, P137), so the side is that ceiling.
         */
        const val SIDE_WIDTH = 2f
        /** How short the mark gets along the pencil on its side, so side shading is a broad flat band, not a big dot. */
        const val SIDE_LENGTH = .6f

        private fun tilt(target: BrushBehavior.Target, flat: Float) = BrushBehavior.Builder()
            .setSource(BrushBehavior.Source.TILT_IN_RADIANS).setTarget(target)
            .setSourceValueRangeStart(SIDE_START).setSourceValueRangeEnd(SIDE_FLAT).setTargetModifierRangeStart(1f).setTargetModifierRangeEnd(flat)
            .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP).setResponseCurve(EasingFunction.Predefined.EASE_IN_OUT)
            .setResponseTimeMillis(60).build()

        /**
         * B22 (P137): a graphite lead. Upright it is a round point whose width follows pressure; tilted onto its side the
         * point stretches along the pencil into a broad shading mark. Its own id lets the wet renderer tell pencil apart;
         * the id is never saved, only the outlines it makes.
         */
        val family: BrushFamily = BrushFamily.Builder()
            .setCoat(BrushTip.Builder().setScaleX(1f).setScaleY(1f).setCornerRounding(1f).setBehaviors(listOf(
                // Pressure: a light touch leaves a thinner line.
                BrushBehavior.Builder().setSource(BrushBehavior.Source.NORMALIZED_PRESSURE).setTarget(BrushBehavior.Target.SIZE_MULTIPLIER)
                    .setSourceValueRangeStart(0f).setSourceValueRangeEnd(1f).setTargetModifierRangeStart(.55f).setTargetModifierRangeEnd(1.05f)
                    .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP).setResponseCurve(EasingFunction.Predefined.EASE_OUT)
                    .setResponseTimeMillis(20).build(),
                // Tilt: past a normal writing angle the side of the lead lays down a broad stroke.
                tilt(BrushBehavior.Target.WIDTH_MULTIPLIER, SIDE_WIDTH),
                tilt(BrushBehavior.Target.HEIGHT_MULTIPLIER, SIDE_LENGTH),
                // The broad side lies along the pencil.
                BrushBehavior.Builder().setSource(BrushBehavior.Source.ORIENTATION_IN_RADIANS).setTarget(BrushBehavior.Target.ROTATION_OFFSET_IN_RADIANS)
                    .setSourceValueRangeStart(0f).setSourceValueRangeEnd((2 * PI).toFloat()).setTargetModifierRangeStart(0f).setTargetModifierRangeEnd((2 * PI).toFloat())
                    .setSourceOutOfRangeBehavior(BrushBehavior.OutOfRange.CLAMP).build(),
            )).build(), BrushPaint())
            .setClientBrushFamilyId("dev.ownednotes.pencil-v2")
            .build()

        fun isPencil(stroke: InProgressStroke) = stroke.brush?.family?.clientBrushFamilyId == family.clientBrushFamilyId

        /**
         * Orientation as androidx.ink takes it from a MotionEvent: Android's −π..π, 0 pointing up, becomes 0..2π, so
         * strokes rebuilt from saved samples come out the same shape as they were drawn live.
         */
        fun inkOrientation(android: Float): Float = ((android + 2.5f * PI.toFloat()) % (2 * PI.toFloat())).let { if (it < 0) it + 2 * PI.toFloat() else it }
        /** Tilt as androidx.ink takes it: 0 upright to π/2 flat. */
        fun inkTilt(android: Float): Float = android.coerceIn(0f, (PI / 2).toFloat())
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val vertex = MutableVec()
    private val input = StrokeInput()
    private val identity = Matrix()

    /** Running pressure and tilt of the stroke being drawn, so its graphite matches what [PencilGrain.level] gives it once saved. */
    private var measured: InProgressStroke? = null
    private var counted = 0
    private var pressure = 0f
    private var tilt = 0f

    /**
     * Every outline as one path. A single fill with the default winding rule covers a pixel once wherever the outline
     * crosses itself, as the union the finished stroke uses does, so translucent pencil never darkens where it crosses
     * itself, without a path union on every frame.
     */
    private fun outline(stroke: InProgressStroke): Path {
        path.rewind()
        for (coat in 0 until stroke.getBrushCoatCount()) for (o in 0 until stroke.getOutlineCount(coat)) {
            val count = stroke.getOutlineVertexCount(coat, o)
            if (count < 3) continue
            for (v in 0 until count) {
                stroke.populateOutlinePosition(coat, o, v, vertex)
                if (v == 0) path.moveTo(vertex.x, vertex.y) else path.lineTo(vertex.x, vertex.y)
            }
            path.close()
        }
        return path
    }

    private fun level(stroke: InProgressStroke): Int {
        if (measured !== stroke) { measured = stroke; counted = 0; pressure = 0f; tilt = 0f }
        val real = stroke.getRealInputCount()
        if (real < counted) { counted = 0; pressure = 0f; tilt = 0f }
        while (counted < real) {
            stroke.populateInput(input, counted++)
            pressure += if (input.pressure >= 0) input.pressure else .6f
            tilt += input.tiltRadians.coerceAtLeast(0f)
        }
        return if (counted == 0) PencilGrain.DEFAULT else PencilGrain.level(pressure / counted, tilt / counted)
    }

    override fun draw(canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: Matrix, animationProgress: Float) {
        if (!isPencil(inProgressStroke)) return fallback.draw(canvas, inProgressStroke, strokeToScreenTransform, animationProgress)
        val color = inProgressStroke.brush?.colorIntArgb ?: return
        paint.color = color
        paint.shader = PencilGrain.shader(color, level(inProgressStroke))
        canvas.drawPath(outline(inProgressStroke), paint)
    }

    override fun draw(canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: AffineTransform, animationProgress: Float) {
        if (!isPencil(inProgressStroke)) return fallback.draw(canvas, inProgressStroke, strokeToScreenTransform, animationProgress)
        draw(canvas, inProgressStroke, identity, animationProgress)
    }

    override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix, animationProgress: Float) =
        fallback.draw(canvas, stroke, strokeToScreenTransform, animationProgress)

    override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: AffineTransform, animationProgress: Float) =
        fallback.draw(canvas, stroke, strokeToScreenTransform, animationProgress)
}
