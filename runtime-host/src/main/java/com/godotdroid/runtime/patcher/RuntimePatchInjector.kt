package com.godotdroid.runtime.patcher

import android.content.Context
import android.os.Environment
import android.util.Log
import com.godotdroid.pck.model.PckFileEntry
import com.godotdroid.pck.model.PluginDependency
import com.godotdroid.pck.model.RendererType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RuntimeEnvironment(
    val gameId: String,
    val runtimeDir: File,
    val overrideCfgFile: File,
    val jniLibsDir: File,
    val cliArgs: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RuntimeEnvironment
        return gameId == other.gameId &&
                runtimeDir == other.runtimeDir &&
                overrideCfgFile == other.overrideCfgFile &&
                jniLibsDir == other.jniLibsDir &&
                cliArgs.contentEquals(other.cliArgs)
    }

    override fun hashCode(): Int {
        var result = gameId.hashCode()
        result = 31 * result + runtimeDir.hashCode()
        result = 31 * result + overrideCfgFile.hashCode()
        result = 31 * result + jniLibsDir.hashCode()
        result = 31 * result + cliArgs.contentHashCode()
        return result
    }
}

/**
 * Generates runtime patches (override.cfg, CLI arguments, and native library staging)
 * for running PC Godot games seamlessly on Android.
 */
class RuntimePatchInjector(private val context: Context) {

    private val runtimeBaseDir by lazy {
        val base = context.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "godotdroid_runtime_env")
        File(base, "runtime_env").apply { mkdirs() }
    }

    /**
     * Prepares isolated working directory, generates override.cfg, stages plugins, and builds CLI args.
     */
    fun prepareRuntimeEnvironment(
        gameId: String,
        pckOrExeFile: File,
        selectedRenderer: RendererType,
        mainSceneOverride: String? = null,
        plugins: List<PluginDependency> = emptyList(),
        injectSteamStub: Boolean = false,
        engineMajor: Int = 4,
        engineMinor: Int = 3,
        audioDriver: String? = null,
        extraCliArgs: List<String> = emptyList()
    ): RuntimeEnvironment {
        val effectiveEngineMajor = engineMajor
        val effectiveEngineMinor = if (engineMajor == 4 && engineMinor >= 6) 5 else engineMinor

        val gameDir = File(runtimeBaseDir, gameId).apply { mkdirs() }
        val jniLibsDir = File(gameDir, "jniLibs").apply { mkdirs() }
        val stagedPck = File(gameDir, "runtime_staged.pck")
        var workingPckFile = pckOrExeFile

        // Step 1: Detect Windows PE .exe ('MZ') and slice the embedded Godot PCK directly into runtime_staged.pck
        val isPeExe = try {
            java.io.RandomAccessFile(pckOrExeFile, "r").use { raf ->
                if (raf.length() >= 2) {
                    val b0 = raf.read()
                    val b1 = raf.read()
                    b0 == 0x4D && b1 == 0x5A
                } else false
            }
        } catch (_: Exception) { false }

        if (isPeExe) {
            if (stagedPck.exists()) stagedPck.setWritable(true)
            var sliced = false
            try {
                java.io.RandomAccessFile(pckOrExeFile, "r").use { srcRaf ->
                    val srcChannel = srcRaf.channel
                    val fileSize = srcChannel.size()
                    if (fileSize >= 12) {
                        val trailerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                        srcChannel.position(fileSize - 12)
                        srcChannel.read(trailerBuf)
                        trailerBuf.flip()
                        val pckSize = trailerBuf.getLong()
                        val magic = trailerBuf.getInt()
                        if (magic == com.godotdroid.pck.inspector.PckConstants.PCK_MAGIC && pckSize in 1 until fileSize) {
                            val embeddedStart = fileSize - 12L - pckSize
                            java.io.RandomAccessFile(stagedPck, "rw").use { dstRaf ->
                                val dstChannel = dstRaf.channel
                                dstChannel.truncate(0)
                                var transferred = 0L
                                while (transferred < pckSize) {
                                    val count = srcChannel.transferTo(
                                        embeddedStart + transferred,
                                        pckSize - transferred,
                                        dstChannel
                                    )
                                    if (count <= 0) break
                                    transferred += count
                                }
                                dstChannel.force(true)
                            }
                            sliced = true
                        } else {
                            Log.w(TAG, "PE trailer magic mismatch: 0x${Integer.toHexString(magic)}, pckSize: $pckSize")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct PE trailer slicing error: ${e.message}")
            }

            // Fallback: Use PckBinaryInspector extraction if trailer slicing did not succeed
            if (!sliced || stagedPck.length() == 0L) {
                try {
                    val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
                    val peHeader = inspector.inspectHeader(pckOrExeFile)
                    if (peHeader.isEmbeddedExe) {
                        inspector.extractStandalonePck(pckOrExeFile, stagedPck, peHeader)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback inspector extraction failed: ${e.message}")
                }
            }

            // Verify runtime_staged.pck starts with GDPC (0x43504447) at byte offset 0
            val isPckValid = try {
                if (stagedPck.exists() && stagedPck.length() >= 4) {
                    java.io.RandomAccessFile(stagedPck, "r").use { raf ->
                        val magicBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        raf.channel.read(magicBuf)
                        magicBuf.flip()
                        magicBuf.getInt() == com.godotdroid.pck.inspector.PckConstants.PCK_MAGIC
                    }
                } else false
            } catch (_: Exception) { false }

            if (isPckValid) {
                stagedPck.setReadable(true, false)
                workingPckFile = stagedPck
                Log.i(TAG, "Successfully sliced and verified standalone PCK (${stagedPck.length()} bytes) starting with GDPC at offset 0")
                try {
                    val aliasFile = File(gameDir, pckOrExeFile.name)
                    if (!aliasFile.exists()) {
                        android.system.Os.link(stagedPck.absolutePath, aliasFile.absolutePath)
                    }
                } catch (_: Throwable) {}
            } else {
                Log.e(TAG, "CRITICAL: runtime_staged.pck validation failed (does not start with GDPC magic)")
            }
        }

        // 1. Generate GodotSteam stub if requested
        if (injectSteamStub) {
            SteamStubGenerator.writeStubToDirectory(gameDir, effectiveEngineMajor)
        }

        // 2. Stage companion sibling files (.pck, .dll, .so) from the game package's parent folder
        val siblingPcks = mutableListOf<String>()
        val parentDir = pckOrExeFile.parentFile
        val companionExtensions = setOf("pck", "zip", "dll", "so", "dylib")

        if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
            var siblingFiles = parentDir.listFiles() ?: emptyArray()
            val existingCompanions = siblingFiles.filter {
                it.isFile && !it.name.equals(pckOrExeFile.name, ignoreCase = true) && it.extension.lowercase() in companionExtensions
            }

            // If no companion files exist in parentDir, search device external storage for the original folder
            if (existingCompanions.isEmpty()) {
                val sourceDir = findSourceDirectoryOnDevice(pckOrExeFile.name, pckOrExeFile.length())
                if (sourceDir != null && sourceDir.exists() && sourceDir.isDirectory) {
                    Log.i(TAG, "Discovered source directory on device storage: ${sourceDir.absolutePath}")
                    val externalCompanions = sourceDir.listFiles()?.filter {
                        it.isFile && !it.name.equals(pckOrExeFile.name, ignoreCase = true) && it.extension.lowercase() in companionExtensions
                    } ?: emptyList()
                    for (comp in externalCompanions) {
                        try {
                            val targetInParent = File(parentDir, comp.name)
                            if (!targetInParent.exists() || targetInParent.length() != comp.length()) {
                                comp.copyTo(targetInParent, overwrite = true)
                                Log.i(TAG, "Restored companion file into game storage: ${comp.name}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not copy companion to parentDir: ${e.message}")
                        }
                    }
                    siblingFiles = parentDir.listFiles() ?: emptyArray()
                }
            }

            for (sibling in siblingFiles) {
                if (!sibling.isFile) continue
                if (sibling.name.equals(pckOrExeFile.name, ignoreCase = true)) continue

                val ext = sibling.extension.lowercase()
                when (ext) {
                    "pck", "zip" -> {
                        siblingPcks.add(sibling.name)
                        val destPck = File(gameDir, sibling.name)
                        if (destPck.exists()) destPck.setWritable(true)
                        sibling.copyTo(destPck, overwrite = true)

                        if (ext == "pck") {
                            // Check if companion PCK header needs engine version clamping
                            try {
                                val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
                                val header = inspector.inspectHeader(destPck)
                                if (header.engineMajor > engineMajor ||
                                    (header.engineMajor == engineMajor && header.engineMinor > engineMinor)
                                ) {
                                    java.io.RandomAccessFile(destPck, "rw").use { raf ->
                                        val buf = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        buf.putInt(engineMajor)
                                        buf.putInt(engineMinor)
                                        buf.putInt(0)
                                        buf.flip()
                                        raf.channel.position(8L)
                                        raf.channel.write(buf)
                                    }
                                    Log.i(TAG, "Clamped companion PCK ${sibling.name} version to $engineMajor.$engineMinor")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not check/clamp version for companion PCK ${sibling.name}: ${e.message}")
                            }
                        }

                        destPck.setReadOnly()
                        Log.i(TAG, "Copied companion resource pack: ${sibling.name} -> ${destPck.absolutePath}")
                    }
                    "dll", "so", "dylib" -> {
                        // Copy companion native library to core/plugin native library directory (jniLibs)
                        val destJniLib = File(jniLibsDir, sibling.name)
                        if (destJniLib.exists()) destJniLib.setWritable(true)
                        sibling.copyTo(destJniLib, overwrite = true)
                        destJniLib.setReadOnly()

                        // Also copy to gameDir so relative lookups next to the main pack succeed
                        val destGameDirLib = File(gameDir, sibling.name)
                        if (destGameDirLib.exists()) destGameDirLib.setWritable(true)
                        sibling.copyTo(destGameDirLib, overwrite = true)
                        destGameDirLib.setReadOnly()

                        Log.i(TAG, "Copied companion native library: ${sibling.name} -> ${destJniLib.absolutePath}")
                    }
                }
            }
        }

        val hasSiblingPcks = siblingPcks.isNotEmpty()
        if (hasSiblingPcks) {
            CompanionPackMounterGenerator.writeScriptToDirectory(gameDir, siblingPcks, pckOrExeFile.name, effectiveEngineMajor, gameDir.absolutePath)
        }

        // 3. Inspect PCK files (for GDNative scripts, project.binary autoload/UID overrides, and downscaling)
        val gdnsStubsToInject = mutableMapOf<String, ByteArray>()
        var hasGdNativeScripts = false
        var projectBinaryOverrides = ProjectBinaryOverrides()

        val stubSoBytes = try {
            context.assets.open("stubs/libgdnative_stub.so").use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load libgdnative_stub.so from assets: ${e.message}")
            null
        }

        try {
            java.io.RandomAccessFile(workingPckFile, "r").use { raf ->
                val channel = raf.channel
                val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
                val inspectResult = inspector.inspect(channel, channel.size())

                // A. Check for GDNative scripts (Godot 3)
                for (entry in inspectResult.files) {
                    val path = entry.path // e.g. "res://Native/NativeTest.gdns"
                    val cleanPath = path.removePrefix("/")
                    if (cleanPath.endsWith(".gdns", ignoreCase = true)) {
                        hasGdNativeScripts = true
                        val stubScript = generateGdnsStubScript(cleanPath)
                        gdnsStubsToInject[cleanPath] = stubScript.toByteArray(Charsets.UTF_8)
                    } else if (cleanPath.equals("res://Native/rust_panic_hook.gd", ignoreCase = true) ||
                               cleanPath.equals("res://Native/rust_panic_hook.gdc", ignoreCase = true) ||
                               cleanPath.equals("res://Native/rust_panic_hook.gd.remap", ignoreCase = true)) {
                        hasGdNativeScripts = true
                        val panicStub = "extends Node\nfunc _ready():\n    pass\n"
                        gdnsStubsToInject["res://Native/rust_panic_hook.gd"] = panicStub.toByteArray(Charsets.UTF_8)
                        val remapStub = "[remap]\npath=\"res://Native/rust_panic_hook.gd\"\n"
                        gdnsStubsToInject["res://Native/rust_panic_hook.gd.remap"] = remapStub.toByteArray(Charsets.UTF_8)
                    } else if (cleanPath.equals("res://Native/lib_optimizations.tres", ignoreCase = true) ||
                               cleanPath.endsWith(".gdnlib", ignoreCase = true)) {
                        hasGdNativeScripts = true
                        val validGdnLib = """
                        [gd_resource type="GDNativeLibrary" format=2]

                        [resource]
                        load_once = false
                        entry/Android.arm64-v8a = "res://Native/Android/lib_aarch64-linux-android.so"
                        entry/Windows.64 = "res://Native/Windows/optimizations.dll"
                        entry/X11.64 = "res://Native/Linux/optimizations.so"
                        dependency/Android.arm64-v8a = [  ]
                        dependency/Windows.64 = [  ]
                        dependency/X11.64 = [  ]
                        """.trimIndent()
                        gdnsStubsToInject[cleanPath] = validGdnLib.toByteArray(Charsets.UTF_8)
                        if (stubSoBytes != null) {
                            gdnsStubsToInject["res://Native/Android/lib_aarch64-linux-android.so"] = stubSoBytes
                            try {
                                val soFile = File(jniLibsDir, "lib_aarch64-linux-android.so")
                                if (soFile.exists()) soFile.setWritable(true)
                                soFile.writeBytes(stubSoBytes)
                                soFile.setReadOnly()
                                val soGameFile = File(gameDir, "lib_aarch64-linux-android.so")
                                if (soGameFile.exists()) soGameFile.setWritable(true)
                                soGameFile.writeBytes(stubSoBytes)
                                soGameFile.setReadOnly()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed staging stub SO file: ${e.message}")
                            }
                        }
                    }
                }

                // B. Check for project.binary or project.godot (Godot 4) to resolve any unresolvable UIDs
                if (effectiveEngineMajor >= 4) {
                    val projEntry = inspectResult.files.find {
                        it.path == "res://project.binary" || it.rawPath == "project.binary" ||
                        it.path == "res://project.godot" || it.rawPath == "project.godot"
                    }
                    if (projEntry != null) {
                        val projBytes = inspector.extractFileBytes(channel, projEntry)
                        val uidEntry = inspectResult.files.find {
                            it.path == "res://.godot/uid_cache.bin" || it.rawPath == ".godot/uid_cache.bin"
                        }
                        val uidBytes = if (uidEntry != null) {
                            try { inspector.extractFileBytes(channel, uidEntry) } catch (_: Exception) { null }
                        } else null

                        projectBinaryOverrides = resolveProjectBinaryOverrides(projBytes, inspectResult.files, uidBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting PCK for scripts/UIDs: ${e.message}")
        }

        // 4. Generate override.cfg
        val overrideCfgFile = File(gameDir, "override.cfg")
        if (overrideCfgFile.exists()) {
            overrideCfgFile.setWritable(true)
        }
        val effectiveMainScene = mainSceneOverride ?: projectBinaryOverrides.mainScene
        val overrideContent = generateOverrideCfg(
            renderer = selectedRenderer,
            mainSceneOverride = effectiveMainScene,
            injectSteamStub = injectSteamStub,
            hasCompanionPcks = hasSiblingPcks,
            engineMajor = effectiveEngineMajor,
            audioDriver = audioDriver,
            busLayoutOverride = projectBinaryOverrides.busLayout,
            extraAutoloads = projectBinaryOverrides.autoloads
        )
        overrideCfgFile.writeText(overrideContent, Charsets.UTF_8)
        overrideCfgFile.setReadOnly()

        // 5. Stage any available Android ARM64 GDExtension libraries
        stageGdExtensionLibraries(gameDir, jniLibsDir, plugins)

        // Always stage libgodot_compat.so into runtime jniLibsDir for universal anti-crash protection
        try {
            val compatBytes = context.assets.open("stubs/libgodot_compat.so").use { it.readBytes() }
            val compatFile = File(jniLibsDir, "libgodot_compat.so")
            if (compatFile.exists()) compatFile.setWritable(true)
            compatFile.writeBytes(compatBytes)
            compatFile.setReadOnly()
        } catch (_: Exception) {}

        // 6. Stage standalone PCK and inject virtual files ONLY when necessary (Steam stub, companion packs, GDNative stubs, in-PCK plugin rewriting, or version clamping)
        // 6. Stage standalone PCK and inject virtual files ONLY when necessary (Steam stub, companion packs, GDNative stubs, in-PCK plugin rewriting, or version clamping)
        var targetPckOrExe = workingPckFile
        val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
        val header = try {
            inspector.inspectHeader(workingPckFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect PCK header: ${e.message}")
            null
        }

        val isEmbeddedExe = header?.isEmbeddedExe == true
        val isVersionOlder = header != null && (
            header.engineMajor > effectiveEngineMajor ||
            (header.engineMajor == effectiveEngineMajor && header.engineMinor > effectiveEngineMinor)
        )
        val hasUidOverrides = projectBinaryOverrides.autoloads.isNotEmpty() ||
            projectBinaryOverrides.mainScene != null ||
            projectBinaryOverrides.busLayout != null

        val needsVirtualInjection = injectSteamStub ||
            plugins.any { it.configPath.isNotBlank() } ||
            hasSiblingPcks ||
            hasGdNativeScripts ||
            isVersionOlder ||
            hasUidOverrides

        if (isEmbeddedExe && !needsVirtualInjection) {
            // Fast direct extraction: Embedded Windows PE .exe contains a valid standalone GDPC archive.
            try {
                if (stagedPck.exists()) stagedPck.setWritable(true)
                if (!stagedPck.exists() || stagedPck.length() == 0L) {
                    inspector.extractStandalonePck(workingPckFile, stagedPck, header!!)
                }
                stagedPck.setReadable(true, false)
                if (stagedPck.exists() && stagedPck.length() > 0) {
                    targetPckOrExe = stagedPck
                    Log.i(TAG, "Fast-path: extracted clean standalone PCK directly from embedded EXE (${stagedPck.length()} bytes)")
                    try {
                        val aliasFile = File(gameDir, pckOrExeFile.name)
                        if (!aliasFile.exists()) {
                            android.system.Os.link(stagedPck.absolutePath, aliasFile.absolutePath)
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct standalone PCK extraction failed: ${e.message}")
            }
        } else if (needsVirtualInjection) {
            try {
                val filesToInject = mutableMapOf<String, ByteArray>()
                filesToInject["res://override.cfg"] = overrideContent.toByteArray(Charsets.UTF_8)
                if (injectSteamStub) {
                    val stubContent = SteamStubGenerator.generateStub(effectiveEngineMajor)
                    filesToInject["res://godotdroid_steam_stub.gd"] = stubContent.toByteArray(Charsets.UTF_8)
                }
                if (hasSiblingPcks) {
                    val mountContent = CompanionPackMounterGenerator.generateMountScript(siblingPcks, pckOrExeFile.name, effectiveEngineMajor, gameDir.absolutePath)
                    filesToInject["res://godotdroid_companion_mount.gd"] = mountContent.toByteArray(Charsets.UTF_8)
                }
                if (hasGdNativeScripts) {
                    filesToInject.putAll(gdnsStubsToInject)
                    Log.i(TAG, "Injected GDNative GDScript stubs for ${gdnsStubsToInject.size} resource(s)")
                }

                // In-PCK .gdextension & .gdnlib rewriting
                val pluginCompat = PluginCompatManager(context)
                for (plugin in plugins) {
                    if (plugin.configPath.isNotBlank()) {
                        var originalConfigText = ""
                        try {
                            java.io.RandomAccessFile(workingPckFile, "r").use { raf ->
                                val inspectResult = inspector.inspect(raf.channel, raf.length())
                                val entry = inspectResult.files.find { it.path == plugin.configPath }
                                if (entry != null) {
                                    originalConfigText = String(inspector.extractFileBytes(raf.channel, entry), Charsets.UTF_8)
                                }
                            }
                        } catch (_: Exception) {}

                        val patchedConfig = pluginCompat.patchExtensionConfigContent(
                            originalIni = originalConfigText,
                            isGodot4 = (effectiveEngineMajor >= 4),
                            androidSoNames = plugin.androidLibraries.values.map { it.substringAfterLast('/') }.ifEmpty { listOf("${plugin.name}.so") }
                        )
                        filesToInject[plugin.configPath] = patchedConfig.toByteArray(Charsets.UTF_8)
                    }
                }

                if (stagedPck.exists()) stagedPck.setWritable(true)
                val patcher = com.godotdroid.pck.patcher.PckVirtualPatcher()
                patcher.patchPck(
                    sourceFile = workingPckFile,
                    targetFile = stagedPck,
                    injectedFiles = filesToInject,
                    targetEngineMajor = effectiveEngineMajor,
                    targetEngineMinor = effectiveEngineMinor
                )
                stagedPck.setReadable(true, false)
                if (stagedPck.exists() && stagedPck.length() > 0) {
                    targetPckOrExe = stagedPck
                    // Create an alias/hardlink matching the original filename in gameDir
                    // so if any companion pack or script looks up pckOrExeFile.name directly inside gameDir, it is found
                    try {
                        val aliasFile = File(gameDir, pckOrExeFile.name)
                        if (!aliasFile.exists()) {
                            android.system.Os.link(stagedPck.absolutePath, aliasFile.absolutePath)
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtual PCK patch skipped or failed: ${e.message}")
                if (isEmbeddedExe && header != null) {
                    try {
                        inspector.extractStandalonePck(workingPckFile, stagedPck, header)
                        stagedPck.setReadable(true, false)
                        if (stagedPck.exists() && stagedPck.length() > 0) {
                            targetPckOrExe = stagedPck
                        }
                    } catch (_: Throwable) {}
                } else {
                    targetPckOrExe = workingPckFile
                }
            }
        } else {
            Log.i(TAG, "Fast-path: launching game package directly via --main-pack without disk duplication")
        }

        // Final sanity verification: Ensure target package starts with GDPC magic (0x43504447)
        if (targetPckOrExe.exists() && targetPckOrExe.length() >= 4) {
            try {
                java.io.RandomAccessFile(targetPckOrExe, "r").use { raf ->
                    val magicBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    raf.channel.read(magicBuf)
                    magicBuf.flip()
                    val magic = magicBuf.getInt()
                    if (magic != com.godotdroid.pck.inspector.PckConstants.PCK_MAGIC) {
                        Log.e(TAG, "CRITICAL: target package ${targetPckOrExe.name} does NOT begin with GDPC magic! Magic: 0x${Integer.toHexString(magic)}")
                    } else {
                        Log.i(TAG, "Verified target package ${targetPckOrExe.name} starts with GDPC magic (0x43504447)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not verify final target package magic: ${e.message}")
            }
        }

        // 5. Build CLI arguments
        val cliArgs = buildGodotCliArgs(
            pckOrExeFile = targetPckOrExe,
            selectedRenderer = selectedRenderer,
            workingDir = gameDir,
            engineMajor = effectiveEngineMajor,
            audioDriver = audioDriver,
            extraArgs = extraCliArgs
        )

        return RuntimeEnvironment(
            gameId = gameId,
            runtimeDir = gameDir,
            overrideCfgFile = overrideCfgFile,
            jniLibsDir = jniLibsDir,
            cliArgs = cliArgs
        )
    }

    /**
     * Generates a Godot override.cfg configuration file.
     */
    fun generateOverrideCfg(
        renderer: RendererType,
        mainSceneOverride: String? = null,
        injectSteamStub: Boolean = false,
        hasCompanionPcks: Boolean = false,
        engineMajor: Int = 4,
        audioDriver: String? = null,
        busLayoutOverride: String? = null,
        extraAutoloads: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("; GodotDroid Auto-Generated Runtime Compatibility Config")

        if (engineMajor >= 4) {
            sb.appendLine("[rendering]")
            when (renderer) {
                RendererType.GL_COMPATIBILITY, RendererType.GLES2, RendererType.GLES3 -> {
                    sb.appendLine("renderer/rendering_method=\"gl_compatibility\"")
                    sb.appendLine("renderer/rendering_method.mobile=\"gl_compatibility\"")
                    sb.appendLine("driver/driver.android=\"opengl3\"")
                    sb.appendLine("renderer/rendering_method.web=\"gl_compatibility\"")
                }
                RendererType.FORWARD_PLUS -> {
                    sb.appendLine("renderer/rendering_method=\"forward_plus\"")
                    sb.appendLine("driver/driver.android=\"vulkan\"")
                }
                RendererType.MOBILE, RendererType.UNKNOWN -> {
                    sb.appendLine("renderer/rendering_method=\"mobile\"")
                    sb.appendLine("renderer/rendering_method.mobile=\"mobile\"")
                    sb.appendLine("driver/driver.android=\"vulkan\"")
                    sb.appendLine("renderer/rendering_method.web=\"gl_compatibility\"")
                }
            }
            sb.appendLine("textures/vram_compression/import_s3tc_bptc=true")
            sb.appendLine("textures/vram_compression/import_etc2_astc=true")

            sb.appendLine()
            sb.appendLine("[display]")
            sb.appendLine("window/size/mode=3 ; Fullscreen Mode on Android")
            sb.appendLine("window/stretch/mode=\"canvas_items\" ; Smooth resolution adaptation for mobile")
            sb.appendLine("window/stretch/aspect=\"expand\" ; Maintain aspect ratio without black borders")
        } else {
            // Godot 3.x
            sb.appendLine("[rendering]")
            val driverName = if (renderer == RendererType.GLES2 || renderer == RendererType.GL_COMPATIBILITY) {
                "GLES2"
            } else {
                "GLES3"
            }
            sb.appendLine("quality/driver/driver_name=\"$driverName\"")
            sb.appendLine("quality/driver/fallback_to_gles2=true")
            sb.appendLine("vram_compression/import_etc2=true")
            sb.appendLine("vram_compression/import_s3tc=true")

            sb.appendLine()
            sb.appendLine("[display]")
            sb.appendLine("window/handheld/orientation=\"landscape\"")
            sb.appendLine("window/stretch/mode=\"2d\"")
            sb.appendLine("window/stretch/aspect=\"expand\"")

            sb.appendLine()
            sb.appendLine("[audio]")
            sb.appendLine("output_latency=50")
            sb.appendLine("output_latency.web=50")
            if (!audioDriver.isNullOrBlank()) {
                sb.appendLine("driver=\"$audioDriver\"")
                sb.appendLine("audio/driver=\"$audioDriver\"")
            }
        }

        if (!mainSceneOverride.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("[application]")
            sb.appendLine("run/main_scene=\"$mainSceneOverride\"")
        }

        if (!busLayoutOverride.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("[audio]")
            sb.appendLine("buses/default_bus_layout=\"$busLayoutOverride\"")
        }

        if (injectSteamStub || hasCompanionPcks || extraAutoloads.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[autoload]")
            if (injectSteamStub) {
                sb.appendLine("Steam=\"*res://godotdroid_steam_stub.gd\"")
            }
            if (hasCompanionPcks) {
                sb.appendLine("GodotDroidCompanionMount=\"*res://godotdroid_companion_mount.gd\"")
            }
            for ((name, path) in extraAutoloads) {
                sb.appendLine("$name=\"$path\"")
            }
        }

        return sb.toString()
    }

    data class ProjectBinaryOverrides(
        val mainScene: String? = null,
        val busLayout: String? = null,
        val autoloads: Map<String, String> = emptyMap()
    )

    fun godotIdToText(pId: Long): String {
        if (pId < 0) return "uid://<invalid>"
        var id = pId
        val base = 36L
        val charCount = 26L
        val sb = java.lang.StringBuilder()
        while (id > 0) {
            val c = (id % base).toInt()
            if (c < charCount) {
                sb.append(('a'.code + c).toChar())
            } else {
                sb.append(('0'.code + (c - charCount.toInt())).toChar())
            }
            id /= base
        }
        return "uid://${sb.reverse()}"
    }

    /**
     * Resolves unresolvable or mismatched UIDs for autoloads, main_scene, and bus layout in Godot 4 games.
     */
    fun resolveProjectBinaryOverrides(
        projBytes: ByteArray,
        allFiles: List<PckFileEntry>,
        uidCacheBytes: ByteArray? = null
    ): ProjectBinaryOverrides {
        val projText = String(projBytes, Charsets.ISO_8859_1)

        val uidMap = mutableMapOf<String, String>()
        if (uidCacheBytes != null && uidCacheBytes.size >= 4) {
            try {
                val buf = ByteBuffer.wrap(uidCacheBytes).order(ByteOrder.LITTLE_ENDIAN)
                val count = buf.getInt()
                for (i in 0 until count) {
                    if (buf.remaining() < 12) break
                    val id = buf.getLong()
                    val len = buf.getInt()
                    if (len < 0 || buf.remaining() < len) break
                    val pathBytes = ByteArray(len)
                    buf.get(pathBytes)
                    val p = String(pathBytes, Charsets.UTF_8).trimEnd('\u0000').trim()
                    val textUid = godotIdToText(id)
                    uidMap[textUid] = p
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing uid_cache.bin: ${e.message}")
            }
        }

        fun getBaseName(path: String): String {
            val name = path.substringAfterLast('/')
            val exts = listOf(".remap", ".gdc", ".gd", ".tscn", ".scn", ".res", ".tres")
            var cur = name
            for (ext in exts) {
                if (cur.endsWith(ext, ignoreCase = true)) {
                    cur = cur.dropLast(ext.length)
                }
            }
            return cur.lowercase()
        }

        fun sanitizeResPath(raw: String): String {
            var path = raw.trimEnd('\u0000').trim()
            if (path.endsWith(".remap", ignoreCase = true)) {
                path = path.dropLast(6)
            }
            if (path.endsWith(".gdc", ignoreCase = true)) {
                path = path.dropLast(4) + ".gd"
            }
            if (!path.startsWith("res://") && !path.startsWith("user://")) {
                path = "res://" + path.trimStart('/')
            }
            return path
        }

        // 1. Resolve Autoloads
        val autoloadRegex = Regex("""autoload/([a-zA-Z0-9_]+)[\s\S]{1,40}?(\*?uid://[a-zA-Z0-9]{8,16})|([a-zA-Z0-9_]+)\s*=\s*"(\*?uid://[a-zA-Z0-9]{8,16})""")
        val resolvedAutoloads = mutableMapOf<String, String>()
        for (match in autoloadRegex.findAll(projText)) {
            val name = match.groupValues[1].ifEmpty { match.groupValues[3] }
            val fullUid = match.groupValues[2].ifEmpty { match.groupValues[4] }
            if (name.isEmpty() || fullUid.isEmpty()) continue
            val isSingleton = fullUid.startsWith("*")
            val uid = fullUid.removePrefix("*")

            var resolvedPath: String? = uidMap[uid]
            if (resolvedPath == null) {
                val target = name.lowercase()
                val candidateFiles = allFiles.filter { getBaseName(it.rawPath) == target }
                    .ifEmpty { allFiles.filter { getBaseName(it.rawPath).contains(target) } }
                val best = candidateFiles.minWithOrNull(
                    compareBy<PckFileEntry> {
                        when {
                            it.rawPath.contains(".tscn", ignoreCase = true) -> 0
                            it.rawPath.contains(".gd", ignoreCase = true) -> 1
                            else -> 2
                        }
                    }
                )
                if (best != null) {
                    resolvedPath = sanitizeResPath(best.rawPath)
                }
            } else {
                resolvedPath = sanitizeResPath(resolvedPath)
            }

            if (resolvedPath != null) {
                resolvedAutoloads[name] = if (isSingleton) "*$resolvedPath" else resolvedPath
                Log.i(TAG, "Resolved autoload $name ($uid) -> ${resolvedAutoloads[name]}")
            }
        }

        // 2. Resolve Main Scene
        val mainSceneRegex = Regex("""(?:application/run/main_scene|run/main_scene)[\s\S]{1,40}?(uid://[a-zA-Z0-9]{8,16})""")
        var resolvedMainScene: String? = null
        val mainMatch = mainSceneRegex.find(projText)
        if (mainMatch != null) {
            val uid = mainMatch.groupValues[1]
            val pathFromMap = uidMap[uid]
            if (pathFromMap != null) {
                resolvedMainScene = sanitizeResPath(pathFromMap)
            } else {
                val mainCandidates = allFiles.filter {
                    val p = it.rawPath.lowercase()
                    (p.endsWith("main.tscn") || p.endsWith("main.tscn.remap") || p.endsWith("main.scn"))
                }
                val bestMain = mainCandidates.find { it.rawPath.endsWith(".remap", ignoreCase = true) }
                    ?: mainCandidates.firstOrNull()
                if (bestMain != null) {
                    resolvedMainScene = sanitizeResPath(bestMain.rawPath)
                }
            }
            if (resolvedMainScene != null) {
                Log.i(TAG, "Resolved main_scene ($uid) -> $resolvedMainScene")
            }
        }

        // 3. Resolve Default Bus Layout
        val busLayoutRegex = Regex("""(?:audio/buses/default_bus_layout|buses/default_bus_layout)[\s\S]{1,40}?(uid://[a-zA-Z0-9]{8,16})""")
        var resolvedBusLayout: String? = null
        val busMatch = busLayoutRegex.find(projText)
        if (busMatch != null) {
            val uid = busMatch.groupValues[1]
            val pathFromMap = uidMap[uid]
            if (pathFromMap != null) {
                resolvedBusLayout = sanitizeResPath(pathFromMap)
            } else {
                val busCandidates = allFiles.filter {
                    it.rawPath.contains("default_bus_layout", ignoreCase = true)
                }
                val bestBus = busCandidates.find { it.rawPath.endsWith(".remap", ignoreCase = true) }
                    ?: busCandidates.firstOrNull()
                if (bestBus != null) {
                    resolvedBusLayout = sanitizeResPath(bestBus.rawPath)
                }
            }
            if (resolvedBusLayout != null) {
                Log.i(TAG, "Resolved default_bus_layout ($uid) -> $resolvedBusLayout")
            }
        }

        return ProjectBinaryOverrides(
            mainScene = resolvedMainScene,
            busLayout = resolvedBusLayout,
            autoloads = resolvedAutoloads
        )
    }

    /**
     * Builds command-line arguments array for Godot engine initialization.
     */
    fun buildGodotCliArgs(
        pckOrExeFile: File,
        selectedRenderer: RendererType,
        workingDir: File,
        engineMajor: Int = 4,
        audioDriver: String? = null,
        extraArgs: List<String> = emptyList()
    ): Array<String> {
        val args = mutableListOf<String>()

        // 1. Target package
        args.add("--main-pack")
        args.add(pckOrExeFile.absolutePath)

        // 2. Rendering Method & Driver / Video Driver
        if (engineMajor >= 4) {
            when (selectedRenderer) {
                RendererType.GL_COMPATIBILITY, RendererType.GLES2, RendererType.GLES3 -> {
                    args.add("--rendering-method")
                    args.add("gl_compatibility")
                    args.add("--rendering-driver")
                    args.add("opengl3")
                }
                RendererType.FORWARD_PLUS -> {
                    args.add("--rendering-method")
                    args.add("forward_plus")
                    args.add("--rendering-driver")
                    args.add("vulkan")
                }
                RendererType.MOBILE, RendererType.UNKNOWN -> {
                    args.add("--rendering-method")
                    args.add("mobile")
                    args.add("--rendering-driver")
                    args.add("vulkan")
                }
            }
        } else {
            // Godot 3.x rejects --rendering-method! Uses --video-driver GLES3|GLES2
            val videoDriver = if (selectedRenderer == RendererType.GLES2 || selectedRenderer == RendererType.GL_COMPATIBILITY) {
                "GLES2"
            } else {
                "GLES3"
            }
            args.add("--video-driver")
            args.add(videoDriver)
        }

        // 3. Audio & display stability
        args.add("--fullscreen")
        if (!audioDriver.isNullOrBlank()) {
            args.add("--audio-driver")
            args.add(audioDriver)
        }

        // 4. Custom extra arguments
        args.addAll(extraArgs)

        return args.toTypedArray()
    }

    /**
     * Stages any detected or cached ARM64 GDExtension libraries into jniLibs with setReadOnly().
     */
    fun stageGdExtensionLibraries(
        gameDir: File,
        jniLibsDir: File,
        plugins: List<PluginDependency>
    ) {
        val baseFilesDir = context.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "godotdroid_runtime_env")
        val pluginCacheDir = File(baseFilesDir, "plugin_cache")
        for (plugin in plugins) {
            if (plugin.isGodotSteam) {
                // If GodotSteam is detected, check if we have a stub libgodotsteam.so in cache
                val cachedSteam = File(pluginCacheDir, "libgodotsteam.so")
                if (cachedSteam.exists()) {
                    val dest = File(jniLibsDir, "libgodotsteam.so")
                    dest.setWritable(true)
                    cachedSteam.copyTo(dest, overwrite = true)
                    dest.setReadOnly()
                }
            }

            // Copy any known arm64 libraries for the plugin
            for ((key, path) in plugin.androidLibraries) {
                val fileName = path.substringAfterLast('/')
                val cached = File(pluginCacheDir, fileName)
                if (cached.exists()) {
                    val dest = File(jniLibsDir, fileName)
                    dest.setWritable(true)
                    cached.copyTo(dest, overwrite = true)
                    dest.setReadOnly()
                }
            }
        }
    }

    /**
     * Cleans up the runtime environment for a given game ID.
     */
    fun cleanupRuntimeEnvironment(gameId: String) {
        val gameDir = File(runtimeBaseDir, gameId)
        if (gameDir.exists()) {
            gameDir.walkBottomUp().forEach { file ->
                file.setWritable(true)
                file.delete()
            }
        }
    }

    /**
     * Recursively searches common device storage directories (/sdcard/Download, /storage/emulated/0, etc.)
     * for a folder that contains targetName (with optional expected size matching).
     */
    fun findSourceDirectoryOnDevice(fileName: String, expectedSize: Long = 0L): File? {
        val searchRoots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            File("/storage/emulated/0"),
            File("/sdcard")
        ).distinct().filter { it.exists() && it.isDirectory }

        for (root in searchRoots) {
            val found = scanDirectoryForFile(root, fileName, expectedSize, maxDepth = 4, currentDepth = 0)
            if (found != null) {
                return found.parentFile
            }
        }
        return null
    }

    private fun scanDirectoryForFile(dir: File, targetName: String, expectedSize: Long, maxDepth: Int, currentDepth: Int): File? {
        if (currentDepth > maxDepth) return null
        val files = dir.listFiles() ?: return null
        // Direct files check
        for (f in files) {
            if (f.isFile && f.name.equals(targetName, ignoreCase = true)) {
                if (expectedSize <= 0L || Math.abs(f.length() - expectedSize) < 2048L) {
                    return f
                }
            }
        }
        // Subdirectories check (ignore hidden folders)
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                val found = scanDirectoryForFile(f, targetName, expectedSize, maxDepth, currentDepth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun generateGdnsStubScript(cleanPath: String): String {
        return when {
            cleanPath.contains("NativeTest", ignoreCase = true) || cleanPath.contains("HelloWorld", ignoreCase = true) -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\nexport var greeting: String = \"Pengiusis\"\nfunc _ready():\n    pass\nfunc _process(_delta):\n    pass\n"
                """.trimIndent()
            }
            cleanPath.contains("MovementManager", ignoreCase = true) -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\n\nvar velocity: Vector2 = Vector2.ZERO\nvar relative_velocity: Vector2 = Vector2.ZERO\nvar speed_modifier: float = 1.0\nvar added_speed: float = 0.0\nvar gravity: float = 980.0\nvar active_gravity: float = 980.0\nvar gravity_modifier: float = 1.0\nvar run_speed: float = 100.0\nvar jump_height: float = 50.0\nvar facing: int = 1\nvar active_speed: float = 100.0\nvar active_jump_duration: float = 0.3\nvar jump_duration_modifier: float = 1.0\nvar active_jump_velocity: float = -300.0\nvar friction: float = 1.0\nvar ground_friction: float = 1.0\nvar snapping_enabled: bool = true\n\nfunc _ready():\n    pass\n\nfunc _process(_delta):\n    pass\n\nfunc _physics_process(_delta):\n    pass\n\nfunc init(_arg1 = null, _arg2 = null, _arg3 = null, _arg4 = null):\n    pass\n\nfunc reset():\n    velocity = Vector2.ZERO\n\nfunc getVelocity() -> Vector2:\n    return velocity\n\nfunc getRelativeVelocity() -> Vector2:\n    return relative_velocity\n\nfunc setVelocity(v):\n    if v is Vector2:\n        velocity = v\n\nfunc setVelocity2D(x, y):\n    velocity = Vector2(float(x), float(y))\n\nfunc setVelocityRaw(v):\n    if v is Vector2:\n        velocity = v\n\nfunc setVelocityRawX(x):\n    velocity.x = float(x)\n\nfunc setVelocityRawY(y):\n    velocity.y = float(y)\n\nfunc forceMinimumVelocity(_v):\n    pass\n\nfunc forceMaximumVelocity(_v):\n    pass\n\nfunc pushAway(_arg1 = null, _arg2 = null):\n    pass\n\nfunc pullTowards(_arg1 = null, _arg2 = null):\n    pass\n\nfunc beginJump():\n    pass\n\nfunc forceJump():\n    pass\n\nfunc releaseJump():\n    pass\n\nfunc resetFallProgress():\n    pass\n\nfunc passthrough():\n    pass\n\nfunc isFloored() -> bool:\n    return true\n\nfunc isJumping() -> bool:\n    return false\n\nfunc isFalling() -> bool:\n    return false\n\nfunc isWalled() -> bool:\n    return false\n\nfunc isRunning() -> bool:\n    return false\n\nfunc getRunSign() -> int:\n    return facing\n\nfunc getRunSpeed() -> float:\n    return run_speed\n\nfunc getJumpHeight() -> float:\n    return jump_height\n\nfunc getFacing() -> int:\n    return facing\n\nfunc getActiveSpeed() -> float:\n    return active_speed\n\nfunc setActiveSpeed(s):\n    active_speed = float(s)\n\nfunc resetActiveSpeed():\n    active_speed = run_speed\n\nfunc setActiveJumpDuration(d):\n    active_jump_duration = float(d)\n\nfunc resetActiveJumpDuration():\n    active_jump_duration = 0.3\n\nfunc setJumpDurationModifier(m):\n    jump_duration_modifier = float(m)\n\nfunc resetJumpDurationModifier():\n    jump_duration_modifier = 1.0\n\nfunc setActiveJumpVelocity(v):\n    active_jump_velocity = float(v)\n\nfunc resetActiveJumpVelocity():\n    active_jump_velocity = -300.0\n\nfunc setSpeedModifier(m):\n    speed_modifier = float(m)\n\nfunc getSpeedModifier() -> float:\n    return speed_modifier\n\nfunc resetSpeedModifier():\n    speed_modifier = 1.0\n\nfunc setAddedSpeed(s):\n    added_speed = float(s)\n\nfunc resetAddedSpeed():\n    added_speed = 0.0\n\nfunc getAddedSpeed() -> float:\n    return added_speed\n\nfunc setGravity(g):\n    gravity = float(g)\n\nfunc getGravity() -> float:\n    return gravity\n\nfunc getActiveGravity() -> float:\n    return active_gravity\n\nfunc resetGravity():\n    gravity = 980.0\n\nfunc setGravityModifier(m):\n    gravity_modifier = float(m)\n\nfunc getGravityModifier() -> float:\n    return gravity_modifier\n\nfunc resetGravityModifier():\n    gravity_modifier = 1.0\n\nfunc setGravityModifierImmediately(m):\n    gravity_modifier = float(m)\n\nfunc setGravityModifierSmooth(m):\n    gravity_modifier = float(m)\n\nfunc setFriction(f):\n    friction = float(f)\n\nfunc resetFriction():\n    friction = 1.0\n\nfunc setGroundFriction(f):\n    ground_friction = float(f)\n\nfunc toggleSnapping(s = true):\n    snapping_enabled = bool(s)\n\nfunc collideWhenNormal():\n    pass\n\nfunc physics_move(_arg1 = null, _arg2 = null, _arg3 = null) -> Vector2:\n    return velocity\n\nfunc physics_collide(_arg1 = null, _arg2 = null):\n    return null\n"
                """.trimIndent()
            }
            cleanPath.contains("VisualSync", ignoreCase = true) -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\n\nvar target = null\nvar enabled: bool = true\nvar mode: int = 0\n\nfunc _ready():\n    pass\n\nfunc _process(_delta):\n    pass\n\nfunc _physics_process(_delta):\n    pass\n\nfunc _notification(_what):\n    pass\n\nfunc init(_arg1 = null, _arg2 = null):\n    pass\n\nfunc set_enabled(p_enabled):\n    enabled = bool(p_enabled)\n\nfunc set_mode(p_mode):\n    mode = int(p_mode)\n\nfunc set_target(p_target):\n    target = p_target\n\nfunc get_target():\n    return target\n\nfunc teleport(_pos = null):\n    pass\n"
                """.trimIndent()
            }
            cleanPath.contains("TileOracle", ignoreCase = true) -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\n\nfunc _ready():\n    pass\nfunc _process(_delta):\n    pass\nfunc clear_caches():\n    pass\nfunc garbage_collect():\n    pass\nfunc hard_reset():\n    pass\nfunc lazy_load(_arg1 = null):\n    pass\nfunc refresh_areas():\n    pass\nfunc register(_arg1 = null, _arg2 = null):\n    pass\nfunc toggle(_arg1 = null):\n    pass\nfunc unregister(_arg1 = null):\n    pass\nfunc update_setup():\n    pass\n"
                """.trimIndent()
            }
            cleanPath.contains("Oracle", ignoreCase = true) -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\n\nfunc _ready():\n    pass\nfunc _process(_delta):\n    pass\nfunc hardReset():\n    pass\nfunc isLayerActive(_layer = 0) -> bool:\n    return true\nfunc isRegistered(_arg = null) -> bool:\n    return true\nfunc register(_arg1 = null, _arg2 = null):\n    pass\nfunc registerLayer(_arg1 = null, _arg2 = null):\n    pass\nfunc register_static(_arg1 = null):\n    pass\nfunc setLayerScales(_arg1 = null, _arg2 = null):\n    pass\nfunc toggleLayer(_layer = 0):\n    pass\nfunc toggle_raycast(_arg = null):\n    pass\nfunc unregister(_arg = null):\n    pass\nfunc unregister_static(_arg = null):\n    pass\n"
                """.trimIndent()
            }
            else -> {
                """
                [gd_resource type="GDScript" format=2]

                [resource]
                script/source = "extends Node2D\nfunc _ready():\n    pass\nfunc _process(_delta):\n    pass\nfunc _physics_process(_delta):\n    pass\nfunc init(_arg1 = null, _arg2 = null, _arg3 = null, _arg4 = null):\n    pass\n"
                """.trimIndent()
            }
        }
    }

    companion object {
        private const val TAG = "RuntimePatchInjector"
    }
}
