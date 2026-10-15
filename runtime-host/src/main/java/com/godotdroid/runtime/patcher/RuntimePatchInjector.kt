package com.godotdroid.runtime.patcher

import android.content.Context
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

    private val runtimeBaseDir = File(context.filesDir, "runtime_env").apply { mkdirs() }

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
            injectSteamStub = injectSteamStub
        )
        overrideCfgFile.writeText(overrideContent, Charsets.UTF_8)
        overrideCfgFile.setReadOnly()

        // 3. Stage any available Android ARM64 GDExtension libraries
        stageGdExtensionLibraries(gameDir, jniLibsDir, plugins)

        // 4. Build CLI arguments
        val cliArgs = buildGodotCliArgs(
            pckOrExeFile = pckOrExeFile,
            selectedRenderer = selectedRenderer,
            workingDir = gameDir,
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
        injectSteamStub: Boolean = false
    ): String {
        val sb = StringBuilder()

        sb.appendLine("; GodotDroid Auto-Generated Runtime Compatibility Config")
        sb.appendLine("[rendering]")

        when (renderer) {
            RendererType.GL_COMPATIBILITY -> {
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

        sb.appendLine()
        sb.appendLine("[display]")
        sb.appendLine("window/size/mode=3 ; Fullscreen Mode on Android")
        sb.appendLine("window/stretch/mode=\"canvas_items\" ; Smooth resolution adaptation for mobile")
        sb.appendLine("window/stretch/aspect=\"expand\" ; Maintain aspect ratio without black borders")

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
        extraArgs: List<String> = emptyList()
    ): Array<String> {
        val args = mutableListOf<String>()

        // 1. Target package
        args.add("--main-pack")
        args.add(pckOrExeFile.absolutePath)

        // 2. Rendering Method & Driver
        when (selectedRenderer) {
            RendererType.GL_COMPATIBILITY -> {
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
}
