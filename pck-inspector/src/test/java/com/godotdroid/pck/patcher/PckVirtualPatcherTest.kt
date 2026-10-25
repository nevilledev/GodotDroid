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

    @Test
    fun testPatchFormatV2_WithAlignedFileBase_AndVersionClamping() {
        // 1. Build synthetic PCK Format v2 where fileBase is aligned to 256 bytes
        val scriptContent = "extends Node\nfunc _ready():\n    print(\"Hello world\")\n"
        val scriptBytes = scriptContent.toByteArray(Charsets.UTF_8)
        val scriptPath = "res://main.gd"
        val scriptPathBytes = scriptPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((scriptPathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val fileBase = 256L // Deliberately pad 256 - (96 + dirSize) bytes between dir and payload
        assertTrue(fileBase > headerSize + dirSize)
        assertEquals(0L, fileBase % 8L) // 8-byte aligned

        val totalSize = (fileBase + scriptBytes.size).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2 with Godot 4.5.0
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2) // v2
        bb.putInt(4) // major = 4
        bb.putInt(5) // minor = 5
        bb.putInt(0) // patch = 0
        bb.putInt(PckConstants.PACK_REL_FILEBASE) // packFlags
        bb.putLong(fileBase) // fileBase = 256
        bb.put(ByteArray(64))

        // Directory at offset 96
        bb.putInt(1) // 1 file
        bb.putInt(scriptPathBytes.size)
        bb.put(scriptPathBytes)
        val pad = paddedPathLen - scriptPathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(0L) // rawOffset = 0 relative to fileBase
        bb.putLong(scriptBytes.size.toLong())
        bb.put(ByteArray(16) { 0x33.toByte() })
        bb.putInt(0)

        // Fill padding up to fileBase
        val dirEnd = headerSize + dirSize
        for (i in dirEnd until fileBase.toInt()) {
            bb.put(0.toByte())
        }

        // Payload at fileBase (256)
        bb.position(fileBase.toInt())
        bb.put(scriptBytes)

        val originalPck = tempFolder.newFile("game_v2_aligned.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // Verify original inspect reads script correctly
        val inspectOrig = inspector.inspect(originalPck)
        assertEquals(4, inspectOrig.header.engineMajor)
        assertEquals(5, inspectOrig.header.engineMinor)
        assertEquals(fileBase, inspectOrig.header.effectiveFileBase)
        val origScript = inspector.extractFileBytes(originalPck, inspectOrig.files.first())
        assertArrayEquals(scriptBytes, origScript)

        // 2. Patch PCK clamping to Godot 4.3 and injecting override.cfg
        val overrideContent = "[rendering]\nrenderer/rendering_method=\"gl_compatibility\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("game_v2_aligned_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf("res://override.cfg" to overrideBytes),
            targetEngineMajor = 4,
            targetEngineMinor = 3
        )

        assertEquals(2, patchResult.totalFilesCount)
        assertEquals(1, patchResult.injectedFilesCount)

        // 3. Inspect patched PCK: verify engine version clamped to 4.3 and data extracted correctly
        val inspectResult = inspector.inspect(patchedPck)
        assertEquals("Engine major must be clamped to 4", 4, inspectResult.header.engineMajor)
        assertEquals("Engine minor must be clamped to 3", 3, inspectResult.header.engineMinor)
        assertEquals(2, inspectResult.files.size)

        val extractedScript = inspector.extractFileBytes(patchedPck, inspectResult.files.find { it.path == "res://main.gd" }!!)
        assertArrayEquals("Script payload bytes must match exactly despite alignment and dir size shift", scriptBytes, extractedScript)

        val extractedOverride = inspector.extractFileBytes(patchedPck, inspectResult.files.find { it.path == "res://override.cfg" }!!)
        assertArrayEquals("Injected override.cfg must match exactly", overrideBytes, extractedOverride)
    }

    @Test
    fun testDirectoryTableEntryLength_MatchesGodotCppReader() {
        // Build a PCK with paths of non-4-byte-aligned lengths
        // e.g. path length 7 (pad 1), 10 (pad 2), 13 (pad 3), 16 (pad 0)
        val file1Path = "res://a.txt" // len 7, padded 8, pad 1
        val file2Path = "res://ab.txt" // len 8, padded 8, pad 0
        val file3Path = "res://abc.txt" // len 9, padded 12, pad 3
        val file4Path = "res://abcd.txt" // len 10, padded 12, pad 2

        val content = "test data".toByteArray(Charsets.UTF_8)
        val basePck = tempFolder.newFile("base_test.pck")
        val bb = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(4)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(512L) // fileBase = 512
        bb.put(ByteArray(64))

        // Directory: 1 file
        bb.putInt(1)
        val p1 = "a.txt".toByteArray(Charsets.UTF_8) // 5 bytes -> padded 8
        bb.putInt(8)
        bb.put(p1)
        bb.put(ByteArray(3))
        bb.putLong(0L) // rawOffset = 0
        bb.putLong(content.size.toLong())
        bb.put(ByteArray(16))
        bb.putInt(0)

        // Pad to fileBase
        while (bb.position() < 512) bb.put(0.toByte())
        bb.position(512)
        bb.put(content)

        FileOutputStream(basePck).use { it.write(bb.array(), 0, 512 + content.size) }

        // Patch with 3 new files of odd lengths
        val patchedPck = tempFolder.newFile("patched_alignment.pck")
        patcher.patchPck(
            sourceFile = basePck,
            targetFile = patchedPck,
            injectedFiles = mapOf(
                file2Path to "22".toByteArray(),
                file3Path to "333".toByteArray(),
                file4Path to "4444".toByteArray()
            )
        )

        // Verify with exact Godot C++ reader logic
        java.io.RandomAccessFile(patchedPck, "r").use { raf ->
            val ch = raf.channel
            val headerBuf = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(headerBuf)
            headerBuf.flip()
            headerBuf.position(24) // fileBase
            val fileBase = headerBuf.getLong()

            // Dir table starts at pos 96
            val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            ch.position(96L)
            ch.read(countBuf)
            countBuf.flip()
            val fileCount = countBuf.getInt()
            assertEquals(4, fileCount)

            val metaBuf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until fileCount) {
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                ch.read(lenBuf)
                lenBuf.flip()
                val sl = lenBuf.getInt()
                // In Godot C++, sl must be a multiple of 4
                assertEquals("Path length sl must be 4-byte padded for Godot C++ reader", 0, sl % 4)

                val strBuf = ByteBuffer.allocate(sl)
                ch.read(strBuf)
                strBuf.flip()
                val pathRaw = ByteArray(sl)
                strBuf.get(pathRaw)
                val cleanPath = String(pathRaw, Charsets.UTF_8).trimEnd('\u0000')
                assertTrue("Path must be non-empty and valid ASCII/UTF-8", cleanPath.isNotBlank())

                // In Godot C++, metadata follows immediately without extra padding
                metaBuf.clear()
                ch.read(metaBuf)
                metaBuf.flip()
                val rawOffset = metaBuf.getLong()
                val size = metaBuf.getLong()
                val md5 = ByteArray(16)
                metaBuf.get(md5)
                val flags = metaBuf.getInt()

                assertTrue("Size must be positive", size > 0)
                assertTrue("Actual offset must be >= fileBase", rawOffset + fileBase >= fileBase)
            }
        }
    }

    @Test
    fun testPatchFormatV1_Godot3_UnpaddedPathsAndMetadata32B() {
        // 1. Build synthetic PCK Format 1 (Godot 3.5 / 3.6) with odd/unaligned path lengths
        val scene1Path = "res://dlc/love/Maps/SexRoom/SexRoom.gd" // 38 bytes (NOT a multiple of 4!)
        val scene1Bytes = "extends Node\nfunc _ready(): pass\n".toByteArray(Charsets.UTF_8)
        val scene2Path = "res://dlc/love/Maps/DarkForest/Quests/DarkForestLoveQuests.gd" // 61 bytes (NOT a multiple of 4!)
        val scene2Bytes = "# quest script\n".toByteArray(Charsets.UTF_8)

        val s1PathBytes = scene1Path.removePrefix("res://").toByteArray(Charsets.UTF_8)
        val s2PathBytes = scene2Path.removePrefix("res://").toByteArray(Charsets.UTF_8)

        val headerSize = 0x54 // 84 bytes
        // Dir size: count (4) + (4 + 38 + 32) + (4 + 61 + 32) = 4 + 74 + 97 = 175 bytes
        val dirSize = 4 + (4 + s1PathBytes.size + 32) + (4 + s2PathBytes.size + 32)
        val payload1Offset = (headerSize + dirSize).toLong()
        val payload2Offset = payload1Offset + scene1Bytes.size

        val totalSize = (payload2Offset + scene2Bytes.size).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header v1
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(1) // Format v1
        bb.putInt(3) // Major 3
        bb.putInt(5) // Minor 5
        bb.putInt(0) // Patch 0
        bb.put(ByteArray(64)) // 16 * uint32 reserved

        // Directory at 0x54
        bb.putInt(2) // 2 files
        // File 1 (unpadded path, 32B metadata)
        bb.putInt(s1PathBytes.size)
        bb.put(s1PathBytes)
        bb.putLong(payload1Offset)
        bb.putLong(scene1Bytes.size.toLong())
        bb.put(ByteArray(16) { 0x55.toByte() })

        // File 2 (unpadded path, 32B metadata)
        bb.putInt(s2PathBytes.size)
        bb.put(s2PathBytes)
        bb.putLong(payload2Offset)
        bb.putLong(scene2Bytes.size.toLong())
        bb.put(ByteArray(16) { 0x66.toByte() })

        // Payload
        bb.put(scene1Bytes)
        bb.put(scene2Bytes)

        val originalPck = tempFolder.newFile("game_v1.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // Inspect original PCK: verify inspector parses unpadded Format 1 correctly
        val inspectOrig = inspector.inspect(originalPck)
        assertEquals(1, inspectOrig.header.formatVersion)
        assertEquals(3, inspectOrig.header.engineMajor)
        assertEquals(5, inspectOrig.header.engineMinor)
        assertEquals(2, inspectOrig.files.size)
        assertEquals("res://dlc/love/Maps/SexRoom/SexRoom.gd", inspectOrig.files[0].path)
        assertEquals("res://dlc/love/Maps/DarkForest/Quests/DarkForestLoveQuests.gd", inspectOrig.files[1].path)

        val extScene1 = inspector.extractFileBytes(originalPck, inspectOrig.files[0])
        assertArrayEquals(scene1Bytes, extScene1)
        val extScene2 = inspector.extractFileBytes(originalPck, inspectOrig.files[1])
        assertArrayEquals(scene2Bytes, extScene2)

        // 2. Patch PCK with injected files (override.cfg and companion mount script)
        val overrideContent = "[rendering]\nquality/driver/driver_name=\"GLES2\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)
        val mountScript = "extends Node\nfunc _init(): print(\"mounted\")\n"
        val mountBytes = mountScript.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("game_v1_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf(
                "res://override.cfg" to overrideBytes,
                "res://godotdroid_companion_mount.gd" to mountBytes
            )
        )

        assertEquals(4, patchResult.totalFilesCount)
        assertEquals(2, patchResult.injectedFilesCount)

        // 3. Inspect patched PCK: verify all 4 files are intact
        val inspectPatched = inspector.inspect(patchedPck)
        assertEquals(1, inspectPatched.header.formatVersion)
        assertEquals(4, inspectPatched.files.size)

        val p1 = inspectPatched.files.find { it.path == "res://dlc/love/Maps/SexRoom/SexRoom.gd" }
        assertNotNull(p1)
        assertArrayEquals(scene1Bytes, inspector.extractFileBytes(patchedPck, p1!!))

        val p2 = inspectPatched.files.find { it.path == "res://dlc/love/Maps/DarkForest/Quests/DarkForestLoveQuests.gd" }
        assertNotNull(p2)
        assertArrayEquals(scene2Bytes, inspector.extractFileBytes(patchedPck, p2!!))

        val pOverride = inspectPatched.files.find { it.path == "res://override.cfg" }
        assertNotNull(pOverride)
        assertArrayEquals(overrideBytes, inspector.extractFileBytes(patchedPck, pOverride!!))

        val pMount = inspectPatched.files.find { it.path == "res://godotdroid_companion_mount.gd" }
        assertNotNull(pMount)
        assertArrayEquals(mountBytes, inspector.extractFileBytes(patchedPck, pMount!!))
    }

    @Test
    fun testPatchFormatV3_PreservesUnprefixedPaths() {
        // Build synthetic PCK Format v3 where original entry has NO "res://" prefix (like project.binary)
        val projectBinaryContent = "binary_project_data_payload_test"
        val projectBytes = projectBinaryContent.toByteArray(Charsets.UTF_8)
        val rawPath = "project.binary"
        val pathBytes = rawPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 40
        val dataOffset = headerSize.toLong()
        val dirOffset = dataOffset + projectBytes.size
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)

        val totalSize = dirOffset + dirSize
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header v3 (Godot 4.5/4.6)
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(3) // v3
        bb.putInt(4) // major
        bb.putInt(6) // minor
        bb.putInt(0) // patch
        bb.putInt(0) // flags
        bb.putLong(0L) // fileBase
        bb.putLong(dirOffset)

        // Payload
        bb.put(projectBytes)

        // Directory
        bb.putInt(1) // 1 file
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        val pad = paddedPathLen - pathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(dataOffset)
        bb.putLong(projectBytes.size.toLong())
        bb.put(ByteArray(16) { 0x44.toByte() })
        bb.putInt(0)

        val originalPck = tempFolder.newFile("unprefixed_v3.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // Patch with override.cfg
        val overrideContent = "[rendering]\nrenderer/rendering_method=\"gl_compatibility\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("unprefixed_v3_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf("res://override.cfg" to overrideBytes)
        )

        assertEquals(2, patchResult.totalFilesCount)
        assertEquals(1, patchResult.injectedFilesCount)

        val inspectPatched = inspector.inspect(patchedPck)
        val binaryEntry = inspectPatched.files.find { it.rawPath == "project.binary" }
        assertNotNull("project.binary must preserve rawPath without res:// prefix", binaryEntry)
        assertEquals("project.binary", binaryEntry!!.rawPath)
        assertArrayEquals(projectBytes, inspector.extractFileBytes(patchedPck, binaryEntry))

        val overrideEntry = inspectPatched.files.find { it.rawPath == "override.cfg" }
        assertNotNull("override.cfg must be injected without res:// prefix to match pack convention", overrideEntry)
        assertEquals("override.cfg", overrideEntry!!.rawPath)
        assertArrayEquals(overrideBytes, inspector.extractFileBytes(patchedPck, overrideEntry))
    }

    @Test
    fun testPatchFormatV3_PreservesFormatV3_WhenTargetingGodotCore() {
        // 1. Build synthetic PCK Format v3 (Godot 4.5)
        val mainSceneContent = "[gd_scene format=3 uid=\"uid://test1234\"]\n"
        val sceneBytes = mainSceneContent.toByteArray(Charsets.UTF_8)
        val scenePath = "res://main.tscn"
        val scenePathBytes = scenePath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((scenePathBytes.size + 3) / 4) * 4

        val headerSize = 40
        val dataOffset = headerSize.toLong()
        val dirOffset = dataOffset + sceneBytes.size
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)

        val totalSize = dirOffset + dirSize
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header v3
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(3) // v3
        bb.putInt(4) // major 4
        bb.putInt(5) // minor 5
        bb.putInt(0) // patch 0
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
        bb.put(ByteArray(16) { 0x44.toByte() })
        bb.putInt(0)

        val originalPck = tempFolder.newFile("game_v3_to_patch.pck")
        FileOutputStream(originalPck).use { it.write(bb.array()) }

        // 2. Patch PCK targeting matching Godot 4.5 core
        val overrideContent = "[rendering]\nrenderer/rendering_method=\"gl_compatibility\"\n"
        val overrideBytes = overrideContent.toByteArray(Charsets.UTF_8)

        val patchedPck = tempFolder.newFile("game_v3_patched.pck")
        val patchResult = patcher.patchPck(
            sourceFile = originalPck,
            targetFile = patchedPck,
            injectedFiles = mapOf("res://override.cfg" to overrideBytes),
            targetEngineMajor = 4,
            targetEngineMinor = 5
        )

        assertEquals("Format version must remain 3", 3, patchResult.formatVersion)
        assertEquals(2, patchResult.totalFilesCount)
        assertEquals(1, patchResult.injectedFilesCount)

        // 3. Inspect patched PCK: verify inspector parses it as valid Format 3 PCK
        val inspectPatched = inspector.inspect(patchedPck)
        assertEquals("Inspected format version must be 3", 3, inspectPatched.header.formatVersion)
        assertEquals("Engine major must be 4", 4, inspectPatched.header.engineMajor)
        assertEquals("Engine minor must be 5", 5, inspectPatched.header.engineMinor)
        assertEquals(2, inspectPatched.files.size)

        val origScene = inspectPatched.files.find { it.path == "res://main.tscn" }
        assertNotNull("main.tscn must be present", origScene)
        val extractedScene = inspector.extractFileBytes(patchedPck, origScene!!)
        assertArrayEquals("Extracted scene bytes must match original exactly", sceneBytes, extractedScene)

        val overrideEntry = inspectPatched.files.find { it.path == "res://override.cfg" }
        assertNotNull("override.cfg must be present", overrideEntry)
        val extractedOverride = inspector.extractFileBytes(patchedPck, overrideEntry!!)
        assertArrayEquals("Extracted override bytes must match", overrideBytes, extractedOverride)
    }

    @Test
    fun testNormalizeDesktopTextureImports() {
        val importContent = "[remap]\n\nimporter=\"texture\"\ntype=\"CompressedTexture2D\"\npath.s3tc=\"res://test.s3tc.ctex\"\n"
        val importBytes = importContent.toByteArray(Charsets.UTF_8)
        val importPath = "res://test.png.import"
        val importPathBytes = importPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((importPathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val dataOffset = (headerSize + dirSize).toLong()

        val totalSize = dataOffset + importBytes.size
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
        bb.putInt(1)
        bb.putInt(importPathBytes.size)
        bb.put(importPathBytes)
        val pad = paddedPathLen - importPathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(dataOffset)
        bb.putLong(importBytes.size.toLong())
        bb.put(ByteArray(16) { 0x33.toByte() })
        bb.putInt(0)

        // Data
        bb.put(importBytes)

        val testPck = tempFolder.newFile("test_import.pck")
        FileOutputStream(testPck).use { it.write(bb.array()) }

        val modifiedCount = patcher.normalizeDesktopTextureImports(testPck)
        assertEquals("One import file should be modified", 1, modifiedCount)

        val insp = inspector.inspect(testPck)
        val entry = insp.files.first { it.path == "res://test.png.import" }
        val extracted = String(inspector.extractFileBytes(testPck, entry), Charsets.UTF_8)
        assertTrue("path=     \" must be present", extracted.contains("path=     \"res://test.s3tc.ctex\""))
        assertTrue("path.s3tc must not be present", !extracted.contains("path.s3tc="))
    }
}
