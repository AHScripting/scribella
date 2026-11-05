package dev.ownednotes.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.graphics.pdf.PdfRenderer
import dev.ownednotes.core.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Derived, ordinary files: usable in Files/any PDF reader even when the app cannot start. */
internal class SafetyCopies(private val app: NotesApplication, private val directory:String = "Download/${app.getString(R.string.safety_folder)}/", private val minimumIntervalMillis:Long = 15_000) {
    companion object {
        // P458: the top level holds only the newest PDF of each notebook under its own name.
        const val OLDER = "Older copies and editable files/"
        internal fun plainName(title:String)=exportFileName(title).take(70).ifBlank {"Notebook"}
        private const val PROVIDER_GAP_MINIMUM=5*60_000L
        private const val PROVIDER_GAP_PER_MIB=30_000L
        /** P559: a large pair is costly to send, so it goes less often. Pairs up to 10 MiB keep the five-minute gap. */
        internal fun providerGapMillis(pairBytes:Long):Long = maxOf(PROVIDER_GAP_MINIMUM,pairBytes*PROVIDER_GAP_PER_MIB/(1024*1024))
    }
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> background("notes-readable-copies",r) }
    private val cloud = Executors.newSingleThreadExecutor { r -> background("notes-readable-cloud",r) }
    private fun background(name:String,r:Runnable)=Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run()},name).apply {isDaemon=true}
    private val pending = linkedSetOf<String>()
    private var running = false
    private val prefs by lazy { SafetyState(File(app.storageRoot,"safety-copies/state.json")) }
    private val nextAllowed=mutableMapOf<String,Long>()
    private val publisher by lazy { MediaPublisher(app, directory+OLDER) }
    @Volatile internal var outstanding = 0; private set
    @Volatile internal var cloudOutstanding = 0; private set
    internal var forceForTest = false
    fun requestAll(entries: List<LibraryEntry>) {
        if (!app.automaticSafetyCopies && !forceForTest)return
        if (!app.isRecoveryProcess && !forceForTest) { SafetyRecoveryService.schedule(app);return }
        entries.filter {it.availableOffline && it.error==null}.forEach {request(it.id)}
    }
    fun request(id: String) {
        if (!app.automaticSafetyCopies && !forceForTest) return
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        if (!app.isRecoveryProcess && !forceForTest) {SafetyRecoveryService.schedule(app);return}
        synchronized(pending) {
            pending += id; outstanding=pending.size + if(running) 1 else 0
            if(running)return
            running=true
            worker.schedule({drain()},1,TimeUnit.SECONDS)
        }
    }
    private fun drain() {
        // State/storage failure must not leave the queue permanently marked as running.
        try {
            while (true) {
                val id = synchronized(pending) {
                    if (pending.isEmpty()) { running=false; outstanding=0; return }
                    val now=SystemClock.elapsedRealtime()
                    val due=pending.firstOrNull {(nextAllowed[it] ?: 0)<=now}
                    if (due==null) {
                        worker.schedule({drain()},(pending.minOf {nextAllowed[it] ?: now}-now).coerceAtLeast(1),TimeUnit.MILLISECONDS)
                        return
                    }
                    due.also {pending.remove(it);outstanding=pending.size+1;nextAllowed[it]=now+minimumIntervalMillis}
                }
                runCatching {prefs.edit().putLong("workingAt",System.currentTimeMillis()).apply()}
                runCatching {capture(id)}.onFailure { failure ->
                    if(failure is SnapshotMoved) {synchronized(pending){pending+=id;outstanding=pending.size+1};return@onFailure}
                    android.util.Log.e("NotesRecovery","Earlier copies retained for $id",failure)
                    runCatching {prefs.edit().putString("error:$id", "Recovery copy not updated: ${failure.message ?: "storage unavailable"}. Earlier copies were kept.").apply()}
                }
            }
        } finally {
            synchronized(pending) {
                // A scheduled drain owns pending work. Only clear the persisted flag at idle.
                if (pending.isEmpty()) {running=false;outstanding=0;runCatching {prefs.edit().putLong("workingAt",0).apply()}}
            }
        }
    }
    private class SnapshotMoved:IOException("Notebook changed while preparing recovery snapshot")
    private fun hash(file:File):String = file.inputStream().use { input ->
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(64*1024)
        while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun capture(id:String) {
        val source=File(File(app.storageRoot,"notebooks"),"$id.inknote")
        if(!source.isFile)return // A deleted/released notebook never deletes its earlier safety copies.
        val snapshotRoot=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"safety-").toFile()
        val work=File(snapshotRoot,"snapshot.inknote")
        var pdf:File?=null
        try {
            // Pin a complete native generation and copy the journal into our private snapshot before parsing.
            val sourceVersion=source.inputStream().use {input->
                val stat=android.system.Os.fstat(input.fd)
                work.outputStream().use {input.copyTo(it)}
                stat
            }
            val journalDirectory=File(snapshotRoot,"journal").apply {mkdirs()}
            val journalCopy=File(journalDirectory,"$id.journal")
            val liveJournal=File(app.storageRoot,"recovery-journals/$id.journal")
            try {liveJournal.inputStream().use {input->journalCopy.outputStream().use {input.copyTo(it)}}}
            catch(missing:java.io.FileNotFoundException){if(liveJournal.exists())throw missing}
            val currentVersion=android.system.Os.stat(source.absolutePath)
            if(sourceVersion.st_ino!=currentVersion.st_ino || sourceVersion.st_dev!=currentVersion.st_dev)throw SnapshotMoved()
            val sourceSha=hash(work)
            val journalSha=if(journalCopy.isFile)hash(journalCopy)else ""
            val before=prefs.getString(id,null)?.let {runCatching {JSONObject(it)}.getOrNull()}
            if(before?.optInt("format")==2 && before.optString("sourceSha")==sourceSha && before.optString("journalSha")==journalSha && publisher.exists(Uri.parse(before.getString("pdf"))) && publisher.exists(Uri.parse(before.getString("native")))) {
                arrange(before);mirror(id,before);return
            }
            var note=NotebookPackage.read(work,File(app.cacheDir,"safety-assets").apply {mkdirs()})
            require(note.id==id){"Notebook identity differs"}
            var recovered=0
            var nativeCopy=work
            if(journalCopy.isFile) {
                val session=DocumentSession(note)
                val journal=RecoveryJournal.open(journalDirectory,session) // only the COPY may be trimmed; the live journal is never opened for writing
                check(journal.warning?.let {it.contains("did not match") || it.contains("could not be applied")}!=true){"Recovery records could not be applied; previous copies retained"}
                recovered=journal.recoveredEdits;note=session.notebook
                if(recovered>0) {
                    val merged=File(snapshotRoot,"merged.inknote")
                    merged.outputStream().use {NotebookPackage.write(note,it)}
                    nativeCopy=merged // Keep work immutable: lazy source assets may still read its original ZIP offsets.
                }
            }
            val sha=hash(nativeCopy)
            pdf=PdfExport.create(note,app.cacheDir,includeReviewedHandwriting=false)
            ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount==note.pages.size){"PDF page count differs"}
                renderer.openPage(0).close();renderer.openPage(renderer.pageCount-1).close()
            } }
            val stamp=SimpleDateFormat("yyyy-MM-dd HH.mm.ss",Locale.ROOT).format(Date())
            val stem=plainName(note.title)+" "+stamp
            val record=publisher.publish(nativeCopy,pdf,stem)
            val result=JSONObject().put("id",id).put("format",2).put("sourceSha",sourceSha).put("journalSha",journalSha).put("recoveredEdits",recovered).put("sha",sha).put("pairBytes",nativeCopy.length()+pdf.length()).put("pdf",record.first.toString()).put("native",record.second.toString())
                .put("title",note.title).put("pages",note.pages.size).put("copiedAt",System.currentTimeMillis()).put("stem",stem)
            // Retain two verified derived generations. Originals, Trash and notebook history are never removed.
            val prior=before?.optJSONObject("previous")
            if(before!=null)result.put("previous",JSONObject().put("pdf",before.getString("pdf")).put("native",before.getString("native")).put("stem",before.optString("stem")))
            prefs.edit().putString(id,result.toString()).putLong("last",System.currentTimeMillis()).remove("error:$id").apply()
            prior?.let {old->listOf("pdf","native").forEach {key->runCatching {app.contentResolver.delete(Uri.parse(old.getString(key)),null,null)}}}
            arrange(result)
            mirror(id,result)
        } finally { pdf?.delete();snapshotRoot.deleteRecursively() }
    }
    /** Newest PDF on top under the notebook's name; editable and earlier copies below. Best effort and repeatable: a copy stays valid wherever it is. */
    internal fun arrange(record:JSONObject) {
        val older=directory+OLDER
        val pdf=Uri.parse(record.getString("pdf"))
        val previous=record.optJSONObject("previous")
        val previousPdf=previous?.let {Uri.parse(it.getString("pdf"))}
        val previousName=previous?.optString("stem")?.takeIf {it.isNotBlank()}?.let {"$it.pdf"}
        runCatching {
            val (path,name)=publisher.location(pdf) ?: return@runCatching
            if(path==directory && name!=record.optString("stem")+".pdf")return@runCatching
            // Rename the earlier PDF before the newest takes its name, so the top level is never without one.
            if(previousPdf!=null && publisher.location(previousPdf)?.first==directory)publisher.move(previousPdf,directory,previousName)
            publisher.move(pdf,directory,plainName(record.optString("title"))+".pdf")
        }
        previousPdf?.let {runCatching {publisher.move(it,older,previousName)}}
        listOfNotNull(record.optString("native"),previous?.optString("native")).filter {it.isNotBlank()}.forEach {native->runCatching {
            val uri=Uri.parse(native)
            // Copies made before P448's fix were named .inknote.zip by Android; give them back their importable name.
            publisher.move(uri,older,publisher.location(uri)?.second?.takeIf {it.endsWith(".inknote.zip")}?.removeSuffix(".zip"))
        }}
    }
    private val cloudActive=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cloudDue=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal fun mirror(id:String,record:JSONObject) {
        val tree=SafetyRecoveryService.destination(app) ?: return
        val identity=tree.toString()+":"+record.getString("sha")
        if(prefs.getString("cloud:$id",null)==identity)return
        // Coalesce repeated local saves. A bounded follow-up sends the newest verified pair.
        val last=prefs.getLong("cloud-time:$id",0)
        val wait=last+providerGapMillis(record.optLong("pairBytes",0))-System.currentTimeMillis()
        if(wait>0) {
            if(cloudDue.add(id))worker.schedule({cloudDue.remove(id);prefs.getString(id,null)?.let {mirror(id,JSONObject(it))}},wait,TimeUnit.MILLISECONDS)
            return
        }
        if(!cloudActive.add(id))return
        synchronized(this){cloudOutstanding++}
        cloud.execute {
            var native:File?=null
            var pdf:File?=null
            try {
                val nativeFile=File.createTempFile("safety-cloud-", ".inknote",app.cacheDir).also {native=it}
                val pdfFile=File.createTempFile("safety-cloud-", ".pdf",app.cacheDir).also {pdf=it}
                app.contentResolver.openInputStream(Uri.parse(record.getString("native")))!!.use {i->nativeFile.outputStream().use {i.copyTo(it)}}
                app.contentResolver.openInputStream(Uri.parse(record.getString("pdf")))!!.use {i->pdfFile.outputStream().use {i.copyTo(it)}}
                val folder=LibraryBackup.TreeFolder(app,tree,app.getString(R.string.safety_folder))
                val stem=record.getString("stem")
                val names=publishMirrorPair(folder,nativeFile,pdfFile,stem)
                val before=prefs.getString("cloud-record:$id",null)?.let {JSONObject(it)}
                val next=JSONObject().put("tree",tree.toString()).put("names",org.json.JSONArray(names))
                if(before?.optString("tree")==tree.toString())next.put("previous",before.getJSONArray("names"))
                // Record the fully verified replacement before pruning only our own older derived pair.
                prefs.edit().putString("cloud-record:$id",next.toString()).putString("cloud:$id",identity)
                    .putLong("cloud-time:$id",System.currentTimeMillis()).putLong("cloud-last",System.currentTimeMillis()).remove("cloud-error:$id").apply()
                if(before?.optString("tree")==tree.toString())before.optJSONArray("previous")?.let {old ->
                    repeat(old.length()){index->runCatching {folder.delete(old.getString(index))}}
                }
            } catch(e:Exception) {
                runCatching {prefs.edit().putString("cloud-error:$id","Recovery provider copy not updated: ${e.message}. Offline copies remain available.").apply()}
            } finally {native?.delete();pdf?.delete();cloudActive.remove(id);synchronized(this){cloudOutstanding--}}
        }
    }
    internal fun publishMirrorPair(folder:LibraryBackup.Folder,native:File,pdf:File,stem:String):List<String> {
        val created=mutableListOf<String>()
        val staged=mutableListOf<Pair<String,String>>()
        val unique=stem+"-"+java.util.UUID.randomUUID().toString().take(8)
        try {
            for ((file,ext) in listOf(native to "inknote",pdf to "pdf")) {
                val proposed="$unique.$ext.pending"
                created+=proposed
                val used=folder.write(proposed,file)
                if(used!=proposed){created.remove(proposed);created+=used}
                val verified=File.createTempFile("safety-verify-", ".$ext",app.cacheDir)
                try {folder.read(used).use {input->verified.outputStream().use {input.copyTo(it)}};check(hash(verified)==hash(file)){"Provider copy checksum differs"}}
                finally {verified.delete()}
                staged+=used to "$unique.$ext"
            }
            // Until both readbacks pass, files end in .pending and cannot be mistaken for finished PDFs/notebooks.
            return staged.map {(from,to)->folder.rename(from,to).also {published->created.remove(from);created+=published}}
        } catch(failure:Exception) {
            created.forEach {runCatching {folder.delete(it)}}
            throw failure
        }
    }
    private fun providerStatus():String {
        val tree=SafetyRecoveryService.destination(app) ?: return "No additional provider folder is configured. Offline copies remain available."
        val all=records()
        val sent=all.count {record->val id=record.optString("id");id.isNotBlank() && prefs.getString("cloud:$id",null)==tree.toString()+":"+record.optString("sha")}
        val pending=all.size-sent
        return if(pending>0) "Provider copies: $sent of ${all.size} verified; $pending awaiting delivery. Offline PDFs are ready."
            else if(all.isNotEmpty()) "All ${all.size} copies verified through the folder provider; OneDrive cloud arrival may follow."
            else "No provider copy has finished yet."
    }
    fun status():String {
        val problem=runCatching {File(app.storageRoot,"safety-copies/scheduling-error.txt").takeIf {it.isFile}?.readText()}.getOrNull()
            ?: prefs.errors("error:").firstOrNull()
        val last=prefs.getLong("last",0)
        val workingAt=prefs.getLong("workingAt",0)
        return problem ?: if(workingAt>0 && System.currentTimeMillis()-workingAt>10*60_000) "The last recovery refresh did not finish. Earlier copies remain available in Downloads / ${app.getString(R.string.safety_folder)}."
        else if(outstanding>0 || workingAt>0) "Updating recovery PDFs; earlier copies remain in Downloads / ${app.getString(R.string.safety_folder)}."
        else if(last==0L) "No readable recovery copy has finished yet."
        else "Newest PDF of each notebook: Downloads / ${app.getString(R.string.safety_folder)}. Editable and earlier copies: its folder “${OLDER.trimEnd('/')}”. Most recent completed copy "+java.text.DateFormat.getDateTimeInstance().format(Date(last))+
            "\n"+providerStatus()+(prefs.errors("cloud-error:").firstOrNull()?.let {"\n$it"} ?: "")
    }
    internal fun records():List<JSONObject> = prefs.records()
    fun openFiles(activity:MainActivity) {
        val folder=Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FNotes%20Lab%20Safety")
        // The system Files app owns access to its directory provider. We must not try to grant an unowned tree URI.
        val intent=Intent(Intent.ACTION_VIEW).setDataAndType(folder,android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { activity.startActivity(intent) }.onFailure {activity.message("Open My Files > Downloads > ${app.getString(R.string.safety_folder)}. "+status())}
    }
    private class SafetyState(file:File) {
        private val atomic=android.util.AtomicFile(file)
        private val lock=Any()
        private fun read()=runCatching {atomic.baseFile.inputStream().bufferedReader().use {JSONObject(it.readText())}}.getOrElse {JSONObject()}
        fun getString(key:String,fallback:String?):String?=synchronized(lock){read().let {if(it.has(key))it.getString(key)else fallback}}
        fun getLong(key:String,fallback:Long):Long=synchronized(lock){read().optLong(key,fallback)}
        fun errors(prefix:String):List<String> = synchronized(lock){val d=read();d.keys().asSequence().filter {it.startsWith(prefix)}.map {d.getString(it)}.toList()}
        fun records():List<JSONObject> = synchronized(lock){val d=read();d.keys().asSequence().filter {it.matches(Regex("[a-zA-Z0-9-]{1,80}")) && !it.contains(':')}.mapNotNull {k->runCatching {JSONObject(d.getString(k))}.getOrNull()}.toList()}
        fun edit()=Editor()
        inner class Editor {
            private val changes=linkedMapOf<String,Any?>()
            fun putString(k:String,v:String)=apply {changes[k]=v}
            fun putLong(k:String,v:Long)=apply {changes[k]=v}
            fun remove(k:String)=apply {changes[k]=null}
            fun apply()=synchronized(lock){
                val d=read();changes.forEach {(k,v)->if(v==null)d.remove(k)else d.put(k,v)}
                atomic.baseFile.parentFile!!.mkdirs()
                val out=atomic.startWrite()
                try {out.write(d.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            }
        }
    }
    internal class MediaPublisher(private val app:NotesApplication,private val directory:String) {
        private val resolver get()=app.contentResolver
        fun exists(uri:Uri):Boolean=runCatching {resolver.openInputStream(uri)?.use {true} ?: false}.getOrDefault(false)
        fun location(uri:Uri):Pair<String,String>? = resolver.query(uri,arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        /** Moves or renames one of our own published copies in place; its Uri, bytes and records stay the same. */
        fun move(uri:Uri,path:String,name:String?) {
            val (currentPath,currentName)=location(uri) ?: return
            if(currentPath==path && (name==null || name==currentName))return
            check(resolver.update(uri,ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH,path)
                if(name!=null) {put(MediaStore.MediaColumns.DISPLAY_NAME,name);if(name.endsWith(".inknote"))put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")}
            },null,null)==1){"Recovery copy not moved"}
        }
        fun publish(native:File,pdf:File,stem:String,afterFirst:()->Unit = {}):Pair<Uri,Uri> {
            val created=mutableListOf<Uri>()
            fun add(file:File,extension:String,mime:String):Uri {
                val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,"$stem.$extension");put(MediaStore.MediaColumns.MIME_TYPE,mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,directory);put(MediaStore.MediaColumns.IS_PENDING,1)
                }) ?: throw IOException("Cannot create recovery copy")
                created+=uri
                val descriptor=resolver.openFileDescriptor(uri,"w") ?: throw IOException("Cannot write recovery copy")
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {out->file.inputStream().use {it.copyTo(out)};out.flush();out.fd.sync()}
                val expected=MessageDigest.getInstance("SHA-256");val actual=MessageDigest.getInstance("SHA-256")
                file.inputStream().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;expected.update(b,0,n)}}
                (resolver.openInputStream(uri) ?: throw IOException("Cannot verify recovery copy")).use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;actual.update(b,0,n)}}
                check(expected.digest().contentEquals(actual.digest())){"Recovery copy checksum differs"}
                return uri
            }
            try {
                val pdfUri=add(pdf,"pdf","application/pdf");afterFirst();val nativeUri=add(native,"inknote","application/octet-stream")
                created.forEach {check(resolver.update(it,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1){"Recovery copy not published"}}
                return pdfUri to nativeUri
            } catch(e:Exception) {created.forEach {runCatching {resolver.delete(it,null,null)}};throw e}
        }
    }
}
