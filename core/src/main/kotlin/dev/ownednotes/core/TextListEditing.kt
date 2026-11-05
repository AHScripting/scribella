package dev.ownednotes.core

/** Visible list markers are plain notebook text, not a second rich-text format. */
object TextListEditing {
    enum class Kind { BULLET, NUMBER, CHECKLIST }
    data class Result(val text:String,val selectionStart:Int,val selectionEnd:Int)
    private data class Line(val start:Int,val end:Int,val indent:Int,val marker:String?,val markerLength:Int) {
        val kind get()=when { marker=="•" -> Kind.BULLET;marker=="☐" || marker=="☑" -> Kind.CHECKLIST;marker!=null -> Kind.NUMBER;else -> null }
    }
    private data class Op(val start:Int,val end:Int,val replacement:String)
    private val breaks=Regex("\r\n|[\r\n]")
    private val marker=Regex("^(•|[0-9]+[.)]|[☐☑])([\t ]+)")
    private fun selected(text:String,start:Int,end:Int):List<Line> {
        require(start in 0..text.length && end in 0..text.length)
        val ranges=breaks.findAll(text).map { it.range }.toList()
        val starts=listOf(0)+ranges.map { it.last+1 }
        fun index(position:Int)=starts.binarySearch(position).let { if(it>=0)it else -it-2 }
        val low=minOf(start,end);val high=maxOf(start,end)
        return (index(low)..index(if(high>low)high-1 else high)).map { i ->
            val first=starts[i];val last=ranges.getOrNull(i)?.first ?: text.length
            val value=text.substring(first,last);val indent=value.indexOfFirst { it!=' ' && it!='\t' }.let { if(it<0)value.length else it }
            val found=marker.find(value.substring(indent))
            Line(first,last,indent,found?.groupValues?.get(1),found?.value?.length ?: 0)
        }
    }
    fun apply(text:String,start:Int,end:Int,kind:Kind):Result {
        val lines=selected(text,start,end)
        val chosen=lines.filter { it.marker!=null || text.substring(it.start+it.indent,it.end).isNotBlank() || lines.size==1 }
        val remove=chosen.isNotEmpty() && chosen.all { it.kind==kind }
        val edits=chosen.mapIndexed { i,line ->
            val prefix=if(remove)"" else when(kind){Kind.BULLET->"• ";Kind.NUMBER->"${i+1}. ";Kind.CHECKLIST->"☐ "}
            Op(line.start+line.indent,line.start+line.indent+line.markerLength,prefix)
        }
        return edit(text,start,end,edits)
    }
    fun toggleChecks(text:String,start:Int,end:Int):Result {
        val lines=selected(text,start,end).filter { it.kind==Kind.CHECKLIST }
        val mark=if(lines.isNotEmpty() && lines.all { it.marker=="☑" })"☐" else "☑"
        return edit(text,start,end,lines.map { Op(it.start+it.indent,it.start+it.indent+1,mark) })
    }
    private fun edit(text:String,start:Int,end:Int,ops:List<Op>):Result {
        val result=buildString { var from=0;ops.forEach { append(text,from,it.start);append(it.replacement);from=it.end };append(text,from,text.length) }
        fun mapped(position:Int):Int {
            var delta=0
            for(op in ops) {
                if(position<op.start)break
                if(position<=op.end)return op.start+delta+op.replacement.length
                delta+=op.replacement.length-(op.end-op.start)
            }
            return position+delta
        }
        return Result(result,mapped(start),mapped(end))
    }
}
