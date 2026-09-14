package app.bumppay.core.solana

/**
 * A compiled (wire-ready) transaction message: the header, the deduplicated account list,
 * the recent blockhash, and the instructions reduced to integer indices.
 *
 * The serialization here is pinned byte-for-byte against the reference implementation by
 * SolanaWireFormatTest; every constant below corresponds to a documented byte offset.
 */
class Message(
    val header: MessageHeader,
    val accountKeys: List<PublicKey>,
    val recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
) {

    init {
        require(recentBlockhash.size == 32) {
            "a blockhash is 32 bytes, got ${recentBlockhash.size}"
        }
    }

    /** The exact bytes that get signed. Also the bytes a wallet verifies. */
    fun serialize(): ByteArray {
        val writer = ByteWriter(256)

        // 3-byte header
        writer.writeByte(header.numRequiredSignatures)
        writer.writeByte(header.numReadonlySignedAccounts)
        writer.writeByte(header.numReadonlyUnsignedAccounts)

        // account keys
        writer.writeShortVecLength(accountKeys.size)
        accountKeys.forEach { writer.write(it.bytes) }

        // recent blockhash
        writer.write(recentBlockhash)

        // instructions
        writer.writeShortVecLength(instructions.size)
        instructions.forEach { instruction ->
            writer.writeByte(instruction.programIdIndex)
            writer.writeShortVecLength(instruction.accountIndices.size)
            writer.write(instruction.accountIndices)
            writer.writeShortVecLength(instruction.data.size)
            writer.write(instruction.data)
        }

        return writer.toByteArray()
    }

    /** Describes the message in a form that is readable in a logcat dump during a tap. */
    fun describe(): String = buildString {
        appendLine("header(signers=${header.numRequiredSignatures}, ")
        appendLine("roSigned=${header.numReadonlySignedAccounts}, ")
        appendLine("roUnsigned=${header.numReadonlyUnsignedAccounts})")
        accountKeys.forEachIndexed { index, key -> appendLine("  [$index] $key") }
        instructions.forEachIndexed { index, instruction ->
            val program = accountKeys[instruction.programIdIndex]
            appendLine(
                "  ix$index -> $program " +
                    "accounts=${instruction.accountIndices.joinToString { it.toString() }} " +
                    "data=${instruction.data.size}B"
            )
        }
    }

    companion object {

        /**
         * Turns human-authored [Instruction]s into the compiled form.
         *
         * The ordering rule below is not a style choice — the runtime rejects a message
         * whose keys are not in canonical order:
         *
         *   index 0        the fee payer (forced writable + signer)
         *   then           writable signers, then read-only signers,
         *                  then writable non-signers, then read-only non-signers
         *   within a group ascending by the raw 32 key bytes
         *
         * Note "raw bytes", not the base58 string: `9hSR…` sorts *before* `ComputeBudget…`
         * as bytes while sorting after it as text. Getting this wrong is the difference
         * between a message that matches the reference implementation and one that does
         * not, which is exactly why the wire-format test exists.
         */
        fun compile(
            instructions: List<Instruction>,
            payer: PublicKey,
            recentBlockhash: ByteArray,
        ): Message {
            require(instructions.isNotEmpty()) { "a transaction needs at least one instruction" }

            val metas = HashMap<PublicKey, MutableMeta>()
            metas.merge(payer, MutableMeta(isSigner = true, isWritable = true))

            instructions.forEach { instruction ->
                instruction.accounts.forEach { account ->
                    metas.merge(
                        account.publicKey,
                        MutableMeta(account.isSigner, account.isWritable),
                    )
                }
                // The program being invoked is itself an account, and is never writable.
                metas.merge(instruction.programId, MutableMeta(isSigner = false, isWritable = false))
            }

            val others = metas.keys.filter { it != payer }.sortedWith { a, b ->
                val metaA = metas.getValue(a)
                val metaB = metas.getValue(b)
                var comparison = metaB.isSigner.compareTo(metaA.isSigner)
                if (comparison == 0) comparison = metaB.isWritable.compareTo(metaA.isWritable)
                if (comparison == 0) comparison = compareUnsigned(a.bytes, b.bytes)
                comparison
            }

            val accountKeys = ArrayList<PublicKey>(metas.size).apply {
                add(payer)
                addAll(others)
            }

            val header = MessageHeader(
                numRequiredSignatures = accountKeys.count { metas.getValue(it).isSigner },
                numReadonlySignedAccounts = accountKeys.count {
                    metas.getValue(it).isSigner && !metas.getValue(it).isWritable
                },
                numReadonlyUnsignedAccounts = accountKeys.count {
                    !metas.getValue(it).isSigner && !metas.getValue(it).isWritable
                },
            )

            val compiled = instructions.map { instruction ->
                CompiledInstruction(
                    programIdIndex = accountKeys.indexOf(instruction.programId),
                    accountIndices = ByteArray(instruction.accounts.size) { index ->
                        accountKeys.indexOf(instruction.accounts[index].publicKey).toByte()
                    },
                    data = instruction.data,
                )
            }

            return Message(header, accountKeys, recentBlockhash, compiled)
        }

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            val shared = minOf(a.size, b.size)
            for (index in 0 until shared) {
                val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }

    private class MutableMeta(isSigner: Boolean, isWritable: Boolean) {
        var isSigner: Boolean = isSigner
            private set
        var isWritable: Boolean = isWritable
            private set

        /** Privileges accumulate: an account touched by two instructions gets the union. */
        fun mergeWith(other: MutableMeta) {
            isSigner = isSigner || other.isSigner
            isWritable = isWritable || other.isWritable
        }
    }

    private fun HashMap<PublicKey, MutableMeta>.merge(key: PublicKey, value: MutableMeta) {
        val existing = this[key]
        if (existing == null) this[key] = value else existing.mergeWith(value)
    }
}

/** The three header counts, each a single byte on the wire. */
data class MessageHeader(
    val numRequiredSignatures: Int,
    val numReadonlySignedAccounts: Int,
    val numReadonlyUnsignedAccounts: Int,
)

/**
 * An instruction reduced to indices into [Message.accountKeys].
 *
 * [accountIndices] are u8s, so a message may reference at most 256 distinct accounts.
 * BumpPay's messages use fewer than ten; the limit is only relevant if a future feature
 * (address lookup tables aside) tries to batch many transfers together.
 */
class CompiledInstruction(
    val programIdIndex: Int,
    val accountIndices: ByteArray,
    val data: ByteArray,
)
