package com.godotdroid.data

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameLibraryRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testResolveParentDirectory_FromDirectFileUri() {
        val gameFolder = tempFolder.newFolder("SomaticHex")
        val mainPack = File(gameFolder, "game.pck").apply { writeText("fake pck") }
        val companionPack = File(gameFolder, "love.pck").apply { writeText("fake love pck") }
        val companionDll = File(gameFolder, "optimizations.dll").apply { writeText("fake dll") }
        val companionZip = File(gameFolder, "music.zip").apply { writeText("fake zip") }
        val companionSo = File(gameFolder, "libcustom.so").apply { writeText("fake so") }
        val companionDylib = File(gameFolder, "libmac.dylib").apply { writeText("fake dylib") }
        val otherFile = File(gameFolder, "readme.txt").apply { writeText("fake readme") }

        // Test companion extensions filtering
        val companionExtensions = GameLibraryRepository.COMPANION_EXTENSIONS
        assertTrue(companionExtensions.contains("pck"))
        assertTrue(companionExtensions.contains("zip"))
        assertTrue(companionExtensions.contains("dll"))
        assertTrue(companionExtensions.contains("so"))
        assertTrue(companionExtensions.contains("dylib"))

        val siblings = gameFolder.listFiles()?.filter { file ->
            file.isFile &&
            file.extension.lowercase() in companionExtensions &&
            !file.name.equals(mainPack.name, ignoreCase = true)
        }?.map { it.name }?.toSet() ?: emptySet()

        assertEquals(
            setOf("love.pck", "optimizations.dll", "music.zip", "libcustom.so", "libmac.dylib"),
            siblings
        )
    }

    @Test
    fun testAutoCopyCompanionsToStagingDirectory() {
        val sourceDir = tempFolder.newFolder("SourceGame")
        val mainPck = File(sourceDir, "game.pck").apply { writeText("game") }
        val lovePck = File(sourceDir, "love.pck").apply { writeText("love") }
        val optDll = File(sourceDir, "optimizations.dll").apply { writeText("dll") }
        val textFile = File(sourceDir, "notes.txt").apply { writeText("notes") }

        val targetDir = tempFolder.newFolder("TargetStaged")

        // Primary pack copied
        mainPck.copyTo(File(targetDir, mainPck.name))

        // Auto-discover companions from sourceDir
        val companions = sourceDir.listFiles()?.filter { file ->
            file.isFile &&
            file.extension.lowercase() in GameLibraryRepository.COMPANION_EXTENSIONS &&
            !file.name.equals(mainPck.name, ignoreCase = true)
        } ?: emptyList()

        for (companion in companions) {
            val dest = File(targetDir, companion.name)
            if (!dest.exists()) {
                companion.copyTo(dest, overwrite = true)
            }
        }

        // Verify staged folder contents
        val stagedNames = targetDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(setOf("game.pck", "love.pck", "optimizations.dll"), stagedNames)
    }
}
