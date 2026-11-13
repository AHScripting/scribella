package dev.ownednotes.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.widget.EditText
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.TextView
import dev.ownednotes.core.NotebookModifiedFilter
import dev.ownednotes.core.attachmentAssetIds
import dev.ownednotes.core.imageAssetIds
import dev.ownednotes.core.LibraryEntry
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.NotebookLibrary
import dev.ownednotes.core.Page
import dev.ownednotes.core.PaperPattern
import dev.ownednotes.core.PaperStyle
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen notebook library (design build-order step 7): covers of each notebook's first page, title and page
 * count, in `repository.entries` order. It talks only through NotebookRepository's public API; the activity owns
 * pickers, so Import is a callback. Unreadable entries show their error and are never opened.
 *
 * [onOpened] runs once the chosen or new notebook is the repository's current one. [loadCover] runs on a private
 * background thread and must return the notebook stored under the id (the default reads the package directly; the
 * library writes atomically, so a concurrent save is never seen half-written). [entriesSource] defaults to
 * `repository.entries`. The view registers its repository listener while attached and closes nothing it was given.
 *
 * Trash (P024): long-pressing a readable cover asks to move that notebook to the trash; the Trash button switches the
 * grid to [trashSource] (default `repository.trashed`), where tapping a cover restores it. Nothing here deletes.
 * P027: the long-press opens an actions sheet (Rename…, Duplicate, Move to trash…), and covers show the edit time.
 * P030: a search field filters covers by title and a Name / Recent switch orders them; both apply to Trash too.
 * P032: notebooks pinned from the long-press sheet come first in the library and carry an amber bookmark ribbon.
 * P044: library-local folders. A chip row under the search field shows All and each folder; the sheet's Move to folder…
 * files a notebook. Folders live in the library preferences, not in notebooks; deleting a folder never deletes notebooks.
 * P045: a coloured book cover can replace a notebook's first-page preview (sheet → Cover…).
 * P046: Select in the top bar lets taps tick several notebooks; a floating bar moves them to a folder or, after
 * asking, to the trash, one repository call at a time.
 * P050: folders nest (up to four deep). A folder shows its subfolders' notebooks too; the chip row becomes a breadcrumb.
 * P052: notebooks carry tags (sheet → Tags…), kept in the library preferences. Tag chips after the folders filter the
 * grid within the open folder, and a search starting with "#" matches tags. P055: the selection bar's Tag… tags many.
 */
@SuppressLint("ViewConstructor")
class LibraryScreen(
    context: Context,
    private val repository: NotebookRepository,
    private val thumbnails: PageThumbnails,
    private val onOpened: (String) -> Unit,
    private val onImport: () -> Unit,
    private val onClose: (() -> Unit)? = null,
    private val entriesSource: () -> List<LibraryEntry> = { repository.entries },
    private val loadCover: (String) -> Notebook = defaultCoverLoader(context),
    private val trashSource: () -> List<LibraryEntry> = { repository.trashed },
    private val loadTrashCover: (String) -> Notebook = defaultTrashCoverLoader(context),
    /** P088: opens the library backup sheet; the Backup button is shown only when this is given. */
    private val onBackup: (() -> Unit)? = null,
    /** P128 (F144): ids of the given notebooks whose saved handwriting or typed text contains a query; runs off the main thread. */
    private val textSearch: (List<LibraryEntry>, String, Notebook?) -> Set<String> = repository.libraryText::matching,
    /** P139 (F170): opens the sync sheet, and the sync state shown beside it in the menu (F180). */
    private val onSync: (() -> Unit)? = null,
    private val syncStatus: () -> String = { "" },
    private val onSourceRecovery: ((String) -> Boolean)? = null,
    private val storageActions: NotebookStorageActions = NotebookStorageActions(repository),
    private val onExportSelected: ((List<String>) -> Unit)? = null,
    private val onExportAll: (() -> Unit)? = null,
) : FrameLayout(context) {
    /** P568: the Library follows the system's dark mode, as Settings and its sheets already did. Covers and paper keep their colours. */
    private val night = context.night
    /** [folder]: a new notebook created while that folder was open is filed into it once it exists. */
    private data class Pending(val targetId: String?, val fromId: String?, val folder: String? = null, val cover: String? = null)

    private val main = Handler(Looper.getMainLooper())
    /** Cover reads run here while the screen is attached, never on NotesApplication.io, so they cannot delay saves. */
    private var loader: ExecutorService? = null
    /** First page (and its PDF asset) of each notebook; the current notebook always uses the live session. */
    private val covers = hashMapOf<String, Notebook>()
    private val coverErrors = hashMapOf<String, String>()
    private val loading = hashSetOf<String>()
    private var releasedCoverIds = emptySet<String>()
    private val tiles = linkedMapOf<String, Tile>()
    private var shownEntries: List<LibraryEntry>? = null
    private var pending: Pending? = null
    private var storagePanel: NotebookStoragePanel? = null
    private data class StorageRequest(val id: String, val query: String, val folder: String?, val tag: String?)
    private var storageRequest: StorageRequest? = null
    private class StorageBatch(val ids: List<String>, val release: Boolean = false,
        val sources: Map<String, String> = emptyMap(), val expected: Map<String, LibraryEntry> = emptyMap(), var ready: Int = 0)
    private var storageBatch: StorageBatch? = null
    val downloadingSelected get() = storageBatch?.release == false
    val freeingSelected get() = storageBatch?.release == true
    private var selectedStorageSheet: Kit.Sheet? = null
    val storageDialog get() = selectedStorageSheet?.dialog ?: storagePanel?.dialog
    private var attached = false
    /** Bumped whenever the grid switches between library and trash, so late cover reads of the other grid are dropped. */
    private var generation = 0
    /** True while the grid lists the trash instead of the library. */
    var showingTrash = false; private set
    /** The open "Move to trash" confirmation, if any (for tests). */
    var trashDialog: AlertDialog? = null; private set
    var emptyTrashSheet:Kit.Sheet?=null;private set
    /** B17 (P136): the open notebook options, a popover beside the notebook, if any (for tests). */
    var actionsMenu: Popover? = null; private set
    /** B16 (P136): the open library menu (the hamburger), if any. */
    var libraryMenu: Popover? = null; private set
    /** B18 (P136): the open folder options, a popover beside the folder, if any. */
    var folderMenu: Popover? = null; private set
    /** F178: the open list of a notebook's earlier versions, if any (for tests). */
    var versionsDialog: AlertDialog? = null; private set
    /** The open rename dialog, if any (for tests). */
    var renameDialog: AlertDialog? = null; private set
    private var shownIdentities: List<LibraryEntry>? = null
    private val preferences = context.getSharedPreferences((context.applicationContext as? NotesApplication)?.preferencesName ?: "library", 0)
    /** Covers are ordered by most recent edit instead of by name (P030); remembered across launches. */
    var sortByRecent = preferences.getBoolean(SORT_KEY, false); private set
    var modifiedFilter = NotebookModifiedFilter.entries.firstOrNull { it.name==preferences.getString(DATE_FILTER_KEY,null) } ?: NotebookModifiedFilter.ALL; private set
    internal var dateMenu:Popover?=null; private set
    /** Ids of pinned notebooks (P032), remembered in the library preferences; the trash grid ignores them. */
    private val archived = (preferences.getStringSet(ARCHIVED_KEY, null) ?: emptySet()).toMutableSet()
    internal var showingArchived = false; private set
    private val pinned = (preferences.getStringSet(PINNED_KEY, null) ?: emptySet()).toMutableSet()
    /** Folder names (P044), alphabetical, and the folder each filed notebook id belongs to. */
    private val folderNames = (preferences.getStringSet(FOLDERS_KEY, null) ?: emptySet()).flatMap { ancestry(it) }.distinct().sortedWith(PATH_ORDER).toMutableList()
    private val filed = (preferences.getStringSet(FOLDER_OF_KEY, null) ?: emptySet()).mapNotNull { line ->
        line.split('\n', limit = 2).takeIf { it.size == 2 && it[1] in folderNames }?.let { it[0] to it[1] }
    }.toMap(hashMapOf())
    /** Coloured cover chosen per notebook id (P045); absent means the first-page preview. */
    private val coverStyles = (preferences.getStringSet(COVERS_KEY, null) ?: emptySet()).mapNotNull { line ->
        line.split('\n', limit = 2).takeIf { it.size == 2 && it[1] in COVER_DESIGNS }?.let { it[0] to it[1] }
    }.toMap(hashMapOf())
    /** The open cover chooser, if any (for tests). */
    var coverDialog: AlertDialog? = null; private set
    /** The folder whose notebooks are shown, or null for All. Not saved across launches; MainActivity restores it when returning from a notebook (B50). */
    var openFolder: String? = null; private set
    /** The open folder chooser, new/rename folder dialog or folder sheet, if any (for tests). */
    var folderDialog: AlertDialog? = null; private set
    /** P534: where notebooks move to, by folder tile. */
    internal var folderChooser: PictureChooser? = null; private set
    /** B18 (P136): a colour (a cover design key) chosen per folder path; absent means the default kraft. */
    private val folderColors = (preferences.getStringSet(FOLDER_COLORS_KEY, null) ?: emptySet()).mapNotNull { line ->
        line.split('\n', limit = 2).takeIf { it.size == 2 && it[1] in COVER_DESIGNS && it[0] in folderNames }?.let { it[0] to it[1] }
    }.toMap(hashMapOf())
    private val folderTiles = linkedMapOf<String, FolderTile>()
    private var shownFolderKey: List<String>? = null
    private val folderRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(24f), dpi(8f), dpi(24f), dpi(2f))
    }
    private val folderStrip = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(folderRow) }
    private val folderChips = linkedMapOf<String?, TextView>()
    /** Tags of each notebook id (P052), alphabetical; a tag exists while some notebook carries it. */
    private val tagged = hashMapOf<String, MutableList<String>>().also { map ->
        (preferences.getStringSet(TAGS_KEY, null) ?: emptySet()).forEach { line ->
            val parts = line.split('\n', limit = 2)
            if (parts.size == 2 && tagName(parts[1]) == parts[1]) {
                val list = map.getOrPut(parts[0]) { mutableListOf() }
                if (list.none { it.equals(parts[1], ignoreCase = true) }) list += parts[1]
            }
        }
        map.values.forEach { it.sortWith(String.CASE_INSENSITIVE_ORDER) }
    }
    /** The tag whose notebooks are shown (within the open folder), or null. Not remembered. */
    var openTag: String? = null; private set
    private val tagChips = linkedMapOf<String, TextView>()
    /** The open tag chooser, tag sheet or tag rename/remove dialog, if any (for tests). */
    var tagsDialog: AlertDialog? = null; private set
    /** True while taps tick notebooks instead of opening them (P046). */
    var selecting = false; private set
    private val selection = linkedSetOf<String>()
    /** Ticked notebook ids, in the order they were ticked. */
    val selectedIds: List<String> get() = selection.toList()
    /** The open "Move N notebooks to the trash" confirmation, if any (for tests). */
    var selectDialog: AlertDialog? = null; private set
    /** Notebooks still to move to the trash; the repository takes one at a time. */
    private val trashQueue = ArrayDeque<String>()
    private var batchTotal = 0
    private var batchMoved = 0
    private var batchInFlight = false
    private var batchFailure: String? = null
    /** A calm outcome of a multi-notebook action, shown instead of the repository's single-notebook message. */
    private var note: String? = null
    private var noteFault = false
    /** P457: a notebook tapped while the open book was still saving; opened as soon as that save ends. */
    private var openAfterSave: String? = null
    private val selectButton = ChromeButton(context, "Select notebooks", null, "Select")
    private val selectAllButton = ChromeButton(context, "Select all shown", null, "Select all")
    private val folderSelectedButton = ChromeButton(context, "Move selected to a folder", null, "Move to folder…")
    private val tagSelectedButton = ChromeButton(context, "Tag selected notebooks", null, "Tag…")
    private val selectionTrashButton = ChromeButton(context, "Move selected to the trash", Glyph.DELETE, "Trash…")
    private val selectionStorageButton = Kit.button(context, "Storage…", Kit.Button.TINTED, small = true) { showSelectedStorage() }
    internal val selectionExportButton = Kit.button(context, "Export PDFs…", Kit.Button.TINTED, small = true) { exportSelectedPdfs() }
    private val doneButton = ChromeButton(context, "Done selecting", Glyph.CLOSE, "Done")
    private val selectionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(4f)); visibility = GONE
    }
    /**
     * Preserve each action's natural touch target when a window cannot fit the whole row. A scroll view with a child takes
     * every touch in its bounds, so taps between the actions never reach the covers under the bar. P637: neither the bar
     * nor this is marked clickable any more, which made a screen reader stop on them as buttons with no name.
     */
    internal val selectionViewport = HorizontalScrollView(context).apply {
        background = Glass.pane(context, night, Glass.PILL); elevation = dp(16f)
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        visibility = GONE
        addView(selectionBar, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val selectionHost = FrameLayout(context).apply {
        visibility = GONE
        addView(selectionViewport, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }
    /** The trimmed search text; covers whose title does not contain it are hidden. */
    var query = ""; private set
    /** P128 (F144): notebooks whose saved handwriting or typed text matches [textHitsFor], read in the background. */
    private var textHits: Set<String> = emptySet()
    private var textHitsFor: String? = null
    private var textSearcher: ExecutorService? = null
    /** The query whose handwriting and typed text search is still running, or null (for tests). */
    var searchingText: String? = null; private set
    private val search = EditText(context).apply {
        setHintTextColor(Glass.secondary(night)); setTextColor(Glass.text(night))
        textSize = 14f; typeface = Tokens.SANS; background = null; setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(dpi(36f), 0, dpi(36f), 0); contentDescription = "Search notebooks"
        // P600: the hint was clipped to "Search, including handw" (tablet) or "handv" (narrow phone); large text keeps "Search".
        if (context.largeText) hint = "Search" else fitHint("Search, including handwriting", "Search all writing", "Search")
    }
    private val clearSearch = SearchGlyph(context, clear = true).apply { contentDescription = "Clear search"; visibility = GONE }
    private val nameSort = sortChip("Name")
    private val recentSort = sortChip("Recent")
    private val dateChip = sortChip("All dates")

    // P188: the Glass board's Library. Import is a glass pill, New notebook the one accent-filled pill.
    private val importButton = PillButton(context, "Import notebook", "Import", PillIcon.IMPORT, filled = false)
    private val newButton = PillButton(context, "New notebook", "New notebook", PillIcon.PLUS, filled = true)
    /** Shown in the header only while the trash is open, to leave it; the side panel's Trash row opens it. */
    private val trashButton = PillButton(context, "Show trash", "Back to library", PillIcon.BACK, filled = false)
    private val emptyTrashButton=Kit.button(context,"Empty Trash…",Kit.Button.DANGER,small=true) { confirmEmptyTrash() }.apply { visibility=GONE }
    private val backupButton = ChromeButton(context, "Back up library to a folder", null, "Backup")
    /** B16 (P136): the library's menu, where Back used to be; P188: a row at the foot of the side panel. */
    private val menuButton = NavRow(context, NavIcon.MORE, "More").apply { contentDescription = "Library menu" }
    /** P188: the side panel's rows: every notebook, each top-level folder, and the trash. */
    private val allRow = NavRow(context, NavIcon.ALL, "All notebooks")
    private val archiveRow = NavRow(context, NavIcon.FOLDER, "Archived")
    internal val archiveButton: View get() = archiveRow
    private val trashRow = NavRow(context, NavIcon.TRASH, "Trash")
    /** P567: Settings didn't need a notebook, yet could only be reached from inside one. */
    private val settingsRow = NavRow(context, NavIcon.SETTINGS, "Settings")
    internal val settingsButton: View get() = settingsRow
    private val gesturesRow = NavRow(context, NavIcon.GESTURES, "Gestures")
    internal val gesturesButton: View get() = gesturesRow
    /** P567: public builds only; null in the owner's app and on very short screens. */
    internal var updateCard: View? = null; private set
    private val folderList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val folderRows = linkedMapOf<String, NavRow>()
    private var folderRowsKey: List<String>? = null
    private val newFolderLink = TextView(context).apply {
        text = "New"; setTextColor(accentInk(night)); textSize = 14f; typeface = Tokens.SANS_SEMIBOLD; gravity = Gravity.CENTER
        setPadding(dpi(10f), 0, dpi(10f), 0); contentDescription = "New folder"; isClickable = true; isFocusable = true
        foreground = ripple(night, dp(12f))
    }
    private val title = TextView(context).apply {
        text = "All notebooks"; setTextColor(Glass.text(night)); textSize = 30f; typeface = Tokens.SANS_SEMIBOLD
        // P654: one real line, so a long folder or tag name ends in "…" (with maxLines 1 alone, a long word after a space vanished).
        // At large text two lines, but a word too wide for the line (a 60-letter folder name) puts it back on one line:
        // over two lines Android split that word and cut it with no "…".
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; keepWordsWhole(leastShare = NAME_SHARE)
        if (context.largeText) {
            var single: Boolean? = null
            addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
                val room = right - left - totalPaddingLeft - totalPaddingRight
                if (room <= 0) return@addOnLayoutChangeListener
                val wide = text.split(Regex("\\s+")).any { paint.measureText(it) > room }
                if (wide != single) { single = wide; v.post { isSingleLine = wide; if (!wide) maxLines = 2; ellipsize = TextUtils.TruncateAt.END } }
            }
        }
    }
    private val hint = TextView(context).apply {
        setTextColor(Glass.secondary(night)); textSize = 13f; typeface = Tokens.SANS
        setPadding(dpi(MAIN_PAD), dpi(14f), dpi(MAIN_PAD), 0); visibility = GONE
        text = "Tap a notebook to restore it to the library. Notebooks in the trash are kept until you restore them."
    }
    private val message = TextView(context).apply {
        setTextColor(faultInk(night)); textSize = 14f; typeface = Tokens.SANS_MEDIUM
        setPadding(dpi(MAIN_PAD), dpi(12f), dpi(MAIN_PAD), 0); visibility = GONE
    }
    private val empty = TextView(context).apply {
        setTextColor(Glass.secondary(night)); textSize = 15f; typeface = Tokens.SANS
        gravity = Gravity.CENTER; setPadding(dpi(24f), dpi(48f), dpi(24f), dpi(48f)); visibility = GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    /** P179 (G4): how many notebooks there are, and that they are kept on this device; P188: the header's subtitle. */
    private val storageLine = TextView(context).apply {
        setTextColor(Glass.secondary(night)); textSize = 14f; typeface = Tokens.SANS; maxLines = if (context.largeText) Int.MAX_VALUE else 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dpi(4f), 0, 0)
    }
    /** P188: "Continue writing": the most recently edited notebooks as wide cards above the grid. */
    private val recentSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = GONE }
    private val recentRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val recentCards = linkedMapOf<String, RecentCard>()
    private var recentKey: List<String>? = null
    private val notebooksLabel = sectionLabel("Notebooks")
    private val notebooksRow = LinearLayout(context)
    private val notebooksControls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val side = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = Glass.pane(context, night, SIDE_RADIUS); elevation = dp(8f)
        setPadding(dpi(16f), dpi(26f), dpi(16f), dpi(18f)); Kit.blockTouches(this)
    }
    private val mainColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** P577: the header: the heading row (panel button, title, count) and the pill buttons, side by side or stacked. */
    private val header = LinearLayout(context)
    private val headingRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val pills = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /** P577: on a narrow screen (a phone, or a narrow split) the side panel slides in over the Library from this button. */
    private val drawerButton = Kit.iconButton(context, "Folders, trash and settings", Glyph.MENU) { openDrawer() }.apply { visibility = GONE }
    private val scrim = View(context).apply {
        setBackgroundColor(if (night) 0x99000000.toInt() else 0x5c1d2433); visibility = GONE; isClickable = true
        contentDescription = "Close the side panel"; setOnClickListener { closeDrawer() }
    }
    /** True while the side panel is a slide-out panel (narrow screens); see [onMeasure]. */
    var compact = false; private set
    /** True while the slide-out side panel is open. */
    val drawerOpen get() = compact && side.visibility == VISIBLE
    private val drawerBack = object : androidx.activity.OnBackPressedCallback(false) { override fun handleOnBackPressed() = closeDrawer() }
    /** The storage line under the search field, or null when hidden (for tests). */
    val storageText: String? get() = if (storageLine.visibility == VISIBLE) storageLine.text.toString() else null
    private var shownFolderPaths = emptyList<String>()
    private var shownFolderDetails = emptyList<String>()
    private val catalogueHeader = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10f), 0, 0) }
    private val catalogueAdapter = CatalogueAdapter()
    private val catalogueLayout = GridLayoutManager(context, 1).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) spanCount else 1
        }
    }
    private val scroll = RecyclerView(context).apply {
        layoutManager = catalogueLayout; adapter = catalogueAdapter
        itemAnimator = null; clipToPadding = false; clipChildren = false
        setPadding(dpi(14f), 0, dpi(14f), dpi(96f))
        setItemViewCacheSize(2)
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position <= 0) return
                val columns = catalogueLayout.spanCount
                if (position <= columns) outRect.top = dpi(14f)
                val count = catalogueAdapter.itemCount - 1
                if ((position - 1) / columns == (count - 1) / columns) outRect.bottom = dpi(14f)
            }
        })
    }
    private val bindPass = Runnable { bindVisible() }
    private val observer: () -> Unit = { refresh() }
    // A busy veil avoids a separate GPU compositing layer for every disabled notebook tile.
    private val busyVeil = Paint().apply { color = (deskOf(night) and 0x00ffffff) or 0x73000000 }

    /** Number of notebook tiles shown, including unreadable ones. */
    val tileCount get() = shownEntries?.size ?: 0
    internal val showingLibraryLoading get() = !repository.loaded && empty.visibility == VISIBLE
    /** The tile for a notebook id, for tests and accessibility checks. */
    fun tileFor(id: String): View? = tiles[id]
    /** Scrolls the grid to a notebook so its tile is drawn (tests: a scope may hold more notebooks than fit). */
    internal fun reveal(id: String): Boolean {
        val index = shownIds.indexOf(id); if (index < 0) return false
        scroll.scrollToPosition(1 + shownFolders.size + index); return true
    }
    /** As [reveal], for a folder's tile. */
    internal fun revealFolder(name: String): Boolean {
        val index = shownFolders.indexOf(name); if (index < 0) return false
        scroll.scrollToPosition(1 + index); return true
    }
    /** Ids of the shown covers, in grid order. */
    val shownIds: List<String> get() = shownEntries.orEmpty().map { it.id }
    /** P188: ids of the "Continue writing" cards, most recently edited first; empty while the section is hidden. */
    val recentIds: List<String> get() = if (recentSection.visibility == VISIBLE) recentCards.keys.toList() else emptyList()
    /** B29: the cover key a "Continue writing" card draws, null for a first-page picture. */
    fun recentCoverStyle(id: String): String? = recentCards[id]?.cover?.style
    /** Whether a notebook's cover currently holds a rendered preview (covers far from the screen let go of theirs). */
    fun hasPreview(id: String): Boolean = tiles[id]?.cover?.hasPreview == true
    internal val pendingCoverReads get() = loading.size
    /** The text shown under the top bar, or null; failures and warnings appear here. */
    val messageText: String? get() = if (message.visibility == VISIBLE) message.text.toString() else null
    val newNotebookButton: View get() = newButton
    val importNotebookButton: View get() = importButton
    val trashToggleButton: View get() = trashButton
    val backupLibraryButton: View get() = backupButton
    val selectToggleButton: View get() = selectButton
    val selectAllShownButton: View get() = selectAllButton
    val moveSelectedButton: View get() = folderSelectedButton
    val trashSelectedButton: View get() = selectionTrashButton
    val tagSelectedButtonView: View get() = tagSelectedButton
    val doneSelectingButton: View get() = doneButton
    /** The floating bar of selection actions. */
    val selectionActions: View get() = selectionBar
    val searchField: EditText get() = search
    val sortNameButton: View get() = nameSort
    val sortRecentButton: View get() = recentSort
    val dateFilterButton: View get() = dateChip
    /** Folder names, alphabetical. */
    val folders: List<String> get() = folderNames.toList()
    /** The folder a notebook is filed in, or null. */
    fun folderOf(id: String): String? = filed[id]
    /** The breadcrumb chip for a folder on the way to the open one, null for the library's top level; null when not shown. */
    fun folderChip(name: String?): TextView? = folderChips[name]
    /** B18 (P136): the grid tile of a folder inside the open one (or at the top level), or null when not shown. */
    fun folderTile(path: String): View? = folderTiles[path]
    /** P608: a top-level folder's row in the side list, or null. */
    internal fun folderRowFor(path: String): View? = folderRows[path]
    /** Paths of the folder tiles shown, in grid order (they lead the grid). */
    val shownFolders: List<String> get() = shownFolderPaths
    /** A folder's colour (a cover design key), or null for the default. */
    fun folderColorOf(path: String): String? = folderColors[path]
    val menuToggleButton: View get() = menuButton
    /** B17 (P136): a notebook tile's ⋮ button, or null (trash, unreadable, not shown). */
    fun moreButtonFor(id: String): View? = tiles[id]?.more
    /** The open library menu, notebook options or folder options. */
    private val openMenu: Popover? get() = listOf(libraryMenu, actionsMenu, folderMenu, dateMenu).firstOrNull { it?.isShowing == true }
    /** The labels of the open menu's rows, in order (for tests); empty when none is open. */
    val menuLabels: List<String> get() = openMenu?.let { menu -> rowsOf(menu.content).map { labelOf(it) } } ?: emptyList()
    /** Taps the open menu's row with this label; false when there is none or it is disabled. */
    fun chooseMenu(label: String): Boolean = openMenu?.let { menu -> rowsOf(menu.content).firstOrNull { labelOf(it) == label } }
        ?.takeIf { it.isEnabled }?.performClick() == true
    /** P606: a tile reads as its full label, a row or pill as its text. */
    private fun labelOf(view: View) = (view as? MenuTile)?.label ?: (view as TextView).text.toString()
    private fun rowsOf(view: View): List<View> = when {
        view is MenuTile -> listOf(view)
        view is TextView && view.isClickable -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { rowsOf(view.getChildAt(it)) }
        else -> emptyList()
    }
    /** The chip for a tag, or null when no readable notebook carries it. */
    fun tagChip(tag: String): TextView? = tagChips[tag]
    /** A notebook's tags, alphabetical. */
    fun tagsOf(id: String): List<String> = tagged[id]?.toList() ?: emptyList()
    /** Every tag a readable library notebook carries, alphabetical (ignoring case). */
    val tags: List<String> get() {
        val readable = entriesSource().filter { it.error == null }.map { it.id }.toSet()
        return tagged.filterKeys { it in readable }.values.flatten().distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    /** The "New folder" chip at the end of the folder row. */
    /** The empty-state text when shown, or null. */
    val emptyText: String? get() = if (empty.visibility == VISIBLE) empty.text.toString() else null
    init {
        setBackgroundColor(deskOf(night))
        Kit.blockTouches(this) // nothing behind the library receives touches
        listOf(selectAllButton, folderSelectedButton, tagSelectedButton, selectionTrashButton, doneButton).forEach { it.glass = true; it.night = night }
        // B16 (P136): no Back button. The menu holds the library-wide actions (the system Back still returns to the
        // notebook). P188: it is the side panel's last row; New notebook and Import lead the header, and the header shows
        // Back to library only while the trash is open.
        menuButton.setOnClickListener { showLibraryMenu() }
        importButton.setOnClickListener { requestImport() }
        newButton.setOnClickListener { showNewNotebook() }
        trashButton.setOnClickListener { showTrash(!showingTrash) }
        selectButton.setOnClickListener { startSelecting() }
        onBackup?.let { backup -> backupButton.setOnClickListener { backup() } }
        // P188: the header: the place shown and its count on the left, the primary buttons on the right.
        // At large text the title takes the whole width and the buttons go under it.
        // P577: on a phone the buttons go under the title too, and a panel button before the title opens the side panel.
        header.setPadding(dpi(MAIN_PAD), dpi(30f), dpi(MAIN_PAD), dpi(6f))
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(title); heading.addView(storageLine)
        headingRow.addView(drawerButton, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { marginEnd = dpi(12f) })
        headingRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headingRow); listOf(trashButton, emptyTrashButton, importButton, newButton).forEach { pills.addView(it) }
        header.addView(pills); arrangeHeader(context.largeText)
        mainColumn.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(folderStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(message)
        mainColumn.addView(hint)
        scroll.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = bindVisible()
        })
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // B53 (P412): a span change requested during this layout pass was dropped, leaving one huge column after a
            // narrow first pass (Back from a notebook). Apply it after the pass, which lays the grid out again.
            scroll.post {
                // P595: with large text a tile is wider, so a name like "Notebook 1" fits beside its ⋮ without splitting.
                // P609: a narrow phone still gets two columns while a tile can be 136 dp wide, not one tile the width of the screen.
                val inner = scroll.width - scroll.paddingLeft - scroll.paddingRight
                val fewest = if (!context.largeText && inner >= dpi(272f)) 2 else 1
                val columns = max(fewest, inner / dpi(if (context.largeText) 220f else 150f))
                if (scroll.width > 0 && catalogueLayout.spanCount != columns) {
                    catalogueLayout.spanCount = columns; scroll.invalidateItemDecorations(); scroll.requestLayout()
                }
            }
            scheduleBind()
        }
        val content = catalogueHeader
        recentSection.addView(sectionLabel("Continue writing").apply { setPadding(dpi(MAIN_PAD), 0, dpi(MAIN_PAD), dpi(14f)) })
        recentRow.setPadding(dpi(MAIN_PAD), 0, dpi(MAIN_PAD), dpi(30f)); recentRow.clipToPadding = false
        recentSection.addView(recentRow); recentSection.clipChildren = false
        content.addView(recentSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(notebooksHeader())
        content.addView(empty)
        mainColumn.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The catalogue recycles its visible cells; the side panel floats over the desk.
        addView(mainColumn, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildSide()
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(side, LayoutParams(dpi(SIDE_WIDTH), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dpi(16f), dpi(16f), 0, dpi(16f)) })
        selectAllButton.setOnClickListener { selectAllShown() }
        folderSelectedButton.setOnClickListener { showMoveSelectedToFolder() }
        tagSelectedButton.setOnClickListener { showTagSelected() }
        selectionTrashButton.setOnClickListener { confirmTrashSelected() }
        doneButton.setOnClickListener { endSelecting() }
        listOf(selectAllButton, folderSelectedButton, tagSelectedButton, selectionTrashButton).forEach { selectionBar.addView(it) }
        selectionBar.addView(selectionExportButton)
        selectionBar.addView(selectionStorageButton)
        selectionBar.addView(doneButton)
        addView(selectionHost, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT)
            .apply { bottomMargin = dpi(24f); rightMargin = dpi(12f) })
        // The search field never takes focus by itself, so the keyboard only appears when it is tapped.
        // P568: no system focus veil, which otherwise tints the whole Library blue whenever the last input was not touch.
        isFocusableInTouchMode = true; descendantFocusability = FOCUS_BEFORE_DESCENDANTS; defaultFocusHighlightEnabled = false
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyQuery(s?.toString().orEmpty()) }
        })
        search.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); closeDrawer(); true } else false }
        clearSearch.setOnClickListener { setQuery(""); hideKeyboard() }
        nameSort.setOnClickListener { setSortByRecent(false) }
        recentSort.setOnClickListener { setSortByRecent(true) }
        dateChip.setOnClickListener {
            dismissMenus()
            dateMenu=sideMenu(dateChip,"Modified date",NotebookModifiedFilter.entries.map { filter -> filter.label to { setModifiedFilter(filter) } }) { if(dateMenu===it)dateMenu=null }
        }
        showSort(); buildFolderRow()
    }

    /**
     * B18 (P136): folders live in the grid, so this row is only the way back out of an open folder (Library › Science ›
     * Physics) and the tag chips. Counts and selection are filled in by [showFolders].
     */
    private fun buildFolderRow() {
        folderRow.removeAllViews(); folderChips.clear()
        val open = openFolder
        if (open != null) (listOf<String?>(null) + ancestry(open)).forEachIndexed { i, name ->
            if (i > 0) folderRow.addView(TextView(context).apply {
                text = "›"; textSize = 16f; setTextColor(Glass.secondary(night)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
            val chip = rowChip()
            chip.setOnClickListener { showFolder(name) }
            if (name != null) chip.setOnLongClickListener { showFolderActions(name, chip) }
            folderChips[name] = chip
            folderRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        }
        // Tags follow the way back after a thin rule.
        tagChips.clear()
        val tagList = tags
        if (tagList.isNotEmpty() && open != null) {
            folderRow.addView(View(context).apply { setBackgroundColor(if (night) Glass.RULE_NIGHT else Glass.RULE); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { marginStart = dpi(8f); marginEnd = dpi(16f) })
        }
        tagList.forEach { tag ->
            val chip = rowChip().apply {
                setOnClickListener { showTag(if (openTag == tag) null else tag) }
                setOnLongClickListener { showTagActions(tag) }
            }
            tagChips[tag] = chip
            folderRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        }
        showFolders()
    }

    /** P654: at least 36 dp tall, and taller when large text needs it (a fixed 36 dp cut the text at 200%). */
    private fun rowChip() = TextView(context).apply {
        textSize = 14f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        maxWidth = dpi(240f); setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(4f)); minWidth = dpi(64f); minHeight = dpi(36f); isClickable = true; isFocusable = true
        foreground = ripple(night, dp(18f))
    }

    /** Selection, counts and descriptions of the folder chips; the row is hidden in Trash, and when it has nothing to show. */
    private fun showFolders() {
        folderStrip.visibility = if (showingTrash || showingArchived || folderRow.childCount == 0) GONE else VISIBLE
        if (showingTrash || showingArchived) return
        if (tags != tagChips.keys.toList()) { buildFolderRow(); return }
        val readable = entriesSource().filter { it.error == null && it.id !in archived }
        // A tag counts its notebooks within the open folder, so the chip says what tapping it will show.
        tagChips.forEach { (tag, chip) ->
            val count = readable.count { entry -> carries(entry.id, tag) && openFolder.let { folder -> folder == null || filed[entry.id]?.let { isWithin(it, folder) } == true } }
            val on = tag == openTag
            chip.text = "#$tag  $count"
            chip.background = if (on) rounded(selectedFill(night), dp(18f)) else rounded(Kit.cardFill(night), dp(18f), if (night) Glass.RULE_NIGHT else Glass.RULE, dpi(1f))
            chip.setTextColor(if (on) accentInk(night) else Glass.text(night))
            chip.isSelected = on
            chip.contentDescription = "Tag $tag, $count" + if (on) ", selected" else ""
        }
        // A folder counts the notebooks in it and in its subfolders.
        val counts = hashMapOf<String, Int>()
        readable.forEach { entry -> filed[entry.id]?.let { folder -> ancestry(folder).forEach { counts[it] = (counts[it] ?: 0) + 1 } } }
        folderChips.forEach { (name, chip) ->
            val on = name == openFolder
            val count = if (name == null) readable.size else counts[name] ?: 0
            chip.text = name?.let { "${leafOf(it)}  $count" } ?: "Library"
            chip.background = if (on) rounded(selectedFill(night), dp(18f)) else rounded(Kit.cardFill(night), dp(18f), if (night) Glass.RULE_NIGHT else Glass.RULE, dpi(1f))
            chip.setTextColor(if (on) accentInk(night) else Glass.secondary(night))
            chip.isSelected = on
            chip.contentDescription = (if (name == null) "All notebooks" else "Folder ${shownPath(name)}") + ", $count" + if (on) ", selected" else ""
        }
    }

    /**
     * P188: the side panel on the Glass board: the library's name, the search field, All notebooks, the top-level folders
     * (with New), and at its foot the trash and the library menu.
     */
    private fun buildSide() {
        side.addView(TextView(context).apply {
            text = "Library"; setTextColor(Glass.text(night)); textSize = 26f; typeface = Tokens.SANS_SEMIBOLD
            setPadding(dpi(8f), 0, 0, dpi(16f)); isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val field = FrameLayout(context).apply { background = rounded(wellOf(night), dp(12f)) }
        field.addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field.addView(SearchGlyph(context, clear = false).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(dpi(36f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        field.addView(clearSearch, FrameLayout.LayoutParams(dpi(40f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        side.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)).apply { bottomMargin = dpi(18f) })
        allRow.setOnClickListener { closeDrawer(); showTrash(false); showTag(null); showFolder(null) }
        side.addView(allRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        val foldersHead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), dpi(18f), 0, dpi(4f))
        }
        foldersHead.addView(TextView(context).apply {
            text = "FOLDERS"; setTextColor(Glass.secondary(night)); textSize = 12f; typeface = Tokens.SANS_SEMIBOLD; letterSpacing = .06f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foldersHead.addView(newFolderLink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, if (context.largeText) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(32f)))
        newFolderLink.setOnClickListener { showNewFolder(parent = null) }
        side.addView(foldersHead)
        // The folder list scrolls on its own when there are many; it comes after the main column, so the grid's scroll
        // stays the screen's first.
        side.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(folderList) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(Glass.rule(context, night), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)).apply {
            topMargin = dpi(8f); bottomMargin = dpi(8f); marginStart = dpi(8f); marginEnd = dpi(8f)
        })
        archiveRow.setOnClickListener { closeDrawer(); showArchived(true) }
        side.addView(archiveRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        trashRow.setOnClickListener { closeDrawer(); showTrash(true) }
        side.addView(trashRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        gesturesRow.setOnClickListener { closeDrawer(); (context as? MainActivity)?.let { GestureGuide.show(it) } }
        side.addView(gesturesRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        settingsRow.setOnClickListener { closeDrawer(); (context as? MainActivity)?.frame?.openSettings() }
        side.addView(settingsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        side.addView(menuButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42f)))
        // P567: the rows above must stay reachable, so a very short screen leaves the card to the More menu.
        if (!context.personalTools && resources.displayMetrics.run { heightPixels / density } >= 602f) {
            updateCard = updateCard()
            side.addView(updateCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        }
    }

    /** P567: the version and the way to look for a newer one, where people see them without searching. Still asks only when tapped. */
    private fun updateCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(wellOf(night), dp(14f)); dpi(10f).let { setPadding(it, it, it, it) }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        addView(Kit.text(context, "${context.getString(R.string.app_name)} ${UpdateCheck.display(version)}", Kit.Type.CAPTION, secondary = true).apply {
            setPadding(dpi(4f), 0, dpi(4f), dpi(8f)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(Kit.button(context, "Check for updates", Kit.Button.PRIMARY, small = true) { UpdateCheck.show(context) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** P188: the side panel's rows: which place is open, the counts, and a row for each top-level folder. */
    private fun showSide(idle: Boolean) {
        val live = entriesSource().filter { it.error == null && it.id !in archived }
        val counts = hashMapOf<String, Int>()
        live.forEach { entry -> filed[entry.id]?.let { folder -> ancestry(folder).forEach { counts[it] = (counts[it] ?: 0) + 1 } } }
        val top = folderNames.filter { parentOf(it) == null }
        val key = top.map { "$it\n${folderColors[it]}" }
        if (key != folderRowsKey) {
            folderRowsKey = key; folderList.removeAllViews(); folderRows.clear()
            top.forEach { path ->
                val row = NavRow(context, NavIcon.FOLDER, leafOf(path)).apply {
                    iconColor = COVER_DESIGNS[folderColors[path] ?: DEFAULT_FOLDER]!!.color
                    setOnClickListener { closeDrawer(); showTrash(false); showFolder(path) }
                    setOnLongClickListener { !selecting && !showingTrash && showFolderActions(path, this) }
                }
                folderRows[path] = row
                folderList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
            }
        }
        // All and the folders only change what is shown, so they stay enabled while selecting (the ticks are kept).
        val calm = !selecting && batchTotal == 0
        allRow.show(live.size, !showingTrash && !showingArchived && openFolder == null && openTag == null, "All notebooks")
        folderRows.forEach { (path, row) ->
            row.show(counts[path] ?: 0, !showingTrash && !showingArchived && openFolder?.let { isWithin(it, path) } == true, "Folder ${shownPath(path)}")
        }
        archiveRow.show(entriesSource().count { it.error == null && it.id in archived }, showingArchived, "Archived")
        archiveRow.isEnabled = calm && idle
        trashRow.show(null, showingTrash, "Trash")
        // Opening the trash would drop a selection or a running move, as the menu's Trash row is disabled then.
        trashRow.isEnabled = calm && (idle || showingTrash)
        settingsRow.isEnabled = calm
        gesturesRow.isEnabled = calm
        newFolderLink.isEnabled = calm && !showingTrash && !showingArchived && folderNames.size < MAX_FOLDERS
        newFolderLink.alpha = if (newFolderLink.isEnabled) 1f else .45f
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text; setTextColor(Glass.text(night)); textSize = 20f; typeface = Tokens.SANS_SEMIBOLD; maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; isFocusable = false
    }

    /** "Notebooks" with the Name / Recent switch at its right, over the grid. */
    private fun notebooksHeader(): View {
        val stacked = context.largeText
        val row = notebooksRow.apply { setPadding(dpi(MAIN_PAD), 0, dpi(MAIN_PAD), 0) }
        row.addView(notebooksLabel)
        val sort = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(thinOf(night), dp(20f))
            setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f)); contentDescription = "Sort notebooks"
        }
        nameSort.accessibilityDelegate = OptionInGroup; recentSort.accessibilityDelegate = OptionInGroup
        sort.addView(nameSort, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sort.addView(recentSort, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tall = if (stacked) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(38f)
        val controls = notebooksControls
        controls.addView(sort, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, tall))
        controls.addView(dateChip,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,tall).apply { marginStart=dpi(8f) })
        row.addView(controls); arrangeNotebooksHeader(stacked)
        return row
    }

    /**
     * P188: "Continue writing": the most recently edited readable notebooks, as many as fit beside each other (one to
     * three), shown on the library's top level when nothing filters it. B28: shown however few notebooks there are, so
     * landscape, where three fit, keeps it with three notebooks.
     */
    /** Opening an unrelated saved book is safe while Trash moves its own reserved target.
     * Keep bound cells, refreshed cells and recent cards on the same existing repository guards.
     */
    private fun canTapNotebook(entry: LibraryEntry): Boolean =
        pending == null && storageRequest == null && storageBatch == null && batchTotal == 0 &&
            (repository.canChangeNotebook || (!showingTrash && (repository.canOpenSavedNotebook(entry.id) || repository.onlySaving))) &&
            (showingTrash || entry.error == null)

    private fun showRecent(readable: List<LibraryEntry>) {
        val room = width - sideSpace() - 2 * dpi(MAIN_PAD)
        val fits = ((room + dpi(16f)) / dpi(if (context.largeText) 420f else 256f)).coerceIn(1, 3)
        val wanted = !showingTrash && !showingArchived && !selecting && openFolder == null && openTag == null && query.isEmpty() && modifiedFilter==NotebookModifiedFilter.ALL && width > 0
        val recent = if (wanted) readable.filter { it.modified > 0L }.sortedByDescending { it.modified }.take(fits) else emptyList()
        recentSection.visibility = if (recent.isEmpty()) GONE else VISIBLE
        val key = recent.map { "${it.identity}\n${it.modified}\n${filed[it.id]}\n${coverStyles[it.id]}" }
        if (key != recentKey) {
            recentKey = key
            recentCards.values.forEach { it.cover.cancel() }; recentCards.clear(); recentRow.removeAllViews()
            val now = System.currentTimeMillis()
            recent.forEachIndexed { i, entry ->
                val card = RecentCard(context, entry, now)
                card.setOnClickListener { open(entry.id) }
                recentCards[entry.id] = card
                recentRow.addView(card, LinearLayout.LayoutParams(0, if (context.largeText) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(172f), 1f).apply { if (i > 0) marginStart = dpi(16f) })
            }
            // Fewer notebooks than room: the cards keep their width.
            if (recent.isNotEmpty()) repeat(fits - recent.size) { recentRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dpi(16f) }) }
        }
        recentCards.values.forEach { it.isEnabled = canTapNotebook(it.entry) }
    }

    /** Room the side panel takes at the left, with the gap after it. */
    private fun sideSpace() = if (compact) 0 else (side.layoutParams?.width ?: dpi(SIDE_WIDTH)) + dpi(24f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // P577: below 600 dp the side panel would squeeze the notebooks, so it becomes a slide-out panel.
        val narrow = width in 1 until dpi(COMPACT_WIDTH)
        if (narrow != compact) setCompact(narrow)
        // A wider side panel on a wide screen; the main column and the selection bar start after it.
        val sideWidth = if (compact) min(dpi(SIDE_WIDTH_WIDE), width - dpi(72f))
            else dpi(if (width >= dpi(1000f)) SIDE_WIDTH_WIDE else SIDE_WIDTH)
        (side.layoutParams as LayoutParams).width = sideWidth
        (mainColumn.layoutParams as LayoutParams).leftMargin = if (compact) 0 else sideWidth + dpi(24f)
        // Keep the usual main-column alignment, but reserve a usable scroll viewport in narrow windows.
        (selectionHost.layoutParams as LayoutParams).leftMargin =
            if (compact) dpi(12f) else min(sideWidth + dpi(24f), max(dpi(12f), width - dpi(360f)))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** P577: the sort switch beside "Notebooks", or under it (large text, narrow screens, where it would cut the word short). */
    private fun arrangeNotebooksHeader(stacked: Boolean) {
        notebooksRow.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        notebooksRow.gravity = if (stacked) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
        notebooksLabel.layoutParams = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) }
        notebooksControls.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { if (stacked) topMargin = dpi(8f) }
    }

    /** P577: the header's buttons beside the title, or under it (large text, narrow screens). */
    private fun arrangeHeader(stacked: Boolean) {
        header.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        header.gravity = if (stacked) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
        headingRow.layoutParams = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) }
        pills.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (stacked) topMargin = dpi(10f) }
        for (i in 0 until pills.childCount) pills.getChildAt(i).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(46f))
            .apply { if (stacked) marginEnd = dpi(10f) else marginStart = dpi(10f) }
    }

    private fun setCompact(narrow: Boolean) {
        compact = narrow
        drawerButton.visibility = if (narrow) VISIBLE else GONE
        arrangeHeader(narrow || context.largeText); arrangeNotebooksHeader(narrow || context.largeText)
        // Over the notebooks the panel must be solid, or their titles show through its rows.
        side.background = if (narrow) rounded(Kit.sheet(night), dp(SIDE_RADIUS)) else Glass.pane(context, night, SIDE_RADIUS)
        side.animate().cancel(); scrim.animate().cancel()
        side.translationX = 0f; scrim.visibility = GONE; drawerBack.isEnabled = false
        side.visibility = if (narrow) GONE else VISIBLE
    }

    /** P577: slides the side panel in over the Library (narrow screens only). */
    fun openDrawer() {
        if (!compact || drawerOpen) return
        dismissMenus(); hideKeyboard()
        side.visibility = VISIBLE; scrim.visibility = VISIBLE
        val from = -(side.layoutParams.width + dpi(16f)).toFloat()
        val quick = !Motion.enabled
        side.translationX = if (quick) 0f else from; scrim.alpha = if (quick) 1f else 0f
        if (!quick) { side.animate().translationX(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.easeOut).start(); scrim.animate().alpha(1f).setDuration(Motion.MEDIUM).start() }
        drawerBack.isEnabled = true
        side.post { allRow.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    /** P577: puts the slide-out side panel away; nothing happens on a wide screen, where it always shows. */
    fun closeDrawer() {
        if (!drawerOpen) return
        drawerBack.isEnabled = false
        val done = { if (compact) { side.visibility = GONE; scrim.visibility = GONE }; side.translationX = 0f }
        if (!Motion.enabled) { done(); return }
        side.animate().translationX(-(side.width + dpi(16f)).toFloat()).setDuration(Motion.SHORT).withEndAction(done).start()
        scrim.animate().alpha(0f).setDuration(Motion.SHORT).start()
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // How many recent cards fit depends on the width.
        if (w != oldw) main.post { refresh() }
    }

    private fun sortChip(label: String) = TextView(context).apply {
        text = label; textSize = 14f; typeface = Tokens.SANS_MEDIUM; gravity = Gravity.CENTER
        setPadding(dpi(16f), if (context.largeText) dpi(6f) else 0, dpi(16f), if (context.largeText) dpi(6f) else 0); minWidth = dpi(72f); isClickable = true; isFocusable = true
        foreground = ripple(night, dp(20f))
    }

    fun setModifiedFilter(filter:NotebookModifiedFilter) {
        if(filter==modifiedFilter)return
        modifiedFilter=filter;preferences.edit().putString(DATE_FILTER_KEY,filter.name).apply()
        showSort();refresh()
    }
    private fun showSort() {
        dateChip.text=modifiedFilter.chip
        dateChip.contentDescription="Modified date filter: ${modifiedFilter.label}"
        dateChip.isSelected=modifiedFilter!=NotebookModifiedFilter.ALL
        dateChip.background=rounded(thinOf(night),dp(20f))
        dateChip.setTextColor(if(dateChip.isSelected)accentInk(night) else Glass.secondary(night))

        listOf(nameSort to !sortByRecent, recentSort to sortByRecent).forEach { (chip, on) ->
            chip.background = if (on) rounded(Kit.chip(night), dp(20f)) else null; chip.elevation = if (on) dp(1f) else 0f
            chip.setTextColor(if (on) (if (night) Glass.TEXT_NIGHT else Glass.ACCENT) else Glass.secondary(night))
            chip.isSelected = on
            chip.contentDescription = "Sort by ${chip.text.toString().lowercase()}" + if (on) ", selected" else ""
        }
    }

    /**
     * B16 (P136): the hamburger menu: back to the open notebook, new folder, select, import, backup and the trash.
     * P606: drawn as the design canvas's tile grid, grouped Notebooks / Keep safe / App; the way back to an open notebook
     * is a pill at the top. In the trash it holds Empty Trash and the way back.
     */
    fun showLibraryMenu(): Boolean {
        if (openMenu != null || !noDialogOpen()) return false
        val busy = selecting || batchTotal > 0
        val activity = context as? MainActivity
        val ink = MenuIcons.INSERT; val safe = MenuIcons.EXPORT; val app = MenuIcons.NOTEBOOK
        val sections = if (showingTrash) listOf("Trash" to listOf(
            MenuChoice("Empty Trash…", "Empty Trash", MenuIcons.TRASH, Tokens.FAULT, !busy && repository.canChangeNotebook && trashSource().isNotEmpty(), fault = true) { confirmEmptyTrash() },
            MenuChoice("Back to the library", "Library", MenuIcons.BOOK, app, !busy && trashButton.isEnabled) { showTrash(false) },
        )) else listOf(
            "Notebooks" to listOfNotNull(
                MenuChoice("New folder…", "New folder", MenuIcons.NEW_FOLDER, ink, !busy && !showingArchived && folderNames.size < MAX_FOLDERS) { showNewFolder() },
                MenuChoice("Select notebooks", "Select", MenuIcons.SELECT, ink, !busy && selectButton.isEnabled) { startSelecting() },
                MenuChoice("Import notebook…", "Import", MenuIcons.IMPORT, ink, !busy && !showingArchived && importButton.isEnabled) { requestImport() },
                onExportAll?.let { export -> MenuChoice("Export all notebooks…", "Export all", MenuIcons.SHARE_OUT, ink, !busy && repository.canChangeNotebook && entriesSource().isNotEmpty()) { export() } },
            ),
            "Keep safe" to listOfNotNull(
                onBackup?.let { backup -> MenuChoice("Back up library…", "Back up", MenuIcons.BACKUP, safe, !busy) { backup() } },
                onSync?.let { sync -> MenuChoice("Sync library… (${syncStatus()})", "Sync", MenuIcons.SYNC, safe, !busy) { sync() } },
                activity?.let { MenuChoice("App lock…", "App lock", MenuIcons.LOCK, safe, !busy) { it.appLockSettings() } },
                activity?.let { MenuChoice("Encrypted backups…", "Encrypted backups", MenuIcons.SHIELD, safe, !busy && repository.canChangeNotebook) { it.encryptedBackups() } },
            ),
            "App" to listOfNotNull(
                activity?.let { MenuChoice("Gestures", "Gestures", MenuIcons.HAND, app, !busy) { GestureGuide.show(it) } },
                activity?.let { MenuChoice("Settings", "Settings", MenuIcons.SLIDERS, app, !busy) { it.frame.openSettings() } },
                if (!context.personalTools) MenuChoice("Check for updates…", "Updates", MenuIcons.OFFLINE, app, true) { UpdateCheck.show(context) } else null,
                MenuChoice("Trash", "Trash", MenuIcons.TRASH, app, !busy && trashButton.isEnabled) { showTrash(true) },
            ),
        )
        val back = if (busy || showingTrash) null else onClose?.let { close -> Triple("Back to the notebook", MenuIcons.BOOK, close) }
        libraryMenu = tileMenu(menuButton, sections.filter { it.second.isNotEmpty() }, beside = false, pill = back) { if (libraryMenu === it) libraryMenu = null }
        return true
    }

    /** Filters covers by title (case-insensitive, trimmed), or by tag prefix after "#"; "" shows every notebook. */
    fun setQuery(text: String) { if (search.text.toString() != text) search.setText(text) else applyQuery(text) }
    private fun applyQuery(text: String) {
        clearSearch.visibility = if (text.isEmpty()) GONE else VISIBLE
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed; searchText(); refresh()
    }
    /** P128 (F144): looks for [query] in every notebook's saved handwriting and typed text, then shows those notebooks too. */
    private fun searchText() {
        val wanted = query
        if (wanted.isEmpty() || wanted.startsWith("#") || showingTrash) { textHits = emptySet(); textHitsFor = null; searchingText = null; return }
        val executor = textSearcher ?: return
        val entries = entriesSource(); val open = repository.session?.notebook
        searchingText = wanted
        executor.execute {
            val hits = runCatching { textSearch(entries, wanted, open) }.getOrDefault(emptySet())
            main.post {
                if (!attached || searchingText != wanted) return@post
                textHits = hits; textHitsFor = wanted; searchingText = null
                if (query == wanted) refresh()
            }
        }
    }
    /** P567: what the search found inside notebook [id], when its name alone wouldn't have found it. */
    internal fun foundInside(id: String): String? = query.takeIf { wanted ->
        wanted.isNotEmpty() && textHitsFor == wanted && id in textHits && entriesSource().none { it.id == id && it.title.contains(wanted, ignoreCase = true) }
    }
    /** Orders covers by most recent edit (true) or by name (false); the choice is remembered. */
    fun setSortByRecent(recent: Boolean) {
        if (recent == sortByRecent) return
        sortByRecent = recent; preferences.edit().putBoolean(SORT_KEY, recent).apply()
        showSort(); refresh()
    }
    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus(); requestFocus()
    }
    /** Folder tiles show: the library (not the trash) without a search or a tag filter. */
    private val browsingFolders get() = !showingTrash && !showingArchived && query.isEmpty() && openTag == null && modifiedFilter==NotebookModifiedFilter.ALL
    private fun shown(all: List<LibraryEntry>): List<LibraryEntry> {
        val folder = openFolder.takeIf { !showingTrash }
        // B18 (P136): while browsing, a folder (or the top level) shows only the notebooks loose in it; its subfolders
        // are tiles of their own. A search or a tag looks through every folder inside it.
        val inFolder = when {
            showingTrash -> all
            browsingFolders -> all.filter { filed[it.id] == folder }
            folder == null -> all
            else -> all.filter { entry -> filed[entry.id]?.let { isWithin(it, folder) } == true }
        }
        val tag = openTag.takeIf { !showingTrash }
        val withTag = if (tag == null) inFolder else inFolder.filter { carries(it.id, tag) }
        val matching = when {
            query.isEmpty() -> withTag
            // "#phys" finds notebooks with a tag starting "phys"; a lone "#" finds every tagged notebook.
            query.startsWith("#") -> query.drop(1).trim().let { start -> withTag.filter { entry -> tagged[entry.id]?.any { it.startsWith(start, ignoreCase = true) } == true } }
            else -> withTag.filter { it.title.contains(query, ignoreCase = true) || (!showingTrash && textHitsFor == query && it.id in textHits) }
        }
        val now=System.currentTimeMillis()
        val dated=if(showingTrash)matching else matching.filter { modifiedFilter.includes(it.modified,now) }
        val sorted = if (sortByRecent) dated.sortedByDescending { it.modified } else dated
        // Pinned notebooks lead the library; the sort is stable, so each group keeps the chosen order.
        return if (showingTrash || pinned.isEmpty()) sorted else sorted.sortedBy { it.id !in pinned }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; requestFocus()
        // P577: Back closes the slide-out side panel first.
        (context as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(drawerBack)
        loader = Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "library-covers").apply { isDaemon = true }
        }
        textSearcher = Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "library-text").apply { isDaemon = true }
        }
        repository.listen(observer)
    }
    override fun onDetachedFromWindow() {
        lift?.let { settle(it, null, animate = false) }
        storagePanel?.dispose(); storagePanel = null; storageRequest = null; storageBatch = null
        selectedStorageSheet?.dismiss(); selectedStorageSheet = null
        emptyTrashSheet?.dismiss();emptyTrashSheet=null
        drawerBack.remove()
        repository.unlisten(observer); attached = false; pending = null; openAfterSave = null
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        trashDialog?.dismiss(); trashDialog = null; repository.dismissLibraryMessage()
        dismissMenus(); renameDialog?.dismiss(); renameDialog = null
        folderDialog?.dismiss(); folderDialog = null; folderChooser?.dismiss(); folderChooser = null; coverDialog?.dismiss(); coverDialog = null; tagsDialog?.dismiss(); tagsDialog = null; newNotebookSheet?.dismiss(); newNotebookSheet = null
        selectDialog?.dismiss(); selectDialog = null; selecting = false; selection.clear()
        trashQueue.clear(); batchTotal = 0; batchInFlight = false; note = null
        main.removeCallbacks(bindPass); tiles.values.forEach { it.cover.cancel() }; recentCards.values.forEach { it.cover.cancel() }
        loader?.shutdownNow(); loader = null; loading.clear()
        textSearcher?.shutdownNow(); textSearcher = null; searchingText = null
        super.onDetachedFromWindow()
    }

    /** Opens a notebook; returns false (and changes nothing) if it is unreadable, unknown or the repository is busy. */
    fun open(id: String): Boolean {
        val entry = entriesSource().firstOrNull { it.id == id } ?: return false
        if (entry.error != null || selecting || pending != null || storageBatch != null || batchTotal > 0) return false
        // P530: a notebook already open beside this one is reused; one writer per notebook.
        if (repository.heldByPeer(id)) { (context as MainActivity).focusCompanionPane(); onOpened(id); return true }
        if (!(repository.canChangeNotebook || repository.canOpenSavedNotebook(id))) {
            // P457 (B51): only the open book's save is finishing. Keep the tap; refresh() opens this book when it ends.
            if (!repository.onlySaving || !entry.availableOffline) return false
            openAfterSave = id; note = "Saving the open notebook, then opening “${entry.title}”…"; noteFault = false
            repository.flushSave(); refresh(); return true
        }
        if (!entry.availableOffline) return startStorageDownload(id, openAfter = true)
        openAfterSave = null; note = null
        val current = repository.session?.notebook?.id
        if (current == id) { onOpened(id); return true }
        if (!repository.openNotebook(id)) return false
        pending = Pending(id, current); refresh()
        return true
    }

    /** Creates "Notebook N" with the lowest free N and opens it; false while the repository is busy. */
    fun createNew(): Boolean {
        if (selecting || pending != null || storageBatch != null || batchTotal > 0 || !repository.canChangeNotebook) return false
        note = null
        val titles = entriesSource().map { it.title.trim().lowercase() }.toSet()
        val title = generateSequence(1) { it + 1 }.map { "Notebook $it" }.first { it.lowercase() !in titles }
        val current = repository.session?.notebook?.id
        if (!repository.createNotebook(title)) return false
        pending = Pending(null, current, openFolder); refresh()
        return true
    }

    /** Asks the activity to start its import picker; false while the repository is busy. */
    fun requestImport(): Boolean {
        if (pending != null || storageBatch != null || !repository.canChangeNotebook) return false
        onImport(); return true
    }

    /** Switches the grid between the library and the trash; the trash list is re-read when it is shown. */
    fun showTrash(show: Boolean) {
        if (show == showingTrash) return
        storagePanel?.dispose(); storagePanel = null; storageRequest = null; storageBatch = null
        selectedStorageSheet?.dismiss(); selectedStorageSheet = null
        showingTrash = show; showingArchived = false; generation++
        emptyTrashSheet?.dismiss();emptyTrashSheet=null
        if (search.text.isNotEmpty()) { query = ""; search.setText("") }
        tiles.values.forEach { it.cover.cancel() }
        covers.clear(); coverErrors.clear(); loading.clear(); shownEntries = null; shownIdentities = null
        trashDialog?.dismiss(); trashDialog = null
        dismissMenus(); renameDialog?.dismiss(); renameDialog = null
        folderDialog?.dismiss(); folderDialog = null; folderChooser?.dismiss(); folderChooser = null; coverDialog?.dismiss(); coverDialog = null; tagsDialog?.dismiss(); tagsDialog = null; newNotebookSheet?.dismiss(); newNotebookSheet = null
        selectDialog?.dismiss(); selectDialog = null; selecting = false; selection.clear(); note = null
        repository.dismissLibraryMessage()
        if (show) repository.refreshTrash()
        refresh()
    }

    /**
     * Back: ends selecting, returns from the trash or Archived, clears a search, leaves a tag, or goes up one folder;
     * false when none applies (so Back can close the library).
     */
    fun leaveTrash(): Boolean {
        if (storageBatch != null) { stopSelectedStorage(); return true }
        if (storagePanel?.isShowing == true) { storagePanel?.dismiss(); return true }
        if(emptyTrashSheet?.dialog?.isShowing==true) { emptyTrashSheet?.dismiss();return true }
        if (endSelecting()) return true
        if (showingArchived) { showArchived(false); return true }
        if (showingTrash) { showTrash(false); return true }
        // P653: Back in a folder left the app; it now steps out of a search, then a tag, then up one folder at a time.
        if (query.isNotEmpty()) { setQuery(""); return true }
        if (openTag != null && showTag(null)) return true
        openFolder?.let { if (showFolder(parentOf(it))) return true }
        return false
    }

    /** Starts ticking notebooks, with [first] ticked when given; false in Trash, while busy or for an unreadable notebook. */
    fun startSelecting(first: String? = null): Boolean {
        if (showingTrash || pending != null || storageRequest != null || storageBatch != null || batchTotal > 0) return false
        if (first != null && entriesSource().none { it.id == first && it.error == null }) return false
        selecting = true; selection.clear(); first?.let { selection += it }; note = null
        selectionViewport.scrollTo(0, 0)
        refresh(); return true
    }

    /** Leaves selecting without changing anything; false when not selecting. */
    fun endSelecting(): Boolean {
        if (!selecting) return false
        selectDialog?.dismiss(); selectDialog = null
        selectedStorageSheet?.dismiss(); selectedStorageSheet = null
        selecting = false; selection.clear(); refresh()
        return true
    }

    /** Ticks or unticks a readable library notebook while selecting; false otherwise. */
    fun toggleSelected(id: String): Boolean {
        if (!selecting || batchTotal > 0 || entriesSource().none { it.id == id && it.error == null }) return false
        if (!selection.remove(id)) selection += id
        refresh(); return true
    }

    /** Ticks every readable notebook shown (folder and search apply); when all are already ticked, unticks them. */
    fun selectAllShown(): Boolean {
        if (!selecting) return false
        val readable = shownEntries.orEmpty().filter { it.error == null }.map { it.id }
        if (readable.isNotEmpty() && selection.containsAll(readable)) selection.removeAll(readable.toSet()) else selection.addAll(readable)
        refresh(); return true
    }

    /** Files every ticked notebook in a folder, or unfiles them (null), and ends selecting; false when refused. */
    fun moveSelectedToFolder(folder: String?): Boolean {
        if (!selecting || showingTrash || selection.isEmpty() || (folder != null && folder !in folderNames)) return false
        val readable = entriesSource().filter { it.error == null }.map { it.id }.toSet()
        val ids = selection.filter { it in readable }
        if (ids.isEmpty()) return false
        ids.forEach { if (folder == null) filed -= it else filed[it] = folder }
        saveFolders()
        note = if (folder == null) "Took ${notebooks(ids.size)} out of folders" else "Moved ${notebooks(ids.size)} to “${leafOf(folder)}”"; noteFault = false
        selecting = false; selection.clear(); shownIdentities = null; refresh()
        return true
    }

    /**
     * Adds [add] to every ticked readable notebook and takes [remove] off them, then ends selecting with a note (P055).
     * Nothing changes (false) for an invalid name or when a notebook would carry more than [MAX_TAGS] tags.
     */
    fun tagSelected(add: Collection<String>, remove: Collection<String> = emptyList()): Boolean {
        if (!selecting || showingTrash || selection.isEmpty() || batchTotal > 0) return false
        val readable = entriesSource().filter { it.error == null }.map { it.id }.toSet()
        val ids = selection.filter { it in readable }
        if (ids.isEmpty()) return false
        val adding = add.map { tagName(it) ?: return false }
        val dropping = remove.map { tagName(it) ?: return false }
        // A tag already in the library keeps its spelling, as in setTags.
        val spelling = tagged.values.flatten().associateBy { it.lowercase() }
        val lists = ids.associateWith { id ->
            (tagsOf(id).filter { tag -> dropping.none { it.equals(tag, ignoreCase = true) } } + adding.map { spelling[it.lowercase()] ?: it })
                .distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (lists.values.any { it.size > MAX_TAGS }) return false
        lists.forEach { (id, list) -> if (list.isEmpty()) tagged -= id else tagged[id] = list.toMutableList() }
        saveTags()
        if (openTag != null && tags.none { it.equals(openTag, ignoreCase = true) }) openTag = null
        buildFolderRow()
        note = when {
            adding.isEmpty() && dropping.isEmpty() -> null
            adding.isEmpty() -> "Updated the tags of ${notebooks(ids.size)}"
            else -> "Tagged ${notebooks(ids.size)} " + adding.distinctBy { it.lowercase() }.joinToString(" ") { "#$it" }
        }; noteFault = false
        selecting = false; selection.clear(); shownIdentities = null; refresh()
        return true
    }

    /** Tag chooser for the ticked notebooks: a tag is ticked when all of them carry it; typed tags go on all of them. */
    fun showTagSelected(): Boolean {
        if (!selecting || selection.isEmpty() || batchTotal > 0 || !noDialogOpen()) return false
        val ids = selection.toList()
        val known = tagged.values.flatten().distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val everywhere = BooleanArray(known.size) { i -> ids.all { carries(it, known[i]) } }
        val checked = everywhere.copyOf()
        val input = EditText(context).apply { setSingleLine(); if (known.isEmpty()) fitHint("Tags, separated by commas", "Tags, comma-separated", "Tags") else fitHint("New tags, separated by commas", "New tags, comma-separated", "New tags") }
        val frame = FrameLayout(context).apply { setPadding(dpi(24f), dpi(8f), dpi(24f), 0); addView(input) }
        fun typed() = input.text.split(',').filter { it.isNotBlank() }
        val dialog = AlertDialog.Builder(context).setTitle("Tag ${notebooks(ids.size)}")
            .apply { if (known.isNotEmpty()) setMultiChoiceItems(known.toTypedArray(), checked) { _, which, on -> checked[which] = on } }
            .setView(frame)
            .setPositiveButton("Save") { _, _ ->
                val add = known.filterIndexed { i, _ -> checked[i] && !everywhere[i] } + typed()
                val remove = known.filterIndexed { i, _ -> everywhere[i] && !checked[i] }
                if (!tagSelected(add, remove)) { note = "A notebook can carry up to $MAX_TAGS tags."; noteFault = true; refresh() }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (tagsDialog === it) tagsDialog = null }
            .show()
        tagsDialog = dialog
        stopTagList(dialog)
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { button.isEnabled = typed().all { tagName(it) != null } }
        })
        return true
    }

    /** Chooser for the ticked notebooks' folder: No folder, each folder and New folder…. */
    fun showMoveSelectedToFolder(): Boolean {
        if (!selecting || selection.isEmpty() || batchTotal > 0 || !noDialogOpen()) return false
        showFolderChooser("Move to folder", null, false, { moveSelectedToFolder(it) }, notebooks(selection.size)) { showNewFolder(moveSelected = true) }
        return true
    }

    /** Asks before moving the ticked notebooks to the trash; false (no dialog) when that is not possible now. */
    fun confirmTrashSelected(): Boolean {
        if (!selecting || !selectionStoredLocally() || pending != null || batchTotal > 0 || !repository.canChangeNotebook || !noDialogOpen()) return false
        val count = selection.size
        selectDialog = AlertDialog.Builder(context).setTitle("Move ${notebooks(count)} to the trash?")
            .setMessage("${if (count == 1) "It leaves the library but is" else "They leave the library but are"} kept unchanged. You can restore ${if (count == 1) "it" else "them"} from Trash at any time.")
            .setPositiveButton("Move to trash") { _, _ -> trashSelected() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (selectDialog === it) selectDialog = null }
            .show()
        return true
    }

    /**
     * Moves the ticked notebooks to the trash without asking, one after another, and ends selecting. A refusal or failure
     * stops the rest, which stay in the library. False (nothing changes) when refused.
     */
    fun trashSelected(): Boolean {
        if (!selecting || showingTrash || !selectionStoredLocally() || pending != null || batchTotal > 0 || !repository.canChangeNotebook) return false
        val known = entriesSource().map { it.id }.toSet()
        val ids = selection.filter { it in known }
        if (ids.isEmpty()) return false
        trashQueue.clear(); trashQueue.addAll(ids)
        batchTotal = ids.size; batchMoved = 0; batchFailure = null; note = null
        selecting = false; selection.clear()
        trashNext()
        return true
    }

    /** True while ticked notebooks are still moving to the trash. */
    val movingToTrash: Boolean get() = batchTotal > 0

    private fun trashNext() {
        while (trashQueue.isNotEmpty()) {
            val id = trashQueue.removeFirst()
            if (entriesSource().none { it.id == id }) continue
            batchInFlight = true
            if (repository.trashNotebook(id)) return
            batchInFlight = false; batchFailure = "The rest could not be moved now."; trashQueue.clear()
        }
        note = when {
            batchFailure != null -> "Moved ${batchMoved} of ${notebooks(batchTotal)} to the trash. $batchFailure"
            else -> "Moved ${notebooks(batchMoved)} to the trash"
        }
        noteFault = batchFailure != null
        batchTotal = 0
        refresh()
    }

    /** Called on each refresh: once the repository has finished a batch trash, the next notebook goes. */
    private fun continueBatch() {
        if (batchTotal == 0 || !batchInFlight || !repository.canChangeNotebook) return
        batchInFlight = false
        val outcome = repository.libraryMessage
        if (outcome == "Moved to trash") batchMoved++ else { batchFailure = outcome ?: "The rest could not be moved."; trashQueue.clear() }
        // Posted, so the next repository call does not run inside this listener callback.
        main.post { if (attached && batchTotal > 0 && !batchInFlight) trashNext() }
    }

    private fun notebooks(count: Int) = if (count == 1) "1 notebook" else "$count notebooks"

    private fun selectedStorageReady() = attached && selecting && !showingTrash && selection.isNotEmpty() &&
        pending == null && storageRequest == null && storageBatch == null && batchTotal == 0 && repository.canChangeNotebook

    /** Selection's single storage home; removal always requires a separate, source-bound confirmation. */
    fun showSelectedStorage(): Boolean {
        if (!selectedStorageReady() || !noDialogOpen()) return false
        val ids = selection.toList()
        val chosen = ids.map { id -> entriesSource().firstOrNull { it.id == id && it.error == null } ?: return false }
        val identities = chosen.associate { it.id to it.identity }
        val local = chosen.filter { it.availableOffline }
        val sources = local.associate { it.id to repository.notebookSource(it.id) }
        fun valid() = selectedStorageReady() && selection.toList() == ids && identities.all { (id, identity) ->
            entriesSource().any { it.id == id && it.identity == identity && it.error == null }
        }
        fun canFree() = valid() && local.isNotEmpty() && local.all { entry ->
            !repository.isNotebookReadOnly(entry.id) && sources[entry.id] != null &&
                repository.notebookSource(entry.id) == sources[entry.id] &&
                entriesSource().any { it.id == entry.id && it.availableOffline }
        }
        fun present(sheet: Kit.Sheet) {
            selectedStorageSheet = sheet
            sheet.dialog.setOnDismissListener { if (selectedStorageSheet === sheet) selectedStorageSheet = null }
            sheet.show()
        }
        val sheet = Kit.Sheet(context, "Selected storage", cancel = "Close")
        sheet.add(Kit.row(context, "${notebooks(local.size)} kept on this device",
            detail = "${chosen.size - local.size} not downloaded"))
        val detail = Kit.text(context, "Download to prepare for offline use. Free local storage verifies an identical saved folder copy first. Earlier versions and backups are kept.", secondary = true)
        sheet.add(detail)
        if (chosen.any { !it.availableOffline }) sheet.add(Kit.button(context, "Download") {
            if (valid()) { sheet.dismiss(); downloadSelected() }
            else detail.text = "The selection changed or is busy. Close and try again."
        })
        if (local.isNotEmpty()) sheet.add(Kit.button(context, "Free local storage…", Kit.Button.TINTED) {
            if (!canFree()) {
                detail.text = "Local copies kept. Check read-only status and each notebook's Storage options and sync folder."
                return@button
            }
            sheet.dismiss()
            lateinit var confirmation: Kit.Sheet
            val explanation = Kit.text(context, "Verify identical copies in the saved folder before removing ${notebooks(local.size)} from this device. Already not-downloaded notebooks are skipped. Download again before writing. Earlier versions and backups are kept.", secondary = true)
            confirmation = Kit.Sheet(context, "Free selected copies?", "Free local copies", onAction = {
                if (!canFree()) {
                    explanation.text = "Nothing else was freed. The selection, source folder or read-only status changed. Close and try again."
                    false
                } else {
                    confirmation.dismiss()
                    startSelectedRelease(local.map { it.id }, sources.mapValues { it.value!! })
                }
            })
            confirmation.add(explanation)
            present(confirmation)
        }.apply { isEnabled = canFree() })
        if (local.isNotEmpty() && !canFree()) detail.text = "Local copies are kept: check read-only status and each notebook's Storage options and sync folder. Download remains available for not-downloaded notebooks."
        present(sheet)
        return true
    }

    private fun startSelectedRelease(ids: List<String>, sources: Map<String, String>): Boolean {
        if (!selectedStorageReady() || !noDialogOpen() || ids.isEmpty() || ids.any { id ->
            id !in selection || repository.isNotebookReadOnly(id) || repository.notebookSource(id) != sources[id] ||
                entriesSource().none { it.id == id && it.error == null && it.availableOffline }
        }) return false
        val batch = StorageBatch(ids, release = true, sources = sources,
            expected = ids.associateWith { id -> entriesSource().first { it.id == id }.identity })
        storageBatch = batch; selecting = false; selection.clear(); note = null
        nextSelectedStorage(batch)
        return true
    }

    /** Makes selected cloud entries available offline, sequentially; local selections need no transfer. Never opens one. */
    fun downloadSelected(): Boolean {
        if (!attached || !selecting || showingTrash || selection.isEmpty() || pending != null || storageRequest != null ||
            storageBatch != null || batchTotal > 0 || !repository.canChangeNotebook || !noDialogOpen()) return false
        val chosen = selection.map { id -> entriesSource().firstOrNull { it.id == id && it.error == null } ?: return false }
        val ids = chosen.filterNot { it.availableOffline }.map { it.id }
        if (ids.isEmpty()) return false
        val batch = StorageBatch(ids)
        storageBatch = batch; selecting = false; selection.clear(); note = null
        nextSelectedStorage(batch)
        return true
    }

    private fun nextSelectedStorage(batch: StorageBatch) {
        if (storageBatch !== batch || !attached || showingTrash) return
        if (batch.ready == batch.ids.size) { finishSelectedStorage(batch, true); return }
        val id = batch.ids[batch.ready]
        val entry = entriesSource().firstOrNull { it.id == id && it.error == null }
        if (entry == null || !repository.canChangeNotebook) { finishSelectedStorage(batch, false); return }
        if (batch.release && entry.identity != batch.expected[id]) {
            finishSelectedStorage(batch, false, "The notebook changed. Remaining local copies were kept.")
            return
        }
        if (entry.availableOffline != batch.release) { batch.ready++; main.post { nextSelectedStorage(batch) }; return }
        if (batch.release && (repository.isNotebookReadOnly(id) || repository.notebookSource(id) != batch.sources[id])) {
            finishSelectedStorage(batch, false, "The source folder or read-only status changed. Remaining local copies were kept.")
            return
        }
        var completed = false
        val operation = if (batch.release) storageActions.release else storageActions.download
        val accepted = operation(id) { result ->
            if (storageBatch === batch && !completed) {
                completed = true
                if (result.isSuccess) { batch.ready++; main.post { nextSelectedStorage(batch) } }
                else finishSelectedStorage(batch, false)
            }
        }
        if (!accepted && !completed && storageBatch === batch) finishSelectedStorage(batch, false)
        if (attached) refresh()
    }

    private fun finishSelectedStorage(batch: StorageBatch, success: Boolean, reason: String? = null) {
        if (storageBatch !== batch) return
        storageBatch = null
        if (!success && attached && !showingTrash) {
            val remaining = batch.ids.drop(batch.ready).toSet()
            val eligible = entriesSource().filter { it.id in remaining && it.error == null && it.availableOffline == batch.release }.map { it.id }.toSet()
            selection.clear()
            selection.addAll(batch.ids.drop(batch.ready).filter { it in eligible })
            selecting = selection.isNotEmpty()
        }
        note = if (success) {
            if (batch.release) "Local storage freed for ${notebooks(batch.ids.size)}. Earlier versions and backups are kept."
            else "${notebooks(batch.ids.size)} available offline."
        } else "${batch.ready} of ${batch.ids.size} " + (if (batch.release) "local copies freed. " else "notebooks ready. ") +
            (reason ?: repository.libraryMessage ?: "Storage action stopped. Remaining notebooks are selected for retry.")
        noteFault = !success
        if (attached) refresh()
    }

    private fun stopSelectedStorage() {
        val release = storageBatch?.release == true
        storageBatch = null; note = if (release) "Remaining local copies kept. The current verification may finish."
            else "Remaining downloads stopped. The current transfer may finish."
        noteFault = false
        refresh()
    }

    /** Preflight the whole batch, including IDs hidden by a filter; parsing errors do not exclude local packages. */
    private fun selectionStoredLocally(): Boolean {
        val local = entriesSource().filter { it.availableOffline }.map { it.id }.toSet()
        return selection.isNotEmpty() && selection.all { it in local }
    }

    private fun canExportSelected() = onExportSelected != null && selecting && !showingTrash &&
        pending == null && storageRequest == null && storageBatch == null && batchTotal == 0 &&
        repository.canChangeNotebook && selectionStoredLocally() &&
        selection.all { id -> entriesSource().any { it.id == id && it.error == null } }

    internal fun exportSelectedPdfs(): Boolean {
        if (!canExportSelected()) return false
        onExportSelected?.invoke(selection.toList())
        return true
    }

    /** The floating bar: shown while selecting, its actions enabled once something is ticked. */
    private fun showSelection(entries: List<LibraryEntry>) {
        val on = selecting && !showingTrash
        selectionHost.visibility = if (on) VISIBLE else GONE
        selectionViewport.visibility = if (on) VISIBLE else GONE
        selectionBar.visibility = selectionViewport.visibility
        // Room under the last row, so the bar never covers a notebook.
        scroll.setPadding(dpi(14f), 0, dpi(14f), dpi(if (on) 192f else 96f))
        if (!on) return
        val any = selection.isNotEmpty()
        selectionExportButton.visibility = if (onExportSelected != null) VISIBLE else GONE
        selectionExportButton.isEnabled = canExportSelected()
        selectionExportButton.contentDescription = if (any && !selectionStoredLocally()) "Download selected notebooks before exporting PDFs" else "Export selected notebooks as PDFs to a folder"
        selectionStorageButton.visibility = if (any) VISIBLE else GONE
        selectionStorageButton.isEnabled = selectedStorageReady()
        selectionStorageButton.contentDescription = "Manage offline storage for selected notebooks"
        folderSelectedButton.isEnabled = any; tagSelectedButton.isEnabled = any
        selectionTrashButton.isEnabled = selectionStoredLocally() && repository.canChangeNotebook
        selectionTrashButton.contentDescription = if (any && !selectionStoredLocally())
            "Download selected notebooks before moving them to trash" else "Move selected to the trash"
        val readable = entries.filter { it.error == null }.map { it.id }
        val all = readable.isNotEmpty() && selection.containsAll(readable)
        selectAllButton.text = if (all) "Clear" else "Select all"
        selectAllButton.contentDescription = if (all) "Untick all shown" else "Select all shown"
        selectAllButton.isEnabled = readable.isNotEmpty()
    }

    private fun canAct(id: String): LibraryEntry? {
        val entry = entriesSource().firstOrNull { it.id == id } ?: return null
        return entry.takeIf { !showingTrash && it.error == null && pending == null && storageRequest == null && storageBatch == null && repository.canChangeNotebook }
    }

    private fun dismissMenus() {
        listOf(libraryMenu, actionsMenu, folderMenu, dateMenu).forEach { it?.dismiss() }
        libraryMenu = null; actionsMenu = null; folderMenu = null; dateMenu=null
    }

    /** P606: one tile of a Library menu: its full [label] (accessibility name, tooltip, tests), the [short] name under its icon. */
    private class MenuChoice(val label: String, val short: String, val path: String, val tint: Int, val enabled: Boolean = true,
                             val fault: Boolean = false, val action: () -> Unit)

    /**
     * P606: a menu in the P605 tile style (the design canvas's Menus-Notebook board): an optional [pill] and [header] on
     * top, then each section's caption over a grid of tiles. Opens beside [anchor] when [beside], else below it; every
     * tile closes the menu before acting.
     */
    private fun tileMenu(anchor: View, sections: List<Pair<String, List<MenuChoice>>>, beside: Boolean, header: View? = null,
                         pill: Triple<String, String, () -> Unit>? = null, closed: (Popover) -> Unit): Popover {
        lateinit var menu: Popover
        menu = Popover(anchor, 340f) { column ->
            column.dpi(12f).let { column.setPadding(it, it, it, it) }
            pill?.let { (text, path, action) -> column.addView(ToolControls.pill(context, text, path) { menu.dismiss(); main.post { action() } }, ToolControls.pillParams(context)) }
            header?.let { column.addView(it) }
            sections.forEach { (caption, choices) ->
                column.addView(ToolControls.tileCaption(context, caption))
                column.addView(ToolControls.tileGrid(context, choices.map { choice ->
                    MenuTile(context, choice.label, choice.short, choice.path, choice.tint, choice.enabled, fault = choice.fault) { menu.dismiss(); main.post { choice.action() } }
                }))
            }
        }
        menu.window.setBackgroundDrawable(Glass.float(context, night, ToolControls.TILE_MENU_RADIUS))
        menu.window.setOnDismissListener { closed(menu) }
        if (beside) menu.showBeside() else menu.show()
        return menu
    }

    /** P606: a notebook's menu starts with the notebook itself (its cover, title, pages and folder), as in the design canvas. */
    private fun bookHeader(entry: LibraryEntry) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(1f), 0, dpi(6f), dpi(5f))
        addView(CoverTile(context, coverStyles[entry.id], false).apply {
            isClickable = false; isFocusable = false; foreground = null; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dpi(50f), dpi(62f)))
        val place = listOfNotNull(if (entry.pageCount == 1) "1 page" else "${entry.pageCount} pages",
            filed[entry.id]?.let { "in ${leafOf(it)}" }, if (!entry.availableOffline) "Not downloaded" else null).joinToString(" · ")
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = entry.title; textSize = 17f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.text(night))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = place; textSize = 13f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(2f), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(7f) })
    }

    /** A menu of [rows] (label to action; null is a divider) opened beside [anchor]; each row closes it before acting. */
    private fun sideMenu(anchor: View, heading: String, rows: List<Pair<String, () -> Unit>?>, closed: (Popover) -> Unit): Popover {
        lateinit var menu: Popover
        menu = Popover(anchor, 248f) { column ->
            column.addView(TextView(context).apply {
                text = heading; textSize = 14f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(night))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(6f))
            })
            rows.forEach { row ->
                if (row == null) column.addView(ToolControls.divider(context))
                else column.addView(ToolControls.menuRow(context, row.first, true) { menu.dismiss(); main.post { row.second() } })
            }
        }
        menu.window.setOnDismissListener { closed(menu) }
        menu.showBeside()
        return menu
    }

    /**
     * B17 (P136): a readable library notebook's options, opened beside it (from its ⋮ button, or a touch and hold);
     * false (no menu) when no action is possible now.
     */
    fun showActions(id: String, anchor: View? = null): Boolean {
        val entry = canAct(id) ?: return false
        if (selecting || openMenu != null || renameDialog != null || trashDialog != null) return false
        val at = anchor ?: tiles[id]?.cover ?: return false
        note = null
        // P606: grouped Notebook / Arrange / Store as tiles, as in the design canvas; a notebook that is not on this
        // device keeps only what works without its pages.
        val ink = MenuIcons.INSERT; val arrange = MenuIcons.PAGES; val store = MenuIcons.NOTEBOOK
        val here = entry.availableOffline
        val sections = listOfNotNull(
            if (here) "Notebook" to listOf(
                MenuChoice("Edit notebook…", "Edit", MenuIcons.PAPER, ink) { showEditNotebook(id) },
                MenuChoice("Rename…", "Rename", MenuIcons.PEN, ink) { showRename(id) },
                MenuChoice("Duplicate", "Duplicate", MenuIcons.DUPLICATE, ink) { duplicate(id) },
                MenuChoice("Earlier versions…", "Earlier versions", MenuIcons.HISTORY, ink) { showVersions(id) },
            ) else null,
            "Arrange" to listOf(
                if (id in pinned) MenuChoice("Unpin", "Unpin", MenuIcons.PIN, arrange) { setPinned(id, false) }
                else MenuChoice("Pin to top", "Pin to top", MenuIcons.PIN, arrange) { setPinned(id, true) },
                MenuChoice("Move to folder…", "Move to folder", MenuIcons.MOVE_FOLDER, arrange) { showMoveToFolder(id) },
                MenuChoice("Cover…", "Cover", MenuIcons.COVER, arrange) { showCoverStyles(id) },
                MenuChoice("Tags…", "Tags", MenuIcons.TAG, arrange) { showTags(id) },
            ),
            "Store" to listOfNotNull(
                if (id in archived) MenuChoice("Return to library", "Return to library", MenuIcons.ARCHIVE, store) { setArchived(id, false) }
                else MenuChoice("Archive", "Archive", MenuIcons.ARCHIVE, store) { setArchived(id, true) },
                MenuChoice("Offline storage…", "Offline storage", MenuIcons.OFFLINE, store) { showStorage(id) },
                if (here) MenuChoice("Move to trash…", "Move to trash", MenuIcons.TRASH, Tokens.FAULT, fault = true) { confirmTrash(id) } else null,
            ),
        )
        actionsMenu = tileMenu(at, sections, beside = true, header = bookHeader(entry)) { if (actionsMenu === it) actionsMenu = null }
        return true
    }

    /** Storage belongs to the notebook in Library; it never exposes an empty editor. */
    fun showStorage(id: String, openAfterDownload: Boolean = false): Boolean {
        val entry = canAct(id) ?: return false
        if (!attached || selecting || batchTotal > 0 || !noDialogOpen()) return false
        storagePanel?.dispose()
        val valid = { attached && !showingTrash && entriesSource().any { it.identity == entry.identity } }
        storagePanel = NotebookStoragePanel(context, repository, entry, storageActions, openAfterDownload, valid,
            release = {
                if (!valid() || !repository.canChangeNotebook) false
                else storageActions.release(id) { if (attached) refresh() }
            },
            download = { startStorageDownload(id, openAfterDownload) },
            recover = onSourceRecovery?.let { handler -> { if (valid()) handler(id) else false } },
            syncSettings = onSync).also { it.show() }
        return true
    }

    private fun startStorageDownload(id: String, openAfter: Boolean): Boolean {
        if (canAct(id) == null || !attached || storageRequest != null) return false
        val request = StorageRequest(id, query, openFolder, openTag)
        storageRequest = request
        val accepted = storageActions.download(id) { result ->
            if (storageRequest === request) {
                storageRequest = null
                if (attached) {
                    val samePlace = !showingTrash && !selecting && query == request.query && openFolder == request.folder && openTag == request.tag
                    if (result.isSuccess && openAfter && samePlace) open(id) else {
                        refresh()
                        if (result.isFailure && openAfter && samePlace) showStorage(id, openAfterDownload = true)
                    }
                }
            }
        }
        if (!accepted && storageRequest === request) storageRequest = null
        if (attached) refresh()
        return accepted
    }

    private fun carries(id: String, tag: String) = tagged[id]?.any { it.equals(tag, ignoreCase = true) } == true

    /**
     * Replaces a readable library notebook's tags; names are trimmed, a leading "#" is dropped and a tag another notebook
     * already uses keeps that spelling. False in Trash, for an unknown or unreadable notebook, an invalid name or more
     * than [MAX_TAGS] tags.
     */
    fun setTags(id: String, names: Collection<String>): Boolean {
        if (showingTrash || entriesSource().none { it.id == id && it.error == null }) return false
        val spelling = tagged.filterKeys { it != id }.values.flatten().associateBy { it.lowercase() }
        val list = names.map { tagName(it) ?: return false }.map { spelling[it.lowercase()] ?: it }
            .distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (list.size > MAX_TAGS) return false
        if (list == tagsOf(id)) return true
        if (list.isEmpty()) tagged -= id else tagged[id] = list.toMutableList()
        saveTags(); afterTagsChanged()
        return true
    }

    /** Shows only notebooks carrying [tag] (within the open folder), or drops the tag filter (null); false for an unknown tag. */
    fun showTag(tag: String?): Boolean {
        if (showingTrash) return false
        val known = tag?.let { name -> tags.firstOrNull { it.equals(name, ignoreCase = true) } ?: return false }
        if (known == openTag) return true
        openTag = known; catalogueLayout.scrollToPositionWithOffset(0, 0); refresh()
        return true
    }

    /** Renames a tag on every notebook carrying it; false for an unknown tag or an invalid name. */
    fun renameTag(from: String, text: String): Boolean {
        if (showingTrash || tagged.values.none { list -> list.any { it.equals(from, ignoreCase = true) } }) return false
        val to = tagName(text) ?: return false
        tagged.values.forEach { list ->
            if (list.removeAll { it.equals(from, ignoreCase = true) }) { list.removeAll { it.equals(to, ignoreCase = true) }; list += to; list.sortWith(String.CASE_INSENSITIVE_ORDER) }
        }
        if (openTag?.equals(from, ignoreCase = true) == true) openTag = to
        saveTags(); afterTagsChanged()
        return true
    }

    /** Takes a tag off every notebook; the notebooks themselves are untouched. False for an unknown tag. */
    fun removeTag(tag: String): Boolean {
        if (showingTrash || tagged.values.none { list -> list.any { it.equals(tag, ignoreCase = true) } }) return false
        tagged.values.forEach { list -> list.removeAll { it.equals(tag, ignoreCase = true) } }
        tagged.values.removeAll { it.isEmpty() }
        saveTags(); afterTagsChanged()
        return true
    }

    private fun saveTags() {
        preferences.edit().putStringSet(TAGS_KEY, tagged.flatMap { (id, list) -> list.map { "$id\n$it" } }.toSet()).apply()
    }

    private fun afterTagsChanged() {
        if (openTag != null && tags.none { it.equals(openTag, ignoreCase = true) }) openTag = null
        buildFolderRow(); shownIdentities = null; refresh()
    }

    /** The tag chooser: each tag in the library (ticked when the notebook carries it) and a field for new ones. */
    fun showTags(id: String): Boolean {
        val entry = canAct(id) ?: return false
        if (!noDialogOpen()) return false
        val known = tagged.values.flatten().distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val checked = BooleanArray(known.size) { carries(id, known[it]) }
        val input = EditText(context).apply { setSingleLine(); if (known.isEmpty()) fitHint("Tags, separated by commas", "Tags, comma-separated", "Tags") else fitHint("New tags, separated by commas", "New tags, comma-separated", "New tags") }
        val frame = FrameLayout(context).apply { setPadding(dpi(24f), dpi(8f), dpi(24f), 0); addView(input) }
        fun typed() = input.text.split(',').filter { it.isNotBlank() }
        val dialog = AlertDialog.Builder(context).setTitle("Tags for “${entry.title}”")
            .apply { if (known.isNotEmpty()) setMultiChoiceItems(known.toTypedArray(), checked) { _, which, on -> checked[which] = on } }
            .setView(frame)
            .setPositiveButton("Save") { _, _ ->
                if (!setTags(id, known.filterIndexed { i, _ -> checked[i] } + typed())) { note = "A notebook can carry up to $MAX_TAGS tags."; noteFault = true; refresh() }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (tagsDialog === it) tagsDialog = null }
            .show()
        tagsDialog = dialog
        stopTagList(dialog)
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { button.isEnabled = typed().all { tagName(it) != null } }
        })
        return true
    }

    /**
     * P655: with many tags the list took the whole sheet and pushed the typing field, Save and Cancel out of it (40 tags
     * squeezed both buttons to nothing). The list now stops at 40% of the screen and scrolls inside that.
     */
    private fun stopTagList(dialog: AlertDialog) {
        val list = dialog.listView ?: return
        val most = (resources.displayMetrics.heightPixels * .4f).toInt()
        list.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > most && v.layoutParams.height != most) v.post { v.layoutParams = v.layoutParams.apply { height = most } }
        }
    }

    /** Long-press sheet of a tag chip: Rename tag…, Remove tag…. */
    fun showTagActions(tag: String): Boolean {
        if (showingTrash || tag !in tagChips || !noDialogOpen()) return false
        tagsDialog = AlertDialog.Builder(context).setTitle("#$tag")
            .setItems(arrayOf("Rename tag…", "Remove tag…")) { _, which ->
                main.post {
                    if (which == 0) tagsDialog = nameDialog("Rename tag", tag, "Rename", { tagName(it) != null }, hint = "Tag name") { renameTag(tag, it) }
                    else confirmRemoveTag(tag)
                }
            }
            .setOnDismissListener { if (tagsDialog === it) tagsDialog = null }
            .show()
        return true
    }

    private fun confirmRemoveTag(tag: String) {
        if (!noDialogOpen()) return
        val count = entriesSource().count { carries(it.id, tag) }
        tagsDialog = AlertDialog.Builder(context).setTitle("Remove the tag “$tag”?")
            .setMessage(when (count) { 0 -> "No notebook in the library carries it."; 1 -> "It comes off 1 notebook; the notebook itself is unchanged."; else -> "It comes off $count notebooks; the notebooks themselves are unchanged." })
            .setPositiveButton("Remove tag") { _, _ -> removeTag(tag) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (tagsDialog === it) tagsDialog = null }
            .show()
    }

    /** The coloured cover key of a notebook (one of [coverStyleKeys]), or null for its first-page preview. */
    fun coverStyleOf(id: String): String? = coverStyles[id]
    /** Cover keys in chooser order. */
    val coverStyleKeys: List<String> get() = COVER_DESIGNS.keys.toList()

    /** Gives a readable library notebook a coloured cover, or its first page back (null); false when refused. */
    fun setCoverStyle(id: String, key: String?): Boolean {
        if (showingTrash || (key != null && key !in COVER_DESIGNS) || entriesSource().none { it.id == id && it.error == null }) return false
        if (coverStyles[id] == key) return true
        if (key == null) coverStyles -= id else coverStyles[id] = key
        preferences.edit().putStringSet(COVERS_KEY, coverStyles.map { (n, k) -> "$n\n$k" }.toSet()).apply()
        (listOfNotNull(tiles[id]?.cover, recentCards[id]?.cover)).forEach { it.style = key; if (key != null) it.release(); it.invalidate() }
        bindVisible()
        return true
    }

    /** The cover chooser: First page, then a mini cover for each design; the current choice is ringed. */
    fun showCoverStyles(id: String): Boolean {
        val entry = canAct(id) ?: return false
        if (!noDialogOpen()) return false
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(4f)) }
        lateinit var dialog: AlertDialog
        (listOf<String?>(null) + COVER_DESIGNS.keys).forEach { key ->
            row.addView(CoverSwatch(context, key, selected = coverStyles[id] == key).apply {
                setOnClickListener { setCoverStyle(id, key); dialog.dismiss() }
            }, LinearLayout.LayoutParams(dpi(72f), dpi(112f) + kotlin.math.ceil(CoverSwatch.grow(context)).toInt()).apply { marginEnd = dpi(4f) })
        }
        dialog = AlertDialog.Builder(context).setTitle("Cover for “${entry.title}”")
            .setView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (coverDialog === it) coverDialog = null }
            .show()
        coverDialog = dialog
        return true
    }

    /** The open New notebook sheet, if any (for tests); P201 step 7 made it a Glass sheet. */
    var newNotebookSheet: Kit.Sheet? = null; private set
    val newNotebookDialog: android.app.Dialog? get() = newNotebookSheet?.dialog
    /** Paper templates New notebook offers: the built-in papers, then this device's saved paper presets. */
    val newNotebookTemplates: List<String> get() = templates().map { it.first }
    /** Page sizes New notebook offers. */
    val newNotebookSizes: List<String> get() = PAGE_SIZES.map { it.first }

    private fun templates(): List<Pair<String, PaperStyle>> {
        val saved = runCatching { PaperPresets(preferences).list() }.getOrDefault(emptyList())
        return BUILT_IN_PAPERS + saved.filter { p -> BUILT_IN_PAPERS.none { it.first.equals(p.name, ignoreCase = true) } }.map { it.name to it.paper }
    }

    /**
     * Creates a one-page notebook titled [title] with the [template] paper and [size] page, opens it, files it into the
     * open folder and gives it the [cover] design (null: its first page shows instead). False when refused or busy.
     */
    fun createNotebook(title: String, cover: String?, template: String, size: String): Boolean {
        if (selecting || pending != null || batchTotal > 0 || showingTrash || !repository.canChangeNotebook) return false
        val name = title.trim()
        if (name.length !in 1..500 || name.any { it.isISOControl() } || (cover != null && cover !in COVER_DESIGNS)) return false
        val paper = templates().firstOrNull { it.first == template }?.second ?: return false
        val (w, h) = PAGE_SIZES.firstOrNull { it.first == size }?.second ?: return false
        if (runCatching { paper.validate(w, h) }.isFailure) return false
        note = null
        val current = repository.session?.notebook?.id
        val notebook = Notebook(title = name, pages = listOf(Page(width = w, height = h, paper = paper)))
        if (!runCatching { repository.importNotebook(notebook) }.getOrDefault(false)) return false
        preferences.edit().putString(NEW_COVER_KEY, cover ?: "").putString(NEW_TEMPLATE_KEY, template).putString(NEW_SIZE_KEY, size).apply()
        pending = Pending(null, current, openFolder, cover); refresh()
        return true
    }

    /**
     * New notebook (P068): a title (the next free "Notebook N", selected), an outside cover, a paper template and a page
     * size. The last choices are offered again next time. Create makes and opens the notebook.
     */
    fun showEditNotebook(id: String): Boolean {
        if (canAct(id) == null || !noDialogOpen()) return false
        return repository.loadNotebookSettings(id) { result ->
            result.onSuccess { if (isAttachedToWindow) showNewNotebook(it) }
                .onFailure { note = "Could not open notebook settings: ${it.message}"; refresh() }
        }
    }

    /**
     * P201 step 7: the Glass "New notebook" board. A centred glass sheet: on the left a large preview of the closed
     * notebook over the cover tiles and "Or start from"; on the right the title, the folder it is filed into, paper
     * tiles and the page size. The columns stack on a narrow screen. Edit notebook ([existing]) uses the same sheet.
     */
    fun showNewNotebook(existing: Notebook? = null): Boolean {
        if (selecting || pending != null || batchTotal > 0 || showingTrash || !repository.canChangeNotebook || !noDialogOpen()) return false
        val titles = entriesSource().map { it.title.trim().lowercase() }.toSet()
        val suggested = generateSequence(1) { it + 1 }.map { "Notebook $it" }.first { it.lowercase() !in titles }
        val options = (if (existing != null) listOf("Keep existing paper" to PaperStyle.DEFAULT) else emptyList()) + templates()
        val sizes = (if (existing != null) listOf("Keep existing sizes" to (0f to 0f)) else emptyList()) + PAGE_SIZES
        var cover: String? = preferences.getString(NEW_COVER_KEY, null).let { if (it == null) DEFAULT_COVER else it.takeIf { k -> k in COVER_DESIGNS } }
        if (existing != null) cover = coverStyles[existing.id]
        var template = if (existing != null) "Keep existing paper" else preferences.getString(NEW_TEMPLATE_KEY, null)?.takeIf { t -> options.any { it.first == t } } ?: "Ruled"
        var size = if (existing != null) "Keep existing sizes" else preferences.getString(NEW_SIZE_KEY, null)?.takeIf { s -> PAGE_SIZES.any { it.first == s } } ?: PAGE_SIZES[0].first
        val night = context.night
        val input = EditText(context)
        val error = Kit.text(context, "", Kit.Type.CAPTION).apply { setTextColor(Kit.dangerText(night)); visibility = GONE }
        val replace = Kit.Switch(context, false, "Replace imported page backgrounds") {}
        lateinit var sheet: Kit.Sheet
        /** Edit notebook's Save: applied in the background, so the sheet closes itself when it succeeds. */
        fun save(existing: Notebook): Boolean {
            val create = sheet.actionButton!!
            val dimensions = sizes.first { it.first == size }.second.takeIf { size != "Keep existing sizes" }
            val paper = options.first { it.first == template }.second.takeIf { template != "Keep existing paper" }
            fun fail(text: String?) { error.text = text; error.visibility = VISIBLE; create.isEnabled = true }
            create.isEnabled = false
            runCatching {
                repository.configureNotebook(existing, input.text.toString(), existing.pages.map { it.id }.toSet(),
                    dimensions?.first, dimensions?.second, paper, replace.isChecked) { success ->
                    if (success) { setCoverStyle(existing.id, cover); sheet.dismiss() }
                    else fail("Could not save settings. Reopen Edit notebook and try again.")
                }
            }.onSuccess { accepted -> if (!accepted) fail("Notebook changed or busy. Reopen Edit notebook.") }
                .onFailure { fail(it.message) }
            return false
        }
        sheet = Kit.Sheet(context, if (existing == null) "New notebook" else "Edit notebook", if (existing == null) "Create" else "Save",
            maxWidth = 1040f, centred = true) {
            if (existing == null) createNotebook(input.text.toString(), cover, template, size) else save(existing)
        }
        fun caption(text: String) = Kit.text(context, text, Kit.Type.CAPTION).apply { textSize = 13f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.secondary(night)) }
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun LinearLayout.put(view: View, gap: Float = 0f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
            addView(view, LinearLayout.LayoutParams(width, height).apply { topMargin = dpi(gap) })
        /** Rows of [cols] equal cells, [gap] dp apart; the last row keeps its cells the same width. */
        fun grid(cells: List<View>, cols: Int, gap: Float) = column().apply {
            cells.chunked(cols).forEachIndexed { r, chunk ->
                val row = LinearLayout(context)
                for (i in 0 until cols) {
                    val cell = chunk.getOrNull(i) ?: View(context)
                    row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dpi(gap) })
                }
                put(row, if (r > 0) gap else 0f)
            }
        }

        // Left: the preview, the covers and the other ways to start.
        val stage = CoverStage(context)
        fun showStage() {
            stage.cover = cover; stage.paper = options.first { it.first == template }.second; stage.title = input.text.toString().trim()
            stage.caption = (openFolder?.takeIf { existing == null } ?: "Notebook").uppercase() + " · " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            stage.label = (cover?.let { COVER_DESIGNS[it]!!.name } ?: "First page") + " · " + template
            stage.contentDescription = "Preview: ${stage.label}"
        }
        val left = column()
        left.put(stage)
        left.put(caption("Cover"), 18f)
        val coverTiles = (listOf<String?>(null) + COVER_DESIGNS.keys).map { CoverTile(context, it, it == cover) }
        coverTiles.forEach { tile -> tile.setOnClickListener { cover = tile.key; coverTiles.forEach { it.on = it.key == cover }; showStage() } }
        left.put(grid(coverTiles, 7, 10f), 10f)
        if (existing == null) {
            left.put(caption("Or start from"), 22f)
            left.put(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(46f); setPadding(dpi(12f), 0, dpi(16f), 0)
                background = rounded(Kit.cardFill(night), dp(23f)); elevation = dp(1f); foreground = ripple(night, dp(23f))
                isClickable = true; isFocusable = true; contentDescription = "Import a PDF or notebook"
                addView(android.widget.ImageView(context).apply { setImageDrawable(Kit.Icon(IMPORT_ICON, Glass.accent(night), dpi(19f))) }, LinearLayout.LayoutParams(dpi(19f), dpi(19f)))
                addView(Kit.text(context, "Import PDF or notebook").apply { textSize = 14f; typeface = Tokens.SANS_SEMIBOLD },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) })
                setOnClickListener { if (requestImport()) sheet.dismiss() }
            }, 10f, if (context.largeText) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(46f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Right: title, folder, paper, size.
        val right = column()
        right.put(caption("Title"))
        input.apply {
            setText(existing?.title ?: suggested); setSingleLine(); selectAll(); hint = "Notebook title"; contentDescription = "Notebook title"
            textSize = 22f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(night)); setHintTextColor(Glass.secondary(night))
            minHeight = dpi(56f); setPadding(dpi(18f), 0, dpi(18f), 0); gravity = Gravity.CENTER_VERTICAL
            fun ring(focused: Boolean) {
                background = if (focused) rounded(Kit.cardFill(night), dp(16f), Glass.accent(night), dpi(2f)) else rounded(Kit.cardFill(night), dp(16f), Kit.well(night), dpi(1f))
            }
            ring(false); setOnFocusChangeListener { _, focused -> ring(focused) }
        }
        right.put(input, 10f)
        if (existing == null && openFolder != null) right.put(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = android.text.SpannableStringBuilder("Folder  ").apply {
                    setSpan(android.text.style.ForegroundColorSpan(Glass.secondary(night)), 0, length, 0)
                    append(openFolder)
                }
                textSize = 14f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(night)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                minHeight = dpi(40f); gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(14f), 0, dpi(14f), 0)
                background = rounded(Kit.well(night), dp(20f)); contentDescription = "Files into the folder $openFolder"
            })
        }, 14f)
        right.put(caption("Paper"), 24f)
        val paperTiles = options.map { (name, paper) -> PaperTile(context, name, paper, name == template) }
        paperTiles.forEach { tile -> tile.setOnClickListener { template = tile.name; paperTiles.forEach { it.on = it.name == template }; showStage() } }
        right.put(grid(paperTiles, 3, 14f), 10f)
        right.put(caption("Size"), 24f)
        fun describeSizes(well: ViewGroup) = sizes.forEachIndexed { i, (name, _) ->
            well.getChildAt(i).contentDescription = (if (name == "Keep existing sizes") "Keep existing page sizes" else "$name page size") + if (name == size) ", selected" else ""
        }
        lateinit var sizeWell: ViewGroup
        sizeWell = Kit.segmented(context, sizes.map { SEGMENT_NAMES[it.first] ?: it.first }, sizes.indexOfFirst { it.first == size }) { which ->
            size = sizes[which].first; describeSizes(sizeWell)
        }
        sizeWell.contentDescription = "Page size"
        describeSizes(sizeWell)
        right.put(sizeWell, 10f)
        if (existing != null) {
            right.put(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(column().apply {
                    put(Kit.text(context, "Replace imported page backgrounds").apply { textSize = 14f; typeface = Tokens.SANS_MEDIUM })
                    put(Kit.text(context, "Removes the imported PDF or background image; handwriting and placed pictures stay.", Kit.Type.CAPTION, secondary = true), 4f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
                addView(replace)
            }, 24f)
            right.put(Kit.text(context, "Applies to all pages; notes and pictures scale with the page. Keep leaves mixed page sizes and papers as they are.", Kit.Type.CAPTION, secondary = true), 12f)
        }
        right.put(error, 12f)

        val columns = LinearLayout(context)
        /** Side by side from 760 dp, stacked below; chosen again when the tablet turns with the sheet open (P650). */
        fun arrange() {
            val wide = resources.displayMetrics.let { it.widthPixels / it.density } >= 760f
            if (columns.childCount > 0 && (columns.orientation == LinearLayout.HORIZONTAL) == wide) return
            columns.removeAllViews(); columns.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (wide) {
                columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .42f))
                columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .58f).apply { marginStart = dpi(30f) })
            } else {
                columns.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                columns.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(24f) })
            }
        }
        arrange()
        sheet.add(columns, 8f)
        sheet.onTurned { arrange() }
        sheet.dialog.setOnDismissListener { if (newNotebookSheet === sheet) newNotebookSheet = null }
        sheet.show()
        val create = sheet.actionButton!!
        fun check() { create.isEnabled = input.text.toString().trim().let { it.length in 1..500 && it.none { c -> c.isISOControl() } } }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { check(); showStage() }
        })
        check(); showStage()
        newNotebookSheet = sheet
        return true
    }

    /** A chooser entry: a small closed book in the design (a ruled page for First page) with its name beneath. */
    private class CoverSwatch(context: Context, val key: String?, selected: Boolean) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); typeface = Tokens.SANS_MEDIUM; textAlign = Paint.Align.CENTER }
        private val frame = RectF()
        private val name = key?.let { COVER_DESIGNS[it]!!.name } ?: "First page"
        // P639: the name grows with the reader's text size; the swatch grows under the cover to hold it.
        private val most = context.readerSize(12f, 16f)
        var on = selected; set(value) { field = value; describe(); invalidate() }
        init { isClickable = true; isFocusable = true; foreground = ripple(night, dp(10f)); describe() }
        private fun describe() { contentDescription = "$name cover" + if (on) ", selected" else "" }
        override fun onDraw(canvas: Canvas) {
            val u = dp(1f)
            frame.set(10f * u, 8f * u, width - 10f * u, height - 28f * u - grow(context))
            val design = COVER_DESIGNS[key]
            paint.style = Paint.Style.FILL; paint.color = 0x24182636; canvas.drawRoundRect(frame.left, frame.top + u, frame.right, frame.bottom + 2 * u, 4 * u, 4 * u, paint)
            if (design == null) {
                paint.color = -1; canvas.drawRoundRect(frame, 4 * u, 4 * u, paint)
                paint.color = Tokens.RULE; paint.strokeWidth = u
                var y = frame.top + 10f * u; while (y < frame.bottom - 4 * u) { canvas.drawLine(frame.left + 5 * u, y, frame.right - 5 * u, y, paint); y += 7f * u }
            } else drawBookCover(canvas, frame, design, paint)
            if (on) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * u; paint.color = Glass.accent(night)
                canvas.drawRoundRect(frame.left - 4 * u, frame.top - 4 * u, frame.right + 4 * u, frame.bottom + 4 * u, 7 * u, 7 * u, paint)
                paint.style = Paint.Style.FILL
            }
            label.color = if (on) accentInk(night) else Glass.secondary(night)
            val shown = key?.let { name } ?: "Page"
            label.fitText(shown, width - 4f * u, most, dp(12f))
            val text = TextUtils.ellipsize(shown, label, width - 4f * u, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, width / 2f, height - 9f * u, label)
        }
        companion object {
            /** P639: the extra height the name needs at a larger text size (none at normal size). */
            fun grow(context: Context) = (context.readerSize(12f, 16f) - context.dp(12f)) * 1.25f
        }
    }

    /**
     * P201 step 7: the New notebook board's preview. The closed notebook stands large on a soft stage with its pages
     * behind it and its title on a cream plate; a pill names the cover and paper. First page shows the paper itself.
     */
    private class CoverStage(context: Context) : View(context) {
        var cover: String? = null; set(value) { field = value; invalidate() }
        var paper: PaperStyle = PaperStyle.DEFAULT; set(value) { field = value; invalidate() }
        var title = ""; set(value) { field = value; invalidate() }
        var caption = ""; set(value) { field = value; invalidate() }
        var label = ""; set(value) { field = value; invalidate() }
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val book = RectF()
        private val box = RectF()
        private val path = Path()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, min(w * 1.02f, dp(440f)).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val u = dp(1f)
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(w / 2, h * .3f, max(w, h) * .75f, if (night) 0xff3a404c.toInt() else Color.WHITE,
                if (night) 0xff262a33.toInt() else 0xffe7ecf4.toInt(), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, w, h, 30 * u, 30 * u, paint); paint.shader = null
            // The board's 236 × 318 cover at 73% of the stage's height, with the pages 10 × 6 behind it.
            // The book starts below the label pill, so a narrow stage does not put the pill over the cover.
            val top = max(h * .1f, 52 * u)
            val bh = min(h * .73f, h - top - 44 * u); val s = bh / 318f; val bw = 236f * s
            book.set((w - bw - 10 * s) / 2, top, (w - bw - 10 * s) / 2 + bw, top + bh)
            paint.color = 0x38141e37; paint.maskFilter = android.graphics.BlurMaskFilter(10 * s, android.graphics.BlurMaskFilter.Blur.NORMAL)
            canvas.drawOval(book.left + 4 * s, book.bottom + 14 * s, book.right + 4 * s, book.bottom + 34 * s, paint); paint.maskFilter = null
            box.set(book.left + 10 * s, book.top + 6 * s, book.right + 10 * s, book.bottom + 6 * s)
            path.reset(); bookPath(path, box); paint.color = Color.WHITE; canvas.drawPath(path, paint)
            paint.color = 0xffe3e7ee.toInt(); paint.strokeWidth = max(1f, s)
            for (i in 1..4) canvas.drawLine(box.right - (2 + 2.5f * i) * s, box.top + 10 * s, box.right - (2 + 2.5f * i) * s, box.bottom - 10 * s, paint)
            path.reset(); bookPath(path, book)
            paint.setShadowLayer(18 * s, 0f, 9 * s, 0x47141e37)
            val design = COVER_DESIGNS[cover]
            paint.color = design?.color ?: (paper.color.toInt() or 0xff000000.toInt()); canvas.drawPath(path, paint)
            paint.clearShadowLayer()
            if (design != null) drawBookCover(canvas, book, design, paint)
            else { canvas.save(); canvas.clipPath(path); PaperArt.closeUp(canvas, book, paper, paint, u); canvas.restore() }
            // The title plate: what the notebook will look like in the library. First page keeps the page itself in view there.
            if (design != null) drawTitlePlate(canvas, book, title.ifEmpty { "Untitled" }, title.isEmpty(), caption, paint, text, box)
            // The pill naming the choices.
            if (label.isNotEmpty()) {
                text.textSize = 12 * u; text.letterSpacing = 0f; text.color = Glass.secondary(night)
                val tw = min(text.measureText(label), w - 60 * u)
                box.set(18 * u, 16 * u, 18 * u + tw + 20 * u, 16 * u + 26 * u)
                paint.color = if (night) 0xcc2b303a.toInt() else 0xccffffff.toInt(); canvas.drawRoundRect(box, 10 * u, 10 * u, paint)
                canvas.drawText(TextUtils.ellipsize(label, text, tw, TextUtils.TruncateAt.END).toString(), box.left + 10 * u, box.top + 17.5f * u, text)
            }
        }
    }

    /** P201 step 7: a cover choice on the New notebook board: a small closed book (a ruled page for First page), ringed when chosen. */
    private class CoverTile(context: Context, val key: String?, selected: Boolean) : View(context) {
        private val name = key?.let { COVER_DESIGNS[it]!!.name } ?: "First page"
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val path = Path()
        var on = selected; set(value) { field = value; describe(); invalidate() }
        init { isClickable = true; isFocusable = true; foreground = ripple(night, dp(9f)); describe() }
        private fun describe() { contentDescription = "$name cover" + if (on) ", selected" else "" }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, min((w - dp(10f)) * 1.3f + dp(10f), dp(72f)).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            val u = dp(1f)
            r.set(5 * u, 5 * u, width - 5 * u, height - 5 * u)
            val design = COVER_DESIGNS[key]
            if (design != null) drawBookCover(canvas, r, design, paint)
            else {
                path.reset(); bookPath(path, r); paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawPath(path, paint)
                paint.color = Tokens.RULE; paint.strokeWidth = u
                var y = r.top + 9 * u; while (y < r.bottom - 4 * u) { canvas.drawLine(r.left + 5 * u, y, r.right - 5 * u, y, paint); y += 6 * u }
                paint.style = Paint.Style.STROKE; paint.color = 0xffd3d8e0.toInt(); paint.strokeWidth = 1.5f * u; canvas.drawPath(path, paint)
            }
            if (on) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * u; paint.color = Glass.accent(night)
                canvas.drawRoundRect(r.left - 3.75f * u, r.top - 3.75f * u, r.right + 3.75f * u, r.bottom + 3.75f * u, 9 * u, 9 * u, paint)
            }
            paint.style = Paint.Style.FILL
        }
    }

    /** P201 step 7: a paper choice on the New notebook board: a close-up of the paper on a rounded tile, its name beneath. */
    private class PaperTile(context: Context, val name: String, paper: PaperStyle, selected: Boolean) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); textAlign = Paint.Align.CENTER }
        private val tile = RectF()
        private val clip = Path()
        private val keep = name == "Keep existing paper"
        private val paper = if (keep) PaperStyle(PaperPattern.RULED) else paper
        // P639: the name grows with the reader's text size, and the tile grows under the paper to hold it.
        private val most = context.readerSize(13f, 18f)
        private val grow = (most - dp(13f)) * 1.25f
        var on = selected; set(value) { field = value; describe(); invalidate() }
        init { isClickable = true; isFocusable = true; foreground = ripple(night, dp(14f)); describe() }
        // P638: "Keep existing paper" already says paper; a screen reader read "Keep existing paper paper".
        private fun describe() { contentDescription = (if (keep) name else "$name paper") + if (on) ", selected" else "" }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dpi(136f) + kotlin.math.ceil(grow).toInt())
        override fun onDraw(canvas: Canvas) {
            val u = dp(1f)
            tile.set(5 * u, 5 * u, width - 5 * u, 101 * u)
            clip.reset(); clip.addRoundRect(tile, 14 * u, 14 * u, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            PaperArt.closeUp(canvas, tile, paper, paint, u)
            if (keep) {
                // Edit notebook's "Keep existing paper": ruled and grid halves, the pages' own papers left as they are.
                canvas.clipPath(Path().apply { moveTo(tile.right, tile.top); lineTo(tile.right, tile.bottom); lineTo(tile.left, tile.bottom); close() })
                PaperArt.closeUp(canvas, tile, KEEP_GRID, paint, u)
            }
            canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = if (night) 0x33ffffff else 0xffe3e7ee.toInt()
            canvas.drawRoundRect(tile, 14 * u, 14 * u, paint)
            if (on) {
                paint.strokeWidth = 2.5f * u; paint.color = Glass.accent(night)
                canvas.drawRoundRect(tile.left - 3.75f * u, tile.top - 3.75f * u, tile.right + 3.75f * u, tile.bottom + 3.75f * u, 17.75f * u, 17.75f * u, paint)
            }
            paint.style = Paint.Style.FILL
            label.typeface = if (on) Kit.Type.TITLE.typeface else Tokens.SANS_MEDIUM
            label.color = if (on) Kit.accentText(night) else Glass.text(night)
            label.fitText(name, width - 4f * u, most, dp(13f))
            canvas.drawText(TextUtils.ellipsize(name, label, width - 4f * u, TextUtils.TruncateAt.END).toString(), width / 2f, 126 * u + grow, label)
        }
    }
    /** B50: the folder, tag or Archived view and the scroll position, so returning from a notebook lands where it was opened. */
    data class Place(val folder: String?, val tag: String?, val archived: Boolean, val position: Int, val offset: Int)
    /** The current place, or null in Trash or while selecting (those are not returned to). */
    fun place(): Place? {
        if (showingTrash || selecting) return null
        val first = catalogueLayout.findFirstVisibleItemPosition()
        val top = catalogueLayout.findViewByPosition(first)?.top?.minus(scroll.paddingTop) ?: 0
        return Place(openFolder, openTag, showingArchived, first.coerceAtLeast(0), if (first < 0) 0 else top)
    }
    /** B53 checks: grid columns in use, and the laid-out width of the first notebook tile (0 when none is shown). */
    internal val gridColumns get() = catalogueLayout.spanCount
    internal val firstTileWidth get() = catalogueLayout.findViewByPosition(1)?.width ?: 0
    /** Returns to [place]; a folder or tag that no longer exists falls back to All without scrolling. */
    fun returnTo(place: Place) {
        if (place.archived) { if (!showArchived(true)) return }
        else {
            if (!showFolder(place.folder)) return
            if (place.tag != null && !showTag(place.tag)) return
        }
        if (place.position > 0 || place.offset != 0) catalogueLayout.scrollToPositionWithOffset(place.position, place.offset)
    }
    /** A shared notebook always enters the root Library, independent of previous filters. */
    internal fun showSharedImports() {
        endSelecting();showTrash(false);showFolder(null);showTag(null)
        setQuery("");setModifiedFilter(NotebookModifiedFilter.ALL)
    }
    /** Opens a folder (null: All); false in Trash or for an unknown folder. Its subfolders' notebooks are shown too. */
    fun showFolder(name: String?): Boolean {
        if (showingTrash || (name != null && name !in folderNames)) return false
        val fromArchive = showingArchived
        showingArchived = false
        if (name == openFolder) { if (fromArchive) { buildFolderRow(); refresh() }; return true }
        openFolder = name; catalogueLayout.scrollToPositionWithOffset(0, 0)
        buildFolderRow(); refresh()
        folderChips[name]?.let { chip -> folderStrip.post { folderStrip.requestChildRectangleOnScreen(folderRow, android.graphics.Rect(chip.left, 0, chip.right, chip.height), false) } }
        return true
    }

    /**
     * A trimmed, single-line folder name of 1–60 characters without "/", not used by a sibling under [parent] (ignoring
     * case) other than [except], or null.
     */
    private fun folderName(text: String, parent: String?, except: String? = null): String? = text.trim().takeIf { name ->
        name.length in 1..MAX_FOLDER_NAME && name.none { it.isISOControl() || it == '/' } &&
            folderNames.none { it != except && parentOf(it) == parent && leafOf(it).equals(name, ignoreCase = true) }
    }

    /** The folder's subfolders, at any depth. */
    fun subfoldersOf(path: String): List<String> = folderNames.filter { it != path && isWithin(it, path) }

    /**
     * Adds an empty folder inside [parent] (null: top level); false for an invalid or duplicate name, an unknown parent,
     * a parent already [MAX_FOLDER_DEPTH] deep, in Trash or at the folder limit.
     */
    fun createFolder(text: String, parent: String? = null): Boolean {
        if (showingTrash || folderNames.size >= MAX_FOLDERS) return false
        if (parent != null && (parent !in folderNames || ancestry(parent).size >= MAX_FOLDER_DEPTH)) return false
        val name = folderName(text, parent) ?: return false
        folderNames += pathOf(parent, name); folderNames.sortWith(PATH_ORDER)
        saveFolders(); buildFolderRow(); refresh()
        return true
    }

    /** Renames a folder, keeping its notebooks and subfolders; false for an unknown folder or an invalid or duplicate name. */
    fun renameFolder(from: String, text: String): Boolean {
        if (showingTrash || from !in folderNames) return false
        val parent = parentOf(from)
        val name = folderName(text, parent, except = from) ?: return false
        val to = pathOf(parent, name)
        if (to == from) return true
        fun moved(path: String) = if (isWithin(path, from)) to + path.substring(from.length) else path
        folderNames.replaceAll { moved(it) }; folderNames.sortWith(PATH_ORDER)
        filed.replaceAll { _, folder -> moved(folder) }
        folderColors.entries.toList().forEach { (path, key) -> if (isWithin(path, from)) { folderColors -= path; folderColors[moved(path)] = key } }
        openFolder = openFolder?.let { moved(it) }
        saveFolders(); buildFolderRow(); shownIdentities = null; refresh()
        return true
    }

    /**
     * Removes a folder and its subfolders. Their notebooks stay in the library, moving up into the removed folder's parent
     * (unfiled at the top level); nothing is deleted. False for an unknown folder.
     */
    fun deleteFolder(name: String): Boolean {
        if (showingTrash || name !in folderNames) return false
        val parent = parentOf(name)
        folderNames.removeAll { isWithin(it, name) }
        folderColors.keys.removeAll { isWithin(it, name) }
        filed.keys.filter { isWithin(filed[it]!!, name) }.forEach { id -> if (parent == null) filed -= id else filed[id] = parent }
        if (openFolder?.let { isWithin(it, name) } == true) openFolder = parent
        saveFolders(); buildFolderRow(); shownIdentities = null; refresh()
        return true
    }

    // ---------------------------------------------------------------------------------------------------------------
    // P608: hold a notebook and drop it on a folder (the design canvas's Library-Drag board). The held notebook lifts
    // (tilted, a little larger, a deep shadow), its place shows a dashed outline, and a dark hint pill says what to do.
    // A folder tile, or a folder in the side list, lights up under the finger; letting go there files the notebook.
    // Letting go anywhere else slides it back; letting go without moving opens its menu, as a hold always did.

    private var touchDown = false
    private var touchX = 0f
    private var touchY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val here = IntArray(2)
    private val there = IntArray(2)

    private inner class Lift(val id: String, var cover: Cover, val from: String?, val startX: Float, val startY: Float) {
        var off = false
        var ghost: View? = null
        var hint: View? = null
        var outline: Path? = null
        var grabX = 0f
        var grabY = 0f
        var target: String? = null
    }
    private var lift: Lift? = null
    /** P608: the notebook being held for a drag, or null. */
    val liftedId: String? get() = lift?.id
    /** P608: true once the held notebook has left its place and follows the finger. */
    val lifting: Boolean get() = lift?.off == true
    /** P608: the folder the held notebook would be filed in if let go now, or null. */
    val liftTarget: String? get() = lift?.target
    /** P608: the floating copy of the held notebook while it follows the finger (for tests and screenshots). */
    val liftGhost: View? get() = lift?.ghost

    /** Room around the lifted copy for its shadow. */
    private val ghostMargin get() = dpi(40f)

    private fun rectOf(view: View): RectF {
        getLocationInWindow(here); view.getLocationInWindow(there)
        val left = (there[0] - here[0]).toFloat(); val top = (there[1] - here[1]).toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchDown = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchDown = false
        }
        touchX = event.x; touchY = event.y
        val held = lift ?: return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!held.off && hypot(event.x - held.startX, event.y - held.startY) > slop) {
                    // The tile under the finger lets go of its touch; from here this screen follows the finger.
                    MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(it); it.recycle() }
                    liftOff(held)
                }
                if (held.off) follow(held) else super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                if (held.off) drop(held)
                else {
                    super.dispatchTouchEvent(event)
                    lift = null; held.cover.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    showActions(held.id, held.cover)
                }
            }
            MotionEvent.ACTION_CANCEL -> { if (!held.off) super.dispatchTouchEvent(event); settle(held, null) }
            else -> if (!held.off) super.dispatchTouchEvent(event)
        }
        return true
    }

    /** Starts holding notebook [id] when a finger is down on its tile; false (the caller opens the menu) otherwise. */
    private fun beginLift(id: String, tile: Tile): Boolean {
        if (!touchDown || lift != null || selecting || showingTrash || folderNames.isEmpty() || canAct(id) == null) return false
        if (openMenu != null || renameDialog != null || trashDialog != null || !noDialogOpen()) return false
        tile.parent?.requestDisallowInterceptTouchEvent(true)
        tile.cover.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
        lift = Lift(id, tile.cover, filed[id], touchX, touchY)
        return true
    }

    /** The held notebook leaves its place: a copy follows the finger and the hint appears. */
    private fun liftOff(held: Lift) {
        val cover = held.cover
        cover.animate().cancel(); cover.scaleX = 1f; cover.scaleY = 1f
        if (cover.width == 0 || cover.height == 0 || !cover.isAttachedToWindow) { settle(held, null, animate = false); return }
        held.off = true
        val at = rectOf(cover); val m = ghostMargin
        val shot = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888)
        cover.draw(Canvas(shot))
        val outline = Path(cover.outline)
        held.outline = outline
        held.grabX = held.startX - at.left; held.grabY = held.startY - at.top
        val ghost = Ghost(context, shot, outline, m)
        addView(ghost, LayoutParams(cover.width + 2 * m, cover.height + 2 * m))
        ghost.translationX = at.left - m; ghost.translationY = at.top - m
        ghost.pivotX = m + held.grabX; ghost.pivotY = m + held.grabY
        ghost.animate().scaleX(1.06f).scaleY(1.06f).rotation(-3f).setDuration(160).start()
        held.ghost = ghost
        cover.liftedOut = outline
        val hint = liftHint()
        val area = rectOf(scroll)
        addView(hint, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44f)))
        hint.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dpi(44f), MeasureSpec.EXACTLY))
        hint.translationX = area.centerX() - hint.measuredWidth / 2f
        // P609: a phone has no free band above the grid (the first folder tile sits there), so the pill waits at the foot.
        hint.translationY = if (compact) min(area.bottom, height.toFloat()) - dp(44f) - dp(28f) else area.top + dp(12f)
        hint.alpha = 0f; hint.animate().alpha(1f).setDuration(160).start()
        held.hint = hint
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** "Drop on a folder to move it": the board's dark pill with a folder-arrow icon. */
    private fun liftHint() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(18f), 0, dpi(18f), 0)
        background = rounded(if (night) 0xff3a4252.toInt() else 0xff1d2433.toInt(), dp(22f))
        elevation = dp(30f); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply { setImageDrawable(Kit.Icon(MOVE_HINT_ICON, Color.WHITE, dpi(18f))) }, LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
        addView(TextView(context).apply {
            text = "Drop on a folder to move it"; setTextColor(Color.WHITE); textSize = 14f; typeface = Tokens.SANS_MEDIUM; maxLines = 1
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(10f) })
    }

    /** Moves the lifted copy with the finger, lights the folder under it and scrolls near the grid's edges. */
    private fun follow(held: Lift, edges: Boolean = true) {
        val ghost = held.ghost ?: return
        ghost.translationX = touchX - held.grabX - ghostMargin; ghost.translationY = touchY - held.grabY - ghostMargin
        val target = folderAt(touchX, touchY, held)
        if (target != held.target) {
            held.target?.let { old -> folderTiles[old]?.dropTarget = false; folderRows[old]?.dropTarget = false }
            target?.let { now -> folderTiles[now]?.dropTarget = true; folderRows[now]?.dropTarget = true; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            held.target = target
        }
        if (!edges) return
        val area = rectOf(scroll); val edge = dp(72f)
        val pull = touchY < area.top + edge || touchY > area.bottom - edge
        if (pull && area.contains(touchX, touchY)) { removeCallbacks(edgeScroll); postOnAnimation(edgeScroll) }
    }

    private val edgeScroll = object : Runnable {
        override fun run() {
            val held = lift?.takeIf { it.off } ?: return
            val area = rectOf(scroll); val edge = dp(72f)
            if (!area.contains(touchX, touchY)) return
            val step = when {
                touchY < area.top + edge -> -((area.top + edge - touchY) / edge * dp(18f)).coerceAtLeast(dp(2f))
                touchY > area.bottom - edge -> ((touchY - (area.bottom - edge)) / edge * dp(18f)).coerceAtLeast(dp(2f))
                else -> return
            }
            if (!scroll.canScrollVertically(if (step < 0) -1 else 1)) return
            scroll.scrollBy(0, step.toInt())
            follow(held, edges = false)
            postOnAnimation(this)
        }
    }

    /** The folder (a grid tile, or a row of the side list) under ([x], [y]), other than the one the notebook is in. */
    private fun folderAt(x: Float, y: Float, held: Lift): String? {
        if (rectOf(scroll).contains(x, y)) folderTiles.forEach { (path, tile) ->
            if (tile.isAttachedToWindow && tile.isShown && rectOf(tile).contains(x, y)) return path.takeIf { it != held.from }
        }
        if (side.isShown) folderRows.forEach { (path, row) ->
            if (row.isShown && rectOf(row).contains(x, y)) return path.takeIf { it != held.from }
        }
        return null
    }

    /** Letting go: into the lit folder (the copy shrinks into it), or back to the notebook's place. */
    private fun drop(held: Lift) {
        val target = held.target
        // The copy flies into what is under the finger: the side list's row, or else the grid tile's picture.
        val into = target?.let { path ->
            folderRows[path]?.takeIf { it.isShown }?.let { rectOf(it) }?.takeIf { it.contains(touchX, touchY) }
                ?: folderTiles[path]?.takeIf { it.isShown }?.let { rectOf(it.art) }
                ?: folderRows[path]?.takeIf { it.isShown }?.let { rectOf(it) }
        }
        if (target != null && into != null && moveToFolder(held.id, target)) settle(held, into)
        else settle(held, null)
    }

    /** Ends a hold: the copy flies [into] a folder's place and fades, or (null) slides back where the notebook is. */
    private fun settle(held: Lift, into: RectF?, animate: Boolean = true) {
        if (lift === held) lift = null
        removeCallbacks(edgeScroll)
        held.target?.let { old -> folderTiles[old]?.dropTarget = false; folderRows[old]?.dropTarget = false }
        held.cover.animate().cancel(); held.cover.scaleX = 1f; held.cover.scaleY = 1f
        held.hint?.let { hint -> if (animate) hint.animate().alpha(0f).setDuration(140).withEndAction { removeView(hint) }.start() else removeView(hint) }
        val ghost = held.ghost
        val finish = { ghost?.let { removeView(it) }; held.cover.liftedOut = null }
        if (ghost == null || !animate) { finish(); return }
        val m = ghostMargin; val home = held.cover.takeIf { it.isAttachedToWindow && it.isShown }?.let { rectOf(it) }
        when {
            into != null -> {
                ghost.pivotX = ghost.width / 2f; ghost.pivotY = ghost.height / 2f
                ghost.animate().translationX(into.centerX() - ghost.width / 2f).translationY(into.centerY() - ghost.height / 2f)
                    .scaleX(.3f).scaleY(.3f).rotation(0f).alpha(0f).setDuration(220).withEndAction(finish).start()
            }
            home != null -> ghost.animate().translationX(home.left - m).translationY(home.top - m).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(220).withEndAction(finish).start()
            else -> ghost.animate().alpha(0f).setDuration(160).withEndAction(finish).start()
        }
    }

    /** The lifted copy: the cover as it was drawn, over a deep soft shadow in the book's own shape. */
    private class Ghost(context: Context, private val shot: Bitmap, private val outline: Path, private val margin: Int) : View(context) {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(dp(24f), 0f, dp(14f), 0x47141b26) }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null); elevation = dp(30f); outlineProvider = null; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            canvas.translate(margin.toFloat(), margin.toFloat())
            canvas.drawPath(outline, shadow)
            canvas.drawBitmap(shot, 0f, 0f, null)
        }
    }

    /** Files a readable library notebook in a folder, or unfiles it (null); false when refused. */
    fun moveToFolder(id: String, folder: String?): Boolean {
        if (showingTrash || (folder != null && folder !in folderNames) || entriesSource().none { it.id == id && it.error == null }) return false
        if (filed[id] == folder) return true
        if (folder == null) filed -= id else filed[id] = folder
        saveFolders(); shownIdentities = null; refresh()
        return true
    }

    private fun saveFolders() {
        preferences.edit().putStringSet(FOLDERS_KEY, folderNames.toSet())
            .putStringSet(FOLDER_OF_KEY, filed.map { (id, folder) -> "$id\n$folder" }.toSet())
            .putStringSet(FOLDER_COLORS_KEY, folderColors.map { (path, key) -> "$path\n$key" }.toSet()).apply()
    }

    // Checked by showing state: a sheet that launched this dialog is already dismissed but its listener may not have run yet.
    private fun noDialogOpen() = folderChooser?.isShowing!=true && selectedStorageSheet?.dialog?.isShowing!=true && storagePanel?.isShowing!=true && emptyTrashSheet?.dialog?.isShowing!=true && listOf(renameDialog, trashDialog, folderDialog, coverDialog, selectDialog, tagsDialog, newNotebookDialog).none { it?.isShowing == true }

    fun confirmEmptyTrash():Boolean {
        if(!showingTrash || !attached || !noDialogOpen() || !repository.canChangeNotebook || trashSource().isEmpty()) return false
        val sheet=Kit.Sheet(context,"Empty Trash?")
        val detail=Kit.text(context,"Checking the notebooks in Trash…",secondary=true)
        var confirmed:Map<String,String>?=null
        val remove=Kit.button(context,"Delete forever",Kit.Button.DANGER) {
            confirmed?.let { if(showingTrash && repository.emptyTrash(it)) sheet.dismiss() }
        }.apply { isEnabled=false;contentDescription="Confirm empty trash" }
        sheet.add(detail);sheet.add(remove)
        emptyTrashSheet=sheet
        sheet.dialog.setOnDismissListener { if(emptyTrashSheet===sheet) emptyTrashSheet=null }
        sheet.show()
        if(!repository.prepareEmptyTrash { result ->
            if(emptyTrashSheet===sheet && sheet.dialog.isShowing && attached && showingTrash) {
                result.onSuccess { snapshot ->
                    confirmed=snapshot
                    detail.text="Permanently delete ${snapshot.size} ${if(snapshot.size==1) "notebook" else "notebooks"} from Trash? This cannot be undone. Notebooks in the library stay unchanged. Deletion records are kept for the next sync."
                    remove.isEnabled=snapshot.isNotEmpty()
                }.onFailure { detail.text="Cannot check Trash. Nothing was deleted. ${it.message}" }
            }
        }) { sheet.dismiss();return false }
        return true
    }

    /** Chooser for a notebook's folder: No folder, each folder as a path (the current one checked) and New folder…. */
    fun showMoveToFolder(id: String): Boolean {
        val entry = canAct(id) ?: return false
        if (!noDialogOpen()) return false
        showFolderChooser("Move to folder", filed[id], true, { moveToFolder(id, it) }, entry.title) { showNewFolder(moveId = id) }
        return true
    }

    /** P534: each folder as its tile, in its own colour. [marked] says whether [current] is shown as where the notebook is now. */
    private fun showFolderChooser(title: String, current: String?, marked: Boolean, move: (String?) -> Unit, subtitle: String? = null, newFolder: () -> Unit) {
        val icon = { d: android.graphics.drawable.Drawable -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER; setImageDrawable(d) } }
        val top = PictureChooser.Item("No folder", if (marked && current == null) "Here now" else "In the Library", "No folder", marked && current == null,
            art = { icon(Kit.Icon(Kit.Paths.DOCUMENT, Glass.secondary(context.night), dpi(30f))) })
        val folders = folderNames.toList()
        val tiles = folders.map { path ->
            val here = marked && path == current
            val holds = notebooks(filed.values.count { it == path })
            val inside = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "In ${shownPath(it)}" }
            PictureChooser.Item(leafOf(path), if (here) "Here now" else inside ?: holds, "Folder ${shownPath(path)}, $holds", here,
                art = { FolderArt(context).apply { design = COVER_DESIGNS[folderColors[path] ?: DEFAULT_FOLDER]!! } })
        }
        val add = PictureChooser.Item("New folder…", null, "New folder", art = { icon(Kit.Icon(Glyph.PLUS, Glass.accent(context.night), dpi(30f))) })
        folderChooser = PictureChooser(context, title, listOf(top) + tiles + add, onDismiss = { folderChooser = null }, subtitle = subtitle) { which ->
            if (which <= folders.size) move(if (which == 0) null else folders[which - 1]) else main.post { newFolder() }
        }.show()
    }

    /**
     * Asks for a folder name. The folder is made inside [parent] (by default the open folder, or the top level when
     * moving); it then opens, or receives [moveId] (or the ticked notebooks, [moveSelected]).
     */
    fun showNewFolder(moveId: String? = null, moveSelected: Boolean = false, parent: String? = if (moveId == null && !moveSelected) openFolder else null): Boolean {
        if (showingTrash || !noDialogOpen() || folderNames.size >= MAX_FOLDERS) return false
        if (parent != null && (parent !in folderNames || ancestry(parent).size >= MAX_FOLDER_DEPTH)) return false
        folderDialog = nameDialog(if (parent == null) "New folder" else "New folder in “${leafOf(parent)}”", "", "Create", { folderName(it, parent) != null }) { text ->
            if (!createFolder(text, parent)) return@nameDialog false
            val name = pathOf(parent, text.trim())
            when { moveSelected -> moveSelectedToFolder(name); moveId != null -> moveToFolder(moveId, name); else -> showFolder(name) }
            true
        }
        return true
    }

    /**
     * B18 (P136): a folder's options beside its tile (its ⋮ button or a touch and hold): Open, Rename…, Colour…,
     * New folder inside… (until [MAX_FOLDER_DEPTH]) and Delete folder….
     */
    fun showFolderActions(name: String, anchor: View? = null): Boolean {
        if (showingTrash || name !in folderNames || openMenu != null || !noDialogOpen()) return false
        val at = anchor ?: folderTiles[name]?.art ?: folderChips[name] ?: return false
        val deep = ancestry(name).size >= MAX_FOLDER_DEPTH
        val rows = mutableListOf<Pair<String, () -> Unit>?>(
            "Open" to { showFolder(name); Unit },
            "Rename…" to { folderDialog = nameDialog("Rename folder", leafOf(name), "Rename", { folderName(it, parentOf(name), except = name) != null }) { renameFolder(name, it) } },
            "Colour…" to { showFolderColors(name); Unit },
        )
        if (!deep) rows += "New folder inside…" to { showNewFolder(parent = name); Unit }
        rows += null; rows += "Delete folder…" to { confirmDeleteFolder(name) }
        folderMenu = sideMenu(at, shownPath(name), rows) { if (folderMenu === it) folderMenu = null }
        return true
    }

    /** B18 (P136): gives a folder one of the notebook cover colours, or the default back (null); false when refused. */
    fun setFolderColor(path: String, key: String?): Boolean {
        if (showingTrash || path !in folderNames || (key != null && key !in COVER_DESIGNS)) return false
        if (folderColors[path] == key) return true
        if (key == null) folderColors -= path else folderColors[path] = key
        saveFolders(); refresh()
        return true
    }

    /** The folder colour chooser: the default, then a small folder in each cover colour; the current one is ringed. */
    fun showFolderColors(path: String): Boolean {
        if (showingTrash || path !in folderNames || !noDialogOpen()) return false
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(4f)) }
        lateinit var dialog: AlertDialog
        (listOf<String?>(null) + COVER_DESIGNS.keys).forEach { key ->
            row.addView(FolderArt(context).apply {
                design = COVER_DESIGNS[key ?: DEFAULT_FOLDER]; label = COVER_DESIGNS[key]?.name ?: "Default"; ringed = folderColors[path] == key
                contentDescription = "$label folder" + if (ringed) ", selected" else ""
                isClickable = true; isFocusable = true; foreground = ripple(night, dp(10f))
                setOnClickListener { setFolderColor(path, key); dialog.dismiss() }
            }, LinearLayout.LayoutParams(dpi(84f), dpi(96f)).apply { marginEnd = dpi(4f) })
        }
        dialog = AlertDialog.Builder(context).setTitle("Colour for “${leafOf(path)}”")
            .setView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (folderDialog === it) folderDialog = null }
            .show()
        folderDialog = dialog
        return true
    }

    private fun confirmDeleteFolder(name: String) {
        if (name !in folderNames || !noDialogOpen()) return
        val count = entriesSource().count { entry -> filed[entry.id]?.let { isWithin(it, name) } == true }
        val subfolders = subfoldersOf(name).size
        val where = parentOf(name)?.let { "move to “${leafOf(it)}”" } ?: "stay in the library"
        val notebooksPart = when (count) { 0 -> null; 1 -> "Its notebook will $where"; else -> "Its $count notebooks will $where" }
        val foldersPart = when (subfolders) { 0 -> null; 1 -> "its subfolder is removed too"; else -> "its $subfolders subfolders are removed too" }
        val message = when {
            notebooksPart != null && foldersPart != null -> "$notebooksPart; $foldersPart."
            notebooksPart != null -> "$notebooksPart; only the folder is removed."
            foldersPart != null -> "The folder has no notebooks; ${foldersPart}."
            else -> "The folder is empty."
        }
        folderDialog = AlertDialog.Builder(context).setTitle("Delete folder “${leafOf(name)}”?")
            .setMessage(message)
            .setPositiveButton("Delete folder") { _, _ -> deleteFolder(name) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (folderDialog === it) folderDialog = null }
            .show()
    }

    /** A single-line name dialog whose action button is enabled only while [valid] accepts the text. */
    private fun nameDialog(title: String, initial: String, action: String, valid: (String) -> Boolean, hint: String = "Folder name", accept: (String) -> Boolean): AlertDialog {
        val input = EditText(context).apply { setText(initial); setSingleLine(); selectAll(); this.hint = hint }
        val frame = FrameLayout(context).apply { setPadding(dpi(24f), dpi(8f), dpi(24f), 0); addView(input) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(frame)
            .setPositiveButton(action) { _, _ -> accept(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (folderDialog === it) folderDialog = null; if (tagsDialog === it) tagsDialog = null }
            .show()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun check() { button.isEnabled = valid(input.text.toString()) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { check() }
        })
        check()
        return dialog
    }

    internal fun isArchived(id: String) = id in archived

    internal fun setArchived(id: String, value: Boolean): Boolean {
        if (canAct(id) == null || selecting || !noDialogOpen()) return false
        if (value) archived += id else archived -= id
        preferences.edit().putStringSet(ARCHIVED_KEY, archived.toSet()).apply()
        note = if (value) "Notebook archived. Open Archived to return it to the library." else "Notebook returned to its original library folder."
        noteFault = false
        shownIdentities = null
        refresh()
        return true
    }

    /** An existing Library grid filter, not a separate notebook store. */
    internal fun showArchived(show: Boolean): Boolean {
        if (show == showingArchived && !showingTrash) return true
        if (!repository.canChangeNotebook || selecting || pending != null || storageRequest != null || storageBatch != null || batchTotal > 0 || !noDialogOpen()) return false
        if (showingTrash) showTrash(false)
        dismissMenus()
        showingArchived = show
        openFolder = null; openTag = null
        note = null; noteFault = false
        setQuery("")
        catalogueLayout.scrollToPositionWithOffset(0, 0)
        buildFolderRow(); refresh()
        return true
    }

    /** Whether a library notebook is pinned to the top. */
    fun isPinned(id: String): Boolean = id in pinned
    /** Pins or unpins a readable library notebook; remembered across launches. False for unknown or unreadable notebooks or in Trash. */
    fun setPinned(id: String, pin: Boolean): Boolean {
        if (showingTrash || entriesSource().none { it.id == id && it.error == null }) return false
        if (pin == (id in pinned)) return true
        if (pin) pinned += id else pinned -= id
        preferences.edit().putStringSet(PINNED_KEY, pinned.toSet()).apply()
        shownIdentities = null; refresh()
        return true
    }

    /** Asks for a new title, prefilled and selected; false when renaming is not possible now. */
    fun showRename(id: String): Boolean {
        val entry = canAct(id) ?: return false
        if (renameDialog != null || trashDialog != null) return false
        val input = EditText(context).apply { setText(entry.title); setSingleLine(); selectAll() }
        val frame = FrameLayout(context).apply { setPadding(dpi(24f), dpi(8f), dpi(24f), 0); addView(input) }
        renameDialog = AlertDialog.Builder(context).setTitle("Rename notebook").setView(frame)
            .setPositiveButton("Rename") { _, _ -> rename(id, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { renameDialog = null }
            .show()
        return true
    }

    /** Retitles a library notebook; false (nothing changes) for an empty or over-long title or when refused. */
    fun rename(id: String, title: String): Boolean {
        canAct(id) ?: return false
        if (title.trim().length !in 1..500) return false
        return repository.renameNotebook(id, title)
    }

    /** Writes a copy of a library notebook ("<title> copy"); it is not opened. False when refused. */
    fun duplicate(id: String): Boolean {
        canAct(id) ?: return false
        return repository.duplicateNotebook(id)
    }

    /**
     * F178: lists the versions sync kept of a notebook before replacing it, newest first; choosing one restores it as a new
     * notebook beside this one (nothing is replaced). False when that is not possible now.
     */
    fun showVersions(id: String): Boolean {
        val entry = canAct(id) ?: return false
        if (versionsDialog != null || renameDialog != null || trashDialog != null) return false
        repository.listVersions(id) { result ->
            if (!attached || versionsDialog != null) return@listVersions
            val versions = result.getOrDefault(emptyList())
            val builder = AlertDialog.Builder(context).setTitle("Earlier versions of “${entry.title}”")
                .setNegativeButton("Close", null).setOnDismissListener { versionsDialog = null }
            when {
                result.isFailure -> builder.setMessage("The kept versions cannot be read: ${result.exceptionOrNull()?.message}")
                versions.isEmpty() -> builder.setMessage(NO_VERSIONS)
                else -> builder.setItems(versions.map(::versionLabel).toTypedArray()) { _, which -> restoreVersion(id, versions[which]) }
            }
            versionsDialog = builder.show()
        }
        return true
    }

    private fun versionTime(time: Long) = EnglishTime.dateTime(context, time)
    /** One row of the versions list. */
    fun versionLabel(version: NotebookLibrary.Version): String =
        if (version.error != null) "${versionTime(version.time)} · cannot be read: ${version.error}"
        else "${versionTime(version.time)} · ${version.title} · ${if (version.pageCount == 1) "1 page" else "${version.pageCount} pages"}"

    /** Restores [version] of a library notebook as a new notebook, "<title> (version of <time>)"; it is not opened. */
    fun restoreVersion(id: String, version: NotebookLibrary.Version): Boolean {
        canAct(id) ?: return false
        if (version.error != null) return false
        note = null
        return repository.restoreVersion(id, version.time, "${version.title.take(460)} (version of ${versionTime(version.time)})")
    }
    /** Asks before moving a readable library notebook to the trash; false (no dialog) when that is not possible now. */
    fun confirmTrash(id: String): Boolean {
        val entry = entriesSource().firstOrNull { it.id == id } ?: return false
        if (showingTrash || entry.error != null || pending != null || !repository.canChangeNotebook || trashDialog != null) return false
        trashDialog = AlertDialog.Builder(context).setTitle("Move “${entry.title}” to the trash?")
            .setMessage("It leaves the library but is kept unchanged. You can restore it from Trash at any time.")
            .setPositiveButton("Move to trash") { _, _ -> moveToTrash(id) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { trashDialog = null }
            .show()
        return true
    }

    /** Moves a library notebook to the trash without asking; false (and nothing changes) when refused. */
    fun moveToTrash(id: String): Boolean {
        if (showingTrash || pending != null || batchTotal > 0 || entriesSource().none { it.id == id }) return false
        note = null
        return repository.trashNotebook(id)
    }

    /** Restores a trashed notebook to the library (it is not opened); false when refused. */
    fun restore(id: String): Boolean {
        if (!showingTrash || pending != null || trashSource().none { it.id == id }) return false
        note = null
        return repository.restoreNotebook(id)
    }

    private fun refresh() {
        if (!attached) return
        dateChip.visibility=if(showingTrash)GONE else VISIBLE
        if (!repository.loaded) {
            // Saved folders are available before notebook manifests. Do not present this partial list as an empty library.
            recentSection.visibility = GONE; notebooksLabel.visibility = GONE; storageLine.visibility = GONE
            empty.text = "Loading notebooks…"; empty.visibility = VISIBLE; message.visibility = GONE
            importButton.isEnabled = false; newButton.isEnabled = false; selectButton.isEnabled = false; trashButton.isEnabled = false
            return
        }
        notebooksLabel.visibility = VISIBLE
        val request = pending
        if (request != null && !repository.busy) {
            pending = null
            val now = repository.session?.notebook?.id
            val opened = if (request.targetId != null) now == request.targetId else now != null && now != request.fromId
            if (opened && request.targetId == null && request.folder != null && request.folder in folderNames) {
                filed[now!!] = request.folder; saveFolders()
            }
            if (opened && request.targetId == null && request.cover != null) {
                coverStyles[now!!] = request.cover
                preferences.edit().putStringSet(COVERS_KEY, coverStyles.map { (n, k) -> "$n\n$k" }.toSet()).apply()
            }
            if (opened) { onOpened(now!!); if (!attached) return }
        }
        openAfterSave?.takeIf { pending == null && (repository.canChangeNotebook || repository.canOpenSavedNotebook(it)) }?.let { id -> openAfterSave = null; note = null; if (open(id)) return }
        continueBatch()
        val all = if (showingTrash) trashSource() else entriesSource().filter { (it.id in archived) == showingArchived }
        val released = if (showingTrash) emptySet() else all.filter { !it.availableOffline }.map { it.id }.toSet()
        if (released != releasedCoverIds) {
            // Invalidate reads even if release and download both finish before an old callback arrives.
            releasedCoverIds = released; generation++; loading.clear()
        }
        // Released notebooks retain metadata, never a first-page package or a cached page preview here.
        all.filter { !showingTrash && !it.availableOffline }.forEach { entry ->
            covers.remove(entry.id); coverErrors.remove(entry.id)
            thumbnails.invalidate(entry.id)
            tiles[entry.id]?.cover?.release(); recentCards[entry.id]?.cover?.release()
        }
        // A tag whose last notebook went to the trash stops filtering.
        if (!showingTrash && openTag != null && tags.none { it == openTag }) openTag = null
        if (selecting) all.filter { it.error == null }.map { it.id }.toSet().let { readable -> selection.retainAll(readable) }
        val entries = shown(all)
        // Tiles are rebuilt only when identity, title, page count or error change; edit times update in place.
        val identities = entries.map { it.identity }
        // B18 (P136): the open folder's subfolders lead the grid while browsing; each shows its colour and what it holds.
        val readableAll = if (showingTrash) emptyList() else all.filter { it.error == null }
        val folders = if (browsingFolders) folderNames.filter { parentOf(it) == openFolder } else emptyList()
        val folderKey = folders.map { path ->
            val count = readableAll.count { entry -> filed[entry.id]?.let { isWithin(it, path) } == true }
            "$path\n${folderColors[path]}\n$count\n${folderNames.count { parentOf(it) == path }}"
        }
        if (identities != shownIdentities || folderKey != shownFolderKey) { rebuild(entries, folders, folderKey); shownIdentities = identities; shownFolderKey = folderKey }
        shownEntries = entries
        val now = System.currentTimeMillis()
        entries.forEach { tiles[it.id]?.showMeta(it, now) }
        val idle = pending == null && storageRequest == null && storageBatch == null && repository.canChangeNotebook && batchTotal == 0
        importButton.isEnabled = idle; newButton.isEnabled = idle; trashButton.isEnabled = idle || showingTrash
        selectButton.isEnabled = idle && entries.any { it.error == null }
        importButton.visibility = if (showingTrash || showingArchived || selecting) GONE else VISIBLE; newButton.visibility = importButton.visibility
        selectButton.visibility = if (showingTrash || selecting) GONE else VISIBLE; backupButton.visibility = importButton.visibility; trashButton.visibility = if (showingTrash) VISIBLE else GONE
        trashButton.contentDescription = if (showingTrash) "Back to library" else "Show trash"
        emptyTrashButton.visibility=if(showingTrash) VISIBLE else GONE
        emptyTrashButton.isEnabled=idle && trashSource().isNotEmpty()
        showFolders(); showSelection(entries)
        val library = if (showingTrash) entriesSource().filter { it.error == null } else readableAll
        showSide(idle); showRecent(library)
        title.text = when {
            showingTrash -> "Trash"
            selecting -> if (selection.isEmpty()) "Select notebooks" else "${selection.size} selected" +
                if (!selectionStoredLocally()) " · Some not downloaded" else ""
            showingArchived -> "Archived"
            openTag != null -> "#$openTag"
            openFolder != null -> leafOf(openFolder!!)
            else -> "All notebooks"
        }
        notebooksLabel.text = if (showingTrash) "In the trash" else if (showingArchived) "Archived notebooks" else "Notebooks"
        hint.visibility = if (showingTrash && all.isNotEmpty()) VISIBLE else GONE
        // P571: the header counts the place that is open (a folder with its subfolders, a tag), not the whole library.
        val here = readableAll.filter { entry ->
            openFolder.let { folder -> folder == null || filed[entry.id]?.let { isWithin(it, folder) } == true } &&
                openTag.let { tag -> tag == null || carries(entry.id, tag) }
        }
        val kept = here.count { it.availableOffline }
        val remote = here.size - kept
        storageLine.text = (if (kept == 1) "1 notebook · kept on this device" else "$kept notebooks · kept on this device") +
            (if (remote > 0) " · $remote not downloaded" else "")
        storageLine.visibility = if (showingTrash || !repository.loaded) GONE else VISIBLE
        empty.text = when {
            query.isNotEmpty() && all.isNotEmpty() -> "No notebooks match “$query”" + (openFolder?.takeIf { !showingTrash }?.let { " in ${leafOf(it)}." } ?: ".")
            !showingTrash && openTag != null -> "No notebooks tagged “$openTag”" + (openFolder?.let { " in ${leafOf(it)}." } ?: ".")
            !showingTrash && modifiedFilter!=NotebookModifiedFilter.ALL -> "No notebooks match ${modifiedFilter.label.lowercase()}. Choose All dates to clear the date filter."
            !showingTrash && openFolder != null -> "“${leafOf(openFolder!!)}” is empty. Use a notebook's ⋮ menu and choose Move to folder…, or create one here."
            all.isNotEmpty() -> "No notebooks match “$query”."
            showingArchived -> "No archived notebooks. Use a notebook’s ⋮ menu and choose Archive."
            showingTrash -> "The trash is empty. Use a notebook's ⋮ menu in the library to move it here."
            else -> "No notebooks yet. Create one or import a notebook."
        }
        empty.visibility = if (entries.isEmpty() && folders.isEmpty()) VISIBLE else GONE
        val failure = repository.status.takeIf { it.startsWith("Open failed") || it.startsWith("Cannot open library") }
        val outcome = repository.libraryMessage
        val progress = when {
            storageBatch != null -> storageBatch!!.let {
                if (it.release) "Verifying selected folder copies… ${it.ready} of ${it.ids.size} local copies freed"
                else "Downloading selected notebooks… ${it.ready} of ${it.ids.size} ready"
            }
            batchTotal > 0 -> "Moving to the trash… ${min(batchMoved + 1, batchTotal)} of $batchTotal"
            repository.busy && repository.status == "Importing…" -> "Importing…"
            repository.busy && repository.status in setOf("Downloading notebook…", "Verifying folder copy…", "Restoring folder access…") -> repository.status
            !repository.loaded -> "Loading library…"
            else -> null
        }
        val text = when {
            progress != null -> progress
            pending != null -> null
            failure != null -> failure
            note != null -> note
            outcome != null -> outcome
            else -> repository.warning
        }
        // Trash and restore successes are calm notes; only failures use the fault colour.
        val calm = text == progress || (text == note && !noteFault) ||
            (outcome != null && text == outcome && !outcome.startsWith("Could not") && !outcome.startsWith("Cannot"))
        message.setTextColor(if (calm) Glass.secondary(night) else faultInk(night))
        message.text = text ?: ""; message.visibility = if (text == null) GONE else VISIBLE
        val current = repository.session?.notebook
        if (!showingTrash) current?.takeIf { notebook -> all.any { it.id == notebook.id && it.availableOffline } }
            ?.let { notebook -> covers[notebook.id] = firstPage(notebook) }
        tiles.forEach { (id, tile) ->
            // Unreadable packages can still be restored unchanged, but never opened.
            tile.isEnabled = canTapNotebook(tile.entry)
            tile.cover.current = !showingTrash && id == current?.id
            tile.showTicked(selecting && !showingTrash, id in selection)
        }
        bindVisible()
    }

    /** Only materialized cells retain previews and first-page packages. The three recent cards are bounded separately. */
    private fun bindVisible() {
        if (!attached) return
        tiles.values.forEach { tile ->
            if (tile.isAttachedToWindow) tile.cover.bind() else tile.cover.release()
        }
        recentCards.values.forEach { it.cover.bind() }
        val retained = tiles.keys + recentCards.keys + listOfNotNull(repository.session?.notebook?.id)
        covers.keys.retainAll(retained.toSet())
    }
    private fun scheduleBind() { main.removeCallbacks(bindPass); main.post(bindPass) }

    private fun rebuild(entries: List<LibraryEntry>, folders: List<String>, folderKey: List<String>) {
        shownEntries = entries
        shownFolderPaths = folders
        shownFolderDetails = folderKey
        tiles.values.forEach { it.cover.release() }
        tiles.clear(); folderTiles.clear()
        catalogueAdapter.notifyDataSetChanged()
        // Covers of notebooks that are gone or no longer readable are dropped.
        covers.keys.retainAll((if (showingTrash) trashSource() else entriesSource()).filter { it.error == null && (showingTrash || it.availableOffline) }.map { it.id }.toSet())
    }


    private fun firstPage(notebook: Notebook): Notebook {
        val page = notebook.pages.first()
        return notebook.copy(pages = listOf(page), assets = notebook.assets.filter { it.id == page.pdfBackground?.assetId },
            imageAssets = notebook.imageAssets.filter { it.id in page.imageAssetIds }, attachmentAssets = notebook.attachmentAssets.filter { it.id in page.attachmentAssetIds })
    }

    private fun loadCoverOf(id: String) {
        val executor = loader ?: return
        val entry = (if (showingTrash) trashSource() else entriesSource()).firstOrNull { it.id == id } ?: return
        if (entry.error != null || (!showingTrash && !entry.availableOffline)) return
        if (id in covers || id in coverErrors || !loading.add(id)) return
        val from = generation
        val read = if (showingTrash) loadTrashCover else loadCover
        val task = runCatching {
            executor.execute {
                val result = runCatching { firstPage(read(id)) }
                main.post {
                    if (from != generation) return@post
                    loading.remove(id)
                    if (!attached) return@post
                    val latest = (if (showingTrash) trashSource() else entriesSource()).firstOrNull { it.id == id }
                    if (latest != null && latest.identity == entry.identity && (showingTrash || latest.availableOffline)) {
                        result.onSuccess { covers[id] = it }.onFailure { coverErrors[id] = it.message ?: "Cover unavailable" }
                    }
                    tiles[id]?.cover?.bind(); recentCards[id]?.cover?.bind()
                }
            }
        }
        if (task.isFailure) loading.remove(id)
    }

    /** The search field's magnifier, or its clear button (a soft disc with a cross) when [clear]. */
    private class SearchGlyph(context: Context, private val clear: Boolean) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        init { if (clear) { isClickable = true; isFocusable = true; foreground = ripple(night, dp(20f)) } }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val u = dp(1f)
            paint.strokeWidth = 1.8f * u
            if (clear) {
                paint.style = Paint.Style.FILL; paint.color = if (night) Kit.track(true) else Tokens.RULE; canvas.drawCircle(cx, cy, 10f * u, paint)
                paint.style = Paint.Style.STROKE; paint.color = if (night) Glass.TEXT_NIGHT else Tokens.INK; val d = 3.6f * u
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint); canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint)
            } else {
                paint.color = if (night) Glass.SECONDARY_NIGHT else Tokens.GRAPHITE; val x = cx + 2f * u; val r = 6f * u
                canvas.drawCircle(x - 1.5f * u, cy - 1.5f * u, r, paint)
                canvas.drawLine(x + 3f * u, cy + 3f * u, x + 7f * u, cy + 7f * u, paint)
            }
        }
    }

    /** How long ago an edit was, as a tile and a recent card say it ("just now", "3 hours ago", "yesterday"). */
    private fun editedAgo(modified: Long, now: Long): String =
        if (now - modified < DateUtils.MINUTE_IN_MILLIS) "just now"
        else EnglishTime.ago(modified, now)
            // "Yesterday" reads as part of the sentence; month names keep their capital.
            .let { if (now - modified < 2 * DateUtils.DAY_IN_MILLIS) it.replaceFirstChar { c -> c.lowercase() } else it }

    /** One notebook: cover, title and page count, or the reason it cannot be opened. */
    private inner class Tile(context: Context, val entry: LibraryEntry, private val trashed: Boolean, pinned: Boolean = false, style: String? = null, tags: List<String> = emptyList()) : LinearLayout(context) {
        val cover = Cover(context, entry).apply { this.trashed = trashed; this.pinned = pinned; this.style = style }
        /** The ⋮ options button of a readable library notebook; null in the trash or for an unreadable one. */
        val more = if (trashed || entry.error != null) null else MoreDots(context, "Options for ${entry.title}")
        private val meta = TextView(context)
        private var description = ""
        init {
            orientation = VERTICAL
            setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(12f))
            background = null; foreground = ripple(night, dp(14f))
            isClickable = true; isFocusable = true
            clipChildren = false // the cover's soft shadow reaches into the padding (P188)
            description = when {
                trashed -> "${entry.title}, in the trash, tap to restore"
                entry.error == null -> "${entry.title}, ${pages(entry.pageCount)}" + (if (!entry.availableOffline && !trashed) ", not downloaded" else "") + (if (tags.isEmpty()) "" else ", tagged ${tags.joinToString(", ")}") + if (pinned) ", pinned" else ""
                else -> "${entry.title}, cannot be opened: ${entry.error}"
            }
            contentDescription = description
            addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            // B17 (P136): the title shares its row with a ⋮ button, so the options are easy to find (holding still works).
            val titleRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
            titleRow.addView(TextView(context).apply {
                text = entry.title; setTextColor(if (entry.error == null) Glass.text(night) else Glass.secondary(night))
                textSize = 14f; typeface = Tokens.SANS_SEMIBOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; keepWordsWhole(leastShare = NAME_SHARE)
                setPadding(dpi(2f), dpi(4f), dpi(2f), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            more?.let { titleRow.addView(it, LinearLayout.LayoutParams(dpi(36f), dpi(34f)).apply { marginEnd = -dpi(8f) }) }
            addView(titleRow)
            if (tags.isNotEmpty() && entry.error == null) addView(TextView(context).apply {
                text = tags.joinToString("  ") { "#$it" }; setTextColor(if (night) Glass.SECONDARY_NIGHT else Tokens.INK_RULE)
                textSize = 12f; typeface = Tokens.SANS_MEDIUM; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(2f), dpi(3f), dpi(2f), 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(meta.apply {
                if (!entry.availableOffline && !trashed) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(Kit.Icon(Kit.Paths.CLOUD, Glass.secondary(night), dpi(16f)), null, null, null)
                    compoundDrawablePadding = dpi(4f)
                }
                text = when {
                    trashed && entry.error == null -> "${pages(entry.pageCount)} · Tap to restore"
                    trashed -> "Unreadable · Tap to restore unchanged"
                    !entry.availableOffline -> "${pages(entry.pageCount)} · Not downloaded"
                    else -> entry.error?.let { "Cannot open: $it" } ?: pages(entry.pageCount)
                }
                setTextColor(if (entry.error == null || trashed) Glass.secondary(night) else faultInk(night))
                textSize = 12f; typeface = Tokens.SANS; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(2f), dpi(2f), dpi(2f), 0)
            })
        }
        /** Adds the relative edit time to a readable library tile ("3 pages · Edited 2 hours ago"). */
        fun showMeta(current: LibraryEntry, now: Long) {
            if (trashed || entry.error != null || current.modified <= 0L) return
            meta.text = "${pages(current.pageCount)} · Edited ${editedAgo(current.modified, now)}" + if (!current.availableOffline) " · Not downloaded" else ""
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Covers share one portrait frame (A4-like) so the grid lines up whatever the page sizes.
            val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            (cover.layoutParams as LinearLayout.LayoutParams).height = (inner * 1.36f).toInt()
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        /** Shows a tick circle while [selecting], filled when [ticked]; the description says which. */
        fun showTicked(selecting: Boolean, ticked: Boolean) {
            if (cover.selecting == selecting && cover.ticked == ticked) return
            cover.selecting = selecting; cover.ticked = ticked; cover.invalidate()
            isSelected = selecting && ticked
            contentDescription = if (!selecting || entry.error != null) description else description + if (ticked) ", selected" else ", not selected"
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // Fading children against their desk background is equivalent to group alpha on this opaque desk,
            // without allocating an offscreen layer. Unreadable entries retain their normal fault appearance.
            if (!isEnabled && entry.error == null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), busyVeil)
        }
        private fun pages(count: Int) = if (count == 1) "1 page" else "$count pages"
    }

    /** Draws the first page as paper on the desk with a soft shadow; unreadable notebooks get a dashed fault outline. */
    private inner class Cover(context: Context, val entry: LibraryEntry) : View(context) {
        var current = false; set(value) { if (field != value) { field = value; invalidate() } }
        /** Trashed covers are drawn as set-aside paper: faded, with a restore badge. */
        var trashed = false
        /** Pinned covers carry an amber bookmark ribbon over the top edge. */
        var pinned = false
        /** A coloured book cover key (P045), drawn instead of the first-page preview, which is then never loaded. */
        var style: String? = null
        /** Centred in its space rather than standing at the bottom like a book on the shelf ("Continue writing" cards). */
        var centred = false
        /** While selecting (P046), a tick circle sits in the top-right corner, filled with a tick when [ticked]. */
        var selecting = false
        var ticked = false
        private val tick = android.graphics.Path()
        private val ribbon = android.graphics.Path()
        private val badge = RectF()
        private val arrow = android.graphics.Path()
        private var bitmap: Bitmap? = null
        val hasPreview get() = bitmap != null
        private var request: PageThumbnails.Request? = null
        private var requestedWidth = 0
        // Page snapshots are immutable and shared while unchanged, so identity tells whether the preview is current.
        private var requestedFor: Page? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); typeface = Tokens.SANS_MEDIUM; textAlign = Paint.Align.CENTER }
        private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        private val plate = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val page = RectF()
        private val shape = Path()
        private val source = RectF()
        private val ring = RectF()

        fun cancel() { request?.cancel(); request = null; requestedFor = null }
        /** P608: the book's outline as last drawn, in this view's coordinates (empty before the first draw). */
        val outline: Path get() = shape
        /** P608: while the notebook is lifted away, its place shows this outline dashed instead of the cover. */
        var liftedOut: Path? = null; set(value) { if (field !== value) { field = value; invalidate() } }
        private val gapDash = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
        /** Lets go of the preview while the cover is far from the screen; the thumbnail cache may still hold it. */
        fun release() { if (bitmap == null && request == null) return; cancel(); bitmap = null }

        fun bind() {
            if (entry.error != null || (!trashed && !entry.availableOffline)) { release(); return }
            if (width == 0) return
            if (style != null) { release(); return }
            val notebook = covers[entry.id] ?: run { loadCoverOf(entry.id); invalidate(); return }
            // Width rounded up to 32 px so similar tiles share cached previews.
            val target = min(PageThumbnails.MAX_EDGE, ((width + 31) / 32) * 32)
            if (notebook.pages[0] === requestedFor && target == requestedWidth) return
            cancel(); requestedFor = notebook.pages[0]; requestedWidth = target
            thumbnails.cached(notebook, notebook.pages[0].id, target)?.let { bitmap = it; invalidate(); return }
            request = thumbnails.request(notebook, notebook.pages[0].id, target) { result ->
                request = null
                result.onSuccess { bitmap = it }.onFailure { coverErrors[entry.id] = it.message ?: "Cover unavailable" }
                invalidate()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (w > 0) scheduleBind() }

        override fun onDraw(canvas: Canvas) {
            liftedOut?.let { gap ->
                paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = if (night) 0x0affffff else 0x081d2433
                canvas.drawPath(gap, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.pathEffect = gapDash
                paint.color = if (night) 0x47ffffff else 0x381d2433; canvas.drawPath(gap, paint); paint.pathEffect = null
                return
            }
            val image = bitmap
            val aspect = when {
                style != null -> BOOK_ASPECT
                image != null -> image.height.toFloat() / image.width
                else -> covers[entry.id]?.pages?.first()?.let { it.height / it.width } ?: 1.4142f
            }
            // P188: the bottom keeps room for the soft shadow the board's covers cast. A [centred] cover sits in the middle,
            // raised a little so its shadow balances it.
            val inset = dp(4f); val below = dp(12f)
            val maxW = width - 2 * inset; val maxH = if (centred) height - 2 * inset - dp(4f) else height - inset - below
            val w = min(maxW, maxH / aspect); val h = w * aspect
            val top = if (centred) (height - h) / 2 - dp(2f) else height - below - h
            page.set((width - w) / 2, top, (width + w) / 2, top + h)
            val radius = if (style != null) dp(5f) else dp(6f)
            if (entry.error != null) {
                paint.style = Paint.Style.FILL; paint.color = if (night) Glass.PANE_NIGHT else Tokens.SURFACE; canvas.drawRoundRect(page, radius, radius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = faultInk(night); paint.pathEffect = dash
                canvas.drawRoundRect(page, radius, radius, paint); paint.pathEffect = null
                text.color = faultInk(night)
                canvas.drawText("Unreadable", page.centerX(), page.centerY() - (text.descent() + text.ascent()) / 2, text)
                return
            }
            // P188: the board's soft, deep shadow under a tight contact shadow.
            paint.style = Paint.Style.FILL; paint.color = -1
            paint.setShadowLayer(dp(11f), 0f, dp(8f), 0x29101828); bookShape(page, radius, style != null); canvas.drawPath(shape, paint)
            paint.setShadowLayer(dp(1.5f), 0f, dp(1f), 0x2e101828); canvas.drawPath(shape, paint)
            paint.clearShadowLayer()
            val design = COVER_DESIGNS[style]
            if (design != null) {
                drawBookCover(canvas, page, design, paint)
                // P567: as on the New notebook board, so a notebook looks the same before and after it is made.
                val folder = filed[entry.id]?.let { leafOf(it) } ?: "Notebook"
                val year = java.util.Calendar.getInstance().apply { timeInMillis = entry.modified }.get(java.util.Calendar.YEAR)
                drawTitlePlate(canvas, page, entry.title, false, "${folder.uppercase()} · $year", paint, plate, ring, dp(6.5f))
            } else {
                paint.color = -1; canvas.drawRoundRect(page, radius, radius, paint)
                if (image != null) {
                    canvas.save(); canvas.clipRect(page)
                    source.set(page); canvas.drawBitmap(image, null, source, paint)
                    canvas.restore()
                } else coverErrors[entry.id]?.let {
                    text.color = Tokens.GRAPHITE
                    canvas.drawText("No preview", page.centerX(), page.centerY(), text)
                }
            }
            if (trashed) {
                // A desk-coloured veil, then an ink badge with a restore arrow in the cover's top-right corner.
                paint.style = Paint.Style.FILL; paint.color = if (night) (Kit.desk(true) and 0x00ffffff) or 0x73000000 else 0x73e4e8ec; canvas.drawPath(shape, paint)
                val size = dp(30f)
                badge.set(page.right - size - dp(8f), page.top + dp(8f), page.right - dp(8f), page.top + dp(8f) + size)
                paint.color = if (night) Kit.group(true) else Tokens.INK; canvas.drawOval(badge, paint)
                paint.style = Paint.Style.STROKE; paint.color = Tokens.ON_INK; paint.strokeWidth = dp(1.75f)
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                val c = dp(1f); val x = badge.left + (size - dp(24f)) / 2; val y = badge.top + (size - dp(24f)) / 2
                arrow.reset(); arrow.addArc(x + 6f * c, y + 6f * c, x + 18f * c, y + 18f * c, 200f, 290f)
                arrow.moveTo(x + 4.5f * c, y + 7f * c); arrow.lineTo(x + 6.2f * c, y + 11.6f * c); arrow.lineTo(x + 10.6f * c, y + 9.8f * c)
                canvas.drawPath(arrow, paint)
            }
            if (pinned) {
                // A bookmark ribbon tucked over the top edge, with a notched tail and a soft shadow.
                val u = dp(1f); val x = page.left + 16f * u; val w = 15f * u; val y = page.top - 3f * u; val h = 27f * u
                ribbon.reset(); ribbon.moveTo(x, y); ribbon.lineTo(x + w, y); ribbon.lineTo(x + w, y + h)
                ribbon.lineTo(x + w / 2, y + h - 5f * u); ribbon.lineTo(x, y + h); ribbon.close()
                paint.style = Paint.Style.FILL
                canvas.save(); canvas.translate(0f, 1.5f * u); paint.color = 0x30182636; canvas.drawPath(ribbon, paint); canvas.restore()
                paint.color = Tokens.AMBER; canvas.drawPath(ribbon, paint)
            }
            if (selecting && entry.error == null) drawTick(canvas, radius)
            paint.style = Paint.Style.STROKE
            if (current) { paint.color = Glass.accent(night); paint.strokeWidth = dp(2.5f); ring.set(page); ring.inset(-dp(3f), -dp(3f)); canvas.drawRoundRect(ring, radius + dp(3f), radius + dp(3f), paint) }
            else if (style == null) { paint.color = Glass.RULE; paint.strokeWidth = dp(1f); canvas.drawRoundRect(page, radius, radius, paint) }
        }

        /** The cover's outline in [shape]: a book's rounder fore-edge, or a page's even corners. */
        private fun bookShape(r: RectF, radius: Float, book: Boolean) {
            shape.reset()
            if (book) bookPath(shape, r) else shape.addRoundRect(r, radius, radius, Path.Direction.CW)
        }

        private fun drawTick(canvas: Canvas, radius: Float) {
            val u = dp(1f)
            if (ticked) {
                // A soft ink wash and an ink outline make the whole cover read as chosen, not just its corner.
                paint.style = Paint.Style.FILL; paint.color = 0x1f182636; canvas.drawPath(shape, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * u; paint.color = if (night) Glass.TEXT_NIGHT else Tokens.INK
                ring.set(page); ring.inset(-3f * u, -3f * u); canvas.drawRoundRect(ring, radius + 3f * u, radius + 3f * u, paint)
            }
            val r = 13f * u; val cx = page.right - r - 8f * u; val cy = page.top + r + 8f * u
            paint.style = Paint.Style.FILL
            paint.color = 0x33182636; canvas.drawCircle(cx, cy + u, r + .5f * u, paint)
            paint.color = if (ticked) Glass.accent(night) else if (night) 0xee2a2f3a.toInt() else 0xeeffffff.toInt(); canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            if (ticked) {
                paint.color = Tokens.ON_INK; paint.strokeWidth = 2.2f * u; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                tick.reset(); tick.moveTo(cx - 5.5f * u, cy + .5f * u); tick.lineTo(cx - 1.5f * u, cy + 4.5f * u); tick.lineTo(cx + 6f * u, cy - 4f * u)
                canvas.drawPath(tick, paint)
            } else { paint.color = Glass.secondary(night); paint.strokeWidth = 1.5f * u; canvas.drawCircle(cx, cy, r - .75f * u, paint) }
        }
    }

    /** B17 (P136): a "⋮" button, three dots stacked, beside a tile's title. */
    private class MoreDots(context: Context, label: String) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Glass.SECONDARY_NIGHT else Tokens.GRAPHITE }
        init { contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true; foreground = ripple(night, dp(16f)) }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val gap = dp(5.5f); val r = dp(2f)
            for (i in -1..1) canvas.drawCircle(cx, cy + i * gap, r, paint)
        }
    }

    /** P188: the header's pill buttons: glass with accent text, or (the one primary action) accent-filled with white text. */
    private class PillButton(context: Context, description: String, private val label: String, private val icon: PillIcon, private val filled: Boolean) : View(context) {
        private val night = context.night
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); typeface = Tokens.SANS_SEMIBOLD; color = if (filled) Color.WHITE else accentInk(night) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = text.color
        }
        private val path = Path()
        init {
            contentDescription = description; isClickable = true; isFocusable = true
            background = if (filled) rounded(Glass.accent(night), dp(23f)) else Glass.pane(context, night, 23f)
            foreground = ripple(filled || night, dp(23f)); elevation = dp(if (filled) 8f else 3f)
            if (filled) { outlineSpotShadowColor = Glass.accent(night); outlineAmbientShadowColor = Glass.accent(night) }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // P639: the label grows with the reader's text size, back down where the row has less room (never below 15 dp).
            val fixed = dp(16f) + dp(18f) + dp(8f) + dp(20f)
            val room = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - fixed
            text.fitText(label, room, context.readerSize(15f, 19f), dp(15f))
            val wide = fixed + text.measureText(label)
            setMeasuredDimension(resolveSize(wide.toInt(), widthMeasureSpec), resolveSize(dpi(46f), heightMeasureSpec))
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .45f }
        override fun onDraw(canvas: Canvas) {
            val s = dp(18f); val x = dp(16f); val y = (height - s) / 2; val c = s / 24f
            path.reset()
            when (icon) {
                PillIcon.PLUS -> { path.moveTo(x + 12 * c, y + 5 * c); path.lineTo(x + 12 * c, y + 19 * c); path.moveTo(x + 5 * c, y + 12 * c); path.lineTo(x + 19 * c, y + 12 * c) }
                PillIcon.IMPORT -> {
                    path.moveTo(x + 12 * c, y + 3.5f * c); path.lineTo(x + 12 * c, y + 14.5f * c)
                    path.moveTo(x + 7.5f * c, y + 10 * c); path.lineTo(x + 12 * c, y + 14.5f * c); path.lineTo(x + 16.5f * c, y + 10 * c)
                    path.moveTo(x + 4 * c, y + 15 * c); path.lineTo(x + 4 * c, y + 20 * c); path.lineTo(x + 20 * c, y + 20 * c); path.lineTo(x + 20 * c, y + 15 * c)
                }
                PillIcon.BACK -> { path.moveTo(x + 15 * c, y + 5 * c); path.lineTo(x + 8 * c, y + 12 * c); path.lineTo(x + 15 * c, y + 19 * c) }
            }
            canvas.drawPath(path, stroke)
            canvas.drawText(label, x + s + dp(8f), height / 2f - (text.descent() + text.ascent()) / 2, text)
        }
    }

    /**
     * P188: a side panel row: a line icon in the accent (a folder in its own colour), the name and an optional count. The
     * place that is open is accent-filled with white text, as on the board.
     */
    private class NavRow(context: Context, private val icon: NavIcon, private val label: String) : View(context) {
        private val night = context.night
        var iconColor = Glass.ACCENT; set(value) { field = value; invalidate() }
        private var count: Int? = null
        private var on = false
        /** P608: lit while a lifted notebook is over this folder's row: accent at 12% with a 2 dp ring, the name in the accent. */
        var dropTarget = false; set(value) { if (field != value) { field = value; invalidate() } }
        private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); typeface = Tokens.SANS_MEDIUM }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); typeface = Tokens.SANS_MEDIUM; textAlign = Paint.Align.RIGHT }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val path = Path()
        init { isClickable = true; isFocusable = true; foreground = ripple(night, dp(12f)); contentDescription = label }
        /** Shows [count] (none when null) and whether this is the open place; [name] leads the description. */
        fun show(count: Int?, on: Boolean, name: String) {
            if (this.count == count && this.on == on && contentDescription?.startsWith(name) == true) return
            this.count = count; this.on = on; isSelected = on
            contentDescription = name + (count?.let { ", $it" } ?: "") + if (on) ", selected" else ""
            invalidate()
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .45f }
        override fun onDraw(canvas: Canvas) {
            val lit = dropTarget
            if (lit) {
                fill.color = (Glass.accent(night) and 0xffffff) or 0x1f000000; box.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
                canvas.drawRoundRect(box, dp(12f), dp(12f), fill)
                stroke.color = Glass.accent(night); stroke.strokeWidth = dp(2f); canvas.drawRoundRect(box, dp(11f), dp(11f), stroke); stroke.strokeWidth = dp(1.8f)
            } else if (on) { fill.color = Glass.accent(night); box.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(box, dp(12f), dp(12f), fill) }
            val s = dp(20f); val x = dp(10f); val y = (height - s) / 2; val c = s / 24f
            val tint = when { lit -> accentInk(night); on -> Color.WHITE; icon == NavIcon.FOLDER -> if (night) seenAtNight(iconColor) else iconColor; else -> accentInk(night) }
            stroke.color = tint; fill.color = tint
            path.reset()
            when (icon) {
                NavIcon.ALL -> {
                    for (col in 0..1) for (row in 0..1) {
                        val l = x + (3.5f + col * 9.5f) * c; val t = y + (3.5f + row * 9.5f) * c
                        box.set(l, t, l + 7.5f * c, t + 7.5f * c); path.addRoundRect(box, 2f * c, 2f * c, Path.Direction.CW)
                    }
                    canvas.drawPath(path, stroke)
                }
                NavIcon.FOLDER -> {
                    path.moveTo(x + 2.5f * c, y + 7 * c); path.quadTo(x + 2.5f * c, y + 4.5f * c, x + 5 * c, y + 4.5f * c)
                    path.lineTo(x + 9.5f * c, y + 4.5f * c); path.lineTo(x + 11.5f * c, y + 7 * c); path.lineTo(x + 19 * c, y + 7 * c)
                    path.quadTo(x + 21.5f * c, y + 7 * c, x + 21.5f * c, y + 9.5f * c); path.lineTo(x + 21.5f * c, y + 17.5f * c)
                    path.quadTo(x + 21.5f * c, y + 20 * c, x + 19 * c, y + 20 * c); path.lineTo(x + 5 * c, y + 20 * c)
                    path.quadTo(x + 2.5f * c, y + 20 * c, x + 2.5f * c, y + 17.5f * c); path.close()
                    canvas.drawPath(path, fill)
                }
                NavIcon.TRASH -> {
                    path.moveTo(x + 4 * c, y + 6.5f * c); path.lineTo(x + 20 * c, y + 6.5f * c)
                    path.moveTo(x + 9 * c, y + 6.5f * c); path.lineTo(x + 9 * c, y + 4 * c); path.lineTo(x + 15 * c, y + 4 * c); path.lineTo(x + 15 * c, y + 6.5f * c)
                    path.moveTo(x + 6 * c, y + 6.5f * c); path.lineTo(x + 7 * c, y + 20 * c); path.lineTo(x + 17 * c, y + 20 * c); path.lineTo(x + 18 * c, y + 6.5f * c)
                    path.moveTo(x + 10 * c, y + 10.5f * c); path.lineTo(x + 10 * c, y + 16 * c); path.moveTo(x + 14 * c, y + 10.5f * c); path.lineTo(x + 14 * c, y + 16 * c)
                    canvas.drawPath(path, stroke)
                }
                NavIcon.MORE -> for (i in 0..2) canvas.drawCircle(x + (6f + 6f * i) * c, y + 12 * c, 1.7f * c, fill)
                NavIcon.GESTURES -> {
                    // P569: two fingertips and the way they move.
                    canvas.drawCircle(x + 8.5f * c, y + 7.5f * c, 2.6f * c, stroke); canvas.drawCircle(x + 15.5f * c, y + 7.5f * c, 2.6f * c, stroke)
                    path.moveTo(x + 4.5f * c, y + 16.5f * c); path.lineTo(x + 19.5f * c, y + 16.5f * c)
                    path.moveTo(x + 7.5f * c, y + 13.5f * c); path.lineTo(x + 4.5f * c, y + 16.5f * c); path.lineTo(x + 7.5f * c, y + 19.5f * c)
                    path.moveTo(x + 16.5f * c, y + 13.5f * c); path.lineTo(x + 19.5f * c, y + 16.5f * c); path.lineTo(x + 16.5f * c, y + 19.5f * c)
                    canvas.drawPath(path, stroke)
                }
                NavIcon.SETTINGS -> {
                    // A gear: a ring with eight short teeth around a small hub.
                    val cx = x + 12 * c; val cy = y + 12 * c
                    canvas.drawCircle(cx, cy, 6.6f * c, stroke); canvas.drawCircle(cx, cy, 2.4f * c, stroke)
                    for (i in 0 until 8) {
                        val a = Math.PI * i / 4; val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                        path.moveTo(cx + 6.6f * c * dx, cy + 6.6f * c * dy); path.lineTo(cx + 9.6f * c * dx, cy + 9.6f * c * dy)
                    }
                    canvas.drawPath(path, stroke)
                }
            }
            val labelX = x + s + dp(12f); val right = width - dp(12f)
            val shownCount = count?.toString()
            number.color = if (on) 0xd9ffffff.toInt() else Glass.secondary(night)
            text.color = if (dropTarget) accentInk(night) else if (on) Color.WHITE else Glass.text(night)
            // P639: the name and count grow with the reader's text size as far as the row holds, the name back down to fit.
            number.textSize = context.readerSize(13f, 17f)
            val room = right - labelX - (shownCount?.let { number.measureText(it) + dp(8f) } ?: 0f)
            text.fitText(label, max(0f, room), context.readerSize(15f, 20f), dp(15f))
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2
            canvas.drawText(TextUtils.ellipsize(label, text, max(0f, room), TextUtils.TruncateAt.END).toString(), labelX, baseline, text)
            shownCount?.let { canvas.drawText(it, right, baseline, number) }
        }
    }

    /**
     * P188: a "Continue writing" card: the notebook's picture in a pale well, a bar in its cover colour, its name, page
     * count, edit time and folder, and an accent "Continue writing". Tapping it opens the notebook, as its tile does.
     * B29: the picture is the one its tile shows, the coloured book cover when it has one, else its first page.
     */
    private inner class RecentCard(context: Context, val entry: LibraryEntry, now: Long) : LinearLayout(context) {
        val cover = Cover(context, entry).apply { style = coverStyles[entry.id]; centred = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        init {
            orientation = HORIZONTAL
            background = rounded(Kit.cardFill(night), dp(22f)); elevation = dp(6f)
            outlineSpotShadowColor = 0xff101828.toInt(); outlineAmbientShadowColor = 0xff101828.toInt()
            foreground = ripple(night, dp(22f)); isClickable = true; isFocusable = true
            setPadding(dpi(10f), dpi(10f), dpi(16f), dpi(10f))
            contentDescription = "Continue writing ${entry.title}"
            // The user (21 October 2026): the book sat low and off centre in a tall well. The well now fits the book and is
            // centred in the card, with the book centred in it.
            val well = FrameLayout(context).apply { background = rounded(if (night) Kit.desk(true) else 0xfff4f6fa.toInt(), dp(14f)); dpi(6f).let { setPadding(it, it, it, it) }; clipChildren = false }
            well.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(well, LinearLayout.LayoutParams(dpi(84f), dpi(110f)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dpi(4f); marginEnd = dpi(16f) })
            val words = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dpi(8f), 0, dpi(4f)) }
            words.addView(View(context).apply { background = rounded(COVER_DESIGNS[coverStyles[entry.id]]?.color ?: Glass.accent(night), dp(3f)) },
                LinearLayout.LayoutParams(dpi(28f), dpi(6f)).apply { bottomMargin = dpi(12f) })
            words.addView(TextView(context).apply {
                text = entry.title; setTextColor(Glass.text(night)); textSize = 17f; typeface = Tokens.SANS_SEMIBOLD; keepWordsWhole(leastShare = NAME_SHARE)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            val pages = if (entry.pageCount == 1) "1 page" else "${entry.pageCount} pages"
            words.addView(TextView(context).apply {
                text = listOfNotNull("$pages · Edited ${editedAgo(entry.modified, now)}", filed[entry.id]?.let { shownPath(it) }).joinToString("\n")
                setTextColor(Glass.secondary(night)); textSize = 13f; typeface = Tokens.SANS; maxLines = if (context.largeText) Int.MAX_VALUE else 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dpi(4f), 0, 0)
            })
            words.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            words.addView(TextView(context).apply {
                text = "Continue ›"; setTextColor(accentInk(night)); textSize = 14f; typeface = Tokens.SANS_SEMIBOLD; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            // At large text the words set the card's height, so they must not be held to the well's.
            addView(words, LinearLayout.LayoutParams(0, if (context.largeText) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .5f }
    }

    /**
     * B18 (P136): a folder in the grid, the same size as a notebook: a folder drawn in its colour (kraft by default),
     * its name with a ⋮ button, and how many notebooks and folders it holds.
     */
    private inner class FolderTile(context: Context, val path: String, design: CoverDesign, notebooks: Int, folders: Int) : LinearLayout(context) {
        val art = FolderArt(context).apply { this.design = design; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        val more = MoreDots(context, "Options for folder ${leafOf(path)}")
        private val holdsText = TextView(context)
        private var holdsLine = ""
        /** P608: lit while a lifted notebook is over this folder: accent at 10% with a ring, and "Drop to move here". */
        var dropTarget = false
            set(value) {
                if (field == value) return
                field = value
                background = if (value) rounded((Glass.accent(night) and 0xffffff) or 0x1a000000, dp(18f), Glass.accent(night), dpi(2.5f)) else null
                holdsText.text = if (value) "Drop to move here" else holdsLine
                holdsText.setTextColor(if (value) accentInk(night) else Glass.secondary(night))
                holdsText.typeface = if (value) Tokens.SANS_MEDIUM else Tokens.SANS
            }
        init {
            orientation = VERTICAL
            setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(12f))
            foreground = ripple(night, dp(14f)); isClickable = true; isFocusable = true
            val holds = listOfNotNull(
                if (notebooks == 1) "1 notebook" else "$notebooks notebooks",
                folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" })
            contentDescription = "Folder ${leafOf(path)}, ${holds.joinToString(", ")}"
            addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            val titleRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
            titleRow.addView(TextView(context).apply {
                text = leafOf(path); setTextColor(Glass.text(night)); textSize = 14f; typeface = Tokens.SANS_SEMIBOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dpi(2f), dpi(4f), dpi(2f), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(more, LinearLayout.LayoutParams(dpi(36f), dpi(34f)).apply { marginEnd = -dpi(8f) })
            addView(titleRow)
            holdsLine = holds.joinToString(" · ")
            addView(holdsText.apply {
                text = holdsLine; setTextColor(Glass.secondary(night)); textSize = 12f; typeface = Tokens.SANS
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dpi(2f), dpi(2f), dpi(2f), 0)
            })
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // The same frame as a notebook cover, so folders and notebooks line up in one grid.
            val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            (art.layoutParams as LinearLayout.LayoutParams).height = (inner * 1.36f).toInt()
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** B48: data spans the full library, while views exist only around the viewport. Header scrolls with the cells. */
    private inner class CatalogueHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var notebookTile: Tile? = null
        var folderTile: FolderTile? = null
        fun release() {
            notebookTile?.let { tile ->
                tile.cover.release()
                if (tiles[tile.entry.id] === tile) tiles.remove(tile.entry.id)
            }
            folderTile?.let { tile -> folderTiles.entries.removeAll { it.value === tile } }
            notebookTile = null; folderTile = null; frame.removeAllViews()
        }
    }
    private inner class CatalogueAdapter : RecyclerView.Adapter<CatalogueHolder>() {
        override fun getItemCount() = 1 + shownFolderPaths.size + (shownEntries?.size ?: 0)
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogueHolder {
            val frame = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    // The header keeps its original full width; tile covers keep their original 24 dp outer edge.
                    if (viewType == 0) { leftMargin = -dpi(14f); rightMargin = -dpi(14f) }
                }
            }
            return CatalogueHolder(frame)
        }
        override fun onBindViewHolder(holder: CatalogueHolder, position: Int) {
            holder.release()
            if (position == 0) {
                (catalogueHeader.parent as? ViewGroup)?.removeView(catalogueHeader)
                holder.frame.addView(catalogueHeader, FrameLayout.LayoutParams(-1, -2)); return
            }
            val index = position - 1
            if (index < shownFolderPaths.size) {
                val path = shownFolderPaths[index]; val parts = shownFolderDetails[index].split('\n')
                val tile = FolderTile(context, path, COVER_DESIGNS[folderColors[path] ?: DEFAULT_FOLDER]!!, parts[2].toInt(), parts[3].toInt())
                tile.setOnClickListener { showFolder(path) }
                tile.setOnLongClickListener { if (selecting) false else showFolderActions(path, tile.art) }
                tile.more.setOnClickListener { showFolderActions(path, tile.more) }
                holder.folderTile = tile; folderTiles[path] = tile
                if (lift?.target == path) tile.dropTarget = true
                holder.frame.addView(tile, FrameLayout.LayoutParams(-1, -2))
            } else {
                val entry = shownEntries!![index - shownFolderPaths.size]
                val tile = Tile(context, entry, showingTrash, pinned = !showingTrash && entry.id in pinned, style = coverStyles[entry.id],
                    tags = if (showingTrash) emptyList() else tagsOf(entry.id))
                if (showingTrash) tile.setOnClickListener { restore(entry.id) }
                else {
                    tile.setOnClickListener { if (selecting) toggleSelected(entry.id) else open(entry.id) }
                    // P608: a hold lifts the notebook to drop on a folder; letting go where it was opens its menu.
                    tile.setOnLongClickListener { if (selecting) toggleSelected(entry.id) else beginLift(entry.id, tile) || showActions(entry.id, tile.cover) }
                    tile.more?.setOnClickListener { if (selecting) toggleSelected(entry.id) else showActions(entry.id, tile.more) }
                }
                tile.isEnabled = canTapNotebook(entry)
                tile.cover.current = !showingTrash && entry.id == repository.session?.notebook?.id
                tile.showTicked(selecting && !showingTrash, entry.id in selection)
                holder.notebookTile = tile; tiles[entry.id] = tile
                lift?.let { held -> if (held.off && held.id == entry.id) { held.cover = tile.cover; tile.cover.liftedOut = held.outline } }
                holder.frame.addView(tile, FrameLayout.LayoutParams(-1, -2))
            }
            scheduleBind()
        }
        override fun onViewRecycled(holder: CatalogueHolder) = holder.release()
        override fun onViewDetachedFromWindow(holder: CatalogueHolder) { holder.notebookTile?.cover?.release() }
        override fun onViewAttachedToWindow(holder: CatalogueHolder) { scheduleBind() }
    }

    companion object {
        fun defaultCoverLoader(context: Context): (String) -> Notebook {
            val app = context.applicationContext as NotesApplication
            val library = NotebookLibrary(File(app.storageRoot, "notebooks"),{ app.attachmentDirectory })
            return { id -> library.readCover(id) }
        }
        fun defaultTrashCoverLoader(context: Context): (String) -> Notebook {
            val app = context.applicationContext as NotesApplication
            val library = NotebookLibrary(File(app.storageRoot, "notebooks"),{ app.attachmentDirectory })
            return { id -> library.readTrashedCover(id) }
        }
    }
}

/** P188: the Glass board's Library layout: the main column's side padding and the floating side panel. */
private const val MAIN_PAD = 24f
private const val SIDE_WIDTH = 248f
private const val SIDE_WIDTH_WIDE = 280f
private const val SIDE_RADIUS = 30f
/** P577: below this width (dp) the side panel slides out from a button instead of standing beside the notebooks. */
private const val COMPACT_WIDTH = 600f
/** P568: the Library's colours: as they were by day, the Glass kit's at night. */
private fun deskOf(night: Boolean) = if (night) Kit.desk(true) else Glass.DESK
/** P608: the hint pill's folder-arrow icon, from the Library-Drag board. */
private const val MOVE_HINT_ICON = "M3 6.5h6l2 2h10v10.5H3zM9.5 14h5.5M12.5 11.5L15 14l-2.5 2.5"

private fun accentInk(night: Boolean) = if (night) Kit.accentText(true) else Glass.ACCENT
private fun faultInk(night: Boolean) = if (night) Kit.dangerText(true) else Tokens.FAULT
private fun selectedFill(night: Boolean) = if (night) Kit.accentFill(true) else Glass.SELECTED
private fun wellOf(night: Boolean) = if (night) Kit.well(true) else 0x1f768096
private fun thinOf(night: Boolean) = if (night) Kit.well(true) else Glass.THIN
/** A dark folder colour (Ink, Navy, Forest) lightened halfway to white, so its icon does not vanish on the night pane. */
private fun seenAtNight(color: Int): Int {
    if (Color.luminance(color) >= .12f) return color
    fun c(shift: Int) = ((((color shr shift) and 0xff) + 255) / 2) shl shift
    return (color and -0x1000000) or c(16) or c(8) or c(0)
}

private enum class PillIcon { IMPORT, PLUS, BACK }
private enum class NavIcon { ALL, FOLDER, TRASH, MORE, GESTURES, SETTINGS }

internal const val NO_VERSIONS ="No earlier versions are kept. When sync replaces this notebook with a newer copy from another device, the version it replaces is kept here (the last 5)."
private const val SORT_KEY = "librarySortRecent"
private const val DATE_FILTER_KEY = "libraryModifiedFilter"
private const val ARCHIVED_KEY = "libraryArchived"
private const val PINNED_KEY = "libraryPinned"
private const val COVERS_KEY = "libraryCovers"
/** New notebook remembers its last cover ("" for First page), paper template and page size (P068). */
private const val NEW_COVER_KEY = "libraryNewCover"
private const val NEW_TEMPLATE_KEY = "libraryNewTemplate"
private const val NEW_SIZE_KEY = "libraryNewSize"
private const val DEFAULT_COVER = "ink"
/** P201 step 7: the New notebook board's "Import PDF" document icon. */
private const val IMPORT_ICON = "M6 3h8l4 4v14H6zM14 3v4h4M9 13h6M9 17h4"
private val KEEP_GRID = PaperStyle(PaperPattern.GRID)
/** Short words for the size segments; TalkBack still reads the full size name. */
private val SEGMENT_NAMES = mapOf("Keep existing sizes" to "Keep", "US Letter" to "Letter")
/** Built-in New notebook papers; saved paper presets follow them. */
internal val BUILT_IN_PAPERS = listOf(
    "Plain" to PaperStyle.DEFAULT,
    "Ruled" to PaperStyle(PaperPattern.RULED),
    "Narrow ruled" to PaperStyle(PaperPattern.RULED, spacing = 24f),
    "Grid" to PaperStyle(PaperPattern.GRID),
    "Dots" to PaperStyle(PaperPattern.DOTS, guideColor = 0xffa9b5c1L, weight = 1.5f),
    "Legal pad" to PaperStyle(PaperPattern.RULED, color = 0xfffdf3c4L, guideColor = 0xff8fb4d8L),
    "Cream" to PaperStyle(PaperPattern.RULED, color = 0xfffaf5eaL, guideColor = 0xffd8ccb4L),
    "Isometric" to PaperStyle(PaperPattern.ISOMETRIC, guideColor = 0xffc3cdd8L, weight = .6f),
    // G8 (P183): night paper, chosen like any other paper; stored colours are kept exactly as chosen.
    "Night" to PaperStyle(PaperPattern.RULED, color = 0xff1a1d21L, guideColor = 0xff3a4352L),
)
/** New notebook page sizes in document units; the named sizes match the page size dialog's presets. */
internal val PAGE_SIZES = listOf("Standard" to (1000f to 1400f), "A4" to (595.28f to 841.89f), "A5" to (419.53f to 595.28f),
    "US Letter" to (612f to 792f), "Square" to (1000f to 1000f))
/** How a cover's material is drawn over its colour (P068). */
private enum class Finish { CLOTH, KRAFT, LINEN, STRIPE, LEATHER, DOTS }
/** A notebook's outside cover: its name, base colour and material. */
private class CoverDesign(val name: String, val color: Int, val finish: Finish = Finish.CLOTH) {
    /** Light covers take an ink outline on the title plate instead of a pale one. */
    val light get() = ((color shr 16 and 0xff) * 299 + (color shr 8 and 0xff) * 587 + (color and 0xff) * 114) / 1000 > 170
}
/** Cover keys, in chooser order (P045 cloths first, then the P068 materials). Keys are stored, so never renamed. */
private val COVER_DESIGNS = linkedMapOf(
    "ink" to CoverDesign("Ink", Tokens.INK), "amber" to CoverDesign("Amber", 0xffc98a2e.toInt()),
    "sage" to CoverDesign("Sage", 0xff6f8f7a.toInt()), "rose" to CoverDesign("Rose", 0xffb0626a.toInt()),
    "slate" to CoverDesign("Slate", 0xff55677a.toInt()), "plum" to CoverDesign("Plum", 0xff6b4e71.toInt()),
    "leather" to CoverDesign("Leather", 0xff6a3f2a.toInt(), Finish.LEATHER), "forest" to CoverDesign("Forest", 0xff2f4f3e.toInt(), Finish.LEATHER),
    "kraft" to CoverDesign("Kraft", 0xffb48a5c.toInt(), Finish.KRAFT), "linen" to CoverDesign("Linen", 0xffe3d9c6.toInt(), Finish.LINEN),
    "navy" to CoverDesign("Navy stripe", 0xff243b5a.toInt(), Finish.STRIPE), "coral" to CoverDesign("Coral dots", 0xffd0735e.toInt(), Finish.DOTS),
)
/** Height over width of a coloured book cover. */
private const val BOOK_ASPECT = 1.32f

/** Shades a colour towards black by [amount] (0–1), keeping its alpha. */
private fun shade(color: Int, amount: Float): Int {
    fun c(shift: Int) = (((color shr shift) and 0xff) * (1 - amount)).toInt().coerceIn(0, 255) shl shift
    return (color and -0x1000000) or c(16) or c(8) or c(0)
}

/** Fixed pseudo-random flecks (kraft fibres, leather grain), the same on every draw. */
private fun drawFlecks(canvas: Canvas, r: RectF, paint: Paint, count: Int, size: Float, dark: Int, light: Int) {
    var seed = 0x2545F491L
    fun next(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) and 0xffffff).toFloat() / 0xffffff }
    repeat(count) { i ->
        paint.color = if (i % 3 == 0) light else dark
        canvas.drawCircle(r.left + next() * r.width(), r.top + next() * r.height(), size * (.5f + next()), paint)
    }
}

/** P188: a closed book's outline on the Glass board: slightly rounded at the spine, rounder at the fore-edge. */
private fun bookPath(path: Path, r: RectF) {
    val u = r.width() / 126f; val s = 5f * u; val f = 14f * u
    path.addRoundRect(r, floatArrayOf(s, s, f, f, f, f, s, s), Path.Direction.CW)
}

/**
 * A closed notebook's outside cover, as on the Glass board (P188): the material over its colour, a shaded spine with a
 * hinge line, an elastic band on leather and a soft diagonal sheen. [drawTitlePlate] adds the name where a cover is a notebook.
 */
private fun drawBookCover(canvas: Canvas, r: RectF, design: CoverDesign, paint: Paint) {
    // Materials keep their earlier scale; the spine and band follow the board's 126-unit cover.
    val u = r.width() / 160f; val b = r.width() / 126f; val color = design.color
    val clip = Path().also { bookPath(it, r) }
    paint.style = Paint.Style.FILL; paint.shader = null
    paint.color = color; canvas.drawPath(clip, paint)
    canvas.save()
    canvas.clipPath(clip)
    val spine = r.left + 13f * b
    when (design.finish) {
        Finish.CLOTH -> {}
        Finish.KRAFT -> drawFlecks(canvas, r, paint, 160, .7f * u, 0x2a3b2410, 0x22ffffff)
        Finish.LEATHER -> drawFlecks(canvas, r, paint, 220, .45f * u, 0x1e000000, 0x10ffffff)
        Finish.LINEN -> {
            paint.strokeWidth = max(1f, .5f * u); paint.style = Paint.Style.STROKE
            var x = r.left; while (x < r.right) { paint.color = 0x0f000000; canvas.drawLine(x, r.top, x, r.bottom, paint); x += 2.4f * u }
            var y = r.top; while (y < r.bottom) { paint.color = 0x14ffffff; canvas.drawLine(r.left, y, r.right, y, paint); y += 2.4f * u }
            paint.style = Paint.Style.FILL
        }
        Finish.STRIPE -> {
            paint.strokeWidth = 2.2f * u; paint.style = Paint.Style.STROKE; paint.color = 0x24ffffff
            var x = r.left - r.height(); while (x < r.right) { canvas.drawLine(x, r.bottom, x + r.height(), r.top, paint); x += 11f * u }
            paint.style = Paint.Style.FILL
        }
        Finish.DOTS -> {
            paint.color = 0x38ffffff; var row = 0
            var y = r.top + 6f * u
            while (y < r.bottom) { var x = r.left + (if (row % 2 == 0) 6f else 13f) * u; while (x < r.right) { canvas.drawCircle(x, y, 2.1f * u, paint); x += 14f * u }; y += 12f * u; row++ }
        }
    }
    paint.color = 0x2e000000; canvas.drawRect(r.left, r.top, spine, r.bottom, paint)
    paint.color = 0x38ffffff; canvas.drawRect(spine, r.top, spine + max(1f, .8f * b), r.bottom, paint)
    if (design.finish == Finish.LEATHER) {
        // An elastic band near the fore-edge.
        paint.color = 0x47000000; canvas.drawRect(r.right - 25f * b, r.top, r.right - 18f * b, r.bottom, paint)
    }
    // The board's sheen: white at 22% fading out at 42% of the way across, at 115°.
    val dx = .906f; val dy = .423f; val reach = (r.width() * dx + r.height() * dy) * .42f
    paint.shader = android.graphics.LinearGradient(r.left, r.top, r.left + dx * reach, r.top + dy * reach,
        0x38ffffff, 0x00ffffff, android.graphics.Shader.TileMode.CLAMP)
    canvas.drawRect(r, paint); paint.shader = null
    canvas.restore()
}

/**
 * P201 step 7, P567: the cream plate on a closed notebook, with its title and a small caption, on the board's 236-unit cover.
 * The New notebook preview and the library's covers share it. [box] is scratch space; a cover too small to read the caption drops it.
 */
private fun drawTitlePlate(canvas: Canvas, book: RectF, title: String, placeholder: Boolean, caption: String, paint: Paint, text: android.text.TextPaint, box: RectF, smallestCaption: Float = 0f) {
    val s = book.width() / 236f; val pad = 14f * s
    val withCaption = 11f * s >= smallestCaption
    val bottom = book.top + 70 * s + pad * 2 + 19 * s + if (withCaption) 15 * s + 11 * s else 0f
    box.set(book.left + 34 * s, book.top + 70 * s, book.left + 202 * s, bottom)
    paint.style = Paint.Style.FILL; paint.color = 0xfffbfaf7.toInt(); paint.setShadowLayer(1.5f * s, 0f, s, 0x33000000)
    canvas.drawRoundRect(box, 8 * s, 8 * s, paint); paint.clearShadowLayer()
    text.textSize = 19 * s; text.typeface = Kit.Type.TITLE.typeface; text.letterSpacing = -.015f
    text.color = if (placeholder) 0xff8a93a6.toInt() else 0xff1d2433.toInt()
    val base = box.top + pad + 16 * s
    canvas.drawText(TextUtils.ellipsize(title, text, box.width() - 2 * pad, TextUtils.TruncateAt.END).toString(), box.left + pad, base, text)
    if (!withCaption) return
    paint.color = 0xffe3e0d8.toInt(); canvas.drawRect(box.left + pad, base + 8 * s, box.right - pad, base + 9 * s, paint)
    text.textSize = 11 * s; text.typeface = Tokens.SANS_SEMIBOLD; text.letterSpacing = .04f; text.color = 0xff8a93a6.toInt()
    canvas.drawText(TextUtils.ellipsize(caption, text, box.width() - 2 * pad, TextUtils.TruncateAt.END).toString(), box.left + pad, base + 9 * s + 15 * s, text)
}

/**
 * B18 (P136): a folder drawn in a cover colour: a back panel with a tab, a sheet of paper peeking out, and the front
 * panel with the material over it. A [label] under it (the colour chooser) and an amber ring when [ringed].
 */
private class FolderArt(context: Context) : View(context) {
    var design: CoverDesign? = null; set(value) { field = value; invalidate() }
    var label: String? = null; set(value) { field = value; requestLayout(); invalidate() }
    var ringed = false; set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); color = if (context.night) Glass.TEXT_NIGHT else Tokens.INK; textAlign = Paint.Align.CENTER; typeface = Tokens.SANS }
    private val box = RectF()
    private val path = android.graphics.Path()
    // P639: the colour's name grows with the reader's text size; the swatch grows under the folder to hold it.
    private val most = context.readerSize(12f, 16f)
    private val grow = (most - dp(12f)) * 1.25f
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); return
        }
        // A swatch in the colour chooser.
        val w = dpi(76f); setMeasuredDimension(w, (w * .82f).toInt() + if (label != null) dpi(22f) + kotlin.math.ceil(grow).toInt() else 0)
    }
    override fun onDraw(canvas: Canvas) {
        val design = design ?: return
        val labelH = if (label != null) dp(22f) + grow else 0f
        val pad = dp(4f)
        val availW = width - 2 * pad; val availH = height - labelH - 2 * pad
        // A folder is wider than tall; centre it in the tile's cover frame.
        val w = minOf(availW, availH / .78f); val h = w * .78f
        box.set((width - w) / 2, pad + (availH - h) / 2 + h * .02f, (width + w) / 2, pad + (availH + h) / 2)
        val u = w / 160f; val r = 7f * u; val color = design.color
        val tabH = 14f * u; val tabW = w * .38f
        // Shadow, then the back panel with its tab.
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0x22000000; canvas.drawRoundRect(box.left + 1.5f * u, box.top + tabH + 3f * u, box.right + 1.5f * u, box.bottom + 3f * u, r, r, paint)
        path.reset()
        path.moveTo(box.left, box.top + r)
        path.quadTo(box.left, box.top, box.left + r, box.top)
        path.lineTo(box.left + tabW - 6f * u, box.top)
        path.quadTo(box.left + tabW, box.top, box.left + tabW + 6f * u, box.top + tabH)
        path.lineTo(box.right - r, box.top + tabH)
        path.quadTo(box.right, box.top + tabH, box.right, box.top + tabH + r)
        path.lineTo(box.right, box.bottom - r); path.quadTo(box.right, box.bottom, box.right - r, box.bottom)
        path.lineTo(box.left + r, box.bottom); path.quadTo(box.left, box.bottom, box.left, box.bottom - r); path.close()
        paint.color = shade(color, .22f); canvas.drawPath(path, paint)
        // A sheet of paper inside.
        paint.color = 0xfff6f1e4.toInt()
        canvas.drawRoundRect(box.left + 10f * u, box.top + tabH + 6f * u, box.right - 10f * u, box.bottom - 20f * u, 3f * u, 3f * u, paint)
        // The front panel, a little lower, with its material.
        val front = RectF(box.left, box.top + tabH + 16f * u, box.right, box.bottom)
        paint.color = color; canvas.drawRoundRect(front, r, r, paint)
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(front, r, r, android.graphics.Path.Direction.CW) })
        when (design.finish) {
            Finish.KRAFT -> drawFlecks(canvas, front, paint, 120, .7f * u, 0x2a3b2410, 0x22ffffff)
            Finish.LEATHER -> drawFlecks(canvas, front, paint, 160, .45f * u, 0x1e000000, 0x10ffffff)
            Finish.STRIPE -> {
                paint.strokeWidth = 2.2f * u; paint.style = Paint.Style.STROKE; paint.color = 0x24ffffff
                var x = front.left - front.height(); while (x < front.right) { canvas.drawLine(x, front.bottom, x + front.height(), front.top, paint); x += 11f * u }
                paint.style = Paint.Style.FILL
            }
            Finish.DOTS -> {
                paint.color = 0x38ffffff; var row = 0; var y = front.top + 6f * u
                while (y < front.bottom) { var x = front.left + (if (row % 2 == 0) 6f else 13f) * u; while (x < front.right) { canvas.drawCircle(x, y, 2.1f * u, paint); x += 14f * u }; y += 12f * u; row++ }
            }
            else -> {}
        }
        paint.shader = android.graphics.LinearGradient(front.left, front.top, front.left, front.top + front.height() * .5f,
            0x30ffffff, 0x00ffffff, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(front, paint); paint.shader = null
        canvas.restore()
        // The top edge of the front panel catches the light.
        paint.color = 0x40ffffff; canvas.drawRect(front.left + r, front.top, front.right - r, front.top + max(1f, 1f * u), paint)
        if (ringed) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f); paint.color = Tokens.AMBER
            val o = dp(2.5f); canvas.drawRoundRect(box.left - o, box.top - o, box.right + o, box.bottom + o, r + o, r + o, paint)
            paint.style = Paint.Style.FILL
        }
        label?.let { text.fitText(it, width - dp(4f), most, dp(12f)); canvas.drawText(it, width / 2f, height - dp(6f), text) }
    }
}

private const val FOLDERS_KEY = "libraryFolders"
/** "path\ncover key" for each coloured folder (B18, P136). */
private const val FOLDER_COLORS_KEY = "libraryFolderColors"
/** A folder without a chosen colour is drawn in kraft, like a manila folder. */
private const val DEFAULT_FOLDER = "kraft"
/** Entries are "<notebook id>\n<folder name>". */
private const val FOLDER_OF_KEY = "libraryFolderOf"
/** "id\ntag" for each tag a notebook carries (P052). */
private const val TAGS_KEY = "libraryTags"
private const val MAX_TAGS = 12
private const val MAX_TAG_NAME = 30

/** A trimmed tag of 1–30 characters (a leading "#" dropped) without commas, "#" or control characters, or null. */
private fun tagName(text: String): String? = text.trim().removePrefix("#").trim().takeIf { name ->
    name.length in 1..MAX_TAG_NAME && name.none { it.isISOControl() || it == ',' || it == '#' }
}
private const val MAX_FOLDERS = 100
private const val MAX_FOLDER_NAME = 60
/** Folders nest at most this deep (P050), so the breadcrumb row stays readable. */
private const val MAX_FOLDER_DEPTH = 4

// Folder paths (P050) join names with "/", which names cannot contain.
private fun parentOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }
private fun leafOf(path: String): String = path.substringAfterLast('/')
private fun pathOf(parent: String?, name: String): String = if (parent == null) name else "$parent/$name"
/** Whether [path] is [folder] or inside it. */
private fun isWithin(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")
/** The path and each of its ancestors, outermost first. */
private fun ancestry(path: String): List<String> = path.split('/').runningReduce { a, b -> "$a/$b" }
/** A path as people read it: "Science › Physics". */
private fun shownPath(path: String): String = path.replace("/", " › ")
/** Parents before their children, siblings alphabetically ignoring case. */
private val PATH_ORDER = Comparator<String> { a, b ->
    val x = a.split('/'); val y = b.split('/')
    (0 until min(x.size, y.size)).firstNotNullOfOrNull { i -> String.CASE_INSENSITIVE_ORDER.compare(x[i], y[i]).takeIf { it != 0 } } ?: (x.size - y.size)
}

