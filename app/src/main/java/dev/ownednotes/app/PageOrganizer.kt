package dev.ownednotes.app

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.Page

/**
 * P201 step 6: the page organizer, as on the Glass "Page organizer" board. Every page of the open notebook as a grid of
 * previews ([PagePanel] with [PagePanel.grid]) under a glass top bar (Done, the notebook's name and counts, Select), with
 * separate Pages, Bookmarks and PDF Contents tabs. PDF sources expand independently. While choosing, a
 * glass bar at the foot counts the chosen pages with Move and Delete; they open the same dialogs as the page strip, with
 * the same guards and Undo. Tapping a page opens it ([onOpen]); [onClose] leaves the organizer.
 */
class PageOrganizer(
    private val activity: MainActivity,
    private val repository: NotebookRepository,
    thumbnails: PageThumbnails,
    private val onOpen: (pageId: String) -> Unit,
    private val onClose: () -> Unit,
    private val c: Context = activity,
) : FrameLayout(c) {
    private val night = c.night
    val grid = PagePanel(c, repository, thumbnails)
    /** P612: the held page's actions (Duplicate, Settings, Delete). */
    internal val holdBar = PageHoldBar(activity, night)
    internal val title = TextView(c)
    internal val subtitle = TextView(c)
    internal val select = TextView(c)
    internal val chosenCount = TextView(c)
    internal val move = barButton(PageStripAction.MOVE.label.removeSuffix("…"), MOVE, Glass.text(night))
    internal val delete = barButton(PageStripAction.DELETE.label.removeSuffix("…"), DELETE, Kit.dangerText(night))
    internal val bulkBar = LinearLayout(c)
    /** Bookmark tab rows, also exposed to focused navigation tests. */
    internal val bookmarkViews = mutableListOf<TextView>()
    private val bookmarkList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val bookmarks = ScrollView(c).apply { addView(bookmarkList); isFillViewport = true }
    private val contentsList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val contents = ScrollView(c).apply { addView(contentsList); isFillViewport = true }
    internal val pdfHeaders = linkedMapOf<String, View>()
    internal val chapterViews = mutableListOf<View>()
    internal var currentTab = 0; private set
    internal val tabs = Kit.segmented(c, listOf("Pages", "Bookmarks", "PDF Contents"), 0) { chooseTab(it) }.apply { contentDescription = "Page panel view" }
    internal var pdfChoosing=false;private set
    internal val pdfChosen=mutableSetOf<String>()
    internal var pdfRemovalSheet:Kit.Sheet?=null;private set
    private val expanded = mutableSetOf<String>()
    private val outlines = mutableMapOf<String, List<PdfContents.Entry>>()
    private val errors = mutableMapOf<String, String>()
    private val loading = mutableSetOf<String>()
    private var epoch = 0
    private var shownSources: List<Pair<String, String>>? = null
    private var shownMarks: List<Triple<String, String, Int>>? = null
    private val observer: () -> Unit = { update() }

    init {
        setBackgroundColor(Kit.desk(night)); isClickable = true
        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        column.addView(topBar(), LinearLayout.LayoutParams(MATCH_PARENT, if (c.largeText) WRAP_CONTENT else c.dpi(84f)))
        column.addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(c.dpi(24f), 0, c.dpi(24f), c.dpi(12f))
        })
        val body = FrameLayout(c)
        grid.night = night; grid.background = null; grid.grid = true; grid.footer = c.dp(110f)
        grid.onPageSelected = { id -> post { onOpen(id) } }
        grid.onPageActions = { activity.pagePanelActions(it) }
        // P612: as in the strip, the held page's actions show at once.
        grid.onPageHeld = { id, bounds -> holdBar.show(grid, id, bounds) }
        grid.onPageHeldEnd = { holdBar.hide() }
        grid.onRefused = { activity.message(it) }
        grid.onChoiceChanged = { showChoice() }
        body.addView(grid, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listOf(bookmarks, contents).forEach { panel ->
            panel.visibility = View.GONE
            panel.setPadding(c.dpi(24f), 0, c.dpi(24f), c.dpi(24f))
            body.addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildBulkBar()
        addView(bulkBar, LayoutParams(WRAP_CONTENT, c.dpi(70f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = c.dpi(24f) })
        showChoice()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); repository.listen(observer) }
    override fun onDetachedFromWindow() { epoch++; repository.unlisten(observer); holdBar.hide(); super.onDetachedFromWindow() }

    /** Back: stop choosing first, then leave the organizer. */
    fun back() { if(pdfChoosing){pdfChoosing=false;pdfChosen.clear();showContents();showChoice()}else if (grid.choosing) grid.stopChoosing() else onClose() }

    private fun topBar() = FrameLayout(c).apply {
        setPadding(c.dpi(24f), c.dpi(16f), c.dpi(24f), c.dpi(16f))
        // P592: large text on a phone squeezed the notebook's name between Done and Select ("Note…"); there the name
        // goes on its own line under the two buttons.
        val under = c.largeText && c.resources.configuration.screenWidthDp < 600
        val done = Kit.button(c, "Done", Kit.Button.GLASS) { onClose() }.apply { contentDescription = "Close all pages" }
        addView(done, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or if (under) Gravity.TOP else Gravity.CENTER_VERTICAL))
        val names = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            title.apply { textSize = 19f; typeface = Kit.Type.TITLE.typeface; setTextColor(Glass.text(night)); maxLines = if (under) 2 else 1; ellipsize = TextUtils.TruncateAt.END; isAccessibilityHeading = true }
            subtitle.apply { textSize = 12.5f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(night)); maxLines = 1 }
            if (under) { title.gravity = Gravity.CENTER_HORIZONTAL; subtitle.gravity = Gravity.CENTER_HORIZONTAL }
            addView(title); addView(subtitle)
        }
        if (under) {
            addView(names, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            done.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
                val top = maxOf(bottom, paddingTop + c.dpi(52f)) - paddingTop + c.dpi(10f)
                (names.layoutParams as LayoutParams).let { if (it.topMargin != top) { it.topMargin = top; names.post { names.requestLayout() } } }
            }
        } else addView(names, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = c.dpi(120f); rightMargin = c.dpi(120f) })
        val pill = LinearLayout(c).apply {
            background = Glass.pane(c, night, 26f); elevation = c.dp(4f); gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dpi(5f), c.dpi(5f), c.dpi(5f), c.dpi(5f))
            select.apply {
                textSize = 14f; typeface = Tokens.SANS_SEMIBOLD; gravity = Gravity.CENTER; minWidth = c.dpi(84f)
                setPadding(c.dpi(16f), 0, c.dpi(16f), 0); isClickable = true; isFocusable = true
                setOnClickListener {
                    if(currentTab==2){pdfChoosing=!pdfChoosing;pdfChosen.clear();showContents();showChoice()}
                    else if (grid.choosing) grid.stopChoosing() else grid.startChoosing()
                }
            }
            addView(select, LinearLayout.LayoutParams(WRAP_CONTENT, c.dpi(40f)))
        }
        addView(pill, LayoutParams(WRAP_CONTENT, c.dpi(52f), Gravity.END or if (under) Gravity.TOP else Gravity.CENTER_VERTICAL))
    }

    /** One of the bulk bar's actions: its icon over a short word, as on the board. */
    private fun barButton(label: String, path: String, color: Int) = TextView(c).apply {
        text = label; textSize = 11.5f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(color); gravity = Gravity.CENTER
        minWidth = c.dpi(62f); setPadding(c.dpi(12f), 0, c.dpi(12f), 0); compoundDrawablePadding = c.dpi(3f)
        setCompoundDrawablesRelative(null, Kit.Icon(path, color, c.dpi(20f)).apply { setBounds(0, 0, c.dpi(20f), c.dpi(20f)) }, null, null)
        background = ripple(night, c.dp(16f)); isClickable = true; isFocusable = true
        contentDescription = "$label chosen pages"
    }

    private fun buildBulkBar() {
        bulkBar.apply {
            gravity = Gravity.CENTER_VERTICAL; background = Glass.pane(c, night, 35f); elevation = c.dp(10f)
            setPadding(c.dpi(22f), 0, c.dpi(10f), 0)
            chosenCount.apply { textSize = 15f; typeface = Kit.Type.TITLE.typeface; setTextColor(Glass.text(night)) }
            addView(chosenCount, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = c.dpi(10f) })
            addView(View(c).apply { setBackgroundColor(if (night) Glass.RULE_NIGHT else Glass.RULE) }, LinearLayout.LayoutParams(c.dpi(1f), c.dpi(34f)).apply { marginEnd = c.dpi(6f) })
            addView(move, LinearLayout.LayoutParams(WRAP_CONTENT, c.dpi(50f)))
            addView(delete, LinearLayout.LayoutParams(WRAP_CONTENT, c.dpi(50f)))
        }
        move.setOnClickListener { activity.movePagesDialog(grid.chosenIds) }
        delete.setOnClickListener { if(currentTab==2)removeChosenPdfPages()else activity.deletePagesDialog(grid.chosenIds) }
    }

    /** As the strip (P178): Select becomes Done while choosing; the bar counts the chosen pages and keeps one page back. */
    private fun showChoice() {
        select.visibility = if (currentTab == 0 || currentTab == 2) View.VISIBLE else View.GONE
        (select.parent as? View)?.visibility = select.visibility
        val on = if(currentTab==2)pdfChoosing else grid.choosing && currentTab==0; val count = if(currentTab==2)pdfChosen.size else grid.chosenIds.size; val all = count >= grid.pageCount
        select.text = if (on) PageStripAction.DONE.label else PageStripAction.SELECT.label
        select.contentDescription = if (on) "Done choosing pages" else "Select pages"
        select.setTextColor(if (on) Color.WHITE else Kit.accentText(night))
        select.background = rounded(if (on) Glass.accent(night) else Kit.accentFill(night), c.dp(20f))
        bulkBar.visibility = if (on) View.VISIBLE else View.GONE
        move.visibility=if(currentTab==2)View.GONE else View.VISIBLE
        chosenCount.text = when {count==0 && currentTab==2 -> "Choose PDFs or chapters"; count == 0 -> "Tap pages to choose"; all && currentTab!=2 -> "All chosen · one must stay"; else -> "$count pages selected" }
        listOf(move, delete).forEach { it.isEnabled = count > 0 && (!all || currentTab==2); it.alpha = if (it.isEnabled) 1f else .4f }
    }

    private fun update() {
        val book = repository.session?.notebook ?: run { post { onClose() }; return }
        val marks = book.pages.withIndex().filter { it.value.bookmark != null }
        title.text = book.title
        val pages = if (book.pages.size == 1) "1 page" else "${book.pages.size} pages"
        subtitle.text = pages + when (marks.size) { 0 -> ""; 1 -> " · 1 bookmark"; else -> " · ${marks.size} bookmarks" }
        val shown = marks.map { Triple(it.value.id, it.value.bookmark!!, it.index * 8 + it.value.outlineLevel) }
        if (shown != shownMarks) { shownMarks = shown; showBookmarks(marks.map { it.value to it.index }) }
        val current = repository.selectedPageId
        bookmarkViews.forEach { row ->
            val on = row.tag == current
            row.background = if (on) rounded(Kit.accentFill(night), c.dp(14f)) else ripple(night, c.dp(14f))
            row.typeface = if (on) Tokens.SANS_SEMIBOLD else Tokens.SANS
        }
        val sources = book.pages.mapNotNull { page -> page.pdfBackground?.let { page.id to "${it.assetId}:${it.pageIndex}" } }
        if (shownSources != sources) { shownSources = sources; if (currentTab == 2) showContents() }
        showChoice()
    }

    /** The bookmarks in page order, indented by their outline level; tapping one makes its page current here. */
    private fun showBookmarks(marks: List<Pair<Page, Int>>) {
        bookmarkList.removeAllViews(); bookmarkViews.clear()
        if (marks.isEmpty()) bookmarkList.addView(Kit.text(c, "Bookmark a page from Page actions to list it here.", Kit.Type.CAPTION, secondary = true).apply {
            setPadding(c.dpi(6f), c.dpi(12f), c.dpi(6f), 0)
        })
        for ((page, index) in marks) {
            val row = TextView(c).apply {
                tag = page.id
                text = page.bookmark; textSize = 14f; setTextColor(Glass.text(night)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = c.dpi(10f)
                setCompoundDrawablesRelative(Kit.Icon(RIBBON, PagePanel.BOOKMARK_RED, c.dpi(18f), fill = true).apply { setBounds(0, 0, c.dpi(18f), c.dpi(18f)) }, null, null, null)
                contentDescription = "Bookmark ${page.bookmark}, page ${index + 1}"
                isClickable = true; isFocusable = true
                setOnClickListener { show(page.id) }
            }
            run {
                row.setPadding(c.dpi(12f + 16f * (page.outlineLevel - 1)), 0, c.dpi(52f), 0)
                bookmarkList.addView(FrameLayout(c).apply {
                    addView(row, LayoutParams(MATCH_PARENT, c.dpi(46f)))
                    addView(TextView(c).apply {
                        text = "p. ${index + 1}"; textSize = 12f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Kit.caps(night)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = c.dpi(12f) })
                }, LinearLayout.LayoutParams(MATCH_PARENT, c.dpi(46f)))
            }
            bookmarkViews += row
        }
    }

    internal fun chooseTab(index: Int) {
        if (index !in 0..2) return
        if (index != currentTab) {grid.stopChoosing();pdfChoosing=false;pdfChosen.clear()}
        currentTab = index
        grid.visibility = if (index == 0) View.VISIBLE else View.GONE
        bookmarks.visibility = if (index == 1) View.VISIBLE else View.GONE
        contents.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 2) showContents()
        showChoice()
    }

    private fun showContents() {
        val note = repository.session?.notebook ?: return
        contentsList.removeAllViews(); pdfHeaders.clear(); chapterViews.clear()
        pdfChosen.retainAll(note.pages.filter {it.pdfBackground!=null}.map {it.id}.toSet())
        if(pdfChoosing)contentsList.addView(Kit.text(c,"Select a PDF or chapters to remove their pages. Chapter selection includes its nested sections. Confirm before removing; Undo restores everything.",Kit.Type.CAPTION,secondary=true))
        val sources = PdfContents.sources(note)
        expanded.retainAll(sources.toSet())
        if (sources.isEmpty()) contentsList.addView(Kit.text(c, "No PDFs in this notebook.", Kit.Type.BODY, secondary = true))
        for ((number, assetId) in sources.withIndex()) {
            val pages = note.pages.withIndex().filter { it.value.pdfBackground?.assetId == assetId }
            val open = assetId in expanded
            val header = Kit.row(c, "PDF ${number + 1}", detail = "${pages.size} pages · starts at notebook page ${pages.first().index + 1}",
                value = if(pdfChoosing)"${pages.count {it.value.id in pdfChosen}} selected" else if (open) "Collapse" else "Expand") {
                if(pdfChoosing){togglePdfPages(PdfContents.pageIds(note,assetId));expanded.add(assetId)}
                else if (!expanded.add(assetId)) expanded.remove(assetId)
                showContents();showChoice()
            }
            header.contentDescription = "PDF ${number + 1}, ${if (open) "expanded" else "collapsed"}, ${pages.size} pages"
            pdfHeaders[assetId] = header
            contentsList.addView(header)
            if (!open) continue
            if(pdfChoosing)contentsList.addView(Kit.button(c,"Hide chapters",Kit.Button.PLAIN,small=true){expanded.remove(assetId);showContents()})
            val list = outlines[assetId]
            when {
                list != null -> {
                    if (list.isEmpty()) contentsList.addView(Kit.text(c, "This PDF has no built-in contents.", Kit.Type.CAPTION, secondary = true))
                    list.forEach { entry ->
                        val target = PdfContents.target(note, assetId, entry)
                        val pageNumber = target?.let { p -> note.pages.indexOfFirst { it.id == p.id } + 1 }
                        val sectionIds=PdfContents.pageIds(note,assetId,entry,list)
                        val row = Kit.row(c, entry.title, value = if(pdfChoosing)"${sectionIds.count {it in pdfChosen}} / ${sectionIds.size} selected" else pageNumber?.let { "p. $it" },
                            detail = if (target == null && !pdfChoosing) "Target page is not in this notebook" else null) {
                            if(pdfChoosing){togglePdfPages(sectionIds);showContents();showChoice();return@row}
                            grid.refusal?.let { activity.message(it); return@row }
                            val current = repository.session?.notebook
                            if (current?.id != note.id) return@row
                            val page = PdfContents.target(current, assetId, entry) ?: return@row
                            show(page.id)
                            if (repository.selectedPageId == page.id) onOpen(page.id)
                        }
                        row.setPaddingRelative(row.paddingStart + c.dpi(16f * entry.depth.coerceAtMost(8)), row.paddingTop, row.paddingEnd, row.paddingBottom)
                        row.tag = assetId to entry; row.isEnabled = if(pdfChoosing)sectionIds.isNotEmpty()else target != null
                        row.contentDescription = "${entry.title}, level ${entry.depth + 1}, " + (pageNumber?.let { "page $it" } ?: "page not imported")
                        row.alpha = if (!row.isEnabled) .5f else 1f
                        row.isSelected=pdfChoosing && sectionIds.isNotEmpty() && pdfChosen.containsAll(sectionIds)
                        chapterViews += row; contentsList.addView(row)
                    }
                }
                errors[assetId] != null -> contentsList.addView(Kit.button(c, "Cannot read contents · Retry", Kit.Button.PLAIN) {
                    errors.remove(assetId); showContents()
                })
                else -> {
                    contentsList.addView(Kit.text(c, "Loading contents…", Kit.Type.CAPTION, secondary = true))
                    if (loading.add(assetId)) {
                        val generation = epoch
                        PdfLinks.executor.execute {
                            val result = runCatching { PdfContents.read(c.applicationContext, note, assetId) }
                            post {
                                if (epoch != generation || !isAttachedToWindow || repository.session?.notebook?.id != note.id) return@post
                                loading.remove(assetId)
                                result.onSuccess { outlines[assetId] = it }.onFailure { errors[assetId] = it.message ?: "Cannot read contents" }
                                if (currentTab == 2) showContents()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun togglePdfPages(ids:Set<String>) {
        if(ids.isNotEmpty() && pdfChosen.containsAll(ids))pdfChosen.removeAll(ids)else pdfChosen.addAll(ids)
    }
    internal fun removeChosenPdfPages() {
        val snapshot=repository.session?.notebook ?: return
        val ids=pdfChosen.toSet()
        if(ids.isEmpty() || repository.readOnly || !repository.canChangeNotebook || activity.ink.isEditing)return
        var keep=false
        val sheet=Kit.Sheet(c,"Remove ${ids.size} PDF pages?","Remove",onAction={
            if(repository.session?.notebook !== snapshot || repository.readOnly || !repository.canChangeNotebook || activity.ink.isEditing){activity.message("The notebook changed. Select PDF pages again.");false}
            else {
                repository.edit {it.removePdfPages(ids,keep)}
                val after=repository.session?.notebook
                pdfChoosing=false;pdfChosen.clear();showContents();showChoice()
                Kit.toast(this,if(keep)"PDF backgrounds removed; annotations kept"else "PDF pages removed",Kit.DANGER){
                    if(repository.session?.notebook === after && repository.canChangeNotebook && !repository.readOnly)repository.edit {it.undo()}
                };true
            }
        })
        sheet.add(Kit.text(c,"Remove selected imported pages and their writing, or keep the pages and annotations and remove only PDF backgrounds. Other pages stay unchanged. One Undo restores this operation. Removing every page leaves one blank page.",Kit.Type.BODY))
        sheet.add(Kit.segmented(c,listOf("Remove pages","Keep annotations"),0){keep=it==1}.apply{contentDescription="What happens to the pages"})
        sheet.actionButton?.setTextColor(Kit.dangerText(c.night))
        pdfRemovalSheet=sheet;sheet.show()
    }

    private fun show(pageId: String) {
        grid.refusal?.let { activity.message(it); return }
        if (repository.selectedPageId != pageId) repository.selectPage(pageId)
    }

    companion object {
        /** Wide enough for the board's sidebar beside a useful grid. */
        const val WIDE_DP = 1000f
        const val RIBBON = "M6 2h12v20l-6-5-6 5z"
        /** The "All pages" button's four previews. */
        const val GRID = "M4 4h6.5v7.5H4zM13.5 4H20v7.5h-6.5zM4 14.5h6.5V20H4zM13.5 14.5H20V20h-6.5z"
        const val MOVE = "M4 7h16M4 12h16M4 17h16"
        const val DELETE = "M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"
    }
}
