package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ownednotes.core.*

/** P130 (F109): a stamp drawn small, fitted into its row the way the page draws its ink. */
internal class StampThumbnail(context: Context, private val record: StampRecord) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paths = record.strokes.map { it to InkGeometry.visible(it) }
    private val bounds = RectF().also { box ->
        val each = RectF(); var first = true
        paths.forEach { (_, path) -> path.computeBounds(each, true); if (first) box.set(each) else box.union(each); first = false }
    }
    init { contentDescription = "Stamp ${record.name}" }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(context.dpi(72f), context.dpi(56f))
    override fun onDraw(canvas: Canvas) {
        val radius = context.dp(6f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paper)
        if (bounds.isEmpty) return
        val pad = context.dp(6f)
        val scale = minOf((width - 2 * pad) / bounds.width(), (height - 2 * pad) / bounds.height(), 2f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f); canvas.scale(scale, scale); canvas.translate(-bounds.centerX(), -bounds.centerY())
        paths.forEach { (stroke, path) -> InkGeometry.draw(canvas, stroke, paint, path = path) }
        canvas.restore()
    }
}

/** Save the selected handwriting as a stamp. The notebook is not changed, so this works in read-only mode too. */
internal fun MainActivity.saveStampDialog(): AlertDialog? {
    val app = application as NotesApplication
    val content = ink.selectedInkForStamp() ?: return null
    val name = EditText(this).apply { hint = "Stamp name"; contentDescription = "Stamp name"; setSingleLine() }
    val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(8f)); addView(name) }
    val dialog = AlertDialog.Builder(this).setTitle("Save as stamp").setView(form)
        .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val record = runCatching { StampRecord.of(name.text.toString(), content.strokes, content.centre) }.getOrElse { name.error = it.message; return@setOnClickListener }
        save.isEnabled = false
        app.io.execute {
            val result = runCatching { app.stampLibrary.add(record.name, content.strokes, content.centre) }
            app.main.post {
                if (!isDestroyed) result.onSuccess { dialog.dismiss(); message("Saved stamp") }
                    .onFailure { name.error = it.message ?: "Cannot save stamp"; save.isEnabled = true }
            }
        }
    }
    return dialog
}

/** Saved stamps: tap one to put it on the current page; Manage renames or removes it from the library. */
internal fun MainActivity.stampLibraryDialog(): AlertDialog? {
    val app = application as NotesApplication; val repo = app.activeDocument
    if (repo.readOnly || !repo.canChangeNotebook) return null
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f)) }
    fun status(text: String) { rows.removeAllViews(); rows.addView(TextView(this).apply { this.text = text; contentDescription = "Stamps status" }) }
    status("Loading stamps…")
    val dialog = AlertDialog.Builder(this).setTitle("Stamps").setView(ScrollView(this).apply { addView(rows) })
        .setNegativeButton("Close", null).create()
    lateinit var reload: () -> Unit
    fun render(stamps: List<StampRecord>) {
        rows.removeAllViews()
        if (stamps.isEmpty()) status("No saved stamps. Select handwriting and choose Save as stamp.")
        stamps.forEach { record ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dpi(4f), 0, dpi(4f)) }
            val insert = View.OnClickListener {
                if (!repo.canChangeNotebook || repo.readOnly || ink.isEditing) { status("The page is busy. Reopen Stamps."); return@OnClickListener }
                if (ink.insertStamp(record)) { dialog.dismiss(); message("Inserted stamp") } else message("Cannot insert stamp here")
            }
            row.addView(StampThumbnail(this, record).apply { setOnClickListener(insert) })
            row.addView(Button(this).apply { text = record.name; contentDescription = "Insert stamp ${record.name}"; setOnClickListener(insert) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Manage"; contentDescription = "Manage stamp ${record.name}"
                setOnClickListener { stampManageDialog(record) { reload() } }
            })
            rows.addView(row)
        }
    }
    reload = {
        app.io.execute {
            val result = runCatching { app.stampLibrary.read() }
            app.main.post {
                if (dialog.isShowing && !isDestroyed) result.onSuccess { render(it) }.onFailure { status("Cannot read the stamp library: ${it.message}") }
            }
        }
    }
    dialog.show(); reload()
    return dialog
}

internal fun MainActivity.stampManageDialog(record: StampRecord, onChanged: () -> Unit): AlertDialog =
    AlertDialog.Builder(this).setTitle(record.name).setItems(arrayOf("Rename…", "Remove from library…")) { _, which ->
        if (which == 0) renameStampDialog(record, onChanged) else removeStampDialog(record, onChanged)
    }.setNegativeButton("Cancel", null).show()

/** Library changes run on io; a stale dialog fails rather than overwriting a newer rename or removal. */
private fun MainActivity.changeStamp(record: StampRecord, operation: (StampLibrary) -> Boolean, done: (Result<Unit>) -> Unit) {
    val app = application as NotesApplication
    app.io.execute {
        val result = runCatching {
            synchronized(app.stampLibrary) {
                require(app.stampLibrary.read().firstOrNull { it.id == record.id } == record) { "Stamp changed. Reopen Stamps." }
                check(operation(app.stampLibrary)) { "Stamp no longer exists." }
            }
        }
        app.main.post { if (!isDestroyed) done(result) }
    }
}

internal fun MainActivity.renameStampDialog(record: StampRecord, onChanged: () -> Unit): AlertDialog {
    val name = EditText(this).apply { contentDescription = "Stamp name"; setSingleLine(); setText(record.name) }
    val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(8f)); addView(name) }
    val dialog = AlertDialog.Builder(this).setTitle("Rename stamp").setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val updated = runCatching { record.copy(name = name.text.toString().trim()).also { it.validate() } }.getOrElse { name.error = it.message; return@setOnClickListener }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE); save.isEnabled = false
        changeStamp(record, { it.rename(record.id, updated.name) }) { result ->
            result.onSuccess { dialog.dismiss(); onChanged() }.onFailure { name.error = it.message; save.isEnabled = true }
        }
    }
    return dialog
}

internal fun MainActivity.removeStampDialog(record: StampRecord, onChanged: () -> Unit): AlertDialog {
    val status = TextView(this).apply { text = "Remove “${record.name}” from your stamps? Stamps already placed in notebooks stay as they are."; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(8f)) }
    val dialog = AlertDialog.Builder(this).setTitle("Remove stamp?").setView(status).setPositiveButton("Remove", null).setNegativeButton("Cancel", null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE); remove.isEnabled = false
        changeStamp(record, { it.remove(record.id) }) { result ->
            result.onSuccess { dialog.dismiss(); onChanged() }.onFailure { status.text = it.message; remove.isEnabled = true }
        }
    }
    return dialog
}
