package dev.ownednotes.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Live sync (docs/LIVE-SYNC-PLAN.md): end-to-end encryption of change sets and the sync code that carries the key.
 * The server only ever sees [seal]ed bytes and a [token] derived from the key; it cannot read notes.
 */
object LiveCrypto {
    const val CODE_PREFIX = "scribella-sync:1:"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()
    private val url = Base64.getUrlEncoder().withoutPadding()

    data class SyncCode(val server: String, val key: ByteArray) {
        /** What the user copies to their other devices: `scribella-sync:1:<server>#<key>`. */
        val text get() = "$CODE_PREFIX$server#${url.encodeToString(key)}"
        override fun equals(other: Any?) = other is SyncCode && other.server == server && other.key.contentEquals(key)
        override fun hashCode() = server.hashCode() * 31 + key.contentHashCode()
    }

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /**
     * The server address as the user typed it, cleaned to `https://host[/path]` (a bare host gets https://; http:// is kept
     * only for a local test server). Null when it is not a web address.
     */
    fun server(typed: String): String? {
        var text = typed.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() } || '#' in text) return null
        if (!text.startsWith("https://") && !text.startsWith("http://")) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.query != null) return null
        return text
    }

    /** Reads a sync code (spaces and line breaks from copying are ignored); null when it is not one. */
    fun parse(code: String): SyncCode? {
        val text = code.filterNot { it.isWhitespace() }
        if (!text.startsWith(CODE_PREFIX)) return null
        val server = server(text.removePrefix(CODE_PREFIX).substringBefore('#')) ?: return null
        val key = runCatching { Base64.getUrlDecoder().decode(text.substringAfter('#', "")) }.getOrNull() ?: return null
        return if (key.size == KEY_BYTES) SyncCode(server, key) else null
    }

    private fun sha256(text: String, key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(text.toByteArray(Charsets.UTF_8)); update(key) }.digest()

    /** The server password: proves this device holds the key without giving the key away. */
    fun token(key: ByteArray): String = sha256("scribella-live-token", key).joinToString("") { "%02x".format(it) }

    /** gzip, then AES-256-GCM with a random nonce: nonce ‖ ciphertext+tag. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return nonce + cipher.doFinal(packed)
    }

    /** Opens [seal]ed bytes; throws when the key is wrong or the bytes were changed. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES + 16) { "Too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, NONCE_BYTES)) }
        val packed = cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        return GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
    }

    fun toText(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun fromText(text: String): ByteArray = Base64.getDecoder().decode(text)
}
