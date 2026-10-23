package app.familyhub.client.data

import app.familyhub.client.db.LocalDb
import app.familyhub.client.sync.LocalWriter
import app.familyhub.client.sync.paletteColor
import app.familyhub.client.sync.toData
import app.familyhub.contract.Ids
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PaletteColors
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.sync.EntityTypes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// Use cases on top of LocalWriter. They throw LocalWriteException when the change is not allowed or invalid.

class TagsWriter(
    private val writer: LocalWriter,
) {
    /** Creates a tag (TAG-001) and returns its id. */
    suspend fun create(
        name: String,
        color: PaletteColor,
        icon: String? = null,
    ): String {
        val id = Ids.newId()
        val data = TagData(name = name, color = color, icon = icon)
        writer.upsert(EntityTypes.TAG, id, FamilyHubJson.encodeToJsonElement(TagData.serializer(), data).jsonObject)
        return id
    }

    suspend fun rename(
        id: String,
        name: String,
    ) = writer.upsert(EntityTypes.TAG, id, JsonObject(mapOf("name" to JsonPrimitive(name))))

    suspend fun recolor(
        id: String,
        color: PaletteColor,
    ) = writer.upsert(EntityTypes.TAG, id, JsonObject(mapOf("color" to JsonPrimitive(color.key))))

    suspend fun archive(
        id: String,
        archived: Boolean = true,
    ) = writer.upsert(EntityTypes.TAG, id, JsonObject(mapOf("archived" to JsonPrimitive(archived))))

    suspend fun delete(id: String) = writer.delete(EntityTypes.TAG, id)
}

class PersonsWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
) {
    /** Creates a relative and returns its id. Without a color, the least used personal color is picked (FND-034). */
    suspend fun create(data: PersonData): String {
        val id = Ids.newId()
        val color = data.color ?: PaletteColors.pickLeastUsed(usedColors())
        writer.upsert(EntityTypes.PERSON, id, encode(data.copy(color = color)))
        return id
    }

    /** Saves [changes] as the new data of the person; only fields that differ from the local copy are sent. */
    suspend fun update(
        id: String,
        changes: PersonData,
    ) {
        val current = localDb.read { personQueries.selectById(id).executeAsOneOrNull() }
        val currentJson = current?.let { encode(it.toData()) } ?: JsonObject(emptyMap())
        val newJson = encode(changes)
        val keys = (currentJson.keys + newJson.keys).filter { currentJson[it] != newJson[it] }
        if (current != null && keys.isEmpty()) return
        writer.upsert(EntityTypes.PERSON, id, JsonObject(keys.associateWith { newJson[it] ?: JsonNull }))
    }

    suspend fun delete(id: String) = writer.delete(EntityTypes.PERSON, id)

    /** The color [create] picks for a new person when none is given: the least used one (FND-034). */
    suspend fun nextColor(): PaletteColor = PaletteColors.pickLeastUsed(usedColors())

    private suspend fun usedColors(): List<PaletteColor> =
        localDb.read {
            personQueries.selectAll().executeAsList().map { paletteColor(it.color) ?: PaletteColors.fallbackFor(it.id) }
        }

    private fun encode(data: PersonData): JsonObject = FamilyHubJson.encodeToJsonElement(PersonData.serializer(), data).jsonObject
}
