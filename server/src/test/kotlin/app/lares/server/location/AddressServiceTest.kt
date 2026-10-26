package app.lares.server.location

import app.lares.contract.api.location.AddressResult
import app.lares.server.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Caches, rounding and the one-request-per-interval gate of [AddressService] (ADR-0026), with a counting lookup. */
class AddressServiceTest {
    private class CountingLookup(
        var label: String? = "Тверская улица, 7",
    ) : AddressLookup {
        val calls = CopyOnWriteArrayList<Pair<String, TimeSource.Monotonic.ValueTimeMark>>()

        override suspend fun reverse(
            latitude: Double,
            longitude: Double,
            language: String,
        ): String? {
            calls += "$latitude,$longitude,$language" to TimeSource.Monotonic.markNow()
            return label
        }

        override suspend fun search(
            query: String,
            language: String,
            near: Pair<Double, Double>?,
        ): List<AddressResult>? {
            calls += "$query|$near" to TimeSource.Monotonic.markNow()
            return label?.let { listOf(AddressResult(it, 55.758, 37.6107)) }
        }
    }

    private val clock = TestClock()

    @Test
    fun loc014RoundedCoordinatesShareOneCacheEntry() =
        runBlocking {
            val lookup = CountingLookup()
            val service = AddressService(lookup, clock, interval = 10.milliseconds)
            assertEquals("Тверская улица, 7", service.reverse(55.75581, 37.61731, "ru"))
            assertEquals("Тверская улица, 7", service.reverse(55.75584, 37.61729, "ru"))
            assertEquals(listOf("55.7558,37.6173,ru"), lookup.calls.map { it.first })
            // Another language is another entry; the cache keeps a week.
            service.reverse(55.75581, 37.61731, "en")
            clock.advance(6.days)
            service.reverse(55.7558, 37.6173, "ru")
            assertEquals(2, lookup.calls.size)
            clock.advance(2.days)
            service.reverse(55.7558, 37.6173, "ru")
            assertEquals(3, lookup.calls.size)
        }

    @Test
    fun adr0026OneRequestPerIntervalForTheWholeServer() =
        runBlocking {
            val lookup = CountingLookup()
            val service = AddressService(lookup, clock, interval = 300.milliseconds)
            listOf(
                async { service.reverse(55.0, 37.0, "ru") },
                async { service.search("Тверская 7", "ru", null) },
            ).awaitAll()
            val (first, second) = lookup.calls.map { it.second }.sorted()
            assertTrue(second - first >= 290.milliseconds, "second request after ${second - first}")
        }

    @Test
    fun adr0026ACallerThatWouldWaitTooLongGetsNothing() =
        runBlocking {
            val lookup = CountingLookup()
            val service = AddressService(lookup, clock, interval = 400.milliseconds, maxWait = 600.milliseconds)
            val labels =
                listOf(55.0, 56.0, 57.0)
                    .map { lat -> async { service.reverse(lat, 37.0, "ru") } }
                    .awaitAll()
            assertEquals(2, lookup.calls.size, "the third would wait 800 ms")
            assertEquals(1, labels.count { it == null })
        }

    @Test
    fun adr0026FailuresAreCachedForTenMinutes() =
        runBlocking {
            val lookup = CountingLookup(label = null)
            val service = AddressService(lookup, clock, interval = 10.milliseconds)
            assertNull(service.reverse(55.0, 37.0, "ru"))
            assertNull(service.reverse(55.0, 37.0, "ru"))
            assertEquals(emptyList(), service.search("Тверская 7", "ru", 55.81 to 37.64))
            assertEquals(emptyList(), service.search("тверская 7", "ru", 55.79 to 37.6))
            assertEquals(2, lookup.calls.size)
            // The area is rounded to 0.1° before it is sent.
            assertEquals("Тверская 7|(55.8, 37.6)", lookup.calls.last().first)

            lookup.label = "Тверская улица, 7"
            clock.advance(11.minutes)
            assertEquals("Тверская улица, 7", service.reverse(55.0, 37.0, "ru"))
            assertEquals(3, lookup.calls.size)
        }

    @Test
    fun loc014GeocoderOffGivesNothingWithoutRequests() =
        runBlocking {
            val service = AddressService(null, clock)
            assertFalse(service.enabled)
            assertNull(service.reverse(55.0, 37.0, "ru"))
            assertEquals(emptyList(), service.search("Тверская 7", "ru", null))
        }
}
