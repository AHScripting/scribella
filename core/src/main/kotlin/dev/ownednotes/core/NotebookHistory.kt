package dev.ownednotes.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Explicit local history maintenance. Use the library's serial IO owner; no automatic retention deletion. */
class NotebookHistory(private val directory: File) {
    data class Item(val bytes: Long, val sha256: String)
    data class Snapshot(val notebookId: String, val items: Map<String, Item>) {
        val bytes: Long get() = items.values.fold(0L) { sum, item -> Math.addExact(sum, item.bytes) }
    }
    data class Purge(val deleted: Int, val skipped: Int)
    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) { null }
    private fun folder(id: String): File? {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid notebook identity" }
        val history = File(directory, NotebookLibrary.VERSIONS)
        val notebook = File(history, id)
        for (part in listOf(directory, history, notebook)) {
            val info = attributes(part) ?: return null
            if (!info.isDirectory) throw IOException("Earlier-version storage is not a plain directory")
        }
        return notebook
    }
    private fun files(folder: File): List<File> = Files.newDirectoryStream(folder.toPath()).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".inknote") }.map { path ->
            if (attributes(path.toFile())?.isRegularFile != true) throw IOException("Earlier version is not a plain file")
            path.toFile()
        }.sortedBy { it.name }
    }
    private fun item(file: File): Item {
        if (attributes(file)?.isRegularFile != true) throw IOException("Earlier version is unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count); bytes = Math.addExact(bytes, count.toLong())
            }
        }
        return Item(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }
    fun snapshot(id: String): Snapshot {
        val folder = folder(id) ?: return Snapshot(id, emptyMap())
        return Snapshot(id, files(folder).associate { it.name to item(it) })
    }
    /** Only exact confirmed bytes may be unlinked. New/changed entries survive; no recursive deletion. */
    fun purge(confirmed: Snapshot): Purge {
        require(confirmed.items.all { (name, value) ->
            name.endsWith(".inknote") && File(name).name == name && '/' !in name && '\\' !in name &&
                value.bytes >= 0 && value.sha256.matches(Regex("[a-f0-9]{64}"))
        }) { "Invalid earlier-version confirmation" }
        val folder = folder(confirmed.notebookId) ?: return Purge(0, confirmed.items.size)
        val current = files(folder).map { it.name }.toSet()
        var deleted = 0
        var skipped = (current - confirmed.items.keys).size
        for ((name, expected) in confirmed.items) {
            val file = File(folder, name)
            val removed = runCatching {
                if (item(file) != expected) false else { Files.delete(file.toPath()); true }
            }.getOrDefault(false)
            if (removed) deleted++ else skipped++
        }
        return Purge(deleted, skipped)
    }
}
