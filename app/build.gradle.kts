plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.librepocket.agent"
    compileSdk = 37
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    defaultConfig {
        // WARNING: applicationId is permanent once published (Play + F-Droid share it).
        // Never change it; use flavors for distribution differences instead.
        applicationId = "dev.librepocket.agent"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "dist"
    productFlavors {
        // Store-safe build: must never request high-risk permissions or declare
        // AccessibilityService / VpnService. The src/play manifest additionally
        // strips them with tools:node="remove" as a guard.
        // applicationId has no suffix: play owns the base ID (dev.librepocket.agent).
        create("play") {
            dimension = "dist"
        }
        // F-Droid / fully open-source build: OSS-only capabilities (ZXing /
        // Tesseract / LiteRT, no Play services). Co-installable via ".foss".
        // The a11y service overlay lands in Phase2 with FossAccessibilityService.
        create("foss") {
            dimension = "dist"
            applicationIdSuffix = ".foss"
        }
        // Direct-download build: all capabilities enabled (ML Kit + OSS stack).
        // Extra permissions and services come from the src/github manifest overlay.
        // Decision: co-installable via ".github" suffix (github succeeds the former full flavor).
        create("github") {
            dimension = "dist"
            applicationIdSuffix = ".github"
        }
    }

    buildTypes {
        debug {
            // Debug builds always carry "-debug" (release stays pure semver).
            versionNameSuffix = "-debug"
        }
        release {
            // Release versionName is pure semver (no flavor/build suffixes).
            // Kept unminified until the codebase has keep-rules to validate.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    // Streaming HTTP client (e.g. SSE transport). No wrapper code yet — infra only.
    implementation(libs.okhttp.sse)
    // Local persistence building blocks. Entities/DAOs are future work.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    implementation(libs.datastore.preferences)
    // Encrypted key/value + file storage.
    implementation(libs.security.crypto)
    // Long-term vault crypto type (P2 stub signature only; P1 ships EncryptedPrefsVault).
    implementation(libs.tink.android)
    // Session JSONL export/import codec.
    implementation(libs.serialization.json)
    // Chat/policy state + streaming (explicit; also pulled transitively by room/datastore).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // Unit tests (M3/M6).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // Unit tests (M2/M4/M5: Android-dependent vault/Room paths run under Robolectric).
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.test.core)
    // SSE replay transport for JVM unit tests (never hits the external network).
    testImplementation(libs.mockwebserver)
    // Instrumented tests (P1_SPEC §10.1 [I] layer; MockWebServer keeps them offline).
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.mockwebserver)
    // Phase1 vision/OCR stack (unreferenced stubs; wired in Phase2).
    // ML Kit is proprietary → github-only; OSS stack → foss + github.
    "githubImplementation"(libs.mlkit.barcode.scanning)
    "githubImplementation"(libs.mlkit.text.recognition)
    "fossImplementation"(libs.zxing.core)
    "githubImplementation"(libs.zxing.core)
    "fossImplementation"(libs.tess.two)
    "githubImplementation"(libs.tess.two)
    "fossImplementation"(libs.litert)
    "githubImplementation"(libs.litert)
}
