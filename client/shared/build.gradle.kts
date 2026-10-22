import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
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
        namespace = "app.familyhub.client"
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
            implementation(projects.contract)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
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

compose.resources {
    packageOfResClass = "app.familyhub.client.resources"
    publicResClass = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
