# BumpPay — security model and threat analysis

The build brief's checklist is answered at the bottom. This document first states the model
honestly, because a payments demo that overstates its guarantees is worse than one that names
its limits.

---

## The trust boundary

Three parties, with different levels of trust:

| Party | Trusted? | Holds |
|---|---|---|
| **Payer app** | Yes — it is the user's device | The session seed, Keystore-wrapped |
| **Merchant terminal** | **No.** Assume hostile | No keys at all |
| **Chain** | Yes | The delegation and the session record |

The terminal is assumed to be hostile, and the architecture is arranged so that the *only*
things it can influence are the amount, the destination, the blockhash, and the reference.
Every one of those is bounded:

- **Amount** — capped by the session limit, enforced in `SessionManager.preparePayment`
  before anything is signed.
- **Destination** — pinned at setup. The app refuses to sign a transfer anywhere else
  (`DESTINATION_NOT_ALLOWED`), and the Anchor program enforces the same rule on-chain.
- **Blockhash** — the terminal supplies it, and it is only used as a *fallback*. Preferring
  the locally cached blockhash means a terminal cannot influence the transaction's validity
  unless the payer's cache is cold. A hostile value can cause a failure; it cannot cause a
  payment the payer did not authorise.
- **Reference** — cosmetic; it lands in a memo.

**What the terminal cannot do:** it never sees the session seed, never signs, and never
receives a transaction that is valid for anything other than the pinned destination and the
remaining limit.

---

## The proximity-skimming defence

This is the attack the brief asks about directly, and the defence is deliberately blunt:

**A tap only produces a signature while the app is in the foreground, on an unlocked device,
with the session armed.** Anything else returns status word `6A82` and no transaction data.

Implementation:

- `MainActivity.onPause()` → `SessionManager.onLeaveForeground()`, which wipes the cached
  seed and disarms. **`onPause`, not `onStop`** — the notification shade, an incoming call or
  the wallet app opening all cover the activity, and all of them must disarm it.
- `ACTION_SCREEN_OFF` → `onDeviceLocked()`, which also wipes and disarms.
- `preparePayment` re-checks the armed state *before* any other validation.

A consequence worth accepting deliberately: `apduservice.xml` sets
`requireDeviceUnlock="false"`. Letting Android refuse the tap at the NFC layer would produce
an indistinguishable "nothing happened", and the checklist requires **demonstrating** the
`6A82` path on camera. The lock state is therefore enforced in the service, not by the
platform — and the observable behaviour is the required one.

---

## The key-storage claim, stated precisely

The checklist item is *"transient private key never leaves Android Keystore"*. That is the
right goal and it **cannot be implemented literally at minSdk 24**, so here is what is
actually done and why.

Solana requires **ed25519** signatures. Android Keystore only gained native ed25519 key
support in **API 35**. Below that, a Keystore-backed ed25519 signing key does not exist.

What BumpPay does instead — **envelope encryption**:

1. A non-exportable AES-256-GCM key is generated *inside* the Keystore
   (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, no padding). On a
   hardware-backed Keystore this key material is unextractable.
2. The session's ed25519 seed is sealed with that key. Only the ciphertext is persisted.
3. The seed is unsealed into memory only for the duration of a tap, used to sign, and then
   **zeroed** (`ByteArray.wipe()`), and it is wiped again on disarm.
4. The GCM IV is generated fresh per seal and stored as a prefix. It is never derived from
   the data or from a counter — IV reuse under GCM is catastrophic.

**The honest limitation:** the seed is hardware-protected *at rest*. During a tap it exists in
process memory, and no Android app below API 35 can claim otherwise. On API 35+ this should be
upgraded to a native Keystore ed25519 key, at which point the checklist item becomes literally
true. `KeystoreSecretBox.securityLevel()` reports whether the device gave us TEE, StrongBox or
software, so the claim can be checked per-device rather than asserted.

---

## The delegation hole, and how far it is closed

SPL token delegation is **amount-based only**. It has no expiry and no destination
restriction. The blueprint is right that this is fine for an MVP and right that it is a
limitation.

| Property | Option A (raw SPL) | Option B (Anchor program) |
|---|---|---|
| Amount cap | On-chain | On-chain |
| Destination bound to one account | **Client-side only** | **On-chain** |
| Expiry | **None** — bounded by amount and by disarming | **On-chain** (`Clock::get()`) |
| Revocation | `Revoke` zeroes the delegation | `Revoke` + session PDA closed, rent returned |
| Session state (spent-so-far) | Local counter, not authoritative | On-chain, decremented by the program |

The shipped app implements Option A and the program implements Option B. Client-side
destination pinning is real enforcement — the app genuinely will not sign a transfer to
another account — but it is enforced by the same code that holds the key, so a rooted device
or a modified APK defeats it. **Option B is what makes it a security property rather than a
policy.** That distinction is the reason the program exists at all and is worth saying out
loud to a judge who asks.

---

## Checklist

| Requirement | Status | Where |
|---|---|---|
| Session invalidated on `onPause` | ✅ implemented | `MainActivity.onPause` → `SessionManager.onLeaveForeground` |
| Re-armed on `onResume` | ✅ implemented | `MainActivity.onResume` → `onEnterForeground` |
| Locked-phone tap returns failure, never transaction data | ✅ implemented | `ACTION_SCREEN_OFF` → `onDeviceLocked`; `preparePayment` gates on `Armed` |
| Over-limit triggers an approval prompt, not a silent failure | ✅ implemented | `ResultCode.OVER_LIMIT` + `PendingApproval` → MWA prompt in `BumpPayApp` |
| Revoke zeroes the **on-chain** delegation | ✅ implemented | `revokeSession()` sends `Revoke` through MWA, and only forgets the session **after** the wallet accepts |
| Transient key never leaves Keystore | ⚠️ **partially** — hardware-protected at rest, in memory during a tap; see above | `KeystoreSecretBox` |
| AID collision-tested | ⚠️ **not yet run on hardware** — the AID is `F0`-prefixed to stay clear of payment-network AIDs | `apduservice.xml` |

---

## Standing assumptions, and the one that matters most

**The demo merchant key is public.** `BumpPayDemo.MERCHANT_SEED` is derived from the literal
string `"bumppay-demo-merchant-do-not-use-on-mainnet"` and is committed to this repository so
that a fresh clone runs without configuration.

`BumpPayDemo.assertSafeForCluster` throws if that key is used when the configured cluster is
mainnet-beta. **Before pointing this at mainnet, generate a real merchant keypair and enter
its address on the payer's Session Setup screen.** This is the single most important change
before handling real value.

Other assumptions:

- The payer's RPC responses are not verified against a second source. A compromised Helius
  endpoint could feed a stale blockhash; it cannot forge a signature.
- `getSignatureStatuses` polling is trusted for confirmation. For a high-value deployment,
  verify against a second RPC.
- The terminal supplies the reference; it is only used as a memo, so a hostile value is
  cosmetic rather than financial.
