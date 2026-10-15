package com.godotdroid.pck.patcher

import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.PckFileEntry
import com.godotdroid.pck.model.PckHeader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

data class PckPatchResult(
    val totalFilesCount: Int,
    val injectedFilesCount: Int,
    val outputFileSize: Long,
    val formatVersion: Int
)

data class InjectedEntryMetadata(
    val path: String,
    val bytes: ByteArray,
    val md5: ByteArray,
    val flags: Int = 0
)

/**
 * Injects virtual files directly into the binary directory table and data payloads
 * of Godot .pck packages (Format v1, v2, v3, and v4).
 */
class PckVirtualPatcher(
    private val inspector: PckBinaryInspector = PckBinaryInspector()
) {

    /**
     * Injects the provided virtual files (e.g. res://override.cfg, res://godotdroid_steam_stub.gd)
     * into a standalone .pck file. If sourceFile is an embedded .exe, it is first carved.
     */
    fun patchPck(
        sourceFile: File,
        targetFile: File,
        injectedFiles: Map<String, ByteArray>
    ): PckPatchResult {
        if (injectedFiles.isEmpty()) {
            sourceFile.copyTo(targetFile, overwrite = true)
            return PckPatchResult(
                totalFilesCount = 0,
                injectedFilesCount = 0,
                outputFileSize = targetFile.length(),
                formatVersion = 0
            )
        }

        // 1. Ensure we have a clean standalone PCK
        val cleanPck: File
        val isTempCarved: Boolean
        val initialHeader = inspector.inspect(sourceFile).header

        if (initialHeader.isEmbeddedExe) {
            cleanPck = File.createTempFile("godotdroid_carved_", ".pck")
            inspector.extractStandalonePck(sourceFile, cleanPck, initialHeader)
            isTempCarved = true
        } else {
            cleanPck = sourceFile
            isTempCarved = false
        }

        try {
            return performPckPatch(cleanPck, targetFile, injectedFiles)
        } finally {
            if (isTempCarved) {
                cleanPck.delete()
            }
        }
    }

    private fun performPckPatch(
        cleanPck: File,
        targetFile: File,
        injectedFiles: Map<String, ByteArray>
    ): PckPatchResult {
        RandomAccessFile(cleanPck, "r").use { srcRaf ->
            val srcChannel = srcRaf.channel
            val srcLength = srcChannel.size()
            val header = inspector.parseHeader(srcChannel, 0L, false, srcLength)
            val existingEntries = inspector.parseFileTable(srcChannel, header, srcLength)

            // Normalize injected paths: ensure "res://" prefix
            val md = MessageDigest.getInstance("MD5")
            val preparedInjected = injectedFiles.map { (path, bytes) ->
                val normalizedPath = if (path.startsWith("res://") || path.startsWith("user://")) {
                    path
                } else {
                    "res://" + path.trimStart('/')
                }
                InjectedEntryMetadata(
                    path = normalizedPath,
                    bytes = bytes,
                    md5 = md.digest(bytes),
                    flags = 0
                )
            }

            val injectedPathSet = preparedInjected.map { it.path }.toSet()
            // Filter out existing entries that are being replaced
            val retainedEntries = existingEntries.filter { it.path !in injectedPathSet }

            return when {
                header.formatVersion in 1..2 -> {
                    patchFormatV1V2(srcChannel, header, retainedEntries, preparedInjected, targetFile)
                }
                header.formatVersion >= 3 -> {
                    patchFormatV3V4(srcChannel, header, retainedEntries, preparedInjected, targetFile)
                }
                else -> {
                    throw IllegalArgumentException("Unsupported PCK format version: ${header.formatVersion}")
                }
            }
        }
    }

    /**
     * Patches Format v1 (Godot 3.x) and Format v2 (Godot 4.0 - 4.3).
     * Directory table is located immediately after the header (offset 0x54 for v1, 0x60 for v2).
     */
    private fun patchFormatV1V2(
        srcChannel: FileChannel,
        header: PckHeader,
        retainedEntries: List<PckFileEntry>,
        injectedEntries: List<InjectedEntryMetadata>,
        targetFile: File
    ): PckPatchResult {
        val formatVersion = header.formatVersion
        val headerSize = if (formatVersion == 1) 0x54L else 0x60L

        // Calculate old directory size
        val oldDirByteSize = calculateDirByteSize(srcChannel, header.directoryOffset, formatVersion)
        val oldPayloadStart = header.directoryOffset + oldDirByteSize

        // Calculate new directory size
        val allNewEntriesCount = retainedEntries.size + injectedEntries.size
        val newDirByteSize = 4L + retainedEntries.sumOf { calculateEntryByteSize(it.path, formatVersion) } +
                injectedEntries.sumOf { calculateEntryByteSize(it.path, formatVersion) }

        val deltaDirBytes = newDirByteSize - oldDirByteSize

        // Temporary target file
        val tempTarget = File(targetFile.parentFile, "${targetFile.name}.tmp")
        if (tempTarget.exists()) tempTarget.delete()

        RandomAccessFile(tempTarget, "rw").use { dstRaf ->
            val dstChannel = dstRaf.channel
            dstChannel.truncate(0)

            // 1. Write Header
            srcChannel.position(0L)
            val headerBuf = ByteBuffer.allocate(headerSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            srcChannel.read(headerBuf)
            headerBuf.flip()

            var newFileBase = header.fileBase
            if (formatVersion == 2) {
                // Ensure PACK_REL_FILEBASE is set
                val packFlags = header.packFlags or PckConstants.PACK_REL_FILEBASE
                headerBuf.putInt(0x14, packFlags)
                // Shift fileBase by deltaDirBytes
                newFileBase = header.fileBase + deltaDirBytes
                headerBuf.putLong(0x18, newFileBase)
            }
            dstChannel.write(headerBuf)

            // 2. Reserve space for directory table (we will write it after computing payload offsets)
            dstChannel.position(headerSize + newDirByteSize)

            // 3. Copy existing payload bytes (from oldPayloadStart to end of file)
            val payloadLength = srcChannel.size() - oldPayloadStart
            if (payloadLength > 0) {
                var copied = 0L
                while (copied < payloadLength) {
                    val count = srcChannel.transferTo(oldPayloadStart + copied, payloadLength - copied, dstChannel)
                    if (count <= 0) break
                    copied += count
                }
            }

            // 4. Append injected files data (aligned to 8 bytes)
            val injectedOffsetMap = mutableMapOf<String, Long>()
            val padBuf = ByteBuffer.allocate(8)

            for (inj in injectedEntries) {
                val currentPos = dstChannel.position()
                val alignedPos = alignUp(currentPos, 8L)
                if (alignedPos > currentPos) {
                    val padSize = (alignedPos - currentPos).toInt()
                    padBuf.clear()
                    padBuf.limit(padSize)
                    for (p in 0 until padSize) padBuf.put(0)
                    padBuf.flip()
                    dstChannel.write(padBuf)
                }
                injectedOffsetMap[inj.path] = dstChannel.position()
                dstChannel.write(ByteBuffer.wrap(inj.bytes))
            }

            // 5. Construct updated directory table entries
            val finalEntries = mutableListOf<CompiledDirEntry>()

            // Retained entries: in v2, with fileBase shifted by deltaDirBytes, rawOffsets remain valid!
            // In v1, there is no fileBase, so each entry's rawOffset is shifted by deltaDirBytes!
            for (old in retainedEntries) {
                val newRawOffset = if (formatVersion == 1) {
                    old.fileOffset + deltaDirBytes
                } else {
                    // For v2: actualOffset was (header.effectiveFileBase + old.rawOffset)
                    // In new file, payload shifted by deltaDirBytes, and newFileBase shifted by deltaDirBytes
                    // Thus rawOffset = (newActualOffset - newFileBase) = (oldActualOffset + delta - (oldBase + delta)) = oldRawOffset!
                    old.fileOffset - header.effectiveFileBase
                }
                finalEntries.add(
                    CompiledDirEntry(
                        path = old.path,
                        rawOffset = newRawOffset,
                        size = old.size,
                        md5 = old.md5,
                        flags = old.flags
                    )
                )
            }

            // Injected entries:
            for (inj in injectedEntries) {
                val actualOffset = injectedOffsetMap[inj.path]!!
                val rawOffset = if (formatVersion == 1) {
                    actualOffset
                } else {
                    actualOffset - newFileBase
                }
                finalEntries.add(
                    CompiledDirEntry(
                        path = inj.path,
                        rawOffset = rawOffset,
                        size = inj.bytes.size.toLong(),
                        md5 = inj.md5,
                        flags = inj.flags
                    )
                )
            }

            // 6. Write directory table at headerSize
            dstChannel.position(headerSize)
            writeDirectoryTable(dstChannel, formatVersion, finalEntries)
        }

        if (targetFile.exists()) targetFile.delete()
        tempTarget.renameTo(targetFile)

        return PckPatchResult(
            totalFilesCount = retainedEntries.size + injectedEntries.size,
            injectedFilesCount = injectedEntries.size,
            outputFileSize = targetFile.length(),
            formatVersion = formatVersion
        )
    }

    /**
     * Patches Format v3 and v4 (Godot 4.4+).
     * Payload data comes first; directory table is located at the end of the file (dirOffset).
     */
    private fun patchFormatV3V4(
        srcChannel: FileChannel,
        header: PckHeader,
        retainedEntries: List<PckFileEntry>,
        injectedEntries: List<InjectedEntryMetadata>,
        targetFile: File
    ): PckPatchResult {
        val formatVersion = header.formatVersion

        val tempTarget = File(targetFile.parentFile, "${targetFile.name}.tmp")
        if (tempTarget.exists()) tempTarget.delete()

        RandomAccessFile(tempTarget, "rw").use { dstRaf ->
            val dstChannel = dstRaf.channel
            dstChannel.truncate(0)

            // In v3+, directory is at header.directoryOffset.
            // Payload region extends from byte 0 up to header.directoryOffset.
            val payloadRegionLength = header.directoryOffset
            var copied = 0L
            while (copied < payloadRegionLength) {
                val count = srcChannel.transferTo(copied, payloadRegionLength - copied, dstChannel)
                if (count <= 0) break
                copied += count
            }

            // Append injected files data at the end of existing payload (aligned to 8 bytes)
            val injectedOffsetMap = mutableMapOf<String, Long>()
            val padBuf = ByteBuffer.allocate(8)

            for (inj in injectedEntries) {
                val currentPos = dstChannel.position()
                val alignedPos = alignUp(currentPos, 8L)
                if (alignedPos > currentPos) {
                    val padSize = (alignedPos - currentPos).toInt()
                    padBuf.clear()
                    padBuf.limit(padSize)
                    for (p in 0 until padSize) padBuf.put(0)
                    padBuf.flip()
                    dstChannel.write(padBuf)
                }
                injectedOffsetMap[inj.path] = dstChannel.position()
                dstChannel.write(ByteBuffer.wrap(inj.bytes))
            }

            // New directory table offset is current position
            val newDirOffset = dstChannel.position()

            // Construct entries
            val finalEntries = mutableListOf<CompiledDirEntry>()
            for (old in retainedEntries) {
                // In v3+, effectiveFileBase == fileBase (when pckStartOffset == 0)
                val rawOffset = old.fileOffset - header.effectiveFileBase
                finalEntries.add(
                    CompiledDirEntry(
                        path = old.path,
                        rawOffset = rawOffset,
                        size = old.size,
                        md5 = old.md5,
                        flags = old.flags
                    )
                )
            }

            for (inj in injectedEntries) {
                val actualOffset = injectedOffsetMap[inj.path]!!
                val rawOffset = actualOffset - header.effectiveFileBase
                finalEntries.add(
                    CompiledDirEntry(
                        path = inj.path,
                        rawOffset = rawOffset,
                        size = inj.bytes.size.toLong(),
                        md5 = inj.md5,
                        flags = inj.flags
                    )
                )
            }

            // Write directory table at end of file
            writeDirectoryTable(dstChannel, formatVersion, finalEntries)

            // Update dirOffset in Header at offset 0x20 (uint64)
            val dirOffsetBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            dirOffsetBuf.putLong(newDirOffset)
            dirOffsetBuf.flip()

            dstChannel.position(0x20L)
            dstChannel.write(dirOffsetBuf)
        }

        if (targetFile.exists()) targetFile.delete()
        tempTarget.renameTo(targetFile)

        return PckPatchResult(
            totalFilesCount = retainedEntries.size + injectedEntries.size,
            injectedFilesCount = injectedEntries.size,
            outputFileSize = targetFile.length(),
            formatVersion = formatVersion
        )
    }

    private fun writeDirectoryTable(
        channel: FileChannel,
        formatVersion: Int,
        entries: List<CompiledDirEntry>
    ) {
        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        countBuf.putInt(entries.size)
        countBuf.flip()
        channel.write(countBuf)

        for (entry in entries) {
            val pathUtf8 = entry.path.toByteArray(Charsets.UTF_8)
            val pathLen = pathUtf8.size
            val paddedLen = ((pathLen + 3) / 4) * 4

            val entryHeaderBuf = ByteBuffer.allocate(4 + paddedLen).order(ByteOrder.LITTLE_ENDIAN)
            entryHeaderBuf.putInt(pathLen)
            entryHeaderBuf.put(pathUtf8)
            // Zero-padding
            for (p in 0 until (paddedLen - pathLen)) {
                entryHeaderBuf.put(0)
            }
            entryHeaderBuf.flip()
            channel.write(entryHeaderBuf)

            val metaSize = if (formatVersion >= 2) 36 else 32
            val metaBuf = ByteBuffer.allocate(metaSize).order(ByteOrder.LITTLE_ENDIAN)
            metaBuf.putLong(entry.rawOffset)
            metaBuf.putLong(entry.size)
            metaBuf.put(entry.md5)
            if (formatVersion >= 2) {
                metaBuf.putInt(entry.flags)
            }
            metaBuf.flip()
            channel.write(metaBuf)
        }
    }

    private fun calculateDirByteSize(channel: FileChannel, dirOffset: Long, formatVersion: Int): Long {
        channel.position(dirOffset)
        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        channel.read(countBuf)
        countBuf.flip()
        val count = countBuf.getInt()

        var totalSize = 4L
        val pathLenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        val metaSize = if (formatVersion >= 2) 36L else 32L

        for (i in 0 until count) {
            pathLenBuf.clear()
            channel.read(pathLenBuf)
            pathLenBuf.flip()
            val pathLen = pathLenBuf.getInt()
            val paddedLen = (((pathLen + 3) / 4) * 4).toLong()

            channel.position(channel.position() + paddedLen + metaSize)
            totalSize += 4L + paddedLen + metaSize
        }
        return totalSize
    }

    private fun calculateEntryByteSize(path: String, formatVersion: Int): Long {
        val pathLen = path.toByteArray(Charsets.UTF_8).size
        val paddedLen = ((pathLen + 3) / 4) * 4
        val metaSize = if (formatVersion >= 2) 36 else 32
        return 4L + paddedLen + metaSize
    }

    private fun alignUp(value: Long, alignment: Long): Long {
        val rem = value % alignment
        return if (rem == 0L) value else value + (alignment - rem)
    }

    private data class CompiledDirEntry(
        val path: String,
        val rawOffset: Long,
        val size: Long,
        val md5: ByteArray,
        val flags: Int
    )
}
