package com.godotdroid.exporter.manifest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary Android XML (AXML) Patcher.
 * Parses Android's compiled binary AndroidManifest.xml (RES_XML_TYPE = 0x00080003),
 * rebuilds the ResStringPool (0x0001) with replaced package names and app titles,
 * and recalculates 4-byte aligned chunk and file sizes.
 */
class AxmlManifestPatcher {

    companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val UTF8_FLAG = 0x00000100
    }

    data class StringPool(
        val headerSize: Int,
        val chunkSize: Int,
        val stringCount: Int,
        val styleCount: Int,
        val flags: Int,
        val stringsStart: Int,
        val stylesStart: Int,
        val strings: List<String>,
        val isUtf8: Boolean,
        val rawStyleData: ByteArray
    )

    /**
     * Patches binary AndroidManifest.xml bytes with the specified package name and app title.
     */
    fun patchManifest(
        manifestBytes: ByteArray,
        newPackageName: String,
        newAppTitle: String
    ): ByteArray {
        val buf = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Verify AXML File Header (8 bytes)
        val fileType = buf.getShort().toInt() and 0xFFFF
        val fileHeaderSize = buf.getShort().toInt() and 0xFFFF
        val totalFileSize = buf.getInt()

        if (fileType != RES_XML_TYPE || fileHeaderSize != 8) {
            throw IllegalArgumentException(
                "Not a valid Android binary XML: expected type 0x0003, got 0x${Integer.toHexString(fileType)}"
            )
        }

        // 2. Read ResStringPool Chunk (starts at offset 8)
        val stringPool = parseStringPool(buf, 8)

        // 3. Perform exact replacements on decoded strings
        val patchedStrings = stringPool.strings.map { str ->
            when (str) {
                "com.godotdroid" -> newPackageName
                "com.godotdroid.fileprovider" -> "$newPackageName.fileprovider"
                "GodotDroid" -> newAppTitle
                else -> str
            }
        }

        // 4. Rebuild the ResStringPool chunk
        val rebuiltPoolChunk = rebuildStringPool(stringPool, patchedStrings)

        // 5. Extract remaining chunks (ResourceMap, Namespace, Element chunks)
        val remainingStart = 8 + stringPool.chunkSize
        val remainingBytes = if (remainingStart < manifestBytes.size) {
            manifestBytes.copyOfRange(remainingStart, manifestBytes.size)
        } else {
            ByteArray(0)
        }

        // 6. Assemble the new AXML file
        val newTotalSize = 8 + rebuiltPoolChunk.size + remainingBytes.size
        val outBuf = ByteBuffer.allocate(newTotalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Write AXML Header
        outBuf.putShort(RES_XML_TYPE.toShort())
        outBuf.putShort(8.toShort())
        outBuf.putInt(newTotalSize)

        // Write Rebuilt String Pool
        outBuf.put(rebuiltPoolChunk)

        // Write Remaining Chunks
        outBuf.put(remainingBytes)

        return outBuf.array()
    }

    private fun parseStringPool(buf: ByteBuffer, chunkOffset: Int): StringPool {
        buf.position(chunkOffset)
        val chunkType = buf.getShort().toInt() and 0xFFFF
        val headerSize = buf.getShort().toInt() and 0xFFFF
        val chunkSize = buf.getInt()

        if (chunkType != RES_STRING_POOL_TYPE) {
            throw IllegalArgumentException(
                "Expected ResStringPool chunk (0x0001) at offset $chunkOffset, got 0x${Integer.toHexString(chunkType)}"
            )
        }

        val stringCount = buf.getInt()
        val styleCount = buf.getInt()
        val flags = buf.getInt()
        val stringsStart = buf.getInt()
        val stylesStart = buf.getInt()

        val isUtf8 = (flags and UTF8_FLAG) != 0

        // Read string offset table (stringCount * 4 bytes)
        val stringOffsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            stringOffsets[i] = buf.getInt()
        }

        // Read style offset table (styleCount * 4 bytes)
        for (i in 0 until styleCount) {
            buf.getInt() // skip style offsets for parsing
        }

        // Absolute offset where string data begins
        val stringDataBaseOffset = chunkOffset + stringsStart

        // Decode strings
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strOffset = stringDataBaseOffset + stringOffsets[i]
            buf.position(strOffset)

            if (isUtf8) {
                // UTF-8 format
                readUtf8Length(buf) // char count
                val byteCount = readUtf8Length(buf)
                val strBytes = ByteArray(byteCount)
                buf.get(strBytes)
                strings.add(String(strBytes, Charsets.UTF_8))
            } else {
                // UTF-16LE format
                val charCount = readUtf16Length(buf)
                val byteCount = charCount * 2
                val strBytes = ByteArray(byteCount)
                buf.get(strBytes)
                strings.add(String(strBytes, Charsets.UTF_16LE))
            }
        }

        // Read raw style data if styles exist
        val rawStyleData = if (styleCount > 0 && stylesStart > 0) {
            val styleBaseOffset = chunkOffset + stylesStart
            val styleDataSize = chunkSize - stylesStart
            if (styleDataSize > 0) {
                buf.position(styleBaseOffset)
                val bytes = ByteArray(styleDataSize)
                buf.get(bytes)
                bytes
            } else ByteArray(0)
        } else ByteArray(0)

        return StringPool(
            headerSize = headerSize,
            chunkSize = chunkSize,
            stringCount = stringCount,
            styleCount = styleCount,
            flags = flags,
            stringsStart = stringsStart,
            stylesStart = stylesStart,
            strings = strings,
            isUtf8 = isUtf8,
            rawStyleData = rawStyleData
        )
    }

    private fun rebuildStringPool(originalPool: StringPool, newStrings: List<String>): ByteArray {
        val stringCount = newStrings.size
        val styleCount = originalPool.styleCount
        val isUtf8 = originalPool.isUtf8

        // Calculate header size: 28 bytes
        val headerSize = 28
        val offsetTableSize = stringCount * 4 + styleCount * 4
        val stringsStart = headerSize + offsetTableSize

        val stringOffsets = IntArray(stringCount)
        val stringDataStream = ByteArrayOutputStream()

        for (i in 0 until stringCount) {
            stringOffsets[i] = stringDataStream.size()
            val str = newStrings[i]

            if (isUtf8) {
                val utf8Bytes = str.toByteArray(Charsets.UTF_8)
                val charCount = str.length
                val byteCount = utf8Bytes.size

                writeUtf8Length(stringDataStream, charCount)
                writeUtf8Length(stringDataStream, byteCount)
                stringDataStream.write(utf8Bytes)
                stringDataStream.write(0) // 1-byte null terminator
            } else {
                val utf16Bytes = str.toByteArray(Charsets.UTF_16LE)
                val charCount = str.length

                writeUtf16Length(stringDataStream, charCount)
                stringDataStream.write(utf16Bytes)
                stringDataStream.write(0) // 2-byte null terminator
                stringDataStream.write(0)
            }
        }

        val stringDataBytes = stringDataStream.toByteArray()

        // Styles start offset
        val stylesStart = if (styleCount > 0 && originalPool.rawStyleData.isNotEmpty()) {
            stringsStart + stringDataBytes.size
        } else 0

        var rawChunkSize = stringsStart + stringDataBytes.size + originalPool.rawStyleData.size

        // 4-byte padding alignment
        val paddingNeeded = (4 - (rawChunkSize % 4)) % 4
        val finalChunkSize = rawChunkSize + paddingNeeded

        val chunkBuf = ByteBuffer.allocate(finalChunkSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. ResStringPool Header (28 bytes)
        chunkBuf.putShort(RES_STRING_POOL_TYPE.toShort())
        chunkBuf.putShort(headerSize.toShort())
        chunkBuf.putInt(finalChunkSize)
        chunkBuf.putInt(stringCount)
        chunkBuf.putInt(styleCount)
        chunkBuf.putInt(originalPool.flags)
        chunkBuf.putInt(stringsStart)
        chunkBuf.putInt(stylesStart)

        // 2. String Offsets Table
        for (offset in stringOffsets) {
            chunkBuf.putInt(offset)
        }

        // 3. Style Offsets Table (if any)
        if (styleCount > 0) {
            for (i in 0 until styleCount) {
                chunkBuf.putInt(0)
            }
        }

        // 4. String Data
        chunkBuf.put(stringDataBytes)

        // 5. Style Data
        if (originalPool.rawStyleData.isNotEmpty()) {
            chunkBuf.put(originalPool.rawStyleData)
        }

        // 6. Padding
        for (p in 0 until paddingNeeded) {
            chunkBuf.put(0.toByte())
        }

        return chunkBuf.array()
    }

    private fun readUtf8Length(buf: ByteBuffer): Int {
        val b1 = buf.get().toInt() and 0xFF
        return if ((b1 and 0x80) != 0) {
            val b2 = buf.get().toInt() and 0xFF
            ((b1 and 0x7F) shl 8) or b2
        } else {
            b1
        }
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun readUtf16Length(buf: ByteBuffer): Int {
        val s1 = buf.getShort().toInt() and 0xFFFF
        return if ((s1 and 0x8000) != 0) {
            val s2 = buf.getShort().toInt() and 0xFFFF
            ((s1 and 0x7FFF) shl 16) or s2
        } else {
            s1
        }
    }

    private fun writeUtf16Length(out: ByteArrayOutputStream, len: Int) {
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
