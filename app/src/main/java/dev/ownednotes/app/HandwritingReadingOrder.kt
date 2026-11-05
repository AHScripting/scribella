package dev.ownednotes.app

import android.content.Context
import android.view.View
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLayout
import java.util.concurrent.atomic.AtomicBoolean

/** Ink-only snapshot of the exact recognizer groups. No engine, notebook asset or provider access. */
internal fun handwritingReadingOrderPreview(context: Context, input: HandwritingInput,
    layout: HandwritingLayout, separateLines: Boolean, dispatch: (() -> Unit) -> Unit,
    current: () -> Boolean): Kit.Sheet {
    val closed=AtomicBoolean(false)
    val sheet=Kit.Sheet(context,"Reading order",cancel="Close")
    val status=Kit.text(context,"Preparing reading order…",secondary=true).apply {
        contentDescription="Reading order status"
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    sheet.add(status)
    sheet.dialog.setOnDismissListener { closed.set(true) }
    sheet.body.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
        // Match the existing Kit cap even when async content arrives after its initial show post.
        sheet.dialog.window?.decorView?.post {
            if (!closed.get() && sheet.dialog.isShowing) sheet.dialog.window?.let { window ->
                val cap=(context.resources.displayMetrics.heightPixels*.9f).toInt()
                if (window.decorView.height>cap) window.setLayout(window.attributes.width,cap)
            }
        }
    }
    sheet.show()
    fun live() = !closed.get() && sheet.dialog.isShowing
    fun sourceCurrent() = runCatching(current).getOrDefault(false)
    fun fail(message: String) {
        if (live()) status.text=message
    }
    runCatching {
        dispatch {
            if (!closed.get()) {
                val prepared=runCatching { layout.prepare(input.strokes,separateLines) }
                context.mainExecutor.execute {
                    if (live()) {
                        if (!sourceCurrent()) fail("The page or reading settings changed. Close this preview and open it again.")
                        else prepared.onSuccess { groups ->
                            status.text="${groups.size} recognition ${if(groups.size==1) "block" else "blocks"}, in reading order. Check the ink below before recognising. Original ink stays unchanged."
                            groups.forEachIndexed { index,group ->
                                sheet.add(Kit.section(context,"Block ${index+1}"))
                                val ink=HandwritingLayoutInk(context,group.strokes)
                                sheet.add(Kit.picture(context,ink.art(0f,ink.bounds(0f))).apply {
                                    contentDescription="Recognition block ${index+1}, ${group.strokes.size} ink ${if(group.strokes.size==1) "fragment" else "fragments"}"
                                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                    isFocusable=true
                                    minimumHeight=context.dpi(96f)
                                })
                            }
                            // Reapply the kit's height cap after asynchronous content is measured.
                            sheet.show()
                        }.onFailure { fail("Cannot preview reading order. ${it.message ?: "Choose a smaller area or different layout."}") }
                    }
                }
            }
        }
    }.onFailure { fail("Cannot prepare reading order. Close this preview and try again.") }
    return sheet
}
