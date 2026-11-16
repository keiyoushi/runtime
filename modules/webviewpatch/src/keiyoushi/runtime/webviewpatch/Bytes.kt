package keiyoushi.runtime.webviewpatch

// Little-endian readers and hex decoding, vendored from keiyoushi :core (utils/Binary.kt,
// utils/Crypto.kt) so the module carries no dependency on extensions-source. They operate on the
// in-module ELF buffers and committed byte-patterns the extension never sees.

/** Reads two bytes at [offset] as a little-endian unsigned 16-bit value, returned as an [Int]. */
internal fun ByteArray.readUShortLittleEndian(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** Reads four bytes at [offset] as a little-endian unsigned 32-bit value, returned as a [Long]. */
internal fun ByteArray.readUIntLittleEndian(offset: Int): Long = (this[offset].toLong() and 0xFF) or
    ((this[offset + 1].toLong() and 0xFF) shl 8) or
    ((this[offset + 2].toLong() and 0xFF) shl 16) or
    ((this[offset + 3].toLong() and 0xFF) shl 24)

/** Reads eight bytes at [offset] as a little-endian signed 64-bit [Long]. */
internal fun ByteArray.readLongLittleEndian(offset: Int): Long = (this[offset].toLong() and 0xFF) or
    ((this[offset + 1].toLong() and 0xFF) shl 8) or
    ((this[offset + 2].toLong() and 0xFF) shl 16) or
    ((this[offset + 3].toLong() and 0xFF) shl 24) or
    ((this[offset + 4].toLong() and 0xFF) shl 32) or
    ((this[offset + 5].toLong() and 0xFF) shl 40) or
    ((this[offset + 6].toLong() and 0xFF) shl 48) or
    ((this[offset + 7].toLong() and 0xFF) shl 56)

/**
 * Decodes this hex string to bytes.
 *
 * @throws IllegalArgumentException if the string has an odd length or contains a non-hex character.
 */
internal fun String.decodeHex(): ByteArray {
    require(length % 2 == 0) { "Unexpected hex string: $this" }

    val result = ByteArray(length / 2)
    for (i in result.indices) {
        val d1 = decodeHexDigit(this[i * 2]) shl 4
        val d2 = decodeHexDigit(this[i * 2 + 1])
        result[i] = (d1 + d2).toByte()
    }
    return result
}

private fun decodeHexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("Unexpected hex digit: $c")
}
