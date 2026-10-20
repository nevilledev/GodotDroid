package com.godotdroid.pck.analyzer

import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.inspector.PckInspectResult
import com.godotdroid.pck.model.GodotGameReport
import com.godotdroid.pck.model.PluginDependency
import com.godotdroid.pck.model.RendererType
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Analyzes Godot game packages to construct a comprehensive compatibility and metadata report.
 */
class GameCompatibilityAnalyzer(
    private val inspector: PckBinaryInspector = PckBinaryInspector()
) {

    /**
     * Analyzes a local PCK or EXE file.
     */
    fun analyze(file: File): GodotGameReport {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val inspectResult = inspector.inspect(channel, channel.size())
            val fallbackTitle = file.nameWithoutExtension
            val report = analyze(channel, inspectResult, fallbackTitle)

            val parentDir = file.parentFile
            val hasExternalDotNet = detectExternalDotNetFolder(parentDir, file.nameWithoutExtension)
            return if (hasExternalDotNet) {
                report.copy(usesDotNet = true)
            } else {
                report
            }
        }
    }

    companion object {
        /**
         * Detects desktop Mono / .NET assemblies located in sibling directories next to the executable,
         * such as `data_<name>/`, `<name>_Data/`, `mono/`, or loose `.dll` assemblies.
         */
        fun detectExternalDotNetFolder(parentDir: File?, baseName: String): Boolean {
            if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) return false

            val siblingFiles = parentDir.listFiles() ?: return false
            for (sibling in siblingFiles) {
                val sName = sibling.name.lowercase()
                if (sibling.isFile) {
                    if (sName == "godotsharp.dll" || sName == "godotsharpeditor.dll" || sName == "mscorlib.dll") {
                        return true
                    }
                } else if (sibling.isDirectory) {
                    val isDataFolder = sName.startsWith("data_") ||
                            sName.endsWith("_data") ||
                            sName == "mono" ||
                            sName == ".mono" ||
                            sName == "assemblies"
                    if (isDataFolder && containsDotNetAssemblies(sibling)) {
                        return true
                    }
                }
            }
            return false
        }

        private fun containsDotNetAssemblies(dir: File, depth: Int = 0): Boolean {
            if (depth > 3) return false
            val children = dir.listFiles() ?: return false
            for (child in children) {
                val cName = child.name.lowercase()
                if (child.isFile) {
                    if (cName.endsWith(".dll") || cName.endsWith(".pdb")) {
                        return true
                    }
                } else if (child.isDirectory) {
                    if (cName == "mono" || cName == "assemblies" || cName == "managed") {
                        return true
                    }
                    if (containsDotNetAssemblies(child, depth + 1)) {
                        return true
                    }
                }
            }
            return false
        }
    }

    /**
     * Analyzes an already inspected package using an open FileChannel.
     */
    fun analyze(
        channel: FileChannel,
        inspectResult: PckInspectResult,
        fallbackTitle: String = "Godot Game"
    ): GodotGameReport {
        val header = inspectResult.header
        val files = inspectResult.files

        if (inspectResult.isEncrypted) {
            val candidateKeys = if (header.isEmbeddedExe) {
                try {
                    com.godotdroid.pck.inspector.PeEncryptionKeyScanner.scanForCandidateKeys(channel, channel.size())
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            return GodotGameReport(
                gameTitle = fallbackTitle,
                mainScene = null,
                iconPath = null,
                pckHeader = header,
                isEmbeddedExe = header.isEmbeddedExe,
                pckStartOffset = header.pckStartOffset,
                totalFilesCount = 0,
                renderingMethod = null,
                detectedRenderer = RendererType.UNKNOWN,
                usesDotNet = false,
                plugins = emptyList(),
                missingAndroidPlugins = emptyList(),
                hasGodotSteam = false,
                isEncrypted = true,
                candidateEncryptionKeys = candidateKeys
            )
        }

        // 1. Project Settings Extraction
        val projectBinaryEntry = files.find { it.path == "res://project.binary" }
        val projectGodotEntry = files.find { it.path == "res://project.godot" }

        var parsedConfig = ParsedProjectConfig(
            gameTitle = null,
            mainScene = null,
            iconPath = null,
            renderingMethod = null,
            rendererType = RendererType.UNKNOWN
        )

        val targetConfigEntry = projectBinaryEntry ?: projectGodotEntry
        if (targetConfigEntry != null) {
            try {
                val configBytes = inspector.extractFileBytes(channel, targetConfigEntry)
                parsedConfig = ProjectConfigParser.parse(configBytes)
            } catch (_: Exception) {
                // If binary parse failed, try text entry if available
                if (projectBinaryEntry != null && projectGodotEntry != null) {
                    try {
                        val textBytes = inspector.extractFileBytes(channel, projectGodotEntry)
                        parsedConfig = ProjectConfigParser.parse(textBytes)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val gameTitle = parsedConfig.gameTitle?.ifBlank { null } ?: fallbackTitle
        val mainScene = parsedConfig.mainScene
        val iconPath = parsedConfig.iconPath
        val renderingMethod = parsedConfig.renderingMethod
        val detectedRenderer = parsedConfig.rendererType

        // 2. Plugin & Dependency Scanner (.gdextension and .gdnlib)
        val pluginEntries = files.filter { entry ->
            entry.path.endsWith(".gdextension", ignoreCase = true) ||
                    entry.path.endsWith(".gdnlib", ignoreCase = true)
        }

        val plugins = mutableListOf<PluginDependency>()
        val missingAndroidPlugins = mutableListOf<PluginDependency>()
        var hasGodotSteam = false

        for (pluginEntry in pluginEntries) {
            try {
                val bytes = inspector.extractFileBytes(channel, pluginEntry)
                val text = String(bytes, Charsets.UTF_8)
                val plugin = ExtensionConfigParser.parse(pluginEntry.path, text)
                plugins.add(plugin)

                if (plugin.isGodotSteam) {
                    hasGodotSteam = true
                }

                // If desktop libraries exist but Android arm64 is missing, flag it
                if (plugin.desktopLibraries.isNotEmpty() && !plugin.hasAndroidArm64) {
                    missingAndroidPlugins.add(plugin)
                }
            } catch (_: Exception) {
            }
        }

        // Also check if any standalone Steam libraries exist anywhere in the package
        if (!hasGodotSteam) {
            hasGodotSteam = files.any { entry ->
                val p = entry.path.lowercase()
                p.contains("godotsteam") || p.contains("steam_api") || p.contains("libgodotsteam")
            }
        }

        // 3. C# / Mono (.NET) Detection
        val usesDotNet = files.any { entry ->
            val p = entry.path.lowercase()
            p.contains(".godot/mono/") ||
                    p.contains(".mono/") ||
                    (p.endsWith(".dll") && !p.contains("godotsteam") && !p.contains("steam_api"))
        }

        return GodotGameReport(
            gameTitle = gameTitle,
            mainScene = mainScene,
            iconPath = iconPath,
            pckHeader = header,
            isEmbeddedExe = header.isEmbeddedExe,
            pckStartOffset = header.pckStartOffset,
            totalFilesCount = files.size,
            renderingMethod = renderingMethod,
            detectedRenderer = detectedRenderer,
            usesDotNet = usesDotNet,
            plugins = plugins,
            missingAndroidPlugins = missingAndroidPlugins,
            hasGodotSteam = hasGodotSteam,
            isEncrypted = false
        )
    }
}
