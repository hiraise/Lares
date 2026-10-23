package app.familyhub.client.sync

import app.familyhub.client.db.FamilyHubDb
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.FieldErrorCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Connects one sync entity type to its typed table. Entity data travels and is merged as the JSON of the contract
 * data class [T]; the adapter decodes it and writes the typed row the UI reads. All functions run inside a transaction.
 */
abstract class EntityAdapter<T : Any>(
    /** Wire name, see `EntityTypes`. */
    val type: String,
    val serializer: KSerializer<T>,
    /** False for types only the server changes (`household`, `member`, `group`). */
    val writable: Boolean,
) {
    private val requiredFields: List<String> =
        (0 until serializer.descriptor.elementsCount)
            .filterNot(serializer.descriptor::isElementOptional)
            .map(serializer.descriptor::getElementName)

    val fieldNames: Set<String> =
        (0 until serializer.descriptor.elementsCount).mapTo(mutableSetOf(), serializer.descriptor::getElementName)

    abstract fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ): T?

    abstract fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: T,
    )

    abstract fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    )

    abstract fun deleteAllTyped(db: FamilyHubDb)

    /** Field errors of a locally written entity (shared validators from `contract/`). */
    open fun validate(data: T): Map<String, FieldErrorCode> = emptyMap()

    /** Canonical form before a local write, the same as the server's (e.g. trimmed names). */
    open fun normalize(data: T): T = data

    /** Checks against other local rows, e.g. tag name uniqueness (TAG-006); the server has the final word. */
    open fun localConflicts(
        db: FamilyHubDb,
        id: String,
        data: T,
    ): Map<String, FieldErrorCode> = emptyMap()

    fun encode(data: T): JsonObject = FamilyHubJson.encodeToJsonElement(serializer, data).jsonObject

    /** Lenient decoding, as for everything that comes from the server. Returns null if [json] is not a valid entity. */
    fun decodeOrNull(json: JsonObject): T? =
        try {
            FamilyHubJson.decodeFromJsonElement(serializer, json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Required fields that are missing or null in [json] (a create must carry all of them). */
    fun missingRequired(json: JsonObject): List<String> = requiredFields.filter { json[it] == null || json[it] == JsonNull }

    /** Writes the typed row from merged JSON, or removes it if the JSON does not decode. */
    fun upsertFromJson(
        db: FamilyHubDb,
        id: String,
        json: JsonObject,
    ) {
        val data = decodeOrNull(json)
        if (data == null) deleteTyped(db, id) else upsertTyped(db, id, data)
    }

    fun selectJson(
        db: FamilyHubDb,
        id: String,
    ): JsonObject? = selectTyped(db, id)?.let(::encode)
}

/** The adapters of all entity types this client version knows; changes of other types are ignored. */
class EntityAdapters(
    adapters: List<EntityAdapter<*>>,
) {
    private val byType: Map<String, EntityAdapter<*>> = adapters.associateBy { it.type }

    val all: Collection<EntityAdapter<*>> get() = byType.values

    operator fun get(type: String): EntityAdapter<*>? = byType[type]
}
