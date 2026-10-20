package com.godotdroid.pck.analyzer

import com.godotdroid.pck.inspector.PckConstants
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

class GameCompatibilityAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val analyzer = GameCompatibilityAnalyzer()

    @Test
    fun testDetectEncryptedPck() {
        // Build synthetic PCK Format v2 with PACK_DIR_ENCRYPTED flag (0x01)
        val pckFile = tempFolder.newFile("encrypted_game.pck")
        val bb = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.PCK_MAGIC) // magic
        bb.putInt(2)                      // format 2
        bb.putInt(4)                      // major 4
        bb.putInt(3)                      // minor 3
        bb.putInt(0)                      // patch 0
        bb.putInt(PckConstants.PACK_DIR_ENCRYPTED) // pack_flags = 1 (encrypted)
        bb.putLong(0L)                    // fileBase
        // 64 reserved bytes
        bb.position(96)

        FileOutputStream(pckFile).use { it.write(bb.array()) }

        val report = analyzer.analyze(pckFile)
        assertTrue("Game report must flag isEncrypted = true", report.isEncrypted)
        assertFalse("Encrypted game should not be marked as using DotNet", report.usesDotNet)
        assertTrue("Missing plugins list must be empty for encrypted game", report.missingAndroidPlugins.isEmpty())
    }

    @Test
    fun testDetectMonoDotNetGame() {
        // Build synthetic PCK with a C# .dll entry
        val pckFile = tempFolder.newFile("mono_game.pck")
        val dllContent = "FAKE_MONO_ASSEMBLY_BYTES".toByteArray()
        val dllPath = "res://.godot/mono/assemblies/Debug/Game.dll"
        val pathBytes = dllPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val fileBase = 256L
        val totalSize = (fileBase + dllContent.size).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(fileBase)
        bb.position(96)

        // Directory
        bb.putInt(1) // 1 file
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        val pad = paddedPathLen - pathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(0L) // rawOffset from fileBase
        bb.putLong(dllContent.size.toLong())
        bb.put(ByteArray(16)) // md5
        bb.putInt(0) // flags

        // Payload
        bb.position(fileBase.toInt())
        bb.put(dllContent)

        FileOutputStream(pckFile).use { it.write(bb.array()) }

        val report = analyzer.analyze(pckFile)
        assertFalse("Should not be encrypted", report.isEncrypted)
        assertTrue("Report must detect C# / Mono (.NET) game", report.usesDotNet)
    }

    @Test
    fun testDetectMissingAndroidGDExtension() {
        // Build synthetic PCK with a .gdextension file containing only Windows DLL
        val pckFile = tempFolder.newFile("gdextension_desktop_only.pck")
        val gdextContent = """
            [configuration]
            entry_symbol = "example_library_init"
            compatibility_minimum = "4.1"

            [libraries]
            windows.debug.x86_64 = "res://bin/libexample.windows.debug.x86_64.dll"
            windows.release.x86_64 = "res://bin/libexample.windows.release.x86_64.dll"
            linux.debug.x86_64 = "res://bin/libexample.linux.debug.x86_64.so"
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val extPath = "res://bin/example.gdextension"
        val pathBytes = extPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val fileBase = 256L
        val totalSize = (fileBase + gdextContent.size).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header v2
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(3)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(fileBase)
        bb.position(96)

        // Directory
        bb.putInt(1)
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        val pad = paddedPathLen - pathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(0L)
        bb.putLong(gdextContent.size.toLong())
        bb.put(ByteArray(16))
        bb.putInt(0)

        // Payload
        bb.position(fileBase.toInt())
        bb.put(gdextContent)

        FileOutputStream(pckFile).use { it.write(bb.array()) }

        val report = analyzer.analyze(pckFile)
        assertFalse("Should not be encrypted", report.isEncrypted)
        assertFalse("Should not be marked as DotNet", report.usesDotNet)
        assertEquals("Must detect 1 plugin", 1, report.plugins.size)
        assertEquals("Must flag missing Android ARM64 binary", 1, report.missingAndroidPlugins.size)
        assertEquals("example", report.missingAndroidPlugins.first().name)
    }

    @Test
    fun testPassValidAndroidGDExtension() {
        // Build synthetic PCK with a .gdextension file containing Android arm64-v8a binary
        val pckFile = tempFolder.newFile("gdextension_android_valid.pck")
        val gdextContent = """
            [configuration]
            entry_symbol = "valid_library_init"
            compatibility_minimum = "4.2"

            [libraries]
            windows.release.x86_64 = "res://bin/libvalid.windows.dll"
            android.release.arm64-v8a = "res://bin/libvalid.android.arm64.so"
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val extPath = "res://bin/valid.gdextension"
        val pathBytes = extPath.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val fileBase = 256L
        val totalSize = (fileBase + gdextContent.size).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(fileBase)
        bb.position(96)

        bb.putInt(1)
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        val pad = paddedPathLen - pathBytes.size
        if (pad > 0) bb.put(ByteArray(pad))
        bb.putLong(0L)
        bb.putLong(gdextContent.size.toLong())
        bb.put(ByteArray(16))
        bb.putInt(0)

        bb.position(fileBase.toInt())
        bb.put(gdextContent)

        FileOutputStream(pckFile).use { it.write(bb.array()) }

        val report = analyzer.analyze(pckFile)
        assertFalse("Should not be encrypted", report.isEncrypted)
        assertFalse("Should not be DotNet", report.usesDotNet)
        assertEquals(1, report.plugins.size)
        assertTrue("Android ARM64 is provided, so missingAndroidPlugins must be empty", report.missingAndroidPlugins.isEmpty())
    }

    @Test
    fun testDetectDotNetViaSiblingDataFolder() {
        val gameFolder = tempFolder.newFolder("MyMonoGame")
        val pckFile = File(gameFolder, "Game.pck")

        // Minimal valid PCK with standard GDScript project settings
        val headerSize = 96
        val fileBase = 128L
        val bb = ByteBuffer.allocate(fileBase.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(fileBase)
        bb.position(96)
        bb.putInt(0) // 0 entries
        FileOutputStream(pckFile).use { it.write(bb.array()) }

        // Before adding data folder, report.usesDotNet must be false
        var report = analyzer.analyze(pckFile)
        assertFalse("Should not detect DotNet yet", report.usesDotNet)

        // Create sibling folder data_Game/Mono/ with an assembly DLL
        val dataFolder = File(gameFolder, "data_Game").apply { mkdirs() }
        val monoFolder = File(dataFolder, "Mono").apply { mkdirs() }
        val dllFile = File(monoFolder, "GameAssembly.dll")
        dllFile.writeBytes("MZ_MOCK_ASSEMBLY".toByteArray())

        report = analyzer.analyze(pckFile)
        assertTrue("Report must detect DotNet from sibling data_Game/ folder", report.usesDotNet)
    }

    @Test
    fun testDetectDotNetViaLooseSiblingAssembly() {
        val gameFolder = tempFolder.newFolder("MyLooseMonoGame")
        val pckFile = File(gameFolder, "Game.pck")

        val headerSize = 96
        val fileBase = 128L
        val bb = ByteBuffer.allocate(fileBase.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(2)
        bb.putInt(4)
        bb.putInt(2)
        bb.putInt(0)
        bb.putInt(PckConstants.PACK_REL_FILEBASE)
        bb.putLong(fileBase)
        bb.position(96)
        bb.putInt(0)
        FileOutputStream(pckFile).use { it.write(bb.array()) }

        // Create sibling loose GodotSharp.dll
        val dllFile = File(gameFolder, "GodotSharp.dll")
        dllFile.writeBytes("MZ_MOCK_GODOT_SHARP".toByteArray())

        val report = analyzer.analyze(pckFile)
        assertTrue("Report must detect DotNet from loose GodotSharp.dll next to pck", report.usesDotNet)
    }
}
