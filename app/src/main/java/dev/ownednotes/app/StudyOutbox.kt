package dev.ownednotes.app

import android.util.AtomicFile
import dev.ownednotes.core.Notebook
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Local, explicit ink-only delivery. Call on app.io; no networking or native notebook copies. */
object StudyOutbox {
    private val identity = Regex("[A-Za-z0-9-]{1,80}")

    fun publish(source: Notebook, cache: File, directory: File): Long {
        require(identity.matches(source.id)) { "Notebook identity is not supported for study delivery" }
        check(directory.mkdirs() || directory.isDirectory) { "Study storage is unavailable" }
        val receiptFile = File(directory, "${source.id}.ready.json")
        // Fail closed on damaged receipts, rather than silently resetting the revision.
        val receiptStorage = AtomicFile(receiptFile)
        val old = if (receiptFile.exists() || File(receiptFile.path + ".bak").exists())
            receiptStorage.openRead().use { JSONObject(it.reader().readText()).getLong("revision") } else 0L
        check(old >= 0 && old < Long.MAX_VALUE) { "Study revision is invalid" }
        var revision = maxOf(System.currentTimeMillis(), old + 1)
        // A crash may leave an uncommitted ZIP. Never reuse or overwrite it.
        while (File(directory, "${source.id}--$revision.zip").exists()) {
            check(revision < Long.MAX_VALUE); revision++
        }
        val staged = StudyInkExport.create(source, cache)
        val target = File(directory, "${source.id}--$revision.zip")
        val pending = File.createTempFile(".study-", ".tmp", directory)
        try {
            pending.outputStream().use { output ->
                staged.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            check(pending.renameTo(target)) { "Cannot finish study ZIP" }
            val sha = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
            }
            val receipt = JSONObject().put("version", 1).put("notebookId", source.id)
                .put("revision", revision).put("file", target.name).put("bytes", target.length())
                .put("sha256", sha.digest().joinToString("") { "%02x".format(it) })
            val atomic = receiptStorage
            val output = atomic.startWrite()
            try { output.write(receipt.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
            // Retain the previous ZIP for an in-flight USB copy. No other book's files are touched.
            directory.listFiles()?.filter { it.name.matches(Regex("${Regex.escape(source.id)}--[0-9]+\\.zip")) }
                ?.sortedByDescending { it.name.substringAfterLast("--").removeSuffix(".zip").toLongOrNull() ?: 0L }
                ?.drop(2)?.forEach { it.delete() }
            return revision
        } finally { staged.delete(); pending.delete() }
    }
}
