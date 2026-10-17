package com.godotdroid.runtime.patcher

import android.content.Context
import android.util.Log
import com.godotdroid.pck.model.PluginDependency
import com.godotdroid.pck.model.RendererType
import java.io.File

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

    private val runtimeBaseDir by lazy { File(context.filesDir, "runtime_env").apply { mkdirs() } }

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
        extraCliArgs: List<String> = emptyList()
    ): RuntimeEnvironment {
        val gameDir = File(runtimeBaseDir, gameId).apply { mkdirs() }
        val jniLibsDir = File(gameDir, "jniLibs").apply { mkdirs() }

        // 1. Generate GodotSteam stub if requested
        if (injectSteamStub) {
            SteamStubGenerator.writeStubToDirectory(gameDir, engineMajor)
        }

        // 2. Generate override.cfg
        val overrideCfgFile = File(gameDir, "override.cfg")
        if (overrideCfgFile.exists()) {
            overrideCfgFile.setWritable(true)
        }
        val overrideContent = generateOverrideCfg(
            renderer = selectedRenderer,
            mainSceneOverride = mainSceneOverride,
            injectSteamStub = injectSteamStub,
            engineMajor = engineMajor
        )
        overrideCfgFile.writeText(overrideContent, Charsets.UTF_8)
        overrideCfgFile.setReadOnly()

        // 3. Stage any available Android ARM64 GDExtension libraries
        stageGdExtensionLibraries(gameDir, jniLibsDir, plugins)

        // 4. Inject virtual files directly into the staged PCK ONLY when necessary (Steam stub or in-PCK plugin rewriting)
        var targetPckOrExe = pckOrExeFile
        val needsPckPatching = injectSteamStub || plugins.any { it.configPath.isNotBlank() }

        if (needsPckPatching) {
            try {
                val filesToInject = mutableMapOf<String, ByteArray>()
                filesToInject["res://override.cfg"] = overrideContent.toByteArray(Charsets.UTF_8)
                if (injectSteamStub) {
                    val stubContent = SteamStubGenerator.generateStub(engineMajor)
                    filesToInject["res://godotdroid_steam_stub.gd"] = stubContent.toByteArray(Charsets.UTF_8)
                }

                // In-PCK .gdextension & .gdnlib rewriting
                val pluginCompat = PluginCompatManager(context)
                for (plugin in plugins) {
                    if (plugin.configPath.isNotBlank()) {
                        var originalConfigText = ""
                        try {
                            java.io.RandomAccessFile(pckOrExeFile, "r").use { raf ->
                                val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
                                val inspectResult = inspector.inspect(raf.channel, raf.length())
                                val entry = inspectResult.files.find { it.path == plugin.configPath }
                                if (entry != null) {
                                    originalConfigText = String(inspector.extractFileBytes(raf.channel, entry), Charsets.UTF_8)
                                }
                            }
                        } catch (_: Exception) {}

                        val patchedConfig = pluginCompat.patchExtensionConfigContent(
                            originalIni = originalConfigText,
                            isGodot4 = (engineMajor >= 4),
                            androidSoNames = plugin.androidLibraries.values.map { it.substringAfterLast('/') }.ifEmpty { listOf("${plugin.name}.so") }
                        )
                        filesToInject[plugin.configPath] = patchedConfig.toByteArray(Charsets.UTF_8)
                    }
                }

                val stagedPck = File(gameDir, "runtime_staged.pck")
                if (stagedPck.exists()) stagedPck.setWritable(true)
                val patcher = com.godotdroid.pck.patcher.PckVirtualPatcher()
                patcher.patchPck(pckOrExeFile, stagedPck, filesToInject)
                stagedPck.setReadOnly()
                if (stagedPck.exists() && stagedPck.length() > 0) {
                    targetPckOrExe = stagedPck
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtual PCK patch skipped or failed: ${e.message}")
                targetPckOrExe = pckOrExeFile
            }
        } else {
            Log.i(TAG, "Fast-path: launching game package directly via --main-pack without disk duplication")
        }

        // 5. Build CLI arguments
        val cliArgs = buildGodotCliArgs(
            pckOrExeFile = targetPckOrExe,
            selectedRenderer = selectedRenderer,
            workingDir = gameDir,
            engineMajor = engineMajor,
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
        engineMajor: Int = 4
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
        }

        if (!mainSceneOverride.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("[application]")
            sb.appendLine("run/main_scene=\"$mainSceneOverride\"")
        }

        if (injectSteamStub) {
            sb.appendLine()
            sb.appendLine("[autoload]")
            sb.appendLine("Steam=\"*res://godotdroid_steam_stub.gd\"")
        }

        return sb.toString()
    }

    /**
     * Builds command-line arguments array for Godot engine initialization.
     */
    fun buildGodotCliArgs(
        pckOrExeFile: File,
        selectedRenderer: RendererType,
        workingDir: File,
        engineMajor: Int = 4,
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
        val pluginCacheDir = File(context.filesDir, "plugin_cache")
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

    companion object {
        private const val TAG = "RuntimePatchInjector"
    }
}
