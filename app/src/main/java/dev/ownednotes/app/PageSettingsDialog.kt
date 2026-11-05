package dev.ownednotes.app

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import dev.ownednotes.core.*
import kotlin.math.roundToInt

/**
 * P201: Page & paper, one Glass sheet from the paper board. Paper, colours, line spacing, size and orientation are
 * staged together and committed by Apply, with explicit opt-in before replacing any imported background.
 */
object PageSettingsDialog {
    private const val MM = 25.4f / 72f

    fun show(context: Context, notebook: Notebook, pageId: String,
        standardScale: ((Set<String>) -> Boolean)? = null,
        standardShape: ((Set<String>, (String?) -> Unit) -> Boolean)? = null,
        applyTemplate: ((Set<String>, ImageAsset, PaperStyle, Float?, Float?, Boolean) -> Boolean)? = null,
        apply: (Set<String>, Float?, Float?, PaperStyle?, Boolean) -> Boolean): Kit.Sheet {
        val app=context.applicationContext as NotesApplication
        val templates=PageTemplates(java.io.File(app.storageRoot,"page-templates"))
        val main=android.os.Handler(android.os.Looper.getMainLooper())
        var template: PageTemplates.Template? = null
        var templateAsset: ImageAsset? = null
        var templateBitmap: android.graphics.Bitmap? = null
        var generation=0
        var templateTiles=emptyList<Kit.Tile>()
        val page = notebook.pages.first { it.id == pageId }
        val night = context.night
        val saved = PaperPresets(context.getSharedPreferences((context.applicationContext as NotesApplication).preferencesName, Context.MODE_PRIVATE)).list()
        val papers = BUILT_IN_PAPERS + saved.filter { p -> BUILT_IN_PAPERS.none { it.first == p.name } }.map { it.name to it.paper }
        val anyBackground = notebook.pages.any { it.hasBackground }

        // The draft. A null paper keeps each page's own paper; size 0 keeps sizes, 1 doubles, 2 is custom, 3+ PAGE_SIZES.
        var paper: PaperStyle? = null
        var paperName = "Current paper"
        var size = 0
        var landscape = page.width > page.height
        var all = false
        var replace = false
        fun shown() = paper ?: page.paper
        fun keepsBackground() = page.hasBackground && !replace

        fun field(label: String, value: Float) = Kit.field(context, label, value.toString()).apply {
            contentDescription = label; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val width = field("Page width", page.width); val height = field("Page height", page.height)
        fun typed() = (width.text.toString().toFloatOrNull() ?: page.width).coerceAtLeast(1f) to (height.text.toString().toFloatOrNull() ?: page.height).coerceAtLeast(1f)
        fun oriented(d: Pair<Float, Float>) = if (landscape) maxOf(d.first, d.second) to minOf(d.first, d.second) else minOf(d.first, d.second) to maxOf(d.first, d.second)

        lateinit var refresh: () -> Unit

        // Preview, its name and Apply to.
        val preview = PageFormatPreview(context)
        val title = Kit.text(context, "", Kit.Type.TITLE).apply { textSize = 22f }
        val subtitle = Kit.text(context, "", Kit.Type.BODY, secondary = true).apply { textSize = 14f }
        val many = notebook.pages.size
        val scope = Kit.segmented(context, listOf("This page", if (many > 1) "All $many pages" else "All pages"), 0,
            listOf(PaperArt.scope(false), PaperArt.scope(true))) { all = it == 1 }.apply { contentDescription = "Page settings scope" }

        // Paper tiles.
        lateinit var paperTiles: List<Kit.Tile>
        lateinit var paperColors: Kit.Swatches
        lateinit var lineColors: Kit.Swatches
        lateinit var spacing: Kit.Stepper
        fun showStyle() {
            paperColors.mark(shown().color.toInt()); lineColors.mark(shown().guideColor.toInt())
            spacing.set((shown().spacing * MM).roundToInt())
        }
        fun paperTile(name: String, art: Pictogram, description: String, style: PaperStyle?) =
            Kit.Tile(context, Kit.picture(context, art), name, style == null) {
                generation++;template=null;templateAsset=null;templateBitmap=null
                templateTiles.forEach { it.chosen=false }
                paper = style; paperName = if (style == null) "Current paper" else name; showStyle(); refresh()
            }.apply { contentDescription = description }
        paperTiles = listOf(paperTile("Keep", PaperArt.template({ page.paper }, { page.hasBackground }), "Keep paper", null)) +
            papers.map { (name, style) -> paperTile(name, PaperArt.template({ style }), "$name paper", style) }
        Kit.oneOf(paperTiles)

        // Size tiles: Keep, the named sizes, then 2× and Custom.
        val sizeArt = mutableListOf<View>()
        fun sizeTile(name: String, description: String, position: Int, art: Pictogram) =
            Kit.Tile(context, Kit.picture(context, art).also { sizeArt += it }, name, position == 0) {
                val dims = when { position == 1 -> page.width * 2 to page.height * 2
                    position >= 3 -> oriented(PAGE_SIZES[position - 3].second)
                    else -> null }
                dims?.let { width.setText(it.first.toString()); height.setText(it.second.toString()) }
                size = position; refresh()
            }.apply { contentDescription = description }
        val sizeTiles = listOf(sizeTile("Keep", "Keep page size", 0, PaperArt.size({ page.width to page.height }, ::shown, PaperArt.SizeMark.KEEP, ::keepsBackground))) +
            PAGE_SIZES.mapIndexed { i, (name, d) -> sizeTile(name, "$name page size", i + 3, PaperArt.size({ oriented(d) }, ::shown)) } +
            sizeTile("2× size", "2× current size", 1, PaperArt.size({ page.width to page.height }, ::shown, PaperArt.SizeMark.DOUBLE)) +
            sizeTile("Custom", "Custom page size", 2, PaperArt.size(::typed, ::shown, PaperArt.SizeMark.CUSTOM))
        Kit.oneOf(sizeTiles)

        val turn = Kit.segmented(context, listOf("Portrait", "Landscape"), if (landscape) 1 else 0) { side ->
            landscape = side == 1
            if (size >= 3) oriented(PAGE_SIZES[size - 3].second).let { width.setText(it.first.toString()); height.setText(it.second.toString()) }
            else if (size == 2) typed().let { (w, h) -> if ((w > h) != landscape) { width.setText(h.toString()); height.setText(w.toString()) } }
            refresh()
        }.apply { contentDescription = "Page orientation" }
        val turnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Kit.text(context, "Orientation", Kit.Type.CAPTION, secondary = true).apply { textSize = 13f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(turn, LinearLayout.LayoutParams(context.dpi(260f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dimensions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(width, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Kit.text(context, "×", Kit.Type.HEADLINE, secondary = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(context.dpi(32f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(height, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Kit.text(context, "points", Kit.Type.CAPTION, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(10f) })
        }

        // Colours and line spacing change the chosen paper (or the current one, when kept).
        fun restyle(change: (PaperStyle) -> PaperStyle) { paper = change(shown()); refresh() }
        paperColors = Kit.Swatches(context, PaperColors.papers.map { it.first.toInt() to "${it.second} paper colour" }, null) { i ->
            restyle { it.copy(color = PaperColors.papers[i].first) }
        }
        lineColors = Kit.Swatches(context, PaperColors.guides.map { it.first.toInt() to "${it.second} lines" }, null, lines = true) { i ->
            restyle { it.copy(guideColor = PaperColors.guides[i].first) }
        }
        spacing = Kit.stepper(context, "line spacing", (shown().spacing * MM).roundToInt(), 6..90, { "$it mm" }) { mm ->
            restyle { it.copy(spacing = (mm / MM).coerceIn(16f, 256f)) }
        }
        showStyle()
        val custom = Kit.row(context, "Custom paper", "Pattern, any colour, line weight, saved papers",
            icon = Kit.square(context, Kit.Paths.PAPER, Kit.NOTICE), chevron = true) {
            PaperDialog.show(context, page.copy(paper = shown(), pdfBackground = null, imageBackground = null)) { style ->
                generation++;template=null;templateAsset=null;templateBitmap=null;templateTiles.forEach { it.chosen=false }
                paper = style; paperName = "Custom paper"; paperTiles.forEach { it.chosen = false }; showStyle(); refresh(); true
            }
        }.apply { contentDescription = "Customize page paper" }
        val replacing = Kit.Switch(context, false, "Replace imported PDF backgrounds") { replace = it; refresh() }
        val lines = listOf<View>(lineColors)

        val issue = Kit.text(context, "", Kit.Type.CAPTION).apply {
            setTextColor(Kit.dangerText(night)); contentDescription = "Page settings validation"; visibility = View.GONE
        }
        fun colourName(value: Long, from: List<Pair<Long, String>>) = from.firstOrNull { it.first == value }?.second?.lowercase() ?: "custom colour"

        refresh = {
            val w = if (size == 0) page.width else width.text.toString().toFloatOrNull()
            val h = if (size == 0) page.height else height.text.toString().toFloatOrNull()
            val keep = keepsBackground()
            val style = if (keep) PaperStyle() else shown()
            val valid = w != null && h != null && w.isFinite() && h.isFinite() && w in 1f..100_000f && h in 1f..100_000f
            preview.templateBitmap = if (keep) null else templateBitmap
            preview.draft = if (valid) Page(width = w!!, height = h!!, paper = style) else null
            val sizeName = when (size) { 0 -> null; 1 -> "2× size"; 2 -> "custom size"; else -> PAGE_SIZES[size - 3].first }
            title.text = if (sizeName == null) paperName else "$paperName on $sizeName"
            subtitle.text = if (!valid) "Enter a width and height to see the page." else listOfNotNull(
                if (w!! > h!!) "Landscape" else if (w < h) "Portrait" else "Square",
                "${(w * MM).roundToInt()} × ${(h * MM).roundToInt()} mm",
                if (keep) "imported background kept" else if(template!=null) "fixed page template" else "${colourName(style.color, PaperColors.papers)} paper",
                if (!keep && style.pattern != PaperPattern.PLAIN) "${colourName(style.guideColor, PaperColors.guides)} lines" else null
            ).joinToString(" · ")
            turnRow.visibility = if (size >= 2) View.VISIBLE else View.GONE
            dimensions.visibility = if (size == 2) View.VISIBLE else View.GONE
            // Line colour and spacing do nothing on plain paper.
            (lines + spacing).forEach { it.alpha = if (shown().pattern == PaperPattern.PLAIN) .45f else 1f }
            sizeArt.forEach { it.invalidate() }
            issue.visibility = View.GONE
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        }
        width.addTextChangedListener(watcher); height.addTextChangedListener(watcher)

        val sheet = Kit.Sheet(context, "Page & paper", "Apply") {
            runCatching {
                val w = if (size != 0) width.text.toString().toFloatOrNull() ?: error("Enter a valid width") else null
                val h = if (size != 0) height.text.toString().toFloatOrNull() ?: error("Enter a valid height") else null
                val ids = if (all) notebook.pages.map { it.id }.toSet() else setOf(pageId)
                val changed=if(template!=null) {
                    val asset=templateAsset ?: error("Template is loading. Try Apply again in a moment.")
                    check(!notebook.pages.any { it.id in ids && it.hasBackground } || replace) { "Enable Replace imported backgrounds first" }
                    applyTemplate?.invoke(ids,asset,shown(),w,h,replace) ?: false
                } else apply(ids,w,h,paper,replace)
                changed.also { if (!it) error("Notebook changed or busy. Reopen Page & paper.") }
            }.getOrElse { issue.text = it.message ?: "Could not change the page"; issue.visibility = View.VISIBLE; false }
        }

        fun strip(label: String, tiles: List<Kit.Tile>, tileWidth: Float, tileHeight: Float) = HorizontalScrollView(context).apply {
            contentDescription = label; isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tiles.forEachIndexed { i, tile -> addView(tile, LinearLayout.LayoutParams(context.dpi(tileWidth), context.dpi(tileHeight)).apply { if (i > 0) marginStart = context.dpi(8f) }) }
            })
        }
        fun scroll(view: View) = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(view) }

        sheet.add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                background = rounded(Kit.well(night), dp(24f)); dpi(14f).let { setPadding(it, it, it, it) }
                addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(context.dpi(190f), context.dpi(244f)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dpi(4f) })
                addView(Kit.section(context, "Apply to"))
                addView(scope)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(24f) })
        }, 0f)
        sheet.add(Kit.section(context, "Paper"), 8f)
        sheet.add(strip("Page template", paperTiles, 92f, 140f), 0f)
        if (applyTemplate != null) {
            val gallery=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;contentDescription="Saved page templates" }
            sheet.add(gallery,8f)
            lateinit var reloadTemplates: () -> Unit
            reloadTemplates = {
                app.io.execute {
                    val loaded=runCatching { templates.list().map { it to templates.thumbnail(it) } }
                    main.post {
                        if(sheet.dialog.isShowing) loaded.onSuccess { entries ->
                            gallery.removeAllViews()
                            if(entries.isNotEmpty()) {
                                gallery.addView(Kit.section(context,"Saved templates"))
                                val tiles=entries.map { (record,bitmap) ->
                                    Kit.Tile(context,Kit.picture(context,Pictogram { c,box,_ -> c.drawBitmap(bitmap,null,box,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap=true }) }),record.name,false) {
                                        val token=++generation
                                        template=record;templateAsset=null;templateBitmap=bitmap
                                        paper=record.paper;paperName=record.name;size=2
                                        width.setText(record.width.toString());height.setText(record.height.toString())
                                        paperTiles.forEach { it.chosen=false };showStyle();refresh()
                                        app.io.execute {
                                            val asset=runCatching { templates.load(record) }
                                            main.post { if(token==generation && sheet.dialog.isShowing) asset.onSuccess { templateAsset=it }.onFailure { issue.text=it.message;issue.visibility=View.VISIBLE } }
                                        }
                                    }.apply { contentDescription="Page template ${record.name}" }
                                }
                                templateTiles=tiles;Kit.oneOf(tiles);gallery.addView(strip("Saved page template choices",tiles,92f,140f))
                            }
                        }.onFailure { issue.text=it.message;issue.visibility=View.VISIBLE }
                    }
                }
            }
            sheet.add(Kit.row(context,"Save page as template","Keep this page as a fixed background for new pages",chevron=true) {
                val name=Kit.field(context,"Template name").apply { contentDescription="Page template name" }
                val error=Kit.text(context,"",Kit.Type.CAPTION,secondary=true)
                var saving=false
                lateinit var save: Kit.Sheet
                save=Kit.Sheet(context,"Save page template","Save") {
                    if(!saving) {
                        saving=true;save.actionButton?.isEnabled=false
                        val title=name.text.toString()
                        app.io.execute {
                            val result=runCatching { templates.save(title,notebook,pageId,app.cacheDir) }
                            main.post {
                                saving=false;save.actionButton?.isEnabled=true
                                result.onSuccess { save.dialog.dismiss();reloadTemplates() }.onFailure { error.text=it.message ?: "Template could not be saved" }
                            }
                        }
                    }
                    false
                }
                save.add(name);save.add(Kit.text(context,"Paper and page content become one fixed image. New writing stays editable.",Kit.Type.CAPTION,secondary=true));save.add(error);save.show()
            }.apply { contentDescription="Save page as template" },8f)
            main.post { reloadTemplates() }
        }
        sheet.add(Kit.section(context, "Size"), 8f)
        sheet.add(strip("Page settings size", sizeTiles, 84f, 112f), 0f)
        sheet.add(Kit.text(context, "Handwriting and pictures scale with the page.", Kit.Type.CAPTION, secondary = true), 8f)
        if (standardScale != null && context.personalTools) sheet.add(Kit.row(context, "Match Standard pen scale…",
            "For imported pages: keep their proportions and existing appearance; match new ink to Standard pages.", chevron = true) {
            val ids = if (all) notebook.pages.map { it.id }.toSet() else setOf(pageId)
            standardScaleConfirmation(context, ids.size) {
                standardScale(ids).also { if (it) sheet.dialog.dismiss() }
            }
        }, 8f)
        // P412 (B52): rare, page or notebook scope, reversible with Undo; it lives beside the pen-scale correction.
        fun shapeIds() = (if (all) notebook.pages else listOf(page)).filter { it.imageBackground == null && it.standardShapeFit() != null }.map { it.id }.toSet()
        if (standardShape != null && context.personalTools && notebook.pages.any { it.imageBackground == null && it.standardShapeFit() != null }) sheet.add(Kit.row(context, "Match Standard page shape…",
            "For imported pages: show them as wide as pages made here. Blank paper is added at the sides; nothing is stretched.", chevron = true) {
            val ids = shapeIds()
            if (ids.isEmpty()) { issue.text = "This page already has the Standard shape."; issue.visibility = View.VISIBLE }
            else standardShapeConfirmation(context, ids.size) { done -> standardShape(ids) { error -> if (error == null) sheet.dialog.dismiss(); done(error) } }
        }, 8f)
        sheet.add(turnRow, 12f)
        sheet.add(dimensions, 12f)
        sheet.add(Kit.section(context, "Paper colour"), 8f)
        sheet.add(scroll(paperColors), 0f)
        sheet.add(Kit.section(context, "Line colour"), 8f)
        sheet.add(scroll(lineColors), 0f)
        sheet.add(Kit.Group(context).apply {
            addView(Kit.row(context, "Line spacing", trailing = spacing))
            if (anyBackground) addView(Kit.row(context, "Replace imported PDF backgrounds", "Off keeps the PDF and draws nothing over it. Undo restores.", trailing = replacing))
            addView(custom)
        }, 20f)
        sheet.add(issue, 12f)
        refresh()
        return sheet.show()
    }
    internal fun standardScaleConfirmation(context: Context, count: Int, change: () -> Boolean): Kit.Sheet {
        val issue = Kit.text(context, "", Kit.Type.CAPTION, secondary = true).apply { visibility = View.GONE }
        val confirmation = Kit.Sheet(context, "Match Standard pen scale?", "Change scale") {
            runCatching { change() }.getOrElse { issue.text = it.message; false }.also { ok ->
                if (!ok) {
                    if (issue.text.isNullOrBlank()) issue.text = "Notebook changed or busy. Reopen Page & paper."
                    issue.visibility = View.VISIBLE
                }
            }
        }
        confirmation.add(Kit.text(context,
            "Change the coordinate scale of $count selected page(s). Page proportions and existing handwriting, pictures and PDFs keep their appearance. Newly drawn ink uses the same scale as Standard pages. This does not infer a physical paper size. Undo restores the previous scale.", Kit.Type.BODY))
        confirmation.add(Kit.text(context, "Other unapplied Page & paper choices will not be applied.", Kit.Type.CAPTION, secondary = true))
        confirmation.add(issue)
        return confirmation.show()
    }
    /** [change] starts the work and reports to its callback: null when done, otherwise the reason. */
    internal fun standardShapeConfirmation(context: Context, count: Int, change: ((String?) -> Unit) -> Boolean): Kit.Sheet {
        val issue = Kit.text(context, "", Kit.Type.CAPTION, secondary = true).apply { visibility = View.GONE }
        lateinit var confirmation: Kit.Sheet
        confirmation = Kit.Sheet(context, "Match Standard page shape?", "Change shape") {
            confirmation.actionButton?.isEnabled = false
            issue.text = "Preparing pages…"; issue.visibility = View.VISIBLE
            val started = runCatching { change { error ->
                if (error == null) confirmation.dialog.dismiss()
                else { issue.text = error; confirmation.actionButton?.isEnabled = true }
            } }.getOrElse { issue.text = it.message; false }
            if (!started) {
                if (issue.text == "Preparing pages…") issue.text = "Notebook changed or busy. Reopen Page & paper."
                confirmation.actionButton?.isEnabled = true
            }
            false
        }
        confirmation.add(Kit.text(context,
            "Give $count selected page(s) the Standard page shape (1000 × 1400, or turned for landscape pages), so they show as wide as pages made here. Each page's writing, pictures and background shrink evenly together and sit centred, with blank paper added at the sides or top and bottom. Nothing is stretched or cut off. Pages with a photo background are left as they are. Undo restores the previous shape.", Kit.Type.BODY))
        confirmation.add(Kit.text(context, "Other unapplied Page & paper choices will not be applied.", Kit.Type.CAPTION, secondary = true))
        confirmation.add(issue)
        return confirmation.show()
    }

}
