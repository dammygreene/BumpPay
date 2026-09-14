#!/usr/bin/env python3
"""
BumpPay — reference test-vector generator.

Why this exists
---------------
`app/` builds Solana transactions by hand (no third-party Kotlin Solana SDK — see
docs/PHASE0-STACK-DECISION.md). Hand-rolled wire-format code is exactly the kind of
thing that fails silently in a hackathon demo, so instead of trusting it we pin it
against the canonical implementation.

This script uses `solders` (the reference Solana SDK bindings) to emit golden vectors
for every wire-format operation BumpPay performs. The same vectors are embedded in
`app/src/test/java/app/bumppay/solana/SolanaWireFormatTest.kt`, which asserts that the
Kotlin implementation produces byte-identical output.

If a vector ever needs regenerating:

    pip install --break-system-packages solders
    python3 tools/gen_test_vectors.py

Output is printed as Kotlin source so it can be pasted straight into the test file.
"""

import base64
import hashlib
import struct

from solders.hash import Hash
from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey
from solders.signature import Signature
from solders.transaction import Transaction

# --------------------------------------------------------------------------------------
# Fixtures. Deterministic so vectors are stable across runs.
# --------------------------------------------------------------------------------------

def kp(seed_byte: int) -> Keypair:
    return Keypair.from_seed(bytes([seed_byte]) * 32)


OWNER = kp(1)          # the user's main wallet (MWA / Seed Vault)
TRANSIENT = kp(2)      # day-to-day session key held in Android Keystore
MERCHANT = kp(3)       # the terminal's receiving address
MINT = kp(4)           # token mint

# Real mainnet addresses so the vectors exercise real base58 payloads.
USDC_MINT = Pubkey.from_string("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
TOKEN_PROGRAM = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
ASSOCIATED_TOKEN_PROGRAM = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
COMPUTE_BUDGET = Pubkey.from_string("ComputeBudget111111111111111111111111111111")
MEMO_PROGRAM = Pubkey.from_string("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
SYSTEM_PROGRAM = Pubkey.from_string("11111111111111111111111111111111")

# BumpPay's own program id. Must match declare_id! in program/programs/bumppay/src/lib.rs
# and the constant in app/src/main/java/app/bumppay/solana/SolanaPrograms.kt.
BUMPPAY_PROGRAM = Pubkey.from_string("BumpPay111111111111111111111111111111111111")

# SPL Token instruction discriminators.
IX_APPROVE_CHECKED = 13
IX_TRANSFER_CHECKED = 12
IX_REVOKE = 5

# A fixed, syntactically valid blockhash. Real blockhashes rotate every ~60s so they are
# useless as test fixtures; only the 32-byte value matters for serialization correctness.
BLOCKHASH = Hash.from_bytes(hashlib.sha256(b"bumppay-demo-blockhash").digest())


def token_ix_data(discriminator: int, amount: int, decimals: int) -> bytes:
    """SPL Token instruction payload: u8 tag || u64 LE amount || u8 decimals."""
    return struct.pack("<BQB", discriminator, amount, decimals)


def ata(owner: Pubkey, mint: Pubkey) -> Pubkey:
    """Associated Token Account derivation (seeds: owner, token program, mint)."""
    return Pubkey.find_program_address(
        [bytes(owner), bytes(TOKEN_PROGRAM), bytes(mint)],
        ASSOCIATED_TOKEN_PROGRAM,
    )[0]


def pda(*seeds: bytes, program: Pubkey = BUMPPAY_PROGRAM) -> Pubkey:
    return Pubkey.find_program_address(list(seeds), program)[0]


# --------------------------------------------------------------------------------------
# 1. Base58
# --------------------------------------------------------------------------------------

def vectors_base58() -> None:
    print("// ---- 1. Base58 round-trip ----")
    for name, pub in [
        ("USDC mint", USDC_MINT),
        ("Token program", TOKEN_PROGRAM),
        ("ATA program", ASSOCIATED_TOKEN_PROGRAM),
        ("Memo program", MEMO_PROGRAM),
        ("Compute budget", COMPUTE_BUDGET),
        ("System program (all-zero key)", SYSTEM_PROGRAM),
    ]:
        print(f'// {name}')
        print(f'"{str(pub)}" to byteArray()')
    print()


# --------------------------------------------------------------------------------------
# 2. Instruction data (little-endian, the classic source of silent corruption)
# --------------------------------------------------------------------------------------

def vectors_instruction_data() -> None:
    print("// ---- 2. SPL Token instruction data (hex) ----")
    cases = [
        ("approveChecked 25 USDC (25_000_000, 6dp)", token_ix_data(IX_APPROVE_CHECKED, 25_000_000, 6)),
        ("transferChecked 1.50 USDC (1_500_000, 6dp)", token_ix_data(IX_TRANSFER_CHECKED, 1_500_000, 6)),
        ("transferChecked 0.000001 USDC (1, 6dp)", token_ix_data(IX_TRANSFER_CHECKED, 1, 6)),
        ("transferChecked max u64", token_ix_data(IX_TRANSFER_CHECKED, 2**64 - 1, 6)),
        ("revoke", token_ix_data(IX_REVOKE, 0, 0)[:1]),
    ]
    for label, data in cases:
        print(f'// {label}')
        print(f'"{data.hex()}" to byteArray()')
    print()
    print("// ---- 2b. Compute Budget instruction data (hex) ----")
    limit = struct.pack("<BI", 2, 200_000)
    price = struct.pack("<BQ", 3, 1_000)
    print(f'// setComputeUnitLimit(200_000)')
    print(f'"{limit.hex()}" to byteArray()')
    print(f'// setComputeUnitPrice(microLamports=1_000)')
    print(f'"{price.hex()}" to byteArray()')
    print()


# --------------------------------------------------------------------------------------
# 3. Program Derived Addresses — exercises the ed25519 off-curve rejection loop
# --------------------------------------------------------------------------------------

def vectors_pdas() -> None:
    print("// ---- 3. PDA derivation ----")
    session = pda(b"session", bytes(OWNER.pubkey()), bytes(TRANSIENT.pubkey()))
    print(f'// session PDA: seeds = [b"session", owner, transient]')
    print(f'// owner     = {OWNER.pubkey()}')
    print(f'// transient = {TRANSIENT.pubkey()}')
    print(f'// expected  = "{session}" (bump {Pubkey.find_program_address([b"session", bytes(OWNER.pubkey()), bytes(TRANSIENT.pubkey())], BUMPPAY_PROGRAM)[1]})')

    owner_ata = ata(OWNER.pubkey(), USDC_MINT)
    dest_ata = ata(MERCHANT.pubkey(), USDC_MINT)
    print(f'// owner USDC ATA      = "{owner_ata}"')
    print(f'// merchant USDC ATA   = "{dest_ata}"')

    # A single-byte seed case, which is where naive implementations skip the bump loop.
    single = pda(b"vault")
    print(f'// single-seed PDA [b"vault"] = "{single}"')
    print()


# --------------------------------------------------------------------------------------
# 4. Full legacy message serialization
# --------------------------------------------------------------------------------------

def build_approve_tx() -> Instruction:
    """The transaction the payer app sends once, at session setup."""
    owner_ata = ata(OWNER.pubkey(), USDC_MINT)
    return [
        Instruction(
            COMPUTE_BUDGET,
            struct.pack("<BI", 2, 200_000),
            [],
        ),
        Instruction(
            COMPUTE_BUDGET,
            struct.pack("<BQ", 3, 1_000),
            [],
        ),
        Instruction(
            TOKEN_PROGRAM,
            token_ix_data(IX_APPROVE_CHECKED, 25_000_000, 6),
            [
                AccountMeta(owner_ata, is_signer=False, is_writable=True),
                AccountMeta(USDC_MINT, is_signer=False, is_writable=False),
                AccountMeta(TRANSIENT.pubkey(), is_signer=False, is_writable=False),
                AccountMeta(OWNER.pubkey(), is_signer=True, is_writable=False),
            ],
        ),
        Instruction(
            MEMO_PROGRAM,
            b"bumppay:session:dmVjdG9yLXRlc3Q",
            [],
        ),
    ]


def build_spend_tx() -> Instruction:
    """The transaction the terminal broadcasts after the tap. Signed by the TRANSIENT key."""
    owner_ata = ata(OWNER.pubkey(), USDC_MINT)
    dest_ata = ata(MERCHANT.pubkey(), USDC_MINT)
    return [
        Instruction(
            COMPUTE_BUDGET,
            struct.pack("<BI", 2, 200_000),
            [],
        ),
        Instruction(
            TOKEN_PROGRAM,
            token_ix_data(IX_TRANSFER_CHECKED, 1_500_000, 6),
            [
                AccountMeta(owner_ata, is_signer=False, is_writable=True),
                AccountMeta(USDC_MINT, is_signer=False, is_writable=False),
                AccountMeta(dest_ata, is_signer=False, is_writable=True),
                AccountMeta(TRANSIENT.pubkey(), is_signer=True, is_writable=False),
            ],
        ),
        Instruction(
            MEMO_PROGRAM,
            b"bumppay:tx:7f3a91c2",
            [],
        ),
    ]


def vectors_messages() -> None:
    print("// ---- 4. Legacy transaction message serialization ----")

    approve = build_approve_tx()
    msg_a = Message.new_with_blockhash(approve, OWNER.pubkey(), BLOCKHASH)
    raw_a = bytes(msg_a)
    print("// 4a. approveChecked session-setup message, fee payer = OWNER")
    print(f'// accounts ({len(msg_a.account_keys)}):')
    for i, k in enumerate(msg_a.account_keys):
        print(f"//   [{i}] {k}")
    print(f'// header: req={msg_a.header.num_required_signatures} '
          f'ro_signed={msg_a.header.num_readonly_signed_accounts} '
          f'ro_unsigned={msg_a.header.num_readonly_unsigned_accounts}')
    print(f'// hex   = "{raw_a.hex()}"')
    print(f'// base64= "{base64.b64encode(raw_a).decode()}"')
    print()

    spend = build_spend_tx()
    msg_s = Message.new_with_blockhash(spend, TRANSIENT.pubkey(), BLOCKHASH)
    raw_s = bytes(msg_s)
    print("// 4b. transferChecked spend message, fee payer = TRANSIENT")
    print(f'// accounts ({len(msg_s.account_keys)}):')
    for i, k in enumerate(msg_s.account_keys):
        print(f"//   [{i}] {k}")
    print(f'// header: req={msg_s.header.num_required_signatures} '
          f'ro_signed={msg_s.header.num_readonly_signed_accounts} '
          f'ro_unsigned={msg_s.header.num_readonly_unsigned_accounts}')
    print(f'// hex   = "{raw_s.hex()}"')
    print(f'// base64= "{base64.b64encode(raw_s).decode()}"')
    print()

    # A many-instruction case that forces a multi-byte compact-u16 length prefix.
    # 200 identical memo instructions -> instruction count 200 encodes as [0xC8, 0x01].
    # Naive implementations that write a single length byte corrupt the message here.
    many = [Instruction(MEMO_PROGRAM, f"m{i}".encode(), []) for i in range(200)]
    msg_m = Message.new_with_blockhash(many, OWNER.pubkey(), BLOCKHASH)
    raw_m = bytes(msg_m)
    print("// 4c. compact-u16 boundary: 200 instructions (count prefix must be 0xC8 0x01)")
    print(f'// length = {len(raw_m)} bytes')
    print(f'// sha256 = {hashlib.sha256(raw_m).hexdigest()}')
    print(f'// first 8 bytes = "{raw_m[:8].hex()}"')
    print()


# --------------------------------------------------------------------------------------
# 5. Transaction signature layout (64-byte slots, fixed order)
# --------------------------------------------------------------------------------------

def vectors_signature_layout() -> None:
    print("// ---- 5. Signed transaction layout ----")
    ixs = build_spend_tx()
    msg = Message.new_with_blockhash(ixs, TRANSIENT.pubkey(), BLOCKHASH)
    tx = Transaction.populate(msg, [TRANSIENT.sign_message(bytes(msg))])
    raw = bytes(tx)
    print("// 5a. fully-signed spend transaction (1 signature)")
    print(f'// total len     = {len(raw)}')
    print(f'// message len   = {len(bytes(msg))}')
    print(f'// sig slot len  = {len(raw) - len(bytes(msg))} (expect 64)')
    print(f'// sig[0..8] hex = "{raw[:8].hex()}"')
    print(f'// base64        = "{base64.b64encode(raw).decode()}"')
    print()

    # num_required_signatures == 2 -> 2 * 64 = 128 leading bytes before message body.
    owner_ata = ata(OWNER.pubkey(), USDC_MINT)
    approve_ixs = [
        Instruction(
            TOKEN_PROGRAM,
            token_ix_data(IX_APPROVE_CHECKED, 25_000_000, 6),
            [
                AccountMeta(owner_ata, is_signer=False, is_writable=True),
                AccountMeta(USDC_MINT, is_signer=False, is_writable=False),
                AccountMeta(TRANSIENT.pubkey(), is_signer=False, is_writable=False),
                AccountMeta(OWNER.pubkey(), is_signer=True, is_writable=False),
            ],
        ),
    ]
    msg2 = Message.new_with_blockhash(approve_ixs, OWNER.pubkey(), BLOCKHASH)
    print("// 5b. partial-signature case (MWA returns owner-signed, transient slot still zeroed)")
    print(f'// num_required_signatures = {msg2.header.num_required_signatures}')
    print(f'// signature block bytes   = {msg2.header.num_required_signatures * 64}')
    print(f'// message len             = {len(bytes(msg2))}')
    print()


if __name__ == "__main__":
    print("// GENERATED by tools/gen_test_vectors.py — do not hand-edit.")
    print(f"// solders version pinned at generation time: {__import__('solders').__version__}")
    print()
    vectors_base58()
    vectors_instruction_data()
    vectors_pdas()
    vectors_messages()
    vectors_signature_layout()
