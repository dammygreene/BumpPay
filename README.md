# BumpPay

**Tap two phones together and a Solana payment settles in under a second — no QR code, and no
biometric prompt per tap.**

Built for the Solana Mobile "Clock In" hackathon.

---

## The idea in one paragraph

Every crypto payment flow today asks the user to approve each transaction, because the wallet
has no way to know a payment is small and intended. BumpPay moves that decision *earlier*:
the payer confirms **one** capped delegation at setup, and after that a physical tap settles
silently, up to the limit they chose. The interesting part happens in the background during
the tap, and the UI exists to stay out of the way.

What makes it impossible outside this hardware: Host Card Emulation, which has no
cross-platform equivalent, and the Seed Vault, which is what makes a session key worth
delegating to. There is no React Native path to this.

---

## What is in here

| Path | What it is |
|---|---|
| `app/` | The payer's phone. Emulates a contactless card, holds the session key, signs at tap time. |
| `merchant-terminal/` | The reader. Sends the charge, broadcasts the signed transaction. Holds no keys. |
| `core/` | Shared between both apps: the APDU contract and the Solana wire format. |
| `program/` | Anchor program: expiry- and destination-bounded delegation. |
| `docs/` | Phase 0 report, security model, reference test vectors. |
| `tools/` | The vector generator that pins the hand-rolled serialization. |

**Why there is a `core/` module** (a deliberate deviation from the two-module layout): the
APDU contract and the transaction serializer must be **byte-identical on both sides of the
tap**. Two hand-maintained copies is the highest-value bug available — the phones would
disagree about what they just agreed on, and it would only appear under a physical tap.

---

## Before you start

**You need two physical Android devices with NFC.** Not emulators: Host Card Emulation only
emulates the *card* side, so there is nothing to tap against without a reader, and two
emulators cannot complete a real ISO-DEP exchange.

### The Gradle wrapper

`gradle/wrapper/gradle-wrapper.properties` is committed, but the wrapper **jar** and the
`gradlew` / `gradlew.bat` scripts are not (the jar is a binary that could not be fetched in
the environment this repository was authored in). Regenerate them once:

```bash
# Either open the project in Android Studio, which generates the wrapper on first open, or:
gradle wrapper --gradle-version 8.11.1
```

After that, `./gradlew` works normally and every command below applies.

### Toolchain

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | API 35 (compileSdk/targetSdk 35, minSdk 24) |
| Gradle | 8.11.1 (via the wrapper) |
| Anchor CLI | 1.2.0 |
| Solana CLI | 4.1.2 — only needed for testnet/mainnet deploys |

### RPC configuration

Put these in `~/.gradle/gradle.properties`, **not** in the repository:

```properties
HELIUS_API_KEY=your-key-here
BUMPPAY_CLUSTER=devnet
```

With no key, both apps fall back to the public devnet RPC. That is fine for Phase 1
(NFC-only) but it rate-limits quickly under repeated taps, which is exactly what a demo is.

---

## Build and run

```bash
git clone <this-repo> && cd BumpPay

# Verify the Solana wire format before anything else. This is the highest-risk code in the
# project and this is the one command that proves it against reference test vectors.
./gradlew :core:testDebugUnitTest

# Install the payer app
./gradlew :app:installDebug

# Install the terminal
./gradlew :merchant-terminal:installDebug
```

### Running a tap

1. Launch **BumpPay** on the payer phone and tap **Connect Wallet** — approve in Seed Vault,
   Phantom or Solflare.
2. Set a limit ($10–$500, or "Always ask"). This is the **one** biometric confirmation.
   Leave the merchant address as the prefilled demo value.
3. The payer phone now shows the idle pulse: **Ready to tap**.
4. Launch **BumpPay Terminal** on the other phone, enter an amount, and hold the phones back
   to back.
5. The terminal broadcasts and confirms. The payer phone shows the checkmark and buzzes.

The payer phone does **no network I/O during the tap**. If you want to make that visible in
the demo video, put it in airplane mode after step 3 — it still works, because the terminal
supplies everything the transaction needs except the signature.

### The on-chain program (optional)

The app works without it, using plain SPL delegation. The program adds real on-chain expiry
and destination pinning — see `docs/SECURITY.md` for why that matters.

```bash
cd program
avm install 1.2.0 && avm use 1.2.0
anchor build
anchor test          # includes a check that the Kotlin discriminators still match
anchor deploy --provider.cluster devnet
anchor keys sync     # writes the deployed program id into declare_id! and Anchor.toml
```

---

## How it works

### The tap

```
Terminal                                    Payer phone
   │  SELECT AID  F0 42 55 4D 50 50 41 59 01   │
   ├──────────────────────────────────────────►│  routes to BumpPayApduService
   │  GET SESSION STATE                        │
   ├──────────────────────────────────────────►│  armed? remaining limit?
   │                                           │
   │  PAYMENT REQUEST (amount, destination,    │  checks: armed → destination
   │    merchant, reference, blockhash)        │         → limit → mint → blockhash
   ├──────────────────────────────────────────►│  signs with the session key
   │  ◄──────── 61 XX  (more data waiting)     │
   │  GET RESPONSE                             │
   ├──────────────────────────────────────────►│
   │  ◄──────── signed transaction + 90 00     │
   │                                           │
   │  broadcasts via Helius, confirms on-chain │
```

Two details that are not obvious and are load-bearing:

- **The response is chunked.** A signed transaction is ~370 bytes; a short response APDU
  carries at most 256. The card answers `61 XX` and the reader pulls the rest with
  `GET RESPONSE`. A reader that ignores this gets a truncated transaction and an RPC
  deserialization error that points nowhere near the real cause.
- **The blockhash comes from the payer's cache, not the terminal.** While the app is
  foregrounded it refreshes one every 15 seconds, so the tap path contains no network call.
  The terminal's blockhash is only a fallback for a cold cache.

### Session setup

One MWA-approved transaction creates a capped SPL delegation and records a session:

```
ComputeBudget × 2
ApproveChecked(owner → transient, limit)     ← the delegation
Memo("bumppay:session:<id>")
```

### Why the destination is pinned

Raw SPL delegation lets a delegate move the approved tokens to **any** account. BumpPay binds
each session to one merchant token account at setup and refuses to sign anything else. That
is enforced client-side today and **on-chain** by the Anchor program. `docs/SECURITY.md` is
explicit about which is which.

---

## Verification

The Solana serialization is hand-rolled (see `docs/PHASE0-STACK-DECISION.md` for why). That
is only defensible because it is pinned against the reference implementation:

```bash
./gradlew :core:testDebugUnitTest     # asserts every byte layout
python3 tools/verify_oncurve_rule.py  # asserts the PDA curve rule
python3 tools/gen_test_vectors.py     # regenerates the goldens (needs solders)
```

`docs/reference-test-vectors.txt` is the generated output, committed so the expected values
are auditable without installing Python.

---

## Known gaps

Stated plainly, because a repository that hides these is worse than one that lists them:

- **Nothing here has been compiled.** The authoring environment had no JDK, Android SDK or
  Rust toolchain, and its network blocked Maven Central and crates.io. Everything was written
  from verified API sources, but **the first `./gradlew assembleDebug` is the real check**.
  The Solana wire format is the exception: it is pinned by the goldens and testable on its own.
- **The release build currently falls back to debug signing.** `app/build.gradle.kts` needs a
  real keystore before submission — the publishing CLI rejects debug builds. See the comment
  in `gradle.properties`.
- **Phase 4 items are implemented but not yet demonstrated on hardware.** Screen-off/locked
  taps return `6A82`, over-limit taps raise the approval prompt, and revoke zeroes the
  delegation — all written, none yet observed under a real tap.
- **SKR integration is not started.** It is stretch scope and was not begun.

## Licence

MIT.
