package com.godotdroid.pck.inspector

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln

/**
 * Scans Windows PE .exe binaries (.rdata and .data sections) for candidate 32-byte (256-bit)
 * AES encryption keys (script_encryption_key / godot_pack_key).
 */
object PeEncryptionKeyScanner {

    private const val KEY_SIZE = 32
    private const val MIN_DISTINCT_BYTES = 20
    private const val MAX_ZERO_BYTES = 3
    private const val MIN_ENTROPY = 4.2
    private const val MAX_ENTROPY = 5.0
    private const val MAX_CANDIDATES = 5

    /**
     * Scans a PE file on disk for candidate AES keys.
     */
    fun scanForCandidateKeys(file: File): List<String> {
        if (!file.exists() || file.length() < 0x200) return emptyList()
        return RandomAccessFile(file, "r").use { raf ->
            scanForCandidateKeys(raf.channel, raf.length())
        }
    }

    /**
     * Scans the open FileChannel of a PE binary.
     */
    fun scanForCandidateKeys(channel: FileChannel, fileLength: Long): List<String> {
        val sections = PeHeaderParser.parseSections(channel, fileLength)
        val targetSections = sections.filter {
            it.name.equals(".rdata", ignoreCase = true) ||
            it.name.equals(".data", ignoreCase = true)
        }

        if (targetSections.isEmpty()) return emptyList()

        val candidates = mutableListOf<KeyCandidate>()

        for (section in targetSections) {
            val rawOffset = section.pointerToRawData
            val rawSize = section.sizeOfRawData
            if (rawOffset <= 0 || rawSize < KEY_SIZE + 8 || rawOffset + rawSize > fileLength) {
                continue
            }

            // Read section bytes safely (capped at 32MB per section)
            val readSize = rawSize.coerceAtMost(32 * 1024 * 1024L).toInt()
            val buffer = ByteBuffer.allocate(readSize).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(rawOffset)
            var bytesRead = 0
            while (bytesRead < readSize) {
                val r = channel.read(buffer)
                if (r <= 0) break
                bytesRead += r
            }
            val data = buffer.array()

            // Scan 4-byte aligned windows of 32 bytes
            for (offset in 0..(bytesRead - KEY_SIZE) step 4) {
                // Check prefix zero padding (at least 4 zeros before offset, or section start)
                val hasZeroPrefix = if (offset >= 4) {
                    data[offset - 4] == 0.toByte() &&
                    data[offset - 3] == 0.toByte() &&
                    data[offset - 2] == 0.toByte() &&
                    data[offset - 1] == 0.toByte()
                } else {
                    offset == 0
                }

                if (!hasZeroPrefix) continue

                // Check suffix zero padding (at least 4 zeros after key, or section end)
                val hasZeroSuffix = if (offset + KEY_SIZE + 4 <= bytesRead) {
                    data[offset + KEY_SIZE] == 0.toByte() &&
                    data[offset + KEY_SIZE + 1] == 0.toByte() &&
                    data[offset + KEY_SIZE + 2] == 0.toByte() &&
                    data[offset + KEY_SIZE + 3] == 0.toByte()
                } else {
                    offset + KEY_SIZE == bytesRead
                }

                if (!hasZeroSuffix) continue

                val candidateBytes = data.copyOfRange(offset, offset + KEY_SIZE)

                // 1. Check zero count
                val zeroCount = candidateBytes.count { it == 0.toByte() }
                if (zeroCount > MAX_ZERO_BYTES) continue

                // 2. Check distinct byte count
                val distinctCount = candidateBytes.distinct().size
                if (distinctCount < MIN_DISTINCT_BYTES) continue

                // 3. Reject plain ASCII text
                val printableAsciiCount = candidateBytes.count { it in 0x20..0x7E }
                if (printableAsciiCount > 24) continue

                // 4. Calculate Shannon Entropy
                val entropy = calculateShannonEntropy(candidateBytes)
                if (entropy in MIN_ENTROPY..MAX_ENTROPY) {
                    val hex = candidateBytes.joinToString("") { "%02x".format(it) }
                    candidates.add(KeyCandidate(hex, entropy))
                }
            }
        }

        // Deduplicate and rank candidates by entropy proximity to optimal ~4.75 bits/byte
        return candidates
            .distinctBy { it.hex }
            .sortedBy { kotlin.math.abs(4.75 - it.entropy) }
            .take(MAX_CANDIDATES)
            .map { it.hex }
    }

    /**
     * Calculates Shannon entropy in bits/byte: H = -sum(p_i * log2(p_i))
     */
    fun calculateShannonEntropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in bytes) {
            counts[b.toInt() and 0xFF]++
        }

        val total = bytes.size.toDouble()
        val log2 = ln(2.0)
        var entropy = 0.0

        for (count in counts) {
            if (count > 0) {
                val p = count / total
                entropy -= p * (ln(p) / log2)
            }
        }

        return entropy
    }

    private data class KeyCandidate(val hex: String, val entropy: Double)
}
