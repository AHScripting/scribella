package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import java.util.WeakHashMap

/**
 * P072 eyedropper (F030): armed from the Pen or Highlighter colour row; the next tap on ink sets that tool's colour
 * to the stroke's colour. A hint pill on the canvas says what to do; a tap off the canvas cancels.
 * Taps are taken before the canvas sees them, so arming never draws, erases or selects anything.
 */
object Eyedropper {
    private class State(val highlighter: Boolean, val hint: Hint)
    private val armed = WeakHashMap<InkCanvas, State>()
    private val tracking = WeakHashMap<InkCanvas, Boolean>()

    fun isArmed(ink: InkCanvas) = armed.containsKey(ink)
    /** For tests: the hint currently shown on [ink], or null. */
    fun hintText(ink: InkCanvas): String? = armed[ink]?.hint?.text

    fun arm(ink: InkCanvas, highlighter: Boolean) {
        disarm(ink)
        val hint = Hint(ink.resources.displayMetrics.density, "Tap ink to pick its colour")
        hint.setBounds(0, 0, ink.width, ink.height)
        ink.overlay.add(hint)
        armed[ink] = State(highlighter, hint)
    }
    fun disarm(ink: InkCanvas) {
        armed.remove(ink)?.let { ink.overlay.remove(it.hint) }
        tracking.remove(ink)
    }

    /** Window-level touch hook: returns true when the eyedropper took the event. */
    fun touch(ink: InkCanvas, event: MotionEvent): Boolean {
        val state = armed[ink] ?: return false
        val location = IntArray(2).also { ink.getLocationInWindow(it) }
        val x = event.x - location[0]; val y = event.y - location[1]
        val over = ink.isShown && x in 0f..ink.width.toFloat() && y in 0f..ink.height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!over) { disarm(ink); return false }
                tracking[ink] = true; return true
            }
            MotionEvent.ACTION_UP -> {
                if (tracking.remove(ink) != true) return false
                if (over) pick(ink, state, x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { tracking.remove(ink); return true }
            else -> return tracking[ink] == true
        }
    }

    private fun pick(ink: InkCanvas, state: State, x: Float, y: Float) {
        val picked = ink.inkColorAt(x, y, ink.resources.displayMetrics.density * SLOP_DP)
        if (picked == null) { state.hint.text = "No ink there. Tap a stroke"; state.hint.invalidateSelf(); return }
        // P089: a see-through stroke gives its colour and, to the pen, its opacity.
        val color = picked or 0xff000000L
        if (state.highlighter) ink.selectHighlighterStyle(ink.highlighterStyle.copy(color = color))
        else ink.selectPenStyle(ink.penStyle.copy(color = color, opacity = PenStyle.opacityOf(picked)))
        val app = ink.context.applicationContext as NotesApplication
        runCatching { InkColors(app.getSharedPreferences(app.preferencesName, android.content.Context.MODE_PRIVATE)).remember(color) }
        ink.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        disarm(ink)
        ink.onToolStateChanged()
    }

    private const val SLOP_DP = 8f

    /** Ink-coloured pill centred near the top of the canvas. */
    private class Hint(private val density: Float, var text: String) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.INK }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.ON_INK; textSize = 14 * density; isFakeBoldText = true }
        private val pill = RectF()
        override fun draw(canvas: Canvas) {
            val w = label.measureText(text) + 32 * density; val h = 36 * density
            val left = bounds.exactCenterX() - w / 2; val top = bounds.top + 16 * density
            pill.set(left, top, left + w, top + h)
            fill.setShadowLayer(6 * density, 0f, 2 * density, Color.argb(70, 0, 0, 0))
            canvas.drawRoundRect(pill, h / 2, h / 2, fill)
            canvas.drawText(text, left + 16 * density, pill.centerY() - (label.descent() + label.ascent()) / 2, label)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
