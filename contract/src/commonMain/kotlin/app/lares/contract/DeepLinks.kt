package app.lares.contract

/** A parsed `lares://` link from a QR code (architecture/auth.md § Invites, § QR login). */
sealed interface DeepLink {
    val serverUrl: String
    val code: String

    data class Invite(
        override val serverUrl: String,
        override val code: String,
    ) : DeepLink

    data class Login(
        override val serverUrl: String,
        override val code: String,
    ) : DeepLink
}

/**
 * Invite and login link payloads: `lares://invite?server=<urlencoded URL>&code=<code>` and the same with `login`.
 * Built by the server, parsed by the client after scanning a QR code.
 */
object DeepLinks {
    const val SCHEME = "lares"
    private const val INVITE = "invite"
    private const val LOGIN = "login"
    private const val HEX = "0123456789ABCDEF"

    fun invite(
        serverUrl: String,
        code: String,
    ): String = build(INVITE, serverUrl, code)

    fun login(
        serverUrl: String,
        code: String,
    ): String = build(LOGIN, serverUrl, code)

    /**
     * Returns the link, or `null` for anything else: another scheme or kind, missing parameters, malformed escapes,
     * or a server that is not an http(s) URL (a QR code may come from anywhere).
     */
    fun parse(uri: String): DeepLink? {
        val prefix = "$SCHEME://"
        val text = uri.trim()
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val rest = text.substring(prefix.length)
        val kind = rest.substringBefore('?').trimEnd('/').lowercase()
        if (!rest.contains('?')) return null
        val params = mutableMapOf<String, String>()
        for (pair in rest.substringAfter('?').split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('=')) ?: return null
            val value = decode(pair.substringAfter('=', missingDelimiterValue = "")) ?: return null
            params.getOrPut(name) { value }
        }
        val server = params["server"]?.takeIf(::isHttpUrl) ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            INVITE -> DeepLink.Invite(server, code)
            LOGIN -> DeepLink.Login(server, code)
            else -> null
        }
    }

    private fun build(
        kind: String,
        serverUrl: String,
        code: String,
    ): String = "$SCHEME://$kind?server=${encode(serverUrl)}&code=${encode(code)}"

    private fun isHttpUrl(value: String): Boolean {
        val lower = value.lowercase()
        val afterScheme =
            when {
                lower.startsWith("https://") -> value.substring("https://".length)
                lower.startsWith("http://") -> value.substring("http://".length)
                else -> return false
            }
        return afterScheme.substringBefore('/').isNotEmpty()
    }

    /** Percent-encodes everything except RFC 3986 unreserved characters, as UTF-8. */
    private fun encode(value: String): String =
        buildString {
            for (byte in value.encodeToByteArray()) {
                val char = (byte.toInt() and 0xFF).toChar()
                if ((char.code < 128 && char.isLetterOrDigit()) || char in "-._~") {
                    append(char)
                } else {
                    val b = byte.toInt() and 0xFF
                    append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
                }
            }
        }

    /** Decodes `%XX` escapes (UTF-8); returns `null` for malformed input. `+` stays a plus sign. */
    private fun decode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char == '%') {
                if (i + 2 >= value.length) return null
                val hi = value[i + 1].digitToIntOrNull(16) ?: return null
                val lo = value[i + 2].digitToIntOrNull(16) ?: return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                char.toString().encodeToByteArray().forEach(bytes::add)
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
