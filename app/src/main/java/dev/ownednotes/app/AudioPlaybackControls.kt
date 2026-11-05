package dev.ownednotes.app

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.SeekBar

/** Shared Glass controls inside Study, with local progress updates rather than toolbar refreshes. */
internal class AudioPlaybackControls(activity:MainActivity):LinearLayout(activity) {
    private val app=activity.application as NotesApplication
    private val playback=app.audioPlayback
    val pause=Kit.button(activity,if(playback.state==AudioPlayback.State.PLAYING) "Pause playback" else "Resume playback",Kit.Button.TINTED){playback.togglePause()}
    val stop=Kit.button(activity,"Stop playback",Kit.Button.PLAIN){playback.stop()}
    private fun time(ms:Int)="${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"
    private val position=Kit.text(activity,"",Kit.Type.CAPTION,secondary=true)
    private val slider=Kit.slider(activity,"Seek",0f,playback.durationMs.coerceAtLeast(1).toFloat(),playback.positionMs.toFloat(),format={""}) {playback.seek(it.toLong())}
    private val progress get()=slider.getChildAt(1) as SeekBar
    private val tick=object:Runnable {override fun run(){
        position.text="${time(playback.positionMs)} / ${time(playback.durationMs)}"
        if(!progress.isPressed)progress.progress=(playback.positionMs*1000L/playback.durationMs.coerceAtLeast(1)).toInt().coerceIn(0,1000)
        postDelayed(this,500)
    }}
    val speeds=Kit.segmented(activity,listOf("0.5×","1×","1.5×","2×"),listOf(.5f,1f,1.5f,2f).indexOf(playback.speed).coerceAtLeast(0)) {i->playback.setSpeed(listOf(.5f,1f,1.5f,2f)[i])}.apply { contentDescription="Playback speed" }
    val replay=Kit.Switch(activity,playback.replayEnabled,"Replay writing") {enabled->
        check(playback.setReplay(enabled)) {"Wait for writing and saving to finish"}
    }
    internal var trimStartMs=0L
    internal var trimEndMs=playback.durationMs.toLong()
    val trim=Kit.button(activity,"Save trimmed copy",Kit.Button.TINTED){
        val id=playback.clipId
        if(id==null || trimEndMs-trimStartMs<250)activity.message("Choose at least a quarter-second of audio")
        else if(!AudioTrim.attach(activity,id,trimStartMs,trimEndMs))activity.message("Wait for notebook saving before trimming")
    }
    init {
        orientation=VERTICAL
        addView(Kit.text(activity,playback.status,Kit.Type.CAPTION,secondary=true))
        addView(position);addView(slider)
        addView(Kit.section(activity,"Playback speed"));addView(speeds)
        val enabled=playback.state==AudioPlayback.State.PLAYING || playback.state==AudioPlayback.State.PAUSED
        for(i in 0 until speeds.childCount)speeds.getChildAt(i).isEnabled=enabled
        addView(pause,LayoutParams(MATCH_PARENT,WRAP_CONTENT));addView(stop,LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        pause.isEnabled=playback.state==AudioPlayback.State.PLAYING || playback.state==AudioPlayback.State.PAUSED
        slider.isEnabled=pause.isEnabled;progress.isEnabled=pause.isEnabled
        replay.isEnabled=enabled && app.activeDocument.canChangeNotebook
        addView(Kit.row(activity,"Replay writing",detail="Current page only. Editing pauses; other notes stay visible.",trailing=replay))
        if(enabled) {
            addView(Kit.section(activity,"Trim a copy"))
            addView(Kit.text(activity,"Original audio and handwriting links stay unchanged. AAC recordings only.",Kit.Type.CAPTION,secondary=true))
            addView(Kit.slider(activity,"Start",0f,playback.durationMs.coerceAtLeast(1).toFloat(),0f,format={time(it.toInt())}) {trimStartMs=it.toLong()})
            addView(Kit.slider(activity,"End",0f,playback.durationMs.coerceAtLeast(1).toFloat(),playback.durationMs.toFloat(),format={time(it.toInt())}) {trimEndMs=it.toLong()})
            trim.isEnabled=!app.activeDocument.readOnly && app.activeDocument.canChangeNotebook
            addView(trim,LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        }
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();post(tick)}
    override fun onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow()}
}
