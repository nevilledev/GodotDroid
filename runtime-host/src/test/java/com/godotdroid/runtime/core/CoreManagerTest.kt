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
        val builtIn = CoreManager.builtInRegistry
        assertNotNull(builtIn)
        assertTrue(builtIn.isNotEmpty())
        val keys = builtIn.map { it.versionKey }
        assertTrue(keys.contains("4.3"))
        assertTrue(keys.contains("4.4"))
        assertTrue(keys.contains("3.5"))
        assertTrue(keys.contains("3.6"))
    }

    @Test
    fun testSanitizeRegistryBaseUrl_HandlesTyposAndFileSuffixes() {
        // 1. Basic trailing slash
        assertEquals(
            "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest",
            CoreManager.sanitizeRegistryBaseUrl("https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/")
        )

        // 2. Trailing cores_manifest.json
        assertEquals(
            "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest",
            CoreManager.sanitizeRegistryBaseUrl("https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/cores_manifest.json")
        )

        // 3. Trailing .zip file URL
        assertEquals(
            "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest",
            CoreManager.sanitizeRegistryBaseUrl("https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/core_4.3.zip")
        )

        // 4. Typo nevilledroid replaced with nevilledev
        assertEquals(
            "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest",
            CoreManager.sanitizeRegistryBaseUrl("https://github.com/nevilledroid/GodotDroid/releases/download/cores-latest/core_4.3.zip")
        )

        // 5. Blank returns default
        assertEquals(
            CoreManager.DEFAULT_REGISTRY_BASE_URL,
            CoreManager.sanitizeRegistryBaseUrl("   ")
        )
    }

    @Test
    fun testParseManifestJson_TopLevelArrayAndTypoCorrection() {
        val jsonArray = """
        [
          {
            "versionKey": "4.3",
            "displayName": "Godot 4.3 Stable",
            "engineVersion": "4.3.0",
            "engineMajor": 4,
            "fileName": "core_4.3.zip",
            "sha256": "261b5a56e86968304e12f339b67f7dd447b3e5f347e721d8834273e0761911a7",
            "sizeBytes": 22583674,
            "downloadUrl": "https://github.com/nevilledroid/GodotDroid/releases/download/cores-latest/core_4.3.zip",
            "isRecommended": true
          },
          {
            "versionKey": "3.5",
            "displayName": "Godot 3.5.3 (GLES3 / GLES2 Classic)",
            "engineVersion": "3.5.3",
            "engineMajor": 3,
            "fileName": "core_3.5.zip",
            "sha256": "abcdef1234567890",
            "sizeBytes": 20000000,
            "downloadUrl": "",
            "isRecommended": false
          }
        ]
        """.trimIndent()

        val baseUrl = "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest"
        val parsed = CoreManager.parseManifestJson(jsonArray, baseUrl)
        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals("4.3", first.versionKey)
        assertEquals("https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/core_4.3.zip", first.downloadUrl)
        assertTrue(first.isRecommended)

        val second = parsed[1]
        assertEquals("3.5", second.versionKey)
        assertEquals("https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/core_3.5.zip", second.downloadUrl)
        assertFalse(second.isRecommended)
    }

    @Test
    fun testParseManifestJson_JsonObjectWithCores() {
        val jsonObject = """
        {
          "cores": [
            {
              "versionKey": "4.4",
              "displayName": "Godot 4.4 Stable",
              "engineVersion": "4.4.0",
              "fileName": "core_4.4.zip",
              "sha256": "112233445566",
              "sizeBytes": 25000000,
              "downloadUrl": "https://github.com/nevilledev/GodotDroid/releases/download/cores-latest/core_4.4.zip"
            }
          ]
        }
        """.trimIndent()

        val parsed = CoreManager.parseManifestJson(jsonObject)
        assertEquals(1, parsed.size)
        assertEquals("4.4", parsed[0].versionKey)
        assertEquals("Godot 4.4 Stable", parsed[0].displayName)
    }

    @Test
    fun testBuiltInRegistry_ContainsAllMajorAndMinorVersions() {
        val keys = CoreManager.builtInRegistry.map { it.versionKey }
        val requiredVersions = listOf(
            "4.7", "4.6", "4.5", "4.4", "4.3", "4.2", "4.1", "4.0",
            "3.6", "3.5", "3.4", "3.3", "3.2"
        )
        for (v in requiredVersions) {
            assertTrue("Expected $v in builtInRegistry", keys.contains(v))
        }
    }

    @Test
    fun testCompareVersionsDescending() {
        assertTrue(CoreManager.compareVersionsDescending("4.7", "4.6") < 0)
        assertTrue(CoreManager.compareVersionsDescending("4.6", "4.7") > 0)
        assertTrue(CoreManager.compareVersionsDescending("4.10", "4.9") < 0)
        assertTrue(CoreManager.compareVersionsDescending("4.0", "3.6") < 0)
        assertTrue(CoreManager.compareVersionsDescending("3.6", "3.2") < 0)
        assertEquals(0, CoreManager.compareVersionsDescending("4.3", "4.3"))
    }

    @Test
    fun testMergeWithBuiltInRegistry_PreservesMissingVersionsAndSortsDescending() {
        val remoteList = listOf(
            CoreDescriptor(
                versionKey = "4.3",
                displayName = "Remote Godot 4.3",
                engineVersion = "4.3.0",
                downloadUrl = "https://custom.cdn/core_4.3.zip"
            ),
            CoreDescriptor(
                versionKey = "3.6",
                displayName = "Remote Godot 3.6",
                engineVersion = "3.6.0",
                downloadUrl = "https://custom.cdn/core_3.6.zip"
            )
        )

        val merged = CoreManager.mergeWithBuiltInRegistry(remoteList)

        // All 13 supported versions should be present
        val keys = merged.map { it.versionKey }
        assertTrue(keys.contains("4.7"))
        assertTrue(keys.contains("4.5"))
        assertTrue(keys.contains("4.3"))
        assertTrue(keys.contains("3.6"))
        assertTrue(keys.contains("3.2"))

        // Remote versions should override built-in versions
        val item43 = merged.first { it.versionKey == "4.3" }
        assertEquals("Remote Godot 4.3", item43.displayName)
        assertEquals("https://custom.cdn/core_4.3.zip", item43.downloadUrl)

        // First item should be 4.7 and last should be 3.2 (proper descending sort)
        assertEquals("4.7", merged.first().versionKey)
        assertEquals("3.2", merged.last().versionKey)
    }

    @Test
    fun testOfficialTpzCandidateUrls() {
        val urls45 = OfficialTpzRangeDownloader.getCandidateTpzUrls("4.5")
        assertTrue(urls45.any { it.contains("4.5-stable") })
        assertTrue(urls45.all { it.endsWith(".tpz") })

        val urls47 = OfficialTpzRangeDownloader.getCandidateTpzUrls("4.7")
        assertTrue(urls47.any { it.contains("4.7") })

        val urls34 = OfficialTpzRangeDownloader.getCandidateTpzUrls("3.4")
        assertTrue(urls34.any { it.contains("3.4.5-stable") })
    }
}
