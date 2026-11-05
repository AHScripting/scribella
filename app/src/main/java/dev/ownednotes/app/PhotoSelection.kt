package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import dev.ownednotes.core.*
import kotlin.math.*

/** Process-local photo clipboard retains immutable original bytes, never a rendered bitmap. */
class PhotoClipboard {
    data class Content(val asset: ImageAsset, val image: ImageObject)
    var content: Content? = null; private set
    fun capture(asset: ImageAsset, image: ImageObject) { content = Content(asset, image) }
    fun clear() { content = null }
}

/** Single-photo selection. A drag changes only the preview until its owning stylus is released. */
internal class PhotoSelection(private val repository: NotebookRepository, private val failure: (String) -> Unit) {
    private var baseline: Notebook? = null
    private var pageId: String? = null
    private var selected: String? = null
    private data class Drag(val pointer: Int, val start: Point, val image: ImageObject, val handle: Int?,
        val frame: ImagePlacement, var preview: ImagePlacement, var lastAngle: Float = 0f, var rotation: Float = 0f, var alignment: ObjectMoveGuides? = null)
    private var drag: Drag? = null
    val moveGuides get() = run { sync(); drag?.alignment?.lines.orEmpty() }
    val isEditing get() = drag != null
    val id: String? get() { sync(); return selected }
    private fun current(): ImageObject? = repository.currentPage?.editableImages?.firstOrNull { it.id == selected && !it.locked }
    private fun sync() {
        if ((repository.readOnly && !repository.savingOriginal) || baseline !== repository.session?.notebook || pageId != repository.selectedPageId) clear()
    }
    fun clear() { drag = null; selected = null; baseline = null; pageId = null }
    fun cancelGesture() { drag = null }
    fun select(id: String): Boolean {
        check(!isEditing)
        if ((repository.readOnly && !repository.savingOriginal) || repository.currentPage?.editableImages?.none { it.id == id && !it.locked } != false) return false
        baseline = repository.session?.notebook; pageId = repository.selectedPageId; selected = id
        return true
    }
    fun preview(image: ImageObject): ImageObject {
        sync()
        return if (image.id == selected && drag != null) image.copy(placement = drag!!.preview) else image
    }
    private val anchors = listOf(0f to 0f, .5f to 0f, 1f to 0f, 1f to .5f,
        1f to 1f, .5f to 1f, 0f to 1f, 0f to .5f)
    /** Expanded only for tiny images; keeps nine distinct, zoom-independent hit targets. */
    private fun frame(p: ImagePlacement, unit: Float): ImagePlacement {
        val w = maxOf(p.width, 72 * unit); val h = maxOf(p.height, 72 * unit)
        return runCatching { ImagePlacement(p.x + (p.width - w) / 2, p.y + (p.height - h) / 2, w, h, p.degrees) }.getOrDefault(p)
    }
    private fun layout(p: ImagePlacement, unit: Float, viewport: RectF): List<Point> {
        val f = frame(p, unit)
        val points = anchors.map { f.pagePoint(it.first, it.second) }
        val centre = f.pagePoint(.5f, .5f)
        // Prefer above; use another edge if its fixed-length stem would leave the editor.
        val candidates = listOf(1, 5, 3, 7).map { i ->
            val base = points[i]; val dx = base.x - centre.x; val dy = base.y - centre.y
            val length = hypot(dx, dy)
            i to Point(base.x + dx / length * 32 * unit, base.y + dy / length * 32 * unit)
        }
        val safe = RectF(viewport).apply { inset(9 * unit, 9 * unit) }
        val chosen = candidates.firstOrNull { safe.contains(it.second.x, it.second.y) } ?: candidates.minBy { (_, tip) ->
            abs(tip.x - tip.x.coerceIn(viewport.left, viewport.right)) + abs(tip.y - tip.y.coerceIn(viewport.top, viewport.bottom))
        }
        stemBase = points[chosen.first]
        return points + chosen.second
    }
    private var stemBase = Point(0f, 0f)
    fun points(unit: Float, viewport: RectF): List<Point> {
        sync(); val image = current() ?: return emptyList()
        return layout(drag?.preview ?: image.placement, unit, viewport)
    }
    fun bounds(unit: Float, viewport: RectF): RectF? {
        val points = points(unit, viewport)
        if (points.isEmpty()) return null
        return RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }).apply { inset(-9 * unit, -9 * unit) }
    }
    fun hit(point: Point, unit: Float, viewport: RectF): Int? = points(unit, viewport).mapIndexed { i, p -> i to hypot(p.x-point.x,p.y-point.y) }
        .filter { it.second <= 12 * unit }.minByOrNull { it.second }?.first
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30,100,220); style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    fun draw(canvas: Canvas, unit: Float, viewport: RectF, editable: Boolean) {
        val p = points(unit, viewport); if (p.isEmpty()) return
        line.strokeWidth = 1.5f * unit
        canvas.drawPath(Path().apply { moveTo(p[0].x,p[0].y); listOf(2,4,6).forEach { lineTo(p[it].x,p[it].y) }; close() },line)
        if (!editable) return
        canvas.drawLine(stemBase.x,stemBase.y,p[8].x,p[8].y,line)
        p.forEachIndexed { i, point ->
            val radius = (if (i == 8) 8 else 5) * unit
            canvas.drawCircle(point.x,point.y,radius,fill); canvas.drawCircle(point.x,point.y,radius,line)
            if (i == 8) canvas.drawArc(point.x-4*unit,point.y-4*unit,point.x+4*unit,point.y+4*unit,-60f,270f,false,line)
        }
    }
    private fun angle(p: Point, centre: Point) = Math.toDegrees(atan2((p.y-centre.y).toDouble(),(p.x-centre.x).toDouble())).toFloat()
    /** Returns true only for a photo gesture; other lasso contacts remain handwriting selection. */
    fun contact(event: MotionEvent, unit: Float, viewport: RectF, map: (Float,Float)->Point): Boolean =
        contact(event, unit, viewport, map, allowFinger = false)
    fun contact(event: MotionEvent, unit: Float, viewport: RectF, map: (Float,Float)->Point, allowFinger: Boolean): Boolean {
        sync()
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (drag != null) return true
                val index = event.actionIndex
                val finger = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER && allowFinger
                if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS && !finger) return false
                val point = map(event.getX(index),event.getY(index))
                val handle = hit(point,unit,viewport)
                val image = if (handle != null) current() else current()?.takeIf { it.placement.contains(point) }
                    ?: if (finger) null else repository.currentPage?.editableImages?.lastOrNull { !it.locked && it.placement.contains(point) }
                if (image == null) { clear(); return false }
                select(image.id)
                drag = Drag(event.getPointerId(index),point,image,handle,frame(image.placement,unit),image.placement,
                    angle(point,image.placement.pagePoint(.5f,.5f))).also { d ->
                    if (handle == null) d.alignment = repository.currentPage?.let { ObjectMoveGuides(it, ObjectMoveGuides.bounds(image.placement), ObjectIds(images = setOf(image.id)), unit) }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> { if (drag == null) return false; append(event,map); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val d = drag ?: return false
                if (event.getPointerId(event.actionIndex) != d.pointer) return true
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) { cancelGesture(); return true }
                append(event,map); drag = null
                val snapshot = baseline; val owner = pageId
                if (snapshot !== repository.session?.notebook || owner != repository.selectedPageId || repository.readOnly || repository.busy || repository.fileBusy) { clear(); return true }
                if (d.preview != d.image.placement) runCatching { repository.edit { it.updateImageObject(owner!!,d.image.id,d.preview) } }
                    .onSuccess { select(d.image.id) }.onFailure { failure("Photo transform failed: ${it.message}"); clear() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { val handled = drag != null; cancelGesture(); return handled }
        }
        return drag != null
    }
    private fun append(event: MotionEvent, map: (Float,Float)->Point) {
        val d = drag ?: return; val index = event.findPointerIndex(d.pointer); if (index < 0) return
        val point = map(event.getX(index),event.getY(index)); val original = d.image.placement
        runCatching {
            when (val handle = d.handle) {
                null -> {
                    val delta = d.alignment?.move(point.x-d.start.x, point.y-d.start.y) ?: Point(point.x-d.start.x, point.y-d.start.y)
                    original.translated(delta.x, delta.y)
                }
                8 -> {
                    val next = angle(point,original.pagePoint(.5f,.5f))
                    var delta = next-d.lastAngle
                    while(delta>180) delta-=360; while(delta< -180) delta+=360
                    d.rotation += delta; d.lastAngle = next
                    val raw = original.degrees+d.rotation
                    val snap = round(raw/45f)*45f
                    original.copy(degrees = if (abs(raw-snap)<=4f) snap else raw)
                }
                else -> {
                    val radians = Math.toRadians(original.degrees.toDouble())
                    val dx = point.x-d.start.x; val dy = point.y-d.start.y
                    val localX = cos(radians).toFloat()*dx+sin(radians).toFloat()*dy
                    val localY = -sin(radians).toFloat()*dx+cos(radians).toFloat()*dy
                    val anchor = anchors[handle]; val opposite = anchors[(handle+4)%8]
                    val vx = (anchor.first-opposite.first)*d.frame.width
                    val vy = (anchor.second-opposite.second)*d.frame.height
                    val sx: Float; val sy: Float
                    if (handle%2==0) {
                        val scale = (1+(localX*vx+localY*vy)/(vx*vx+vy*vy)).coerceIn(maxOf(1/original.width,1/original.height),minOf(100000/original.width,100000/original.height))
                        sx=scale; sy=scale
                    } else { sx=if(vx==0f)1f else (1+localX/vx).coerceIn(1/original.width,100000/original.width)
                        sy=if(vy==0f)1f else (1+localY/vy).coerceIn(1/original.height,100000/original.height) }
                    val w=original.width*sx; val h=original.height*sy
                    val pivot=original.pagePoint(opposite.first,opposite.second)
                    val cx=(.5f-opposite.first)*w; val cy=(.5f-opposite.second)*h
                    ImagePlacement(pivot.x+cos(radians).toFloat()*cx-sin(radians).toFloat()*cy-w/2,
                        pivot.y+sin(radians).toFloat()*cx+cos(radians).toFloat()*cy-h/2,w,h,original.degrees)
                }
            }
        }.onSuccess { d.preview=it } // Out-of-domain drag keeps the last valid preview; never writes invalid geometry.
    }
    fun delete(): Boolean {
        sync(); val id=selected ?: return false; val owner=pageId ?: return false
        if (isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        repository.edit { it.removeImageObjects(owner,setOf(id)) }; clear(); return true
    }
    fun duplicate(): Boolean {
        sync(); val id=selected ?: return false; val owner=pageId ?: return false
        if (isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        return runCatching { var copy: String?=null; repository.edit { copy=it.duplicateImageObject(owner,id); true }; copy?.let { select(it) } == true }
            .onFailure { failure("Photo duplication failed: ${it.message}") }.getOrDefault(false)
    }
    fun copy(clipboard: PhotoClipboard, cut: Boolean): Boolean {
        sync(); val image=current() ?: return false
        if (isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        val asset=baseline?.imageAssets?.firstOrNull { it.id==image.assetId } ?: return false
        clipboard.capture(asset,image)
        return !cut || delete()
    }
    fun paste(clipboard: PhotoClipboard, at: Point? = null): Boolean {
        val content=clipboard.content ?: return false; val page=repository.currentPage ?: return false
        if (isEditing || !repository.canChangeNotebook || repository.readOnly) return false
        return runCatching {
            val p=content.image.placement
            // Centre on the target page; bound oversized cross-page copies without changing the aspect ratio.
            val fit=minOf(1f,page.width*.8f/p.width,page.height*.8f/p.height)
            val w=p.width*fit; val h=p.height*fit
            var inserted: String?=null
            val o=pasteOrigin(at,w,h,page.width,page.height)
            repository.edit { inserted=it.insertImageObject(page.id,content.asset,ImagePlacement(o.x,o.y,w,h,p.degrees),content.image.orientation,content.image.crop);true }
            inserted?.let { select(it) } == true
        }.onFailure { failure("Photo paste failed: ${it.message}") }.getOrDefault(false)
    }
}
