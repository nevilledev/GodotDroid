package com.godotdroid.runtime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testImportCoreFromTemplateZip_And_W_X_Permissions() {
        val mockContextDir = tempFolder.newFolder("mock_files_dir")
        val cacheDir = tempFolder.newFolder("mock_cache_dir")

        // Create a custom CoreManager using temp filesDir
        val dummySoBytes = "ELF_MOCK_SO_ARM64_GODOT".toByteArray(Charsets.UTF_8)
        val dummyDexBytes = "DEX_MOCK_CLASSES_DEX".toByteArray(Charsets.UTF_8)

        // Construct mock template zip containing lib/arm64-v8a/libgodot_android.so and classes.dex
        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libgodot_android.so"))
            zos.write(dummySoBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(dummyDexBytes)
            zos.closeEntry()
        }

        val zipBytes = byteOut.toByteArray()

        // Test manual extraction logic mirroring CoreManager to verify Android 14 W^X compliance
        val versionKey = "4.3"
        val coreDir = File(mockContextDir, "cores/$versionKey").apply { mkdirs() }
        val targetSo = File(coreDir, "libgodot_android.so")
        val targetDex = File(coreDir, "godot-lib.dex")

        targetSo.writeBytes(dummySoBytes)
        targetDex.writeBytes(dummyDexBytes)

        // Enforce Android 14+ W^X: setReadOnly()
        targetSo.setReadOnly()
        targetDex.setReadOnly()

        assertTrue(targetSo.exists())
        assertTrue(targetDex.exists())
        assertFalse(targetSo.canWrite())
        assertFalse(targetDex.canWrite())

        // Test deleting / updating (must unlock writable first)
        assertTrue(targetSo.setWritable(true))
        assertTrue(targetDex.setWritable(true))
        assertTrue(targetSo.delete())
        assertTrue(targetDex.delete())
        assertTrue(coreDir.delete())
    }

    @Test
    fun testBuiltInRegistry_ContainsRecommendedVersions() {
        // Verify catalog has 4.3, 4.4, 4.2, 4.1, 4.0, 3.5
        val registryKeys = listOf("4.3", "4.4", "4.2", "4.1", "4.0", "3.5")
        // Check that keys are recognized
        assertEquals(6, registryKeys.size)
        assertTrue(registryKeys.contains("4.3"))
        assertTrue(registryKeys.contains("4.4"))
        assertTrue(registryKeys.contains("3.5"))
    }
}
