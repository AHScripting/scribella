package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * F055 (P076): a see-through ruler in screen pixels, a guide only (never saved with the notebook).
 * Fingers on it move and turn it; a pen stroke starting on it or beside a long edge is projected onto that edge.
 */
class Ruler(private val density: Float, private val pxPerMm: Float) {
    var cx = 0f; private set
    var cy = 0f; private set
    /** Angle of the long edges, kept in [0, 180). */
    var degrees = 0f; private set
    var length = 0f; private set
    val thickness = 76f * density
    private val margin = 24f * density

    fun place(x: Float, y: Float, angle: Float) { cx = x; cy = y; degrees = normal(angle) }
    /** Long enough to cross the whole view at any angle and position on it. */
    fun fit(width: Int, height: Int) {
        length = hypot(width.toFloat(), height.toFloat()) * 2f
        cx = cx.coerceIn(0f, width.toFloat()); cy = cy.coerceIn(0f, height.toFloat())
    }

    private val dx get() = cos(Math.toRadians(degrees.toDouble())).toFloat()
    private val dy get() = sin(Math.toRadians(degrees.toDouble())).toFloat()
    private fun along(x: Float, y: Float) = (x - cx) * dx + (y - cy) * dy
    private fun across(x: Float, y: Float) = -(x - cx) * dy + (y - cy) * dx
    fun contains(x: Float, y: Float) = abs(along(x, y)) <= length / 2 && abs(across(x, y)) <= thickness / 2

    /**
     * The projection that keeps a stroke on the nearer long edge, or null when the pen starts away from the ruler.
     * [offset] (screen px, normally half the ink width) keeps the ink beside the edge instead of under it.
     */
    fun edgeProjection(x: Float, y: Float, offset: Float, reach: Float = margin): Matrix? {
        if (abs(along(x, y)) > length / 2) return null
        val v = across(x, y)
        if (abs(v) > thickness / 2 + reach) return null
        val side = if (v < 0) -1f else 1f
        val shift = side * (thickness / 2 + offset)
        // A point on the edge line, then x' = a + d (d · (x - a)).
        val ax = cx - dy * shift; val ay = cy + dx * shift
        val dot = ax * dx + ay * dy
        return Matrix().apply { setValues(floatArrayOf(dx * dx, dx * dy, ax - dot * dx, dx * dy, dy * dy, ay - dot * dy, 0f, 0f, 1f)) }
    }

    // ---- Finger gestures: one finger moves, two fingers move and turn. ----
    private var owner = false
    private var startCx = 0f; private var startCy = 0f; private var startDegrees = 0f
    private var anchorX = 0f; private var anchorY = 0f; private var anchorAngle = 0f
    private var anchorIds = emptyList<Int>()
    val moving get() = owner

    /** Returns true while the ruler owns this finger gesture. A finger touching elsewhere starts no ruler gesture. */
    fun touch(event: MotionEvent, fingers: List<Int>): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                owner = fingers.size == 1 && contains(event.getX(0), event.getY(0))
                if (owner) anchor(event, fingers)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> if (owner) {
                anchor(event, fingers.filter { !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && it == event.actionIndex) })
            }
            MotionEvent.ACTION_MOVE -> if (owner) follow(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (owner) { owner = false; return true }
        }
        return owner
    }
    private fun anchor(event: MotionEvent, fingers: List<Int>) {
        startCx = cx; startCy = cy; startDegrees = degrees
        anchorIds = fingers.take(2).map { event.getPointerId(it) }
        val points = anchorIds.map { id -> event.findPointerIndex(id).let { event.getX(it) to event.getY(it) } }
        anchorX = points.map { it.first }.average().toFloat(); anchorY = points.map { it.second }.average().toFloat()
        anchorAngle = if (points.size == 2) angle(points[0], points[1]) else 0f
    }
    private fun follow(event: MotionEvent) {
        val points = anchorIds.mapNotNull { id -> event.findPointerIndex(id).takeIf { it >= 0 }?.let { event.getX(it) to event.getY(it) } }
        if (points.size != anchorIds.size || points.isEmpty()) return
        val mx = points.map { it.first }.average().toFloat(); val my = points.map { it.second }.average().toFloat()
        val turn = if (points.size == 2) angle(points[0], points[1]) - anchorAngle else 0f
        cx = startCx + mx - anchorX; cy = startCy + my - anchorY
        // Turning happens about the fingers' midpoint, so the ruler stays under the hand.
        if (turn != 0f) {
            val r = Math.toRadians(turn.toDouble()); val ox = cx - mx; val oy = cy - my
            cx = mx + (ox * cos(r) - oy * sin(r)).toFloat(); cy = my + (ox * sin(r) + oy * cos(r)).toFloat()
        }
        degrees = snapped(startDegrees + turn)
    }
    private fun angle(a: Pair<Float, Float>, b: Pair<Float, Float>) = Math.toDegrees(atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())).toFloat()

    // ---- Drawing ----
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bounds = RectF()

    /** P201 step 5: graphite glass by night (set from the canvas's context). */
    var night = false
    private val strong = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false)
    private val semibold = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)

    /** P201 step 5: as on the Selection board, frosted glass with ink ticks and a round glass angle disc. */
    /** Zero retains the original screen ruler; positive ratios use document centimetres. */
    var scaleRatio = 0
        set(value) { require(value in RulerScale.ratios); field=value }
    fun draw(canvas: Canvas, pageScale: Float = 1f) {
        val state = canvas.save()
        canvas.translate(cx, cy); canvas.rotate(degrees)
        val half = length / 2; val t = thickness / 2
        bounds.set(-half, -t, half, t)
        // A soft shadow lifts the frosted glass off white paper.
        body.color = 0x14000000; bounds.offset(0f, 2f * density); canvas.drawRect(bounds, body); bounds.offset(0f, -2f * density)
        body.color = if (night) (if (owner) 0xa62a2f3a.toInt() else 0x8c2a2f3a.toInt()) else if (owner) 0xccffffff.toInt() else 0xb3ffffff.toInt()
        canvas.drawRect(bounds, body)
        line.color = if (night) Glass.EDGE_NIGHT else 0x331d2433; line.strokeWidth = density; canvas.drawRect(bounds, line)
        // Ticks at real size from both long edges; numbers every centimetre from the centre.
        line.strokeWidth = density * .75f
        line.color = if (night) 0x99f2f4f8.toInt() else 0x8c1d2433.toInt()
        label.color = if (night) 0xccf2f4f8.toInt() else 0xb31d2433.toInt(); label.textSize = 10f * density; label.typeface = semibold
        if(scaleRatio > 0) {
            val ticks=RulerScale.ticks(pageScale,scaleRatio,density)
            val count=(half/ticks.minorPixels).toInt().coerceAtMost(4096)
            for(i in -count..count) {
                val x=i*ticks.minorPixels;val major=i%5==0
                val tick=(if(major)14f else 6f)*density
                canvas.drawLine(x,-t,x,-t+tick,line);canvas.drawLine(x,t,x,t-tick,line)
                if(major && i!=0) canvas.drawText(RulerScale.label(kotlin.math.abs(i/5)*ticks.majorCm),x,-t+tick+label.textSize,label)
            }
        } else {
        val count = (half / pxPerMm).toInt()
        for (mm in -count..count) {
            val x = mm * pxPerMm
            val tick = when { mm % 10 == 0 -> 14f; mm % 5 == 0 -> 10f; else -> 6f } * density
            canvas.drawLine(x, -t, x, -t + tick, line); canvas.drawLine(x, t, x, t - tick, line)
            if (mm % 10 == 0 && mm != 0) canvas.drawText("${abs(mm) / 10}", x, -t + tick + label.textSize, label)
        }
        }
        // Angle readout: a 64 dp glass disc on the ruler's centre, the angle over "snap 15°", kept upright.
        canvas.rotate(-degrees)
        val r = 32f * density
        body.color = 0x1f000000; canvas.drawCircle(0f, 2f * density, r + 2f * density, body)
        body.color = if (night) Glass.PANE_NIGHT else Glass.PANE; canvas.drawCircle(0f, 0f, r, body)
        line.color = if (night) Glass.EDGE_NIGHT else Glass.EDGE; line.strokeWidth = density; canvas.drawCircle(0f, 0f, r, line)
        label.color = Glass.text(night); label.textSize = 19f * density; label.typeface = strong
        canvas.drawText("${readout}°", 0f, 3f * density, label)
        label.color = Glass.secondary(night); label.textSize = 10f * density; label.typeface = semibold
        canvas.drawText(if(scaleRatio>0) "1:$scaleRatio · cm" else "snap 15°", 0f, 17f * density, label)
        canvas.restoreToCount(state)
    }
    /** Whole degrees as a protractor reads them, 0–90 either way from horizontal. */
    val readout get() = degrees.roundToInt().let { if (it > 90) 180 - it else it } % 180

    companion object {
        fun normal(angle: Float) = ((angle % 180f) + 180f) % 180f
        /** Snaps to every 15° within 2°. */
        fun snapped(angle: Float): Float {
            val a = normal(angle); val nearest = (a / 15f).roundToInt() * 15f
            return if (abs(a - nearest) <= 2f) normal(nearest) else a
        }
    }
}
