package app.lares.client.sync

import app.lares.client.db.LaresDb
import app.lares.client.db.Sync_outbox
import app.lares.client.db.Sync_shadow
import app.lares.contract.api.LaresJson
import app.lares.contract.sync.MutationOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Rebuilds typed rows by the materialization rule of architecture/sync.md: typed row = decode(shadow ⊕ every pending
 * outbox `fields` of the entity, in order); absent if the shadow is a tombstone or any pending op is a delete.
 */
class Materializer(
    private val adapters: EntityAdapters,
) {
    /** Must run inside a transaction. Unknown types are ignored. */
    fun rematerialize(
        db: LaresDb,
        type: String,
        id: String,
    ) {
        val adapter = adapters[type] ?: return
        val shadow = db.sync_shadowQueries.get(type, id).executeAsOneOrNull()
        val pending = db.sync_outboxQueries.byEntity(type, id).executeAsList()
        val merged = merge(shadow, pending)
        if (merged == null) adapter.deleteTyped(db, id) else adapter.upsertFromJson(db, id, merged)
    }

    companion object {
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"

        fun opName(op: MutationOp): String =
            when (op) {
                MutationOp.UPSERT -> OP_UPSERT
                MutationOp.DELETE -> OP_DELETE
            }

        /** The merged entity JSON, or null if the entity is absent (deleted, or neither shadow nor pending changes). */
        fun merge(
            shadow: Sync_shadow?,
            pending: List<Sync_outbox>,
        ): JsonObject? {
            if (shadow?.deleted == 1L || pending.any { it.op == OP_DELETE }) return null
            if (shadow == null && pending.isEmpty()) return null
            var data = shadow?.data_?.let(::parseObject) ?: JsonObject(emptyMap())
            for (entry in pending) {
                val fields = entry.fields?.let(::parseObject) ?: continue
                data = JsonObject(data + fields)
            }
            return data
        }

        fun parseObject(text: String): JsonObject = LaresJson.parseToJsonElement(text).jsonObject
    }
}
