package app.lares.server.location

import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.AddressLabel
import app.lares.contract.api.location.AddressSearch
import app.lares.contract.api.location.LocationConfig
import app.lares.server.AppDeps
import app.lares.server.notifications.FakePushService
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.testing.assertApplied
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.memberSession
import app.lares.server.testing.newPlace
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** `GET /location/address` and `GET /location/search` (LOC-014, LOC-024): permissions, limits, validation, no own traffic. */
class AddressRoutesTest {
    private val fake = FakeNominatim()

    @AfterTest
    fun stop() = fake.close()

    private fun geocoderApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(
            configure = { it.copy(location = it.location.copy(geocoderUrl = fake.url), pushAllowedHosts = setOf("127.0.0.1")) },
            block = block,
        )

    private suspend fun HttpClient.address(
        token: String,
        query: String = "lat=55.75801&lon=37.61072",
    ): HttpResponse = send(HttpMethod.Get, "/api/v1/location/address?$query", token)

    private suspend fun HttpClient.search(
        token: String,
        q: String = "Тверская 7",
    ): HttpResponse = send(HttpMethod.Get, "/api/v1/location/search?q=${q.encodeURLParameter()}", token)

    private suspend fun HttpResponse.assertError(
        code: ErrorCode,
        fields: Map<String, FieldErrorCode> = emptyMap(),
    ) {
        val error = body<ApiErrorBody>().error
        assertEquals(code, error.code, error.message)
        assertEquals(fields, error.fields)
    }

    @Test
    fun loc014AddressNeedsViewAndSearchNeedsManagePlaces() =
        geocoderApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val nobody = client.memberSession(family.admin.accessToken, "guest", emptySet())

            assertEquals(HttpStatusCode.Forbidden, client.address(nobody.accessToken).status)
            val kid = client.address(family.kid.accessToken)
            assertEquals(HttpStatusCode.OK, kid.status)
            assertEquals(AddressLabel("Тверская улица, 7"), kid.body())
            assertEquals("55.758", fake.requests.single().parameters["lat"], "rounded to 4 decimals")

            assertEquals(HttpStatusCode.Forbidden, client.search(family.kid.accessToken).status)
            val adult = client.search(family.adult.accessToken)
            assertEquals(HttpStatusCode.OK, adult.status)
            assertEquals(
                "Тверская улица, 7, Москва",
                adult
                    .body<AddressSearch>()
                    .results
                    .first()
                    .label,
            )

            val config = client.send(HttpMethod.Get, "/api/v1/location/config", family.kid.accessToken).body<LocationConfig>()
            assertEquals(true, config.addresses)
        }

    @Test
    fun loc014InvalidCoordinatesAndQueries() =
        geocoderApp { deps ->
            val client = jsonClient()
            val token = client.locationFamily(deps).admin.accessToken
            client.address(token, "lon=37.6").assertError(ErrorCode.VALIDATION_FAILED, mapOf("lat" to FieldErrorCode.REQUIRED))
            client
                .address(token, "lat=abc&lon=181")
                .assertError(
                    ErrorCode.VALIDATION_FAILED,
                    mapOf("lat" to FieldErrorCode.INVALID_FORMAT, "lon" to FieldErrorCode.OUT_OF_RANGE),
                )
            client.address(token, "lat=-90.5&lon=NaN").assertError(
                ErrorCode.VALIDATION_FAILED,
                mapOf("lat" to FieldErrorCode.OUT_OF_RANGE, "lon" to FieldErrorCode.INVALID_FORMAT),
            )
            client.search(token, "  ab  ").assertError(ErrorCode.VALIDATION_FAILED, mapOf("q" to FieldErrorCode.TOO_SHORT))
            client.search(token, "x".repeat(201)).assertError(ErrorCode.VALIDATION_FAILED, mapOf("q" to FieldErrorCode.TOO_LONG))
            assertEquals(emptyList(), fake.requests.toList())
        }

    @Test
    fun loc014RateLimitsPerMember() =
        geocoderApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            repeat(AddressEndpoints.ADDRESS_PER_MINUTE) {
                assertEquals(HttpStatusCode.OK, client.address(family.kid.accessToken).status, "address $it")
            }
            client.address(family.kid.accessToken).assertError(ErrorCode.RATE_LIMITED)
            assertEquals(HttpStatusCode.OK, client.address(family.kid2.accessToken).status, "another member")
            assertEquals(1, fake.requests.size, "the rest came from the cache")

            repeat(AddressEndpoints.SEARCH_PER_MINUTE) {
                assertEquals(HttpStatusCode.OK, client.search(family.adult.accessToken).status, "search $it")
            }
            client.search(family.adult.accessToken).assertError(ErrorCode.RATE_LIMITED)
            deps.testClock.advance(1.minutes)
            assertEquals(HttpStatusCode.OK, client.search(family.adult.accessToken).status, "a minute later")
        }

    @Test
    fun loc014LanguageIsThePushLanguageOrTheHouseholdDefault() =
        geocoderApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            FakePushService().use { push ->
                val device = push.device("adult", "en-GB")
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.send(HttpMethod.Put, "/api/v1/me/push", family.adult.accessToken, device.registration).status,
                )
                client.address(family.adult.accessToken)
            }
            assertEquals("en", fake.requests.last().parameters["accept-language"])
            val default =
                deps.db.dbQuery {
                    HouseholdTable.selectAll().where { HouseholdTable.id eq family.householdId }.single()[HouseholdTable.defaultLanguage]
                }
            client.search(family.admin.accessToken)
            assertEquals(default, fake.requests.last().parameters["accept-language"])
            assertEquals(null, fake.requests.last().parameters["viewbox"], "no places yet")
        }

    @Test
    fun adr0026UploadsNeverReachTheGeocoder() =
        geocoderApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            client.pushOne(family.admin.accessToken, deps.newPlace(family.admin.memberId)).assertApplied()
            client.setSharing(family.kid.accessToken)
            val now = deps.testClock.now()
            client.upload(family.kid.accessToken, listOf(fix(LAT + 0.01, LON, now - 3.minutes), fix(LAT + 0.01, LON, now - 2.minutes)))
            client.upload(family.kid.accessToken, listOf(fix(LAT, LON, now - 1.minutes), fix(LAT, LON, now)))
            client.locationNow(family.admin.accessToken)
            assertEquals(emptyList(), fake.requests.toList())
        }

    @Test
    fun loc014GeocoderOffAnswersEmpty() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            assertEquals("{}", client.address(family.admin.accessToken).bodyAsText())
            assertEquals("""{"results":[]}""", client.search(family.admin.accessToken).bodyAsText())
            val config = client.send(HttpMethod.Get, "/api/v1/location/config", family.kid.accessToken).body<LocationConfig>()
            assertEquals(false, config.addresses)
        }
}
