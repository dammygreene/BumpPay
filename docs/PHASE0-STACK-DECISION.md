# Phase 0 — Stack verification and recommendation

**Status:** complete. This document is the Phase 0 report the build brief asks for, written
before any application code was produced.

**Headline:** the one thing the brief flagged as unverifiable — *"I can't confirm this is a
real, maintained library"* about the "Artemis Solana SDK" — **is real and maintained**. It is
*not*, however, what this project should build on. The reasoning is below, along with two
other findings that change the plan more than the Artemis question does.

---

## 1. "Artemis Solana SDK" — verified real, and deliberately not used

**It exists.** `QuarksBlueFoot/Selenus-Artemis-Solana-SDK-`, published to Maven Central under
the `xyz.selenus` group (`xyz.selenus:artemis-ws`, currently `2.3.0`). Apache-2.0. 48 commits,
12 tags, most recent commit `c8f258c` dated 16 May 2026 — roughly four months old, which is
maintenance, not abandonment.

It is also considerably more than a wrapper. Its own module map describes six dependency
rings: `artemis-core` (Pubkey, Keypair, Base58, PDA, Ed25519), `artemis-rpc` (92 JSON-RPC
methods, endpoint pool, circuit breaker), `artemis-tx`/`artemis-vtx` (legacy and v0
transactions, a `prepare → simulate → sign → send → confirm` pipeline with blockhash refresh
on retry), `artemis-programs` (System, Token, ATA, ComputeBudget builders), plus Token-2022,
Metaplex, cNFT/DAS, Anchor, and a `mobile/artemis-wallet-mwa-android` module that wraps
Mobile Wallet Adapter. There is even an `interop/` ring containing compatibility shims for
sol4k, Solana-KMP and web3-solana.

**Recommendation: do not build on it.** Three reasons, in order of weight:

1. **Adoption is effectively zero.** Two stars, no forks, single author. That is not a
   judgement about quality — the code may be excellent — it is a statement about the
   *support surface* when something goes wrong on day 19 of a four-week build. There is no
   community to have already hit your bug, and no second implementation to compare against.
2. **The blast radius is the whole transaction path.** `artemis-tx` builds and signs the
   exact bytes that move money. A defect anywhere in that path is a demo failure, and it is
   the one dependency in this stack that cannot be swapped out in an afternoon.
3. **We only need eight RPC methods and one instruction layout.** The measured surface of
   BumpPay's Solana usage is: `getLatestBlockhash`, `getBalance`, `getTokenAccountBalance`,
   `sendTransaction`, `getSignatureStatuses`, `getMinimumBalanceForRentExemption`,
   `getAccountInfo`, and legacy-transaction construction for four instruction kinds. A
   hundred lines of OkHttp plus two hundred lines of serialization covers all of it with no
   dependency at all.

**What was done instead — and why it is not a leap of faith.** `sol4k` was the brief's other
suggestion, but it is a KMP library of similar age, and choosing it would be the same bet
with a different name. Direct JSON-RPC via OkHttp was called out as the third option and it
is the one taken, because *hand-rolling a wire format is only risky if you cannot test it* —
and this one can be tested exhaustively.

`tools/gen_test_vectors.py` uses **solders**, the reference Solana SDK, to emit golden
vectors for every byte-level operation BumpPay performs: base58 round-trips, SPL Token
instruction payloads, Compute Budget payloads, PDA derivation including the bump, full
serialized messages for both the session-setup and tap-time transactions, the compact-u16
length boundary, and the signature-block layout. Those vectors are embedded in
`core/src/test/java/app/bumppay/core/solana/SolanaWireFormatTest.kt` and asserted byte-for-byte.

The output is in `docs/reference-test-vectors.txt`. It is not a claim that hand-rolled code
is better — it is that this particular hand-rolled code is *verified against the reference
implementation*, which is a stronger position than depending on something unverifiable.

Two findings from the generator worth calling out, both of which would have been silent bugs:

- **The session PDA's bump is 253, not 255.** The hashes for bumps 254 and 255 land *on* the
  ed25519 curve and must be rejected. An implementation that skipped the off-curve check
  would derive a different address, and the failure would surface as an opaque
  `ConstraintSeeds` at the moment of the first real tap.
- **A message with 200 instructions needs a two-byte length prefix (`C8 01`).** Writing it as
  a single byte produces a message that is one byte shorter and decodes to something else
  entirely. Ordinary transactions are far below this boundary, which is exactly why it would
  never have been caught by testing the happy path.

**Revisit Artemis if** the project later needs Token-2022, cNFT/DAS, or Metaplex — areas
where its Ring 3 modules are doing genuinely substantial work that would be unreasonable to
reimplement. It is a legitimate upgrade path; it is just not the right foundation for a
four-week deadline.

---

## 2. Mobile Wallet Adapter — 2.0.8, API confirmed against source

`com.solanamobile:mobile-wallet-adapter-clientlib-ktx` **2.0.8** is the newest version on
Maven Central (23 published versions; 2.0.8 published June 2025). `mvnrepository` advertises
a 2.1.0 for the sibling `clientlib` artifact, but 2.1.0 is **not** published for the Kotlin
`clientlib-ktx` artifact — using it would fail dependency resolution. Pinned to 2.0.8.

Rather than coding against recollection, the client's source was read directly and the
following were confirmed:

| Item | Confirmed API |
|---|---|
| Constructor | `MobileWalletAdapter(connectionIdentity: ConnectionIdentity, …)` — `connectionIdentity` is **non-nullable** |
| Identity | `ConnectionIdentity(identityUri, iconUri, identityName)`; `identityUri` must be absolute and hierarchical, `iconUri` must be **relative** or the client throws |
| Entry point | `suspend fun <T> transact(sender: ActivityResultSender, block: suspend AdapterOperations.(AuthorizationResult) -> T): TransactionResult<T>` |
| Result type | `sealed class TransactionResult<T>` → `Success` / `Failure(message, e)` / `NoWalletFound(message)` |
| Cluster | `var blockchain: Blockchain` — set to `Solana.Devnet` / `Solana.Mainnet`. Changing it **clears `authToken`** |
| Authorize | `authorize(identityUri, iconUri, identityName, chain: String, authToken, features, addresses, signInPayload)`; `chain` must be a valid chain identifier such as `solana:devnet` |
| Send | `signAndSendTransactions(transactions: Array<ByteArray>, params: TransactionParams)` → `SignAndSendTransactionsResult`, whose member is `signatures: byte[][]` |
| Activity plumbing | `ActivityResultSender(rootActivity: ComponentActivity)`; it registers a real `registerForActivityResult` launcher and **asserts only one request is in flight** — so it is constructed once per activity, not per call |

Two consequences are baked into the app: `MainActivity` must be a `ComponentActivity`, and
transactions handed to the wallet are serialized with **zero-filled signature slots**
(`Transaction.serializeForSigning()`), which is the form MWA expects.

---

## 3. Anchor and Solana CLI

| Tool | Version | Note |
|---|---|---|
| Anchor CLI | **1.2.0** (4 Sep 2026) | Current stable. `2.0.0-rc.1` exists — an RC is the wrong choice for a fixed deadline. |
| anchor-lang / anchor-spl | **1.2.0** | Must match the CLI's minor, or IDL generation fails confusingly. |
| Solana CLI | 4.1.2 (Agda/Agave) | **Only needed if you want testnet/mainnet deploys.** Anchor ≥ 1.0 dropped the hard dependency and defaults to Surfpool/LiteSVM for tests, which removes a whole install step. |
| Rust | 1.85.0 | Matches the current Anchor docs. |
| AVM | `cargo install --git https://github.com/otter-sec/anchor avm`, then `avm install 1.2.0 && avm use 1.2.0` | Anchor moved to the `otter-sec` org. |

Anchor 1.x also moved the TypeScript client to `@anchor-lang/core`. BumpPay's program does
not need it — the Kotlin client computes the instruction discriminators itself (see below) —
which keeps the Rust toolchain out of the Android build entirely.

---

## 4. Android toolchain

Pinned deliberately to a **known-compatible** set rather than the newest available, because
a half-finished toolchain migration mid-build costs an evening that the schedule does not
have:

| Component | Version |
|---|---|
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (with the Compose Compiler plugin, which is versioned to Kotlin from 2.0 onward) |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 (HCE's practical floor) |
| JVM target | 17 |
| BouncyCastle | `bcprov-jdk18on:1.78.1`, **lightweight API only** |

Android Studio will offer to upgrade AGP and Gradle. Accepting is fine — but do it as one
deliberate commit with a re-run of `./gradlew :core:testDebugUnitTest`, not while debugging
the NFC handshake.

**Why BouncyCastle, and why only the lightweight API.** Solana requires **ed25519**
signatures. `Signature.getInstance("Ed25519")` is not available before API 33, and minSdk is
24. Registering BouncyCastle as a JCA provider on Android is a classic source of
works-on-my-device failures, because Android ships its own cut-down BouncyCastle under the
same provider name. Calling `Ed25519Signer` and `Ed25519PrivateKeyParameters` directly avoids
provider resolution entirely and cannot collide. This is also why there is one small
algorithm written by hand — the ed25519 point-decompression check that PDA derivation needs
(see `Ed25519Curve.kt`), which was validated against solders before being transcribed.

---

## Recommendation summary

| Layer | Choice | Status |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | ✅ verified |
| Wallet signing | `mobile-wallet-adapter-clientlib-ktx:2.0.8` | ✅ verified against source |
| Solana RPC + transactions | **Hand-rolled** over OkHttp + JSON-RPC | ✅ decision above, pinned by golden vectors |
| On-chain program | Anchor 1.2.0 / anchor-lang 1.2.0 | ✅ verified current stable |
| RPC provider | Helius, with public devnet fallback | ✅ |
| Artemis Solana SDK | Real and maintained; **not adopted** | ⚠️ documented upgrade path |

---

## Assumptions that were not verified, stated plainly

Per the brief's instruction to flag assumptions rather than build around them silently:

1. **Nothing in this repository has been compiled.** The environment used to write it has no
   JDK, no Android SDK and no Rust toolchain, and its network egress blocks Maven Central,
   `dl.google.com` and crates.io. All Kotlin, Compose, Gradle and Rust source was written
   from verified API documentation and reference sources, but **the first
   `./gradlew assembleDebug` is the real check**. Expect small import and signature fixups.
   The one thing that is *not* a guess is the Solana byte-level serialization, which is
   pinned by the golden vectors and can be verified independently of the Android build via
   `./gradlew :core:testDebugUnitTest`.
2. **HCE cannot be exercised on an emulator.** Emulated NFC tag reading exists, but two
   emulators cannot complete a real peer-to-peer ISO-DEP exchange, so Phase 1's acceptance
   criterion requires **two physical Android devices**. This is flagged rather than
   discovered in Week 1.
3. **`gradle/wrapper/gradle-wrapper.jar` is absent.** It is a binary and could not be
   downloaded in the writing environment. Android Studio generates it on first open, or run
   `gradle wrapper --gradle-version 8.11.1` once with a system Gradle. Everything else in the
   wrapper (`gradle-wrapper.properties`, `gradlew`, `gradlew.bat`) is present.
4. **The memo program's account rule** was verified from its own source documentation: it
   "verifies that any accounts provided are signers of the transaction". BumpPay therefore
   passes **no accounts**, which is valid and vacuous. Adding a non-signer account would make
   the memo program reject the entire transaction.
