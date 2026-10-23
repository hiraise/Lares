package app.lares.server.notifications

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushEndpointGuardTest {
    private val dns =
        mapOf(
            "ntfy.example.org" to "93.184.215.14",
            "evil.example.org" to "192.168.1.10",
            "ntfy" to "172.18.0.5",
        )
    private val guard = PushEndpointGuard(setOf("ntfy")) { host -> listOf(InetAddress.getByName(dns[host] ?: host)) }

    @Test
    fun globalHostsAndAllowListedHostsPass() {
        assertNull(guard.check("https://ntfy.example.org/upAbc?up=1"))
        assertNull(guard.check("http://ntfy:80/upAbc"))
    }

    @Test
    fun nonGlobalAddressesAreRefused() {
        listOf(
            "https://evil.example.org/up",
            "http://127.0.0.1/up",
            "http://10.0.0.1/up",
            "http://172.16.0.1/up",
            "http://169.254.169.254/latest",
            "http://100.64.0.1/up",
            "http://[::1]/up",
            "http://[fd00::1]/up",
            "http://[fe80::1]/up",
            "http://0.0.0.0/up",
        ).forEach { assertNotNull(guard.check(it), it) }
    }

    @Test
    fun malformedEndpointsAreRefused() {
        assertEquals("scheme must be http or https", guard.check("ftp://ntfy.example.org/up"))
        assertNotNull(guard.check("not a url"))
        assertNotNull(guard.check("https:///up"))
    }
}
