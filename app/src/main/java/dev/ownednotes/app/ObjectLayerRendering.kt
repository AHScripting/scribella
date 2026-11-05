package dev.ownednotes.app

import android.graphics.Canvas
import dev.ownednotes.core.Page
import dev.ownednotes.core.objectOpacity
import kotlin.math.roundToInt

internal inline fun Canvas.withObjectOpacity(page: Page, layer: String?, draw: () -> Unit) {
    val opacity = page.objectOpacity(layer)
    if (opacity >= 1f) { draw(); return }
    val saved = saveLayerAlpha(null, (255 * opacity).roundToInt())
    try { draw() } finally { restoreToCount(saved) }
}
