package dev.ownednotes.app

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import dev.ownednotes.core.hasBackground
import dev.ownednotes.core.DocumentSession
import dev.ownednotes.core.Layer
import dev.ownednotes.core.ImageBackground
import dev.ownednotes.core.LibraryEntry
import dev.ownednotes.core.NotebookPackage
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.TextExport
import dev.ownednotes.core.TurnDirection

/** P105 (F138): the action of the launcher's Quick note shortcut (res/xml/shortcuts.xml). */
internal const val QUICK_NOTE = "dev.ownednotes.app.QUICK_NOTE"

/** P530: a finger this soon after the pen was near is the resting hand, not a request to change panes. */
private const val PANE_PALM_MS = 500L
/** P699: while a notebook is open, sync again this long after the last sync ended. */
private const val AUTO_SYNC_MS = 30_000L
/** While instant sync is connected, folder sync only needs to run now and then (LIVE-SYNC-PLAN step 3b). */
private const val LIVE_AUTO_SYNC_MS = 600_000L
class MainActivity : ComponentActivity() {
    private val app get() = application as NotesApplication
    /** P530 (F081/F082): the active pane's document. Library-wide work uses [NotesApplication.repository]. */
    private val repository get() = app.activeDocument
    lateinit var primaryInk: InkCanvas; private set
    var companionInk: InkCanvas? = null; private set
    /** The active pane's canvas. */
    val ink: InkCanvas get() = companionInk?.takeIf { app.activeDocument === app.repository.companion } ?: primaryInk
    /** Editor chrome; tests read its tool buttons. */
    lateinit var frame: EditorFrame; private set
    internal val appLockGate by lazy {AppLockGate(this)}
    internal var appLockSetup:AppLockSetup?=null
    fun appLockSettings()=AppLockPanels.settings(this)
    internal var textEditor: TextEditorHost? = null
    internal var photoCropEditor: PhotoCropHost? = null
    lateinit var thumbnails: PageThumbnails; private set
    var libraryScreen: LibraryScreen? = null; private set
    internal var libraryStorageActions: NotebookStorageActions? = null
    internal var notebookFolderLauncher: ((android.net.Uri) -> Unit)? = null
    private val observer: () -> Unit = { refresh() }
    val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val closeSettings = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // P567: Settings can be open over the Library; Back closes it instead of leaving the app.
            if (libraryScreen != null && frame.settingsOpen) { frame.closeSettings(); return }
            libraryScreen?.let { if (!it.leaveTrash()) finish() }
                // P201 step 10: Back cancels a scan, as it dismissed the scan dialog before.
                ?: if (scanScreen != null) scanScreen!!.cancel()
                // P689: Back leaves the picture crop without changing the picture.
                else if (pictureCrop != null) pictureCrop!!.back()
                else if (frame.settingsOpen) frame.closeSettings()
                // P201 step 6: the page organizer stops choosing, then closes, like Settings.
                else if (frame.organizerOpen) frame.organizer!!.back()
                else if (pdfTextReading || ink.pdfTextSelectionActive) cancelPdfTextSelection()
                // P178 (G3): Back leaves page choosing before it leaves the notebook.
                else if (frame.choosingPages) frame.stopChoosingPages()
                // P653: Back closes flashcards, then the Study panel, before it leaves the notebook (it left with them open).
                else if (frame.flashcardBar != null) frame.closeFlashcards()
                else if (frame.studyPanel != null) frame.closeStudyPanel() else showLibrary()
        }
    }
    /** Keyboard cover actions share the editor's commands; forms and other panels keep native keys. */
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean {
        if(appLockGate.locked)return true
        if(!::primaryInk.isInitialized || !::frame.isInitialized || currentFocus is EditText || textEditor!=null || photoCropEditor!=null ||
            libraryScreen!=null || frame.settingsOpen || frame.organizerOpen || frame.choosingPages || scanScreen!=null || pictureCrop!=null || !ink.inputEnabled)
            return super.onKeyDown(keyCode,event)
        val ctrl=event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        val pageKey=!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed &&
            (keyCode==android.view.KeyEvent.KEYCODE_PAGE_UP || keyCode==android.view.KeyEvent.KEYCODE_PAGE_DOWN)
        val command=ctrl && !event.isShiftPressed && keyCode in setOf(android.view.KeyEvent.KEYCODE_Z,android.view.KeyEvent.KEYCODE_Y,
            android.view.KeyEvent.KEYCODE_C,android.view.KeyEvent.KEYCODE_X,android.view.KeyEvent.KEYCODE_V,android.view.KeyEvent.KEYCODE_F)
        if(!pageKey && !command)return super.onKeyDown(keyCode,event)
        // Consume recognised keys while temporarily busy instead of leaking them into another action.
        if(event.repeatCount>0 || ink.isEditing || !repository.canChangeNotebook || repository.busy || repository.fileBusy)return true
        when(keyCode) {
            android.view.KeyEvent.KEYCODE_PAGE_UP -> turnPage(-1)
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> turnPage(1)
            android.view.KeyEvent.KEYCODE_Z -> if(!repository.readOnly && repository.session?.canUndo==true)repository.edit { it.undo() }
            android.view.KeyEvent.KEYCODE_Y -> if(!repository.readOnly && repository.session?.canRedo==true)repository.edit { it.redo() }
            android.view.KeyEvent.KEYCODE_C -> if(!repository.readOnly && ink.selectedCount>0)ink.copySelectedInk()
            android.view.KeyEvent.KEYCODE_X -> if(!repository.readOnly && ink.selectedCount>0)ink.copySelectedInk(cut=true)
            android.view.KeyEvent.KEYCODE_V -> frame.runCommand(EditorCommand.PASTE)
            android.view.KeyEvent.KEYCODE_F -> frame.runCommand(EditorCommand.SEARCH)
        }
        return true
    }
    private var penTraceCount = 0
    private var lastTraceAction = -1
    private fun tracePen(event: MotionEvent) {
        // Opt-in engineering trace: event metadata only, never coordinates or notebook data.
        if (!intent.getBooleanExtra("tracePen", false) || penTraceCount >= 64 || event.actionMasked == lastTraceAction ||
            (0 until event.pointerCount).none { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS }) return
        lastTraceAction = event.actionMasked; penTraceCount++
        android.util.Log.d("NotesPenTrace", "action=${event.actionMasked} buttons=${event.buttonState} pressure=${event.getPressure(0)}")
    }
    // ---- P530 (F081/F082): side-by-side panes. ----
    private fun wireCanvas(canvas: InkCanvas, document: NotebookRepository) {
        if (dark) canvas.deskColor = Tokens.DESK_DARK
        canvas.onPageTurn = { direction -> turnPage(if (direction == TurnDirection.NEXT) 1 else -1).also { if (!it && direction == TurnDirection.NEXT) frame.offerNewPage() } }
        canvas.onPageJump = { turnPage(it) }
        canvas.onAttachmentTap = { attachmentMenu(it) }
        canvas.onPdfLinkTap = { id, point -> followPdfLink(id, point) }
        canvas.tapTarget = { id, point -> tapTarget(id, point) }
        canvas.onNoteAction = { pageId, id, part, x, y -> frame.noteAction(canvas, document, pageId, id, part, x, y) }
        canvas.onTextTap = { id, point -> frame.inPlaceText.openExisting(canvas, document, id, point) }
        canvas.onFingerDown = { id -> readTapTargets(id) }
        canvas.onPdfPasswordRequired = { asset ->
            if(libraryScreen==null)requestPdfPassword(asset,unlocked={
                thumbnails.clear();canvas.retryPdfBackground();app.searchIndexer.schedule(document.session?.notebook)
            })
        }
        canvas.configurePageCurl(thumbnails)
        canvas.onToolStateChanged = { frame.refreshTools() }
        ToolCursor.attach(canvas) { document.readOnly || !canvas.inputEnabled || libraryScreen != null || frame.settingsOpen }
    }
    private var companionOpening = false
    private var companionCloseRevision: Long? = null
    /** Notebook menu → Open alongside…: chooses another notebook; the two stay separate. */
    internal fun openAlongsideDialog(): NotebookChooser? {
        val sources = app.repository.otherNotebooks()
        if (sources.isEmpty()) { message("There is no other notebook to open"); return null }
        return NotebookChooser(this, "Open alongside this notebook", sources) { openCompanionPane(it.id) }.show()
    }
    /** A refused or failed open leaves the first document exactly as it was. */
    internal fun openCompanionPane(id: String): Boolean {
        if (app.repository.companion?.session?.notebook?.id == id) { focusCompanionPane(); return true }
        if (!app.repository.openCompanion(id)) { message("That notebook could not open alongside this one"); return false }
        companionOpening = true
        app.repository.companion?.listen(observer)
        return true
    }
    /** Keeps the panes matching the documents; also rebuilds them after the activity is recreated. */
    private fun syncCompanionPane() {
        val document = app.repository.companion ?: return
        val session = document.session
        companionCloseRevision?.let { asked ->
            when {
                session == null -> companionCloseRevision = null
                document.editRevision != asked -> companionCloseRevision = null
                app.repository.closeCompanion() -> companionCloseRevision = null
            }
        }
        val canvas = companionInk
        if (document.session != null && canvas == null) {
            val created = InkCanvas(this, document, { refresh() }, { message(it) })
            companionInk = created
            wireCanvas(created, document)
            thumbnails.panes = 2 // B63: room for both panes' page-turn previews
            frame.attachCompanion(created, document)
            if (companionOpening || app.activeDocument === document) {
                app.focusDocument(document)
                frame.focusPane(created, document)
            }
            companionOpening = false
        } else if (document.session == null && canvas != null) {
            if (ink === canvas) app.focusDocument(app.repository)
            frame.detachCompanion()
            canvas.disposePageCurl()
            thumbnails.panes = 1
            companionInk = null
        } else if (companionOpening && document.session == null && !document.busy) {
            companionOpening = false
            message(document.status)
        }
    }
    /** Makes [canvas] the shared toolbar's target. Refused while the current pane has a contact or a recording in progress. */
    internal fun focusPane(canvas: InkCanvas): Boolean {
        if (canvas === ink) return true
        if (ink.isEditing || frame.zoomWriting?.canvas?.isEditing == true) return false
        if (app.audio.active) { message("Stop recording before switching notebooks"); return false }
        val document = if (canvas === primaryInk) app.repository else app.repository.companion ?: return false
        app.audioPlayback.stop()
        cancelPdfTextSelection()
        ink.penHover(MotionEvent.ACTION_HOVER_EXIT, 0f, 0f, android.os.SystemClock.uptimeMillis())
        app.focusDocument(document)
        frame.focusPane(canvas, document)
        refresh()
        return true
    }
    internal fun focusCompanionPane() { companionInk?.let { focusPane(it) } }
    /** Saves first; a failed save keeps the pane open with its existing warning. The first notebook keeps its page. */
    fun closeCompanionPane() {
        val document = app.repository.companion ?: return
        val canvas = companionInk ?: return
        if (canvas.isEditing) { message("Finish writing before closing the companion"); return }
        if (!focusPane(primaryInk)) return
        canvas.prepareForPause()
        document.flushSave()
        companionCloseRevision = document.editRevision
        refresh()
        if (companionCloseRevision != null && app.repository.companion?.session != null) message("Saving the companion; it closes when the save finishes")
    }
    private var penNear = false
    private var penSeenAt = 0L
    private fun overCanvas(canvas: InkCanvas, event: MotionEvent): Boolean {
        val at = IntArray(2); canvas.getLocationInWindow(at)
        return event.x >= at[0] && event.x < at[0] + canvas.width && event.y >= at[1] && event.y < at[1] + canvas.height
    }
    /**
     * The first contact on the other pane moves the toolbar there before the view tree delivers it, so a pen writes at once.
     * A finger while the pen is near is treated as the resting hand. An inactive canvas has input off, so a refused contact does nothing.
     */
    private fun focusPaneForContact(event: MotionEvent) {
        val second = companionInk ?: return
        val stylus = (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        val samsung = InkCanvas.isSamsungContact(event)
        if (stylus) {
            penSeenAt = event.eventTime
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> penNear = true
                MotionEvent.ACTION_HOVER_EXIT -> penNear = false
            }
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN && !(samsung && event.actionMasked == 211)) return
        val target = if (overCanvas(primaryInk, event)) primaryInk else if (overCanvas(second, event)) second else return
        if (target === ink || !ink.inputEnabled || frame.coversInk(event.x, event.y) || frame.openPopover != null || frame.choosingPages || frame.dragTarget != null ||
            textEditor != null || photoCropEditor != null || Eyedropper.isArmed(ink)) return
        if (!stylus && (penNear || event.eventTime - penSeenAt < PANE_PALM_MS)) return
        focusPane(target)
    }
    private fun routeSamsungContact(event: MotionEvent): Boolean {
        if (!::primaryInk.isInitialized || !InkCanvas.isSamsungContact(event) || frame.dragTarget != null || libraryScreen != null) return false
        val location = IntArray(2); ink.getLocationInWindow(location)
        if (!ink.isEditing && (event.x < location[0] || event.x >= location[0] + ink.width || event.y < location[1] || event.y >= location[1] + ink.height ||
                frame.coversInk(event.x, event.y))) return false
        val local = MotionEvent.obtain(event)
        return try { local.offsetLocation(-location[0].toFloat(), -location[1].toFloat()); ink.dispatchGenericMotionEvent(local) }
        finally { local.recycle() }
    }
    /** P568: the first real pen ends the automatic finger writing. Every real event passes here, wherever on screen it lands. */
    private fun notePen(event: MotionEvent) {
        if (app.penSeen || (0 until event.pointerCount).none { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) return
        app.penSeen = true
        if (::primaryInk.isInitialized) primaryInk.penArrived()
        companionInk?.penArrived()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        notePen(event)
        if(appLockGate.locked)return true
        tracePen(event)
        if (::primaryInk.isInitialized) focusPaneForContact(event)
        if (::primaryInk.isInitialized) ToolCursor.contact(ink, event)
        if (::primaryInk.isInitialized && Eyedropper.touch(ink, event)) return true
        return routeSamsungContact(event) || super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        notePen(event)
        if(appLockGate.locked)return true
        tracePen(event)
        if (::primaryInk.isInitialized) focusPaneForContact(event)
        if (::primaryInk.isInitialized) {
            if (InkCanvas.isSamsungContact(event)) ToolCursor.contact(ink, event)
            else ToolCursor.hover(ink, event)
        }
        // A pen can leave while Library/settings cover the editor. Its exit must still release palm protection.
        if (::primaryInk.isInitialized && (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                (libraryScreen == null && !frame.settingsOpen)) &&
            (0 until event.pointerCount).any { event.getToolType(it) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER) }) {
            val location = IntArray(2); ink.getLocationInWindow(location)
            ink.penHover(event.actionMasked, event.x - location[0], event.y - location[1], event.eventTime)
        }
        return routeSamsungContact(event) || super.dispatchGenericMotionEvent(event)
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = app.exportSnapshot
        app.exportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.exportStarted()
            app.exporting.execute {
                val result = runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { NotebookPackage.write(snapshot, it) } ?: error("Cannot open export destination") }
                app.main.post { repository.exportFinished(); message(result.fold({ "Notebook exported" }, { "Export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("Export was interrupted; please try again")
    }
    /** P716/P717: a compressed copy of the memory report goes where the user chooses; the report stays here until deleted. */
    private val saveMemoryReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri == null) return@registerForActivityResult
        message("Saving the memory report…")
        app.exporting.execute {
            val result = runCatching { MemoryReport.save(app, uri) }
            app.main.post {
                if (isDestroyed) return@post
                val mb = { bytes: Long -> String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0) }
                message(result.fold({ saved ->
                    val shown = saved.reported
                    if (shown != null && shown < saved.written) "Wrote ${mb(saved.written)} MB, but the chosen place shows ${mb(shown)} MB so far. The report stays on this device."
                    else "Memory report saved (${mb(saved.written)} MB, compressed). It stays on this device until deleted in Library sync."
                }, { "The memory report could not be saved: ${it.message ?: it.javaClass.simpleName}" }))
            }
        }
    }
    /** P123 (F196): several notebooks at once; ones already in the library are skipped and the outcome is reported. */
    private val import = registerForActivityResult(ImportChooser.Many()) { uris ->
        if (uris.isNotEmpty()) {
            val openImported = libraryScreen == null
            repository.setFileBusy(true)
            app.io.execute {
                val names = uris.map { uri ->
                    runCatching {
                        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment ?: "notebook"
                }
                val files = uris.zip(names) { uri, name ->
                    name to { app.contentResolver.openInputStream(uri)?.use { NotebookImport.read(app.cacheDir, it, name,app.attachmentDirectory) } ?: error("Cannot open notebook") }
                }
                app.main.post {
                    repository.setFileBusy(false)
                    if(names.any {it.endsWith(".pdf",ignoreCase=true)}) {
                        chooseVisualPdfBatch(uris,names,openImported)
                    } else if (!importFilesWithPasswords(files, openImported) { batch ->
                        showNotebookImportResult(batch)
                    }) message("Wait for the local save, then import again")
                }
            }
        }
    }
    private val importPdf = registerForActivityResult(ImportChooser.One()) { uri ->
        if (uri != null) preparePdfSelection(uri, null)
    }
    /** Instant sync: the user's own Cloudflare server, saved as a zip from the app's assets (server/sync-server in the source). */
    private val serverFiles = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        app.io.execute {
            val result = runCatching {
                val names = assets.list("sync-server").orEmpty().sorted()
                check(names.isNotEmpty()) { "the server files are missing from this build" }
                (contentResolver.openOutputStream(uri, "wt") ?: error("the file could not be opened")).use { raw ->
                    java.util.zip.ZipOutputStream(raw).use { zip ->
                        for (name in names) {
                            zip.putNextEntry(java.util.zip.ZipEntry("scribella-sync-server/$name"))
                            assets.open("sync-server/$name").use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            app.main.post { message(result.fold({ "Server files saved. Copy the zip to a computer, unzip it and follow README.md." }, { "Could not save the server files: ${it.message}" })) }
        }
    }
    fun saveSyncServerFiles() { runCatching { serverFiles.launch("scribella-sync-server.zip") }.onFailure { message("Cannot save the server files: ${it.message}") } }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val snapshot = app.pdfExportSnapshot
        val includeReviewed = app.pdfExportReviewedHandwriting
        val editableInk = app.pdfExportEditableInk
        val smallerFile = app.pdfExportSmallerFile
        app.pdfExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.exportStarted()
            app.exporting.execute {
                val result = runCatching {
                    // P094 (F127): PDF and image pages keep their printed text as an invisible, searchable layer.
                    val staged = PdfExport.create(snapshot, app.cacheDir, includeReviewedHandwriting = includeReviewed, editableInk = editableInk, smallerFile=smallerFile) { page -> runCatching { PageText.read(app, snapshot, page) }.getOrNull() }
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                            ?: error("Cannot open PDF destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.exportFinished(); message(result.fold({ "PDF exported" }, { "PDF export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("PDF export was interrupted; please try again")
    }
    // Registered after the original launchers to preserve their result keys.
    private val insertPdf = registerForActivityResult(ImportChooser.One()) { uri ->
        val target = app.pdfInsertTarget
        app.pdfInsertTarget = null
        if (uri != null && target != null) preparePdfSelection(uri, target)
        else if (uri != null) message("PDF insertion was interrupted. Please choose the PDF again.")
    }
    // Append registrations: existing document launcher keys must remain stable.
    private val exportPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val snapshot = app.pngExportSnapshot
        app.pngExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.exportStarted()
            app.exporting.execute {
                val result = runCatching {
                    val staged = PageImageExport.create(snapshot.notebook, snapshot.pageId, app.cacheDir, snapshot.smallerFile)
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                            ?: error("Cannot open PNG destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.exportFinished(); message(result.fold({ "PNG exported" }, { "PNG export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("PNG export was interrupted; please try again")
    }
    // Appended so every previous ActivityResult key stays stable.
    private val insertImage = registerForActivityResult(ImportChooser.One()) { uri ->
        val target = app.imageInsertTarget
        app.imageInsertTarget = null
        if (uri != null && target != null) {
            if (!repository.canInsertImage(target)) message("The notebook or page changed. Choose Insert image page again.")
            else {
                repository.setFileBusy(true)
                app.importing.execute {
                    val parsed = runCatching {
                        app.contentResolver.openInputStream(uri)?.use { ImageSupport.import(it) } ?: error("Cannot open image")
                    }
                    app.main.post {
                        repository.setFileBusy(false)
                        parsed.fold(onSuccess = { source -> placeImportedPages(target, source) },
                            onFailure = { message("Image insertion failed: ${it.message}") })
                    }
                }
            }
        } else if (uri != null) message("Image insertion was interrupted. Please choose the image again.")
    }
    // Always append launchers so existing document result keys remain stable.
    private val insertPhoto = registerForActivityResult(ImportChooser.One()) { uri ->
        val target = app.photoInsertTarget
        app.photoInsertTarget = null
        if (uri != null && target != null) {
            if (!repository.canInsertImage(target)) message("The notebook or page changed. Choose Insert photo again.")
            else {
                repository.setFileBusy(true)
                app.importing.execute {
                    val parsed = runCatching {
                        app.contentResolver.openInputStream(uri)?.use { ImageSupport.importPhoto(it) } ?: error("Cannot open image")
                    }
                    app.main.post {
                        repository.setFileBusy(false)
                        parsed.fold(onSuccess = { photo -> placeImportedPhoto(target, photo) },
                            onFailure = { message("Photo insertion failed: ${it.message}") })
                    }
                }
            }
        } else if (uri != null) message("Photo insertion was interrupted. Please choose the image again.")
    }
    // P083 scan capture; appended after insertPhoto so earlier launcher keys stay stable.
    private val scanCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = ScanProcessing.target
        val file = ScanProcessing.captureFile(app)
        if (target == null) { file.delete(); return@registerForActivityResult }
        if (!taken) { file.delete(); scanResumed(); return@registerForActivityResult }
        openScan(file)
    }
    // P084 attach file; appended after scanCapture so earlier launcher keys stay stable.
    private val attachFile = registerForActivityResult(ImportChooser.One()) { uri ->
        val target = app.attachInsertTarget
        app.attachInsertTarget = null
        if (uri != null && target != null) {
            if (!repository.canInsertImage(target)) message("The notebook or page changed. Choose Attach file again.")
            else {
                repository.setFileBusy(true)
                app.importing.execute {
                    val read = runCatching {
                        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                        } ?: uri.lastPathSegment
                        val asset = app.contentResolver.openInputStream(uri)?.use { input ->
                            dev.ownednotes.core.AttachmentAsset.fromStream(input,app.attachmentDirectory)
                        } ?: error("Cannot open file")
                        Triple(asset, name, app.contentResolver.getType(uri))
                    }
                    app.main.post {
                        repository.setFileBusy(false)
                        read.fold(onSuccess = { (asset, name, type) ->
                            runCatching { repository.insertAttachment(target, asset, name, type) }.onSuccess { id ->
                                message(if (id == null) "The notebook or page changed. Choose Attach file again." else "Attached file")
                            }.onFailure { message("Could not attach the file: ${it.message}") }
                        }, onFailure = { message("Could not attach the file: ${it.message}") })
                    }
                }
            }
        } else if (uri != null) message("Attaching was interrupted. Please choose the file again.")
    }
    // P088 library backup folder; appended after attachFile so earlier launcher keys stay stable.
    private val backupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        runCatching { LibraryBackup.setFolder(this, tree) }.onSuccess { backUpLibrary(announce = true) }
            .onFailure { message("Cannot use that folder for backups: ${it.message}") }
    }
    // P107 (F189): appended after backupFolder so earlier launcher keys stay stable.
    private val exportText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { finishTextExport(it, markdown = false) }
    private val exportMarkdown = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { finishTextExport(it, markdown = true) }
    // P139 (F170): the sync folder; appended so earlier launcher keys stay stable.
    private val syncFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        runCatching { LibrarySync.setFolder(app, tree) }.onSuccess { syncLibrary(announce = true) }
            .onFailure { message("Cannot use that folder for sync: ${it.message}") }
    }
    // Append launcher after syncFolder to preserve all existing result keys.
    private val exportStudyInk = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = app.studyInkExportSnapshot
        app.studyInkExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.exportStarted()
            app.exporting.execute {
                val result = runCatching {
                    val staged = StudyInkExport.create(snapshot, app.cacheDir)
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { out -> staged.inputStream().use { it.copyTo(out) } }
                            ?: error("Cannot open export destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.exportFinished(); message(result.fold(
                    { "Handwriting-only ZIP exported; PDFs, images and attachments excluded" },
                    { "Handwriting export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("Handwriting export was interrupted; please try again")
    }

    private data class NotebookSourceTarget(val id: String, val source: String)
    private data class NotebookSourceSelection(val target: NotebookSourceTarget, val selected: String)
    private var notebookSourceTarget: NotebookSourceTarget? = null
    private var notebookSourceSelection: NotebookSourceSelection? = null
    internal val pendingNotebookSourceId get() = notebookSourceTarget?.id ?: notebookSourceSelection?.target?.id
    // Appended after exportStudyInk: every existing Activity-result registration retains its key.
    private val notebookSourceFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        finishNotebookSourceSelection(it)
    }
    // P201 step 10: Import from Photos on the Scan screen; appended last so earlier launcher keys stay stable.
    private val scanPhoto = registerForActivityResult(ImportChooser.One()) { uri ->
        if (ScanProcessing.target == null) return@registerForActivityResult
        if (uri == null) { scanResumed(); return@registerForActivityResult }
        val file = ScanProcessing.captureFile(app).also { it.delete() }
        app.importing.execute { // B80 (P707): scan pages are cache files, not behind folder sync
            val copied = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("Cannot open image")
            }
            app.main.post {
                copied.onSuccess { openScan(file) }.onFailure { file.delete(); message("Cannot open that photo: ${it.message}"); scanResumed() }
            }
        }
    }
    // P247: Android's camera question the first time Scan is used; appended last so earlier launcher keys stay stable.
    /** P702: asked when "Keep receiving in the background" is turned on; the switch works either way (the notification is hidden without it). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    internal fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (ScanProcessing.target == null) return@registerForActivityResult
        if (granted) { ScanProcessing.live = true; openScan(null); return@registerForActivityResult }
        // Without the permission Android refuses the system camera too, so a picture from Photos is the way left.
        message("The camera is off for this app. Choose a photo to scan, or allow the camera in Android settings.")
        pickScanPhoto()
    }

    // Appended after existing launchers, preserving their saved result keys.
    private val changedSyncFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        if (!repository.canChangeNotebook) { message("Wait for the local save, then choose the test folder again."); return@registerForActivityResult }
        repository.setFileBusy(true)
        LibrarySync.prepareChangedFolder(app, tree) { result ->
            repository.setFileBusy(false)
            result.onSuccess { syncLibrary(announce = true) }.onFailure {
                message("The test folder could not be used. Choose an empty folder; your previous sync setting was kept.")
            }
        }
    }
    // Appended after all previous launchers so their ActivityResult keys do not change.
    private var batchPdfIds: List<String>? = null
    private var batchExportNative = false
    internal var batchPdfFolderLauncher: (() -> Unit)? = null
    private val batchPdfFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { finishBatchPdfExport(it) }
    // Append registrations so earlier ActivityResult keys remain stable.
    private val encryptedSyncFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if(tree!=null) encryptedSyncPrompt=EncryptedSyncPrompt(this,tree).also {it.show()}
    }
    internal var encryptedSyncPrompt: EncryptedSyncPrompt?=null
    private val newEncryptedBackupFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {tree->
        if(tree!=null)encryptedBackupPrompt=EncryptedBackupPrompt(this,tree,true).also {it.show()}
    }
    private val restoreEncryptedBackupFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {tree->
        if(tree!=null)encryptedBackupPrompt=EncryptedBackupPrompt(this,tree,false).also {it.show()}
    }
    internal var encryptedBackupPrompt:EncryptedBackupPrompt?=null
    fun encryptedBackups()=EncryptedBackupPanels.show(this)
    fun chooseEncryptedBackupFolder(restore:Boolean) {
        runCatching {if(restore)restoreEncryptedBackupFolder.launch(null)else newEncryptedBackupFolder.launch(null)}
            .onFailure {message("Cannot choose a folder")}
    }

    internal fun chooseBatchPdfFolder(ids: List<String>, includeNative: Boolean = false): Boolean {
        if (batchPdfIds != null || !repository.canChangeNotebook || ids.isEmpty() ||
            ids.distinct().size != ids.size || ids.any { id -> repository.entries.none { it.id == id && it.error == null && it.availableOffline } }) return false
        batchPdfIds = ids.toList()
        batchExportNative = includeNative
        return runCatching { batchPdfFolderLauncher?.invoke() ?: batchPdfFolder.launch(null) }.fold({ true }, {
            batchPdfIds = null; message("Cannot choose an export folder: ${it.message}"); false
        })
    }

    internal fun chooseAllExportFolder(): Boolean {
        if (!repository.canChangeNotebook) return false
        val entries = repository.entries
        if (entries.isEmpty()) { message("There are no notebooks to export."); return false }
        if (entries.any { !it.availableOffline }) { message("Download all notebooks from Storage before exporting the whole library."); return false }
        if (entries.any { it.error != null }) { message("A notebook could not be read. Use Select notebooks to export the readable ones."); return false }
        message("Choose a folder for PDF and editable notebook copies.")
        return chooseBatchPdfFolder(entries.map { it.id }, includeNative = true)
    }

    internal fun finishBatchPdfExport(tree: android.net.Uri?) {
        val ids = batchPdfIds ?: return
        batchPdfIds = null
        val includeNative = batchExportNative
        batchExportNative = false
        if (tree == null) return
        if (!repository.canChangeNotebook || ids.any { id -> repository.entries.none { it.id == id && it.error == null && it.availableOffline } }) {
            message("Wait for local saves or downloads, then select the notebooks to export again."); return
        }
        repository.setFileBusy(true)
        message(if (includeNative) "Exporting ${ids.size} notebooks as PDFs and editable copies…" else "Exporting ${ids.size} notebooks as PDFs…")
        app.io.execute {
            val result = runCatching {
                val library = dev.ownednotes.core.NotebookLibrary(java.io.File(app.storageRoot, "notebooks"), { app.attachmentDirectory })
                BatchPdfExport.run(app, tree, library, ids, app.cacheDir, includeNative = includeNative)
            }
            app.main.post {
                repository.setFileBusy(false)
                message(result.fold({ it.message }, { "PDF export failed: ${it.message}" }))
            }
        }
    }

    internal fun chooseChangedSyncFolder() {
        runCatching { changedSyncFolder.launch(null) }.onFailure { message("Cannot choose a test folder: ${it.message}") }
    }

    internal fun recoverNotebookSource(id: String): Boolean {
        if (!repository.canChangeNotebook || ink.isEditing || notebookSourceTarget != null || notebookSourceSelection != null) return false
        val source = repository.notebookSource(id)?.takeIf { FolderAccess.isTreeSource(it) } ?: return false
        notebookSourceTarget = NotebookSourceTarget(id, source)
        return runCatching {
            val tree = android.net.Uri.parse(source)
            val initial = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree))
            notebookFolderLauncher?.invoke(initial) ?: notebookSourceFolder.launch(initial)
        }.fold({ true }, { notebookSourceTarget = null; message("Cannot open the folder picker. Please try again."); false })
    }

    internal fun finishNotebookSourceSelection(tree: android.net.Uri?) {
        val target = notebookSourceTarget ?: return
        notebookSourceTarget = null
        if (tree != null) notebookSourceSelection = NotebookSourceSelection(target, tree.toString())
        refresh()
    }

    private fun applyNotebookSourceSelection() {
        val selection = notebookSourceSelection ?: return
        if (!repository.canChangeNotebook || ink.isEditing) return
        notebookSourceSelection = null
        val target = selection.target
        if (!repository.restoreNotebookSourceAccess(target.id, target.source, selection.selected) { result ->
            if (result.isSuccess && !isDestroyed && !isFinishing && repository.notebookSource(target.id) == target.source) {
                libraryScreen?.showStorage(target.id)
            }
        }) message("Folder selection did not apply. The notebook and its original source were kept.")
    }

    fun launchExportStudyInk() {
        if (ink.isEditing || !repository.canChangeNotebook || app.studyInkExportSnapshot != null) return
        val snapshot = repository.session?.notebook ?: return
        AlertDialog.Builder(this).setTitle("Export handwriting only")
            .setMessage("Exports visible pen strokes and drawings on white pages, with page identities and change hashes. PDF backgrounds, images, attachments, typed text and hidden ink are excluded. Nothing is sent to an AI automatically.")
            .setPositiveButton("Choose destination") { _, _ ->
                if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook) {
                    message("The notebook changed. Try exporting again.")
                } else {
                    app.studyInkExportSnapshot = snapshot
                    runCatching { exportStudyInk.launch("handwriting-only.zip") }
                        .onFailure { app.studyInkExportSnapshot = null; message("Cannot choose export destination: ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun prepareStudyCopy() {
        if (ink.isEditing || !repository.canChangeNotebook) return
        val snapshot = repository.session?.notebook ?: return
        val external = app.getExternalFilesDir(null)
        if (external == null) { message("Study storage is unavailable; use Export handwriting only instead"); return }
        repository.exportStarted()
        app.exporting.execute {
            val result = runCatching { StudyOutbox.publish(snapshot, app.cacheDir, java.io.File(external, "study-outbox")) }
            app.main.post {
                repository.exportFinished()
                message(result.fold({ "Handwriting-only study copy ready for PC over USB. Nothing sent to an AI." },
                    { "Study copy failed: ${it.message}" }))
            }
        }
    }

    private fun finishTextExport(uri: android.net.Uri?, markdown: Boolean) {
        val snapshot = app.textExportSnapshot
        app.textExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.exportStarted()
            app.exporting.execute {
                val result = runCatching { writeTextExport(app, uri, snapshot, markdown) }
                app.main.post { repository.exportFinished(); message(result.fold({ "Text exported" }, { "Text export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("Text export was interrupted; please try again")
    }
    // Appended after all earlier result registrations; their persisted keys remain unchanged.
    private val exportSvg = registerForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        val snapshot=app.svgExportSnapshot;app.svgExportSnapshot=null
        if(uri!=null && snapshot!=null) {
            repository.exportStarted()
            app.exporting.execute {
                val result=runCatching {
                    val staged=SvgExport.create(snapshot.notebook,snapshot.pageId,app.cacheDir)
                    try {app.contentResolver.openOutputStream(uri,"wt")?.use {out -> staged.inputStream().use {it.copyTo(out)}} ?: error("Cannot open SVG destination")}finally {staged.delete()}
                }
                app.main.post {repository.exportFinished();message(result.fold({"SVG exported"},{"SVG export failed: ${it.message}"}))}
            }
        }else if(uri!=null)message("SVG export was interrupted; please try again")
    }
    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target=audioPermissionNotebook;audioPermissionNotebook=null
        if(granted && target==repository.session?.notebook?.id && libraryScreen==null) {if(!app.audio.start())message(app.audio.status)}
        else if(!granted) message("Microphone permission is needed only to record audio")
    }
    // P662: appended after recordPermission so the earlier launcher keys stay stable.
    private val addHighlights = registerForActivityResult(ImportChooser.One()) { uri ->
        val notebookId = highlightsTarget; highlightsTarget = null
        if (uri == null || notebookId == null) return@registerForActivityResult
        repository.setFileBusy(true)
        app.importing.execute {
            val copied = runCatching {
                val file = java.io.File.createTempFile("add-highlights-", ".pdf", app.cacheDir)
                (app.contentResolver.openInputStream(uri) ?: error("Cannot open the PDF")).use { input -> file.outputStream().use { input.copyTo(it) } }
                file
            }
            app.main.post {
                repository.setFileBusy(false)
                copied.fold({ addHighlightsFrom(notebookId, it, keep = false) { report -> message(report.text) } }, { message("Couldn't open the PDF: ${it.message}") })
            }
        }
    }
    private var highlightsTarget: String? = null
    private var highlightsRunning = false
    // P689 (B1): Google's document scanner; appended after addHighlights so the earlier launcher keys stay stable.
    private val googleScan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (ScanProcessing.target == null) return@registerForActivityResult
        val pages = if (result.resultCode == RESULT_OK) GoogleScanner.pages(result.data) else emptyList()
        if (pages.isEmpty()) scanResumed() else keepGooglePages(pages)
    }

    /** P662: Study → "Add highlights from PDF…": a marked-up copy of this notebook's PDF puts its highlights into the notebook. */
    fun launchAddHighlights(): Boolean {
        val note = repository.session?.notebook ?: return false
        if (ink.isEditing || highlightsRunning || highlightsTarget != null || repository.readOnly || !repository.canChangeNotebook || repository.busy || repository.fileBusy) return false
        if (note.pages.none { it.pdfBackground != null }) return false
        highlightsTarget = note.id
        return runCatching { chooseSource(addHighlights, "Add highlights from", "pdf", arrayOf("application/pdf")) { highlightsTarget = null }; true }.getOrElse {
            highlightsTarget = null; message("Cannot choose PDF: ${it.message}"); false
        }
    }

    /** What adding one file's highlights did: for the message, and for the inbox receipt. */
    internal data class HighlightsReport(val result: String, val added: Int, val pages: Int, val repeated: Int, val leftOut: Int, val text: String)

    /**
     * P662: reads the markings in [file] and adds them to the open notebook [notebookId] as one Undo step, as ordinary
     * highlighter (or pen) strokes on the pages that show the PDF it marks. [file] is deleted afterwards unless [keep].
     */
    private fun addHighlightsFrom(notebookId: String, file: java.io.File, keep: Boolean, done: (HighlightsReport) -> Unit) {
        fun finish(report: HighlightsReport) { highlightsRunning = false; if (!keep) file.delete(); done(report); refresh() }
        val note = repository.session?.notebook
        if (note?.id != notebookId || highlightsRunning) { if (!keep) file.delete(); done(HighlightsReport("closed", 0, 0, 0, 0, "The notebook was closed, so nothing was added")); return }
        highlightsRunning = true
        repository.setFileBusy(true)
        app.importing.execute {
            val read = runCatching {
                val source = PdfHighlights.sourceOf(file, app.cacheDir)
                PdfHighlights.match(app.cacheDir, note, source)?.let { PdfHighlights.fromFile(app.cacheDir, note, file, it) }
            }
            app.main.post {
                repository.setFileBusy(false)
                val found = read.getOrElse { finish(HighlightsReport("failed", 0, 0, 0, 0, "Couldn't read highlights from this PDF: ${it.message}")); return@post }
                    ?: run { finish(HighlightsReport("no-match", 0, 0, 0, 0, "This PDF isn't a copy of this notebook's PDF, so nothing was added")); return@post }
                val skipped = if (found.leftOut > 0) " · ${found.leftOut} couldn't be read" else ""
                val repeated = if (found.repeated > 0) " · ${found.repeated} already there" else ""
                if (found.total == 0) {
                    val text = if (found.repeated > 0) "These highlights are already in the notebook" else "No highlights found in this PDF"
                    finish(HighlightsReport("nothing", 0, 0, found.repeated, found.leftOut, text + skipped)); return@post
                }
                var added = 0
                if (repository.session?.notebook?.id == notebookId) repository.edit { session -> added = session.addStrokes(found.byPage); added > 0 }
                if (added == 0) { finish(HighlightsReport("busy", 0, 0, found.repeated, found.leftOut, "The notebook is busy or read-only, so nothing was added")); return@post }
                val what = if (found.penMarks == 0) (if (added == 1) "highlight" else "highlights") else (if (added == 1) "marking" else "markings")
                val pages = found.byPage.size
                finish(HighlightsReport("added", added, pages, found.repeated, found.leftOut,
                    "Added $added $what on $pages ${if (pages == 1) "page" else "pages"}$repeated$skipped. Undo removes them."))
            }
        }
    }

    /**
     * P662 (personal tools only): marked-up PDFs an AI conversation put on the device with adb wait in
     * Android/data/<app id>/files/highlights-inbox/ until the notebook they name is open here. Each one is added like
     * "Add highlights from PDF…", then moved to done/ (or failed/) beside a .json receipt the conversation can read back.
     */
    private val inboxSkipped = mutableSetOf<String>()
    private var inboxChecking = false
    private fun checkHighlightsInbox() {
        if (!personalTools || inboxChecking || highlightsRunning || ink.isEditing || libraryScreen != null) return
        if (repository.busy || repository.fileBusy || repository.readOnly || !repository.canChangeNotebook) return
        val note = repository.session?.notebook ?: return
        val inbox = getExternalFilesDir("highlights-inbox") ?: return
        val waiting = inbox.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
            .filter { "${note.id}/${it.name}/${it.length()}/${it.lastModified()}" !in inboxSkipped }
        if (waiting.isEmpty()) return
        inboxChecking = true
        app.io.execute {
            val mine = waiting.firstOrNull { file ->
                val source = runCatching { PdfHighlights.sourceOf(file, app.cacheDir) }.getOrNull()
                val fits = source != null && (source.notebookId == note.id.lowercase() ||
                    source.assetId != null && note.pages.any { it.pdfBackground?.assetId == source.assetId })
                if (!fits) app.main.post { inboxSkipped += "${note.id}/${file.name}/${file.length()}/${file.lastModified()}" }
                fits
            }
            app.main.post {
                inboxChecking = false
                if (mine == null || repository.session?.notebook?.id != note.id) return@post
                addHighlightsFrom(note.id, mine, keep = true) { report ->
                    message(report.text)
                    val folder = java.io.File(inbox, if (report.result == "failed") "failed" else "done").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                    val base = mine.name.removeSuffix(".pdf").removeSuffix(".PDF") + "-" + stamp
                    val moved = mine.renameTo(java.io.File(folder, "$base.pdf"))
                    if (!moved) inboxSkipped += "${note.id}/${mine.name}/${mine.length()}/${mine.lastModified()}"
                    runCatching {
                        java.io.File(folder, "$base.json").writeText(org.json.JSONObject().apply {
                            put("file", mine.name); put("notebook", note.id); put("result", report.result); put("added", report.added)
                            put("pages", report.pages); put("repeated", report.repeated); put("leftOut", report.leftOut); put("message", report.text)
                        }.toString(1))
                    }
                }
            }
        }
    }
    private var audioPermissionNotebook:String?=null
    internal fun recordAudio() {
        if(app.audio.active){app.audio.stop();return}
        if(app.audio.occupied || libraryScreen!=null || !repository.canChangeNotebook || repository.readOnly)return
        if(app.audio.needsPermission && androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            audioPermissionNotebook=repository.session?.notebook?.id;recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }else if(!app.audio.start())message(app.audio.status)
    }
    /** P698: sync brought another device's Library organisation; the open Library shows it. */
    private val layoutChanged: () -> Unit = { if (!isDestroyed) libraryScreen?.reloadOrganization() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryLayoutSync.onChanged = layoutChanged
        savedInstanceState?.let { state ->
            batchPdfIds = state.getStringArrayList("batchPdfIds")?.toList()
            batchExportNative = state.getBoolean("batchExportNative", false)
            if (state.containsKey("libraryPosition")) libraryPlace = LibraryScreen.Place(state.getString("libraryFolder"), state.getString("libraryTag"),
                state.getBoolean("libraryArchived"), state.getInt("libraryPosition"), state.getInt("libraryOffset"))
            val id = state.getString("notebookSourceId")
            val source = state.getString("notebookSourceUri")
            if (id != null && source != null) {
                val target = NotebookSourceTarget(id, source)
                val selected = state.getString("notebookSourceSelected")
                if (selected == null) notebookSourceTarget = target
                else notebookSourceSelection = NotebookSourceSelection(target, selected)
            }
        }
        primaryInk = InkCanvas(this, app.repository, { refresh() }, { message(it) })
        thumbnails = PageThumbnails(cacheDir)
        frame = EditorFrame(this, primaryInk, app.repository)
        wireCanvas(primaryInk, app.repository)
        app.repository.companion?.let { it.listen(observer); syncCompanionPane() }
        // B07 (P131): a swipe past the last page offers a new one instead of doing nothing.
        setContentView(frame.root)
        appLockGate.created()
        systemBars()
        onBackPressedDispatcher.addCallback(this, closeSettings)
        if (!(savedInstanceState == null && intent?.action == QUICK_NOTE) &&
            (savedInstanceState?.getBoolean("libraryVisible", true) ?: true)) {
            showLibrary(allowLoading = true)
        }
        app.repository.listen(observer)
        app.repository.listen(dropOpened)
        app.repository.listen(savedWatch)
        quickNotePending = savedInstanceState == null && intent?.action == QUICK_NOTE
        frame.root.post { showCrashNote() }
        shareRequestId=savedInstanceState?.getString("shareRequestId") ?: java.util.UUID.randomUUID().toString()
        app.sharedNotebookImport.offer(intent,shareRequestId!!)
        refresh()
        if (libraryScreen == null) { restoreTextEditor(); restorePhotoCropEditor() }
    }

    fun launchExport() { app.exportSnapshot = repository.session?.notebook ?: return; export.launch("notebook.inknote") }
    fun launchImport() = chooseSource(import, "Import notebooks from", "notebook", arrayOf("*/*"))
    /** B34 (P197): asks which app to take the item from, then opens it there; backing out runs [cancel]. */
    private fun chooseSource(launcher: androidx.activity.result.ActivityResultLauncher<ImportChooser.Request>, title: String, key: String,
                             types: Array<String>, cancel: () -> Unit = {}) =
        ImportChooser.choose(this, title, key, types, photos = key == "photo", launch = { source ->
            runCatching { launcher.launch(ImportChooser.Request(types, source)) }
                .onFailure { cancel(); message("Cannot open that app: ${it.message}") }
        }, cancel = cancel)
    /** Library imports add books without replacing the previously open editor session. */
    internal fun importNotebookFiles(files: List<Pair<String, () -> NotebookImport.Result>>,
        openImported: Boolean = libraryScreen == null, done: (NotebookRepository.BatchImport) -> Unit = {}): Boolean {
        val library = libraryScreen.takeUnless { openImported }
        val destination = library?.openFolder
        return repository.importNotebookFiles(files, openImported) { batch ->
            val unfiled = if (library == null) 0 else batch.imported.count { !library.moveToFolder(it.id, destination) }
            done(batch)
            if (unfiled > 0) message("The destination folder is unavailable. $unfiled imported notebooks are in All notebooks.")
        }
    }
    internal fun showNotebookImportResult(batch:NotebookRepository.BatchImport) {
                        if (batch.failed.any { it.contains(NotebookImport.OFFICE_PDF_HELP) }) {
                            Kit.Sheet(this, "Import report", cancel = "Done").apply {
                                add(Kit.text(this@MainActivity, batch.message + batch.reports.joinToString("\n\n", prefix = if (batch.reports.isEmpty()) "" else "\n\n") { (name, report) -> "$name\n${report.text()}" }))
                            }.show()
                        } else if (batch.reports.isEmpty()) message(batch.message)
                        else AlertDialog.Builder(this).setTitle("Import report")
                            .setMessage(batch.message + "\n\n" + batch.reports.joinToString("\n\n") { (name, report) -> "$name\n${report.text()}" })
                            .setPositiveButton("OK", null).show()
    }
    internal var pdfPasswordPrompt: PdfPasswordPrompt? = null
    internal fun requestPdfPassword(asset: dev.ownednotes.core.PdfAsset, unlocked: ()->Unit, cancelled: ()->Unit = {}) {
        if(isDestroyed || pdfPasswordPrompt!=null)return
        val prompt=PdfPasswordPrompt(this,asset,unlocked={ pdfPasswordPrompt=null;unlocked() },cancelled={pdfPasswordPrompt=null;cancelled()})
        pdfPasswordPrompt=prompt;prompt.show()
    }
    internal fun importFilesWithPasswords(files: List<Pair<String, ()->NotebookImport.Result>>, openImported: Boolean,
        done: (NotebookRepository.BatchImport)->Unit): Boolean {
        if(!repository.canChangeNotebook)return false
        val parsed=mutableListOf<Pair<String,()->NotebookImport.Result>>()
        fun next(index: Int) {
            if(isDestroyed) { repository.setFileBusy(false);return }
            if(index==files.size) {
                repository.setFileBusy(false)
                if(!importNotebookFiles(parsed,openImported,done))message("Wait for the local save, then import again")
                return
            }
            repository.setFileBusy(true)
            val (name,read)=files[index]
            app.io.execute {
                val result=runCatching(read)
                app.main.post {
                    if(isDestroyed) { repository.setFileBusy(false);return@post }
                    val error=result.exceptionOrNull()
                    if(error is PdfAccess.PasswordRequired) {
                        repository.setFileBusy(false)
                        requestPdfPassword(error.asset,unlocked={next(index)},cancelled={
                            parsed+=name to { error("PDF password entry cancelled") };next(index+1)
                        })
                    } else {
                        parsed+=name to { result.getOrThrow() };next(index+1)
                    }
                }
            }
        }
        next(0);return true
    }
    /** PDF members of a Library batch each receive a preview before any notebook is imported. */
    private fun chooseVisualPdfBatch(uris:List<android.net.Uri>,names:List<String>,openImported:Boolean) {
        val files=mutableListOf<Pair<String,()->NotebookImport.Result>>()
        fun next(index:Int) {
            if(isDestroyed)return
            if(index==uris.size){
                if(!importFilesWithPasswords(files,openImported,::showNotebookImportResult))message("Wait for the local save, then import again")
                return
            }
            val uri=uris[index];val name=names[index]
            if(name.endsWith(".pdf",ignoreCase=true)) {
                preparePdfSelection(uri,null,selected={source->files+=name to {NotebookImport.Result(source)};next(index+1)})
            } else {
                files+=name to {app.contentResolver.openInputStream(uri)?.use {NotebookImport.read(app.cacheDir,it,name,app.attachmentDirectory)} ?: error("Cannot open notebook")}
                next(index+1)
            }
        }
        next(0)
    }
    private fun preparePdfSelection(uri: android.net.Uri, target: PdfInsertTarget?,selected:((Notebook)->Unit)?=null) {
        if (target != null && !repository.canInsertPdf(target)) {
            message("The notebook or page changed. Choose Insert PDF pages again."); return
        }
        repository.setFileBusy(true)
        app.importing.execute {
            var sourceTitle="Imported PDF"
            val result = runCatching {
                runCatching {app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
                    if(cursor.moveToFirst())sourceTitle=cursor.getString(0)?.substringBeforeLast('.')?.ifBlank {"Imported PDF"} ?: "Imported PDF"
                }}
                app.contentResolver.openInputStream(uri)?.use { PdfSupport.prepare(app.cacheDir, it,app.attachmentDirectory) }
                    ?: error("Cannot open PDF")
            }
            app.main.post {
                repository.setFileBusy(false)
                if (!isDestroyed) result.onSuccess { prepared -> pdfPageSelectionDialog(prepared, target,sourceTitle,selected) }
                    .onFailure { if(it is PdfAccess.PasswordRequired) requestPdfPassword(it.asset, {preparePdfSelection(uri,target,selected)})
                        else message("PDF import failed: ${it.message}") }
            }
        }
    }

    internal fun pdfPageSelectionDialog(prepared: PdfSupport.Prepared, target: PdfInsertTarget?,sourceTitle:String="Imported PDF",selected:((Notebook)->Unit)?=null): PdfPagePicker {
        val fromLibrary=libraryScreen!=null
        val capacity = minOf(dev.ownednotes.core.NotebookLimits.PDF_PAGES,
            dev.ownednotes.core.NotebookLimits.PAGES - (target?.notebook?.pages?.size ?: 0))
        return PdfPagePicker(this,prepared,capacity,accept={indices->
            if (target != null && !repository.canInsertPdf(target)) {
                message("The notebook changed. Choose Insert PDF pages again.")
            } else {
                repository.setFileBusy(true)
                app.importing.execute {
                    val result=runCatching {PdfSupport.importSelected(app.cacheDir,prepared,sourceTitle,indices)}
                    app.main.post {
                        repository.setFileBusy(false)
                        result.onSuccess {chosen->pdfPlacement(chosen,target,sourceTitle) {source,note->
                            runCatching {
                                if(selected!=null)selected(source)
                                else if(target==null) {
                                    check(importNotebookFiles(listOf(source.title to {NotebookImport.Result(source)}),openImported=!fromLibrary) {batch->
                                        if(batch.failed.isNotEmpty())showNotebookImportResult(batch)
                                        else message("Imported ${source.pages.size} of ${prepared.total} PDF pages")
                                    }) {"Wait for the local save, then import again"}
                                } else {
                                    val ids=repository.insertPdfPages(target,source)
                                    check(ids.isNotEmpty()) {"The notebook changed. Insert PDF pages again."}
                                    if(!isDestroyed && ::frame.isInitialized)frame.revealPage(ids.first())
                                    message("Inserted ${ids.size} of ${prepared.total} PDF pages")
                                }
                                note?.let(::message)
                            }.onFailure {message("PDF import failed: ${it.message}")}
                        }}.onFailure {message("PDF import failed: ${it.message}")}
                    }
                }
            }
        }).show().also {pdfSelectionDialog=it}
    }
    internal var pdfSelectionDialog: PdfPagePicker? = null
    internal var pdfFitSheet: PdfFitSheet? = null

    /**
     * P671 (board 6): when the chosen PDF pages differ in shape from the paper they land on, asks once (or uses the
     * remembered answer) whether to fit them to the paper or keep their size, then hands [done] the notebook to import and
     * a note when fitting could not be done (a locked or turned PDF keeps its size). Closing the sheet imports nothing.
     */
    private fun pdfPlacement(source: Notebook, target: PdfInsertTarget?, title: String, done: (Notebook, String?) -> Unit) {
        val paper = PdfFit.paper(this, target)
        val differing = PdfFit.differing(source, paper)
        if (differing.isEmpty() || isDestroyed) { done(source, null); return }
        val preferences = getSharedPreferences(app.preferencesName, 0)
        fun place(choice: PdfFit.Choice) {
            if (choice == PdfFit.Choice.KEEP) { done(source, null); return }
            repository.setFileBusy(true)
            app.importing.execute {
                val fitted = runCatching { PdfFit.fitted(app, source, paper) }
                app.main.post {
                    repository.setFileBusy(false)
                    if (isDestroyed) return@post
                    fitted.onSuccess { done(it, null) }.onFailure { done(source, "Kept the PDF's size: ${it.message}") }
                }
            }
        }
        PdfFit.remembered(preferences)?.let { place(it); return }
        val common = source.pages.filter { it.id in differing }.groupingBy { it.width to it.height }.eachCount().maxBy { it.value }.key
        val name = if (title == "Imported PDF") title else "$title.pdf"
        pdfFitSheet = PdfFitSheet(this, name, source.pages.size, common, paper, target != null) { choice, remember ->
            if (remember) PdfFit.remember(preferences, choice)
            place(choice)
        }.show()
    }

    fun pdfSourcesDialog() {
        val note = repository.session?.notebook ?: return
        val sources = note.assets.filter { asset -> note.pages.any { it.pdfBackground?.assetId == asset.id } }
        if (sources.isEmpty()) { message("No PDF sources in this notebook"); return }
        fun current() = repository.session?.notebook === note && !repository.readOnly && repository.canChangeNotebook
        AlertDialog.Builder(this).setTitle("PDF sources")
            .setItems(sources.mapIndexed { index, asset ->
                val pages = note.pages.withIndex().filter { it.value.pdfBackground?.assetId == asset.id }
                "PDF ${index + 1} - ${pages.size} pages - first at notebook page ${pages.first().index + 1}"
            }.toTypedArray()) { _, index ->
                val asset = sources[index]
                val count = note.pages.count { it.pdfBackground?.assetId == asset.id }
                fun remove(deletePages: Boolean) {
                    if (!current()) { message("The notebook changed. Open PDF sources again."); return }
                    repository.edit { it.removePdfSource(asset.id, deletePages) }
                    message(if (deletePages) "Removed $count PDF pages. Undo is available." else "PDF removed; page ink and objects kept. Undo is available.")
                }
                AlertDialog.Builder(this).setTitle("Remove PDF source?")
                    .setMessage("This source is used on $count notebook pages, including repeated imports of the same file. Keep annotations removes PDF backgrounds but keeps pages, handwriting and other objects. Delete pages also deletes their annotations. Undo restores either operation.")
                    .setPositiveButton("Keep annotations") { _, _ -> remove(false) }
                    .setNeutralButton("Delete pages…") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Delete $count PDF pages and their annotations?")
                            .setMessage("All pages using this source will be removed together. Other pages stay. An empty notebook gets one blank page.")
                            .setPositiveButton("Delete pages") { _, _ -> remove(true) }
                            .setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    fun launchImportPdf() = chooseSource(importPdf, "Import PDF from", "pdf", arrayOf("application/pdf"))
    fun launchInsertPdf(): Boolean {
        if (ink.isEditing || app.pdfInsertTarget != null || app.imageInsertTarget != null || app.photoInsertTarget != null) return false
        val target = repository.capturePdfInsertTarget() ?: return false
        app.pdfInsertTarget = target
        return runCatching { chooseSource(insertPdf, "Import PDF pages from", "pdf", arrayOf("application/pdf")) { app.pdfInsertTarget = null }; true }.getOrElse {
            app.pdfInsertTarget = null; message("Cannot choose PDF: ${it.message}"); false
        }
    }
    fun launchInsertImage(): Boolean {
        if (ink.isEditing || app.imageInsertTarget != null || app.pdfInsertTarget != null || app.photoInsertTarget != null) return false
        val target = repository.captureImageInsertTarget() ?: return false
        app.imageInsertTarget = target
        return runCatching { chooseSource(insertImage, "Import image page from", "photo", arrayOf("image/*")) { app.imageInsertTarget = null }; true }.getOrElse {
            app.imageInsertTarget = null; message("Cannot choose image: ${it.message}"); false
        }
    }
    /**
     * P720: imported image pages go in after the chosen page; a single one then opens on the crop screen by itself, where
     * Add page keeps it (cropped, straightened or in a look, as one Undo step with the import) and the cross takes it out.
     */
    internal fun placeImportedPages(target: ImageInsertTarget, source: dev.ownednotes.core.Notebook) {
        runCatching { repository.insertImagePages(target, source) }.onSuccess { ids ->
            if (ids.isEmpty()) message("The notebook or page changed. Insert image page again.")
            else {
                if (!isDestroyed && ::frame.isInitialized) frame.revealPage(ids.first())
                val mark = repository.session?.localSteps
                if (ids.size == 1 && mark != null && !isDestroyed) cropImported(null, mark) else message("Inserted image page")
            }
        }.onFailure { message("Image insertion failed: ${it.message}") }
    }
    /** P720: an imported photo goes on the page, selected, and opens on the crop screen by itself, as an image page does. */
    internal fun placeImportedPhoto(target: ImageInsertTarget, photo: ImageSupport.ImportedPhoto) {
        runCatching { repository.insertPhoto(target, photo) }.onSuccess { id ->
            if (id == null) message("The notebook or page changed. Choose Insert photo again.")
            else {
                if (!isDestroyed && ::primaryInk.isInitialized) ink.selectPhoto(id)
                val mark = repository.session?.localSteps
                if (mark != null && !isDestroyed) cropImported(id, mark) else message("Inserted photo")
            }
        }.onFailure { message("Photo insertion failed: ${it.message}") }
    }
    fun launchInsertPhoto(): Boolean {
        if (ink.isEditing || app.photoInsertTarget != null || app.imageInsertTarget != null || app.pdfInsertTarget != null) return false
        val target = repository.captureImageInsertTarget() ?: return false
        app.photoInsertTarget = target
        return runCatching { chooseSource(insertPhoto, "Import photo from", "photo", arrayOf("image/*")) { app.photoInsertTarget = null }; true }.getOrElse {
            app.photoInsertTarget = null; message("Cannot choose photo: ${it.message}"); false
        }
    }
    /**
     * F105/F106, P247: opens the scan screen with the in-app camera, asking for the camera permission the first time.
     * Refused, it offers Photos; with no camera, Photos. Tests can turn the in-app camera off to drive the system camera.
     */
    fun launchScan(): Boolean {
        if (ink.isEditing || ScanProcessing.target != null || app.photoInsertTarget != null || app.imageInsertTarget != null || app.pdfInsertTarget != null) return false
        val target = repository.captureImageInsertTarget() ?: return false
        ScanProcessing.target = target
        // P689 (B1): Google's scanner when it is the remembered choice and Play services has it.
        if (GoogleScanner.chosen(scanPreferences) && GoogleScanner.available(this)) { startGoogleScan(); return true }
        return openOwnCamera()
    }
    private val scanPreferences get() = getSharedPreferences(app.preferencesName, 0)
    /** Scribella's own camera for this scan: the live view, asking for the camera the first time; Photos without a camera. */
    internal fun openOwnCamera(): Boolean {
        ScanProcessing.target ?: return false
        ScanProcessing.google = false
        if (!ScanProcessing.liveAllowed) {
            val file = ScanProcessing.captureFile(app).also { it.delete() }
            return runCatching {
                scanCapture.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.scans", file)); true
            }.getOrElse { ScanProcessing.target = null; message("Cannot open the camera: ${it.message}"); false }
        }
        if (!ScanCamera.available(this)) { message("This device has no camera. Choose a photo to scan."); pickScanPhoto(); return true }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ScanProcessing.live = true; openScan(null); return true
        }
        return runCatching { cameraPermission.launch(android.Manifest.permission.CAMERA); true }
            .getOrElse { ScanProcessing.target = null; message("Cannot open the camera: ${it.message}"); false }
    }
    /** P201 step 10: the Scan screen over the editor while a scan is being edited; null otherwise. */
    internal var scanScreen: ScanScreen? = null; private set
    /**
     * Opens the Scan screen for a captured photo, or with [file] null for the pages kept so far; tests call it with a
     * prepared file. A photo that cannot be read leaves the kept pages, or ends the scan when there are none.
     */
    internal fun openScan(file: java.io.File?, review: ScanProcessing.KeptPage? = null) {
        val target = ScanProcessing.target ?: return
        if (!::frame.isInitialized) { ScanProcessing.clear(); file?.delete(); return }
        val photo = file?.let { f ->
            runCatching { ScanProcessing.decode(f) }.getOrElse { if (review == null) f.delete(); message("Scan failed: ${it.message}"); null }
        }
        // After decoding: a screen still open deletes the capture file, which the new photo has just been read from.
        closeScan(clear = false)
        // P247: live, the screen opens on the camera even before the first page.
        if (photo == null && ScanProcessing.pages.isEmpty() && !ScanProcessing.live) { ScanProcessing.clear(); return }
        // A kept page's photo belongs to the page, so only a new capture goes to the screen to delete.
        val screen = ScanScreen(this, repository, target, file.takeIf { photo != null && review == null }, photo,
            review?.corners ?: photo?.let { ScanProcessing.detect(it) }, review.takeIf { photo != null })
        scanScreen = screen
        frame.root.addView(screen, android.widget.FrameLayout.LayoutParams(-1, -1))
        SafeArea.keep(screen)
        systemBars()
        refresh()
    }
    /** P689: the picture crop screen, while open ([openPictureCrop]). */
    internal var pictureCrop: PictureCropScreen? = null; private set
    internal fun showPictureCrop(screen: PictureCropScreen) {
        closePictureCrop(refresh = false); ink.clearSelection()
        pictureCrop = screen; frame.root.addView(screen, android.widget.FrameLayout.LayoutParams(-1, -1)); refresh()
        darkBars(PictureCropScreen.BACK)
    }
    internal fun closePictureCrop(refresh: Boolean = true) {
        val open = pictureCrop ?: return
        pictureCrop = null; frame.root.removeView(open); open.dispose()
        systemBars()
        if (refresh) refresh()
    }
    /** P722: the colour at the edge of the scan screen's background. */
    private val SCAN_EDGE = 0xff1a1715.toInt()
    /** P720: a dark full screen (the crop screen) takes the system bars into its own colour, with light icons. */
    internal fun darkBars(colour: Int) {
        window.statusBarColor = colour; window.navigationBarColor = colour
        runCatching {
            val c = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val n = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(0, c or n)
        }
    }
    /** Closes the Scan screen; [clear] also ends the scan (Add, Cancel), otherwise the kept pages wait for the next photo. */
    internal fun closeScan(clear: Boolean = true, refresh: Boolean = true) {
        val open = scanScreen ?: return
        scanScreen = null; frame.root.removeView(open); open.dispose()
        systemBars()
        if (clear) ScanProcessing.clear()
        if (refresh) refresh()
    }
    /** The shutter on a photo: the camera again for the next page (live, the scan screen's own), keeping the pages so far. */
    internal fun captureNextScan() {
        if (ScanProcessing.target == null) return
        if (ScanProcessing.google) { startGoogleScan(); return }
        if (ScanProcessing.live) { openScan(null); return }
        val file = ScanProcessing.captureFile(app).also { it.delete() }
        runCatching { scanCapture.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.scans", file)) }
            .onFailure { message("Cannot open the camera: ${it.message}"); scanResumed() }
    }
    /** Import from Photos: a picture from the gallery or files in place of a camera photo. */
    internal fun pickScanPhoto() {
        if (ScanProcessing.target == null) return
        runCatching { chooseSource(scanPhoto, "Scan a photo from", "photo", arrayOf("image/*")) { scanResumed() } }
            .onFailure { message("Cannot choose photo: ${it.message}"); scanResumed() }
    }
    /** P686: photos shared into Scribella, waiting for the notebook the user chose ([first]) to open. */
    private var sharedPhotos: Pair<String, List<java.io.File>>? = null
    internal val sharedPhotosWaiting get() = sharedPhotos != null || sharedPhotoChoice != null
    /**
     * P686: shared photos go into notebook [id]: it opens (from the Library), then the photos become this scan's pages,
     * each with its page found and straightened, and the first opens on its corners so every page can be checked,
     * cropped, turned or removed before Add puts them after the open page as one Undo step.
     */
    internal fun scanSharedPhotos(id: String, files: List<java.io.File>) {
        val library = libraryScreen
        if (library == null && repository.session?.notebook?.id == id) { askHowToAddShared(files); return }
        sharedPhotos = id to files
        if (library == null || !library.open(id)) {
            sharedPhotos = null; files.forEach { it.delete() }
            message("Cannot open that notebook now. Share the photos again.")
        }
    }
    /** P688: the sheet asking whether shared photos become new pages or pictures on the open page; null when not shown. */
    internal var sharedPhotoChoice: SharedPhotoChoice? = null; private set
    private fun askHowToAddShared(files: List<java.io.File>) {
        val note = repository.session?.notebook
        if (note == null || sharedPhotoChoice != null || isDestroyed) { files.forEach { it.delete() }; message("Share the photos again."); return }
        val number = note.pages.indexOfFirst { it.id == repository.selectedPageId } + 1
        sharedPhotoChoice = SharedPhotoChoice(this, note.title, number, files,
            choose = { asPages -> if (asPages) startSharedScan(files) else placeSharedPhotos(files) },
            closedThen = { chosen -> sharedPhotoChoice = null; if (!chosen) files.forEach { it.delete() }; repository.mediaChanged() }).show()
    }
    /**
     * P688: shared photos placed on the open page as pictures (one centred as Import photo does, more in a grid), all in
     * one Undo step. Each can then be moved, resized or cropped like any picture.
     */
    private fun placeSharedPhotos(files: List<java.io.File>) {
        val drop = { text: String -> files.forEach { it.delete() }; message(text) }
        if (ink.isEditing || ScanProcessing.target != null || app.photoInsertTarget != null || app.imageInsertTarget != null || app.pdfInsertTarget != null)
            return drop("Finish what is open, then share the photos again.")
        val target = repository.captureImageInsertTarget() ?: return drop("This page cannot take pictures now. Share the photos again.")
        val number = target.notebook.pages.indexOfFirst { it.id == target.pageId } + 1
        repository.setFileBusy(true)
        message(if (files.size == 1) "Placing the photo…" else "Placing ${files.size} photos…")
        app.importing.execute {
            val photos = mutableListOf<ImageSupport.ImportedPhoto>(); var failed = 0
            for (file in files) { runCatching { file.inputStream().use { ImageSupport.importPhoto(it) } }.onSuccess { photos += it }.onFailure { failed++ }; file.delete() }
            // P697: on a phone with sending on, the pictures go to the other device as one drop instead.
            // B80 (P707): the sync state file is read in turn with sync, after the slow decode.
            val send = photos.isNotEmpty() && app.inLibraryOrder { PageDrops.canSend(app, target.notebook.id) }
            val jpegs = if (send) runCatching { photos.map(PageDrops::pictureJpeg) }.getOrNull() else null
            app.main.post {
                repository.setFileBusy(false)
                if (photos.isEmpty()) { message("The photos could not be read. Share them again."); return@post }
                val left = when (failed) { 0 -> ""; 1 -> " One photo could not be read."; else -> " $failed photos could not be read." }
                if (jpegs != null) return@post sendSharedPictures(target, number, photos, jpegs, left)
                placeSharedHere(target, number, photos, left, null)
            }
        }
    }
    private fun sendSharedPictures(target: ImageInsertTarget, number: Int, photos: List<ImageSupport.ImportedPhoto>, jpegs: List<ByteArray>, left: String) {
        repository.setFileBusy(true)
        PageDrops.send(app, target.notebook.id, target.pageId, number, jpegs, kind = dev.ownednotes.core.PageDrop.PICTURES) { sent ->
            repository.setFileBusy(false)
            sent.onSuccess { drop ->
                val what = PageDrops.items(jpegs.size, dev.ownednotes.core.PageDrop.PICTURES)
                message((if (drop.uploaded) "$what for page $number on the way to your other device. They show above the page list until it adds them."
                    else "$what for page $number kept to send: the sync folder can't be reached now. They go to your other device as soon as it can.") + left)
                if (!isDestroyed && ::primaryInk.isInitialized) frame.showDrops(force = true)
            }.onFailure { placeSharedHere(target, number, photos, left, "Could not send (${it.message}); placed here.") }
        }
    }
    /** P688: the shared photos on the open page as pictures, one Undo; [note] says why they were not sent. */
    private fun placeSharedHere(target: ImageInsertTarget, number: Int, photos: List<ImageSupport.ImportedPhoto>, left: String, note: String?) {
        val ids = runCatching { repository.insertPhotos(target, photos) }.getOrElse { message("Placing the photos failed: ${it.message}"); return }
        if (ids.isEmpty()) { message("The notebook or page changed. Share the photos again."); return }
        if (!isDestroyed && ::primaryInk.isInitialized && ids.size == 1) ink.selectPhoto(ids.first())
        message((note?.let { "$it " } ?: "") + (if (ids.size == 1) "Placed the photo on page $number." else "Placed ${ids.size} photos on page $number.") + left)
    }
    private fun startSharedScan(files: List<java.io.File>) {
        val drop = { text: String -> files.forEach { it.delete() }; message(text) }
        if (ink.isEditing || ScanProcessing.target != null || app.photoInsertTarget != null || app.imageInsertTarget != null || app.pdfInsertTarget != null)
            return drop("Finish what is open, then share the photos again.")
        val target = repository.captureImageInsertTarget() ?: return drop("This notebook cannot take new pages now. Share the photos again.")
        ScanProcessing.target = target; ScanProcessing.live = false
        ScanProcessing.photoFile(app) // clears pages left behind by an earlier scan
        val look = ScanProcessing.look
        message(if (files.size == 1) "Preparing the photo…" else "Preparing ${files.size} photos…")
        app.importing.execute { // B80 (P707): scan pages are cache files, not behind folder sync
            val kept = mutableListOf<ScanProcessing.KeptPage>(); var failed = 0
            for (file in files) runCatching { ScanProcessing.keepShared(app, file, look) }.onSuccess { kept += it }.onFailure { failed++; file.delete() }
            app.main.post {
                if (ScanProcessing.target !== target || isDestroyed) { kept.forEach { it.photo.delete(); it.preview.recycle() }; return@post }
                ScanProcessing.pages += kept
                if (failed > 0) message(if (failed == 1) "One photo could not be read and was left out." else "$failed photos could not be read and were left out.")
                val first = kept.firstOrNull() ?: run { ScanProcessing.clear(); return@post }
                openScan(first.photo, first)
            }
        }
    }
    /**
     * P689 (B1): opens Google's document scanner for this scan's next pages (it remembers the choice). If it cannot open,
     * Scribella's camera takes over with a short note, so Scan always works.
     */
    internal fun startGoogleScan() {
        if (ScanProcessing.target == null) return
        GoogleScanner.choose(scanPreferences, true)
        ScanProcessing.google = true; ScanProcessing.live = false
        val left = dev.ownednotes.core.NotebookLimits.PAGES - (repository.session?.notebook?.pages?.size ?: 0) - ScanProcessing.pages.size
        GoogleScanner.start(this, left, { sender ->
            if (ScanProcessing.target == null || isDestroyed) return@start
            runCatching { googleScan.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }.onFailure { googleScanFailed() }
        }, { googleScanFailed() })
    }
    private fun googleScanFailed() {
        if (ScanProcessing.target == null || isDestroyed) return
        message("Google's scanner is not ready on this device, so Scribella's camera opened.")
        openOwnCamera()
    }
    /** Scribella's own camera instead of Google's, remembered for the next scan. */
    internal fun useOwnCamera() {
        GoogleScanner.choose(scanPreferences, false)
        closeScan(clear = false)
        openOwnCamera()
    }
    /** P689 (B1): Google's finished pages join the scan's row; the first opens so each can still be cropped, turned or removed. */
    private fun keepGooglePages(uris: List<android.net.Uri>) {
        val target = ScanProcessing.target ?: return
        message(if (uris.size == 1) "Preparing the page…" else "Preparing ${uris.size} pages…")
        app.importing.execute { // B80 (P707): scan pages are cache files, not behind folder sync
            val kept = mutableListOf<ScanProcessing.KeptPage>(); var failed = 0
            for (uri in uris) runCatching {
                val file = ScanProcessing.captureFile(app).also { it.delete() }
                (app.contentResolver.openInputStream(uri) ?: error("Cannot open the page")).use { input -> file.outputStream().use { input.copyTo(it) } }
                ScanProcessing.keepShared(app, file, ScanProcessing.Look.ORIGINAL, find = false)
            }.onSuccess { kept += it }.onFailure { failed++ }
            app.main.post {
                if (ScanProcessing.target !== target || isDestroyed) { kept.forEach { it.photo.delete(); it.preview.recycle() }; return@post }
                ScanProcessing.pages += kept
                if (failed > 0) message(if (failed == 1) "One page could not be read and was left out." else "$failed pages could not be read and were left out.")
                val first = kept.firstOrNull() ?: run { scanResumed(); return@post }
                openScan(first.photo, first)
            }
        }
    }
    /** Back from the camera or a picker with nothing new: the pages kept so far, or the end of the scan. */
    private fun scanResumed() { if (ScanProcessing.pages.isEmpty() && !ScanProcessing.live) ScanProcessing.clear() else openScan(null) }
    /** P561: a kept page opens on its photo, with its corners and look, to fit it again. It keeps its place in the scan. */
    internal fun reviewScan(page: ScanProcessing.KeptPage) {
        if (ScanProcessing.target == null || page !in ScanProcessing.pages) return
        if (page.photo.exists()) { openScan(page.photo, page); return }
        // Android may clear the app's cache when storage is short. The finished page itself is still kept.
        message("The photo behind this page has been cleared, so its corners cannot be changed. The page is still kept.")
        openScan(null)
    }
    internal var pageTextDialog: AlertDialog? = null
    internal var pdfTextReading=false; private set
    private var pdfTextRequestToken=0L
    internal fun cancelPdfTextSelection() {
        pdfTextRequestToken++;pdfTextReading=false;ink.clearPdfTextSelection()
    }
    internal fun startPdfTextSelection(): Boolean {
        val note=repository.session?.notebook ?: return false
        val page=repository.currentPage ?: return false
        if(page.pdfBackground==null || pdfTextReading || ink.isEditing || !repository.canChangeNotebook) return false
        val token=++pdfTextRequestToken
        pdfTextReading=true
        message("Reading PDF text on this device…")
        app.io.execute {
            val result=runCatching { PdfTextContent.read(this,note,page) }
            app.main.post {
                if(token!=pdfTextRequestToken || isDestroyed) return@post
                pdfTextReading=false
                val current=repository.currentPage
                if(libraryScreen!=null || frame.settingsOpen || repository.session?.notebook?.id!=note.id ||
                    current==null || current.id!=page.id || current.pdfBackground!=page.pdfBackground || current.width!=page.width || current.height!=page.height) return@post
                result.onSuccess { read ->
                    if(read.glyphs.isEmpty()) message("No selectable PDF text. Use Copy printed page text (OCR) to read the page image on this device.")
                    else if(ink.beginPdfTextSelection(read)) message("Drag a finger across words, then tap Copy. Close or Back returns to normal tools.")
                }.onFailure { message("Cannot select PDF text: ${it.message}") }
            }
        }
        return true
    }
    internal fun copySelectedPdfText(): Boolean {
        val text=ink.selectedPdfText
        if(text.isEmpty()) return false
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("PDF selection",text))
        val repo=(application as NotesApplication).activeDocument
        (application as NotesApplication).clippingSource=repo.session?.notebook?.id?.let {id->repo.selectedPageId?.let {NotesApplication.ClippingSource(dev.ownednotes.core.NotebookLink(id,it),dev.ownednotes.core.TextBox.clean(text))}}
        message("Copied selected PDF text")
        return true
    }
    internal var pageTextBody: android.widget.TextView? = null
    /** P090 (F117): reads the text of the current PDF or image page and lets it be selected and copied. */
    fun pageTextDialog(): Boolean {
        val note = repository.session?.notebook ?: return false
        val page = repository.currentPage ?: return false
        if (!PageText.readable(page) || pageTextDialog != null) return false
        val density = resources.displayMetrics.density
        val status = ToolControls.caption(this, "Reading the page…")
        val body = android.widget.TextView(this).apply {
            setTextIsSelectable(true); textSize = 16f; setTextColor(Glass.text(dark)); visibility = android.view.View.GONE
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt(); setPadding(pad, pad / 2, pad, 0)
            addView(status); addView(body)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Printed text (OCR)").setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton("Copy all", null).setNegativeButton("Close") { _, _ -> }
            .setOnDismissListener { pageTextDialog = null; pageTextBody = null }.create()
        pageTextDialog = dialog; pageTextBody = body
        dialog.show()
        val copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        copy.setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Page text", body.text))
            message("Copied page text")
        }
        fun show(result: PageText.Result) {
            if (result.blocks.isEmpty()) { status.text = "No text found on this page."; return }
            status.text = "Read from the page image on this device. Check for recognition errors, then select text or copy all."
            body.text = result.text; body.visibility = android.view.View.VISIBLE; copy.isEnabled = true
        }
        PageText.cachedPage(page)?.let { show(it); return true }
        app.io.execute {
            val read = runCatching { PageText.readPage(app, note, page) }
            app.main.post {
                if (pageTextDialog !== dialog) return@post
                read.onSuccess { show(it) }.onFailure { status.text = "Cannot read this page: ${it.message}" }
            }
        }
        return true
    }
    internal var textMarkDialog: AlertDialog? = null
    internal var textMarkBody: android.widget.TextView? = null
    internal var textMarkPicker: TextWordPicker? = null
    internal var textMarkSwitch: android.widget.TextView? = null
    /** P096 (F123): select recognised words of the current PDF or image page, then highlight or underline them. */
    fun textMarkDialog(): Boolean {
        val note = repository.session?.notebook ?: return false
        val page = repository.currentPage ?: return false
        if (!page.hasBackground || textMarkDialog != null || ink.isEditing || repository.readOnly || !repository.canChangeNotebook) return false
        val density = resources.displayMetrics.density
        val status = ToolControls.caption(this, "Reading the page…")
        val body = android.widget.TextView(this).apply {
            setTextIsSelectable(true); textSize = 16f; setTextColor(Glass.text(dark)); visibility = android.view.View.GONE
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt(); setPadding(pad, pad / 2, pad, 0)
            addView(status); addView(body)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Highlight text").setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton("Highlight", null).setNeutralButton("Underline", null).setNegativeButton("Close") { _, _ -> }
            .setOnDismissListener { textMarkDialog = null; textMarkBody = null; textMarkPicker = null; textMarkSwitch = null }.create()
        textMarkDialog = dialog; textMarkBody = body
        dialog.show()
        val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL).map { dialog.getButton(it).apply { isEnabled = false } }
        var result: PageText.Result? = null
        fun mark(underline: Boolean) {
            val read = result ?: return
            val start = minOf(body.selectionStart, body.selectionEnd); val end = maxOf(body.selectionStart, body.selectionEnd)
            if (start < 0 || end <= start) { message("Select some words first"); return }
            if (repository.session?.notebook?.id != note.id || repository.currentPage?.id != page.id || ink.isEditing) { message("The page changed. Open Highlight text again."); dialog.dismiss(); return }
            val count = ink.markText(PageText.bands(read, start, end), underline)
            if (count == 0) { message("Those words could not be marked"); return }
            message(if (underline) "Underlined $count line${if (count == 1) "" else "s"}" else "Highlighted $count line${if (count == 1) "" else "s"}")
            dialog.dismiss()
        }
        buttons[0].setOnClickListener { mark(false) }; buttons[1].setOnClickListener { mark(true) }
        fun show(read: PageText.Result) {
            if (read.lines.none { it.words.isNotEmpty() }) { status.text = "No text found on this page."; return }
            result = read
            status.text = "Drag across the words on the page, then choose Highlight or Underline."
            body.text = read.text; buttons.forEach { it.isEnabled = true }
            val picker = TextWordPicker(this, page, read) { start, end -> android.text.Selection.setSelection(body.text as android.text.Spannable, start, end) }
            val switch = Kit.button(this, "Choose in a text list", Kit.Button.TINTED) {}
            // One way to choose at a time, so the page never shows a choice the text list has since changed.
            switch.setOnClickListener {
                val toList = picker.visibility == android.view.View.VISIBLE
                android.text.Selection.removeSelection(body.text as android.text.Spannable); picker.clear()
                picker.visibility = if (toList) android.view.View.GONE else android.view.View.VISIBLE
                body.visibility = if (toList) android.view.View.VISIBLE else android.view.View.GONE
                switch.text = if (toList) "Choose on the page" else "Choose in a text list"
                status.text = if (toList) "Select the words to mark, then choose Highlight or Underline." else "Drag across the words on the page, then choose Highlight or Underline."
            }
            val gap = (12 * density).toInt()
            content.addView(picker, 1, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            content.addView(switch, 2, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap; bottomMargin = gap })
            textMarkPicker = picker; textMarkSwitch = switch
            dialog.window?.setLayout(minOf((resources.displayMetrics.widthPixels * .92f).toInt(), (760 * density).toInt()), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            app.io.execute {
                val bitmap = runCatching { PageText.pageBitmap(app, note, page) }.getOrNull()
                app.main.post { if (bitmap != null) { if (textMarkPicker === picker) picker.show(bitmap) else bitmap.recycle() } }
            }
        }
        PageText.cached(page)?.let { show(it); return true }
        app.io.execute {
            val read = runCatching { PageText.read(app, note, page) }
            app.main.post {
                if (textMarkDialog !== dialog) return@post
                read.onSuccess { show(it) }.onFailure { status.text = "Cannot read this page: ${it.message}" }
            }
        }
        return true
    }
    internal var notebookSearchDialog: android.app.Dialog? = null
    internal var notebookSearchField: android.widget.EditText? = null
    internal var notebookSearchResults: android.widget.LinearLayout? = null
    internal var notebookSearchStatus: android.widget.TextView? = null
    /** P093 (F118): searches the text of PDF and image pages (read on the device), handwriting (B19) and typed text boxes. */
    fun notebookSearchDialog(looking: String = ""): Boolean {
        val note = repository.session?.notebook ?: return false
        if (notebookSearchDialog != null) return false
        val density = resources.displayMetrics.density
        val unread = note.pages.filter { PageText.readable(it) && PdfBookText.cached(it).isNullOrBlank() && PageText.cachedPage(it) == null }
        var done = note.pages.count { PageText.readable(it) } - unread.size; val total = done + unread.size
        val status = ToolControls.caption(this, "").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        // P576: a Glass sheet with the kit's search field, in place of the older system dialog.
        val field = Kit.field(this, "Search text in this notebook", glyph = Glyph.SEARCH).apply {
            textSize = 16f; contentDescription = "Search text in this notebook"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val results = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { isNestedScrollingEnabled = true; isVerticalScrollBarEnabled = false }
        val open = java.util.concurrent.atomic.AtomicBoolean(true); var failed = 0
        val snippets = SearchSnippets(thumbnails, note, scroll, results)
        val sheet = Kit.Sheet(this, "Search in notebook", cancel = "Close")
        sheet.add(field)
        sheet.add(status, 6f)
        sheet.add(scroll.apply { addView(results) }, 4f).layoutParams.height = (resources.displayMetrics.heightPixels * .45f).toInt()
        val dialog = sheet.dialog
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener { open.set(false); snippets.close(); app.searchIndexer.onInkRead = null; notebookSearchDialog = null; notebookSearchField = null; notebookSearchResults = null; notebookSearchStatus = null }
        fun refresh() {
            val query = field.text.toString()
            val hits = PageText.search(note, query)
            val reading = (if (done < total) "Reading pages for search: $done of $total. " else "") + (if (failed > 0) "$failed page(s) could not be read. " else "")
            status.text = reading + when {
                PageText.normalize(query).isEmpty() -> if (reading.isEmpty()) "Type a word or phrase." else ""
                hits.isEmpty() -> "No matches."
                else -> (if (hits.size == 1) "1 match." else "${hits.size} matches.") + (if (hits.size > SearchSnippets.MOST_ROWS) " Showing the first ${SearchSnippets.MOST_ROWS}." else "")
            }
            snippets.clear(); results.removeAllViews()
            hits.take(SearchSnippets.MOST_ROWS).forEach { hit ->
                results.addView(SearchHitRow(this, hit, note.pages[hit.pageIndex], dark).apply {
                    text = "Page ${hit.pageIndex + 1}${if (hit.typed) " · text box" else if (hit.handwriting) " · handwriting" else ""}\n${hit.excerpt}"
                    textSize = 15f; setTextColor(Glass.text(dark))
                    isClickable = true; isFocusable = true; foreground = ripple(dark, 12f * density)
                    setOnClickListener {
                        if (repository.session?.notebook !== note || ink.isEditing) { message("The notebook changed. Search again."); return@setOnClickListener }
                        dialog.dismiss(); repository.selectPage(hit.pageId); frame.revealPage(hit.pageId)
                    }
                })
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        })
        notebookSearchDialog = dialog; notebookSearchField = field; notebookSearchResults = results; notebookSearchStatus = status
        // B19 (P136): handwriting read in the background shows up while the search is open.
        app.searchIndexer.onInkRead = { if (notebookSearchDialog === dialog) refresh() }
        // P567: a page of PDF text shown in the results is read once, so its picture can show the place.
        snippets.locate = { page -> app.searchIndexer.locate(note, page, { !open.get() }) { if (notebookSearchDialog === dialog) refresh() } }
        sheet.show(); refresh()
        if (looking.isNotEmpty()) { field.setText(looking); field.setSelection(looking.length) }
        if (unread.isNotEmpty()) {
            done = 0
            app.searchIndexer.readForSearch(note, { !open.get() },
                progress = { app.main.post { if (notebookSearchDialog === dialog) refresh() } },
                read = { ok -> app.main.post { if (notebookSearchDialog === dialog) { done++; if (!ok) failed++ } } })
        }
        return true
    }
    private fun canExportPdf(expected: Notebook): Boolean = !ink.isEditing && repository.canChangeNotebook &&
        repository.session?.notebook === expected && app.pdfExportSnapshot == null
    fun launchExportPdf(): Boolean {
        val note = repository.session?.notebook ?: return false
        return launchExportPdf(note, note)
    }
    fun launchExportPng(smallerFile:Boolean=false): Boolean {
        if (ink.isEditing || !repository.canChangeNotebook || app.pngExportSnapshot != null) return false
        val note = repository.session?.notebook ?: return false
        val pageId = repository.selectedPageId ?: return false
        val index = note.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        app.pngExportSnapshot = PageImageSnapshot(note, pageId, smallerFile)
        return runCatching { exportPng.launch("page-${index + 1}.png"); true }.getOrElse {
            app.pngExportSnapshot = null; message("Cannot choose PNG destination: ${it.message}"); false
        }
    }
    private fun launchExportPdf(expected: Notebook, snapshot: Notebook, includeReviewed: Boolean = true, editableInk: Boolean = false, smallerFile:Boolean=false): Boolean {
        if (!canExportPdf(expected)) return false
        app.pdfExportSnapshot = snapshot
        app.pdfExportReviewedHandwriting = includeReviewed
        app.pdfExportEditableInk = editableInk
        app.pdfExportSmallerFile = smallerFile
        return runCatching { exportPdf.launch("notebook.pdf"); true }.getOrElse {
            app.pdfExportSnapshot = null; message("Cannot choose PDF destination: ${it.message}"); false
        }
    }
    /**
     * P201 step 8: the Export sheet's Save to Files ([share] false) and Share. Save runs the export each format already
     * had; Share writes the same file to cache and hands it to the app the user picks in Android's share sheet, as
     * attachments' Share does. False when a guard refused (pen down, busy, another export waiting, or [expected] is no
     * longer the open notebook); the sheet then stays open. A range that names no page throws, for the sheet to show.
     */
    internal fun exportFromSheet(expected: Notebook, request: ExportRequest, share: Boolean): Boolean {
        if (ink.isEditing || !repository.canChangeNotebook || repository.session?.notebook !== expected) {
            message("The notebook changed or is busy. Open Export again."); return false
        }
        val title = exportFileName(expected.title)
        return when (request.format) {
            ExportFormat.PDF -> {
                if (app.pdfExportSnapshot != null) return false
                val numbers = request.pages
                val snapshot = when {
                    numbers == null && request.paper -> expected
                    numbers == null -> PdfExport.selectPages(expected, (1..expected.pages.size).toSet(), false)
                    else -> PdfExport.selectPages(expected, numbers, request.paper)
                }
                if (!share) launchExportPdf(expected, snapshot, request.reviewed, request.editableInk, request.smallerFile)
                else shareExport("$title.pdf", "application/pdf") { file ->
                    val staged = PdfExport.create(snapshot, app.cacheDir, includeReviewedHandwriting = request.reviewed, editableInk = request.editableInk, smallerFile=request.smallerFile) { page -> runCatching { PageText.read(app, snapshot, page) }.getOrNull() }
                    try { staged.copyTo(file, overwrite = true) } finally { staged.delete() }
                }
            }
            ExportFormat.NOTEBOOK -> if (!share) { launchExport(); true }
                else shareExport("$title.inknote", "application/zip") { file -> file.outputStream().use { NotebookPackage.write(expected, it) } }
            ExportFormat.IMAGE -> {
                val pageId = repository.selectedPageId ?: return false
                if (!share) launchExportPng(request.smallerFile)
                else shareExport("page-${expected.pages.indexOfFirst { it.id == pageId } + 1}.png", "image/png") { file ->
                    val staged = PageImageExport.create(expected, pageId, app.cacheDir, request.smallerFile)
                    try { staged.copyTo(file, overwrite = true) } finally { staged.delete() }
                }
            }
            ExportFormat.SVG -> {
                val pageId=repository.selectedPageId ?: return false
                if(!share) {
                    if(app.svgExportSnapshot!=null)return false
                    app.svgExportSnapshot=SvgExportSnapshot(expected,pageId)
                    runCatching {exportSvg.launch("page-${expected.pages.indexOfFirst {it.id==pageId}+1}.svg");true}.getOrElse {
                        app.svgExportSnapshot=null;message("Cannot choose SVG destination: ${it.message}");false
                    }
                }else shareExport("page-${expected.pages.indexOfFirst {it.id==pageId}+1}.svg","image/svg+xml") {file ->
                    val staged=SvgExport.create(expected,pageId,app.cacheDir)
                    try {staged.copyTo(file,overwrite=true)}finally {staged.delete()}
                }
            }
            ExportFormat.TEXT -> if (!share) launchTextExport(expected, request.markdown)
                else shareExport(title + if (request.markdown) ".md" else ".txt", if (request.markdown) "text/markdown" else "text/plain") { file ->
                    writeTextExport(app, android.net.Uri.fromFile(file), expected, request.markdown)
                }
        }
    }
    /** P201 step 8: writes [name] in the background, then offers it to other apps; earlier shared exports are removed first. */
    private fun shareExport(name: String, mime: String, write: (java.io.File) -> Unit): Boolean {
        repository.exportStarted()
        app.exporting.execute {
            val file = runCatching {
                val root = java.io.File(cacheDir, "attachments")
                root.listFiles { f -> f.name.startsWith("export-") }?.forEach { it.deleteRecursively() }
                java.io.File(java.io.File(root, "export-${System.nanoTime()}").apply { mkdirs() }, name).also(write)
            }
            app.main.post {
                repository.exportFinished()
                if (isDestroyed) return@post
                file.fold(onSuccess = { f ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.attachments", f)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(android.content.Intent.createChooser(intent, "Share $name"))
                    }.onFailure { message(if (it is android.content.ActivityNotFoundException) "No app on this device can receive this file" else "${it.message}") }
                }, onFailure = { message("Could not prepare the export: ${it.message}") })
            }
        }
        return true
    }
    /** P107 (F189): typed and printed text as a .txt or .md file; handwriting is not converted. */
    /** P122 (F195): after an import that left something out, says what came over and what did not; null when nothing was left out. */
    fun importReportDialog(report: ImportReport): AlertDialog? {
        if (report.complete || isFinishing || isDestroyed) return null
        return AlertDialog.Builder(this).setTitle("Imported with some content left out")
            .setMessage(report.text()).setPositiveButton("OK", null).show()
    }
    fun textExportDialog(): AlertDialog? {
        val note = repository.session?.notebook ?: return null
        if (ink.isEditing || !repository.canChangeNotebook || app.textExportSnapshot != null) return null
        return AlertDialog.Builder(this).setTitle("Export text")
            .setMessage("Typed text boxes, printed text from PDF and image pages, and attached file names. Saved reviewed handwriting is included; unreviewed ink is not converted.")
            .setItems(arrayOf("Plain text (.txt)", "Markdown (.md)")) { _, which -> launchTextExport(note, markdown = which == 1) }
            .setNegativeButton("Cancel", null).show()
    }
    fun launchTextExport(expected: Notebook, markdown: Boolean): Boolean {
        if (ink.isEditing || !repository.canChangeNotebook || repository.session?.notebook !== expected || app.textExportSnapshot != null) {
            message("The notebook changed or is busy. Choose Export text again."); return false
        }
        app.textExportSnapshot = expected
        val name = exportFileName(expected.title) + if (markdown) ".md" else ".txt"
        return runCatching { (if (markdown) exportMarkdown else exportText).launch(name); true }.getOrElse {
            app.textExportSnapshot = null; message("Cannot choose a destination: ${it.message}"); false
        }
    }
    fun pdfExportDialog(): AlertDialog? {
        val note = repository.session?.notebook ?: return null
        if (!canExportPdf(note)) return null
        val current = note.pages.indexOfFirst { it.id == repository.selectedPageId } + 1
        if (current < 1) return null
        val paper = android.widget.CheckBox(this).apply {
            text = "Include notebook paper (lines, dots and color)"; isChecked = true
        }
        val reviewed = android.widget.CheckBox(this).apply {
            text = "Include reviewed handwriting as searchable text"; isChecked = true
            contentDescription = "Include reviewed handwriting"
        }
        val options = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; addView(paper); addView(reviewed) }
        return AlertDialog.Builder(this).setTitle("Export PDF copy")
            .setView(options)
            .setItems(arrayOf("All pages (${note.pages.size})", "Current page ($current)", "Page range…")) { _, which ->
                if (!canExportPdf(note)) message("The notebook changed or is busy. Choose Export PDF again.")
                else when (which) {
                    0 -> launchExportPdf(note, if (paper.isChecked) note else PdfExport.selectPages(note, "1-${note.pages.size}", false), reviewed.isChecked)
                    1 -> launchExportPdf(note, PdfExport.selectPages(note, current.toString(), paper.isChecked), reviewed.isChecked)
                    2 -> pdfPageRangeDialog(note, paper.isChecked, reviewed.isChecked)
                }
            }.setNegativeButton("Cancel", null).show()
    }
    fun pdfPageRangeDialog(expected: Notebook, includePaper: Boolean = true, includeReviewed: Boolean = true): AlertDialog? {
        if (!canExportPdf(expected)) return null
        val input = EditText(this).apply {
            hint = "1, 3-5"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Export PDF pages")
            .setMessage("Pages 1–${expected.pages.size}; ranges follow notebook order.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Export", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!canExportPdf(expected)) {
                    message("The notebook changed or is busy. Choose Export PDF again."); dialog.dismiss()
                } else runCatching { PdfExport.selectPages(expected, input.text.toString(), includePaper) }
                    .onSuccess { if (launchExportPdf(expected, it, includeReviewed)) dialog.dismiss() }
                    .onFailure { input.error = it.message }
            }
        }
        dialog.show()
        return dialog
    }

    /** P568: the links of the last PDF page a finger touched, so a tap can tell at once whether it opens one or is a dot. */
    private var pdfLinksRead: Pair<String, List<PdfLinks.Link>>? = null
    private var pdfLinksReading: String? = null
    private fun readTapTargets(pageId: String) {
        val notebook = repository.session?.notebook ?: return
        val source = notebook.pages.firstOrNull { it.id == pageId } ?: return
        if (source.pdfBackground == null || pdfLinksRead?.first == pageId || pdfLinksReading == pageId) return
        pdfLinksReading = pageId
        PdfLinks.executor.execute {
            val links = runCatching { PdfLinks.read(this, notebook, source) }.getOrDefault(emptyList())
            app.main.post { if (pdfLinksReading == pageId) pdfLinksReading = null; pdfLinksRead = pageId to links }
        }
    }
    /** Answers for the same things [followPdfLink] opens. Null: this PDF page's links are not read yet. */
    internal fun tapTarget(pageId: String, point: dev.ownednotes.core.Point): Boolean? {
        val source = repository.session?.notebook?.pages?.firstOrNull { it.id == pageId } ?: return false
        if (point.x !in 0f..source.width || point.y !in 0f..source.height) return false
        if (AudioPlayback.hit(source, point) != null || NotebookLinks.hit(source, point) != null || TextLinks.hit(source, point) != null) return true
        if (StickyNotes.hit(source, point)?.sticky?.collapsed == true) return true
        if (source.pdfBackground == null) return false
        return pdfLinksRead?.takeIf { it.first == pageId }?.second?.any { it.bounds.contains(point.x, point.y) }
    }
    private var pdfLinkBusy = false
    internal var pdfLinkSheet: Kit.Sheet? = null
        private set
    internal fun followPdfLink(pageId: String, point: dev.ownednotes.core.Point) {
        if (pdfLinkBusy || ink.isEditing || repository.busy || repository.fileBusy || libraryScreen != null) return
        val notebook = repository.session?.notebook ?: return
        val source = notebook.pages.firstOrNull { it.id == pageId } ?: return
        if (point.x !in 0f..source.width || point.y !in 0f..source.height) return
        val selected = repository.selectedPageId
        AudioPlayback.hit(source,point)?.let {moment->
            if(app.audioPlayback.play(moment.attachmentId,moment.timeMs))frame.openStudyPanel()else message(app.audioPlayback.status)
            return
        }
        NotebookLinks.hit(source,point)?.let {target->
            if(!repository.followLink(target) {repository.selectedPageId?.let {frame.revealPage(it)}})message("Linked notebook or page is unavailable. Download the notebook first if needed.")
            return
        }
        TextLinks.hit(source,point)?.let { showWebLink(notebook,selected,it);return }
        StickyNotes.hit(source,point)?.let { box ->
            if(box.sticky?.collapsed==true)StickyNotes.toggle(this,pageId,box.id)
            return
        }

        if(source.pdfBackground==null)return
        pdfLinkBusy = true
        PdfLinks.executor.execute {
            val found = runCatching { PdfLinks.read(this, notebook, source).firstOrNull { it.bounds.contains(point.x, point.y) } }
            app.main.post {
                pdfLinkBusy = false
                if (isDestroyed || repository.session?.notebook !== notebook || repository.selectedPageId != selected ||
                    ink.isEditing || repository.busy || repository.fileBusy || libraryScreen != null) return@post
                val link = found.getOrNull() ?: return@post
                if (link.sourcePage != null) {
                    val target = PdfLinks.target(notebook, source, link)
                    if (target == null) message("The linked page was not imported into this notebook")
                    else { repository.selectPage(target.id); frame.revealPage(target.id) }
                } else {
                    val uri = PdfLinks.webUri(link.uri) ?: return@post
                    showWebLink(notebook,selected,uri)
                }
            }
        }
    }

    /** Shared confirmation for PDF and typed-text URLs; opening never mutates a notebook. */
    private fun showWebLink(notebook:Notebook,selected:String?,uri:android.net.Uri) {
        pdfLinkSheet?.dialog?.dismiss()
        val sheet=Kit.Sheet(this,"Open web link?","Open",onAction={
            if(repository.session?.notebook !== notebook || repository.selectedPageId!=selected || libraryScreen!=null || ink.isEditing || isDestroyed)false
            else {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,uri)) }
                    .onFailure { message("No browser could open this link") }
                true
            }
        })
        pdfLinkSheet=sheet
        sheet.add(Kit.text(this,uri.toString(),secondary=true))
        sheet.dialog.setOnDismissListener { if(pdfLinkSheet===sheet)pdfLinkSheet=null }
        sheet.show()
    }

    fun turnPage(offset: Int): Boolean {
        if (ink.isEditing || repository.busy || repository.fileBusy) return false
        val pages = repository.session?.notebook?.pages ?: return false
        val index = pages.indexOfFirst { it.id == repository.selectedPageId }
        val target = pages.getOrNull(index + offset) ?: return false
        repository.selectPage(target.id)
        frame.revealPage(target.id)
        return repository.selectedPageId == target.id
    }
    fun insertPage() {
        var id: String? = null
        repository.edit { id = it.addPage(repository.selectedPageId!!); true }
        id?.let { repository.selectPage(it) }
    }
    /** P612: the held page's Duplicate: the copy goes after that page, which need not be the current one. */
    fun duplicatePage(pageId: String) {
        if (repository.readOnly || !repository.canChangeNotebook || repository.session?.notebook?.pages?.none { it.id == pageId } != false) return
        if (repository.selectedPageId != pageId) repository.selectPage(pageId)
        duplicatePage()
    }
    fun duplicatePage() {
        var id: String? = null
        repository.edit { id = it.duplicatePage(repository.selectedPageId!!); true }
        id?.let { repository.selectPage(it) }
    }
    fun navigateHistory(forward: Boolean): Boolean {
        if (ink.isEditing || !repository.navigateHistory(forward) {repository.selectedPageId?.let {frame.revealPage(it)}}) return false
        return true
    }
    fun setReadOnly(enabled: Boolean): Boolean {
        if (ink.isEditing || !repository.setReadOnly(enabled)) {
            message("Finish writing and saving before changing read-only mode.")
            return false
        }
        return true
    }
    /** P733 (board 26): Back and Forward as tiles; what is remembered is behind the (i). */
    internal fun navigationHistoryDialog(): PageSheets.History? {
        if (ink.isEditing || !repository.canChangeNotebook) return null
        val session = repository.session ?: return null
        val sheet = Kit.Sheet(this, "Navigation history", cancel = "Close")
        sheet.add(SyncPanels.sectionRow(this, "Links and page jumps", PageSheets.info(this, "About navigation history", "Navigation history", listOf(
            Explain.Line(SyncPanels.P.LINK, Glass.accent(night), "Links and page jumps are remembered."),
            Explain.Line(SyncPanels.P.SCROLL, SyncPanels.slate(night), "Scrolling is not.")))))
        fun travel(forward: Boolean) {
            if (repository.session !== session || !navigateHistory(forward)) {
                message("Navigation is unavailable. Reopen Navigation history.")
            }
            sheet.dismiss()
        }
        fun tile(path: String, title: String, on: Boolean, yes: String, no: String, forward: Boolean) = SyncPanels.PlaceTile(this, path, Glass.accent(night), title).apply {
            state(if (on) yes else no, Glass.secondary(night)); isEnabled = on; alpha = if (on) 1f else .45f
            setOnClickListener { travel(forward) }
        }
        val back = tile(SyncPanels.P.BACK, "Back", repository.canNavigateBack, "Where you were before", "Nothing before", false)
        val forward = tile(SyncPanels.P.ARROW, "Forward", repository.canNavigateForward, "Where you went next", "Nothing ahead", true)
        sheet.add(SyncPanels.TileGrid(this, 220f).apply { addView(back); addView(forward) }, gap = 2f)
        sheet.show()
        return PageSheets.History(sheet, back, forward)
    }
    fun bookmarkDialog(): AlertDialog? {
        if (ink.isEditing || repository.readOnly || !repository.canChangeNotebook) return null
        val snapshot = repository.session?.notebook ?: return null
        val page = repository.currentPage ?: return null
        val name = EditText(this).apply {
            contentDescription = "Bookmark name"; hint = "Bookmark name"; setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
            setText(page.bookmark ?: "Page ${snapshot.pages.indexOfFirst { it.id == page.id } + 1}")
            selectAll()
        }
        // P091: where this bookmark sits in the outline; a new bookmark starts under the previous one's level.
        var level = if (page.bookmark != null) page.outlineLevel
            else snapshot.pages.take(snapshot.pages.indexOfFirst { it.id == page.id }).lastOrNull { it.bookmark != null }?.outlineLevel ?: 1
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(name)
            addView(ToolControls.heading(this@MainActivity, "Outline level", 12f))
            addView(ToolControls.segmented(this@MainActivity, listOf("Top", "Sub", "Sub-sub"), level - 1) { level = it + 1 }.apply { contentDescription = "Outline level" })
        }
        val builder = AlertDialog.Builder(this).setTitle(if (page.bookmark == null) "Bookmark page" else "Edit bookmark")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null)
        if (page.bookmark != null) builder.setNeutralButton("Remove", null)
        return builder.create().apply {
            show()
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    if (repository.setBookmark(snapshot, page.id, name.text.toString(), level)) dismiss()
                    else name.error = "This page changed. Reopen the bookmark editor."
                }.onFailure { name.error = it.message ?: "Cannot save bookmark" }
            }
            if (page.bookmark != null) getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (repository.setBookmark(snapshot, page.id, null)) dismiss()
                else name.error = "This page changed. Reopen the bookmark editor."
            }
        }
    }
    /** P091: one Bookmarks row, indented by its outline level. */
    internal fun outlineEntry(index: Int, page: Page): String =
        "\u2003\u2003".repeat(page.outlineLevel - 1) + (if (page.outlineLevel > 1) "\u203a " else "") + "Page ${index + 1} \u00b7 ${page.bookmark}"
    /** P733 (board 26): a row per bookmark, indented by its outline level; a picture and where to add one when there are none. */
    internal fun bookmarksDialog(): PageSheets.Bookmarks? {
        if (ink.isEditing || !repository.canChangeNotebook) return null
        val snapshot = repository.session?.notebook ?: return null
        val entries = snapshot.pages.withIndex().filter { it.value.bookmark != null }
        val sheet = Kit.Sheet(this, "Bookmarks", cancel = "Close")
        if (entries.isEmpty()) sheet.add(PageSheets.Empty(this, SyncPanels.P.BOOKMARK, Glass.accent(night), "No bookmarks yet",
            hint = PageSheets.menuPath(this, "Page menu", "Bookmark page", SyncPanels.P.BOOKMARK)))
        val rows = entries.map { (index, page) ->
            SyncPanels.RowCard(this, SyncPanels.P.BOOKMARK, Glass.accent(night), page.bookmark!!).apply {
                state("Page ${index + 1}", null)
                setOnClickListener {
                    if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook)
                        message("The notebook changed. Reopen Bookmarks.")
                    else {
                        repository.selectPage(page.id)
                        frame.revealPage(page.id)
                    }
                    sheet.dismiss()
                }
            }
        }
        val levels = entries.map { it.value.outlineLevel.coerceAtLeast(1) }
        rows.forEachIndexed { i, row ->
            sheet.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dpi(10f)
                marginStart = dpi(24f) * (levels[i] - 1)
            })
        }
        sheet.show()
        return PageSheets.Bookmarks(sheet, rows, levels)
    }
    fun pageSizeDialog(): AlertDialog? {
        if (ink.isEditing || repository.readOnly || repository.busy || repository.fileBusy) return null
        val snapshot = repository.session?.notebook ?: return null
        val page = repository.currentPage ?: return null
        if (page.hasBackground) { message("Background pages retain their original dimensions"); return null }
        return PageSizeDialog.show(this, page) { w, h -> repository.resizePage(snapshot, page.id, w, h) }
    }
    fun paperDialog(): AlertDialog? {
        if (ink.isEditing || repository.readOnly || repository.busy || repository.fileBusy) return null
        val snapshot = repository.session?.notebook ?: return null
        val page = repository.currentPage ?: return null
        if (page.hasBackground) { message("Paper settings are available for blank pages"); return null }
        return PaperDialog.show(this, page) { paper -> repository.setPaper(snapshot, page.id, paper) }
    }
    internal fun movePageDialog(): PageChooser? {
        if (repository.readOnly) return null
        val snapshot = repository.session!!.notebook
        val pageId = repository.selectedPageId!!
        val current = snapshot.pages.indexOfFirst { it.id == pageId }
        val tiles = snapshot.pages.mapIndexed { i, page ->
            when {
                i == current -> PageChooser.Tile(page.id, "Page ${i + 1}, here now", PageChooser.Mark.HERE)
                i < current -> PageChooser.Tile(page.id, "Before page ${i + 1}", PageChooser.Mark.BEFORE)
                else -> PageChooser.Tile(page.id, "After page ${i + 1}", PageChooser.Mark.AFTER)
            }
        }
        return PageChooser(this, "Move page to position", snapshot, tiles, current) { target ->
            if (target != current && !repository.movePage(snapshot, pageId, target)) message("Page changed; reopen Move page")
        }.show()
    }
    /**
     * P667 (the user's item 6): picks the page shown beside this one in the landscape two-page view. Choosing this page
     * goes back to pairing by page number. Kept in the app's settings, so the notebook file does not change.
     */
    internal fun showBesideDialog(): PageChooser? {
        val snapshot = repository.session?.notebook ?: return null
        val pageId = repository.selectedPageId ?: return null
        val current = snapshot.pages.indexOfFirst { it.id == pageId }
        if (current < 0 || snapshot.pages.size < 2) return null
        val mate = ink.shownBeside(pageId)
        val tiles = snapshot.pages.mapIndexed { i, page ->
            when {
                i == current -> PageChooser.Tile(page.id, "Page ${i + 1}, here now: pair by number", PageChooser.Mark.HERE)
                page.id == mate -> PageChooser.Tile(page.id, "Page ${i + 1}, beside now", PageChooser.Mark.NONE)
                else -> PageChooser.Tile(page.id, "Page ${i + 1}", PageChooser.Mark.NONE)
            }
        }
        return PageChooser(this, "Show beside this page", snapshot, tiles, mate?.let { id -> snapshot.pages.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: current) { target ->
            val chosen = snapshot.pages.getOrNull(target) ?: return@PageChooser
            ink.showBeside(pageId, if (chosen.id == pageId) null else chosen.id)
            message(if (chosen.id == pageId) "Pages pair by number again" else "Page ${target + 1} shows beside this page in landscape")
        }.show()
    }
    /**
     * P670 (board 15): chooses the page shown in the peek, a floating window over the page being written on. [peeked] is the
     * page in the peek now, marked "in the peek"; the page open now is offered too.
     */
    internal fun peekDialog(peeked: String? = null): PageChooser? {
        val snapshot = repository.session?.notebook ?: return null
        val pageId = repository.selectedPageId ?: return null
        val current = snapshot.pages.indexOfFirst { it.id == pageId }
        if (current < 0) return null
        val tiles = snapshot.pages.mapIndexed { i, page ->
            when (page.id) {
                peeked -> PageChooser.Tile(page.id, "Page ${i + 1}, in the peek now", PageChooser.Mark.HERE)
                pageId -> PageChooser.Tile(page.id, "Page ${i + 1}, open now", PageChooser.Mark.NONE)
                else -> PageChooser.Tile(page.id, "Page ${i + 1}", PageChooser.Mark.NONE)
            }
        }
        val start = peeked?.let { id -> snapshot.pages.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: (if (current + 1 < snapshot.pages.size) current + 1 else maxOf(0, current - 1))
        return PageChooser(this, "Peek at a page", snapshot, tiles, start) { target ->
            snapshot.pages.getOrNull(target)?.let { frame.peek.open(it.id) }
        }.show()
    }
    /** P099 (F070): copies, or with [move] moves, this page to the end of another notebook. */
    internal fun pageToNotebookDialog(move: Boolean): NotebookChooser? {
        if (move && repository.readOnly) return null
        val snapshot = repository.session!!.notebook
        val pageId = repository.selectedPageId!!
        val targets = repository.otherNotebooks().filterNot { repository.isNotebookReadOnly(it.id) }
        if (targets.isEmpty()) { message("Create another notebook first"); return null }
        val name = if (move) "Move page to notebook" else "Copy page to notebook"
        return NotebookChooser(this, name, targets) {
            if (!repository.transferPage(snapshot, pageId, it.id, move, ::message)) message("Page changed; reopen $name")
        }.show()
    }
    internal fun mergeNotebookDialog(): NotebookChooser? {
        if (repository.readOnly) return null
        val snapshot = repository.session!!.notebook
        val sources = repository.otherNotebooks()
        if (sources.isEmpty()) { message("There is no other notebook to merge"); return null }
        return NotebookChooser(this, "Add all pages of another notebook", sources) {
            if (!repository.mergeNotebook(snapshot, it.id, ::message)) message("Notebook changed; reopen Merge notebook")
        }.show()
    }
    /**
     * P100 (F071, F072, F073, F076): this page's ink layers, top first. Tap a name to draw on that layer; each row can
     * be hidden, moved, renamed or deleted. Every change except choosing the active layer is one Undo step.
     */
    fun layersDialog(): Kit.Sheet? {
        if (ink.isEditing || repository.readOnly || !repository.canChangeNotebook) return null
        val pageId = repository.selectedPageId ?: return null
        lateinit var sheet: Kit.Sheet
        lateinit var refresh: () -> Unit
        fun change(operation: (DocumentSession) -> Boolean) {
            val snapshot = repository.session?.notebook
            if (snapshot == null || repository.selectedPageId != pageId) { sheet.dismiss(); message("Page changed; reopen Layers"); return }
            runCatching { repository.editLayers(snapshot, pageId, operation) }.onFailure { message(it.message ?: "Cannot change layers") }
            ink.clearSelection(); refresh()
        }
        // P733 (board 26): a sheet with Add layer in its header; what layers do is behind the (i).
        sheet = Kit.Sheet(this, "Layers", "Add layer", cancel = "Close", onAction = { change { it.addLayer(pageId); true }; false })
        sheet.add(SyncPanels.sectionRow(this, "This page · top first", PageSheets.info(this, "About layers", "Layers", listOf(
            Explain.Line(SyncPanels.P.PLUS, Glass.accent(night), "Add a layer to draw above your ink."),
            Explain.Line(SyncPanels.P.CHECK, SyncPanels.green(night), "Tap a layer to draw on it."),
            Explain.Line(SyncPanels.P.LOCK, SyncPanels.slate(night), "A locked layer can’t be selected or erased.")))))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.add(list, gap = 2f)
        fun rename(layer: Layer) {
            val input = EditText(this).apply { setText(layer.name); setSingleLine(); selectAll(); contentDescription = "Layer name"
                filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
            AlertDialog.Builder(this).setTitle("Rename layer").setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Rename") { _, _ -> change { it.renameLayer(pageId, layer.id, input.text.toString()) } }.show()
        }
        fun add(row: View) = list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (list.childCount > 0) topMargin = dpi(10f)
        })
        refresh = refresh@{
            list.removeAllViews()
            val page = repository.currentPage?.takeIf { it.id == pageId } ?: return@refresh
            // One layer: a single row; the (i) says how to add another.
            if (page.layers.isEmpty()) add(PageSheets.LayerRow(this, "Layer 1", "Drawing here · the only layer", active = true, hidden = false, controls = emptyList(), onPick = null))
            val active = repository.session?.activeLayer(pageId)
            page.layers.withIndex().reversed().forEach { (index, layer) ->
                val percent = Math.round(layer.opacity * 100)
                val controls = listOf(
                    PageSheets.well(this, if (layer.hidden) SyncPanels.P.EYE_OFF else SyncPanels.P.EYE, null, "${if (layer.hidden) "Show" else "Hide"} ${layer.name}") {
                        change { it.setLayerHidden(pageId, layer.id, !layer.hidden) }
                    },
                    // P101 (F075): a locked layer's ink stays drawn but can't be selected or erased.
                    PageSheets.well(this, if (layer.locked) SyncPanels.P.LOCK else SyncPanels.P.UNLOCK, null, "${if (layer.locked) "Unlock" else "Lock"} ${layer.name}") {
                        change { it.setLayerLocked(pageId, layer.id, !layer.locked) }
                    },
                    // P102 (F078): see-through layers for tracing.
                    PageSheets.well(this, null, "$percent%", "Opacity of ${layer.name}, $percent percent") {
                        val choices = intArrayOf(100, 75, 50, 25, 10)
                        AlertDialog.Builder(this).setTitle("Opacity of “${layer.name}”")
                            .setSingleChoiceItems(choices.map { "$it %" }.toTypedArray(), choices.indexOf(percent)) { picker, which ->
                                picker.dismiss(); change { it.setLayerOpacity(pageId, layer.id, choices[which] / 100f) }
                            }.setNegativeButton("Cancel", null).show()
                    },
                    PageSheets.well(this, SyncPanels.P.UP, null, "Move ${layer.name} up", enabled = index < page.layers.lastIndex) { change { it.moveLayer(pageId, layer.id, index + 1) } },
                    PageSheets.well(this, SyncPanels.P.DOWN, null, "Move ${layer.name} down", enabled = index > 0) { change { it.moveLayer(pageId, layer.id, index - 1) } },
                    PageSheets.well(this, SyncPanels.P.PEN, null, "Rename ${layer.name}") { rename(layer) },
                    PageSheets.well(this, SyncPanels.P.TRASH, null, "Delete ${layer.name}", danger = true, enabled = page.layers.size > 1) {
                        AlertDialog.Builder(this).setTitle("Delete “${layer.name}”?").setMessage("Removes this layer and its ink, photos and text. Undo brings them back.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> change { it.deleteLayer(pageId, layer.id) } }.show()
                    },
                )
                val state = listOfNotNull("Drawing here".takeIf { layer.id == active }, "Hidden".takeIf { layer.hidden }, "Locked".takeIf { layer.locked })
                    .joinToString(" · ").ifEmpty { "Tap to draw on it" }
                add(PageSheets.LayerRow(this, layer.name, state, layer.id == active, layer.hidden, controls) {
                    if (layer.locked) { message("Unlock ${layer.name} to draw on it"); return@LayerRow }
                    if (layer.hidden) change { it.setLayerHidden(pageId, layer.id, false) }
                    repository.session?.takeIf { repository.selectedPageId == pageId }?.selectLayer(pageId, layer.id)
                    ink.clearSelection(); refresh()
                })
            }
            sheet.actionButton?.apply { val room = page.layers.size < Page.MAX_LAYERS; isEnabled = room; alpha = if (room) 1f else .45f }
        }
        refresh()
        return sheet.show()
    }
    fun pageSettingsDialog(pageId: String? = repository.selectedPageId): Kit.Sheet? {
        if (!repository.canChangeNotebook || repository.readOnly) return null
        val snapshot = repository.session?.notebook ?: return null
        if (snapshot.pages.none { it.id == pageId }) return null
        return PageSettingsDialog.show(this, snapshot, pageId!!, standardScale = { ids ->
            if (!repository.canChangeNotebook || repository.readOnly || repository.session?.notebook !== snapshot) false
            else { repository.edit { it.matchStandardPageScale(ids) }; true }
        }, standardShape = { ids, done -> repository.matchStandardPageShape(snapshot, ids, done)
        }, applyTemplate = { ids, asset, paper, w, h, replace ->
            if (!repository.canChangeNotebook || repository.readOnly || repository.session?.notebook !== snapshot) false
            else { repository.edit { it.applyPageTemplate(ids,asset,paper,w,h,replace) }; true }
        }, told = ::message) { ids, w, h, paper, replace ->
            repository.configureNotebook(snapshot, snapshot.title, ids, w, h, paper, replace)
        }
    }
    fun pagePanelActions(pageId: String): AlertDialog? {
        if (!repository.canChangeNotebook || repository.readOnly) return null
        val book = repository.session?.notebook ?: return null
        val index = book.pages.indexOfFirst { it.id == pageId }; if (index < 0) return null
        return AlertDialog.Builder(this).setTitle("Page ${index + 1}")
            .setItems(arrayOf("Page settings…", "Delete page…")) { _, which ->
                if (which == 0) pageSettingsDialog(pageId) else deletePageDialog(pageId)
            }.show()
    }
    /** P733 (board 26): what goes, in one line, and a red Delete page button; Cancel in the header. */
    internal fun deletePageDialog(pageId: String? = repository.selectedPageId): PageSheets.DeletePage? {
        if (repository.readOnly || !repository.canChangeNotebook) return null
        val snapshot = repository.session?.notebook ?: return null
        val number = snapshot.pages.indexOfFirst { it.id == pageId } + 1
        if (number == 0) return null
        if (snapshot.pages.size <= 1) { message("Keep at least one page in the notebook"); return null }
        val sheet = Kit.Sheet(this, "Delete page $number?", cancel = "Cancel")
        sheet.add(PageSheets.consequence(this, SyncPanels.P.TRASH, SyncPanels.fault(night), "Page $number and everything on it",
            "Undo brings it back while this notebook is open."))
        val delete = LiveSyncLook.pill(this, "Delete page", Kit.Button.DANGER, SyncPanels.P.TRASH, height = 44f) {
            if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook || repository.readOnly) {
                message("Page changed; reopen Delete page")
            } else {
                repository.selectPage(pageId!!)
                if (!repository.deletePage(snapshot, pageId)) message("Page changed; reopen Delete page")
            }
            sheet.dismiss()
        }
        sheet.add(delete)
        sheet.show()
        return PageSheets.DeletePage(sheet, delete)
    }
    /** P178 (G3): deletes the pages chosen in the strip after a count confirmation; one Undo brings them all back. */
    fun deletePagesDialog(pageIds: Set<String>): AlertDialog? {
        if (repository.readOnly || !repository.canChangeNotebook || pageIds.isEmpty()) return null
        val snapshot = repository.session?.notebook ?: return null
        if (pageIds.any { id -> snapshot.pages.none { it.id == id } }) { message("The pages changed; choose again"); return null }
        if (pageIds.size >= snapshot.pages.size) { message("Keep at least one page in the notebook"); return null }
        val count = pageIds.size
        return AlertDialog.Builder(this).setTitle(if (count == 1) "Delete 1 page?" else "Delete $count pages?")
            .setMessage("Removes ${if (count == 1) "this page" else "these pages"} and ${if (count == 1) "its" else "their"} contents. One Undo brings ${if (count == 1) "it" else "them all"} back.")
            .setPositiveButton("Delete") { _, _ ->
                if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook || !repository.deletePages(snapshot, pageIds)) {
                    message("The pages changed; choose again")
                } else {
                    frame.stopChoosingPages()
                    val organizer = frame.organizer
                    if (organizer != null) {
                        // P201 step 6: the organizer covers the tool bar, so its own glass toast carries the Undo.
                        val after = repository.session?.notebook
                        Kit.toast(organizer, if (count == 1) "Deleted 1 page" else "Deleted $count pages", Kit.DANGER) {
                            if (repository.session?.notebook === after && !repository.busy && !repository.readOnly) repository.edit { it.undo() }
                        }
                    } else message(if (count == 1) "Deleted 1 page. Undo is in the tool bar." else "Deleted $count pages. Undo is in the tool bar.")
                }
            }.setNegativeButton("Cancel", null).show()
    }
    /** P178 (G3): moves the chosen pages, in their order, as one block to the start, the end or after another page. */
    internal fun movePagesDialog(pageIds: Set<String>): PageChooser? {
        if (repository.readOnly || !repository.canChangeNotebook || pageIds.isEmpty()) return null
        val snapshot = repository.session?.notebook ?: return null
        if (pageIds.any { id -> snapshot.pages.none { it.id == id } }) { message("The pages changed; choose again"); return null }
        val numbers = snapshot.pages.withIndex().associate { (i, p) -> p.id to i + 1 }
        val rest = snapshot.pages.filterNot { it.id in pageIds }
        if (rest.isEmpty()) { message("Keep at least one page out of the choice to move around"); return null }
        val tiles = listOf(PageChooser.Tile(rest.first().id, "To the start", PageChooser.Mark.BEFORE)) + rest.mapIndexed { i, page ->
            val after = "After page ${numbers[page.id]}"
            if (i == rest.lastIndex) PageChooser.Tile(page.id, "To the end", PageChooser.Mark.AFTER, "To the end, ${after.lowercase()}")
            else PageChooser.Tile(page.id, after, PageChooser.Mark.AFTER)
        }
        val count = pageIds.size
        return PageChooser(this, if (count == 1) "Move 1 page" else "Move $count pages", snapshot, tiles) { target ->
            if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook) message("The pages changed; choose again")
            else if (repository.movePages(snapshot, pageIds, target)) frame.stopChoosingPages()
            else if (repository.session?.notebook === snapshot) message("Those pages are already there")
            else message("The pages changed; choose again")
        }.show()
    }
    fun clearPageInkDialog() {
        if (repository.readOnly) return
        val snapshot = repository.session!!.notebook
        val pageId = repository.selectedPageId!!
        AlertDialog.Builder(this).setTitle("Clear this page's ink?")
            .setMessage("Removes all handwriting on this page. Its PDF background and other pages stay intact. Recovery uses this open notebook's memory-only Undo history.")
            .setPositiveButton("Clear ink") { _, _ ->
                if (!repository.clearPageInk(snapshot, pageId)) message("Page changed or already empty; reopen Clear page ink")
            }.setNegativeButton("Cancel", null).show()
    }
    fun selectionColorDialog(): AlertDialog? {
        val target = ink.selectionColorTarget() ?: return null
        val picker = RgbColorPicker(this, target.initial)
        val dialog = AlertDialog.Builder(this).setTitle("Selection color")
            .setView(android.widget.ScrollView(this).apply { addView(picker) })
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                if (ink.recolorSelection(target, picker.color())) dialog.dismiss()
                else picker.hexInput.error = "Selection changed or is busy. Reopen Selection color."
            }.onFailure { picker.hexInput.error = it.message ?: "Cannot recolor selection" }
        }
        return dialog
    }
    /** F017: one width for every selected stroke; Apply is a single Undo step. */
    /** P102 (F079): moves the selected handwriting to another unlocked layer, in one undo step. */
    internal fun selectionLayerDialog(): PictureChooser? {
        if (ink.isEditing || repository.readOnly) return null
        val pageId = repository.selectedPageId ?: return null
        val notebook = repository.session?.notebook ?: return null
        val page = notebook.pages.firstOrNull { it.id == pageId } ?: return null
        val ids = ink.selectedObjectIds
        val targets = page.layers.reversed().filterNot { it.locked || it.hidden }
        if (ids.isEmpty || page.layers.size < 2) return null
        val current = (page.strokes.filter { it.id in ids.strokes }.map { it.layer } +
            page.images.filter { it.id in ids.images }.map { it.layer } + page.textBoxes.filter { it.id in ids.texts }.map { it.layer }).distinct().singleOrNull()
        val items = targets.map { layer ->
            val count = page.strokes.count { it.layer == layer.id } + page.images.count { it.layer == layer.id } + page.textBoxes.count { it.layer == layer.id }
            val detail = if (layer.id == current) "Here now" else if (count == 0) "Empty" else if (count == 1) "1 item" else "$count items"
            PictureChooser.Item(layer.name, detail, here = layer.id == current, draw = { edge -> layerSample(notebook, page, layer, edge) })
        }
        return PictureChooser(this, "Move to layer", items) { which ->
            runCatching { if (repository.editLayers(notebook, pageId) { it.moveObjectsToLayer(pageId, ids, targets[which].id) }) ink.clearSelection() }
                .onFailure { message(it.message ?: "Cannot move to that layer") }
        }.show()
    }
    fun selectionWidthDialog(): AlertDialog? {
        val target = ink.selectionColorTarget() ?: return null
        val first = target.notebook.pages.firstOrNull { it.id == target.pageId }?.strokes?.firstOrNull { it.id in target.ids } ?: return null
        var width = first.width.coerceIn(.5f, 50f)
        val preview = StrokePreview(this, highlighter = first.isHighlighter).apply {
            style = PenStyle(first.color, width, PenKind.forBrush(first.brush))
        }
        val error = android.widget.TextView(this).apply { setTextColor(Tokens.FAULT); visibility = android.view.View.GONE; setPadding(0, dpi(8f), 0, 0) }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(8f), dpi(24f), 0)
            addView(preview)
            addView(ToolControls.slider(context, "Width · page units", .5f, 50f, width, ToolControls::roundWidth, ToolControls::formatUnits) {
                width = it; preview.style = preview.style.copy(width = it)
            })
            addView(error)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Selection width").setView(column)
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                if (ink.rewidthSelection(target, width)) dialog.dismiss()
                else { error.text = "Selection changed or is busy. Reopen Selection width."; error.visibility = android.view.View.VISIBLE }
            }.onFailure { error.text = it.message ?: "Cannot change the selection width"; error.visibility = android.view.View.VISIBLE }
        }
        return dialog
    }
    private fun titleDialog(label: String, initial: String, apply: (String) -> Unit) {
        val input = EditText(this).apply { setSingleLine(); setText(initial) }
        AlertDialog.Builder(this).setTitle(label).setView(input).setPositiveButton("Save") { _, _ ->
            runCatching { apply(input.text.toString()) }.onFailure { message(it.message ?: "Invalid title") }
        }.setNegativeButton("Cancel", null).show()
    }
    fun newNotebookDialog() = titleDialog("New notebook", "") { repository.createNotebook(it) }
    fun renameDialog() {
        if (repository.readOnly) return
        val session = repository.session ?: return
        titleDialog("Rename notebook", session.notebook.title) { title -> repository.edit { it.rename(title) } }
    }
    /**
     * System bars; the decor view exists only after setContentView. The Library is still ink with a light-icon status bar;
     * P175 (G1): the Glass editor's status bar is desk, with dark icons by day.
     */
    internal fun systemBars() {
        // P720: the crop screen keeps the bars in its own dark colour while it shows.
        if (pictureCrop != null) { Kit.fullScreen(window); darkBars(PictureCropScreen.BACK); return }
        // P722: the scan screen too, in the colour at the edge of its own background.
        if (scanScreen != null) { Kit.fullScreen(window); darkBars(SCAN_EDGE); return }
        val desk = if (dark) Tokens.DESK_DARK else Tokens.DESK
        val lightDesk = !dark
        // P567: Settings over the Library looks as it does over the editor.
        val library = libraryScreen != null && !frame.settingsOpen
        // P568: at night the Library's bars are its own desk.
        window.statusBarColor = if (library) (if (dark) Kit.desk(true) else Tokens.INK) else desk
        window.navigationBarColor = if (library && dark) Kit.desk(true) else desk
        Kit.fullScreen(window)
        runCatching {
            val c = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val n = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance((if (lightDesk && !library) c else 0) or (if (lightDesk) n else 0), c or n)
        }
    }
    private var openingLibrary = false
    /** B50: where the Library was when it last closed (kept across rotation, not across launches; a launch opens on All). */
    private var libraryPlace: LibraryScreen.Place? = null
    /** P457 (B51): edit revision when Library was asked for while the open book was still saving; shown when that save ends. */
    private var libraryAfterSave: Long? = null
    /**
     * P574: once per updated version, the What's new note; otherwise, if the person turned it on, the daily update
     * check. Never both at once, and never over a notebook opened meanwhile.
     */
    private fun libraryNews(screen: LibraryScreen) {
        val version = UpdateCheck.currentVersion(this)
        val updated = runCatching { packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime != it.lastUpdateTime } }.getOrDefault(true)
        val lines = WhatsNew.due(getSharedPreferences(app.preferencesName, 0), version, updated)
        if (lines != null) { frame.root.post { if (libraryScreen === screen && !isFinishing) WhatsNew.show(this, version, lines) }; return }
        AutoUpdateCheck.run(this, stillShowing = { libraryScreen === screen && !isFinishing && !isDestroyed })
    }

    fun showLibrary(allowLoading: Boolean = false, syncOnOpen:Boolean = true) {
        cancelPdfTextSelection()
        libraryAfterSave = null
        if (!allowLoading && libraryScreen == null && !openingLibrary && !ink.isEditing && !repository.canChangeNotebook && repository.onlySaving) {
            repository.flushSave(); libraryAfterSave = repository.editRevision; return
        }
        if (openingLibrary || libraryScreen != null || (!allowLoading && !repository.canChangeNotebook) || ink.isEditing) return
        openingLibrary = true
        try {
        frame.closeSettings(); frame.closeOrganizer(); closeScan(); closePictureCrop()
        // P530: the Library belongs to the first document; opening a book replaces only that pane.
        focusPane(primaryInk)
        val screen = LibraryScreen(this, app.repository, thumbnails, onOpened = { id ->
            // P567: found by what is written inside it: go on to show where.
            val found = libraryScreen?.foundInside(id)?.takeIf { app.repository.session?.notebook?.id == id }
            closeLibrary()
            if (found != null) frame.root.post { notebookSearchDialog(found) }
            // P686, P688: photos shared into this notebook ask how they go in once it is on screen.
            sharedPhotos?.let { (wanted, files) ->
                sharedPhotos = null
                if (wanted == id && app.repository.session?.notebook?.id == id) frame.root.post { askHowToAddShared(files) } else files.forEach { it.delete() }
            }
            // P699: a notebook changed on two devices asks what to keep when it is opened.
            if (id in LibrarySync.bothChanged(app)) frame.root.post { askBothChanged(id) }
        },
            onImport = { launchImport() }, onBackup = { backupDialog() },
            onExportSelected = { chooseBatchPdfFolder(it) }, onExportAll = { chooseAllExportFolder() },
            onSync = { syncDialog() }, syncStatus = { LibrarySync.status(this) }, syncProgress = { if (LibrarySync.running) LibrarySync.progressText else null },
            onSourceRecovery = { recoverNotebookSource(it) }, storageActions = libraryStorageActions ?: NotebookStorageActions(app.repository))
        libraryScreen = screen
        frame.root.addView(screen, android.widget.FrameLayout.LayoutParams(-1, -1))
        SafeArea.keep(screen)
        systemBars()
        refresh()
        // B50: back in the folder, tag and scroll position the notebook was opened from.
        if (repository.loaded) libraryPlace?.let { screen.returnTo(it) }
        // P139 (F167): opening the library syncs it, unless sync is paused; only problems are shown.
        if (syncOnOpen && repository.canChangeNotebook && LibrarySync.hasSource(this) && !LibrarySync.paused(this)) syncLibrary(announce = false)
        if (app.showsNews) libraryNews(screen)
        } finally { openingLibrary = false }
    }
    fun closeLibrary() {
        // A released current notebook has no editor session. Stay in Library until one opens.
        if (repository.session == null) return
        libraryScreen?.let { libraryPlace = it.place(); frame.root.removeView(it) }
        libraryScreen = null
        systemBars()
        ink.retryPdfBackground()
        refresh()
    }
    /** P105 (F138): the launcher's Quick note shortcut, opened as soon as the library has loaded. */
    private var quickNotePending = false
    private var shareRequestId:String?=null
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == QUICK_NOTE) quickNotePending = true
        else {shareRequestId=java.util.UUID.randomUUID().toString();app.sharedNotebookImport.offer(intent,shareRequestId!!)}
        refresh()
    }
    private fun openPendingQuickNote() {
        if (appLockGate.locked || !quickNotePending || ink.isEditing || !repository.canChangeNotebook) return
        quickNotePending = false
        // Posted, so the notebook change never runs inside the refresh that noticed it.
        frame.root.post {
            if (appLockGate.locked || ink.isEditing || !repository.canChangeNotebook) { quickNotePending = true; return@post }
            frame.closeSettings(); frame.closeOrganizer(); closeScan(); closePictureCrop()
            repository.openQuickNote { if (libraryScreen != null) closeLibrary() }
        }
    }
    fun refresh() {
        if (!::frame.isInitialized || appLockGate.locked) return
        // Newer writing cancels the earlier Library request; otherwise it completes once the save has finished.
        libraryAfterSave?.let { asked -> if (repository.editRevision != asked) libraryAfterSave = null else if (repository.canChangeNotebook) showLibrary() }
        applyNotebookSourceSelection()
        app.sharedNotebookImport.drain(this)
        openPendingQuickNote()
        // A released notebook (including a refused Quick note shortcut) has no editor to return to.
        if (repository.loaded && repository.session == null && libraryScreen == null && !openingLibrary) {
            showLibrary(allowLoading = true)
            return
        }
        syncCompanionPane()
        checkHighlightsInbox()
        val enabled = repository.session != null && !repository.fileBusy && !repository.busy && libraryScreen == null && !frame.settingsOpen && !frame.organizerOpen && scanScreen == null && pictureCrop == null
        val active = ink
        primaryInk.inputEnabled = enabled && active === primaryInk
        companionInk?.inputEnabled = enabled && active === companionInk
        closeSettings.isEnabled = true
        frame.refresh()
        primaryInk.refresh()
        companionInk?.refresh()
    }
    /** The last [message] shown, for tests; a toast cannot be read back. */
    internal var lastMessage: String? = null
    fun message(text: String) { lastMessage = text; Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    /** P088: the backup sheet; the first time it goes straight to the folder picker. */
    /**
     * Library backup. P732: board 25's Look A sheet ([StoragePanels.backup]): the card, Back up now, the Folder and Restore
     * tiles, the owner's readable recovery copies, Stop backing up. Every button of the old dialog is kept.
     */
    fun backupDialog(context: android.content.Context = this): Kit.Sheet? {
        if (isDestroyed) return null
        return StoragePanels.backup(this, context, StoragePanels.BackupActions(
            choose = { chooseBackupFolder() },
            backUp = { backUpLibrary(announce = true) },
            restore = { restoreDialog() },
            stop = { LibraryBackup.stop(this); message("Backups stopped. Files already in the folder stay there.") },
            openRecovery = { app.safetyCopies.openFiles(this) },
            updateRecovery = { app.safetyCopies.requestAll(repository.entries); message("Recovery refresh requested. Completed copies remain in Downloads / ${getString(R.string.safety_folder)}.") },
        ))
    }
    /** P124 (F220): the restore confirmation last shown, for checking it. */
    var restorePrompt: AlertDialog? = null; private set
    /** P124 (F220): confirms, then adds back the backed-up notebooks the library and its trash lack. */
    fun restoreDialog(): AlertDialog? {
        if (isDestroyed) return null
        val tree = LibraryBackup.folderUri(this) ?: return null
        return AlertDialog.Builder(this).setTitle("Restore from backup?")
            .setMessage("Notebooks in “${LibraryBackup.folderName(this@MainActivity)}” in ${LibraryBackup.folderLabel(this)} that are not in your library or its trash are added back. " +
                "Nothing in your library is replaced, and the backup folder is not changed.")
            .setPositiveButton("Restore") { _, _ ->
                if (!app.repository.restoreFromBackup({ FolderProcess.backupFolder(app, tree) }) { message(repository.libraryMessage ?: "Restore finished.") })
                    message("Restore is not available while the notebook is busy.")
            }
            .setNegativeButton("Cancel", null)
            .show().also { restorePrompt = it }
    }
    private fun chooseBackupFolder() {
        runCatching { backupFolder.launch(LibraryBackup.folderUri(this)) }.onFailure { message("Cannot choose a folder: ${it.message}") }
    }
    /** P203: library transfer controls use the shared Glass kit. No provider account is needed. P729: board 21's Look A ([SyncPanels]). */
    fun syncDialog(context: android.content.Context = this): Kit.Sheet? {
        if (isDestroyed) return null
        return SyncPanels.show(this, context, SyncPanels.Actions(
            chooseFolder = { chooseSyncFolder() },
            webDav = { webDavSyncDialog(context) },
            webDavRecovery = { webDavRecoveryDialog(context) },
            addEncrypted = { runCatching { encryptedSyncFolder.launch(null) }.onFailure { message("Cannot choose a folder") } },
            syncEncrypted = { EncryptedSync.sync(app) { it?.let(::message) } },
            disconnectEncrypted = { EncryptedSync.disconnect(app); message("Encrypted copy disconnected; its copies and primary sync are kept.") },
            instantSync = { LiveSyncPanels.open(this, context) },
            syncNow = { syncLibrary(announce = true) },
            stop = { LibrarySync.stop(app); message("Sync stopped. Notebook copies, saved account and transfer recovery data were kept.") },
            smallerUploads = { chooseChangedSyncFolder() },
            memorySummary = { showMemorySummary() },
            saveMemoryReport = { saveMemoryReport.launch(MemoryReport.fileName()) },
            deleteMemoryReport = { MemoryReport.delete(this); message("Memory report deleted") },
            lastExits = { showExits() },
        ))
    }
    fun webDavSyncDialog(context: android.content.Context = this): Kit.Sheet = WebDavPanels.setup(context, app) {
        message("WebDAV connected."); syncLibrary(announce = true)
    }
    fun webDavRecoveryDialog(context: android.content.Context = this): Kit.Sheet = WebDavPanels.recovery(context, app) {
        syncLibrary(announce = true)
    }
    private fun chooseSyncFolder() {
        runCatching { syncFolder.launch(LibrarySync.folderUri(this)) }.onFailure { message("Cannot choose a folder: ${it.message}") }
    }
    /** Syncs after any queued save; [announce] shows the outcome, otherwise only problems are shown. */
    fun syncLibrary(announce: Boolean, after: () -> Unit = {}) {
        // P721: the Library's sync chip turns to the running sync at once, and back as soon as it ends.
        frame.root.post { libraryScreen?.syncChanged() }
        LibrarySync.sync(app, app.repository) { text ->
            libraryScreen?.syncChanged()
            if (text != null && (announce || text.startsWith("Sync failed") || text.contains("could not sync") || text.contains("both versions kept") || text.contains("scanned page") || text.contains(LibraryLayoutSync.UPDATED))) message(text)
            // P699: notebooks changed on both devices wait for the user's choice, one at a time.
            if (text != null && LibrarySync.bothChanged(app).isNotEmpty()) askBothChanged()
            if (text != null) takeNewerOpen()
            if (text != null) combineHeld()
            if (text != null && !text.startsWith("Sync failed")) app.liveSync.folderSynced()
            after()
            EncryptedSync.sync(app) { extra -> if(extra!=null && (announce || extra.startsWith("Encrypted sync failed"))) message(extra) }
        }
    }
    /** P713: notebooks being combined by [combineHeld] now. */
    private val combining = mutableSetOf<String>()
    /**
     * P713: a notebook changed on both devices with nothing clashing, held because it was open here (even behind the
     * Library), is combined as soon as the app can change it, then sent. A clash on the way asks the user instead.
     * One at a time; the sync that sends it looks for the next one.
     */
    private fun combineHeld() {
        if (isDestroyed) return
        val pid = android.os.Process.myPid().toString()
        stoppedCombines(pid)
        val id = LibrarySync.combineReady(app).firstOrNull { it !in combining && runCatching { combineMarker(it).readText() }.getOrNull() != pid } ?: return
        val title = repository.entries.firstOrNull { it.id == id }?.title ?: "A notebook"
        combining += id
        runCatching { combineMarker(id).writeText(pid) }
        val started = repository.resolveBothChanged(id, { LibrarySync.resolveBothChanged(app, id, dev.ownednotes.core.FolderSync.Choice.COMBINE, onlyClean = true) }) { result ->
            combining -= id
            combineMarker(id).delete()
            if (isDestroyed) return@resolveBothChanged
            result.onSuccess {
                message("Combined both devices' changes to “$title”. Sending it to your other device.")
                syncLibrary(announce = false) { app.liveSync.folderChanged(listOf(id)) }
            }.onFailure {
                // P714: not tried by itself again (it may have run out of memory): the user is asked.
                app.io.execute { runCatching { LibrarySync.cancelCombine(app, id) }; app.main.post { if (!isDestroyed) askBothChanged(id) } }
            }
        }
        // Busy now (a save or another change): the next sync tries again.
        if (!started) { combining -= id; combineMarker(id).delete() }
    }
    /** P714: why the app stopped last time, shown once, with a copy button. */
    private fun showCrashNote() {
        if (isDestroyed) return
        val text = CrashNote.takeShown(this) ?: return
        AlertDialog.Builder(this).setTitle("The app stopped last time")
            .setMessage("This note says why. Copy it and pass it on if it happens again.\n\n$text")
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Crash note", text))
                message("Copied")
            }
            .setNegativeButton("Close", null)
            .apply { if (personalTools && MemoryReport.available(this@MainActivity)) setNeutralButton("Memory summary") { _, _ -> showMemorySummary() } }
            .show()
    }
    /** P726: Android's own record of why the app ended lately (ExitHistory), read off the main thread, with Copy. */
    internal var exitsSheet: Kit.Sheet? = null; private set
    internal fun showExits() {
        if (isDestroyed) return
        // Its own thread: the app's file thread may be busy with a save or a sync when this is needed.
        Thread({
            val text = ExitHistory.describe(this)
            app.main.post {
                if (isDestroyed) return@post
                exitsSheet = Kit.Sheet(this, "Last exits", "Copy", cancel = "Close") {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Last exits", text))
                    message("Copied")
                    false
                }.apply {
                    add(Kit.text(this@MainActivity, "Why Android ended the app lately, newest first. Copy it and pass it on.", secondary = true))
                    add(Kit.text(this@MainActivity, text, Kit.Type.CAPTION).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f })
                    dialog.setOnDismissListener { exitsSheet = null }
                    show()
                }
            }
        }, "exit-history").start()
    }
    /** P717/P718: the memory summary opens on a screen of its own (MemoryReportActivity), which reads the report in a
     *  process of its own: this process, when its memory is full, only asks for the screen. */
    private fun showMemorySummary() {
        if (!MemoryReport.open(this)) message("The memory summary could not be opened")
    }
    /** P714: marks a combine running in this app process, so one cut off by the app stopping is not tried again by itself. */
    private fun combineMarker(id: String) = java.io.File(app.filesDir, "combining-$id")
    private fun stoppedCombines(pid: String) {
        app.filesDir.listFiles { f -> f.name.startsWith("combining-") }.orEmpty().forEach { f ->
            if (runCatching { f.readText() }.getOrNull() != pid) {
                runCatching { LibrarySync.cancelCombine(app, f.name.removePrefix("combining-")) }
                f.delete()
            }
        }
    }
    internal var bothChangedChoice: BothChangedChoice? = null; private set
    private var bothChangedAsking = false
    /** P699: notebooks the user put off with "Not now"; asked again at the next sync or when opened. */
    private val bothChangedLater = mutableSetOf<String>()
    /**
     * P699: asks what to keep for a notebook changed here and on another device ([only], or the first one waiting that was
     * not put off). The choice runs with edits waiting, then sync sends the result.
     */
    fun askBothChanged(only: String? = null) {
        if (bothChangedAsking || bothChangedChoice != null || isDestroyed) return
        val waiting = LibrarySync.bothChanged(app)
        val id = only?.takeIf { it in waiting } ?: waiting.firstOrNull { it !in bothChangedLater } ?: return
        bothChangedAsking = true
        app.io.execute {
            val details = runCatching { LibrarySync.bothChangedDetails(app, id) }
            app.main.post {
                bothChangedAsking = false
                if (isDestroyed) return@post
                val found = details.getOrElse { message("“${repository.entries.firstOrNull { e -> e.id == id }?.title ?: "A notebook"}” changed on both devices, but its versions could not be read. Sync again later."); return@post }
                bothChangedChoice = BothChangedChoice(this, found, choose = { choice -> keepBothChangedChoice(id, found.title, choice) },
                    closedThen = { chosen -> bothChangedChoice = null; if (!chosen) bothChangedLater += id }).show()
            }
        }
    }
    private fun keepBothChangedChoice(id: String, title: String, choice: dev.ownednotes.core.FolderSync.Choice, retry: Boolean = true) {
        bothChangedLater -= id
        val started = repository.resolveBothChanged(id, { LibrarySync.resolveBothChanged(app, id, choice) }) { result ->
            result.onSuccess {
                message(when (choice) {
                    dev.ownednotes.core.FolderSync.Choice.COMBINE -> "Combined both devices' changes to “$title”. Sending it to your other device."
                    dev.ownednotes.core.FolderSync.Choice.KEEP_HERE -> "Kept this device's “$title”. The other version is in Earlier versions."
                    dev.ownednotes.core.FolderSync.Choice.KEEP_THERE -> "Took the other device's “$title”. This device's version is in Earlier versions."
                    dev.ownednotes.core.FolderSync.Choice.KEEP_BOTH -> "Kept both: the other device's version is now “$title (other device)”."
                    dev.ownednotes.core.FolderSync.Choice.TAKE_NEWER -> "“$title” updated from your other device."
                })
                syncLibrary(announce = false)
            }.onFailure { message("Nothing was changed: ${it.message ?: "the choice could not be kept"}. Sync again, then choose.") }
        }
        if (started) return
        // The open notebook is still saving: save it, then try once more.
        if (retry && repository.session?.notebook?.id == id) { repository.flushSave(); frame.root.postDelayed({ keepBothChangedChoice(id, title, choice, retry = false) }, 1500) }
        else message("Nothing was changed yet. Close the second pane or wait for the save to finish, then sync again to choose.")
    }
    /** Backs up after any queued save; [announce] shows the outcome, otherwise only failures are shown. */
    fun backUpLibrary(announce: Boolean) {
        val titles = repository.entries.associate { it.id to it.title }
        LibraryBackup.backUp(app, titles) { text ->
            if (text != null && (announce || text.startsWith("Backup failed") || text.startsWith("Backup could not"))) message(text)
        }
    }
    fun launchAttachFile(video: Boolean = false): Boolean {
        if (ink.isEditing || app.attachInsertTarget != null || app.photoInsertTarget != null || app.imageInsertTarget != null || app.pdfInsertTarget != null) return false
        val target = repository.captureImageInsertTarget() ?: return false
        app.attachInsertTarget = target
        return runCatching { chooseSource(attachFile, if(video) "Attach a video from" else "Attach a file from", if(video) "video" else "file", if(video) arrayOf("video/*") else arrayOf("*/*")) { app.attachInsertTarget = null }; true }.getOrElse {
            app.attachInsertTarget = null; message("Cannot choose a file: ${it.message}"); false
        }
    }
    /** Open, share or (undoably) remove a tapped attached-file card. */
    fun attachmentMenu(card: dev.ownednotes.core.FileAttachment): AlertDialog? {
        if (isDestroyed || libraryScreen != null || frame.settingsOpen) return null
        val page = repository.currentPage ?: return null
        val size = repository.session?.notebook?.attachmentAssets?.firstOrNull { it.id == card.assetId }?.let { dev.ownednotes.core.FileAttachment.sizeText(it.size) }.orEmpty()
        val owner = repository.session?.notebook ?: return null
        val current = page.attachments.firstOrNull { it.id == card.id } ?: return null
        val canEdit = !repository.readOnly && repository.canChangeNotebook
        val openLabel = if(current.mimeType.startsWith("video/")) "Play video" else "Open"
        val items = (listOf(openLabel, "Share") + if (!canEdit) emptyList() else if (current.locked) listOf("Unlock") else listOf("Remove from page", "Lock")).toTypedArray()
        return AlertDialog.Builder(this).setTitle(card.name).setItems(items) { _, which ->
            if (repository.session?.notebook !== owner || repository.selectedPageId != page.id) return@setItems
            when (items[which]) {
                openLabel -> openAttachment(current, share = false)
                "Share" -> openAttachment(current, share = true)
                "Remove from page" -> if (repository.canChangeNotebook && repository.removeAttachment(page.id, current.id)) message("Removed ${card.name}. Undo restores it.")
                "Lock", "Unlock" -> if (repository.editLocks(owner, page.id) {
                    it.setObjectsLocked(page.id, locked = !current.locked, attachments = setOf(current.id))
                }) message(if (current.locked) "Unlocked. Hold the card to move it." else "Locked. Touch and hold it to unlock.")
            }
        }.setNegativeButton("Close", null).create().also { dialog ->
            dialog.setOnShowListener { dialog.window?.decorView?.contentDescription = "Attached file: ${card.name}, $size" }
            dialog.show()
        }
    }
    /** Copies an attached file to cache and hands it to another app read-only; the notebook copy never leaves the package. */
    fun openAttachment(card: dev.ownednotes.core.FileAttachment, share: Boolean) {
        val asset = repository.session?.notebook?.attachmentAssets?.firstOrNull { it.id == card.assetId } ?: return message("The file is missing")
        app.io.execute {
            val file = runCatching {
                val dir = java.io.File(cacheDir, "attachments/${card.id}").apply { deleteRecursively(); mkdirs() }
                java.io.File(dir, card.name).also { out -> out.outputStream().use { asset.writeTo(it) } }
            }
            app.main.post {
                if (isDestroyed) return@post
                file.fold(onSuccess = { f ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.attachments", f)
                        val intent = if (share) android.content.Intent(android.content.Intent.ACTION_SEND).setType(card.mimeType).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            else android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, card.mimeType)
                        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(android.content.Intent.createChooser(intent, if (share) "Share ${card.name}" else "Open ${card.name}"))
                    }.onFailure { message(if (it is android.content.ActivityNotFoundException) "No app on this device can open this file" else "${it.message}") }
                }, onFailure = { message("Could not prepare the file: ${it.message}") })
            }
        }
    }
    override fun onResume() {super.onResume();appLockGate.resumed();dropTick.run();app.main.postDelayed(autoSync, 3_000)}
    /**
     * P699 (SYNC-MERGE-PLAN step 4): while a notebook is open and sync is set, sync a few seconds after each save and
     * about every 30 s, so a change made here reaches the other device soon and a newer version from there is taken in.
     */
    private var autoSyncing = false
    private var lastAutoSync = 0L
    private var savesSeen = -1L
    private val autoSync = Runnable { runAutoSync() }
    private fun runAutoSync() {
        app.main.removeCallbacks(autoSync)
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (repository.session == null || !LibrarySync.hasSource(this) || LibrarySync.paused(this)) return
        if (autoSyncing || !repository.canChangeNotebook) { app.main.postDelayed(autoSync, 5_000); return }
        autoSyncing = true; lastAutoSync = android.os.SystemClock.elapsedRealtime()
        syncLibrary(announce = false) { autoSyncing = false; app.main.removeCallbacks(autoSync); app.main.postDelayed(autoSync, if (app.liveSync.live) LIVE_AUTO_SYNC_MS else AUTO_SYNC_MS) }
    }
    private val savedWatch: () -> Unit = {
        val saves = repository.saves
        if (saves != savesSeen) {
            val first = savesSeen < 0; savesSeen = saves
            if (!first && !autoSyncing) {
                // A few seconds after a save, but not more often than every 15 s while writing goes on.
                val wait = maxOf(5_000L, lastAutoSync + (if (app.liveSync.live) LIVE_AUTO_SYNC_MS else 15_000L) - android.os.SystemClock.elapsedRealtime())
                app.main.removeCallbacks(autoSync); app.main.postDelayed(autoSync, wait)
            }
        }
    }
    /** P699: the open notebook is unchanged here and the other device has a newer version: take it in (once saved). */
    internal fun takeNewerOpen() {
        val note = repository.session?.notebook ?: return
        if (note.id !in LibrarySync.newer(app)) return
        repository.resolveBothChanged(note.id, { LibrarySync.resolveBothChanged(app, note.id, dev.ownednotes.core.FolderSync.Choice.TAKE_NEWER) }) { result ->
            if (result.isSuccess) message("“${note.title}” updated from your other device.")
        }
    }
    /** P695 (C2): page drops from another device are looked for while the app is in front, about once a minute, and when a notebook opens. */
    private var dropNotebook: String? = null
    private val dropTick = object : Runnable {
        override fun run() {
            app.main.removeCallbacks(this)
            if (isFinishing || isDestroyed) return
            checkDrops()
            app.main.postDelayed(this, 60_000)
        }
    }
    private val dropOpened: () -> Unit = {
        val id = app.repository.session?.notebook?.id
        if (id != null && id != dropNotebook && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) app.main.post { checkDrops() }
        dropNotebook = id
    }
    internal fun checkDrops(done: () -> Unit = {}) {
        if (!PageDrops.eligible(app) || !app.documentsReady) return done()
        PageDrops.check(app, app.repository, notice = { message(it) }) { if (::frame.isInitialized && !isDestroyed) frame.showDrops(force = true); done() }
    }
    override fun onPause() { app.main.removeCallbacks(dropTick); app.main.removeCallbacks(autoSync); if (::frame.isInitialized) frame.inPlaceText.done(); appLockGate.paused();frame.zoomWriting?.canvas?.prepareForPause();primaryInk.prepareForPause();companionInk?.prepareForPause(); app.openDocuments.forEach { it.flushSave() }; super.onPause() }
    // P103: Paste follows what other apps copied while Scribella was in the background.
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) { Kit.fullScreen(window); refresh() } }
    // P088: leaving the app backs the library up when a backup folder is set (queued behind the save onPause started).
    /** Instant sync connects while Scribella is on screen; it asks for folder sync when new files or missed changes need it. */
    override fun onStart() { super.onStart(); app.liveSync.folderSync = { app.main.removeCallbacks(liveFolderSync); app.main.postDelayed(liveFolderSync, 3_000) }; app.liveSync.shown(true); LiveSyncBackground.update(app) }
    private val liveFolderSync = Runnable { runLiveFolderSync() }
    private fun runLiveFolderSync() {
        if (isFinishing || isDestroyed || !LibrarySync.hasSource(this) || LibrarySync.paused(this)) return
        if (autoSyncing || !repository.canChangeNotebook) { app.main.postDelayed(liveFolderSync, 5_000); return }
        autoSyncing = true; lastAutoSync = android.os.SystemClock.elapsedRealtime()
        syncLibrary(announce = false) { autoSyncing = false }
    }
    override fun onStop() {if(!isChangingConfigurations){app.liveSync.shown(false);if(app.audio.active)app.audio.stop();app.audioPlayback.stop()};appLockGate.stopped(); if (!isChangingConfigurations && LibraryBackup.folderUri(this) != null) backUpLibrary(announce = false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("libraryVisible", libraryScreen != null)
        (libraryScreen?.place() ?: libraryPlace)?.let { place ->
            outState.putString("libraryFolder", place.folder); outState.putString("libraryTag", place.tag); outState.putBoolean("libraryArchived", place.archived)
            outState.putInt("libraryPosition", place.position); outState.putInt("libraryOffset", place.offset)
        }
        shareRequestId?.let {outState.putString("shareRequestId",it)}
        batchPdfIds?.let { outState.putStringArrayList("batchPdfIds", java.util.ArrayList(it)); outState.putBoolean("batchExportNative", batchExportNative) }
        (notebookSourceTarget ?: notebookSourceSelection?.target)?.let { target ->
            outState.putString("notebookSourceId", target.id); outState.putString("notebookSourceUri", target.source)
            notebookSourceSelection?.let { outState.putString("notebookSourceSelected", it.selected) }
        }
        saveTextEditorDraft(); savePhotoCropDraft(); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {if (::frame.isInitialized) runCatching { frame.openPopover?.dismiss() } /* P642: a tool popover open while the activity ends would leak its window */;encryptedBackupPrompt?.sheet?.dismiss();appLockGate.dispose();appLockSetup?.sheet?.dismiss(); encryptedSyncPrompt?.sheet?.dismiss(); pdfPasswordPrompt?.sheet?.dismiss(); pdfLinkSheet?.dialog?.dismiss(); closeScan(refresh = false);closePictureCrop(refresh = false);closeTextEditorForDestroy(); closePhotoCropForDestroy(); app.openDocuments.forEach { it.unlisten(observer) }; app.repository.unlisten(dropOpened); app.repository.unlisten(savedWatch); app.main.removeCallbacks(dropTick); app.main.removeCallbacks(autoSync); app.main.removeCallbacks(liveFolderSync); app.liveSync.folderSync = null; if (LibraryLayoutSync.onChanged === layoutChanged) LibraryLayoutSync.onChanged = null; primaryInk.disposePageCurl();companionInk?.disposePageCurl(); thumbnails.close(); super.onDestroy() }
}

/** A file name from a notebook title, without characters file pickers reject. */
internal fun exportFileName(title: String) = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(60).ifEmpty { "notebook" }

/** P107 (F189): writes [notebook]'s text to [uri]; reads PDF, image and photo pages first (on NotesApplication.io). */
internal fun writeTextExport(app: NotesApplication, uri: android.net.Uri, notebook: Notebook, markdown: Boolean) {
    val printed: (Page) -> List<String>? = { page ->
        if (PageText.readable(page)) runCatching { PageText.readPage(app, notebook, page).blocks }.getOrNull() else null
    }
    val text = if (markdown) TextExport.markdown(notebook, printed) else TextExport.text(notebook, printed)
    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Cannot open the destination")
}
