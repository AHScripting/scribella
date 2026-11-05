package dev.ownednotes.app

import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.ownednotes.core.*

/** Library writes copy immutable photo data; they never change the source notebook. */
internal fun MainActivity.saveStickerDialog(imageId:String?=null):AlertDialog? {
    val app=application as NotesApplication;val repo=app.activeDocument
    val target=repo.captureImageInsertTarget() ?: return null
    val content=if(imageId!=null) {
        val photo=repo.captureStickerPhoto(imageId) ?: return null
        ObjectBundle(emptyList(),listOf(photo.image),emptyList(),listOf(photo.asset))
    } else repo.captureStickerObjects(ink.selectedObjectIds) ?: return null
    val name=EditText(this).apply { hint="Sticker name";contentDescription="Sticker name";setSingleLine() }
    val collection=EditText(this).apply { hint="Collection (optional)";contentDescription="Sticker collection";setSingleLine() }
    val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dpi(20f),dpi(8f),dpi(20f),dpi(8f));addView(name);addView(collection) }
    val dialog=AlertDialog.Builder(this).setTitle("Save as sticker").setView(form)
        .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val record=runCatching { StickerRecord.fromObjects(name.text.toString(),content,collection.text.toString()) }
            .getOrElse { name.error=it.message;return@setOnClickListener }
        if(!repo.canInsertImage(target)){name.error="The page changed or is busy. Reopen Save as sticker.";return@setOnClickListener}
        repo.setFileBusy(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
        app.io.execute {
            val result=runCatching { app.stickerLibrary.addObjects(record.name,content,record.collection) }
            app.main.post {
                repo.setFileBusy(false)
                if(!isDestroyed) {
                    result.onSuccess { dialog.dismiss();message("Saved sticker") }
                        .onFailure { name.error=it.message ?: "Cannot save sticker";dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
                }
            }
        }
    }
    return dialog
}

/** Capture the destination before asynchronous loading; each insertion revalidates that exact snapshot. */
internal fun MainActivity.stickerLibraryDialog():AlertDialog? {
    val app=application as NotesApplication;val repo=app.activeDocument
    val target=repo.captureImageInsertTarget() ?: return null
    val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dpi(16f),dpi(8f),dpi(16f),dpi(8f)) }
    val filter=Spinner(this).apply { contentDescription="Sticker collection filter" }
    val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(filter);addView(rows) }
    var loaded:StickerPack?=null
    var collections=listOf<String?>(null)
    var selectedCollection:String?=null
    fun status(text:String) { rows.removeAllViews();rows.addView(TextView(this).apply { this.text=text }) }
    status("Loading stickers…")
    val dialog=AlertDialog.Builder(this).setTitle("Stickers").setView(ScrollView(this).apply { addView(body) })
        .setNegativeButton("Close",null).create()
    lateinit var reload:()->Unit
    fun render() {
        val pack=loaded ?: return
        rows.removeAllViews()
        val visible=pack.stickers.filter { selectedCollection==null || it.collection==selectedCollection }
        if(visible.isEmpty())status(if(pack.stickers.isEmpty())"No saved stickers. Select ink, text or photos and choose Save as sticker." else "No stickers in this collection.")
        visible.forEach { record ->
                    val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                    row.addView(Button(this).apply {
                        text=if(record.collection.isEmpty())record.name else "${record.collection} · ${record.name}"
                        contentDescription="Insert sticker ${record.name}"
                        setOnClickListener {
                            runCatching { ink.insertStickerObjects(target,record,pack.imageAssets) }.onSuccess { inserted ->
                                if(!inserted)status("The page changed or is busy. Reopen Stickers.")
                                else { dialog.dismiss();message("Inserted sticker") }
                            }.onFailure { message("Cannot insert sticker: ${it.message}") }
                        }
                    },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
                    row.addView(Button(this).apply {
                        text="Manage";contentDescription="Manage sticker ${record.name}"
                        setOnClickListener { stickerManageDialog(record) { reload() } }
                    })
                    rows.addView(row)
        }
    }
    filter.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent:AdapterView<*>?) = Unit
        override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
            selectedCollection=collections.getOrNull(position);render()
        }
    }
    reload={
        app.io.execute {
            val result=runCatching { app.stickerLibrary.read() }
            app.main.post {
                if(dialog.isShowing && !isDestroyed) {
                    result.onFailure { loaded=null;status("Cannot read sticker library: ${it.message}") }.onSuccess { pack ->
                        loaded=pack
                        collections=listOf<String?>(null)+pack.stickers.map { it.collection }.distinct().sorted()
                        if(selectedCollection !in collections)selectedCollection=null
                        val choice=collections.indexOf(selectedCollection)
                        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,collections.map {
                            when(it){null->"All collections";""->"Uncollected";else->"Collection: $it"}
                        })
                        filter.setSelection(choice);render()
                    }
                }
            }
        }
    }
    dialog.show();reload()
    return dialog
}

internal fun MainActivity.stickerManageDialog(record:StickerRecord,onChanged:()->Unit):AlertDialog? {
    val repo=(application as NotesApplication).activeDocument
    if(repo.readOnly || !repo.canChangeNotebook)return null
    return AlertDialog.Builder(this).setTitle(record.name).setItems(arrayOf("Rename or change collection…","Remove from library…")) { _,which ->
        if(which==0)editStickerDialog(record,onChanged) else removeStickerDialog(record,onChanged)
    }.setNegativeButton("Cancel",null).show()
}

/** Guard the exact library record atomically, so an old dialog cannot overwrite a newer rename/removal. */
private fun MainActivity.changeSticker(record:StickerRecord,operation:(StickerLibrary)->Boolean,done:(Result<Unit>)->Unit):Boolean {
    val app=application as NotesApplication;val repo=app.activeDocument
    if(repo.readOnly || !repo.canChangeNotebook)return false
    repo.setFileBusy(true)
    app.io.execute {
        val result=runCatching {
            synchronized(app.stickerLibrary) {
                require(app.stickerLibrary.read().stickers.firstOrNull { it.id==record.id }==record) { "Sticker changed. Reopen its controls." }
                check(operation(app.stickerLibrary)) { "Sticker no longer exists." }
            }
        }
        app.main.post { repo.setFileBusy(false);if(!isDestroyed)done(result) }
    }
    return true
}

internal fun MainActivity.editStickerDialog(record:StickerRecord,onChanged:()->Unit):AlertDialog? {
    val repo=(application as NotesApplication).activeDocument
    if(repo.readOnly || !repo.canChangeNotebook)return null
    val name=EditText(this).apply { contentDescription="Sticker name";setSingleLine();setText(record.name) }
    val collection=EditText(this).apply { contentDescription="Sticker collection";hint="Collection (optional)";setSingleLine();setText(record.collection) }
    val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dpi(20f),dpi(8f),dpi(20f),dpi(8f));addView(name);addView(collection) }
    val dialog=AlertDialog.Builder(this).setTitle("Edit sticker").setView(form).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val updated=runCatching { record.copy(name=name.text.toString(),collection=collection.text.toString()) }.getOrElse { name.error=it.message;return@setOnClickListener }
        val save=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val started=changeSticker(record,{ it.rename(record.id,updated.name,updated.collection) }) { result ->
            result.onSuccess { dialog.dismiss();onChanged() }.onFailure { name.error=it.message;save.isEnabled=true }
        }
        if(started)save.isEnabled=false else name.error="Notebook is read-only or busy. Try again when ready."
    }
    return dialog
}

internal fun MainActivity.removeStickerDialog(record:StickerRecord,onChanged:()->Unit):AlertDialog? {
    val repo=(application as NotesApplication).activeDocument
    if(repo.readOnly || !repo.canChangeNotebook)return null
    val status=TextView(this).apply { text="Remove “${record.name}” from the sticker library? Photos already inserted in notebooks stay unchanged.";setPadding(dpi(20f),dpi(8f),dpi(20f),dpi(8f));contentDescription="Sticker removal status" }
    val dialog=AlertDialog.Builder(this).setTitle("Remove sticker?").setView(status).setPositiveButton("Remove",null).setNegativeButton("Cancel",null).create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val remove=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val started=changeSticker(record,{ it.remove(record.id) }) { result ->
            result.onSuccess { dialog.dismiss();onChanged() }.onFailure { status.text=it.message;remove.isEnabled=true }
        }
        if(started)remove.isEnabled=false else status.text="Notebook is read-only or busy. Try again when ready."
    }
    return dialog
}
