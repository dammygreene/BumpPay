# BumpPay — Execution Blueprint
### Transient NFC Settlement Protocol for Solana Mobile "Clock In"

---

## 1. Product Vision

**One-liner:** Tap two Seeker phones together, settle a Solana payment in under 400ms, no QR codes, no per-tap biometric confirmation.

**Submission constraints (non-negotiable):**
- Functional signed-release Android APK (debug builds rejected by the publishing CLI)
- Public GitHub repo, buildable/cloneable
- 3-minute demo video showing it running **on a device**, not a simulator
- Pitch deck
- Deadline: **Oct 9, 2026, 07:59 GMT+1** (~4 weeks from today)

**Judging weights to build toward:** Stickiness/PMF 25%, UX 25%, Innovation 25%, Presentation 25%. For a payments app, "stickiness" reads as *reliability and speed under repeated real taps*, not daily-engagement gamification — so the bar is: does it work, every time, fast, on camera.

---

## 2. System Architecture

Three layers, deliberately decoupled so a bug in one doesn't block the others during dev:

```
┌─────────────────────────────────────────────┐
│  MWA Layer (Kotlin)                          │
│  High-security signing — session setup,      │
│  over-limit fallback, revocation             │
└───────────────────┬───────────────────────────┘
                    │
┌───────────────────┴───────────────────────────┐
│  HCE Layer (Kotlin, HostApduService)          │
│  Background NFC card emulation.               │
│  Owns the "tap" moment end to end.            │
└───────────────────┬───────────────────────────┘
                    │
┌───────────────────┴───────────────────────────┐
│  On-Chain Layer (SPL Token + thin Anchor prog)│
│  Delegation, spend limits, revocation         │
└─────────────────────────────────────────────┘
```

**A component the source blueprint doesn't spell out but you need:** since HCE only emulates the *card* side, there's nothing on the other end to tap against. You need to build a small **Merchant Terminal companion app** — a second Android app (can run on any spare Android phone/emulator) using `NfcAdapter.enableReaderMode`, which sends the APDU commands and has the internet connection to broadcast the signed transaction. Budget real time for this — it's a second, smaller app, not a config toggle.

---

## 3. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | Native required for HCE — no way around this from React Native |
| UI | Jetpack Compose | Fastest path to a clean "invisible execution" UI |
| Min SDK | 24 (HCE requirement) | Target Android 15 |
| Wallet signing | `mobile-wallet-adapter-clientlib-ktx` | Official Solana Mobile Kotlin MWA client |
| RPC provider | Helius | Confirmed real, used for Genesis Token verification too — reuse the same key |
| On-chain program | Anchor (Rust) | Thin wrapper — see §5 |
| Solana Kotlin RPC/tx building | **Verify before committing** | The earlier doc named "Artemis Solana SDK" — I can't confirm this is a real, maintained library. Don't build around it blind. Safer bets: `sol4k` (community Kotlin Solana SDK) or hand-roll transaction construction via direct JSON-RPC calls with OkHttp/Ktor. Spend 30 minutes verifying whichever you pick has recent commits before Week 1 planning locks in. |

---

## 4. Repository Structure

```
bumppay/
├── app/                    # Main Kotlin Android app (the "wallet holder")
│   ├── hce/                # HostApduService implementation
│   ├── mwa/                # MWA session + signing wrappers
│   └── ui/                 # Jetpack Compose screens
├── merchant-terminal/       # Companion reader-mode app
├── program/                 # Anchor program (Rust)
│   ├── programs/bumppay/
│   └── tests/
└── docs/                    # Pitch deck source, architecture diagrams
```

Two Android app modules in one repo keeps the GitHub submission clean and lets the demo video show both sides from one clone.

---

## 5. On-Chain Design — the key decision

**Option A — MVP (raw SPL Token, no custom program):**
SPL Token's native `ApproveChecked` already lets an owner delegate a capped spend amount to another keypair, and that delegate can call `Transfer`/`TransferChecked` without the owner's signature, up to the approved amount. This alone gets you a working delegation + tap-to-pay flow with **zero custom Rust**. `Revoke` sets it back to zero.

*Limitation:* base SPL delegation has no time-based expiration — it's amount-based only. "Session expires when you background the app" would be enforced client-side (delete/ignore the local transient keypair), not truly on-chain.

**Option B — Enhanced (thin Anchor wrapper), recommended:**
A minimal 3-instruction program gives you real on-chain expiration and something concrete to point to for "we wrote a program," which matters for judging credibility on the technical-depth axis:

- `init_session(expiry: i64, limit: u64)` — creates a PDA (`seeds = [b"session", owner.key(), transient_key.key()]`) storing the expiry timestamp and limit, then CPIs into the token program's `approve_checked`.
- `spend_via_session(amount: u64)` — checks `Clock::get()?.unix_timestamp < session.expiry` and `amount <= session.remaining`, then CPIs `transfer_checked`, decrementing `remaining`.
- `revoke_session()` — CPIs `revoke` and closes the PDA, returning rent to the owner.

Keep it to these three. Don't be tempted to add multi-session management, analytics, or fee logic before the core flow works end to end — that's exactly the kind of scope creep that eats week 3.

**Recommendation:** build Option A first to prove the tap-to-signature-to-broadcast pipeline works at all, *then* swap in Option B once HCE is solid. Don't build the Anchor program before you've confirmed the NFC handshake even fires reliably — that's your biggest unknown, so de-risk it first (see §9).

---

## 6. Mobile App UX Flow

1. **Connect Wallet** — single button, triggers MWA session (works with Seed Vault, Phantom, Solflare — no per-wallet code needed).
2. **Session Setup ("Risk Tolerance")** — slider for spend limit ($10–$500) or "always ask" ($0 limit). This is the `init_session` call, signed via MWA (one deliberate biometric confirm, not per-tap).
3. **Bump State (idle/foreground)** — minimal screen, pulsing NFC icon, "ready to tap."
4. **Success State** — checkmark flash + haptic buzz on settlement.
5. **Over-limit fallback** — if the terminal requests more than the session limit, don't fail silently: surface an MWA biometric prompt immediately, so the user can still complete the payment manually.
6. **Revoke Session** — always-visible button; sends the revoke instruction, invalidates the transient key on-chain immediately.

Design philosophy per the source spec is right: **invisible execution**. Resist adding screens. The whole point is that the interesting part happens in the background during a physical tap.

---

## 7. HCE / NFC Implementation Plan

**Setup:**
- `apduservice.xml` defining a unique AID so Android routes taps to BumpPay instead of Google Wallet.
- `AndroidManifest.xml`: register the service, require `android.permission.BIND_NFC_SERVICE`.

**Tap sequence:**
1. Terminal sends `SELECT AID` → Android routes to BumpPay's `HostApduService`.
2. Terminal sends a command APDU with the payment amount.
3. `processCommandApdu()` validates amount against the session's remaining limit.
4. If valid: transient key signs a transfer transaction locally, returns it as the response APDU.
5. Terminal (which has connectivity) broadcasts the signed tx to Solana via Helius RPC.
6. If invalid/over-limit: return a failure status word, trigger MWA fallback (§6.5) instead.

**Lifecycle binding — don't skip this:**
- Session is only "hot" while the app is in `onResume` (foreground). On `onPause`, invalidate the local session immediately.
- If the phone is locked or app backgrounded during a tap attempt, return failure status word `6A 82` rather than any transaction data — this is your proximity-skimming defense and judges will likely probe it.

---

## 8. Build & Test Roadmap (Sep 11 → Oct 9)

**Week 1 — De-risk the unknown, not the familiar.**
Build the bare HCE "hello world": BumpPay app emulates a card, Merchant Terminal companion reads it, no Solana involved yet — just confirm two physical/emulated Android devices can complete an APDU handshake reliably. This is the piece with the most unknowns; if it's going to blow the timeline, better to find out now.

**Week 2 — On-chain plumbing.**
MWA wallet connect + Option A raw SPL delegation (`ApproveChecked`/`Transfer`/`Revoke`) tested on devnet, independent of the NFC layer. Confirm a transient key can spend on the owner's behalf.

**Week 3 — Wire it together.**
Merge the NFC handshake from Week 1 with the signing flow from Week 2: real tap → real signed transfer → real broadcast, happy path only, on devnet. Then swap in the Option B Anchor program if time allows.

**Week 4 — Harden and package.**
Over-limit fallback, lock-state failure codes, revocation, session UI polish. Move to testnet/mainnet-beta with small real amounts for the demo. Record the 3-minute demo (physical devices, not emulator — this is explicitly weighted in judging). Build the pitch deck. Compile the **signed release** APK (debug builds are auto-rejected). Submit with buffer before the Oct 9 07:59 GMT+1 cutoff — don't target the deadline itself.

---

## 9. Security / Edge-Case Checklist

- [ ] Session invalidated on `onPause`, re-authenticated via biometric on `onResume`
- [ ] Locked-phone tap attempts return failure status, never transaction data
- [ ] Over-limit amount triggers MWA fallback, not silent failure
- [ ] Revoke button actually zeroes the on-chain delegation, not just local state
- [ ] Transient private key never leaves Android Keystore
- [ ] AID collision-tested (confirm Google Wallet / other HCE apps don't intercept your taps)

---

## 10. SKR Integration Angle ($10k bonus track)

Natural hook without extra architecture: use SKR stake as a trust signal. Users who stake SKR unlock higher default auto-approve limits (skip the biometric fallback more often), or get fee discounts on the settlement. This is a small addition to §5's session PDA (one extra check against a staking account) but directly targets the separate $10k SKR-integration prize on top of the main track.

---

## 11. Deliverables Checklist (mapped to official rules)

- [ ] Functional Android APK — signed release build
- [ ] GitHub repo — both app modules, Anchor program, clean README
- [ ] 3-minute demo video — physical device taps visible, not simulator
- [ ] Pitch deck — lead with the hardware-dependency thesis: *why this is impossible outside Seeker hardware* (Seed Vault + HCE + sub-second settlement)
- [ ] (Stretch) Publish to Solana dApp Store within 30 days of results if you place, to claim any cash prize
