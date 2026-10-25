package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRules
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.location.LocationVisibility
import app.lares.contract.location.VisibilityTarget
import app.lares.contract.location.VisibilityViewer
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionEvaluator
import app.lares.contract.validation.Validators
import app.lares.server.household.GroupRepository
import app.lares.server.household.MemberRepository
import app.lares.server.household.MemberTable
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.nowForDb
import app.lares.server.platform.sync.lockHousehold
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock

/**
 * Who sees whose location (LOC-006, ADR-0025): the stored rules, their admin editing, and the levels of one viewer
 * evaluated with the shared `LocationVisibility`. Rules never change sync visibility, so no access version is bumped.
 */
class LocationVisibilityService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hub: LocationHub,
) {
    suspend fun get(householdId: UUID): LocationRules = db.dbQuery { LocationRules(rules(this, householdId)) }

    /**
     * Replaces all rules of the household (the caller has `household.manage`). Every group and member must be live and
     * of this household, else `VALIDATION_FAILED` on `rules.N.subject|target`. Tells the live channel after commit.
     */
    suspend fun put(
        householdId: UUID,
        request: PutLocationRulesRequest,
    ) {
        val shapeErrors = Validators.locationRules(request)
        if (shapeErrors.isNotEmpty()) throw invalid(shapeErrors)
        db.dbQuery {
            lockHousehold(householdId)
            val errors = referenceErrors(householdId, request)
            if (errors.isNotEmpty()) throw invalid(errors)
            LocationRuleRepository.replace(householdId, request.rules, clock.nowForDb())
            afterCommit { hub.publish(LocationHubEvent.VisibilityChanged(householdId)) }
        }
    }

    fun rules(
        tx: JdbcTransaction,
        householdId: UUID,
    ): List<LocationRule> = LocationRuleRepository.all(householdId)

    /** The viewer's level for every live member of the household (the viewer included). */
    fun levels(
        tx: JdbcTransaction,
        householdId: UUID,
        viewerMemberId: UUID,
    ): Map<UUID, VisibilityLevel> {
        val rules = rules(tx, householdId)
        val groups = GroupRepository.loadViews(householdId)
        val groupsOf = mutableMapOf<String, MutableSet<String>>()
        groups.filterNot { it.deleted }.forEach { group ->
            group.memberIds.forEach { groupsOf.getOrPut(it) { mutableSetOf() } += group.id }
        }
        val viewerId = viewerMemberId.toString()
        val permissions = PermissionEvaluator.evaluate(viewerId, groups)
        val viewer =
            VisibilityViewer(
                memberId = viewerId,
                isAdmin = permissions.isAdmin,
                canView = permissions.has(Permission.LOCATION_VIEW),
                groupIds = groupsOf[viewerId].orEmpty(),
            )
        return liveMemberIds(householdId).associateWith { memberId ->
            val target = VisibilityTarget(memberId.toString(), groupsOf[memberId.toString()].orEmpty())
            LocationVisibility.level(viewer, target, rules)
        }
    }

    /** The viewer's level for one member; NONE for a member that is not live in the household. */
    fun level(
        tx: JdbcTransaction,
        householdId: UUID,
        viewerMemberId: UUID,
        targetMemberId: UUID,
    ): VisibilityLevel = levels(tx, householdId, viewerMemberId)[targetMemberId] ?: VisibilityLevel.NONE

    private fun liveMemberIds(householdId: UUID): List<UUID> =
        MemberTable
            .select(MemberTable.id)
            .where { (MemberTable.householdId eq householdId) and (MemberTable.deleted eq false) }
            .orderBy(MemberTable.id)
            .map { it[MemberTable.id] }

    /** `rules.N.subject|target: INVALID_FORMAT` for every group or member id that is not live in the household. */
    private fun referenceErrors(
        householdId: UUID,
        request: PutLocationRulesRequest,
    ): Map<String, FieldErrorCode> {
        val parties = request.rules.flatMap { listOf(it.subject, it.target) }
        val groupIds = parties.idsOf(PartyKind.GROUP)
        val memberIds = parties.idsOf(PartyKind.MEMBER)
        val liveGroups = GroupRepository.activeIds(householdId, groupIds)
        val liveMembers = MemberRepository.activeIds(householdId, memberIds)

        fun RuleParty.isLive(): Boolean {
            val parsed = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return when (kind) {
                PartyKind.GROUP -> parsed in liveGroups
                PartyKind.MEMBER -> parsed in liveMembers
                else -> true
            }
        }
        val errors = linkedMapOf<String, FieldErrorCode>()
        request.rules.forEachIndexed { i, rule ->
            if (!rule.subject.isLive()) errors["rules.$i.subject"] = FieldErrorCode.INVALID_FORMAT
            if (!rule.target.isLive()) errors["rules.$i.target"] = FieldErrorCode.INVALID_FORMAT
        }
        return errors
    }

    private fun List<RuleParty>.idsOf(kind: PartyKind): Set<UUID> =
        filter { it.kind == kind }.mapNotNullTo(
            mutableSetOf(),
        ) { party -> party.id?.let { runCatching { UUID.fromString(it) }.getOrNull() } }

    private fun invalid(errors: Map<String, FieldErrorCode>) =
        ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.keys.joinToString()}", errors)
}
