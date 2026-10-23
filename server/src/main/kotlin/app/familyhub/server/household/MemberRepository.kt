package app.familyhub.server.household

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class MemberRow(
    val id: UUID,
    val householdId: UUID,
    val userId: UUID?,
    val personId: UUID,
    val accessVersion: Long,
    val seq: Long,
    val deleted: Boolean,
)

object MemberRepository {
    fun create(
        householdId: UUID,
        id: UUID,
        userId: UUID?,
        personId: UUID,
        seq: Long,
        now: Instant,
    ) {
        MemberTable.insert {
            it[MemberTable.id] = id
            it[MemberTable.householdId] = householdId
            it[MemberTable.userId] = userId
            it[MemberTable.personId] = personId
            it[MemberTable.seq] = seq
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    fun get(
        householdId: UUID,
        id: UUID,
    ): MemberRow? =
        MemberTable
            .selectAll()
            .where { (MemberTable.householdId eq householdId) and (MemberTable.id eq id) }
            .singleOrNull()
            ?.let(::toRow)

    /**
     * The active member of a user. M0 has one household per instance; with several, the oldest membership wins
     * until household switching exists.
     */
    fun findActiveByUser(userId: UUID): MemberRow? =
        MemberTable
            .selectAll()
            .where { (MemberTable.userId eq userId) and (MemberTable.deleted eq false) }
            .orderBy(MemberTable.createdAt)
            .limit(1)
            .singleOrNull()
            ?.let(::toRow)

    /** A change to the member's sync data (`MemberData`, e.g. `hasPassword`); the caller allocates [seq]. */
    fun stampSeq(
        householdId: UUID,
        id: UUID,
        seq: Long,
        now: Instant,
    ) {
        MemberTable.update({ (MemberTable.householdId eq householdId) and (MemberTable.id eq id) }) {
            it[MemberTable.seq] = seq
            it[updatedAt] = now
        }
    }

    /** Marks the member deleted; the caller allocates [seq] (the tombstone travels through sync). */
    fun tombstone(
        householdId: UUID,
        id: UUID,
        seq: Long,
        now: Instant,
    ) {
        MemberTable.update({ (MemberTable.householdId eq householdId) and (MemberTable.id eq id) }) {
            it[deleted] = true
            it[MemberTable.seq] = seq
            it[updatedAt] = now
        }
    }

    /** The subset of [ids] that are non-deleted members of the household. */
    fun activeIds(
        householdId: UUID,
        ids: Collection<UUID>,
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return MemberTable
            .select(MemberTable.id)
            .where { (MemberTable.householdId eq householdId) and (MemberTable.id inList ids) and (MemberTable.deleted eq false) }
            .mapTo(mutableSetOf()) { it[MemberTable.id] }
    }

    /** True if a non-deleted member already has this person as their profile. */
    fun isPersonLinked(
        householdId: UUID,
        personId: UUID,
    ): Boolean =
        MemberTable
            .selectAll()
            .where { (MemberTable.householdId eq householdId) and (MemberTable.personId eq personId) and (MemberTable.deleted eq false) }
            .limit(1)
            .any()

    fun bumpAccessVersion(
        householdId: UUID,
        memberIds: Collection<UUID>,
    ) {
        if (memberIds.isEmpty()) return
        MemberTable.update({ (MemberTable.householdId eq householdId) and (MemberTable.id inList memberIds) }) {
            it[accessVersion] = accessVersion + 1
        }
    }

    private fun toRow(it: org.jetbrains.exposed.v1.core.ResultRow) =
        MemberRow(
            id = it[MemberTable.id],
            householdId = it[MemberTable.householdId],
            userId = it[MemberTable.userId],
            personId = it[MemberTable.personId],
            accessVersion = it[MemberTable.accessVersion],
            seq = it[MemberTable.seq],
            deleted = it[MemberTable.deleted],
        )
}
