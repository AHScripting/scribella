package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.zip.CRC32

/** Private local write-ahead log. No source PDF/image bytes, sync metadata, or notebook schema changes.
 * Calls are serialized by the repository. append() syncs before a completed edit is acknowledged;
 * acknowledge() follows the ordinary atomic native save, preserving every newer record.
 */
class RecoveryJournal private constructor(private val file:File, initial:Notebook, private val nativeSource:File?=null, private val cacheDirectory:File?=null) {
    @Serializable private data class PageChange(val page:Page,val order:List<String>,val strokes:List<InkStroke>)
    @Serializable private data class Change(val version:Int=1,val notebookId:String,val before:String,val after:String,
        val title:String,val pages:List<String>,val changed:List<PageChange>,val pdfs:List<String>,val images:List<String>,val attachments:List<String>)
    @Serializable private data class StrokeDigest(val id:String,val sha:String)
    @Serializable private data class PageDigest(val id:String,val sha:String,val strokes:List<StrokeDigest>)
    @Serializable private data class CacheData(val version:Int=1,val notebookId:String,val sourceSha:String,val root:String,val pages:List<PageDigest>)
    @Serializable private data class CacheEnvelope(val payload:String,val sha:String)
    var usedFingerprintCache=false;private set
    /** P614: [NEEDS_CHECKPOINT] is an edit too large for one record or the journal's room (Match pen size on a long
     * notebook rescales every stroke); like new sources it is made durable by the ordinary save before more input. */
    enum class Append { UNCHANGED, RECORDED, NEEDS_ASSET_CHECKPOINT, NEEDS_CHECKPOINT }
    private val json=Json { encodeDefaults=true }
    private var fingerprintVersion=2
    private val binaryStroke=RecoveryStrokeFingerprint(json)
    private val pageHashes=IdentityHashMap<Page,String>()
    private val strokeHashes=IdentityHashMap<InkStroke,String>()
    private val records=ArrayList<Change>()
    private var latest=initial
    private var sourceBase=initial
    private var availablePdfs=initial.assets.map { it.id }.toSet()
    private var availableImages=initial.imageAssets.map { it.id }.toSet()
    private var availableAttachments=initial.attachmentAssets.map { it.id }.toSet()
    var recoveredEdits=0;private set
    var warning:String?=null;private set
    /** Same lowercase digest bytes without invoking Formatter32 times for every stroke. */
    private fun hex(bytes:ByteArray):String {
        val characters=CharArray(bytes.size*2)
        bytes.forEachIndexed {index,byte->
            val value=byte.toInt() and 255
            characters[index*2]=HEX[value ushr 4];characters[index*2+1]=HEX[value and 15]
        }
        return characters.concatToString()
    }
    private fun sha(bytes:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun fingerprint(note:Notebook):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun part(value:String) { val b=value.toByteArray();digest.update(java.nio.ByteBuffer.allocate(4).putInt(b.size).array());digest.update(b) }
        part(note.id);part(note.title)
        note.assets.sortedBy { it.id }.forEach { part("pdf:${it.id}:${it.size}") }
        note.imageAssets.sortedBy { it.id }.forEach { part("image:${it.id}:${it.size}") }
        note.attachmentAssets.sortedBy { it.id }.forEach { part("attachment:${it.id}:${it.size}") }
        note.pages.forEach { page ->
            val hash=pageHashes[page] ?: run {
                val values=StringBuilder(json.encodeToString(Page.serializer(),page.copy(strokes=emptyList())))
                page.strokes.forEach { stroke ->
                    val hash=strokeHashes[stroke] ?: (if(fingerprintVersion==1) sha(json.encodeToString(InkStroke.serializer(),stroke).toByteArray())
                        else hex(binaryStroke.digest(stroke))).also { strokeHashes[stroke]=it }
                    values.append(':').append(hash)
                }
                sha(values.toString().toByteArray()).also { pageHashes[page]=it }
            }
            part(hash)
        }
        // Keep only a bounded number of old immutable identities; never retain an entire edit history here.
        if(pageHashes.size>maxOf(1024,note.pages.size*2)) {
            val live=java.util.Collections.newSetFromMap(IdentityHashMap<Page,Boolean>()).apply { addAll(note.pages) }
            pageHashes.keys.retainAll(live)
        }
        val count=note.pages.sumOf { it.strokes.size }
        if(strokeHashes.size>maxOf(1024,count*2)) {
            val live=java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke,Boolean>()).apply { note.pages.forEach { addAll(it.strokes) } }
            strokeHashes.keys.retainAll(live)
        }
        return hex(digest.digest())
    }
    private fun sha(input:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
        return hex(digest.digest())
    }
    private fun cacheFile():File?=cacheDirectory?.let {File(it,"${latest.id}.fingerprints")}
    private fun loadFingerprintCache(note:Notebook):Boolean {
        val source=nativeSource ?: return false
        val cache=cacheFile() ?: return false
        if(!cache.isFile || cache.length()>MAX_CACHE_BYTES || Files.isSymbolicLink(cache.toPath()))return false
        val result=runCatching {
            val envelope=json.decodeFromString(CacheEnvelope.serializer(),cache.readText())
            require(sha(envelope.payload.toByteArray())==envelope.sha)
            val data=json.decodeFromString(CacheData.serializer(),envelope.payload)
            require(data.version==fingerprintVersion && data.notebookId==note.id && data.root.matches(HASH))
            require(source.inputStream().use {sha(it)}==data.sourceSha)
            require(data.pages.size==note.pages.size)
            data.pages.zip(note.pages).forEach {(cached,page)->
                require(cached.id==page.id && cached.sha.matches(HASH) && cached.strokes.size==page.strokes.size)
                cached.strokes.zip(page.strokes).forEach {(digest,stroke)->require(digest.id==stroke.id && digest.sha.matches(HASH))}
            }
            data.pages.zip(note.pages).forEach {(cached,page)->
                pageHashes[page]=cached.sha
                cached.strokes.zip(page.strokes).forEach {(digest,stroke)->strokeHashes[stroke]=digest.sha}
            }
            require(fingerprint(note)==data.root)
            true
        }
        if(result.getOrDefault(false))return true
        // Never keep partially loaded hashes from an invalid/unrelated cache.
        pageHashes.clear();strokeHashes.clear();return false
    }
    private fun publishFingerprintCache(note:Notebook) {
        val source=nativeSource ?: return
        val cache=cacheFile() ?: return
        // The caller serializes journal operations; freeze only hashes, never mutable journal state.
        runCatching {
            val root=fingerprint(note)
            val pages=note.pages.map {page->PageDigest(page.id,requireNotNull(pageHashes[page]),page.strokes.map {StrokeDigest(it.id,requireNotNull(strokeHashes[it]))})}
            val input=source.inputStream() // pins this successful atomic generation before another save replaces its path
            try {cacheWorker.execute {
                runCatching {
                    val sourceSha=input.use {sha(it)}
                    val data=CacheData(version=fingerprintVersion,notebookId=note.id,sourceSha=sourceSha,root=root,pages=pages)
                    val payload=json.encodeToString(CacheData.serializer(),data)
                    val envelope=json.encodeToString(CacheEnvelope.serializer(),CacheEnvelope(payload,sha(payload.toByteArray())))
                    require(envelope.length<=MAX_CACHE_BYTES)
                    val parent=cache.parentFile
                    require((parent.isDirectory || parent.mkdirs()) && !Files.isSymbolicLink(parent.toPath()))
                    val pending=File.createTempFile("fingerprints-", ".pending",parent)
                    try {
                        FileOutputStream(pending).use {out->out.write(envelope.toByteArray(Charsets.UTF_8));out.fd.sync()}
                        Files.move(pending.toPath(),cache.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
                    } finally {pending.delete()}
                }.also {runCatching {input.close()}}
            }} catch(failure:Throwable){input.close();throw failure}
        } // A derived-cache failure must never fail an open, native save or journal acknowledgement.
    }
    private fun changes(before:Notebook,after:Notebook):List<PageChange> {
        val old=before.pages.associateBy { it.id }
        return after.pages.filter { old[it.id] !== it }.map { page ->
            val strokes=old[page.id]?.strokes.orEmpty().associateBy { it.id }
            PageChange(page.copy(strokes=emptyList()),page.strokes.map { it.id },page.strokes.filter { strokes[it.id] !== it })
        }
    }
    private fun apply(change:Change,note:Notebook):Notebook {
        val pages=note.pages.associateBy { it.id }.toMutableMap()
        change.changed.forEach { delta ->
            val strokes=pages[delta.page.id]?.strokes.orEmpty().associateBy { it.id }.toMutableMap()
            delta.strokes.forEach { strokes[it.id]=it }
            require(delta.order.distinct().size==delta.order.size)
            pages[delta.page.id]=delta.page.copy(strokes=delta.order.map { requireNotNull(strokes[it]) })
        }
        fun <T> selected(ids:List<String>,values:List<T>,id:(T)->String):List<T> {
            val by=values.associateBy(id);return ids.map { requireNotNull(by[it]) }
        }
        return note.copy(title=change.title,pages=change.pages.map { requireNotNull(pages[it]) },
            assets=selected(change.pdfs,sourceBase.assets) { it.id },imageAssets=selected(change.images,sourceBase.imageAssets) { it.id },
            attachmentAssets=selected(change.attachments,sourceBase.attachmentAssets) { it.id })
    }
    private fun encode(change:Change)=json.encodeToString(Change.serializer(),change).toByteArray().also { require(it.size in 1..MAX_RECORD) { "Recovery record exceeds budget" } }
    private fun write(output:DataOutputStream,bytes:ByteArray) { output.writeInt(bytes.size);output.writeInt(CRC32().apply { update(bytes) }.value.toInt());output.write(bytes) }
    private fun ensureOwned() {
        require((file.parentFile.isDirectory || file.parentFile.mkdirs()) && !Files.isSymbolicLink(file.parentFile.toPath()))
        require(!Files.isSymbolicLink(file.toPath()))
    }
    fun append(next:Notebook):Append {
        require(next.id==latest.id)
        if(next === latest) return Append.UNCHANGED
        if(next.assets.map { it.id }.toSet()!=availablePdfs || next.imageAssets.map { it.id }.toSet()!=availableImages || next.attachmentAssets.map { it.id }.toSet()!=availableAttachments) return Append.NEEDS_ASSET_CHECKPOINT
        val before=fingerprint(latest);val after=fingerprint(next)
        if(before==after) { latest=next;return Append.UNCHANGED }
        val changed=changes(latest,next)
        // A lower bound of the record's size, so a whole-notebook change is never encoded into one huge string first.
        val least=changed.sumOf { page -> page.strokes.sumOf { it.samples.size*MIN_SAMPLE_BYTES+it.outlines.sumOf { o -> o.size.toLong() }*MIN_POINT_BYTES } }
        if(least>MAX_RECORD) return Append.NEEDS_CHECKPOINT
        val change=Change(version=fingerprintVersion,notebookId=next.id,before=before,after=after,title=next.title,pages=next.pages.map { it.id },changed=changed,
            pdfs=next.assets.map { it.id },images=next.imageAssets.map { it.id },attachments=next.attachmentAssets.map { it.id })
        val bytes=json.encodeToString(Change.serializer(),change).toByteArray()
        if(bytes.size>MAX_RECORD) return Append.NEEDS_CHECKPOINT
        ensureOwned()
        if(file.length()+bytes.size+8>MAX_JOURNAL) return Append.NEEDS_CHECKPOINT
        FileOutputStream(file,true).use { output -> val data=DataOutputStream(output);write(data,bytes);data.flush();output.fd.sync() }
        records.add(change);latest=next
        return Append.RECORDED
    }
    private fun replace(remaining:List<Change>) {
        ensureOwned()
        if(remaining.isEmpty()) { if(file.exists() && !file.delete()) throw IOException("Recovery records could not be retired");return }
        val pending=File.createTempFile("recovery-", ".pending",file.parentFile)
        try {
            FileOutputStream(pending).use { output -> val data=DataOutputStream(output);remaining.forEach { write(data,encode(it)) };data.flush();output.fd.sync() }
            Files.move(pending.toPath(),file.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    /** Retire only the prefix actually included in a successful atomic package save. */
    fun acknowledge(saved:Notebook) {
        require(saved.id==latest.id)
        val savedHash=fingerprint(saved)
        val index=records.indexOfLast { it.after==savedHash }
        if(index<0) {publishFingerprintCache(saved);return}
        val remaining=records.drop(index+1);replace(remaining);records.clear();records.addAll(remaining)
        publishFingerprintCache(saved)
    }
    /** Called only after the ordinary package save durably includes newly introduced sources. */
    fun assetCheckpoint(saved:Notebook) {
        require(saved.id==latest.id);replace(emptyList());records.clear();latest=saved;sourceBase=saved
        availablePdfs=saved.assets.map { it.id }.toSet();availableImages=saved.imageAssets.map { it.id }.toSet();availableAttachments=saved.attachmentAssets.map { it.id }.toSet()
        publishFingerprintCache(saved)
    }
    private fun preserveAndTrim(valid:List<Change>) {
        if(file.exists()) file.copyTo(File(file.parentFile,file.name+".preserved-"+UUID.randomUUID()))
        replace(valid)
    }
    private fun readRecords() {
        if(!file.exists()) return
        ensureOwned();require(file.length()<=MAX_JOURNAL) { "Recovery journal exceeds budget" }
        var damaged=false
        DataInputStream(file.inputStream().buffered()).use { input ->
            while(true) {
                val first=input.read();if(first<0) break
                try {
                    val length=(first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    require(length in 1..MAX_RECORD)
                    val crc=input.readInt();val bytes=ByteArray(length);input.readFully(bytes)
                    require(CRC32().apply { update(bytes) }.value.toInt()==crc)
                    val record=json.decodeFromString(Change.serializer(),bytes.toString(Charsets.UTF_8))
                    require(record.version in 1..2 && record.notebookId==latest.id && record.before.matches(HASH) && record.after.matches(HASH))
                    require(records.isEmpty() || (record.version==fingerprintVersion && records.last().after==record.before))
                    if(records.isEmpty())fingerprintVersion=record.version
                    records.add(record)
                } catch(_:EOFException) { damaged=true;break } catch(_:IllegalArgumentException) { damaged=true;break }
            }
        }
        if(damaged) { warning="An unfinished recovery record was preserved; complete edits were recovered.";preserveAndTrim(records) }
    }
    private fun recover(session:DocumentSession) {
        val current=fingerprint(session.notebook)
        val start=records.indexOfFirst { it.before==current }.takeIf { it>=0 }
            ?: records.indexOfLast { it.after==current }.takeIf { it>=0 }?.plus(1)
        if(start==null) {
            if(records.isNotEmpty()) { warning="Recovery records did not match this saved notebook and were preserved.";preserveAndTrim(emptyList());records.clear() }
            return
        }
        for(index in start until records.size) {
            val change=records[index]
            val result=runCatching {
                val next=apply(change,session.notebook)
                require(fingerprint(next)==change.after) { "Recovery content did not match its fingerprint" }
                session.applyRecoveredNotebook(next)
            }
            if(result.isFailure) {
                warning="A recovery record could not be applied and was preserved; earlier complete edits were recovered."
                val valid=records.take(index);preserveAndTrim(valid);records.clear();records.addAll(valid)
                break
            }
            recoveredEdits++
        }
        latest=session.notebook
    }
    companion object {
        private val HASH=Regex("[a-f0-9]{64}")
        private const val MAX_RECORD=16*1024*1024
        private const val MAX_JOURNAL=64L*1024*1024
        // The fewest JSON bytes a sample ({"x":0.0,"y":0.0,"timeMs":0,"pressure":0.0,"tilt":0.0,"orientation":0.0}) or point takes.
        private const val MIN_SAMPLE_BYTES=60L
        private const val MIN_POINT_BYTES=17L
        private const val HEX="0123456789abcdef"
        private const val MAX_CACHE_BYTES=32L*1024*1024
        private val cacheWorker=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"notes-recovery-fingerprints").apply {isDaemon=true;priority=Thread.MIN_PRIORITY}}
        internal fun awaitFingerprintCacheWrites(){cacheWorker.submit {}.get(30,java.util.concurrent.TimeUnit.SECONDS)}
        fun open(directory:File,session:DocumentSession,nativeSource:File?=null,cacheDirectory:File?=null):RecoveryJournal {
            require(session.notebook.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            return RecoveryJournal(File(directory,"${session.notebook.id}.journal"),session.notebook,nativeSource,cacheDirectory).also {
                // Select the digest contract from the validated journal prefix before loading derived hashes.
                it.readRecords()
                it.usedFingerprintCache=it.loadFingerprintCache(session.notebook)
                if(!it.usedFingerprintCache){it.fingerprint(session.notebook);it.publishFingerprintCache(session.notebook)}
                it.recover(session)
            }
        }
    }
}
