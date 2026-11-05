package dev.ownednotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.ownednotes.core.EncryptedFolder
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.SyncCancellation
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Additional opt-in destination. Primary folder, agreements and nightly delivery are never changed. */
internal object EncryptedSync {
    private const val TREE="encrypted_sync_tree"
    private const val RESULT="encrypted_sync_result"
    private fun prefs(context: Context)=context.getSharedPreferences((context.applicationContext as NotesApplication).preferencesName,0)
    fun folderUri(context: Context): Uri?=prefs(context).getString(TREE,null)?.let(Uri::parse)
    fun status(context: Context)=prefs(context).getString(RESULT,"Not synced yet")!!
    private fun identity(tree: Uri)=FolderSync.sha256(tree.toString().byteInputStream())
    private fun scope(app: NotesApplication)=FolderSync.sha256(app.preferencesName.byteInputStream()).take(24)
    private fun work(app: NotesApplication)=File(app.cacheDir,"encrypted-sync-${scope(app)}").apply { mkdirs() }
    private fun state(app: NotesApplication,tree: Uri)=File(app.storageRoot,"encrypted-sync-${identity(tree)}.txt")

    /** Validate remotely before recording configuration. Never select the primary folder here. */
    fun connect(app: NotesApplication,tree: Uri,passphrase: CharArray,done: (Result<Unit>)->Unit) {
        if(tree==LibrarySync.folderUri(app)) { passphrase.fill('\u0000');done(Result.failure(IOException("Choose a separate folder; current sync is kept.")));return }
        try { app.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch(error: Exception) { passphrase.fill('\u0000');done(Result.failure(error));return }
        prepare(app,tree,passphrase,{ LibrarySync.TreeFolder(app,tree) },done)
    }
    /** Synthetic provider injection is used only by isolated acceptance checks. Work stays on the save queue. */
    internal fun prepare(app: NotesApplication,tree: Uri,passphrase: CharArray,remote: ()->FolderSync.Folder,done: (Result<Unit>)->Unit) {
        val old=folderUri(app)
        app.io.execute {
            val result=runCatching {
                require(tree!=LibrarySync.folderUri(app)) { "Choose a separate encrypted destination" }
                val opened=EncryptedFolder.open(remote(),passphrase,work(app),create=true)
                val key=opened.keyMaterial()
                try { DeviceKey(app).save(identity(tree),key) }finally { key.fill(0) }
                Unit
            }
            passphrase.fill('\u0000')
            app.main.post {
                val committed=result.mapCatching {
                    check(prefs(app).edit().putString(TREE,tree.toString()).putString(RESULT,"Connected; ready to sync").commit()) { "Cannot save encrypted destination" }
                }
                if(committed.isSuccess) FolderAccess.releaseIfUnused(app,old) else FolderAccess.releaseIfUnused(app,tree)
                done(committed)
            }
        }
    }
    /** Disconnect only: copies in both destinations are retained. */
    fun disconnect(app: NotesApplication) {
        val old=folderUri(app) ?: return
        prefs(app).edit().remove(TREE).remove(RESULT).apply()
        app.io.execute { DeviceKey(app).forget(identity(old)) }
        FolderAccess.releaseIfUnused(app,old)
    }
    fun sync(app: NotesApplication,continueSync: ()->Boolean={true},cancellation: SyncCancellation?=null,
        remote: (() -> FolderSync.Folder)?=null,wifi: ()->Boolean={LibrarySync.onWifi(app)},done: (String?)->Unit) {
        val tree=folderUri(app) ?: return done(null)
        fun permitted()=folderUri(app)==tree && continueSync() && cancellation?.isCancelled!=true
        if(!permitted())return done(null)
        fun guard() { check(permitted()) { "Encrypted sync stopped" };if(LibrarySync.wifiOnly(app) && !wifi())throw IOException(LibrarySync.WIFI_WAITING) }
        app.repository.syncLibrary({busy,titles->
            guard()
            val raw=remote?.invoke() ?: LibrarySync.TreeFolder(app,tree,createIfMissing=false)
            val guarded=object: FolderSync.Folder {
                override fun list(): Map<String,FolderSync.Stat> {guard();return raw.list()}
                override fun read(name: String): InputStream {guard();return raw.read(name)}
                override fun write(name: String,from: File): FolderSync.Stat {guard();return raw.write(name,from)}
                override fun delete(name: String) {guard();raw.delete(name)}
            }
            val key=DeviceKey(app).read(identity(tree))
            val encrypted=try {EncryptedFolder.withKey(guarded,key,work(app))}finally {key.fill(0)}
            val file=state(app,tree)
            val agreement=if(file.isFile)FolderSync.decode(file.readText())else emptyMap()
            FolderSync.run(File(app.storageRoot,"notebooks"),encrypted,agreement,busy,titles,
                cancelled={!permitted()},sourceKey="encrypted:${tree}",attachmentDirectory=app.attachmentDirectory).also {outcome->
                guard()
                val atomic=AtomicFile(file);val output=atomic.startWrite()
                try {output.write(FolderSync.encode(outcome.state).toByteArray());atomic.finishWrite(output)}catch(error: Throwable){atomic.failWrite(output);throw error}
            }
        }) {result->
            if(!permitted()) {done(null);return@syncLibrary}
            val text=result.fold({ "Encrypted destination: ${it.message}" },{
                if(LibrarySync.wifiOnly(app) && !wifi())LibrarySync.WIFI_WAITING
                else "Encrypted sync failed. Check folder access and connection, or reconnect with its passphrase. Local notebooks and primary sync are kept."
            })
            prefs(app).edit().putString(RESULT,text).apply();done(text)
        }
    }

    /** Device-local wrapped derived key; no passphrase or plaintext key is backed up or shared. */
    private class DeviceKey(app: NotesApplication) {
        private val directory=File(app.noBackupFilesDir,"encrypted-sync-${scope(app)}").apply {mkdirs()}
        private val alias="notes-lab-encrypted-sync-${scope(app)}"
        private fun key(create: Boolean): SecretKey {
            val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            if(store.containsAlias(alias))return store.getKey(alias,null) as SecretKey
            check(create) {"Device key unavailable"}
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
        fun save(id: String,plain: ByteArray) {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(true));updateAAD(id.toByteArray())}
            val atomic=AtomicFile(File(directory,id));val out=atomic.startWrite()
            try {out.write(cipher.iv+cipher.doFinal(plain));atomic.finishWrite(out)}catch(error: Throwable){atomic.failWrite(out);throw error}
        }
        fun read(id: String): ByteArray {
            val bytes=AtomicFile(File(directory,id)).openRead().use {it.readNBytes(61)};require(bytes.size==60)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(id.toByteArray());doFinal(bytes.copyOfRange(12,bytes.size))
            }
        }
        fun forget(id: String)=AtomicFile(File(directory,id)).delete()
    }
}
