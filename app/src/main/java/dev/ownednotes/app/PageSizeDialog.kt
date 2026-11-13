package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import dev.ownednotes.core.Page
import kotlin.math.abs

/** Per-page dimensions use existing document units (PDF points); the notebook is untouched until Apply. */
object PageSizeDialog {
    fun show(context: Context, page: Page, apply: (Float, Float) -> Boolean): AlertDialog {
        fun dimension(label: String, value: Float) = EditText(context).apply {
            contentDescription = label; hint = label; setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(12)); setText(value.toString())
        }
        val widthField = dimension("Page width", page.width)
        val heightField = dimension("Page height", page.height)
        val issue = ToolControls.caption(context, "").apply { contentDescription = "Page size validation" }
        val summary = ToolControls.caption(context, "")
        fun size(): Pair<Float, Float>? {
            val w = widthField.text.toString().toFloatOrNull() ?: return null
            val h = heightField.text.toString().toFloatOrNull() ?: return null
            return (w to h).takeIf { w.isFinite() && h.isFinite() && w in 1f..100_000f && h in 1f..100_000f }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val preview = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val (w, h) = size() ?: return
                if (runCatching { page.paper.validate(w, h) }.isFailure) return
                // P594: drawn inside the grey well, so a white page shows on a white sheet.
                val bw = (width - paddingLeft - paddingRight).toFloat(); val bh = (height - paddingTop - paddingBottom).toFloat()
                if (bw <= 0f || bh <= 0f) return
                val scale = minOf(bw / w, bh / h)
                canvas.save(); canvas.translate(paddingLeft + (bw - w * scale) / 2, paddingTop + (bh - h * scale) / 2)
                canvas.scale(scale, scale); canvas.clipRect(0f, 0f, w, h)
                PaperRenderer.draw(canvas, page.copy(width = w, height = h), paint, least = 1f / scale); canvas.restore()
            }
        }.apply {
            contentDescription = "Page size preview"; layoutParams = LinearLayout.LayoutParams(-1, context.dpi(156f))
            background = rounded(Kit.well(context.night), context.dp(24f)); context.dpi(12f).let { setPadding(it, it, it, it) }
        }
        val presets = listOf("Custom", "A4", "A5", "US Letter", "US Legal", "Square")
        val dimensions = listOf(0f to 0f, 595.28f to 841.89f, 419.53f to 595.28f, 612f to 792f, 612f to 1008f, 1000f to 1000f)
        fun landscape() = size()?.let { it.first > it.second } ?: (page.width > page.height)
        fun oriented(d: Pair<Float, Float>) = if (landscape()) d.second to d.first else d
        // P173: presets are page silhouettes in their real proportions, turned to the current orientation.
        val preset = VisualChoiceStrip(context, "Page size presets", presets,
            listOf(PaperArt.size({ size() ?: (page.width to page.height) }, { null }, PaperArt.SizeMark.CUSTOM)) +
                dimensions.drop(1).map { d -> PaperArt.size({ oriented(d) }, { page.paper }) },
            cardWidth = 80f, descriptions = listOf("Custom size") + presets.drop(1).map { "$it page size" })
        val exact = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun update() {
            val d = size()
            issue.text = if (d == null) "Enter width and height from 1 to 100000" else
                runCatching { page.paper.validate(d.first, d.second) }.exceptionOrNull()?.message.orEmpty()
            summary.text = d?.let { (w, h) -> "${if (w > h) "Landscape" else if (h > w) "Portrait" else "Square"} · $w × $h" }.orEmpty()
            // The card that matches the typed size, in either orientation; otherwise Custom.
            val match = d?.let { (w, h) -> dimensions.indexOfFirst { (pw, ph) -> pw > 0 &&
                ((abs(pw - w) < .01f && abs(ph - h) < .01f) || (abs(pw - h) < .01f && abs(ph - w) < .01f)) } } ?: -1
            preset.showSelected(maxOf(match, 0)); preset.redraw()
            if (match <= 0) exact.visibility = View.VISIBLE
            preview.invalidate()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update()
            override fun afterTextChanged(s: Editable?) {}
        }
        widthField.addTextChangedListener(watcher); heightField.addTextChangedListener(watcher)
        preset.onSelect = { position ->
            if (position == 0) exact.visibility = View.VISIBLE
            else {
                val (w, h) = oriented(dimensions[position])
                widthField.setText(w.toString()); heightField.setText(h.toString())
            }
        }
        val swap = OptionTile(context, "Turn", Pictogram { c, box, on ->
            val u = box.height() / 64f
            PaperArt.page(c, box, 3f, 4f, page.paper, on, .7f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * u; strokeCap = Paint.Cap.ROUND; color = Tokens.INK }
            val arc = android.graphics.RectF(box.centerX() - 22f * u, box.centerY() - 26f * u, box.centerX() + 22f * u, box.centerY() + 26f * u)
            c.drawArc(arc, -60f, 80f, false, p)
            val ex = arc.centerX() + 22f * u * kotlin.math.cos(Math.toRadians(20.0)).toFloat()
            val ey = arc.centerY() + 26f * u * kotlin.math.sin(Math.toRadians(20.0)).toFloat()
            c.drawLine(ex, ey, ex - 5f * u, ey - 2f * u, p); c.drawLine(ex, ey, ex + 2f * u, ey - 5f * u, p)
        }, 56f, 64f).apply {
            contentDescription = "Swap page orientation"
            setOnClickListener {
                val w = widthField.text.toString(); val h = heightField.text.toString()
                widthField.setText(h); heightField.setText(w)
            }
        }
        exact.apply {
            visibility = View.GONE
            addView(ToolControls.heading(context, "Width · points", 8f)); addView(widthField)
            addView(ToolControls.heading(context, "Height · points", 8f)); addView(heightField)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(context.dpi(20f), context.dpi(8f), context.dpi(20f), context.dpi(8f))
            addView(preview); addView(summary)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(preset, LinearLayout.LayoutParams(0, -2, 1f))
                addView(swap, LinearLayout.LayoutParams(context.dpi(72f), swap.natural).apply { marginStart = context.dpi(8f); topMargin = context.dpi(4f) })
            })
            addView(exact)
            addView(ToolControls.caption(context, "Page units are PDF points (72 per inch). Applies to this page only. Choose Custom for exact width and height."))
            addView(ToolControls.caption(context, "Ink stays at its original coordinates. Shrinking clips the view and export, but retains all stored ink. Undo restores the size. New blank pages inherit this size. PDF export supports edges up to 14400 points."))
            addView(issue)
        }
        update()
        return AlertDialog.Builder(context).setTitle("Page size").setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create().apply {
                show(); Glass.sheet(this)
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val d = size()
                    if (d == null) { update(); return@setOnClickListener }
                    runCatching {
                        page.paper.validate(d.first, d.second)
                        if (apply(d.first, d.second)) dismiss() else issue.text = "This page changed. Reopen Page size."
                    }.onFailure { issue.text = it.message ?: "Cannot change page size" }
                }
            }
    }
}
