package app.bumppay.session

import app.bumppay.core.solana.PublicKey

/**
 * The persistent description of a pre-authorised spend.
 *
 * Everything the app needs to sign a payment without the owner's involvement, *except* the
 * seed — which is stored separately, Keystore-wrapped, and never inside this record.
 *
 * ## Why the destination is pinned
 *
 * With raw SPL token delegation (the blueprint's Option A) the runtime grants the delegate
 * the right to move up to N tokens to *any* destination. That is a real hole: a compromised
 * payer app, or a malicious terminal that gets a request signed, can send the approved
 * amount anywhere.
 *
 * BumpPay closes the part of it that can be closed client-side by binding the session to
 * one destination token account at setup time and refusing to sign a transfer to any other.
 * That is enforcement, not policy theatre — but it is enforced by the app, not the chain.
 * The Anchor program (Option B) moves the same check on-chain where it cannot be bypassed.
 * docs/SECURITY.md states this trade-off plainly.
 */
data class SessionRecord(
    /** The user's main wallet. Signs once, at setup. */
    val owner: PublicKey,
    /** The day-to-day session key. Held Keystore-wrapped, signs every tap. */
    val transient: PublicKey,
    val mint: PublicKey,
    /** The owner's token account that funds the session. */
    val ownerTokenAccount: PublicKey,
    /** The merchant token account this session may pay. The pinned destination. */
    val destinationTokenAccount: PublicKey,
    /** The merchant's wallet, for display and for the on-chain program's check. */
    val merchant: PublicKey,
    val limitBaseUnits: Long,
    val spentBaseUnits: Long,
    val decimals: Int,
    /** Unix seconds. Zero means "no on-chain expiry" — the Option A path. */
    val expiryUnixSeconds: Long,
    /** Identifies this session in the memo, so payments are greppable in an explorer. */
    val sessionId: String,
    /** The block height after which the setup transaction's blockhash is void. */
    val lastValidBlockHeight: Long,
) {
    val remainingBaseUnits: Long get() = (limitBaseUnits - spentBaseUnits).coerceAtLeast(0)

    fun isExpired(nowUnixSeconds: Long): Boolean =
        expiryUnixSeconds > 0 && nowUnixSeconds >= expiryUnixSeconds

    /** True when a spend of [amountBaseUnits] fits inside what is left. */
    fun canSpend(amountBaseUnits: Long): Boolean =
        amountBaseUnits in 1..remainingBaseUnits

    fun withSpend(amountBaseUnits: Long): SessionRecord =
        copy(spentBaseUnits = spentBaseUnits + amountBaseUnits)
}

/**
 * Whether the session is currently able to sign *without* the user present.
 *
 * This is deliberately separate from whether a [SessionRecord] exists: a session persists
 * across restarts, but it is only *armed* while the app is in the foreground and the device
 * is unlocked. That distinction is the proximity-skimming defence, and the checklist asks
 * for it to be demonstrable.
 */
sealed interface SessionPhase {

    /** No session at all. The Connect Wallet screen is the only reachable route. */
    data object NoSession : SessionPhase

    /** A session exists but is not armed — backgrounded, locked, or revoked locally. */
    data class Disarmed(val reason: DisarmReason) : SessionPhase

    /** Armed. A tap right now will settle immediately. */
    data object Armed : SessionPhase

    /** A tap is being signed. The window between detection and settlement. */
    data object Signing : SessionPhase

    /** Last tap settled. Transient; the UI auto-dismisses back to [Armed]. */
    data class Settled(val signature: String, val amountBaseUnits: Long, val decimals: Int) : SessionPhase
}

enum class DisarmReason {
    /** The app left the foreground. The dominant case, and the correct one. */
    APP_BACKGROUNDED,

    /** Screen off or keyguard up. Taps must return 6A82. */
    DEVICE_LOCKED,

    /** The user revoked. */
    REVOKED,

    /** NFC is turned off system-wide, so no tap can arrive anyway. */
    NFC_DISABLED,

    /** The limit is exhausted. */
    LIMIT_EXHAUSTED,
}

/**
 * An over-limit request that is waiting on the user.
 *
 * Phase 4 requires that exceeding the session limit triggers a Mobile Wallet Adapter
 * biometric prompt rather than failing silently. The tap itself is far too short to show a
 * dialog, so the flow is: the HCE service returns status word `6985` immediately (so the
 * terminal can show "ask the payer to approve"), and *simultaneously* the payer's phone
 * surfaces this prompt. The user approves and MWA signs the payment directly, with the
 * owner as signer and no session key involved.
 *
 * Both devices end up agreeing on what happened without either blocking the other.
 */
data class PendingApproval(
    val amountBaseUnits: Long,
    val decimals: Int,
    val mint: PublicKey,
    val destinationTokenAccount: PublicKey,
    val reference: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingApproval &&
            amountBaseUnits == other.amountBaseUnits &&
            decimals == other.decimals &&
            mint == other.mint &&
            destinationTokenAccount == other.destinationTokenAccount &&
            reference.contentEquals(other.reference)

    override fun hashCode(): Int {
        var result = amountBaseUnits.hashCode()
        result = 31 * result + decimals
        result = 31 * result + mint.hashCode()
        result = 31 * result + destinationTokenAccount.hashCode()
        result = 31 * result + reference.contentHashCode()
        return result
    }
}
