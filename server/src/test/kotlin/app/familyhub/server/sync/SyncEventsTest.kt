package app.familyhub.server.sync

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.Protocol
import app.familyhub.contract.api.admin.UpdateHouseholdRequest
import app.familyhub.contract.sync.SyncEvent
import app.familyhub.server.testing.ADULTS
import app.familyhub.server.testing.assertApplied
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.lastSeq
import app.familyhub.server.testing.memberSession
import app.familyhub.server.testing.newTag
import app.familyhub.server.testing.pushOne
import app.familyhub.server.testing.send
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Realtime events (architecture/sync.md § Realtime events, FND-042). */
class SyncEventsTest {
    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun HttpClient.events(
        token: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = webSocket("/api/v1/sync/events", request = {
        bearerAuth(token)
        header(Protocol.HEADER, Protocol.VERSION.toString())
    }) { block() }

    private suspend fun DefaultClientWebSocketSession.nextEvent(): SyncEvent =
        withTimeout(5.seconds) {
            val frame = incoming.receive() as Frame.Text
            FamilyHubJson.decodeFromString(SyncEvent.serializer(), frame.readText())
        }

    @Test
    fun helloThenChangedForOtherSessionsAndAdminWrites() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val other = client.memberSession(admin.accessToken, "bob", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            wsClient().events(admin.accessToken) {
                assertEquals(SyncEvent(SyncEvent.HELLO, deps.lastSeq(admin.householdUuid)), nextEvent())

                val seq = client.pushOne(other.accessToken, deps.newTag("Car")).assertApplied()!!
                assertEquals(SyncEvent(SyncEvent.CHANGED, seq), nextEvent())

                // Writes through admin REST notify as well.
                client.send(HttpMethod.Patch, "/api/v1/household", admin.accessToken, UpdateHouseholdRequest(name = "Renamed"))
                assertEquals(SyncEvent(SyncEvent.CHANGED, deps.lastSeq(admin.householdUuid)), nextEvent())
            }
        }

    @Test
    fun quickChangesAreDebouncedToTheNewestSeq() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            wsClient().events(admin.accessToken) {
                nextEvent()
                repeat(5) { client.pushOne(admin.accessToken, deps.newTag("Tag $it")).assertApplied() }
                val finalSeq = deps.lastSeq(admin.householdUuid)
                val received = mutableListOf<SyncEvent>()
                while (received.lastOrNull()?.seq != finalSeq) received += nextEvent()
                // No more messages arrive afterwards.
                val extra = withTimeoutOrNull(1200.milliseconds) { incoming.receive() }
                assertEquals(null, extra)
                assertTrue(received.size <= 3, "debounced: $received")
                assertTrue(received.all { it.type == SyncEvent.CHANGED })
                assertEquals(received.map { it.seq }.sorted(), received.map { it.seq })
            }
        }

    /** The test engine hides the handshake status; the tests above prove the session check runs on upgrade. */
    @Test
    fun upgradeRequiresASession() =
        testApp { _ ->
            jsonClient().setup()
            assertFails {
                wsClient().webSocket("/api/v1/sync/events", request = {
                    bearerAuth("not-a-token")
                    header(Protocol.HEADER, Protocol.VERSION.toString())
                }) { incoming.receive() }
            }
        }
}
