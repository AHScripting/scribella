package dev.ownednotes.app

import dev.ownednotes.core.OfficeArchive
import java.io.IOException

/** Saved section headers/footers. Only PAGE is evaluated; every other field stays a reported cache. */
internal class DocxRunningMatter(
    private val archive: OfficeArchive,private val mainPart: String,
    private val links: Map<String,OfficeDocumentIndex.Companion.Link>,private val evenOdd: Boolean,
    private val warnings: MutableSet<String>,
) {
    data class Part(val path: String,val root: OfficeXml.Node,val footer: Boolean,val distance: Float)
    private val refs=mutableMapOf<Pair<String,String>,String?>()
    private val roots=HashMap<String,OfficeXml.Node>()
    private var first=1; private var start=1; private var title=false
    private var headerDistance=36f; private var footerDistance=36f; private var format="decimal"
    fun number(physical: Int)=start+physical-first
    fun configure(props: OfficeXml.Node?,physicalFirst: Int) {
        val continued=number(physicalFirst)
        first=physicalFirst
        val rawStart=props?.child("pgNumType")?.w("start")
        start=if(rawStart==null) continued else rawStart.toIntOrNull() ?: throw IOException("Invalid Word page-number start")
        require(start in 1..1_000_000) { "Unsupported Word page-number start" }
        format=props?.child("pgNumType")?.w("fmt") ?: format
        title=props?.child("titlePg")?.let(::on) ?: false
        val margin=props?.child("pgMar")
        fun distance(name: String,old: Float): Float {
            val raw=margin?.w(name) ?: return old
            val n=raw.toFloatOrNull()?.div(20) ?: throw IOException("Invalid Word $name distance")
            require(n.isFinite() && n in 0f..14400f) { "Unsupported Word $name distance" }; return n
        }
        headerDistance=distance("header",headerDistance); footerDistance=distance("footer",footerDistance)
        for(role in listOf("header","footer")) for(ref in props?.all("${role}Reference").orEmpty()) {
            val type=ref.w("type") ?: "default"
            if(type !in setOf("default","first","even")) { warnings.add("Unknown header/footer section slot"); continue }
            val id=R.firstNotNullOfOrNull { ref.attr("{$it}id") }; val link=id?.let { links[it] }
            val key=role to type
            if(link==null || link.external || !OfficeDocumentIndex.isType(link,role)) {
                refs[key]=null; warnings.add("Invalid or external header/footer (not downloaded)"); continue
            }
            refs[key]=OfficeDocumentIndex.target(mainPart,link.target)
        }
    }
    fun selected(physical: Int): List<Part> {
        val slot=if(title && physical==first) "first" else if(evenOdd && number(physical)%2==0) "even" else "default"
        return listOf("header","footer").mapNotNull { role ->
            val path=refs[role to slot] ?: return@mapNotNull null
            val root=roots.getOrPut(path) {
                OfficeXml.tree(archive.readPart(path))
            }
            require(root.name==(if(role=="header") "hdr" else "ftr") && root.ns in W) { "Invalid Word $role part" }
            Part(path,root,role=="footer",if(role=="footer") footerDistance else headerDistance)
        }
    }
    fun fields(node: OfficeXml.Node,physical: Int): OfficeXml.Node {
        fun copy(n: OfficeXml.Node,children: List<OfficeXml.Node>)=OfficeXml.Node(n.name,n.ns,n.attributes).also { it.text=n.text; it.children.addAll(children) }
        fun descendants(n: OfficeXml.Node,name: String)=sequenceOf(n).plus(n.descendants(name)).filter { it.name==name }
        fun evaluated(command: String,cached: List<OfficeXml.Node>,ns: String): List<OfficeXml.Node>? {
            if(command.length>10000) throw IOException("Word field instruction exceeds the limit")
            val tokens=command.trim().split(Regex("\\s+"))
            if(!tokens.firstOrNull().equals("PAGE",ignoreCase=true)) { warnings.add("Header/footer fields other than PAGE (saved values only; not executed)"); return null }
            var style=format
            for(i in 1 until tokens.lastIndex) if(tokens[i]=="\\*") style=when(tokens[i+1]) {
                "ROMAN" -> "upperRoman"; "roman" -> "lowerRoman"; "ALPHABETIC" -> "upperLetter"; "alphabetic" -> "lowerLetter"; "Arabic" -> "decimal"; "MERGEFORMAT" -> style
                else -> { warnings.add("Custom PAGE field formats (section numbering used)"); style }
            }
            val n=number(physical)
            val value=DocxNumbering.format(n,style,warnings)
            val run=OfficeXml.Node("r",ns,emptyMap())
            cached.asSequence().flatMap { descendants(it,"r") }.firstOrNull()?.child("rPr")?.let { run.children.add(it) }
            run.children.add(OfficeXml.Node("t",ns,emptyMap()).also { it.text=value }); return listOf(run)
        }
        fun rewrite(n: OfficeXml.Node): OfficeXml.Node {
            val out=ArrayList<OfficeXml.Node>(); var i=0
            while(i<n.children.size) {
                val child=n.children[i]
                if(child.name=="fldSimple") {
                    val value=evaluated(child.w("instr").orEmpty(),child.children,child.ns)
                    out.addAll(value ?: listOf(child)); i++; continue
                }
                fun kind(r: OfficeXml.Node)=if(r.name=="r") r.child("fldChar")?.w("fldCharType") else null
                if(kind(child)=="begin") {
                    var depth=0; var end=i; var separate=-1; var nested=false
                    while(end<n.children.size) {
                        when(kind(n.children[end])) {
                            "begin" -> { depth++; if(depth>1) nested=true }
                            "separate" -> if(depth==1) separate=end
                            "end" -> { depth--; if(depth==0) break }
                        }
                        end++
                    }
                    if(end>=n.children.size || separate<0 || nested) {
                        warnings.add("Nested or malformed header/footer fields (saved values only)")
                        val last=minOf(end+1,n.children.size); out.addAll(n.children.subList(i,last)); i=last; continue
                    }
                    val command=n.children.subList(i,separate).asSequence().flatMap { descendants(it,"instrText") }.joinToString("") { it.text }
                    val cached=n.children.subList(separate+1,end)
                    val value=evaluated(command,cached,child.ns)
                    out.addAll(value ?: n.children.subList(i,end+1)); i=end+1; continue
                }
                out.add(rewrite(child)); i++
            }
            return copy(n,out)
        }
        return rewrite(node)
    }
    companion object {
        private val W=setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main")
        private val R=listOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships","http://purl.oclc.org/ooxml/officeDocument/relationships")
        private fun OfficeXml.Node.w(name: String)=W.firstNotNullOfOrNull { attr("{$it}$name") } ?: attr(name)
        fun on(node: OfficeXml.Node)=node.w("val") !in setOf("0","false","off")
    }
}
