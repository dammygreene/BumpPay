package app.bumppay.core.solana

/**
 * One account referenced by one instruction.
 *
 * [isSigner] and [isWritable] are assertions the runtime enforces: claiming `isWritable`
 * on an account the instruction does not actually write is tolerated, but *omitting* a
 * privilege that the instruction needs fails at runtime with an opaque access violation.
 * When merging metas across instructions Solana takes the union, which is what
 * [Message.compile] does below.
 */
data class AccountMeta(
    val publicKey: PublicKey,
    val isSigner: Boolean,
    val isWritable: Boolean,
) {
    companion object {
        fun writable(publicKey: PublicKey, signer: Boolean = false) =
            AccountMeta(publicKey, isSigner = signer, isWritable = true)

        fun readonly(publicKey: PublicKey, signer: Boolean = false) =
            AccountMeta(publicKey, isSigner = signer, isWritable = false)
    }
}

/**
 * A program call: which program, with what arguments, touching which accounts.
 *
 * This is the pre-compilation form. [Message.compile] turns a list of these into the
 * index-based wire representation.
 */
class Instruction(
    val programId: PublicKey,
    val data: ByteArray,
    val accounts: List<AccountMeta>,
) {
    override fun toString(): String =
        "Instruction(program=$programId, data=${data.size}B, accounts=${accounts.size})"
}
