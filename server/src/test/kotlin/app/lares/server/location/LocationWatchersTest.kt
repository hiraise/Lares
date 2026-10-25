package app.lares.server.location

import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.AlertWatcher
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.LocationWatchers
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.api.location.Watcher
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.delete
import app.lares.server.testing.groupId
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.newPlace
import app.lares.server.testing.newPlaceAlert
import app.lares.server.testing.pushOne
import app.lares.server.testing.putRules
import app.lares.server.testing.send
import app.lares.server.testing.testApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Кто видит меня» (LOC-015): viewers and arrive / leave subscribers of the caller. */
class LocationWatchersTest {
    private suspend fun HttpClient.watchers(token: String): LocationWatchers {
        val response = send(HttpMethod.Get, "/api/v1/location/me/watchers", token)
        assertEquals(HttpStatusCode.OK, response.status, "watchers")
        return response.body()
    }

    @Test
    fun loc015ViewersAndLiveAlertsOfSubscribersWhoStillSeeMe() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val kid = family.kid.accessToken
            val adminWatcher = Watcher(family.admin.memberId, VisibilityLevel.HISTORY)
            val adultWatcher = Watcher(family.adult.memberId, VisibilityLevel.HISTORY)
            assertEquals(LocationWatchers(listOf(adminWatcher, adultWatcher).sortedBy { it.memberId }, emptyList()), client.watchers(kid))

            val school = deps.newPlace(family.adult.memberId, "School")
            val park = deps.newPlace(family.adult.memberId, "Park")
            client.pushOne(family.adult.accessToken, school).assertApplied()
            client.pushOne(family.adult.accessToken, park).assertApplied()
            client
                .pushOne(
                    family.adult.accessToken,
                    deps.newPlaceAlert(family.kid.memberId, family.adult.memberId, setOf(school.id, park.id)),
                ).assertApplied()
            client
                .pushOne(
                    family.admin.accessToken,
                    deps.newPlaceAlert(family.kid.memberId, family.admin.memberId, setOf(park.id)),
                ).assertApplied()
            client.pushOne(family.adult.accessToken, deps.delete(EntityTypes.PLACE, park.id)).assertApplied()

            // Deleted places drop out; a subscriber left with none is not listed.
            assertEquals(listOf(AlertWatcher(family.adult.memberId, setOf(school.id))), client.watchers(kid).alerts)

            // The adult no longer sees kids: neither a viewer nor an alert subscriber any more.
            val adults = RuleParty(PartyKind.GROUP, deps.groupId(family.householdId, ADULTS).toString())
            val kids = RuleParty(PartyKind.GROUP, deps.groupId(family.householdId, KIDS).toString())
            client.putRules(family.admin.accessToken, listOf(LocationRuleInput(kids, adults, VisibilityLevel.CURRENT)))
            assertEquals(LocationWatchers(listOf(adminWatcher), emptyList()), client.watchers(kid))

            // The adult is seen by the kids now and by the administrator.
            assertEquals(
                setOf(family.admin.memberId, family.kid.memberId, family.kid2.memberId),
                client
                    .watchers(family.adult.accessToken)
                    .viewers
                    .map { it.memberId }
                    .toSet(),
            )

            deps.createSecondHousehold()
            val stranger = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            assertEquals(LocationWatchers(emptyList(), emptyList()), client.watchers(stranger.accessToken))
        }
}
