package dev.ownednotes.app

import dev.ownednotes.core.*

/** Existing text objects own note content, layers, selection, clipboard and Undo. */
internal object StickyNotes {
    fun toggle(activity:MainActivity,pageId:String,id:String):Boolean {
        val repo=(activity.application as NotesApplication).activeDocument
        if(!repo.canChangeNotebook || repo.readOnly || activity.ink.isEditing)return false
        val page=repo.session?.notebook?.pages?.firstOrNull {it.id==pageId} ?: return false
        val box=page.textBoxes.firstOrNull {it.id==id && it.sticky!=null} ?: return false
        if(!page.canEdit(box))return false
        var changed=false
        repo.edit {changed=it.updateTextBox(pageId,box.toggleStickyFold());changed}
        if(changed && repo.selectedPageId==pageId)activity.ink.selectText(id)
        return changed
    }
    fun hit(page:Page,point:Point):TextBox? {
        for(slice in page.paintSlices.asReversed()) {
            if(slice.attachments.any {it.placement.contains(point)})return null
            val box=slice.textBoxes.asReversed().firstOrNull {page.canSee(it.layer) && page.objectOpacity(it.layer)>0f &&
                (it.sticky!=null || it.style.color ushr 24!=0L) && it.placement.contains(point)}
            if(box!=null)return box.takeIf {it.sticky!=null}
            if(slice.images.any {page.canSee(it.layer) && page.objectOpacity(it.layer)>0f && it.placement.contains(point)})return null
        }
        return null
    }
}
