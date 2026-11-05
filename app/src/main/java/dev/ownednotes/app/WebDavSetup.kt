package dev.ownednotes.app

import dev.ownednotes.core.SyncCancellation
import dev.ownednotes.core.WebDavEndpoint
import dev.ownednotes.core.WebDavHttpClient

/** Explicit setup action, on IO. Failed drafts do not freeze a new account to a mistyped endpoint. */
internal class WebDavSetup(private val store: WebDavAccountStore,
    private val verify: (WebDavEndpoint, String?, String?, SyncCancellation) -> Unit = { endpoint, username, password, token ->
        WebDavHttpClient.open(endpoint, username, password).use { client ->
            token.register { client.close() }.use {
                if (token.isCancelled) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CANCELLED)
                client.verifyWriteSupport()
                client.list()
            }
        }
    }) {
    fun connect(address: String, username: String?, password: String?, token: SyncCancellation): WebDavAccountStore.Account {
        val endpoint = store.validateDraft(address, username, password)
        val previous = store.load()
        if (previous != null && (previous.endpoint.collection != endpoint.collection || previous.username != username))
            throw WebDavAccountStore.Failure(WebDavAccountStore.Problem.IDENTITY_CHANGE)
        checkRunning(token)
        verify(endpoint, username, password, token)
        checkRunning(token)
        return store.save(endpoint.collection.toString(), username, password)
    }
    private fun checkRunning(token: SyncCancellation) {
        if (token.isCancelled) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CANCELLED)
    }
}
