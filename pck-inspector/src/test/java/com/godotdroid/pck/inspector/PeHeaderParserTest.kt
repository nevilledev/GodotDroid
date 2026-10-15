package com.godotdroid.pck.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeHeaderParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPeSectionFallback_PckSectionFound() {
        // Construct mock PE file with a ".pck" section header
        val peHeaderOffset = 0x80L
        val sectionTableOffset = peHeaderOffset + 24L
        val pckDataOffset = 0x200L

        val totalSize = (pckDataOffset + 64).toInt()
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. DOS Header
        bb.putShort(PckConstants.DOS_MAGIC) // 'MZ' at 0
        bb.position(0x3C)
        bb.putInt(peHeaderOffset.toInt()) // e_lfanew at 0x3C

        // 2. PE Signature & COFF Header at 0x80
        bb.position(peHeaderOffset.toInt())
        bb.putInt(PckConstants.PE_SIGNATURE) // "PE\0\0"
        bb.putShort(0x8664.toShort()) // Machine (x86_64)
        bb.putShort(2) // NumberOfSections = 2
        bb.putInt(0) // TimeDateStamp
        bb.putInt(0) // PointerToSymbolTable
        bb.putInt(0) // NumberOfSymbols
        bb.putShort(0) // SizeOfOptionalHeader = 0
        bb.putShort(0x0002) // Characteristics

        // 3. Section 1: ".text" (40 bytes)
        bb.position(sectionTableOffset.toInt())
        val textName = ".text\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
        bb.put(textName)
        bb.putInt(0x100) // VirtualSize
        bb.putInt(0x1000) // VirtualAddress
        bb.putInt(0x100) // SizeOfRawData
        bb.putInt(0x100) // PointerToRawData
        bb.put(ByteArray(16)) // Remaining section fields

        // 4. Section 2: ".pck" (40 bytes)
        val pckName = ".pck\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
        bb.put(pckName)
        bb.putInt(0x100) // VirtualSize
        bb.putInt(0x2000) // VirtualAddress
        bb.putInt(0x100) // SizeOfRawData
        bb.putInt(pckDataOffset.toInt()) // PointerToRawData = 0x200
        bb.put(ByteArray(16)) // Remaining section fields

        // 5. PCK Data at pckDataOffset (0x200)
        bb.position(pckDataOffset.toInt())
        bb.putInt(PckConstants.PCK_MAGIC) // "GDPC"
        bb.putInt(2) // v2
        bb.putInt(4) // 4
        bb.putInt(3) // 3
        bb.putInt(0) // 0
        bb.putInt(0) // packFlags
        bb.putLong(0L) // fileBase

        val exeFile = tempFolder.newFile("test_pe_section.exe")
        FileOutputStream(exeFile).use { it.write(bb.array()) }

        RandomAccessFile(exeFile, "r").use { raf ->
            val channel = raf.channel
            val offset = PeHeaderParser.findPckSectionOffset(channel, channel.size())
            assertNotNull(offset)
            assertEquals(pckDataOffset, offset)

            // Verify PckBinaryInspector locates this via Method 2 fallback
            val location = PckBinaryInspector().locatePckStartOffset(channel, channel.size())
            assertEquals(pckDataOffset, location.pckStartOffset)
            assertEquals(true, location.isEmbeddedExe)
        }
    }

    @Test
    fun testPeSection_NotFound() {
        val exeFile = tempFolder.newFile("non_godot.exe")
        val bb = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(PckConstants.DOS_MAGIC)
        FileOutputStream(exeFile).use { it.write(bb.array()) }

        RandomAccessFile(exeFile, "r").use { raf ->
            val channel = raf.channel
            val offset = PeHeaderParser.findPckSectionOffset(channel, channel.size())
            assertNull(offset)
        }
    }
}
