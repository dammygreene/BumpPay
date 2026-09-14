import * as anchor from "@anchor-lang/core";
import { Program } from "@anchor-lang/core";
import { createHash } from "crypto";
import { readFileSync } from "fs";
import { join } from "path";
import { assert } from "chai";
import { Bumppay } from "../target/types/bumppay";

/**
 * Anchor integration tests for the BumpPay session program.
 *
 * The first test is the interesting one. BumpPay's Kotlin client hand-rolls its transaction
 * construction, which means it hardcodes Anchor's 8-byte instruction discriminators rather
 * than importing them from a generated client. Those constants are derived from
 * `sha256("global:<instruction_name>")[0..8]`, so renaming a Rust instruction silently
 * invalidates the Kotlin side.
 *
 * "Silently" is the problem: the Kotlin code would still compile, the app would still build,
 * and the first real tap would fail with an opaque on-chain error. So this test recomputes
 * the discriminators and reads them back out of the Kotlin source file, failing loudly on
 * any drift. It is a cross-language contract test, and it is cheap.
 */

function discriminator(instructionName: string): Buffer {
  return createHash("sha256")
    .update(`global:${instructionName}`)
    .digest()
    .subarray(0, 8);
}

describe("bumppay", () => {
  anchor.setProvider(anchor.AnchorProvider.env());
  const program = anchor.workspace.Bumppay as Program<Bumppay>;

  it("instruction discriminators match the hand-rolled Kotlin client", () => {
    const kotlinPath = join(
      __dirname,
      "..",
      "..",
      "core",
      "src",
      "main",
      "java",
      "app",
      "bumppay",
      "core",
      "solana",
      "SolanaPrograms.kt",
    );
    const kotlin = readFileSync(kotlinPath, "utf8");

    const expectations: Array<[string, string]> = [
      ["init_session", "IX_INIT_SESSION"],
      ["spend_via_session", "IX_SPEND_VIA_SESSION"],
      ["revoke_session", "IX_REVOKE_SESSION"],
    ];

    for (const [instructionName, kotlinConstant] of expectations) {
      const bytes = discriminator(instructionName);

      // The Kotlin constant is written as a list of hex byte literals, e.g.
      //   0x79.toByte(), 0xCE.toByte(), ...
      const expectedLiterals = Array.from(bytes)
        .map((b) => `0x${b.toString(16).padStart(2, "0")}`.toUpperCase().replace("0X", "0x"))
        .join("");

      const block = kotlin.split(`val ${kotlinConstant}`)[1];
      assert.isDefined(block, `${kotlinConstant} not found in SolanaPrograms.kt`);

      const declaration = block.split(")")[0];
      const actualLiterals = (declaration.match(/0x[0-9a-fA-F]{2}/g) ?? [])
        .map((s) => s.toUpperCase().replace("0X", "0x"))
        .join("");

      assert.equal(
        actualLiterals,
        expectedLiterals,
        `${kotlinConstant} is out of date for ${instructionName}. ` +
          `Rust says [${Array.from(bytes).join(", ")}]. ` +
          `Update SolanaPrograms.kt and docs/PHASE0-STACK-DECISION.md.`,
      );
    }
  });

  it("initialises a session and enforces the limit", async () => {
    // NOTE: this is the shape of the test, not a finished one. A full version needs a local
    // USDC mint, two funded token accounts, and a mint authority — which is most of the work
    // of building the fixture, and is why the brief says to prove Option A first and only
    // then reach for the program. Fill this in once Phase 2's devnet flow is green.
    assert.isDefined(program.programId);
  });
});
