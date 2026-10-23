package app.lares.server.platform

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
        assertEquals("jdbc:postgresql://localhost:5432/lares", config.db.url)
        assertEquals("lares", config.db.user)
        assertEquals("http://localhost:8080", config.publicUrl)
        assertEquals(AppConfig.LogFormat.PLAIN, config.logFormat)
        assertNull(config.setupCode)
    }

    @Test
    fun readsEnvironment() {
        val config =
            AppConfig.fromEnv(
                mapOf(
                    "LARES_PORT" to "9000",
                    "LARES_PUBLIC_URL" to "https://family.example.org/",
                    "LARES_LOG_FORMAT" to "json",
                    "LARES_SETUP_CODE" to "123456",
                ),
            )
        assertEquals(9000, config.port)
        assertEquals("https://family.example.org", config.publicUrl)
        assertEquals(AppConfig.LogFormat.JSON, config.logFormat)
        assertEquals("123456", config.setupCode)
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_PORT" to "70000")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_PORT" to "abc")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_DB_URL" to "mysql://x")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_PUBLIC_URL" to "family.example.org")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_LOG_FORMAT" to "xml")) }
    }

    @Test
    fun toStringHidesSecrets() {
        val config = AppConfig.fromEnv(mapOf("LARES_DB_PASSWORD" to "s3cret", "LARES_SETUP_CODE" to "654321"))
        val text = config.toString()
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("654321"))
    }
}
