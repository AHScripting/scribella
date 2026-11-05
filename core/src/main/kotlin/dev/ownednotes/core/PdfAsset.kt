package dev.ownednotes.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Immutable content-addressed original PDF; leased snapshots share it without copying. */
class PdfAsset private constructor(private val payload: AttachmentPayload) {
    val id get()=payload.id
    val size get()=payload.size
    fun openStream(): InputStream=payload.openStream()
    fun writeTo(output: OutputStream) { openStream().use { it.copyTo(output) } }
    /** Synchronous read-only borrow. Do not modify, retain or return the file beyond this callback.
     * Stored originals are verified before access; memory-backed assets use a temporary copy.
     * The supplied owned workspace must outlive the asset and every consumer.
     */
    fun <T> withVerifiedFile(scratch: File,operation: (File)->T): T=payload.withVerifiedFile(scratch,operation)
    internal fun discardUnpublished() { payload.discard() }
    override fun equals(other: Any?)=other is PdfAsset && id==other.id && size==other.size
    override fun hashCode()=id.hashCode()
    companion object {
        const val LEGACY_MAX_BYTES=8*1024*1024
        const val SCHEMA27_MAX_BYTES=64*1024*1024
        const val MEMORY_MAX_BYTES=SCHEMA27_MAX_BYTES
        const val MAX_BYTES=512*1024*1024
        private val HEADER="%PDF-".toByteArray(Charsets.US_ASCII)
        fun fromBytes(bytes: ByteArray): PdfAsset {
            require(bytes.size in 5..MEMORY_MAX_BYTES) { "Use file-backed import for PDFs larger than 64 MiB" }
            require(bytes.copyOfRange(0,5).contentEquals(HEADER)) { "Missing PDF header" }
            return PdfAsset(MemoryAttachment(bytes))
        }
        /** Input remains caller-owned; an invalid/incomplete source discards only its new unpublished copy. */
        fun fromStream(input: InputStream,ownedDirectory: File): PdfAsset {
            val stored=StoredAttachment.read(input,ownedDirectory,MAX_BYTES)
            try {
                stored.openStream().use { require(it.readNBytes(5).contentEquals(HEADER)) { "Missing PDF header" } }
                return PdfAsset(stored)
            } catch(error: Throwable) { stored.discard();throw error }
        }
        fun <T> fromStream(input: InputStream,ownedDirectory: File,build: (PdfAsset)->T): T {
            val asset=fromStream(input,ownedDirectory)
            try { return build(asset) } catch(error: Throwable) { asset.discardUnpublished();throw error }
        }
    }
}

internal fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
