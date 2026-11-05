package dev.ownednotes.app

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Region
import dev.ownednotes.core.EraserSweep
import dev.ownednotes.core.InkStroke
import kotlin.math.roundToInt

/** The same visible vector geometry is used for display, hit testing and PDF export. */
object InkGeometry {
    fun sweep(sweep: EraserSweep): Path {
        val result = Path()
        val points = sweep.basis?.let { basis -> sweep.points.map(basis::inverse) } ?: sweep.points
        val first = points.first()
        val line = Path().apply {
            moveTo(first.x, first.y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = sweep.radius * 2
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }.getFillPath(line, result)
        // A stationary tap must erase too; degenerate paths vary across renderers.
        val cap = Path().apply { addCircle(first.x, first.y, sweep.radius, Path.Direction.CW) }
        check(result.op(cap, Path.Op.UNION)) { "Cannot construct eraser sweep" }
        sweep.basis?.let { basis -> result.transform(Matrix().apply {
            setValues(floatArrayOf(basis.a, basis.c, 0f, basis.b, basis.d, 0f, 0f, 0f, 1f))
        }) }
        return result
    }

    private data class Mask(val sweep: EraserSweep, val path: Path)
    // A gesture shares one immutable mask across every touched stroke. Keep these operands
    // thread-confined and private: Path is mutable, and callers of sweep() still own a fresh path.
    private val masks = ThreadLocal.withInitial { ArrayDeque<Mask>() }
    private fun maskPath(value: EraserSweep): Path {
        val cache = masks.get()
        cache.firstOrNull { it.sweep === value }?.let { return it.path }
        val path = sweep(value)
        if (value.points.size <= 25_000) {
            while (cache.isNotEmpty() && (cache.size >= 8 || cache.sumOf { it.sweep.points.size } + value.points.size > 50_000))
                cache.removeFirst()
            cache.addLast(Mask(value, path))
        }
        return path
    }

    /**
     * B03 (P131): the live area-eraser preview. A moving sweep is its round-capped stroked fill, which already covers the
     * start cap, so the costly union with the cap circle is skipped; a tap or a transformed sweep uses [sweep].
     */
    fun previewSweep(sweep: EraserSweep): Path {
        val first = sweep.points.first()
        if (sweep.basis != null || sweep.points.all { it == first }) return sweep(sweep)
        val line = Path().apply { moveTo(first.x, first.y); sweep.points.drop(1).forEach { lineTo(it.x, it.y) } }
        return Path().also { result ->
            Paint().apply { style = Paint.Style.STROKE; strokeWidth = sweep.radius * 2; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }.getFillPath(line, result)
        }
    }

    private data class Filled(val stroke: InkStroke, val outline: Path, val fill: Path, val visible: Path, val points: Int)
    // Path is thread-confined. Bound the additional cache by geometry size as well as count.
    private val filled = ThreadLocal.withInitial { ArrayDeque<Filled>() }
    private fun parts(stroke: InkStroke): Filled {
        val cache=filled.get()
        cache.firstOrNull { it.stroke === stroke }?.let { return it }
        val outline=outline(stroke)
        val fill=Path().apply {
            fillType=Path.FillType.EVEN_ODD
            stroke.samples.forEachIndexed { i,p -> if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) }; close()
            stroke.erasures.forEach { check(op(maskPath(it),Path.Op.DIFFERENCE)) { "Cannot subtract erased fill" } }
        }
        val visible=Path(outline).apply { check(op(fill,Path.Op.UNION)) { "Cannot combine shape fill" } }
        val result=Filled(stroke,outline,fill,visible,stroke.samples.size+stroke.outlines.sumOf { it.size }+stroke.erasures.sumOf { it.points.size })
        if(result.points<=50_000) {
            while(cache.isNotEmpty() && (cache.size>=24 || cache.sumOf { it.points }+result.points>50_000)) cache.removeFirst()
            cache.addLast(result)
        }
        return result
    }
    fun visible(stroke: InkStroke): Path = if(stroke.fillColor == null) outline(stroke) else parts(stroke).visible

    fun draw(canvas: Canvas, stroke: InkStroke, paint: Paint, opacity: Float = 1f, path: Path? = null) {
        if(stroke.fillColor == null) canvas.drawPath(path ?: visible(stroke),paint(paint,stroke,opacity))
        else {
            val geometry=parts(stroke)
            paint(paint,stroke,opacity).apply { color=requireNotNull(stroke.fillColor).toInt(); shader=null; alpha=(255*opacity*(if(stroke.isHighlighter) InkStroke.HIGHLIGHTER_OPACITY else 1f)).roundToInt() }
            canvas.drawPath(geometry.fill,paint)
            canvas.drawPath(geometry.outline,paint(paint,stroke,opacity))
        }
    }
    private fun outline(stroke: InkStroke): Path = Path().apply {
        // B40: serially unioning every dash into an ever larger path is quadratic on dense ink.
        // Balanced merges preserve the independently filled outlines' union, including opposite windings.
        var level = stroke.outlines.map { outline ->
            Path().apply {
                moveTo(outline[0].x, outline[0].y)
                outline.drop(1).forEach { lineTo(it.x, it.y) }; close()
            }
        }
        val a = RectF(); val b = RectF()
        while (level.size > 1) {
            level = level.chunked(2).map { pair ->
                val left = pair[0]
                if (pair.size == 2) {
                    val right = pair[1]
                    left.computeBounds(a, true); right.computeBounds(b, true)
                    // Disjoint bounds cannot overlap or cancel each other's winding.
                    if (!RectF.intersects(a, b)) left.addPath(right)
                    else check(left.op(right, Path.Op.UNION)) { "Cannot combine ink outlines" }
                }
                left
            }
        }
        level.firstOrNull()?.let { set(it) }
        stroke.erasures.forEach { check(op(maskPath(it), Path.Op.DIFFERENCE)) { "Cannot subtract erased area" } }
    }

    /** Fill paint for one stroke. A highlighter is one uniform translucent layer multiplied onto what lies beneath, so ink under it stays dark. */
    /** [opacity] is the stroke's layer opacity (P102), multiplied into its own. */
    fun paint(paint: Paint, stroke: InkStroke, opacity: Float = 1f): Paint = paint.apply {
        color = stroke.color.toInt()
        if (stroke.isHighlighter) { alpha = (255 * InkStroke.HIGHLIGHTER_OPACITY).roundToInt(); blendMode = BlendMode.MULTIPLY } else blendMode = null
        // P127: pencil fills its outline with graphite grain, denser the harder and more upright it was held (B22, P137);
        // every other stroke is a solid fill.
        shader = if (stroke.isPencil) PencilGrain.shader(color, PencilGrain.level(stroke.samples)) else null
        isFilterBitmap = stroke.isPencil
        if (opacity < 1f) alpha = (alpha * opacity).roundToInt().coerceAtLeast(1)
    }

    fun intersects(ink: Path, area: Path): Boolean {
        // B03 (P131): disjoint bounds cannot intersect, so skip the exact path op; Skia caches each path's bounds.
        val a = RectF().also { ink.computeBounds(it, true) }
        val b = RectF().also { area.computeBounds(it, true) }
        // Inverse fills extend beyond their finite contour bounds; only finite fills can be rejected here.
        if (!ink.isInverseFillType && !area.isInverseFillType && !RectF.intersects(a, b)) return false
        return Path().let {
            check(it.op(ink, area, Path.Op.INTERSECT)) { "Cannot hit-test ink" }
            !it.isEmpty
        }
    }

    private class SelectionFillProof {
        val rectangle = RectF(); val bounds = RectF()
        val clip = Region(); val pixels = Region()
        val contour = Path(); val outside = Path()
    }
    private val selectionFillProof = ThreadLocal.withInitial { SelectionFillProof() }
    private enum class SelectionProofResult { UNPROVEN, CONTAINED, DISJOINT }
    /** Positive fill proof, or the same finite disjoint-bounds rejection used by intersects(). */
    private fun selectionProof(ink: Path, area: Path, requireFill: Boolean = true): SelectionProofResult {
        if (ink.isInverseFillType || area.isInverseFillType) return SelectionProofResult.UNPROVEN
        val proof = selectionFillProof.get()
        val rectangular = area.isRect(proof.rectangle)
        if (!rectangular) area.computeBounds(proof.rectangle, true)
        ink.computeBounds(proof.bounds, true)
        val b = proof.bounds
        if (!RectF.intersects(b, proof.rectangle)) return SelectionProofResult.DISJOINT
        if (!proof.rectangle.contains(b) || !(b.width() <= 1024f && b.height() <= 1024f &&
            b.left > -10_000_000f && b.top > -10_000_000f && b.right < 10_000_000f && b.bottom < 10_000_000f)) return SelectionProofResult.UNPROVEN
        if (!rectangular) {
            if (b.width() <= 0f || b.height() <= 0f) return SelectionProofResult.UNPROVEN
            // This tests a sufficient enclosing rectangle, never an approximation of the ink or lasso.
            proof.contour.rewind(); proof.contour.addRect(b, Path.Direction.CW)
            check(proof.outside.op(proof.contour, area, Path.Op.DIFFERENCE)) { "Cannot test selection bounds" }
            if (!proof.outside.isEmpty) return SelectionProofResult.UNPROVEN
        }
        if (!requireFill) return SelectionProofResult.CONTAINED
        proof.clip.set(kotlin.math.floor(b.left.toDouble()).toInt(), kotlin.math.floor(b.top.toDouble()).toInt(),
            kotlin.math.ceil(b.right.toDouble()).toInt(), kotlin.math.ceil(b.bottom.toDouble()).toInt())
        return if (!proof.clip.isEmpty && proof.pixels.setPath(ink, proof.clip)) SelectionProofResult.CONTAINED
            else SelectionProofResult.UNPROVEN
    }

    /** Test the actual remaining fill, rather than its bounding box or erased source points. */
    fun selects(ink: Path, area: Path, fullyEnclosed: Boolean): Boolean {
        if (ink.isEmpty) return false
        if (!fullyEnclosed) return when (selectionProof(ink, area)) {
            SelectionProofResult.CONTAINED -> true
            SelectionProofResult.DISJOINT -> false
            // Finite bounds were already checked; inverse fills always need this exact operation.
            SelectionProofResult.UNPROVEN -> Path().let {
                check(it.op(ink, area, Path.Op.INTERSECT)) { "Cannot hit-test ink" }
                !it.isEmpty
            }
        }
        // A finite path wholly inside a finite rectangle cannot leave that rectangle, including its
        // curves, holes and erased remnants. This accepts exact containment without simplifying every
        // stroke through Skia. Anything crossing the bounds (or an inverse/non-rectangle) stays exact.
        if (!ink.isInverseFillType && !area.isInverseFillType) {
            val rectangle = RectF()
            if (area.isRect(rectangle)) {
                val bounds = RectF().also { ink.computeBounds(it, true) }
                if (rectangle.contains(bounds)) return true
            } else if (selectionProof(ink, area, requireFill = false) == SelectionProofResult.CONTAINED) return true
        }
        return Path().let {
            check(it.op(ink, area, Path.Op.DIFFERENCE)) { "Cannot test ink containment" }
            it.isEmpty
        }
    }
}
