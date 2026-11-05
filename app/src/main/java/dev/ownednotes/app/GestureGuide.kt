package dev.ownednotes.app

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

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

    /** Marks for the row squares, on a 24-unit grid. Fingertips are small rings, so one finger or two reads at a glance. */
    private object Mark {
        private fun tip(x: Float, y: Float, r: Float = 1.6f) = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
        val WRITE = "M3 20c2.5-5.5 4.5-8.5 6.5-8s-.5 6.5 1.5 7 3.5-3.5 5-7.5" + tip(18.5f, 6.5f)
        val ONE_MOVE = tip(12f, 5f) + "M12 10.5V21M8 17l4 4 4-4"
        val ONE_SWIPE = tip(19f, 12f) + "M14 12H3M7 8l-4 4 4 4"
        val TWO_MOVE = tip(7.5f, 5f) + tip(16.5f, 5f) + "M12 9.5V21M8 17l4 4 4-4"
        val TWO_SWIPE = tip(19f, 6.5f) + tip(19f, 17.5f) + "M14 12H3M7 8l-4 4 4 4"
        const val PINCH = "M10 14l-6 6M4 15v5h5M14 10l6-6M15 4h5v5"
        val TAP = tip(12f, 12f) + tip(12f, 12f, 8f)
        val TWO_TAP = tip(7f, 12f, 1f) + tip(17f, 12f, 1f) + tip(7f, 12f, 4.4f) + tip(17f, 12f, 4.4f)
        val HOLD = tip(12f, 12f, 9f) + "M12 7v5l3.5 2.2"
        const val BUTTON = "M8.5 3.5h7v17h-7zM12 7v5"
        const val SHAPE = "M12 4l9 16H3z"
        const val SCRIBBLE = "M3 16l4-9 3 11 4-13 3 12 4-7"
        val LOOP = "M18 6.5A8.5 8.5 0 1 0 20.5 12" + tip(12f, 12f)
    }

    internal data class Line(val mark: String, val tint: Int, val title: String, val detail: String)
    internal data class Part(val title: String, val lines: List<Line>)
    /** The guide on screen. */
    internal class Open(val sheet: Kit.Sheet, val tab: () -> Int, val choose: (Int) -> Unit)
    internal var open: Open? = null
        private set

    private fun shortcuts(ink: InkCanvas, tapDetail: String) = listOfNotNull(
        Line(Mark.TAP, SHORTCUT_TINT, "Tap", tapDetail),
        if (ink.twoFingerUndo) Line(Mark.TWO_TAP, SHORTCUT_TINT, "Tap with two fingers", "Undoes the last step.") else null,
        Line(Mark.HOLD, SHORTCUT_TINT, "Hold one finger still", "Opens the quick ring for tools and colours. On a picture, text box or attached file, it picks that up to move."),
    )

    /** What the page does with Draw with finger on. Pan, pinch and swipe work here whatever the finger setting says. */
    internal fun fingerParts(ink: InkCanvas) = listOf(
        Part("Writing", listOf(
            Line(Mark.WRITE, WRITE_TINT, "One finger, drag", "Writes with the chosen pen or tool. The lasso, laser, ruler and zoom box follow your finger too."),
        )),
        Part("Moving around", listOf(
            Line(Mark.TWO_MOVE, MOVE_TINT, "Two fingers, drag", "Moves the page."),
            Line(Mark.PINCH, MOVE_TINT, "Pinch with two fingers", "Zooms in and out."),
            Line(Mark.TWO_SWIPE, MOVE_TINT, "Two fingers, quick swipe", "Turns the page."),
        )),
        Part("Shortcuts", shortcuts(ink, "Opens a link or attached file. Anywhere else it leaves a dot.")),
    )

    /** What the page does with Draw with finger off, by the user's pen and finger settings. */
    internal fun penParts(ink: InkCanvas): List<Part> {
        val c = ink.context
        val button = when (ink.buttonAction) {
            InkCanvas.ButtonAction.ERASER -> "Uses the eraser while you hold it, with the pen near the page."
            InkCanvas.ButtonAction.PEN -> "Uses the pen while you hold it, with the pen near the page."
            InkCanvas.ButtonAction.SELECTED_TOOL -> null
        }
        val writing = listOfNotNull(
            Line(Kit.Paths.PEN, WRITE_TINT, "Pen on the page", if (ink.fingerSettings.handLock) "Writes with the chosen tool. Your hand is ignored while the pen is near." else "Writes with the chosen tool."),
            button?.let { Line(Mark.BUTTON, WRITE_TINT, "Hold the side button", it) },
            if (ScribbleErase.enabled(c)) Line(Mark.SCRIBBLE, WRITE_TINT, "Scribble over ink", "Erases it. Undo brings the ink back.") else null,
            if (ink.holdToShape) Line(Mark.SHAPE, WRITE_TINT, "Draw, then hold the pen still", "Turns the line into a clean shape.") else null,
            if (LoopSelect.enabled(c)) Line(Mark.LOOP, WRITE_TINT, "Draw a loop, then hold still", "Selects the ink inside it. Needs Hold assist set to Line or Shapes.") else null,
        )
        val twoFingers = listOf(
            Line(Mark.TWO_MOVE, MOVE_TINT, "Two fingers, drag", "Moves the page."),
            Line(Mark.PINCH, MOVE_TINT, "Pinch with two fingers", "Zooms in and out."),
        )
        val fingers = when (ink.fingerSettings.mode) {
            InkCanvas.FingerMode.ONE -> listOf(
                Line(Mark.ONE_MOVE, MOVE_TINT, "One finger, drag", "Moves the page."),
                Line(Mark.ONE_SWIPE, MOVE_TINT, "One finger, quick swipe", "Turns the page."),
            ) + twoFingers
            InkCanvas.FingerMode.TWO -> twoFingers
            InkCanvas.FingerMode.OFF -> listOf(Line(Kit.Paths.HAND, MOVE_TINT, "Fingers don't move the page", "This is turned off in Settings, under Palm & touch."))
        }
        return listOf(Part("Writing", writing), Part("Moving around", fingers), Part("Shortcuts", shortcuts(ink, "Opens a link or attached file.")))
    }

    private const val FINGER_NOTE = "A pen with a soft rubber tip counts as a finger, so it follows these gestures too. Keep your hand off the screen while you use one.\n\n" +
        "A pen your device recognises takes over the first time it touches the page, and fingers go back to moving the page, unless you have set the switch above yourself."
    private const val PEN_NOTE = "Any pen your device recognises works this way. Not every pen has a side button."

    /** Words wrap here: this is the one place they get to explain themselves in full. */
    private fun unlimit(view: View) {
        if (view is TextView) view.maxLines = Int.MAX_VALUE
        if (view is ViewGroup) for (i in 0 until view.childCount) unlimit(view.getChildAt(i))
    }

    private fun column(activity: MainActivity, parts: List<Part>) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val c = activity
        parts.forEach { part ->
            addView(Kit.section(c, part.title))
            addView(Kit.Group(c, Kit.group(c.night), 16f).apply {
                part.lines.forEach { line ->
                    addView(Kit.row(c, line.title, line.detail, Kit.Icon(line.mark, line.tint, c.dpi(30f), square = true)).also(::unlimit))
                }
            })
        }
    }

    /** Where the parts break into two columns, so the columns come out about even. */
    private fun split(parts: List<Part>): Int {
        val sizes = parts.map { it.lines.size + 1 }
        return (1 until parts.size).minByOrNull { abs(sizes.take(it).sum() - sizes.drop(it).sum()) } ?: parts.size
    }

    /** Opens on what the page does right now: fingers while Draw with finger is on, otherwise the pen. */
    fun show(activity: MainActivity): Open {
        val c = activity
        val ink = activity.ink
        val wide = c.resources.displayMetrics.widthPixels >= c.dpi(720f)
        val sheet = Kit.Sheet(c, "Gestures", cancel = "Done", maxWidth = if (wide) 900f else 760f)
        var tab = if (ink.drawWithFinger) 0 else 1
        val pages = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun page() {
            pages.removeAllViews()
            val parts = if (tab == 0) fingerParts(ink) else penParts(ink)
            val at = split(parts)
            if (wide && at < parts.size) pages.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(column(c, parts.take(at)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = c.dpi(10f) })
                addView(column(c, parts.drop(at)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dpi(10f) })
            }) else pages.addView(column(c, parts))
            pages.addView(Kit.text(c, if (tab == 0) FINGER_NOTE else PEN_NOTE, Kit.Type.CAPTION, secondary = true).apply {
                textSize = 13f; setPadding(dpi(4f), dpi(16f), dpi(4f), dpi(4f))
            })
        }
        fun fill() {
            sheet.body.removeAllViews()
            val finger = ink.drawWithFinger
            val seen = (c.applicationContext as NotesApplication).penSeen
            val state = when {
                finger && !seen -> "On, because no pen has been found yet. One finger writes."
                finger -> "On. One finger writes."
                else -> "Off. Only a pen writes, and fingers move the page."
            }
            val switch = Kit.Switch(c, finger, "Draw with finger") { on ->
                // The switch throws in the middle of a stroke. A sheet is modal, so this is only a guard.
                if (!ink.isEditing && !ink.navigating) { ink.selectDrawWithFinger(on); tab = if (on) 0 else 1 }
                sheet.body.post { fill() }
            }
            sheet.add(Kit.Group(c, Kit.group(c.night), 16f).apply {
                addView(Kit.row(c, "Draw with finger", state, trailing = switch).also(::unlimit))
            })
            sheet.add(Kit.segmented(c, listOf("Using a finger", "Using a pen"), tab) { tab = it; page() }.apply { contentDescription = "Show gestures for" }, 14f)
            page()
            sheet.add(pages, 0f)
            if (!activity.frame.settingsOpen) sheet.add(Kit.Group(c, Kit.group(c.night), 16f).apply {
                addView(Kit.row(c, "Change these in Settings", "Pen gestures, the side button, fingers and palm.", chevron = true) {
                    sheet.dismiss(); activity.frame.openSettings()
                })
            }, 12f)
        }
        fill()
        val shown = Open(sheet, { tab }, { tab = it; fill() })
        open = shown
        sheet.dialog.setOnDismissListener { if (open === shown) open = null }
        sheet.show()
        return shown
    }
}
