package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@OptIn(ExperimentalSerializationApi::class)
@Serializable private data class PageReference(val id: String, val file: String, val sha256: String,
    /** B24 (P140, schema 26): the page's point data, absent on a page without ink. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ink: String? = null, @EncodeDefault(EncodeDefault.Mode.NEVER) val inkSha256: String? = null)
@Serializable private data class AssetReference(val id: String, val file: String, val sha256: String)
@OptIn(ExperimentalSerializationApi::class)
@Serializable private data class Manifest(val format: String = "owned-notes", val schemaVersion: Int = 1, val id: String, val title: String, val pages: List<PageReference>, @EncodeDefault(EncodeDefault.Mode.NEVER) val assets: List<AssetReference> = emptyList(), @EncodeDefault(EncodeDefault.Mode.NEVER) val imageAssets: List<AssetReference> = emptyList(), @EncodeDefault(EncodeDefault.Mode.NEVER) val attachments: List<AssetReference> = emptyList())

object NotebookPackage {
    private const val MAX_ENTRY = 16 * 1024 * 1024
    private const val MAX_TOTAL = 32 * 1024 * 1024
    /** B24 (P140): schema 26, the compact form, for notebooks beyond what schemas 1–25 hold. */
    const val COMPACT_SCHEMA = 26
    const val SUPPORTED_SCHEMA = 37
    private const val MAX_INK_ENTRY = 64 * 1024 * 1024
    private const val MAX_COMPACT_TOTAL = 768L * 1024 * 1024
    /** Pretty page JSON above about this size is slow to write each time, so such a notebook is saved compact. */
    private const val LEGACY_ESTIMATE = 8L * 1024 * 1024
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    @OptIn(ExperimentalSerializationApi::class)
    private val compactJson = Json { encodeDefaults = true; explicitNulls = false }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * B24 (P140): whether [notebook] stays within schemas 1–25 (which older builds open) and is small enough that their
     * pretty page JSON is quick to write. Anything else is saved compact (schema 26).
     */
    private fun legacyFits(notebook: Notebook): Boolean {
        if (notebook.schemaVersion() >= COMPACT_SCHEMA) return false
        if (notebook.imageAssets.any { it.size > 8 * 1024 * 1024 } || notebook.assets.any { it.size > PdfAsset.LEGACY_MAX_BYTES }) return false
        if (notebook.pages.size > NotebookLimits.LEGACY_PAGES || notebook.assets.size + notebook.imageAssets.size > NotebookLimits.LEGACY_BACKGROUND_FILES ||
            notebook.assets.sumOf { it.size.toLong() } + notebook.imageAssets.sumOf { it.size.toLong() } > NotebookLimits.LEGACY_BACKGROUND_BYTES) return false
        if (notebook.pages.any { (it.pdfBackground?.pageIndex ?: 0) >= NotebookLimits.LEGACY_PDF_PAGES }) return false
        if (notebook.pages.sumOf { it.textBoxes.size } > NotebookLimits.LEGACY_TEXT_BOXES || notebook.pages.sumOf { it.images.size } > NotebookLimits.LEGACY_IMAGES ||
            notebook.pages.sumOf { page -> page.textBoxes.sumOf { it.text.length.toLong() } } > 1_000_000L) return false
        var samples = 0L; var vertices = 0L; var eraser = 0L
        notebook.pages.forEach { page -> page.strokes.forEach { stroke ->
            samples += stroke.samples.size; vertices += stroke.outlines.sumOf { it.size }; eraser += stroke.erasures.sumOf { it.points.size }
        } }
        return samples <= NotebookLimits.LEGACY_SAMPLES && vertices <= NotebookLimits.LEGACY_VERTICES && eraser <= NotebookLimits.LEGACY_ERASER_POINTS &&
            samples * 220 + (vertices + eraser) * 100 <= LEGACY_ESTIMATE
    }

    /** A page as last saved: unchanged pages (the same [page] instance) are copied, not encoded and compressed again. */
    private class SavedPage(page: Page, val compact: Boolean, val json: ZipBody, val ink: ZipBody?) {
        val page = WeakReference(page)
        val pageId = page.id
    }
    /** Retain hot encoded pages from at most two notebooks, within an estimated32MiB budget. */
    private val savedPages = PageEncodingCache<SavedPage>(32L * 1024 * 1024) {
        256L + it.json.compressed.size + (it.ink?.compressed?.size ?: 0)
    }
    /** Compressed PDFs, images and attached files by package path; their ids are their SHA256, so a path never changes content. */
    private val savedFiles = ZipBodyCache(64L * 1024 * 1024)

    internal data class CacheUsage(val pageBytes: Long, val originalBytes: Long)
    /** Local accounting only, without notebook IDs or content. */
    internal fun cacheUsage() = CacheUsage(savedPages.retainedBytes(), savedFiles.retainedBytes())

    /** Release reconstructible encoding caches; active saves may finish with their private local references. */
    fun clearCaches() {
        synchronized(savedPages) { savedPages.clear() }
        savedFiles.clear()
    }

    private fun encode(page: Page, compact: Boolean): SavedPage {
        if (!compact) return json.encodeToString(page).toByteArray(Charsets.UTF_8).let { SavedPage(page, false, ZipBody.of(it, hash(it)), null) }
        val ink = if (page.strokes.isEmpty()) null else InkCodec.encode(page)
        val bytes = compactJson.encodeToString(InkCodec.skeleton(page)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ENTRY && (ink?.size ?: 0) <= MAX_INK_ENTRY) { "A page holds more than a notebook page can" }
        return SavedPage(page, true, ZipBody.of(bytes, hash(bytes)), ink?.let { ZipBody.of(it, hash(it), Deflater.BEST_SPEED) })
    }

    private fun file(path: String, writeTo: (OutputStream) -> Unit): ZipBody {
        synchronized(savedFiles) { savedFiles[path] }?.let { return it }
        val bytes = ByteArrayOutputStream().also(writeTo).toByteArray()
        // PNG/JPEG already contain compressed pixels. Copy them through raw DEFLATE blocks
        // instead of spending the first post-open save recompressing the same originals.
        val level = if (path.startsWith("images/")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION
        return ZipBody.of(bytes, hash(bytes), level).also { body -> synchronized(savedFiles) { savedFiles[path] = body } }
    }

    private data class PreparedPages(val legacy: Boolean, val entries: List<SavedPage>)
    /** Validates and prepares only page encodings, without opening or compressing original files. */
    private fun preparePages(notebook: Notebook): PreparedPages {
        // Strokes of pages saved unchanged passed these checks then; they are only counted again.
        notebook.validate({ false }) { page -> savedPages[notebook.id, page.id]?.page?.get() === page }
        fun page(page: Page, compact: Boolean): SavedPage =
            savedPages[notebook.id, page.id]?.takeIf { it.page.get() === page && it.compact == compact }
                ?: encode(page, compact).also { savedPages[notebook.id, page.id] = it }
        val legacy = if (legacyFits(notebook) && notebook.attachmentAssets.all { it.size<=AttachmentAsset.LEGACY_MAX_BYTES } &&
            notebook.attachmentAssets.sumOf { it.size.toLong() }<=AttachmentAsset.LEGACY_MAX_TOTAL_BYTES) notebook.pages.map { page(it, false) }.takeIf { pageData ->
            pageData.all { it.json.size <= MAX_ENTRY } && pageData.sumOf { it.json.size.toLong() } + notebook.assets.sumOf { it.size.toLong() } + notebook.imageAssets.sumOf { it.size.toLong() } + notebook.attachmentAssets.sumOf { it.size.toLong() } < MAX_TOTAL - 65536
        } else null
        val pageData = legacy ?: notebook.pages.map { page(it, true) }
        savedPages.retainPages(notebook.id, notebook.pages.mapTo(HashSet()) { it.id })
        return PreparedPages(legacy != null, pageData)
    }

    /**
     * Saves [notebook]. B24 (P140): pages unchanged since this notebook was last saved or primed (see [prime]) are
     * copied as already-compressed bytes, so a save costs about the changed pages plus copying the rest.
     */
    fun write(notebook: Notebook, destination: OutputStream) {
        val prepared = preparePages(notebook)
        val pageData = prepared.entries
        val pages = pageData.map { e ->
            PageReference(e.pageId, "pages/${e.pageId}.json", e.json.sha256, e.ink?.let { "pages/${e.pageId}.ink" }, e.ink?.sha256)
        }
        val manifest = Manifest(schemaVersion = if (prepared.legacy) notebook.schemaVersion() else maxOf(COMPACT_SCHEMA,notebook.schemaVersion()), id = notebook.id, title = notebook.title, pages = pages, assets = notebook.assets.map { AssetReference(it.id, "assets/${it.id}.pdf", it.id) }, imageAssets = notebook.imageAssets.map { AssetReference(it.id, "images/${it.id}.${it.extension}", it.id) }, attachments = notebook.attachmentAssets.map { AssetReference(it.id, "files/${it.id}.bin", it.id) })
        // Each entry has its own compressor, point data at the fastest level (about three times quicker, a quarter
        // larger). Changing the level between entries of one ZipOutputStream
        // (setLevel) wrote data Android's zlib could not inflate ("invalid distance code") in P140's first tablet run.
        // The destination is left open (AtomicFile still needs it for fsync).
        val zip = ZipWriter(destination)
        val manifestBytes = (if (prepared.legacy) json else compactJson).encodeToString(manifest).toByteArray(Charsets.UTF_8)
        zip.entry("manifest.json", ZipBody.of(manifestBytes, ""))
        pageData.forEach { e ->
            zip.entry("pages/${e.pageId}.json", e.json)
            e.ink?.let { zip.entry("pages/${e.pageId}.ink", it) }
        }
        notebook.assets.forEach { asset -> "assets/${asset.id}.pdf".let {
            if (asset.size > PdfAsset.LEGACY_MAX_BYTES) zip.streamEntry(it, asset.size, asset::writeTo)
            else zip.entry(it, file(it, asset::writeTo))
        } }
        notebook.imageAssets.forEach { asset -> "images/${asset.id}.${asset.extension}".let { zip.entry(it, file(it, asset::writeTo)) } }
        notebook.attachmentAssets.forEach { asset -> "files/${asset.id}.bin".let { zip.entry(it, file(it, asset::writeTo)) } }
        zip.finish()
    }

    /**
     * Warms validated page encodings after [read], without reading/compressing attached originals or writing files.
     * Cold originals are compressed by an actual [write]; browsing alone must not block the shared IO queue on them.
     */
    fun prime(notebook: Notebook) { preparePages(notebook) }

    private class Reading {
        var manifest: Manifest? = null
        val raw = HashMap<String, ByteArray>()
        val files = HashMap<String, AttachmentAsset>()
        val pdfs = HashMap<String, PdfAsset>()
        val pages = HashMap<String, Page>()
        val byFile = HashMap<String, PageReference>()
        val budget = InkCodec.Budget()
    }

    /** Per-read unpublished file/PDF copies; rollback never touches caller files or earlier assets. */
    private class OwnedAttachments(private val directory: File) {
        private val created=ArrayList<AttachmentAsset>()
        private val createdPdfs=ArrayList<PdfAsset>()
        private var bytes=0L
        private var pdfBytes=0L
        private fun counted(source: InputStream,total: AtomicLong)=object: InputStream() {
            override fun read(): Int = source.read().also { if(it>=0) count(1) }
            override fun read(b: ByteArray,off: Int,len: Int): Int = source.read(b,off,len).also { if(it>0) count(it) }
            private fun count(n: Int) { require(total.addAndGet(n.toLong())<=MAX_COMPACT_TOTAL) { "Package exceeds notebook limits" } }
        }
        fun read(source: InputStream,total: AtomicLong): AttachmentAsset {
            require(created.size<AttachmentAsset.MAX_FILES) { "Too many attached files" }
            val asset=AttachmentAsset.fromStream(counted(source,total),directory)
            created.add(asset);bytes+=asset.size
            require(bytes<=AttachmentAsset.MAX_TOTAL_BYTES) { "Attached files exceed the notebook limit" }
            return asset
        }
        fun readPdf(source: InputStream,total: AtomicLong): PdfAsset {
            require(createdPdfs.size<NotebookLimits.BACKGROUND_FILES) { "Too many PDF files" }
            val asset=PdfAsset.fromStream(counted(source,total),directory)
            createdPdfs.add(asset);pdfBytes+=asset.size
            require(pdfBytes<=NotebookLimits.BACKGROUND_BYTES) { "PDF files exceed the notebook limit" }
            return asset
        }
        fun rollback() { created.forEach { it.discardUnpublished() };createdPdfs.forEach { it.discardUnpublished() } }
    }
    private fun withOwnedAttachments(directory: File,block: (OwnedAttachments)->Notebook): Notebook {
        require(directory.isDirectory) { "Attachment storage directory is unavailable" }
        val owned=OwnedAttachments(directory)
        try { return block(owned) } catch(error: Throwable) { owned.rollback();throw error }
    }

    /** Checks the manifest before any page data is interpreted. */
    private fun checkManifest(manifest: Manifest) {
        require(manifest.format == "owned-notes" && manifest.schemaVersion in 1..SUPPORTED_SCHEMA) { "Unsupported notebook format/version" }
        val compact = manifest.schemaVersion >= COMPACT_SCHEMA
        require(manifest.schemaVersion >= 9 || manifest.imageAssets.isEmpty()) { "Image assets require schema 9" }
        require(manifest.assets.size + manifest.imageAssets.size <= if (compact) NotebookLimits.BACKGROUND_FILES else NotebookLimits.LEGACY_BACKGROUND_FILES) { "Too many background assets" }
        require(manifest.schemaVersion >= 14 || manifest.attachments.isEmpty()) { "Attached files require schema 14" }
        require(manifest.attachments.size <= AttachmentAsset.MAX_FILES && manifest.attachments.map { it.id }.distinct().size == manifest.attachments.size) { "Too many or duplicate attached files" }
        require(when (manifest.schemaVersion) { 1 -> manifest.assets.isEmpty(); 2 -> manifest.assets.size in 1..4; else -> true }) { "Invalid schema assets" }
        require(manifest.pages.size in 1..if (compact) NotebookLimits.PAGES else NotebookLimits.LEGACY_PAGES) { "Invalid page count" }
        require(manifest.pages.map { it.file }.distinct().size == manifest.pages.size && manifest.pages.map { it.id }.distinct().size == manifest.pages.size) { "Duplicate page reference" }
        require(manifest.pages.all { if (compact) it.ink == null || (it.ink == "pages/${it.id}.ink" && it.inkSha256 != null) else it.ink == null && it.inkSha256 == null }) { "Invalid ink reference" }
        require(manifest.assets.map { it.id }.distinct().size == manifest.assets.size) { "Duplicate PDF asset" }
        require(manifest.imageAssets.map { it.id }.distinct().size == manifest.imageAssets.size) { "Duplicate image asset" }
    }

    private val entryPath = Regex("pages/[a-zA-Z0-9-]{1,80}\\.(json|ink)|assets/[a-f0-9]{64}\\.pdf|images/[a-f0-9]{64}\\.(png|jpg)|files/[a-f0-9]{64}\\.bin")
    private val maxEntries = 1 + 2 * NotebookLimits.PAGES + NotebookLimits.BACKGROUND_FILES + AttachmentAsset.MAX_FILES

    private fun checkEntry(name: String, directory: Boolean, seen: MutableSet<String>) {
        require(seen.size < maxEntries && !directory) { "Too many or unsupported entries" }
        require(name == "manifest.json" || name.matches(entryPath)) { "Unsupported package path" }
        require(seen.add(name)) { "Duplicate package entry" }
    }

    /** Reads one entry, refusing more than its own limit or, with the others read so far ([total]), the package's. */
    private fun InputStream.entryBytes(name: String, total: AtomicLong): ByteArray {
        val limit = when {
            name.endsWith(".ink") -> MAX_INK_ENTRY
            name.startsWith("images/") -> ImageAsset.MAX_BYTES
            name.startsWith("files/") -> AttachmentAsset.MAX_BYTES
            name.startsWith("assets/") -> PdfAsset.MEMORY_MAX_BYTES
            else -> MAX_ENTRY
        }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= limit && total.addAndGet(count.toLong()) <= MAX_COMPACT_TOTAL) { "Package exceeds notebook limits" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun parseManifest(data: ByteArray): Manifest =
        json.decodeFromJsonElement(Manifest.serializer(), json.parseToJsonElement(data.toString(Charsets.UTF_8))).also { checkManifest(it) }

    private fun Manifest.entries() = (pages.flatMap { listOfNotNull(it.file, it.ink) } + assets.map { it.file } + imageAssets.map { it.file } + attachments.map { it.file } + "manifest.json").toSet()

    private fun checkSize(manifest: Manifest, entries: Int, total: Long) {
        if (manifest.schemaVersion < COMPACT_SCHEMA) require(entries < 30 && total <= MAX_TOTAL) { "Package exceeds preview limits" }
    }

    /** Decodes one page from its JSON and, in the compact form, its point data. */
    private fun decodePage(manifest: Manifest, reference: PageReference, bytes: ByteArray, ink: ByteArray?, budget: InkCodec.Budget): Page {
        require(reference.file == "pages/${reference.id}.json") { "Invalid page reference" }
        require(hash(bytes) == reference.sha256) { "Page checksum mismatch" }
        val page = json.decodeFromString<Page>(bytes.toString(Charsets.UTF_8)).also { require(it.id == reference.id) { "Page identity mismatch" } }
        return when {
            ink != null -> { require(hash(ink) == reference.inkSha256) { "Page checksum mismatch" }; InkCodec.decode(page, ink, budget) }
            manifest.schemaVersion >= COMPACT_SCHEMA -> page.also { require(it.strokes.isEmpty()) { "Ink without its data" } }
            else -> page.packed()
        }
    }

    /** Decodes [reference]'s page once its JSON (and ink, if any) has arrived; their bytes are then released. */
    private fun Reading.settle(reference: PageReference) {
        val manifest = manifest ?: return
        if (reference.id in pages) return
        val bytes = raw[reference.file] ?: return
        val ink = reference.ink?.let { raw[it] ?: return }
        pages[reference.id] = decodePage(manifest, reference, bytes, ink, budget)
        raw.remove(reference.file); reference.ink?.let { raw.remove(it) }
    }

    /** The notebook from its decoded [pages] and the bytes of its other entries, checked against its schema. */
    private fun assemble(manifest: Manifest, pages: List<Page>, attachment: ((String) -> AttachmentAsset)? = null, pdf: ((String)->PdfAsset)? = null, entry: (String) -> ByteArray?): Notebook {
        val assets = manifest.assets.map { reference ->
            require(reference.file == "assets/${reference.id}.pdf" && reference.id == reference.sha256) { "Invalid PDF reference" }
            val asset = pdf?.invoke(reference.file) ?: PdfAsset.fromBytes(requireNotNull(entry(reference.file)))
            require(asset.id == reference.sha256) { "PDF checksum mismatch" }
            asset
        }
        require(manifest.schemaVersion>=27 || assets.all { it.size<=PdfAsset.LEGACY_MAX_BYTES }) { "Larger PDF originals require schema 27" }
        require(manifest.schemaVersion >= 28 || assets.all { it.size <= PdfAsset.SCHEMA27_MAX_BYTES }) { "PDF originals above 64 MiB require schema 28" }
        val images = manifest.imageAssets.map { reference ->
            val asset = ImageAsset.fromBytes(requireNotNull(entry(reference.file)) { "Missing image" })
            require(reference.file == "images/${asset.id}.${asset.extension}" && reference.id == asset.id && reference.sha256 == asset.id) { "Invalid image reference/checksum" }
            asset
        }
        require(manifest.schemaVersion >= 28 || assets.sumOf { it.size.toLong() } + images.sumOf { it.size.toLong() } <= NotebookLimits.SCHEMA27_BACKGROUND_BYTES) { "Backgrounds above 64 MiB require schema 28" }
        var attachedBytes=0L
        val files = manifest.attachments.map { reference ->
            val asset = attachment?.invoke(reference.file) ?: requireNotNull(entry(reference.file)) { "Missing attached file" }.let { bytes ->
                require(attachedBytes+bytes.size<=AttachmentAsset.MAX_TOTAL_BYTES) { "Attached files exceed the notebook limit" }
                AttachmentAsset.fromBytes(bytes)
            }
            attachedBytes+=asset.size
            require(attachedBytes<=AttachmentAsset.MAX_TOTAL_BYTES) { "Attached files exceed the notebook limit" }
            require(reference.file == "files/${asset.id}.bin" && reference.id == asset.id && reference.sha256 == asset.id) { "Invalid attached file reference/checksum" }
            asset
        }
        require(manifest.schemaVersion>=COMPACT_SCHEMA || (files.all { it.size<=AttachmentAsset.LEGACY_MAX_BYTES } &&
            files.sumOf { it.size.toLong() }<=AttachmentAsset.LEGACY_MAX_TOTAL_BYTES)) { "Larger original files require compact schema 26" }
        val notebook = Notebook(manifest.id, manifest.title, pages, assets, images, files)
        require(manifest.schemaVersion != 1 || notebook.pages.all { it.pdfBackground == null }) { "PDF requires schema 2" }
        require(manifest.schemaVersion >= 3 || notebook.pages.all { page -> page.strokes.all { it.erasures.isEmpty() } }) { "Area erasing requires schema 3" }
        require(manifest.schemaVersion >= 4 || notebook.pages.all { page -> page.strokes.all { stroke -> stroke.erasures.all { it.basis == null } } }) { "Affine erasing requires schema 4" }
        require(manifest.schemaVersion >= 5 || notebook.pages.all { page -> page.strokes.none { it.isHighlighter } }) { "Highlighter requires schema 5" }
        require(manifest.schemaVersion >= 6 || notebook.pages.all { page -> page.strokes.none { it.isExtendedPen } }) { "Extended pen requires schema 6" }
        require(manifest.schemaVersion >= 7 || notebook.pages.all { it.paper == PaperStyle.DEFAULT }) { "Paper settings require schema 7" }
        require(manifest.schemaVersion >= 8 || notebook.pages.all { it.bookmark == null }) { "Bookmarks require schema 8" }
        require(manifest.schemaVersion >= 9 || notebook.pages.all { it.imageBackground == null }) { "Image background requires schema 9" }
        require(manifest.schemaVersion >= 10 || notebook.pages.all { it.images.isEmpty() }) { "Image objects require schema 10" }
        require(manifest.schemaVersion >= 11 || notebook.pages.all { it.textBoxes.isEmpty() }) { "Text boxes require schema 11" }
        require(manifest.schemaVersion >= 12 || notebook.pages.all { page -> page.images.all { it.crop == ImageCrop.FULL } }) { "Image crop requires schema 12" }
        require(manifest.schemaVersion >= 13 || notebook.pages.all { page -> page.strokes.none { it.isTape } }) { "Study tape requires schema 13" }
        require(manifest.schemaVersion >= 14 || notebook.pages.all { it.attachments.isEmpty() }) { "Attachments require schema 14" }
        require(manifest.schemaVersion >= 15 || notebook.pages.all { page -> page.strokes.none { it.isTranslucent } }) { "See-through ink requires schema 15" }
        require(manifest.schemaVersion >= 16 || notebook.pages.all { it.paper.pattern != PaperPattern.ISOMETRIC }) { "Isometric paper requires schema 16" }
        require(manifest.schemaVersion >= 17 || notebook.pages.all { it.outlineLevel == 1 }) { "Nested outline requires schema 17" }
        require(manifest.schemaVersion >= 18 || notebook.pages.all { page -> page.strokes.none { it.isCalligraphy } }) { "Calligraphy pen requires schema 18" }
        require(manifest.schemaVersion >= 19 || notebook.pages.all { page -> page.strokes.all { it.group == null } }) { "Grouped ink requires schema 19" }
        require(manifest.schemaVersion >= 20 || notebook.pages.all { it.layers.isEmpty() }) { "Ink layers require schema 20" }
        require(manifest.schemaVersion >= 21 || !notebook.hasLocks) { "Locked objects require schema 21" }
        require(manifest.schemaVersion >= 22 || notebook.pages.all { page -> page.layers.all { it.opacity == 1f } }) { "Layer opacity requires schema 22" }
        require(manifest.schemaVersion >= 24 || notebook.pages.all { page -> page.reviewedInk.all { it.inputMode == ReviewedInkInput.RAW } }) { "Reviewed remaining centerlines require schema 24" }
        require(manifest.schemaVersion >= 23 || notebook.pages.all { it.reviewedInk.isEmpty() }) { "Reviewed handwriting requires schema 23" }
        require(manifest.schemaVersion >= 25 || notebook.pages.all { page -> page.strokes.none { it.isPencil } }) { "Pencil requires schema 25" }
        require(manifest.schemaVersion >= 37 || notebook.pages.all { page -> page.strokes.none { it.isFountain } }) { "Fountain pen requires schema 37" }
        require(manifest.schemaVersion >= 36 || notebook.pages.all {p->p.strokes.none {it.audio!=null}}) { "Audio links require schema 36" }
        require(manifest.schemaVersion >= 35 || notebook.pages.none { it.hasNotebookLinks }) { "Notebook links require schema 35" }
        require(manifest.schemaVersion >= 34 || notebook.pages.all { p -> p.textBoxes.none { it.sticky != null } }) { "Sticky notes require schema 34" }
        require(manifest.schemaVersion >= 33 || notebook.pages.all { p -> !p.hasObjectLayers }) { "Object layers require schema 33" }
        require(manifest.schemaVersion >= 32 || notebook.pages.all { p -> p.attachments.none { it.locked } }) { "Attachment locks require schema 32" }
        require(manifest.schemaVersion >= 31 || notebook.pages.all { p -> p.strokes.all { it.fillColor == null } }) { "Shape fills require schema 31" }
        require(manifest.schemaVersion >= 30 || notebook.pages.all { p -> p.images.all { it.group == null } && p.textBoxes.all { it.group == null } }) { "Mixed groups require schema 30" }
        require(manifest.schemaVersion >= 29 || notebook.pages.all { page -> page.strokes.all { it.shapeKind == null } }) { "Shape identity requires schema 29" }
        notebook.validate()
        return notebook
    }

    /** Reads a package from a stream (an import or a backup), one entry after another. */
    fun read(source: InputStream): Notebook = readStream(source,null)
    /** Owned copies survive source closure; directory must outlive the returned assets/streams. */
    fun read(source: InputStream,ownedDirectory: File): Notebook = withOwnedAttachments(ownedDirectory) { readStream(source,it) }
    private fun readStream(source: InputStream,owned: OwnedAttachments?): Notebook {
        val reading = Reading()
        val seen = LinkedHashSet<String>()
        val total = AtomicLong()
        var attachedBytes=0L;var attachedFiles=0
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                checkEntry(entry.name, entry.isDirectory, seen)
                if(entry.name.startsWith("files/")) require(++attachedFiles<=AttachmentAsset.MAX_FILES) { "Too many attached files" }
                if(owned!=null && entry.name.startsWith("files/")) {
                    reading.files[entry.name]=owned.read(zip,total)
                    zip.closeEntry()
                    continue
                }
                if(owned!=null && entry.name.startsWith("assets/")) {
                    reading.pdfs[entry.name]=owned.readPdf(zip,total)
                    zip.closeEntry()
                    continue
                }
                val data = zip.entryBytes(entry.name, total)
                zip.closeEntry()
                if(entry.name.startsWith("files/")) {
                    attachedBytes+=data.size
                    require(attachedBytes<=AttachmentAsset.MAX_TOTAL_BYTES) { "Attached files exceed the notebook limit" }
                }
                if (entry.name == "manifest.json") {
                    val manifest = parseManifest(data)
                    reading.manifest = manifest
                    manifest.pages.forEach { reference -> reading.byFile[reference.file] = reference; reference.ink?.let { reading.byFile[it] = reference } }
                    manifest.pages.forEach { reading.settle(it) }
                } else {
                    reading.raw[entry.name] = data
                    reading.byFile[entry.name]?.let { reading.settle(it) }
                }
            }
        }
        val manifest = requireNotNull(reading.manifest) { "Missing manifest" }
        checkSize(manifest, seen.size, total.get())
        require(seen == manifest.entries()) { "Missing or unreferenced entries" }
        return assemble(manifest, manifest.pages.map { reference -> requireNotNull(reading.pages[reference.id]) { "Missing page" } },
            attachment=if(owned==null) null else { path -> requireNotNull(reading.files[path]) { "Missing attached file" } },
            pdf=if(owned==null) null else { path -> requireNotNull(reading.pdfs[path]) { "Missing PDF" } }) { reading.raw[it] }
    }

    /** A package file opened for reading entries in any order, or null when it has no readable zip directory. */
    private fun open(file: File): ZipFile? = try { ZipFile(file) } catch (_: ZipException) { null }

    /** The manifest of an opened package, after checking that its entries are exactly the ones the manifest names. */
    private fun ZipFile.manifest(): Manifest {
        val seen = LinkedHashSet<String>()
        entries().asSequence().forEach { checkEntry(it.name, it.isDirectory, seen) }
        val manifest = parseManifest(getInputStream(requireNotNull(getEntry("manifest.json")) { "Missing manifest" }).use { it.entryBytes("manifest.json", AtomicLong()) })
        require(seen == manifest.entries()) { "Missing or unreferenced entries" }
        checkSize(manifest, seen.size, 0L)
        return manifest
    }

    private fun ZipFile.bytes(name: String, total: AtomicLong): ByteArray =
        getInputStream(requireNotNull(getEntry(name)) { "Missing or unreferenced entries" }).use { it.entryBytes(name, total) }

    /**
     * Reads a package file with the same checks as [read] of a stream. Its pages are unpacked and decoded on several
     * cores at once, which is most of the time a long notebook takes to open (B24 follow-up).
     */
    fun read(file: File): Notebook = readFile(file,null)
    fun read(file: File,ownedDirectory: File): Notebook = withOwnedAttachments(ownedDirectory) { readFile(file,it) }
    private fun readFile(file: File,owned: OwnedAttachments?): Notebook {
        val zip = open(file) ?: return file.inputStream().use { readStream(it,owned) }
        return zip.use {
            val manifest = zip.manifest()
            val total = AtomicLong()
            val budget = InkCodec.Budget()
            val pages = manifest.pages.parallelStream().map { reference ->
                decodePage(manifest, reference, zip.bytes(reference.file, total), reference.ink?.let { zip.bytes(it, total) }, budget)
            }.collect(Collectors.toList())
            assemble(manifest, pages,attachment=owned?.let { store -> { path: String ->
                zip.getInputStream(requireNotNull(zip.getEntry(path))).use { store.read(it,total) }
            } },pdf=owned?.let { store -> { path: String ->
                zip.getInputStream(requireNotNull(zip.getEntry(path))).use { store.readPdf(it,total) }
            } }) { zip.bytes(it, total) }.also { checkSize(manifest, 0, total.get()) }
        }
    }

    /** What a library list shows. Only the manifest is read; pages are checked when the notebook opens. */
    class Summary(val id: String, val title: String, val pageCount: Int)

    fun summary(file: File): Summary {
        val zip = open(file) ?: return read(file).let { Summary(it.id, it.title, it.pages.size) }
        return zip.use { zip.manifest().let { Summary(it.id, it.title, it.pages.size) } }
    }

    /** The notebook with only its first page and the files that page uses, for a cover; the other pages are not read. */
    fun firstPage(file: File): Notebook = firstPageFile(file,null)
    fun firstPage(file: File,ownedDirectory: File): Notebook = withOwnedAttachments(ownedDirectory) { firstPageFile(file,it) }
    private fun firstPageFile(file: File,owned: OwnedAttachments?): Notebook {
        val zip = open(file) ?: return readFile(file,owned).firstPage()
        return zip.use {
            val manifest = zip.manifest()
            val total = AtomicLong()
            val reference = manifest.pages.first()
            val page = decodePage(manifest, reference, zip.bytes(reference.file, total), reference.ink?.let { zip.bytes(it, total) }, InkCodec.Budget())
            val only = manifest.copy(pages = listOf(reference), assets = manifest.assets.filter { it.id == page.pdfBackground?.assetId },
                imageAssets = manifest.imageAssets.filter { it.id in page.imageAssetIds }, attachments = manifest.attachments.filter { it.id in page.attachmentAssetIds })
            assemble(only, listOf(page),attachment=owned?.let { store -> { path: String ->
                zip.getInputStream(requireNotNull(zip.getEntry(path))).use { store.read(it,total) }
            } },pdf=owned?.let { store -> { path: String ->
                zip.getInputStream(requireNotNull(zip.getEntry(path))).use { store.readPdf(it,total) }
            } }) { zip.bytes(it, total) }
        }
    }
}

/** [this] with only its first page and the files that page uses. */
fun Notebook.firstPage(): Notebook {
    val page = pages.first()
    return copy(pages = listOf(page), assets = assets.filter { it.id == page.pdfBackground?.assetId },
        imageAssets = imageAssets.filter { it.id in page.imageAssetIds }, attachmentAssets = attachmentAssets.filter { it.id in page.attachmentAssetIds })
}

/** P101: anything on any page is locked. */
internal val Notebook.hasLocks get() = pages.any { page -> page.layers.any { it.locked } || page.strokes.any { it.locked } || page.images.any { it.locked } || page.textBoxes.any { it.locked } }

/** The schema the notebook's features need (1–25,27–30); the package may be saved compact as [NotebookPackage.COMPACT_SCHEMA]. */
fun Notebook.schemaVersion(): Int = when {
    pages.any { page -> page.strokes.any { it.isFountain } } -> 37
    pages.any {p->p.strokes.any {it.audio!=null}} -> 36
    pages.any {it.hasNotebookLinks} -> 35
    pages.any { p -> p.textBoxes.any { it.sticky != null } } -> 34
    pages.any { it.hasObjectLayers } -> 33
    pages.any { p -> p.attachments.any { it.locked } } -> 32
    pages.any { p -> p.strokes.any { it.fillColor != null } } -> 31
    pages.any { p -> p.images.any { it.group != null } || p.textBoxes.any { it.group != null } } -> 30
    pages.any { page -> page.strokes.any { it.shapeKind != null } } -> 29
    assets.any { it.size > PdfAsset.SCHEMA27_MAX_BYTES } || assets.sumOf { it.size.toLong() } + imageAssets.sumOf { it.size.toLong() } > NotebookLimits.SCHEMA27_BACKGROUND_BYTES -> 28
    assets.any { it.size>PdfAsset.LEGACY_MAX_BYTES } -> 27
    pages.any { page -> page.strokes.any { it.isPencil } } -> 25
    pages.any { page -> page.reviewedInk.any { it.inputMode != ReviewedInkInput.RAW } } -> 24
    pages.any { it.reviewedInk.isNotEmpty() } -> 23
    pages.any { page -> page.layers.any { it.opacity < 1f } } -> 22
    hasLocks -> 21
    pages.any { it.layers.isNotEmpty() } -> 20
    pages.any { page -> page.strokes.any { it.group != null } } -> 19
    pages.any { page -> page.strokes.any { it.isCalligraphy } } -> 18
    pages.any { it.outlineLevel > 1 } -> 17
    pages.any { it.paper.pattern == PaperPattern.ISOMETRIC } -> 16
    pages.any { page -> page.strokes.any { it.isTranslucent } } -> 15
    pages.any { it.attachments.isNotEmpty() } -> 14
    pages.any { page -> page.strokes.any { it.isTape } } -> 13
    pages.any { page -> page.images.any { it.crop != ImageCrop.FULL } } -> 12
    pages.any { it.textBoxes.isNotEmpty() } -> 11
    pages.any { it.images.isNotEmpty() } -> 10
    pages.any { it.imageBackground != null } -> 9
    pages.any { it.bookmark != null } -> 8
    pages.any { it.paper != PaperStyle.DEFAULT } -> 7
    pages.any { page -> page.strokes.any { it.isExtendedPen } } -> 6
    pages.any { page -> page.strokes.any { it.isHighlighter } } -> 5
    pages.any { page -> page.strokes.any { stroke -> stroke.erasures.any { it.basis != null } } } -> 4
    pages.any { page -> page.strokes.any { it.erasures.isNotEmpty() } } -> 3
    assets.isNotEmpty() -> 2
    else -> 1
}
