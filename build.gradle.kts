// Top-level build file. Plugins are declared here with `apply false` so that each
// module can opt in with its own `alias(...)`; this keeps plugin versions in one place.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
