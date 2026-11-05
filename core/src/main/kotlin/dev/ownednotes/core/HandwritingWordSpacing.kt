package dev.ownednotes.core

import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max

/** B57: manual-preview word placement, after recognition. Automatic writing keeps the existing parser. */
object HandwritingWordSpacing {
    private class Cluster(val strokes:MutableList<InkStroke>,val left:Float,var right:Float)
    private fun weight(text:String)=text.sumOf { c->when {
        c.lowercaseChar() in "iltfj" -> .35
        c.lowercaseChar() in "mw" -> 1.05
        c.isLetterOrDigit() -> .72
        else -> .25
    }}.toFloat()
    private fun median(values:List<Float>)=values.sorted().let {it[it.size/2]}

    /** Only reconsider a grossly collapsed multi-character word, not ordinary personal spacing.
     * Require a distinctly better, unambiguous partition; otherwise keep that row as originally drawn.
     * Text, samples and stroke identities are never synthesized or changed here.
     */
    fun repair(words:List<HandwritingRestyle.Word>, confirmedFirstWord:Boolean=false):List<HandwritingRestyle.Word> {
        if(words.size<2 || words.any {it.text==null || it.strokes.isEmpty()})return words
        val source=words.flatMap {it.strokes}
        if(source.any {it.samples.isEmpty() || it.samples.any {p->!p.x.isFinite() || !p.y.isFinite()}})return words
        val widths=words.map {w->w.strokes.maxOf {s->s.samples.maxOf {it.x}}-w.strokes.minOf {s->s.samples.minOf {it.x}}}
        val weights=words.map {weight(it.text!!)}
        val units=widths.mapIndexed {i,w->w/weights[i]};val unit=median(units)
        if(unit<=0 || words.indices.none {words[it].text!!.count(Char::isLetterOrDigit)>=2 && units[it]<unit*.2f})return words
        fun kept()=listOf(HandwritingRestyle.Word(null,source))
        if(words.size>32 || source.size>HandwritingInput.MAX_STROKES || source.sumOf {it.samples.size.toLong()}>HandwritingInput.MAX_POINTS || source.map {it.id}.toSet().size!=source.size)return kept()
        val clusters=mutableListOf<Cluster>()
        source.map {s->Triple(s,s.samples.minOf {it.x},s.samples.maxOf {it.x})}.sortedBy {it.second}.forEach {(s,l,r)->
            val last=clusters.lastOrNull()
            if(last!=null && l<last.right+1f){last.strokes+=s;last.right=max(last.right,r)}else clusters+=Cluster(mutableListOf(s),l,r)
        }
        val n=clusters.size;val m=words.size
        if(n !in m..128)return kept()
        // A manual caller may independently confirm a well-proportioned long first word.
        // Constrain that exact boundary; retain both improvement and runner-up safety margins.
        val firstEnd=if(confirmedFirstWord) {
            val first=words.first();val ids=first.strokes.mapTo(HashSet()){it.id}
            val firstHeight=first.strokes.maxOf {s->s.samples.maxOf {it.y}}-first.strokes.minOf {s->s.samples.minOf {it.y}}
            val end=clusters.takeWhile {c->c.strokes.all {it.id in ids}}.size
            if(first.text!!.count(Char::isLetterOrDigit)<8 || firstHeight<=0 || widths.first()<firstHeight || end==0 ||
                clusters.take(end).sumOf {it.strokes.size}!=ids.size)return kept()
            end
        } else -1
        val heights=source.map {s->s.samples.maxOf {it.y}-s.samples.minOf {it.y}}
        val tall=heights.maxOrNull()?:return kept()
        if(tall<=0)return kept()
        val height=median(heights.filter {it>tall*.1f})
        fun cost(k:Int,start:Int,end:Int):Double {
            val width=max(height*.0001f,clusters[end-1].right-clusters[start].left)
            val ratio=ln((width/(unit*weights[k])).toDouble())
            val gap=if(end<n)clusters[end].left-clusters[end-1].right else 0f
            return ratio*ratio-.5*ln1p((gap/height).toDouble())
        }
        // Two best paths per state: the runner-up prevents silently picking an ambiguous boundary.
        val scores=Array(m+1){Array(n+1){DoubleArray(2){Double.POSITIVE_INFINITY}}}
        val starts=Array(m+1){Array(n+1){IntArray(2){-1}}}
        val ranks=Array(m+1){Array(n+1){IntArray(2)}}
        scores[0][0][0]=0.0
        for(k in 0 until m)for(start in 0 until n) {
            if(!scores[k][start][0].isFinite())continue
            for(end in start+1..n-(m-k-1)) {
                if(k==0 && firstEnd>=0 && end!=firstEnd)continue
                val step=cost(k,start,end)
                for(rank in 0..1) {
                    val score=scores[k][start][rank]+step
                    if(!score.isFinite())continue
                    val slot=if(score<scores[k+1][end][0])0 else if(score<scores[k+1][end][1])1 else continue
                    if(slot==0){scores[k+1][end][1]=scores[k+1][end][0];starts[k+1][end][1]=starts[k+1][end][0];ranks[k+1][end][1]=ranks[k+1][end][0]}
                    scores[k+1][end][slot]=score;starts[k+1][end][slot]=start;ranks[k+1][end][slot]=rank
                }
            }
        }
        val cuts=IntArray(m);var end=n;var rank=0
        for(k in m downTo 1){cuts[k-1]=end;val next=starts[k][end][rank];if(next<0)return kept();rank=ranks[k][end][rank];end=next}
        var oldCost=0.0;var start=0
        for(k in words.indices) {
            val ids=words[k].strokes.mapTo(HashSet()){it.id}
            val stop=start+clusters.drop(start).takeWhile {c->c.strokes.all {it.id in ids}}.size
            if(stop==start)return kept()
            oldCost+=cost(k,start,stop);start=stop
        }
        if(oldCost-scores[m][n][0]<1.0 || scores[m][n][1]-scores[m][n][0]<.25)return kept()
        start=0
        return words.mapIndexed {k,w->
            val strokes=clusters.subList(start,cuts[k]).flatMap {it.strokes};start=cuts[k]
            if(HandwritingRestyle.words(strokes,w.text!!)==null)return kept()
            HandwritingRestyle.Word(w.text,strokes)
        }
    }
}
