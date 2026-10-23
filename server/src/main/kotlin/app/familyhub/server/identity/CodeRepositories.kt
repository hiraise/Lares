package app.familyhub.server.identity

import app.familyhub.contract.api.FamilyHubJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

data class InviteRow(
    val id: UUID,
    val householdId: UUID,
    val groupIds: Set<UUID>,
    val personId: UUID?,
    val createdByMemberId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val revokedAt: Instant?,
) {
    fun isUsable(now: Instant): Boolean = usedAt == null && revokedAt == null && expiresAt > now
}

object InviteRepository {
    private val idsSerializer = ListSerializer(String.serializer())

    fun create(
        id: UUID,
        householdId: UUID,
        codeHash: ByteArray,
        groupIds: Set<UUID>,
        personId: UUID?,
        createdByMemberId: UUID,
        now: Instant,
        expiresAt: Instant,
    ) {
        InviteTable.insert {
            it[InviteTable.id] = id
            it[InviteTable.householdId] = householdId
            it[InviteTable.codeHash] = codeHash
            it[InviteTable.groupIds] = FamilyHubJson.encodeToString(idsSerializer, groupIds.map(UUID::toString))
            it[InviteTable.personId] = personId
            it[InviteTable.createdByMemberId] = createdByMemberId
            it[createdAt] = now
            it[InviteTable.expiresAt] = expiresAt
        }
    }

    fun findByCodeHash(
        codeHash: ByteArray,
        forUpdate: Boolean = false,
    ): InviteRow? =
        InviteTable
            .selectAll()
            .where { InviteTable.codeHash eq codeHash }
            .let { if (forUpdate) it.forUpdate() else it }
            .singleOrNull()
            ?.let(::toRow)

    fun get(
        householdId: UUID,
        id: UUID,
    ): InviteRow? =
        InviteTable
            .selectAll()
            .where { (InviteTable.householdId eq householdId) and (InviteTable.id eq id) }
            .singleOrNull()
            ?.let(::toRow)

    /** Neither used, revoked nor expired; newest first. */
    fun listActive(
        householdId: UUID,
        now: Instant,
    ): List<InviteRow> =
        InviteTable
            .selectAll()
            .where {
                (InviteTable.householdId eq householdId) and InviteTable.usedAt.isNull() and InviteTable.revokedAt.isNull() and
                    (InviteTable.expiresAt greater now)
            }.orderBy(InviteTable.createdAt, SortOrder.DESC)
            .map(::toRow)

    fun markUsed(
        id: UUID,
        memberId: UUID,
        now: Instant,
    ) {
        InviteTable.update({ InviteTable.id eq id }) {
            it[usedAt] = now
            it[usedByMemberId] = memberId
        }
    }

    fun revoke(
        id: UUID,
        now: Instant,
    ) {
        InviteTable.update({ (InviteTable.id eq id) and InviteTable.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    private fun toRow(it: ResultRow) =
        InviteRow(
            id = it[InviteTable.id],
            householdId = it[InviteTable.householdId],
            groupIds = FamilyHubJson.decodeFromString(idsSerializer, it[InviteTable.groupIds]).mapTo(mutableSetOf(), UUID::fromString),
            personId = it[InviteTable.personId],
            createdByMemberId = it[InviteTable.createdByMemberId],
            createdAt = it[InviteTable.createdAt],
            expiresAt = it[InviteTable.expiresAt],
            usedAt = it[InviteTable.usedAt],
            revokedAt = it[InviteTable.revokedAt],
        )
}

data class LoginCodeRow(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

object LoginCodeRepository {
    fun create(
        id: UUID,
        householdId: UUID,
        memberId: UUID,
        codeHash: ByteArray,
        createdByMemberId: UUID,
        now: Instant,
        expiresAt: Instant,
    ) {
        LoginCodeTable.insert {
            it[LoginCodeTable.id] = id
            it[LoginCodeTable.householdId] = householdId
            it[LoginCodeTable.memberId] = memberId
            it[LoginCodeTable.codeHash] = codeHash
            it[LoginCodeTable.createdByMemberId] = createdByMemberId
            it[createdAt] = now
            it[LoginCodeTable.expiresAt] = expiresAt
        }
    }

    /** Locks the row: two devices exchanging the same code are serialized, so only one wins. */
    fun findByCodeHashForUpdate(codeHash: ByteArray): LoginCodeRow? =
        LoginCodeTable
            .selectAll()
            .where { LoginCodeTable.codeHash eq codeHash }
            .forUpdate()
            .singleOrNull()
            ?.let {
                LoginCodeRow(
                    id = it[LoginCodeTable.id],
                    householdId = it[LoginCodeTable.householdId],
                    memberId = it[LoginCodeTable.memberId],
                    expiresAt = it[LoginCodeTable.expiresAt],
                    usedAt = it[LoginCodeTable.usedAt],
                )
            }

    fun markUsed(
        id: UUID,
        now: Instant,
    ) {
        LoginCodeTable.update({ LoginCodeTable.id eq id }) { it[usedAt] = now }
    }
}
