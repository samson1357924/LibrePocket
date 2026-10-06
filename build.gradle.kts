// Root build file: plugin versions are declared in gradle/libs.versions.toml
// and applied in module build files. No business logic lives here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
