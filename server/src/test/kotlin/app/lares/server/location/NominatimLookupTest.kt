package app.lares.server.location

import app.lares.contract.api.location.AddressResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The Nominatim client against a fake service and recorded responses (LOC-014, LOC-024, ADR-0026). */
class NominatimLookupTest {
    private val fake = FakeNominatim()

    private fun lookup(email: String? = "family@example.com") =
        NominatimLookup(fake.url, email, NominatimLookup.userAgent("1.2.3"), timeout = 500.milliseconds.toJavaDuration())

    @AfterTest
    fun stop() = fake.close()

    @Test
    fun loc014ReverseSendsTheParametersAndBuildsAStreetLabel() =
        runBlocking {
            assertEquals("Тверская улица, 7", lookup().reverse(55.758, 37.6107, "ru"))
            val request = fake.requests.single()
            assertEquals("reverse", request.path)
            assertEquals(
                mapOf(
                    "format" to "jsonv2",
                    "lat" to "55.758",
                    "lon" to "37.6107",
                    "zoom" to "18",
                    "layer" to "address",
                    "addressdetails" to "1",
                    "accept-language" to "ru",
                    "email" to "family@example.com",
                ),
                request.parameters,
            )
            assertEquals("Lares/1.2.3 (+https://github.com/hiraise/Lares)", request.headers["user-agent"])
        }

    @Test
    fun loc014WithoutARoadTheLabelIsTheAreaAndWithoutAnythingNull() =
        runBlocking {
            fake.responses = { FakeNominatim.sample("reverse-park.json") }
            assertEquals("Якиманка", lookup(email = null).reverse(55.73, 37.6, "en"))
            assertEquals(null, fake.requests.single().parameters["email"])

            fake.responses = { """{"error":"Unable to geocode"}""" }
            assertNull(lookup().reverse(0.0, 0.0, "en"))
            fake.responses = { """["unexpected"]""" }
            assertNull(lookup().reverse(0.0, 0.0, "en"))
        }

    @Test
    fun loc024SearchReturnsLabelsWithTheCityAndPrefersTheArea() =
        runBlocking {
            val results = lookup().search("Тверская 7", "ru", 55.8 to 37.6)
            assertEquals(
                listOf(
                    AddressResult("Тверская улица, 7, Москва", 55.7580891, 37.6107024),
                    AddressResult("Тверская улица, Тверь", 56.8587, 35.9176),
                    AddressResult("Москва", 55.75, 37.61),
                ),
                results,
            )
            val parameters = fake.requests.single().parameters
            assertEquals("Тверская 7", parameters["q"])
            assertEquals("5", parameters["limit"])
            assertEquals("37.1,56.3,38.1,55.3", parameters["viewbox"])
            assertEquals(null, parameters["bounded"])

            lookup().search("Тверская 7", "ru", null)
            assertEquals(null, fake.requests.last().parameters["viewbox"])
        }

    @Test
    fun loc014ServerErrorsAndTimeoutsGiveNull() =
        runBlocking {
            fake.status = HttpStatusCode.InternalServerError
            assertNull(lookup().reverse(55.758, 37.6107, "ru"))
            assertNull(lookup().search("Тверская 7", "ru", null))

            fake.status = HttpStatusCode.OK
            fake.delay = 2.seconds
            assertNull(lookup().reverse(55.758, 37.6107, "ru"))
        }
}
