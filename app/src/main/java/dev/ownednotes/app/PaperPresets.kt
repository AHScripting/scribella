package dev.ownednotes.app

import android.content.SharedPreferences
import dev.ownednotes.core.PaperPattern
import dev.ownednotes.core.PaperStyle
import org.json.JSONArray
import org.json.JSONObject

/** Local reusable styles, independent of any page or portable notebook. */
class PaperPresets(private val preferences: SharedPreferences) {
    data class Preset(val name: String, val paper: PaperStyle)

    fun list(): List<Preset> {
        val raw = runCatching { preferences.getString(KEY, null) }.getOrNull() ?: return emptyList()
        if (raw.length > 32_768) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Preset>()
        for (i in 0 until minOf(array.length(), LIMIT)) {
            val preset = runCatching {
                val json = array.getJSONObject(i)
                val name = checkedName(json.getString("name"))
                val paper = PaperStyle(PaperPattern.valueOf(json.getString("pattern")), json.getLong("color"),
                    json.getLong("guideColor"), json.getDouble("spacing").toFloat(), json.getDouble("weight").toFloat())
                paper.validate(1f, 1f) // scalar bounds; density is checked against the destination page later
                Preset(name, paper)
            }.getOrNull() ?: continue
            if (result.none { it.name.equals(preset.name, ignoreCase = true) }) result += preset
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun save(name: String, paper: PaperStyle) {
        val checked = checkedName(name)
        paper.validate(1f, 1f)
        val entries = list()
        require(entries.none { it.name.equals(checked, ignoreCase = true) }) { "A preset with that name already exists" }
        require(entries.size < LIMIT) { "You can save up to $LIMIT paper presets" }
        write(entries + Preset(checked, paper))
    }

    fun remove(name: String): Boolean {
        val entries = list()
        val kept = entries.filterNot { it.name == name }
        if (kept.size == entries.size) return false
        write(kept)
        return true
    }

    private fun write(entries: List<Preset>) {
        val array = JSONArray()
        entries.forEach { (name, paper) -> array.put(JSONObject().put("name", name).put("pattern", paper.pattern.name)
            .put("color", paper.color).put("guideColor", paper.guideColor).put("spacing", paper.spacing.toDouble())
            .put("weight", paper.weight.toDouble())) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private fun checkedName(value: String): String {
        val name = value.trim()
        require(name.length in 1..60 && name.none { it.isISOControl() }) { "Use 1–60 characters without line breaks" }
        return name
    }

    companion object {
        const val KEY = "paperPresetsV1"
        const val LIMIT = 24
    }
}
