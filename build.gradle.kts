plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
}

// Source trees as file trees that skip build output: a plain "**/*.kt" walks the build directories too and fails when a
// compile task running in parallel replaces class files during the walk.
fun sources(include: String) =
    fileTree(rootDir) {
        include(include)
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/node_modules/**", "**/.venv/**")
    }

spotless {
    kotlin {
        target(sources("**/*.kt"))
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(sources("**/*.kts"))
        ktlint(libs.versions.ktlint.get())
    }
}
