package com.godotdroid.pck.inspector

import com.godotdroid.pck.analyzer.GameCompatibilityAnalyzer
import com.godotdroid.pck.model.PckFormatVersion
import com.godotdroid.pck.model.RendererType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PckBinaryInspectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val inspector = PckBinaryInspector()
    private val analyzer = GameCompatibilityAnalyzer(inspector)

    @Test
    fun testPckFormatV1_Godot35_Standalone() {
        // Construct synthetic PCK Format v1 (Godot 3.5.0)
        val fileContent = "config_version=4\n[application]\nconfig/name=\"Test Game 3.5\"\nrun/main_scene=\"res://Main.tscn\"\n"
        val fileBytes = fileContent.toByteArray(Charsets.UTF_8)
        val path = "res://project.godot"
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        // Layout:
        // 0x00: Magic (4)
        // 0x04: FormatVersion = 1 (4)
        // 0x08: Major = 3 (4)
        // 0x0C: Minor = 5 (4)
        // 0x10: Patch = 0 (4)
        // 0x14: Reserved 64 bytes (64) -> ends at 0x54 (84)
        // 0x54: Directory:
        //   - fileCount = 1 (4)
        //   - pathLength = 17 (4)
        //   - path (pathBytes.size, unpadded in Godot 3 Format 1)
        //   - rawOffset (8)
        //   - size (8)
        //   - md5 (16)
        // Data follows directory
        val headerSize = 84
        val dirHeaderSize = 4 + 4 + pathBytes.size + 8 + 8 + 16
        val dataOffset = (headerSize + dirHeaderSize).toLong()

        val totalSize = dataOffset + fileBytes.size
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(1) // v1
        bb.putInt(3) // Major 3
        bb.putInt(5) // Minor 5
        bb.putInt(0) // Patch 0
        bb.put(ByteArray(64)) // 64 bytes reserved

        assertEquals(0x54, bb.position())

        // Directory (unpadded in Godot 3)
        bb.putInt(1) // 1 file
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)

        bb.putLong(dataOffset) // rawOffset
        bb.putLong(fileBytes.size.toLong()) // size
        val dummyMd5 = ByteArray(16) { (it + 1).toByte() }
        bb.put(dummyMd5) // md5 (16 bytes, no flags in v1!)

        // File Data
        bb.put(fileBytes)

        val pckFile = tempFolder.newFile("game_v1.pck")
        FileOutputStream(pckFile).use { it.write(bb.array()) }

        // Test Inspection
        val result = inspector.inspect(pckFile)
        assertFalse(result.isEncrypted)
        assertEquals(PckConstants.PCK_MAGIC, result.header.magic)
        assertEquals(1, result.header.formatVersion)
        assertEquals(PckFormatVersion.V1, result.header.format)
        assertEquals(3, result.header.engineMajor)
        assertEquals(5, result.header.engineMinor)
        assertEquals(0, result.header.enginePatch)
        assertEquals("3.5.0", result.header.engineVersionString)
        assertFalse(result.header.isEmbeddedExe)
        assertEquals(0L, result.header.pckStartOffset)

        assertEquals(1, result.files.size)
        val entry = result.files[0]
        assertEquals("res://project.godot", entry.path)
        assertEquals(dataOffset, entry.fileOffset)
        assertEquals(fileBytes.size.toLong(), entry.size)
        assertArrayEquals(dummyMd5, entry.md5)
        assertEquals(0, entry.flags)

        // Test File Extraction
        val extracted = inspector.extractFileBytes(pckFile, entry)
        assertArrayEquals(fileBytes, extracted)

        // Test Compatibility Analyzer
        val report = analyzer.analyze(pckFile)
        assertEquals("Test Game 3.5", report.gameTitle)
        assertEquals("res://Main.tscn", report.mainScene)
        assertFalse(report.usesDotNet)
        assertFalse(report.hasGodotSteam)
        assertEquals(1, report.totalFilesCount)
    }

    @Test
    fun testPckFormatV2_Godot42_Standalone() {
        // Construct synthetic PCK Format v2 (Godot 4.2.0) with .gdextension plugin
        val projectContent = """
            [application]
            config/name="Godot 4 Game"
            run/main_scene="res://scenes/Root.tscn"
            config/icon="res://icon.png"
            [rendering]
            renderer/rendering_method="forward_plus"
        """.trimIndent()
        val projectBytes = projectContent.toByteArray(Charsets.UTF_8)
        val projectPath = "res://project.godot"
        val projectPathBytes = projectPath.toByteArray(Charsets.UTF_8)
        val projectPaddedPathLen = ((projectPathBytes.size + 3) / 4) * 4

        val extContent = """
            [configuration]
            entry_symbol = "steam_init"
            [libraries]
            windows.debug.x86_64 = "res://bin/godotsteam.windows.dll"
            linux.debug.x86_64 = "res://bin/libgodotsteam.linux.so"
        """.trimIndent()
        val extBytes = extContent.toByteArray(Charsets.UTF_8)
        val extPath = "res://addons/godotsteam/godotsteam.gdextension"
        val extPathBytes = extPath.toByteArray(Charsets.UTF_8)
        val extPaddedPathLen = ((extPathBytes.size + 3) / 4) * 4

        // Layout:
        // 0x00: Magic (4)
        // 0x04: FormatVersion = 2 (4)
        // 0x08: Major = 4 (4)
        // 0x0C: Minor = 2 (4)
        // 0x10: Patch = 0 (4)
        // 0x14: packFlags = 0x02 (PACK_REL_FILEBASE) (4)
        // 0x18: fileBase = 0L (8)
        // 0x20: Reserved 64 bytes (64) -> ends at 0x60 (96)
        // 0x60: Directory:
        //   - fileCount = 2 (4)
        //   - Entry 1: pathLen(4) + paddedPath + rawOffset(8) + size(8) + md5(16) + flags(4)
        //   - Entry 2: pathLen(4) + paddedPath + rawOffset(8) + size(8) + md5(16) + flags(4)
        // Data follows directory
        val headerSize = 96
        val dirSize = 4 +
                (4 + projectPaddedPathLen + 8 + 8 + 16 + 4) +
                (4 + extPaddedPathLen + 8 + 8 + 16 + 4)
        val data1Offset = (headerSize + dirSize).toLong()
        val data2Offset = data1Offset + projectBytes.size

        val totalSize = data2Offset + extBytes.size
        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2) // v2
        bb.putInt(4) // 4
        bb.putInt(2) // 2
        bb.putInt(0) // 0
        bb.putInt(PckConstants.PACK_REL_FILEBASE) // packFlags
        bb.putLong(0L) // fileBase
        bb.put(ByteArray(64)) // 64 bytes reserved

        assertEquals(0x60, bb.position())

        // Directory
        bb.putInt(2) // 2 files

        // Entry 1
        bb.putInt(projectPathBytes.size)
        bb.put(projectPathBytes)
        val pad1 = projectPaddedPathLen - projectPathBytes.size
        if (pad1 > 0) bb.put(ByteArray(pad1))
        bb.putLong(data1Offset)
        bb.putLong(projectBytes.size.toLong())
        bb.put(ByteArray(16) { 0xAA.toByte() })
        bb.putInt(0) // flags (Godot 4 entries have 4 bytes flags!)

        // Entry 2
        bb.putInt(extPathBytes.size)
        bb.put(extPathBytes)
        val pad2 = extPaddedPathLen - extPathBytes.size
        if (pad2 > 0) bb.put(ByteArray(pad2))
        bb.putLong(data2Offset)
        bb.putLong(extBytes.size.toLong())
        bb.put(ByteArray(16) { 0xBB.toByte() })
        bb.putInt(0) // flags

        // File Data
        bb.put(projectBytes)
        bb.put(extBytes)

        val pckFile = tempFolder.newFile("game_v2.pck")
        FileOutputStream(pckFile).use { it.write(bb.array()) }

        val result = inspector.inspect(pckFile)
        assertFalse(result.isEncrypted)
        assertEquals(2, result.header.formatVersion)
        assertEquals(PckFormatVersion.V2, result.header.format)
        assertEquals(4, result.header.engineMajor)
        assertEquals(2, result.header.engineMinor)
        assertEquals(0, result.header.enginePatch)
        assertEquals("4.2.0", result.header.engineVersionString)
        assertEquals(2, result.files.size)

        // Test File 1
        val entry1 = result.files[0]
        assertEquals("res://project.godot", entry1.path)
        val extracted1 = inspector.extractFileBytes(pckFile, entry1)
        assertArrayEquals(projectBytes, extracted1)

        // Test File 2
        val entry2 = result.files[1]
        assertEquals("res://addons/godotsteam/godotsteam.gdextension", entry2.path)
        val extracted2 = inspector.extractFileBytes(pckFile, entry2)
        assertArrayEquals(extBytes, extracted2)

        // Compatibility Report verification
        val report = analyzer.analyze(pckFile)
        assertEquals("Godot 4 Game", report.gameTitle)
        assertEquals("res://scenes/Root.tscn", report.mainScene)
        assertEquals("res://icon.png", report.iconPath)
        assertEquals(RendererType.FORWARD_PLUS, report.detectedRenderer)
        assertTrue(report.hasGodotSteam)
        assertEquals(1, report.plugins.size)
        val plugin = report.plugins[0]
        assertEquals("godotsteam", plugin.name)
        assertTrue(plugin.isGodotSteam)
        assertFalse(plugin.hasAndroidArm64)
        assertEquals(1, report.missingAndroidPlugins.size)
    }

    @Test
    fun testPckFormatV3_Godot44_EmbeddedInExeTrailer() {
        // Construct mock PE EXE with embedded PCK Format v3 (Godot 4.4.0) and trailer
        val mainSceneContent = "[gd_scene format=3 uid=\"uid://mock\"]\n"
        val sceneBytes = mainSceneContent.toByteArray(Charsets.UTF_8)
        val scenePath = "res://main.tscn"
        val scenePathBytes = scenePath.toByteArray(Charsets.UTF_8)
        val scenePaddedPathLen = ((scenePathBytes.size + 3) / 4) * 4

        // 1. Mock PE Executable Header (128 bytes)
        val peHeaderBytes = ByteArray(128)
        // DOS magic 'MZ' (0x5A4D)
        peHeaderBytes[0] = 'M'.code.toByte()
        peHeaderBytes[1] = 'Z'.code.toByte()

        val pckStartOffset = peHeaderBytes.size.toLong()

        // 2. PCK Format v3 Header
        // 0x00: Magic (4)
        // 0x04: FormatVersion = 3 (4)
        // 0x08: Major = 4 (4)
        // 0x0C: Minor = 4 (4)
        // 0x10: Patch = 0 (4)
        // 0x14: packFlags = 0 (4)
        // 0x18: fileBase = 0L (8)
        // 0x20: dirOffset = 40L (8) -> points to directory immediately after this 40-byte v3 header
        val pckHeaderSize = 40
        val dirOffset = pckHeaderSize.toLong()

        // 3. Directory Table
        val dirSize = 4 + (4 + scenePaddedPathLen + 8 + 8 + 16 + 4)
        val rawDataOffset = dirOffset + dirSize // relative to effectiveFileBase (which is pckStartOffset)

        val pckDataSize = dirOffset + dirSize + sceneBytes.size

        // 4. Godot Trailer Footer (12 bytes at very end of file)
        // uint64 pckSize (8 bytes)
        // uint32 magic (4 bytes)
        val trailerSize = 12

        val totalExeSize = peHeaderBytes.size + pckDataSize.toInt() + trailerSize
        val bb = ByteBuffer.allocate(totalExeSize).order(ByteOrder.LITTLE_ENDIAN)

        // Write PE bytes
        bb.put(peHeaderBytes)
        assertEquals(pckStartOffset, bb.position().toLong())

        // Write PCK v3 Header
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(3) // v3
        bb.putInt(4) // 4
        bb.putInt(4) // 4
        bb.putInt(0) // 0
        bb.putInt(0) // packFlags
        bb.putLong(0L) // fileBase
        bb.putLong(dirOffset) // dirOffset

        // Write Directory
        bb.putInt(1) // 1 file
        bb.putInt(scenePathBytes.size)
        bb.put(scenePathBytes)
        val pad = scenePaddedPathLen - scenePathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(rawDataOffset) // relative to effectiveFileBase
        bb.putLong(sceneBytes.size.toLong())
        bb.put(ByteArray(16) { 0xCC.toByte() })
        bb.putInt(0) // flags

        // Write File Data
        bb.put(sceneBytes)

        val pckBytesWritten = bb.position().toLong() - pckStartOffset
        assertEquals(pckDataSize.toLong(), pckBytesWritten)

        // Write Trailer
        bb.putLong(pckBytesWritten) // pckSize
        bb.putInt(PckConstants.PCK_MAGIC) // trailer magic

        assertEquals(totalExeSize, bb.position())

        val exeFile = tempFolder.newFile("game.exe")
        FileOutputStream(exeFile).use { it.write(bb.array()) }

        // Test Inspection
        val result = inspector.inspect(exeFile)
        assertTrue(result.header.isEmbeddedExe)
        assertEquals(pckStartOffset, result.header.pckStartOffset)
        assertEquals(3, result.header.formatVersion)
        assertEquals(PckFormatVersion.V3, result.header.format)
        assertEquals(4, result.header.engineMajor)
        assertEquals(4, result.header.engineMinor)
        assertEquals(0, result.header.enginePatch)
        assertEquals("4.4.0", result.header.engineVersionString)

        assertEquals(1, result.files.size)
        val fileEntry = result.files[0]
        assertEquals("res://main.tscn", fileEntry.path)
        assertEquals(pckStartOffset + rawDataOffset, fileEntry.fileOffset)
        assertEquals(sceneBytes.size.toLong(), fileEntry.size)

        // Test File Extraction
        val extracted = inspector.extractFileBytes(exeFile, fileEntry)
        assertArrayEquals(sceneBytes, extracted)

        // Test Standalone PCK Extractor
        val standalonePck = tempFolder.newFile("extracted.pck")
        val transferred = inspector.extractStandalonePck(exeFile, standalonePck, result.header)
        assertTrue(transferred > 0)
        assertTrue(standalonePck.exists())

        // Verify the extracted file can be inspected as standalone PCK
        val standaloneResult = inspector.inspect(standalonePck)
        assertFalse(standaloneResult.header.isEmbeddedExe)
        assertEquals(0L, standaloneResult.header.pckStartOffset)
        assertEquals(1, standaloneResult.files.size)
        assertEquals("res://main.tscn", standaloneResult.files[0].path)
    }

    @Test
    fun testBinaryConfigFileECFG_Parsing() {
        // Test parsing of project.binary with ECFG magic
        val bb = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.ECFG_MAGIC) // "ECFG"

        // Put null-separated strings: key, value pairs
        val pairs = listOf(
            "application/config/name" to "Space Shooter 2D",
            "application/run/main_scene" to "res://scenes/Menu.tscn",
            "application/config/icon" to "res://icon.svg",
            "rendering/renderer/rendering_method" to "mobile"
        )

        for ((k, v) in pairs) {
            bb.put(k.toByteArray(Charsets.UTF_8))
            bb.put(0.toByte())
            bb.put(v.toByteArray(Charsets.UTF_8))
            bb.put(0.toByte())
        }

        val configBytes = ByteArray(bb.position())
        System.arraycopy(bb.array(), 0, configBytes, 0, configBytes.size)

        val parsed = com.godotdroid.pck.analyzer.ProjectConfigParser.parse(configBytes)
        assertEquals("Space Shooter 2D", parsed.gameTitle)
        assertEquals("res://scenes/Menu.tscn", parsed.mainScene)
        assertEquals("res://icon.svg", parsed.iconPath)
        assertEquals("mobile", parsed.renderingMethod)
        assertEquals(RendererType.MOBILE, parsed.rendererType)
    }

    @Test
    fun testExtractStandalonePck_FormatV1_RebaseOffsets() {
        // Construct mock PE EXE (128 bytes) with embedded Godot 3.5 PCK (Format v1)
        // using absolute offsets in the EXE
        val peHeaderBytes = ByteArray(128)
        peHeaderBytes[0] = 'M'.code.toByte()
        peHeaderBytes[1] = 'Z'.code.toByte()
        val pckStartOffset = peHeaderBytes.size.toLong()

        val fileContent = "extends Node\nfunc _ready():\n    print(\"Hello Godot 3.5\")\n"
        val fileBytes = fileContent.toByteArray(Charsets.UTF_8)
        val path = "res://Main.gd"
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        val headerSize = 84 // 20 + 64 reserved
        val dirSize = 4 + 4 + pathBytes.size + 8 + 8 + 16
        val dataOffsetInExe = pckStartOffset + headerSize + dirSize

        val pckSize = headerSize + dirSize + fileBytes.size
        val totalSize = peHeaderBytes.size + pckSize + 12 // 12-byte trailer
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // PE bytes
        bb.put(peHeaderBytes)

        // PCK Header v1
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(1) // Format v1
        bb.putInt(3) // Major 3
        bb.putInt(5) // Minor 5
        bb.putInt(0) // Patch 0
        bb.put(ByteArray(64)) // 64 bytes reserved

        // Directory (unpadded in Godot 3)
        bb.putInt(1) // 1 file
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        bb.putLong(dataOffsetInExe) // Absolute offset in EXE!
        bb.putLong(fileBytes.size.toLong())
        val dummyMd5 = ByteArray(16) { 0x35.toByte() }
        bb.put(dummyMd5)

        // Data
        bb.put(fileBytes)

        // Trailer
        bb.putLong(pckSize.toLong())
        bb.putInt(PckConstants.PCK_MAGIC)

        val exeFile = tempFolder.newFile("game_v1_embedded.exe")
        FileOutputStream(exeFile).use { it.write(bb.array()) }

        // Inspect original embedded EXE
        val originalResult = inspector.inspect(exeFile)
        assertTrue(originalResult.header.isEmbeddedExe)
        assertEquals(pckStartOffset, originalResult.header.pckStartOffset)
        assertEquals(1, originalResult.files.size)
        val originalExtracted = inspector.extractFileBytes(exeFile, originalResult.files[0])
        assertArrayEquals(fileBytes, originalExtracted)

        // Carve standalone PCK
        val carvedPck = tempFolder.newFile("game_v1_carved.pck")
        val transferred = inspector.extractStandalonePck(exeFile, carvedPck, originalResult.header)
        assertTrue(transferred > 0)

        // Inspect carved standalone PCK
        val carvedResult = inspector.inspect(carvedPck)
        assertFalse(carvedResult.header.isEmbeddedExe)
        assertEquals(0L, carvedResult.header.pckStartOffset)
        assertEquals(1, carvedResult.files.size)

        val carvedEntry = carvedResult.files[0]
        assertEquals("res://Main.gd", carvedEntry.path)
        assertEquals((headerSize + dirSize).toLong(), carvedEntry.fileOffset) // Rebased to 0!
        assertEquals(fileBytes.size.toLong(), carvedEntry.size)

        val carvedExtracted = inspector.extractFileBytes(carvedPck, carvedEntry)
        assertArrayEquals(fileBytes, carvedExtracted)
    }

    @Test
    fun testExtractStandalonePck_FormatV2_RebaseOffsets() {
        // Construct mock PE EXE (128 bytes) with embedded Godot 4.2 PCK (Format v2)
        // packFlags = 0 (PACK_REL_FILEBASE not set), fileBase = pckStartOffset (absolute)
        val peHeaderBytes = ByteArray(128)
        peHeaderBytes[0] = 'M'.code.toByte()
        peHeaderBytes[1] = 'Z'.code.toByte()
        val pckStartOffset = peHeaderBytes.size.toLong()

        val fileContent = "extends Node\nfunc _ready():\n    print(\"Hello Godot 4.2\")\n"
        val fileBytes = fileContent.toByteArray(Charsets.UTF_8)
        val path = "res://Game.gd"
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96 // 20 + 4 (flags) + 8 (fileBase) + 64 (reserved)
        val dirSize = 4 + 4 + paddedPathLen + 8 + 8 + 16 + 4
        val rawOffset = (headerSize + dirSize).toLong()

        val pckSize = headerSize + dirSize + fileBytes.size
        val totalSize = peHeaderBytes.size + pckSize + 12
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // PE bytes
        bb.put(peHeaderBytes)

        // PCK Header v2: packFlags = 0 (absolute fileBase), fileBase = pckStartOffset
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2) // Format v2
        bb.putInt(4) // Major 4
        bb.putInt(2) // Minor 2
        bb.putInt(0) // Patch 0
        bb.putInt(0) // packFlags (PACK_REL_FILEBASE is 0!)
        bb.putLong(pckStartOffset) // Absolute fileBase
        bb.put(ByteArray(64))

        // Directory
        bb.putInt(1)
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        val pad = paddedPathLen - pathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(rawOffset)
        bb.putLong(fileBytes.size.toLong())
        val dummyMd5 = ByteArray(16) { 0x42.toByte() }
        bb.put(dummyMd5)
        bb.putInt(0) // entry flags

        // Data
        bb.put(fileBytes)

        // Trailer
        bb.putLong(pckSize.toLong())
        bb.putInt(PckConstants.PCK_MAGIC)

        val exeFile = tempFolder.newFile("game_v2_embedded.exe")
        FileOutputStream(exeFile).use { it.write(bb.array()) }

        // Inspect original embedded EXE
        val originalResult = inspector.inspect(exeFile)
        assertTrue(originalResult.header.isEmbeddedExe)
        assertEquals(pckStartOffset, originalResult.header.pckStartOffset)
        assertEquals(pckStartOffset + rawOffset, originalResult.files[0].fileOffset)
        val originalExtracted = inspector.extractFileBytes(exeFile, originalResult.files[0])
        assertArrayEquals(fileBytes, originalExtracted)

        // Carve standalone PCK
        val carvedPck = tempFolder.newFile("game_v2_carved.pck")
        val transferred = inspector.extractStandalonePck(exeFile, carvedPck, originalResult.header)
        assertTrue(transferred > 0)

        // Inspect carved standalone PCK
        val carvedResult = inspector.inspect(carvedPck)
        assertFalse(carvedResult.header.isEmbeddedExe)
        assertEquals(0L, carvedResult.header.pckStartOffset)
        assertTrue((carvedResult.header.packFlags and PckConstants.PACK_REL_FILEBASE) != 0) // PACK_REL_FILEBASE enabled!
        assertEquals(0L, carvedResult.header.fileBase) // Rebased fileBase
        assertEquals(rawOffset, carvedResult.files[0].fileOffset) // Rebased to 0 + rawOffset!

        val carvedExtracted = inspector.extractFileBytes(carvedPck, carvedResult.files[0])
        assertArrayEquals(fileBytes, carvedExtracted)
    }
}
