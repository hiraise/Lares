package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.InstanceInfo
import app.lares.contract.api.auth.Me
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.server.household.GroupRepository
import app.lares.server.household.GroupTable
import app.lares.server.household.MemberTable
import app.lares.server.household.PersonTable
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.testing.assertError
import app.lares.server.testing.bearer
import app.lares.server.testing.json
import app.lares.server.testing.jsonClient
import app.lares.server.testing.setup
import app.lares.server.testing.setupRequest
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FND-001, FND-023: first-run setup. */
class SetupTest {
    @Test
    fun wrongCodeIsRejected() =
        testApp {
            val response = jsonClient().post("/api/v1/setup") { json(setupRequest(code = "ZZZZZ-ZZZZZ")) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertError(ErrorCode.SETUP_CODE_INVALID)
            val info = jsonClient().get("/api/v1/instance").body<InstanceInfo>()
            assertEquals(false, info.initialized)
        }

    @Test
    fun setupCreatesHouseholdAdminAndTemplates() =
        testApp { deps ->
            val client = jsonClient()
            // The code is accepted in any letter case and with look-alike characters.
            val result = client.setup(setupRequest(code = "abcde-l2345"))
            assertTrue(result.accessToken.isNotEmpty() && result.refreshToken.isNotEmpty())
            assertEquals(deps.testClock.now() + AuthService.ACCESS_TTL, result.accessExpiresAt)
            assertEquals(deps.testClock.now() + AuthService.REFRESH_TTL, result.refreshExpiresAt)

            val me = client.get("/api/v1/me") { bearer(result.accessToken) }.body<Me>()
            assertEquals(result.me, me)
            assertTrue(me.isAdmin)
            assertEquals(Permission.entries.toSet(), me.permissions)
            assertEquals("admin", me.login)
            assertEquals("Anna", me.displayName)
            assertEquals("The Smiths", me.householdName)
            assertTrue(me.hasPassword)

            val groups = deps.db.dbQuery { GroupRepository.loadViews(UUID.fromString(me.householdId)) }
            val names = deps.db.dbQuery { GroupTable.selectAll().associate { it[GroupTable.id].toString() to it[GroupTable.name] } }
            assertEquals(setOf("Administrators", "Adults", "Kids"), names.values.toSet())
            val admins = groups.single { it.builtIn == BuiltInGroup.ADMINISTRATORS }
            assertEquals(setOf(me.memberId), admins.memberIds)
            assertEquals(PermissionCatalogue.adultsTemplate, groups.single { names[it.id] == "Adults" }.permissions)
            assertEquals(PermissionCatalogue.kidsTemplate, groups.single { names[it.id] == "Kids" }.permissions)

            val info = jsonClient().get("/api/v1/instance").body<InstanceInfo>()
            assertEquals(true, info.initialized)
        }

    @Test
    fun groupNamesFollowDefaultLanguage() =
        testApp { deps ->
            jsonClient().setup(setupRequest(language = "ru"))
            val names = deps.db.dbQuery { GroupTable.selectAll().map { it[GroupTable.name] }.toSet() }
            assertEquals(setOf("Администраторы", "Взрослые", "Дети"), names)
        }

    @Test
    fun secondSetupIsRejected() =
        testApp {
            val client = jsonClient()
            client.setup()
            val response = client.post("/api/v1/setup") { json(setupRequest(login = "other")) }
            assertEquals(HttpStatusCode.Conflict, response.status)
            response.assertError(ErrorCode.SETUP_ALREADY_DONE)
        }

    @Test
    fun sixthWrongAttemptIsRateLimited() =
        testApp {
            val client = jsonClient()
            repeat(5) {
                client.post("/api/v1/setup") { json(setupRequest(code = "WRONG")) }.assertError(ErrorCode.SETUP_CODE_INVALID)
            }
            val response = client.post("/api/v1/setup") { json(setupRequest()) }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            response.assertError(ErrorCode.RATE_LIMITED)
        }

    @Test
    fun invalidFieldsAreReported() =
        testApp {
            val request =
                setupRequest(login = "a b", password = "short").copy(
                    householdName = " ",
                    timeZone = "Mars/Base",
                    currency = "rub",
                    defaultLanguage = "de",
                    displayName = "",
                )
            val response = jsonClient().post("/api/v1/setup") { json(request) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.assertError(ErrorCode.VALIDATION_FAILED)
            assertEquals(
                mapOf(
                    "householdName" to FieldErrorCode.REQUIRED,
                    "timeZone" to FieldErrorCode.INVALID_FORMAT,
                    "currency" to FieldErrorCode.INVALID_FORMAT,
                    "defaultLanguage" to FieldErrorCode.INVALID_FORMAT,
                    "displayName" to FieldErrorCode.REQUIRED,
                    "login" to FieldErrorCode.INVALID_FORMAT,
                    "password" to FieldErrorCode.TOO_SHORT,
                ),
                body.error.fields,
            )
        }

    @Test
    fun everySyncableRowGetsItsOwnSeq() =
        testApp { deps ->
            jsonClient().setup()
            deps.db.dbQuery {
                val lastSeq = HouseholdSyncStateTable.selectAll().single()[HouseholdSyncStateTable.lastSeq]
                val seqs =
                    HouseholdTable.selectAll().map { it[HouseholdTable.seq] } +
                        GroupTable.selectAll().map { it[GroupTable.seq] } +
                        PersonTable.selectAll().map { it[PersonTable.seq] } +
                        MemberTable.selectAll().map { it[MemberTable.seq] }
                // household, 3 groups, person, member; Administrators was re-stamped when the admin joined it.
                assertEquals(6, seqs.size)
                assertEquals(seqs.size, seqs.toSet().size, "no two rows share a seq: $seqs")
                assertTrue(seqs.all { it in 1..lastSeq }, "seqs $seqs within 1..$lastSeq")
                assertEquals(lastSeq, seqs.max(), "the newest change is stamped on a row")
            }
        }
}
