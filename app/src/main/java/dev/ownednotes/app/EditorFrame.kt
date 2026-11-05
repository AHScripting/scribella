package dev.ownednotes.app

import android.graphics.Rect
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.NotebookLimits
import dev.ownednotes.core.canFill
import dev.ownednotes.core.HandwritingLines
import dev.ownednotes.core.HandwritingRestyle
import dev.ownednotes.core.HandwritingRefine
import dev.ownednotes.core.ShapeRecognizer
import dev.ownednotes.core.hasBackground
import android.graphics.Color
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * P186: thickness of the docked tool strip, in dp: a 76 dp palette (shorter than the board's 112 dp at the user's review,
 * so the page gets the room), 14 dp from its edge, 6 dp toward the page.
 */
private const val TOOL_STRIP = 96f
/** P560: a side rail: two 48 dp columns, 6 dp from its edge and 6 dp toward the page. */
private const val TOOL_RAIL = 108f
/** P560: a tool's slot on a rail, short enough that four rows of tools leave the palette whole on a landscape screen. */
private const val RAIL_SLOT = 62f
/** P186: the floating app bar's height: the Glass board's single row of 52 dp pills, 14 dp from the top, 8 dp below. */
private const val TOP_BAR = 74f
private const val PANE_TAB = 40f
private const val PANE_MIN = 240f
private const val PANE_GAP = 2f
private const val PANE_HIT = 18f
/** P184 (G9): the command palette's entry and search hint. */
internal const val FIND_COMMAND = "Find a command…"
/** P186: the first row of the "Page and paper" menu. */
internal const val PAPER_SETTINGS = "Paper and page settings…"
/** P183 (G8): the width of the Settings group list, in dp. */
private const val SETTINGS_SIDEBAR = 300f

/** Typed view of `NotebookRepository.status`. The repository strings stay the source of truth. */
enum class SaveState {
    LOADING, OPENING, READY, SAVING, SAVED, WORKING, FAILED;
    companion object {
        fun of(status: String) = when (status) {
            "Saved locally" -> SAVED
            "Saving…" -> SAVING
            "Ready" -> READY
            "Loading…" -> LOADING
            "Opening notebook…" -> OPENING
            // P539: a step still under way ("Copying page…") has not failed.
            else -> if (status.endsWith("…")) WORKING else FAILED
        }
    }
}

/**
 * Editor frame (design build-order step 1): slim top app bar, top-docked tool bar, fault bar,
 * floating selection bar and the Settings screen. It only calls the existing editor APIs.
 */
class EditorFrame(private val activity: MainActivity, private val primaryCanvas: InkCanvas, private val primaryDocument: NotebookRepository) {
    private val c = activity
    /** P530 (F081/F082): the pane the shared toolbar and every command in this class act on. */
    private var ink = primaryCanvas
    private var repository = primaryDocument
    private var companionCanvas: InkCanvas? = null
    private var companionDocument: NotebookRepository? = null
    val splitOpen get() = companionCanvas != null
    val root = FrameLayout(c)

    val library = ChromeButton(c, "Library", Glyph.BACK, "Library")
    private val title = TextView(c)
    private val statusText = TextView(c)
    val previous = ChromeButton(c, "Previous page", Glyph.PREVIOUS)
    val pages = ChromeButton(c, "Page actions", Glyph.PAGES, "1 / 1")
    val next = ChromeButton(c, "Next page", Glyph.NEXT)
    /** B06 (P133): the old single "…" menu is split into Insert, Search, Import and export, and a smaller notebook menu. */
    /** P186: "Page and paper" on the Glass board: paper settings and the page actions, in one menu. */
    val pageSettings = ChromeButton(c, "Page and paper", Glyph.PAGE_SETTINGS)
    val insert = ChromeButton(c, "Insert", Glyph.PLUS)
    val capture = ChromeButton(c, "Capture and files", Glyph.CAPTURE)
    val study = ChromeButton(c, "Study", Glyph.STUDY)
    val search = ChromeButton(c, "Search and navigation", Glyph.SEARCH)
    val share = ChromeButton(c, "Export", Glyph.SHARE)
    val record = ChromeButton(c, "Study", Glyph.STUDY)
    val more = ChromeButton(c, "More", Glyph.MORE)
    val panelToggle = ChromeButton(c, "Show pages", Glyph.PANEL)
    val pagePanel = PagePanel(c, repository, activity.thumbnails)
    val continuous = ContinuousPageView(c)
    private val desk = FrameLayout(c)
    /** B07 (P131): the empty page after the last one, with an "Add page" button. */
    val addPageCard = AddPageCard(c, { addPageAtEnd() }, { turnBackFromAddPage() })
    private var shownNotebook: dev.ownednotes.core.Notebook? = null
    private var shownPageId: String? = null
    private var syncingStrip = false
    private var awaitingStripLayout = false
    private var selectingFromStrip = false

    /** Saved pens (build-order step 3); `pen` is the first and stays the default. */
    val pens = List(PenPresets.COUNT) { ChromeButton(c, "Pen ${it + 1}") }
    val pen get() = pens[0]
    val highlighter = ChromeButton(c, "Highlighter")
    val eraser = ChromeButton(c, "Eraser", Glyph.ERASER_STROKE)
    /** B31 (P193): the paint brush, standing after the eraser as a paint roller in the pen's colour. */
    val paintBrush = ChromeButton(c, "Paint brush")
    val lasso = ChromeButton(c, "Lasso", Glyph.LASSO_FREEFORM)
    /** B11: drops a chosen shape on the page with the current pen. */
    val shapes = ChromeButton(c, "Shapes", Glyph.SHAPES)
    val undo = ChromeButton(c, "Undo", Glyph.UNDO)
    val redo = ChromeButton(c, "Redo", Glyph.REDO)
    val zoomOut = ChromeButton(c, "Zoom out", Glyph.ZOOM_OUT)
    val fit = ChromeButton(c, "Fit page", Glyph.FIT)
    val zoomIn = ChromeButton(c, "Zoom in", Glyph.ZOOM_IN)

    private val fault = LinearLayout(c)
    private val faultText = TextView(c)
    private val retry = faultAction("Retry save", primary = true)
    private val exportCopy = faultAction("Export copy")

    val selectionBar = ContextToolbar(c)
    internal val pdfTextBar = ContextToolbar(c)
    internal val pdfTextCopy = ChromeButton(c,"Copy selected PDF text",Glyph.COPY,"Copy")
    private val pdfTextClose = ChromeButton(c,"Close PDF text selection",Glyph.CLOSE,"Close")
    val editText = ChromeButton(c, "Edit text box", Glyph.TEXT, "Edit…")
    internal val foldNote=ChromeButton(c,"Fold sticky note",text="Fold")
    internal val editTable=ChromeButton(c,"Edit table",text="Table…")
    internal val linkSource=ChromeButton(c,"Link to source",text="Link to source")
    internal val connectShapes=ChromeButton(c,"Connect shapes",text="Connect")
    internal val linkSelection=ChromeButton(c,"Link selection",text="Link")
    internal val removeSelectionLink=ChromeButton(c,"Remove selection link",text="Remove link")
    val cropPhoto = ChromeButton(c, "Crop photo", Glyph.CROP, "Crop…")
    val saveSticker = ChromeButton(c, "Save selection as sticker", text = "Save as sticker…")
    /** P130 (F109): keep the selected handwriting as a reusable stamp. */
    val saveStamp = ChromeButton(c, "Save handwriting as stamp", text = "Save as stamp…")
    val cutInk = ChromeButton(c, "Cut handwriting", Glyph.CUT, "Cut")
    val copyInk = ChromeButton(c, "Copy handwriting", Glyph.COPY, "Copy")
    // B27 (P192): pen kind, colour and thickness of the selection in one pop-out.
    val colorInk = ChromeButton(c, "Change the pen, colour and thickness of the selection", text = "Style…")
    /** P413 (auto-refine R1): tidy the selected handwriting, beside Style… (docs/AUTO-REFINE-DESIGN.md §5). */
    val refineInk = ChromeButton(c, "Refine the selected handwriting", text = "Refine…")
    val widthInk = ChromeButton(c, "Change selection width", text = "Width…")
    val straightenInk = ChromeButton(c, "Straighten handwriting", text = "Straighten")
    val tapeInk = ChromeButton(c, "Cover with study tape", text = "Tape")
    /** P098 (F050): Group or Ungroup the selected strokes. */
    val groupInk = ChromeButton(c, "Group handwriting", text = "Group")
    /** P101 (F074): lock the selection in place. */
    val lockInk = ChromeButton(c, "Lock selection", text = "Lock")
    /** P102 (F079): move the selected handwriting to another layer. */
    val layerInk = ChromeButton(c, "Move selection to layer", text = "Layer…")
    val pasteInk = ChromeButton(c, "Paste handwriting", Glyph.PASTE, "Paste")
    val duplicateInk = ChromeButton(c, "Duplicate selection", Glyph.DUPLICATE)
    val deleteInk = ChromeButton(c, "Delete selection", Glyph.DELETE)
    val deselect = ChromeButton(c, "Deselect", Glyph.CLOSE)
    /** B25 (P138): the selection bar shows the main actions; the rest open from this "⋮", so the bar never scrolls. */
    val moreInk = ChromeButton(c, "More selection actions", Glyph.MORE)
    /** Kept off the bar, in this order, in the "⋮" menu; each keeps its own visibility, enabled state and action. */
    internal val selectionExtras get() = listOf(linkSelection, removeSelectionLink, straightenInk, tapeInk, groupInk, lockInk, layerInk, saveSticker, saveStamp)
    /** B12: shown beside locked objects the user held, tapped or circled. */
    val unlockBar = ContextToolbar(c)
    val unlockPicked = ChromeButton(c, "Unlock", Glyph.UNLOCK, "Unlock")
    /** B35 (P198): Paste beside a lasso tap on empty paper. */
    val pasteBar = ContextToolbar(c)
    val pasteHere = ChromeButton(c, "Paste handwriting", Glyph.PASTE, "Paste")

    val settings = FrameLayout(c)
    /**
     * P201 step 4: Settings as on the Glass Settings board. On a wide screen a glass sidebar lists the groups and the
     * detail shows the chosen group; a narrow screen stacks every group. Built afresh by [openSettings].
     */
    private val settingsNavRows = mutableListOf<TextView>()
    private val settingsPages = mutableListOf<View>()
    private var settingsChosen = 0
    /** The group rows in the sidebar, for tests. */
    internal val settingsGroups: List<TextView> get() = settingsNavRows
    /** The group pages, in sidebar order, for tests. */
    internal val settingsGroupPages: List<View> get() = settingsPages
    val settingsOpen get() = settings.visibility == View.VISIBLE
    private var popover: Popover? = null
    /** B11: the open menu, for tests. */
    internal val openPopover get() = popover?.takeIf { it.isShowing }

    /** Tool bar docking (build-order step 2). The top app bar never moves; only the tool bar does. */
    enum class Dock(val label: String) { TOP("Top"), BOTTOM("Bottom"), LEFT("Left"), RIGHT("Right") }
    private val preferences = (c.application as NotesApplication).let { it.getSharedPreferences(it.preferencesName, 0) }
    private var continuousPreferred = preferences.getBoolean("continuousMode", false)
    /** P530: side-by-side panes stay single-page; the saved choice returns when the companion closes. */
    val continuousMode get() = continuousPreferred && companionCanvas == null
    var panelOpen = preferences.getBoolean("pagePanelOpen", false); private set
    var dock = run {
        // P186: the Glass palette stands at the foot of the page, as on the board; a bar left at the old top default moves there once.
        if (!preferences.getBoolean("glassPalette", false)) {
            val edit = preferences.edit().putBoolean("glassPalette", true)
            if (preferences.getString("toolbarDock", Dock.TOP.name) == Dock.TOP.name) edit.putString("toolbarDock", Dock.BOTTOM.name)
            edit.apply()
        }
        runCatching { Dock.valueOf(preferences.getString("toolbarDock", Dock.BOTTOM.name)!!) }.getOrDefault(Dock.BOTTOM)
    }
        private set
    /** P560: the handle at the palette's start: hold and drag it to another edge, or tap it for the positions. Settings places it too. */
    val grip = PaletteGrip(c, activity.dark)
    /** P186: the Glass palette's colour dots: recent and preset colours for the tool in use, then "All colours". */
    val inkDots = List(4) { InkDot(c, null, "Colour") }
    val allColours = InkDot(c, null, "All colours")
    private val dotGroup = LinearLayout(c)
    private val undoGroup = LinearLayout(c)
    private val historyRule = Glass.rule(c, activity.dark)
    private val colourRule = Glass.rule(c, activity.dark)
    /** P186: the palette, for tests (its frame is what a finger drags to another edge). */
    internal val palette get() = toolPill
    private val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val middle = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    private val toolBar = LinearLayout(c)
    private val toolGroup = LinearLayout(c)
    private val zoomGroup = LinearLayout(c)
    /** P175 (G1): the Glass chrome. Bars are strips of desk; the controls float on them in frosted pills. */
    private val night = activity.dark
    private val deskColor = if (night) Tokens.DESK_DARK else Tokens.DESK
    /** The pill the tools float in; the tool bar around it is a strip of desk that docks to an edge. */
    private val toolPill = LinearLayout(c)
    /** The top bar's two control pills: page navigation, and the page and notebook actions. */
    private val pagePill = pill()
    private val actionPill = pill()
    private val libraryPill = pill()
    private val namedPill = pill()
    /**
     * P176 (G1b): the page fills the editor and the chrome floats over it. [stage] holds the page area with, above it, the
     * app bar (and the tool bar when docked top) in [topChrome]; a bottom tool bar floats at its foot. Side rails stay beside the page.
     */
    private val stage = FrameLayout(c)
    private val topChrome = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    private var chromeTop = 0
    private var chromeBottom = 0
    private val dockHint = View(c).apply {
        val accent = Glass.accent(night)
        background = rounded((accent and 0xffffff) or 0x2e000000, dp(Glass.PILL), accent, dpi(2f)); visibility = View.GONE
    }
    /** The edge a grip drag would snap to, or null when no drag is in progress. */
    var dragTarget: Dock? = null; private set
    /** P178 (G3): the open page strip: a Select/Done button above the previews and, while choosing, the bulk bar at its foot. */
    private val pageStrip = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (night) PagePanel.NIGHT_PANE else PagePanel.LIGHT_PANE) }
    val choosePages = stripButton(PageStripAction.SELECT.label)
    val movePages = stripButton(PageStripAction.MOVE.label)
    val deletePages = stripButton(PageStripAction.DELETE.label)
    /** P201 step 6: opens the page organizer from the strip's header. */
    val allPages = android.widget.ImageView(c).apply {
        setImageDrawable(Kit.Icon(PageOrganizer.GRID, Glass.accent(night), c.dpi(20f))); scaleType = android.widget.ImageView.ScaleType.CENTER
        contentDescription = PageStripAction.ALL.label; tooltipText = PageStripAction.ALL.label
        background = ripple(night, c.dp(20f)); isClickable = true; isFocusable = true
        setOnClickListener { openOrganizer() }
    }
    internal val chosenCount = TextView(c)
    private val bulkBar = LinearLayout(c)

    init {
        (pens + listOf(library, previous, pages, next, pageSettings, insert, capture, study, search, share, more, panelToggle,
            highlighter, eraser, paintBrush, lasso, shapes, undo, redo, zoomOut, fit, zoomIn)).forEach { it.glass = true; it.night = night }
        (inkDots + allColours).forEach { it.night = night }
        library.emphasis = true
        eraser.uprightTool = ToolArt.Tool.ERASER; paintBrush.uprightTool = ToolArt.Tool.PAINT; lasso.uprightTool = ToolArt.Tool.LASSO; shapes.uprightTool = ToolArt.Tool.RULER
        topChrome.addView(topBar(), LinearLayout.LayoutParams(MATCH_PARENT, c.dpi(TOP_BAR)))
        topChrome.addView(faultBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        continuous.deskColor = if (activity.dark) Tokens.DESK_DARK else Tokens.DESK
        continuous.visibility = if (continuousMode) View.VISIBLE else View.GONE
        desk.addView(continuous, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        desk.addView(primaryCanvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addPageCard.deskColor = continuous.deskColor
        addPageCard.onPullBack = { originY, dx, dy -> ink.dragFromEnd(originY + addPageCardTop, dx, dy) }
        addPageCard.onLetGo = { fast -> ink.releaseFromEnd(fast) }
        primaryCanvas.endCard = EndCard()
        desk.addView(addPageCard, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { gravity = Gravity.TOP or Gravity.LEFT })
        desk.addView(selectionBar(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        selectionBar.useGlass(night)
        pdfTextCopy.onTap { activity.copySelectedPdfText() }
        pdfTextClose.onTap { activity.cancelPdfTextSelection() }
        pdfTextBar.setActions(listOf(pdfTextCopy,pdfTextClose))
        pdfTextBar.useGlass(night)
        desk.addView(pdfTextBar,FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.TOP or Gravity.LEFT))
        unlockPicked.onTap { ink.unlockPicked() }
        unlockBar.setActions(listOf(unlockPicked))
        unlockBar.useGlass(night)
        desk.addView(unlockBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        pasteHere.onTap { ink.pasteSpotPoint?.let { if (ink.canPasteInk) ink.pasteInk(it) } }
        pasteBar.setActions(listOf(pasteHere))
        pasteBar.useGlass(night)
        desk.addView(pasteBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        desk.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionSelectionBar(); placeAddPage(); if (desk.width != panesWidth) desk.post { layoutPanes() } }
        middle.addView(desk, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        stage.setBackgroundColor(deskColor); stage.clipChildren = false
        stage.addView(middle, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stage.addView(topChrome, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        column.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        buildToolBar()
        placeToolBar()
        val chrome = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeUnderChrome() }
        topChrome.addOnLayoutChangeListener(chrome); toolBar.addOnLayoutChangeListener(chrome); desk.addOnLayoutChangeListener(chrome)
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(dockHint)
        root.addView(settingsScreen(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pagePanel.onPageActions = { activity.pagePanelActions(it) }
        pagePanel.onPageSelected = { revealPage(it) }
        pagePanel.onRefused = { activity.message(it) }
        pagePanel.night = night
        pagePanel.onChoiceChanged = { showPageChoice() }
        wireCanvas(primaryCanvas)
        buildPageStrip()
        continuous.onRenderFailed = { _, error -> activity.message("Page preview failed: $error") }
        continuous.onViewportChanged = { if (continuousMode && !ink.isEditing) ink.fit(); placeAddPage() }
        continuous.onCurrentPageChanged = { _, id ->
            if (!syncingStrip && !awaitingStripLayout && !selectingFromStrip && !ink.isEditing && !ink.isHandLocked() && !repository.busy && !repository.fileBusy) {
                selectingFromStrip = true
                try { repository.selectPage(id, recordHistory = false) } finally { selectingFromStrip = false }
            }
        }
        configureStrip()
        placePanel()
    }

    /** P176 (G1b): a fitted page, the continuous strip's ends and the selection bars stay clear of the floating chrome. */
    private fun placeUnderChrome() {
        val top = topChrome.height
        val bottom = if (dock == Dock.BOTTOM && toolBar.parent === stage) toolBar.height else 0
        if (top == chromeTop && bottom == chromeBottom) return
        chromeTop = top; chromeBottom = bottom
        applyPaneInsets()
        layoutPanes()
        continuous.setInsets(top.toFloat(), bottom.toFloat())
        positionSelectionBar()
        placeStudy()
    }

    // ---- P530 (F081/F082): two panes under one shared toolbar. Each canvas keeps its own document, page and zoom. ----
    private fun wireCanvas(canvas: InkCanvas) {
        canvas.onPdfTextSelectionChanged = { positionSelectionBar() }
        canvas.onShapeSnapped = { kind, after -> showSnapToast(kind, after) }
        canvas.onViewportChanged = { positionSelectionBar(); showFit(); zoomWriting?.viewportChanged(); if (addPageCard.covering) placeAddPage() }
        canvas.onZoomWritingRequested = { openZoomWriting() }
        canvas.onFingerHold = { x, y -> quickRing(x, y) }
    }
    private var paneRatio = preferences.getFloat("splitRatio", .5f).takeIf { it.isFinite() }?.coerceIn(.15f, .85f) ?: .5f
    private var panesWidth = 0
    private val primaryTab by lazy { PaneTab(primaryCanvas, closes = false) }
    private val companionTab by lazy { PaneTab(null, closes = true) }
    private val paneDivider by lazy { PaneDivider() }
    /** A pane's title strip: the notebook's name, the active pane's accent line, and Close on the companion. */
    private inner class PaneTab(private var canvas: InkCanvas?, closes: Boolean) : LinearLayout(c) {
        private val name = Kit.text(c, "").apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f }
        private val line = android.graphics.Paint()
        var active = false; private set
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setWillNotDraw(false)
            setBackgroundColor(if (night) Glass.PANE_NIGHT else Glass.PANE)
            setPadding(c.dpi(12f), 0, c.dpi(4f), 0)
            addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (closes) addView(View(c).apply {
                // The kit's button is a 48 dp disc; this one sits inside the strip's height.
                val mark = Kit.Icon(Glyph.CLOSE, Glass.text(night), dpi(18f))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f808080),
                    android.graphics.drawable.LayerDrawable(arrayOf(mark)).apply { setLayerGravity(0, Gravity.CENTER); setLayerSize(0, dpi(18f), dpi(18f)) }, null)
                contentDescription = "Close companion"; tooltipText = "Close companion"
                isClickable = true; isFocusable = true
                setOnClickListener { runCatching { activity.closeCompanionPane() } }
            }, LinearLayout.LayoutParams(c.dpi(48f), MATCH_PARENT))
            setOnClickListener { this.canvas?.let { activity.focusPane(it) } }
        }
        fun show(canvas: InkCanvas?, document: NotebookRepository?, active: Boolean, label: String) {
            this.canvas = canvas; this.active = active
            val title = document?.session?.notebook?.title.orEmpty()
            name.text = title
            name.setTextColor(if (active) Glass.accent(night) else if (night) Glass.SECONDARY_NIGHT else Glass.SECONDARY)
            contentDescription = "$label pane, $title" + if (active) ", active" else ", tap to make active"
            invalidate()
        }
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val edge = c.dpi(if (active) 2f else 1f).toFloat()
            line.color = if (active) Glass.accent(night) else Kit.separator(night)
            canvas.drawRect(0f, height - edge, width.toFloat(), height.toFloat(), line)
        }
    }
    /** Drag or use the arrow keys to share the width. The panes change once on release; a PDF is not redrawn at every step. */
    private inner class PaneDivider : View(c) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var startX = 0f
        private var dragging = false
        init {
            contentDescription = "Resize panes"; isFocusable = true
            setOnKeyListener { _, code, event ->
                val step = when (code) { android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -.05f; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> .05f; else -> return@setOnKeyListener false }
                if (event.action == android.view.KeyEvent.ACTION_DOWN) commitRatio(paneRatio + step)
                true
            }
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = when (action) {
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { commitRatio(paneRatio + .05f); true }
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { commitRatio(paneRatio - .05f); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val line = c.dpi(if (dragging) 3f else 2f).toFloat()
            paint.color = if (dragging) Glass.accent(night) else Kit.separator(night)
            canvas.drawRect((width - line) / 2, 0f, (width + line) / 2, height.toFloat(), paint)
            paint.color = if (dragging) Glass.accent(night) else Kit.caps(night)
            val grip = c.dpi(4f).toFloat(); val length = c.dpi(28f).toFloat()
            canvas.drawRoundRect((width - grip) / 2, (height - length) / 2, (width + grip) / 2, (height + length) / 2, grip / 2, grip / 2, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (primaryCanvas.isEditing || companionCanvas?.isEditing == true) return false
                    startX = event.rawX; dragging = true; parent.requestDisallowInterceptTouchEvent(true); invalidate()
                }
                MotionEvent.ACTION_MOVE -> translationX = paneTravel(event.rawX - startX)
                MotionEvent.ACTION_UP -> {
                    val moved = paneTravel(event.rawX - startX)
                    dragging = false; translationX = 0f
                    if (desk.width > 0) commitRatio(paneRatio + moved / desk.width) else invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; translationX = 0f; invalidate() }
            }
            return true
        }
        private fun paneTravel(dx: Float) = (paneSplit(desk.width) + dx).coerceIn(paneMin(desk.width).toFloat(), (desk.width - paneMin(desk.width)).toFloat()) - paneSplit(desk.width)
    }
    private fun commitRatio(ratio: Float) {
        paneRatio = ratio.coerceIn(.15f, .85f)
        preferences.edit().putFloat("splitRatio", paneRatio).apply()
        layoutPanes()
        paneDivider.invalidate()
    }
    /** Portrait keeps two panes: each stays at least this wide, or half of a very narrow window. */
    private fun paneMin(width: Int) = minOf(c.dpi(PANE_MIN), width / 2)
    private fun paneSplit(width: Int) = (width * paneRatio).toInt().coerceIn(paneMin(width), width - paneMin(width))
    private fun applyPaneInsets() {
        val top = chromeTop + if (companionCanvas != null) c.dpi(PANE_TAB) else 0
        primaryCanvas.setFitInsets(0f, top.toFloat(), 0f, chromeBottom.toFloat())
        companionCanvas?.setFitInsets(0f, top.toFloat(), 0f, chromeBottom.toFloat())
    }
    private fun View.placeInDesk(left: Int, top: Int, width: Int, height: Int) {
        val params = layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) return
        params.leftMargin = left; params.topMargin = top; params.width = width; params.height = height
        layoutParams = params
    }
    private fun layoutPanes() {
        panesWidth = desk.width
        val second = companionCanvas
        placeAddPageCard()
        if (second == null) { primaryCanvas.placeInDesk(0, 0, MATCH_PARENT, MATCH_PARENT); return }
        if (desk.width <= 0) return
        val split = paneSplit(desk.width); val gap = c.dpi(PANE_GAP); val tab = c.dpi(PANE_TAB); val hit = c.dpi(PANE_HIT)
        primaryCanvas.placeInDesk(0, 0, split - gap, MATCH_PARENT)
        second.placeInDesk(split + gap, 0, desk.width - split - gap, MATCH_PARENT)
        primaryTab.placeInDesk(0, chromeTop, split - gap, tab)
        companionTab.placeInDesk(split + gap, chromeTop, desk.width - split - gap, tab)
        paneDivider.placeInDesk(split - hit / 2, chromeTop, hit, (desk.height - chromeTop - chromeBottom).coerceAtLeast(tab))
        paneDivider.setBackgroundColor(Color.TRANSPARENT)
    }
    /** How far the "Add page" sheet starts below the top of its page's canvas; [AddPageCard.ghost] is in the sheet's coordinates. */
    private var addPageCardTop = 0
    /** P560: beside another notebook the sheet lies over its own pane only, below that pane's tab, and leaves the other notebook in view. */
    private fun placeAddPageCard() {
        val second = companionCanvas
        if (second == null || desk.width <= 0) { addPageCardTop = 0; addPageCard.placeInDesk(0, 0, MATCH_PARENT, MATCH_PARENT); placeAddPage(); return }
        val split = paneSplit(desk.width); val gap = c.dpi(PANE_GAP)
        addPageCardTop = chromeTop + c.dpi(PANE_TAB)
        if (ink === second) addPageCard.placeInDesk(split + gap, addPageCardTop, desk.width - split - gap, MATCH_PARENT)
        else addPageCard.placeInDesk(0, addPageCardTop, split - gap, MATCH_PARENT)
        placeAddPage()
    }
    private fun refreshPaneTabs() {
        val second = companionCanvas ?: return
        primaryTab.show(primaryCanvas, primaryDocument, ink === primaryCanvas, "Left")
        companionTab.show(second, companionDocument, ink === second, "Right")
    }
    /** Adds the companion's canvas; the page strip stays single-pane. P560: each pane offers "Add page" after its own last page. */
    internal fun attachCompanion(canvas: InkCanvas, document: NotebookRepository) {
        if (companionCanvas === canvas) return
        continuous.stopFling(); hideAddPage(instant = true)
        companionCanvas = canvas; companionDocument = document
        shownNotebook = null
        continuous.visibility = View.GONE; continuous.release(); configureStrip()
        canvas.adoptToolsFrom(ink)
        wireCanvas(canvas)
        canvas.endCard = EndCard()
        var at = desk.indexOfChild(primaryCanvas) + 1
        desk.addView(canvas, at++, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        desk.addView(primaryTab, at++, FrameLayout.LayoutParams(MATCH_PARENT, c.dpi(PANE_TAB)))
        desk.addView(companionTab, at++, FrameLayout.LayoutParams(MATCH_PARENT, c.dpi(PANE_TAB)))
        desk.addView(paneDivider, at, FrameLayout.LayoutParams(c.dpi(PANE_HIT), MATCH_PARENT))
        applyPaneInsets()
        layoutPanes()
        refreshPaneTabs()
    }
    /** Removes the saved companion; the surviving notebook keeps its page and returns to the full width. */
    internal fun detachCompanion() {
        val second = companionCanvas ?: return
        hideAddPage(instant = true)
        if (ink === second) focusPane(primaryCanvas, primaryDocument)
        companionCanvas = null; companionDocument = null
        listOf(second, primaryTab, companionTab, paneDivider).forEach { desk.removeView(it) }
        applyPaneInsets()
        layoutPanes()
        shownNotebook = null
        continuous.visibility = if (continuousMode) View.VISIBLE else View.GONE
        configureStrip(); syncStrip()
        if (continuousMode) repository.selectedPageId?.let { revealPage(it) }
    }
    /** Moves the shared toolbar. The previous pane keeps its page, zoom and selection; open panels bound to its page close. */
    internal fun focusPane(canvas: InkCanvas, document: NotebookRepository) {
        if (canvas === ink) return
        popover?.dismiss(); ring?.dismiss()
        closeZoomWriting(); closeRecall(); closeFlashcards(); closeStudyPanel()
        pagePanel.stopChoosing()
        hideAddPage(instant = true)
        val previous = ink
        ink = canvas; repository = document
        pagePanel.retarget(document)
        canvas.adoptToolsFrom(previous)
        placeAddPageCard()
        refreshPaneTabs()
        positionSelectionBar()
    }

    private fun configureStrip() {
        primaryCanvas.strip = if (!continuousMode) null else object : InkCanvas.Strip {
            override fun frameFor(pageId: String): RectF? {
                val index = continuous.notebook?.pages?.indexOfFirst { it.id == pageId } ?: return null
                return continuous.pageBounds(index)
            }
            override fun scrollBy(dy: Float) { if (idle) continuous.setScrollOffset(continuous.scrollOffset + dy) }
            override fun fling(velocityY: Float) { if (idle) continuous.fling(velocityY) }
            override fun stopFling() = continuous.stopFling()
            override fun pageAt(x: Float, y: Float): Pair<String, dev.ownednotes.core.Point>? {
                val hit = continuous.layout?.pageAt(dev.ownednotes.core.Point(x, y + continuous.scrollOffset)) ?: return null
                return continuous.notebook?.pages?.getOrNull(hit.pageIndex)?.id?.let { it to hit.point }
            }
            override fun penAt(x: Float, y: Float) {
                if (!idle) return
                val hit = continuous.layout?.pageAt(dev.ownednotes.core.Point(x, y + continuous.scrollOffset)) ?: return
                val id = continuous.notebook?.pages?.getOrNull(hit.pageIndex)?.id ?: return
                // Hover only moves the tool cursor when the pen is already over the active page.
                if (id == repository.selectedPageId) return
                selectingFromStrip = true
                try { repository.selectPage(id, recordHistory = false); ink.fit() } finally { selectingFromStrip = false }
            }
            override fun anchor() = stripAnchor()
        }
    }
    /** F086: the page at the top of the strip and how far down it is scrolled, in page units. */
    private fun stripAnchor(): Pair<String, Float>? = continuous.layout?.takeIf { continuous.height > 0 && !it.isEmpty }?.pageAtScroll(continuous.readingTop)?.let { hit ->
        continuous.notebook?.pages?.getOrNull(hit.pageIndex)?.id?.let { it to hit.point.y.coerceAtLeast(0f) }
    }

    fun setContinuousMode(enabled: Boolean): Boolean {
        if (!idle) return false
        if (splitOpen) return false
        if (enabled == continuousMode) return true
        continuous.stopFling()
        continuousPreferred = enabled
        shownNotebook = null
        preferences.edit().putBoolean("continuousMode", enabled).apply()
        continuous.visibility = if (enabled) View.VISIBLE else View.GONE
        configureStrip()
        syncStrip()
        if (enabled) repository.selectedPageId?.let { revealPage(it) }
        else { continuous.release(); ink.fit() }
        activity.refresh()
        return true
    }

    fun setPanelOpen(open: Boolean): Boolean {
        if (!idle) return false
        if (!open) pagePanel.stopChoosing()
        panelOpen = open
        preferences.edit().putBoolean("pagePanelOpen", open).apply()
        placePanel(animate = open); activity.refresh()
        return true
    }

    private fun placePanel(animate: Boolean = false) {
        (pageStrip.parent as? ViewGroup)?.removeView(pageStrip)
        pageStrip.animate().cancel(); pageStrip.translationX = 0f; pageStrip.alpha = 1f
        // P186: the page strip opens on the right, under the Glass board's Pages button, unless the tools are docked there.
        val right = dock != Dock.RIGHT
        if (panelOpen) {
            val index = if (right) middle.childCount else 0
            // P176 (G1b): the strip starts below the floating app bar and clears a docked tool bar.
            middle.addView(pageStrip, index, LinearLayout.LayoutParams(c.dpi(PagePanel.PREFERRED_WIDTH_DP), MATCH_PARENT).apply {
                topMargin = c.dpi(TOP_BAR) + if (dock == Dock.TOP) c.dpi(TOOL_STRIP) else 0
                bottomMargin = if (dock == Dock.BOTTOM) c.dpi(TOOL_STRIP) else 0
            })
            // Opening slides the strip in from its edge with a short fade; the page area resizes once, at the start.
            if (animate && Motion.enabled) {
                pageStrip.translationX = c.dp(28f) * (if (right) 1 else -1); pageStrip.alpha = 0f
                pageStrip.animate().translationX(0f).alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.easeOut).start()
            }
        }
        panelToggle.active = panelOpen
        panelToggle.contentDescription = if (panelOpen) "Hide pages" else "Show pages"
        // B09 (P131): the button sits at the end of the top bar where the panel opens, and its glyph shows that side.
        // P186: first in the action pill on the right, as on the board; last in the Library pill when the strip opens left.
        panelToggle.glyph = if (right) Glyph.PANEL else Glyph.PANEL_LEFT
        val bar = if (right) actionPill else libraryPill
        val placed = if (right) bar.getChildAt(0) === panelToggle else bar.getChildAt(bar.childCount - 1) === panelToggle
        if (!placed) {
            (panelToggle.parent as? ViewGroup)?.removeView(panelToggle)
            if (right) bar.addView(panelToggle, 0) else bar.addView(panelToggle)
        }
    }

    /** B07 (P131): pages can be added to this notebook: it is open for editing and under the 16-page limit. */
    private val canAddPage get() = repository.session?.notebook?.pages?.size?.let { it < NotebookLimits.PAGES } == true && !repository.readOnly

    /** The selected member may be the left page of the final landscape spread. */
    private val atLastDisplayedPage: Boolean get() {
        val pages = repository.session?.notebook?.pages ?: return false
        val index = pages.indexOfFirst { it.id == repository.selectedPageId }
        return index >= 0 && (index == pages.lastIndex ||
            (ink.spreadShown && BookSpread.start(index) + 2 >= pages.size))
    }

    /** Single-page mode, last page: a swipe past it slides in the empty page with "Add page". False when nothing was shown. */
    fun offerNewPage(slide: Boolean = true): Boolean {
        val pages = repository.session?.notebook?.pages ?: return false
        if (continuousMode || !idle || !canAddPage || !atLastDisplayedPage) return false
        if (ink.pageRect == null) return false
        addPageCard.paperColor = pages.last().paper.color.toInt()
        addPageCard.covering = true; placeAddPage()
        addPageCard.animate().cancel(); addPageCard.translationX = 0f; addPageCard.alpha = 1f
        if (slide && Motion.enabled) {
            // Beside another notebook it fades in: sliding, it would pass over that notebook.
            if (splitOpen) { addPageCard.alpha = 0f; addPageCard.animate().alpha(1f) }
            else { addPageCard.translationX = desk.width.toFloat(); addPageCard.animate().translationX(0f) }
            addPageCard.animate().setDuration(Motion.MEDIUM).setInterpolator(Motion.easeOut).start()
        }
        return true
    }

    /** B13 (P136): closing the card animates. Without a page curl to turn back, it slides away to the right,
     * the way it came in; [instant] is for a curl that has already drawn the way back. */
    fun hideAddPage(instant: Boolean = false) {
        if (!addPageCard.covering) return
        addPageCard.animate().cancel()
        fun closed() { addPageCard.translationX = 0f; addPageCard.alpha = 1f; addPageCard.covering = false; placeAddPage() }
        if (instant || !Motion.enabled || desk.width == 0) { closed(); return }
        (if (splitOpen) addPageCard.animate().alpha(0f) else addPageCard.animate().translationX(desk.width.toFloat()))
            .setDuration(Motion.MEDIUM).setInterpolator(Motion.easeOut).withEndAction { closed() }.start()
    }

    /** B13 (P136): a tap beside the button turns the last page back over the card; a swipe already does, finger-driven. */
    private fun turnBackFromAddPage() {
        if (!addPageCard.covering) return
        val rect = ink.pageRect
        if (rect != null && Motion.enabled && ink.dragFromEnd(rect.bottom, 2f, 0f)) ink.releaseFromEnd(fast = true)
        else hideAddPage()
    }

    // A class, not a property: init (above) hands it to the ink before this point's properties are initialised.
    private inner class EndCard : PageCurlView.EndCard {
        // Not while the sheet is open: a finger still down from the turn that opened it must not turn onto it again.
        override val available get() = !addPageCard.covering && !continuousMode && idle && canAddPage && atLastDisplayedPage
        override fun draw(canvas: android.graphics.Canvas, page: RectF) {
            repository.session?.notebook?.pages?.lastOrNull()?.let { addPageCard.paperColor = it.paper.color.toInt() }
            addPageCard.drawSheet(canvas, page)
        }
        override fun open() = offerNewPage(slide = false)
        override fun conceal(hidden: Boolean) { if (addPageCard.covering) addPageCard.alpha = if (hidden) 0f else 1f }
        override fun close() = hideAddPage(instant = true)
    }

    /** Follows the empty slot after the last page in continuous mode; keeps a shown card on the page in single-page mode. */
    private fun placeAddPage() {
        addPageCard.ghost = when {
            addPageCard.covering -> ink.pageRect?.let { RectF(it).apply { offset(0f, -addPageCardTop.toFloat()) } }
            continuousMode -> continuous.addPageBounds?.takeIf { it.bottom > 0 && it.top < continuous.height }
            else -> null
        }
    }

    private fun addPageAtEnd() {
        val last = repository.session?.notebook?.pages?.lastOrNull()?.id ?: return
        if (!idle || !canAddPage) return
        hideAddPage()
        var id: String? = null
        repository.edit { id = it.addPage(last); true }
        id?.let { repository.selectPage(it); revealPage(it) }
    }

    fun revealPage(id: String) {
        if (!continuousMode || selectingFromStrip || ink.isEditing) return
        val index = repository.session?.notebook?.pages?.indexOfFirst { it.id == id } ?: return
        if (index < 0) return
        syncingStrip = true
        try { continuous.scrollToPage(index); ink.fit() } finally { syncingStrip = false }
    }

    /** F086: a reopened notebook scrolls back to where it was left, when that place is beside the remembered page. */
    private fun restoreStrip(notebookId: String, selected: String) {
        val saved = ink.viewMemory.get(notebookId) as? ViewMemory.Strip ?: return
        val pages = repository.session?.notebook?.pages ?: return
        val index = pages.indexOfFirst { it.id == saved.pageId }
        val current = pages.indexOfFirst { it.id == selected }
        if (index < 0 || current < 0 || kotlin.math.abs(index - current) > 1) return
        syncingStrip = true
        try { continuous.scrollToPage(index, saved.localY.coerceAtMost(pages[index].height)); ink.fit() } finally { syncingStrip = false }
    }

    /** F063: continuous view scrolls so the end of the old page sits near the top, with the new room below it. */
    internal fun revealRoom(pageId: String, localY: Float) {
        if (!continuousMode || ink.isEditing) return
        val index = repository.session?.notebook?.pages?.indexOfFirst { it.id == pageId } ?: return
        if (index < 0) return
        syncingStrip = true
        try { continuous.scrollToPage(index, localY); ink.fit() } finally { syncingStrip = false }
    }

    private fun syncStrip() {
        if (!continuousMode || ink.isEditing) return
        val notebook = repository.session?.notebook
        var opened = false
        if (shownNotebook !== notebook) {
            // F086: leaving a notebook in continuous mode remembers how far it was scrolled.
            val previous = shownNotebook
            if (previous != null && previous.id != notebook?.id) stripAnchor()?.let { (pageId, top) -> runCatching { ink.viewMemory.save(previous.id, ViewMemory.Strip(pageId, top)) } }
            opened = notebook != null && previous?.id != notebook.id
            shownNotebook = notebook
            syncingStrip = true
            try { continuous.setNotebook(notebook) } finally { syncingStrip = false }
        }
        val selected = repository.selectedPageId
        // A new Activity has no strip layout yet. Its first layout starts at the top, which must not select page 1;
        // reveal the selected page (and the remembered scroll) once it has been laid out.
        if (continuous.layout == null && notebook != null && selected != null) {
            if (!awaitingStripLayout) {
                awaitingStripLayout = true
                continuous.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        if (continuous.layout == null) return
                        v.removeOnLayoutChangeListener(this)
                        v.post {
                            awaitingStripLayout = false
                            val id = repository.selectedPageId
                            if (continuousMode && id != null && repository.session?.notebook?.id == notebook.id) {
                                shownPageId = id; revealPage(id); restoreStrip(notebook.id, id)
                                // Layout-time reports were suppressed; reconcile once the restored viewport is final.
                                continuous.currentPage?.let { index ->
                                    continuous.notebook?.pages?.getOrNull(index)?.let { page ->
                                        continuous.onCurrentPageChanged(index, page.id)
                                    }
                                }
                            }
                        }
                    }
                })
            }
            return
        }
        if (shownPageId != selected || opened) {
            shownPageId = selected
            if (!selectingFromStrip && selected != null) revealPage(selected)
        }
        if (opened && selected != null) restoreStrip(notebook!!.id, selected)
    }

    val presets = PenPresets(preferences, ink.penStyle)
    /** Selects saved pen [index] as the drawing tool. Refused during pen or file activity. */
    fun selectPen(index: Int): Boolean {
        if (!idle) return false
        if(ink.laserEnabled) ink.selectLaser(false)
        presets.select(index)
        ink.selectPenStyle(presets[index])
        ink.selectHighlighter(false); ink.selectLasso(false); ink.eraser = false; ink.painter = false
        activity.refresh()
        return true
    }

    private var ring: android.widget.PopupWindow? = null
    /** P183 (G8): the quick ring while it is open, for tests. */
    internal val quickRingView get() = ring?.takeIf { it.isShowing }?.contentView as? QuickRing
    /**
     * P183 (G8), P199: opens the quick ring centred on a held finger ([x], [y] in page-view coordinates). Each slice runs the
     * tool bar's own button (a tool already in use stays as it is); Colour opens the colour circle for the highlighter when
     * it is in use, else the pen. The centre pastes under the finger, dimmed when nothing can be pasted.
     */
    internal fun quickRing(x: Float, y: Float): Boolean {
        if (!idle || repository.readOnly || settingsOpen || repository.currentPage == null) return false
        popover?.dismiss(); ring?.dismiss()
        fun QuickRing.Slice.runs(button: ChromeButton, always: Boolean = false) = apply {
            setOnClickListener { ring?.dismiss(); if (button.isEnabled && (always || !button.active)) button.performClick() }
        }
        val marker = drawingWithHighlighter
        val now = (if (marker) ink.highlighterStyle else ink.penStyle).color
        // P200: where the ring stands in the window, so its colour circle opens in the same place.
        val at = IntArray(2); ink.getLocationInWindow(at)
        val root = ink.rootView; val margin = c.dpi(8f); val side = c.dpi(QuickRing.DIAMETER)
        val left = (at[0] + x - side / 2f).toInt().coerceIn(margin, maxOf(margin, root.width - side - margin))
        val top = (at[1] + y - side / 2f).toInt().coerceIn(margin, maxOf(margin, root.height - side - margin))
        val centre = android.graphics.Point(left + side / 2, top + side / 2)
        val names = (InkColors.palettes.flatMap { it.second } + InkColors.highlights).toMap()
        val slices = listOf(
            QuickRing.Slice(c, night, "Undo", "Undo", enabled = undo.isEnabled) { canvas, p -> Glyphs.draw(canvas, Glyph.UNDO, p) }.runs(undo, always = true),
            QuickRing.Slice(c, night, "Redo", "Redo", enabled = redo.isEnabled) { canvas, p -> Glyphs.draw(canvas, Glyph.REDO, p) }.runs(redo, always = true)
        ) + pens.indices.map { index ->
            // Pens are told apart by their ink, as in the palette: the colour's name, else the pen kind.
            val style = presets[index]; val name = names[style.color] ?: style.kind.label
            QuickRing.Slice(c, night, name, "Pen ${index + 1}, ${style.kind.label}, $name", on = pens[index].active) { canvas, _ ->
                QuickRing.tool(canvas, ToolArt.of(style.kind), style.color.toInt())
            }.runs(pens[index])
        } + listOf(
            QuickRing.Slice(c, night, "Highlighter", "Highlighter", on = highlighter.active) { canvas, _ ->
                QuickRing.tool(canvas, ToolArt.Tool.HIGHLIGHTER, ToolArt.mix(ink.highlighterStyle.color.toInt(), Tokens.INK, .1f))
            }.runs(highlighter),
            QuickRing.Slice(c, night, "Eraser", eraser.contentDescription.toString().removeSuffix(", selected"), on = eraser.active) { canvas, _ ->
                QuickRing.tool(canvas, ToolArt.Tool.ERASER, 0)
            }.runs(eraser),
            QuickRing.Slice(c, night, "Lasso", "Lasso", on = lasso.active) { canvas, p -> Glyphs.draw(canvas, lasso.glyph ?: Glyph.LASSO_FREEFORM, p) }.runs(lasso),
            QuickRing.Slice(c, night, "Colour", "Colour, " + (names[now] ?: "saved colour %06X".format(now and 0xffffff))) { canvas, p -> QuickRing.swatch(canvas, now, night, p) }
                .apply { setOnClickListener { ring?.dismiss(); act { colourCircle(allColours, drawingWithHighlighter, centre) { applyRingColor(it) } } } })
        val pasteable = ink.canPasteInk
        val label = ink.clipboardLabel
        val paste = QuickRing.Slice(c, night, "Paste", if (pasteable) label else "Paste, nothing copied", enabled = pasteable,
            detail = if (pasteable) label.removePrefix("Paste ") else "Nothing copied") { canvas, p -> Glyphs.draw(canvas, Glyph.PASTE, p) }
        paste.setOnClickListener { val at = ink.pagePoint(x, y); ring?.dismiss(); act { if (idle && ink.canPasteInk) ink.pasteInk(at) } }
        val view = QuickRing(c, night, slices, paste) { ring?.dismiss() }
        ring = android.widget.PopupWindow(view, side, side, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); isOutsideTouchable = true
            animationStyle = R.style.Scribella_PopoverMotion
            showAtLocation(ink, Gravity.TOP or Gravity.START, left, top)
        }
        ink.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }
    private val drawingWithHighlighter get() = ink.highlighter && !ink.eraser && !ink.lassoEnabled && !ink.painter
    private fun applyRingColor(color: Long) {
        if (!idle) return
        if (drawingWithHighlighter) ink.selectHighlighterStyle(ink.highlighterStyle.copy(color = color))
        else {
            if (ink.eraser || ink.lassoEnabled) selectPen(presets.selected)
            ink.selectPenStyle(ink.penStyle.copy(color = color))
        }
        activity.refresh()
    }

    /** Moves the tool bar to [target] and remembers it. Ignored during pen or file activity. */
    fun dockTo(target: Dock): Boolean {
        if (!idle) return false
        if (target != dock) {
            popover?.dismiss()
            dock = target
            preferences.edit().putString("toolbarDock", target.name).apply()
            placeToolBar()
                placePanel()
        }
        return true
    }

    private fun placeToolBar() {
        (toolBar.parent as? ViewGroup)?.removeView(toolBar)
        val vertical = dock == Dock.LEFT || dock == Dock.RIGHT
        val bar = c.dpi(if (vertical) TOOL_RAIL else TOOL_STRIP)
        toolBar.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // P186: the palette floats 14 dp from its edge; a rail's two columns stand a little closer to theirs.
        val edge = c.dpi(if (vertical) 6f else 14f)
        toolBar.gravity = when (dock) {
            Dock.BOTTOM -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; Dock.TOP -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
            Dock.LEFT -> Gravity.CENTER_VERTICAL or Gravity.LEFT; Dock.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
        }
        when (dock) {
            Dock.BOTTOM -> toolBar.setPadding(0, 0, 0, edge); Dock.TOP -> toolBar.setPadding(0, c.dpi(4f), 0, 0)
            Dock.LEFT -> toolBar.setPadding(edge, 0, 0, 0); Dock.RIGHT -> toolBar.setPadding(0, 0, edge, 0)
        }
        listOf(toolPill, toolGroup, zoomGroup, dotGroup).forEach { it.orientation = toolBar.orientation; it.gravity = Gravity.CENTER }
        // Undo and Redo stand side by side at every edge (P186 review: stacked, they made the palette too thick).
        undoGroup.orientation = LinearLayout.HORIZONTAL; undoGroup.gravity = Gravity.CENTER
        // The handle lies at the palette's start, inside its rounded end.
        if (vertical) toolPill.setPadding(0, c.dpi(4f), 0, c.dpi(10f)) else toolPill.setPadding(c.dpi(6f), 0, c.dpi(14f), 0)
        grip.across = vertical
        grip.layoutParams = LinearLayout.LayoutParams(if (vertical) c.dpi(96f) else c.dpi(36f), if (vertical) c.dpi(38f) else c.dpi(76f))
        // The board's hairlines between history, tools and colours, 44 dp in the shorter palette.
        fun rule(view: View, before: Float, after: Float) {
            view.layoutParams = LinearLayout.LayoutParams(if (vertical) c.dpi(72f) else c.dpi(1f), if (vertical) c.dpi(1f) else c.dpi(44f)).apply {
                if (vertical) { topMargin = c.dpi(before); bottomMargin = c.dpi(after) } else { marginStart = c.dpi(before); marginEnd = c.dpi(after) }
            }
        }
        rule(historyRule, 4f, 8f); rule(colourRule, 8f, 8f)
        // P560: every tool looks the same at every edge: tip up, sunk in its slot until it is chosen. A rail keeps them
        // upright in rows of two, each row on a hairline shelf, so the whole palette fits a landscape screen.
        val tools = pens + highlighter + eraser + paintBrush + lasso + shapes
        val dots = inkDots + allColours
        arrange(toolGroup, tools, vertical, shelves = true)
        arrange(dotGroup, dots, vertical)
        tools.forEach {
            it.uprightTip = 0f
            it.slotLength = if (vertical) RAIL_SLOT else ChromeButton.SLOT
            it.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        dots.forEach { it.layoutParams = LinearLayout.LayoutParams(if (vertical) c.dpi(48f) else c.dpi(40f), if (vertical) c.dpi(42f) else WRAP_CONTENT) }
        toolPill.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        listOf(toolGroup, zoomGroup, dotGroup, undoGroup).forEach { it.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }
        // P176 (G1b): top and bottom bars float over the page; side rails stand beside it, below the app bar.
        when (dock) {
            Dock.TOP -> topChrome.addView(toolBar, LinearLayout.LayoutParams(MATCH_PARENT, bar))
            Dock.BOTTOM -> stage.addView(toolBar, FrameLayout.LayoutParams(MATCH_PARENT, bar, Gravity.BOTTOM))
            Dock.LEFT -> middle.addView(toolBar, 0, LinearLayout.LayoutParams(bar, MATCH_PARENT).apply { topMargin = c.dpi(TOP_BAR) })
            Dock.RIGHT -> middle.addView(toolBar, LinearLayout.LayoutParams(bar, MATCH_PARENT).apply { topMargin = c.dpi(TOP_BAR) })
        }
        grip.contentDescription = "Move toolbar. Docked ${dock.label.lowercase()}. Drag to another edge, or tap for positions."
        toolPill.contentDescription = "Tools. Docked ${dock.label.lowercase()}. Drag the handle to move it."
    }
    /** One line along a top or bottom palette; rows of two on a rail. */
    private fun arrange(group: LinearLayout, views: List<View>, rows: Boolean, shelves: Boolean = false) {
        views.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        group.removeAllViews()
        if (!rows) { views.forEach { group.addView(it) }; return }
        views.chunked(2).forEachIndexed { i, pair ->
            if (shelves && i > 0) group.addView(Glass.rule(c, night), LinearLayout.LayoutParams(c.dpi(72f), c.dpi(1f)))
            // Each row sets the group's width: left to fill it, the rows would be cut to the shelf's width.
            group.addView(LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; pair.forEach { addView(it) } }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    /** Edge nearest to a point in [root] coordinates, below the app bar. */
    private fun nearestEdge(x: Float, y: Float): Dock {
        val top = c.dpi(TOP_BAR).toFloat()
        val distances = mapOf(Dock.TOP to y - top, Dock.BOTTOM to root.height - y, Dock.LEFT to x, Dock.RIGHT to root.width - x)
        return distances.minBy { it.value }.key
    }
    private fun showHint(target: Dock?) {
        dragTarget = target
        if (target == null) { dockHint.visibility = View.GONE; return }
        val top = c.dpi(TOP_BAR)
        val bar = c.dpi(TOOL_STRIP); val rail = c.dpi(TOOL_RAIL); val inset = c.dpi(6f)
        dockHint.layoutParams = when (target) {
            Dock.TOP -> FrameLayout.LayoutParams(MATCH_PARENT, bar, Gravity.TOP).apply { topMargin = top }
            Dock.BOTTOM -> FrameLayout.LayoutParams(MATCH_PARENT, bar, Gravity.BOTTOM)
            Dock.LEFT -> FrameLayout.LayoutParams(rail, MATCH_PARENT, Gravity.START).apply { topMargin = top }
            Dock.RIGHT -> FrameLayout.LayoutParams(rail, MATCH_PARENT, Gravity.END).apply { topMargin = top }
        }.apply { setMargins(leftMargin + inset, topMargin + inset, rightMargin + inset, bottomMargin + inset) }
        dockHint.visibility = View.VISIBLE
    }

    /**
     * Grip: drag with a finger or the pen to an edge, or tap for a position menu. P560: the grip is shown again, because the
     * palette's own frame (between and around its tools) is too thin to find; the frame still drags the same way.
     */
    private fun bindGrip() {
        bindDrag(grip)
        bindDrag(toolPill)
        grip.onTap { dockMenu() }
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(handle: View) {
        val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
        var downX = 0f; var downY = 0f
        handle.setOnTouchListener { view, event ->
            val at = IntArray(2); root.getLocationOnScreen(at)
            val x = event.rawX - at[0]; val y = event.rawY - at[1]
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = x; downY = y; view.isPressed = true; view.parent.requestDisallowInterceptTouchEvent(true) }
                android.view.MotionEvent.ACTION_MOVE -> if (view.isEnabled && (dragTarget != null || kotlin.math.hypot(x - downX, y - downY) > slop)) {
                    val target = nearestEdge(x, y)
                    if (target != dragTarget) { showHint(target); tick(view) }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val target = dragTarget
                    showHint(null)
                    if (target != null) { if (dockTo(target)) tick(view) } else if (view.isEnabled) view.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { view.isPressed = false; showHint(null) }
            }
            true
        }
    }
    private fun dockMenu() = menu(toolPill, "Toolbar position", Dock.entries.map { target ->
        Triple(if (target == dock) "${target.label} ✓" else target.label, true) { dockTo(target); Unit }
    }, toward = true)

    private fun act(action: () -> Unit) { runCatching(action).onFailure { activity.message(it.message ?: "Operation failed") } }
    private fun View.onTap(action: () -> Unit) = setOnClickListener { act(action) }
    private fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** P175 (G1): a frosted Glass pill floating on the desk. */
    private fun pill(vararg views: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Glass.pane(c, night); elevation = dp(6f)
        setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f))
        views.forEach { addView(it) }
    }

    /**
     * B15: two rows. The first holds Library, the notebook's name and its save state; the second the page and notebook controls.
     * P175 (G1): both rows are desk, their controls grouped in floating Glass pills.
     * P176 (G1b): the rows are clear; the pills float over the page, and touches between them reach it.
     */
    /**
     * P186: one row, as on the Glass board: Library on the left, the notebook's name and page centred, and Pages, Search,
     * Page and paper, Insert, Share and More on the right. Capture, Study, page actions and page turning are reached from
     * those menus (see MENU-AND-VISUAL-AUDIT.md); their buttons stay as detached views that open the same menus.
     */
    private fun topBar() = FrameLayout(c).apply {
        clipChildren = false
        setPadding(dpi(32f), dpi(14f), dpi(32f), dpi(8f))
        previous.onTap { activity.turnPage(-1) }
        next.onTap { activity.turnPage(1) }
        pages.onTap { pageMenu() }
        insert.onTap { insertMenu() }
        pageSettings.onTap { pageAndPaperMenu() }
        capture.onTap { commandMenu(insert, EditorMenu.CAPTURE) }
        study.onTap { commandMenu(search, EditorMenu.STUDY) }
        search.onTap { searchMenu() }
        share.onTap { shareMenu() }
        record.onTap { openStudyPanel() }
        more.onTap { overflowMenu() }
        panelToggle.onTap { setPanelOpen(!panelOpen) }
        library.onTap { activity.showLibrary() }
        libraryPill.apply { setPadding(dpi(4f), 0, dpi(4f), 0); addView(library) }
        actionPill.apply { setPadding(dpi(6f), 0, dpi(6f), 0); listOf(record, search, pageSettings, insert, share, more).forEach { addView(it) } }
        addView(libraryPill, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(52f), Gravity.LEFT or Gravity.CENTER_VERTICAL))
        addView(actionPill, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(52f), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        title.apply {
            textSize = 15f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(night))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }
        statusText.apply { textSize = 12f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(night)); maxLines = 1; gravity = Gravity.CENTER }
        // The bar has one height: at large text these two lines stop growing, so both stay whole.
        if (c.largeText) { title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 19f); statusText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f) }
        // The notebook's name over its page and where its ink stands, centred over the page; a tap renames it.
        namedPill.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dpi(26f), 0, dpi(26f), 0); minimumWidth = dpi(180f)
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(statusText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(1f) })
            foreground = ripple(night, dp(Glass.PILL)); isClickable = true; isFocusable = true
            contentDescription = "Notebook title. Tap to rename."
            onTap { if (title.isEnabled) activity.renameDialog() }
        }
        addView(namedPill, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(52f), Gravity.CENTER))
        // The title never runs under the side pills: centred on the screen when it fits, else in the room between them,
        // and capped to that room.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val gapLeft = libraryPill.right + dpi(12f); val gapRight = actionPill.left - dpi(12f)
            val cap = maxOf(dpi(68f), gapRight - gapLeft - dpi(52f))
            if (title.maxWidth != cap) post { title.maxWidth = cap; statusText.maxWidth = cap }
            val shift = when {
                namedPill.left < gapLeft || namedPill.right > gapRight -> (gapLeft + gapRight) / 2f - (namedPill.left + namedPill.right) / 2f
                else -> 0f
            }
            if (namedPill.translationX != shift) namedPill.translationX = shift
        }
    }

    private fun buildToolBar() = toolBar.apply {
        clipChildren = false
        // P186: the Glass board's palette: history, a hairline, the tools standing in their slots, then the colours.
        undoGroup.apply { addView(undo); addView(redo) }
        // [placeToolBar] sets out the tools and colours for the edge they are on.
        // B14: only Fit is shown, and P186 shows it only while the page is zoomed; pinch zooms. zoomOut/zoomIn stay detached.
        zoomGroup.apply { addView(fit) }
        toolPill.apply {
            background = Glass.pane(c, night, 36f); elevation = dp(10f); clipChildren = true
            addView(grip); addView(undoGroup); addView(historyRule); addView(toolGroup); addView(zoomGroup); addView(colourRule); addView(dotGroup)
        }
        addView(toolPill)
        bindGrip()
        inkDots.forEach { dot -> dot.setOnClickListener { dot.color?.let { color -> act { applyRingColor(color.toLong() and 0xffffffffL) } } } }
        allColours.onTap { colourCircle(allColours, drawingWithHighlighter) { applyRingColor(it) } }
        // Tapping the active tool again opens its settings; tapping another tool selects it.
        pens.forEachIndexed { index, button ->
            button.onTap {
                if(ink.laserEnabled) ink.selectLaser(false)
                if (button.active) open(button, width = 452f, toward = true, tool = true) {
                    ToolControls.pen(it, ink, "Pen ${index + 1} of ${pens.size}") {
                        colourCircle(button, highlighter = false) { color -> ink.selectPenStyle(ink.penStyle.copy(color = color)); activity.refresh() }
                    }
                }
                else { selectPen(index); tick(button) }
            }
        }
        highlighter.onTap { if(ink.laserEnabled) ink.selectLaser(false);
            if (highlighter.active) open(highlighter, width = 452f, toward = true, tool = true) {
                ToolControls.highlighter(it, ink) {
                    colourCircle(highlighter, highlighter = true) { color -> ink.selectHighlighterStyle(ink.highlighterStyle.copy(color = color)); activity.refresh() }
                }
            }
            else { ink.selectLasso(false); ink.eraser = false; ink.painter = false; ink.selectHighlighter(true); tick(highlighter); activity.refresh() }
        }
        eraser.onTap { if(ink.laserEnabled) ink.selectLaser(false);
            if (eraser.active) open(eraser, width = 420f, toward = true, tool = true) { ToolControls.eraser(it, ink) { activity.refresh() } }
            else { ink.selectHighlighter(false); ink.selectLasso(false); ink.eraser = true; tick(eraser); activity.refresh() }
        }
        paintBrush.onTap { if(ink.laserEnabled) ink.selectLaser(false);
            if (paintBrush.active) open(paintBrush, width = 452f, toward = true, tool = true) {
                ToolControls.paint(it, ink, { activity.refresh() }) {
                    colourCircle(paintBrush, highlighter = false) { color -> ink.selectPenStyle(ink.penStyle.copy(color = color)); activity.refresh() }
                }
            }
            else { ink.selectHighlighter(false); ink.selectLasso(false); ink.painter = true; tick(paintBrush); activity.refresh() }
        }
        lasso.onTap { if(ink.laserEnabled) ink.selectLaser(false);
            if (lasso.active) open(lasso, width = 420f, toward = true, tool = true) { ToolControls.lasso(it, ink) { activity.refresh() } }
            else { ink.selectHighlighter(false); ink.selectLasso(true); tick(lasso); activity.refresh() }
        }
        shapes.onTap { if(ink.laserEnabled) ink.selectLaser(false); shapesMenu() }
        undo.onTap { repository.edit { it.undo() } }
        redo.onTap { repository.edit { it.redo() } }
        zoomOut.onTap { ink.zoom(.8f) }
        fit.onTap { ink.fitButton() }
        // F089: long-press Fit locks or unlocks the zoom level.
        fit.setOnLongClickListener { act { ink.selectZoomLock(!ink.zoomLocked); tick(fit); activity.refresh() }; true }
        zoomIn.onTap { ink.zoom(1.25f) }
    }

    /** Fault bar actions are pills: the first (primary) filled white, the rest outlined; disabled ones fade. */
    private fun faultAction(label: String, primary: Boolean = false) = TextView(c).apply {
        text = label; textSize = 15f; typeface = Tokens.SANS_MEDIUM
        setTextColor(if (primary) Tokens.FAULT else 0xffffffff.toInt())
        gravity = Gravity.CENTER; minHeight = dpi(40f); setPadding(dpi(18f), 0, dpi(18f), 0)
        background = if (primary) rounded(0xffffffff.toInt(), dp(20f)) else rounded(0, dp(20f), 0xb3ffffff.toInt(), dpi(1.5f))
        foreground = ripple(!primary, dp(20f))
    }
    private fun faultBar() = fault.apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Tokens.FAULT); setPadding(dpi(16f), dpi(8f), dpi(12f), dpi(8f)); visibility = View.GONE
        elevation = dp(2f)
        // A round "!" badge marks the bar as a problem without relying on colour alone.
        addView(TextView(c).apply {
            text = "!"; textSize = 15f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER
            setTextColor(Tokens.FAULT); background = rounded(0xffffffff.toInt(), dp(12f)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(c.dpi(24f), c.dpi(24f)).apply { marginEnd = c.dpi(12f) })
        faultText.apply { textSize = 15f; typeface = Tokens.SANS_MEDIUM; setTextColor(0xffffffff.toInt()); maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
        addView(faultText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        retry.onTap { repository.save() }
        exportCopy.onTap { activity.launchExport() }
        addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dpi(12f) })
        addView(exportCopy, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dpi(8f) })
    }

    private fun selectionBar() = selectionBar.apply {
        cutInk.onTap { ink.copySelectedInk(cut = true) }
        copyInk.onTap { ink.copySelectedInk() }
        editText.onTap { ink.selectedTextId?.let { id -> activity.textBoxDialog(id) { ink.selectText(it) } } }
        linkSelection.onTap {NotebookLinks.choose(activity)}
        removeSelectionLink.onTap {NotebookLinks.remove(activity)}
        foldNote.onTap {ink.selectedTextId?.let {StickyNotes.toggle(activity,repository.selectedPageId!!,it)}}
        editTable.onTap {TableSheet.edit(activity)}
        linkSource.onTap {ink.linkPastedToSource()}
        connectShapes.onTap {ConnectSheet.show(activity)}
        cropPhoto.onTap { ink.selectedPhotoId?.let { id -> activity.photoCropDialog(id) { ink.selectPhoto(id) } } }
        saveSticker.onTap { activity.saveStickerDialog() }
        saveStamp.onTap { activity.saveStampDialog() }
        colorInk.onTap { restyleSelection() }
        refineInk.onTap { refineSelection() }
        widthInk.onTap { restyleSelection() }
        straightenInk.onTap { ink.straightenSelection() }
        tapeInk.onTap { ink.tapeSelection() }
        groupInk.onTap { ink.groupSelection() }
        lockInk.onTap { ink.lockSelection() }
        layerInk.onTap { activity.selectionLayerDialog() }
        pasteInk.onTap { ink.pasteInk() }
        duplicateInk.onTap { ink.duplicateSelection() }
        deleteInk.onTap { ink.deleteSelection() }
        deselect.onTap { ink.clearSelection() }
        moreInk.onTap { selectionMenu() }
        setActions(listOf(linkSource, editText, foldNote, editTable, connectShapes, cropPhoto, cutInk, copyInk, pasteInk, colorInk, refineInk, duplicateInk, deleteInk, moreInk, deselect))
    }

    private fun selectionMenu() = menu(moreInk, null, selectionExtras.filter { it.visibility == View.VISIBLE }.map { button ->
        Triple(button.text ?: button.contentDescription.toString(), button.isEnabled) { button.performClick(); Unit }
    })

    private fun positionSelectionBar() {
        val pdfBounds=ink.pdfTextBoundsInView
        if(pdfBounds==null || settingsOpen || repository.busy || repository.fileBusy || !ink.inputEnabled) pdfTextBar.visibility=View.GONE
        else {
            pdfTextCopy.isEnabled=ink.selectedPdfText.isNotEmpty()
            pdfBounds.offset(ink.x,ink.y)
            pdfTextBar.place(pdfBounds,desk.width,desk.height,chromeTop,chromeBottom)
        }
        val locked = ink.lockedPickBoundsInView
        if (locked == null || ink.isEditing || !ink.inputEnabled || settingsOpen || repository.busy || repository.fileBusy) unlockBar.visibility = View.GONE
        else { locked.offset(ink.x, ink.y); unlockBar.place(locked, desk.width, desk.height, chromeTop, chromeBottom) }
        val spot = ink.pasteSpotInView
        if (spot == null || ink.isEditing || !ink.inputEnabled || settingsOpen || repository.busy || repository.fileBusy) pasteBar.visibility = View.GONE
        else { spot.offset(ink.x, ink.y); pasteBar.place(spot, desk.width, desk.height, chromeTop, chromeBottom) }
        val bounds = ink.selectedBoundsInView
        if (!ink.lassoEnabled || bounds == null || ink.isEditing || !ink.inputEnabled || settingsOpen || repository.busy || repository.fileBusy) {
            if (selectionBar.visibility == View.VISIBLE) popover?.dismiss()
            selectionBar.visibility = View.GONE; return
        }
        bounds.offset(ink.x, ink.y)
        colorInk.dot = selectionColor()
        val moved = selectionBar.place(bounds, desk.width, desk.height, chromeTop, chromeBottom)
        if ((moved && (popover == null || popover !== refinePopover)) || selectionBar.visibility != View.VISIBLE) popover?.dismiss()
    }

    private fun settingsScreen() = settings.apply { visibility = View.GONE; isClickable = true }
    /** P567: public builds only, beside the app's name and version. */
    private fun updateButton() = if (c.personalTools) null else Kit.button(c, "Check for updates", Kit.Button.TINTED, small = true) { UpdateCheck.show(c) }
    fun openSettings() {
        popover?.dismiss()
        val night = activity.dark
        settings.removeAllViews(); settingsNavRows.clear(); settingsPages.clear()
        settings.setBackgroundColor(Kit.desk(night))
        // P183 (G8): the sidebar sits beside the detail only where both fit; a narrow screen stacks every group.
        val screen = c.resources.displayMetrics.widthPixels
        val split = screen >= c.dpi(SETTINGS_SIDEBAR + 500f)
        val fill = Kit.cardFill(night)
        // Each group's blocks, with the words a search finds them by.
        val blocks = mutableListOf<MutableList<Pair<View, String>>>()
        fun words(view: View): List<String> = listOfNotNull((view as? TextView)?.text?.toString(), view.contentDescription?.toString()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { words(view.getChildAt(it)) } else emptyList()
        fun page(title: String) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Kit.text(c, title, Kit.Type.TITLE).apply { setPadding(dpi(4f), dpi(10f), 0, dpi(2f)); isAccessibilityHeading = true })
            settingsPages += this; blocks.add(mutableListOf())
        }
        // A caps label over a card, as the board's "STYLUS" and "PALM REJECTION". Rows go straight in the card;
        // other controls go in [body].
        fun block(page: LinearLayout, caps: String, build: (Kit.Group) -> Unit) {
            val card = Kit.Group(c, fill, 20f).apply { elevation = dp(.5f); build(this) }
            val block = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Kit.section(c, caps).apply { setPadding(dpi(16f), dpi(20f), dpi(16f), dpi(8f)) })
                addView(card)
            }
            page.addView(block)
            val title = ((page.getChildAt(0) as TextView).text).toString()
            blocks[settingsPages.indexOf(page)] += block to (listOf(title, caps) + words(card)).joinToString(" ")
        }
        fun Kit.Group.body(build: (LinearLayout) -> Unit) = addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(12f)); build(this)
        })
        fun label(text: String) = Kit.text(c, text, Kit.Type.HEADLINE).apply { textSize = 15f; setPadding(0, dpi(14f), 0, dpi(10f)) }
        fun note(text: String) = Kit.text(c, text, Kit.Type.CAPTION, secondary = true).apply { textSize = 13f; setPadding(0, dpi(8f), 0, dpi(2f)) }
        fun switchRow(title: String, on: Boolean, name: String = title, detail: String? = null, change: (Boolean) -> Unit) =
            Kit.row(c, title, detail, trailing = Kit.Switch(c, on, name, onChange = change))
        fun LinearLayout.put(view: View) = addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // P569: the guide sits first where someone looks when a gesture doesn't do what they expected.
        fun guide(page: LinearLayout) = block(page, "Gesture guide") { card ->
            card.addView(Kit.row(c, "Gestures", "What fingers and pens do on the page.", chevron = true) { GestureGuide.show(activity) })
        }
        val pen = page("Pen & stylus")
        guide(pen)
        // P568: only once a pen has been seen; a finger has no side button.
        if ((c.applicationContext as NotesApplication).penSeen) block(pen, "Pen side button") { card ->
            card.body {
                it.put(Kit.segmented(c, InkCanvas.ButtonAction.entries.map { action -> action.label }, ink.buttonAction.ordinal) { i ->
                    ink.selectButtonAction(InkCanvas.ButtonAction.entries[i])
                }.apply { contentDescription = "Pen side button" })
                it.put(note("Hold for the chosen tool; release to restore your selected tool. Selected tool disables the override. Applies while the pen is near the screen."))
            }
        }
        block(pen, "Pen gestures") { card -> card.body { ToolControls.writingPreferences(it) } }
        block(pen, "Hold to shape") { card ->
            card.addView(switchRow("Hold the pen still to shape", ink.holdToShape, "Global hold assist") { on -> ink.selectHoldToShape(on) })
            card.body {
                it.addView(ToolControls.demo(GestureDemo(c, GestureDemo.Kind.HOLD)))
                it.put(Kit.slider(c, "Hold delay", 250f, 1500f, ink.holdSettings.delayMs.toFloat(), { value -> "${value.toInt()} ms" },
                    { value -> (value / 50).toInt() * 50f }) { value -> ink.selectHoldSettings(ink.holdSettings.copy(delayMs = value.toLong())) })
                it.put(Kit.slider(c, "Movement tolerance", .5f, 3f, ink.holdSettings.toleranceMm, { value -> "%.1f mm".format(value) },
                    { value -> kotlin.math.round(value * 10) / 10 }) { value -> ink.selectHoldSettings(ink.holdSettings.copy(toleranceMm = value)) })
                it.put(note("Choose Freehand, Line or Shapes separately in each tool. Hold still after drawing to apply assistance; one undo restores your drawing. Choosing Line or Shapes turns assistance on. Off disables both tools without losing their choices."))
            }
        }

        val touch = page("Palm & touch")
        guide(touch)
        block(touch, "Fingers") { card ->
            card.addView(switchRow("Two-finger tap to undo", ink.twoFingerUndo, "Two-finger undo setting",
                "Writing and pen proximity keep palm rejection active.") { on -> ink.selectTwoFingerUndo(on) })
            card.body {
                it.addView(ToolControls.demo(GestureDemo(c, GestureDemo.Kind.TWO_FINGERS)))
                it.put(label("Finger navigation").apply { setPadding(0, dpi(8f), 0, dpi(10f)) })
                it.put(Kit.segmented(c, InkCanvas.FingerMode.entries.map { mode -> mode.label }, ink.fingerSettings.mode.ordinal) { i ->
                    ink.selectFingerSettings(ink.fingerSettings.copy(mode = InkCanvas.FingerMode.entries[i]))
                }.apply { contentDescription = "Finger navigation" })
            }
        }
        block(touch, "Palm rejection") { card ->
            card.addView(switchRow("Ignore hand while pen is near", ink.fingerSettings.handLock, "Hand lock") { on ->
                ink.selectFingerSettings(ink.fingerSettings.copy(handLock = on))
            })
            card.body {
                it.put(Kit.slider(c, "Hand lock grace", .3f, 3f, ink.fingerSettings.graceMs / 1000f, { value -> "%.1f s".format(value) },
                    { value -> kotlin.math.round(value * 10) / 10 }) { value -> ink.selectFingerSettings(ink.fingerSettings.copy(graceMs = kotlin.math.round(value * 10).toLong() * 100)) })
                it.put(note("How long your hand stays ignored after the pen moves away."))
            }
        }

        val layout = page("Pages & layout")
        block(layout, "Toolbar") { card ->
            card.body {
                it.put(Kit.segmented(c, Dock.entries.map { d -> d.label }, dock.ordinal) { i -> check(dockTo(Dock.entries[i])) { "Wait for input completion" } }
                    .apply { contentDescription = "Toolbar position" })
                it.put(note("You can also drag the tool palette by its edge to any side of the screen."))
            }
        }
        block(layout, "Pages") { card ->
            card.body {
                it.put(label("Page layout").apply { setPadding(0, 0, 0, dpi(10f)) })
                it.put(Kit.segmented(c, listOf("Single page", "Continuous"), if (continuousMode) 1 else 0) { i ->
                    if (splitOpen) activity.message("Side-by-side view stays single-page. Your choice returns when it closes.")
                    else check(setContinuousMode(i == 1)) { "Wait for input completion" }
                }.apply { contentDescription = "Page layout" })
                it.put(note("Continuous pages fit the width. Zoom is available in single-page mode."))
                it.put(label("Zoom"))
                it.put(Kit.segmented(c, listOf("Free", "Locked"), if (ink.zoomLocked) 1 else 0) { i ->
                    ink.selectZoomLock(i == 1); activity.refresh()
                }.apply { contentDescription = "Zoom lock setting" })
                it.put(note("Locked keeps the current zoom on every page; pinching stops changing it. Long-press Fit to switch."))
                it.put(label("In landscape"))
                it.put(Kit.segmented(c, listOf("Two pages", "One page"), if (ink.bookSpreadEnabled) 0 else 1) { i ->
                    ink.selectBookSpread(i == 0)
                }.apply { contentDescription = "Landscape spread setting" })
                it.put(note("Two pages open side by side like a book. Write on either page; the pen makes it current."))
            }
        }
        block(layout, "Turning pages") { card ->
            card.body {
                it.put(label("Turn pages in single-page mode").apply { setPadding(0, 0, 0, dpi(10f)) })
                it.put(Kit.segmented(c, listOf("Horizontal", "Vertical"), ink.fingerSettings.axis.ordinal) { i ->
                    ink.selectFingerSettings(ink.fingerSettings.copy(axis = dev.ownednotes.core.TurnAxis.entries[i]))
                }.apply { contentDescription = "Page turn direction" })
            }
            card.addView(switchRow("Paper curl", ink.pageCurlEnabled, "Page curl setting",
                "Pull a corner or swipe at 100%. In a two-page spread the sheet turns over the spine.") { on -> ink.selectPageCurl(on) })
            card.addView(switchRow("See-through paper while turning", ink.seeThroughEnabled, "See-through paper setting") { on -> ink.selectSeeThrough(on) })
        }

        val storage = page("Privacy & storage")
        block(storage, "Your notebooks") { card ->
            card.body {
                it.put(Kit.text(c, "Notebooks are stored on this tablet only, in the app's private storage. Use Export notebook to keep a copy elsewhere. Undo lasts while a notebook is open. Deleted notebooks can be restored from Library → Trash."))
            }
        }
        // P108 (F190): the search index is a rebuildable cache; rebuilding never touches notebooks.
        block(storage, "Search index") { card ->
            val app = c.applicationContext as NotesApplication
            fun describe() = PageText.index?.size()?.let { (pages, bytes) ->
                if (pages == 0) "Empty" else "$pages ${if (pages == 1) "page" else "pages"} · ${(bytes + 1023) / 1024} KB"
            } ?: "Empty"
            val value = Kit.text(c, describe(), secondary = true).apply { fontFeatureSettings = "tnum" }
            card.body {
                it.put(LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Kit.text(c, "Printed text in PDF and photo pages"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(value)
                })
                it.put(note("Kept on this tablet so search is quick. The app periodically trims older cached text to about 32 MB or 4,000 entries; missing text is read again when needed. Rebuilding empties this cache and reads the open notebook again. Notebook files and earlier versions are kept."))
                lateinit var rebuild: View
                rebuild = Kit.button(c, "Rebuild search index", Kit.Button.TINTED, small = true) {
                    rebuild.isEnabled = false; value.text = "Rebuilding…"
                    app.searchIndexer.rebuild(repository.session?.notebook) { rebuild.isEnabled = true; value.text = describe() }
                }.apply { contentDescription = "Rebuild search index" }
                it.addView(rebuild, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dpi(12f) })
            }
            searchIndexValue = value
        }
        // P563: the licence, what Google's text recognition library sends, and every library's notice.
        block(storage, "About") { card ->
            card.body {
                it.put(Kit.text(c, AboutNotices.ML_KIT))
                it.put(note(AboutNotices.LICENCE))
            }
            // P566: only where the public APK is what is installed. The owner's debug app has another id and key.
            if (!c.personalTools) card.addView(Kit.row(c, "Check for updates", chevron = true) { UpdateCheck.show(c) })
            card.addView(Kit.row(c, "Licences and notices", chevron = true) { AboutNotices.show(c) })
        }

        val empty = Kit.text(c, "No settings match.", secondary = true).apply { setPadding(dpi(4f), dpi(24f), 0, 0); visibility = View.GONE }
        val search = Kit.field(c, "Search settings", glyph = Glyph.SEARCH).apply { contentDescription = "Search settings" }
        fun show() {
            val query = search.text.toString().trim()
            settingsPages.forEachIndexed { i, page ->
                val found = blocks[i].count { (block, text) ->
                    val hit = query.isEmpty() || text.contains(query, ignoreCase = true)
                    block.visibility = if (hit) View.VISIBLE else View.GONE; hit
                }
                page.visibility = if (found > 0 && (query.isNotEmpty() || !split || i == settingsChosen)) View.VISIBLE else View.GONE
            }
            empty.visibility = if (settingsPages.none { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            settingsNavRows.forEachIndexed { i, row ->
                val on = query.isEmpty() && i == settingsChosen
                row.isSelected = on
                row.background = if (on) rounded(Glass.accent(night), c.dp(14f)) else null
                row.setTextColor(if (on) Color.WHITE else Glass.text(night))
                row.typeface = if (on) Kit.Type.HEADLINE.typeface else Tokens.SANS_MEDIUM
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = show()
        })

        val detail = ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, 0, 0, dpi(32f)) }
        detail.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            settingsPages.forEach { page -> put(page) }
            put(empty)
            put(Kit.privacy(c, "Your ink never leaves this tablet unless you export it yourself.").apply {
                (this.layoutParams as? ViewGroup.MarginLayoutParams)
            })
            (getChildAt(childCount - 1).layoutParams as LinearLayout.LayoutParams).topMargin = dpi(24f)
        })

        // The sidebar: title and Done, search, the app card, then the groups as rows on two cards.
        val sidebar = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; elevation = dp(3f)
            // By day the board's frosted sidebar reads pale blue-grey over the desk, so its white cards stand out.
            background = if (night) Glass.pane(c, true, 32f) else rounded(Kit.group(false), dp(32f), Color.WHITE, dpi(1f))
            setPadding(dpi(18f), dpi(20f), dpi(18f), dpi(18f))
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6f), 0, 0, 0)
                addView(Kit.text(c, "Settings", Kit.Type.LARGE_TITLE).apply { isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(Kit.button(c, "Done", Kit.Button.PLAIN) { closeSettings() }.apply { contentDescription = "Close settings" })
            })
            addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(14f) })
        }
        if (split) {
            val notebooks = repository.entries.size
            // Cards inside the glass sidebar: white by day; a step lighter than the pane by night, or they vanish into it.
            val inner = if (night) Kit.group(true) else fill
            val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "unknown"
            sidebar.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; background = rounded(inner, dp(20f)); setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f))
                addView(View(c).apply {
                    // The real launcher icon, so this card can't drift from what's on the home screen.
                    background = c.getDrawable(R.mipmap.ic_launcher)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginEnd = dpi(14f) })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Kit.text(c, c.getString(R.string.app_name), Kit.Type.HEADLINE))
                    addView(Kit.text(c, "$notebooks ${if (notebooks == 1) "notebook" else "notebooks"} · ${UpdateCheck.display(version)}", Kit.Type.CAPTION, secondary = true)
                        .apply { textSize = 13f; setPadding(0, dpi(4f), 0, 0); fontFeatureSettings = "tnum"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dpi(16f) })
            updateButton()?.let { sidebar.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dpi(10f) }) }
            // Board icons and colours: blue pen, orange hand, green paper; teal shield on its own card.
            val groups = listOf(
                listOf(Triple("Pen & stylus", Kit.Paths.PEN, 0xff0a84ff.toInt()), Triple("Palm & touch", Kit.Paths.HAND, Kit.NOTICE),
                    Triple("Pages & layout", Kit.Paths.PAPER, Kit.DONE)),
                listOf(Triple("Privacy & storage", Kit.Paths.SHIELD, Kit.TEAL)))
            groups.forEach { rows ->
                sidebar.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; background = rounded(inner, dp(20f)); dpi(4f).let { setPadding(it, it, it, it) }
                    rows.forEach { (name, path, tint) ->
                        val index = settingsNavRows.size
                        val row = TextView(c).apply {
                            text = name; textSize = 15f; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                            setPadding(dpi(10f), 0, dpi(12f), 0); compoundDrawablePadding = dpi(12f)
                            setCompoundDrawablesRelative(Kit.Icon(path, tint, dpi(30f), square = true).apply { setBounds(0, 0, dpi(30f), dpi(30f)) }, null, null, null)
                            foreground = ripple(night, dp(14f)); isClickable = true; isFocusable = true; contentDescription = "$name settings"
                            setOnClickListener { search.setText(""); settingsChosen = index; show(); detail.scrollTo(0, 0) }
                        }
                        settingsNavRows += row
                        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48f)))
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dpi(16f) })
            }
        } else updateButton()?.let { sidebar.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dpi(10f) }) }
        settingsChosen = settingsChosen.coerceIn(0, settingsPages.lastIndex)
        settings.addView(LinearLayout(c).apply {
            orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(20f), dpi(24f), 0); clipChildren = false; clipToPadding = false
            if (split) {
                addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(sidebar) },
                    LinearLayout.LayoutParams(dpi(SETTINGS_SIDEBAR), WRAP_CONTENT).apply { bottomMargin = dpi(20f) })
                addView(detail, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dpi(28f) })
            } else {
                addView(sidebar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dpi(8f) })
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        show()
        // P567: over the Library when opened from it. The Library stays underneath, so Done returns to the same place.
        settings.translationZ = if (activity.libraryScreen != null) 64f * activity.resources.displayMetrics.density else 0f
        activity.libraryScreen?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settings.visibility = View.VISIBLE
        activity.systemBars()
        activity.refresh()
    }
    internal val settingsPanel: View get() = settings
    /** P108: the search index size shown in Settings, for tests. */
    internal var searchIndexValue: TextView? = null; private set
    fun closeSettings() {
        if (settings.visibility == View.VISIBLE) (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(settings.windowToken, 0)
        settings.visibility = View.GONE
        activity.libraryScreen?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        activity.systemBars()
        activity.refresh()
    }

    val choosingPages get() = pagePanel.choosing || organizer?.grid?.choosing == true
    fun stopChoosingPages() { pagePanel.stopChoosing(); organizer?.grid?.stopChoosing() }

    /** P201 step 6: the page organizer, a full-screen glass grid over the editor; null while closed. */
    internal var organizer: PageOrganizer? = null; private set
    val organizerOpen get() = organizer != null
    fun openOrganizer() {
        if (organizer != null || repository.session == null) return
        popover?.dismiss(); pagePanel.stopChoosing()
        organizer = PageOrganizer(activity, repository, activity.thumbnails, onOpen = { closeOrganizer(); revealPage(it) }, onClose = { closeOrganizer() })
            .also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        activity.refresh()
    }
    fun closeOrganizer() {
        val open = organizer ?: return
        organizer = null; root.removeView(open); activity.refresh()
    }
    /** P178 (G3): a text chip in the page strip, in the Glass accent. */
    private fun stripButton(label: String) = TextView(c).apply {
        text = label; textSize = 15f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER; maxLines = 1
        setTextColor(Glass.accent(night)); minHeight = c.dpi(40f); setPadding(c.dpi(10f), 0, c.dpi(10f), 0)
        background = ripple(night, c.dp(Glass.CHIP)); isClickable = true; isFocusable = true
    }
    private fun buildPageStrip() {
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dpi(14f), c.dpi(4f), c.dpi(6f), 0) }
        header.addView(TextView(c).apply { text = "Pages"; textSize = 14f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(night)) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(allPages, LinearLayout.LayoutParams(c.dpi(40f), c.dpi(40f)))
        header.addView(choosePages)
        choosePages.setOnClickListener { if (pagePanel.choosing) pagePanel.stopChoosing() else pagePanel.startChoosing() }
        pageStrip.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pageStrip.addView(pagePanel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bulkBar.orientation = LinearLayout.VERTICAL
        bulkBar.background = Glass.pane(c, night, Glass.CHIP + 2f); bulkBar.elevation = c.dp(4f)
        bulkBar.setPadding(c.dpi(6f), c.dpi(6f), c.dpi(6f), c.dpi(4f))
        chosenCount.apply { textSize = 13f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night)); gravity = Gravity.CENTER; setPadding(0, 0, 0, c.dpi(2f)) }
        bulkBar.addView(chosenCount, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val actions = LinearLayout(c)
        actions.addView(movePages, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(deletePages, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        deletePages.setTextColor(Tokens.FAULT)
        bulkBar.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        movePages.setOnClickListener { activity.movePagesDialog(pagePanel.chosenIds) }
        deletePages.setOnClickListener { activity.deletePagesDialog(pagePanel.chosenIds) }
        pageStrip.addView(bulkBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(c.dpi(8f), c.dpi(4f), c.dpi(8f), c.dpi(8f)) })
        showPageChoice()
    }
    /** P178 (G3): Select becomes Done while choosing; the bulk bar counts the chosen pages and keeps one page back. */
    private fun showPageChoice() {
        val on = pagePanel.choosing; val count = pagePanel.chosenIds.size; val all = count >= pagePanel.pageCount
        choosePages.text = if (on) PageStripAction.DONE.label else PageStripAction.SELECT.label
        choosePages.contentDescription = if (on) "Done choosing pages" else "Select pages"
        bulkBar.visibility = if (on) View.VISIBLE else View.GONE
        chosenCount.text = when { count == 0 -> "Tap pages to choose"; all -> "All chosen · one must stay"; else -> "$count chosen" }
        listOf(movePages, deletePages).forEach { it.isEnabled = count > 0 && !all; it.alpha = if (it.isEnabled) 1f else .4f }
    }

    /** Side of a tool bar control that faces the page. */
    private val pageSide get() = when (dock) { Dock.TOP -> Gravity.BOTTOM; Dock.BOTTOM -> Gravity.TOP; Dock.LEFT -> Gravity.END; Dock.RIGHT -> Gravity.START }
    private fun open(anchor: View, alignEnd: Boolean = false, width: Float? = 340f, toward: Boolean = false, above: Boolean = false,
        tool: Boolean = false, build: (LinearLayout) -> Unit) {
        popover?.dismiss()
        popover = Popover(anchor, width, tool, build).also {
            if (above) it.showAboveOrBelow() else if (toward) it.showToward(pageSide) else it.show(alignEnd)
        }
    }
    /**
     * P190: the colour circle pops out beside [anchor] toward the page, in place of the old centred dialog. It starts at the
     * pen's or highlighter's colour and gives [apply] the colour only on "Use colour".
     * P200: given [around] (a point in the window), the compact circle opens centred there instead, as from the quick ring.
     */
    private fun colourCircle(anchor: View, highlighter: Boolean, around: android.graphics.Point? = null, apply: (Long) -> Unit) {
        val now = (if (highlighter) ink.highlighterStyle else ink.penStyle).color
        if (around == null) return open(anchor, width = 340f, toward = true, tool = true) { it.addView(ColorWheel(c, now, highlighter, use = apply)) }
        popover?.dismiss()
        popover = Popover(anchor, width = 252f, tool = true) {
            it.dpi(18f).let { pad -> it.setPadding(pad, pad, pad, pad) }
            it.addView(ColorWheel(c, now, highlighter, compact = true, use = apply))
        }.also {
            // In the ring's own glass, so on a white page it reads as the ring turned into a colour circle.
            it.window.setBackgroundDrawable(Glass.pane(c, night, 30f)); it.showAround(around.x, around.y)
        }
    }
    /**
     * B27 (P192): the selection's Style pop-out by "Style…". [draft] carries what was already changed across a trip to the
     * colour circle, which opens in its place and comes back here on "Use colour".
     */
    /** P201 step 5: the Selection board's shape-snap pill, naming the shape, with "Keep my stroke" (one Undo). */
    internal var snapToast: View? = null
        private set
    private fun showSnapToast(kind: ShapeRecognizer.Kind, after: Notebook) {
        snapToast?.let { desk.removeView(it) }
        val name = when (kind) {
            ShapeRecognizer.Kind.LINE -> "Straight line"; ShapeRecognizer.Kind.ARROW -> "Arrow"; ShapeRecognizer.Kind.TRIANGLE -> "Triangle"
            ShapeRecognizer.Kind.RECTANGLE -> "Rectangle"; ShapeRecognizer.Kind.QUADRILATERAL -> "Four-sided shape"; ShapeRecognizer.Kind.POLYGON -> "Polygon"
            ShapeRecognizer.Kind.CIRCLE -> "Perfect circle"; ShapeRecognizer.Kind.ELLIPSE -> "Ellipse"; ShapeRecognizer.Kind.STAR -> "Star"
            ShapeRecognizer.Kind.HEART -> "Heart"; ShapeRecognizer.Kind.CHECK -> "Tick"; ShapeRecognizer.Kind.LIGHTNING -> "Lightning bolt"
            ShapeRecognizer.Kind.POLYLINE -> "Straight segments"; ShapeRecognizer.Kind.CURVE -> "Smooth curve"
        }
        snapToast = Kit.toast(desk, name, Kit.DONE, "Keep my stroke", compact = true, topMargin = chromeTop + c.dpi(12f)) {
            // The snap is two history steps; one Undo brings back the freehand stroke, but only if nothing changed since.
            if (repository.session?.notebook === after && !repository.busy && !repository.readOnly) repository.edit { it.undo() }
        }
    }

    /** P201 step 5: the colour the selection menu's "Style…" dot shows: the first pen stroke's, else the first stroke's. */
    private fun selectionColor(): Int? {
        val target = ink.selectionColorTarget() ?: return null
        val strokes = target.notebook.pages.firstOrNull { it.id == target.pageId }?.strokes.orEmpty().filter { it.id in target.ids && !it.isTape }
        return (strokes.firstOrNull { !it.isHighlighter } ?: strokes.firstOrNull())?.let { (it.color or 0xff000000L).toInt() }
    }

    internal fun restyleSelection(draft: InkSelection.Restyle = InkSelection.Restyle()) {
        val target = ink.selectionColorTarget() ?: return
        val strokes = target.notebook.pages.firstOrNull { it.id == target.pageId }?.strokes.orEmpty().filter { it.id in target.ids && !it.isTape }
        val first = strokes.firstOrNull { !it.isHighlighter } ?: strokes.firstOrNull() ?: return
        val start = PenStyle(first.color or 0xff000000L, first.width.coerceIn(.5f, 50f), PenKind.forBrush(first.brush))
        val pens = strokes.any { !it.isHighlighter }
        open(colorInk, width = 380f, above = true, tool = true) { column ->
            ToolControls.selection(column, start, draft, target.ids.size, pens, strokes.any { it.isHighlighter },
                fillEligible = strokes.all { it.canFill }, initialFill = first.fillColor,
                customFill = { now ->
                    open(colorInk, width = 340f, above = true, tool = true) {
                        it.addView(ColorWheel(c, now.fillColor ?: first.fillColor ?: start.color, false) { color -> restyleSelection(now.copy(fillChanged = true, fillColor = color)) })
                    }
                },
                custom = { now ->
                    open(colorInk, width = 340f, above = true, tool = true) {
                        it.addView(ColorWheel(c, now.color ?: start.color, !pens) { color -> restyleSelection(now.copy(color = color)) })
                    }
                },
                apply = { change ->
                    check(change.isEmpty || ink.restyleSelection(target, change)) { "The selection changed or is busy. Select it again." }
                })
        }
    }
    /** P413: the open Refine… pop-out; the selection bar moving as the writing is tidied doesn't close it. */
    private var refinePopover: Popover? = null
    /** B60: the selected writing uses the same current hands as automatic handwriting styling. */
    internal fun refineSelection() {
        val target = ink.selectionColorTarget() ?: return
        val strokes = target.notebook.pages.firstOrNull { it.id == target.pageId }?.strokes
            ?.filter { it.id in target.ids && HandwritingRestyle.eligible(it) }.orEmpty()
        if (strokes.isEmpty()) { android.widget.Toast.makeText(c, "Select handwriting to refine.", android.widget.Toast.LENGTH_SHORT).show(); return }
        HandStyleSheet.show(c, strokes) { pieces -> ink.rewriteSelectedHandwriting(target, pieces) }
    }
    /** P177 (G2): rows sit in grouped glass cards; a null row starts the next group. [icons] lead rows with coloured squares. */
    /** P186: [captions] names the group a null row starts, for menus that join two homes (Insert with Capture, Search with Study). */
    private fun menu(anchor: View, header: String?, rows: List<Triple<String, Boolean, () -> Unit>?>, toward: Boolean = false, above: Boolean = false, icons: List<Pair<Glyph, Int>?>? = null, captions: Map<Int, String> = emptyMap()) = open(anchor, alignEnd = true, width = 300f, toward = toward, above = above) { column ->
        column.setPadding(column.dpi(10f), column.dpi(10f), column.dpi(10f), column.dpi(10f))
        header?.let { column.addView(ToolControls.caption(c, it).apply { setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(8f)) }) }
        var group: LinearLayout? = null
        rows.forEachIndexed { index, row ->
            if (row == null) {
                group = null
                captions[index]?.let { column.addView(ToolControls.caption(c, it).apply { setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(6f)) }) }
                return@forEachIndexed
            }
            val into = group ?: ToolControls.menuGroup(c, column.childCount == (if (header == null) 0 else 1)).also { column.addView(it); group = it }
            val icon = icons?.getOrNull(index)
            into.addView(ToolControls.menuRow(c, row.first, row.second, icon?.first, icon?.second ?: Glass.ACCENT) { popover?.dismiss(); act(row.third) })
        }
    }
    private fun overflowMenu() = commandMenu(more, EditorMenu.NOTEBOOK)
    /** P186: the Glass board has no Capture button; Insert lists Capture and files below its own rows. */
    private fun insertMenu() = commandMenu(insert, EditorMenu.INSERT, also = listOf(EditorMenu.CAPTURE))
    /** P186: the board has no Study button; Search lists Study below its own rows. */
    private fun searchMenu() = commandMenu(search, EditorMenu.NAVIGATE, also = listOf(EditorMenu.STUDY))
    /** P186: the board's "Page and paper": the paper settings, then the page actions. */
    private fun pageAndPaperMenu() = commandMenu(pageSettings, EditorMenu.PAGES, header = "Page and paper",
        lead = listOf(Triple(PAPER_SETTINGS, !repository.readOnly) { activity.pageSettingsDialog(); Unit }))
    private fun commandMenu(anchor: View, home: EditorMenu, also: List<EditorMenu> = emptyList(),
                            lead: List<Triple<String, Boolean, () -> Unit>> = emptyList(), header: String = home.label) {
        val rows = mutableListOf<Triple<String, Boolean, () -> Unit>?>()
        val icons = mutableListOf<Pair<Glyph, Int>?>()
        val captions = mutableMapOf<Int, String>()
        fun gap(caption: String?) { rows += null; icons += null; caption?.let { captions[rows.size - 1] = it } }
        fun add(menu: EditorMenu) = EditorCommand.shownIn(menu, c).forEach { command ->
            rows += Triple(commandLabel(command), commandEnabled(command)) { runCommand(command) }; icons += menuIcon(command) to menuTint(command)
        }
        // P184 (G9): the notebook menu leads with the command palette, in a group of its own.
        if (home == EditorMenu.NOTEBOOK) { rows += Triple(FIND_COMMAND, true) { more.post { commandPalette() }; Unit }; icons += Glyph.SEARCH to Glass.ACCENT; gap(null) }
        if (lead.isNotEmpty()) { lead.forEach { rows += it; icons += Glyph.PAGE_SETTINGS to menuTint(EditorCommand.DUPLICATE_PAGE) }; gap(home.label) }
        add(home)
        also.forEach { gap(it.label); add(it) }
        menu(anchor, header, rows, icons = icons, captions = captions)
    }
    /** P184 (G9): every editor command by name in a glass search sheet; each runs through runCommand and its own guards. */
    internal fun commandPalette() = open(more, alignEnd = true, width = 380f) { column ->
        column.setPadding(column.dpi(10f), column.dpi(10f), column.dpi(10f), column.dpi(10f))
        val results = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val query = EditText(c).apply {
            hint = FIND_COMMAND; contentDescription = "Find a command"; setSingleLine(); textSize = 16f; typeface = Tokens.SANS
            setTextColor(Glass.text(night)); setHintTextColor(Glass.secondary(night))
            background = rounded(if (night) 0xff353b47.toInt() else 0xffffffff.toInt(), c.dp(Glass.PILL), if (night) Glass.RULE_NIGHT else Glass.RULE, c.dpi(1f))
            setPadding(c.dpi(16f), c.dpi(10f), c.dpi(16f), c.dpi(10f))
        }
        fun list(text: String) {
            results.removeAllViews()
            val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val found = EditorCommand.entries.filter { command -> EditorCommand.offered(command, c) && "${commandLabel(command)} ${command.home.label}".lowercase().let { label -> words.all { it in label } } }
            if (found.isEmpty()) { results.addView(ToolControls.caption(c, "No command matches “${text.trim()}”").apply { setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(4f)) }); return }
            val group = ToolControls.menuGroup(c, true).also { results.addView(it) }
            found.forEach { command ->
                group.addView(ToolControls.menuRow(c, commandLabel(command), commandEnabled(command), menuIcon(command), menuTint(command)) { popover?.dismiss(); act { runCommand(command) } }
                    .apply { contentDescription = "${commandLabel(command)}, ${command.home.label}" })
            }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                list(s?.toString().orEmpty())
                // The sheet opened at the full list's height; let it shrink to the rows that remain.
                popover?.window?.let { if (it.isShowing) it.update(it.width, WRAP_CONTENT) }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        column.addView(query, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = c.dpi(8f) })
        column.addView(results); list("")
        paletteQuery = query
    }
    /** P184: the palette's search field while it is open, for tests. */
    internal var paletteQuery: EditText? = null; private set
    /** P177 (G2): each command's icon square; the glyph falls back to its menu's, the colour is its menu's (red when it removes work). */
    private fun menuIcon(command: EditorCommand): Glyph = when (command) {
        EditorCommand.PASTE -> Glyph.PASTE
        EditorCommand.DUPLICATE_PAGE, EditorCommand.COPY_PAGE -> Glyph.DUPLICATE
        EditorCommand.STICKERS, EditorCommand.STAMPS -> Glyph.SHAPES
        EditorCommand.SEARCH -> Glyph.SEARCH
        EditorCommand.ORGANIZER -> Glyph.PAGES
        EditorCommand.HISTORY -> Glyph.RETRY
        EditorCommand.LAYERS -> Glyph.PANEL
        EditorCommand.MOVE_PAGE -> Glyph.GRIP
        EditorCommand.UNLOCK -> Glyph.UNLOCK
        EditorCommand.CLEAR_INK -> Glyph.ERASER_AREA
        EditorCommand.DELETE_PAGE, EditorCommand.CLEAR_TRANSCRIPT -> Glyph.DELETE
        EditorCommand.GESTURES -> Glyph.LASSO_FREEFORM
        EditorCommand.SETTINGS -> Glyph.PAGE_SETTINGS
        else -> when (command.home) {
            EditorMenu.INSERT -> Glyph.PLUS; EditorMenu.CAPTURE -> Glyph.CAPTURE; EditorMenu.STUDY -> Glyph.STUDY; EditorMenu.NAVIGATE -> Glyph.SEARCH
            EditorMenu.PAGES -> Glyph.PAGES; EditorMenu.EXPORT -> Glyph.SHARE; EditorMenu.NOTEBOOK -> Glyph.MENU
        }
    }
    private fun menuTint(command: EditorCommand): Int = when (command) {
        EditorCommand.DELETE_PAGE, EditorCommand.CLEAR_INK, EditorCommand.CLEAR_TRANSCRIPT -> Tokens.FAULT
        else -> when (command.home) {
            EditorMenu.INSERT -> Glass.ACCENT; EditorMenu.CAPTURE -> 0xff168f84.toInt(); EditorMenu.STUDY -> 0xff6f52e0.toInt()
            EditorMenu.NAVIGATE -> 0xff3f5bd9.toInt(); EditorMenu.PAGES -> 0xffd9821c.toInt(); EditorMenu.EXPORT -> 0xff2b9156.toInt(); EditorMenu.NOTEBOOK -> 0xff667085.toInt()
        }
    }
    internal fun commandLabel(command: EditorCommand): String = when (command) {
        EditorCommand.LASER -> if(ink.laserEnabled) "Stop laser pointer" else command.label
        EditorCommand.PASTE -> ink.clipboardLabel
        EditorCommand.READ_ONLY -> if (repository.readOnly) "Enable editing" else command.label
        EditorCommand.OPEN_ALONGSIDE -> if (splitOpen) "Close companion" else command.label
        EditorCommand.BOOKMARK -> if (repository.currentPage?.bookmark == null) command.label else "Edit bookmark…"
        EditorCommand.TAPE -> if (ink.pageTapeCovered) "Reveal all study tape" else "Cover all study tape"
        else -> command.label
    }
    internal fun commandEnabled(command: EditorCommand): Boolean {
        val change = idle && repository.canChangeNotebook
        val edit = change && !repository.readOnly
        val note = repository.session?.notebook
        val page = repository.currentPage
        return when (command) {
            EditorCommand.GESTURES -> true
            EditorCommand.PASTE -> edit && ink.canPasteInk
            EditorCommand.BLANK, EditorCommand.PDF_PAGES, EditorCommand.IMAGE_PAGE, EditorCommand.SCAN, EditorCommand.DUPLICATE_PAGE -> edit && (note?.pages?.size ?: NotebookLimits.PAGES) < NotebookLimits.PAGES
            EditorCommand.PDF_SOURCES -> edit && note?.pages?.any { it.pdfBackground != null } == true
            EditorCommand.PAGE_TEXT -> change && page?.let { PageText.readable(it) } == true
            EditorCommand.MORE_ROOM -> edit && page?.hasBackground == false
            EditorCommand.PDF_SELECT_TEXT -> change && page?.pdfBackground!=null && !activity.pdfTextReading
            EditorCommand.TEXT_MARK -> edit && page?.hasBackground == true
            EditorCommand.CLEAR_TRANSCRIPT -> edit && page?.reviewedInk?.isNotEmpty() == true
            EditorCommand.TAPE -> change && ink.pageHasTape
            EditorCommand.LASER -> change && page != null
            EditorCommand.STUDY_PANEL -> idle && page != null
            EditorCommand.HISTORY -> change && (repository.canNavigateBack || repository.canNavigateForward)
            EditorCommand.COPY_PAGE -> change && repository.otherNotebooks().isNotEmpty()
            EditorCommand.TRANSFER_PAGE -> edit && (note?.pages?.size ?: 0) > 1 && repository.otherNotebooks().isNotEmpty()
            EditorCommand.MOVE_PAGE, EditorCommand.DELETE_PAGE -> edit && (note?.pages?.size ?: 0) > 1
            EditorCommand.UNLOCK -> edit && page?.let { p -> p.strokes.any { it.locked } || p.images.any { it.locked } || p.textBoxes.any { it.locked } || p.attachments.any { it.locked } } == true
            EditorCommand.CLEAR_INK -> edit && page?.strokes?.isNotEmpty() == true
            EditorCommand.MERGE -> edit && repository.otherNotebooks().isNotEmpty()
            EditorCommand.OPEN_ALONGSIDE -> idle && (splitOpen || repository.otherNotebooks().isNotEmpty())
            EditorCommand.HANDWRITING, EditorCommand.PREPARE_STUDY, EditorCommand.INK_EXPORT, EditorCommand.SEARCH, EditorCommand.BOOKMARKS, EditorCommand.ORGANIZER,
            EditorCommand.NATIVE_EXPORT, EditorCommand.PDF_EXPORT, EditorCommand.PNG_EXPORT, EditorCommand.TEXT_EXPORT,
            EditorCommand.READ_ONLY, EditorCommand.IMPORT, EditorCommand.IMPORT_PDF, EditorCommand.SETTINGS -> change
            else -> edit
        }
    }
    internal fun runCommand(command: EditorCommand) {
        if (!commandEnabled(command)) return
        when (command) {
            EditorCommand.LASER -> ink.selectLaser(!ink.laserEnabled)
            EditorCommand.STUDY_PANEL -> openStudyPanel()
            EditorCommand.PASTE -> ink.pasteInk()
            EditorCommand.BLANK -> activity.insertPage()
            EditorCommand.TEXT -> activity.textBoxDialog { ink.selectText(it) }
            EditorCommand.STICKY_NOTE -> activity.textBoxDialog(sticky=true) { ink.selectText(it) }
            EditorCommand.TABLE -> TableSheet.insert(activity)
            EditorCommand.STICKERS -> activity.stickerLibraryDialog()
            EditorCommand.STAMPS -> activity.stampLibraryDialog()
            EditorCommand.SCAN -> activity.launchScan()
            EditorCommand.PHOTO -> activity.launchInsertPhoto()
            EditorCommand.PDF_PAGES -> activity.launchInsertPdf()
            EditorCommand.IMAGE_PAGE -> activity.launchInsertImage()
            EditorCommand.ATTACH -> activity.launchAttachFile()
            EditorCommand.VIDEO -> activity.launchAttachFile(video = true)
            EditorCommand.PDF_SOURCES -> activity.pdfSourcesDialog()
            EditorCommand.HANDWRITING -> activity.handwritingDialog(ink.selectedIds.toSet())
            EditorCommand.PAGE_TEXT -> activity.pageTextDialog()
            EditorCommand.PDF_SELECT_TEXT -> activity.startPdfTextSelection()
            EditorCommand.TEXT_MARK -> activity.textMarkDialog()
            EditorCommand.PREPARE_STUDY -> activity.prepareStudyCopy()
            EditorCommand.INK_EXPORT -> activity.launchExportStudyInk()
            EditorCommand.CLEAR_TRANSCRIPT -> activity.clearHandwritingDialog()
            EditorCommand.TAPE -> ink.revealAllTape(ink.pageTapeCovered)
            EditorCommand.SEARCH -> activity.notebookSearchDialog()
            EditorCommand.BOOKMARKS -> activity.bookmarksDialog()
            EditorCommand.HISTORY -> activity.navigationHistoryDialog()
            EditorCommand.ORGANIZER -> openOrganizer()
            EditorCommand.DUPLICATE_PAGE -> activity.duplicatePage()
            EditorCommand.MORE_ROOM -> PageRoomSheet.show(activity)
            EditorCommand.BOOKMARK -> activity.bookmarkDialog()
            EditorCommand.MOVE_PAGE -> activity.movePageDialog()
            EditorCommand.COPY_PAGE -> activity.pageToNotebookDialog(false)
            EditorCommand.TRANSFER_PAGE -> activity.pageToNotebookDialog(true)
            EditorCommand.LAYERS -> activity.layersDialog()
            EditorCommand.UNLOCK -> { val n = repository.session!!.notebook; val id = repository.selectedPageId!!; repository.editLocks(n, id) { it.unlockPageObjects(id) } }
            EditorCommand.CLEAR_INK -> activity.clearPageInkDialog()
            EditorCommand.DELETE_PAGE -> activity.deletePageDialog()
            EditorCommand.NATIVE_EXPORT -> exportSheet(ExportFormat.NOTEBOOK)
            EditorCommand.PDF_EXPORT -> exportSheet(ExportFormat.PDF)
            EditorCommand.PNG_EXPORT -> exportSheet(ExportFormat.IMAGE)
            EditorCommand.TEXT_EXPORT -> exportSheet(ExportFormat.TEXT)
            EditorCommand.READ_ONLY -> activity.setReadOnly(!repository.readOnly)
            EditorCommand.RENAME -> activity.renameDialog()
            EditorCommand.MERGE -> activity.mergeNotebookDialog()
            EditorCommand.OPEN_ALONGSIDE -> if (splitOpen) activity.closeCompanionPane() else activity.openAlongsideDialog()
            EditorCommand.IMPORT -> activity.launchImport()
            EditorCommand.IMPORT_PDF -> activity.launchImportPdf()
            EditorCommand.SAVE -> repository.save()
            EditorCommand.GESTURES -> GestureGuide.show(activity)
            EditorCommand.SETTINGS -> openSettings()
        }
    }
    private fun shapesMenu() {
        val add = idle && repository.currentPage != null && !repository.readOnly
        open(shapes, alignEnd = true, width = 300f, toward = true) { column ->
            column.setPadding(column.dpi(8f), column.dpi(8f), column.dpi(8f), column.dpi(8f))
            column.addView(ToolControls.caption(c, "Shape pen: everything you draw becomes a shape").apply { setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(4f)) })
            column.addView(ToolControls.segmented(c, listOf("On", "Off"), if (ink.shapePen) 0 else 1) { i -> act { ink.selectShapePen(i == 0); refreshTools() } }.apply {
                contentDescription = "Shape pen"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(12f); marginEnd = dpi(12f) }
            })
            column.addView(ToolControls.menuRow(c, if (ink.rulerShown) "Hide ruler" else "Show ruler", true) { ink.showRuler(!ink.rulerShown); popover?.dismiss() })
            column.addView(ToolControls.caption(c, "Ruler scale").apply { setPadding(dpi(12f),dpi(8f),dpi(12f),dpi(4f)) })
            column.addView(Kit.segmented(c,listOf("Screen","1:1","1:10","1:100"),RulerScale.ratios.indexOf(ink.ruler.scaleRatio)) { i ->
                act { ink.selectRulerScale(RulerScale.ratios[i]); ink.showRuler(true) }
            }.apply { contentDescription="Ruler scale" })
            column.addView(ToolControls.caption(c,"Drawing distances are in cm and follow zoom.").apply { setPadding(dpi(12f),dpi(4f),dpi(12f),dpi(8f)) })
            column.addView(ToolControls.divider(c))
            column.addView(ToolControls.caption(c, "Insert a shape").apply { setPadding(dpi(12f), 0, dpi(12f), dpi(4f)) })
            dev.ownednotes.core.ShapeLibrary.Choice.entries.chunked(4).forEach { choices ->
                column.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(dpi(4f), 0, dpi(4f), 0)
                    choices.forEach { choice ->
                        addView(ShapeIcon(c, choice).apply {
                            isEnabled = add; alpha = if (add) 1f else .38f
                            setOnClickListener { popover?.dismiss(); act { ink.insertShape(choice); activity.refresh() } }
                        }, LinearLayout.LayoutParams(0, dpi(60f), 1f))
                    }
                    repeat(4 - choices.size) { addView(View(c), LinearLayout.LayoutParams(0, dpi(60f), 1f)) }
                })
            }
        }
    }
    /** One shape drawn as a picture from the same outline the page gets; its name is read aloud and shown on hold. */
    internal class ShapeIcon(context: android.content.Context, val choice: dev.ownednotes.core.ShapeLibrary.Choice) : View(context) {
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(2.2f); color = if(context.night)Glass.text(true) else Tokens.INK
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val path = android.graphics.Path()
        init {
            contentDescription = choice.label; tooltipText = choice.label
            isClickable = true; isFocusable = true; background = ripple(context.night, dp(10f))
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            path.reset()
            val box = minOf(w, h) - dp(22f)
            if (box <= 0) return
            val points = dev.ownednotes.core.ShapeLibrary.path(choice, dev.ownednotes.core.Point(0f, 0f), 100f, 1f)
            if (points.size < 2) return
            val left = points.minOf { it.x }; val top = points.minOf { it.y }
            val span = maxOf(points.maxOf { it.x } - left, points.maxOf { it.y } - top, 1f)
            val scale = box / span
            val dx = (w - (points.maxOf { it.x } - left) * scale) / 2f; val dy = (h - (points.maxOf { it.y } - top) * scale) / 2f
            points.forEachIndexed { i, p ->
                val x = dx + (p.x - left) * scale; val y = dy + (p.y - top) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        override fun onDraw(canvas: android.graphics.Canvas) { canvas.drawPath(path, line) }
    }
    private fun shareMenu() = exportSheet()
    /** P201 step 8: the Export board as the Export button's popover, open at [format]; the four Export commands come here too. */
    internal var exportSheet: ExportSheet? = null
        private set
    internal fun exportSheet(format: ExportFormat = ExportFormat.PDF) {
        val note = repository.session?.notebook ?: return
        val pageId = repository.selectedPageId ?: note.pages.firstOrNull()?.id ?: return
        val chosen = if (pagePanel.choosing) pagePanel.chosenIds.toSet() else emptySet()
        open(share, alignEnd = true, width = 580f, tool = true) { column ->
            exportSheet = ExportSheet(column, activity, note, pageId, chosen, format) { popover?.dismiss() }
        }
        popover?.grows = true
    }
    /** P201 step 9: the Study board's side panel at the right of the page, and its Recall check bar; null while closed. */
    internal var studyPanel: StudyPanel? = null
        private set
    internal var recallBar: RecallBar? = null
        private set
    internal var zoomWriting:ZoomWritingStrip?=null;private set
    internal fun openZoomWriting() {
        if(!repository.canChangeNotebook || ink.isEditing || repository.readOnly)return
        popover?.dismiss();closeRecall();closeFlashcards()
        if(zoomWriting==null)zoomWriting=ZoomWritingStrip(activity,ink){closeZoomWriting()}.also {desk.addView(it,FrameLayout.LayoutParams(-1,-1))}
    }
    internal fun closeZoomWriting() {
        val box=zoomWriting ?: return
        if(box.canvas.isEditing)return
        desk.removeView(box);zoomWriting=null
    }
    internal var flashcardBar:FlashcardRecallBar?=null;private set
    internal fun startFlashcards() {
        val app=activity.application as NotesApplication
        val note=repository.session?.notebook ?: return
        if(app.studyCards.due(note).isEmpty())return
        closeZoomWriting();closeRecall();closeFlashcards();openStudyPanel()
        flashcardBar=FlashcardRecallBar(c,app,note.id,{card,answer->studyPanel?.showCard(note,card,answer)},{closeFlashcards()}).also {desk.addView(it)}
        placeStudy()
    }
    internal fun closeFlashcards() {flashcardBar?.let {desk.removeView(it)};flashcardBar=null;studyPanel?.refresh()}
    fun openStudyPanel() {
        popover?.dismiss()
        if (studyPanel == null) studyPanel = StudyPanel(c, this, activity, ink, repository) { closeStudyPanel() }.also { desk.addView(it) }
        placeStudy()
    }
    fun closeStudyPanel() { closeFlashcards(); studyPanel?.let { desk.removeView(it) }; studyPanel = null }
    /** Covers every strip on the page and asks them back one at a time, top to bottom. */
    internal fun startRecall() {
        val pageId = repository.selectedPageId ?: return
        val ids = ink.pageTapeIds.takeIf { it.isNotEmpty() } ?: return
        closeZoomWriting(); closeRecall(); closeFlashcards()
        recallBar = RecallBar(c, ink, pageId, ids) { closeRecall() }.also { desk.addView(it) }
        placeStudy()
    }
    fun closeRecall() { recallBar?.let { desk.removeView(it) }; recallBar = null }
    /** Keeps the panel and the bar clear of the floating chrome. */
    private fun placeStudy() {
        flashcardBar?.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {bottomMargin=chromeBottom+c.dpi(18f)}
        studyPanel?.layoutParams = FrameLayout.LayoutParams(c.dpi(328f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = chromeTop + c.dpi(12f); bottomMargin = chromeBottom + c.dpi(12f); marginEnd = c.dpi(16f)
        }
        recallBar?.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = chromeBottom + c.dpi(18f)
        }
    }

    /** P186: the page-actions button is no longer shown; its menu opens from "Page and paper". */
    private fun pageMenu() = commandMenu(pageSettings, EditorMenu.PAGES)

    private val idle get() = !ink.isEditing && !repository.fileBusy && !repository.busy

    /** True when a vendor contact at window coordinates lands on chrome drawn over the page. */
    fun coversInk(x: Float, y: Float): Boolean {
        if (settingsOpen) return true
        if (zoomWriting?.covers(x,y)==true) return true
        // P176 (G1b): the floating pills are over the page; the pen must not write through them.
        val paneChrome = if (splitOpen) listOf(primaryTab, companionTab, paneDivider) else emptyList()
        return (paneChrome + listOfNotNull(selectionBar, unlockBar, pasteBar, libraryPill, namedPill, pagePill, actionPill, toolPill, fault, studyPanel, recallBar, flashcardBar)).any { bar ->
            bar.visibility == View.VISIBLE && IntArray(2).let { at -> bar.getLocationInWindow(at); Rect(at[0], at[1], at[0] + bar.width, at[1] + bar.height).contains(x.toInt(), y.toInt()) }
        }
    }

    fun refresh() {
        syncStrip()
        refreshPaneTabs()
        zoomWriting?.let {if(it.pageId!=repository.selectedPageId || it.notebookId!=repository.session?.notebook?.id)closeZoomWriting()else it.refresh()}
        val session = repository.session
        // P201 step 9: the Recall check belongs to one page; the panel follows the open page and closes with the notebook.
        if (recallBar?.pageId != null && recallBar?.pageId != repository.selectedPageId) closeRecall()
        if (flashcardBar?.notebookId != null && flashcardBar?.notebookId != session?.notebook?.id) closeFlashcards()
        if (session == null) { closeRecall(); closeFlashcards(); closeStudyPanel() }
        studyPanel?.refresh()
        // Preserve the last idle appearance, including already unavailable Undo/Redo, during a stroke/save.
        // Real enabled flags below continue to prevent navigation or tool changes in the middle of input.
        val holdChrome = session != null && !repository.busy && !repository.fileBusy &&
            (ink.isEditing || repository.inputBusy || repository.savingLocally)
        (pens + listOf(highlighter, eraser, paintBrush, lasso, shapes, zoomOut, fit, zoomIn, pages, insert, pageSettings, capture, study, search, share, more,
            panelToggle, library, undo, redo, previous, next)).forEach { it.holdEnabledAppearance(holdChrome) }
        (inkDots + allColours).forEach { it.holdEnabledAppearance(holdChrome) }
        val audio=(activity.application as NotesApplication).audio
        record.holdEnabledAppearance(holdChrome)
        record.isEnabled=idle && session!=null
        record.active=audio.active
        record.contentDescription=if(audio.active) "Study, audio recording in progress" else "Study"
        val ready = idle && session != null
        if (!idle) popover?.dismiss()
        (pens + listOf(highlighter, eraser, paintBrush, lasso, zoomOut, fit, zoomIn, pages, insert, pageSettings, capture, study, search, share, more, panelToggle)).forEach { it.isEnabled = ready }
        grip.isEnabled = ready
        zoomOut.isEnabled = ready && !continuousMode && !ink.zoomLocked
        zoomIn.isEnabled = ready && !continuousMode && !ink.zoomLocked
        fit.active = ink.zoomLocked
        fit.contentDescription = if (ink.zoomLocked) "Fit page, zoom locked" else "Fit page"
        showFit()
        // P186: "Page and paper" also holds the page actions, which keep their own read-only guards; its paper row is off.
        pageSettings.isEnabled = ready
        title.isEnabled = ready && !repository.readOnly
        namedPill.isEnabled = title.isEnabled
        library.isEnabled = idle && repository.canChangeNotebook
        undo.isEnabled = ready && !repository.readOnly && session?.canUndo == true
        redo.isEnabled = ready && !repository.readOnly && session?.canRedo == true
        (pens + listOf(highlighter, eraser, paintBrush, lasso, shapes)).forEach { it.isEnabled = ready && !repository.readOnly }
        val list = session?.notebook?.pages.orEmpty()
        val index = list.indexOfFirst { it.id == repository.selectedPageId }
        previous.isEnabled = ready && index > 0
        next.isEnabled = ready && index >= 0 && index < list.lastIndex
        pages.text = if (index < 0) "–" else "${index + 1} / ${list.size}"
        continuous.addPageSlot = continuousMode && canAddPage
        if (addPageCard.covering && (continuousMode || !canAddPage || !atLastDisplayedPage)) hideAddPage(instant = true)
        addPageCard.button.isEnabled = ready && canAddPage
        placeAddPage()
        title.text = session?.notebook?.title?.take(120).orEmpty()
        pagePosition = if (index < 0) "" else "Page ${index + 1} of ${list.size}"

        refreshTools()
        refreshStatus(ready)
    }
    private var pagePosition = ""
    /** P186: Fit appears in the palette only while the page is zoomed or its zoom is locked; pinch out also fits. */
    private fun showFit() {
        val zoomed = !continuousMode && (ink.zoomLocked || ink.pageScale > ink.fitScale * 1.02f)
        val shown = if (zoomed) View.VISIBLE else View.GONE
        if (fit.visibility != shown) fit.visibility = shown
    }

    /** Tool marks only; safe to call from inside input handling (no layout, repository or input calls). */
    fun refreshTools() {
        // Temporary tools never replace the selected tool; suspended selection stays marked, dashed.
        val override = ink.effectiveEraser && !ink.eraser
        val drawing = !ink.lassoEnabled && !ink.eraser && !ink.painter
        val penOverride = ink.effectivePen && (!drawing || (ink.highlighter && !ink.effectiveHighlighter))
        // The selected pen mirrors the live style; the presentation hook stays free of side effects other than the preset store.
        presets.sync(ink.penStyle)
        pens.forEachIndexed { index, button ->
            button.sample = presets[index]
            button.active = drawing && !ink.highlighter && index == presets.selected; button.suspended = button.active && !ink.effectivePen
            button.override = penOverride && index == presets.selected
            button.contentDescription = "Pen ${index + 1}" + if (index == presets.selected) ", selected" else ""
        }
        eraser.active = ink.eraser; eraser.override = override
        highlighter.active = drawing && ink.highlighter
        highlighter.highlighterSample = ink.highlighterStyle
        highlighter.suspended = highlighter.active && !ink.effectiveHighlighter
        eraser.suspended = eraser.active && !ink.effectiveEraser
        eraser.glyph = if (ink.eraserMode == InkCanvas.EraserMode.AREA) Glyph.ERASER_AREA else Glyph.ERASER_STROKE
        eraser.contentDescription = if (ink.eraserMode == InkCanvas.EraserMode.AREA) "Area eraser" else "Whole-stroke eraser"
        paintBrush.active = ink.painter; paintBrush.suspended = paintBrush.active && !ink.effectivePainter
        paintBrush.sample = ink.penStyle
        paintBrush.contentDescription = "Paint brush" + if (ink.painter) ", selected" else ""
        lasso.active = ink.lassoEnabled; lasso.suspended = lasso.active && !ink.effectiveLasso
        shapes.active = ink.shapePen; shapes.contentDescription = if (ink.shapePen) "Shapes, shape pen on" else "Shapes"
        lasso.glyph = if (ink.selectionOptions.shape == InkSelection.Shape.RECTANGLE) Glyph.LASSO_RECTANGLE else Glyph.LASSO_FREEFORM
        refreshDots()
    }

    /** P186: the palette's dots offer the colours the quick ring offers, for the highlighter while it is in use, else the pen. */
    private fun refreshDots() {
        val marker = drawingWithHighlighter
        val colors = InkColors(preferences).quick(marker).take(inkDots.size)
        val names = (InkColors.palettes.flatMap { it.second } + InkColors.highlights).toMap()
        val current = (if (marker) ink.highlighterStyle else ink.penStyle).color
        val drawing = !ink.eraser && !ink.lassoEnabled
        val enabled = pens.first().isEnabled
        inkDots.forEachIndexed { index, dot ->
            val color = colors.getOrNull(index)
            val shown = if (color == null) View.GONE else View.VISIBLE
            if (dot.visibility != shown) dot.visibility = shown
            dot.color = color?.toInt()
            dot.chosen = drawing && color == current
            val name = color?.let { names[it] ?: "Saved colour %06X".format(it and 0xffffff) } ?: "Colour"
            dot.contentDescription = name + if (dot.chosen) ", selected" else ""
            dot.isEnabled = enabled
        }
        allColours.isEnabled = enabled
    }

    private fun refreshStatus(ready: Boolean) {
        val session = repository.session
        val state = SaveState.of(repository.status)
        // P186: the Glass board's subtitle is the page; where the ink stands follows it only when it is not simply saved.
        // Saving and Saved stay off it, so the title does not flicker after every stroke; a failure also opens the fault bar.
        val label = when {
            repository.fileBusy -> "Reading or writing file…"
            ink.pdfBackgroundStatus?.startsWith("Rendering ") == true -> ink.pdfBackgroundStatus!!
            repository.readOnly -> "Read only"
            state == SaveState.SAVED || state == SaveState.SAVING || state == SaveState.READY -> null
            state == SaveState.LOADING -> "Loading…"
            state == SaveState.OPENING -> "Opening…"
            state == SaveState.WORKING -> repository.status
            else -> "Not saved"
        }
        statusText.text = listOfNotNull(pagePosition.takeIf { it.isNotEmpty() }, label).joinToString(" · ")
        statusText.setTextColor(if (label == "Not saved") Tokens.FAULT else Glass.secondary(night))

        val problems = buildList {
            if (state == SaveState.FAILED) add(repository.status)
            ink.pdfBackgroundStatus?.takeIf { it.contains("unavailable") }?.let(::add)
            repository.warning?.let(::add)
        }
        fault.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        faultText.text = problems.joinToString("\n")
        val saveFailed = repository.status.startsWith("Save failed")
        retry.visibility = if (saveFailed) View.VISIBLE else View.GONE
        exportCopy.visibility = retry.visibility
        retry.isEnabled = idle; exportCopy.isEnabled = idle && session != null
        retry.alpha = if (retry.isEnabled) 1f else .5f; exportCopy.alpha = if (exportCopy.isEnabled) 1f else .5f

        val selected = ink.selectedCount
        val photo = ink.selectedPhotoId != null
        val text = ink.selectedTextId != null
        val mixed = ink.mixedSelection
        val linkIds=ink.selectedObjectIds
        val linkSupported=NotebookLinks.supported(linkIds)
        linkSelection.visibility=if(linkSupported)View.VISIBLE else View.GONE
        linkSelection.isEnabled=ready && linkSupported && !repository.readOnly
        val hasLink=repository.currentPage?.let {NotebookLinks.has(it,linkIds)}==true
        linkSelection.text=if(hasLink)"Change link" else "Link"
        removeSelectionLink.visibility=if(linkSupported && hasLink)View.VISIBLE else View.GONE
        removeSelectionLink.isEnabled=ready && linkSupported && hasLink && !repository.readOnly

        editText.visibility = if (text) View.VISIBLE else View.GONE
        editText.isEnabled = ready && text
        val fromSource=ink.pastedSource!=null
        linkSource.visibility=if(fromSource)View.VISIBLE else View.GONE
        linkSource.isEnabled=ready && fromSource && repository.canChangeNotebook && !repository.readOnly
        val table=ink.selectedTable()!=null
        editTable.visibility=if(table)View.VISIBLE else View.GONE
        editTable.isEnabled=ready && table && repository.canChangeNotebook && !repository.readOnly
        val joining=ink.connectableShapes()!=null || ink.chainableShapes()!=null
        // P555: one connector selected: the same button changes it.
        val connectable=joining || ink.selectedConnector()!=null
        connectShapes.text=if(joining || !connectable)"Connect" else "Connector"
        connectShapes.contentDescription=if(joining || !connectable)"Connect shapes" else "Change connector"
        connectShapes.visibility=if(connectable)View.VISIBLE else View.GONE
        connectShapes.isEnabled=ready && connectable && repository.canChangeNotebook && !repository.readOnly
        val sticky=repository.currentPage?.textBoxes?.firstOrNull {it.id==ink.selectedTextId}?.sticky
        foldNote.visibility=if(sticky!=null)View.VISIBLE else View.GONE
        foldNote.isEnabled=ready && sticky!=null && repository.canChangeNotebook
        foldNote.text=if(sticky?.collapsed==true)"Unfold" else "Fold"
        foldNote.contentDescription=if(sticky?.collapsed==true)"Unfold sticky note" else "Fold sticky note"

        cropPhoto.visibility = if (photo) View.VISIBLE else View.GONE
        cropPhoto.isEnabled = ready && photo
        saveSticker.visibility = if (!ink.selectedObjectIds.isEmpty) View.VISIBLE else View.GONE
        saveSticker.isEnabled = ready && !ink.selectedObjectIds.isEmpty && repository.canChangeNotebook
        saveStamp.visibility = if (photo || text || mixed) View.GONE else View.VISIBLE
        saveStamp.isEnabled = ready && !photo && !text && !mixed && ink.selectedIds.isNotEmpty()
        listOf(colorInk, refineInk, widthInk, straightenInk, tapeInk).forEach { it.visibility = if (photo || text || mixed) View.GONE else View.VISIBLE }
        val group = ink.selectionIsGroup
        groupInk.visibility = if (photo || text) View.GONE else View.VISIBLE
        groupInk.text = if (group) "Ungroup" else "Group"
        groupInk.contentDescription = if (group) "Ungroup selection" else "Group selection"
        groupInk.isEnabled = ready && !photo && !text && (group || ink.selectedCount >= 2)
        layerInk.visibility = if ((repository.currentPage?.layers?.size ?: 0) >= 2) View.VISIBLE else View.GONE
        layerInk.isEnabled = ready && !ink.selectedObjectIds.isEmpty
        cutInk.contentDescription = if (mixed) "Cut selection" else if (text) "Cut text" else if (photo) "Cut photo" else "Cut handwriting"
        copyInk.contentDescription = if (mixed) "Copy selection" else if (text) "Copy text" else if (photo) "Copy photo" else "Copy handwriting"
        listOf(cutInk, copyInk, colorInk, widthInk, straightenInk, tapeInk, lockInk, duplicateInk, deleteInk, deselect).forEach { it.isEnabled = ready && selected > 0 }
        refineInk.isEnabled = ready && selected > 0 && repository.canChangeNotebook && !repository.readOnly
        pasteInk.contentDescription = ink.clipboardLabel
        pasteInk.isEnabled = ready && ink.canPasteInk
        pasteHere.contentDescription = ink.clipboardLabel
        pasteHere.isEnabled = ready && ink.canPasteInk
        moreInk.visibility = if (selectionExtras.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        moreInk.isEnabled = ready && selected > 0
        positionSelectionBar()
    }

    /** Screenshot hook for tests: shows a save failure in the fault bar until the next refresh. Changes no state. */
    internal fun previewFault(text: String) {
        fault.visibility = View.VISIBLE; faultText.text = text
        retry.visibility = View.VISIBLE; exportCopy.visibility = View.VISIBLE
    }
}
