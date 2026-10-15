package com.godotdroid.overlay.repository

import android.content.Context
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.overlay.model.TouchControlLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Handles persistent storage of per-game virtual touch control layouts in JSON format.
 */
class TouchLayoutRepository(private val context: Context) {

    private val layoutsDir = File(context.filesDir, "control_layouts").apply { mkdirs() }

    /**
     * Loads saved layout for a game, or creates and returns the default PC Keyboard preset.
     */
    suspend fun getLayout(gameId: String): TouchControlLayout = withContext(Dispatchers.IO) {
        val file = getLayoutFile(gameId)
        if (file.exists()) {
            try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                return@withContext TouchControlLayout.fromJson(json)
            } catch (_: Exception) {
            }
        }
        val defaultLayout = DefaultControlPresets.createPcKeyboardPreset(gameId)
        saveLayout(defaultLayout)
        defaultLayout
    }

    /**
     * Saves a control layout to disk.
     */
    suspend fun saveLayout(layout: TouchControlLayout) = withContext(Dispatchers.IO) {
        val file = getLayoutFile(layout.gameId)
        file.writeText(layout.toJson().toString(2), Charsets.UTF_8)
    }

    /**
     * Resets the layout for a game to one of the built-in presets.
     */
    suspend fun resetToPreset(gameId: String, presetName: String): TouchControlLayout = withContext(Dispatchers.IO) {
        val preset = DefaultControlPresets.createPreset(presetName, gameId)
        saveLayout(preset)
        preset
    }

    private fun getLayoutFile(gameId: String): File {
        val safeId = gameId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(layoutsDir, "layout_$safeId.json")
    }
}
