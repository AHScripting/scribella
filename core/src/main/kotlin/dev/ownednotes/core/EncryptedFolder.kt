package dev.ownednotes.core

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Opt-in additional endpoint transport. It never changes a primary folder or native notebook bytes. */
class EncryptedFolder private constructor(private val remote: FolderSync.Folder, private val master: ByteArray,
    private val marker: ByteArray, private val scratch: File) : FolderSync.Folder {
    /** Wrap with device Keystore before persisting; never put these bytes in preferences or shared folders. */
    fun keyMaterial(): ByteArray = master.copyOf()
    private fun name(logical: String): String {
        require(logical.matches(Regex("[A-Za-z0-9._-]{1,200}")) && logical!="." && logical!="..") { "Invalid encrypted filename" }
        return logical+SUFFIX
    }
    private fun checkMarker() { checkMarker(remote,marker) }
    override fun list(): Map<String,FolderSync.Stat> {
        checkMarker()
        return remote.list().filterKeys { it!=MARKER }.map { (stored,stat) ->
            require(stored.endsWith(SUFFIX)) { "This encrypted folder contains an unrecognized file" }
            val logical=stored.removeSuffix(SUFFIX);name(logical)
            val payload=stat.size-OVERHEAD
            require(payload>=0) { "Encrypted file is incomplete" }
            val chunks=if(payload==0L)0L else (payload+CHUNK+FRAME-1)/(CHUNK+FRAME)
            val plain=payload-chunks*FRAME
            require(plain in 0..MAX_BYTES && encryptedSize(plain)==stat.size) { "Encrypted file has an invalid length" }
            logical to FolderSync.Stat(plain,stat.modified)
        }.toMap()
    }
    override fun read(name: String): InputStream {
        checkMarker();val stored=name(name);val staged=File.createTempFile("decrypt-",".pending",scratch)
        try {
            remote.read(stored).use { raw -> DataInputStream(raw.buffered()).use { input ->
                val header=input.readNBytes(HEADER);require(header.size==HEADER && header.copyOfRange(0,8).contentEquals(MAGIC)) { "Invalid encrypted header" }
                val size=ByteBuffer.wrap(header,24,8).long;require(size in 0..MAX_BYTES) { "Encrypted file exceeds the notebook limit" }
                val key=fileKey(master,header.copyOfRange(8,24));var remaining=size;var index=0
                staged.outputStream().buffered().use { output ->
                    while(remaining>0) {
                        val count=input.readInt();require(count==minOf(CHUNK.toLong(),remaining).toInt()) { "Incomplete encrypted chunk" }
                        val bytes=input.readNBytes(count+16);require(bytes.size==count+16) { "Incomplete encrypted chunk" }
                        output.write(cipher(Cipher.DECRYPT_MODE,key,header,name,index++,false).doFinal(bytes));remaining-=count
                    }
                    require(input.readInt()==0) { "Missing encrypted end marker" }
                    val end=input.readNBytes(16);require(end.size==16) { "Incomplete encrypted end marker" }
                    require(cipher(Cipher.DECRYPT_MODE,key,header,name,index,true).doFinal(end).isEmpty())
                    require(input.read()==-1) { "Unexpected encrypted trailing data" }
                }
            } }
            // No bytes reach the notebook reader until every chunk and the terminal record authenticate.
            return object : FilterInputStream(staged.inputStream()) {
                override fun close() { try { super.close() } finally { staged.delete() } }
            }
        } catch(error: Throwable) { staged.delete();throw IOException("Encrypted sync file could not be authenticated",error) }
    }
    override fun write(name: String, from: File): FolderSync.Stat {
        checkMarker();val stored=name(name);val size=from.length();require(from.isFile && size in 0..MAX_BYTES)
        val header=ByteBuffer.allocate(HEADER).put(MAGIC).put(random(16)).putLong(size).array()
        val key=fileKey(master,header.copyOfRange(8,24));val staged=File.createTempFile("encrypt-",".pending",scratch)
        try {
            DataOutputStream(staged.outputStream().buffered()).use { output ->
                output.write(header);var remaining=size;var index=0
                from.inputStream().buffered().use { input ->
                    while(remaining>0) {
                        val count=minOf(CHUNK.toLong(),remaining).toInt();val bytes=input.readNBytes(count)
                        require(bytes.size==count) { "Notebook changed during encryption" }
                        output.writeInt(count);output.write(cipher(Cipher.ENCRYPT_MODE,key,header,name,index++,false).doFinal(bytes));remaining-=count
                    }
                    require(input.read()==-1) { "Notebook changed during encryption" }
                }
                output.writeInt(0);output.write(cipher(Cipher.ENCRYPT_MODE,key,header,name,index,true).doFinal())
            }
            check(staged.length()==encryptedSize(size));checkMarker()
            val written=remote.write(stored,staged)
            check(written.size==staged.length()) { "Encrypted upload is incomplete" }
            return FolderSync.Stat(size,written.modified)
        }finally { staged.delete() }
    }
    override fun delete(name: String) { checkMarker();remote.delete(name(name)) }

    companion object {
        const val MARKER=".notes-lab-encryption"
        private const val SUFFIX=".nle"
        private const val CHUNK=65536
        private const val FRAME=20L
        private const val HEADER=32
        private const val OVERHEAD=52L
        private const val MAX_BYTES=2L*1024*1024*1024
        private const val ITERATIONS=600_000
        private val MAGIC="NLCFILE1".toByteArray(Charsets.US_ASCII)
        private val MARKER_MAGIC="NLCKEY01".toByteArray(Charsets.US_ASCII)
        private val CHECK="Notes Lab key v1".toByteArray(Charsets.US_ASCII)
        private fun random(n: Int)=ByteArray(n).also { SecureRandom().nextBytes(it) }
        private fun encryptedSize(size: Long)=OVERHEAD+size+((size+CHUNK-1)/CHUNK)*FRAME
        private fun fileKey(master: ByteArray,salt: ByteArray)=Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(master,"HmacSHA256"));doFinal("Notes Lab file v1".toByteArray()+salt)
        }
        private fun cipher(mode: Int,key: ByteArray,header: ByteArray,name: String,index: Int,last: Boolean): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,ByteBuffer.allocate(12).putLong(0).putInt(index).array()))
                updateAAD(header);updateAAD(name.toByteArray(Charsets.UTF_8));updateAAD(byteArrayOf(if(last)1 else 0))
            }
        private fun checkMarker(remote: FolderSync.Folder, expected: ByteArray) {
            val actual=remote.read(MARKER).use { it.readNBytes(73) }
            check(MessageDigest.isEqual(expected,actual)) { "Encrypted folder identity changed; reconnect with the passphrase" }
        }
        private fun verify(marker: ByteArray,key: ByteArray) {
            require(marker.size==72 && marker.copyOfRange(0,8).contentEquals(MARKER_MAGIC)) { "Unrecognized encrypted folder" }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,marker.copyOfRange(28,40)))
                updateAAD(marker.copyOfRange(0,28))
            }
            require(MessageDigest.isEqual(CHECK,cipher.doFinal(marker.copyOfRange(40,72)))) { "Invalid encryption key" }
        }
        /** Creation is allowed only in an empty endpoint. Opening an existing endpoint never changes it. */
        fun open(remote: FolderSync.Folder, passphrase: CharArray, scratch: File, create: Boolean=false): EncryptedFolder {
            require(passphrase.size in 8..4096) { "Use a passphrase of at least 8 characters" };check(scratch.isDirectory || scratch.mkdirs())
            val listing=remote.list();var marker=if(MARKER in listing)remote.read(MARKER).use {it.readNBytes(73)} else null
            if(marker==null)require(create && listing.isEmpty()) { "Choose a new empty encrypted folder or an existing encrypted destination" }
            val prefix=if(marker==null)ByteBuffer.allocate(28).put(MARKER_MAGIC).put(random(16)).putInt(ITERATIONS).array() else {
                require(marker.size==72 && marker.copyOfRange(0,8).contentEquals(MARKER_MAGIC));marker.copyOfRange(0,28)
            }
            val iterations=ByteBuffer.wrap(prefix,24,4).int;require(iterations in ITERATIONS..2_000_000)
            val spec=PBEKeySpec(passphrase,prefix.copyOfRange(8,24),iterations,256)
            val key=try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }finally {spec.clearPassword()}
            try {
                if(marker==null) {
                    val iv=random(12);val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));updateAAD(prefix)
                    }
                    marker=prefix+iv+cipher.doFinal(CHECK)
                    require(remote.list().isEmpty()) { "The selected folder is no longer empty" }
                    val staged=File.createTempFile("key-marker-",".pending",scratch)
                    try { staged.writeBytes(marker);remote.write(MARKER,staged) }finally {staged.delete()}
                }
                verify(marker,key);checkMarker(remote,marker)
                return EncryptedFolder(remote,key,marker,scratch)
            }catch(error: Exception) {key.fill(0);throw IOException("Incorrect passphrase or damaged encrypted destination",error)}
        }
        fun withKey(remote: FolderSync.Folder,key: ByteArray,scratch: File): EncryptedFolder {
            require(key.size==32);check(scratch.isDirectory || scratch.mkdirs())
            val marker=remote.read(MARKER).use {it.readNBytes(73)}
            try {verify(marker,key)}catch(error: Exception){throw IOException("Encrypted destination needs its passphrase again",error)}
            return EncryptedFolder(remote,key.copyOf(),marker,scratch)
        }
    }
}
