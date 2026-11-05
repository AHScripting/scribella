package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * P563: what anyone who gets the app is told about its licence, its libraries and what Google's library sends.
 * Home: Settings → Privacy & storage → About. Occasional, app scope, nothing to undo; it only reads files the app ships.
 */
internal object AboutNotices {
    const val LICENCE = "Free to use, change and share for noncommercial purposes, under the PolyForm Noncommercial License 1.0.0: polyformproject.org/licenses/noncommercial/1.0.0"

    /** Google's terms require telling people this. It matches developers.google.com/ml-kit/terms and its Play disclosure page. */
    const val ML_KIT = "Reading handwriting and printed text happens on this device, using Google's ML Kit library. Your ink, images and recognised text are not sent anywhere by it. " +
        "ML Kit does send Google usage and performance figures: device model and Android version, this app's name and version, an install identifier, the languages you set up, how long recognition took, input sizes and error codes. " +
        "It also contacts Google to download a handwriting language when you ask for one."

    val FILES = listOf("THIRD-PARTY-NOTICES.txt", "Apache-2.0.txt", "pdfiumandroid-2.0.3-LICENSE.txt", "GOOGLE-LIBRARY-NOTICES.txt")

    /** The notices, split so a list view only lays out what is on screen: the whole text is about 2 MB. */
    fun read(context: Context): List<String> = FILES.flatMap { name ->
        context.assets.open("licenses/$name").bufferedReader().use { it.readLines() }.chunked(40).map { it.joinToString("\n") } + ""
    }

    /** The open notices dialog, for its test. */
    internal var open: AlertDialog? = null
        private set

    fun show(context: Context): AlertDialog {
        val lines = mutableListOf("Loading…")
        val adapter = object : BaseAdapter() {
            override fun getCount() = lines.size
            override fun getItem(position: Int) = lines[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun isEnabled(position: Int) = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                (convertView as? TextView ?: Kit.text(context, "", Kit.Type.CAPTION).apply { textSize = 12f; setPadding(context.dpi(20f), 0, context.dpi(20f), 0) })
                    .apply { text = lines[position] }
        }
        val list = ListView(context).apply { this.adapter = adapter; divider = null; contentDescription = "Licences and notices" }
        val dialog = AlertDialog.Builder(context).setTitle("Licences and notices").setView(list).setPositiveButton("Close", null)
            .setOnDismissListener { open = null }.show()
        open = dialog
        val main = Handler(Looper.getMainLooper())
        thread(name = "about-notices") {
            val text = runCatching { read(context) }.getOrElse { listOf("The notices could not be read.") }
            main.post { lines.clear(); lines += text; adapter.notifyDataSetChanged() }
        }
        return dialog
    }
}
