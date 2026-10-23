package app.familyhub.server.testing

import app.familyhub.contract.api.admin.CreateLoginCodeRequest
import app.familyhub.contract.api.admin.CreateMemberRequest
import app.familyhub.contract.api.admin.LoginCodeCreated
import app.familyhub.contract.api.admin.LoginWithCodeRequest
import app.familyhub.contract.api.admin.MemberCreated
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.server.AppDeps
import app.familyhub.server.household.GroupTable
import app.familyhub.server.household.MemberTable
import app.familyhub.server.household.PersonRepository
import app.familyhub.server.platform.db.HouseholdSyncStateTable
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.recordChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.assertEquals

// Helpers for household administration tests (M0-04).

const val ADULTS = "Adults"
const val KIDS = "Kids"
const val MEMBER_PASSWORD = "member password"

val AuthResult.householdUuid: UUID get() = UUID.fromString(me.householdId)

/** Id of a group by its template name (setup in English) or of Administrators. */
suspend fun AppDeps.groupId(
    householdId: UUID,
    name: String,
): UUID =
    db.dbQuery {
        GroupTable
            .selectAll()
            .where { (GroupTable.householdId eq householdId) and (GroupTable.name eq name) }
            .single()[GroupTable.id]
    }

suspend fun AppDeps.administratorsId(householdId: UUID): UUID =
    db.dbQuery {
        GroupTable
            .selectAll()
            .where { (GroupTable.householdId eq householdId) and (GroupTable.builtIn eq BuiltInGroup.ADMINISTRATORS.name) }
            .single()[GroupTable.id]
    }

suspend fun AppDeps.accessVersion(memberId: String): Long =
    db.dbQuery { MemberTable.selectAll().where { MemberTable.id eq UUID.fromString(memberId) }.single()[MemberTable.accessVersion] }

suspend fun AppDeps.groupSeq(groupId: UUID): Long =
    db.dbQuery {
        GroupTable.selectAll().where { GroupTable.id eq groupId }.single()[GroupTable.seq]
    }

suspend fun AppDeps.lastSeq(householdId: UUID): Long =
    db.dbQuery {
        HouseholdSyncStateTable
            .selectAll()
            .where {
                HouseholdSyncStateTable.householdId eq householdId
            }.single()[HouseholdSyncStateTable.lastSeq]
    }

suspend fun HttpClient.createMember(
    adminToken: String,
    request: CreateMemberRequest,
): MemberCreated {
    val response =
        post("/api/v1/members") {
            bearer(adminToken)
            json(request)
        }
    assertEquals(HttpStatusCode.Created, response.status, "create member")
    return response.body()
}

/** Creates a member with login [login] and [MEMBER_PASSWORD] in [groupIds] and logs them in. */
suspend fun HttpClient.memberSession(
    adminToken: String,
    login: String,
    groupIds: Set<UUID>,
): AuthResult {
    createMember(adminToken, CreateMemberRequest(login, login, MEMBER_PASSWORD, groupIds.mapTo(mutableSetOf(), UUID::toString)))
    val response = login(login, MEMBER_PASSWORD)
    assertEquals(HttpStatusCode.OK, response.status, "login $login")
    return response.body()
}

suspend fun HttpClient.createLoginCode(
    token: String,
    memberId: String? = null,
): HttpResponse =
    post("/api/v1/login-codes") {
        bearer(token)
        json(CreateLoginCodeRequest(memberId))
    }

suspend fun HttpClient.exchangeLoginCode(code: String): HttpResponse =
    post("/api/v1/auth/login-code") { json(LoginWithCodeRequest(code, testDevice)) }

suspend fun HttpClient.loginCodeFor(
    token: String,
    memberId: String? = null,
): LoginCodeCreated {
    val response = createLoginCode(token, memberId)
    assertEquals(HttpStatusCode.Created, response.status, "create login code")
    return response.body()
}

/**
 * A second household with its own administrator, created through the setup code path but bypassing the
 * one-household rule (test only). Log in with [login] / [ADMIN_PASSWORD].
 */
suspend fun AppDeps.createSecondHousehold(login: String = "admin-b"): UUID {
    val hash = passwordHasher.hash(ADMIN_PASSWORD)
    val result =
        db.dbQuery {
            with(setupService) { createHousehold(setupRequest(login = login).copy(householdName = "The Joneses"), hash) }
        }
    return UUID.fromString(result.me.householdId)
}

/** Sends a request with an optional bearer token and JSON body. */
suspend fun HttpClient.send(
    method: HttpMethod,
    path: String,
    token: String?,
    body: Any? = null,
): HttpResponse =
    request(path) {
        this.method = method
        token?.let { bearer(it) }
        body?.let { json(it) }
    }

/** A relative without an account (persons are written through sync from M0-05 on). */
suspend fun AppDeps.createRelative(
    householdId: UUID,
    name: String,
): UUID =
    db.dbQuery {
        val id = UUID.randomUUID()
        PersonRepository.create(
            householdId,
            id,
            PersonData(name = name),
            recordChange(householdId),
            clock.nowForDb(),
        )
        id
    }
