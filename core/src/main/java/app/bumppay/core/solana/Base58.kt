package app.bumppay.core.solana

import java.math.BigInteger

/**
 * Base58 (Bitcoin alphabet) — the encoding Solana uses for every address.
 *
 * Hand-rolled rather than pulled in as a dependency because it is 40 lines and because
 * the one subtlety (leading zero bytes are significant and encode as leading '1's) is
 * worth having visibly in the codebase.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    // Note: 0, O, I and l are deliberately absent from the alphabet.
    private val INDEX: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    private val FIFTY_EIGHT = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        var value = BigInteger(1, input)
        val encoded = StringBuilder()
        while (value.signum() > 0) {
            val (quotient, remainder) = value.divideAndRemainder(FIFTY_EIGHT)
            encoded.append(ALPHABET[remainder.toInt()])
            value = quotient
        }

        // Each leading zero byte is a literal '1' and must be preserved, otherwise
        // "11111111111111111111111111111111" (the system program) would round-trip to "".
        val leadingZeros = input.countLeadingZeroBytes()
        repeat(leadingZeros) { encoded.append('1') }

        return encoded.reverse().toString()
    }

    /** @throws IllegalArgumentException if [input] contains a character outside the alphabet. */
    fun decode(input: String): ByteArray {
        require(input.isNotEmpty()) { "base58 input must not be empty" }

        var value = BigInteger.ZERO
        for (char in input) {
            val digit = if (char.code < 128) INDEX[char.code] else -1
            require(digit >= 0) { "invalid base58 character '$char' in \"$input\"" }
            value = value.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit.toLong()))
        }

        val magnitude = value.toByteArray() // big-endian, may carry a leading sign byte
        val body = when {
            magnitude.size == 1 && magnitude[0] == 0.toByte() -> ByteArray(0)
            magnitude[0] == 0.toByte() -> magnitude.copyOfRange(1, magnitude.size)
            else -> magnitude
        }

        val leadingZeros = input.count { it == '1' }
        return ByteArray(leadingZeros) + body
    }

    /** Convenience for the common case of decoding a 32-byte Solana address. */
    fun decodePublicKey(input: String): ByteArray {
        val bytes = decode(input)
        require(bytes.size == 32) {
            "\"$input\" decoded to ${bytes.size} bytes, expected 32 for a Solana address"
        }
        return bytes
    }

    private fun ByteArray.countLeadingZeroBytes(): Int {
        var count = 0
        while (count < size && this[count] == 0.toByte()) count++
        return count
    }
}
