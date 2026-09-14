package app.bumppay.core.solana

/**
 * Little-endian writer plus Solana's `compact-u16` ("shortvec") length encoding.
 *
 * Every integer in a Solana instruction payload is little-endian, while the surrounding
 * message framing is not. Keeping both in one place means the two obvious bugs — writing
 * a u64 big-endian, or writing an instruction count as a single byte — are fixable in
 * exactly one spot.
 */
internal class ByteWriter(initialCapacity: Int = 128) {

    private var buffer = ByteArray(initialCapacity)
    private var length = 0

    fun writeByte(value: Int): ByteWriter {
        ensureCapacity(1)
        buffer[length++] = value.toByte()
        return this
    }

    fun write(bytes: ByteArray): ByteWriter {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, length)
        length += bytes.size
        return this
    }

    /** u16, little-endian. */
    fun writeU16(value: Int): ByteWriter {
        writeByte(value and 0xFF)
        writeByte((value ushr 8) and 0xFF)
        return this
    }

    /** u32, little-endian. */
    fun writeU32(value: Long): ByteWriter {
        writeByte((value and 0xFF).toInt())
        writeByte(((value ushr 8) and 0xFF).toInt())
        writeByte(((value ushr 16) and 0xFF).toInt())
        writeByte(((value ushr 24) and 0xFF).toInt())
        return this
    }

    /** u64, little-endian. */
    fun writeU64(value: Long): ByteWriter {
        writeU32(value and 0xFFFFFFFFL)
        writeU32((value ushr 32) and 0xFFFFFFFFL)
        return this
    }

    /**
     * Solana's `compact-u16`: 7 bits of payload per byte, with the high bit meaning
     * "another byte follows".
     *
     * The boundary that matters: a count of 200 encodes as `C8 01`, not `C8`. Any
     * implementation that writes one length byte produces a message that deserializes to
     * something completely different — usually detected only as an opaque on-chain parse
     * error. Pinned by test vector 4c.
     */
    fun writeShortVecLength(value: Int): ByteWriter {
        require(value >= 0) { "length must not be negative" }
        var remaining = value
        while (remaining >= 0x80) {
            writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining)
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(length)

    val size: Int get() = length

    private fun ensureCapacity(extra: Int) {
        if (length + extra <= buffer.size) return
        var newSize = buffer.size * 2
        while (newSize < length + extra) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }
}

/** Reads the little-endian primitives above back out. Used by tests and the terminal. */
internal class ByteReader(private val source: ByteArray, private var offset: Int = 0) {

    fun remaining(): Int = source.size - offset

    fun readByte(): Int {
        require(remaining() >= 1) { "expected 1 more byte, have ${remaining()}" }
        return source[offset++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        require(remaining() >= count) { "expected $count more bytes, have ${remaining()}" }
        return source.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun readU64(): Long {
        var result = 0L
        for (shift in 0 until 64 step 8) {
            result = result or (readByte().toLong() shl shift)
        }
        return result
    }

    fun readShortVecLength(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val current = readByte()
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) return result
            shift += 7
        }
    }
}
