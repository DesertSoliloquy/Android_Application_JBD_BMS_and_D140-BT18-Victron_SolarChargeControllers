package com.offgrid.bmsmonitor.driver

/**
 * Byte-level helpers. Kotlin's Byte is signed, so everything funnels through Int
 * with explicit masking. Big-endian (be) and little-endian (le) variants mirror
 * the struct format codes used in the original Python drivers.
 */
object B {
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    fun u16be(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    fun s16be(b: ByteArray, o: Int): Int = u16be(b, o).let { if (it >= 0x8000) it - 0x10000 else it }

    fun u16le(b: ByteArray, o: Int): Int = (u8(b, o + 1) shl 8) or u8(b, o)
    fun s16le(b: ByteArray, o: Int): Int = u16le(b, o).let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32be(b: ByteArray, o: Int): Long =
        ((u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong())
    fun s32be(b: ByteArray, o: Int): Long = u32be(b, o).let { if (it >= 0x80000000L) it - 0x100000000L else it }

    fun u32le(b: ByteArray, o: Int): Long =
        ((u8(b, o + 3).toLong() shl 24) or (u8(b, o + 2).toLong() shl 16) or
            (u8(b, o + 1).toLong() shl 8) or u8(b, o).toLong())
    fun s32le(b: ByteArray, o: Int): Long = u32le(b, o).let { if (it >= 0x80000000L) it - 0x100000000L else it }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** CRC-16/Modbus, returned low-byte-first as a 2-byte array (RTU trailer order). */
    fun modbusCrc(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    fun bytesOf(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
}
