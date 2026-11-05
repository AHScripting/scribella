package dev.ownednotes.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Existing notebook coordinates are PDF points (72 per inch). Display-only, never resizes ink. */
internal object RulerScale {
    val ratios = listOf(0,1,10,100)
    const val POINTS_PER_CM = 72f / 2.54f
    data class Ticks(val pixelsPerCm: Float, val majorCm: Float, val minorPixels: Float) {
        fun centimetres(pixels: Float) = pixels / pixelsPerCm
    }
    fun ticks(pageScale: Float, ratio: Int, density: Float): Ticks {
        require(pageScale.isFinite() && pageScale>0 && ratio in ratios && ratio>0 && density.isFinite() && density>0)
        val pixelsPerCm = pageScale * POINTS_PER_CM / ratio
        val wantedCm = 56f * density / pixelsPerCm
        val magnitude = 10.0.pow(floor(log10(wantedCm.toDouble()))).toFloat()
        val step = listOf(1f,2f,5f,10f).first { it*magnitude>=wantedCm } * magnitude
        return Ticks(pixelsPerCm,step,step*pixelsPerCm/5f)
    }
    fun label(value: Float):String = java.math.BigDecimal.valueOf(value.toDouble()).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
