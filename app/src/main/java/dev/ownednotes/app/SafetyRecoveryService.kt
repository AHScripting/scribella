package dev.ownednotes.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import dev.ownednotes.core.NotebookLibrary
import java.io.File

/** Separate process: a broken editor process cannot prevent ordinary recovery copies or provider delivery. */
class SafetyRecoveryService : JobService() {
    companion object {
        private const val PERIODIC=48121
        private const val SOON=48122
        private val scheduleLock=Any()
        fun destination(app:NotesApplication):android.net.Uri? = runCatching {
            val file=File(app.storageRoot,"safety-copies/destination.json")
            org.json.JSONObject(file.readText()).optString("tree").takeIf {it.isNotBlank()}?.let(android.net.Uri::parse)
        }.getOrNull()
        private val scheduling=java.util.concurrent.atomic.AtomicBoolean()
        private val requests=java.util.concurrent.atomic.AtomicLong()
        private val schedulerWorker=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"notes-recovery-schedule").apply {isDaemon=true}}
        fun schedule(app:NotesApplication) {
            if(!app.automaticSafetyCopies)return
            requests.incrementAndGet()
            if(!scheduling.compareAndSet(false,true))return
            schedulerWorker.execute {
                var handled=0L
                try {do {handled=requests.get();scheduleNow(app)}while(handled!=requests.get())}
                finally {scheduling.set(false);if(handled!=requests.get())schedule(app)}
            }
        }
        private fun scheduleNow(app:NotesApplication) {
            // Derived copies are best effort. A scheduler/storage failure must never prevent Library startup or a native save.
            runCatching { synchronized(scheduleLock) {
                val root=File(app.storageRoot,"safety-copies").apply {mkdirs()}
                // SharedPreferences caches are process-local. Publish the current authorized destination explicitly.
                val tree=LibrarySync.folderUri(app) ?: LibraryBackup.folderUri(app)
                val config=org.json.JSONObject().put("tree",tree?.toString() ?: "").toString().toByteArray(Charsets.UTF_8)
                val atomic=android.util.AtomicFile(File(root,"destination.json"))
                val out=atomic.startWrite()
                try {out.write(config);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
                File(root,"refresh-requested").apply {if(!exists())createNewFile();setLastModified(System.currentTimeMillis())}
                val scheduler=app.getSystemService(JobScheduler::class.java)
                val component=ComponentName(app,SafetyRecoveryService::class.java)
                if(scheduler.getPendingJob(PERIODIC)==null) {
                    check(scheduler.schedule(JobInfo.Builder(PERIODIC,component).setPersisted(true).setPeriodic(15*60_000L).build())==JobScheduler.RESULT_SUCCESS){"Could not schedule recovery copies"}
                }
                if(scheduler.getPendingJob(SOON)==null) {
                    check(scheduler.schedule(JobInfo.Builder(SOON,component).setMinimumLatency(1000).setOverrideDeadline(60_000).build())==JobScheduler.RESULT_SUCCESS){"Could not schedule recovery refresh"}
                }
                File(root,"scheduling-error.txt").delete()
            } }.onFailure {failure ->
                android.util.Log.e("NotesRecovery","Recovery scheduling failed; earlier copies retained",failure)
                runCatching {File(app.storageRoot,"safety-copies/scheduling-error.txt").writeText("Recovery refresh could not be scheduled. Earlier copies remain in Downloads / ${app.getString(R.string.safety_folder)}.")}
            }
        }

    }
    @Volatile private var generation=0
    private var working=false
    private var activeJob=-1
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onStartJob(params:JobParameters):Boolean {
        if(working)return false
        working=true;activeJob=params.jobId
        val current=++generation
        val app=application as NotesApplication
        check(app.isRecoveryProcess)
        Thread({
            try {
                val library=NotebookLibrary(File(app.storageRoot,"notebooks"))
                var passStarted=System.currentTimeMillis()
                app.safetyCopies.requestAll(library.entries())
                val end=android.os.SystemClock.elapsedRealtime()+8*60_000
                while(current==generation && android.os.SystemClock.elapsedRealtime()<end) {
                    Thread.sleep(200)
                    if(app.safetyCopies.outstanding==0 && app.safetyCopies.cloudOutstanding==0) {
                        if(File(app.storageRoot,"safety-copies/refresh-requested").lastModified()<=passStarted)break
                        passStarted=System.currentTimeMillis();app.safetyCopies.requestAll(library.entries())
                    }
                }
                main.post {if(current==generation){working=false;jobFinished(params,app.safetyCopies.outstanding>0 || app.safetyCopies.cloudOutstanding>0)}}
            }catch(e:Exception) {
                android.util.Log.e("NotesRecovery","Recovery copies could not refresh; earlier copies retained",e)
                main.post {if(current==generation){working=false;jobFinished(params,true)}}
            }
        },"notes-recovery-job").start()
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {if(params.jobId==activeJob){generation++;working=false};return true}
}
