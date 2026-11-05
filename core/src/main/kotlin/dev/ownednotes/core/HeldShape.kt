package dev.ownednotes.core

import kotlin.math.*

/** B43: immutable contact baseline; repeated moves never compound scale or change the original freehand ink. */
class HeldShape(private val kind: ShapeRecognizer.Kind, path: List<Point>, private val contact: Point, guideLines: List<Pair<Point,Point>> = emptyList()) {
    private val path=path.toList()
    private val references=if(kind==ShapeRecognizer.Kind.LINE) guideLines.take(12).flatMap { (a,b) ->
        if(listOf(a.x,a.y,b.x,b.y).any { !it.isFinite() || abs(it)>100000 } || hypot(b.x-a.x,b.y-a.y)<ShapeRecognizer.MIN_SIZE) emptyList()
        else {
            val angle=atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble())
            listOf(angle to (a to b),(angle+PI/2) to (a to b))
        }
    } else emptyList()

    data class Result(val kind: ShapeRecognizer.Kind, val path: List<Point>, val guides: List<Pair<Point, Point>>)

    private val adjustmentAngle: Double = when (kind) {
        ShapeRecognizer.Kind.RECTANGLE -> path.firstOrNull()?.let { start ->
            path.drop(1).firstOrNull { hypot(it.x-start.x,it.y-start.y)>.01f }?.let {
                atan2((it.y-start.y).toDouble(),(it.x-start.x).toDouble())
            }
        } ?: 0.0
        ShapeRecognizer.Kind.ELLIPSE -> ellipseAngle()
        else -> 0.0
    }

    /** Arc-weighted principal axis; avoids equal weighting of unevenly spaced samples and closure duplicates.
     * Evaluated once per held contact, never in the move/render loop.
     */
    private fun ellipseAngle(): Double {
        if(path.size<3) return 0.0
        val ring=if(path.first()==path.last()) path else path+path.first()
        var weight=0.0;var cx=0.0;var cy=0.0
        for(i in 1 until ring.size) {
            val a=ring[i-1];val b=ring[i];val w=hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble())
            weight+=w;cx+=w*(a.x+b.x)/2;cy+=w*(a.y+b.y)/2
        }
        if(weight<=0 || !weight.isFinite()) return 0.0
        cx/=weight;cy/=weight
        var xx=0.0;var yy=0.0;var xy=0.0
        for(i in 1 until ring.size) {
            val a=ring[i-1];val b=ring[i];val w=hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble())
            val x=(a.x+b.x)/2.0-cx;val y=(a.y+b.y)/2.0-cy
            xx+=w*x*x;yy+=w*y*y;xy+=w*x*y
        }
        return .5*atan2(2*xy,xx-yy)
    }

    private val frameCos=cos(adjustmentAngle).toFloat()
    private val frameSin=sin(adjustmentAngle).toFloat()
    private fun local(p: Point)=Point(p.x*frameCos+p.y*frameSin,-p.x*frameSin+p.y*frameCos)
    private fun world(p: Point)=Point(p.x*frameCos-p.y*frameSin,p.x*frameSin+p.y*frameCos)
    // The frame and opposite anchor belong to the contact, not to its display frames.
    private val points=if(kind==ShapeRecognizer.Kind.LINE) emptyList() else this.path.map(::local)
    private val grab=local(contact)
    private val left=points.minOfOrNull { it.x } ?: 0f
    private val right=points.maxOfOrNull { it.x } ?: 0f
    private val top=points.minOfOrNull { it.y } ?: 0f
    private val bottom=points.maxOfOrNull { it.y } ?: 0f
    private val w=right-left
    private val h=bottom-top
    private val gx=if(grab.x<(left+right)/2) left else right
    private val gy=if(grab.y<(top+bottom)/2) top else bottom
    private val ax=if(gx==left) right else left
    private val ay=if(gy==top) bottom else top

    fun move(pointer: Point, tolerance: Float): Result? {
        if (path.size < 2 || !pointer.x.isFinite() || !pointer.y.isFinite()) return null
        require(tolerance.isFinite() && tolerance > 0f)
        val dx = pointer.x - contact.x; val dy = pointer.y - contact.y
        if (hypot(dx, dy) < tolerance * .35f) return null
        if (kind == ShapeRecognizer.Kind.LINE) {
            val start = path.first(); val oldEnd = path.last()
            val end = Point(oldEnd.x + dx, oldEnd.y + dy)
            val length = hypot(end.x - start.x, end.y - start.y)
            if (length < ShapeRecognizer.MIN_SIZE) return null
            val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            var nearest = round(angle / (PI / 4)) * (PI / 4)
            var gap=abs(angle-nearest)
            var reference: Pair<Point,Point>?=null
            for((base,line) in references) {
                val target=base+round((angle-base)/PI)*PI
                val delta=abs(angle-target)
                if(delta+1e-8<gap) { nearest=target;gap=delta;reference=line }
            }
            val assisted = gap <= Math.toRadians(ShapeRecognizer.ANGLE_SNAP_DEGREES)
            val theta = if (assisted) nearest else angle
            val x = cos(theta).toFloat(); val y = sin(theta).toFloat()
            val target = Point(start.x + length * x, start.y + length * y)
            val points = path.indices.map { i ->
                val t = i.toFloat() / path.lastIndex
                Point(start.x + (target.x - start.x) * t, start.y + (target.y - start.y) * t)
            }
            val guides = if (assisted) listOf(Point(start.x - x * tolerance * 3, start.y - y * tolerance * 3) to
                Point(target.x + x * tolerance * 3, target.y + y * tolerance * 3)) + listOfNotNull(reference) else emptyList()
            return Result(kind, points, guides)
        }
        // Resize in the shape's own frame. A tilted oval can have a square page-axis
        // bounding box while remaining eccentric, so that box cannot establish circle symmetry.
        if (w < .01f || h < .01f) return null
        val delta = local(Point(dx, dy))
        var tx = gx + delta.x; var ty = gy + delta.y
        var square = false
        if (kind in setOf(ShapeRecognizer.Kind.RECTANGLE, ShapeRecognizer.Kind.CIRCLE, ShapeRecognizer.Kind.ELLIPSE) &&
            abs(abs(tx - ax) - abs(ty - ay)) <= tolerance) {
            val side = (abs(tx - ax) + abs(ty - ay)) / 2
            tx = ax + (if (tx >= ax) side else -side); ty = ay + (if (ty >= ay) side else -side)
            square = true
        }
        if (abs(tx - ax) < ShapeRecognizer.MIN_SIZE / 2 || abs(ty - ay) < ShapeRecognizer.MIN_SIZE / 2) return null
        val sx = (tx - ax) / (gx - ax); val sy = (ty - ay) / (gy - ay)
        val transformed = points.map { world(Point(ax + (it.x - ax) * sx, ay + (it.y - ay) * sy)) }
        if (transformed.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 100_000 || abs(it.y) > 100_000 }) return null
        // A symmetry snap still uses the shape frame; its visual ruler axes always stay screen-aligned.
        val guides = if (square) HeldShapeGuides.internalCross(transformed,tolerance) else emptyList()
        val resultKind = when {
            square && kind == ShapeRecognizer.Kind.ELLIPSE -> ShapeRecognizer.Kind.CIRCLE
            !square && kind == ShapeRecognizer.Kind.CIRCLE -> ShapeRecognizer.Kind.ELLIPSE
            else -> kind
        }
        return Result(resultKind, transformed, guides)
    }
}
