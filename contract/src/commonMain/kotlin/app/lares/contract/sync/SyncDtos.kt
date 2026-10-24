package app.lares.contract.sync

import app.lares.contract.api.ApiError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

// Wire format of sync protocol v1 (architecture/sync.md). Entity data travels as the JSON of the type's data class
// from `entities/`, without id, seq and deleted.

/** Wire names of the entity types (architecture/sync.md § Entity types). Clients ignore types they do not know. */
object EntityTypes {
    const val HOUSEHOLD = "household"
    const val MEMBER = "member"
    const val GROUP = "group"
    const val PERSON = "person"
    const val TAG = "tag"
    const val TASK = "task"
    const val CHECKLIST_ITEM = "checklist_item"
    const val TASK_COMPLETION = "task_completion"
    const val SHOPPING_LIST = "shopping_list"
    const val SHOPPING_ITEM = "shopping_item"
    const val EVENT = "event"
    const val EVENT_OCCURRENCE = "event_occurrence"
    const val EVENT_REMINDER = "event_reminder"
}

/** Limits of pull and push. */
object SyncLimits {
    const val DEFAULT_PULL_LIMIT = 500
    const val MAX_PULL_LIMIT = 1000
    const val MAX_MUTATIONS = 100

    /** Mutations created longer ago are rejected with `MUTATION_EXPIRED`; the idempotency log is kept as long. */
    const val MUTATION_TTL_DAYS = 30

    /** Tombstones older than this may be purged (then `purge_horizon_seq` moves). */
    const val TOMBSTONE_TTL_DAYS = 90
}

/** One changed row. [data] is null for tombstones. */
@Serializable
data class Change(
    val type: String,
    val id: String,
    val seq: Long,
    val deleted: Boolean,
    val data: JsonObject? = null,
)

/** Response of `GET /api/v1/sync/changes`. */
@Serializable
data class PullResponse(
    val reset: Boolean,
    val accessVersion: Long,
    val epoch: String,
    val changes: List<Change>,
    val cursor: Long,
    val hasMore: Boolean,
)

@Serializable
enum class MutationOp {
    @SerialName("upsert")
    UPSERT,

    @SerialName("delete")
    DELETE,
}

/** A client change from the outbox. [fields] holds only changed fields (upsert); it is absent for delete. */
@Serializable
data class Mutation(
    val mutationId: String,
    val type: String,
    val id: String,
    val op: MutationOp,
    val fields: JsonObject? = null,
    val createdAt: Instant,
)

/** Body of `POST /api/v1/sync/mutations`: at most [SyncLimits.MAX_MUTATIONS], in outbox order. */
@Serializable
data class PushRequest(
    val mutations: List<Mutation>,
)

@Serializable
enum class MutationStatus {
    @SerialName("applied")
    APPLIED,

    @SerialName("rejected")
    REJECTED,
}

/** [seq] is null for no-op applies; [duplicate] marks a result replayed from the idempotency log. */
@Serializable
data class MutationResult(
    val mutationId: String,
    val status: MutationStatus,
    val seq: Long? = null,
    val error: ApiError? = null,
    val duplicate: Boolean = false,
)

@Serializable
data class PushResponse(
    val results: List<MutationResult>,
)

/** WebSocket message of `/api/v1/sync/events`: [type] is [HELLO] or [CHANGED]; [seq] is the household's newest seq. */
@Serializable
data class SyncEvent(
    val type: String,
    val seq: Long,
) {
    companion object {
        const val HELLO = "hello"
        const val CHANGED = "changed"
    }
}
