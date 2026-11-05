package dev.ownednotes.core

import kotlinx.serialization.Serializable

/** Weak package-local reference: removing the clip never invalidates the writer's ink. */
@Serializable data class AudioLink(val attachmentId:String,val timeMs:Long) {
    init {
        require(attachmentId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid audio clip reference" }
        require(timeMs in 0..86_400_000L) { "Invalid audio position" }
    }
}
