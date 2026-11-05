package dev.ownednotes.app

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import dev.ownednotes.core.PdfAsset

/** User-approved existing Glass sheet. Password text is never saved in view/activity state. */
internal class PdfPasswordPrompt(private val activity: MainActivity, private val asset: PdfAsset,
    private val unlocked: ()->Unit, private val cancelled: ()->Unit = {}) {
    private val app=activity.application as NotesApplication
    val password=Kit.field(activity,"PDF password").apply {
        contentDescription="PDF password";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod=PasswordTransformationMethod.getInstance();isSaveEnabled=false
        importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    val show=Kit.Switch(activity,false,"Show password") { visible ->
        password.transformationMethod=if(visible)null else PasswordTransformationMethod.getInstance()
        password.setSelection(password.text.length)
    }
    val remember=Kit.Switch(activity,false,"Remember on this device") {}
    private var completed=false
    private var working=false
    val sheet=Kit.Sheet(activity,"Unlock PDF","Unlock",onAction={submit();false}).apply {
        add(Kit.text(activity,"Enter this PDF’s password. Its original file stays protected; Scribella can display, search and export unlocked pages."))
        add(password)
        add(Kit.row(activity,"Show password",trailing=show) { show.performClick() })
        add(Kit.row(activity,"Remember on this device",detail="Encrypted on this tablet only; never included in notebooks or sync.",trailing=remember) { remember.performClick() })
    }
    init { sheet.dialog.setOnDismissListener { password.text.clear();if(!completed)cancelled() } }
    fun show() { sheet.show() }
    private fun submit() {
        if(working || activity.isDestroyed)return
        val value=password.text.toString()
        if(value.length>4096) { password.error="Password is too long";return }
        val keep=remember.isChecked
        working=true;sheet.actionButton?.isEnabled=false;password.isEnabled=false;show.isEnabled=false;remember.isEnabled=false
        app.io.execute {
            val result=runCatching { PdfAccess.unlock(asset,value,keep) }
            app.main.post {
                working=false
                if(!sheet.dialog.isShowing || activity.isDestroyed)return@post
                result.onSuccess { completed=true;sheet.dismiss();unlocked() }.onFailure {
                    sheet.actionButton?.isEnabled=true;password.isEnabled=true;show.isEnabled=true;remember.isEnabled=true
                    password.error="Could not unlock this PDF. Check the password and try again."
                    password.requestFocus()
                }
            }
        }
    }
}
