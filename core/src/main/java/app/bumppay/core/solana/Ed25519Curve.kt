package app.bumppay.core.solana

import java.math.BigInteger

/**
 * The ed25519 curve check that Program Derived Address derivation depends on.
 *
 * A PDA is defined as a 32-byte hash that is deliberately *not* a valid ed25519 point, so
 * that no private key can ever exist for it. `create_program_address` therefore has to
 * attempt point decompression and reject anything that succeeds.
 *
 * Why this is spelled out at this length: the rule is easy to get subtly wrong, and the
 * failure mode is that BumpPay's session PDA silently differs from every other Solana
 * client's idea of it — which surfaces as "invalid account" only at runtime, under a tap,
 * on stage. The exact algorithm below was validated against `solders` (the reference
 * implementation) across real keypair-derived addresses and real PDAs before being
 * transcribed; see tools/verify_oncurve_rule.py.
 */
internal object Ed25519Curve {

    /** p = 2^255 - 19 */
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** d = -121665 / 121666 mod p */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** (p - 1) / 2, the exponent used for the Euler criterion. */
    private val LEGENDRE_EXPONENT: BigInteger =
        P.subtract(BigInteger.ONE).divide(BigInteger.TWO)

    /**
     * True if [compressed] is a valid compressed edwards25519 point — i.e. the address is
     * ON the curve and therefore cannot be a valid PDA.
     *
     * Mirrors curve25519-dalek's `CompressedEdwardsY::decompress()`, which is what
     * Solana's `Pubkey::is_on_curve()` calls.
     */
    fun isOnCurve(compressed: ByteArray): Boolean {
        if (compressed.size != 32) return false

        // Little-endian, with the top bit carrying the sign of x rather than part of y.
        val y = littleEndianToBigInteger(compressed).and(BigInteger.TWO.pow(255).subtract(BigInteger.ONE))

        // Non-canonical field elements are not on the curve.
        if (y >= P) return false

        // Solve x^2 = (y^2 - 1) / (d * y^2 + 1)  (mod p)
        val ySquared = y.multiply(y).mod(P)
        val numerator = ySquared.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(ySquared).add(BigInteger.ONE).mod(P)
        if (denominator.signum() == 0) return false

        val xSquared = numerator.multiply(denominator.modInverse(P)).mod(P)

        // x^2 is a square iff x^2^((p-1)/2) == 1. Zero counts as a square (the order-2 point).
        if (xSquared.signum() == 0) return true
        return xSquared.modPow(LEGENDRE_EXPONENT, P) == BigInteger.ONE
    }

    /** Little-endian byte order is what ed25519 uses everywhere. */
    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val bigEndian = ByteArray(bytes.size)
        for (index in bytes.indices) {
            bigEndian[bytes.size - 1 - index] = bytes[index]
        }
        return BigInteger(1, bigEndian)
    }
}
