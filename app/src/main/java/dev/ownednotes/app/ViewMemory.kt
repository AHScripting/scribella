package dev.ownednotes.app

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * F086: where each notebook was last viewed, so it reopens at the same zoom and scroll, not only the same page (P028).
 * Single page keeps the zoom (a multiple of fit, 1–8) and the page point at the view centre; continuous keeps the page
 * at the top of the strip and how far down it was scrolled. Local preference "view:<notebook id>"; never in the notebook file.
 */
class ViewMemory(private val preferences: SharedPreferences) {
    sealed class View { abstract val pageId: String }
    data class Single(override val pageId: String, val zoom: Float, val centreX: Float, val centreY: Float) : View() {
        init { require(zoom.isFinite() && zoom in 1f..8f && centreX.isFinite() && centreY.isFinite()) { "Invalid page view" } }
    }
    data class Strip(override val pageId: String, val localY: Float) : View() {
        init { require(localY.isFinite() && localY >= 0f) { "Invalid strip view" } }
    }

    private fun key(notebookId: String) = "view:$notebookId"

    fun save(notebookId: String, view: View) {
        val json = JSONObject().put("page", view.pageId)
        when (view) {
            is Single -> json.put("mode", "single").put("zoom", view.zoom.toDouble()).put("x", view.centreX.toDouble()).put("y", view.centreY.toDouble())
            is Strip -> json.put("mode", "strip").put("top", view.localY.toDouble())
        }
        val text = json.toString()
        if (preferences.getString(key(notebookId), null) != text) preferences.edit().putString(key(notebookId), text).apply()
    }

    /** The saved view, or null when none was saved or the saved value is unreadable. */
    fun get(notebookId: String): View? = runCatching {
        val json = JSONObject(preferences.getString(key(notebookId), null) ?: return null)
        val page = json.getString("page")
        when (json.getString("mode")) {
            "single" -> Single(page, json.getDouble("zoom").toFloat(), json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            "strip" -> Strip(page, json.getDouble("top").toFloat())
            else -> null
        }
    }.getOrNull()
}
