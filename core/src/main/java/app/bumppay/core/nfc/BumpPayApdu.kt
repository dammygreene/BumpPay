package app.bumppay.core.nfc

import app.bumppay.core.solana.ByteWriter

/**
 * The APDU contract between the payer app (card emulation) and the merchant terminal
 * (reader). Both phones compile this same file, which is the point of the `:core` module.
 *
 * ## Why a chunked response exists
 *
 * A signed Solana transaction for a simple SPL transfer is roughly 370-400 bytes. A short
 * ISO 7816-4 response APDU carries at most 256 bytes of data, so the transaction cannot be
 * returned in a single response — it has to be pulled in pieces. This is not a design
 * preference; it is the constraint that shapes the protocol.
 *
 * The standard mechanism is used: a successful command answers `61 XX` ("XX bytes
 * available"), and the reader issues `GET RESPONSE` until it receives `90 00`. Readers
 * that are not aware of this (or a phone that tries to send 400 bytes at once) will appear
 * to hang or produce a truncated transaction, so both sides implement it.
 *
 * ## Status words
 *
 * Status words are chosen to be meaningful rather than generic, because the Phase 4
 * security checklist requires demonstrating them on camera:
 *
 * | SW      | Meaning                                                    |
 * |---------|------------------------------------------------------------|
 * | `9000`  | success                                                    |
 * | `6A82`  | no armed session — including the locked/backgrounded case   |
 * | `6985`  | amount exceeds the remaining session limit                  |
 * | `6986`  | session exists but is not in a state that allows spending   |
 * | `6A86`  | incorrect P1/P2                                             |
 * | `6700`  | wrong length                                                |
 * | `6D00`  | instruction not supported                                   |
 * | `6F00`  | internal failure (signing or serialization)                 |
 */
object BumpPayApdu {

    /**
     * The Application ID that Android uses to route a tap to BumpPay instead of to Google
     * Wallet.
     *
     * Layout: `F0` (ISO 7816 proprietary class) + "BUMPPAY" ASCII + `01` protocol version.
     * Nine bytes, well inside the 5-16 byte range. The `F0` prefix keeps it clear of the
     * payment-network AIDs (`A0000000…`) that a real wallet uses, which is what makes the
     * AID-collision check in Phase 4 meaningful: if a tap reaches BumpPay, no other HCE app
     * claimed this AID.
     */
    val AID = byteArrayOf(
        0xF0.toByte(),
        0x42, 0x55, 0x4D, 0x50, 0x50, 0x41, 0x59, // "BUMPPAY"
        0x01,
    )

    /** The AID as it appears in apduservice.xml. Kept adjacent so the two cannot drift. */
    val AID_HEX: String = AID.joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    object Ins {
        const val GET_SESSION_STATE = 0x10
        const val PAYMENT_REQUEST = 0x20
        const val CANCEL_PAYMENT = 0x30
        const val GET_RESPONSE = 0xC0
        const val SELECT = 0xA4
    }

    const val CLA_ISO = 0x00
    const val CLA_PROPRIETARY = 0x80

    object Sw {
        const val OK = 0x9000

        /** No armed session, or the session was torn down on `onPause`. */
        const val NO_SESSION = 0x6A82

        /** Amount exceeds the remaining pre-authorised limit. */
        const val OVER_LIMIT = 0x6985

        /** Session present but not spendable (e.g. requires re-auth). */
        const val CONDITIONS_NOT_SATISFIED = 0x6986

        const val WRONG_P1P2 = 0x6A86
        const val WRONG_LENGTH = 0x6700
        const val INS_NOT_SUPPORTED = 0x6D00
        const val INTERNAL_ERROR = 0x6F00

        /** "XX more bytes available" — the chunked-response marker. */
        fun moreAvailable(count: Int): Int = 0x6100 or (count and 0xFF)
    }

    /** A parsed command APDU. */
    data class Command(
        val cla: Int,
        val ins: Int,
        val p1: Int,
        val p2: Int,
        val data: ByteArray,
        val expectedLength: Int,
    ) {
        // data class equality on a ByteArray needs an explicit override to be meaningful;
        // the generated one compares references, which silently breaks assertEquals.
        override fun equals(other: Any?): Boolean =
            other is Command &&
                cla == other.cla && ins == other.ins && p1 == other.p1 && p2 == other.p2 &&
                data.contentEquals(other.data) && expectedLength == other.expectedLength

        override fun hashCode(): Int {
            var result = cla
            result = 31 * result + ins
            result = 31 * result + p1
            result = 31 * result + p2
            result = 31 * result + data.contentHashCode()
            result = 31 * result + expectedLength
            return result
        }

        /** True for `SELECT AID` with the BumpPay AID, i.e. the tap opening the session. */
        fun isSelectOfBumpPay(): Boolean =
            cla == CLA_ISO && ins == Ins.SELECT && p1 == 0x04 &&
                data.contentEquals(AID)
    }

    /**
     * Parses an APDU received by the HostApduService.
     *
     * @return null when the bytes are not a well-formed command APDU, or when the expected
     *   response length is absent (which Android permits for some readers).
     */
    fun parseCommand(apdu: ByteArray): Command? {
        if (apdu.size < 4) return null

        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        // Case 1: no Lc, no Le.
        if (apdu.size == 4) {
            return Command(cla, ins, p1, p2, ByteArray(0), expectedLength = 0)
        }

        var offset = 4
        var lc = apdu[offset].toInt() and 0xFF
        offset++

        // Extended-length APDU (Lc == 0 and two length bytes follow). Reader mode on
        // Android does not normally produce these, but a hostile or buggy reader could,
        // and mis-parsing one would be a silent protocol desync.
        if (lc == 0x00 && apdu.size > offset + 1) {
            val extended = ((apdu[offset].toInt() and 0xFF) shl 8) or (apdu[offset + 1].toInt() and 0xFF)
            if (apdu.size >= offset + 2 + extended) {
                lc = extended
                offset += 2
            }
        }

        if (apdu.size < offset + lc) return null
        val data = apdu.copyOfRange(offset, offset + lc)
        offset += lc

        // Optional Le byte. 0 means "up to 256 bytes".
        val le = if (apdu.size > offset) apdu[offset].toInt() and 0xFF else 0

        return Command(cla, ins, p1, p2, data, expectedLength = le)
    }

    /** Builds a response APDU: data followed by the two status bytes. */
    fun response(data: ByteArray, statusWord: Int): ByteArray {
        val writer = ByteWriter(data.size + 2)
        writer.write(data)
        writer.writeByte((statusWord ushr 8) and 0xFF)
        writer.writeByte(statusWord and 0xFF)
        return writer.toByteArray()
    }

    /**
     * Builds a command APDU — the reader's half of the contract.
     *
     * Lives here rather than in the terminal app so that the two halves of the protocol are
     * defined in one file. A reader that builds a subtly different APDU shape from what the
     * emulator parses is a failure mode that presents as "the tap does nothing", with no
     * error on either side.
     *
     * Emits the short form (`Lc` as a single byte) because every BumpPay command payload is
     * well under 256 bytes: the largest is a payment request at ~146 bytes.
     */
    fun command(
        cla: Int,
        ins: Int,
        p1: Int = 0,
        p2: Int = 0,
        data: ByteArray = ByteArray(0),
        /** 0 means "up to 256 bytes"; omit for commands with no expected data. */
        le: Int = 0,
    ): ByteArray {
        require(data.size <= MAX_SHORT_APDU_DATA) {
            "command payload of ${data.size} bytes exceeds the short APDU limit"
        }

        val writer = ByteWriter(data.size + 6)
        writer.writeByte(cla)
        writer.writeByte(ins)
        writer.writeByte(p1)
        writer.writeByte(p2)
        if (data.isNotEmpty()) {
            writer.writeByte(data.size)
            writer.write(data)
        }
        if (le > 0) writer.writeByte(le)
        return writer.toByteArray()
    }

    /** Splits a response APDU into its data and its status word. */
    fun splitResponse(apdu: ByteArray): Pair<ByteArray, Int> {
        require(apdu.size >= 2) { "a response APDU is at least 2 bytes, got ${apdu.size}" }
        val statusWord = ((apdu[apdu.size - 2].toInt() and 0xFF) shl 8) or
            (apdu[apdu.size - 1].toInt() and 0xFF)
        return apdu.copyOfRange(0, apdu.size - 2) to statusWord
    }

    /** True when a status word is the chunked-response marker `61 XX`. */
    fun hasMoreData(statusWord: Int): Boolean = (statusWord and 0xFF00) == 0x6100

    /** Bytes the card says are still waiting, per a `61 XX` status word. */
    fun moreDataLength(statusWord: Int): Int = (statusWord and 0xFF).let { if (it == 0) 256 else it }

    /** A bare status word with no data. */
    fun status(statusWord: Int): ByteArray = response(ByteArray(0), statusWord)

    /** Convenience for the error paths, which are all bare status words. */
    fun error(statusWord: Int): ByteArray = status(statusWord)

    /**
     * Splits a payload that is too large for one short APDU into a first response and the
     * bytes still owed.
     *
     * @return the response to send now, plus what remains for `GET RESPONSE` to serve.
     */
    fun firstChunk(payload: ByteArray, maxChunk: Int = MAX_SHORT_APDU_DATA): Pair<ByteArray, ByteArray> {
        if (payload.size <= maxChunk) {
            return response(payload, Sw.OK) to ByteArray(0)
        }
        val head = payload.copyOfRange(0, maxChunk)
        val tail = payload.copyOfRange(maxChunk, payload.size)
        // 61 XX tells the reader exactly how many bytes are waiting.
        return response(head, Sw.moreAvailable(tail.size.coerceAtMost(0xFF))) to tail
    }

    /**
     * Serves one `GET RESPONSE`.
     *
     * @return the response to send now, plus whatever still remains.
     */
    fun nextChunk(remaining: ByteArray, maxChunk: Int = MAX_SHORT_APDU_DATA): Pair<ByteArray, ByteArray> {
        if (remaining.size <= maxChunk) {
            return response(remaining, Sw.OK) to ByteArray(0)
        }
        val head = remaining.copyOfRange(0, maxChunk)
        val tail = remaining.copyOfRange(maxChunk, remaining.size)
        return response(head, Sw.moreAvailable(tail.size.coerceAtMost(0xFF))) to tail
    }

    /**
     * 255 bytes rather than 256.
     *
     * A short APDU can technically carry 256 bytes of data, but `61 00` is the encoding for
     * "256 bytes available" and several readers treat `00` as "nothing to fetch". Capping
     * at 255 keeps the count unambiguous, at the cost of one extra round trip.
     */
    const val MAX_SHORT_APDU_DATA = 255

    /** Total response budget, guards against a reader claiming a huge Le. */
    const val MAX_RESPONSE_BYTES = 4 * 1024
}
