package dev.ownednotes.core

import kotlin.math.*

/** Page dimensions and their content change together; embedded PDF/image/file bytes never change. */
internal fun Notebook.configuredPages(ids: Set<String>, title: String? = null, width: Float? = null,
    height: Float? = null, paper: PaperStyle? = null, replaceBackgrounds: Boolean = false): Notebook {
    require(ids.isNotEmpty() && pages.map { it.id }.containsAll(ids)) { "Page not found" }
    require((width == null) == (height == null)) { "Choose both page dimensions" }
    require(width == null || (width.isFinite() && height!!.isFinite() && width in 1f..100_000f && height in 1f..100_000f)) { "Invalid page size" }
    require(!replaceBackgrounds || paper != null) { "Choose a template before replacing backgrounds" }
    val name = title?.trim() ?: this.title
    require(name.length in 1..500 && name.none { it.isISOControl() }) { "Title must have 1–500 characters" }
    val next = pages.map { page ->
        if (page.id !in ids) page else {
            val scaled = if (width == null || (width == page.width && height == page.height)) page
                else page.scaledTo(width, height!!)
            if (paper == null || (page.hasBackground && !replaceBackgrounds)) scaled else
                scaled.copy(paper = paper, pdfBackground = null, imageBackground = null)
        }
    }
    val pdfs = next.mapNotNull { it.pdfBackground?.assetId }.toSet()
    val images = next.flatMap { it.imageAssetIds }.toSet()
    return copy(title = name, pages = next, assets = assets.filter { it.id in pdfs }, imageAssets = imageAssets.filter { it.id in images })
}

private fun Page.scaledTo(w: Float, h: Float): Page {
    val sx = w / width; val sy = h / height
    val uniform = sx == sy
    fun point(p: Point) = Point(p.x * sx, p.y * sy)
    fun placement(p: ImagePlacement): ImagePlacement {
        val angle = Math.toRadians(p.degrees.toDouble())
        val ux = sx * cos(angle); val uy = sy * sin(angle)
        val vx = -sx * sin(angle); val vy = sy * cos(angle)
        val a = hypot(ux, uy); val b = hypot(vx, vy)
        require(abs((ux * vx + uy * vy) / (a * b)) < .00001) {
            "A rotated item needs the original page proportions. Choose a proportional size."
        }
        val nw = (p.width * a).toFloat(); val nh = (p.height * b).toFloat()
        return p.copy(x = (p.x + p.width / 2) * sx - nw / 2, y = (p.y + p.height / 2) * sy - nh / 2,
            width = nw, height = nh, degrees = Math.toDegrees(atan2(uy, ux)).toFloat())
    }
    // P412: guides are page units too, so ruled lines stay under the writing they were written on (ruled rows follow height).
    val guideScale = if (paper.pattern == PaperPattern.RULED) sy else sqrt(sx * sy)
    return copy(width = w, height = h,
        paper = if (paper.pattern == PaperPattern.PLAIN) paper else
            paper.copy(spacing = (paper.spacing * guideScale).coerceIn(16f, 256f), weight = (paper.weight * sqrt(sx * sy)).coerceIn(.25f, 3f)),
        strokes = strokes.map { stroke -> stroke.copy(
            width = stroke.width * sqrt(sx * sy),
            samples = stroke.samples.map { it.copy(x = it.x * sx, y = it.y * sy) },
            outlines = stroke.outlines.map { it.map(::point) },
            erasures = stroke.erasures.map { erasure ->
                if (uniform) erasure.copy(points = erasure.points.map(::point), radius = erasure.radius * sx)
                else {
                    val b = erasure.basis ?: EraserBasis(1f, 0f, 0f, 1f)
                    erasure.copy(points = erasure.points.map(::point), basis = EraserBasis(b.a * sx, b.b * sy, b.c * sx, b.d * sy))
                }
            }) },
        images = images.map { it.copy(placement = placement(it.placement)) },
        textBoxes = textBoxes.map { it.copy(placement = placement(it.placement), style = it.style.copy(size = it.style.size * sqrt(sx * sy))) },
        attachments = attachments.map { it.copy(placement = placement(it.placement)) })
}

/** Use the native Standard short-edge coordinate scale without guessing a physical paper size.
 * Existing content keeps its relative size; original PDF/photo bytes and page identity are untouched.
 */
fun Notebook.withStandardPageScale(ids: Set<String>): Notebook {
    require(ids.isNotEmpty() && pages.map { it.id }.containsAll(ids)) { "Page not found" }
    val next = pages.map { page ->
        val edge = minOf(page.width, page.height)
        if (page.id !in ids || edge == 1000f) page else {
            val factor = 1000f / edge
            val w = if (page.width <= page.height) 1000f else page.width * factor
            val h = if (page.height <= page.width) 1000f else page.height * factor
            require(w.isFinite() && h.isFinite() && w in 1f..100_000f && h in 1f..100_000f) { "This page cannot use Standard scale" }
            page.scaledTo(w, h)
        }
    }
    return if (next == pages) this else copy(pages = next)
}

/** Where a page's whole area lands on a Standard-shaped page: uniformly scaled by [scale], then moved by ([dx], [dy]). */
data class StandardShapeFit(val width: Float, val height: Float, val scale: Float, val dx: Float, val dy: Float)

/** Null when the page already has the Standard shape (1000 × 1400, or 1400 × 1000 when landscape). */
fun Page.standardShapeFit(): StandardShapeFit? {
    val w = if (width <= height) 1000f else 1400f
    val h = if (width <= height) 1400f else 1000f
    if (width == w && height == h) return null
    val scale = minOf(w / width, h / height)
    return StandardShapeFit(w, h, scale, (w - width * scale) / 2, (h - height * scale) / 2)
}

/** P412 (B52): give imported pages the Standard page shape with blank paper around the old area, so they show as wide as
 * pages made here. Content is scaled uniformly (never stretched); a PDF background moves to [padded], a caller-made PDF page
 * that shows the old page centred with blank margins. Photo backgrounds are refused; original assets are only dropped once unused.
 */
fun Notebook.withStandardPageShape(ids: Set<String>, padded: Map<String, Pair<PdfAsset, PdfBackground>>): Notebook {
    require(ids.isNotEmpty() && pages.map { it.id }.containsAll(ids)) { "Page not found" }
    val next = pages.map { page ->
        val fit = if (page.id in ids) page.standardShapeFit() else null
        if (fit == null) page else {
            require(page.imageBackground == null) { "Pages with a photo background keep their shape" }
            val background = page.pdfBackground?.let { requireNotNull(padded[page.id]) { "Padded PDF page missing" }.second }
            page.scaledTo(page.width * fit.scale, page.height * fit.scale).movedBy(fit.dx, fit.dy)
                .copy(width = fit.width, height = fit.height, pdfBackground = background)
        }
    }
    if (next == pages) return this
    val added = padded.filterKeys { id -> next.any { it.id == id } }.values.map { it.first }.distinctBy { it.id }
    val used = next.mapNotNull { it.pdfBackground?.assetId }.toSet()
    return copy(pages = next, assets = (assets + added.filter { asset -> assets.none { it.id == asset.id } }).filter { it.id in used })
}

/** P546 (F063): the page at its new size with everything on it moved by ([dx], [dy]); reviewed handwriting moves along and stays reviewed. */
internal fun Page.withRoom(width: Float, height: Float, dx: Float, dy: Float): Page {
    if (dx == 0f && dy == 0f) return copy(width = width, height = height)
    val active = activeReviewedInk.map { it.id }.toSet()
    val moved = movedBy(dx, dy).copy(width = width, height = height)
    return moved.copy(reviewedInk = reviewedInk.map { record ->
        if (record.id !in active) record else record.copy(
            sourceHash = requireNotNull(ReviewedInk.fingerprint(moved, record.strokeIds, record.inputMode)),
            bounds = with(record.bounds) { InkTextBounds(left + dx, top + dy, right + dx, bottom + dy) })
    })
}

private fun Page.movedBy(dx: Float, dy: Float): Page {
    fun point(p: Point) = Point(p.x + dx, p.y + dy)
    return copy(
        strokes = strokes.map { stroke -> stroke.copy(
            samples = stroke.samples.map { it.copy(x = it.x + dx, y = it.y + dy) },
            outlines = stroke.outlines.map { it.map(::point) },
            erasures = stroke.erasures.map { it.copy(points = it.points.map(::point)) }) },
        images = images.map { it.copy(placement = it.placement.translated(dx, dy)) },
        textBoxes = textBoxes.map { it.copy(placement = it.placement.translated(dx, dy)) },
        attachments = attachments.map { it.copy(placement = it.placement.translated(dx, dy)) })
}
