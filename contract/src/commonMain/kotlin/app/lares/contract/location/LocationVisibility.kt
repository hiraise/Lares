package app.lares.contract.location

import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel

/** The member who looks at the map. [canView] is `location.view`. */
data class VisibilityViewer(
    val memberId: String,
    val isAdmin: Boolean,
    val canView: Boolean,
    val groupIds: Set<String>,
)

/** The member being looked at. */
data class VisibilityTarget(
    val memberId: String,
    val groupIds: Set<String>,
)

/** Who sees whose location and how much (LOC-006, ADR-0025). Shared by the server and the client. */
object LocationVisibility {
    /**
     * Without `location.view` nothing; oneself and administrators everything; otherwise the widest level of the rules
     * whose subject matches the viewer and whose target matches the target. Rules of level NONE or with a party of kind
     * UNKNOWN match nothing.
     */
    fun level(
        viewer: VisibilityViewer,
        target: VisibilityTarget,
        rules: List<LocationRule>,
    ): VisibilityLevel {
        if (!viewer.canView) return VisibilityLevel.NONE
        if (viewer.memberId == target.memberId || viewer.isAdmin) return VisibilityLevel.HISTORY
        return rules
            .filter { it.subject.matches(viewer.memberId, viewer.groupIds) && it.target.matches(target.memberId, target.groupIds) }
            .maxOfOrNull { it.level } ?: VisibilityLevel.NONE
    }

    /** The rules of a new household: adults see everybody with history, kids see adults now. */
    fun defaultRules(
        adultsGroupId: String,
        kidsGroupId: String,
    ): List<LocationRuleInput> =
        listOf(
            LocationRuleInput(RuleParty(PartyKind.GROUP, adultsGroupId), RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY),
            LocationRuleInput(RuleParty(PartyKind.GROUP, kidsGroupId), RuleParty(PartyKind.GROUP, adultsGroupId), VisibilityLevel.CURRENT),
        )

    private fun RuleParty.matches(
        memberId: String,
        groupIds: Set<String>,
    ): Boolean =
        when (kind) {
            PartyKind.EVERYONE -> true
            PartyKind.GROUP -> id != null && id in groupIds
            PartyKind.MEMBER -> id == memberId
            PartyKind.UNKNOWN -> false
        }
}
