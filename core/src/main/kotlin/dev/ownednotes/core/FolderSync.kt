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
    /** [detail] (P710): where it stopped, as [problemDetail] gives it: fixed step words and error type names only. */
    data class Problem(val id: String, val title: String, val kind: ProblemKind, val detail: String? = null)

    /**
     * P710: a fixed label naming the part of the work in progress when a failure happened. It is attached as a suppressed
     * exception, so the failure keeps its own type and causes. Only fixed words go in it: never a name, path or message.
     */
    class Step(label: String) : Exception(label, null, false, false)

    /** P710: the error's type names and any [Step] labels in its causes: safe to show, as they hold no names or paths. */
    fun failureTypes(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        val types = causes.map { it.javaClass.simpleName.ifEmpty { "Exception" } }
        val steps = causes.flatMap { cause -> cause.suppressed.filterIsInstance<Step>().mapNotNull { it.message } }
        return (types + steps).distinct().joinToString(", ").take(300)
    }

    /** P710: "while sending (IOException, ...)", for telling where a notebook's sync stopped. */
    fun problemDetail(step: String, error: Throwable) = "while $step (${failureTypes(error)})"

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
        /** P699: changed on both devices, held until the user chooses ([resolve]); neither version was changed. */
        val choices: List<String> = emptyList(),
        /** P699: open here, unchanged here, newer on the other device: held so the app can take it in ([Choice.TAKE_NEWER]). */
        val newer: List<String> = emptyList(),
        /** B79: changed on both devices with no clash, combined here and sent (also in [uploaded]); the other device should fetch. */
        val combined: List<String> = emptyList(),
        /** B79: the ids of [combined], for telling the other devices to fetch them. */
        val combinedIds: List<String> = emptyList(),
        /** P713: changed on both devices with no clash but open here: held, and combined by the app once it can change the notebook. */
        val combineLater: List<String> = emptyList(),
        /** B73 (P723): changed here only by instant sync, so left for the device that made the change to send. */
        val deferred: List<String> = emptyList(),
    ) {
        val changed get() = uploaded.size + downloaded.size + conflicts.size + trashed.size + deletedThere.size
        val message: String get() = buildList {
            if (changed == 0 && failed.isEmpty() && waiting.isEmpty() && choices.isEmpty() && newer.isEmpty() && combineLater.isEmpty() && deferred.isEmpty() && !cancelled) add(
                if(notKeptOffline==0) "Synced: everything is up to date ($inSync notebooks)"
                else "Sync checked local notebooks ($inSync up to date)")
            else {
                if (cancelled) add("sync stopped; completed transfers were kept")
                if (uploaded.isNotEmpty()) add("sent ${count(uploaded)}")
                if (downloaded.isNotEmpty()) add("received ${count(downloaded)}")
                if (conflicts.isNotEmpty()) add("${count(conflicts)} changed on two devices, both versions kept: ${conflicts.joinToString()}")
                if (combined.isNotEmpty()) add("combined changes from both devices in ${combined.joinToString()}")
                if (choices.isNotEmpty()) add("${count(choices)} changed on both devices, choose what to keep: ${choices.joinToString()}")
                if (newer.isNotEmpty()) add("a newer version of ${newer.joinToString()} came from your other device")
                if (combineLater.isNotEmpty()) add("combining changes from both devices in ${combineLater.joinToString()}")
                if (trashed.isNotEmpty()) add("moved ${count(trashed)} deleted on another device to the trash")
                if (deletedThere.isNotEmpty()) add("removed ${count(deletedThere)} you deleted from the sync folder")
                if (waiting.isNotEmpty()) add("${count(waiting)} waiting until closed: ${waiting.joinToString()}")
                if (deferred.isNotEmpty()) add("${count(deferred)} left for your other device to send: ${deferred.joinToString()}")
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
     * B79: with [holdConflicts] and [autoCombine] (instant sync on), a notebook changed on both devices is combined at once
     * when nothing clashes; only a real clash is held for the user's choice.
     */
    fun run(directory: File, folder: Folder, state: Map<String, Record>, busy: Set<String> = emptySet(), titles: Map<String, String> = emptyMap(), now: () -> Long = System::currentTimeMillis, cancelled: () -> Boolean = { false }, sourceKey: String = "default-folder", attachmentDirectory: File? = null, holdConflicts: Boolean = false, autoCombine: Boolean = false, progress: (String) -> Unit = {}, deferUpload: Set<String> = emptySet()): Outcome {
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
        runCatching { progress("Listing the sync folder") }
        val listing = folder.list()
        val remoteStats = listing.filterKeys { it.endsWith(PACKAGE) && it.removeSuffix(PACKAGE).matches(ID) }.mapKeys { it.key.removeSuffix(PACKAGE) }
        val deletionIndex = SyncDeletionIndex.Session(directory, folder, listing, sourceKey, cancelled, released)

        val next = state.toMutableMap()
        val uploaded = mutableListOf<String>(); val downloaded = mutableListOf<String>(); val conflicts = mutableListOf<String>()
        val choices = mutableListOf<String>()
        val newer = mutableListOf<String>()
        val combined = mutableListOf<String>()
        val combinedIds = mutableListOf<String>()
        val combineLater = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val trashed = mutableListOf<String>(); val deletedThere = mutableListOf<String>(); val waiting = mutableListOf<String>(); val failed = mutableListOf<String>()
        val problems = mutableListOf<Problem>()
        var inSync = 0
        // P710: the part of the current notebook's work in progress, named in its problem if it fails.
        // P713: each step is also shown live in Library sync, after [label] (which notebook, how far through).
        var label = ""
        var step: String by kotlin.properties.Delegates.observable("") { _, _, v -> if (v.isNotEmpty()) runCatching { progress(if (label.isEmpty()) v else "$label \u00b7 $v") } }
        fun name(id: String) = titles[id] ?: id

        fun localSeen(id: String): Seen? {
            val f = localFiles[id] ?: return null
            val old = state[id]?.local
            if (old != null && old.size == f.length() && old.modified == f.lastModified()) return old
            step = "reading this device's copy"
            return Seen(f.length(), f.lastModified(), sha256(f.inputStream()))
        }
        fun remoteSeen(id: String): Seen? {
            val s = remoteStats[id] ?: return null
            val old = state[id]?.remote
            // A folder that reports no times is read every time rather than trusted.
            if (old != null && s.modified != 0L && old.size == s.size && old.modified == s.modified) return old
            step = "reading the folder's copy"
            return Seen(s.size, s.modified, sha256(folder.read("$id$PACKAGE")))
        }
        /** Sends [id]'s package, checks the copy, and clears any deletion note. */
        fun upload(id: String, seen: Seen): Seen {
            var snapshot:File?=null
            try {
                step = "copying it here before sending"
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
                step = "sending"
                val stat = folder.write("$id$PACKAGE", staged)
                step = "reading back what was sent"
                check(sha256(folder.read("$id$PACKAGE")) == seen.sha) { "the copy in the folder differs" }
                step = "recording what was sent"
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
        /**
         * Copies the folder's package into a pending file beside the library and checks it is [seen] and a readable notebook.
         * P715: read page by page ([NotebookPackage.syncSummary]); a full read happens only where the file is taken in.
         */
        fun fetch(id: String, seen: Seen): Pair<File, NotebookPackage.SyncSummary> {
            val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
            try {
                step = "fetching the folder's copy"
                FileOutputStream(pending).use { out -> folder.read("$id$PACKAGE").use { it.copyTo(out) }; out.fd.sync() }
                step = "checking the fetched copy"
                check(sha256(pending.inputStream()) == seen.sha) { "the folder's copy changed while reading" }
                if (cutShort(pending)) throw CutShortCopy()
                val summary = NotebookPackage.syncSummary(pending)
                check(summary.id == id) { "the folder's file and notebook identity differ" }
                return pending to summary
            } catch (e: Throwable) { pending.delete(); throw e }
        }
        fun keepVersion(id: String) = keepVersion(directory, id, file(id), now())
        /** P699: what both sides now agree on, for a later merge. Never fails the sync. */
        fun agreed(id: String, sha: String, base: NotebookMerge.Base? = null) {
            if (!holdConflicts) return
            dropHeld(directory, id) // agreed again: a version held for a choice is out of date
            runCatching { saveBase(directory, id, sha, base ?: file(id).takeIf { sha256(it.inputStream()) == sha }?.let { NotebookPackage.syncSummary(it).base } ?: return) }
        }
        /**
         * B79: instant sync is on and both devices changed [id] since they last agreed. Combines the two at once when no page
         * clashes, so no choice is asked: this device's copy is sent when it already has everything, the other's is taken
         * when it has everything, otherwise the combined notebook is saved here and sent. [pending] is [there]'s fetched
         * file (null when it is already held). False leaves the notebook for the user's choice.
         */
        fun combine(id: String, planned: Seen, remote: Seen, there: NotebookPackage.SyncSummary, pending: File?): Boolean {
            step = "combining both versions"
            // P713: the copy here may have been saved since planning; compare with what is here now, not the planned hash.
            val local = file(id).let { f -> if (f.length() == planned.size && f.lastModified() == planned.modified) planned else Seen(f.length(), f.lastModified(), sha256(f.inputStream())) }
            val base = base(directory, id, state[id]?.base) ?: return false
            step = "combining: comparing both versions"
            val here = runCatching { NotebookPackage.syncSummary(file(id)) }.getOrNull()?.takeIf { it.id == id } ?: return false
            if (same(here, there)) {
                next[id] = Record(local.sha, local, remote); inSync++; agreed(id, local.sha, here.base); return true
            }
            // P715: the only place both versions are decoded whole: for the merge itself, one after the other. The editor's
            // cache of saved pages goes first (its next save encodes every page again), so the heap holds as little as it can.
            NotebookPackage.clearCaches()
            var merged: Notebook? = mergeWhole(library, id, base, file(id), pending ?: heldFile(directory, id)) { step = it }?.takeIf { it.clashes == 0 }?.notebook ?: return false
            step = "combining: checking the combined notebook"
            val mergedBase = NotebookMerge.base(merged!!); val mergedAssets = LiveChanges.assetIds(merged)
            fun matches(side: NotebookPackage.SyncSummary) = side.id == id && side.base == mergedBase && side.assetIds == mergedAssets
            when {
                matches(here) -> {
                    // This device has everything (e.g. it added the page): send its copy; the other device fetches it.
                    merged = null
                    next[id] = Record(local.sha, local, upload(id, local)); uploaded += name(id); combined += name(id); combinedIds += id; agreed(id, local.sha, here.base)
                }
                matches(there) -> {
                    merged = null
                    // The other device has everything: take its copy, as a plain download would.
                    val source = pending ?: File(directory, ".$id-${UUID.randomUUID()}.pending").also { heldFile(directory, id).copyTo(it, overwrite = true) }
                    try {
                        val committed = NotebookReadGate.access(directory, id) {
                            if (id in busy || sha256(file(id).inputStream()) != local.sha) false else {
                                keepVersion(id)
                                Files.move(source.toPath(), file(id).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                                true
                            }
                        }
                        if (!committed) {
                            // Open here: held as simply newer, so the app takes it in once the notebook is saved.
                            if (pending != null) hold(directory, id, pending, remote)
                            if (heldSha(directory, id) != remote.sha) { waiting += name(id); return true }
                            newerMark(directory, id).writeText(remote.sha); newer += name(id); return true
                        }
                    } finally { if (source !== pending) source.delete() }
                    val f = file(id)
                    next[id] = Record(remote.sha, Seen(f.length(), f.lastModified(), remote.sha), remote)
                    deletionIndex.restored(id)
                    downloaded += name(id)
                    agreed(id, remote.sha, there.base)
                }
                else -> {
                    step = "combining: saving the combined notebook"
                    val written = NotebookReadGate.access(directory, id) {
                        if (id in busy || sha256(file(id).inputStream()) != local.sha) false else { keepVersion(id); library.write(merged!!); true }
                    }
                    merged = null
                    if (!written) {
                        // P713: open here. Held with a mark, so the app combines it ([resolve], only when nothing clashes) as soon as
                        // it can change the notebook, instead of waiting for a close that a notebook behind the Library never gets.
                        // Never a choice box; the next sync finds it held and fetches nothing again.
                        if (pending != null) hold(directory, id, pending, remote)
                        newerMark(directory, id).delete()
                        combineMark(directory, id).apply { parentFile.mkdirs() }.writeText(remote.sha)
                        combineLater += name(id); return true
                    }
                    step = "combining: checking the saved notebook"
                    val f = file(id)
                    val seen = Seen(f.length(), f.lastModified(), sha256(f.inputStream()))
                    next[id] = Record(seen.sha, seen, upload(id, seen)); uploaded += name(id); combined += name(id); combinedIds += id; agreed(id, seen.sha, mergedBase)
                }
            }
            return true
        }

        val ids = (localFiles.keys + remoteStats.keys + state.keys + trashedIds).toSortedSet()
        var interrupted = false
        data class Plan(val id: String, val local: Seen?, val remote: Seen?, val action: Action)
        val plans = mutableListOf<Plan>()
        for ((i, id) in ids.withIndex()) {
            if (cancelled()) { interrupted = true; break }
            if (id in released) continue
            label = "Looking at ${i + 1} of ${ids.size}: ${name(id)}"
            step = "checking what changed"
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
                // B73 (P723, the user's choice): while instant sync is on, a notebook changed here only by changes taken in from
                // the other device ([deferUpload]) is left for that device to send. Both sending it within seconds made OneDrive
                // refuse one upload (409 Conflict). Its copy then arrives in the folder; a later sync here finds it the same,
                // or combines both (B79). The app stops listing a notebook after a while, so this device sends it then.
                if (action == Action.UPLOAD && id in deferUpload && remote != null && !restored) { deferred += name(id); return@runCatching }
                // An open notebook can still be sent, so a cut-short copy of it need not wait until it is closed.
                val openAndCutShort = id in busy && local != null && action in setOf(Action.DOWNLOAD, Action.CONFLICT) && try {
                    val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
                    try {
                        FileOutputStream(pending).use { out -> folder.read("$id$PACKAGE").use { it.copyTo(out) }; out.fd.sync() }
                        cutShort(pending)
                    } finally { pending.delete() }
                } catch (_: IOException) { false }
                if (openAndCutShort) { plans += Plan(id, local, remote, Action.UPLOAD); return@runCatching }
                // P699: holding the other device's version changes nothing here, so an open notebook can be held too.
                // P699: a newer version of an open notebook is held like a conflict; the app takes it in once the notebook is saved.
                val holdNewer = holdConflicts && id in busy && action == Action.DOWNLOAD && local != null
                if (!holdNewer && id in busy && action in setOf(Action.DOWNLOAD, Action.TRASH_LOCAL) + if (holdConflicts) emptySet() else setOf(Action.CONFLICT)) { waiting += name(id); return@runCatching }
                plans += Plan(id, local, remote, if (holdNewer) Action.CONFLICT else action)
            }.onFailure { if(it is LocalChangedBeforeUpload)waiting+=name(id) else {failed += name(id); problems += Problem(id, name(id), problemKind(it), problemDetail(step, it))} }
        }
        if (interrupted) return Outcome(uploaded, downloaded, conflicts, trashed, deletedThere, waiting, failed, inSync, next, problems, true, released.size, choices, newer, combined, combinedIds, combineLater, deferred)
        // Batch all deletion intents before removing any packages. No per-notebook cloud files.
        plans.filter { it.action == Action.DELETE_REMOTE }.forEach { deletionIndex.deleted(it.id, it.remote!!.sha) }
        val plannedMetadata = plans.associate { it.id to deletionIndex.entry(it.id) }
        deletionIndex.publish()
        if (plannedMetadata.any { (id, entry) -> deletionIndex.entry(id) != entry })
            throw IOException("Sync deletion metadata changed during planning; retry sync.")
        deletionIndex.migrateLegacy()
        val removing = plans.filter { it.action == Action.DELETE_REMOTE }.map { it.id }
        deletionIndex.verifyDeleted(removing)
        for ((i, plan) in plans.withIndex()) {
            val (id, local, remote, action) = plan
            if (cancelled()) { interrupted = true; break }
            label = "Working on ${i + 1} of ${plans.size}: ${name(id)}"
            step = "starting to " + when (action) {
                Action.UPLOAD -> "send"; Action.DOWNLOAD -> "receive"; Action.CONFLICT -> "handle a change on both devices"
                Action.TRASH_LOCAL -> "move to the trash"; Action.DELETE_REMOTE -> "delete from the folder"; else -> "record"
            }
            runCatching {
                when (action) {
                    Action.NONE -> { next[id] = Record(local!!.sha, local, remote); inSync++; if (holdConflicts && baseSha(directory, id) != local.sha) agreed(id, local.sha) }
                    Action.UPLOAD -> { next[id] = Record(local!!.sha, local, upload(id, local)); uploaded += name(id); agreed(id, local.sha) }
                    Action.DOWNLOAD -> {
                        remote!!
                        val (pending, summary) = try { fetch(id, remote) } catch (cut: CutShortCopy) {
                            if (local == null) throw cut
                            next[id] = Record(local.sha, local, upload(id, local)); uploaded += name(id); return@runCatching
                        }
                        // P715: the whole notebook is read once, with every check, before it is taken in; nothing of it is kept.
                        try { check(library.readPackage(pending).id == id) { "the folder's file and notebook identity differ" } } catch (e: Throwable) { pending.delete(); throw e }
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
                        downloaded += summary.title
                        agreed(id, remote.sha, summary.base)
                    }
                    Action.CONFLICT -> if (holdConflicts) {
                        // B79: with instant sync, changes on both devices are combined unless they clash. Not for a notebook
                        // unchanged here (a newer version of an open notebook): that one is simply taken in.
                        // P714: not after a combine the app started stopped or clashed: the user is asked instead.
                        val tryCombine = autoCombine && local!!.sha != state[id]?.base && !askMark(directory, id).isFile
                        // P699: hold the other device's version until the user chooses; nothing changes here or there.
                        if (heldSha(directory, id) != remote!!.sha) {
                            val (pending, there) = try { fetch(id, remote) } catch (_: CutShortCopy) {
                                next[id] = Record(local!!.sha, local, upload(id, local)); uploaded += name(id); return@runCatching
                            }
                            // Live sync (docs/LIVE-SYNC-PLAN.md): both devices already have the same notes, saved
                            // separately. Nothing to choose: both copies are recorded as agreed.
                            step = "comparing both versions"
                            if (runCatching { same(NotebookPackage.syncSummary(file(id)).also { require(it.id == id) }, there) }.getOrDefault(false)) {
                                pending.delete(); next[id] = Record(local!!.sha, local, remote); inSync++; agreed(id, local.sha); return@runCatching
                            }
                            val done = try { tryCombine && combine(id, local!!, remote, there, pending) } catch (e: Throwable) { pending.delete(); throw e }
                            if (done) { pending.delete(); return@runCatching }
                            try { hold(directory, id, pending, remote) } finally { pending.delete() }
                        } else if (tryCombine) {
                            // Held by an earlier sync (before instant sync, or a clash since edited away): try again.
                            val there = runCatching { NotebookPackage.syncSummary(heldFile(directory, id)) }.getOrNull()?.takeIf { it.id == id }
                            if (there != null && combine(id, local!!, remote, there, null)) return@runCatching
                        }
                        val newerFile = newerMark(directory, id)
                        combineMark(directory, id).delete()
                        if (local!!.sha == state[id]?.base) { newerFile.writeText(remote.sha); newer += name(id) } else { newerFile.delete(); choices += name(id) }
                    } else {
                        // Keep both: the other device's version becomes a new notebook here (and in the folder); this one is sent.
                        val (pending, _) = try { fetch(id, remote!!) } catch (_: CutShortCopy) {
                            next[id] = Record(local!!.sha, local, upload(id, local)); uploaded += name(id); return@runCatching
                        }
                        if(id in busy){pending.delete();waiting+=name(id);return@runCatching}
                        val copy = try {
                            NotebookPackage.clearCaches()
                            val notebook = library.readPackage(pending)
                            check(notebook.id == id) { "the folder's file and notebook identity differ" }
                            library.importCopy(notebook.copy(title = "${notebook.title.take(470)} (other device)"))
                        } finally { pending.delete() }
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
            }.onFailure { if(it is LocalChangedBeforeUpload)waiting+=name(id) else {failed += name(id); problems += Problem(id, name(id), problemKind(it), problemDetail(step, it))} }
        }
        // A failed transfer may leave the provider in explicit recovery mode. Preserve the partial
        // outcome/agreement instead of re-entering it for metadata and throwing away that result.
        // Session.change already retains restore intents locally; the next run publishes before
        // transfers. Deletion intents were verified before any package removal above.
        if (!interrupted && failed.isEmpty()) deletionIndex.publish()
        return Outcome(uploaded, downloaded, conflicts, trashed, deletedThere, waiting, failed, inSync, next, problems, interrupted,released.size, choices, newer, combined, combinedIds, combineLater, deferred)
    }

    /** One line per notebook, so the state can live in a small text file. */
    fun encode(state: Map<String, Record>): String = state.toSortedMap().entries.joinToString("\n") { (id, r) ->
        fun s(x: Seen?) = x?.let { "${it.size}\t${it.modified}\t${it.sha}" } ?: "-\t-\t-"
        "$id\t${r.base}\t${s(r.local)}\t${s(r.remote)}"
    }

    // P699 (docs/SYNC-MERGE-PLAN.md): the last agreed version's record, and the other device's version held for a choice.
    const val BASES = ".sync-base"
    const val HELD = ".sync-held"
    /** Live sync: the same pages, items, title and files, though saved separately (so the files differ). */
    fun sameContent(a: Notebook, b: Notebook): Boolean = a.id == b.id && NotebookMerge.base(a) == NotebookMerge.base(b) &&
        LiveChanges.assetIds(a) == LiveChanges.assetIds(b)
    /** P715: [sameContent] for two files' page-by-page summaries. */
    internal fun same(a: NotebookPackage.SyncSummary, b: NotebookPackage.SyncSummary): Boolean =
        a.id == b.id && a.base == b.base && a.assetIds == b.assetIds
    /** [TAKE_NEWER]: this device's copy is unchanged since the two agreed; the other's simply replaces it (no version kept). */
    enum class Choice { COMBINE, KEEP_HERE, KEEP_THERE, KEEP_BOTH, TAKE_NEWER }

    private fun baseFile(directory: File, id: String) = File(File(directory, BASES), "$id.json")
    private fun baseShaFile(directory: File, id: String) = File(File(directory, BASES), "$id.sha")
    private fun heldFile(directory: File, id: String) = File(File(directory, HELD), "$id$PACKAGE")
    private fun heldInfo(directory: File, id: String) = File(File(directory, HELD), "$id.txt")
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile.apply { if (!isDirectory && !mkdirs()) throw IOException("Cannot create ${name}") }
        val part = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try { FileOutputStream(part).use { it.write(bytes); it.fd.sync() }; Files.move(part.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { part.delete() }
    }

    internal fun baseSha(directory: File, id: String): String? = baseShaFile(directory, id).takeIf { it.isFile }?.readText()?.trim()
    internal fun saveBase(directory: File, id: String, sha: String, notebook: Notebook) = saveBase(directory, id, sha, NotebookMerge.base(notebook))
    internal fun saveBase(directory: File, id: String, sha: String, base: NotebookMerge.Base) {
        writeAtomically(baseFile(directory, id), NotebookMerge.encode(base))
        writeAtomically(baseShaFile(directory, id), sha.toByteArray())
    }
    /** The record of the version with checksum [sha], if this device kept it. */
    fun base(directory: File, id: String, sha: String?): NotebookMerge.Base? =
        if (sha == null || baseSha(directory, id) != sha) null else runCatching { NotebookMerge.decode(baseFile(directory, id).readBytes()) }.getOrNull()

    private fun heldSeen(directory: File, id: String): Seen? = heldInfo(directory, id).takeIf { it.isFile && heldFile(directory, id).isFile }?.readText()?.trim()?.split('	')
        ?.takeIf { it.size == 3 }?.let { runCatching { Seen(it[1].toLong(), it[2].toLong(), it[0]) }.getOrNull() }
    internal fun heldSha(directory: File, id: String) = heldSeen(directory, id)?.sha
    private fun dropHeld(directory: File, id: String) { heldFile(directory, id).delete(); heldInfo(directory, id).delete(); newerMark(directory, id).delete(); combineMark(directory, id).delete(); askMark(directory, id).delete() }
    private fun hold(directory: File, id: String, pending: File, remote: Seen) {
        val target = heldFile(directory, id).apply { parentFile.mkdirs() }
        Files.move(pending.toPath(), target.toPath(), REPLACE_EXISTING)
        writeAtomically(heldInfo(directory, id), "${remote.sha}	${remote.size}	${remote.modified}".toByteArray())
    }
    /**
     * P715: both versions of [id], read whole and combined over [base], or null when either cannot be read or combined. In
     * a call of its own, so neither whole copy stays reachable from the caller while the combined notebook is written.
     */
    private fun mergeWhole(library: NotebookLibrary, id: String, base: NotebookMerge.Base, here: File, there: File, stage: (String) -> Unit = {}): NotebookMerge.Result? = runCatching {
        stage("combining: reading this device's copy whole")
        val hereWhole = library.readPackage(here).takeIf { it.id == id } ?: return null
        stage("combining: reading the other device's copy whole")
        val thereWhole = library.readPackage(there).takeIf { it.id == id } ?: return null
        stage("combining: merging the pages")
        NotebookMerge.merge(base, hereWhole, thereWhole)
    }.getOrNull()

    private fun keepVersion(directory: File, id: String, current: File, time: Long) {
        if (!current.isFile) return
        val dir = File(File(directory, NotebookLibrary.VERSIONS), id).apply { if (!isDirectory && !mkdirs()) throw IOException("Cannot create the versions folder") }
        current.copyTo(File(dir, "$time$PACKAGE"), overwrite = true)
        dir.listFiles().orEmpty().filter { it.name.endsWith(PACKAGE) }.sortedByDescending { it.name.removeSuffix(PACKAGE).toLongOrNull() ?: 0L }.drop(KEEP_VERSIONS).forEach { it.delete() }
    }

    private fun newerMark(directory: File, id: String) = File(File(directory, HELD), "$id.newer")
    /** P699: held notebook [id] was unchanged here when last synced, so the other device's version is simply newer (no hashing). */
    fun newerOnly(directory: File, id: String): Boolean = newerMark(directory, id).isFile
    private fun combineMark(directory: File, id: String) = File(File(directory, HELD), "$id.combine")
    /** P713: held notebook [id] combines with no clash; the app combines it ([resolve] with onlyClean) once it can change it. */
    fun combineOnly(directory: File, id: String): Boolean = combineMark(directory, id).isFile
    private fun askMark(directory: File, id: String) = File(File(directory, HELD), "$id.ask")
    /**
     * P714: a combine the app started stopped (the app was closed or ran out of memory) or clashed: the held notebook waits
     * for the user's choice, and sync does not try to combine it by itself again until that choice is made.
     */
    fun cancelCombine(directory: File, id: String) {
        combineMark(directory, id).delete()
        if (heldFile(directory, id).isFile) askMark(directory, id).apply { parentFile.mkdirs() }.writeText("ask")
    }
    private fun unchangedHere(directory: File, id: String, state: Map<String, Record>): Boolean {
        val base = state[id]?.base ?: return false
        val local = File(directory, "$id$PACKAGE").takeIf { it.isFile } ?: return false
        return sha256(local.inputStream()) == base
    }

    /** Notebooks held for a choice (changed on both devices) or to be taken in ([newerOnly]); the other device's version is here. */
    fun held(directory: File): List<String> = File(directory, HELD).listFiles().orEmpty()
        .filter { it.name.endsWith(PACKAGE) && it.name.removeSuffix(PACKAGE).matches(ID) }.map { it.name.removeSuffix(PACKAGE) }
        .filter { heldSeen(directory, it) != null && File(directory, "$it$PACKAGE").isFile }.sorted()

    /** What each side changed since they last agreed, for the dialog; null changes when this device has no record of it. */
    data class Details(val title: String, val here: NotebookMerge.Changes?, val there: NotebookMerge.Changes?, val otherTitle: String) {
        val canCombine get() = here != null && there != null
    }
    @Suppress("UNUSED_PARAMETER") // P715: kept for the app's call; the summaries need no attachment folder.
    fun details(directory: File, id: String, state: Map<String, Record>, attachmentDirectory: File? = null): Details {
        // P715: page by page, so the box never holds either side's notebook whole.
        val here = NotebookPackage.syncSummary(File(directory, "$id$PACKAGE")); check(here.id == id) { "The notebook file and identity differ" }
        val there = NotebookPackage.syncSummary(heldFile(directory, id)); check(there.id == id) { "The held file and notebook identity differ" }
        val base = base(directory, id, state[id]?.base)
        return Details(here.title, base?.let { NotebookMerge.changes(it, here.base) }, base?.let { NotebookMerge.changes(it, there.base) }, there.title)
    }

    /**
     * Carries out the user's [choice] for a held notebook (call on the thread that saves notebooks, with the notebook
     * closed) and returns the new sync state; the next sync sends the result. The version not kept goes to the
     * notebook's versions folder first. [Choice.COMBINE] needs this device's record of the last agreed version.
     */
    fun resolve(directory: File, id: String, choice: Choice, state: Map<String, Record>, attachmentDirectory: File? = null, now: Long = System.currentTimeMillis(), onlyClean: Boolean = false, progress: (String) -> Unit = {}): Map<String, Record> {
        val library = NotebookLibrary(directory, attachmentDirectory)
        val remote = heldSeen(directory, id) ?: throw IOException("Nothing is waiting for a choice for this notebook")
        val held = heldFile(directory, id)
        progress("reading the other device's copy")
        check(sha256(held.inputStream()) == remote.sha) { "The other device's version changed while waiting" }
        val local = File(directory, "$id$PACKAGE")
        // P715: the held version is read page by page here; whole only where it is combined or taken in.
        val there = NotebookPackage.syncSummary(held)
        check(there.id == id) { "The held file and notebook identity differ" }
        fun thereWhole() = library.readPackage(held).also { check(it.id == id) { "The held file and notebook identity differ" } }
        when (choice) {
            Choice.COMBINE -> {
                val base = base(directory, id, state[id]?.base) ?: throw IllegalStateException("This device has no record to combine from")
                progress("combining: comparing both versions")
                val here = NotebookPackage.syncSummary(local)
                NotebookPackage.clearCaches()
                progress("combining: reading both copies whole")
                var result: NotebookMerge.Result? = NotebookMerge.merge(base, library.readPackage(local), thereWhole())
                progress("combining: checking the combined notebook")
                // P713: the app's own combine never settles a clash; the mark goes, so the user is asked instead.
                if (onlyClean && result!!.clashes != 0) { cancelCombine(directory, id); throw IllegalStateException("Both devices changed the same thing; choose what to keep") }
                val merged = NotebookPackage.SyncSummary(id, result!!.notebook.title, NotebookMerge.base(result.notebook), LiveChanges.assetIds(result.notebook))
                when {
                    // P714: this copy already has everything: nothing is written (the next sync sends it if it differs).
                    same(merged, here) -> result = null
                    // P714: the other device's copy has everything: its file is taken as it is, not built again.
                    same(merged, there) -> { result = null; NotebookReadGate.access(directory, id) {
                        keepVersion(directory, id, local, now)
                        val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
                        try { held.copyTo(pending, overwrite = true); Files.move(pending.toPath(), local.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) } finally { pending.delete() }
                    } }
                    else -> { progress("combining: saving the combined notebook"); NotebookReadGate.access(directory, id) { keepVersion(directory, id, local, now); library.write(result!!.notebook) } }
                }
            }
            Choice.KEEP_HERE -> keepVersion(directory, id, held, now)
            Choice.KEEP_THERE, Choice.TAKE_NEWER -> NotebookReadGate.access(directory, id) {
                if (choice == Choice.TAKE_NEWER) {
                    // B79: or the held version already has everything this copy has (instant sync brought it here too).
                    if (!unchangedHere(directory, id, state)) {
                        NotebookPackage.clearCaches()
                        check(base(directory, id, state[id]?.base)?.let { b -> runCatching { NotebookMerge.merge(b, library.readPackage(local), thereWhole())
                            .let { m -> m.clashes == 0 && m.notebook.id == id && NotebookMerge.base(m.notebook) == there.base && LiveChanges.assetIds(m.notebook) == there.assetIds } }
                            .getOrDefault(false) } == true) { "This notebook changed here too" }
                        keepVersion(directory, id, local, now)
                    } else thereWhole() // P715: every check of a full read, before the file is taken in
                } else { thereWhole(); keepVersion(directory, id, local, now) }
                val pending = File(directory, ".$id-${UUID.randomUUID()}.pending")
                try { held.copyTo(pending, overwrite = true); Files.move(pending.toPath(), local.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) } finally { pending.delete() }
            }
            Choice.KEEP_BOTH -> { NotebookPackage.clearCaches(); thereWhole().let { library.importCopy(it.copy(title = "${it.title.take(470)} (other device)")) } }
        }
        // Agreed with the other device's version now: what is here differs from it, so the next sync sends this one.
        runCatching { saveBase(directory, id, remote.sha, there.base) }
        held.delete(); heldInfo(directory, id).delete(); newerMark(directory, id).delete(); combineMark(directory, id).delete(); askMark(directory, id).delete()
        return state + (id to Record(remote.sha, null, remote))
    }

    fun decode(text: String): Map<String, Record> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t').takeIf { it.size == 8 } ?: return@mapNotNull null
        fun s(i: Int) = if (p[i] == "-") null else runCatching { Seen(p[i].toLong(), p[i + 1].toLong(), p[i + 2]) }.getOrNull()
        p[0].takeIf { it.matches(ID) }?.let { it to Record(p[1], s(2), s(5)) }
    }.toMap()
}
