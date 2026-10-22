package app.familyhub.server.platform

import java.net.URI

/** Server configuration, read from `FAMILYHUB_*` environment variables. Defaults suit local development. */
data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val publicUrl: String,
    val logFormat: LogFormat,
    /** Optional one-time code required by first-run setup (M0-03). */
    val setupCode: String?,
) {
    enum class LogFormat { PLAIN, JSON }

    // Keeps secrets out of logs and exception messages.
    override fun toString(): String =
        "AppConfig(port=$port, db=$db, publicUrl=$publicUrl, logFormat=$logFormat, setupCode=${if (setupCode == null) "unset" else "***"})"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(
                name: String,
                default: String,
            ): String = env[name]?.takeIf { it.isNotBlank() } ?: default

            val portText = value("FAMILYHUB_PORT", "8080")
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "FAMILYHUB_PORT must be a number in 1..65535, got '$portText'" }

            val dbUrl = value("FAMILYHUB_DB_URL", "jdbc:postgresql://localhost:5432/familyhub")
            require(dbUrl.startsWith("jdbc:postgresql://")) { "FAMILYHUB_DB_URL must start with jdbc:postgresql://" }

            val publicUrl = value("FAMILYHUB_PUBLIC_URL", "http://localhost:8080").trimEnd('/')
            require(isHttpUrl(publicUrl)) { "FAMILYHUB_PUBLIC_URL must be an absolute http(s) URL, got '$publicUrl'" }

            val logFormatText = value("FAMILYHUB_LOG_FORMAT", "plain")
            val logFormat =
                LogFormat.entries.firstOrNull { it.name.equals(logFormatText, ignoreCase = true) }
                    ?: throw IllegalArgumentException("FAMILYHUB_LOG_FORMAT must be 'plain' or 'json', got '$logFormatText'")

            return AppConfig(
                port = port,
                db =
                    DbConfig(
                        url = dbUrl,
                        user = value("FAMILYHUB_DB_USER", "familyhub"),
                        password = value("FAMILYHUB_DB_PASSWORD", "familyhub"),
                    ),
                publicUrl = publicUrl,
                logFormat = logFormat,
                setupCode = env["FAMILYHUB_SETUP_CODE"]?.takeIf { it.isNotBlank() },
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
