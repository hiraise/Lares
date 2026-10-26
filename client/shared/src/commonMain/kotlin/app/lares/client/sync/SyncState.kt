package app.lares.client.sync

import app.lares.client.db.LaresDb
import app.lares.contract.api.ErrorCode
import kotlin.time.Instant

/**
 * Version of the local data layout. Raise it when the client starts storing data differently (new typed columns,
 * a new entity type); a mismatch with the stored value makes the next sync a full resync.
 */
const val DATA_SCHEMA_VERSION = 5

/** What `sync_state` holds. [cursor] 0 means "never synced". */
data class SyncStateValues(
    val cursor: Long,
    val accessVersion: Long?,
    val epoch: String?,
    val dataSchemaVersion: Int?,
    val lastSuccessAt: Instant?,
    val lastError: ErrorCode?,
) {
    /** True if the next pull must start from scratch. */
    val needsFullSync: Boolean
        get() = cursor <= 0 || accessVersion == null || epoch == null || dataSchemaVersion != DATA_SCHEMA_VERSION
}

/** Typed access to the `sync_state` key-value table. Functions run inside a transaction or a read. */
internal object SyncStateStore {
    private const val CURSOR = "cursor"
    private const val ACCESS_VERSION = "accessVersion"
    private const val EPOCH = "epoch"
    private const val DATA_SCHEMA_VERSION_KEY = "dataSchemaVersion"
    private const val LAST_SUCCESS_AT = "lastSuccessAt"
    private const val LAST_ERROR = "lastError"

    fun load(db: LaresDb): SyncStateValues {
        fun get(key: String) = db.sync_stateQueries.get(key).executeAsOneOrNull()
        return SyncStateValues(
            cursor = get(CURSOR)?.toLongOrNull() ?: 0,
            accessVersion = get(ACCESS_VERSION)?.toLongOrNull(),
            epoch = get(EPOCH),
            dataSchemaVersion = get(DATA_SCHEMA_VERSION_KEY)?.toIntOrNull(),
            lastSuccessAt = get(LAST_SUCCESS_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            lastError = get(LAST_ERROR)?.let { name -> ErrorCode.entries.firstOrNull { it.name == name } ?: ErrorCode.UNKNOWN },
        )
    }

    fun cursor(db: LaresDb): Long =
        db.sync_stateQueries
            .get(CURSOR)
            .executeAsOneOrNull()
            ?.toLongOrNull() ?: 0

    /** Saved together with every applied page. */
    fun savePosition(
        db: LaresDb,
        cursor: Long,
        accessVersion: Long,
        epoch: String,
    ) {
        db.sync_stateQueries.put(CURSOR, cursor.toString())
        db.sync_stateQueries.put(ACCESS_VERSION, accessVersion.toString())
        db.sync_stateQueries.put(EPOCH, epoch)
        db.sync_stateQueries.put(DATA_SCHEMA_VERSION_KEY, DATA_SCHEMA_VERSION.toString())
    }

    fun saveResult(
        db: LaresDb,
        lastSuccessAt: Instant?,
        lastError: ErrorCode?,
    ) {
        if (lastSuccessAt != null) db.sync_stateQueries.put(LAST_SUCCESS_AT, lastSuccessAt.toString())
        if (lastError == null) db.sync_stateQueries.remove(LAST_ERROR) else db.sync_stateQueries.put(LAST_ERROR, lastError.name)
    }
}
