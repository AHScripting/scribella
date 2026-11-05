package dev.ownednotes.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.ownednotes.core.WebDavEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private account owner. Call on the save/IO executor; never sync or export this directory. */
internal class WebDavAccountStore(private val directory: File, private val keyAlias: String) {
    enum class Problem { INVALID_INPUT, IDENTITY_CHANGE, BUSY, UNAVAILABLE }
    class Failure(val problem: Problem) : IOException("WebDAV account settings need attention ($problem).")

    // Deliberately not a data class: generated toString/copy/component functions must not expose secrets.
    class Account(val id: UUID, val endpoint: WebDavEndpoint, val username: String?, val password: String?) {
        override fun toString() = "WebDAV account (credentials hidden)"
    }

    private val file = File(directory, "account.bin")
    private val aad = "Notes Lab WebDAV account v1:$keyAlias".toByteArray(Charsets.UTF_8)

    fun load(): Account? = guardedOperation { locked { read() } }

    /** Password refresh preserves UUID. Changing endpoint/user needs the future explicit retirement flow. */
    fun validateDraft(address: String, username: String?, password: String?): WebDavEndpoint {
        val endpoint = try { WebDavEndpoint.parse(address) } catch (_: Exception) { throw Failure(Problem.INVALID_INPUT) }
        // Normalization can append '/'; never persist an address the bounded decoder cannot reopen.
        if (endpoint.collection.toString().length > 8192) throw Failure(Problem.INVALID_INPUT)
        validateCredentials(username, password)
        return endpoint
    }

    fun save(address: String, username: String?, password: String?): Account {
        val endpoint = validateDraft(address, username, password)
        return guardedOperation { locked {
            val previous = read() // Corrupt/missing-key settings cannot silently become a different account.
            if (previous != null && (previous.endpoint.collection != endpoint.collection || previous.username != username)) {
                throw Failure(Problem.IDENTITY_CHANGE)
            }
            val account = Account(previous?.id ?: UUID.randomUUID(), endpoint, username, password)
            val plain = encode(account)
            val encrypted = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = previous == null))
                cipher.updateAAD(aad)
                check(cipher.iv.size == 12)
                ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeInt(MAGIC); out.writeInt(1); out.write(cipher.iv); out.write(cipher.doFinal(plain))
                    }
                }.toByteArray()
            } finally { plain.fill(0) }
            check(encrypted.size <= MAX_BYTES)
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(encrypted); atomic.finishWrite(out) }
            catch (error: Throwable) { atomic.failWrite(out); throw error }
            account
        } }
    }

    private fun read(): Account? {
        val backup = File(directory, "account.bin.bak")
        if (!present(file) && !present(backup)) return null
        // AtomicFile can recover its previous committed backup, never a new uncommitted write.
        val bytes = AtomicFile(file).openRead().use { it.readNBytes(MAX_BYTES + 1) }
        check(bytes.size in 36..MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == MAGIC && input.readInt() == 1)
        val iv = ByteArray(12).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(input.readBytes())
        return try { decode(plain) } finally { plain.fill(0) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) return store.getKey(keyAlias, null) as SecretKey
        check(create) // Never generate a replacement key for existing unreadable settings.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun <T> locked(block: () -> T): T {
        Files.createDirectories(directory.toPath())
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        for (name in listOf("account.bin", "account.bin.bak", "account.bin.new", "account.lock")) {
            val path = File(directory, name).toPath()
            check(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS))
        }
        return FileChannel.open(File(directory, "account.lock").toPath(), CREATE, WRITE).use { channel ->
            val guard = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw Failure(Problem.BUSY)
            guard.use { block() }
        }
    }

    private fun <T> guardedOperation(block: () -> T): T = try { block() }
        catch (error: Failure) { throw error }
        catch (_: Exception) { throw Failure(Problem.UNAVAILABLE) } // No endpoint, username, password or cause leaks.

    private fun encode(account: Account): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeLong(account.id.mostSignificantBits); out.writeLong(account.id.leastSignificantBits)
            out.writeUTF(account.endpoint.collection.toString())
            out.writeBoolean(account.username != null)
            account.username?.let { out.writeUTF(it); out.writeUTF(account.password!!) }
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): Account = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val id = UUID(input.readLong(), input.readLong())
        val endpoint = WebDavEndpoint.parse(input.readUTF())
        val authenticated = input.readBoolean()
        val username = if (authenticated) input.readUTF() else null
        val password = if (authenticated) input.readUTF() else null
        validateCredentials(username, password)
        check(input.available() == 0)
        Account(id, endpoint, username, password)
    }

    private fun validateCredentials(username: String?, password: String?) {
        if ((username == null) != (password == null) ||
            username != null && (username.length !in 1..1024 || ':' in username || username.any { it.isISOControl() }) ||
            password != null && password.length > 8192) throw Failure(Problem.INVALID_INPUT)
    }

    private fun present(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    companion object {
        private const val MAGIC = 0x4e4c5741
        private const val MAX_BYTES = 65536
        fun namespace(app: NotesApplication): String =
            MessageDigest.getInstance("SHA-256").digest(app.preferencesName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun forApp(app: NotesApplication): WebDavAccountStore {
            val scope = namespace(app)
            return WebDavAccountStore(File(app.noBackupFilesDir, "webdav-$scope"), "notes-lab.webdav.$scope")
        }
    }
}
