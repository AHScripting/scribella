package dev.ownednotes.app

import android.content.Context
import android.text.InputType
import dev.ownednotes.core.SyncCancellation
import dev.ownednotes.core.WebDavHttpClient
import dev.ownednotes.core.WebDavRecovery

/** Functional composition of existing Glass parts; no new artwork, palette or layout primitives. */
internal object WebDavPanels {
    fun setup(context: Context, app: NotesApplication, connected: () -> Unit): Kit.Sheet {
        val address = Kit.field(context, "HTTPS server folder address").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val username = Kit.field(context, "Username").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val password = Kit.field(context, "Password or app password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val detail = Kit.text(context, "Loading saved sign-in…", secondary = true)
        val token = SyncCancellation()
        val original = LibrarySync.sourceIdentity(app)
        var alive = true; var busy = false; var loaded = false
        lateinit var sheet: Kit.Sheet
        sheet = Kit.Sheet(context, "WebDAV sync", "Check and connect", cancel = "Cancel") {
            if (alive && loaded && !busy) {
                val draftAddress = address.text.toString().trim()
                val draftUser = username.text.toString().takeIf { it.isNotEmpty() }
                val draftPassword = password.text.toString().let { if (draftUser == null && it.isEmpty()) null else it }
                busy = true; sheet.actionButton?.isEnabled = false
                address.isEnabled = false; username.isEnabled = false; password.isEnabled = false
                detail.text = "Checking server support…"
                app.io.execute {
                    val result = runCatching { WebDavSetup(app.webDavAccounts).connect(draftAddress, draftUser, draftPassword, token) }
                    app.main.post {
                        if (alive && LibrarySync.sourceIdentity(app) == original) {
                            busy = false
                            result.fold({ account ->
                                LibrarySync.setWebDav(app, account.id)
                                sheet.dismiss(); connected()
                            }, { error ->
                                detail.text = explain(error)
                                sheet.actionButton?.isEnabled = true; password.isEnabled = true
                                // New drafts remain editable. Existing endpoint/user deliberately keep their identity.
                                app.io.execute {
                                    val empty = runCatching { app.webDavAccounts.load() == null }.getOrDefault(false)
                                    app.main.post { if (alive && empty) { address.isEnabled = true; username.isEnabled = true } }
                                }
                            })
                        } else if (alive) { detail.text = "Sync source changed. Close this sheet and reopen it." }
                    }
                }
            }
            false
        }
        sheet.actionButton?.isEnabled = false
        sheet.add(Kit.text(context, "Use an HTTPS WebDAV folder that you control. The check creates small temporary test files. Connecting enables two-way notebook sync; existing notebooks stay saved on this tablet.", secondary = true))
        sheet.add(address); sheet.add(username); sheet.add(password)
        sheet.add(Kit.text(context, "Leave both sign-in fields empty only for a server that allows anonymous access. Use your server's app password when available.", Kit.Type.CAPTION, secondary = true))
        sheet.add(detail)
        sheet.dialog.setOnDismissListener { alive = false; token.cancel(); password.text.clear() }
        sheet.show()
        app.io.execute {
            val saved = runCatching { app.webDavAccounts.load() }
            app.main.post {
                if (alive) saved.fold({ account ->
                    loaded = true; sheet.actionButton?.isEnabled = true
                    if (account != null) {
                        address.setText(account.endpoint.collection.toString()); username.setText(account.username.orEmpty())
                        address.isEnabled = false; username.isEnabled = false
                        detail.text = "Saved account. Enter its password to reconnect. Endpoint and username keep their original identity; stopping sync keeps saved account and recovery data."
                    } else detail.text = "Sign-in is encrypted on this tablet. A failed check does not save a new account."
                }, { detail.text = "Saved sign-in could not be read. Account and recovery data were kept; no new account was created." })
            }
        }
        return sheet
    }

    fun recovery(context: Context, app: NotesApplication, recovered: () -> Unit): Kit.Sheet {
        val detail = Kit.text(context, "Inspect an interrupted transfer without changing notebook copies.", secondary = true)
        val token = SyncCancellation(); val original = LibrarySync.sourceIdentity(app)
        var alive = true; var busy = false; var observed: WebDavRecovery? = null
        lateinit var sheet: Kit.Sheet
        lateinit var finish: android.widget.TextView
        fun run(reconcile: Boolean) {
            if (!alive || busy || LibrarySync.sourceIdentity(app) != original) return
            val attempt = observed?.attempt?.id
            if (reconcile && attempt == null) return
            busy = true; sheet.actionButton?.isEnabled = false; finish.isEnabled = false
            detail.text = if (reconcile) "Checking the exact transfer again…" else "Inspecting transfer…"
            app.io.execute {
                val result = runCatching {
                    val source = WebDavSource.forApp(app)
                    check(source.identity == original) { "Sync source changed" }
                    if (reconcile) source.reconcile(attempt!!, token) else source.inspectRecovery(token)
                }
                app.main.post {
                    if (alive && LibrarySync.sourceIdentity(app) == original) {
                        busy = false; sheet.actionButton?.isEnabled = true
                        result.fold({ recovery ->
                            observed = recovery
                            if (reconcile) { sheet.dismiss(); recovered() }
                            else if (recovery == null) detail.text = "No interrupted transfer is recorded. You can sync normally."
                            else {
                                val safe = recovery.outcome != WebDavRecovery.Outcome.CONFLICT && recovery.stageState != WebDavRecovery.StageState.CHANGED
                                detail.text = if (safe) "The server copy can be checked against the recorded transfer. Finish recovery checks it again and removes only a verified temporary upload before acknowledging the transfer. Notebook copies are not overwritten."
                                    else "The server or temporary copy differs from the recorded transfer. Recovery stays blocked; notebook copies and the transfer record were kept."
                                finish.isEnabled = safe
                            }
                        }, { detail.text = explain(it) })
                    }
                }
            }
        }
        sheet = Kit.Sheet(context, "WebDAV recovery", "Inspect", cancel = "Done") { run(false); false }
        finish = Kit.button(context, "Verify and finish recovery", Kit.Button.TINTED) { run(true) }.apply { isEnabled = false }
        sheet.add(detail); sheet.add(finish)
        sheet.dialog.setOnDismissListener { alive = false; token.cancel() }
        return sheet.show()
    }

    private fun explain(error: Throwable): String = when (error) {
        is WebDavAccountStore.Failure -> when (error.problem) {
            WebDavAccountStore.Problem.INVALID_INPUT -> "Check the HTTPS folder address and sign-in fields."
            WebDavAccountStore.Problem.IDENTITY_CHANGE -> "This tablet already has a different saved WebDAV account. Its identity and recovery data were kept."
            else -> "Saved sign-in is unavailable or busy. Existing account data was kept. Try again later."
        }
        is WebDavHttpClient.Failure -> when (error.problem) {
            WebDavHttpClient.Problem.ACCESS -> "The server refused access. Check your sign-in and folder permissions."
            WebDavHttpClient.Problem.UNSUPPORTED -> "This server did not pass the required safe-sync checks. No new account was saved."
            WebDavHttpClient.Problem.RECOVERY_REQUIRED, WebDavHttpClient.Problem.UNCERTAIN, WebDavHttpClient.Problem.CHANGED -> "The transfer needs another inspection. Notebook copies and recovery data were kept."
            WebDavHttpClient.Problem.CANCELLED -> "Connection cancelled."
            else -> "The server or private storage is unavailable. Check the connection and try again; notebook copies were kept."
        }
        else -> "The operation could not finish. Existing notebooks and account data were kept."
    }
}
