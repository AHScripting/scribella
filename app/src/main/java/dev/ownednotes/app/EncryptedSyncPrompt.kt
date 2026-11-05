package dev.ownednotes.app

import android.net.Uri
import android.text.InputType
import android.text.method.PasswordTransformationMethod

/** Approved Glass sheet; reuse an existing encrypted folder by entering its original passphrase. */
internal class EncryptedSyncPrompt(private val activity: MainActivity,private val tree: Uri) {
    private val app=activity.application as NotesApplication
    private fun field(label: String)=Kit.field(activity,label).apply {
        contentDescription=label;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod=PasswordTransformationMethod.getInstance();isSaveEnabled=false
        importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    val password=field("Encryption passphrase")
    val confirmation=field("Confirm passphrase")
    val show=Kit.Switch(activity,false,"Show passphrase") {visible->
        listOf(password,confirmation).forEach {it.transformationMethod=if(visible)null else PasswordTransformationMethod.getInstance();it.setSelection(it.text.length)}
    }
    private var working=false
    val sheet=Kit.Sheet(activity,"Additional encrypted destination","Connect",onAction={submit();false}).apply {
        add(Kit.text(activity,"Use a new empty folder, or enter an existing encrypted folder’s passphrase. Your current sync keeps running."))
        add(Kit.text(activity,"Keep this passphrase safe: another device needs it to open these copies. It cannot be recovered. File names and sizes remain visible. Your current primary copies remain unencrypted.",secondary=true))
        add(password);add(confirmation);add(Kit.row(activity,"Show passphrase",trailing=show){show.performClick()})
    }
    init {sheet.dialog.setOnDismissListener {password.text.clear();confirmation.text.clear()}}
    fun show() {sheet.show()}
    private fun submit() {
        if(working)return
        if(password.text.length !in 8..4096) {password.error="Use between 8 and 4096 characters";return}
        if(password.text.toString()!=confirmation.text.toString()) {confirmation.error="Passphrases do not match";return}
        val value=CharArray(password.text.length) {password.text[it]}
        working=true;sheet.actionButton?.isEnabled=false;password.isEnabled=false;confirmation.isEnabled=false;show.isEnabled=false
        sheet.dialog.setCancelable(false);sheet.dialog.setCanceledOnTouchOutside(false)
        EncryptedSync.connect(app,tree,value) {result->
            working=false
            if(!sheet.dialog.isShowing || activity.isDestroyed)return@connect
            result.onSuccess {sheet.dismiss();activity.message("Additional encrypted destination connected. Primary sync is unchanged.");activity.syncLibrary(true)}.onFailure {
                sheet.actionButton?.isEnabled=true;password.isEnabled=true;confirmation.isEnabled=true;show.isEnabled=true;sheet.dialog.setCancelable(true)
                password.error="Could not connect. Check folder access and passphrase; new destinations must be empty."
            }
        }
    }
}
