package dev.ownednotes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

/**
 * P139 (F170, with F167 and F174–F180): two-way sync of a library with a shared folder (for example one inside a OneDrive
 * or Google Drive folder on each device). The folder holds `<id>.inknote` packages, byte for byte as the library stores
 * them, and one shared [SyncDeletionIndex] for deletion/restoration metadata. Legacy per-notebook
 * deletion markers migrate only after the consolidated index is written and verified.
 *
 * Safety: nothing is ever lost on either side. A notebook is replaced only by a copy whose checksum and package were
 * checked, and the replaced local package is kept in the versions folder first. A notebook deleted elsewhere goes to the
 * local trash, and only when a deletion note says so (a folder that merely looks empty deletes nothing). Two devices
 * editing the same notebook keep both: the other device's version is added as a copy.
 */
object FolderSync {
    /** The size and time a file had when its checksum was taken, so unchanged files are not read again. */
    data class Seen(val size: Long, val modified: Long, val sha: String)
    /** What the last sync agreed: [base] is the shared checksum; both observations null retain a deletion agreement. */
    data class Record(val base: String, val local: Seen?, val remote: Seen?)
    data class Stat(val size: Long, val modified: Long)

    /** The shared folder. [write] creates or replaces [name] and returns its new size and time. */
    interface Folder {
        fun list(): Map<String, Stat>
        fun read(name: String): InputStream
        fun write(name: String, from: File): Stat
        fun delete(name: String)
    }

    /** Optional provider capability; existing flat sources remain valid and unchanged. */
    interface NestedFolder : Folder {
        fun child(name: String, create: Boolean = false): NestedFolder
    }
    fun requireChildName(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9._-]{1,80}")) && name != "." && name != "..") { "Invalid sync namespace name" }
    }

    class DirectoryFolder(private val directory: File) : NestedFolder {
        override fun child(name: String, create: Boolean): NestedFolder {
            requireChildName(name)
            val root = directory.toPath()
            if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Sync parent directory is missing or linked")
            val path = root.resolve(name)
            if (create && !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(path) } catch (_: java.nio.file.FileAlreadyExistsException) { }
            }
            if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Sync child directory is missing, linked or unavailable")
            return DirectoryFolder(path.toFile())
        }

        override fun list(): Map<String, Stat> = directory.apply { mkdirs() }.listFiles().orEmpty().filter { it.isFile }.associate { it.name to Stat(it.length(), it.lastModified()) }
        override fun read(name: String): InputStream = File(directory, name).inputStream()
        override fun write(name: String, from: File): Stat { directory.mkdirs(); val f = File(directory, name); from.copyTo(f, overwrite = true); return Stat(f.length(), f.lastModified()) }
        override fun delete(name: String) {
            val file = File(directory, name)
            if (file.exists() && !file.delete()) throw IOException("Cannot remove the sync file")
        }
    }

    enum class Action { NONE, UPLOAD, DOWNLOAD, CONFLICT, TRASH_LOCAL, DELETE_REMOTE, FORGET }

    /**
     * The decision for one notebook. [local] and [remote] are checksums (null when absent), [trashed] says the local copy
     * is in the trash, [deleted] that the folder holds a deletion note, [base] what the last sync agreed (null if never).
     */
    fun decide(local: String?, trashed: Boolean, remote: String?, deleted: Boolean, base: String?): Action = when {
        local != null && remote != null -> when {
            local == remote -> Action.NONE
            base == local -> Action.DOWNLOAD
            base == remote -> Action.UPLOAD
            else -> Action.CONFLICT
        }
        // Deleted on another device, and not changed here since: to the trash. Edited here, or no note: keep it, send it.
        local != null -> if (deleted && base == local) Action.TRASH_LOCAL else Action.UPLOAD
        // Deleted here, and not changed on the other device since: delete there too. Changed there, or never here: bring it.
        remote != null -> if (trashed && base == remote) Action.DELETE_REMOTE else Action.DOWNLOAD
        else -> Action.FORGET
    }

    enum class ProblemKind { ACCESS, SPACE, CONTENT, UNAVAILABLE }
    data class Problem(val id: String, val title: String, val kind: ProblemKind)

    /** A provider can classify a sanitized failure without exposing server bodies or private paths. */
    interface ClassifiedFailure { val syncProblemKind: ProblemKind }

    /** Provider failures are classified locally; raw exception messages/paths are not part of the user report. */
    fun problemKind(error: Throwable): ProblemKind {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<ClassifiedFailure>().firstOrNull()?.let { return it.syncProblemKind }
        if (causes.any { it is SecurityException || it is java.nio.file.AccessDeniedException }) return ProblemKind.ACCESS
        if (causes.any { cause ->
                val reason = (cause as? java.nio.file.FileSystemException)?.reason ?: cause.message.orEmpty()
                reason.contains("ENOSPC", ignoreCase = true) || reason.contains("no space left", ignoreCase = true) ||
                    reason.contains("quota exceeded", ignoreCase = true) || reason.contains("EDQUOT", ignoreCase = true)
            }) return ProblemKind.SPACE
        if (causes.any { it is IllegalArgumentException || it is IllegalStateException || it is java.util.zip.ZipException }) return ProblemKind.CONTENT
        return ProblemKind.UNAVAILABLE
    }

    data class Outcome(
        val uploaded: List<String>, val downloaded: List<String>, val conflicts: List<String>,
        val trashed: List<String>, val deletedThere: List<String>, val waiting: List<String>, val failed: List<String>,
        val inSync: Int, val state: Map<String, Record>, val problems: List<Problem> = emptyList(), val cancelled: Boolean = false,
        val notKeptOffline: Int = 0,
    ) {
        val changed get() = uploaded.size + downloaded.size + conflicts.size + trashed.size + deletedThere.size
        val message: String get() = buildList {
            if (changed == 0 && failed.isEmpty() && waiting.isEmpty() && !cancelled) add(
                if(notKeptOffline==0) "Synced: everything is up to date ($inSync notebooks)"
                else "Sync checked local notebooks ($inSync up to date)")
            else {
                if (cancelled) add("sync stopped; completed transfers were kept")
                if (uploaded.isNotEmpty()) add("sent ${count(uploaded)}")
                if (downloaded.isNotEmpty()) add("received ${count(downloaded)}")
                if (conflicts.isNotEmpty()) add("${count(conflicts)} changed on two devices, both versions kept: ${conflicts.joinToString()}")
                if (trashed.isNotEmpty()) add("moved ${count(trashed)} deleted on another device to the trash")
                if (deletedThere.isNotEmpty()) add("removed ${count(deletedThere)} you deleted from the sync folder")
                if (waiting.isNotEmpty()) add("${count(waiting)} waiting until closed: ${waiting.joinToString()}")
                if (failed.isNotEmpty()) add("${count(failed)} could not sync: ${failed.joinToString()}")
            }
            if(notKeptOffline>0) add("$notKeptOffline ${if(notKeptOffline==1) "notebook is" else "notebooks are"} not kept offline; download from the original folder when needed")
        }.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        private fun count(names: List<String>) = if (names.size == 1) "1 notebook" else "${names.size} notebooks"
    }

    private class LocalChangedBeforeUpload:IOException("Saved notebook moved or changed before upload")
    /** With nothing here to send instead, the file itself is the problem: the folder was reached, so this must not read as "could not be reached". */
    private class CutShortCopy:IOException("The folder's copy of this notebook was cut short"), ClassifiedFailure {
        override val syncProblemKind = ProblemKind.CONTENT
    }
    private val ID = Regex("[a-zA-Z0-9-]{1,80}")
    private val ZIP_START = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private const val PACKAGE = ".inknote"
    private const val DELETED = ".deleted"
    const val KEEP_VERSIONS = 5

    fun sha256(stream: InputStream): String = stream.use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Syncs the library in [directory] with [folder]. [state] is the previous outcome's state for this folder (empty the
     * first time). Notebooks in [busy] (open in the editor) are sent but never replaced or trashed; they wait for a later
     * sync. [titles] name notebooks in the outcome. Call on the thread that saves notebooks.
     */
    fun run(directory: File, folder: Folder, state: Map<String, Record>, busy: Set<String> = emptySet(), titles: Map<String, String> = emptyMap(), now: () -> Long = System::currentTimeMillis, cancelled: () -> Boolean = { false }, sourceKey: String = "default-folder", attachmentDirectory: File? = null): Outcome {
        if (cancelled()) return Outcome(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, state, cancelled = true)
        val library = NotebookLibrary(directory,attachmentDirectory)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create notebook directory")
        // Their origin can differ from the current destination. Never silently fetch them or
        // interpret missing bytes as deletion, including after Trash has been emptied.
        val released = OfflineNotebooks(directory).released().mapTo(HashSet()) { it.id }
        val versions = File(directory, NotebookLibrary.VERSIONS)
        fun file(id: String) = File(directory, "$id$PACKAGE")
        val localFiles = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(PACKAGE) && it.nameWithoutExtension.matches(ID) }.associateBy { it.nameWithoutExtension }
        val trashedIds = File(directory, "trash").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(PACKAGE) }.map { it.nameWithoutExtension }.toSet() +
            File(directory,NotebookLibrary.DELETIONS).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(DELETED) && it.name.removeSuffix(DELETED).matches(ID) }.map { it.name.removeSuffix(DELETED) }
        val listing = folder.list()
        val remoteStats = listing.filterKeys { it.endsWith(PACKAGE) && it.removeSuffix(PACKAGE).matches(ID) }.mapKeys { it.key.removeSuffix(PACKAGE) }
        val deletionIndex = SyncDeletionIndex.Session(directory, folder, listing, sourceKey, cancelled, released)

        val next = state.toMutableMap()
        val uploaded = mutableListOf<String>(); val downloaded = mutableListOf<String>(); val conflicts = mutableListOf<String>()
        val trashed = mutableListOf<String>(); val deletedThere = mutableListOf<String>(); val waiting = mutableListOf<String>(); val failed = mutableListOf<String>()
        val problems = mutableListOf<Problem>()
        var inSync = 0
        fun name(id: String) = titles[id] ?: id

        fun localSeen(id: String): Seen? {
            val f = localFiles[id] ?: return null
            val old = state[id]?.local
            if (old != null && old.size == f.length() && old.modified == f.lastModified()) return old
            return Seen(f.length(), f.lastModified(), sha256(f.inputStream()))
        }
        fun remoteSeen(id: String): Seen? {
            val s = remoteStats[id] ?: return null
            val old = state[id]?.remote
            // A folder that reports no times is read every time rather than trusted.
            if (old != null && s.modified != 0L && old.size == s.size && old.modified == s.modified) return old
            return Seen(s.size, s.modified, sha256(folder.read("$id$PACKAGE")))
        }
        /** Sends [id]'s package, checks the copy, and clears any deletion note. */
        fun upload(id: String, seen: Seen): Seen {
            var snapshot:File?=null
            try {
                // Pin the immutable native generation under the gate; large disk copies and network IO hold no gate.
                val input=NotebookReadGate.access(directory,id) {
                    val source=file(id)
                    if(!source.isFile)throw LocalChangedBeforeUpload()
                    source.inputStream()
                }
                val staged=input.use {pinned->
                    File.createTempFile(".upload-$id-", ".pending",directory).also {copy->
                        snapshot=copy
                        FileOutputStream(copy).use {out->pinned.copyTo(out);out.fd.sync()}
                    }
                }
                if(sha256(staged.inputStream())!=seen.sha)throw LocalChangedBeforeUpload()
                val stat = folder.write("$id$PACKAGE", staged)
                check(sha256(folder.read("$id$PACKAGE")) == seen.sha) { "the copy in the folder differs" }
                NotebookReadGate.access(directory,id) {if(file(id).isFile)deletionIndex.restored(id)}
                return Seen(stat.size, stat.modified, seen.sha)
            } finally {snapshot?.delete()}
        }
        /**
         * P558: an upload killed part way leaves the start of a package, which has no ZIP directory at its end. That is no
         * version of anything, so it may be replaced. A whole package this version cannot read may be newer: it stays an error.
         * So does a file that never began as a package, because that isn't ours to overwrite. Empty counts: killed before the first byte.
         */
        fun cutShort(pending: File): Boolean {
            val start = pending.inputStream().use { input -> ByteArray(ZIP_START.size).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
            if (!start.contentEquals(ZIP_START.copyOf(start.size))) return false
            return try { java.util.zip.ZipFile(pending).close(); false }
            catch (_: java.util.zip.ZipException) { runCatching { library.readPackage(pending) }.isFailure }
        }
        /** Copies the folder's package into a pending file beside the library and checks it is [seen] and a readable notebook. */
        fun fetch(id: String, seen: Seen): Pair<File, Notebook> {
            val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
            try {
                FileOutputStream(pending).use { out -> folder.read("$id$PACKAGE").use { it.copyTo(out) }; out.fd.sync() }
                check(sha256(pending.inputStream()) == seen.sha) { "the folder's copy changed while reading" }
                if (cutShort(pending)) throw CutShortCopy()
                val notebook = library.readPackage(pending)
                check(notebook.id == id) { "the folder's file and notebook identity differ" }
                return pending to notebook
            } catch (e: Throwable) { pending.delete(); throw e }
        }
        fun keepVersion(id: String) {
            val current = file(id).takeIf { it.isFile } ?: return
            val dir = File(versions, id).apply { if (!isDirectory && !mkdirs()) throw IOException("Cannot create the versions folder") }
            current.copyTo(File(dir, "${now()}$PACKAGE"), overwrite = true)
            dir.listFiles().orEmpty().filter { it.name.endsWith(PACKAGE) }.sortedByDescending { it.name.removeSuffix(PACKAGE).toLongOrNull() ?: 0L }.drop(KEEP_VERSIONS).forEach { it.delete() }
        }

        val ids = (localFiles.keys + remoteStats.keys + state.keys + trashedIds).toSortedSet()
        var interrupted = false
        data class Plan(val id: String, val local: Seen?, val remote: Seen?, val action: Action)
        val plans = mutableListOf<Plan>()
        for (id in ids) {
            if (cancelled()) { interrupted = true; break }
            if (id in released) continue
            runCatching {
                val local = localSeen(id); val remote = remoteSeen(id)
                val previous = state[id]
                val restored = local != null && id !in trashedIds && previous != null &&
                    previous.local == null && previous.remote == null
                val deletion = deletionIndex.entry(id)?.takeIf { it.deleted && it.sha256 != null }
                // A completed remote Restore is a newer decision than the local Trash copy left
                // by an earlier sync. Do not turn that old copy into a fresh deletion request.
                val restoredRemotely = local == null && remote != null && previous != null &&
                    previous.local == null && previous.remote == null &&
                    deletionIndex.entry(id)?.deleted == false
                val action = if (restoredRemotely) Action.DOWNLOAD else if (restored) {
                    // Explicit Restore clears local deletion intent. Re-publish it rather than
                    // treating the cloud marker as a fresh deletion; keep both if another
                    // device edited its copy while this notebook was in Trash.
                    if (remote == null || remote.sha == local!!.sha) Action.UPLOAD else Action.CONFLICT
                } else if (deletion != null && remote?.sha == deletion.sha256) {
                    when {
                        local == null -> Action.DELETE_REMOTE
                        local.sha == deletion.sha256 -> Action.TRASH_LOCAL
                        else -> Action.UPLOAD
                    }
                } else if (deletion != null && remote == null && local?.sha == deletion.sha256) Action.TRASH_LOCAL
                else decide(local?.sha, id in trashedIds, remote?.sha, deletionIndex.isDeleted(id), previous?.base)
                // An open notebook can still be sent, so a cut-short copy of it need not wait until it is closed.
                val openAndCutShort = id in busy && local != null && action in setOf(Action.DOWNLOAD, Action.CONFLICT) && try {
                    val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
                    try {
                        FileOutputStream(pending).use { out -> folder.read("$id$PACKAGE").use { it.copyTo(out) }; out.fd.sync() }
                        cutShort(pending)
                    } finally { pending.delete() }
                } catch (_: IOException) { false }
                if (openAndCutShort) { plans += Plan(id, local, remote, Action.UPLOAD); return@runCatching }
                if (id in busy && action in setOf(Action.DOWNLOAD, Action.CONFLICT, Action.TRASH_LOCAL)) { waiting += name(id); return@runCatching }
                plans += Plan(id, local, remote, action)
            }.onFailure { if(it is LocalChangedBeforeUpload)waiting+=name(id) else {failed += name(id); problems += Problem(id, name(id), problemKind(it))} }
        }
        if (interrupted) return Outcome(uploaded, downloaded, conflicts, trashed, deletedThere, waiting, failed, inSync, next, problems, true, released.size)
        // Batch all deletion intents before removing any packages. No per-notebook cloud files.
        plans.filter { it.action == Action.DELETE_REMOTE }.forEach { deletionIndex.deleted(it.id, it.remote!!.sha) }
        val plannedMetadata = plans.associate { it.id to deletionIndex.entry(it.id) }
        deletionIndex.publish()
        if (plannedMetadata.any { (id, entry) -> deletionIndex.entry(id) != entry })
            throw IOException("Sync deletion metadata changed during planning; retry sync.")
        deletionIndex.migrateLegacy()
        val removing = plans.filter { it.action == Action.DELETE_REMOTE }.map { it.id }
        deletionIndex.verifyDeleted(removing)
        for ((id, local, remote, action) in plans) {
            if (cancelled()) { interrupted = true; break }
            runCatching {
                when (action) {
                    Action.NONE -> { next[id] = Record(local!!.sha, local, remote); inSync++ }
                    Action.UPLOAD -> { next[id] = Record(local!!.sha, local, upload(id, local)); uploaded += name(id) }
                    Action.DOWNLOAD -> {
                        remote!!
                        val (pending, notebook) = try { fetch(id, remote) } catch (cut: CutShortCopy) {
                            if (local == null) throw cut
                            next[id] = Record(local.sha, local, upload(id, local)); uploaded += name(id); return@runCatching
                        }
                        val committed = try {
                            NotebookReadGate.access(directory,id) {
                                // Opening may start while provider.read is blocked, after the original plan was made.
                                if(id in busy) false else {
                                    keepVersion(id)
                                    Files.move(pending.toPath(), file(id).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                                    true
                                }
                            }
                        } finally { pending.delete() }
                        if(!committed){waiting+=name(id);return@runCatching}
                        val f = file(id)
                        next[id] = Record(remote.sha, Seen(f.length(), f.lastModified(), remote.sha), remote)
                        deletionIndex.restored(id)
                        downloaded += notebook.title
                    }
                    Action.CONFLICT -> {
                        // Keep both: the other device's version becomes a new notebook here (and in the folder); this one is sent.
                        val (pending, notebook) = try { fetch(id, remote!!) } catch (_: CutShortCopy) {
                            next[id] = Record(local!!.sha, local, upload(id, local)); uploaded += name(id); return@runCatching
                        }
                        if(id in busy){pending.delete();waiting+=name(id);return@runCatching}
                        val copy = try { library.importCopy(notebook.copy(title = "${notebook.title.take(470)} (other device)")) } finally { pending.delete() }
                        val copyFile = file(copy.id)
                        val copySeen = Seen(copyFile.length(), copyFile.lastModified(), sha256(copyFile.inputStream()))
                        next[copy.id] = Record(copySeen.sha, copySeen, upload(copy.id, copySeen))
                        next[id] = Record(local!!.sha, local, upload(id, local))
                        conflicts += name(id)
                    }
                    Action.TRASH_LOCAL -> {
                        val committed=NotebookReadGate.access(directory,id){if(id in busy)false else {library.trash(id);true}}
                        if(committed){next[id]=Record(local!!.sha,null,null);trashed+=name(id)}else waiting+=name(id)
                    }
                    Action.DELETE_REMOTE -> {
                        folder.delete("$id$PACKAGE")
                        val after = folder.list()
                        if ("$id$PACKAGE" in after || SyncDeletionIndex.FILE_NAME !in after)
                            throw IOException("The sync folder has not confirmed the deletion")
                        // A provider's queued upload or stale replica can reintroduce the same bytes later.
                        // Keep the last agreement while local deletion intent exists, so retry removes
                        // that exact stale version instead of downloading it as a new notebook.
                        // A remotely edited version still takes the existing DOWNLOAD path.
                        next[id] = Record(remote!!.sha, null, null); deletedThere += name(id)
                    }
                    Action.FORGET -> {
                        if ((id in trashedIds || deletionIndex.isDeleted(id)) && state[id] != null)
                            next[id] = state.getValue(id).copy(local = null, remote = null)
                        else next.remove(id)
                    }
                }
            }.onFailure { if(it is LocalChangedBeforeUpload)waiting+=name(id) else {failed += name(id); problems += Problem(id, name(id), problemKind(it))} }
        }
        // A failed transfer may leave the provider in explicit recovery mode. Preserve the partial
        // outcome/agreement instead of re-entering it for metadata and throwing away that result.
        // Session.change already retains restore intents locally; the next run publishes before
        // transfers. Deletion intents were verified before any package removal above.
        if (!interrupted && failed.isEmpty()) deletionIndex.publish()
        return Outcome(uploaded, downloaded, conflicts, trashed, deletedThere, waiting, failed, inSync, next, problems, interrupted,released.size)
    }

    /** One line per notebook, so the state can live in a small text file. */
    fun encode(state: Map<String, Record>): String = state.toSortedMap().entries.joinToString("\n") { (id, r) ->
        fun s(x: Seen?) = x?.let { "${it.size}\t${it.modified}\t${it.sha}" } ?: "-\t-\t-"
        "$id\t${r.base}\t${s(r.local)}\t${s(r.remote)}"
    }

    fun decode(text: String): Map<String, Record> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t').takeIf { it.size == 8 } ?: return@mapNotNull null
        fun s(i: Int) = if (p[i] == "-") null else runCatching { Seen(p[i].toLong(), p[i + 1].toLong(), p[i + 2]) }.getOrNull()
        p[0].takeIf { it.matches(ID) }?.let { it to Record(p[1], s(2), s(5)) }
    }.toMap()
}
