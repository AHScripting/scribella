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
    internal var lastBatch:NotebookRepository.BatchImport?=null;private set
    internal val pending get()=requests.isNotEmpty() || running
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
        if(found.isEmpty() || found.size>50 || found.any {it.scheme!="content"}) {notices.add("Share up to 50 notebook files using read access from the sending app.");return}
        if(requests.size>=8){notices.add("Please wait for the current notebook imports before sharing more.");return}
        requests.add(Request(found))
    }
    fun drain(activity:MainActivity) {
        if(posted || activity.appLockGate.locked || activity.isDestroyed || activity.isFinishing)return
        if(requests.isEmpty() && results.isEmpty() && notices.isEmpty())return
        posted=true
        app.main.post {
            posted=false
            if(activity.isDestroyed || activity.isFinishing){repo.mediaChanged();return@post}
            if(activity.appLockGate.locked || activity.ink.isEditing || !repo.canChangeNotebook || running)return@post
            if(notices.isNotEmpty())activity.message(notices.removeFirst())
            if(results.isNotEmpty())activity.showNotebookImportResult(results.removeFirst())
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
            running=true
            if(repo.importNotebookFiles(files,openImported=false) {batch->
                running=false;lastBatch=batch;results.add(batch);repo.mediaChanged()
            })requests.removeFirst() else running=false
        }
    }
}
