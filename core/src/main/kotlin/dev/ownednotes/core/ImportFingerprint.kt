package dev.ownednotes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P123 (F196): a fingerprint of a notebook's title and content that ignores the identities the app gives notebooks,
 * pages and objects, so the same file imported twice, or a notebook's own export, is recognised as already present.
 * Identities are renumbered in the order they appear, so references between objects (groups, layers) still count.
 */
object ImportFingerprint {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun of(notebook: Notebook): String {
        val names = HashMap<String, String>()
        fun normal(element: JsonElement, key: String?): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (name, value) -> normal(value, name) })
            is JsonArray -> JsonArray(element.map { normal(it, key) })
            is JsonPrimitive -> if (element.isString && (key == "id" || uuid.matches(element.content)))
                JsonPrimitive(names.getOrPut(element.content) { "#${names.size}" }) else element
        }
        val pages = JsonArray(notebook.pages.map { normal(Json.encodeToJsonElement(Page.serializer(), it), null) })
        val text = buildString {
            append(notebook.title).append('\n').append(pages).append('\n')
            notebook.assets.forEach { append("pdf:").append(it.id).append('\n') }
            notebook.imageAssets.forEach { append("image:").append(it.id).append('\n') }
            notebook.attachmentAssets.forEach { append("file:").append(it.id).append('\n') }
        }
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    /** The id of a readable library notebook with the same fingerprint as [incoming], or null. Trashed notebooks never match. */
    fun findIn(library: NotebookLibrary, incoming: Notebook): String? {
        val print = of(incoming)
        return library.entries().filter { it.error == null && it.title == incoming.title && it.pageCount == incoming.pages.size }
            .firstOrNull { entry -> runCatching { of(library.read(entry.id)) == print }.getOrDefault(false) }?.id
    }
}
