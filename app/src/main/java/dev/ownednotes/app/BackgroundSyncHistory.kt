package dev.ownednotes.app

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/** One local execution receipt, separate from manual sync results; never part of a notebook/export. */
internal class BackgroundSyncHistory(private val prefs: SharedPreferences) {
    enum class State { RUNNING, CHECKED, WAITING, NEEDS_ATTENTION, INTERRUPTED }
    data class Receipt(val started: Long, val ended: Long, val state: State, val currentSource: Boolean)
    private var active: String? = null
    @Synchronized fun begin(source: String?, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("background_receipt_id", id).putLong("background_receipt_start", now)
            .putLong("background_receipt_end", 0).putString("background_receipt_state", State.RUNNING.name)
            .putString("background_receipt_source", fingerprint(source)).apply()
        active = id
        return id
    }
    @Synchronized fun finish(id: String, source: String?, state: State, now: Long = System.currentTimeMillis()) {
        require(state != State.RUNNING)
        if (id != active || prefs.getString("background_receipt_id", null) != id) return
        val same = prefs.getString("background_receipt_source", null) == fingerprint(source)
        prefs.edit().putLong("background_receipt_end", now)
            .putString("background_receipt_state", (if (same) state else State.INTERRUPTED).name).apply()
        active = null
    }
    @Synchronized fun receipt(source: String?): Receipt? {
        val id = prefs.getString("background_receipt_id", null) ?: return null
        val saved = runCatching { State.valueOf(prefs.getString("background_receipt_state", "")!!) }
            .getOrDefault(State.INTERRUPTED)
        val state = if (saved == State.RUNNING && active != id) State.INTERRUPTED else saved
        return Receipt(prefs.getLong("background_receipt_start", 0), prefs.getLong("background_receipt_end", 0),
            state, prefs.getString("background_receipt_source", null) == fingerprint(source))
    }
    fun summary(source: String?): String {
        val r = receipt(source) ?: return "No background check recorded yet."
        val whenText = EnglishTime.short(if (r.ended > 0) r.ended else r.started)
        if (!r.currentSource) return "Last background check: $whenText, for a previous sync source."
        val result = when (r.state) {
            State.RUNNING -> "running"
            State.CHECKED -> "checked the sync folder"
            State.WAITING -> "waiting for notebooks or connection"
            State.NEEDS_ATTENTION -> "needs attention; a later check can retry"
            State.INTERRUPTED -> "interrupted; a later check can retry"
        }
        return "Last background check: $whenText — $result."
    }
    private fun fingerprint(source: String?): String = MessageDigest.getInstance("SHA-256")
        .digest((source ?: "no-source").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
