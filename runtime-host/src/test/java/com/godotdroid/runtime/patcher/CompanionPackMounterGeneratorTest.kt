package com.godotdroid.runtime.patcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPackMounterGeneratorTest {

    @Test
    fun testGenerateMountScript_Godot4() {
        val script = CompanionPackMounterGenerator.generateMountScript(
            companionPcks = listOf("love.pck", "dlc1.pck"),
            mainPackName = "game.pck",
            engineMajor = 4
        )

        // Must declare known packs
        assertTrue(script.contains("\"love.pck\""))
        assertTrue(script.contains("\"dlc1.pck\""))

        // Must use Godot 4 DirAccess
        assertTrue(script.contains("DirAccess.open(\".\")"))
        assertTrue(script.contains("func _mount_pack(pack_path: String) -> void:"))
        assertTrue(script.contains("ProjectSettings.load_resource_pack(pack_path)"))

        // Must not use Godot 3 Directory.new()
        assertFalse(script.contains("Directory.new()"))
    }

    @Test
    fun testGenerateMountScript_Godot3() {
        val script = CompanionPackMounterGenerator.generateMountScript(
            companionPcks = listOf("companion.pck"),
            mainPackName = "main.pck",
            engineMajor = 3
        )

        assertTrue(script.contains("\"companion.pck\""))

        // Must use Godot 3 Directory.new()
        assertTrue(script.contains("Directory.new()"))
        assertTrue(script.contains("func _mount_pack(pack_path):"))
        assertTrue(script.contains("ProjectSettings.load_resource_pack(pack_path)"))

        // Must not use Godot 4 DirAccess
        assertFalse(script.contains("DirAccess.open"))
    }

    @Test
    fun testGenerateMountScript_WithRuntimeDirPath() {
        val script = CompanionPackMounterGenerator.generateMountScript(
            companionPcks = listOf("love.pck"),
            mainPackName = "game.pck",
            engineMajor = 3,
            runtimeDirPath = "/data/user/0/com.godotdroid/files/runtime_env/game123"
        )

        assertTrue(script.contains("/data/user/0/com.godotdroid/files/runtime_env/game123"))
        assertTrue(script.contains("plus_file(pack_path)"))
    }
}
