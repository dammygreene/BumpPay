package app.bumppay.core.solana

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A 32-byte Solana address, plus PDAs and address helpers.
 *
 * Deliberately a distinct type rather than a bare `ByteArray`: most of the bugs in
 * hand-rolled transaction code come from mixing up which byte array is which, and a type
 * that stringifies to base58 makes logs readable.
 */
class PublicKey(bytes: ByteArray) {

    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == 32) {
            "a Solana public key is exactly 32 bytes, got ${bytes.size}"
        }
    }

    /** `String.toPublicKey()` is a common idiom; this is the explicit form. */
    override fun toString(): String = Base58.encode(bytes)

    override fun equals(other: Any?): Boolean =
        this === other || (other is PublicKey && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** True when this address sits on the ed25519 curve (so no PDA bump could produce it). */
    fun isOnCurve(): Boolean = Ed25519Curve.isOnCurve(bytes)

    fun toBase58(): String = Base58.encode(bytes)

    fun toHex(): String = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    companion object {

        /**
         * The domain-separation marker Solana appends to PDA preimages. Including it is
         * what stops PDA hashes colliding with ordinary hashes.
         */
        private val PDA_MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

        /**
         * Hard limit on a single seed; Solana rejects longer ones at runtime.
         */
        const val MAX_SEED_LENGTH = 32

        fun fromBase58(value: String): PublicKey = PublicKey(Base58.decodePublicKey(value))

        fun fromBytes(bytes: ByteArray): PublicKey = PublicKey(bytes)

        /**
         * Deterministically derived address that no keypair can own — or `null` if the
         * resulting hash happens to land on the curve, in which case the caller must bump.
         */
        fun createProgramAddress(seeds: List<ByteArray>, programId: PublicKey): PublicKey? {
            seeds.forEach { seed ->
                require(seed.size <= MAX_SEED_LENGTH) {
                    "PDA seeds must be at most $MAX_SEED_LENGTH bytes, got ${seed.size}"
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            seeds.forEach(digest::update)
            digest.update(programId.bytes)
            digest.update(PDA_MARKER)

            val hash = digest.digest()
            if (Ed25519Curve.isOnCurve(hash)) return null
            return PublicKey(hash)
        }

        /**
         * Same as [createProgramAddress], but walks the bump byte down from 255 until it
         * finds a hash that is off-curve. This is the function that must agree with every
         * other Solana client, so its output is pinned by a test vector.
         *
         * @return the derived address and the bump that produced it (the bump must be
         *   passed back to the on-chain program as a seed for it to re-derive the PDA).
         */
        fun findProgramAddress(seeds: List<ByteArray>, programId: PublicKey): Pair<PublicKey, Int> {
            for (bump in 255 downTo 0) {
                val candidate = createProgramAddress(seeds + byteArrayOf(bump.toByte()), programId)
                if (candidate != null) return candidate to bump
            }
            error("unable to find a viable program address bump")
        }

        /**
         * Associated Token Account derivation, used so the payer does not have to be told
         * where the merchant's token account is.
         */
        fun findAssociatedTokenAddress(
            owner: PublicKey,
            mint: PublicKey,
            tokenProgramId: PublicKey = SolanaPrograms.TOKEN,
            associatedTokenProgramId: PublicKey = SolanaPrograms.ASSOCIATED_TOKEN,
        ): PublicKey = findProgramAddress(
            listOf(owner.bytes, tokenProgramId.bytes, mint.bytes),
            associatedTokenProgramId,
        ).first
    }
}

/** Generates a fresh 32-byte seed from the platform CSPRNG. */
internal fun randomSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
