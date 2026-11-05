package dev.ownednotes.app

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.view.View
import android.view.WindowManager
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import dev.ownednotes.core.FolderSync

/** Process-owned authentication, never serialized or restored from Activity state. */
internal class AppLockSession(private val clock: ()->Long={SystemClock.elapsedRealtime()}) {
    companion object {const val AWAY_MILLIS=3*60*1000L}
    private var authenticated=false
    private var awayAt:Long?=null
    private var failures=0
    private var blockedUntil=0L
    fun needsLock(enabled:Boolean)=enabled && (!authenticated || awayAt?.let {clock()-it>=AWAY_MILLIS}==true)
    fun unlock() {authenticated=true;awayAt=null;failures=0;blockedUntil=0}
    fun lock() {authenticated=false;awayAt=null}
    fun leave() {if(authenticated && awayAt==null)awayAt=clock()}
    fun returned() {awayAt=null}
    fun canAttempt()=clock()>=blockedUntil
    fun failed() {failures++;if(failures%5==0)blockedUntil=clock()+30_000}
}

/** PIN verifier is salted/derived, then wrapped by this installation's non-exportable Keystore key. */
internal class AppLockStore(app:NotesApplication) {
    private val scope=FolderSync.sha256(app.preferencesName.byteInputStream()).take(24)
    internal val credentialFile=File(app.noBackupFilesDir,"app-lock-$scope.bin")
    private val atomic=AtomicFile(credentialFile)
    private val alias="notes-lab-app-lock-$scope"
    fun enabled()=credentialFile.exists() || File(credentialFile.path+".bak").exists()
    private fun valid(pin:CharArray)=pin.size in 6..12 && pin.all {it in '0'..'9'}
    private fun derive(pin:CharArray,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(pin,salt,600_000,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally {spec.clearPassword()}
    }
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(store.containsAlias(alias))return store.getKey(alias,null) as SecretKey
        check(create) {"App-lock key is unavailable"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    /** Owns and clears the supplied character array. */
    fun setPin(pin:CharArray) {
        try {
            require(valid(pin)) {"Use a PIN of 6 to 12 digits"}
            val salt=ByteArray(16).also {SecureRandom().nextBytes(it)};val hash=derive(pin,salt)
            val plain=salt+hash
            try {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(true));updateAAD(alias.toByteArray())}
                val wrapped=cipher.iv+cipher.doFinal(plain);val out=atomic.startWrite()
                try {out.write(wrapped);atomic.finishWrite(out)}catch(error:Throwable){atomic.failWrite(out);throw error}
            }finally {plain.fill(0);hash.fill(0);salt.fill(0)}
        }finally {pin.fill('\u0000')}
    }
    /** Invalid/corrupt credentials never unlock; callers show a generic error. */
    fun verify(pin:CharArray):Boolean {
        try {
            if(!valid(pin))return false
            val wrapped=atomic.openRead().use {it.readNBytes(77)};require(wrapped.size==76)
            val plain=Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,wrapped.copyOfRange(0,12)));updateAAD(alias.toByteArray());doFinal(wrapped.copyOfRange(12,wrapped.size))
            }
            require(plain.size==48)
            val derived=derive(pin,plain.copyOfRange(0,16))
            return try {MessageDigest.isEqual(derived,plain.copyOfRange(16,48))}finally {derived.fill(0);plain.fill(0)}
        }finally {pin.fill('\u0000')}
    }
    fun disable() {atomic.delete();check(!enabled()) {"Could not remove app lock"}}
}

/** A locked Activity has no visible/accessibility notebook tree and consumes editor input. */
internal class AppLockGate(private val activity:MainActivity) {
    private val app=activity.application as NotesApplication
    val store=AppLockStore(app)
    private val session get()=app.appLockSession
    private var configured=store.enabled()
    val locked get()=session.needsLock(configured)
    internal var prompt:AppLockUnlock?=null;private set
    private var active=false
    private var disposed=false
    private var biometric:CancellationSignal?=null
    private val content get()=activity.findViewById<View>(android.R.id.content)
    private fun cover() {content.visibility=View.INVISIBLE;activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    private fun reveal() {content.visibility=View.VISIBLE;activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);activity.refresh()}
    fun created() {if(configured)cover()}
    fun resumed() {
        active=true;configured=store.enabled()
        if(locked) {
            cover();if(prompt?.sheet?.dialog?.isShowing!=true)prompt=AppLockUnlock(activity,this).also {it.show()}
        } else {session.returned();reveal()}
    }
    fun paused() {if(configured)cover()}
    fun stopped() {active=false;session.leave();biometric?.cancel();biometric=null}
    fun lockNow() {session.lock();resumed()}
    fun enabledNow() {configured=store.enabled();if(active && !disposed) {session.unlock();reveal()}}
    fun disable() {store.disable();configured=false;session.lock();if(active)reveal()}
    fun canAttempt()=session.canAttempt()
    fun wrongPin()=session.failed()
    fun unlock() {
        if(!active || disposed || activity.isDestroyed || prompt?.sheet?.dialog?.isShowing!=true)return
        session.unlock();val current=prompt;prompt=null;current?.authorized=true;current?.sheet?.dismiss();biometric?.cancel();biometric=null;reveal()
    }
    fun dismissed() {if(!disposed && locked && !activity.isFinishing)activity.finish()}
    fun dispose() {disposed=true;active=false;biometric?.cancel();prompt?.sheet?.dismiss();prompt=null}
    fun biometricAvailable()=activity.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
    fun fingerprint(error:(String)->Unit) {
        if(!active || !locked || !biometricAvailable())return
        biometric?.cancel();val cancellation=CancellationSignal();biometric=cancellation
        BiometricPrompt.Builder(activity).setTitle("Unlock Scribella")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Use PIN",activity.mainExecutor) {_,_->cancellation.cancel()}.build()
            .authenticate(cancellation,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {if(biometric===cancellation)unlock()}
                override fun onAuthenticationError(code:Int,message:CharSequence) {
                    if(biometric===cancellation){biometric=null;if(active)error("Use your PIN to unlock.")}
                }
                override fun onAuthenticationFailed() {if(active)error("Fingerprint not recognized. Try again or use your PIN.")}
            })
    }
}
