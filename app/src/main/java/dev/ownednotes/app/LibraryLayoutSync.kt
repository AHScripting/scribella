package dev.ownednotes.app

import android.content.SharedPreferences
import android.os.Looper
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.LibraryLayout
import dev.ownednotes.core.LibraryLayout.Item
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P698: the Library's organisation (folders and their colours, which notebook is in which folder, covers, tags, pinned,
 * archived) travels with sync in [LibraryLayout.FILE_NAME], so every device shows the same arrangement.
 *
 * Changes are stamped when they are made (a preference listener), so the newest change wins rather than the device that
 * synced last. The merge runs on the main thread, where the Library writes these preferences, so a change made by hand
 * while a sync runs is never written over. View choices (sort, date filter, open folder) stay per device.
 */
object LibraryLayoutSync {
    private enum class Kind { PRESENT, PAIR }
    /** [PRESENT]: each saved line is an item. [PAIR]: each line is "key\nvalue". */
    private class Saved(val set: String, val key: String, val kind: Kind)
    private val SAVED = listOf(
        Saved("folders", "libraryFolders", Kind.PRESENT),
        Saved("folderColors", "libraryFolderColors", Kind.PAIR),
        Saved("folderOf", "libraryFolderOf", Kind.PAIR),
        Saved("covers", "libraryCovers", Kind.PAIR),
        Saved("tags", "libraryTags", Kind.PRESENT),
        Saved("pinned", "libraryPinned", Kind.PRESENT),
        Saved("archived", "libraryArchived", Kind.PRESENT))
    private val sets = SAVED.map { it.set }.toSet()
    private val preferenceKeys = SAVED.map { it.key }.toSet()
    private const val KNOWN_FILE = "library-layout.json"
    const val UPDATED = "Library folders updated from your other device."

    /** Held here: preferences keep their listeners only weakly. */
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var cache: Pair<File, Map<Item, LibraryLayout.Entry>>? = null
    /** Main thread, after a sync changed this device's organisation (MainActivity reloads the open Library). */
    var onChanged: (() -> Unit)? = null

    private fun preferences(app: NotesApplication) = app.getSharedPreferences(app.preferencesName, 0)
    private fun knownFile(app: NotesApplication) = File(app.storageRoot, KNOWN_FILE)

    internal fun current(preferences: SharedPreferences): Map<Item, String> {
        val out = HashMap<Item, String>()
        for (s in SAVED) for (line in preferences.getStringSet(s.key, null).orEmpty().sorted()) when (s.kind) {
            Kind.PRESENT -> if (line.isNotEmpty()) out[Item(s.set, line)] = "1"
            Kind.PAIR -> line.split('\n', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { out.putIfAbsent(Item(s.set, it[0]), it[1]) }
        }
        return out
    }

    private fun write(preferences: SharedPreferences, values: Map<Item, String>) {
        val before = current(preferences)
        val edit = preferences.edit()
        for (s in SAVED) {
            val now = values.filterKeys { it.set == s.set }
            if (now == before.filterKeys { it.set == s.set }) continue
            val lines = now.map { (item, value) -> if (s.kind == Kind.PRESENT) item.key else "${item.key}\n$value" }.toSet()
            if (lines.isEmpty()) edit.remove(s.key) else edit.putStringSet(s.key, lines)
        }
        edit.commit()
    }

    private fun known(app: NotesApplication): Map<Item, LibraryLayout.Entry> {
        val file = knownFile(app)
        cache?.takeIf { it.first == file }?.let { return it.second }
        val read = if (file.isFile) runCatching { LibraryLayout.decode(file.readBytes()) }.getOrNull() else null
        if (read != null) { cache = file to read; return read }
        // The first time: this device's arrangement as it is, never newer than a change made anywhere.
        val first = LibraryLayout.baseline(current(preferences(app)))
        saveKnown(app, first)
        return first
    }

    private fun saveKnown(app: NotesApplication, entries: Map<Item, LibraryLayout.Entry>) {
        val file = knownFile(app)
        val part = File(file.parentFile, ".$KNOWN_FILE.part")
        part.writeBytes(LibraryLayout.encode(entries))
        if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
        cache = file to entries
    }

    /** At start: the first time takes this device's arrangement as it is; then every change is stamped as made. */
    fun start(app: NotesApplication) {
        runCatching { known(app) }
        val watch = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in preferenceKeys) runCatching { noteChange(app) } }
        listener = watch
        preferences(app).registerOnSharedPreferenceChangeListener(watch)
    }

    private fun noteChange(app: NotesApplication) {
        val known = known(app)
        val stamped = LibraryLayout.stamp(known, current(preferences(app)), System.currentTimeMillis())
        if (stamped != known) saveKnown(app, stamped)
    }

    /** Main thread: merges [remote] into this device; the merged layout, and whether this device's organisation changed. */
    internal fun merge(app: NotesApplication, remote: Map<Item, LibraryLayout.Entry>): Pair<Map<Item, LibraryLayout.Entry>, Boolean> {
        val preferences = preferences(app)
        val here = current(preferences)
        val local = LibraryLayout.stamp(known(app), here, System.currentTimeMillis())
        val merged = LibraryLayout.merge(local, remote)
        val values = LibraryLayout.values(merged).filterKeys { it.set in sets }
        // Known first, so the listener sees nothing new when the preferences change below.
        saveKnown(app, merged)
        val changed = values != here
        if (changed) { write(preferences, values); onChanged?.invoke() }
        return merged to changed
    }

    /**
     * On a sync worker, inside the sync folder: reads the shared layout, merges it here, and writes it back when it
     * differs. True when this device's organisation changed. A damaged or newer-version file is left alone.
     */
    fun sync(app: NotesApplication, folder: FolderSync.Folder, check: () -> Unit): Boolean {
        check()
        val remote = folder.list()[LibraryLayout.FILE_NAME]?.let { stat ->
            if (stat.size > LibraryLayout.MAX_BYTES) throw LibraryLayout.Invalid("The shared library layout is too large")
            folder.read(LibraryLayout.FILE_NAME).use { LibraryLayout.decode(it.readNBytes(LibraryLayout.MAX_BYTES + 1)) }
        } ?: emptyMap()
        check()
        val (merged, changed) = onMain(app) { merge(app, remote) } ?: return false
        if (merged != remote) {
            check()
            val upload = File.createTempFile("library-layout", ".json", app.cacheDir)
            try { upload.writeBytes(LibraryLayout.encode(merged)); folder.write(LibraryLayout.FILE_NAME, upload) } finally { upload.delete() }
        }
        return changed
    }

    /** Runs [block] on the main thread and waits for it; null if the main thread does not get to it in time. */
    private fun <T> onMain(app: NotesApplication, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        app.main.post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) return null
        return result!!.getOrThrow()
    }
}
