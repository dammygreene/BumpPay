package app.bumppay.mwa

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import app.bumppay.core.solana.PublicKey
import app.bumppay.core.solana.Transaction
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult

/**
 * Mobile Wallet Adapter wrapper: the only place BumpPay touches the user's real wallet.
 *
 * MWA is used for exactly three operations, and never during a tap:
 *
 *  1. **Connect** — establish the session and learn the owner's address.
 *  2. **Session setup** — one biometric confirmation that creates the capped delegation.
 *  3. **Revoke / over-limit** — deliberate, user-present actions.
 *
 * The tap path deliberately does not use MWA at all. That is the entire point of BumpPay:
 * MWA gives you a signed transaction in exchange for a user gesture, and the product exists
 * because doing that once is acceptable and doing it per tap is not.
 *
 * ## Activity-result plumbing
 *
 * `ActivityResultSender` registers a real `registerForActivityResult` launcher against the
 * host activity, and MWA's own implementation asserts that only one request is in flight at
 * a time. It is therefore created once per activity and reused, rather than per call — the
 * alternative is a crash the second time the user taps "Connect Wallet" quickly.
 */
class MwaWallet(activity: ComponentActivity) {

    private val sender = ActivityResultSender(activity)

    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            // Must be absolute and hierarchical, or MWA rejects the authorize request.
            identityUri = Uri.parse("https://bumppay.app"),
            // Must be *relative* — it is resolved against identityUri by the wallet. A full
            // URL here throws IllegalArgumentException from inside the client library.
            iconUri = Uri.parse("favicon.ico"),
            identityName = "BumpPay",
        ),
    )

    var connection: WalletConnection? = null
        private set

    data class WalletConnection(
        val address: PublicKey,
        /** Opaque reauthorisation token. Persisting it skips the approval prompt next launch. */
        val authToken: String?,
    )

    /** Points the adapter at a cluster. Changing it clears the auth token, by MWA's design. */
    fun setCluster(devnet: Boolean) {
        adapter.blockchain = if (devnet) Solana.Devnet else Solana.Mainnet
    }

    /**
     * Establishes (or re-establishes) the wallet connection.
     *
     * @return the connection, or null with [onFailure] invoked describing why. Never throws:
     *   "no wallet installed" is an ordinary outcome on a fresh device, not an exception.
     */
    suspend fun connect(onFailure: (String) -> Unit): WalletConnection? {
        val result = adapter.transact(sender) { authResult ->
            val account = authResult.accounts.firstOrNull()
                ?: error("the wallet authorized but returned no accounts")

            WalletConnection(
                address = PublicKey.fromBytes(account.publicKey),
                authToken = authResult.authToken,
            )
        }

        return when (result) {
            is TransactionResult.Success -> result.payload.also { connection = it }
            is TransactionResult.Failure -> {
                Log.w(TAG, "connect failed: ${result.message}", result.e)
                onFailure(result.message)
                null
            }
            is TransactionResult.NoWalletFound -> {
                Log.w(TAG, "no MWA wallet on device")
                onFailure("no compatible wallet found")
                null
            }
        }
    }

    /**
     * Hands [transaction] to the wallet to sign **and broadcast**, returning its signature.
     *
     * `sign_and_send_transactions` rather than `sign_transactions` because the wallet already
     * holds a working RPC connection and sending from there avoids BumpPay needing a second
     * round trip. It also means the signature the wallet reports is the one the network saw.
     *
     * The transaction is serialized with zero-filled signature slots
     * ([Transaction.serializeForSigning]); that is the form a wallet expects, and it is how
     * the wallet knows how many signatures to produce.
     */
    suspend fun signAndSend(
        transaction: Transaction,
        onFailure: (String) -> Unit,
    ): String? {
        val unsigned = transaction.serializeForSigning()

        val result = adapter.transact(sender) { operations ->
            operations.signAndSendTransactions(arrayOf(unsigned))
        }

        return when (result) {
            is TransactionResult.Success -> {
                val signature = result.payload.signatures.firstOrNull()
                if (signature == null) {
                    onFailure("wallet returned no signature")
                    null
                } else {
                    app.bumppay.core.solana.Base58.encode(signature)
                }
            }
            is TransactionResult.Failure -> {
                Log.w(TAG, "signAndSend failed: ${result.message}", result.e)
                onFailure(result.message)
                null
            }
            is TransactionResult.NoWalletFound -> {
                onFailure("no compatible wallet found")
                null
            }
        }
    }

    /** Drops the authorisation. Does not touch the on-chain delegation. */
    suspend fun disconnect() {
        runCatching { adapter.disconnect(sender) }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
        connection = null
    }

    private companion object {
        const val TAG = "MwaWallet"
    }
}
