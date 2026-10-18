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
        injectedFiles: Map<String, ByteArray>,
        targetEngineMajor: Int? = null,
        targetEngineMinor: Int? = null
    ): PckPatchResult {
        // 1. Ensure we have a clean standalone PCK
        val cleanPck: File
        val isTempCarved: Boolean
        val initialHeader = inspector.inspect(sourceFile).header

        val needsClamping = targetEngineMajor != null && targetEngineMinor != null &&
            (initialHeader.engineMajor > targetEngineMajor ||
             (initialHeader.engineMajor == targetEngineMajor && initialHeader.engineMinor > targetEngineMinor))

        if (injectedFiles.isEmpty() && !initialHeader.isEmbeddedExe && !needsClamping) {
            sourceFile.copyTo(targetFile, overwrite = true)
            return PckPatchResult(
                totalFilesCount = 0,
                injectedFilesCount = 0,
                outputFileSize = targetFile.length(),
                formatVersion = initialHeader.formatVersion
            )
        }

        if (initialHeader.isEmbeddedExe) {
            cleanPck = File.createTempFile("godotdroid_carved_", ".pck")
            inspector.extractStandalonePck(sourceFile, cleanPck, initialHeader)
            isTempCarved = true
        } else {
            cleanPck = sourceFile
            isTempCarved = false
        }

        try {
            return performPckPatch(cleanPck, targetFile, injectedFiles, targetEngineMajor, targetEngineMinor)
        } finally {
            if (isTempCarved) {
                cleanPck.delete()
            }
        }
    }

    private fun performPckPatch(
        cleanPck: File,
        targetFile: File,
        injectedFiles: Map<String, ByteArray>,
        targetEngineMajor: Int? = null,
        targetEngineMinor: Int? = null
    ): PckPatchResult {
        RandomAccessFile(cleanPck, "r").use { srcRaf ->
            val srcChannel = srcRaf.channel
            val srcLength = srcChannel.size()
            val header = inspector.parseHeader(srcChannel, 0L, false, srcLength)
            val existingEntries = inspector.parseFileTable(srcChannel, header, srcLength)

            // Determine if the pack uses "res://" prefix (standard for Godot PCKs)
            val usesResPrefix = existingEntries.isEmpty() || existingEntries.any { it.path.startsWith("res://") }

            fun normalizePath(p: String): String {
                val clean = p.trim()
                return if (usesResPrefix) {
                    if (clean.startsWith("res://")) clean else "res://" + clean.removePrefix("/")
                } else {
                    clean.removePrefix("res://").removePrefix("/")
                }
            }

            val md = MessageDigest.getInstance("MD5")
            val preparedInjected = injectedFiles.map { (path, bytes) ->
                val normalizedPath = normalizePath(path)
                InjectedEntryMetadata(
                    path = normalizedPath,
                    bytes = bytes,
                    md5 = md.digest(bytes),
                    flags = 0
                )
            }

            val injectedPathSet = preparedInjected.map { normalizePath(it.path) }.toSet()
            // Filter out existing entries that are being replaced
            val retainedEntries = existingEntries.filter { normalizePath(it.path) !in injectedPathSet }

            return when {
                header.formatVersion in 1..2 -> {
                    patchFormatV1V2(srcChannel, header, existingEntries, retainedEntries, preparedInjected, targetFile, targetEngineMajor, targetEngineMinor)
                }
                header.formatVersion >= 3 -> {
                    patchFormatV3V4(srcChannel, header, retainedEntries, preparedInjected, targetFile, targetEngineMajor, targetEngineMinor)
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
        existingEntries: List<PckFileEntry>,
        retainedEntries: List<PckFileEntry>,
        injectedEntries: List<InjectedEntryMetadata>,
        targetFile: File,
        targetEngineMajor: Int? = null,
        targetEngineMinor: Int? = null
    ): PckPatchResult {
        val formatVersion = header.formatVersion
        val headerSize = if (formatVersion == 1) 0x54L else 0x60L

        // Calculate old directory size from existingEntries
        val oldDirByteSize = 4L + existingEntries.sumOf { calculateEntryByteSize(it.path, formatVersion) }
        
        // In format 2, Godot 8-byte aligns payload start (header.effectiveFileBase)
        // In format 1, payload start is the lowest file offset or immediately follows directory table
        val oldPayloadStart = if (formatVersion >= 2 && header.effectiveFileBase > 0) {
            header.effectiveFileBase
        } else {
            existingEntries.minOfOrNull { it.fileOffset } ?: (header.directoryOffset + oldDirByteSize)
        }

        // Calculate new directory size
        val allNewEntriesCount = retainedEntries.size + injectedEntries.size
        val newDirByteSize = 4L + retainedEntries.sumOf { calculateEntryByteSize(it.path, formatVersion) } +
                injectedEntries.sumOf { calculateEntryByteSize(it.path, formatVersion) }

        // In format 2, Godot requires payload to start at an 8-byte aligned fileBase
        val newFileBase = if (formatVersion == 2) {
            alignUp(headerSize + newDirByteSize, 8L)
        } else {
            headerSize + newDirByteSize
        }

        val payloadLength = srcChannel.size() - oldPayloadStart

        // Precompute all directory entries before writing for strictly sequential output
        val finalEntries = mutableListOf<CompiledDirEntry>()

        for (old in retainedEntries) {
            val cleanPath = old.path
            val newRawOffset = if (formatVersion == 1) {
                newFileBase + (old.fileOffset - oldPayloadStart)
            } else {
                old.fileOffset - oldPayloadStart
            }
            finalEntries.add(
                CompiledDirEntry(
                    path = cleanPath,
                    rawOffset = newRawOffset,
                    size = old.size,
                    md5 = old.md5,
                    flags = old.flags
                )
            )
        }

        var curInjectedActual = newFileBase + payloadLength
        for (inj in injectedEntries) {
            val alignedActual = alignUp(curInjectedActual, 8L)
            val cleanPath = inj.path
            val rawOffset = if (formatVersion == 1) {
                alignedActual
            } else {
                alignedActual - newFileBase
            }
            finalEntries.add(
                CompiledDirEntry(
                    path = cleanPath,
                    rawOffset = rawOffset,
                    size = inj.bytes.size.toLong(),
                    md5 = inj.md5,
                    flags = inj.flags
                )
            )
            curInjectedActual = alignedActual + inj.bytes.size
        }

        // Temporary target file
        val tempTarget = File(targetFile.parentFile, "${targetFile.name}.tmp")
        if (tempTarget.exists()) tempTarget.delete()

        RandomAccessFile(tempTarget, "rw").use { dstRaf ->
            val dstChannel = dstRaf.channel
            dstChannel.truncate(0)
            dstChannel.position(0L)

            // 1. Write Header
            srcChannel.position(0L)
            val headerBuf = ByteBuffer.allocate(headerSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            srcChannel.read(headerBuf)
            headerBuf.flip()

            // Version clamping if target core is older than PCK
            if (targetEngineMajor != null && targetEngineMinor != null) {
                if (header.engineMajor > targetEngineMajor ||
                    (header.engineMajor == targetEngineMajor && header.engineMinor > targetEngineMinor)
                ) {
                    headerBuf.putInt(0x08, targetEngineMajor)
                    headerBuf.putInt(0x0C, targetEngineMinor)
                    headerBuf.putInt(0x10, 0)
                }
            }

            if (formatVersion == 2) {
                // Ensure PACK_REL_FILEBASE is set
                val packFlags = header.packFlags or PckConstants.PACK_REL_FILEBASE
                headerBuf.putInt(0x14, packFlags)
                headerBuf.putLong(0x18, newFileBase)
            }
            dstChannel.write(headerBuf)

            // 2. Write Directory Table immediately after header
            writeDirectoryTable(dstChannel, formatVersion, finalEntries)

            // 3. Pad up to newFileBase
            val writtenAfterDir = dstChannel.position()
            if (writtenAfterDir < newFileBase) {
                val padSize = (newFileBase - writtenAfterDir).toInt()
                val pad = ByteBuffer.allocate(padSize)
                dstChannel.write(pad)
            }

            // 4. Stream payload from srcChannel at oldPayloadStart sequentially
            if (payloadLength > 0) {
                srcChannel.position(oldPayloadStart)
                val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                var remaining = payloadLength
                while (remaining > 0) {
                    buffer.clear()
                    val toRead = minOf(buffer.capacity().toLong(), remaining).toInt()
                    buffer.limit(toRead)
                    val readCount = srcChannel.read(buffer)
                    if (readCount <= 0) break
                    buffer.flip()
                    dstChannel.write(buffer)
                    remaining -= readCount
                }
            }

            // 5. Append injected files data (aligned to 8 bytes)
            for (inj in injectedEntries) {
                val currentPos = dstChannel.position()
                val alignedPos = alignUp(currentPos, 8L)
                if (alignedPos > currentPos) {
                    val padSize = (alignedPos - currentPos).toInt()
                    val pad = ByteBuffer.allocate(padSize)
                    dstChannel.write(pad)
                }
                dstChannel.write(ByteBuffer.wrap(inj.bytes))
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempTarget.renameTo(targetFile)) {
            tempTarget.copyTo(targetFile, overwrite = true)
            tempTarget.delete()
        }

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
        targetFile: File,
        targetEngineMajor: Int? = null,
        targetEngineMinor: Int? = null
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
            if (payloadRegionLength > 0) {
                srcChannel.position(0L)
                val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                var remaining = payloadRegionLength
                while (remaining > 0) {
                    buffer.clear()
                    val toRead = minOf(buffer.capacity().toLong(), remaining).toInt()
                    buffer.limit(toRead)
                    val readCount = srcChannel.read(buffer)
                    if (readCount <= 0) break
                    buffer.flip()
                    dstChannel.write(buffer)
                    remaining -= readCount
                }
            }

            // Version clamping in header at offset 0x08 if target core is older than PCK
            if (targetEngineMajor != null && targetEngineMinor != null) {
                if (header.engineMajor > targetEngineMajor ||
                    (header.engineMajor == targetEngineMajor && header.engineMinor > targetEngineMinor)
                ) {
                    val verBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    verBuf.putInt(targetEngineMajor)
                    verBuf.putInt(targetEngineMinor)
                    verBuf.putInt(0)
                    verBuf.flip()
                    dstChannel.position(0x08L)
                    dstChannel.write(verBuf)
                }
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
        if (!tempTarget.renameTo(targetFile)) {
            tempTarget.copyTo(targetFile, overwrite = true)
            tempTarget.delete()
        }

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
            val cleanPath = entry.path
            val pathUtf8 = cleanPath.toByteArray(Charsets.UTF_8)
            val pathLen = pathUtf8.size

            if (formatVersion >= 2) {
                val paddedLen = ((pathLen + 3) / 4) * 4
                val entryHeaderBuf = ByteBuffer.allocate(4 + paddedLen).order(ByteOrder.LITTLE_ENDIAN)
                entryHeaderBuf.putInt(paddedLen)
                entryHeaderBuf.put(pathUtf8)
                for (p in 0 until (paddedLen - pathLen)) {
                    entryHeaderBuf.put(0)
                }
                entryHeaderBuf.flip()
                channel.write(entryHeaderBuf)
            } else {
                val entryHeaderBuf = ByteBuffer.allocate(4 + pathLen).order(ByteOrder.LITTLE_ENDIAN)
                entryHeaderBuf.putInt(pathLen)
                entryHeaderBuf.put(pathUtf8)
                entryHeaderBuf.flip()
                channel.write(entryHeaderBuf)
            }

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
            val readLen = if (formatVersion >= 2) (((pathLen + 3) / 4) * 4).toLong() else pathLen.toLong()

            channel.position(channel.position() + readLen + metaSize)
            totalSize += 4L + readLen + metaSize
        }
        return totalSize
    }

    private fun calculateEntryByteSize(path: String, formatVersion: Int): Long {
        val pathLen = path.toByteArray(Charsets.UTF_8).size
        val readLen = if (formatVersion >= 2) ((pathLen + 3) / 4) * 4 else pathLen
        val metaSize = if (formatVersion >= 2) 36 else 32
        return 4L + readLen + metaSize
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
