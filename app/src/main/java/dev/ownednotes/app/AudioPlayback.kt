package dev.ownednotes.app

import android.graphics.Path
import android.media.MediaPlayer
import dev.ownednotes.core.*
import java.io.File

/** Local-only playback; cache copies are temporary and carry no notebook mutation. */
internal class AudioPlayback(private val app:NotesApplication) {
    enum class State { IDLE, PREPARING, PLAYING, PAUSED }
    var state=State.IDLE;private set
    var revision=0;private set
    var status="Choose a recording or tap its handwriting";private set
    var clipId:String?=null;private set
    var lastRequestedMs=0L;private set
    var lastSeekMs=0L;private set
    internal var muteForTests=false
    private var owner:String?=null
    private var generation=0
    private var player:MediaPlayer?=null
    private var prepared=false
    var speed=1f;private set
    var replayEnabled=false;private set
    internal data class ReplayFrame(val clipId:String,val timeMs:Long)
    internal fun replayFrame():ReplayFrame?=if(replayEnabled)clipId?.let {ReplayFrame(it,positionMs.toLong())}else null
    fun setReplay(enabled:Boolean):Boolean {
        if(enabled && (!prepared || app.audio.occupied || !repo.canChangeNotebook))return false
        replayEnabled=enabled;repo.setReplayHeld(enabled);publish(if(enabled) "Replaying writing on this page" else "Normal writing view")
        return true
    }
    private fun clearReplay(){replayEnabled=false;repo.setReplayHeld(false)}
    internal val actuallyPlaying get()=prepared && runCatching {player?.isPlaying==true}.getOrDefault(false)
    private var file:File?=null
    private val repo get()=app.activeDocument
    val positionMs get()=if(!prepared)0 else runCatching {player?.currentPosition?:0}.getOrDefault(0)
    val durationMs get()=if(!prepared)0 else runCatching {player?.duration?.coerceAtLeast(0)?:0}.getOrDefault(0)
    init {repo.listen {if(owner!=null && owner!=repo.session?.notebook?.id)stop()}}
    private fun publish(text:String){status=text;revision++;repo.mediaChanged()}
    fun play(id:String,timeMs:Long):Boolean {
        if(app.audio.occupied){status="Stop recording before playback";return false}
        val note=repo.session?.notebook?:return false
        val card=note.pages.asSequence().flatMap {it.attachments.asSequence()}.firstOrNull {it.id==id && it.mimeType.startsWith("audio/")}
        val asset=card?.let {c->note.attachmentAssets.firstOrNull {it.id==c.assetId}}
        if(asset==null){status="This recording is unavailable; the handwriting is still kept";return false}
        stop();val token=++generation;owner=note.id;clipId=id;lastRequestedMs=timeMs.coerceAtLeast(0);lastSeekMs=0
        state=State.PREPARING;publish("Loading ${card.name}…")
        app.io.execute {
            val result=runCatching {val f=File.createTempFile("audio-play-",".m4a",app.cacheDir);try {f.outputStream().use {asset.writeTo(it)};f}catch(error:Exception){f.delete();throw error}}
            app.main.post {
                if(token!=generation){result.getOrNull()?.delete();return@post}
                result.onSuccess {source->
                    file=source
                    try {
                        val p=MediaPlayer();player=p
                        if(muteForTests)p.setVolume(0f,0f)
                        p.setOnPreparedListener {
                            if(token!=generation)return@setOnPreparedListener
                            prepared=true;seek(lastRequestedMs);if(speed!=1f)p.playbackParams=android.media.PlaybackParams().allowDefaults().setPitch(1f).setSpeed(speed);p.start();state=State.PLAYING;publish(card.name)
                        }
                        p.setOnSeekCompleteListener {if(token==generation){lastSeekMs=p.currentPosition.toLong();if(replayEnabled && state==State.PAUSED)repo.mediaChanged()}}
                        p.setOnCompletionListener {if(token==generation){clearReplay();state=State.PAUSED;publish("Recording finished")}}
                        p.setOnErrorListener {_,_,_->if(token==generation){stop();publish("Could not play this recording")};true}
                        p.setDataSource(source.path);p.prepareAsync()
                    }catch(error:Exception){stop();publish("Could not play: ${error.message}")}
                }.onFailure {stop();publish("Could not open recording: ${it.message}")}
            }
        }
        return true
    }
    fun seek(ms:Long) {
        val p=player?:return
        if(!prepared)return
        val target=ms.coerceIn(0,durationMs.toLong())
        lastRequestedMs=target
        runCatching {p.seekTo(target,MediaPlayer.SEEK_CLOSEST)}.onFailure {publish("Could not seek recording")}
    }
    fun setSpeed(value:Float):Boolean {
        if(!prepared || value !in listOf(.5f,1f,1.5f,2f))return false
        val p=player?:return false
        val paused=state==State.PAUSED
        return runCatching {
            p.playbackParams=android.media.PlaybackParams().allowDefaults().setPitch(1f).setSpeed(value)
            val acceptedSpeed=p.playbackParams.speed
            if(paused)p.pause() // Android reports speed zero after pause; retain the accepted non-zero setting.
            speed=acceptedSpeed
            publish(if(paused) "Playback paused" else "Playing at ${speed}×");true
        }.getOrElse {publish("This speed is not supported on this device");false}
    }
    fun togglePause() {
        val p=player?:return
        if(!prepared)return
        runCatching {
            when(state){
                State.PLAYING->{p.pause();state=State.PAUSED;publish("Playback paused")}
                State.PAUSED->{if(positionMs>=durationMs)seek(0);p.start();state=State.PLAYING;publish("Playing recording")}
                else->Unit
            }
        }.onFailure {stop();publish("Could not continue playback")}
    }
    fun stop() {
        if(state==State.IDLE && player==null && file==null && !replayEnabled)return
        clearReplay();generation++;prepared=false;runCatching {player?.release()};player=null
        file?.let {f->app.io.execute {f.delete()}};file=null;owner=null;clipId=null;state=State.IDLE;publish("Playback stopped")
    }
    companion object {
        internal fun visibleInReplay(stroke:InkStroke,frame:ReplayFrame?):Boolean =
            frame==null || stroke.audio?.let {it.attachmentId!=frame.clipId || it.timeMs<=frame.timeMs}!=false
        fun hit(page:Page,point:Point):AudioLink? {
            if(!point.x.isFinite() || !point.y.isFinite())return null
            val probe=Path().apply {addCircle(point.x,point.y,3f,Path.Direction.CW)}
            for(slice in page.paintSlices.asReversed()) {
                if(slice.attachments.any {it.placement.contains(point)})return null
                for(stroke in slice.drawnStrokes.asReversed()) {
                    if(slice.opacityOf(stroke)<=0f || stroke.color ushr 24==0L)continue
                    if(InkGeometry.intersects(InkGeometry.visible(stroke),probe))return stroke.audio
                }
                if(slice.textBoxes.any {slice.canSee(it.layer) && slice.objectOpacity(it.layer)>0f && it.placement.contains(point)})return null
                if(slice.images.any {slice.canSee(it.layer) && slice.objectOpacity(it.layer)>0f && it.placement.contains(point)})return null
            }
            return null
        }
    }
}
