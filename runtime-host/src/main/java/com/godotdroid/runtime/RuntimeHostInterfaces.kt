package com.godotdroid.runtime

import com.godotdroid.pck.model.RendererType
import java.io.File

/**
 * Configuration parameters for launching a Godot game inside the isolated process.
 */
data class GodotLaunchConfig(
    val pckOrExeFile: File,
    val gameTitle: String,
    val mainSceneOverride: String? = null,
    val rendererOverride: RendererType? = null,
    val additionalCommandLineArgs: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
    val enableTouchOverlay: Boolean = true,
    val immersiveFullScreen: Boolean = true
)

/**
 * Runtime execution state lifecycle.
 */
sealed class GodotRuntimeState {
    data object Idle : GodotRuntimeState()
    data class Initializing(val message: String) : GodotRuntimeState()
    data class Running(val pid: Int, val renderer: RendererType) : GodotRuntimeState()
    data object Paused : GodotRuntimeState()
    data class Stopped(val exitCode: Int) : GodotRuntimeState()
    data class Error(val message: String, val throwable: Throwable? = null) : GodotRuntimeState()
}

/**
 * Core interface for the isolated Godot process runner host.
 */
interface IGodotRuntimeHost {
    val currentState: GodotRuntimeState

    /**
     * Prepares and starts the game inside the isolated :godot_runner process.
     */
    suspend fun launchGame(config: GodotLaunchConfig): Result<Unit>

    /**
     * Pauses the running game engine.
     */
    fun pause()

    /**
     * Resumes the paused game engine.
     */
    fun resume()

    /**
     * Gracefully terminates the running engine instance.
     */
    fun stop()

    /**
     * Force kills the isolated process if unresponsive.
     */
    fun kill()
}
