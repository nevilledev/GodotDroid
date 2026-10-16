package com.godotdroid.pck.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeEncryptionKeyScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testShannonEntropyCalculation() {
        // Zero entropy for uniform identical bytes
        val allZeros = ByteArray(32) { 0 }
        assertEquals(0.0, PeEncryptionKeyScanner.calculateShannonEntropy(allZeros), 0.001)

        // Maximum theoretical entropy for 32 unique bytes: log2(32) = 5.0
        val unique32 = ByteArray(32) { it.toByte() }
        assertEquals(5.0, PeEncryptionKeyScanner.calculateShannonEntropy(unique32), 0.001)

        // Medium entropy for typical data
        val halfAndHalf = ByteArray(32) { if (it < 16) 0x11.toByte() else 0x22.toByte() }
        assertEquals(1.0, PeEncryptionKeyScanner.calculateShannonEntropy(halfAndHalf), 0.001)
    }

    @Test
    fun testExtractAesKeyFromSyntheticPeRdataSection() {
        val expectedKeyBytes = byteArrayOf(
            0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(),
            0xe5.toByte(), 0xf6.toByte(), 0x07.toByte(), 0x18.toByte(),
            0x29.toByte(), 0x3a.toByte(), 0x4b.toByte(), 0x5c.toByte(),
            0x6d.toByte(), 0x7e.toByte(), 0x8f.toByte(), 0x90.toByte(),
            0x01.toByte(), 0x12.toByte(), 0x23.toByte(), 0x34.toByte(),
            0x45.toByte(), 0x56.toByte(), 0x67.toByte(), 0x78.toByte(),
            0x89.toByte(), 0x9a.toByte(), 0xab.toByte(), 0xbc.toByte(),
            0xcd.toByte(), 0xde.toByte(), 0xef.toByte(), 0xf0.toByte()
        )
        val expectedHexKey = expectedKeyBytes.joinToString("") { "%02x".format(it) }
        assertEquals(64, expectedHexKey.length)

        // Build a synthetic Windows PE binary
        val totalFileSize = 0x1000 // 4096 bytes
        val peBytes = ByteArray(totalFileSize)
        val bb = ByteBuffer.wrap(peBytes).order(ByteOrder.LITTLE_ENDIAN)

        // DOS Header
        bb.put(0, 'M'.code.toByte())
        bb.put(1, 'Z'.code.toByte())
        val peHeaderOffset = 0x80
        bb.putInt(0x3C, peHeaderOffset)

        // PE Signature
        bb.position(peHeaderOffset)
        bb.putInt(PckConstants.PE_SIGNATURE)

        // COFF Header (20 bytes)
        bb.putShort(0x8664.toShort()) // Machine AMD64
        bb.putShort(2.toShort()) // NumberOfSections: 2 (.text and .rdata)
        bb.putInt(0) // TimeDateStamp
        bb.putInt(0) // PointerToSymbolTable
        bb.putInt(0) // NumberOfSymbols
        val sizeOfOptionalHeader = 240
        bb.putShort(sizeOfOptionalHeader.toShort())
        bb.putShort(0x0022.toShort()) // Characteristics

        // Optional Header (240 bytes)
        val optHeaderStart = bb.position()
        bb.putShort(0x020B.toShort()) // PE32+ (64-bit)
        // Advance to section table
        bb.position(optHeaderStart + sizeOfOptionalHeader)

        // Section 1: .text (40 bytes)
        val textName = ".text\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
        bb.put(textName)
        bb.putInt(0x200) // VirtualSize
        bb.putInt(0x1000) // VirtualAddress
        bb.putInt(0x200) // SizeOfRawData
        bb.putInt(0x400) // PointerToRawData
        bb.put(ByteArray(16)) // relocations, line numbers, flags

        // Section 2: .rdata (40 bytes)
        val rdataName = ".rdata\u0000\u0000".toByteArray(Charsets.US_ASCII)
        bb.put(rdataName)
        bb.putInt(0x400) // VirtualSize
        bb.putInt(0x2000) // VirtualAddress
        bb.putInt(0x400) // SizeOfRawData
        val rdataRawOffset = 0x600
        bb.putInt(rdataRawOffset) // PointerToRawData
        bb.put(ByteArray(16)) // relocations, line numbers, flags

        // Embed the key inside .rdata at offset rdataRawOffset + 16 (0x610)
        // Surrounded by 16 bytes of zeros before and after
        val keyOffset = rdataRawOffset + 16
        System.arraycopy(expectedKeyBytes, 0, peBytes, keyOffset, expectedKeyBytes.size)

        // Write synthetic EXE file
        val exeFile = tempFolder.newFile("game_encrypted.exe")
        FileOutputStream(exeFile).use { it.write(peBytes) }

        // Execute scan
        val candidateKeys = PeEncryptionKeyScanner.scanForCandidateKeys(exeFile)

        assertFalse("Candidate keys should not be empty", candidateKeys.isEmpty())
        assertTrue("Candidate keys should contain the embedded 64-char hex key", candidateKeys.contains(expectedHexKey))
        assertEquals("Top candidate key should be the exact high-entropy AES key", expectedHexKey, candidateKeys[0])
    }
}
