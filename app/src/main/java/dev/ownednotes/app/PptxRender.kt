package dev.ownednotes.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.*
import android.text.style.*
import dev.ownednotes.core.OfficeArchive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.*

/** Local, bounded static slide conversion. Unsupported content is reported, never sent to a converter. */
internal class PptxRender(private val archive: OfficeArchive, private val index: OfficeDocumentIndex) {
    data class Result(val file: File, val warnings: List<String>)
    private data class Part(val path: String, val root: OfficeXml.Node, val links: Map<String,OfficeDocumentIndex.Companion.Link>)
    private val warnings = linkedSetOf("Exact Office font and layout fidelity; tablet fonts may substitute for presentation fonts")
    private val parts = object : LinkedHashMap<String,Part>(8,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Part>?) = size > 4
    }
    private var decodedPixels = 0L
    private var shapes = 0
    private var characters = 0L
    private var colors = emptyMap<String,Int>()
    private var aliases = emptyMap<String,String>()
    private var theme: OfficeXml.Node? = null
    private val defaultTextStyle by lazy { part(index.mainPart).root.child("defaultTextStyle") }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun part(path: String): Part = parts.getOrPut(path) {
        val parent = path.substringBeforeLast('/',"")
        val rels = (if(parent.isEmpty()) "" else "$parent/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        Part(path,OfficeXml.tree(archive.readPart(path)),if(rels in archive.parts) OfficeDocumentIndex.links(archive,rels) else emptyMap())
    }
    private fun related(owner: Part, kind: String): Part? {
        val links = owner.links.values.filter { OfficeDocumentIndex.isType(it,kind) }
        if (links.isEmpty()) return null
        require(links.size == 1 && !links.single().external) { "Unsupported Office relationship" }
        return part(OfficeDocumentIndex.target(owner.path,links.single().target))
    }
    fun render(cache: File): Result {
        require(index.kind == OfficeDocumentIndex.Kind.SLIDES)
        val width = (index.widthEmu / EMU).roundToInt(); val height = (index.heightEmu / EMU).roundToInt()
        require(width in 1..14400 && height in 1..14400) { "Unsupported presentation page size" }
        val output = File.createTempFile("office-slides-", ".pdf",cache)
        try {
            val pdf=PdfDocument()
            try {
                index.slides.forEachIndexed { number,path ->
                    interrupted()
                    val slide = part(path)
                    require(slide.root.name == "sld" && slide.root.ns in P) { "Invalid presentation slide" }
                    val layout = related(slide,"slideLayout"); val master = layout?.let { related(it,"slideMaster") }
                    val themePart = master?.let { related(it,"theme") }
                    theme = themePart?.root?.child("themeElements")
                    colors = theme?.child("clrScheme")?.children?.mapNotNull { color ->
                        val value = color.children.firstOrNull()?.let { rawColor(it) }
                        value?.let { color.name to it }
                    }?.toMap().orEmpty()
                    aliases = master?.root?.child("clrMap")?.attributes.orEmpty()
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(width,height,number+1).create())
                    try {
                        val canvas = page.canvas
                        val background = slide.root.child("cSld")?.child("bg") ?: layout?.root?.child("cSld")?.child("bg") ?: master?.root?.child("cSld")?.child("bg")
                        canvas.drawColor(background?.let { fill(it.child("bgPr") ?: it.child("bgRef")) } ?: Color.WHITE)
                        if (slide.root.attr("showMasterSp") !in setOf("0","false")) {
                            master?.let { renderTree(canvas,it,it.root.child("cSld")?.child("spTree"),emptyList(),null,true) }
                            layout?.let { renderTree(canvas,it,it.root.child("cSld")?.child("spTree"),emptyList(),master,true) }
                        }
                        renderTree(canvas,slide,slide.root.child("cSld")?.child("spTree"),listOfNotNull(layout,master),master,false)
                        if (slide.root.child("timing") != null || slide.root.child("transition") != null) warnings.add("Slide animation and transitions (static pages only)")
                        if (slide.root.attr("show") in setOf("0","false")) warnings.add("Hidden slides are included in their original order")
                    } finally { pdf.finishPage(page) }
                }
                output.outputStream().use { raw ->
                    val limited = object : OutputStream() {
                        var count = 0L
                        override fun write(value: Int) { ensure(1); raw.write(value) }
                        override fun write(bytes: ByteArray, off: Int, len: Int) { ensure(len); raw.write(bytes,off,len) }
                        fun ensure(size: Int) { count += size; require(count <= dev.ownednotes.core.PdfAsset.MAX_BYTES) { "Rendered presentation exceeds the current PDF import limit" } }
                    }
                    pdf.writeTo(limited)
                }
            } finally { pdf.close() }
            return Result(output,warnings.toList())
        } catch (error: Throwable) { output.delete(); throw error }
    }
    private fun renderTree(canvas: Canvas, owner: Part, tree: OfficeXml.Node?, fallbacks: List<Part>, master: Part?, static: Boolean) {
        tree?.children?.forEach { node ->
            interrupted()
            if (node.name in setOf("nvGrpSpPr","grpSpPr","extLst")) return@forEach
            if (++shapes > 20000) throw IOException("Presentation has too many drawing objects")
            if (node.ns !in P) { warnings.add("Objects in unsupported XML namespaces"); return@forEach }
            if (static && placeholder(node) != null) return@forEach
            when(node.name) {
                "sp","cxnSp" -> drawShape(canvas,node,fallbacks,master)
                "pic" -> drawPicture(canvas,owner,node)
                "grpSp" -> {
                    val x = node.child("grpSpPr")?.child("xfrm")
                    val off=x?.child("off"); val ext=x?.child("ext"); val childOff=x?.child("chOff"); val childExt=x?.child("chExt")
                    if (x == null || childExt == null || childExt.number("cx") <= 0 || childExt.number("cy") <= 0) warnings.add("Groups without usable transform coordinates")
                    else { canvas.save(); try {
                        canvas.translate(off?.number("x")?.div(EMU) ?: 0f,off?.number("y")?.div(EMU) ?: 0f)
                        val w=(ext?.number("cx") ?: 0f)/EMU; val h=(ext?.number("cy") ?: 0f)/EMU
                        canvas.rotate(x.number("rot")/60000f,w/2,h/2)
                        canvas.scale(if(x.attr("flipH") in setOf("1","true")) -1f else 1f,if(x.attr("flipV") in setOf("1","true")) -1f else 1f,w/2,h/2)
                        canvas.scale((ext?.number("cx") ?: 0f)/childExt.number("cx"),(ext?.number("cy") ?: 0f)/childExt.number("cy"))
                        canvas.translate(-(childOff?.number("x") ?: 0f)/EMU,-(childOff?.number("y") ?: 0f)/EMU)
                        renderTree(canvas,owner,node,fallbacks,master,static)
                    } finally { canvas.restore() } }
                }
                "graphicFrame" -> {
                    val data=node.child("graphic")?.child("graphicData")
                    val table=data?.child("tbl")
                    if(table != null) drawTable(canvas,node,table)
                    else if(data?.child("chart") != null) drawChart(canvas,owner,node,data.child("chart")!!)
                    else warnings.add("Diagrams and other embedded slide objects")
                }
                "AlternateContent" -> warnings.add("Alternate Office drawing content")
                else -> warnings.add("Some embedded slide objects")
            }
        }
    }
    private fun placeholder(shape: OfficeXml.Node): OfficeXml.Node? = shape.children.firstOrNull { it.name.startsWith("nv") }?.child("nvPr")?.child("ph")
    private fun drawChart(canvas: Canvas, owner: Part, frame: OfficeXml.Node, reference: OfficeXml.Node) {
        val id=REL.firstNotNullOfOrNull { reference.attr("{$it}id") }
        val link=id?.let { owner.links[it] }
        if(link == null || link.external || !OfficeDocumentIndex.isType(link,"chart")) {
            warnings.add("Linked external or unsupported charts (not downloaded)"); return
        }
        val root=part(OfficeDocumentIndex.target(owner.path,link.target)).root
        val chart=try { PptxChart.read(root) } catch(error: PptxChart.Unsupported) { warnings.add(error.message.orEmpty()); return }
        warnings.add("Chart appearance is approximate and uses saved data only; workbooks and links are not refreshed")
        val palette=if(chart.circular) chart.categories.mapIndexed { i,_ ->
            fill(chart.points.getOrNull(i)) ?: colors["accent${i%6+1}"] ?: Color.BLUE
        } else chart.series.mapIndexed { i,series -> fill(if(chart.line && !chart.area) series.appearance?.child("ln") else series.appearance) ?: colors["accent${i%6+1}"] ?: Color.BLUE }
        transformed(canvas,frame.child("xfrm")) { w,h ->
            if(w<100 || h<80) { warnings.add("Charts too small for readable static conversion"); return@transformed }
            if(PptxChart.draw(canvas,chart,w,h,palette,chart.series.mapIndexed { i,series -> fill(series.markerAppearance) ?: palette[i] })) warnings.add("Some chart labels shortened or sampled to fit")
        }
    }
    private data class TableCell(val node: OfficeXml.Node,val row: Int,val column: Int,val rowSpan: Int,val columnSpan: Int)
    private fun drawTable(canvas: Canvas, frame: OfficeXml.Node, table: OfficeXml.Node) {
        if(frame.child("xfrm")?.child("off") == null || frame.child("xfrm")?.child("ext") == null) {
            warnings.add("Tables without usable position or size"); return
        }
        val columns=table.child("tblGrid")?.all("gridCol").orEmpty().map { it.number("w")/EMU }
        val rows=table.all("tr"); val heights=rows.map { it.number("h")/EMU }
        if(columns.isEmpty() || columns.size > 200 || rows.isEmpty() || rows.size > 2000 || rows.size.toLong()*columns.size > 20000 ||
            (columns+heights).any { it <= 0 || it > 100000 }) {
            warnings.add("Tables with invalid or oversized grids"); return
        }
        val covered=Array(rows.size) { BooleanArray(columns.size) }; val cells=ArrayList<TableCell>()
        fun span(node: OfficeXml.Node,name: String)=node.attr(name)?.toIntOrNull() ?: if(node.attr(name)==null) 1 else 0
        for((r,row) in rows.withIndex()) {
            val entries=row.all("tc")
            if(entries.size != columns.size) { warnings.add("Tables with incomplete cell grids"); return }
            for((c,cell) in entries.withIndex()) {
                val continued=cell.attr("hMerge") in setOf("1","true") || cell.attr("vMerge") in setOf("1","true")
                if(covered[r][c]) {
                    if(!continued) { warnings.add("Tables with inconsistent cell merges"); return }; continue
                }
                val rs=span(cell,"rowSpan"); val cs=span(cell,"gridSpan")
                if(continued || rs !in 1..rows.size-r || cs !in 1..columns.size-c) { warnings.add("Tables with inconsistent cell merges"); return }
                for(rr in r until r+rs) for(cc in c until c+cs) {
                    if(covered[rr][cc]) { warnings.add("Tables with overlapping cell merges"); return }
                    covered[rr][cc]=true
                }
                cells.add(TableCell(cell,r,c,rs,cs))
            }
        }
        val xs=FloatArray(columns.size+1); val ys=FloatArray(rows.size+1)
        columns.forEachIndexed { i,v -> xs[i+1]=xs[i]+v }; heights.forEachIndexed { i,v -> ys[i+1]=ys[i]+v }
        if(!xs.last().isFinite() || !ys.last().isFinite()) throw IOException("Invalid presentation table dimensions")
        if(table.child("tblPr")?.child("tableStyleId") != null) warnings.add("Exact theme table styles (cell content uses a simple grid where no explicit style is stored)")
        transformed(canvas,frame.child("xfrm")) { w,h ->
            if(w <= 0 || h <= 0) { warnings.add("Tables without usable display size"); return@transformed }
            canvas.scale(w/xs.last(),h/ys.last())
            for(cell in cells) {
                interrupted()
                val left=xs[cell.column]; val top=ys[cell.row]
                val width=xs[cell.column+cell.columnSpan]-left; val height=ys[cell.row+cell.rowSpan]-top
                val props=cell.node.child("tcPr")
                canvas.save()
                try {
                    canvas.translate(left,top); canvas.clipRect(0f,0f,width,height)
                    fill(props)?.let { value -> paint.reset(); paint.color=value; canvas.drawRect(0f,0f,width,height,paint) }
                    for((edge,points) in listOf("lnL" to floatArrayOf(0f,0f,0f,height),"lnR" to floatArrayOf(width,0f,width,height),"lnT" to floatArrayOf(0f,0f,width,0f),"lnB" to floatArrayOf(0f,height,width,height))) {
                        val line=props?.child(edge)
                        if(line?.child("noFill") != null) continue
                        paint.reset(); paint.isAntiAlias=true; paint.color=fill(line) ?: Color.GRAY
                        paint.strokeWidth=(line?.number("w",6350f) ?: 6350f).coerceAtLeast(0f)/EMU
                        if(line?.child("prstDash")?.attr("val") !in listOf(null,"solid")) paint.pathEffect=DashPathEffect(floatArrayOf(maxOf(.25f,paint.strokeWidth)*4,maxOf(.25f,paint.strokeWidth)*3),0f)
                        canvas.drawLine(points[0],points[1],points[2],points[3],paint)
                    }
                    cell.node.child("txBody")?.let { original ->
                        val originalBody=original.child("bodyPr")
                        val attrs=originalBody?.attributes.orEmpty().toMutableMap()
                        for((margin,inset) in listOf("marL" to "lIns","marR" to "rIns","marT" to "tIns","marB" to "bIns")) props?.attr(margin)?.let { attrs[inset]=it }
                        props?.attr("anchor")?.let { attrs["anchor"]=it }
                        val body=OfficeXml.Node(original.name,original.ns,original.attributes).apply {
                            children.add(OfficeXml.Node("bodyPr",originalBody?.ns.orEmpty(),attrs).apply { children.addAll(originalBody?.children.orEmpty()) })
                            children.addAll(original.children.filter { it.name != "bodyPr" })
                        }
                        if(props?.children?.any { it.name !in setOf("lnL","lnR","lnT","lnB","noFill","solidFill") } == true) warnings.add("Advanced table cell effects and diagonal borders")
                        text(canvas,body,emptyList(),null,null,width,height)
                    }
                } finally { canvas.restore() }
            }
        }
    }
    private fun inherited(shape: OfficeXml.Node, sources: List<Part>): List<OfficeXml.Node> {
        val ph = placeholder(shape) ?: return emptyList()
        return sources.mapNotNull { source ->
            val candidates = source.root.child("cSld")?.child("spTree")?.children.orEmpty().filter { placeholder(it) != null }
            val idx = ph.attr("idx") ?: "0"
            candidates.firstOrNull { (placeholder(it)?.attr("idx") ?: "0") == idx }
                ?: candidates.firstOrNull { val type=ph.attr("type") ?: "obj"; (placeholder(it)?.attr("type") ?: "obj") == if(type == "obj") "body" else if(type == "ctrTitle") "title" else type }
        }
    }
    private fun transformed(canvas: Canvas, transform: OfficeXml.Node?, action: (Float,Float) -> Unit) {
        val off=transform?.child("off"); val ext=transform?.child("ext")
        if (off == null || ext == null) { warnings.add("Objects without usable position or size"); return }
        val x=off.number("x")/EMU; val y=off.number("y")/EMU; val w=ext.number("cx")/EMU; val h=ext.number("cy")/EMU
        if (w < 0 || h < 0 || maxOf(abs(x),abs(y),w,h) > 100000f) throw IOException("Invalid presentation object coordinates")
        canvas.save()
        try {
            canvas.translate(x+w/2,y+h/2); canvas.rotate(transform.number("rot")/60000f)
            canvas.scale(if(transform.attr("flipH") in setOf("1","true")) -1f else 1f,if(transform.attr("flipV") in setOf("1","true")) -1f else 1f)
            canvas.translate(-w/2,-h/2); action(w,h)
        } finally { canvas.restore() }
    }
    private fun drawShape(canvas: Canvas, shape: OfficeXml.Node, fallbackParts: List<Part>, master: Part?) {
        val chain=listOf(shape)+inherited(shape,fallbackParts)
        val properties=chain.mapNotNull { it.child("spPr") }
        val transforms=properties.mapNotNull { it.child("xfrm") }
        val transform=transforms.firstOrNull()?.let {
            OfficeXml.Node("xfrm",it.ns,transforms.asReversed().flatMap { node -> node.attributes.entries.map { entry -> entry.key to entry.value } }.toMap()).apply {
                listOf("off","ext").forEach { name -> transforms.firstNotNullOfOrNull { node -> node.child(name) }?.let { children.add(it) } }
            }
        }
        transformed(canvas,transform) { w,h ->
            val props=properties.firstOrNull()
            val geometry=properties.firstNotNullOfOrNull { it.child("prstGeom") ?: it.child("custGeom") }
            val kind=geometry?.takeIf { it.name == "prstGeom" }?.attr("prst") ?: if(shape.name == "cxnSp") "line" else "rect"
            val path=Path()
            when(kind) {
                "rect" -> path.addRect(0f,0f,w,h,Path.Direction.CW)
                "roundRect" -> path.addRoundRect(0f,0f,w,h,minOf(w,h)/6,minOf(w,h)/6,Path.Direction.CW)
                "ellipse" -> path.addOval(0f,0f,w,h,Path.Direction.CW)
                "triangle" -> { path.moveTo(w/2,0f); path.lineTo(w,h); path.lineTo(0f,h); path.close() }
                "diamond" -> { path.moveTo(w/2,0f); path.lineTo(w,h/2); path.lineTo(w/2,h); path.lineTo(0f,h/2); path.close() }
                "line","straightConnector1" -> { path.moveTo(0f,0f); path.lineTo(w,h) }
                else -> warnings.add("Some custom shape outlines")
            }
            if(geometry?.name == "custGeom") { path.reset(); warnings.add("Custom shape paths") }
            val style=chain.firstNotNullOfOrNull { it.child("style") }
            val fillProps=properties.firstOrNull { p -> p.children.any { it.name in setOf("noFill","solidFill","gradFill","pattFill","blipFill") } }
            val color=if(fillProps?.child("noFill") != null) null else fill(fillProps) ?: style?.child("fillRef")?.let { fillReference(it) }
            if(color != null && kind != "line" && kind != "straightConnector1") { paint.reset(); paint.isAntiAlias=true; paint.color=color; canvas.drawPath(path,paint) }
            val line=properties.firstNotNullOfOrNull { it.child("ln") }
            val stroke=if(line?.child("noFill") != null) null else fill(line) ?: style?.child("lnRef")?.let { color(it) }
            if(stroke != null) { paint.reset(); paint.isAntiAlias=true; paint.color=stroke; paint.style=Paint.Style.STROKE; paint.strokeWidth=(line?.number("w",12700f) ?: 12700f)/EMU
                if(line?.child("prstDash")?.attr("val") !in listOf(null,"solid")) paint.pathEffect=DashPathEffect(floatArrayOf(maxOf(.25f,paint.strokeWidth)*4,maxOf(.25f,paint.strokeWidth)*3),0f)
                canvas.drawPath(path,paint)
            }
            if(line?.child("headEnd") != null || line?.child("tailEnd") != null) warnings.add("Arrowhead styles")
            if(props?.children?.any { it.name in setOf("effectLst","effectDag","scene3d","sp3d") } == true) warnings.add("Shadows, glow and 3D effects")
            val body=shape.child("txBody")
            if(style?.child("effectRef")?.number("idx")?.let { it > 0 } == true) warnings.add("Theme shadows and drawing effects")
            if(body != null && w > 0 && h > 0) text(canvas,body,chain.drop(1).mapNotNull { it.child("txBody") },master?.root?.child("txStyles"),placeholder(shape)?.let { it.attr("type") ?: "obj" },w,h,style?.child("fontRef"))
        }
    }
    private fun drawPicture(canvas: Canvas, owner: Part, picture: OfficeXml.Node) {
        val blip=picture.child("blipFill")?.child("blip")
        val id=REL.firstNotNullOfOrNull { blip?.attr("{$it}embed") }
        val link=id?.let { owner.links[it] }
        if(link == null || link.external || !OfficeDocumentIndex.isType(link,"image")) { warnings.add("Linked external or unsupported pictures (not downloaded)"); return }
        val name=OfficeDocumentIndex.target(owner.path,link.target)
        val bytes=archive.readPart(name,OfficeArchive.PART_BYTES)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0) { warnings.add("Picture formats unavailable on this tablet"); return }
        if(bounds.outWidth.toLong()*bounds.outHeight > 100_000_000L) throw IOException("Presentation picture exceeds the pixel limit")
        var sample=1
        while(bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample) > 1_000_000) sample *= 2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: throw IOException("Presentation picture could not be decoded")
        try {
            decodedPixels += bitmap.width.toLong()*bitmap.height
            if(decodedPixels > 64_000_000L) throw IOException("Presentation pictures exceed the current conversion memory budget")
            transformed(canvas,picture.child("spPr")?.child("xfrm")) { w,h ->
                val crop=picture.child("blipFill")?.child("srcRect")
                val l=(crop?.number("l") ?: 0f)/100000f; val t=(crop?.number("t") ?: 0f)/100000f
                val r=1f-(crop?.number("r") ?: 0f)/100000f; val b=1f-(crop?.number("b") ?: 0f)/100000f
                if(l < 0 || t < 0 || r > 1 || b > 1 || r <= l || b <= t) { warnings.add("Nonstandard picture crop"); return@transformed }
                val source=Rect((l*bitmap.width).roundToInt(),(t*bitmap.height).roundToInt(),(r*bitmap.width).roundToInt(),(b*bitmap.height).roundToInt())
                paint.reset(); paint.isFilterBitmap=true; canvas.drawBitmap(bitmap,source,RectF(0f,0f,w,h),paint)
            }
            if(blip?.children?.isNotEmpty() == true) warnings.add("Picture colour and transparency effects")
        } finally { bitmap.recycle() }
    }
    private fun fillReference(ref: OfficeXml.Node): Int? {
        val index=ref.attr("idx")?.toIntOrNull() ?: return color(ref)
        if(index == 0) return null
        val items=theme?.child("fmtScheme")?.child("fillStyleLst")?.children
        return items?.getOrNull(index-1)?.let { if(it.name == "solidFill") color(it,color(ref)) else { warnings.add("Gradient or pattern theme fills"); color(ref) } } ?: color(ref)
    }
    private fun fill(node: OfficeXml.Node?): Int? {
        if(node == null || node.child("noFill") != null) return null
        node.child("solidFill")?.let { return color(it) }
        if(node.name in setOf("bgRef","solidFill")) return color(node)
        if(node.child("gradFill") != null || node.child("pattFill") != null || node.child("blipFill") != null) warnings.add("Gradient, pattern or picture fills")
        return null
    }
    private fun rawColor(node: OfficeXml.Node): Int? = when(node.name) {
        "srgbClr" -> node.attr("val")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLong(16)?.let { (it or 0xff000000L).toInt() }
        "sysClr" -> node.attr("lastClr")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLong(16)?.let { (it or 0xff000000L).toInt() }
        else -> null
    }
    private fun color(parent: OfficeXml.Node, placeholder: Int? = null): Int? {
        val node=parent.children.firstOrNull { it.name.endsWith("Clr") } ?: return null
        var value=rawColor(node) ?: if(node.name == "schemeClr") {
            val key=node.attr("val").orEmpty()
            if(key == "phClr") placeholder else colors[aliases[key] ?: when(key) { "bg1" -> "lt1"; "bg2" -> "lt2"; "tx1" -> "dk1"; "tx2" -> "dk2"; else -> key }]
        } else null
        if(value == null) { warnings.add("Unresolved theme or special colours"); value=Color.BLACK }
        node.child("alpha")?.let { value=(value!! and 0x00ffffff) or ((it.number("val",100000f).coerceIn(0f,100000f)/100000f*255).roundToInt() shl 24) }
        for(change in node.children.filter { it.name != "alpha" }) {
            val amount=change.number("val",100000f).coerceIn(0f,100000f)/100000f
            if(change.name in setOf("tint","shade")) {
                fun channel(c: Int): Int {
                    val srgb=c/255.0
                    val linear=if(srgb <= .04045) srgb/12.92 else ((srgb+.055)/1.055).pow(2.4)
                    val mixed=if(change.name == "tint") linear*amount+1-amount else linear*amount
                    val result=if(mixed <= .0031308) mixed*12.92 else 1.055*mixed.pow(1/2.4)-.055
                    return (result*255).roundToInt().coerceIn(0,255)
                }
                value=Color.argb(Color.alpha(value!!),channel(Color.red(value!!)),channel(Color.green(value!!)),channel(Color.blue(value!!)))
            } else warnings.add("Advanced colour transforms")
        }
        return value
    }
    private fun text(canvas: Canvas, body: OfficeXml.Node, inherited: List<OfficeXml.Node>, styles: OfficeXml.Node?, kind: String?, width: Float, height: Float, fontRef: OfficeXml.Node? = null) {
        val bodyChain=(listOf(body)+inherited).mapNotNull { it.child("bodyPr") }
        fun bodySetting(key: String) = bodyChain.firstNotNullOfOrNull { it.attr(key) }
        fun inset(key: String, default: Float) = (bodySetting(key)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default)/EMU
        if(bodySetting("vert") !in listOf(null,"horz")) warnings.add("Vertical Office text")
        val left=inset("lIns",91440f); val right=inset("rIns",91440f)
        val top=inset("tIns",45720f); val bottom=inset("bIns",45720f)
        val fontScale=(bodyChain.firstNotNullOfOrNull { it.child("normAutofit") }?.number("fontScale",100000f) ?: 100000f).coerceIn(1000f,100000f)/100000f
        val fontDefaults=fontRef?.let { ref -> OfficeXml.Node("defRPr",ref.ns,emptyMap()).apply {
            children.add(OfficeXml.Node("solidFill",ref.ns,emptyMap()).apply { children.addAll(ref.children) })
            theme?.child("fontScheme")?.child(if(ref.attr("idx") == "major") "majorFont" else "minorFont")?.child("latin")?.let { children.add(it) }
        } }
        val text=SpannableStringBuilder()
        body.all("p").forEachIndexed { number,p ->
            val start=text.length
            val props=p.child("pPr"); val level=props?.attr("lvl")?.toIntOrNull()?.coerceIn(0,8) ?: 0
            val levelName="lvl${level+1}pPr"
            val masterStyle=styles?.child(when(kind) { "title","ctrTitle" -> "titleStyle"; "body","obj","subTitle" -> "bodyStyle"; else -> "otherStyle" })
            val localParagraphs=listOfNotNull(props)+(listOf(body)+inherited).mapNotNull { it.child("lstStyle") }.flatMap { listOfNotNull(it.child(levelName),it.child("defPPr")) }
            val baseParagraphs=listOfNotNull(masterStyle,defaultTextStyle).flatMap { listOfNotNull(it.child(levelName),it.child("defPPr")) }
            val paragraphChain=localParagraphs+baseParagraphs
            fun paragraphSetting(key: String) = paragraphChain.firstNotNullOfOrNull { it.attr(key) }
            val bulletSpec=paragraphChain.firstNotNullOfOrNull { paragraph -> paragraph.children.firstOrNull { it.name in setOf("buNone","buChar","buAutoNum") } }
            val bullet=bulletSpec?.takeIf { it.name == "buChar" }?.attr("char")
            if(bullet != null) text.append(bullet).append(" ")
            if(bulletSpec?.name == "buAutoNum") { text.append("${number+1}. "); warnings.add("Advanced automatic list numbering") }
            for(run in p.children) {
                if(run.name == "br") { text.append('\n'); continue }
                if(run.name !in setOf("r","fld")) continue
                val value=run.child("t")?.text.orEmpty(); if(value.isEmpty()) continue
                val from=text.length; text.append(value)
                val rp=run.child("rPr"); val runChain=listOfNotNull(rp)+localParagraphs.mapNotNull { it.child("defRPr") }+listOfNotNull(fontDefaults)+baseParagraphs.mapNotNull { it.child("defRPr") }
                fun setting(key: String) = runChain.firstNotNullOfOrNull { it.attr(key) }
                val size=((setting("sz")?.toFloatOrNull()?.takeIf { it.isFinite() }?.div(100f) ?: 18f)*fontScale).coerceIn(1f,400f)
                val bold=setting("b") in setOf("1","true"); val italic=setting("i") in setOf("1","true")
                text.setSpan(AbsoluteSizeSpan(size.roundToInt()),from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan((if(bold) Typeface.BOLD else 0) or (if(italic) Typeface.ITALIC else 0)),from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val fill=runChain.firstNotNullOfOrNull { it.child("solidFill") }
                var family=runChain.firstNotNullOfOrNull { it.child("latin")?.attr("typeface") }
                if(family?.startsWith("+m") == true) family=theme?.child("fontScheme")?.child(if(family.startsWith("+mj")) "majorFont" else "minorFont")?.child("latin")?.attr("typeface")
                val lower=family.orEmpty().lowercase(java.util.Locale.ROOT)
                val portable=when { lower in setOf("times new roman","times","cambria","georgia","serif") -> "serif"; lower.contains("courier") || lower.contains("mono") || lower == "consolas" -> "monospace"; else -> "sans-serif" }
                text.setSpan(TypefaceSpan(portable),from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                (fill?.let { color(it) } ?: fontRef?.let { color(it) })?.let { text.setSpan(ForegroundColorSpan(it),from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                if(setting("u") !in listOf(null,"none")) text.setSpan(UnderlineSpan(),from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(rp?.child("hlinkClick") != null) warnings.add("Interactive hyperlinks (static text only)")
                if(run.name == "fld") warnings.add("Dynamic text fields (saved text only)")
            }
            if(number < body.all("p").lastIndex) text.append('\n')
            val alignment=when(paragraphSetting("algn")) { "ctr" -> Layout.Alignment.ALIGN_CENTER; "r" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            if(text.length > start) text.setSpan(AlignmentSpan.Standard(alignment),start,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if(paragraphChain.any { paragraph -> paragraph.children.any { it.name in setOf("lnSpc","spcBef","spcAft") } }) warnings.add("Exact paragraph spacing")
        }
        characters += text.length
        if(characters > 2_000_000) throw IOException("Presentation text exceeds the conversion limit")
        if(text.isEmpty()) return
        val available=(width-left-right).roundToInt()
        if(available <= 0 || height-top-bottom <= 0) { warnings.add("Text boxes without usable inner space"); return }
        val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=18f; typeface=Typeface.DEFAULT }
        val layout=StaticLayout.Builder.obtain(text,0,text.length,textPaint,available).setIncludePad(false).build()
        val free=height-top-bottom-layout.height
        val y=top+when(bodySetting("anchor")) { "ctr" -> free/2; "b" -> free; else -> 0f }
        if(free < -1) warnings.add("Overflowing Office text (clipped to its text box)")
        canvas.save(); try { canvas.clipRect(0f,0f,width,height); canvas.translate(left,y); layout.draw(canvas) } finally { canvas.restore() }
    }
    private fun interrupted() { if(Thread.currentThread().isInterrupted) throw IOException("Office import cancelled") }
    companion object {
        private const val EMU=12700f
        private val REL=listOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships","http://purl.oclc.org/ooxml/officeDocument/relationships")
        private val P=setOf("http://schemas.openxmlformats.org/presentationml/2006/main","http://purl.oclc.org/ooxml/presentationml/main")
    }
}
