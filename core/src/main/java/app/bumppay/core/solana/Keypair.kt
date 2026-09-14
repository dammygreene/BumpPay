package app.bumppay.core.solana

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 keypair and signing.
 *
 * Uses BouncyCastle's *lightweight* API rather than the JCA provider deliberately. Android
 * ships its own cut-down BouncyCastle, and registering a second provider under the same
 * name is a classic source of "works on one device, throws NoSuchAlgorithmException on
 * another". Calling the primitive directly sidesteps provider resolution entirely.
 *
 * `Signature.getInstance("Ed25519")` would be the zero-dependency option, but it is only
 * available from API 33 and BumpPay's minSdk is 24.
 */
class Keypair private constructor(
    /** 32-byte seed. This is the actual secret; guard it accordingly. */
    val seed: ByteArray,
) {

    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)

    private val publicKeyParameters: Ed25519PublicKeyParameters = privateKey.generatePublicKey()

    val publicKey: PublicKey = PublicKey(publicKeyParameters.encoded)

    /** Raw 32-byte public key, for the rare places that want bytes. */
    val publicKeyBytes: ByteArray get() = publicKeyParameters.encoded

    /**
     * Signs [message] (i.e. the serialized transaction message) producing a 64-byte
     * signature. Callers should zero [message] afterwards when it is done with — it is
     * the exact bytes that will be broadcast.
     */
    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {

        fun fromSeed(seed: ByteArray): Keypair {
            require(seed.size == 32) { "an ed25519 seed is 32 bytes, got ${seed.size}" }
            return Keypair(seed.copyOf())
        }

        fun generate(): Keypair {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return Keypair(seed)
        }

        /** Verifies a detached signature. Used by the terminal to sanity-check payloads. */
        fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey.bytes, 0))
            verifier.update(message, 0, message.size)
            return verifier.verifySignature(signature)
        }
    }
}
