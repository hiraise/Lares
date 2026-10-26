package app.lares.client.ui.location

import app.lares.client.resources.Res
import app.lares.client.resources.group_administrators
import app.lares.client.resources.loc_level_current
import app.lares.client.resources.loc_level_history
import app.lares.client.resources.rule_everyone_sees
import app.lares.client.resources.rule_everyone_sees_all
import app.lares.client.resources.rule_group_sees
import app.lares.client.resources.rule_group_sees_all
import app.lares.client.resources.rule_member_sees
import app.lares.client.resources.rule_member_sees_all
import app.lares.client.resources.rule_unknown
import app.lares.client.resources.rules_error_party
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.location.LocationLimits

/** A rule being edited; [key] keeps a row stable while the list changes. */
data class RuleDraft(
    val key: Int,
    val subject: RuleParty,
    val target: RuleParty,
    val level: VisibilityLevel,
) {
    fun toInput() = LocationRuleInput(subject, target, level)
}

/** A party to pick in the edit sheet: everybody, a group or a member. */
data class PartyOption(
    val party: RuleParty,
    val name: UiText,
)

/** Names for the sentences: groups (the Administrators group labelled by the app) and members. */
data class RuleNames(
    val groups: Map<String, UiText> = emptyMap(),
    val members: Map<String, String> = emptyMap(),
) {
    fun of(party: RuleParty): UiText? =
        when (party.kind) {
            PartyKind.GROUP -> party.id?.let { groups[it] }
            PartyKind.MEMBER -> party.id?.let { members[it] }?.let(::raw)
            PartyKind.EVERYONE, PartyKind.UNKNOWN -> null
        }

    /** «Все», then the groups, then the members — the chips of «Кто» and «Кого». */
    fun options(everyone: UiText): List<PartyOption> =
        listOf(PartyOption(LocationRulesLogic.EVERYONE, everyone)) +
            groups.map { (id, name) -> PartyOption(RuleParty(PartyKind.GROUP, id), name) } +
            members.entries.sortedBy { it.value.lowercase() }.map { (id, name) -> PartyOption(RuleParty(PartyKind.MEMBER, id), raw(name)) }

    companion object {
        /** Groups in their stored order, the Administrators group under the app's label. */
        fun of(
            groups: List<Triple<String, String, Boolean>>,
            members: Map<String, String>,
        ) = RuleNames(
            groups.associate { (id, name, administrators) ->
                id to
                    if (administrators) uiText(Res.string.group_administrators) else raw(name)
            },
            members,
        )
    }
}

/**
 * «Геопозиция: кто кого видит» (plan M4-10, LOC-006, ADR-0025): rules as sentences, editing the list that one `PUT`
 * saves, and the server's validation errors mapped to rows.
 */
object LocationRulesLogic {
    val EVERYONE = RuleParty(PartyKind.EVERYONE)

    /** «Взрослые видят всех · с историей», «Дети видят: Взрослые · где сейчас», «Бабушка видит: Аня · где сейчас». */
    fun sentence(
        rule: RuleDraft,
        names: RuleNames,
    ): UiText {
        val level = uiText(if (rule.level == VisibilityLevel.HISTORY) Res.string.loc_level_history else Res.string.loc_level_current)
        val target = rule.target.kind == PartyKind.EVERYONE
        val whom = if (target) null else names.of(rule.target) ?: uiText(Res.string.rule_unknown)
        return when (rule.subject.kind) {
            PartyKind.EVERYONE -> {
                if (whom == null) uiText(Res.string.rule_everyone_sees_all, level) else uiText(Res.string.rule_everyone_sees, whom, level)
            }

            PartyKind.MEMBER -> {
                val who = names.of(rule.subject) ?: uiText(Res.string.rule_unknown)
                if (whom ==
                    null
                ) {
                    uiText(Res.string.rule_member_sees_all, who, level)
                } else {
                    uiText(Res.string.rule_member_sees, who, whom, level)
                }
            }

            PartyKind.GROUP, PartyKind.UNKNOWN -> {
                val who = names.of(rule.subject) ?: uiText(Res.string.rule_unknown)
                if (whom ==
                    null
                ) {
                    uiText(Res.string.rule_group_sees_all, who, level)
                } else {
                    uiText(Res.string.rule_group_sees, who, whom, level)
                }
            }
        }
    }

    fun fromServer(rules: List<LocationRule>): List<RuleDraft> = rules.mapIndexed { i, r -> RuleDraft(i, r.subject, r.target, r.level) }

    /** A new rule's defaults: everybody sees everybody where now; the admin picks. */
    fun newDraft(rules: List<RuleDraft>): RuleDraft = RuleDraft(nextKey(rules), EVERYONE, EVERYONE, VisibilityLevel.CURRENT)

    fun canAdd(rules: List<RuleDraft>): Boolean = rules.size < LocationLimits.MAX_RULES

    /** Adds [draft] or replaces the rule with its key. */
    fun put(
        rules: List<RuleDraft>,
        draft: RuleDraft,
    ): List<RuleDraft> =
        if (rules.any { it.key == draft.key }) {
            rules.map { if (it.key == draft.key) draft else it }
        } else if (canAdd(rules)) {
            rules + draft
        } else {
            rules
        }

    fun delete(
        rules: List<RuleDraft>,
        key: Int,
    ): List<RuleDraft> = rules.filterNot { it.key == key }

    /** Whether [rules] differ from what the server has (order does not matter for visibility). */
    fun changed(
        rules: List<RuleDraft>,
        saved: List<RuleDraft>,
    ): Boolean = rules.map { it.toInput() } != saved.map { it.toInput() }

    /**
     * `VALIDATION_FAILED` fields («rules.3.subject») → the row's key and its message: a gone group or member for the
     * parties, the field message otherwise. Fields that name no row are left out.
     */
    fun rowErrors(
        rules: List<RuleDraft>,
        fields: Map<String, FieldErrorCode>,
    ): Map<Int, UiText> =
        buildMap {
            for ((field, code) in fields) {
                val parts = field.split('.')
                if (parts.size < 2 || parts[0] != "rules") continue
                val rule = parts[1].toIntOrNull()?.let { rules.getOrNull(it) } ?: continue
                val party = parts.getOrNull(2) == "subject" || parts.getOrNull(2) == "target"
                if (rule.key !in this) put(rule.key, uiText(if (party) Res.string.rules_error_party else code.messageRes()))
            }
        }

    private fun nextKey(rules: List<RuleDraft>): Int = (rules.maxOfOrNull { it.key } ?: -1) + 1
}
