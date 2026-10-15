package com.godotdroid.runtime.patcher

import android.app.Activity
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.loader.DynamicGodotLoader
import org.godotengine.godot.GodotHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginCompatTest {

    @Test
    fun testSteamStubGeneratorGodot4() {
        val stub = SteamStubGenerator.generateStub(engineMajor = 4)

        // Verify Godot 4 typed signals and methods
        assertTrue(stub.contains("extends Node"))
        assertTrue(stub.contains("signal user_stats_received"))
        assertTrue(stub.contains("signal overlay_toggled"))
        assertTrue(stub.contains("func steamInit(retrieve_stats: bool = true) -> Dictionary:"))
        assertTrue(stub.contains("func steamInitEx(app_id: int = 480, embed_callbacks: bool = true) -> Dictionary:"))
        assertTrue(stub.contains("func isSteamRunning() -> bool:"))
        assertTrue(stub.contains("func getSteamID() -> int:"))
        assertTrue(stub.contains("76561197960287930"))
        assertTrue(stub.contains("func getPersonaName() -> String:"))
        assertTrue(stub.contains("\"GodotDroidPlayer\""))
        assertTrue(stub.contains("func getAchievement(name: String) -> Dictionary:"))
        assertTrue(stub.contains("func run_callbacks() -> void:"))
    }

    @Test
    fun testSteamStubGeneratorGodot3() {
        val stub = SteamStubGenerator.generateStub(engineMajor = 3)

        // Verify Godot 3 untyped GDScript syntax
        assertTrue(stub.contains("extends Node"))
        assertTrue(stub.contains("func steamInit(retrieve_stats = true):"))
        assertTrue(stub.contains("func steamInitEx(app_id = 480, embed_callbacks = true):"))
        assertTrue(stub.contains("func isSteamRunning():"))
        assertTrue(stub.contains("func getSteamID():"))
        assertFalse(stub.contains("-> Dictionary:"))
        assertFalse(stub.contains("-> bool:"))
    }

    @Test
    fun testGenerateOverrideCfgWithSteamStub() {
        // Test with Steam Stub enabled
        val patchInjector = RuntimePatchInjector(Activity())
        val cfgWithSteam = patchInjector.generateOverrideCfg(
            renderer = RendererType.MOBILE,
            mainSceneOverride = "res://scenes/Main.tscn",
            injectSteamStub = true
        )

        assertTrue(cfgWithSteam.contains("[rendering]"))
        assertTrue(cfgWithSteam.contains("renderer/rendering_method=\"mobile\""))
        assertTrue(cfgWithSteam.contains("driver/driver.android=\"vulkan\""))
        assertTrue(cfgWithSteam.contains("[application]"))
        assertTrue(cfgWithSteam.contains("run/main_scene=\"res://scenes/Main.tscn\""))
        assertTrue(cfgWithSteam.contains("[autoload]"))
        assertTrue(cfgWithSteam.contains("Steam=\"*res://godotdroid_steam_stub.gd\""))
    }

    @Test
    fun testGenerateOverrideCfgWithoutSteamStub() {
        // Test with Steam Stub disabled and GL_COMPATIBILITY
        val patchInjector = RuntimePatchInjector(Activity())
        val cfgWithoutSteam = patchInjector.generateOverrideCfg(
            renderer = RendererType.GL_COMPATIBILITY,
            mainSceneOverride = null,
            injectSteamStub = false
        )

        assertTrue(cfgWithoutSteam.contains("renderer/rendering_method=\"gl_compatibility\""))
        assertTrue(cfgWithoutSteam.contains("driver/driver.android=\"opengl3\""))
        assertFalse(cfgWithoutSteam.contains("[autoload]"))
        assertFalse(cfgWithoutSteam.contains("godotdroid_steam_stub.gd"))
    }

    @Test
    fun testGodotHostDynamicProxyReflection() {
        val dummyActivity = Activity()
        val dummyCliArgs = arrayOf(
            "--main-pack",
            "/data/user/0/com.godotdroid/files/test.pck",
            "--rendering-method",
            "gl_compatibility",
            "--rendering-driver",
            "opengl3"
        )

        val loader = DynamicGodotLoader(dummyActivity)
        val classLoader = GodotHost::class.java.classLoader
        assertNotNull(classLoader)

        val proxy = loader.createGodotHostProxy(
            classLoader = classLoader!!,
            activity = dummyActivity,
            cliArgs = dummyCliArgs,
            godotInstanceProvider = { null }
        )

        assertNotNull("Proxy should be created successfully", proxy)
        assertTrue("Proxy should implement GodotHost", proxy is GodotHost)

        val godotHost = proxy as GodotHost
        assertEquals(dummyActivity, godotHost.getActivity())

        val cliList = godotHost.getCommandLine()
        assertEquals(6, cliList.size)
        assertEquals("--main-pack", cliList[0])
        assertEquals("/data/user/0/com.godotdroid/files/test.pck", cliList[1])
        assertEquals("--rendering-method", cliList[2])
        assertEquals("gl_compatibility", cliList[3])
        assertEquals("--rendering-driver", cliList[4])
        assertEquals("opengl3", cliList[5])

        assertTrue(godotHost.getHostPlugins().isEmpty())

        // Ensure lifecycle notifications do not throw exceptions
        godotHost.onGodotSetupCompleted()
        godotHost.onGodotMainLoopStarted()
        godotHost.onGodotForceQuit(0)
    }
}
