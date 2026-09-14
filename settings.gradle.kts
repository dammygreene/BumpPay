// BumpPay — tap-to-pay Solana settlement for Solana Mobile "Clock In".
//
// Three Gradle projects plus the on-chain program:
//   :core              shared wire-format + NFC protocol code used by BOTH apps
//   :app               the payer's wallet-holding device (HCE card emulation)
//   :merchant-terminal the reader (the other phone that has connectivity)
//   program/           Anchor program (Rust, built with `anchor build`, not Gradle)
//
// Why :core exists (a deliberate deviation from the blueprint's two-module layout):
// the APDU contract and the Solana transaction serialization must be byte-identical on
// both sides of the tap. Two hand-maintained copies of that logic is the highest-value
// bug you could ship — the phones would disagree about what they just agreed on, and it
// would only show up under a physical tap. One module, one source of truth.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BumpPay"

include(":core")
include(":app")
include(":merchant-terminal")
