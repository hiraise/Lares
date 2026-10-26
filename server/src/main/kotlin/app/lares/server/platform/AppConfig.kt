package app.lares.server.platform

import java.net.URI

/** Server configuration, read from `LARES_*` environment variables. Defaults suit local development. */
data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val publicUrl: String,
    val logFormat: LogFormat,
    /** Optional one-time code required by first-run setup (M0-03). */
    val setupCode: String?,
    /** Push endpoint hosts allowed despite resolving to non-global addresses, e.g. an internal ntfy (M0-09). */
    val pushAllowedHosts: Set<String> = emptySet(),
    /** Map styles and the address lookup (architecture/location.md § Configuration). */
    val location: LocationConfigValues = LocationConfigValues(),
) {
    enum class LogFormat { PLAIN, JSON }

    // Keeps secrets out of logs and exception messages.
    override fun toString(): String =
        "AppConfig(port=$port, db=$db, publicUrl=$publicUrl, logFormat=$logFormat, pushAllowedHosts=$pushAllowedHosts, location=$location, setupCode=${if (setupCode == null) "unset" else "***"})"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(
                name: String,
                default: String,
            ): String = env[name]?.takeIf { it.isNotBlank() } ?: default

            val portText = value("LARES_PORT", "8080")
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "LARES_PORT must be a number in 1..65535, got '$portText'" }

            val dbUrl = value("LARES_DB_URL", "jdbc:postgresql://localhost:5432/lares")
            require(dbUrl.startsWith("jdbc:postgresql://")) { "LARES_DB_URL must start with jdbc:postgresql://" }

            val publicUrl = value("LARES_PUBLIC_URL", "http://localhost:8080").trimEnd('/')
            require(isHttpUrl(publicUrl)) { "LARES_PUBLIC_URL must be an absolute http(s) URL, got '$publicUrl'" }

            val logFormatText = value("LARES_LOG_FORMAT", "plain")
            val logFormat =
                LogFormat.entries.firstOrNull { it.name.equals(logFormatText, ignoreCase = true) }
                    ?: throw IllegalArgumentException("LARES_LOG_FORMAT must be 'plain' or 'json', got '$logFormatText'")

            val mapStyleUrl = value("LARES_MAP_STYLE_URL", LocationConfigValues.DEFAULT_MAP_STYLE_URL).trim()
            require(isLocationUrl(mapStyleUrl)) { "LARES_MAP_STYLE_URL must be an https URL, got '$mapStyleUrl'" }
            val mapDarkStyleUrl = value("LARES_MAP_STYLE_DARK_URL", LocationConfigValues.DEFAULT_MAP_DARK_STYLE_URL).trim()
            require(isLocationUrl(mapDarkStyleUrl)) { "LARES_MAP_STYLE_DARK_URL must be an https URL, got '$mapDarkStyleUrl'" }
            val mapUpstream = value("LARES_MAP_UPSTREAM", LocationConfigValues.DEFAULT_MAP_UPSTREAM).trim().trimEnd('/')
            require(isLocationUrl(mapUpstream)) { "LARES_MAP_UPSTREAM must be an https URL, got '$mapUpstream'" }
            val mapProxyText = value("LARES_MAP_PROXY", "false").trim().lowercase()
            require(mapProxyText in setOf("true", "false")) { "LARES_MAP_PROXY must be 'true' or 'false', got '$mapProxyText'" }
            // Not read with value(): set but empty switches addresses off.
            val geocoderUrl =
                (env["LARES_GEOCODER_URL"] ?: LocationConfigValues.DEFAULT_GEOCODER_URL).trim().trimEnd('/').takeIf { it.isNotEmpty() }
            require(
                geocoderUrl == null || isLocationUrl(geocoderUrl),
            ) { "LARES_GEOCODER_URL must be an https URL or empty, got '$geocoderUrl'" }

            return AppConfig(
                port = port,
                db =
                    DbConfig(
                        url = dbUrl,
                        user = value("LARES_DB_USER", "lares"),
                        password = value("LARES_DB_PASSWORD", "lares"),
                    ),
                publicUrl = publicUrl,
                logFormat = logFormat,
                setupCode = env["LARES_SETUP_CODE"]?.takeIf { it.isNotBlank() },
                pushAllowedHosts =
                    env["LARES_PUSH_ALLOWED_HOSTS"]
                        .orEmpty()
                        .split(',')
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                location =
                    LocationConfigValues(
                        mapStyleUrl = mapStyleUrl,
                        mapDarkStyleUrl = mapDarkStyleUrl,
                        mapUpstream = mapUpstream,
                        mapProxyBase = if (mapProxyText == "true") "$publicUrl/map" else null,
                        labelFont =
                            (env["LARES_MAP_LABEL_FONT"] ?: LocationConfigValues.DEFAULT_LABEL_FONT).trim().takeIf { it.isNotEmpty() },
                        geocoderUrl = geocoderUrl,
                        geocoderEmail = env["LARES_GEOCODER_EMAIL"]?.trim()?.takeIf { it.isNotEmpty() },
                    ),
            )
        }

        /** https, or http for a local test server. */
        private fun isLocationUrl(text: String): Boolean =
            runCatching {
                val uri = URI(text)
                val host = uri.host.orEmpty()
                host.isNotEmpty() &&
                    (uri.scheme == "https" || (uri.scheme == "http" && host in setOf("localhost", "127.0.0.1")))
            }.getOrDefault(false)

        private fun isHttpUrl(text: String): Boolean =
            runCatching {
                val uri = URI(text)
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
            }.getOrDefault(false)
    }
}

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String = "DbConfig(url=$url, user=$user, password=***)"
}

/**
 * Map styles for the clients and the Nominatim-compatible address lookup (null = addresses off). With [mapProxyBase]
 * (`<public URL>/map`, ADR-0029) styles on [mapUpstream] are handed out through the server's map cache.
 */
data class LocationConfigValues(
    val mapStyleUrl: String = DEFAULT_MAP_STYLE_URL,
    val mapDarkStyleUrl: String = DEFAULT_MAP_DARK_STYLE_URL,
    val mapUpstream: String = DEFAULT_MAP_UPSTREAM,
    val mapProxyBase: String? = null,
    /** The font of pin initials and place labels; a style's glyph server knows only its own names. */
    val labelFont: String? = DEFAULT_LABEL_FONT,
    val geocoderUrl: String? = DEFAULT_GEOCODER_URL,
    val geocoderEmail: String? = null,
) {
    // The contact address stays out of logs.
    override fun toString(): String =
        "LocationConfigValues(mapStyleUrl=$mapStyleUrl, mapDarkStyleUrl=$mapDarkStyleUrl, mapUpstream=$mapUpstream, " +
            "mapProxyBase=$mapProxyBase, labelFont=$labelFont, geocoderUrl=$geocoderUrl, " +
            "geocoderEmail=${if (geocoderEmail == null) "unset" else "***"})"

    /** The URL a phone loads: through the map cache when [url] is on the upstream, else unchanged. */
    fun clientUrl(url: String): String {
        val base = mapProxyBase ?: return url
        val prefix = "$mapUpstream/"
        return if (url.startsWith(prefix)) "$base/${url.removePrefix(prefix)}" else url
    }

    companion object {
        const val DEFAULT_MAP_UPSTREAM = "https://tiles.versatiles.org"
        const val DEFAULT_MAP_STYLE_URL = "$DEFAULT_MAP_UPSTREAM/assets/styles/colorful/style.json"
        const val DEFAULT_MAP_DARK_STYLE_URL = "$DEFAULT_MAP_UPSTREAM/assets/styles/colorful-dark/style.json"
        const val DEFAULT_LABEL_FONT = "noto_sans_bold"
        const val DEFAULT_GEOCODER_URL = "https://nominatim.openstreetmap.org"
    }
}
