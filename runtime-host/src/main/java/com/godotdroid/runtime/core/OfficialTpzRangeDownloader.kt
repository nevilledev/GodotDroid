package com.godotdroid.runtime.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Downloads Godot Android export template APKs directly from official Godot `.tpz` archives
 * hosted on GitHub Releases using HTTP Range requests.
 *
 * Instead of downloading the full ~800 MB - 1 GB `.tpz` archive, this downloader:
 * 1. Fetches the ZIP End-of-Central-Directory (EOCD) and Central Directory via Range requests (~50 KB).
 * 2. Locates `templates/android_release.apk` (or `android_release.apk`).
 * 3. Streams only the ~25 MB - 90 MB APK payload (decompressing DEFLATE on-the-fly) directly to disk.
 */
class OfficialTpzRangeDownloader(
    private val httpClient: OkHttpClient
) {

    /**
     * Attempts to find and download `android_release.apk` for the specified version.
     * Tries candidate release tags and URLs until one succeeds.
     */
    suspend fun downloadForVersion(
        versionKey: String,
        engineVersion: String? = null,
        targetApkFile: File,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val candidateUrls = getCandidateTpzUrls(versionKey, engineVersion)
        Log.i(TAG, "Attempting to download Godot $versionKey template from ${candidateUrls.size} candidate URLs")

        var lastException: Throwable? = null
        for (url in candidateUrls) {
            onProgress?.invoke(DownloadProgress(0, 100, 2, "Connecting to official Godot release (${url.substringAfterLast('/')})..."))
            Log.i(TAG, "Trying TPZ URL: $url")
            val result = downloadAndroidTemplateViaHttpRange(url, targetApkFile, onProgress)
            if (result.isSuccess) {
                Log.i(TAG, "Successfully downloaded Android template for Godot $versionKey from $url")
                return@withContext result
            } else {
                lastException = result.exceptionOrNull()
                Log.w(TAG, "Candidate URL failed ($url): ${lastException?.message}")
            }
        }

        Result.failure(
            lastException ?: IOException("Could not download Godot $versionKey export template from any official release mirror.")
        )
    }

    /**
     * Downloads `android_release.apk` from a single `.tpz` archive URL using HTTP Range requests.
     */
    suspend fun downloadAndroidTemplateViaHttpRange(
        tpzUrl: String,
        targetApkFile: File,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Probe file length using Range: bytes=0-0
            val probeRequest = Request.Builder()
                .url(tpzUrl)
                .header("User-Agent", "GodotDroid/1.0")
                .header("Range", "bytes=0-0")
                .build()

            val probeResponse = httpClient.newCall(probeRequest).execute()
            if (!probeResponse.isSuccessful && probeResponse.code != 206) {
                probeResponse.close()
                return@withContext Result.failure(IOException("HTTP error ${probeResponse.code} accessing $tpzUrl"))
            }

            val totalSize = parseTotalSizeFromResponse(probeResponse)
            probeResponse.close()

            if (totalSize <= 0) {
                return@withContext Result.failure(IOException("Could not determine total archive size from $tpzUrl"))
            }

            Log.i(TAG, "Total TPZ archive size: $totalSize bytes (~${totalSize / (1024 * 1024)} MB)")
            onProgress?.invoke(DownloadProgress(0, totalSize, 5, "Locating Android template within archive..."))

            // Step 2: Fetch the last 65536 bytes to find the End of Central Directory (EOCD)
            val tailSize = minOf(65536L, totalSize)
            val tailStart = totalSize - tailSize
            val tailBytes = fetchRangeBytes(tpzUrl, tailStart, totalSize - 1)
                ?: return@withContext Result.failure(IOException("Failed to fetch ZIP EOCD tail bytes from $tpzUrl"))

            val eocdOffsetInTail = findEocdOffset(tailBytes)
            if (eocdOffsetInTail < 0) {
                return@withContext Result.failure(IOException("ZIP End-of-Central-Directory signature not found in $tpzUrl"))
            }

            val cdSize = getUint32LE(tailBytes, eocdOffsetInTail + 12)
            val cdOffset = getUint32LE(tailBytes, eocdOffsetInTail + 16)
            Log.i(TAG, "ZIP Central Directory: size=$cdSize bytes, offset=$cdOffset")

            if (cdSize <= 0 || cdOffset <= 0 || cdOffset + cdSize > totalSize) {
                return@withContext Result.failure(IOException("Invalid ZIP Central Directory bounds: size=$cdSize, offset=$cdOffset"))
            }

            // Step 3: Fetch Central Directory bytes
            onProgress?.invoke(DownloadProgress(0, totalSize, 8, "Reading archive directory..."))
            val cdBytes = fetchRangeBytes(tpzUrl, cdOffset, cdOffset + cdSize - 1)
                ?: return@withContext Result.failure(IOException("Failed to fetch Central Directory bytes from $tpzUrl"))

            // Step 4: Scan Central Directory for android_release.apk
            val entry = findAndroidApkEntryInCd(cdBytes)
                ?: return@withContext Result.failure(IOException("No Android template APK found in $tpzUrl"))

            Log.i(TAG, "Found target entry: ${entry.fileName}, compMethod=${entry.compressionMethod}, compSize=${entry.compressedSize}, uncompSize=${entry.uncompressedSize}")

            // Step 5: Fetch and stream the APK payload
            onProgress?.invoke(DownloadProgress(0, entry.uncompressedSize, 10, "Streaming Android template (${entry.uncompressedSize / (1024 * 1024)} MB)..."))

            // Request from localHeaderOffset to (localHeaderOffset + 30 + 1024 + compressedSize)
            val payloadRangeStart = entry.localHeaderOffset
            val payloadRangeEnd = entry.localHeaderOffset + 30L + 1024L + entry.compressedSize

            val payloadRequest = Request.Builder()
                .url(tpzUrl)
                .header("User-Agent", "GodotDroid/1.0")
                .header("Range", "bytes=$payloadRangeStart-$payloadRangeEnd")
                .build()

            val payloadResponse = httpClient.newCall(payloadRequest).execute()
            if (!payloadResponse.isSuccessful && payloadResponse.code != 206) {
                payloadResponse.close()
                return@withContext Result.failure(IOException("HTTP error ${payloadResponse.code} streaming template payload"))
            }

            val bodyStream = payloadResponse.body?.byteStream()
                ?: return@withContext Result.failure(IOException("Empty response body when streaming template"))

            val tempTarget = File(targetApkFile.parentFile, "${targetApkFile.name}.downloading")
            if (tempTarget.exists()) tempTarget.delete()

            try {
                bodyStream.use { rawInput ->
                    // Read Local File Header (30 bytes)
                    val lfhHeader = ByteArray(30)
                    readFully(rawInput, lfhHeader)
                    val lfhSig = getUint32LE(lfhHeader, 0)
                    if (lfhSig != 0x04034b50L) {
                        throw IOException("Invalid Local File Header signature: 0x${java.lang.Long.toHexString(lfhSig)}")
                    }

                    val lfhNameLen = getUint16LE(lfhHeader, 26)
                    val lfhExtraLen = getUint16LE(lfhHeader, 28)
                    val skipBytes = lfhNameLen + lfhExtraLen
                    skipFully(rawInput, skipBytes.toLong())

                    val boundedStream = BoundedInputStream(rawInput, entry.compressedSize)
                    val dataStream = if (entry.compressionMethod == 8) {
                        InflaterInputStream(boundedStream, Inflater(true))
                    } else {
                        boundedStream
                    }

                    FileOutputStream(tempTarget).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        var written = 0L
                        var lastProgressReport = 0L
                        val totalExpected = if (entry.uncompressedSize > 0) entry.uncompressedSize else entry.compressedSize

                        while (dataStream.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressReport > 100 || written == totalExpected) {
                                lastProgressReport = now
                                val percent = if (totalExpected > 0) ((written * 100) / totalExpected).toInt().coerceIn(10, 95) else 50
                                val mbWritten = written / (1024 * 1024)
                                val mbTotal = totalExpected / (1024 * 1024)
                                onProgress?.invoke(
                                    DownloadProgress(
                                        bytesRead = written,
                                        totalBytes = totalExpected,
                                        percentage = percent,
                                        currentStage = "Downloading template: $mbWritten / $mbTotal MB ($percent%)"
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (targetApkFile.exists()) targetApkFile.delete()
                if (!tempTarget.renameTo(targetApkFile)) {
                    tempTarget.copyTo(targetApkFile, overwrite = true)
                    tempTarget.delete()
                }

                Log.i(TAG, "Android template extracted successfully: ${targetApkFile.absolutePath} (${targetApkFile.length()} bytes)")
                Result.success(targetApkFile)
            } finally {
                payloadResponse.close()
                if (tempTarget.exists()) tempTarget.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download Android template via HTTP Range from $tpzUrl", e)
            Result.failure(e)
        }
    }

    private fun fetchRangeBytes(url: String, startByte: Long, endByte: Long): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GodotDroid/1.0")
            .header("Range", "bytes=$startByte-$endByte")
            .build()

        val response = httpClient.newCall(request).execute()
        return try {
            if (response.isSuccessful || response.code == 206) {
                response.body?.bytes()
            } else {
                null
            }
        } finally {
            response.close()
        }
    }

    private fun parseTotalSizeFromResponse(response: Response): Long {
        val contentRange = response.header("Content-Range")
        if (!contentRange.isNullOrBlank()) {
            val totalStr = contentRange.substringAfterLast('/', "")
            val parsed = totalStr.toLongOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        val cl = response.body?.contentLength() ?: -1L
        if (cl > 0) return cl
        return -1L
    }

    private fun findEocdOffset(tail: ByteArray): Int {
        val sig = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == sig[0] &&
                tail[i + 1] == sig[1] &&
                tail[i + 2] == sig[2] &&
                tail[i + 3] == sig[3]
            ) {
                return i
            }
        }
        return -1
    }

    private fun findAndroidApkEntryInCd(cdBytes: ByteArray): ZipCdEntry? {
        var pos = 0
        var fallbackSourceApk: ZipCdEntry? = null

        while (pos + 46 <= cdBytes.size) {
            val sig = getUint32LE(cdBytes, pos)
            if (sig != 0x02014b50L) break

            val compressionMethod = getUint16LE(cdBytes, pos + 10)
            val compressedSize = getUint32LE(cdBytes, pos + 20)
            val uncompressedSize = getUint32LE(cdBytes, pos + 24)
            val nameLen = getUint16LE(cdBytes, pos + 28)
            val extraLen = getUint16LE(cdBytes, pos + 30)
            val commentLen = getUint16LE(cdBytes, pos + 32)
            val localHeaderOffset = getUint32LE(cdBytes, pos + 42)

            val fileName = if (pos + 46 + nameLen <= cdBytes.size) {
                String(cdBytes, pos + 46, nameLen, Charsets.UTF_8)
            } else ""

            val entry = ZipCdEntry(
                fileName = fileName,
                compressionMethod = compressionMethod,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localHeaderOffset
            )

            val lower = fileName.lowercase()
            if (lower.endsWith("android_release.apk") || lower == "android_release.apk") {
                return entry
            } else if (lower.endsWith("android_source.apk") || lower.endsWith("android.apk") || lower.endsWith("android_debug.apk")) {
                if (fallbackSourceApk == null) fallbackSourceApk = entry
            }

            pos += 46 + nameLen + extraLen + commentLen
        }

        return fallbackSourceApk
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("Unexpected EOF while reading header")
            offset += read
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var skipped = 0L
        while (skipped < bytesToSkip) {
            val s = input.skip(bytesToSkip - skipped)
            if (s <= 0) {
                if (input.read() == -1) throw IOException("Unexpected EOF while skipping")
                skipped += 1
            } else {
                skipped += s
            }
        }
    }

    private fun getUint16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun getUint32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    companion object {
        private const val TAG = "OfficialTpzRangeDownloader"

        /**
         * Builds candidate official Godot export template `.tpz` URLs for a version.
         */
        fun getCandidateTpzUrls(versionKey: String, engineVersion: String? = null): List<String> {
            val tags = mutableListOf<String>()

            // 1. Exact engineVersion if specified
            if (!engineVersion.isNullOrBlank()) {
                val clean = engineVersion.trim()
                if (clean.contains("-")) {
                    tags.add(clean)
                } else {
                    tags.add("$clean-stable")
                }
            }

            // 2. Exact versionKey-stable
            tags.add("$versionKey-stable")

            // 3. Known patch and minor releases
            when (versionKey) {
                "4.7" -> tags.addAll(listOf("4.7.2-stable", "4.7.1-stable", "4.7.0-stable", "4.7-dev1"))
                "4.6" -> tags.addAll(listOf("4.6.3-stable", "4.6.2-stable", "4.6.1-stable", "4.6.0-stable", "4.6-dev1"))
                "4.5" -> tags.addAll(listOf("4.5.2-stable", "4.5.1-stable", "4.5.0-stable"))
                "4.4" -> tags.addAll(listOf("4.4.1-stable", "4.4.0-stable"))
                "4.3" -> tags.addAll(listOf("4.3.0-stable"))
                "4.2" -> tags.addAll(listOf("4.2.2-stable", "4.2.1-stable", "4.2.0-stable"))
                "4.1" -> tags.addAll(listOf("4.1.4-stable", "4.1.3-stable", "4.1.2-stable", "4.1.1-stable"))
                "4.0" -> tags.addAll(listOf("4.0.4-stable", "4.0.3-stable", "4.0.2-stable", "4.0.1-stable"))
                "3.6" -> tags.addAll(listOf("3.6.2-stable", "3.6.1-stable", "3.6.0-stable"))
                "3.5" -> tags.addAll(listOf("3.5.3-stable", "3.5.2-stable", "3.5.1-stable"))
                "3.4" -> tags.addAll(listOf("3.4.5-stable", "3.4.4-stable", "3.4.3-stable", "3.4.2-stable", "3.4.1-stable"))
                "3.3" -> tags.addAll(listOf("3.3.4-stable", "3.3.3-stable", "3.3.2-stable", "3.3.1-stable"))
                "3.2" -> tags.addAll(listOf("3.2.3-stable", "3.2.2-stable", "3.2.1-stable"))
            }

            return tags.distinct().map { tag ->
                "https://github.com/godotengine/godot/releases/download/$tag/Godot_v${tag}_export_templates.tpz"
            }
        }
    }

    private data class ZipCdEntry(
        val fileName: String,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long
    )

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val readCount = delegate.read(b, off, toRead)
            if (readCount > 0) remaining -= readCount.toLong()
            return readCount
        }

        override fun available(): Int = minOf(delegate.available().toLong(), remaining).toInt()
        override fun close() {
            // Keep delegate stream open for caller
        }
    }
}
