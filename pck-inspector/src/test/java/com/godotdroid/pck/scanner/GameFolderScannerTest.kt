package com.godotdroid.pck.scanner

import com.godotdroid.pck.inspector.PckConstants
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

class GameFolderScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scanner = GameFolderScanner()

    private fun writeDummyPck(
        file: File,
        major: Int = 4,
        minor: Int = 3,
        formatVersion: Int = 2,
        paddingBytes: Int = 0
    ) {
        val projectGodot = "[application]\nconfig/name=\"GenericGame\"\nrun/main_scene=\"res://main.tscn\"\n"
        val fileBytes = projectGodot.toByteArray(Charsets.UTF_8)
        val path = "res://project.godot"
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + 4 + paddedPathLen + 8 + 8 + 16 + 4
        val dataOffset = (headerSize + dirSize).toLong()
        val totalSize = dataOffset + fileBytes.size + paddingBytes

        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(PckConstants.PCK_MAGIC)
        bb.putInt(formatVersion)
        bb.putInt(major)
        bb.putInt(minor)
        bb.putInt(0) // patch
        bb.putInt(0) // flags
        bb.putLong(0L) // fileBase
        bb.put(ByteArray(64)) // reserved

        // Directory
        bb.putInt(1) // 1 file
        bb.putInt(pathBytes.size)
        bb.put(pathBytes)
        bb.put(ByteArray(paddedPathLen - pathBytes.size))
        bb.putLong(dataOffset)
        bb.putLong(fileBytes.size.toLong())
        bb.put(ByteArray(16)) // md5
        bb.putInt(0) // flags

        // Data
        bb.put(fileBytes)
        if (paddingBytes > 0) {
            bb.put(ByteArray(paddingBytes))
        }

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bb.array()) }
    }

    private fun writeDummyEmbeddedExe(file: File, major: Int = 4, minor: Int = 3) {
        val peHeader = ByteArray(128)
        peHeader[0] = 'M'.code.toByte()
        peHeader[1] = 'Z'.code.toByte()

        val projectGodot = "[application]\nconfig/name=\"GenericGame\"\n"
        val fileBytes = projectGodot.toByteArray(Charsets.UTF_8)
        val path = "res://project.godot"
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pathBytes.size + 3) / 4) * 4

        val headerSize = 96
        val dirSize = 4 + 4 + paddedPathLen + 8 + 8 + 16 + 4
        val dataOffset = (headerSize + dirSize).toLong()
        val pckContentSize = dataOffset + fileBytes.size

        val pckBuf = ByteBuffer.allocate(pckContentSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        pckBuf.putInt(PckConstants.PCK_MAGIC)
        pckBuf.putInt(2)
        pckBuf.putInt(major)
        pckBuf.putInt(minor)
        pckBuf.putInt(0)
        pckBuf.putInt(0)
        pckBuf.putLong(0L)
        pckBuf.put(ByteArray(64))
        pckBuf.putInt(1)
        pckBuf.putInt(pathBytes.size)
        pckBuf.put(pathBytes)
        pckBuf.put(ByteArray(paddedPathLen - pathBytes.size))
        pckBuf.putLong(dataOffset)
        pckBuf.putLong(fileBytes.size.toLong())
        pckBuf.put(ByteArray(16))
        pckBuf.putInt(0)
        pckBuf.put(fileBytes)

        val pckBytes = pckBuf.array()
        val pckSizeTotal = pckBytes.size.toLong()

        val trailerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        trailerBuf.putLong(pckSizeTotal)
        trailerBuf.putInt(PckConstants.PCK_MAGIC)

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(peHeader)
            out.write(pckBytes)
            out.write(trailerBuf.array())
        }
    }

    private fun writeDummyNonEmbeddedExe(file: File) {
        file.parentFile?.mkdirs()
        val pe = ByteArray(128)
        pe[0] = 'M'.code.toByte()
        pe[1] = 'Z'.code.toByte()
        FileOutputStream(file).use { it.write(pe) }
    }

    @Test
    fun testScanner_EmbeddedPck() {
        val rootDir = tempFolder.newFolder("EmbeddedGame")
        val exeFile = File(rootDir, "game.exe")
        writeDummyEmbeddedExe(exeFile, major = 4, minor = 3)

        // Also add an unrelated text file and companion asset
        File(rootDir, "readme.txt").writeText("Sample documentation")

        val result = scanner.scan(rootDir)

        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.first()
        assertEquals(CandidateSelectionReason.EMBEDDED_EXE, candidate.reason)
        assertEquals(exeFile.absolutePath, candidate.file.absolutePath)
        assertEquals("game.exe", candidate.relativePath)
        assertEquals(2, result.totalFileCount)
        assertTrue(result.dllWarnings.isEmpty())
    }

    @Test
    fun testScanner_ExternalPck() {
        val rootDir = tempFolder.newFolder("ExternalPckGame")
        val exeFile = File(rootDir, "my_game.exe")
        val pckFile = File(rootDir, "my_game.pck")
        writeDummyNonEmbeddedExe(exeFile)
        writeDummyPck(pckFile, major = 4, minor = 2)

        val result = scanner.scan(rootDir)

        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.first()
        assertEquals(CandidateSelectionReason.MATCHING_EXE_PCK, candidate.reason)
        assertEquals(pckFile.absolutePath, candidate.file.absolutePath)
        assertEquals("my_game.pck", candidate.relativePath)
        assertEquals(exeFile.absolutePath, candidate.matchingExeFile?.absolutePath)
        assertEquals(1, result.allPckFiles.size)
    }

    @Test
    fun testScanner_SeveralPcks_ResolvesLargestOrMultipleCandidates() {
        val rootDir = tempFolder.newFolder("SeveralPcksGame")
        val smallPck = File(rootDir, "patch.pck")
        val largePck = File(rootDir, "content.pck")
        writeDummyPck(smallPck, major = 4, minor = 3, paddingBytes = 50)
        writeDummyPck(largePck, major = 4, minor = 3, paddingBytes = 500)

        // Without matching exe, the largest PCK must be chosen
        val result = scanner.scan(rootDir)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.first()
        assertEquals(CandidateSelectionReason.LARGEST_PCK, candidate.reason)
        assertEquals(largePck.absolutePath, candidate.file.absolutePath)

        // Now test equal size tie -> multiple candidates returned for chooser dialog
        val tiedFolder = tempFolder.newFolder("TiedPcksGame")
        val packA = File(tiedFolder, "pack_a.pck")
        val packB = File(tiedFolder, "pack_b.pck")
        writeDummyPck(packA, major = 4, minor = 3, paddingBytes = 100)
        writeDummyPck(packB, major = 4, minor = 3, paddingBytes = 100)

        val tiedResult = scanner.scan(tiedFolder)
        assertEquals(2, tiedResult.candidates.size)
        assertTrue(tiedResult.candidates.all { it.reason == CandidateSelectionReason.LARGEST_PCK })
    }

    @Test
    fun testScanner_ExeInsideSubfolder() {
        val rootDir = tempFolder.newFolder("SubfolderGame")
        val subDir = File(rootDir, "bin")
        val exeFile = File(subDir, "launcher.exe")
        val pckFile = File(subDir, "launcher.pck")
        writeDummyNonEmbeddedExe(exeFile)
        writeDummyPck(pckFile, major = 4, minor = 4)

        // Put an extra loose file in root
        File(rootDir, "license.txt").writeText("MIT License")

        val result = scanner.scan(rootDir)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.first()
        assertEquals(CandidateSelectionReason.MATCHING_EXE_PCK, candidate.reason)
        assertEquals(pckFile.absolutePath, candidate.file.absolutePath)
        assertTrue(candidate.relativePath.replace('\\', '/').endsWith("bin/launcher.pck"))
    }

    @Test
    fun testScanner_JunkFilesExcluded() {
        val rootDir = tempFolder.newFolder("GameWithJunk")
        val mainExe = File(rootDir, "game.exe")
        writeDummyEmbeddedExe(mainExe, major = 4, minor = 3)

        // Add various junk files
        File(rootDir, "vc_redist.x64.exe").writeText("junk")
        File(rootDir, "vcredist_x86.exe").writeText("junk")
        File(rootDir, "uninstall.exe").writeText("junk")
        File(rootDir, "unins000.exe").writeText("junk")
        File(rootDir, "crashpad_handler.exe").writeText("junk")
        val installerDir = File(rootDir, "installers").apply { mkdirs() }
        File(installerDir, "setup.exe").writeText("junk")

        val result = scanner.scan(rootDir)

        assertEquals(1, result.candidates.size)
        assertEquals("game.exe", result.candidates.first().relativePath)
        assertEquals(1, result.totalFileCount) // Only game.exe counted, all junk ignored
        assertTrue(result.junkFilesExcluded.isNotEmpty())
        assertTrue(result.junkFilesExcluded.any { it.contains("vc_redist", ignoreCase = true) })
        assertTrue(result.junkFilesExcluded.any { it.contains("unins", ignoreCase = true) })
        assertTrue(result.junkFilesExcluded.any { it.contains("crashpad", ignoreCase = true) })
    }

    @Test
    fun testScanner_EmptyFolder() {
        val rootDir = tempFolder.newFolder("EmptyGameFolder")
        // Folder has only a readme file, no Godot PCK or EXE
        File(rootDir, "info.txt").writeText("Just notes")

        val result = scanner.scan(rootDir)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.allPckFiles.isEmpty())
        assertEquals(1, result.totalFileCount)
    }

    @Test
    fun testScanner_DllWarnings_WindowsOnly() {
        val rootDir = tempFolder.newFolder("DllWarningGame")
        val exe = File(rootDir, "game.exe")
        writeDummyEmbeddedExe(exe, major = 4, minor = 3)

        // Loose DLL without corresponding SO
        File(rootDir, "steam_api64.dll").writeText("dummy dll")

        val result = scanner.scan(rootDir)
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.dllWarnings.size)
        assertTrue(result.dllWarnings.first().contains("steam_api64.dll"))
    }
}
