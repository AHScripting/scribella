package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.UUID
import kotlin.math.hypot

@Serializable data class Point(val x: Float, val y: Float)
@Serializable data class Sample(val x: Float, val y: Float, val timeMs: Long, val pressure: Float, val tilt: Float = 0f, val orientation: Float = 0f)
/** Linear map of a round eraser footprint: x'=a*x+c*y, y'=b*x+d*y. */
@Serializable data class EraserBasis(val a: Float, val b: Float, val c: Float, val d: Float) {
    fun inverse(point: Point): Point {
        val determinant = a.toDouble() * d - b.toDouble() * c
        return Point(((d * point.x.toDouble() - c * point.y.toDouble()) / determinant).toFloat(),
            ((a * point.y.toDouble() - b * point.x.toDouble()) / determinant).toFloat())
    }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class EraserSweep(val points: List<Point>, val radius: Float,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val basis: EraserBasis? = null)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val color: Long = 0xff182636,
    val width: Float = 4f,
    val brush: String = "androidx-pressure-pen-v1",
    val samples: List<Sample>,
    // Canonical solid-fill geometry. Raw samples remain available for future editing.
    val outlines: List<List<Point>>,
    // Subtract round-capped, round-joined sweeps from this stroke only.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val erasures: List<EraserSweep> = emptyList(),
    /** P098 (schema 19): strokes sharing a group id on one page are selected and edited together. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val group: String? = null,
    /** P100 (schema 20): id of the page layer this stroke is on; null exactly when its page has no layers. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val layer: String? = null,
    /** P101 (schema 21): a locked stroke is drawn but can't be selected, moved or erased until unlocked. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
    /** Schema 29: explicit identity of a snapped/tool-created shape; never inferred for older ink. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shapeKind: ShapeRecognizer.Kind? = null,
    /** Schema 31: independent solid interior of an explicitly closed shape. Null means no fill. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fillColor: Long? = null,
    /** Schema 35: finger-followed notebook/page target attached to this ink. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val link: NotebookLink? = null,
    /** Schema 36: local clip moment captured at pen-down, never exported to a remote ink reader. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val audio: AudioLink? = null,
) {
    val isHighlighter get() = brush == HIGHLIGHTER
    val isExtendedPen get() = brush == BALLPOINT || brush == DASHED
    val isTape get() = brush == TAPE
    /** P097 (schema 18): flat nib at a fixed angle; like every pen, its outlines are the authoritative shape. */
    val isCalligraphy get() = brush == CALLIGRAPHY
    /** P127 (schema 25): pencil; its outlines are the authoritative shape, and the graphite grain is only how this app fills them. */
    val isPencil get() = brush == PENCIL
    /** P089 (schema 15): pen-type strokes may be see-through; the alpha byte of [color] is the whole stroke's opacity. */
    val isTranslucent get() = (color ushr 24) != 255L
    companion object {
        /** P085 study tape (schema 13): an opaque rounded strip; its single outline is the whole shape, samples are its corners. */
        const val TAPE = "study-tape-v1"
        const val PEN = "androidx-pressure-pen-v1"
        const val BALLPOINT = "androidx-ballpoint-v1"
        const val DASHED = "androidx-dashed-line-v1"
        const val CALLIGRAPHY = "calligraphy-v1"
        const val PENCIL = "pencil-v1"
        /** Chisel-tip highlighter (schema 5). Colour stays opaque; renderers draw the whole stroke at [HIGHLIGHTER_OPACITY]. */
        const val HIGHLIGHTER = "androidx-highlighter-v1"
        const val HIGHLIGHTER_OPACITY = .38f
        /** Lowest pen opacity alpha (10 %). */
        const val MIN_ALPHA = 26L
    }
}
@Serializable data class PdfBackground(val assetId: String, val pageIndex: Int)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ImageBackground(val assetId: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val orientation: Int = 1) {
    /** EXIF orientation, mapping normalized raw pixels to normalized upright coordinates. */
    fun normalizedTransform(): List<Float> {
        require(orientation in 1..8) { "Invalid image orientation" }
        return when (orientation) {
            1 -> listOf(1f, 0f, 0f, 1f, 0f, 0f)
            2 -> listOf(-1f, 0f, 0f, 1f, 1f, 0f)
            3 -> listOf(-1f, 0f, 0f, -1f, 1f, 1f)
            4 -> listOf(1f, 0f, 0f, -1f, 0f, 1f)
            5 -> listOf(0f, 1f, 1f, 0f, 0f, 0f)
            6 -> listOf(0f, 1f, -1f, 0f, 1f, 0f)
            7 -> listOf(0f, -1f, -1f, 0f, 1f, 1f)
            else -> listOf(0f, -1f, 1f, 0f, 0f, 1f)
        }
    }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Page(val id: String = UUID.randomUUID().toString(), val width: Float = 1000f, val height: Float = 1400f, val strokes: List<InkStroke> = emptyList(), val pdfBackground: PdfBackground? = null, @EncodeDefault(EncodeDefault.Mode.NEVER) val paper: PaperStyle = PaperStyle.DEFAULT, @EncodeDefault(EncodeDefault.Mode.NEVER) val bookmark: String? = null, @EncodeDefault(EncodeDefault.Mode.NEVER) val imageBackground: ImageBackground? = null, @EncodeDefault(EncodeDefault.Mode.NEVER) val images: List<ImageObject> = emptyList(), @EncodeDefault(EncodeDefault.Mode.NEVER) val textBoxes: List<TextBox> = emptyList(), @EncodeDefault(EncodeDefault.Mode.NEVER) val attachments: List<FileAttachment> = emptyList(),
    /** P091 (schema 17): depth of this page's bookmark in the outline, 1 = top level; always 1 without a bookmark. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outlineLevel: Int = 1,
    /** P100 (schema 20): ink layers, bottom first; empty means one implicit layer. Strokes are stored in layer order. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val layers: List<Layer> = emptyList(),
    /** P119 schema23: explicit reviewed text, tied to unchanged visible native ink. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reviewedInk: List<ReviewedInk> = emptyList()) {
    val paintSlices: List<Page> by lazy { objectLayerSlices(this) }
    val editableImages: List<ImageObject> by lazy { images.filter { canEdit(it) } }
    val editableTexts: List<TextBox> by lazy { textBoxes.filter { canEdit(it) } }
    val activeReviewedInk: List<ReviewedInk> by lazy { reviewedInk.filter { it.matches(this) } }
    /** Strokes on visible layers, in drawing order; the same list as [strokes] when nothing is hidden. */
    val drawnStrokes: List<InkStroke> by lazy {
        val hidden = layers.filter { it.hidden }.map { it.id }.toSet()
        if (hidden.isEmpty()) strokes else strokes.filterNot { it.layer in hidden }
    }
    private val layerOpacity: Map<String, Float> by lazy { layers.filter { it.opacity < 1f }.associate { it.id to it.opacity } }
    /** P102 (F078): how opaque [stroke]'s layer draws it; 1 on a page without see-through layers. */
    fun opacityOf(stroke: InkStroke): Float = if (layerOpacity.isEmpty()) 1f else layerOpacity[stroke.layer] ?: 1f
    companion object { const val MAX_OUTLINE_LEVEL = 3; const val MAX_LAYERS = 8 }
}
/** P100 (F071): a named ink layer of one page; a hidden layer is not drawn, exported or picked. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Layer(val id: String = UUID.randomUUID().toString(), val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hidden: Boolean = false,
    /** P101 (F075, schema 21): a locked layer's ink can't be selected or erased, and new ink never lands on it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
    /** P102 (F078, schema 22): the whole layer's ink is drawn at this opacity, e.g. to trace over it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val opacity: Float = 1f) {
    companion object {
        fun validName(name: String) = name.length in 1..40 && name == name.trim() && name.none { it.isISOControl() }
        const val MIN_OPACITY = .1f
    }
}
data class Notebook(val id: String = UUID.randomUUID().toString(), val title: String = "Pen test", val pages: List<Page> = listOf(Page()), val assets: List<PdfAsset> = emptyList(), val imageAssets: List<ImageAsset> = emptyList(), val attachmentAssets: List<AttachmentAsset> = emptyList())
val Page.hasBackground get() = pdfBackground != null || imageBackground != null

private val ID = Regex("[a-zA-Z0-9-]{1,80}")

/**
 * [checked] tells which strokes are already known valid (B23, P137: an edit keeps every other stroke as it was, and
 * checking all their samples again after each pen lift held the pen back); they still count towards the limits.
 */
fun Notebook.validate(checked: (InkStroke) -> Boolean = { false }) = validate(checked) { false }

/** [checkedPage]: B24 (P140), pages whose strokes already passed (saved unchanged); like [checked], they still count towards the limits. */
internal fun Notebook.validate(checked: (InkStroke) -> Boolean, checkedPage: (Page) -> Boolean) {
    fun Float.coordinate() = isFinite() && kotlin.math.abs(this) <= 1_000_000f
    fun validId(id: String) = ID.matches(id)
    require(validId(id) && title.length <= 500) { "Invalid notebook metadata" }
    require(pages.size in 1..NotebookLimits.PAGES && pages.map { it.id }.distinct().size == pages.size) { "Invalid page list" }
    require(assets.size <= NotebookLimits.BACKGROUND_FILES && assets.map { it.id }.distinct().size == assets.size && assets.sumOf { it.size.toLong() } <= NotebookLimits.BACKGROUND_BYTES) { "Invalid PDF assets" }
    require(assets.all { asset -> pages.any { it.pdfBackground?.assetId == asset.id } }) { "Unreferenced PDF asset" }
    require(imageAssets.map { it.id }.distinct().size == imageAssets.size && assets.size + imageAssets.size <= NotebookLimits.BACKGROUND_FILES &&
        assets.sumOf { it.size.toLong() } + imageAssets.sumOf { it.size.toLong() } <= NotebookLimits.BACKGROUND_BYTES) { "Invalid combined background assets" }
    require(imageAssets.all { asset -> pages.any { asset.id in it.imageAssetIds } }) { "Unreferenced image asset" }
    require(attachmentAssets.size <= AttachmentAsset.MAX_FILES && attachmentAssets.map { it.id }.distinct().size == attachmentAssets.size &&
        attachmentAssets.sumOf { it.size.toLong() } <= AttachmentAsset.MAX_TOTAL_BYTES) { "Attached files exceed preview limits" }
    require(attachmentAssets.all { asset -> pages.any { asset.id in it.attachmentAssetIds } }) { "Unreferenced attached file" }
    require(pages.sumOf { it.attachments.size } <= FileAttachment.MAX_TOTAL && pages.all { page -> page.attachments.size <= FileAttachment.MAX_PER_PAGE &&
        page.attachments.map { it.id }.distinct().size == page.attachments.size && page.attachments.all { card -> attachmentAssets.any { it.id == card.assetId } } }) {
        "Too many, duplicate or missing attachment cards"
    }
    var points = 0L
    var vertices = 0L
    var eraserPoints = 0L
    var imageCount = 0
    var textCount = 0
    var textCharacters = 0L
    pages.forEach { page ->
        textCount += page.textBoxes.size
        val pageCharacters = page.textBoxes.sumOf { it.text.length.toLong() }
        textCharacters += pageCharacters
        require(page.textBoxes.size <= 128 && textCount <= NotebookLimits.TEXT_BOXES && pageCharacters <= 1_000_000L && textCharacters <= 8_000_000L &&
            page.textBoxes.map { it.id }.distinct().size == page.textBoxes.size) { "Text boxes exceed preview limits or have duplicate IDs" }
        page.pdfBackground?.let { require(it.pageIndex in 0 until NotebookLimits.PDF_PAGES && assets.any { asset -> asset.id == it.assetId }) { "Invalid PDF background" } }
        page.imageBackground?.let { background ->
            require(page.pdfBackground == null && background.orientation in 1..8 && imageAssets.any { it.id == background.assetId }) { "Invalid image background" }
        }
        imageCount += page.images.size
        require(page.images.size <= 128 && imageCount <= NotebookLimits.IMAGES && page.images.map { it.id }.distinct().size == page.images.size) { "Too many or duplicate image objects" }
        page.images.forEach { image ->
            require(validId(image.id) && image.orientation in 1..8 && imageAssets.any { it.id == image.assetId }) { "Invalid image object reference" }
        }
        require(validId(page.id) && page.width.isFinite() && page.height.isFinite() && page.width in 1f..100_000f && page.height in 1f..100_000f) { "Invalid page" }
        page.paper.validate(page.width, page.height)
        require(page.bookmark == null || (page.bookmark.length in 1..120 && page.bookmark == page.bookmark.trim() &&
            page.bookmark.none { it.isISOControl() })) { "Bookmark names must be 1–120 characters without surrounding whitespace or controls" }
        require(page.outlineLevel in 1..Page.MAX_OUTLINE_LEVEL && (page.bookmark != null || page.outlineLevel == 1)) { "Outline level must be 1–3 on a bookmarked page" }
        require(!page.hasBackground || page.paper == PaperStyle.DEFAULT) { "Background pages retain their original paper" }
        require(page.reviewedInk.size <= ReviewedInk.MAX_PER_PAGE && page.reviewedInk.map { it.id }.distinct().size == page.reviewedInk.size &&
            page.reviewedInk.sumOf { it.text.length } <= 65_536) { "Too much or duplicate reviewed handwriting text" }
        require(page.strokes.size <= NotebookLimits.STROKES_PER_PAGE && page.strokes.map { it.id }.distinct().size == page.strokes.size) { "Invalid strokes" }
        require(page.layers.size <= Page.MAX_LAYERS && page.layers.map { it.id }.distinct().size == page.layers.size &&
            page.layers.all { validId(it.id) && Layer.validName(it.name) && it.opacity.isFinite() && it.opacity in Layer.MIN_OPACITY..1f }) { "Invalid layers" }
        val order = page.layers.mapIndexed { index, layer -> layer.id to index }.toMap()
        require(page.images.all { it.layer == null || it.layer in order } && page.textBoxes.all { it.layer == null || it.layer in order }) { "Object on a missing layer" }
        require(page.strokes.all { if (page.layers.isEmpty()) it.layer == null else it.layer in order }) { "Stroke on a missing layer" }
        require(page.strokes.zipWithNext().all { (a, b) -> (order[a.layer] ?: 0) <= (order[b.layer] ?: 0) }) { "Strokes out of layer order" }
        // B24 (P140): one page's point data must fit its package entry, so a page that saves is never refused later.
        require(page.strokes.sumOf(::inkBytes) <= NotebookLimits.PAGE_INK_BYTES) {
            "This page is full. Continue on a new page."
        }
        val pageChecked = checkedPage(page)
        page.strokes.forEach { stroke ->
            if (pageChecked || checked(stroke)) {
                eraserPoints += stroke.erasures.sumOf { it.points.size }
                vertices += stroke.outlines.sumOf { it.size }
                points += stroke.samples.size
                require(eraserPoints <= NotebookLimits.ERASER_POINTS) { "Too many eraser points" }
                require(points <= NotebookLimits.SAMPLES && vertices <= NotebookLimits.VERTICES) { "This notebook is full. Start a new notebook, or split this one with Copy pages." }
                return@forEach
            }
            stroke.erasures.forEach { sweep ->
                require(sweep.radius.isFinite() && sweep.radius in .01f..1000f && sweep.points.isNotEmpty() && sweep.points.all { it.x.coordinate() && it.y.coordinate() }) { "Invalid eraser sweep" }
                sweep.basis?.let { basis ->
                    val values = listOf(basis.a, basis.b, basis.c, basis.d)
                    val determinant = basis.a.toDouble() * basis.d - basis.b.toDouble() * basis.c
                    require(values.all { it.isFinite() && kotlin.math.abs(it) <= 10_000 } && kotlin.math.abs(determinant) >= 1e-8 &&
                        values.all { kotlin.math.abs(it / determinant) <= 1_000_000 } &&
                        sweep.radius * maxOf(hypot(basis.a, basis.c), hypot(basis.b, basis.d)) <= 1_000_000) { "Invalid eraser basis" }
                }
                eraserPoints += sweep.points.size
                require(eraserPoints <= NotebookLimits.ERASER_POINTS) { "Too many eraser points" }
            }
            require(stroke.fillColor == null || (stroke.canFill && stroke.fillColor in 0xff000000L..0xffffffffL)) { "Invalid shape fill" }
            require(stroke.group == null || validId(stroke.group)) { "Invalid stroke group" }
            require(validId(stroke.id) && stroke.color in 0..0xffffffffL && stroke.width.isFinite() && stroke.width in 0.01f..1000f) { "Invalid stroke style" }
            require((stroke.brush == InkStroke.PEN || stroke.isHighlighter || stroke.isExtendedPen || stroke.isTape || stroke.isCalligraphy || stroke.isPencil) &&
                ((stroke.color ushr 24) == 255L || ((stroke.brush == InkStroke.PEN || stroke.isExtendedPen || stroke.isCalligraphy || stroke.isPencil) && (stroke.color ushr 24) >= InkStroke.MIN_ALPHA))) { "Unsupported brush or transparency" }
            require(stroke.samples.isNotEmpty() && stroke.outlines.isNotEmpty()) { "Empty stroke" }
            var time = -1L
            stroke.samples.forEach { sample ->
                require(sample.x.coordinate() && sample.y.coordinate() && sample.pressure.isFinite() && sample.pressure in 0f..1f && sample.tilt.isFinite() && sample.orientation.isFinite()) { "Invalid sample" }
                require(sample.timeMs in 0..86_400_000 && sample.timeMs >= time) { "Invalid sample time" }
                time = sample.timeMs
            }
            stroke.outlines.forEach { outline ->
                require(outline.size >= 3 && outline.all { it.x.coordinate() && it.y.coordinate() }) { "Invalid outline" }
                vertices += outline.size
            }
            points += stroke.samples.size
            require(points <= NotebookLimits.SAMPLES && vertices <= NotebookLimits.VERTICES) { "This notebook is full. Start a new notebook, or split this one with Copy pages." }
        }
    }
}

class DocumentSession private constructor(initial: Notebook, @Suppress("UNUSED_PARAMETER") validated: Validated) {
    private object Validated {}
    /** In-memory inputs always receive full validation. There is no public trust/skip flag. */
    constructor(initial: Notebook = Notebook()) : this(initial.also { it.validate() }, Validated)
    companion object {
        /** Package reading already validates the entire document; do not repeat that walk before editing. */
        fun read(file: java.io.File, ownedDirectory: java.io.File? = null): DocumentSession {
            val parsed = if (ownedDirectory == null) NotebookPackage.read(file) else NotebookPackage.read(file, ownedDirectory)
            return DocumentSession(parsed, Validated)
        }
        /** F059: redraws a connector along a new path in its own style; the app sets it, since outlines come from its brushes. Without it connectors stay where they are. */
        @Volatile var redrawConnector: ((InkStroke, List<Point>) -> InkStroke?)? = null
    }
    var notebook: Notebook = initial; private set
    private val undo = ArrayDeque<Notebook>()
    private val redo = ArrayDeque<Notebook>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    private fun commit(proposed: Notebook): Boolean {
        val redraw = redrawConnector
        val followed = if (redraw == null) proposed.pages else notebook.pages.associateBy { it.id }.let { old ->
            proposed.pages.map { page -> old[page.id]?.let { Connectors.follow(it, page, redraw) } ?: page }
        }
        val next = proposed.copy(pages = followed.map(::layered))
        if (next == notebook) return false
        // The current notebook passed validation, so strokes it holds unchanged need only be counted.
        val known = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InkStroke, Boolean>())
        notebook.pages.forEach { known.addAll(it.strokes) }
        next.validate(known::contains)
        undo.addLast(notebook)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        notebook = next
        return true
    }
    /** Saving a transcription is one Undo step; original stroke data remains unchanged. */
    fun saveReviewedHandwriting(input: HandwritingInput, text: String, language: String): Boolean {
        val record = ReviewedInk.create(notebook, input, text, language)
        val ids = record.strokeIds.toSet()
        return commit(notebook.copy(pages = notebook.pages.map { page -> if (page.id != input.pageId) page else
            page.copy(reviewedInk = page.activeReviewedInk.filterNot { old -> old.strokeIds.any { it in ids } } + record) }))
    }
    /**
     * P126 (F145): replaces the read handwriting with [text] in an editable text box where the ink was ([HandwritingConversion]),
     * as one Undo step. Only [input]'s ink goes; reviews of it go too. Refused when the page changed or the ink is locked.
     */
    fun convertHandwriting(input: HandwritingInput, text: String): String {
        require(input.matches(notebook, input.pageId)) { "Handwriting changed. Read it again before converting." }
        val clean = TextBox.clean(text).trim()
        require(clean.isNotEmpty()) { "Enter text to convert." }
        val page = notebook.pages.first { it.id == input.pageId }
        val ids = input.strokeIds.toSet()
        val ink = page.strokes.filter { it.id in ids }
        val locked = page.layers.filter { it.locked }.mapTo(HashSet()) { it.id }
        require(ink.none { it.locked || it.layer in locked }) { "Some of this handwriting is locked. Unlock it first." }
        val box = TextBox(clean, HandwritingConversion.placement(input.bounds, clean, page), HandwritingConversion.style(ink, input.bounds, clean))
        commit(notebook.copy(pages = notebook.pages.map { if (it.id != page.id) it else it.copy(
            strokes = it.strokes.filterNot { stroke -> stroke.id in ids }, textBoxes = it.textBoxes + box,
            reviewedInk = it.reviewedInk.filterNot { record -> record.strokeIds.any { id -> id in ids } }) }))
        return box.id
    }
    fun clearReviewedHandwriting(pageId: String): Boolean {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(reviewedInk = emptyList()) else it }))
    }
    /** P100: chosen layer per page; not saved, and checked against the page on every read. */
    private val activeLayers = mutableMapOf<String, String>()
    /** New ink lands here: the chosen layer if it is still visible, else the top visible layer; null on a page without layers. */
    fun activeLayer(pageId: String): String? = notebook.pages.firstOrNull { it.id == pageId }?.let(::activeIn)
    private fun activeIn(page: Page): String? {
        val open = page.layers.filter { !it.hidden && !it.locked }
        return (open.firstOrNull { it.id == activeLayers[page.id] } ?: open.lastOrNull() ?: page.layers.lastOrNull { !it.hidden } ?: page.layers.lastOrNull())?.id
    }
    fun selectLayer(pageId: String, layerId: String) {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val layer = page.layers.firstOrNull { it.id == layerId } ?: error("Layer not found")
        require(!layer.locked) { "Unlock this layer to draw on it" }
        activeLayers[pageId] = layerId
    }
    /** Strokes without a valid layer join the active one, strokes on a page without layers lose theirs, and all are kept in layer order. */
    private fun layered(page: Page): Page {
        if (page.layers.isEmpty()) return if (page.strokes.all { it.layer == null }) page else page.copy(strokes = page.strokes.map { it.copy(layer = null) })
        val order = page.layers.mapIndexed { index, layer -> layer.id to index }.toMap()
        val target by lazy { activeIn(page)!! }
        val assigned = page.strokes.map { if (it.layer in order) it else it.copy(layer = target) }
        val sorted = assigned.sortedBy { order.getValue(it.layer!!) }
        return if (sorted == page.strokes) page else page.copy(strokes = sorted)
    }
    /** P100 (F071): adds a layer on top and makes it active; the first one also puts the existing ink on "Layer 1". */
    fun addLayer(pageId: String, name: String? = null): String {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val base = if (page.layers.isEmpty()) listOf(Layer(name = "Layer 1")) else page.layers
        require(base.size < Page.MAX_LAYERS) { "A page can have at most ${Page.MAX_LAYERS} layers" }
        val label = name?.trim() ?: generateSequence(base.size + 1) { it + 1 }.map { "Layer $it" }.first { label -> base.none { it.name == label } }
        require(Layer.validName(label)) { "Layer names must be 1–40 characters" }
        val layer = Layer(name = label)
        val strokes = if (page.layers.isEmpty()) page.strokes.map { it.copy(layer = base.first().id) } else page.strokes
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(layers = base + layer, strokes = strokes) else it }))
        activeLayers[pageId] = layer.id
        return layer.id
    }
    private fun editLayers(pageId: String, layerId: String, change: (Page) -> Page): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.layers.any { it.id == layerId }) { "Layer not found" }
        val pages = notebook.pages.map { if (it.id == pageId) change(it) else it }
        val used = pages.flatMap { it.imageAssetIds }.toSet()
        return commit(notebook.copy(pages = pages, imageAssets = notebook.imageAssets.filter { it.id in used }))
    }
    fun renameLayer(pageId: String, layerId: String, name: String): Boolean {
        val label = name.trim()
        require(Layer.validName(label)) { "Layer names must be 1–40 characters" }
        return editLayers(pageId, layerId) { page -> page.copy(layers = page.layers.map { if (it.id == layerId) it.copy(name = label) else it }) }
    }
    /** P100 (F072): at least one layer stays visible, so new ink always has somewhere to show. */
    fun setLayerHidden(pageId: String, layerId: String, hidden: Boolean): Boolean = editLayers(pageId, layerId) { page ->
        val layers = page.layers.map { if (it.id == layerId) it.copy(hidden = hidden) else it }
        require(layers.any { !it.hidden }) { "Keep at least one layer visible" }
        require(layers.any { !it.hidden && !it.locked }) { "Keep at least one visible, unlocked layer" }
        page.copy(layers = layers)
    }
    /** P101 (F075): a locked layer keeps its ink drawn but out of reach; one visible, unlocked layer always stays. */
    fun setLayerLocked(pageId: String, layerId: String, locked: Boolean): Boolean = editLayers(pageId, layerId) { page ->
        val layers = page.layers.map { if (it.id == layerId) it.copy(locked = locked) else it }
        require(layers.any { !it.hidden && !it.locked }) { "Keep at least one visible, unlocked layer" }
        page.copy(layers = layers)
    }
    /** P102 (F078): 0.1–1; the layer's ink keeps its own colours and opacity, multiplied by this. */
    fun setLayerOpacity(pageId: String, layerId: String, opacity: Float): Boolean = editLayers(pageId, layerId) { page ->
        require(opacity.isFinite() && opacity in Layer.MIN_OPACITY..1f) { "Layer opacity must be 10–100 %" }
        page.copy(layers = page.layers.map { if (it.id == layerId) it.copy(opacity = opacity) else it })
    }
    /** P102 (F079): puts [ids] on [layerId] in one undo step; they are drawn in that layer's place. */
    fun moveStrokesToLayer(ids: Set<String>, pageId: String, layerId: String): Boolean = editLayers(pageId, layerId) { page ->
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        require(page.layers.none { it.id == layerId && it.locked }) { "Unlock that layer first" }
        require(page.strokes.none { it.id in ids && (it.locked || page.layers.any { layer -> layer.id == it.layer && layer.locked }) }) { "Selection contains locked ink" }
        page.copy(strokes = page.strokes.map { if (it.id in ids) it.copy(layer = layerId) else it })
    }
    /** P374: photos and text share the existing ordered layers. */
    fun moveObjectsToLayer(pageId: String, ids: ObjectIds, layerId: String): Boolean {
        val page = notebook.pages.first { it.id == pageId }
        notebook.captureObjects(pageId, ids)
        require(page.layers.any { it.id == layerId && !it.locked && !it.hidden }) { "Choose a visible, unlocked layer" }
        return editLayers(pageId, layerId) { it.copy(
            strokes = it.strokes.map { s -> if (s.id in ids.strokes) s.copy(layer = layerId) else s },
            images = it.images.map { i -> if (i.id in ids.images) i.copy(layer = layerId) else i },
            textBoxes = it.textBoxes.map { t -> if (t.id in ids.texts) t.copy(layer = layerId) else t }) }
    }
    /** P101 (F074): locks or unlocks the given strokes, photos and text boxes of one page in one undo step. */
    fun setObjectsLocked(pageId: String, strokes: Set<String> = emptySet(), images: Set<String> = emptySet(), texts: Set<String> = emptySet(), locked: Boolean, attachments: Set<String> = emptySet()): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(strokes) && page.images.map { it.id }.containsAll(images) &&
            page.textBoxes.map { it.id }.containsAll(texts) && page.attachments.map { it.id }.containsAll(attachments)) { "Selection contains missing objects" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id != pageId) it else it.copy(
            strokes = it.strokes.map { s -> if (s.id in strokes) s.copy(locked = locked) else s },
            images = it.images.map { i -> if (i.id in images) i.copy(locked = locked) else i },
            textBoxes = it.textBoxes.map { t -> if (t.id in texts) t.copy(locked = locked) else t },
            attachments = it.attachments.map { a -> if (a.id in attachments) a.copy(locked = locked) else a }) }))
    }
    /** P101 (F074): unlocks every stroke, photo and text box on the page; layer locks stay. */
    fun unlockPageObjects(pageId: String): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        return setObjectsLocked(pageId, page.strokes.filter { it.locked }.map { it.id }.toSet(), page.images.filter { it.locked }.map { it.id }.toSet(),
            page.textBoxes.filter { it.locked }.map { it.id }.toSet(), locked = false, attachments = page.attachments.filter { it.locked }.map { it.id }.toSet())
    }
    /** P100 (F073): [targetIndex] is the layer's final position, 0 = bottom. */
    fun moveLayer(pageId: String, layerId: String, targetIndex: Int): Boolean = editLayers(pageId, layerId) { page ->
        require(targetIndex in page.layers.indices) { "Invalid layer position" }
        val layers = page.layers.toMutableList()
        layers.add(targetIndex, layers.removeAt(layers.indexOfFirst { it.id == layerId }))
        page.copy(layers = layers)
    }
    /** Removes the layer and its ink in one undo step; the last layer stays. */
    fun deleteLayer(pageId: String, layerId: String): Boolean = editLayers(pageId, layerId) { page ->
        require(page.layers.size > 1) { "Keep at least one layer" }
        val layers = page.layers.filterNot { it.id == layerId }
        page.copy(layers = if (layers.any { !it.hidden && !it.locked }) layers else layers.dropLast(1) + layers.last().copy(hidden = false, locked = false),
            strokes = page.strokes.filterNot { it.layer == layerId }, images = page.images.filterNot { it.layer == layerId }, textBoxes = page.textBoxes.filterNot { it.layer == layerId })
    }
    /** Insert a validated PDF notebook without activating it or rewriting any source PDF bytes. */
    fun insertPdfPages(source: Notebook, afterPageId: String): List<String> {
        source.validate()
        require(source.pages.all { it.pdfBackground != null }) { "Insertion requires PDF pages" }
        return insertBackgroundPages(source, afterPageId)
    }
    fun insertImagePages(source: Notebook, afterPageId: String): List<String> {
        source.validate()
        require(source.pages.all { it.imageBackground != null }) { "Insertion requires image pages" }
        return insertBackgroundPages(source, afterPageId)
    }
    private fun insertBackgroundPages(source: Notebook, afterPageId: String): List<String> {
        val after = notebook.pages.indexOfFirst { it.id == afterPageId }
        require(after >= 0) { "Page not found" }
        source.validate()
        require(notebook.pages.size + source.pages.size <= NotebookLimits.PAGES) { "A notebook can have at most ${NotebookLimits.PAGES} pages" }
        val assets = notebook.assets.toMutableList()
        source.assets.forEach { incoming ->
            val existing = assets.firstOrNull { it.id == incoming.id }
            if (existing == null) assets.add(incoming)
            else require(existing == incoming) { "Conflicting PDF asset" }
        }
        val images = notebook.imageAssets.toMutableList()
        source.imageAssets.forEach { incoming ->
            val existing = images.firstOrNull { it.id == incoming.id }
            if (existing == null) images.add(incoming) else require(existing == incoming) { "Conflicting image asset" }
        }
        require(assets.size + images.size <= NotebookLimits.BACKGROUND_FILES && assets.sumOf { it.size.toLong() } + images.sumOf { it.size.toLong() } <= NotebookLimits.BACKGROUND_BYTES) {
            "A notebook can use at most ${NotebookLimits.BACKGROUND_FILES} PDF and image files, ${NotebookLimits.BACKGROUND_BYTES / (1024 * 1024)} MB together"
        }
        val pages = source.pages.map { page -> page.copy(id = UUID.randomUUID().toString(),
            strokes = page.strokes.map { it.copy(id = UUID.randomUUID().toString()) }, images = page.images.map { it.copy(id = UUID.randomUUID().toString()) },
            textBoxes = page.textBoxes.map { it.copy(id = UUID.randomUUID().toString()) }, attachments = page.attachments.map { it.copy(id = UUID.randomUUID().toString()) }).withCopiedReviews(page) }
        val files = notebook.attachmentAssets.toMutableList()
        source.attachmentAssets.forEach { incoming -> if (files.none { it.id == incoming.id }) files.add(incoming) }
        commit(notebook.copy(pages = notebook.pages.take(after + 1) + pages + notebook.pages.drop(after + 1), assets = assets, imageAssets = images, attachmentAssets = files))
        return pages.map { it.id }
    }
    fun add(stroke: InkStroke, pageId: String = notebook.pages.first().id): Boolean {
        require(notebook.pages.any { it.id == pageId })
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(strokes = it.strokes + stroke) else it }))
    }
    fun remove(ids: Set<String>, pageId: String? = null): Boolean = commit(notebook.copy(pages = notebook.pages.map { page ->
        if (pageId == null || page.id == pageId) page.copy(strokes = page.strokes.filterNot { it.id in ids }) else page
    }))
    fun rename(title: String): Boolean {
        require(title.trim().length in 1..500) { "Title must have 1–500 characters" }
        return commit(notebook.copy(title = title.trim()))
    }
    /** Recolor only; canonical geometry and erasure masks remain byte-for-byte authoritative. */
    fun recolorStrokes(ids: Set<String>, pageId: String, color: Long): Boolean {
        require(color in 0xff000000L..0xffffffffL) { "Select an opaque RGB color" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        // Each stroke keeps its own opacity (P089); only the RGB changes.
        fun recolored(stroke: InkStroke) = (stroke.color and 0xff000000L) or (color and 0xffffffL)
        if (ids.isEmpty() || page.strokes.filter { it.id in ids }.all { it.color == recolored(it) }) return false
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> if (stroke.id in ids) stroke.copy(color = recolored(stroke)) else stroke })
        }))
    }
    /**
     * Width only (F017), one history step. Each replacement keeps its stroke's id, colour, brush, samples and erasure
     * sweeps; only width and the renderer-regenerated outlines change. Z-order is preserved.
     */
    fun rewidthStrokes(pageId: String, replacements: List<InkStroke>): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val byId = replacements.associateBy { it.id }
        require(byId.size == replacements.size) { "Duplicate stroke id" }
        val current = page.strokes.associateBy { it.id }
        replacements.forEach { new ->
            val old = current[new.id]
            require(old != null) { "Selection contains missing strokes" }
            require(new.width.isFinite() && new.width in 0.01f..1000f && new.outlines.isNotEmpty()) { "Invalid stroke width" }
            require(new.copy(width = old.width, outlines = old.outlines) == old) { "Width change may not alter other stroke data" }
        }
        if (replacements.isEmpty() || replacements.all { current.getValue(it.id).width == it.width }) return false
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> byId[stroke.id] ?: stroke })
        }))
    }
    /**
     * B27 (P192): colour, width and pen kind together, one history step. Each replacement keeps its stroke's id, opacity,
     * samples, erasure sweeps, group, layer and lock. Only pen strokes change kind, and only to another pen; highlighters
     * and tape keep theirs. Z-order is preserved.
     */
    fun restyleStrokes(pageId: String, replacements: List<InkStroke>): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val byId = replacements.associateBy { it.id }
        require(byId.size == replacements.size) { "Duplicate stroke id" }
        val current = page.strokes.associateBy { it.id }
        replacements.forEach { new ->
            val old = current[new.id]
            require(old != null) { "Selection contains missing strokes" }
            require(new.width.isFinite() && new.width in 0.01f..1000f && new.outlines.isNotEmpty()) { "Invalid stroke width" }
            require(new.copy(color = old.color, width = old.width, brush = old.brush, outlines = old.outlines, fillColor = old.fillColor) == old) { "Restyling may not alter other stroke data" }
            require(new.color ushr 24 == old.color ushr 24) { "Restyling keeps each stroke's opacity" }
            require(new.brush == old.brush || (new.brush in PEN_BRUSHES && old.brush in PEN_BRUSHES)) { "Only pen strokes change pen kind" }
        }
        if (replacements.isEmpty() || replacements.all { current.getValue(it.id) == it }) return false
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> byId[stroke.id] ?: stroke })
        }))
    }
    /**
     * P413 (auto-refine R1): refined handwriting, one history step. Each replacement changes only its stroke's samples,
     * outlines and eraser geometry ([HandwritingRefine]); identity, pen, colour, width, layer and links stay. Z-order is kept.
     */
    fun refineStrokes(pageId: String, replacements: List<InkStroke>): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val byId = replacements.associateBy { it.id }
        require(byId.size == replacements.size) { "Duplicate stroke id" }
        val current = page.strokes.associateBy { it.id }
        replacements.forEach { new ->
            val old = requireNotNull(current[new.id]) { "Selection contains missing strokes" }
            require(new.copy(samples = old.samples, outlines = old.outlines, erasures = old.erasures) == old) { "Refining may only move and reshape handwriting" }
            require(new.samples.isNotEmpty() && new.samples.all { it.x.isFinite() && it.y.isFinite() } && new.outlines.isNotEmpty() &&
                new.outlines.all { o -> o.size >= 3 && o.all { it.x.isFinite() && it.y.isFinite() } }) { "Invalid refined stroke" }
        }
        if (replacements.isEmpty() || replacements.all { current.getValue(it.id) == it }) return false
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> byId[stroke.id] ?: stroke })
        }))
    }
    /**
     * P415 (handwriting style): each group's original words, given exactly as they are on the page, are swapped for their
     * rewritten strokes, one history step. The rewritten strokes take the place (z-order) and layer of their group's first
     * original; Undo brings back the originals exactly. Nothing is applied if any original changed meanwhile.
     */
    fun rewriteHandwriting(pageId: String, groups: List<Pair<List<InkStroke>, List<InkStroke>>>): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        if (groups.isEmpty()) return false
        val current = page.strokes.associateBy { it.id }
        val olds = groups.flatMap { it.first }
        require(groups.all { it.first.isNotEmpty() && it.second.isNotEmpty() } && olds.map { it.id }.distinct().size == olds.size) { "Invalid rewrite" }
        require(olds.all { current[it.id] == it }) { "Writing changed before it was rewritten" }
        val news = groups.flatMap { it.second }
        require(news.map { it.id }.distinct().size == news.size && news.none { it.id in current }) { "Duplicate stroke id" }
        require(news.all { new -> HandwritingRestyle.eligible(new) && new.width.isFinite() && new.samples.isNotEmpty() &&
            new.samples.all { it.x.isFinite() && it.y.isFinite() } && new.outlines.isNotEmpty() &&
            new.outlines.all { o -> o.all { it.x.isFinite() && it.y.isFinite() } } }) { "Invalid rewritten stroke" }
        val firstOf = groups.associate { (old, new) -> old.first().id to new.map { it.copy(layer = old.first().layer) } }
        val gone = olds.map { it.id }.toSet()
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.flatMap { stroke -> firstOf[stroke.id] ?: if (stroke.id in gone) emptyList() else listOf(stroke) })
        }))
    }
    private val PEN_BRUSHES = setOf(InkStroke.PEN, InkStroke.BALLPOINT, InkStroke.DASHED, InkStroke.CALLIGRAPHY, InkStroke.PENCIL)
    fun moveStrokes(ids: Set<String>, pageId: String, dx: Float, dy: Float, imageIds: Set<String> = emptySet(), textIds: Set<String> = emptySet()): Boolean {
        require(dx.isFinite() && dy.isFinite()) { "Invalid movement" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        notebook.captureObjects(pageId,ObjectIds(ids,imageIds,textIds))
        if ((ids.isEmpty() && imageIds.isEmpty() && textIds.isEmpty()) || (dx == 0f && dy == 0f)) return false
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> if (stroke.id in ids) stroke.translated(dx, dy) else stroke },
                images=it.images.map { image -> if(image.id in imageIds) image.copy(placement=image.placement.translated(dx,dy)) else image },
                textBoxes=it.textBoxes.map { box -> if(box.id in textIds) box.copy(placement=box.placement.translated(dx,dy)) else box })
        }))
    }
    fun duplicateStrokes(ids: Set<String>, pageId: String, dx: Float = 24f, dy: Float = 24f): Set<String> {
        require(dx.isFinite() && dy.isFinite()) { "Invalid movement" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        if (ids.isEmpty()) return emptySet()
        val copies = regrouped(page.strokes.filter { it.id in ids }).map { it.translated(dx, dy).copy(id = UUID.randomUUID().toString()) }
        commit(notebook.copy(pages = notebook.pages.map { if (it.id != pageId) it else it.copy(strokes = it.strokes + copies) }))
        return copies.map { it.id }.toSet()
    }
    /** Paste the whole mixed bundle in one history commit, preserving relative geometry and original assets. */
    fun pasteObjects(bundle: ObjectBundle, pageId: String, dx: Float = 0f, dy: Float = 0f, audio:LinkedAudioSelection?=null): ObjectIds {
        require(dx.isFinite() && dy.isFinite()) { "Invalid paste position" }
        require(notebook.pages.any { it.id==pageId }) { "Page not found" }
        val audioPlan=audio?.prepare(notebook,pageId,bundle.strokes)
        val sourceStrokes=audioPlan?.strokes ?: bundle.strokes
        val target=audioPlan?.notebook ?: notebook
        val groups=(sourceStrokes.mapNotNull { it.group }+bundle.images.mapNotNull { it.group }+bundle.texts.mapNotNull { it.group }).distinct().associateWith { UUID.randomUUID().toString() }
        val strokes=sourceStrokes.map { it.translated(dx,dy).copy(id=UUID.randomUUID().toString(),layer=null,locked=false,group=it.group?.let(groups::getValue)) }
        val photos=bundle.images.map { it.copy(id=UUID.randomUUID().toString(),placement=it.placement.translated(dx,dy),locked=false,layer=it.layer?.takeIf { layer -> notebook.pages.first { it.id==pageId }.layerEditable(layer) },group=it.group?.let(groups::getValue)) }
        val text=bundle.texts.map { it.copy(id=UUID.randomUUID().toString(),placement=it.placement.translated(dx,dy),locked=false,layer=it.layer?.takeIf { layer -> notebook.pages.first { it.id==pageId }.layerEditable(layer) },group=it.group?.let(groups::getValue)) }
        val ids=ObjectIds(strokes.map { it.id }.toSet(),photos.map { it.id }.toSet(),text.map { it.id }.toSet())
        if(ids.isEmpty) return ids
        val assets=notebook.imageAssets.associateBy { it.id }.toMutableMap()
        bundle.assets.forEach { asset -> require(assets[asset.id]==null || assets[asset.id]==asset) { "Conflicting image asset" };assets[asset.id]=asset }
        commit(target.copy(pages=target.pages.map { if(it.id!=pageId) it else it.copy(strokes=it.strokes+strokes,images=it.images+photos,textBoxes=it.textBoxes+text) },imageAssets=assets.values.toList()))
        return ids
    }
    fun removeObjects(pageId: String, ids: ObjectIds): Boolean {
        notebook.captureObjects(pageId,ids) // Refuse stale or protected selections before changing anything.
        val pages=notebook.pages.map { if(it.id!=pageId) it else it.copy(strokes=it.strokes.filterNot { s -> s.id in ids.strokes },
            images=it.images.filterNot { image -> image.id in ids.images },textBoxes=it.textBoxes.filterNot { box -> box.id in ids.texts },
            reviewedInk=it.reviewedInk.filterNot { record -> record.strokeIds.any { id -> id in ids.strokes } }) }
        val used=pages.flatMap { it.imageAssetIds }.toSet()
        return commit(notebook.copy(pages=pages,imageAssets=notebook.imageAssets.filter { it.id in used }))
    }
    /** Import editable ink; optional linked recording payload joins the same Undo. Page backgrounds never travel. */
    fun pasteStrokes(strokes: List<InkStroke>, pageId: String, dx: Float = 0f, dy: Float = 0f, audio:LinkedAudioSelection?=null): Set<String> {
        require(dx.isFinite() && dy.isFinite()) { "Invalid paste position" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        if (strokes.isEmpty()) return emptySet()
        require(strokes.size <= 10_000 - page.strokes.size) { "Too many strokes on destination page" }
        val audioPlan=audio?.prepare(notebook,pageId,strokes)
        val target=audioPlan?.notebook ?: notebook
        val copies = regrouped(audioPlan?.strokes ?: strokes).map { it.translated(dx, dy).copy(id = UUID.randomUUID().toString(), layer = null, locked = false) }
        commit(target.copy(pages = target.pages.map { if (it.id == pageId) it.copy(strokes = it.strokes + copies) else it }))
        return copies.map { it.id }.toSet()
    }
    /** Positive independent axis scales followed by clockwise rotation; canonical outlines stay authoritative. */
    fun transformStrokes(ids: Set<String>, pageId: String, pivot: Point, scale: Float = 1f, degrees: Float = 0f, scaleY: Float = scale, imageIds: Set<String> = emptySet(), textIds: Set<String> = emptySet()): Boolean {
        require(pivot.x.isFinite() && pivot.y.isFinite() && scale.isFinite() && scale > 0f && scaleY.isFinite() && scaleY > 0f && degrees.isFinite()) { "Invalid selection transform" }
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        val angle = ((degrees.toDouble() % 360.0) + 360.0) % 360.0
        notebook.captureObjects(pageId,ObjectIds(ids,imageIds,textIds))
        if ((ids.isEmpty() && imageIds.isEmpty() && textIds.isEmpty()) || (scale == 1f && scaleY == 1f && angle == 0.0)) return false
        // Exact quarter-turns avoid introducing trig noise on repeated toolbar rotations.
        val (cos, sin) = when (angle) {
            0.0 -> 1.0 to 0.0
            90.0 -> 0.0 to 1.0
            180.0 -> -1.0 to 0.0
            270.0 -> 0.0 to -1.0
            else -> Math.cos(Math.toRadians(angle)) to Math.sin(Math.toRadians(angle))
        }
        fun point(x: Float, y: Float): Point {
            val dx = x.toDouble() - pivot.x; val dy = y.toDouble() - pivot.y
            return Point((pivot.x + cos * scale * dx - sin * scaleY * dy).toFloat(), (pivot.y + sin * scale * dx + cos * scaleY * dy).toFloat())
        }
        fun mask(value: EraserSweep): EraserSweep {
            val uniform = scale == scaleY
            val basis = value.basis ?: EraserBasis(1f, 0f, 0f, 1f)
            val sx = if (uniform) 1f else scale; val sy = if (uniform) 1f else scaleY
            val next = if (uniform && value.basis == null) null else EraserBasis(
                (cos * sx * basis.a - sin * sy * basis.b).toFloat(), (sin * sx * basis.a + cos * sy * basis.b).toFloat(),
                (cos * sx * basis.c - sin * sy * basis.d).toFloat(), (sin * sx * basis.c + cos * sy * basis.d).toFloat())
            return value.copy(radius = value.radius * if (uniform) scale else 1f, basis = next, points = value.points.map { point(it.x, it.y) })
        }
        fun stroke(value: InkStroke): InkStroke = value.copy(
            width = value.width * if (scale == scaleY) scale else kotlin.math.sqrt(scale * scaleY),
            samples = value.samples.map { sample -> point(sample.x, sample.y).let { sample.copy(x = it.x, y = it.y) } },
            outlines = value.outlines.map { outline -> outline.map { point(it.x, it.y) } },
            erasures = value.erasures.map(::mask),
        )
        fun placement(p: ImagePlacement): ImagePlacement {
            val a=p.pagePoint(0f,0f).let { point(it.x,it.y) };val b=p.pagePoint(1f,0f).let { point(it.x,it.y) };val d=p.pagePoint(0f,1f).let { point(it.x,it.y) }
            val ux=b.x-a.x;val uy=b.y-a.y;val vx=d.x-a.x;val vy=d.y-a.y
            val w=kotlin.math.hypot(ux,uy);val h=kotlin.math.hypot(vx,vy)
            require(kotlin.math.abs(ux*vx+uy*vy)<=w*h*.0001f) { "Use a corner handle to resize rotated text or photos together" }
            val centre=p.pagePoint(.5f,.5f).let { point(it.x,it.y) }
            return ImagePlacement(centre.x-w/2,centre.y-h/2,w,h,Math.toDegrees(kotlin.math.atan2(uy,ux).toDouble()).toFloat())
        }
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { value -> if (value.id in ids) stroke(value) else value },
                images=it.images.map { image -> if(image.id in imageIds) image.copy(placement=placement(image.placement)) else image },
                textBoxes=it.textBoxes.map { box -> if(box.id in textIds) box.copy(placement=placement(box.placement)) else box })
        }))
    }
    /**
     * F096: swaps one table's lines and cell text as one Undo step, in the table's place; everything else on the page stays.
     * P549: [inkMoves] shifts handwriting in the cells by these amounts in the same step, so it follows its row and column.
     */
    fun replaceTable(pageId: String, group: String, strokes: List<InkStroke>, texts: List<TextBox>, inkMoves: Map<String, Point> = emptyMap()): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val oldStrokes = page.strokes.filter { it.group == group }; val oldTexts = page.textBoxes.filter { it.group == group }
        require(inkMoves.keys.all { id -> page.strokes.any { it.id == id && it.group != group } } && inkMoves.values.all { it.x.isFinite() && it.y.isFinite() }) { "Invalid cell ink" }
        notebook.captureObjects(pageId, ObjectIds(oldStrokes.map { it.id }.toSet() + inkMoves.keys, emptySet(), oldTexts.map { it.id }.toSet()))
        require(strokes.all { it.group == group } && texts.all { it.group == group }) { "Table objects must share its group" }
        require(page.strokes.size - oldStrokes.size + strokes.size <= 10_000) { "Too many strokes on destination page" }
        val inkAt = page.strokes.indexOfFirst { it.group == group }.let { if (it < 0) page.strokes.size else it }
        val textAt = page.textBoxes.indexOfFirst { it.group == group }.let { if (it < 0) page.textBoxes.size else it }
        val ink = page.strokes.filter { it.group != group }.map { s -> inkMoves[s.id]?.let { s.translated(it.x, it.y) } ?: s }.toMutableList().apply { addAll(inkAt, strokes) }
        val text = page.textBoxes.filter { it.group != group }.toMutableList().apply { addAll(textAt.coerceAtMost(size), texts) }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(strokes = ink, textBoxes = text) else it }))
    }
    /**
     * P555: redraws a connector in place, with the same id, and sets its label as one Undo step: [label] replaces the
     * old label (or is added), and null removes it. A labelled connector shares a group with its label alone.
     */
    fun replaceConnector(pageId: String, stroke: InkStroke, label: TextBox?): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val at = page.strokes.indexOfFirst { it.id == stroke.id }
        require(at >= 0) { "Connector not found" }
        val old = Connectors.label(page, stroke.id)
        require(page.strokes[at].group == null || old != null) { "Ungroup the connector first" }
        notebook.captureObjects(pageId, ObjectIds(setOf(stroke.id), emptySet(), setOfNotNull(old?.id)))
        require(if (label == null) stroke.group == null else stroke.group != null && label.group == stroke.group) { "A label shares its connector's group" }
        require(label == null || label.id == old?.id || page.textBoxes.none { it.id == label.id }) { "Duplicate text box" }
        require(stroke.group == null || stroke.group == page.strokes[at].group || (page.strokes.none { it.group == stroke.group } && page.images.none { it.group == stroke.group } && page.textBoxes.none { it.group == stroke.group })) { "Group in use" }
        val ink = page.strokes.toMutableList().apply { set(at, stroke) }
        val textAt = old?.let { o -> page.textBoxes.indexOfFirst { it.id == o.id } } ?: page.textBoxes.size
        val text = page.textBoxes.filter { it.id != old?.id }.toMutableList().apply { if (label != null) add(textAt.coerceAtMost(size), label) }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(strokes = ink, textBoxes = text) else it }))
    }
    fun groupObjects(pageId: String, ids: ObjectIds): String? {
        notebook.captureObjects(pageId,ids)
        if(ids.strokes.size+ids.images.size+ids.texts.size<2) return null
        val group=UUID.randomUUID().toString()
        commit(notebook.copy(pages=notebook.pages.map { p -> if(p.id!=pageId) p else p.copy(
            strokes=p.strokes.map { if(it.id in ids.strokes) it.copy(group=group) else it },
            images=p.images.map { if(it.id in ids.images) it.copy(group=group) else it },
            textBoxes=p.textBoxes.map { if(it.id in ids.texts) it.copy(group=group) else it }) }))
        return group
    }
    fun ungroupObjects(pageId: String, ids: ObjectIds): Boolean {
        notebook.captureObjects(pageId,ids)
        val page=notebook.pages.first { it.id==pageId }
        val ink=page.strokes.map { if(it.id in ids.strokes) it.copy(group=null) else it }
        val photos=page.images.map { if(it.id in ids.images) it.copy(group=null) else it }
        val text=page.textBoxes.map { if(it.id in ids.texts) it.copy(group=null) else it }
        val sizes=(ink.mapNotNull { it.group }+photos.mapNotNull { it.group }+text.mapNotNull { it.group }).groupingBy { it }.eachCount()
        return commit(notebook.copy(pages=notebook.pages.map { p -> if(p.id!=pageId) p else p.copy(
            strokes=ink.map { if(it.group!=null && sizes.getValue(it.group)<2) it.copy(group=null) else it },
            images=photos.map { if(it.group!=null && sizes.getValue(it.group)<2) it.copy(group=null) else it },
            textBoxes=text.map { if(it.group!=null && sizes.getValue(it.group)<2) it.copy(group=null) else it }) }))
    }
    /** P098 (F050): one new group holding exactly [ids] (at least two strokes); earlier groups of those strokes are left. */
    fun groupStrokes(ids: Set<String>, pageId: String): String? {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        if (ids.size < 2) return null
        val group = UUID.randomUUID().toString()
        commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> if (stroke.id in ids) stroke.copy(group = group) else stroke })
        }))
        return group
    }
    /** P098 (F050): [ids] leave their groups; other members of those groups stay grouped only if two or more remain. */
    fun ungroupStrokes(ids: Set<String>, pageId: String): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.map { it.id }.containsAll(ids)) { "Selection contains missing strokes" }
        val left = page.strokes.map { if (it.id in ids) it.copy(group = null) else it }
        val sizes = left.mapNotNull { it.group }.groupingBy { it }.eachCount()
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = left.map { stroke -> if (stroke.group != null && sizes.getValue(stroke.group) < 2) stroke.copy(group = null) else stroke })
        }))
    }
    fun eraseArea(ids: Set<String>, sweep: EraserSweep, pageId: String): Boolean {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        return commit(notebook.copy(pages = notebook.pages.map { page ->
            if (page.id != pageId) page else page.copy(strokes = page.strokes.map { stroke ->
                if (stroke.id in ids) stroke.copy(erasures = stroke.erasures + sweep) else stroke
            })
        }))
    }
    /** Swaps one stroke for another in place (same z-order), e.g. a freehand stroke for its recognized shape. */
    fun replaceStroke(id: String, replacement: InkStroke, pageId: String): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(page.strokes.any { it.id == id }) { "Stroke not found" }
        require(replacement.id == id || page.strokes.none { it.id == replacement.id }) { "Duplicate stroke id" }
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.map { stroke -> if (stroke.id == id) replacement.copy(layer = stroke.layer) else stroke })
        }))
    }
    /** B04 (P132): swaps one stroke for several in its place (same z-order and layer), e.g. a coloured region for one straight highlight per text line. */
    fun replaceStrokes(id: String, replacements: List<InkStroke>, pageId: String): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val old = page.strokes.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Stroke not found")
        require(replacements.isNotEmpty() && replacements.map { it.id }.distinct().size == replacements.size &&
            replacements.none { r -> r.id != id && page.strokes.any { it.id == r.id } }) { "Duplicate stroke id" }
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id != pageId) it else it.copy(strokes = it.strokes.flatMap { stroke -> if (stroke.id == id) replacements.map { r -> r.copy(layer = old.layer) } else listOf(stroke) })
        }))
    }
    fun addPage(afterId: String): String {
        val index = notebook.pages.indexOfFirst { it.id == afterId }
        require(index >= 0) { "Page not found" }
        // P413: an imported page's paper is hidden under its PDF/photo, so a new page takes the nearest page's own paper instead.
        val pages = notebook.pages
        val paper = ((index downTo 0).asSequence() + (index + 1 until pages.size).asSequence())
            .map { pages[it] }.firstOrNull { !it.hasBackground }?.paper ?: PaperStyle.DEFAULT
        val page = Page(width = pages[index].width, height = pages[index].height, paper = paper)
        commit(notebook.copy(pages = notebook.pages.toMutableList().apply { add(index + 1, page) }))
        return page.id
    }
    fun duplicatePage(pageId: String): String {
        val index = notebook.pages.indexOfFirst { it.id == pageId }
        require(index >= 0) { "Page not found" }
        val duplicate = notebook.pages[index].copy(id = UUID.randomUUID().toString(), strokes = notebook.pages[index].strokes.map { it.copy(id = UUID.randomUUID().toString()) }, images = notebook.pages[index].images.map { it.copy(id = UUID.randomUUID().toString()) }, textBoxes = notebook.pages[index].textBoxes.map { it.copy(id = UUID.randomUUID().toString()) }, attachments = notebook.pages[index].attachments.map { it.copy(id = UUID.randomUUID().toString()) }).withCopiedReviews(notebook.pages[index])
        val proposed=notebook.copy(pages = notebook.pages.toMutableList().apply { add(index + 1, duplicate) })
        val audio=LinkedAudioSelection.capture(notebook,notebook.pages[index].strokes).prepare(proposed,duplicate.id,duplicate.strokes)
        commit(audio.notebook.copy(pages=audio.notebook.pages.map {if(it.id==duplicate.id)it.copy(strokes=audio.strokes)else it}))
        return duplicate.id
    }
    /**
     * P099 (F070): copies [pageIds] of another notebook, in that order, to the end of this one with fresh ids, together
     * with the PDF, image and attached files they use. Files are content-addressed, so a file already here is shared.
     */
    fun appendPages(source: Notebook, pageIds: List<String> = source.pages.map { it.id }): List<String> {
        source.validate()
        require(pageIds.isNotEmpty() && pageIds.distinct().size == pageIds.size) { "Choose pages to copy" }
        val chosen = pageIds.map { id -> source.pages.firstOrNull { it.id == id } ?: error("Page not found") }
        require(notebook.pages.size + chosen.size <= NotebookLimits.PAGES) { "A notebook can have at most ${NotebookLimits.PAGES} pages" }
        fun <T> joined(mine: List<T>, incoming: List<T>, id: (T) -> String) = mine + incoming.filter { file -> mine.none { id(it) == id(file) } }
        val assets = joined(notebook.assets, source.assets.filter { asset -> chosen.any { it.pdfBackground?.assetId == asset.id } }) { it.id }
        val images = joined(notebook.imageAssets, source.imageAssets.filter { asset -> chosen.any { asset.id in it.imageAssetIds } }) { it.id }
        val files = joined(notebook.attachmentAssets, source.attachmentAssets.filter { asset -> chosen.any { asset.id in it.attachmentAssetIds } }) { it.id }
        require(assets.size + images.size <= NotebookLimits.BACKGROUND_FILES && assets.sumOf { it.size.toLong() } + images.sumOf { it.size.toLong() } <= NotebookLimits.BACKGROUND_BYTES) {
            "A notebook can use at most ${NotebookLimits.BACKGROUND_FILES} PDF and image files, ${NotebookLimits.BACKGROUND_BYTES / (1024 * 1024)} MB together"
        }
        val pages = chosen.map { page -> page.copy(id = UUID.randomUUID().toString(),
            strokes = regrouped(page.strokes).map { it.copy(id = UUID.randomUUID().toString()) }, images = page.images.map { it.copy(id = UUID.randomUUID().toString()) },
            textBoxes = page.textBoxes.map { it.copy(id = UUID.randomUUID().toString()) }, attachments = page.attachments.map { it.copy(id = UUID.randomUUID().toString()) }).withCopiedReviews(page) }
        val proposed=notebook.copy(pages = notebook.pages + pages, assets = assets, imageAssets = images, attachmentAssets = files)
        val audio=LinkedAudioSelection.capture(source,chosen.flatMap {it.strokes}).prepare(proposed,pages.first().id,pages.flatMap {it.strokes})
        val rewritten=audio.strokes.associateBy {it.id};val copiedIds=pages.map {it.id}.toSet()
        commit(audio.notebook.copy(pages=audio.notebook.pages.map {p->if(p.id in copiedIds)p.copy(strokes=p.strokes.map {rewritten.getValue(it.id)})else p}))
        return pages.map { it.id }
    }
    fun insertTextBox(pageId: String, text: String, placement: ImagePlacement, style: TextStyle = TextStyle(), sticky:StickyNote?=null): String {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        val box = TextBox(text, placement, style, sticky=sticky)
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(textBoxes = it.textBoxes + box) else it }))
        return box.id
    }
    /** All chosen editable ink/text receive one target and one Undo step. */
    fun linkObjects(pageId:String, ids:ObjectIds, target:NotebookLink?):Boolean {
        val page=notebook.pages.firstOrNull {it.id==pageId} ?: error("Page not found")
        require(ids.images.isEmpty()) {"Links support ink and text"}
        if(ids.isEmpty)return false
        notebook.captureObjects(pageId,ids)
        return commit(notebook.copy(pages=notebook.pages.map {p->if(p.id!=pageId)p else p.copy(
            strokes=p.strokes.map {if(it.id in ids.strokes)it.copy(link=target)else it},
            textBoxes=p.textBoxes.map {if(it.id in ids.texts)it.copy(link=target)else it})}))
    }
    fun updateTextBox(pageId: String, box: TextBox): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        notebook.captureObjects(pageId, ObjectIds(texts = setOf(box.id)))
        require(page.textBoxes.any { it.id == box.id }) { "Text box not found" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(textBoxes = it.textBoxes.map { current ->
            if (current.id == box.id) box else current
        }) else it }))
    }
    fun duplicateTextBox(pageId: String, textId: String, dx: Float = 24f, dy: Float = 24f): String {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        notebook.captureObjects(pageId, ObjectIds(texts = setOf(textId)))
        val original = page.textBoxes.firstOrNull { it.id == textId } ?: error("Text box not found")
        val box = original.copy(id = UUID.randomUUID().toString(), placement = original.placement.translated(dx, dy))
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(textBoxes = it.textBoxes + box) else it }))
        return box.id
    }
    fun removeTextBoxes(pageId: String, ids: Set<String>): Boolean {
        notebook.captureObjects(pageId, ObjectIds(texts = ids))
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(textBoxes = it.textBoxes.filterNot { box -> box.id in ids }) else it }))
    }
    fun insertImageObject(pageId: String, asset: ImageAsset, placement: ImagePlacement, orientation: Int = 1, crop: ImageCrop = ImageCrop.FULL): String {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        val existing = notebook.imageAssets.firstOrNull { it.id == asset.id }
        require(existing == null || existing == asset) { "Conflicting image asset" }
        val image = ImageObject(asset.id, placement, orientation = orientation, crop = crop)
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(images = it.images + image) else it },
            imageAssets = if (existing == null) notebook.imageAssets + asset else notebook.imageAssets))
        return image.id
    }
    fun updateImageObject(pageId: String, imageId: String, placement: ImagePlacement): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        notebook.captureObjects(pageId, ObjectIds(images = setOf(imageId)))
        require(page.images.any { it.id == imageId }) { "Image not found" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(images = it.images.map { image ->
            if (image.id == imageId) image.copy(placement = placement) else image
        }) else it }))
    }
    fun cropImageObject(pageId: String, imageId: String, crop: ImageCrop): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        notebook.captureObjects(pageId, ObjectIds(images = setOf(imageId)))
        val original = page.images.firstOrNull { it.id == imageId } ?: error("Image not found")
        val image = original.croppedTo(crop)
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId)
            it.copy(images = it.images.map { item -> if (item.id == imageId) image else item }) else it }))
    }
    fun duplicateImageObject(pageId: String, imageId: String, dx: Float = 24f, dy: Float = 24f): String {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        notebook.captureObjects(pageId, ObjectIds(images = setOf(imageId)))
        val source = page.images.firstOrNull { it.id == imageId } ?: error("Image not found")
        val image = source.copy(id = UUID.randomUUID().toString(), placement = source.placement.translated(dx, dy))
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(images = it.images + image) else it }))
        return image.id
    }
    fun removeImageObjects(pageId: String, ids: Set<String>): Boolean {
        notebook.captureObjects(pageId, ObjectIds(images = ids))
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        val pages = notebook.pages.map { if (it.id == pageId) it.copy(images = it.images.filterNot { image -> image.id in ids }) else it }
        val referenced = pages.flatMap { it.imageAssetIds }.toSet()
        return commit(notebook.copy(pages = pages, imageAssets = notebook.imageAssets.filter { it.id in referenced }))
    }
    /** targetIndex is the page's final zero-based position in the ordered list. */
    fun movePage(pageId: String, targetIndex: Int): Boolean {
        val index = notebook.pages.indexOfFirst { it.id == pageId }
        require(index >= 0) { "Page not found" }
        require(targetIndex in notebook.pages.indices) { "Invalid page position" }
        if (index == targetIndex) return false
        val pages = notebook.pages.toMutableList()
        val page = pages.removeAt(index)
        pages.add(targetIndex, page)
        return commit(notebook.copy(pages = pages))
    }
    /** Remove one source PDF across all its pages; preserve annotations by default. One Undo. */
    /** Remove selected imported pages or only their backgrounds, as one reversible edit. */
    fun removePdfPages(pageIds:Set<String>,keepAnnotations:Boolean):Boolean {
        require(pageIds.isNotEmpty()) {"Choose PDF pages"}
        require(pageIds.all {id->notebook.pages.any {it.id==id && it.pdfBackground!=null}}) {"The PDF pages changed"}
        val pages=notebook.pages.mapNotNull {page->
            if(page.id !in pageIds)page else if(keepAnnotations)page.copy(pdfBackground=null)else null
        }.ifEmpty {listOf(Page())}
        val pdfs=pages.mapNotNull {it.pdfBackground?.assetId}.toSet()
        val images=pages.flatMap {it.imageAssetIds}.toSet()
        val files=pages.flatMap {it.attachmentAssetIds}.toSet()
        return commit(notebook.copy(pages=pages,assets=notebook.assets.filter {it.id in pdfs},imageAssets=notebook.imageAssets.filter {it.id in images},attachmentAssets=notebook.attachmentAssets.filter {it.id in files}))
    }

    fun removePdfSource(assetId: String, deletePages: Boolean = false): Boolean {
        require(notebook.pages.any { it.pdfBackground?.assetId == assetId }) { "PDF source not found" }
        val pages = notebook.pages.mapNotNull { page ->
            if (page.pdfBackground?.assetId != assetId) page
            else if (deletePages) null
            else page.copy(pdfBackground = null, paper = PaperStyle.DEFAULT)
        }.ifEmpty { listOf(Page()) }
        val pdfs = pages.mapNotNull { it.pdfBackground?.assetId }.toSet()
        val images = pages.flatMap { it.imageAssetIds }.toSet()
        val files = pages.flatMap { it.attachmentAssetIds }.toSet()
        return commit(notebook.copy(pages = pages, assets = notebook.assets.filter { it.id in pdfs },
            imageAssets = notebook.imageAssets.filter { it.id in images }, attachmentAssets = notebook.attachmentAssets.filter { it.id in files }))
    }

    fun deletePage(pageId: String): Boolean = deletePages(setOf(pageId))
    /** P178 (G3): moves [pageIds] as one block, in notebook order, so the first lands at [targetIndex] of the result. One Undo step. */
    fun movePages(pageIds: Set<String>, targetIndex: Int): Boolean {
        require(pageIds.isNotEmpty() && pageIds.all { id -> notebook.pages.any { it.id == id } }) { "Page not found" }
        val moving = notebook.pages.filter { it.id in pageIds }
        val rest = notebook.pages.filterNot { it.id in pageIds }
        require(targetIndex in 0..rest.size) { "Invalid page position" }
        val pages = rest.toMutableList().apply { addAll(targetIndex, moving) }
        if (pages.map { it.id } == notebook.pages.map { it.id }) return false
        return commit(notebook.copy(pages = pages))
    }
    /** Removes [pageIds] as one Undo step (P178, G3); one page always stays, and assets that remaining pages use stay. */
    fun deletePages(pageIds: Set<String>): Boolean {
        require(pageIds.isNotEmpty() && pageIds.all { id -> notebook.pages.any { it.id == id } }) { "Page not found" }
        val pages = notebook.pages.filterNot { it.id in pageIds }
        require(pages.isNotEmpty()) { "Keep at least one page in the notebook" }
        val referenced = pages.mapNotNull { it.pdfBackground?.assetId }.toSet()
        val imageReferences = pages.flatMap { it.imageAssetIds }.toSet()
        val fileReferences = pages.flatMap { it.attachmentAssetIds }.toSet()
        return commit(notebook.copy(pages = pages, assets = notebook.assets.filter { it.id in referenced }, imageAssets = notebook.imageAssets.filter { it.id in imageReferences },
            attachmentAssets = notebook.attachmentAssets.filter { it.id in fileReferences }))
    }
    /** Attaches [asset] as a card on [pageId] (F107); identical files share one stored copy. */
    fun insertAttachment(pageId: String, asset: AttachmentAsset, name: String, mimeType: String, placement: ImagePlacement, id:String=UUID.randomUUID().toString()): String {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        val existing = notebook.attachmentAssets.firstOrNull { it.id == asset.id }
        require(existing == null || existing == asset) { "Conflicting attached file" }
        require(notebook.pages.none {p->p.attachments.any {it.id==id}}) { "Duplicate attachment card" }
        val card = FileAttachment(asset.id, name, mimeType, placement, id=id)
        commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(attachments = it.attachments + card) else it },
            attachmentAssets = if (existing == null) notebook.attachmentAssets + asset else notebook.attachmentAssets))
        return card.id
    }
    /** Changes only card placement; lock protection matches photos and text. */
    fun updateAttachment(pageId: String, id: String, placement: ImagePlacement): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        val card = page.attachments.firstOrNull { it.id == id } ?: error("Attachment not found")
        require(!card.locked) { "Unlock that attachment first" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(
            attachments = it.attachments.map { a -> if (a.id == id) a.copy(placement = placement) else a }) else it }))
    }
    /** Removes cards; a stored file goes with the last card that uses it. */
    fun removeAttachments(pageId: String, ids: Set<String>): Boolean {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        require(notebook.pages.first { it.id == pageId }.attachments.none { it.id in ids && it.locked }) { "Unlock that attachment first" }
        val pages = notebook.pages.map { if (it.id == pageId) it.copy(attachments = it.attachments.filterNot { card -> card.id in ids }) else it }
        val referenced = pages.flatMap { it.attachmentAssetIds }.toSet()
        return commit(notebook.copy(pages = pages, attachmentAssets = notebook.attachmentAssets.filter { it.id in referenced }))
    }
    /** Atomically configure selected pages, scaling stored geometry with dimensions. */
    fun configurePages(pageIds: Set<String>, title: String? = null, width: Float? = null, height: Float? = null,
        paper: PaperStyle? = null, replaceBackgrounds: Boolean = false): Boolean =
        commit(notebook.configuredPages(pageIds, title, width, height, paper, replaceBackgrounds))

    /** A reusable fixed paper background; destination ink stays editable and the whole operation is one Undo. */
    fun applyPageTemplate(pageIds: Set<String>, asset: ImageAsset, paper: PaperStyle, width: Float? = null, height: Float? = null, replaceBackgrounds: Boolean = false): Boolean {
        require(notebook.pages.filter { it.id in pageIds }.none { it.hasBackground } || replaceBackgrounds) { "Enable Replace imported backgrounds first" }
        val configured=notebook.configuredPages(pageIds,width=width,height=height,paper=paper,replaceBackgrounds=replaceBackgrounds)
        val assets=configured.imageAssets.associateBy { it.id }.toMutableMap()
        require(assets[asset.id]==null || assets[asset.id]==asset) { "Conflicting template asset" };assets[asset.id]=asset
        return commit(configured.copy(pages=configured.pages.map { if(it.id in pageIds) it.copy(imageBackground=ImageBackground(asset.id),pdfBackground=null,paper=paper) else it },imageAssets=assets.values.toList()))
    }

    /** Explicit page-scale correction, including all selected pages in one Undo step. */
    fun matchStandardPageScale(pageIds: Set<String>): Boolean = commit(notebook.withStandardPageScale(pageIds))
    /** P412: Standard page shape with blank margins for all selected pages in one Undo step; see [withStandardPageShape]. */
    fun matchStandardPageShape(pageIds: Set<String>, padded: Map<String, Pair<PdfAsset, PdfBackground>> = emptyMap()): Boolean =
        commit(notebook.withStandardPageShape(pageIds, padded))

    /** Legacy canvas-only resize; page settings uses configurePages to scale content as well. */
    fun resizePage(pageId: String, width: Float, height: Float): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(!page.hasBackground) { "Background pages retain their original dimensions" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(width = width, height = height) else it }))
    }
    /** P546 (F063): more room on a blank page; room above or to the left moves everything on it along, in one Undo step. */
    fun addPageRoom(pageId: String, direction: PageRoom.Direction, pages: Float): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(!page.hasBackground) { "Background pages retain their original dimensions" }
        val size = PageRoom.grown(page, direction, pages) ?: return false
        val shift = PageRoom.shift(page, direction, size)
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.withRoom(size.first, size.second, shift.x, shift.y) else it }))
    }
    fun setPaper(pageId: String, paper: PaperStyle): Boolean {
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: error("Page not found")
        require(!page.hasBackground) { "Paper settings are available for blank pages" }
        return commit(notebook.copy(pages = notebook.pages.map { if (it.id == pageId) it.copy(paper = paper) else it }))
    }
    fun clearPageInk(pageId: String): Boolean {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        // P101: locked ink, and ink on locked layers, stays.
        return commit(notebook.copy(pages = notebook.pages.map { page -> if (page.id != pageId) page else {
            val closed = page.layers.filter { it.locked }.map { it.id }.toSet()
            page.copy(strokes = page.strokes.filter { it.locked || it.layer in closed })
        } }))
    }
    /** A bookmark belongs to the page identity, independent of ink or source PDF contents. */
    /** [level] null keeps the page's outline level; removing the bookmark resets it to the top level. */
    fun setBookmark(pageId: String, name: String?, level: Int? = null): Boolean {
        require(notebook.pages.any { it.id == pageId }) { "Page not found" }
        require(level == null || level in 1..Page.MAX_OUTLINE_LEVEL) { "Outline level must be 1–3" }
        val normalized = name?.trim()
        return commit(notebook.copy(pages = notebook.pages.map {
            if (it.id == pageId) it.copy(bookmark = normalized, outlineLevel = if (normalized == null) 1 else level ?: it.outlineLevel) else it
        }))
    }
    /** Replays a verified local recovery operation through normal validation and Undo history. */
    fun applyRecoveredNotebook(next: Notebook): Boolean = commit(next)
    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        redo.addLast(notebook); notebook = undo.removeLast(); return true
    }
    fun redo(): Boolean {
        if (redo.isEmpty()) return false
        undo.addLast(notebook); notebook = redo.removeLast(); return true
    }
}

private fun InkStroke.translated(dx: Float, dy: Float): InkStroke = copy(
    samples = samples.map { it.copy(x = it.x + dx, y = it.y + dy) },
    outlines = outlines.map { outline -> outline.map { Point(it.x + dx, it.y + dy) } },
    erasures = erasures.map { sweep -> sweep.copy(points = sweep.points.map { Point(it.x + dx, it.y + dy) }) },
)

/** Copies of grouped strokes form their own new groups, so a copy never joins the original. */
private fun regrouped(strokes: List<InkStroke>): List<InkStroke> {
    val fresh = strokes.mapNotNull { it.group }.distinct().associateWith { UUID.randomUUID().toString() }
    return strokes.map { stroke -> stroke.group?.let { stroke.copy(group = fresh.getValue(it)) } ?: stroke }
}
/** P098 (F050): [ids] plus every stroke that shares a group with one of them. */
fun Page.withGroups(ids: Set<String>): Set<String> {
    val groups = strokes.filter { it.id in ids }.mapNotNull { it.group }.toSet()
    return if (groups.isEmpty()) ids else ids + strokes.filter { it.group in groups }.map { it.id }
}

/** Raw centreline proximity only. Masked strokes require visible-geometry hit testing (InkGeometry on Android). */
fun hits(stroke: InkStroke, point: Point, radius: Float): Boolean {
    val threshold = radius + stroke.width / 2
    val samples = stroke.samples
    if (samples.size == 1) return hypot(samples[0].x - point.x, samples[0].y - point.y) <= threshold
    return samples.zipWithNext().any { (a, b) ->
        val dx = b.x - a.x; val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared == 0f) 0f else (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        hypot(a.x + fraction * dx - point.x, a.y + fraction * dy - point.y) <= threshold
    }
}
