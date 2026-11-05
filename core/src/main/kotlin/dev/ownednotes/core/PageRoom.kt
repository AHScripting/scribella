package dev.ownednotes.core

/**
 * F063: a blank page grows when more room is needed, using its ordinary width and height, so nothing on it scales.
 * Room below or to the right leaves everything where it is; room above or to the left moves everything down or right
 * by the room added, so it keeps its place on the paper. [MAX_SIDE] is the largest page PDF readers accept.
 */
object PageRoom {
    const val MAX_SIDE = 14_400f
    /** Appended in P546 (UP, LEFT); the sheet's choices follow this order. */
    enum class Direction { DOWN, RIGHT, BOTH, UP, LEFT }
    private val Direction.tall get() = this == Direction.DOWN || this == Direction.BOTH || this == Direction.UP
    private val Direction.wide get() = this == Direction.RIGHT || this == Direction.BOTH || this == Direction.LEFT

    /** The new width and height after adding [pages] page lengths, or null when the page can take no more room. */
    fun grown(page: Page, direction: Direction, pages: Float): Pair<Float, Float>? {
        if (page.hasBackground || !pages.isFinite() || pages <= 0f) return null
        val unit = minOf(page.width, page.height)
        val width = if (!direction.wide) page.width else minOf(page.width + unit * pages, MAX_SIDE)
        val height = if (!direction.tall) page.height else minOf(page.height + unit * 1.414f * pages, MAX_SIDE)
        val grew = (direction.tall && height > page.height) || (direction.wide && width > page.width)
        if (!grew || width < page.width || height < page.height) return null
        return (width to height).takeIf { runCatching { page.paper.validate(width, height) }.isSuccess }
    }

    /** How far what is on the page moves when it grows to [size]: only room above or to the left moves it. */
    fun shift(page: Page, direction: Direction, size: Pair<Float, Float>): Point = Point(
        if (direction == Direction.LEFT) size.first - page.width else 0f,
        if (direction == Direction.UP) size.second - page.height else 0f)
}
