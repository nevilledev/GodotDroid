package com.godotdroid.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.godotdroid.pck.analyzer.GameCompatibilityAnalyzer
import com.godotdroid.pck.model.RendererType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class GameLibraryRepository(
    private val context: Context,
    private val analyzer: GameCompatibilityAnalyzer = GameCompatibilityAnalyzer()
) {
    private val gamesDir = File(context.filesDir, "games").apply { mkdirs() }
    private val databaseFile = File(context.filesDir, "game_library.json")

    suspend fun getGames(): List<SavedGameItem> = withContext(Dispatchers.IO) {
        if (!databaseFile.exists()) return@withContext emptyList()
        try {
            val jsonStr = databaseFile.readText(Charsets.UTF_8)
            val jsonArr = JSONArray(jsonStr)
            val list = mutableListOf<SavedGameItem>()
            for (i in 0 until jsonArr.length()) {
                val item = SavedGameItem.fromJson(jsonArr.getJSONObject(i))
                // Only return if file still exists on disk
                if (File(item.filePath).exists()) {
                    list.add(item)
                }
            }
            list.sortedByDescending { it.dateAdded }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun importGameFromUri(uri: Uri): Result<SavedGameItem> = withContext(Dispatchers.IO) {
        try {
            val originalName = queryFileName(uri) ?: "game_${System.currentTimeMillis()}.pck"
            val id = UUID.randomUUID().toString()
            val targetDir = File(gamesDir, id).apply { mkdirs() }
            val destinationFile = File(targetDir, originalName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for $uri"))

            // Analyze imported file
            val report = analyzer.analyze(destinationFile)
            val defaultRenderer = if (report.detectedRenderer != RendererType.UNKNOWN) {
                report.detectedRenderer
            } else {
                RendererType.MOBILE
            }

            val item = SavedGameItem(
                id = id,
                filePath = destinationFile.absolutePath,
                originalFileName = originalName,
                fileSize = destinationFile.length(),
                report = report,
                selectedRenderer = defaultRenderer,
                dateAdded = System.currentTimeMillis()
            )

            // Save to database
            val existing = getGames().toMutableList()
            existing.removeAll { it.filePath == item.filePath }
            existing.add(0, item)
            saveGames(existing)

            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRendererOverride(gameId: String, renderer: RendererType): Boolean = withContext(Dispatchers.IO) {
        val games = getGames().toMutableList()
        val index = games.indexOfFirst { it.id == gameId }
        if (index != -1) {
            games[index] = games[index].copy(selectedRenderer = renderer)
            saveGames(games)
            true
        } else {
            false
        }
    }

    suspend fun deleteGame(gameId: String): Boolean = withContext(Dispatchers.IO) {
        val games = getGames().toMutableList()
        val index = games.indexOfFirst { it.id == gameId }
        if (index != -1) {
            val item = games.removeAt(index)
            saveGames(games)
            try {
                val file = File(item.filePath)
                file.delete()
                file.parentFile?.delete()
            } catch (_: Exception) {
            }
            true
        } else {
            false
        }
    }

    private fun saveGames(games: List<SavedGameItem>) {
        val arr = JSONArray()
        for (g in games) {
            arr.put(g.toJson())
        }
        databaseFile.writeText(arr.toString(2), Charsets.UTF_8)
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (colIdx != -1) {
                            name = cursor.getString(colIdx)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (name == null) {
            name = uri.path?.let { p ->
                val cut = p.lastIndexOf('/')
                if (cut != -1) p.substring(cut + 1) else p
            }
        }
        return name
    }
}
