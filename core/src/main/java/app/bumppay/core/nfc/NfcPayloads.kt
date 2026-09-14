package app.bumppay.core.nfc

import app.bumppay.core.solana.ByteReader
import app.bumppay.core.solana.ByteWriter
import app.bumppay.core.solana.PublicKey

/**
 * The payloads carried inside BumpPay's APDUs.
 *
 * All integers are little-endian, matching Solana convention. Every decoder validates
 * length and version before reading fields, because these bytes arrive over a radio link
 * from a device that may be anything — including a hostile one.
 */
object NfcPayloads {

    const val PROTOCOL_VERSION = 1

    /** Result codes carried inside a payment response (distinct from the status word). */
    object ResultCode {
        const val OK = 0
        const val NO_SESSION = 1
        const val OVER_LIMIT = 2
        const val NOT_ARMED = 3
        const val SIGNING_FAILED = 4
        const val DESTINATION_NOT_ALLOWED = 5
        const val STALE_BLOCKHASH = 6
    }

    private const val KEY_LENGTH = 32

    // ===================================================================================
    // GET SESSION STATE response
    // ===================================================================================

    /**
     * What the payer app tells the terminal about itself before any money moves.
     *
     * The terminal uses this to render "payer ready, 25.00 USDC remaining" before the
     * cashier commits, which is a materially better demo than tapping blind.
     */
    data class SessionState(
        val isArmed: Boolean,
        val requiresReauth: Boolean,
        val remainingBaseUnits: Long,
        val limitBaseUnits: Long,
        val decimals: Int,
        val expiryUnixSeconds: Long,
        val mint: PublicKey,
    ) {

        fun encode(): ByteArray {
            val flags = (if (isArmed) FLAG_ARMED else 0) or
                (if (requiresReauth) FLAG_REQUIRES_REAUTH else 0)

            return ByteWriter(64)
                .writeByte(PROTOCOL_VERSION)
                .writeByte(flags)
                .writeU64(remainingBaseUnits)
                .writeU64(limitBaseUnits)
                .writeByte(decimals)
                .writeU64(expiryUnixSeconds)
                .write(mint.bytes)
                .toByteArray()
        }

        companion object {
            const val ENCODED_LENGTH = 1 + 1 + 8 + 8 + 1 + 8 + KEY_LENGTH

            fun decode(bytes: ByteArray): SessionState {
                require(bytes.size >= ENCODED_LENGTH) {
                    "session state payload is $ENCODED_LENGTH bytes, got ${bytes.size}"
                }
                val reader = ByteReader(bytes)
                val version = reader.readByte()
                require(version == PROTOCOL_VERSION) { "unsupported protocol version $version" }

                val flags = reader.readByte()
                return SessionState(
                    isArmed = flags and FLAG_ARMED != 0,
                    requiresReauth = flags and FLAG_REQUIRES_REAUTH != 0,
                    remainingBaseUnits = reader.readU64(),
                    limitBaseUnits = reader.readU64(),
                    decimals = reader.readByte(),
                    expiryUnixSeconds = reader.readU64(),
                    mint = PublicKey.fromBytes(reader.readBytes(KEY_LENGTH)),
                )
            }
        }
    }

    private const val FLAG_ARMED = 0x01
    private const val FLAG_REQUIRES_REAUTH = 0x02

    // ===================================================================================
    // PAYMENT REQUEST
    // ===================================================================================

    /**
     * The terminal's request: how much, to whom, and under what reference.
     *
     * ## Why the terminal supplies a blockhash
     *
     * A transaction needs a recent blockhash to be valid, and BumpPay's whole premise is
     * that the payer's phone does nothing slow at tap time. So the terminal — which
     * definitely has connectivity, because it is the one broadcasting — includes a recent
     * blockhash in the request.
     *
     * The payer app treats it as a *fallback*: while the app is in the foreground it keeps
     * its own blockhash warm (see `BlockhashCache`) and prefers that, because a blockhash
     * the terminal chose cannot be validated locally. If the phone's cache is cold or
     * stale, it falls back to this value so a tap still succeeds.
     *
     * This is a deliberate trust trade-off and is documented in docs/SECURITY.md: a
     * malicious terminal can supply a stale blockhash and make the transaction fail, but
     * it cannot make the payer spend more than the session limit or send anywhere other
     * than the session's bound destination.
     */
    data class PaymentRequest(
        val amountBaseUnits: Long,
        val decimals: Int,
        val mint: PublicKey,
        val destinationTokenAccount: PublicKey,
        val merchant: PublicKey,
        /** 8-byte order reference, echoed into the memo so the payment is greppable. */
        val reference: ByteArray,
        val terminalBlockhash: ByteArray,
    ) {

        fun encode(): ByteArray = ByteWriter(160)
            .writeByte(PROTOCOL_VERSION)
            .writeU64(amountBaseUnits)
            .writeByte(decimals)
            .write(mint.bytes)
            .write(destinationTokenAccount.bytes)
            .write(merchant.bytes)
            .write(reference)
            .write(terminalBlockhash)
            .toByteArray()

        override fun equals(other: Any?): Boolean =
            other is PaymentRequest &&
                amountBaseUnits == other.amountBaseUnits &&
                decimals == other.decimals &&
                mint == other.mint &&
                destinationTokenAccount == other.destinationTokenAccount &&
                merchant == other.merchant &&
                reference.contentEquals(other.reference) &&
                terminalBlockhash.contentEquals(other.terminalBlockhash)

        override fun hashCode(): Int {
            var result = amountBaseUnits.hashCode()
            result = 31 * result + decimals
            result = 31 * result + mint.hashCode()
            result = 31 * result + destinationTokenAccount.hashCode()
            result = 31 * result + merchant.hashCode()
            result = 31 * result + reference.contentHashCode()
            result = 31 * result + terminalBlockhash.contentHashCode()
            return result
        }

        companion object {
            const val REFERENCE_LENGTH = 8
            const val ENCODED_LENGTH =
                1 + 8 + 1 + KEY_LENGTH + KEY_LENGTH + KEY_LENGTH + REFERENCE_LENGTH + 32

            /** @throws IllegalArgumentException if the bytes are truncated or malformed. */
            fun decode(bytes: ByteArray): PaymentRequest {
                require(bytes.size >= ENCODED_LENGTH) {
                    "payment request payload is $ENCODED_LENGTH bytes, got ${bytes.size}"
                }
                val reader = ByteReader(bytes)
                val version = reader.readByte()
                require(version == PROTOCOL_VERSION) { "unsupported protocol version $version" }

                return PaymentRequest(
                    amountBaseUnits = reader.readU64(),
                    decimals = reader.readByte(),
                    mint = PublicKey.fromBytes(reader.readBytes(KEY_LENGTH)),
                    destinationTokenAccount = PublicKey.fromBytes(reader.readBytes(KEY_LENGTH)),
                    merchant = PublicKey.fromBytes(reader.readBytes(KEY_LENGTH)),
                    reference = reader.readBytes(REFERENCE_LENGTH),
                    terminalBlockhash = reader.readBytes(KEY_LENGTH),
                )
            }
        }
    }

    // ===================================================================================
    // PAYMENT RESPONSE
    // ===================================================================================

    /**
     * The payer's answer: either the signed transaction, or a code explaining why not.
     *
     * The transaction is fully signed by the time it leaves the phone (the transient
     * session key is the only required signer), so the terminal's job is reduced to
     * "base64 it and broadcast" — it never holds key material.
     */
    data class PaymentResponse(
        val resultCode: Int,
        val transaction: ByteArray,
        val usedOwnBlockhash: Boolean,
    ) {

        fun encode(): ByteArray = ByteWriter(transaction.size + 8)
            .writeByte(PROTOCOL_VERSION)
            .writeByte(resultCode)
            .writeByte(if (usedOwnBlockhash) 1 else 0)
            .writeByte(0) // reserved, keeps the header 4-byte aligned
            .write(transaction)
            .toByteArray()

        override fun equals(other: Any?): Boolean =
            other is PaymentResponse &&
                resultCode == other.resultCode &&
                usedOwnBlockhash == other.usedOwnBlockhash &&
                transaction.contentEquals(other.transaction)

        override fun hashCode(): Int {
            var result = resultCode
            result = 31 * result + if (usedOwnBlockhash) 1 else 0
            result = 31 * result + transaction.contentHashCode()
            return result
        }

        companion object {
            const val HEADER_LENGTH = 4

            fun decode(bytes: ByteArray): PaymentResponse {
                require(bytes.size >= HEADER_LENGTH) {
                    "payment response needs at least $HEADER_LENGTH bytes, got ${bytes.size}"
                }
                val reader = ByteReader(bytes)
                val version = reader.readByte()
                require(version == PROTOCOL_VERSION) { "unsupported protocol version $version" }

                val resultCode = reader.readByte()
                val usedOwnBlockhash = reader.readByte() != 0
                reader.readByte() // reserved

                return PaymentResponse(
                    resultCode = resultCode,
                    transaction = reader.readBytes(reader.remaining()),
                    usedOwnBlockhash = usedOwnBlockhash,
                )
            }

            /** Builds a failure response with no transaction attached. */
            fun failure(resultCode: Int): PaymentResponse =
                PaymentResponse(resultCode, ByteArray(0), usedOwnBlockhash = false)
        }

        val isSuccess: Boolean get() = resultCode == ResultCode.OK && transaction.isNotEmpty()

        /** Human-readable reason, for the terminal's log and the demo video. */
        fun describeFailure(): String = when (resultCode) {
            ResultCode.NO_SESSION -> "payer has no armed session"
            ResultCode.OVER_LIMIT -> "amount exceeds the payer's remaining limit"
            ResultCode.NOT_ARMED -> "payer session is not armed (screen off or backgrounded)"
            ResultCode.SIGNING_FAILED -> "payer could not sign the transaction"
            ResultCode.DESTINATION_NOT_ALLOWED -> "payer refused: destination is not the bound merchant"
            ResultCode.STALE_BLOCKHASH -> "no usable recent blockhash"
            else -> "unknown result code $resultCode"
        }
    }
}
