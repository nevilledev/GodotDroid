package com.godotdroid.runtime.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Manages per-version Godot Android runtime cores (libgodot_android.so + godot-lib.dex).
 * Fully compliant with Android 14+ (API 34) W^X SELinux rules.
 */
class CoreManager(private val context: Context) {

    private val coresDir = File(context.filesDir, "cores").apply { mkdirs() }

    /**
     * Default catalog of officially supported Godot Android runtime cores.
     */
    val builtInRegistry: List<CoreDescriptor> = listOf(
        CoreDescriptor(
            versionKey = "4.3",
            displayName = "Godot 4.3 Stable (Vulkan / Compatibility)",
            engineVersion = "4.3.0",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/4.3-stable/godot-lib.4.3.0.stable.template_release.arm64.zip",
            sizeBytes = 35_000_000L,
            isRecommended = true
        ),
        CoreDescriptor(
            versionKey = "4.4",
            displayName = "Godot 4.4 Dev/RC (PCK v3)",
            engineVersion = "4.4.0",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/4.4-stable/godot-lib.4.4.0.stable.template_release.arm64.zip",
            sizeBytes = 36_000_000L
        ),
        CoreDescriptor(
            versionKey = "4.2",
            displayName = "Godot 4.2 Stable",
            engineVersion = "4.2.2",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/4.2.2-stable/godot-lib.4.2.2.stable.template_release.arm64.zip",
            sizeBytes = 34_000_000L
        ),
        CoreDescriptor(
            versionKey = "4.1",
            displayName = "Godot 4.1 Stable",
            engineVersion = "4.1.3",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/4.1.3-stable/godot-lib.4.1.3.stable.template_release.arm64.zip",
            sizeBytes = 33_000_000L
        ),
        CoreDescriptor(
            versionKey = "4.0",
            displayName = "Godot 4.0 Stable",
            engineVersion = "4.0.4",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/4.0.4-stable/godot-lib.4.0.4.stable.template_release.arm64.zip",
            sizeBytes = 32_000_000L
        ),
        CoreDescriptor(
            versionKey = "3.5",
            displayName = "Godot 3.5.3 (GLES3 / GLES2 Classic)",
            engineVersion = "3.5.3",
            downloadUrl = "https://github.com/godotengine/godot/releases/download/3.5.3-stable/godot-lib.3.5.3.stable.release.arm64.zip",
            sizeBytes = 28_000_000L
        )
    )

    /**
     * Lists all locally installed and valid Godot cores.
     */
    fun getInstalledCores(): List<InstalledCore> {
        val list = mutableListOf<InstalledCore>()
        val directories = coresDir.listFiles { f -> f.isDirectory } ?: return emptyList()

        for (dir in directories) {
            val versionKey = dir.name
            val soFile = File(dir, "libgodot_android.so")
            val dexFile = File(dir, "godot-lib.dex")
            val manifestFile = File(dir, "core_manifest.json")

            if (soFile.exists() && dexFile.exists()) {
                var engineVersion = "$versionKey.0"
                var sourceType = "UNKNOWN"
                var installedTimestamp = soFile.lastModified()
                var sha256 = ""

                if (manifestFile.exists()) {
                    try {
                        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
                        engineVersion = json.optString("engineVersion", engineVersion)
                        sourceType = json.optString("sourceType", sourceType)
                        installedTimestamp = json.optLong("installedTimestamp", installedTimestamp)
                        sha256 = json.optString("sha256", sha256)
                    } catch (_: Exception) {
                    }
                }

                list.add(
                    InstalledCore(
                        versionKey = versionKey,
                        engineVersion = engineVersion,
                        coreDir = dir,
                        soFile = soFile,
                        dexFile = dexFile,
                        sourceType = sourceType,
                        installedTimestamp = installedTimestamp,
                        sha256 = sha256
                    )
                )
            }
        }

        return list.sortedByDescending { it.versionKey }
    }

    /**
     * Checks if a core for the specified version key is installed.
     */
    fun isCoreInstalled(versionKey: String): Boolean {
        return getCore(versionKey) != null
    }

    /**
     * Finds matching installed core for an engine version (e.g. major=4, minor=3 -> "4.3").
     */
    fun findMatchingCore(engineMajor: Int, engineMinor: Int): InstalledCore? {
        val targetKey = "$engineMajor.$engineMinor"
        val exactMatch = getCore(targetKey)
        if (exactMatch != null) return exactMatch

        // Fallback: match by major version (e.g., any 4.x core if compatible)
        val installed = getInstalledCores()
        return installed.firstOrNull { it.versionKey.startsWith("$engineMajor.") }
    }

    /**
     * Retrieves an installed core by its version key (e.g., "4.3").
     */
    fun getCore(versionKey: String): InstalledCore? {
        val dir = File(coresDir, versionKey)
        val soFile = File(dir, "libgodot_android.so")
        val dexFile = File(dir, "godot-lib.dex")
        if (dir.exists() && soFile.exists() && dexFile.exists()) {
            val manifestFile = File(dir, "core_manifest.json")
            var engineVersion = "$versionKey.0"
            var sourceType = "UNKNOWN"
            var installedTimestamp = soFile.lastModified()
            var sha256 = ""

            if (manifestFile.exists()) {
                try {
                    val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    engineVersion = json.optString("engineVersion", engineVersion)
                    sourceType = json.optString("sourceType", sourceType)
                    installedTimestamp = json.optLong("installedTimestamp", installedTimestamp)
                    sha256 = json.optString("sha256", sha256)
                } catch (_: Exception) {
                }
            }

            return InstalledCore(
                versionKey = versionKey,
                engineVersion = engineVersion,
                coreDir = dir,
                soFile = soFile,
                dexFile = dexFile,
                sourceType = sourceType,
                installedTimestamp = installedTimestamp,
                sha256 = sha256
            )
        }
        return null
    }

    /**
     * Imports Godot Android runtime core from any official Godot template zip/apk/tpz archive.
     * Extracts lib/arm64-v8a/libgodot_android.so and classes.dex into cores/{versionKey}.
     */
    suspend fun importCoreFromTemplate(
        inputStream: InputStream,
        versionKey: String,
        engineVersion: String = "$versionKey.0",
        sourceName: String = "local_template",
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<InstalledCore> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(DownloadProgress(0, 100, 5, "Scanning template archive..."))

            val targetDir = File(coresDir, versionKey).apply { mkdirs() }
            val tempDir = File(context.cacheDir, "core_import_$versionKey").apply { mkdirs() }

            val targetSo = File(targetDir, "libgodot_android.so")
            val targetDex = File(targetDir, "godot-lib.dex")
            val soFilesDir = File(tempDir, "so_files").apply { mkdirs() }
            val tempDex = File(tempDir, "temp_classes.dex")

            // Ensure writable before overwriting
            if (targetSo.exists()) targetSo.setWritable(true)
            if (targetDex.exists()) targetDex.setWritable(true)

            var foundSo = false
            var foundDex = false

            // Helper to extract an entry
            fun handleZipEntry(name: String, stream: InputStream) {
                if (isArm64So(name)) {
                    val fileName = name.substringAfterLast('/')
                    val destSo = File(soFilesDir, fileName)
                    if (destSo.exists()) destSo.setWritable(true)
                    FileOutputStream(destSo).use { out -> stream.copyTo(out) }
                    if (isArm64LibGodot(name)) {
                        foundSo = true
                    }
                } else if (isClassesDex(name) && !foundDex) {
                    if (tempDex.exists()) tempDex.setWritable(true)
                    FileOutputStream(tempDex).use { out -> stream.copyTo(out) }
                    foundDex = true
                }
            }

            // Read archive bytes or stream
            val zipIn = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipIn.nextEntry

            while (entry != null) {
                val name = entry.name

                if (name.endsWith(".tpz", ignoreCase = true) ||
                    name.endsWith("android_release.apk", ignoreCase = true) ||
                    name.endsWith("android_debug.apk", ignoreCase = true)
                ) {
                    // Nested archive in .tpz
                    onProgress?.invoke(DownloadProgress(30, 100, 30, "Extracting nested Android template: $name..."))
                    val nestedBytes = zipIn.readBytes()
                    val nestedZipIn = ZipInputStream(ByteArrayInputStream(nestedBytes))
                    var nestedEntry = nestedZipIn.nextEntry

                    while (nestedEntry != null) {
                        handleZipEntry(nestedEntry.name, nestedZipIn)
                        nestedZipIn.closeEntry()
                        nestedEntry = nestedZipIn.nextEntry
                    }
                } else {
                    handleZipEntry(name, zipIn)
                }

                zipIn.closeEntry()
                if (foundSo && foundDex) {
                    // Continue scanning to grab any other companion .so libraries if present
                }
                entry = zipIn.nextEntry
            }

            if (!foundSo) {
                return@withContext Result.failure(
                    IllegalStateException("Could not find lib/arm64-v8a/libgodot_android.so inside template archive.")
                )
            }
            if (!foundDex) {
                return@withContext Result.failure(
                    IllegalStateException("Could not find classes.dex inside template archive.")
                )
            }

            // Move extracted dex to target directory
            tempDex.copyTo(targetDex, overwrite = true)
            tempDex.delete()
            targetDex.setReadOnly()

            // Move all extracted .so files to target directory
            val extractedSos = soFilesDir.listFiles { f -> f.extension == "so" } ?: emptyArray()
            for (so in extractedSos) {
                val target = File(targetDir, so.name)
                if (target.exists()) target.setWritable(true)
                so.copyTo(target, overwrite = true)
                so.delete()
                target.setReadOnly()
            }
            soFilesDir.delete()
            tempDir.delete()

            // Compute SHA-256
            val sha256 = calculateSha256(targetSo)

            // Write Manifest
            val manifest = JSONObject().apply {
                put("versionKey", versionKey)
                put("engineVersion", engineVersion)
                put("sourceType", "LOCAL_TEMPLATE")
                put("sourceName", sourceName)
                put("installedTimestamp", System.currentTimeMillis())
                put("sha256", sha256)
            }
            val manifestFile = File(targetDir, "core_manifest.json")
            manifestFile.writeText(manifest.toString(2), Charsets.UTF_8)

            onProgress?.invoke(DownloadProgress(100, 100, 100, "Core installed successfully!"))

            val installedCore = InstalledCore(
                versionKey = versionKey,
                engineVersion = engineVersion,
                coreDir = targetDir,
                soFile = targetSo,
                dexFile = targetDex,
                sourceType = "LOCAL_TEMPLATE",
                installedTimestamp = System.currentTimeMillis(),
                sha256 = sha256
            )
            Result.success(installedCore)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import core $versionKey", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a Godot runtime core zip from remote registry and installs it.
     */
    suspend fun downloadCore(
        descriptor: CoreDescriptor,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<InstalledCore> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(DownloadProgress(0, 100, 0, "Connecting to ${descriptor.displayName}..."))

            val url = URL(descriptor.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val totalBytes = connection.contentLengthLong.let { if (it > 0) it else descriptor.sizeBytes }
            val input = connection.inputStream

            onProgress?.invoke(DownloadProgress(0, totalBytes, 10, "Downloading core archive..."))

            // Stream download and extract
            val result = importCoreFromTemplate(
                inputStream = input,
                versionKey = descriptor.versionKey,
                engineVersion = descriptor.engineVersion,
                sourceName = "REMOTE_REGISTRY",
                onProgress = onProgress
            )

            connection.disconnect()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download core ${descriptor.versionKey}", e)
            Result.failure(e)
        }
    }

    fun deleteCore(versionKey: String): Boolean {
        val dir = File(coresDir, versionKey)
        if (!dir.exists()) return false

        // Unlock read-only permissions on all extracted binaries before deletion
        dir.listFiles()?.forEach { file ->
            file.setWritable(true)
            file.delete()
        }
        return dir.delete()
    }

    private fun isArm64So(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".so") && (lower.contains("arm64") || lower.contains("aarch64"))
    }

    private fun isArm64LibGodot(path: String): Boolean {
        val lower = path.lowercase()
        return (lower.endsWith("libgodot_android.so") || lower.endsWith("libgodot_android.arm64.so")) &&
                (lower.contains("arm64") || lower.contains("aarch64"))
    }

    private fun isClassesDex(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith("classes.dex") || lower.endsWith("godot-lib.dex")
    }

    private fun calculateSha256(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "CoreManager"
    }
}
