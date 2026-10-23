package app.familyhub.tools.loadsim

import app.familyhub.contract.api.ApiErrorBody
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.Protocol
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** A non-2xx answer. [code] is the API error code when the body carried one. */
class HttpFailure(
    val status: Int,
    val code: ErrorCode?,
    body: String,
) : Exception("HTTP $status ${code ?: ""} ${body.take(200)}")

/**
 * The HTTP side of one simulated device: its own connections (like a separate phone), its tokens, and timing of every
 * request into the [Recorder]. Endpoint labels use path templates (`PATCH /groups/{id}`).
 */
class DeviceApi(
    private val base: String,
    insecure: Boolean,
    private val recorder: Recorder,
) : AutoCloseable {
    @Volatile
    var auth: AuthResult? = null

    private val client =
        HttpClient(CIO) {
            expectSuccess = false
            install(WebSockets)
            engine {
                // Timeouts are applied per HTTP call below; an engine-wide one would also cut the WebSocket.
                requestTimeout = 0
                if (insecure) https { trustManager = TrustAll }
            }
        }

    /** Sends [body] as JSON and decodes the answer as [R]; records the latency under [label]. */
    suspend inline fun <reified B, reified R> call(
        label: String,
        method: HttpMethod,
        path: String,
        body: B?,
        authenticated: Boolean = true,
    ): R {
        val text = exchange(label, method, path, body?.let { FamilyHubJson.encodeToString(serializer<B>(), it) }, authenticated)
        return FamilyHubJson.decodeFromString(serializer<R>(), text)
    }

    /** Like [call] without a request body. */
    suspend inline fun <reified R> get(
        label: String,
        path: String,
        authenticated: Boolean = true,
    ): R = FamilyHubJson.decodeFromString(serializer<R>(), exchange(label, HttpMethod.Get, path, null, authenticated))

    /** A call whose answer body is not needed (204). */
    suspend inline fun <reified B> send(
        label: String,
        method: HttpMethod,
        path: String,
        body: B?,
    ) {
        exchange(label, method, path, body?.let { FamilyHubJson.encodeToString(serializer<B>(), it) }, true)
    }

    /** One timed request; non-2xx answers throw [HttpFailure], network errors are recorded with status 0 and rethrown. */
    suspend fun exchange(
        label: String,
        method: HttpMethod,
        path: String,
        json: String?,
        authenticated: Boolean,
    ): String {
        val token = if (authenticated) checkNotNull(auth) { "device has no session" }.accessToken else null
        return try {
            attempt(label, method, path, json, token)
        } catch (e: HttpFailure) {
            // A request that raced a token refresh carries the rotated-out token; the app retries it with the new one.
            // The failed attempt stays in the statistics.
            val current = auth?.accessToken
            if (e.status != 401 || token == null || current == token) throw e
            attempt(label, method, path, json, current)
        }
    }

    private suspend fun attempt(
        label: String,
        method: HttpMethod,
        path: String,
        json: String?,
        token: String?,
    ): String {
        val start = TimeSource.Monotonic.markNow()
        val (status, text) =
            try {
                withTimeout(REQUEST_TIMEOUT) {
                    val response =
                        client.request("$base/api/v1$path") {
                            this.method = method
                            header(Protocol.HEADER, Protocol.VERSION.toString())
                            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                            if (json != null) {
                                contentType(ContentType.Application.Json)
                                setBody(json)
                            }
                        }
                    response.status.value to response.bodyAsText()
                }
            } catch (e: CancellationException) {
                // A timeout is a failed request; cancellation of the run is not.
                if (e is kotlinx.coroutines.TimeoutCancellationException) recorder.record(label, 0, start.millis())
                throw e
            } catch (e: Exception) {
                recorder.record(label, 0, start.millis())
                throw e
            }
        recorder.record(label, status, start.millis())
        if (status !in 200..299) {
            val code = runCatching { FamilyHubJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }.getOrNull()
            throw HttpFailure(status, code, text)
        }
        return text
    }

    /** Rotates the token pair (`POST /auth/refresh`). */
    suspend fun refresh() {
        val current = checkNotNull(auth)
        auth =
            call<RefreshRequest, AuthResult>(
                "POST /auth/refresh",
                HttpMethod.Post,
                "/auth/refresh",
                RefreshRequest(current.refreshToken),
                false,
            )
    }

    /**
     * Opens `/sync/events` and runs [block] on it; the upgrade time is recorded as [EVENTS]. Returns when the socket
     * closes. A failed upgrade is recorded with status 0 and rethrown.
     */
    suspend fun events(block: suspend DefaultClientWebSocketSession.() -> Unit) {
        val token = checkNotNull(auth).accessToken
        val url = base.replaceFirst("http", "ws") + "/api/v1/sync/events"
        val start = TimeSource.Monotonic.markNow()
        var opened = false
        try {
            client.webSocket(urlString = url, request = {
                header(Protocol.HEADER, Protocol.VERSION.toString())
                header(HttpHeaders.Authorization, "Bearer $token")
            }) {
                opened = true
                recorder.record(EVENTS, 101, start.millis())
                recorder.wsOpened.incrementAndGet()
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!opened) recorder.record(EVENTS, 0, start.millis())
            throw e
        }
    }

    override fun close() = client.close()

    private fun TimeSource.Monotonic.ValueTimeMark.millis(): Double = elapsedNow().toDouble(DurationUnit.MILLISECONDS)

    companion object {
        const val EVENTS = "WS /sync/events"
        val REQUEST_TIMEOUT = 30.seconds
    }
}

/** Accepts every certificate; only for `--insecure` against a local stack. */
private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
