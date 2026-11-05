package dev.ownednotes.app

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import dev.ownednotes.core.Page
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.hasObjectLayers
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import dev.ownednotes.core.Point
import dev.ownednotes.core.visibleCenterlines
import java.io.File
import kotlin.math.ceil

/** Standard PDF Ink annotations, one per visible native stroke. The appearance retains our brush and erasures. */
internal object InkPdfAnnotations {
    fun append(document: PDDocument, target: PDPage, page: Page, workspace: File, notebook: Notebook? = null) {
        val map = SourcePdfExport.overlayTransform(target)
        fun raw(point: Point) = map.transformPoint(point.x, ceil(page.height) - point.y)
        page.paintSlices.forEach { slice ->
        if (page.hasObjectLayers && (slice.images.isNotEmpty() || slice.textBoxes.isNotEmpty())) appendObjects(document, target, requireNotNull(notebook), slice, workspace)
        slice.drawnStrokes.forEach { stroke ->
            val path = InkGeometry.visible(stroke)
            val bounds = RectF().also { path.computeBounds(it, true) }
            if (path.isEmpty || !bounds.intersect(0f,0f,page.width,page.height) || bounds.isEmpty) return@forEach
            val corners = listOf(Point(bounds.left,bounds.top),Point(bounds.right,bounds.top),Point(bounds.left,bounds.bottom),Point(bounds.right,bounds.bottom)).map(::raw)
            val left=corners.minOf { it.x };val bottom=corners.minOf { it.y }
            val right=corners.maxOf { it.x };val top=corners.maxOf { it.y }
            val rectangle=PDRectangle(left,bottom,right-left,top-bottom)
            var fragments=stroke.visibleCenterlines().flatMap { clip(it,page.width,page.height) }
            if (fragments.isEmpty()) {
                // A pressure outline may remain beside an erased centreline. Keep actual visible
                // contours as editable paths rather than inventing points or restoring an erased gap.
                val samples=path.approximate(.5f);val contours=mutableListOf<List<Point>>();var contour=mutableListOf<Point>()
                for(i in samples.indices step 3) {
                    if(i>0 && samples[i]==samples[i-3] && (samples[i+1]!=samples[i-2] || samples[i+2]!=samples[i-1])) {
                        if(contour.isNotEmpty()) contours+=contour;contour=mutableListOf()
                    }
                    contour+=Point(samples[i+1],samples[i+2])
                }
                if(contour.isNotEmpty()) contours+=contour
                fragments=contours.flatMap { clip(it,page.width,page.height) }
            }
            require(fragments.isNotEmpty()) { "This stroke cannot be exported as editable ink. Turn off editable ink and try again." }
            val annotation=PDAnnotationMarkup(COSDictionary().apply { setName(COSName.SUBTYPE,PDAnnotationMarkup.SUB_TYPE_INK) }).apply {
                this.rectangle=rectangle
                annotationName=stroke.id
                setPrinted(true);setReadOnly(false)
                color=PDColor(floatArrayOf(((stroke.color shr 16) and 255)/255f,((stroke.color shr 8) and 255)/255f,(stroke.color and 255)/255f),PDDeviceRGB.INSTANCE)
                borderStyle=PDBorderStyleDictionary().apply { width=stroke.width }
                inkList=fragments.map { fragment -> fragment.flatMap { point -> raw(point).let { listOf(it.x,it.y) } }.toFloatArray() }.toTypedArray()
            }
            val overlay=File.createTempFile("ink-appearance-",".pdf",workspace)
            try {
                val android=PdfDocument()
                try {
                    val sheet=android.startPage(PdfDocument.PageInfo.Builder(ceil(page.width).toInt(),ceil(page.height).toInt(),1).create())
                    try {
                        sheet.canvas.clipRect(0f,0f,page.width,page.height)
                        InkGeometry.draw(sheet.canvas,stroke,Paint(Paint.ANTI_ALIAS_FLAG),page.opacityOf(stroke),path)
                    } finally { android.finishPage(sheet) }
                    overlay.outputStream().use { android.writeTo(it) }
                } finally { android.close() }
                PDDocument.load(overlay,MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace)).use { layer ->
                    val form=LayerUtility(document).importPageAsForm(layer,0)
                    val appearance=PDAppearanceStream(document)
                    appearance.bBox=PDRectangle(rectangle.width,rectangle.height)
                    appearance.setResources(com.tom_roush.pdfbox.pdmodel.PDResources())
                    checkNotNull(appearance.resources) { "Missing PDF ink appearance resources" }
                    PDPageContentStream(document,appearance).use { content ->
                        content.transform(Matrix.getTranslateInstance(-left,-bottom))
                        content.transform(map);content.drawForm(form)
                    }
                    annotation.appearance=PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
                }
                annotation.page=target
                target.annotations.add(annotation)
            } finally { overlay.delete() }
        }
        }
    }

    /** A read-only object appearance preserves stacking while native Ink annotations remain editable. */
    private fun appendObjects(document: PDDocument, target: PDPage, notebook: Notebook, page: Page, workspace: File) {
        val map = SourcePdfExport.overlayTransform(target)
        val corners = listOf(0f to 0f, page.width to 0f, 0f to page.height, page.width to page.height)
            .map { map.transformPoint(it.first, ceil(page.height) - it.second) }
        val left = corners.minOf { it.x }; val bottom = corners.minOf { it.y }
        val rectangle = PDRectangle(left, bottom, corners.maxOf { it.x } - left, corners.maxOf { it.y } - bottom)
        val annotation = PDAnnotationRubberStamp().apply { this.rectangle = rectangle; isPrinted = true; isReadOnly = true }
        val overlay = File.createTempFile("objects-appearance-", ".pdf", workspace)
        try {
            val android = PdfDocument()
            try {
                val sheet = android.startPage(PdfDocument.PageInfo.Builder(ceil(page.width).toInt(), ceil(page.height).toInt(), 1).create())
                try {
                    sheet.canvas.clipRect(0f, 0f, page.width, page.height)
                    PhotoRendering.drawPage(sheet.canvas, page, notebook.imageAssets)
                    TextRendering.drawPage(sheet.canvas, page)
                } finally { android.finishPage(sheet) }
                overlay.outputStream().use { android.writeTo(it) }
            } finally { android.close() }
            PDDocument.load(overlay, MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace)).use { layer ->
                val form = LayerUtility(document).importPageAsForm(layer, 0)
                val appearance = PDAppearanceStream(document)
                appearance.bBox = PDRectangle(rectangle.width, rectangle.height)
                appearance.setResources(com.tom_roush.pdfbox.pdmodel.PDResources())
                PDPageContentStream(document, appearance).use { content ->
                    content.transform(Matrix.getTranslateInstance(-left, -bottom)); content.transform(map); content.drawForm(form)
                }
                annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
            }
            annotation.page = target; target.annotations.add(annotation)
        } finally { overlay.delete() }
    }

    /** Clip sampled segments to the visible page; never join across masked/off-page gaps. */
    private fun clip(points: List<Point>, width: Float, height: Float): List<List<Point>> {
        if(points.size==1) return if(points[0].x in 0f..width && points[0].y in 0f..height) listOf(points) else emptyList()
        val result=mutableListOf<MutableList<Point>>()
        for((a,b) in points.zipWithNext()) {
            var lo=0f;var hi=1f;val dx=b.x-a.x;val dy=b.y-a.y;var visible=true
            for((p,q) in listOf(-dx to a.x,dx to width-a.x,-dy to a.y,dy to height-a.y)) {
                if(p==0f) { if(q<0f) visible=false }
                else { val t=q/p;if(p<0f) lo=maxOf(lo,t) else hi=minOf(hi,t) }
            }
            if(!visible || lo>hi) continue
            val first=Point(a.x+lo*dx,a.y+lo*dy);val last=Point(a.x+hi*dx,a.y+hi*dy)
            val previous=result.lastOrNull()
            if(previous!=null && previous.last()==first) previous+=last else result+=mutableListOf(first,last)
        }
        return result
    }
}
