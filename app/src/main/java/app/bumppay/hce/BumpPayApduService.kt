package app.bumppay.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import app.bumppay.BumpPayApplication
import app.bumppay.core.nfc.BumpPayApdu
import app.bumppay.core.nfc.NfcPayloads

/**
 * Host Card Emulation: this class owns the tap.
 *
 * ## Contract with Android
 *
 * The NFC stack calls [processCommandApdu] on a **binder thread** with a hard timeout
 * (roughly 500ms per command before the field drops). Nothing here may block on I/O. That
 * constraint shapes the whole design: the blockhash is pre-cached, the transaction is built
 * locally, and the only potentially-blocking work is a Keystore unseal, which is
 * sub-millisecond on a warm device.
 *
 * [processCommandApdu] must return the response APDU synchronously, so the signing path is
 * called directly rather than launched into a coroutine. Deferring it would mean returning
 * an empty response and losing the tap.
 *
 * ## Why a chunked response exists
 *
 * A signed transfer transaction is ~370 bytes; a short response APDU carries at most 256.
 * So a successful payment answers `61 XX` and the terminal pulls the rest with
 * `GET RESPONSE`. Both sides implement this in `:core`, so they cannot disagree.
 */
class BumpPayApduService : HostApduService() {

    private val sessionManager
        get() = (application as BumpPayApplication).sessionManager

    /**
     * Bytes still owed to the reader after a partial response.
     *
     * Reset on every SELECT so a failed or abandoned tap cannot leak a partial transaction
     * into the next one — a subtle and nasty bug class for anything doing chunked NFC.
     */
    private var pending: ByteArray = ByteArray(0)

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val command = BumpPayApdu.parseCommand(commandApdu)
            ?: return BumpPayApdu.error(BumpPayApdu.Sw.WRONG_LENGTH)

        // A new SELECT means a new tap: drop anything left over from the previous one.
        if (command.isSelectOfBumpPay()) {
            pending = ByteArray(0)
            Log.d(TAG, "tap opening: SELECT ${BumpPayApdu.AID_HEX}")
            return BumpPayApdu.status(BumpPayApdu.Sw.OK)
        }

        return when (command.ins) {
            BumpPayApdu.Ins.GET_SESSION_STATE -> handleSessionState()

            BumpPayApdu.Ins.PAYMENT_REQUEST -> handlePaymentRequest(command.data)

            BumpPayApdu.Ins.GET_RESPONSE -> handleGetResponse()

            BumpPayApdu.Ins.CANCEL_PAYMENT -> {
                pending = ByteArray(0)
                Log.d(TAG, "tap cancelled by reader")
                BumpPayApdu.status(BumpPayApdu.Sw.OK)
            }

            BumpPayApdu.Ins.SELECT -> BumpPayApdu.error(BumpPayApdu.Sw.NO_SESSION)

            else -> BumpPayApdu.error(BumpPayApdu.Sw.INS_NOT_SUPPORTED)
        }
    }

    private fun handleSessionState(): ByteArray {
        val record = sessionManager.session.value
        val phase = sessionManager.phase.value

        if (record == null) {
            return BumpPayApdu.error(BumpPayApdu.Sw.NO_SESSION)
        }

        val state = NfcPayloads.SessionState(
            isArmed = phase is app.bumppay.session.SessionPhase.Armed,
            requiresReauth = phase is app.bumppay.session.SessionPhase.Disarmed &&
                phase.reason == app.bumppay.session.DisarmReason.DEVICE_LOCKED,
            remainingBaseUnits = record.remainingBaseUnits,
            limitBaseUnits = record.limitBaseUnits,
            decimals = record.decimals,
            expiryUnixSeconds = record.expiryUnixSeconds,
            mint = record.mint,
        )

        // Never chunked: 59 bytes always fits, and a single response is one fewer round trip
        // in the window that decides whether the demo feels instant.
        return BumpPayApdu.response(state.encode(), BumpPayApdu.Sw.OK)
    }

    private fun handlePaymentRequest(payload: ByteArray): ByteArray {
        val request = try {
            NfcPayloads.PaymentRequest.decode(payload)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "malformed payment request: ${e.message}")
            return BumpPayApdu.error(BumpPayApdu.Sw.WRONG_LENGTH)
        }

        val response = sessionManager.preparePayment(request)

        // Map the result onto the protocol: the status word is what a reader acts on
        // immediately, and it is what the Phase 4 checklist demonstrates on camera.
        if (!response.isSuccess) {
            Log.i(TAG, "tap refused: ${response.describeFailure()}")
            return BumpPayApdu.error(statusWordFor(response.resultCode))
        }

        val encoded = response.encode()
        val (firstResponse, remaining) = BumpPayApdu.firstChunk(encoded)
        pending = remaining

        if (remaining.isEmpty()) {
            Log.i(TAG, "tap settled in a single response (${encoded.size} bytes)")
        } else {
            Log.i(TAG, "tap signed (${encoded.size} bytes), ${remaining.size} bytes to fetch")
        }

        return firstResponse
    }

    private fun handleGetResponse(): ByteArray {
        if (pending.isEmpty()) {
            return BumpPayApdu.error(BumpPayApdu.Sw.INS_NOT_SUPPORTED)
        }

        val (response, remaining) = BumpPayApdu.nextChunk(pending)
        pending = remaining
        return response
    }

    private fun statusWordFor(resultCode: Int): Int = when (resultCode) {
        NfcPayloads.ResultCode.NO_SESSION -> BumpPayApdu.Sw.NO_SESSION
        NfcPayloads.ResultCode.OVER_LIMIT -> BumpPayApdu.Sw.OVER_LIMIT
        NfcPayloads.ResultCode.NOT_ARMED -> BumpPayApdu.Sw.NO_SESSION
        NfcPayloads.ResultCode.DESTINATION_NOT_ALLOWED -> BumpPayApdu.Sw.CONDITIONS_NOT_SATISFIED
        NfcPayloads.ResultCode.SIGNING_FAILED -> BumpPayApdu.Sw.INTERNAL_ERROR
        NfcPayloads.ResultCode.STALE_BLOCKHASH -> BumpPayApdu.Sw.CONDITIONS_NOT_SATISFIED
        else -> BumpPayApdu.Sw.INTERNAL_ERROR
    }

    /**
     * The field dropped. Anything owed to the reader is now worthless: the transaction was
     * never delivered, so discarding it is both correct and a small security win — a signed
     * transaction sitting in a buffer is spendable by whatever reads it next.
     */
    override fun onDeactivated(reason: Int) {
        if (pending.isNotEmpty()) {
            Log.w(TAG, "field lost with ${pending.size} bytes undelivered; discarding")
        }
        pending = ByteArray(0)
    }

    private companion object {
        const val TAG = "BumpPayHce"
    }
}
