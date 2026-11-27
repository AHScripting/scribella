package dev.ownednotes.app

import android.app.Dialog
import android.content.Context
import dev.ownednotes.core.LibraryEntry
import dev.ownednotes.core.NotebookLibrary
import dev.ownednotes.core.NotebookHistory
import dev.ownednotes.app.SyncPanels.P

/** Shared operations also let isolated UI tests use synthetic provider folders, never real user content. */
class NotebookStorageActions(repository: NotebookRepository,
    val inspect: (String, (Result<Boolean>) -> Unit) -> Boolean = repository::inspectNotebookSourceAccess,
    val release: (String, (Result<Unit>) -> Unit) -> Boolean = { id, done ->
        repository.releaseNotebookStorage(id, closeCurrent = true, done = done)
    },
    val download: (String, (Result<Unit>) -> Unit) -> Boolean = { id, done -> repository.downloadNotebook(id, done = done) },
    val diskUsage: (String, (Result<NotebookLibrary.DiskUsage>) -> Unit) -> Boolean = repository::inspectNotebookDiskUsage,
    val historySnapshot: (String, (Result<NotebookHistory.Snapshot>) -> Unit) -> Boolean = repository::prepareHistoryCleanup,
    val removeHistory: (NotebookHistory.Snapshot, (Result<NotebookHistory.Purge>) -> Unit) -> Boolean = repository::removeEarlierVersions,
)

/** Glass kit and board 25's parts ([StoragePanels]); owns sheet lifetime, never notebook IO or navigation. */
internal class NotebookStoragePanel(private val context: Context, private val repository: NotebookRepository,
    private val entry: LibraryEntry, private val actions: NotebookStorageActions, private val openAfter: Boolean,
    private val valid: () -> Boolean, private val release: () -> Boolean, private val download: () -> Boolean,
    private val recover: (() -> Boolean)?, private val syncSettings: (() -> Unit)?) {
    private var alive = true
    private var sheet: Kit.Sheet? = null
    val dialog: Dialog? get() = sheet?.dialog
    val isShowing get() = sheet?.dialog?.isShowing == true

    private fun freeing(night: Boolean) = listOf(
        Explain.Line(P.CHECK, SyncPanels.green(night), "Its folder copy is checked first."),
        Explain.Line(P.DOWNLOAD, Glass.accent(night), "Download it again before writing."),
        Explain.Line(P.RESTORE, SyncPanels.slate(night), "Earlier versions and backups are kept."))

    private fun space(night: Boolean) = listOf(
        Explain.Line(P.BOOK, Glass.accent(night), "Only notebook files are counted."),
        Explain.Line(P.CLOCK, SyncPanels.slate(night), "Temporary files and search caches may stay."),
        Explain.Line(P.FOLDER, SyncPanels.green(night), "Backups and sync copies are kept, and not counted."))

    /**
     * P732 (design canvas board 25): the notebook's name under the title; a card (on this device or in its folder only,
     * and whether its folder can be read); two size tiles; the main button (Free up space, or Download and open / Keep
     * on this device once freed), enabled once the folder is checked; Remove earlier versions and Original folder (or
     * Sync settings) as rows. Explanations are behind the (i) buttons.
     */
    fun show() {
        val source = repository.notebookSource(entry.id)
        val webDav = source?.startsWith("webdav:") == true
        val local = entry.availableOffline
        val readOnly = repository.isNotebookReadOnly(entry.id)
        val night = context.night
        val device = StoragePanels.deviceWord(context)
        val page = Kit.Sheet(context, "Notebook storage", cancel = "Close", subtitle = entry.title)
        sheet = page

        // The card: where the notebook is, and whether its folder can be read.
        val card = StoragePanels.PlaceCard(context, if (webDav) P.SERVER else P.FOLDER, null, "About freeing space")
        card.info.setOnClickListener { Explain.show(card.info, "Freeing space", freeing(night)) }
        val left = if (local) "This $device" else "Not on this $device"
        // The library's sync folder by its name (board 25), otherwise "Original folder".
        val right = when { webDav -> "WebDAV server"; source == null -> "No sync folder"
            source == LibrarySync.sourceIdentity(context) -> "“${LibrarySync.folderName(context)}”"; else -> "Original folder" }
        val head = if (local) "Kept on this device" else "In its folder only"
        fun state(accessible: Boolean?, problem: String? = null) = card.show(when {
            problem != null -> StoragePanels.PlaceCard.State(SyncPanels.amber(night), P.ALERT, head, problem, P.ALERT,
                dashedLine = true, leftDashed = !local, left = left, right = right)
            source == null -> StoragePanels.PlaceCard.State(SyncPanels.green(night), P.CHECK, head,
                "Choose a sync folder and sync it before freeing space.", P.CHECK, dashedLine = true, rightDashed = true, left = left, right = right)
            readOnly && local -> StoragePanels.PlaceCard.State(SyncPanels.green(night), P.CHECK, head, "Read-only: its local copy is kept.", P.CHECK, left = left, right = right)
            accessible == null -> StoragePanels.PlaceCard.State(SyncPanels.slate(night), P.SYNC, head, "Checking its folder…", null,
                leftDashed = !local, left = left, right = right)
            accessible && local -> StoragePanels.PlaceCard.State(SyncPanels.green(night), P.CHECK, head, "Works offline · its folder copy can be read", P.CHECK, left = left, right = right)
            accessible -> StoragePanels.PlaceCard.State(Glass.accent(night), P.DOWNLOAD, head, "Download this notebook before opening or writing.", P.DOWNLOAD,
                leftDashed = true, left = left, right = right)
            else -> StoragePanels.PlaceCard.State(SyncPanels.amber(night), P.ALERT, "Can’t reach its folder",
                if (webDav) "Open sync settings to check the saved sign-in. Your notebook has not changed."
                else "Choose the original folder again. Your notebook has not changed.", P.ALERT, dashedLine = true, leftDashed = !local, left = left, right = right)
        })
        state(null)
        page.add(card)

        // Space on this device: two size tiles.
        val about = Explain.Button(context, "About space on this $device")
        about.setOnClickListener { Explain.show(about, "Space on this $device", space(night)) }
        page.add(SyncPanels.sectionRow(context, "Space on this $device", about), gap = 16f)
        val current = StoragePanels.StatTile(context, P.BOOK, Glass.accent(night), "Current notebook file size").apply {
            words.text = if (local) "This notebook" else "Freed · in its folder only"
        }
        val versions = StoragePanels.StatTile(context, P.RESTORE, SyncPanels.slate(night), "Earlier version file sizes").apply { words.text = "Earlier versions" }
        page.add(SyncPanels.TileGrid(context, 220f).apply { addView(current); addView(versions) }, gap = 2f)

        // The main button waits until the original folder has been checked.
        val primary = LiveSyncLook.pill(context, if (local) "Free up space" else if (openAfter) "Download and open" else "Keep on this device", Kit.Button.PRIMARY, P.DOWNLOAD) {
            if (!alive || !valid()) return@pill
            if (local) confirmRelease() else if (download()) page.dismiss()
            else state(true, "The notebook is busy or changed. Wait for saving and try again.")
        }.apply { isEnabled = false }
        page.add(primary)

        // Remove earlier versions, and the original folder (or sync settings).
        val history = SyncPanels.RowCard(context, P.TRASH, SyncPanels.fault(night), "Remove earlier versions", titleColor = SyncPanels.fault(night)).apply {
            state("Checking…", null); isEnabled = false
        }
        history.setOnClickListener {
            if (!history.isEnabled || !alive || !valid() || !repository.canChangeNotebook || repository.isNotebookReadOnly(entry.id)) return@setOnClickListener
            if (!actions.historySnapshot(entry.id) { result ->
                if (alive && sheet === page && page.dialog.isShowing && valid()) {
                    result.onSuccess { snapshot ->
                        if (snapshot.items.isEmpty()) history.state("No earlier versions remain on this device.", null)
                        else confirmHistory(snapshot)
                    }.onFailure { history.state("Could not be checked. Nothing was removed.", null) }
                }
            }) history.state("The notebook is busy or changed. Wait and try again.", null)
        }
        val folder = if (source != null && !webDav && recover != null) SyncPanels.RowCard(context, P.FOLDER, SyncPanels.green(night), "Original folder").apply {
            state("Choose it again if access is lost", null)
            setOnClickListener { if (alive && valid() && recover.invoke()) page.dismiss() }
        } else null
        val settings = if ((source == null || webDav) && syncSettings != null) SyncPanels.RowCard(context, P.SERVER, SyncPanels.slate(night), "Sync settings").apply {
            state(if (webDav) "Check the server sign-in" else "Choose a sync folder", null)
            setOnClickListener { if (alive && valid()) { page.dismiss(); syncSettings.invoke() } }
        } else null
        page.add(SyncPanels.TileGrid(context, 300f).apply { addView(history); folder?.let(::addView); settings?.let(::addView) }, gap = 16f)
        page.show()

        if (!actions.diskUsage(entry.id) { result ->
            if (alive && sheet === page && page.dialog.isShowing && valid()) {
                result.onSuccess { usage ->
                    current.number.text = android.text.format.Formatter.formatFileSize(context, usage.currentBytes)
                    versions.number.text = android.text.format.Formatter.formatFileSize(context, usage.earlierVersionBytes)
                    val files = "${usage.earlierVersionFiles} ${if (usage.earlierVersionFiles == 1) "file" else "files"}"
                    versions.words.text = "Earlier versions · $files"
                    history.state(if (usage.earlierVersionFiles > 0) "$files · ${android.text.format.Formatter.formatFileSize(context, usage.earlierVersionBytes)}" else "Nothing to remove", null)
                    history.isEnabled = usage.earlierVersionFiles > 0 && !readOnly
                }.onFailure { current.number.text = "Unavailable"; versions.number.text = "Unavailable"; history.state("Sizes unavailable", null) }
            }
        }) { current.number.text = "Unavailable"; versions.number.text = "Unavailable"; history.state("Sizes unavailable", null) }
        if (source != null && !actions.inspect(entry.id) { result ->
            if (alive && sheet === page && page.dialog.isShowing && valid()) {
                val accessible = result.getOrDefault(false)
                primary.isEnabled = accessible && (!local || !readOnly)
                state(accessible)
                // Out of reach: the Original folder row is the way back, so it says so and stands out.
                if (!accessible) folder?.apply {
                    state("Choose it again", SyncPanels.amber(night))
                    background = rounded(Kit.cardFill(night), context.dp(Glass.CARD), Explain.alpha(SyncPanels.amber(night), .6f), context.dpi(1.5f))
                }
            }
        }) state(null, "The notebook source changed. Close this sheet and try again.")
    }

    private fun confirmHistory(snapshot: NotebookHistory.Snapshot) {
        sheet?.dismiss()
        val confirmation = Kit.Sheet(context, "Remove earlier versions?", cancel = "Cancel")
        sheet = confirmation
        confirmation.add(Kit.text(context, entry.title, Kit.Type.HEADLINE))
        confirmation.add(Kit.text(context, "Permanently remove ${snapshot.items.size} earlier-version files (${android.text.format.Formatter.formatFileSize(context, snapshot.bytes)}) from this device? You will lose the ability to restore these versions here. This cannot be undone. Current notebook, Trash, backups and copies in the sync folder are kept. New or changed versions are kept.", secondary = true))
        val outcome = Kit.text(context, "", secondary = true)
        confirmation.add(outcome)
        confirmation.add(Kit.button(context, "Remove earlier versions", Kit.Button.DANGER) {
            if (!alive || sheet !== confirmation || !confirmation.dialog.isShowing || !valid() ||
                repository.isNotebookReadOnly(entry.id)) return@button
            if (actions.removeHistory(snapshot) { result ->
                if (alive && sheet === confirmation && confirmation.dialog.isShowing && valid()) {
                    if (result.isSuccess) { confirmation.dismiss(); show() }
                    else outcome.text = "Removal could not finish. Close and check storage again; remaining files are kept."
                }
            }) outcome.text = "Removing confirmed earlier versions…"
            else outcome.text = "The notebook is busy or changed. Nothing was removed. Close and try again."
        })
        confirmation.show()
    }

    private fun confirmRelease() {
        sheet?.dismiss()
        val confirmation = Kit.Sheet(context, "Free local storage?", "Free local copy", onAction = {
            alive && valid() && release()
        })
        sheet = confirmation
        confirmation.add(Kit.text(context, entry.title, Kit.Type.HEADLINE))
        confirmation.add(Kit.text(context, "The app will verify an identical copy in the original folder before removing this device's current notebook file. Download it again before writing. Earlier versions and backups are kept.", secondary = true))
        confirmation.show()
    }

    fun dismiss() { sheet?.dismiss() }
    fun dispose() { alive = false; sheet?.dismiss(); sheet = null }
}
