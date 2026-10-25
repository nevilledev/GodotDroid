package com.godotdroid.pck.model

enum class PckFormatVersion(val versionNumber: Int, val description: String) {
    V1(1, "Godot 3.x (Format v1)"),
    V2(2, "Godot 4.0 – 4.3 (Format v2)"),
    V3(3, "Godot 4.4 – 4.5 (Format v3)"),
    V4(4, "Godot 4.6+ (Format v4)"),
    UNKNOWN(-1, "Unknown / Unsupported Format");

    companion object {
        fun fromInt(version: Int): PckFormatVersion {
            return entries.find { it.versionNumber == version } ?: UNKNOWN
        }
    }
}

enum class RendererType(val displayName: String, val isMobileOptimized: Boolean) {
    FORWARD_PLUS("Forward+ (Desktop / High-End Vulkan)", isMobileOptimized = false),
    MOBILE("Vulkan Mobile (Clustered Mobile)", isMobileOptimized = true),
    GL_COMPATIBILITY("OpenGL Compatibility (GLES3 / Broad Compatibility)", isMobileOptimized = true),
    GLES3("GLES3 (OpenGL ES 3.0 / High Quality)", isMobileOptimized = true),
    GLES2("GLES2 (OpenGL ES 2.0 / Legacy Compatibility)", isMobileOptimized = true),
    UNKNOWN("Default / Auto", isMobileOptimized = true)
}

data class PckHeader(
    val magic: Int,
    val formatVersion: Int,
    val engineMajor: Int,
    val engineMinor: Int,
    val enginePatch: Int,
    val packFlags: Int,
    val fileBase: Long,
    val effectiveFileBase: Long,
    val directoryOffset: Long,
    val isEncrypted: Boolean,
    val isEmbeddedExe: Boolean,
    val pckStartOffset: Long,
    val fileLength: Long
) {
    val format: PckFormatVersion get() = PckFormatVersion.fromInt(formatVersion)
    val engineVersionString: String get() = "$engineMajor.$engineMinor.$enginePatch"
    val summaryBadge: String get() = "Godot $engineVersionString (PCK v$formatVersion)"
}

data class PckFileEntry(
    val path: String,
    val fileOffset: Long,
    val size: Long,
    val md5: ByteArray,
    val flags: Int = 0,
    val rawPath: String = path
) {
    val isEncrypted: Boolean get() = (flags and 0x01) != 0

    val md5Hex: String get() = md5.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PckFileEntry
        return path == other.path &&
                fileOffset == other.fileOffset &&
                size == other.size &&
                flags == other.flags &&
                md5.contentEquals(other.md5) &&
                rawPath == other.rawPath
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + fileOffset.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + md5.contentHashCode()
        result = 31 * result + flags
        result = 31 * result + rawPath.hashCode()
        return result
    }
}

data class PluginDependency(
    val name: String,
    val configPath: String,
    val isGodot4Extension: Boolean,
    val desktopLibraries: Map<String, String>,
    val androidLibraries: Map<String, String>,
    val hasAndroidArm64: Boolean,
    val isGodotSteam: Boolean
)

data class GodotGameReport(
    val gameTitle: String,
    val mainScene: String?,
    val iconPath: String?,
    val pckHeader: PckHeader,
    val isEmbeddedExe: Boolean,
    val pckStartOffset: Long,
    val totalFilesCount: Int,
    val renderingMethod: String?,
    val detectedRenderer: RendererType,
    val usesDotNet: Boolean,
    val plugins: List<PluginDependency>,
    val missingAndroidPlugins: List<PluginDependency>,
    val hasGodotSteam: Boolean,
    val hasDesktopTextures: Boolean = false,
    val isEncrypted: Boolean,
    val candidateEncryptionKeys: List<String> = emptyList()
)
