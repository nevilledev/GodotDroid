package com.godotdroid.exporter

import android.content.Context
import com.godotdroid.exporter.builder.ZipAlignedApkBuilder
import com.godotdroid.exporter.model.StandaloneConfig
import com.godotdroid.exporter.signer.ApkSignerEngine
import com.godotdroid.overlay.model.TouchControlLayout
import com.godotdroid.pck.model.RendererType
import com.godotdroid.pck.patcher.PckVirtualPatcher
import com.godotdroid.runtime.patcher.RuntimePatchInjector
import com.godotdroid.runtime.patcher.SteamStubGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExportRequest(
    val gameId: String,
    val appTitle: String,
    val packageName: String,
    val sourceGameFile: File,
    val baseApkFile: File,
    val outputSignedApk: File,
    val engineVersionKey: String,
    val coreDir: File,
    val selectedRenderer: RendererType,
    val controlLayout: TouchControlLayout,
    val injectSteamStub: Boolean,
    val customIconPng: ByteArray? = null,
    val extraCliArgs: List<String> = emptyList()
)

/**
 * End-to-end standalone APK export engine.
 * Coordinates PCK virtual patching, AndroidManifest string pool rewriting,
 * 4-byte aligned ZIP packaging, and V1/V2/V3 APK signing.
 */
class StandaloneApkExporter(
    private val context: Context,
    private val pckPatcher: PckVirtualPatcher = PckVirtualPatcher(),
    private val apkBuilder: ZipAlignedApkBuilder = ZipAlignedApkBuilder(),
    private val signerEngine: ApkSignerEngine = ApkSignerEngine()
) {

    suspend fun exportApk(
        request: ExportRequest,
        onProgress: ((step: String, percent: Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val stagingDir = File(context.cacheDir, "apk_export_${System.currentTimeMillis()}").apply { mkdirs() }
            val patchedPck = File(stagingDir, "main.pck")
            val unsignedApk = File(stagingDir, "unsigned.apk")

            try {
                // 1. Prepare override.cfg and GodotSteam stub
                onProgress?.invoke("Patching game package with runtime configuration...", 0.10f)
                val patchInjector = RuntimePatchInjector(context)
                val overrideCfg = patchInjector.generateOverrideCfg(
                    renderer = request.selectedRenderer,
                    mainSceneOverride = null,
                    injectSteamStub = request.injectSteamStub
                )

                val engineMajor = request.engineVersionKey.substringBefore('.').toIntOrNull() ?: 4
                val filesToInject = mutableMapOf<String, ByteArray>()
                filesToInject["res://override.cfg"] = overrideCfg.toByteArray(Charsets.UTF_8)

                if (request.injectSteamStub) {
                    val steamCode = SteamStubGenerator.generateStub(engineMajor)
                    filesToInject["res://godotdroid_steam_stub.gd"] = steamCode.toByteArray(Charsets.UTF_8)
                }

                // Inject into standalone PCK
                pckPatcher.patchPck(request.sourceGameFile, patchedPck, filesToInject)
                onProgress?.invoke("Game package virtual patch complete.", 0.25f)

                // 2. Build StandaloneConfig
                val standaloneConfig = StandaloneConfig(
                    gameId = request.gameId,
                    gameTitle = request.appTitle,
                    engineVersionKey = request.engineVersionKey,
                    rendererType = request.selectedRenderer.name,
                    controlPreset = request.controlLayout.presetName,
                    injectSteamStub = request.injectSteamStub,
                    customCliFlags = request.extraCliArgs
                )

                // 3. Build Unsigned 4-byte Aligned APK
                onProgress?.invoke("Rewriting manifest and packaging APK assets...", 0.35f)
                apkBuilder.buildUnsignedApk(
                    baseApkFile = request.baseApkFile,
                    outputUnsignedApk = unsignedApk,
                    customPackageName = request.packageName,
                    customGameTitle = request.appTitle,
                    standaloneConfig = standaloneConfig,
                    patchedPckFile = patchedPck,
                    controlsLayoutJson = request.controlLayout.toJson().toString(2),
                    coreDir = request.coreDir,
                    customIconPng = request.customIconPng,
                    onProgress = { step, progress ->
                        // Map internal builder progress (0.1..0.9) to overall (0.35..0.75)
                        val scaled = 0.35f + progress * 0.40f
                        onProgress?.invoke(step, scaled)
                    }
                )

                // 4. Sign APK with V1/V2/V3 schemes
                onProgress?.invoke("Generating keystore and signing APK (V1/V2/V3)...", 0.80f)
                val keystoreFile = File(context.filesDir, "keystore/godotdroid_export.p12")
                val credentials = signerEngine.getOrCreateSigningKey(keystoreFile)

                val signed = signerEngine.signApk(
                    unsignedApk = unsignedApk,
                    signedApk = request.outputSignedApk,
                    credentials = credentials,
                    minSdkVersion = 26
                )

                if (!signed) {
                    return@withContext Result.failure(IllegalStateException("ApkSigner failed to generate signed APK."))
                }

                // 5. Verify Signature
                onProgress?.invoke("Verifying APK integrity...", 0.95f)
                val isVerified = signerEngine.verifyApk(request.outputSignedApk)
                if (!isVerified) {
                    return@withContext Result.failure(IllegalStateException("Cryptographic verification failed for exported APK."))
                }

                onProgress?.invoke("Export completed successfully!", 1.0f)
                Result.success(request.outputSignedApk)
            } finally {
                // Clean up staging directory
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
