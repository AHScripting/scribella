package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import java.util.Locale
import kotlin.math.*

/** Static chart caches only. Never opens a workbook, evaluates formulas or refreshes a link. */
internal object PptxChart {
    class Unsupported(message: String): Exception(message)
    data class Series(val name: String,val values: List<Double>,val appearance: OfficeXml.Node?,val marker: String="none",val markerSize: Float=5f,val lineWidth: Float=2f,val lineVisible: Boolean=true,val markerAppearance: OfficeXml.Node? = null)
    data class Scatter(val xValues: List<List<Double>>, val minimum: Double, val maximum: Double)
    data class Chart(val categories: List<String>,val series: List<Series>,val horizontal: Boolean,
                     val title: String,val minimum: Double,val maximum: Double,val line: Boolean=false,val between: Boolean=true,val circular: Boolean=false,val angle: Float=0f,val hole: Float=0f,val points: List<OfficeXml.Node?> = emptyList(),val scatter: Scatter? = null,val area: Boolean=false)
    private fun unsupported(message: String): Nothing = throw Unsupported(message)
    private fun cache(node: OfficeXml.Node?): List<String> {
        node ?: unsupported("Charts without complete saved data (linked data is not refreshed)")
        val points=node.all("pt")
        val count=node.child("ptCount")?.attr("val")?.toIntOrNull() ?: points.size
        if(count !in 1..1000 || points.size != count) unsupported("Charts with missing or oversized saved data")
        val indexed=points.map { p -> (p.attr("idx")?.toIntOrNull() ?: -1) to (p.child("v")?.text ?: unsupported("Charts with missing saved values")) }
        if(indexed.map { it.first }.sorted() != (0 until count).toList()) unsupported("Charts with inconsistent saved data indices")
        return indexed.sortedBy { it.first }.map { it.second }
    }
    private fun number(value: String): Double = value.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it)<=1e12 }
        ?: unsupported("Charts with nonnumeric or unsupported saved values")
    fun read(root: OfficeXml.Node): Chart {
        val namespaces=setOf("http://schemas.openxmlformats.org/drawingml/2006/chart","http://purl.oclc.org/ooxml/drawingml/chart")
        if(root.name != "chartSpace" || root.ns !in namespaces) unsupported("Unsupported chart part")
        val chart=root.child("chart") ?: unsupported("Charts without a plot")
        val plot=chart.child("plotArea") ?: unsupported("Charts without a plot")
        val types=plot.children.filter { it.name.endsWith("Chart") }
        if(types.size != 1 || types.single().name !in setOf("barChart","lineChart","pieChart","doughnutChart","scatterChart","areaChart")) unsupported("Chart types other than simple clustered columns/bars, standard lines,2D single-series pie/doughnut static XY scatter or simple single-series area charts")
        if(types.single().name=="scatterChart") return readScatter(chart,plot,types.single())
        val bars=types.single(); val area=bars.name=="areaChart"; val line=bars.name=="lineChart" || area; val circular=bars.name in setOf("pieChart","doughnutChart")
        fun on(node: OfficeXml.Node?)=node!=null && node.attr("val") !in setOf("0","false","off")
        if(line && (on(bars.child("smooth")) || bars.children.any { it.name in setOf("hiLowLines","upDownBars","dropLines") })) unsupported("Smoothed lines or advanced line-chart decorations")
        if(line && (plot.all("valAx").size>1 || plot.all("catAx").size>1 || plot.child("dateAx")!=null)) unsupported("Line charts with date or secondary axes")
        if(line && plot.child("catAx")?.child("scaling")?.child("orientation")?.attr("val")=="maxMin") unsupported("Line charts with reversed category axes")
        if(bars.child("grouping")?.attr("val") !in listOf(null,if(line) "standard" else "clustered")) unsupported("Stacked and percentage charts")
        val direction=bars.child("barDir")?.attr("val") ?: "col"
        if(direction !in setOf("col","bar")) unsupported("Unsupported chart direction")
        val nodes=bars.all("ser")
        if(nodes.size !in 1..50) unsupported("Charts with missing or oversized series")
        if(area) {
            if(nodes.size!=1 || on(bars.child("varyColors")) || bars.child("dropLines")!=null)
                unsupported("Area charts with multiple series, varying colours or drop lines")
            val category=plot.all("catAx").singleOrNull();val value=plot.all("valAx").singleOrNull()
            fun id(node: OfficeXml.Node?)=node?.attr("val")?.toLongOrNull()?.takeIf { it in -2147483648L..4294967295L }?.and(0xffffffffL)
                ?: unsupported("Area charts with invalid axis IDs")
            if(category==null || value==null || plot.child("dateAx")!=null || plot.child("serAx")!=null)
                unsupported("Area charts without primary category and value axes")
            val ids=bars.all("axId").map(::id)
            if(ids.size!=2 || ids.distinct().size!=2 || ids.toSet()!=setOf(id(category.child("axId")),id(value.child("axId"))))
                unsupported("Area charts with duplicate or mismatched axes")
            if(category.child("axPos")?.attr("val") !in setOf("b","t") || value.child("axPos")?.attr("val") !in setOf("l","r"))
                unsupported("Area charts with unsupported axis positions")
            for((axis,other) in listOf(category to value,value to category)) {
                if(axis.child("crossAx")?.let(::id)?.let { it!=id(other.child("axId")) }==true ||
                    axis.child("scaling")?.child("orientation")?.attr("val") !in setOf(null,"minMax") || axis.child("scaling")?.child("logBase")!=null)
                    unsupported("Area charts with reversed, logarithmic or mismatched axes")
            }
            val node=nodes.single();val appearance=node.child("spPr")
            if(node.children.any { it.name in setOf("dPt","trendline","errBars","pictureOptions","marker") } ||
                appearance?.children?.any { it.name in setOf("noFill","gradFill","pattFill","blipFill","grpFill") }==true ||
                appearance?.child("ln")?.let { it.child("noFill")==null }==true)
                unsupported("Area charts with unsupported fills, outlines or per-point/decorative styles")
            if(listOfNotNull(bars.child("dLbls"),node.child("dLbls")).any { labels -> labels.child("dLbl")!=null ||
                listOf("showVal","showCatName","showSerName","showPercent","showBubbleSize","showLegendKey").any { on(labels.child(it)) } })
                unsupported("Area charts with data labels")
        }

        val orders=nodes.mapIndexed { i,node -> node.child("order")?.attr("val")?.toIntOrNull() ?: i }
        if(orders.distinct().size != orders.size) unsupported("Charts with duplicate series order")
        var categories: List<String>?=null
        val series=nodes.zip(orders).sortedBy { it.second }.map { (node,_) ->
            val cat=node.child("cat")
            val labels=cache(cat?.child("strRef")?.child("strCache") ?: cat?.child("strLit") ?: cat?.child("numRef")?.child("numCache") ?: cat?.child("numLit"))
            if(categories != null && categories != labels) unsupported("Charts with different categories between series")
            categories=labels
            val values=node.child("val")
            val numbers=cache(values?.child("numRef")?.child("numCache") ?: values?.child("numLit")).map(::number)
            if(numbers.size != labels.size || nodes.size.toLong()*numbers.size > 10000) unsupported("Charts with inconsistent or oversized series")
            val tx=node.child("tx")
            val name=tx?.child("v")?.text ?: tx?.child("strRef")?.child("strCache")?.let { cache(it).first() }.orEmpty()
            if(line && (on(node.child("smooth")) || node.child("errBars")!=null)) unsupported("Smoothed lines or line-chart error bars")
            val appearance=node.child("spPr"); val stroke=appearance?.child("ln")
            if(line && stroke?.child("prstDash")?.attr("val") !in setOf(null,"solid")) unsupported("Line charts with unsupported dashed line styles")
            val marker=node.child("marker"); val symbol=marker?.child("symbol")?.attr("val") ?: if(on(bars.child("marker"))) "circle" else "none"
            if(line && symbol !in setOf("none","auto","circle","square","diamond","triangle")) unsupported("Line charts with unsupported marker symbols")
            val markerSize=marker?.child("size")?.attr("val")?.let { it.toFloatOrNull()?.takeIf { n -> n.isFinite() && n in 2f..72f } ?: unsupported("Invalid line-chart marker size") } ?: 5f
            val lineWidth=stroke?.attr("w")?.let { it.toFloatOrNull()?.div(12700)?.takeIf { n -> n.isFinite() && n in 0f..100f } ?: unsupported("Invalid line-chart stroke width") } ?: 2f
            Series(name,numbers,appearance,if(line) symbol else "none",markerSize,lineWidth,stroke?.child("noFill")==null)
        }
        if(circular) {
            if(series.size!=1) unsupported("Multi-series pie or multi-ring doughnut charts")
            if(series.single().values.any { it<0 } || series.single().values.sum()<=0) unsupported("Pie/doughnut charts with negative or zero-total saved values")
            val points=MutableList<OfficeXml.Node?>(categories!!.size) { null };val seen=HashSet<Int>()
            for(node in nodes.single().all("dPt")) {
                val i=node.child("idx")?.attr("val")?.toIntOrNull() ?: unsupported("Invalid pie point index")
                if(i !in points.indices || !seen.add(i)) unsupported("Duplicate or invalid pie point indices")
                points[i]=node.child("spPr")
            }
            if(nodes.single().children.any { it.name=="explosion" && it.attr("val") !in setOf(null,"0") } || nodes.single().all("dPt").any { it.child("explosion")?.attr("val") !in setOf(null,"0") }) unsupported("Exploded pie/doughnut charts")
            fun setting(name: String,default: Float,range: ClosedFloatingPointRange<Float>): Float {
                val raw=bars.child(name)?.attr("val") ?: return default
                return raw.toFloatOrNull()?.takeIf { it.isFinite() && it in range } ?: unsupported("Invalid pie/doughnut $name")
            }
            val angle=setting("firstSliceAng",0f,0f..360f)
            val hole=if(bars.name=="doughnutChart") setting("holeSize",50f,10f..90f)/100 else 0f
            val title=chart.child("title")?.descendants("t")?.joinToString("") { it.text }.orEmpty()
            return Chart(categories,series,false,title,0.0,1.0,circular=true,angle=angle,hole=hole,points=points)
        }
        val scaling=plot.child("valAx")?.child("scaling")
        if(scaling?.child("logBase") != null || scaling?.child("orientation")?.attr("val") == "maxMin") unsupported("Logarithmic or reversed chart axes")
        val low=minOf(0.0,series.minOf { it.values.min() }); val high=maxOf(0.0,series.maxOf { it.values.max() })
        val span=maxOf(1e-12,high-low); val base=10.0.pow(floor(log10(span/5)))
        val step=listOf(1.0,2.0,5.0,10.0).first { it*base >= span/5 }*base
        val minimum=scaling?.child("min")?.attr("val")?.let(::number) ?: floor(low/step)*step
        val maximum=scaling?.child("max")?.attr("val")?.let(::number) ?: if(high==low) 1.0 else ceil(high/step)*step
        if(maximum<=minimum) unsupported("Charts with invalid axis limits")
        if(area && (series.single().values.size<2 || series.single().values.any { value ->
            val position=(value-minimum)/(maximum-minimum);!position.isFinite() || abs(position)>1000000.0
        })) unsupported("Area charts with incomplete data or unsafe axis ranges")
        val title=if(chart.child("autoTitleDeleted")?.attr("val") in setOf("1","true")) "" else
            chart.child("title")?.descendants("t")?.joinToString("") { it.text }?.takeIf { it.isNotBlank() }
                ?: if(series.size==1) series.single().name else ""
        return Chart(categories!!,series,!line && direction=="bar",title,minimum,maximum,line,plot.child("valAx")?.child("crossBetween")?.attr("val") != "midCat",area=area)
    }
    /** Saved numeric XY caches only: no category substitution, workbook refresh or point sorting. */
    private fun readScatter(chart: OfficeXml.Node,plot: OfficeXml.Node,scatter: OfficeXml.Node): Chart {
        fun on(node: OfficeXml.Node?)=node!=null && node.attr("val") !in setOf("0","false","off")
        val style=scatter.child("scatterStyle")?.attr("val")
        if(style !in setOf("marker","line","lineMarker")) unsupported("Scatter charts with smoothed or unsupported styles")
        if(on(scatter.child("varyColors")) || plot.child("catAx")!=null || plot.child("dateAx")!=null || plot.child("serAx")!=null)
            unsupported("Scatter charts with per-point colours or non-value axes")
        fun id(node: OfficeXml.Node?): Long = node?.attr("val")?.toLongOrNull()?.takeIf { it in -2147483648L..4294967295L }
            ?.and(0xffffffffL) ?: unsupported("Scatter charts with missing or invalid axis IDs")
        val ids=scatter.all("axId").map(::id)
        val axes=plot.all("valAx")
        if(ids.size!=2 || ids.distinct().size!=2 || axes.size!=2 || axes.map { id(it.child("axId")) }.toSet()!=ids.toSet())
            unsupported("Scatter charts with missing, duplicate or secondary axes")
        val xAxis=axes.singleOrNull { it.child("axPos")?.attr("val") in setOf("b","t") }
            ?: unsupported("Scatter charts without one horizontal value axis")
        val yAxis=axes.singleOrNull { it.child("axPos")?.attr("val") in setOf("l","r") }
            ?: unsupported("Scatter charts without one vertical value axis")
        for((axis,other) in listOf(xAxis to yAxis,yAxis to xAxis)) {
            if(axis.child("crossAx")?.let(::id)?.let { it!=id(other.child("axId")) }==true)
                unsupported("Scatter charts with mismatched crossing axes")
            val scaling=axis.child("scaling")
            if(scaling?.child("logBase")!=null || scaling?.child("orientation")?.attr("val") !in setOf(null,"minMax"))
                unsupported("Scatter charts with logarithmic or reversed axes")
        }
        val nodes=scatter.all("ser")
        if(nodes.size !in 1..50) unsupported("Scatter charts with missing or oversized series")
        val ordered=nodes.mapIndexed { i,node -> node to (node.child("order")?.attr("val")?.let { value ->
            value.toIntOrNull()?.takeIf { it>=0 } ?: unsupported("Scatter charts with invalid series order")
        } ?: i) }.sortedBy { it.second }
        if(ordered.map { it.second }.distinct().size!=nodes.size) unsupported("Scatter charts with duplicate series order")
        var total=0
        val xValues=ArrayList<List<Double>>()
        val series=ordered.map { (node,_) ->
            if(on(node.child("smooth")) || node.children.any { it.name in setOf("errBars","trendline","dPt") })
                unsupported("Scatter charts with smoothing, error bars, trendlines or per-point styles")
            val labels=listOfNotNull(scatter.child("dLbls"),node.child("dLbls"))
            if(labels.any { label -> label.child("dLbl")!=null || listOf("showVal","showCatName","showSerName","showPercent","showBubbleSize","showLegendKey").any { on(label.child(it)) } })
                unsupported("Scatter charts with data labels")
            fun values(name: String): List<Double> {
                val value=node.child(name)
                return cache(value?.child("numRef")?.child("numCache") ?: value?.child("numLit")).map(::number)
            }
            val xs=values("xVal");val ys=values("yVal");total+=xs.size
            if(xs.size!=ys.size || total>10000) unsupported("Scatter charts with inconsistent or oversized XY caches")
            xValues.add(xs)
            val tx=node.child("tx")
            val name=tx?.child("v")?.text ?: tx?.child("strRef")?.child("strCache")?.let { cache(it).first() }.orEmpty()
            val marker=node.child("marker");val symbol=marker?.child("symbol")?.attr("val") ?: if(style=="line") "none" else "circle"
            if(symbol !in setOf("none","auto","circle","square","diamond","triangle"))
                unsupported("Scatter charts with unsupported marker styles")
            val markerAppearance=marker?.child("spPr")
            if(markerAppearance?.children?.any { it.name in setOf("gradFill","pattFill","blipFill") }==true ||
                markerAppearance?.child("ln")?.let { it.child("noFill")==null }==true)
                unsupported("Scatter charts with complex marker fills or outlines")
            val size=marker?.child("size")?.attr("val")?.let { it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in 2f..72f }
                ?: unsupported("Invalid scatter marker size") } ?: 5f
            val appearance=node.child("spPr");val stroke=appearance?.child("ln")
            if(stroke?.child("prstDash")?.attr("val") !in setOf(null,"solid")) unsupported("Scatter charts with dashed line styles")
            val width=stroke?.attr("w")?.let { it.toFloatOrNull()?.div(12700)?.takeIf { v -> v.isFinite() && v in 0f..100f }
                ?: unsupported("Invalid scatter stroke width") } ?: 2f
            Series(name,ys,appearance,if(style=="line" || markerAppearance?.child("noFill")!=null) "none" else symbol,size,width,style!="marker" && stroke?.child("noFill")==null,markerAppearance)
        }
        fun range(axis: OfficeXml.Node,values: List<Double>): Pair<Double,Double> {
            var low=values.min();var high=values.max()
            if(low==high) { val pad=maxOf(1.0,abs(low)*.1);low-=pad;high+=pad }
            val span=maxOf(1e-12,high-low);val base=10.0.pow(floor(log10(span/5)))
            val step=listOf(1.0,2.0,5.0,10.0).first { it*base>=span/5 }*base
            val scaling=axis.child("scaling")
            val minimum=scaling?.child("min")?.attr("val")?.let(::number) ?: floor(low/step)*step
            val maximum=scaling?.child("max")?.attr("val")?.let(::number) ?: ceil(high/step)*step
            if(!minimum.isFinite() || !maximum.isFinite() || maximum<=minimum) unsupported("Scatter charts with invalid axis limits")
            return minimum to maximum
        }
        val x=range(xAxis,xValues.flatten());val y=range(yAxis,series.flatMap { it.values })
        fun safe(value: Double,min: Double,max: Double): Boolean {
            val position=(value-min)/(max-min)
            return position.isFinite() && abs(position)<=1_000_000
        }
        if(xValues.any { values -> values.any { !safe(it,x.first,x.second) } } ||
            series.any { row -> row.values.any { !safe(it,y.first,y.second) } })
            unsupported("Scatter charts with coordinates beyond the safe axis range")
        val title=if(on(chart.child("autoTitleDeleted"))) "" else chart.child("title")?.descendants("t")?.joinToString("") { it.text }.orEmpty()
        return Chart(emptyList(),series,false,title,y.first,y.second,line=true,scatter=Scatter(xValues,x.first,x.second))
    }

    /** Compact finite ticks; increase precision until distinct represented values stay distinct. */
    internal fun axisLabels(minimum: Double, maximum: Double): List<String> {
        val values=(0..5).map { tick -> when(tick) { 0 -> minimum; 5 -> maximum; else -> minimum+(maximum-minimum)*(tick/5.0) } }
        fun text(value: Double, precision: Int): String {
            if(value==0.0) return "0"
            val parts=String.format(Locale.ROOT,"%.${precision}g",value).split('e')
            val mantissa=if('.' in parts[0]) parts[0].trimEnd('0').trimEnd('.') else parts[0]
            return mantissa+if(parts.size==2) "e"+parts[1].toInt() else ""
        }
        for(precision in 3..17) {
            val labels=values.map { text(it,precision) }
            if(labels.distinct().size==values.distinct().size) return labels
        }
        return values.map { it.toString() }
    }

    /** Returns true if labels had to be shortened or sampled to fit. */
    fun draw(canvas: Canvas,chart: Chart,width: Float,height: Float,colors: List<Int>,markerColors: List<Int> = colors): Boolean {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=10f }
        var shortened=false
        fun label(value: String,x: Float,y: Float,available: Float,align: Paint.Align=Paint.Align.LEFT) {
            val text=TextUtils.ellipsize(value,paint,maxOf(1f,available),TextUtils.TruncateAt.END).toString()
            if(text != value) shortened=true
            paint.color=Color.BLACK; paint.style=Paint.Style.FILL; paint.textAlign=align
            canvas.drawText(text,x,y,paint)
        }
        val valueLabels=axisLabels(chart.minimum,chart.maximum)
        val xLabels=chart.scatter?.let { axisLabels(it.minimum,it.maximum) }
        val measuredLeft=if(chart.horizontal || chart.circular) 0f else valueLabels.maxOf { paint.measureText(it) }+6f
        val edgeLabels=if(chart.horizontal) valueLabels else xLabels
        val left=maxOf(if(chart.horizontal) 62f else 34f,measuredLeft,edgeLabels?.first()?.let { paint.measureText(it)/2+2f } ?: 0f).coerceAtMost(maxOf(34f,width*.4f))
        val top=if(chart.title.isEmpty()) 12f else 30f
        val right=width-maxOf(12f,edgeLabels?.last()?.let { paint.measureText(it)/2+2f } ?: 0f)
        val bottom=height-if(chart.series.size>1) 40f else 26f
        fun numericLabel(text: String,x: Float,y: Float,available: Float,align: Paint.Align) {
            val original=paint.textSize
            try {
                val measured=paint.measureText(text)
                if(measured>available) paint.textSize=original*(available/measured)
                // An omitted tick is reported; a truncated number would change its meaning.
                if(paint.textSize<6f) { shortened=true;return }
                paint.color=Color.BLACK;paint.style=Paint.Style.FILL;paint.textAlign=align
                canvas.drawText(text,x,y,paint)
            } finally { paint.textSize=original }
        }
        canvas.save()
        try {
            canvas.clipRect(0f,0f,width,height)
            paint.color=Color.WHITE; canvas.drawRect(0f,0f,width,height,paint)
            if(chart.title.isNotEmpty()) { paint.textSize=12f; label(chart.title,width/2,16f,width-12,Paint.Align.CENTER); paint.textSize=10f }
            if(chart.circular) {
                val cx=width*.31f;val cy=(top+height-12)/2;val radius=minOf(width*.29f-8,(height-top-12)/2).coerceAtLeast(1f)
                val bounds=RectF(cx-radius,cy-radius,cx+radius,cy+radius)
                val values=chart.series.single().values;val total=values.sum();var angle=chart.angle-90f
                paint.style=Paint.Style.FILL
                for((i,value) in values.withIndex()) {
                    val sweep=(360*value/total).toFloat();paint.color=colors[i]
                    if(sweep>0) canvas.drawArc(bounds,angle,sweep,true,paint)
                    angle+=sweep
                }
                if(chart.hole>0) { paint.color=Color.WHITE;canvas.drawCircle(cx,cy,radius*chart.hole,paint) }
                val available=maxOf(0,((height-top-8)/16).toInt())
            for((i,value) in chart.categories.withIndex()) {
                    if(i>=available) { shortened=true;continue }
                    val y=top+12+i*16;paint.color=colors[i];canvas.drawRect(width*.64f,y-8,width*.64f+7,y-1,paint)
                    label(value,width*.64f+11,y,width*.36f-18)
                }
                return shortened
            }
            fun position(value: Double)=((value-chart.minimum)/(chart.maximum-chart.minimum)).toFloat()
            for(tick in 0..5) {
                val text=valueLabels[tick]
                paint.color=Color.LTGRAY; paint.strokeWidth=.5f
                if(chart.horizontal) {
                    val x=left+(right-left)*tick/5
                    canvas.drawLine(x,top,x,bottom,paint); numericLabel(text,x,bottom+13,(right-left)/5-4,Paint.Align.CENTER)
                } else {
                    val y=bottom-(bottom-top)*tick/5
                    canvas.drawLine(left,y,right,y,paint); numericLabel(text,left-4,y+3,left-6,Paint.Align.RIGHT)
                }
            }
            val categorySize=(if(chart.horizontal) bottom-top else right-left)/chart.categories.size.coerceAtLeast(1)
            val group=categorySize*.75f; val barSize=group/chart.series.size
            val every=maxOf(1,ceil((if(chart.horizontal) 14f else 36f)/categorySize).toInt())
            canvas.save()
            try {
                canvas.clipRect(left,top,right,bottom)
                if(chart.line) {
                    for((i,series) in chart.series.withIndex()) {
                        fun x(index: Int)=chart.scatter?.let { xy -> left+(right-left)*((xy.xValues[i][index]-xy.minimum)/(xy.maximum-xy.minimum)).toFloat() }
                            ?: if(chart.between || chart.categories.size==1) left+categorySize*(index+.5f)
                            else left+(right-left)*index/(chart.categories.size-1)
                        paint.color=colors[i]; paint.strokeWidth=series.lineWidth; paint.strokeJoin=Paint.Join.ROUND
                        if(chart.area) {
                            val path=Path();val baseline=bottom-(bottom-top)*position(0.0).coerceIn(0f,1f)
                            path.moveTo(x(0),baseline)
                            for((c,value) in series.values.withIndex()) path.lineTo(x(c),bottom-(bottom-top)*position(value))
                            path.lineTo(x(series.values.lastIndex),baseline);path.close()
                            paint.style=Paint.Style.FILL;canvas.drawPath(path,paint)
                        } else if(series.lineVisible && series.lineWidth>0 && series.values.size>1) {
                            val path=Path()
                            for((c,value) in series.values.withIndex()) {
                                val y=bottom-(bottom-top)*position(value)
                                if(c==0) path.moveTo(x(c),y) else path.lineTo(x(c),y)
                            }
                            paint.style=Paint.Style.STROKE; canvas.drawPath(path,paint)
                        }
                        paint.style=Paint.Style.FILL;paint.color=markerColors[i]
                        for((c,value) in series.values.withIndex()) {
                            val cx=x(c); val cy=bottom-(bottom-top)*position(value); val radius=series.markerSize/2
                            when(series.marker) {
                                "circle","auto" -> canvas.drawCircle(cx,cy,radius,paint)
                                "square" -> canvas.drawRect(cx-radius,cy-radius,cx+radius,cy+radius,paint)
                                "diamond","triangle" -> {
                                    val path=Path();path.moveTo(cx,cy-radius)
                                    if(series.marker=="diamond") { path.lineTo(cx+radius,cy);path.lineTo(cx,cy+radius);path.lineTo(cx-radius,cy) }
                                    else { path.lineTo(cx+radius,cy+radius);path.lineTo(cx-radius,cy+radius) }
                                    path.close();canvas.drawPath(path,paint)
                                }
                            }
                        }
                    }
                } else {
                for((s,series) in chart.series.withIndex()) for((c,value) in series.values.withIndex()) {
                    paint.color=colors[s]; paint.style=Paint.Style.FILL
                    val start=(if(chart.horizontal) top else left)+categorySize*c+(categorySize-group)/2+barSize*s
                    if(chart.horizontal) {
                        val zero=left+(right-left)*position(0.0); val end=left+(right-left)*position(value)
                        canvas.drawRect(minOf(zero,end),start,maxOf(zero,end),start+barSize,paint)
                    } else {
                        val zero=bottom-(bottom-top)*position(0.0); val end=bottom-(bottom-top)*position(value)
                        canvas.drawRect(start,minOf(zero,end),start+barSize,maxOf(zero,end),paint)
                    }
                }
                }
            } finally { canvas.restore() }
            chart.scatter?.let { xy ->
                for(tick in 0..5) {
                    val x=left+(right-left)*tick/5
                    numericLabel(xLabels!![tick],x,bottom+14,(right-left)/5-4,Paint.Align.CENTER)
                }
            }
            for((i,value) in chart.categories.withIndex()) {
                if(i%every != 0) { shortened=true; continue }
                if(chart.horizontal) label(value,left-5,top+categorySize*(i+.5f)+3,left-8,Paint.Align.RIGHT)
                else {
                    val x=if(chart.line && !chart.between && chart.categories.size>1) left+(right-left)*i/(chart.categories.size-1) else left+categorySize*(i+.5f)
                    label(value,x,bottom+14,categorySize*every-4,Paint.Align.CENTER)
                }
            }
            if(chart.series.size>1) for((i,series) in chart.series.withIndex()) {
                val slot=width/chart.series.size; val x=i*slot
                paint.color=if(chart.line && !series.lineVisible) markerColors[i] else colors[i]; canvas.drawRect(x,height-12,x+7,height-5,paint)
                label(series.name,x+10,height-4,slot-12)
            }
        } finally { canvas.restore() }
        return shortened
    }
}
