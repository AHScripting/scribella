package dev.ownednotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.ownednotes.core.OfflineNotebooks
import java.io.File

/** Shared ownership of persisted grants: sync, backup and released notebooks can use one tree. */
internal object FolderAccess {
    internal fun isTreeSource(tree: String): Boolean = runCatching {
        val uri = Uri.parse(tree)
        uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri)
    }.getOrDefault(false)

    internal fun canRecover(tree: String, grants: Result<Map<String, Boolean>>): Boolean =
        isTreeSource(tree) && grants.getOrNull()?.get(tree) == true

    /** Call on IO: transient picker access and write-only grants cannot protect a released notebook. */
    fun hasPersistedRead(context: Context, tree: String): Boolean = canRecover(tree, runCatching {
        context.contentResolver.persistedUriPermissions.associate { it.uri.toString() to it.isReadPermission }
    })

    /** Keep the permissions the user's current sync/backup configuration needs; an older source only needs reads. */
    internal fun recoveryFlags(tree: String, sync: String?, backup: String?): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (tree == sync || tree == backup) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

    private fun recoveryFlags(context: Context, tree: String) = recoveryFlags(tree,
        LibrarySync.folderUri(context)?.toString(), LibraryBackup.folderUri(context)?.toString())

    fun takeForRecovery(context: Context, tree: String) {
        context.contentResolver.takePersistableUriPermission(Uri.parse(tree), recoveryFlags(context, tree))
    }

    fun hasRecoveryAccess(context: Context, tree: String): Boolean = runCatching {
        val flags = recoveryFlags(context, tree)
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == tree }
        isTreeSource(tree) && permission?.isReadPermission == true &&
            (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 || permission.isWritePermission)
    }.getOrDefault(false)

    internal fun canRelease(tree: String, sync: String?, backup: String?,
        sources: Result<Set<String>>, idle: Boolean): Boolean =
        idle && tree != sync && tree != backup && sources.getOrNull()?.let { tree !in it } == true

    fun releaseIfUnused(context: Context, tree: Uri?) {
        tree ?: return
        if (!isTreeSource(tree.toString())) return
        val app = context.applicationContext as? NotesApplication ?: return
        // Record IO shares the save/transfer queue. On the main thread, a pending transfer
        // or an unreadable library retains the grant rather than risking the only remote copy.
        app.io.execute {
            val sources = runCatching {
                OfflineNotebooks(File(app.storageRoot, "notebooks")).released().map { it.source }.toSet()
            }
            app.main.post {
                if (tree != EncryptedSync.folderUri(app) && canRelease(tree.toString(), LibrarySync.folderUri(app)?.toString(),
                        LibraryBackup.folderUri(app)?.toString(), sources, app.documentsReady)) {
                    runCatching {
                        app.contentResolver.releasePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }
            }
        }
    }
}
