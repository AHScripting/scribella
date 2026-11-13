package dev.ownednotes.app

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*

/**
 * Visual first. Exact values remain available for precision and existing text-editor draft compatibility.
 * P575: Glass look, by day and by night: a rounded swatch of the chosen colour with its name, ring swatches like the
 * pen palette, a pill hue track and a rounded shade field with white-ringed knobs, and the exact values in a tinted well.
 */
class RgbColorPicker(context: Context, initial: Long) : LinearLayout(context) {
    private val night = context.night
    val hexInput = EditText(context).apply {
        setSingleLine(); hint = "#RRGGBB"; contentDescription = "Hex color"; textSize = 15f; typeface = Tokens.SANS_MEDIUM
        setTextColor(Glass.text(night)); setHintTextColor(Glass.secondary(night)); fontFeatureSettings = "tnum"
        background = rounded(ToolControls.wellFill(night), dp(12f)); setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private val channelColors = intArrayOf(0xffe0453a.toInt(), 0xff2fa84f.toInt(), 0xff2f7de1.toInt())
    val channels = List(3) { i -> SeekBar(context).apply {
        max = 255; contentDescription = listOf("Red channel", "Green channel", "Blue channel")[i]
        progressTintList = android.content.res.ColorStateList.valueOf(channelColors[i])
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (night) 0x40ffffff else 0x261d2433)
        thumb = ToolControls.Knob(dpi(28f), dp(12f)); splitTrack = false; background = null
    } }
    private val preview = TextView(context).apply {
        gravity = Gravity.CENTER; minHeight = dpi(44f); textSize = 15f; typeface = Tokens.SANS_MEDIUM; setPadding(dpi(12f), 0, dpi(12f), 0)
    }
    private val exact = LinearLayout(context).apply {
        orientation = VERTICAL; visibility = GONE; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(8f))
        background = rounded(ToolControls.wellFill(night), dp(Glass.CHIP + 4f))
    }
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
        addView(preview, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(10f) })
        val colors = listOf(0xff182636L to "Black", 0xffffffffL to "White", 0xffd32f2fL to "Red", 0xffff9800L to "Orange", 0xffffeb3bL to "Yellow", 0xff388e3cL to "Green", 0xff1976d2L to "Blue", 0xff7b1fa2L to "Purple")
        addView(LinearLayout(context).apply {
            colors.forEach { (color, name) -> addView(object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                init { contentDescription = "$name color"; tooltipText = name; isClickable = true; isFocusable = true; setOnClickListener { setColor(color) } }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f; val r = minOf(dp(15f), minOf(width, height) * .32f)
                    paint.style = Paint.Style.FILL; paint.color = color.toInt()
                    paint.setShadowLayer(dp(1.5f), 0f, dp(.5f), 0x33141e37); canvas.drawCircle(cx, cy, r, paint); paint.clearShadowLayer()
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = if (night) Glass.RULE_NIGHT else Glass.RULE
                    canvas.drawCircle(cx, cy, r, paint)
                    if (selected == color) { paint.strokeWidth = dp(2.5f); paint.color = Glass.accent(night); canvas.drawCircle(cx, cy, r + dp(4f), paint) }
                }
            }, LayoutParams(0, dpi(48f), 1f)) }
        })
        addView(ToolControls.caption(context, "Choose a hue, then touch the shade you want.").apply { setPadding(0, dpi(10f), 0, dpi(6f)) })
        addView(hueStrip, LayoutParams(-1, dpi(40f)))
        addView(colorArea, LayoutParams(-1, dpi(160f)).apply { topMargin = dpi(8f) })
        lateinit var toggle: TextView
        toggle = Kit.button(context, "Precise colour values", Kit.Button.TINTED, small = true) {
            exact.visibility = if (exact.visibility == VISIBLE) GONE else VISIBLE
            if (exact.visibility == GONE) setColor(selected)
            toggle.text = if (exact.visibility == VISIBLE) "Hide precise values" else "Precise colour values"
            toggle.contentDescription = if (exact.visibility == VISIBLE) "Hide precise color values" else "Show precise color values"
        }.apply { contentDescription = "Show precise color values" }
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f); gravity = Gravity.START })
        exact.addView(ToolControls.caption(context, "Hex code"))
        exact.addView(hexInput, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        channels.forEachIndexed { index, bar ->
            exact.addView(ToolControls.caption(context, listOf("Red", "Green", "Blue")[index]).apply { setPadding(0, dpi(10f), 0, 0) })
            exact.addView(bar, LayoutParams(-1, dpi(40f)))
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!changing) setColor(0xff000000L or (channels[0].progress.toLong() shl 16) or (channels[1].progress.toLong() shl 8) or channels[2].progress.toLong())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        addView(exact, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
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
            preview.background = rounded(value.toInt(), dp(Glass.CHIP + 2f), if (night) Glass.RULE_NIGHT else Glass.RULE, dpi(1f))
            preview.setTextColor(if (Color.luminance(value.toInt()) > .4f) Glass.TEXT else Color.WHITE)
            preview.text = "%s  ·  #%06X".format(ColorNames.of(value).replaceFirstChar { it.uppercase() }, value and 0xffffff)
            preview.contentDescription = "Selected color: ${ColorNames.of(value)}"; hexInput.error = null
            colorArea.stateDescription = ColorNames.of(value); hueStrip.stateDescription = "${hsv[0].toInt()} degrees"
            fun redraw(v: View) {v.invalidate();if(v is android.view.ViewGroup)for(i in 0 until v.childCount)redraw(v.getChildAt(i))};redraw(this)
        } finally { changing = false }
    }
    class ColorArea(context: Context, private val changed: (Float, Float) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private val clip = Path(); private var hue = 0f; private var saturation = 0f; private var value = 1f
        init { contentDescription = "Color shade"; isClickable = true; isFocusable = true }
        fun show(h: Float, s: Float, v: Float) { hue=h;saturation=s;value=v;invalidate() }
        override fun onDraw(c: Canvas) {
            if (width == 0 || height == 0) return
            val r = dp(14f); val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.save(); clip.reset(); clip.addRoundRect(box, r, r, Path.Direction.CW); c.clipPath(clip)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f,0f,width.toFloat(),0f,Color.WHITE,Color.HSVToColor(floatArrayOf(hue,1f,1f)),Shader.TileMode.CLAMP)
            c.drawRect(box,paint)
            paint.shader = LinearGradient(0f,0f,0f,height.toFloat(),Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
            c.drawRect(box,paint);paint.shader=null;c.restore()
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1f);paint.color=if (context.night) Glass.RULE_NIGHT else Glass.RULE;c.drawRoundRect(box,r,r,paint)
            val x = saturation*width; val y = (1-value)*height
            paint.style=Paint.Style.FILL;paint.color=Color.HSVToColor(floatArrayOf(hue,saturation,value))
            paint.setShadowLayer(dp(3f),0f,dp(1f),0x55141e37);c.drawCircle(x,y,dp(11f),paint);paint.clearShadowLayer()
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(3f);paint.color=Color.WHITE;c.drawCircle(x,y,dp(11f),paint);paint.style=Paint.Style.FILL
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
            // A pill track inset by the knob's radius, so the knob never leaves the view at either end.
            val k = dp(13f); val top = height/2f - dp(7f); val bottom = height/2f + dp(7f)
            paint.style=Paint.Style.FILL
            paint.shader=LinearGradient(k,0f,width-k,0f,intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null,Shader.TileMode.CLAMP)
            c.drawRoundRect(k-dp(7f),top,width-k+dp(7f),bottom,dp(7f),dp(7f),paint);paint.shader=null
            val x = k + hue/360f*(width-2*k); val y = height/2f
            paint.color=Color.HSVToColor(floatArrayOf(hue,1f,1f));paint.setShadowLayer(dp(3f),0f,dp(1f),0x55141e37);c.drawCircle(x,y,k,paint);paint.clearShadowLayer()
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(3f);paint.color=Color.WHITE;c.drawCircle(x,y,k-dp(1.5f),paint);paint.style=Paint.Style.FILL
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(e.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {parent?.requestDisallowInterceptTouchEvent(true);val k=dp(13f);changed(((e.x-k)/(width-2*k).coerceAtLeast(1f)).coerceIn(0f,.99999f)*360f);if(e.actionMasked==MotionEvent.ACTION_UP)performClick();return true};return e.actionMasked==MotionEvent.ACTION_CANCEL
        }
        override fun onKeyDown(code:Int,event:android.view.KeyEvent):Boolean {
            if(code==android.view.KeyEvent.KEYCODE_DPAD_LEFT || code==android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                changed((hue+(if(code==android.view.KeyEvent.KEYCODE_DPAD_LEFT)-3f else 3f)+360f)%360f);return true
            };return super.onKeyDown(code,event)
        }
        override fun performClick():Boolean {super.performClick();return true}
    }
}
