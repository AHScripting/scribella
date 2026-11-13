package dev.ownednotes.app

import android.annotation.SuppressLint
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes

/**
 * P616 (board 11): a highlighter whose chisel stands upright, so a highlight starts and ends with a vertical
 * edge. The stock highlighter is the same thin chisel turned 150°, which leans its ends; across a line of text
 * both cover the same height. Saved strokes keep the outlines they were drawn with, so older highlights
 * keep their slanted ends and only new ones are upright.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
object HighlighterBrush {
    /** Width of the chisel relative to its height (the stock chisel's .25, a touch wider for up and down strokes). */
    const val EDGE = .3f
    const val ID = "dev.ownednotes.highlighter-upright"

    // Custom brush families are experimental in androidx.ink 1.0.0; see CalligraphyBrush.
    @SuppressLint("RestrictedApi")
    val family: BrushFamily = BrushFamily.Builder()
        .setCoat(
            BrushTip.Builder().setScaleX(EDGE).setScaleY(1f).setCornerRounding(.3f).setRotationDegrees(0f)
                .setBehaviors(listOf(StockBrushes.predictionFadeOutBehavior)).build(),
            BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD),
        )
        .setClientBrushFamilyId(ID)
        .build()
}
