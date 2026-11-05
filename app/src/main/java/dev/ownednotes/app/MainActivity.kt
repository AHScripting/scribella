package dev.ownednotes.app

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
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
                else if (frame.settingsOpen) frame.closeSettings()
                // P201 step 6: the page organizer stops choosing, then closes, like Settings.
                else if (frame.organizerOpen) frame.organizer!!.back()
                else if (pdfTextReading || ink.pdfTextSelectionActive) cancelPdfTextSelection()
                // P178 (G3): Back leaves page choosing before it leaves the notebook.
                else if (frame.choosingPages) frame.stopChoosingPages() else showLibrary()
        }
    }
    /** Keyboard cover actions share the editor's commands; forms and other panels keep native keys. */
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean {
        if(appLockGate.locked)return true
        if(!::primaryInk.isInitialized || !::frame.isInitialized || currentFocus is EditText || textEditor!=null || photoCropEditor!=null ||
            libraryScreen!=null || frame.settingsOpen || frame.organizerOpen || frame.choosingPages || scanScreen!=null || !ink.inputEnabled)
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
            repository.setFileBusy(true)
            app.io.execute {
                val result = runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { NotebookPackage.write(snapshot, it) } ?: error("Cannot open export destination") }
                app.main.post { repository.setFileBusy(false); message(result.fold({ "Notebook exported" }, { "Export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("Export was interrupted; please try again")
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
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val snapshot = app.pdfExportSnapshot
        val includeReviewed = app.pdfExportReviewedHandwriting
        val editableInk = app.pdfExportEditableInk
        val smallerFile = app.pdfExportSmallerFile
        app.pdfExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.setFileBusy(true)
            app.io.execute {
                val result = runCatching {
                    // P094 (F127): PDF and image pages keep their printed text as an invisible, searchable layer.
                    val staged = PdfExport.create(snapshot, app.cacheDir, includeReviewedHandwriting = includeReviewed, editableInk = editableInk, smallerFile=smallerFile) { page -> runCatching { PageText.read(app, snapshot, page) }.getOrNull() }
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                            ?: error("Cannot open PDF destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.setFileBusy(false); message(result.fold({ "PDF exported" }, { "PDF export failed: ${it.message}" })) }
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
            repository.setFileBusy(true)
            app.io.execute {
                val result = runCatching {
                    val staged = PageImageExport.create(snapshot.notebook, snapshot.pageId, app.cacheDir, snapshot.smallerFile)
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                            ?: error("Cannot open PNG destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.setFileBusy(false); message(result.fold({ "PNG exported" }, { "PNG export failed: ${it.message}" })) }
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
                app.io.execute {
                    val parsed = runCatching {
                        app.contentResolver.openInputStream(uri)?.use { ImageSupport.import(it) } ?: error("Cannot open image")
                    }
                    app.main.post {
                        repository.setFileBusy(false)
                        parsed.fold(onSuccess = { source ->
                            runCatching { repository.insertImagePages(target, source) }.onSuccess { ids ->
                                if (ids.isEmpty()) message("The notebook or page changed. Insert image page again.")
                                else {
                                    if (!isDestroyed && ::frame.isInitialized) frame.revealPage(ids.first())
                                    message("Inserted image page")
                                }
                            }.onFailure { message("Image insertion failed: ${it.message}") }
                        }, onFailure = { message("Image insertion failed: ${it.message}") })
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
                app.io.execute {
                    val parsed = runCatching {
                        app.contentResolver.openInputStream(uri)?.use { ImageSupport.importPhoto(it) } ?: error("Cannot open image")
                    }
                    app.main.post {
                        repository.setFileBusy(false)
                        parsed.fold(onSuccess = { photo ->
                            runCatching { repository.insertPhoto(target, photo) }.onSuccess { id ->
                                if (id == null) message("The notebook or page changed. Choose Insert photo again.")
                                else {
                                    if (!isDestroyed && ::primaryInk.isInitialized) ink.selectPhoto(id)
                                    message("Inserted photo")
                                }
                            }.onFailure { message("Photo insertion failed: ${it.message}") }
                        }, onFailure = { message("Photo insertion failed: ${it.message}") })
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
                app.io.execute {
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
            repository.setFileBusy(true)
            app.io.execute {
                val result = runCatching {
                    val staged = StudyInkExport.create(snapshot, app.cacheDir)
                    try {
                        app.contentResolver.openOutputStream(uri, "wt")?.use { out -> staged.inputStream().use { it.copyTo(out) } }
                            ?: error("Cannot open export destination")
                    } finally { staged.delete() }
                }
                app.main.post { repository.setFileBusy(false); message(result.fold(
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
        app.io.execute {
            val copied = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("Cannot open image")
            }
            app.main.post {
                copied.onSuccess { openScan(file) }.onFailure { file.delete(); message("Cannot open that photo: ${it.message}"); scanResumed() }
            }
        }
    }
    // P247: Android's camera question the first time Scan is used; appended last so earlier launcher keys stay stable.
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

    private fun encryptedSyncControls(sheet: Kit.Sheet,context: android.content.Context) {
        sheet.add(Kit.section(context,"Additional encrypted destination"))
        if(EncryptedSync.folderUri(app)==null) {
            sheet.add(Kit.button(context,"Add encrypted destination…",Kit.Button.PLAIN) {
                sheet.dismiss();runCatching {encryptedSyncFolder.launch(null)}.onFailure {message("Cannot choose a folder")}
            })
        } else {
            sheet.add(Kit.text(context,EncryptedSync.status(app),secondary=true))
            sheet.add(Kit.button(context,"Sync encrypted destination",Kit.Button.TINTED) {
                sheet.dismiss();EncryptedSync.sync(app) {it?.let(::message)}
            })
            sheet.add(Kit.button(context,"Disconnect encrypted destination",Kit.Button.PLAIN) {
                EncryptedSync.disconnect(app);sheet.dismiss();message("Encrypted destination disconnected; its copies and primary sync are kept.")
            })
        }
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
        repository.setFileBusy(true)
        app.io.execute {
            val result = runCatching { StudyOutbox.publish(snapshot, app.cacheDir, java.io.File(external, "study-outbox")) }
            app.main.post {
                repository.setFileBusy(false)
                message(result.fold({ "Handwriting-only study copy ready for PC over USB. Nothing sent to an AI." },
                    { "Study copy failed: ${it.message}" }))
            }
        }
    }

    private fun finishTextExport(uri: android.net.Uri?, markdown: Boolean) {
        val snapshot = app.textExportSnapshot
        app.textExportSnapshot = null
        if (uri != null && snapshot != null) {
            repository.setFileBusy(true)
            app.io.execute {
                val result = runCatching { writeTextExport(app, uri, snapshot, markdown) }
                app.main.post { repository.setFileBusy(false); message(result.fold({ "Text exported" }, { "Text export failed: ${it.message}" })) }
            }
        } else if (uri != null) message("Text export was interrupted; please try again")
    }
    // Appended after all earlier result registrations; their persisted keys remain unchanged.
    private val exportSvg = registerForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        val snapshot=app.svgExportSnapshot;app.svgExportSnapshot=null
        if(uri!=null && snapshot!=null) {
            repository.setFileBusy(true)
            app.io.execute {
                val result=runCatching {
                    val staged=SvgExport.create(snapshot.notebook,snapshot.pageId,app.cacheDir)
                    try {app.contentResolver.openOutputStream(uri,"wt")?.use {out -> staged.inputStream().use {it.copyTo(out)}} ?: error("Cannot open SVG destination")}finally {staged.delete()}
                }
                app.main.post {repository.setFileBusy(false);message(result.fold({"SVG exported"},{"SVG export failed: ${it.message}"}))}
            }
        }else if(uri!=null)message("SVG export was interrupted; please try again")
    }
    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target=audioPermissionNotebook;audioPermissionNotebook=null
        if(granted && target==repository.session?.notebook?.id && libraryScreen==null) {if(!app.audio.start())message(app.audio.status)}
        else if(!granted) message("Microphone permission is needed only to record audio")
    }
    private var audioPermissionNotebook:String?=null
    internal fun recordAudio() {
        if(app.audio.active){app.audio.stop();return}
        if(app.audio.occupied || libraryScreen!=null || !repository.canChangeNotebook || repository.readOnly)return
        if(app.audio.needsPermission && androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            audioPermissionNotebook=repository.session?.notebook?.id;recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }else if(!app.audio.start())message(app.audio.status)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        quickNotePending = savedInstanceState == null && intent?.action == QUICK_NOTE
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
        app.io.execute {
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
                app.io.execute {
                    val result=runCatching {PdfSupport.importSelected(app.cacheDir,prepared,sourceTitle,indices)}
                    app.main.post {
                        repository.setFileBusy(false)
                        result.onSuccess {source->
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
                            }.onFailure {message("PDF import failed: ${it.message}")}
                        }.onFailure {message("PDF import failed: ${it.message}")}
                    }
                }
            }
        }).show().also {pdfSelectionDialog=it}
    }
    internal var pdfSelectionDialog: PdfPagePicker? = null

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
                    .setNeutralButton("Delete pages...") { _, _ ->
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
        if (!ScanProcessing.liveAllowed) {
            val file = ScanProcessing.captureFile(app).also { it.delete() }
            return runCatching {
                scanCapture.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.scans", file)); true
            }.getOrElse { ScanProcessing.target = null; message("Cannot open the camera: ${it.message}"); false }
        }
        if (!ScanCamera.available(this)) { message("This tablet has no camera. Choose a photo to scan."); pickScanPhoto(); return true }
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
        refresh()
    }
    /** Closes the Scan screen; [clear] also ends the scan (Add, Cancel), otherwise the kept pages wait for the next photo. */
    internal fun closeScan(clear: Boolean = true, refresh: Boolean = true) {
        val open = scanScreen ?: return
        scanScreen = null; frame.root.removeView(open); open.dispose()
        if (clear) ScanProcessing.clear()
        if (refresh) refresh()
    }
    /** The shutter on a photo: the camera again for the next page (live, the scan screen's own), keeping the pages so far. */
    internal fun captureNextScan() {
        if (ScanProcessing.target == null) return
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
    internal var notebookSearchDialog: AlertDialog? = null
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
        val status = ToolControls.caption(this, "")
        val field = android.widget.EditText(this).apply {
            hint = "Search text in this notebook"; isSingleLine = true; textSize = 16f; setTextColor(Glass.text(dark))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val results = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this)
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt(); setPadding(pad, pad / 2, pad, 0)
            addView(field); addView(status)
            addView(scroll.apply { addView(results) }, android.widget.LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .45f).toInt()))
        }
        val open = java.util.concurrent.atomic.AtomicBoolean(true); var failed = 0
        val snippets = SearchSnippets(thumbnails, note, scroll, results)
        val dialog = AlertDialog.Builder(this).setTitle("Search in notebook").setView(content).setNegativeButton("Close") { _, _ -> }
            .setOnDismissListener { open.set(false); snippets.close(); app.searchIndexer.onInkRead = null; notebookSearchDialog = null; notebookSearchField = null; notebookSearchResults = null; notebookSearchStatus = null }.create()
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
                    isClickable = true; background = android.util.TypedValue().let { value ->
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true); getDrawable(value.resourceId)
                    }
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
        dialog.show(); refresh()
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
        repository.setFileBusy(true)
        app.io.execute {
            val file = runCatching {
                val root = java.io.File(cacheDir, "attachments")
                root.listFiles { f -> f.name.startsWith("export-") }?.forEach { it.deleteRecursively() }
                java.io.File(java.io.File(root, "export-${System.nanoTime()}").apply { mkdirs() }, name).also(write)
            }
            app.main.post {
                repository.setFileBusy(false)
                if (isDestroyed) return@post
                file.fold(onSuccess = { f ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.attachments", f)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(android.content.Intent.createChooser(intent, "Share $name"))
                    }.onFailure { message(if (it is android.content.ActivityNotFoundException) "No app on this tablet can receive this file" else "${it.message}") }
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
    fun navigationHistoryDialog(): AlertDialog? {
        if (ink.isEditing || !repository.canChangeNotebook) return null
        val session = repository.session ?: return null
        return AlertDialog.Builder(this).setTitle("Navigation history")
            .setMessage("Return to pages and notebooks visited through links or page jumps. Scrolling does not add history.")
            .setNegativeButton("Close", null).setNeutralButton("Back", null).setPositiveButton("Forward", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = repository.canNavigateBack
                getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = repository.canNavigateForward
                fun travel(forward: Boolean) {
                    if (repository.session !== session || !navigateHistory(forward)) {
                        message("Navigation is unavailable. Reopen Navigation history.")
                    }
                    dismiss()
                }
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { travel(false) }
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { travel(true) }
            }
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
    fun bookmarksDialog(): AlertDialog? {
        if (ink.isEditing || !repository.canChangeNotebook) return null
        val snapshot = repository.session?.notebook ?: return null
        val entries = snapshot.pages.withIndex().filter { it.value.bookmark != null }
        val builder = AlertDialog.Builder(this).setTitle("Bookmarks").setNegativeButton("Close", null)
        if (entries.isEmpty()) builder.setMessage("No bookmarked pages yet. Use Bookmark page from the page menu.")
        else builder.setItems(entries.map { outlineEntry(it.index, it.value) }.toTypedArray()) { _, index ->
            if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook)
                message("The notebook changed. Reopen Bookmarks.")
            else {
                val id = entries[index].value.id
                repository.selectPage(id)
                frame.revealPage(id)
            }
        }
        return builder.show()
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
    fun layersDialog(): AlertDialog? {
        if (ink.isEditing || repository.readOnly || !repository.canChangeNotebook) return null
        val pageId = repository.selectedPageId ?: return null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(12f), 0) }
        val dialog = AlertDialog.Builder(this).setTitle("Layers").setView(android.widget.ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null).setNeutralButton("Add layer", null).create()
        lateinit var refresh: () -> Unit
        fun change(operation: (DocumentSession) -> Boolean) {
            val snapshot = repository.session?.notebook
            if (snapshot == null || repository.selectedPageId != pageId) { dialog.dismiss(); message("Page changed; reopen Layers"); return }
            runCatching { repository.editLayers(snapshot, pageId, operation) }.onFailure { message(it.message ?: "Cannot change layers") }
            ink.clearSelection(); refresh()
        }
        fun button(label: String, description: String, enabled: Boolean = true, action: () -> Unit) = android.widget.Button(this).apply {
            text = label; contentDescription = description; isEnabled = enabled; minWidth = 0; minimumWidth = dpi(44f)
            isAllCaps = false; setOnClickListener { action() }
        }
        fun rename(layer: Layer) {
            val input = EditText(this).apply { setText(layer.name); setSingleLine(); selectAll(); contentDescription = "Layer name"
                filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
            AlertDialog.Builder(this).setTitle("Rename layer").setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Rename") { _, _ -> change { it.renameLayer(pageId, layer.id, input.text.toString()) } }.show()
        }
        refresh = refresh@{
            list.removeAllViews()
            val page = repository.currentPage?.takeIf { it.id == pageId } ?: return@refresh
            if (page.layers.isEmpty()) {
                list.addView(android.widget.TextView(this).apply {
                    text = "This page has one layer. Add a layer to draw above your ink, then tap a layer to draw on it."
                    setPadding(0, dpi(8f), 0, dpi(8f))
                })
            }
            val active = repository.session?.activeLayer(pageId)
            page.layers.withIndex().reversed().forEach { (index, layer) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(android.widget.TextView(this).apply {
                    text = (if (layer.id == active) "✓ " else " ") + layer.name + (if (layer.hidden) " (hidden)" else "") + if (layer.locked) " (locked)" else ""
                    contentDescription = "Layer ${layer.name}"; textSize = 16f; setPadding(0, dpi(12f), dpi(8f), dpi(12f))
                    setOnClickListener {
                        if (layer.locked) { message("Unlock ${layer.name} to draw on it"); return@setOnClickListener }
                        if (layer.hidden) change { it.setLayerHidden(pageId, layer.id, false) }
                        repository.session?.takeIf { repository.selectedPageId == pageId }?.selectLayer(pageId, layer.id)
                        ink.clearSelection(); refresh()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(button(if (layer.hidden) "Show" else "Hide", "${if (layer.hidden) "Show" else "Hide"} ${layer.name}") {
                    change { it.setLayerHidden(pageId, layer.id, !layer.hidden) }
                })
                // P101 (F075): a locked layer's ink stays drawn but can't be selected or erased.
                row.addView(button(if (layer.locked) "Unlock" else "Lock", "${if (layer.locked) "Unlock" else "Lock"} ${layer.name}") {
                    change { it.setLayerLocked(pageId, layer.id, !layer.locked) }
                })
                // P102 (F078): see-through layers for tracing.
                val percent = Math.round(layer.opacity * 100)
                row.addView(button("$percent%", "Opacity of ${layer.name}, $percent percent") {
                    val choices = intArrayOf(100, 75, 50, 25, 10)
                    AlertDialog.Builder(this).setTitle("Opacity of “${layer.name}”")
                        .setSingleChoiceItems(choices.map { "$it %" }.toTypedArray(), choices.indexOf(percent)) { picker, which ->
                            picker.dismiss(); change { it.setLayerOpacity(pageId, layer.id, choices[which] / 100f) }
                        }.setNegativeButton("Cancel", null).show()
                })
                row.addView(button("↑", "Move ${layer.name} up", index < page.layers.lastIndex) { change { it.moveLayer(pageId, layer.id, index + 1) } })
                row.addView(button("↓", "Move ${layer.name} down", index > 0) { change { it.moveLayer(pageId, layer.id, index - 1) } })
                row.addView(button("Rename", "Rename ${layer.name}") { rename(layer) })
                row.addView(button("Delete", "Delete ${layer.name}", page.layers.size > 1) {
                    AlertDialog.Builder(this).setTitle("Delete “${layer.name}”?").setMessage("Removes this layer and its ink, photos and text. Undo brings them back.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> change { it.deleteLayer(pageId, layer.id) } }.show()
                })
                list.addView(row)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = page.layers.size < Page.MAX_LAYERS
        }
        dialog.show()
        refresh()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { change { it.addLayer(pageId); true } }
        return dialog
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
        }) { ids, w, h, paper, replace ->
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
    fun deletePageDialog(pageId: String? = repository.selectedPageId): AlertDialog? {
        if (repository.readOnly || !repository.canChangeNotebook) return null
        val snapshot = repository.session?.notebook ?: return null
        val number = snapshot.pages.indexOfFirst { it.id == pageId } + 1
        if (number == 0) return null
        if (snapshot.pages.size <= 1) { message("Keep at least one page in the notebook"); return null }
        return AlertDialog.Builder(this).setTitle("Delete page $number?")
            .setMessage("Removes this page and its contents. Undo is available in this open notebook until its history is cleared. There is no persistent page trash yet.")
            .setPositiveButton("Delete") { _, _ ->
                if (repository.session?.notebook !== snapshot || !repository.canChangeNotebook || repository.readOnly) {
                    message("Page changed; reopen Delete page")
                } else {
                    repository.selectPage(pageId!!)
                    if (!repository.deletePage(snapshot, pageId)) message("Page changed; reopen Delete page")
                }
            }.setNegativeButton("Cancel", null).show()
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
        val desk = if (dark) Tokens.DESK_DARK else Tokens.DESK
        val lightDesk = !dark
        // P567: Settings over the Library looks as it does over the editor.
        val library = libraryScreen != null && !frame.settingsOpen
        // P568: at night the Library's bars are its own desk.
        window.statusBarColor = if (library) (if (dark) Kit.desk(true) else Tokens.INK) else desk
        window.navigationBarColor = if (library && dark) Kit.desk(true) else desk
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
    fun showLibrary(allowLoading: Boolean = false, syncOnOpen:Boolean = true) {
        cancelPdfTextSelection()
        libraryAfterSave = null
        if (!allowLoading && libraryScreen == null && !openingLibrary && !ink.isEditing && !repository.canChangeNotebook && repository.onlySaving) {
            repository.flushSave(); libraryAfterSave = repository.editRevision; return
        }
        if (openingLibrary || libraryScreen != null || (!allowLoading && !repository.canChangeNotebook) || ink.isEditing) return
        openingLibrary = true
        try {
        frame.closeSettings(); frame.closeOrganizer(); closeScan()
        // P530: the Library belongs to the first document; opening a book replaces only that pane.
        focusPane(primaryInk)
        val screen = LibraryScreen(this, app.repository, thumbnails, onOpened = { id ->
            // P567: found by what is written inside it: go on to show where.
            val found = libraryScreen?.foundInside(id)?.takeIf { app.repository.session?.notebook?.id == id }
            closeLibrary()
            if (found != null) frame.root.post { notebookSearchDialog(found) }
        },
            onImport = { launchImport() }, onBackup = { backupDialog() },
            onExportSelected = { chooseBatchPdfFolder(it) }, onExportAll = { chooseAllExportFolder() },
            onSync = { syncDialog() }, syncStatus = { LibrarySync.status(this) },
            onSourceRecovery = { recoverNotebookSource(it) }, storageActions = libraryStorageActions ?: NotebookStorageActions(app.repository))
        libraryScreen = screen
        frame.root.addView(screen, android.widget.FrameLayout.LayoutParams(-1, -1))
        systemBars()
        refresh()
        // B50: back in the folder, tag and scroll position the notebook was opened from.
        if (repository.loaded) libraryPlace?.let { screen.returnTo(it) }
        // P139 (F167): opening the library syncs it, unless sync is paused; only problems are shown.
        if (syncOnOpen && repository.canChangeNotebook && LibrarySync.hasSource(this) && !LibrarySync.paused(this)) syncLibrary(announce = false)
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
            frame.closeSettings(); frame.closeOrganizer(); closeScan()
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
        val enabled = repository.session != null && !repository.fileBusy && !repository.busy && libraryScreen == null && !frame.settingsOpen && !frame.organizerOpen && scanScreen == null
        val active = ink
        primaryInk.inputEnabled = enabled && active === primaryInk
        companionInk?.inputEnabled = enabled && active === companionInk
        closeSettings.isEnabled = true
        frame.refresh()
        primaryInk.refresh()
        companionInk?.refresh()
    }
    fun message(text: String) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    /** P088: the backup sheet; the first time it goes straight to the folder picker. */
    fun backupDialog(): AlertDialog? {
        if (isDestroyed) return null
        val hasBackupFolder = LibraryBackup.folderUri(this) != null
        val last = LibraryBackup.lastBackup(this)
        val when_ = if (last == 0L) "No backup has finished yet." else "Last backup: " +
            android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS) +
            " (${LibraryBackup.lastCount(this)} notebooks)."
        // P124 (F220): the text and a Restore action share a custom view, since the three dialog buttons are taken.
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(12f), dpi(24f), 0) }
        body.addView(android.widget.TextView(this).apply {
            text = (if(hasBackupFolder) "Notebooks are copied to “${LibraryBackup.folderName(this@MainActivity)}” in ${LibraryBackup.folderLabel(this@MainActivity)}. $when_\n\n" else if(personalTools) "Choose an additional folder for native backups. Readable recovery copies below are automatic.\n\n" else "Choose a folder for your backups.\n\n") +
                "Only changed notebooks are copied, each copy is checked, and the library is also backed up when you leave the app."
            textSize = 16f
        })
        if (personalTools) {
            body.addView(Kit.section(this, "Readable recovery copies"))
            body.addView(Kit.text(this, app.safetyCopies.status()+"\n\nThese ordinary files work outside Scribella and its app lock. The latest two verified local generations are retained. Folder-provider delivery may follow later.", secondary = true))
            body.addView(Kit.button(this, "Open recovery PDFs in Files", Kit.Button.TINTED) {app.safetyCopies.openFiles(this)})
            body.addView(Kit.button(this, "Update recovery copies", Kit.Button.PLAIN) {app.safetyCopies.requestAll(repository.entries);message("Recovery refresh requested. Completed copies remain in Downloads / ${getString(R.string.safety_folder)}.")})
        }
        val restore = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "Restore missing notebooks…"; isAllCaps = false }
        restore.isEnabled = hasBackupFolder
        body.addView(restore, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        val dialog = AlertDialog.Builder(this).setTitle("Library backup").setView(body)
            .setPositiveButton(if(hasBackupFolder) "Back up now" else "Choose backup folder…") { _, _ -> if(hasBackupFolder) backUpLibrary(announce = true) else chooseBackupFolder() }
            .setNeutralButton("Change folder…") { _, _ -> chooseBackupFolder() }
            .setNegativeButton("Stop backing up") { _, _ -> LibraryBackup.stop(this); message("Backups stopped. Files already in the folder stay there.") }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = hasBackupFolder
        restore.setOnClickListener { dialog.dismiss(); restoreDialog() }
        return dialog
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
                if (!app.repository.restoreFromBackup({ LibraryBackup.TreeFolder(app, tree) }) { message(repository.libraryMessage ?: "Restore finished.") })
                    message("Restore is not available while the notebook is busy.")
            }
            .setNegativeButton("Cancel", null)
            .show().also { restorePrompt = it }
    }
    private fun chooseBackupFolder() {
        runCatching { backupFolder.launch(LibraryBackup.folderUri(this)) }.onFailure { message("Cannot choose a folder: ${it.message}") }
    }
    /** P203: library transfer controls use the shared Glass kit. No provider account is needed. */
    fun syncDialog(context: android.content.Context = this): Kit.Sheet? {
        if (isDestroyed) return null
        if (!LibrarySync.hasSource(this)) {
            return Kit.Sheet(context, "Library sync", cancel = "Done").apply {
                add(Kit.text(context, "Choose where to sync your notebooks. Every notebook stays saved on this tablet until you explicitly free its local copy.", secondary = true))
                add(Kit.button(context, "Choose sync folder…", Kit.Button.TINTED) { dismiss(); chooseSyncFolder() })
                if (personalTools) {
                    add(Kit.button(context, "Try smaller uploads in a new folder…", Kit.Button.PLAIN) { dismiss(); chooseChangedSyncFolder() })
                    add(Kit.text(context, "Test folders reuse PDF and photo data after the first upload. They do not replace files in an existing sync folder. Recovery data is retained; the nightly reader does not use this test format.", secondary = true))
                }
                add(Kit.button(context, "WebDAV server…", Kit.Button.TINTED) { dismiss(); webDavSyncDialog(context) })
                encryptedSyncControls(this,context)
            }.show()
        }
        val paused = LibrarySync.paused(this)
        lateinit var sheet: Kit.Sheet
        sheet = Kit.Sheet(context, "Library sync", "Sync now", cancel = "Done") {
            syncLibrary(announce = true)
            true
        }
        sheet.add(Kit.text(context, "${LibrarySync.status(this)} · ${LibrarySync.folderLabel(this)}", Kit.Type.HEADLINE))
        sheet.add(Kit.text(context, if (LibrarySync.webDavSelected(this)) "Notebooks sync with the configured WebDAV server folder. Every copy is checked; conflicting edits keep both versions. Replaced versions are kept and remote deletions go to the trash here." else "Notebooks sync with “${LibrarySync.folderName(this@MainActivity)}”. Every copy is checked; a notebook changed on two devices keeps both versions. Replaced versions are kept and remote deletions go to the trash here.", secondary = true))
        if (!LibrarySync.webDavSelected(this) && personalTools) {
            sheet.add(Kit.text(context, if (LibrarySync.changedPages(this)) "Smaller uploads test: unchanged PDFs and photos are reused. Recovery data is retained in this folder. The nightly reader does not use this test format." else "Complete-notebook uploads are active. The smaller-uploads test uses a separate empty folder.", secondary = true))
            sheet.add(Kit.button(context, "Try smaller uploads in a new folder…", Kit.Button.PLAIN) { sheet.dismiss(); chooseChangedSyncFolder() })
        }
        LibrarySync.lastResult(this)?.let { sheet.add(Kit.text(context, "Last sync: $it", Kit.Type.CAPTION, secondary = true)) }
        sheet.add(Kit.section(context, "Transfers"))
        val wifiSwitch = Kit.Switch(context, LibrarySync.wifiOnly(this), "Wi-Fi-only sync") {
            LibrarySync.setWifiOnly(this, it)
        }
        val backgroundSwitch = Kit.Switch(context, BackgroundSync.enabled(app), "Background sync") {
            if (!BackgroundSync.setEnabled(app, it)) {
                message("Android could not schedule background sync. Sync now still works.")
                error("Background sync scheduling refused")
            }
        }
        sheet.add(Kit.Group(context).apply {
            addView(Kit.row(context, "Background sync", "Let Android sync while the app is closed.", trailing = backgroundSwitch) { backgroundSwitch.performClick() })
            addView(Kit.row(context, "Wi-Fi-only sync", "Wait for Wi-Fi before starting folder transfers.", trailing = wifiSwitch) { wifiSwitch.performClick() })
        })
        sheet.add(Kit.text(context, app.backgroundHistory.summary(LibrarySync.sourceIdentity(app)), Kit.Type.CAPTION, secondary = true))
        sheet.add(Kit.text(context, if (LibrarySync.webDavSelected(this)) "Android schedules background attempts about every 15 minutes or later when battery and network conditions allow. Pause prevents new background attempts. Wi-Fi-only also applies to Sync now. Interrupted native transfers keep recovery records; use Inspect interrupted transfer if sync needs attention." else "Android schedules background attempts about every 15 minutes or later, when battery and network conditions allow. Pause stops them. Wi-Fi-only controls Scribella’s folder access, including Sync now. A transfer already in progress can finish. Your cloud app controls when its cached files upload. Connect to Wi-Fi and choose Sync now to retry.", Kit.Type.CAPTION, secondary = true))
        sheet.add(Kit.text(context, if (paused) "Sync is paused. Sync now still works." else "The library syncs each time you open it.", secondary = true))
        sheet.add(Kit.button(context, if (paused) "Resume" else "Pause", Kit.Button.TINTED) {
            LibrarySync.setPaused(this, !paused)
            sheet.dismiss()
            message(if (paused) "Sync resumed." else "Sync paused. Your notebooks stay saved on this tablet; Sync now still works.")
        })
        sheet.add(Kit.button(context, "Change folder…", Kit.Button.PLAIN) { sheet.dismiss(); chooseSyncFolder() })
        sheet.add(Kit.button(context, "WebDAV server…", Kit.Button.PLAIN) { sheet.dismiss(); webDavSyncDialog(context) })
        if (LibrarySync.webDavSelected(this)) sheet.add(Kit.button(context, "Inspect interrupted transfer…", Kit.Button.TINTED) {
            sheet.dismiss(); webDavRecoveryDialog(context)
        })
        sheet.add(Kit.button(context, "Stop syncing", Kit.Button.DANGER) {
            LibrarySync.stop(app); sheet.dismiss()
            message("Sync stopped. Notebook copies, saved account and transfer recovery data were kept.")
        })
        encryptedSyncControls(sheet,context)
        return sheet.show()
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
    fun syncLibrary(announce: Boolean) {
        LibrarySync.sync(app, app.repository) { text ->
            if (text != null && (announce || text.startsWith("Sync failed") || text.contains("could not sync") || text.contains("both versions kept"))) message(text)
            EncryptedSync.sync(app) { extra -> if(extra!=null && (announce || extra.startsWith("Encrypted sync failed"))) message(extra) }
        }
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
                    }.onFailure { message(if (it is android.content.ActivityNotFoundException) "No app on this tablet can open this file" else "${it.message}") }
                }, onFailure = { message("Could not prepare the file: ${it.message}") })
            }
        }
    }
    override fun onResume() {super.onResume();appLockGate.resumed()}
    override fun onPause() { appLockGate.paused();frame.zoomWriting?.canvas?.prepareForPause();primaryInk.prepareForPause();companionInk?.prepareForPause(); app.openDocuments.forEach { it.flushSave() }; super.onPause() }
    // P103: Paste follows what other apps copied while Scribella was in the background.
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refresh() }
    // P088: leaving the app backs the library up when a backup folder is set (queued behind the save onPause started).
    override fun onStop() {if(!isChangingConfigurations){if(app.audio.active)app.audio.stop();app.audioPlayback.stop()};appLockGate.stopped(); if (!isChangingConfigurations && LibraryBackup.folderUri(this) != null) backUpLibrary(announce = false); super.onStop() }
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
    override fun onDestroy() {encryptedBackupPrompt?.sheet?.dismiss();appLockGate.dispose();appLockSetup?.sheet?.dismiss(); encryptedSyncPrompt?.sheet?.dismiss(); pdfPasswordPrompt?.sheet?.dismiss(); pdfLinkSheet?.dialog?.dismiss(); closeScan(refresh = false);closeTextEditorForDestroy(); closePhotoCropForDestroy(); app.openDocuments.forEach { it.unlisten(observer) }; primaryInk.disposePageCurl();companionInk?.disposePageCurl(); thumbnails.close(); super.onDestroy() }
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
