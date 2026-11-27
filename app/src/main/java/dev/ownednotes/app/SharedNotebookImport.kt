package dev.ownednotes.app

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/** External Share is a Library operation. Queued work belongs to the process, not a destroyed activity. */
internal class SharedNotebookImport(private val app:NotesApplication) {
    private data class Request(val uris:List<Uri>)
    private val requests=ArrayDeque<Request>()
    private val seen=linkedSetOf<String>()
    private val results=ArrayDeque<NotebookRepository.BatchImport>()
    private val notices=ArrayDeque<String>()
    private var posted=false
    private var running=false
    /** P686: shared photos, copied into the app, waiting for the user to choose the notebook they go into. */
    private val photoBatches=ArrayDeque<List<java.io.File>>()
    private var copying=0
    private var choosing=false
    /** P686: the notebook question for shared photos while it is open (tests choose through it). */
    internal var photoChooser:NotebookChooser?=null;private set
    internal var lastBatch:NotebookRepository.BatchImport?=null;private set
    internal val pending get()=requests.isNotEmpty() || running || photoBatches.isNotEmpty() || copying>0 || choosing
    private val repo get()=app.repository
    fun offer(intent:Intent?,id:String) {
        if(intent?.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE) || !seen.add(id))return
        if(seen.size>64)seen.remove(seen.first())
        val found=runCatching {
            val streams=if(intent!!.action==Intent.ACTION_SEND_MULTIPLE)intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri::class.java).orEmpty()
                else listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java))
            val clip=intent.clipData
            (streams+if(streams.isEmpty() && clip!=null)(0 until minOf(clip.itemCount,51)).mapNotNull {clip.getItemAt(it).uri}else emptyList()).distinct()
        }.getOrElse {notices.add("Could not read the shared notebook. Please share it again.");return}
        val photos=found.isNotEmpty() && found.all {uri->(runCatching {app.contentResolver.getType(uri)}.getOrNull() ?: intent!!.type).orEmpty().startsWith("image/")}
        if(found.isEmpty() || found.size>50 || found.any {it.scheme!="content"}) {
            notices.add(if(photos || intent!!.type.orEmpty().startsWith("image/"))"Share up to 50 photos using read access from the sending app." else "Share up to 50 notebook files using read access from the sending app.");return
        }
        if(photos){copyPhotos(found);return}
        if(requests.size>=8){notices.add("Please wait for the current notebook imports before sharing more.");return}
        requests.add(Request(found))
    }
    /**
     * P686: copies shared photos into the app while the sending app's read access lasts. Each photo may be up to
     * [MOST_PHOTO_BYTES]; a batch that cannot be read whole is dropped with one message.
     */
    private fun copyPhotos(uris:List<Uri>) {
        copying++
        val folder=java.io.File(app.cacheDir,"scans").also {it.mkdirs()}
        app.io.execute {
            val copied=mutableListOf<java.io.File>()
            val result=runCatching {
                for(uri in uris) {
                    val file=java.io.File(folder,"shared-${java.util.UUID.randomUUID()}.jpg").also {copied+=it}
                    app.contentResolver.openInputStream(uri)?.use {input->file.outputStream().use {out->
                        val buffer=ByteArray(64*1024);var total=0L
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=MOST_PHOTO_BYTES){"A shared photo is larger than 80 MB."};out.write(buffer,0,n)}
                    }} ?: error("Cannot read a shared photo.")
                }
            }
            app.main.post {
                copying--
                result.onSuccess {photoBatches.add(copied.toList())}.onFailure {
                    copied.forEach {it.delete()}
                    notices.add((it.message?.takeIf {m->m.startsWith("A shared photo")} ?: "Could not read the shared photos.")+" Please share them again.")
                }
                repo.mediaChanged()
            }
        }
    }
    /** P686: asks which notebook the shared photos go into, then opens it on the scan review with the photos as its pages. */
    private fun choosePhotoNotebook(activity:MainActivity,files:List<java.io.File>) {
        val targets=repo.entries.filter {it.error==null && !repo.isNotebookReadOnly(it.id)}.sortedByDescending {it.modified}
        if(targets.isEmpty()){files.forEach {it.delete()};activity.message("Create a notebook first, then share the photos again.");return}
        choosing=true
        var chosen=false
        val title=if(files.size==1)"Add this photo to a notebook" else "Add ${files.size} photos to a notebook"
        photoChooser=NotebookChooser(activity,title,targets,closedThen={photoChooser=null;choosing=false;if(!chosen)files.forEach {it.delete()};repo.mediaChanged()}) {entry->
            chosen=true
            activity.scanSharedPhotos(entry.id,files)
        }.show()
    }
    fun drain(activity:MainActivity) {
        if(posted || activity.appLockGate.locked || activity.isDestroyed || activity.isFinishing)return
        if(requests.isEmpty() && results.isEmpty() && notices.isEmpty() && photoBatches.isEmpty())return
        posted=true
        app.main.post {
            posted=false
            if(activity.isDestroyed || activity.isFinishing){repo.mediaChanged();return@post}
            if(activity.appLockGate.locked || activity.ink.isEditing || !repo.canChangeNotebook || running)return@post
            if(notices.isNotEmpty())activity.message(notices.removeFirst())
            if(results.isNotEmpty())activity.showNotebookImportResult(results.removeFirst())
            if(photoBatches.isNotEmpty() && !choosing && ScanProcessing.target==null && !activity.sharedPhotosWaiting){
                activity.showLibrary(syncOnOpen=false)
                choosePhotoNotebook(activity,photoBatches.removeFirst());return@post
            }
            if(requests.isEmpty())return@post
            activity.showLibrary(syncOnOpen=false)
            val library=activity.libraryScreen?:return@post
            library.showSharedImports()
            val request=requests.first()
            val files=request.uris.map {uri->
                "Shared notebook" to {
                    val name=runCatching {app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())c.getString(0)else null}}.getOrNull()
                        ?.takeIf {it.isNotBlank()} ?: uri.lastPathSegment.orEmpty()
                    require(name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("inknote","pdf","pptx","docx")) {"Unsupported shared file: $name. Share a Scribella notebook, PDF, DOCX or PPTX."}
                    app.contentResolver.openInputStream(uri)?.use {NotebookImport.read(app.cacheDir,it,name,app.attachmentDirectory)}
                        ?: error("Cannot read $name. Please share it again with file access.")
                }
            }
            if(choosing)return@post
            running=true
            if(repo.importNotebookFiles(files,openImported=false) {batch->
                running=false;lastBatch=batch;results.add(batch);repo.mediaChanged()
            })requests.removeFirst() else running=false
        }
    }
    private companion object { const val MOST_PHOTO_BYTES=80L*1024*1024 }
}
