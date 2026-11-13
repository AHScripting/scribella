package dev.ownednotes.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.*
import android.text.style.*
import dev.ownednotes.core.OfficeArchive
import dev.ownednotes.core.PdfAsset
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.*

/** Local static Word layout. Unsupported Office content is retained in the original and reported. */
internal class DocxRender(private val archive: OfficeArchive,private val index: OfficeDocumentIndex) {
    data class Result(val file: File,val pages: Int,val warnings: List<String>)
    private val warnings=linkedSetOf("Exact Word fonts, layout and pagination (device fonts and local reflow are used)")
    private val bitmaps=ArrayList<Bitmap>()
    private var decodedPixels=0L
    private var characters=0
    private var blocks=0
    private val partLinks=HashMap<String,Map<String,OfficeDocumentIndex.Companion.Link>>()
    private var drawingPart: String?=null
    private val bitmapByPart=HashMap<String,Bitmap>()
    private fun linksFor(part: String): Map<String,OfficeDocumentIndex.Companion.Link> = partLinks.getOrPut(part) {
        val parent=part.substringBeforeLast('/',"")
        val path=(if(parent.isEmpty()) "" else "$parent/")+"_rels/"+part.substringAfterLast('/')+".rels"
        if(path in archive.parts) OfficeDocumentIndex.links(archive,path) else emptyMap()
    }
    private val links by lazy { linksFor(index.mainPart) }
    private val running by lazy { DocxRunningMatter(archive,index.mainPart,links,related("settings")?.child("evenAndOddHeaders")?.let { DocxRunningMatter.on(it) } ?: false,warnings) }
    private fun related(type: String): OfficeXml.Node? {
        val selected=links.values.filter { OfficeDocumentIndex.isType(it,type) }
        if(selected.isEmpty()) return null
        require(selected.size==1 && !selected.single().external) { "Unsupported linked Word $type part" }
        return OfficeXml.tree(archive.readPart(OfficeDocumentIndex.target(index.mainPart,selected.single().target))).also {
            require(it.name==type && (if(type=="theme") it.ns in A else it.ns in W)) { "Unsupported Word $type structure" }
        }
    }
    private val styleRoot by lazy { related("styles") }
    private val styles by lazy { styleRoot?.all("style")?.associateBy { it.w("styleId").orEmpty() }.orEmpty() }
    private val defaultStyle by lazy { styles.values.firstOrNull { it.w("type")=="paragraph" && it.w("default") in setOf("1","true","on") } }
    private val defaultParagraph by lazy { styleRoot?.child("docDefaults")?.child("pPrDefault")?.child("pPr") }
    private val defaultRun by lazy { styleRoot?.child("docDefaults")?.child("rPrDefault")?.child("rPr") }
    private val themeFonts by lazy { related("theme")?.child("themeElements")?.child("fontScheme") }
    private val numbering by lazy { related("numbering") }
    private val lists by lazy { DocxNumbering(numbering,warnings) }
    private fun OfficeXml.Node.w(name: String): String? = W.firstNotNullOfOrNull { attr("{$it}$name") } ?: attr(name)
    private fun OfficeXml.Node.n(name: String,default: Float=0f)=w(name)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
    private fun chain(id: String?): List<OfficeXml.Node> {
        val found=ArrayList<OfficeXml.Node>(); val seen=HashSet<String>(); var next=id
        while(next != null) {
            if(!seen.add(next) || seen.size>32) throw IOException("Word styles contain a cycle or excessive inheritance")
            val style=styles[next] ?: break
            found.add(style); next=style.child("basedOn")?.w("val")
        }
        return found
    }
    private data class Geometry(val width: Int=612,val height: Int=792,val left: Float=72f,val top: Float=72f,val right: Float=72f,val bottom: Float=72f) {
        val contentWidth get()=width-left-right
        val contentHeight get()=height-top-bottom
    }
    private var geometry=Geometry()
    private var pdf: PdfDocument?=null
    private var page: PdfDocument.Page?=null
    private var pages=0
    private var y=0f
    private var content=false
    private fun canvas(): Canvas {
        if(page==null) {
            if(pages>=1999) throw IOException("Word document exceeds the current page limit")
            page=pdf!!.startPage(PdfDocument.PageInfo.Builder(geometry.width,geometry.height,++pages).create())
            page!!.canvas.drawColor(Color.WHITE); y=geometry.top; content=false
            drawRunningMatter(page!!.canvas)
        }
        return page!!.canvas
    }
    private fun finishPage() { page?.let { pdf!!.finishPage(it) }; page=null; content=false }
    private fun pageBreak() { canvas(); finishPage() }
    private fun interrupted() { if(Thread.currentThread().isInterrupted) throw IOException("Office import cancelled") }
    fun render(cache: File): Result {
        require(index.kind==OfficeDocumentIndex.Kind.WORD)
        val root=OfficeXml.tree(archive.readPart(index.mainPart))
        require(root.name=="document" && root.ns in W) { "Invalid Word document" }
        val body=root.child("body") ?: throw IOException("Word document has no body")
        if(links.values.any { link -> listOf("footnotes","endnotes","comments").any { OfficeDocumentIndex.isType(link,it) } })
            warnings.add("Footnotes, endnotes and comments")
        val output=File.createTempFile("office-word-",".pdf",cache)
        try {
            pdf=PdfDocument()
            try {
                val section=ArrayList<OfficeXml.Node>()
                var first=true
                fun renderSection(properties: OfficeXml.Node?) {
                    val before=geometry
                    geometry=sectionGeometry(properties,geometry)
                    if(!first && (properties?.child("type")?.w("val") != "continuous" || geometry != before)) finishPage()
                    if(page!=null && geometry != before) finishPage()
                    if(page!=null && properties?.children?.any { it.name in setOf("headerReference","footerReference","titlePg","pgNumType") }==true) warnings.add("Continuous-section running matter changes (applied to subsequent pages)")
                    running.configure(properties,if(page==null) pages+1 else pages)
                    first=false
                    renderBlocks(section); section.clear()
                }
                for(block in body.children) {
                    if(block.name=="sectPr") { renderSection(block); continue }
                    section.add(block)
                    block.takeIf { it.name=="p" }?.child("pPr")?.child("sectPr")?.let { renderSection(it) }
                }
                if(section.isNotEmpty() || first) renderSection(null)
                if(page==null && pages==0) canvas()
                finishPage()
                output.outputStream().use { raw -> pdf!!.writeTo(object: OutputStream() {
                    var bytes=0L
                    private fun reserve(size: Int) { bytes+=size; if(bytes>PdfAsset.MAX_BYTES) throw IOException("Rendered Word document exceeds the current PDF import limit") }
                    override fun write(value: Int) { reserve(1); raw.write(value) }
                    override fun write(data: ByteArray,offset: Int,count: Int) { reserve(count); raw.write(data,offset,count) }
                }) }
            } finally { page?.let { pdf?.finishPage(it) }; page=null; pdf?.close(); pdf=null }
            return Result(output,pages,warnings.toList())
        } catch(error: Throwable) { output.delete(); throw error }
        finally { bitmaps.forEach { it.recycle() }; bitmaps.clear(); bitmapByPart.clear() }
    }
    private fun drawRunningMatter(canvas: Canvas) {
        for(part in running.selected(pages)) {
            val previous=drawingPart; drawingPart=part.path
            try {
                data class Line(val layout: StaticLayout,val left: Float,val before: Float,val after: Float)
                val lines=part.root.children.mapNotNull { block ->
                    if(block.name!="p") { warnings.add("Header/footer tables and non-paragraph blocks"); return@mapNotNull null }
                    if(++blocks>20000) throw IOException("Word document exceeds the block limit")
                    val node=running.fields(block,pages); val (props,runs)=properties(node)
                    val left=twips(props,"ind","left").coerceIn(0f,geometry.contentWidth-20)
                    val right=twips(props,"ind","right").coerceIn(0f,geometry.contentWidth-left-10)
                    val width=geometry.contentWidth-left-right
                    val before=twips(props,"spacing","before").coerceIn(0f,geometry.height.toFloat())
                    val after=twips(props,"spacing","after").coerceIn(0f,geometry.height.toFloat())
                    Line(layout(richText(node,runs,width),props,width,runs),left,before,after)
                }
                val height=lines.sumOf { (it.layout.height+it.before+it.after).toDouble() }.toFloat()
                val top=if(part.footer) geometry.height-part.distance-height else part.distance
                val limitTop=if(part.footer) geometry.height-geometry.bottom else 0f
                val limitBottom=if(part.footer) geometry.height.toFloat() else geometry.top
                if(top<limitTop || top+height>limitBottom) warnings.add("Header/footer content outside saved margins (clipped to margin area)")
                canvas.save()
                try {
                    canvas.clipRect(geometry.left,limitTop,geometry.width-geometry.right,limitBottom)
                    var offset=top
                    for(line in lines) {
                        offset+=line.before; canvas.save()
                        try { canvas.translate(geometry.left+line.left,offset); line.layout.draw(canvas) } finally { canvas.restore() }
                        offset+=line.layout.height+line.after
                    }
                } finally { canvas.restore() }
            } finally { drawingPart=previous }
        }
    }
    private fun sectionGeometry(props: OfficeXml.Node?, previous: Geometry): Geometry {
        val size=props?.child("pgSz"); val margin=props?.child("pgMar")
        val next=Geometry(size?.n("w",previous.width*20f)?.div(20)?.roundToInt() ?: previous.width,
            size?.n("h",previous.height*20f)?.div(20)?.roundToInt() ?: previous.height,
            margin?.n("left",previous.left*20)?.div(20) ?: previous.left,margin?.n("top",previous.top*20)?.div(20) ?: previous.top,
            margin?.n("right",previous.right*20)?.div(20) ?: previous.right,margin?.n("bottom",previous.bottom*20)?.div(20) ?: previous.bottom)
        require(next.width in 40..14400 && next.height in 40..14400 && listOf(next.left,next.top,next.right,next.bottom).all { it>=0 } && next.contentWidth>=30 && next.contentHeight>=30) { "Unsupported Word page size or margins" }
        if(props?.child("cols")?.n("num",1f)?.let { it>1 }==true) warnings.add("Multiple-column sections (shown in one column)")
        if(props?.children?.any { it.name in setOf("pgBorders","textDirection","docGrid","vAlign") }==true) warnings.add("Advanced section and page formatting")
        return next
    }
    private fun renderBlock(node: OfficeXml.Node) {
        if(++blocks>20000) throw IOException("Word document has too many content blocks")
        when(node.name) {
            "p" -> paragraph(node)
            "tbl" -> table(node)
            "sdt" -> node.child("sdtContent")?.children?.forEach(::renderBlock)
            "ins" -> { warnings.add("Tracked changes shown as saved final text"); node.children.forEach(::renderBlock) }
            "del" -> warnings.add("Tracked changes shown as saved final text")
            "bookmarkStart","bookmarkEnd","proofErr","permStart","permEnd" -> Unit
            else -> warnings.add("Some embedded or unsupported Word blocks")
        }
    }
    private fun properties(paragraph: OfficeXml.Node): Pair<List<OfficeXml.Node>,List<OfficeXml.Node>> {
        val own=paragraph.child("pPr")
        val inherited=chain(own?.child("pStyle")?.w("val") ?: defaultStyle?.w("styleId"))
        return (listOfNotNull(own)+inherited.mapNotNull { it.child("pPr") }+listOfNotNull(defaultParagraph)) to
            (inherited.mapNotNull { it.child("rPr") }+listOfNotNull(defaultRun))
    }
    private fun setting(props: List<OfficeXml.Node>,key: String,attribute: String="val")=props.firstNotNullOfOrNull { it.child(key)?.w(attribute) }
    private fun enabled(props: List<OfficeXml.Node>,key: String)=props.firstNotNullOfOrNull { it.child(key) }?.let { it.w("val") !in setOf("0","false","off") } ?: false
    private fun twips(props: List<OfficeXml.Node>,key: String,attribute: String,default: Float=0f)=setting(props,key,attribute)?.toFloatOrNull()?.takeIf { it.isFinite() }?.div(20) ?: default
    private fun numberedProperties(node: OfficeXml.Node,base: List<OfficeXml.Node>,marker: DocxNumbering.Marker): List<OfficeXml.Node> {
        val split=if(node.child("pPr")!=null) 1 else 0
        return base.take(split)+listOfNotNull(marker.paragraph)+base.drop(split)
    }
    private data class PreparedParagraph(
        val layouts: List<StaticLayout>, val left: Float, val before: Float, val after: Float,
        val breakBefore: Boolean, val keepLines: Boolean, val keepNext: Boolean
    ) {
        val height: Float get() = before + layouts.sumOf { it.height }.toFloat() + after
    }
    private fun explicitPageBreak(node: OfficeXml.Node) = node.descendants("br").any { it.w("type") == "page" }
    /** Only direct paragraph chains share pagination; forced breaks and structural blocks take precedence. */
    private fun renderBlocks(nodes: List<OfficeXml.Node>) {
        var start = 0
        while (start < nodes.size) {
            interrupted()
            var end = start
            if (nodes[start].name == "p" && !explicitPageBreak(nodes[start])) {
                while (end + 1 < nodes.size && nodes[end + 1].name == "p" &&
                    enabled(properties(nodes[end]).first, "keepNext") &&
                    !enabled(properties(nodes[end + 1]).first, "pageBreakBefore") && !explicitPageBreak(nodes[end + 1])) end++
            }
            if (end == start) { renderBlock(nodes[start]); start++; continue }
            // Once a chain exceeds one page, its start must move but its remaining layouts need not be retained.
            val prepared = ArrayList<PreparedParagraph>()
            var height = 0f; var next = start
            while (next <= end) {
                if (++blocks > 20000) throw IOException("Word document has too many content blocks")
                val paragraph = prepareParagraph(nodes[next++]); prepared += paragraph; height += paragraph.height
                if (height > geometry.contentHeight) break
            }
            canvas()
            if (content && height > geometry.height - geometry.bottom - y) finishPage()
            prepared.forEach(::drawParagraph)
            while (next <= end) {
                if (++blocks > 20000) throw IOException("Word document has too many content blocks")
                drawParagraph(prepareParagraph(nodes[next++]))
            }
            start = end + 1
        }
    }
    private fun prepareParagraph(node: OfficeXml.Node): PreparedParagraph {
        interrupted()
        val (base,runs)=properties(node)
        val marker=lists.marker(base); val props=numberedProperties(node,base,marker)
        val left=twips(props,"ind","left").coerceIn(0f,geometry.contentWidth-20)
        val right=twips(props,"ind","right").coerceIn(0f,geometry.contentWidth-left-10)
        val text=richText(node,runs,geometry.contentWidth-left-right,marker.text,marker.run)
        return PreparedParagraph(splitPages(text).map { layout(it,props,geometry.contentWidth-left-right,runs) },
            left, twips(props,"spacing","before").coerceIn(0f,geometry.contentHeight),
            twips(props,"spacing","after").coerceIn(0f,geometry.contentHeight),
            enabled(base,"pageBreakBefore"),enabled(props,"keepLines"),enabled(props,"keepNext"))
    }
    private fun paragraph(node: OfficeXml.Node) {
        val prepared = prepareParagraph(node)
        drawParagraph(prepared)
        if (prepared.keepNext) warnings.add("Keep-with-next across explicit breaks or non-paragraph blocks")
    }
    private fun drawParagraph(paragraph: PreparedParagraph) {
        if (paragraph.breakBefore && content) pageBreak()
        val before = paragraph.before
        canvas(); if(content && y+before>=geometry.height-geometry.bottom) finishPage(); canvas(); y+=minOf(before,geometry.height-geometry.bottom-y)
        for((i,layout) in paragraph.layouts.withIndex()) {
            if(paragraph.keepLines && content && layout.height<=geometry.contentHeight && y+layout.height>geometry.height-geometry.bottom) { finishPage(); canvas() }
            emit(layout,geometry.left+paragraph.left)
            if(i<paragraph.layouts.lastIndex) pageBreak()
        }
        y=minOf(geometry.height-geometry.bottom,y+paragraph.after)
    }
    private fun splitPages(text: CharSequence): List<CharSequence> {
        val result=ArrayList<CharSequence>(); var start=0
        for(i in text.indices) if(text[i]=='\u000c') { result.add(text.subSequence(start,i)); start=i+1 }
        result.add(text.subSequence(start,text.length)); return result
    }
    private fun layout(text: CharSequence,props: List<OfficeXml.Node>,width: Float,runs: List<OfficeXml.Node>): StaticLayout {
        val size=setting(runs,"sz")?.toFloatOrNull()?.takeIf { it.isFinite() }?.div(2)?.coerceIn(1f,400f) ?: 11f
        val value=SpannableStringBuilder(if(text.isEmpty()) " " else text)
        val rawFirst=twips(props,"ind","firstLine")-twips(props,"ind","hanging")
        val first=rawFirst.coerceIn(-width,width-1)
        if(first!=rawFirst) warnings.add("Paragraph indents outside the usable text area")
        if(first!=0f) value.setSpan(LeadingMarginSpan.Standard(first.roundToInt(),0),0,value.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val alignment=when(setting(props,"jc")) { "center" -> Layout.Alignment.ALIGN_CENTER; "right","end" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=size; typeface=Typeface.create("serif",Typeface.NORMAL) }
        val builder=StaticLayout.Builder.obtain(value,0,value.length,paint,maxOf(1,width.roundToInt())).setAlignment(alignment).setIncludePad(false)
        val line=setting(props,"spacing","line")?.toFloatOrNull()?.takeIf { it.isFinite() }
        if(line!=null) {
            if(setting(props,"spacing","lineRule") in listOf(null,"auto")) builder.setLineSpacing(0f,(line/240).coerceIn(.5f,5f))
            else { warnings.add("Exact and minimum paragraph line spacing"); builder.setLineSpacing(maxOf(0f,line/20-size),1f) }
        }
        if(setting(props,"jc") in setOf("both","distribute")) builder.setJustificationMode(android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }
    private fun emit(layout: StaticLayout,x: Float) {
        var line=0
        while(line<layout.lineCount) {
            interrupted(); canvas()
            val top=layout.getLineTop(line); val lineHeight=layout.getLineBottom(line)-top
            if(y+lineHeight>geometry.height-geometry.bottom && (content || y>geometry.top+.01f)) { finishPage(); continue }
            val scale=minOf(1f,geometry.contentHeight/lineHeight.coerceAtLeast(1))
            if(scale<1) warnings.add("Very tall text or pictures reduced to fit a page")
            var end=line+1
            while(scale==1f && end<layout.lineCount && layout.getLineBottom(end)-top<=geometry.height-geometry.bottom-y) end++
            val height=(layout.getLineBottom(end-1)-top)*scale
            val canvas=canvas(); canvas.save()
            try { canvas.clipRect(geometry.left,y,geometry.width-geometry.right,y+height); canvas.translate(x,y-top*scale); canvas.scale(scale,scale); layout.draw(canvas) }
            finally { canvas.restore() }
            y+=height; content=true; line=end
            if(line<layout.lineCount) finishPage()
        }
    }
    private fun richText(paragraph: OfficeXml.Node,defaults: List<OfficeXml.Node>,width: Float,prefix: String="",markerProperties: OfficeXml.Node?=null): CharSequence {
        val text=SpannableStringBuilder()
        fun run(node: OfficeXml.Node) {
            val own=node.child("rPr"); val props=listOfNotNull(own)+chain(own?.child("rStyle")?.w("val")).mapNotNull { it.child("rPr") }+defaults
            if(enabled(props,"vanish")) return
            val start=text.length
            for(child in node.children) when(child.name) {
                "t" -> text.append(child.text)
                "tab" -> text.append('\t')
                "br" -> text.append(if(child.w("type")=="page") '\u000c' else '\n')
                "cr" -> text.append('\n')
                "noBreakHyphen" -> text.append('\u2011')
                "softHyphen" -> text.append('\u00ad')
                "drawing" -> picture(child,width)?.let { span -> val from=text.length; text.append('\ufffc'); text.setSpan(span,from,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "rPr","lastRenderedPageBreak" -> Unit
                "instrText","fldChar" -> warnings.add("Dynamic fields shown as saved text only")
                "footnoteReference","endnoteReference","commentReference" -> warnings.add("Footnote, endnote and comment references")
                else -> warnings.add("Some run objects, equations or embedded Word content")
            }
            if(text.length==start) return
            val size=setting(props,"sz")?.toFloatOrNull()?.takeIf { it.isFinite() }?.div(2)?.coerceIn(1f,400f) ?: 11f
            fun span(value: Any) { text.setSpan(value,start,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            span(AbsoluteSizeSpan(size.roundToInt())); span(StyleSpan((if(enabled(props,"b")) Typeface.BOLD else 0) or (if(enabled(props,"i")) Typeface.ITALIC else 0)))
            val themeFont=setting(props,"rFonts","asciiTheme") ?: setting(props,"rFonts","hAnsiTheme")
            val family=setting(props,"rFonts","ascii") ?: setting(props,"rFonts","hAnsi")
                ?: themeFont?.let { themeFonts?.child(if(it.startsWith("major")) "majorFont" else "minorFont")?.child("latin")?.attr("typeface") }
            val lower=family.orEmpty().lowercase(java.util.Locale.ROOT)
            span(TypefaceSpan(when { lower.contains("courier") || lower.contains("mono") || lower=="consolas" -> "monospace"; lower in setOf("arial","calibri","aptos","helvetica","sans-serif") -> "sans-serif"; else -> "serif" }))
            hex(setting(props,"color"))?.let { span(ForegroundColorSpan(it)) }
            if(setting(props,"u") !in listOf(null,"none")) span(UnderlineSpan())
            if(enabled(props,"strike") || enabled(props,"dstrike")) span(StrikethroughSpan())
            when(setting(props,"vertAlign")) { "superscript" -> { span(SuperscriptSpan()); span(RelativeSizeSpan(.7f)) }; "subscript" -> { span(SubscriptSpan()); span(RelativeSizeSpan(.7f)) } }
            val highlight=setting(props,"highlight")
            val color=mapOf("yellow" to 0xffffff00.toInt(),"green" to 0xff00ff00.toInt(),"cyan" to 0xff00ffff.toInt(),"magenta" to 0xffff00ff.toInt(),"blue" to Color.BLUE,"red" to Color.RED,"lightGray" to Color.LTGRAY)[highlight]
                ?: hex(setting(props,"shd","fill"))
            color?.let { span(BackgroundColorSpan(it)) }
            if(props.any { it.child("color")?.w("themeColor") != null || it.child("rFonts")?.w("asciiTheme") != null }) warnings.add("Exact theme fonts and colours")
        }
        fun walk(node: OfficeXml.Node) {
            for(child in node.children) when(child.name) {
                "r" -> run(child)
                "hyperlink","fldSimple","smartTag" -> { warnings.add("Interactive links and fields (saved text only)"); walk(child) }
                "sdt" -> child.child("sdtContent")?.let { walk(it) }
                "ins" -> { warnings.add("Tracked changes shown as saved final text"); walk(child) }
                "del" -> warnings.add("Tracked changes shown as saved final text")
                "pPr","bookmarkStart","bookmarkEnd","proofErr","commentRangeStart","commentRangeEnd" -> Unit
                else -> warnings.add("Some paragraph objects or equations")
            }
        }
        if(prefix.isNotEmpty()) {
            val marker=OfficeXml.Node("r",paragraph.ns,emptyMap())
            markerProperties?.let { marker.children.add(it) }
            marker.children.add(OfficeXml.Node("t",paragraph.ns,emptyMap()).also { it.text=prefix })
            run(marker)
        }
        walk(paragraph); characters+=text.length
        if(characters>2_000_000) throw IOException("Word text exceeds the current conversion limit")
        return text
    }
    private class Picture(private val bitmap: Bitmap,private val source: Rect,private val width: Int,private val height: Int,
        private val drawWidth: Float,private val drawHeight: Float,private val centerX: Float,private val centerY: Float,private val rotation: Float,private val flipH: Boolean,private val flipV: Boolean): ReplacementSpan() {
        override fun getSize(paint: Paint,text: CharSequence,start: Int,end: Int,fm: Paint.FontMetricsInt?): Int {
            fm?.let { it.ascent=-height; it.top=-height; it.descent=0; it.bottom=0 }; return width
        }
        override fun draw(canvas: Canvas,text: CharSequence,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: Paint) {
            val copy=Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.save()
            try {
                canvas.clipRect(x,(y-height).toFloat(),x+width,y.toFloat())
                canvas.translate(x+centerX,y-height+centerY)
                canvas.rotate(rotation)
                canvas.scale(if(flipH) -1f else 1f,if(flipV) -1f else 1f)
                canvas.drawBitmap(bitmap,source,RectF(-drawWidth/2,-drawHeight/2,drawWidth/2,drawHeight/2),copy)
            } finally { canvas.restore() }
        }
    }
    private fun picture(drawing: OfficeXml.Node,availableWidth: Float): Picture? {
        val inline=drawing.child("inline")
        if(inline==null) { warnings.add("Floating pictures and drawing objects"); return null }
        val blip=inline.descendants("blip").firstOrNull()
        val id=R.firstNotNullOfOrNull { blip?.attr("{$it}embed") }
        val sourcePart=drawingPart ?: index.mainPart
        val link=id?.let { linksFor(sourcePart)[it] }
        if(link==null || link.external || !OfficeDocumentIndex.isType(link,"image")) { warnings.add("Linked external or unsupported drawings (not downloaded)"); return null }
        val path=OfficeDocumentIndex.target(sourcePart,link.target)
        val bitmap=bitmapByPart[path] ?: run {
        val bytes=archive.readPart(path,OfficeArchive.PART_BYTES)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) { warnings.add("Picture formats unavailable on this device"); return null }
        if(bounds.outWidth.toLong()*bounds.outHeight>100_000_000L) throw IOException("Word picture exceeds the pixel limit")
        var sample=1
        while(bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>1_000_000) sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: throw IOException("Word picture could not be decoded")
        bitmaps.add(bitmap); decodedPixels+=bitmap.width.toLong()*bitmap.height
        if(decodedPixels>64_000_000L) throw IOException("Word pictures exceed the conversion memory budget")
        bitmapByPart[path]=bitmap; bitmap
        }
        val extent=inline.child("extent")
        val w=(extent?.number("cx") ?: 0f)/12700f; val h=(extent?.number("cy") ?: 0f)/12700f
        if(w<=0 || h<=0 || w>100000 || h>100000) { warnings.add("Pictures without usable saved display size"); return null }
        val crop=inline.descendants("srcRect").firstOrNull()
        val l=(crop?.number("l") ?: 0f)/100000; val t=(crop?.number("t") ?: 0f)/100000
        val r=1-(crop?.number("r") ?: 0f)/100000; val b=1-(crop?.number("b") ?: 0f)/100000
        if(l<0 || t<0 || r>1 || b>1 || r<=l || b<=t) { warnings.add("Nonstandard picture crops"); return null }
        val transform=inline.descendants("pic").firstOrNull()?.child("spPr")?.child("xfrm")
        val rawRotation=transform?.attr("rot")
        val rotation=if(rawRotation==null) 0f else rawRotation.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it)<=2_147_483_647 }?.let { ((it/60000)%360).toFloat() }
        if(rotation==null) { warnings.add("Invalid inline picture rotation (picture not rendered)"); return null }
        val imageExtent=transform?.child("ext")
        val internalWidth=if(imageExtent==null) w else imageExtent.number("cx")/12700f
        val internalHeight=if(imageExtent==null) h else imageExtent.number("cy")/12700f
        if(internalWidth<=0 || internalHeight<=0 || internalWidth>100000 || internalHeight>100000) { warnings.add("Invalid inline picture transform size (picture not rendered)"); return null }
        if(abs(internalWidth-w)>1 || abs(internalHeight-h)>1) warnings.add("Different picture and inline extents (inline display size used)")
        // wp:extent is the displayed object size; effectExtent adds wrapping/line-height space.
        // Keep the saved padding rather than inventing a larger rotated page-layout box.
        val effects=inline.child("effectExtent")
        val padding=arrayOf("l","t","r","b").map { name ->
            val raw=effects?.attr(name)
            val value=if(raw==null) 0f else raw.toFloatOrNull()?.div(12700f)
            if(value==null || !value.isFinite() || value<0 || value>100000) throw IOException("Unsupported inline picture effect extent")
            value
        }
        val layoutWidth=w+padding[0]+padding[2]; val layoutHeight=h+padding[1]+padding[3]
        val scale=minOf(1f,availableWidth/layoutWidth,(geometry.contentHeight-5).coerceAtLeast(1f)/layoutHeight)
        if(scale<1) warnings.add("Large inline pictures reduced to fit the page")
        val radians=Math.toRadians(rotation.toDouble())
        val rotatedWidth=abs(cos(radians))*w+abs(sin(radians))*h
        val rotatedHeight=abs(sin(radians))*w+abs(cos(radians))*h
        if((rotatedWidth-w)/2>minOf(padding[0],padding[2])+1 || (rotatedHeight-h)/2>minOf(padding[1],padding[3])+1)
            warnings.add("Transformed pictures outside their saved effect extents (clipped to saved layout box)")
        if(transform?.child("off")?.let { it.number("x")!=0f || it.number("y")!=0f }==true) warnings.add("Nonzero inline picture offsets (centered fallback used)")
        if(blip?.children?.isNotEmpty()==true || inline.descendants("effectLst").any() || inline.descendants("effectDag").any()) warnings.add("Picture effects and colour adjustments")
        fun flip(name: String): Boolean {
            val value=transform?.attr(name)
            if(value!=null && value !in setOf("0","1","true","false")) warnings.add("Invalid picture flip value (unflipped fallback used)")
            return value in setOf("1","true")
        }
        val source=Rect((l*bitmap.width).roundToInt(),(t*bitmap.height).roundToInt(),(r*bitmap.width).roundToInt(),(b*bitmap.height).roundToInt())
        if(source.width()<=0 || source.height()<=0) { warnings.add("Picture crops smaller than a decoded pixel"); return null }
        return Picture(bitmap,source,maxOf(1,(layoutWidth*scale).roundToInt()),maxOf(1,(layoutHeight*scale).roundToInt()),w*scale,h*scale,(padding[0]+w/2)*scale,(padding[1]+h/2)*scale,rotation,flip("flipH"),flip("flipV"))
    }
    private data class Cell(val node: OfficeXml.Node,val column: Int,val span: Int,val layouts: List<StaticLayout>,val height: Float,val padding: FloatArray,val alignment: String,val row: Int,var rowSpan: Int=1)
    private fun cellPadding(cp: OfficeXml.Node?,table: OfficeXml.Node?): FloatArray {
        val local=cp?.child("tcMar"); val defaults=table?.child("tblCellMar")
        return arrayOf("left","top","right","bottom").mapIndexed { i,side ->
            fun edge(n: OfficeXml.Node?)=n?.child(side) ?: when(side) { "left" -> n?.child("start"); "right" -> n?.child("end"); else -> null }
            val value=edge(local) ?: edge(defaults)
            when(value?.w("type")) {
                "nil" -> 0f
                null,"dxa" -> if(value==null) (if(i%2==0) 5.5f else 4f) else value.n("w")/20
                else -> { warnings.add("Non-point cell margins (fallback padding used)"); if(i%2==0) 5.5f else 4f }
            }.also { if(!it.isFinite() || it<0 || it>10000) throw IOException("Word cell margin exceeds the layout budget") }
        }.toFloatArray()
    }
    private fun border(canvas: Canvas,node: OfficeXml.Node?,x1: Float,y1: Float,x2: Float,y2: Float,fallback: Boolean) {
        if(node==null && !fallback) return
        val style=node?.w("val") ?: "single"
        if(style in setOf("nil","none")) return
        val width=(node?.w("sz")?.toFloatOrNull()?.div(8) ?: .5f)
        if(!width.isFinite() || width<0 || width>100) throw IOException("Word table border exceeds the layout budget")
        if(width==0f) return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=hex(node?.w("color")) ?: if(fallback) Color.GRAY else Color.BLACK
            strokeWidth=width; this.style=Paint.Style.STROKE
        }
        when(style) {
            "single","double" -> Unit
            "dashed" -> paint.pathEffect=DashPathEffect(floatArrayOf(width*4,width*3),0f)
            "dotted" -> { paint.pathEffect=DashPathEffect(floatArrayOf(width,width*2),0f); paint.strokeCap=Paint.Cap.ROUND }
            else -> warnings.add("Advanced table border styles (single-line fallback used)")
        }
        if(node?.w("themeColor")!=null || node?.w("shadow")=="1" || node?.w("space")?.let { it!="0" }==true)
            warnings.add("Table border theme colours, shadows and spacing")
        if(style=="double") {
            // Word's size denotes the total composite border width.
            paint.strokeWidth=width/3
            val dx=if(x1==x2) width/3 else 0f; val dy=if(y1==y2) width/3 else 0f
            canvas.drawLine(x1-dx,y1-dy,x2-dx,y2-dy,paint); canvas.drawLine(x1+dx,y1+dy,x2+dx,y2+dy,paint)
        } else canvas.drawLine(x1,y1,x2,y2,paint)
    }
    private fun table(node: OfficeXml.Node) {
        interrupted()
        val rows=node.all("tr")
        if(rows.size>2000) throw IOException("Word table has too many rows")
        val grid=node.child("tblGrid")?.all("gridCol").orEmpty().map { it.n("w")/20 }
        val count=if(grid.isNotEmpty()) grid.size else rows.maxOfOrNull { row -> row.all("tc").sumOf { it.child("tcPr")?.child("gridSpan")?.w("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1 } } ?: 0
        if(count !in 1..200 || rows.size.toLong()*count>20000 || grid.any { it<=0 || it>100000 }) { warnings.add("Tables with unsupported grids"); return }
        val props=node.child("tblPr")
        warnings.add("Exact table layout, style inheritance and shared-border conflict resolution")
        if(props?.child("tblCellSpacing")!=null) warnings.add("Spacing between table cells")
        if(props?.child("tblStyle")!=null) warnings.add("Exact Word table theme styling (simple cell grid used)")
        val widthNode=props?.child("tblW")
        val width=when(widthNode?.w("type")) { "dxa" -> widthNode.n("w")/20; "pct" -> geometry.contentWidth*widthNode.n("w")/5000; else -> geometry.contentWidth }.coerceIn(20f,geometry.contentWidth)
        val widths=if(grid.isEmpty()) List(count) { width/count } else grid.map { it/grid.sum()*width }
        val offsets=FloatArray(count+1); widths.forEachIndexed { i,v -> offsets[i+1]=offsets[i]+v }
        val prepared=ArrayList<List<Cell>>()
        val rowHeights=FloatArray(rows.size)
        var active=emptyMap<Int,Cell>()
        for((rowIndex,row) in rows.withIndex()) {
            interrupted()
            if(row.child("trPr")?.child("gridBefore")?.n("val")?.let { it!=0f }==true || row.child("trPr")?.child("gridAfter")?.n("val")?.let { it!=0f }==true) {
                warnings.add("Tables with omitted leading/trailing grid cells (table not rendered)"); return
            }
            var column=0; val cells=ArrayList<Cell>(); val nextActive=mutableMapOf<Int,Cell>()
            for(cell in row.all("tc")) {
                val cp=cell.child("tcPr"); val span=cp?.child("gridSpan")?.w("val")?.toIntOrNull() ?: 1
                if(span<=0 || column+span>count) { warnings.add("Table rows with inconsistent column spans"); return }
                if(cp?.child("hMerge")!=null) { warnings.add("Legacy horizontal table merge encoding (table not rendered)"); return }
                val merge=cp?.child("vMerge")
                val kind=merge?.w("val") ?: if(merge!=null) "continue" else null
                if(kind!=null && kind !in setOf("restart","continue")) { warnings.add("Invalid vertical table merge (table not rendered)"); return }
                if(kind=="continue") {
                    val origin=active[column]
                    if(origin==null || origin.span!=span) { warnings.add("Invalid vertical table merge grid (table not rendered)"); return }
                    if(cell.children.any { it.name !in setOf("tcPr","p") } || cell.all("p").any { p -> p.children.any { it.name!="pPr" && !(it.name=="r" && it.children.all { c -> c.name=="rPr" }) } }) {
                        warnings.add("Nonempty vertical merge continuation (table not rendered)"); return
                    }
                    if(cp?.children?.any { it.name !in setOf("vMerge","gridSpan","tcW") }==true) warnings.add("Merge continuation cell formatting (origin cell formatting used)")
                    origin.rowSpan++; cells.add(origin); nextActive[column]=origin; column+=span; continue
                }
                val cellWidth=offsets[column+span]-offsets[column]
                val padding=cellPadding(cp,props)
                val innerWidth=(cellWidth-padding[0]-padding[2]).coerceAtLeast(1f)
                if(padding[0]+padding[2]>=cellWidth) warnings.add("Cell margins wider than the cell (text clipped)")
                val layouts=cell.all("p").map { p ->
                    val (pp,rp)=properties(p); val marker=lists.marker(pp)
                    layout(richText(p,rp,innerWidth,marker.text,marker.run),numberedProperties(p,pp,marker),innerWidth,rp)
                }
                val alignment=cp?.child("vAlign")?.w("val") ?: "top"
                if(alignment !in setOf("top","center","bottom")) warnings.add("Distributed cell vertical alignment (top fallback used)")
                if(cp?.child("textDirection")!=null || row.child("tblPrEx")!=null || props?.child("bidiVisual")!=null) warnings.add("Vertical/bidirectional table text and row property exceptions")
                if(cell.children.any { it.name !in setOf("tcPr","p") }) warnings.add("Nested tables and embedded cell blocks")
                val origin=Cell(cell,column,span,layouts,layouts.sumOf { it.height }.toFloat()+padding[1]+padding[3],padding,alignment,rowIndex)
                cells.add(origin)
                if(kind=="restart") nextActive[column]=origin
                column+=span
            }
            if(column!=count) { warnings.add("Table rows with incomplete grids (table not rendered)"); return }
            val minimum=row.child("trPr")?.child("trHeight")?.n("val")?.div(20) ?: 0f
            if(!minimum.isFinite() || minimum<0 || minimum>100000) throw IOException("Word table row exceeds the layout budget")
            rowHeights[rowIndex]=maxOf(12f,minimum,cells.filter { it.row==rowIndex && it.node.child("tcPr")?.child("vMerge")==null }.maxOfOrNull { it.height } ?: 0f)
            prepared.add(cells); active=nextActive
        }
        // Expand the last occupied row if a merged origin needs more space. Content is drawn once
        // in a continuous cell coordinate space, clipped into each occupied row/page fragment.
        for((rowIndex,cells) in prepared.withIndex()) for(cell in cells.filter { it.row==rowIndex }) {
            val occupied=(cell.row until cell.row+cell.rowSpan).sumOf { rowHeights[it].toDouble() }.toFloat()
            if(cell.height>occupied) rowHeights[cell.row+cell.rowSpan-1]+=cell.height-occupied
        }
        if(rowHeights.any { !it.isFinite() || it>100000 }) throw IOException("Word table row exceeds the layout budget")
        val rowOffsets=FloatArray(rows.size+1)
        rowHeights.forEachIndexed { i,height -> rowOffsets[i+1]=rowOffsets[i]+height }
        val requestedHeaders = rows.takeWhile { enabled(listOfNotNull(it.child("trPr")), "tblHeader") }.size
        val requestedHeight = rowHeights.take(requestedHeaders).sum()
        val headerCount = if (requestedHeaders > 0 &&
            (requestedHeight > geometry.contentHeight - 24f || prepared.take(requestedHeaders).flatten().any { it.rowSpan > 1 })) {
            warnings.add("Repeated table headers with vertical merges or insufficient room for body content"); 0
        } else requestedHeaders
        val headerHeight = if (headerCount > 0) requestedHeight else 0f
        val bodyBudget = geometry.contentHeight - headerHeight
        var headerPage = -1
        fun paintRow(rowIndex: Int, start: Float, portion: Float) {
            val height = rowHeights[rowIndex]; val cells = prepared[rowIndex]
            val canvas=canvas(); canvas.save()
            try {
                canvas.clipRect(geometry.left,y,geometry.left+width,y+portion)
                canvas.translate(geometry.left,y-start)
                for(cell in cells) {
                    val cellHeight=rowOffsets[cell.row+cell.rowSpan]-rowOffsets[cell.row]
                    canvas.save()
                    try {
                    canvas.clipRect(0f,0f,width,height)
                    canvas.translate(0f,-(rowOffsets[rowIndex]-rowOffsets[cell.row]))
                    val left=offsets[cell.column]; val right=offsets[cell.column+cell.span]
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                    val fill=hex(cell.node.child("tcPr")?.child("shd")?.w("fill"))
                    if(fill!=null) { paint.color=fill; canvas.drawRect(left,0f,right,cellHeight,paint) }
                    val explicit=cell.node.child("tcPr")?.child("tcBorders"); val tableBorders=props?.child("tblBorders")
                    fun edge(name: String,tableName: String)=explicit?.child(name) ?: when(name) { "left" -> explicit?.child("start"); "right" -> explicit?.child("end"); else -> null } ?: tableBorders?.child(tableName)
                    val fallback=explicit==null && tableBorders==null
                    border(canvas,edge("top",if(cell.row==0) "top" else "insideH"),left,0f,right,0f,fallback)
                    border(canvas,edge("bottom",if(cell.row+cell.rowSpan==rows.size) "bottom" else "insideH"),left,cellHeight,right,cellHeight,fallback)
                    border(canvas,edge("left",if(cell.column==0) "left" else "insideV"),left,0f,left,cellHeight,fallback)
                    border(canvas,edge("right",if(cell.column+cell.span==count) "right" else "insideV"),right,0f,right,cellHeight,fallback)
                    canvas.save(); try {
                        canvas.clipRect(left+cell.padding[0],cell.padding[1],maxOf(left+cell.padding[0],right-cell.padding[2]),maxOf(cell.padding[1],cellHeight-cell.padding[3]))
                        val extra=(cellHeight-cell.height).coerceAtLeast(0f)
                        val shift=when(cell.alignment) { "center" -> extra/2; "bottom" -> extra; else -> 0f }
                        canvas.translate(left+cell.padding[0],cell.padding[1]+shift)
                        for(layout in cell.layouts) { layout.draw(canvas); canvas.translate(0f,layout.height.toFloat()) }
                    } finally { canvas.restore() }
                    } finally { canvas.restore() }
                }
            } finally { canvas.restore() }
        }
        fun repeatHeaders(rowIndex: Int) {
            canvas()
            if (headerCount == 0 || rowIndex < headerCount || pages == headerPage) return
            for (header in 0 until headerCount) {
                interrupted(); paintRow(header, 0f, rowHeights[header])
                y += rowHeights[header]; content = true
            }
            headerPage = pages
        }
        if (headerCount > 0) {
            canvas()
            val reserve = headerHeight + (rowHeights.getOrNull(headerCount)?.coerceAtMost(bodyBudget) ?: 0f)
            if (content && y + reserve > geometry.height - geometry.bottom) finishPage()
        }
        for ((rowIndex, cells) in prepared.withIndex()) {
            interrupted()
            val height = rowHeights[rowIndex]
            if (cells.any { it.rowSpan > 1 }) warnings.add("Vertically merged cells use continuous local layout across rows/pages")
            repeatHeaders(rowIndex)
            val budget = if (rowIndex >= headerCount) bodyBudget else geometry.contentHeight
            if (content && y + height > geometry.height - geometry.bottom && height <= budget) {
                finishPage(); repeatHeaders(rowIndex)
            }
            var start = 0f
            while (start < height) {
                repeatHeaders(rowIndex)
                val portion = minOf(height - start, geometry.height - geometry.bottom - y)
                if (portion < 1) { finishPage(); continue }
                paintRow(rowIndex, start, portion)
                y += portion; content = true; start += portion
                if (rowIndex < headerCount) headerPage = pages
                if (start < height) { warnings.add("Very tall table rows split across pages"); finishPage() }
            }
        }
    }
    private fun hex(value: String?): Int?=value?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toLong(16)?.let { (it or 0xff000000L).toInt() }
    companion object {
        private val W=listOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main")
        private val R=listOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships","http://purl.oclc.org/ooxml/officeDocument/relationships")
        private val A=setOf("http://schemas.openxmlformats.org/drawingml/2006/main","http://purl.oclc.org/ooxml/drawingml/main")
    }
}
