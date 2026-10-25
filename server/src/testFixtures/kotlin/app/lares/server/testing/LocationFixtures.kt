package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.LocationRules
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.time.Instant

// Helpers for location tests (M4-02, M4-03); reused by later M4 plans and integration-tests.

/** A new place by [createdBy] (a member id) near the centre of Moscow. [extra] adds or overrides fields. */
fun AppDeps.newPlace(
    createdBy: String,
    name: String = "School",
    latitude: Double = 55.7558,
    longitude: Double = 37.6173,
    radiusM: Int = 150,
    extra: Map<String, Any?> = emptyMap(),
    id: String = Ids.newId(),
): Mutation =
    upsert(
        EntityTypes.PLACE,
        id,
        JsonObject(
            fields(
                "name" to name,
                "latitude" to latitude,
                "longitude" to longitude,
                "radiusM" to radiusM,
                "createdBy" to createdBy,
            ) + fields(*extra.toList().toTypedArray()),
        ),
    )

/** An upsert of [subscriberId]'s alerts about [memberId] with its derived id unless [id] is given. */
fun AppDeps.newPlaceAlert(
    memberId: String,
    subscriberId: String,
    placeIds: Set<String>,
    id: String = Ids.placeAlertRowId(memberId, subscriberId),
): Mutation =
    upsert(
        EntityTypes.PLACE_ALERT,
        id,
        fields("memberId" to memberId, "subscriberId" to subscriberId, "placeIds" to JsonArray(placeIds.map(::JsonPrimitive))),
    )

/** `PUT /location/rules` with [token]; returns the response for the caller to check. */
suspend fun HttpClient.putRules(
    token: String,
    rules: List<LocationRuleInput>,
): HttpResponse = send(HttpMethod.Put, "/api/v1/location/rules", token, PutLocationRulesRequest(rules))

/** `GET /location/rules` with [token], expecting 200. */
suspend fun HttpClient.getRules(token: String): LocationRules {
    val response = send(HttpMethod.Get, "/api/v1/location/rules", token)
    assertEquals(HttpStatusCode.OK, response.status, "get rules")
    return response.body()
}

/** A fix at [at] (device time). */
fun fix(
    latitude: Double,
    longitude: Double,
    at: Instant,
    accuracyM: Float = 10f,
): PositionFix = PositionFix(recordedAt = at, latitude = latitude, longitude = longitude, accuracyM = accuracyM)

/** `PUT /location/me/sharing` with [token]; returns the response for the caller to check. */
suspend fun HttpClient.setSharingRaw(
    token: String,
    status: SharingStatus,
    pausedUntil: Instant? = null,
    reason: OffReason? = null,
): HttpResponse = send(HttpMethod.Put, "/api/v1/location/me/sharing", token, SetSharingRequest(status, pausedUntil, reason))

/** `PUT /location/me/sharing` with [token], expecting 204. */
suspend fun HttpClient.setSharing(
    token: String,
    status: SharingStatus = SharingStatus.SHARING,
    pausedUntil: Instant? = null,
    reason: OffReason? = null,
) {
    assertEquals(HttpStatusCode.NoContent, setSharingRaw(token, status, pausedUntil, reason).status, "set sharing $status")
}

/** `POST /location/positions` with [token], expecting 200. */
suspend fun HttpClient.upload(
    token: String,
    fixes: List<PositionFix>,
): UploadPositionsResponse {
    val response = send(HttpMethod.Post, "/api/v1/location/positions", token, UploadPositionsRequest(fixes))
    assertEquals(HttpStatusCode.OK, response.status, "upload")
    return response.body()
}
