package com.godotdroid.runtime.patcher

import android.app.Activity
import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.PckFileEntry
import com.godotdroid.pck.model.RendererType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RuntimePatchInjectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val injector = RuntimePatchInjector(Activity())

    @Test
    fun testGenerateOverrideCfg_Godot4_Mobile() {
        val cfg = injector.generateOverrideCfg(
            renderer = RendererType.MOBILE,
            mainSceneOverride = "res://Main.tscn",
            injectSteamStub = true,
            engineMajor = 4
        )

        // Godot 4 specifics
        assertTrue(cfg.contains("renderer/rendering_method=\"mobile\""))
        assertTrue(cfg.contains("driver/driver.android=\"vulkan\""))
        assertTrue(cfg.contains("textures/vram_compression/import_s3tc_bptc=true"))
        assertTrue(cfg.contains("textures/vram_compression/import_etc2_astc=true"))
        assertTrue(cfg.contains("window/stretch/mode=\"canvas_items\""))
        assertTrue(cfg.contains("run/main_scene=\"res://Main.tscn\""))
        assertTrue(cfg.contains("Steam=\"*res://godotdroid_steam_stub.gd\""))

        // Must not contain Godot 3 keys
        assertFalse(cfg.contains("quality/driver/driver_name"))
        assertFalse(cfg.contains("window/stretch/mode=\"2d\""))
    }

    @Test
    fun testGenerateOverrideCfg_Godot4_Compatibility() {
        val cfg = injector.generateOverrideCfg(
            renderer = RendererType.GL_COMPATIBILITY,
            engineMajor = 4
        )

        assertTrue(cfg.contains("renderer/rendering_method=\"gl_compatibility\""))
        assertTrue(cfg.contains("driver/driver.android=\"opengl3\""))
        assertTrue(cfg.contains("window/stretch/mode=\"canvas_items\""))
    }

    @Test
    fun testGenerateOverrideCfg_Godot3_GLES3() {
        val cfg = injector.generateOverrideCfg(
            renderer = RendererType.GLES3,
            mainSceneOverride = "res://MainScene.tscn",
            injectSteamStub = true,
            engineMajor = 3
        )

        // Godot 3 specifics
        assertTrue(cfg.contains("quality/driver/driver_name=\"GLES3\""))
        assertTrue(cfg.contains("quality/driver/fallback_to_gles2=true"))
        assertTrue(cfg.contains("vram_compression/import_etc2=true"))
        assertTrue(cfg.contains("vram_compression/import_s3tc=true"))
        assertTrue(cfg.contains("window/handheld/orientation=\"landscape\""))
        assertTrue(cfg.contains("window/stretch/mode=\"2d\""))
        assertTrue(cfg.contains("run/main_scene=\"res://MainScene.tscn\""))
        assertTrue(cfg.contains("Steam=\"*res://godotdroid_steam_stub.gd\""))
        assertTrue(cfg.contains("output_latency=50"))
        assertTrue(cfg.contains("output_latency.web=50"))

        val cfgWithDriver = injector.generateOverrideCfg(
            renderer = RendererType.GLES3,
            engineMajor = 3,
            audioDriver = "Dummy"
        )
        assertTrue(cfgWithDriver.contains("driver=\"Dummy\""))
        assertTrue(cfgWithDriver.contains("audio/driver=\"Dummy\""))

        // Must never contain Godot 4 keys
        assertFalse(cfg.contains("renderer/rendering_method"))
        assertFalse(cfg.contains("window/stretch/mode=\"canvas_items\""))
        assertFalse(cfg.contains("import_s3tc_bptc"))
    }

    @Test
    fun testGenerateOverrideCfg_Godot3_GLES2() {
        val cfg = injector.generateOverrideCfg(
            renderer = RendererType.GLES2,
            engineMajor = 3
        )

        assertTrue(cfg.contains("quality/driver/driver_name=\"GLES2\""))
        assertTrue(cfg.contains("quality/driver/fallback_to_gles2=true"))
        assertTrue(cfg.contains("window/stretch/mode=\"2d\""))
        assertFalse(cfg.contains("renderer/rendering_method"))
    }

    @Test
    fun testBuildGodotCliArgs_Godot4() {
        val pckFile = File("c:/test/game.pck")
        val workingDir = File("c:/test/runtime")

        val args = injector.buildGodotCliArgs(
            pckOrExeFile = pckFile,
            selectedRenderer = RendererType.MOBILE,
            workingDir = workingDir,
            engineMajor = 4
        )

        assertEquals("--main-pack", args[0])
        assertEquals(pckFile.absolutePath, args[1])
        assertEquals("--rendering-method", args[2])
        assertEquals("mobile", args[3])
        assertEquals("--rendering-driver", args[4])
        assertEquals("vulkan", args[5])
        assertEquals("--fullscreen", args[6])
    }

    @Test
    fun testBuildGodotCliArgs_Godot3() {
        val pckFile = File("c:/test/game.pck")
        val workingDir = File("c:/test/runtime")

        val args = injector.buildGodotCliArgs(
            pckOrExeFile = pckFile,
            selectedRenderer = RendererType.GLES3,
            workingDir = workingDir,
            engineMajor = 3
        )

        assertEquals("--main-pack", args[0])
        assertEquals(pckFile.absolutePath, args[1])
        assertEquals("--video-driver", args[2])
        assertEquals("GLES3", args[3])
        assertEquals("--fullscreen", args[4])

        // Must NOT pass --rendering-method to Godot 3
        assertFalse(args.contains("--rendering-method"))
        assertFalse(args.contains("--rendering-driver"))
    }

    @Test
    fun testBuildGodotCliArgs_Godot3_GLES2() {
        val pckFile = File("c:/test/game.pck")
        val workingDir = File("c:/test/runtime")

        val args = injector.buildGodotCliArgs(
            pckOrExeFile = pckFile,
            selectedRenderer = RendererType.GLES2,
            workingDir = workingDir,
            engineMajor = 3
        )

        assertEquals("--main-pack", args[0])
        assertEquals(pckFile.absolutePath, args[1])
        assertEquals("--video-driver", args[2])
        assertEquals("GLES2", args[3])
        assertFalse(args.contains("--rendering-method"))
    }

    @Test
    fun testBuildGodotCliArgs_Godot3_WithAudioDriver() {
        val pckFile = File("c:/test/game.pck")
        val workingDir = File("c:/test/runtime")

        val args = injector.buildGodotCliArgs(
            pckOrExeFile = pckFile,
            selectedRenderer = RendererType.GLES3,
            workingDir = workingDir,
            engineMajor = 3,
            audioDriver = "Dummy"
        )

        assertEquals("--main-pack", args[0])
        assertEquals(pckFile.absolutePath, args[1])
        assertEquals("--video-driver", args[2])
        assertEquals("GLES3", args[3])
        assertEquals("--fullscreen", args[4])
        assertEquals("--audio-driver", args[5])
        assertEquals("Dummy", args[6])
    }

    private fun createMinimalPck(file: File, major: Int = 4, minor: Int = 3) {
        val bb = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2) // format v2
        bb.putInt(major)
        bb.putInt(minor)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(128L) // fileBase = 128
        bb.put(ByteArray(64)) // reserved
        bb.putInt(0) // fileCount = 0
        while (bb.position() < 128) bb.put(0.toByte())
        FileOutputStream(file).use { it.write(bb.array(), 0, 128) }
    }

    @Test
    fun testPrepareRuntimeEnvironment_CopiesSiblingPcksAndDlls() {
        val gameFolder = tempFolder.newFolder("MultiPackGame")
        val mainPck = File(gameFolder, "game.pck")
        val siblingPck = File(gameFolder, "love.pck")
        val siblingDll = File(gameFolder, "optimizations.dll")
        val siblingSo = File(gameFolder, "libextra.so")

        createMinimalPck(mainPck, 4, 3)
        createMinimalPck(siblingPck, 4, 5) // test version clamping on sibling PCK (4.5 -> 4.3)
        siblingDll.writeText("fake dll binary payload", Charsets.UTF_8)
        siblingSo.writeText("fake so binary payload", Charsets.UTF_8)

        val gameId = "test_multi_pack_${System.currentTimeMillis()}"
        val env = injector.prepareRuntimeEnvironment(
            gameId = gameId,
            pckOrExeFile = mainPck,
            selectedRenderer = RendererType.MOBILE,
            engineMajor = 4,
            engineMinor = 3
        )

        try {
            // Sibling PCK must be copied to runtime staging dir
            val stagedSiblingPck = File(env.runtimeDir, "love.pck")
            assertTrue("Sibling PCK love.pck must be copied to runtime staging dir", stagedSiblingPck.exists())

            // Sibling PCK version should be clamped to target engine (4.3)
            val inspector = com.godotdroid.pck.inspector.PckBinaryInspector()
            val siblingHeader = inspector.inspect(stagedSiblingPck).header
            assertEquals(4, siblingHeader.engineMajor)
            assertEquals(3, siblingHeader.engineMinor)

            // Companion DLL must be copied to jniLibs and runtimeDir
            val stagedDllInJniLibs = File(env.jniLibsDir, "optimizations.dll")
            val stagedDllInRuntimeDir = File(env.runtimeDir, "optimizations.dll")
            assertTrue("optimizations.dll must be copied to jniLibs", stagedDllInJniLibs.exists())
            assertTrue("optimizations.dll must be copied to runtimeDir", stagedDllInRuntimeDir.exists())
            assertEquals("fake dll binary payload", stagedDllInJniLibs.readText(Charsets.UTF_8))

            // Companion SO must be copied to jniLibs and runtimeDir
            val stagedSoInJniLibs = File(env.jniLibsDir, "libextra.so")
            val stagedSoInRuntimeDir = File(env.runtimeDir, "libextra.so")
            assertTrue("libextra.so must be copied to jniLibs", stagedSoInJniLibs.exists())
            assertTrue("libextra.so must be copied to runtimeDir", stagedSoInRuntimeDir.exists())
            assertEquals("fake so binary payload", stagedSoInJniLibs.readText(Charsets.UTF_8))

            // Main staged pack must exist alongside siblings
            val stagedMainPck = File(env.runtimeDir, "runtime_staged.pck")
            assertTrue("Staged main PCK must exist in runtime staging dir", stagedMainPck.exists())

            // Companion mount script must be generated and registered in override.cfg
            val mountScript = File(env.runtimeDir, "godotdroid_companion_mount.gd")
            assertTrue("godotdroid_companion_mount.gd must be generated", mountScript.exists())
            assertTrue("mount script must contain companion pack name", mountScript.readText().contains("love.pck"))

            val overrideCfgText = env.overrideCfgFile.readText()
            assertTrue(
                "override.cfg must register GodotDroidCompanionMount autoload",
                overrideCfgText.contains("GodotDroidCompanionMount=\"*res://godotdroid_companion_mount.gd\"")
            )
        } finally {
            injector.cleanupRuntimeEnvironment(gameId)
            assertFalse("Runtime directory must be cleaned up", env.runtimeDir.exists())
        }
    }

    @Test
    fun testResolveProjectBinaryOverrides_UidAutoloadsAndScenes() {
        // Construct a synthetic project.binary payload containing UID autoloads, main_scene, and bus layout
        val dummyProjBinary = ("autoload/Decl\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0013\u0000\u0000\u0000*uid://otuuvm4j2vdo\u0000" +
                "autoload/UI\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0014\u0000\u0000\u0000*uid://bf145sr1xf432\u0000" +
                "autoload/Sounds\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0014\u0000\u0000\u0000*uid://bivvjsjhtvntn\u0000" +
                "autoload/Text\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0013\u0000\u0000\u0000*uid://270vke52ydme\u0000" +
                "application/run/main_scene\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0013\u0000\u0000\u0000uid://dqdpkdp7oq5xg\u0000" +
                "audio/buses/default_bus_layout\u001c\u0000\u0000\u0000\u0004\u0000\u0000\u0000\u0012\u0000\u0000\u0000uid://wl1gjmiml0bm\u0000").toByteArray(Charsets.ISO_8859_1)

        val files = listOf(
            PckFileEntry("res://src/global/decl.tscn.remap", 0, 100, ByteArray(16), 0, "src/global/decl.tscn.remap"),
            PckFileEntry("res://src/global/decl.gd.remap", 0, 100, ByteArray(16), 0, "src/global/decl.gd.remap"),
            PckFileEntry("res://src/ui/ui.gd.remap", 0, 100, ByteArray(16), 0, "src/ui/ui.gd.remap"),
            PckFileEntry("res://src/global/sounds.gd.remap", 0, 100, ByteArray(16), 0, "src/global/sounds.gd.remap"),
            PckFileEntry("res://src/global/text.gd.remap", 0, 100, ByteArray(16), 0, "src/global/text.gd.remap"),
            PckFileEntry("res://src/main.tscn.remap", 0, 100, ByteArray(16), 0, "src/main.tscn.remap"),
            PckFileEntry("res://src/default_bus_layout.tres.remap", 0, 100, ByteArray(16), 0, "src/default_bus_layout.tres.remap")
        )

        val overrides = injector.resolveProjectBinaryOverrides(dummyProjBinary, files, null)

        assertEquals("*res://src/global/decl.tscn", overrides.autoloads["Decl"])
        assertEquals("*res://src/ui/ui.gd", overrides.autoloads["UI"])
        assertEquals("*res://src/global/sounds.gd", overrides.autoloads["Sounds"])
        assertEquals("*res://src/global/text.gd", overrides.autoloads["Text"])
        assertEquals("res://src/main.tscn", overrides.mainScene)
        assertEquals("res://src/default_bus_layout.tres", overrides.busLayout)

        // Verify override.cfg generation includes all resolved items
        val cfg = injector.generateOverrideCfg(
            renderer = RendererType.GL_COMPATIBILITY,
            mainSceneOverride = overrides.mainScene,
            busLayoutOverride = overrides.busLayout,
            extraAutoloads = overrides.autoloads,
            engineMajor = 4
        )

        assertTrue(cfg.contains("Decl=\"*res://src/global/decl.tscn\""))
        assertTrue(cfg.contains("UI=\"*res://src/ui/ui.gd\""))
        assertTrue(cfg.contains("Sounds=\"*res://src/global/sounds.gd\""))
        assertTrue(cfg.contains("Text=\"*res://src/global/text.gd\""))
        assertTrue(cfg.contains("run/main_scene=\"res://src/main.tscn\""))
        assertTrue(cfg.contains("buses/default_bus_layout=\"res://src/default_bus_layout.tres\""))
    }
}
