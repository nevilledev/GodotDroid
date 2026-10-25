package com.godotdroid.pck.scanner

import com.godotdroid.pck.analyzer.ExtensionConfigParser
import com.godotdroid.pck.analyzer.GameCompatibilityAnalyzer
import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.GodotGameReport
import java.io.File

enum class CandidateSelectionReason {
    EMBEDDED_EXE,
    MATCHING_EXE_PCK,
    LARGEST_PCK
}

data class GameCandidate(
    val file: File,
    val relativePath: String,
    val sizeBytes: Long,
    val reason: CandidateSelectionReason,
    val matchingExeFile: File? = null
)

data class FolderScanResult(
    val rootDir: File,
    val candidates: List<GameCandidate>,
    val allPckFiles: List<File>,
    val allExeFiles: List<File>,
    val totalFileCount: Int,
    val totalSizeBytes: Long,
    val junkFilesExcluded: List<String>,
    val dllWarnings: List<String>
)

class GameFolderScanner(
    private val pckInspector: PckBinaryInspector = PckBinaryInspector(),
    private val compatibilityAnalyzer: GameCompatibilityAnalyzer = GameCompatibilityAnalyzer(pckInspector)
) {

    companion object {
        private val JUNK_PREFIXES = listOf(
            "vc_redist",
            "vcredist",
            "uninstall",
            "unins",
            "crashpad",
            "installer",
            "installers",
            "setup"
        )

        /**
         * Checks whether a file or directory name matches known junk / installer patterns.
         */
        fun isJunk(name: String): Boolean {
            val lower = name.lowercase().trim()
            return JUNK_PREFIXES.any { prefix -> lower.startsWith(prefix) }
        }
    }

    /**
     * Recursively scans rootDir, excludes junk files, collects statistics,
     * finds main game file candidates, and identifies DLL warnings.
     */
    fun scan(rootDir: File): FolderScanResult {
        val allPcks = mutableListOf<File>()
        val allExes = mutableListOf<File>()
        val allDlls = mutableListOf<File>()
        val allSos = mutableListOf<File>()
        val allPluginConfigs = mutableListOf<File>()
        val junkExcluded = mutableListOf<String>()

        var totalFiles = 0
        var totalBytes = 0L

        fun traverse(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val relPath = child.relativeTo(rootDir).path
                if (isJunk(child.name)) {
                    junkExcluded.add(relPath)
                    continue
                }

                if (child.isDirectory) {
                    traverse(child)
                } else if (child.isFile) {
                    totalFiles++
                    totalBytes += child.length()

                    val ext = child.extension.lowercase()
                    when (ext) {
                        "pck" -> allPcks.add(child)
                        "exe" -> allExes.add(child)
                        "dll" -> allDlls.add(child)
                        "so" -> allSos.add(child)
                        "gdextension", "gdnlib" -> allPluginConfigs.add(child)
                    }
                }
            }
        }

        traverse(rootDir)

        // 1. Identify Main Game File Candidates
        val candidates = findCandidates(rootDir, allExes, allPcks)

        // 2. Collect DLL Warnings (Windows-only .dll plugins without Android arm64 builds)
        val dllWarnings = collectDllWarnings(allPluginConfigs, allDlls, allSos)

        return FolderScanResult(
            rootDir = rootDir,
            candidates = candidates,
            allPckFiles = allPcks.sortedBy { it.relativeTo(rootDir).path },
            allExeFiles = allExes.sortedBy { it.relativeTo(rootDir).path },
            totalFileCount = totalFiles,
            totalSizeBytes = totalBytes,
            junkFilesExcluded = junkExcluded,
            dllWarnings = dllWarnings
        )
    }

    /**
     * Resolves game candidate(s) by applying the prioritization hierarchy:
     * 1. .exe with embedded PCK (GDPC magic via PckBinaryInspector)
     * 2. .pck matching .exe name
     * 3. largest .pck
     * If several candidates remain at any step, all are returned so the caller can prompt the user.
     */
    fun findCandidates(rootDir: File, allExes: List<File>, allPcks: List<File>): List<GameCandidate> {
        // Priority 1: Check for .exe with embedded PCK
        val embeddedExes = allExes.filter { isEmbeddedPckExe(it) }
        if (embeddedExes.isNotEmpty()) {
            return embeddedExes.map { exe ->
                GameCandidate(
                    file = exe,
                    relativePath = exe.relativeTo(rootDir).path,
                    sizeBytes = exe.length(),
                    reason = CandidateSelectionReason.EMBEDDED_EXE
                )
            }
        }

        // Priority 2: Check for .pck matching an .exe name
        val matchingPairs = mutableListOf<Pair<File, File>>() // (pck, exe)
        for (pck in allPcks) {
            val pckBase = pck.nameWithoutExtension.lowercase()
            // Prefer exe in the same directory first
            val sameDirExe = allExes.firstOrNull { exe ->
                exe.parentFile == pck.parentFile && exe.nameWithoutExtension.lowercase() == pckBase
            }
            val matchingExe = sameDirExe ?: allExes.firstOrNull { exe ->
                exe.nameWithoutExtension.lowercase() == pckBase
            }

            if (matchingExe != null) {
                matchingPairs.add(pck to matchingExe)
            }
        }

        if (matchingPairs.isNotEmpty()) {
            return matchingPairs.map { (pck, exe) ->
                GameCandidate(
                    file = pck,
                    relativePath = pck.relativeTo(rootDir).path,
                    sizeBytes = pck.length(),
                    reason = CandidateSelectionReason.MATCHING_EXE_PCK,
                    matchingExeFile = exe
                )
            }
        }

        // Priority 3: Fall back to largest .pck
        if (allPcks.isNotEmpty()) {
            val maxSize = allPcks.maxOf { it.length() }
            val largestPcks = allPcks.filter { it.length() == maxSize }
            return largestPcks.map { pck ->
                GameCandidate(
                    file = pck,
                    relativePath = pck.relativeTo(rootDir).path,
                    sizeBytes = pck.length(),
                    reason = CandidateSelectionReason.LARGEST_PCK
                )
            }
        }

        // No Godot game candidates found
        return emptyList()
    }

    /**
     * Checks if an .exe file contains an embedded Godot PCK.
     */
    fun isEmbeddedPckExe(exeFile: File): Boolean {
        return try {
            val header = pckInspector.inspectHeader(exeFile)
            header.isEmbeddedExe || header.magic == PckConstants.PCK_MAGIC
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Collects warnings for Windows-only .dll plugins / binaries without Android builds.
     */
    fun collectDllWarnings(
        pluginConfigFiles: List<File>,
        allDlls: List<File>,
        allSos: List<File>
    ): List<String> {
        val warnings = mutableListOf<String>()

        // Check plugin configs (.gdextension / .gdnlib)
        for (configFile in pluginConfigFiles) {
            try {
                val content = configFile.readText(Charsets.UTF_8)
                val plugin = ExtensionConfigParser.parse(configFile.name, content)
                if (plugin.desktopLibraries.isNotEmpty() && !plugin.hasAndroidArm64) {
                    warnings.add(
                        "Plugin '${plugin.name}' defines Windows libraries but lacks an Android ARM64 (.so) build."
                    )
                }
            } catch (_: Exception) {}
        }

        // Check loose .dll files that do not have an equivalent .so file
        for (dll in allDlls) {
            val dllBase = dll.nameWithoutExtension.lowercase()
            val hasMatchingSo = allSos.any { so ->
                val soName = so.nameWithoutExtension.lowercase().removePrefix("lib")
                soName.contains(dllBase) || dllBase.contains(soName)
            }
            if (!hasMatchingSo) {
                // If this dll wasn't already covered by a plugin warning, flag it
                val alreadyCovered = warnings.any { it.contains(dll.name, ignoreCase = true) }
                if (!alreadyCovered) {
                    warnings.add("Windows library '${dll.name}' has no corresponding Android (.so) binary.")
                }
            }
        }

        return warnings
    }

    /**
     * Analyzes a candidate game file to produce a complete compatibility report.
     */
    fun analyzeCandidate(candidate: GameCandidate): GodotGameReport {
        return compatibilityAnalyzer.analyze(candidate.file)
    }
}
