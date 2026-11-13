package dev.ownednotes.app

import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * User-approved controls inside the existing Study panel; no separate recording screen. P615 (board 9): one row, "Audio"
 * with its status and Record (Stop and Pause while recording) on the row; recoverable and saved clips list under it.
 */
internal class AudioRecordingControls(private val activity:MainActivity,frame:EditorFrame):LinearLayout(activity) {
    private val app=activity.application as NotesApplication
    private val audio=app.audio
    val record=Kit.button(activity,if(audio.active) "Stop" else "Record",Kit.Button.PRIMARY,small=true, onClick={activity.recordAudio()}).apply {
        contentDescription=if(audio.active) "Stop recording" else "Start recording"
        isEnabled=audio.active || (!audio.occupied && app.activeDocument.canChangeNotebook && !app.activeDocument.readOnly)
        setOnClickListener {activity.recordAudio()}
    }
    val pause=Kit.button(activity,if(audio.state==AudioRecording.State.PAUSED) "Resume" else "Pause",Kit.Button.TINTED,small=true, onClick={audio.togglePause()}).apply {
        contentDescription=if(audio.state==AudioRecording.State.PAUSED) "Resume recording" else "Pause recording"
        isEnabled=audio.active;setOnClickListener {audio.togglePause()}
    }
    init {
        orientation=VERTICAL
        val clips=app.activeDocument.session?.notebook?.pages.orEmpty().flatMap {page->page.attachments.filter {it.mimeType.startsWith("audio/")}.map {page to it}}
        val detail=when {
            audio.status!=AudioRecording.READY -> audio.status
            clips.isEmpty() -> "Kept in this notebook"
            else -> "${clips.size} clip${if(clips.size==1) "" else "s"} in this notebook"
        }
        addView(StudyRow(activity,"Audio",StudyRow.square(activity,"M12 4a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V7a3 3 0 0 1 3-3zM6 11a6 6 0 0 0 12 0M12 17v3",0xffff3b30.toInt()),detail).apply {
            if(audio.active)trail(pause);trail(record)
        })
        audio.recoverableHere().filter { !audio.occupied }.forEach {folder->
            addView(Kit.row(activity,"Recover saved clip",detail="Unfinished recording kept on this device",menu=true,onClick={if(!audio.recover(folder))activity.message("Open the original notebook and try again")}))
        }
        clips.forEach {(page,clip)->addView(Kit.row(activity,clip.name,detail="Audio · tap to open player",menu=true,onClick={
            if(!audio.occupied && app.activeDocument.canChangeNotebook){app.activeDocument.selectPage(page.id);app.audioPlayback.play(clip.id,0)}
        }))}
        if(app.audioPlayback.state!=AudioPlayback.State.IDLE)addView(AudioPlaybackControls(activity),LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply {
            activity.dpi(12f).let {marginStart=it;marginEnd=it;bottomMargin=it}
        })
    }
}
