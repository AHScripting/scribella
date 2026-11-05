package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import dev.ownednotes.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local named page snapshots; copied PNG bytes travel as ordinary background assets when applied. */
class PageTemplates(private val root: File) {
    data class Template(val name: String, val assetId: String, val width: Float, val height: Float, val paper: PaperStyle)
    private val index get()=AtomicFile(File(root,"templates.json"))
    fun list(): List<Template> {
        if(!index.baseFile.exists() && !File(index.baseFile.path+".bak").exists()) return emptyList()
        val raw=index.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        require(raw.length<=32768) { "Template index is too large" }
        val entries=JSONArray(raw)
        require(entries.length()<=24) { "Too many templates" }
        return (0 until entries.length()).map { i ->
            val o=entries.getJSONObject(i);val id=o.getString("asset")
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid template asset" }
            val paper=PaperStyle(PaperPattern.valueOf(o.getString("pattern")),o.getLong("color"),o.getLong("guideColor"),o.getDouble("spacing").toFloat(),o.getDouble("weight").toFloat())
            Template(o.getString("name"),id,o.getDouble("width").toFloat(),o.getDouble("height").toFloat(),paper)
        }.sortedBy { it.name.lowercase() }
    }
    fun load(template: Template): ImageAsset {
        val file=File(root,template.assetId+".png");require(file.length() in 1..ImageAsset.MAX_BYTES.toLong()) { "Template image unavailable" }
        return ImageAsset.fromBytes(file.readBytes()).also { require(it.id==template.assetId) { "Template image changed" } }
    }
    fun thumbnail(template: Template): Bitmap {
        val file=File(root,template.assetId+".png")
        return requireNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=8 })) { "Template preview unavailable" }
    }
    fun save(name: String, notebook: Notebook, pageId: String, cache: File): Template {
        val checked=name.trim();require(checked.length in 1..60 && checked.none { it.isISOControl() }) { "Use 1–60 characters" }
        val before=list();require(before.size<24) { "You can save up to 24 templates" }
        require(before.none { it.name.equals(checked,true) }) { "A template with that name already exists" }
        val page=notebook.pages.first { it.id==pageId }
        val png=PageImageExport.create(notebook,pageId,cache)
        try {
            val asset=ImageAsset.fromBytes(png.readBytes());root.mkdirs()
            val image=AtomicFile(File(root,asset.id+".png"));var output=image.startWrite()
            try { asset.writeTo(output);image.finishWrite(output) } catch(e: Throwable) { image.failWrite(output);throw e }
            val item=Template(checked,asset.id,page.width,page.height,page.paper)
            val data=JSONArray()
            (before+item).forEach { t -> data.put(JSONObject().put("name",t.name).put("asset",t.assetId).put("width",t.width.toDouble()).put("height",t.height.toDouble())
                .put("pattern",t.paper.pattern.name).put("color",t.paper.color).put("guideColor",t.paper.guideColor).put("spacing",t.paper.spacing.toDouble()).put("weight",t.paper.weight.toDouble())) }
            output=index.startWrite()
            try { output.write(data.toString().toByteArray());index.finishWrite(output) } catch(e: Throwable) { index.failWrite(output);throw e }
            return item
        } finally { png.delete() }
    }
}
