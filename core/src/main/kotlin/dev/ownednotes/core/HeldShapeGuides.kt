package dev.ownednotes.core

import kotlin.math.*

/** Bounded native-ink context captured once at held contact; never searches attachments or runs per frame. */
object HeldShapeGuides {
    /** Screen/page-axis rulers, clipped to the actual polygon; never a rotated bounding-box assertion. */
    fun internalCross(path:List<Point>,inset:Float):List<Pair<Point,Point>> {
        if(path.size<3 || !inset.isFinite() || inset<0f || path.any {!it.x.isFinite() || !it.y.isFinite()})return emptyList()
        val cx=(path.minOf {it.x}+path.maxOf {it.x})/2
        val cy=(path.minOf {it.y}+path.maxOf {it.y})/2
        fun axis(horizontal:Boolean):Pair<Point,Point>? {
            val at=if(horizontal)cy else cx;val middle=if(horizontal)cx else cy
            val cuts=ArrayList<Float>()
            for(i in path.indices) {
                val a=path[i];val b=path[(i+1)%path.size]
                val av=if(horizontal)a.y else a.x;val bv=if(horizontal)b.y else b.x
                // Half-open crossings count a polygon vertex once; parallel edges cannot divide by zero.
                if((av>at)!=(bv>at)) {
                    val from=if(horizontal)a.x else a.y;val to=if(horizontal)b.x else b.y
                    cuts.add(from+(at-av)/(bv-av)*(to-from))
                }
            }
            cuts.sort()
            for(i in 0 until cuts.size-1 step 2) {
                val lo=cuts[i];val hi=cuts[i+1]
                if(middle>=lo && middle<=hi && hi-lo>.001f) {
                    val margin=minOf(inset,(hi-lo)*.1f)
                    return if(horizontal)Point(lo+margin,at) to Point(hi-margin,at)
                        else Point(at,lo+margin) to Point(at,hi-margin)
                }
            }
            return null
        }
        return listOfNotNull(axis(true),axis(false))
    }

    fun nearbyLines(page: Page, start: Point, end: Point, screenUnit: Float): List<Pair<Point,Point>> {
        if(!screenUnit.isFinite() || screenUnit<=0 || listOf(start.x,start.y,end.x,end.y).any { !it.isFinite() }) return emptyList()
        val minimum=maxOf(ShapeRecognizer.MIN_SIZE,48f*screenUnit);val radius=144f*screenUnit
        val candidates=ArrayList<Pair<Float,Pair<Point,Point>>>();var strokes=0;var samples=0
        fun distance(p: Point,a: Point,b: Point): Float {
            val dx=(b.x-a.x).toDouble();val dy=(b.y-a.y).toDouble();val span=dx*dx+dy*dy
            if(span<=0) return hypot(p.x-a.x,p.y-a.y)
            val t=(((p.x-a.x)*dx+(p.y-a.y)*dy)/span).coerceIn(0.0,1.0)
            return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy).toFloat()
        }
        for(stroke in page.drawnStrokes.asReversed()) {
            if(++strokes>256) break
            if(stroke.isTape || stroke.erasures.isNotEmpty() || stroke.outlines.isEmpty() || page.opacityOf(stroke)<=0f || (stroke.color ushr 24)==0L) continue
            if(stroke.samples.size !in 2..512 || samples+stroke.samples.size>8192) continue
            samples+=stroke.samples.size
            val a=stroke.samples.first().let { Point(it.x,it.y) };val b=stroke.samples.last().let { Point(it.x,it.y) }
            val length=hypot(b.x-a.x,b.y-a.y)
            if(!length.isFinite() || length<minimum) continue
            val near=minOf(distance(start,a,b),distance(end,a,b),distance(a,start,end),distance(b,start,end))
            if(!near.isFinite() || near>radius) continue
            val dx=(b.x-a.x).toDouble();val dy=(b.y-a.y).toDouble()
            if(stroke.samples.any { !it.x.isFinite() || !it.y.isFinite() || abs(dx*(it.y-a.y)-dy*(it.x-a.x))/length>2f*screenUnit }) continue
            candidates.add(near to (a to b))
        }
        return candidates.sortedBy { it.first }.take(12).map { it.second }
    }
}
