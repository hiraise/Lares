package app.familyhub.server.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppConfigTest {
    @Test
    fun defaultsSuitLocalDevelopment() {
        val config = AppConfig.fromEnv(emptyMap())
        assertEquals(8080, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/familyhub", config.db.url)
        assertEquals("familyhub", config.db.user)
        assertEquals("http://localhost:8080", config.publicUrl)
        assertEquals(AppConfig.LogFormat.PLAIN, config.logFormat)
        assertNull(config.setupCode)
    }

    @Test
    fun readsEnvironment() {
        val config =
            AppConfig.fromEnv(
                mapOf(
                    "FAMILYHUB_PORT" to "9000",
                    "FAMILYHUB_PUBLIC_URL" to "https://family.example.org/",
                    "FAMILYHUB_LOG_FORMAT" to "json",
                    "FAMILYHUB_SETUP_CODE" to "123456",
                ),
            )
        assertEquals(9000, config.port)
        assertEquals("https://family.example.org", config.publicUrl)
        assertEquals(AppConfig.LogFormat.JSON, config.logFormat)
        assertEquals("123456", config.setupCode)
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("FAMILYHUB_PORT" to "70000")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("FAMILYHUB_PORT" to "abc")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("FAMILYHUB_DB_URL" to "mysql://x")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("FAMILYHUB_PUBLIC_URL" to "family.example.org")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("FAMILYHUB_LOG_FORMAT" to "xml")) }
    }

    @Test
    fun toStringHidesSecrets() {
        val config = AppConfig.fromEnv(mapOf("FAMILYHUB_DB_PASSWORD" to "s3cret", "FAMILYHUB_SETUP_CODE" to "654321"))
        val text = config.toString()
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("654321"))
    }
}
