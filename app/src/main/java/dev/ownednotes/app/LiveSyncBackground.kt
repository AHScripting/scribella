package dev.ownednotes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.ownednotes.core.LiveSyncClient

/**
 * Instant sync step 9 (docs/LIVE-SYNC-PLAN.md, P702): "Keep receiving in the background". Off by default. While it is on
 * (and instant sync is on and not refused), [LiveSyncService] keeps the connection with no screen, under a quiet
 * notification that names no notebook.
 */
internal object LiveSyncBackground {
    private const val KEY = "live_sync_background"
    private fun prefs(app: NotesApplication) = app.getSharedPreferences(app.preferencesName, 0)
    fun enabled(app: NotesApplication) = prefs(app).getBoolean(KEY, false)
    private fun wanted(app: NotesApplication) = enabled(app) && app.liveSync.status.state.let {
        it != LiveSyncClient.State.OFF && it != LiveSyncClient.State.DENIED
    }

    /** The switch. Turning it on starts the service, so call it while Scribella is in front. Main thread. */
    fun setEnabled(app: NotesApplication, on: Boolean) { prefs(app).edit().putBoolean(KEY, on).apply(); update(app) }

    /** Starts or stops the service to match the switch and instant sync; turning instant sync off also turns the switch off. */
    fun update(app: NotesApplication) {
        if (enabled(app) && app.liveSync.status.state == LiveSyncClient.State.OFF) prefs(app).edit().putBoolean(KEY, false).apply()
        if (wanted(app)) runCatching { app.startForegroundService(Intent(app, LiveSyncService::class.java)) }
        else if (LiveSyncService.running) runCatching { app.stopService(Intent(app, LiveSyncService::class.java)) }
    }

    /** After a reboot or an update: back on only if the user left it on. */
    fun restart(app: NotesApplication) { if (wanted(app)) runCatching { app.startForegroundService(Intent(app, LiveSyncService::class.java)) } }
}

/** Keeps instant sync connected while Scribella is in the background. Main process: it uses app.liveSync and the repository. */
class LiveSyncService : Service() {
    companion object {
        const val ACTION_STOP = "dev.ownednotes.app.live_sync.STOP"
        const val CHANNEL = "live_sync"
        const val NOTIFICATION_ID = 0x4c495645
        @Volatile var running = false
            private set
    }
    private val app get() = application as NotesApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LiveSyncBackground.setEnabled(app, false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            // Android refused a start from the background (it is tried again the next time Scribella opens).
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) { running = true; app.liveSync.background(true) }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) { running = false; app.liveSync.background(false) }
        super.onDestroy()
    }

    /** Silent and low: no sound, no badge, and no notebook names or content. */
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Instant sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Scribella receives changes from your other devices in the background"
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Instant sync is on")
            .setContentText("Receiving changes from your other devices")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

/** Starts [LiveSyncService] again after a reboot or an app update, if the user left the switch on. */
class LiveSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? NotesApplication ?: return
        runCatching { LiveSyncBackground.restart(app) }
    }
}
