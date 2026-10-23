package app.lares.contract.sync

import app.lares.contract.api.ApiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.TagData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The JSON examples of architecture/sync.md decode to the DTOs and encode back to the same JSON. */
class SyncDtosTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun pullResponseMatchesTheDocumentedExample() {
        val text =
            """
            {
              "reset": false,
              "accessVersion": 7,
              "epoch": "01928c40-0000-7000-8000-000000000000",
              "changes": [
                { "type": "tag", "id": "01928c4e-0000-7000-8000-000000000000", "seq": 42, "deleted": false,
                  "data": { "name": "Car", "color": "blue", "archived": false } },
                { "type": "person", "id": "01928c4f-0000-7000-8000-000000000000", "seq": 43, "deleted": true }
              ],
              "cursor": 43,
              "hasMore": false
            }
            """.trimIndent()
        val response = LaresJson.decodeFromString(PullResponse.serializer(), text)
        assertEquals(43, response.cursor)
        assertEquals(2, response.changes.size)
        assertNull(response.changes[1].data)
        val tag = LaresJson.decodeFromJsonElement(TagData.serializer(), response.changes[0].data!!)
        assertEquals(TagData("Car", PaletteColor.BLUE), tag)
        assertEquals(json(text), LaresJson.encodeToJsonElement(PullResponse.serializer(), response))
    }

    @Test
    fun pushRequestMatchesTheDocumentedExample() {
        val text =
            """
            {
              "mutations": [
                { "mutationId": "01928c50-0000-7000-8000-000000000000", "type": "tag", "id": "01928c4e-0000-7000-8000-000000000000",
                  "op": "upsert", "fields": { "name": "Car", "color": "blue" }, "createdAt": "2026-10-22T10:15:00Z" },
                { "mutationId": "01928c51-0000-7000-8000-000000000000", "type": "person", "id": "01928c4f-0000-7000-8000-000000000000",
                  "op": "delete", "createdAt": "2026-10-22T10:16:00Z" }
              ]
            }
            """.trimIndent()
        val request = LaresJson.decodeFromString(PushRequest.serializer(), text)
        assertEquals(MutationOp.UPSERT, request.mutations[0].op)
        assertEquals(MutationOp.DELETE, request.mutations[1].op)
        assertEquals(Instant.parse("2026-10-22T10:16:00Z"), request.mutations[1].createdAt)
        assertEquals(json(text), LaresJson.encodeToJsonElement(PushRequest.serializer(), request))
    }

    @Test
    fun pushResponseMatchesTheDocumentedExample() {
        val text =
            """
            {
              "results": [
                { "mutationId": "01928c50", "status": "applied", "seq": 44, "duplicate": false },
                { "mutationId": "01928c51", "status": "rejected",
                  "error": { "code": "ENTITY_NOT_FOUND", "message": "Not found", "fields": {} }, "duplicate": false },
                { "mutationId": "01928c4a", "status": "applied", "seq": 40, "duplicate": true }
              ]
            }
            """.trimIndent()
        val response = LaresJson.decodeFromString(PushResponse.serializer(), text)
        assertEquals(
            listOf(
                MutationResult("01928c50", MutationStatus.APPLIED, seq = 44),
                MutationResult("01928c51", MutationStatus.REJECTED, error = ApiError(ErrorCode.ENTITY_NOT_FOUND, "Not found")),
                MutationResult("01928c4a", MutationStatus.APPLIED, seq = 40, duplicate = true),
            ),
            response.results,
        )
        assertEquals(json(text), LaresJson.encodeToJsonElement(PushResponse.serializer(), response))
        // `duplicate` may be omitted by the server; it defaults to false.
        val short = """{"results":[{"mutationId":"m","status":"applied","seq":1}]}"""
        assertEquals(false, LaresJson.decodeFromString(PushResponse.serializer(), short).results[0].duplicate)
    }

    @Test
    fun syncEventMatchesTheDocumentedMessages() {
        assertEquals(
            json("""{"type":"hello","seq":5}"""),
            LaresJson.encodeToJsonElement(SyncEvent.serializer(), SyncEvent(SyncEvent.HELLO, 5)),
        )
        assertEquals(
            SyncEvent(SyncEvent.CHANGED, 9),
            LaresJson.decodeFromString(SyncEvent.serializer(), """{"type":"changed","seq":9}"""),
        )
    }

    @Test
    fun personColorIsOptionalAndUnknownColorsFallBackToGray() {
        val old = LaresJson.decodeFromString(PersonData.serializer(), """{"name":"Grandma"}""")
        assertNull(old.color)
        val newer = LaresJson.decodeFromString(PersonData.serializer(), """{"name":"Grandma","color":"turquoise"}""")
        assertEquals(PaletteColor.GRAY, newer.color)
        val encoded = LaresJson.encodeToJsonElement(PersonData.serializer(), PersonData("Ann", color = PaletteColor.TEAL))
        assertEquals(JsonPrimitive("teal"), (encoded as kotlinx.serialization.json.JsonObject)["color"])
    }

    @Test
    fun tagDataDefaults() {
        val fields = buildJsonObject { put("name", "Car") }
        val withColor =
            buildJsonObject {
                put("name", "Car")
                put("color", "red")
            }
        kotlin.test.assertFails { LaresJson.decodeFromJsonElement(TagData.serializer(), fields) }
        assertEquals(
            TagData("Car", PaletteColor.RED, icon = null, archived = false),
            LaresJson.decodeFromJsonElement(TagData.serializer(), withColor),
        )
    }
}
