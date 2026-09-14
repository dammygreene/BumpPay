// :merchant-terminal — the other phone.
//
// This is not optional scope and it is not a config toggle. Host Card Emulation only
// emulates the *card* side of a contactless exchange, so without a reader there is nothing
// for the payer's phone to be tapped against. The blueprint calls this out explicitly, and
// it is the single most commonly-underestimated part of the project.
//
// Deliberately a separate application rather than a second activity in :app: the two roles
// have different trust levels. The terminal is the untrusted party — it holds no keys, and
// in the security model it is assumed to be hostile. Keeping it a separate installable
// makes that boundary visible in the repository instead of merely asserted in a document.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.bumppay.terminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bumppay.terminal"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField(
            "String",
            "HELIUS_API_KEY",
            "\"${project.findProperty("HELIUS_API_KEY") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "BUMPPAY_CLUSTER",
            "\"${project.findProperty("BUMPPAY_CLUSTER") ?: "devnet"}\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // The shared APDU contract and transaction codec. Sharing this with the payer app is
    // what guarantees both ends agree about the bytes on the wire.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
