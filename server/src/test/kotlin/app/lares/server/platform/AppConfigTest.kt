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

    @Test
    fun mapTilesThroughTheCache() {
        val defaults = AppConfig.fromEnv(emptyMap()).location
        assertEquals("https://tiles.versatiles.org/assets/styles/colorful/style.json", defaults.mapStyleUrl)
        assertEquals("https://tiles.versatiles.org/assets/styles/colorful-dark/style.json", defaults.mapDarkStyleUrl)
        assertEquals("noto_sans_bold", defaults.labelFont)
        assertNull(defaults.mapProxyBase, "the dev server hands out direct URLs")
        assertEquals(defaults.mapStyleUrl, defaults.clientUrl(defaults.mapStyleUrl))

        val proxied =
            AppConfig
                .fromEnv(mapOf("LARES_PUBLIC_URL" to "https://family.example.org/", "LARES_MAP_PROXY" to "true"))
                .location
        assertEquals("https://family.example.org/map", proxied.mapProxyBase)
        assertEquals("https://family.example.org/map/assets/styles/colorful/style.json", proxied.clientUrl(proxied.mapStyleUrl))
        assertEquals(
            "https://tiles.example.com/light.json",
            proxied.clientUrl("https://tiles.example.com/light.json"),
            "other origins stay direct",
        )
        assertEquals("https://tiles.versatiles.org.evil/x.json", proxied.clientUrl("https://tiles.versatiles.org.evil/x.json"))

        val own =
            AppConfig
                .fromEnv(
                    mapOf(
                        "LARES_MAP_PROXY" to "true",
                        "LARES_MAP_UPSTREAM" to "https://tiles.openfreemap.org/",
                        "LARES_MAP_STYLE_URL" to "https://tiles.openfreemap.org/styles/liberty",
                        "LARES_MAP_LABEL_FONT" to " ",
                    ),
                ).location
        assertEquals("http://localhost:8080/map/styles/liberty", own.clientUrl(own.mapStyleUrl))
        assertNull(own.labelFont, "blank: the app's fallback font")

        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_MAP_PROXY" to "yes")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("LARES_MAP_UPSTREAM" to "ftp://tiles.example.com")) }
    }
}
