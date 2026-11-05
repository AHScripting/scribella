package dev.ownednotes.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.UUID

/**
 * Dedicated derived-storage session, never a notebook/provider folder. Hold for as long as any of its
 * assets/streams can be used. Close only after their last use (PC command); Android holds for process
 * lifetime. OS locks protect other live processes; later opens reclaim abandoned known copies.
 */
class AttachmentWorkspace private constructor(val directory: File,private val channel: FileChannel,private val lease: FileLock): Closeable {
    private var closed=false
    @Synchronized override fun close() {
        if(closed) return
        closed=true
        // A caller-inserted/unknown file is never removed, even from our own session.
        if(known(directory)) copies(directory).forEach { it.delete() }
        try { lease.release() } finally { channel.close() }
        if(known(directory) && copies(directory).isEmpty()) { File(directory,LEASE).delete();directory.delete() }
    }
    companion object {
        private const val LEASE=".lease"
        private val SESSION=Regex("session-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val COPY=Regex("attachment-owned-[0-9]+\\.bin")
        private fun copies(directory: File)=directory.listFiles().orEmpty().filter { it.name!=LEASE }
        private fun known(directory: File): Boolean {
            if(!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) || !directory.name.matches(SESSION)) return false
            val files=directory.listFiles() ?: return false
            return files.any { it.name==LEASE } && files.all {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && (it.name==LEASE || it.name.matches(COPY))
            }
        }
        private fun reclaim(directory: File) {
            if(!known(directory)) return
            try {
                RandomAccessFile(File(directory,LEASE),"rw").channel.use { channel ->
                    val lock=try { channel.tryLock() } catch(_: OverlappingFileLockException) { null }
                    if(lock==null) return
                    lock.use { if(known(directory)) copies(directory).forEach { it.delete() } }
                }
                if(known(directory) && copies(directory).isEmpty()) { File(directory,LEASE).delete();directory.delete() }
            } catch(_: IOException) { /* In-use/inaccessible copies stay for a later startup. */ }
        }
        /** Root must be a dedicated private derived-storage area, never a library or sync folder. */
        fun open(root: File): AttachmentWorkspace {
            require(!Files.isSymbolicLink(root.toPath())) { "Attachment workspace cannot be a symbolic link" }
            require(root.isDirectory || root.mkdirs()) { "Cannot create attachment workspace" }
            val manager=File(root,".manager-lock")
            require(!Files.isSymbolicLink(manager.toPath())) { "Invalid attachment workspace lock" }
            // Serialize session creation with stale scanning, including across processes.
            synchronized(AttachmentWorkspace::class.java) {
                RandomAccessFile(manager,"rw").channel.use { owner -> owner.lock().use {
                    root.listFiles().orEmpty().forEach { reclaim(it) }
                    val directory=File(root,"session-${UUID.randomUUID()}")
                    require(directory.mkdir()) { "Cannot create attachment session" }
                    var channel: FileChannel?=null
                    try {
                        channel=RandomAccessFile(File(directory,LEASE),"rw").channel
                        return AttachmentWorkspace(directory,channel,channel.lock())
                    } catch(error: Throwable) {
                        channel?.close();File(directory,LEASE).delete();directory.delete();throw error
                    }
                } }
            }
        }
    }
}
