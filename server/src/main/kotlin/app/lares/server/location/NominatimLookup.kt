package app.lares.server.location

import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.AddressResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Street addresses for coordinates and address search (LOC-014, LOC-024, ADR-0026). */
interface AddressLookup {
    /** A short label such as «Тверская улица, 7»; null when nothing is known or the service failed. */
    suspend fun reverse(
        latitude: Double,
        longitude: Double,
        language: String,
    ): String?

    /** At most 5 results; null when the service failed. [near] prefers results around that point (not bounded). */
    suspend fun search(
        query: String,
        language: String,
        near: Pair<Double, Double>?,
    ): List<AddressResult>?
}

/**
 * A Nominatim-compatible service at [baseUrl] (architecture/location.md § Addresses). Unknown response shapes give
 * null; coordinates and queries are never logged.
 */
class NominatimLookup(
    private val baseUrl: String,
    private val email: String?,
    private val userAgent: String,
    private val timeout: java.time.Duration = TIMEOUT,
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) : AddressLookup {
    private val log = KotlinLogging.logger {}

    override suspend fun reverse(
        latitude: Double,
        longitude: Double,
        language: String,
    ): String? {
        val response =
            get(
                "reverse",
                listOf(
                    "format" to "jsonv2",
                    "lat" to coordinate(latitude),
                    "lon" to coordinate(longitude),
                    "zoom" to "18",
                    "layer" to "address",
                    "addressdetails" to "1",
                    "accept-language" to language,
                ),
            ) as? JsonObject ?: return null
        return (response["address"] as? JsonObject)?.let(::shortLabel)
    }

    override suspend fun search(
        query: String,
        language: String,
        near: Pair<Double, Double>?,
    ): List<AddressResult>? {
        val parameters =
            buildList {
                add("format" to "jsonv2")
                add("q" to query)
                add("limit" to MAX_RESULTS.toString())
                add("addressdetails" to "1")
                add("accept-language" to language)
                if (near != null) {
                    val (lat, lon) = near
                    // left,top,right,bottom; a preference, not a bound.
                    val box = listOf(lon - VIEWBOX_DEG, lat + VIEWBOX_DEG, lon + VIEWBOX_DEG, lat - VIEWBOX_DEG)
                    add("viewbox" to box.joinToString(",", transform = ::coordinate))
                }
            }
        val response = get("search", parameters) as? JsonArray ?: return null
        return response
            .mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val lat = obj.string("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = obj.string("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                val address = obj["address"] as? JsonObject
                val short = address?.let(::shortLabel) ?: obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val city = address?.let { a -> CITY_KEYS.firstNotNullOfOrNull { a.string(it) } }
                val label = if (city == null || city == short) short else "$short, $city"
                AddressResult(label, lat, lon)
            }.take(MAX_RESULTS)
    }

    /** `road, house_number`; without a road the first of the smaller-to-larger area names. */
    private fun shortLabel(address: JsonObject): String? {
        val road = address.string("road")
        if (road != null) return address.string("house_number")?.let { "$road, $it" } ?: road
        return FALLBACK_KEYS.firstNotNullOfOrNull { address.string(it) }
    }

    private suspend fun get(
        path: String,
        parameters: List<Pair<String, String>>,
    ): kotlinx.serialization.json.JsonElement? {
        val all = if (email != null) parameters + ("email" to email) else parameters
        val query = all.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return try {
            val request =
                HttpRequest
                    .newBuilder(URI("$baseUrl/$path?$query"))
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() !in 200..299) {
                log.warn { "Address service answered ${response.statusCode()} to $path" }
                null
            } else {
                LaresJson.parseToJsonElement(response.body())
            }
        } catch (e: Exception) {
            log.warn { "Address service $path failed: ${e::class.simpleName}" }
            null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        val TIMEOUT: java.time.Duration = 5.seconds.toJavaDuration()
        const val MAX_RESULTS = 5
        const val VIEWBOX_DEG = 0.5
        private val FALLBACK_KEYS = listOf("pedestrian", "footway", "neighbourhood", "suburb", "village", "town", "city")
        private val CITY_KEYS = listOf("city", "town", "village")

        fun userAgent(version: String) = "Lares/$version (+https://github.com/hiraise/Lares)"

        private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    }
}
