package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import dev.ownednotes.core.Sample
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * P127 (F023), redrawn for B22 (P137): the graphite this app fills pencil outlines with. Graphite catches on the
 * high points of the paper, so a pencil line is speckled paper tooth, dense where the pencil is pressed hard and
 * sparse where it is light or laid on its side. The tooth is fixed to the page (seeded, in page units), so crossing
 * strokes share it and every redraw and export matches. A notebook keeps only the plain outlines; other readers draw
 * pencil as a soft solid line.
 */
object PencilGrain {
    /** Tile size in page units. */
    const val TILE = 128
    /** Bitmap pixels per page unit, so the tooth stays crisp when the page is zoomed in. */
    const val RES = 2
    /** The faintest graphite, as a fraction of the stroke's own opacity, so a light line stays readable. */
    const val LIGHTEST = .1f
    /** Graphite density levels from a light or side-on pencil to a hard-pressed upright one. */
    const val LEVELS = 6
    private const val SIZE = TILE * RES
    private const val LIGHT = .38f
    private const val DARK = .92f
    /** Softness of the tooth's edge: how far either side of the density threshold graphite fades in. */
    private const val EDGE = .18f

    /** Fraction of the tooth that holds graphite at [level]. */
    fun density(level: Int) = LIGHT + (DARK - LIGHT) * level.coerceIn(0, LEVELS - 1) / (LEVELS - 1f)

    /** Level for a pencil pressed at [pressure] 0–1 and [tilt] radians from upright (0 = upright, π/2 = flat). */
    fun level(pressure: Float, tilt: Float): Int {
        val side = smooth(.75f, 1.25f, tilt)
        val amount = (.4f + .6f * pressure.coerceIn(0f, 1f)) * (1 - .45f * side)
        return ((amount - LIGHT) / (DARK - LIGHT) * (LEVELS - 1)).roundToInt().coerceIn(0, LEVELS - 1)
    }

    /** Level from a saved stroke's typical pressure and tilt, looking at no more than 64 of its samples. */
    fun level(samples: List<Sample>): Int {
        if (samples.isEmpty()) return level(.6f, 0f)
        val step = maxOf(1, samples.size / 64)
        var pressure = 0f; var tilt = 0f; var count = 0
        var i = 0
        while (i < samples.size) { val s = samples[i]; pressure += s.pressure; tilt += s.tilt.coerceIn(0f, 1.5708f); count++; i += step }
        return level(pressure / count, tilt / count)
    }

    /** The level menus and cursors show: an ordinary upright pencil. */
    val DEFAULT get() = level(.6f, 0f)

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /**
     * Height of the paper tooth at each bitmap pixel, 0–1 and evenly spread (every value equally common), so the share
     * of paper that takes graphite is exactly the level's density. Three octaves: fine tooth, its clumps and soft patches.
     */
    internal val coverage: FloatArray by lazy {
        val random = Random(137)
        fun lattice(cells: Int) = FloatArray(cells * cells) { random.nextFloat() }
        val octaves = listOf(TILE / 1 to .5f, TILE / 2 to .3f, TILE / 8 to .2f).map { (cells, weight) -> Triple(cells, weight, lattice(cells)) }
        val height = FloatArray(SIZE * SIZE) { i ->
            val u = (i % SIZE + .5f) / SIZE; val v = (i / SIZE + .5f) / SIZE
            octaves.fold(0f) { sum, (cells, weight, grid) ->
                val x = u * cells; val y = v * cells
                val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
                val fx = x - x0; val fy = y - y0
                fun g(dx: Int, dy: Int) = grid[((y0 + dy) % cells) * cells + (x0 + dx) % cells]
                val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
                val top = g(0, 0) + (g(1, 0) - g(0, 0)) * sx
                val bottom = g(0, 1) + (g(1, 1) - g(0, 1)) * sx
                sum + weight * (top + (bottom - top) * sy)
            }
        }
        // Rank the heights so they are evenly spread.
        val order = height.indices.sortedBy { height[it] }
        FloatArray(SIZE * SIZE).also { out -> order.forEachIndexed { rank, index -> out[index] = (rank + .5f) / out.size } }
    }

    /** Graphite at [level] for one pixel of tooth [c]: full where the tooth stands above the threshold, fading at its edge. */
    internal fun alpha(c: Float, level: Int): Float {
        val threshold = 1 - density(level)
        return LIGHTEST + (1 - LIGHTEST) * smooth(threshold - EDGE, threshold + EDGE, c)
    }

    private val shaders = object : LinkedHashMap<Long, BitmapShader>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BitmapShader>) = size > 12
    }
    private val bitmaps = object : LinkedHashMap<Long, Bitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>) = size > 12
    }
    private fun key(rgb: Int, level: Int) = (rgb.toLong() shl 8) or level.coerceIn(0, LEVELS - 1).toLong()

    private fun tile(rgb: Int, level: Int): Bitmap = bitmaps.getOrPut(key(rgb, level)) {
        val table = IntArray(256) { (255 * alpha((it + .5f) / 256, level)).roundToInt().coerceIn(0, 255) }
        val pixels = IntArray(SIZE * SIZE) { i -> (table[(coverage[i] * 256).toInt().coerceIn(0, 255)] shl 24) or rgb }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /**
     * Graphite in [color]'s RGB at [level], in page units; the paint's own alpha still applies on top. Shared and cached,
     * so don't change its matrix. Draw with bitmap filtering on, so the tooth stays soft when zoomed.
     */
    fun shader(color: Int, level: Int = DEFAULT): Shader = synchronized(this) {
        val rgb = color and 0xffffff
        shaders.getOrPut(key(rgb, level)) {
            BitmapShader(tile(rgb, level), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(1f / RES, 1f / RES) })
            }
        }
    }

    /** A new grain shader for previews drawn [scale] pixels per page unit. */
    fun scaled(color: Int, scale: Float, level: Int = DEFAULT): Shader = synchronized(this) {
        BitmapShader(tile(color and 0xffffff, level), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(scale / RES, scale / RES) })
        }
    }
}
