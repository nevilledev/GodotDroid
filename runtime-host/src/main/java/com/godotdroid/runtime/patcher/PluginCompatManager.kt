package com.godotdroid.runtime.patcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class PluginCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val targetSoName: String,
    val downloadUrl: String,
    val isInstalled: Boolean = false
)

/**
 * Manages GDExtension and GDNative binary compatibility, downloads community ARM64 libraries,
 * and patches .gdextension configuration files.
 */
class PluginCompatManager(private val context: Context) {

    private val pluginCacheDir = File(context.filesDir, "plugin_cache").apply { mkdirs() }

    val builtInPlugins: List<PluginCatalogEntry> = listOf(
        PluginCatalogEntry(
            id = "godotsteam",
            displayName = "GodotSteam (ARM64 Stub / Native)",
            description = "Steamworks API integration for Godot 4.x / 3.x",
            targetSoName = "libgodotsteam.so",
            downloadUrl = "https://github.com/GodotSteam/GodotSteam/releases/download/v4.12/godotsteam-android-arm64.zip"
        ),
        PluginCatalogEntry(
            id = "godot-jolt",
            displayName = "Godot Jolt Physics",
            description = "High performance Jolt Physics replacement for Godot 4",
            targetSoName = "libjolt.android.template_release.arm64.so",
            downloadUrl = "https://github.com/godot-jolt/godot-jolt/releases/download/v0.13.0-stable/godot-jolt-android.zip"
        ),
        PluginCatalogEntry(
            id = "godot-sqlite",
            displayName = "Godot SQLite",
            description = "SQLite database bindings for Godot 4",
            targetSoName = "libgodot-sqlite.android.template_release.arm64.so",
            downloadUrl = "https://github.com/2shady4u/godot-sqlite/releases/download/v3.2/godot-sqlite-android.zip"
        ),
        PluginCatalogEntry(
            id = "fmod",
            displayName = "FMOD for Godot",
            description = "FMOD Studio integration for adaptive game audio",
            targetSoName = "libfmod_godot.android.template_release.arm64.so",
            downloadUrl = "https://github.com/utopia-rise/fmod-gdextension/releases/download/5.0.0/fmod-android.zip"
        ),
        PluginCatalogEntry(
            id = "terrain3d",
            displayName = "Terrain3D",
            description = "High performance editable 3D terrain system",
            targetSoName = "libterrain3d.android.template_release.arm64.so",
            downloadUrl = "https://github.com/TokisanGames/Terrain3D/releases/download/v0.9.3/terrain3d-android.zip"
        )
    )

    fun getCatalog(): List<PluginCatalogEntry> {
        return builtInPlugins.map { plugin ->
            val cachedFile = File(pluginCacheDir, plugin.targetSoName)
            plugin.copy(isInstalled = cachedFile.exists() && cachedFile.length() > 0)
        }
    }

    /**
     * Imports a user-supplied ARM64 native library (.so) into the plugin cache.
     */
    fun importCustomSo(inputStream: InputStream, targetSoName: String): File {
        val destFile = File(pluginCacheDir, targetSoName)
        if (destFile.exists()) destFile.setWritable(true)
        FileOutputStream(destFile).use { out ->
            inputStream.copyTo(out)
        }
        destFile.setReadOnly()
        return destFile
    }

    /**
     * Patches an existing text .gdextension content to ensure android.release.arm64-v8a points to the staged library.
     */
    fun patchGdExtensionContent(originalContent: String, stagedRelativeSoPath: String): String {
        val lines = originalContent.lines().toMutableList()
        val librariesIndex = lines.indexOfFirst { it.trim().equals("[libraries]", ignoreCase = true) }

        val arm64Entry = "android.release.arm64-v8a = \"$stagedRelativeSoPath\""
        val arm64DebugEntry = "android.debug.arm64-v8a = \"$stagedRelativeSoPath\""

        if (librariesIndex != -1) {
            // Check if already present
            val hasArm64 = lines.any { it.trim().startsWith("android.release.arm64", ignoreCase = true) }
            if (!hasArm64) {
                lines.add(librariesIndex + 1, arm64Entry)
                lines.add(librariesIndex + 2, arm64DebugEntry)
            }
        } else {
            lines.add("")
            lines.add("[libraries]")
            lines.add(arm64Entry)
            lines.add(arm64DebugEntry)
        }

        return lines.joinToString("\n")
    }

    /**
     * Downloads an ARM64 plugin from remote url.
     */
    suspend fun downloadPlugin(entry: PluginCatalogEntry): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = URL(entry.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val destFile = File(pluginCacheDir, entry.targetSoName)
            if (destFile.exists()) destFile.setWritable(true)

            // If it's a zip archive, extract the .so file
            if (entry.downloadUrl.endsWith(".zip", ignoreCase = true)) {
                val zipIn = ZipInputStream(connection.inputStream)
                var zipEntry = zipIn.nextEntry
                var found = false
                while (zipEntry != null) {
                    if (zipEntry.name.endsWith(".so", ignoreCase = true) &&
                        (zipEntry.name.contains("arm64") || zipEntry.name.contains("aarch64") || zipEntry.name.endsWith(entry.targetSoName))
                    ) {
                        FileOutputStream(destFile).use { out -> zipIn.copyTo(out) }
                        found = true
                        break
                    }
                    zipIn.closeEntry()
                    zipEntry = zipIn.nextEntry
                }
                if (!found) {
                    return@withContext Result.failure(IllegalStateException("No arm64 .so found in downloaded archive"))
                }
            } else {
                FileOutputStream(destFile).use { out -> connection.inputStream.copyTo(out) }
            }

            destFile.setReadOnly()
            connection.disconnect()
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download plugin ${entry.id}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "PluginCompatManager"
    }
}
