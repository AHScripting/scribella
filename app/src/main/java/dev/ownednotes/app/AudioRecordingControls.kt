package dev.ownednotes.app

import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/** User-approved controls inside the existing Study panel; no separate recording screen. */
internal class AudioRecordingControls(private val activity:MainActivity,frame:EditorFrame):LinearLayout(activity) {
    private val app=activity.application as NotesApplication
    private val audio=app.audio
    val record=Kit.button(activity,if(audio.active) "Stop recording" else "Start recording",Kit.Button.PRIMARY, onClick={activity.recordAudio()}).apply {
        isEnabled=audio.active || (!audio.occupied && app.activeDocument.canChangeNotebook && !app.activeDocument.readOnly)
        setOnClickListener {activity.recordAudio()}
    }
    val pause=Kit.button(activity,if(audio.state==AudioRecording.State.PAUSED) "Resume recording" else "Pause recording",Kit.Button.TINTED, onClick={audio.togglePause()}).apply {
        isEnabled=audio.active;setOnClickListener {audio.togglePause()}
    }
    init {
        orientation=VERTICAL
        addView(Kit.section(activity,"Audio recordings"))
        addView(Kit.text(activity,audio.status,Kit.Type.CAPTION,secondary=true))
        addView(record,LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply {topMargin=activity.dpi(8f)})
        if(audio.active)addView(pause,LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply {topMargin=activity.dpi(6f)})
        audio.recoverableHere().filter { !audio.occupied }.forEach {folder->
            addView(Kit.row(activity,"Recover saved clip",detail="Unfinished recording kept on this device",onClick={if(!audio.recover(folder))activity.message("Open the original notebook and try again")}))
        }
        val clips=app.activeDocument.session?.notebook?.pages.orEmpty().flatMap {page->page.attachments.filter {it.mimeType.startsWith("audio/")}.map {page to it}}
        clips.forEach {(page,clip)->addView(Kit.row(activity,clip.name,detail="Audio · tap to open player",onClick={
            if(!audio.occupied && app.activeDocument.canChangeNotebook){app.activeDocument.selectPage(page.id);app.audioPlayback.play(clip.id,0)}
        }))}
        if(app.audioPlayback.state!=AudioPlayback.State.IDLE)addView(AudioPlaybackControls(activity))
        if(clips.isEmpty() && !audio.active)addView(Kit.text(activity,"Clips stay inside this notebook. Leaving the app stops recording. Up to 64 MiB per clip.",Kit.Type.CAPTION,secondary=true))
    }
}
