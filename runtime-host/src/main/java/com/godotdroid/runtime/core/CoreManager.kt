package com.godotdroid.runtime.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Manages per-version Godot Android runtime cores (libgodot_android.so + godot-lib.dex).
 * Fully compliant with Android 14+ (API 34) W^X SELinux rules.
 */
class CoreManager(private val context: Context) {

    private val coresDir = File(context.filesDir, "cores").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("godotdroid_core_prefs", Context.MODE_PRIVATE)
    private val cachedManifestFile = File(context.filesDir, "remote_cores_manifest.json")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val tpzRangeDownloader: OfficialTpzRangeDownloader by lazy {
        OfficialTpzRangeDownloader(httpClient)
    }

    private val _installedCores = MutableStateFlow<List<InstalledCore>>(getInstalledCores())
    val installedCores: StateFlow<List<InstalledCore>> = _installedCores.asStateFlow()

    fun refreshInstalledCores(): List<InstalledCore> {
        val list = getInstalledCores()
        _installedCores.value = list
        return list
    }

    private val _registryCatalog = MutableStateFlow<List<CoreDescriptor>>(loadInitialCatalog())
    val registryCatalog: StateFlow<List<CoreDescriptor>> = _registryCatalog.asStateFlow()

    /**
     * Gets the configured custom or default GitHub registry base URL, sanitized.
     */
    fun getRegistryBaseUrl(): String {
        val raw = prefs.getString(PREF_REGISTRY_BASE_URL, DEFAULT_REGISTRY_BASE_URL) ?: DEFAULT_REGISTRY_BASE_URL
        val sanitized = sanitizeRegistryBaseUrl(raw)
        if (sanitized != raw) {
            prefs.edit().putString(PREF_REGISTRY_BASE_URL, sanitized).apply()
        }
        return sanitized
    }

    /**
     * Updates the persistent custom GitHub registry base URL after sanitizing.
     */
    fun setRegistryBaseUrl(url: String) {
        val sanitized = sanitizeRegistryBaseUrl(url)
        prefs.edit().putString(PREF_REGISTRY_BASE_URL, sanitized).apply()
    }

    /**
     * Returns the active catalog of Godot runtime cores.
     */
    fun getRegistryCatalog(): List<CoreDescriptor> {
        return _registryCatalog.value
    }

    /**
     * Loads initial catalog: cached remote manifest merged with builtInRegistry if available, otherwise builtInRegistry.
     */
    private fun loadInitialCatalog(): List<CoreDescriptor> {
        val cached = loadCachedManifest()
        return if (cached.isNotEmpty()) {
            mergeWithBuiltInRegistry(cached)
        } else {
            builtInRegistry
        }
    }

    /**
     * Syncs remote manifest, updating the reactive StateFlow catalog.
     */
    suspend fun syncRemoteManifest(baseUrl: String = getRegistryBaseUrl()): Result<List<CoreDescriptor>> =
        fetchRemoteManifest(baseUrl)

    /**
     * Fetches the latest cores_manifest.json from the specified registry base URL.
     */
    suspend fun fetchRemoteManifest(baseUrl: String = getRegistryBaseUrl()): Result<List<CoreDescriptor>> = withContext(Dispatchers.IO) {
        val sanitizedBase = sanitizeRegistryBaseUrl(baseUrl)
        val manifestUrl = "$sanitizedBase/cores_manifest.json"
        Log.i(TAG, "Fetching remote cores manifest from $manifestUrl")

        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "GodotDroid/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val statusCode = response.code
            val finalUrl = response.request.url.toString()
            Log.i(TAG, "Manifest fetch HTTP status $statusCode, final URL: $finalUrl")

            if (!response.isSuccessful) {
                val err = "HTTP error $statusCode fetching manifest from $manifestUrl"
                Log.e(TAG, err)
                return@withContext Result.failure(IOException(err))
            }

            val body = response.body ?: throw IOException("Empty response body from $manifestUrl")
            val jsonText = body.string()

            val parsed = parseManifestJson(jsonText, sanitizedBase)
            if (parsed.isEmpty()) {
                val err = "No valid core entries found in manifest from $manifestUrl"
                Log.e(TAG, err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            try {
                cachedManifestFile.writeText(jsonText, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache manifest file to ${cachedManifestFile.absolutePath}", e)
            }

            val merged = mergeWithBuiltInRegistry(parsed)
            _registryCatalog.value = merged
            Log.i(TAG, "Manifest parsed: ${parsed.size} remote cores, total ${merged.size} in catalog")
            Result.success(merged)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch remote cores manifest from $manifestUrl", e)
            Result.failure(e)
        }
    }

    private fun loadCachedManifest(): List<CoreDescriptor> {
        if (!cachedManifestFile.exists()) return emptyList()
        return try {
            val text = cachedManifestFile.readText(Charsets.UTF_8)
            parseManifestJson(text, getRegistryBaseUrl())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached cores manifest", e)
            emptyList()
        }
    }

    /**
     * Parses manifest JSON supporting both top-level JSONArray and JSONObject {"cores": [...]}.
     */
    fun parseManifestJson(jsonText: String, baseUrl: String = getRegistryBaseUrl()): List<CoreDescriptor> {
        return Companion.parseManifestJson(jsonText, baseUrl)
    }

    /**
     * Default catalog of officially supported Godot Android runtime cores.
     */
    val builtInRegistry: List<CoreDescriptor> get() = Companion.builtInRegistry

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
        sourceType: String = "LOCAL_TEMPLATE",
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
                put("sourceType", sourceType)
                put("sourceName", sourceName)
                put("installedTimestamp", System.currentTimeMillis())
                put("sha256", sha256)
            }
            val manifestFile = File(targetDir, "core_manifest.json")
            manifestFile.writeText(manifest.toString(2), Charsets.UTF_8)

            onProgress?.invoke(DownloadProgress(100, 100, 100, "Core installed successfully!"))

            Log.i(TAG, "Install success: Godot $versionKey ($engineVersion, $sourceType) installed at ${targetDir.absolutePath}")

            val installedCore = InstalledCore(
                versionKey = versionKey,
                engineVersion = engineVersion,
                coreDir = targetDir,
                soFile = targetSo,
                dexFile = targetDex,
                sourceType = sourceType,
                installedTimestamp = System.currentTimeMillis(),
                sha256 = sha256
            )
            refreshInstalledCores()
            Result.success(installedCore)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import core $versionKey", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a Godot runtime core zip from remote registry and installs it.
     * Automatically falls back to Official Godot TPZ Range Downloader if custom zip is missing (HTTP 404)
     * or fails.
     */
    suspend fun downloadCore(
        descriptor: CoreDescriptor,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<InstalledCore> = withContext(Dispatchers.IO) {
        val fixedUrl = descriptor.downloadUrl.replace("nevilledroid", "nevilledev", ignoreCase = true)
        Log.i(TAG, "Get tapped: versionKey=${descriptor.versionKey}, url=$fixedUrl")

        // If the downloadUrl points directly to godotengine release assets that are not .tpz or if blank, jump directly to TPZ range downloader
        if (fixedUrl.isBlank() || (fixedUrl.contains("godotengine/godot/releases") && !fixedUrl.endsWith(".tpz"))) {
            Log.i(TAG, "Direct pre-built zip not hosted for ${descriptor.versionKey}. Using Official TPZ Range Downloader...")
            return@withContext downloadViaOfficialTpz(descriptor, onProgress)
        }

        onProgress?.invoke(DownloadProgress(0, 100, 0, "Connecting to ${descriptor.displayName}..."))
        val tempFile = File(context.cacheDir, "core_download_${descriptor.versionKey}_${System.currentTimeMillis()}.tmp")

        try {
            val request = Request.Builder()
                .url(fixedUrl)
                .header("User-Agent", "GodotDroid/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val statusCode = response.code
            val finalUrl = response.request.url.toString()
            Log.i(TAG, "HTTP status $statusCode, final URL: $finalUrl")

            if (!response.isSuccessful) {
                response.close()
                Log.w(TAG, "Download failed with HTTP $statusCode from $fixedUrl. Falling back to Official Godot TPZ...")
                return@withContext downloadViaOfficialTpz(descriptor, onProgress)
            }

            val body = response.body ?: throw IOException("Empty response body from $fixedUrl")
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else descriptor.sizeBytes

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    var bytesDownloaded = 0L
                    var lastReport = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 80 || bytesDownloaded == totalBytes) {
                            lastReport = now
                            val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 95) else 50
                            val mb = bytesDownloaded / (1024 * 1024)
                            val totalMb = totalBytes / (1024 * 1024)
                            onProgress?.invoke(DownloadProgress(bytesDownloaded, totalBytes, percent, "Downloading: $mb / $totalMb MB ($percent%)"))
                        }
                    }
                    output.flush()
                }
            }

            // Verify SHA-256
            onProgress?.invoke(DownloadProgress(totalBytes, totalBytes, 96, "Verifying SHA-256 checksum..."))
            val calculatedSha256 = calculateSha256(tempFile)
            if (descriptor.sha256.isNotBlank()) {
                if (!calculatedSha256.equals(descriptor.sha256.trim(), ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 fail: Expected ${descriptor.sha256}, Got $calculatedSha256")
                    tempFile.delete()
                    return@withContext Result.failure(
                        SecurityException("SHA-256 verification failed for ${descriptor.displayName}. Expected: ${descriptor.sha256}, calculated: $calculatedSha256")
                    )
                }
                Log.i(TAG, "SHA-256 pass: $calculatedSha256")
            } else {
                Log.i(TAG, "SHA-256 pass: (no checksum in manifest, computed $calculatedSha256)")
            }

            // Extract and install
            onProgress?.invoke(DownloadProgress(totalBytes, totalBytes, 98, "Installing Godot ${descriptor.versionKey} core..."))
            val installResult = FileInputStream(tempFile).use { stream ->
                importCoreFromTemplate(
                    inputStream = stream,
                    versionKey = descriptor.versionKey,
                    engineVersion = descriptor.engineVersion,
                    sourceName = fixedUrl,
                    sourceType = "REMOTE_REGISTRY",
                    onProgress = onProgress
                )
            }

            tempFile.delete()

            installResult.onSuccess { core ->
                Log.i(TAG, "Install success: Godot ${core.versionKey} (${core.engineVersion}) installed at ${core.coreDir.absolutePath}")
            }.onFailure { e ->
                Log.e(TAG, "Install failed for Godot ${descriptor.versionKey}", e)
            }

            installResult
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "Exception downloading core ${descriptor.versionKey} from $fixedUrl: ${e.message}. Falling back to Official Godot TPZ...")
            downloadViaOfficialTpz(descriptor, onProgress)
        }
    }

    /**
     * Downloads official Android template APK using HTTP Range requests from official Godot release mirrors
     * without downloading the full ~800 MB - 1 GB archive.
     */
    suspend fun downloadViaOfficialTpz(
        descriptor: CoreDescriptor,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<InstalledCore> = withContext(Dispatchers.IO) {
        val targetApk = File(context.cacheDir, "temp_official_template_${descriptor.versionKey}_${System.currentTimeMillis()}.apk")
        try {
            val tpzResult = tpzRangeDownloader.downloadForVersion(
                versionKey = descriptor.versionKey,
                engineVersion = descriptor.engineVersion,
                targetApkFile = targetApk,
                onProgress = onProgress
            )
            if (tpzResult.isFailure) {
                return@withContext Result.failure(
                    tpzResult.exceptionOrNull() ?: IOException("Failed to download official template for Godot ${descriptor.versionKey}")
                )
            }
            val apkFile = tpzResult.getOrThrow()
            onProgress?.invoke(DownloadProgress(95, 100, 95, "Extracting ARM64 engine binaries..."))
            val installResult = FileInputStream(apkFile).use { stream ->
                importCoreFromTemplate(
                    inputStream = stream,
                    versionKey = descriptor.versionKey,
                    engineVersion = descriptor.engineVersion,
                    sourceName = "Official Godot TPZ (${descriptor.versionKey})",
                    sourceType = "OFFICIAL_TPZ_RANGE",
                    onProgress = onProgress
                )
            }
            apkFile.delete()
            installResult
        } catch (e: Exception) {
            if (targetApk.exists()) targetApk.delete()
            Log.e(TAG, "Failed downloading via official TPZ for ${descriptor.versionKey}", e)
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
        val deleted = dir.delete()
        if (deleted) {
            refreshInstalledCores()
        }
        return deleted
    }

    private fun isArm64So(path: String): Boolean {
        val lower = path.lowercase()
        if (!lower.endsWith(".so")) return false
        if (lower.contains('/')) {
            return lower.contains("arm64") || lower.contains("aarch64")
        }
        return true
    }

    private fun isArm64LibGodot(path: String): Boolean {
        val lower = path.lowercase()
        val fileName = lower.substringAfterLast('/')
        val isGodot = fileName == "libgodot_android.so" ||
                fileName == "libgodot_android.arm64.so" ||
                fileName.startsWith("libgodot_android")
        if (!isGodot) return false
        if (lower.contains('/')) {
            return lower.contains("arm64") || lower.contains("aarch64")
        }
        return true
    }

    private fun isClassesDex(path: String): Boolean {
        val lower = path.lowercase()
        val fileName = lower.substringAfterLast('/')
        return fileName == "classes.dex" || fileName == "godot-lib.dex"
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate SHA-256", e)
            ""
        }
    }

    companion object {
        private const val TAG = "CoreManager"
        private const val PREF_REGISTRY_BASE_URL = "custom_registry_base_url"
        const val DEFAULT_REGISTRY_BASE_URL = "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest"

        /**
         * Default catalog of officially supported Godot Android runtime cores.
         */
        val builtInRegistry: List<CoreDescriptor> = listOf(
            // Godot 4.x Cores
            CoreDescriptor(
                versionKey = "4.7",
                displayName = "Godot 4.7 Dev (PCK v4)",
                engineVersion = "4.7.0",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.7.zip",
                sizeBytes = 40_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.6",
                displayName = "Godot 4.6 Dev (PCK v4)",
                engineVersion = "4.6.0",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.6.zip",
                sizeBytes = 38_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.5",
                displayName = "Godot 4.5 Stable (PCK v3)",
                engineVersion = "4.5.0",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.5.zip",
                sizeBytes = 37_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.4",
                displayName = "Godot 4.4.1 Stable (PCK v3)",
                engineVersion = "4.4.1",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.4.zip",
                sizeBytes = 36_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.3",
                displayName = "Godot 4.3 Stable (Vulkan / Compatibility)",
                engineVersion = "4.3.0",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.3.zip",
                sizeBytes = 35_000_000L,
                isRecommended = true
            ),
            CoreDescriptor(
                versionKey = "4.2",
                displayName = "Godot 4.2.2 Stable",
                engineVersion = "4.2.2",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.2.zip",
                sizeBytes = 34_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.1",
                displayName = "Godot 4.1.4 Stable",
                engineVersion = "4.1.4",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.1.zip",
                sizeBytes = 33_000_000L
            ),
            CoreDescriptor(
                versionKey = "4.0",
                displayName = "Godot 4.0.4 Stable",
                engineVersion = "4.0.4",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_4.0.zip",
                sizeBytes = 32_000_000L
            ),

            // Godot 3.x Cores
            CoreDescriptor(
                versionKey = "3.6",
                displayName = "Godot 3.6 Stable (GLES3 / GLES2)",
                engineVersion = "3.6.0",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_3.6.zip",
                sizeBytes = 30_000_000L,
                isRecommended = true
            ),
            CoreDescriptor(
                versionKey = "3.5",
                displayName = "Godot 3.5.3 (GLES3 / GLES2 Classic)",
                engineVersion = "3.5.3",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_3.5.zip",
                sizeBytes = 28_000_000L
            ),
            CoreDescriptor(
                versionKey = "3.4",
                displayName = "Godot 3.4.5 Stable",
                engineVersion = "3.4.5",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_3.4.zip",
                sizeBytes = 27_000_000L
            ),
            CoreDescriptor(
                versionKey = "3.3",
                displayName = "Godot 3.3.4 Stable",
                engineVersion = "3.3.4",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_3.3.zip",
                sizeBytes = 26_000_000L
            ),
            CoreDescriptor(
                versionKey = "3.2",
                displayName = "Godot 3.2.3 Stable",
                engineVersion = "3.2.3",
                downloadUrl = "$DEFAULT_REGISTRY_BASE_URL/core_3.2.zip",
                sizeBytes = 25_000_000L
            )
        )

        /**
         * Compares two engine version keys in descending order (e.g. 4.7 > 4.5 > 4.3 > 3.6).
         */
        fun compareVersionsDescending(v1: String, v2: String): Int {
            val p1 = v1.split('.').mapNotNull { it.toIntOrNull() }
            val p2 = v2.split('.').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(p1.size, p2.size)) {
                val num1 = p1.getOrElse(i) { 0 }
                val num2 = p2.getOrElse(i) { 0 }
                if (num1 != num2) {
                    return num2.compareTo(num1)
                }
            }
            return v2.compareTo(v1)
        }

        /**
         * Merges remote catalog with builtInRegistry, ensuring no supported version is ever hidden
         * even if not yet published to the remote registry.
         */
        fun mergeWithBuiltInRegistry(remote: List<CoreDescriptor>): List<CoreDescriptor> {
            val remoteMap = remote.associateBy { it.versionKey }
            val merged = mutableListOf<CoreDescriptor>()
            merged.addAll(remote)
            for (builtIn in builtInRegistry) {
                if (!remoteMap.containsKey(builtIn.versionKey)) {
                    merged.add(builtIn)
                }
            }
            return merged.sortedWith { a, b ->
                compareVersionsDescending(a.versionKey, b.versionKey)
            }
        }

        /**
         * Sanitizes registry base URL: trims whitespace, replaces typo 'nevilledroid' with 'nevilledev',
         * removes trailing slashes, and strips any trailing '/cores_manifest.json' or '.zip' filename.
         */
        fun sanitizeRegistryBaseUrl(url: String): String {
            var sanitized = url.trim()
                .replace("nevilledroid", "nevilledev", ignoreCase = true)
                .trimEnd('/')

            if (sanitized.endsWith("/cores_manifest.json", ignoreCase = true)) {
                sanitized = sanitized.substring(0, sanitized.length - "/cores_manifest.json".length)
            } else if (sanitized.endsWith("cores_manifest.json", ignoreCase = true)) {
                sanitized = sanitized.substring(0, sanitized.length - "cores_manifest.json".length)
            }

            val zipRegex = Regex("/[^/]+\\.zip$", RegexOption.IGNORE_CASE)
            sanitized = sanitized.replace(zipRegex, "")
            sanitized = sanitized.trimEnd('/')

            return if (sanitized.isBlank()) DEFAULT_REGISTRY_BASE_URL else sanitized
        }

        /**
         * Parses manifest JSON supporting both top-level JSONArray and JSONObject {"cores": [...]}.
         */
        fun parseManifestJson(jsonText: String, baseUrl: String = DEFAULT_REGISTRY_BASE_URL): List<CoreDescriptor> {
            return try {
                val sanitizedBaseUrl = sanitizeRegistryBaseUrl(baseUrl)
                val trimmed = jsonText.trim()
                val array: JSONArray = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        obj.optJSONArray("cores") ?: obj.optJSONArray("manifest") ?: JSONArray()
                    }
                    else -> {
                        Log.e(TAG, "Manifest JSON format unrecognized: ${trimmed.take(60)}")
                        return emptyList()
                    }
                }

                val list = mutableListOf<CoreDescriptor>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val versionKey = obj.optString("versionKey", "")
                    if (versionKey.isBlank()) continue

                    val displayName = obj.optString("displayName", "Godot $versionKey")
                    val engineVersion = obj.optString("engineVersion", "$versionKey.0")
                    val rawDownloadUrl = obj.optString("downloadUrl", "")
                    val fileName = obj.optString("fileName", "core_$versionKey.zip")
                    val sizeBytes = obj.optLong("sizeBytes", 0L)
                    val isRecommended = obj.optBoolean("isRecommended", false)
                    val sha256 = obj.optString("sha256", "")

                    // Fix downloadUrl: replace typo nevilledroid with nevilledev or construct from $sanitizedBaseUrl/$fileName
                    val fixedDownloadUrl = when {
                        rawDownloadUrl.isNotBlank() -> rawDownloadUrl.replace("nevilledroid", "nevilledev", ignoreCase = true)
                        else -> "$sanitizedBaseUrl/$fileName"
                    }

                    list.add(
                        CoreDescriptor(
                            versionKey = versionKey,
                            displayName = displayName,
                            engineVersion = engineVersion,
                            downloadUrl = fixedDownloadUrl,
                            sizeBytes = sizeBytes,
                            sha256 = sha256,
                            isRecommended = isRecommended
                        )
                    )
                }
                list
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse cores manifest JSON", e)
                emptyList()
            }
        }
    }
}
