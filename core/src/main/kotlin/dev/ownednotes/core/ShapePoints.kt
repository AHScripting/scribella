package dev.ownednotes.core

import kotlin.math.*

/** Editable vertices reconstructed only for explicitly identified, straight-edged canonical shapes. */
class ShapePoints private constructor(val kind: ShapeRecognizer.Kind, private val vertices: List<Point>) {
    val handles: List<Point> get() = if(kind==ShapeRecognizer.Kind.ARROW) vertices.take(2) else vertices
    fun move(index: Int, target: Point): List<Point>? {
        if(index !in handles.indices || !target.x.isFinite() || !target.y.isFinite() || abs(target.x)>100000 || abs(target.y)>100000) return null
        if(kind==ShapeRecognizer.Kind.RECTANGLE) return HeldShape(kind,vertices+vertices.first(),handles[index]).move(target,.5f)?.path
        val next=vertices.toMutableList()
        if(kind==ShapeRecognizer.Kind.ARROW) {
            val a=vertices[0];val b=vertices[1]
            val tail=if(index==0) target else a;val tip=if(index==1) target else b
            val ox=b.x-a.x;val oy=b.y-a.y;val nx=tip.x-tail.x;val ny=tip.y-tail.y
            val size=ox*ox+oy*oy
            if(hypot(nx,ny)<4f || size<1f) return null
            val c=(ox*nx+oy*ny)/size;val s=(ox*ny-oy*nx)/size
            return ShapeRecognizer.densify(vertices.map { p -> val x=p.x-a.x;val y=p.y-a.y;Point(tail.x+c*x-s*y,tail.y+s*x+c*y) },2f)
        }
        next[index]=target
        val closed=kind!=ShapeRecognizer.Kind.LINE
        val path=if(closed) next+next.first() else next
        if(path.zipWithNext().any { (a,b) -> hypot(a.x-b.x,a.y-b.y)<4f }) return null
        return ShapeRecognizer.densify(path,2f)
    }
    companion object {
        fun from(stroke: InkStroke): ShapePoints? {
            val kind=stroke.shapeKind ?: return null
            if(stroke.group!=null || stroke.erasures.isNotEmpty() || stroke.locked) return null
            if(kind !in setOf(ShapeRecognizer.Kind.LINE,ShapeRecognizer.Kind.ARROW,ShapeRecognizer.Kind.RECTANGLE,ShapeRecognizer.Kind.TRIANGLE,ShapeRecognizer.Kind.QUADRILATERAL,ShapeRecognizer.Kind.POLYGON)) return null
            val points=stroke.samples.map { Point(it.x,it.y) }
            if(points.size<2) return null
            fun removable(a: Point,b: Point,c: Point): Boolean {
                val x=b.x-a.x;val y=b.y-a.y;val u=c.x-b.x;val v=c.y-b.y
                return x*u+y*v>=0 && abs(x*v-y*u)<=.015f*max(1f,hypot(c.x-a.x,c.y-a.y))
            }
            val corners=mutableListOf<Point>()
            for(p in points) {
                if(corners.lastOrNull()?.let { hypot(it.x-p.x,it.y-p.y)<.001f }==true) continue
                while(corners.size>=2 && removable(corners[corners.size-2],corners.last(),p)) corners.removeAt(corners.lastIndex)
                corners.add(p)
            }
            val closed=kind !in setOf(ShapeRecognizer.Kind.LINE,ShapeRecognizer.Kind.ARROW)
            if(closed) {
                if(corners.size<4 || hypot(corners.first().x-corners.last().x,corners.first().y-corners.last().y)>.1f) return null
                corners.removeAt(corners.lastIndex)
                while(corners.size>3 && removable(corners.last(),corners.first(),corners[1])) corners.removeAt(0)
                while(corners.size>3 && removable(corners[corners.size-2],corners.last(),corners.first())) corners.removeAt(corners.lastIndex)
            }
            val expected=when(kind) { ShapeRecognizer.Kind.LINE->2;ShapeRecognizer.Kind.ARROW->5;ShapeRecognizer.Kind.TRIANGLE->3;ShapeRecognizer.Kind.RECTANGLE,ShapeRecognizer.Kind.QUADRILATERAL->4;else->corners.size }
            if(corners.size!=expected || corners.size !in 2..8) return null
            return ShapePoints(kind,corners)
        }
    }
}
