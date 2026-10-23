package app.familyhub.server.sync

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.permissions.WriteRules
import app.familyhub.contract.sync.Mutation
import app.familyhub.contract.sync.MutationOp
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.sync.recordChange
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID

/** A stored row of a writable type, loaded by id regardless of household (to tell "other household" apart). */
data class StoredEntity<D>(
    val householdId: UUID,
    val deleted: Boolean,
    val data: D,
)

/**
 * Push processing steps 4–7 of architecture/sync.md for a client-writable type with data class [D]:
 * household check, tombstone rules, shared `WriteRules`, field-level merge, strict decoding and validation.
 */
abstract class WritableSyncHandler<D : Any>(
    private val serializer: KSerializer<D>,
) : SyncEntityHandler {
    override val clientWritable: Boolean = true

    private val fieldNames: Set<String> =
        (0 until serializer.descriptor.elementsCount).mapTo(
            mutableSetOf(),
            serializer.descriptor::getElementName,
        )
    private val requiredFields: List<String> =
        (0 until serializer.descriptor.elementsCount)
            .filterNot(serializer.descriptor::isElementOptional)
            .map(serializer.descriptor::getElementName)

    protected abstract fun load(id: UUID): StoredEntity<D>?

    /** Field errors of the merged entity (shared validators from `contract/`). */
    protected abstract fun validate(
        ctx: MutationContext,
        data: D,
    ): Map<String, FieldErrorCode>

    /** Canonical form before validation and storage, e.g. trimmed names. */
    protected open fun normalize(data: D): D = data

    /** Extra checks for a write that will happen; throw [ApiException] to reject. [existing] is null for a create. */
    protected open fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: D?,
        data: D,
    ) = Unit

    protected open fun checkDelete(
        ctx: MutationContext,
        id: UUID,
    ) = Unit

    protected abstract fun insert(
        ctx: MutationContext,
        id: UUID,
        data: D,
        seq: Long,
    )

    protected abstract fun update(
        ctx: MutationContext,
        id: UUID,
        data: D,
        seq: Long,
    )

    protected abstract fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    )

    /** True if [id] is the viewer's own person profile (members may edit their own profile). */
    protected open fun isOwnProfile(
        ctx: MutationContext,
        id: UUID,
    ): Boolean = false

    fun toJson(data: D): JsonObject = FamilyHubJson.encodeToJsonElement(serializer, data).jsonObject

    override fun apply(
        tx: JdbcTransaction,
        ctx: MutationContext,
        mutation: Mutation,
    ): Long? {
        val id =
            runCatching { UUID.fromString(mutation.id) }.getOrNull()
                ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid entity id", mapOf("id" to FieldErrorCode.INVALID_FORMAT))
        val stored = load(id)
        if (stored != null && stored.householdId != ctx.householdId) {
            // Never reveal that the id exists elsewhere (ADR-0010).
            if (mutation.op == MutationOp.DELETE) return null
            throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Entity not found")
        }
        return when (mutation.op) {
            MutationOp.DELETE -> delete(tx, ctx, mutation, id, stored)
            MutationOp.UPSERT -> upsert(tx, ctx, mutation, id, stored)
        }
    }

    private fun delete(
        tx: JdbcTransaction,
        ctx: MutationContext,
        mutation: Mutation,
        id: UUID,
        stored: StoredEntity<D>?,
    ): Long? {
        if (stored == null || stored.deleted) return null
        requireAllowed(ctx, mutation, id)
        checkDelete(ctx, id)
        val seq = tx.recordChange(ctx.householdId)
        tombstone(ctx, id, seq)
        return seq
    }

    private fun upsert(
        tx: JdbcTransaction,
        ctx: MutationContext,
        mutation: Mutation,
        id: UUID,
        stored: StoredEntity<D>?,
    ): Long? {
        if (stored?.deleted == true) throw ApiException(ErrorCode.ENTITY_DELETED, "Entity was deleted")
        requireAllowed(ctx, mutation, id)
        val fields = mutation.fields ?: JsonObject(emptyMap())
        val data = normalize(decodeMerged(stored?.data, fields))
        val errors = validate(ctx, data)
        if (errors.isNotEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.keys.joinToString()}", errors)
        }
        if (stored != null && stored.data == data) return null
        checkUpsert(ctx, id, stored?.data, data)
        val seq = tx.recordChange(ctx.householdId)
        if (stored == null) insert(ctx, id, data, seq) else update(ctx, id, data, seq)
        return seq
    }

    private fun requireAllowed(
        ctx: MutationContext,
        mutation: Mutation,
        id: UUID,
    ) {
        if (!WriteRules.canMutate(type, mutation.op, ctx.viewer, existingPersonOwner = isOwnProfile(ctx, id))) {
            throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${mutation.op.name.lowercase()} $type")
        }
    }

    /**
     * Merges [fields] into the current data (field-level last-writer-wins) and decodes the result strictly:
     * unknown field names, missing required fields and values that do not survive a round trip unchanged
     * (e.g. an unknown enum value that the lenient decoder would map to a fallback) are `VALIDATION_FAILED`.
     */
    private fun decodeMerged(
        current: D?,
        fields: JsonObject,
    ): D {
        val unknown = fields.keys - fieldNames
        if (unknown.isNotEmpty()) throw invalid(unknown.associateWith { FieldErrorCode.INVALID_FORMAT })
        val base = current?.let(::toJson) ?: JsonObject(emptyMap())
        val merged = JsonObject(base + fields)
        val missing = requiredFields.filter { merged[it] == null || merged[it] == JsonNull }
        if (missing.isNotEmpty()) throw invalid(missing.associateWith { FieldErrorCode.REQUIRED })
        val decoded =
            try {
                FamilyHubJson.decodeFromJsonElement(serializer, merged)
            } catch (e: SerializationException) {
                throw invalid(emptyMap(), "Malformed fields: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw invalid(emptyMap(), "Malformed fields: ${e.message}")
            }
        val roundTrip = toJson(decoded)
        val changedByDecoding = fields.filter { (name, value) -> (roundTrip[name] ?: JsonNull) != value }.keys
        if (changedByDecoding.isNotEmpty()) throw invalid(changedByDecoding.associateWith { FieldErrorCode.INVALID_FORMAT })
        return decoded
    }

    private fun invalid(
        fields: Map<String, FieldErrorCode>,
        message: String = "Invalid fields: ${fields.keys.joinToString()}",
    ) = ApiException(ErrorCode.VALIDATION_FAILED, message, fields)
}
