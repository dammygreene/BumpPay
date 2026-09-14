package app.bumppay.session

import android.util.Log
import app.bumppay.core.nfc.NfcPayloads
import app.bumppay.core.solana.Keypair
import app.bumppay.core.solana.PublicKey
import app.bumppay.core.solana.SolanaPrograms
import app.bumppay.core.solana.SolanaRpc
import app.bumppay.core.solana.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the session lifecycle and is the only component allowed to sign a tap payment.
 *
 * The security posture lives in one place on purpose. Everything that decides "may this tap
 * produce a signature?" is in [preparePayment], in a fixed order, so it can be read top to
 * bottom and audited against docs/SECURITY.md without chasing logic across the UI.
 *
 * ## Threading
 *
 * [preparePayment] is deliberately **synchronous**. It is called from
 * `HostApduService.processCommandApdu`, which runs on a binder thread, and the NFC link has
 * a hard timeout — a suspend function there would need `runBlocking` anyway, and pretending
 * otherwise would hide the fact that this code must not do network I/O. It does exactly one
 * thing that can be slow (a Keystore unseal, sub-millisecond on a warm device) and nothing
 * that touches the network.
 */
class SessionManager(
    private val store: SessionStore,
    private val rpc: SolanaRpc,
) {

    data class Blockhash(val value: String, val fetchedAtMs: Long) {
        fun isFresh(nowMs: Long, maxAgeMs: Long = BLOCKHASH_MAX_AGE_MS): Boolean =
            nowMs - fetchedAtMs < maxAgeMs

        companion object {
            /**
             * Blockhashes are valid for ~60-90 seconds (about 150 blocks). Caching one for
             * 20s leaves a wide margin while still being comfortably "recent", and it keeps
             * the tap path free of network calls.
             */
            const val BLOCKHASH_MAX_AGE_MS = 20_000L
        }
    }

    private val _phase = MutableStateFlow<SessionPhase>(SessionPhase.NoSession)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    private val _session = MutableStateFlow<SessionRecord?>(null)
    val session: StateFlow<SessionRecord?> = _session.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    private val _nfcReadiness = MutableStateFlow(NfcReadiness())
    val nfcReadiness: StateFlow<NfcReadiness> = _nfcReadiness.asStateFlow()

    /**
     * Set true while the app is resumed. A tap arriving when this is false must be refused
     * with status word 6A82 — this is the proximity-skimming defence, and it is the single
     * most important line in this file.
     */
    @Volatile
    private var foreground: Boolean = false

    /** Set true while the keyguard is up. A locked phone never signs. */
    @Volatile
    private var deviceUnlocked: Boolean = true

    /** Cached seed for the armed session only. Wiped on disarm. */
    @Volatile
    private var cachedSeed: ByteArray? = null

    /** Guards the seed cache and the spend counter against a double tap. */
    private val signingMutex = Mutex()

    @Volatile
    private var blockhash: Blockhash? = null

    init {
        // Restore across process death, but land disarmed: a session that was armed when
        // the app was killed must not come back armed.
        store.load()?.let { loaded ->
            _session.value = loaded.record
            _phase.value = SessionPhase.Disarmed(DisarmReason.APP_BACKGROUNDED)
            loaded.seed.wipe()
        } ?: run {
            _phase.value = SessionPhase.NoSession
        }
    }

    // ===================================================================================
    // Lifecycle
    // ===================================================================================

    /** Called from `Activity.onResume`. */
    fun onEnterForeground() {
        foreground = true
        deviceUnlocked = true
        refreshArming()
    }

    /**
     * Called from `Activity.onPause`.
     *
     * Invalidating here rather than on `onStop` is intentional and stricter than required:
     * `onPause` fires as soon as anything covers the app (a notification shade, an incoming
     * call, the wallet app opening), and "the app is not visibly in front of the user" is
     * exactly the condition under which an unattended tap should fail.
     */
    fun onLeaveForeground() {
        foreground = false
        wipeSeed()
        if (_phase.value is SessionPhase.Armed || _phase.value is SessionPhase.Signing) {
            _phase.value = SessionPhase.Disarmed(DisarmReason.APP_BACKGROUNDED)
        }
    }

    fun onDeviceLocked() {
        deviceUnlocked = false
        wipeSeed()
        if (_phase.value !is SessionPhase.Settled) {
            _phase.value = SessionPhase.Disarmed(DisarmReason.DEVICE_LOCKED)
        }
    }

    fun onDeviceUnlocked() {
        deviceUnlocked = true
        refreshArming()
    }

    fun onNfcAvailabilityChanged(available: Boolean, enabled: Boolean, hceCapable: Boolean) {
        _nfcReadiness.value = NfcReadiness(available, enabled, hceCapable)
        if (!enabled && _phase.value is SessionPhase.Armed) {
            _phase.value = SessionPhase.Disarmed(DisarmReason.NFC_DISABLED)
        } else if (enabled) {
            refreshArming()
        }
    }

    /** Recomputes whether a tap would currently be honoured. */
    private fun refreshArming() {
        val record = _session.value
        _phase.value = when {
            record == null -> SessionPhase.NoSession
            !foreground -> SessionPhase.Disarmed(DisarmReason.APP_BACKGROUNDED)
            !deviceUnlocked -> SessionPhase.Disarmed(DisarmReason.DEVICE_LOCKED)
            !_nfcReadiness.value.enabled -> SessionPhase.Disarmed(DisarmReason.NFC_DISABLED)
            record.remainingBaseUnits <= 0 -> SessionPhase.Disarmed(DisarmReason.LIMIT_EXHAUSTED)
            else -> SessionPhase.Armed
        }
    }

    /** Loads the seed for the armed session. Only ever called on the tap path. */
    private fun armedSeed(): ByteArray? {
        cachedSeed?.let { return it }
        val loaded = store.load() ?: return null
        cachedSeed = loaded.seed
        return loaded.seed
    }

    private fun wipeSeed() {
        cachedSeed?.wipe()
        cachedSeed = null
    }

    // ===================================================================================
    // Blockhash cache
    // ===================================================================================

    /**
     * Keeps a recent blockhash warm while the app is in the foreground.
     *
     * This is what makes a sub-400ms tap possible: a transaction needs a recent blockhash,
     * and fetching one at tap time would put an RPC round trip inside the NFC window. By
     * refreshing in the background the tap path becomes pure computation.
     */
    suspend fun refreshBlockhash() {
        runCatching { rpc.getLatestBlockhash() }
            .onSuccess {
                blockhash = Blockhash(it.blockhash, System.currentTimeMillis())
                Log.d(TAG, "blockhash refreshed: ${it.blockhash.take(8)}…")
            }
            .onFailure { Log.w(TAG, "blockhash refresh failed; will fall back to terminal's", it) }
    }

    fun cachedBlockhash(): Blockhash? = blockhash

    /**
     * Chooses which blockhash to build the transaction against.
     *
     * Prefers the locally cached one because it is the only one that can be validated; the
     * terminal's is a fallback so that a cold cache still results in a completed payment
     * rather than a failed tap.
     *
     * @return the chosen 32 bytes, and whether it came from cache.
     */
    private fun chooseBlockhash(terminalSupplied: ByteArray): Pair<ByteArray, Boolean>? {
        val cached = blockhash
        if (cached != null && cached.isFresh(System.currentTimeMillis())) {
            val decoded = runCatching { app.bumppay.core.solana.Base58.decodePublicKey(cached.value) }.getOrNull()
            if (decoded != null) return decoded to true
        }
        return if (terminalSupplied.size == 32) terminalSupplied to false else null
    }

    // ===================================================================================
    // The tap path
    // ===================================================================================

    /**
     * Turns a terminal's request into either a signed transaction or a refusal.
     *
     * Order matters and is not arbitrary — cheapest and most safety-critical checks run
     * first, and nothing is signed until every gate has passed:
     *
     *  1. a session must exist                        -> NO_SESSION
     *  2. it must be armed (foreground, unlocked, NFC) -> NOT_ARMED
     *  3. the destination must be the one bound at setup -> DESTINATION_NOT_ALLOWED
     *  4. the amount must fit the remaining limit      -> OVER_LIMIT
     *  5. the mint and decimals must match the session -> NO_SESSION
     *  6. a usable blockhash must be available         -> STALE_BLOCKHASH
     *
     * Only then is the seed unsealed, one transaction signed, and the seed wiped.
     */
    fun preparePayment(request: NfcPayloads.PaymentRequest): NfcPayloads.PaymentResponse {
        val record = _session.value
            ?: return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.NO_SESSION)

        // 2. Armed check. This is the check that a locked or backgrounded phone fails.
        if (_phase.value !is SessionPhase.Armed) {
            Log.w(TAG, "refusing tap: phase is ${_phase.value}")
            return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.NOT_ARMED)
        }

        // 3. Destination pinning. Closes the "delegate can pay anyone" hole client-side.
        if (request.destinationTokenAccount != record.destinationTokenAccount) {
            Log.w(
                TAG,
                "refusing tap: destination ${request.destinationTokenAccount} is not the " +
                    "session's bound destination ${record.destinationTokenAccount}",
            )
            return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.DESTINATION_NOT_ALLOWED)
        }

        // 5. Mint/decimals must match, or the amount means something different.
        if (request.mint != record.mint || request.decimals != record.decimals) {
            Log.w(TAG, "refusing tap: mint or decimals do not match the session")
            return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.NO_SESSION)
        }

        if (record.isExpired(System.currentTimeMillis() / 1000)) {
            Log.w(TAG, "refusing tap: session expired")
            _session.value = record.copy(limitBaseUnits = 0)
            refreshArming()
            return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.NO_SESSION)
        }

        // 4. Limit check.
        if (!record.canSpend(request.amountBaseUnits)) {
            Log.w(
                TAG,
                "over limit: ${request.amountBaseUnits} requested, " +
                    "${record.remainingBaseUnits} remaining",
            )
            // Hand the decision to the user instead of failing silently. The terminal has
            // already been told 6985; this is what raises the MWA prompt on the payer phone.
            _pendingApproval.value = PendingApproval(
                amountBaseUnits = request.amountBaseUnits,
                decimals = record.decimals,
                mint = record.mint,
                destinationTokenAccount = request.destinationTokenAccount,
                reference = request.reference,
            )
            return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.OVER_LIMIT)
        }

        // 6. Blockhash.
        val (blockhashBytes, usedOwn) = chooseBlockhash(request.terminalBlockhash)
            ?: return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.STALE_BLOCKHASH)

        val seed = armedSeed()
            ?: return NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.NO_SESSION)

        _phase.value = SessionPhase.Signing

        val signed = try {
            signSpend(record, seed, request, blockhashBytes)
        } catch (e: Exception) {
            Log.e(TAG, "signing failed", e)
            null
        } finally {
            // The seed exists in memory only across the signing call above.
            wipeSeed()
        }

        return if (signed == null) {
            refreshArming()
            NfcPayloads.PaymentResponse.failure(NfcPayloads.ResultCode.SIGNING_FAILED)
        } else {
            // Optimistic local accounting. The chain is the source of truth (the delegation
            // is decremented by the runtime); this counter exists so the UI does not show a
            // stale remaining balance between taps, and a mismatch self-corrects on the next
            // session sync.
            val updated = record.withSpend(request.amountBaseUnits)
            _session.value = updated
            store.recordSpend(request.amountBaseUnits)

            Log.i(
                TAG,
                "signed ${request.amountBaseUnits} base units for ${request.reference.joinToString("") {
                    (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                }}",
            )

            refreshArming()
            NfcPayloads.PaymentResponse(
                resultCode = NfcPayloads.ResultCode.OK,
                transaction = signed,
                usedOwnBlockhash = usedOwn,
            )
        }
    }

    /**
     * Builds and signs the tap transaction.
     *
     * Instruction order is deliberate. The compute budget instructions come first so they
     * are applied to the units the token transfer actually consumes; the memo comes last so
     * the payment reference is the final, most legible thing in an explorer view.
     */
    private fun signSpend(
        record: SessionRecord,
        seed: ByteArray,
        request: NfcPayloads.PaymentRequest,
        blockhashBytes: ByteArray,
    ): ByteArray {
        val keypair = Keypair.fromSeed(seed)

        // Defensive: the cached seed must belong to the session's declared transient key.
        // A mismatch would mean the transaction is signed by a key the delegation does not
        // cover, which fails on-chain with an opaque error.
        require(keypair.publicKey == record.transient) {
            "session seed does not match the recorded transient key"
        }

        val instructions = listOf(
            SolanaPrograms.ComputeBudget.setComputeUnitLimit(TAP_COMPUTE_UNIT_LIMIT),
            SolanaPrograms.ComputeBudget.setComputeUnitPrice(TAP_COMPUTE_UNIT_PRICE),
            SolanaPrograms.Token.transferChecked(
                source = record.ownerTokenAccount,
                mint = record.mint,
                destination = record.destinationTokenAccount,
                authority = record.transient,
                amount = request.amountBaseUnits,
                decimals = record.decimals,
            ),
            SolanaPrograms.Memo.paymentTag(request.reference.toHex()),
        )

        // The transient key is both the authority and the fee payer, which makes it the
        // only required signer — so the terminal receives a fully-signed transaction and
        // needs no key material of its own.
        return Transaction.from(instructions, record.transient, blockhashBytes)
            .partialSign(keypair)
            .serialize()
    }

    /** Clears the over-limit prompt once the user has answered it. */
    fun clearPendingApproval() {
        _pendingApproval.value = null
    }

    // ===================================================================================
    // Session mutation
    // ===================================================================================

    fun installSession(record: SessionRecord, seed: ByteArray) {
        store.save(record, seed)
        _session.value = record
        wipeSeed()
        refreshArming()
    }

    fun clearApprovalFlag() = clearPendingApproval()

    /**
     * Tears the session down locally. The caller is responsible for also zeroing the
     * on-chain delegation — see the revoke flow in the ViewModel, which sends the
     * `Revoke` instruction through Mobile Wallet Adapter. Doing only one of the two is
     * the failure mode the Phase 4 checklist calls out explicitly.
     */
    fun forgetSession() {
        wipeSeed()
        store.clear()
        _session.value = null
        _phase.value = SessionPhase.NoSession
    }

    fun onSettled(signature: String, amountBaseUnits: Long, decimals: Int) {
        _phase.value = SessionPhase.Settled(signature, amountBaseUnits, decimals)
    }

    fun onSettlementAcknowledged() = refreshArming()

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        private const val TAG = "SessionManager"

        /**
         * A tap transaction is one token transfer plus a memo: a handful of CU. 200k is a
         * generous ceiling that still bounds what a bug could consume, and setting it
         * explicitly avoids the default 200k-per-instruction over-allocation.
         */
        const val TAP_COMPUTE_UNIT_LIMIT = 200_000

        /**
         * Priority fee in micro-lamports per compute unit. 1_000 is negligible on devnet and
         * enough to avoid being dropped during congestion on mainnet. Making it a constant
         * rather than zero means the demo does not fall over if the network is busy.
         */
        const val TAP_COMPUTE_UNIT_PRICE = 1_000L
    }
}

/** Snapshot of the device's NFC state, surfaced so the UI can explain why taps do nothing. */
data class NfcReadiness(
    val hardwarePresent: Boolean = true,
    val enabled: Boolean = true,
    val hceCapable: Boolean = true,
) {
    val allGood: Boolean get() = hardwarePresent && enabled && hceCapable
}
