package dev.ownednotes.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Native-file view over the existing content-addressed transfer store, for an explicit fresh-folder opt-in.
 * FolderSync still owns local Trash, restoration and its consolidated deletion index.
 * Immutable remote recovery blobs are retained; this adapter does not add garbage collection.
 */
class ChangedPageFolder private constructor(
    private val root: FolderSync.NestedFolder,
    private val source: PackageTransferSource,
    private val cancelled: () -> Boolean,
) : FolderSync.Folder, Closeable {
    private val pinned=linkedMapOf<String,PackageTransferStore.Version>()
    private fun running() { if(cancelled()) throw IOException("Sync stopped") }
    private fun snapshot(): PackageTransferStore.Snapshot {
        running()
        return source.scan().also { require(it.incomplete==0) { "Transfer history is incomplete; retry without replacing notebooks" } }
    }
    private fun deletions(): Map<String,SyncDeletionIndex.Entry> {
        running()
        val stat=root.list()[SyncDeletionIndex.FILE_NAME] ?: throw IOException("Deletion metadata unavailable")
        require(stat.size in 1..SyncDeletionIndex.MAX_BYTES.toLong()) { "Invalid deletion metadata size" }
        return root.read(SyncDeletionIndex.FILE_NAME).use { SyncDeletionIndex.decode(it.readNBytes(SyncDeletionIndex.MAX_BYTES+1)) }
    }
    private fun singleHeads(): Map<String,PackageTransferStore.Version> = snapshot().heads.mapValues { (_, heads) ->
        require(heads.size==1) { "Concurrent versions arrived; retry to preserve both copies" }; heads.single()
    }
    private fun stat(version: PackageTransferStore.Version) = FolderSync.Stat(version.commit.notebook.size,
        version.commit.notebook.sha256.take(15).toLong(16).coerceAtLeast(1))
    /** Preserve all divergent contents with P342 before acknowledging any observed heads.
     * A concurrent later head is never acknowledged by this publication; the next scan detects it.
     */
    private fun prepare() {
        val before=snapshot()
        for((id,heads) in before.heads) {
            if(heads.size<2) continue
            require(heads.size<=16) { "Too many concurrent versions; existing copies retained" }
            if(heads.map { it.commit.notebook.sha256 }.distinct().size>1) {
                val copies=source.preserveConflicts(id)
                val represented=copies.keys.mapNotNull { before.ready[it]?.notebook?.sha256 }.toSet()
                require(heads.all { it.commit.notebook.sha256 in represented }) { "Conflict heads changed; retry before acknowledgement" }
                val after=snapshot()
                require(after.heads[id].orEmpty().map { it.hash }.toSet()==heads.map { it.hash }.toSet()) { "Concurrent edit arrived; copies retained, retry sync" }
                require(copies.values.all { copy -> after.ready[copy.hash]==copy.commit }) { "Conflict copies were not durable" }
            }
            val selected=heads.minBy { it.hash }
            source.upload(id,source.download(selected),heads.map { it.hash })
        }
        pinned.putAll(singleHeads())
    }
    override fun list(): Map<String,FolderSync.Stat> {
        val deleted=deletions()
        val result=linkedMapOf<String,FolderSync.Stat>()
        val heads=singleHeads()
        require(heads.mapValues { it.value.hash }==pinned.mapValues { it.value.hash }) { "Remote notebooks changed during sync; retry from a fresh snapshot" }
        heads.forEach { (id,version) ->
            val entry=deleted[id]
            if(entry?.deleted!=true || entry.sha256!=version.commit.notebook.sha256) result["$id.inknote"]=stat(version)
        }
        result[SyncDeletionIndex.FILE_NAME]=root.list()[SyncDeletionIndex.FILE_NAME] ?: throw IOException("Deletion metadata disappeared")
        return result
    }
    private fun id(name:String):String = name.removeSuffix(".inknote").also {
        require(name=="$it.inknote" && it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid notebook name" }
    }
    override fun read(name:String):InputStream {
        running()
        if(name==SyncDeletionIndex.FILE_NAME) return root.read(name)
        val version=pinned[id(name)] ?: throw IOException("Notebook was not in this sync snapshot")
        return source.download(version).inputStream()
    }
    override fun write(name:String,from:File):FolderSync.Stat {
        running()
        if(name==SyncDeletionIndex.FILE_NAME) return root.write(name,from)
        val id=id(name)
        // Only acknowledge the exact version observed by this sync, never an unseen concurrent edit.
        val parents=pinned[id]?.let { listOf(it.hash) } ?: emptyList()
        val published=source.upload(id,from,parents).version
        pinned[id]=published
        return stat(published)
    }
    override fun delete(name:String) {
        running()
        val id=id(name);val version=pinned[id] ?: throw IOException("Unknown notebook deletion")
        val entry=deletions()[id]
        require(entry?.deleted==true && entry.sha256==version.commit.notebook.sha256) { "Deletion was not durably published" }
        require(singleHeads()[id]?.commit?.notebook?.sha256==entry.sha256) { "Notebook changed during deletion; copy retained" }
        // Visibility is controlled by the consolidated index. Immutable blobs remain recovery history.
    }
    override fun close()=source.close()
    companion object {
        fun open(root:FolderSync.NestedFolder,workspace:File,attachments:File,sourceKey:String,
            initialize:Boolean=false,cancelled:()->Boolean={false}):ChangedPageFolder {
            val source=PackageTransferSource.open(root,workspace,attachments,sourceKey,initialize,cancelled)
            return try { ChangedPageFolder(root,source,cancelled).also { it.prepare() } }
            catch(error:Throwable) { source.close();throw error }
        }
    }
}
