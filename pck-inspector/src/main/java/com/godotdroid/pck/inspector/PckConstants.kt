package com.godotdroid.pck.inspector

object PckConstants {
    /**
     * "GDPC" magic in Little-Endian (0x43504447)
     * 'G' = 0x47, 'D' = 0x44, 'P' = 0x50, 'C' = 0x43
     */
    const val PCK_MAGIC: Int = 0x43504447

    /**
     * "MZ" DOS header magic in Little-Endian (0x5A4D)
     */
    const val DOS_MAGIC: Short = 0x5A4D.toShort()

    /**
     * "PE\0\0" PE signature in Little-Endian (0x00004550)
     */
    const val PE_SIGNATURE: Int = 0x00004550

    /**
     * "ECFG" binary config file magic in Little-Endian (0x47464345)
     */
    const val ECFG_MAGIC: Int = 0x47464345

    // Pack Header Flags
    const val PACK_DIR_ENCRYPTED: Int = 1 shl 0 // 0x01
    const val PACK_REL_FILEBASE: Int = 1 shl 1  // 0x02
    const val PACK_SPARSE_BUNDLE: Int = 1 shl 2 // 0x04

    // File Entry Flags (Godot 4, formatVersion >= 2)
    const val PACK_FILE_ENCRYPTED: Int = 1 shl 0 // 0x01
    const val PACK_FILE_REMOVAL: Int = 1 shl 1   // 0x02
}

class InvalidPckException(message: String, cause: Throwable? = null) : Exception(message, cause)
class EncryptedPckException(message: String) : Exception(message)
