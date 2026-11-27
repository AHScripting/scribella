package dev.ownednotes.app

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
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
        "0.4.0-alpha" to listOf(
            "Instant sync: changes, photos too, reach your other device in seconds through a small free server of your own on Cloudflare. A guide and a QR code set it up.",
            "Changed on both devices? Scribella combines the two or asks which to keep. Library folders, colours, tags and pins follow sync too.",
            "Scan uses Google's document scanner where your device has it. Scans are sharper, and Scribella's own scanner finds shaded corners and whitens the paper.",
            "Share photos from Photos, Drive or Files to Scribella, as new pages or as pictures on the open page. Scans made on your phone can go to your tablet.",
            "Crop a picture with straight sides or four free corners, straighten it, change its look, and turn a picture into a page or a page into a picture.",
            "Handwritten sticky notes, and typing straight into a text box on the page, turned ones too.",
            "A calmer look with fewer words: sync, backups, storage, gestures, Settings and the older dialogs, with explanations behind (i). The app fits phones.",
            "Steadier with big notebooks, big photos and long sync backlogs; highlights along text lines keep one height per line.",
        ),
        "0.3.0-alpha" to listOf(
            "Peek: a floating window shows another page of the notebook. Move it, resize it, or fold it to a small bubble at the edge.",
            "Show beside: choose which page sits next to this one in the landscape two-page view.",
            "The slim bar holds every button: page buttons in the middle, Show pages by the pin, and the notebook's name in a small pill with a pencil to rename it.",
            "Add highlights from PDF (Study menu) brings a marked-up copy's highlights into the notebook.",
            "A PDF with pages of another shape asks once: fit them to your paper, or keep their size.",
            "A damaged page no longer stops the notebook from opening; the other pages open.",
            "A cloud folder that stops answering can no longer freeze sync or backups.",
            "The fountain pen stays fast on full pages, and two-page view keeps both pages sharp.",
        ),
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
     * P735 (board 28): the same items in a few words, each with an icon, two to a row. A version without these keeps its
     * sentences. Each list has one item per line of [NOTES], in the same order.
     */
    internal enum class Tone {
        ACCENT, AMBER, SLATE, GREEN;
        fun color(night: Boolean) = when (this) {
            ACCENT -> Glass.accent(night); AMBER -> SyncPanels.amber(night); SLATE -> SyncPanels.slate(night); GREEN -> SyncPanels.green(night)
        }
    }
    internal data class Item(val icon: String, val tone: Tone, val text: String)
    private val P = SyncPanels.P
    val SHORT: Map<String, List<Item>> = mapOf(
        "0.4.0-alpha" to listOf(
            Item(P.BOLT, Tone.ACCENT, "Instant sync: your other device in seconds"),
            Item(P.DEVICES, Tone.GREEN, "Changed on both devices: combine or choose"),
            Item(P.CAMERA, Tone.ACCENT, "Google’s document scanner; sharper scans"),
            Item(P.SHARE, Tone.ACCENT, "Share photos in as pages or pictures"),
            Item(P.FIT, Tone.SLATE, "Crop with straight sides or four corners"),
            Item(P.TEXTBOX, Tone.AMBER, "Handwritten sticky notes; type on the page"),
            Item(P.SPARK, Tone.ACCENT, "A calmer look with fewer words; fits phones"),
            Item(P.CHECK, Tone.GREEN, "Steadier with big notebooks and photos"),
        ),
        "0.3.0-alpha" to listOf(
            Item(P.WINDOW, Tone.ACCENT, "Peek: another page in a floating window"),
            Item(P.SPREAD, Tone.ACCENT, "Show beside: pick the page next to this one"),
            Item(P.SLIM, Tone.ACCENT, "The slim bar holds every button"),
            Item(P.MARKER, Tone.AMBER, "Add highlights from a marked-up PDF"),
            Item(P.FIT, Tone.SLATE, "PDF pages of another shape: fit them or keep their size"),
            Item(P.CHECK, Tone.GREEN, "A damaged page no longer stops a notebook opening"),
            Item(P.CLOUD, Tone.GREEN, "A cloud folder that stops answering can’t freeze sync"),
            Item(P.FOUNTAIN, Tone.SLATE, "Fountain pen stays fast; two pages stay sharp"),
        ),
    )

    private fun card(context: Context, item: Item) = LinearLayout(context).apply {
        val night = context.night
        gravity = Gravity.CENTER_VERTICAL
        background = SyncPanels.cardBackground(context)
        minimumHeight = context.dpi(60f)
        setPadding(context.dpi(12f), context.dpi(10f), context.dpi(12f), context.dpi(10f))
        addView(SyncPanels.disc(context, item.icon, item.tone.color(night), 36f, 20f))
        addView(SyncPanels.label(context, item.text, 14f, false, Glass.text(night)).apply { maxLines = if (context.largeText) 4 else 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(12f) })
    }

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
        val items = SHORT[version]?.takeIf { it.size == lines.size }
        val sheet = Kit.Sheet(context, "What’s new", cancel = "Close")
        sheet.add(Kit.text(context, "Version ${UpdateCheck.display(version)}", Kit.Type.HEADLINE))
        if (items != null) sheet.add(SyncPanels.TileGrid(context, 300f).apply { items.forEach { addView(card(context, it)) } }, gap = 16f)
        else lines.forEach { sheet.add(Kit.text(context, "•  $it", secondary = true), gap = 8f) }
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
