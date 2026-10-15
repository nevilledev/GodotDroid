package com.godotdroid.exporter.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Descriptor stored in assets/standalone_config.json of exported standalone game APKs.
 */
data class StandaloneConfig(
    val gameId: String,
    val gameTitle: String,
    val engineVersionKey: String,
    val rendererType: String,
    val controlPreset: String,
    val injectSteamStub: Boolean,
    val customCliFlags: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gameId", gameId)
        put("gameTitle", gameTitle)
        put("engineVersionKey", engineVersionKey)
        put("rendererType", rendererType)
        put("controlPreset", controlPreset)
        put("injectSteamStub", injectSteamStub)
        val arr = JSONArray()
        customCliFlags.forEach { arr.put(it) }
        put("customCliFlags", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): StandaloneConfig {
            val flagsList = mutableListOf<String>()
            val arr = json.optJSONArray("customCliFlags")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    flagsList.add(arr.getString(i))
                }
            }

            return StandaloneConfig(
                gameId = json.getString("gameId"),
                gameTitle = json.getString("gameTitle"),
                engineVersionKey = json.optString("engineVersionKey", "4.3"),
                rendererType = json.optString("rendererType", "MOBILE"),
                controlPreset = json.optString("controlPreset", "PC Keyboard (WASD + Mouse)"),
                injectSteamStub = json.optBoolean("injectSteamStub", false),
                customCliFlags = flagsList
            )
        }
    }
}
