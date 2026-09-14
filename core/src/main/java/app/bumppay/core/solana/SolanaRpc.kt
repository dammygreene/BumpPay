package app.bumppay.core.solana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal Solana JSON-RPC client covering exactly the calls BumpPay makes.
 *
 * Deliberately hand-rolled (see docs/PHASE0-STACK-DECISION.md). BumpPay needs eight RPC
 * methods, none of them exotic; a hundred-line client has a smaller blast radius than a
 * general-purpose SDK, and it keeps the serialization logic in this package as the single
 * source of truth for what goes on the wire.
 *
 * All calls are `suspend` and hop to [Dispatchers.IO]. OkHttp itself is blocking, so this
 * is the honest place to put the dispatcher swap rather than pretending otherwise.
 */
class SolanaRpc(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
) {

    private val requestIds = AtomicLong(1)

    data class Blockhash(val blockhash: String, val lastValidBlockHeight: Long)

    data class SignatureStatus(
        val slot: Long,
        val confirmations: Long?,
        val confirmationStatus: String?,
        val error: Any?,
    ) {
        val isConfirmed: Boolean
            get() = error == null && (confirmationStatus == "confirmed" || confirmationStatus == "finalized")
        val isFailed: Boolean get() = error != null
    }

    sealed interface Confirmation {
        data class Confirmed(val slot: Long, val commitment: String) : Confirmation
        data class Failed(val reason: String) : Confirmation
        data class TimedOut(val signature: String) : Confirmation
    }

    suspend fun getLatestBlockhash(commitment: String = "confirmed"): Blockhash {
        val result = call("getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", commitment)))
        val value = result.getJSONObject("value")
        return Blockhash(
            blockhash = value.getString("blockhash"),
            lastValidBlockHeight = value.getLong("lastValidBlockHeight"),
        )
    }

    suspend fun getBalance(publicKey: PublicKey, commitment: String = "confirmed"): Long {
        val params = JSONArray()
            .put(publicKey.toBase58())
            .put(JSONObject().put("commitment", commitment))
        return call("getBalance", params).getLong("value")
    }

    suspend fun getMinimumBalanceForRentExemption(spaceBytes: Long): Long =
        call("getMinimumBalanceForRentExemption", JSONArray().put(spaceBytes)).getLong("value")

    /** Returns null when the account does not exist (which is not an error, just an absence). */
    suspend fun getTokenAccountBalance(account: PublicKey, commitment: String = "confirmed"): Long? {
        val params = JSONArray()
            .put(account.toBase58())
            .put(JSONObject().put("commitment", commitment))
        return try {
            call("getTokenAccountBalance", params).getJSONObject("value").getLong("amount")
        } catch (e: SolanaRpcException) {
            if (e.isAccountNotFound()) null else throw e
        }
    }

    /**
     * Broadcasts an already-signed transaction and returns its signature.
     *
     * `skipPreflight = false` by default: preflight simulation is what turns "the tap
     * silently produced a broken transaction" into a readable error message, which matters
     * a great deal on stage. Turn it off only when chasing latency.
     */
    suspend fun sendTransaction(
        serializedTransaction: ByteArray,
        commitment: String = "confirmed",
        skipPreflight: Boolean = false,
    ): String {
        val encoded = android.util.Base64.encodeToString(serializedTransaction, android.util.Base64.NO_WRAP)
        val options = JSONObject()
            .put("encoding", "base64")
            .put("skipPreflight", skipPreflight)
            .put("preflightCommitment", commitment)
            .put("maxRetries", 3)

        return call("sendTransaction", JSONArray().put(encoded).put(options)).let { json ->
            // sendTransaction returns the signature as a bare JSON string.
            if (json is String) json else json.toString()
        }
    }

    suspend fun getSignatureStatuses(signatures: List<String>): List<SignatureStatus?> {
        val params = JSONArray().put(JSONArray(signatures))
        val value = call("getSignatureStatuses", params).getJSONArray("value")

        return (0 until value.length()).map { index ->
            if (value.isNull(index)) return@map null
            val entry = value.getJSONObject(index)
            SignatureStatus(
                slot = entry.optLong("slot", 0L),
                confirmations = if (entry.isNull("confirmations")) null else entry.optLong("confirmations"),
                confirmationStatus = if (entry.isNull("confirmationStatus")) null else entry.optString("confirmationStatus"),
                error = if (entry.isNull("err")) null else entry.get("err"),
            )
        }
    }

    /**
     * Polls until the transaction is confirmed, failed, or [timeoutMs] elapses.
     *
     * Polling `getSignatureStatuses` rather than calling the deprecated `confirmTransaction`
     * websocket-style API: one fewer subscription to leak, and it behaves predictably on
     * metered mobile connections.
     */
    suspend fun awaitConfirmation(
        signature: String,
        timeoutMs: Long = 30_000,
        pollIntervalMs: Long = 1_000,
    ): Confirmation {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = getSignatureStatuses(listOf(signature)).firstOrNull()
            when {
                status == null -> Unit // not seen yet
                status.isFailed -> return Confirmation.Failed(status.error?.toString() ?: "unknown error")
                status.isConfirmed -> return Confirmation.Confirmed(
                    slot = status.slot,
                    commitment = status.confirmationStatus ?: "confirmed",
                )
            }
            delay(pollIntervalMs)
        }
        return Confirmation.TimedOut(signature)
    }

    /** Fetches a token account's mint/owner/amount in one round trip. */
    suspend fun getAccountInfo(account: PublicKey, commitment: String = "confirmed"): JSONObject? {
        val params = JSONArray()
            .put(account.toBase58())
            .put(JSONObject().put("encoding", "base64").put("commitment", commitment))
        val result = call("getAccountInfo", params)
        if (result.isNull("value")) return null
        return result.getJSONObject("value")
    }

    // -----------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------

    private suspend fun call(method: String, params: JSONArray): Any = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestIds.getAndIncrement())
            .put("method", method)
            .put("params", params)

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful && text.isEmpty()) {
                    throw SolanaRpcException(
                        "HTTP ${response.code} from RPC endpoint with an empty body",
                        code = response.code,
                    )
                }
                text
            }
        } catch (e: IOException) {
            throw SolanaRpcException("network failure calling $method: ${e.message}", cause = e)
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw SolanaRpcException("RPC returned non-JSON for $method: ${body.take(200)}", cause = e)
        }

        // JSON-RPC errors are reported in-band, with HTTP 200.
        if (json.has("error") && !json.isNull("error")) {
            val error = json.getJSONObject("error")
            throw SolanaRpcException(
                message = error.optString("message", "unknown RPC error"),
                code = error.optInt("code", 0),
                data = error.opt("data")?.toString(),
            )
        }

        json.get("result")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Timeouts tuned for a mobile connection during a live tap. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Public devnet, for when no Helius key is configured. Rate-limited; fine for Phase 1. */
        const val DEVNET_PUBLIC = "https://api.devnet.solana.com"

        fun heliusEndpoint(apiKey: String, cluster: String): String =
            "https://${if (cluster == "mainnet-beta") "mainnet" else cluster}.helius-rpc.com/?api-key=$apiKey"
    }
}

/** A JSON-RPC level failure (as opposed to a transport failure). */
class SolanaRpcException(
    override val message: String,
    val code: Int = 0,
    val data: String? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * Solana reports a missing account as a specific negative error code rather than a
     * null result, so "the merchant has no token account yet" would otherwise look like a
     * hard failure.
     */
    fun isAccountNotFound(): Boolean =
        code == -32602 || message.contains("could not find account", ignoreCase = true) ||
            message.contains("Invalid param", ignoreCase = true)
}
