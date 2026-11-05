package dev.ownednotes.core

/**
 * Vertical stack of a notebook's pages for continuous mode (design decision D1).
 *
 * Document space is the scrollable strip in view pixels: page content is multiplied by [scale],
 * pages keep their own sizes, are centred horizontally and separated by [gap], with [margin]
 * around the strip. Immutable and free of Android types; rebuild it when pages or the viewport
 * width change. Not yet used by InkCanvas.
 */
class PageLayout private constructor(
    val slots: List<PageSlot>,
    val scale: Float,
    val gap: Float,
    val margin: Float,
    val contentWidth: Float,
    val contentHeight: Float,
) {
    val isEmpty get() = slots.isEmpty()

    /** Document point of a page-local point (page units, origin at the page's top-left). */
    fun toDocument(pageIndex: Int, local: Point): Point {
        val slot = slots[pageIndex]
        return Point(slot.left + local.x * scale, slot.top + local.y * scale)
    }

    /** Page under a document point, or null in gaps, margins and beside narrower pages. A shared edge (gap 0) belongs to the lower page. */
    fun pageAt(document: Point): PagePoint? {
        val index = pageIndexAtY(document.y) ?: return null
        val slot = slots[index]
        if (document.x < slot.left || document.x > slot.right) return null
        return PagePoint(index, Point((document.x - slot.left) / scale, (document.y - slot.top) / scale))
    }

    /** Page whose vertical band contains [y], ignoring x; null in gaps and margins. */
    fun pageIndexAtY(y: Float): Int? {
        val index = lastPageStartingAtOrAbove(y)
        return if (index >= 0 && y <= slots[index].bottom) index else null
    }

    /** Page nearest to [y] vertically (containing it, else the closer neighbour; ties go to the upper page). Null when empty. */
    fun nearestPageIndex(y: Float): Int? {
        if (slots.isEmpty()) return null
        pageIndexAtY(y)?.let { return it }
        val above = lastPageStartingAtOrAbove(y)
        if (above < 0) return 0
        if (above == slots.lastIndex) return above
        return if (y - slots[above].bottom <= slots[above + 1].top - y) above else above + 1
    }

    /** Pages meeting the band [viewportTop, viewportBottom] widened by [overscan] on both sides; touching edges do not count. Empty range when none. */
    fun visiblePages(viewportTop: Float, viewportBottom: Float, overscan: Float = 0f): IntRange {
        require(viewportTop.isFinite() && viewportBottom.isFinite() && viewportBottom >= viewportTop) { "Invalid viewport" }
        require(overscan.isFinite() && overscan >= 0f) { "Invalid overscan" }
        val low = viewportTop - overscan
        val high = viewportBottom + overscan
        val first = slots.indexOfFirst { it.bottom > low }
        if (first < 0 || slots[first].top >= high) return IntRange.EMPTY
        val last = slots.indexOfLast { it.top < high }
        return first..last
    }

    /** The current page: the page covering the viewport's vertical centre, or the nearest one when the centre is in a gap or margin. */
    fun currentPage(viewportTop: Float, viewportBottom: Float): Int? {
        require(viewportTop.isFinite() && viewportBottom.isFinite() && viewportBottom >= viewportTop) { "Invalid viewport" }
        return nearestPageIndex(viewportTop + (viewportBottom - viewportTop) / 2f)
    }

    /** Largest scroll offset for a viewport of [viewportHeight]; 0 when the strip fits. */
    fun maxScroll(viewportHeight: Float): Float = (contentHeight - viewportHeight).coerceAtLeast(0f)

    /** Scroll offset that puts a page-local y at the viewport top, clamped to the scrollable range. */
    fun scrollOffsetFor(pageIndex: Int, localY: Float, viewportHeight: Float): Float =
        (slots[pageIndex].top + localY * scale).coerceIn(0f, maxScroll(viewportHeight))

    /** Page and page-local point at the viewport top for a scroll offset; null in gaps and margins. */
    fun pageAtScroll(scrollOffset: Float): PagePoint? {
        val index = pageIndexAtY(scrollOffset) ?: return null
        val slot = slots[index]
        return PagePoint(index, Point(0f, (scrollOffset - slot.top) / scale))
    }

    private fun lastPageStartingAtOrAbove(y: Float): Int {
        var low = 0
        var high = slots.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (slots[mid].top <= y) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return found
    }

    companion object {
        /** Scale that fits the widest page between the margins of a viewport [viewportWidth] wide. */
        fun fitToWidth(pages: List<Page>, viewportWidth: Float, gap: Float = 24f, margin: Float = 24f): PageLayout {
            require(viewportWidth.isFinite() && margin.isFinite() && margin >= 0f && viewportWidth > 2 * margin) { "Viewport narrower than its margins" }
            val widest = pages.maxOfOrNull { it.width } ?: return of(pages, 1f, gap, margin, viewportWidth)
            return of(pages, (viewportWidth - 2 * margin) / widest, gap, margin, viewportWidth)
        }

        /** Layout at an explicit [scale]. The strip is at least [minContentWidth] wide so pages centre in the viewport. */
        fun of(pages: List<Page>, scale: Float, gap: Float = 24f, margin: Float = 24f, minContentWidth: Float = 0f): PageLayout {
            require(scale.isFinite() && scale > 0f) { "Invalid scale" }
            require(gap.isFinite() && gap >= 0f && margin.isFinite() && margin >= 0f) { "Invalid spacing" }
            require(minContentWidth.isFinite() && minContentWidth >= 0f) { "Invalid content width" }
            require(pages.all { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }) { "Invalid page size" }
            if (pages.isEmpty()) return PageLayout(emptyList(), scale, gap, margin, minContentWidth, 0f)
            val width = maxOf(minContentWidth.toDouble(), pages.maxOf { it.width.toDouble() } * scale + 2.0 * margin)
            var top = margin.toDouble()
            val slots = pages.mapIndexed { index, page ->
                val w = page.width.toDouble() * scale
                val h = page.height.toDouble() * scale
                PageSlot(index, page.id, ((width - w) / 2).toFloat(), top.toFloat(), w.toFloat(), h.toFloat()).also { top += h + gap }
            }
            return PageLayout(slots, scale, gap, margin, width.toFloat(), (top - gap + margin).toFloat())
        }
    }
}

/** A page's rectangle in document space. */
data class PageSlot(val index: Int, val pageId: String, val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

/** A page index with a point in that page's own units. */
data class PagePoint(val pageIndex: Int, val point: Point)
