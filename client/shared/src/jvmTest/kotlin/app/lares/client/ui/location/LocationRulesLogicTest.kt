package app.lares.client.ui.location

import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.location.LocationLimits
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Кто кого видит» as sentences and list editing (plan M4-10, LOC-006). */
class LocationRulesLogicTest {
    private val names =
        RuleNames.of(
            listOf(Triple("g-adults", "Взрослые", false), Triple("g-kids", "Дети", false), Triple("g-admins", "", true)),
            mapOf("m-granny" to "Бабушка", "m-anya" to "Аня"),
        )
    private val everyone = LocationRulesLogic.EVERYONE

    private fun group(id: String) = RuleParty(PartyKind.GROUP, id)

    private fun member(id: String) = RuleParty(PartyKind.MEMBER, id)

    private fun rule(
        key: Int,
        subject: RuleParty,
        target: RuleParty,
        level: VisibilityLevel = VisibilityLevel.CURRENT,
    ) = RuleDraft(key, subject, target, level)

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun both(text: UiText): Pair<String, String> =
        runBlocking {
            Locale.setDefault(Locale.ENGLISH)
            val en = text.load()
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val ru = text.load()
            Locale.setDefault(Locale.ENGLISH)
            en to ru
        }

    @Test
    fun `LOC-006 sentences in en and ru`() {
        assertEquals(
            "Взрослые see everybody · with history" to "Взрослые видят всех · с историей",
            both(LocationRulesLogic.sentence(rule(0, group("g-adults"), everyone, VisibilityLevel.HISTORY), names)),
        )
        assertEquals(
            "Дети see: Взрослые · where now" to "Дети видят: Взрослые · где сейчас",
            both(LocationRulesLogic.sentence(rule(1, group("g-kids"), group("g-adults")), names)),
        )
        assertEquals(
            "Бабушка sees: Аня · where now" to "Бабушка видит: Аня · где сейчас",
            both(LocationRulesLogic.sentence(rule(2, member("m-granny"), member("m-anya")), names)),
        )
        assertEquals(
            "Everybody sees: Administrators · where now" to "Все видят: Администраторы · где сейчас",
            both(LocationRulesLogic.sentence(rule(3, everyone, group("g-admins")), names)),
        )
        assertEquals(
            "Unknown sees everybody · where now" to "Неизвестно видит всех · где сейчас",
            both(LocationRulesLogic.sentence(rule(4, member("m-gone"), everyone), names)),
        )
    }

    @Test
    fun `LOC-006 add, edit and delete keep the other rows`() {
        val saved = LocationRulesLogic.fromServer(emptyList())
        val first = LocationRulesLogic.newDraft(saved).copy(subject = group("g-kids"), target = group("g-adults"))
        var rules = LocationRulesLogic.put(saved, first)
        val second = LocationRulesLogic.newDraft(rules).copy(subject = member("m-granny"))
        rules = LocationRulesLogic.put(rules, second)
        assertEquals(listOf(0, 1), rules.map { it.key })
        assertTrue(LocationRulesLogic.changed(rules, saved))

        rules = LocationRulesLogic.put(rules, first.copy(level = VisibilityLevel.HISTORY))
        assertEquals(listOf(VisibilityLevel.HISTORY, VisibilityLevel.CURRENT), rules.map { it.level }, "edited in place")

        rules = LocationRulesLogic.delete(rules, 0)
        assertEquals(listOf(member("m-granny")), rules.map { it.subject })
        assertFalse(LocationRulesLogic.changed(rules, rules))
    }

    @Test
    fun `LOC-006 no more than the limit`() {
        var rules = emptyList<RuleDraft>()
        repeat(LocationLimits.MAX_RULES + 1) { rules = LocationRulesLogic.put(rules, LocationRulesLogic.newDraft(rules)) }
        assertEquals(LocationLimits.MAX_RULES, rules.size)
        assertFalse(LocationRulesLogic.canAdd(rules))
    }

    @Test
    fun `LOC-006 validation errors go to their rows`() {
        val rules = listOf(rule(5, group("g-kids"), everyone), rule(9, member("m-gone"), everyone))
        val errors =
            LocationRulesLogic.rowErrors(
                rules,
                mapOf(
                    "rules.1.subject" to FieldErrorCode.INVALID_FORMAT,
                    "rules.0.level" to FieldErrorCode.INVALID_FORMAT,
                    "rules.7.target" to FieldErrorCode.INVALID_FORMAT,
                    "rules" to FieldErrorCode.OUT_OF_RANGE,
                ),
            )
        assertEquals(setOf(5, 9), errors.keys)
        assertEquals(
            "This group or member no longer exists" to "Этой группы или участника больше нет",
            both(errors.getValue(9)),
        )
    }
}
