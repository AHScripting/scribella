package dev.ownednotes.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.SyncCancellation

/** F204: opt-in OS-owned periodic work; no timer, account or permanent foreground service. */
internal object BackgroundSync {
    const val JOB_ID = 0x4e4c5359
    const val INTERVAL = 15 * 60 * 1000L
    private const val KEY = "sync_background"
    private fun prefs(app: NotesApplication) = app.getSharedPreferences(app.preferencesName, 0)
    fun enabled(app: NotesApplication) = prefs(app).getBoolean(KEY, false)
    fun eligible(app: NotesApplication) = enabled(app) && !LibrarySync.paused(app) && LibrarySync.hasSource(app)

    fun jobInfo(app: NotesApplication): JobInfo = JobInfo.Builder(JOB_ID, ComponentName(app, BackgroundSyncService::class.java))
        .setPeriodic(INTERVAL)
        .setPersisted(true)
        .setRequiresBatteryNotLow(true)
        .setBackoffCriteria(INTERVAL, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .apply {
            if (LibrarySync.wifiOnly(app)) setRequiredNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build())
        }.build()

    /** Tests have a separate storage root; they must never schedule/cancel a production OS job. */
    fun update(app: NotesApplication): Boolean {
        if (app.storageRoot.canonicalFile != app.filesDir.canonicalFile) return true
        return runCatching {
            val scheduler = app.getSystemService(JobScheduler::class.java)
            if (!eligible(app)) { scheduler.cancel(JOB_ID); return@runCatching true }
            val wanted = jobInfo(app)
            val old = scheduler.getPendingJob(JOB_ID)
            if (old != null && old.service == wanted.service && old.intervalMillis == wanted.intervalMillis &&
                old.isPersisted && old.isRequireBatteryNotLow && old.requiredNetwork == wanted.requiredNetwork) true
            else scheduler.schedule(wanted) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    fun setEnabled(app: NotesApplication, value: Boolean): Boolean {
        val old = enabled(app)
        prefs(app).edit().putBoolean(KEY, value).apply()
        if (update(app)) return true
        prefs(app).edit().putBoolean(KEY, old).apply()
        update(app)
        return false
    }
}

/** Same serialized transfer path as Sync now, with readiness and cancellation at operation boundaries. */
internal class BackgroundSyncRun(
    private val app: NotesApplication,
    private val folder: (() -> FolderSync.Folder)? = null,
    private val allowed: () -> Boolean = { BackgroundSync.eligible(app) },
    private val ready: () -> Boolean = { app.documentsReady },
    private val timeoutMillis: Long = 60_000L,
    private val finished: (retry: Boolean) -> Unit,
) {
    private val cancellation = SyncCancellation()
    private val startedAt = SystemClock.elapsedRealtime()
    private var completed = false
    private var syncing = false
    private val tick = Runnable { advance() }
    private var receipt: String? = null
    fun start() {
        if (receipt != null || cancellation.isCancelled || completed) return
        receipt = app.backgroundHistory.begin(LibrarySync.sourceIdentity(app))
        advance()
    }
    fun stop() {
        cancellation.cancel(); app.main.removeCallbacks(tick)
        receipt?.let { app.backgroundHistory.finish(it, LibrarySync.sourceIdentity(app), BackgroundSyncHistory.State.INTERRUPTED) }
    }
    private fun complete(retry: Boolean, state: BackgroundSyncHistory.State) {
        if (completed || cancellation.isCancelled) return
        completed = true; app.main.removeCallbacks(tick)
        receipt?.let { app.backgroundHistory.finish(it, LibrarySync.sourceIdentity(app), state) }
        finished(retry)
    }
    private fun advance() {
        if (cancellation.isCancelled || completed || syncing) return
        if (!allowed()) { complete(false, BackgroundSyncHistory.State.INTERRUPTED); return }
        if (!ready()) {
            if (SystemClock.elapsedRealtime() - startedAt >= timeoutMillis) complete(true, BackgroundSyncHistory.State.WAITING)
            else app.main.postDelayed(tick, 500L)
            return
        }
        syncing = true
        LibrarySync.sync(app, app.repository, folder = folder, continueSync = { allowed() }, cancellation = cancellation) { result ->
            // A normal periodic retry is enough for provider errors, an open notebook or Wi-Fi waits.
            val state = when {
                result == null -> BackgroundSyncHistory.State.INTERRUPTED
                LibrarySync.lastProblem(app) -> BackgroundSyncHistory.State.NEEDS_ATTENTION
                result == LibrarySync.WIFI_WAITING || LibrarySync.waitingForNotebooks(app) -> BackgroundSyncHistory.State.WAITING
                else -> BackgroundSyncHistory.State.CHECKED
            }
            if(folder==null && !cancellation.isCancelled && allowed()) {
                EncryptedSync.sync(app,continueSync={allowed()},cancellation=cancellation) {extra->
                    complete(false,if(extra?.startsWith("Encrypted sync failed")==true) BackgroundSyncHistory.State.NEEDS_ATTENTION else state)
                }
            } else complete(false,state)
        }
    }
}

class BackgroundSyncService : JobService() {
    private var current: BackgroundSyncRun? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as NotesApplication
        if (!BackgroundSync.eligible(app)) return false
        current?.stop()
        val run = BackgroundSyncRun(app) { retry ->
            current = null
            jobFinished(params, retry)
        }
        current = run
        app.main.post { if (current === run) run.start() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        current?.stop(); current = null
        return BackgroundSync.eligible(application as NotesApplication)
    }
    override fun onDestroy() { current?.stop(); current = null; super.onDestroy() }
}
