package dev.ownednotes.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import dev.ownednotes.core.*
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Replaceable test surface. Only stylus/eraser tool types can create document operations. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
/** P568: a fingertip is broader than a nib and hides the ruler's edge. */
private const val FINGER_RULER_REACH_DP = 48f
/** P568: stored for every finger sample, so a finger's line stays even on any device. */
private const val FINGER_PRESSURE = .6f

class InkCanvas(context: Context, private val repository: NotebookRepository, private val changed: () -> Unit, private val failure: (String) -> Unit) : FrameLayout(context) {
    private val router = PenRouter()
    private val viewport = Matrix()
    private val inverse = Matrix()
    internal var onZoomWritingRequested:()->Unit={}
    internal var onWritingFinished:()->Unit={}
    private var fixedWritingRegion:RectF?=null
    internal fun setWritingRegion(region:RectF) {
        if(isEditing)return
        fixedWritingRegion=RectF(region);fitted=false
        if(width>0 && height>0)fit()
    }
    /** P530 (F081/F082): the shared toolbar's tools follow focus. Pages, zoom and selection stay with this pane. */
    internal fun adoptToolsFrom(source:InkCanvas) {
        if(isEditing)return
        val finger=source.drawWithFinger;val lasso=source.lassoEnabled;val paint=source.painter;val scope=source.paintScope;val shape=source.shapePen
        mirrorWritingTools(source)
        drawWithFinger=finger;if(lasso)selectLasso(true) else clearSelectionForTool()
        painter=paint;paintScope=scope;shapePen=shape
    }
    private fun clearSelectionForTool() { if(lassoEnabled||selectedCount>0)selectLasso(false) }
    internal fun mirrorWritingTools(source:InkCanvas) {
        if(isEditing)return
        if(laser.enabled != source.laserEnabled) laser.enabled = source.laserEnabled
        if(penStyle!=source.penStyle){penStyle=source.penStyle;brush=makeBrush(penStyle)}
        if(highlighterStyle!=source.highlighterStyle){highlighterStyle=source.highlighterStyle;highlighterBrush=makeHighlighter(highlighterStyle)}
        eraser=source.eraser;eraserMode=source.eraserMode;eraserRadius=source.eraserRadius;eraserTarget=source.eraserTarget
        highlighter=source.highlighter;highlighterFollowsText=source.highlighterFollowsText
        buttonAction=source.buttonAction;holdToShape=source.holdToShape;holdSettings=source.holdSettings
        penAssist=source.penAssist;highlighterAssist=source.highlighterAssist
        drawWithFinger=source.drawWithFinger;lassoEnabled=false;painter=false;inputEnabled=source.inputEnabled
    }
    private val laser = LaserPointer(context) { pageRect }
    val laserEnabled get() = laser.enabled
    internal val laserTraceCount get() = laser.traceCount
    internal val laserContact get() = laser.contact
    private var laserPage: Pair<String?,String?>? = null
    fun selectLaser(enabled: Boolean) {
        check(!isEditing && !navigating) { "Finish the current gesture first" }
        cancelActive(); clearPdfTextSelection(); laser.enabled = enabled
        laserPage = repository.session?.notebook?.id to repository.selectedPageId
        showHint(if(enabled) "Laser pointer: temporary marks only" else "Writing tool restored")
        publishChange()
    }
    private var fitted = false
    var eraser = false
        set(value) { field = value; if (value) painter = false }
    /** B31 (P193): the paint brush: strokes it passes over take the selected pen's settings, one Undo step per brush stroke. */
    var painter = false
        set(value) { field = value; if (value) eraser = false }
    enum class PaintScope(val label: String) { ALL("Everything"), COLOUR("Colour"), WIDTH("Thickness") }
    enum class EraserMode { STROKE, AREA }
    enum class EraserTarget(val label: String) {
        ALL("All"), PEN("Pen"), HIGHLIGHTER("Highlights");
        fun accepts(stroke: InkStroke) = when (this) {
            ALL -> true
            PEN -> !stroke.isHighlighter
            HIGHLIGHTER -> stroke.isHighlighter
        }
    }
    private val toolPreferences = (context.applicationContext as NotesApplication).let { it.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE) }
    /** P568: a finger writes until a real pen is first seen; a choice made on the switch always wins. */
    var drawWithFinger = if (toolPreferences.contains("drawWithFinger")) toolPreferences.getBoolean("drawWithFinger", false)
        else !(context.applicationContext as NotesApplication).penSeen
        private set(value) { field = value; touch.fingerWriting = value }
    private var stylusHovering = false
    /** P568: the first real pen hands page moving back to one finger, unless the user chose otherwise. */
    internal fun penArrived() {
        if (toolPreferences.contains("drawWithFinger") || !drawWithFinger) return
        drawWithFinger = false; cancelCurl(); showHint("Pen found: fingers now move the page", 3000); publishChange()
    }
    private fun firstFingerHint() {
        if (toolPreferences.contains("drawWithFinger") || toolPreferences.getBoolean("fingerHintShown", false)) return
        toolPreferences.edit().putBoolean("fingerHintShown", true).apply()
        showHint("Writing with your finger. Two fingers move the page.", 3000)
    }
    /** P568: one finger that may stand in for the pen: not a palm, and no pen near. */
    private fun fingerMayWrite(event: MotionEvent) = drawWithFinger && !stylusHovering &&
        !touch.isHandLocked(event.eventTime) && event.pointerCount == 1 &&
        event.getTouchMajor(0) < 18f * resources.displayMetrics.xdpi / 25.4f
    fun selectDrawWithFinger(enabled: Boolean) {
        check(!isEditing && !navigating) { "Finish the current gesture first" }
        drawWithFinger = enabled; toolPreferences.edit().putBoolean("drawWithFinger", enabled).apply()
        cancelCurl(); publishChange()
    }
    var eraserMode = runCatching { EraserMode.valueOf(toolPreferences.getString("eraserMode", "STROKE")!!) }.getOrDefault(EraserMode.STROKE)
        set(value) { field = value; toolPreferences.edit().putString("eraserMode", value.name).apply() }
    var eraserTarget = runCatching { EraserTarget.valueOf(toolPreferences.getString("eraserTarget", "ALL")!!) }.getOrDefault(EraserTarget.ALL)
        private set
    fun selectEraserTarget(target: EraserTarget) {
        eraserTarget = target; toolPreferences.edit().putString("eraserTarget", target.name).apply()
        publishChange()
    }
    var paintScope = runCatching { PaintScope.valueOf(toolPreferences.getString("paintScope", "ALL")!!) }.getOrDefault(PaintScope.ALL)
        private set
    fun selectPaintScope(scope: PaintScope) {
        paintScope = scope; toolPreferences.edit().putString("paintScope", scope.name).apply()
        publishChange()
    }
    /** What the paint brush gives a pen stroke ([highlight] false) or a highlight under [paintScope]. */
    fun paintChange(highlight: Boolean): InkSelection.Restyle {
        val style = if (highlight) highlighterStyle else penStyle
        return InkSelection.Restyle(kind = style.kind.takeIf { !highlight && paintScope == PaintScope.ALL },
            color = style.color.takeIf { paintScope != PaintScope.WIDTH }, width = style.width.takeIf { paintScope != PaintScope.COLOUR })
    }
    private fun savedSmoothing(kind: PenKind) = toolPreferences.getInt("penSmoothing.${kind.name}", 50).coerceIn(0, 100)
    var penStyle = runCatching {
        val kind = runCatching { PenKind.valueOf(toolPreferences.getString("penKind", null).orEmpty()) }.getOrDefault(PenKind.PRESSURE)
        PenStyle(toolPreferences.getLong("penColor", 0xff182636), toolPreferences.getFloat("penWidth", 4f), kind,
            runCatching { PenStyle(opacity = toolPreferences.getFloat("penOpacity", 1f)).opacity }.getOrDefault(1f), savedSmoothing(kind))
    }.getOrDefault(PenStyle())
        private set
    var eraserRadius = runCatching { toolPreferences.getFloat("eraserRadius", 12f).also { require(it.isFinite() && it in 1f..100f) } }.getOrDefault(12f)
        private set
    fun selectPenStyle(style: PenStyle) {
        val chosen = if (style.kind != penStyle.kind) style.copy(smoothing = savedSmoothing(style.kind)) else style
        InkColors(toolPreferences).apply { remember(chosen.color); use(chosen.color, highlighter = false) }
        val nextBrush = makeBrush(chosen)
        penStyle = chosen; brush = nextBrush
        toolPreferences.edit().putLong("penColor", chosen.color).putFloat("penWidth", chosen.width).putString("penKind", chosen.kind.name)
            .putFloat("penOpacity", chosen.opacity).putInt("penSmoothing.${chosen.kind.name}", chosen.smoothing).apply()
        publishChange()
    }
    fun selectEraserRadius(radius: Float) {
        require(radius.isFinite() && radius in 1f..100f) { "Eraser size must be 2–200 page units across" }
        eraserRadius = radius; toolPreferences.edit().putFloat("eraserRadius", radius).apply()
        publishChange()
    }
    /** Presentation-only notice that the S Pen button override changed; it must not touch input or repository state. */
    var onToolStateChanged: () -> Unit = {}
    enum class ButtonAction(val label: String) { ERASER("Eraser"), PEN("Pen"), SELECTED_TOOL("Selected tool") }
    var buttonAction = ButtonAction.entries.firstOrNull { it.name == toolPreferences.getString("buttonAction", null) } ?: ButtonAction.ERASER
        private set
    private var buttonHeld = false; set(value) { if (field != value) { field = value; onToolStateChanged() } }
    val effectiveEraser get() = if (buttonHeld) when (buttonAction) {
        ButtonAction.ERASER -> true
        ButtonAction.PEN -> false
        ButtonAction.SELECTED_TOOL -> eraser
    } else eraser
    val effectiveLasso get() = lassoEnabled && !effectiveEraser && !(buttonHeld && buttonAction == ButtonAction.PEN)
    val effectivePainter get() = painter && !effectiveEraser && !(buttonHeld && buttonAction == ButtonAction.PEN)
    val effectivePen get() = !effectiveEraser && !effectiveLasso && !effectivePainter
    val effectiveHighlighter get() = effectivePen && highlighter && !(buttonHeld && buttonAction == ButtonAction.PEN)
    fun selectButtonAction(action: ButtonAction) {
        check(!isEditing) { "Wait for input completion" }
        buttonHeld = false; buttonAction = action
        toolPreferences.edit().putString("buttonAction", action.name).apply()
        publishChange()
    }
    var inputEnabled = true
        set(value) { field = value; if (!value) { cancelCurl(); laser.clear() } }
    /** Desk colour behind the page (design token); presentation only. */
    var deskColor = Tokens.DESK; set(value) { field = value; dry.invalidate() }
    private fun makeBrush(style: PenStyle) = brushFor(style)
    private var brush = makeBrush(penStyle)
    /** Highlighter tool (P029): its own colour and width; a translucent chisel tip that tints rather than covers. */
    var highlighter = false
        private set
    // P620: a new install starts with board 10's vivid yellow; a saved colour stays.
    var highlighterStyle = runCatching { PenStyle(toolPreferences.getLong("highlighterColor", 0xffffe600), toolPreferences.getFloat("highlighterWidth", 18f)) }.getOrDefault(PenStyle(0xffffe600, 18f))
        private set
    /** Wet preview colour carries the opacity; persisted strokes keep the opaque colour and the brush id. */
    private fun makeHighlighter(style: PenStyle, width: Float = style.width) = Brush.createWithColorIntArgb(HighlighterBrush.family,
        (style.color.toInt() and 0xffffff) or ((255 * InkStroke.HIGHLIGHTER_OPACITY).roundToInt() shl 24), width, .1f)
    private var highlighterBrush = makeHighlighter(highlighterStyle)
    /** Raster PDF line assistance; each stroke keeps the preference it started with. */
    var highlighterFollowsText = toolPreferences.getBoolean("highlighterFollowsText", true)
        private set
    /** P617 (board 11): Mix colours. Off (the default), going over a highlight never darkens it; on, overlaps darken and blend. Display only. */
    var highlighterMix = toolPreferences.getBoolean("highlighterMix", false).also { InkGeometry.mixHighlights = it }
        private set
    fun selectHighlighterMix(enabled: Boolean) {
        highlighterMix = enabled; InkGeometry.mixHighlights = enabled
        toolPreferences.edit().putBoolean("highlighterMix", enabled).apply()
        dry.invalidate(); invalidate(); publishChange()
    }
    fun selectHighlighterFollowsText(enabled: Boolean) {
        highlighterFollowsText = enabled
        toolPreferences.edit().putBoolean("highlighterFollowsText", enabled).apply()
        publishChange()
    }
    /** Safe at any time, like [selectPenStyle]: a pending stroke keeps the tool it started with; the next segment uses the new one. */
    fun selectHighlighter(enabled: Boolean) {
        highlighter = enabled
        publishChange()
    }
    fun selectHighlighterStyle(style: PenStyle) {
        InkColors(toolPreferences).apply { remember(style.color); use(style.color, highlighter = true) }
        val next = makeHighlighter(style)
        highlighterStyle = style; highlighterBrush = next
        toolPreferences.edit().putLong("highlighterColor", style.color).putFloat("highlighterWidth", style.width).apply()
        publishChange()
    }
    private data class Pending(val inkId: InProgressStrokeId?, val started: Long, val erase: Boolean, val notebookId: String, val pageId: String, val mode: EraserMode, val style: PenStyle, val brush: Brush, val radius: Float, val samples: MutableList<Sample> = mutableListOf(), val erased: MutableSet<String> = linkedSetOf(), val kind: String = InkStroke.PEN, val target: EraserTarget = EraserTarget.ALL, val paint: Boolean = false, val audio: dev.ownednotes.core.AudioLink? = null) {
        /** B31 (P193): a paint segment's touched strokes ([erased] holds every touched id), restyled, with their outlines for the live preview. */
        val painted = linkedMapOf<String, Pair<InkStroke, Path>>()
        var penPaint = InkSelection.Restyle()
        var markPaint = InkSelection.Restyle()
        /** Hold-to-shape (P025): null for eraser segments or when the preference is off. */
        var hold: ShapeRecognizer.HoldDetector? = null
        var assist: HoldAssist = HoldAssist.SHAPES
        var followText = false
        /** Set once held ink snaps; subsequent contact adjusts geometry without appending to the freehand baseline. */
        var snap: Snap? = null
        var heldShape: HeldShape? = null
        var adjustment: HeldShape.Result? = null
        var renderedAdjustment: HeldShape.Result? = null
        var adjustmentGeometryUs = 0L
    }
    /** A snapped shape ready to commit, plus the morph endpoints (equal-count resamples) for its animation. */
    private class Snap(val kind: ShapeRecognizer.Kind, val stroke: InkStroke, val fill: Path, val exact: Path, val from: List<Point>, val to: List<Point>,
        val guides: List<Pair<Point, Point>> = emptyList())
    /** Hold the pen still at the end of a stroke to snap it into a line or shape. Local preference; on by default. */
    var holdToShape = toolPreferences.getBoolean("holdToShape", true)
        private set
    var holdSettings = runCatching { HoldSettings(toolPreferences.getLong("holdDelayMs", 500), toolPreferences.getFloat("holdToleranceMm", 1.2f)) }.getOrDefault(HoldSettings())
        private set
    var penAssist = runCatching { HoldAssist.valueOf(toolPreferences.getString("penHoldAssist", null).orEmpty()) }.getOrDefault(HoldAssist.SHAPES)
        private set
    var highlighterAssist = runCatching { HoldAssist.valueOf(toolPreferences.getString("highlighterHoldAssist", null).orEmpty()) }.getOrDefault(HoldAssist.SHAPES)
        private set
    fun selectHoldSettings(settings: HoldSettings) {
        check(!isEditing) { "Wait for input completion" }
        holdSettings = settings
        toolPreferences.edit().putLong("holdDelayMs", settings.delayMs).putFloat("holdToleranceMm", settings.toleranceMm).apply()
        publishChange()
    }
    fun selectHoldAssist(highlighter: Boolean, mode: HoldAssist) {
        check(!isEditing) { "Wait for input completion" }
        if (highlighter) highlighterAssist = mode else penAssist = mode
        val edit = toolPreferences.edit().putString(if (highlighter) "highlighterHoldAssist" else "penHoldAssist", mode.name)
        if (mode != HoldAssist.FREEHAND) { holdToShape = true; edit.putBoolean("holdToShape", true) }
        edit.apply(); publishChange()
    }
    /** Two-finger tap undoes (P033). Local preference; on by default. */
    var twoFingerUndo = toolPreferences.getBoolean("twoFingerUndo", true)
        private set
    fun selectTwoFingerUndo(enabled: Boolean) { twoFingerUndo = enabled; toolPreferences.edit().putBoolean("twoFingerUndo", enabled).apply() }
    /** B20 (P136): the shape pen: each pen stroke becomes the shape it looks like when the pen lifts; unclear ones stay freehand. */
    var shapePen = toolPreferences.getBoolean("shapePen", false)
        private set
    fun selectShapePen(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        shapePen = enabled; toolPreferences.edit().putBoolean("shapePen", enabled).apply()
        publishChange()
    }
    fun selectHoldToShape(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        holdToShape = enabled; toolPreferences.edit().putBoolean("holdToShape", enabled).apply()
        publishChange()
    }
    /** The shape the pen is currently holding, for tests and accessibility; null when nothing has snapped. */
    val snappedShape get() = active.values.firstNotNullOfOrNull { it.snap?.kind }
    /** P201 step 5: a held stroke became this shape; [after] is the notebook that holds it (for "Keep my stroke"). */
    var onShapeSnapped: ((kind: ShapeRecognizer.Kind, after: Notebook) -> Unit)? = null
    private val active = linkedMapOf<Int, Pending>()
    private val finishing = linkedMapOf<InProgressStrokeId, Pending>()
    private var pendingLassoCompletion: (() -> Unit)? = null
    private val selection: InkSelection = InkSelection(repository, failure).apply {
        onPicked = {
            pendingLassoCompletion?.also { pendingLassoCompletion = null }?.invoke()
            dry.invalidate(); publishChange()
        }
        reshapeStroke = { stroke, points ->
            val pressure=stroke.samples.first().pressure
            restroke(stroke.copy(samples=points.mapIndexed { i,p -> Sample(p.x,p.y,i*4L,pressure) }),stroke.width)
        }
    }
    private val shapePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND }
    private val photoSelection = PhotoSelection(repository, failure)
    private val textSelection = TextSelection(repository, failure)
    init {
        InkColors(toolPreferences).apply { remember(penStyle.color); remember(highlighterStyle.color) }
        selection.configure(InkSelection.Options(
            InkSelection.Shape.entries.firstOrNull { it.name == toolPreferences.getString("selectionShape", null) } ?: InkSelection.Shape.FREEFORM,
            InkSelection.Rule.entries.firstOrNull { it.name == toolPreferences.getString("selectionRule", null) } ?: InkSelection.Rule.TOUCHING,
            InkSelection.Filter.entries.firstOrNull { it.name == toolPreferences.getString("selectionFilter", null) } ?: InkSelection.Filter.ALL,
        ))
    }
    val selectionOptions get() = selection.options
    fun selectLassoOptions(options: InkSelection.Options) {
        check(!isEditing) { "Wait for input completion" }
        selection.configure(options); photoSelection.clear(); textSelection.clear()
        toolPreferences.edit().putString("selectionShape", options.shape.name).putString("selectionRule", options.rule.name).putString("selectionFilter", options.filter.name).apply()
        publishChange()
    }
    var lassoEnabled = false; private set(value) { field = value; if (value) painter = false }
    val selectedIds get() = selection.objectIds.strokes
    val mixedSelection get() = selection.hasObjects
    val selectedObjectIds get() = selection.objectIds.let { ObjectIds(it.strokes, it.images + setOfNotNull(selectedPhotoId), it.texts + setOfNotNull(selectedTextId)) }
    val selectedPhotoId get() = photoSelection.id
    val selectedTextId get() = textSelection.id
    val selectedCount get() = selection.ids.size + (if (selectedPhotoId != null) 1 else 0) + (if (selectedTextId != null) 1 else 0)
    private val photoViewport get() = RectF(0f, 0f, width.toFloat(), height.toFloat()).also { inverse.mapRect(it) }
    private fun selectObjectGroup(parts: dev.ownednotes.core.ObjectIds): Boolean {
        if (selection.options.filter != InkSelection.Filter.ALL) return false
        val page = repository.currentPage ?: return false
        val grouped = page.images.any { it.id in parts.images && it.group != null } || page.textBoxes.any { it.id in parts.texts && it.group != null }
        if (!grouped) return false
        photoSelection.clear(); textSelection.clear()
        selection.select(parts.selectionKeys(), pickablePaths(InkSelection.Filter.ALL).keys)
        lassoEnabled = true; eraser = false
        return selection.ids.isNotEmpty()
    }
    fun selectPhoto(id: String): Boolean {
        check(!isEditing)
        selection.clear(); textSelection.clear()
        if (selectObjectGroup(dev.ownednotes.core.ObjectIds(images=setOf(id)))) { publishChange(); return true }
        val selected = photoSelection.select(id)
        if (selected) { lassoEnabled = true; eraser = false }
        publishChange(); return selected
    }
    fun selectText(id: String): Boolean {
        check(!isEditing)
        selection.clear(); photoSelection.clear()
        if (selectObjectGroup(dev.ownednotes.core.ObjectIds(texts=setOf(id)))) { publishChange(); return true }
        val selected = textSelection.select(id)
        if (selected) { lassoEnabled = true; eraser = false }
        publishChange(); return selected
    }
    /** A fresh view-space rectangle, including the transient selection outline. */
    private val handleUnit get() = resources.displayMetrics.density / pageScale
    private val viewportTop get() = floatArrayOf(0f, 0f).also { inverse.mapPoints(it) }[1]
    // Chrome asks even while the Library covers the editor. Empty selection needs no page paths.
    val selectionHandlePointsInView: List<Point> get() {
        if (selectedCount == 0) return emptyList()
        return (if (selectedTextId != null) textSelection.points(handleUnit, photoViewport) else if (selectedPhotoId != null) photoSelection.points(handleUnit, photoViewport) else selection.pointHandles.takeIf { it.isNotEmpty() }?.let { it + selection.handles(selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), handleUnit, viewportTop)!!.points[8] } ?: selection.handles(selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), handleUnit, viewportTop)?.points.orEmpty()).map {
            val xy = floatArrayOf(it.x, it.y); viewport.mapPoints(xy); Point(xy[0], xy[1])
        }
    }
    val selectedBoundsInView: RectF? get() {
        if (selectedCount == 0) return null
        return (if (selectedTextId != null) textSelection.bounds(handleUnit, photoViewport) else if (selectedPhotoId != null) photoSelection.bounds(handleUnit, photoViewport) else selection.selectedBounds(selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), handleUnit, viewportTop))?.also { viewport.mapRect(it) }
    }
    var onViewportChanged: () -> Unit = {}
    fun selectLasso(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        lassoEnabled = enabled; selection.clear(); photoSelection.clear(); textSelection.clear(); lockedPick = null; pasteSpot = null
        if (enabled) eraser = false
        publishChange()
    }
    fun clearSelection() { check(!isEditing); selection.clear(); photoSelection.clear(); textSelection.clear(); lockedPick = null; pasteSpot = null; publishChange() }
    fun deleteSelection() { check(!isEditing); if (repository.readOnly) return; if (selectedTextId != null) textSelection.delete() else if (selectedPhotoId != null) photoSelection.delete() else selection.delete(); publishChange() }
    fun duplicateSelection() { check(!isEditing); if (repository.readOnly) return; if (selectedTextId != null) textSelection.duplicate() else if (selectedPhotoId != null) photoSelection.duplicate() else selection.duplicate(); publishChange() }
    /** P101 (F074): locks the selection; it stays drawn but can't be picked again until the page menu unlocks it. */
    fun lockSelection(): Boolean {
        check(!isEditing)
        val notebook = repository.session?.notebook ?: return false
        val pageId = repository.selectedPageId ?: return false
        val parts = selection.objectIds
        val strokes = parts.strokes; val photo = selectedPhotoId; val text = selectedTextId
        if (repository.readOnly || (selection.ids.isEmpty() && photo == null && text == null)) return false
        val changed = repository.editLocks(notebook, pageId) { it.setObjectsLocked(pageId, strokes, parts.images + setOfNotNull(photo), parts.texts + setOfNotNull(text), locked = true) }
        if (changed) { clearSelection(); showHint("Locked. Touch and hold it to unlock") }
        return changed
    }
    /** B12: locked objects the user held, tapped or circled; the editor offers Unlock beside them. Bounds in page units. */
    private data class LockedPick(val pageId: String, val strokes: Set<String>, val photos: Set<String>, val texts: Set<String>, val bounds: RectF, val attachments: Set<String>)
    private var lockedPick: LockedPick? = null
    val lockedPickBoundsInView: RectF? get() {
        val pick = lockedPick ?: return null
        if (pick.pageId != repository.selectedPageId || repository.readOnly) { lockedPick = null; return null }
        return RectF(pick.bounds).also { viewport.mapRect(it) }
    }
    fun dismissLockedPick() { if (lockedPick != null) { lockedPick = null; publishChange() } }
    /** B35 (P198): where a lasso tap on empty paper asked to paste; the editor offers Paste beside it. Page units. */
    private data class PasteSpot(val pageId: String, val at: Point)
    private var pasteSpot: PasteSpot? = null
    private var pasteTap: FloatArray? = null
    private var pasteTapTime = 0L
    private val hasPasteable get() = (copiedHere || outsideClip() != SystemClipboard.Outside.NONE) && !repository.fileBusy
    /** The spot's page point while it still applies, or null. */
    val pasteSpotPoint: Point? get() {
        val spot = pasteSpot ?: return null
        if (spot.pageId != repository.selectedPageId || repository.readOnly || !hasPasteable || selectedCount > 0) { pasteSpot = null; return null }
        return spot.at
    }
    /** A small square around the spot in view coordinates, for placing the Paste bar beside it. */
    val pasteSpotInView: RectF? get() = pasteSpotPoint?.let { val r = 8 * resources.displayMetrics.density
        floatArrayOf(it.x, it.y).also { xy -> viewport.mapPoints(xy) }.let { xy -> RectF(xy[0] - r, xy[1] - r, xy[0] + r, xy[1] + r) } }
    fun dismissPasteSpot() { if (pasteSpot != null) { pasteSpot = null; publishChange() } }
    /** B12: picks the locked objects under [region], or within [slop] page units of [at]; true when any were found. */
    private fun pickLocked(region: Path?, at: Point?, slop: Float): Boolean {
        lockedPick = null
        val page = repository.currentPage ?: return false
        if (repository.readOnly) return false
        val probe = region ?: at?.let { Path().apply { addCircle(it.x, it.y, slop, Path.Direction.CW) } } ?: return false
        fun outline(p: ImagePlacement) = Path().apply {
            listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).forEachIndexed { i, (u, v) ->
                val q = p.pagePoint(u, v); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
            close()
        }
        val bounds = RectF(); var any = false
        fun hit(path: Path): Boolean {
            if (!InkGeometry.intersects(path, probe)) return false
            val b = RectF(); path.computeBounds(b, true)
            if (any) bounds.union(b) else bounds.set(b); any = true
            return true
        }
        val paths = visiblePaths(page.strokes)
        val strokes = page.drawnStrokes.filter { it.locked && paths[it.id]?.let(::hit) == true }.map { it.id }.toSet()
        val photos = page.images.filter { it.locked && page.canSee(it.layer) && hit(outline(it.placement)) }.map { it.id }.toSet()
        val texts = page.textBoxes.filter { it.locked && page.canSee(it.layer) && hit(outline(it.placement)) }.map { it.id }.toSet()
        val attachments = page.attachments.filter { it.locked && hit(outline(it.placement)) }.map { it.id }.toSet()
        if (!any) return false
        lockedPick = LockedPick(page.id, strokes, photos, texts, bounds, attachments)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        publishChange(); return true
    }
    /** B12: unlocks the picked objects in one undo step and selects them, so they can be moved straight away. */
    fun unlockPicked(): Boolean {
        check(!isEditing)
        val pick = lockedPick ?: return false
        lockedPick = null
        val notebook = repository.session?.notebook
        val page = repository.currentPage
        if (notebook == null || page == null || repository.readOnly || page.id != pick.pageId) { publishChange(); return false }
        val strokes = pick.strokes.filterTo(mutableSetOf()) { id -> page.strokes.any { it.id == id } }
        val photos = pick.photos.filterTo(mutableSetOf()) { id -> page.images.any { it.id == id } }
        val texts = pick.texts.filterTo(mutableSetOf()) { id -> page.textBoxes.any { it.id == id } }
        val changed = repository.editLocks(notebook, page.id) { it.setObjectsLocked(page.id, strokes, photos, texts, locked = false, attachments = pick.attachments) }
        if (!changed) { publishChange(); return false }
        showHint("Unlocked")
        when {
            strokes.isNotEmpty() -> { lassoEnabled = true; eraser = false; photoSelection.clear(); textSelection.clear(); selection.select(strokes); onToolStateChanged(); publishChange() }
            photos.size == 1 -> { selectPhoto(photos.first()); onToolStateChanged() }
            texts.size == 1 -> { selectText(texts.first()); onToolStateChanged() }
            else -> publishChange()
        }
        return true
    }
    /** P098 (F050): the selected ink is exactly one group, so the selection bar offers Ungroup. */
    val selectionIsGroup get() = selectedTextId == null && selectedPhotoId == null && selection.isOneGroup
    /** P098 (F050): Group two or more selected strokes, or Ungroup a selected group. */
    fun groupSelection(): Boolean {
        check(!isEditing)
        if (repository.readOnly || selectedTextId != null || selectedPhotoId != null) return false
        val ungroup = selection.isOneGroup
        val changed = selection.toggleGroup()
        if (changed) showHint(if (ungroup) "Ungrouped" else "Grouped ${selection.ids.size} objects")
        publishChange(); return changed
    }
    /** P072 eyedropper: colour of the topmost visible ink within [slop] view pixels of view point ([x], [y]), or null. */
    fun inkColorAt(x: Float, y: Float, slop: Float): Long? {
        val strokes = repository.currentPage?.strokes ?: return null
        val xy = floatArrayOf(x, y); inverse.mapPoints(xy)
        val probe = Path().apply { addCircle(xy[0], xy[1], slop / pageScale, Path.Direction.CW) }
        val paths = visiblePaths(strokes)
        return repository.currentPage!!.drawnStrokes.lastOrNull { InkGeometry.intersects(paths.getValue(it.id), probe) }?.color
    }
    /** B60: current-hand preview supplies only readable words; all other selected ink stays exact. */
    internal fun rewriteSelectedHandwriting(target: InkSelection.ColorTarget, pieces: List<HandStyle.Piece>): Boolean {
        if (isEditing || repository.readOnly || !repository.canChangeNotebook || repository.session?.notebook !== target.notebook) return false
        val groups = pieces.mapNotNull { piece ->
            val fresh = HandStyle.strokes(piece) ?: return@mapNotNull null
            piece.word.strokes to fresh.map { restroke(it, it.width) }
        }
        val applied = selection.rewriteHandwriting(target, groups)
        if (applied) publishChange()
        return applied
    }
    fun selectionColorTarget(): InkSelection.ColorTarget? =
        if (isEditing || repository.readOnly || !repository.canChangeNotebook) null else selection.colorTarget()
    fun recolorSelection(target: InkSelection.ColorTarget, color: Long): Boolean {
        if (isEditing || repository.readOnly || !repository.canChangeNotebook) return false
        val applied = selection.recolor(target, color)
        if (applied) InkColors(toolPreferences).remember(color)
        publishChange()
        return applied
    }
    /** Width of the first selected stroke, shown as the starting value of the width dialog. */
    fun selectionWidth(target: InkSelection.ColorTarget): Float? =
        target.notebook.pages.firstOrNull { it.id == target.pageId }?.strokes?.firstOrNull { it.id in target.ids }?.width
    fun rewidthSelection(target: InkSelection.ColorTarget, width: Float): Boolean {
        require(width.isFinite() && width in .5f..50f) { "Width must be 0.5–50 page units" }
        if (isEditing || repository.readOnly || !repository.canChangeNotebook) return false
        val applied = selection.rewidth(target, width) { restroke(it, width) }
        publishChange()
        return applied
    }
    /** B27 (P192): pen kind, colour and width of the selection together, as one Undo step. */
    fun restyleSelection(target: InkSelection.ColorTarget, change: InkSelection.Restyle): Boolean {
        change.width?.let { require(it.isFinite() && it in .5f..50f) { "Width must be 0.5–50 page units" } }
        change.color?.let { require(it in 0xff000000L..0xffffffffL) { "Choose an opaque RGB color" } }
        change.fillColor?.let { require(it in 0xff000000L..0xffffffffL) { "Choose an opaque fill colour" } }
        if (isEditing || repository.readOnly || !repository.canChangeNotebook) return false
        val applied = selection.restyle(target) { restyled(it, change) }
        if (applied) change.color?.let { InkColors(toolPreferences).remember(it) }
        publishChange()
        return applied
    }
    /** P413 (auto-refine R1): what Refine… last put on the page, so choosing another strength first takes it back. */
    private var refinedShown: Pair<InkSelection.ColorTarget, Notebook>? = null
    /**
     * P413 (auto-refine R1, docs/AUTO-REFINE-DESIGN.md): tidies the selected handwriting at [strength], in place of any
     * strength already tried from the same Refine… pop-out, so the page shows one choice and Undo takes it back in one
     * step. A null [strength] puts the writing back as written. Strokes whose points were smoothed are redrawn by their own
     * pen; the rest move with their outlines. Returns what was tidied (no strokes: nothing needed changing).
     */
    fun refineSelection(target: InkSelection.ColorTarget, strength: HandwritingRefine.Strength?): HandwritingRefine.Result {
        check(!isEditing && !repository.readOnly && repository.canChangeNotebook) { "Refine is not available right now." }
        val shown = refinedShown?.takeIf { (from, it) -> from == target && repository.session?.notebook === it }?.second
        val page = target.notebook.pages.firstOrNull { it.id == target.pageId }
        checkNotNull(page) { "The selection changed. Select it again." }
        val result = if (strength == null) HandwritingRefine.Result(emptyList(), 0, 0)
            else HandwritingRefine.refine(page.strokes.filter { it.id in target.ids }, page.paper, strength)
        val replacements = result.strokes.map { if (it.reshaped) restroke(it.stroke, it.stroke.width) else it.stroke }
        if (shown == null && replacements.isEmpty()) return result
        check(selection.refine(target, replacements, shown)) { "The selection changed or is busy. Select it again." }
        refinedShown = repository.session?.notebook?.takeIf { replacements.isNotEmpty() }?.let { target to it }
        publishChange()
        return result
    }
    /** P415: finished lines rewritten in the chosen hand while writing (HandStyle). */
    internal val handStyle = HandStyleWriter(this)
    /**
     * P415: puts rewritten words on the page in place of the writing, as one Undo. Waits only while the pen is down or
     * the notebook is busy (P416: a save in progress no longer holds it up); words whose writing changed or was erased
     * meanwhile are left as they are. The writing then cross-fades into the hand ([HandFade]).
     */
    internal fun applyHandStyle(notebookId: String, pageId: String, groups: List<Pair<List<InkStroke>, List<InkStroke>>>, waits: Int) {
        if (repository.session?.notebook?.id != notebookId || repository.readOnly) return
        if (isEditing || repository.busy || repository.fileBusy) {
            if (waits < 600) postDelayed({ applyHandStyle(notebookId, pageId, groups, waits + 1) }, 50)
            return
        }
        val page = repository.session?.notebook?.pages?.firstOrNull { it.id == pageId } ?: return
        val onPage = page.strokes.associateBy { it.id }
        val still = groups.filter { (old, _) -> old.all { onPage[it.id] == it } }.takeIf { it.isNotEmpty() } ?: return
        val drawn = runCatching { still.map { (old, new) -> old to new.map { restroke(it, it.width) } } }.getOrNull() ?: return
        val shown = if (pageId == repository.selectedPageId) visiblePaths(page.strokes) else emptyMap()
        val before = repository.session?.notebook
        repository.edit { session -> runCatching { session.rewriteHandwriting(pageId, drawn) }.getOrDefault(false) }
        if (repository.session?.notebook !== before && shown.isNotEmpty()) {
            handFade = HandFade(pageId, drawn.flatMap { (old, _) -> old.mapNotNull { s -> shown[s.id]?.let { s to it } } },
                drawn.flatMap { (_, new) -> new.map { it.id } }.toSet(), SystemClock.uptimeMillis())
        }
        publishChange()
    }
    /** P416: rewritten words fade in over the writing they replace, which fades out, instead of snapping. */
    private class HandFade(val pageId: String, val old: List<Pair<InkStroke, Path>>, val new: Set<String>, val start: Long) {
        fun level() = ((SystemClock.uptimeMillis() - start) / 240f).coerceIn(0f, 1f)
    }
    private var handFade: HandFade? = null
    /** P413: whether Refine… can put a choice on the page now; a save still being written makes it wait. */
    val canRefine get() = !isEditing && !repository.readOnly && repository.canChangeNotebook
    val clipboardIsPhoto get() = app.photoClipboard.content != null
    private val copiedHere get() = app.mixedClipboard.content != null || app.inkClipboard.hasInk || app.photoClipboard.content != null || app.textClipboard.content != null
    /**
     * P103 (F099): text another app copied after anything copied here pastes as a new text box. B35 (P198): a picture pastes as
     * a new photo; text wins when both are offered. P587: one read of the system clipboard answers both.
     */
    private fun outsideClip() = SystemClipboard.outside(app, if (copiedHere) app.copiedAt else -1L)
    val clipboardHasOutsideText get() = outsideClip() == SystemClipboard.Outside.TEXT
    val clipboardHasOutsideImage get() = outsideClip() == SystemClipboard.Outside.IMAGE
    private fun pasteLabel(outside: SystemClipboard.Outside) = when {
        outside == SystemClipboard.Outside.NONE && app.mixedClipboard.content != null -> "Paste selection"
        outside == SystemClipboard.Outside.TEXT || app.textClipboard.content != null -> "Paste text"
        outside == SystemClipboard.Outside.IMAGE || clipboardIsPhoto -> "Paste photo"
        else -> "Paste handwriting"
    }
    private val pasteAllowed get() = repository.currentPage != null && !repository.readOnly && !isEditing && !repository.busy && !repository.fileBusy
    val clipboardLabel get() = pasteLabel(outsideClip())
    val canPasteInk get() = (copiedHere || outsideClip() != SystemClipboard.Outside.NONE) && pasteAllowed
    /** P587: the Paste label and whether Paste can act now, from one clipboard read. */
    fun pasteState(): Pair<String, Boolean> = outsideClip().let { pasteLabel(it) to ((copiedHere || it != SystemClipboard.Outside.NONE) && pasteAllowed) }
    fun copySelectedInk(cut: Boolean = false): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return false
        val photo = selectedPhotoId != null
        val text = selectedTextId != null
        // P086: the ink as it is now, before a cut removes it, for the Android clipboard picture.
        val ink = if (text || photo) emptyList() else repository.currentPage?.strokes.orEmpty().filter { it.id in selection.ids }
        val mixed = !text && !photo && selection.hasObjects
        val copied = if (mixed) selection.copyMixed(app.mixedClipboard, selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), cut) else if (text) textSelection.copy(app.textClipboard, cut) else if (photo) photoSelection.copy(app.photoClipboard, cut)
            else selection.copy(app.inkClipboard, visiblePaths(repository.currentPage?.strokes.orEmpty()), cut)
        if (copied) {
            if (!mixed) app.mixedClipboard.clear()
            if (mixed) app.inkClipboard.clear()
            if (!text) app.textClipboard.clear()
            if (!photo) app.photoClipboard.clear()
            if (text || photo) app.inkClipboard.clear()
            app.copiedAt = System.currentTimeMillis()
            app.clippingSource = if (cut) null else repository.session?.notebook?.id?.let { id -> repository.selectedPageId?.let { NotesApplication.ClippingSource(NotebookLink(id, it)) } }
            SystemClipboard.publish(app, ink)
            if (text) app.textClipboard.content?.let { SystemClipboard.publishText(app, it.box.text) }
        }
        publishChange(); return copied
    }
    /** Pastes the clipboard centred on [at] (page units), or on the page centre. B35 (P198): another app's picture imports in the background. */
    fun pasteInk(at: Point? = null): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return false
        pasteSpot = null
        val clip = outsideClip()
        if (clip == SystemClipboard.Outside.IMAGE) return pasteOutsideImage(at)
        val outside = if (clip == SystemClipboard.Outside.TEXT) SystemClipboard.outsideText(app) else null
        val pasted = if (outside != null) textSelection.pasteText(outside, at) else if (app.mixedClipboard.content != null) selection.pasteMixed(app.mixedClipboard, at) else if (app.textClipboard.content != null) textSelection.paste(app.textClipboard, at)
            else if (app.photoClipboard.content != null) photoSelection.paste(app.photoClipboard, at) else selection.paste(app.inkClipboard, at)
        if (pasted) {
            if (app.mixedClipboard.content != null && outside == null) { photoSelection.clear(); textSelection.clear() }
            else if (outside != null || app.textClipboard.content != null) { selection.clear(); photoSelection.clear() }
            else if (app.photoClipboard.content != null) { selection.clear(); textSelection.clear() }
            else { photoSelection.clear(); textSelection.clear() }
        }
        if (pasted) { lassoEnabled = true; eraser = false }
        pastedClipping = if (pasted) app.clippingSource?.takeIf { it.text == outside && (it.link.notebookId != repository.session?.notebook?.id || it.link.pageId != repository.selectedPageId) }?.let {
            val ids = selectedObjectIds; PastedClipping(it.link, ObjectIds(ids.strokes, emptySet(), ids.texts))
        } else null
        publishChange(); return pasted
    }
    private class PastedClipping(val link: NotebookLink, val ids: ObjectIds)
    private var pastedClipping: PastedClipping? = null
    /** F124: the source of the pasted clipping still selected and not yet linked. Photos cannot hold links, so a photo-only clipping has none. */
    internal val pastedSource: NotebookLink? get() {
        val pasted = pastedClipping ?: return null
        val now = selectedObjectIds; val page = repository.currentPage ?: return null
        if (pasted.ids.isEmpty || now.strokes != pasted.ids.strokes || now.texts != pasted.ids.texts || NotebookLinks.has(page, pasted.ids)) return null
        return pasted.link
    }
    /** F124: one Undo step, the same link a person could set by hand with Link. */
    internal fun linkPastedToSource(): Boolean {
        val link = pastedSource ?: return false
        val pasted = pastedClipping ?: return false; val pageId = repository.selectedPageId ?: return false
        if (isEditing || repository.readOnly || !repository.canChangeNotebook) return false
        var linked = false
        runCatching { repository.edit { linked = it.linkObjects(pageId, pasted.ids, link); linked } }.onFailure { failure("The selection is no longer editable") }
        pastedClipping = null
        publishChange(); return linked
    }
    /** Local clipboard keeps the user's clipboard intact; pasteObjects commits the entire sticker once. */
    internal fun insertStickerObjects(target:ImageInsertTarget,record:StickerRecord,assets:List<ImageAsset>):Boolean {
        if(isEditing || !repository.canInsertImage(target))return false
        if(record.photoOnly) {
            val asset=assets.first { it.id==record.image!!.assetId }
            val id=repository.insertSticker(target,record,asset) ?: return false
            selectPhoto(id);return true
        }
        val local=MixedClipboard().apply { capture(record.clipboardContent(assets)) }
        val pasted=selection.pasteMixed(local)
        if(pasted){photoSelection.clear();textSelection.clear();lassoEnabled=true;eraser=false}
        publishChange();return pasted
    }
    /** True when the import started. The page, notebook and read-only state are checked again before the photo goes in. */
    private fun pasteOutsideImage(at: Point?): Boolean {
        val uri = SystemClipboard.outsideImage(app) ?: return false.also { failure("Photo paste failed: the picture is no longer on the clipboard") }
        val target = repository.captureImageInsertTarget() ?: return false
        repository.setFileBusy(true); publishChange()
        app.io.execute {
            val parsed = runCatching { app.contentResolver.openInputStream(uri)?.use { ImageSupport.importPhoto(it) } ?: error("Cannot open the picture") }
            app.main.post {
                repository.setFileBusy(false)
                parsed.mapCatching { repository.insertPhoto(target, it, at) ?: error("The notebook or page changed") }
                    .onSuccess { id -> if (!isEditing) { selection.clear(); textSelection.clear(); selectPhoto(id); lassoEnabled = true; eraser = false } }
                    .onFailure { failure("Photo paste failed: ${it.message}") }
                publishChange()
            }
        }
        return true
    }
    /** B35 (P198): the page point under a view point, for pasting where the finger or pen is. */
    fun pagePoint(viewX: Float, viewY: Float): Point {
        val p = floatArrayOf(viewX, viewY); inverse.mapPoints(p); return Point(p[0], p[1])
    }
    private var pdfTextSelection: PdfTextSelection? = null
    private var pdfTextBlockedContact=false
    internal var onPdfTextSelectionChanged: (() -> Unit)? = null
    /** P629: a finger or pen came down on the page (EditorFrame ends the page's glide at once). */
    internal var onTouchDown: (() -> Unit)? = null
    /** P630: a pen or highlighter stroke was just added to the page (EditorFrame may fold the toolbar into the slim bar). */
    internal var onInkWritten: (() -> Unit)? = null
    internal val pdfTextSelectionActive get() = pdfTextSelection != null
    internal val selectedPdfText get() = pdfTextSelection?.takeIf { it.matches(repository.currentPage) }?.text.orEmpty()
    internal val pdfTextBoundsInView: RectF? get() = pdfTextSelection?.let { selected ->
        val page=repository.currentPage ?: return@let null
        val boxes=selected.boxes
        (if(boxes.isEmpty()) RectF(0f,0f,page.width,page.height)
         else RectF(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }).also { viewport.mapRect(it) }
    }
    internal fun beginPdfTextSelection(content: PdfTextContent.Result): Boolean {
        val page=repository.currentPage ?: return false
        if(page.pdfBackground==null || isEditing || !inputEnabled || content.glyphs.isEmpty()) return false
        clearSelection()
        pdfTextSelection=PdfTextSelection(page,content)
        pdfTextSelectionChanged()
        return true
    }
    internal fun clearPdfTextSelection() {
        if(pdfTextSelection==null) return
        pdfTextSelection=null;pdfTextBlockedContact=false;pdfTextSelectionChanged()
    }
    private fun pdfTextSelectionChanged() { dry.invalidate();onPdfTextSelectionChanged?.invoke() }
    private val pdfTextPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Glass.accent(context.night);alpha=70 }
    /** Explicit mode consumes one-finger selection. Pen contact and multi-touch restore the usual tools/navigation. */
    private fun selectPdfText(event: MotionEvent): Boolean {
        val selected=pdfTextSelection ?: return false
        if(!selected.matches(repository.currentPage)) { clearPdfTextSelection();return false }
        val fingers=(0 until event.pointerCount).filter { event.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER }
        if(fingers.size!=event.pointerCount || fingers.size>1) {
            clearPdfTextSelection()
            if(fingers.size>1 && event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) {
                val first=fingers.first { it!=event.actionIndex }
                applyIntent(touch.onTouch(TouchSample(event.getPointerId(first),TouchTool.FINGER,TouchPhase.DOWN,
                    event.getX(first),event.getY(first),event.eventTime,event.getTouchMajor(first))))
            }
            return false
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) pdfTextBlockedContact=false
        if(!inputEnabled || repository.busy || repository.fileBusy || (fingerSettings.handLock && isHandLocked()) ||
            event.getTouchMajor(0)>=touch.palmContactSize || event.flags and MotionEvent.FLAG_CANCELED!=0) {
            pdfTextBlockedContact=true;selected.finish(cancel=true);pdfTextSelectionChanged()
        }
        if(pdfTextBlockedContact) {
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) pdfTextBlockedContact=false
            return true
        }
        val point=pagePoint(event.x,event.y)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> selected.begin(point.x,point.y,14*handleUnit)
            MotionEvent.ACTION_MOVE -> selected.move(point.x,point.y)
            MotionEvent.ACTION_UP -> selected.finish(event.flags and MotionEvent.FLAG_CANCELED!=0)
            MotionEvent.ACTION_CANCEL -> selected.finish(cancel=true)
        }
        pdfTextSelectionChanged()
        return true
    }
    /** P130 (F109): the selected handwriting as a stamp holds it; neither the clipboard nor the page changes. */
    fun selectedInkForStamp(): InkClipboard.Content? {
        if (isEditing || repository.busy || selectedPhotoId != null || selectedTextId != null || selection.ids.isEmpty()) return null
        val scratch = InkClipboard()
        return if (selection.copy(scratch, visiblePaths(repository.currentPage?.strokes.orEmpty()), false)) scratch.content else null
    }
    /** P130: a stamp goes in like pasted handwriting, centred on the page and selected so it can be moved straight away. */
    fun insertStamp(record: dev.ownednotes.core.StampRecord): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return false
        val pasted = selection.paste(InkClipboard().apply { capture(record.strokes, record.centre) })
        if (pasted) { photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false }
        publishChange(); return pasted
    }
    /** B11: a shape from the Shapes button, drawn with the current pen, goes in centred on the page and selected, like a stamp. */
    fun insertShape(choice: dev.ownednotes.core.ShapeLibrary.Choice): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        val page = repository.currentPage ?: return false
        if (repository.readOnly) return false
        val width = minOf(page.width * .3f, page.height * .3f / choice.aspect.coerceAtLeast(.01f))
        val centre = Point(page.width / 2, page.height / 2)
        val samples = dev.ownednotes.core.ShapeLibrary.path(choice, centre, width).mapIndexed { i, p -> Sample(p.x, p.y, i * 4L, .6f) }
        val batch = MutableStrokeInputBatch()
        samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
        val stroke = InkStroke(color = penStyle.inkColor, width = penStyle.width, brush = penStyle.kind.brushId, samples = samples, outlines = outlines(Stroke(makeBrush(penStyle), batch)), shapeKind = choice.kind)
        if (stroke.outlines.isEmpty()) return false
        val pasted = selection.paste(InkClipboard().apply { capture(listOf(stroke), centre) })
        if (pasted) { photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false }
        publishChange(); return pasted
    }
    /** Selects these strokes on the current page, as a lasso around just them would. */
    internal fun selectStrokes(ids: Set<String>): Boolean {
        val page = repository.currentPage ?: return false
        val present = page.strokes.map { it.id }.filter { it in ids }.toSet()
        if (isEditing || present.isEmpty()) return false
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(present)
        publishChange(); return true
    }
    /** Selects these objects on the current page, ink, photos and text alike, as a lasso around just them would. */
    internal fun selectObjects(ids: dev.ownednotes.core.ObjectIds): Boolean {
        val page = repository.currentPage ?: return false
        val present = page.objectIds(ids.selectionKeys())
        if (isEditing || present.isEmpty) return false
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(present.selectionKeys())
        publishChange(); return true
    }
    /** F059: the two closed shapes selected, in drawing order, when a connector can join them; P550: a photo or text box counts too. */
    internal fun connectableShapes(): Pair<dev.ownednotes.core.Connectors.End, dev.ownednotes.core.Connectors.End>? =
        selectedEnds(2..2)?.let { it[0] to it[1] }
    /** P557: three to twelve selected ends, in the order a chain joins them (see Connectors.chain). */
    internal fun chainableShapes(): List<dev.ownednotes.core.Connectors.End>? = selectedEnds(3..12)?.let(dev.ownednotes.core.Connectors::chain)
    private fun selectedEnds(count: IntRange): List<dev.ownednotes.core.Connectors.End>? {
        if (isEditing) return null
        val page = repository.currentPage ?: return null
        val ids = selectedObjectIds
        if (ids.strokes.size + ids.images.size + ids.texts.size !in count) return null
        val shapes = page.strokes.filter { it.id in ids.strokes }.map { dev.ownednotes.core.Connectors.end(it) ?: return null } +
            page.images.filter { it.id in ids.images }.map(dev.ownednotes.core.Connectors::end) +
            page.textBoxes.filter { it.id in ids.texts }.map(dev.ownednotes.core.Connectors::end)
        return shapes.takeIf { it.size in count }
    }
    /** P557: joins each neighbouring pair of the chain with the same kind of connector, in the current pen; one Undo step. */
    fun connectChain(kind: ShapeRecognizer.Kind, reverse: Boolean, elbow: Boolean = false): Boolean {
        check(!isEditing)
        val chain = chainableShapes()?.let { if (reverse) it.reversed() else it } ?: return false
        val page = repository.currentPage ?: return false
        if (repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy) return false
        val pen = InkStroke(color = penStyle.inkColor, width = penStyle.width, brush = penStyle.kind.brushId, samples = emptyList(), outlines = emptyList(),
            shapeKind = if (elbow) ShapeRecognizer.Kind.POLYLINE else kind)
        val strokes = chain.zipWithNext { from, to ->
            val path = if (elbow) dev.ownednotes.core.Connectors.elbow(from, to, kind == ShapeRecognizer.Kind.ARROW, penStyle.width)?.let { ShapeRecognizer.densify(it, 2f) }
                else dev.ownednotes.core.Connectors.route(from, to)?.let { dev.ownednotes.core.Connectors.path(kind, it.first, it.second, penStyle.width) }
            path?.let { ConnectorInk.redraw(pen, it) } ?: run { failure("Some of them overlap. Move them apart to connect them"); return false }
        }
        var added = emptySet<String>()
        runCatching { repository.edit { added = it.pasteStrokes(strokes, page.id); added.isNotEmpty() } }.onFailure { failure("The shapes could not be connected") }
        if (added.isEmpty()) return false
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(added)
        publishChange(); return true
    }
    internal class SelectedConnector(val pageId: String, val stroke: InkStroke, val found: dev.ownednotes.core.Connectors.Found,
        val from: dev.ownednotes.core.Connectors.End, val to: dev.ownednotes.core.Connectors.End, val label: dev.ownednotes.core.TextBox?)
    /** P555: the selected connector, alone or with its label, while both its ends are on the page. */
    internal fun selectedConnector(): SelectedConnector? {
        if (isEditing) return null
        val page = repository.currentPage ?: return null
        val ids = selectedObjectIds
        if (ids.strokes.size != 1 || ids.images.isNotEmpty() || ids.texts.size > 1) return null
        val stroke = page.strokes.firstOrNull { it.id in ids.strokes } ?: return null
        val label = dev.ownednotes.core.Connectors.label(page, stroke.id)
        if (ids.texts.isNotEmpty() && ids.texts.single() != label?.id) return null
        if (stroke.group != null && label == null) return null
        val found = dev.ownednotes.core.Connectors.connector(page, stroke.id) ?: return null
        val ends = dev.ownednotes.core.Connectors.ends(page).associateBy { it.id }
        return SelectedConnector(page.id, stroke, found, ends[found.link.from] ?: return null, ends[found.link.to] ?: return null, label)
    }
    /** P555: redraws [connector] as [kind], straight or elbow, keeping its id and pen, and sets or clears its label; one Undo step. */
    internal fun changeConnector(connector: SelectedConnector, kind: ShapeRecognizer.Kind, reverse: Boolean, elbow: Boolean, label: String): Boolean {
        check(!isEditing)
        val page = repository.currentPage ?: return false
        if (repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy || page.id != connector.pageId ||
            page.strokes.firstOrNull { it.id == connector.stroke.id } != connector.stroke || dev.ownednotes.core.Connectors.label(page, connector.stroke.id) != connector.label) {
            failure("The connector changed; it was not updated"); return false
        }
        val old = connector.stroke
        val (from, to) = if (reverse) connector.to to connector.from else connector.from to connector.to
        val path = if (elbow) dev.ownednotes.core.Connectors.elbow(from, to, kind == ShapeRecognizer.Kind.ARROW, old.width)?.let { ShapeRecognizer.densify(it, 2f) }
            else dev.ownednotes.core.Connectors.route(from, to)?.let { dev.ownednotes.core.Connectors.path(kind, it.first, it.second, old.width) }
        if (path == null) { failure("Move them apart to connect them"); return false }
        val redrawn = ConnectorInk.redraw(old.copy(shapeKind = if (elbow) ShapeRecognizer.Kind.POLYLINE else kind), path)?.copy(id = old.id) ?: return false
        val was = connector.label
        val style = was?.style ?: ConnectorInk.labelStyle(old.color)
        val placement = ConnectorInk.labelPlacement(label, old.color, from, to, elbow, old.width, page.width / 2, style)
        val group = if (placement == null) null else old.group ?: java.util.UUID.randomUUID().toString()
        val box = if (placement == null) null else was?.copy(text = label, placement = placement)
            ?: dev.ownednotes.core.TextBox(label, placement, style, group = group, layer = old.layer)
        var done = false
        runCatching { repository.edit { done = it.replaceConnector(page.id, redrawn.copy(group = group), box); done } }.onFailure { failure("The connector could not be changed") }
        if (!done) return false
        if (group != null) selectGroup(group) else { photoSelection.clear(); textSelection.clear(); selection.select(setOf(old.id)); publishChange() }
        return true
    }
    /** F059: joins the two selected shapes with a line or arrow in the current pen and selects it; one Undo step. */
    fun connectShapes(kind: ShapeRecognizer.Kind, reverse: Boolean, elbow: Boolean = false, label: String = ""): Boolean {
        check(!isEditing)
        val (first, second) = connectableShapes() ?: return false
        val page = repository.currentPage ?: return false
        if (repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy) return false
        val (from, to) = if (reverse) second to first else first to second
        // P552: an elbow turns two right angles; it is an ordinary polyline, found again from its exact path.
        val path = if (elbow) dev.ownednotes.core.Connectors.elbow(from, to, kind == ShapeRecognizer.Kind.ARROW, penStyle.width)?.let { ShapeRecognizer.densify(it, 2f) }
            else dev.ownednotes.core.Connectors.route(from, to)?.let { dev.ownednotes.core.Connectors.path(kind, it.first, it.second, penStyle.width) }
        if (path == null) { failure("Move them apart to connect them"); return false }
        val pen = InkStroke(color = penStyle.inkColor, width = penStyle.width, brush = penStyle.kind.brushId, samples = emptyList(), outlines = emptyList(),
            shapeKind = if (elbow) ShapeRecognizer.Kind.POLYLINE else kind)
        val stroke = ConnectorInk.redraw(pen, path) ?: return false
        // P554: a label is a text box grouped with the connector alone, so it moves, follows and goes with it.
        val placement = ConnectorInk.labelPlacement(label, penStyle.inkColor, from, to, elbow, penStyle.width, page.width / 2)
        if (placement != null) {
            val group = java.util.UUID.randomUUID().toString()
            val layer = repository.session?.activeLayer(page.id)
            val line = stroke.copy(group = group, layer = layer)
            val box = dev.ownednotes.core.TextBox(label, placement, ConnectorInk.labelStyle(penStyle.inkColor), group = group, layer = layer)
            var done = false
            runCatching { repository.edit { done = it.replaceTable(page.id, group, listOf(line), listOf(box)); done } }.onFailure { failure("The shapes could not be connected") }
            if (!done) return false
            selectGroup(group); return true
        }
        var added = emptySet<String>()
        runCatching { repository.edit { added = it.pasteStrokes(listOf(stroke), page.id); added.isNotEmpty() } }.onFailure { failure("The shapes could not be connected") }
        if (added.isEmpty()) return false
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(added)
        publishChange(); return true
    }
    internal class SelectedTable(val pageId: String, val group: String, val grid: dev.ownednotes.core.TableGrid, val cells: Map<Pair<Int, Int>, String>, val pageWidth: Float, val pageHeight: Float)
    /** F096: the selected group, if it is a table that can still be edited as one. */
    internal fun selectedTable(): SelectedTable? {
        if (selectedTextId != null || selectedPhotoId != null) return null
        val page = repository.currentPage ?: return null
        val group = selection.oneGroup ?: return null
        val grid = dev.ownednotes.core.TableGrid.of(page, group) ?: return null
        val cells = page.textBoxes.filter { it.group == group }.mapNotNull { box ->
            grid.cellAt(Point(box.placement.x + box.placement.width / 2, box.placement.y + box.placement.height / 2))?.let { it to box.text }
        }.toMap()
        return SelectedTable(page.id, group, grid, cells, page.width, page.height)
    }
    /** Selects a whole group's objects, as a tap on one of its members does. */
    internal fun selectGroup(group: String): Boolean {
        val page = repository.currentPage ?: return false
        val ids = page.strokes.filter { it.group == group }.map { it.id } + page.textBoxes.filter { it.group == group }.map { it.id }
        if (ids.isEmpty()) return false
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(ids.toSet())
        publishChange(); return true
    }
    private fun tableLine(line: dev.ownednotes.core.TableGrid.Line, color: Long, width: Float, group: String, layer: String?): InkStroke {
        val samples = ShapeRecognizer.densify(listOf(line.from, line.to), 8f).mapIndexed { i, p -> Sample(p.x, p.y, i * 4L, .6f) }
        val strokeWidth = (if (line.heavy) width * 2.5f else width).coerceIn(.5f, 50f)
        val style = PenStyle(color = color or 0xff000000L, width = strokeWidth, kind = PenKind.BALLPOINT)
        val batch = MutableStrokeInputBatch()
        samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
        return InkStroke(color = style.inkColor, width = strokeWidth, brush = style.kind.brushId, samples = samples, outlines = outlines(Stroke(makeBrush(style), batch)),
            shapeKind = ShapeRecognizer.Kind.LINE, group = group, layer = layer)
    }
    /** F096: a new table in the current pen's colour, centred and selected, one Undo step. */
    fun insertTable(rows: Int, columns: Int, header: Boolean): Boolean {
        check(!isEditing)
        val page = repository.currentPage ?: return false
        if (repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy) return false
        val grid = dev.ownednotes.core.TableGrid.fresh(rows, columns, header, page.width, page.height)
        val group = java.util.UUID.randomUUID().toString()
        val layer = repository.session?.activeLayer(page.id)
        val strokes = grid.lines.map { tableLine(it, penStyle.color, 1.5f, group, layer) }
        if (strokes.any { it.outlines.isEmpty() }) return false
        repository.edit { it.replaceTable(page.id, group, strokes, emptyList()) }
        photoSelection.clear(); textSelection.clear(); lassoEnabled = true; eraser = false
        selection.select(strokes.map { it.id }.toSet())
        publishChange(); return true
    }
    /** F096: applies the Table sheet: new lines and cell text, keeping each kept cell's text style; P549: ink written in cells follows its cell. */
    internal fun applyTable(table: SelectedTable, rows: Int, columns: Int, header: Boolean, cells: Map<Pair<Int, Int>, String>): Boolean =
        applyTable(table, table.grid.resized(rows, columns, header, table.pageWidth, table.pageHeight), cells)
    /** P553: applies [grid] as the table's new lines, which may also have dragged column widths and row heights. */
    internal fun applyTable(table: SelectedTable, grid: dev.ownednotes.core.TableGrid, cells: Map<Pair<Int, Int>, String>): Boolean {
        check(!isEditing)
        val page = repository.currentPage ?: return false
        if (repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy || page.id != table.pageId) { failure("The notebook changed; the table was not updated"); return false }
        val current = dev.ownednotes.core.TableGrid.of(page, table.group)
        if (current != table.grid) { failure("The table changed; it was not updated"); return false }
        val oldStrokes = page.strokes.filter { it.group == table.group }
        val oldTexts = page.textBoxes.filter { it.group == table.group }
        if (oldStrokes.any { it.locked } || oldTexts.any { it.locked }) { failure("Unlock the table first"); return false }
        val thin = oldStrokes.minOf { it.width }
        val strokes = grid.lines.map { tableLine(it, oldStrokes.first().color, thin, table.group, oldStrokes.first().layer) }
        if (strokes.any { it.outlines.isEmpty() }) return false
        val byCell = oldTexts.associateBy { box -> current.cellAt(Point(box.placement.x + box.placement.width / 2, box.placement.y + box.placement.height / 2)) }
        val texts = cells.filter { it.value.isNotEmpty() }.map { (cell, text) ->
            val placement = grid.cellBox(cell.first, cell.second)
            byCell[cell]?.copy(text = text, placement = placement)
                ?: dev.ownednotes.core.TextBox(text, placement, dev.ownednotes.core.TextStyle(size = grid.textSize), group = table.group, layer = oldStrokes.first().layer)
        }
        val moves = dev.ownednotes.core.TableGrid.cellInkMoves(page, table.group, grid)
        repository.edit { it.replaceTable(page.id, table.group, strokes, texts, moves) }
        selection.select(strokes.map { it.id }.toSet())
        publishChange(); return true
    }
    /**
     * P096 (F123): marks recognised text [bands] on the current page as one undo step: a straight highlighter stroke in
     * the highlighter's colour over each band, or a thin underline in the pen's colour. Returns the strokes added.
     */
    fun markText(bands: List<RectF>, underline: Boolean): Int {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        val page = repository.currentPage ?: return 0
        if (repository.readOnly || bands.isEmpty()) return 0
        val strokes = bands.mapNotNull { band ->
            if (band.width() <= 0f || band.height() <= 0f) return@mapNotNull null
            runCatching {
                if (underline) {
                    val style = penStyle.copy(width = (band.height() * .08f).coerceIn(1f, 6f))
                    val y = band.bottom + style.width
                    val count = maxOf(16, (band.width() / 8f).toInt()).coerceAtMost(400)
                    val samples = List(count) { i -> Sample(band.left + band.width() * i / (count - 1), y, i * 4L, .6f) }
                    val batch = MutableStrokeInputBatch()
                    samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
                    InkStroke(color = style.inkColor, width = style.width, brush = style.kind.brushId, samples = samples, outlines = outlines(Stroke(makeBrush(style), batch)))
                } else {
                    TextHighlight.straighten(band, listOf(Sample(band.left, band.centerY(), 0L, .6f))) { width -> makeHighlighter(highlighterStyle, width) }?.let { line ->
                        InkStroke(color = highlighterStyle.inkColor, width = line.width, brush = InkStroke.HIGHLIGHTER, samples = line.samples, outlines = outlines(line.stroke))
                    }
                }
            }.getOrNull()?.takeIf { it.outlines.isNotEmpty() }
        }
        if (strokes.isEmpty()) return 0
        var added = 0
        repository.edit { added = it.pasteStrokes(strokes, page.id).size; added > 0 }
        dry.invalidate(); publishChange()
        return added
    }
    fun transformSelection(scale: Float = 1f, degrees: Float = 0f) {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return
        selection.transform(selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), scale, degrees)
        publishChange()
    }
    /** P077 (F060): turns the selected line of writing level about its centre, as one Undo step. True when it turned. */
    fun straightenSelection(): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return false
        val ids = selection.ids
        val turn = Straighten.turn(repository.currentPage?.strokes.orEmpty().filter { it.id in ids })
        if (turn !is Straighten.Turn) {
            showHint(if (turn == Straighten.Level) "Already level" else "Select a line of writing to straighten"); return false
        }
        selection.transform(visiblePaths(repository.currentPage?.strokes.orEmpty()), 1f, turn.degrees)
        showHint("Straightened by ${"%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(turn.degrees))}°")
        publishChange(); return true
    }
    /** P085 (F158): tapes peeled back in this editor. Never saved, so a reopened notebook starts with every tape covered. */
    private val revealedTape = mutableSetOf<String>()
    val revealedTapeIds: Set<String> get() = revealedTape
    val pageHasTape get() = repository.currentPage?.strokes?.any { it.isTape } == true
    val pageTapeCovered get() = repository.currentPage?.strokes?.any { it.isTape && it.id !in revealedTape } == true
    /** Lays one study tape over the selected ink as one Undo step, then selects the tape. True when it was added. */
    fun tapeSelection(): Boolean {
        check(!isEditing && !repository.busy && !repository.fileBusy)
        if (repository.readOnly) return false
        val page = repository.currentPage ?: return false
        val paths = visiblePaths(page.strokes)
        val bounds = RectF()
        page.strokes.filter { it.id in selection.ids }.forEach { stroke -> RectF().also { paths.getValue(stroke.id).computeBounds(it, true); bounds.union(it) } }
        if (bounds.isEmpty) return false
        val tape = StudyTape.cover(bounds)
        repository.edit { it.add(tape, page.id); true }
        if (repository.currentPage?.strokes?.any { it.id == tape.id } != true) return false
        selection.select(setOf(tape.id))
        showHint("Tape added. Tap it with the pen to peek")
        publishChange(); return true
    }
    /** Reveals (or covers) every tape on the current page. */
    fun revealAllTape(reveal: Boolean) {
        val ids = repository.currentPage?.strokes.orEmpty().filter { it.isTape }.map { it.id }
        if (reveal) revealedTape += ids else revealedTape -= ids.toSet()
        dry.invalidate(); publishChange()
    }
    /** P201 step 9: the current page's tapes, top to bottom then left to right (the Recall check's order). */
    val pageTapeIds: List<String> get() = repository.currentPage?.strokes.orEmpty().filter { it.isTape }
        .sortedWith(compareBy({ s -> s.samples.minOf { it.y } }, { s -> s.samples.minOf { it.x } })).map { it.id }
    /** P201 step 9: peels one tape back or lays it down again, as a pen tap on it does (live state only, never saved). */
    fun revealTape(id: String, reveal: Boolean) {
        if (reveal == (id in revealedTape)) return
        if (reveal) revealedTape += id else revealedTape -= id
        dry.invalidate(); publishChange()
    }
    /** A short pen tap on a tape peels it back or lays it down again, instead of leaving a dot. */
    private fun toggleTapeAt(pending: Pending): Boolean {
        if (pending.erase || pending.samples.isEmpty()) return false
        val page = repository.currentPage?.takeIf { it.id == pending.pageId && it.strokes.any { s -> s.isTape } } ?: return false
        val unit = resources.displayMetrics.density / pageScale
        val xs = pending.samples.map { it.x }; val ys = pending.samples.map { it.y }
        if (xs.max() - xs.min() > 6 * unit || ys.max() - ys.min() > 6 * unit || pending.samples.last().timeMs - pending.samples.first().timeMs > 400) return false
        val id = StudyTape.at(page.drawnStrokes, visiblePaths(page.strokes), xs.average().toFloat(), ys.average().toFloat(), 2 * unit) ?: return false
        val revealed = id !in revealedTape
        if (revealed) revealedTape += id else revealedTape -= id
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        showHint(if (revealed) "Tape peeled back" else "Tape covered")
        dry.invalidate(); publishChange()
        return true
    }
    private val tapeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f) }
    private var cachedStrokes: List<InkStroke>? = null
    private var paths = emptyMap<String, Path>()
    private var pathsByStroke = java.util.IdentityHashMap<InkStroke, Path>()
    /**
     * B23 (P137): strokes are immutable, so a stroke kept from the last list keeps its path; only new or changed strokes
     * are built. Rebuilding all of them after every pen lift held the pen back by up to 150 ms on a dense page.
     */
    private fun visiblePaths(strokes: List<InkStroke>): Map<String, Path> {
        if (cachedStrokes !== strokes) {
            val previous = pathsByStroke
            val next = java.util.IdentityHashMap<InkStroke, Path>(strokes.size)
            strokes.forEach { next[it] = previous[it] ?: InkGeometry.visible(it) }
            pathsByStroke = next
            paths = strokes.associate { it.id to next.getValue(it) }; cachedStrokes = strokes
        }
        return paths
    }
    private var selectionPage: Page? = null
    private var selectionInkPaths: Map<String,Path>? = null
    private var selectionImages: List<ImageObject>? = null
    private var selectionTexts: List<TextBox>? = null
    private var combinedSelectionPaths = emptyMap<String,Path>()
    private fun selectionPaths(ink: Map<String,Path>): Map<String,Path> {
        val page=repository.currentPage ?: return ink
        if(selectionPage === page && selectionInkPaths === ink && selectionImages === page.images && selectionTexts === page.textBoxes) return combinedSelectionPaths
        fun outline(p: ImagePlacement)=Path().apply {
            p.corners().forEachIndexed { i,q -> if(i==0) moveTo(q.x,q.y) else lineTo(q.x,q.y) };close()
        }
        selectionPage=page;selectionInkPaths=ink;selectionImages=page.images;selectionTexts=page.textBoxes
        combinedSelectionPaths = ink + page.editableImages.associate { imageKey(it.id) to outline(it.placement) } +
            page.editableTexts.associate { textKey(it.id) to outline(it.placement) }
        return combinedSelectionPaths
    }
    private data class Pickable(val all: Map<String, Path>, val filter: InkSelection.Filter, val layer: String?, val paths: Map<String, Path>)
    private var pickable: Pickable? = null
    /** P100: ink on the active layer only, so other layers can't be picked, erased or scribbled away. P101: never locked ink. */
    private fun onActiveLayer(pageId: String): (InkStroke) -> Boolean {
        val layer = repository.session?.activeLayer(pageId) ?: return { !it.locked }
        return { it.layer == layer && !it.locked }
    }
    /** F043: the strokes the lasso may pick up under [filter]; Text and Images pick no ink. P100: only the active layer's. */
    private fun pickablePaths(filter: InkSelection.Filter): Map<String, Path> {
        val page = repository.currentPage!!
        val strokes = page.strokes
        val all = visiblePaths(strokes)
        val layer = repository.session?.activeLayer(page.id)
        if (filter == InkSelection.Filter.ALL && layer == null && strokes.none { it.locked }) return selectionPaths(all)
        pickable?.takeIf { it.all === all && it.filter == filter && it.layer == layer }?.let { return if(filter==InkSelection.Filter.ALL) selectionPaths(it.paths) else it.paths }
        val keep = strokes.filter { (layer == null || it.layer == layer) && !it.locked && when (filter) {
            InkSelection.Filter.ALL -> true
            InkSelection.Filter.INK -> !it.isHighlighter
            InkSelection.Filter.HIGHLIGHTER -> it.isHighlighter
            InkSelection.Filter.SHAPES -> it.shapeKind != null
            else -> false
        } }.map { it.id }.toSet()
        val ink=all.filterKeys { it in keep }.also { pickable = Pickable(all, filter, layer, it) }
        return if(filter==InkSelection.Filter.ALL) selectionPaths(ink) else ink
    }
    private val app get() = context.applicationContext as NotesApplication
    private val photoBitmaps: PhotoBitmapCache = PhotoBitmapCache(app) { dry.invalidate(); changed() }
    private val pdfTiles: PdfTileLayer by lazy { PdfTileLayer(app) { dry.invalidate() } }
    internal val pdfTileStats: PdfTileLayer.Stats get() = pdfTiles.stats
    internal fun pdfTilePixelAt(point: Point) = repository.currentPage?.let { pdfTiles.pixelAt(it, point) }

    private val textLayouts: TextLayoutCache = TextLayoutCache(app) { dry.invalidate(); changed() }
    private fun backgroundKey(page: Page?): Any? = page?.pdfBackground ?: page?.imageBackground
    private var pdfKey: Any? = null
    private var failedPdfKey: Any? = null
    private var pdfError: String? = null
    private var pdfBitmap: Bitmap? = null
    private var pdfRendering = false
    // Keep the public names for existing input/curl consumers; readiness covers both background kinds.
    val pdfBackgroundReady get() = (backgroundKey(repository.currentPage) == null ||
        (pdfKey == backgroundKey(repository.currentPage) && pdfBitmap != null)) && photoBitmaps.isReady(repository.currentPage) && textLayouts.isReady(repository.currentPage)
    val pdfBackgroundStatus: String? get() {
        val label = if (repository.currentPage?.imageBackground != null) "Image" else "PDF"
        return when {
            pdfBackgroundReady -> null
            backgroundKey(repository.currentPage) == null || (pdfKey == backgroundKey(repository.currentPage) && pdfBitmap != null) ->
                if (!photoBitmaps.isReady(repository.currentPage)) photoBitmaps.status ?: "Rendering images…"
                else textLayouts.status ?: "Laying out text…"
            failedPdfKey == backgroundKey(repository.currentPage) -> "$label unavailable: $pdfError"
            else -> "Rendering $label…"
        }
    }
    internal var onPdfPasswordRequired: ((dev.ownednotes.core.PdfAsset) -> Unit)? = null
    internal fun retryPdfBackground() { failedPdfKey=null;pdfError=null;requestPdf();dry.invalidate() }
    private fun requestPdf() {
        photoBitmaps.request(repository.session?.notebook, repository.currentPage)
        val page = repository.currentPage
        textLayouts.request(page, page?.textBoxes?.firstOrNull { it.id == selectedTextId }?.let { textSelection.preview(it) })
        val background = backgroundKey(repository.currentPage)
        if (pdfKey != background) { pdfBitmap = null; pdfKey = background }
        if (background == null || pdfBitmap != null || pdfRendering || failedPdfKey == background || !isAttachedToWindow) return
        val note = repository.session?.notebook ?: return
        pdfRendering = true
        app.pageRendering.execute {
            val result = runCatching {
                when (background) {
                    is PdfBackground -> PdfSupport.render(app.cacheDir, note.assets.first { it.id == background.assetId }, background)
                    is ImageBackground -> ImageSupport.render(note.imageAssets.first { it.id == background.assetId }, background)
                    else -> error("Unknown background")
                }
            }
            app.main.post {
                pdfRendering = false
                if (isAttachedToWindow && backgroundKey(repository.currentPage) == background) {
                    result.onSuccess { pdfKey = background; pdfBitmap = it }
                        .onFailure { failedPdfKey = background; pdfError = it.message
                            if(it is PdfAccess.PasswordRequired) onPdfPasswordRequired?.invoke(it.asset) else failure("Background failed: ${it.message}") }
                    dry.invalidate()
                    changed()
                } else result.getOrNull()?.recycle()
                requestPdf()
            }
        }
    }
    private val replayTick=object:Runnable {
        override fun run(){
            dry.invalidate()
            if(isAttachedToWindow && app.audioPlayback.replayEnabled && app.audioPlayback.state==AudioPlayback.State.PLAYING)postDelayed(this,33)
        }
    }
    private fun syncReplayFrames(){
        removeCallbacks(replayTick)
        if(isAttachedToWindow && app.audioPlayback.replayEnabled && app.audioPlayback.state==AudioPlayback.State.PLAYING)post(replayTick)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncReplayFrames(); pdfTiles.attach(); photoBitmaps.attach(); textLayouts.attach(); requestPdf() }
    override fun onDetachedFromWindow() { pendingLassoCompletion = null; selection.cancelGesture(); removeCallbacks(replayTick); laser.clear(); pdfTiles.detach(); cancelSnapFrame(); active.values.forEach { it.adjustment = null }; cancelCurl(); photoBitmaps.detach(); textLayouts.detach(); pdfBitmap = null; super.onDetachedFromWindow() }
    private val dry = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG) // carries a highlighter's blend mode; never reused for paper
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pageBounds = RectF()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowBounds = RectF()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            pdfTiles.beginFrame()
            try {
            // In continuous mode the strip underneath draws the desk, the shadows and the other pages.
            if (strip == null) canvas.drawColor(deskColor)
            val page = repository.currentPage ?: return
            if (strip != null && !fitted) return
            // P585: in continuous mode the page that becomes current while scrolling only starts loading its PDF, picture,
            // photos and text then; drawn before they are ready it covered the strip's finished picture of it with bare
            // paper, a flicker as each page passed. Until it is ready the strip's picture shows instead (input waits too).
            if (strip != null && !pdfBackgroundReady) { stripWaitingDraws++; return }
            if (strip == null) {
                // Soft two-layer page shadow in screen pixels, so it keeps its size at every zoom. A spread shares one.
                shadowBounds.set(0f, 0f, page.width, page.height); spread?.let { shadowBounds.union(it.partner) }; viewport.mapRect(shadowBounds)
                val unit = resources.displayMetrics.density
                shadowPaint.color = 0x14000000; shadowBounds.offset(0f, unit); canvas.drawRoundRect(shadowBounds, unit * 2, unit * 2, shadowPaint)
                shadowPaint.color = 0x0c000000; shadowBounds.inset(-unit * 2, -unit * 2); shadowBounds.offset(0f, unit * 2); canvas.drawRoundRect(shadowBounds, unit * 6, unit * 6, shadowPaint)
            }
            canvas.save(); canvas.concat(viewport)
            spread?.let { drawPartner(canvas, it) }
            canvas.save()
            canvas.clipRect(0f, 0f, page.width, page.height)
            PaperRenderer.draw(canvas, page, paint)
            pageBounds.set(0f, 0f, page.width, page.height)
            if (pdfKey == backgroundKey(page)) pdfBitmap?.let { canvas.drawBitmap(it, null, pageBounds, bitmapPaint) }
            if (page.pdfBackground != null && pdfKey == backgroundKey(page) && pdfBitmap != null) {
                repository.session?.notebook?.let { note -> pdfTiles.draw(canvas, note, page, viewport,
                    RectF(0f, 0f, this@InkCanvas.width.toFloat(), this@InkCanvas.height.toFloat())) }
            }
            val replay=app.audioPlayback.replayFrame() // One player read per frame, never per stroke.
            val visible = visiblePaths(page.strokes)
            val fade = handFade?.takeIf { it.pageId == page.id }; val faded = fade?.level() ?: 1f
            page.paintSlices.forEach { slice ->
            slice.images.forEach { image -> photoBitmaps.bitmap(image.assetId)?.let {
                canvas.save();if(imageKey(image.id) in selection.ids) { val delta=selection.offset;canvas.translate(delta.x,delta.y);canvas.concat(selection.previewMatrix) }
                canvas.withObjectOpacity(page, image.layer) { PhotoRendering.draw(canvas, photoSelection.preview(image), it, bitmapPaint) };canvas.restore()
            } }
            slice.textBoxes.forEach { box ->
                val preview = textSelection.preview(box)
                (textLayouts.layout(preview) ?: textLayouts.layout(box))?.let {
                    canvas.save();if(textKey(box.id) in selection.ids) { val delta=selection.offset;canvas.translate(delta.x,delta.y);canvas.concat(selection.previewMatrix) }
                    canvas.withObjectOpacity(page, box.layer) { TextRendering.draw(canvas, preview, it) };canvas.restore()
                }
            }
            AttachmentRendering.drawPage(canvas, attachmentDrag?.takeIf { it.owner === repository.session?.notebook && it.pageId == page.id }?.let { d -> slice.copy(attachments = slice.attachments.map { if (it.id == d.card.id) it.copy(placement = d.preview) else it }) } ?: slice, repository.session?.notebook?.attachmentAssets.orEmpty())
            val selected = selection.ids
            val delta = selection.offset
            val erasing = active.values.filter { it.erase && it.pageId == page.id }
            // B03 (P131): pairs, not a map keyed by Pending (a data class that would hash every sample per stroke per frame).
            val previews = erasing.filter { it.mode == EraserMode.AREA && it.samples.isNotEmpty() }.map { it.erased to InkGeometry.previewSweep(it.sweep()) }
            // B31 (P193): strokes the paint brush touched show their new look until the pen lifts.
            val painting = active.values.filter { it.paint && it.pageId == page.id && it.painted.isNotEmpty() }.map { it.painted }
            val reshaping = selection.shapePreview
            // P617: with Mix colours off a later highlight covers earlier ones; a moving selection keeps its own shape.
            val moving = selected.isNotEmpty() && (delta.x != 0f || delta.y != 0f || !selection.previewMatrix.isIdentity)
            val flat = InkGeometry.flatHighlights(slice.drawnStrokes.filter { !(moving && it.id in selected) && painting.none { p -> it.id in p } }) { visible.getValue(it.id) }
            slice.drawnStrokes.forEach { stroke ->
                if (AudioPlayback.visibleInReplay(stroke,replay) && erasing.none { it.mode == EraserMode.STROKE && stroke.id in it.erased }) {
                    val repainted = painting.firstNotNullOfOrNull { it[stroke.id] }
                    val path = repainted?.second ?: visible.getValue(stroke.id)
                    canvas.save()
                    if (stroke.id in selected) { canvas.translate(delta.x, delta.y); canvas.concat(selection.previewMatrix) }
                    // The live area-erase preview clips the sweep out instead of a per-frame path difference; lift commits the exact geometry.
                    previews.forEach { (erased, sweep) -> if (stroke.id in erased) canvas.clipOutPath(sweep) }
                    if (stroke.isTape && stroke.id in revealedTape) {
                        // A peeled-back tape keeps a faint fill and a dashed edge, so it can be found and tapped again.
                        canvas.drawPath(path, InkGeometry.paint(inkPaint, stroke).apply { alpha = 36 })
                        tapeOutline.color = stroke.color.toInt(); tapeOutline.alpha = 210; tapeOutline.strokeWidth = 1.5f * handleUnit
                        canvas.drawPath(path, tapeOutline)
                    } else if (reshaping?.first == stroke.id) canvas.drawPath(reshaping.second, InkGeometry.paint(shapePointPaint,stroke,page.opacityOf(stroke)).apply { style=Paint.Style.STROKE;strokeWidth=stroke.width })
                    else InkGeometry.draw(canvas, repainted?.first ?: stroke, inkPaint, page.opacityOf(stroke) * (if (fade != null && stroke.id in fade.new) faded else 1f), flat[stroke] ?: path)
                    canvas.restore()
                }
            }
            }
            if (fade != null) {
                fade.old.forEach { (stroke, path) -> InkGeometry.draw(canvas, stroke, inkPaint, page.opacityOf(stroke) * (1 - faded), path) }
                if (faded < 1f) postInvalidateOnAnimation() else handFade = null
            }
            active.values.forEach { pending -> pending.snap?.takeIf { pending.pageId == page.id }?.let { drawSnap(canvas, it, pending.style) } }
            snapGuide.color = Glass.accent(context.night); snapGuide.alpha = 180; snapGuide.strokeWidth = handleUnit
            (selection.moveGuides + photoSelection.moveGuides + textSelection.moveGuides).forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, snapGuide) }
            pdfTextSelection?.takeIf { it.matches(page) }?.boxes?.forEach { canvas.drawRect(it,pdfTextPaint) }
            spread?.let { gutter.draw(canvas, pageBounds, spineOnRight = it.currentOnLeft) }
            canvas.restore() // Handles may extend beyond the paper, but never beyond the editor view.
            if (lassoEnabled) {
                selection.draw(canvas, selectionPaths(visible), handleUnit, viewportTop, effectiveLasso)
                photoSelection.draw(canvas, handleUnit, photoViewport, effectiveLasso)
                textSelection.draw(canvas, handleUnit, photoViewport, effectiveLasso)
            }
            canvas.restore()
            } finally { pdfTiles.endFrame() }
        }
        /** The spread's other page: crisp live ink on plain paper; PDF pages use their preview once it is ready. */
        private fun drawPartner(canvas: Canvas, s: Spread) {
            val notebook = repository.session?.notebook ?: return
            val partner = notebook.pages.firstOrNull { it.id == s.partnerId } ?: return
            canvas.save(); canvas.clipRect(s.partner)
            val preview = if (partner.pdfBackground != null || partner.imageBackground != null || partner.images.isNotEmpty() || partner.textBoxes.isNotEmpty() || partner.attachments.isNotEmpty()) partnerPreview(notebook, partner) else null
            if (preview != null) {
                canvas.drawBitmap(preview, null, s.partner, bitmapPaint)
                val k = s.partner.width() / partner.width
                val partnerViewport = Matrix(viewport).apply { preTranslate(s.partner.left, s.partner.top); preScale(k, k) }
                canvas.save(); canvas.translate(s.partner.left, s.partner.top); canvas.scale(k, k)
                pdfTiles.draw(canvas, notebook, partner, partnerViewport,
                    RectF(0f, 0f, this@InkCanvas.width.toFloat(), this@InkCanvas.height.toFloat()), flattened = true)
                canvas.restore()
            } else {
                val k = s.partner.width() / partner.width
                canvas.translate(s.partner.left, s.partner.top); canvas.scale(k, k)
                PaperRenderer.draw(canvas, partner, paint)
                val paths = partnerPaths(partner.strokes)
                val flat = InkGeometry.flatHighlights(partner.drawnStrokes) { paths.getValue(it.id) }
                partner.drawnStrokes.forEach { InkGeometry.draw(canvas, it, inkPaint, partner.opacityOf(it), flat[it] ?: paths.getValue(it.id)) }
            }
            canvas.restore()
            gutter.draw(canvas, s.partner, spineOnRight = !s.currentOnLeft)
        }
    }
    private val gutter = BookSpread.Gutter()
    private var partnerStrokes: List<InkStroke>? = null
    private var partnerPathMap = emptyMap<String, Path>()
    private fun partnerPaths(strokes: List<InkStroke>): Map<String, Path> {
        if (partnerStrokes !== strokes) { partnerPathMap = strokes.associate { it.id to InkGeometry.visible(it) }; partnerStrokes = strokes }
        return partnerPathMap
    }
    private var partnerRequest: PageThumbnails.Request? = null
    private var partnerRequestKey: PageThumbnails.Key? = null
    private fun partnerPreview(notebook: Notebook, page: Page): Bitmap? {
        val service = thumbnailService ?: return null
        service.cached(notebook, page.id, BookFlipView.PREVIEW_WIDTH)?.let { return it }
        val key = service.key(notebook, page, BookFlipView.PREVIEW_WIDTH)
        if (partnerRequestKey != key) {
            partnerRequest?.cancel(); partnerRequestKey = key
            partnerRequest = service.request(notebook, page.id, BookFlipView.PREVIEW_WIDTH) { partnerRequest = null; dry.invalidate() }
        }
        return null
    }
    // B01: the library marks its renderer hook restricted; PencilWetRenderer only draws through it.
    @SuppressLint("RestrictedApi")
    private val wet = InProgressStrokesView(context).apply { rendererFactory = { PencilWetRenderer() } }
    val isEditing get() = attachmentDrag != null || active.isNotEmpty() || finishing.isNotEmpty() || selection.isEditing || photoSelection.isEditing || textSelection.isEditing
    val pendingCount get() = active.size + finishing.size + (if (selection.isEditing) 1 else 0) + (if (photoSelection.isEditing) 1 else 0) + (if (textSelection.isEditing) 1 else 0)
    init {
        addView(dry, LayoutParams(-1, -1)); addView(wet, LayoutParams(-1, -1)); addView(laser, LayoutParams(-1,-1))
        wet.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                var wrote = false
                strokes.forEach { (id, stroke) ->
                    val pending = finishing.remove(id) ?: return@forEach
                    commit(pending, stroke); wrote = true
                }
                dry.invalidate()
                wet.removeFinishedStrokes(strokes.keys)
                publishChange()
                if(fixedWritingRegion!=null)onWritingFinished()
                if (wrote) onInkWritten?.invoke()
            }
        })
        // Navigation sees each event first, so a pen arriving cancels a finger gesture before its stroke starts.
        wet.setOnTouchListener { _, event -> palmRoute(event); true }
    }

    // ---- P573: palm rest. With finger writing on (rubber-tip pens report as fingers), a resting hand is left out
    // before navigation or ink see it; see PalmFilter for the rules. ----
    private val palmFilter by lazy {
        PalmFilter(palmSize = 18f * resources.displayMetrics.xdpi / 25.4f, slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    }
    private var palmDownTime = 0L
    private fun palmRoute(event: MotionEvent) {
        val act = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> PalmFilter.Act.DOWN
            MotionEvent.ACTION_MOVE -> PalmFilter.Act.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> PalmFilter.Act.UP
            MotionEvent.ACTION_CANCEL -> PalmFilter.Act.CANCEL
            else -> null
        }
        if (act == null) { route(event); return }
        val contacts = (0 until event.pointerCount).map { i ->
            PalmFilter.Contact(event.getPointerId(i), event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER, event.getX(i), event.getY(i), event.getTouchMajor(i))
        }
        val enabled = drawWithFinger && !repository.readOnly && inputEnabled
        val emits = palmFilter.filter(act, event.getPointerId(event.actionIndex), contacts, event.eventTime, enabled,
            firstOfGesture = event.actionMasked == MotionEvent.ACTION_DOWN)
        val all = contacts.map { it.id }
        val unchanged = emits == null || emits.size == 1 && emits[0].keep == all && emits[0].act == act &&
            (act == PalmFilter.Act.MOVE || act == PalmFilter.Act.CANCEL || emits[0].actionId == event.getPointerId(event.actionIndex))
        if (unchanged) { if (event.actionMasked == MotionEvent.ACTION_DOWN) palmDownTime = event.downTime; route(event); return }
        emits!!.forEach { emit -> palmEvent(event, emit)?.let { part -> try { route(part) } finally { part.recycle() } } }
    }
    /** [event] carrying only [emit]'s pointers, with their history, as [emit]'s action. */
    private fun palmEvent(event: MotionEvent, emit: PalmFilter.Emit): MotionEvent? {
        val indices = emit.keep.map { event.findPointerIndex(it) }
        if (indices.isEmpty() || indices.any { it < 0 }) return null
        val at = emit.keep.indexOf(emit.actionId)
        val action = when (emit.act) {
            PalmFilter.Act.DOWN -> if (indices.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (at.coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PalmFilter.Act.UP -> if (indices.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (at.coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PalmFilter.Act.MOVE -> MotionEvent.ACTION_MOVE
            PalmFilter.Act.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        if (action == MotionEvent.ACTION_DOWN) palmDownTime = event.eventTime
        val properties = Array(indices.size) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(indices[it], p) } }
        fun coords(pos: Int) = Array(indices.size) { MotionEvent.PointerCoords().also { c ->
            if (pos < 0) event.getPointerCoords(indices[it], c) else event.getHistoricalPointerCoords(indices[it], pos, c) } }
        val history = if (emit.act == PalmFilter.Act.MOVE) event.historySize else 0
        val first = if (history > 0) event.getHistoricalEventTime(0) else event.eventTime
        val part = MotionEvent.obtain(palmDownTime, first, action, indices.size, properties, coords(if (history > 0) 0 else -1), event.metaState,
            event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        if (history > 0) {
            for (pos in 1 until history) part.addBatch(event.getHistoricalEventTime(pos), coords(pos), event.metaState)
            part.addBatch(event.eventTime, coords(-1), event.metaState)
        }
        return part
    }

    // ---- B33 (P196): androidx.ink's finished-stroke layer asks for a frame on every display refresh while it is attached,
    // with nothing to draw and even with the app in the background. It is only needed while ink is handed over, so it
    // rests a moment after the last stroke settles and comes back before the next one starts. ----
    private var finishedLayer: View? = null
    private var finishedLayerParent: ViewGroup? = null
    private val restLayer = Runnable { restFinishedLayer() }
    private fun wakeFinishedLayer() {
        removeCallbacks(restLayer); postDelayed(restLayer, LAYER_REST_MS)
        val layer = finishedLayer ?: return
        val parent = finishedLayerParent ?: return
        if (layer.parent == null && parent.isAttachedToWindow) parent.addView(layer, 0)
    }
    private fun restFinishedLayer() {
        removeCallbacks(restLayer)
        if (active.isNotEmpty() || finishing.isNotEmpty() || wet.hasUnfinishedStrokes() || wet.getFinishedStrokes().isNotEmpty()) {
            postDelayed(restLayer, LAYER_REST_MS); return
        }
        val layer = finishedLayer ?: findFinishedLayer(wet)?.also { finishedLayer = it; finishedLayerParent = it.parent as? ViewGroup } ?: return
        (layer.parent as? ViewGroup)?.removeView(layer)
    }
    // P530: a stroke written while a resized wet surface is being replaced can wait forever for its render callback,
    // holding the editor until the next pause. The library is asked to hand it over; failing that it is committed from
    // its samples, as a pause would.
    private val handoffWatch = Runnable { recoverHandoff() }
    @SuppressLint("RestrictedApi")
    private fun recoverHandoff() {
        if (finishing.isEmpty()) return
        // P558: flush ends every open wet stroke, including one under the pen, and the library holds a finished stroke
        // back while a later one is open. So waiting here is normal during writing: flushing crashed the next pen move.
        if (active.values.any { it.inkId != null && it.snap == null }) { removeCallbacks(handoffWatch); postDelayed(handoffWatch, HANDOFF_WATCH_MS); return }
        val flushed = wet.flush(HANDOFF_FLUSH_MS, java.util.concurrent.TimeUnit.MILLISECONDS, false)
        android.util.Log.w("NotesInk", "Handoff watch: flushed=$flushed left=${finishing.size}")
        if (finishing.isEmpty()) return
        commitFinishedInput()
        dry.invalidate(); publishChange()
    }
    private fun findFinishedLayer(view: View): View? {
        if (view.javaClass.name == FINISHED_LAYER) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) findFinishedLayer(view.getChildAt(i))?.let { return it }
        return null
    }

    // ---- P076 ruler (F055): a screen guide; a stroke started at an edge is projected onto it before navigation and ink see it. ----
    val ruler = Ruler(resources.displayMetrics.density, resources.displayMetrics.xdpi / 25.4f).apply {
        night = context.night
        scaleRatio = toolPreferences.getInt("rulerScaleRatio",0).takeIf { it in RulerScale.ratios } ?: 0
    }
    fun selectRulerScale(ratio: Int) {
        check(!isEditing && !navigating) { "Finish the current gesture first" }
        ruler.scaleRatio=ratio;toolPreferences.edit().putInt("rulerScaleRatio",ratio).apply()
        dry.invalidate();publishChange()
    }
    var rulerShown = false; private set
    fun showRuler(shown: Boolean) {
        if (shown && !rulerShown) { ruler.fit(width, height); ruler.place(width / 2f, height / 2f, 0f) }
        rulerShown = shown; rulerPen = null; rulerHeldPen = false; invalidate()
    }
    private var rulerPen: Matrix? = null
    private var rulerHeldPen = false
    private fun route(event: MotionEvent) {
        if(fixedWritingRegion!=null) {
            if(event.actionMasked==MotionEvent.ACTION_CANCEL) {rulerHeldPen=false;boxFinger=false;handle(event)}
            else (fingerOnly(event) ?: penOnly(event))?.let {try {handle(it)}finally {it.recycle()}}
            return
        }
        if (selectPdfText(event)) return
        if (fingerObjectContact(event)) return
        if (!rulerShown) { navigate(event); handle(event); return }
        val fingers = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (ruler.touch(event, fingers)) {
            invalidate()
            // The hand holding the ruler is not navigation; a pen drawing meanwhile still reaches the ink alone.
            penOnly(event)?.let { pen -> try { project(pen) } finally { pen.recycle() } }
            return
        }
        project(event)
    }
    private fun project(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) rulerPen = rulerStart(event)
        val lock = rulerPen
        if (lock == null) { navigate(event); handle(event) }
        else {
            val projected = MotionEvent.obtain(event)
            try { projected.transform(lock); navigate(projected); handle(projected) } finally { projected.recycle() }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) rulerPen = null
    }
    private fun rulerStart(event: MotionEvent): Matrix? {
        val finger = drawWithFinger && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (event.pointerCount != 1 || (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS && !finger) || !effectivePen) return null
        val width = if (effectiveHighlighter) highlighterStyle.width else penStyle.width
        // P568: a fingertip is broader than a nib and hides the edge, so it gets twice the reach.
        return if (finger) ruler.edgeProjection(event.x, event.y, width * pageScale / 2, FINGER_RULER_REACH_DP * resources.displayMetrics.density)
            else ruler.edgeProjection(event.x, event.y, width * pageScale / 2)
    }
    /** P568: one finger writes in the zoom box as a pen does; a second finger or a pen ends it without a mark. */
    private var boxFinger = false
    private fun fingerOnly(event: MotionEvent): MotionEvent? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && drawWithFinger && event.pointerCount == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            boxFinger = true; return MotionEvent.obtain(event)
        }
        if (!boxFinger) return null
        val ending = event.pointerCount > 1 || !drawWithFinger
        if (ending || event.actionMasked == MotionEvent.ACTION_UP) boxFinger = false
        return MotionEvent.obtain(event).also { if (ending) it.action = MotionEvent.ACTION_CANCEL }
    }
    private fun penOnly(event: MotionEvent): MotionEvent? {
        val i = (0 until event.pointerCount).firstOrNull { event.getToolType(it) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER) } ?: return null
        val lifting = event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) && event.actionIndex == i)
        val action = when {
            event.actionMasked == MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            lifting -> MotionEvent.ACTION_UP
            !rulerHeldPen -> MotionEvent.ACTION_DOWN
            else -> MotionEvent.ACTION_MOVE
        }
        if (action == MotionEvent.ACTION_UP && !rulerHeldPen) return null
        rulerHeldPen = !lifting
        val properties = arrayOf(MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) })
        val coordinates = arrayOf(MotionEvent.PointerCoords().also { event.getPointerCoords(i, it) })
        return MotionEvent.obtain(event.downTime, event.eventTime, action, 1, properties, coordinates, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
    }
    private var fittedPageId: String? = null
    private var fittedPageWidth = 0f
    private var fittedPageHeight = 0f
    private fun finishEmptyLasso(region: Path?, point: Point, allowPaste: Boolean): Boolean {
        if (selectedCount != 0) return false
        if (pickLocked(region,point,6 * handleUnit)) return true
        if (allowPaste && hasPasteable) {
            val owner = repository.currentPage?.id ?: return false
            pasteSpot = PasteSpot(owner,point); publishChange(); return true
        }
        return false
    }
    private fun publishChange() {
        if (active.values.none { it.snap != null }) { snapAnimator?.cancel(); snapAnimator = null }
        scheduleHoldCheck()
        repository.setInputBusy(this,isEditing); changed()
    }
    fun refresh() {
        syncReplayFrames()
        val currentLaserPage = repository.session?.notebook?.id to repository.selectedPageId
        if(laserPage != currentLaserPage || !inputEnabled || repository.busy || repository.fileBusy) laser.clear()
        laserPage = currentLaserPage
        if(pdfTextSelection?.matches(repository.currentPage)==false) clearPdfTextSelection()
        selection.sync()
        photoSelection.id // Synchronize against page, snapshot and reading-mode changes.
        textSelection.id
        repository.setInputBusy(this,isEditing)
        requestPdf()
        dry.invalidate()
        val page = repository.currentPage
        if (!isEditing) resizedFrom?.let { resizedFrom = null; refitAfterResize(it[0], it[1]) }
        if (fittedPageId != repository.selectedPageId || page?.width != fittedPageWidth || page?.height != fittedPageHeight) fitted = false
        // A page added, removed or resized beside this one changes the spread.
        if (fitted && page != null && strip == null && spreadFor(page)?.key != spread?.key) fitted = false
        if (!fitted && width > 0 && repository.currentPage != null) fit()
        pageCurl?.deskColor = deskColor; bookFlip?.deskColor = deskColor
        val turnable = pageCurlEnabled && strip == null && !zoomedIn && inputEnabled && !isEditing
        pageCurl?.sync(turnable && spread == null)
        bookFlip?.sync(turnable && spread != null)
    }
    /** Fits the page to the view, or in continuous mode lays it exactly over its slot in the strip. Frozen while input is pending. */
    fun fit(overview: Boolean = false) {
        laser.clear()
        if (isEditing) return
        val page = repository.currentPage ?: return
        fixedWritingRegion?.let {region->
            spread=null
            val scale=min(width/region.width(),height/region.height()).coerceAtLeast(.01f)
            viewport.setScale(scale,scale);viewport.postTranslate(-region.left*scale,-region.top*scale)
            viewport.invert(inverse);fitScale=scale;fitted=true;fittedPageId=page.id
            fittedPageWidth=page.width;fittedPageHeight=page.height;dry.invalidate();onViewportChanged();return
        }
        val strip = strip
        val frame = strip?.frameFor(page.id)
        if (strip != null && frame == null) { spread = null; fitted = false; dry.invalidate(); return }
        // F086: leaving a notebook remembers its view; the first fit of the next one picks up where it was left.
        val notebookId = repository.session?.notebook?.id
        if (notebookId != viewNotebookId && width > 0 && height > 0) {
            viewNotebookId?.let { if (strip == null) rememberSingleView(it) }
            viewNotebookId = notebookId
            restoreView = notebookId?.let { viewMemory.get(it) } as? ViewMemory.Single
        }
        val pair = if (strip == null) spreadFor(page) else null
        spread = pair
        val area = fitArea
        val scale = pair?.scale ?: frame?.let { it.width() / page.width } ?: min((area.width() - 24f) / page.width, (area.height() - 24f) / page.height).coerceAtLeast(.01f)
        if (pair != null) viewport.set(pair.fit)
        else {
            viewport.setScale(scale, scale)
            if (frame != null) viewport.postTranslate(frame.left, frame.top)
            else viewport.postTranslate(area.centerX() - page.width * scale / 2, area.centerY() - page.height * scale / 2)
            // F063: a page grown much longer than it is wide opens filling the view from its top-left corner; pinch out for all of it.
            if (frame == null && !overview && !zoomLocked) fillScale(page, area)?.let { fill ->
                viewport.setScale(fill, fill); viewport.postTranslate(area.left + 12f, area.top + 12f)
            }
        }
        // F089: a locked zoom opens every page at the same level, keeping its top edge in place.
        if (strip == null && zoomLocked && lockedZoom > 1.001f) {
            val top = floatArrayOf(0f, 0f).also { viewport.mapPoints(it) }[1]
            viewport.postScale(lockedZoom, lockedZoom, width / 2f, top)
        }
        restoreView?.let { saved ->
            restoreView = null
            if (strip == null && pair == null && !zoomLocked && saved.pageId == page.id && saved.zoom > 1.001f) {
                val zoomed = scale * saved.zoom
                viewport.setScale(zoomed, zoomed)
                viewport.postTranslate(area.centerX() - saved.centreX * zoomed, area.centerY() - saved.centreY * zoomed)
            }
        }
        viewport.invert(inverse); fitScale = scale; fitted = true; fittedPageId = page.id
        fittedPageWidth = page.width; fittedPageHeight = page.height; dry.invalidate()
        onViewportChanged()
    }
    /** P176 (G1b): edges covered by the floating Glass chrome, in pixels. The page is drawn under them but fits clear of them. */
    private val fitInsets = RectF()
    /** The part of this view a fitted page fills: all of it, less [fitInsets]. */
    val fitArea get() = RectF(fitInsets.left, fitInsets.top, width - fitInsets.right, height - fitInsets.bottom).takeIf { it.width() > 48f && it.height() > 48f }
        ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
    fun setFitInsets(left: Float, top: Float, right: Float, bottom: Float) {
        if (fitInsets.left == left && fitInsets.top == top && fitInsets.right == right && fitInsets.bottom == bottom) return
        // A zoomed page or spread keeps its level and the page point at the fit area's centre (a restored view included).
        val hadSpread = spread != null
        val keep = if (fitted && strip == null && zoomedIn && fitScale > 0f)
            (pageScale / fitScale) to fitArea.let { floatArrayOf(it.centerX(), it.centerY()) }.also { inverse.mapPoints(it) } else null
        fitInsets.set(left, top, right, bottom)
        if (isEditing || width <= 0) return
        val overview = fitted && strip == null && !zoomedIn
        settleCurl(); cancelCurl(); fit(overview)
        if (keep == null || hadSpread != (spread != null)) return
        val (zoom, centre) = keep
        val area = fitArea; val zoomed = fitScale * zoom
        viewport.setScale(zoomed, zoomed)
        viewport.postTranslate(area.centerX() - centre[0] * zoomed, area.centerY() - centre[1] * zoomed)
        viewport.invert(inverse); dry.invalidate()
        onViewportChanged()
    }
    internal val viewMemory = ViewMemory(toolPreferences)
    private var viewNotebookId: String? = null
    private var restoreView: ViewMemory.Single? = null
    /** Saves the single-page view of [notebookId]: its zoom (a multiple of fit) and the page point at the view centre. */
    private fun rememberSingleView(notebookId: String) {
        val pageId = fittedPageId ?: return
        // refresh() clears `fitted` when the page changes, before fit() runs; the viewport still shows the notebook being left.
        if (spread != null || fitScale <= 0f) return
        val centre = fitArea.let { floatArrayOf(it.centerX(), it.centerY()) }.also { inverse.mapPoints(it) }
        val zoom = (pageScale / fitScale).coerceIn(1f, 8f).let { if (it <= 1.001f) 1f else it }
        runCatching { viewMemory.save(notebookId, ViewMemory.Single(pageId, zoom, centre[0], centre[1])) }
    }
    /** F086: saves where the open notebook is being viewed (MainActivity calls this through prepareForPause). */
    fun rememberView() {
        val notebookId = repository.session?.notebook?.id ?: return
        if (notebookId != viewNotebookId) return
        val strip = strip
        if (strip == null) rememberSingleView(notebookId)
        else strip.anchor()?.let { (pageId, top) -> runCatching { viewMemory.save(notebookId, ViewMemory.Strip(pageId, top.coerceAtLeast(0f))) } }
    }
    /** F089: a locked zoom keeps its level; pinch and the zoom buttons leave it alone. Local preference. */
    var zoomLocked = toolPreferences.getBoolean("zoomLocked", false); private set
    /** The locked level as a multiple of the fitted scale (1 = fit). */
    var lockedZoom = toolPreferences.getFloat("lockedZoom", 1f).takeIf { it.isFinite() && it in 1f..8f } ?: 1f; private set
    /** Locking keeps the level on screen now (single-page mode); continuous mode keeps the last saved level. */
    fun selectZoomLock(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        cancelCurl()
        if (enabled && strip == null && fitted) lockedZoom = (pageScale / fitScale).coerceIn(1f, 8f).let { if (it <= 1.001f) 1f else it }
        zoomLocked = enabled
        toolPreferences.edit().putBoolean("zoomLocked", enabled).putFloat("lockedZoom", lockedZoom).apply()
        showHint(if (enabled) "Zoom locked at ${(lockedZoom * 100).roundToInt()}%" else "Zoom unlocked")
        publishChange()
    }
    /** F063: the scale that fills the view across a long page's short side, or null for an ordinary page. */
    private fun fillScale(page: Page, area: RectF): Float? {
        if (page.hasBackground) return null
        return when {
            page.height > page.width * 2f -> (area.width() - 24f) / page.width
            page.width > page.height * 2f -> (area.height() - 24f) / page.height
            else -> null
        }?.takeIf { it > 0f }
    }
    /** F063: fit() shrinks a large page far enough that eight times fit would be too little, so a large blank page zooms as far as an ordinary one. */
    private val zoomCap: Float get() {
        val page = repository.currentPage
        val large = page != null && !page.hasBackground && maxOf(page.width, page.height) > 2800f
        return if (large) maxOf(fitScale * 8f, minOf(width, height) / 1000f * 8f) else fitScale * 8f
    }
    /** The Fit button: on a long page it moves between filling the view and showing the whole page. */
    fun fitButton() {
        val page = repository.currentPage
        val filling = page != null && strip == null && spread == null && fillScale(page, fitArea)?.let { kotlin.math.abs(pageScale / it - 1f) < .01f } == true
        fit(filling)
    }
    /** F063: gives the current page more room as one Undo step. The view stays at its level and moves to show the new room. */
    fun growPage(newWidth: Float, newHeight: Float): Boolean {
        check(!isEditing)
        val note = repository.session?.notebook ?: return false
        val page = repository.currentPage ?: return false
        val before = Matrix(viewport)
        val wasSpread = spread != null
        if (!repository.resizePage(note, page.id, newWidth, newHeight)) return false
        showRoom(page, before, wasSpread, newWidth > page.width, newHeight > page.height, 0f, 0f)
        return true
    }
    /** P546 (F063): adds room in [direction]; room above or to the left moves what is on the page along with it. */
    fun addRoom(direction: PageRoom.Direction, pages: Float): Boolean {
        check(!isEditing)
        val note = repository.session?.notebook ?: return false
        val page = repository.currentPage ?: return false
        val size = PageRoom.grown(page, direction, pages) ?: return false
        val shift = PageRoom.shift(page, direction, size)
        val before = Matrix(viewport)
        val wasSpread = spread != null
        if (!repository.addPageRoom(note, page.id, direction, pages)) return false
        showRoom(page, before, wasSpread, size.first > page.width, size.second > page.height, shift.x, shift.y)
        return true
    }
    /** Keeps the zoom level and moves so the old edge facing the new room sits 30% into the view; what was on the page stays put on screen until then. */
    private fun showRoom(page: Page, before: Matrix, wasSpread: Boolean, wider: Boolean, taller: Boolean, dx: Float, dy: Float) {
        if (strip != null) return
        fit()
        if (wasSpread || spread != null) return
        viewport.set(before); viewport.preTranslate(-dx, -dy)
        val far = floatArrayOf(page.width + dx, page.height + dy, dx, dy).also { viewport.mapPoints(it) }
        val area = fitArea
        viewport.postTranslate(
            if (!wider) 0f else if (dx > 0f) area.left + area.width() * .7f - far[2] else area.left + area.width() * .3f - far[0],
            if (!taller) 0f else if (dy > 0f) area.top + area.height() * .7f - far[3] else area.top + area.height() * .3f - far[1])
        viewport.invert(inverse); dry.invalidate(); onViewportChanged()
    }
    fun zoom(factor: Float) {
        if (isEditing || strip != null || zoomLocked) return
        cancelCurl()
        require(factor.isFinite() && factor > 0f) { "Zoom factor must be positive and finite" }
        val current = pageScale
        val target = (current * factor).coerceIn(fitScale, zoomCap)
        if (target <= fitScale * 1.001f) { fit(overview = true); return }
        val area = fitArea
        viewport.postScale(target / current, target / current, area.centerX(), area.centerY())
        viewport.invert(inverse); dry.invalidate()
        onViewportChanged()
    }
    /** Current page scale (view pixels per page unit) and the scale `fit()` chose. */
    val pageScale get() = FloatArray(9).also { viewport.getValues(it) }[Matrix.MSCALE_X]
    var fitScale = 1f; private set
    /** Fit is the navigation baseline (100%); allow only a tiny floating-point tolerance. */
    private val zoomedIn get() = pageScale > fitScale * 1.001f
    /** The current page's rectangle in this view's coordinates. */
    val pageRect: RectF? get() = repository.currentPage?.let { RectF(0f, 0f, it.width, it.height).also { r -> viewport.mapRect(r) } }

    // ---- Finger navigation (user decision D3, build-order step 4). Fingers never ink; this only moves the view. ----

    enum class FingerMode(val label: String) { ONE("One finger"), TWO("Two fingers"), OFF("Off") }
    data class FingerSettings(val mode: FingerMode = FingerMode.ONE, val axis: TurnAxis = TurnAxis.HORIZONTAL, val handLock: Boolean = true, val graceMs: Long = 1000) {
        init { require(graceMs in 300..3000) { "Grace time must be 0.3–3 s" } }
    }
    var fingerSettings = runCatching {
        FingerSettings(FingerMode.valueOf(toolPreferences.getString("fingerMode", "ONE")!!), TurnAxis.valueOf(toolPreferences.getString("turnAxis", "HORIZONTAL")!!),
            toolPreferences.getBoolean("handLock", true), toolPreferences.getLong("handLockGraceMs", 1000))
    }.getOrDefault(FingerSettings())
        private set
    fun selectFingerSettings(settings: FingerSettings) {
        check(!isEditing) { "Wait for input completion" }
        cancelCurl()
        fingerSettings = settings
        toolPreferences.edit().putString("fingerMode", settings.mode.name).putString("turnAxis", settings.axis.name)
            .putBoolean("handLock", settings.handLock).putLong("handLockGraceMs", settings.graceMs).apply()
        touch = machine(settings); endNavigation(null)
        publishChange()
    }

    /** Continuous mode host (user decision D1): the strip under this view, which draws the other pages. */
    interface Strip {
        /** Where a page sits in this view's coordinates, or null while it is not laid out. */
        fun frameFor(pageId: String): RectF?
        fun scrollBy(dy: Float)
        fun fling(velocityY: Float)
        fun stopFling()
        /** The pen is near or on (x, y) and no input is pending: the page under it may become current. */
        fun penAt(x: Float, y: Float)
        fun pageAt(x: Float, y: Float): Pair<String, Point>? = null
        /** F086: the page at the top of the strip and its local y there, or null while nothing is laid out. */
        fun anchor(): Pair<String, Float>? = null
    }
    /** Non-null in continuous mode. Fingers then scroll the strip and the page is laid over its slot. */
    var strip: Strip? = null
        set(value) { cancelCurl(); endNavigation(null); field = value; fitted = false; dry.invalidate(); fit() }
    /** Single-page swipe; returns true when the page changed. */
    var onPageTurn: (TurnDirection) -> Boolean = { false }
    /** P084: a short one-finger tap on an attached-file card of the current page. Fingers never ink, so this never competes with a stroke. */
    var onAttachmentTap: (dev.ownednotes.core.FileAttachment) -> Unit = {}
    internal var onPdfLinkTap: (String, Point) -> Unit = { _, _ -> }
    /** P568: does a tap here open something? Null while a PDF page's links are still being read. */
    internal var tapTarget: (String, Point) -> Boolean? = { _, _ -> false }
    internal var onFingerDown: (String) -> Unit = {}
    /** P568: true when this finger's tap opened something, so its dot is taken back. */
    private var fingerTapTaken = false
    private var fingerLassoDropped = false
    /** The dot has to reach the page first, which can take a moment; about three seconds is long enough to stop asking. */
    private fun followAfterDot(pageId: String, point: Point, tries: Int = 75) {
        if (!isEditing) onPdfLinkTap(pageId, point)
        else if (tries > 0) postDelayed({ followAfterDot(pageId, point, tries - 1) }, 40)
    }
    /** Current-page point in this view's coordinates (tests and menus). */
    internal fun pageToView(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { viewport.mapPoints(it) }
    private var tapPointer = -1; private var tapX = 0f; private var tapY = 0f; private var tapTime = 0L

    var pageCurlEnabled = toolPreferences.getBoolean("pageCurl", true); private set
    private var pageCurl: PageCurlView? = null
    internal val pageCurlForTest get() = pageCurl!!
    /** For tests: why a page turn is or isn't ready. */
    internal val pageCurlState get() = "enabled=$pageCurlEnabled strip=${strip != null} zoomed=$zoomedIn input=$inputEnabled editing=$isEditing spread=${spread != null} previews=${pageCurl?.previewsReady} active=$pageCurlActive ${pageCurl?.warmState}"
    private var curlEligible = false
    private var curlOriginX = 0f; private var curlOriginY = 0f
    private var curlDx = 0f; private var curlDy = 0f
    val pageCurlActive get() = pageCurl?.active == true
    val pageCurlProgress get() = pageCurl?.progress ?: 0f
    val pageCurlReady get() = pageCurl?.previewsReady == true
    fun configurePageCurl(thumbnails: PageThumbnails) {
        check(pageCurl == null)
        pageCurl = PageCurlView(context, repository, thumbnails, { direction ->
            inputEnabled && !isEditing && !repository.busy && !repository.fileBusy && onPageTurn(direction)
        }, { pdfBackgroundReady }).also {
            it.endCard = endCard; it.fitArea = { fitArea }; addView(it, LayoutParams(-1, -1))
            // P587: during a turn the page doesn't change, so the graphics chip keeps it as one picture rather than redrawing
            // every stroke under the turning page each frame (about 12 ms a frame on the tablet with a written page).
            it.onTurning = { on -> dry.setLayerType(if (on) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null) }
        }
        thumbnailService = thumbnails
        bookFlip = BookFlipView(context, repository, thumbnails, { offset ->
            inputEnabled && !isEditing && !repository.busy && !repository.fileBusy && onPageJump(offset)
        }, { pdfBackgroundReady }).also { it.seeThrough = seeThroughEnabled; it.onShownChanged = { onBookFlipChanged() }; addView(it, LayoutParams(-1, -1)) }
    }
    /** P585: frames the page left to the strip's picture while its background loaded (tests). */
    internal var stripWaitingDraws = 0L
    fun selectPageCurl(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        cancelCurl(); pageCurlEnabled = enabled
        toolPreferences.edit().putBoolean("pageCurl", enabled).apply(); publishChange()
    }
    fun disposePageCurl() {
        pageCurl?.dispose(); pageCurl = null; curlEligible = false
        bookFlip?.dispose(); bookFlip = null; partnerRequest?.cancel(); partnerRequest = null; partnerRequestKey = null; thumbnailService = null
    }
    private fun cancelCurl() { pageCurl?.cancel(); bookFlip?.cancel(); curlEligible = false; curlDx = 0f; curlDy = 0f }
    /** P560: a turn already let go lands now rather than being thrown away, so the next swipe starts on its destination. */
    private fun settleCurl() { pageCurl?.settleNow(); bookFlip?.settleNow() }
    /** B13 (P136): the "Add page" sheet the single-page curl turns onto past the last page, and back from. */
    var endCard: PageCurlView.EndCard? = null
        set(value) { field = value; pageCurl?.endCard = value }
    /** A finger pull on the open "Add page" sheet; false when no curl can show it, so the sheet closes on its own. */
    fun dragFromEnd(originY: Float, dx: Float, dy: Float): Boolean {
        if (!pageCurlEnabled || spread != null || zoomedIn || isEditing || !inputEnabled) return false
        val rect = pageRect ?: return false
        return pageCurl?.dragFromEnd(originY, dx, dy, rect) == true
    }
    /** Lets go of [dragFromEnd]: it completes past a third of the way, or at once on a fast swipe back. */
    fun releaseFromEnd(fast: Boolean) { if (pageCurl?.active == true) pageCurl?.release(if (fast) TurnDirection.PREVIOUS else null) }

    // ---- Landscape two-page spread (N003). One page stays live and editable; its partner is drawn beside it. ----

    var bookSpreadEnabled = toolPreferences.getBoolean("bookSpread", app.bookSpreadDefault); private set
    /** The turning sheet's other side shows through faintly. */
    var seeThroughEnabled = toolPreferences.getBoolean("pageSeeThrough", true); private set
    fun selectBookSpread(enabled: Boolean) {
        check(!isEditing) { "Wait for input completion" }
        cancelCurl(); bookSpreadEnabled = enabled
        toolPreferences.edit().putBoolean("bookSpread", enabled).apply()
        fitted = false; fit(); publishChange()
    }
    fun selectSeeThrough(enabled: Boolean) {
        seeThroughEnabled = enabled; bookFlip?.seeThrough = enabled
        toolPreferences.edit().putBoolean("pageSeeThrough", enabled).apply(); publishChange()
    }
    /** Moves by [offset] pages; MainActivity supplies the real jump. Spread turns move two pages at a time. */
    var onPageJump: (Int) -> Boolean = { offset -> when (offset) {
        1 -> onPageTurn(TurnDirection.NEXT)
        -1 -> onPageTurn(TurnDirection.PREVIOUS)
        else -> false
    } }
    /** The fitted spread: the partner's rectangle in the current page's units, and the fit for the current page. */
    private class Spread(val partnerId: String?, val partner: RectF, val currentOnLeft: Boolean, val fit: Matrix, val scale: Float, val key: String)
    private var spread: Spread? = null
    private var thumbnailService: PageThumbnails? = null
    private var bookFlip: BookFlipView? = null
    private val slotLeft = RectF()
    private val slotRight = RectF()
    /** True while the current page is shown beside its partner (landscape, single-page layout). */
    val spreadShown get() = spread != null
    /** The partner page's rectangle in this view, or null without one. */
    val partnerRect: RectF? get() = spread?.takeIf { it.partnerId != null }?.let { RectF(it.partner).also { r -> viewport.mapRect(r) } }
    val bookFlipActive get() = bookFlip?.active == true
    /** P585: the spread's two slots in this view, the right one even when no page fills it; false without a spread. */
    fun spreadSlots(left: RectF, right: RectF): Boolean {
        val s = spread ?: return false
        val own = pageRect ?: return false
        val other = RectF(s.partner).also { viewport.mapRect(it) }
        if (s.currentOnLeft) { left.set(own); right.set(other) } else { left.set(other); right.set(own) }
        return true
    }
    /** P585: the final spread of a notebook with an odd page count, whose right slot is empty. */
    val spreadEndsEmpty get() = spread?.partnerId == null && spread != null
    /** P585: a spread's turning sheet appeared or went. */
    var onBookFlipChanged: () -> Unit = {}
    val bookFlipProgress get() = bookFlip?.progress ?: 0f
    val bookFlipReady get() = bookFlip?.previewsReady == true
    private fun spreadFor(page: Page): Spread? {
        val pages = repository.session?.notebook?.pages ?: return null
        if (!bookSpreadEnabled || strip != null || width <= height || pages.size < 2) return null
        val index = pages.indexOfFirst { it.id == page.id }
        if (index < 0) return null
        val start = BookSpread.start(index)
        val left = pages[start]; val right = pages.getOrNull(start + 1)
        val area = fitArea
        BookSpread.slots(area.width(), area.height(), 12f, left, right, slotLeft, slotRight)
        slotLeft.offset(area.left, area.top); slotRight.offset(area.left, area.top)
        val onLeft = index == start
        val own = if (onLeft) slotLeft else slotRight
        val other = if (onLeft) slotRight else slotLeft
        val scale = own.height() / page.height
        val fit = Matrix().apply { setScale(scale, scale); postTranslate(own.left, own.top) }
        val partnerPage = if (onLeft) right else left
        val partner = RectF((other.left - own.left) / scale, 0f, (other.right - own.left) / scale, page.height)
        // Not the view's size: onSizeChanged handles that, keeping a zoomed spread (P560).
        return Spread(partnerPage?.id, partner, onLeft, fit, scale, "${partnerPage?.id}:${partnerPage?.width}:${partnerPage?.height}")
    }
    /** The whole spread in this view's coordinates. */
    private fun spreadBounds(s: Spread): RectF? = repository.currentPage?.let { RectF(0f, 0f, it.width, it.height).apply { union(s.partner); viewport.mapRect(this) } }
    /** A pen over the partner page makes it current before any stroke starts, keeping the view exactly where it is. */
    private fun penOnSpread(x: Float, y: Float) {
        val s = spread ?: return
        val id = s.partnerId ?: return
        if (isEditing || !inputEnabled || repository.busy || repository.fileBusy || overSelectionHandle(x, y)) return
        val xy = floatArrayOf(x, y); inverse.mapPoints(xy)
        if (!s.partner.contains(xy[0], xy[1])) return
        val partner = repository.session?.notebook?.pages?.firstOrNull { it.id == id } ?: return
        val k = s.partner.width() / partner.width
        val next = Matrix(viewport).apply { preTranslate(s.partner.left, s.partner.top); preScale(k, k) }
        cancelCurl()
        repository.selectPage(id, recordHistory = false)
        if (repository.selectedPageId != id) return
        val adopted = spreadFor(partner) ?: return fit()
        viewport.set(next); viewport.invert(inverse); spread = adopted; fitScale = adopted.scale
        fitted = true; fittedPageId = id; fittedPageWidth = partner.width; fittedPageHeight = partner.height
        requestPdf(); dry.invalidate(); onViewportChanged(); changed()
    }
    /** A swipe without the animation turns the whole spread: to the next spread's first page, or the previous one's last. */
    private fun spreadTurn(direction: TurnDirection): Boolean {
        val pages = repository.session?.notebook?.pages ?: return false
        val index = pages.indexOfFirst { it.id == repository.selectedPageId }
        if (index < 0) return false
        val start = BookSpread.start(index)
        val target = if (direction == TurnDirection.NEXT) start + 2 else start - 1
        if (direction == TurnDirection.NEXT && target >= pages.size)
            return endCard?.takeIf { it.available }?.open() == true
        return target in pages.indices && onPageJump(target - index)
    }

    private fun machine(s: FingerSettings): TouchIntentMachine {
        val metrics = resources.displayMetrics
        val mm = metrics.xdpi / 25.4f
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        return TouchIntentMachine(
            oneFingerPan = s.mode == FingerMode.ONE, twoFingerPan = s.mode != FingerMode.OFF, pinchZoom = s.mode != FingerMode.OFF,
            swipeToTurn = s.mode == FingerMode.ONE, turnAxis = s.axis, handLockGraceMs = if (s.handLock) s.graceMs else 0,
            // Pixel thresholds from physical size; the palm size is a backstop, the hand lock is the main rule.
            palmContactSize = 18 * mm, touchSlop = slop, pinchSlop = slop * 1.5f, swipeMinDistance = 12 * mm,
            swipeMinVelocity = .15f * mm, tapMaxMovement = slop * 1.5f,
        ).apply { fingerWriting = drawWithFinger }
    }
    private var touch = machine(fingerSettings)
    private var gestureStart: Matrix? = null
    private var gestureStartedAt = 0L
    /** True while a cancellation from the system itself, not a pen or a palm rule, is being handled. */
    private var systemTookGesture = false
    private var turned = false
    private var stripScrolled = false
    private var fingerVelocity: VelocityTracker? = null
    /** True while fingers would be ignored because the pen is near or was just put away. */
    fun isHandLocked(timeMs: Long = android.os.SystemClock.uptimeMillis()) = touch.isHandLocked(timeMs)
    /** True while a finger gesture is moving the view. */
    val navigating get() = gestureStart != null || stripScrolled || pageCurlActive || bookFlipActive

    /** Pen hover anywhere in the window, in this view's coordinates (MainActivity forwards it). */
    private fun overSelectionHandle(x: Float, y: Float): Boolean {
        if (!effectiveLasso) return false
        val xy = floatArrayOf(x, y); inverse.mapPoints(xy)
        return textSelection.hit(Point(xy[0], xy[1]), handleUnit, photoViewport) != null || photoSelection.hit(Point(xy[0], xy[1]), handleUnit, photoViewport) != null || selection.handles(selectionPaths(visiblePaths(repository.currentPage?.strokes.orEmpty())), handleUnit, viewportTop)?.hit(Point(xy[0], xy[1])) != null
    }
    fun penHover(action: Int, x: Float, y: Float, timeMs: Long) {
        val signal = when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> PenSignal.HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> PenSignal.HOVER_EXIT
            else -> return
        }
        stylusHovering = signal == PenSignal.HOVER_ENTER
        if (signal == PenSignal.HOVER_ENTER) { settleCurl(); cancelCurl() }
        if (fingerSettings.handLock) applyIntent(touch.onPen(signal, timeMs))
        if (signal == PenSignal.HOVER_ENTER) strip?.let { it.stopFling(); if (!isEditing && !overSelectionHandle(x, y) && x in 0f..width.toFloat() && y in 0f..height.toFloat()) it.penAt(x, y) }
        if (signal == PenSignal.HOVER_ENTER && strip == null) penOnSpread(x, y)
    }

    private fun navigate(event: MotionEvent) {
        // Library/settings can disable input before the platform cancels the old child gesture.
        // Forward that cancellation so its pointer tracks cannot block a fresh gesture later.
        val canceling = event.actionMasked == MotionEvent.ACTION_CANCEL
        if (repository.currentPage == null && !canceling) return
        if (!canceling && (!inputEnabled || repository.busy || repository.fileBusy)) {
            applyIntent(TouchIntent.Cancel); endNavigation(null); return
        }
        val action = event.actionMasked
        val fingers = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        val pens = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        val index = event.actionIndex
        if (action == MotionEvent.ACTION_DOWN) {
            // P568: a read-only page takes no ink, so one finger keeps moving it.
            touch.fingerWriting = drawWithFinger && !repository.readOnly
            // P586: a landed page still waiting for its PDF stays up; the next turn can start from it.
            settleCurl(); if (pageCurl?.holding == true) { bookFlip?.cancel(); curlEligible = false; curlDx = 0f; curlDy = 0f } else cancelCurl()
            curlEligible = pageCurlEnabled && strip == null && !zoomedIn && !isEditing &&
                fingerSettings.mode == FingerMode.ONE && fingerSettings.axis == TurnAxis.HORIZONTAL &&
                fingers.size == 1 && pens.isEmpty() && (spread?.let { spreadBounds(it) } ?: pageRect)?.contains(event.getX(index), event.getY(index)) == true
            curlOriginX = event.getX(index); curlOriginY = event.getY(index)
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || pens.isNotEmpty()) cancelCurl()
        val writer = action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN) && (index in pens ||
            (action == MotionEvent.ACTION_DOWN && index in fingers && !repository.readOnly && fingerMayWrite(event)))
        if (writer) strip?.let {
            // The strip must not move under a stroke; the page under the pen becomes current before the stroke starts.
            it.stopFling(); if (!isEditing && !overSelectionHandle(event.getX(index), event.getY(index))) it.penAt(event.getX(index), event.getY(index))
        } ?: run { if (writer) penOnSpread(event.getX(index), event.getY(index)) }
        fun sample(i: Int, phase: TouchPhase) = TouchSample(event.getPointerId(i), if (i in fingers) TouchTool.FINGER else TouchTool.STYLUS, phase,
            event.getX(i), event.getY(i), event.eventTime, event.getTouchMajor(i), palm = phase == TouchPhase.UP && event.flags and MotionEvent.FLAG_CANCELED != 0)
        val known = fingers + pens
        trackAttachmentTap(event, fingers, pens)
        if (fingers.isNotEmpty() && pens.isEmpty()) (fingerVelocity ?: VelocityTracker.obtain().also { fingerVelocity = it }).addMovement(event)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (index in known) applyIntent(touch.onTouch(sample(index, TouchPhase.DOWN)))
            MotionEvent.ACTION_MOVE -> known.forEach { applyIntent(touch.onTouch(sample(it, TouchPhase.MOVE))) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (index in known) applyIntent(touch.onTouch(sample(index, TouchPhase.UP)))
            MotionEvent.ACTION_CANCEL -> {
                systemTookGesture = true
                fingers.firstOrNull()?.let { applyIntent(touch.onTouch(sample(it, TouchPhase.CANCEL))) }
                pens.firstOrNull()?.let { applyIntent(touch.onTouch(sample(it, TouchPhase.CANCEL))) }
                systemTookGesture = false
            }
        }
        val lastFingerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
            (action == MotionEvent.ACTION_POINTER_UP && fingers.none { it != index })
        if (lastFingerUp && (index in fingers || action == MotionEvent.ACTION_CANCEL)) endNavigation(if (action == MotionEvent.ACTION_CANCEL) null else event.getPointerId(index))
    }

    /**
     * B12: a still finger held on a locked object offers to unlock it; the lift that follows is not a tap.
     * P183 (G8): held anywhere else, it opens the quick ring at the finger. Only fingers reach here, so pen holds keep HoldAssist.
     */
    private val fingerHold = Runnable {
        if (tapPointer < 0 || navigating || !inputEnabled || repository.busy || repository.fileBusy ||
            !pdfBackgroundReady || (fingerSettings.handLock && isHandLocked()) || (laserEnabled && drawWithFinger)) return@Runnable
        if (isEditing) {
            // P568: in finger writing the still finger has only begun a dot or a lasso, and the hold replaces it.
            val onlyThisFinger = drawWithFinger && finishing.isEmpty() && attachmentDrag == null && !photoSelection.isEditing &&
                !textSelection.isEditing && selection.ids.isEmpty() && active.keys.all { it == tapPointer }
            if (!onlyThisFinger) return@Runnable
            cancelActive()
        }
        val xy = floatArrayOf(tapX, tapY); inverse.mapPoints(xy)
        if (pickLocked(null, Point(xy[0], xy[1]), 8 * handleUnit)) tapPointer = -1
        else if (selectFingerObject(Point(xy[0], xy[1]))) {
            objectFingerPointer = tapPointer; tapPointer = -1
            touch.onTouch(TouchSample(objectFingerPointer, TouchTool.FINGER, TouchPhase.CANCEL, tapX, tapY, SystemClock.uptimeMillis()))
            cancelCurl(); endNavigation(null)
            dry.invalidate(); publishChange()
        }
        else onFingerHold?.let { tapPointer = -1; it(tapX, tapY) }
    }
    private data class AttachmentDrag(val owner: Notebook, val pageId: String, val card: FileAttachment,
        val start: Point, var preview: ImagePlacement)
    private var attachmentDrag: AttachmentDrag? = null
    private var objectFingerPointer = -1
    private fun selectFingerObject(point: Point): Boolean {
        val page = repository.currentPage ?: return false
        if (repository.readOnly) return false
        AttachmentRendering.hit(page, point.x, point.y)?.takeIf { !it.locked }?.let { card ->
            if (!repository.canChangeNotebook) return false
            val owner = repository.session?.notebook ?: return false
            selection.clear(); photoSelection.clear(); textSelection.clear(); lockedPick = null
            attachmentDrag = AttachmentDrag(owner, page.id, card, point, card.placement)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return true
        }
        page.editableTexts.lastOrNull { it.placement.contains(point) }?.let { return selectText(it.id) }
        page.editableImages.lastOrNull { it.placement.contains(point) }?.let { return selectPhoto(it.id) }
        return false
    }
    private fun cancelFingerObject() {
        objectFingerPointer = -1; attachmentDrag = null
        photoSelection.cancelGesture(); textSelection.cancelGesture()
    }
    /** B45: only an already selected object is directly draggable; ordinary finger swipes still navigate. */
    private fun fingerObjectContact(event: MotionEvent): Boolean {
        val finger = event.pointerCount == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val allowed = finger && !rulerShown && inputEnabled && pdfBackgroundReady && !repository.readOnly &&
            !repository.busy && !repository.fileBusy && active.isEmpty() &&
            !(fingerSettings.handLock && isHandLocked(event.eventTime)) &&
            event.getTouchMajor(0) < 18f * resources.displayMetrics.xdpi / 25.4f &&
            event.flags and MotionEvent.FLAG_CANCELED == 0
        if (!allowed || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (objectFingerPointer >= 0) { cancelFingerObject(); dry.invalidate(); publishChange() }
            return false
        }
        attachmentDrag?.let { d ->
            if (d.owner !== repository.session?.notebook || d.pageId != repository.selectedPageId ||
                event.getPointerId(0) != objectFingerPointer) { cancelFingerObject(); publishChange(); return false }
            val point = pagePoint(event.x, event.y)
            runCatching { d.preview = d.card.placement.translated(point.x - d.start.x, point.y - d.start.y) }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                attachmentDrag = null; objectFingerPointer = -1
                if (d.preview != d.card.placement) runCatching { repository.edit { it.updateAttachment(d.pageId, d.card.id, d.preview) } }
                    .onFailure { failure("Attachment move failed: ${it.message}") }
            }
            dry.invalidate(); publishChange(); return true
        }
        val map: (Float, Float) -> Point = ::pagePoint
        fun contact(value: MotionEvent): Boolean = if (selectedTextId != null)
            textSelection.contact(value, handleUnit, photoViewport, map, allowFinger = true)
            else if (selectedPhotoId != null) photoSelection.contact(value, handleUnit, photoViewport, map, allowFinger = true)
            else false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isEditing || !effectiveLasso || !contact(event)) return false
            objectFingerPointer = event.getPointerId(0)
            removeCallbacks(fingerHold); tapPointer = -1; cancelCurl(); endNavigation(null)
            dry.invalidate(); publishChange(); return true
        }
        if (objectFingerPointer < 0 || event.getPointerId(0) != objectFingerPointer) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !photoSelection.isEditing && !textSelection.isEditing) {
            if (hypot(event.x - tapX, event.y - tapY) <= ViewConfiguration.get(context).scaledTouchSlop) return true
            val down = MotionEvent.obtain(event)
            try { down.action = MotionEvent.ACTION_DOWN; down.setLocation(tapX, tapY); contact(down) } finally { down.recycle() }
        }
        if (photoSelection.isEditing || textSelection.isEditing) contact(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) objectFingerPointer = -1
        dry.invalidate(); publishChange(); return true
    }
    /** P183 (G8): a still finger held on the page, in view coordinates. */
    var onFingerHold: ((Float, Float) -> Unit)? = null
    private fun trackAttachmentTap(event: MotionEvent, fingers: List<Int>, pens: List<Int>) {
        // P568: in finger writing a finger is writing as well as tapping: a tap on something opens it, anywhere else it is a dot.
        val writing = drawWithFinger && !repository.readOnly
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              fingerTapTaken = false
              if (fingers.size == 1 && pens.isEmpty() && (!isEditing || writing)) {
                tapPointer = event.getPointerId(index); tapX = event.getX(index); tapY = event.getY(index); tapTime = event.eventTime
                removeCallbacks(fingerHold); postDelayed(fingerHold, ViewConfiguration.getLongPressTimeout().toLong() * (if (writing) 2 else 1))
                if (writing) repository.selectedPageId?.let(onFingerDown)
              } else tapPointer = -1
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { tapPointer = -1; removeCallbacks(fingerHold) }
            MotionEvent.ACTION_MOVE -> if (tapPointer >= 0) {
                val i = event.findPointerIndex(tapPointer)
                if (i < 0 || pens.isNotEmpty() || hypot(event.getX(i) - tapX, event.getY(i) - tapY) > ViewConfiguration.get(context).scaledTouchSlop) { tapPointer = -1; removeCallbacks(fingerHold) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(fingerHold)
                val tapped = tapPointer >= 0 && event.getPointerId(index) == tapPointer && event.eventTime - tapTime <= ViewConfiguration.getLongPressTimeout() &&
                    event.flags and MotionEvent.FLAG_CANCELED == 0 && (!isEditing || writing) && !turned
                tapPointer = -1
                if (tapped) {
                    val page = repository.currentPage ?: return
                    val xy = floatArrayOf(event.getX(index), event.getY(index)); inverse.mapPoints(xy)
                    val card = AttachmentRendering.hit(page, xy[0], xy[1])
                    if (card != null) { fingerTapTaken = writing; post { onAttachmentTap(card) } }
                    else if (!(fingerSettings.handLock && isHandLocked(event.eventTime))) {
                        val hit = strip?.pageAt(event.getX(index), event.getY(index)) ?: spread?.let { spread ->
                            spread.partnerId?.takeIf { spread.partner.contains(xy[0], xy[1]) }?.let { id ->
                                repository.session?.notebook?.pages?.firstOrNull { it.id == id }?.let { partner ->
                                    val scale = spread.partner.width() / partner.width
                                    id to Point((xy[0] - spread.partner.left) / scale, (xy[1] - spread.partner.top) / scale)
                                }
                            }
                        } ?: (page.id to Point(xy[0], xy[1]))
                        if (!writing) onPdfLinkTap(hit.first, hit.second)
                        else when (tapTarget(hit.first, hit.second)) {
                            true -> { fingerTapTaken = true; post { onPdfLinkTap(hit.first, hit.second) } }
                            null -> followAfterDot(hit.first, hit.second)
                            false -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun applyIntent(intent: TouchIntent) {
        val strip = strip
        when (intent) {
            is TouchIntent.Pan -> if (strip != null) scrollStrip(strip, -intent.dy)
                else if (zoomedIn) transform { it.postTranslate(intent.dx, intent.dy) }
                else if (curlEligible && !isEditing) {
                    curlDx += intent.dx; curlDy += intent.dy
                    val s = spread
                    if (s != null) pageRect?.let { own ->
                        val other = RectF(s.partner).also { viewport.mapRect(it) }
                        bookFlip?.drag(curlDx, if (s.currentOnLeft) own else other, if (s.currentOnLeft) other else own)
                    } else pageRect?.let { pageCurl?.drag(curlOriginX, curlOriginY, curlDx, curlDy, it) }
                }
            is TouchIntent.Zoom -> if (strip != null) scrollStrip(strip, -intent.dy)
            else if (zoomLocked) { if (zoomedIn) transform { it.postTranslate(intent.dx, intent.dy) } }
            else {
                cancelCurl()
                val current = pageScale
                val target = (current * intent.scale).coerceIn(fitScale, zoomCap)
                if (target <= fitScale * 1.001f) transform { fit(overview = true) }
                else transform { m ->
                    m.postScale(target / current, target / current, intent.focusX, intent.focusY)
                    m.postTranslate(intent.dx, intent.dy)
                }
            }
            is TouchIntent.PageTurn -> if (strip == null && !isEditing && !zoomedIn) {
                if (bookFlipActive) bookFlip?.release(intent.direction)
                // P586: while a landed page is held, a swipe that couldn't start a turn yet waits its turn: an instant turn
                // here showed the next page bare while its PDF drew.
                else if (pageCurl?.holding == true) pageCurl?.queue(intent.direction)
                else if (pageCurlActive) pageCurl?.release(intent.direction)
                else if (if (spread != null) spreadTurn(intent.direction) else onPageTurn(intent.direction)) turned = true
            }
            TouchIntent.Cancel -> {
                cancelCurl()
                strip?.stopFling(); stripScrolled = false
                // P560: a resting palm's brief gesture is undone; a deliberate zoom the system interrupted stays where it was.
                val deliberate = systemTookGesture && SystemClock.uptimeMillis() - gestureStartedAt >= DELIBERATE_GESTURE_MS
                gestureStart?.let { if (!isEditing && !deliberate) { viewport.set(it); viewport.invert(inverse); dry.invalidate(); onViewportChanged() } }
                gestureStart = null
            }
            TouchIntent.TwoFingerTap -> twoFingerTap()
            TouchIntent.Ignore -> Unit
        }
    }

    /** Undo on a two-finger tap, with a tick and a fading pill so the change is never silent. Returns whether it undid. */
    internal fun twoFingerTap(): Boolean {
        val session = repository.session
        if (!twoFingerUndo || isEditing || repository.busy || repository.fileBusy || session == null) return false
        val before = session.notebook
        val undone = session.canUndo && run { repository.edit { it.undo() }; repository.session?.notebook !== before }
        performHapticFeedback(if (undone) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.REJECT)
        showHint(if (undone) "Undo" else "Nothing to undo")
        return undone
    }
    var hintText: String? = null
        private set
    private var hintAlpha = 0f
    private var hintAnimator: ValueAnimator? = null
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false) }
    private val hintBounds = RectF()
    private fun showHint(text: String, lasting: Long = 900) {
        hintAnimator?.cancel(); hintText = text
        hintAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = lasting
            addUpdateListener {
                val t = it.animatedFraction
                // Quick fade in, hold, then fade out.
                hintAlpha = when { t < .15f -> t / .15f; t < .6f -> 1f; else -> 1f - (t - .6f) / .4f }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { if (hintAnimator === animation) { hintText = null; hintAnimator = null; invalidate() } }
            })
        }
        if (ValueAnimator.areAnimatorsEnabled()) hintAnimator?.start()
        else { hintAnimator = null; hintAlpha = 1f; postDelayed({ if (hintAnimator == null) { hintText = null; invalidate() } }, lasting) }
        invalidate()
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (rulerShown) ruler.draw(canvas,pageScale)
        val text = hintText ?: return
        val unit = resources.displayMetrics.density
        val w = hintLabel.measureText(text) + unit * 32; val h = unit * 36
        // Below a top-docked toolbar (56 dp bar + 6 dp margin), centred horizontally.
        hintBounds.set((width - w) / 2, unit * 88, (width + w) / 2, unit * 88 + h)
        hintPaint.color = 0xe6182636.toInt(); hintPaint.alpha = (0xe6 * hintAlpha).roundToInt()
        canvas.drawRoundRect(hintBounds, h / 2, h / 2, hintPaint)
        hintLabel.alpha = (255 * hintAlpha).roundToInt()
        canvas.drawText(text, hintBounds.centerX(), hintBounds.centerY() - (hintLabel.descent() + hintLabel.ascent()) / 2, hintLabel)
    }

    /** Applies a finger transform in single-page mode; the transform stays frozen while any input is pending. */
    private inline fun transform(change: (Matrix) -> Unit) {
        if (isEditing || turned) return
        if (gestureStart == null) { gestureStart = Matrix(viewport); gestureStartedAt = SystemClock.uptimeMillis() }
        change(viewport); viewport.invert(inverse); dry.invalidate()
        onViewportChanged()
    }
    private fun scrollStrip(strip: Strip, dy: Float) {
        if (isEditing) return
        stripScrolled = true; strip.scrollBy(dy)
    }

    /** The last finger lifted: fling the strip, or let a fitted page settle back after a pan that did not turn it. */
    private fun endNavigation(liftedPointer: Int?) {
        if (liftedPointer != null && pageCurlActive) pageCurl?.release()
        if (liftedPointer != null && bookFlipActive) bookFlip?.release()
        curlEligible = false
        val tracker = fingerVelocity
        val strip = strip
        if (strip != null && stripScrolled && liftedPointer != null && tracker != null && !isEditing) {
            val configuration = ViewConfiguration.get(context)
            tracker.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
            val vy = tracker.getYVelocity(liftedPointer)
            if (kotlin.math.abs(vy) > configuration.scaledMinimumFlingVelocity) strip.fling(-vy)
        }
        if (strip == null && gestureStart != null && !turned && !zoomedIn) fit()
        gestureStart = null; turned = false; stripScrolled = false
        tracker?.clear()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); ruler.fit(w, h)
        // A stroke in progress freezes the view; the size is caught up with once it ends (refresh).
        if (isEditing) { if (resizedFrom == null) resizedFrom = intArrayOf(oldw, oldh); return }
        refitAfterResize(oldw, oldh)
    }
    private var resizedFrom: IntArray? = null
    /** P560: a zoomed page survives any change of this view's size. A system bar coming or going (Samsung's taskbar under
     * an arm) leaves it exactly where it is; a real resize (rotation, a pane's width) keeps its level and centre. */
    private fun refitAfterResize(oldw: Int, oldh: Int) {
        val zoomed = fitted && strip == null && fixedWritingRegion == null && zoomedIn && fitScale > 0f && oldw > 0 && oldh > 0
        if (!zoomed) { settleCurl(); cancelCurl(); fit(); return }
        val hadSpread = spread != null
        val before = Matrix(viewport)
        val zoom = pageScale / fitScale
        val centre = floatArrayOf((fitInsets.left + oldw - fitInsets.right) / 2, (fitInsets.top + oldh - fitInsets.bottom) / 2).also { inverse.mapPoints(it) }
        val systemBar = width == oldw && kotlin.math.abs(height - oldh) <= SYSTEM_BAR_DP * resources.displayMetrics.density
        fit()
        if (hadSpread != (spread != null)) return
        if (systemBar) viewport.set(before)
        else {
            val area = fitArea; val scale = fitScale * zoom
            viewport.setScale(scale, scale)
            viewport.postTranslate(area.centerX() - centre[0] * scale, area.centerY() - centre[1] * scale)
        }
        viewport.invert(inverse); dry.invalidate(); onViewportChanged()
    }
    fun cancelActive() {
        pendingLassoCompletion = null
        laser.clear()
        rulerHeldPen=false;boxFinger=false
        cancelSnapFrame()
        cancelFingerObject()
        applyIntent(TouchIntent.Cancel)
        endNavigation(null)
        touch = machine(fingerSettings)
        selection.cancelGesture()
        photoSelection.cancelGesture()
        textSelection.cancelGesture()
        active.values.forEach { pending -> if (pending.snap == null) pending.inkId?.let { id -> wet.cancelStroke(id) } }
        active.clear()
        buttonHeld = false
        router.route(PointerPacket(PointerAction.CANCEL, -1, emptyList()))
        publishChange()
    }
    private fun outlines(stroke: Stroke) = outlinesOf(stroke)
    private fun commit(pending: Pending, stroke: Stroke) {
        try {
            check(repository.session?.notebook?.id == pending.notebookId) { "Notebook changed before stroke completion" }
            val original = InkStroke(color = pending.style.inkColor, width = pending.style.width, brush = pending.kind,
                samples = pending.samples.toList(), outlines = outlines(stroke), layer = repository.session?.activeLayer(pending.pageId), audio=pending.audio)
            // A renderer handoff may arrive after navigation or pause. Never use another page's pixels.
            val page = repository.currentPage
            val straight = if (pending.followText && pending.kind == InkStroke.HIGHLIGHTER &&
                page?.id == pending.pageId && (page.textBoxes.isNotEmpty() ||
                    (page.pdfBackground != null && pdfKey == page.pdfBackground))) {
                run {
                    runCatching {
                        // B04 (P132): one straight highlight per printed line, so colouring a region marks each line it covers.
                        val pdf = pdfBitmap.takeIf { page.pdfBackground != null && pdfKey == page.pdfBackground }
                        val lines = TextHighlight.pageBands(page, pdf, pending.samples, pending.style.width, textLayouts::layout).map { band ->
                            TextHighlight.straighten(band, pending.samples) { width -> makeHighlighter(pending.style, width) } ?: return@runCatching null
                        }
                        if (lines.isEmpty()) return@runCatching null
                        val candidates = lines.mapIndexed { i, line ->
                            original.copy(id = if (i == 0) original.id else java.util.UUID.randomUUID().toString(), samples = line.samples, width = line.width, outlines = outlines(line.stroke))
                        }
                        // Validate the whole proposed document before starting the two-entry history transaction.
                        val notebook = repository.session!!.notebook
                        notebook.copy(pages = notebook.pages.map {
                            if (it.id == pending.pageId) it.copy(strokes = (it.strokes + candidates).sortedBy { s -> it.layers.indexOfFirst { l -> l.id == s.layer } }) else it
                        }).validate()
                        candidates
                    }.getOrNull()
                }
            } else null
            // P074 scribble to erase: the ink a pen scribble covers on its own page.
            val scribbled = if (straight != null || !ScribbleErase.enabled(context)) emptySet() else
                repository.session!!.notebook.pages.firstOrNull { it.id == pending.pageId }?.strokes?.let { strokes ->
                    val current = strokes === repository.currentPage?.strokes
                    ScribbleErase.covered(original, strokes.filter(onActiveLayer(pending.pageId))) { if (current) visiblePaths(strokes).getValue(it.id) else InkGeometry.visible(it) }
                }.orEmpty()
            repository.edit {
                it.add(original, pending.pageId)
                // Undo restores the user's freehand mark before a second Undo removes it.
                if (straight != null) it.replaceStrokes(original.id, straight, pending.pageId)
                // Likewise Undo after a scribble erase brings the covered ink back with the scribble kept as ink.
                if (scribbled.isNotEmpty()) it.remove(scribbled + original.id, pending.pageId)
                true
            }
            if (scribbled.isNotEmpty()) {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                showHint("Erased ${scribbled.size} stroke${if (scribbled.size == 1) "" else "s"}. Undo keeps your scribble")
            } else if (straight == null && repository.session?.notebook?.pages?.firstOrNull { it.id == pending.pageId }?.strokes?.any { it.id == original.id } == true)
                handStyle.written(original, pending.pageId, pending.notebookId)
        } catch (error: Exception) { failure("Stroke could not be committed: ${error.message}") }
    }

    // ---- Hold-to-shape (P025). The hold clock runs on the main thread because a perfectly still pen sends no events. ----

    private val holdCheck = Runnable { checkHolds() }
    private fun scheduleHoldCheck() {
        removeCallbacks(holdCheck)
        val now = SystemClock.uptimeMillis()
        val wait = active.values.mapNotNull { if (it.snap == null) it.hold?.remaining(now) else null }.minOrNull() ?: return
        postDelayed(holdCheck, wait + 1)
    }
    private fun checkHolds() {
        val now = SystemClock.uptimeMillis()
        active.values.toList().forEach { pending ->
            val hold = pending.hold
            if (pending.snap == null && !pending.erase && hold != null && hold.held(now)) { hold.consume(); snap(pending) }
        }
        scheduleHoldCheck()
    }
    /** P075: selects the ink inside a held loop with the Lasso rule and switches to the Lasso; the loop itself is dropped. */
    private fun selectLoop(pending: Pending, inkId: InProgressStrokeId, drawn: List<Point>, minSize: Float): Boolean {
        val page = repository.currentPage?.takeIf { it.id == pending.pageId } ?: return false
        val region = LoopSelect.area(drawn, minSize) ?: return false
        val fully = selection.options.rule == InkSelection.Rule.FULLY_ENCLOSED
        val ids = pickablePaths(InkSelection.Filter.ALL).filterValues { InkGeometry.selects(it, region, fully) }.keys
        // B12: a held loop around only locked objects offers to unlock them instead of snapping to a shape.
        val locked = ids.isEmpty() && pickLocked(region, null, 0f)
        if (ids.isEmpty() && !locked) return false
        wet.cancelStroke(inkId)
        // The rest of this contact (until lift) goes nowhere: the pointer leaves ink input and the lasso has no gesture.
        active.values.remove(pending); router.route(PointerPacket(PointerAction.CANCEL, -1, emptyList()))
        if (locked) { dry.invalidate(); return true }
        lassoEnabled = true; eraser = false
        photoSelection.clear(); textSelection.clear(); selection.select(ids)
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showHint("Selected ${ids.size} stroke${if (ids.size == 1) "" else "s"}")
        onToolStateChanged()
        dry.invalidate(); publishChange()
        return true
    }
    /** Hold-to-shape snaps only a clear shape; the shape pen ([tidy], B26) turns every stroke into its nearest clean shape. */
    private fun snap(pending: Pending, lifted: Boolean = false, tidy: Boolean = false) {
        val inkId = pending.inkId ?: return
        if (!inputEnabled || repository.busy || repository.session?.notebook?.id != pending.notebookId || pending.samples.size < 2) return
        val unit = resources.displayMetrics.density / pageScale
        val drawn = pending.samples.map { Point(it.x, it.y) }
        // P075 circle and hold to select: a pen loop around ink selects it instead of snapping.
        if (!lifted && pending.kind != InkStroke.HIGHLIGHTER && LoopSelect.enabled(context) && selectLoop(pending, inkId, drawn, 12 * unit)) return
        val input = if (!lifted && pending.assist == HoldAssist.LINE) listOf(drawn.first(), drawn.last()) else drawn
        val spacing = (1.5f * unit).coerceIn(.5f, 4f)
        val shape = (if (tidy) ShapeRecognizer.tidy(input, spacing) else ShapeRecognizer.recognize(input, spacing)) ?: return
        if (shape.path.size > 25_000) return
        // The shape keeps the writer's typical pressure, so its weight matches the freehand line it replaces.
        val pressure = pending.samples.map { it.pressure }.sorted()[pending.samples.size / 2].coerceIn(.2f, 1f)
        val samples = shape.path.mapIndexed { index, point -> Sample(point.x, point.y, index * 4L, pressure) }
        val batch = MutableStrokeInputBatch()
        samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
        val stroke = try {
            InkStroke(color = pending.style.inkColor, width = pending.style.width, brush = pending.kind, samples = samples, outlines = outlines(Stroke(pending.brush, batch)), shapeKind = shape.kind, audio=pending.audio)
        } catch (error: Exception) { return }
        if (stroke.outlines.isEmpty()) return
        wet.cancelStroke(inkId)
        val exact = Path().apply { shape.path.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        pending.snap = Snap(shape.kind, stroke, InkGeometry.visible(stroke), exact, ShapeRecognizer.resample(drawn, 160), ShapeRecognizer.resample(shape.path, 160))
        if (!lifted) {
            val page=repository.session?.notebook?.pages?.firstOrNull { it.id==pending.pageId }
            val guides=if(shape.kind==ShapeRecognizer.Kind.LINE && page!=null)
                HeldShapeGuides.nearbyLines(page,shape.path.first(),shape.path.last(),unit) else emptyList()
            pending.heldShape = HeldShape(shape.kind, shape.path, drawn.last(),guides)
        }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        snapAnimator?.cancel()
        snapProgress = 0f
        // Emphasized deceleration: the line moves decisively, then settles. System-disabled animations jump to the end.
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { snapProgress = it.animatedValue as Float; dry.invalidate() }
            start()
        }
        dry.invalidate(); publishChange()
    }
    /** B43: inverse viewport mapping applies equally to fit, zoom and landscape partner pages. */
    /** Opt-in local phase diagnostics; no samples or content leave the canvas. Null during ordinary use. */
    internal data class SnapAdjustmentTiming(val geometryUs: Long, val brushUs: Long, val visibleUs: Long, val animationUs: Long, val worker: Boolean = false)
    internal var onSnapAdjustmentTiming: ((SnapAdjustmentTiming) -> Unit)? = null

    // Unbuffered stylus packets can outnumber display frames. Keep only the latest valid geometry,
    // but calculate every move against HeldShape's immutable baseline (including its dead-zone rules).
    private var snapFrameScheduled = false
    private val snapMain = android.os.Handler(android.os.Looper.getMainLooper())
    internal var snapPreviewExecutor: java.util.concurrent.Executor = SNAP_PREVIEW_WORKER
    private var snapBuildInFlight = false
    private var snapEpoch = 0L
    private val snapFrame = Runnable { snapFrameScheduled = false; buildSnapPreview() }
    private fun scheduleSnapFrame() {
        if (!snapFrameScheduled && !snapBuildInFlight && active.values.any { it.adjustment != null }) {
            snapFrameScheduled = true
            postOnAnimation(snapFrame)
        }
    }
    private fun buildSnapPreview() {
        if (snapBuildInFlight) return
        val pending = active.values.firstOrNull { it.adjustment != null } ?: return
        val result = pending.adjustment ?: return
        if (result == pending.renderedAdjustment) { pending.adjustment = null; scheduleSnapFrame(); return }
        val previous = pending.snap?.stroke ?: return
        if (!ownsSnapContact(pending)) { pending.adjustment = null; scheduleSnapFrame(); return }
        val style = pending.style
        val marker = pending.kind == InkStroke.HIGHLIGHTER
        val geometryUs = pending.adjustmentGeometryUs
        val timed = onSnapAdjustmentTiming != null
        val epoch = snapEpoch
        snapBuildInFlight = true
        try {
            snapPreviewExecutor.execute {
                val started = if (timed) System.nanoTime() else 0L
                // Worker touches no View state; native brush, batch, stroke and outline scratch are confined here.
                val next = runCatching {
                    buildSnapStroke(if (marker) makeHighlighter(style) else makeBrush(style), previous, result)
                }.getOrNull()
                val offMain = android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
                val brushUs = if (timed) (System.nanoTime() - started) / 1000 else 0L
                snapMain.post {
                    snapBuildInFlight = false
                    if (epoch == snapEpoch && ownsSnapContact(pending)) {
                        if (next != null) applySnapAdjustment(pending, result, next, geometryUs, brushUs, worker = offMain)
                        if (pending.adjustment === result) pending.adjustment = null
                    }
                    scheduleSnapFrame()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            snapBuildInFlight = false
            if (pending.adjustment === result) pending.adjustment = null
        }
    }
    private fun ownsSnapContact(pending: Pending) = isAttachedToWindow && inputEnabled && !repository.busy && !repository.readOnly && active.values.any { it === pending } &&
        repository.session?.notebook?.id == pending.notebookId && repository.currentPage?.id == pending.pageId
    private fun cancelSnapFrame() { removeCallbacks(snapFrame); snapFrameScheduled = false; snapEpoch++ }
    private fun adjustSnap(event: MotionEvent, pointerId: Int, pending: Pending, deferred: Boolean = false) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        val started = if (onSnapAdjustmentTiming != null) System.nanoTime() else 0L
        val xy = floatArrayOf(event.getX(index), event.getY(index)); inverse.mapPoints(xy)
        pending.heldShape?.move(Point(xy[0], xy[1]), 6f * handleUnit)?.let { pending.adjustment = it }
        if (onSnapAdjustmentTiming != null) pending.adjustmentGeometryUs = (System.nanoTime() - started) / 1000
        if (!deferred) renderSnapAdjustment(pending)
        else scheduleSnapFrame()
    }
    private fun renderSnapAdjustment(pending: Pending) {
        val result = pending.adjustment ?: return
        pending.adjustment = null
        if (result == pending.renderedAdjustment) return
        val previous = pending.snap ?: return
        val observer = onSnapAdjustmentTiming
        val started = if (observer != null) System.nanoTime() else 0L
        val next = buildSnapStroke(pending.brush, previous.stroke, result) ?: return
        val brushUs = if (observer != null) (System.nanoTime() - started) / 1000 else 0L
        applySnapAdjustment(pending, result, next, pending.adjustmentGeometryUs, brushUs, worker = false)
    }
    /** Mutable native inputs and outline scratch are local to this invocation, including worker calls. */
    private fun buildSnapStroke(brush: Brush, previous: InkStroke, result: HeldShape.Result): InkStroke? {
        val pressure = previous.samples.first().pressure
        val samples = result.path.mapIndexed { i, p -> Sample(p.x, p.y, i * 4L, pressure) }
        return runCatching {
            val batch = MutableStrokeInputBatch()
            samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
            previous.copy(samples = samples, outlines = outlines(Stroke(brush, batch))).takeIf { it.outlines.isNotEmpty() }
        }.getOrNull()
    }
    private fun applySnapAdjustment(pending: Pending, result: HeldShape.Result, next: InkStroke,
        geometryUs: Long, brushUs: Long, worker: Boolean) {
        val observer = onSnapAdjustmentTiming
        val started = if (observer != null) System.nanoTime() else 0L
        val fill = InkGeometry.visible(next)
        val visibleReady = if (observer != null) System.nanoTime() else 0L
        val exact = Path().apply { result.path.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val resampled = ShapeRecognizer.resample(result.path, 160)
        pending.snap = Snap(result.kind, next, fill, exact, resampled, resampled, result.guides)
        pending.renderedAdjustment = result
        snapAnimator?.cancel(); snapProgress = 1f
        dry.invalidate()
        observer?.invoke(SnapAdjustmentTiming(geometryUs, brushUs,
            (visibleReady-started)/1000, (System.nanoTime()-visibleReady)/1000, worker))
    }

    private fun commitSnap(pending: Pending) {
        val snap = pending.snap ?: return
        try {
            check(repository.session?.notebook?.id == pending.notebookId) { "Notebook changed before shape completion" }
            val original = InkStroke(color = pending.style.inkColor, width = pending.style.width, brush = pending.kind, samples = pending.samples.toList(), outlines = outlines(reconstruct(pending)), audio=pending.audio)
            // Two history steps: Undo first brings back the freehand stroke, then removes it.
            val before = repository.session?.notebook
            repository.edit { it.add(original, pending.pageId); it.replaceStroke(original.id, snap.stroke.copy(id = original.id), pending.pageId); true }
            repository.session?.notebook?.takeIf { it !== before }?.let { after -> onShapeSnapped?.invoke(snap.kind, after) }
        } catch (error: Exception) { failure("Shape could not be committed: ${error.message}") }
    }
    private var snapAnimator: ValueAnimator? = null
    private var snapProgress = 1f
    private val snapPath = Path()
    private val snapLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val snapFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val snapGuide = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Morphing line, a soft halo that widens and fades, then the committed geometry fading in as the motion settles. */
    private fun drawSnap(canvas: Canvas, snap: Snap, style: PenStyle) {
        val t = snapProgress
        val settle = ((t - .55f) / .45f).coerceIn(0f, 1f)
        val unit = resources.displayMetrics.density / pageScale
        val color = style.color.toInt()
        snapGuide.color = Glass.accent(context.night)
        snapGuide.alpha = 180; snapGuide.strokeWidth = unit
        val closedRulers=snap.kind==ShapeRecognizer.Kind.RECTANGLE || snap.kind==ShapeRecognizer.Kind.CIRCLE || snap.kind==ShapeRecognizer.Kind.ELLIPSE
        if(closedRulers){canvas.save();canvas.clipPath(snap.exact)}
        snap.guides.forEach { (a, b) ->
            canvas.drawLine(a.x, a.y, b.x, b.y, snapGuide)
            if(closedRulers)for(i in 0..4) {
                val x=a.x+(b.x-a.x)*i/4;val y=a.y+(b.y-a.y)*i/4
                val tick=if(i==2)3f*unit else 2f*unit
                if(kotlin.math.abs(a.y-b.y)<.001f)canvas.drawLine(x,y-tick,x,y+tick,snapGuide)
                else canvas.drawLine(x-tick,y,x+tick,y,snapGuide)
            }
        }
        if(closedRulers)canvas.restore()
        // A highlighter keeps its translucency and multiply blend throughout, so the snap never flashes opaque.
        val marker = snap.stroke.isHighlighter
        val opacity = if (marker) InkStroke.HIGHLIGHTER_OPACITY else style.opacity
        val blend = if (marker) BlendMode.MULTIPLY else null
        snapLine.blendMode = blend; snapFill.blendMode = blend
        if (t < 1f) {
            snapLine.color = color; snapLine.alpha = (56 * opacity * (1 - t)).toInt(); snapLine.strokeWidth = style.width + 16 * unit * t
            canvas.drawPath(snap.exact, snapLine)
        }
        if (settle < 1f) {
            snapPath.rewind()
            snap.from.indices.forEach { i ->
                val a = snap.from[i]; val b = snap.to[i]
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                if (i == 0) snapPath.moveTo(x, y) else snapPath.lineTo(x, y)
            }
            snapLine.color = color; snapLine.alpha = (255 * opacity * (1 - settle)).toInt(); snapLine.strokeWidth = style.width
            canvas.drawPath(snapPath, snapLine)
        }
        snapFill.color = color; snapFill.alpha = (255 * opacity * settle).toInt()
        canvas.drawPath(snap.fill, snapFill)
    }
    /** Renderer handoff is not guaranteed while the window is being stopped. */
    fun prepareForPause() {
        cancelActive()
        commitFinishedInput()
        rememberView()
        dry.invalidate(); publishChange()
        restFinishedLayer()
    }
    private fun commitFinishedInput() {
        finishing.toList().forEach { (id, pending) ->
            try {
                commit(pending, reconstruct(pending))
                finishing.remove(id)
            } catch (error: Exception) { failure("Pending stroke could not be saved: ${error.message}") }
        }
        dry.invalidate()
    }
    private fun reconstruct(pending: Pending): Stroke {
        val batch = MutableStrokeInputBatch()
        var lastTime = -1L
        pending.samples.forEach { sample ->
            // Keep original timestamps in the file; disambiguate only for reconstruction.
            lastTime = maxOf(sample.timeMs, lastTime + 1)
            batch.add(InputToolType.STYLUS, sample.x, sample.y, lastTime, pressure = sample.pressure, tiltRadians = PencilWetRenderer.inkTilt(sample.tilt), orientationRadians = PencilWetRenderer.inkOrientation(sample.orientation))
        }
        return Stroke(pending.brush, batch)
    }
    /**
     * F017: the same stored samples through the stroke's own brush at [width]; everything but width and outlines is kept.
     * B27 (P192): [brush] redraws a pen stroke as another pen kind. The outline shape doesn't depend on colour, so a
     * see-through stroke is drawn opaque here and keeps its own colour.
     */
    private fun restroke(stroke: InkStroke, width: Float, brush: String = stroke.brush): InkStroke {
        if (stroke.isTape) return stroke // A tape's size is its shape, not a pen width.
        val style = PenStyle(stroke.color or 0xff000000L, width.coerceIn(.5f, 50f), PenKind.forBrush(brush))
        val tip = if (stroke.isHighlighter) makeHighlighter(style) else makeBrush(style)
        val batch = MutableStrokeInputBatch()
        var lastTime = -1L
        stroke.samples.forEach { sample ->
            lastTime = maxOf(sample.timeMs, lastTime + 1)
            batch.add(InputToolType.STYLUS, sample.x, sample.y, lastTime, pressure = sample.pressure, tiltRadians = PencilWetRenderer.inkTilt(sample.tilt), orientationRadians = PencilWetRenderer.inkOrientation(sample.orientation))
        }
        val outlines = outlines(Stroke(tip, batch))
        check(outlines.isNotEmpty()) { "Stroke could not be redrawn at the new width" }
        return stroke.copy(width = width, brush = brush, outlines = outlines)
    }
    /** B27 (P192): one stroke with [change] applied; colour alone keeps the outlines, and each stroke keeps its opacity. */
    private fun restyled(stroke: InkStroke, change: InkSelection.Restyle): InkStroke {
        if (stroke.isTape) return stroke
        val color = change.color?.let { (stroke.color and 0xff000000L) or (it and 0xffffffL) } ?: stroke.color
        val brush = change.kind?.takeIf { PenKind.entries.any { it.brushId == stroke.brush } }?.brushId ?: stroke.brush
        val width = change.width ?: stroke.width
        val shaped = if (brush == stroke.brush && width == stroke.width) stroke else restroke(stroke, width, brush)
        return shaped.copy(color = color, fillColor = if (change.fillChanged && stroke.canFill) change.fillColor else stroke.fillColor)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { systemTookGesture = true; cancelActive(); systemTookGesture = false }
    }
    private fun updateButton(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) buttonHeld = false
        else if ((0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) {
            buttonHeld = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        }
    }
    companion object {
        /** The pen brush for [style]; also used away from the canvas, to redraw connectors (F059). */
        internal fun brushFor(style: PenStyle) = Brush.createWithColorIntArgb(PenSmoothing.family(when (style.kind) {
            PenKind.PRESSURE -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
            PenKind.BALLPOINT -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
            PenKind.DASHED -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
            PenKind.CALLIGRAPHY -> CalligraphyBrush.family
            PenKind.PENCIL -> PencilWetRenderer.family
            PenKind.FOUNTAIN -> FountainInk.everyday
            PenKind.FOUNTAIN_SOFT -> FountainInk.soft
        }, style.smoothing), style.inkColor.toInt(), style.width, .1f)
        internal fun outlinesOf(stroke: Stroke): List<List<Point>> {
            val shape = stroke.shape
            val point = MutableVec()
            return buildList {
                for (group in 0 until shape.getRenderGroupCount()) for (outline in 0 until shape.getOutlineCount(group)) {
                    val vertices = shape.getOutlineVertexCount(group, outline)
                    if (vertices >= 3) add(List(vertices) { index -> shape.populateOutlinePosition(group, outline, index, point); Point(point.x, point.y) })
                }
            }
        }
        private val SNAP_PREVIEW_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "held-shape-preview").apply { isDaemon = true }
        }
        /** How far from the pen the paint brush catches ink, on screen. */
        const val PAINT_REACH_DP = 6f
        /** B33 (P196): how long after the last stroke settles androidx.ink's finished-stroke layer is put to rest. */
        /** The most a system bar (Samsung's taskbar, a gesture hint) changes this view's height by. */
        private const val SYSTEM_BAR_DP = 72f
        private const val DELIBERATE_GESTURE_MS = 700L
        private const val LAYER_REST_MS = 1000L
        private const val HANDOFF_WATCH_MS = 1200L
        private const val HANDOFF_FLUSH_MS = 200L
        private const val FINISHED_LAYER = "androidx.ink.authoring.FinishedShapesView"
        // Samsung's alternate button-held contact actions; these may have buttonState == 0.
        fun isSamsungContact(event: MotionEvent) = event.actionMasked in 211..214 &&
            event.pointerCount == 1 && event.getToolType(0) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
    }
    private fun samsungContact(event: MotionEvent): Boolean {
        if (!isSamsungContact(event)) return false
        val normalized = MotionEvent.obtain(event)
        try {
            normalized.action = when (event.actionMasked) { 211 -> MotionEvent.ACTION_DOWN; 212 -> MotionEvent.ACTION_UP; 213 -> MotionEvent.ACTION_MOVE; else -> MotionEvent.ACTION_CANCEL }
            if(fixedWritingRegion==null)navigate(normalized)
            handle(normalized, samsungButton = true)
        } finally { normalized.recycle() }
        return true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown?.invoke()
        if(laserEnabled) {
            val allowed = inputEnabled && !repository.busy && !repository.fileBusy && repository.currentPage != null && pdfBackgroundReady
            laser.fingerDraws = drawWithFinger
            if(laser.accept(event,allowed)) {
                val pen = (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
                // P568: a pointing finger still counts towards a two-finger move, and never locks fingers out.
                if(!pen) { if(allowed) navigate(event) }
                // Keep the normal pen-proximity palm lock; pointer mode never enters the ink router.
                else when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN, 211 -> penHover(MotionEvent.ACTION_HOVER_ENTER,event.x,event.y,event.eventTime)
                    // P568: a pen that does not report hover would otherwise leave the lock on for good.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, 212, 214 -> penHover(MotionEvent.ACTION_HOVER_EXIT,event.x,event.y,event.eventTime)
                }
                return true
            }
            laser.clear()
            if(allowed) navigate(event)
            return true
        }
        return if(samsungContact(event)) true else super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if(laserEnabled) {
            if(isSamsungContact(event)) return dispatchTouchEvent(event)
            if(event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) penHover(MotionEvent.ACTION_HOVER_EXIT,event.x,event.y,event.eventTime)
            return super.dispatchGenericMotionEvent(event)
        }
        if (samsungContact(event)) return true
        val stylus = (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        if (!stylus) return super.dispatchGenericMotionEvent(event)
        val held = buttonHeld
        updateButton(event)
        val button = event.actionMasked in listOf(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)
        if (inputEnabled && !repository.readOnly && !repository.busy && pdfBackgroundReady && button) {
            if (lassoEnabled && (selection.isEditing || active.isNotEmpty())) {
                val contact = MotionEvent.obtain(event)
                try { contact.action = MotionEvent.ACTION_MOVE; handle(contact) } finally { contact.recycle() }
            } else active.keys.toList().forEach { switchSegment(event, it) }
        }
        // B33 (P196): hovering changes nothing here unless the side button changed, so it no longer redraws the page and toolbar.
        if (button || held != buttonHeld || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) publishChange()
        return true
    }
    private fun startSegment(event: MotionEvent, pointerId: Int, erase: Boolean, notebookId: String, pageId: String): Pending {
        // Resolve pending ink before erasing, so a quick button press can erase the last stroke too.
        val paint = !erase && effectivePainter
        if (erase || paint) commitFinishedInput()
        val marker = !erase && !paint && effectiveHighlighter
        val strokeBrush = if (marker) highlighterBrush else brush
        val id = if (erase || paint) null else { wakeFinishedLayer(); wet.startStroke(event, pointerId, strokeBrush, inverse) }
        // The paint brush catches ink within a fixed reach on screen, whatever the zoom.
        val radius = if (paint) PAINT_REACH_DP * resources.displayMetrics.density / pageScale else eraserRadius
        return Pending(id, event.eventTime, erase, notebookId, pageId, eraserMode, if (marker) highlighterStyle else penStyle, strokeBrush, radius,
            kind = if (marker) InkStroke.HIGHLIGHTER else penStyle.kind.brushId, target = if (paint) EraserTarget.ALL else eraserTarget, paint = paint, audio=if(erase || paint)null else (context.applicationContext as NotesApplication).audio.captureLink()).also {
            if (paint) { it.penPaint = paintChange(highlight = false); it.markPaint = paintChange(highlight = true) }
            it.assist = if (marker) highlighterAssist else penAssist
            it.followText = marker && highlighterFollowsText
            // Physical rest tolerance is converted to page units, including zoom.
            if (!erase && !paint && holdToShape && it.assist != HoldAssist.FREEHAND)
                it.hold = ShapeRecognizer.HoldDetector(resources.displayMetrics.xdpi / 25.4f * holdSettings.toleranceMm / pageScale, holdSettings.delayMs)
            capture(event, pointerId, it, includeHistory = false)
        }
    }
    private fun Pending.sweep() = EraserSweep(samples.map { Point(it.x, it.y) }, radius)
    /** B31 (P193): restyles a stroke the paint brush touched, once; a stroke that would not change, or cannot be redrawn, is left alone. */
    private fun paint(pending: Pending, stroke: InkStroke) {
        val next = runCatching { restyled(stroke, if (stroke.isHighlighter) pending.markPaint else pending.penPaint) }.getOrNull() ?: return
        if (next != stroke) pending.painted[stroke.id] = next to InkGeometry.visible(next)
    }
    private fun finishSegment(event: MotionEvent, pointerId: Int, pending: Pending, captureCurrent: Boolean = true) {
        if (captureCurrent) {
            if (pending.snap != null) adjustSnap(event, pointerId, pending)
            else capture(event, pointerId, pending)
        }
        // Tool boundaries deliberately exclude the boundary packet; flush the preceding valid move.
        if (pending.snap != null) renderSnapAdjustment(pending)
        if (pending.paint) {
            if (pending.painted.isNotEmpty()) try {
                if (repository.session?.notebook?.id == pending.notebookId) repository.edit { it.restyleStrokes(pending.pageId, pending.painted.values.map { p -> p.first }) }
                else failure("Notebook changed before the paint brush finished")
            } catch (error: Exception) { failure("Paint could not be committed: ${error.message}") }
        } else if (pending.erase) {
            try { if (repository.session?.notebook?.id == pending.notebookId) repository.edit {
                if (pending.mode == EraserMode.STROKE) it.remove(pending.erased, pending.pageId)
                else it.eraseArea(pending.erased, pending.sweep(), pending.pageId)
            }
            else failure("Notebook changed before eraser completion")
            } catch (error: Exception) { failure("Eraser change could not be committed: ${error.message}") }
        } else if (pending.snap != null) commitSnap(pending)
        // B26: the shape pen works with every pen (not the highlighter), and tidies whatever matches no shape.
        else if (shapePen && !pending.erase && pending.kind != InkStroke.HIGHLIGHTER && run { snap(pending, lifted = true, tidy = true); pending.snap != null }) commitSnap(pending)
        else if (toggleTapeAt(pending)) pending.inkId?.let { wet.cancelStroke(it) }
        else pending.inkId?.let { finishing[it] = pending; wet.finishStroke(event, pointerId, it); removeCallbacks(handoffWatch); postDelayed(handoffWatch, HANDOFF_WATCH_MS) }
    }
    /** P558: if the renderer ever ends a wet stroke early, its samples are still ours: save them and carry on as a new stroke rather than crash. */
    private fun addToWet(event: MotionEvent, pointerId: Int, pending: Pending, id: InProgressStrokeId) {
        try { wet.addToStroke(event, pointerId, id) }
        catch (ended: IllegalStateException) {
            android.util.Log.w("NotesInk", "A wet stroke ended early; kept its ${pending.samples.size} samples")
            commit(pending, reconstruct(pending))
            active[pointerId] = startSegment(event, pointerId, pending.erase, pending.notebookId, pending.pageId)
        }
    }
    private fun switchSegment(event: MotionEvent, pointerId: Int): Boolean {
        val pending = active[pointerId] ?: return false
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return false
        val erase = effectiveEraser || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val paint = !erase && effectivePainter
        if (erase == pending.erase && paint == pending.paint && (erase || paint || effectiveHighlighter == (pending.kind == InkStroke.HIGHLIGHTER))) return false
        // The new tool starts at the reported boundary, without sweeping across the prior segment.
        if (pending.erase || pending.paint || pending.snap != null) finishSegment(event, pointerId, pending, captureCurrent = false)
        else {
            // The boundary event belongs to the new tool. Do not append it to the old wet stroke.
            val stroke = reconstruct(pending)
            pending.inkId?.let { wet.cancelStroke(it) }
            commit(pending, stroke)
        }
        active[pointerId] = startSegment(event, pointerId, erase, pending.notebookId, pending.pageId)
        return true
    }
    private fun tool(type: Int) = when (type) { MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.PEN; MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER; MotionEvent.TOOL_TYPE_FINGER -> PointerTool.FINGER; MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE; else -> PointerTool.UNKNOWN }
    private fun handle(event: MotionEvent, samsungButton: Boolean = false) {
        if (repository.currentPage == null || repository.readOnly || repository.busy || !inputEnabled || !pdfBackgroundReady) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            fingerLassoDropped = false
            if (lockedPick != null || pasteSpot != null) { lockedPick = null; pasteSpot = null; publishChange() }
            pasteTap = if (selectedCount == 0) floatArrayOf(event.x, event.y) else null; pasteTapTime = event.eventTime
        }
        // In continuous mode the page must sit over its strip slot before ink can map into it.
        if (strip != null && !fitted) return
        updateButton(event)
        if (samsungButton && event.actionMasked != MotionEvent.ACTION_CANCEL) buttonHeld = true
        val physicalEraser = (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        val wantsLasso = effectiveLasso && !physicalEraser
        if (photoSelection.isEditing && !wantsLasso) photoSelection.cancelGesture()
        if (textSelection.isEditing && !wantsLasso) textSelection.cancelGesture()
        if (lassoEnabled) {
            if (selection.isEditing && !wantsLasso) {
                selection.cancelGesture()
                if (event.actionMasked !in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP)) {
                    val down = MotionEvent.obtain(event)
                    try { down.action = MotionEvent.ACTION_DOWN; handle(down, samsungButton) } finally { down.recycle() }
                    return
                }
            }
            if (wantsLasso && active.isNotEmpty() && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                active.toList().forEach { (pointer, pending) -> finishSegment(event, pointer, pending, captureCurrent = false) }
                active.clear(); router.route(PointerPacket(PointerAction.CANCEL, -1, emptyList()))
                if (event.actionMasked !in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP)) {
                    val down = MotionEvent.obtain(event)
                    try { down.action = MotionEvent.ACTION_DOWN; handle(down) } finally { down.recycle() }
                    return
                }
            }
            if (wantsLasso && active.isEmpty()) {
                // P568: a second finger means move or zoom, and a tap that opened something is not a selection.
                if (drawWithFinger && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER } &&
                    (event.pointerCount > 1 || fingerTapTaken || fingerLassoDropped)) {
                    fingerLassoDropped = true; fingerTapTaken = false; pasteTap = null
                    selection.cancelGesture(); photoSelection.cancelGesture(); textSelection.cancelGesture()
                    publishChange(); return
                }
                val fingerActs = fingerMayWrite(event)
                selection.allowFinger = fingerActs
                commitFinishedInput()
                val filter = selection.options.filter
                val photos = filter == InkSelection.Filter.ALL || filter == InkSelection.Filter.IMAGES
                val texts = filter == InkSelection.Filter.ALL || filter == InkSelection.Filter.TEXT
                if (filter == InkSelection.Filter.ALL && !selection.isEditing && selection.ids.isEmpty() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val xy = floatArrayOf(event.x, event.y); inverse.mapPoints(xy)
                    val point = Point(xy[0], xy[1]); val page = repository.currentPage!!
                    val text = page.editableTexts.lastOrNull { it.placement.contains(point) }
                    val photo = page.editableImages.lastOrNull { it.placement.contains(point) }
                    if (text != null) selectObjectGroup(dev.ownednotes.core.ObjectIds(texts=setOf(text.id)))
                    else if (photo != null) selectObjectGroup(dev.ownednotes.core.ObjectIds(images=setOf(photo.id)))
                }
                if (!selection.isEditing && (selection.ids.isEmpty() || selectedPhotoId != null || selectedTextId != null)) {
                    val map: (Float, Float) -> Point = { x, y ->
                        val point = floatArrayOf(x, y); inverse.mapPoints(point); Point(point[0], point[1])
                    }
                    val photoFirst = selectedPhotoId != null
                    val first = if (photoFirst) photos && photoSelection.contact(event, handleUnit, photoViewport, map, fingerActs)
                        else texts && textSelection.contact(event, handleUnit, photoViewport, map, fingerActs)
                    val second = !first && if (photoFirst) texts && textSelection.contact(event, handleUnit, photoViewport, map, fingerActs)
                        else photos && photoSelection.contact(event, handleUnit, photoViewport, map, fingerActs)
                    if (first || second) {
                        if ((first && photoFirst) || (second && !photoFirst)) textSelection.clear() else photoSelection.clear()
                        selection.clear(); dry.invalidate(); publishChange(); return
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { photoSelection.clear(); textSelection.clear() }
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) pendingLassoCompletion = null
                selection.contact(event, pickablePaths(filter), handleUnit, viewportTop) { x, y ->
                    val point = floatArrayOf(x, y); inverse.mapPoints(point); Point(point[0], point[1])
                }
                if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) && photos != texts) selection.lastRegion?.let { region ->
                    // Text or Images filter: the lasso picks the topmost object of that kind instead of ink.
                    val page = repository.currentPage!!
                    val enclosed = selection.options.rule == InkSelection.Rule.FULLY_ENCLOSED
                    fun outline(p: ImagePlacement) = Path().apply {
                        listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).forEachIndexed { i, (u, v) ->
                            val q = p.pagePoint(u, v); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
                        close()
                    }
                    if (texts) page.editableTexts.lastOrNull { InkGeometry.selects(outline(it.placement), region, enclosed) }?.let { selectText(it.id); return }
                    if (photos) page.editableImages.lastOrNull { InkGeometry.selects(outline(it.placement), region, enclosed) }?.let { selectPhoto(it.id); return }
                }
                // Preserve empty-lasso unlock and short-tap Paste after either synchronous or deferred picking.
                if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) && event.flags and MotionEvent.FLAG_CANCELED == 0) {
                    val xy = floatArrayOf(event.getX(event.actionIndex),event.getY(event.actionIndex)); inverse.mapPoints(xy)
                    val point = Point(xy[0],xy[1]); val region = selection.lastRegion; val start = pasteTap
                    val allowPaste = start != null && event.eventTime - pasteTapTime < ViewConfiguration.getLongPressTimeout() &&
                        hypot(event.getX(event.actionIndex)-start[0],event.getY(event.actionIndex)-start[1]) <= ViewConfiguration.get(context).scaledTouchSlop
                    if (selection.isPicking) pendingLassoCompletion = { if (selection.lastRegion === region) finishEmptyLasso(region,point,allowPaste); Unit }
                    else if (finishEmptyLasso(region,point,allowPaste)) return
                }
                publishChange(); return
            }
        }
        // Some firmware starts the alternate stream while an ordinary contact is active.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && active.containsKey(event.getPointerId(0))) {
            switchSegment(event, event.getPointerId(0)); publishChange(); return
        }
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> PointerAction.DOWN
            MotionEvent.ACTION_MOVE -> PointerAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> PointerAction.UP
            MotionEvent.ACTION_CANCEL -> PointerAction.CANCEL
            else -> return
        }
        val packet = PointerPacket(action, event.getPointerId(event.actionIndex), (0 until event.pointerCount).map { Pointer(event.getPointerId(it), tool(event.getToolType(it))) }, event.flags and MotionEvent.FLAG_CANCELED != 0 || fingerTapTaken)
        fingerTapTaken = false
        // B33 (P196): a move that only lengthens wet ink changes nothing the page or the toolbar draws, so it skips their redraw.
        var inkOnly = true; var appended = false; var eraserMovesOnly = true
        val fingerInk = !effectiveLasso && fingerMayWrite(event)
        val routedInputs = router.route(packet, allowFinger = fingerInk)
        routedInputs.forEach { routed ->
            val pointerId = routed.pointerId
            when (routed.action) {
                InkAction.START -> {
                    inkOnly = false; eraserMovesOnly = false
                    requestUnbufferedDispatch(event)
                    if (routed.tool == PointerTool.FINGER) firstFingerHint()
                    val erase = effectiveEraser || routed.tool == PointerTool.ERASER
                    active[pointerId] = startSegment(event, pointerId, erase, repository.session!!.notebook.id, repository.currentPage!!.id)
                }
                InkAction.APPEND -> if (!switchSegment(event, pointerId)) active[pointerId]?.let { pending ->
                    appended = true
                    if (!pending.erase) eraserMovesOnly = false
                    // Held-shape movement only changes its preview (adjustSnap invalidates it).
                    // Keep the toolbar/repository publication for start, snap, tool switch and lift.
                    if (pending.erase || pending.paint) inkOnly = false
                    if (pending.samples.size > 25_000) { inkOnly = false; eraserMovesOnly = false; cancelActive(); failure("Stroke exceeded preview limit; lift the pen and continue.") }
                    else if (pending.snap != null) adjustSnap(event, pointerId, pending, deferred = true)
                    else { capture(event, pointerId, pending); pending.inkId?.let { addToWet(event, pointerId, pending, it) } }
                } else { inkOnly = false; eraserMovesOnly = false }
                InkAction.FINISH -> {
                    inkOnly = false; eraserMovesOnly = false
                    active.remove(pointerId)?.let { pending -> finishSegment(event, pointerId, pending) }
                }
                InkAction.CANCEL -> {
                    inkOnly = false; eraserMovesOnly = false
                    active.remove(pointerId)?.takeIf { it.snap == null }?.inkId?.let { wet.cancelStroke(it, event) }
                    buttonHeld = false
                }
            }
        }
        if (inkOnly && appended && action == PointerAction.MOVE) { scheduleHoldCheck(); return }
        // P587: a move that reaches no ink (a finger turning or moving the page) changes nothing the page or the screen around
        // it shows. Republishing for each one refreshed the whole editor and redrew the page under every frame of a page
        // turn (about 8 ms of the frame on the tablet); the touch's down and lift still publish.
        if (routedInputs.isEmpty() && action == PointerAction.MOVE) return
        // Erasing changes the page preview, but the toolbar and input owner stay unchanged until lift.
        if (eraserMovesOnly && appended && action == PointerAction.MOVE) { scheduleHoldCheck(); dry.invalidate(); return }
        publishChange()
        dry.invalidate()
    }
    private val segment = RectF()
    /** P416: each drawn path's bounds, measured once rather than for every stroke at every eraser sample. */
    private val pathBounds = java.util.WeakHashMap<Path, RectF>()
    private fun boundsOf(path: Path) = pathBounds.getOrPut(path) { RectF().also { path.computeBounds(it, true) } }
    /** B56 (P635): the erased page's strokes, found once per notebook state rather than at every eraser sample. */
    private var sweepNotebook = java.lang.ref.WeakReference<Notebook>(null)
    private var sweepPageId: String? = null
    private var sweepPageStrokes: List<InkStroke> = emptyList()
    private fun sweepStrokes(pageId: String): List<InkStroke> {
        val notebook = repository.session?.notebook
        if (notebook == null || notebook !== sweepNotebook.get() || pageId != sweepPageId) {
            sweepPageStrokes = notebook?.pages?.firstOrNull { it.id == pageId }?.strokes.orEmpty(); sweepNotebook = java.lang.ref.WeakReference(notebook); sweepPageId = pageId
        }
        return sweepPageStrokes
    }
    /** B56 (P635): each stroke's drawn bounds as left, top, right, bottom, built once per stroke list. */
    private var boundsFor: List<InkStroke>? = null
    private var strokeBounds = FloatArray(0)
    private fun sweepBounds(strokes: List<InkStroke>, visible: Map<String, Path>): FloatArray {
        if (boundsFor !== strokes) {
            val b = FloatArray(strokes.size * 4)
            strokes.forEachIndexed { i, s -> val r = boundsOf(visible.getValue(s.id)); b[i * 4] = r.left; b[i * 4 + 1] = r.top; b[i * 4 + 2] = r.right; b[i * 4 + 3] = r.bottom }
            strokeBounds = b; boundsFor = strokes
        }
        return strokeBounds
    }
    private fun capture(event: MotionEvent, id: Int, pending: Pending, includeHistory: Boolean = true) {
        val index = event.findPointerIndex(id)
        if (index < 0) return
        // P568: a finger's reported pressure and shape are noise that differs by device; its line stays even.
        val finger = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
        fun sample(x: Float, y: Float, time: Long, pressure: Float, tilt: Float, orientation: Float) {
            val coords = floatArrayOf(x, y); inverse.mapPoints(coords)
            val relative = (time - pending.started).coerceAtLeast(0)
            if (relative < (pending.samples.lastOrNull()?.timeMs ?: 0)) return
            val value = if (finger) Sample(coords[0], coords[1], relative, FINGER_PRESSURE, 0f, 0f)
                else Sample(coords[0], coords[1], relative, pressure.coerceIn(0f, 1f), tilt, orientation)
            if (value == pending.samples.lastOrNull()) return
            pending.samples.add(value)
            pending.hold?.add(Point(value.x, value.y), time)
            if (pending.erase || pending.paint) {
                val previous = pending.samples.getOrNull(pending.samples.lastIndex - 1)
                val points = listOfNotNull(previous?.let { Point(it.x, it.y) }, Point(value.x, value.y))
                val strokes = sweepStrokes(pending.pageId)
                val visible = visiblePaths(strokes)
                val layer = onActiveLayer(pending.pageId)
                // B03 (P131): only strokes whose bounds reach this segment get the exact test, and the sweep is built only for them.
                // B56 (P635): the bounds test comes first, on plain floats kept for the gesture, before any lookup by id.
                val r = pending.radius
                segment.set(points.minOf { it.x } - r, points.minOf { it.y } - r, points.maxOf { it.x } + r, points.maxOf { it.y } + r)
                val b = sweepBounds(strokes, visible)
                val near = ArrayList<InkStroke>()
                for (i in strokes.indices) {
                    val o = i * 4
                    if (!(segment.left < b[o + 2] && b[o] < segment.right && segment.top < b[o + 3] && b[o + 1] < segment.bottom)) continue
                    val it = strokes[i]
                    if (pending.target.accepts(it) && layer(it) && it.id !in pending.erased && !(pending.paint && it.isTape)) near += it
                }
                if (near.isNotEmpty()) {
                    val area = InkGeometry.previewSweep(EraserSweep(points, pending.radius))
                    near.forEach { if (InkGeometry.intersects(visible.getValue(it.id), area)) { pending.erased.add(it.id); if (pending.paint) paint(pending, it) } }
                }
            }
        }
        if (includeHistory) for (history in 0 until event.historySize) sample(event.getHistoricalX(index, history), event.getHistoricalY(index, history), event.getHistoricalEventTime(history), event.getHistoricalPressure(index, history), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, history), event.getHistoricalOrientation(index, history))
        sample(event.getX(index), event.getY(index), event.eventTime, event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index), event.getOrientation(index))
    }
}
