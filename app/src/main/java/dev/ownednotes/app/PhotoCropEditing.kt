package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import dev.ownednotes.core.*

data class PhotoCropTarget(val notebook:Notebook,val pageId:String,val imageId:String) {
    val image get()=notebook.pages.firstOrNull { it.id==pageId }?.images?.firstOrNull { it.id==imageId }
}
internal data class PhotoCropState(val target:PhotoCropTarget,var draft:List<String>?=null)
internal data class PhotoCropHost(val state:PhotoCropState,val dialog:AlertDialog,val form:PhotoCropForm)

fun NotebookRepository.capturePhotoCropTarget(imageId:String):PhotoCropTarget? {
    if(readOnly || !canChangeNotebook)return null
    val note=session?.notebook ?: return null;val page=currentPage ?: return null
    if(page.images.none { it.id==imageId })return null
    return PhotoCropTarget(note,page.id,imageId)
}
fun NotebookRepository.applyPhotoCrop(target:PhotoCropTarget,crop:ImageCrop):Boolean {
    if(readOnly || !canChangeNotebook || session?.notebook !== target.notebook || selectedPageId!=target.pageId || target.image==null)return false
    edit { it.cropImageObject(target.pageId,target.imageId,crop) };return true
}

internal class PhotoCropForm(context:Context,val image:ImageObject,asset:ImageAsset):LinearLayout(context) {
    private val initial=listOf(image.crop.left,image.crop.top,image.crop.right,image.crop.bottom).map { (it*100).toString() }
    val fields=initial.mapIndexed { index,value -> EditText(context).apply {
        contentDescription=listOf("Crop left percent","Crop top percent","Crop right percent","Crop bottom percent")[index]
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setSingleLine();setText(value)
    } }
    private val preview=object:View(context) {
        private val shown=RectF()
        private var dragging=-1
        init { contentDescription="Photo crop preview"; isClickable=true; isFocusable=true }
        override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
            val value=runCatching {cropValue()}.getOrDefault(image.crop)
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if(shown.isEmpty)return false
                    dragging=handleAt(value,event.x,event.y)
                    if(dragging<0)return false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> if(dragging>=0) {
                    val x=(event.x-shown.left)/shown.width();val y=(event.y-shown.top)/shown.height()
                    val gap=minOf(.001f,(value.right-value.left)/2,(value.bottom-value.top)/2)
                    // Corners 0..3 run clockwise from top left; edges 4..7 are left, top, right, bottom.
                    if(dragging==0 || dragging==3 || dragging==4)fields[0].setText((x.coerceIn(0f,value.right-gap)*100).toString())
                    if(dragging==1 || dragging==2 || dragging==6)fields[2].setText((x.coerceIn(value.left+gap,1f)*100).toString())
                    if(dragging==0 || dragging==1 || dragging==5)fields[1].setText((y.coerceIn(0f,value.bottom-gap)*100).toString())
                    if(dragging==2 || dragging==3 || dragging==7)fields[3].setText((y.coerceIn(value.top+gap,1f)*100).toString())
                }
                android.view.MotionEvent.ACTION_UP -> {dragging=-1;performClick()}
                android.view.MotionEvent.ACTION_CANCEL -> dragging=-1
            };invalidate();return true
        }
        override fun performClick():Boolean {super.performClick();return true}
        // A corner wins over an edge, so small crops can still be taken by a corner.
        private fun handleAt(value:ImageCrop,x:Float,y:Float):Int {
            val left=shown.left+value.left*shown.width();val right=shown.left+value.right*shown.width()
            val top=shown.top+value.top*shown.height();val bottom=shown.top+value.bottom*shown.height()
            val corners=listOf(left to top,right to top,right to bottom,left to bottom).map { (cx,cy)->kotlin.math.hypot(cx-x,cy-y) }
            val corner=corners.indices.minByOrNull { corners[it] }!!
            if(corners[corner]<=dp(40f))return corner
            val reach=dp(24f);val across=y in top-reach..bottom+reach;val along=x in left-reach..right+reach
            return listOfNotNull(if(across)4 to kotlin.math.abs(x-left) else null,if(along)5 to kotlin.math.abs(y-top) else null,
                if(across)6 to kotlin.math.abs(x-right) else null,if(along)7 to kotlin.math.abs(y-bottom) else null)
                .filter { it.second<=reach }.minByOrNull { it.second }?.first ?: -1
        }
        var bitmap:Bitmap?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas);val bitmap=bitmap ?: return
            if(width<2 || height<2)return
            val swap=image.orientation in 5..8
            val p=runCatching { ImagePlacement.fit(if(swap)asset.height else asset.width,if(swap)asset.width else asset.height,width.toFloat(),height.toFloat()) }.getOrNull() ?: return
            shown.set(p.x,p.y,p.x+p.width,p.y+p.height)
            PhotoRendering.draw(canvas,image.copy(placement=p,crop=ImageCrop.FULL),bitmap,paint)
            val crop=runCatching { cropValue() }.getOrDefault(image.crop)
            val tl=p.pagePoint(crop.left,crop.top);val br=p.pagePoint(crop.right,crop.bottom)
            val mask=Path().apply { fillType=Path.FillType.EVEN_ODD;addRect(p.x,p.y,p.x+p.width,p.y+p.height,Path.Direction.CW);addRect(tl.x,tl.y,br.x,br.y,Path.Direction.CW) }
            paint.color=0x88000000.toInt();paint.style=Paint.Style.FILL;canvas.drawPath(mask,paint)
            val accent=Glass.accent(context.night)
            paint.color=accent;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f)
            canvas.drawRect(tl.x,tl.y,br.x,br.y,paint)
            fun knob(shape:RectF,radius:Float) {
                paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawRoundRect(shape,radius,radius,paint)
                paint.style=Paint.Style.STROKE;paint.color=accent;canvas.drawRoundRect(shape,radius,radius,paint)
            }
            // Edge grips only where they clear the corner handles.
            val midX=(tl.x+br.x)/2;val midY=(tl.y+br.y)/2;val long=dp(16f);val thin=dp(4f);val room=dp(64f)
            if(br.x-tl.x>=room)listOf(tl.y,br.y).forEach { y->knob(RectF(midX-long,y-thin,midX+long,y+thin),thin) }
            if(br.y-tl.y>=room)listOf(tl.x,br.x).forEach { x->knob(RectF(x-thin,midY-long,x+thin,midY+long),thin) }
            val r=dp(8f);listOf(tl.x to tl.y,br.x to tl.y,br.x to br.y,tl.x to br.y).forEach { (x,y)->knob(RectF(x-r,y-r,x+r,y+r),r) }
            paint.style=Paint.Style.FILL
            // The bitmap itself must never inherit the overlay paint's alpha on the next frame.
            paint.color=Color.BLACK
        }
    }
    init {
        tag="photo-crop-form";orientation=VERTICAL;setPadding(dpi(16f),dpi(8f),dpi(16f),dpi(8f))
        addView(preview,LayoutParams(LayoutParams.MATCH_PARENT,dpi(280f)))
        addView(TextView(context).apply { text="Drag a corner or an edge to crop the photo." })
        val exact=LinearLayout(context).apply {orientation=VERTICAL;visibility=GONE}
        addView(android.widget.CheckBox(context).apply {text="Precise crop values";setOnCheckedChangeListener {_,on->exact.visibility=if(on)VISIBLE else GONE}})
        addView(exact)
        fields.forEachIndexed { i,field ->
            exact.addView(TextView(context).apply { text=listOf("Left","Top","Right","Bottom")[i] });exact.addView(field)
            field.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { preview.invalidate() }
                override fun afterTextChanged(s:Editable?) {}
            })
        }
    }
    fun snapshot()=fields.map { it.text.toString() }
    fun restore(values:List<String>) { if(values.size==4)fields.zip(values).forEach { (field,value)->field.setText(value) } }
    fun reset()=restore(listOf("0","0","100","100"))
    fun cropValue():ImageCrop {
        val raw=snapshot();if(raw==initial)return image.crop
        val v=raw.map { (it.trim().toFloatOrNull() ?: error("Enter crop edges from 0 to 100"))/100 }
        return ImageCrop(v[0],v[1],v[2],v[3])
    }
    fun load(bitmap:Bitmap) { preview.bitmap=bitmap;preview.invalidate() }
    fun release() { preview.bitmap=null }
}

fun MainActivity.photoCropDialog(imageId:String,onSaved:()->Unit={}):AlertDialog? {
    photoCropEditor?.dialog?.takeIf { it.isShowing }?.let { return it }
    if(ink.isEditing || textEditor?.dialog?.isShowing==true)return null
    val target=(application as NotesApplication).activeDocument.capturePhotoCropTarget(imageId) ?: return null
    return showPhotoCropDialog(PhotoCropState(target),onSaved)
}
private fun MainActivity.showPhotoCropDialog(state:PhotoCropState,onSaved:()->Unit):AlertDialog {
    val app=application as NotesApplication;val target=state.target;val image=target.image!!
    val asset=target.notebook.imageAssets.first { it.id==image.assetId }
    val form=PhotoCropForm(this,image,asset);state.draft?.let { form.restore(it) }
    val dialog=AlertDialog.Builder(this).setTitle("Crop photo").setView(ScrollView(this).apply { addView(form) })
        .setPositiveButton("Apply",null).setNegativeButton("Cancel",null).setNeutralButton("Reset",null).create()
    val host=PhotoCropHost(state,dialog,form);photoCropEditor=host;app.photoCropState=state
    dialog.setOnDismissListener {
        state.draft=form.snapshot();form.release()
        if(!isChangingConfigurations && app.photoCropState===state)app.photoCropState=null
        if(photoCropEditor===host)photoCropEditor=null
    }
    dialog.show();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { form.reset() }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        runCatching { app.activeDocument.applyPhotoCrop(target,form.cropValue()) }.onSuccess { applied ->
            if(applied){dialog.dismiss();onSaved()}else form.fields.first().error="The page changed or is busy. Reopen Crop."
        }.onFailure { form.fields.first().error=it.message ?: "Cannot crop photo" }
    }
    app.io.execute {
        val result=runCatching { ImageSupport.decodeRaw(asset,512) }
        app.main.post { if(dialog.isShowing && !isDestroyed)result.onSuccess { form.load(it) }.onFailure { form.fields.first().error="Preview unavailable" } }
    }
    return dialog
}
internal fun MainActivity.savePhotoCropDraft() { photoCropEditor?.let { it.state.draft=it.form.snapshot() } }
internal fun MainActivity.closePhotoCropForDestroy() { savePhotoCropDraft();photoCropEditor?.dialog?.dismiss() }
internal fun MainActivity.restorePhotoCropEditor() {
    if(textEditor?.dialog?.isShowing==true)return
    (application as NotesApplication).photoCropState?.let { state -> showPhotoCropDialog(state) { ink.selectPhoto(state.target.imageId) } }
}
