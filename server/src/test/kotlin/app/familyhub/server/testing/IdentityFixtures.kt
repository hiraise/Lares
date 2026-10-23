package app.familyhub.server.testing

import app.familyhub.contract.api.ApiErrorBody
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.DeviceInfo
import app.familyhub.contract.api.auth.LoginRequest
import app.familyhub.contract.api.auth.SetupRequest
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.server.AppDeps
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.GroupTable
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PersonRepository
import app.familyhub.server.identity.UserRepository
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.recordChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.assertEquals

const val ADMIN_LOGIN = "admin"
const val ADMIN_PASSWORD = "correct horse battery"

val testDevice = DeviceInfo(name = "Pixel 9", platform = "android")

fun setupRequest(
    code: String = TEST_SETUP_CODE,
    language: String = "en",
    login: String = ADMIN_LOGIN,
    password: String = ADMIN_PASSWORD,
) = SetupRequest(
    setupCode = code,
    householdName = "The Smiths",
    timeZone = "Europe/Moscow",
    currency = "RUB",
    defaultLanguage = language,
    displayName = "Anna",
    login = login,
    password = password,
    device = testDevice,
)

fun HttpRequestBuilder.json(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

fun HttpRequestBuilder.bearer(token: String) = bearerAuth(token)

suspend fun HttpClient.setup(request: SetupRequest = setupRequest()): AuthResult {
    val response = post("/api/v1/setup") { json(request) }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}

suspend fun HttpClient.login(
    login: String,
    password: String,
): HttpResponse = post("/api/v1/auth/login") { json(LoginRequest(login, password, testDevice)) }

/** Asserts an error response with the given code and returns its body. */
suspend fun HttpResponse.assertError(code: ErrorCode): ApiErrorBody {
    val body = body<ApiErrorBody>()
    assertEquals(code, body.error.code, "body: $body")
    return body
}

/**
 * Creates a second user directly in the database (M0-04 adds the endpoints): user, person, member and,
 * if [admin], membership in Administrators. Returns the member id.
 */
suspend fun AppDeps.createUserDirectly(
    householdId: UUID,
    login: String,
    password: String,
    admin: Boolean,
): UUID {
    val hash = passwordHasher.hash(password)
    return db.dbQuery {
        val now = clock.nowForDb()
        val userId = UUID.randomUUID()
        UserRepository.create(userId, login, hash, now)
        val personId = UUID.randomUUID()
        PersonRepository.create(householdId, personId, PersonData(name = login), recordChange(householdId), now)
        val memberId = UUID.randomUUID()
        MemberRepository.create(householdId, memberId, userId, personId, recordChange(householdId), now)
        if (admin) {
            val adminsId =
                GroupTable
                    .selectAll()
                    .where { (GroupTable.householdId eq householdId) and (GroupTable.builtIn eq BuiltInGroup.ADMINISTRATORS.name) }
                    .single()[GroupTable.id]
            GroupRepository.addMember(adminsId, memberId)
            GroupRepository.stampSeq(householdId, adminsId, recordChange(householdId), now)
        }
        memberId
    }
}
