package dev.ownednotes.app

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException

/** Shared bounded XML gate for indexing and rendering; no entity declarations or external resource resolver. */
internal object OfficeXml {
    class Node(val name: String, val ns: String, val attributes: Map<String,String>) {
        val children = ArrayList<Node>()
        var text: String = ""
        fun child(name: String) = children.firstOrNull { it.name == name }
        fun all(name: String) = children.filter { it.name == name }
        fun attr(name: String): String? = attributes[name]
        fun number(name: String, default: Float = 0f): Float = attr(name)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
        fun descendants(name: String): Sequence<Node> = children.asSequence().flatMap { sequenceOf(it) + it.descendants(name) }.filter { it.name == name }
    }
    fun walk(bytes: ByteArray, root: String, namespaces: Set<String>, visit: (XmlPullParser) -> Unit) {
        process(bytes) { p,event -> if (event == XmlPullParser.START_TAG) {
            if (p.depth == 1 && (p.name != root || p.namespace !in namespaces)) invalid()
            visit(p)
        } }
    }
    fun tree(bytes: ByteArray): Node {
        val stack = ArrayList<Node>(); val text = ArrayList<StringBuilder>(); var result: Node? = null; var nodes = 0
        process(bytes) { p,event -> when(event) {
            XmlPullParser.START_TAG -> {
                if (++nodes > 50000) invalid()
                val attributes = LinkedHashMap<String,String>()
                repeat(p.attributeCount) { i ->
                    val ns = p.getAttributeNamespace(i).orEmpty()
                    val key = if (ns.isEmpty()) p.getAttributeName(i) else "{$ns}${p.getAttributeName(i)}"
                    attributes[key] = p.getAttributeValue(i)
                }
                val node = Node(p.name,p.namespace.orEmpty(),attributes)
                stack.lastOrNull()?.children?.add(node) ?: run { result = node }
                stack.add(node); text.add(StringBuilder())
            }
            XmlPullParser.TEXT,XmlPullParser.CDSECT,XmlPullParser.ENTITY_REF,XmlPullParser.IGNORABLE_WHITESPACE -> text.lastOrNull()?.append(p.text.orEmpty())
            XmlPullParser.END_TAG -> { stack.removeAt(stack.lastIndex).text = text.removeAt(text.lastIndex).toString() }
        } }
        return result ?: invalid()
    }
    private fun process(bytes: ByteArray, visit: (XmlPullParser,Int) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(bytes.inputStream(),null)
        var seen = false; var tokens = 0
        while (true) {
            val event = parser.nextToken()
            if (++tokens > 250000 || parser.depth > 64) invalid()
            when(event) {
                XmlPullParser.DOCDECL -> invalid()
                XmlPullParser.ENTITY_REF -> if (parser.name !in setOf("amp","lt","gt","apos","quot") && !parser.name.orEmpty().startsWith('#')) invalid()
                XmlPullParser.START_TAG -> seen = true
                XmlPullParser.END_DOCUMENT -> { if (!seen) invalid(); return }
            }
            visit(parser,event)
        }
    }
    private fun invalid(): Nothing = throw IOException("Office XML is unsupported or exceeds the import limit")
}
