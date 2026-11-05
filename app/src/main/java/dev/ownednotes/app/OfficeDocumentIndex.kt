package dev.ownednotes.app

import dev.ownednotes.core.OfficeArchive
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URI

/** Structural index only. No rendering, filesystem extraction, macro execution or external-resource requests. */
internal data class OfficeDocumentIndex(val kind: Kind, val mainPart: String, val slides: List<String>,
    val widthEmu: Long = 0, val heightEmu: Long = 0) {
    enum class Kind { WORD, SLIDES }
    companion object {
        private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CONTENT = "http://schemas.openxmlformats.org/package/2006/content-types"
        private val OFFICE = setOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships/", "http://purl.oclc.org/ooxml/officeDocument/relationships/")
        private val PRESENTATION = setOf("http://schemas.openxmlformats.org/presentationml/2006/main", "http://purl.oclc.org/ooxml/presentationml/main")
        private val WORD = setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main")
        private const val WORD_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        private const val SLIDES_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        private const val SLIDE_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        internal data class Link(val type: String, val target: String, val external: Boolean)
        fun read(archive: OfficeArchive): OfficeDocumentIndex {
            val types = LinkedHashMap<String,String>()
            xml(archive.readPart("[Content_Types].xml"), "Types", setOf(CONTENT)) { p ->
                if (p.depth == 2 && p.name == "Override" && p.namespace == CONTENT) {
                    val raw = attribute(p,"PartName"); checkPart(raw.startsWith('/'))
                    val name = target("",raw)
                    checkPart(types.put(name,attribute(p,"ContentType")) == null)
                }
            }
            val mainLinks = links(archive,"_rels/.rels").values.filter { isType(it,"officeDocument") }
            checkPart(mainLinks.size == 1 && !mainLinks.single().external)
            val main = target("",mainLinks.single().target)
            return when(types[main]) {
                WORD_TYPE -> {
                    xml(archive.readPart(main),"document",WORD) {}
                    OfficeDocumentIndex(Kind.WORD,main,emptyList())
                }
                SLIDES_TYPE -> {
                    val ids = ArrayList<String>(); var width = 0L; var height = 0L
                    xml(archive.readPart(main),"presentation",PRESENTATION) { p ->
                        if (p.namespace in PRESENTATION && p.name == "sldId" && p.depth == 3) {
                            val id = OFFICE.firstNotNullOfOrNull { p.getAttributeValue(it.removeSuffix("/"),"id") }
                            checkPart(id != null && id !in ids && ids.size < 2000); ids.add(id!!)
                        }
                        if (p.namespace in PRESENTATION && p.name == "sldSz" && p.depth == 2) {
                            checkPart(width == 0L)
                            width = attribute(p,"cx").toLongOrNull() ?: invalid()
                            height = attribute(p,"cy").toLongOrNull() ?: invalid()
                        }
                    }
                    checkPart(width in 1..1_000_000_000L && height in 1..1_000_000_000L && ids.isNotEmpty())
                    val parent = main.substringBeforeLast('/',"")
                    val relName = (if (parent.isEmpty()) "" else "$parent/") + "_rels/" + main.substringAfterLast('/') + ".rels"
                    val related = links(archive,relName)
                    val slides = ids.map { id ->
                        val link = related[id] ?: invalid()
                        checkPart(isType(link,"slide") && !link.external)
                        target(main,link.target).also { name -> checkPart(name in archive.parts && types[name] == SLIDE_TYPE) }
                    }
                    checkPart(slides.distinct().size == slides.size)
                    OfficeDocumentIndex(Kind.SLIDES,main,slides,width,height)
                }
                else -> invalid()
            }
        }
        internal fun links(archive: OfficeArchive, name: String): Map<String,Link> {
            val values = LinkedHashMap<String,Link>()
            xml(archive.readPart(name),"Relationships",setOf(REL)) { p ->
                if (p.depth == 2 && p.namespace == REL && p.name == "Relationship") {
                    val mode = p.getAttributeValue(null,"TargetMode")
                    checkPart(mode == null || mode == "Internal" || mode == "External")
                    val id = attribute(p,"Id")
                    checkPart(values.put(id,Link(attribute(p,"Type"),attribute(p,"Target"),mode == "External")) == null)
                }
            }
            return values
        }
        internal fun isType(link: Link, suffix: String) = OFFICE.any { link.type == it + suffix }
        private fun attribute(p: XmlPullParser, name: String): String = p.getAttributeValue(null,name)?.takeIf { it.isNotEmpty() } ?: invalid()
        internal fun target(base: String, raw: String): String {
            val uri = try { URI(raw) } catch (_: Exception) { invalid() }
            checkPart(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null)
            checkPart(!Regex("(?i)%2f|%5c").containsMatchIn(raw))
            val path = uri.path ?: invalid()
            checkPart(path.isNotEmpty() && path.none { it.isISOControl() || it == '\\' || it == ':' })
            val stack = ArrayList<String>()
            if (!path.startsWith('/')) stack.addAll(base.substringBeforeLast('/',"").split('/').filter { it.isNotEmpty() })
            for (part in path.removePrefix("/").split('/')) when(part) {
                ".." -> { checkPart(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                "." -> Unit
                "" -> invalid()
                else -> stack.add(part)
            }
            checkPart(stack.isNotEmpty()); return stack.joinToString("/")
        }
        private fun xml(bytes: ByteArray, root: String, namespaces: Set<String>, visit: (XmlPullParser) -> Unit) =
            OfficeXml.walk(bytes,root,namespaces,visit)
        private fun checkPart(value: Boolean) { if (!value) invalid() }
        private fun invalid(): Nothing = throw IOException("Office document structure is unsupported or could not be verified")
    }
}
