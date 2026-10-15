package com.godotdroid.runtime.patcher

import android.app.Activity
import com.godotdroid.pck.model.RendererType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimePatchInjectorTest {

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
}
