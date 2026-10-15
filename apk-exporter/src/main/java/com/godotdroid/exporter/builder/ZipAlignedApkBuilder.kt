package com.godotdroid.exporter.builder

import com.godotdroid.exporter.manifest.AxmlManifestPatcher
import com.godotdroid.exporter.model.StandaloneConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 4-Byte Aligned ZIP/APK Packager.
 * Repackages the base GodotDroid APK, patches AndroidManifest.xml, and injects
 * standalone game assets (main.pck, controls.json, engine core binaries) with
 * 4-byte alignment for uncompressed STORED entries.
 */
class ZipAlignedApkBuilder(
    private val manifestPatcher: AxmlManifestPatcher = AxmlManifestPatcher()
) {

    class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    /**
     * Builds an unsigned APK by taking entries from baseApkFile and injecting standalone assets.
     */
    fun buildUnsignedApk(
        baseApkFile: File,
        outputUnsignedApk: File,
        customPackageName: String,
        customGameTitle: String,
        standaloneConfig: StandaloneConfig,
        patchedPckFile: File,
        controlsLayoutJson: String,
        coreDir: File,
        customIconPng: ByteArray? = null,
        onProgress: ((step: String, percent: Float) -> Unit)? = null
    ) {
        if (!baseApkFile.exists()) {
            throw IllegalArgumentException("Base APK template does not exist: ${baseApkFile.absolutePath}")
        }
        if (!patchedPckFile.exists()) {
            throw IllegalArgumentException("Patched PCK does not exist: ${patchedPckFile.absolutePath}")
        }

        if (outputUnsignedApk.exists()) {
            outputUnsignedApk.delete()
        }
        outputUnsignedApk.parentFile?.mkdirs()

        onProgress?.invoke("Preparing base APK entries...", 0.10f)

        val fos = FileOutputStream(outputUnsignedApk)
        val countingOut = CountingOutputStream(fos)
        val zos = ZipOutputStream(countingOut)

        val seenEntries = mutableSetOf<String>()

        ZipFile(baseApkFile).use { zip ->
            val entries = zip.entries().toList()
            val totalEntries = entries.size.toFloat()

            // 1. Process and copy entries from base APK
            for ((index, entry) in entries.withIndex()) {
                val name = entry.name

                // Skip signature files
                if (isSignatureFile(name)) continue

                // Skip files we are overriding
                if (name == "assets/standalone_config.json" ||
                    name == "assets/main.pck" ||
                    name == "assets/controls.json" ||
                    name.startsWith("assets/core/")
                ) {
                    continue
                }

                seenEntries.add(name)

                if (name == "AndroidManifest.xml") {
                    onProgress?.invoke("Patching AndroidManifest.xml...", 0.30f)
                    val originalBytes = zip.getInputStream(entry).use { it.readBytes() }
                    val patchedBytes = manifestPatcher.patchManifest(
                        originalBytes,
                        customPackageName,
                        customGameTitle
                    )
                    writeDeflatedEntry(zos, countingOut, "AndroidManifest.xml", patchedBytes)
                } else if (customIconPng != null && isAppIconEntry(name)) {
                    writeDeflatedEntry(zos, countingOut, name, customIconPng)
                } else {
                    // Copy existing entry
                    val entryBytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.method == ZipEntry.STORED) {
                        writeStoredAlignedEntry(zos, countingOut, name, entryBytes)
                    } else {
                        writeDeflatedEntry(zos, countingOut, name, entryBytes)
                    }
                }

                if (index % 50 == 0) {
                    val progress = 0.10f + (index.toFloat() / totalEntries) * 0.40f
                    onProgress?.invoke("Copying APK resources...", progress)
                }
            }

            // 2. Inject Standalone Configuration
            onProgress?.invoke("Injecting standalone configuration...", 0.60f)
            val configBytes = standaloneConfig.toJson().toString(2).toByteArray(Charsets.UTF_8)
            writeDeflatedEntry(zos, countingOut, "assets/standalone_config.json", configBytes)

            // 3. Inject Controls Layout JSON
            val controlsBytes = controlsLayoutJson.toByteArray(Charsets.UTF_8)
            writeDeflatedEntry(zos, countingOut, "assets/controls.json", controlsBytes)

            // 4. Inject Engine Core Binaries
            onProgress?.invoke("Bundling Godot engine runtime core...", 0.70f)
            val libGodotFile = File(coreDir, "libgodot_android.so")
            if (libGodotFile.exists()) {
                val libBytes = libGodotFile.readBytes()
                writeStoredAlignedEntry(zos, countingOut, "assets/core/libgodot_android.so", libBytes)
            }

            val dexFile = File(coreDir, "godot-lib.dex")
            if (dexFile.exists()) {
                val dexBytes = dexFile.readBytes()
                writeDeflatedEntry(zos, countingOut, "assets/core/godot-lib.dex", dexBytes)
            }

            val libcFile = File(coreDir, "libc++_shared.so")
            if (libcFile.exists()) {
                val libcBytes = libcFile.readBytes()
                writeStoredAlignedEntry(zos, countingOut, "assets/core/libc++_shared.so", libcBytes)
            }

            // 5. Inject Patched Game PCK (STORED with 4-byte alignment)
            onProgress?.invoke("Bundling 4-byte aligned game package...", 0.85f)
            val pckBytes = patchedPckFile.readBytes()
            writeStoredAlignedEntry(zos, countingOut, "assets/main.pck", pckBytes)
        }

        zos.finish()
        zos.flush()
        fos.close()

        onProgress?.invoke("Unsigned APK packaging complete.", 0.95f)
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".EC") ||
                upper == "META-INF/MANIFEST.MF"
    }

    private fun isAppIconEntry(name: String): Boolean {
        return name.contains("ic_launcher") && name.endsWith(".png")
    }

    private fun writeDeflatedEntry(
        zos: ZipOutputStream,
        countingOut: CountingOutputStream,
        name: String,
        data: ByteArray
    ) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = System.currentTimeMillis()
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    /**
     * Writes an uncompressed (STORED) entry aligned to a 4-byte boundary in the output file.
     */
    private fun writeStoredAlignedEntry(
        zos: ZipOutputStream,
        countingOut: CountingOutputStream,
        name: String,
        data: ByteArray
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val currentOffset = countingOut.count

        // Local header size = 30 + nameBytes.size + extraBytes.size
        // Payload offset = currentOffset + 30 + nameBytes.size + extraLen
        val basePayloadOffset = currentOffset + 30 + nameBytes.size
        val rem = (basePayloadOffset % 4).toInt()
        val padSize = if (rem == 0) 0 else 4 - rem

        val extra = if (padSize > 0) ByteArray(padSize) else null

        val crc = CRC32().apply { update(data) }.value

        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc
            time = System.currentTimeMillis()
            if (extra != null) {
                this.extra = extra
            }
        }

        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }
}
