package app.lares.server.location

import app.lares.contract.api.location.AddressResult
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Addresses through the configured geocoder (ADR-0026): in-memory LRU caches, at most one request per [interval] for the
 * whole server, callers that would wait longer than [maxWait] get nothing. Failures and empty answers are kept for
 * 10 minutes, so a broken service is not asked again on every map refresh. [lookup] null = addresses off.
 */
class AddressService(
    private val lookup: AddressLookup?,
    private val clock: Clock,
    private val interval: Duration = 1.seconds,
    private val maxWait: Duration = 5.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val enabled: Boolean get() = lookup != null

    private class Entry<T>(
        val value: T,
        val expiresAt: Instant,
    )

    private class Lru<K, V>(
        private val capacity: Int,
    ) {
        private val map = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

        @Synchronized
        fun get(
            key: K,
            now: Instant,
        ): Entry<V>? {
            val entry = map[key] ?: return null
            if (entry.expiresAt <= now) {
                map.remove(key)
                return null
            }
            return entry
        }

        @Synchronized
        fun put(
            key: K,
            entry: Entry<V>,
        ) {
            map[key] = entry
            if (map.size > capacity) map.remove(map.keys.first())
        }
    }

    private val reverseCache = Lru<String, String?>(REVERSE_ENTRIES)
    private val searchCache = Lru<String, List<AddressResult>>(SEARCH_ENTRIES)
    private val slotLock = Any()
    private var nextSlot: TimeMark? = null

    /** The street address near the point; the coordinates are rounded to 4 decimals (≈ 11 m) before anything else. */
    suspend fun reverse(
        latitude: Double,
        longitude: Double,
        language: String,
    ): String? {
        val lookup = lookup ?: return null
        val lat = round4(latitude)
        val lon = round4(longitude)
        val key = "$lat,$lon,$language"
        reverseCache.get(key, clock.now())?.let { return it.value }
        if (!waitForSlot()) return null
        val label = lookup.reverse(lat, lon, language)
        reverseCache.put(key, Entry(label, clock.now() + if (label == null) FAILURE_TTL else REVERSE_TTL))
        return label
    }

    /** Address search ([query] already trimmed); [near] rounded to 0.1° before it is sent or used as a key. */
    suspend fun search(
        query: String,
        language: String,
        near: Pair<Double, Double>?,
    ): List<AddressResult> {
        val lookup = lookup ?: return emptyList()
        val area = near?.let { (lat, lon) -> round1(lat) to round1(lon) }
        val key = "${query.lowercase(Locale.ROOT)}|$language|${area?.first},${area?.second}"
        searchCache.get(key, clock.now())?.let { return it.value }
        if (!waitForSlot()) return emptyList()
        val results = lookup.search(query, language, area)
        val ttl = if (results.isNullOrEmpty()) FAILURE_TTL else SEARCH_TTL
        searchCache.put(key, Entry(results.orEmpty(), clock.now() + ttl))
        return results.orEmpty()
    }

    /** Reserves the next request slot and waits for it; false (nothing reserved) when it is more than [maxWait] away. */
    private suspend fun waitForSlot(): Boolean {
        val wait =
            synchronized(slotLock) {
                val now = timeSource.markNow()
                val slot = nextSlot?.takeIf { it.hasNotPassedNow() } ?: now
                val wait = -slot.elapsedNow()
                if (wait > maxWait) return false
                nextSlot = slot + interval
                wait
            }
        if (wait.isPositive()) delay(wait)
        return true
    }

    companion object {
        const val REVERSE_ENTRIES = 5_000
        const val SEARCH_ENTRIES = 500
        val REVERSE_TTL = 7.days
        val SEARCH_TTL = 1.days
        val FAILURE_TTL = 10.minutes

        fun round4(value: Double): Double = (value * 10_000).roundToLong() / 10_000.0

        private fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0
    }
}
