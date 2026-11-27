package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.app.SyncPanels.P
import kotlin.math.roundToInt

/**
 * P732 (design canvas board 25, 27 Nov 2026): Library backup and Notebook storage in board 21's Look A. [PlaceCard] is
 * the card both use: this device and a place (the backup folder, a notebook's original folder), joined by a line with
 * a circle in the middle, then the state. [backup] is the Library backup sheet; Notebook storage is
 * [NotebookStoragePanel]. Explanations are behind the (i) buttons ([Explain]).
 */
internal object StoragePanels {
    /** "tablet" or "phone", by the screen's smallest width. */
    fun deviceWord(context: Context) = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    private fun howBackupWorks(night: Boolean) = listOf(
        Explain.Line(P.REPEAT, Glass.accent(night), "Only changed notebooks are copied."),
        Explain.Line(P.CHECK, SyncPanels.green(night), "Each copy is checked."),
        Explain.Line(P.DOOR, SyncPanels.slate(night), "Also when you leave the app."),
        Explain.Line(P.RESTORE, SyncPanels.slate(night), "Restore adds back missing notebooks; nothing is replaced."))

    /** What the Library backup sheet's parts do: MainActivity's pickers and dialogs. */
    class BackupActions(
        val choose: () -> Unit,
        val backUp: () -> Unit,
        val restore: () -> Unit,
        val stop: () -> Unit,
        val openRecovery: () -> Unit,
        val updateRecovery: () -> Unit,
    )

    /**
     * Library backup (board 25). With a folder: the card (this device to the backup folder, when it last finished),
     * Back up now in the header, the Folder and Restore tiles, the owner's recovery copies as one dashed row, and Stop
     * backing up small and red. Before a folder: an invitation card and Choose backup folder.
     */
    fun backup(activity: MainActivity, context: Context, act: BackupActions): Kit.Sheet {
        val night = context.night
        val has = LibraryBackup.folderUri(activity) != null
        lateinit var sheet: Kit.Sheet
        sheet = Kit.Sheet(context, "Library backup", if (has) "Back up now" else null, cancel = "Done") { act.backUp(); true }
        val name = "“${LibraryBackup.folderName(activity)}”"
        val card = if (has) PlaceCard(context, P.ARCHIVE, null, "How backup works")
            else PlaceCard(context, P.FOLDER_PLUS, "Keep a copy of every notebook" to "In a folder you choose. Only changed notebooks are copied.", "How backup works")
        card.info.setOnClickListener { Explain.show(card.info, "How backup works", howBackupWorks(night)) }
        if (has) {
            val last = LibraryBackup.lastBackup(activity)
            val where = "in ${LibraryBackup.folderLabel(activity)}"
            val device = "This ${deviceWord(context)}"
            card.show(if (last == 0L) PlaceCard.State(SyncPanels.amber(night), P.ARROW, "No backup has finished yet", where.replaceFirstChar { it.uppercase() }, P.CLOCK, left = device, right = name)
                else PlaceCard.State(SyncPanels.green(night), P.ARROW, "Backed up ${EnglishTime.ago(last)}",
                    "${LibraryBackup.lastCount(activity)} notebooks · $where", P.CHECK, left = device, right = name))
        } else card.show(PlaceCard.State(Glass.accent(night), P.ARROW, dashedLine = true, rightDashed = true))
        sheet.add(card)
        if (has) {
            sheet.add(SyncPanels.sectionRow(context, "Backup folder", null), gap = 16f)
            sheet.add(SyncPanels.TileGrid(context, 150f).apply {
                addView(SyncPanels.PlaceTile(context, P.FOLDER, SyncPanels.green(night), "Folder").apply {
                    state(name, Glass.secondary(night)); setOnClickListener { sheet.dismiss(); act.choose() }
                })
                addView(SyncPanels.PlaceTile(context, P.RESTORE, SyncPanels.slate(night), "Restore").apply {
                    state("Add back missing notebooks", Glass.secondary(night)); setOnClickListener { sheet.dismiss(); act.restore() }
                })
            }, gap = 2f)
        } else {
            sheet.add(SyncPanels.choiceCard(context, P.FOLDER, Glass.accent(night), "Choose backup folder", "OneDrive, Google Drive or any folder app",
                recommended = true, chevron = true) { sheet.dismiss(); act.choose() })
        }
        if (activity.personalTools) {
            val copies = (activity.application as NotesApplication).safetyCopies
            val status = copies.status()
            sheet.add(SyncPanels.dashedRow(context, P.DOC, "Readable recovery copies", copies.brief()) {
                sheet.dismiss(); recovery(context, status, act)
            }, gap = 16f)
        }
        if (has) sheet.add(SyncPanels.stopRow(context, "Stop backing up") { sheet.dismiss(); act.stop() }, gap = 16f)
        return sheet.show()
    }

    /** The owner's readable recovery copies (personal builds only): what they are, open them, or refresh them. */
    private fun recovery(context: Context, status: String, act: BackupActions) = Kit.Sheet(context, "Readable recovery copies", cancel = "Done").apply {
        add(Kit.text(context, status, secondary = true))
        add(Kit.text(context, "These ordinary files work outside Scribella and its app lock. The latest two verified local generations are retained. Folder-provider delivery may follow later.",
            Kit.Type.CAPTION, secondary = true), 8f)
        add(Kit.button(context, "Open recovery PDFs in Files", Kit.Button.TINTED) { act.openRecovery() })
        add(Kit.button(context, "Update recovery copies", Kit.Button.PLAIN) { dismiss(); act.updateRecovery() }, 8f)
    }.show()

    /** A card on a tile: a disc, a big number and a label under it ("2.7 kB", "This notebook"); 72 dp tall. */
    internal class StatTile(context: Context, path: String, color: Int, numberDescription: String) : LinearLayout(context) {
        private val night = context.night
        val number = SyncPanels.label(context, "Checking…", 22f, true, Glass.text(night)).apply { contentDescription = numberDescription; maxLines = 2 }
        val words = SyncPanels.label(context, "", 13f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 4 else 2; ellipsize = TextUtils.TruncateAt.END }
        init {
            orientation = HORIZONTAL; gravity = Gravity.TOP
            background = SyncPanels.cardBackground(context)
            minimumHeight = context.dpi(72f); setPadding(context.dpi(14f), context.dpi(14f), context.dpi(14f), context.dpi(12f))
            addView(SyncPanels.disc(context, path, color, 40f, 22f))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(number, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = -context.dpi(2f) })
                addView(words, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(4f) })
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(14f) })
        }
    }

    /**
     * Board 25's card: this device and a place ([placePath]) joined by a line with a circle in the middle. [invite]
     * (a title and a line) shows before there is a place; otherwise the two labels and the state, with its badge.
     */
    internal class PlaceCard(context: Context, placePath: String, private val invite: Pair<String, String>?, infoLabel: String) : ViewGroup(context) {
        /** [color] for the line, circle and badge; [middle] the circle's icon; dashed parts for what is missing. */
        data class State(val color: Int, val middle: String, val head: String = "", val under: String = "", val badge: String? = null,
            val dashedLine: Boolean = false, val leftDashed: Boolean = false, val rightDashed: Boolean = false, val left: String = "", val right: String = "")

        private val night = context.night
        var state = State(Glass.accent(night), P.ARROW); private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f)
        private val device = Explain.Disc(Glass.accent(night), P.TABLET, .5f, fill = .10f)
        private val deviceDashed = Explain.Disc(Glass.accent(night), P.TABLET, .5f, dashed = true)
        private val place = Explain.Disc(SyncPanels.green(night), placePath, .5f)
        private val placeDashed = Explain.Disc(SyncPanels.green(night), placePath, .5f, dashed = true)
        private var middle = Kit.Icon(P.ARROW, Glass.accent(night), 0)
        val info = Explain.Button(context, infoLabel)
        private val left = if (invite != null) null else centred(SyncPanels.label(context, "", 14f, true, Glass.text(night)))
        private val right = if (invite != null) null else centred(SyncPanels.label(context, "", 14f, true, Glass.text(night)))
        val status = centred(SyncPanels.label(context, invite?.first ?: "", if (invite != null) 18f else 17f, true, Glass.text(night)).apply {
            compoundDrawablePadding = context.dpi(8f); if (invite == null) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        val detail = centred(SyncPanels.label(context, invite?.second ?: "", if (invite != null) 14f else 13f, false, Glass.secondary(night)))

        init {
            setWillNotDraw(false)
            addView(info)
            left?.let { addView(it) }; right?.let { addView(it) }
            addView(status); addView(detail)
        }
        private fun centred(v: TextView) = v.apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END }

        fun show(state: State) {
            this.state = state
            middle = Kit.Icon(state.middle, state.color, 0)
            if (invite == null) {
                left!!.text = state.left; right!!.text = state.right
                status.text = state.head
                status.setCompoundDrawablesRelative(state.badge?.let { Explain.Badge(state.color, it, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) } }, null, null, null)
                detail.text = state.under
                detail.visibility = if (state.under.isEmpty()) View.GONE else View.VISIBLE
            }
            requestLayout(); invalidate()
        }

        private fun centres(w: Int): Pair<Int, Int> {
            val o = minOf(dpi(if (invite != null) 150f else 170f), w / 2 - dpi(56f)).coerceAtLeast(dpi(44f))
            return (w / 2 - o) to (w / 2 + o)
        }
        private val discTop get() = dpi(if (invite != null) 30f else 34f)
        private fun statusTop() = if (invite != null) dpi(122f) else dpi(114f) + maxOf(left!!.measuredHeight, right!!.measuredHeight) + dpi(14f)
        private fun detailTop() = statusTop() + status.measuredHeight + dpi(if (invite != null) 7f else 6f)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun most(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.AT_MOST)
            fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)
            info.measure(exactly(dpi(44f)), exactly(dpi(44f)))
            val (lx, rx) = centres(w)
            fun room(x: Int) = minOf(dpi(200f), 2 * minOf(x, w - x) - dpi(8f))
            left?.measure(most(room(lx)), free); right?.measure(most(room(rx)), free)
            status.measure(most(w - dpi(32f)), free); detail.measure(most(w - dpi(32f)), free)
            val end = if (detail.visibility == View.GONE) statusTop() + status.measuredHeight + dpi(16f)
                else detailTop() + detail.measuredHeight + dpi(if (invite != null) 20f else 10f)
            setMeasuredDimension(w, end)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            info.layout(w - dpi(52f), dpi(8f), w - dpi(8f), dpi(52f))
            val (lx, rx) = centres(w)
            left?.let { it.layout(lx - it.measuredWidth / 2, dpi(114f), lx + (it.measuredWidth + 1) / 2, dpi(114f) + it.measuredHeight) }
            right?.let { it.layout(rx - it.measuredWidth / 2, dpi(114f), rx + (it.measuredWidth + 1) / 2, dpi(114f) + it.measuredHeight) }
            val s = statusTop()
            status.layout((w - status.measuredWidth) / 2, s, (w + status.measuredWidth) / 2, s + status.measuredHeight)
            val d = detailTop()
            detail.layout((w - detail.measuredWidth) / 2, d, (w + detail.measuredWidth) / 2, d + detail.measuredHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = dp(Glass.CARD)
            val fill = Kit.cardFill(night)
            val c = state.color
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, 0f, h * .7f, blend(fill, c, if (night) .10f else .06f), fill, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            paint.shader = null
            val hair = maxOf(1f, dp(.5f))
            paint.style = Paint.Style.STROKE; paint.strokeWidth = hair; paint.color = if (night) Glass.RULE_NIGHT else Glass.RULE
            canvas.drawRoundRect(hair / 2, hair / 2, w - hair / 2, h - hair / 2, r, r, paint)

            val (lx, rx) = centres(width)
            val cy = discTop + dpi(36f)
            val d = dpi(36f)
            (if (state.leftDashed) deviceDashed else device).apply { setBounds(lx - d, cy - d, lx + d, cy + d) }.draw(canvas)
            (if (state.rightDashed) placeDashed else place).apply { setBounds(rx - d, cy - d, rx + d, cy + d) }.draw(canvas)

            val x1 = lx + dp(44f); val x2 = rx - dp(44f)
            paint.strokeWidth = dp(2.5f); paint.strokeCap = Paint.Cap.BUTT
            // Before there is a place, the line is a neutral dashed one, as on Instant sync's card.
            paint.color = if (invite != null) (if (night) 0x73ffffff else 0x731d2433) else Explain.alpha(c, .45f)
            paint.pathEffect = if (state.dashedLine) dash else null
            canvas.drawLine(x1, cy.toFloat(), x2, cy.toFloat(), paint)
            paint.pathEffect = null
            val cx = width / 2f
            paint.style = Paint.Style.FILL; paint.color = fill
            canvas.drawCircle(cx, cy.toFloat(), dp(20f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Explain.alpha(c, .45f)
            canvas.drawCircle(cx, cy.toFloat(), dp(20f), paint)
            val i = dpi(11f)
            middle.setBounds(cx.roundToInt() - i, cy - i, cx.roundToInt() + i, cy + i); middle.draw(canvas)
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xff) + ((((b shr shift) and 0xff) - ((a shr shift) and 0xff)) * t)).roundToInt()
            return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
