import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.lares.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lares.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing only when a keystore is provided through the environment (release workflow); keystores are
    // never committed. Without it, `assembleRelease` produces an unsigned APK and releases ship the debug APK.
    val keystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    val releaseSigning =
        if (keystoreFile.isNullOrBlank()) {
            null
        } else {
            signingConfigs.create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.client.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime)
    // UnifiedPush connector only; never `embedded-fcm-distributor` (Google Play Services, ADR-0006).
    implementation(libs.unifiedpush.connector)
}
