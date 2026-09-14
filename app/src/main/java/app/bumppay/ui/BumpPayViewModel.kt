package app.bumppay.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.bumppay.BumpPayApplication
import app.bumppay.core.solana.Base58
import app.bumppay.core.solana.BumpPayDemo
import app.bumppay.core.solana.Keypair
import app.bumppay.core.solana.PublicKey
import app.bumppay.core.solana.SolanaPrograms
import app.bumppay.core.solana.Transaction
import app.bumppay.mwa.MwaWallet
import app.bumppay.session.SessionManager
import app.bumppay.session.SessionRecord
import app.bumppay.session.wipe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single coordinator between the wallet, the session and the screens.
 *
 * ## The one rule this class enforces
 *
 * Mobile Wallet Adapter is invoked from exactly three places, each of which is a
 * **deliberate, user-present** action: setup, revoke, and over-limit approval. The tap path
 * never reaches the wallet. If a future change makes a tap call into `MwaWallet`, the
 * product has quietly become "a wallet with extra steps" and the demo thesis is gone.
 */
class BumpPayViewModel(
    private val app: BumpPayApplication,
    private val wallet: MwaWallet,
) : ViewModel() {

    val sessionManager: SessionManager = app.sessionManager

    val phase = sessionManager.phase
    val session = sessionManager.session
    val pendingApproval = sessionManager.pendingApproval
    val nfcReadiness = sessionManager.nfcReadiness

    private val _walletAddress = MutableStateFlow<PublicKey?>(null)
    val walletAddress: StateFlow<PublicKey?> = _walletAddress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /** Last settled signature, for the explorer link on the success screen. */
    private val _lastSignature = MutableStateFlow<String?>(null)
    val lastSignature: StateFlow<String?> = _lastSignature.asStateFlow()

    private var blockhashJob: Job? = null

    data class UiMessage(val text: String, val isError: Boolean = false)

    init {
        wallet.setCluster(devnet = !app.isMainnet)
        // Reconnect silently. MWA's authToken makes this prompt-free when the wallet still
        // honours the previous authorization.
        viewModelScope.launch {
            wallet.connect { /* silent on first launch; the Connect screen handles failures */ }
                ?.let { _walletAddress.value = it.address }
        }
    }

    // ===================================================================================
    // App lifecycle -> session arming
    // ===================================================================================

    fun onResume() {
        sessionManager.onEnterForeground()
        startBlockhashRefresh()
    }

    /**
     * `onPause`, not `onStop`.
     *
     * Arming is torn down the moment the app is no longer visibly in front of the user.
     * That is stricter than Android's lifecycle strictly requires and is exactly the
     * intended behaviour: an unattended phone in a pocket must not be tappable.
     */
    fun onPause() {
        sessionManager.onLeaveForeground()
        stopBlockhashRefresh()
    }

    fun onDeviceLocked() = sessionManager.onDeviceLocked()

    fun onDeviceUnlocked() = sessionManager.onDeviceUnlocked()

    fun onNfcStateChanged(available: Boolean, enabled: Boolean, hceCapable: Boolean) =
        sessionManager.onNfcAvailabilityChanged(available, enabled, hceCapable)

    /**
     * Keeps a fresh blockhash in hand so a tap needs no network.
     *
     * Refreshing every 15s against a 20s freshness window means the cache is essentially
     * always valid while armed. The API cost is 4 requests a minute, which is nothing, and
     * it buys a tap path with zero network latency in it.
     */
    private fun startBlockhashRefresh() {
        if (blockhashJob?.isActive == true) return
        blockhashJob = viewModelScope.launch {
            while (isActive) {
                sessionManager.refreshBlockhash()
                delay(SessionManager.Blockhash.BLOCKHASH_MAX_AGE_MS - 5_000L)
            }
        }
    }

    private fun stopBlockhashRefresh() {
        blockhashJob?.cancel()
        blockhashJob = null
    }

    // ===================================================================================
    // Connect
    // ===================================================================================

    fun connectWallet() {
        viewModelScope.launch {
            _busy.value = true
            val connection = wallet.connect { reason ->
                _message.value = UiMessage("Could not connect: $reason", isError = true)
            }
            _busy.value = false
            if (connection != null) _walletAddress.value = connection.address
        }
    }

    // ===================================================================================
    // Session setup — the one deliberate biometric moment
    // ===================================================================================

    /**
     * Creates the capped delegation.
     *
     * The user confirms once, here, and never again per tap. The transaction grants the
     * transient session key the right to move up to [limitBaseUnits] out of the owner's
     * token account, and the session record pins the destination so the app will refuse to
     * sign a payment anywhere else.
     */
    fun setupSession(
        merchantAddressText: String,
        limitBaseUnits: Long,
        alwaysAsk: Boolean,
    ) {
        val owner = _walletAddress.value
        if (owner == null) {
            _message.value = UiMessage("Connect a wallet first", isError = true)
            return
        }

        val merchant = runCatching { PublicKey.fromBase58(merchantAddressText.trim()) }
            .getOrElse {
                _message.value = UiMessage("That merchant address is not valid base58", isError = true)
                return
            }

        viewModelScope.launch {
            _busy.value = true
            try {
                BumpPayDemo.assertSafeForCluster(app.isMainnet)

                val mint = BumpPayDemo.usdcFor(if (app.isMainnet) "mainnet-beta" else "devnet")
                val decimals = BumpPayDemo.USDC_DECIMALS
                val ownerTokenAccount = PublicKey.findAssociatedTokenAddress(owner, mint)
                val destinationTokenAccount = PublicKey.findAssociatedTokenAddress(merchant, mint)

                val blockhash = app.rpc.getLatestBlockhash()

                // Fresh session key. Generated here, sealed immediately, never leaves the
                // device, and is only unsealed inside the tap window.
                val transient = Keypair.generate()
                val sessionId = blockhash.blockhash.take(12)

                val instructions = buildList {
                    add(SolanaPrograms.ComputeBudget.setComputeUnitLimit(200_000))
                    add(SolanaPrograms.ComputeBudget.setComputeUnitPrice(1_000))
                    // "Always ask" is modelled as a zero delegation rather than a separate
                    // code path: the on-chain state is identical, and the app simply never
                    // finds a spendable budget, so every tap routes to the MWA fallback.
                    add(
                        SolanaPrograms.Token.approveChecked(
                            source = ownerTokenAccount,
                            mint = mint,
                            delegate = transient.publicKey,
                            owner = owner,
                            amount = if (alwaysAsk) 0L else limitBaseUnits,
                            decimals = decimals,
                        ),
                    )
                    add(SolanaPrograms.Memo.sessionTag(sessionId))
                }

                val transaction = Transaction.from(
                    instructions = instructions,
                    payer = owner,
                    recentBlockhash = Base58.decodePublicKey(blockhash.blockhash),
                )

                val signature = wallet.signAndSend(transaction) { reason ->
                    _message.value = UiMessage("Wallet refused: $reason", isError = true)
                }

                if (signature == null) return@launch

                sessionManager.installSession(
                    record = SessionRecord(
                        owner = owner,
                        transient = transient.publicKey,
                        mint = mint,
                        ownerTokenAccount = ownerTokenAccount,
                        destinationTokenAccount = destinationTokenAccount,
                        merchant = merchant,
                        limitBaseUnits = if (alwaysAsk) 0L else limitBaseUnits,
                        spentBaseUnits = 0L,
                        decimals = decimals,
                        // No on-chain expiry on the raw-SPL path; the session is bounded by
                        // amount and by being disarmed whenever the app is not in front of
                        // the user. The Anchor program adds real expiry — see docs/SECURITY.md.
                        expiryUnixSeconds = 0L,
                        sessionId = sessionId,
                        lastValidBlockHeight = blockhash.lastValidBlockHeight,
                    ),
                    seed = transient.seed,
                )

                // Keypair copies the seed internally, so the caller's copy is now redundant.
                transient.seed.wipe()

                _lastSignature.value = signature
                _message.value = UiMessage(
                    if (alwaysAsk) "Session ready — every payment will ask first" else "Session ready",
                )
            } catch (e: Exception) {
                Log.e(TAG, "session setup failed", e)
                _message.value = UiMessage("Setup failed: ${e.message}", isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    // ===================================================================================
    // Over-limit approval (Phase 4: never fail silently)
    // ===================================================================================

    /**
     * Signs a single over-limit payment with the owner's wallet.
     *
     * Reached only from the prompt raised when a tap exceeded the session limit. It is a
     * normal, fully-authenticated transaction — the session key is not involved, so this
     * path cannot be abused to bypass the limit.
     */
    fun approveOverLimitPayment() {
        val request = pendingApproval.value ?: return
        val owner = _walletAddress.value ?: return
        val record = session.value ?: return

        viewModelScope.launch {
            _busy.value = true
            try {
                val blockhash = app.rpc.getLatestBlockhash()

                val transaction = Transaction.from(
                    instructions = listOf(
                        SolanaPrograms.ComputeBudget.setComputeUnitLimit(200_000),
                        SolanaPrograms.Token.transferChecked(
                            source = record.ownerTokenAccount,
                            mint = record.mint,
                            destination = request.destinationTokenAccount,
                            authority = owner,
                            amount = request.amountBaseUnits,
                            decimals = record.decimals,
                        ),
                        SolanaPrograms.Memo.paymentTag(request.reference.toHex()),
                    ),
                    payer = owner,
                    recentBlockhash = Base58.decodePublicKey(blockhash.blockhash),
                )

                val signature = wallet.signAndSend(transaction) { reason ->
                    _message.value = UiMessage("Approval failed: $reason", isError = true)
                }

                sessionManager.clearPendingApproval()
                if (signature != null) {
                    _lastSignature.value = signature
                    sessionManager.onSettled(signature, request.amountBaseUnits, record.decimals)
                }
            } catch (e: Exception) {
                _message.value = UiMessage("Approval failed: ${e.message}", isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissOverLimitPrompt() = sessionManager.clearPendingApproval()

    // ===================================================================================
    // Revoke
    // ===================================================================================

    /**
     * Zeroes the delegation **on-chain**, then clears local state.
     *
     * Order matters and is the opposite of what feels natural. Clearing locally first would
     * leave a live delegation behind if the wallet then refused, and a "revoked" session
     * that can still spend is the worst possible state to be in. So: send the revoke, and
     * only forget the session once the chain has accepted it.
     */
    fun revokeSession() {
        val record = session.value ?: return
        val owner = _walletAddress.value ?: record.owner

        viewModelScope.launch {
            _busy.value = true
            try {
                val blockhash = app.rpc.getLatestBlockhash()

                val transaction = Transaction.from(
                    instructions = listOf(
                        SolanaPrograms.Token.revoke(source = record.ownerTokenAccount, owner = owner),
                        SolanaPrograms.Memo.create("bumppay:revoke:${record.sessionId}"),
                    ),
                    payer = owner,
                    recentBlockhash = Base58.decodePublicKey(blockhash.blockhash),
                )

                val signature = wallet.signAndSend(transaction) { reason ->
                    _message.value = UiMessage("Revoke failed: $reason", isError = true)
                }

                if (signature != null) {
                    // Local teardown only after the chain has the revoke.
                    sessionManager.forgetSession()
                    _message.value = UiMessage("Session revoked")
                }
            } catch (e: Exception) {
                _message.value = UiMessage("Revoke failed: ${e.message}", isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    fun acknowledgeSettlement() = sessionManager.onSettlementAcknowledged()

    fun consumeMessage() {
        _message.value = null
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    class Factory(
        private val app: BumpPayApplication,
        private val wallet: MwaWallet,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BumpPayViewModel(app, wallet) as T
    }

    private companion object {
        const val TAG = "BumpPayViewModel"
    }
}
