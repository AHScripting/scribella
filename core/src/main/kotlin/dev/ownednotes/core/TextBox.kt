package dev.ownednotes.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

/** Portable families supplied by Android and SVG consumers; no downloaded fonts or font files. */
@Serializable enum class TextFont(val cssFamily: String) {
    SANS("sans-serif"), SERIF("serif"), MONOSPACE("monospace")
}

@Serializable enum class TextAlignment { START, CENTER, END }

/** Font sizes use page units, independent of device pixels or editor zoom. */
@Serializable data class TextStyle(
    val font: TextFont = TextFont.SANS,
    val size: Float = 24f,
    val weight: Int = 400,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Long = 0xff172a3a,
    val alignment: TextAlignment = TextAlignment.START,
) {
    init {
        require(size.isFinite() && size in 4f..512f) { "Invalid text size" }
        require(weight in 100..900) { "Invalid text weight" }
        require(color in 0..0xffffffffL) { "Invalid text color" }
    }
}

/**
 * Exact text is separate from the visible rectangle: resizing/reflow must never discard overflow.
 * Placement reuses the existing validated rectangle mathematics; it implies no image asset/EXIF.
 * Native text content remains independent of device font metrics and editor zoom.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class TextBox(
    val text: String,
    val placement: ImagePlacement,
    val style: TextStyle = TextStyle(),
    val id: String = UUID.randomUUID().toString(),
    /** P101 (schema 21): a locked text box is drawn but can't be selected, edited or moved until unlocked. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
    /** Schema 30: shared mixed-object group on this page. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val group: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val layer: String? = null,
    /** Schema 34: optional coloured, foldable text note. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sticky: StickyNote? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val link: NotebookLink? = null,
) {
    init {
        require(group==null || group.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid text group" }
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid text box ID" }
        require(text.length <= MAX_TEXT_LENGTH) { "Text box exceeds character limit" }
        // Preserve UTF-16 exactly, but reject ill-formed pairs and XML-incompatible controls before export.
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                require(index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) { "Invalid Unicode text" }
                index += 2
            } else {
                require(!Character.isLowSurrogate(char) &&
                    (char.code >= 0x20 || char == '\t' || char == '\r' || char == '\n') &&
                    char.code != 0xfffe && char.code != 0xffff) { "Unsupported text character" }
                index++
            }
        }
    }
    companion object {
        const val MAX_TEXT_LENGTH = 100_000
        /** P103 (F099): text from another app made fit for a text box. Unsupported characters are dropped and it is cut at [MAX_TEXT_LENGTH] without splitting a pair. */
        fun clean(text: String): String {
            val out = StringBuilder(minOf(text.length, MAX_TEXT_LENGTH))
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (Character.isHighSurrogate(char)) {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        if (out.length + 2 > MAX_TEXT_LENGTH) break
                        out.append(char).append(text[index + 1]); index += 2
                    } else index++
                    continue
                }
                if (!Character.isLowSurrogate(char) && (char.code >= 0x20 || char == '\t' || char == '\r' || char == '\n') &&
                    char.code != 0xfffe && char.code != 0xffff) {
                    if (out.length + 1 > MAX_TEXT_LENGTH) break
                    out.append(char)
                }
                index++
            }
            return out.toString()
        }
    }
}

/** Fold state preserves the complete text and expanded size; placement remains the visible hit rectangle. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class StickyNote(val color:Long=0xffffe59a, val collapsed:Boolean=false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expandedWidth:Float?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expandedHeight:Float?=null) {
    init {
        require(color in 0xff000000L..0xffffffffL) { "Choose an opaque note colour" }
        require(if(collapsed) expandedWidth!=null && expandedHeight!=null && expandedWidth.isFinite() && expandedHeight.isFinite() && expandedWidth in 1f..100000f && expandedHeight in 1f..100000f
            else expandedWidth==null && expandedHeight==null) { "Invalid unfolded note size" }
    }
}
fun TextBox.toggleStickyFold():TextBox {
    val note=sticky ?: return this
    return if(note.collapsed)copy(placement=placement.copy(width=note.expandedWidth!!,height=note.expandedHeight!!),sticky=note.copy(collapsed=false,expandedWidth=null,expandedHeight=null))
    else copy(placement=placement.copy(width=minOf(48f,placement.width),height=minOf(48f,placement.height)),
        sticky=note.copy(collapsed=true,expandedWidth=placement.width,expandedHeight=placement.height))
}
