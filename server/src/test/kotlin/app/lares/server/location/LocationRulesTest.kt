package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.location.LocationVisibility
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertError
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.getRules
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newPlace
import app.lares.server.testing.newPlaceAlert
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.putRules
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LOC-006, ADR-0025: visibility rules on the server, edited by administrators. */
class LocationRulesTest {
    private fun group(id: UUID) = RuleParty(PartyKind.GROUP, id.toString())

    private fun member(id: String) = RuleParty(PartyKind.MEMBER, id)

    private val everyone = RuleParty(PartyKind.EVERYONE)

    private fun List<LocationRule>.inputs() = map { LocationRuleInput(it.subject, it.target, it.level) }

    private suspend fun HttpClient.newGroup(
        admin: AuthResult,
        name: String,
        permissions: Set<Permission> = emptySet(),
    ): UUID =
        UUID.fromString(
            send(HttpMethod.Post, "/api/v1/groups", admin.accessToken, CreateGroupRequest(name, permissions)).body<GroupCreated>().groupId,
        )

    @Test
    fun loc006DefaultsAfterSetupAndPutReplacesInOrder() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adults = deps.groupId(admin.householdUuid, ADULTS)
            val kids = deps.groupId(admin.householdUuid, KIDS)

            val defaults = client.getRules(admin.accessToken).rules
            assertEquals(LocationVisibility.defaultRules(adults.toString(), kids.toString()), defaults.inputs())

            val adult = client.memberSession(admin.accessToken, "adult", setOf(adults))
            val wanted =
                listOf(
                    LocationRuleInput(member(adult.me.memberId), everyone, VisibilityLevel.CURRENT),
                    LocationRuleInput(group(kids), group(kids), VisibilityLevel.HISTORY),
                    LocationRuleInput(group(adults), member(admin.me.memberId), VisibilityLevel.HISTORY),
                )
            assertEquals(HttpStatusCode.NoContent, client.putRules(admin.accessToken, wanted).status)
            val stored = client.getRules(admin.accessToken).rules
            assertEquals(wanted, stored.inputs())
            assertTrue(stored.map { it.id }.intersect(defaults.map { it.id }.toSet()).isEmpty(), "new ids on every PUT")

            // Non-admins neither read nor change the rules.
            assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Get, "/api/v1/location/rules", adult.accessToken).status)
            client.putRules(adult.accessToken, emptyList()).assertError(ErrorCode.FORBIDDEN)
            assertEquals(wanted, client.getRules(admin.accessToken).rules.inputs())

            assertEquals(HttpStatusCode.NoContent, client.putRules(admin.accessToken, emptyList()).status)
            assertTrue(client.getRules(admin.accessToken).rules.isEmpty())
        }

    @Test
    fun loc006UnknownOrForeignIdsAreRejectedPerField() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adults = deps.groupId(admin.householdUuid, ADULTS)
            val householdB = deps.createSecondHousehold(login = "admin-b")
            val adminB = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val foreignGroup = deps.groupId(householdB, ADULTS)
            val deletedGroup = client.newGroup(admin, "Temp")
            client.send(HttpMethod.Delete, "/api/v1/groups/$deletedGroup", admin.accessToken)

            val response =
                client.putRules(
                    admin.accessToken,
                    listOf(
                        LocationRuleInput(group(foreignGroup), everyone, VisibilityLevel.CURRENT),
                        LocationRuleInput(group(adults), member(adminB.me.memberId), VisibilityLevel.CURRENT),
                        LocationRuleInput(group(deletedGroup), member("not-a-uuid"), VisibilityLevel.CURRENT),
                        LocationRuleInput(group(adults), everyone, VisibilityLevel.HISTORY),
                    ),
                )
            assertEquals(
                mapOf(
                    "rules.0.subject" to FieldErrorCode.INVALID_FORMAT,
                    "rules.1.target" to FieldErrorCode.INVALID_FORMAT,
                    "rules.2.subject" to FieldErrorCode.INVALID_FORMAT,
                    "rules.2.target" to FieldErrorCode.INVALID_FORMAT,
                ),
                response.assertError(ErrorCode.VALIDATION_FAILED).error.fields,
            )
            // Shape errors come from the shared validator.
            val shape =
                client.putRules(
                    admin.accessToken,
                    listOf(LocationRuleInput(RuleParty(PartyKind.EVERYONE, adults.toString()), everyone, VisibilityLevel.NONE)),
                )
            assertEquals(
                mapOf("rules.0.subject" to FieldErrorCode.INVALID_FORMAT, "rules.0.level" to FieldErrorCode.INVALID_FORMAT),
                shape.assertError(ErrorCode.VALIDATION_FAILED).error.fields,
            )
            assertEquals(2, client.getRules(admin.accessToken).rules.size, "nothing changed")

            // Each household sees only its own rules.
            val rulesB = client.getRules(adminB.accessToken).rules
            assertEquals(
                LocationVisibility.defaultRules(foreignGroup.toString(), deps.groupId(householdB, KIDS).toString()),
                rulesB.inputs(),
            )
        }

    @Test
    fun adr0025DeletingGroupsAndMembersRemovesTheirRulesAndAlerts() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = admin.householdUuid
            val adults = deps.groupId(householdId, ADULTS)
            val kids = deps.groupId(householdId, KIDS)
            val adult = client.memberSession(admin.accessToken, "adult", setOf(adults))
            val kid = client.memberSession(admin.accessToken, "kid", setOf(kids))
            val temp = client.newGroup(admin, "Temp")
            val defaults = client.getRules(admin.accessToken).rules.inputs()
            val kidSeesAdult = LocationRuleInput(member(kid.me.memberId), member(adult.me.memberId), VisibilityLevel.HISTORY)
            val tempRule = LocationRuleInput(group(temp), everyone, VisibilityLevel.CURRENT)
            val adultSeesKid = LocationRuleInput(group(adults), member(kid.me.memberId), VisibilityLevel.HISTORY)
            client.putRules(admin.accessToken, defaults + kidSeesAdult + tempRule + adultSeesKid)

            client.send(HttpMethod.Delete, "/api/v1/groups/$temp", admin.accessToken)
            assertEquals(defaults + kidSeesAdult + adultSeesKid, client.getRules(admin.accessToken).rules.inputs())

            val school = deps.newPlace(adult.me.memberId)
            client.pushOne(adult.accessToken, school).assertApplied()
            val aboutKid = deps.newPlaceAlert(kid.me.memberId, adult.me.memberId, setOf(school.id))
            val byKid = deps.newPlaceAlert(adult.me.memberId, kid.me.memberId, setOf(school.id))
            client.pushOne(adult.accessToken, aboutKid).assertApplied()
            client.pushOne(kid.accessToken, byKid).assertApplied()
            val (adultCursor, _) = client.pullAll(adult.accessToken)

            assertEquals(
                HttpStatusCode.NoContent,
                client.send(HttpMethod.Delete, "/api/v1/members/${kid.me.memberId}", admin.accessToken).status,
            )
            assertEquals(defaults, client.getRules(admin.accessToken).rules.inputs())
            deps.db.dbQuery {
                val rows =
                    PlaceAlertTable.selectAll().where {
                        PlaceAlertTable.id inList
                            listOf(
                                aboutKid.id,
                                byKid.id,
                            ).map(UUID::fromString)
                    }
                assertTrue(rows.all { it[PlaceAlertTable.deleted] })
                assertEquals(2, rows.map { it[PlaceAlertTable.seq] }.toSet().size, "each tombstone has its own seq")
            }
            // Tombstones carry no data and reach everybody with location.view.
            val tombstones = client.pullAll(adult.accessToken, adultCursor).second.filter { it.type == EntityTypes.PLACE_ALERT }
            assertEquals(setOf(aboutKid.id, byKid.id), tombstones.map { it.id }.toSet())
            assertTrue(tombstones.all { it.deleted })
        }

    @Test
    fun loc006LevelsOfTheDefaultFamily() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = admin.householdUuid
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(householdId, ADULTS)))
            val kid = client.memberSession(admin.accessToken, "kid", setOf(deps.groupId(householdId, KIDS)))
            val shopper =
                client.memberSession(
                    admin.accessToken,
                    "shopper",
                    setOf(client.newGroup(admin, "Shoppers", setOf(Permission.SHOPPING_VIEW))),
                )
            val ids = listOf(admin, adult, kid, shopper).map { UUID.fromString(it.me.memberId) }

            suspend fun levels(viewer: AuthResult): List<VisibilityLevel> =
                deps.db.dbQuery {
                    val all = deps.locationVisibility.levels(this, householdId, UUID.fromString(viewer.me.memberId))
                    assertEquals(ids.toSet(), all.keys)
                    ids.map { all.getValue(it) }
                }
            val h = VisibilityLevel.HISTORY
            val c = VisibilityLevel.CURRENT
            val n = VisibilityLevel.NONE
            // Columns: admin, adult, kid, shopper.
            assertEquals(listOf(h, h, h, h), levels(admin))
            assertEquals(listOf(h, h, h, h), levels(adult))
            assertEquals(listOf(n, c, h, n), levels(kid))
            assertEquals(listOf(n, n, n, n), levels(shopper), "no location.view: nobody, not even oneself")
            deps.db.dbQuery {
                assertEquals(c, deps.locationVisibility.level(this, householdId, ids[2], ids[1]))
                assertEquals(n, deps.locationVisibility.level(this, householdId, ids[2], UUID.randomUUID()))
            }
        }
}
