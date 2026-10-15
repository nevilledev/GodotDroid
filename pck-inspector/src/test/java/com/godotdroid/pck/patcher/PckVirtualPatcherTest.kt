package com.godotdroid.pck.patcher

import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.inspector.PckConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PckVirtualPatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val inspector = PckBinaryInspector()
    private val patcher = PckVirtualPatcher(inspector)

    @Test
    fun testPatchFormatV2_InjectOverrideAndSteamStub() {
        // 1. Build synthetic PCK Format v2 (Godot 4.2)
        val projectContent = "[application]\nconfig/name=\"Original Game\"\n"
        val projectBytes = projectContent.toByteArray(Charsets.UTF_8)
        val projectPath = "res://project.godot"
        val projectPathBytes = projectPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((projectPathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val dataOffset = (headerSize + dirSize).toLong()

        val totalSize = dataOffset + projectBytes.size
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2) // v2
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE) // packFlags
        bb.putLong(0L) // fileBase
        bb.put(ByteArray(64))

        // Directory
        bb.putInt(1) // 1 file
        bb.putInt(projectPathBytes.size)
        bb.put(projectPathBytes)
        val pad = paddedPathLen - projectPathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(dataOffset)
        bb.putLong(projectBytes.size.toLong())
        bb.put(ByteArray(16) { 0x11.toByte() })
        bb.putInt(0) // flags

        // Payload
        bb.put(projectBytes)

        val originalPck = tempFolder.newFile("game_v2.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // 2. Patch PCK with override.cfg and godotdroid_steam_stub.gd
        val overrideContent = "[rendering]\nrenderer/rendering_method=\"mobile\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)

        val steamStubContent = "extends Node\nfunc steamInit():\n    return {\"status\": 1}\n"
        val steamBytes = steamStubContent.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("game_v2_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf(
                "res://override.cfg" to overrideBytes,
                "res://godotdroid_steam_stub.gd" to steamBytes
            )
        )

        assertEquals(3, patchResult.totalFilesCount)
        assertEquals(2, patchResult.injectedFilesCount)

        // 3. Inspect and verify patched PCK
        val inspectResult = inspector.inspect(patchedPck)
        assertEquals(2, inspectResult.header.formatVersion)
        assertEquals(3, inspectResult.files.size)

        val projectEntry = inspectResult.files.find { it.path == "res://project.godot" }
        assertNotNull("project.godot must be preserved", projectEntry)
        val extractedProjectBytes = inspector.extractFileBytes(patchedPck, projectEntry!!)
        assertArrayEquals(projectBytes, extractedProjectBytes)

        val overrideEntry = inspectResult.files.find { it.path == "res://override.cfg" }
        assertNotNull("override.cfg must be injected", overrideEntry)
        val extractedOverrideBytes = inspector.extractFileBytes(patchedPck, overrideEntry!!)
        assertArrayEquals(overrideBytes, extractedOverrideBytes)

        val steamEntry = inspectResult.files.find { it.path == "res://godotdroid_steam_stub.gd" }
        assertNotNull("steam stub must be injected", steamEntry)
        val extractedSteamBytes = inspector.extractFileBytes(patchedPck, steamEntry!!)
        assertArrayEquals(steamBytes, extractedSteamBytes)
    }

    @Test
    fun testPatchFormatV3_InjectOverrideAndSteamStub() {
        // 1. Build synthetic PCK Format v3 (Godot 4.4)
        val mainSceneContent = "[gd_scene format=3]\n"
        val sceneBytes = mainSceneContent.toByteArray(Charsets.UTF_8)
        val scenePath = "res://main.tscn"
        val scenePathBytes = scenePath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((scenePathBytes.size + 3) / 4) * 4

        // Layout:
        // Header (40 bytes):
        //   magic(4), format(4), major(4), minor(4), patch(4), flags(4), fileBase(8), dirOffset(8)
        // Data follows header:
        //   sceneBytes
        // Directory follows data:
        //   dirOffset = 40 + sceneBytes.size
        val headerSize = 40
        val dataOffset = headerSize.toLong()
        val dirOffset = dataOffset + sceneBytes.size
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)

        val totalSize = dirOffset + dirSize
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header v3
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(3) // v3
        bb.putInt(4)
        bb.putInt(4)
        bb.putInt(0)
        bb.putInt(0) // flags
        bb.putLong(0L) // fileBase
        bb.putLong(dirOffset) // dirOffset

        // Payload data immediately after header
        bb.put(sceneBytes)

        // Directory table at dirOffset
        bb.putInt(1) // 1 file
        bb.putInt(scenePathBytes.size)
        bb.put(scenePathBytes)
        val pad = paddedPathLen - scenePathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(dataOffset) // rawOffset = dataOffset (fileBase is 0)
        bb.putLong(sceneBytes.size.toLong())
        bb.put(ByteArray(16) { 0x22.toByte() })
        bb.putInt(0)

        val originalPck = tempFolder.newFile("game_v3.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // 2. Patch PCK
        val overrideContent = "[rendering]\nrenderer/rendering_method=\"gl_compatibility\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)

        val steamContent = "extends Node\nfunc isSteamRunning(): return true\n"
        val steamBytes = steamContent.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("game_v3_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf(
                "res://override.cfg" to overrideBytes,
                "res://godotdroid_steam_stub.gd" to steamBytes
            )
        )

        assertEquals(3, patchResult.totalFilesCount)
        assertEquals(2, patchResult.injectedFilesCount)

        // 3. Inspect and verify patched v3 PCK
        val inspectResult = inspector.inspect(patchedPck)
        assertEquals(3, inspectResult.header.formatVersion)
        assertEquals(3, inspectResult.files.size)

        val sceneEntry = inspectResult.files.find { it.path == "res://main.tscn" }
        assertNotNull("main.tscn must be preserved", sceneEntry)
        val extractedSceneBytes = inspector.extractFileBytes(patchedPck, sceneEntry!!)
        assertArrayEquals(sceneBytes, extractedSceneBytes)

        val overrideEntry = inspectResult.files.find { it.path == "res://override.cfg" }
        assertNotNull("override.cfg must be injected", overrideEntry)
        val extractedOverrideBytes = inspector.extractFileBytes(patchedPck, overrideEntry!!)
        assertArrayEquals(overrideBytes, extractedOverrideBytes)

        val steamEntry = inspectResult.files.find { it.path == "res://godotdroid_steam_stub.gd" }
        assertNotNull("steam stub must be injected", steamEntry)
        val extractedSteamBytes = inspector.extractFileBytes(patchedPck, steamEntry!!)
        assertArrayEquals(steamBytes, extractedSteamBytes)
    }
}
