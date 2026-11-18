package dev.ownednotes.app

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.ownednotes.core.FolderSync
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * P674 (the user's yes of 17 November, "My Scribella start hang"): every read, write, listing and delete in a sync or
 * backup folder picked through the system file picker (RSAF for OneDrive) runs in the app's own ":folder" process.
 * On 13 November a file served by RSAF stopped answering, the app's read sat in the kernel, the app could not be killed and
 * would not start again until the tablet restarted. Now the app only waits, with a time limit, for the folder process:
 * when the folder stops answering the call fails as "unavailable", the folder process is stopped and the next call starts
 * a fresh one. File contents come back through a pipe the app reads with a time limit, so the app itself never touches
 * the provider's files. Sync and backup logic is unchanged; only where [LibrarySync.TreeFolder] runs changes.
 */
object FolderProcess {
    internal const val DESCRIPTOR = "dev.ownednotes.app.FolderProcess"
    internal const val OPEN = 1
    internal const val LIST = 2
    internal const val READ = 3
    internal const val WRITE = 4
    internal const val DELETE = 5
    internal const val BACKUP_OPEN = 6
    internal const val NAMES = 7
    internal const val BACKUP_WRITE = 8
    internal const val RENAME = 9
    internal const val OK = 0
    internal const val FAILED = 1
    internal const val STALE = 2
    internal const val PROCESS = ":folder"

    /** Opening a folder, listing, deleting and opening a file for reading. Tests shorten these. */
    @Volatile internal var answerMillis = 60_000L
    /** How long a file being read may send nothing at all. */
    @Volatile internal var stallMillis = 60_000L
    /** Writing: [answerMillis] plus this for every megabyte. */
    @Volatile internal var writeMillisPerMegabyte = 8_000L
    private const val CONNECT_MILLIS = 20_000L
    private const val IDLE_MILLIS = 60_000L
    internal fun resetLimits() { answerMillis = 60_000L; stallMillis = 60_000L; writeMillisPerMegabyte = 8_000L }

    /** The folder [rootName] inside [tree], created on first use unless [createIfMissing] is false; like [LibrarySync.TreeFolder]. */
    fun folder(context: Context, tree: Uri, createIfMissing: Boolean = true, rootName: String = LibrarySync.folderName(context)): FolderSync.NestedFolder =
        Remote(context.applicationContext, tree, createIfMissing, emptyList(), rootName)

    /** A failure of the folder process itself, or one that could not be carried across as its own type. */
    internal class Failure(message: String, override val syncProblemKind: FolderSync.ProblemKind, cause: Throwable? = null) :
        IOException(message, cause), FolderSync.ClassifiedFailure
    private class Stale : Exception()
    private fun unavailable(message: String, cause: Throwable? = null) = Failure(message, FolderSync.ProblemKind.UNAVAILABLE, cause)
    private const val STOPPED = "The sync folder stopped answering. Your notebooks were not changed; try again later."

    /** The backup folder [folderName] inside [tree], created on first use; like [LibraryBackup.TreeFolder]. */
    fun backupFolder(context: Context, tree: Uri, folderName: String = LibraryBackup.folderName(context)): LibraryBackup.Folder =
        RemoteBackup(context.applicationContext, tree, folderName)

    /** A folder opened in the folder process, known there by a number. */
    private abstract class Opened(val context: Context) {
        @Volatile private var handle = 0L
        protected abstract fun open(): Long
        // Resolved at once, like TreeFolder, so a missing folder fails when it is opened and not at the first listing.
        protected fun start() { handle = open() }
        /** A folder process started since this folder was opened no longer knows it: open it again, once. */
        protected fun <T> onFolder(code: Int, millis: Long, args: (Parcel) -> Unit, result: (Parcel) -> T): T {
            try { return call(context, code, millis, { p -> p.writeLong(handle); args(p) }, result) } catch (_: Stale) { }
            handle = open()
            return call(context, code, millis, { p -> p.writeLong(handle); args(p) }, result)
        }
        protected fun readPipe(name: String): InputStream = onFolder(READ, answerMillis, { it.writeString(name) }) { r ->
            PipeInput(context, ParcelFileDescriptor.CREATOR.createFromParcel(r))
        }
        protected fun writeMillis(from: File) = answerMillis + from.length() / (1 shl 20) * writeMillisPerMegabyte + writeMillisPerMegabyte
    }

    private class RemoteBackup(context: Context, val tree: Uri, val folderName: String) : Opened(context), LibraryBackup.Folder {
        init { start() }
        override fun open(): Long = call(context, BACKUP_OPEN, answerMillis, { p -> p.writeString(tree.toString()); p.writeString(folderName) }) { it.readLong() }
        override fun names(): Set<String> = onFolder(NAMES, answerMillis, { }) { r -> r.createStringArrayList()!!.toSet() }
        override fun write(name: String, from: File): String =
            onFolder(BACKUP_WRITE, writeMillis(from), { p -> p.writeString(name); p.writeString(from.absolutePath) }) { it.readString()!! }
        override fun read(name: String): InputStream = readPipe(name)
        override fun rename(from: String, to: String): String = onFolder(RENAME, answerMillis, { p -> p.writeString(from); p.writeString(to) }) { it.readString()!! }
        override fun delete(name: String) = onFolder(DELETE, answerMillis, { it.writeString(name) }) { }
    }

    private class Remote(context: Context, val tree: Uri, val create: Boolean, val path: List<String>, val rootName: String) : Opened(context), FolderSync.NestedFolder {
        init { start() }
        override fun open(): Long = call(context, OPEN, answerMillis, { p ->
            p.writeString(tree.toString()); p.writeInt(if (create) 1 else 0); p.writeString(rootName); p.writeStringList(path)
        }) { it.readLong() }
        override fun child(name: String, create: Boolean): FolderSync.NestedFolder {
            FolderSync.requireChildName(name)
            return Remote(context, tree, create, path + name, rootName)
        }
        override fun list(): Map<String, FolderSync.Stat> = onFolder(LIST, answerMillis, { }) { r ->
            LinkedHashMap<String, FolderSync.Stat>().apply { repeat(r.readInt()) { put(r.readString()!!, FolderSync.Stat(r.readLong(), r.readLong())) } }
        }
        override fun read(name: String): InputStream = readPipe(name)
        override fun write(name: String, from: File): FolderSync.Stat =
            onFolder(WRITE, writeMillis(from), { p ->
                p.writeString(name); p.writeString(from.absolutePath)
            }) { r -> FolderSync.Stat(r.readLong(), r.readLong()) }
        override fun delete(name: String) = onFolder(DELETE, answerMillis, { it.writeString(name) }) { }
    }

    /** The read end of a file the folder process copies across. Never blocks longer than [stallMillis] without data. */
    private class PipeInput(val context: Context, val pipe: ParcelFileDescriptor) : InputStream() {
        private val fd = pipe.fileDescriptor
        private var ended = false
        private var closed = false
        init { active.incrementAndGet() }
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) { val n = read(one, 0, 1); if (n < 0) return -1; if (n == 1) return one[0].toInt() and 0xff }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0
            if (ended) return -1
            val wait = StructPollfd().also { it.fd = fd; it.events = OsConstants.POLLIN.toShort() }
            val end = SystemClock.uptimeMillis() + stallMillis
            while (true) {
                val left = end - SystemClock.uptimeMillis()
                if (left <= 0) { stop(context); throw unavailable(STOPPED) }
                val ready = try { Os.poll(arrayOf(wait), left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
                    catch (e: ErrnoException) { if (e.errno == OsConstants.EINTR) continue; throw unavailable("The sync folder could not be read", e) }
                if (ready <= 0) continue
                val n = try { Os.read(fd, b, off, len) }
                    catch (e: ErrnoException) { if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue; throw unavailable("The sync folder could not be read", e) }
                if (n > 0) return n
                ended = true
                // The folder process says whether the whole file came across or the read failed part way.
                try { pipe.checkError() } catch (e: IOException) { throw unavailable("The sync folder could not finish reading the file", e) }
                return -1
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            runCatching { pipe.close() }
            active.decrementAndGet(); touched()
        }
    }

    // The connection to the folder process. Calls never wait on the main thread, and a stuck call only ever holds a worker.
    private val lock = Any()
    @Volatile private var binder: IBinder? = null
    @Volatile private var connection: ServiceConnection? = null
    private var bound: Context? = null
    private val active = AtomicInteger()
    private val lastUse = AtomicLong()
    private val calls = Executors.newCachedThreadPool { Thread(it, "folder-call").apply { isDaemon = true } }
    private val callbacks = Executors.newSingleThreadExecutor { Thread(it, "folder-connection").apply { isDaemon = true } }
    private val idle = Executors.newSingleThreadScheduledExecutor { Thread(it, "folder-idle").apply { isDaemon = true } }

    private fun service(context: Context): IBinder {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        synchronized(lock) {
            binder?.takeIf { it.isBinderAlive }?.let { return it }
            release()
            val connected = CountDownLatch(1)
            val link = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (connection === this) binder = service
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { if (connection === this) binder = null }
                override fun onBindingDied(name: ComponentName) { if (connection === this) binder = null }
                override fun onNullBinding(name: ComponentName) { connected.countDown() }
            }
            connection = link; bound = context
            val started = try { context.bindService(Intent(context, FolderService::class.java), Context.BIND_AUTO_CREATE, callbacks, link) }
                catch (e: SecurityException) { false }
            if (!started) { release(); throw unavailable("The sync folder process could not be started") }
            // A folder process that cannot die (stuck in the kernel) also blocks a new one starting; that is an error here, not a wait.
            if (!connected.await(CONNECT_MILLIS, TimeUnit.MILLISECONDS) || binder == null) { release(); throw unavailable(STOPPED) }
            return binder!!
        }
    }

    /** Lets the folder process go (it stops when nothing else uses it). */
    internal fun release() {
        synchronized(lock) {
            val link = connection
            connection = null; binder = null
            if (link != null) runCatching { bound?.unbindService(link) }
            bound = null
        }
    }

    /** Stops a folder process that stopped answering, so its stuck thread goes with it. */
    private fun stop(context: Context) {
        release()
        processIds(context).forEach { Process.killProcess(it) }
    }
    internal fun processIds(context: Context): List<Int> {
        val name = context.packageName + PROCESS
        return context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty().filter { it.processName == name }.map { it.pid }
    }

    private fun touched() {
        lastUse.set(SystemClock.uptimeMillis())
        runCatching {
            idle.schedule({ if (active.get() == 0 && SystemClock.uptimeMillis() - lastUse.get() >= IDLE_MILLIS) release() }, IDLE_MILLIS + 1_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun <T> call(context: Context, code: Int, millis: Long, args: (Parcel) -> Unit, result: (Parcel) -> T): T {
        active.incrementAndGet()
        try {
            val service = service(context)
            val work = calls.submit(Callable {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR); args(data)
                    try { service.transact(code, data, reply, 0) } catch (e: RemoteException) { throw unavailable("The sync folder process stopped", e) }
                    when (reply.readInt()) {
                        OK -> result(reply)
                        STALE -> throw Stale()
                        else -> throw failure(reply)
                    }
                } finally { data.recycle(); reply.recycle() }
            })
            return try { work.get(millis, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { work.cancel(true); stop(context); throw unavailable(STOPPED) }
            catch (e: ExecutionException) { throw e.cause ?: e }
            catch (e: InterruptedException) { work.cancel(true); Thread.currentThread().interrupt(); throw InterruptedIOException("Sync stopped") }
        } finally { active.decrementAndGet(); touched() }
    }

    // How a failure crosses: its type where callers tell types apart, its message, and how sync classifies it.
    private const val NOT_FOUND = 0
    private const val SECURITY = 1
    private const val ARGUMENT = 2
    private const val STATE = 3
    private const val OTHER = 4
    internal fun writeFailure(reply: Parcel, error: Throwable) {
        reply.setDataSize(0); reply.setDataPosition(0)
        reply.writeInt(FAILED)
        reply.writeInt(when (error) {
            is FileNotFoundException -> NOT_FOUND
            is SecurityException -> SECURITY
            is IllegalArgumentException -> ARGUMENT
            is IllegalStateException -> STATE
            else -> OTHER
        })
        reply.writeString(error.message)
        reply.writeString(FolderSync.problemKind(error).name)
    }
    private fun failure(reply: Parcel): Exception {
        val type = reply.readInt()
        val message = reply.readString()
        val kind = runCatching { FolderSync.ProblemKind.valueOf(reply.readString()!!) }.getOrDefault(FolderSync.ProblemKind.UNAVAILABLE)
        return when (type) {
            NOT_FOUND -> FileNotFoundException(message)
            SECURITY -> SecurityException(message)
            ARGUMENT -> IllegalArgumentException(message)
            STATE -> IllegalStateException(message)
            else -> Failure(message ?: "The sync folder failed", kind)
        }
    }
}

/**
 * The ":folder" process side of [FolderProcess]: runs [LibrarySync.TreeFolder] (sync) and [LibraryBackup.TreeFolder]
 * (backups) for the app and nobody else.
 */
class FolderService : Service() {
    private val folders = object : LinkedHashMap<Long, Any>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?) = size > 64
    }
    private val next = AtomicLong(1)
    private fun keep(folder: Any, out: Parcel) {
        val id = next.getAndIncrement()
        synchronized(folders) { folders[id] = folder }
        out.writeInt(FolderProcess.OK); out.writeLong(id)
    }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < FolderProcess.OPEN || code > FolderProcess.RENAME) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Only Scribella may use its sync folder process")
            data.enforceInterface(FolderProcess.DESCRIPTOR)
            val out = reply ?: return true
            try {
                when (code) {
                    FolderProcess.OPEN -> {
                        val tree = Uri.parse(data.readString()!!)
                        val create = data.readInt() != 0
                        val root = data.readString()!!
                        val path = data.createStringArrayList()!!
                        return true.also { keep(LibrarySync.TreeFolder(this@FolderService, tree, create, path, root), out) }
                    }
                    FolderProcess.BACKUP_OPEN -> {
                        val tree = Uri.parse(data.readString()!!)
                        return true.also { keep(LibraryBackup.TreeFolder(this@FolderService, tree, data.readString()!!), out) }
                    }
                }
                val folder = synchronized(folders) { folders[data.readLong()] } ?: run { out.writeInt(FolderProcess.STALE); return true }
                val sync = folder as? LibrarySync.TreeFolder
                val backup = folder as? LibraryBackup.Folder
                when (code) {
                    FolderProcess.LIST -> {
                        val files = sync!!.list()
                        out.writeInt(FolderProcess.OK); out.writeInt(files.size)
                        files.forEach { (name, stat) -> out.writeString(name); out.writeLong(stat.size); out.writeLong(stat.modified) }
                    }
                    FolderProcess.NAMES -> { val names = ArrayList(backup!!.names()); out.writeInt(FolderProcess.OK); out.writeStringList(names) }
                    FolderProcess.READ -> {
                        val name = data.readString()!!
                        val input = sync?.read(name) ?: backup!!.read(name)
                        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (e: Exception) { input.close(); throw e }
                        copy(input, pipe[1])
                        out.writeInt(FolderProcess.OK)
                        pipe[0].writeToParcel(out, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                    }
                    FolderProcess.WRITE -> {
                        val name = data.readString()!!
                        val stat = sync!!.write(name, File(data.readString()!!))
                        out.writeInt(FolderProcess.OK); out.writeLong(stat.size); out.writeLong(stat.modified)
                    }
                    FolderProcess.BACKUP_WRITE -> {
                        val name = data.readString()!!
                        val used = backup!!.write(name, File(data.readString()!!))
                        out.writeInt(FolderProcess.OK); out.writeString(used)
                    }
                    FolderProcess.RENAME -> {
                        val from = data.readString()!!
                        val used = backup!!.rename(from, data.readString()!!)
                        out.writeInt(FolderProcess.OK); out.writeString(used)
                    }
                    FolderProcess.DELETE -> {
                        val name = data.readString()!!
                        if (sync != null) sync.delete(name) else backup!!.delete(name)
                        out.writeInt(FolderProcess.OK)
                    }
                }
            } catch (error: Throwable) { FolderProcess.writeFailure(out, error) }
            return true
        }
    }
    /** Copies the provider's file into the pipe; a failure part way reaches the app as an error, never as a short file. */
    private fun copy(input: InputStream, sink: ParcelFileDescriptor) = Thread({
        try {
            input.use { it.copyTo(FileOutputStream(sink.fileDescriptor), 64 * 1024) }
            sink.close()
        } catch (error: Throwable) { runCatching { sink.closeWithError(error.message ?: "The sync folder failed while reading") } }
    }, "folder-read").apply { isDaemon = true }.start()
    override fun onBind(intent: Intent): IBinder = binder
}
