package dev.ownednotes.core

import kotlinx.serialization.Serializable

/** Explicit, hash-guarded local maintenance of reviewed handwriting bands. Never interprets attachment content. */
object InkPrune {
    @Serializable data class Band(val top: Float, val bottom: Float)
    @Serializable data class PagePlan(val pageId: String, val handwritingSha256: String, val bands: List<Band>)
    @Serializable data class Plan(val notebookId: String, val sourceSha256: String, val pages: List<PagePlan>)
    data class Result(val notebook: Notebook, val removed: Map<String, Set<String>>)

    fun apply(notebook: Notebook, plan: Plan): Result {
        require(notebook.id == plan.notebookId) { "Notebook identity changed" }
        require(plan.pages.map { it.pageId }.distinct().size == plan.pages.size) { "Duplicate page plan" }
        val hashes = StudyManifest.build(notebook).pages.associate { it.pageId to it.handwritingSha256 }
        val removed = linkedMapOf<String, Set<String>>()
        for (request in plan.pages) {
            require(hashes[request.pageId] == request.handwritingSha256) { "Handwriting changed; review current ink before pruning" }
            require(request.bands.isNotEmpty() && request.bands.all {
                it.top.isFinite() && it.bottom.isFinite() && it.top >= 0 && it.bottom <= 1 && it.top < it.bottom
            }) { "Invalid reviewed band" }
            val page = notebook.pages.first { it.id == request.pageId }
            removed[page.id] = page.drawnStrokes.filter { stroke ->
                val points = stroke.outlines.flatten()
                points.isNotEmpty() && request.bands.any { band ->
                    (band.top == 0f && band.bottom == 1f) ||
                        points.all { (band.top == 0f || it.y >= page.height * band.top) &&
                        (band.bottom == 1f || it.y <= page.height * band.bottom) }
                }
            }.map { it.id }.toSet()
        }
        val next = notebook.copy(pages = notebook.pages.map { page ->
            val ids = removed[page.id].orEmpty()
            if (ids.isEmpty()) page else page.copy(strokes = page.strokes.filterNot { it.id in ids },
                reviewedInk = page.reviewedInk.filterNot { record -> record.strokeIds.any { it in ids } })
        })
        next.validate()
        return Result(next, removed)
    }
}
