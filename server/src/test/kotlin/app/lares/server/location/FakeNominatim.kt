package app.lares.server.location

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A request the fake geocoder received; [parameters] by name, [headers] with lower-case names. */
class GeocoderRequest(
    val path: String,
    val parameters: Map<String, String>,
    val headers: Map<String, String>,
)

/**
 * A Nominatim-compatible service on a random local port (tests never reach the internet). Answers `/reverse` and
 * `/search` with [status] and the body [responses] gives for the path, after [delay].
 */
class FakeNominatim : AutoCloseable {
    @Volatile
    var status: HttpStatusCode = HttpStatusCode.OK

    @Volatile
    var delay: Duration = Duration.ZERO

    @Volatile
    var responses: (path: String) -> String = { path -> sample(DEFAULT_SAMPLES.getValue(path)) }

    val requests = CopyOnWriteArrayList<GeocoderRequest>()

    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                listOf("reverse", "search").forEach { path ->
                    get("/$path") {
                        requests +=
                            GeocoderRequest(
                                path,
                                call.request.queryParameters
                                    .entries()
                                    .associate { it.key to it.value.first() },
                                call.request.headers
                                    .entries()
                                    .associate { it.key.lowercase() to it.value.first() },
                            )
                        if (delay.isPositive()) delay(delay)
                        call.respondText(responses(path), ContentType.Application.Json, status)
                    }
                }
            }
        }.start(wait = false)

    val url: String =
        "http://127.0.0.1:" +
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }

    override fun close() {
        server.stop(0, 0)
    }

    companion object {
        private val DEFAULT_SAMPLES = mapOf("reverse" to "reverse-tverskaya-7.json", "search" to "search-tverskaya.json")

        /** A recorded response from `src/test/resources/nominatim/`. */
        fun sample(name: String): String =
            checkNotNull(FakeNominatim::class.java.getResource("/nominatim/$name")) { "No sample $name" }.readText()
    }
}
