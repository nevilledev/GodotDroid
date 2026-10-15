package org.godotengine.godot

import android.app.Activity

/**
 * Test interface matching the official Godot 4.x GodotHost contract.
 */
interface GodotHost {
    fun getActivity(): Activity?
    fun getCommandLine(): List<String>
    fun getHostPlugins(): Set<Any>
    fun onGodotSetupCompleted()
    fun onGodotMainLoopStarted()
    fun onGodotForceQuit(exitCode: Int)
}
