package dev.ownednotes.app

import android.app.Dialog
import android.content.Context
import dev.ownednotes.core.LibraryEntry
import dev.ownednotes.core.NotebookLibrary
import dev.ownednotes.core.NotebookHistory

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

/** Existing Glass kit only; owns sheet lifetime, never notebook IO or navigation. */
internal class NotebookStoragePanel(private val context: Context, private val repository: NotebookRepository,
    private val entry: LibraryEntry, private val actions: NotebookStorageActions, private val openAfter: Boolean,
    private val valid: () -> Boolean, private val release: () -> Boolean, private val download: () -> Boolean,
    private val recover: (() -> Boolean)?, private val syncSettings: (() -> Unit)?) {
    private var alive = true
    private var sheet: Kit.Sheet? = null
    val dialog: Dialog? get() = sheet?.dialog
    val isShowing get() = sheet?.dialog?.isShowing == true

    fun show() {
        val source = repository.notebookSource(entry.id)
        val webDav = source?.startsWith("webdav:") == true
        val local = entry.availableOffline
        val readOnly = repository.isNotebookReadOnly(entry.id)
        val page = Kit.Sheet(context, "Notebook storage", cancel = "Close")
        sheet = page
        page.add(Kit.text(context, entry.title, Kit.Type.HEADLINE))
        page.add(Kit.row(context, if (local) "Kept on this device" else "Not downloaded",
            detail = if (local) "Available for offline use" else "Download from its original folder to open it"))
        val currentBytes = Kit.text(context, "Checking…", secondary = true).apply { contentDescription = "Current notebook file size" }
        val versionBytes = Kit.text(context, "Checking…", secondary = true).apply { contentDescription = "Earlier version file sizes" }
        page.add(Kit.Group(context).apply {
            addView(Kit.row(context, "Current notebook file", trailing = currentBytes))
            addView(Kit.row(context, "Earlier versions", detail = "Kept after freeing the current copy", trailing = versionBytes))
        })
        page.add(Kit.text(context, "Sizes cover notebook files only. Shared temporary files and recognition/search caches may remain after freeing a local copy. Existing backups and copies in your sync folder are kept and are not included.", secondary = true))
        val detail = Kit.text(context, when {
            source == null -> "Choose a sync folder and sync this notebook before freeing its local copy."
            readOnly && local -> "This notebook is read-only. Its local copy is kept."
            else -> "Checking access to the original folder…"
        }, secondary = true)
        page.add(detail)
        val primary = Kit.button(context, if (local) "Free up space…" else if (openAfter) "Download and open" else "Keep on this device") {
            if (!alive || !valid()) return@button
            if (local) confirmRelease() else if (download()) page.dismiss()
            else detail.text = "The notebook is busy or changed. Wait for saving and try again."
        }.apply { isEnabled = false }
        page.add(primary)
        val historyDetail = Kit.text(context, "Earlier versions stay until you explicitly remove them. Removing them cannot be undone.", secondary = true)
        val historyButton = Kit.button(context, "Remove earlier versions…", Kit.Button.DANGER) {
            if (!alive || !valid() || !repository.canChangeNotebook || repository.isNotebookReadOnly(entry.id)) return@button
            if (!actions.historySnapshot(entry.id) { result ->
                if (alive && sheet === page && page.dialog.isShowing && valid()) {
                    result.onSuccess { snapshot ->
                        if (snapshot.items.isEmpty()) historyDetail.text = "No earlier versions remain on this device."
                        else confirmHistory(snapshot)
                    }.onFailure { historyDetail.text = "Earlier versions could not be checked. Nothing was removed." }
                }
            }) historyDetail.text = "The notebook is busy or changed. Wait and try again."
        }.apply { isEnabled = false }
        page.add(historyButton); page.add(historyDetail)
        if (source != null && !webDav && recover != null) page.add(Kit.button(context, "Choose original folder…", Kit.Button.TINTED) {
            if (alive && valid() && recover.invoke()) page.dismiss()
        })
        if ((source == null || webDav) && syncSettings != null) page.add(Kit.button(context, "Sync settings…", Kit.Button.TINTED) {
            if (alive && valid()) { page.dismiss(); syncSettings.invoke() }
        })
        if (local) page.add(Kit.text(context, "Earlier versions and existing backups are kept. This action removes only the current notebook file after verifying its folder copy.", secondary = true))
        page.show()
        if (!actions.diskUsage(entry.id) { result ->
            if (alive && sheet === page && page.dialog.isShowing && valid()) {
                result.onSuccess { usage ->
                    currentBytes.text = android.text.format.Formatter.formatFileSize(context, usage.currentBytes)
                    versionBytes.text = "${usage.earlierVersionFiles} ${if (usage.earlierVersionFiles == 1) "file" else "files"} · ${android.text.format.Formatter.formatFileSize(context, usage.earlierVersionBytes)}"
                    historyButton.isEnabled = usage.earlierVersionFiles > 0 && !readOnly
                }.onFailure { currentBytes.text = "Unavailable"; versionBytes.text = "Unavailable" }
            }
        }) { currentBytes.text = "Unavailable"; versionBytes.text = "Unavailable" }
        if (source != null && !actions.inspect(entry.id) { result ->
            if (alive && sheet === page && page.dialog.isShowing && valid()) {
                val accessible = result.getOrDefault(false)
                primary.isEnabled = accessible && (!local || !readOnly)
                detail.text = when {
                    readOnly && local -> "This notebook is read-only. Its local copy is kept."
                    accessible && local -> "Original-folder read access is available. The saved copy is verified before local storage is freed."
                    accessible -> "Original-folder read access is available. Download this notebook before opening or writing."
                    else -> if (webDav) "Open sync settings to check the saved WebDAV sign-in. Your notebook has not changed."
                        else "Choose the original folder to restore access. Your notebook has not changed."
                }
            }
        }) detail.text = "The notebook source changed. Close this sheet and try again."
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
