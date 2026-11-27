// =====================================================================
// Top-level build file.
// We only DECLARE the plugins here (apply false); the app module applies them.
// Versions live in gradle/libs.versions.toml.
// =====================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
