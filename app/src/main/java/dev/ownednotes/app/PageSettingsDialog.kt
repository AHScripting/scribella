package dev.ownednotes.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import dev.ownednotes.core.*
import kotlin.math.roundToInt

/**
 * P201: Page & paper, one Glass sheet from the paper board. Paper, colours, line spacing, size and orientation are
 * staged together and committed by Apply, with explicit opt-in before replacing any imported background.
 * P613: laid out as the design canvas's board 7, "Page & paper on one screen": a centred card with nothing to scroll on
 * a tablet. Paper and Size sit side by side as grids of small tiles, four to a row (saved templates join Paper); Colour
 * and Lines are one row each with spacing at the end; the rarer tools are short chips at the foot, and "Replace imported
 * PDF backgrounds" is the chip switch "Paper over PDF". Below 700 dp the two grids stack.
 */
object PageSettingsDialog {
    private const val MM = 25.4f / 72f
    // The board's chip icons, on a 24-unit grid.
    private const val STAR = "M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z"
    private const val BOOKMARK = "M6 3.5h12v17l-6-4-6 4z"
    private const val PDF = "M7 3.5h7l4 4v13H7zM10 13h6M10 16.5h4"
    private const val PEN = "M4 20l4-1L19 8l-3-3L5 16zM14 7l3 3"
    private const val SHAPE = "M8 4h8v16H8zM4 9v6M20 9v6"
    /** Shorter names under the small tiles; TalkBack (and the tests) keep the full names. */
    private val SHORT = mapOf("Narrow ruled" to "Narrow", "Legal pad" to "Legal", "US Letter" to "Letter", "2× size" to "2×")

    fun show(context: Context, notebook: Notebook, pageId: String,
        standardScale: ((Set<String>) -> Boolean)? = null,
        standardShape: ((Set<String>, (String?) -> Unit) -> Boolean)? = null,
        applyTemplate: ((Set<String>, ImageAsset, PaperStyle, Float?, Float?, Boolean) -> Boolean)? = null,
        told: ((String) -> Unit)? = null,
        apply:(Set<String>, Float?, Float?, PaperStyle?, Boolean) -> Boolean): Kit.Sheet {
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
        // P633 (B54): a new paper leaves imported pages alone unless Paper over PDF is on; count them so Apply can say so.
        fun kept() = if (paper == null || replace || template != null) 0
            else (if (all) notebook.pages else listOf(page)).count { it.hasBackground }

        fun field(label: String, value: Float) = Kit.field(context, label, value.toString()).apply {
            contentDescription = label; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val width = field("Page width", page.width); val height = field("Page height", page.height)
        fun typed() = (width.text.toString().toFloatOrNull() ?: page.width).coerceAtLeast(1f) to (height.text.toString().toFloatOrNull() ?: page.height).coerceAtLeast(1f)
        fun oriented(d: Pair<Float, Float>) = if (landscape) maxOf(d.first, d.second) to minOf(d.first, d.second) else minOf(d.first, d.second) to maxOf(d.first, d.second)

        lateinit var refresh: () -> Unit

        // Under 700 dp Paper and Size stack; under 600 dp (a phone) the scope also moves under the preview (P592).
        // P650: chosen again by arrange() when the tablet turns with the sheet open.
        fun widthDp() = context.resources.configuration.screenWidthDp
        val preview = PageFormatPreview(context)
        val title = Kit.text(context, "", Kit.Type.TITLE).apply { textSize = 20f; maxLines = 2 }
        val subtitle = Kit.text(context, "", Kit.Type.BODY, secondary = true).apply { textSize = 13f }
        val many = notebook.pages.size
        val scope = Kit.segmented(context, listOf("This page", if (many > 1) "All $many pages" else "All pages"), 0) { all = it == 1; refresh() }
            .apply { contentDescription = "Page settings scope" }

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
            Kit.Tile(context, Kit.picture(context, art), SHORT[name] ?: name, style == null, compact = true) {
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
            Kit.Tile(context, Kit.picture(context, art).also { sizeArt += it }, SHORT[name] ?: name, position == 0, compact = true) {
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

        // Orientation (and, for Custom, the width and height) show only once a size is being set.
        val turn = Kit.segmented(context, listOf("Portrait", "Landscape"), if (landscape) 1 else 0) { side ->
            landscape = side == 1
            if (size >= 3) oriented(PAGE_SIZES[size - 3].second).let { width.setText(it.first.toString()); height.setText(it.second.toString()) }
            else if (size == 2) typed().let { (w, h) -> if ((w > h) != landscape) { width.setText(h.toString()); height.setText(w.toString()) } }
            refresh()
        }.apply { contentDescription = "Page orientation" }
        val dimensions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(width, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Kit.text(context, "×", Kit.Type.HEADLINE, secondary = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(context.dpi(28f), WRAP_CONTENT))
            addView(height, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Kit.text(context, "pt", Kit.Type.CAPTION, secondary = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dpi(8f) })
        }
        val shapeRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun arrangeShape(narrow: Boolean) = shapeRow.apply {
            removeAllViews(); orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            addView(turn, LinearLayout.LayoutParams(if (narrow) MATCH_PARENT else context.dpi(240f), WRAP_CONTENT))
            addView(dimensions, if (narrow) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dpi(10f) }
                else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dpi(16f) })
        }

        // Colours and line spacing change the chosen paper (or the current one, when kept).
        fun restyle(change: (PaperStyle) -> PaperStyle) { paper = change(shown()); refresh() }
        paperColors = Kit.Swatches(context, PaperColors.papers.map { it.first.toInt() to "${it.second} paper colour" }, null, compact = true) { i ->
            restyle { it.copy(color = PaperColors.papers[i].first) }
        }
        lineColors = Kit.Swatches(context, PaperColors.guides.map { it.first.toInt() to "${it.second} lines" }, null, lines = true, compact = true) { i ->
            restyle { it.copy(guideColor = PaperColors.guides[i].first) }
        }
        spacing = Kit.stepper(context, "line spacing", (shown().spacing * MM).roundToInt(), 6..90, { "$it mm" }) { mm ->
            restyle { it.copy(spacing = (mm / MM).coerceIn(16f, 256f)) }
        }
        showStyle()

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
                if (!keep && style.pattern != PaperPattern.PLAIN) "${colourName(style.guideColor, PaperColors.guides)} lines" else null,
                kept().takeIf { all && it > 0 }?.let { "$it imported ${if (it == 1) "page keeps its" else "pages keep their"} background" }
            ).joinToString(" · ")
            shapeRow.visibility = if (size >= 2) View.VISIBLE else View.GONE
            dimensions.visibility = if (size == 2) View.VISIBLE else View.GONE
            // Line colour and spacing do nothing on plain paper.
            listOf<View>(lineColors, spacing).forEach { it.alpha = if (shown().pattern == PaperPattern.PLAIN) .45f else 1f }
            sizeArt.forEach { it.invalidate() }
            issue.visibility = View.GONE
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        }
        width.addTextChangedListener(watcher); height.addTextChangedListener(watcher)

        val sheet = Kit.Sheet(context, "Page & paper", "Apply", centred = true) {
            runCatching {
                val w = if (size != 0) width.text.toString().toFloatOrNull() ?: error("Enter a valid width") else null
                val h = if (size != 0) height.text.toString().toFloatOrNull() ?: error("Enter a valid height") else null
                val ids = if (all) notebook.pages.map { it.id }.toSet() else setOf(pageId)
                val skipped = kept()
                val changed=if(template!=null) {
                    val asset=templateAsset ?: error("Template is loading. Try Apply again in a moment.")
                    check(!notebook.pages.any { it.id in ids && it.hasBackground } || replace) { "Turn on Paper over PDF first" }
                    applyTemplate?.invoke(ids,asset,shown(),w,h,replace) ?: false
                } else apply(ids,w,h,paper,replace)
                changed.also { if (!it) error("Notebook changed or busy. Reopen Page & paper.") }
                    .also { if (skipped > 0) told?.invoke(keptNote(ids.size - skipped, skipped)) }
            }.getOrElse { issue.text = it.message ?: "Could not change the page"; issue.visibility = View.VISIBLE; false }
        }

        // The board's pieces: small caps labels, hairlines, four-to-a-row tile grids and pill chips.
        val rule = if (night) 0x1fffffff else 0x141d2433
        fun label(text: String) = TextView(context).apply {
            this.text = text.uppercase(); textSize = 12f; typeface = Tokens.SANS_MEDIUM; letterSpacing = .06f; maxLines = 1
            setTextColor(Kit.caps(night)); isAccessibilityHeading = true
        }
        fun hairline() = View(context).apply { setBackgroundColor(rule) }
        fun grid(into: LinearLayout, tiles: List<Kit.Tile>) {
            tiles.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            into.removeAllViews()
            tiles.chunked(4).forEachIndexed { r, row ->
                into.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
                    repeat(4 - row.size) { addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (r > 0) topMargin = context.dpi(8f) })
            }
        }
        fun chip(label: String, icon: String, trailing: View? = null, onClick: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpi(40f)
            setPadding(context.dpi(10f), context.dpi(4f), context.dpi(if (trailing != null) 8f else 14f), context.dpi(4f))
            background = RippleDrawable(ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f0a66d9), rounded(Kit.well(night), context.dp(20f)), null)
            addView(ImageView(context).apply {
                setImageDrawable(Kit.Icon(icon, Glass.accent(night), context.dpi(20f))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dpi(20f), context.dpi(20f)))
            addView(TextView(context).apply {
                text = label; textSize = 13f; typeface = Tokens.SANS_MEDIUM; maxLines = 1; includeFontPadding = false; setTextColor(Glass.text(night))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dpi(7f) })
            trailing?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dpi(8f) }) }
            contentDescription = label; isClickable = true; isFocusable = true
            setOnClickListener { runCatching(onClick) }
        }

        // Preview, its name and the scope (beside the preview, or under it on a phone).
        val named = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(subtitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dpi(2f) })
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        head.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(2f), 0, 0)
            addView(FrameLayout(context).apply {
                background = rounded(Kit.well(night), dp(18f)); dpi(13f).let { setPadding(it, it, it, it) }
                addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(context.dpi(96f), context.dpi(124f)))
            addView(named, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dpi(18f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun arrangeHead(narrow: Boolean) {
            (scope.parent as? ViewGroup)?.removeView(scope)
            if (narrow) head.addView(scope, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dpi(12f) })
            else named.addView(scope, LinearLayout.LayoutParams(context.dpi(300f), WRAP_CONTENT).apply { topMargin = context.dpi(10f) })
        }
        sheet.add(head, 0f)
        sheet.add(hairline(), 16f).layoutParams.height = 1

        // Paper | Size.
        val paperGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; contentDescription = "Page template" }
        val sizeGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; contentDescription = "Page settings size" }
        grid(paperGrid, paperTiles); grid(sizeGrid, sizeTiles)
        fun column(name: String, body: View) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = context.dpi(8f) })
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val paperColumn = column("Paper", paperGrid); val sizeColumn = column("Size", sizeGrid); val divider = hairline()
        val paperAndSize = LinearLayout(context)
        fun arrangeColumns(wide: Boolean) = paperAndSize.apply {
            removeAllViews(); orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (wide) {
                addView(paperColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(divider, LinearLayout.LayoutParams(1, MATCH_PARENT).apply { marginStart = context.dpi(14f); marginEnd = context.dpi(14f) })
                addView(sizeColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            } else {
                addView(paperColumn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(sizeColumn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dpi(16f) })
            }
        }
        var arranged: Pair<Boolean, Boolean>? = null
        fun arrange() {
            val now = (widthDp() >= 700) to (widthDp() < 600)
            if (now == arranged) return
            arranged = now; arrangeColumns(now.first); arrangeShape(now.second); arrangeHead(now.second)
        }
        arrange()
        sheet.onTurned { arrange() }
        sheet.add(paperAndSize, 16f)
        sheet.add(shapeRow, 14f)

        // Colour, then Lines with the spacing at the end.
        fun swatchRow(name: String, swatches: View, end: View? = null) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // At least the board's 52 dp, so the dots line up; large text widens it rather than wrapping the word.
            addView(label(name).apply { minWidth = context.dpi(52f) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dpi(10f) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(swatches) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            end?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dpi(12f) }) }
        }
        sheet.add(swatchRow("Colour", paperColors), 16f)
        sheet.add(swatchRow("Lines", lineColors, spacing), 6f)

        // The rarer tools, as chips. Each one that changes the book explains itself in its own confirmation.
        val chips = ToolControls.Flow(context, context.dpi(8f), context.dpi(8f))
        chips.addView(chip("Custom paper", STAR) {
            PaperDialog.show(context, page.copy(paper = shown(), pdfBackground = null, imageBackground = null)) { style ->
                generation++;template=null;templateAsset=null;templateBitmap=null;templateTiles.forEach { it.chosen=false }
                paper = style; paperName = "Custom paper"; paperTiles.forEach { it.chosen = false }; showStyle(); refresh(); true
            }
        }.apply { contentDescription = "Customize page paper" })
        if (applyTemplate != null) {
            lateinit var reloadTemplates: () -> Unit
            reloadTemplates = {
                app.io.execute {
                    val loaded=runCatching { templates.list().map { it to templates.thumbnail(it) } }
                    main.post {
                        if(sheet.dialog.isShowing) loaded.onSuccess { entries ->
                            val tiles=entries.map { (record,bitmap) ->
                                Kit.Tile(context,Kit.picture(context,Pictogram { c,box,_ -> c.drawBitmap(bitmap,null,box,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap=true }) }),record.name,false,compact=true) {
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
                            // Saved templates join the Paper tiles, after the papers.
                            templateTiles=tiles;Kit.oneOf(tiles);grid(paperGrid,paperTiles+tiles)
                        }.onFailure { issue.text=it.message;issue.visibility=View.VISIBLE }
                    }
                }
            }
            chips.addView(chip("Save as template", BOOKMARK) {
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
            }.apply { contentDescription="Save page as template" })
            main.post { reloadTemplates() }
        }
        // Off keeps imported PDF pages as they are; on draws the chosen paper in their place. Undo restores.
        if (anyBackground) {
            val replacing = Kit.Switch(context, false, "Paper over PDF", small = true) { replace = it; refresh() }
            chips.addView(chip("Paper over PDF", PDF, replacing) { replacing.performClick() }.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        if (standardScale != null && context.personalTools) chips.addView(chip("Match pen size", PEN) {
            val ids = if (all) notebook.pages.map { it.id }.toSet() else setOf(pageId)
            standardScaleConfirmation(context, ids.size) {
                standardScale(ids).also { if (it) sheet.dialog.dismiss() }
            }
        }.apply { contentDescription = "Match Standard pen scale…" })
        // P412 (B52): rare, page or notebook scope, reversible with Undo; it lives beside the pen-scale correction.
        fun shapeIds() = (if (all) notebook.pages else listOf(page)).filter { it.imageBackground == null && it.standardShapeFit() != null }.map { it.id }.toSet()
        if (standardShape != null && context.personalTools && notebook.pages.any { it.imageBackground == null && it.standardShapeFit() != null }) chips.addView(chip("Match page shape", SHAPE) {
            val ids = shapeIds()
            if (ids.isEmpty()) { issue.text = "This page already has the Standard shape."; issue.visibility = View.VISIBLE }
            else standardShapeConfirmation(context, ids.size) { done -> standardShape(ids) { error -> if (error == null) sheet.dialog.dismiss(); done(error) } }
        }.apply { contentDescription = "Match Standard page shape…" })
        sheet.add(hairline(), 16f).layoutParams.height = 1
        sheet.add(chips, 14f)
        sheet.add(issue, 10f)
        refresh()
        return sheet.show()
    }
    /** P633 (B54): said after Apply when imported pages kept their background, so "All pages" does not look as if it changed every page. */
    internal fun keptNote(changed: Int, kept: Int): String {
        fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"
        val first = if (changed > 0) "New paper on ${pages(changed)}. " else ""
        return first + "${pages(kept)} kept ${if (kept == 1) "its" else "their"} imported background. Turn on Paper over PDF to change ${if (kept == 1) "it" else "them"} too."
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
