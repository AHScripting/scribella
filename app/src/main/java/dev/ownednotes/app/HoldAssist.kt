package dev.ownednotes.app

/** Local tool preferences. Saved strokes keep the result; Undo retains the source gesture in memory. */
enum class HoldAssist { FREEHAND, LINE, SHAPES }

data class HoldSettings(val delayMs: Long = 500, val toleranceMm: Float = 1.2f) {
    init {
        require(delayMs in 250..1500) { "Hold delay must be 250–1500 ms" }
        require(toleranceMm.isFinite() && toleranceMm in .5f..3f) { "Movement tolerance must be 0.5–3 mm" }
    }
}
