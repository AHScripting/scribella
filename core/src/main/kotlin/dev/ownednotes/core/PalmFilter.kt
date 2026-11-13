package dev.ownednotes.core

import kotlin.math.hypot

/**
 * P573: palm rest for rubber-tip pens and fingertips. Android reports a rubber-tip pen as a finger, so with finger
 * writing on, the hand resting on the glass counts as a second finger: before this, it ended the stroke and moved or
 * zoomed the page.
 *
 * The filter sits in front of all other touch handling and decides which finger contacts the rest of the canvas sees:
 * - A contact as broad as a palm ([palmSize] across) is never seen.
 * - A finger that lands later, while the writing finger is moving, is a palm, and is never seen.
 * - A finger that lands while the first is still resting where it landed, after [pinchWindowMs], means the first was
 *   the palm: it is cancelled (its mark is dropped) and the new contact writes instead.
 * - Two fingers landing together, the second within [pinchWindowMs] of the first, are a two-finger gesture and pass.
 * - A pen, an eraser or any other tool is never filtered, and gestures that start with filtering off pass unchanged.
 * A contact that is left out stays left out until it lifts.
 */
class PalmFilter(private val palmSize: Float, private val slop: Float, private val pinchWindowMs: Long = PINCH_WINDOW_MS) {
    /** One pointer of an event: its id, whether it is a finger, where it is and how broad its contact is. */
    data class Contact(val id: Int, val finger: Boolean, val x: Float, val y: Float, val size: Float)
    enum class Act { DOWN, MOVE, UP, CANCEL }
    /** One event the canvas should see: [act] of [actionId], carrying only the [keep] pointers (ids, in event order). */
    data class Emit(val act: Act, val actionId: Int, val keep: List<Int>)

    private var engaged = false
    private var together = false
    private val seen = linkedSetOf<Int>()
    private val hidden = mutableSetOf<Int>()
    private var writer: Int? = null
    private var writerX = 0f; private var writerY = 0f; private var writerTime = 0L; private var writerMoved = false

    /** True while this gesture is being filtered (finger writing was on when it started). */
    val active get() = engaged

    /**
     * The events to pass on for one input event; null means pass the original event unchanged. [enabled] is read when
     * a gesture starts: finger writing on and the page open for writing.
     */
    fun filter(act: Act, actionId: Int, contacts: List<Contact>, time: Long, enabled: Boolean, firstOfGesture: Boolean = act == Act.DOWN && contacts.size == 1): List<Emit>? {
        if (firstOfGesture) reset(enabled && contacts.all { it.finger })
        if (!engaged) return null
        val out = mutableListOf<Emit>()
        fun kept() = contacts.map { it.id }.filter { it in seen }
        when (act) {
            Act.DOWN -> {
                val contact = contacts.firstOrNull { it.id == actionId } ?: return out
                when {
                    !contact.finger -> { seen += contact.id; out += Emit(Act.DOWN, contact.id, kept()) }
                    contact.size >= palmSize -> hidden += contact.id
                    together -> { seen += contact.id; out += Emit(Act.DOWN, contact.id, kept()) }
                    seen.isEmpty() -> { seen += contact.id; startWriter(contact, time); out += Emit(Act.DOWN, contact.id, kept()) }
                    time - writerTime <= pinchWindowMs -> {
                        // Two fingers together: a move or zoom, not a palm.
                        together = true; seen += contact.id; out += Emit(Act.DOWN, contact.id, kept())
                    }
                    !writerMoved -> {
                        // The first contact rested where it landed: it was the palm, and this one is the tip.
                        out += Emit(Act.CANCEL, -1, kept())
                        hidden += seen; seen.clear()
                        seen += contact.id; startWriter(contact, time); out += Emit(Act.DOWN, contact.id, kept())
                    }
                    else -> hidden += contact.id
                }
            }
            Act.MOVE -> {
                contacts.firstOrNull { it.id == writer }?.let { if (hypot(it.x - writerX, it.y - writerY) > slop) writerMoved = true }
                // A writing contact that spreads out to palm size was the palm all along.
                val grown = contacts.filter { it.finger && it.id in seen && it.size >= palmSize && !together }
                if (grown.isNotEmpty()) {
                    out += Emit(Act.CANCEL, -1, kept())
                    hidden += seen; seen.clear(); writer = null
                } else if (seen.isNotEmpty()) out += Emit(Act.MOVE, -1, kept())
            }
            Act.UP -> {
                if (actionId in hidden) { hidden -= actionId; return out }
                if (actionId !in seen) return out
                out += Emit(Act.UP, actionId, kept())
                seen -= actionId
                if (writer == actionId) writer = null
            }
            Act.CANCEL -> {
                if (seen.isNotEmpty()) out += Emit(Act.CANCEL, -1, kept())
                reset(false)
            }
        }
        return out
    }

    private fun startWriter(contact: Contact, time: Long) {
        writer = contact.id; writerX = contact.x; writerY = contact.y; writerTime = time; writerMoved = false
    }

    private fun reset(engage: Boolean) {
        engaged = engage; together = false; seen.clear(); hidden.clear(); writer = null; writerMoved = false
    }

    companion object {
        /** How close together two fingers must land to count as a two-finger move or zoom rather than a palm. */
        const val PINCH_WINDOW_MS = 150L
    }
}
