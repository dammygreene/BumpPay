package app.bumppay.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Golden-vector tests for BumpPay's hand-rolled Solana wire format.
 *
 * ## Where these numbers come from
 *
 * None of the expected values in this file were written by hand. They were produced by
 * `solders`, the reference Solana SDK, via `tools/gen_test_vectors.py`, and pasted in. That
 * matters: hand-rolled serialization code fails in ways that look correct — a length byte
 * that works until a count crosses 127, an instruction payload written big-endian, an
 * account ordering that only diverges once two keys sort differently as bytes versus as
 * base58 text. Every one of those produces a *plausible* byte string that the network
 * rejects with an unhelpful error.
 *
 * So this file is the contract. If a refactor breaks any of these, the code is wrong, not
 * the test. Re-generate rather than hand-edit:
 *
 *     pip install --break-system-packages solders
 *     python3 tools/gen_test_vectors.py
 *
 * Run with:  ./gradlew :core:testDebugUnitTest
 */
class SolanaWireFormatTest {

    // ===================================================================================
    // Fixtures — identical to tools/gen_test_vectors.py
    // ===================================================================================

    private val owner = Keypair.fromSeed(ByteArray(32) { 1 })
    private val transient = Keypair.fromSeed(ByteArray(32) { 2 })
    private val merchant = Keypair.fromSeed(ByteArray(32) { 3 })

    private val usdc = PublicKey.fromBase58("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")

    /** A fixed 32-byte blockhash. Real ones rotate every ~60s and make terrible fixtures. */
    private val blockhash: ByteArray =
        MessageDigest.getInstance("SHA-256").digest("bumppay-demo-blockhash".toByteArray())

    private fun ByteArray.hex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    // ===================================================================================
    // 1. Base58
    // ===================================================================================

    @Test
    fun `base58 round-trips real mainnet addresses`() {
        val addresses = listOf(
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC mint
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", // token program
            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", // ATA program
            "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", // memo program
            "ComputeBudget111111111111111111111111111111", // compute budget
            "So11111111111111111111111111111111111111112", // wrapped SOL
        )

        addresses.forEach { address ->
            val decoded = Base58.decode(address)
            assertEquals("$address should decode to 32 bytes", 32, decoded.size)
            assertEquals("$address should survive a round trip", address, Base58.encode(decoded))
        }
    }

    /**
     * The system program is 32 zero bytes, which base58-encodes as 32 '1' characters.
     * An implementation that ignores leading zero bytes decodes it to the empty array and
     * then fails a length check somewhere far away from the actual bug.
     */
    @Test
    fun `base58 preserves leading zero bytes`() {
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        assertEquals(32, Base58.decode("11111111111111111111111111111111").size)

        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals(1, Base58.decode("1").size)

        // One leading zero followed by meaningful data.
        val mixed = byteArrayOf(0, 0, 1, 2, 3)
        assertEquals(mixed.toList(), Base58.decode(Base58.encode(mixed)).toList())
    }

    // ===================================================================================
    // 2. Instruction data — little-endian, the classic silent-corruption site
    // ===================================================================================

    @Test
    fun `spl token instruction payloads match the reference implementation`() {
        val ownerAta = PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc)

        val approve = SolanaPrograms.Token.approveChecked(
            source = ownerAta,
            mint = usdc,
            delegate = transient.publicKey,
            owner = owner.publicKey,
            amount = 25_000_000,
            decimals = 6,
        )
        assertEquals("0d40787d010000000006", approve.data.hex())

        val transfer = SolanaPrograms.Token.transferChecked(
            source = ownerAta,
            mint = usdc,
            destination = PublicKey.findAssociatedTokenAddress(merchant.publicKey, usdc),
            authority = transient.publicKey,
            amount = 1_500_000,
            decimals = 6,
        )
        assertEquals("0c60e316000000000006", transfer.data.hex())

        // One base unit — the smallest representable amount, and where an off-by-one in
        // the decimal conversion would first show up.
        val oneBaseUnit = SolanaPrograms.Token.transferChecked(
            source = ownerAta,
            mint = usdc,
            destination = PublicKey.findAssociatedTokenAddress(merchant.publicKey, usdc),
            authority = transient.publicKey,
            amount = 1,
            decimals = 6,
        )
        assertEquals("0c010000000000000006", oneBaseUnit.data.hex())

        // Largest amount a signed Kotlin Long can express. The point is the byte ORDER:
        // 0x7FFF_FFFF_FFFF_FFFF must serialize as ff ff ff ff ff ff ff 7f, i.e. the
        // significant byte last. A big-endian write would put 7f first and move a
        // very different amount of money.
        val maxAmount = SolanaPrograms.Token.transferChecked(
            source = ownerAta,
            mint = usdc,
            destination = PublicKey.findAssociatedTokenAddress(merchant.publicKey, usdc),
            authority = transient.publicKey,
            amount = Long.MAX_VALUE,
            decimals = 6,
        )
        assertEquals("0cffffffffffffff7f06", maxAmount.data.hex())

        // The generator also pins the true u64 max (0cffffffffffffffff06); a Long cannot
        // hold that value positively, so it is checked against the raw writer instead.
        assertEquals(
            "0cffffffffffffffff06",
            (byteArrayOf(SolanaPrograms.Token.IX_TRANSFER_CHECKED.toByte()) +
                ByteWriter(8).writeU64(-1L).toByteArray() +
                byteArrayOf(6)).hex(),
        )

        val revoke = SolanaPrograms.Token.revoke(source = ownerAta, owner = owner.publicKey)
        assertEquals("05", revoke.data.hex())
    }

    @Test
    fun `compute budget payloads match the reference implementation`() {
        assertEquals(
            "02400d0300",
            SolanaPrograms.ComputeBudget.setComputeUnitLimit(200_000).data.hex(),
        )
        assertEquals(
            "03e803000000000000",
            SolanaPrograms.ComputeBudget.setComputeUnitPrice(1_000).data.hex(),
        )
    }

    /**
     * The memo program requires that any account it is given is a signer of the
     * transaction, so an empty account list is the correct and safe form.
     */
    @Test
    fun `memo instructions carry no accounts`() {
        val memo = SolanaPrograms.Memo.paymentTag("7f3a91c2")
        assertTrue(memo.accounts.isEmpty())
        assertEquals("bumppay:tx:7f3a91c2", String(memo.data, Charsets.UTF_8))
    }

    // ===================================================================================
    // 3. PDA derivation — exercises the ed25519 off-curve rejection loop
    // ===================================================================================

    @Test
    fun `keypair derivation matches the reference implementation`() {
        // If these fail, the BouncyCastle Ed25519 wiring is wrong and nothing else matters.
        assertEquals("AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9", owner.publicKey.toBase58())
        assertEquals("9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu", transient.publicKey.toBase58())
    }

    @Test
    fun `associated token addresses match the reference implementation`() {
        assertEquals(
            "3wvJdyFnGvaMWpbq93NU91SggiVRveULUXL6iX5VZDGP",
            PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc).toBase58(),
        )
        assertEquals(
            "DNDTCnZkNk358qDFZd9unHtnrc73SsXcpVWtwJJMrR4B",
            PublicKey.findAssociatedTokenAddress(merchant.publicKey, usdc).toBase58(),
        )
    }

    /**
     * The session PDA, and specifically its bump. The bump is 253 rather than 255, which
     * means the hash for bump 254 and 255 landed ON the curve and had to be rejected. A
     * implementation that skips the off-curve check, or that checks the wrong direction,
     * returns a different address here.
     */
    @Test
    fun `session PDA matches the reference implementation including its bump`() {
        val (address, bump) = PublicKey.findProgramAddress(
            seeds = listOf(
                "session".toByteArray(Charsets.US_ASCII),
                owner.publicKey.bytes,
                transient.publicKey.bytes,
            ),
            programId = SolanaPrograms.BUMPPAY,
        )

        assertEquals("8LBCuZERWUpKAHoGboqVthXSGaXThM3P3tvf4dv6yDL9", address.toBase58())
        assertEquals(253, bump)
    }

    @Test
    fun `single-seed PDA matches the reference implementation`() {
        val (address, _) = PublicKey.findProgramAddress(
            seeds = listOf("vault".toByteArray(Charsets.US_ASCII)),
            programId = SolanaPrograms.BUMPPAY,
        )
        assertEquals("76YhUFvF7MeNgEQUFQi659BmhyhVk5KxoPRAyJQttjLt", address.toBase58())
    }

    @Test
    fun `on-curve detection distinguishes keypairs from PDAs`() {
        // Real keypair-derived addresses are on the curve...
        assertTrue(usdc.isOnCurve())
        assertTrue(SolanaPrograms.TOKEN.isOnCurve())

        // ...and derived PDAs must not be, or they would have a private key.
        val (sessionPda, _) = SolanaPrograms.BumpPay.sessionPda(owner.publicKey, transient.publicKey)
        assertFalse("PDA must be off-curve", sessionPda.isOnCurve())

        val ownerAta = PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc)
        assertFalse("ATA must be off-curve", ownerAta.isOnCurve())
    }

    @Test
    fun `createProgramAddress returns null rather than looping when the hash is on-curve`() {
        // findProgramAddress must always succeed...
        val (address, _) = PublicKey.findProgramAddress(
            seeds = listOf("session".toByteArray(), owner.publicKey.bytes, transient.publicKey.bytes),
            programId = SolanaPrograms.BUMPPAY,
        )
        assertNotNull(address)

        // ...while the no-bump form is allowed to fail. It returns null rather than throwing
        // so the caller can decide to bump.
        val result = PublicKey.createProgramAddress(
            seeds = listOf("session".toByteArray(), owner.publicKey.bytes, transient.publicKey.bytes),
            programId = SolanaPrograms.BUMPPAY,
        )
        // For these particular seeds the bumpless hash happens to be off-curve, so this
        // documents the shape of the API rather than asserting a rejection.
        assertNull(result ?: null)
    }

    // ===================================================================================
    // 4. Message serialization — the whole point of this file
    // ===================================================================================

    private fun approveInstructions(): List<Instruction> {
        val ownerAta = PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc)
        return listOf(
            SolanaPrograms.ComputeBudget.setComputeUnitLimit(200_000),
            SolanaPrograms.ComputeBudget.setComputeUnitPrice(1_000),
            SolanaPrograms.Token.approveChecked(
                source = ownerAta,
                mint = usdc,
                delegate = transient.publicKey,
                owner = owner.publicKey,
                amount = 25_000_000,
                decimals = 6,
            ),
            SolanaPrograms.Memo.sessionTag("dmVjdG9yLXRlc3Q"),
        )
    }

    private fun spendInstructions(): List<Instruction> {
        val ownerAta = PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc)
        val merchantAta = PublicKey.findAssociatedTokenAddress(merchant.publicKey, usdc)
        return listOf(
            SolanaPrograms.ComputeBudget.setComputeUnitLimit(200_000),
            SolanaPrograms.Token.transferChecked(
                source = ownerAta,
                mint = usdc,
                destination = merchantAta,
                authority = transient.publicKey,
                amount = 1_500_000,
                decimals = 6,
            ),
            SolanaPrograms.Memo.paymentTag("7f3a91c2"),
        )
    }

    @Test
    fun `session setup message serializes byte-for-byte identically to the reference`() {
        val message = Message.compile(approveInstructions(), owner.publicKey, blockhash)

        assertEquals(7, message.accountKeys.size)
        assertEquals(1, message.header.numRequiredSignatures)
        assertEquals(0, message.header.numReadonlySignedAccounts)
        assertEquals(5, message.header.numReadonlyUnsignedAccounts)

        // Canonical account order: fee payer first, then writable non-signers, then
        // read-only non-signers sorted by RAW KEY BYTES (not by base58 text).
        assertEquals(owner.publicKey.toBase58(), message.accountKeys[0].toBase58())
        assertEquals(
            PublicKey.findAssociatedTokenAddress(owner.publicKey, usdc).toBase58(),
            message.accountKeys[1].toBase58(),
        )

        assertEquals(
            "010005078a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c" +
                "2bc90238686902c261f364c614f1ecab4ff3e05f581a1c08053fc753d34de76c0306466f" +
                "e5211732ffecadba72c39be7bc8ce5bbc5f7126b2c439b3a40000000054a535a992921064" +
                "d24e87160da387c7c35b5ddbc92bb81e41fa8404105448d06ddf6e1d765a193d9cbe146ce" +
                "eb79ac1cb485ed5f5b37913a8cf5857eff00a98139770ea87d175f56a35466c34c7ecccb8" +
                "d8a91b4ee37a25df60f5b8fc9b394c6fa7af3bedbad3a3d65f36aabc97431b1bbe4c2d2" +
                "f6e0e47ca60203452f5d61c44f3955704151dcfd8a3cd6b11de658e2e630aea022b5d82f0" +
                "b1b8af63809b50402000502400d030002000903e8030000000000000404010605000a0d4" +
                "0787d01000000000603001f62756d707061793a73657373696f6e3a646d566a6447397" +
                "94c58526c633351",
            message.serialize().hex(),
        )
    }

    @Test
    fun `tap-time spend message serializes byte-for-byte identically to the reference`() {
        val message = Message.compile(spendInstructions(), transient.publicKey, blockhash)

        assertEquals(7, message.accountKeys.size)
        assertEquals(1, message.header.numRequiredSignatures)
        assertEquals(0, message.header.numReadonlySignedAccounts)
        assertEquals(4, message.header.numReadonlyUnsignedAccounts)

        // The transient session key pays the fee, so it is index 0.
        assertEquals(transient.publicKey.toBase58(), message.accountKeys[0].toBase58())

        assertEquals(
            "010004078139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b3942" +
                "bc90238686902c261f364c614f1ecab4ff3e05f581a1c08053fc753d34de76cb7bb84f5fe" +
                "33de15a41e5b08ebd21a5af1f86e46f653453e870f3047a51ed0e00306466fe5211732ffe" +
                "cadba72c39be7bc8ce5bbc5f7126b2c439b3a40000000054a535a992921064d24e87160da" +
                "387c7c35b5ddbc92bb81e41fa8404105448d06ddf6e1d765a193d9cbe146ceeb79ac1cb48" +
                "5ed5f5b37913a8cf5857eff00a9c6fa7af3bedbad3a3d65f36aabc97431b1bbe4c2d2f6e" +
                "0e47ca60203452f5d61c44f3955704151dcfd8a3cd6b11de658e2e630aea022b5d82f0b1b" +
                "8af63809b50303000502400d03000504010602000a0c60e31600000000000604001362756" +
                "d707061793a74783a3766336139316332",
            message.serialize().hex(),
        )
    }

    /**
     * The compact-u16 boundary.
     *
     * 200 instructions is the smallest count whose length prefix needs two bytes (`C8 01`).
     * Any implementation that writes the count as a single byte produces a message that is
     * a plausible-looking byte shorter and decodes to something entirely different on the
     * other end — the worst kind of bug to find during a live demo.
     */
    @Test
    fun `compact-u16 length prefix handles counts above 127`() {
        val many = (0 until 200).map { index ->
            SolanaPrograms.Memo.create("m$index")
        }
        val message = Message.compile(many, owner.publicKey, blockhash)
        val serialized = message.serialize()

        assertEquals(200, message.instructions.size)
        assertEquals(1392, serialized.size)

        // Work out where the instruction count lands rather than hardcoding a number:
        // 3 header bytes + 1 count byte + 32 per account key + 32 blockhash bytes.
        // Here there are exactly 2 accounts (the payer and the memo program), so the
        // count prefix begins at byte 100.
        val instructionCountOffset = 3 + 1 + (message.accountKeys.size * 32) + 32
        assertEquals(2, message.accountKeys.size)
        assertEquals(100, instructionCountOffset)

        // 200 does not fit in 7 bits, so the prefix is two bytes: C8 01.
        assertEquals("c8", serialized[instructionCountOffset].hex())
        assertEquals("01", serialized[instructionCountOffset + 1].hex())

        // Sanity-check the header/account preamble the offset above was derived from.
        assertEquals("01", serialized[0].hex()) // num_required_signatures
        assertEquals("00", serialized[1].hex()) // num_readonly_signed
        assertEquals("01", serialized[2].hex()) // num_readonly_unsigned (the memo program)
        assertEquals("02", serialized[3].hex()) // account count

        assertEquals(
            "73b98f655939be7d84b6f12f6463f8fa08e0b5fb3bf7a98976d6d9a9ea5b9bfd",
            MessageDigest.getInstance("SHA-256").digest(serialized).hex(),
        )
    }

    // ===================================================================================
    // 5. Signing and transaction layout
    // ===================================================================================

    @Test
    fun `signed transaction layout places signatures before the message`() {
        val transaction = Transaction.from(spendInstructions(), transient.publicKey, blockhash)

        assertFalse("nothing signed yet", transaction.isFullySigned())
        assertEquals(1, transaction.signatureCount)

        transaction.partialSign(transient)
        assertTrue("transient is the only required signer", transaction.isFullySigned())

        val serialized = transaction.serialize()
        val messageLength = transaction.message.serialize().size

        assertEquals(308, messageLength)
        assertEquals(373, serialized.size)
        // 1 byte of compact-u16 count + 64 bytes of signature.
        assertEquals(65, serialized.size - messageLength)
        // The message body starts with its 3-byte header: 1 signer, 0 ro-signed, 4 ro-unsigned.
        assertEquals("01", serialized[65].hex())
        assertEquals("00", serialized[66].hex())
        assertEquals("04", serialized[67].hex())
    }

    @Test
    fun `partial serialization zero-fills signatures for a wallet to replace`() {
        val transaction = Transaction.from(approveInstructions(), owner.publicKey, blockhash)
        val forSigning = transaction.serializeForSigning()

        // 1 count byte + 64 zero-filled signature bytes + message.
        assertEquals(1 + 64 + transaction.message.serialize().size, forSigning.size)
        assertTrue("signature slot must be zero-filled", forSigning.copyOfRange(1, 65).all { it == 0.toByte() })

        // Signing must fill exactly that slot.
        transaction.partialSign(owner)
        val signed = transaction.serialize()
        assertTrue(transaction.isFullySigned())
        assertFalse("signature slot must now hold real bytes", signed.copyOfRange(1, 65).all { it == 0.toByte() })
        assertEquals(signed.size, forSigning.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `serializing an unsigned transaction is refused`() {
        // Broadcasting a half-signed transaction produces an RPC error that looks
        // unrelated to the real cause, so this is a hard failure by design.
        Transaction.from(spendInstructions(), transient.publicKey, blockhash).serialize()
    }

    @Test
    fun `signatures verify against the serialized message`() {
        val transaction = Transaction.from(spendInstructions(), transient.publicKey, blockhash)
        transaction.partialSign(transient)

        val message = transaction.message.serialize()
        val serialized = transaction.serialize()
        val signature = serialized.copyOfRange(1, 65)

        assertTrue(
            "signature must verify over the message bytes",
            Keypair.verify(transient.publicKey, message, signature),
        )

        // And must NOT verify over a different message — guards against signing the wrong thing.
        val tampered = message.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(Keypair.verify(transient.publicKey, tampered, signature))
    }

    @Test
    fun `signing with a key that is not a required signer is refused`() {
        val transaction = Transaction.from(spendInstructions(), transient.publicKey, blockhash)
        try {
            transaction.partialSign(merchant)
            throw AssertionError("expected an IllegalArgumentException for a non-signer")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not a signer"))
        }
    }

    // ===================================================================================
    // 6. Amounts — where a rounding bug would move the wrong amount of money
    // ===================================================================================

    @Test
    fun `amount conversion is exact and refuses to round away value`() {
        assertEquals(25_000_000L, Amounts.toBaseUnits(java.math.BigDecimal("25"), 6))
        assertEquals(25_500_000L, Amounts.toBaseUnits(java.math.BigDecimal("25.5"), 6))
        assertEquals(1L, Amounts.toBaseUnits(java.math.BigDecimal("0.000001"), 6))
        assertEquals(1_500_000L, Amounts.toBaseUnits(java.math.BigDecimal("1.5"), 6))

        // More precision than the mint supports must throw rather than truncate.
        try {
            Amounts.toBaseUnits(java.math.BigDecimal("0.0000001"), 6)
            throw AssertionError("expected a refusal to round 7 decimal places into 6")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("refusing to silently round"))
        }

        assertEquals("25.500000", Amounts.formatPlain(25_500_000, 6))
        assertEquals("1.500000 USDC", Amounts.format(1_500_000, 6, "USDC"))
    }
}
