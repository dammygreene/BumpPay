package app.bumppay.core.solana

/**
 * Shared configuration for the demo, used by both apps so they cannot disagree.
 *
 * ## Security warning — read before changing anything here
 *
 * [MERCHANT_SEED] is a **well-known, publicly-committed private key**. It exists so that a
 * fresh clone can run the two apps against each other without a configuration step, which
 * is worth a great deal during a four-week build and worth a great deal to a judge trying
 * to run the project.
 *
 * It is therefore only ever acceptable on **devnet**. `docs/SECURITY.md` lists this as the
 * single most important thing to change before pointing the demo at mainnet-beta, and the
 * app refuses to use this key when the configured cluster is mainnet — see
 * `BumpPayDemo.assertSafeForCluster`.
 */
object BumpPayDemo {

    /**
     * A deliberately public devnet-only key. Deriving it from a readable string rather than
     * embedding raw bytes means nobody can mistake it for something that was generated and
     * then accidentally committed.
     */
    val MERCHANT_SEED: ByteArray
        get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest("bumppay-demo-merchant-do-not-use-on-mainnet".toByteArray(Charsets.UTF_8))

    fun merchantKeypair(): Keypair = Keypair.fromSeed(MERCHANT_SEED)

    /** The address the payer binds a session to. Printed on the terminal's screen. */
    val MERCHANT_ADDRESS: String
        get() = merchantKeypair().publicKey.toBase58()

    /**
     * Throws if the demo key is about to be used somewhere it would matter. Called during
     * session setup rather than at first use, so the failure is loud and early.
     */
    fun assertSafeForCluster(isMainnet: Boolean) {
        check(!isMainnet) {
            "BumpPay's built-in demo merchant key is public and must never be used on " +
                "mainnet-beta. Configure a real merchant keypair in the terminal app and " +
                "enter its address on the payer's Session Setup screen."
        }
    }

    /** USDC, chosen for having realistic decimals rather than being a wrapped native token. */
    fun usdcFor(cluster: String): PublicKey =
        if (cluster == "mainnet-beta") SolanaPrograms.USDC_MAINNET else SolanaPrograms.USDC_DEVNET

    const val USDC_DECIMALS = 6
}
