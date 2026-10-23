package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.contract.sync.MutationOp
import app.lares.contract.sync.MutationResult
import app.lares.contract.sync.MutationStatus
import app.lares.contract.sync.PullResponse
import app.lares.contract.sync.PushRequest
import app.lares.contract.sync.PushResponse
import app.lares.server.AppDeps
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Helpers for sync tests (M0-05).

/** Builds `fields` from plain values: strings, numbers, booleans, null or ready JSON elements. */
fun fields(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(
        pairs.associate { (key, value) ->
            key to
                when (value) {
                    null -> kotlinx.serialization.json.JsonNull
                    is JsonElement -> value
                    is String -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> error("Unsupported value $value")
                }
        },
    )

fun AppDeps.upsert(
    type: String,
    id: String,
    fields: JsonObject,
    mutationId: String = Ids.newId(),
) = Mutation(mutationId, type, id, MutationOp.UPSERT, fields, clock.now())

fun AppDeps.delete(
    type: String,
    id: String,
    mutationId: String = Ids.newId(),
) = Mutation(mutationId, type, id, MutationOp.DELETE, null, clock.now())

fun AppDeps.newTag(
    name: String,
    color: String = "blue",
    id: String = Ids.newId(),
) = upsert(EntityTypes.TAG, id, fields("name" to name, "color" to color))

suspend fun HttpClient.pushRaw(
    token: String,
    mutations: List<Mutation>,
): HttpResponse =
    post("/api/v1/sync/mutations") {
        bearer(token)
        json(PushRequest(mutations))
    }

suspend fun HttpClient.push(
    token: String,
    vararg mutations: Mutation,
): List<MutationResult> {
    val response = pushRaw(token, mutations.toList())
    assertEquals(HttpStatusCode.OK, response.status, "push")
    return response.body<PushResponse>().results
}

/** Pushes one mutation and returns its result. */
suspend fun HttpClient.pushOne(
    token: String,
    mutation: Mutation,
): MutationResult = push(token, mutation).single()

fun MutationResult.assertApplied(): Long? {
    assertEquals(MutationStatus.APPLIED, status, "result: $this")
    assertNull(error)
    return seq
}

fun MutationResult.assertRejected(code: ErrorCode) {
    assertEquals(MutationStatus.REJECTED, status, "result: $this")
    assertEquals(code, error?.code, "result: $this")
}

suspend fun HttpClient.pullRaw(
    token: String,
    after: Long = 0,
    limit: Int? = null,
    accessVersion: Long? = null,
    epoch: String? = null,
): HttpResponse =
    get("/api/v1/sync/changes") {
        bearer(token)
        parameter("after", after)
        limit?.let { parameter("limit", it) }
        accessVersion?.let { parameter("accessVersion", it) }
        epoch?.let { parameter("epoch", it) }
    }

suspend fun HttpClient.pull(
    token: String,
    after: Long = 0,
    limit: Int? = null,
    accessVersion: Long? = null,
    epoch: String? = null,
): PullResponse {
    val response = pullRaw(token, after, limit, accessVersion, epoch)
    assertEquals(HttpStatusCode.OK, response.status, "pull")
    return response.body()
}

/** Pulls incrementally from [from] (a previous response) until `hasMore = false`; returns the last page and all changes. */
suspend fun HttpClient.pullAll(
    token: String,
    from: PullResponse? = null,
    limit: Int? = null,
): Pair<PullResponse, List<Change>> {
    val changes = mutableListOf<Change>()
    var last = from
    do {
        val page = pull(token, last?.cursor ?: 0, limit, last?.accessVersion, last?.epoch)
        changes += page.changes
        last = page
    } while (page.hasMore)
    return last!! to changes
}

/** Decodes a change's data with the contract serializer. */
inline fun <reified T> Change.dataAs(): T = LaresJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), data!!)
