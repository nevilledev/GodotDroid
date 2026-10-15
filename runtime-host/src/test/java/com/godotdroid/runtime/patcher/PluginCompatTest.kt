package com.godotdroid.runtime.patcher

import android.app.Activity
import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.loader.DynamicGodotLoader
import org.godotengine.godot.GodotHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Test
    fun testPatchExtensionConfigContent_Godot4() {
        val manager = PluginCompatManager(Activity())
        val originalIni = """
            [configuration]
            entry_symbol = "example_library_init"
            compatibility_minimum = "4.1"

            [libraries]
            windows.release.x86_64 = "res://bin/libexample.windows.template_release.x86_64.dll"
            linux.release.x86_64 = "res://bin/libexample.linux.template_release.x86_64.so"
        """.trimIndent()

        val patched = manager.patchExtensionConfigContent(
            originalIni = originalIni,
            isGodot4 = true,
            androidSoNames = listOf("libexample.android.template_release.arm64.so")
        )

        assertTrue(patched.contains("[libraries]"))
        assertTrue(patched.contains("android.release.arm64-v8a = \"res://bin/libexample.android.template_release.arm64.so\""))
        assertTrue(patched.contains("android.debug.arm64-v8a = \"res://bin/libexample.android.template_release.arm64.so\""))
        assertTrue(patched.contains("android.release.arm64 = \"res://bin/libexample.android.template_release.arm64.so\""))
        assertTrue(patched.contains("windows.release.x86_64"))
    }

    @Test
    fun testPatchExtensionConfigContent_Godot3() {
        val manager = PluginCompatManager(Activity())
        val originalIni = """
            [general]
            singleton=false
            load_once=true
            symbol_prefix="godot_"
            reloadable=true

            [entry]
            Windows.64="res://bin/win64/libplugin.dll"
            X11.64="res://bin/x11/libplugin.so"

            [dependencies]
            Windows.64=[  ]
            X11.64=[  ]
        """.trimIndent()

        val patched = manager.patchExtensionConfigContent(
            originalIni = originalIni,
            isGodot4 = false,
            androidSoNames = listOf("libplugin.so")
        )

        assertTrue(patched.contains("[entry]"))
        assertTrue(patched.contains("Android.arm64-v8a=\"res://bin/libplugin.so\""))
        assertTrue(patched.contains("Windows.64="))
    }

    @Test
    fun testInPckPluginVirtualPatching() {
        val manager = PluginCompatManager(Activity())
        val originalIni = "[libraries]\nwindows.release.x86_64=\"res://bin/libtest.dll\"\n"
        val patched = manager.patchExtensionConfigContent(originalIni, true, listOf("libtest.so"))

        val tempFolder = File(System.getProperty("java.io.tmpdir"), "godotdroid_plugin_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val sourcePck = File(tempFolder, "source.pck")
            val targetPck = File(tempFolder, "target.pck")

            val initialBytes = createSyntheticPck(mapOf("res://test.gdextension" to originalIni.toByteArray(Charsets.UTF_8)))
            sourcePck.writeBytes(initialBytes)

            val patcher = com.godotdroid.pck.patcher.PckVirtualPatcher()
            val filesToInject = mapOf("res://test.gdextension" to patched.toByteArray(Charsets.UTF_8))
            patcher.patchPck(sourcePck, targetPck, filesToInject)

            val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
            java.io.RandomAccessFile(targetPck, "r").use { raf ->
                val result = inspector.inspect(raf.channel, raf.length())
                val entry = result.files.find { it.path == "res://test.gdextension" }
                assertNotNull("Patched .gdextension should be found in target PCK", entry)
                val bytes = inspector.extractFileBytes(raf.channel, entry!!)
                val text = String(bytes, Charsets.UTF_8)
                assertTrue(text.contains("android.release.arm64-v8a = \"res://bin/libtest.so\""))
            }
        } finally {
            tempFolder.deleteRecursively()
        }
    }

    private fun createSyntheticPck(files: Map<String, ByteArray>): ByteArray {
        val headerSize = 96
        var dirSize = 4
        for ((path, _) in files) {
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val paddedPathLen = ((pathBytes.size + 3) / 4) * 4
            dirSize += 4 + paddedPathLen + 8 + 8 + 16 + 4
        }
        val dataOffset = (headerSize + dirSize).toLong()
        val totalPayloadSize = files.values.sumOf { it.size }
        val totalSize = dataOffset + totalPayloadSize
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(0L)
        bb.put(ByteArray(64))

        // Directory
        bb.putInt(files.size)
        var curOffset = dataOffset
        for ((path, bytes) in files) {
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val paddedPathLen = ((pathBytes.size + 3) / 4) * 4
            bb.putInt(pathBytes.size)
            bb.put(pathBytes)
            val pad = paddedPathLen - pathBytes.size
            if (pad > 0) bb.put(ByteArray(pad))
            bb.putLong(curOffset)
            bb.putLong(bytes.size.toLong())
            bb.put(ByteArray(16) { 0x11.toByte() })
            bb.putInt(0)
            curOffset += bytes.size
        }

        // Payloads
        for ((_, bytes) in files) {
            bb.put(bytes)
        }

        return bb.array()
    }
}
