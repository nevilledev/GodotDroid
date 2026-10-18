package org.godotengine.godot

import android.app.Activity

/**
 * Test interface matching the official Godot 4.x GodotHost contract,
 * including primitive return types that test proxy unboxing safety.
 */
interface GodotHost {
    fun getActivity(): Activity?
    fun getCommandLine(): List<String>
    fun getHostPlugins(): Set<Any>
    fun getGodot(): Any?
    fun onGodotSetupCompleted()
    fun onGodotMainLoopStarted()
    fun onGodotForceQuit(exitCode: Int): Boolean
    fun onNewGodotInstanceRequested(args: Array<String>): Int
    fun supportsFeature(feature: String): Boolean
}
