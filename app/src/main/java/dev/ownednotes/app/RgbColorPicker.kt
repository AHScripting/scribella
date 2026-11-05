package dev.ownednotes.app

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*

/** Visual first. Exact values remain available for precision and existing text-editor draft compatibility. */
class RgbColorPicker(context: Context, initial: Long) : LinearLayout(context) {
    val hexInput = EditText(context).apply { setSingleLine(); hint = "#RRGGBB"; contentDescription = "Hex color" }
    val channels = List(3) { SeekBar(context).apply { max = 255; contentDescription = listOf("Red channel", "Green channel", "Blue channel")[it] } }
    private val preview = TextView(context).apply { gravity = Gravity.CENTER; minHeight = dpi(40f); text = "Selected color" }
    private val exact = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private var changing = false
    private var selected = initial
    private val hsv = FloatArray(3)
    val colorArea = ColorArea(context) { saturation, brightness ->
        hsv[1] = saturation; hsv[2] = brightness; setColor(Color.HSVToColor(hsv).toLong() and 0xffffffffL)
    }
    val hueStrip = HueStrip(context) { hue ->
        hsv[0] = hue
        // Selecting a hue from black/white should produce a visible color immediately.
        if (hsv[1] == 0f) hsv[1] = 1f
        if (hsv[2] == 0f) hsv[2] = 1f
        setColor(Color.HSVToColor(hsv).toLong() and 0xffffffffL)
    }
    init {
        orientation = VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(12f))
        addView(preview)
        val colors = listOf(0xff182636L to "Black", 0xffffffffL to "White", 0xffd32f2fL to "Red", 0xffff9800L to "Orange", 0xffffeb3bL to "Yellow", 0xff388e3cL to "Green", 0xff1976d2L to "Blue", 0xff7b1fa2L to "Purple")
        addView(LinearLayout(context).apply {
            colors.forEach { (color, name) -> addView(object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                init { contentDescription = "$name color"; tooltipText = name; isClickable = true; isFocusable = true; setOnClickListener { setColor(color) } }
                override fun onDraw(canvas: Canvas) {
                    val r = minOf(width, height) * .32f; paint.style = Paint.Style.FILL; paint.color = color.toInt(); canvas.drawCircle(width/2f,height/2f,r,paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (selected == color) 3f else 1f); paint.color = if (selected == color) Tokens.AMBER else Tokens.RULE
                    canvas.drawCircle(width/2f,height/2f,r,paint)
                }
            }, LayoutParams(0, dpi(48f), 1f)) }
        })
        addView(ToolControls.caption(context, "Choose a hue, then touch the shade you want."))
        addView(hueStrip, LayoutParams(-1, dpi(48f)))
        addView(colorArea, LayoutParams(-1, dpi(160f)))
        addView(Button(context).apply {
            text = "Precise color values"; contentDescription = "Show precise color values"
            setOnClickListener {
                exact.visibility = if (exact.visibility == VISIBLE) GONE else VISIBLE
                if (exact.visibility == GONE) setColor(selected)
                text = if (exact.visibility == VISIBLE) "Hide precise color values" else "Precise color values"
            }
        })
        exact.addView(hexInput)
        channels.forEach { bar ->
            exact.addView(TextView(context).apply { text = bar.contentDescription }); exact.addView(bar, LayoutParams(-1, dpi(44f)))
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!changing) setColor(0xff000000L or (channels[0].progress.toLong() shl 16) or (channels[1].progress.toLong() shl 8) or channels[2].progress.toLong())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        addView(exact)
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!changing) runCatching { color() }.onSuccess { setColor(it, false) }.onFailure { exact.visibility=VISIBLE;hexInput.error="Choose a color visually or correct the precise value" } }
            override fun afterTextChanged(s: Editable?) {}
        })
        setColor(initial)
    }
    fun color(): Long {
        val text = hexInput.text.toString().trim().removePrefix("#")
        require(text.matches(Regex("[a-fA-F0-9]{6}"))) { "Enter a valid precise color, or choose a color visually" }
        return 0xff000000L or text.toLong(16)
    }
    private fun setColor(value: Long, updateText: Boolean = true) {
        changing = true
        try {
            val previousHue=hsv[0]; selected = value; Color.colorToHSV(value.toInt(), hsv)
            if(hsv[1]==0f || hsv[2]==0f)hsv[0]=previousHue
            colorArea.show(hsv[0], hsv[1], hsv[2]); hueStrip.show(hsv[0])
            channels.forEachIndexed { i, bar -> bar.progress = ((value shr ((2-i)*8)) and 255).toInt() }
            if (updateText) hexInput.setText("%06X".format(value and 0xffffff))
            preview.setBackgroundColor(value.toInt()); preview.setTextColor(if (Color.luminance(value.toInt()) > .4f) Color.BLACK else Color.WHITE)
            preview.contentDescription = "Selected color: ${ColorNames.of(value)}"; hexInput.error = null
            colorArea.stateDescription = ColorNames.of(value); hueStrip.stateDescription = "${hsv[0].toInt()} degrees"
            fun redraw(v: View) {v.invalidate();if(v is android.view.ViewGroup)for(i in 0 until v.childCount)redraw(v.getChildAt(i))};redraw(this)
        } finally { changing = false }
    }
    class ColorArea(context: Context, private val changed: (Float, Float) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private var hue = 0f; private var saturation = 0f; private var value = 1f
        init { contentDescription = "Color shade"; isClickable = true; isFocusable = true }
        fun show(h: Float, s: Float, v: Float) { hue=h;saturation=s;value=v;invalidate() }
        override fun onDraw(c: Canvas) {
            if (width == 0 || height == 0) return
            paint.shader = LinearGradient(0f,0f,width.toFloat(),0f,Color.WHITE,Color.HSVToColor(floatArrayOf(hue,1f,1f)),Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader = LinearGradient(0f,0f,0f,height.toFloat(),Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.shader=null
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(3f);paint.color=Color.WHITE;c.drawCircle(saturation*width,(1-value)*height,dp(7f),paint)
            paint.strokeWidth=dp(1f);paint.color=Color.BLACK;c.drawCircle(saturation*width,(1-value)*height,dp(8f),paint);paint.style=Paint.Style.FILL
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(e.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
                parent?.requestDisallowInterceptTouchEvent(true);changed((e.x/width.coerceAtLeast(1)).coerceIn(0f,1f),(1-e.y/height.coerceAtLeast(1)).coerceIn(0f,1f));if(e.actionMasked==MotionEvent.ACTION_UP)performClick();return true
            };return e.actionMasked==MotionEvent.ACTION_CANCEL
        }
        override fun onKeyDown(code:Int,event:android.view.KeyEvent):Boolean {
            when(code){android.view.KeyEvent.KEYCODE_DPAD_LEFT->changed((saturation-.02f).coerceAtLeast(0f),value)
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT->changed((saturation+.02f).coerceAtMost(1f),value)
                android.view.KeyEvent.KEYCODE_DPAD_UP->changed(saturation,(value+.02f).coerceAtMost(1f))
                android.view.KeyEvent.KEYCODE_DPAD_DOWN->changed(saturation,(value-.02f).coerceAtLeast(0f))
                else->return super.onKeyDown(code,event)};return true
        }
        override fun performClick():Boolean {super.performClick();return true}
    }
    class HueStrip(context: Context, private val changed: (Float) -> Unit) : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var hue=0f
        init {contentDescription="Color hue";isClickable=true;isFocusable=true}
        fun show(value:Float){hue=value;invalidate()}
        override fun onDraw(c:Canvas){
            if(width==0)return
            paint.shader=LinearGradient(0f,0f,width.toFloat(),0f,intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null,Shader.TileMode.CLAMP)
            c.drawRect(0f,dp(10f),width.toFloat(),height-dp(10f),paint);paint.shader=null
            paint.color=Tokens.INK;paint.strokeWidth=dp(3f);c.drawLine(hue/360f*width,dp(5f),hue/360f*width,height-dp(5f),paint)
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(e.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {parent?.requestDisallowInterceptTouchEvent(true);changed((e.x/width.coerceAtLeast(1)).coerceIn(0f,.99999f)*360f);if(e.actionMasked==MotionEvent.ACTION_UP)performClick();return true};return e.actionMasked==MotionEvent.ACTION_CANCEL
        }
        override fun onKeyDown(code:Int,event:android.view.KeyEvent):Boolean {
            if(code==android.view.KeyEvent.KEYCODE_DPAD_LEFT || code==android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                changed((hue+(if(code==android.view.KeyEvent.KEYCODE_DPAD_LEFT)-3f else 3f)+360f)%360f);return true
            };return super.onKeyDown(code,event)
        }
        override fun performClick():Boolean {super.performClick();return true}
    }
}
