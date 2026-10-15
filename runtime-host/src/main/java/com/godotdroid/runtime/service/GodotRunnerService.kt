package com.godotdroid.runtime.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.godotdroid.runtime.GodotLaunchConfig
import com.godotdroid.runtime.GodotRuntimeState
import com.godotdroid.runtime.IGodotRuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service running inside isolated process android:process=":godot_runner".
 * Isolates game engine execution, native memory crashes, and signal traps from the main UI process.
 */
class GodotRunnerService : Service(), IGodotRuntimeHost {

    private val binder = RunnerBinder()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow<GodotRuntimeState>(GodotRuntimeState.Idle)
    val stateFlow: StateFlow<GodotRuntimeState> = _state.asStateFlow()

    override val currentState: GodotRuntimeState
        get() = _state.value

    inner class RunnerBinder : Binder() {
        val service: GodotRunnerService get() = this@GodotRunnerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override suspend fun launchGame(config: GodotLaunchConfig): Result<Unit> {
        Log.i(TAG, "Preparing to launch game: ${config.gameTitle} (${config.pckOrExeFile.name})")
        _state.value = GodotRuntimeState.Initializing("Setting up Godot runtime environment...")

        // Phase 2 will load libgodot_android.so and start GodotActivity/SurfaceView
        _state.value = GodotRuntimeState.Running(
            pid = android.os.Process.myPid(),
            renderer = config.rendererOverride ?: com.godotdroid.pck.model.RendererType.MOBILE
        )
        return Result.success(Unit)
    }

    override fun pause() {
        if (_state.value is GodotRuntimeState.Running) {
            Log.i(TAG, "Pausing Godot runtime")
            _state.value = GodotRuntimeState.Paused
        }
    }

    override fun resume() {
        if (_state.value is GodotRuntimeState.Paused) {
            Log.i(TAG, "Resuming Godot runtime")
            _state.value = GodotRuntimeState.Running(
                pid = android.os.Process.myPid(),
                renderer = com.godotdroid.pck.model.RendererType.MOBILE
            )
        }
    }

    override fun stop() {
        Log.i(TAG, "Stopping Godot runtime")
        _state.value = GodotRuntimeState.Stopped(exitCode = 0)
        stopSelf()
    }

    override fun kill() {
        Log.w(TAG, "Force killing isolated :godot_runner process")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "GodotRunnerService"
    }
}
