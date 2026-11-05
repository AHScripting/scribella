package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** F090: bounded, screen-only traces. Never references the repository or an export renderer. */
internal class LaserPointer(context: Context, private val bounds: () -> RectF?) : View(context) {
    private data class Mark(val x: Float, val y: Float, val time: Long, val start: Boolean)
    private val marks = ArrayDeque<Mark>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Glass.ACCENT; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private var pointer = -1
    private var last: Mark? = null
    internal val contact get() = pointer >= 0
    internal val traceCount get() = marks.size
    internal var enabled = false
        set(value) { field = value; clear() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
    fun clear() { marks.clear(); pointer = -1; last = null; invalidate() }
    /** P568: in finger writing one finger points as a pen does; two fingers still move the page. */
    internal var fingerDraws = false
    /** Consumes pen streams including eraser/button variants; never turns them into writing. */
    fun accept(event: MotionEvent, allowed: Boolean): Boolean {
        val pens = (0 until event.pointerCount).filter {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER ||
                (fingerDraws && event.pointerCount == 1 && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER)
        }
        if (!enabled) return false
        if (!allowed || event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0 || event.pointerCount != 1) {
            clear(); return pens.isNotEmpty() || event.actionMasked == MotionEvent.ACTION_CANCEL
        }
        if (pens.isEmpty()) return false
        val i = pens.single(); val action = when(event.actionMasked) {
            211 -> MotionEvent.ACTION_DOWN; 212 -> MotionEvent.ACTION_UP; 213 -> MotionEvent.ACTION_MOVE; 214 -> MotionEvent.ACTION_CANCEL
            else -> event.actionMasked
        }
        val rect = bounds()
        if (action == MotionEvent.ACTION_CANCEL) { clear(); return true }
        if (action == MotionEvent.ACTION_DOWN) {
            if (rect?.contains(event.getX(i), event.getY(i)) != true) { clear(); return true }
            pointer = event.getPointerId(i); last = null
        }
        if (pointer != event.getPointerId(i)) return true
        fun add(x: Float, y: Float) {
            if (rect?.contains(x, y) != true) { last = null; return }
            val m = Mark(x,y,SystemClock.uptimeMillis(),last == null)
            marks.addLast(m); while(marks.size > MAX_MARKS) marks.removeFirst()
            last = m
        }
        if(action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            for(h in 0 until event.historySize) add(event.getHistoricalX(i,h),event.getHistoricalY(i,h))
            add(event.getX(i),event.getY(i))
        }
        if(action == MotionEvent.ACTION_UP) { pointer = -1; last = null }
        invalidate(); return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        while(marks.isNotEmpty() && now - marks.first().time >= FADE_MS) marks.removeFirst()
        val rect = bounds() ?: return
        val save = canvas.save(); canvas.clipRect(rect)
        var previous: Mark? = null
        for(m in marks) {
            paint.alpha = (255f * (1f - (now-m.time).toFloat()/FADE_MS)).toInt().coerceIn(0,255)
            val p = previous
            if(!m.start && p != null) canvas.drawLine(p.x,p.y,m.x,m.y,paint)
            else canvas.drawPoint(m.x,m.y,paint)
            previous=m
        }
        if(contact) last?.let { paint.alpha=255;canvas.drawCircle(it.x,it.y,4f*resources.displayMetrics.density,paint) }
        canvas.restoreToCount(save)
        // A held stationary dot is static. Once the trail expires, no perpetual animation loop remains.
        if(marks.isNotEmpty()) postInvalidateOnAnimation()
    }
    companion object { const val FADE_MS = 800L; const val MAX_MARKS = 256 }
}
