package dev.ownednotes.app

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/*
 * P569 feature record: the gesture guide. Task: learn what fingers and pens do on the page, with or without an S Pen.
 * Scope: reads tool settings; its one control is the same Draw with finger switch as the pen popover. Home: "Gestures" in
 * the Library side panel and More menu, EditorCommand.GESTURES in the Notebook (⋮) menu and command palette, and a row in
 * Settings under Pen & stylus and Palm & touch. Frequency: rare, but it must be easy to find. Destructive: none.
 * See MENU-AND-VISUAL-AUDIT.md. The lines follow the user's settings, so what it says is what the page does.
 */
internal object GestureGuide {
    private val WRITE_TINT get() = Glass.ACCENT
    private const val MOVE_TINT = 0xff168f84.toInt()
    private const val SHORTCUT_TINT = 0xffd9821c.toInt()
    private val P = SyncPanels.P

    /** Marks the boards have no icon for, on a 24-unit grid. Fingertips are small rings. */
    private object Mark {
        private fun tip(x: Float, y: Float, r: Float = 1.6f) = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
        val WRITE = "M3 20c2.5-5.5 4.5-8.5 6.5-8s-.5 6.5 1.5 7 3.5-3.5 5-7.5" + tip(18.5f, 6.5f)
        val LOOP = "M18 6.5A8.5 8.5 0 1 0 20.5 12" + tip(12f, 12f)
    }

    /**
     * P735 (board 27): each gesture is a tile, what you do over what it does in a word or two; a part's longer lines
     * ([notes]) wait behind the (i) after its title.
     */
    internal data class Line(val mark: String, val tint: Int, val title: String, val detail: String)
    internal data class Part(val title: String, val lines: List<Line>, val notes: List<Explain.Line> = emptyList())
    /** The guide on screen. */
    internal class Open(val sheet: Kit.Sheet, val tab: () -> Int, val choose: (Int) -> Unit)
    internal var open: Open? = null
        private set

    private fun slate(ink: InkCanvas) = SyncPanels.slate(ink.context.night)

    private fun shortcuts(ink: InkCanvas, tapNote: Explain.Line?) = Part("Shortcuts", listOfNotNull(
        Line(P.TAP, SHORTCUT_TINT, "Tap", "Opens a link or file"),
        if (ink.twoFingerUndo) Line(P.TWO_TAP, SHORTCUT_TINT, "Two-finger tap", "Undo") else null,
        Line(P.CLOCK, SHORTCUT_TINT, "Hold one finger", "Quick ring"),
    ), listOfNotNull(
        tapNote,
        Explain.Line(P.CLOCK, SHORTCUT_TINT, "Hold on a picture, text box or file to pick it up and move it."),
        if (ink.twoFingerUndo) Explain.Line(P.TWO_TAP, slate(ink), "Two-finger tap can be turned off in Palm & touch.") else null,
    ))

    /** What the page does with Draw with finger on. Pan, pinch and swipe work here whatever the finger setting says. */
    internal fun fingerParts(ink: InkCanvas) = listOf(
        Part("Writing", listOf(Line(Mark.WRITE, WRITE_TINT, "One finger, drag", "Writes")), listOf(
            Explain.Line(P.PEN, WRITE_TINT, "The lasso, laser, ruler and zoom box follow your finger too."),
            Explain.Line(P.HAND, slate(ink), "A soft rubber-tip pen counts as a finger. You can rest your hand while you write."),
            Explain.Line(P.SIDE_BUTTON, slate(ink), "A pen your device knows takes over the first time it touches the page."))),
        Part("Moving around", listOf(
            Line(P.TWO_DRAG, MOVE_TINT, "Two fingers, drag", "Moves the page"),
            Line(P.PINCH, MOVE_TINT, "Pinch", "Zooms"),
            Line(P.TWO_SWIPE, MOVE_TINT, "Two fingers, swipe", "Turns the page"),
        )),
        shortcuts(ink, Explain.Line(P.TAP, SHORTCUT_TINT, "A tap anywhere else leaves a dot.")),
    )

    /** What the page does with Draw with finger off, by the user's pen and finger settings. */
    internal fun penParts(ink: InkCanvas): List<Part> {
        val c = ink.context
        val button = when (ink.buttonAction) {
            InkCanvas.ButtonAction.ERASER -> "Eraser while held"
            InkCanvas.ButtonAction.PEN -> "Pen while held"
            InkCanvas.ButtonAction.SELECTED_TOOL -> null
        }
        val scribble = ScribbleErase.enabled(c)
        val loop = LoopSelect.enabled(c)
        val writing = listOfNotNull(
            Line(P.PEN, WRITE_TINT, "Pen on the page", "Writes"),
            button?.let { Line(P.SIDE_BUTTON, WRITE_TINT, "Side button", it) },
            if (scribble) Line(P.SQUIGGLE, WRITE_TINT, "Scribble over ink", "Erases") else null,
            if (ink.holdToShape) Line(P.SHAPE, WRITE_TINT, "Draw, hold still", "Clean shape") else null,
            if (loop) Line(Mark.LOOP, WRITE_TINT, "Loop, hold still", "Selects ink") else null,
        )
        val notes = listOfNotNull(
            if (ink.fingerSettings.handLock) Explain.Line(P.PEN, WRITE_TINT, "Your hand is ignored while the pen is near.") else null,
            Explain.Line(P.SIDE_BUTTON, slate(ink), "Not every pen has a side button."),
            if (scribble) Explain.Line(P.UNDO, slate(ink), "Undo brings scribbled ink back.") else null,
            if (loop) Explain.Line(P.WRENCH, slate(ink), "Loop select needs Hold assist set to Line or Shapes.") else null,
        )
        val twoFingers = listOf(
            Line(P.TWO_DRAG, MOVE_TINT, "Two fingers, drag", "Moves the page"),
            Line(P.PINCH, MOVE_TINT, "Pinch", "Zooms"),
        )
        val fingers = when (ink.fingerSettings.mode) {
            InkCanvas.FingerMode.ONE -> Part("Moving around", listOf(
                Line(P.DRAG, MOVE_TINT, "One finger, drag", "Moves the page"),
                Line(P.SWIPE, MOVE_TINT, "Quick swipe", "Turns the page"),
            ) + twoFingers)
            InkCanvas.FingerMode.TWO -> Part("Moving around", twoFingers)
            InkCanvas.FingerMode.OFF -> Part("Moving around", listOf(Line(P.HAND, MOVE_TINT, "Fingers don't move the page", "Off in Palm & touch")))
        }
        return listOf(Part("Writing", writing, notes), fingers, shortcuts(ink, null))
    }

    private fun section(activity: MainActivity, part: Part) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val c = activity
        val info = if (part.notes.isEmpty()) null else Explain.Button(c, "About ${part.title.lowercase()}").apply {
            setOnClickListener { Explain.show(this, part.title, part.notes) }
        }
        addView(SyncPanels.sectionRow(c, part.title, info), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dpi(16f); bottomMargin = c.dpi(8f)
        })
        addView(SyncPanels.TileGrid(c, 150f, stretch = false).apply {
            part.lines.forEach { line ->
                addView(SyncPanels.PlaceTile(c, line.mark, line.tint, line.title, chevron = false, discSize = 44f).apply {
                    state(line.detail, Glass.secondary(c.night))
                })
            }
        })
    }

    /** Opens on what the page does right now: fingers while Draw with finger is on, otherwise the pen. */
    fun show(activity: MainActivity): Open {
        val c = activity
        val ink = activity.ink
        val sheet = Kit.Sheet(c, "Gestures", cancel = "Done")
        var tab = if (ink.drawWithFinger) 0 else 1
        val pages = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun page() {
            pages.removeAllViews()
            (if (tab == 0) fingerParts(ink) else penParts(ink)).forEach { pages.addView(section(c, it)) }
        }
        fun fill() {
            sheet.body.removeAllViews()
            val finger = ink.drawWithFinger
            val seen = (c.applicationContext as NotesApplication).penSeen
            val state = when {
                finger && !seen -> "On · no pen found yet"
                finger -> "On · one finger writes"
                else -> "Off · fingers move the page"
            }
            val switch = Kit.Switch(c, finger, "Draw with finger") { on ->
                // The switch throws in the middle of a stroke. A sheet is modal, so this is only a guard.
                if (!ink.isEditing && !ink.navigating) { ink.selectDrawWithFinger(on); tab = if (on) 0 else 1 }
                sheet.body.post { fill() }
            }
            sheet.add(Kit.Group(c, Kit.group(c.night), 20f).apply {
                addView(Kit.row(c, "Draw with finger", state, icon = Explain.Disc(SyncPanels.slate(c.night), P.HAND, 22f / 40f), iconSize = 40f, trailing = switch))
            })
            sheet.add(Kit.segmented(c, listOf("Using a finger", "Using a pen"), tab) { tab = it; page() }.apply { contentDescription = "Show gestures for" }, 14f)
            page()
            sheet.add(pages, 0f)
            if (!activity.frame.settingsOpen) sheet.add(Kit.Group(c, Kit.group(c.night), 20f).apply {
                addView(Kit.row(c, "Change these in Settings", icon = Explain.Disc(SyncPanels.slate(c.night), P.GEAR, 20f / 36f), iconSize = 36f, chevron = true) {
                    sheet.dismiss(); activity.frame.openSettings()
                })
            }, 20f)
        }
        fill()
        val shown = Open(sheet, { tab }, { tab = it; fill() })
        open = shown
        sheet.dialog.setOnDismissListener { if (open === shown) open = null }
        sheet.show()
        return shown
    }
}
