package dev.ownednotes.core

import kotlin.math.abs
import kotlin.math.max

/** Conservative manual-preview preservation of detached markers; never reads/invents their label. */
object HandwritingMarkers {
    class Leading(val marker:List<InkStroke>,val body:List<InkStroke>)
    private class Box(val index:Int,val left:Float,val top:Float,val right:Float,val bottom:Float) {
        val width get()=right-left
        val height get()=bottom-top
    }
    private fun clusters(line:List<InkStroke>):List<List<Box>> {
        val boxes=line.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val result=mutableListOf<MutableList<Box>>()
        var right=Float.NEGATIVE_INFINITY
        for(box in boxes.sortedBy {it.left}) {
            if(result.isEmpty() || box.left>right) {result+=mutableListOf(box);right=box.right}
            else {result.last()+=box;right=max(right,box.right)}
        }
        return result
    }
    /** Preserve interior-crossing rays, including a short central mark; neighbouring curved letters stay prose. */
    fun asterisks(line:List<InkStroke>):List<List<InkStroke>> {
        if(!valid(line))return emptyList()
        val clusters=clusters(line)
        if(clusters.size>128)return emptyList()
        val result=mutableListOf<List<InkStroke>>()
        for((k,group)in clusters.withIndex()) {
            if(group.size !in 3..5)continue
            val ink=group.map {line[it.index]}
            if(ink.any {it.samples.size !in 2..512})continue
            val points=ink.map {s->s.samples.map {Point(it.x,it.y)}}
            val axes=points.map(::markerAxis)
            val fitted=axes.indices.filter {axes[it]!=null}
            val extra=axes.indices.filter {axes[it]==null}
            if(fitted.size<3 || extra.size>1)continue
            val boxes=fitted.map {group[it]}
            val left=boxes.minOf {it.left};val top=boxes.minOf {it.top}
            val end=boxes.maxOf {it.right};val bottom=boxes.maxOf {it.bottom}
            val span=max(end-left,bottom-top)
            if(span<ShapeRecognizer.MIN_SIZE || end-left<=0 || (end-left)/(bottom-top) !in .35f..2.5f)continue
            if(k>0 && left-clusters[k-1].maxOf {it.right}<span*.1f)continue
            if(k<clusters.lastIndex && clusters[k+1].minOf {it.left}-end<span*.1f)continue
            val ends=fitted.map {axes[it]!!}
            val lengths=ends.map {(a,b)->kotlin.math.hypot(b.x-a.x,b.y-a.y)}
            if(lengths.min()<=0 || lengths.max()/lengths.min()>2.25f)continue
            val angles=ends.map {(a,b)->Math.toDegrees(kotlin.math.atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble())).let {(it+180)%180}}
            fun apart(a:Int,b:Int):Boolean {val d=abs(angles[a]-angles[b]);return minOf(d,180-d)>=25.0}
            if(angles.indices.none {a->(a+1 until angles.size).any {b->apart(a,b) && (b+1 until angles.size).any {c->apart(a,c)&&apart(b,c)}}})continue
            // Branches in A/K/Y meet at endpoints. Every marker ray needs an interior crossing with another ray.
            if(ends.indices.any {i->ends.indices.none {j->
                if(i==j || !apart(i,j))false else {
                    val(a,b)=ends[i];val(c,d)=ends[j]
                    val ux=b.x-a.x;val uy=b.y-a.y;val vx=d.x-c.x;val vy=d.y-c.y
                    val det=ux*vy-uy*vx
                    if(abs(det)<lengths[i]*lengths[j]*.01f)false else {
                        val t=((c.x-a.x)*vy-(c.y-a.y)*vx)/det
                        val u=((c.x-a.x)*uy-(c.y-a.y)*ux)/det
                        t in .1f.. .9f && u in .1f.. .9f
                    }
                }
            }})continue
            val mx=ends.map {(a,b)->(a.x+b.x)/2}.average().toFloat()
            val my=ends.map {(a,b)->(a.y+b.y)/2}.average().toFloat()
            // A small bounded neighbourhood permits an uneven centre, while every ray must still cross internally.
            val centres=listOf(Point(mx,my))+(-1..1).flatMap {x->(-1..1).filter {x!=0 || it!=0}.map {y->Point(mx+x*span*.1f,my+y*span*.1f)}}
            val centre=centres.firstOrNull {c->ends.withIndex().all {(i,pair)->
                val(a,b)=pair;val dx=b.x-a.x;val dy=b.y-a.y;val length=lengths[i]
                val along=((c.x-a.x)*dx+(c.y-a.y)*dy)/(length*length)
                along in .15f.. .85f && pathDistance(c.x,c.y,points[fitted[i]])<=span*.15f
            }}?:continue
            val kept=fitted.toMutableSet();var unsafe=false
            for(i in extra) {
                val ps=points[i];val box=group[i]
                val central=ps.zipWithNext().sumOf {(a,b)->kotlin.math.hypot(b.x-a.x,b.y-a.y).toDouble()}<=lengths.sorted()[lengths.size/2]*.65 &&
                    ps.all {kotlin.math.hypot(it.x-centre.x,it.y-centre.y)<=span*.45f}
                if(central)kept+=i
                // Only an outlying curved prose stroke can overlap a small edge; never absorb it into the marker.
                else if(!((box.left<left && box.right<=left+span*.15f) || (box.right>end && box.left>=end-span*.15f)))unsafe=true
            }
            if(unsafe)continue
            val indices=kept.map {group[it].index}.toSet()
            result+=line.filterIndexed {i,_->i in indices}
        }
        return result
    }
    /** A loose one-stroke arrow must have a long straight shaft followed by two opposite, backward branches. */
    fun arrows(line:List<InkStroke>):List<List<InkStroke>> {
        if(!valid(line))return emptyList()
        val clusters=clusters(line)
        if(clusters.size>128)return emptyList()
        val result=mutableListOf<List<InkStroke>>()
        for((k,group)in clusters.withIndex()) {
            if(group.size!=1)continue
            val box=group.single();val stroke=line[box.index]
            if(stroke.samples.size !in 5..512)continue
            val span=max(box.width,box.height)
            if(k>0 && box.left-clusters[k-1].maxOf {it.right}<span*.1f)continue
            if(k<clusters.lastIndex && clusters[k+1].minOf {it.left}-box.right<span*.1f)continue
            val ps=stroke.samples;val a=ps.first()
            val extent=ps.maxOf {kotlin.math.hypot(it.x-a.x,it.y-a.y)}
            if(extent<ShapeRecognizer.MIN_SIZE)continue
            var travel=0f;var best=0;var length=0f;var dx=0f;var dy=0f
            for(i in 1 until ps.size-2) {
                travel+=kotlin.math.hypot(ps[i].x-ps[i-1].x,ps[i].y-ps[i-1].y)
                val d=kotlin.math.hypot(ps[i].x-a.x,ps[i].y-a.y)
                if(d<extent*.65f || d<=length || travel>d*1.2f)continue
                val ux=(ps[i].x-a.x)/d;val uy=(ps[i].y-a.y)/d
                if((0..i).any {j->abs((ps[j].x-a.x)*uy-(ps[j].y-a.y)*ux)>d*.08f})continue
                best=i;length=d;dx=ux;dy=uy
            }
            if(best==0)continue
            val along=ps.drop(best).map {(it.x-a.x)*dx+(it.y-a.y)*dy}
            val side=ps.drop(best).map {(it.x-a.x)*dy-(it.y-a.y)*dx}
            if(along.min()<length*.6f || along.max()>length*1.25f || side.min()>-length*.12f || side.max()<length*.12f ||
                side.maxOf {abs(it)}>length*.65f || along.zipWithNext().sumOf {(a,b)->max(0f,a-b).toDouble()}<length*.12 ||
                ps.drop(best).zipWithNext().sumOf {(a,b)->kotlin.math.hypot(b.x-a.x,b.y-a.y).toDouble()}>length*3)continue
            result+=listOf(stroke)
        }
        return result
    }
    /** Paired short slanted marks above prose are kept as ink, without inventing opening/closing quote characters. */
    fun quotationMarks(line:List<InkStroke>):List<List<InkStroke>> {
        if(!valid(line))return emptyList()
        val boxes=line.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val heights=boxes.map {it.height}.sorted();val typical=heights[heights.size*3/4]
        if(typical<=0)return emptyList()
        val body=boxes.filter {it.height>typical*.35f}.map {it.bottom}.sorted()
        if(body.size<3)return emptyList()
        val baseline=body[body.size/2]
        val candidates=boxes.filter {box->
            val ps=line[box.index].samples
            if(ps.size !in 2..512 || box.height !in typical*.12f..typical*.65f || box.bottom>baseline-typical*.25f || box.height<box.width*.9f)return@filter false
            val a=ps.first();val b=ps.last();val chord=kotlin.math.hypot(b.x-a.x,b.y-a.y)
            abs(b.y-a.y)>=box.height*.55f && chord>0f && ps.zipWithNext().sumOf {(a,b)->kotlin.math.hypot(b.x-a.x,b.y-a.y).toDouble()}<=chord*1.7
        }.sortedBy {it.left}
        val pairs=mutableListOf<MutableList<Box>>()
        for(box in candidates) {
            val last=pairs.lastOrNull()?.lastOrNull()
            if(last==null || box.left-last.right>typical*.3f || abs((box.top+box.bottom)-(last.top+last.bottom))*.5f>typical*.65f)pairs+=mutableListOf(box)
            else pairs.last()+=box
        }
        return pairs.filter {it.size==2}.mapNotNull {pair->
            val left=pair.minOf {it.left};val right=pair.maxOf {it.right}
            val top=pair.minOf {it.top};val bottom=pair.maxOf {it.bottom}
            // Ticks directly over a letter can be an umlaut, not detached quotation punctuation.
            if(boxes.any {it.index!=pair[0].index && it.index!=pair[1].index && it.height>typical*.35f &&
                    it.left<right && it.right>left && it.top>=top-typical*.1f && it.bottom>=bottom+typical*.35f})return@mapNotNull null
            val a=line[pair[0].index].samples;val b=line[pair[1].index].samples
            val ax=a.last().x-a.first().x;val ay=a.last().y-a.first().y
            val bx=b.last().x-b.first().x;val by=b.last().y-b.first().y
            if(abs(ax*bx+ay*by)<kotlin.math.hypot(ax,ay)*kotlin.math.hypot(bx,by)*.65f)null
            else {val indices=pair.map {it.index}.toSet();line.filterIndexed {i,_->i in indices}}
        }
    }
    private fun markerAxis(points:List<Point>):Pair<Point,Point>? {
        ShapeRecognizer.fittedStraightAxis(points)?.let {return it}
        val a=points.first();val b=points.last();val dx=b.x-a.x;val dy=b.y-a.y;val length=kotlin.math.hypot(dx,dy)
        if(length<ShapeRecognizer.MIN_SIZE || points.zipWithNext().sumOf {(a,b)->kotlin.math.hypot(b.x-a.x,b.y-a.y).toDouble()}>length*1.6 ||
            points.any {abs((it.x-a.x)*dy-(it.y-a.y)*dx)>length*length*.32f})return null
        return a to b
    }
    private fun pathDistance(x:Float,y:Float,points:List<Point>):Float = points.zipWithNext().minOf {(a,b)->
        val dx=b.x-a.x;val dy=b.y-a.y;val d=dx*dx+dy*dy
        val t=if(d<=0)0f else (((x-a.x)*dx+(y-a.y)*dy)/d).coerceIn(0f,1f)
        kotlin.math.hypot(x-a.x-t*dx,y-a.y-t*dy)
    }
    /** Keep an uncertain punctuation-bearing word when its edge has a tall curved mark.
     * This prevents a comma-sized reading from replacing a parenthesis; manual preview only.
     * Original samples, capture order and outlines survive without guessing a character.
     */
    fun protectTerminalPunctuation(words:List<HandwritingRestyle.Word>, source:List<InkStroke>):List<HandwritingRestyle.Word> {
        return words.map {word->
            val text=word.text;val ink=word.strokes
            if(text==null || text.lastOrNull() !in listOf(',', ';', ':', '.', '!', '?') ||
                text.count(Char::isLetterOrDigit)<2 || !valid(ink))return@map word
            if(terminalCurves(ink).isEmpty())word else {
                val originals=ink.associateBy {it.id}
                val kept=source.filter {originals[it.id] === it}
                if(kept.size==ink.size)HandwritingRestyle.Word(null,kept) else word
            }
        }
    }
    private fun terminalCurves(ink:List<InkStroke>):List<Box> {
        if(!valid(ink))return emptyList()
        val boxes=ink.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val left=boxes.minOf {it.left};val right=boxes.maxOf {it.right}
        val height=boxes.maxOf {it.bottom}-boxes.minOf {it.top}
        if(height<=0 || right<=left)return emptyList()
        return boxes.filter {box->
            val points=ink[box.index].samples
            if(points.size !in 6..512 || box.height<height*.65f || box.width/box.height !in .03f.. .4f ||
                box.left<left+(right-left)*.7f || box.right<right-height*.05f)return@filter false
            val a=points.first();val b=points.last();val dy=b.y-a.y;val dx=b.x-a.x
            if(abs(dy)<box.height*.8f || minOf(a.y,b.y)>box.top+box.height*.15f ||
                maxOf(a.y,b.y)<box.bottom-box.height*.15f)return@filter false
            var travel=0f
            for(i in 1 until points.size)travel+=abs(points[i].y-points[i-1].y)
            if(travel>box.height*1.3f)return@filter false
            val length=kotlin.math.hypot(dx,dy)
            val bow=points.maxOf {abs((it.x-a.x)*dy-(it.y-a.y)*dx)/length}
            bow>=box.height*.025f && bow<=box.height*.3f &&
                boxes.count {it.index!=box.index && it.right<=box.left+height*.05f}>=2
        }
    }
    class MarkedLetters(val text:String,val letters:List<InkStroke>,val punctuation:List<InkStroke>)
    /** A possible closing mark is kept as ink, never inferred as a character. The caller must independently
     * read all remaining letters and require exact agreement before using this split in a manual preview.
     */
    fun closingLetters(word:HandwritingRestyle.Word,source:List<InkStroke>):MarkedLetters? {
        val text=word.text?:return null
        if(text.lastOrNull() !in listOf(',', ';', ':', '.', '!', '?'))return null
        val expected=text.dropLast(1)
        if(expected.length !in 2..32 || expected.any {!it.isLetter()})return null
        val ink=word.strokes;val curve=terminalCurves(ink).singleOrNull()?:return null
        val originals=ink.associateBy {it.id};val ordered=source.filter {originals[it.id] === it}
        if(ordered.size!=ink.size || ordered.map {it.id}.toSet().size!=ink.size)return null
        val boxes=ink.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val small=boxes.filter {it.index!=curve.index && it.width<=curve.height*.15f && it.height<=curve.height*.15f &&
            it.left>=curve.left && it.right<=curve.right && it.top>=curve.top && it.bottom<=curve.bottom}
        if(small.size>2)return null
        val kept=(small.map {it.index}+curve.index).toSet()
        val body=boxes.filter {it.index !in kept}
        if(body.size<2)return null
        val right=body.maxOf {it.right}
        if(curve.left<right-curve.height*.1f || curve.right<=right+curve.height*.1f ||
            small.any {it.left<=right+curve.height*.03f})return null
        val ids=kept.map {ink[it].id}.toSet()
        return MarkedLetters(expected,ordered.filter {it.id !in ids},ordered.filter {it.id in ids})
    }
    /** An isolated opening curve taller than a short lowercase word stays with its original word.
     * It may have been omitted by recognition. Do not infer or draw a bracket character.
     */
    fun protectOpeningMark(words:List<HandwritingRestyle.Word>, source:List<InkStroke>):List<HandwritingRestyle.Word> = words.map {word->
        val text=word.text;val ink=word.strokes
        if(text==null || text.firstOrNull()?.isLowerCase()!=true || text.any {!it.isLetter()} ||
            text.length !in 2..4 || !valid(ink))return@map word
        if(openingCurves(ink).isEmpty())word else {
            val originals=ink.associateBy {it.id};val kept=source.filter {originals[it.id] === it}
            if(kept.size==ink.size)HandwritingRestyle.Word(null,kept) else word
        }
    }
    private fun openingCurves(ink:List<InkStroke>):List<Box> {
        if(!valid(ink))return emptyList()
        val boxes=ink.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val left=boxes.minOf {it.left}
        return boxes.filter {box->
            val points=ink[box.index].samples;val rest=boxes.filter {it.index!=box.index}
            if(points.size !in 6..512 || box.height<=0 || box.width/box.height !in .03f.. .4f ||
                box.height<=rest.maxOf {it.height}*1.25f || box.left>left+box.height*.05f ||
                box.top>rest.minOf {it.top}+box.height*.05f || box.bottom<rest.maxOf {it.bottom}-box.height*.05f ||
                rest.any {it.left<box.right-box.height*.05f})return@filter false
            val a=points.first();val b=points.last();val dx=b.x-a.x;val dy=b.y-a.y
            if(abs(dy)<box.height*.8f || minOf(a.y,b.y)>box.top+box.height*.15f ||
                maxOf(a.y,b.y)<box.bottom-box.height*.15f)return@filter false
            var travel=0f
            for(i in 1 until points.size)travel+=abs(points[i].y-points[i-1].y)
            if(travel>box.height*1.3f)return@filter false
            val deviations=points.map {it.x-a.x-(it.y-a.y)*dx/dy}
            val inward=-deviations.min();val outward=deviations.max()
            inward>=box.height*.025f && inward<=box.height*.3f && outward<=inward*.5f
        }
    }
    /** A detached opening curve stays original; the caller must strictly confirm all remaining letters. */
    fun openingLetters(word:HandwritingRestyle.Word,source:List<InkStroke>):MarkedLetters? {
        val text=word.text?:return null
        if(text.firstOrNull()?.isLowerCase()!=true || text.length !in 2..4 || text.any {!it.isLetter()})return null
        val ink=word.strokes;val curve=openingCurves(ink).singleOrNull()?:return null
        val originals=ink.associateBy {it.id};val ordered=source.filter {originals[it.id] === it}
        if(ordered.size!=ink.size || ordered.map {it.id}.toSet().size!=ink.size)return null
        val kept=ink[curve.index]
        val letters=ordered.filter {it!==kept}
        if(letters.size<2)return null
        return MarkedLetters(text,letters,ordered.filter {it===kept})
    }
    private fun valid(line:List<InkStroke>)=line.size in 3..HandwritingInput.MAX_STROKES &&
        line.sumOf {it.samples.size.toLong()}<=HandwritingInput.MAX_POINTS &&
        line.none {s->s.samples.isEmpty() || s.samples.any {!it.x.isFinite() || !it.y.isFinite() || abs(it.x)>1000000 || abs(it.y)>1000000}} &&
        line.map {it.id}.toSet().size==line.size
    fun leadingCircle(line:List<InkStroke>):Leading? {
        if(line.size !in 3..HandwritingInput.MAX_STROKES || line.sumOf {it.samples.size.toLong()}>HandwritingInput.MAX_POINTS ||
            line.any {s->s.samples.isEmpty() || s.samples.any {!it.x.isFinite() || !it.y.isFinite() || abs(it.x)>1000000 || abs(it.y)>1000000}} ||
            line.map {it.id}.toSet().size!=line.size)return null
        val boxes=line.mapIndexed {i,s->Box(i,s.samples.minOf {it.x},s.samples.minOf {it.y},s.samples.maxOf {it.x},s.samples.maxOf {it.y})}
        val outer=boxes.minBy {it.left}
        if(outer.width<=0 || outer.height<=0 || outer.width/outer.height !in .35f..2.5f || line[outer.index].samples.size !in 6..1024)return null
        val inside=boxes.filter {it.index!=outer.index && it.left>outer.left+outer.width*.1f && it.right<outer.right-outer.width*.1f &&
            it.top>outer.top+outer.height*.1f && it.bottom<outer.bottom-outer.height*.1f}
        if(inside.size !in 1..4)return null
        val indices=(inside.map {it.index}+outer.index).toSet()
        val body=boxes.filter {it.index !in indices}
        if(body.isEmpty() || body.any {it.left<=outer.right+outer.width*.25f} || body.maxOf {it.right}-body.minOf {it.left}<outer.width*1.5f)return null
        val shape=runCatching {ShapeRecognizer.recognize(line[outer.index].samples.map {Point(it.x,it.y)},max(outer.width,outer.height)/16f)}.getOrNull()?:return null
        if(!shape.closed || shape.kind !in setOf(ShapeRecognizer.Kind.CIRCLE,ShapeRecognizer.Kind.ELLIPSE))return null
        return Leading(line.filterIndexed {i,_->i in indices},line.filterIndexed {i,_->i !in indices})
    }
}
