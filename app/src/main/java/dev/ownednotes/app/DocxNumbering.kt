package dev.ownednotes.app

import java.io.IOException
import java.util.Locale

/** Saved numbering only: no external parts, fields, or picture-bullet execution. */
internal class DocxNumbering(root: OfficeXml.Node?,private val warnings: MutableSet<String>) {
    data class Marker(val text: String="",val paragraph: OfficeXml.Node?=null,val run: OfficeXml.Node?=null)
    private fun indexed(nodes: List<OfficeXml.Node>,key: String): Map<String,OfficeXml.Node> {
        val result=LinkedHashMap<String,OfficeXml.Node>()
        for(node in nodes) { val id=node.w(key) ?: throw IOException("Missing Word numbering identifier")
            require(result.put(id,node)==null) { "Duplicate Word numbering identifier" } }
        return result
    }
    private val instances=indexed(root?.all("num").orEmpty(),"numId")
    private val abstracts=indexed(root?.all("abstractNum").orEmpty(),"abstractNumId")
    private data class Level(val node: OfficeXml.Node,val start: Int,val restart: Int,val format: String)
    private data class ListState(val levels: Map<Int,Level>,val counts: IntArray=IntArray(9) { -1 })
    private val lists=HashMap<String,ListState?>()
    private fun integer(raw: String?,default: Int): Int {
        if(raw==null) return default
        return raw.toIntOrNull()?.takeIf { it in 0..1_000_000 } ?: throw IOException("Invalid Word list counter")
    }
    private fun levels(nodes: List<OfficeXml.Node>): Map<Int,OfficeXml.Node> {
        val result=LinkedHashMap<Int,OfficeXml.Node>()
        for(node in nodes) {
            val level=integer(node.w("ilvl"),0)
            require(level in 0..8 && result.put(level,node)==null) { "Invalid or duplicate Word list level" }
        }
        return result
    }
    private fun resolve(id: String): ListState? {
        val num=instances[id] ?: return null
        val abstract=abstracts[num.child("abstractNumId")?.w("val")] ?: return null
        if(abstract.child("numStyleLink")!=null) warnings.add("Style-linked list numbering (direct saved levels only)")
        val definitions=levels(abstract.all("lvl")); val overrides=levels(num.all("lvlOverride"))
        return ListState((definitions.keys+overrides.keys).mapNotNull { index ->
            val override=overrides[index]; val node=override?.child("lvl") ?: definitions[index] ?: return@mapNotNull null
            require(integer(node.w("ilvl"),index)==index) { "Mismatched Word numbering override" }
            val start=integer(override?.child("startOverride")?.w("val"),integer(node.child("start")?.w("val"),0))
            val restart=integer(node.child("lvlRestart")?.w("val"),index)
            // Invalid later-level restart references are ignored: use the default previous level.
            val trigger=if(restart in 0..index) restart else index
            index to Level(node,start,trigger,node.child("numFmt")?.w("val") ?: "decimal")
        }.toMap())
    }
    fun marker(props: List<OfficeXml.Node>): Marker {
        fun property(key: String)=props.firstNotNullOfOrNull { it.child("numPr")?.child(key)?.w("val") }
        val id=property("numId") ?: return Marker()
        if(id=="0") return Marker()
        val level=integer(property("ilvl"),0); require(level in 0..8) { "Invalid Word paragraph list level" }
        val list=if(lists.containsKey(id)) lists[id] else resolve(id).also { lists[id]=it }
        val current=list?.levels?.get(level)
        if(list==null || current==null) { warnings.add("Unresolved list numbering (a bullet is shown)"); return Marker("• ") }
        val value=if(list.counts[level]<0) current.start else list.counts[level]+1
        require(value<=1_000_000) { "Word list counter exceeds the limit" }; list.counts[level]=value
        for((index,definition) in list.levels) if(index>level && definition.restart==level+1) list.counts[index]=-1
        val node=current.node
        if(node.child("lvlPicBulletId")!=null) warnings.add("Picture list markers (saved text or portable bullet used)")
        if(node.child("lvlJc")?.w("val") !in setOf(null,"left","start")) warnings.add("Exact list marker alignment")
        val legal=node.child("isLgl")?.let { it.w("val") !in setOf("0","false","off") } ?: false
        var text=if(node.child("lvlText")?.w("null")=="1") "" else node.child("lvlText")?.w("val") ?: if(current.format=="bullet") "•" else "%${level+1}."
        require(text.length<=4096) { "Word list marker exceeds the limit" }
        text=Regex("%([1-9])").replace(text) { match ->
            val ref=match.groupValues[1].toInt()-1
            if(ref>level) "" else {
                val definition=list.levels[ref]
                if(definition==null) { warnings.add("Missing referenced Word list level (decimal used)"); "0" }
                else format(if(list.counts[ref]<0) definition.start else list.counts[ref],if(legal) "decimal" else definition.format,warnings)
            }
        }
        if(current.format=="none") text=""
        if(current.format=="bullet" && text.any { it.code in 0xE000..0xF8FF }) { text="•"; warnings.add("Symbol-font list markers (portable bullets used)") }
        val suffix=when(node.child("suff")?.w("val")) { "nothing" -> ""; "space" -> " "; null,"tab" -> { warnings.add("Exact list tabs and hanging marker layout"); "\t" }; else -> { warnings.add("Unknown list suffix (space used)"); " " } }
        require(text.length<=4096) { "Word list marker exceeds the limit" }
        return Marker(if(text.isEmpty()) "" else text+suffix,node.child("pPr"),node.child("rPr"))
    }
    companion object {
        private val W=listOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main")
        private fun OfficeXml.Node.w(name: String)=W.firstNotNullOfOrNull { attr("{$it}$name") } ?: attr(name)
        fun format(value: Int,style: String,warnings: MutableSet<String>): String {
            fun fallback(reason: String): String { warnings.add(reason); return value.toString() }
            fun letters(): String {
                if(value<1 || (value-1)/26+1>1024) return fallback("Alphabetic numbering outside supported range (decimal used)")
                return ('A'+(value-1)%26).toString().repeat((value-1)/26+1)
            }
            fun roman(): String {
                if(value !in 1..3999) return fallback("Roman numbering outside1–3999 (decimal used)")
                var rest=value; val out=StringBuilder()
                for((n,s) in listOf(1000 to "M",900 to "CM",500 to "D",400 to "CD",100 to "C",90 to "XC",50 to "L",40 to "XL",10 to "X",9 to "IX",5 to "V",4 to "IV",1 to "I")) while(rest>=n) { out.append(s); rest-=n }
                return out.toString()
            }
            return when(style) {
                "decimal" -> value.toString(); "decimalZero" -> value.toString().padStart(2,'0')
                "upperRoman" -> roman(); "lowerRoman" -> roman().lowercase(Locale.ROOT)
                "upperLetter" -> letters(); "lowerLetter" -> letters().lowercase(Locale.ROOT)
                "none" -> ""; "bullet" -> "•"
                else -> fallback("Unsupported list number format $style (decimal used)")
            }
        }
    }
}
