package dev.ownednotes.app

import android.media.MediaRecorder
import dev.ownednotes.core.AttachmentAsset
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Process-owned foreground recording. Drafts survive failed attachment/save and process restart. */
internal class AudioRecording(private val app: NotesApplication) {
    companion object { /** The status before anything has been recorded; the Study panel shows a shorter line for it. */ const val READY="Record a lecture clip on this device" }
    interface Backend {
        fun start(file:File,onLimit:()->Unit)
        fun pause()
        fun resume()
        fun stop()
        fun release()
    }
    private class Microphone(private val app:NotesApplication):Backend {
        private var recorder:MediaRecorder?=null
        override fun start(file:File,onLimit:()->Unit) {
            val r=MediaRecorder(app);recorder=r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100);r.setAudioEncodingBitRate(96000)
            r.setMaxFileSize(AttachmentAsset.MAX_BYTES.toLong()-64*1024)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _,what,_ -> if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)app.main.post(onLimit) }
            r.setOnErrorListener { _,_,_ -> app.main.post(onLimit) }
            r.prepare();r.start()
        }
        override fun pause(){recorder!!.pause()}
        override fun resume(){recorder!!.resume()}
        override fun stop(){recorder!!.stop()}
        override fun release(){recorder?.release();recorder=null}
    }
    enum class State { IDLE, RECORDING, PAUSED, FINISHING, SAVING }
    var state=State.IDLE;private set
    var revision=0;private set
    var status=READY;private set
    internal var testFactory:(()->Backend)?=null
    val needsPermission get()=testFactory==null
    val active get()=state==State.RECORDING || state==State.PAUSED
    val occupied get()=state!=State.IDLE
    private val repo get()=app.activeDocument
    private val directory get()=File(app.storageRoot,"audio-drafts").apply {mkdirs()}
    private var backend:Backend?=null
    private var draft:File?=null
    private var notebookId=""
    private var pageId=""
    private var pending:AttachmentAsset?=null
    private var pendingName=""
    internal var clock:()->Long={android.os.SystemClock.elapsedRealtime()}
    private var startedAt=0L
    private var pausedAt=0L
    private var pausedMs=0L
    fun captureLink():dev.ownednotes.core.AudioLink? = if(state==State.RECORDING)draft?.let {dev.ownednotes.core.AudioLink(it.name,(clock()-startedAt-pausedMs).coerceIn(0,86_400_000L))}else null
    private var observing=false
    private val changed={onRepositoryChanged()}
    private var recoveryCache:List<File>?=null
    val drafts:List<File> get()=recoveryCache ?: directory.listFiles().orEmpty().filter {it.isDirectory && File(it,"clip.m4a").length()>0 && File(it,"target.json").isFile}.sortedBy {it.name}.also {recoveryCache=it}
    private fun publish(text:String) {status=text;revision++;repo.recordingChanged(occupied)}
    fun start():Boolean {
        if(occupied || repo.readOnly || !repo.canChangeNotebook)return false
        val note=repo.session?.notebook?:return false
        if(note.attachmentAssets.size>=AttachmentAsset.MAX_FILES || note.attachmentAssets.sumOf {it.size.toLong()}>=AttachmentAsset.MAX_TOTAL_BYTES) {
            publish("Recording needs room for another attached file");return false
        }
        notebookId=note.id;pageId=repo.selectedPageId?:return false
        val folder=File(directory,UUID.randomUUID().toString()).apply {mkdirs()}
        File(folder,"target.json").writeText(JSONObject().put("notebookId",notebookId).put("pageId",pageId).put("name","Recording ${java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss",java.util.Locale.ROOT).format(java.util.Date())}-${folder.name.take(8)}.m4a").toString())
        draft=folder
        return try {
            backend=(testFactory?.invoke()?:Microphone(app))
            backend!!.start(File(folder,"clip.m4a")) {if(active)stop()}
            startedAt=clock();pausedMs=0;pausedAt=0
            app.audioPlayback.stop()
            state=State.RECORDING;publish("Recording · stays on this device until notebook sync");true
        }catch(error:Exception){backend?.release();backend=null;state=State.IDLE;publish("Could not record: ${error.message}. Any partial clip is kept locally.");false}
    }
    fun togglePause() {
        if(!active)return
        try {if(state==State.RECORDING){backend!!.pause();pausedAt=clock();state=State.PAUSED;publish("Recording paused")}else{backend!!.resume();pausedMs+=(clock()-pausedAt).coerceAtLeast(0);state=State.RECORDING;publish("Recording")}}
        catch(error:Exception){stop();status="Recorder failed: ${error.message}. Clip retained locally."}
    }
    fun stop() {
        if(!active)return
        state=State.FINISHING;publish("Saving recording…")
        val stopped=runCatching {backend!!.stop()};backend?.release();backend=null
        if(stopped.isFailure){fail("Recording could not finish; partial clip kept locally");return}
        loadDraft()
    }
    /** Explicit recovery in the current notebook; never opens or modifies a different notebook. */
    fun recover(folder:File):Boolean {
        if(occupied || !repo.canChangeNotebook || repo.readOnly || folder !in drafts)return false
        val target=runCatching {JSONObject(File(folder,"target.json").readText())}.getOrNull()?:return false
        if(target.optString("notebookId")!=repo.session?.notebook?.id)return false
        notebookId=target.getString("notebookId");pageId=target.getString("pageId");draft=folder
        state=State.FINISHING;publish("Recovering recording…");loadDraft();return true
    }
    fun recoverableHere():List<File> = drafts.filter {runCatching {JSONObject(File(it,"target.json").readText()).optString("notebookId")==repo.session?.notebook?.id}.getOrDefault(false)}
    private fun loadDraft() {
        val file=File(draft!!,"clip.m4a")
        app.io.execute {
            val result=runCatching {file.inputStream().use {AttachmentAsset.fromStream(it,app.attachmentDirectory)}}
            app.main.post {result.onSuccess {pending=it;if(!observing){observing=true;repo.listen(changed)}else onRepositoryChanged()}.onFailure {fail("Clip kept locally: ${it.message}")}}
        }
    }
    private fun onRepositoryChanged() {
        if((state==State.FINISHING || state==State.SAVING) && !repo.savingLocally && repo.status.startsWith("Save failed:")){fail("Recording kept locally; retry notebook save, then recover it in Study");return}
        if(!repo.canCommitRecording)return
        val asset=pending?:return
        if(repo.session?.notebook?.id!=notebookId){fail("Clip kept locally; open its original notebook to recover it");return}
        if(state==State.SAVING){
            val note=repo.session!!.notebook
            if(note.pages.any {p->p.attachments.any {it.assetId==asset.id && it.name==pendingName}}){
                val finished=draft;pending=null;draft=null;state=State.IDLE;recoveryCache=null
                if(observing){repo.unlisten(changed);observing=false}
                publish("Recording saved inside notebook")
                // Only our checked, fixed-name draft children; no recursive deletion of computed paths.
                finished?.let {folder->app.io.execute {File(folder,"clip.m4a").delete();File(folder,"target.json").delete();folder.delete()}}
            }
            return
        }
        if(state!=State.FINISHING)return
        state=State.SAVING
        val result=runCatching {pendingName=JSONObject(File(draft!!,"target.json").readText()).getString("name");repo.insertRecordedClip(notebookId,pageId,asset,pendingName,draft!!.name)}
        if(result.getOrDefault(false))onRepositoryChanged()else fail("Clip kept locally: ${result.exceptionOrNull()?.message?:"could not attach"}. Recover it from Study.")
    }
    private fun fail(text:String){pending=null;state=State.IDLE;if(observing){repo.unlisten(changed);observing=false};recoveryCache=null;publish(text)}
}
