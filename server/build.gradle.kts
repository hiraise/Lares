plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.familyhub.server.ApplicationKt")
}

dependencies {
    implementation(projects.contract)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
