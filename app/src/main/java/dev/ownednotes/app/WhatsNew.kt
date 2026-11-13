package dev.ownednotes.app

import android.content.Context
import android.content.SharedPreferences
import kotlin.concurrent.thread

/**
 * P574: a short "What's new" note, shown once after the app is updated to a version that has one. A new install records
 * its version without showing anything, so the first thing a new person sees is still the Library.
 *
 * The notes ship inside the app, keyed by `versionName`. At release time the entry for the new version is written from
 * "Waiting for the next release" in docs/CONTINUE-HERE.md, and its key must match `versionName` exactly.
 */
internal object WhatsNew {
    val NOTES: Map<String, List<String>> = mapOf(
        "0.2.0-alpha" to listOf(
            "A slim bar: fold the toolbar into one thin strip, and let it fold by itself when you start writing if you like.",
            "Pick your own pens and highlighters: the + after them adds one, holding one removes it. New: a fountain pen with an Everyday and a Soft nib, and highlighters with a chisel or fine tip.",
            "Highlights, underlines, strike-outs and pen drawings already in a PDF come with it when you import it, as ink you can erase or recolour.",
            "Writing with a rubber-tip pen or a finger, you can rest your hand on the screen.",
            "Turning a page in portrait rolls it over like paper.",
            "Menus are grids of icons, and Page & paper, the Pages panel and the Study panel fit on one screen.",
            "In the Library, hold a notebook and drag it onto a folder to move it there.",
            "Many fixes for phones, large text and screen readers.",
            "Scribella can check for a new version once a day, if you turn it on.",
        ),
    )
    private const val SEEN = "whatsNewSeen"

    /**
     * The notes to show now, or null. Records [version] as seen either way, so each version's note appears at most once.
     * [updated] is false for a new install: then nothing is shown.
     */
    fun due(saved: SharedPreferences, version: String, updated: Boolean, notes: Map<String, List<String>> = NOTES): List<String>? {
        if (version.isEmpty()) return null
        val last = saved.getString(SEEN, null)
        if (last == version) return null
        saved.edit().putString(SEEN, version).apply()
        if (last == null && !updated) return null
        return notes[version]?.takeIf { it.isNotEmpty() }
    }

    /** Glass parts only. Public builds also offer the automatic update check here, until it is on. */
    fun show(context: Context, version: String, lines: List<String>): Kit.Sheet {
        val sheet = Kit.Sheet(context, "What’s new", cancel = "Close")
        sheet.add(Kit.text(context, "Version ${UpdateCheck.display(version)}", Kit.Type.HEADLINE))
        lines.forEach { sheet.add(Kit.text(context, "•  $it", secondary = true), gap = 8f) }
        if (!context.personalTools) {
            val saved = AutoUpdateCheck.preferences(context)
            if (!AutoUpdateCheck.enabled(saved)) sheet.add(Kit.row(context, "Tell me when a new version is out", "Once a day, asks GitHub. Nothing about your notebooks is sent.",
                trailing = Kit.Switch(context, false, "Tell me when a new version is out") { AutoUpdateCheck.setEnabled(saved, it) }))
        }
        sheet.show()
        return sheet
    }

    fun current(context: Context): List<String>? = NOTES[UpdateCheck.currentVersion(context)]
}

/**
 * P574: the automatic update check, off until the person turns it on (Settings → Privacy & storage → About, or the
 * What's new note), because the app otherwise makes no network calls of its own. Public builds only. When on, opening
 * the Library asks GitHub at most once a day, the same request as Check for updates, and a newer version is offered once.
 */
internal object AutoUpdateCheck {
    private const val ENABLED = "autoUpdateCheck"
    private const val CHECKED = "autoUpdateCheckedAt"
    private const val OFFERED = "autoUpdateOffered"
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    @Volatile private var running = false

    fun preferences(context: Context): SharedPreferences =
        (context.applicationContext as NotesApplication).let { it.getSharedPreferences(it.preferencesName, 0) }

    fun enabled(saved: SharedPreferences) = saved.getBoolean(ENABLED, false)
    fun setEnabled(saved: SharedPreferences, on: Boolean) { saved.edit().putBoolean(ENABLED, on).apply() }

    /** True when on and the last check was a day or more ago, or the clock went back. Records the attempt. */
    fun claim(saved: SharedPreferences, now: Long): Boolean {
        if (!enabled(saved)) return false
        val last = saved.getLong(CHECKED, 0L)
        if (last in 1..now && now - last < INTERVAL_MS) return false
        saved.edit().putLong(CHECKED, now).apply()
        return true
    }

    /** The release to tell the person about, once per version; null for anything else. */
    fun offer(saved: SharedPreferences, result: UpdateCheck.Result): UpdateCheck.Release? {
        val release = (result as? UpdateCheck.Result.Available)?.release ?: return null
        if (saved.getString(OFFERED, null) == release.version) return null
        saved.edit().putString(OFFERED, release.version).apply()
        return release
    }

    /** Called when the Library appears. [stillShowing] is checked on the main thread before anything is shown. */
    fun run(context: Context, stillShowing: () -> Boolean, now: Long = System.currentTimeMillis(),
            current: String = UpdateCheck.currentVersion(context), check: () -> UpdateCheck.Result = { UpdateCheck.fetch(current) }) {
        val app = context.applicationContext as NotesApplication
        if (context.personalTools || running) return
        val saved = preferences(context)
        if (!claim(saved, now)) return
        running = true
        thread(name = "auto-update-check", isDaemon = true) {
            val result = runCatching(check).getOrDefault(UpdateCheck.Result.Failed)
            app.main.post {
                running = false
                if (result is UpdateCheck.Result.Available && stillShowing() && offer(saved, result) != null) UpdateCheck.showAvailable(context, current, result)
            }
        }
    }
}
