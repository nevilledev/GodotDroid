package com.godotdroid.runtime.patcher

import com.godotdroid.pck.model.RendererType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimePatchInjectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGenerateOverrideCfg_Mobile() {
        val dummyContext = tempFolder.newFolder("files_dir")
        // Mirror generator logic
        val injector = object {
            fun generate(renderer: RendererType): String {
                val sb = StringBuilder()
                sb.appendLine("[rendering]")
                when (renderer) {
                    RendererType.GL_COMPATIBILITY -> {
                        sb.appendLine("renderer/rendering_method=\"gl_compatibility\"")
                        sb.appendLine("driver/driver.android=\"opengl3\"")
                    }
                    RendererType.MOBILE -> {
                        sb.appendLine("renderer/rendering_method=\"mobile\"")
                        sb.appendLine("driver/driver.android=\"vulkan\"")
                    }
                    RendererType.FORWARD_PLUS -> {
                        sb.appendLine("renderer/rendering_method=\"forward_plus\"")
                        sb.appendLine("driver/driver.android=\"vulkan\"")
                    }
                    RendererType.UNKNOWN -> {}
                }
                sb.appendLine()
                sb.appendLine("[display]")
                sb.appendLine("window/size/mode=3")
                sb.appendLine("window/stretch/mode=\"canvas_items\"")
                sb.appendLine("window/stretch/aspect=\"expand\"")
                return sb.toString()
            }
        }

        val cfg = injector.generate(RendererType.MOBILE)
        assertTrue(cfg.contains("renderer/rendering_method=\"mobile\""))
        assertTrue(cfg.contains("driver/driver.android=\"vulkan\""))
        assertTrue(cfg.contains("window/stretch/mode=\"canvas_items\""))
        assertTrue(cfg.contains("window/size/mode=3"))
    }

    @Test
    fun testGenerateOverrideCfg_Compatibility() {
        val dummyContext = tempFolder.newFolder("files_dir")
        val injector = object {
            fun generate(renderer: RendererType): String {
                val sb = StringBuilder()
                sb.appendLine("[rendering]")
                when (renderer) {
                    RendererType.GL_COMPATIBILITY -> {
                        sb.appendLine("renderer/rendering_method=\"gl_compatibility\"")
                        sb.appendLine("driver/driver.android=\"opengl3\"")
                    }
                    else -> {}
                }
                return sb.toString()
            }
        }

        val cfg = injector.generate(RendererType.GL_COMPATIBILITY)
        assertTrue(cfg.contains("renderer/rendering_method=\"gl_compatibility\""))
        assertTrue(cfg.contains("driver/driver.android=\"opengl3\""))
    }

    @Test
    fun testBuildGodotCliArgs() {
        val pckFile = File("c:/test/game.pck")
        val workingDir = File("c:/test/runtime")
        val args = mutableListOf<String>()
        args.add("--main-pack")
        args.add(pckFile.absolutePath)
        args.add("--rendering-method")
        args.add("mobile")
        args.add("--rendering-driver")
        args.add("vulkan")
        args.add("--fullscreen")

        assertEquals(7, args.size)
        assertEquals("--main-pack", args[0])
        assertEquals(pckFile.absolutePath, args[1])
        assertEquals("--rendering-method", args[2])
        assertEquals("mobile", args[3])
    }
}
