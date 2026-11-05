package dev.ownednotes.core

import kotlin.math.abs

/** Manual Refine only: translate words within their own line; raw marks are fixed barriers. */
object HandwritingWordLayout {
    class Part(val source:List<InkStroke>,val lines:List<List<Point>>?)
    private data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float) {
        val width get()=right-left
    }
    private fun box(points:List<Point>):Box? {
        if(points.isEmpty() || points.any {!it.x.isFinite() || !it.y.isFinite() || abs(it.x)>1000000 || abs(it.y)>1000000})return null
        return Box(points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y})
    }
    /** At Even, share the available span between words, keeping both ends fixed. Original and refused runs are bit-exact.
     * No wrapping, scaling, y movement, source edits or movement across raw marks is performed.
     */
    fun arrange(parts:List<Part>,protectedInk:List<InkStroke>,xHeight:Float,amount:Float):List<List<List<Point>>?> {
        val original=parts.map {it.lines}
        if(parts.size<2 || !amount.isFinite() || amount<=0f || amount>1f || !xHeight.isFinite() || xHeight<=0f)return original
        val source=parts.flatMap {it.source}
        if(source.size>HandwritingInput.MAX_STROKES || source.any {it.samples.isEmpty()} ||
            source.sumOf {it.samples.size.toLong()}>HandwritingInput.MAX_POINTS || source.map {it.id}.toSet().size!=source.size)return original
        if(parts.sumOf {p->p.lines.orEmpty().sumOf {it.size.toLong()}}>HandwritingInput.MAX_POINTS)return original
        val sourceBoxes=parts.map {p->box(p.source.flatMap {s->s.samples.map {Point(it.x,it.y)}})?:return original}
        val drawn=parts.map {p->p.lines?.flatten()?.let(::box)}
        if(parts.indices.any {parts[it].lines!=null && drawn[it]==null})return original
        val fixed=(protectedInk+parts.filter {it.lines==null}.flatMap {it.source}).distinctBy {it.id}
        if(fixed.sumOf {it.samples.size.toLong()}>HandwritingInput.MAX_POINTS)return original
        val barriers=fixed.map {s->box(s.samples.map {Point(it.x,it.y)})?:return original}
        val result=original.toMutableList()
        fun blocked(a:Int,b:Int):Boolean {
            val left=sourceBoxes[a];val right=sourceBoxes[b]
            if(right.left<=left.left)return true
            val top=minOf(left.top,right.top)-xHeight*.5f
            val bottom=maxOf(left.bottom,right.bottom)+xHeight*.5f
            return barriers.any {it.right>=left.right && it.left<=right.left && it.bottom>=top && it.top<=bottom}
        }
        fun place(first:Int,end:Int) {
            if(end-first<2)return
            val boxes=(first until end).map {drawn[it]?:return}
            if(boxes.any {it.width<=0f})return
            val available=boxes.last().right-boxes.first().left-boxes.sumOf {it.width.toDouble()}.toFloat()
            val gap=available/(end-first-1)
            // Crowded/ambiguous runs stay original rather than squeezing letters or spilling outside the selection.
            if(!gap.isFinite() || gap<xHeight*.5f)return
            var x=boxes.first().left
            for(i in first until end) {
                val b=drawn[i]!!;val dx=(x-b.left)*amount
                if(abs(dx)>.00001f)result[i]=parts[i].lines!!.map {line->line.map {Point(it.x+dx,it.y)}}
                x+=b.width+gap
            }
        }
        var start=0
        for(i in parts.indices) {
            if(drawn[i]==null) {place(start,i);start=i+1}
            else if(i>start && blocked(i-1,i)) {place(start,i);start=i}
        }
        place(start,parts.size)
        return result
    }
}
