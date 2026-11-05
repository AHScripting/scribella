package dev.ownednotes.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Finger navigation for user decision D3, as a pure state machine with no Android types.
 * Stylus samples only feed the hand lock and always map to [TouchIntent.Ignore];
 * ink (including explicit opt-in finger ink) keeps going through [PenRouter]. Coordinates are view pixels and times are milliseconds;
 * the host scales the pixel thresholds by screen density.
 */
enum class TouchTool { FINGER, STYLUS }
enum class TouchPhase { DOWN, MOVE, UP, CANCEL }

/** One pointer of a motion event. [size] is the contact's major axis in pixels; [palm] is the platform's palm/cancel flag. */
data class TouchSample(
    val id: Int,
    val tool: TouchTool,
    val phase: TouchPhase,
    val x: Float,
    val y: Float,
    val timeMs: Long,
    val size: Float = 0f,
    val palm: Boolean = false,
)

/** Pen proximity as reported by hover events and pen contacts. */
enum class PenSignal { HOVER_ENTER, HOVER_EXIT, CONTACT_DOWN, CONTACT_UP }

enum class TurnAxis { HORIZONTAL, VERTICAL }

/** NEXT is a swipe toward the left (horizontal axis) or toward the top (vertical axis). */
enum class TurnDirection { NEXT, PREVIOUS }

sealed class TouchIntent {
    object Ignore : TouchIntent() { override fun toString() = "Ignore" }
    data class Pan(val dx: Float, val dy: Float) : TouchIntent()
    /** Scale by [scale] about the focus; [dx]/[dy] is the focus drift since the last zoom when two-finger pan is on. */
    data class Zoom(val scale: Float, val focusX: Float, val focusY: Float, val dx: Float = 0f, val dy: Float = 0f) : TouchIntent()
    data class PageTurn(val direction: TurnDirection, val axis: TurnAxis) : TouchIntent()
    object TwoFingerTap : TouchIntent() { override fun toString() = "TwoFingerTap" }
    /** Undo or settle whatever this gesture's earlier Pan/Zoom intents did. */
    object Cancel : TouchIntent() { override fun toString() = "Cancel" }
}

class TouchIntentMachine(
    val oneFingerPan: Boolean = true,
    val twoFingerPan: Boolean = true,
    val pinchZoom: Boolean = true,
    val swipeToTurn: Boolean = true,
    val turnAxis: TurnAxis = TurnAxis.HORIZONTAL,
    /** Fingers stay ignored for this long after the pen leaves proximity. */
    val handLockGraceMs: Long = 600,
    /** Contacts at least this large are palms. */
    val palmContactSize: Float = 90f,
    /** Movement before a one-finger or two-finger pan starts. */
    val touchSlop: Float = 16f,
    /** Span change before a two-finger gesture becomes a zoom. */
    val pinchSlop: Float = 24f,
    val swipeMinDistance: Float = 160f,
    /** Pixels per millisecond along the turn axis, measured over [velocityWindowMs] before lift. */
    val swipeMinVelocity: Float = 0.6f,
    /** Distance along the turn axis must be at least this multiple of the cross-axis distance. */
    val swipeAxisDominance: Float = 1f,
    val velocityWindowMs: Long = 100,
    val tapMaxDurationMs: Long = 300,
    val tapMaxMovement: Float = 24f,
) {
    /** Opt-in writing suppresses one-finger navigation without losing pen proximity state. */
    var fingerWriting: Boolean = false
        set(value) { if (field != value) { abandonGesture(); field = value } }
    private class Track(val startX: Float, val startY: Float) {
        var x = startX
        var y = startY
        var travel = 0f
        val history = ArrayDeque<Triple<Long, Float, Float>>()
    }

    private enum class Mode { IDLE, PENDING, PAN, SWIPE, TWO_PENDING, TWO_ACTIVE, DONE }

    private var penHovering = false
    private var penTouching = false
    private var penLeftAt: Long? = null

    /** Non-ignored fingers currently down, in arrival order. */
    private val tracks = linkedMapOf<Int, Track>()
    /** Pointers ignored until they lift: palms and contacts that began under the hand lock. */
    private val ignored = mutableSetOf<Int>()
    private var mode = Mode.IDLE
    private var emitted = false
    private var gestureStart = 0L
    private var zooming = false
    private var tapEligible = false
    private var startSpan = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    fun isHandLocked(timeMs: Long): Boolean =
        penHovering || penTouching || penLeftAt?.let { timeMs - it < handLockGraceMs } == true

    fun onPen(signal: PenSignal, timeMs: Long): TouchIntent {
        when (signal) {
            PenSignal.HOVER_ENTER -> penHovering = true
            PenSignal.CONTACT_DOWN -> penTouching = true
            PenSignal.HOVER_EXIT -> { penHovering = false; if (!penTouching) penLeftAt = timeMs }
            PenSignal.CONTACT_UP -> { penTouching = false; if (!penHovering) penLeftAt = timeMs }
        }
        return if (penHovering || penTouching) abandonGesture() else TouchIntent.Ignore
    }

    fun onTouch(sample: TouchSample): TouchIntent {
        if (sample.tool == TouchTool.STYLUS) return onStylus(sample)
        if (sample.phase == TouchPhase.CANCEL) {
            val result = if (emitted) TouchIntent.Cancel else TouchIntent.Ignore
            tracks.clear(); ignored.clear(); resetGesture()
            return result
        }
        val id = sample.id
        var restart: TouchIntent = TouchIntent.Ignore
        if (sample.phase == TouchPhase.DOWN) {
            // A DOWN for an id that never lifted means the platform reused it; drop the old gesture and start over.
            ignored.remove(id)
            if (id in tracks) restart = abandonGesture().also { ignored.remove(id) }
        }
        if (id in ignored) {
            if (sample.phase == TouchPhase.UP) ignored.remove(id)
            return TouchIntent.Ignore
        }
        val isPalm = sample.palm || sample.size >= palmContactSize
        return when (sample.phase) {
            TouchPhase.DOWN -> { fingerDown(sample, isPalm); restart }
            TouchPhase.MOVE -> tracks[id]?.let { if (isPalm) palmDuringGesture(id) else fingerMove(it, sample) } ?: TouchIntent.Ignore
            TouchPhase.UP -> tracks[id]?.let { if (isPalm) palmDuringGesture(id).also { ignored.remove(id) } else fingerUp(id, it, sample) } ?: TouchIntent.Ignore
            TouchPhase.CANCEL -> TouchIntent.Ignore
        }
    }

    private fun onStylus(sample: TouchSample): TouchIntent = when (sample.phase) {
        TouchPhase.DOWN, TouchPhase.MOVE -> { penTouching = true; abandonGesture() }
        TouchPhase.UP, TouchPhase.CANCEL -> { penTouching = false; if (!penHovering) penLeftAt = sample.timeMs; TouchIntent.Ignore }
    }

    private fun fingerDown(sample: TouchSample, isPalm: Boolean) {
        if (isPalm || isHandLocked(sample.timeMs)) { ignored += sample.id; return }
        when (mode) {
            Mode.IDLE -> {
                tracks[sample.id] = Track(sample.x, sample.y).also { record(it, sample) }
                mode = Mode.PENDING; gestureStart = sample.timeMs
            }
            Mode.PENDING, Mode.PAN, Mode.SWIPE -> {
                tracks[sample.id] = Track(sample.x, sample.y).also { record(it, sample) }
                mode = Mode.TWO_PENDING; zooming = false
                startSpan = span(); startCx = centroidX(); startCy = centroidY()
                lastSpan = startSpan; lastCx = startCx; lastCy = startCy
            }
            // A third finger, or a new finger after a two-finger gesture lost one, is never part of the gesture.
            Mode.TWO_PENDING, Mode.TWO_ACTIVE, Mode.DONE -> ignored += sample.id
        }
    }

    private fun fingerMove(track: Track, sample: TouchSample): TouchIntent {
        val previousX = track.x
        val previousY = track.y
        record(track, sample)
        return when (mode) {
            Mode.PENDING -> {
                if (fingerWriting || track.travel <= touchSlop) return TouchIntent.Ignore
                when {
                    !fingerWriting && oneFingerPan -> { mode = Mode.PAN; emit(TouchIntent.Pan(track.x - track.startX, track.y - track.startY)) }
                    !fingerWriting && swipeToTurn -> { mode = Mode.SWIPE; TouchIntent.Ignore }
                    else -> { mode = Mode.DONE; TouchIntent.Ignore }
                }
            }
            Mode.PAN -> emit(TouchIntent.Pan(track.x - previousX, track.y - previousY))
            Mode.TWO_PENDING -> {
                val s = span(); val cx = centroidX(); val cy = centroidY()
                val startsZoom = (pinchZoom || fingerWriting) && pinched(s, cx, cy, sample.timeMs)
                val startsPan = (twoFingerPan || fingerWriting) && hypot(cx - startCx, cy - startCy) > touchSlop
                if (!startsZoom && !startsPan) return TouchIntent.Ignore
                mode = Mode.TWO_ACTIVE; zooming = startsZoom
                twoFingerStep(s, cx, cy)
            }
            Mode.TWO_ACTIVE -> {
                val s = span(); val cx = centroidX(); val cy = centroidY()
                if (!zooming && (pinchZoom || fingerWriting) && pinched(s, cx, cy, sample.timeMs)) zooming = true
                twoFingerStep(s, cx, cy)
            }
            Mode.IDLE, Mode.SWIPE, Mode.DONE -> TouchIntent.Ignore
        }
    }

    /**
     * P568: two fingers swiping together never move in step; in finger writing that alone must not read as a zoom, which
     * would rule out the page turn. Fingers are read one at a time, so the distance between them only means something once
     * both have this moment's position.
     */
    private fun pinched(s: Float, cx: Float, cy: Float, time: Long): Boolean {
        val change = abs(s - startSpan)
        if (!fingerWriting) return change > pinchSlop
        return tracks.values.all { it.history.last().first == time } && change > 2 * pinchSlop && change > .5f * hypot(cx - startCx, cy - startCy)
    }

    private fun twoFingerStep(s: Float, cx: Float, cy: Float): TouchIntent {
        val dx = cx - lastCx
        val dy = cy - lastCy
        val scale = if (lastSpan > 0f && s > 0f) s / lastSpan else 1f
        lastSpan = s; lastCx = cx; lastCy = cy
        return when {
            zooming -> emit(if (twoFingerPan || fingerWriting) TouchIntent.Zoom(scale, cx, cy, dx, dy) else TouchIntent.Zoom(scale, cx, cy))
            twoFingerPan || fingerWriting -> emit(TouchIntent.Pan(dx, dy))
            else -> TouchIntent.Ignore
        }
    }

    private fun fingerUp(id: Int, track: Track, sample: TouchSample): TouchIntent {
        record(track, sample)
        tracks.remove(id)
        return when (mode) {
            Mode.PENDING, Mode.PAN, Mode.SWIPE -> swipeResult(track, sample.timeMs).also { resetGesture() }
            Mode.TWO_PENDING, Mode.TWO_ACTIVE -> {
                // The remaining finger may still end a two-finger tap, but it never pans on its own.
                tapEligible = mode == Mode.TWO_PENDING && !emitted && track.travel <= tapMaxMovement
                // P568: where one finger writes, the page turns with a two-finger swipe.
                val turn = if (fingerWriting && !zooming) swipeResult(track, sample.timeMs, twoFingers = true) else TouchIntent.Ignore
                mode = Mode.DONE
                turn
            }
            Mode.DONE -> {
                if (tracks.isNotEmpty()) return TouchIntent.Ignore
                val tap = tapEligible && track.travel <= tapMaxMovement && sample.timeMs - gestureStart <= tapMaxDurationMs
                resetGesture()
                if (tap) TouchIntent.TwoFingerTap else TouchIntent.Ignore
            }
            Mode.IDLE -> TouchIntent.Ignore
        }
    }


    private fun swipeResult(track: Track, upTime: Long, twoFingers: Boolean = false): TouchIntent {
        if (!twoFingers && (!swipeToTurn || fingerWriting)) return TouchIntent.Ignore
        val horizontal = turnAxis == TurnAxis.HORIZONTAL
        val along = if (horizontal) track.x - track.startX else track.y - track.startY
        val cross = if (horizontal) track.y - track.startY else track.x - track.startX
        if (abs(along) < swipeMinDistance || abs(along) < abs(cross) * swipeAxisDominance) return TouchIntent.Ignore
        val (oldTime, oldX, oldY) = track.history.first()
        val travelled = if (horizontal) track.x - oldX else track.y - oldY
        val velocity = travelled / maxOf(upTime - oldTime, 1L)
        // The recent motion must go the same way as the whole swipe, fast enough.
        if (velocity * along <= 0f || abs(velocity) < swipeMinVelocity) return TouchIntent.Ignore
        val direction = if (along < 0f) TurnDirection.NEXT else TurnDirection.PREVIOUS
        return emit(TouchIntent.PageTurn(direction, turnAxis))
    }

    /** A palm flag on a pointer that was part of the gesture means the hand is on the glass: drop the whole gesture. */
    private fun palmDuringGesture(id: Int): TouchIntent {
        val result = abandonGesture()
        ignored += id
        return result
    }

    /** Every finger down now stays ignored until it lifts. */
    private fun abandonGesture(): TouchIntent {
        if (tracks.isEmpty()) return TouchIntent.Ignore
        val result = if (emitted) TouchIntent.Cancel else TouchIntent.Ignore
        ignored += tracks.keys
        tracks.clear()
        resetGesture()
        return result
    }

    private fun resetGesture() {
        mode = Mode.IDLE; emitted = false; zooming = false; tapEligible = false
    }

    private fun emit(intent: TouchIntent): TouchIntent { emitted = true; return intent }

    private fun record(track: Track, sample: TouchSample) {
        track.x = sample.x
        track.y = sample.y
        track.travel = maxOf(track.travel, hypot(sample.x - track.startX, sample.y - track.startY))
        track.history.addLast(Triple(sample.timeMs, sample.x, sample.y))
        // Keep the newest sample at or before the window start so the velocity spans the whole window.
        while (track.history.size > 2 && track.history[1].first <= sample.timeMs - velocityWindowMs) track.history.removeFirst()
    }

    private fun span(): Float {
        val (a, b) = tracks.values.toList()
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun centroidX() = tracks.values.sumOf { it.x.toDouble() }.toFloat() / tracks.size
    private fun centroidY() = tracks.values.sumOf { it.y.toDouble() }.toFloat() / tracks.size
}
