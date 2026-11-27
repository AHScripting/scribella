package dev.ownednotes.core

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * P717: a short text from an Android heap dump (hprof 1.0.3, as Debug.dumpHprofData writes it): what fills the app's
 * heap by class (count and size), this app's own classes, and for the largest of them the fields that hold them,
 * followed up through collections and arrays to the first other class, a static field or a GC root. Class and field
 * names and numbers only, never values, so it can be copied and passed on like the crash note. The file (hundreds of
 * MB) is read a few times, streaming; memory goes to names, class layouts and a sample of the objects.
 */
object HeapSummary {
    private const val MAX_HOPS = 6
    private const val SAMPLE = 4000
    private const val PENDING_LIMIT = 200_000
    private const val BIG_ARRAY = 1L shl 20
    private const val CHAIN_LIMIT = 300_000
    private const val STEPS = 2 + MAX_HOPS

    /** [owner]: the package prefix of this app's classes. [progress] hears which reading is under way. */
    fun of(file: File, owner: String = "dev.ownednotes.", progress: (String) -> Unit = {}): String = Reading(file, owner, progress).summary()

    private val PLUMBING = arrayOf("java.", "javax.", "kotlin.", "kotlinx.", "sun.", "libcore.", "dalvik.", "android.util.",
        "androidx.collection.", "androidx.core.util.", "com.android.internal.util.")

    private class ClassInfo(val superId: Long, val fieldNames: LongArray, val fieldTypes: ByteArray)
    private class Layout(val offsets: IntArray, val names: LongArray)
    /**
     * A sampled object (or one that holds it), with the chain so far, how many sampled objects it stands for, and the
     * sampled object it started from ([origin], 0 once several are merged): a chain that comes back to it is the object's
     * own (a lazy field's initializer pointing back at it), not what holds it.
     */
    private class Pending(val target: Int, val chain: Chain, val hops: Int, var weight: Int, var origin: Long) { var hit = false }
    /**
     * P717: one step of a chain, shared by every chain that starts the same way (a tree), so that hundreds of thousands
     * of chains cost little memory; the text is made only for those printed. On the Pixel the reading ran out of memory
     * when every chain was kept as its own text. [budget] bounds the steps made; past it a chain is counted, not kept.
     */
    private class Chain(val parent: Chain?, val segment: String) {
        private var children: HashMap<String, Chain>? = null
        fun child(segment: String, budget: IntArray): Chain? {
            val map = children ?: HashMap<String, Chain>(2).also { children = it }
            map[segment]?.let { return it }
            if (budget[0] <= 0) return null
            budget[0]--
            return Chain(this, segment).also { map[segment] = it }
        }
        fun text(): String {
            val parts = ArrayList<String>(); var c: Chain? = this
            while (c != null) { parts += c.segment; c = c.parent }
            return parts.asReversed().joinToString(" ← ")
        }
    }
    private class Target(val title: String, first: String, val count: Long, val bytes: Long, val stride: Long) {
        val root = Chain(null, first)
        var seen = 0L; var taken = 0; var other = 0L; val paths = HashMap<Chain, Int>()
    }

    /** By object id (never 0), open addressing: the dumps hold millions of objects. */
    private class IdMap<V : Any> {
        private var keys = LongArray(64)
        private var values = arrayOfNulls<Any>(64)
        var size = 0; private set
        private fun slot(key: Long, mask: Int): Int { val h = key * -0x61c8864680b583ebL; return (h xor (h ushr 29)).toInt() and mask }
        @Suppress("UNCHECKED_CAST")
        operator fun get(key: Long): V? {
            val mask = keys.size - 1; var i = slot(key, mask)
            while (true) { val k = keys[i]; if (k == 0L) return null; if (k == key) return values[i] as V; i = (i + 1) and mask }
        }
        operator fun set(key: Long, value: V) {
            if ((size + 1) * 2 > keys.size) grow()
            val mask = keys.size - 1; var i = slot(key, mask)
            while (true) {
                val k = keys[i]
                if (k == 0L) { keys[i] = key; values[i] = value; size++; return }
                if (k == key) { values[i] = value; return }
                i = (i + 1) and mask
            }
        }
        @Suppress("UNCHECKED_CAST")
        private fun grow() {
            val oldKeys = keys; val oldValues = values
            keys = LongArray(oldKeys.size * 2); values = arrayOfNulls(oldKeys.size * 2); size = 0
            for (i in oldKeys.indices) if (oldKeys[i] != 0L) set(oldKeys[i], oldValues[i] as V)
        }
        @Suppress("UNCHECKED_CAST")
        fun forEach(action: (Long, V) -> Unit) { for (i in keys.indices) if (keys[i] != 0L) action(keys[i], values[i] as V) }
    }

    private class Input(file: File) : Closeable {
        private val stream = FileInputStream(file)
        private val length = file.length()
        private val buf = ByteArray(1 shl 18)
        private var at = 0
        private var end = 0
        private var base = 0L
        var idSize = 4
        val pos: Long get() = base + at
        private fun fill(): Boolean {
            if (at < end) return true
            base += end; at = 0; end = 0
            val n = stream.read(buf, 0, buf.size)
            if (n > 0) end = n
            return end > 0
        }
        fun more(): Boolean = fill()
        fun u1(): Int { if (!fill()) throw EOFException(); return buf[at++].toInt() and 0xFF }
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Int {
            if (end - at < 4) return (u1() shl 24) or (u1() shl 16) or (u1() shl 8) or u1()
            val b = buf; val i = at; at += 4
            return ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        }
        fun len(): Long = u4().toLong() and 0xFFFFFFFFL
        fun id(): Long = if (idSize == 4) len() else (u4().toLong() shl 32) or len()
        fun skip(count: Long) {
            var left = count
            val buffered = minOf(left, (end - at).toLong()).toInt(); at += buffered; left -= buffered
            if (left == 0L) return
            base += end; at = 0; end = 0
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) { base += skipped; left -= skipped; continue }
                if (!fill()) throw EOFException()
                val step = minOf(left, (end - at).toLong()).toInt(); at += step; left -= step
            }
            if (pos > length) throw EOFException()   // a skip can go past the end of a file cut short
        }
        fun read(into: ByteArray, count: Int) {
            var done = 0
            while (done < count) {
                if (!fill()) throw EOFException()
                val step = minOf(count - done, end - at)
                System.arraycopy(buf, at, into, done, step); at += step; done += step
            }
        }
        override fun close() = stream.close()
    }

    private abstract class Visitor {
        open val strings = false
        open val fields = false
        open val elements = false
        open fun string(id: Long, text: String) {}
        open fun loadClass(classId: Long, nameId: Long) {}
        open fun root(kind: Int, id: Long) {}
        open fun classDump(classId: Long, superId: Long, fieldNames: LongArray, fieldTypes: ByteArray) {}
        open fun staticRef(classId: Long, nameId: Long, value: Long) {}
        open fun instance(id: Long, classId: Long, length: Int, data: ByteArray?) {}
        open fun objectArray(id: Long, classId: Long, count: Int, data: ByteArray?) {}
        open fun primitiveArray(id: Long, type: Int, count: Long) {}
    }

    private class Reading(private val file: File, private val owner: String, private val progress: (String) -> Unit) {
        private var idSize = 4
        private val names = HashMap<Long, String>()
        private val classNames = IdMap<String>()
        private val classes = IdMap<ClassInfo>()
        private val layouts = IdMap<Layout>()
        /** Per class id, or minus the type for primitive arrays: count and bytes in the app heap. */
        private val hist = IdMap<LongArray>()
        private val big = IdMap<LongArray>()
        private val heapTotals = LinkedHashMap<String, LongArray>()
        private var heapTotal = LongArray(1)
        private var inApp = true
        private var truncated = false
        private var appObjects = 0L
        private var appBytes = 0L
        private var buffer = ByteArray(1 shl 16)

        private fun room(n: Int): ByteArray { if (buffer.size < n) buffer = ByteArray(maxOf(n, buffer.size * 2)); return buffer }
        private fun size(type: Int): Int = when (type) {
            2 -> idSize; 4, 8 -> 1; 5, 9 -> 2; 6, 10 -> 4; 7, 11 -> 8
            else -> error("Unknown field type $type")
        }
        private fun idAt(b: ByteArray, o: Int): Long {
            val high = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
            if (idSize == 4) return high.toLong() and 0xFFFFFFFFL
            val low = ((b[o + 4].toInt() and 0xFF) shl 24) or ((b[o + 5].toInt() and 0xFF) shl 16) or ((b[o + 6].toInt() and 0xFF) shl 8) or (b[o + 7].toInt() and 0xFF)
            return (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
        }
        private fun name(key: Long): String = if (key < 0) when ((-key).toInt()) {
            4 -> "boolean[]"; 5 -> "char[]"; 6 -> "float[]"; 7 -> "double[]"; 8 -> "byte[]"; 9 -> "short[]"; 10 -> "int[]"; 11 -> "long[]"; else -> "?[]"
        } else classNames[key] ?: "class #$key"
        private fun short(name: String): String =
            if (name.startsWith(owner) || name.startsWith("java.") || name.startsWith("kotlin.")) name.substringAfterLast('.') else name
        private fun mine(key: Long) = key > 0 && name(key).startsWith(owner)
        /** Lambdas made by the compiler: followed through to what holds them, never chosen to be followed. */
        private fun synthetic(name: String) = name.contains("\$\$ExternalSyntheticLambda") || name.contains("\$\$Lambda")
        private fun plumbing(name: String) = name.endsWith("[]") || synthetic(name) || PLUMBING.any { name.startsWith(it) }
        private fun layout(classId: Long): Layout = layouts[classId] ?: run {
            val offsets = ArrayList<Int>(); val fieldNames = ArrayList<Long>()
            var at = 0; var c = classId; var guard = 0
            while (c != 0L && guard++ < 64) {
                val info = classes[c] ?: break
                val weak = classNames[c] == "java.lang.ref.Reference"
                for (i in info.fieldTypes.indices) {
                    val t = info.fieldTypes[i].toInt()
                    if (t == 2 && !weak) { offsets += at; fieldNames += info.fieldNames[i] }
                    at += size(t)
                }
                c = info.superId
            }
            Layout(offsets.toIntArray(), fieldNames.toLongArray()).also { layouts[classId] = it }
        }
        private fun rootName(kind: Int) = when (kind) {
            0x01 -> "JNI global (native code)"; 0x02 -> "JNI local"; 0x03 -> "a running thread's stack"; 0x04 -> "native stack"
            0x05 -> "system class"; 0x06 -> "thread block"; 0x07 -> "monitor in use"; 0x08 -> "thread object"
            0x89 -> "interned string"; 0x8A -> "waiting to be finalized"; 0x8B -> "debugger"; 0x8C -> "reference cleanup"
            0x8D -> "VM internal"; 0x8E -> "JNI monitor"; else -> "GC root"
        }

        private fun walk(step: Int, v: Visitor) {
            progress("Reading the memory report: step $step of at most $STEPS")
            Input(file).use { input ->
                val header = StringBuilder()
                while (true) { val c = input.u1(); if (c == 0) break; if (header.length < 40) header.append(c.toChar()) }
                require(header.startsWith("JAVA PROFILE")) { "Not a heap dump" }
                idSize = input.u4(); input.idSize = idSize
                require(idSize == 4 || idSize == 8) { "Unknown id size $idSize" }
                input.skip(8)
                inApp = true; heapTotal = heapTotals.getOrPut("app") { LongArray(1) }
                try {
                    while (input.more()) {
                        val tag = input.u1(); input.u4(); val length = input.len()
                        when (tag) {
                            0x01 -> if (v.strings) {
                                val id = input.id(); val n = (length - idSize).toInt(); val bytes = room(n)
                                input.read(bytes, n); v.string(id, String(bytes, 0, n, Charsets.UTF_8))
                            } else input.skip(length)
                            0x02 -> { input.u4(); val cid = input.id(); input.u4(); v.loadClass(cid, input.id()) }
                            0x0C, 0x1C -> segment(input, input.pos + length, v)
                            else -> input.skip(length)
                        }
                    }
                } catch (_: EOFException) { truncated = true }
            }
        }

        private fun segment(input: Input, stop: Long, v: Visitor) {
            while (input.pos < stop) {
                when (val sub = input.u1()) {
                    0xFF, 0x05, 0x07, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x90 -> v.root(sub, input.id())
                    0x01 -> { v.root(sub, input.id()); input.id() }
                    0x02, 0x03, 0x08, 0x8E -> { v.root(sub, input.id()); input.skip(8) }
                    0x04, 0x06 -> { v.root(sub, input.id()); input.skip(4) }
                    0xFE -> {
                        input.u4(); val heap = names[input.id()] ?: "?"
                        inApp = heap == "app"; heapTotal = heapTotals.getOrPut(heap) { LongArray(1) }
                    }
                    0x20 -> classDump(input, v)
                    0x21 -> {
                        val id = input.id(); input.skip(4); val cid = input.id(); val n = input.len().toInt()
                        if (v.fields) { val data = room(n); input.read(data, n); v.instance(id, cid, n, data) }
                        else { input.skip(n.toLong()); v.instance(id, cid, n, null) }
                    }
                    0x22 -> {
                        val id = input.id(); input.skip(4); val count = input.len().toInt(); val cid = input.id()
                        val n = count.toLong() * idSize
                        if (v.elements) { val data = room(n.toInt()); input.read(data, n.toInt()); v.objectArray(id, cid, count, data) }
                        else { input.skip(n); v.objectArray(id, cid, count, null) }
                    }
                    0x23 -> {
                        val id = input.id(); input.skip(4); val count = input.len(); val type = input.u1()
                        input.skip(count * size(type)); v.primitiveArray(id, type, count)
                    }
                    0xC3 -> { val id = input.id(); input.skip(4); val count = input.len(); v.primitiveArray(id, input.u1(), count) }
                    else -> error("Unknown heap record 0x${sub.toString(16)}")
                }
            }
        }

        private fun classDump(input: Input, v: Visitor) {
            val cid = input.id(); input.skip(4); val sup = input.id(); input.skip(5L * idSize + 4)
            repeat(input.u2()) { input.skip(2); input.skip(size(input.u1()).toLong()) }
            repeat(input.u2()) {
                val nameId = input.id(); val type = input.u1()
                if (type == 2) v.staticRef(cid, nameId, input.id()) else input.skip(size(type).toLong())
            }
            val count = input.u2(); val fieldNames = LongArray(count); val fieldTypes = ByteArray(count)
            for (i in 0 until count) { fieldNames[i] = input.id(); fieldTypes[i] = input.u1().toByte() }
            v.classDump(cid, sup, fieldNames, fieldTypes)
        }

        private fun tally(key: Long, bytes: Long, array: Boolean) {
            heapTotal[0] += bytes
            if (!inApp) return
            val h = hist[key] ?: LongArray(2).also { hist[key] = it }
            h[0]++; h[1] += bytes; appObjects++; appBytes += bytes
            if (array && bytes >= BIG_ARRAY) { val b = big[key] ?: LongArray(2).also { big[key] = it }; b[0]++; b[1] += bytes }
        }

        fun summary(): String {
            // 1: what is there, by class.
            walk(1, object : Visitor() {
                override val strings = true
                override fun string(id: Long, text: String) { names[id] = text }
                override fun loadClass(classId: Long, nameId: Long) { classNames[classId] = (names[nameId] ?: "?").replace('/', '.') }
                override fun classDump(classId: Long, superId: Long, fieldNames: LongArray, fieldTypes: ByteArray) { classes[classId] = ClassInfo(superId, fieldNames, fieldTypes) }
                override fun instance(id: Long, classId: Long, length: Int, data: ByteArray?) = tally(classId, length + 8L, false)
                override fun objectArray(id: Long, classId: Long, count: Int, data: ByteArray?) = tally(classId, 12L + count.toLong() * idSize, true)
                override fun primitiveArray(id: Long, type: Int, count: Long) = tally(-type.toLong(), 12L + count * size(type), true)
            })
            val ranked = ArrayList<Pair<Long, LongArray>>().apply { hist.forEach { k, v -> add(k to v) } }.sortedByDescending { it.second[1] }

            // Which to follow: this app's largest classes, its notebooks and pages, the largest others, and big arrays.
            val targets = ArrayList<Target>(); val byKey = IdMap<Int>(); val bigByKey = IdMap<Int>()
            fun stride(count: Long) = maxOf(1L, (count + SAMPLE - 1) / SAMPLE)
            fun choose(key: Long) {
                if (byKey[key] != null) return
                val h = hist[key] ?: return
                if (big[key]?.get(0) == h[0]) return
                byKey[key] = targets.size; targets += Target(short(name(key)), short(name(key)), h[0], h[1], stride(h[0]))
            }
            ranked.filter { mine(it.first) && !synthetic(name(it.first)) }.take(6).forEach { choose(it.first) }
            for (wanted in listOf("app.MainActivity", "core.Notebook", "core.Page")) ranked.firstOrNull { name(it.first) == owner + wanted }?.let { choose(it.first) }
            ranked.filter { !mine(it.first) }.take(3).forEach { choose(it.first) }
            big.forEach { key, b ->
                bigByKey[key] = targets.size
                targets += Target("${short(name(key))} of 1 MB or more", short(name(key)), b[0], b[1], stride(b[0]))
            }

            // 2: a sample of each.
            var want = IdMap<ArrayList<Pending>>()
            fun pick(id: Long, key: Long, bytes: Long, array: Boolean) {
                if (!inApp) return
                val index = (if (array && bytes >= BIG_ARRAY) bigByKey[key] else null) ?: byKey[key] ?: return
                val t = targets[index]; val n = t.seen++
                if (n % t.stride == 0L && t.taken < SAMPLE) { t.taken++; want[id] = arrayListOf(Pending(index, t.root, 0, 1, id)) }
            }
            walk(2, object : Visitor() {
                override fun instance(id: Long, classId: Long, length: Int, data: ByteArray?) = pick(id, classId, length + 8L, false)
                override fun objectArray(id: Long, classId: Long, count: Int, data: ByteArray?) = pick(id, classId, 12L + count.toLong() * idSize, true)
                override fun primitiveArray(id: Long, type: Int, count: Long) = pick(id, -type.toLong(), 12L + count * size(type), true)
            })

            // 3 onwards: what refers to them, one step up per reading, through collections and arrays.
            val budget = intArrayOf(CHAIN_LIMIT)
            fun finish(p: Pending, chain: Chain?) {
                val t = targets[p.target]
                if (chain == null) t.other += p.weight else t.paths.merge(chain, p.weight) { a, b -> a + b }
            }
            var step = 3
            var stopped = false
            try {
            while (want.size > 0 && step < 3 + MAX_HOPS) {
                val current = want
                val next = IdMap<ArrayList<Pending>>(); var nextCount = 0
                fun follow(list: ArrayList<Pending>, wanted: Long, referrer: Long, segment: String, through: Boolean) {
                    if (referrer == wanted) return   // an object pointing at itself (a lazy field's lock)
                    for (p in list) {
                        p.hit = true
                        if (referrer == p.origin) continue   // back where it started: its own field, not a holder
                        val chain = p.chain.child(segment, budget)
                        if (chain == null) { finish(p, null); continue }
                        if (through && p.hops + 1 < MAX_HOPS && nextCount < PENDING_LIMIT) {
                            val held = next[referrer] ?: ArrayList<Pending>(1).also { next[referrer] = it }
                            val same = held.firstOrNull { it.target == p.target && it.chain === chain }
                            if (same != null) { same.weight += p.weight; if (same.origin != p.origin) same.origin = 0L }
                            else { held += Pending(p.target, chain, p.hops + 1, p.weight, p.origin); nextCount++ }
                        } else finish(p, if (through) chain.child("…", budget) else chain)
                    }
                }
                walk(step++, object : Visitor() {
                    override val fields = true
                    override val elements = true
                    override fun root(kind: Int, id: Long) { current[id]?.forEach { it.hit = true; finish(it, it.chain.child(rootName(kind), budget)) } }
                    override fun staticRef(classId: Long, nameId: Long, value: Long) {
                        if (value != 0L) current[value]?.forEach { it.hit = true; finish(it, it.chain.child("static ${short(name(classId))}.${names[nameId] ?: "?"}", budget)) }
                    }
                    override fun instance(id: Long, classId: Long, length: Int, data: ByteArray?) {
                        val layout = layout(classId); val offsets = layout.offsets
                        for (i in offsets.indices) {
                            if (offsets[i] + idSize > length) break
                            val r = idAt(data!!, offsets[i]); if (r == 0L) continue
                            val list = current[r] ?: continue
                            val holder = name(classId)
                            follow(list, r, id, "${short(holder)}.${names[layout.names[i]] ?: "?"}", plumbing(holder))
                        }
                    }
                    override fun objectArray(id: Long, classId: Long, count: Int, data: ByteArray?) {
                        for (i in 0 until count) {
                            val r = idAt(data!!, i * idSize); if (r == 0L) continue
                            val list = current[r] ?: continue
                            follow(list, r, id, short(name(classId)), true)
                        }
                    }
                })
                current.forEach { _, list -> list.forEach { if (!it.hit) finish(it, it.chain.child("(no strong reference: garbage or weakly held)", budget)) } }
                want = next
            }
            } catch (_: OutOfMemoryError) {
                // What was found so far is still worth reading; the objects of the step under way are let go.
                want = IdMap(); stopped = true
            }

            // The text.
            fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1048576.0)
            fun n(v: Long) = String.format(Locale.US, "%,d", v)
            fun line(key: Long, h: LongArray) = String.format(Locale.US, "%8s %11s  %s\n", mb(h[1]), n(h[0]), short(name(key)))
            val out = StringBuilder()
            out.append("Heap dump ").append(mb(file.length())).append(" MB").append(if (truncated) " (it ends early)" else "").append('\n')
            out.append("App heap: ").append(mb(appBytes)).append(" MB in ").append(n(appObjects)).append(" objects")
            val others = heapTotals.filter { it.key != "app" && it.value[0] > 0 }
            if (others.isNotEmpty()) out.append(" (not counted: ").append(others.entries.joinToString { "${it.key} ${mb(it.value[0])} MB" }).append(')')
            out.append("\n\nLargest in the app heap (MB, count, class):\n")
            ranked.take(15).forEach { out.append(line(it.first, it.second)) }
            out.append("\nThis app's classes, largest first (MB, count, class):\n")
            ranked.filter { mine(it.first) }.take(25).forEach { out.append(line(it.first, it.second)) }
            var bigCount = 0L; var bigBytes = 0L; big.forEach { _, b -> bigCount += b[0]; bigBytes += b[1] }
            out.append("\nArrays of 1 MB or more: ").append(n(bigCount)).append(", ").append(mb(bigBytes)).append(" MB\n")
            ranked.firstOrNull { name(it.first) == "android.graphics.Bitmap" }?.let { out.append("Bitmaps (their pixels are outside this heap): ").append(n(it.second[0])).append('\n') }
            out.append("\nWhat holds them (from up to ").append(n(SAMPLE.toLong()))
                .append(" of each: how many, then the fields from the object up to the first class that is not a collection, a static field or a GC root):\n")
            if (stopped) out.append("(The reading stopped at step ").append(step - 1).append(": out of memory. These are the chains found until then.)\n")
            for (t in targets) {
                if (t.taken == 0) continue
                out.append(t.title).append(": ").append(n(t.count)).append(", ").append(mb(t.bytes)).append(" MB")
                if (t.taken < t.count) out.append(" (").append(n(t.taken.toLong())).append(" looked at)")
                out.append('\n')
                val paths = t.paths.entries.sortedByDescending { it.value }
                paths.take(6).forEach { out.append(String.format(Locale.US, "%8s  %s\n", n(it.value.toLong()), it.key.text())) }
                if (paths.size > 6) out.append("          and ").append(paths.size - 6).append(" other chains\n")
                if (t.other > 0) out.append("          and ").append(n(t.other)).append(" more past the limit of chains kept\n")
            }
            return out.toString()
        }
    }
}
