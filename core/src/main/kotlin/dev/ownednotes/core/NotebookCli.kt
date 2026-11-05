package dev.ownednotes.core

import java.io.File
import java.util.Locale
import java.util.Base64

object SvgRenderer {
    private fun number(value: Float) = String.format(Locale.ROOT, "%.4f", value)
    fun render(page: Page, images: List<ImageAsset> = emptyList(), files: List<AttachmentAsset> = emptyList()): String = buildString {
        require(page.pdfBackground == null) { "PDF background requires a PDF renderer; use extract-pdf to recover the original PDF" }
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${number(page.width)} ${number(page.height)}\" overflow=\"hidden\">")
        append("<rect width=\"100%\" height=\"100%\" fill=\"${if (page.paper.color == 0xffffffffL) "white" else String.format(Locale.ROOT, "#%06x", page.paper.color and 0xffffff)}\"/>")
        val guideColor = String.format(Locale.ROOT, "#%06x", page.paper.guideColor and 0xffffff)
        page.paper.guides(page.width, page.height, { x1, y1, x2, y2 ->
            append("<line x1=\"${number(x1)}\" y1=\"${number(y1)}\" x2=\"${number(x2)}\" y2=\"${number(y2)}\" stroke=\"$guideColor\" stroke-width=\"${number(page.paper.weight)}\"/>")
        }, { x, y, radius -> append("<circle cx=\"${number(x)}\" cy=\"${number(y)}\" r=\"${number(radius)}\" fill=\"$guideColor\"/>") })
        page.imageBackground?.let { background ->
            val asset = requireNotNull(images.firstOrNull { it.id == background.assetId }) { "Image asset missing" }
            val m = background.normalizedTransform()
            val matrix = listOf(m[0] * page.width, m[1] * page.height, m[2] * page.width, m[3] * page.height, m[4] * page.width, m[5] * page.height)
            append("<image width=\"1\" height=\"1\" preserveAspectRatio=\"none\" transform=\"matrix(${matrix.joinToString(" ") { number(it) }})\" href=\"data:${asset.mimeType};base64,${Base64.getEncoder().encodeToString(asset.svgBytes())}\"/>")
        }
        val layeredObjects = page.hasObjectLayers
        for ((sliceIndex, page) in page.paintSlices.withIndex()) {
        val prefix = if (layeredObjects) "$sliceIndex-" else ""
        page.images.forEachIndexed { index, image ->
            val asset = requireNotNull(images.firstOrNull { it.id == image.assetId }) { "Image asset missing" }
            val matrix = image.rawToPageTransform()
            append("<g opacity=\"${number(page.objectOpacity(image.layer))}\">")
            if (image.crop != ImageCrop.FULL) {
                val corners = image.placement.corners()
                val path = corners.mapIndexed { i, point -> "${if(i==0) "M" else "L"}${number(point.x)} ${number(point.y)}" }.joinToString(" ") + " Z"
                append("<defs><clipPath id=\"photo-crop-$prefix$index\" clipPathUnits=\"userSpaceOnUse\"><path d=\"$path\"/></clipPath></defs><g clip-path=\"url(#photo-crop-$prefix$index)\">")
            }
            append("<image width=\"1\" height=\"1\" preserveAspectRatio=\"none\" transform=\"matrix(${matrix.joinToString(" ") { number(it) }})\" href=\"data:${asset.mimeType};base64,${Base64.getEncoder().encodeToString(asset.svgBytes())}\"/>")
            if (image.crop != ImageCrop.FULL) append("</g>")
            append("</g>")
        }
        page.textBoxes.forEach { box ->
            val p = box.placement; val style = box.style
            val radians = Math.toRadians(p.degrees.toDouble())
            val c = kotlin.math.cos(radians).toFloat(); val s = kotlin.math.sin(radians).toFloat()
            val origin = p.pagePoint(0f, 0f)
            val matrix = listOf(c, s, -s, c, origin.x, origin.y)
            val color = String.format(Locale.ROOT, "#%06x", style.color and 0xffffff)
            val alpha = number((style.color ushr 24).toFloat() / 255f * page.objectOpacity(box.layer))
            val align = style.alignment.name.lowercase(Locale.ROOT)
            box.sticky?.let { note ->
                val noteColor=String.format(Locale.ROOT,"#%06x",note.color and 0xffffff)
                append("<g transform=\"matrix(${matrix.joinToString(" ") { number(it) }})\" opacity=\"${number(page.objectOpacity(box.layer))}\"><rect width=\"${number(p.width)}\" height=\"${number(p.height)}\" fill=\"$noteColor\"/>")
                if(note.collapsed) {
                    val fold=minOf(p.width,p.height)*.3f
                    append("<path d=\"M${number(p.width-fold)} 0 L${number(p.width-fold)} ${number(fold)} L${number(p.width)} ${number(fold)}\" fill=\"none\" stroke=\"#172a3a\" stroke-width=\"1\"/>")
                }
                append("</g>")
            }
            if(box.sticky?.collapsed==true)return@forEach
            // XHTML supplies real browser font shaping/wrapping; literal text never becomes markup.
            append("<foreignObject width=\"${number(p.width)}\" height=\"${number(p.height)}\" transform=\"matrix(${matrix.joinToString(" ") { number(it) }})\">")
            append("<div xmlns=\"http://www.w3.org/1999/xhtml\" dir=\"auto\" style=\"width:100%;height:100%;overflow:hidden;white-space:pre-wrap;overflow-wrap:anywhere;margin:0;padding:0;line-height:1.2;font-family:${style.font.cssFamily};font-size:${number(style.size)}px;font-weight:${style.weight};font-style:${if (style.italic) "italic" else "normal"};text-decoration:${if (style.underline) "underline" else "none"};color:$color;opacity:$alpha;text-align:$align\">")
            append(box.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;"))
            append("</div></foreignObject>")
        }
        page.attachments.forEach { card ->
            // Same card as Android's AttachmentRendering: the file itself stays inside the notebook package.
            val p = card.placement
            val radians = Math.toRadians(p.degrees.toDouble())
            val c = kotlin.math.cos(radians).toFloat(); val s = kotlin.math.sin(radians).toFloat()
            val origin = p.pagePoint(0f, 0f)
            val unit = minOf(p.height, p.width / 3f)
            val pad = unit * .16f; val icon = unit - 2 * pad
            fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val size = files.firstOrNull { it.id == card.assetId }?.let { FileAttachment.sizeText(it.size) }.orEmpty()
            append("<g transform=\"matrix(${listOf(c, s, -s, c, origin.x, origin.y).joinToString(" ") { number(it) }})\">")
            append("<svg width=\"${number(p.width)}\" height=\"${number(p.height)}\" overflow=\"hidden\">")
            append("<rect x=\"${number(1f)}\" y=\"${number(1f)}\" width=\"${number(p.width - 2)}\" height=\"${number(p.height - 2)}\" rx=\"${number(unit * .14f)}\" fill=\"#f6f8fb\" stroke=\"#c5cfda\" stroke-width=\"2\"/>")
            append("<rect x=\"${number(pad)}\" y=\"${number((p.height - icon) / 2)}\" width=\"${number(icon)}\" height=\"${number(icon)}\" rx=\"${number(icon * .14f)}\" fill=\"#3f6b94\"/>")
            append("<text x=\"${number(pad + icon / 2)}\" y=\"${number(p.height / 2 + icon * .1f)}\" text-anchor=\"middle\" font-family=\"sans-serif\" font-weight=\"700\" font-size=\"${number(icon * .28f)}\" fill=\"white\">${escape(card.label)}</text>")
            val textX = pad * 2 + icon
            append("<text x=\"${number(textX)}\" y=\"${number(p.height / 2 - unit * .04f)}\" font-family=\"sans-serif\" font-size=\"${number(unit * .24f)}\" fill=\"#172a3a\">${escape(card.name)}</text>")
            append("<text x=\"${number(textX)}\" y=\"${number(p.height / 2 + unit * .26f)}\" font-family=\"sans-serif\" font-size=\"${number(unit * .19f)}\" fill=\"#5b6b7b\">${escape(size)}</text>")
            append("</svg></g>")
        }
        for ((strokeIndex, stroke) in page.drawnStrokes.withIndex()) {
            if (stroke.erasures.isNotEmpty()) {
                append("<defs><mask id=\"erase$prefix$strokeIndex\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"${number(page.width)}\" height=\"${number(page.height)}\"><rect width=\"${number(page.width)}\" height=\"${number(page.height)}\" fill=\"white\"/>")
                stroke.erasures.forEach { sweep ->
                    val points = sweep.basis?.let { basis ->
                        append("<g transform=\"matrix(${number(basis.a)} ${number(basis.b)} ${number(basis.c)} ${number(basis.d)} 0 0)\">")
                        sweep.points.map(basis::inverse)
                    } ?: sweep.points
                    if (sweep.points.size == 1) {
                        val point = points[0]
                        append("<circle cx=\"${number(point.x)}\" cy=\"${number(point.y)}\" r=\"${number(sweep.radius)}\" fill=\"black\"/>")
                    } else {
                        append("<path fill=\"none\" stroke=\"black\" stroke-width=\"${number(sweep.radius * 2)}\" stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"")
                        points.forEachIndexed { index, point -> append(if (index == 0) "M" else "L"); append("${number(point.x)} ${number(point.y)} ") }
                        append("\"/>")
                    }
                    if (sweep.basis != null) append("</g>")
                }
                append("</mask></defs><g mask=\"url(#erase$prefix$strokeIndex)\">")
            }
            val color = String.format(Locale.ROOT, "#%06x", stroke.color and 0xffffff)
            stroke.fillColor?.let { fill ->
                val fillOpacity=page.opacityOf(stroke)*(if(stroke.isHighlighter) InkStroke.HIGHLIGHTER_OPACITY else 1f)
                append("<g opacity=\"${number(fillOpacity)}\"${if(stroke.isHighlighter) " style=\"mix-blend-mode:multiply\"" else ""}><path fill=\"${String.format(Locale.ROOT, "#%06x", fill and 0xffffff)}\" fill-rule=\"evenodd\" d=\"")
                stroke.samples.forEachIndexed { index, point -> append(if (index == 0) "M" else "L"); append("${number(point.x)} ${number(point.y)} ") }
                append("Z\"/></g>")
            }
            // Group opacity composites the outlines as one layer, so a highlighter never darkens where it crosses itself.
            val opacity = page.opacityOf(stroke) *
                if (stroke.isHighlighter) InkStroke.HIGHLIGHTER_OPACITY else (stroke.color ushr 24) / 255f
            val opacityGroup = stroke.isHighlighter || opacity < 1f
            if (stroke.isHighlighter) append("<g opacity=\"${number(opacity)}\" style=\"mix-blend-mode:multiply\">")
            else if (opacityGroup) append("<g opacity=\"${number(opacity)}\">")
            for (outline in stroke.outlines) {
                append("<path fill=\"$color\" d=\"")
                outline.forEachIndexed { index, point -> append(if (index == 0) "M" else "L"); append("${number(point.x)} ${number(point.y)} ") }
                append("Z\"/>")
            }
            if (opacityGroup) append("</g>")
            if (stroke.erasures.isNotEmpty()) append("</g>")
        }
        }
        append("</svg>\n")
    }
}

fun fixture(): Notebook {
    val stroke = InkStroke(id = "fixture-stroke", samples = listOf(Sample(100f, 100f, 0, .4f), Sample(300f, 250f, 180, .9f)), outlines = listOf(listOf(Point(98f, 102f), Point(298f, 254f), Point(304f, 246f), Point(102f, 98f))))
    return Notebook("fixture-notebook", "Portable format fixture", listOf(Page("fixture-page", strokes = listOf(stroke))))
}

fun main(args: Array<String>) {
    AttachmentWorkspace.open(File(System.getProperty("java.io.tmpdir"),"scribella-owned-attachments")).use {
        runCommand(args,it.directory)
    }
}
private fun runCommand(args: Array<String>,attachmentDirectory: File) {
    require(args.size >= 2) { "Usage: notes-core inspect FILE | study-manifest FILE | bookmarks FILE | render-svg FILE OUTPUT.svg [PAGE_NUMBER] | compare FILE OTHER_FILE | fixture OUTPUT.inknote" }
    when (args[0]) {
        "transfer-mirror" -> {
            require(args.size == 3) { "Usage: transfer-mirror SYNC_ROOT PRIVATE_MIRROR" }
            val result = PackageTransferMirror.run(File(args[1]), File(args[2]), attachmentDirectory)
            println(PackageTransferMirror.encodeResult(result))
        }
        "transfer-stage", "transfer-hydrate" -> PackageTransferCommands.run(args, attachmentDirectory)
        "prune-reviewed-ink" -> {
            require(args.size == 4) { "Usage: prune-reviewed-ink FILE PLAN.json NEW_OUTPUT.inknote" }
            val source = File(args[1]); val output = File(args[3])
            val plan = kotlinx.serialization.json.Json.decodeFromString<InkPrune.Plan>(File(args[2]).readText())
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == plan.sourceSha256) { "Source changed" }
            val result = InkPrune.apply(NotebookPackage.read(source,attachmentDirectory), plan)
            require(result.removed.values.any { it.isNotEmpty() }) { "No reviewed strokes matched" }
            java.nio.file.Files.newOutputStream(output.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use {
                NotebookPackage.write(result.notebook, it)
            }
            check(NotebookPackage.read(output,attachmentDirectory) == result.notebook) { "Output readback mismatch" }
            result.removed.forEach { (id, ids) -> println("$id: removed ${ids.size} reviewed strokes") }
        }
        "export-ink-svg" -> {
            require(args.size == 3) { "Usage: export-ink-svg FILE NEW_OUTPUT_DIRECTORY" }
            try { PcInkExport.write(NotebookPackage.read(File(args[1]),attachmentDirectory), File(args[2])) }
            catch (_: Exception) { throw IllegalArgumentException("Native handwriting extraction failed; source preserved") }
        }
        "render-ink-svg" -> {
            require(args.size in 3..4) { "Usage: render-ink-svg FILE OUTPUT.svg [PAGE_NUMBER]" }
            val note = NotebookPackage.read(File(args[1]),attachmentDirectory)
            val index = if (args.size == 4) requireNotNull(args[3].toIntOrNull()) - 1 else 0
            require(index in note.pages.indices) { "Page outside notebook" }
            val output = File(args[2])
            require(output.extension.lowercase(Locale.ROOT) == "svg" && !output.exists()) { "Choose a new .svg output file" }
            val svg = StudyInk.svg(note.pages[index])
            // Create atomically: never replace an existing notebook/output, including a raced destination.
            java.nio.file.Files.newOutputStream(output.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use {
                it.write(svg.toByteArray(Charsets.UTF_8))
            }
        }
        "study-manifest" -> {
            require(args.size == 2) { "Usage: study-manifest FILE (metadata JSON on stdout)" }
            val note = NotebookPackage.read(File(args[1]),attachmentDirectory)
            println(StudyManifest.encode(note))
        }
        "fixture" -> File(args[1]).outputStream().use { NotebookPackage.write(fixture(), it) }
        "inspect" -> {
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val images = (if (note.imageAssets.isEmpty()) "" else "; ${note.imageAssets.size} image asset(s)") +
                (if (note.attachmentAssets.isEmpty()) "" else "; ${note.attachmentAssets.size} attached file(s)")
            println("${note.title}: ${note.pages.size} page(s), ${note.pages.sumOf { it.strokes.size }} stroke(s), ${note.pages.sumOf { it.strokes.sumOf { stroke -> stroke.samples.size } }} samples; ${note.assets.size} PDF asset(s)$images; content-feature schema ${note.schemaVersion()}; package verified")
        }
        "bookmarks" -> {
            require(args.size == 2) { "Usage: bookmarks FILE" }
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            note.pages.forEachIndexed { index, page -> page.bookmark?.let { println("${index + 1}\t${page.id}\t$it") } }
        }
        "extract-pdf" -> {
            require(args.size in 3..4) { "Usage: extract-pdf FILE OUTPUT.pdf [ASSET_NUMBER]" }
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val index = if (args.size == 4) requireNotNull(args[3].toIntOrNull()) - 1 else 0
            require(index in note.assets.indices) { "PDF asset number outside notebook" }
            File(args[2]).outputStream().use { note.assets[index].writeTo(it) }
        }
        "extract-image" -> {
            require(args.size in 3..4) { "Usage: extract-image FILE OUTPUT [ASSET_NUMBER]" }
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val index = if (args.size == 4) (args[3].toIntOrNull() ?: error("Invalid image number")) - 1 else 0
            require(index in note.imageAssets.indices) { "Image asset number outside notebook" }
            File(args[2]).outputStream().use { note.imageAssets[index].writeTo(it) }
        }
        "extract-file" -> {
            require(args.size == 4) { "Usage: extract-file FILE PAGE_NUMBER CARD_NUMBER (writes the file under its own name in the current folder)" }
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val page = note.pages.getOrNull((args[2].toIntOrNull() ?: 0) - 1) ?: error("Page number outside notebook")
            val card = page.attachments.getOrNull((args[3].toIntOrNull() ?: 0) - 1) ?: error("Card number outside page")
            val target = File(card.name).name.ifEmpty { "attachment" }
            require(!File(target).exists()) { "$target already exists" }
            File(target).outputStream().use { output -> note.attachmentAssets.first { it.id == card.assetId }.writeTo(output) }
            println("Wrote $target")
        }
        "render-svg" -> {
            require(args.size in 3..4) { "Supply an output .svg path and optional page number" }
            val note = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val pageNumber = if (args.size == 4) requireNotNull(args[3].toIntOrNull()) { "Invalid page number" } else 1
            require(pageNumber in 1..note.pages.size) { "Page number outside notebook" }
            File(args[2]).writeText(SvgRenderer.render(note.pages[pageNumber - 1], note.imageAssets, note.attachmentAssets))
        }
        "compare" -> {
            require(args.size == 3) { "Supply two notebook files" }
            val first = File(args[1]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            val second = File(args[2]).inputStream().use { NotebookPackage.read(it,attachmentDirectory) }
            require(first == second) { "Notebook contents differ" }
            println("Notebook contents match, including identities, samples and geometry")
        }
        else -> error("Unknown command: ${args[0]}")
    }
}
