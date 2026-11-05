package dev.ownednotes.app

import android.graphics.Path
import dev.ownednotes.core.*

/** Finger-only follow uses visible geometry, never hidden/erased ink or numeric page positions. */
internal object NotebookLinks {
    fun hit(page:Page,point:Point):NotebookLink? {
        if(!point.x.isFinite() || !point.y.isFinite())return null
        val probe=Path().apply {addCircle(point.x,point.y,3f,Path.Direction.CW)}
        for(slice in page.paintSlices.asReversed()) {
            if(slice.attachments.any {it.placement.contains(point)})return null
            for(stroke in slice.drawnStrokes.asReversed()) {
                if(slice.opacityOf(stroke)<=0f || stroke.color ushr 24==0L)continue
                if(InkGeometry.intersects(InkGeometry.visible(stroke),probe))return stroke.link
            }
            val text=slice.textBoxes.asReversed().firstOrNull {slice.canSee(it.layer) && slice.objectOpacity(it.layer)>0f &&
                (it.sticky!=null || it.style.color ushr 24!=0L) && it.placement.contains(point)}
            if(text!=null)return text.link
            if(slice.images.any {slice.canSee(it.layer) && slice.objectOpacity(it.layer)>0f && it.placement.contains(point)})return null
        }
        return null
    }
    fun supported(ids:ObjectIds)=ids.images.isEmpty() && !ids.isEmpty
    fun has(page:Page,ids:ObjectIds)=page.strokes.any {it.id in ids.strokes && it.link!=null} || page.textBoxes.any {it.id in ids.texts && it.link!=null}

    fun choose(activity:MainActivity):Kit.Sheet? {
        val repo=(activity.application as NotesApplication).activeDocument
        val source=repo.session?.notebook ?: return null
        val pageId=repo.selectedPageId ?: return null
        val ids=activity.ink.selectedObjectIds
        if(!repo.canChangeNotebook || repo.readOnly || activity.ink.isEditing || !supported(ids))return null
        fun valid()=!activity.isDestroyed && !activity.ink.isEditing && repo.canChangeNotebook && !repo.readOnly &&
            repo.session?.notebook===source && repo.selectedPageId==pageId
        val sheet=Kit.Sheet(activity,"Link destination")
        fun save(target:NotebookLink) {
            if(!valid()){activity.message("The selection changed. Select it again.");sheet.dismiss();return}
            runCatching {repo.edit {it.linkObjects(pageId,ids,target)}}.onFailure {activity.message("The selection is no longer editable.")}
            sheet.dismiss()
        }
        fun notebooks() {
            sheet.titleView.text="Link destination";sheet.body.removeAllViews()
            val entries=repo.entries.filter {it.error==null && it.availableOffline}
            for(entry in entries)sheet.add(Kit.row(activity,entry.title,chevron=true) {
                if(!valid()){sheet.dismiss();return@row}
                sheet.body.removeAllViews();sheet.titleView.text=entry.title
                sheet.add(Kit.text(activity,"Loading pages…",Kit.Type.BODY))
                val started=repo.linkPages(entry.id) {result->
                    if(!sheet.dialog.isShowing || !valid())return@linkPages
                    sheet.body.removeAllViews()
                    sheet.add(Kit.row(activity,"Choose another notebook") {notebooks()})
                    result.onSuccess {pages->
                        sheet.add(Kit.row(activity,"Link to notebook","Opens its last visited page") {save(NotebookLink(entry.id))})
                        pages.forEachIndexed {i,(id,label)->sheet.add(Kit.row(activity,label,detail="Page ${i+1}") {save(NotebookLink(entry.id,id))})}
                    }.onFailure {sheet.add(Kit.text(activity,"Cannot read this notebook. Try reopening it from the Library.",Kit.Type.BODY))}
                }
                if(!started){sheet.body.removeAllViews();sheet.add(Kit.text(activity,"Notebook unavailable. Download it first from the Library.",Kit.Type.BODY))}
            })
        }
        notebooks();return sheet.show()
    }
    fun remove(activity:MainActivity):Boolean {
        val repo=(activity.application as NotesApplication).activeDocument
        val id=repo.selectedPageId ?: return false;val ids=activity.ink.selectedObjectIds
        if(!repo.canChangeNotebook || repo.readOnly || activity.ink.isEditing || !supported(ids))return false
        var changed=false
        runCatching {repo.edit {changed=it.linkObjects(id,ids,null);changed}}
            .onFailure {activity.message("The selection is no longer editable.")}
        return changed
    }
}
