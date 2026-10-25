package app.lares.server.location

import app.lares.contract.api.LaresJson
import app.lares.contract.api.Protocol
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.assertApplied
import app.lares.server.testing.createMember
import app.lares.server.testing.fix
import app.lares.server.testing.groupId
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.newShoppingList
import app.lares.server.testing.pushOne
import app.lares.server.testing.putRules
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The live channel (LOC-003, ADR-0024): snapshot, member changes and visibility changes, only to allowed viewers. */
class LocationLiveTest {
    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun HttpClient.live(
        token: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = webSocket("/api/v1/location/live", request = {
        bearerAuth(token)
        header(Protocol.HEADER, Protocol.VERSION.toString())
    }) { block() }

    private suspend fun DefaultClientWebSocketSession.next(): LiveMessage =
        withTimeout(5.seconds) {
            val frame = incoming.receive() as Frame.Text
            LaresJson.decodeFromString(LiveMessage.serializer(), frame.readText())
        }

    private suspend fun DefaultClientWebSocketSession.nothingMore() =
        assertNull(withTimeoutOrNull(1200.milliseconds) { incoming.receive() })

    @Test
    fun loc003SnapshotThenMemberMessagesOnlyForVisibleMembers() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val now = deps.testClock.now()
            client.setSharing(family.adult.accessToken)
            client.setSharing(family.kid2.accessToken)
            wsClient().live(family.kid.accessToken) {
                val snapshot = next()
                assertEquals(LiveMessage.SNAPSHOT, snapshot.type)
                assertEquals(
                    setOf(family.adult.memberId, family.kid.memberId),
                    snapshot.now!!
                        .members
                        .map { it.memberId }
                        .toSet(),
                )
                assertEquals(SharingStatus.SHARING, snapshot.now!!.of(family.adult).status)

                val position = fix(LAT, LON, now)
                client.upload(family.adult.accessToken, listOf(position))
                val member = next()
                assertEquals(LiveMessage.MEMBER, member.type)
                assertEquals(family.adult.memberId, member.member!!.memberId)
                assertEquals(VisibilityLevel.CURRENT, member.member!!.level)
                assertEquals(position, member.member!!.last)

                // The other kid is not visible to this kid; a shopping write changes nothing.
                client.upload(family.kid2.accessToken, listOf(position))
                client.pushOne(family.adult.accessToken, deps.newShoppingList(family.adult.memberId)).assertApplied()
                nothingMore()
            }
        }

    @Test
    fun loc006RulesRemovingVisibilitySendGoneThenASnapshot() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            wsClient().live(family.adult.accessToken) {
                assertEquals(4, next().now!!.members.size)
                val adults = RuleParty(PartyKind.GROUP, deps.groupId(family.householdId, ADULTS).toString())
                val kids = RuleParty(PartyKind.GROUP, deps.groupId(family.householdId, KIDS).toString())
                client.putRules(family.admin.accessToken, listOf(LocationRuleInput(kids, adults, VisibilityLevel.CURRENT)))

                // The administrator and both kids drop out (the administrator is not in Adults).
                val messages = List(4) { next() }
                assertEquals(List(3) { LiveMessage.GONE }, messages.take(3).map { it.type })
                assertEquals(
                    setOf(family.admin.memberId, family.kid.memberId, family.kid2.memberId),
                    messages.take(3).map { it.memberId }.toSet(),
                )
                assertEquals(LiveMessage.SNAPSHOT, messages[3].type)
                assertEquals(
                    setOf(family.adult.memberId),
                    messages[3]
                        .now!!
                        .members
                        .map { it.memberId }
                        .toSet(),
                )
                nothingMore()
            }
        }

    @Test
    fun loc012LogoutOfTheViewersSessionClosesTheSocket() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            wsClient().live(family.adult.accessToken) {
                next()
                assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Post, "/api/v1/auth/logout", family.adult.accessToken).status)
                val reason = withTimeout(5.seconds) { closeReason.await() }
                assertEquals(LocationLiveService.CLOSE_SIGNED_OUT, reason?.code)
            }
        }

    @Test
    fun loc003MemberWithoutViewIsClosedWithForbidden() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            client.createMember(family.admin.accessToken, CreateMemberRequest("guest", "guest", MEMBER_PASSWORD, emptySet()))
            val guest = client.login("guest", MEMBER_PASSWORD).body<AuthResult>()
            wsClient().live(guest.accessToken) {
                val reason = withTimeout(5.seconds) { closeReason.await() }
                assertEquals(LocationLiveService.CLOSE_FORBIDDEN, reason?.code)
            }
        }
}
