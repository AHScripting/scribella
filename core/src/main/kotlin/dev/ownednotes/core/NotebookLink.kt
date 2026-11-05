package dev.ownednotes.core

import kotlinx.serialization.Serializable

/** Stable local targets; titles and page positions are deliberately not stored. */
@Serializable data class NotebookLink(val notebookId:String, val pageId:String?=null) {
    init {
        require(notebookId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid link notebook ID" }
        require(pageId==null || pageId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid link page ID" }
    }
}
val Page.hasNotebookLinks get()=strokes.any {it.link!=null} || textBoxes.any {it.link!=null}
