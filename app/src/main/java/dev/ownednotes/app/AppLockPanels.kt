package dev.ownednotes.app

import android.text.InputType
import android.text.method.PasswordTransformationMethod

private fun pinField(activity:MainActivity,label:String)=Kit.field(activity,label).apply {
    contentDescription=label;inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    transformationMethod=PasswordTransformationMethod.getInstance();isSaveEnabled=false
    importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    filters=arrayOf(android.text.InputFilter.LengthFilter(12))
}
private fun chars(field:android.widget.EditText)=CharArray(field.text.length) {field.text[it]}

internal class AppLockUnlock(private val activity:MainActivity,private val gate:AppLockGate) {
    private val app=activity.application as NotesApplication
    val pin=pinField(activity,"App PIN")
    internal var authorized=false
    private var working=false
    val sheet=Kit.Sheet(activity,"Unlock Scribella","Unlock",cancel="Close app",onAction={submit();false}).apply {
        add(Kit.text(activity,"Enter your PIN to open your notebooks."));add(pin)
        if(gate.biometricAvailable())add(Kit.button(activity,"Use fingerprint",Kit.Button.TINTED) {gate.fingerprint {pin.error=it}})
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setDimAmount(1f)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {pin.text.clear();if(!authorized)gate.dismissed()}
    }
    fun show() {sheet.show()}
    private fun submit() {
        if(working)return
        if(!gate.canAttempt()) {pin.error="Too many attempts. Wait 30 seconds, then try again.";return}
        val value=chars(pin);working=true;pin.isEnabled=false;sheet.actionButton?.isEnabled=false
        app.io.execute {
            val accepted=runCatching {gate.store.verify(value)}.getOrDefault(false)
            value.fill('\u0000')
            app.main.post {
                working=false
                if(activity.isDestroyed || !sheet.dialog.isShowing)return@post
                pin.isEnabled=true;sheet.actionButton?.isEnabled=true;pin.text.clear()
                if(accepted)gate.unlock() else {gate.wrongPin();pin.error="Could not unlock. Check your PIN and try again."}
            }
        }
    }
}

internal class AppLockSetup(private val activity:MainActivity) {
    private val app=activity.application as NotesApplication
    val pin=pinField(activity,"New app PIN")
    val confirmation=pinField(activity,"Confirm app PIN")
    val show=Kit.Switch(activity,false,"Show PIN") {visible->listOf(pin,confirmation).forEach {it.transformationMethod=if(visible)null else PasswordTransformationMethod.getInstance()}}
    private var working=false
    val sheet=Kit.Sheet(activity,"Set up app lock","Enable",onAction={submit();false}).apply {
        add(Kit.text(activity,"Use 6 to 12 digits. Keep this PIN safe. Fingerprint unlocking is offered when it is set up in Android."))
        add(Kit.text(activity,"Locks on a cold start or after three minutes away. This lock protects this device’s app screen; it does not encrypt your current cloud copies or change sync.",secondary=true))
        add(pin);add(confirmation);add(Kit.row(activity,"Show PIN",trailing=show){show.performClick()})
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {pin.text.clear();confirmation.text.clear()}
    }
    fun show() {sheet.show()}
    private fun submit() {
        if(working)return
        if(pin.text.length !in 6..12 || pin.text.any {it !in '0'..'9'}) {pin.error="Use a PIN of 6 to 12 digits";return}
        if(pin.text.toString()!=confirmation.text.toString()) {confirmation.error="PINs do not match";return}
        val value=chars(pin);working=true;sheet.actionButton?.isEnabled=false;pin.isEnabled=false;confirmation.isEnabled=false;show.isEnabled=false
        app.io.execute {
            val result=runCatching {activity.appLockGate.store.setPin(value)}
            app.main.post {
                working=false
                result.onSuccess {
                    activity.appLockGate.enabledNow()
                    if(!activity.isDestroyed && sheet.dialog.isShowing) {sheet.dismiss();activity.message("App lock enabled on this device.")}
                }.onFailure {
                    if(!activity.isDestroyed && sheet.dialog.isShowing) {sheet.actionButton?.isEnabled=true;pin.isEnabled=true;confirmation.isEnabled=true;show.isEnabled=true;pin.error="Could not save app lock. Try again."}
                }
            }
        }
    }
}

internal object AppLockPanels {
    fun settings(activity:MainActivity):Kit.Sheet? {
        val gate=activity.appLockGate
        if(gate.locked)return null
        return Kit.Sheet(activity,"App lock",cancel="Done").apply {
            if(!gate.store.enabled()) {
                add(Kit.text(activity,"Protect this device’s notebooks with a PIN and optional fingerprint unlocking."))
                add(Kit.button(activity,"Set up app lock…",Kit.Button.TINTED) {dismiss();activity.appLockSetup=AppLockSetup(activity).also {it.show()}})
            } else {
                add(Kit.text(activity,"App lock is on. Scribella locks on a cold start or after three minutes away. Your PIN stays on this device."))
                add(Kit.button(activity,"Lock now",Kit.Button.TINTED) {dismiss();gate.lockNow()})
                add(Kit.button(activity,"Turn off app lock",Kit.Button.PLAIN) {gate.disable();dismiss();activity.message("App lock turned off. Your notebooks are kept.")})
            }
        }.show()
    }
}
