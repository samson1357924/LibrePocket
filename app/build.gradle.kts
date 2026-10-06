plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.librepocket.agent"
    compileSdk = 37

    defaultConfig {
        // WARNING: applicationId is permanent once published (Play + F-Droid share it).
        // Never change it; use flavors for distribution differences instead.
        applicationId = "dev.librepocket.agent"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "dist"
    productFlavors {
        // Store-safe build: must never request high-risk permissions or declare
        // AccessibilityService / VpnService. The src/play manifest additionally
        // strips them with tools:node="remove" as a guard.
        create("play") {
            dimension = "dist"
        }
        // Full build for F-Droid / direct download: all capabilities enabled.
        // Extra permissions and services come from the src/full manifest overlay.
        create("full") {
            dimension = "dist"
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        debug {
            // Skeleton only; no shrink rules needed yet.
        }
        release {
            // Kept unminified until the codebase has keep-rules to validate.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
}
