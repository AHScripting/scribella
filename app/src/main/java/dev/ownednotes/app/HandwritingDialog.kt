package dev.ownednotes.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.*
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLayout

/** Technical review controls: no automatic replacement, indexing, or export of unverified candidates. */
internal fun MainActivity.handwritingDialog(
    ids: Set<String> = emptySet(), engine: HandwritingEngine = DigitalHandwriting,
    uiContext: Context = this,
): Dialog? {
    val app = application as NotesApplication
    val repo = app.activeDocument
    if (!repo.canChangeNotebook) return null
    val notebook = repo.session?.notebook ?: return null
    val pageId = repo.selectedPageId ?: return null
    val input = runCatching { HandwritingInput.capture(notebook, pageId, ids) }.getOrElse {
        message(it.message ?: "Cannot read handwriting."); return null
    }
    val languages = HandwritingLanguage.entries
    var readingLayout = HandwritingLayout()
    val layoutButton = Kit.button(uiContext, "Writing layout…", Kit.Button.TINTED) {}.apply {
        contentDescription = "Writing layout"
    }
    var layoutSheet: Kit.Sheet? = null
    var orderSheet: Kit.Sheet? = null
    val orderButton=Kit.button(uiContext,"Reading order preview…",Kit.Button.TINTED) {}.apply {
        contentDescription="Reading order preview"
    }
    var languageIndex = 0
    var splitLines = true
    var refreshModel: () -> Unit = {}
    val language = Kit.segmented(uiContext, languages.map { it.label }, languageIndex) { next ->
        if (next != languageIndex) { languageIndex = next; refreshModel() }
    }.apply {
        contentDescription = "Handwriting language"
        languages.forEachIndexed { index, item -> getChildAt(index).contentDescription = "Handwriting language ${item.label}" }
    }
    val separateLines = Kit.Switch(uiContext, splitLines, "Separate handwriting lines") { next ->
        splitLines = next; refreshModel()
    }
    val status = Kit.text(uiContext, "", secondary = true).apply {
        contentDescription = "Handwriting status"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val download = Kit.button(uiContext, "Download language model", Kit.Button.TINTED) {}.apply {
        contentDescription = "Download handwriting model"; isEnabled = false
    }
    val recognize = Kit.button(uiContext, "Recognise handwriting") {}.apply {
        contentDescription = "Recognise handwriting"; isEnabled = false
    }
    val candidates = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
    val review = Kit.field(uiContext, "Review and correct the recognised text").apply {
        contentDescription = "Handwriting review"; setSingleLine(false)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START; minLines = 2; minHeight = dpi(160f); visibility = View.GONE
    }
    val copy = Kit.button(uiContext, "Copy reviewed text", Kit.Button.TINTED) {}.apply {
        contentDescription = "Copy reviewed handwriting"; isEnabled = false
    }
    val save = Kit.button(uiContext, "Save for search/export", Kit.Button.TINTED) {}.apply {
        contentDescription = "Save reviewed handwriting"; isEnabled = false
    }
    val convert = Kit.button(uiContext, "Convert to text box", Kit.Button.TINTED) {}.apply {
        contentDescription = "Convert handwriting to text box"; visibility = View.GONE
    }
    val sheet = Kit.Sheet(uiContext, "Read handwriting", cancel = "Close")
    val dialog = sheet.dialog
    sheet.add(Kit.text(uiContext, (if (ids.isEmpty()) "Reads visible handwriting on this page." else "Reads the selected handwriting.") +
        " Separate lines reads each column from top to bottom. Writing layout can turn sideways ink upright or read two columns. Review every result. Original ink stays unless you convert it to a text box. Language models download once (about 20 MB each); recognition runs on this device.", secondary = true))
    // The ink and the text read from it side by side; the ink has the row until there is a result.
    sheet.add(LinearLayout(uiContext).apply {
        addView(HandwritingSourcePreview(uiContext, notebook.pages.first { it.id == pageId }, input.strokeIds.toSet()).apply { minimumHeight = dpi(160f) },
            LinearLayout.LayoutParams(0, -1, 1f))
        addView(review, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12f) })
    })
    sheet.add(Kit.section(uiContext, "Language")); sheet.add(language, 8f)
    sheet.add(layoutButton)
    sheet.add(Kit.row(uiContext, "Separate horizontal lines", trailing = separateLines) { separateLines.performClick(); Unit })
    sheet.add(orderButton); sheet.add(status); sheet.add(download); sheet.add(recognize)
    sheet.add(candidates); sheet.add(copy); sheet.add(save); sheet.add(convert)
    // Async review results can make the form taller after the kit's initial measurement.
    sheet.body.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
        dialog.window?.decorView?.post {
            if (dialog.isShowing) dialog.window?.let { window ->
                val cap = (resources.displayMetrics.heightPixels * .9f).toInt()
                if (window.decorView.height > cap) window.setLayout(window.attributes.width, cap)
            }
        }
    }
    var generation = 0
    var acceptedGeneration = -1
    fun active(token: Int) = dialog.isShowing && !isDestroyed && token == generation
    fun current() = repo.canChangeNotebook && repo.session?.notebook?.let { input.matches(it, repo.selectedPageId ?: "") } == true
    fun clearReview() {
        acceptedGeneration = -1; review.setText(""); review.visibility = View.GONE; candidates.removeAllViews(); convert.visibility = View.GONE
        copy.isEnabled = false
        save.isEnabled = false
    }
    fun checkModel() {
        val token = ++generation; clearReview(); download.isEnabled = false; recognize.isEnabled = false
        status.text = "Checking language model…"
        runCatching { engine.downloaded(languages[languageIndex]) }.onSuccess { task ->
            task.addOnSuccessListener { ready ->
                if (active(token)) {
                    status.text = if (ready) "Language model ready. Review the result before copying." else "Download this language model to recognise handwriting."
                    download.isEnabled = !ready; recognize.isEnabled = ready
                }
            }.addOnFailureListener { error ->
                if (active(token)) { status.text = "Cannot check model: ${error.message}"; download.isEnabled = true }
            }
        }.onFailure { if (active(token)) { status.text = "Cannot check model: ${it.message}"; download.isEnabled = true } }
    }
    refreshModel = { checkModel() }
    sheet.show()
    checkModel()
    orderButton.setOnClickListener {
        if (!current()) { clearReview(); status.text="The page changed or is busy. Reopen Read handwriting."; return@setOnClickListener }
        orderSheet?.dismiss()
        val capturedLayout=readingLayout;val capturedSeparate=splitLines
        orderSheet=handwritingReadingOrderPreview(uiContext,input,capturedLayout,capturedSeparate,
            dispatch={ app.io.execute(it) },current={ dialog.isShowing && !isDestroyed && current() &&
                readingLayout==capturedLayout && splitLines==capturedSeparate })
    }
    layoutButton.setOnClickListener {
        if (!current()) { clearReview(); status.text = "The page changed or is busy. Reopen Read handwriting."; return@setOnClickListener }
        layoutSheet = handwritingLayoutPicker(uiContext, input, readingLayout) { next ->
            if (!dialog.isShowing || !current()) false else {
                if (next != readingLayout) { readingLayout = next; checkModel() }
                true
            }
        }
    }
    download.setOnClickListener {
        val token = ++generation; clearReview(); download.isEnabled = false; recognize.isEnabled = false
        status.text = "Downloading language model… You can close this dialog while it finishes."
        runCatching { engine.download(languages[languageIndex]) }.onSuccess { task ->
            task.addOnSuccessListener { if (active(token)) checkModel() }
                .addOnFailureListener { if (active(token)) { status.text = "Download failed: ${it.message}"; download.isEnabled = true } }
        }.onFailure { if (active(token)) { status.text = "Download failed: ${it.message}"; download.isEnabled = true } }
    }
    recognize.setOnClickListener {
        if (!current()) { clearReview(); status.text = "The page changed or is busy. Reopen Read handwriting."; return@setOnClickListener }
        val token = ++generation; clearReview(); recognize.isEnabled = false
        status.text = "Reading handwriting…"
        runCatching { engine.recognize(input, languages[languageIndex], splitLines, readingLayout) }.onSuccess { task ->
            task.addOnSuccessListener { values ->
                if (!active(token)) return@addOnSuccessListener
                recognize.isEnabled = true
                if (!current()) { status.text = "The page changed. Reopen Read handwriting."; return@addOnSuccessListener }
                val choices = values.filter { it.isNotBlank() }.distinct().take(10)
                if (choices.isEmpty()) { status.text = "No text recognised. Try selecting one clear line."; return@addOnSuccessListener }
                acceptedGeneration = token; review.setText(choices.first()); review.visibility = View.VISIBLE
                choices.forEach { candidate -> candidates.addView(Kit.button(uiContext, candidate, Kit.Button.TINTED) {
                    if (active(token) && current()) review.setText(candidate)
                }.apply {
                    contentDescription = "Handwriting candidate $candidate"; maxLines = 4
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) }) }
                status.text = "Review and correct the text. Nothing has been saved to your notebook."
                copy.isEnabled = true
                save.isEnabled = !repo.readOnly
                convert.visibility = if (repo.readOnly) View.GONE else View.VISIBLE
            }.addOnFailureListener { if (active(token)) { status.text = "Recognition failed: ${it.message}"; recognize.isEnabled = true } }
        }.onFailure { if (active(token)) { status.text = "Recognition failed: ${it.message}"; recognize.isEnabled = true } }
    }
    copy.setOnClickListener {
        if (acceptedGeneration != generation || !current()) {
            clearReview(); status.text = "The page changed or is busy. Reopen Read handwriting."; return@setOnClickListener
        }
        val text = review.text.toString()
        if (text.isBlank()) { review.error = "Enter text to copy."; return@setOnClickListener }
        (getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("Reviewed handwriting", text))
        message("Reviewed text copied")
    }
    save.setOnClickListener {
        if (acceptedGeneration != generation || !current() || repo.readOnly) {
            clearReview(); status.text = "The page changed, is busy or read-only. Reopen Read handwriting."; return@setOnClickListener
        }
        runCatching {
            repo.edit { it.saveReviewedHandwriting(input, review.text.toString(), languages[languageIndex].tag) }
        }.onSuccess { dialog.dismiss(); message("Reviewed text saved; original ink kept") }
            .onFailure { review.error = it.message ?: "Cannot save reviewed text" }
    }
    convert.setOnClickListener {
        if (acceptedGeneration != generation || !current() || repo.readOnly) {
            clearReview(); status.text = "The page changed, is busy or read-only. Reopen Read handwriting."; return@setOnClickListener
        }
        if (review.text.isBlank()) { review.error = "Enter text to convert."; return@setOnClickListener }
        runCatching { repo.edit { it.convertHandwriting(input, review.text.toString()); true } }
            .onSuccess { if (!ink.isEditing) ink.clearSelection(); dialog.dismiss(); message("Converted to a text box. Undo brings the handwriting back.") }
            .onFailure { review.error = it.message ?: "Cannot convert handwriting" }
    }
    dialog.setOnDismissListener { ++generation; layoutSheet?.dismiss(); orderSheet?.dismiss() }
    return dialog
}

/** Explicit removal of saved transcriptions leaves every native stroke intact. */
internal fun MainActivity.clearHandwritingDialog(): AlertDialog? {
    val repo = (application as NotesApplication).activeDocument
    val note = repo.session?.notebook ?: return null
    val page = repo.currentPage ?: return null
    if (!repo.canChangeNotebook || repo.readOnly || page.reviewedInk.isEmpty()) return null
    return AlertDialog.Builder(this).setTitle("Remove saved handwriting text?")
        .setMessage("Removes reviewed text from this page's search and exports. Original handwriting stays.")
        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
            if (repo.canChangeNotebook && !repo.readOnly && repo.session?.notebook === note && repo.selectedPageId == page.id)
                repo.edit { it.clearReviewedHandwriting(page.id) }
            else message("The page changed or is busy. Try again.")
        }.show()
}
