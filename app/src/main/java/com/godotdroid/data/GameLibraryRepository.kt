package com.godotdroid.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
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
    companion object {
        private const val TAG = "GameLibraryRepository"
        val COMPANION_EXTENSIONS = setOf("pck", "zip", "dll", "so", "dylib")

        fun sanitizeFileName(name: String): String {
            val extension = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            val baseName = name.substringBeforeLast('.', name)
            val normalized = java.text.Normalizer.normalize(baseName, java.text.Normalizer.Form.NFD)
            val withoutDiacritics = normalized.replace("\\p{M}".toRegex(), "")
            val cleanBase = withoutDiacritics.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val sanitized = (if (cleanBase.isBlank()) "game_${System.currentTimeMillis()}" else cleanBase) + extension
            return sanitized
        }
    }

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

    suspend fun importGameFromUri(uri: Uri): Result<SavedGameItem> = importGameFromUris(listOf(uri))

    suspend fun importGameFromUris(uris: List<Uri>): Result<SavedGameItem> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No files selected"))
        }
        try {
            data class SelectedFileInfo(val uri: Uri, val name: String, val size: Long)
            val fileInfos = uris.map { uri ->
                val name = queryFileName(uri) ?: "game_${System.currentTimeMillis()}.pck"
                val size = queryFileSize(uri)
                SelectedFileInfo(uri, name, size)
            }

            // Designate primary pack: prefer .pck over .exe, then game.pck, then largest .pck
            fun score(info: SelectedFileInfo): Double {
                val lower = info.name.lowercase()
                var s = 0.0
                if (lower.endsWith(".pck")) s += 1000.0
                else if (lower.endsWith(".exe")) s += 500.0
                else s += 1.0

                if (lower == "game.pck" || lower.endsWith("/game.pck")) s += 50.0
                else if (lower.contains("game") || lower.contains("main")) s += 20.0

                val sizeMb = info.size.toDouble() / (1024.0 * 1024.0)
                s += sizeMb.coerceAtMost(50.0)
                return s
            }

            val primaryInfo = fileInfos.maxByOrNull { score(it) } ?: fileInfos.first()
            val id = UUID.randomUUID().toString()
            val targetDir = File(gamesDir, id).apply { mkdirs() }

            var primaryFile: File? = null
            for (info in fileInfos) {
                val safeName = sanitizeFileName(info.name)
                val dest = File(targetDir, safeName)
                context.contentResolver.openInputStream(info.uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                        output.flush()
                        try { output.fd.sync() } catch (_: Throwable) {}
                    }
                }
                dest.setReadable(true, false)
                if (dest.exists() && safeName != info.name) {
                    try {
                        val originalAlias = File(targetDir, info.name)
                        if (!originalAlias.exists()) {
                            android.system.Os.link(dest.absolutePath, originalAlias.absolutePath)
                        }
                    } catch (_: Throwable) {}
                }
                if (info == primaryInfo) {
                    primaryFile = dest
                }
            }

            if (primaryFile == null || !primaryFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Failed to stage primary game package"))
            }

            // Normalize primary package: If source is an embedded Windows PE .exe, extract the standalone PCK directly into main.pck.
            // If it is already a standalone .pck, normalize its name to main.pck so paths are clean ASCII and immune to Unicode/URI issues.
            val pckInspector = com.godotdroid.pck.inspector.PckBinaryInspector()
            try {
                val header = pckInspector.inspectHeader(primaryFile)
                val mainPck = File(targetDir, "main.pck")
                if (header.isEmbeddedExe) {
                    pckInspector.extractStandalonePck(primaryFile, mainPck, header)
                    mainPck.setReadable(true, false)
                    Log.i(TAG, "Carved standalone PCK (${mainPck.length()} bytes) from embedded EXE ${primaryFile.name}")
                    primaryFile.delete()
                    primaryFile = mainPck
                } else if (primaryFile.name != "main.pck") {
                    if (primaryFile.renameTo(mainPck)) {
                        mainPck.setReadable(true, false)
                        primaryFile = mainPck
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not normalize primary PCK: ${e.message}")
            }

            // Auto-discover and copy all companion files (*.pck, *.zip, *.dll, *.so, *.dylib)
            // from the source parent directory so the user never has to copy companion files manually
            try {
                val resolvedParentDirs = uris.mapNotNull { resolveParentDirectory(it) }.distinct()
                for (parentDir in resolvedParentDirs) {
                    val companions = parentDir.listFiles()?.filter { file ->
                        file.isFile &&
                        file.extension.lowercase() in COMPANION_EXTENSIONS &&
                        !file.name.equals(primaryInfo.name, ignoreCase = true) &&
                        !file.name.equals(primaryFile.name, ignoreCase = true)
                    } ?: emptyList()

                    for (companion in companions) {
                        val safeName = sanitizeFileName(companion.name)
                        val dest = File(targetDir, safeName)
                        if (!dest.exists() || dest.length() != companion.length()) {
                            try {
                                companion.copyTo(dest, overwrite = true)
                                dest.setReadable(true, false)
                                if (safeName != companion.name) {
                                    val originalAlias = File(targetDir, companion.name)
                                    if (!originalAlias.exists()) {
                                        android.system.Os.link(dest.absolutePath, originalAlias.absolutePath)
                                    }
                                }
                                Log.i(TAG, "Auto-copied companion file from source directory: ${companion.name} (${companion.length()} bytes)")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to auto-copy companion file ${companion.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Companion auto-discovery error: ${e.message}")
            }

            // Analyze imported primary file
            val report = analyzer.analyze(primaryFile)
            val defaultRenderer = if (report.detectedRenderer != RendererType.UNKNOWN) {
                report.detectedRenderer
            } else {
                RendererType.MOBILE
            }

            val item = SavedGameItem(
                id = id,
                filePath = primaryFile.absolutePath,
                originalFileName = primaryInfo.name,
                fileSize = primaryFile.length(),
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

    suspend fun addCompanionFiles(gameId: String, uris: List<Uri>): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val games = getGames()
            val game = games.find { it.id == gameId }
                ?: return@withContext Result.failure(IllegalArgumentException("Game not found"))
            val targetDir = File(game.filePath).parentFile
                ?: return@withContext Result.failure(IllegalStateException("Game directory not found"))

            for (uri in uris) {
                val rawName = queryFileName(uri) ?: continue
                val safeName = sanitizeFileName(rawName)
                val dest = File(targetDir, safeName)
                if (dest.exists()) dest.setWritable(true)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                if (dest.exists() && safeName != rawName) {
                    try {
                        val originalAlias = File(targetDir, rawName)
                        if (!originalAlias.exists()) {
                            android.system.Os.link(dest.absolutePath, originalAlias.absolutePath)
                        }
                    } catch (_: Throwable) {}
                }
            }
            Result.success(getCompanionFiles(game))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCompanionFiles(game: SavedGameItem): List<File> {
        val gameFile = File(game.filePath)
        val dir = gameFile.parentFile ?: return emptyList()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { file ->
            file.isFile && !file.name.equals(gameFile.name, ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()
    }

    suspend fun removeCompanionFile(gameId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val games = getGames()
        val game = games.find { it.id == gameId } ?: return@withContext false
        val targetDir = File(game.filePath).parentFile ?: return@withContext false
        val file = File(targetDir, fileName)
        if (file.exists() && !file.name.equals(File(game.filePath).name, ignoreCase = true)) {
            file.delete()
        } else {
            false
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
                file.parentFile?.deleteRecursively()
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

    private fun queryFileSize(uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (colIdx != -1 && !cursor.isNull(colIdx)) {
                            return cursor.getLong(colIdx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return 0L
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

    /**
     * Resolves the parent directory of a source URI if accessible via direct filesystem
     * or standard SAF external storage document providers.
     */
    fun resolveParentDirectory(uri: Uri): File? {
        try {
            // 1. Direct file:// URI
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val file = File(path)
                val parent = file.parentFile
                if (parent != null && parent.isDirectory && parent.canRead()) {
                    return parent
                }
            }

            // 2. content:// URI
            if (uri.scheme == "content") {
                // Check SAF Document URI
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (uri.authority == "com.android.externalstorage.documents") {
                        val parts = docId.split(":", limit = 2)
                        if (parts.size == 2) {
                            val type = parts[0]
                            val relPath = parts[1]
                            val baseDir = if ("primary".equals(type, ignoreCase = true)) {
                                Environment.getExternalStorageDirectory()
                            } else {
                                File("/storage/$type")
                            }
                            val candidate = File(baseDir, relPath)
                            val parent = candidate.parentFile
                            if (parent != null && parent.isDirectory && parent.canRead()) {
                                return parent
                            }
                        }
                    } else if (uri.authority == "com.android.providers.downloads.documents") {
                        if (docId.startsWith("raw:")) {
                            val rawPath = docId.removePrefix("raw:")
                            val candidate = File(rawPath)
                            val parent = candidate.parentFile
                            if (parent != null && parent.isDirectory && parent.canRead()) {
                                return parent
                            }
                        }
                    }
                }

                // MediaStore _data column fallback query
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (idx != -1) {
                                val path = cursor.getString(idx)
                                if (!path.isNullOrBlank()) {
                                    val candidate = File(path)
                                    val parent = candidate.parentFile
                                    if (parent != null && parent.isDirectory && parent.canRead()) {
                                        return parent
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Path pattern fallback e.g. contains "/storage/"
                val uriPath = uri.path
                if (uriPath != null && uriPath.contains("/storage/")) {
                    val sub = "/storage/" + uriPath.substringAfter("/storage/")
                    val candidate = File(sub)
                    val parent = candidate.parentFile
                    if (parent != null && parent.isDirectory && parent.canRead()) {
                        return parent
                    }
                }
            }

            // 3. Fallback: Search device external storage for matching file and companion files
            val fileName = queryFileName(uri)
            if (!fileName.isNullOrBlank()) {
                val size = queryFileSize(uri)
                val foundParent = findSourceDirectoryOnDevice(fileName, size)
                if (foundParent != null && foundParent.isDirectory && foundParent.canRead()) {
                    Log.i(TAG, "Resolved source parent directory via device storage scan: ${foundParent.absolutePath}")
                    return foundParent
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve parent directory for $uri: ${e.message}")
        }
        return null
    }

    /**
     * Recursively searches common device storage directories (/sdcard/Download, /storage/emulated/0, etc.)
     * for a folder that contains targetName (with optional expected size matching).
     */
    fun findSourceDirectoryOnDevice(fileName: String, expectedSize: Long = 0L): File? {
        val searchRoots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            File("/storage/emulated/0"),
            File("/sdcard")
        ).distinct().filter { it.exists() && it.isDirectory }

        for (root in searchRoots) {
            val found = scanDirectoryForFile(root, fileName, expectedSize, maxDepth = 4, currentDepth = 0)
            if (found != null) {
                return found.parentFile
            }
        }
        return null
    }

    private fun scanDirectoryForFile(dir: File, targetName: String, expectedSize: Long, maxDepth: Int, currentDepth: Int): File? {
        if (currentDepth > maxDepth) return null
        val files = dir.listFiles() ?: return null
        // Direct files check
        for (f in files) {
            if (f.isFile && f.name.equals(targetName, ignoreCase = true)) {
                if (expectedSize <= 0L || Math.abs(f.length() - expectedSize) < 2048L) {
                    return f
                }
            }
        }
        // Subdirectories check (ignore hidden folders)
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                val found = scanDirectoryForFile(f, targetName, expectedSize, maxDepth, currentDepth + 1)
                if (found != null) return found
            }
        }
        return null
    }
}
