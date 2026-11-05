package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.ownednotes.core.Page
import dev.ownednotes.core.PaperPattern
import dev.ownednotes.core.PaperStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Existing native form controls; the preview never edits the notebook until Apply. */
object PaperDialog {
    /** P534: saved papers by a sample of each. */
    private fun presetChooser(context: Context, page: Page, title: String, entries: List<PaperPresets.Preset>, choose: (Int) -> Unit) =
        PictureChooser(context, title, entries.map { preset -> PictureChooser.Item(preset.name, description = "Saved paper: ${preset.name}", draw = { edge -> paperSample(page, preset.paper, edge) }) }, choose = choose).show().sheet.dialog
    fun show(context: Context, page: Page, apply: (PaperStyle) -> Boolean): AlertDialog {
        var draft = page.paper
        val app = context.applicationContext as NotesApplication
        val presets = PaperPresets(app.getSharedPreferences(app.preferencesName, Context.MODE_PRIVATE))
        var rebuild: () -> Unit = {}
        var child: android.app.Dialog? = null
        // P173: drawings that follow the draft (pattern cards, colour swatches) repaint through this.
        var follow: () -> Unit = {}
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val preview = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val scale = minOf(width / page.width, height / page.height)
                canvas.save(); canvas.translate((width - page.width * scale) / 2, 0f); canvas.scale(scale, scale)
                canvas.clipRect(0f, 0f, page.width, page.height)
                if (runCatching { draft.validate(page.width, page.height) }.isSuccess)
                    PaperRenderer.draw(canvas, page.copy(paper = draft), paint)
                else { paint.color = draft.color.toInt(); canvas.drawRect(0f, 0f, page.width, page.height, paint) }
                canvas.restore()
            }
        }.apply { contentDescription = "Paper preview"; layoutParams = LinearLayout.LayoutParams(-1, context.dpi(160f)) }
        val issue = ToolControls.caption(context, "").apply { contentDescription = "Paper validation" }
        fun update() {
            issue.text = runCatching { draft.validate(page.width, page.height) }.exceptionOrNull()?.message.orEmpty()
            preview.invalidate(); follow()
        }
        fun hexField(label: String, color: Long) = EditText(context).apply {
            contentDescription = label; hint = "$label · RRGGBB"; visibility = View.GONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7)); setSingleLine()
            setText(String.format(Locale.ROOT, "%06X", color and 0xffffff))
        }
        fun rgb(field: EditText): Long? = field.text.toString().trim().removePrefix("#").takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) }?.toLong(16)?.or(0xff000000L)
        var background = hexField("Paper color", draft.color)
        var guides = hexField("Guide color", draft.guideColor)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft = draft.copy(color = rgb(background) ?: draft.color, guideColor = rgb(guides) ?: draft.guideColor); update()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        fun checkedDraft(): PaperStyle? {
            val color = rgb(background); val guideColor = rgb(guides)
            if (color == null) { background.error = "Enter six RGB hex digits"; return null }
            if (guideColor == null) { guides.error = "Enter six RGB hex digits"; return null }
            draft = draft.copy(color = color, guideColor = guideColor)
            return runCatching { draft.validate(page.width, page.height); draft }
                .onFailure { issue.text = it.message }.getOrNull()
        }
        fun pick(label: String, field: EditText) {
            val picker = RgbColorPicker(context, rgb(field) ?: 0xffffffffL)
            child = AlertDialog.Builder(context).setTitle(label).setView(ScrollView(context).apply { addView(picker) })
                .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).show().also { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        runCatching { picker.color() }.onSuccess { field.setText("%06X".format(it and 0xffffff)); dialog.dismiss() }
                            .onFailure { picker.hexInput.error = it.message }
                    }
                }
        }
        /** Paper or line colours as little sheets; the last chip opens the full picker. The hex field stays the source. */
        fun swatches(label: String, colors: List<Pair<Long, String>>, guide: Boolean, field: () -> EditText): PaperColorRow {
            val chips = colors.map { (color, name) -> PaperColorChip(context, color, "$name ${if (guide) "lines" else "paper"}", { draft }, guide) } +
                PaperColorChip(context, null, "Choose $label visually", { draft }, guide)
            chips.forEach { chip ->
                chip.setOnClickListener {
                    val color = chip.color ?: return@setOnClickListener pick(label, field())
                    field().setText(String.format(Locale.ROOT, "%06X", color and 0xffffff))
                    chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            return PaperColorRow(context, chips).apply { contentDescription = "$label swatches" }
        }
        fun strip(row: View) = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(context.dpi(20f), context.dpi(8f), context.dpi(20f), context.dpi(8f))
        }
        rebuild = {
            column.removeAllViews()
            background = hexField("Paper color", draft.color); guides = hexField("Guide color", draft.guideColor)
            background.addTextChangedListener(watcher); guides.addTextChangedListener(watcher)
            column.apply {
            (preview.parent as? ViewGroup)?.removeView(preview)
            addView(preview)
            addView(ToolControls.heading(context, "Pattern", 8f))
            // Each card is a close-up of this paper with that pattern, in the draft's colours and spacing.
            val patterns = VisualOptions.row(context, listOf("Plain", "Ruled", "Grid", "Dots", "Isometric"),
                PaperPattern.entries.map { pattern -> Pictogram { c, box, on -> PaperArt.page(c, box, 3f, 4f, draft.copy(pattern = pattern), on, .94f) } },
                draft.pattern.ordinal, 52f, 60f) {
                draft = draft.copy(pattern = PaperPattern.entries[it]); update()
            }.apply { contentDescription = "Paper pattern" }
            addView(patterns)
            val paperRow = swatches("Paper color", PaperColors.papers, false) { background }
            val guideRow = swatches("Guide color", PaperColors.guides, true) { guides }
            follow = {
                for (i in 0 until patterns.childCount) patterns.getChildAt(i).invalidate()
                paperRow.mark(draft.color); guideRow.mark(draft.guideColor); paperRow.redraw(); guideRow.redraw()
            }
            addView(ToolControls.heading(context, "Paper colour", 8f)); addView(strip(paperRow))
            addView(ToolControls.heading(context, "Line colour", 8f)); addView(strip(guideRow))
            addView(android.widget.CheckBox(context).apply {
                text = "Precise paper color values"
                setOnCheckedChangeListener { _, on -> background.visibility = if (on) View.VISIBLE else View.GONE; guides.visibility = if (on) View.VISIBLE else View.GONE }
            })
            addView(background); addView(guides)
            addView(ToolControls.slider(context, "Spacing · page units", 16f, 256f, draft.spacing, { it.roundToInt().toFloat() }, { it.roundToInt().toString() }) {
                draft = draft.copy(spacing = it); update()
            })
            addView(ToolControls.slider(context, "Guide weight · page units", .25f, 3f, draft.weight, { (it * 4).roundToInt() / 4f }, { String.format(Locale.ROOT, "%.2f", it) }) {
                draft = draft.copy(weight = it); update()
            })
            addView(ToolControls.caption(context, "Applies to this page. New blank pages inherit its paper; existing pages stay as they are."))
            addView(issue)
            addView(ToolControls.heading(context, "Saved papers", 12f))
            // P201 step 3: kit buttons, so the preset actions are sentence case and follow day and night.
            val load = Kit.button(context, "Saved paper…", Kit.Button.TINTED, small = true) {
                val entries = presets.list()
                child = presetChooser(context, page, "Saved paper", entries) { index -> draft = entries[index].paper; rebuild() }
            }.apply { contentDescription = "Choose paper preset" }
            lateinit var delete: View
            delete = Kit.button(context, "Delete preset…", Kit.Button.DANGER, small = true) {
                val entries = presets.list()
                child = presetChooser(context, page, "Delete saved paper preset", entries) { index ->
                    presets.remove(entries[index].name)
                    val any = presets.list().isNotEmpty(); delete.isEnabled = any; load.isEnabled = any
                    issue.text = "Preset deleted. Page paper is unchanged."
                }
            }.apply { contentDescription = "Delete paper preset" }
            val any = presets.list().isNotEmpty(); load.isEnabled = any; delete.isEnabled = any
            val save = Kit.button(context, "Save…", Kit.Button.TINTED, small = true) {
                run {
                    val paper = checkedDraft() ?: return@run
                    val name = EditText(context).apply {
                        hint = "Preset name"; contentDescription = "Paper preset name"; setSingleLine()
                        filters = arrayOf(InputFilter.LengthFilter(60))
                    }
                    child = AlertDialog.Builder(context).setTitle("Save paper preset").setView(name)
                        .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create().apply {
                            show()
                            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                runCatching { presets.save(name.text.toString(), paper) }
                                    .onSuccess { dismiss(); load.isEnabled = true; delete.isEnabled = true; issue.text = "Preset saved. Apply to change this page." }
                                    .onFailure { name.error = it.message }
                            }
                        }
                }
            }.apply { contentDescription = "Save paper preset" }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(load, save, delete).forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = context.dpi(6f) }) }
            })
            addView(ToolControls.caption(context, "Presets save style only on this device. Saving or deleting a preset does not change this page. Choose a preset, then Apply to use it."))
            }
            update()
        }
        rebuild()
        return AlertDialog.Builder(context).setTitle("Paper settings").setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create().apply {
                show(); Glass.sheet(this)
                setOnDismissListener { child?.dismiss() }
                // Attach synchronously, as with the RGB picker, so immediate Apply works too.
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val paper = checkedDraft() ?: return@setOnClickListener
                    runCatching {
                        if (apply(paper)) dismiss() else issue.text = "This page changed. Reopen Paper settings."
                    }.onFailure { issue.text = it.message ?: "Cannot apply paper settings" }
                }
            }
    }
}
