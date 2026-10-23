import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version comes from `version` in gradle.properties (bumped by tools/dev/release.sh). versionCode is derived from it
// (MAJOR * 1_000_000 + MINOR * 1_000 + PATCH) so every release increases it without a second number to maintain.
val appVersionName = project.version.toString()
val appVersionCode =
    Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersionName)?.destructured?.let { (major, minor, patch) ->
        major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    } ?: error("version=$appVersionName in gradle.properties is not MAJOR.MINOR.PATCH")

android {
    namespace = "app.lares.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lares.android"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Release signing only when a keystore is provided through the environment (release workflow, ADR-0019); the
    // keystore is never committed. Without it, `assembleRelease` produces an unsigned APK, which the release workflow
    // refuses to publish.
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
