package dev.ownednotes.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.ownednotes.core.DocumentSession
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.NotebookLibrary
import dev.ownednotes.core.NotebookReadGate
import dev.ownednotes.core.PageDrop
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P695-P696 (C1-C3 of docs/PHONE-CAPTURE-PLAN.md): page drops on Android. A phone sends the pages it scanned for a
 * synced notebook as a [PageDrop] in the sync folder; another device that has the notebook adds them as one edit
 * (the open notebook through the editor, with one Undo and a notice; a closed one at sync). Only that device writes the
 * notebook, so no conflict copy appears. A drop leaves the folder once the notebook with its pages has gone up.
 */
internal object PageDrops {
    /** P699: a new key, so a phone that had sending on by the old default starts with it off. */
    private const val SEND_KEY = "scan_drops_send_chosen"
    private const val APPLIED = "applied-drops.txt"
    private const val SENT = "sent-drops"
    private const val SENT_LIST = "sent-drops.txt"
    /** A drop not picked up in this long is pointed out on the phone. */
    const val LATE = 24 * 60 * 60 * 1000L
    /** Reading drops and making pages: off the save thread, one at a time. */
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "notes-drops").apply { isDaemon = true } }
    private val checking = AtomicBoolean()
    private val lastSync = java.util.concurrent.atomic.AtomicLong()
    private const val SYNC_GAP = 4 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences((context.applicationContext as? NotesApplication)?.preferencesName ?: "library", 0)
    /**
     * P699 (docs/SYNC-MERGE-PLAN.md step 3): off by default everywhere. Scan → Add puts the pages in this device's notebook,
     * and sync takes the notebook to the other devices (merging when both changed). Sending stays for whoever turns it on.
     */
    @Suppress("UNUSED_PARAMETER") fun sendDefault(context: Context) = false
    fun sendEnabled(context: Context) = prefs(context).getBoolean(SEND_KEY, sendDefault(context))
    fun setSendEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(SEND_KEY, on).apply() }

    @Synchronized fun installId(app: NotesApplication): String {
        val file = File(app.storageRoot, "install-id")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }?.let { return it }
        return UUID.randomUUID().toString().also { file.parentFile?.mkdirs(); file.writeText(it) }
    }
    fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }?.take(100) ?: "another device"
    private fun applied(app: NotesApplication) = PageDrop.Applied(File(app.storageRoot, APPLIED))

    // ---- The sending device ----

    /**
     * A drop this device sent and still keeps a copy of, until its pages come back in the notebook or it is cancelled.
     * Not [uploaded] yet when the sync folder could not be reached: it goes up at the next check.
     */
    data class Sent(val dropId: String, val notebookId: String, val count: Int, val time: Long, val pageNumber: Int?, val kind: String, val uploaded: Boolean = true) {
        val fileName get() = PageDrop.name(notebookId, dropId)
    }
    /** Kept in memory: the page list asks on every refresh. */
    @Volatile private var sentCache: Pair<File, List<Sent>>? = null
    @Synchronized fun sent(app: NotesApplication): List<Sent> {
        val file = File(app.storageRoot, SENT_LIST)
        sentCache?.takeIf { it.first == file }?.let { return it.second }
        if (!file.isFile) return emptyList<Sent>().also { sentCache = file to it }
        return file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 7) return@mapNotNull null
            runCatching { Sent(p[0], p[1], p[2].toInt(), p[3].toLong(), p[4].toIntOrNull(), p[5], p[6] == "1").also { PageDrop.name(it.notebookId, it.dropId) } }.getOrNull()
        }.also { sentCache = file to it }
    }
    @Synchronized private fun saveSent(app: NotesApplication, list: List<Sent>) {
        val file = File(app.storageRoot, SENT_LIST)
        val pending = File(app.storageRoot, ".$SENT_LIST.pending")
        pending.writeText(list.joinToString("") { "${it.dropId}\t${it.notebookId}\t${it.count}\t${it.time}\t${it.pageNumber ?: "-"}\t${it.kind}\t${if (it.uploaded) 1 else 0}\n" })
        sentCache = null
        if (!pending.renameTo(file)) { file.delete(); if (!pending.renameTo(file)) throw IOException("Cannot save the list of sent pages") }
        sentCache = file to list
    }
    @Synchronized private fun forget(app: NotesApplication, dropId: String) {
        val list = sent(app); val gone = list.filter { it.dropId == dropId }
        if (gone.isEmpty()) return
        saveSent(app, list - gone.toSet())
        gone.forEach { File(File(app.storageRoot, SENT), it.fileName).delete() }
    }
    fun sentFor(app: NotesApplication, notebookId: String) = sent(app).filter { it.notebookId == notebookId }
    @Synchronized private fun markUploaded(app: NotesApplication, dropId: String) {
        val list = sent(app)
        if (list.any { it.dropId == dropId && !it.uploaded }) saveSent(app, list.map { if (it.dropId == dropId) it.copy(uploaded = true) else it })
    }
    private fun copy(app: NotesApplication, sent: Sent) = File(File(app.storageRoot, SENT), sent.fileName)
    /** Puts a drop that could not go up before into the sync folder. */
    private fun upload(app: NotesApplication, root: FolderSync.NestedFolder, sent: Sent) {
        val dir = File(app.cacheDir, "drop-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val m = copy(app, sent).inputStream().use { PageDrop.read(it, dir) }
            PageDrop.send(root, m, copy(app, sent))
            markUploaded(app, sent.dropId)
        } finally { dir.deleteRecursively() }
    }

    /**
     * Whether Add should send to the other device: the switch is on and the notebook is in a sync folder it has reached.
     * Without a connection the drop waits here and goes up later; the notebook here is not changed either way.
     */
    fun canSend(app: NotesApplication, notebookId: String) = sendEnabled(app) && LibrarySync.folderUri(app) != null && LibrarySync.synced(app, notebookId)

    /**
     * Sends [jpegs] (finished pages, in order) to go after [afterPageId]; [done] runs on the main thread. Fails only when
     * the drop cannot be kept here; when the folder cannot be reached now, it is kept with uploaded = false.
     */
    fun send(app: NotesApplication, notebookId: String, afterPageId: String?, pageNumber: Int?, jpegs: List<ByteArray>,
             kind: String = PageDrop.PAGES, folder: (() -> FolderSync.NestedFolder)? = null, done: (Result<Sent>) -> Unit) {
        val open = folder ?: LibrarySync.folderUri(app)?.let { tree -> { FolderProcess.folder(app, tree) } }
            ?: return done(Result.failure(IOException("No sync folder")))
        val reachable = folder != null || eligible(app)
        worker.execute {
            val result = runCatching {
                val manifest = PageDrop.Manifest(id = UUID.randomUUID().toString(), notebookId = notebookId, kind = kind, pageId = afterPageId,
                    pageNumber = pageNumber, sender = deviceName(app), senderId = installId(app), time = System.currentTimeMillis())
                val file = File(File(app.storageRoot, SENT).apply { mkdirs() }, manifest.fileName)
                val (entry, written) = try {
                    val written = PageDrop.write(file, manifest, jpegs)
                    Sent(written.id, notebookId, jpegs.size, written.time, pageNumber, kind, uploaded = false).also { synchronized(this) { saveSent(app, sent(app) + it) } } to written
                } catch (e: Throwable) { file.delete(); throw e }
                if (reachable && runCatching { PageDrop.send(open(), written, file) }.isSuccess) { markUploaded(app, entry.dropId); entry.copy(uploaded = true) } else entry
            }
            app.main.post { done(result) }
        }
    }

    /** The other device has taken the drop: it is no longer in the sync folder. */
    class AlreadyAdded : IOException("Your other device has already added these pages. They appear here when the notebook syncs.")
    /** Takes [sent] back out of the sync folder; [AlreadyAdded] when the other device took it first. */
    private fun takeBack(open: (() -> FolderSync.NestedFolder)?, sent: Sent) {
        val root = open?.invoke() ?: throw IOException("No sync folder")
        if (PageDrop.folder(root, false) == null || sent.fileName !in PageDrop.pending(root)) throw AlreadyAdded()
        PageDrop.delete(root, sent.fileName)
    }

    /** Cancel on the phone: the drop leaves the sync folder first, then the copy here. */
    fun cancel(app: NotesApplication, sent: Sent, folder: (() -> FolderSync.NestedFolder)? = null, done: (Result<Unit>) -> Unit) {
        val open = folder ?: LibrarySync.folderUri(app)?.let { tree -> { FolderProcess.folder(app, tree) } }
        worker.execute {
            val uploaded = sent(app).firstOrNull { it.dropId == sent.dropId }?.uploaded ?: sent.uploaded
            val result = runCatching { if (uploaded) takeBack(open, sent) }.onFailure { if (it is AlreadyAdded) runCatching { forget(app, sent.dropId) } }
                .mapCatching { forget(app, sent.dropId) }
            app.main.post { done(result) }
        }
    }

    /** Add here: the drop leaves the sync folder (so no other device adds it too), then its pages go into the open notebook. */
    fun addHere(app: NotesApplication, repository: NotebookRepository, sent: Sent, folder: (() -> FolderSync.NestedFolder)? = null, done: (Result<Int>) -> Unit) {
        val open = folder ?: LibrarySync.folderUri(app)?.let { tree -> { FolderProcess.folder(app, tree) } }
        worker.execute {
            val dir = File(app.cacheDir, "drop-${UUID.randomUUID()}").apply { mkdirs() }
            val ready = runCatching {
                val m = File(File(app.storageRoot, SENT), sent.fileName).inputStream().use { PageDrop.read(it, dir) }
                val content = importDrop(app, m, dir)
                if (sent(app).firstOrNull { it.dropId == sent.dropId }?.uploaded ?: sent.uploaded) takeBack(open, sent)
                Prepared(m, content)
            }.onFailure { if (it is AlreadyAdded) runCatching { forget(app, sent.dropId) } }
            dir.deleteRecursively()
            app.main.post {
                val result = ready.mapCatching { prepared ->
                    // Out of the folder already: if the notebook cannot take them now, they go back so nothing is lost.
                    val ids = repository.insertDrop(sent.notebookId, prepared.manifest, prepared.content) ?: run {
                        worker.execute { runCatching { open?.invoke()?.let { root -> upload(app, root, sent) } } }
                        throw IOException("The notebook is busy. Try Add here again.")
                    }
                    worker.execute { runCatching { applied(app).add(sent.dropId, sent.notebookId, prepared.time); forget(app, sent.dropId) } }
                    ids.size
                }
                done(result)
            }
        }
    }

    /** Sent drops whose pages are now in [notebook] (it came back from the other device) are done. */
    fun arrivedIn(app: NotesApplication, notebook: Notebook): List<Sent> {
        val ids = notebook.pages.mapTo(HashSet()) { it.id }.also { set -> notebook.pages.forEach { p -> p.images.forEach { set += it.id } } }
        val arrived = sentFor(app, notebook.id).filter { PageDrop.pageId(it.dropId, 0) in ids }
        if (arrived.isNotEmpty()) worker.execute { arrived.forEach { runCatching { forget(app, it.dropId) } } }
        return arrived
    }

    // ---- The receiving device ----

    /** What a drop adds: new pages, or the pictures for one page. */
    class Content(val pages: Notebook?, val photos: List<ImageSupport.ImportedPhoto>)
    private class Prepared(val manifest: PageDrop.Manifest, val content: Content) { val time = System.currentTimeMillis() / 1000 * 1000 }
    class Arrival(val notebookId: String, val pages: Int, val sender: String, val kind: String = PageDrop.PAGES)

    private fun importDrop(app: NotesApplication, m: PageDrop.Manifest, dir: File): Content = if (m.kind == PageDrop.PICTURES)
        Content(null, m.images.map { File(dir, it.name).inputStream().use(ImageSupport::importPhoto) })
    else m.images.map { File(dir, it.name).inputStream().use { s -> ImageSupport.import(s, "Scanned page") } }.let { pages ->
        Content(Notebook(title = "Scanned pages", pages = pages.flatMap { it.pages }, imageAssets = pages.flatMap { it.imageAssets }.distinctBy { it.id }), emptyList())
    }

    /** The drop's pages as [content] into [session]: ids from the drop, so nothing is added twice. Empty when they are there. */
    fun insert(session: DocumentSession, m: PageDrop.Manifest, content: Content, grid: (List<ImageSupport.ImportedPhoto>, Float, Float) -> List<dev.ownednotes.core.ImagePlacement>): List<String> {
        val ids = m.images.indices.map { PageDrop.pageId(m.id, it) }
        if (m.kind == PageDrop.PICTURES) {
            val page = session.notebook.pages.firstOrNull { it.id == m.pageId }
            if (page != null) {
                val places = if (content.photos.size == 1) listOf(content.photos[0].placement(page)) else grid(content.photos, page.width, page.height)
                return session.insertImageObjects(page.id, content.photos.mapIndexed { i, p -> Triple(p.asset, places[i], p.orientation) }, ids)
            }
            // The page is gone: the pictures become pages at the end, as Import does.
            val pages = content.photos.map { ImageSupport.import(it.asset.openStream(), "Shared picture") }
            return session.insertDropPages(Notebook(title = "Pictures", pages = pages.flatMap { it.pages }, imageAssets = pages.flatMap { it.imageAssets }.distinctBy { it.id }), null, ids)
        }
        return session.insertDropPages(content.pages!!, m.pageId, ids)
    }

    /** Reads drop [name] into a fresh folder; null when it is damaged or still being copied (it stays for another try). */
    private fun fetch(app: NotesApplication, root: FolderSync.NestedFolder, name: String): Pair<PageDrop.Manifest, File>? {
        val dir = File(app.cacheDir, "drop-${UUID.randomUUID()}").apply { mkdirs() }
        return runCatching { PageDrop.open(root, name).use { PageDrop.read(it, dir) } to dir }.getOrElse { dir.deleteRecursively(); null }
    }
    private fun waiting(app: NotesApplication, names: Collection<String>, has: (String) -> Boolean): List<Pair<String, String>> {
        val sentIds = sent(app).mapTo(HashSet()) { it.dropId }; val done = applied(app).entries().mapTo(HashSet()) { it.dropId }
        return names.mapNotNull { name -> PageDrop.parse(name)?.takeIf { (notebook, drop) -> drop !in sentIds && drop !in done && has(notebook) }?.let { name to it.first } }
    }

    /**
     * On the sync thread, after the notebooks were brought up to date: adds waiting drops to notebooks that are here and
     * not open. The notebook is written under its read gate, and only while it is still not open.
     */
    fun applyClosed(app: NotesApplication, root: FolderSync.NestedFolder, busy: Set<String>): List<Arrival> {
        val directory = File(app.storageRoot, "notebooks")
        val library = NotebookLibrary(directory, app.attachmentDirectory)
        val arrivals = mutableListOf<Arrival>()
        for ((name, notebookId) in waiting(app, PageDrop.pending(root).keys) { it !in busy && library.contains(it) }) {
            val (m, dir) = fetch(app, root, name) ?: continue
            try {
                if (m.senderId == installId(app) || PageDrop.name(m.notebookId, m.id) != name) continue
                val content = importDrop(app, m, dir)
                val time = System.currentTimeMillis() / 1000 * 1000
                val added = NotebookReadGate.access(directory, notebookId) {
                    if (notebookId in busy) null else {
                        val session = library.readSession(notebookId)
                        insert(session, m, content, app.repository::photoGrid).also { if (it.isNotEmpty()) library.write(session.notebook) }
                    }
                } ?: continue
                applied(app).add(m.id, notebookId, time)
                if (added.isNotEmpty()) arrivals += Arrival(notebookId, added.size, m.sender, m.kind)
            } catch (_: Exception) {
                // Left in the folder; tried again at the next sync.
            } finally { dir.deleteRecursively() }
        }
        return arrivals
    }

    /** After a sync: drops this device added leave the folder once the notebook saved after adding them has gone up. */
    fun cleanUp(app: NotesApplication, root: FolderSync.NestedFolder, state: Map<String, FolderSync.Record>) {
        val added = applied(app).entries().associateBy { it.dropId }
        for (name in PageDrop.pending(root).keys) {
            val (notebookId, dropId) = PageDrop.parse(name) ?: continue
            val entry = added[dropId]?.takeIf { it.notebookId == notebookId } ?: continue
            val record = state[notebookId] ?: continue
            val local = record.local ?: continue; val remote = record.remote ?: continue
            if (local.sha == remote.sha && local.modified >= entry.time) runCatching { PageDrop.delete(root, name) }
        }
    }

    /** Looking needs a plain sync folder that is not paused, and Wi-Fi when sync is set to Wi-Fi only. */
    fun eligible(app: NotesApplication) = LibrarySync.folderUri(app) != null && !LibrarySync.paused(app) &&
        (!LibrarySync.wifiOnly(app) || LibrarySync.onWifi(app))

    /**
     * While the app is in front (about once a minute) and when a notebook opens: drops for the open notebook go in
     * through the editor (one Undo each, with [notice]); then, when drops wait for other notebooks or for their
     * notebook to go up, a sync runs. [done] runs on the main thread.
     */
    fun check(app: NotesApplication, repository: NotebookRepository, folder: (() -> FolderSync.NestedFolder)? = null,
              notice: (String) -> Unit, done: () -> Unit = {}) {
        if (folder == null && !eligible(app)) return done()
        if (!checking.compareAndSet(false, true)) return done()
        val tree = LibrarySync.folderUri(app)
        val open: () -> FolderSync.NestedFolder = folder ?: { FolderProcess.folder(app, tree!!) }
        val openIds = app.openDocuments.mapNotNull { it.session?.notebook?.id }.toSet()
        worker.execute {
            var syncWanted = false
            val ready = mutableListOf<Prepared>()
            val delivered = mutableListOf<Sent>()
            runCatching {
                val root = open()
                sent(app).filter { !it.uploaded }.forEach { runCatching { upload(app, root, it) } }
                val listed = PageDrop.folder(root, false) != null
                val names = PageDrop.pending(root).keys
                // This device's drops that left the folder were added on the other device (or cancelled there).
                if (listed) sent(app).filter { it.uploaded && it.fileName !in names && System.currentTimeMillis() - it.time > 60_000 }.forEach {
                    runCatching { forget(app, it.dropId); delivered += it }
                }
                if (names.isEmpty()) return@runCatching
                val notebooks = File(app.storageRoot, "notebooks")
                val waitingHere = waiting(app, names) { File(notebooks, "$it.inknote").isFile }
                // Drops for closed notebooks go in at a sync; drops added here leave the folder at a sync once their notebook
                // has gone up (an open notebook goes up when closed). At most one such sync every few minutes.
                val ids = names.mapNotNull { PageDrop.parse(it) }
                val addedHere = applied(app).entries().any { e -> e.notebookId !in openIds && ids.any { it.second == e.dropId } }
                syncWanted = (waitingHere.any { it.second !in openIds } || addedHere) &&
                    System.currentTimeMillis() - lastSync.get() >= SYNC_GAP
                for ((name, notebookId) in waitingHere) {
                    if (notebookId !in openIds) continue
                    val (m, dir) = fetch(app, root, name) ?: continue
                    try {
                        if (m.senderId != installId(app) && PageDrop.name(m.notebookId, m.id) == name) ready += Prepared(m, importDrop(app, m, dir))
                    } catch (_: Exception) { } finally { dir.deleteRecursively() }
                }
            }
            app.main.post {
                if (delivered.isNotEmpty()) notice("${items(delivered.sumOf { it.count }, kindOf(delivered.map { it.kind }))} added on your other device. They appear here when the notebook syncs.")
                for (p in ready) {
                    val document = app.openDocuments.firstOrNull { it.session?.notebook?.id == p.manifest.notebookId } ?: continue
                    val ids = document.insertDrop(p.manifest.notebookId, p.manifest, p.content) ?: continue
                    worker.execute { runCatching { applied(app).add(p.manifest.id, p.manifest.notebookId, p.time) } }
                    if (ids.isNotEmpty()) notice("${items(ids.size, p.manifest.kind)} arrived from ${p.manifest.sender}")
                }
                if (syncWanted) {
                    // Behind the applied-list writes above, so the sync sees them. An open notebook goes up when closed.
                    lastSync.set(System.currentTimeMillis())
                    worker.execute { app.main.post {
                        LibrarySync.sync(app, repository, folder = folder) { checking.set(false); done() }
                    } }
                } else { checking.set(false); done() }
            }
        }
    }

    fun pages(n: Int, kind: String = "") = (if (kind.isEmpty()) "" else "$kind ").let { if (n == 1) "1 ${it}page" else "$n ${it}pages" }
    /** P697: "2 scanned pages" for a page drop, "2 pictures" for pictures placed on a page. */
    fun items(n: Int, kind: String) = if (kind == PageDrop.PICTURES) (if (n == 1) "1 picture" else "$n pictures") else pages(n, "scanned")
    /** Pictures when every drop is pictures, otherwise pages. */
    fun kindOf(kinds: List<String>) = if (kinds.isNotEmpty() && kinds.all { it == PageDrop.PICTURES }) PageDrop.PICTURES else PageDrop.PAGES
    /**
     * P697: a shared photo as a JPEG for a picture drop. A JPEG goes as it is, so the other device reads its own turn;
     * anything else, or a JPEG too large for a drop, is drawn upright on white.
     */
    fun pictureJpeg(photo: ImageSupport.ImportedPhoto): ByteArray {
        val bytes = photo.asset.openStream().use { it.readBytes() }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes.size <= PageDrop.MAX_IMAGE_BYTES) return bytes
        val drawn = ImageSupport.render(photo.asset, dev.ownednotes.core.ImageBackground(photo.asset.id, photo.orientation), 3508)
        try {
            return java.io.ByteArrayOutputStream().also { drawn.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally { drawn.recycle() }
    }
    /** The sync report's line for drops added at sync. */
    fun report(arrivals: List<Arrival>, titles: Map<String, String>): String? = arrivals.takeIf { it.isNotEmpty() }?.groupBy { it.notebookId }?.entries
        ?.joinToString("; ") { (id, list) -> "${items(list.sumOf { it.pages }, kindOf(list.map { it.kind }))} from ${list.first().sender} added to ${titles[id] ?: "a notebook"}" }
}
