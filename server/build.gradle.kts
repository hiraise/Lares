plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.lares.server.ApplicationKt")
}

// The server version comes from Gradle `version` (gradle.properties) and is read at runtime from a generated resource.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    val serverVersion = project.version.toString()
    inputs.property("version", serverVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("lares-build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$serverVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo)
}

dependencies {
    implementation(projects.contract)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.webpush)

    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // The test harness (testing/) is shared with :integration-tests as test fixtures.
    testFixturesApi(projects.contract)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.ktor.server.test.host)
    testFixturesApi(libs.ktor.client.content.negotiation)
    testFixturesApi(libs.ktor.serialization.kotlinx.json)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.exposed.core)
    testFixturesImplementation(libs.exposed.jdbc)
    testFixturesImplementation(libs.hikaricp)

    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
