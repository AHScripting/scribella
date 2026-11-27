package dev.ownednotes.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.NotebookMerge

/**
 * P699 (docs/SYNC-MERGE-PLAN.md, MENU-AND-VISUAL-AUDIT "P699 Changed on two devices"): a notebook was changed here and on
 * another device since the two last agreed. Nothing has been changed on either side yet. The sheet says what each side
 * changed and asks what to keep; "Not now" leaves it waiting and sync asks again. The version not kept goes into the
 * notebook's Earlier versions first, so it can be restored.
 */
internal class BothChangedChoice(private val activity: MainActivity, private val details: FolderSync.Details,
    private val choose: (FolderSync.Choice) -> Unit, private val closedThen: (chosen: Boolean) -> Unit) {
    private val c get() = activity
    private val night = c.night
    private var chosen = false
    private val name = details.title.ifBlank { "Untitled" }
    internal val sheet = Kit.Sheet(c, "Changed on both devices", cancel = "Not now", maxWidth = 640f, centred = true,
        subtitle = "“$name” was changed here and on your other device. Nothing has been changed yet.")
    internal val cards = LinkedHashMap<FolderSync.Choice, View>()
    val isShowing get() = sheet.dialog.isShowing
    fun dismiss() = sheet.dismiss()

    /** Chooses as a tap on a card does. Combine is refused when this device has no record to combine from. */
    internal fun pick(choice: FolderSync.Choice) {
        if (chosen || (choice == FolderSync.Choice.COMBINE && !details.canCombine)) return
        chosen = true; dismiss(); choose(choice)
    }

    private fun card(choice: FolderSync.Choice, title: String, hint: String, recommended: Boolean = false, enabled: Boolean = true) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val edge = if (recommended && enabled) Glass.ACCENT else if (night) Glass.EDGE_NIGHT else Glass.EDGE
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Glass.ACCENT and 0x30ffffff),
                rounded(Kit.cardFill(night), c.dp(16f), edge, if (recommended && enabled) c.dpi(2f) else 1), null)
            setPadding(c.dpi(16f), c.dpi(12f), c.dpi(16f), c.dpi(14f))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Kit.text(c, title, Kit.Type.HEADLINE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (recommended && enabled) addView(Kit.text(c, "Recommended", Kit.Type.CAPTION).apply { setTextColor(Glass.ACCENT) })
            })
            addView(Kit.text(c, hint, Kit.Type.CAPTION, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(4f) })
            contentDescription = (if (recommended && enabled) "$title, recommended. " else "$title. ") + hint
            isClickable = enabled; isFocusable = enabled; isEnabled = enabled
            if (!enabled) alpha = .45f
            setOnClickListener { pick(choice) }
            cards[choice] = this
        }

    fun show(): BothChangedChoice {
        // What each side changed, in words.
        sheet.add(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (night) 0x14ffffff else 0x0d1d2433, c.dp(14f), 0, 0)
            setPadding(c.dpi(14f), c.dpi(10f), c.dpi(14f), c.dpi(10f))
            addView(Kit.text(c, "This device: ${describe(details.here)}", Kit.Type.BODY))
            addView(Kit.text(c, "Other device: ${describe(details.there)}" +
                (if (details.otherTitle != details.title) ", now called “${details.otherTitle}”" else ""), Kit.Type.BODY),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(6f) })
        }, 8f)
        sheet.add(card(FolderSync.Choice.COMBINE, "Combine both",
            if (details.canCombine) "Every page and all writing from both devices. Where the same thing was changed on both, that page is kept twice, the other device's copy right after it."
            else "Not possible for this notebook: this device has no record of the version both last shared. Choose another way.",
            recommended = true, enabled = details.canCombine), 12f)
        sheet.add(card(FolderSync.Choice.KEEP_HERE, "Keep this device's", "The other device's version is kept in Earlier versions."), 10f)
        sheet.add(card(FolderSync.Choice.KEEP_THERE, "Keep the other device's", "This device's version is kept in Earlier versions."), 10f)
        sheet.add(card(FolderSync.Choice.KEEP_BOTH, "Keep both as separate notebooks", "The other device's version becomes “$name (other device)”."), 10f)
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dismiss() } }
        activity.lifecycle.addObserver(observer)
        sheet.dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer); closedThen(chosen) }
        sheet.show()
        return this
    }

    companion object {
        /** "2 pages added, page 3 changed" in plain words. */
        fun describe(changes: NotebookMerge.Changes?): String = when {
            changes == null -> "changes not known"
            changes.none -> "no changes"
            else -> listOfNotNull(
                changes.added.takeIf { it > 0 }?.let { if (it == 1) "1 page added" else "$it pages added" },
                changes.removed.takeIf { it > 0 }?.let { if (it == 1) "1 page removed" else "$it pages removed" },
                changes.changedPages.takeIf { it.isNotEmpty() }?.let { p ->
                    when { p.size == 1 -> "page ${p[0]} changed"; p.size <= 4 -> "pages ${p.dropLast(1).joinToString(", ")} and ${p.last()} changed"; else -> "${p.size} pages changed" }
                },
                "renamed".takeIf { changes.retitled }).joinToString(", ")
        }
    }
}
