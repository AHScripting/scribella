package dev.ownednotes.app

import android.content.Intent
import android.net.Uri
import dev.ownednotes.core.EncryptedFolder
import dev.ownednotes.core.FolderSync
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** Explicit snapshots/restores; never reconfigure the user's ordinary backup or either sync endpoint. */
internal object EncryptedBackups {
    /** P566: as LibrarySync.folderName. */
    fun folderName(context:android.content.Context):String=context.getString(R.string.encrypted_backup_folder)
    fun newKey()="NLB1-"+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
    fun cleanKey(value:String)=value.filterNot {it.isWhitespace()}.also {require(it.matches(Regex("NLB1-[A-Za-z0-9_-]{43}"))) {"Enter the complete recovery key"}}
    private fun scratch(app:NotesApplication)=File(app.cacheDir,"encrypted-backup-${FolderSync.sha256(app.preferencesName.byteInputStream()).take(24)}").apply {mkdirs()}
    private fun folder(encrypted:EncryptedFolder)=object:LibraryBackup.Folder {
        override fun names()=encrypted.list().keys
        override fun write(name:String,from:File):String {encrypted.write(name,from);return name}
        override fun read(name:String)=encrypted.read(name)
        override fun delete(name:String)=encrypted.delete(name)
    }
    private fun separate(app:NotesApplication,tree:Uri) {
        require(tree!=LibrarySync.folderUri(app) && tree!=EncryptedSync.folderUri(app) && tree!=LibraryBackup.folderUri(app)) {"Choose a separate folder; current backups and sync are kept"}
    }
    private fun grant(app:NotesApplication,tree:Uri) {
        separate(app,tree)
        app.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    fun create(app:NotesApplication,tree:Uri,key:String,done:(Result<LibraryBackup.Result>)->Unit) {
        try {grant(app,tree)}catch(error:Exception){done(Result.failure(error));return}
        createPrepared(app,tree,key,{LibrarySync.TreeFolder(app,tree,rootName=folderName(app))}) {result->FolderAccess.releaseIfUnused(app,tree);done(result)}
    }
    internal fun createPrepared(app:NotesApplication,tree:Uri,key:String,remote:()->FolderSync.Folder,done:(Result<LibraryBackup.Result>)->Unit) {
        if(!app.documentsReady){done(Result.failure(IllegalStateException("Wait for the local save")));return}
        val secret=try {cleanKey(key).toCharArray()}catch(error:Exception){done(Result.failure(error));return}
        val titles=app.repository.entries.associate {it.id to it.title}
        app.repository.setFileBusy(true)
        app.io.execute {
            val result=runCatching {
                separate(app,tree)
                val encrypted=EncryptedFolder.open(remote(),secret,scratch(app),create=true)
                // ID-only names do not reveal notebook titles; the original package carries the title.
                LibraryBackup.run(File(app.storageRoot,"notebooks"),folder(encrypted),titles,emptyMap(),nameFor={id,_->"$id.inknote"})
            }
            secret.fill('\u0000')
            app.main.post {app.repository.setFileBusy(false);done(result)}
        }
    }
    fun restore(app:NotesApplication,tree:Uri,key:String,done:(Result<LibraryBackup.Restore>)->Unit) {
        try {grant(app,tree)}catch(error:Exception){done(Result.failure(error));return}
        restorePrepared(app,key,{LibrarySync.TreeFolder(app,tree,createIfMissing=false,rootName=folderName(app))}) {result->FolderAccess.releaseIfUnused(app,tree);done(result)}
    }
    internal fun restorePrepared(app:NotesApplication,key:String,remote:()->FolderSync.Folder,done:(Result<LibraryBackup.Restore>)->Unit) {
        val secret=try {cleanKey(key).toCharArray()}catch(error:Exception){done(Result.failure(error));return}
        if(!app.repository.restoreFromBackup({
            try {folder(EncryptedFolder.open(remote(),secret,scratch(app),create=false))}finally {secret.fill('\u0000')}
        },done)) {secret.fill('\u0000');done(Result.failure(IllegalStateException("Wait for the local save")))}
    }
}
