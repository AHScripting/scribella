package dev.ownednotes.app

import android.graphics.RectF
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.PdfAsset
import dev.ownednotes.core.Point
import dev.ownednotes.core.Sample
import dev.ownednotes.core.ShapeRecognizer
import dev.ownednotes.core.validate
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P570: highlights already in an imported PDF become the app's own highlighter ink on the same pages.
 * P572: underlines, strike-outs, squiggly underlines and freehand ink drawings come in too, as plain pen ink.
 *
 * Android's PDF renderer never draws annotations, so without this a marked-up PDF opened here looks unmarked.
 * Each Highlight annotation's marked text runs (QuadPoints, or its Rect when it has none) become one straight
 * highlighter stroke per run, in the annotation's colour; the other text markings become thin pen lines along each
 * run, and each path of an Ink annotation becomes one pen stroke at the annotation's line width. They are ordinary
 * strokes: erasable, recolourable, exported. Sticky notes, typed comments, form fields and other kinds are only
 * counted, so the import report can say they were left out; their contents are never read.
 * The original PDF is never changed. Reading markings can never fail an import: any problem leaves the pages plain.
 */
internal object PdfHighlights {
    /** Bounded work for a hostile or enormous file; anything past these is reported as left out. */
    const val MAX_TOTAL = 20_000
    const val MAX_PER_PAGE = 1_000
    /** Points kept from one ink path; longer paths are thinned evenly. */
    const val MAX_PATH_POINTS = 5_000
    /** The app's own default highlighter colour, for highlights that state none. */
    const val DEFAULT_COLOR = 0xfff9d43aL
    /** Ink and text markings that state no colour are drawn in this red, the usual reviewing colour. */
    const val DEFAULT_PEN_COLOR = 0xffe0302aL

    /** Something drawn from the PDF, in page units (y down). */
    sealed interface Shape
    /** A marked run in page units (y down), with the direction the text runs and its colour. */
    data class Mark(val band: RectF, val vertical: Boolean, val color: Long) : Shape
    /** A pen line through [points], [width] page units wide, with the annotation's opacity. */
    data class Line(val points: List<Point>, val width: Float, val color: Long, val opacity: Float = 1f) : Shape

    /** Annotation kinds that stay out, as the import report names them. */
    enum class Kind(val one: String, val many: String) {
        STICKY_NOTE("sticky note", "sticky notes"),
        TYPED_COMMENT("typed comment", "typed comments"),
        FORM_FIELD("form field", "form fields"),
        OTHER("other PDF marking", "other PDF markings"),
    }

    /** What the import brought ([highlights], [penMarks]) and what it left out: [unreadable] markings and other kinds. */
    data class Result(val notebook: Notebook, val highlights: Int, val penMarks: Int, val unreadable: Int, val notCarried: Map<Kind, Int> = emptyMap()) {
        val added get() = highlights + penMarks
    }

    /** Adds every readable marking to the PDF pages of [notebook] that show [asset]. Never throws. */
    fun apply(cache: File, notebook: Notebook, asset: PdfAsset): Result = runCatching {
        val targets = notebook.pages.withIndex().filter { it.value.pdfBackground?.assetId == asset.id && it.value.strokes.isEmpty() }
        if (targets.isEmpty()) return Result(notebook, 0, 0, 0)
        val reading = PdfAccess.withFile(cache, asset) { file -> read(file, targets.map { it.value.pdfBackground!!.pageIndex }.toSet(), cache) }
        var highlights = 0; var penMarks = 0; var leftOut = reading.unreadable
        val pages = notebook.pages.toMutableList()
        targets.forEach { (position, page) ->
            val found = reading.found[page.pdfBackground!!.pageIndex].orEmpty()
            if (found.isEmpty()) return@forEach
            val strokes = found.mapNotNull { item ->
                if (highlights + penMarks >= MAX_TOTAL) { leftOut++; return@mapNotNull null }
                val scaleX = page.width / item.pageWidth; val scaleY = page.height / item.pageHeight
                when (val shape = item.shape) {
                    is Mark -> {
                        val band = RectF(shape.band.left * scaleX, shape.band.top * scaleY, shape.band.right * scaleX, shape.band.bottom * scaleY)
                        band.intersect(0f, 0f, page.width, page.height)
                        stroke(Mark(band, shape.vertical, shape.color))?.also { highlights++ }
                    }
                    is Line -> {
                        val points = shape.points.map { Point((it.x * scaleX).coerceIn(0f, page.width), (it.y * scaleY).coerceIn(0f, page.height)) }
                        penStroke(Line(points, shape.width * (scaleX + scaleY) / 2f, shape.color, shape.opacity))?.also { penMarks++ }
                    }
                } ?: run { leftOut++; null }
            }
            if (strokes.isNotEmpty()) pages[position] = page.copy(strokes = strokes)
        }
        val result = notebook.copy(pages = pages)
        // A file whose markings would make the notebook invalid keeps its pages plain rather than failing the import.
        if (runCatching { result.validate() }.isFailure) Result(notebook, 0, 0, highlights + penMarks + leftOut, reading.notCarried)
        else Result(result, highlights, penMarks, leftOut, reading.notCarried)
    }.getOrElse { Result(notebook, 0, 0, 0) }

    private data class Found(val shape: Shape, val pageWidth: Float, val pageHeight: Float)
    private class Reading(val found: Map<Int, List<Found>>, val unreadable: Int, val notCarried: Map<Kind, Int>)

    /** PDF annotation subtypes this brings in. */
    private const val HIGHLIGHT = PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
    private const val UNDERLINE = PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
    private const val STRIKEOUT = PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
    private const val SQUIGGLY = PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
    private const val INK = "Ink"
    private val TEXT_MARKINGS = setOf(HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY)
    /** Kinds with nothing of their own to show: links, and the pop-up windows that belong to comments. */
    private val UNSEEN = setOf("Link", "Popup")

    /** Markings on the given zero-based pages, in display-oriented page units of each page's crop box. */
    private fun read(file: File, wanted: Set<Int>, cache: File): Reading {
        val workspace = File.createTempFile("pdf-highlights-", "", cache)
        check(workspace.delete() && workspace.mkdir()) { "Cannot create PDF workspace" }
        try {
            return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace)).use { document ->
                val found = mutableMapOf<Int, List<Found>>(); val notCarried = mutableMapOf<Kind, Int>()
                var total = 0; var unreadable = 0
                wanted.sorted().forEach { index ->
                    if (index !in 0 until document.numberOfPages || total >= MAX_TOTAL) return@forEach
                    val page = document.getPage(index)
                    val annotations = runCatching { page.annotations }.getOrNull() ?: return@forEach
                    val shapes = mutableListOf<Found>()
                    annotations.forEach { annotation ->
                        val subtype = runCatching { annotation.subtype }.getOrNull()
                        if (subtype == null || subtype in UNSEEN || annotation.isHidden || annotation.isNoView) return@forEach
                        val drawn = when (subtype) {
                            in TEXT_MARKINGS -> runCatching { runs(page, annotation, subtype).toList() }.getOrNull()
                            INK -> if (runCatching { inkWidth(annotation) }.getOrDefault(1f) == 0f) return@forEach // drawn with no width: shows nothing
                                else runCatching { ink(page, annotation) }.getOrNull()
                            else -> {
                                val kind = when (subtype) { "Text" -> Kind.STICKY_NOTE; "FreeText" -> Kind.TYPED_COMMENT; "Widget" -> Kind.FORM_FIELD; else -> Kind.OTHER }
                                notCarried[kind] = (notCarried[kind] ?: 0) + 1
                                return@forEach
                            }
                        }
                        if (drawn.isNullOrEmpty()) { unreadable++; return@forEach }
                        val room = maxOf(MAX_PER_PAGE - shapes.size, 0)
                        if (drawn.size > room) unreadable += drawn.size - room
                        shapes += drawn.take(room)
                    }
                    if (shapes.isNotEmpty()) { found[index] = shapes; total += shapes.size }
                }
                Reading(found, unreadable, notCarried)
            }
        } finally { workspace.deleteRecursively() }
    }

    /** Maps PDF user space on [page] to display-oriented page units, y down (the inverse of SourcePdfExport.overlayTransform). */
    private class Placement(page: PDPage) {
        private val crop = page.cropBox
        private val rotation = ((page.rotation % 360) + 360) % 360
        val valid = crop.width > 0f && crop.height > 0f && rotation in listOf(0, 90, 180, 270)
        private val turned = rotation == 90 || rotation == 270
        val displayWidth = if (turned) crop.height else crop.width
        val displayHeight = if (turned) crop.width else crop.height
        fun place(x: Float, y: Float): Point {
            val u = x - crop.lowerLeftX; val v = y - crop.lowerLeftY
            val (dx, up) = when (rotation) {
                90 -> v to crop.width - u
                180 -> crop.width - u to crop.height - v
                270 -> crop.height - v to u
                else -> u to v
            }
            return Point(dx, displayHeight - up)
        }
    }

    private fun runs(page: PDPage, annotation: PDAnnotation, subtype: String): Sequence<Found> {
        val placement = Placement(page)
        if (!placement.valid) return emptySequence()
        val place = placement::place
        val color = colorOf(annotation, if (subtype == HIGHLIGHT) DEFAULT_COLOR else DEFAULT_PEN_COLOR)
        val quads = (annotation as? PDAnnotationTextMarkup)?.quadPoints?.takeIf { it.size >= 8 && it.size % 8 == 0 }
        val corners: List<List<Point>> = if (quads != null) quads.toList().chunked(8).map { quad -> List(4) { place(quad[it * 2], quad[it * 2 + 1]) } }
            else annotation.rectangle?.let { r -> listOf(listOf(place(r.lowerLeftX, r.upperRightY), place(r.upperRightX, r.upperRightY),
                place(r.lowerLeftX, r.lowerLeftY), place(r.upperRightX, r.lowerLeftY))) } ?: emptyList()
        return corners.asSequence().mapNotNull { quad ->
            if (quad.any { !it.x.isFinite() || !it.y.isFinite() }) return@mapNotNull null
            val shape = if (subtype == HIGHLIGHT) {
                val band = RectF(quad.minOf { it.x }, quad.minOf { it.y }, quad.maxOf { it.x }, quad.maxOf { it.y })
                if (band.width() <= 0f || band.height() <= 0f) return@mapNotNull null
                // Quads list the top edge first (Acrobat and most writers): its direction is the text's direction.
                val along = Point(quad[1].x - quad[0].x, quad[1].y - quad[0].y)
                val vertical = if (along.x == 0f && along.y == 0f) band.height() > band.width() * 3f else abs(along.y) > abs(along.x)
                Mark(band, vertical, color)
            } else textLine(quad, subtype, color) ?: return@mapNotNull null
            Found(shape, placement.displayWidth, placement.displayHeight)
        }
    }

    /**
     * The pen line an underline, strike-out or squiggly draws on one marked run (display units). The quad's first two
     * points are the top edge, in the text's direction; the other two are the bottom edge, in either order.
     */
    internal fun textLine(quad: List<Point>, subtype: String, color: Long): Line? {
        val a = quad[0]; val b = quad[1]
        var ax = b.x - a.x; var ay = b.y - a.y
        val top = hypot(ax, ay)
        if (!(top > 0f)) return null
        ax /= top; ay /= top
        val bottom = listOf(quad[2], quad[3]).sortedBy { it.x * ax + it.y * ay }
        val start = bottom[0]; val end = bottom[1]
        val length = (end.x - start.x) * ax + (end.y - start.y) * ay
        // Across the text, from the bottom edge toward the top edge.
        var nx = (a.x + b.x - start.x - end.x) / 2f; var ny = (a.y + b.y - start.y - end.y) / 2f
        val height = hypot(nx, ny)
        if (!(length > 0f) || !(height > 0f)) return null
        nx /= height; ny /= height
        fun at(s: Float, up: Float) = Point(start.x + ax * s + nx * up, start.y + ay * s + ny * up)
        val width = (height / 14f).coerceIn(.5f, 50f)
        return when (subtype) {
            STRIKEOUT -> Line(listOf(at(0f, height * .5f), at(length, height * .5f)), width, color)
            SQUIGGLY -> {
                val count = (length / (height / 5f)).toInt().coerceIn(2, 2_000)
                Line(List(count + 1) { i -> at(length * i / count, height * (if (i % 2 == 0) .04f else .16f)) }, (width * .8f).coerceAtLeast(.5f), color)
            }
            else -> Line(listOf(at(0f, height * .08f), at(length, height * .08f)), width, color)
        }
    }

    /** Each path of an Ink annotation as one line, at the annotation's border width and opacity. */
    private fun ink(page: PDPage, annotation: PDAnnotation): List<Found> {
        val placement = Placement(page)
        if (!placement.valid) return emptyList()
        val width = inkWidth(annotation)
        val list = annotation.cosObject.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray ?: return emptyList()
        val color = colorOf(annotation, DEFAULT_PEN_COLOR)
        val opacity = annotation.cosObject.getFloat(COSName.CA, 1f).takeIf { it.isFinite() }?.coerceIn(.1f, 1f) ?: 1f
        return (0 until minOf(list.size(), MAX_PER_PAGE)).mapNotNull { i ->
            val numbers = (list.getObject(i) as? COSArray)?.toFloatArray() ?: return@mapNotNull null
            if (numbers.size < 2 || numbers.any { !it.isFinite() }) return@mapNotNull null
            val all = List(numbers.size / 2) { placement.place(numbers[it * 2], numbers[it * 2 + 1]) }
            val step = (all.size + MAX_PATH_POINTS - 1) / MAX_PATH_POINTS
            val points = if (step <= 1) all else all.filterIndexed { index, _ -> index % step == 0 } + all.last()
            Found(Line(points, width, color, opacity), placement.displayWidth, placement.displayHeight)
        }
    }

    /** An ink annotation's line width: its border style's /W, else its /Border array's third number, else 1. */
    private fun inkWidth(annotation: PDAnnotation): Float {
        val style = annotation.cosObject.getDictionaryObject(COSName.BS) as? COSDictionary
        val width = (style?.getDictionaryObject(COSName.W) as? COSNumber)?.floatValue()
            ?: (annotation.cosObject.getDictionaryObject(COSName.BORDER) as? COSArray)?.takeIf { it.size() >= 3 }?.let { (it.getObject(2) as? COSNumber)?.floatValue() }
            ?: 1f
        return if (width.isFinite()) width.coerceAtLeast(0f) else 1f
    }

    /** Opaque RGB as stored on a stroke; grey, RGB and CMYK annotation colours are understood. */
    internal fun colorOf(annotation: PDAnnotation, fallback: Long = DEFAULT_COLOR): Long {
        // The raw /C numbers: the Android PDFBox port has no CMYK colour space to build a PDColor from.
        val c = runCatching { (annotation.cosObject.getDictionaryObject(COSName.C) as? COSArray)?.toFloatArray() }.getOrNull() ?: return fallback
        if (c.any { !it.isFinite() }) return fallback
        val rgb = when (c.size) {
            1 -> floatArrayOf(c[0], c[0], c[0])
            3 -> c
            4 -> floatArrayOf((1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3]))
            else -> return fallback
        }.map { (it.coerceIn(0f, 1f) * 255).roundToInt().toLong() }
        return 0xff000000L or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    /** One straight highlighter stroke covering [mark], sized like the editor's own text-following highlights. */
    internal fun stroke(mark: Mark): InkStroke? {
        val band = mark.band
        if (band.width() <= 0f || band.height() <= 0f) return null
        val length = if (mark.vertical) band.height() else band.width()
        val thickness = if (mark.vertical) band.width() else band.height()
        val count = (length / 16f).toInt().coerceIn(4, 60)
        val samples = List(count) { i ->
            val t = i / (count - 1f)
            if (mark.vertical) Sample(band.centerX(), band.top + band.height() * t, i * 4L, .5f)
            else Sample(band.left + band.width() * t, band.centerY(), i * 4L, .5f)
        }
        fun build(width: Float): Stroke {
            val batch = MutableStrokeInputBatch()
            samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
            return Stroke(brush(mark.color, width), batch)
        }
        val target = (thickness * 1.12f).coerceIn(1f, 200f)
        val probe = build(target)
        val covered = across(probe, mark.vertical)
        if (!(covered > 0f)) return null
        val width = (target * target / covered).coerceIn(1f, 200f)
        val stroke = if (abs(width - target) < .01f) probe else build(width)
        val outlines = InkCanvas.outlinesOf(stroke)
        if (outlines.isEmpty()) return null
        return InkStroke(color = mark.color, width = width, brush = InkStroke.HIGHLIGHTER, samples = samples, outlines = outlines)
    }

    /** One uniform-width pen stroke along [line], drawn like the app's own ballpoint. */
    internal fun penStroke(line: Line): InkStroke? {
        if (line.points.isEmpty() || line.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val width = line.width.takeIf { it.isFinite() }?.coerceIn(.5f, 50f) ?: return null
        // A single point is a dot: give it a hair of length so the pen draws it.
        val path = if (line.points.size == 1) listOf(line.points[0], Point(line.points[0].x + .01f, line.points[0].y)) else line.points
        val samples = ShapeRecognizer.densify(path, 8f).take(MAX_PATH_POINTS * 2).mapIndexed { i, p -> Sample(p.x, p.y, i * 4L, .5f) }
        // Smoothing off: the PDF's points are already final, and the pen's usual smoothing would round off sharp corners.
        val style = PenStyle(color = line.color or 0xff000000L, width = width, kind = PenKind.BALLPOINT, opacity = line.opacity.coerceIn(.1f, 1f), smoothing = 0)
        val batch = MutableStrokeInputBatch()
        samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
        val outlines = InkCanvas.outlinesOf(Stroke(InkCanvas.brushFor(style), batch))
        if (outlines.isEmpty()) return null
        return InkStroke(color = style.inkColor, width = width, brush = InkStroke.BALLPOINT, samples = samples, outlines = outlines)
    }

    private fun brush(color: Long, width: Float) = Brush.createWithColorIntArgb(HighlighterBrush.family,
        (color.toInt() and 0xffffff) or ((255 * InkStroke.HIGHLIGHTER_OPACITY).roundToInt() shl 24), width, .1f)

    /** How far the stroke's shape reaches across its direction of travel. */
    private fun across(stroke: Stroke, vertical: Boolean): Float {
        val shape = stroke.shape; val point = MutableVec()
        var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE
        for (group in 0 until shape.getRenderGroupCount()) for (outline in 0 until shape.getOutlineCount(group))
            for (i in 0 until shape.getOutlineVertexCount(group, outline)) {
                shape.populateOutlinePosition(group, outline, i, point)
                val value = if (vertical) point.x else point.y
                low = min(low, value); high = max(high, value)
            }
        return high - low
    }
}
