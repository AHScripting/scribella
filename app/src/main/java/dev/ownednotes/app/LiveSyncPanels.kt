package dev.ownednotes.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.app.SyncPanels.P
import dev.ownednotes.core.LiveCrypto
import dev.ownednotes.core.LiveSyncClient

/**
 * Instant sync sheets (docs/LIVE-SYNC-PLAN.md, menu placement: Library → Sync library → Instant sync). P731: drawn as
 * design canvas board 22, with Library sync's parts ([SyncPanels], [Explain]) and its own ([LiveSyncLook]).
 */
internal object LiveSyncPanels {
    private val MainActivity.notes get() = application as NotesApplication

    /** "tablet" or "phone", by the screen's smallest width. */
    private fun deviceWord(context: Context) = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    private fun howItWorks(night: Boolean) = listOf(
        Explain.Line(P.BOLT, Glass.accent(night), "Each change reaches your other devices within seconds."),
        Explain.Line(P.LOCK, SyncPanels.green(night), "Encrypted on this device; the server cannot read it."),
        Explain.Line(P.SERVER, SyncPanels.slate(night), "A small free server of your own, on Cloudflare."),
        Explain.Line(P.FOLDER, SyncPanels.green(night), "Folder sync stays on: your backup, and photos, PDFs and files."))

    private fun adding(night: Boolean) = listOf(
        Explain.Line(P.QR, Glass.accent(night), "There: Instant sync, then Scan QR code or Paste a sync code."),
        Explain.Line(P.LOCK, SyncPanels.fault(night), "Only your own devices: anyone with the code can read your synced changes."))

    private fun goodToKnow(night: Boolean) = listOf(
        Explain.Line(P.SERVER, SyncPanels.slate(night), "Cloudflare's free plan is plenty for one person."),
        Explain.Line(P.BOX, SyncPanels.slate(night), "Unzip on Windows: right-click the zip, then Extract All."),
        Explain.Line(P.KEY, SyncPanels.amber(night), "If Wrangler asks for a workers.dev name, any free name works."),
        Explain.Line(P.MOON, SyncPanels.green(night), "Works while Scribella is open, or always with Keep receiving in the background. If Android still stops it, set Scribella's battery use to Unrestricted."),
        Explain.Line(P.REPEAT, Glass.accent(night), "Changes made offline are sent when the device reconnects."),
        Explain.Line(P.FOLDER, SyncPanels.green(night), "Photos, PDFs and attached files still go through folder sync: keep a sync folder set."))

    private fun terminalHelp(night: Boolean) = listOf(
        Explain.Line(P.TERMINAL, Glass.text(night), "Right-click an empty spot in the folder, not on a file."),
        Explain.Line(P.TERMINAL, SyncPanels.slate(night), "Windows 10: hold Shift while you right-click, then Open PowerShell window here."),
        Explain.Line(P.CHECK, SyncPanels.green(night), "The line where you type ends with scribella-sync-server>."))

    /**
     * Set up, check, share or turn off instant sync on this device (P731, design canvas board 22, from the Library sync
     * sheet's Instant sync tile). Off: the card, then First device (server address, Create sync code, How to make a
     * server) and Join a device (Scan QR code, Paste a sync code). On: the card with its state, Add a device tiles, the
     * background switch, the guide and the server files, and Turn off. Explanations are behind the (i) buttons.
     */
    fun open(activity: MainActivity, context: Context = activity): Kit.Sheet {
        val live = activity.notes.liveSync
        val night = context.night
        val sheet = Kit.Sheet(context, "Instant sync", cancel = "Done")
        val code = live.client.syncCode
        val card = LiveSyncLook.Card(context, off = code == null, device = deviceWord(context))
        card.info.setOnClickListener { Explain.show(card.info, "How instant sync works", howItWorks(night)) }
        card.onRetry = { live.start() }
        val watcher = { if (code != null) LiveSyncLook.describe(live.status).let { (look, text, under) -> card.show(look, text, under) } }
        watcher()
        live.watch(watcher)
        sheet.dialog.setOnDismissListener { live.unwatch(watcher) }
        sheet.add(card)
        if (code == null) {
            val address = Kit.field(context, "Server address").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                contentDescription = "Server address"; setHintTextColor(SyncPanels.mute(night))
            }
            val problem = Kit.text(context, "", Kit.Type.CAPTION, secondary = true).apply { visibility = View.GONE }
            fun say(text: String) { problem.text = text; problem.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
            lateinit var create: TextView
            create = LiveSyncLook.pill(context, "Create sync code", Kit.Button.PRIMARY) {
                val server = LiveCrypto.server(address.text.toString())
                if (server == null) { say("Enter the address Cloudflare printed, like https://scribella-sync.yourname.workers.dev"); return@pill }
                create.isEnabled = false; say("Checking the server…")
                check(server) { ok, message ->
                    activity.notes.main.post {
                        create.isEnabled = true
                        if (!ok) { say(message); return@post }
                        val made = LiveCrypto.SyncCode(server, LiveCrypto.newKey())
                        Thread { live.setCode(made); activity.notes.main.post { sheet.dismiss(); activity.notes.liveSync.start(); open(activity, context); activity.message("Instant sync is on. Share the sync code with your other devices.") } }.start()
                    }
                }
            }
            val first = LiveSyncLook.choice(context, P.SERVER, Glass.accent(night), "First device", "Uses a free server of your own", first = true, content = listOf(
                address to 22f, create to 10f, problem to 8f,
                LiveSyncLook.linkRow(context, P.BOOK, "How to make a server") { guide(activity, context) } to 14f))
            val join = LiveSyncLook.choice(context, P.DEVICES, SyncPanels.green(night), "Join a device", "Instant sync is on elsewhere", first = false, content = listOf(
                LiveSyncLook.hint(context, P.TABLET, "There: Instant sync, then Show QR code") to 22f,
                LiveSyncLook.pill(context, "Scan QR code", Kit.Button.TINTED, P.QR) { scan(activity, context, sheet) } to 10f,
                LiveSyncLook.pill(context, "Paste a sync code", Kit.Button.PLAIN, P.PASTE, 36f) { sheet.dismiss(); join(activity, context) } to 14f))
            sheet.add(SyncPanels.TileGrid(context, 300f).apply { addView(first); addView(join) })
        } else {
            val about = Explain.Button(context, "About adding a device")
            about.setOnClickListener { Explain.show(about, "Adding a device", adding(night)) }
            sheet.add(SyncPanels.sectionRow(context, "Add a device", about), gap = 16f)
            sheet.add(SyncPanels.TileGrid(context, 150f).apply {
                addView(SyncPanels.PlaceTile(context, P.QR, Glass.accent(night), "Show QR code").apply {
                    state("Scan it there", Glass.secondary(night)); setOnClickListener { showQr(activity, context, code) }
                })
                addView(SyncPanels.PlaceTile(context, P.SHARE, Glass.accent(night), "Share code").apply {
                    state("Send it to yourself", Glass.secondary(night))
                    setOnClickListener {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.text)
                        runCatching { activity.startActivity(Intent.createChooser(send, "Send the sync code to your other device")) }
                            .onFailure { activity.message("No app here can send text. Use Copy code instead.") }
                    }
                })
                addView(SyncPanels.PlaceTile(context, P.COPY, Glass.accent(night), "Copy code").apply {
                    state("Paste it there", Glass.secondary(night)); setOnClickListener { copy(activity, code.text) }
                })
            }, gap = 2f)
            // P702 (step 9): opt in to receiving with no screen, under a quiet notification.
            sheet.add(SyncPanels.sectionRow(context, "While Scribella is closed", null), gap = 16f)
            val background = Kit.Switch(context, LiveSyncBackground.enabled(activity.notes), "Keep receiving in the background") { on ->
                if (on) activity.askForNotifications()
                LiveSyncBackground.setEnabled(activity.notes, on)
                watcher()
            }
            sheet.add(SyncPanels.switchTile(context, P.MOON, "Keep receiving in the background", background), gap = 2f)
            sheet.add(SyncPanels.caption(context, "A small notification shows while it listens · uses a little battery"), gap = 8f)
            sheet.add(SyncPanels.TileGrid(context, 300f).apply {
                addView(SyncPanels.RowCard(context, P.BOOK, Glass.accent(night), "How to set it up").apply {
                    state("The steps, with pictures", null); setOnClickListener { guide(activity, context) }
                })
                addView(SyncPanels.RowCard(context, P.DOWNLOAD, SyncPanels.slate(night), "Server files").apply {
                    state("Save them to update your server", null); setOnClickListener { activity.saveSyncServerFiles() }
                })
            }, gap = 18f)
            sheet.add(SyncPanels.stopRow(context, "Turn off instant sync") { confirmOff(activity, context, sheet) }, gap = 16f)
        }
        return sheet.show()
    }

    /**
     * The set-up guide (P731, board 22): three chips (time, where, how often), nine short numbered steps with small
     * pictures of what the computer shows, and Stuck? Ask an AI. The longer notes are behind the (i) buttons; the full
     * version is README.md inside the saved server files.
     */
    fun guide(activity: MainActivity, context: Context = activity): Kit.Sheet {
        val night = context.night
        val sheet = Kit.Sheet(context, "Set up instant sync", cancel = "Done")
        val know = Explain.Button(context, "Good to know")
        know.setOnClickListener { Explain.show(know, "Good to know", goodToKnow(night)) }
        sheet.add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            addView(LiveSyncLook.Flow(context).apply {
                addView(LiveSyncLook.chip(context, P.CLOCK, "About 15 minutes"))
                addView(LiveSyncLook.chip(context, P.TERMINAL, "On a computer"))
                addView(LiveSyncLook.chip(context, P.REPEAT, "Only once"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(know, LinearLayout.LayoutParams(context.dpi(44f), context.dpi(44f)).apply { topMargin = -context.dpi(6f); marginEnd = -context.dpi(2f) })
        })
        val prompt = 0xffe6e9ef.toInt(); val typed = 0xff7ee2a0.toInt(); val printed = 0xff9cc8ff.toInt()
        val node = if (night) 0xff5fb35c.toInt() else 0xff3c873a.toInt()
        val steps = listOf(
            LiveSyncLook.Step(P.CLOUD, Glass.accent(night), "Make a free Cloudflare account", "dash.cloudflare.com/sign-up"),
            LiveSyncLook.Step(P.BOX, node, "Install Node.js", "The LTS version, from nodejs.org"),
            LiveSyncLook.Step(P.DOWNLOAD, SyncPanels.slate(night), "Save the server files", "Copy the zip to the computer and unzip it") { c ->
                LinearLayout(c).apply {
                    addView(LiveSyncLook.pill(c, "Save server files", Kit.Button.TINTED, P.DOWNLOAD, 36f) { activity.saveSyncServerFiles() }.apply {
                        setPadding(c.dpi(54f), 0, c.dpi(28f), 0)
                    })
                }
            },
            LiveSyncLook.Step(P.TERMINAL, Glass.text(night), "Open a terminal in that folder", "Right-click an empty spot, then Open in Terminal") { c ->
                LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                    addView(LiveSyncLook.terminal(c, listOf("PS C:\\…\\scribella-sync-server>" to prompt)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val help = Explain.Button(c, "Opening a terminal")
                    help.setOnClickListener { Explain.show(help, "Opening a terminal", terminalHelp(night)) }
                    addView(help, LinearLayout.LayoutParams(c.dpi(44f), c.dpi(44f)).apply { marginStart = c.dpi(2f); topMargin = -c.dpi(2f) })
                }
            },
            LiveSyncLook.Step(P.KEY, SyncPanels.amber(night), "Sign in to Cloudflare", "Type it, press Enter, then Allow in the browser") { c ->
                LiveSyncLook.terminal(c, listOf("> npx wrangler login" to typed))
            },
            LiveSyncLook.Step(P.SERVER, SyncPanels.green(night), "Put your server online", "It prints your server address") { c ->
                LiveSyncLook.terminal(c, listOf("> npx wrangler deploy" to typed, "https://scribella-sync.yourname.workers.dev" to printed))
            },
            LiveSyncLook.Step(P.GLOBE, Glass.accent(night), "Check it in a browser", "Open that address") { c -> LiveSyncLook.browser(c) },
            LiveSyncLook.Step(P.TABLET, Glass.accent(night), "First device", "Instant sync: paste the address, then Create sync code"),
            LiveSyncLook.Step(P.QR, Glass.accent(night), "Each other device", "Instant sync: Scan QR code, pointed at the first device"),
        )
        // On a phone the step pictures start under the numbers' discs, so the terminal keeps its width.
        val narrow = minOf(context.resources.configuration.screenWidthDp, 760) - 44 < 440
        sheet.add(LiveSyncLook.Steps(context, steps, if (narrow) 46f else 98f), gap = 16f)
        sheet.add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            background = SyncPanels.cardBackground(context)
            setPadding(context.dpi(14f), context.dpi(14f), context.dpi(16f), context.dpi(12f))
            addView(SyncPanels.disc(context, P.SPARK, if (night) 0xff9d8bff.toInt() else 0xff7b61ff.toInt(), 40f, 22f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(SyncPanels.label(context, "Stuck? Ask an AI", 15.5f, true, Glass.text(night)).apply { isAccessibilityHeading = true })
                addView(SyncPanels.label(context, "Give Claude, ChatGPT or Gemini the README.md from the server files and say your step. Never your Cloudflare password or sync code.",
                    13f, false, Glass.secondary(night)).apply { setLineSpacing(context.dp(3.8f), 1f) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(4f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(14f) })
        }, gap = 32f)
        return sheet.show()
    }

    private fun join(activity: MainActivity, context: Context): Kit.Sheet {
        val field = Kit.field(context, "Paste the sync code").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            contentDescription = "Sync code"
        }
        val problem = Kit.text(context, "", Kit.Type.CAPTION, secondary = true)
        lateinit var sheet: Kit.Sheet
        sheet = Kit.Sheet(context, "Join instant sync", "Join") {
            sheet.actionButton?.isEnabled = false
            joinWith(activity, context, field.text.toString(), sheet, report = { problem.text = it }) { sheet.actionButton?.isEnabled = true }
            false
        }
        sheet.add(Kit.text(context, "On a device that already has instant sync: Instant sync → Share code. Then paste the code here.", secondary = true))
        sheet.add(field); sheet.add(problem, 8f)
        sheet.add(Kit.button(context, "Scan QR code instead…", Kit.Button.PLAIN) { scan(activity, context, sheet) })
        sheet.dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        sheet.dialog.setOnDismissListener { field.text.clear() }
        return sheet.show()
    }

    /**
     * Checks a sync code (typed, pasted or scanned) and its server, then turns instant sync on and reopens the Instant sync
     * sheet. [report] shows progress and problems; [finished] runs when it stops without joining.
     */
    private fun joinWith(activity: MainActivity, context: Context, text: String, parent: Kit.Sheet?, report: (String) -> Unit, finished: () -> Unit = {}) {
        val live = activity.notes.liveSync
        val code = LiveCrypto.parse(text)
        if (code == null) { report("That is not a whole Scribella sync code. It starts with ${LiveCrypto.CODE_PREFIX}"); finished(); return }
        report("Checking the server…")
        check(code.server) { ok, message ->
            activity.notes.main.post {
                if (!ok) { report(message); finished(); return@post }
                Thread { live.setCode(code); activity.notes.main.post { parent?.dismiss(); live.start(); open(activity, context); activity.message("Instant sync is on for this device.") } }.start()
            }
        }
    }

    /** The sync code as a QR code, for Scan QR code on another device. FLAG_SECURE keeps it out of screenshots. */
    fun showQr(activity: MainActivity, context: Context, code: LiveCrypto.SyncCode): Kit.Sheet {
        val sheet = Kit.Sheet(context, "Sync code QR", cancel = "Done", maxWidth = 460f)
        sheet.add(Kit.text(context, "On your other device: Library → Sync library → Instant sync… → Scan QR code, and point the camera here.", secondary = true))
        val image = android.widget.ImageView(context).apply {
            setImageBitmap(qrBitmap(code.text))
            adjustViewBounds = true; maxHeight = (340 * resources.displayMetrics.density).toInt()
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "QR code holding this device's sync code"
        }
        sheet.add(image)
        sheet.add(Kit.text(context, "Show it only to your own devices: anyone who scans it can read your synced changes.", Kit.Type.CAPTION, secondary = true))
        sheet.dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        return sheet.show()
    }

    /** Black on white with a 4-module quiet zone, so phone cameras read it in light or dark mode. */
    internal fun qrBitmap(text: String, size: Int = 720): android.graphics.Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 4, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /**
     * Google's code scanner (Google Play services shows the camera; Scribella needs no camera permission). A scanned sync
     * code joins as if pasted. If the scanner is not on the device yet, Google Play services fetches it first.
     */
    private fun scan(activity: MainActivity, context: Context, parent: Kit.Sheet?) {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        val scanner = runCatching { com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(activity, options) }.getOrElse {
            activity.message("The QR scanner is not available here. Use Paste a sync code instead."); return
        }
        val failed = { e: Exception -> activity.message("The QR scanner could not start (${e.message ?: "Google Play services"}). Use Paste a sync code instead.") }
        val start = {
            scanner.startScan()
                .addOnSuccessListener { barcode -> joinWith(activity, context, barcode.rawValue.orEmpty(), parent, report = { activity.message(it) }) }
                .addOnFailureListener { failed(it) }
        }
        val modules = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(activity)
        modules.areModulesAvailable(scanner).addOnSuccessListener { available ->
            if (available.areModulesAvailable()) { start(); return@addOnSuccessListener }
            activity.message("Getting the QR scanner from Google Play services…")
            modules.installModules(com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder().addApi(scanner).build())
                .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) start() else activity.message("The QR scanner is downloading. Choose Scan QR code again in a minute.") }
                .addOnFailureListener { failed(it) }
        }.addOnFailureListener { failed(it) }
    }

    private fun confirmOff(activity: MainActivity, context: Context, parent: Kit.Sheet) {
        val live = activity.notes.liveSync
        Kit.Sheet(context, "Turn off instant sync?", "Turn off") {
            Thread { live.setCode(null); activity.notes.main.post { LiveSyncBackground.setEnabled(activity.notes, false); parent.dismiss(); activity.message("Instant sync is off on this device. Your notes are unchanged; folder sync carries on.") } }.start()
            true
        }.apply {
            add(Kit.text(context, "This device forgets the sync code and stops sending and receiving instant changes. No notes are deleted, here or on the server's other devices. To turn it on again, join with the same sync code.", secondary = true))
        }.show()
    }

    private fun copy(activity: MainActivity, text: String) {
        val clip = ClipData.newPlainText("Scribella sync code", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        activity.message("Sync code copied. Paste it only on your own devices.")
    }

    /** Opens the server's front page on a background thread and checks it is a Scribella sync server. */
    private fun check(server: String, done: (Boolean, String) -> Unit) = Thread {
        val result = runCatching {
            val connection = java.net.URL("$server/").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            try {
                val code = connection.responseCode
                val body = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText().take(2000) } else ""
                code to body
            } finally { connection.disconnect() }
        }
        result.fold({ (code, body) ->
            when {
                code in 200..299 && body.contains("Scribella sync server") -> done(true, "")
                code in 200..299 -> done(false, "Something answered at this address, but it is not a Scribella sync server. Check the address.")
                else -> done(false, "The server answered $code. Check the address, or run npx wrangler deploy again.")
            }
        }, { done(false, "Could not reach the server: ${it.message ?: "no connection"}. Check the address and the internet connection.") })
    }.start()
}
