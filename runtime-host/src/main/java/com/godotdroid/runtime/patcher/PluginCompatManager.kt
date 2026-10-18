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

    private val pluginCacheDir by lazy {
        val base = context.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "godotdroid_plugin_cache")
        File(base, "plugin_cache").apply { mkdirs() }
    }

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
     * Patches .gdextension (Godot 4) or .gdnlib (Godot 3) configuration file content
     * to inject Android ARM64 binary paths into [libraries] or [entry] sections.
     */
    fun patchExtensionConfigContent(
        originalIni: String,
        isGodot4: Boolean,
        androidSoNames: List<String>
    ): String {
        val lines = originalIni.lines().toMutableList()
        val targetLibs = if (androidSoNames.isEmpty()) listOf("libplugin.so") else androidSoNames

        if (isGodot4) {
            // Godot 4: [libraries] section
            var libIdx = lines.indexOfFirst { it.trim().equals("[libraries]", ignoreCase = true) }
            if (libIdx == -1) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[libraries]")
                libIdx = lines.size - 1
            }

            var insertOffset = 1
            for (so in targetLibs) {
                val path = if (so.startsWith("res://")) so else "res://bin/$so"
                val releaseEntry = "android.release.arm64-v8a = \"$path\""
                val debugEntry = "android.debug.arm64-v8a = \"$path\""
                val plainArm64Entry = "android.release.arm64 = \"$path\""

                if (!lines.any { it.trim().startsWith("android.release.arm64-v8a", ignoreCase = true) }) {
                    lines.add(libIdx + insertOffset++, releaseEntry)
                }
                if (!lines.any { it.trim().startsWith("android.debug.arm64-v8a", ignoreCase = true) }) {
                    lines.add(libIdx + insertOffset++, debugEntry)
                }
                if (!lines.any { it.trim().startsWith("android.release.arm64 =", ignoreCase = true) }) {
                    lines.add(libIdx + insertOffset++, plainArm64Entry)
                }
            }
        } else {
            // Godot 3: [entry] section
            var entryIdx = lines.indexOfFirst { it.trim().equals("[entry]", ignoreCase = true) }
            if (entryIdx == -1) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[entry]")
                entryIdx = lines.size - 1
            }

            var insertOffset = 1
            for (so in targetLibs) {
                val path = if (so.startsWith("res://")) so else "res://bin/$so"
                val entryLine = "Android.arm64-v8a=\"$path\""
                if (!lines.any { it.trim().startsWith("Android.arm64-v8a", ignoreCase = true) }) {
                    lines.add(entryIdx + insertOffset++, entryLine)
                }
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Patches an existing text .gdextension content to ensure android.release.arm64-v8a points to the staged library.
     */
    fun patchGdExtensionContent(originalContent: String, stagedRelativeSoPath: String): String {
        return patchExtensionConfigContent(originalContent, isGodot4 = true, listOf(stagedRelativeSoPath))
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
