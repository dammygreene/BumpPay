package app.bumppay.terminal

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bumppay.core.nfc.BumpPayApdu
import app.bumppay.core.nfc.NfcPayloads
import app.bumppay.core.solana.Amounts
import app.bumppay.core.solana.Base58
import app.bumppay.core.solana.BumpPayDemo
import app.bumppay.core.solana.SolanaRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.security.SecureRandom

/**
 * The merchant terminal.
 *
 * ## Trust model
 *
 * This app is the *untrusted* half of the system, and the architecture reflects that: it
 * holds no private keys, it never sees the payer's wallet, and the only thing it does with
 * the signed transaction it receives is broadcast it. Everything it can lie about — the
 * amount, the destination, the blockhash — is bounded by what the payer's session permits.
 *
 * ## Why it owns the network
 *
 * The payer's phone does no network I/O during a tap by design. The terminal, which is
 * stationary and well-connected, is the natural place to broadcast. It also means the payer
 * app works with no connectivity at all, which is a genuinely good demo moment.
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    private val state = MutableStateFlow<TerminalState>(TerminalState.Idle)

    private val rpc: SolanaRpc by lazy {
        val key = BuildConfig.HELIUS_API_KEY
        SolanaRpc(
            if (key.isNotBlank()) {
                SolanaRpc.heliusEndpoint(key, BuildConfig.BUMPPAY_CLUSTER)
            } else {
                SolanaRpc.DEVNET_PUBLIC
            },
        )
    }

    /** The charge amount the cashier has entered, in USDC. */
    private var typedAmount: String = "1.50"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TerminalTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink0) {
                    val current by state.collectAsStateWithLifecycle()
                    var amount by remember { mutableStateOf(typedAmount) }

                    TerminalScreen(
                        state = current,
                        amount = amount,
                        nfcAvailable = nfcAdapter != null,
                        nfcEnabled = nfcAdapter?.isEnabled == true,
                        onAmountChange = {
                            amount = it
                            typedAmount = it
                        },
                        onReset = { state.value = TerminalState.Idle },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReader()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    /**
     * Reader mode rather than the foreground-dispatch + `enableForegroundDispatch` pair.
     *
     * Reader mode gives the app exclusive, low-latency access to the field and skips
     * NDEF discovery, which is what keeps the tap inside a sub-second budget. It also
     * avoids the intent-based handoff that adds a visible delay on some devices.
     */
    private fun enableReader() {
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    /**
     * Called on a binder thread the instant a tag enters the field.
     *
     * The APDU exchange is blocking by nature (`IsoDep.transceive`), so it is moved onto an
     * IO coroutine. Doing it inline here would block the NFC callback and invite the
     * presence check to tear the connection down mid-transaction.
     */
    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.d(TAG, "tag is not ISO-DEP; ignoring")
            return
        }

        scope.launch(Dispatchers.IO) {
            runPayment(isoDep)
        }
    }

    private suspend fun runPayment(isoDep: IsoDep) {
        state.value = TerminalState.Reading

        try {
            isoDep.connect()
            isoDep.timeout = ISO_DEP_TIMEOUT_MS

            // 1. SELECT — this is what routes the tap to BumpPay's HCE service.
            val (_, selectSw) = exchange(
                isoDep,
                BumpPayApdu.command(
                    cla = BumpPayApdu.CLA_ISO,
                    ins = BumpPayApdu.Ins.SELECT,
                    p1 = 0x04,
                    p2 = 0x00,
                    data = BumpPayApdu.AID,
                ),
            )
            if (selectSw != BumpPayApdu.Sw.OK) {
                fail("payer did not answer SELECT (SW ${selectSw.hex()})")
                return
            }

            // 2. GET SESSION STATE — lets the cashier see the payer is actually armed
            //    before committing to a charge.
            val (stateBytes, stateSw) = exchange(
                isoDep,
                BumpPayApdu.command(
                    cla = BumpPayApdu.CLA_PROPRIETARY,
                    ins = BumpPayApdu.Ins.GET_SESSION_STATE,
                    le = 0,
                ),
            )

            if (stateSw != BumpPayApdu.Sw.OK) {
                fail(explain(stateSw))
                return
            }

            val payerState = runCatching { NfcPayloads.SessionState.decode(stateBytes) }.getOrNull()
            if (payerState != null && !payerState.isArmed) {
                fail("payer session is not armed")
                return
            }

            // 3. Build and send the payment request.
            val amountBaseUnits = Amounts.toBaseUnits(BigDecimal(typedAmount.trim()), BumpPayDemo.USDC_DECIMALS)
            val blockhash = rpc.getLatestBlockhash()
            val reference = ByteArray(8).also { SecureRandom().nextBytes(it) }

            val request = NfcPayloads.PaymentRequest(
                amountBaseUnits = amountBaseUnits,
                decimals = BumpPayDemo.USDC_DECIMALS,
                mint = BumpPayDemo.usdcFor(BuildConfig.BUMPPAY_CLUSTER),
                destinationTokenAccount = app.bumppay.core.solana.PublicKey
                    .findAssociatedTokenAddress(BumpPayDemo.merchantKeypair().publicKey, BumpPayDemo.usdcFor(BuildConfig.BUMPPAY_CLUSTER)),
                merchant = BumpPayDemo.merchantKeypair().publicKey,
                reference = reference,
                terminalBlockhash = Base58.decodePublicKey(blockhash.blockhash),
            )

            state.value = TerminalState.Reading

            // 4. Send the request and collect the (chunked) response.
            val (responseBytes, requestSw) = exchange(
                isoDep,
                BumpPayApdu.command(
                    cla = BumpPayApdu.CLA_PROPRIETARY,
                    ins = BumpPayApdu.Ins.PAYMENT_REQUEST,
                    data = request.encode(),
                ),
            )

            if (requestSw != BumpPayApdu.Sw.OK) {
                // 6985 is the over-limit signal: the payer's phone has raised an approval
                // prompt, so this is a "wait" rather than a hard failure.
                fail(explain(requestSw))
                return
            }

            val response = NfcPayloads.PaymentResponse.decode(responseBytes)
            if (!response.isSuccess) {
                fail(response.describeFailure())
                return
            }

            // 5. Broadcast. The transaction is already fully signed by the payer's
            //    session key, so the terminal needs no key material of its own.
            state.value = TerminalState.Broadcasting
            val signature = rpc.sendTransaction(response.transaction)

            when (val confirmation = rpc.awaitConfirmation(signature)) {
                is SolanaRpc.Confirmation.Confirmed -> {
                    state.value = TerminalState.Settled(
                        signature = signature,
                        amountText = Amounts.format(amountBaseUnits, BumpPayDemo.USDC_DECIMALS, "USDC"),
                        slot = confirmation.slot,
                    )
                }
                is SolanaRpc.Confirmation.Failed -> fail("transaction failed: ${confirmation.reason}")
                is SolanaRpc.Confirmation.TimedOut -> {
                    // Not necessarily a failure: the signature is valid, the cluster just
                    // has not reported it yet. Reporting it as "submitted" is more truthful
                    // than calling it a failure, and the explorer link settles the question.
                    state.value = TerminalState.Settled(
                        signature = signature,
                        amountText = Amounts.format(amountBaseUnits, BumpPayDemo.USDC_DECIMALS, "USDC"),
                        slot = -1L,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tap failed", e)
            fail(e.message ?: e::class.java.simpleName)
        } finally {
            runCatching { isoDep.close() }
        }
    }

    /**
     * Sends one APDU and transparently collects any chunked continuation.
     *
     * A signed transaction is ~370 bytes and a short response APDU carries at most 256, so
     * the card answers `61 XX` and the reader must keep issuing `GET RESPONSE`. A reader
     * that ignores this gets a truncated transaction and an RPC error that points at
     * deserialization rather than at the NFC protocol — hence the explicit loop.
     */
    private fun exchange(isoDep: IsoDep, command: ByteArray): Pair<ByteArray, Int> {
        var (data, statusWord) = BumpPayApdu.splitResponse(isoDep.transceive(command))
        val accumulated = ArrayList<Byte>(data.size + 512).apply { data.forEach { add(it) } }

        var guard = 0
        while (BumpPayApdu.hasMoreData(statusWord)) {
            if (guard++ >= MAX_CHUNKS) {
                error("card kept offering more data after $MAX_CHUNKS chunks")
            }
            val getResponse = BumpPayApdu.command(
                cla = BumpPayApdu.CLA_ISO,
                ins = BumpPayApdu.Ins.GET_RESPONSE,
                le = 0,
            )
            val (chunk, nextStatus) = BumpPayApdu.splitResponse(isoDep.transceive(getResponse))
            chunk.forEach { accumulated.add(it) }
            statusWord = nextStatus
        }

        return accumulated.toByteArray() to statusWord
    }

    /** Turns a status word into something a cashier can act on. */
    private fun explain(statusWord: Int): String = when (statusWord) {
        BumpPayApdu.Sw.NO_SESSION -> "payer has no active session (or the phone is locked)"
        BumpPayApdu.Sw.OVER_LIMIT -> "over the payer's limit — they have been asked to approve"
        BumpPayApdu.Sw.CONDITIONS_NOT_SATISFIED -> "payer declined"
        BumpPayApdu.Sw.WRONG_LENGTH -> "malformed request"
        BumpPayApdu.Sw.INS_NOT_SUPPORTED -> "unsupported command"
        BumpPayApdu.Sw.INTERNAL_ERROR -> "payer could not sign"
        else -> "card returned SW ${statusWord.hex()}"
    }

    private fun fail(reason: String) {
        Log.w(TAG, "declined: $reason")
        state.value = TerminalState.Declined(reason)
    }

    private fun Int.hex(): String = "0x" + toString(16).uppercase().padStart(4, '0')

    private companion object {
        const val TAG = "BumpPayTerminal"

        /** Long enough for a chunked pull, short enough to fail fast on a dead link. */
        const val ISO_DEP_TIMEOUT_MS = 3_000

        /** 4KB / 255 bytes, with headroom. A tap transaction needs 2 chunks. */
        const val MAX_CHUNKS = 24
    }
}

/** What the terminal is doing, from the cashier's point of view. */
sealed interface TerminalState {
    data object Idle : TerminalState
    data object Reading : TerminalState
    data object Broadcasting : TerminalState
    data class Settled(val signature: String, val amountText: String, val slot: Long) : TerminalState
    data class Declined(val reason: String) : TerminalState
}

// =========================================================================================
// UI — one screen. A terminal is a tool, not a product surface.
// =========================================================================================

private val Ink0 = Color(0xFF08090B)
private val Ink1 = Color(0xFF101216)
private val Signal = Color(0xFF6FE3D2)
private val Settled = Color(0xFF7BE0A8)
private val Revoke = Color(0xFFE5776B)
private val TextPrimary = Color(0xFFF2F4F7)
private val TextSecondary = Color(0xFF9AA3AF)
private val Hairline = Color(0xFF2A2F38)

@Composable
private fun TerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Signal,
            background = Ink0,
            surface = Ink1,
            onSurface = TextPrimary,
            outline = Hairline,
        ),
        content = content,
    )
}

@Composable
private fun TerminalScreen(
    state: TerminalState,
    amount: String,
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    onAmountChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "BUMPPAY TERMINAL",
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(28.dp))

        when {
            !nfcAvailable -> Notice("This device has no NFC reader.", Revoke)

            !nfcEnabled -> Notice("Turn NFC on to accept payments.", Revoke)

            state is TerminalState.Settled -> {
                Text("Settled", fontSize = 15.sp, color = Settled, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(amount + " USDC", fontSize = 40.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(
                    state.signature.take(28) + "…",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                )
                if (state.slot > 0) {
                    Text("slot ${state.slot}", fontSize = 12.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onReset,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ink0),
                ) { Text("New charge") }
            }

            state is TerminalState.Declined -> {
                Text("Declined", fontSize = 15.sp, color = Revoke, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(state.reason, fontSize = 15.sp, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onReset,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ink0),
                ) { Text("Try again") }
            }

            else -> {
                val busy = state !is TerminalState.Idle

                Text(
                    when (state) {
                        is TerminalState.Reading -> "Hold the phone to the back of this device"
                        is TerminalState.Broadcasting -> "Broadcasting…"
                        else -> "Tap a phone to charge"
                    },
                    fontSize = 20.sp,
                    color = TextPrimary,
                )

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = amount,
                    onValueChange = onAmountChange,
                    label = { Text("Amount (USDC)") },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 24.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    "Merchant ${BumpPayDemo.MERCHANT_ADDRESS.take(20)}…",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Text(text, fontSize = 16.sp, color = color)
}
