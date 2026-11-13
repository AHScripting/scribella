package dev.ownednotes.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import dev.ownednotes.core.*
import java.io.File
import java.util.concurrent.Executors

data class PdfInsertTarget(val notebook: Notebook, val pageId: String)
data class ImageInsertTarget(val notebook: Notebook, val pageId: String)

open class NotesApplication : Application() {
    val io = Executors.newSingleThreadExecutor()
    // Saved reads do not queue behind a provider or a stalled recoverable Trash move.
    internal val opening = Executors.newSingleThreadExecutor { task -> Thread(task,"notes-opening").apply {isDaemon=true} }
    internal val trashing = Executors.newSingleThreadExecutor { task -> Thread(task,"notes-trash").apply {isDaemon=true} }
    // P457 (B51): the open book's own saves never wait behind a provider transfer on io. Each holds that book's
    // NotebookReadGate, which sync/backup also take for their short local commits; every other write stays on io.
    internal val saving = Executors.newSingleThreadExecutor { task -> Thread(task,"notes-saving") }
    /** Call on io before sync/backup; wait for the actual requested revision, including pending/spaced saves. */
    internal fun afterQueuedSaves() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Do not wait for local saving on the UI thread" }
        val completed = java.util.concurrent.CompletableFuture<Unit>()
        // P530: every open document's requested revision; a failure in either one stops the sync or backup.
        check(main.post {
            OpenNotebookSaves.await(openDocuments.map { document -> { saved: java.util.concurrent.CompletableFuture<Unit> -> document.completeAfterLocalSaves(saved) } })
                .whenComplete { _, error -> if (error == null) completed.complete(Unit) else completed.completeExceptionally(error) }
        }) { "Local saving is unavailable" }
        try { completed.get() }
        catch (error: InterruptedException) { completed.cancel(false); Thread.currentThread().interrupt(); throw error }
        catch (error: java.util.concurrent.ExecutionException) {
            throw java.io.IOException("Local saving failed; sync or backup was not started", error.cause)
        }
    }

    // P322: selected page backgrounds must not wait behind notebook saves/imports/search.
    // Process-owned like io; each canvas submits at most one background render at a time.
    internal val pageRendering = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() },
            "notes-page-background" ).apply { isDaemon = true }
    }
    /** Library startup must not depend on opening any particular notebook. */
    open val restoreSessionOnStartup: Boolean get() = false
    /** A new install opens on an empty library. Only tests start with a notebook, so they have one to work in. */
    open val seedFirstNotebook: Boolean get() = false
    /**
     * P568: has a real pen (one Android reports as a stylus) ever touched or hovered over the app? A new install starts
     * with no, so a finger writes; an install updated from before this existed starts with yes and keeps pen mode.
     */
    protected open val penAssumed: Boolean get() = packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime != it.lastUpdateTime }
    private var penSeenNow: Boolean? = null
    internal var penSeen: Boolean
        get() = penSeenNow ?: getSharedPreferences(preferencesName, 0).let { saved ->
            if (saved.contains("penSeen")) saved.getBoolean("penSeen", false)
            else penAssumed.also { saved.edit().putBoolean("penSeen", it).apply() }
        }.also { penSeenNow = it }
        set(value) { penSeenNow = value; getSharedPreferences(preferencesName, 0).edit().putBoolean("penSeen", value).apply() }
    /** P574: the What's new note and the automatic update check belong to the real library, not to test runs. */
    open val showsNews: Boolean get() = preferencesName == "library"
    internal val isRecoveryProcess get() = Application.getProcessName().endsWith(":recovery")
    open val automaticSafetyCopies: Boolean get() = preferencesName == "library" && personalTools
    internal val safetyCopies by lazy { SafetyCopies(this) }
    val main = Handler(Looper.getMainLooper())
    open val storageRoot: File get() = filesDir
    // Strong process-lifetime owner. Derived copies stay outside notebooks, backups and sync.
    private val attachmentWorkspace by lazy { AttachmentWorkspace.open(File(storageRoot,"attachment-sessions")) }
    internal val attachmentDirectory: File get() = attachmentWorkspace.directory
    /** One owner serializes library transactions; all callers perform its IO on io. */
    val stickerLibrary: StickerLibrary by lazy { StickerLibrary(File(storageRoot, "stickers")) }
    /** P130 (F109): reusable ink stamps, outside every notebook. */
    val stampLibrary: StampLibrary by lazy { StampLibrary(File(storageRoot, "stamps")) }
    open val preferencesName: String get() = "library"
    /** Account secrets stay in Keystore-backed no-backup storage, outside notebooks and shared sync folders. */
    internal val webDavAccounts by lazy { WebDavAccountStore.forApp(this) }
    internal val appLockSession=AppLockSession()
    internal val studyCards by lazy {StudyCards(this)}
    internal val sharedNotebookImport by lazy {SharedNotebookImport(this)}
    internal val audio by lazy {AudioRecording(this)}
    internal val audioPlayback by lazy {AudioPlayback(this)}
    internal val backgroundHistory by lazy { BackgroundSyncHistory(getSharedPreferences(preferencesName, 0)) }
    /** Landscape two-page spread (N003) until the user chooses; instrumented tests keep single-page fitting. */
    open val bookSpreadDefault: Boolean get() = true
    var exportSnapshot: Notebook? = null
    var pdfExportSnapshot: Notebook? = null
    var pdfExportReviewedHandwriting: Boolean = true
    var pdfExportEditableInk: Boolean = false
    var pdfExportSmallerFile:Boolean=false
    var pngExportSnapshot: PageImageSnapshot? = null
    internal var svgExportSnapshot: SvgExportSnapshot? = null
    var studyInkExportSnapshot: Notebook? = null
    /** P107: the notebook a text or Markdown export will write. */
    var textExportSnapshot: Notebook? = null
    var pdfInsertTarget: PdfInsertTarget? = null
    var imageInsertTarget: ImageInsertTarget? = null
    var photoInsertTarget: ImageInsertTarget? = null
    var attachInsertTarget: ImageInsertTarget? = null
    val mixedClipboard = MixedClipboard()
    val inkClipboard = InkClipboard()
    val photoClipboard = PhotoClipboard()
    val textClipboard = TextClipboard()
    /** P103: when something was last copied here, so newer text from another app pastes instead. */
    var copiedAt = 0L
    /** F124: where the last copy came from. [text] is set for PDF text, which only the Android clipboard carries. */
    internal class ClippingSource(val link: NotebookLink, val text: String? = null)
    internal var clippingSource: ClippingSource? = null
    internal var textEditorState: TextEditorState? = null
    internal var photoCropState: PhotoCropState? = null
    lateinit var repository: NotebookRepository
    /** P530 (F081/F082): the open documents; the second exists only once side-by-side view has been used. Main thread. */
    internal val openDocuments get() = listOfNotNull(repository, repository.companion)
    /** P530: every open document can take part in a library-wide operation (sync, backup). */
    internal val documentsReady get() = openDocuments.all { it.canChangeNotebook }
    private var focusedCompanion = false
    /** P530: the document the shared toolbar and editor commands act on; the Library always uses [repository]. */
    internal val activeDocument: NotebookRepository get() = repository.companion?.takeIf { focusedCompanion } ?: repository
    internal fun focusDocument(document: NotebookRepository) {
        check(Looper.myLooper() == Looper.getMainLooper())
        focusedCompanion = document === repository.companion
        searchIndexer.schedule(activeDocument.session?.notebook)
    }
    /** P106 (F142): reads PDF, image and photo pages in the background so search finds them at once. */
    internal val searchIndexer by lazy { SearchIndexer(this) }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            PageText.clearMemory(); InkText.clearMemory(); NotebookPackage.clearCaches()
        }
    }
    override fun onLowMemory() {
        super.onLowMemory()
        PageText.clearMemory(); InkText.clearMemory(); NotebookPackage.clearCaches()
    }
    override fun onCreate() {
        super.onCreate()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        PdfAccess.initialize(this)
        DocumentSession.redrawConnector = ConnectorInk::redraw
        if (isRecoveryProcess) return // Recovery must not construct the editor repository or eagerly open a notebook.
        PersonalVocabulary.initialize(this)
        PageText.index = SearchIndex(File(cacheDir, "search-index"))
        repository = NotebookRepository(this)
        BackgroundSync.update(this)
        repository.listen { searchIndexer.schedule(activeDocument.session?.notebook) }
    }
}

class NotebookRepository(private val app: NotesApplication, private val owner: NotebookRepository? = null) {
    /** P530 (F081/F082): what both documents see of one library. Document state (session, journal, revisions, writer) stays per repository. */
    private class SharedLibrary {
        var entries = emptyList<LibraryEntry>()
        var trashed = emptyList<LibraryEntry>()
        var trashingId: String? = null
        // Each in-flight sync retains every notebook opened during that cycle, including a read still in preparation.
        val openingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val syncReservations = mutableListOf<MutableSet<String>>() // main-thread ownership
    }
    private val shared: SharedLibrary = owner?.shared ?: SharedLibrary()
    /** The side-by-side second document; it has no startup, library or "active" role of its own. */
    internal var companion: NotebookRepository? = null; private set
    private val peer get() = owner ?: companion
    private var openingId: String? = null
    /** True while the other document holds, or is opening, this notebook. One writer per notebook. */
    internal fun heldByPeer(id: String) = peer?.let { it.session?.notebook?.id == id || it.openingId == id } == true
    private val sideBySide get() = (owner ?: this).companion?.let { it.session != null || it.openingId != null } == true
    /** Library mutations leave a notebook alone while a pane of a side-by-side view holds it. */
    internal fun heldInSideBySide(id: String) = sideBySide && (session?.notebook?.id == id || openingId == id || heldByPeer(id))
    /** Opens [id] beside this document. Refused for this notebook; a failed open leaves this document exact. */
    internal fun openCompanion(id: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(owner == null) { "Only the first document opens a companion" }
        if (session == null || session?.notebook?.id == id) return false
        val document = companion ?: NotebookRepository(app, this).also { created ->
            companion = created
            created.listen { app.searchIndexer.schedule(app.activeDocument.session?.notebook) }
        }
        return document.session == null && document.openNotebook(id)
    }
    /** Detaches a fully saved companion; the journal is empty by then, and the first document does not change. False while anything is unsaved. */
    internal fun closeCompanion(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val document = companion ?: return true
        if (document.session != null && !document.readyForNotebookChange) return false
        document.session = null; document.selectedPageId = null; document.userReadOnly = false
        document.recoveryCheckpoint = null; document.journal = null; document.warning = null; document.status = "Ready"
        document.backLocations.clear(); document.forwardLocations.clear()
        if (app.activeDocument === document) app.focusDocument(this)
        document.notifyChanged(); notifyChanged()
        return true
    }
    /** Last open only; monotonic durations, no note contents or identity retained. */
    internal data class OpenTiming(val queueMs: Long, val readMs: Long, val prepareMs: Long, val mainQueueMs: Long, val activateMs: Long, val notifyMs: Long, val initialUiMs: Long, val workerQueueMs: Long)
    internal var lastOpenTiming: OpenTiming? = null; private set
    private val library: NotebookLibrary = owner?.library ?: NotebookLibrary(File(app.storageRoot, "notebooks"),{ app.attachmentDirectory }, onWritten = { app.safetyCopies.request(it.nameWithoutExtension) })
    /** P128 (F144): the Library search's reader of saved handwriting and typed text; call it off the main thread. */
    val libraryText = LibraryText(library)
    var session: DocumentSession? = null; private set
    var selectedPageId: String? = null; private set
    private var userReadOnly = false
    private var recoveryCheckpoint: Long? = null
    val explicitReadOnly get() = userReadOnly
    private var replayHeld=false
    internal fun setReplayHeld(held:Boolean){replayHeld=held}
    private val unavailableRecoveryIds=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recoveryUnavailable get()=session?.notebook?.id?.let {it in unavailableRecoveryIds}==true
    val readOnly get() = userReadOnly || recoveryUnavailable || recoveryCheckpoint != null || replayHeld
    /** Read-only only until a new original finishes its save, so a selection may stay. */
    internal val savingOriginal get() = recoveryCheckpoint != null && !userReadOnly && !recoveryUnavailable && !replayHeld
    private var journal: RecoveryJournal? = null
    private data class PreparedRecovery(val journal:RecoveryJournal?=null,val unavailable:Boolean=false)
    private val preparedJournals = java.util.IdentityHashMap<DocumentSession, PreparedRecovery>()
    private val openingIds get()=shared.openingIds
    private val syncReservations get()=shared.syncReservations

    /** Runs beside parsing on the IO executor; finished ink never waits behind a full package save. */
    private fun prepareRecovery(prepared: DocumentSession): DocumentSession {
        val saved=prepared.notebook
        try {
            val opened = RecoveryJournal.open(File(app.storageRoot, "recovery-journals"), prepared,
                File(app.storageRoot,"notebooks/${saved.id}.inknote"),File(app.cacheDir,"recovery-fingerprints"))
            synchronized(preparedJournals) { preparedJournals[prepared] = PreparedRecovery(journal=opened) }
            return prepared
        } catch(error:Exception) {
            if(error is InterruptedException) {Thread.currentThread().interrupt();throw error}
            // A failed replay may have partly mutated its session. Fall back to the validated native snapshot,
            // never that partially recovered session, and never acknowledge or replace the recovery evidence.
            val fallback=DocumentSession(saved)
            synchronized(preparedJournals) {preparedJournals[fallback]=PreparedRecovery(unavailable=true)}
            android.util.Log.w("NotesRecovery","Private recovery unavailable: ${error.javaClass.simpleName}")
            return fallback
        }
    }
    fun isNotebookReadOnly(id: String) = id in unavailableRecoveryIds || preferences.getBoolean("readOnly:$id", false)
    fun setReadOnly(enabled: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val id = session?.notebook?.id ?: return false
        if (!canChangeNotebook || recoveryUnavailable) return false
        userReadOnly = enabled
        preferences.edit().putBoolean("readOnly:$id", enabled).apply()
        notifyChanged()
        return true
    }
    private data class Location(val notebookId:String,val pageId:String)
    private val backLocations = ArrayDeque<Location>()
    private val forwardLocations = ArrayDeque<Location>()
    private fun location()=session?.notebook?.id?.let {book->selectedPageId?.let {Location(book,it)}}
    val canNavigateBack get() = canChangeNotebook && hasLocation(backLocations)
    val canNavigateForward get() = canChangeNotebook && hasLocation(forwardLocations)
    private fun available(value:Location):Boolean = if(value.notebookId==session?.notebook?.id)
        session!!.notebook.pages.any {it.id==value.pageId} else localEntry(value.notebookId)!=null
    private fun hasLocation(locations:ArrayDeque<Location>)=locations.any {it!=location() && available(it)}
    private fun pushLocation(locations:ArrayDeque<Location>,value:Location?) {
        if(value==null || locations.lastOrNull()==value)return
        if(locations.size>=100)locations.removeFirst()
        locations.addLast(value)
    }
    /** Document Undo is independent of navigation, including cross-notebook jumps. */
    fun navigateHistory(forward:Boolean,onOpened:()->Unit={}):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!canChangeNotebook)return false
        val source=if(forward)forwardLocations else backLocations
        val destination=if(forward)backLocations else forwardLocations
        while(source.isNotEmpty()) {
            val target=source.removeLast()
            if(target==location() || !available(target))continue
            val from=location()
            return followLink(NotebookLink(target.notebookId,target.pageId),false) {
                pushLocation(destination,from);onOpened()
            }
        }
        return false
    }
    internal fun followLink(target:NotebookLink,recordHistory:Boolean=true,onOpened:()->Unit={}):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!canChangeNotebook)return false
        val from=location()
        fun arrived() {
            if(recordHistory && from!=location()){pushLocation(backLocations,from);forwardLocations.clear()}
            onOpened();notifyChanged()
        }
        val current=session?.notebook
        if(current?.id==target.notebookId) {
            val page=target.pageId ?: selectedPageId ?: current.pages.first().id
            if(current.pages.none {it.id==page})return false
            selectPage(page,recordHistory=false);arrived();return true
        }
        if(localEntry(target.notebookId)==null)return false
        var parsed:DocumentSession?=null
        return changeNotebook(load={
            library.readSession(target.notebookId).also {value->
                require(target.pageId==null || value.notebook.pages.any {it.id==target.pageId}) {"Linked page is unavailable"}
                parsed=value
            }.notebook
        },prepare={requireNotNull(parsed)},keepHistory=true,readOnlyId=target.notebookId,opened={
            target.pageId?.let {selectedPageId=it;rememberPage()};arrived()
        })
    }
    /** Destination chooser reads only on IO; it never saves or changes the active notebook. */
    internal fun linkPages(id:String,done:(Result<List<Pair<String,String>>>)->Unit):Boolean {
        if(!canChangeNotebook || localEntry(id)==null)return false
        fun labels(note:Notebook)=note.pages.mapIndexed {i,p->p.id to (p.bookmark ?: "Page ${i+1}")}
        session?.notebook?.takeIf {it.id==id}?.let {done(Result.success(labels(it)));return true}
        app.io.execute {
            val result=runCatching {labels(library.readSession(id).notebook)}
            app.main.post {done(result)}
        }
        return true
    }
    val currentPage: Page? get() = session?.notebook?.pages?.firstOrNull { it.id == selectedPageId }
    var entries: List<LibraryEntry>
        get() = shared.entries
        private set(value) { shared.entries = value }
    var status = if (owner == null) "Loading…" else "Ready"; private set
    var warning: String? = null; private set
    var loaded = owner != null; private set
    private var operationBusy = owner == null
    private var trashingId: String?
        get() = shared.trashingId
        set(value) { shared.trashingId = value }
    // A pending Trash protects its active session, while an unrelated saved session can remain usable.
    var busy: Boolean
        get() = operationBusy || (trashingId != null && session?.notebook?.id == trashingId)
        private set(value) { operationBusy = value }
    var fileBusy = false; private set
    var inputBusy = false; private set
    val importing get() = busy
    private var revision = 0L
    private var savedRevision = 0L
    private var pendingWrite: Pair<Long, Notebook>? = null
    private var writing = false
    private val saveWaiters = mutableListOf<Pair<Long, java.util.concurrent.CompletableFuture<Unit>>>()
    /** Main-thread state owns the revision; a marker on the writer misses snapshots not submitted yet. */
    internal fun completeAfterLocalSaves(completed: java.util.concurrent.CompletableFuture<Unit>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (completed.isDone) return
        saveWaiters.removeAll { it.second.isDone }
        if (savedRevision >= revision) { completed.complete(Unit); return }
        saveWaiters += revision to completed
        flushSave()
    }
    private fun finishSaveWaiters(error: Throwable? = null) {
        val ready = saveWaiters.filter { it.second.isDone || it.first <= savedRevision || error != null }
        saveWaiters.removeAll(ready.toSet())
        ready.forEach { (target, completed) ->
            if (target <= savedRevision) completed.complete(Unit)
            else if (error != null) completed.completeExceptionally(error)
        }
    }
    internal val savingLocally get() = writing || pendingWrite != null
    /** P457: only the open book's save (or a failed one being retried) stands between the Library and another book. */
    internal val onlySaving get() = !recordingHeld && loaded && !busy && !fileBusy && !inputBusy && (savingLocally || savedRevision != revision)
    /** P457: moves on with every edit, so a Library request made before newer writing can be dropped. */
    internal val editRevision get() = revision
    private var recordingHeld=false
    internal val canCommitRecording get() = loaded && !readOnly && !busy && !fileBusy && !inputBusy && !writing && pendingWrite==null && savedRevision==revision
    internal fun recordingChanged(held:Boolean){recordingHeld=held;notifyChanged()}
    internal fun mediaChanged(){notifyChanged()}
    private val readyForNotebookChange get() = !recordingHeld && loaded && !operationBusy && !fileBusy && !inputBusy && !writing && pendingWrite == null && savedRevision == revision
    // Library mutations stay serialized; only saved reads of a different ID can bypass a pending Trash.
    val canChangeNotebook get() = trashingId == null && readyForNotebookChange
    internal fun canOpenSavedNotebook(id: String) = readyForNotebookChange && localEntry(id) != null
    private val listeners = linkedSetOf<() -> Unit>()
    init {
        if (owner == null) app.io.execute {
            var migrationWarning: String? = null
            val result = runCatching {
                val legacy = AtomicFile(File(app.storageRoot, "notebook.inknote"))
                val hasLegacy = legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists()
                if (hasLegacy) {
                    runCatching { legacy.openRead().use { library.migrateLegacy(library.readPackage(it)) } }
                        .onFailure { migrationWarning = "Legacy notebook preserved but could not be migrated: ${it.message}" }
                }
                var list = library.entries()
                val settings = app.getSharedPreferences(app.preferencesName, 0)
                val notebookRoot = File(app.storageRoot, "notebooks")
                val previouslyUsed = settings.getBoolean("library_initialized", false) ||
                    File(notebookRoot, "trash").exists() || File(notebookRoot, NotebookLibrary.DELETIONS).exists()
                if (app.seedFirstNotebook && list.isEmpty() && migrationWarning == null && !hasLegacy && !previouslyUsed) {
                    library.write(Notebook(title = "Notebook 1")); list = library.entries()
                }
                val preferred = app.getSharedPreferences(app.preferencesName, 0).getString("active", null)
                // The list reads only manifests; a notebook that then fails to open is skipped for the next one.
                val candidates = list.filter { it.error == null && it.availableOffline }.sortedByDescending { it.id == preferred }
                list to if (app.restoreSessionOnStartup) candidates.asSequence().mapNotNull { runCatching { prepareRecovery(library.readSession(it.id)) }.getOrNull() }.firstOrNull() else null
            }
            app.main.post {
                loaded = true; busy = false; warning = migrationWarning
                result.onSuccess { (list, note) ->
                    preferences.edit().putBoolean("library_initialized", true).apply()
                    entries = list; note?.let { activate(it) }; status = "Ready"
                    if (app.automaticSafetyCopies) app.safetyCopies.requestAll(list)
                }
                    .onFailure { status = "Cannot open library. Original files preserved: ${it.message}" }
                notifyChanged()
            }
        }
    }
    fun listen(listener: () -> Unit) { listeners.add(listener); listener() }
    fun unlisten(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() = listeners.toList().forEach { it() }
    private val preferences get() = app.getSharedPreferences(app.preferencesName, 0)
    /** Last slow event of each kind only. No notebook identity/content; optional evidence must never delay completion. */
    private fun keepSlowTiming(kind: String, totalMs: Long, timing: String) {
        if (totalMs < 1_000) return
        runCatching {
            preferences.edit().putLong("slow_${kind}_at", System.currentTimeMillis())
                .putString("slow_${kind}_timing", timing).apply()
        }
    }

    /** Opens [notebook] at the page it was last left on (P028), or its first page when that page is gone. */
    private fun activate(prepared: DocumentSession, keepHistory:Boolean=false) {
        val notebook = prepared.notebook
        if(!keepHistory){backLocations.clear(); forwardLocations.clear()}
        val remembered = preferences.getString(pageKey(notebook.id), null)
        session = prepared
        val recovery=synchronized(preparedJournals) { preparedJournals.remove(prepared) }
        journal=recovery?.journal
        if(recovery!=null) {
            if(recovery.unavailable)unavailableRecoveryIds+=notebook.id else unavailableRecoveryIds-=notebook.id
        }
        recoveryCheckpoint = null
        if(recoveryUnavailable) warning="Recovery data is unavailable. This is the last saved copy, opened read-only. Unsaved recovery data has been preserved."
        else journal?.warning?.let { warning = it }
        if (journal?.recoveredEdits != 0 && journal != null) app.main.post {
            if (session === prepared && !busy && !readOnly) save()
        }
        userReadOnly = preferences.getBoolean("readOnly:${notebook.id}",false)
        selectedPageId = notebook.pages.firstOrNull { it.id == remembered }?.id ?: notebook.pages.first().id
        if (owner == null) preferences.edit().putString("active", notebook.id).apply()
        // B24 (P140): compress the pages once in the background, so even the first save copies the unchanged ones.
        if (!readOnly) app.io.execute { runCatching { NotebookPackage.prime(notebook) } }
    }
    private fun pageKey(id: String) = "page:$id"
    private fun rememberPage() {
        val id = session?.notebook?.id ?: return
        val page = selectedPageId ?: return
        if (preferences.getString(pageKey(id), null) != page) preferences.edit().putString(pageKey(id), page).apply()
    }
    /** [opened]: nothing was written, so the entry keeps the file's time (the list read it). */
    private fun updateEntry(notebook: Notebook, opened: Boolean = false) {
        val modified = entries.firstOrNull { it.id == notebook.id }?.modified?.takeIf { opened } ?: System.currentTimeMillis()
        // B23 (P137): a save that keeps the title keeps the order, so the library is not sorted again after every stroke.
        val index = entries.indexOfFirst { it.id == notebook.id }
        if (index >= 0 && entries[index].title == notebook.title) {
            entries = entries.toMutableList().also { it[index] = LibraryEntry(notebook.id, notebook.title, notebook.pages.size, modified = modified) }
            return
        }
        entries = (entries.filterNot { it.id == notebook.id } + LibraryEntry(notebook.id, notebook.title, notebook.pages.size, modified = modified)).sortedWith(compareBy({ it.title.lowercase(java.util.Locale.ROOT) }, { it.id }))
    }
    fun setFileBusy(value: Boolean) { fileBusy = value; notifyChanged() }
    private val legacyInputOwner=Any()
    private val inputOwners=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
    fun setInputBusy(value:Boolean)=setInputBusy(legacyInputOwner,value)
    internal fun setInputBusy(owner:Any,value:Boolean) {
        if(value)inputOwners.add(owner)else inputOwners.remove(owner)
        val next=inputOwners.isNotEmpty()
        if(inputBusy!=next){inputBusy=next;notifyChanged()}
    }
    fun selectPage(id: String, recordHistory: Boolean = true) {
        require(session?.notebook?.pages?.any { it.id == id } == true) { "Page not found" }
        if (selectedPageId == id) return
        if (recordHistory) {
            pushLocation(backLocations, location())
            forwardLocations.clear()
        }
        selectedPageId = id; rememberPage(); notifyChanged()
    }
    fun edit(operation: (DocumentSession) -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy || readOnly) return
        val current = session ?: return
        val oldPageIndex = current.notebook.pages.indexOfFirst { it.id == selectedPageId }.coerceAtLeast(0)
        if (operation(current)) {
            if (current.notebook.pages.none { it.id == selectedPageId }) {
                selectedPageId = current.notebook.pages[minOf(oldPageIndex, current.notebook.pages.lastIndex)].id
                rememberPage()
            }
            save()
        }
    }
    fun capturePdfInsertTarget(): PdfInsertTarget? = if (readOnly || !canChangeNotebook) null
        else session?.notebook?.let { note -> selectedPageId?.let { PdfInsertTarget(note, it) } }
    fun canInsertPdf(target: PdfInsertTarget): Boolean = !readOnly && canChangeNotebook &&
        session?.notebook === target.notebook && selectedPageId == target.pageId && target.notebook.pages.any { it.id == target.pageId }
    fun insertPdfPages(target: PdfInsertTarget, source: Notebook): List<String> {
        if (!canInsertPdf(target)) return emptyList()
        var inserted = emptyList<String>()
        edit { inserted = it.insertPdfPages(source, target.pageId); inserted.isNotEmpty() }
        inserted.firstOrNull()?.let { selectPage(it) }
        return inserted
    }
    fun captureImageInsertTarget(): ImageInsertTarget? = if (readOnly || !canChangeNotebook) null
        else session?.notebook?.let { note -> selectedPageId?.let { ImageInsertTarget(note, it) } }
    fun canInsertImage(target: ImageInsertTarget): Boolean = !readOnly && canChangeNotebook &&
        session?.notebook === target.notebook && selectedPageId == target.pageId && target.notebook.pages.any { it.id == target.pageId }
    fun insertImagePages(target: ImageInsertTarget, source: Notebook): List<String> {
        if (!canInsertImage(target)) return emptyList()
        var inserted = emptyList<String>()
        edit { inserted = it.insertImagePages(source, target.pageId); inserted.isNotEmpty() }
        inserted.firstOrNull()?.let { selectPage(it) }
        return inserted
    }
    /** Same immutable target guard as background insertion; fitting and native limits precede history mutation. */
    fun insertPhoto(target: ImageInsertTarget, photo: ImageSupport.ImportedPhoto, at: Point? = null): String? {
        if (!canInsertImage(target)) return null
        val page = target.notebook.pages.first { it.id == target.pageId }
        // B35 (P198): a photo pasted at a spot keeps its fitted size, centred there and kept on the page.
        val placement = photo.placement(page).let { p -> if (at == null) p else pasteOrigin(at, p.width, p.height, page.width, page.height).let { p.copy(x = it.x, y = it.y) } }
        var inserted: String? = null
        edit { inserted = it.insertImageObject(page.id, photo.asset, placement, photo.orientation); true }
        return inserted
    }
    /** Attached files use the same target guard; core limits reject before history changes. */
    fun insertAttachment(target: ImageInsertTarget, asset: AttachmentAsset, name: String?, mimeType: String?): String? {
        if (!canInsertImage(target)) return null
        val page = target.notebook.pages.first { it.id == target.pageId }
        val placement = FileAttachment.cardPlacement(page.width, page.height, page.attachments.size)
        var inserted: String? = null
        edit { inserted = it.insertAttachment(page.id, asset, FileAttachment.cleanName(name), FileAttachment.cleanMimeType(mimeType), placement); true }
        return inserted
    }
    /** Recording keeps a stable notebook ID while normal writing changes immutable notebook snapshots. */
    internal fun insertRecordedClip(notebookId:String,pageId:String,asset:AttachmentAsset,name:String,clipId:String):Boolean {
        if(!canCommitRecording || session?.notebook?.id!=notebookId)return false
        val note=session!!.notebook
        if(note.pages.any {p->p.attachments.any {it.assetId==asset.id && it.name==name}})return true
        val page=note.pages.firstOrNull {it.id==pageId} ?: currentPage ?: return false
        var inserted:String?=null
        edit {inserted=it.insertAttachment(page.id,asset,name,"audio/mp4",FileAttachment.cardPlacement(page.width,page.height,page.attachments.size),id=clipId);inserted!=null}
        return inserted!=null
    }
    fun removeAttachment(pageId: String, id: String): Boolean {
        var removed = false
        edit { removed = it.removeAttachments(pageId, setOf(id)); removed }
        return removed
    }
    /** Notebooks a page can be copied to or merged from: readable, other than the open one. */
    fun otherNotebooks(): List<LibraryEntry> = entries.filter { it.error == null && it.availableOffline && it.id != session?.notebook?.id && !heldByPeer(it.id) }
    /**
     * P099 (F070): writes a copy of page [pageId] at the end of notebook [targetId]. With [move], the page then leaves the
     * open notebook as one undoable step; the other notebook keeps its copy. [outcome] gets a message on the main thread.
     */
    fun transferPage(expected: Notebook, pageId: String, targetId: String, move: Boolean, outcome: (String) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session?.notebook !== expected || selectedPageId != pageId || !canChangeNotebook) return false
        if (otherNotebooks().none { it.id == targetId } || isNotebookReadOnly(targetId) || (move && (readOnly || expected.pages.size < 2))) return false
        busy = true; status = "Copying page…"; notifyChanged()
        app.io.execute {
            val result = runCatching {
                val target = DocumentSession(library.read(targetId)).apply { appendPages(expected, listOf(pageId)) }.notebook
                library.write(target)
                target.title to library.entries()
            }
            app.main.post {
                busy = false; status = "Saved locally"
                result.onSuccess { (title, list) ->
                    entries = list
                    if (!move) outcome("Copied to the end of “$title”")
                    else if (editCurrentPage(expected, pageId) { it.deletePage(pageId) }) outcome("Moved to the end of “$title”")
                    else outcome("Copied to “$title”, but this page changed, so it was not removed here")
                }.onFailure { outcome("Could not copy the page: ${it.message}") }
                notifyChanged()
            }
        }
        return true
    }
    /** P099 (F070): appends a copy of every page of notebook [sourceId] to the open notebook as one undoable step; the source is unchanged. */
    fun mergeNotebook(expected: Notebook, sourceId: String, outcome: (String) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session?.notebook !== expected || readOnly || !canChangeNotebook || otherNotebooks().none { it.id == sourceId }) return false
        busy = true; status = "Opening notebook…"; notifyChanged()
        app.io.execute {
            val result = runCatching { library.read(sourceId) }
            app.main.post {
                busy = false; status = "Saved locally"
                result.onSuccess { source ->
                    var added = emptyList<String>(); var error: Throwable? = null
                    if (session?.notebook === expected) edit { runCatching { it.appendPages(source) }.onSuccess { ids -> added = ids }.onFailure { error = it }; added.isNotEmpty() }
                    when {
                        added.isNotEmpty() -> { selectPage(added.first()); outcome("Added ${added.size} ${if (added.size == 1) "page" else "pages"} from “${source.title}”") }
                        error != null -> outcome("Could not merge: ${error?.message}")
                        else -> outcome("The notebook changed; merge again")
                    }
                }.onFailure { outcome("Could not open that notebook: ${it.message}") }
                notifyChanged()
            }
        }
        return true
    }
    fun movePage(expected: Notebook, pageId: String, targetIndex: Int): Boolean =
        editCurrentPage(expected, pageId) { it.movePage(pageId, targetIndex) }
    fun deletePage(expected: Notebook, pageId: String): Boolean =
        editCurrentPage(expected, pageId) { it.deletePage(pageId) }
    /** P178 (G3): page-strip bulk actions on chosen pages; the same snapshot and busy/read-only guards, without a current-page match. */
    fun deletePages(expected: Notebook, pageIds: Set<String>): Boolean = editPages(expected, pageIds) { it.deletePages(pageIds) }
    fun movePages(expected: Notebook, pageIds: Set<String>, targetIndex: Int): Boolean = editPages(expected, pageIds) { it.movePages(pageIds, targetIndex) }
    private fun editPages(expected: Notebook, pageIds: Set<String>, operation: (DocumentSession) -> Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = session ?: return false
        if (current.notebook !== expected || pageIds.isEmpty() || readOnly || busy || fileBusy || inputBusy) return false
        edit(operation)
        return current.notebook !== expected
    }
    /** P100: one layer change on the selected page; false when the page changed meanwhile or nothing changed. */
    fun editLayers(expected: Notebook, pageId: String, operation: (DocumentSession) -> Boolean): Boolean =
        editCurrentPage(expected, pageId, operation)
    /** P101: lock or unlock objects on the open page. */
    fun editLocks(expected: Notebook, pageId: String, operation: (DocumentSession) -> Boolean): Boolean =
        editCurrentPage(expected, pageId, operation)
    fun clearPageInk(expected: Notebook, pageId: String): Boolean =
        editCurrentPage(expected, pageId) { it.clearPageInk(pageId) }
    fun setBookmark(expected: Notebook, pageId: String, name: String?, level: Int? = null): Boolean {
        val page = expected.pages.firstOrNull { it.id == pageId } ?: return false
        if (page.bookmark == name?.trim() && (name == null || level == null || level == page.outlineLevel)) return session?.notebook === expected && selectedPageId == pageId && !readOnly && !busy && !fileBusy && !inputBusy
        return editCurrentPage(expected, pageId) { it.setBookmark(pageId, name, level) }
    }
    fun resizePage(expected: Notebook, pageId: String, width: Float, height: Float): Boolean {
        val page = expected.pages.firstOrNull { it.id == pageId } ?: return false
        if (page.hasBackground) return false
        if (page.width == width && page.height == height) return session?.notebook === expected && selectedPageId == pageId && !readOnly && !busy && !fileBusy && !inputBusy
        return editCurrentPage(expected, pageId) { it.resizePage(pageId, width, height) }
    }
    /** P546 (F063): more room on the current blank page in any direction, as one Undo step. */
    fun addPageRoom(expected: Notebook, pageId: String, direction: dev.ownednotes.core.PageRoom.Direction, pages: Float): Boolean {
        val page = expected.pages.firstOrNull { it.id == pageId } ?: return false
        if (page.hasBackground || dev.ownednotes.core.PageRoom.grown(page, direction, pages) == null) return false
        return editCurrentPage(expected, pageId) { it.addPageRoom(pageId, direction, pages) }
    }
    fun setPaper(expected: Notebook, pageId: String, paper: dev.ownednotes.core.PaperStyle): Boolean {
        val page = expected.pages.firstOrNull { it.id == pageId } ?: return false
        if (page.hasBackground) return false
        if (page.paper == paper) return session?.notebook === expected && selectedPageId == pageId && !readOnly && !busy && !fileBusy && !inputBusy
        return editCurrentPage(expected, pageId) { it.setPaper(pageId, paper) }
    }
    /** Dialog actions must still refer to the same immutable snapshot and selected page. */
    private fun editCurrentPage(expected: Notebook, pageId: String, operation: (DocumentSession) -> Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = session ?: return false
        if (current.notebook !== expected || selectedPageId != pageId || readOnly || busy || fileBusy || inputBusy) return false
        edit(operation)
        return current.notebook !== expected
    }
    fun save() {
        if (busy || userReadOnly || recoveryUnavailable) return
        val snapshot = session?.notebook ?: return
        val target = ++revision
        val recovery = runCatching { journal?.append(snapshot) }
        if (recoveryCheckpoint != null || recovery.isFailure || recovery.getOrNull() == RecoveryJournal.Append.NEEDS_ASSET_CHECKPOINT ||
            recovery.getOrNull() == RecoveryJournal.Append.NEEDS_CHECKPOINT) {
            // New originals, and an edit too large for the recovery log (P614), are made durable by the existing atomic save before further input.
            // A journal IO failure also blocks more edits until that save succeeds.
            recoveryCheckpoint = target
            recovery.exceptionOrNull()?.let { warning = "Recovery log unavailable; saving before more edits: ${it.message}" }
        }
        // Completed journal edits get independent copies even when a provider is delaying the native-save queue.
        if(recovery.getOrNull()==RecoveryJournal.Append.RECORDED) app.safetyCopies.request(snapshot.id)
        status = "Saving…"; updateEntry(snapshot)
        pendingWrite = target to snapshot
        notifyChanged(); writeNext()
    }
    /**
     * B33 (P196): every save rewrites the whole package, photos and PDFs included (a 6 MB photo meant 270 MB written for
     * 48 strokes). A save that follows straight on from another waits until about [SAVE_BYTES_PER_MS] of its photos,
     * PDFs and files would have been written, or [SAVE_SPACING] times as long as the last save took, at most
     * [SAVE_SPACING_MAX_MS]; a light notebook barely waits. The first save after a pause starts at once, and [flushSave]
     * (leaving the app) ends the wait.
     */
    private var spacingSave = false
    // A flush may arrive while a writer is running. Drain through that revision before spacing again.
    private var flushRevision: Long? = null
    private val spacedSave = Runnable { spacingSave = false; writeNext() }
    // P457: a failed save is written again (at once when leaving the book), so one IO error cannot keep the Library shut.
    private var failedSaves = 0
    private val retrySave = Runnable { retryFailedSave() }
    private fun retryFailedSave() {
        if (writing || pendingWrite != null || spacingSave || savedRevision == revision || busy || userReadOnly) return
        val snapshot = session?.notebook ?: return
        pendingWrite = revision to snapshot; status = "Saving…"; notifyChanged(); writeNext()
    }
    fun flushSave() {
        if (savedRevision != revision || writing || pendingWrite != null || recoveryCheckpoint != null)
            flushRevision = maxOf(flushRevision ?: revision, revision)
        if (recoveryCheckpoint != null && !writing && pendingWrite == null) { save(); return }
        if (!writing && pendingWrite == null && !spacingSave && savedRevision != revision) { app.main.removeCallbacks(retrySave); retryFailedSave(); return }
        if (!spacingSave) return
        app.main.removeCallbacks(spacedSave); spacingSave = false; writeNext()
    }
    private fun writeNext() {
        if (writing || spacingSave) return
        val (target, snapshot) = pendingWrite ?: return
        pendingWrite = null; writing = true
        app.saving.execute {
            val started = SystemClock.uptimeMillis()
            val result = runCatching { NotebookReadGate.access(File(app.storageRoot, "notebooks"), snapshot.id) { library.write(snapshot) } }
            val took = SystemClock.uptimeMillis() - started
            app.main.post {
                writing = false
                if (result.isSuccess) {
                    savedRevision = maxOf(savedRevision, target)
                    if (flushRevision?.let { savedRevision >= it } == true) flushRevision = null
                    val retired = runCatching {
                        if (recoveryCheckpoint == target) journal?.assetCheckpoint(snapshot) else journal?.acknowledge(snapshot)
                    }
                    if (retired.isSuccess && recoveryCheckpoint == target) recoveryCheckpoint = null
                    retired.exceptionOrNull()?.let { warning = "Notebook saved; recovery log retained: ${it.message}" }
                }
                status = when {
                    target == revision && result.isFailure -> "Save failed: ${result.exceptionOrNull()?.message}"
                    savedRevision == revision -> "Saved locally"
                    else -> "Saving…"
                }
                if (pendingWrite != null && result.isSuccess && flushRevision == null) {
                    val heavy = snapshot.assets.sumOf { it.size.toLong() } + snapshot.imageAssets.sumOf { it.size.toLong() } + snapshot.attachmentAssets.sumOf { it.size.toLong() }
                    spacingSave = true; app.main.postDelayed(spacedSave, maxOf(took * SAVE_SPACING, heavy / SAVE_BYTES_PER_MS).coerceAtMost(SAVE_SPACING_MAX_MS))
                }
                if (result.isSuccess) failedSaves = 0
                else if (pendingWrite == null) { failedSaves++; app.main.removeCallbacks(retrySave); app.main.postDelayed(retrySave, (1000L shl minOf(failedSaves, 5)).coerceAtMost(SAVE_RETRY_MAX_MS)) }
                finishSaveWaiters(result.exceptionOrNull()?.takeIf { pendingWrite == null })
                notifyChanged(); writeNext()
            }
        }
    }
    private fun localEntry(id: String) = entries.firstOrNull { it.id == id && it.id != trashingId && it.error == null && it.availableOffline }
    fun openNotebook(id: String): Boolean {
        if (localEntry(id) == null) return false
        var parsed: DocumentSession? = null
        return changeNotebook({ library.readSession(id).also { parsed=it }.notebook },prepare={ notebook ->
            requireNotNull(parsed).also { check(it.notebook === notebook) }
        },readOnlyId=id)
    }

    /** P214: Library-only confirmation may explicitly close the current saved notebook.
     * Provider access and byte verification share the save queue. No local bytes are removed on refusal.
     * [folder] is injectable for isolated tests; its argument always identifies the original source tree.
     */
    fun releaseNotebookStorage(id: String, closeCurrent: Boolean = false,
        source: String? = LibrarySync.sourceIdentity(app),
        folder: (String) -> FolderSync.Folder = { LibrarySync.originalSource(app, it) },
        sourceAccess: (String) -> Boolean = { LibrarySync.sourceReadable(app, it) },
        wifi: () -> Boolean = { LibrarySync.onWifi(app) }, cancelled: () -> Boolean = { false },
        done: (Result<Unit>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val entry = localEntry(id) ?: return false
        val current = session
        if (!canChangeNotebook || isNotebookReadOnly(id) || heldInSideBySide(id) || source.isNullOrBlank() || source.length > 4096 ||
            (current?.notebook?.id == id && !closeCurrent)) return false
        if (!storageNetworkAllowed(wifi)) return false
        val busyIds = setOfNotNull(current?.notebook?.id).let { if (closeCurrent) it - id else it }
        val interrupted = storageCancellation(wifi, cancelled)
        busy = true; status = "Verifying folder copy…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching {
                if (interrupted()) throw java.io.InterruptedIOException("Transfer cancelled")
                val checkAccess = {
                    if (!sourceAccess(source)) throw java.io.IOException("Persistent original-folder read access is required")
                }
                checkAccess() // Refuse before constructing/querying the provider.
                val remote = folder(source)
                val release = { OfflineNotebooks(File(app.storageRoot, "notebooks")).releaseWithAccessCheck(id, source, remote, busyIds,
                    checkAccess = checkAccess, cancelled = interrupted) }
                if (remote is java.io.Closeable) remote.use { release() } else release()
                Unit
            }
            val list = runCatching { library.entries() }.getOrNull()
            app.main.post {
                if (result.isSuccess && current?.notebook?.id == id && session === current) {
                    session = null; selectedPageId = null; userReadOnly = false
                    backLocations.clear(); forwardLocations.clear()
                    preferences.edit().remove("active").apply()
                }
                entries = list ?: if (result.isSuccess) entries.map {
                    if (it.id == id) entry.copy(availableOffline = false, offlineSource = source) else it
                } else entries
                busy = false; status = if (session == null) "Ready" else "Saved locally"
                libraryMessage = if (result.isSuccess) "Local storage freed. Download this notebook to write again."
                    else "Local storage was kept. Check access to the original folder, its saved copy and connection, then try again."
                notifyChanged(); done(result)
            }
        }
        return true
    }

    /** Metadata only: a released notebook never follows a changed global sync destination. */
    fun notebookSource(id: String): String? {
        val entry = entries.firstOrNull { it.id == id && it.error == null } ?: return null
        return if (entry.availableOffline) LibrarySync.sourceIdentity(app) else entry.offlineSource
    }

    /** Read-only physical file sizes for a storage sheet; app IO only, no provider or package-content read. */
    fun inspectNotebookDiskUsage(id: String, done: (Result<NotebookLibrary.DiskUsage>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!canChangeNotebook) return false
        val entry = entries.firstOrNull { it.id == id } ?: return false
        app.io.execute {
            val result = runCatching { library.diskUsage(id) }
            app.main.post {
                done(if (canChangeNotebook && entries.firstOrNull { it.id == id } == entry) result
                    else Result.failure(IllegalStateException("Notebook changed or became busy during storage inspection")))
            }
        }
        return true
    }

    /** Snapshot retained bytes on the same serial IO owner used by sync/history writes. */
    fun prepareHistoryCleanup(id: String, done: (Result<NotebookHistory.Snapshot>) -> Unit): Boolean {
        if (!canChangeNotebook || isNotebookReadOnly(id) || heldByPeer(id)) return false
        val entry = entries.firstOrNull { it.id == id && it.error == null } ?: return false
        busy = true; notifyChanged()
        app.io.execute {
            val result = runCatching { NotebookHistory(File(app.storageRoot, "notebooks")).snapshot(id) }
            app.main.post {
                busy = false; notifyChanged()
                done(if (entries.firstOrNull { it.id == id } == entry && !isNotebookReadOnly(id)) result
                    else Result.failure(IllegalStateException("Notebook changed during history inspection")))
            }
        }
        return true
    }
    fun removeEarlierVersions(snapshot: NotebookHistory.Snapshot, done: (Result<NotebookHistory.Purge>) -> Unit): Boolean {
        if (!canChangeNotebook || isNotebookReadOnly(snapshot.notebookId) || heldByPeer(snapshot.notebookId) || snapshot.items.isEmpty() ||
            entries.none { it.id == snapshot.notebookId && it.error == null }) return false
        val confirmed = snapshot.copy(items = snapshot.items.toMap())
        busy = true; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching { NotebookHistory(File(app.storageRoot, "notebooks")).purge(confirmed) }
            app.main.post {
                busy = false
                libraryMessage = result.fold({ "Removed ${it.deleted} earlier versions from this device." +
                    if (it.skipped > 0) " Kept or skipped ${it.skipped} new, changed or unavailable versions. Review storage again." else "" },
                    { "Could not finish removing earlier versions. Review storage again. ${it.message}" })
                notifyChanged(); done(result)
            }
        }
        return true
    }

    /** Read-only grant inspection for a visible storage sheet; no provider content is read. */
    fun inspectNotebookSourceAccess(id: String, done: (Result<Boolean>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!loaded) return false
        val source = notebookSource(id) ?: return false
        app.io.execute {
            val result = runCatching { LibrarySync.sourceReadable(app, source) }
            app.main.post {
                done(if (notebookSource(id) == source) result else Result.failure(IllegalStateException("Notebook source changed")))
            }
        }
        return true
    }

    /** Reselect the SAME tree, retaining recovery access without changing sync preferences or notebook content.
     * The picker supplies transient access; persist only after validating the expected notebook/source.
     */
    fun restoreNotebookSourceAccess(id: String, expected: String, selected: String?,
        takeAccess: (String) -> Unit = { FolderAccess.takeForRecovery(app, it) },
        sourceAccess: (String) -> Boolean = { FolderAccess.hasRecoveryAccess(app, it) },
        done: (Result<Unit>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!canChangeNotebook || selected == null) return false
        val entry = entries.firstOrNull { it.id == id && it.error == null } ?: return false
        if (selected != expected || notebookSource(id) != expected || !FolderAccess.isTreeSource(expected)) {
            libraryMessage = "Choose this notebook's original folder. Its saved source was not changed."
            notifyChanged(); return false
        }
        busy = true; status = "Restoring folder access…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching {
                val stillExpected = if (entry.availableOffline) {
                    library.contains(id) && LibrarySync.folderUri(app)?.toString() == expected
                } else OfflineNotebooks(File(app.storageRoot, "notebooks")).record(id)?.source == expected
                check(stillExpected) { "The notebook source changed while choosing a folder" }
                takeAccess(expected)
                check(sourceAccess(expected)) { "Required folder permission could not be retained" }
            }
            app.main.post {
                busy = false; status = if (session == null) "Ready" else "Saved locally"
                libraryMessage = if (result.isSuccess) "Access to the original folder restored."
                    else "Folder access was not restored. The notebook was kept. Choose its original folder and try again."
                notifyChanged(); done(result)
            }
        }
        return true
    }

    /** Download into private pending storage, validate, then commit without activating the editor.
     * A source change in global sync preferences never redirects this notebook's download.
     */
    fun downloadNotebook(id: String,
        folder: (String) -> FolderSync.Folder = { LibrarySync.originalSource(app, it) },
        wifi: () -> Boolean = { LibrarySync.onWifi(app) }, cancelled: () -> Boolean = { false },
        done: (Result<Unit>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val entry = entries.firstOrNull { it.id == id && it.error == null && !it.availableOffline } ?: return false
        if (!canChangeNotebook || !storageNetworkAllowed(wifi)) return false
        val busyIds = openDocumentIds()
        val interrupted = storageCancellation(wifi, cancelled)
        busy = true; status = "Downloading notebook…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching {
                val storage = OfflineNotebooks(File(app.storageRoot, "notebooks"),app.attachmentDirectory)
                val record = storage.record(id)
                if (record != null) {
                    if (interrupted()) throw java.io.InterruptedIOException("Transfer cancelled")
                    val remote = folder(record.source)
                    val restore = { storage.restore(id, record.source, remote, busyIds, interrupted) }
                    if (remote is java.io.Closeable) remote.use { restore() } else restore()
                }
                // Existing local bytes can win an interrupted previous download.
                check(library.contains(id)) { "The notebook has not been downloaded" }
                Unit
            }
            val list = runCatching { library.entries() }.getOrNull()
            app.main.post {
                entries = list ?: if (result.isSuccess) entries.map {
                    if (it.id == id) entry.copy(availableOffline = true, offlineSource = null) else it
                } else entries
                busy = false; status = if (session == null) "Ready" else "Saved locally"
                libraryMessage = if (result.isSuccess) "Notebook downloaded and available offline."
                    else "Download did not finish. The library record was kept. Check access to its original folder and try again."
                if (result.isSuccess) entry.offlineSource?.let { source ->
                    FolderAccess.releaseIfUnused(app, android.net.Uri.parse(source))
                }
                notifyChanged(); done(result)
            }
        }
        return true
    }

    private fun storageNetworkAllowed(wifi: () -> Boolean): Boolean {
        if (!LibrarySync.wifiOnly(app) || runCatching(wifi).getOrDefault(false)) return true
        libraryMessage = "Connect to Wi-Fi before transferring this notebook."; notifyChanged()
        return false
    }
    private fun storageCancellation(wifi: () -> Boolean, cancelled: () -> Boolean): () -> Boolean {
        var checked = -1L; var allowed = true
        return {
            if (cancelled()) true else {
                val now = SystemClock.elapsedRealtime()
                if (checked < 0L || now - checked >= 1000L) {
                    allowed = !LibrarySync.wifiOnly(app) || runCatching(wifi).getOrDefault(false)
                    checked = now
                }
                !allowed
            }
        }
    }
    /**
     * P105 (F138): opens the "Quick notes" notebook (made on first use) on a blank page at its end, ready to write.
     * A blank last page is reused; otherwise one undoable page is added.
     */
    fun openQuickNote(opened: () -> Unit = {}): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!canChangeNotebook) return false
        fun blank(page: Page) = page.strokes.isEmpty() && page.images.isEmpty() && page.textBoxes.isEmpty() && page.attachments.isEmpty() && !page.hasBackground
        fun toBlankPage() {
            val pages = session?.notebook?.pages ?: return
            if (blank(pages.last())) { selectPage(pages.last().id); return }
            var id: String? = null
            edit { id = it.addPage(pages.last().id); true }
            id?.let { selectPage(it) }
        }
        val candidate = entries.firstOrNull { it.error == null && it.title == QUICK_NOTES && !isNotebookReadOnly(it.id) }
        if (candidate?.availableOffline == false) {
            libraryMessage = "Download Quick notes from the Library before writing."; notifyChanged(); return false
        }
        val existing = candidate?.id
        if (existing != null && heldByPeer(existing)) { libraryMessage = "Quick notes is already open beside this notebook."; notifyChanged(); return false }
        if (existing != null && existing == session?.notebook?.id) { toBlankPage(); opened(); return true }
        return changeNotebook({ existing?.let { library.read(it) } ?: Notebook(title = QUICK_NOTES).also { library.write(it) } }) { toBlankPage(); opened() }
    }
    fun createNotebook(title: String): Boolean {
        require(title.trim().length in 1..500) { "Title must have 1–500 characters" }
        return changeNotebook({ Notebook(title = title.trim()).also { library.write(it) } })
    }
    fun importNotebook(notebook: Notebook): Boolean {
        notebook.validate()
        return changeNotebook({ library.importCopy(notebook) })
    }
    /** P123 (F196): what a batch import did; [duplicates] and [failed] name the files. */
    class BatchImport(val imported: List<Notebook>, val duplicates: List<String>, val failed: List<String>,
                      val reports: List<Pair<String, ImportReport>> = emptyList()) {
        val message: String get() = buildList {
            add(if (imported.size == 1) "Imported 1 notebook" else "Imported ${imported.size} notebooks")
            if (duplicates.isNotEmpty()) add("skipped ${duplicates.size} already in the library: ${duplicates.joinToString()}")
            if (failed.isNotEmpty()) add("${failed.size} could not be imported: ${failed.joinToString()}")
        }.joinToString("; ")
    }
    /**
     * P123 (F196): imports each file in turn, skipping one whose notebook is already in the library ([ImportFingerprint]).
     * Each import is written before the next file is checked, so a file picked twice is caught too. The last notebook
     * imported opens; [done] gets the outcome. Refused (false) unless the notebook can change right now.
     */
    fun importNotebooks(files: List<Pair<String, () -> Notebook>>, done: (BatchImport) -> Unit = {}): Boolean {
        return importNotebookFiles(files.map { (name, read) -> name to { NotebookImport.Result(read()) } }, done = done)
    }

    fun importNotebookFiles(files: List<Pair<String, () -> NotebookImport.Result>>, openImported: Boolean = true, done: (BatchImport) -> Unit = {}): Boolean {
        if (!canChangeNotebook || files.isEmpty()) return false
        busy = true; status = "Importing…"; notifyChanged()
        app.io.execute {
            val imported = mutableListOf<Notebook>(); val duplicates = mutableListOf<String>(); val failed = mutableListOf<String>()
            val reports = mutableListOf<Pair<String, ImportReport>>()
            for ((name, read) in files) {
                runCatching {
                    val parsed = read()
                    val incoming = parsed.notebook.also { it.validate() }
                    if (ImportFingerprint.findIn(library, incoming) != null) duplicates += name else {
                        imported += library.importCopy(incoming)
                        parsed.report?.takeUnless { it.complete }?.let { reports += name to it }
                    }
                }.onFailure { failed += "$name (${it.message ?: "unreadable"})" }
            }
            val list = runCatching { library.entries() }.getOrNull()
            // Validation walks all ink; prepare before publishing on the UI thread.
            val prepared = if (openImported) imported.lastOrNull()?.let { notebook ->
                runCatching { prepareRecovery(DocumentSession(notebook)) }.onFailure { failed += "${notebook.title} (could not prepare: ${it.message})" }.getOrNull()
            } else null
            app.main.post {
                busy = false
                list?.let { entries = it }
                prepared?.let { activate(it) }
                status = "Saved locally"
                notifyChanged()
                done(BatchImport(imported, duplicates, failed, reports))
            }
        }
        return true
    }
    /** Notebooks in the trash (P024); refreshed with [refreshTrash] and after each trash or restore. */
    var trashed: List<LibraryEntry>
        get() = shared.trashed
        private set(value) { shared.trashed = value }
    /** Outcome of the last library action (trash, restore, rename, duplicate, trash listing); never a save state. */
    var libraryMessage: String? = null; private set
    fun dismissLibraryMessage() { if (libraryMessage != null) { libraryMessage = null; notifyChanged() } }
    fun refreshTrash() {
        if (!loaded || busy) return
        busy = true; notifyChanged()
        app.io.execute {
            val result = runCatching { library.trashEntries() }
            app.main.post { busy = false; result.onSuccess { trashed = it }.onFailure { libraryMessage = "Cannot read trash: ${it.message}" }; notifyChanged() }
        }
    }
    /** Captures exactly the bytes to be confirmed; never reads/writes active notebook contents. */
    fun prepareEmptyTrash(done:(Result<Map<String,String>>)->Unit):Boolean {
        if(!canChangeNotebook) return false
        busy=true;notifyChanged()
        app.io.execute {
            val result=runCatching { library.trashSnapshot() }
            app.main.post { busy=false;notifyChanged();done(result) }
        }
        return true
    }
    fun emptyTrash(confirmed:Map<String,String>):Boolean {
        if(!canChangeNotebook || confirmed.isEmpty()) return false
        val snapshot=confirmed.toMap()
        busy=true;libraryMessage=null;notifyChanged()
        app.io.execute {
            val result=runCatching { library.emptyTrash(snapshot) }
            val list=runCatching { library.trashEntries() }
            app.main.post {
                busy=false;list.onSuccess { trashed=it }
                libraryMessage=result.fold({ "Permanently deleted ${it.deleted} ${if(it.deleted==1) "notebook" else "notebooks"} from Trash."+
                    if(it.kept>0) " Kept ${it.kept} changed or unavailable items; review Trash again." else "" },
                    { "Could not finish emptying Trash. Remaining items are preserved. ${it.message}" })
                notifyChanged()
            }
        }
        return true
    }
    /** Scalar, content-free diagnostics for the reported slow Move to Trash; no document identity leaves here. */
    internal data class TrashTiming(val queueMs:Long,val moveMs:Long,val refreshMs:Long,val deliveryMs:Long,val totalMs:Long,val moved:Boolean)
    internal var onTrashTiming:((TrashTiming)->Unit)?=null
    /** Moves unchanged bytes to recoverable Trash, detaching the active book without opening a replacement. */
    fun trashNotebook(id: String): Boolean {
        if (heldInSideBySide(id)) { libraryMessage = "Close side-by-side view before moving this notebook to Trash."; notifyChanged(); return false }
        if (!canChangeNotebook || isNotebookReadOnly(id) || entries.none { it.id == id && it.availableOffline }) return false
        val requested=SystemClock.uptimeMillis()
        // Reserve before work is queued; every in-flight cycle keeps this ID through its completion.
        openingIds+=id;syncReservations.forEach {it+=id}
        val current = session
        trashingId = id; status = "Moving to Trash…"; libraryMessage = null; notifyChanged()
        val enqueued=SystemClock.uptimeMillis()
        app.trashing.execute {
            val started=SystemClock.uptimeMillis()
            var moved=false;var moveMs=0L;var refreshMs=0L
            val result = runCatching {
                val moving=SystemClock.uptimeMillis()
                try {NotebookReadGate.access(File(app.storageRoot,"notebooks"),id){library.trash(id)};moved=true}finally {moveMs=SystemClock.uptimeMillis()-moving}
                val refreshing=SystemClock.uptimeMillis()
                try {library.entries() to library.trashEntries()}finally {refreshMs=SystemClock.uptimeMillis()-refreshing}
            }
            val finished=SystemClock.uptimeMillis()
            app.main.post {
                val delivered=SystemClock.uptimeMillis()
                openingIds-=id
                // Moving an active book is a Library action; never parse an unrelated replacement here.
                // Detach even if a later list refresh fails: a live session must not save this book back.
                if(moved && current?.notebook?.id==id && session===current) {
                    session=null;selectedPageId=null;userReadOnly=false;recoveryCheckpoint=null;journal=null
                    backLocations.clear();forwardLocations.clear()
                    preferences.edit().remove("active").apply()
                }
                trashingId = null
                result.onSuccess { (list, trash) ->
                    entries = list; trashed = trash
                    libraryMessage = "Moved to trash"
                }.onFailure {
                    if(moved)entries=entries.filterNot {entry->entry.id==id}
                    libraryMessage = if(moved) "Moved to Trash; could not refresh the library: ${it.message}"
                        else "Could not move to trash: ${it.message}"
                }
                if(status == "Moving to Trash…") status = if(session==null) "Ready" else "Saved locally"
                notifyChanged()
                val timing=TrashTiming(started-enqueued,moveMs,refreshMs,delivered-finished,SystemClock.uptimeMillis()-requested,moved)
                if(timing.totalMs>=1_000)android.util.Log.i("ScribellaTrash","queue_ms=${timing.queueMs} move_ms=${timing.moveMs} refresh_ms=${timing.refreshMs} delivery_ms=${timing.deliveryMs} total_ms=${timing.totalMs} moved=${timing.moved}")
                keepSlowTiming("trash", timing.totalMs, timing.toString())
                // Diagnostics must never change completion, even when an injected test observer fails.
                runCatching {onTrashTiming?.invoke(timing)}
            }
        }
        return true
    }
    /** Moves a trashed notebook back to the library without opening it. */
    fun restoreNotebook(id: String): Boolean {
        if (!canChangeNotebook || trashed.none { it.id == id }) return false
        busy = true; status = "Opening notebook…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching { library.restore(id); library.entries() to library.trashEntries() }
            app.main.post {
                busy = false
                result.onSuccess { (list, trash) -> entries = list; trashed = trash; libraryMessage = "Restored to the library" }
                    .onFailure { libraryMessage = "Could not restore: ${it.message}" }
                status = "Saved locally"
                notifyChanged()
            }
        }
        return true
    }
    /**
     * P124 (F220): adds the notebooks in the backup [folder] that the library and its trash lack ([LibraryBackup.restore]);
     * none is opened and nothing is replaced. [folder] is built on the save thread. Refused (false) unless the notebook can change.
     */
    fun restoreFromBackup(folder: () -> LibraryBackup.Folder, done: (Result<LibraryBackup.Restore>) -> Unit = {}): Boolean {
        if (!canChangeNotebook || sideBySide) return false
        busy = true; status = "Restoring…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching { LibraryBackup.restore(folder(), library) }
            val list = runCatching { library.entries() }.getOrNull()
            app.main.post {
                busy = false
                list?.let { entries = it }
                libraryMessage = result.fold({ it.message }, { "Restore failed: ${it.message ?: "the backup folder cannot be reached"}" })
                status = "Saved locally"
                notifyChanged()
                done(result)
            }
        }
        return true
    }
    /**
     * Sync and all native writes remain serialized. Explicit read-only opens use a separate executor with live
     * reservations; FolderSync rechecks those reservations under the per-notebook local commit gate.
     * Provider work never holds that gate, so a stalled transfer cannot gate opening saved local work.
     */
    fun syncLibrary(sync: (Set<String>, Map<String, String>) -> FolderSync.Outcome, done: (Result<FolderSync.Outcome>) -> Unit) {
        if (!loaded) { done(Result.failure(IllegalStateException("the library is still loading"))); return }
        val open=java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {addAll(openingIds);addAll(openDocumentIds())}
        syncReservations.add(open)
        val titles = (trashed + entries).associate { it.id to it.title }
        app.io.execute {
            val result = runCatching { app.afterQueuedSaves(); sync(open, titles) }
            val lists = runCatching { library.entries() to library.trashEntries() }.getOrNull()
            app.main.post {
                syncReservations.removeAll {it===open}
                lists?.let { (list, trash) -> entries = list; trashed = trash }
                notifyChanged()
                done(result)
            }
        }
    }
    /** Every notebook a pane holds; sync, downloads and storage release leave these alone. */
    private fun openDocumentIds() = setOfNotNull(session?.notebook?.id, peer?.session?.notebook?.id)
    /**
     * Retitles a notebook (P027). The open notebook is renamed through its normal undoable edit and autosave; any other
     * readable notebook is read, retitled and written atomically in the background. False when refused.
     */
    fun renameNotebook(id: String, title: String): Boolean {
        require(title.trim().length in 1..500) { "Title must have 1–500 characters" }
        val entry = localEntry(id) ?: return false
        if (!canChangeNotebook || isNotebookReadOnly(id) || heldByPeer(id)) return false
        if (session?.notebook?.id == id) {
            if (entry.title == title.trim()) return true
            edit { it.rename(title) }
            libraryMessage = "Renamed to “${title.trim()}”"; notifyChanged()
            return true
        }
        return libraryAction({ library.rename(id, title) }) { "Renamed to “${it.title}”" }
    }
    /** Read settings without opening or activating the book. The returned snapshot guards a later save. */
    fun loadNotebookSettings(id: String, done: (Result<Notebook>) -> Unit): Boolean {
        if (!canChangeNotebook || isNotebookReadOnly(id) || localEntry(id) == null || heldByPeer(id)) return false
        val open = session?.notebook?.takeIf { it.id == id }
        busy = true; notifyChanged()
        app.io.execute {
            val result = runCatching { open ?: library.read(id) }
            app.main.post { busy = false; notifyChanged(); done(result) }
        }
        return true
    }
    /** One undoable edit for the active book, or an atomic background write for a closed library book. */
    fun configureNotebook(expected: Notebook, title: String, pageIds: Set<String>, width: Float? = null,
        height: Float? = null, paper: PaperStyle? = null, replaceBackgrounds: Boolean = false,
        done: (Boolean) -> Unit = {}): Boolean {
        if (!canChangeNotebook || isNotebookReadOnly(expected.id) || localEntry(expected.id) == null || heldByPeer(expected.id)) return false
        if (session?.notebook?.id == expected.id) {
            if (session?.notebook !== expected) return false
            edit { it.configurePages(pageIds, title, width, height, paper, replaceBackgrounds) }
            done(true)
            return true
        }
        busy = true; notifyChanged()
        app.io.execute {
            val result = runCatching {
                check(library.read(expected.id) == expected) { "The notebook changed. Reopen Edit notebook." }
                val changed = DocumentSession(expected).also { it.configurePages(pageIds, title, width, height, paper, replaceBackgrounds) }.notebook
                library.write(changed)
                library.entries()
            }
            app.main.post {
                busy = false
                result.onSuccess { entries = it; libraryMessage = "Notebook updated" }
                    .onFailure { libraryMessage = "Could not edit notebook: ${it.message}" }
                notifyChanged(); done(result.isSuccess)
            }
        }
        return true
    }
    /** P412 (B52): padded PDF pages are made off the main thread; the change applies in one Undo step only if the open
     * notebook is still [expected]. [done] gets null on success, otherwise the reason. */
    fun matchStandardPageShape(expected: Notebook, ids: Set<String>, done: (String?) -> Unit): Boolean {
        if (busy || readOnly || !canChangeNotebook || session?.notebook !== expected) return false
        busy = true; notifyChanged()
        app.io.execute {
            val padded = runCatching { StandardPageShape.padded(expected, ids, app.cacheDir, app.attachmentDirectory) }
            app.main.post {
                busy = false
                val error = padded.fold({ pdfs ->
                    if (session?.notebook !== expected) "The notebook changed. Reopen Page & paper."
                    else runCatching {
                        var changed = false
                        edit { changed = it.matchStandardPageShape(ids, pdfs); changed }
                        if (changed) null else "These pages already have the Standard shape"
                    }.getOrElse { it.message ?: "Pages could not change shape" }
                }, { it.message ?: "Pages could not change shape" })
                notifyChanged(); done(error)
            }
        }
        return true
    }
    /** Writes a copy of a readable notebook with a fresh identity ("<title> copy"); it is not opened. */
    fun duplicateNotebook(id: String): Boolean {
        if (localEntry(id) == null || !canChangeNotebook || heldByPeer(id)) return false
        return libraryAction({ library.duplicate(id) }) { "Duplicated as “${it.title}”" }
    }
    /** F178: a notebook's kept versions, newest first, read on the save thread (where sync keeps them). */
    fun listVersions(id: String, done: (Result<List<NotebookLibrary.Version>>) -> Unit) {
        app.io.execute { val result = runCatching { library.versions(id) }; app.main.post { done(result) } }
    }
    /** F178: writes a kept version as a new notebook titled [title]; it is not opened, and nothing is replaced. */
    fun restoreVersion(id: String, time: Long, title: String): Boolean {
        if (entries.none { it.id == id } || !canChangeNotebook || title.trim().length !in 1..500) return false
        return libraryAction({ library.restoreVersion(id, time, title) }) { "Restored as “${it.title}”" }
    }
    private fun libraryAction(action: () -> Notebook, outcome: (Notebook) -> String): Boolean {
        busy = true; status = "Opening notebook…"; libraryMessage = null; notifyChanged()
        app.io.execute {
            val result = runCatching { action().let { it to library.entries() } }
            app.main.post {
                busy = false
                result.onSuccess { (notebook, list) -> entries = list; libraryMessage = outcome(notebook) }
                    .onFailure { libraryMessage = "Could not change the library: ${it.message}" }
                status = "Saved locally"
                notifyChanged()
            }
        }
        return true
    }
    private fun changeNotebook(load: () -> Notebook, prepare: (Notebook) -> DocumentSession = { DocumentSession(it) }, keepHistory:Boolean=false, readOnlyId:String?=null, opened: () -> Unit = {}): Boolean {
        if (if(readOnlyId == null) !canChangeNotebook else !canOpenSavedNotebook(readOnlyId)) return false
        if (readOnlyId != null && heldByPeer(readOnlyId)) return false
        // Include the initial synchronous screen update in request-to-worker time.
        val requested = SystemClock.elapsedRealtime()
        readOnlyId?.let {id->openingId=id;openingIds+=id;syncReservations.forEach {it+=id}}
        busy = true; status = "Opening notebook…"; notifyChanged()
        val executor=if(readOnlyId==null)app.io else app.opening
        // Preserve request-to-worker total while distinguishing initial screen work from queue waiting.
        val enqueued = SystemClock.elapsedRealtime()
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching {if(readOnlyId==null)load() else NotebookReadGate.access(File(app.storageRoot,"notebooks"),readOnlyId,load)}
            val read = SystemClock.elapsedRealtime()
            val prepared = result.mapCatching { prepareRecovery(prepare(it)) }
            val ready = SystemClock.elapsedRealtime()
            app.main.post {
                val posted = SystemClock.elapsedRealtime()
                readOnlyId?.let {openingIds-=it;openingId=null}
                busy = false
                prepared.onSuccess { activate(it,keepHistory); updateEntry(it.notebook, opened = true); status = if(recoveryUnavailable) "Opened last saved copy (read only)" else "Saved locally"; opened() }
                    .onFailure { status = "Open failed: ${it.message}" }
                val activated = SystemClock.elapsedRealtime()
                notifyChanged()
                val timing = OpenTiming(started-requested, read-started, ready-read, posted-ready, activated-posted, SystemClock.elapsedRealtime()-activated, enqueued-requested, started-enqueued)
                lastOpenTiming = timing
                // B51: existing content-free phases also survive an ordinary process restart when an open is slow.
                android.util.Log.i("NotesOpen", "$timing")
                keepSlowTiming("open", timing.queueMs + timing.readMs + timing.prepareMs + timing.mainQueueMs + timing.activateMs + timing.notifyMs, timing.toString())
            }
        }
        return true
    }
    companion object {
        const val QUICK_NOTES = "Quick notes"
        private const val SAVE_SPACING = 4L
        private const val SAVE_BYTES_PER_MS = 2048L
        private const val SAVE_SPACING_MAX_MS = 2000L
        private const val SAVE_RETRY_MAX_MS = 30_000L
    }
}
