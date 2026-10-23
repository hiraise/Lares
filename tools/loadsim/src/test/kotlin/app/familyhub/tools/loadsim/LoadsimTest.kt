package app.familyhub.tools.loadsim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LoadsimTest {
    @Test
    fun `parses flags and durations`() {
        val config =
            Config.parse(
                listOf(
                    "--server",
                    "https://localhost/",
                    "--devices",
                    "4",
                    "--duration",
                    "5m",
                    "--warmup",
                    "0",
                    "--burst",
                    "30s",
                    "--insecure",
                ),
            )
        assertEquals("https://localhost", config.server)
        assertEquals(4, config.devices)
        assertEquals(2, config.members)
        assertEquals(5.minutes, config.duration)
        assertEquals(Duration.ZERO, config.warmup)
        assertEquals(30.seconds, config.burst)
        assertEquals(true, config.insecure)
        assertEquals(Duration.parse("1h30m"), Config.duration("--duration", "1h30m"))
    }

    @Test
    fun `rejects bad options`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--devices", "0")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--duration", "soon")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--admin-login", "a")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--whatever")) }
    }

    @Test
    fun `burst moves to the middle when it does not fit`() {
        assertEquals(5.minutes, Config().effectiveBurstAt)
        assertEquals(2.minutes, Config(duration = 5.minutes, burst = 60.seconds).effectiveBurstAt)
        assertNull(Config(burst = Duration.ZERO).effectiveBurstAt)
        assertNull(Config(duration = 30.seconds, burst = 60.seconds).effectiveBurstAt)
    }

    @Test
    fun `nearest-rank percentiles`() {
        val values = (1..100).map { it.toDouble() }
        assertEquals(50.0, EndpointStats.percentile(values, 0.50))
        assertEquals(95.0, EndpointStats.percentile(values, 0.95))
        assertEquals(100.0, EndpointStats.percentile(values, 1.0))
        assertEquals(7.0, EndpointStats.percentile(listOf(7.0), 0.99))
        assertEquals(0.0, EndpointStats.percentile(emptyList(), 0.5))
    }

    @Test
    fun `stats count errors and leave WebSocket opens out of ALL`() {
        val samples =
            listOf(
                Sample(1, Phase.STEADY, "GET /sync/changes", 200, 10.0),
                Sample(2, Phase.STEADY, "GET /sync/changes", 500, 30.0),
                Sample(3, Phase.STEADY, DeviceApi.EVENTS, 101, 900.0),
            )
        val table = EndpointStats.table(samples).associateBy { it.endpoint }
        assertEquals(2, table.getValue("GET /sync/changes").count)
        assertEquals(1, table.getValue("GET /sync/changes").errors)
        assertEquals(2, table.getValue(EndpointStats.ALL).count)
        assertEquals(30.0, table.getValue(EndpointStats.ALL).max)
    }
}
