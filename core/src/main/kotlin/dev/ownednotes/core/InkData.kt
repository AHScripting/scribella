package dev.ownednotes.core

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * B24 (P140): how much one notebook may hold. The whole notebook stays in memory while it is open, so these are memory
 * budgets (about 200 MB of point data at most), not format limits. Before P140 the preview allowed 16 pages,
 * 250,000 samples and 1,000,000 outline vertices in the whole notebook; such notebooks still save as schemas 1–25.
 */
object NotebookLimits {
    const val PAGES = 2_000
    const val STROKES_PER_PAGE = 10_000
    const val SAMPLES = 4_000_000L
    const val VERTICES = 12_000_000L
    const val ERASER_POINTS = 2_000_000L
    /** One page's point data, in bytes as saved (24 per sample, 8 per outline or eraser point): about a million samples of handwriting with their outlines. */
    const val PAGE_INK_BYTES = 48L * 1024 * 1024
    /** PDF and image files used as backgrounds or placed images, together. */
    const val BACKGROUND_FILES = 64
    const val SCHEMA27_BACKGROUND_BYTES = 64L * 1024 * 1024
    const val BACKGROUND_BYTES = 512L * 1024 * 1024
    const val PDF_PAGES = 2_000
    const val TEXT_BOXES = 20_000
    const val IMAGES = 20_000

    /** The limits of schemas 1–25, which older builds can open. */
    internal const val LEGACY_PAGES = 16
    internal const val LEGACY_SAMPLES = 250_000L
    internal const val LEGACY_VERTICES = 1_000_000L
    internal const val LEGACY_ERASER_POINTS = 250_000L
    internal const val LEGACY_BACKGROUND_FILES = 4
    internal const val LEGACY_BACKGROUND_BYTES = 16L * 1024 * 1024
    internal const val LEGACY_PDF_PAGES = 16
    internal const val LEGACY_TEXT_BOXES = 512
    internal const val LEGACY_IMAGES = 512
}

/**
 * B24 (P140): samples kept in two flat arrays instead of one object each (about half the memory). Read-only, and equal
 * to any other list of the same samples.
 */
internal class PackedSamples(private val values: FloatArray, private val times: IntArray) : AbstractList<Sample>(), RandomAccess {
    override val size get() = times.size
    override fun get(index: Int): Sample {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index")
        val i = index * 5
        return Sample(values[i], values[i + 1], times[index].toLong(), values[i + 2], values[i + 3], values[i + 4])
    }
    companion object {
        /** [samples] packed, or unchanged when already packed or a time does not fit (validation then refuses it). */
        fun of(samples: List<Sample>): List<Sample> {
            if (samples is PackedSamples || samples.isEmpty() || samples.any { it.timeMs !in 0..Int.MAX_VALUE }) return samples
            val values = FloatArray(samples.size * 5); val times = IntArray(samples.size)
            samples.forEachIndexed { n, s -> val i = n * 5; values[i] = s.x; values[i + 1] = s.y; values[i + 2] = s.pressure; values[i + 3] = s.tilt; values[i + 4] = s.orientation; times[n] = s.timeMs.toInt() }
            return PackedSamples(values, times)
        }
    }
}

/** B24 (P140): points kept in one flat array (about a third of the memory). */
internal class PackedPoints(private val values: FloatArray) : AbstractList<Point>(), RandomAccess {
    override val size get() = values.size / 2
    override fun get(index: Int): Point {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index")
        return Point(values[index * 2], values[index * 2 + 1])
    }
    companion object {
        fun of(points: List<Point>): List<Point> {
            if (points is PackedPoints || points.isEmpty()) return points
            val values = FloatArray(points.size * 2)
            points.forEachIndexed { n, p -> values[n * 2] = p.x; values[n * 2 + 1] = p.y }
            return PackedPoints(values)
        }
    }
}

/** This stroke with its point data packed; the same instance when nothing changes. */
internal fun InkStroke.packed(): InkStroke {
    val s = PackedSamples.of(samples)
    val o = outlines.map { PackedPoints.of(it) }
    val e = erasures.map { if (it.points is PackedPoints) it else it.copy(points = PackedPoints.of(it.points)) }
    return if (s === samples && o.indices.all { o[it] === outlines[it] } && e.indices.all { e[it] === erasures[it] }) this else copy(samples = s, outlines = o, erasures = e)
}
internal fun Page.packed(): Page = if (strokes.isEmpty()) this else copy(strokes = strokes.map { it.packed() })
/** B24 (P140): the same notebook (equal to this one) with its point data held compactly in memory. */
fun Notebook.compacted(): Notebook = copy(pages = pages.map { it.packed() })
fun Page.compacted(): Page = packed()
/** Bytes [page]'s point data takes in its package entry; [NotebookLimits.PAGE_INK_BYTES] at most. */
fun inkBytes(stroke: InkStroke): Long = 24L * stroke.samples.size + 8L * stroke.outlines.sumOf { it.size } + 8L * stroke.erasures.sumOf { it.points.size }

/**
 * B24 (P140, schema 26): a page's point data as one binary entry, `pages/<id>.ink`, beside its JSON, which keeps
 * everything else with empty `samples`, `outlines` and eraser `points`. Big-endian:
 * `"OWNI"`, version 1, stroke count; per stroke its sample count and samples (x, y float; time int; pressure, tilt,
 * orientation float: 24 bytes), its outline count and each outline (point count, then x, y floats), its eraser sweep
 * count (equal to the JSON's) and each sweep's points the same way. Floats are stored bit for bit.
 */
internal object InkCodec {
    private const val MAGIC = 0x4F574E49 // "OWNI"
    private const val VERSION = 1

    /** [page] without its point data, for the page JSON. */
    fun skeleton(page: Page): Page = page.copy(strokes = page.strokes.map { stroke ->
        stroke.copy(samples = emptyList(), outlines = emptyList(), erasures = stroke.erasures.map { it.copy(points = emptyList()) })
    })

    fun encode(page: Page): ByteArray {
        var size = 12L
        page.strokes.forEach { stroke ->
            size += 4 + 24L * stroke.samples.size + 4 + stroke.outlines.sumOf { 4 + 8L * it.size } + 4 + stroke.erasures.sumOf { 4 + 8L * it.points.size }
        }
        require(size <= Int.MAX_VALUE - 64) { "Page ink is too large" }
        val buffer = ByteBuffer.allocate(size.toInt())
        buffer.putInt(MAGIC).putInt(VERSION).putInt(page.strokes.size)
        fun points(list: List<Point>) { buffer.putInt(list.size); list.forEach { buffer.putFloat(it.x).putFloat(it.y) } }
        page.strokes.forEach { stroke ->
            buffer.putInt(stroke.samples.size)
            stroke.samples.forEach { buffer.putFloat(it.x).putFloat(it.y).putInt(it.timeMs.toInt()).putFloat(it.pressure).putFloat(it.tilt).putFloat(it.orientation) }
            buffer.putInt(stroke.outlines.size); stroke.outlines.forEach(::points)
            buffer.putInt(stroke.erasures.size); stroke.erasures.forEach { points(it.points) }
        }
        check(!buffer.hasRemaining())
        return buffer.array()
    }

    /** Counts what [decode] has read so far across a notebook, so a damaged file cannot fill the memory first. */
    /** Shared by pages decoded at the same time, so its counts are atomic. */
    class Budget { val samples = AtomicLong(); val vertices = AtomicLong(); val eraserPoints = AtomicLong() }

    /** Fills [skeleton]'s strokes from [bytes], packed. */
    fun decode(skeleton: Page, bytes: ByteArray, budget: Budget): Page {
        val buffer = ByteBuffer.wrap(bytes)
        fun count(each: Int): Int {
            require(buffer.remaining() >= 4) { "Ink data ends early" }
            val n = buffer.getInt()
            require(n >= 0 && n.toLong() * each <= buffer.remaining()) { "Invalid ink data" }
            return n
        }
        fun points(): List<Point> {
            val values = FloatArray(count(8) * 2)
            for (i in values.indices) values[i] = buffer.getFloat()
            return PackedPoints(values)
        }
        require(bytes.size >= 12 && buffer.getInt() == MAGIC && buffer.getInt() == VERSION) { "Unsupported ink data" }
        require(count(12) == skeleton.strokes.size) { "Ink data does not match its page" }
        val strokes = skeleton.strokes.map { stroke ->
            require(stroke.samples.isEmpty() && stroke.outlines.isEmpty() && stroke.erasures.all { it.points.isEmpty() }) { "Point data outside the ink entry" }
            val n = count(24)
            require(budget.samples.addAndGet(n.toLong()) <= NotebookLimits.SAMPLES) { "Notebook exceeds its limits" }
            val values = FloatArray(n * 5); val times = IntArray(n)
            for (i in 0 until n) {
                values[i * 5] = buffer.getFloat(); values[i * 5 + 1] = buffer.getFloat(); times[i] = buffer.getInt()
                values[i * 5 + 2] = buffer.getFloat(); values[i * 5 + 3] = buffer.getFloat(); values[i * 5 + 4] = buffer.getFloat()
            }
            val outlines = List(count(4)) { points().also { require(budget.vertices.addAndGet(it.size.toLong()) <= NotebookLimits.VERTICES) { "Notebook exceeds its limits" } } }
            require(count(4) == stroke.erasures.size) { "Ink data does not match its page" }
            val erasures = stroke.erasures.map { sweep -> sweep.copy(points = points().also { require(budget.eraserPoints.addAndGet(it.size.toLong()) <= NotebookLimits.ERASER_POINTS) { "Notebook exceeds its limits" } }) }
            stroke.copy(samples = if (n == 0) emptyList() else PackedSamples(values, times), outlines = outlines, erasures = erasures)
        }
        require(!buffer.hasRemaining()) { "Unexpected ink data" }
        return skeleton.copy(strokes = strokes)
    }
}
