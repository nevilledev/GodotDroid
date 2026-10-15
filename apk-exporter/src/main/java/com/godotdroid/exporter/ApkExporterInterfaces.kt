package com.godotdroid.exporter

import com.godotdroid.pck.model.PluginDependency
import com.godotdroid.pck.model.RendererType
import java.io.File

data class ExportConfig(
    val packageId: String,
    val appName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val iconFile: File? = null,
    val targetAbi: String = "arm64-v8a",
    val godotVersion: String,
    val pckFile: File,
    val plugins: List<PluginDependency> = emptyList(),
    val defaultRenderer: RendererType = RendererType.MOBILE,
    val embedTouchControls: Boolean = true
)

sealed class ExportResult {
    data class Success(val outputApkFile: File, val totalSizeBytes: Long) : ExportResult()
    data class Failure(val errorMessage: String, val errorDetails: Throwable? = null) : ExportResult()
}

fun interface ExportProgressCallback {
    fun onProgress(percentage: Int, currentStage: String)
}

/**
 * Interface for standalone APK export packaging.
 */
interface IApkExporter {
    suspend fun exportApk(
        config: ExportConfig,
        outputDestination: File,
        progressCallback: ExportProgressCallback? = null
    ): ExportResult
}
