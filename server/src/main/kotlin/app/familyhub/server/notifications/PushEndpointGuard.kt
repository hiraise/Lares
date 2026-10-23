package app.familyhub.server.notifications

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF guard for push endpoints (UnifiedPush server recommendation): an endpoint must be an http(s) URL whose host
 * resolves only to global addresses, unless the host is in [allowedHosts] (`FAMILYHUB_PUSH_ALLOWED_HOSTS`), e.g. an
 * ntfy container on the internal Docker network. Checked on registration and again before every send.
 */
class PushEndpointGuard(
    allowedHosts: Set<String>,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    private val allowed = allowedHosts.map { it.lowercase() }.toSet()

    /** Returns `null` if the endpoint may be used, otherwise the reason. Blocking: resolves the host name. */
    fun check(endpoint: String): String? {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "not a URL"
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "scheme must be http or https"
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
        if (host.isNullOrEmpty()) return "no host"
        if (host in allowed) return null
        val addresses = runCatching { resolve(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) return "host does not resolve"
        return if (addresses.any(::isNonGlobal)) "host resolves to a non-global address" else null
    }

    companion object {
        /** Loopback, unspecified, private (RFC 1918, RFC 4193), shared (RFC 6598), link-local and multicast addresses. */
        fun isNonGlobal(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            return when (address) {
                is Inet4Address -> {
                    val first = bytes[0].toInt() and 0xFF
                    val second = bytes[1].toInt() and 0xFF
                    first == 0 || (first == 100 && second in 64..127)
                }

                // fc00::/7 unique local addresses.
                is Inet6Address -> {
                    (bytes[0].toInt() and 0xFE) == 0xFC
                }

                else -> {
                    true
                }
            }
        }
    }
}
