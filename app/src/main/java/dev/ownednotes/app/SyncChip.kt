package dev.ownednotes.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * P721: the Library's sync chip, under the notebook count. It always says how sync stands ("Synced 5 minutes ago", "Sync
 * needs attention", "Waiting for Wi-Fi"). While a sync runs its arrows turn and it says what the sync is doing ("Checking
 * 4 of 19", "Syncing German · 2 of 3") with a line along its foot for how far it has come. Just finished, it says "Synced
 * just now" in the accent for a minute. A tap opens the Library sync sheet with the details and Sync now.
 */
internal class SyncChip(context: Context, private val night: Boolean, onTap: () -> Unit) : LinearLayout(context) {
    internal val text = TextView(context)
    private val icon = View(context)
    private var shownIcon = ""
    private var spin: ObjectAnimator? = null
    /** How far the running sync has come, 0..1, or below 0 when it cannot say. */
    internal var fraction = -1f; private set
    internal var running = false; private set
    private var finishedAt = 0L
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Glass.ACCENT_NIGHT else Glass.ACCENT }
    private val clip = Path(); private val box = RectF()

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setWillNotDraw(false)
        setPadding(dpi(10f), 0, dpi(14f), 0)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33ffffff else 0x1f1d2433),
            rounded(if (night) 0x14ffffff else Glass.THIN, dp(16f)), rounded(-1, dp(16f)))
        text.apply { textSize = 13f; typeface = Kit.Type.CAPTION.typeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addView(icon, LayoutParams(dpi(16f), dpi(16f)).apply { marginEnd = dpi(7f) })
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        isClickable = true; isFocusable = true; setOnClickListener { onTap() }
    }

    /** Shows [status] (no sync running) or, while one runs, [progress] (what it is doing now). True while one runs. */
    fun show(status: String, progress: String?, now: Long = System.currentTimeMillis()): Boolean {
        val wasRunning = running
        running = progress != null
        val secondary = Glass.secondary(night); val accent = if (night) Glass.ACCENT_NIGHT else Glass.ACCENT
        if (running) {
            val (said, part) = describe(progress!!)
            fraction = part ?: -1f
            text.text = said; text.setTextColor(accent); setIcon(MenuIcons.SYNC, accent); turn(true)
            contentDescription = "Library sync: $said. Opens Library sync"
            if (!wasRunning) announceForAccessibility("Syncing")
        } else {
            fraction = -1f; turn(false)
            if (wasRunning) { finishedAt = now; announceForAccessibility(status.takeUnless { it.startsWith("Synced") } ?: "Sync finished") }
            val fresh = status.startsWith("Synced") && now - finishedAt < JUST_NOW
            val trouble = status.contains("attention")
            val said = if (fresh) "Synced just now" else status
            val colour = when { trouble -> if (night) 0xffff8a80.toInt() else Tokens.FAULT; fresh -> accent; else -> secondary }
            text.text = said; text.setTextColor(colour)
            setIcon(when { trouble -> WARNING; status.startsWith("Synced") -> CHECK; status.contains("paused") -> PAUSED; else -> MenuIcons.SYNC }, colour)
            contentDescription = "Library sync: $said. Opens Library sync"
        }
        invalidate()
        return running
    }
    private fun setIcon(path: String, colour: Int) {
        val key = "$path|$colour"
        if (key == shownIcon) return
        shownIcon = key
        icon.background = LayerDrawable(arrayOf<Drawable>(Kit.Icon(path, colour, dpi(16f))))
    }
    private fun turn(on: Boolean) {
        if (on == (spin != null)) return
        if (!on) { spin?.cancel(); spin = null; icon.rotation = 0f; return }
        if (!ValueAnimator.areAnimatorsEnabled()) return
        spin = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); turn(false) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || fraction < 0f) return
        // The line along the foot, inside the chip's rounded ends.
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.save(); clip.rewind(); clip.addRoundRect(box, dp(16f), dp(16f), Path.Direction.CW); canvas.clipPath(clip)
        val thick = dp(2.5f)
        bar.alpha = 255; canvas.drawRect(0f, height - thick, width * fraction.coerceIn(.04f, 1f), height.toFloat(), bar)
        canvas.restore()
    }

    companion object {
        /** How long a finished sync says "Synced just now". */
        const val JUST_NOW = 60_000L
        private const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
        private const val WARNING = "M12 4.5l8.5 15h-17zM12 10v4.5M12 17.2v.3"
        private const val PAUSED = "M9 6v12M15 6v12"
        private val STEP = Regex("^(Looking at|Working on) (\\d+) of (\\d+): (.+?)(?: · (.*))?$")
        /**
         * A sync's progress line in a few words, and how far it has come (null when it cannot say). Folder sync first looks
         * at every notebook ("Looking at 4 of 19: German · reading…"), then works on those that changed ("Working on 2 of 3:
         * German · sending…"); its other steps ("Opening the sync folder") are said as they are.
         */
        fun describe(progress: String): Pair<String, Float?> {
            val step = STEP.matchEntire(progress.trim())
            if (step != null) {
                val (kind, i, n, name) = step.destructured
                val at = i.toInt(); val of = n.toInt().coerceAtLeast(1)
                return if (kind == "Looking at") "Checking $at of $of" to (at - 1f) / of
                else "Syncing ${name.trim()} · $at of $of" to (at - 1f) / of
            }
            val said = progress.trim().ifEmpty { "Starting" }
            return "Syncing · " + said.replaceFirstChar { it.lowercaseChar() } to null
        }
    }
}
