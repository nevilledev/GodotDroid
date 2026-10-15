package com.godotdroid.exporter

import com.android.apksig.ApkVerifier
import com.godotdroid.exporter.builder.ZipAlignedApkBuilder
import com.godotdroid.exporter.manifest.AxmlManifestPatcher
import com.godotdroid.exporter.model.StandaloneConfig
import com.godotdroid.exporter.signer.ApkSignerEngine
import com.godotdroid.overlay.model.DefaultControlPresets
import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.RendererType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manifestPatcher = AxmlManifestPatcher()
    private val signerEngine = ApkSignerEngine()

    @Test
    fun testAxmlManifestPatcher_ExactReplacements_Utf8() {
        val originalStrings = listOf(
            "com.godotdroid",
            "com.godotdroid.ui.MainActivity",
            "com.godotdroid.fileprovider",
            "GodotDroid",
            "android.intent.action.MAIN"
        )

        val axmlBytes = createSyntheticBinaryAxml(originalStrings, isUtf8 = true)
        val patchedBytes = manifestPatcher.patchManifest(
            manifestBytes = axmlBytes,
            newPackageName = "com.godotdroid.game.celeste",
            newAppTitle = "Celeste Mobile"
        )

        // Verify AXML Header
        val buf = ByteBuffer.wrap(patchedBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, buf.getShort().toInt() and 0xFFFF) // RES_XML_TYPE
        assertEquals(8, buf.getShort().toInt() and 0xFFFF)
        assertEquals(patchedBytes.size, buf.getInt())

        // Verify StringPool Header
        assertEquals(0x0001, buf.getShort().toInt() and 0xFFFF) // RES_STRING_POOL_TYPE
        assertEquals(28, buf.getShort().toInt() and 0xFFFF)
        val chunkSize = buf.getInt()
        val stringCount = buf.getInt()
        assertEquals(5, stringCount)

        // Read offsets and strings
        buf.position(8 + 28) // skip headers
        val offsets = IntArray(stringCount) { buf.getInt() }
        val stringsStart = 8 + 28 + (stringCount * 4)

        val decodedStrings = mutableListOf<String>()
        for (i in 0 until stringCount) {
            buf.position(stringsStart + offsets[i])
            readUtf8Len(buf) // char count
            val byteCount = readUtf8Len(buf)
            val strBytes = ByteArray(byteCount)
            buf.get(strBytes)
            decodedStrings.add(String(strBytes, Charsets.UTF_8))
        }

        assertEquals("com.godotdroid.game.celeste", decodedStrings[0])
        assertEquals("com.godotdroid.ui.MainActivity", decodedStrings[1]) // Class name preserved!
        assertEquals("com.godotdroid.game.celeste.fileprovider", decodedStrings[2])
        assertEquals("Celeste Mobile", decodedStrings[3])
        assertEquals("android.intent.action.MAIN", decodedStrings[4])
    }

    @Test
    fun testAxmlManifestPatcher_ExactReplacements_Utf16() {
        val originalStrings = listOf(
            "com.godotdroid",
            "com.godotdroid.runtime.GodotRunnerActivity",
            "GodotDroid"
        )

        val axmlBytes = createSyntheticBinaryAxml(originalStrings, isUtf8 = false)
        val patchedBytes = manifestPatcher.patchManifest(
            manifestBytes = axmlBytes,
            newPackageName = "com.godotdroid.game.hollowknight",
            newAppTitle = "Hollow Knight"
        )

        val buf = ByteBuffer.wrap(patchedBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, buf.getShort().toInt() and 0xFFFF)
        assertEquals(patchedBytes.size, buf.getInt(4))

        // Read strings
        val stringCount = buf.getInt(8 + 8)
        val stringsStart = 8 + 28 + (stringCount * 4)
        buf.position(8 + 28)
        val offsets = IntArray(stringCount) { buf.getInt() }

        val decodedStrings = mutableListOf<String>()
        for (i in 0 until stringCount) {
            buf.position(stringsStart + offsets[i])
            val charCount = buf.getShort().toInt() and 0xFFFF
            val strBytes = ByteArray(charCount * 2)
            buf.get(strBytes)
            decodedStrings.add(String(strBytes, Charsets.UTF_16LE))
        }

        assertEquals("com.godotdroid.game.hollowknight", decodedStrings[0])
        assertEquals("com.godotdroid.runtime.GodotRunnerActivity", decodedStrings[1]) // Preserved
        assertEquals("Hollow Knight", decodedStrings[2])
    }

    @Test
    fun testApkSignerEngine_GenerateKeyAndSignApk() {
        val keystoreFile = tempFolder.newFile("test_keystore.p12")
        keystoreFile.delete()

        val credentials = signerEngine.getOrCreateSigningKey(keystoreFile)
        assertNotNull(credentials.privateKey)
        assertNotNull(credentials.certificate)
        assertTrue(keystoreFile.exists())

        // Second call reloads existing credentials
        val reloaded = signerEngine.getOrCreateSigningKey(keystoreFile)
        assertEquals(credentials.privateKey.algorithm, reloaded.privateKey.algorithm)

        // Create mock unsigned APK with AndroidManifest.xml
        val unsignedApk = tempFolder.newFile("mock_unsigned.apk")
        val syntheticAxml = createSyntheticBinaryAxml(listOf("com.godotdroid"), isUtf8 = true)
        ZipOutputStream(FileOutputStream(unsignedApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(syntheticAxml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex_content".toByteArray())
            zos.closeEntry()
        }

        val signedApk = tempFolder.newFile("mock_signed.apk")
        val signed = signerEngine.signApk(unsignedApk, signedApk, credentials)
        assertTrue(signed)
        assertTrue(signedApk.exists())
        assertTrue(signedApk.length() > 0)

        // Verify with ApkSignerEngine
        val isVerified = signerEngine.verifyApk(signedApk, 26)
        assertTrue("APK must pass cryptographic verification", isVerified)

        // Also verify directly via official Google ApkVerifier
        val verifierResult = ApkVerifier.Builder(signedApk)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
        assertTrue(verifierResult.isVerified)
        assertTrue(verifierResult.isVerifiedUsingV2Scheme || verifierResult.isVerifiedUsingV3Scheme)
        ZipFile(signedApk).use { zip ->
            assertNotNull(zip.getEntry("META-INF/MANIFEST.MF"))
        }
    }

    @Test
    fun testZipAlignedApkBuilder_And_ApkVerifier_EndToEnd() {
        // 1. Create Synthetic Base APK
        val baseApk = tempFolder.newFile("base_template.apk")
        val syntheticAxml = createSyntheticBinaryAxml(
            listOf("com.godotdroid", "GodotDroid", "com.godotdroid.fileprovider"),
            isUtf8 = true
        )
        ZipOutputStream(FileOutputStream(baseApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(syntheticAxml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("base_dex_bytecode".toByteArray())
            zos.closeEntry()
        }

        // 2. Create Synthetic PCK (Format v2)
        val pckFile = tempFolder.newFile("game.pck")
        val pckContent = "[application]\nconfig/name=\"Test Game\"\n"
        val pckContentBytes = pckContent.toByteArray(Charsets.UTF_8)
        val pckPathBytes = "res://project.godot".toByteArray(Charsets.UTF_8)
        val paddedPathLen = ((pckPathBytes.size + 3) / 4) * 4
        val dirSize = 4 + (4 + paddedPathLen + 8 + 8 + 16 + 4)
        val dataOffset = 96L + dirSize

        val pckBb = ByteBuffer.allocate((dataOffset + pckContentBytes.size).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        pckBb.putInt(PckConstants.PCK_MAGIC)
        pckBb.putInt(2)
        pckBb.putInt(4)
        pckBb.putInt(3)
        pckBb.putInt(0)
        pckBb.putInt(PckConstants.PACK_REL_FILEBASE)
        pckBb.putLong(0L)
        pckBb.put(ByteArray(64))
        pckBb.putInt(1)
        pckBb.putInt(pckPathBytes.size)
        pckBb.put(pckPathBytes)
        if (paddedPathLen > pckPathBytes.size) pckBb.put(ByteArray(paddedPathLen - pckPathBytes.size))
        pckBb.putLong(dataOffset)
        pckBb.putLong(pckContentBytes.size.toLong())
        pckBb.put(ByteArray(16))
        pckBb.putInt(0)
        pckBb.put(pckContentBytes)
        FileOutputStream(pckFile).use { it.write(pckBb.array()) }

        // 3. Create mock Core directory
        val coreDir = tempFolder.newFolder("core_4_3")
        File(coreDir, "libgodot_android.so").writeBytes("dummy_so_bytes".toByteArray())
        File(coreDir, "godot-lib.dex").writeBytes("dummy_core_dex".toByteArray())

        // 4. Build Unsigned Aligned APK
        val unsignedApk = tempFolder.newFile("unsigned_aligned.apk")
        val config = StandaloneConfig(
            gameId = "game_celeste",
            gameTitle = "Celeste",
            engineVersionKey = "4.3",
            rendererType = "MOBILE",
            controlPreset = DefaultControlPresets.PRESET_PC_KEYBOARD,
            injectSteamStub = true
        )
        val layout = DefaultControlPresets.createPcKeyboardPreset("game_celeste")

        val builder = ZipAlignedApkBuilder()
        builder.buildUnsignedApk(
            baseApkFile = baseApk,
            outputUnsignedApk = unsignedApk,
            customPackageName = "com.godotdroid.game.celeste",
            customGameTitle = "Celeste",
            standaloneConfig = config,
            patchedPckFile = pckFile,
            controlsLayoutJson = layout.toJson().toString(2),
            coreDir = coreDir
        )

        // 5. Sign APK
        val signedApk = tempFolder.newFile("signed_final.apk")
        val keystoreFile = tempFolder.newFile("keystore.p12")
        keystoreFile.delete()
        val credentials = signerEngine.getOrCreateSigningKey(keystoreFile)

        val signSuccess = signerEngine.signApk(unsignedApk, signedApk, credentials)
        assertTrue("APK must sign successfully", signSuccess)

        // 6. Verify with ApkVerifier
        val verifierResult = ApkVerifier.Builder(signedApk)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
        assertTrue("Signed APK must be cryptographically valid: ${verifierResult.errors}", verifierResult.isVerified)
        assertTrue(verifierResult.isVerifiedUsingV2Scheme || verifierResult.isVerifiedUsingV3Scheme)

        // 7. Verify entries inside signed APK
        ZipFile(signedApk).use { zip ->
            assertNotNull(zip.getEntry("AndroidManifest.xml"))
            assertNotNull(zip.getEntry("classes.dex"))
            assertNotNull(zip.getEntry("assets/standalone_config.json"))
            assertNotNull(zip.getEntry("assets/controls.json"))
            assertNotNull(zip.getEntry("assets/core/libgodot_android.so"))
            assertNotNull(zip.getEntry("assets/core/godot-lib.dex"))
            assertNotNull(zip.getEntry("assets/main.pck"))

            val pckEntry = zip.getEntry("assets/main.pck")
            assertEquals(ZipEntry.STORED, pckEntry.method)
        }
    }

    private fun createSyntheticBinaryAxml(strings: List<String>, isUtf8: Boolean): ByteArray {
        val stringDataStream = ByteArrayOutputStream()
        val stringOffsets = IntArray(strings.size)

        for (i in strings.indices) {
            stringOffsets[i] = stringDataStream.size()
            val s = strings[i]
            if (isUtf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeUtf8Len(stringDataStream, s.length)
                writeUtf8Len(stringDataStream, bytes.size)
                stringDataStream.write(bytes)
                stringDataStream.write(0)
            } else {
                val bytes = s.toByteArray(Charsets.UTF_16LE)
                writeUtf16Len(stringDataStream, s.length)
                stringDataStream.write(bytes)
                stringDataStream.write(0)
                stringDataStream.write(0)
            }
        }
        val stringBytes = stringDataStream.toByteArray()
        val headerSize = 28
        val offsetTableSize = strings.size * 4
        val stringsStart = headerSize + offsetTableSize
        val rawChunkSize = stringsStart + stringBytes.size
        val pad = (4 - (rawChunkSize % 4)) % 4
        val poolChunkSize = rawChunkSize + pad

        val poolBuf = ByteBuffer.allocate(poolChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        poolBuf.putShort(0x0001.toShort()) // RES_STRING_POOL_TYPE
        poolBuf.putShort(28.toShort())
        poolBuf.putInt(poolChunkSize)
        poolBuf.putInt(strings.size)
        poolBuf.putInt(0) // styleCount
        poolBuf.putInt(if (isUtf8) 0x100 else 0) // flags
        poolBuf.putInt(stringsStart)
        poolBuf.putInt(0) // stylesStart
        for (off in stringOffsets) poolBuf.putInt(off)
        poolBuf.put(stringBytes)
        for (p in 0 until pad) poolBuf.put(0.toByte())

        // Dummy trailing chunk (ResourceMap: 8 bytes header + 8 bytes payload = 16 bytes)
        val dummyChunk = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        dummyChunk.putShort(0x0180.toShort()) // RES_XML_RESOURCE_MAP_TYPE
        dummyChunk.putShort(8.toShort())
        dummyChunk.putInt(16)
        dummyChunk.putInt(0x01010000)
        dummyChunk.putInt(0x01010001)

        val totalSize = 8 + poolChunkSize + 16
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003.toShort()) // RES_XML_TYPE
        out.putShort(8.toShort())
        out.putInt(totalSize)
        out.put(poolBuf.array())
        out.put(dummyChunk.array())

        return out.array()
    }

    private fun readUtf8Len(buf: ByteBuffer): Int {
        val b1 = buf.get().toInt() and 0xFF
        return if ((b1 and 0x80) != 0) {
            val b2 = buf.get().toInt() and 0xFF
            ((b1 and 0x7F) shl 8) or b2
        } else {
            b1
        }
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun writeUtf16Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val s1 = ((len shr 16) and 0x7FFF) or 0x8000
            val s2 = len and 0xFFFF
            out.write(s1 and 0xFF)
            out.write((s1 shr 8) and 0xFF)
            out.write(s2 and 0xFF)
            out.write((s2 shr 8) and 0xFF)
        } else {
            out.write(len and 0xFF)
            out.write((len shr 8) and 0xFF)
        }
    }
}
