package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.ownednotes.core.*

/** Approved controls inside the existing Study panel; pending question survives page/panel changes. */
internal class StudyCardControls(private val activity:MainActivity,private val ink:InkCanvas,private val frame:EditorFrame):LinearLayout(activity) {
    private val app=activity.application as NotesApplication
    private val repo=app.activeDocument
    private val cards=app.studyCards
    init {orientation=VERTICAL;render()}
    private fun attempt(action:()->Unit) {runCatching {check(repo.canChangeNotebook && !ink.isEditing) {"Wait for the page to finish saving"};action()}.onFailure {activity.message(it.message ?: "Could not update card")}}
    /**
     * P615 (board 9): one row, "Flashcards" with its due and total counts, and its two buttons side by side under it
     * (stacked with large text). Lasso a question, Mark question, then lasso its answer and save the card.
     */
    private fun render() {
        removeAllViews()
        val note=repo.session?.notebook
        val all=note?.let {cards.list(it.id)}.orEmpty();val due=note?.let {cards.due(it)}.orEmpty()
        addView(StudyRow(context,"Flashcards",StudyRow.square(context,"M4 8h12v12H4zM8 4h12v12",0xff5e5ce6.toInt()),
            if(note==null) "Open a notebook" else "${due.size} due · ${all.size} cards"))
        note ?: return
        val pending=cards.pending?.takeIf {it.first==note.id}
        val stacked=context.largeText
        fun small(label:String,description:String,onClick:()->Unit)=Kit.button(context,label,Kit.Button.TINTED,small=true,onClick=onClick).apply {
            contentDescription=description;textSize=13.5f;context.dpi(10f).let {setPadding(it,0,it,0)}
        }
        addView(LinearLayout(context).apply {
            orientation=if(stacked) VERTICAL else HORIZONTAL
            context.dpi(12f).let {setPadding(it,0,it,it)}
            addView(small(if(pending==null)"Mark question" else "Redo question","Mark selection as flashcard question") {attempt {
                cards.question(repo.session!!.notebook,repo.selectedPageId!!,ink.selectedObjectIds);render()
            }},if(stacked) LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT) else LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
            addView(small("Review due","Review due cards") {frame.startFlashcards()}.apply {isEnabled=due.isNotEmpty()},
                if(stacked) LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply {topMargin=context.dpi(8f)}
                else LayoutParams(0,LayoutParams.WRAP_CONTENT,1f).apply {marginStart=context.dpi(8f)})
        })
        if(pending!=null) addView(LinearLayout(context).apply {
            orientation=VERTICAL;context.dpi(12f).let {setPadding(it,0,it,it)}
            addView(CardPreview(context,note,pending.second))
            addView(Kit.button(context,"Answer and save card",Kit.Button.PRIMARY,small=true) {attempt {
                cards.answer(repo.session!!.notebook,repo.selectedPageId!!,ink.selectedObjectIds);activity.message("Flashcard saved");render()
            }},LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply {topMargin=context.dpi(8f)})
        })
    }
}

/** Live native-object preview: no PDF background or copied ink is stored in the card. */
internal class CardPreview(context:Context,private val note:Notebook,ref:StudyCards.Ref):View(context) {
    private val source=note.pages.firstOrNull {it.id==ref.page}
    private val page=source?.copy(strokes=source.strokes.filter {it.id in ref.ids.strokes},images=source.images.filter {it.id in ref.ids.images},textBoxes=source.textBoxes.filter {it.id in ref.ids.texts},attachments=emptyList(),pdfBackground=null,imageBackground=null)
    private val box=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription="Flashcard selection preview";minimumHeight=context.dpi(130f)
        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,context.dpi(150f))
        page?.drawnStrokes?.forEach {s->val r=RectF();InkGeometry.visible(s).computeBounds(r,true);box.union(r)}
        val points=page?.images.orEmpty().flatMap {it.placement.corners()}+page?.textBoxes.orEmpty().flatMap {it.placement.corners()}
        if(points.isNotEmpty())box.union(RectF(points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y}))
        box.inset(-8f,-8f)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);canvas.drawColor(Color.WHITE)
        val page=page ?: return
        if(box.width()<=0 || box.height()<=0)return
        val saved=canvas.save()
        val scale=minOf(width/box.width(),height/box.height())
        canvas.translate((width-box.width()*scale)/2,(height-box.height()*scale)/2);canvas.scale(scale,scale);canvas.translate(-box.left,-box.top)
        try {ExportRendering.drawObjects(canvas,note,page,inkPaint=paint)}finally {canvas.restoreToCount(saved)}
    }
}

/** Existing Recall-bar placement and Glass parts, now grading persistent linked cards. */
internal class FlashcardRecallBar(context:Context,private val app:NotesApplication,val notebookId:String,private val show:(StudyCards.Card,Boolean)->Unit,private val close:()->Unit):LinearLayout(context) {
    private val cards=app.studyCards.due(requireNotNull(app.activeDocument.session).notebook).toMutableList()
    private var revealed=false
    private val current get()=cards.firstOrNull()
    init {orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=Glass.pane(context,context.night,34f);elevation=context.dp(16f);isClickable=true;setPadding(context.dpi(16f),context.dpi(10f),context.dpi(16f),context.dpi(10f));render()}
    private fun render() {
        removeAllViews()
        val card=current
        addView(Kit.text(context,if(card==null)"Review complete" else "${cards.size} due",Kit.Type.CAPTION))
        if(card!=null) {
            show(card,revealed)
            if(!revealed)addView(Kit.button(context,"Reveal",Kit.Button.PRIMARY) {revealed=true;render()})
            else {
                addView(Kit.button(context,"Didn’t know",Kit.Button.TINTED) {grade(false)})
                addView(Kit.button(context,"Knew it",Kit.Button.PRIMARY) {grade(true)})
            }
        }
        addView(Kit.button(context,"Close",Kit.Button.PLAIN) {close()})
    }
    private fun grade(knew:Boolean) {val id=current?.id ?: return;app.studyCards.grade(id,knew);cards.removeAt(0);revealed=false;render()}
}
