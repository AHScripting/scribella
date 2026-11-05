package dev.ownednotes.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.roundToInt
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLayout
import dev.ownednotes.core.Point

/** Local pen-centerline preview only: never loads a page background, image, attachment or PDF. */
internal fun handwritingLayoutPicker(context: Context, input: HandwritingInput, initial: HandwritingLayout,
    apply: (HandwritingLayout) -> Boolean): Kit.Sheet {
    var chosen = initial
    val ink = HandwritingLayoutInk(context, HandwritingLayout().oriented(input.strokes))
    val artwork = (0..3).map { turns -> ink.art(turns*90f,ink.bounds(turns*90f)) }
    var currentRotation = Triple(initial.quarterTurns, initial.tiltDegrees, initial.slantDegrees)
    var extent = ink.bounds(initial.quarterTurns*90f+initial.tiltDegrees, initial.slantDegrees)
    var currentWidth = extent.width().coerceAtLeast(1f)
    var currentHeight = extent.height().coerceAtLeast(1f)
    var previewArt = ink.art(initial.quarterTurns*90f+initial.tiltDegrees,extent,initial.slantDegrees) {
        chosen.columnFraction.takeIf { chosen.columns == 2 }
    }
    val error = Kit.text(context, "", Kit.Type.CAPTION, secondary = true).apply {
        contentDescription = "Handwriting layout status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val preview = Kit.picture(context, Pictogram { canvas, box, _ ->
        previewArt.draw(canvas, box, false)
    }).apply { contentDescription = "Recognition ink preview" }
    var gapButton: View? = null
    fun refresh() {
        val rotation = Triple(chosen.quarterTurns, chosen.tiltDegrees, chosen.slantDegrees)
        if (rotation != currentRotation) {
            val angle = chosen.quarterTurns*90f+chosen.tiltDegrees
            extent = ink.bounds(angle,chosen.slantDegrees)
            currentWidth = extent.width().coerceAtLeast(1f)
            currentHeight = extent.height().coerceAtLeast(1f)
            previewArt = ink.art(angle,extent,chosen.slantDegrees) { chosen.columnFraction.takeIf { chosen.columns == 2 } }
            currentRotation = rotation
        }
        error.text = ""
        gapButton?.visibility = if (chosen.columns == 2) View.VISIBLE else View.GONE
        preview.stateDescription = "Fine rotation ${chosen.tiltDegrees.roundToInt()} degrees. Letter slant ${chosen.slantDegrees.roundToInt()} degrees. " +
            if (chosen.columns == 2) "Column divider ${(chosen.columnFraction * 100).toInt()} percent. Drag into the gap between columns." else "One column"
        preview.invalidate()
    }
    fun divider(next: Float) { chosen = chosen.copy(columnFraction = next.coerceIn(.05f,.95f)); refresh() }
    // Kit picture is the existing preview surface. Only its recognition-specific interaction changes.
    preview.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    preview.isFocusable = true
    preview.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host,info)
            if (chosen.columns == 2) {
                info.className = android.widget.SeekBar::class.java.name
                info.isScrollable = true
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,5f,95f,chosen.columnFraction*100)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
        }
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (chosen.columns == 2) {
                val next = when(action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> chosen.columnFraction + .025f
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> chosen.columnFraction - .025f
                    android.R.id.accessibilityActionSetProgress -> args?.takeIf { it.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) }
                        ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.div(100f)
                    else -> null
                }
                if (next != null && next.isFinite()) { divider(next); return true }
            }
            return super.performAccessibilityAction(host,action,args)
        }
    }
    var dragging = false
    var interrupted = false
    var dragStart = chosen.columnFraction
    fun previewScale() = minOf((preview.width-context.dp(16f)).coerceAtLeast(1f)/currentWidth,
        (preview.height-context.dp(16f)).coerceAtLeast(1f)/currentHeight)
    fun updateTouch(x: Float) {
        val right = currentWidth
        val scale = previewScale()
        divider((x-(preview.width-right*scale)/2f)/(right*scale))
    }
    preview.setOnClickListener { /* drag release; accessibility adjustments use the range actions */ }
    preview.setOnTouchListener { _, event ->
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (chosen.columns != 2) false else {
                    val right=currentWidth
                    val scale=previewScale()
                    val x=(preview.width-right*scale)/2f+right*scale*chosen.columnFraction
                    dragging=kotlin.math.abs(event.x-x)<=context.dp(24f)
                    interrupted=false; dragStart=chosen.columnFraction
                    if(dragging) preview.parent.requestDisallowInterceptTouchEvent(true)
                    dragging
                }
            }
            MotionEvent.ACTION_MOVE -> { if(dragging && !interrupted) updateTouch(event.x); dragging }
            MotionEvent.ACTION_POINTER_DOWN -> { if(dragging) { interrupted=true; divider(dragStart) }; dragging }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed=dragging
                if(dragging) {
                    if(event.actionMasked==MotionEvent.ACTION_CANCEL) divider(dragStart)
                    else if(!interrupted) { updateTouch(event.x); preview.performClick() }
                    preview.parent.requestDisallowInterceptTouchEvent(false)
                }
                dragging=false; consumed
            }
            else -> dragging
        }
    }
    refresh()
    val sheet = Kit.Sheet(context, "Writing layout", "Apply") {
        runCatching { chosen.prepare(input.strokes, false); apply(chosen) }
            .onFailure { error.text = it.message ?: "Choose a different layout." }.getOrDefault(false)
    }
    sheet.add(Kit.text(context, "Turn the handwriting upright in this preview. Only recognition changes; your original ink stays in place.", secondary = true))
    sheet.add(LinearLayout(context).apply {
        addView(preview, LinearLayout.LayoutParams(-1, context.dpi(160f)))
    })
    sheet.add(Kit.section(context, "Rotation"))
    val labels = listOf("As written", "Turn right", "Upside down", "Turn left")
    val tiles = (0..3).map { turns -> Kit.Tile(context,
        // These full-point previews do not change with the slider. Cache their native rendering,
        // bounded to each thumbnail view; Android owns layer lifetime when the sheet detaches.
        Kit.picture(context, artwork[turns]).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) },
        labels[turns], initial.quarterTurns == turns) {
        chosen = chosen.copy(quarterTurns = turns); refresh()
    } }
    Kit.oneOf(tiles)
    // Two rows retain readable touch targets on a narrow window.
    tiles.chunked(2).forEach { row -> sheet.add(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        row.forEach { addView(it, LinearLayout.LayoutParams(0, context.dpi(112f), 1f)) }
    }, gap = 4f) }
    lateinit var angleKnob: SeekBar
    val fineRotation = Kit.slider(context, "Fine rotation", -45f, 45f, initial.tiltDegrees,
        format = { "${it.roundToInt()}°" }, round = { it.roundToInt().toFloat() }) { degrees ->
        chosen = chosen.copy(tiltDegrees = degrees)
        angleKnob.stateDescription = "${degrees.roundToInt()} degrees"
        refresh()
    }
    angleKnob = fineRotation.getChildAt(1) as SeekBar
    angleKnob.stateDescription = "${initial.tiltDegrees.roundToInt()} degrees"
    // The kit slider uses 1000 internal steps; expose meaningful degrees to assistive input.
    angleKnob.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                -45f, 45f, chosen.tiltDegrees)
        }
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val degrees = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> chosen.tiltDegrees + 1f
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> chosen.tiltDegrees - 1f
                android.R.id.accessibilityActionSetProgress -> args?.takeIf { it.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) }
                    ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                else -> return super.performAccessibilityAction(host, action, args)
            }
            if (degrees == null || !degrees.isFinite()) return false
            val native = ((degrees.coerceIn(-45f,45f) + 45f) / 90f * angleKnob.max).roundToInt()
            return super.performAccessibilityAction(host, android.R.id.accessibilityActionSetProgress,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, native.toFloat()) })
        }
    }
    sheet.add(fineRotation)
    sheet.add(Kit.text(context, "Straighten tilted lines in the preview before recognition. This does not move your original ink.", Kit.Type.CAPTION, secondary = true))
    lateinit var slantKnob: SeekBar
    val letterSlant = Kit.slider(context, "Letter slant", -30f, 30f, initial.slantDegrees,
        format = { "${it.roundToInt()}°" }, round = { it.roundToInt().toFloat() }) { degrees ->
        chosen = chosen.copy(slantDegrees = degrees)
        slantKnob.stateDescription = "${degrees.roundToInt()} degrees"
        refresh()
    }
    slantKnob = letterSlant.getChildAt(1) as SeekBar
    slantKnob.stateDescription = "${initial.slantDegrees.roundToInt()} degrees"
    // The kit slider uses 1000 internal steps; expose meaningful degrees to assistive input.
    slantKnob.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                -30f, 30f, chosen.slantDegrees)
        }
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val degrees = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> chosen.slantDegrees + 1f
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> chosen.slantDegrees - 1f
                android.R.id.accessibilityActionSetProgress -> args?.takeIf { it.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) }
                    ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                else -> return super.performAccessibilityAction(host, action, args)
            }
            if (degrees == null || !degrees.isFinite()) return false
            val native = ((degrees.coerceIn(-30f,30f) + 30f) / 60f * slantKnob.max).roundToInt()
            return super.performAccessibilityAction(host, android.R.id.accessibilityActionSetProgress,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, native.toFloat()) })
        }
    }
    sheet.add(letterSlant)
    sheet.add(Kit.text(context, "Adjust leaning letters in the ink preview. Zero keeps their original slant; recognition only changes after Apply.", Kit.Type.CAPTION, secondary = true))
    sheet.add(Kit.section(context, "Reading order"))
    sheet.add(Kit.segmented(context, listOf("One column", "Two columns"), initial.columns - 1,
        listOf(columnArt(context, 1), columnArt(context, 2))) { index ->
        chosen = chosen.copy(columns = index + 1); refresh()
    }.apply { contentDescription = "Writing pad columns" })
    gapButton = Kit.button(context, "Find column gap", Kit.Button.TINTED) {
        if (chosen.columns == 2) {
            runCatching { chosen.columnGap(input.strokes) }.onSuccess { fraction ->
                if (fraction == null) error.text = "No clear column gap. Drag the divider or choose one column."
                else { divider(fraction); error.text = "Divider placed in the widest clear gap. Check the preview before applying." }
            }.onFailure { error.text = it.message ?: "Cannot find a column gap." }
        }
    }.apply {
        contentDescription = "Find column gap"
        visibility = if (chosen.columns == 2) View.VISIBLE else View.GONE
    }
    sheet.add(gapButton!!)
    sheet.add(Kit.text(context, "Two columns reads down the left side, then the right. Drag the blue divider into the gap between columns. Writing must not cross it.", Kit.Type.CAPTION, secondary = true))
    sheet.add(error)
    return sheet.show()
}

/** One immutable path set for every tile and angle. Recognition still uses the untouched input. */
internal class HandwritingLayoutInk(private val context: Context, private val strokes: List<List<Point>>) {
    val paths = strokes.map { stroke -> Path().apply {
        moveTo(stroke.first().x, stroke.first().y)
        stroke.drop(1).forEach { lineTo(it.x, it.y) }
        if (stroke.size == 1) lineTo(stroke.first().x + .01f, stroke.first().y)
    } }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    fun bounds(angle: Float, slantDegrees: Float = 0f): RectF {
        val shear=kotlin.math.tan(Math.toRadians(slantDegrees.toDouble()))
        val (cosine,sine) = when ((angle%360f+360f)%360f) {
            0f -> 1.0 to 0.0
            90f -> 0.0 to 1.0
            180f -> -1.0 to 0.0
            270f -> 0.0 to -1.0
            else -> Math.toRadians(angle.toDouble()).let { kotlin.math.cos(it) to kotlin.math.sin(it) }
        }
        var left=Float.POSITIVE_INFINITY; var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY; var bottom=Float.NEGATIVE_INFINITY
        for (stroke in strokes) for (p in stroke) {
            val rotatedX=(p.x*cosine-p.y*sine).toFloat(); val y=(p.x*sine+p.y*cosine).toFloat()
            val x=(rotatedX+shear*y).toFloat()
            left=minOf(left,x); top=minOf(top,y); right=maxOf(right,x); bottom=maxOf(bottom,y)
        }
        return RectF(left,top,right,bottom)
    }
    fun art(angle: Float, bounds: RectF, slantDegrees: Float = 0f, divider: () -> Float? = { null }): Pictogram {
        // Transform centerlines once when the layout changes, not the preview pen's width.
        val matrix=android.graphics.Matrix().apply {
            setRotate(angle)
            postSkew(kotlin.math.tan(Math.toRadians(slantDegrees.toDouble())).toFloat(),0f)
            postTranslate(-bounds.left,-bounds.top)
        }
        val adjusted=paths.map { Path(it).apply { transform(matrix) } }
        return Pictogram { canvas, box, _ ->
        val right=bounds.width().coerceAtLeast(1f); val bottom=bounds.height().coerceAtLeast(1f)
        val scale = minOf((box.width() - context.dp(16f)).coerceAtLeast(1f) / right,
            (box.height() - context.dp(16f)).coerceAtLeast(1f) / bottom)
        canvas.save(); canvas.translate(box.centerX() - right * scale / 2f, box.centerY() - bottom * scale / 2f); canvas.scale(scale, scale)
        pen.color = Glass.text(context.night); pen.strokeWidth = context.dp(1.8f) / scale
        adjusted.forEach { canvas.drawPath(it, pen) }
        divider()?.let { fraction -> pen.color = Glass.accent(context.night); pen.strokeWidth = context.dp(1f) / scale; canvas.drawLine(right * fraction, 0f, right * fraction, bottom, pen) }
        canvas.restore()
        }
    }
}

private fun columnArt(context: Context, columns: Int) = Pictogram { canvas, box, _ ->
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.text(context.night); strokeWidth = context.dp(1.5f); strokeCap = Paint.Cap.ROUND }
    val left = box.left + box.width() * .12f
    val columnWidth = box.width() * .76f / columns
    for (column in 0 until columns) for (line in 0..3) {
        val x = left + column * columnWidth
        val y = box.top + box.height() * (.2f + line * .2f)
        canvas.drawLine(x, y, x + columnWidth * .75f, y, paint)
    }
}
