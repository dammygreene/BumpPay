// :core — shared between the payer app and the merchant terminal.
//
// An Android library (not a pure JVM library) because both apps consume it and it needs
// the Android SDK for Base64/Log. It has no UI and no NFC code of its own: it is the
// frozen contract between the two phones plus the Solana wire format.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.bumppay.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // JSON-RPC transport. OkHttp rather than a Solana SDK — see
    // docs/PHASE0-STACK-DECISION.md for why this project hand-rolls the wire format.
    api(libs.okhttp)

    // Ed25519 in the lightweight API only (no JCA provider registration, so it cannot
    // collide with Android's built-in BouncyCastle). Needed because `Signature("Ed25519")`
    // is not available before API 33 and minSdk here is 24.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
