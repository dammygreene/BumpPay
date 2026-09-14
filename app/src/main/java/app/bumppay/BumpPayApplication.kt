package app.bumppay

import android.app.Application
import app.bumppay.core.solana.SolanaRpc
import app.bumppay.session.SessionManager
import app.bumppay.session.SessionStore

/**
 * Manual dependency container.
 *
 * Deliberately not Hilt. The app has three collaborators; a DI framework would add an
 * annotation processor, a Gradle plugin and a build-time failure mode in exchange for
 * nothing on a four-week timeline. If the graph grows past roughly six objects this should
 * be revisited — but the honest reason it is written this way is that the tap path needs a
 * *stable, inspectable* set of singletons, and `Application` gives exactly that.
 *
 * The session manager is shared with `BumpPayApduService`, which matters: the NFC service
 * and the UI must observe the *same* armed state, and they are in the same process by
 * construction (the service is not declared with `android:process`).
 */
class BumpPayApplication : Application() {

    private val cluster: String get() = BuildConfig.BUMPPAY_CLUSTER

    /** Helius when a key is configured, public devnet as a fallback. */
    fun rpcEndpoint(): String {
        val key = BuildConfig.HELIUS_API_KEY
        return if (key.isNotBlank()) {
            SolanaRpc.heliusEndpoint(key, cluster)
        } else {
            when (cluster) {
                "mainnet-beta" -> "https://api.mainnet-beta.solana.com"
                "testnet" -> "https://api.testnet.solana.com"
                else -> SolanaRpc.DEVNET_PUBLIC
            }
        }
    }

    val isMainnet: Boolean get() = cluster == "mainnet-beta"

    val rpc: SolanaRpc by lazy { SolanaRpc(rpcEndpoint()) }

    val sessionStore: SessionStore by lazy { SessionStore(this) }

    val sessionManager: SessionManager by lazy { SessionManager(sessionStore, rpc) }

    override fun onCreate() {
        super.onCreate()
        // Touch the manager so a restored session is loaded before the first tap can
        // possibly arrive, rather than lazily on the binder thread.
        sessionManager
    }
}
