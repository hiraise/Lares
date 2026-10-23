// Load simulation CLI (plan M0-11): a simulated family of devices against a running server.
//   ./gradlew :tools:loadsim:installDist && tools/loadsim/build/install/loadsim/bin/loadsim --help
// Usually run through deploy/scripts/loadtest.sh, which also starts the stack and samples memory.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.familyhub.tools.loadsim.MainKt")
    applicationName = "loadsim"
}

dependencies {
    implementation(projects.contract)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
