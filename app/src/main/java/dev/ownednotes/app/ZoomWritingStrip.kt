package dev.ownednotes.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Approved magnified writing strip; native ink is committed directly to the current page. */
internal class ZoomWritingStrip(private val activity:MainActivity,private val primary:InkCanvas,private val close:()->Unit):FrameLayout(activity) {
    private val app=activity.application as NotesApplication
    private val repo=app.activeDocument
    val pageId=repo.selectedPageId!!
    val notebookId=repo.session!!.notebook.id
    private val page get()=repo.currentPage!!
    private val region=RectF(0f,page.height*.15f,page.width*.4f,page.height*.15f+page.width*.12f)
    internal val writingRegion get()=RectF(region)
    val canvas=InkCanvas(activity,repo,{activity.refresh()},{activity.message(it)})
    internal val panel=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;background=Glass.pane(activity,activity.night,30f);elevation=activity.dp(16f);setPadding(activity.dpi(12f),activity.dpi(8f),activity.dpi(12f),activity.dpi(8f));isClickable=true}
    private val indicator=object:View(activity) {
        private var down:Pair<Float,Float>?=null
        private var original=RectF()
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=activity.dp(2f);color=Glass.accent(activity.night)}
        private fun box():RectF {val a=primary.pageToView(region.left,region.top);val b=primary.pageToView(region.right,region.bottom);return RectF(a[0],a[1],b[0],b[1])}
        override fun onDraw(c:Canvas) {c.drawRoundRect(box(),activity.dp(4f),activity.dp(4f),paint)}
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(event.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER || canvas.isEditing)return false
            val point=primary.pagePoint(event.x,event.y)
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{if(!box().contains(event.x,event.y))return false;down=point.x to point.y;original=RectF(region);parent.requestDisallowInterceptTouchEvent(true)}
                MotionEvent.ACTION_MOVE->{val start=down ?: return false;moveTo(original.left+point.x-start.first,original.top+point.y-start.second)}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{down=null;parent.requestDisallowInterceptTouchEvent(false)}
            }
            return true
        }
    }
    init {
        contentDescription="Zoom writing box"
        addView(indicator,LayoutParams(-1,-1))
        panel.addView(LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(Kit.text(activity,"Zoom writing",Kit.Type.HEADLINE),LinearLayout.LayoutParams(0,-2,1f))
            addView(Kit.button(activity,"Previous line",Kit.Button.PLAIN,small=true){line(-1)})
            addView(Kit.button(activity,"Next line",Kit.Button.PLAIN,small=true){line(1)})
            addView(Kit.button(activity,"Close",Kit.Button.PLAIN,small=true){if(!canvas.isEditing)close()})
        })
        panel.addView(canvas,LinearLayout.LayoutParams(-1,activity.dpi(150f)))
        addView(panel,LayoutParams(-1,-2,Gravity.BOTTOM).apply {setMargins(activity.dpi(24f),0,activity.dpi(24f),activity.dpi(110f))})
        canvas.addOnLayoutChangeListener {_,l,t,r,b,ol,ot,or,ob->if((r-l!=or-ol || b-t!=ob-ot) && r>l && b>t && !canvas.isEditing) {
            region.bottom=region.top+region.width()*(b-t)/(r-l);moveTo(region.left,region.top)
        }}
        canvas.onWritingFinished={
            val end=repo.currentPage?.strokes?.lastOrNull()?.samples?.lastOrNull()
            if(end!=null && end.x>=region.right-region.width()*.12f && end.y in region.top..region.bottom) {
                if(region.right<page.width-.5f)moveTo(region.left+region.width()*.8f,region.top)else line(1)
            }
        }
        refresh()
    }
    fun refresh() {canvas.mirrorWritingTools(primary);canvas.refresh();indicator.invalidate()}
    fun viewportChanged() {indicator.invalidate()}
    internal fun line(direction:Int) {if(!canvas.isEditing)moveTo(0f,region.top+direction*region.height()*.8f)}
    internal fun moveTo(left:Float,top:Float) {
        if(canvas.isEditing)return
        val w=region.width().coerceAtMost(page.width);val h=region.height().coerceAtMost(page.height)
        val x=left.coerceIn(0f,page.width-w);val y=top.coerceIn(0f,page.height-h)
        region.set(x,y,x+w,y+h);canvas.setWritingRegion(region);indicator.invalidate()
    }
    fun covers(x:Float,y:Float):Boolean {
        val at=IntArray(2);panel.getLocationInWindow(at)
        return x>=at[0] && x<at[0]+panel.width && y>=at[1] && y<at[1]+panel.height
    }
}
