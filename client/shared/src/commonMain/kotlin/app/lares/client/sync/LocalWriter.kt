package app.lares.client.sync

import app.lares.client.auth.LocalSession
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.permissions.Viewer
import app.lares.contract.permissions.WriteRules
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Local writes (architecture/sync.md § Local write): in one transaction check the shared `WriteRules`, build and
 * validate the merged entity, write the typed row and append the mutation to the outbox; then request a sync.
 * Forbidden or invalid writes throw [LocalWriteException] and store nothing.
 */
class LocalWriter(
    private val localDb: LocalDb,
    private val adapters: EntityAdapters,
    private val clock: Clock,
    private val syncEngine: SyncEngine,
) {
    /** Creates the entity if it does not exist (then [fields] must hold all required fields), otherwise patches it. */
    suspend fun upsert(
        type: String,
        id: String,
        fields: JsonObject,
    ) {
        val changed = localDb.transaction { upsert(this, writableAdapter(type), id, fields) }
        if (changed) syncEngine.requestAfterLocalWrite()
    }

    suspend fun delete(
        type: String,
        id: String,
    ) {
        val changed = localDb.transaction { delete(this, writableAdapter(type), id) }
        if (changed) syncEngine.requestAfterLocalWrite()
    }

    private fun writableAdapter(type: String): EntityAdapter<*> {
        val adapter = adapters[type] ?: throw IllegalArgumentException("Unknown entity type $type")
        require(adapter.writable) { "Entity type $type is read-only for local writes" }
        return adapter
    }

    private fun <T : Any> upsert(
        db: LaresDb,
        adapter: EntityAdapter<T>,
        id: String,
        fields: JsonObject,
    ): Boolean {
        val viewer = viewer(db)
        val currentData = adapter.selectTyped(db, id)
        val current = currentData?.let(adapter::encode)
        if (current == null && isKnown(db, adapter.type, id)) throw LocalWriteException(ErrorCode.ENTITY_DELETED)
        requireAllowed(adapter.type, MutationOp.UPSERT, viewer, id)

        val unknown = fields.keys - adapter.fieldNames
        if (unknown.isNotEmpty()) throw LocalValidationException(unknown.associateWith { FieldErrorCode.INVALID_FORMAT })
        val merged = JsonObject((current ?: JsonObject(emptyMap())) + fields)
        val missing = adapter.missingRequired(merged)
        if (missing.isNotEmpty()) throw LocalValidationException(missing.associateWith { FieldErrorCode.REQUIRED })
        val decoded =
            adapter.decodeOrNull(merged)
                ?: throw LocalValidationException(fields.keys.associateWith { FieldErrorCode.INVALID_FORMAT })
        val data = adapter.normalize(decoded)
        adapter.authorize(db, viewer, MutationOp.UPSERT, id, currentData, data)
        val errors = adapter.validate(data) + adapter.localConflicts(db, id, data)
        if (errors.isNotEmpty()) throw LocalValidationException(errors)

        val normalized = adapter.encode(data)
        if (normalized == current) return false
        adapter.upsertTyped(db, id, data)
        // Send the normalized values of the changed fields; a field set to null travels as JSON null.
        val outgoing = JsonObject(fields.keys.associateWith { normalized[it] ?: JsonNull })
        enqueue(db, adapter.type, id, MutationOp.UPSERT, outgoing)
        return true
    }

    private fun <T : Any> delete(
        db: LaresDb,
        adapter: EntityAdapter<T>,
        id: String,
    ): Boolean {
        val viewer = viewer(db)
        val current = adapter.selectTyped(db, id) ?: return false
        requireAllowed(adapter.type, MutationOp.DELETE, viewer, id)
        adapter.authorize(db, viewer, MutationOp.DELETE, id, current, null)
        adapter.checkDelete(db, id, current)
        adapter.deleteTyped(db, id)
        enqueue(db, adapter.type, id, MutationOp.DELETE, null)
        return true
    }

    private fun viewer(db: LaresDb): Viewer = LocalSession.viewer(db) ?: throw LocalForbiddenException("Not logged in")

    /** True if the entity exists in the shadow or the outbox, i.e. an absent typed row means it was deleted. */
    private fun isKnown(
        db: LaresDb,
        type: String,
        id: String,
    ): Boolean =
        db.sync_shadowQueries.get(type, id).executeAsOneOrNull() != null ||
            db.sync_outboxQueries
                .byEntity(type, id)
                .executeAsList()
                .isNotEmpty()

    private fun requireAllowed(
        type: String,
        op: MutationOp,
        viewer: Viewer,
        id: String,
    ) {
        // Same ownership rule as the server: the id is the viewer's own person profile.
        val ownProfile = type == EntityTypes.PERSON && id == viewer.personId
        if (!WriteRules.canMutate(type, op, viewer, ownProfile)) {
            throw LocalForbiddenException("Not allowed to ${op.name.lowercase()} $type")
        }
    }

    private fun enqueue(
        db: LaresDb,
        type: String,
        id: String,
        op: MutationOp,
        fields: JsonObject?,
    ) {
        db.sync_outboxQueries.enqueue(
            mutation_id = Ids.newId(),
            type = type,
            entity_id = id,
            op = Materializer.opName(op),
            fields = fields?.let { LaresJson.encodeToString(JsonObject.serializer(), it) },
            created_at = clock.now().toString(),
        )
    }
}
