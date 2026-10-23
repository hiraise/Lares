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
) {
    enum class LogFormat { PLAIN, JSON }

    // Keeps secrets out of logs and exception messages.
    override fun toString(): String =
        "AppConfig(port=$port, db=$db, publicUrl=$publicUrl, logFormat=$logFormat, pushAllowedHosts=$pushAllowedHosts, setupCode=${if (setupCode == null) "unset" else "***"})"

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
            )
        }

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
