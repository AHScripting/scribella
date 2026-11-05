package dev.ownednotes.core

import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** HTTPS collection boundary only. Does not connect, follow hrefs, or store credentials. */
class WebDavEndpoint private constructor(val collection: URI) {
    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port

    /** null means the collection itself; otherwise the decoded direct-child name. */
    internal fun child(href: String): String? {
        val uri = parseUri(href)
        require(uri.isAbsolute || href.startsWith('/') && !href.startsWith("//")) { "Unsupported WebDAV resource address" }
        validatePath(uri)
        val resolved = if (uri.isAbsolute) uri else collection.resolve(uri)
        validate(resolved)
        require(resolved.host.equals(collection.host, true) && port(resolved) == port(collection)) { "WebDAV resource is outside the collection" }
        val path = resolved.path
        val base = collection.path
        if (path == base || base != "/" && path == base.dropLast(1)) return null
        require(path.startsWith(base)) { "WebDAV resource is outside the collection" }
        val child = path.removePrefix(base).removeSuffix("/")
        require(child.isNotEmpty() && '/' !in child) { "WebDAV listing contains a nested resource" }
        require(!path.endsWith('/') || !SYNC_NAME.matches(child)) { "WebDAV sync filename has a collection address" }
        return child
    }

    /** Native packages, legacy markers and the one reserved deletion index only. */
    fun resource(name: String): URI {
        require(SYNC_NAME.matches(name)) { "Unsupported WebDAV sync filename" }
        return collection.resolve(name)
    }

    /** Only this client's randomly owned staging files, never a caller-provided path. */
    internal fun stage(id: java.util.UUID): URI = collection.resolve(".notes-lab-stage-$id.tmp")

    companion object {
        internal val SYNC_NAME = Regex("(?:[A-Za-z0-9-]{1,80}\\.(?:inknote|deleted)|\\.notes-lab-deletions\\.json)")

        fun parse(value: String): WebDavEndpoint {
            val uri = parseUri(value.trim())
            validate(uri)
            val host = uri.host.lowercase(Locale.ROOT)
            val authority = if (uri.port == -1 || uri.port == 443) host else "$host:${uri.port}"
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }.let { if (it.endsWith('/')) it else "$it/" }
            return WebDavEndpoint(URI("https://$authority$path"))
        }

        private fun parseUri(value: String): URI {
            require(value.length in 1..8192) { "Invalid WebDAV address" }
            return try { URI(URI(value).toASCIIString()) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid WebDAV address")
            }
        }

        private fun validate(uri: URI) {
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() &&
                uri.port in -1..65535 && uri.port != 0 && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && !uri.isOpaque) { "Use an HTTPS WebDAV collection address without credentials, query or fragment" }
            validatePath(uri)
        }

        private fun validatePath(uri: URI) {
            val raw = uri.rawPath.orEmpty()
            require(!Regex("%(2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) { "Ambiguous WebDAV resource path" }
            val path = uri.path.orEmpty()
            require('\\' !in path && path.none { it.isISOControl() } && "//" !in path &&
                path.split('/').none { it == "." || it == ".." }) { "Ambiguous WebDAV resource path" }
        }
    }
}

/** A validated listing is still not authorization to overwrite: use conditional HTTP requests later. */
object WebDavListing {
    data class Entry(val name: String, val size: Long, val etag: String?) {
        val strongEtag: String? get() = etag?.takeUnless { it.startsWith("W/") }
        // Deliberately no last-modified caching token: WebDAV date granularity can miss same-size edits.
        fun syncStat() = FolderSync.Stat(size, 0)
    }

    private const val DAV = "DAV:"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val STATUS = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [^\\r\\n]*)?")
    private val ETAG = Regex("(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"")
    private val BARE_ETAG = Regex("[A-Za-z0-9._~-]{1,256}")

    /** Parses the body of a successful 207 Depth:1 response. No filesystem/network/model access. */
    fun read(endpoint: WebDavEndpoint, input: InputStream): Map<String, Entry> {
        val bytes = try { input.readNBytes(MAX_BYTES + 1) } catch (_: IOException) {
            // Keep unavailable transport separate from invalid XML, without exposing its raw cause.
            throw IOException("Unable to read WebDAV directory listing")
        }
        try {
            require(bytes.size <= MAX_BYTES) { "WebDAV listing is too large" }
            val handler = TreeHandler()
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            reader.parse(InputSource(bytes.inputStream()))
            return listing(endpoint, requireNotNull(handler.root))
        } catch (error: Exception) {
            // Never echo server XML/hrefs/paths or parser error snippets into user-facing errors.
            throw IllegalArgumentException("Invalid or unsupported WebDAV directory listing")
        }
    }

    private fun listing(endpoint: WebDavEndpoint, root: Node): Map<String, Entry> {
        require(root.isDav("multistatus"))
        val responses = root.children.filter { it.isDav("response") }
        require(responses.size in 1..10000)
        val seen = hashSetOf<String?>()
        val entries = linkedMapOf<String, Entry>()
        var collectionSeen = false
        for (response in responses) {
            val name = endpoint.child(response.one("href").scalar())
            require(seen.add(name)) // includes duplicate root and percent-encoding aliases
            require(response.dav("status").isEmpty()) // per-resource errors are never an empty folder
            val properties = linkedMapOf<String, Node>()
            val stats = response.dav("propstat")
            require(stats.isNotEmpty())
            for (stat in stats) {
                val code = status(stat.one("status").scalar())
                val prop = stat.one("prop")
                require(code == 200 || code == 404) // unavailable props allowed; denied/failed listing is not
                if (code == 200) for (node in prop.children.filter { it.namespace == DAV }) {
                    require(properties.put(node.name, node) == null)
                }
            }
            val type = requireNotNull(properties["resourcetype"])
            require(type.text.toString().isBlank())
            val collection = type.children.any { it.isDav("collection") }
            if (name == null) {
                require(collection)
                collectionSeen = true
            } else if (WebDavEndpoint.SYNC_NAME.matches(name)) {
                require(!collection && type.children.isEmpty())
                val sizeText = requireNotNull(properties["getcontentlength"]).scalar()
                require(sizeText.matches(Regex("[0-9]{1,19}")))
                val size = requireNotNull(sizeText.toLongOrNull())
                val etag = properties["getetag"]?.scalar()?.let { value ->
                    // WsgiDAV emits bare opaque ASCII tags in this XML property. HTTP conditions
                    // still use exact quoted strong tags; never normalize HTTP headers/weak syntax.
                    if (BARE_ETAG.matches(value)) "\"$value\"" else value.also { require(ETAG.matches(it)) }
                }
                entries[name] = Entry(name, size, etag)
            }
        }
        require(collectionSeen)
        return entries
    }

    private fun status(text: String): Int = requireNotNull(STATUS.matchEntire(text)).groupValues[1].toInt()

    private class Node(val namespace: String, val name: String) {
        val text = StringBuilder()
        val children = arrayListOf<Node>()
        fun isDav(value: String) = namespace == DAV && name == value
        fun dav(value: String) = children.filter { it.isDav(value) }
        fun one(value: String) = dav(value).single()
        fun scalar(): String { require(children.isEmpty()); return text.toString().trim() }
    }

    private class TreeHandler : DefaultHandler2() {
        var root: Node? = null
        private val stack = arrayListOf<Node>()
        private var nodes = 0
        override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit = throw SAXException("DTD prohibited")
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity prohibited")
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = throw SAXException("External entity prohibited")
        override fun error(error: org.xml.sax.SAXParseException): Unit = throw SAXException("Malformed XML")
        override fun fatalError(error: org.xml.sax.SAXParseException): Unit = throw SAXException("Malformed XML")
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            require(++nodes <= 200000 && stack.size < 32 && attributes.length <= 32 && localName.length <= 256 && uri.length <= 8192)
            for (i in 0 until attributes.length) require(attributes.getValue(i).length <= 8192)
            // A nested response can conceal otherwise valid notebook names: fail rather than ignore it.
            if (uri == DAV && localName == "response") require(stack.size == 1 && stack[0].isDav("multistatus"))
            val node = Node(uri, localName)
            if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children += node
            stack += node
        }
        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (stack.isNotEmpty()) {
                val text = stack.last().text
                require(text.length + length <= 8192)
                text.append(chars, start, length)
            }
        }
        override fun endElement(uri: String, localName: String, qName: String) { stack.removeAt(stack.lastIndex) }
    }
}
