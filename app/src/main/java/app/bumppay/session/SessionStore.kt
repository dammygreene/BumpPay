package app.bumppay.session

import android.content.Context
import android.util.Base64
import app.bumppay.core.solana.PublicKey

/**
 * Persistence for the armed session.
 *
 * Backed by `SharedPreferences` rather than DataStore, deliberately: the HCE service needs
 * to read session state **synchronously** on a binder thread inside `processCommandApdu`,
 * and a suspend-only API there would force either a `runBlocking` wrapper or a second
 * in-memory copy that can drift. Preferences reads are synchronous and cheap, and the
 * process is already warm because the app must be in the foreground for the session to be
 * armed at all.
 *
 * Split of responsibility:
 *   - the **seed** is sealed by the Keystore and stored as an opaque blob
 *   - everything else is plain metadata, which is not secret (it is all derivable from
 *     public chain state anyway)
 */
class SessionStore(context: Context, private val secretBox: KeystoreSecretBox = KeystoreSecretBox()) {

    private val preferences =
        context.applicationContext.getSharedPreferences("bumppay.session", Context.MODE_PRIVATE)

    /** Persists [record] and seals [seed]. Replaces any previous session outright. */
    fun save(record: SessionRecord, seed: ByteArray) {
        val sealedSeed = secretBox.seal(seed)
        preferences.edit()
            .putString(KEY_SEED, Base64.encodeToString(sealedSeed, Base64.NO_WRAP))
            .putString(KEY_OWNER, record.owner.toBase58())
            .putString(KEY_TRANSIENT, record.transient.toBase58())
            .putString(KEY_MINT, record.mint.toBase58())
            .putString(KEY_OWNER_ATA, record.ownerTokenAccount.toBase58())
            .putString(KEY_DESTINATION, record.destinationTokenAccount.toBase58())
            .putString(KEY_MERCHANT, record.merchant.toBase58())
            .putLong(KEY_LIMIT, record.limitBaseUnits)
            .putLong(KEY_SPENT, record.spentBaseUnits)
            .putInt(KEY_DECIMALS, record.decimals)
            .putLong(KEY_EXPIRY, record.expiryUnixSeconds)
            .putString(KEY_SESSION_ID, record.sessionId)
            .putLong(KEY_LAST_VALID_BLOCK_HEIGHT, record.lastValidBlockHeight)
            .apply()
    }

    /**
     * @return the stored record and its seed, or null if there is no session, or if the
     *   stored blob can no longer be decrypted (device restore, Keystore key cleared,
     *   app data moved between devices). In that case the session is worthless and is
     *   dropped rather than half-restored.
     */
    fun load(): LoadedSession? {
        val owner = preferences.getString(KEY_OWNER, null) ?: return null
        val sealed = preferences.getString(KEY_SEED, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return null

        val seed = try {
            secretBox.open(sealed)
        } catch (e: Exception) {
            // Cannot recover the seed -> the session cannot sign anything -> discard it.
            clear()
            return null
        }

        return runCatching {
            LoadedSession(
                record = SessionRecord(
                    owner = PublicKey.fromBase58(owner),
                    transient = PublicKey.fromBase58(preferences.getString(KEY_TRANSIENT, null)!!),
                    mint = PublicKey.fromBase58(preferences.getString(KEY_MINT, null)!!),
                    ownerTokenAccount = PublicKey.fromBase58(preferences.getString(KEY_OWNER_ATA, null)!!),
                    destinationTokenAccount = PublicKey.fromBase58(preferences.getString(KEY_DESTINATION, null)!!),
                    merchant = PublicKey.fromBase58(preferences.getString(KEY_MERCHANT, null)!!),
                    limitBaseUnits = preferences.getLong(KEY_LIMIT, 0L),
                    spentBaseUnits = preferences.getLong(KEY_SPENT, 0L),
                    decimals = preferences.getInt(KEY_DECIMALS, 6),
                    expiryUnixSeconds = preferences.getLong(KEY_EXPIRY, 0L),
                    sessionId = preferences.getString(KEY_SESSION_ID, "") ?: "",
                    lastValidBlockHeight = preferences.getLong(KEY_LAST_VALID_BLOCK_HEIGHT, 0L),
                ),
                seed = seed,
            )
        }.getOrElse {
            seed.wipe()
            clear()
            null
        }
    }

    /** Records a spend so the displayed remaining limit stays honest between taps. */
    fun recordSpend(additionalBaseUnits: Long) {
        val current = preferences.getLong(KEY_SPENT, 0L)
        preferences.edit().putLong(KEY_SPENT, current + additionalBaseUnits).apply()
    }

    /**
     * Removes the session metadata and, importantly, destroys the Keystore wrapping key.
     * Dropping the blob alone would leave the seed recoverable from a stale backup.
     */
    fun clear() {
        preferences.edit().clear().apply()
        secretBox.destroyKey()
    }

    fun hasSession(): Boolean = preferences.contains(KEY_OWNER)

    fun securityLevel(): String = secretBox.securityLevel()

    data class LoadedSession(val record: SessionRecord, val seed: ByteArray)

    private companion object {
        const val KEY_SEED = "seed"
        const val KEY_OWNER = "owner"
        const val KEY_TRANSIENT = "transient"
        const val KEY_MINT = "mint"
        const val KEY_OWNER_ATA = "owner_ata"
        const val KEY_DESTINATION = "destination"
        const val KEY_MERCHANT = "merchant"
        const val KEY_LIMIT = "limit"
        const val KEY_SPENT = "spent"
        const val KEY_DECIMALS = "decimals"
        const val KEY_EXPIRY = "expiry"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_LAST_VALID_BLOCK_HEIGHT = "last_valid_block_height"
    }
}
