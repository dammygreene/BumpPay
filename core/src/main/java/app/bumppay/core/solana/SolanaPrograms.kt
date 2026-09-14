package app.bumppay.core.solana

/**
 * Program ids and instruction builders.
 *
 * Instruction payloads are written by hand in little-endian order, matching each program's
 * documented layout. The SPL Token and Compute Budget payloads are pinned against the
 * reference implementation by SolanaWireFormatTest — those two are where a wrong byte
 * order produces a transaction that is *valid* but does something different from what was
 * intended, which is the worst possible failure mode for a payments demo.
 */
object SolanaPrograms {

    val SYSTEM = PublicKey.fromBase58("11111111111111111111111111111111")
    val TOKEN = PublicKey.fromBase58("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022 = PublicKey.fromBase58("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN = PublicKey.fromBase58("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val COMPUTE_BUDGET = PublicKey.fromBase58("ComputeBudget111111111111111111111111111111")
    val MEMO = PublicKey.fromBase58("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")

    /**
     * BumpPay's own program. Must match `declare_id!` in
     * program/programs/bumppay/src/lib.rs — a mismatch shows up only at runtime as
     * "program not found", so it is asserted in one place and referenced everywhere else.
     */
    val BUMPPAY = PublicKey.fromBase58("BumpPay111111111111111111111111111111111111")

    /** USDC on devnet/mainnet. The demo settles in this because it has real decimals (6). */
    val USDC_MAINNET = PublicKey.fromBase58("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    val USDC_DEVNET = PublicKey.fromBase58("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")

    /** Wrapped SOL, the usual fallback when a demo faucet has no USDC. */
    val WRAPPED_SOL = PublicKey.fromBase58("So11111111111111111111111111111111111111112")

    // -----------------------------------------------------------------------------------
    // Compute budget
    // -----------------------------------------------------------------------------------

    object ComputeBudget {

        /** Payload is `tag:u8 || units:u32le`. Tag 2 = SetComputeUnitLimit. */
        fun setComputeUnitLimit(units: Int): Instruction = Instruction(
            programId = COMPUTE_BUDGET,
            data = ByteWriter(5).writeByte(2).writeU32(units.toLong()).toByteArray(),
            accounts = emptyList(),
        )

        /**
         * Payload is `tag:u8 || microLamports:u64le`. Tag 3 = SetComputeUnitPrice.
         *
         * On devnet this is mostly noise; on mainnet it is the difference between a
         * confirmed tap and a dropped transaction, which is why the payer app lets it be
         * configured rather than hardcoding 0.
         */
        fun setComputeUnitPrice(microLamports: Long): Instruction = Instruction(
            programId = COMPUTE_BUDGET,
            data = ByteWriter(9).writeByte(3).writeU64(microLamports).toByteArray(),
            accounts = emptyList(),
        )
    }

    // -----------------------------------------------------------------------------------
    // Memo
    // -----------------------------------------------------------------------------------

    object Memo {

        /**
         * Attaches a UTF-8 string to the transaction so the payment reference is legible
         * in a block explorer — which is worth real points in the demo video.
         *
         * The memo program's rule, per its own source: it "validates a string of UTF-8
         * encoded characters and verifies that any accounts provided are signers of the
         * transaction". So listed accounts must be signers, and listing *none* is valid
         * and vacuous — which is what BumpPay does.
         *
         * Do not "helpfully" add the payer here as a signer if it is not in fact signing
         * this transaction, and do not add non-signer accounts: either one makes the memo
         * program reject the whole transaction at simulation time, and the error points at
         * the memo program rather than at the extra account.
         */
        fun create(utf8Text: String): Instruction = Instruction(
            programId = MEMO,
            data = utf8Text.toByteArray(Charsets.UTF_8),
            accounts = emptyList(),
        )

        /** BumpPay's memo convention: a human-readable, greppable payment tag. */
        fun sessionTag(sessionIdHex: String): Instruction =
            create("bumppay:session:$sessionIdHex")

        fun paymentTag(referenceHex: String): Instruction =
            create("bumppay:tx:$referenceHex")
    }

    // -----------------------------------------------------------------------------------
    // SPL Token
    // -----------------------------------------------------------------------------------

    object Token {

        const val IX_INITIALIZE_ACCOUNT = 1
        const val IX_TRANSFER = 3
        const val IX_APPROVE = 4
        const val IX_REVOKE = 5
        const val IX_TRANSFER_CHECKED = 12
        const val IX_APPROVE_CHECKED = 13

        /**
         * Grants [delegate] the right to move up to [amount] base units out of [source].
         *
         * Payload: `tag:u8 || amount:u64le || decimals:u8`.
         * Accounts: source (writable), mint (read-only), delegate (read-only), owner (signer).
         *
         * `_checked` is used instead of plain `Approve` so the mint's decimals are asserted
         * on-chain: a mismatch fails the transaction rather than moving a wrong-sized amount.
         */
        fun approveChecked(
            source: PublicKey,
            mint: PublicKey,
            delegate: PublicKey,
            owner: PublicKey,
            amount: Long,
            decimals: Int,
        ): Instruction = Instruction(
            programId = TOKEN,
            data = ByteWriter(10)
                .writeByte(IX_APPROVE_CHECKED)
                .writeU64(amount)
                .writeByte(decimals)
                .toByteArray(),
            accounts = listOf(
                AccountMeta.writable(source),
                AccountMeta.readonly(mint),
                AccountMeta.readonly(delegate),
                AccountMeta.readonly(owner, signer = true),
            ),
        )

        /**
         * Moves [amount] base units from [source] to [destination].
         *
         * Payload: `tag:u8 || amount:u64le || decimals:u8`.
         * Accounts: source (writable), mint (read-only), destination (writable), authority (signer).
         *
         * The authority here is the *transient* session key acting as delegate, which is
         * the entire point of the product: the payer's main wallet does not sign this, so
         * no biometric prompt appears at tap time.
         */
        fun transferChecked(
            source: PublicKey,
            mint: PublicKey,
            destination: PublicKey,
            authority: PublicKey,
            amount: Long,
            decimals: Int,
        ): Instruction = Instruction(
            programId = TOKEN,
            data = ByteWriter(10)
                .writeByte(IX_TRANSFER_CHECKED)
                .writeU64(amount)
                .writeByte(decimals)
                .toByteArray(),
            accounts = listOf(
                AccountMeta.writable(source),
                AccountMeta.readonly(mint),
                AccountMeta.writable(destination),
                AccountMeta.readonly(authority, signer = true),
            ),
        )

        /** Payload is a bare `tag:u8` (5). Setting the delegate back to zero. */
        fun revoke(source: PublicKey, owner: PublicKey): Instruction = Instruction(
            programId = TOKEN,
            data = byteArrayOf(IX_REVOKE.toByte()),
            accounts = listOf(
                AccountMeta.writable(source),
                AccountMeta.readonly(owner, signer = true),
            ),
        )

        /** Creates the recipient's token account if it does not exist yet. */
        fun createAssociatedTokenAccountIdempotent(
            payer: PublicKey,
            owner: PublicKey,
            mint: PublicKey,
        ): Instruction = Instruction(
            programId = ASSOCIATED_TOKEN,
            data = byteArrayOf(1), // 1 = Idempotent
            accounts = listOf(
                AccountMeta.writable(payer, signer = true),
                AccountMeta.readonly(PublicKey.findAssociatedTokenAddress(owner, mint)),
                AccountMeta.readonly(owner),
                AccountMeta.readonly(mint),
                AccountMeta.readonly(SYSTEM),
                AccountMeta.readonly(TOKEN),
            ),
        )
    }

    // -----------------------------------------------------------------------------------
    // BumpPay program (Anchor) — the Option B path
    // -----------------------------------------------------------------------------------

    /**
     * Client for BumpPay's own Anchor program.
     *
     * Anchor prepends an 8-byte discriminator to every instruction payload: the first eight
     * bytes of `sha256("global:<instruction_name>")`. Those constants are computed here
     * rather than pasted from a generated client so that the hand-rolled path has no
     * dependency on the Anchor TypeScript toolchain. If an instruction is ever renamed on
     * the Rust side, these four constants must change together — see the comment in
     * program/programs/bumppay/src/lib.rs.
     */
    object BumpPay {

        /** sha256("global:init_session")[0..8] */
        val IX_INIT_SESSION = byteArrayOf(
            0x79.toByte(), 0xCE.toByte(), 0x50, 0x6A, 0xE7.toByte(), 0xC2.toByte(), 0xE1.toByte(), 0xF8.toByte(),
        )

        /** sha256("global:spend_via_session")[0..8] */
        val IX_SPEND_VIA_SESSION = byteArrayOf(
            0x40, 0x11, 0x54, 0x71, 0x37, 0x40, 0xB4.toByte(), 0xC9.toByte(),
        )

        /** sha256("global:revoke_session")[0..8] */
        val IX_REVOKE_SESSION = byteArrayOf(
            0x56, 0x5C, 0xC6.toByte(), 0x78, 0x90.toByte(), 0x02, 0x07, 0xC2.toByte(),
        )

        /**
         * `init_session(expiry: i64, limit: u64)`
         *
         * Creates the session PDA `[b"session", owner, transient]` and CPIs into
         * `approve_checked` so the on-chain delegation matches what the PDA records.
         */
        fun initSession(
            owner: PublicKey,
            transient: PublicKey,
            mint: PublicKey,
            ownerTokenAccount: PublicKey,
            expiryUnixSeconds: Long,
            limitBaseUnits: Long,
        ): Instruction {
            val (sessionPda, _) = sessionPda(owner, transient)

            return Instruction(
                programId = BUMPPAY,
                data = ByteWriter(24)
                    .write(IX_INIT_SESSION)
                    .writeU64(expiryUnixSeconds)
                    .writeU64(limitBaseUnits)
                    .toByteArray(),
                accounts = listOf(
                    AccountMeta.writable(owner, signer = true),
                    AccountMeta.readonly(transient),
                    AccountMeta.readonly(mint),
                    AccountMeta.writable(ownerTokenAccount),
                    AccountMeta.writable(sessionPda),
                    AccountMeta.readonly(TOKEN),
                    AccountMeta.readonly(SYSTEM),
                ),
            )
        }

        /**
         * `spend_via_session(amount: u64)`
         *
         * The on-chain check that raw SPL delegation cannot express: expiry, remaining
         * budget, and a destination locked to this session's merchant.
         */
        fun spendViaSession(
            owner: PublicKey,
            transient: PublicKey,
            mint: PublicKey,
            ownerTokenAccount: PublicKey,
            destinationTokenAccount: PublicKey,
            merchant: PublicKey,
            amountBaseUnits: Long,
        ): Instruction {
            val (sessionPda, _) = sessionPda(owner, transient)

            return Instruction(
                programId = BUMPPAY,
                data = ByteWriter(16)
                    .write(IX_SPEND_VIA_SESSION)
                    .writeU64(amountBaseUnits)
                    .toByteArray(),
                accounts = listOf(
                    AccountMeta.readonly(transient, signer = true),
                    AccountMeta.readonly(mint),
                    AccountMeta.writable(ownerTokenAccount),
                    AccountMeta.writable(destinationTokenAccount),
                    AccountMeta.writable(sessionPda),
                    AccountMeta.readonly(merchant),
                    AccountMeta.readonly(TOKEN),
                ),
            )
        }

        /** `revoke_session()` — CPIs `revoke` and returns the PDA's rent to the owner. */
        fun revokeSession(
            owner: PublicKey,
            transient: PublicKey,
            ownerTokenAccount: PublicKey,
        ): Instruction {
            val (sessionPda, _) = sessionPda(owner, transient)

            return Instruction(
                programId = BUMPPAY,
                data = IX_REVOKE_SESSION,
                accounts = listOf(
                    AccountMeta.writable(owner, signer = true),
                    AccountMeta.writable(ownerTokenAccount),
                    AccountMeta.writable(sessionPda),
                    AccountMeta.readonly(TOKEN),
                ),
            )
        }

        /** `seeds = [b"session", owner, transient]` — the PDA both sides must agree on. */
        fun sessionPda(owner: PublicKey, transient: PublicKey): Pair<PublicKey, Int> =
            PublicKey.findProgramAddress(
                seeds = listOf(SESSION_SEED, owner.bytes, transient.bytes),
                programId = BUMPPAY,
            )

        val SESSION_SEED = "session".toByteArray(Charsets.US_ASCII)
    }
}
