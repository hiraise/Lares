import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// Compose UI tests on the JVM need the desktop runtime for the host OS.
val composeDesktopRuntime =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        when {
            os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> libs.compose.desktop.macos.arm64
            os.contains("linux") && (arch == "amd64" || arch == "x86_64") -> libs.compose.desktop.linux.x64
            else -> error("No Compose desktop runtime in the version catalog for $os/$arch")
        }
    }

kotlin {
    jvmToolchain(21)

    android {
        namespace = "app.lares.client"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
        withHostTest { }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Contract types are part of the client core's public API (repositories, session, sync).
            api(projects.contract)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.coroutines)
            api(libs.koin.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.jb.navigation.compose)
            implementation(libs.jb.navigationevent.compose)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            api(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.qrose)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
            // The map (ADR-0027); never the location-runtime-gms / -hms artifacts.
            implementation(libs.maplibre.compose)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(project.dependencies.platform(libs.junit.bom))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.compose.ui.test)
            implementation(composeDesktopRuntime)
        }
    }
}

sqldelight {
    databases {
        create("LaresDb") {
            packageName.set("app.lares.client.db")
        }
    }
}

compose.resources {
    packageOfResClass = "app.lares.client.resources"
    publicResClass = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // CI keeps no test reports: print the whole failure, causes included.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
