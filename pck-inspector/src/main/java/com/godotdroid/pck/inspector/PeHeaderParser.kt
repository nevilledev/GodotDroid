package com.godotdroid.pck.inspector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Parser for Windows Portable Executable (PE) headers to locate embedded Godot sections (.pck).
 */
object PeHeaderParser {

    data class PeSection(
        val name: String,
        val virtualSize: Long,
        val virtualAddress: Long,
        val sizeOfRawData: Long,
        val pointerToRawData: Long
    )

    /**
     * Attempts to find a section named ".pck" in a PE executable.
     * Returns the pointer to raw data (file offset) if found, or null otherwise.
     */
    fun findPckSectionOffset(channel: FileChannel, fileLength: Long): Long? {
        if (fileLength < 0x40) return null

        val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)

        // Read DOS header e_lfanew at 0x3C
        channel.position(0x3C)
        buffer.clear()
        buffer.limit(4)
        if (channel.read(buffer) < 4) return null
        buffer.flip()
        val peHeaderOffset = buffer.getInt().toLong() and 0xFFFFFFFFL

        if (peHeaderOffset <= 0 || peHeaderOffset + 24 > fileLength) {
            return null
        }

        // Verify PE signature at e_lfanew
        channel.position(peHeaderOffset)
        buffer.clear()
        buffer.limit(24) // 4 bytes signature + 20 bytes COFF header
        if (channel.read(buffer) < 24) return null
        buffer.flip()

        val peSignature = buffer.getInt()
        if (peSignature != PckConstants.PE_SIGNATURE) {
            return null
        }

        // COFF header
        buffer.getShort() // Machine (2 bytes)
        val numberOfSections = buffer.getShort().toInt() and 0xFFFF
        buffer.getInt() // TimeDateStamp (4 bytes)
        buffer.getInt() // PointerToSymbolTable (4 bytes)
        buffer.getInt() // NumberOfSymbols (4 bytes)
        val sizeOfOptionalHeader = buffer.getShort().toInt() and 0xFFFF

        val sectionTableOffset = peHeaderOffset + 24 + sizeOfOptionalHeader
        if (sectionTableOffset + (numberOfSections.toLong() * 40L) > fileLength) {
            return null
        }

        val sectionBuffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numberOfSections) {
            channel.position(sectionTableOffset + (i.toLong() * 40L))
            sectionBuffer.clear()
            if (channel.read(sectionBuffer) < 40) break
            sectionBuffer.flip()

            val nameBytes = ByteArray(8)
            sectionBuffer.get(nameBytes)
            val name = String(nameBytes, Charsets.US_ASCII).trim('\u0000', ' ')

            val virtualSize = sectionBuffer.getInt().toLong() and 0xFFFFFFFFL
            val virtualAddress = sectionBuffer.getInt().toLong() and 0xFFFFFFFFL
            val sizeOfRawData = sectionBuffer.getInt().toLong() and 0xFFFFFFFFL
            val pointerToRawData = sectionBuffer.getInt().toLong() and 0xFFFFFFFFL

            if (name.equals(".pck", ignoreCase = true)) {
                // Verify magic at pointerToRawData
                if (pointerToRawData + 4 <= fileLength) {
                    val magicBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    channel.position(pointerToRawData)
                    if (channel.read(magicBuf) == 4) {
                        magicBuf.flip()
                        if (magicBuf.getInt() == PckConstants.PCK_MAGIC) {
                            return pointerToRawData
                        }
                    }
                }
            }
        }

        return null
    }
}
