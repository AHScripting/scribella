package dev.ownednotes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Page
import dev.ownednotes.core.hasBackground

/**
 * P201 step 9: the Glass "Study mode" board's side panel, floating at the right of the page. Text shows the handwriting
 * the user has read and reviewed on this page (Copy text, Fix a word); Tape counts the study tape and starts the Recall
 * check; Marks leads to the printed-text tools. Below them are the Study commands as rows and the privacy note. Every
 * action runs the existing command or canvas call with its own guards; nothing here reads or sends anything new.
 */
internal class StudyPanel(context: Context, private val frame: EditorFrame, private val activity: MainActivity,
    private val ink: InkCanvas, private val repository: NotebookRepository, private val close: () -> Unit) : FrameLayout(context) {
    enum class Tab(val label: String) { TEXT("Text"), TAPE("Tape"), MARKS("Marks") }

    private val c = context
    private val night = c.night
    var tab = Tab.TEXT; private set
    // P654: one real line; with maxLines 1 alone a long title stood two lines tall in a one-line space.
    private val subtitle = Kit.text(c, "", Kit.Type.CAPTION, secondary = true, name = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val tools = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    /** What the panel last showed; it is rebuilt only when this changes, so a refresh per stroke stays cheap. */
    private var shown: List<Any?>? = null
    val segments = Kit.segmented(c, Tab.entries.map { it.label }, tab.ordinal) { i -> tab = Tab.entries[i]; refresh() }.apply {
        contentDescription = "Study view"
        getChildAt(0).contentDescription = "Text: your handwriting, read"; getChildAt(1).contentDescription = "Tape: study tape and recall"
        getChildAt(2).contentDescription = "Marks: printed text on PDF and picture pages"
    }

    init {
        background = Glass.pane(c, night, 30f); elevation = dp(16f); clipToOutline = true
        contentDescription = "Study panel"; isClickable = true // touches on the panel never reach the page
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; dpi(18f).let { setPadding(it, it, it, it) }
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Kit.text(c, "Study", Kit.Type.TITLE).apply { textSize = 21f; isAccessibilityHeading = true })
                    addView(subtitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(3f) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(View(c).apply {
                    val r = dp(20f)
                    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433),
                        android.graphics.drawable.LayerDrawable(arrayOf(rounded(if (night) 0x1fffffff else 0x0f1d2433, r),
                            Kit.Icon("M6 6l12 12M18 6L6 18", Glass.text(night), dpi(20f)))).apply {
                            setLayerGravity(1, Gravity.CENTER); setLayerSize(1, dpi(20f), dpi(20f))
                        }, rounded(-1, r))
                    contentDescription = "Close study panel"; tooltipText = "Close"; isClickable = true; isFocusable = true
                    setOnClickListener { close() }
                }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
            })
            addView(segments, gap(14f))
            addView(body, gap(14f))
            addView(Kit.section(c, "More study tools").apply { setPadding(dpi(4f), 0, dpi(4f), dpi(8f)) }, gap(14f))
            addView(tools, gap(0f))
            addView(Kit.privacy(c, "Study tools run on this device."), gap(12f))
        }
        addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(column) }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refresh()
    }

    private fun gap(top: Float) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(top) }
    private fun caption(text: String) = Kit.section(c, text).apply { setPadding(dpi(4f), 0, dpi(4f), dpi(8f)) }
    /** The board's white cards on the glass panel (graphite by night). */
    private fun card() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(if (night) Kit.group(true) else Color.WHITE, dp(18f)); elevation = dp(1f)
    }
    /** P615 (board 9): one white card of study rows, hairlines between them inset to the row's text. */
    private fun rows(vararg views: View) = Kit.Group(c, if (night) Kit.group(true) else Color.WHITE, 18f).apply {
        elevation = dp(1f); views.forEach { it.setTag(R.id.kit_inset, dpi(54f)); addView(it) }
    }

    internal fun showCard(note:dev.ownednotes.core.Notebook,card:StudyCards.Card,answer:Boolean) {
        body.removeAllViews();body.addView(caption(if(answer)"Answer" else "Question"));body.addView(CardPreview(c,note,if(answer)card.answer else card.question));shown=null
    }

    private val page get() = repository.currentPage
    private val tapes: List<InkStroke> get() = page?.strokes.orEmpty().filter { it.isTape }

    /** Shows the open page; called by the editor's refresh. */
    fun refresh() {
        if (frame.flashcardBar != null) return
        val note = repository.session?.notebook
        val page = page
        val key = listOf(note, page?.id, tab, (activity.application as NotesApplication).studyCards.revision, ink.revealedTapeIds.toSet(), (activity.application as NotesApplication).audio.revision, (activity.application as NotesApplication).audioPlayback.revision, EditorCommand.entries.map { frame.commandEnabled(it) }, frame.commandLabel(EditorCommand.TAPE))
        if (key == shown) return
        shown = key
        val index = note?.pages?.indexOfFirst { it.id == page?.id } ?: -1
        subtitle.text = if (note == null || index < 0) "" else "Page ${index + 1} · ${note.title}"
        body.removeAllViews()
        when (tab) {
            // P615 (board 9): vocabulary, flashcards, tape and audio are one card of rows, each with its own status and buttons.
            Tab.TEXT -> { handwriting(page); body.addView(rows(VocabularyControls(c, activity.application as NotesApplication), StudyCardControls(activity, ink, frame),
                tapeRow(page, open = true), AudioRecordingControls(activity, frame)), gap(14f)) }
            Tab.TAPE -> { tapeTab(page); body.addView(rows(AudioRecordingControls(activity, frame)), gap(14f)) }
            Tab.MARKS -> { marks(page); body.addView(rows(AudioRecordingControls(activity, frame)), gap(14f)) }
        }
        tools.removeAllViews()
        tools.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            TOOLS.filter { EditorCommand.offered(it.command, context) }.forEach { addView(tile(it), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        })
    }

    /** P615 (board 9): a More study tools icon with its short name; TalkBack reads the full command name. */
    private fun tile(tool: Tool) = LinearLayout(c).apply {
        val on = frame.commandEnabled(tool.command)
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dpi(6f), 0, dpi(6f))
        addView(View(c).apply { background = Kit.Icon(tool.path, tool.color, dpi(40f), square = true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
        addView(TextView(c).apply {
            text = tool.short; textSize = 11.5f; typeface = Tokens.SANS; gravity = Gravity.CENTER; maxLines = if (c.largeText) 3 else 2; keepWordsWhole()
            setTextColor(if (tool.command == EditorCommand.CLEAR_TRANSCRIPT) Kit.dangerText(night) else Glass.text(night))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(6f) })
        val label = frame.commandLabel(tool.command).removeSuffix("…")
        contentDescription = if (on) label else "$label, unavailable"; tooltipText = label
        isEnabled = on; alpha = if (on) 1f else .4f
        if (on) {
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433), null, rounded(-1, dp(12f)))
            setOnClickListener { frame.runCommand(tool.command) }
        }
    }

    private fun command(command: EditorCommand, color: Int, path: String) = frame.commandEnabled(command).let { on ->
        Kit.row(c, frame.commandLabel(command).removeSuffix("…"), icon = Kit.square(c, path, color), menu = true,
            danger = command == EditorCommand.CLEAR_TRANSCRIPT, onClick = if (on) ({ frame.runCommand(command) }) else null).apply {
            isEnabled = on; alpha = if (on) 1f else .4f; if (!on) contentDescription = "${frame.commandLabel(command)}, unavailable"
        }
    }

    /** "Your handwriting, read": each reviewed line in reading order; taped lines are marked so they are not spoiled. */
    private fun handwriting(page: Page?) {
        val records = page?.activeReviewedInk.orEmpty().sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val covered = tapes.filter { it.id !in ink.revealedTapeIds }.map { tape ->
            RectF(tape.samples.minOf { it.x }, tape.samples.minOf { it.y }, tape.samples.maxOf { it.x }, tape.samples.maxOf { it.y })
        }
        body.addView(card().apply {
            dpi(6f).let { setPadding(0, it, 0, it) }
            if (records.isEmpty()) addView(Kit.text(c, "Nothing read on this page yet.", secondary = true).apply {
                textSize = 13.5f; setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
            })
            records.forEachIndexed { i, record ->
                val box = RectF(record.bounds.left, record.bounds.top, record.bounds.right, record.bounds.bottom)
                val taped = covered.any { RectF.intersects(it, box) }
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f))
                    addView(View(c).apply { background = rounded(if (taped) Glass.accent(night) else Kit.done(night), dp(3.5f)) },
                        LinearLayout.LayoutParams(dpi(7f), dpi(7f)).apply { marginEnd = dpi(10f) })
                    addView(Kit.text(c, if (taped) "Covered by study tape" else record.text.replace('\n', ' ')).apply {
                        textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                        if (taped) setTextColor(Glass.secondary(night))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(TextView(c).apply {
                        text = if (taped) "taped" else "line ${i + 1}"; textSize = 11f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(0xff8a93a6.toInt())
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(10f) })
                    if (i > 0) background = Divider(night, dp(14f))
                })
            }
        })
        val read = frame.commandEnabled(EditorCommand.HANDWRITING)
        // P595: with large text the two buttons stack, each the panel's width, so neither label splits a word.
        val stacked = c.largeText
        body.addView(LinearLayout(c).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            addView(button("Copy text", Kit.Button.PRIMARY, records.isNotEmpty()) {
                val text = records.joinToString("\n") { it.text }
                c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Handwriting", text))
                activity.message("Copied ${records.size} line${if (records.size == 1) "" else "s"} of handwriting")
            }, if (stacked) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button(if (records.isEmpty()) "Read handwriting" else "Fix a word", Kit.Button.TINTED, read) { frame.runCommand(EditorCommand.HANDWRITING) },
                if (stacked) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(8f) }
                else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dpi(8f) })
        }, gap(10f))
    }

    /** The board's study tape row: a strip of tape, how many are hidden and shown, and how to peek; [open] goes to the Tape tab. */
    private fun tapeRow(page: Page?, open: Boolean): View {
        val all = tapes; val hidden = all.count { it.id !in ink.revealedTapeIds }
        val title = if (all.isEmpty()) "No study tape here" else "$hidden hidden · ${all.size - hidden} shown"
        val detail = when {
            page == null -> ""
            all.isEmpty() -> "Select ink with the lasso, then Tape"
            else -> "Tap a strip with the pen to peek"
        }
        return StudyRow(c, title, TapeSwatch(c), detail, iconWidth = 48f, iconHeight = 24f).apply {
            contentDescription = "Study tape: $title. $detail"
            if (open) {
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
                setOnClickListener { segments.getChildAt(Tab.TAPE.ordinal).performClick() }
            }
        }
    }

    private fun tapeTab(page: Page?) {
        body.addView(rows(tapeRow(page, open = false)))
        val any = tapes.isNotEmpty()
        body.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(frame.commandLabel(EditorCommand.TAPE).removeSuffix(" study tape"), Kit.Button.TINTED, frame.commandEnabled(EditorCommand.TAPE)) {
                frame.runCommand(EditorCommand.TAPE)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Recall check", Kit.Button.PRIMARY, any && frame.commandEnabled(EditorCommand.TAPE)) { frame.startRecall() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dpi(8f) })
        }, gap(10f))
        body.addView(Kit.text(c, "Covers every strip, then shows them one at a time.",
            Kit.Type.CAPTION, secondary = true).apply { setPadding(dpi(4f), 0, dpi(4f), 0) }, gap(10f))
    }

    private fun marks(page: Page?) {
        body.addView(caption("Printed text"))
        if (page?.hasBackground != true) body.addView(card().apply {
            addView(Kit.text(c, "This page has no PDF or picture.", secondary = true).apply {
                textSize = 13.5f; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
            })
        }) else body.addView(Kit.Group(c, if (night) Kit.group(true) else Color.WHITE, 18f).apply {
            elevation = dp(1f); dpi(4f).let { setPadding(it, it, it, it) }
            TOOLS.take(2).forEach { (command, color, path) -> addView(command(command, color, path)) }
        })
        body.addView(Kit.text(c, "Highlights are ink: the lasso moves them, Undo removes them.",
            Kit.Type.CAPTION, secondary = true).apply { setPadding(dpi(4f), 0, dpi(4f), 0) }, gap(10f))
    }

    /** The board's 40 dp buttons. */
    private fun button(label: String, style: Kit.Button, enabled: Boolean, onClick: () -> Unit) =
        Kit.button(c, label, style, small = true, onClick = onClick).apply { minHeight = dpi(40f); minimumHeight = dpi(40f); textSize = 13.5f; isEnabled = enabled }

    /** A hairline above a list row, inset like the board's. */
    private class Divider(night: Boolean, private val inset: Float) : android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) {
        private val line = Paint().apply { color = Kit.separator(night) }
        override fun draw(canvas: Canvas) { canvas.drawRect(bounds.left + inset, bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + 1.5f, line) }
    }

    /** A short strip of the study tape's own kraft colour, a little crooked, as on the board. */
    private class TapeSwatch(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33ffffff; strokeWidth = context.dp(6f) }
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.rotate(-4f, width / 2f, height / 2f)
            paint.color = StudyTape.COLOR.toInt()
            val box = RectF(dp(2f), dp(3f), width - dp(2f), height - dp(3f))
            canvas.drawRoundRect(box, dp(2f), dp(2f), paint)
            canvas.clipRect(box)
            var x = -height.toFloat()
            while (x < width) { canvas.drawLine(x, height.toFloat(), x + height, 0f, stripe); x += dp(12f) }
            canvas.restore()
        }
    }

    /** A More study tools entry: its command, colour, icon and the short name under its icon (board 9). */
    data class Tool(val command: EditorCommand, val color: Int, val path: String, val short: String)

    companion object {
        /** The board's "More study tools", with its colours and icons. */
        val TOOLS = listOf(
            Tool(EditorCommand.PAGE_TEXT, 0xff0a84ff.toInt(), "M8 8h11v11H8zM5 16V5h11", "Page text"),
            Tool(EditorCommand.TEXT_MARK, 0xffffb800.toInt(), "M4 20h16M7 16l9-9 3 3-9 9H7z", "Highlight"),
            Tool(EditorCommand.PREPARE_STUDY, 0xff5e5ce6.toInt(), "M3 5h18v11H3zM8 20h8M12 16v4", "Study copy"),
            Tool(EditorCommand.INK_EXPORT, 0xff34c759.toInt(), "M12 3v12M8 11l4 4 4-4M5 21h14", "Export ink"),
            Tool(EditorCommand.CLEAR_TRANSCRIPT, 0xffff3b30.toInt(), "M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13", "Remove text"))
    }
}

/**
 * P615 (board 9): a Study panel row: a leading icon (a 30 dp coloured square, or the tape swatch), a title and its status
 * line, then the row's own buttons ([trail]). The rows sit in one white card; their content goes under them.
 */
internal class StudyRow(context: Context, title: String, icon: View, detail: String, iconWidth: Float = 30f, iconHeight: Float = 30f) : LinearLayout(context) {
    val detail: TextView
    /** The icon and text; the buttons join it, or go on their own line under it with large text so no word splits. */
    private val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val foot = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL; visibility = GONE }
    init {
        val night = context.night
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(60f); setPadding(context.dpi(12f), context.dpi(6f), context.dpi(12f), context.dpi(6f))
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // The tape swatch is wider than a square; it keeps the text where the squares put it.
        val wide = iconWidth > 30f
        head.addView(icon, LayoutParams(context.dpi(iconWidth), context.dpi(iconHeight)).apply { marginEnd = context.dpi(if (wide) 6f else 12f); if (wide) marginStart = -context.dpi(6f) })
        this.detail = TextView(context).apply {
            text = detail; textSize = 12.5f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night)); maxLines = if (context.largeText) Int.MAX_VALUE else 3
            setPadding(0, context.dpi(3f), 0, 0); setLineSpacing(0f, 1.1f); keepWordsWhole()
        }
        head.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = title; textSize = 15f; typeface = Tokens.SANS; includeFontPadding = false; setTextColor(Glass.text(night))
                maxLines = if (context.largeText) Int.MAX_VALUE else 2; keepWordsWhole()
            })
            addView(this@StudyRow.detail)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 0 })
        addView(foot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(6f) })
        // Centre the head in the 60 dp row.
        head.minimumHeight = context.dpi(48f)
    }
    /** Adds a button at the row's end (on the line under it with large text). */
    fun trail(view: View) {
        if (context.largeText) { foot.visibility = VISIBLE; foot.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(8f) }) }
        else head.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(8f) })
    }

    companion object {
        /** The board's 30 dp coloured square with a white icon. */
        fun square(context: Context, path: String, color: Int) = View(context).apply { background = Kit.square(context, path, color) }
    }
}

/**
 * P201 step 9: the board's Recall check bar. It covers every study tape on the page, then uncovers them one at a time
 * in reading order (or shuffled): "Peek next" shows the next strip, then "Knew it" or "Not yet" records the answer as a
 * dot. Answers and peeks are live state only, like peeling tape by hand; nothing is saved to the notebook.
 */
internal class RecallBar(context: Context, private val ink: InkCanvas, val pageId: String, ids: List<String>, private val close: () -> Unit) :
    LinearLayout(context) {
    private val c = context
    private val night = c.night
    var order = ids; private set
    /** Answers by tape id: true when remembered. */
    val answers = linkedMapOf<String, Boolean>()
    /** The tape uncovered and waiting for an answer, if any. */
    var peeking: String? = null; private set
    private val title = Kit.text(c, "Recall check").apply { textSize = 15f; typeface = Kit.Type.TITLE.typeface; maxLines = 1 }
    private val count = Kit.text(c, "", secondary = true).apply { textSize = 12f; maxLines = 1 }
    private val dots = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val buttons = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Glass.pane(c, night, 34f); elevation = dp(16f); isClickable = true
        minimumHeight = dpi(68f); setPadding(dpi(22f), dpi(10f), dpi(10f), dpi(10f))
        addView(LinearLayout(c).apply {
            orientation = VERTICAL
            addView(title); addView(count, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(2f) })
        })
        addView(dots, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(16f) })
        addView(View(c).apply { setBackgroundColor(if (night) 0x29ffffff else 0x1f1d2433) }, LayoutParams(dpi(1f), dpi(34f)).apply { marginStart = dpi(16f) })
        addView(buttons, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(16f) })
        restart(order)
    }

    private val next get() = order.firstOrNull { it !in answers }
    val remembered get() = answers.values.count { it }

    /** Covers every strip and asks [ids] again from the first. */
    private fun restart(ids: List<String>) {
        ink.revealAllTape(false); order = ids; answers.clear(); peeking = null; paint()
    }
    fun peek() { val id = next ?: return; ink.revealTape(id, true); peeking = id; paint() }
    fun answer(knew: Boolean) { val id = peeking ?: return; answers[id] = knew; peeking = null; paint() }
    fun shuffle() { val done = order.filter { it in answers }; order = done + order.filter { it !in answers }.shuffled(); paint() }
    fun showAll() { ink.revealAllTape(true); close() }

    private fun paint() {
        val finished = next == null && peeking == null
        title.text = if (finished) "Recall check done" else "Recall check"
        count.text = "$remembered of ${order.size} remembered"
        dots.removeAllViews()
        order.forEachIndexed { i, id ->
            val color = when {
                answers[id] == true -> Kit.done(night)
                answers[id] == false -> Kit.DANGER
                id == peeking || (peeking == null && id == next) -> Glass.accent(night)
                else -> if (night) 0x40ffffff else 0xffc9d1de.toInt()
            }
            dots.addView(View(c).apply { background = rounded(color, dp(3f)) }, LayoutParams(dpi(if (order.size > 12) 10f else 22f), dpi(6f)).apply { if (i > 0) marginStart = dpi(5f) })
        }
        dots.contentDescription = "$remembered remembered, ${answers.values.count { !it }} not yet, ${order.size - answers.size} to go"
        buttons.removeAllViews()
        fun add(view: TextView) = buttons.addView(view, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (buttons.childCount > 0) marginStart = dpi(8f) })
        when {
            finished -> {
                val missed = order.filter { answers[it] == false }
                add(grey(if (missed.isEmpty()) "Again" else "Try ${missed.size} again") { restart(missed.ifEmpty { order }) })
                add(Kit.button(c, "Done", Kit.Button.PRIMARY) { showAll() })
            }
            peeking != null -> {
                add(grey("Show all") { showAll() })
                add(Kit.button(c, "Not yet", Kit.Button.DANGER) { answer(false) })
                add(Kit.button(c, "Knew it", Kit.Button.PRIMARY) { answer(true) })
            }
            else -> {
                add(grey("Shuffle") { shuffle() }.apply { isEnabled = order.count { it !in answers } > 1 })
                add(grey("Show all") { showAll() })
                add(Kit.button(c, "Peek next →", Kit.Button.PRIMARY) { peek() }.apply { contentDescription = "Peek at the next strip" })
            }
        }
    }

    /** The board's grey pill buttons beside the primary one. */
    private fun grey(label: String, onClick: () -> Unit) = Kit.button(c, label, Kit.Button.TINTED, onClick = onClick).apply {
        setTextColor(Glass.text(night)); textSize = 14f
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433),
            rounded(if (night) 0x1fffffff else 0x0f1d2433, dp(24f)), rounded(-1, dp(24f)))
        setPadding(dpi(18f), 0, dpi(18f), 0)
    }
}
