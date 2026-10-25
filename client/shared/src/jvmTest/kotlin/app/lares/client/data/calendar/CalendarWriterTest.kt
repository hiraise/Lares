package app.lares.client.data.calendar

import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.client.sync.Materializer
import app.lares.contract.Ids
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalendarWriterTest {
    private val f = CalendarFixture()

    private fun d(
        day: Int,
        month: Int = 10,
    ) = LocalDate(2026, month, day)

    private val weekly =
        Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))

    // Mon 19 Oct, 17:00–18:00, weekly on Monday and Wednesday.
    private val dance =
        timedEvent("Танцы", d(19), LocalTime(17, 0), LocalTime(18, 0), setOf(P_ANYA))
            .copy(recurrence = weekly, place = "Зал", reminderMinutesBefore = 30)
    private val e = Ids.newId()

    private suspend fun fields(index: Int): JsonObject = LaresJson.parseToJsonElement(f.outbox()[index].fields!!).jsonObject

    private suspend fun summary() = f.outbox().map { it.type to it.entity_id }

    private suspend fun setUp(login: String = OLGA) {
        f.family()
        f.loginAs(login, *(if (login == ANYA) CAL_KID else CAL_ADULT))
        f.putEvent(e, dance)
    }

    @Test
    fun `CAL-001 create queues the event with me as creator`() =
        runTest {
            setUp()
            val id = f.calendarWriter.create(timedEvent(" Стоматолог ", d(22), LocalTime(16, 0), LocalTime(17, 0), createdBy = "ignored"))
            assertEquals(listOf(EntityTypes.EVENT to id), summary())
            assertEquals(OLGA, fields(0)["createdBy"]?.jsonPrimitive?.content)
            assertEquals("Стоматолог", fields(0)["title"]?.jsonPrimitive?.content)
        }

    @Test
    fun `CAL-015 update resets changed occurrences only when the schedule changes`() =
        runTest {
            setUp()
            f.putRow(EventOccurrenceData(e, d(26), cancelled = true))
            f.putRow(EventOccurrenceData(e, d(28)))
            f.calendarWriter.update(e, dance.copy(place = "Зал 2"))
            assertEquals(listOf(EntityTypes.EVENT to e), summary())
            assertEquals(setOf("place"), fields(0).keys)

            f.calendarWriter.update(e, dance.copy(place = "Зал 2", recurrence = weekly.copy(weekdays = setOf(DayOfWeek.MONDAY))))
            assertEquals(
                listOf(EntityTypes.EVENT to e, EntityTypes.EVENT to e, EntityTypes.EVENT_OCCURRENCE to Ids.occurrenceRowId(e, d(26))),
                summary(),
            )
            assertEquals(setOf("cancelled"), fields(2).keys)
            assertEquals(listOf(false, false), f.rows(e).map { it.cancelled != 0L })
        }

    @Test
    fun `CAL-015 edit occurrence writes the derived row with nulls for unchanged fields`() =
        runTest {
            setUp()
            f.calendarWriter.editOccurrence(
                e,
                d(28),
                OccurrenceChanges(
                    d(28),
                    d(28),
                    LocalTime(17, 0),
                    LocalTime(18, 0),
                    title = "Танцы",
                    place = "Зал 2",
                    participantIds = setOf(P_ANYA),
                ),
            )
            val rowId = Ids.occurrenceRowId(e, d(28))
            assertEquals(listOf(EntityTypes.EVENT_OCCURRENCE to rowId), summary())
            assertEquals(setOf("eventId", "occurrence", "cancelled", "place"), fields(0).keys)
            // A later change of another field sends only that field, so offline edits on two phones merge.
            f.calendarWriter.editOccurrence(
                e,
                d(28),
                OccurrenceChanges(startTime = LocalTime(18, 0), endTime = LocalTime(19, 0), place = "Зал 2"),
            )
            assertEquals(setOf("startDate", "endDate", "startTime", "endTime"), fields(1).keys)
            f.calendarWriter.cancelOccurrence(e, d(28))
            assertEquals(setOf("cancelled"), fields(2).keys)
            // Times must be a full set.
            val error =
                assertFailsWith<LocalValidationException> {
                    f.writer.upsert(
                        EntityTypes.EVENT_OCCURRENCE,
                        Ids.occurrenceRowId(e, d(2, 11)),
                        LaresJson
                            .encodeToJsonElement(EventOccurrenceData.serializer(), EventOccurrenceData(e, d(2, 11), startDate = d(2, 11)))
                            .jsonObject,
                    )
                }
            assertEquals(FieldErrorCode.INVALID_FORMAT, error.fields["endDate"])
            // A row id that is not the derived one is refused.
            val wrongId =
                assertFailsWith<LocalValidationException> {
                    f.writer.upsert(
                        EntityTypes.EVENT_OCCURRENCE,
                        Ids.newId(),
                        LaresJson
                            .encodeToJsonElement(
                                EventOccurrenceData.serializer(),
                                EventOccurrenceData(e, d(2, 11), cancelled = true),
                            ).jsonObject,
                    )
                }
            assertEquals(mapOf("id" to FieldErrorCode.INVALID_FORMAT), wrongId.fields)
        }

    @Test
    fun `CAL-015 this and following on the first date is a plain update`() =
        runTest {
            setUp()
            assertEquals(e, f.calendarWriter.editFollowing(e, d(19), dance.copy(title = "Танцы!")))
            assertEquals(listOf(EntityTypes.EVENT to e), summary())
            assertEquals(setOf("title"), fields(0).keys)
        }

    @Test
    fun `CAL-015 this and following on a later date splits the series`() =
        runTest {
            setUp()
            f.putRow(EventOccurrenceData(e, d(21), title = "До"))
            f.putRow(EventOccurrenceData(e, d(26), cancelled = true))
            f.putRow(EventOccurrenceData(e, d(28), place = "Там"))
            val newId =
                f.calendarWriter.editFollowing(
                    e,
                    d(26),
                    dance.copy(startDate = d(26), endDate = d(26), startTime = LocalTime(18, 0), endTime = LocalTime(19, 0)),
                )
            assertEquals(
                listOf(
                    EntityTypes.EVENT to newId,
                    EntityTypes.EVENT to e,
                    EntityTypes.EVENT_OCCURRENCE to Ids.occurrenceRowId(e, d(26)),
                    EntityTypes.EVENT_OCCURRENCE to Ids.occurrenceRowId(e, d(28)),
                ),
                summary(),
            )
            assertEquals(e, fields(0)["splitFrom"]?.jsonPrimitive?.content)
            assertEquals(mapOf("repeatUntil" to "2026-10-25"), fields(1).mapValues { it.value.jsonPrimitive.content })
            assertEquals(setOf("cancelled"), fields(2).keys)
            assertEquals(setOf("place"), fields(3).keys)
        }

    @Test
    fun `CAL-015 delete following deletes the series or ends it`() =
        runTest {
            setUp()
            f.putRow(EventOccurrenceData(e, d(28), place = "Там"))
            f.calendarWriter.deleteFollowing(e, d(26))
            assertEquals(listOf(EntityTypes.EVENT to e, EntityTypes.EVENT_OCCURRENCE to Ids.occurrenceRowId(e, d(28))), summary())
            assertEquals("2026-10-25", fields(0)["repeatUntil"]?.jsonPrimitive?.content)
            f.calendarWriter.deleteFollowing(e, d(19))
            assertEquals(Materializer.opName(MutationOp.DELETE), f.outbox().last().op)
        }

    @Test
    fun `CAL-006 my reminder and as in the event`() =
        runTest {
            setUp(ANYA)
            val rowId = Ids.reminderRowId(e, ANYA)
            f.calendarWriter.setMyReminder(e, null)
            f.calendarWriter.setMyReminder(e, 60)
            // Аня takes part: «Как в событии» writes the event's 30.
            f.calendarWriter.clearMyReminder(e)
            assertEquals(List(3) { EntityTypes.EVENT_REMINDER to rowId }, summary())
            assertEquals(setOf("eventId", "memberId"), fields(0).keys)
            assertEquals(listOf("60", "30"), listOf(fields(1), fields(2)).map { it["minutesBefore"]!!.jsonPrimitive.content })
        }

    @Test
    fun `CAL-006 as in the event writes none for someone the event does not concern`() =
        runTest {
            setUp(DIMA)
            f.calendarWriter.setMyReminder(e, 15)
            f.calendarWriter.clearMyReminder(e)
            assertEquals(setOf("minutesBefore"), fields(1).keys)
            assertEquals(
                null,
                f.localDb.read { event_reminderQueries.selectById(Ids.reminderRowId(e, DIMA)).executeAsOne() }.minutes_before,
            )
        }

    @Test
    fun `CAL-017 a kid cannot change an adult's event locally`() =
        runTest {
            setUp(ANYA)
            assertFailsWith<LocalForbiddenException> { f.calendarWriter.update(e, dance.copy(title = "Моё")) }
            assertFailsWith<LocalForbiddenException> { f.calendarWriter.editOccurrence(e, d(21), OccurrenceChanges(title = "Моё")) }
            assertFailsWith<LocalForbiddenException> { f.calendarWriter.cancelOccurrence(e, d(21)) }
            assertFailsWith<LocalForbiddenException> { f.calendarWriter.editFollowing(e, d(26), dance) }
            assertFailsWith<LocalForbiddenException> { f.calendarWriter.delete(e) }
            assertEquals(emptyList(), f.outbox())
            // Her own reminder is fine.
            f.calendarWriter.setMyReminder(e, 10)
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `CAL-015 child rows are never deleted locally`() =
        runTest {
            setUp()
            f.calendarWriter.cancelOccurrence(e, d(21))
            f.calendarWriter.setMyReminder(e, 5)
            assertFailsWith<LocalForbiddenException> { f.writer.delete(EntityTypes.EVENT_OCCURRENCE, Ids.occurrenceRowId(e, d(21))) }
            assertFailsWith<LocalForbiddenException> { f.writer.delete(EntityTypes.EVENT_REMINDER, Ids.reminderRowId(e, OLGA)) }
            assertEquals(2, f.outbox().size)
        }
}
