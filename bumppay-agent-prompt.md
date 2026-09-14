# Agent Build Prompt: BumpPay

Paste everything below to your coding agent (e.g. Claude Code) as the initial task brief.

---

## Role & Working Mode

You are building **BumpPay**, a tap-to-pay Solana Mobile app, for submission to the Solana Mobile "Clock In" hackathon (deadline: Oct 9, 2026, 07:59 GMT+1). This is a multi-week build with two genuinely unfamiliar subsystems (Android NFC/HCE and Anchor/Solana programs). Work in **phases**, and do not proceed to the next phase until the current one has a working, tested artifact — not just code that compiles. At the end of each phase, summarize what works, what doesn't, and what you're unsure about before moving on. Flag any assumption you're making about an unverified library or API rather than silently building around it.

Ask me clarifying questions before starting a phase if requirements are ambiguous — don't guess silently on anything that would be expensive to unwind later (account structures, AID scheme, repo layout).

---

## Project Overview

**What it is:** Two Android phones tap together (NFC), and a Solana token payment settles in under a second, without the payer unlocking their phone or approving a biometric prompt for every tap — up to a pre-authorized spend limit.

**Why it's hard, specifically:** HCE (Host Card Emulation) requires native Kotlin — no React Native / cross-platform path exists for this. It must run on a **physical or emulated Android device with a second device acting as the "terminal"** — HCE only emulates the card side, so a companion reader-mode app is required and is not optional scope.

**Submission requirements (hard constraints):**
- Signed **release** APK — debug builds are automatically rejected by the publishing CLI
- Public GitHub repository, clean enough for someone else to clone and run
- 3-minute demo video showing it running on-device (not a simulator)
- Pitch deck
- Must integrate the Solana Mobile Stack + Mobile Wallet Adapter (MWA)
- Must interact meaningfully with the Solana network (this is not optional — the payment must actually settle on-chain, not be simulated)

---

## Tech Stack (do not deviate without checking with me first)

| Layer | Choice |
|---|---|
| Language | Kotlin, native Android |
| Min SDK | 24 (required for HCE) |
| Target | Android 15 |
| UI framework | Jetpack Compose |
| Wallet signing | `mobile-wallet-adapter-clientlib-ktx` (official Solana Mobile MWA client) |
| RPC provider | Helius |
| On-chain program | Anchor (Rust) |
| Solana Kotlin SDK | **Not yet decided — first task is to verify options** (see Phase 0) |

---

## Phase 0 — Verify Before Building (do this first, report back before continuing)

1. Confirm whether a library called "Artemis Solana SDK" exists, is maintained, and is suitable for Kotlin Solana RPC/transaction work. If it doesn't check out, propose alternatives (e.g. `sol4k`, or direct JSON-RPC via OkHttp/Ktor) with your reasoning, and wait for my go-ahead before committing to one.
2. Confirm current versions of `mobile-wallet-adapter-clientlib-ktx` and Anchor CLI/Solana CLI that are compatible with each other as of now.
3. Report back with your recommended final stack before writing any code.

---

## Repository Structure to Create

```
bumppay/
├── app/                     # Main Android app (the payer's wallet-holding device)
│   ├── hce/                 # HostApduService implementation
│   ├── mwa/                 # MWA session + signing wrappers
│   └── ui/                  # Jetpack Compose screens
├── merchant-terminal/        # Companion reader-mode app (the "terminal" side)
├── program/                  # Anchor program (Rust)
│   ├── programs/bumppay/
│   └── tests/
├── docs/                     # Architecture diagrams, pitch deck source
└── README.md                 # Setup + run instructions for both apps + program
```

---

## Phase 1 — De-risk the NFC Handshake (no Solana yet)

**Goal:** Prove two Android apps can complete a reliable APDU handshake before any blockchain logic touches it.

Build:
- `app/hce/`: minimal `HostApduService` that responds to a `SELECT AID` command with a hardcoded success response. Create the required `apduservice.xml` and register the service + `BIND_NFC_SERVICE` permission in the manifest.
- `merchant-terminal/`: minimal app using `NfcAdapter.enableReaderMode` that sends a `SELECT AID` command and logs the response.

**Acceptance criteria:** Tapping two physical devices (or two emulators configured for NFC, if that's viable — flag if it isn't and physical devices are required) together completes the handshake and both apps log success. Do not proceed to Phase 2 until this works reliably across multiple taps, not just once.

---

## Phase 2 — On-Chain Delegation (independent of NFC)

**Goal:** Prove a transient keypair can spend on an owner's behalf, without any NFC involved yet.

Start with **raw SPL Token instructions, no custom Anchor program**:
1. Generate a local "transient" keypair.
2. Use MWA to prompt the user's main wallet to sign an `ApproveChecked` instruction, delegating a capped USDC amount to the transient key's public address.
3. Have the transient key sign and submit a `TransferChecked` instruction moving a small amount, without the owner's signature, and confirm it settles on devnet.
4. Implement `Revoke` and confirm it zeroes the delegation.

**Acceptance criteria:** You can approve, spend within limit, attempt to spend over limit (should fail cleanly), and revoke — all verified against devnet transaction history, not just "no errors thrown."

Only after this works: build the enhanced Anchor program version —
- `init_session(expiry, limit)` — PDA at `seeds = [b"session", owner, transient_key]`, storing expiry + limit, CPIs `approve_checked`
- `spend_via_session(amount)` — checks `Clock::get()?.unix_timestamp < expiry` and remaining balance, CPIs `transfer_checked`
- `revoke_session()` — CPIs `revoke`, closes the PDA, returns rent

Keep this program to exactly these three instructions. Do not add multi-session support, fee logic, or analytics without explicit approval — scope creep here is the highest risk to the timeline.

---

## Phase 3 — Merge NFC + On-Chain (happy path only)

Wire Phase 1's handshake to Phase 2's signing flow:
1. Terminal sends payment amount via command APDU.
2. `processCommandApdu()` validates against remaining session limit.
3. If valid: transient key signs the transfer, returns it as the response APDU.
4. Terminal broadcasts the signed transaction via Helius RPC.
5. Confirm settlement on devnet, end to end, from a physical tap.

**Acceptance criteria:** A real tap between two devices results in a confirmed devnet transaction, observable in a block explorer, within the demo's expected sub-second window (measure and report actual latency).

---

## Phase 4 — Edge Cases & Security

Implement and test each of the following individually, with a way to demonstrate each on camera:
- Lifecycle binding: session invalidated on `onPause`, requires biometric re-auth via `onResume`
- Locked-phone tap returns failure status word `6A 82`, never transaction data
- Over-limit request triggers an MWA biometric fallback prompt, not a silent failure
- Revoke button zeroes the on-chain delegation, confirmed via a follow-up failed spend attempt
- AID collision check: confirm the tap routes to BumpPay and not Google Wallet or another HCE app on the test devices

---

## Phase 5 — UX Polish

Build the five screens per this flow, keeping the design philosophy "invisible execution" — minimal screens, most of the value is in the background:
1. Connect Wallet (single button, MWA session)
2. Session Setup — slider for spend limit ($10–$500) or "always ask" ($0)
3. Bump State (idle) — pulsing NFC icon
4. Success State — checkmark + haptic feedback
5. Revoke Session — always visible

---

## Phase 6 — Package & Submit

- Move from devnet to testnet/mainnet-beta with small real amounts for the demo
- Compile a **signed release** APK (verify it is not a debug build)
- Write the README so a judge can clone and run both apps + the program
- Confirm the GitHub repo is public and clean
- Report back with a checklist of what's done vs. what's missing against the deliverables below, before I record the demo video and build the pitch deck

---

## Final Deliverables Checklist (report status against this at the end)

- [ ] Signed release Android APK
- [ ] Public GitHub repo (both app modules + Anchor program)
- [ ] Working end-to-end tap-to-settle flow on a real network (not just devnet if time allows)
- [ ] All Phase 4 edge cases demonstrated and working
- [ ] README sufficient for a stranger to clone and run it

---

## Stretch (only after everything above is solid)

SKR integration for the $10k bonus track: users who stake SKR get higher default auto-approve limits or fee discounts on settlement. This should be a small addition to the session PDA (one extra check against a staking account), not a new subsystem. Do not start this until Phase 4 is complete.
