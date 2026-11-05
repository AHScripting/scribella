package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.view.MotionEvent
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Point
import dev.ownednotes.core.expandedGroups
import dev.ownednotes.core.selectionGroup
import dev.ownednotes.core.ObjectIds
import dev.ownednotes.core.captureObjects
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Transient whole-stroke selection. Only completed document operations are persisted. */
class InkSelection(private val repository: NotebookRepository, private val failure: (String) -> Unit) {
    enum class Shape { FREEFORM, RECTANGLE }
    enum class Rule { TOUCHING, FULLY_ENCLOSED }
    /** F043 (P087): which kind of object the lasso picks up. */
    enum class Filter(val label: String) { ALL("All"), INK("Ink"), HIGHLIGHTER("Highlighter"), TEXT("Text"), IMAGES("Images"), SHAPES("Shapes") }
    data class Options(val shape: Shape = Shape.FREEFORM, val rule: Rule = Rule.TOUCHING, val filter: Filter = Filter.ALL)
    /** The area of the last completed lasso, for picking text boxes or images with it. */
    var lastRegion: Path? = null; private set
    var options = Options(); private set
    fun configure(value: Options) { check(!isEditing); clear(); options = value }
    private companion object {
        val picker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() },
                "notes-lasso-picker").apply { isDaemon = true }
        }
        val pickerMain = android.os.Handler(android.os.Looper.getMainLooper())
    }
    private var pickGeneration = 0L
    private var pickJob: java.util.concurrent.Future<*>? = null
    val isPicking get() = pickJob != null
    var onPicked: (() -> Unit)? = null
    private fun cancelPicking() { ++pickGeneration; pickJob?.cancel(true); pickJob = null }
    /** Private path copies keep Skia's lazy caches off the UI-owned paths. Only the current gesture may publish. */
    private fun pick(paths: Map<String, Path>, region: Path) {
        val enclosed = options.rule == Rule.FULLY_ENCLOSED
        if (paths.size < 512) {
            selected = withGroups(paths.filterValues { InkGeometry.selects(it, region, enclosed) }.keys.toSet()).intersect(paths.keys)
            return
        }
        cancelPicking()
        val ticket = pickGeneration; val notebook = baseline; val owner = pageId
        val privatePaths = paths.mapValues { (_, path) -> Path(path) }; val privateRegion = Path(region)
        val allowed = privatePaths.keys.toSet()
        pickJob = picker.submit {
            val result = runCatching { buildSet {
                for ((id,path) in privatePaths) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    if (InkGeometry.selects(path,privateRegion,enclosed)) add(id)
                }
            } }
            pickerMain.post {
                if (ticket != pickGeneration) return@post
                pickJob = null
                if (repository.explicitReadOnly || repository.session?.notebook !== notebook || repository.selectedPageId != owner) {
                    clear(); return@post
                }
                result.onSuccess { selected = withGroups(it).intersect(allowed) }
                    .onFailure { failure("Selection failed: ${it.message}") }
                onPicked?.invoke()
            }
        }
    }
    private var baseline: Notebook? = null
    private var pageId: String? = null
    private var selected = emptySet<String>()
    private data class Gesture(val pointer: Int, val moving: Boolean, val start: Point, var last: Point, val points: MutableList<Point>,
        val handle: Int? = null, val handles: SelectionHandles? = null, var scale: Float = 1f, var scaleY: Float = 1f,
        var degrees: Float = 0f, var rawDegrees: Float = 0f, var lastAngle: Float = 0f, var alignment: ObjectMoveGuides? = null)
    private var gesture: Gesture? = null
    internal val moveGuides get() = run { sync(); gesture?.alignment?.lines.orEmpty() }
    private data class ShapeGesture(val pointer: Int, val stroke: dev.ownednotes.core.InkStroke, val model: dev.ownednotes.core.ShapePoints, val index: Int, val start: Point, var path: List<Point>? = null, var controls: List<Point> = model.handles, var preview: Path? = null)
    private var shapeGesture: ShapeGesture? = null
    var reshapeStroke: ((dev.ownednotes.core.InkStroke, List<Point>) -> dev.ownednotes.core.InkStroke)? = null
    private var shapeSource: dev.ownednotes.core.InkStroke? = null
    private var shapeModel: dev.ownednotes.core.ShapePoints? = null
    private fun selectedShape(): Pair<dev.ownednotes.core.InkStroke, dev.ownednotes.core.ShapePoints>? {
        if (selected.size != 1) return null
        val stroke = repository.currentPage?.strokes?.singleOrNull { it.id in selected } ?: return null
        if (stroke !== shapeSource) { shapeSource=stroke; shapeModel=dev.ownednotes.core.ShapePoints.from(stroke) }
        return shapeModel?.let { stroke to it }
    }
    val pointHandles: List<Point> get() { sync(); return shapeGesture?.controls ?: selectedShape()?.second?.handles.orEmpty() }
    fun pointHit(p: Point, unit: Float): Int? {
        val points=pointHandles
        return points.indices.minByOrNull { hypot(points[it].x-p.x,points[it].y-p.y) }?.takeIf { hypot(points[it].x-p.x,points[it].y-p.y)<=12*unit }
    }
    val shapePreview: Pair<String,Path>? get() = shapeGesture?.let { g -> g.preview?.let { g.stroke.id to it } }
    val isEditing get() = gesture != null || shapeGesture != null
    val ids: Set<String> get() { sync(); return selected }
    internal val objectIds get() = repository.currentPage?.objectIds(ids) ?: ObjectIds()
    val hasObjects get() = objectIds.let { it.images.isNotEmpty() || it.texts.isNotEmpty() }
    data class ColorTarget(val notebook: Notebook, val pageId: String, val ids: Set<String>, val initial: Long)
    fun colorTarget(): ColorTarget? {
        sync()
        if (isEditing || selected.isEmpty() || hasObjects) return null
        val snapshot = baseline ?: return null
        val owner = pageId ?: return null
        val initial = repository.currentPage?.strokes?.firstOrNull { it.id in selected }?.color ?: return null
        return ColorTarget(snapshot, owner, selected.toSet(), initial)
    }
    fun recolor(target: ColorTarget, color: Long): Boolean {
        sync()
        if (isEditing || !repository.canChangeNotebook || baseline !== target.notebook || pageId != target.pageId ||
            selected != target.ids || selected.isEmpty()) return false
        repository.edit { it.recolorStrokes(target.ids, target.pageId, color) }
        baseline = repository.session?.notebook; pageId = target.pageId; selected = target.ids
        return true
    }
    /** F017: [restroke] regenerates one stroke's outlines at the new width; the whole selection is one Undo step. */
    fun rewidth(target: ColorTarget, width: Float, restroke: (dev.ownednotes.core.InkStroke) -> dev.ownednotes.core.InkStroke): Boolean {
        sync()
        if (isEditing || !repository.canChangeNotebook || baseline !== target.notebook || pageId != target.pageId ||
            selected != target.ids || selected.isEmpty()) return false
        val page = target.notebook.pages.firstOrNull { it.id == target.pageId } ?: return false
        val replacements = page.strokes.filter { it.id in target.ids && it.width != width }.map(restroke)
        repository.edit { it.rewidthStrokes(target.pageId, replacements) }
        baseline = repository.session?.notebook; pageId = target.pageId; selected = target.ids
        return true
    }
    /** B27 (P192): what the Style pop-out changes; null leaves that property of each stroke as it is. */
    data class Restyle(val kind: PenKind? = null, val color: Long? = null, val width: Float? = null, val fillChanged: Boolean = false, val fillColor: Long? = null) {
        val isEmpty get() = kind == null && color == null && width == null && !fillChanged
    }
    /** B27 (P192): [restyled] gives one stroke's new colour, width, brush and outlines; the whole selection is one Undo step. */
    fun restyle(target: ColorTarget, restyled: (dev.ownednotes.core.InkStroke) -> dev.ownednotes.core.InkStroke): Boolean {
        sync()
        if (isEditing || !repository.canChangeNotebook || baseline !== target.notebook || pageId != target.pageId ||
            selected != target.ids || selected.isEmpty()) return false
        val page = target.notebook.pages.firstOrNull { it.id == target.pageId } ?: return false
        val replacements = page.strokes.filter { it.id in target.ids }.mapNotNull { old -> restyled(old).takeIf { it != old } }
        repository.edit { it.restyleStrokes(target.pageId, replacements) }
        baseline = repository.session?.notebook; pageId = target.pageId; selected = target.ids
        return true
    }
    /**
     * P413 (auto-refine R1): puts the tidied [replacements] on the page. [shown] is a refine of [target] still on screen:
     * it is taken back in the same edit, so whichever strength shows is one Undo step from the writing as written.
     * False when the selection moved on.
     */
    fun refine(target: ColorTarget, replacements: List<dev.ownednotes.core.InkStroke>, shown: Notebook? = null): Boolean {
        sync()
        val from = shown ?: target.notebook
        if (isEditing || !repository.canChangeNotebook || baseline !== from || repository.session?.notebook !== from ||
            pageId != target.pageId || selected != target.ids || selected.isEmpty()) return false
        var applied = false
        // The edit redraws the editor before it returns; the selection holds still meanwhile, so the bar and the
        // Refine… pop-out stay open.
        holding = true
        try {
            repository.edit { session ->
                if (shown != null) { session.undo(); if (session.notebook !== target.notebook) { session.redo(); return@edit false } }
                applied = true
                (replacements.isNotEmpty() && session.refineStrokes(target.pageId, replacements)) || shown != null
            }
        } finally { holding = false }
        baseline = repository.session?.notebook; pageId = target.pageId; selected = target.ids
        return applied
    }
    /** P441: selected current-hand rewriting replaces word IDs and remains one guarded Undo step. */
    fun rewriteHandwriting(target: ColorTarget, groups: List<Pair<List<dev.ownednotes.core.InkStroke>, List<dev.ownednotes.core.InkStroke>>>): Boolean {
        sync()
        if (isEditing || !repository.canChangeNotebook || baseline !== target.notebook ||
            repository.session?.notebook !== target.notebook || pageId != target.pageId || selected != target.ids ||
            groups.isEmpty() || groups.any { pair -> pair.first.any { it.id !in target.ids } }) return false
        var applied = false
        holding = true
        try { repository.edit { session -> session.rewriteHandwriting(target.pageId, groups).also { applied = it } } }
        finally { holding = false }
        if (applied) {
            baseline = repository.session?.notebook; pageId = target.pageId
            selected = (target.ids - groups.flatMap { it.first }.map { it.id }.toSet()) + groups.flatMap { it.second }.map { it.id }
        }
        return applied
    }
    val offset: Point get() = gesture?.takeIf { it.moving && it.handle == null }?.let { Point(it.last.x - it.start.x, it.last.y - it.start.y) } ?: Point(0f, 0f)
    val previewMatrix: Matrix get() = Matrix().apply {
        gesture?.takeIf { it.handle != null }?.let {
            val pivot = it.handles!!.pivot(it.handle!!)
            setScale(it.scale, it.scaleY, pivot.x, pivot.y)
            postRotate(it.degrees, pivot.x, pivot.y)
        }
    }
    private val accent = Color.rgb(30, 100, 220)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 10, 20, 35) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 30, 100, 220) }
    // G6 (P181): the selection pill uses the Glass accent, like the active chip on the toolbar.
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; alpha = 235 }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Tokens.ON_INK; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private var dashUnit = 0f
    /** Dashes are sized in screen units so the outline reads the same at every zoom. */
    private fun outline(unit: Float): Paint = outlinePaint.apply {
        if (unit != dashUnit) { dashUnit = unit; pathEffect = DashPathEffect(floatArrayOf(6 * unit, 4 * unit), 0f) }
        strokeWidth = 1.25f * unit
    }
    private var holding = false
    fun sync() {
        if (holding) return
        if (repository.explicitReadOnly || baseline !== repository.session?.notebook || pageId != repository.selectedPageId) clear()
    }
    fun clear() { cancelPicking(); boundsPaths = null; boundsIds = null; boundsNotebook = null; boundsValue = null; selected = emptySet(); baseline = null; pageId = null; gesture = null; shapeGesture = null }
    fun cancelGesture() { cancelPicking(); gesture = null; shapeGesture = null }
    /** P075: select [ids] on the current page, as if a lasso had just closed around them. */
    fun select(ids: Set<String>, allowed: Set<String>? = null) { check(!isEditing); cancelPicking(); baseline = repository.session?.notebook; pageId = repository.selectedPageId; selected = withGroups(ids).let { if (allowed == null) it else it.intersect(allowed) } }
    /** P098 (F050): touching one member of a group picks up the whole group. */
    private fun withGroups(ids: Set<String>) = repository.currentPage?.let { page ->
        val expanded = page.expandedGroups(page.objectIds(ids))
        // P548 (F096): a table takes the handwriting in its cells along with it.
        val groups = page.strokes.filter { it.id in expanded.strokes }.mapNotNull { it.group }.toSet()
        expanded.copy(strokes = expanded.strokes + groups.flatMap { dev.ownednotes.core.TableGrid.cellInk(page, it) }).selectionKeys()
    } ?: ids
    val isOneGroup: Boolean get() = oneGroup != null
    val oneGroup: String? get() = repository.currentPage?.let { dev.ownednotes.core.TableGrid.selectionGroup(it, objectIds) }
    /** One persistent group across selected editable objects, with one Undo step. */
    fun toggleGroup(): Boolean {
        check(!isEditing)
        sync(); val owner = pageId ?: return false
        val retained = selected
        if (retained.size < 2) return false
        val parts = objectIds
        val grouped = isOneGroup
        var changed = false
        repository.edit { changed = if (grouped) it.ungroupObjects(owner, parts) else it.groupObjects(owner, parts) != null; changed }
        baseline = repository.session?.notebook; pageId = owner; selected = retained
        return changed
    }
    // InkCanvas owns immutable path maps: changed ink/objects publish a new map. The document and ID identities
    // also guard this cache, so Undo, transforms, selection changes and page switches cannot reuse old bounds.
    private var boundsPaths: Map<String, Path>? = null
    private var boundsIds: Set<String>? = null
    private var boundsNotebook: Notebook? = null
    private var boundsValue: RectF? = null
    private fun bounds(paths: Map<String, Path>): RectF? {
        if (boundsPaths === paths && boundsIds === selected && boundsNotebook === baseline) return boundsValue?.let(::RectF)
        var result: RectF? = null
        selected.forEach { id -> paths[id]?.takeUnless { it.isEmpty }?.let { path ->
            val box = RectF(); path.computeBounds(box, true)
            if (result == null) result = box else result!!.union(box)
        } }
        boundsPaths = paths; boundsIds = selected; boundsNotebook = baseline; boundsValue = result
        return result?.let(::RectF)
    }
    fun handles(paths: Map<String, Path>, unit: Float, minY: Float): SelectionHandles? {
        sync(); return bounds(paths)?.let { SelectionHandles(it, unit, minY) }
    }
    fun selectedBounds(paths: Map<String, Path>, unit: Float, minY: Float): RectF? {
        sync()
        return handles(paths, unit, minY)?.let { layout -> RectF(layout.box).apply {
            union(layout.points[8].x - 7 * unit, layout.points[8].y - 7 * unit, layout.points[8].x + 7 * unit, layout.points[8].y + 7 * unit)
            inset(-7 * unit, -7 * unit); previewMatrix.mapRect(this)
            val delta = offset; offset(delta.x, delta.y)
        } }
    }
    fun draw(canvas: Canvas, paths: Map<String, Path>, unit: Float, minY: Float, editable: Boolean) {
        sync()
        (gesture?.handles ?: handles(paths, unit, minY))?.let { layout ->
            canvas.save(); val delta = offset; canvas.translate(delta.x, delta.y)
            val transform = previewMatrix
            val boundary = Path().apply { addRect(layout.box, Path.Direction.CW); transform(transform) }
            canvas.drawPath(boundary, outline(unit))
            if (editable) {
                handlePaint.style = Paint.Style.STROKE; handlePaint.strokeWidth = 1.5f * unit
                val line = layout.rotationStem(transform)
                canvas.drawLine(line[0], line[1], line[2], line[3], handlePaint)
                val corners = pointHandles
                layout.points.forEachIndexed { index, point ->
                    if (corners.isNotEmpty() && index != 8) return@forEachIndexed
                    val radius = (if (index == 8) 8 else 5) * unit
                    val xy = floatArrayOf(point.x, point.y); transform.mapPoints(xy)
                    if (index == 8) { xy[0] = line[2]; xy[1] = line[3] }
                    canvas.drawCircle(xy[0], xy[1] + unit, radius + 1.5f * unit, handleShadow)
                    if (index == 8) rotateHandle(canvas, xy[0], xy[1], radius, unit) else {
                        canvas.drawCircle(xy[0], xy[1], radius, handleFill)
                        canvas.drawCircle(xy[0], xy[1], radius, handlePaint)
                    }
                }
                corners.forEach { point ->
                    val xy=floatArrayOf(point.x,point.y);transform.mapPoints(xy)
                    canvas.drawCircle(xy[0], xy[1] + unit, 6.5f * unit, handleShadow)
                    canvas.drawCircle(xy[0], xy[1], 5 * unit, handleFill)
                    canvas.drawCircle(xy[0], xy[1], 5 * unit, handlePaint)
                }
                gesture?.takeIf { it.handle != null }?.let { current ->
                    // The label sits just outside the dragged handle, away from the selection, so the pen never covers it.
                    val index = current.handle!!
                    val centre = floatArrayOf(layout.box.centerX(), layout.box.centerY()).also { transform.mapPoints(it) }
                    val anchor = if (index == 8) floatArrayOf(line[2], line[3]) else floatArrayOf(layout.points[index].x, layout.points[index].y).also { transform.mapPoints(it) }
                    val dx = anchor[0] - centre[0]; val dy = anchor[1] - centre[1]
                    val length = hypot(dx, dy).takeIf { it > 0f } ?: 1f
                    pill(canvas, SelectionHandles.readout(index, current.scale, current.scaleY, current.degrees),
                        anchor[0] + dx / length * 30 * unit, anchor[1] + dy / length * 30 * unit, unit)
                }
            }
            canvas.restore()
        }
        if (isPicking) lastRegion?.let { canvas.drawPath(it,lassoFill); canvas.drawPath(it,outline(unit)) }
        gesture?.takeUnless { it.moving }?.let { current ->
            if (options.shape == Shape.RECTANGLE || current.points.size >= 3) canvas.drawPath(area(current), lassoFill)
            canvas.drawPath(area(current, close = false), outline(unit))
        }
    }
    /** A filled accent disc with a three-quarter arrow, so the rotate handle reads differently from the resize dots. */
    private fun rotateHandle(canvas: Canvas, x: Float, y: Float, radius: Float, unit: Float) {
        handlePaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, radius, handlePaint)
        handlePaint.style = Paint.Style.STROKE
        val arc = radius * .48f
        glyphPaint.style = Paint.Style.STROKE; glyphPaint.strokeWidth = 1.4f * unit
        canvas.drawArc(x - arc, y - arc, x + arc, y + arc, -60f, 270f, false, glyphPaint)
        // Arrowhead at the arc's end, pointing along the clockwise turn.
        val end = Math.toRadians(210.0)
        val ex = x + arc * cos(end).toFloat(); val ey = y + arc * sin(end).toFloat()
        val tx = -sin(end).toFloat(); val ty = cos(end).toFloat()
        val nx = cos(end).toFloat(); val ny = sin(end).toFloat()
        val head = 2.6f * unit
        glyphPaint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(ex + tx * head, ey + ty * head)
            lineTo(ex + nx * head, ey + ny * head); lineTo(ex - nx * head, ey - ny * head); close()
        }, glyphPaint)
    }
    /** A small ink label centred on ([x], [y]) in page units, sized for the screen through [unit]. */
    private fun pill(canvas: Canvas, label: String, x: Float, y: Float, unit: Float) {
        pillText.textSize = 12 * unit
        val half = pillText.measureText(label) / 2 + 8 * unit
        canvas.drawRoundRect(x - half, y - 11 * unit, x + half, y + 11 * unit, 11 * unit, 11 * unit, pillPaint)
        canvas.drawText(label, x, y - (pillText.ascent() + pillText.descent()) / 2, pillText)
    }
    private fun area(current: Gesture, close: Boolean = true): Path = Path().apply {
        if (options.shape == Shape.RECTANGLE) {
            addRect(minOf(current.start.x, current.last.x), minOf(current.start.y, current.last.y),
                maxOf(current.start.x, current.last.x), maxOf(current.start.y, current.last.y), Path.Direction.CW)
        } else {
            fillType = Path.FillType.EVEN_ODD
            current.points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
            if (close) close()
        }
    }
    /** P568: in finger writing, one finger draws the lasso and moves the selection as a pen does. */
    var allowFinger = false
    fun contact(event: MotionEvent, paths: Map<String, Path>, unit: Float, minY: Float, map: (Float, Float) -> Point) {
        sync()
        shapeGesture?.let { current ->
            if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0) { shapeGesture=null; return }
            val index=event.findPointerIndex(current.pointer)
            if(index<0) { shapeGesture=null; return }
            if(event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                if(event.actionMasked != MotionEvent.ACTION_MOVE && event.getPointerId(event.actionIndex)!=current.pointer) return
                val target=map(event.getX(index),event.getY(index))
                current.model.move(current.index,target)?.let { path ->
                    current.path=path
                    current.preview=Path().apply { path.forEachIndexed { i,p -> if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) } }
                    current.controls=dev.ownednotes.core.ShapePoints.from(current.stroke.copy(samples=path.mapIndexed { i,p -> dev.ownednotes.core.Sample(p.x,p.y,i*4L,.5f) }))?.handles ?: current.model.handles
                }
                if(event.actionMasked != MotionEvent.ACTION_MOVE) {
                    shapeGesture=null
                    val owner=pageId ?: return
                    val retained=selected
                    if(hypot(target.x-current.start.x,target.y-current.start.y)>.01f) current.path?.let { path ->
                        runCatching { val next=reshapeStroke!!(current.stroke,path);repository.edit { it.replaceStroke(current.stroke.id,next,owner) } }
                            .onSuccess { baseline=repository.session?.notebook;pageId=owner;selected=retained }
                            .onFailure { failure("Shape edit failed: ${it.message}") }
                    }
                }
            }
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val tool = event.getToolType(event.actionIndex)
                if (gesture != null || (tool != MotionEvent.TOOL_TYPE_STYLUS && !(allowFinger && tool == MotionEvent.TOOL_TYPE_FINGER))) return
                cancelPicking()
                val point = map(event.getX(event.actionIndex), event.getY(event.actionIndex))
                val shape=selectedShape()
                val pointHandle=if(reshapeStroke!=null && !repository.readOnly) pointHit(point,unit) else null
                if(shape!=null && pointHandle!=null) {
                    baseline=repository.session?.notebook;pageId=repository.selectedPageId
                    shapeGesture=ShapeGesture(event.getPointerId(event.actionIndex),shape.first,shape.second,pointHandle,point)
                    lastRegion=null;return
                }
                val layout = handles(paths, unit, minY)
                val handle = layout?.hit(point)?.takeIf { pointHandles.isEmpty() || it == 8 }
                val moving = handle != null || layout?.box?.contains(point.x, point.y) == true
                if (!moving) selected = emptySet()
                lastRegion = null
                baseline = repository.session?.notebook; pageId = repository.selectedPageId
                gesture = Gesture(event.getPointerId(event.actionIndex), moving, point, point, mutableListOf(point), handle, layout.takeIf { moving }).apply {
                    if (handle == 8) lastAngle = angle(point, layout!!.pivot(8))
                    if (moving && handle == null) alignment = repository.currentPage?.let { page -> bounds(paths)?.let { ObjectMoveGuides(page, it, objectIds, unit) } }
                }
            }
            MotionEvent.ACTION_MOVE -> append(event, map)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val current = gesture ?: return
                if (event.getPointerId(event.actionIndex) != current.pointer) return
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) { cancelGesture(); return }
                append(event, map)
                if (gesture == null) return
                gesture = null
                val owner = pageId ?: return
                if (current.handle != null) {
                    val retained = selected
                    runCatching { repository.edit { objectIds.let { ids -> it.transformStrokes(ids.strokes, owner, current.handles!!.pivot(current.handle), current.scale, current.degrees, current.scaleY,ids.images,ids.texts) } } }
                        .onSuccess { baseline = repository.session?.notebook; pageId = owner; selected = retained }
                        .onFailure { failure("Selection transform failed: ${it.message}") }
                } else if (current.moving) {
                    val retained = selected
                    runCatching { repository.edit { objectIds.let { ids -> it.moveStrokes(ids.strokes, owner, current.last.x - current.start.x, current.last.y - current.start.y,ids.images,ids.texts) } } }
                        .onSuccess { baseline = repository.session?.notebook; pageId = owner; selected = retained }
                        .onFailure { failure("Selection move failed: ${it.message}") }
                } else if (options.shape == Shape.RECTANGLE || current.points.size >= 3) {
                    val region = area(current)
                    lastRegion = region
                    pick(paths,region)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
    }
    private fun append(event: MotionEvent, map: (Float, Float) -> Point) {
        val current = gesture ?: return
        val index = event.findPointerIndex(current.pointer)
        if (index < 0) return
        fun point(x: Float, y: Float) {
            val value = map(x, y)
            current.last = if (current.moving && current.handle == null) current.alignment?.move(value.x-current.start.x, value.y-current.start.y)?.let {
                Point(current.start.x+it.x, current.start.y+it.y)
            } ?: value else value
            current.handle?.let { index ->
                val layout = current.handles!!
                if (index == 8) {
                    val next = angle(value, layout.pivot(8))
                    var change = next - current.lastAngle
                    if (change > 180) change -= 360 else if (change < -180) change += 360
                    current.rawDegrees += change; current.lastAngle = next
                    current.degrees = SelectionHandles.snapped(current.rawDegrees)
                } else {
                    val factor = layout.scale(index, current.start, value)
                    current.scale = if (index in listOf(1, 5)) 1f else factor
                    current.scaleY = if (index in listOf(3, 7)) 1f else factor
                }
            }
            if (!current.moving && options.shape == Shape.FREEFORM && current.points.lastOrNull() != value) current.points.add(value)
        }
        if (!current.moving && options.shape == Shape.FREEFORM) for (history in 0 until event.historySize) point(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
        point(event.getX(index), event.getY(index))
        if (current.points.size > 5000) { cancelGesture(); failure("Lasso exceeded preview limit; draw a shorter loop") }
    }
    private fun angle(point: Point, pivot: Point): Float = Math.toDegrees(atan2((point.y - pivot.y).toDouble(), (point.x - pivot.x).toDouble())).toFloat()
    fun delete() {
        sync(); val owner = pageId ?: return
        val retained = selected
        val parts=objectIds
        repository.edit { if (hasObjects) it.removeObjects(owner,parts) else it.remove(retained, owner) }
        clear()
    }
    fun duplicate() {
        sync(); val owner = pageId ?: return
        val retained = selected
        var copies = emptySet<String>()
        val parts=objectIds
        val bundle=if(hasObjects) repository.session!!.notebook.captureObjects(owner,parts) else null
        repository.edit { copies = if(bundle!=null) it.pasteObjects(bundle,owner,24f,24f).selectionKeys() else it.duplicateStrokes(retained, owner); copies.isNotEmpty() }
        baseline = repository.session?.notebook; pageId = owner; selected = copies
    }
    fun transform(paths: Map<String, Path>, scale: Float, degrees: Float) {
        check(!isEditing)
        sync(); val owner = pageId ?: return
        val box = bounds(paths) ?: return
        val retained = selected
        val parts=objectIds
        repository.edit { it.transformStrokes(parts.strokes, owner, Point(box.centerX(), box.centerY()), scale, degrees,imageIds=parts.images,textIds=parts.texts) }
        baseline = repository.session?.notebook; pageId = owner; selected = retained
    }
    fun copyMixed(clipboard: MixedClipboard, paths: Map<String,Path>, cut: Boolean): Boolean {
        sync();val owner=pageId ?: return false
        val box=bounds(paths) ?: return false
        if(isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        val parts=objectIds
        val source=repository.session!!.notebook
        val bundle=source.captureObjects(owner,parts)
        val audio=dev.ownednotes.core.LinkedAudioSelection.capture(source,bundle.strokes)
        if(cut) {
            var removed=false;repository.edit { removed=it.removeObjects(owner,parts);removed }
            if(!removed) return false
        }
        clipboard.capture(MixedClipboard.Content(bundle,Point(box.centerX(),box.centerY()),box.width(),box.height(),audio))
        if(cut) clear()
        return true
    }
    fun pasteMixed(clipboard: MixedClipboard, at: Point? = null): Boolean {
        val content=clipboard.content ?: return false;val page=repository.currentPage ?: return false
        if(isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        val origin=pasteOrigin(at,content.width,content.height,page.width,page.height)
        val dx=origin.x+content.width/2-content.centre.x;val dy=origin.y+content.height/2-content.centre.y
        var pasted=emptySet<String>()
        repository.edit { pasted=it.pasteObjects(content.bundle,page.id,dx,dy,content.audio).selectionKeys();pasted.isNotEmpty() }
        baseline=repository.session?.notebook;pageId=page.id;selected=pasted
        return pasted.isNotEmpty()
    }
    fun copy(clipboard: InkClipboard, paths: Map<String, Path>, cut: Boolean): Boolean {
        check(!isEditing)
        sync(); val owner = pageId ?: return false
        val box = bounds(paths) ?: return false
        val strokes = repository.currentPage?.strokes.orEmpty().filter { it.id in selected }
        if (strokes.isEmpty()) return false
        val audio=dev.ownednotes.core.LinkedAudioSelection.capture(repository.session!!.notebook,strokes)
        if (cut) {
            val retained = selected
            var removed = false
            repository.edit { removed = it.remove(retained, owner); removed }
            if (!removed) return false
        }
        clipboard.capture(strokes, Point(box.centerX(), box.centerY()),audio)
        if (cut) clear()
        return true
    }
    fun paste(clipboard: InkClipboard, at: Point? = null): Boolean {
        check(!isEditing)
        val content = clipboard.content ?: return false
        val page = repository.currentPage ?: return false
        var dx = page.width / 2 - content.centre.x; var dy = page.height / 2 - content.centre.y
        // B35 (P198): centre the ink on the chosen spot, kept on the page.
        val points = if (at == null) emptyList() else content.strokes.flatMap { it.outlines.flatten() }
        if (points.isNotEmpty()) {
            val left = points.minOf { it.x }; val top = points.minOf { it.y }
            val origin = pasteOrigin(at, points.maxOf { it.x } - left, points.maxOf { it.y } - top, page.width, page.height)
            dx = origin.x - left; dy = origin.y - top
        }
        var copies = emptySet<String>()
        repository.edit {
            copies = it.pasteStrokes(content.strokes, page.id, dx, dy,content.audio)
            copies.isNotEmpty()
        }
        if (copies.isEmpty()) return false
        baseline = repository.session?.notebook; pageId = page.id; selected = copies
        return true
    }
}
