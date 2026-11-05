package dev.ownednotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * P566: Check for updates, in public builds only. It runs only when the user taps the row, asks GitHub for the project's
 * release list and sends nothing about the user's notebooks. It never downloads or installs anything: a new version opens
 * in the browser. The list is used because GitHub's "latest release" address skips pre-releases, and these are alphas.
 */
internal object UpdateCheck {
    private const val REPOSITORY = "AHScripting/scribella"
    const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=5"
    private const val PAGES = "https://github.com/$REPOSITORY/"
    private const val MOST_BYTES = 512 * 1024
    private const val MOST_NOTES = 1500

    data class Release(val version: String, val notes: String, val page: String)

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val release: Release) : Result
        data object NoReleases : Result
        data object Failed : Result
    }

    private data class Version(val numbers: List<Long>, val label: String?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            numbers.zip(other.numbers).forEach { (mine, theirs) -> if (mine != theirs) return mine.compareTo(theirs) }
            // 0.1.0 is newer than 0.1.0-alpha; otherwise alpha, beta and rc already sort by name.
            return when {
                label == other.label -> 0
                label == null -> 1
                other.label == null -> -1
                else -> label.compareTo(other.label)
            }
        }
    }

    private val PATTERN = Regex("""^v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?$""")

    private fun parse(text: String): Version? = PATTERN.matchEntire(text.trim())?.let { match ->
        Version(match.groupValues.subList(1, 4).map { it.toLong() }, match.groupValues[4].ifEmpty { null })
    }

    /** False when either version can't be read, so an odd tag never offers an update. */
    fun isNewer(candidate: String, current: String): Boolean {
        val theirs = parse(candidate) ?: return false
        val mine = parse(current) ?: return false
        return theirs > mine
    }

    /** "0.1.0-alpha" as people read it. */
    fun display(version: String) = version.removePrefix("v").replace('-', ' ')

    /** The newest usable entry of GitHub's list, compared with [current]. Throws if the list isn't valid JSON. */
    fun newest(json: String, current: String): Result {
        val list = JSONArray(json)
        val releases = (0 until list.length()).mapNotNull { index ->
            val entry = list.getJSONObject(index)
            val tag = entry.optString("tag_name")
            val page = entry.optString("html_url")
            val version = parse(tag)
            if (entry.optBoolean("draft") || version == null || !page.startsWith(PAGES)) null
            else version to Release(tag.removePrefix("v"), entry.optString("body").trim().take(MOST_NOTES), page)
        }
        val best = releases.maxByOrNull { it.first }?.second ?: return Result.NoReleases
        return if (isNewer(best.version, current)) Result.Available(best) else Result.UpToDate
    }

    /** What an answer from GitHub means. 404 is what it says until the first release exists. */
    fun interpret(code: Int, body: String?, current: String): Result = when (code) {
        HttpURLConnection.HTTP_OK -> body?.let { runCatching { newest(it, current) }.getOrNull() } ?: Result.Failed
        HttpURLConnection.HTTP_NOT_FOUND -> Result.NoReleases
        else -> Result.Failed
    }

    fun fetch(current: String): Result = runCatching {
        val connection = URL(RELEASES).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Scribella-update-check")
            val code = connection.responseCode
            interpret(code, if (code == HttpURLConnection.HTTP_OK) connection.inputStream.use(::readLimited) else null, current)
        } finally { connection.disconnect() }
    }.getOrDefault(Result.Failed)

    private fun readLimited(input: java.io.InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toString(Charsets.UTF_8.name())
            output.write(buffer, 0, read)
            if (output.size() > MOST_BYTES) return null
        }
    }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Glass parts only. Runs on its own thread, so a slow network can't hold up notebook work on the app's IO thread. */
    fun show(context: Context, current: String = currentVersion(context), check: () -> Result = { fetch(current) }): Kit.Sheet {
        val app = context.applicationContext as NotesApplication
        val status = Kit.text(context, "Checking GitHub…", secondary = true)
        val sheet = Kit.Sheet(context, "Check for updates", cancel = "Close")
        sheet.add(Kit.text(context, "You have version ${display(current)}."))
        sheet.add(status)
        var open = true
        sheet.dialog.setOnDismissListener { open = false }
        sheet.show()
        thread(name = "update-check", isDaemon = true) {
            val result = runCatching(check).getOrDefault(Result.Failed)
            app.main.post { if (open) showResult(context, sheet, status, result) }
        }
        return sheet
    }

    private fun showResult(context: Context, sheet: Kit.Sheet, status: android.widget.TextView, result: Result) {
        status.text = when (result) {
            Result.UpToDate -> "This is the newest version."
            Result.NoReleases -> "No versions have been published yet."
            Result.Failed -> "Couldn’t reach GitHub. Check your connection and try again."
            is Result.Available -> "Version ${display(result.release.version)} is ready. Installing it over this one keeps your notebooks."
        }
        if (result !is Result.Available) return
        if (result.release.notes.isNotEmpty()) sheet.add(Kit.text(context, result.release.notes, secondary = true))
        sheet.add(Kit.button(context, "Open the download page", Kit.Button.TINTED) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.release.page))) }
                .onFailure { status.text = "No browser could open it. The address is ${result.release.page}" }
        })
    }
}
