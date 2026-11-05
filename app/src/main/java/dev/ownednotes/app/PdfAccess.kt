package dev.ownednotes.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.ownednotes.core.PdfAsset
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Original assets stay encrypted and byte-exact. Only a private, disposable working copy is unlocked. */
internal object PdfAccess {
    class PasswordRequired(val asset: PdfAsset) : java.io.IOException("This PDF needs its password")
    private lateinit var app: NotesApplication
    private val plain = mutableSetOf<String>()
    private val opened = mutableMapOf<String, File>()
    private lateinit var directory: File
    internal lateinit var passwords: PdfPasswords; private set

    @Synchronized fun initialize(application: NotesApplication) {
        app=application
        val scope=application.preferencesName.toByteArray().let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        }
        directory=File(application.cacheDir,"pdf-open-$scope-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        passwords=PdfPasswords(application,scope)
        plain.clear();opened.clear()
    }

    fun <T> withFile(scratch: File, asset: PdfAsset, operation: (File)->T): T = asset.withVerifiedFile(scratch) { original ->
        val working=synchronized(this) {
            opened[asset.id]?.takeIf { it.isFile } ?: if(asset.id in plain) original else {
                try {
                    ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).close() }
                    plain+=asset.id;original
                } catch(locked: SecurityException) {
                    val password=passwords.read(asset.id) ?: throw PasswordRequired(asset)
                    try { unlockFile(asset,original,password) }
                    catch(error: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw PasswordRequired(asset) }
                }
            }
        }
        operation(working)
    }

    @Synchronized fun unlock(asset: PdfAsset, password: String, remember: Boolean) {
        require(password.length<=4096) { "Password is too long" }
        asset.withVerifiedFile(app.cacheDir) { original ->
            // Exercise the renderer's password API before publishing any decrypted working file.
            ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfiumCore(app).newDocument(descriptor,password).use { check(it.getPageCount()>0) { "PDF has no pages" } }
            }
            unlockFile(asset,original,password)
        }
        if(remember) passwords.save(asset.id,password) else passwords.forget(asset.id)
    }

    private fun unlockFile(asset: PdfAsset, original: File, password: String): File {
        val destination=File.createTempFile("unlocked-",".pdf",directory)
        try {
            PDDocument.load(original,password,MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)).use { document ->
                document.isAllSecurityToBeRemoved=true
                document.save(destination)
            }
            ParcelFileDescriptor.open(destination,ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).close() }
            opened.put(asset.id,destination)?.delete()
            return destination
        } catch(error: Throwable) { destination.delete();throw error }
    }
    /** Simulates a fresh process for isolated tests; remembered secrets are deliberately retained. */
    @Synchronized internal fun clearSession() { opened.values.forEach { it.delete() };opened.clear();plain.clear() }
}

/** Only ciphertext in scoped no-backup files; the AES key never leaves Android Keystore. */
internal class PdfPasswords(app: NotesApplication, scope: String) {
    private val directory=File(app.noBackupFilesDir,"pdf-passwords-$scope").apply { mkdirs() }
    private val alias="notes-lab-pdf-passwords-$scope"
    private fun file(id: String): File { require(id.matches(Regex("[a-f0-9]{64}")));return File(directory,"$id.bin") }
    @Synchronized fun save(id: String, password: String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(true));updateAAD(id.toByteArray()) }
        val plaintext=password.toByteArray(Charsets.UTF_8)
        val ciphertext=try { cipher.iv+cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val atomic=AtomicFile(file(id));val output=atomic.startWrite()
        try { output.write(ciphertext);atomic.finishWrite(output) } catch(error: Throwable) { atomic.failWrite(output);throw error }
    }
    @Synchronized fun read(id: String): String? = runCatching {
        val source=file(id);if(!source.isFile && !File(source.path+".bak").isFile) return null
        val bytes=AtomicFile(source).openRead().use { it.readNBytes(20_000) };require(bytes.size in 28..19_999)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(id.toByteArray())
        }
        val plaintext=cipher.doFinal(bytes.copyOfRange(12,bytes.size))
        try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
    }.getOrNull()
    @Synchronized fun forget(id: String) { AtomicFile(file(id)).delete() }
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(store.containsAlias(alias)) return store.getKey(alias,null) as SecretKey
        check(create) { "Saved PDF password key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
