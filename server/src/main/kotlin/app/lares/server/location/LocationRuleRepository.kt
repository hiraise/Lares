package app.lares.server.location

import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.server.platform.newEntityId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

/** Stored visibility rules (ADR-0025). All functions run inside `dbQuery`; writers hold the household lock. */
object LocationRuleRepository {
    /** The household's rules in the order the administrator saved them. */
    fun all(householdId: UUID): List<LocationRule> =
        LocationRuleTable
            .selectAll()
            .where { LocationRuleTable.householdId eq householdId }
            .orderBy(LocationRuleTable.position to SortOrder.ASC, LocationRuleTable.id to SortOrder.ASC)
            .map(::toRule)

    /** Replaces all rules of the household; ids are new. The ids in [rules] are checked by the caller. */
    fun replace(
        householdId: UUID,
        rules: List<LocationRuleInput>,
        now: Instant,
    ) {
        LocationRuleTable.deleteWhere { LocationRuleTable.householdId eq householdId }
        rules.forEachIndexed { index, rule ->
            LocationRuleTable.insert {
                it[id] = newEntityId()
                it[LocationRuleTable.householdId] = householdId
                it[subjectKind] = rule.subject.kind.key
                it[subjectGroupId] = rule.subject.idIf(PartyKind.GROUP)
                it[subjectMemberId] = rule.subject.idIf(PartyKind.MEMBER)
                it[targetKind] = rule.target.kind.key
                it[targetGroupId] = rule.target.idIf(PartyKind.GROUP)
                it[targetMemberId] = rule.target.idIf(PartyKind.MEMBER)
                it[level] = rule.level.key
                it[position] = index
                it[createdAt] = now
            }
        }
    }

    /** Deletes the rules naming the group as subject or target (group delete, ADR-0025). */
    fun deleteNamingGroup(groupId: UUID) {
        LocationRuleTable.deleteWhere { (subjectGroupId eq groupId) or (targetGroupId eq groupId) }
    }

    /** Deletes the rules naming the member as subject or target (member removal, ADR-0025). */
    fun deleteNamingMember(memberId: UUID) {
        LocationRuleTable.deleteWhere { (subjectMemberId eq memberId) or (targetMemberId eq memberId) }
    }

    private fun RuleParty.idIf(kind: PartyKind): UUID? = if (this.kind == kind) id?.let(UUID::fromString) else null

    private fun toRule(row: ResultRow) =
        LocationRule(
            id = row[LocationRuleTable.id].toString(),
            subject =
                party(
                    row[LocationRuleTable.subjectKind],
                    row[LocationRuleTable.subjectGroupId],
                    row[LocationRuleTable.subjectMemberId],
                ),
            target =
                party(
                    row[LocationRuleTable.targetKind],
                    row[LocationRuleTable.targetGroupId],
                    row[LocationRuleTable.targetMemberId],
                ),
            level = VisibilityLevel.entries.firstOrNull { it.key == row[LocationRuleTable.level] } ?: VisibilityLevel.NONE,
        )

    private fun party(
        kind: String,
        groupId: UUID?,
        memberId: UUID?,
    ): RuleParty {
        val parsed = PartyKind.entries.firstOrNull { it.key == kind } ?: PartyKind.UNKNOWN
        return RuleParty(parsed, (groupId ?: memberId)?.toString())
    }
}
