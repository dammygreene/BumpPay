package app.bumppay.core.solana

/**
 * A legacy transaction: a message plus its signature slots.
 *
 * The two serialization entry points are not interchangeable and the distinction matters:
 *
 *  - [serializeForSigning] writes zero-filled 64-byte placeholders for signatures that are
 *    not yet present. This is the form handed to Mobile Wallet Adapter, which is how the
 *    wallet knows how many signatures to produce and in what order.
 *  - [serialize] refuses to run until every required signature is real. This is the form
 *    broadcast to the network, and the assert is there so a half-signed transaction can
 *    never be sent and then explained away as an RPC problem.
 */
class Transaction internal constructor(
    val message: Message,
    private val signatures: Array<ByteArray?>,
) {

    val signatureCount: Int get() = signatures.size

    /** True once every slot holds a signature. */
    fun isFullySigned(): Boolean = signatures.all { it != null }

    /**
     * Signs with each keypair whose public key appears among the message's signers.
     *
     * The message is serialized once and every signature is produced over those same
     * bytes: signing a re-serialized copy would still work today, but it would break
     * silently the first time a mutable message is introduced.
     */
    fun partialSign(vararg keypairs: Keypair): Transaction {
        if (keypairs.isEmpty()) return this

        val messageBytes = message.serialize()
        val requiredSigners = message.accountKeys.take(message.header.numRequiredSignatures)

        keypairs.forEach { keypair ->
            val index = requiredSigners.indexOf(keypair.publicKey)
            require(index >= 0) {
                "${keypair.publicKey} is not a signer on this transaction; " +
                    "required signers are ${requiredSigners.joinToString()}"
            }
            signatures[index] = keypair.sign(messageBytes)
        }

        return this
    }

    /** Placeholder-filled serialization, for handing the transaction to a wallet. */
    fun serializeForSigning(): ByteArray {
        val writer = ByteWriter(400)
        writer.writeShortVecLength(signatures.size)
        signatures.forEach { signature ->
            // An absent signature is 64 zero bytes, not an omission.
            writer.write(signature ?: ByteArray(ED25519_SIGNATURE_LENGTH))
        }
        writer.write(message.serialize())
        return writer.toByteArray()
    }

    /** Network-ready serialization. Throws rather than shipping an invalid transaction. */
    fun serialize(): ByteArray {
        if (!isFullySigned()) {
            val missing = signatures.count { it == null }
            error(
                "refusing to serialize a transaction with $missing of ${signatures.size} " +
                    "signatures missing — broadcasting this would fail at the RPC layer " +
                    "with a signature-count error that looks unrelated to the real cause"
            )
        }
        return serializeForSigning()
    }

    fun describe(): String = buildString {
        appendLine("Transaction(signatures=${signatures.size}, signed=${isFullySigned()})")
        append(message.describe())
    }

    companion object {
        const val ED25519_SIGNATURE_LENGTH = 64

        /**
         * Builds a transaction whose signature slots are sized from the message's signer
         * count. Signing happens afterwards via [partialSign].
         */
        fun from(instructions: List<Instruction>, payer: PublicKey, recentBlockhash: ByteArray): Transaction {
            val message = Message.compile(instructions, payer, recentBlockhash)
            return Transaction(
                message = message,
                signatures = arrayOfNulls(message.header.numRequiredSignatures),
            )
        }
    }
}
