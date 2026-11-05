package dev.ownednotes.core

/**
 * P126 (F145): how converted handwriting is laid out as a text box. The box starts where the ink did, its letters are
 * about as tall as the handwriting lines, and it is wide and tall enough for the text so nothing starts hidden.
 */
object HandwritingConversion {
    const val MIN_SIZE = 12f
    const val MAX_SIZE = 96f

    /** Font size from the handwriting's height per line. */
    fun size(bounds: InkTextBounds, text: String): Float {
        val lines = text.lines().size.coerceAtLeast(1)
        return ((bounds.bottom - bounds.top) / lines * .62f).coerceIn(MIN_SIZE, MAX_SIZE)
    }

    /** At least the handwriting's own rectangle, grown for the typed text, and kept on the page. */
    fun placement(bounds: InkTextBounds, text: String, page: Page): ImagePlacement {
        val size = size(bounds, text)
        val lines = text.lines()
        val width = maxOf(bounds.right - bounds.left, lines.maxOf { it.length } * size * .56f + size).coerceIn(1f, page.width)
        val height = maxOf(bounds.bottom - bounds.top, lines.size * size * 1.4f + size * .4f).coerceIn(1f, page.height)
        val x = bounds.left.coerceIn(0f, page.width - width)
        val y = bounds.top.coerceIn(0f, page.height - height)
        return ImagePlacement(x, y, width, height)
    }

    /** The colour most of the converted ink was written in, fully opaque; the default text colour without ink. */
    fun style(ink: List<InkStroke>, bounds: InkTextBounds, text: String): TextStyle {
        val color = ink.groupBy { it.color or 0xff000000L }.maxByOrNull { (_, strokes) -> strokes.sumOf { it.samples.size } }?.key
        return TextStyle(size = size(bounds, text), color = color ?: TextStyle().color)
    }
}
