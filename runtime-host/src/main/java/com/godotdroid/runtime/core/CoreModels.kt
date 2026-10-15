package com.godotdroid.runtime.core

import java.io.File

/**
 * Metadata for a downloadable Godot engine core available in the registry.
 */
data class CoreDescriptor(
    val versionKey: String, // e.g. "4.3", "4.2", "3.5"
    val displayName: String, // e.g. "Godot 4.3 Stable"
    val engineVersion: String, // e.g. "4.3.0"
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val isRecommended: Boolean = false
)

/**
 * Represents an installed, validated Godot runtime core on the device.
 */
data class InstalledCore(
    val versionKey: String,
    val engineVersion: String,
    val coreDir: File,
    val soFile: File,
    val dexFile: File,
    val sourceType: String, // "LOCAL_TEMPLATE" or "REMOTE_REGISTRY"
    val installedTimestamp: Long,
    val sha256: String
) {
    val isValid: Boolean get() = soFile.exists() && dexFile.exists() && soFile.length() > 0 && dexFile.length() > 0
}

/**
 * Progress updates for core downloading or extraction.
 */
data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percentage: Int,
    val currentStage: String
)
