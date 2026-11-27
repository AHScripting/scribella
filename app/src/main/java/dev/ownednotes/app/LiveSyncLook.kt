package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.app.SyncPanels.P
import dev.ownednotes.core.LiveSyncClient
import kotlin.math.roundToInt

/**
 * P731 (design canvas board 22, 27 Nov 2026): the parts of the Instant sync sheet and its set-up guide, in board 21's
 * Look A. [Card] is this device ⇄ your other devices with a bolt between them; [pill] the board's 40 dp buttons;
 * [Steps] the guide's numbered steps on a rail, with [terminal] and [browser] pictures of what the computer shows.
 */
internal object LiveSyncLook {
    /** The server's address as people read it: no https://, no slash at the end. */
    fun host(server: String?) = server.orEmpty().substringAfter("://").trimEnd('/')

    /** The card's look, headline and the line under it, for [status] (with [pending] changes waiting). */
    fun describe(status: LiveSyncClient.Status): Triple<Card.Look, String, String> {
        val waiting = if (status.pending > 0) "${status.pending} ${if (status.pending == 1) "change" else "changes"} waiting to send" else null
        val host = host(status.server).takeIf { it.isNotEmpty() }
        return when (status.state) {
            LiveSyncClient.State.OFF -> Triple(Card.Look.OFF, "", "")
            LiveSyncClient.State.PAUSED -> Triple(Card.Look.ON, "On · connects while Scribella is open", listOfNotNull(host, waiting).joinToString(" · "))
            LiveSyncClient.State.CONNECTING -> Triple(Card.Look.CONNECTING, "Connecting…", listOfNotNull(host, waiting).joinToString(" · "))
            LiveSyncClient.State.LIVE -> Triple(Card.Look.LIVE, "Connected", listOfNotNull(host, waiting).joinToString(" · "))
            LiveSyncClient.State.WAITING -> Triple(Card.Look.WAITING, "Not connected · trying again",
                listOfNotNull(status.detail?.trim()?.removeSuffix(".")?.takeIf { it.isNotEmpty() }, waiting).joinToString(" · "))
            LiveSyncClient.State.DENIED -> Triple(Card.Look.DENIED, "Refused by the server", "It belongs to another sync code. Turn off, then join with the right code.")
        }
    }

    /**
     * The board's 40 dp button (36 dp when [height] says so): PRIMARY, TINTED or PLAIN, 15 sp, with an optional icon
     * before the label. Disabled, it fades to 40%.
     */
    fun pill(context: Context, label: String, style: Kit.Button, path: String? = null, height: Float = 40f, onClick: () -> Unit): TextView =
        Pill(context).apply {
            val night = context.night
            text = label; textSize = 15f; typeface = Tokens.SANS_MEDIUM; includeFontPadding = false; gravity = Gravity.CENTER
            maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END
            minHeight = context.dpi(height); minimumHeight = context.dpi(height)
            setPadding(context.dpi(if (path != null) 42f else 16f), context.dpi(4f), context.dpi(16f), context.dpi(4f))
            val (fill, ink) = when (style) {
                Kit.Button.PRIMARY -> Glass.accent(night) to android.graphics.Color.WHITE
                Kit.Button.TINTED -> Kit.accentFill(night) to Kit.accentText(night)
                // P733 (board 26): Delete page's red button.
                Kit.Button.DANGER -> (if (night) 0xffe5534b.toInt() else 0xffd93a3a.toInt()) to android.graphics.Color.WHITE
                else -> android.graphics.Color.TRANSPARENT to (if (night) Kit.accentText(true) else Glass.accent(false))
            }
            setTextColor(ink)
            val radius = context.dp(height / 2)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(if (style == Kit.Button.PRIMARY || style == Kit.Button.DANGER) 0x40ffffff else 0x1f1d2433), rounded(fill, radius), rounded(-1, radius))
            if (style == Kit.Button.PRIMARY || style == Kit.Button.DANGER) { elevation = context.dp(3f); outlineSpotShadowColor = fill; outlineAmbientShadowColor = fill }
            icon = path?.let { Kit.Icon(it, ink, context.dpi(20f)) }
            isClickable = true; isFocusable = true
            setOnClickListener { if (isEnabled) runCatching(onClick) }
        }

    /**
     * The pill's TextView. Its [icon] is drawn 6 dp before the first line of words, wherever the words sit, so on a
     * wide button the icon and words stay together in the middle (a compound drawable would sit at the edge).
     */
    private class Pill(context: Context) : TextView(context) {
        var icon: android.graphics.drawable.Drawable? = null
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .4f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val i = icon ?: return
            val l = layout ?: return
            val s = context.dpi(20f)
            val x = (compoundPaddingLeft + l.getLineLeft(0) - scrollX).roundToInt() - context.dpi(26f)
            val y = (height - s) / 2
            i.setBounds(x, y, x + s, y + s); i.draw(canvas)
        }
    }

    /**
     * One of the two choices before instant sync is on: "First device" (accent ring) or "Join a device". A disc, a title
     * over a short line, then [content] in order.
     */
    fun choice(context: Context, path: String, color: Int, title: String, detail: String, first: Boolean, content: List<Pair<View, Float>>) =
        LinearLayout(context).apply {
            val night = context.night
            orientation = LinearLayout.VERTICAL
            background = if (first) rounded(Explain.alpha(Glass.accent(night), if (night) .12f else .05f), context.dp(Glass.CARD),
                Explain.alpha(Glass.accent(night), .55f), context.dpi(1.5f)) else SyncPanels.cardBackground(context)
            setPadding(context.dpi(16f), context.dpi(14f), context.dpi(16f), context.dpi(12f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(SyncPanels.disc(context, path, color, 40f, 22f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(SyncPanels.label(context, title, 16f, true, if (first) Kit.accentText(night) else Glass.text(night)).apply { isAccessibilityHeading = true })
                    addView(SyncPanels.label(context, detail, 13f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 4 else 2 },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(5f) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(14f) })
            })
            content.forEach { (view, gap) ->
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(gap) })
            }
        }

    /** "How to make a server" under Create sync code: a book, the words and a chevron, all in the accent. */
    fun linkRow(context: Context, path: String, title: String, onClick: () -> Unit) = LinearLayout(context).apply {
        val night = context.night
        val ink = if (night) Kit.accentText(true) else Glass.accent(false)
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(36f)
        background = ripple(night, context.dp(12f))
        addView(icon(context, path, ink, 20f))
        addView(SyncPanels.label(context, title, 15f, true, ink).apply { maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(8f) })
        addView(icon(context, Kit.Paths.CHEVRON, ink, 18f))
        isClickable = true; isFocusable = true; contentDescription = title
        setOnClickListener { runCatching(onClick) }
    }

    /** "There: Instant sync, then Show QR code": a quiet hint box with a device icon. */
    fun hint(context: Context, path: String, words: String) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(44f); setPadding(context.dpi(12f), context.dpi(6f), context.dpi(12f), context.dpi(6f))
        background = rounded(if (night) 0x0fffffff else 0x0a1d2433, context.dp(12f))
        addView(icon(context, path, SyncPanels.slate(night), 22f))
        addView(SyncPanels.label(context, words, 13.5f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 4 else 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(8f) })
    }

    fun icon(context: Context, path: String, color: Int, size: Float) = View(context).apply {
        background = Kit.Icon(path, color, context.dpi(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dpi(size), context.dpi(size))
    }

    // --------------------------------------------------------------------------------------------------- The guide

    /** A small grey chip: "About 15 minutes", "On a computer", "Only once". */
    fun chip(context: Context, path: String, words: String) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(32f); setPadding(context.dpi(12f), 0, context.dpi(14f), 0)
        background = rounded(if (night) 0x1affffff else 0x0f1d2433, context.dp(16f))
        addView(icon(context, path, Glass.secondary(night), 18f))
        addView(SyncPanels.label(context, words, 13.5f, true, Glass.text(night)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(6f) })
    }

    /** Chips side by side, onto a new line when the sheet is narrow. */
    class Flow(context: Context, private val gap: Float = 8f) : ViewGroup(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec); val g = context.dpi(gap)
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += line + g; line = 0 }
                x += c.measuredWidth + g; line = maxOf(line, c.measuredHeight)
            }
            setMeasuredDimension(w, y + line)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l; val g = context.dpi(gap)
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += line + g; line = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + g; line = maxOf(line, c.measuredHeight)
            }
        }
    }

    /** A step's number: white on an accent circle, with a ring of the sheet's colour where it crosses the rail. */
    private class Number(context: Context, private val value: String) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = context.dp(14f); typeface = Typeface.create(Typeface.SANS_SERIF, 700, false) }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(38f), context.dpi(38f))
        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            paint.color = Kit.sheet(night); canvas.drawCircle(c, c, c, paint)
            paint.color = Glass.accent(night); canvas.drawCircle(c, c, context.dp(15f), paint)
            paint.color = android.graphics.Color.WHITE
            canvas.drawText(value, c, c - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    /** One step of the guide: what to do, in a picture, and an optional [extra] (a button or a picture) under it. */
    class Step(val path: String, val color: Int, val title: String, val detail: String, val extra: ((Context) -> View)? = null)

    /** The numbered steps on a rail. [indent] is where the words (and the pictures under them) start: 98 dp, 46 on a phone. */
    class Steps(context: Context, steps: List<Step>, private val indent: Float) : LinearLayout(context) {
        private val night = context.night
        private val rail = Paint().apply { color = Explain.alpha(Glass.accent(night), .25f) }
        private val numbers = mutableListOf<View>()
        init {
            orientation = VERTICAL; setWillNotDraw(false)
            val narrow = indent < 98f
            steps.forEachIndexed { i, step ->
                val header = LinearLayout(context).apply {
                    orientation = HORIZONTAL; gravity = Gravity.TOP
                    val number = Number(context, "${i + 1}").also { numbers += it }
                    addView(number, LayoutParams(context.dpi(38f), context.dpi(38f)).apply { marginStart = -context.dpi(4f) })
                    addView(SyncPanels.disc(context, step.path, step.color, 38f, 20f).apply {
                        visibility = if (narrow) View.GONE else View.VISIBLE
                    }, LayoutParams(context.dpi(38f), context.dpi(38f)).apply { marginStart = context.dpi(12f) })
                    addView(LinearLayout(context).apply {
                        orientation = VERTICAL
                        addView(SyncPanels.label(context, step.title, 15.5f, true, Glass.text(night)).apply { maxLines = Int.MAX_VALUE },
                            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(3f) })
                        addView(SyncPanels.label(context, step.detail, 13f, false, Glass.secondary(night)).apply { maxLines = Int.MAX_VALUE; setLineSpacing(0f, 1.1f) },
                            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(4f) })
                    }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(if (narrow) 12f else 14f) })
                    // A screen reader reads the step as one line, number first.
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "Step ${i + 1}: ${step.title}. ${step.detail}"
                    for (k in 0 until childCount) getChildAt(k).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = context.dpi(if (steps[i - 1].extra != null) 16f else 24f) })
                step.extra?.let { make ->
                    addView(make(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dpi(12f); marginStart = context.dpi(indent)
                    })
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            if (numbers.size < 2) return
            fun centre(v: View) = (v.parent as View).top + v.top + v.height / 2f
            val x = context.dp(15f)
            canvas.drawRect(x - context.dp(1f), centre(numbers.first()), x + context.dp(1f), centre(numbers.last()), rail)
        }
    }

    /** At most 380 dp wide, as on the board; narrower on a phone. */
    private open class Capped(context: Context) : LinearLayout(context) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(
            MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(w), context.dpi(380f)), MeasureSpec.getMode(w).takeIf { it != MeasureSpec.UNSPECIFIED } ?: MeasureSpec.AT_MOST), h)
    }

    /** What the terminal shows: a dark window with three dots, and [lines] in a fixed-width font. */
    fun terminal(context: Context, lines: List<Pair<String, Int>>): LinearLayout = Capped(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xff1e2230.toInt(), context.dp(10f)); clipToOutline = true
        addView(object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0xff2b3140.toInt())
                listOf(0xffff5f57.toInt() to 10f, 0xfffebc2e.toInt() to 21f, 0xff28c840.toInt() to 32f).forEach { (c, x) ->
                    p.color = c; canvas.drawCircle(context.dp(x + 3.5f), context.dp(11.5f), context.dp(3.5f), p)
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(22f)))
        val text = lines.joinToString("\n") { it.first }
        lines.forEachIndexed { i, (words, colour) ->
            addView(TextView(context).apply {
                this.text = words; textSize = 12.5f; typeface = Typeface.MONOSPACE; includeFontPadding = false; setTextColor(colour)
                setLineSpacing(context.dp(4.5f), 1f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dpi(12f); marginEnd = context.dpi(12f); topMargin = context.dpi(if (i == 0) 8f else 4.5f)
            })
        }
        setPadding(0, 0, 0, context.dpi(8f))
        contentDescription = "Terminal: $text"
    }

    /** What the browser shows at the server's address. */
    fun browser(context: Context): LinearLayout = Capped(context).apply {
        val night = context.night
        orientation = LinearLayout.VERTICAL
        background = rounded(Kit.cardFill(night), context.dp(10f), if (night) Glass.RULE_NIGHT else Glass.RULE, maxOf(1, context.dpi(1f))); clipToOutline = true
        addView(FrameLayout(context).apply {
            setBackgroundColor(if (night) 0xff2c313b.toInt() else 0xffeef1f5.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(Kit.cardFill(night), context.dp(9f)); setPadding(context.dpi(6f), 0, context.dpi(8f), 0)
                addView(icon(context, P.LOCK, SyncPanels.slate(night), 14f))
                addView(SyncPanels.label(context, "scribella-sync.yourname.workers.dev", 12f, false, Glass.secondary(night)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(4f) })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dpi(18f)).apply { setMargins(context.dpi(10f), context.dpi(6f), context.dpi(10f), context.dpi(6f)) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(30f)))
        addView(SyncPanels.label(context, "Scribella sync server is running", 13.5f, true, Glass.text(night)).apply {
            gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = context.dpi(8f)
            setCompoundDrawablesRelative(Explain.Badge(SyncPanels.green(night), P.CHECK, context.dpi(20f)).apply { setBounds(0, 0, context.dpi(20f), context.dpi(20f)) }, null, null, null)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpi(14f); topMargin = context.dpi(12f); bottomMargin = context.dpi(12f)
        })
        contentDescription = "Browser at your server's address: Scribella sync server is running"
    }

    // ---------------------------------------------------------------------------------------------------- The card

    /**
     * Board 22's card: this device ⇄ your other devices, joined by a line with a bolt in the middle. Off, the line is
     * dashed and the card invites; on, the state sits under the two labels: connected (green), connecting (amber, a
     * piece moves along the line), not connected (amber, dashed, with Try again), refused (red), or on (accent).
     */
    internal class Card(context: Context, private val off: Boolean, device: String) : ViewGroup(context) {
        enum class Look { OFF, ON, CONNECTING, LIVE, WAITING, DENIED }
        private val night = context.night
        var look = Look.OFF; private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f)
        private val deviceDisc = Explain.Disc(Glass.accent(night), P.TABLET, .5f, fill = .10f)
        private val otherDisc = Explain.Disc(SyncPanels.green(night), P.DEVICES, .5f, dashed = off)
        private var middle = Kit.Icon(P.BOLT, Glass.accent(night), 0)
        val info = Explain.Button(context, "How instant sync works")
        var onRetry: () -> Unit = {}
        val retry: LinearLayout? = if (off) null else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f1d2433),
                rounded(if (night) 0x1affffff else 0x0f1d2433, context.dp(16f)), null)
            setPadding(context.dpi(12f), 0, context.dpi(16f), 0)
            addView(icon(context, P.SYNC, Glass.text(night), 18f))
            addView(SyncPanels.label(context, "Try again", 14f, true, Glass.text(night)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(6f) })
            isClickable = true; isFocusable = true; contentDescription = "Try again"
            setOnClickListener { runCatching { onRetry() } }
        }
        private val left = if (off) null else centred(SyncPanels.label(context, "This $device", 14f, true, Glass.text(night)))
        private val right = if (off) null else centred(SyncPanels.label(context, "Your other devices", 14f, true, Glass.text(night)))
        val status = centred(if (off) SyncPanels.label(context, "Your changes on every device within seconds", 18f, true, Glass.text(night))
            else SyncPanels.label(context, "", 17f, true, Glass.text(night)).apply {
                compoundDrawablePadding = context.dpi(8f); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        val detail = centred(if (off) SyncPanels.label(context, "Encrypted here first: your server cannot read them.", 14f, false, Glass.secondary(night))
            else SyncPanels.label(context, "", 13f, false, Glass.secondary(night)))

        init {
            setWillNotDraw(false)
            retry?.let { addView(it) }
            addView(info)
            left?.let { addView(it) }; right?.let { addView(it) }
            addView(status); addView(detail)
            show(if (off) Look.OFF else Look.ON, status.text.toString(), detail.text.toString())
        }
        private fun centred(v: TextView) = v.apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END }

        fun show(look: Look, text: String, under: String) {
            val moved = text != status.text.toString() || under != detail.text.toString() || look != this.look
            this.look = look
            val c = colour()
            if (!off) {
                status.text = text
                status.setTextColor(if (look == Look.CONNECTING) c else Glass.text(night))
                val badge = when (look) { Look.LIVE -> P.CHECK; Look.WAITING -> P.CLOCK; Look.DENIED -> P.ALERT; Look.ON -> P.BOLT; else -> null }
                status.setCompoundDrawablesRelative(badge?.let { Explain.Badge(c, it, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) } }, null, null, null)
                detail.text = under
                detail.visibility = if (under.isEmpty()) View.GONE else View.VISIBLE
                retry?.visibility = if (look == Look.WAITING) View.VISIBLE else View.GONE
            }
            middle = Kit.Icon(if (look == Look.DENIED) P.ALERT else P.BOLT, c, 0)
            if (moved) requestLayout()
            invalidate()
        }

        private fun colour() = when (look) {
            Look.OFF, Look.ON -> Glass.accent(night)
            Look.LIVE -> SyncPanels.green(night)
            Look.CONNECTING, Look.WAITING -> SyncPanels.amber(night)
            Look.DENIED -> SyncPanels.fault(night)
        }

        private fun centres(w: Int): Pair<Int, Int> {
            val o = minOf(dpi(if (off) 150f else 170f), w / 2 - dpi(56f)).coerceAtLeast(dpi(44f))
            return (w / 2 - o) to (w / 2 + o)
        }
        private val discTop get() = dpi(if (off) 30f else 34f)
        private fun statusTop() = if (off) dpi(122f) else dpi(114f) + maxOf(left!!.measuredHeight, right!!.measuredHeight) + dpi(14f)
        private fun detailTop() = statusTop() + status.measuredHeight + dpi(if (off) 7f else 6f)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun most(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.AT_MOST)
            fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)
            retry?.measure(free, exactly(dpi(32f)))
            info.measure(exactly(dpi(44f)), exactly(dpi(44f)))
            val (lx, rx) = centres(w)
            fun room(x: Int) = minOf(dpi(200f), 2 * minOf(x, w - x) - dpi(8f))
            left?.measure(most(room(lx)), free); right?.measure(most(room(rx)), free)
            status.measure(most(w - dpi(32f)), free); detail.measure(most(w - dpi(32f)), free)
            val end = if (detail.visibility == View.GONE) statusTop() + status.measuredHeight + dpi(16f)
                else detailTop() + detail.measuredHeight + dpi(if (off) 20f else 10f)
            setMeasuredDimension(w, end)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            retry?.let { it.layout(dpi(16f), dpi(16f), dpi(16f) + it.measuredWidth, dpi(16f) + it.measuredHeight) }
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
            val c = colour()
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
            deviceDisc.setBounds(lx - d, cy - d, lx + d, cy + d); deviceDisc.draw(canvas)
            otherDisc.setBounds(rx - d, cy - d, rx + d, cy + d); otherDisc.draw(canvas)

            // The line: dashed while off and while it cannot connect.
            val x1 = lx + dp(44f); val x2 = rx - dp(44f)
            paint.strokeWidth = dp(2.5f); paint.strokeCap = Paint.Cap.BUTT
            paint.color = if (look == Look.OFF) (if (night) 0x73ffffff else 0x731d2433) else Explain.alpha(c, .45f)
            paint.pathEffect = if (look == Look.OFF || look == Look.WAITING) dash else null
            canvas.drawLine(x1, cy.toFloat(), x2, cy.toFloat(), paint)
            paint.pathEffect = null

            if (look == Look.CONNECTING && x2 > x1) {
                // Connecting: a short piece moves along the line towards the other devices.
                val length = x2 - x1
                val phase = if (Motion.enabled) (SystemClock.uptimeMillis() % 1600L) / 1600f else .45f
                val piece = length * .3f
                val start = x1 - piece + (length + piece) * phase
                val a = maxOf(x1, start); val b = minOf(x2, start + piece)
                paint.style = Paint.Style.FILL; paint.color = c
                if (b > a) canvas.drawRoundRect(a, cy - dp(2f), b, cy + dp(2f), dp(2f), dp(2f), paint)
                if (Motion.enabled) postInvalidateOnAnimation()
            }
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
