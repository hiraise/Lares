// Multi-device sync tests: the real server (embedded Netty, Testcontainers PostgreSQL) and several client cores in one JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(projects.server)
    testImplementation(testFixtures(projects.server))
    testImplementation(projects.client.shared)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.koin.core)
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
