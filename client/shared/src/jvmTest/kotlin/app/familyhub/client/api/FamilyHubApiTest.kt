package app.familyhub.client.api

import app.familyhub.client.auth.InMemoryTokenStore
import app.familyhub.client.auth.Tokens
import app.familyhub.client.platform.InMemoryServerStore
import app.familyhub.contract.api.ApiError
import app.familyhub.contract.api.ApiErrorBody
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.InstanceInfo
import app.familyhub.contract.api.Protocol
import app.familyhub.contract.api.ProtocolRange
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.Me
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class FamilyHubApiTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val oldTokens = Tokens("access-1", now + 1.hours, "refresh-1", now + 1000.hours, "session")
    private val me =
        Me(
            userId = "u",
            memberId = "m",
            householdId = "h",
            householdName = "The Smiths",
            personId = "p",
            displayName = "Anna",
            login = "anna",
            hasPassword = true,
            isAdmin = true,
            permissions = emptySet(),
        )
    private val tokenStore = InMemoryTokenStore()
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FamilyHubApi {
        val engine =
            MockEngine { request ->
                synchronized(requests) { requests += request }
                handler(request)
            }
        return FamilyHubApi(createHttpClient(engine), InMemoryServerStore("http://family.test/"), tokenStore, clock)
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.error(
        status: HttpStatusCode,
        code: ErrorCode,
        fields: Map<String, FieldErrorCode> = emptyMap(),
    ) = json(FamilyHubJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, "test", fields))), status)

    private fun MockRequestHandleScope.meResponse() = json(FamilyHubJson.encodeToString(Me.serializer(), me))

    private fun MockRequestHandleScope.refreshed(n: Int) =
        json(
            FamilyHubJson.encodeToString(
                AuthResult.serializer(),
                AuthResult("access-$n", now + 1.hours, "refresh-$n", now + 1000.hours, "session", me),
            ),
        )

    private fun TestScope.collectAuthEvents(api: FamilyHubApi): List<AuthEvent> {
        val events = mutableListOf<AuthEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { api.authEvents.toList(events) }
        return events
    }

    @Test
    fun everyRequestCarriesTheProtocolHeader() =
        runTest {
            tokenStore.save(oldTokens)
            val api =
                api { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/instance" -> {
                            json(
                                FamilyHubJson.encodeToString(InstanceInfo.serializer(), InstanceInfo(true, "0.1.0", ProtocolRange(1, 1))),
                            )
                        }

                        else -> {
                            meResponse()
                        }
                    }
                }
            api.instance()
            api.me()
            assertEquals(listOf("1", "1"), requests.map { it.headers[Protocol.HEADER] })
            assertEquals("Bearer access-1", requests[1].headers[HttpHeaders.Authorization])
            assertEquals("http://family.test/api/v1/instance", requests[0].url.toString())
        }

    @Test
    fun expiredAccessTokenIsRefreshedOnceAndTheCallRetried() =
        runTest {
            tokenStore.save(oldTokens)
            val api =
                api { request ->
                    when {
                        request.url.encodedPath == "/api/v1/auth/refresh" -> {
                            refreshed(2)
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer access-1" -> {
                            error(
                                HttpStatusCode.Unauthorized,
                                ErrorCode.SESSION_EXPIRED,
                            )
                        }

                        else -> {
                            meResponse()
                        }
                    }
                }
            // Two calls at once share one refresh (single flight).
            val results = listOf(async { api.me() }, async { api.me() }).awaitAll()
            assertEquals(listOf(me, me), results)
            assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/auth/refresh" })
            assertEquals("access-2", tokenStore.load()?.accessToken)
            assertEquals("refresh-2", tokenStore.load()?.refreshToken)
            assertEquals(2, requests.count { it.headers[HttpHeaders.Authorization] == "Bearer access-2" })
        }

    @Test
    fun aSecondUnauthorizedAnswerIsNotRetriedAgain() =
        runTest {
            tokenStore.save(oldTokens)
            val api =
                api { request ->
                    if (request.url.encodedPath ==
                        "/api/v1/auth/refresh"
                    ) {
                        refreshed(2)
                    } else {
                        error(HttpStatusCode.Unauthorized, ErrorCode.SESSION_EXPIRED)
                    }
                }
            val e = assertFailsWith<ApiException> { api.me() }
            assertEquals(ErrorCode.SESSION_EXPIRED, e.code)
            assertEquals(3, requests.size)
        }

    @Test
    fun revokedSessionLogsOut() =
        runTest {
            tokenStore.save(oldTokens)
            val api = api { error(HttpStatusCode.Unauthorized, ErrorCode.SESSION_REVOKED) }
            val events = collectAuthEvents(api)
            val e = assertFailsWith<ApiException> { api.me() }
            assertEquals(ErrorCode.SESSION_REVOKED, e.code)
            assertNull(tokenStore.load())
            assertEquals(listOf<AuthEvent>(AuthEvent.LoggedOut), events)
        }

    @Test
    fun failedRefreshLogsOut() =
        runTest {
            tokenStore.save(oldTokens)
            val api =
                api { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        error(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHENTICATED)
                    } else {
                        error(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHENTICATED)
                    }
                }
            val events = collectAuthEvents(api)
            assertFailsWith<ApiException> { api.me() }
            assertNull(tokenStore.load())
            assertEquals(listOf<AuthEvent>(AuthEvent.LoggedOut), events)
        }

    @Test
    fun errorBodiesAreDecoded() =
        runTest {
            tokenStore.save(oldTokens)
            val api = api { error(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, mapOf("name" to FieldErrorCode.TOO_LONG)) }
            val e = assertFailsWith<ApiException> { api.createGroup(CreateGroupRequest("x".repeat(100))) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
            assertEquals(mapOf("name" to FieldErrorCode.TOO_LONG), e.fields)
            assertEquals(400, e.httpStatus)
        }

    @Test
    fun unknownCodesAndNonJsonBodiesGetAFallback() =
        runTest {
            val api =
                api { request ->
                    if (request.url.encodedPath == "/api/v1/instance") {
                        respond("Bad gateway", HttpStatusCode.BadGateway)
                    } else {
                        json("""{"error":{"code":"SOMETHING_NEW","message":"x"}}""", HttpStatusCode.Conflict)
                    }
                }
            assertEquals(ErrorCode.INTERNAL, assertFailsWith<ApiException> { api.instance() }.code)
            assertEquals(
                ErrorCode.UNKNOWN,
                assertFailsWith<ApiException> {
                    api.previewInvite(
                        app.familyhub.contract.api.admin
                            .InvitePreviewRequest("X"),
                    )
                }.code,
            )
        }
}
