package app.lares.client.api

import app.lares.contract.api.LaresJson
import app.lares.contract.api.Protocol
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json

/**
 * Extra client configuration from a platform module (tests use it to simulate a lost network).
 * Applied after the defaults.
 */
fun interface HttpClientCustomizer {
    fun HttpClientConfig<*>.customize()
}

/** The HTTP client of the app: shared JSON, protocol header on every request, timeouts, WebSockets. */
fun createHttpClient(
    engine: HttpClientEngine,
    customizer: HttpClientCustomizer? = null,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(LaresJson) }
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        defaultRequest { header(Protocol.HEADER, Protocol.VERSION.toString()) }
        customizer?.run { customize() }
    }
