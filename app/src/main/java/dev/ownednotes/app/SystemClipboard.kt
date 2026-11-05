package dev.ownednotes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.TextBox
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * P086 (F047): copied handwriting also goes on the Android clipboard as a PNG picture, so it pastes into other apps.
 * Paste inside Scribella keeps using the in-app ink clipboard, so it still gives editable strokes.
 */
object SystemClipboard {
    const val LABEL = "Handwriting from Scribella"
    const val MIME = "image/png"
    /** Page units around the ink, pixels per page unit, and the largest picture side. */
    private const val MARGIN = 12f
    private const val SCALE = 2f
    const val MAX_SIDE = 4096
    private val NAME = Regex("handwriting-\\d+\\.png")

    fun authority(context: Context) = "${context.packageName}.clipboard"
    fun folder(context: Context) = File(context.cacheDir, "clipboard")

    /** Renders [strokes] off the main thread and offers the picture on the Android clipboard. Failures only lose the picture. */
    fun publish(app: NotesApplication, strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        app.io.execute {
            val uri = runCatching {
                val bitmap = render(strokes)
                val folder = folder(app).apply { mkdirs() }
                val file = File(folder, "handwriting-${System.currentTimeMillis()}.png")
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode picture" } } } finally { bitmap.recycle() }
                // Only the newest picture is kept; an older clip is replaced on the clipboard anyway.
                folder.listFiles().orEmpty().filter { it != file }.forEach { it.delete() }
                Uri.Builder().scheme("content").authority(authority(app)).appendPath(file.name).build()
            }.getOrNull() ?: return@execute
            app.main.post {
                runCatching { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData(LABEL, arrayOf(MIME), ClipData.Item(uri))) }
            }
        }
    }

    /** P103 (F099): a copied text box's text, for pasting into other apps. */
    const val TEXT_LABEL = "Text from Scribella"
    fun publishText(context: Context, text: String) {
        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(TEXT_LABEL, text)) }
    }

    /** True when another app put text on the Android clipboard after [since] (System.currentTimeMillis). Reads only the description. */
    fun hasOutsideText(context: Context, since: Long): Boolean = runCatching {
        val description = context.getSystemService(ClipboardManager::class.java).primaryClipDescription ?: return false
        description.hasMimeType("text/*") && description.label?.toString() !in setOf(LABEL, TEXT_LABEL) && description.timestamp > since
    }.getOrDefault(false)

    /** The clipboard's text, made fit for a text box, or null when there is none. */
    fun outsideText(context: Context): String? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        TextBox.clean(clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** B35 (P198): true when another app put a picture on the Android clipboard after [since]. Reads only the description. */
    fun hasOutsideImage(context: Context, since: Long): Boolean = runCatching {
        val description = context.getSystemService(ClipboardManager::class.java).primaryClipDescription ?: return false
        description.hasMimeType("image/*") && description.label?.toString() !in setOf(LABEL, TEXT_LABEL) && description.timestamp > since
    }.getOrDefault(false)

    /** The picture another app copied, or null. Android grants this app read access to it while it is on the clipboard. */
    fun outsideImage(context: Context): Uri? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        clip.getItemAt(0).uri?.takeIf { it.scheme == "content" }
    }.getOrNull()

    /** The visible ink on white, [SCALE] pixels per page unit (smaller when the longest side would pass [MAX_SIDE]). */
    fun render(strokes: List<InkStroke>): Bitmap {
        val paths = strokes.map { it to InkGeometry.visible(it) }
        val bounds = RectF()
        paths.forEach { (_, path) -> RectF().also { path.computeBounds(it, true); bounds.union(it) } }
        bounds.inset(-MARGIN, -MARGIN)
        val scale = min(SCALE, MAX_SIDE / max(bounds.width(), bounds.height()))
        val bitmap = Bitmap.createBitmap(ceil(bounds.width() * scale).toInt().coerceIn(1, MAX_SIDE), ceil(bounds.height() * scale).toInt().coerceIn(1, MAX_SIDE), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            scale(scale, scale); translate(-bounds.left, -bounds.top)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paths.forEach { (stroke, path) -> InkGeometry.draw(this, stroke, paint, path = path) }
        }
        return bitmap
    }

    fun file(context: Context, uri: Uri): File? {
        val name = uri.lastPathSegment?.takeIf { uri.pathSegments.size == 1 && NAME.matches(it) } ?: return null
        return File(folder(context), name).takeIf { it.isFile }
    }
}

/** Read-only access to the newest copied-handwriting picture, for apps the clipboard grants it to. Not exported. */
class ClipboardImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = SystemClipboard.MIME
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
        if (android.content.ClipDescription.compareMimeTypes(SystemClipboard.MIME, mimeTypeFilter)) arrayOf(SystemClipboard.MIME) else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val file = SystemClipboard.file(requireNotNull(context), uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = SystemClipboard.file(requireNotNull(context), uri) ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray(), 1).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else "handwriting.png" }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
