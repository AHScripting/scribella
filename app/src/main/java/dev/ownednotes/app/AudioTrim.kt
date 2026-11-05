package dev.ownednotes.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.ownednotes.core.AttachmentAsset
import java.io.File
import java.nio.ByteBuffer

/** Lossless AAC packet copy into a new M4A; the original is never opened for writing. */
internal object AudioTrim {
    fun create(asset:AttachmentAsset,startMs:Long,endMs:Long,cache:File):File {
        require(startMs>=0 && endMs-startMs>=250 && endMs<=86_400_000) { "Choose at least a quarter-second of audio" }
        val input=File.createTempFile("audio-trim-source-",".m4a",cache)
        val output=File.createTempFile("audio-trim-",".m4a",cache)
        val extractor=MediaExtractor();var muxer:MediaMuxer?=null
        try {
            input.outputStream().use {asset.writeTo(it)}
            input.inputStream().use {extractor.setDataSource(it.fd)}
            require(extractor.trackCount==1) { "Only single-track AAC recordings can be trimmed here" }
            val format=extractor.getTrackFormat(0)
            require(format.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm") { "Only AAC recordings can be trimmed here; the original is kept" }
            val duration=format.getLong(MediaFormat.KEY_DURATION)
            require(endMs*1000<=duration+50_000) { "Trim end is outside the recording" }
            extractor.selectTrack(0);extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setLong(MediaFormat.KEY_DURATION,(endMs-startMs)*1000)
            val writer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
            val track=writer.addTrack(format);writer.start()
            val buffer=ByteBuffer.allocateDirect(1024*1024);val info=MediaCodec.BufferInfo()
            var first=-1L;var last=-1L;var samples=0
            while(true) {
                val time=extractor.sampleTime
                if(time<0 || time>=endMs*1000)break
                if(time>=startMs*1000) {
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0) { "Encrypted audio cannot be trimmed here" }
                    buffer.clear();val size=extractor.readSampleData(buffer,0)
                    if(size<0)break
                    require(size<=buffer.capacity() && time>=last) { "Invalid audio packet" }
                    if(first<0)first=time
                    info.set(0,size,time-first,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    writer.writeSampleData(track,buffer,info);last=time;samples++
                }
                if(!extractor.advance())break
            }
            require(samples>=2) { "Choose a longer audio range" }
            writer.stop();return output
        }catch(error:Exception){output.delete();throw error}
        finally {runCatching {muxer?.release()};extractor.release();input.delete()}
    }
    fun attach(activity:MainActivity,clipId:String,startMs:Long,endMs:Long):Boolean {
        val app=activity.application as NotesApplication;val repo=app.activeDocument
        if(app.audio.occupied || !repo.canChangeNotebook || repo.readOnly)return false
        val note=repo.session?.notebook?:return false
        val pair=note.pages.firstNotNullOfOrNull {p->p.attachments.firstOrNull {it.id==clipId}?.let {p to it}}?:return false
        val asset=note.attachmentAssets.firstOrNull {it.id==pair.second.assetId}?:return false
        app.audioPlayback.stop();repo.setFileBusy(true)
        app.io.execute {
            val result=runCatching {val file=create(asset,startMs,endMs,app.cacheDir);try {file.inputStream().use {AttachmentAsset.fromStream(it,app.attachmentDirectory)}}finally {file.delete()}}
            app.main.post {
                repo.setFileBusy(false)
                result.onSuccess {trimmed->
                    val added=runCatching {repo.insertRecordedClip(note.id,pair.first.id,trimmed,"${pair.second.name.substringBeforeLast('.').take(180)} - trim ${java.util.UUID.randomUUID().toString().take(8)}.m4a",java.util.UUID.randomUUID().toString())}
                    activity.message(if(added.getOrDefault(false)) "Trim saved as a separate clip. Undo removes it." else "Could not add trim: ${added.exceptionOrNull()?.message?:"notebook changed"}. Original kept.")
                }.onFailure {activity.message(it.message?:"Could not trim recording; original kept")}
            }
        }
        return true
    }
}
