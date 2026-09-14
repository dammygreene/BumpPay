// :app — the payer's wallet-holding device.
//
// This is the phone that gets tapped. It emulates a contactless card (HCE), holds the
// pre-authorised session key, and returns a signed transaction over the NFC link so that
// it never needs connectivity at tap time.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.bumppay"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bumppay"
        minSdk = 24 // HCE's floor, not a preference.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Runtime configuration, read from gradle.properties or ~/.gradle/gradle.properties.
        // A Helius key belongs in the latter; it is not a secret worth committing, but it
        // is a billed key and a leaked one costs real money.
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
            // Keeps a debug build installable alongside a release build on the same demo
            // phone, which matters when comparing "does it work in release?" mid-week.
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing config is injected only when the keystore properties exist, so that
            // `assembleRelease` still runs on a machine without the keystore. The publishing
            // CLI rejects debug builds, so shipping the fallback artifact would be a silent
            // disqualification — the README calls this out explicitly.
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

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // BouncyCastle ships signature files that break the APK packager otherwise.
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Solana Mobile Stack: wallet connection and owner-signed transactions.
    implementation(libs.mwa.clientlib.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
