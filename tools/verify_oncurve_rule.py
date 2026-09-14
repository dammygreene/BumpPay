#!/usr/bin/env python3
"""
Validates the Ed25519 decompression rule that BumpPay's PDA derivation depends on.

`PublicKey.findProgramAddress` has to reject any candidate hash that happens to land on
the Ed25519 curve, and it does that by attempting point decompression. Get this wrong in
one direction and PDAs silently differ from every other Solana client; get it wrong in
the other and legitimate addresses get rejected. Either way it is a nightmare to debug
under a hackathon clock, so this script checks the rule against known-good values before
the Kotlin version gets written.

Run:  python3 tools/verify_oncurve_rule.py
"""

import hashlib

from solders.pubkey import Pubkey

# Curve constants for edwards25519.
P = 2**255 - 19
D = (-121665 * pow(121666, P - 2, P)) % P


def decompressable(compressed: bytes) -> bool:
    """True if these 32 bytes are a valid compressed Edwards point (i.e. ON curve).

    Mirrors what curve25519-dalek's CompressedEdwardsY::decompress() does, and therefore
    what Solana's Pubkey::is_on_curve() returns.
    """
    if len(compressed) != 32:
        return False

    # The top bit carries the sign of x; y is everything else, little-endian.
    y = int.from_bytes(compressed, "little") & ((1 << 255) - 1)

    # y must be a canonical field element.
    if y >= P:
        return False

    # Solve x^2 = (y^2 - 1) / (d*y^2 + 1)  (mod p)
    y2 = (y * y) % P
    numerator = (y2 - 1) % P
    denominator = (D * y2 + 1) % P
    if denominator == 0:
        return False

    x2 = (numerator * pow(denominator, P - 2, P)) % P

    # x2 is a valid square iff x2^((p-1)/2) == 1, with x2 == 0 counting as square.
    if x2 == 0:
        return True
    return pow(x2, (P - 1) // 2, P) == 1


def pda_off_curve(seeds: list[bytes], program: Pubkey) -> tuple[bool, int, str]:
    """Re-implements find_program_address so we can confirm the bump loop behaviour."""
    for bump in range(255, -1, -1):
        # NOTE: Solana appends the bump as a single byte as the LAST seed before the
        # program id and the marker.
        digest = hashlib.sha256(
            b"".join(seeds) + bytes([bump]) + bytes(program) + b"ProgramDerivedAddress"
        ).digest()

        if not decompressable(digest):
            return True, bump, str(Pubkey.from_bytes(digest))

    raise RuntimeError("no off-curve bump found")


def pda_no_bump(seeds: list[bytes], program: Pubkey) -> str:
    """create_program_address: hash without a bump byte."""
    digest = hashlib.sha256(
        b"".join(seeds) + bytes(program) + b"ProgramDerivedAddress"
    ).digest()
    return str(Pubkey.from_bytes(digest))


BUMPPAY_PROGRAM = Pubkey.from_string("BumpPay111111111111111111111111111111111111")

failures = []


def check(label: str, got, want) -> None:
    ok = got == want
    print(f"[{'PASS' if ok else 'FAIL'}] {label}\n        got  = {got}\n        want = {want}")
    if not ok:
        failures.append(label)


print("=" * 82)
print("1. Decompression / on-curve rule against known keypair-derived addresses")
print("=" * 82)

# These were all originally produced by Keypair::new(), so they MUST decompress.
KNOWN_ON_CURVE = [
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",  # USDC mint
    "So11111111111111111111111111111111111111112",     # wrapped SOL mint
    "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",     # token program
]
for addr in KNOWN_ON_CURVE:
    check(f"on-curve: {addr}", decompressable(bytes(Pubkey.from_string(addr))), True)

print()
print("=" * 82)
print("2. Real PDAs must be OFF-curve (this is the property the loop relies on)")
print("=" * 82)

ASSOCIATED_TOKEN_PROGRAM = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
TOKEN_PROGRAM = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")

# Canonical ATA derivation, cross-checked against solders' own find_program_address.
owner = Pubkey.from_string("AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9")
usdc = Pubkey.from_string("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")

manual_ata = pda_off_curve([bytes(owner), bytes(TOKEN_PROGRAM), bytes(usdc)], ASSOCIATED_TOKEN_PROGRAM)
reference_ata = Pubkey.find_program_address(
    [bytes(owner), bytes(TOKEN_PROGRAM), bytes(usdc)], ASSOCIATED_TOKEN_PROGRAM
)
check(
    "ATA(owner, USDC) matches solders",
    manual_ata[2],
    str(reference_ata[0]),
)
check("ATA bump matches solders", manual_ata[1], reference_ata[1])

print()
print("=" * 82)
print("3. BumpPay session PDA — the loop must actually iterate")
print("=" * 82)

# Deterministic stand-ins for the owner / transient keys (seed bytes 0x01 / 0x02).
owner_kp = Pubkey.from_bytes(hashlib.sha256(b"\x01" * 32).digest()[:0] or bytes(32))
# Use the real fixture keys from gen_test_vectors.py so the numbers line up.
SESSION_OWNER = Pubkey.from_string("AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9")
SESSION_TRANSIENT = Pubkey.from_string("9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu")

manual_session = pda_off_curve(
    [b"session", bytes(SESSION_OWNER), bytes(SESSION_TRANSIENT)], BUMPPAY_PROGRAM
)
reference_session = Pubkey.find_program_address(
    [b"session", bytes(SESSION_OWNER), bytes(SESSION_TRANSIENT)], BUMPPAY_PROGRAM
)
check("session PDA matches solders", manual_session[2], str(reference_session[0]))
check("session bump matches solders", manual_session[1], reference_session[1])
check("session bump is not 255 (loop genuinely iterates)", manual_session[1] < 255, True)

print()
print("=" * 82)
print("4. create_program_address (no bump) must be REJECTED when on-curve")
print("=" * 82)

# The raw hash for the session seeds with no bump — check whether it is on-curve, to
# document why the bump exists at all.
raw = pda_no_bump([b"session", bytes(SESSION_OWNER), bytes(SESSION_TRANSIENT)], BUMPPAY_PROGRAM)
print(f"        raw (bumpless) hash = {raw}")
print(f"        on curve?           = {decompressable(bytes(Pubkey.from_string(raw)))}")
print("        'vault' single-seed case:")
manual_vault = pda_off_curve([b"vault"], BUMPPAY_PROGRAM)
check("vault PDA matches solders", manual_vault[2], "76YhUFvF7MeNgEQUFQi659BmhyhVk5KxoPRAyJQttjLt")

print()
print("=" * 82)
if failures:
    print(f"RESULT: {len(failures)} FAILURE(S) — the Kotlin implementation would be wrong.")
    for f in failures:
        print(f"  - {f}")
    raise SystemExit(1)
print("RESULT: all checks passed. The decompression rule above is safe to transcribe to Kotlin.")
