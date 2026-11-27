package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.LiveSyncClient
import kotlin.math.roundToInt

/**
 * P729 (design canvas board 21, Look A, chosen on 27 Nov 2026): Library sync with fewer words. A status card shows this
 * device ⇄ the folder (or server), how the last sync went, Pause and an (i); two switch tiles; four tiles for where it
 * syncs; in the owner's build one Diagnostics row; Stop syncing, small and red. Every paragraph of the old sheet is
 * behind one of the two (i) buttons ([Explain]). No control was removed: each old button is a tile, a switch or a row.
 */
internal object SyncPanels {
    const val GREEN = 0xff2b9156.toInt()
    const val AMBER = 0xffd9821c.toInt()
    const val SLATE = 0xff667085.toInt()
    const val MUTE = 0xff8a93a6.toInt()
    fun green(night: Boolean) = if (night) 0xff3fb872.toInt() else GREEN
    fun amber(night: Boolean) = if (night) 0xffe8a03c.toInt() else AMBER
    fun fault(night: Boolean) = Kit.dangerText(night)
    fun slate(night: Boolean) = if (night) 0xff98a2b3.toInt() else SLATE
    fun mute(night: Boolean) = if (night) 0xff7c869a.toInt() else MUTE

    /** The board's icons (24-unit SVG paths). */
    object P {
        const val TABLET = "M6.5 2.5h11a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 20V4a1.5 1.5 0 0 1 1.5-1.5zM10.5 18.5h3"
        const val FOLDER = "M3 6.5h6l2 2h10v10.5H3z"
        const val FOLDER_PLUS = "M3 6.5h6l2 2h10v10.5H3zM12 11.5v5M9.5 14h5"
        const val SERVER = "M4 4.5h16v6H4zM4 13.5h16v6H4zM7.5 7.5h.01M7.5 16.5h.01"
        const val SHIELD = "M12 3l7 3v5.5c0 4.3-2.9 8-7 9.5-4.1-1.5-7-5.2-7-9.5V6l7-3zM9 12l2.2 2.2L15.5 10"
        const val BOLT = "M13 2.5L5 13.5h6l-1 8 8-11h-6z"
        const val WIFI = "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0M12 19.5h.01"
        const val MOON = "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
        const val PAUSE = "M9 5.5v13M15 5.5v13"
        const val PLAY = "M8 5.5l11 6.5-11 6.5z"
        const val WRENCH = "M14.7 6.3a4 4 0 0 0 5 5L11 20a2.1 2.1 0 0 1-3-3l8.7-8.7M14.7 6.3a4 4 0 0 1 5-3l-2.6 2.6.5 2 2 .5 2.6-2.6"
        const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
        const val SYNC = "M19.5 12a7.5 7.5 0 0 1-13.1 5M4.5 12a7.5 7.5 0 0 1 13.1-5M17.6 3.5V7h-3.5M6.4 20.5V17h3.5"
        const val STOP = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM5.6 5.6l12.8 12.8"
        const val TWO = "M8.5 3.5h10v13h-10zM5.5 7v13.5h10"
        const val RESTORE = "M4 12a8 8 0 1 0 2.4-5.7M4 4.5V8h3.5M12 8v4.5l3 2"
        const val TRASH = "M4.5 6.5h15M9.5 6.5v-2h5v2M6.5 6.5l1 13.5h9l1-13.5M10 10.5v6M14 10.5v6"
        const val CLOCK = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3.5 2"
        const val UPLOAD = "M7 18.5a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4.5 4.5 0 0 1-.5 9M12 19.5v-7M9.5 15l2.5-2.5 2.5 2.5"
        const val ALERT = "M12 6.5v7M12 17.5h.01"
        // P731 (board 22): Instant sync and its guide.
        const val DEVICES = "M3.5 5.5h11v13h-11zM17 8.5h4v10h-4zM8 16h2"
        const val LOCK = "M6 11h12v9.5H6zM8.5 11V7.5a3.5 3.5 0 0 1 7 0V11"
        const val QR = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2.5v2.5H14zM17.5 17.5H20V20h-2.5zM14 19.5h1M19.5 14h.5M6.8 6.8h.4M16.8 6.8h.4M6.8 16.8h.4"
        const val SHARE = "M12 15V3.5M8 7.5l4-4 4 4M5 12v7.5h14V12"
        const val COPY = "M9 9h11v11H9zM5 15V4h11"
        const val PASTE = "M8 4.5H6v16h12v-16h-2M8.5 3h7v3h-7zM9 11h6M9 15h4"
        const val BOOK = "M4 5.5c3-1.5 5.5-1.5 8 0v14c-2.5-1.5-5-1.5-8 0zM12 5.5c2.5-1.5 5-1.5 8 0v14c-3-1.5-5.5-1.5-8 0z"
        const val DOWNLOAD = "M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14"
        const val CLOUD = "M7 18.5a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4.5 4.5 0 0 1-.5 9z"
        const val BOX = "M12 2.5l8.5 4.75v9.5L12 21.5l-8.5-4.75v-9.5zM12 12v9.5M12 12l8.5-4.75M12 12L3.5 7.25"
        const val TERMINAL = "M3.5 5h17v14h-17zM7 9.5l3 2.5-3 2.5M12 15h5"
        const val GLOBE = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z"
        const val KEY = "M8 15.5a4.5 4.5 0 1 1 3.9-6.75H21v3h-2v2.5h-3v-2.5h-4.1A4.5 4.5 0 0 1 8 15.5zM7 11h.01"
        const val SPARK = "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"
        const val REPEAT = "M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M18 3v4h-4M6 21v-4h4"
        // P732 (board 25): Library backup and Notebook storage.
        const val ARCHIVE = "M3.5 4.5h17v4h-17zM5 8.5v11h14v-11M10 12.5h4"
        const val ARROW = "M5 12h13M13 6.5l5.5 5.5-5.5 5.5"
        const val DOC = "M6.5 3h7.5l4.5 4.5V21h-12zM14 3v4.5h4.5M9 12.5h6M9 16h6"
        const val DOOR = "M5 20.5V4.5h9v16M14 6.5h5v14M11 12.5h.01M3 20.5h18"
        // P733 (board 26): the tool boards' (i) cards and the older dialogs' sheets.
        const val DROP = "M12 3.5s6 6.5 6 10.5a6 6 0 0 1-12 0c0-4 6-10.5 6-10.5z"
        const val MARKER = "M14.5 4.5l5 5-8 8H6.5v-5zM4 20.5h16"
        const val TEXTLINES = "M4 7h16M4 12h11M4 17h14"
        const val SQUIGGLE = "M3 15c2-5 3.5-5 5 0s3 5 5 0 3.5-5 5 0 2 2 3 1"
        const val UNDO = "M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11"
        const val PLUS = "M12 5v14M5 12h14"
        const val PEN = "M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5zM13.5 7l3 3"
        const val LOOP = "M12 4c5 0 8.5 2.7 8.5 6.5S17 17 12 17s-8.5-2.7-8.5-6.5S7 4 12 4zM12 17l-1.5 4"
        const val BRUSH = "M18.5 3.5l2 2-8.5 8.5-2-2zM10 12l2 2c0 3-2 6-6.5 6 1.5-1.5 1-3.5 1.5-5 .5-1.8 1.8-3 3-3z"
        const val SHAPES = "M7 12a4 4 0 1 0 0-.01M14 17l3.5-7 3.5 7z"
        const val RULER = "M3 15.5L15.5 3l5.5 5.5L8.5 21zM7 11.5l2 2M10 8.5l2 2M13 5.5l2 2"
        const val PAGE = "M6.5 3h8l3 3v15h-11zM14.5 3v3h3"
        const val PAGES = "M9 6.5h7.5l3 3V21H9zM5.5 17.5V3h8"
        const val RANGE = "M3.5 5.5h6v13h-6zM14.5 5.5h6v13h-6zM11 12h2"
        const val PAPER = "M5 3.5h14v17H5zM8 8h8M8 12h8M8 16h8"
        const val TEXTBOX = "M4 5h16v14H4zM8 9h8M12 9v7"
        const val CLIP = "M20 11.5l-8 8a5 5 0 0 1-7-7l8.5-8.5a3.5 3.5 0 0 1 5 5L10 17.5a2 2 0 0 1-3-3l7.5-7.5"
        const val MARKDOWN = "M3.5 6h17v12h-17zM6.5 15V9l2.5 3 2.5-3v6M16 9v6M14 13l2 2 2-2"
        const val EYE = "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
        const val EYE_OFF = EYE + "M4 4l16 16"
        const val UNLOCK = "M6 11h12v9.5H6zM8.5 11V7.5a3.5 3.5 0 0 1 6.8-1.2"
        const val UP = "M12 19V5M6.5 10.5L12 5l5.5 5.5"
        const val DOWN = "M12 5v14M6.5 13.5L12 19l5.5-5.5"
        const val BOOKMARK = "M6.5 3.5h11v17l-5.5-4-5.5 4z"
        const val BACK = "M19 12H6M11 6.5L5.5 12l5.5 5.5"
        const val LAYERS = "M12 3.5l9 5-9 5-9-5zM3 13l9 5 9-5"
        const val LINK = "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7L11.5 6.8M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1.5-1.5"
        const val SCROLL = "M12 3v18M8 7l4-4 4 4M8 17l4 4 4-4"
        const val MENU = "M5 6h14M5 12h14M5 18h14"
        // P735 (boards 27 and 28): gestures, settings and What's new.
        const val HAND = "M9 11V5.5a1.5 1.5 0 0 1 3 0V11M12 10.5V9a1.5 1.5 0 0 1 3 0v2M15 11a1.5 1.5 0 0 1 3 0v3.5a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L4 15a1.5 1.5 0 0 1 2.5-1.7L9 16"
        const val SIDE_BUTTON = "M9.5 2.5h5v16l-2.5 3-2.5-3zM12 7v3.5"
        const val SHAPE = "M12 4l8.5 15h-17z"
        const val DRAG = "M12 3.5h.01M12 7v12M7.5 14.5L12 19l4.5-4.5"
        const val SWIPE = "M20 12H5M9.5 7.5L5 12l4.5 4.5M19.5 12h.01"
        const val TWO_DRAG = "M9 3.5h.01M15 3.5h.01M12 7v12M7.5 14.5L12 19l4.5-4.5"
        const val TWO_SWIPE = "M19.5 8h.01M19.5 16h.01M16 12H4M8.5 7.5L4 12l4.5 4.5"
        const val PINCH = "M14 10l6-6M20 9V4h-5M10 14l-6 6M4 15v5h5"
        const val TAP = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
        const val TWO_TAP = "M7.5 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM16.5 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"
        const val GEAR = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"
        const val ERASER = "M8.5 20H20M5 15.5l9-9 5 5-8.5 8.5H8.5zM10 10.5l5 5"
        const val CURSOR = "M5.5 3.5l13 6.5-5.5 1.8-2.3 5.7z"
        const val NEAR = "M6 8a8 8 0 0 1 12 0M8.5 11a4.5 4.5 0 0 1 7 0M12 14.5h.01"
        const val CAMERA = "M4 7.5h3.5L9 5h6l1.5 2.5H20v12H4zM12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"
        const val SEND = "M4 12l16-8-6 16-2.5-6.5zM11.5 13.5L20 4"
        const val SEARCH = "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM15.5 15.5L20 20"
        const val HEART = "M12 20s-7.5-4.5-7.5-10A4 4 0 0 1 12 7a4 4 0 0 1 7.5 3c0 5.5-7.5 10-7.5 10z"
        const val WINDOW = "M3.5 5h17v14h-17zM12.5 10h6v6.5h-6z"
        const val SPREAD = "M3 5.5h8.5v13H3zM12.5 5.5H21v13h-8.5z"
        const val SLIM = "M3 15.5h18v4H3zM7 17.5h.01M12 17.5h.01M17 17.5h.01"
        const val FIT = "M5 3.5h14v17H5zM7.5 6h9v6h-9z"
        const val FOUNTAIN = "M12 3l4 6-4 12-4-12zM12 9v4"
    }

    /** What the sheet's parts do: MainActivity's pickers and sheets. */
    class Actions(
        val chooseFolder: () -> Unit,
        val webDav: () -> Unit,
        val webDavRecovery: () -> Unit,
        val addEncrypted: () -> Unit,
        val syncEncrypted: () -> Unit,
        val disconnectEncrypted: () -> Unit,
        val instantSync: () -> Unit,
        val syncNow: () -> Unit,
        val stop: () -> Unit,
        val smallerUploads: () -> Unit,
        val memorySummary: () -> Unit,
        val saveMemoryReport: () -> Unit,
        val deleteMemoryReport: () -> Unit,
        val lastExits: () -> Unit,
    )

    fun show(activity: MainActivity, context: Context, act: Actions): Kit.Sheet =
        if (LibrarySync.hasSource(activity)) syncing(activity, context, act) else newcomer(activity, context, act)

    /** "This tablet" or "This phone", by the screen's smallest width. */
    private fun deviceWord(context: Context) = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    private fun howItWorks(night: Boolean) = listOf(
        Explain.Line(P.CHECK, green(night), "Every copy is checked."),
        Explain.Line(P.TWO, Glass.accent(night), "Changed on two devices: both versions are kept."),
        Explain.Line(P.RESTORE, slate(night), "Replaced versions are kept."),
        Explain.Line(P.TRASH, fault(night), "Deleted on another device: goes to Trash here."))

    private fun transfers(night: Boolean, webDav: Boolean) = listOf(
        Explain.Line(P.MOON, green(night), "Android checks about every 15 min, when battery and network allow."),
        Explain.Line(P.WIFI, green(night), "Wi-Fi only holds Sync now too, until Wi-Fi."),
        if (webDav) Explain.Line(P.RESTORE, slate(night), "An interrupted transfer keeps a recovery record.")
        else Explain.Line(P.UPLOAD, slate(night), "Your cloud app decides when its own copies upload."),
        Explain.Line(P.PAUSE, amber(night), "Pause stops the checks; Sync now still works."))

    // ------------------------------------------------------------------------------------------------ A folder is set

    private fun syncing(activity: MainActivity, context: Context, act: Actions): Kit.Sheet {
        val app = activity.application as NotesApplication
        val night = context.night
        val webDav = LibrarySync.webDavSelected(activity)
        lateinit var sheet: Kit.Sheet
        lateinit var live: Runnable
        // P713: Sync now keeps the sheet open; the card shows what the sync is doing, live.
        sheet = Kit.Sheet(context, "Library sync", "Sync now", cancel = "Done") {
            if (!LibrarySync.running) act.syncNow()
            app.main.postDelayed(live, 100)
            false
        }
        val place = if (webDav) LibrarySync.folderLabel(activity) else "“${LibrarySync.folderLabel(activity)}”"
        val card = StatusCard(context, newcomer = false, webDav = webDav, place = place, device = deviceWord(context))
        var wasRunning = false
        var finished = false
        fun refresh() {
            card.paused(LibrarySync.paused(activity))
            if (LibrarySync.running) {
                wasRunning = true; finished = false
                val seconds = ((System.currentTimeMillis() - LibrarySync.startedAt) / 1000).coerceAtLeast(0)
                val doing = LibrarySync.progressText.ifEmpty { "Starting" }
                card.show(StatusCard.Look.SYNCING, "Syncing… $seconds s", doing, fraction(doing))
                sheet.actionButton?.apply { isEnabled = false; alpha = .5f }
            } else {
                // Finished while the sheet was open: the line under the state says so until the next sync.
                if (wasRunning) { wasRunning = false; finished = true }
                val (look, text) = lookOf(activity)
                val detail = detailOf(activity)
                card.show(look, text, if (finished) listOf("Finished", detail).filter { it.isNotEmpty() }.joinToString(" · ") else detail)
                sheet.actionButton?.apply { isEnabled = true; alpha = 1f }
            }
        }
        card.onPause = { LibrarySync.setPaused(activity, !LibrarySync.paused(activity)); refresh() }
        card.info.setOnClickListener { Explain.show(card.info, "How Library sync works", howItWorks(night)) }
        card.onStatus = { LibrarySync.lastResult(activity)?.let { Explain.show(card.info, "Last sync", emptyList(), it) } }
        refresh()
        sheet.add(card)

        // Transfers
        val info = Explain.Button(context, "About transfers")
        info.setOnClickListener { Explain.show(info, "Transfers", transfers(night, webDav)) }
        sheet.add(sectionRow(context, "Transfers", info), gap = 16f)
        val background = Kit.Switch(context, BackgroundSync.enabled(app), "Background sync") {
            if (!BackgroundSync.setEnabled(app, it)) {
                activity.message("Android could not schedule background sync. Sync now still works.")
                error("Background sync scheduling refused")
            }
        }
        val wifi = Kit.Switch(context, LibrarySync.wifiOnly(activity), "Wi-Fi-only sync") {
            LibrarySync.setWifiOnly(activity, it); app.main.post(live)
        }
        sheet.add(TileGrid(context, 300f).apply {
            addView(switchTile(context, P.MOON, "In the background", background))
            addView(switchTile(context, P.WIFI, "Wi-Fi only", wifi))
        }, gap = 2f)
        sheet.add(caption(context, backgroundLine(app)), gap = 8f)

        // Where it syncs
        sheet.add(sectionRow(context, "Where it syncs", null), gap = 11f)
        val encrypted = EncryptedSync.folderUri(app) != null
        val instant = PlaceTile(context, P.BOLT, Glass.accent(night), "Instant sync").apply {
            setOnClickListener { sheet.dismiss(); act.instantSync() }
        }
        sheet.add(TileGrid(context, 150f).apply {
            addView(PlaceTile(context, P.FOLDER, green(night), "Folder").apply {
                state(if (webDav) "Not set" else place, if (webDav) mute(night) else Glass.secondary(night))
                setOnClickListener { sheet.dismiss(); act.chooseFolder() }
            })
            addView(PlaceTile(context, P.SERVER, green(night), "WebDAV").apply {
                state(if (webDav) "In use" else "Not set", if (webDav) Glass.secondary(night) else mute(night))
                setOnClickListener { sheet.dismiss(); if (webDav) webDavSheet(context, act) else act.webDav() }
            })
            addView(PlaceTile(context, P.SHIELD, green(night), "Encrypted copy").apply {
                state(if (encrypted) "On" else "Off", if (encrypted) Glass.secondary(night) else mute(night))
                setOnClickListener { sheet.dismiss(); if (encrypted) encryptedSheet(activity, context, act) else act.addEncrypted() }
            })
            addView(instant)
        }, gap = 2f)
        val watcher = { instantState(app, night).let { (text, dot) -> instant.state(text, Glass.secondary(night), dot) } }
        watcher()
        app.liveSync.watch(watcher)

        if (activity.personalTools) {
            val parts = listOfNotNull("Smaller uploads test".takeIf { !webDav }, MemoryReport.about(activity)?.let { "Memory report" }, "Last exits")
            sheet.add(diagnosticsRow(context, parts.joinToString(" · ")) { sheet.dismiss(); diagnostics(activity, context, act) }, gap = 16f)
        }
        sheet.add(stopRow(context) { sheet.dismiss(); act.stop() }, gap = 16f)

        live = object : Runnable {
            override fun run() {
                app.main.removeCallbacks(this)
                if (activity.isDestroyed || !sheet.dialog.isShowing) return
                refresh()
                // A sync started elsewhere (opening the Library, instant sync, background) shows here too.
                app.main.postDelayed(this, if (LibrarySync.running) 500 else 1000)
            }
        }
        sheet.dialog.setOnDismissListener { app.liveSync.unwatch(watcher); app.main.removeCallbacks(live) }
        return sheet.show().also { app.main.post(live) }
    }

    /** The card's look and its headline, from the same facts as [LibrarySync.status]. */
    internal fun lookOf(context: Context): Pair<StatusCard.Look, String> {
        val look = when {
            LibrarySync.paused(context) -> StatusCard.Look.PAUSED
            LibrarySync.wifiOnly(context) && !LibrarySync.onWifi(context) -> StatusCard.Look.WAITING
            LibrarySync.lastProblem(context) -> StatusCard.Look.FAILED
            LibrarySync.waitingForNotebooks(context) -> StatusCard.Look.WAITING
            LibrarySync.lastSync(context) == 0L -> StatusCard.Look.NOT_YET
            else -> StatusCard.Look.SYNCED
        }
        return look to LibrarySync.status(context)
    }

    /** "20:14 · 2 sent · 1 received": when the last complete sync ended and, in a few words, what the last sync did. */
    internal fun detailOf(context: Context): String {
        val last = LibrarySync.lastSync(context)
        return listOfNotNull(if (last > 0) clock(context, last) else null, LibrarySync.lastResult(context)?.let(::shortResult)?.takeIf { it.isNotEmpty() })
            .joinToString(" · ")
    }

    /** The last sync's report in a few words; its whole text opens from the card (tap the state line). */
    internal fun shortResult(report: String): String {
        val first = report.lineSequence().first().trim()
        if (first.startsWith("Sync failed")) return "Sync failed"
        if (first.startsWith("Waiting for Wi-Fi")) return ""
        return first.removeSuffix(".").split("; ").joinToString(" · ") { piece ->
            Regex("^Synced: everything is up to date \\((\\d+) notebooks?\\)$").find(piece)?.let { "all up to date" }
                ?: Regex("^Sync checked local notebooks \\((\\d+) up to date\\)$").find(piece)?.let { "${it.groupValues[1]} up to date" }
                ?: Regex("^[Ss]ent (\\d+) notebooks?$").find(piece)?.let { "${it.groupValues[1]} sent" }
                ?: Regex("^[Rr]eceived (\\d+) notebooks?$").find(piece)?.let { "${it.groupValues[1]} received" }
                ?: piece
        }
    }

    /** "3 of 5" in a progress line, as a share of the bar; null when the line has no count. */
    private fun fraction(doing: String) = Regex("(\\d+) of (\\d+)").find(doing)?.let {
        val of = it.groupValues[2].toFloat(); if (of > 0) (it.groupValues[1].toFloat() / of).coerceIn(0f, 1f) else null
    }

    /** "20:14" today, a date and time before today. */
    private fun clock(context: Context, time: Long): String {
        val today = java.time.LocalDate.now()
        val then = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (then == today) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time)) else EnglishTime.short(time)
    }

    /** "Last background check 19:58 · checked": the background receipt in a few words. */
    private fun backgroundLine(app: NotesApplication): String {
        val r = app.backgroundHistory.receipt(LibrarySync.sourceIdentity(app)) ?: return "No background check yet"
        val time = clock(app, if (r.ended > 0) r.ended else r.started)
        if (!r.currentSource) return "Last background check $time · another folder"
        val result = when (r.state) {
            BackgroundSyncHistory.State.RUNNING -> "running"
            BackgroundSyncHistory.State.CHECKED -> "checked"
            BackgroundSyncHistory.State.WAITING -> "waiting"
            BackgroundSyncHistory.State.NEEDS_ATTENTION -> "needs attention"
            BackgroundSyncHistory.State.INTERRUPTED -> "interrupted"
        }
        return "Last background check $time · $result"
    }

    /** Instant sync's state in a word or two, with its dot. */
    private fun instantState(app: NotesApplication, night: Boolean): Pair<String, Int> = when (app.liveSync.status.state) {
        LiveSyncClient.State.OFF -> "Off" to (if (night) Glass.OFF_NIGHT else Glass.OFF)
        LiveSyncClient.State.PAUSED -> "On" to Glass.accent(night)
        LiveSyncClient.State.CONNECTING -> "Connecting…" to amber(night)
        LiveSyncClient.State.LIVE -> "Connected" to green(night)
        LiveSyncClient.State.WAITING -> "Not connected" to amber(night)
        LiveSyncClient.State.DENIED -> "Refused" to fault(night)
    }

    // ------------------------------------------------------------------------------------------------- No folder yet

    private fun newcomer(activity: MainActivity, context: Context, act: Actions): Kit.Sheet {
        val app = activity.application as NotesApplication
        val night = context.night
        lateinit var sheet: Kit.Sheet
        sheet = Kit.Sheet(context, "Library sync", cancel = "Done")
        val card = StatusCard(context, newcomer = true, webDav = false, place = "", device = deviceWord(context))
        card.info.setOnClickListener { Explain.show(card.info, "How Library sync works", howItWorks(night)) }
        sheet.add(card)
        sheet.add(TileGrid(context, 300f).apply {
            addView(choiceCard(context, P.FOLDER, Glass.accent(night), "Choose a folder", "OneDrive, Google Drive or any folder app", recommended = true) {
                sheet.dismiss(); act.chooseFolder()
            })
            addView(choiceCard(context, P.SERVER, green(night), "WebDAV server", "Nextcloud and other servers", recommended = false) {
                sheet.dismiss(); act.webDav()
            })
        }, gap = 16f)
        sheet.add(sectionRow(context, "Also", null), gap = 7f)
        val encrypted = EncryptedSync.folderUri(app) != null
        val instant = RowCard(context, P.BOLT, Glass.accent(night), "Instant sync").apply {
            setOnClickListener { sheet.dismiss(); act.instantSync() }
        }
        sheet.add(TileGrid(context, 300f).apply {
            addView(RowCard(context, P.SHIELD, green(night), "Encrypted copy").apply {
                state(if (encrypted) "On" else "Add a second, locked folder", null)
                setOnClickListener { sheet.dismiss(); if (encrypted) encryptedSheet(activity, context, act) else act.addEncrypted() }
            })
            addView(instant)
        }, gap = 2f)
        val watcher = { instantState(app, night).let { (text, dot) -> instant.state(text, dot) } }
        watcher()
        app.liveSync.watch(watcher)
        if (activity.personalTools) sheet.add(diagnosticsRow(context, "Smaller uploads test") { sheet.dismiss(); diagnostics(activity, context, act) }, gap = 16f)
        sheet.dialog.setOnDismissListener { app.liveSync.unwatch(watcher) }
        return sheet.show()
    }

    // ------------------------------------------------------------------------------------------- Behind the tiles

    /** The encrypted copy's own sheet: its state, Sync and Disconnect. */
    private fun encryptedSheet(activity: MainActivity, context: Context, act: Actions) = Kit.Sheet(context, "Encrypted copy", cancel = "Done").apply {
        add(Kit.text(context, EncryptedSync.status(activity), secondary = true))
        add(Kit.button(context, "Sync encrypted copy", Kit.Button.TINTED) { dismiss(); act.syncEncrypted() })
        add(Kit.button(context, "Disconnect", Kit.Button.PLAIN) { dismiss(); act.disconnectEncrypted() })
    }.show()

    /** WebDAV in use: its settings and the interrupted-transfer check. */
    private fun webDavSheet(context: Context, act: Actions) = Kit.Sheet(context, "WebDAV server", cancel = "Done").apply {
        add(Kit.Group(context).apply {
            addView(Kit.row(context, "Server settings", chevron = true) { dismiss(); act.webDav() })
            addView(Kit.row(context, "Inspect interrupted transfer", chevron = true) { dismiss(); act.webDavRecovery() })
        })
    }.show()

    /** The owner's tools, gathered behind one row (personal builds only). */
    private fun diagnostics(activity: MainActivity, context: Context, act: Actions) = Kit.Sheet(context, "Diagnostics", cancel = "Done").apply {
        val source = LibrarySync.hasSource(activity)
        if (!LibrarySync.webDavSelected(activity)) {
            add(Kit.text(context, when {
                !source -> "Test folders reuse PDF and photo data after the first upload. They do not replace files in an existing sync folder. Recovery data is retained; the nightly reader does not use this test format."
                LibrarySync.changedPages(activity) -> "Smaller uploads test: unchanged PDFs and photos are reused. Recovery data is retained in this folder. The nightly reader does not use this test format."
                else -> "Complete-notebook uploads are active. The smaller-uploads test uses a separate empty folder."
            }, secondary = true))
            add(Kit.button(context, "Try smaller uploads in a new folder…", Kit.Button.PLAIN) { dismiss(); act.smallerUploads() })
        }
        // P716/P717: a memory report taken when the app's memory was high or ran out: read here, saved where the user chooses, or deleted.
        if (source) MemoryReport.about(activity)?.let { about ->
            add(Kit.text(context, "Memory report: $about. It holds what was in the app's memory, notebooks included; its summary holds only class names and numbers.", secondary = true))
            add(Kit.button(context, "Memory summary…", Kit.Button.PLAIN) { dismiss(); act.memorySummary() })
            add(Kit.button(context, "Save memory report…", Kit.Button.PLAIN) { dismiss(); act.saveMemoryReport() })
            add(Kit.button(context, "Delete memory report", Kit.Button.PLAIN) { dismiss(); act.deleteMemoryReport() })
        }
        // P726: Android's own record of the app's last exits.
        if (source) add(Kit.button(context, "Last exits…", Kit.Button.PLAIN) { dismiss(); act.lastExits() })
    }.show()

    // ------------------------------------------------------------------------------------------------------ Parts

    internal fun label(context: Context, value: String, size: Float, medium: Boolean, color: Int) = TextView(context).apply {
        text = value; textSize = size; typeface = if (medium) Tokens.SANS_MEDIUM else Tokens.SANS; includeFontPadding = false
        setTextColor(color)
    }

    internal fun cardBackground(context: Context) = rounded(Kit.cardFill(context.night), context.dp(Glass.CARD),
        if (context.night) Glass.RULE_NIGHT else Glass.RULE, maxOf(1, context.dpi(.5f)))

    internal fun disc(context: Context, path: String, color: Int, size: Float, icon: Float) = View(context).apply {
        background = Explain.Disc(color, path, icon / size); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dpi(size), context.dpi(size))
    }

    internal fun chevron(context: Context) = View(context).apply {
        background = Kit.Icon(Kit.Paths.CHEVRON, if (context.night) 0x66ffffff else MUTE, context.dpi(18f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Small grey caps over a group, with an optional (i) after them; 32 dp tall. */
    internal fun sectionRow(context: Context, title: String, info: Explain.Button?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(32f)
        addView(TextView(context).apply {
            text = title.uppercase(); textSize = 12.5f; typeface = Typeface.create(Typeface.SANS_SERIF, 600, false); includeFontPadding = false
            letterSpacing = .024f; setTextColor(Kit.caps(context.night)); setPadding(context.dpi(2f), 0, 0, 0)
            isAccessibilityHeading = true
        })
        info?.let { addView(it, LinearLayout.LayoutParams(context.dpi(44f), context.dpi(44f)).apply {
            marginStart = context.dpi(5f); topMargin = -context.dpi(6f); bottomMargin = -context.dpi(6f)
        }) }
    }

    internal fun caption(context: Context, value: String) = label(context, value, 12.5f, false, Glass.secondary(context.night)).apply {
        setPadding(context.dpi(4f), 0, 0, 0)
    }

    /** A 60 dp card with a green disc, a title and a switch; a tap anywhere on it flips the switch. */
    internal fun switchTile(context: Context, path: String, title: String, switch: Kit.Switch) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = cardBackground(context); foreground = ripple(night, context.dp(Glass.CARD))
        minimumHeight = context.dpi(60f); setPadding(context.dpi(12f), context.dpi(8f), context.dpi(14f), context.dpi(8f))
        addView(disc(context, path, green(night), 36f, 20f))
        addView(label(context, title, 15f, true, Glass.text(night)).apply { maxLines = if (context.largeText) Int.MAX_VALUE else 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(12f); marginEnd = context.dpi(8f) })
        addView(switch)
        setOnClickListener { switch.performClick() }
        // One stop for a screen reader: the title and the switch's state (as Kit.row).
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name; info.isCheckable = true; info.isChecked = switch.isChecked
                info.contentDescription = title
            }
        }
    }

    /** One of the four "Where it syncs" tiles: a disc, a title, its state and a chevron; 110 dp tall. */
    internal class PlaceTile(context: Context, path: String, color: Int, private val title: String, chevron: Boolean = true, discSize: Float = 40f) : FrameLayout(context) {
        private val night = context.night
        private val stateView = TextView(context).apply {
            textSize = 13f; typeface = Tokens.SANS; includeFontPadding = false
            maxLines = if (context.largeText) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = context.dpi(6f)
        }
        init {
            background = cardBackground(context); foreground = ripple(night, context.dp(Glass.CARD))
            minimumHeight = context.dpi(110f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dpi(14f), context.dpi(14f), context.dpi(14f), context.dpi(10f))
                addView(disc(context, path, color, discSize, discSize * .55f))
                addView(label(context, title, 15f, true, Glass.text(night)).apply { maxLines = if (context.largeText) 3 else 2 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(10f) })
                addView(stateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(5f) })
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (chevron) addView(chevron(context), LayoutParams(context.dpi(18f), context.dpi(18f), Gravity.END or Gravity.TOP).apply {
                topMargin = context.dpi(22f); marginEnd = context.dpi(12f)
            })
            isClickable = chevron; isFocusable = true
        }
        /** Shows [text] in [color], after a small [dot] when given. */
        fun state(text: String, color: Int, dot: Int? = null) {
            stateView.text = text; stateView.setTextColor(color)
            stateView.setCompoundDrawablesRelative(dot?.let { dotDrawable(context, it) }, null, null, null)
            contentDescription = "$title, $text"
        }
        override fun setOnClickListener(l: OnClickListener?) = super.setOnClickListener { v -> runCatching { l?.onClick(v) } }
    }

    /** A 60 dp card row under "Also": a disc, a title over a short line, a chevron. */
    internal class RowCard(context: Context, path: String, color: Int, private val title: String, titleColor: Int? = null) : LinearLayout(context) {
        private val night = context.night
        private val line = label(context, "", 12.5f, false, Glass.secondary(night)).apply {
            maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END; compoundDrawablePadding = context.dpi(6f)
        }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(context); foreground = ripple(night, context.dp(Glass.CARD))
            minimumHeight = context.dpi(60f); setPadding(context.dpi(12f), context.dpi(8f), context.dpi(12f), context.dpi(8f))
            addView(disc(context, path, color, 36f, 20f))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(label(context, title, 15f, true, titleColor ?: Glass.text(night)).apply { maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END })
                addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(3f) })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(12f); marginEnd = context.dpi(8f) })
            addView(chevron(context), LayoutParams(context.dpi(18f), context.dpi(18f)))
            isClickable = true; isFocusable = true
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .55f }
        fun state(text: String, dot: Int?) {
            line.text = text
            line.setCompoundDrawablesRelative(dot?.let { dotDrawable(context, it) }, null, null, null)
            contentDescription = "$title, $text"
        }
        override fun setOnClickListener(l: OnClickListener?) = super.setOnClickListener { v -> runCatching { l?.onClick(v) } }
    }

    private fun dotDrawable(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setBounds(0, 0, context.dpi(8f), context.dpi(8f))
    }

    /** A first-choice card ("Choose a folder", "WebDAV server"); the recommended one has an accent ring and a badge. */
    internal fun choiceCard(context: Context, path: String, color: Int, title: String, detail: String, recommended: Boolean, chevron: Boolean = false, onClick: () -> Unit) =
        LinearLayout(context).apply {
            val night = context.night
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            background = if (recommended) rounded(Explain.alpha(Glass.accent(night), if (night) .12f else .05f), context.dp(Glass.CARD),
                Explain.alpha(Glass.accent(night), .55f), context.dpi(1.5f)) else cardBackground(context)
            foreground = ripple(night, context.dp(Glass.CARD))
            minimumHeight = context.dpi(104f); setPadding(context.dpi(16f), context.dpi(14f), context.dpi(14f), context.dpi(14f))
            addView(disc(context, path, color, 40f, 22f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context, title, 16f, true, if (recommended) Kit.accentText(night) else Glass.text(night)).apply { setPadding(0, context.dpi(2f), 0, 0) })
                addView(label(context, detail, 13f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 4 else 2 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(5f) })
                if (recommended) addView(label(context, "Recommended", 12f, true, Kit.accentText(night)).apply {
                    background = rounded(Explain.alpha(Glass.accent(night), .10f), context.dp(9f)); setPadding(context.dpi(8f), context.dpi(2f), context.dpi(8f), context.dpi(2f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(12f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(14f) })
            if (chevron) addView(View(context).apply {
                background = Kit.Icon(Kit.Paths.CHEVRON, Glass.accent(night), context.dpi(18f)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dpi(18f), context.dpi(18f)).apply { gravity = Gravity.CENTER_VERTICAL })
            isClickable = true; isFocusable = true; contentDescription = "$title. $detail"
            setOnClickListener { runCatching(onClick) }
        }

    /** The owner's tools behind one dashed row. */
    private fun diagnosticsRow(context: Context, detail: String, onClick: () -> Unit) = dashedRow(context, P.WRENCH, "Diagnostics", detail, onClick)
    internal fun dashedRow(context: Context, path: String, title: String, detail: String, onClick: () -> Unit) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = context.dp(16f)
            setStroke(context.dpi(1.5f), Explain.alpha(slate(night), .45f), context.dp(5f), context.dp(4f))
        }
        foreground = ripple(night, context.dp(16f))
        minimumHeight = context.dpi(56f); setPadding(context.dpi(12f), context.dpi(8f), context.dpi(14f), context.dpi(8f))
        addView(disc(context, path, slate(night), 36f, 20f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context, title, 15f, true, Glass.text(night)))
            addView(label(context, detail, 12.5f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 4 else 2 },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(3f) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(12f); marginEnd = context.dpi(8f) })
        addView(chevron(context), LinearLayout.LayoutParams(context.dpi(18f), context.dpi(18f)))
        isClickable = true; isFocusable = true; contentDescription = title
        setOnClickListener { runCatching(onClick) }
    }

    /** Stop syncing (or Turn off instant sync): a small red action in the middle, under everything. */
    internal fun stopRow(context: Context, onClick: () -> Unit) = stopRow(context, "Stop syncing", onClick)
    internal fun stopRow(context: Context, title: String, onClick: () -> Unit) = FrameLayout(context).apply {
        val night = context.night
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpi(40f); setPadding(context.dpi(18f), 0, context.dpi(18f), 0)
            background = ripple(night, context.dp(20f))
            addView(View(context).apply { background = Kit.Icon(P.STOP, fault(night), context.dpi(22f)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(context.dpi(22f), context.dpi(22f)))
            addView(label(context, title, 15f, true, fault(night)).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(8f) })
            isClickable = true; isFocusable = true; contentDescription = title
            setOnClickListener { runCatching(onClick) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    /**
     * Tiles in equal columns, at least [minColumn] dp wide (wider when text is set large), each row as tall as its
     * tallest tile: four across on a tablet, two on a phone.
     */
    internal class TileGrid(context: Context, private val minColumn: Float, private val gap: Float = 12f, private val stretch: Boolean = true) : ViewGroup(context) {
        private var columns = 1
        private val rows = mutableListOf<Int>()
        private fun shown() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val g = context.dpi(gap)
            val tiles = shown()
            val least = context.dpi(minColumn * resources.configuration.fontScale.coerceIn(1f, 1.6f))
            columns = ((w + g) / (least + g)).coerceIn(1, if (stretch) maxOf(1, tiles.size) else 4)
            val cell = (w - g * (columns - 1)) / columns
            rows.clear()
            var height = 0
            tiles.chunked(columns).forEachIndexed { i, row ->
                row.forEach { it.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
                val tall = row.maxOf { it.measuredHeight }
                row.forEach { it.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tall, MeasureSpec.EXACTLY)) }
                rows += tall
                height += tall + if (i > 0) g else 0
            }
            setMeasuredDimension(w, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val g = context.dpi(gap)
            val cell = (r - l - g * (columns - 1)) / columns
            var y = 0
            shown().chunked(columns).forEachIndexed { i, row ->
                row.forEachIndexed { j, v -> val x = j * (cell + g); v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight) }
                y += rows.getOrElse(i) { 0 } + g
            }
        }
    }

    /**
     * The board's status card: this device ⇄ the folder or server, joined by a line, with a sync circle in the middle;
     * the state under it, and Pause and an (i) on top. Green when synced (a moving bar while it syncs), amber when paused
     * or waiting, red when it needs attention. [newcomer] is the card before a folder is chosen: a dashed folder and an
     * invitation in place of the state.
     */
    internal class StatusCard(context: Context, private val newcomer: Boolean, webDav: Boolean, place: String, device: String) : ViewGroup(context) {
        enum class Look { SYNCED, SYNCING, PAUSED, WAITING, FAILED, NOT_YET }
        private val night = context.night
        var look = Look.NOT_YET; private set
        private var fraction: Float? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f)
        private val deviceDisc = Explain.Disc(Glass.accent(night), P.TABLET, .5f, fill = .10f)
        private val placeDisc = if (newcomer) Explain.Disc(green(night), P.FOLDER_PLUS, .5f, dashed = true)
            else Explain.Disc(green(night), if (webDav) P.SERVER else P.FOLDER, .5f)
        private var middle = Kit.Icon(P.SYNC, green(night), 0)
        var onPause: () -> Unit = {}
        var onStatus: () -> Unit = {}
        val info = Explain.Button(context, "How Library sync works")
        private val chipIcon = View(context)
        private val chipText = label(context, "Pause", 14f, true, Glass.text(night))
        val chip: LinearLayout? = if (newcomer) null else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f1d2433),
                rounded(if (night) 0x1affffff else 0x0f1d2433, context.dp(16f)), null)
            setPadding(context.dpi(12f), 0, context.dpi(16f), 0)
            addView(chipIcon, LinearLayout.LayoutParams(context.dpi(18f), context.dpi(18f)))
            addView(chipText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(6f) })
            isClickable = true; isFocusable = true
            setOnClickListener { runCatching { onPause() } }
        }
        private val left = if (newcomer) null else centred(label(context, "This $device", 14f, true, Glass.text(night)))
        private val right = if (newcomer) null else centred(label(context, place, 14f, true, Glass.text(night)))
        val status = centred(if (newcomer) label(context, "Keep your notebooks on every device", 18f, true, Glass.text(night))
            else label(context, "", 17f, true, Glass.text(night)).apply {
                compoundDrawablePadding = context.dpi(8f); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setOnClickListener { runCatching { onStatus() } }
            })
        val detail = centred(if (newcomer) label(context, "They stay on this $device too.", 14f, false, Glass.secondary(night))
            else label(context, "", 13f, false, Glass.secondary(night)).apply { setOnClickListener { runCatching { onStatus() } } })

        init {
            setWillNotDraw(false)
            chip?.let { addView(it) }
            addView(info)
            left?.let { addView(it) }; right?.let { addView(it) }
            addView(status); addView(detail)
            paused(false)
        }
        private fun centred(v: TextView) = v.apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END }

        fun paused(paused: Boolean) {
            chipText.text = if (paused) "Resume" else "Pause"
            chipIcon.background = Kit.Icon(if (paused) P.PLAY else P.PAUSE, Glass.text(night), dpi(18f))
        }

        /** Shows [look] with its headline [text] and the line under it, [under]; [fraction] fills the bar while syncing. */
        fun show(look: Look, text: String, under: String, fraction: Float? = null) {
            val moved = text != status.text.toString() || under != detail.text.toString() || look != this.look
            this.look = look; this.fraction = fraction
            val c = colour()
            status.text = text
            status.setTextColor(if (look == Look.SYNCING) c else Glass.text(night))
            val badge = when (look) { Look.SYNCED -> P.CHECK; Look.PAUSED -> P.PAUSE; Look.WAITING -> P.CLOCK; Look.FAILED -> P.ALERT; else -> null }
            status.setCompoundDrawablesRelative(badge?.let { Explain.Badge(c, it, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) } }, null, null, null)
            detail.text = under
            detail.visibility = if (under.isEmpty()) View.GONE else View.VISIBLE
            middle = Kit.Icon(when (look) { Look.PAUSED -> P.PAUSE; Look.WAITING -> P.CLOCK; Look.FAILED -> P.ALERT; else -> P.SYNC }, c, 0)
            chip?.visibility = if (look == Look.SYNCING) View.INVISIBLE else View.VISIBLE
            if (moved) requestLayout()
            invalidate()
        }

        private fun colour() = when (look) {
            Look.SYNCED, Look.SYNCING -> green(night)
            Look.PAUSED, Look.WAITING -> amber(night)
            Look.FAILED -> fault(night)
            Look.NOT_YET -> slate(night)
        }

        private fun centres(w: Int): Pair<Int, Int> {
            val off = minOf(dpi(if (newcomer) 150f else 170f), w / 2 - dpi(56f)).coerceAtLeast(dpi(44f))
            return (w / 2 - off) to (w / 2 + off)
        }
        private val discTop get() = dpi(if (newcomer) 30f else 34f)
        private fun statusTop() = if (newcomer) dpi(122f) else dpi(114f) + maxOf(left!!.measuredHeight, right!!.measuredHeight) + dpi(16f)
        private fun detailTop() = statusTop() + status.measuredHeight + dpi(if (newcomer) 7f else 6f)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun most(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.AT_MOST)
            fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)
            chip?.measure(free, exactly(dpi(32f)))
            info.measure(exactly(dpi(44f)), exactly(dpi(44f)))
            val (lx, rx) = centres(w)
            fun room(x: Int) = minOf(dpi(200f), 2 * minOf(x, w - x) - dpi(8f))
            left?.measure(most(room(lx)), free); right?.measure(most(room(rx)), free)
            status.measure(most(w - dpi(32f)), free); detail.measure(most(w - dpi(32f)), free)
            val end = if (detail.visibility == View.GONE) statusTop() + status.measuredHeight + dpi(16f)
                else detailTop() + detail.measuredHeight + dpi(if (newcomer) 20f else 10f)
            setMeasuredDimension(w, end)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            chip?.let { it.layout(dpi(16f), dpi(16f), dpi(16f) + it.measuredWidth, dpi(16f) + it.measuredHeight) }
            // The (i)'s 32 dp circle sits at (w - 46, 14), in its 44 dp target.
            info.layout(w - dpi(52f), dpi(8f), w - dpi(8f), dpi(52f))
            val (lx, rx) = centres(w)
            left?.let { it.layout(lx - it.measuredWidth / 2, dpi(114f), lx + (it.measuredWidth + 1) / 2, dpi(114f) + it.measuredHeight) }
            right?.let { it.layout(rx - it.measuredWidth / 2, dpi(114f), rx + (it.measuredWidth + 1) / 2, dpi(114f) + it.measuredHeight) }
            val s = statusTop()
            status.layout((w - status.measuredWidth) / 2, s, (w + status.measuredWidth) / 2, s + status.measuredHeight)
            val d = detailTop()
            detail.layout((w - detail.measuredWidth) / 2, d, (w + detail.measuredWidth) / 2, d + detail.measuredHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = dp(Glass.CARD)
            val fill = Kit.cardFill(night)
            val c = if (newcomer) green(night) else colour()
            // The card: a faint wash of the state's colour at the top, fading to the card's own fill.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, 0f, h * .7f, blend(fill, c, if (night) .10f else .06f), fill, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            paint.shader = null
            val hair = maxOf(1f, dp(.5f))
            paint.style = Paint.Style.STROKE; paint.strokeWidth = hair; paint.color = if (night) Glass.RULE_NIGHT else Glass.RULE
            canvas.drawRoundRect(hair / 2, hair / 2, w - hair / 2, h - hair / 2, r, r, paint)

            val (lx, rx) = centres(width)
            val cy = discTop + dpi(36f)
            val d = dpi(36f)
            deviceDisc.setBounds(lx - d, cy - d, lx + d, cy + d); deviceDisc.draw(canvas)
            placeDisc.setBounds(rx - d, cy - d, rx + d, cy + d); placeDisc.draw(canvas)

            // The line between them: dashed before a folder is chosen and while paused.
            val x1 = lx + dp(44f); val x2 = rx - dp(44f)
            paint.strokeWidth = dp(2.5f); paint.strokeCap = Paint.Cap.BUTT
            paint.color = if (newcomer) (if (night) 0x40ffffff else 0x401d2433) else Explain.alpha(c, .45f)
            paint.pathEffect = if (newcomer || look == Look.PAUSED) dash else null
            canvas.drawLine(x1, cy.toFloat(), x2, cy.toFloat(), paint)
            paint.pathEffect = null
            if (newcomer) return

            if (look == Look.SYNCING && x2 > x1) {
                val length = x2 - x1
                val (a, b) = fraction?.let { x1 to x1 + length * it } ?: run {
                    // No count to show: a short bar moves along the line.
                    val phase = if (Motion.enabled) (SystemClock.uptimeMillis() % 1600L) / 1600f else .5f
                    val piece = length * .3f
                    val start = x1 - piece + (length + piece) * phase
                    maxOf(x1, start) to minOf(x2, start + piece)
                }
                paint.style = Paint.Style.FILL; paint.color = c
                if (b > a) canvas.drawRoundRect(a, cy - dp(2f), b, cy + dp(2f), dp(2f), dp(2f), paint)
                if (fraction == null && Motion.enabled) postInvalidateOnAnimation()
            }
            // The circle in the middle, in the state's colour.
            val cx = width / 2f
            paint.style = Paint.Style.FILL; paint.color = fill
            canvas.drawCircle(cx, cy.toFloat(), dp(20f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Explain.alpha(c, .45f)
            canvas.drawCircle(cx, cy.toFloat(), dp(20f), paint)
            val i = dpi(11f)
            middle.setBounds(cx.roundToInt() - i, cy - i, cx.roundToInt() + i, cy + i); middle.draw(canvas)
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xff) + ((((b shr shift) and 0xff) - ((a shr shift) and 0xff)) * t)).roundToInt()
            return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
