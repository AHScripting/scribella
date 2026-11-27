package dev.ownednotes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * P718: the memory summary on a screen of its own, in the app's ":report" process (manifest), so it has a heap of its
 * own. On the Pixel the app's process was so full that even waiting there for another process to read the report ran
 * out of memory ("Failed to allocate a 48 byte allocation"). Now the app only opens this screen: reading, showing,
 * copying and saving all happen here, and the screen stays when the app's process stops. Opened from the crash note and
 * Library sync, and by itself when the app stops out of memory. Personal tools only.
 */
class MemoryReportActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var body: TextView
    private lateinit var copy: TextView
    private lateinit var save: TextView
    private var reading: MemoryReport.Reading? = null
    private var shown: String? = null
    private var saving: String? = null

    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri == null) return@registerForActivityResult
        saving = "Saving the memory report…"; show()
        val app = applicationContext
        Thread({
            val result = runCatching { MemoryReport.save(app, uri) }
            val mb = { bytes: Long -> String.format(Locale.US, "%.1f", bytes / 1048576.0) }
            val text = result.fold({ saved ->
                val there = saved.reported
                if (there != null && there < saved.written) "Wrote ${mb(saved.written)} MB, but the chosen place shows ${mb(there)} MB so far. The report stays on this device."
                else "Memory report saved (${mb(saved.written)} MB, compressed). It stays on this device until deleted in Library sync."
            }, { "The memory report could not be saved: ${it.message ?: it.javaClass.simpleName}" })
            main.post { saving = text; if (!isDestroyed) show() }
        }, "memory-report-save").start()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            show()
            if (reading?.done != true) main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this
        val night = context.night
        val pad = dpi(20f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Kit.desk(night))
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        root.addView(Kit.text(context, "Memory summary", Kit.Type.TITLE))
        status = Kit.text(context, "", secondary = true)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        body = TextView(context).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            setTextColor(if (night) 0xffe6e9ef.toInt() else 0xff1c1f26.toInt())
        }
        root.addView(ScrollView(context).apply { isFillViewport = true; addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12f); bottomMargin = dpi(12f) })
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        copy = Kit.button(context, "Copy", Kit.Button.PRIMARY) { copyText() }
        save = Kit.button(context, "Save whole report…", Kit.Button.PLAIN) { saveReport.launch(MemoryReport.fileName()) }
        buttons.addView(Kit.button(context, "Close", Kit.Button.PLAIN) { finish() })
        buttons.addView(save, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(4f) })
        buttons.addView(copy, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(8f) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        title = "Memory summary"
        runCatching {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        }
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        begin()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    /** Reads the report on this device, or shows the reading already under way or done. */
    private fun begin() {
        reading = MemoryReport.begin(applicationContext)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun show() {
        val r = reading ?: return
        val text = r.text
        val failed = r.failed
        status.text = when {
            saving != null -> saving
            text != null -> "Tap Copy and paste it where you were asked. It holds class and field names and numbers, no notebook content."
            failed != null -> "The memory report could not be read: $failed"
            else -> "Reading the memory report on this device. This can take a minute or two." + r.step.let { if (it.isEmpty()) "" else "\n$it" }
        }
        val now = text ?: failed?.let { "Memory summary: could not be read\n$it" }
        if (now != null && now != shown) { body.text = now; shown = now }
        copy.isEnabled = now != null
        save.isEnabled = MemoryReport.available(this)
    }

    private fun copyText() {
        val text = shown ?: return
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Memory summary", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** For startActivity from any context: the screen has a task of its own (manifest). */
        fun intent(context: android.content.Context) = Intent(context, MemoryReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
