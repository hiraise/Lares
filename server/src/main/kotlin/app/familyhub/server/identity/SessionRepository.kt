package app.familyhub.server.identity

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class SessionRow(
    val id: UUID,
    val userId: UUID,
    val memberId: UUID,
    val householdId: UUID,
    val deviceName: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
    val rotatedAt: Instant?,
    val accessUsedSinceRotation: Boolean,
    val revokedAt: Instant?,
)

/** Hashes and expiry of a freshly issued token pair. */
data class TokenHashes(
    val accessHash: ByteArray,
    val accessExpiresAt: Instant,
    val refreshHash: ByteArray,
    val refreshExpiresAt: Instant,
)

object SessionRepository {
    fun create(
        id: UUID,
        userId: UUID,
        memberId: UUID,
        householdId: UUID,
        deviceName: String,
        platform: String,
        tokens: TokenHashes,
        now: Instant,
    ) {
        SessionTable.insert {
            it[SessionTable.id] = id
            it[SessionTable.userId] = userId
            it[SessionTable.memberId] = memberId
            it[SessionTable.householdId] = householdId
            it[SessionTable.deviceName] = deviceName
            it[SessionTable.platform] = platform
            it[createdAt] = now
            it[lastSeenAt] = now
            it[accessHash] = tokens.accessHash
            it[accessExpiresAt] = tokens.accessExpiresAt
            it[refreshHash] = tokens.refreshHash
            it[refreshExpiresAt] = tokens.refreshExpiresAt
        }
    }

    fun findByAccessHash(hash: ByteArray): SessionRow? =
        SessionTable
            .selectAll()
            .where { SessionTable.accessHash eq hash }
            .singleOrNull()
            ?.let(::toRow)

    /** Locks the row: concurrent refreshes of one session are serialized. */
    fun findByRefreshHashForUpdate(hash: ByteArray): SessionRow? =
        SessionTable
            .selectAll()
            .where { SessionTable.refreshHash eq hash }
            .forUpdate()
            .singleOrNull()
            ?.let(::toRow)

    fun findByPreviousRefreshHashForUpdate(hash: ByteArray): SessionRow? =
        SessionTable
            .selectAll()
            .where { SessionTable.previousRefreshHash eq hash }
            .forUpdate()
            .singleOrNull()
            ?.let(::toRow)

    fun get(id: UUID): SessionRow? =
        SessionTable
            .selectAll()
            .where { SessionTable.id eq id }
            .singleOrNull()
            ?.let(::toRow)

    /** Sessions of the user that are neither revoked nor past their refresh expiry, most recently seen first. */
    fun listActive(
        userId: UUID,
        now: Instant,
    ): List<SessionRow> =
        SessionTable
            .selectAll()
            .where { (SessionTable.userId eq userId) and SessionTable.revokedAt.isNull() and (SessionTable.refreshExpiresAt greater now) }
            .orderBy(SessionTable.lastSeenAt, SortOrder.DESC)
            .map(::toRow)

    /**
     * Replaces the token pair. [previousRefreshHash] is the refresh token the client presented;
     * [rotatedAt] is when the grace window for it started.
     */
    fun rotate(
        id: UUID,
        tokens: TokenHashes,
        previousRefreshHash: ByteArray,
        rotatedAt: Instant,
    ) {
        SessionTable.update({ SessionTable.id eq id }) {
            it[accessHash] = tokens.accessHash
            it[accessExpiresAt] = tokens.accessExpiresAt
            it[refreshHash] = tokens.refreshHash
            it[refreshExpiresAt] = tokens.refreshExpiresAt
            it[SessionTable.previousRefreshHash] = previousRefreshHash
            it[SessionTable.rotatedAt] = rotatedAt
            it[accessUsedSinceRotation] = false
        }
    }

    fun touch(
        id: UUID,
        lastSeenAt: Instant?,
        markAccessUsed: Boolean,
    ) {
        if (lastSeenAt == null && !markAccessUsed) return
        SessionTable.update({ SessionTable.id eq id }) {
            if (lastSeenAt != null) it[SessionTable.lastSeenAt] = lastSeenAt
            if (markAccessUsed) it[accessUsedSinceRotation] = true
        }
    }

    /** Ends a session. Rows are never deleted; an already revoked session keeps its original time. */
    fun revoke(
        id: UUID,
        now: Instant,
    ) {
        SessionTable.update({ (SessionTable.id eq id) and SessionTable.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    fun revokeAllOfUser(
        userId: UUID,
        now: Instant,
    ) {
        SessionTable.update({ (SessionTable.userId eq userId) and SessionTable.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    private fun toRow(it: ResultRow) =
        SessionRow(
            id = it[SessionTable.id],
            userId = it[SessionTable.userId],
            memberId = it[SessionTable.memberId],
            householdId = it[SessionTable.householdId],
            deviceName = it[SessionTable.deviceName],
            platform = it[SessionTable.platform],
            createdAt = it[SessionTable.createdAt],
            lastSeenAt = it[SessionTable.lastSeenAt],
            accessExpiresAt = it[SessionTable.accessExpiresAt],
            refreshExpiresAt = it[SessionTable.refreshExpiresAt],
            rotatedAt = it[SessionTable.rotatedAt],
            accessUsedSinceRotation = it[SessionTable.accessUsedSinceRotation],
            revokedAt = it[SessionTable.revokedAt],
        )
}
