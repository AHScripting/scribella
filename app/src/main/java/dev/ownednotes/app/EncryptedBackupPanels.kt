package dev.ownednotes.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import java.io.FileOutputStream

internal object EncryptedBackupPanels {
    fun show(activity:MainActivity)=Kit.Sheet(activity,"Encrypted backups",cancel="Done").apply {
        add(Kit.text(activity,"Make a separate encrypted copy of your downloaded notebooks, or restore missing notebooks with its recovery key. Existing backup and sync settings stay in place."))
        add(Kit.button(activity,"Create encrypted backup…",Kit.Button.TINTED) {dismiss();activity.chooseEncryptedBackupFolder(false)})
        add(Kit.button(activity,"Restore encrypted backup…",Kit.Button.PLAIN) {dismiss();activity.chooseEncryptedBackupFolder(true)})
    }.show()
}

internal class EncryptedBackupPrompt(private val activity:MainActivity,private val tree:Uri,val creating:Boolean) {
    private val app=activity.application as NotesApplication
    internal val recoveryKey=if(creating)EncryptedBackups.newKey() else ""
    val keyText=Kit.text(activity,"•••• •••• •••• ••••",Kit.Type.HEADLINE).apply {typeface=Typeface.MONOSPACE;isSaveEnabled=false}
    val keyField=Kit.field(activity,"Recovery key").apply {
        contentDescription="Recovery key";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod=PasswordTransformationMethod.getInstance();isSaveEnabled=false
        importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    val saved=Kit.Switch(activity,false,"I saved the recovery key") {savedChanged(it)}
    val show=Kit.Switch(activity,false,"Show recovery key") {visible->
        if(creating)keyText.text=if(visible)recoveryKey else "•••• •••• •••• ••••"
        else keyField.transformationMethod=if(visible)null else PasswordTransformationMethod.getInstance()
    }
    private var working=false
    val problem=Kit.text(activity,"",Kit.Type.CAPTION,secondary=true)
    val sheet=Kit.Sheet(activity,if(creating)"Create encrypted backup" else "Restore encrypted backup",if(creating)"Back up" else "Restore",onAction={submit();false}).apply {
        add(Kit.text(activity,if(creating)"Use a new empty folder for this snapshot. Save its recovery key first: Scribella cannot recover it. Existing backups and sync are unchanged." else "Enter this backup’s complete recovery key. Only missing notebooks are restored; notebooks already here or in Trash are kept."))
        if(creating) {
            add(keyText)
            add(Kit.button(activity,"Copy recovery key",Kit.Button.TINTED) {copyKey()})
            add(Kit.button(activity,"Print recovery key…",Kit.Button.PLAIN) {printKey()})
            add(Kit.row(activity,"I saved the recovery key",trailing=saved){saved.performClick()})
        } else add(keyField)
        add(Kit.row(activity,"Show recovery key",trailing=show){show.performClick()});add(problem)
        actionButton?.isEnabled=!creating
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {keyField.text.clear();keyText.text=""}
    }
    private fun savedChanged(value:Boolean) {sheet.actionButton?.isEnabled=value}
    fun show() {sheet.show()}
    internal fun copyKey() {
        val clip=ClipData.newPlainText("Scribella recovery key",recoveryKey)
        clip.description.extras=PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        activity.message("Recovery key copied. Keep it somewhere safe.")
    }
    private fun printKey() {
        runCatching {activity.getSystemService(PrintManager::class.java).print("Scribella recovery key",RecoveryKeyPrintAdapter(recoveryKey),PrintAttributes.Builder().build())}
            .onFailure {problem.text="Printing could not start. You can copy the recovery key instead."}
    }
    private fun submit() {
        if(working || creating && !saved.isChecked)return
        val key=runCatching {EncryptedBackups.cleanKey(if(creating)recoveryKey else keyField.text.toString())}.getOrElse {keyField.error="Enter the complete recovery key";return}
        working=true;sheet.actionButton?.isEnabled=false;keyField.isEnabled=false;saved.isEnabled=false
        if(creating)EncryptedBackups.create(app,tree,key) {result->
            finish(result.fold({
                "Encrypted backup: ${it.copied} notebooks saved; ${it.failed.size} failed.${it.scopeNotice}"
            },{"Encrypted backup failed. Check folder access and choose a separate empty folder. Existing notebooks and backups are kept."}),result.getOrNull()?.let {it.failed.isEmpty() && it.notDownloaded==0}==true)
        } else EncryptedBackups.restore(app,tree,key) {result->
            finish(result.fold({it.message},{"Could not restore. Check the recovery key and folder access. Existing notebooks are kept."}),result.getOrNull()?.failed?.isEmpty()==true)
        }
    }
    private fun finish(text:String,success:Boolean) {
        working=false
        if(activity.isDestroyed || !sheet.dialog.isShowing)return
        if(success){sheet.dismiss();activity.message(text)}else {
            problem.text=text;sheet.actionButton?.isEnabled=!creating || saved.isChecked;keyField.isEnabled=true;saved.isEnabled=true
        }
    }
}

/** Generated only on explicit Print; never stored in a notebook, shared folder or preferences. */
internal class RecoveryKeyPrintAdapter(private val key:String):PrintDocumentAdapter() {
    override fun onLayout(old:PrintAttributes,attributes:PrintAttributes,cancellation:CancellationSignal,callback:LayoutResultCallback,extras:Bundle?) {
        if(cancellation.isCanceled)callback.onLayoutCancelled()
        else callback.onLayoutFinished(PrintDocumentInfo.Builder("Scribella recovery key.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(),old!=attributes)
    }
    override fun onWrite(pages:Array<out PageRange>,destination:ParcelFileDescriptor,cancellation:CancellationSignal,callback:WriteResultCallback) {
        if(cancellation.isCanceled){callback.onWriteCancelled();return}
        try {
            FileOutputStream(destination.fileDescriptor).use {writeDocument(it)}
            if(cancellation.isCanceled)callback.onWriteCancelled()else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }catch(error:Exception){callback.onWriteFailed("Could not print recovery key")}
    }
    /** Same PDF payload used by Android Print; isolated tests verify without invoking a real printer. */
    internal fun writeDocument(output:java.io.OutputStream) {
        val pdf=PdfDocument()
        try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=20f}
            page.canvas.drawText("Scribella encrypted backup",48f,70f,paint)
            paint.textSize=14f;page.canvas.drawText("Keep this key safe. It opens the backup; Scribella cannot recover it.",48f,108f,paint)
            paint.typeface=Typeface.MONOSPACE;paint.textSize=18f
            key.chunked(24).forEachIndexed {index,line->page.canvas.drawText(line,48f,170f+index*30f,paint)}
            paint.typeface=Typeface.DEFAULT;paint.textSize=14f
            page.canvas.drawText("Library → Encrypted backups → Restore encrypted backup",48f,290f,paint)
            pdf.finishPage(page);pdf.writeTo(output)
        }finally {pdf.close()}
    }
}
