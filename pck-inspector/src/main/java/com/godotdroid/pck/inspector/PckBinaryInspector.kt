package com.godotdroid.pck.inspector

import com.godotdroid.pck.model.PckFileEntry
import com.godotdroid.pck.model.PckHeader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class PckInspectResult(
    val header: PckHeader,
    val files: List<PckFileEntry>,
    val isEncrypted: Boolean
)

data class PckLocationResult(
    val pckStartOffset: Long,
    val isEmbeddedExe: Boolean
)

/**
 * High-performance, zero-copy binary parser for Godot .pck files and embedded Windows PE .exe binaries.
 * Uses FileChannel and ByteOrder.LITTLE_ENDIAN to stream headers and directory tables
 * without loading multi-gigabyte files into RAM.
 */
class PckBinaryInspector {

    /**
     * Inspects a PCK or EXE file by path/File.
     */
    fun inspect(file: File): PckInspectResult {
        if (!file.exists()) {
            throw InvalidPckException("Target file does not exist: ${file.absolutePath}")
        }
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            return inspect(channel, channel.size())
        }
    }

    /**
     * Inspects using an existing FileChannel.
     */
    fun inspect(channel: FileChannel, fileLength: Long = channel.size()): PckInspectResult {
        val location = locatePckStartOffset(channel, fileLength)
        val header = parseHeader(channel, location.pckStartOffset, location.isEmbeddedExe, fileLength)

        if (header.isEncrypted) {
            return PckInspectResult(
                header = header,
                files = emptyList(),
                isEncrypted = true
            )
        }

        val files = parseFileTable(channel, header, fileLength)
        return PckInspectResult(
            header = header,
            files = files,
            isEncrypted = false
        )
    }

    /**
     * Step A: Locates the pck_start_offset in either a standalone .pck or an embedded Windows PE .exe.
     */
    fun locatePckStartOffset(channel: FileChannel, fileLength: Long): PckLocationResult {
        if (fileLength < 4) {
            throw InvalidPckException("File is too small to be a valid Godot package (length: $fileLength bytes)")
        }

        val buf4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Read first 4 bytes at offset 0
        channel.position(0L)
        buf4.clear()
        if (channel.read(buf4) < 4) {
            throw InvalidPckException("Failed to read header magic at offset 0")
        }
        buf4.flip()
        val magicAtZero = buf4.getInt()

        if (magicAtZero == PckConstants.PCK_MAGIC) {
            return PckLocationResult(pckStartOffset = 0L, isEmbeddedExe = false)
        }

        // 2. Check if it is a Windows PE .exe ('MZ' / 0x5A4D at offset 0)
        val shortMagic = (magicAtZero and 0xFFFF).toShort()
        if (shortMagic == PckConstants.DOS_MAGIC) {
            // Method 1: Godot Trailer Footer
            if (fileLength >= 12) {
                // Read last 4 bytes at fileLength - 4
                channel.position(fileLength - 4)
                buf4.clear()
                if (channel.read(buf4) == 4) {
                    buf4.flip()
                    val trailerMagic = buf4.getInt()
                    if (trailerMagic == PckConstants.PCK_MAGIC) {
                        // Read uint64 pckSize at fileLength - 12
                        val buf8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        channel.position(fileLength - 12)
                        if (channel.read(buf8) == 8) {
                            buf8.flip()
                            val pckSize = buf8.getLong()

                            // Check candidate 1: fileLength - 12 - pckSize
                            val candidate1 = fileLength - 12 - pckSize
                            if (candidate1 in 0 until fileLength) {
                                if (verifyMagicAt(channel, candidate1)) {
                                    return PckLocationResult(pckStartOffset = candidate1, isEmbeddedExe = true)
                                }
                            }

                            // Check candidate 2: fileLength - 8 - pckSize
                            val candidate2 = fileLength - 8 - pckSize
                            if (candidate2 in 0 until fileLength) {
                                if (verifyMagicAt(channel, candidate2)) {
                                    return PckLocationResult(pckStartOffset = candidate2, isEmbeddedExe = true)
                                }
                            }
                        }
                    }
                }
            }

            // Method 2: PE .pck Section Header Fallback
            val peOffset = PeHeaderParser.findPckSectionOffset(channel, fileLength)
            if (peOffset != null) {
                return PckLocationResult(pckStartOffset = peOffset, isEmbeddedExe = true)
            }

            throw InvalidPckException(
                "Windows executable detected, but no embedded Godot .pck trailer or .pck section was found."
            )
        }

        throw InvalidPckException(
            "File signature mismatch: Expected Godot PCK magic (0x43504447) or Windows PE ('MZ'), found 0x${Integer.toHexString(magicAtZero)}"
        )
    }

    private fun verifyMagicAt(channel: FileChannel, offset: Long): Boolean {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(offset)
        if (channel.read(buf) == 4) {
            buf.flip()
            return buf.getInt() == PckConstants.PCK_MAGIC
        }
        return false
    }

    /**
     * Step B: Parses the GDPC Header supporting Format v1, v2, v3, and v4.
     */
    fun parseHeader(
        channel: FileChannel,
        pckStartOffset: Long,
        isEmbeddedExe: Boolean,
        fileLength: Long
    ): PckHeader {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(pckStartOffset)
        if (channel.read(buf) < 20) {
            throw InvalidPckException("Unexpected end of file while reading GDPC header at offset $pckStartOffset")
        }
        buf.flip()

        val magic = buf.getInt()
        if (magic != PckConstants.PCK_MAGIC) {
            throw InvalidPckException(
                "Invalid PCK magic at offset $pckStartOffset: expected 0x43504447, got 0x${Integer.toHexString(magic)}"
            )
        }

        val formatVersion = buf.getInt()
        val engineMajor = buf.getInt()
        val engineMinor = buf.getInt()
        val enginePatch = buf.getInt()

        var packFlags = 0
        var fileBase = 0L
        var effectiveFileBase = 0L
        val directoryOffset: Long

        when {
            formatVersion == 1 -> {
                // Godot 3.x
                packFlags = 0
                fileBase = 0L
                effectiveFileBase = if (isEmbeddedExe) pckStartOffset else 0L
                // Skip 64 bytes (16 * uint32 reserved)
                channel.position(channel.position() + 64L)
                directoryOffset = pckStartOffset + 0x54L
            }
            formatVersion == 2 -> {
                // Godot 4.0 – 4.3
                val v2Buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                if (channel.read(v2Buf) < 12) {
                    throw InvalidPckException("Unexpected end of file while reading format v2 header flags")
                }
                v2Buf.flip()
                packFlags = v2Buf.getInt()
                fileBase = v2Buf.getLong()

                effectiveFileBase = if ((packFlags and PckConstants.PACK_REL_FILEBASE) != 0) {
                    pckStartOffset + fileBase
                } else {
                    fileBase
                }

                // Skip 64 bytes (16 * uint32 reserved)
                channel.position(channel.position() + 64L)
                directoryOffset = channel.position()
            }
            formatVersion >= 3 -> {
                // Godot 4.4+ (Format v3 and v4)
                val v3Buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                if (channel.read(v3Buf) < 20) {
                    throw InvalidPckException("Unexpected end of file while reading format v3+ header flags")
                }
                v3Buf.flip()
                packFlags = v3Buf.getInt()
                fileBase = v3Buf.getLong()
                val dirOffset = v3Buf.getLong()

                // In v3+, PACK_REL_FILEBASE is always active
                effectiveFileBase = pckStartOffset + fileBase
                directoryOffset = pckStartOffset + dirOffset
            }
            else -> {
                throw InvalidPckException("Unsupported PCK format version: $formatVersion")
            }
        }

        val isEncrypted = (packFlags and PckConstants.PACK_DIR_ENCRYPTED) != 0

        return PckHeader(
            magic = magic,
            formatVersion = formatVersion,
            engineMajor = engineMajor,
            engineMinor = engineMinor,
            enginePatch = enginePatch,
            packFlags = packFlags,
            fileBase = fileBase,
            effectiveFileBase = effectiveFileBase,
            directoryOffset = directoryOffset,
            isEncrypted = isEncrypted,
            isEmbeddedExe = isEmbeddedExe,
            pckStartOffset = pckStartOffset,
            fileLength = fileLength
        )
    }

    /**
     * Step C: Parses the File Table & builds the list of virtual files.
     */
    fun parseFileTable(
        channel: FileChannel,
        header: PckHeader,
        fileLength: Long
    ): List<PckFileEntry> {
        if (header.isEncrypted) {
            return emptyList()
        }

        channel.position(header.directoryOffset)

        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        if (channel.read(countBuf) < 4) {
            throw InvalidPckException("Failed to read fileCount at directory offset ${header.directoryOffset}")
        }
        countBuf.flip()
        val fileCount = countBuf.getInt()

        if (fileCount < 0) {
            throw InvalidPckException("Invalid negative fileCount: $fileCount")
        }

        val entries = ArrayList<PckFileEntry>(fileCount)

        // Read entries sequentially
        val pathLenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        val entryDataBuf = ByteBuffer.allocate(
            if (header.formatVersion >= 2) 36 else 32 // 8 (offset) + 8 (size) + 16 (md5) [+ 4 (flags)]
        ).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until fileCount) {
            pathLenBuf.clear()
            if (channel.read(pathLenBuf) < 4) {
                throw InvalidPckException("Unexpected EOF reading path length for entry #$i")
            }
            pathLenBuf.flip()
            val pathLength = pathLenBuf.getInt()
            if (pathLength < 0 || pathLength > 65536) {
                throw InvalidPckException("Corrupted path length ($pathLength) for entry #$i")
            }

            // Path string is padded to 4-byte boundary
            val paddedLength = ((pathLength + 3) / 4) * 4
            val pathBytes = ByteBuffer.allocate(paddedLength)
            if (channel.read(pathBytes) < paddedLength) {
                throw InvalidPckException("Unexpected EOF reading path bytes for entry #$i")
            }
            pathBytes.flip()

            val rawStringBytes = ByteArray(pathLength)
            pathBytes.get(rawStringBytes)
            var path = String(rawStringBytes, Charsets.UTF_8).trimEnd('\u0000')
            if (!path.startsWith("res://") && !path.startsWith("user://")) {
                path = "res://" + path.trimStart('/')
            }

            entryDataBuf.clear()
            val expectedDataBytes = if (header.formatVersion >= 2) 36 else 32
            if (channel.read(entryDataBuf) < expectedDataBytes) {
                throw InvalidPckException("Unexpected EOF reading metadata for entry #$i ($path)")
            }
            entryDataBuf.flip()

            val rawOffset = entryDataBuf.getLong()
            val size = entryDataBuf.getLong()
            val md5 = ByteArray(16)
            entryDataBuf.get(md5)

            val entryFlags = if (header.formatVersion >= 2) {
                entryDataBuf.getInt()
            } else {
                0
            }

            // Calculate actual byte offset in file
            val actualOffset = when {
                header.formatVersion >= 2 -> {
                    header.effectiveFileBase + rawOffset
                }
                header.isEmbeddedExe -> {
                    // For embedded Godot 3.x, check if rawOffset is relative or absolute
                    if (rawOffset < header.pckStartOffset &&
                        header.pckStartOffset + rawOffset + size <= fileLength
                    ) {
                        header.pckStartOffset + rawOffset
                    } else {
                        rawOffset
                    }
                }
                else -> {
                    rawOffset
                }
            }

            entries.add(
                PckFileEntry(
                    path = path,
                    fileOffset = actualOffset,
                    size = size,
                    md5 = md5,
                    flags = entryFlags
                )
            )
        }

        return entries
    }

    /**
     * Extracts virtual file content as ByteArray on demand.
     */
    fun extractFileBytes(channel: FileChannel, entry: PckFileEntry): ByteArray {
        if (entry.size > Int.MAX_VALUE) {
            throw IllegalArgumentException("File ${entry.path} is too large to extract into memory (${entry.size} bytes)")
        }
        if (entry.size == 0L) {
            return ByteArray(0)
        }

        val buffer = ByteBuffer.allocate(entry.size.toInt())
        channel.position(entry.fileOffset)
        var totalRead = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read <= 0) break
            totalRead += read
        }

        if (totalRead < entry.size) {
            throw InvalidPckException("Failed to read complete file data for ${entry.path}: expected ${entry.size}, read $totalRead")
        }

        return buffer.array()
    }

    fun extractFileBytes(file: File, entry: PckFileEntry): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            return extractFileBytes(raf.channel, entry)
        }
    }

    /**
     * Extracts standalone .pck portion from an embedded EXE file to targetPckFile,
     * performing necessary header and directory offset rebasing so all virtual files
     * resolve accurately at standalone offset 0.
     */
    fun extractStandalonePck(sourceFile: File, targetPckFile: File, header: PckHeader): Long {
        RandomAccessFile(sourceFile, "r").use { srcRaf ->
            val srcChannel = srcRaf.channel
            RandomAccessFile(targetPckFile, "rw").use { dstRaf ->
                val dstChannel = dstRaf.channel
                dstChannel.truncate(0)

                val startOffset = header.pckStartOffset
                val lengthToCopy = srcChannel.size() - startOffset
                var transferred = 0L

                while (transferred < lengthToCopy) {
                    val count = srcChannel.transferTo(startOffset + transferred, lengthToCopy - transferred, dstChannel)
                    if (count <= 0) break
                    transferred += count
                }

                if (header.isEmbeddedExe && header.pckStartOffset > 0) {
                    // Rebase offsets for standalone format
                    when (header.formatVersion) {
                        1 -> {
                            // Format v1: rebase rawOffset in directory entries
                            val dirOffsetInCarved = header.directoryOffset - header.pckStartOffset
                            dstChannel.position(dirOffsetInCarved)
                            val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            if (dstChannel.read(countBuf) == 4) {
                                countBuf.flip()
                                val fileCount = countBuf.getInt()

                                val pathLenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                val offsetBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

                                for (i in 0 until fileCount) {
                                    pathLenBuf.clear()
                                    if (dstChannel.read(pathLenBuf) < 4) break
                                    pathLenBuf.flip()
                                    val pathLength = pathLenBuf.getInt()
                                    val paddedLength = ((pathLength + 3) / 4) * 4

                                    // Skip path bytes
                                    dstChannel.position(dstChannel.position() + paddedLength)

                                    val offsetPos = dstChannel.position()
                                    offsetBuf.clear()
                                    if (dstChannel.read(offsetBuf) < 8) break
                                    offsetBuf.flip()
                                    val rawOffset = offsetBuf.getLong()

                                    if (rawOffset >= header.pckStartOffset) {
                                        val newOffset = rawOffset - header.pckStartOffset
                                        offsetBuf.clear()
                                        offsetBuf.putLong(newOffset)
                                        offsetBuf.flip()
                                        dstChannel.position(offsetPos)
                                        dstChannel.write(offsetBuf)
                                    }

                                    // Skip size (8) + md5 (16)
                                    dstChannel.position(offsetPos + 8 + 8 + 16)
                                }
                            }
                        }
                        2 -> {
                            // Format v2: ensure PACK_REL_FILEBASE is enabled and fileBase is rebased
                            val newPackFlags = header.packFlags or PckConstants.PACK_REL_FILEBASE
                            val newFileBase = header.effectiveFileBase - header.pckStartOffset

                            val patchBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                            patchBuf.putInt(newPackFlags)
                            patchBuf.putLong(newFileBase)
                            patchBuf.flip()

                            dstChannel.position(0x14L)
                            dstChannel.write(patchBuf)
                        }
                        else -> {
                            // Format v3+: PACK_REL_FILEBASE is always active and fileBase is already relative
                        }
                    }
                }

                return transferred
            }
        }
    }
}
