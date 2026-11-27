package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Live sync (docs/LIVE-SYNC-PLAN.md): one device's connection to the user's sync server. It turns each save into an
 * encrypted change set ([saved]), keeps unsent ones in an outbox until the server confirms them, and hands changes from
 * the other devices to [Events.incoming] one at a time, in the server's order.
 *
 * Everything runs on one queue thread, so diffs and "taken in" updates happen in the order the app asked for them: a save
 * made before another device's change was applied is diffed before that change joins the base, and is never sent back.
 *
 * Files in [dir]: `state.json` (sync code, device id, last entry seen), `bases/<notebook>.json`, `outbox/` and `held/`
 * (change sets that wait until folder sync has uploaded the files they need), and `incoming/<entry>`: other devices'
 * change sets, still sealed, waiting to be taken in (P718); P719: `photos/` (photos being fetched: sealed pieces, then
 * the checked photo by its checksum) and `covered.txt` (folder sets whose photos were taken in live). Outbox files ending
 * in `.piece` are pieces of this device's photos still to be sent.
 *
 * P719: a save whose only new files are photos (photo pages, embedded photos) goes live when the server keeps photos:
 * each photo goes up in sealed pieces, then the set naming it; another device fetches the pieces, checks the checksum
 * and takes the set in, while the sets after it wait. The folder set is still held and sent as before, for apps before
 * P719 and as the fallback (no photos on the server, a photo gone or not fetched within [PHOTO_WAIT_MS]).
 */
class LiveSyncClient(private val dir: File, private val events: Events, private val http: OkHttpClient = defaultHttp) {
    interface Events {
        /** Another device's [set]. Apply it, then call [done] once (from any thread) with how it was taken. */
        fun incoming(set: LiveChanges.ChangeSet, done: (Taken) -> Unit)
        /**
         * P724: true when the app takes several sets at once through [incomingRun]. Coming back to many changes for one
         * closed notebook rewrote its whole file once per change (60 MB each for the user's notebook with photos).
         */
        val runs: Boolean get() = false
        /**
         * P724: several of another device's sets in a row for one notebook, in order, none with photos or new files: apply
         * them all (a closed notebook read and written once), then call [done] once, as for [incoming]. Only when [runs].
         */
        fun incomingRun(sets: List<LiveChanges.ChangeSet>, done: (Taken) -> Unit): Unit = error("Runs are not taken")
        /** Run folder sync soon: another device added files to a notebook, or this device missed older changes. */
        fun folderSyncWanted()
        fun statusChanged(status: Status) {}
    }
    /**
     * How an incoming change was taken. [OPEN]: applied to the notebook in the editor, before any later save of it, so it
     * joins the base. [FILE]: applied to a closed notebook's file; the base is left as it was (a later save may send those
     * items again with the same values, which is harmless, but a stale open copy can never send them back as removed).
     * [LATER]: not now (the notebook is opening, or busy with a folder copy or a copy of pages); offered again in about
     * [RETRY_MS], for at most [MAX_RETRIES] tries, and later changes wait behind it so they keep their order (B71).
     * [NOT]: not applied (read-only, being trashed, or the notebook is not here); folder sync brings it later.
     */
    enum class Taken { OPEN, FILE, LATER, NOT }
    enum class State { OFF, PAUSED, CONNECTING, LIVE, WAITING, DENIED }
    data class Status(val state: State, val server: String? = null, val pending: Int = 0, val detail: String? = null)

    @Serializable private data class Saved(val code: String? = null, val device: String, val after: Long = 0)

    companion object {
        /** Change sets larger than this (a big paste, a whole PDF's worth of pages) go through folder sync instead. */
        const val MAX_SEALED_CHARS = 700_000
        const val PING_MS = 25_000L
        const val SILENT_MS = 70_000L
        const val BASE_FLUSH_MS = 5_000L
        const val RETRY_MS = 1_000L
        const val MAX_RETRIES = 30
        /** B79: after a set that says a notebook's files changed, its later sets wait this long at most for folder sync. */
        const val FETCH_WAIT_MS = 60_000L
        /** P719: a photo goes in pieces of this many bytes, each sealed (about 700,000 characters; the server takes 1,000,000). */
        const val PIECE_BYTES = 512 * 1024
        /** P719: how long a set waits for its photos; then the notebook comes through folder sync, as before. */
        const val PHOTO_WAIT_MS = 120_000L
        /** P719: pieces asked for ahead while fetching a photo, and bytes queued for sending before waiting (OkHttp closes at 16 MiB). */
        private const val FETCH_AHEAD = 4
        /** P724: at most this many sets, and this many sealed characters, are taken in together ([Events.incomingRun]). */
        const val RUN_SETS = 200
        const val RUN_CHARS = 1_000_000L
        private const val SEND_QUEUE_BYTES = 4L * 1024 * 1024
        private const val KEEP_COVERED = 500
        private const val KEEP_TRACKERS = 4
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        }
        private fun newId() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        /** wss://host/path/sync from https://host/path (ws:// for an http:// test server). */
        fun socketUrl(server: String) = server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/sync"
    }

    private val queue = Executors.newSingleThreadScheduledExecutor { Thread(it, "live-sync").apply { isDaemon = true } }
    private val stateFile = File(dir, "state.json")
    private val basesDir = File(dir, "bases")
    private val outboxDir = File(dir, "outbox")
    private val heldDir = File(dir, "held")
    private val incomingDir = File(dir, "incoming")
    private val photosDir = File(dir, "photos")
    private val coveredFile = File(dir, "covered.txt")

    // All of the following is touched only on the queue thread.
    private var saved: Saved = load()
    private var code: LiveCrypto.SyncCode? = saved.code?.let(LiveCrypto::parse)
    private var wanted = false
    private var socket: WebSocket? = null
    private var generation = 0
    private var state = if (code == null) State.OFF else State.PAUSED
    private var detail: String? = null
    private var failures = 0
    private var reconnect: ScheduledFuture<*>? = null
    private var pinger: ScheduledFuture<*>? = null
    private var heard = 0L
    private val trackers = LinkedHashMap<String, LiveChanges.Tracker>(8, .75f, true)
    private val dirty = HashSet<String>()
    private var flush: ScheduledFuture<*>? = null
    /**
     * P718: the entries waiting to be taken in, in the server's order; each other device's set waits sealed in
     * [incomingDir] until its turn. They were decoded on arrival and kept here: on the Pixel, coming back to thousands of
     * changes filled the app's 512 MB (3,153 sets held about 700 MB on the emulator), at every start again.
     */
    private val incoming = ArrayDeque<Long>()
    /** The first waiting entry, read from its file; [covered]: a folder set whose photos were taken in live (P719). */
    private class Waiting(val seq: Long, val set: LiveChanges.ChangeSet?, val covered: Boolean)
    private var head: Waiting? = null
    private var applying = false
    // P719: the server keeps photos (its welcome says so); the photos being fetched for the first waiting set.
    private var photosOk = false
    private class Download(val seq: Long, val refs: List<LiveChanges.PhotoRef>, val turn: Int) { val asked = HashMap<String, Int>() }
    private var download: Download? = null
    private var downloadTurn = 0
    private var photoFailed: Long? = null       // the entry whose photos could not be fetched: it goes the folder way
    private var covered: LinkedHashSet<String>? = null
    private val sent = HashSet<String>()        // outbox files sent on this connection
    private var pumping: ScheduledFuture<*>? = null
    private var retries = 0             // how often the change at the front of [incoming] was answered LATER
    private var highest = saved.after   // the newest entry already queued or applied
    private var epoch = 0               // a new code starts a new epoch; answers from the old one are ignored
    private val fetching = HashMap<String, Int>()   // B79: notebooks being fetched by folder sync; their later sets wait
    private var fetchTurn = 0
    private var welcomed = false

    @Volatile var status: Status = Status(state, code?.server, pendingCount()); private set

    // Entries and photos left waiting by an earlier run: the server sends them again after [Saved.after].
    init { incomingDir.deleteRecursively(); photosDir.deleteRecursively() }

    /** P719: a photo fetched for the set being taken in ([LiveChanges.PhotoRef.id]), checked against its checksum. */
    fun photo(id: String): File? = if (!id.matches(Regex("[0-9a-f]{64}"))) null else File(photosDir, id).takeIf { it.isFile }

    /** The sync code this device uses, or null when instant sync is off. */
    val syncCode: LiveCrypto.SyncCode? get() = call { code }
    val configured get() = status.state != State.OFF

    private fun <T> call(block: () -> T): T = queue.submit(block).get(20, TimeUnit.SECONDS)

    private fun load(): Saved = runCatching { json.decodeFromString<Saved>(stateFile.readText()) }.getOrNull()
        ?.takeIf { it.device.matches(Regex("[0-9a-f]{24}")) } ?: Saved(device = newId())
    private fun store() {
        dir.mkdirs()
        val temp = File(dir, "state.json.tmp")
        temp.writeText(json.encodeToString(Saved.serializer(), saved))
        if (!temp.renameTo(stateFile)) { stateFile.delete(); check(temp.renameTo(stateFile)) { "Could not save live sync settings" } }
    }
    private fun pendingCount() = (outboxDir.list()?.count { !it.endsWith(".piece") } ?: 0) + (heldDir.list()?.size ?: 0)
    private fun publish() {
        val next = Status(state, code?.server, pendingCount(), detail)
        if (next != status) { status = next; runCatching { events.statusChanged(next) } }
    }

    /** Sets up this device with [next] (a new or joined code), or turns instant sync off with null. Notes are not touched. */
    fun setCode(next: LiveCrypto.SyncCode?) = call {
        disconnect()
        basesDir.deleteRecursively(); outboxDir.deleteRecursively(); heldDir.deleteRecursively()
        trackers.clear(); dirty.clear(); dropIncoming(); fetching.clear(); applying = false; retries = 0; epoch++
        code = next
        saved = saved.copy(code = next?.text, after = 0); store(); highest = 0
        state = if (next == null) State.OFF else State.PAUSED; detail = null; failures = 0
        publish()
        if (next != null && wanted) connect()
    }

    /** While the app is in front: stay connected (reconnecting with growing pauses up to 30 s). */
    fun start() = queue.execute { wanted = true; failures = 0; connect() }
    /** When the app goes to the background. Unsent changes stay in the outbox. */
    fun stop() = queue.execute {
        wanted = false; flushBases(); disconnect()
        if (code != null) { state = State.PAUSED; detail = null }
        publish()
    }

    /** A notebook was opened: start following it from here if it has no base yet ([reset]: from here anyway). */
    fun opened(notebook: Notebook, reset: Boolean = false) = queue.execute {
        if (code == null) return@execute
        if (reset || tracker(notebook.id) == null) { trackers[notebook.id] = LiveChanges.Tracker(LiveChanges.baseOf(notebook)); changed(notebook.id) }
    }

    /** A notebook was saved: send what changed since the last save. Call on the thread that edits, in edit order. */
    fun saved(notebook: Notebook) = queue.execute {
        val key = code?.key ?: return@execute
        val tracker = tracker(notebook.id)
        if (tracker == null) { trackers[notebook.id] = LiveChanges.Tracker(LiveChanges.baseOf(notebook)); changed(notebook.id); return@execute }
        val before = tracker.base.assets
        val set = runCatching { tracker.diff(notebook, saved.device) }.getOrElse { trackers[notebook.id] = LiveChanges.Tracker(LiveChanges.baseOf(notebook)); null }
        changed(notebook.id)
        if (set == null || set.empty) return@execute
        val plain = LiveChanges.encode(set)
        var sealed = LiveCrypto.toText(LiveCrypto.seal(key, plain))
        // P719: new photos (and no other new files) go live; the folder set below is kept for the other ways.
        if (set.folder && photosOk && sealed.length <= MAX_SEALED_CHARS && sendPhotos(key, notebook, set, before, plain, sealed)) { publish(); return@execute }
        val folder = set.folder || sealed.length > MAX_SEALED_CHARS
        if (folder && !set.folder) sealed = LiveCrypto.toText(LiveCrypto.seal(key, LiveChanges.encode(LiveChanges.ChangeSet(notebook = notebook.id, device = saved.device, folder = true))))
        // B79: a set made after a held one for the same notebook (ink on a page just added with a photo) waits behind it,
        // so the other device fetches the new page before it is sent changes to that page.
        val behind = !folder && notebook.id in heldNotebooks(key)
        val target = if (folder || behind) heldDir else outboxDir
        target.mkdirs()
        val id = newId()
        File(target, "%016d-%s".format(System.currentTimeMillis(), id)).writeText(sealed)
        if (folder) events.folderSyncWanted() else if (!behind) pump()
        publish()
    }

    /**
     * P719: [set] adds photos and no other files: each photo goes to the outbox in sealed pieces, then the set itself with
     * folder off, naming the photos and the folder set it [LiveChanges.ChangeSet.covers]. That folder set ([classic]) is
     * held until folder sync has uploaded the notebook, as before, for apps before P719 and as the fallback; marked "-p",
     * it does not hold back later sets (ink on the new page goes at once). False when [set] is not one for this way.
     * Pieces are read from the photo's bytes as they are sealed, so no second copy of a photo is made.
     */
    private fun sendPhotos(key: ByteArray, notebook: Notebook, set: LiveChanges.ChangeSet, before: Set<String>, plain: ByteArray, classic: String): Boolean {
        val added = LiveChanges.assetIds(notebook) - before
        if (added.isEmpty() || added.any { !it.startsWith("i:") }) return false
        val photos = notebook.imageAssets.filter { "i:" + it.id in added }
        if (photos.size != added.size) return false
        val refs = photos.map { LiveChanges.PhotoRef(id = it.id, blob = newId(), size = it.size, parts = (it.size + PIECE_BYTES - 1) / PIECE_BYTES) }
        val live = LiveCrypto.toText(LiveCrypto.seal(key, LiveChanges.encode(set.copy(folder = false, photos = refs, covers = sha256Hex(plain)))))
        if (live.length > MAX_SEALED_CHARS) return false
        val time = System.currentTimeMillis()
        outboxDir.mkdirs()
        val written = ArrayList<File>()
        try {
            for ((photo, ref) in photos.zip(refs)) photo.openStream().use { input ->
                val buffer = ByteArray(PIECE_BYTES)
                for (part in 0 until ref.parts) {
                    val n = input.readNBytes(buffer, 0, PIECE_BYTES)
                    val file = File(outboxDir, "%016d-%s-%04d-%04d.piece".format(time, ref.blob, part, ref.parts))
                    file.writeText(LiveCrypto.toText(LiveCrypto.seal(key, if (n == PIECE_BYTES) buffer else buffer.copyOf(n))))
                    written += file
                }
            }
        } catch (e: Exception) { written.forEach { it.delete() }; return false }
        heldDir.mkdirs()
        File(heldDir, "%016d-%s-p".format(time, newId())).writeText(classic)
        File(outboxDir, "%016d-%s".format(time + 1, newId())).writeText(live)
        events.folderSyncWanted()
        pump()
        return true
    }
    /** The notebooks that held change sets belong to; P719: not those whose photos were sent live ("-p"). */
    private fun heldNotebooks(key: ByteArray): Set<String> = heldDir.listFiles().orEmpty().filter { !it.name.endsWith("-p") }.mapNotNullTo(HashSet()) { file ->
        runCatching { LiveChanges.decode(LiveCrypto.open(key, LiveCrypto.fromText(file.readText()))).notebook }.getOrNull()
    }

    /** Folder sync finished: the files that held change sets wait for are uploaded, so the other devices can fetch them. */
    fun folderSynced() = queue.execute {
        // B79: the notebooks other devices changed are fetched, so changes to them that waited can be taken in now.
        if (fetching.isNotEmpty()) { fetching.clear(); next() }
        val held = heldDir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (held.isEmpty()) return@execute
        outboxDir.mkdirs()
        for (file in held) file.renameTo(File(outboxDir, file.name))
        pump()
        publish()
    }

    /**
     * B79: folder sync combined changes from both devices into these notebooks and uploaded them: tell the other
     * devices to fetch them now (a set with only [LiveChanges.ChangeSet.folder]), instead of waiting for their next sync.
     */
    fun folderChanged(ids: Collection<String>) = queue.execute {
        val key = code?.key ?: return@execute
        if (ids.isEmpty()) return@execute
        outboxDir.mkdirs()
        for (notebook in ids.distinct()) {
            val sealed = LiveCrypto.toText(LiveCrypto.seal(key, LiveChanges.encode(LiveChanges.ChangeSet(notebook = notebook, device = saved.device, folder = true))))
            File(outboxDir, "%016d-%s".format(System.currentTimeMillis(), newId())).writeText(sealed)
        }
        pump()
        publish()
    }

    /** Writes the bases now (before the process may end). */
    fun flushNow() = call { flushBases() }

    private fun tracker(id: String): LiveChanges.Tracker? = trackers[id] ?: runCatching {
        LiveChanges.Tracker(LiveChanges.decodeBase(File(basesDir, "$id.json").readBytes()))
    }.getOrNull()?.also { trackers[id] = it; trim() }
    private fun trim() {
        while (trackers.size > KEEP_TRACKERS) {
            val oldest = trackers.keys.first()
            if (oldest in dirty) writeBase(oldest)
            trackers.remove(oldest)
        }
    }
    private fun changed(id: String) {
        dirty += id; trim()
        if (flush == null) flush = queue.schedule({ flush = null; flushBases() }, BASE_FLUSH_MS, TimeUnit.MILLISECONDS)
    }
    private fun writeBase(id: String) {
        val tracker = trackers[id] ?: return
        runCatching {
            basesDir.mkdirs()
            val temp = File(basesDir, "$id.json.tmp")
            temp.writeBytes(LiveChanges.encodeBase(tracker.base))
            val file = File(basesDir, "$id.json")
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
        dirty -= id
    }
    private fun flushBases() { dirty.toList().forEach(::writeBase) }

    // ---- connection ----
    private fun connect() {
        val current = code ?: return
        if (!wanted || socket != null || state == State.DENIED) return
        reconnect?.cancel(false); reconnect = null
        val gen = ++generation
        state = State.CONNECTING; publish()
        val request = runCatching { Request.Builder().url(socketUrl(current.server)).build() }.getOrElse {
            state = State.WAITING; detail = "The server address is not valid"; publish(); return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = queue.execute { if (gen == generation) opened(webSocket, current) }
            override fun onMessage(webSocket: WebSocket, text: String) = queue.execute { if (gen == generation) message(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                queue.execute { if (gen == generation) lost(webSocket, reason.ifBlank { "The server closed the connection" }, code == 4003) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = queue.execute {
                if (gen == generation) lost(webSocket, when (response?.code) { 404 -> "No sync server at this address"; null -> t.message ?: "No connection"; else -> "The server answered ${response.code}" }, false)
            }
        })
    }
    private fun opened(webSocket: WebSocket, current: LiveCrypto.SyncCode) {
        heard = System.currentTimeMillis()
        sent.clear()
        webSocket.send(buildJsonObject { put("t", "hello"); put("token", LiveCrypto.token(current.key)); put("device", saved.device); put("after", saved.after) }.toString())
        pinger?.cancel(false)
        pinger = queue.scheduleWithFixedDelay({
            val s = socket ?: return@scheduleWithFixedDelay
            if (System.currentTimeMillis() - heard > SILENT_MS) { s.cancel(); return@scheduleWithFixedDelay }
            s.send("ping")
        }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
    }
    private fun lost(webSocket: WebSocket, reason: String?, denied: Boolean) {
        if (socket !== webSocket) return
        socket = null; welcomed = false; pinger?.cancel(false); pinger = null
        if (denied) { state = State.DENIED; detail = "This server belongs to another sync code"; publish(); return }
        if (!wanted || code == null) return
        failures++
        state = State.WAITING; detail = reason; publish()
        val wait = (1000L shl minOf(failures - 1, 5)).coerceAtMost(30_000L)
        reconnect = queue.schedule({ reconnect = null; connect() }, wait, TimeUnit.MILLISECONDS)
    }
    private fun disconnect() {
        generation++
        reconnect?.cancel(false); reconnect = null
        pinger?.cancel(false); pinger = null
        socket?.close(1000, "Bye"); socket = null; welcomed = false
    }
    /**
     * Sends the outbox in the order it was made: change sets ("put") and, P719, pieces of photos ("piece"; dropped when
     * the server does not keep photos). Before the server's welcome nothing goes (a put before hello would be refused);
     * the welcome sends the rest. P719: a few MB at a time, as OkHttp closes a WebSocket with more than 16 MiB queued.
     * Files stay until the server confirms them ("ok", "pok"); the server ignores ones it already has.
     */
    private fun pump() {
        val s = socket ?: return
        if (!welcomed) return
        for (f in outboxDir.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (f.name in sent) continue
            if (s.queueSize() > SEND_QUEUE_BYTES) {
                if (pumping == null) pumping = queue.schedule({ pumping = null; pump() }, 50, TimeUnit.MILLISECONDS)
                return
            }
            val body = runCatching { f.readText() }.getOrNull() ?: continue
            val message = if (!f.name.endsWith(".piece")) buildJsonObject { put("t", "put"); put("id", f.name.substringAfter('-')); put("b", body) }
            else {
                val parts = f.name.removeSuffix(".piece").split('-')
                if (!photosOk || parts.size != 4) { f.delete(); continue }
                buildJsonObject { put("t", "piece"); put("id", parts[1]); put("part", parts[2].toInt()); put("parts", parts[3].toInt()); put("b", body) }
            }
            if (!s.send(message.toString())) return
            sent += f.name
        }
    }

    private fun message(text: String) {
        heard = System.currentTimeMillis()
        if (text == "pong") return
        val m = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (m["t"]?.jsonPrimitive?.content) {
            "welcome" -> {
                failures = 0; detail = null; welcomed = true
                photosOk = m["photos"] != null
                // Unsent change sets go first, in the order they were made; the server ignores ones it already has.
                pump()
                // P719: photos being fetched when the connection dropped are asked for again (or go the folder way).
                download?.let { d -> if (photosOk) { d.asked.clear(); askPieces(d) } else photosFailed(d) }
            }
            "e" -> entry(m)
            "live" -> { state = State.LIVE; publish() }
            "ok" -> {
                val id = m["id"]?.jsonPrimitive?.content ?: return
                outboxDir.listFiles()?.filter { it.name.substringAfter('-') == id }?.forEach { it.delete() }
                publish()
            }
            // P719: the server has a piece of this device's photo, or has no room for that photo (its set then makes the
            // other devices fetch the notebook through folder sync).
            "pok" -> {
                val id = m["id"]?.jsonPrimitive?.content ?: return
                val part = m["part"]?.jsonPrimitive?.int ?: return
                outboxDir.listFiles()?.filter { it.name.endsWith(".piece") && it.name.substringAfter('-').startsWith("$id-%04d-".format(part)) }?.forEach { it.delete() }
            }
            "full" -> {
                val id = m["id"]?.jsonPrimitive?.content ?: return
                outboxDir.listFiles()?.filter { it.name.endsWith(".piece") && it.name.substringAfter('-').startsWith("$id-") }?.forEach { it.delete() }
            }
            "piece" -> piece(m)
            "nophoto" -> { val d = download ?: return; if (d.refs.any { it.blob == m["id"]?.jsonPrimitive?.content }) photosFailed(d) }
            "reset" -> {
                // Older changes are gone from the server (or it was set up again): folder sync brings this device up to date.
                saved = saved.copy(after = m["last"]?.jsonPrimitive?.long ?: 0); store()
                dropIncoming(); fetching.clear(); highest = saved.after; events.folderSyncWanted()
            }
            "denied" -> { state = State.DENIED; detail = "This server belongs to another sync code"; publish() }
        }
    }
    private fun entry(m: JsonObject) {
        val seq = m["seq"]?.jsonPrimitive?.long ?: return
        if (seq <= maxOf(saved.after, highest)) return
        highest = seq
        // This device's own change (seen again while catching up) needs no file: there is nothing to take in.
        if (m["d"]?.jsonPrimitive?.content != saved.device) {
            val sealed = m["b"]?.jsonPrimitive?.content
            val kept = sealed != null && runCatching { incomingDir.mkdirs(); File(incomingDir, seq.toString()).writeText(sealed) }.isSuccess
            // Not kept (no room): folder sync brings the notebook up to date instead.
            if (!kept) events.folderSyncWanted()
        }
        incoming.addLast(seq)
        next()
    }
    /** The first waiting entry and its set, read once (null: this device's own, or one that could not be read). */
    private fun first(): Waiting? {
        val seq = incoming.firstOrNull() ?: return null
        head?.let { if (it.seq == seq) return it }
        val file = File(incomingDir, seq.toString())
        var covers = false
        val set = if (!file.isFile) null else runCatching {
            val plain = LiveCrypto.open(code!!.key, LiveCrypto.fromText(file.readText()))
            LiveChanges.decode(plain).also { if (it.folder) covers = sha256Hex(plain) in coveredSets() }
        }.getOrNull()
        return Waiting(seq, set, covers).also { head = it }
    }
    /** An entry is done with: its file goes and the server's place moves past it. */
    private fun finish(seq: Long) {
        if (head?.seq == seq) head = null
        File(incomingDir, seq.toString()).delete()
        advance(seq)
    }
    private fun dropIncoming() {
        incoming.clear(); head = null; incomingDir.deleteRecursively()
        download = null; photoFailed = null; photosDir.deleteRecursively()
    }

    // ---- P719: photos taken in live ----
    /** Folder sets (by the checksum of their contents) whose photos were taken in live, the newest [KEEP_COVERED]. */
    private fun coveredSets(): LinkedHashSet<String> = covered ?: LinkedHashSet(runCatching { coveredFile.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }).also { covered = it }
    private fun cover(hash: String) {
        val all = coveredSets()
        all.remove(hash); all.add(hash)
        while (all.size > KEEP_COVERED) all.remove(all.first())
        runCatching { dir.mkdirs(); coveredFile.writeText(all.joinToString("\n", postfix = "\n")) }
    }
    private fun ready(set: LiveChanges.ChangeSet) = set.photos.orEmpty().all { File(photosDir, it.id).isFile }
    /**
     * Fetches the photos of the first waiting set [seq] (once): true while they are on their way, false when they cannot
     * come (no photos on this server, or they failed): the set then goes the folder way.
     */
    private fun fetchPhotos(seq: Long, set: LiveChanges.ChangeSet): Boolean {
        if (photoFailed == seq || (welcomed && !photosOk)) return false
        if (download?.seq == seq) return true
        val turn = ++downloadTurn
        val d = Download(seq, set.photos.orEmpty().filter { !File(photosDir, it.id).isFile }, turn)
        download = d
        val expected = epoch
        queue.schedule({ if (expected == epoch && download?.turn == turn) photosFailed(d) }, PHOTO_WAIT_MS, TimeUnit.MILLISECONDS)
        if (welcomed) askPieces(d)
        return true
    }
    /** Asks for the next pieces of the first photo still missing, [FETCH_AHEAD] at a time. */
    private fun askPieces(d: Download) {
        val ref = d.refs.firstOrNull { !File(photosDir, it.id).isFile } ?: return
        val s = socket ?: return
        val have = File(photosDir, ref.blob).list()?.size ?: 0
        var next = maxOf(d.asked[ref.blob] ?: 0, have)
        while (next < ref.parts && next < have + FETCH_AHEAD) {
            if (!File(photosDir, "${ref.blob}/$next").isFile) s.send(buildJsonObject { put("t", "fetch"); put("id", ref.blob); put("part", next) }.toString())
            next++
        }
        d.asked[ref.blob] = next
    }
    /** A piece of a photo being fetched: kept sealed on disk; a whole photo is opened piece by piece and checked. */
    private fun piece(m: JsonObject) {
        val d = download ?: return
        val id = m["id"]?.jsonPrimitive?.content ?: return
        val ref = d.refs.firstOrNull { it.blob == id } ?: return
        val part = m["part"]?.jsonPrimitive?.int ?: return
        val body = m["b"]?.jsonPrimitive?.content ?: return
        if (part !in 0 until ref.parts || m["parts"]?.jsonPrimitive?.int != ref.parts) return photosFailed(d)
        val pieces = File(photosDir, ref.blob)
        if (runCatching { pieces.mkdirs(); File(pieces, part.toString()).writeText(body) }.isFailure) return photosFailed(d)
        if ((pieces.list()?.size ?: 0) < ref.parts) { askPieces(d); return }
        val key = code?.key ?: return
        val part1 = File(photosDir, "${ref.id}.part")
        val checked = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            part1.outputStream().use { out ->
                for (i in 0 until ref.parts) {
                    val bytes = LiveCrypto.open(key, LiveCrypto.fromText(File(pieces, i.toString()).readText()))
                    digest.update(bytes); out.write(bytes); size += bytes.size
                }
            }
            size == ref.size.toLong() && digest.digest().joinToString("") { "%02x".format(it) } == ref.id
        }.getOrDefault(false)
        pieces.deleteRecursively()
        if (!checked || !part1.renameTo(File(photosDir, ref.id))) { part1.delete(); return photosFailed(d) }
        socket?.send(buildJsonObject { put("t", "got"); put("id", ref.blob) }.toString())
        if (d.refs.all { File(photosDir, it.id).isFile }) { download = null; next() } else askPieces(d)
    }
    /** The photos of [d]'s set could not be fetched: that set goes the folder way. */
    private fun photosFailed(d: Download) {
        if (download !== d) return
        download = null; photoFailed = d.seq
        d.refs.forEach { File(photosDir, it.blob).deleteRecursively() }
        next()
    }
    private fun dropPhotos(set: LiveChanges.ChangeSet) = set.photos.orEmpty().forEach { File(photosDir, it.id).delete(); File(photosDir, it.blob).deleteRecursively() }
    /** Hands the next incoming change to the app; the one after waits until it is done. */
    private fun next() {
        // A loop, not a call per entry: catching up can pass thousands of this device's own entries at once.
        while (!applying) {
            val waiting = first() ?: return
            val seq = waiting.seq
            val set = waiting.set
            // B79: a change to a notebook that folder sync is fetching waits (with the ones after it, in order) until the
            // fetch is done or FETCH_WAIT_MS passed: it may be ink on a page this device does not have yet.
            if (set != null && !set.folder && set.notebook in fetching) return
            // P719: a set with photos waits (and the ones after it) until they are fetched; if they cannot be, its notebook
            // comes through folder sync, when the folder set sent for the same change arrives.
            val photos = set != null && !set.folder && !set.photos.isNullOrEmpty()
            if (photos && !ready(set!!) && fetchPhotos(seq, set)) return
            incoming.removeFirst()
            if (photoFailed == seq) photoFailed = null
            // This device's own change, or one that could not be read: nothing to do.
            if (set == null) { finish(seq); continue }
            // P719: the folder set of a change whose photos were taken in live: nothing to fetch.
            if (set.folder && waiting.covered) { finish(seq); continue }
            if (photos && !ready(set)) { dropPhotos(set); events.folderSyncWanted(); finish(seq); continue }
            // New files: the notebook is fetched through folder sync.
            if (set.folder) {
                val turn = ++fetchTurn
                val expected = epoch
                fetching[set.notebook] = turn
                queue.schedule({ if (expected == epoch && fetching[set.notebook] == turn) { fetching.remove(set.notebook); next() } }, FETCH_WAIT_MS, TimeUnit.MILLISECONDS)
                events.folderSyncWanted(); finish(seq); continue
            }
            applying = true
            var answered = false
            val expected = epoch
            // P724: the sets right after it for the same notebook go with it, so a closed notebook is written once for all.
            val run = if (events.runs && !photos) followers(set) else emptyList()
            val sets = if (run.isEmpty()) listOf(set) else listOf(set) + run.mapNotNull { it.set }
            val done: (Taken) -> Unit = { taken ->
                queue.execute {
                    if (answered || expected != epoch) return@execute
                    answered = true
                    if (taken == Taken.LATER && ++retries <= MAX_RETRIES) {
                        // Offered again soon; it stays first, so the changes after it keep waiting in order.
                        incoming.addFirst(seq); head = waiting
                        queue.schedule({ if (expected == epoch && applying) { applying = false; next() } }, RETRY_MS, TimeUnit.MILLISECONDS)
                        return@execute
                    }
                    applying = false; retries = 0
                    when (taken) {
                        Taken.OPEN -> tracker(set.notebook)?.let { t -> sets.forEach { t.takeIn(it) }; changed(set.notebook) }
                        // P719: the base knows the photos, so the next save here does not send them back.
                        Taken.FILE -> if (photos) tracker(set.notebook)?.let { it.knowPhotos(set.photos.orEmpty().map { p -> p.id }); changed(set.notebook) }
                        Taken.LATER, Taken.NOT -> events.folderSyncWanted()
                    }
                    // P719: taken in, so the folder set sent for the same change needs no fetch here.
                    if (photos && (taken == Taken.OPEN || taken == Taken.FILE)) set.covers?.let(::cover)
                    if (photos) dropPhotos(set)
                    finish(seq)
                    // P724: the rest of the run, still first in the queue unless a reset emptied it (then nothing is left to finish).
                    for (w in run) { if (incoming.firstOrNull() != w.seq) break; incoming.removeFirst(); finish(w.seq) }
                    next()
                }
            }
            runCatching { if (run.isEmpty()) events.incoming(set, done) else events.incomingRun(sets, done) }.onFailure { done(Taken.NOT) }
            return
        }
    }
    /**
     * P724: the entries now first in the queue (the one being offered, [set], is already off it) that can be taken in with
     * [set]: sets for the same notebook without photos or new files, and this device's own entries between them (nothing
     * to take in). Read from their files only for this
     * turn; at most [RUN_SETS] sets and [RUN_CHARS] sealed characters, so a long backlog never sits in memory at once.
     */
    private fun followers(set: LiveChanges.ChangeSet): List<Waiting> {
        if (set.folder) return emptyList()
        val key = code?.key ?: return emptyList()
        val run = ArrayList<Waiting>()
        var sets = 0
        var chars = 0L
        for (i in 0 until incoming.size) {
            if (sets >= RUN_SETS) break
            val seq = incoming[i]
            val file = File(incomingDir, seq.toString())
            if (!file.isFile) { run += Waiting(seq, null, false); continue }
            val text = runCatching { file.readText() }.getOrNull() ?: break
            if (chars + text.length > RUN_CHARS) break
            val next = runCatching { LiveChanges.decode(LiveCrypto.open(key, LiveCrypto.fromText(text))) }.getOrNull() ?: break
            if (next.notebook != set.notebook || next.folder || !next.photos.isNullOrEmpty()) break
            run += Waiting(seq, next, false)
            sets++; chars += text.length
        }
        // It ends with a set: entries after the last one wait for their own turn.
        while (run.isNotEmpty() && run.last().set == null) run.removeAt(run.lastIndex)
        return run
    }
    private fun advance(seq: Long) {
        if (seq > saved.after) { saved = saved.copy(after = seq); store() }
    }
}
