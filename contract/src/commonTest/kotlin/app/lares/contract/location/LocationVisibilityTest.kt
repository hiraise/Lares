package app.lares.contract.location

import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationVisibilityTest {
    private val adults = "g-adults"
    private val kids = "g-kids"
    private val defaults =
        LocationVisibility.defaultRules(adults, kids).mapIndexed { i, r -> LocationRule("r$i", r.subject, r.target, r.level) }

    private fun viewer(
        id: String,
        vararg groups: String,
        admin: Boolean = false,
        canView: Boolean = true,
    ) = VisibilityViewer(id, admin, canView, groups.toSet())

    private fun target(
        id: String,
        vararg groups: String,
    ) = VisibilityTarget(id, groups.toSet())

    private fun rule(
        subject: RuleParty,
        target: RuleParty,
        level: VisibilityLevel,
    ) = LocationRule("x", subject, target, level)

    @Test
    fun `LOC-006 oneself and administrators see history, nobody without location view`() {
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(viewer("k1", kids), target("k1", kids), emptyList()))
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(viewer("a1", admin = true), target("k1", kids), emptyList()))
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(viewer("k1", kids, canView = false), target("k1", kids), defaults))
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(viewer("a1", admin = true, canView = false), target("k1"), defaults))
    }

    @Test
    fun `LOC-006 default rules - adults see kids with history, kids see adults now, kids do not see kids`() {
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(viewer("a1", adults), target("k1", kids), defaults))
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(viewer("a1", adults), target("a2", adults), defaults))
        assertEquals(VisibilityLevel.CURRENT, LocationVisibility.level(viewer("k1", kids), target("a1", adults), defaults))
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(viewer("k1", kids), target("k2", kids), defaults))
    }

    @Test
    fun `LOC-006 member rule, widest of two rules, everyone subject`() {
        val k1 = viewer("k1", kids)
        val memberRule = rule(RuleParty(PartyKind.MEMBER, "k1"), RuleParty(PartyKind.MEMBER, "k2"), VisibilityLevel.CURRENT)
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(k1, target("k2", kids), emptyList()))
        assertEquals(VisibilityLevel.CURRENT, LocationVisibility.level(k1, target("k2", kids), listOf(memberRule)))
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(viewer("k3", kids), target("k2", kids), listOf(memberRule)))
        val wide = rule(RuleParty(PartyKind.GROUP, kids), RuleParty(PartyKind.GROUP, kids), VisibilityLevel.HISTORY)
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(k1, target("k2", kids), listOf(memberRule, wide)))
        assertEquals(VisibilityLevel.HISTORY, LocationVisibility.level(k1, target("k2", kids), listOf(wide, memberRule)))
        val everyone = rule(RuleParty(PartyKind.EVERYONE), RuleParty(PartyKind.MEMBER, "a1"), VisibilityLevel.CURRENT)
        assertEquals(VisibilityLevel.CURRENT, LocationVisibility.level(viewer("x"), target("a1"), listOf(everyone)))
    }

    @Test
    fun `LOC-006 rules naming unknown groups, unknown kinds or level none match nothing`() {
        val k1 = viewer("k1", kids)
        val rules =
            listOf(
                rule(RuleParty(PartyKind.GROUP, "g-gone"), RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY),
                rule(RuleParty(PartyKind.UNKNOWN, kids), RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY),
                rule(RuleParty(PartyKind.EVERYONE), RuleParty(PartyKind.UNKNOWN), VisibilityLevel.HISTORY),
                rule(RuleParty(PartyKind.GROUP), RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY),
                rule(RuleParty(PartyKind.EVERYONE), RuleParty(PartyKind.EVERYONE), VisibilityLevel.NONE),
            )
        assertEquals(VisibilityLevel.NONE, LocationVisibility.level(k1, target("k2", kids), rules))
    }
}
