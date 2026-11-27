package dev.ownednotes.app

import dev.ownednotes.core.ImageAsset
import dev.ownednotes.core.LiveChanges
import dev.ownednotes.core.LiveCrypto
import dev.ownednotes.core.LiveSyncClient
import dev.ownednotes.core.Notebook
import java.io.File

/**
 * Instant sync (docs/LIVE-SYNC-PLAN.md): the app's side of [LiveSyncClient]. One per process, owned by NotesApplication.
 * The screen connects it while the app is in front ([shown]) and runs folder sync when it asks ([folderSync]).
 * Step 9: while [LiveSyncService] runs ([background]) it stays connected with no screen, and folder sets run the
 * background folder sync instead.
 */
internal class LiveSync(private val app: NotesApplication) : LiveSyncClient.Events {
    val client by lazy { LiveSyncClient(File(app.storageRoot, "live-sync"), this) }
    private val watchers = linkedSetOf<() -> Unit>()
    /** Set by the screen: run folder sync soon (new files, or changes this device missed). */
    var folderSync: (() -> Unit)? = null

    val status get() = client.status
    val live get() = status.state == LiveSyncClient.State.LIVE
    val configured get() = status.state != LiveSyncClient.State.OFF

    private var shown = false
    private var background = false
    private var folderRun: BackgroundSyncRun? = null
    private var folderAgain = false
    private val backgroundFolder = Runnable { backgroundFolderSync() }

    fun start() { follow(reset = false); client.start() }
    fun stop() = client.stop()
    /** The screen came to the front or left it. Main thread. */
    fun shown(screen: Boolean) { shown = screen; if (screen) start() else if (!background) stop() }
    /** [LiveSyncService] started or stopped. Main thread. */
    fun background(on: Boolean) {
        background = on
        if (on) return start()
        app.main.removeCallbacks(backgroundFolder); folderAgain = false
        folderRun?.stop(); folderRun = null
        if (!shown) stop()
    }
    /** A folder set arrived with no screen: run the background folder sync (one at a time), then send what waited. */
    private fun backgroundFolderSync() {
        if (shown && folderSync != null) return folderSync!!.invoke()
        if (!background || !LibrarySync.hasSource(app) || LibrarySync.paused(app)) return
        if (folderRun != null) { folderAgain = true; return }
        folderAgain = false
        lateinit var run: BackgroundSyncRun
        run = BackgroundSyncRun(app, allowed = { background && LibrarySync.hasSource(app) && !LibrarySync.paused(app) }) {
            if (folderRun !== run) return@BackgroundSyncRun
            folderRun = null
            if (run.outcome == BackgroundSyncHistory.State.CHECKED) folderSynced()
            if (folderAgain) app.main.postDelayed(backgroundFolder, 3_000)
        }
        folderRun = run
        run.start()
    }
    /**
     * Turns instant sync on with [code] (off with null). Not on the main thread (it writes files). The notebooks already
     * loaded are followed from what they are now: a notebook that stays open while the code is set is not opened again
     * (the Library reuses it), and without this its first edit afterwards only set the starting point and was not sent.
     */
    fun setCode(code: LiveCrypto.SyncCode?) { client.setCode(code); if (code != null) app.main.post { follow(reset = true) } }
    /** Follows the loaded notebooks; without [reset], only those it is not following yet. Main thread. */
    private fun follow(reset: Boolean) { if (configured) app.openNotebooks.forEach { client.opened(it, reset) } }
    fun saved(notebook: Notebook) { if (configured) client.saved(notebook) }
    fun opened(notebook: Notebook) { if (configured) client.opened(notebook, reset = true) }
    /** Folder sync finished: changes held back until their files were uploaded can go now. */
    fun folderSynced() { if (configured) client.folderSynced() }
    /** B79: folder sync combined both devices' changes in these notebooks and uploaded them; the other devices fetch them. */
    fun folderChanged(ids: Collection<String>) { if (configured && ids.isNotEmpty()) client.folderChanged(ids) }

    /** Calls [watcher] on the main thread whenever the status changes, until [unwatch]. */
    fun watch(watcher: () -> Unit) { watchers += watcher }
    fun unwatch(watcher: () -> Unit) { watchers -= watcher }

    override fun incoming(set: LiveChanges.ChangeSet, done: (LiveSyncClient.Taken) -> Unit) {
        // P719: the set's photos, fetched by the client into files, are read here, off the main thread, one set at a time.
        val photos = runCatching { set.photos.orEmpty().map { ImageAsset.fromBytes(client.photo(it.id)!!.readBytes()) } }
            .getOrElse { return done(LiveSyncClient.Taken.NOT) }
        if (!app.main.post { app.takeLiveChanges(listOf(set), photos, done) }) done(LiveSyncClient.Taken.NOT)
    }
    /** P724: several sets in a row for one notebook (no photos): taken in together, a closed notebook written once. */
    override val runs get() = true
    override fun incomingRun(sets: List<LiveChanges.ChangeSet>, done: (LiveSyncClient.Taken) -> Unit) {
        if (!app.main.post { app.takeLiveChanges(sets, emptyList(), done) }) done(LiveSyncClient.Taken.NOT)
    }
    override fun folderSyncWanted() {
        app.main.post {
            val screen = folderSync.takeIf { shown }
            if (screen != null) screen() else { app.main.removeCallbacks(backgroundFolder); app.main.postDelayed(backgroundFolder, 3_000) }
        }
    }
    override fun statusChanged(status: LiveSyncClient.Status) {
        app.main.post {
            // Turned off, or the server refused the code: the background service goes too.
            if (status.state == LiveSyncClient.State.OFF || status.state == LiveSyncClient.State.DENIED) LiveSyncBackground.update(app)
            watchers.toList().forEach { it() }
        }
    }

    /** One line for the Library sync sheet and the Instant sync sheet. */
    fun summary(): String {
        val s = status
        val waiting = if (s.pending > 0) " · ${s.pending} ${if (s.pending == 1) "change" else "changes"} waiting to send" else ""
        return when (s.state) {
            LiveSyncClient.State.OFF -> "Off. Changes reach your other devices through folder sync."
            LiveSyncClient.State.PAUSED -> "On. Connects while Scribella is open${if (LiveSyncBackground.enabled(app)) " or receiving in the background" else ""}$waiting."
            LiveSyncClient.State.CONNECTING -> "Connecting…$waiting"
            LiveSyncClient.State.LIVE -> "Connected. Changes reach your other devices within seconds$waiting."
            LiveSyncClient.State.WAITING -> "Not connected${s.detail?.let { ": $it" } ?: ""}. Trying again…$waiting"
            LiveSyncClient.State.DENIED -> "Refused: this server belongs to another sync code. Turn instant sync off and join with the right code."
        }
    }
}
