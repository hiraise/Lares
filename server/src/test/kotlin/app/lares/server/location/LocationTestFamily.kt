package app.lares.server.location

import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.LocationNow
import app.lares.server.AppDeps
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.memberSession
import app.lares.server.testing.send
import app.lares.server.testing.setup
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.assertEquals

/** A family with the default rules: the administrator, one adult and two kids. */
internal class LocationFamily(
    val admin: AuthResult,
    val adult: AuthResult,
    val kid: AuthResult,
    val kid2: AuthResult,
) {
    val householdId: UUID get() = admin.householdUuid
}

internal suspend fun HttpClient.locationFamily(deps: AppDeps): LocationFamily {
    val admin = setup()
    val adults = deps.groupId(admin.householdUuid, ADULTS)
    val kids = deps.groupId(admin.householdUuid, KIDS)
    return LocationFamily(
        admin = admin,
        adult = memberSession(admin.accessToken, "dima", setOf(adults)),
        kid = memberSession(admin.accessToken, "anya", setOf(kids)),
        kid2 = memberSession(admin.accessToken, "petya", setOf(kids)),
    )
}

internal val AuthResult.memberId: String get() = me.memberId

internal val AuthResult.memberUuid: UUID get() = UUID.fromString(me.memberId)

internal suspend fun HttpClient.locationNow(token: String): LocationNow {
    val response = send(HttpMethod.Get, "/api/v1/location/now", token)
    assertEquals(HttpStatusCode.OK, response.status, "now")
    return response.body()
}

internal fun LocationNow.of(member: AuthResult) = members.single { it.memberId == member.me.memberId }

/** Coordinates near the centre of Moscow. */
internal const val LAT = 55.7558
internal const val LON = 37.6173
