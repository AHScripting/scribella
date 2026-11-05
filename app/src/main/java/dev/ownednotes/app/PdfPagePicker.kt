package dev.ownednotes.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.ownednotes.core.PdfPages
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Visible-page previews only. PDF numbers count the cover and front matter, not printed book labels. */
internal class PdfPagePicker(private val activity:MainActivity, val prepared:PdfSupport.Prepared,
    private val capacity:Int, private val accept:(List<Int>)->Unit, private val cancelled:()->Unit = {}) {
    private val c get()=activity
    private val selected=if(prepared.total<=capacity)(0 until prepared.total).toMutableSet()else mutableSetOf()
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"pdf-import-previews").apply {isDaemon=true}}
    private val cache=android.util.LruCache<Int,Bitmap>(24)
    private var closed=false
    private var accepted=false
    internal var renderedCount=0;private set
    internal val range=Kit.field(c,"Optional PDF page range, e.g. 12–21").apply {id=android.R.id.edit;setSingleLine(true)}
    internal val count=Kit.text(c,"")
    internal val grid=RecyclerView(c)
    internal val sheet=Kit.Sheet(c,"Import PDF pages","Import",maxWidth=1040f,centred=true,onAction={
        val chosen=runCatching {
            if(range.text.isNotBlank()) PdfPages.select(range.text.toString(),prepared.total,capacity)
            else selected.sorted().also {require(it.isNotEmpty()) {"Choose at least one page"};require(it.size<=capacity) {"Choose up to $capacity pages"}}
        }.getOrElse {range.error=it.message;return@Sheet false}
        accepted=true;accept(chosen);true
    })
    val isShowing get()=sheet.dialog.isShowing
    fun dismiss()=sheet.dismiss()
    fun getButton(which:Int):TextView?=if(which==AlertDialog.BUTTON_POSITIVE)sheet.actionButton else null
    fun <T:View> findViewById(id:Int):T=sheet.dialog.findViewById<T>(id)!!
    private val adapter:RecyclerView.Adapter<Holder> = PreviewAdapter()
    private inner class PreviewAdapter:RecyclerView.Adapter<Holder>() {
        override fun getItemCount()=prepared.total
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(LinearLayout(c))
        override fun onBindViewHolder(h:Holder,index:Int) {
            h.task?.cancel(false);h.index=index;h.art.setImageBitmap(null)
            h.label.text="PDF page ${index+1}";h.itemView.contentDescription="PDF page ${index+1}, ${if(index in selected)"selected" else "not selected"}"
            h.itemView.background=rounded(if(index in selected)Kit.accentFill(c.night)else Kit.cardFill(c.night),c.dp(14f))
            h.label.setTextColor(if(index in selected)Kit.accentText(c.night)else Glass.text(c.night))
            h.itemView.isSelected=index in selected
            cache.get(index)?.let {h.art.setImageBitmap(it);return}
            h.task=worker.submit {
                if(closed || h.index!=index)return@submit
                val result=runCatching {PdfSupport.thumbnail(c.cacheDir,prepared,index)}
                activity.runOnUiThread {
                    if(closed){result.getOrNull()?.recycle();return@runOnUiThread}
                    result.onSuccess {cache.put(index,it);renderedCount++;if(h.index==index)h.art.setImageBitmap(it)}
                        .onFailure {if(h.index==index){h.label.text="PDF page ${index+1} · preview unavailable"}}
                }
            }
        }
        override fun onViewRecycled(h:Holder){h.index=-1;h.task?.cancel(false);h.art.setImageBitmap(null)}
    }
    private inner class Holder(root:LinearLayout):RecyclerView.ViewHolder(root) {
        val art=ImageView(c).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(android.graphics.Color.WHITE);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        val label=Kit.text(c,"",Kit.Type.CAPTION).apply {gravity=Gravity.CENTER}
        var index=-1;var task:Future<*>?=null
        init {
            (itemView as LinearLayout).apply {
                orientation=LinearLayout.VERTICAL;setPadding(c.dpi(6f),c.dpi(6f),c.dpi(6f),c.dpi(6f))
                layoutParams=RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,c.dpi(174f)).apply {setMargins(c.dpi(4f),c.dpi(4f),c.dpi(4f),c.dpi(4f))}
                addView(art,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));addView(label)
                isClickable=true;isFocusable=true;setOnClickListener {if(index>=0)toggle(index)}
            }
        }
    }
    internal fun toggle(index:Int){require(index in 0 until prepared.total);range.setText("");if(!selected.add(index))selected.remove(index);refresh()}
    internal fun chooseAll(all:Boolean){range.setText("");selected.clear();if(all && prepared.total<=capacity)selected.addAll(0 until prepared.total);refresh()}
    internal fun chosen()=selected.sorted()
    private fun refresh(){count.text="${selected.size} selected · ${prepared.total} PDF pages · limit $capacity";adapter.notifyDataSetChanged()}
    fun show():PdfPagePicker {
        sheet.add(Kit.text(c,"Choose the pages by their previews. PDF page numbers include the cover and contents; printed book numbers may differ.",Kit.Type.CAPTION,secondary=true))
        val actions=LinearLayout(c).apply {gravity=Gravity.CENTER_VERTICAL
            addView(Kit.button(c,"All",Kit.Button.PLAIN,small=true){if(prepared.total>capacity)c.message("Choose up to $capacity pages")else chooseAll(true)})
            addView(Kit.button(c,"None",Kit.Button.PLAIN,small=true){chooseAll(false)})
            addView(count,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))}
        sheet.add(actions,8f)
        grid.layoutManager=GridLayoutManager(c,if(c.resources.configuration.screenWidthDp>=700)4 else 3)
        grid.setOnTouchListener {v,e->if(e.actionMasked==MotionEvent.ACTION_DOWN)v.parent.requestDisallowInterceptTouchEvent(true);false}
        grid.adapter=adapter;grid.isNestedScrollingEnabled=false;grid.itemAnimator=null;grid.setItemViewCacheSize(4)
        sheet.body.addView(grid,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,c.dpi(370f)))
        sheet.add(range,8f)
        sheet.add(LinearLayout(c).apply {
            addView(Kit.button(c,"Select range",Kit.Button.PLAIN,small=true){
                runCatching {PdfPages.select(range.text.toString(),prepared.total,capacity)}.onSuccess {selected.clear();selected.addAll(it);range.setText("");refresh();grid.scrollToPosition(it.first())}.onFailure {range.error=it.message}
            })
            addView(Kit.button(c,"Go to page",Kit.Button.PLAIN,small=true){
                val n=range.text.toString().trim().toIntOrNull()
                if(n==null || n !in 1..prepared.total)range.error="Enter a PDF page number" else {grid.scrollToPosition(n-1);range.setText("")}
            })
        },0f)
        val observer=object:DefaultLifecycleObserver {override fun onDestroy(owner:LifecycleOwner){dismiss()}}
        activity.lifecycle.addObserver(observer)
        sheet.dialog.setOnDismissListener {closed=true;worker.shutdownNow();cache.evictAll();activity.lifecycle.removeObserver(observer);if(!accepted)cancelled()}
        refresh();sheet.show();return this
    }
}
