package dev.ownednotes.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.Cleaner
import java.security.MessageDigest

/** Internal immutable storage; only owned copies, never a caller-owned file path. */
internal interface AttachmentPayload {
    val id: String
    val size: Int
    fun openStream(): InputStream
    fun <T> withVerifiedFile(directory: File,operation: (File)->T): T {
        require(directory.isDirectory) { "Asset scratch directory is unavailable" }
        val file=File.createTempFile("asset-access-",".bin",directory)
        try {
            file.outputStream().use { output -> openStream().use { it.copyTo(output) } }
            return operation(file)
        } finally { file.delete() }
    }
    /** Only for rollback of a newly constructed, unpublished asset. */
    fun discard() {}
}
internal class MemoryAttachment(bytes: ByteArray): AttachmentPayload {
    private val bytes=bytes.copyOf()
    override val id=sha256(this.bytes)
    override val size get()=bytes.size
    override fun openStream(): InputStream=ByteArrayInputStream(bytes)
}

/**
 * Process-lifetime owned scratch. The directory must stay available while assets/streams are live.
 * Cleaner is a fallback for unreachable copies, not crash cleanup or permission to clear active storage.
 */
internal class StoredAttachment private constructor(private val file: File,override val id: String,override val size: Int): AttachmentPayload {
    private class Cleanup(private val file: File): Runnable { override fun run() { file.delete() } }
    @Suppress("unused") private val cleanup=cleaner.register(this,Cleanup(file))
    override fun discard() { cleanup.clean() }
    override fun <T> withVerifiedFile(directory: File,operation: (File)->T): T {
        try {
            openStream().use { source ->
                val buffer=ByteArray(64*1024)
                while(true) {
                    if(Thread.currentThread().isInterrupted) throw IOException("Asset access cancelled")
                    if(source.read(buffer)<0) break
                }
            }
            return operation(file)
        } finally { java.lang.ref.Reference.reachabilityFence(this) }
    }
    override fun openStream(): InputStream {
        val source=file.inputStream()
        // A live stream retains its owner, so automatic cleanup cannot remove its backing file.
        return object: InputStream() {
            private val owner=this@StoredAttachment
            private val digest=MessageDigest.getInstance("SHA-256")
            private var count=0;private var verified=false;private var closed=false
            private val single=ByteArray(1)
            override fun read(): Int { val n=read(single,0,1);return if(n<0) -1 else single[0].toInt() and 255 }
            override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                if(closed) throw IOException("Attachment stream is closed")
                if(offset<0 || length<0 || offset>bytes.size-length) throw IndexOutOfBoundsException()
                if(length==0) return 0
                val n=source.read(bytes,offset,length)
                if(n>0) {
                    if(n>owner.size-count) throw IOException("Stored attachment length changed")
                    digest.update(bytes,offset,n);count+=n
                } else if(n<0 && !verified) {
                    if(count!=owner.size || hex(digest.digest())!=owner.id) throw IOException("Stored attachment checksum mismatch")
                    verified=true
                }
                return n
            }
            override fun available(): Int=if(closed) 0 else minOf(source.available(),owner.size-count)
            override fun close() { if(!closed) { closed=true;source.close() } }
        }
    }
    companion object {
        private val cleaner by lazy { Cleaner.create() }
        private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT,it.toInt() and 255) }
        fun read(input: InputStream,directory: File,limit: Int): StoredAttachment {
            require(limit>0)
            fun interrupted() { if(Thread.currentThread().isInterrupted) throw IOException("Attachment import cancelled") }
            interrupted()
            require(directory.isDirectory) { "Attachment storage directory is unavailable" }
            val file=File.createTempFile("attachment-owned-",".bin",directory)
            try {
                val digest=MessageDigest.getInstance("SHA-256");var size=0
                file.outputStream().use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        interrupted()
                        var n=input.read(buffer,0,minOf(buffer.size,limit-size+1))
                        if(n<0) break
                        if(n==0) { val value=input.read();if(value<0) break;buffer[0]=value.toByte();n=1 }
                        require(n<=limit-size) { "Attached files must be at most ${limit/(1024*1024)} MiB" }
                        output.write(buffer,0,n);digest.update(buffer,0,n);size+=n
                    }
                }
                interrupted();require(size>0) { "The file is empty" }
                return StoredAttachment(file,hex(digest.digest()),size)
            } catch(error: Throwable) { file.delete();throw error }
        }
    }
}
