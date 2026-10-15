package com.godotdroid.boot

import android.content.Context
import android.content.Intent
import com.godotdroid.exporter.model.StandaloneConfig
import com.godotdroid.overlay.repository.TouchLayoutRepository
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.activity.GodotRunnerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Handles automatic initialization and boot sequence when running inside an exported Standalone APK.
 */
object StandaloneBootManager {

    private const val CONFIG_ASSET_PATH = "standalone_config.json"
    private const val PCK_ASSET_PATH = "main.pck"
    private const val CONTROLS_ASSET_PATH = "controls.json"

    fun isStandaloneApk(context: Context): Boolean {
        return try {
            val list = context.assets.list("") ?: emptyArray()
            list.contains(CONFIG_ASSET_PATH)
        } catch (_: Exception) {
            false
        }
    }

    fun loadConfig(context: Context): StandaloneConfig? {
        return try {
            context.assets.open(CONFIG_ASSET_PATH).use { input ->
                val jsonStr = input.bufferedReader().readText()
                StandaloneConfig.fromJson(JSONObject(jsonStr))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts bundled core, game package, and touch controls from assets on first launch,
     * and constructs the launch Intent for GodotRunnerActivity.
     */
    suspend fun prepareAndCreateLaunchIntent(
        context: Context,
        onProgress: (String) -> Unit
    ): Intent = withContext(Dispatchers.IO) {
        onProgress("Reading standalone configuration...")
        val config = loadConfig(context)
            ?: throw IllegalStateException("Failed to load standalone configuration from APK assets.")

        val versionKey = config.engineVersionKey

        // 1. Stage Engine Core into filesDir/cores/{versionKey}
        onProgress("Staging Godot $versionKey runtime core...")
        val coresDir = File(context.filesDir, "cores").apply { mkdirs() }
        val targetCoreDir = File(coresDir, versionKey).apply { mkdirs() }

        extractCoreBinary(context, "core/libgodot_android.so", File(targetCoreDir, "libgodot_android.so"))
        extractCoreBinary(context, "core/godot-lib.dex", File(targetCoreDir, "godot-lib.dex"))
        extractCoreBinary(context, "core/libc++_shared.so", File(targetCoreDir, "libc++_shared.so"))

        // Write Core Manifest
        val manifestFile = File(targetCoreDir, "core_manifest.json")
        if (!manifestFile.exists()) {
            val manifest = JSONObject().apply {
                put("versionKey", versionKey)
                put("engineVersion", "$versionKey.0")
                put("sourceType", "BUNDLED_STANDALONE")
                put("sourceName", "apk_assets")
                put("installedTimestamp", System.currentTimeMillis())
                put("sha256", "standalone_bundled")
            }
            manifestFile.writeText(manifest.toString(2), Charsets.UTF_8)
            manifestFile.setReadOnly()
        }

        // 2. Stage Main PCK
        onProgress("Extracting game assets...")
        val standaloneDir = File(context.filesDir, "standalone").apply { mkdirs() }
        val stagedPck = File(standaloneDir, "main.pck")
        if (!stagedPck.exists() || stagedPck.length() == 0L) {
            if (stagedPck.exists()) stagedPck.setWritable(true)
            context.assets.open(PCK_ASSET_PATH).use { input ->
                FileOutputStream(stagedPck).use { output ->
                    input.copyTo(output)
                }
            }
            stagedPck.setReadOnly()
        }

        // 3. Stage Controls Layout
        onProgress("Configuring touch overlay...")
        try {
            val controlsAsset = context.assets.open(CONTROLS_ASSET_PATH).bufferedReader().readText()
            val layoutDir = File(context.filesDir, "control_layouts").apply { mkdirs() }
            val layoutFile = File(layoutDir, "layout_${config.gameId}.json")
            layoutFile.writeText(controlsAsset, Charsets.UTF_8)
        } catch (_: Exception) {
            // Optional if already default
        }

        // 4. Construct Launch Intent
        onProgress("Launching ${config.gameTitle}...")
        val renderer = try {
            RendererType.valueOf(config.rendererType)
        } catch (_: Exception) {
            RendererType.MOBILE
        }

        GodotRunnerActivity.createIntent(
            context = context,
            gameId = config.gameId,
            gameTitle = config.gameTitle,
            filePath = stagedPck.absolutePath,
            renderer = renderer,
            versionKey = versionKey,
            mainSceneOverride = null,
            injectSteamStub = config.injectSteamStub,
            controlPreset = config.controlPreset
        )
    }

    private fun extractCoreBinary(context: Context, assetPath: String, targetFile: File) {
        try {
            val stream = context.assets.open(assetPath)
            if (targetFile.exists()) {
                targetFile.setWritable(true)
            }
            stream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setReadOnly()
        } catch (_: Exception) {
            // Some binaries (e.g. libc++_shared.so) are optional
        }
    }
}
